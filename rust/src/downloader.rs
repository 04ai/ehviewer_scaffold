use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU32, AtomicUsize, Ordering};
use std::sync::Arc;
use tokio::sync::{Mutex, RwLock};
use serde::{Deserialize, Serialize};
use lazy_static::lazy_static;

use crate::api::{resolve_image_url, get_image};
use futures::StreamExt;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DownloadTask {
    pub gid: String,
    pub token: String,
    pub title: String,
    pub total_pages: u32,
    pub downloaded_pages: u32,
    pub status: i32, // 0 = stopped, 1 = downloading, 2 = completed, -1 = error
    pub error_msg: Option<String>,
}

/// On-disk record: the task plus the viewer URLs needed to resume.
#[derive(Debug, Clone, Serialize, Deserialize)]
struct PersistedDownload {
    #[serde(flatten)]
    task: DownloadTask,
    image_urls: Vec<String>,
}

lazy_static! {
    /// UI-visible queue + per-gallery viewer URLs (needed for resume).
    static ref QUEUE: Arc<RwLock<HashMap<String, (DownloadTask, Vec<String>)>>> = Arc::new(RwLock::new(HashMap::new()));
    /// active_tasks tracks if a download task should stop (false = stop)
    static ref ACTIVE_TASKS: Arc<Mutex<HashMap<String, bool>>> = Arc::new(Mutex::new(HashMap::new()));
    /// Where downloaded images land on disk (set once from init_backend).
    static ref DOWNLOAD_DIR: Arc<Mutex<Option<PathBuf>>> = Arc::new(Mutex::new(None));
    /// How many galleries are downloading right now (replaces a fixed
    /// semaphore so the user-facing concurrency setting can change at runtime).
    static ref ACTIVE_DOWNLOADS: Arc<AtomicUsize> = Arc::new(AtomicUsize::new(0));
    /// User-configured concurrency cap (1..=8); defaults to MAX_CONCURRENT_DOWNLOADS.
    static ref CONCURRENCY_LIMIT: Arc<AtomicUsize> = Arc::new(AtomicUsize::new(MAX_CONCURRENT_DOWNLOADS));
    /// Serializes writes to downloads.json (the worker runs pages in parallel).
    static ref PERSIST_LOCK: Arc<Mutex<()>> = Arc::new(Mutex::new(()));
}

const QUEUE_FILE: &str = "downloads.json";
const MAX_CONCURRENT_DOWNLOADS: usize = 2;
/// Pages of one gallery that are fetched in parallel inside a worker.
const PAGE_CONCURRENCY: usize = 4;

/// Initialize the downloader: create the download directory and restore any
/// previously persisted tasks. Restored tasks that were mid-flight (status 1)
/// are downgraded to "stopped" because their worker is gone — the UI can
/// resume them explicitly.
pub async fn init_downloader(parent_dir: &str) {
    let dir = PathBuf::from(parent_dir).join("eh_downloads");
    let _ = tokio::fs::create_dir_all(&dir).await;

    {
        let mut d = DOWNLOAD_DIR.lock().await;
        *d = Some(dir);
    }

    let queue_file = DOWNLOAD_DIR.lock().await.as_ref().map(|d| d.join(QUEUE_FILE));
    let Some(queue_file) = queue_file else { return; };
    let Ok(data) = tokio::fs::read(&queue_file).await else { return };
    let Ok(persisted) = serde_json::from_slice::<Vec<PersistedDownload>>(&data) else { return };

    let mut q = QUEUE.write().await;
    for mut p in persisted {
        if p.task.status == 1 {
            p.task.status = 0; // process died while downloading
        }
        q.insert(p.task.gid.clone(), (p.task, p.image_urls));
    }
}

async fn persist() {
    let dir = DOWNLOAD_DIR.lock().await.as_ref().map(|d| d.clone());
    let Some(dir) = dir else { return };
    let file = dir.join(QUEUE_FILE);

    let tasks = {
        let q = QUEUE.read().await;
        q.iter()
            .map(|(_, (t, urls))| PersistedDownload {
                task: t.clone(),
                image_urls: urls.clone(),
            })
            .collect::<Vec<_>>()
    };

    if let Ok(json) = serde_json::to_vec(&tasks) {
        let _guard = PERSIST_LOCK.lock().await;
        let _ = tokio::fs::write(&file, json).await;
    }
}

/// Detect a file extension from image magic bytes (JPG/PNG/GIF/WebP).
fn detect_ext(bytes: &[u8]) -> &'static str {
    if bytes.starts_with(&[0xFF, 0xD8]) {
        "jpg"
    } else if bytes.starts_with(b"\x89PNG\r\n\x1a\n") {
        "png"
    } else if bytes.starts_with(b"GIF8") {
        "gif"
    } else if bytes.len() > 12 && &bytes[0..4] == b"RIFF" && &bytes[8..12] == b"WEBP" {
        "webp"
    } else {
        "bin"
    }
}

