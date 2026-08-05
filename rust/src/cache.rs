use anyhow::Result;
use lru::LruCache;
use std::num::NonZeroUsize;
use std::sync::{Arc, Mutex};
use tokio::fs;
use std::path::PathBuf;

/// Upper bound on total bytes held in the in-memory (L1) cache.
/// Kept modest so low-end Android devices don't get OOM-killed;
/// individual images larger than this are skipped (not cached).
const MAX_MEM_CACHE_BYTES: usize = 96 * 1024 * 1024;

/// Cheap guard: refuse to cache bodies that look like an HTML error page
/// (e.g. a Cloudflare/403 challenge that slips through with status 200).
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

/// L1 (memory) cache: LRU with a byte-based budget.
struct MemCache {
    entries: LruCache<String, Arc<Vec<u8>>>,
    total_bytes: usize,
}

impl MemCache {
    fn get(&mut self, url: &str) -> Option<Arc<Vec<u8>>> {
        self.entries.get(url).cloned()
    }

    fn put(&mut self, url: String, bytes: Arc<Vec<u8>>) {
        let size = bytes.len();
        if size > MAX_MEM_CACHE_BYTES {
            return;
        }
        if let Some(evicted) = self.entries.put(url, bytes) {
            self.total_bytes = self.total_bytes.saturating_sub(evicted.len());
        }
        self.total_bytes += size;
        while self.total_bytes > MAX_MEM_CACHE_BYTES {
            match self.entries.pop_lru() {
                Some((_, ev)) => self.total_bytes = self.total_bytes.saturating_sub(ev.len()),
                None => break,
            }
        }
    }

    fn pop(&mut self, url: &str) -> Option<Arc<Vec<u8>>> {
        let evicted = self.entries.pop(url)?;
        self.total_bytes = self.total_bytes.saturating_sub(evicted.len());
        Some(evicted)
    }
}

pub struct CacheEngine {
    disk_path: PathBuf,
    mem_cache: Mutex<MemCache>,
}

impl CacheEngine {
    pub fn new(capacity: usize, disk_path: String) -> Self {
        let capacity = NonZeroUsize::new(capacity.max(1)).unwrap();
        Self {
            disk_path: PathBuf::from(disk_path),
            mem_cache: Mutex::new(MemCache {
                entries: LruCache::new(capacity),
                total_bytes: 0,
            }),
        }
    }

    pub async fn init_disk_cache(&self) -> Result<()> {
        if !self.disk_path.exists() {
            fs::create_dir_all(&self.disk_path).await?;
        }
        Ok(())
    }

    pub fn set_disk_path(&mut self, path: String) {
        self.disk_path = PathBuf::from(path);
    }

    fn get_disk_file_path(&self, url: &str) -> PathBuf {
        let file_name = format!("{:x}", md5::compute(url.as_bytes()));
        self.disk_path.join(file_name)
    }

    /// L1 (memory) + L2 (disk) cache lookup.
    /// Network access is intentionally NOT performed while holding the
    /// global cache lock, so slow fetches cannot block other image loads.
    pub async fn get_cached(&self, url: &str) -> Result<Option<Arc<Vec<u8>>>> {
        if let Some(bytes) = self.mem_cache.lock().unwrap_or_else(|e| e.into_inner()).get(url) {
            return Ok(Some(bytes));
        }

        let disk_file = self.get_disk_file_path(url);
        if disk_file.exists() {
            let bytes = Arc::new(fs::read(&disk_file).await?);
            self.mem_cache
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .put(url.to_string(), bytes.clone());
            return Ok(Some(bytes));
        }

        Ok(None)
    }

    /// Persist fetched bytes to memory + disk. HTML error pages are never
    /// cached so a transient failure cannot poison the cache and break
    /// future retries.
    pub async fn store(&self, url: &str, bytes: &[u8]) -> Result<()> {
        if looks_like_html_error(bytes) {
            return Ok(());
        }
        let arc = Arc::new(bytes.to_vec());
        self.mem_cache
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .put(url.to_string(), arc);
        let disk_file = self.get_disk_file_path(url);
        fs::write(&disk_file, bytes).await?;
        Ok(())
    }

    /// Drop a URL from both L1 (memory) and L2 (disk).
    /// Used after a download completes: the images are safe on disk in the
    /// download directory, so their duplicate HTTP-cache copies can go.
    pub async fn remove(&self, url: &str) -> Result<()> {
        self.mem_cache
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .pop(url);
        let disk_file = self.get_disk_file_path(url);
        if disk_file.exists() {
            fs::remove_file(&disk_file).await?;
        }
        Ok(())
    }
}
