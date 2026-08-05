use anyhow::Result;
use flutter_rust_bridge::DartFnFuture;
use crate::network::NetworkClient;
use crate::parser::{GalleryItem, GalleryDetail};
use crate::cache::CacheEngine;
use lazy_static::lazy_static;
use std::sync::Arc;
use tokio::sync::Mutex;

lazy_static! {
    static ref NETWORK_CLIENT: Arc<NetworkClient> = Arc::new(NetworkClient::new());
    static ref CACHE_ENGINE: Arc<Mutex<CacheEngine>> =
        Arc::new(Mutex::new(CacheEngine::new(100, "./.cache".to_string())));
}

/// A basic health check to ensure Dart <-> Rust FFI is working
pub async fn health_check(name: String) -> String {
    format!("Hello, {}! Rust FFI is working flawlessly.", name)
}

/// Initialize the Rust backend environments (cache dirs, connection pools)
/// enable_eh_host is kept for API compatibility but no longer used:
/// the built-in hosts feature was removed (DNS is used as-is).
pub async fn init_backend(cache_dir: String, enable_eh_host: bool) -> Result<()> {
    let _ = enable_eh_host; // kept for API compatibility, no longer used
    let mut cache = CACHE_ENGINE.lock().await;
    cache.set_disk_path(cache_dir.clone());
    cache.init_disk_cache().await?;
    drop(cache); // release lock before async network ops

    // Downloads live beside the HTTP cache dir (its parent), NOT inside it,
    // so the periodic cache auto-clear can wipe image cache files while the
    // Dart side keeps skipping the download folder.
    let downloads_parent = std::path::Path::new(&cache_dir)
        .parent()
        .and_then(|p| p.to_str())
        .unwrap_or(&cache_dir);
    crate::downloader::init_downloader(downloads_parent).await;

    log::info!("Backend initialized.");
    Ok(())
}

/// Sync cookies from Flutter WebView to Rust Reqwest client
pub async fn sync_cookies(cookie_string: String) -> Result<()> {
    NETWORK_CLIENT.update_cookies(&cookie_string).await?;
    Ok(())
}

/// Set target site (https://e-hentai.org or https://exhentai.org)
pub async fn set_site_url(url: String) -> Result<()> {
    NETWORK_CLIENT.update_site_url(&url).await;
    Ok(())
}

/// Configure network client. Kept for API compatibility; the built-in
/// hosts feature was removed, so both flags are ignored (DNS used as-is).
pub async fn configure_network(enable_eh_host: bool, enable_ex_host: bool) -> Result<()> {
    let _ = (enable_eh_host, enable_ex_host); // kept for API compatibility, no longer used
    NETWORK_CLIENT.rebuild_client().await;
    Ok(())
}

pub async fn download_tag_db(path: String) -> Result<()> {
    crate::tag_translator::download_tag_db(path).await
}

pub async fn load_tag_db(path: String) -> Result<()> {
    crate::tag_translator::load_tag_db(path).await
}

#[flutter_rust_bridge::frb(sync)]
pub fn translate_tag_sync(namespace: String, tag: String) -> String {
    crate::tag_translator::translate_tag_sync(namespace, tag)
}

#[flutter_rust_bridge::frb(sync)]
pub fn search_tag_by_chinese(keyword: String) -> Vec<crate::tag_translator::TagSuggestion> {
    crate::tag_translator::search_tag_by_chinese(keyword)
}

/// Fetch the E-Hentai front page and parse real gallery data
/// Falls back to informative mock data on network error
pub async fn fetch_front_page(query: Option<String>, options: Option<SearchOptions>) -> Vec<GalleryItem> {
    let site_url = NETWORK_CLIENT.get_site_url().await;
    
    let mut url = site_url.clone();
    
    // build_search_query_string adds '?' (or '&') itself when it appends
    // parameters, so the base URL stays clean here.
    url = format!("{}/", url);
    build_search_query_string(&mut url, query, options);

    log::info!("Fetching front page from {} ...", url);

    match NETWORK_CLIENT.get_html(&url).await {
        Ok(html) => {
            match crate::parser::parse_gallery_list(&html) {
                Ok(page_data) if !page_data.items.is_empty() => {
                    log::info!("Parsed {} gallery items from front page", page_data.items.len());
                    page_data.items
                }
                Ok(_) => {
                    // Page loaded but no items - show what we actually got
                    let preview: String = html.chars().take(100).collect();
                    error_items(format!("0 items. HTML len: {}. Preview: {}", html.len(), preview))
                }
                Err(e) => {
                    log::error!("Parse error: {}", e);
                    error_items(format!("Parse error: {}", e))
                }
            }
        }
        Err(e) => {
            log::error!("Network error: {}", e);
            error_items(format!("Network error: {}", e))
        }
    }
}