/// Write one page (0-indexed) under the gallery's download directory.
async fn save_page(gid: &str, page_idx: u32, bytes: &[u8]) -> anyhow::Result<PathBuf> {
    let dir = DOWNLOAD_DIR.lock().await.as_ref().map(|d| d.clone());
    let Some(base) = dir else {
        anyhow::bail!("downloader not initialized");
    };

    let gallery_dir = base.join(gid);
    tokio::fs::create_dir_all(&gallery_dir).await?;
    let file = gallery_dir.join(format!("page_{}.{}", page_idx + 1, detect_ext(bytes)));
    tokio::fs::write(&file, bytes).await?;
    Ok(file)
}

/// Check whether a page (0-indexed) already has a file on disk, so a resumed
/// run can skip pages saved by a previous run without re-hitting the network
/// (even if the persisted page counter drifted).
fn page_file_exists(base: &Path, gid: &str, page_idx: u32) -> bool {
    let gallery_dir = base.join(gid);
    ["jpg", "png", "gif", "webp", "bin"].iter().any(|ext| {
        gallery_dir.join(format!("page_{}.{}", page_idx + 1, ext)).exists()
    })
}

/// Start (or resume) a download. `image_urls` carries page 0's viewer URLs
/// (at most 20); when `total_pages` is larger — or `total_pages == 0` means
/// "collect everything" — the missing ?p=N pages are fetched first so the
/// whole gallery is queued, not just the first page.
pub async fn start_download(
    gid: String,
    token: String,
    title: String,
    image_urls: Vec<String>,
    total_pages: u32,
) -> anyhow::Result<()> {
    // Phase 1 (queue lock): decide the resume point and whether the URL list
    // is truncated. The lock is released before any network I/O below, so the
    // UI keeps responding while the page list is being collected.
    let plan: Option<(u32, Vec<String>, String, String, bool)> = {
        let queue = QUEUE.read().await;
        match queue.get(&gid) {
            Some((t, stored_urls)) => {
                if t.status == 1 {
                    return Ok(()); // worker already running
                }
                if t.downloaded_pages >= t.total_pages {
                    return Ok(()); // already completed
                }
                let urls = if image_urls.is_empty() {
                    stored_urls.clone()
                } else {
                    image_urls
                };
                // Tasks created before pagination existed stored only page 0
                // (total == urls == 20). Treat that as truncated so a resume
                // self-heals by collecting until the site stops yielding pages.
                let truncated = t.total_pages <= 20 && urls.len() == 20;
                Some((t.downloaded_pages, urls, t.token.clone(), t.title.clone(), truncated))
            }
            None => {
                if image_urls.is_empty() {
                    anyhow::bail!("没有可续传的下载任务（gid={}）", gid);
                }
                Some((0, image_urls, token, title, false))
            }
        }
    };
    let Some((resume_at, mut urls, token, title, truncated)) = plan else {
        return Ok(());
    };

    // Phase 2 (no locks): fetch the remaining viewer pages if needed.
    let need_collect = (urls.len() as u32) < total_pages || total_pages == 0 || truncated;
    if need_collect {
        let target = if total_pages == 0 || truncated {
            u32::MAX // collect until the site stops yielding new URLs
        } else {
            total_pages
        };
        crate::api::collect_viewer_urls(&gid, &token, &mut urls, target)
            .await
            .map_err(|e| anyhow::anyhow!("收集图片列表失败: {}", e))?;
    }

    // Phase 3 (queue lock): insert the task with the complete URL list.
    // Re-check the status under the write lock: if a concurrent start (e.g.
    // double-tap on 继续) already spawned a worker, do nothing.
    let resume_at = {
        let mut queue = QUEUE.write().await;
        if let Some((t, _)) = queue.get(&gid) {
            if t.status == 1 {
                return Ok(()); // another call already started it
            }
        }
        let total = urls.len() as u32;
        queue.insert(
            gid.clone(),
            (
                DownloadTask {
                    gid: gid.clone(),
                    token,
                    title,
                    total_pages: total,
                    downloaded_pages: resume_at,
                    status: 1, // downloading
                    error_msg: None,
                },
                urls,
            ),
        );
        resume_at
    };
    persist().await;

    {
        let mut active = ACTIVE_TASKS.lock().await;
        active.insert(gid.clone(), true);
    }

    tokio::spawn(async move {
        run_download_worker(gid.clone(), resume_at).await;
    });

    Ok(())
}

