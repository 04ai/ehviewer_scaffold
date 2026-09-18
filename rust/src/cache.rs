use anyhow::Result;
use std::path::PathBuf;
use std::sync::Arc;
use tokio::fs;

// ─── Capacity Constants ───────────────────────────────────────────────────────


/// Maximum total size of on-disk (L2) cached image files.
/// Files are evicted in LRU order (oldest mtime first) when exceeded.
/// Eviction runs asynchronously after each write so it never delays the caller.
const MAX_DISK_CACHE_BYTES: u64 = 512 * 1024 * 1024; // 512 MB

// ─── HTML Error Guard ─────────────────────────────────────────────────────────

/// Refuse to cache bodies that look like an HTML error page
/// (e.g. a Cloudflare / 403 challenge that slips through with HTTP 200).
fn looks_like_html_error(bytes: &[u8]) -> bool {
    if bytes.is_empty() {
        return true;
    }
    if bytes.starts_with(b"<") {
        let head = String::from_utf8_lossy(&bytes[..bytes.len().min(512)]).to_ascii_lowercase();
        if head.contains("<html") || head.contains("<!doctype") || head.contains("<head") {
            return true;
        }
    }
    false
}


// ─── CacheEngine ─────────────────────────────────────────────────────────────

/// Two-level image cache with **interior mutability** — no outer `Mutex` needed.
///
/// ## Layers
/// - **L2 (disk)**: MD5-keyed files under `disk_path`, with a 512 MB LRU
///   eviction policy enforced asynchronously in a background task after every
///   `store()` call.
///
/// ## Concurrency
/// `CacheEngine` wraps in `Arc<CacheEngine>` with zero outer synchronization.
/// Disk reads and writes run fully concurrently with no lock held.
pub struct CacheEngine {
    /// Base directory for L2 disk files.
    /// `std::sync::RwLock` because `set_disk_path` is only called once at init
    /// (very brief exclusive lock), and every subsequent call is a shared read.
    disk_path: std::sync::RwLock<PathBuf>,
}

impl CacheEngine {
    /// Create a new CacheEngine.  The `disk_path` can be updated later via
    /// [`set_disk_path`] before any reads/writes are issued.
    pub fn new(disk_path: String) -> Self {
        Self {
            disk_path: std::sync::RwLock::new(PathBuf::from(disk_path)),
        }
    }

    /// Create the L2 disk cache directory if it does not already exist.
    pub async fn init_disk_cache(&self) -> Result<()> {
        let path = self.disk_path.read().unwrap().clone();
        if !path.exists() {
            fs::create_dir_all(&path).await?;
        }
        Ok(())
    }

    /// Update the L2 disk cache base directory (called once during init).
    pub fn set_disk_path(&self, path: String) {
        *self.disk_path.write().unwrap() = PathBuf::from(path);
    }

    /// Compute the disk file path for a given URL (hash of the URL bytes).
    /// The `std::RwLock` read is held only for the `PathBuf::join` call (ns-scale).
    pub fn get_disk_file_path(&self, url: &str) -> PathBuf {
        let file_name = format!("{:x}", md5::compute(url.as_bytes()));
        self.disk_path.read().unwrap().join(file_name)
    }

    /// Disk cache lookup.
    /// Disk I/O runs with **no lock held**, so a slow read never blocks others.
    pub async fn get_cached(&self, url: &str) -> Result<Option<Arc<Vec<u8>>>> {
        // L2: disk — path computed under a brief ns-scale read-lock, then all I/O is lock-free.
        let disk_file = self.get_disk_file_path(url);
        if disk_file.exists() {
            // Read from disk with no lock held.
            let bytes = Arc::new(fs::read(&disk_file).await?);
            return Ok(Some(bytes));
        }

        Ok(None)
    }

    /// Persist fetched bytes to disk.
    ///
    /// HTML error pages are silently dropped so a transient failure cannot
    /// poison the cache and break future retries.
    ///
    /// The write runs fully lock-free. After the write, disk LRU eviction is
    /// spawned as a fire-and-forget task.
    pub async fn store(&self, url: &str, bytes: &[u8]) -> Result<()> {
        if looks_like_html_error(bytes) {
            return Ok(());
        }

        // L2: disk write (no lock held — concurrent stores do not block each other)
        let disk_file = self.get_disk_file_path(url);
        fs::write(&disk_file, bytes).await?;

        // Enforce the disk size budget asynchronously.
        // Clone the path while holding the read-lock for ns; then release.
        let disk_root = self.disk_path.read().unwrap().clone();
        tokio::spawn(async move {
            if let Err(e) = evict_disk_lru(&disk_root, MAX_DISK_CACHE_BYTES).await {
                log::warn!("Disk LRU eviction error: {}", e);
            }
        });

        Ok(())
    }