#[derive(Debug, Clone)]
pub struct SearchOptions {
    pub f_sname: bool,
    pub f_stags: bool,
    pub f_sdesc: bool,
    pub f_cats: Option<u32>,
}

fn build_search_query_string(url: &mut String, query: Option<String>, options: Option<SearchOptions>) {
    let mut params: Vec<String> = Vec::new();
    if let Some(q) = query {
        let q = q.trim();
        if !q.is_empty() {
            params.push(format!("f_search={}", urlencoding::encode(q)));
            if let Some(opt) = &options {
                if opt.f_sname { params.push("f_sname=on".to_string()); }
                if opt.f_stags { params.push("f_stags=on".to_string()); }
                if opt.f_sdesc { params.push("f_sdesc=on".to_string()); }
            }
        }
    }
    if let Some(opt) = options {
        if let Some(cats) = opt.f_cats {
            params.push(format!("f_cats={}", cats));
        }
    }
    if !params.is_empty() {
        let sep = if url.contains('?') { "&" } else { "?" };
        url.push_str(sep);
        url.push_str(&params.join("&"));
    }
}

/// Fetch paginated gallery list (home page and search)
pub async fn fetch_gallery_list(page: u32, page_url: Option<String>, query: Option<String>, options: Option<SearchOptions>) -> Result<crate::parser::GalleryPage> {
    let site_url = NETWORK_CLIENT.get_site_url().await;
    
    let url = if let Some(u) = page_url {
        // If a specific next/prev URL is provided, use it directly! (E-Hentai's next= pagination)
        u
    } else {
        // Fallback to traditional page numbers for first page or jump
        let mut u = format!("{}/?page={}", site_url, page);
        build_search_query_string(&mut u, query, options);
        u
    };

    let html = NETWORK_CLIENT.get_html(&url).await?;
    crate::parser::parse_gallery_list(&html)
}

/// Fetch a custom gallery list (like watched, popular, toplist, favorites)
pub async fn fetch_custom_list(path: String, page: u32, page_url: Option<String>, query: Option<String>, options: Option<SearchOptions>) -> Result<crate::parser::GalleryPage> {
    let site_url = NETWORK_CLIENT.get_site_url().await;

    let base_url = if path.is_empty() {
        site_url
    } else {
        format!("{}/{}", site_url, path)
    };

    let url = if let Some(u) = page_url {
        u
    } else {
        let mut u = if base_url.contains('?') {
            format!("{}&page={}", base_url, page)
        } else {
            format!("{}?page={}", base_url, page)
        };

        build_search_query_string(&mut u, query, options);
        u
    };

    let html = NETWORK_CLIENT.get_html(&url).await?;
    let mut result = crate::parser::parse_gallery_list(&html)?;

    // Some pages (e.g. toplist.php) don't emit next= links; paginate by
    // page number so "load more" keeps working.
    if result.next_url.is_none() && !result.items.is_empty() {
        let next_url = if base_url.contains('?') {
            format!("{}&page={}", base_url, page + 1)
        } else {
            format!("{}?page={}", base_url, page + 1)
        };
        result.next_url = Some(next_url);
    }

    Ok(result)
}

/// Fetch gallery details and preload next 3-5 pages
pub async fn fetch_gallery_detail(id: String) -> Result<GalleryDetail> {
    let site_url = NETWORK_CLIENT.get_site_url().await;
    let url = format!("{}/g/{}", site_url, id);
    
    let html = match NETWORK_CLIENT.get_html(&url).await {
        Ok(html) => html,
        Err(e) => {
            log::error!("Failed to fetch gallery detail {}: {}", id, e);
            return Err(e);
        }
    };

    let detail = match crate::parser::parse_gallery_detail(&html) {
        Ok(mut d) => {
            d.id = id;
            d
        }
        Err(e) => {
            log::error!("Failed to parse gallery detail: {}", e);
            return Err(e);
        }
    };

    // Spawn background preloader for the first 3 images.
    // Viewer page URLs are resolved to real image URLs first, then fetched
    // through the same cache path the reader uses (get_image), so the
    // preload actually warms the cache the reader reads from.
    let urls = detail.image_urls.clone();
    let client = Arc::clone(&NETWORK_CLIENT);
    let cache = Arc::clone(&CACHE_ENGINE);
    tokio::spawn(async move {
        for url in urls.iter().take(3) {
            if let Ok(real_url) = resolve_image_url(url.clone()).await {
                let _ = fetch_image_cached(&real_url, &cache, &client).await;
            }
        }
    });

    Ok(detail)
}