pub async fn pause_download(gid: String) -> anyhow::Result<()> {
    {
        let mut active = ACTIVE_TASKS.lock().await;
        if let Some(status) = active.get_mut(&gid) {
            *status = false; // signal to stop
        }
    }

    {
        let mut queue = QUEUE.write().await;
        if let Some((task, _)) = queue.get_mut(&gid) {
            if task.status == 1 {
                task.status = 0; // stopped
            }
        }
    }
    persist().await;
    Ok(())
}

pub async fn get_all_downloads() -> Vec<DownloadTask> {
    let queue = QUEUE.read().await;
    queue.values().map(|(t, _)| t.clone()).collect()
}

/// Delete a download task completely: signal any running worker to stop,
/// drop the task from the queue (so it neither shows up nor re-persists),
/// and remove its downloaded files from disk.
pub async fn delete_download(gid: String) {
    // Stop a worker that is currently mid-flight.
    {
        let mut active = ACTIVE_TASKS.lock().await;
        active.insert(gid.clone(), false);
    }
    {
        let mut queue = QUEUE.write().await;
        queue.remove(&gid);
    }
    persist().await;

    let base = DOWNLOAD_DIR.lock().await.as_ref().map(|d| d.clone());
    if let Some(base) = base {
        let _ = tokio::fs::remove_dir_all(base.join(&gid)).await;
    }
}

/// Directory where .torrent files land (eh_downloads/torrents).
pub async fn torrents_dir() -> Option<PathBuf> {
    DOWNLOAD_DIR.lock().await.as_ref().map(|d| d.join("torrents"))
}

/// Change the max number of galleries downloading at once (1..=8). Applies to
/// workers that have not yet acquired a slot; running workers finish in place.
pub async fn set_concurrency(n: u32) {
    CONCURRENCY_LIMIT.store(n.clamp(1, 8) as usize, Ordering::Relaxed);
    log::info!(
        "Download concurrency set to {}",
        CONCURRENCY_LIMIT.load(Ordering::Relaxed)
    );
}

/// Point the downloader at a new directory (used by the download-path setting).
pub async fn set_download_dir(path: String) -> anyhow::Result<()> {
    let dir = PathBuf::from(&path);
    tokio::fs::create_dir_all(&dir).await?;
    *DOWNLOAD_DIR.lock().await = Some(dir);
    log::info!("Download directory set to {}", path);
    Ok(())
}

/// Current download directory, if initialized.
pub async fn get_download_dir() -> Option<String> {
    DOWNLOAD_DIR
        .lock()
        .await
        .as_ref()
        .map(|d| d.to_string_lossy().to_string())
}

/// Block until fewer than the configured limit of workers are running, then
/// register this worker (replaces a fixed-capacity semaphore).
async fn acquire_slot() {
    loop {
        if ACTIVE_DOWNLOADS.load(Ordering::Relaxed)
            < CONCURRENCY_LIMIT.load(Ordering::Relaxed)
        {
            break;
        }
        tokio::time::sleep(std::time::Duration::from_millis(150)).await;
    }
    ACTIVE_DOWNLOADS.fetch_add(1, Ordering::Relaxed);
}

/// Mirror the in-flight page counter into the queue and persist. Called after
/// every completed page so the UI (3s poll) sees live progress.
async fn sync_progress(gid: &str, downloaded: &AtomicU32) {
    let is_active = {
        let active = ACTIVE_TASKS.lock().await;
        active.get(gid).copied().unwrap_or(false)
    };
    {
        let mut queue = QUEUE.write().await;
        if let Some((task, _)) = queue.get_mut(gid) {
            let now = downloaded.load(Ordering::Relaxed);
            if now > task.downloaded_pages {
                task.downloaded_pages = now;
            }
            // Only mark completed while the worker is still active: a pause
            // that lands while the last page is in-flight must not flip a
            // stopped task to "done".
            if is_active && task.downloaded_pages >= task.total_pages {
                task.status = 2; // completed
            }
        }
    }
    persist().await;
}

