use anyhow::Result;
use bytes::Bytes;
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
    /// Approximate total bytes currently held on disk (L2).
    ///
    /// Maintained incrementally (`+len` on store, `-len` on remove/clear) so the
    /// hot `store()` path can decide "are we over budget?" with a single atomic
    /// load instead of walking the whole cache directory.
    ///
    /// This is what makes `store()` O(1) instead of O(files in cache). Before,
    /// every single image write spawned a full `read_dir` + per-file `stat` +
    /// sort, so reading a 20-page gallery cost ~20 full scans of the cache dir.
    /// The counter only needs to be approximately right — it is a trigger,
    /// not an accounting record.
    ///
    /// `Arc`-wrapped purely so the background eviction task can own a handle to
    /// it without borrowing `self` (which isn't `'static`).
    disk_bytes: Arc<std::sync::atomic::AtomicU64>,
    /// Single-flight latch: true while an eviction pass is in flight, so a burst
    /// of concurrent `store()` calls schedules at most one scan.
    evicting: Arc<std::sync::atomic::AtomicBool>,
}

impl CacheEngine {
    /// Create a new CacheEngine.  The `disk_path` can be updated later via
    /// [`set_disk_path`] before any reads/writes are issued.
    pub fn new(disk_path: String) -> Self {
        Self {
            disk_path: std::sync::RwLock::new(PathBuf::from(disk_path)),
            disk_bytes: Arc::new(std::sync::atomic::AtomicU64::new(0)),
            evicting: Arc::new(std::sync::atomic::AtomicBool::new(false)),
        }
    }