/// Fetch a specific page of gallery thumbnails
pub async fn fetch_gallery_page(id: String, token: String, page: u32) -> Result<GalleryDetail> {
    let site_url = NETWORK_CLIENT.get_site_url().await;
    let url = format!("{}/g/{}/{}/?p={}", site_url, id, token, page);
    
    let html = NETWORK_CLIENT.get_html(&url).await?;
    let mut detail = crate::parser::parse_gallery_detail(&html)?;
    detail.id = id;
    
    Ok(detail)
}

/// Fetch image bytes, using L1 (Memory) + L2 (Disk) caching.
/// The global cache lock is only held for the (fast) cache read/write, never
/// across a network request, so one slow image cannot block other loads.
pub async fn get_image(url: String) -> Result<Vec<u8>> {
    fetch_image_cached(&url, &CACHE_ENGINE, &NETWORK_CLIENT).await
}

/// Fetch image bytes (L1/L2/L3 cache-aware) while reporting download
/// progress via [on_progress], called with `(downloaded_bytes, total_bytes)`
/// after each received chunk. `total` is `None` when the server omits
/// Content-Length. Cache hits report instant 100% completion.
pub async fn get_image_with_progress(
    url: String,
    on_progress: impl Fn(u64, Option<u64>) -> DartFnFuture<()> + Send + 'static,
) -> Result<Vec<u8>> {
    // L1 + L2: memory / disk cache hit → complete instantly.
    {
        let c = CACHE_ENGINE.lock().await;
        if let Some(bytes) = c.get_cached(&url).await? {
            on_progress(bytes.len() as u64, Some(bytes.len() as u64)).await;
            return Ok(bytes.to_vec());
        }
    }

    // L3: network fetch, reporting progress chunk-by-chunk (lock released).
    let bytes = NETWORK_CLIENT
        .get_bytes_with_progress(&url, on_progress)
        .await?;

    // Persist to memory + disk (HTML error bodies are rejected inside store()).
    {
        let c = CACHE_ENGINE.lock().await;
        let _ = c.store(&url, &bytes).await;
    }

    Ok(bytes)
}

async fn fetch_image_cached(
    url: &str,
    cache: &Arc<Mutex<CacheEngine>>,
    network: &Arc<NetworkClient>,
) -> Result<Vec<u8>> {
    // L1 + L2: memory / disk cache hit
    {
        let c = cache.lock().await;
        if let Some(bytes) = c.get_cached(url).await? {
            return Ok(bytes.to_vec());
        }
    }

    // L3: Network fetch (lock released)
    let bytes = network.get_bytes(url).await?;

    // Persist to memory + disk (HTML error bodies are rejected inside store())
    {
        let c = cache.lock().await;
        let _ = c.store(url, &bytes).await;
    }

    Ok(bytes)
}

fn error_items(msg: String) -> Vec<GalleryItem> {
    vec![GalleryItem {
        gid: "error".to_string(),
        token: "".to_string(),
        title: format!("❌ {}", msg),
        thumb_url: "".to_string(),
        category: "Error".to_string(),
        uploader: "System".to_string(),
        post_date: "".to_string(),
    }]
}

/// Resolves the actual image URL from an E-Hentai viewer page URL
pub async fn resolve_image_url(viewer_url: String) -> Result<String> {
    let html = NETWORK_CLIENT.get_html(&viewer_url).await?;
    crate::parser::parse_image_url(&html)
}