async fn run_download_worker(gid: String, start_at: u32) {
    // Wait for a slot: at most MAX_CONCURRENT_DOWNLOADS workers download at
    // once; the permit is held until this gallery finishes or is paused.
    acquire_slot().await;

    let (urls, total_pages) = {
        let queue = QUEUE.read().await;
        match queue.get(&gid) {
            Some((_, urls)) => (urls.clone(), urls.len() as u32),
            None => {
                ACTIVE_DOWNLOADS.fetch_sub(1, Ordering::Relaxed);
                return; // task no longer tracked
            }
        }
    };
    let base_dir = DOWNLOAD_DIR.lock().await.as_ref().map(|d| d.clone());

    let downloaded = Arc::new(AtomicU32::new(start_at));
    let failures = Arc::new(AtomicU32::new(0));
    let stop_requested = Arc::new(AtomicBool::new(false));
    let resolved_urls = Arc::new(Mutex::new(Vec::<String>::new()));
    let last_error = Arc::new(Mutex::new(None::<String>));

    // Scan every index, not just [start_at..]: files already on disk are
    // skipped by page_file_exists, so a resume with gaps from a previous
    // interrupted run heals itself without trusting the persisted counter.
    let pages: Vec<u32> = (0..total_pages).collect();
    futures::stream::iter(pages)
        .map(|i| {
            let gid = gid.clone();
            let urls = urls.clone();
            let base_dir = base_dir.clone();
            let downloaded = Arc::clone(&downloaded);
            let failures = Arc::clone(&failures);
            let stop_requested = Arc::clone(&stop_requested);
            let resolved_urls = Arc::clone(&resolved_urls);
            let last_error = Arc::clone(&last_error);
            async move {
                if stop_requested.load(Ordering::Relaxed) {
                    return;
                }
                let is_active = {
                    let active = ACTIVE_TASKS.lock().await;
                    active.get(&gid).copied().unwrap_or(false)
                };
                if !is_active {
                    stop_requested.store(true, Ordering::Relaxed);
                    return;
                }

                // Skip pages that are already saved on disk (resume).
                if base_dir.as_ref().map(|b| page_file_exists(b, &gid, i)).unwrap_or(false) {
                    downloaded.fetch_max(i + 1, Ordering::Relaxed);
                    sync_progress(&gid, &downloaded).await;
                    return;
                }

                let real_url = match resolve_image_url(urls[i as usize].clone()).await {
                    Ok(url) => url,
                    Err(e) => {
                        failures.fetch_add(1, Ordering::Relaxed);
                        let mut le = last_error.lock().await;
                        if le.is_none() {
                            *le = Some(e.to_string());
                        }
                        return;
                    }
                };
                let bytes = match get_image(real_url.clone()).await {
                    Ok(bytes) => bytes,
                    Err(e) => {
                        failures.fetch_add(1, Ordering::Relaxed);
                        let mut le = last_error.lock().await;
                        if le.is_none() {
                            *le = Some(e.to_string());
                        }
                        return;
                    }
                };

                // Re-check the stop flag right before writing: a delete/pause
                // can happen while this page was in-flight.
                let still_active = {
                    let active = ACTIVE_TASKS.lock().await;
                    active.get(&gid).copied().unwrap_or(false)
                };
                if !still_active {
                    stop_requested.store(true, Ordering::Relaxed);
                    return;
                }

                match save_page(&gid, i, &bytes).await {
                    Ok(_) => {
                        downloaded.fetch_max(i + 1, Ordering::Relaxed);
                        resolved_urls.lock().await.push(real_url);
                        sync_progress(&gid, &downloaded).await;
                    }
                    Err(e) => {
                        failures.fetch_add(1, Ordering::Relaxed);
                        let mut le = last_error.lock().await;
                        if le.is_none() {
                            *le = Some(e.to_string());
                        }
                    }
                }
            }
        })
        .buffer_unordered(PAGE_CONCURRENCY)
        .collect::<()>()
        .await;

    // Finalize the status if the run did not complete.
    {
        let mut queue = QUEUE.write().await;
        if let Some((task, _)) = queue.get_mut(&gid) {
            let done = downloaded.load(Ordering::Relaxed);
            task.downloaded_pages = done;
            if done >= task.total_pages {
                task.status = 2; // completed
            } else if stop_requested.load(Ordering::Relaxed) {
                task.status = 0; // stopped by user, resumable
            } else {
                let failed = failures.load(Ordering::Relaxed);
                if failed > 0 {
                    task.status = -1; // error, resumable
                    let msg = last_error.lock().await.clone().unwrap_or_default();
                    task.error_msg = Some(format!(
                        "{} / {} 页下载失败: {}",
                        failed, task.total_pages, msg
                    ));
                } else {
                    task.status = 0; // no work left, treat as stopped
                }
            }
        }
    }
    persist().await;

    // Download finished: drop the duplicate copies of its images from the
    // HTTP cache (L1 memory + L2 disk) since they're persisted on disk.
    let completed = {
        let queue = QUEUE.read().await;
        matches!(queue.get(&gid).map(|(t, _)| t.status), Some(2))
    };
    if completed {
        for url in resolved_urls.lock().await.iter() {
            crate::api::evict_image_cache(url.clone()).await;
        }
    }

    ACTIVE_DOWNLOADS.fetch_sub(1, Ordering::Relaxed);

    let mut active = ACTIVE_TASKS.lock().await;
    active.remove(&gid);
}