    /// Create the L2 disk cache directory if it does not already exist, and seed
    /// the running byte counter with the size of what is already on disk.
    ///
    /// This is the only place that walks the directory; every later sizing
    /// decision uses [`CacheEngine::disk_bytes`].
    pub async fn init_disk_cache(&self) -> Result<()> {
        let path = self.disk_path.read().unwrap().clone();
        if !path.exists() {
            fs::create_dir_all(&path).await?;
        }
        let total = scan_total_bytes(&path).await.unwrap_or(0);
        self.disk_bytes
            .store(total, std::sync::atomic::Ordering::Relaxed);
        log::info!(
            "Disk cache seeded: {} MB already on disk",
            total / 1024 / 1024
        );
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
    ///
    /// Returns `Bytes` rather than a fresh `Vec<u8>` so callers can pass the
    /// body straight through without copying it (images are frequently several
    /// MB; the old `Arc<Vec<u8>>` → `.to_vec()` round trip copied every one).
    pub async fn get_cached(&self, url: &str) -> Result<Option<Bytes>> {
        // L2: disk — path computed under a brief ns-scale read-lock, then all I/O is lock-free.
        let disk_file = self.get_disk_file_path(url);
        if disk_file.exists() {
            // Read from disk with no lock held. `Bytes::from(Vec)` is a move,
            // not a copy.
            let bytes = Bytes::from(fs::read(&disk_file).await?);
            return Ok(Some(bytes));
        }

        Ok(None)
    }

    /// Disk cache lookup that only succeeds while the entry is younger than
    /// `max_age_secs`.
    ///
    /// For content that legitimately changes (gallery listings): an unbounded
    /// cache would silently keep serving a stale feed, while no cache at all
    /// means re-fetching the front page every time you come back from a gallery.
    /// Images and gallery metadata are stable enough for [`Self::get_cached`].
    pub async fn get_cached_within(&self, url: &str, max_age_secs: u64) -> Result<Option<Bytes>> {
        // Same locking discipline as `get_cached`: path under a brief read-lock,
        // every syscall after that runs with no lock held.
        let disk_file = self.get_disk_file_path(url);
        if !disk_file.exists() {
            return Ok(None);
        }

        if let Ok(meta) = fs::metadata(&disk_file).await {
            if let Ok(modified) = meta.modified() {
                if modified.elapsed().unwrap_or_default().as_secs() > max_age_secs {
                    // Stale — unlink it so the disk budget is not spent on
                    // entries we will never serve again.
                    let _ = fs::remove_file(&disk_file).await;
                    return Ok(None);
                }
            }
        }

        Ok(Some(Bytes::from(fs::read(&disk_file).await?)))
    }

    /// Persist fetched bytes to disk.
    ///
    /// HTML error pages are silently dropped so a transient failure cannot
    /// poison the cache and break future retries.
    ///
    /// The write runs fully lock-free. Sizing is tracked with an atomic counter,
    /// so the common case (still inside the budget) does **no** directory walk
    /// at all; only when the counter crosses the cap is a single scan scheduled,
    /// and concurrent callers cannot schedule more than one.
    pub async fn store(&self, url: &str, bytes: &[u8]) -> Result<()> {
        if looks_like_html_error(bytes) {
            return Ok(());
        }

        use std::sync::atomic::Ordering;

        // L2: disk write (no lock held — concurrent stores do not block each other)
        let disk_file = self.get_disk_file_path(url);
        let is_new = !disk_file.exists();

        // Write to a sibling temp file, then rename into place.
        //
        // `rename` within a directory is atomic, so another reader can never
        // observe a half-written file. That stopped being theoretical once image
        // fetches became abortable: the future can be dropped between the write
        // and the next line, which with a direct `fs::write` would leave a
        // truncated file that the cache would happily hand to Coil forever after.
        let tmp_file = disk_file.with_extension("tmp");
        fs::write(&tmp_file, bytes).await?;
        fs::rename(&tmp_file, &disk_file).await?;

        // Only count genuinely new files; overwriting an existing key would
        // otherwise inflate the counter until the next reconciling scan.
        if is_new {
            self.disk_bytes
                .fetch_add(bytes.len() as u64, Ordering::Relaxed);
        }

        // Cheap "should we care?" check — no I/O.
        if self.disk_bytes.load(Ordering::Relaxed) <= MAX_DISK_CACHE_BYTES {
            return Ok(());
        }

        // Over budget. Take the single-flight latch; if someone else already
        // holds it, let their pass do the work rather than stacking scans.
        if self
            .evicting
            .compare_exchange(false, true, Ordering::AcqRel, Ordering::Relaxed)
            .is_err()
        {
            return Ok(());
        }

        let disk_root = self.disk_path.read().unwrap().clone();
        let bytes_counter = Arc::clone(&self.disk_bytes);
        let latch = Arc::clone(&self.evicting);
        tokio::spawn(async move {
            match evict_disk_lru(&disk_root, MAX_DISK_CACHE_BYTES).await {
                Ok(final_total) => {
                    // Re-sync from reality: the counter only tracks writes made
                    // through this process, so a fresh scan is authoritative.
                    bytes_counter.store(final_total, Ordering::Relaxed);
                }
                Err(e) => log::warn!("Disk LRU eviction error: {}", e),
            }
            latch.store(false, Ordering::Release);
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

        // Keep the running size counter honest after a bulk delete.
        if freed_bytes > 0 {
            self.disk_bytes.fetch_sub(
                freed_bytes.min(
                    self.disk_bytes
                        .load(std::sync::atomic::Ordering::Relaxed),
                ),
                std::sync::atomic::Ordering::Relaxed,
            );
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
        match fs::metadata(&disk_file).await {
            Ok(meta) if meta.is_file() => {
                let size = meta.len();
                fs::remove_file(&disk_file).await?;
                self.disk_bytes.fetch_sub(
                    size.min(
                        self.disk_bytes
                            .load(std::sync::atomic::Ordering::Relaxed),
                    ),
                    std::sync::atomic::Ordering::Relaxed,
                );
            }
            _ => {}
        }
        Ok(())
    }
}

// ─── Disk Size Accounting ────────────────────────────────────────────────────

/// Sum the size of every regular file directly inside `dir`.
///
/// Only used to seed / re-sync [`CacheEngine`]'s running counter — never on the
/// `store()` hot path, which is the whole point of keeping the counter.
async fn scan_total_bytes(dir: &PathBuf) -> Result<u64> {
    let mut total: u64 = 0;
    let mut dir_iter = fs::read_dir(dir).await?;
    while let Some(entry) = dir_iter.next_entry().await? {
        if let Ok(meta) = entry.metadata().await {
            if meta.is_file() {
                total += meta.len();
            }
        }
    }
    Ok(total)
}

// ─── Disk LRU Eviction ───────────────────────────────────────────────────────

/// Scan `dir` and remove the oldest files (by mtime) until the total size
/// is at or below `max_bytes * 90%`.
///
/// Returns the resulting total size so the caller can re-sync its counter.
/// Runs in a background [`tokio::spawn`] task — never delays callers of
/// [`CacheEngine::store`].
async fn evict_disk_lru(dir: &PathBuf, max_bytes: u64) -> Result<u64> {
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
        return Ok(total_size);
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

    Ok(total_size)
}