/// Collect the full viewer-URL list for a gallery by walking every ?p=N page,
/// starting from page 1 (page 0's URLs are already in `current`). Stops once
/// `target` URLs are collected, when a page yields no new URLs (dedupe-based,
/// used by truncated/stale tasks), or after MAX_PAGES as a safety valve.
pub(crate) async fn collect_viewer_urls(
    gid: &str,
    token: &str,
    current: &mut Vec<String>,
    target: u32,
) -> Result<()> {
    let site_url = NETWORK_CLIENT.get_site_url().await;
    let mut seen: std::collections::HashSet<String> = current.iter().cloned().collect();
    let mut p: u32 = 1;
    const MAX_PAGES: u32 = 300;
    while (current.len() as u32) < target && p <= MAX_PAGES {
        let url = format!("{}/g/{}/{}/?p={}", site_url, gid, token, p);
        let html = NETWORK_CLIENT.get_html(&url).await?;
        let detail = crate::parser::parse_gallery_detail(&html)
            .map_err(|e| anyhow::anyhow!("解析第 {} 页失败: {}", p, e))?;
        let mut new = 0;
        for u in detail.image_urls {
            if seen.insert(u.clone()) {
                current.push(u);
                new += 1;
            }
        }
        if new == 0 {
            break;
        }
        p += 1;
    }
    Ok(())
}

pub async fn start_download(
    gid: String,
    token: String,
    title: String,
    image_urls: Vec<String>,
    total_pages: u32,
) -> Result<()> {
    crate::downloader::start_download(gid, token, title, image_urls, total_pages).await?;
    Ok(())
}

pub async fn pause_download(gid: String) -> Result<()> {
    crate::downloader::pause_download(gid).await?;
    Ok(())
}

pub async fn get_download_tasks() -> Vec<crate::downloader::DownloadTask> {
    crate::downloader::get_all_downloads().await
}

pub async fn delete_download(gid: String) -> Result<()> {
    crate::downloader::delete_download(gid).await;
    Ok(())
}

/// Change the max number of galleries downloading at once (1..=8).
/// Previously this setting existed in the UI but was ignored by the Rust core.
pub async fn set_download_concurrency(n: u32) -> Result<()> {
    crate::downloader::set_concurrency(n).await;
    Ok(())
}

/// Point the downloader at a new directory (the download-path setting).
pub async fn set_download_dir(path: String) -> Result<()> {
    crate::downloader::set_download_dir(path).await
}

/// Current download directory, if initialized.
pub async fn get_download_dir() -> Option<String> {
    crate::downloader::get_download_dir().await
}

/// Evict one image URL from the HTTP cache. Used by the downloader once a
/// gallery finishes: the pages are persisted in the download directory, so
/// their duplicate copies in the L1/L2 cache can be dropped.
pub(crate) async fn evict_image_cache(url: String) {
    let cache = CACHE_ENGINE.lock().await;
    let _ = cache.remove(&url).await;
}

/// Fetch the torrent list for a gallery (/gallerytorrents.php popup).
pub async fn fetch_torrents(gid: String, token: String) -> Result<Vec<crate::parser::TorrentItem>> {
    let site_url = NETWORK_CLIENT.get_site_url().await;
    let url = format!("{}/gallerytorrents.php?gid={}&t={}", site_url, gid, token);
    let html = NETWORK_CLIENT.get_html(&url).await?;
    Ok(crate::parser::parse_torrents(&html))
}

/// Download a .torrent file (e-hentai: ehtracker.org/get, exhentai:
/// exhentai.org/torrent) and persist it under eh_downloads/torrents.
/// Returns the saved file path.
pub async fn download_torrent(name: String, hash: String, token: String) -> Result<String> {
    let site_url = NETWORK_CLIENT.get_site_url().await;
    let ex = site_url.contains("exhentai");
    let host = if ex { "https://exhentai.org" } else { "https://ehtracker.org" };
    let path = if ex { "torrent" } else { "get" };
    let url = format!("{}/{}/{}/{}.torrent", host, path, token, hash);
    let bytes = NETWORK_CLIENT.get_bytes(&url).await?;
    // A bencoded torrent file always starts with 'd' (the outer dictionary);
    // anything else is an HTML error page / Cloudflare block, not a torrent.
    if bytes.first() != Some(&b'd') {
        anyhow::bail!("返回内容不是有效的种子文件（{} 字节）", bytes.len());
    }
    let dir = crate::downloader::torrents_dir()
        .await
        .ok_or_else(|| anyhow::anyhow!("下载目录未初始化"))?;
    tokio::fs::create_dir_all(&dir).await?;
    let fname = sanitize_filename(&name);
    let path = dir.join(format!("{}.torrent", fname));
    tokio::fs::write(&path, &bytes).await?;
    Ok(path.to_string_lossy().to_string())
}