    /// Clear disk cache files older than `older_than_days` days.
    /// This is called from Kotlin's advanced settings "auto-clean" feature.
    pub async fn clear_expired(&self, older_than_days: u64) -> Result<u64> {
        let disk_root = self.disk_path.read().unwrap().clone();
        let threshold_secs = older_than_days * 24 * 3600;
        let now = std::time::SystemTime::now();
        let mut freed_bytes: u64 = 0;
        let mut dir_iter = match fs::read_dir(&disk_root).await {
            Ok(d) => d,
            Err(_) => return Ok(0), // cache dir doesn't exist yet — nothing to clear
        };
        while let Ok(Some(entry)) = dir_iter.next_entry().await {
            let meta = match entry.metadata().await {
                Ok(m) => m,
                Err(_) => continue,
            };
            if !meta.is_file() { continue; }
            let age_secs = now.duration_since(
                meta.modified().unwrap_or(std::time::SystemTime::UNIX_EPOCH)
            ).map(|d| d.as_secs()).unwrap_or(0);
            if age_secs >= threshold_secs {
                let size = meta.len();
                if fs::remove_file(entry.path()).await.is_ok() {
                    freed_bytes += size;
                }
            }
        }

        log::info!("clear_expired({}d): freed {} MB", older_than_days, freed_bytes / 1024 / 1024);
        Ok(freed_bytes)
    }

    /// Drop a URL from disk.
    ///
    /// Called by the downloader after a gallery finishes — downloaded images are
    /// safe in the download directory, so their duplicate HTTP-cache copies can go.
    pub async fn remove(&self, url: &str) -> Result<()> {
        let disk_file = self.get_disk_file_path(url);
        if disk_file.exists() {
            fs::remove_file(&disk_file).await?;
        }
        Ok(())
    }
}

// ─── Disk LRU Eviction ───────────────────────────────────────────────────────

/// Scan `dir` and remove the oldest files (by mtime) until the total size
/// is at or below `max_bytes * 90%`.  Skips the scan entirely when already
/// under budget.  Runs in a background [`tokio::spawn`] task — never delays
/// callers of [`CacheEngine::store`].
async fn evict_disk_lru(dir: &PathBuf, max_bytes: u64) -> Result<()> {
    let mut entries: Vec<(PathBuf, u64, std::time::SystemTime)> = Vec::new();
    let mut total_size: u64 = 0;

    let mut dir_iter = fs::read_dir(dir).await?;
    while let Some(entry) = dir_iter.next_entry().await? {
        let meta = entry.metadata().await?;
        if meta.is_file() {
            let mtime = meta
                .modified()
                .unwrap_or(std::time::SystemTime::UNIX_EPOCH);
            total_size += meta.len();
            entries.push((entry.path(), meta.len(), mtime));
        }
    }

    // Fast path: already within budget — nothing to evict.
    if total_size <= max_bytes {
        return Ok(());
    }

    // Sort oldest-first (least recently modified = LRU).
    entries.sort_unstable_by_key(|(_, _, mtime)| *mtime);

    // Shrink to 90% of limit to amortize future eviction scans.
    let target = max_bytes / 10 * 9;
    let mut freed: u64 = 0;
    for (path, size, _) in entries {
        if total_size <= target {
            break;
        }
        if let Ok(()) = fs::remove_file(&path).await {
            total_size = total_size.saturating_sub(size);
            freed += size;
            log::info!(
                "Disk LRU evict: {} KB freed ({:?})",
                size / 1024,
                path.file_name().unwrap_or_default()
            );
        }
    }

    if freed > 0 {
        log::info!(
            "Disk cache after eviction: {} MB / {} MB cap",
            total_size / 1024 / 1024,
            max_bytes / 1024 / 1024
        );
    }

    Ok(())
}