/// Make a string safe to use as a file name.
fn sanitize_filename(name: &str) -> String {
    let cleaned: String = name
        .chars()
        .map(|c| {
            if c.is_control() || "\\/:*?\"<>|".contains(c) {
                '_'
            } else {
                c
            }
        })
        .collect();
    let cleaned = cleaned.trim().to_string();
    let cleaned = if cleaned.is_empty() { "torrent".to_string() } else { cleaned };
    if cleaned.chars().count() > 80 {
        // Slice by chars, not bytes: byte-slicing a CJK title here panics.
        cleaned.chars().take(80).collect::<String>()
    } else {
        cleaned
    }
}

/// Add gallery to favorites
pub async fn add_favorite(gid: String, token: String, favcat: String, favnote: String) -> Result<String> {
    let site_url = NETWORK_CLIENT.get_site_url().await;
    let url = format!("{}/gallerypopups.php?gid={}&t={}&act=addfav", site_url, gid, token);
    
    let form = [
        ("favcat", favcat.as_str()),
        ("favnote", favnote.as_str()),
        ("apply", "Add to Favorites"),
        ("update", "1"),
    ];
    
    let res = NETWORK_CLIENT.post_form(&url, &form).await?;
    Ok(res)
}

/// Post a comment to a gallery
pub async fn post_comment(gid: String, token: String, content: String) -> Result<String> {
    let site_url = NETWORK_CLIENT.get_site_url().await;
    let url = format!("{}/g/{}/{}/", site_url, gid, token);
    
    let form = [
        ("commenttext_new", content.as_str()),
    ];
    
    let res = NETWORK_CLIENT.post_form(&url, &form).await?;
    Ok(res)
}

/// Rate a gallery 1..5 via the web vote form (no API key required).
/// Fetches the detail page for the CSRF vote_key, then submits
/// vote_data="v{rating}" like the site's own JS does.
pub async fn vote_gallery(gid: String, token: String, rating: u32) -> Result<String> {
    if !(1..=5).contains(&rating) {
        anyhow::bail!("rating must be between 1 and 5");
    }
    let site_url = NETWORK_CLIENT.get_site_url().await;
    let url = format!("{}/g/{}/{}/", site_url, gid, token);

    let html = NETWORK_CLIENT.get_html(&url).await?;
    let vote_key = crate::parser::parse_vote_key(&html)
        .ok_or_else(|| anyhow::anyhow!("评分表单不可用，请确认已登录"))?;

    let vote_data = format!("v{}", rating);
    let form = [
        ("vote_key", vote_key.as_str()),
        ("vote_data", vote_data.as_str()),
    ];

    let res = NETWORK_CLIENT.post_form(&url, &form).await?;
    if res.to_lowercase().contains("not logged in") || res.to_lowercase().contains("insufficient") {
        anyhow::bail!("评分失败：请确认已登录且账号状态正常");
    }
    Ok(res)
}

/// Add a tag to the account's watched tags (My Tags), so matching galleries
/// show up on the /watched page (the app's "订阅" list). Requires login:
/// the My Tags form is only available to logged-in accounts.
pub async fn add_watched_tag(tag: String) -> Result<String> {
    let site_url = NETWORK_CLIENT.get_site_url().await;
    let url = format!("{}/mytags", site_url);
    let html = NETWORK_CLIENT.get_html(&url).await?;
    if html.contains("requires you to log on") {
        anyhow::bail!("需要先登录 E-Hentai 账号才能关注标签");
    }

    let (fields, post_url, watch_updated) = {
        let document = scraper::Html::parse_document(&html);
        let form_sel = scraper::Selector::parse("form").unwrap();
        let input_sel = scraper::Selector::parse("input").unwrap();
        let textarea_sel = scraper::Selector::parse("textarea").unwrap();

        let Some(form) = document.select(&form_sel).next() else {
            anyhow::bail!("无法解析 My Tags 页面");
        };

        let mut fields: Vec<(String, String)> = Vec::new();
        let mut watch_updated = false;

        for textarea in form.select(&textarea_sel) {
            let name = textarea.value().attr("name").unwrap_or_default().to_string();
            let mut value = textarea.text().collect::<String>();
            if name == "watch_list" {
                let trimmed = value.trim().to_string();
                let exists = trimmed.lines().any(|l| l.trim() == tag);
                if !exists {
                    if !trimmed.is_empty() {
                        value.push('\n');
                    }
                    value.push_str(&tag);
                    watch_updated = true;
                }
            }
            fields.push((name, value));
        }

        for input in form.select(&input_sel) {
            let name = input.value().attr("name").unwrap_or_default();
            if name.is_empty() {
                continue;
            }
            let typ = input.value().attr("type").unwrap_or("text");
            if matches!(typ, "submit" | "button" | "reset" | "image") {
                continue;
            }
            if matches!(typ, "checkbox" | "radio") && input.value().attr("checked").is_none() {
                continue;
            }
            fields.push((name.to_string(), input.value().attr("value").unwrap_or_default().to_string()));
        }

        let action = form.value().attr("action").unwrap_or("mytags");
        let post_url = if action.starts_with("http") {
            action.to_string()
        } else if action.starts_with('/') {
            format!("{}{}", site_url, action)
        } else {
            format!("{}/{}", site_url, action)
        };

        (fields, post_url, watch_updated)
    };

    let form_fields: Vec<(&str, &str)> = fields.iter().map(|(k, v)| (k.as_str(), v.as_str())).collect();
    let res = NETWORK_CLIENT.post_form(&post_url, &form_fields).await?;
    if res.contains("requires you to log on") {
        anyhow::bail!("关注失败：会话已失效，请重新登录");
    }

    if watch_updated {
        Ok(format!("已关注标签: {}", tag))
    } else {
        Ok(format!("标签已在关注列表中: {}", tag))
    }
}

/// Fetch one more page of comments from the gallery detail pages.
/// Comments repeat across image pagination; callers should dedupe by
/// (author, time, content). Returns an empty vec when there are no more.
pub async fn fetch_more_comments(gid: String, token: String, page: u32) -> Vec<crate::parser::GalleryComment> {
    let site_url = NETWORK_CLIENT.get_site_url().await;
    let url = format!("{}/g/{}/{}/?p={}", site_url, gid, token, page);
    match NETWORK_CLIENT.get_html(&url).await {
        Ok(html) => crate::parser::parse_comments(&html),
        Err(_) => Vec::new(),
    }
}

/// Rate a gallery via api.php
pub async fn rate_gallery(gid: u64, token: String, rating: u32, apiuid: u64, apikey: String) -> Result<String> {
    let site_url = NETWORK_CLIENT.get_site_url().await;
    let url = format!("{}/api.php", site_url);
    
    let payload = serde_json::json!({
        "method": "rategallery",
        "apiuid": apiuid,
        "apikey": apikey,
        "gid": gid,
        "token": token,
        "rating": rating
    });
    
    let res = NETWORK_CLIENT.post_json(&url, &payload).await?;
    Ok(res)
}

/// Fetch tag autocomplete suggestions
pub async fn fetch_autocomplete(prefix: String) -> Result<String> {
    let site_url = NETWORK_CLIENT.get_site_url().await;
    let url = format!("{}/api.php", site_url);
    
    let payload = serde_json::json!({
        "method": "tagsuggest",
        "text": prefix
    });
    
    let res = NETWORK_CLIENT.post_json(&url, &payload).await?;
    Ok(res)
}

#[cfg(test)]
mod tests {
    use super::sanitize_filename;

    #[test]
    fn sanitize_long_cjk_name_does_not_panic() {
        // >80 bytes of multi-byte UTF-8: byte-slicing at 80 used to panic.
        let long = "?????????????????????????".repeat(4);
        assert!(long.len() > 80);
        let cleaned = sanitize_filename(&long);
        assert_eq!(cleaned.chars().count(), 80);
    }

    #[test]
    fn sanitize_strips_invalid_filename_chars() {
        assert_eq!(
            sanitize_filename("a/b\\c:d*e?f\"g<h>i|j"),
            "a_b_c_d_e_f_g_h_i_j"
        );
    }

    #[test]
    fn sanitize_empty_falls_back_to_torrent() {
        assert_eq!(sanitize_filename("   "), "torrent");
    }
}
