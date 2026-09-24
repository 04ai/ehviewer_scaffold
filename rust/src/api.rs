use anyhow::Result;
use bytes::Bytes;
use crate::network::NetworkClient;
use crate::parser::{GalleryItem, GalleryDetail, EhWebConfig};
use crate::cache::CacheEngine;
use lazy_static::lazy_static;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, RwLock};
use std::collections::HashMap;

lazy_static! {
    static ref NETWORK_CLIENT: Arc<NetworkClient> = Arc::new(NetworkClient::new());
    /// Global image cache — `Arc<CacheEngine>` with interior mutability.
    /// No outer Mutex: concurrent tasks share this reference directly and
    /// contend only on the µs-scale std::Mutex<MemCache> inside CacheEngine.
    static ref CACHE_ENGINE: Arc<CacheEngine> =
        Arc::new(CacheEngine::new("./.cache".to_string()));

    /// Real-time progress tracker for active reader image downloads (0.0 .. 1.0).
    ///
    /// Bounded on purpose — see [`IMAGE_PROGRESS_CAP`]. The key is the full
    /// viewer URL, so without a cap every page ever opened stays resident for
    /// the lifetime of the process.
    static ref IMAGE_PROGRESS: RwLock<HashMap<String, f32>> = RwLock::new(HashMap::new());

    /// Set once an exclusion-only listing query has been refused, so the probe is paid
    /// at most once per process. See `fetch_gallery_list`.
    static ref EXCLUSION_ONLY_REJECTED: AtomicBool = AtomicBool::new(false);
}

/// E-Hentai uses at most the first 8 terms of a search; extras are ignored.
const EXCLUSION_TERM_BUDGET: usize = 8;

/// Soft cap on tracked viewer URLs.
///
/// A reader session only ever polls entries whose image is still loading, so
/// finished (`1.0`) entries are dead weight. When the map grows past this,
/// completed entries are swept first; if that is not enough (e.g. hundreds of
/// genuinely in-flight requests) the map is cleared, which costs at most a
/// stale progress read for images that are about to be delivered anyway.
const IMAGE_PROGRESS_CAP: usize = 256;

/// How long a parsed gallery detail may be served from disk before it is
/// refetched.
///
/// Detail is cached mainly for the "open → back → open again" cycle, but it
/// carries mutable state (rating, favourites count, and whether *this* account
/// has favourited it). An unbounded entry used to keep answering with a stale
/// `is_favorited` long after the change, so the detail cache is time-bounded
/// like the listing cache — just with a longer window, because tag lists and
/// page counts do not move.
const DETAIL_CACHE_TTL_SECS: u64 = 120;

/// Retrieve current image download progress for a given viewer page URL (0.0 .. 1.0)
pub fn get_image_progress(viewer_url: &str) -> f32 {
    IMAGE_PROGRESS.read().map(|m| m.get(viewer_url).copied().unwrap_or(0.0)).unwrap_or(0.0)
}

/// Set image download progress for a given viewer page URL
pub fn set_image_progress(viewer_url: &str, progress: f32) {
    if let Ok(mut m) = IMAGE_PROGRESS.write() {
        m.insert(viewer_url.to_string(), progress);
        if m.len() > IMAGE_PROGRESS_CAP {
            m.retain(|_, v| *v < 1.0);
            if m.len() > IMAGE_PROGRESS_CAP {
                m.clear();
            }
        }
    }
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

    // Rust does not install a logger by default, which on Android meant every
    // `log::*` in this crate was compiled in but thrown away — hundreds of
    // diagnostics that never once reached logcat. `init_once` is idempotent,
    // so repeated backend inits (activity restarts) are harmless.
    let _ = android_logger::init_once(
        android_logger::Config::default()
            .with_tag("EhRust")
            // Info keeps the hot image path quiet: nothing in this crate logs
            // above Debug on a per-poll / per-frame cadence.
            .with_max_level(log::LevelFilter::Info),
    );

    // CacheEngine uses interior mutability — no outer lock needed.
    CACHE_ENGINE.set_disk_path(cache_dir.clone());
    CACHE_ENGINE.init_disk_cache().await?;

    // Downloads live beside the HTTP cache dir (its parent), NOT inside it,
    // so the periodic cache auto-clear can wipe image cache files while the
    // Dart side keeps skipping the download folder.
    let downloads_parent = std::path::Path::new(&cache_dir)
        .parent()
        .and_then(|p| p.to_str())
        .unwrap_or(&cache_dir);
    crate::downloader::init_downloader(downloads_parent).await;

    // Load the gallery→tags index that backs blocked-tag filtering. The block
    // list itself arrives later from Kotlin via `set_blocked_tags` (it is
    // replayed at cold start, like the site URL and the download directory).
    crate::blocked::init_index(&cache_dir);

    log::info!("Backend initialized.");
    Ok(())
}

/// Replace the blocked-tag list. Kotlin's SharedPreferences stays authoritative;
/// this is in-memory state replayed on cold start.
pub fn set_blocked_tags(tags: Vec<String>) {
    crate::blocked::set_blocked_tags(tags);
}

/// Keep only the gids that are not blocked.
///
/// Used when the block list changes while a list is already on screen: the UI
/// drops the newly blocked galleries from what it has, instead of refetching the
/// whole page (which would also throw away the scroll position).
pub fn filter_blocked_ids(gids: Vec<String>) -> Vec<String> {
    if !crate::blocked::has_blocked() {
        return gids;
    }
    let before = gids.len();
    let kept: Vec<String> = gids
        .into_iter()
        .filter(|g| !crate::blocked::is_gallery_blocked(g))
        .collect();
    if kept.len() != before {
        log::info!(
            "Blocked-tag filter dropped {} of {} already-listed galleries",
            before - kept.len(),
            before
        );
    }
    kept
}

/// Sync cookies from Flutter WebView to Rust Reqwest client
pub async fn sync_cookies(cookie_string: String) -> Result<()> {
    NETWORK_CLIENT.update_cookies(&cookie_string).await?;
    Ok(())
}

/// Set user agent string dynamically from Android WebView
pub async fn set_user_agent(user_agent: String) -> Result<()> {
    NETWORK_CLIENT.update_user_agent(&user_agent).await;
    Ok(())
}

/// Set target site (https://e-hentai.org or https://exhentai.org)
pub async fn set_site_url(url: String) -> Result<()> {
    NETWORK_CLIENT.update_site_url(&url).await;
    Ok(())
}

pub async fn download_tag_db(path: String) -> Result<()> {
    crate::tag_translator::download_tag_db(path).await
}

pub async fn load_tag_db(path: String) -> Result<()> {
    crate::tag_translator::load_tag_db(path).await
}

pub fn translate_tag_sync(namespace: String, tag: String) -> String {
    crate::tag_translator::translate_tag_sync(namespace, tag)
}

/// Translate a whole batch of `(namespace, tag)` pairs in a single call.
///
/// A gallery routinely carries 60–120 tags, and each individual
/// [`translate_tag_sync`] costs one JNI transition plus one `TAG_DB` read-lock
/// acquisition. Calling across the boundary once per tag meant 60–120
/// transitions just to render a detail page; this collapses it to one.
///
/// Keys are `"namespace:tag"`, matching the map the detail screen indexes.
/// Values fall back to the raw tag when there is no translation, so callers
/// never have to handle an empty string.
pub fn translate_tags_batch(pairs: Vec<(String, String)>) -> HashMap<String, String> {
    let mut out = HashMap::with_capacity(pairs.len());
    for (ns, tag) in pairs {
        let key = format!("{}:{}", ns, tag);
        let translated = crate::tag_translator::translate_tag_sync(ns, tag.clone());
        out.insert(key, if translated.is_empty() { tag } else { translated });
    }
    out
}

pub fn search_tag_by_chinese(keyword: String) -> Vec<crate::tag_translator::TagSuggestion> {
    crate::tag_translator::search_tag_by_chinese(keyword)
}

/// Ask E-Hentai itself what tags start with `keyword`.
///
/// This is `POST {site}/api.php` with `{"method":"tagsuggest","text":...}`,
/// returning entries shaped like `{"ns":"female","tn":"kafka"}`.
///
/// Unlike the bundled translation database this needs **no download** and
/// covers E-Hentai's full vocabulary, including the long tail of tags the
/// curated database never includes. Network failures return an empty list so
/// the caller falls back to local sources.
pub async fn suggest_tags_online(keyword: String) -> Result<Vec<crate::tag_translator::TagSuggestion>> {
    let kw = keyword.trim();
    if kw.is_empty() {
        return Ok(Vec::new());
    }

    let site_url = NETWORK_CLIENT.get_site_url().await;
    let url = format!("{}/api.php", site_url);
    let body = serde_json::json!({ "method": "tagsuggest", "text": kw });

    let response = match NETWORK_CLIENT.post_json(&url, &body).await {
        Ok(r) => r,
        Err(e) => {
            // Expected offline / when the site is unreachable: this is a
            // suggestion source, never a correctness requirement.
            log::debug!("tagsuggest failed: {}", e);
            return Ok(Vec::new());
        }
    };

    let value: serde_json::Value = match serde_json::from_str(&response) {
        Ok(v) => v,
        Err(e) => {
            // Warn, not debug: reaching here means the endpoint answered but
            // not with JSON, so the request itself worked and our assumption
            // about the payload is what is wrong.
            log::warn!("tagsuggest response was not JSON: {}", e);
            return Ok(Vec::new());
        }
    };

    // E-Hentai has shipped more than one shape for this endpoint: a bare array
    // of entries, and an object carrying them under "tags". Only the object
    // form was implemented, so a bare array fell into `_` and returned an
    // empty list — the online completion source silently contributed nothing.
    // Accept the top level too, and keep the object-with-keys form working.
    let entries: Vec<TagSuggestEntry> = {
        let container = value.get("tags").unwrap_or(&value);
        match container {
            serde_json::Value::Array(arr) => arr
                .iter()
                .filter_map(|e| serde_json::from_value::<TagSuggestEntry>(e.clone()).ok())
                .collect(),
            serde_json::Value::Object(map) => map
                .values()
                .filter_map(|e| serde_json::from_value::<TagSuggestEntry>(e.clone()).ok())
                .collect(),
            _ => Vec::new(),
        }
    };

    let mut out = Vec::with_capacity(entries.len());
    for entry in entries {
        let tag = entry.tn.trim();
        if tag.is_empty() {
            continue;
        }
        let ns = entry.ns.trim();
        let raw = if ns.is_empty() {
            tag.to_string()
        } else {
            format!("{}:{}", ns, tag)
        };
        let translated = if ns.is_empty() {
            String::new()
        } else {
            crate::tag_translator::translate_tag_sync(ns.to_string(), tag.to_string())
        };
        out.push(crate::tag_translator::TagSuggestion { raw, translated });
    }

    if out.is_empty() {
        // The endpoint answered but nothing was parseable — log the head of the
        // payload so a future shape change is diagnosable from logcat instead
        // of looking like "the site has no tag for this".
        log::warn!(
            "tagsuggest('{}') yielded no suggestions; payload head: {}",
            kw,
            response.chars().take(120).collect::<String>()
        );
    } else {
        log::info!("tagsuggest('{}') → {} suggestions", kw, out.len());
    }
    Ok(out)
}

#[derive(serde::Deserialize)]
struct TagSuggestEntry {
    #[serde(default)]
    ns: String,
    #[serde(default)]
    tn: String,
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
                    crate::blocked::filter_blocked(page_data.items)
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

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
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
pub async fn fetch_gallery_list(page: u32, page_url: Option<String>, query: Option<String>, options: Option<SearchOptions>, force_refresh: bool) -> Result<crate::parser::GalleryPage> {
    let site_url = NETWORK_CLIENT.get_site_url().await;

    let paged_by_url = page_url.is_some();
    let url = if let Some(u) = page_url {
        // If a specific next/prev URL is provided, use it directly! (E-Hentai's next= pagination)
        u
    } else {
        // Fallback to traditional page numbers for first page or jump
        let mut u = format!("{}/?page={}", site_url, page);
        build_search_query_string(&mut u, query.clone(), options.clone());
        u
    };

    // Kept at info level rather than removed: this one line is what localised a cold-start
    // race where the block list had not reached Rust yet, which made the probe below look
    // like it simply never ran. It only fires for page 0.
    if page == 0 {
        log::info!(
            "Listing probe: paged_by_url={} query_empty={} blocked={} disabled={}",
            paged_by_url,
            query.as_deref().unwrap_or("").trim().is_empty(),
            crate::blocked::has_blocked(),
            EXCLUSION_ONLY_REJECTED.load(Ordering::Relaxed)
        );
    }

    // No user query, first page, and something is blocked: try to make the *server*
    // filter the listing, instead of only being able to filter the galleries this
    // device has already opened (a listing carries no tags — see `blocked`).
    //
    // E-Hentai's search documentation says "Searches with only exclusions are not
    // permitted", but that could never be verified from here, and if it does work the
    // whole feature becomes complete at zero extra traffic. So it is probed once; a
    // refusal is remembered for the process lifetime, and the fallback below is the
    // ordinary listing, so a failed probe costs one extra request and nothing else.
    if !paged_by_url
        && page == 0
        && query.as_deref().unwrap_or("").trim().is_empty()
        && crate::blocked::has_blocked()
        && !EXCLUSION_ONLY_REJECTED.load(Ordering::Relaxed)
    {
        let terms = crate::blocked::exclusion_terms(EXCLUSION_TERM_BUDGET);
        log::info!("exclusion-only probe: {} terms", terms.len());
        if !terms.is_empty() {
            let mut trial = format!("{}/?page=0", site_url);
            build_search_query_string(&mut trial, Some(terms.join(" ")), options.clone());
            match fetch_list_with_cache(&trial, force_refresh).await {
                Ok(p) if !p.items.is_empty() => {
                    log::info!(
                        "Exclusion-only listing accepted: {} items, {} block terms applied server-side",
                        p.items.len(),
                        terms.len()
                    );
                    return Ok(p);
                }
                Ok(_) => {
                    // The server took the query and returned nothing: that is the
                    // documented refusal, so stop probing for this process.
                    log::warn!(
                        "Exclusion-only listing refused (as documented); falling back to the \
                         unfiltered listing. List-side filtering still applies to galleries \
                         that have been opened."
                    );
                    EXCLUSION_ONLY_REJECTED.store(true, Ordering::Relaxed);
                }
                Err(e) => {
                    // A transport failure is NOT a refusal — remembering it would let one
                    // flaky request disable server-side filtering for the whole process.
                    log::warn!("Exclusion-only probe failed, will retry next time: {}", e);
                }
            }
        }
    }

    fetch_list_with_cache(&url, force_refresh).await
}

/// How long a gallery list may be served from disk before it is refetched.
///
/// Short on purpose. This exists so that *coming back* is instant — returning
/// from a long read, or the process being recreated after the OS reclaimed it,
/// used to refetch the front page and re-download every thumbnail. Minutes, not
/// hours: the feed does move, and a search result set must not go stale.
const LIST_CACHE_TTL_SECS: u64 = 300;

/// Shared by the normal and custom list paths: TTL-cached parse of one listing.
/// Fetch a listing and serve it from the TTL cache when possible, **without**
/// blocked-tag filtering. See [`fetch_list_with_cache`] for the filtered entry
/// point the UI actually calls.
async fn fetch_list_cached_raw(url: &str, max_age_secs: u64) -> Result<crate::parser::GalleryPage> {
    let cache_key = format!("list:{}", url);

    // `max_age_secs == 0` means "do not serve from cache at all" — that is what an
    // explicit pull-to-refresh asks for. Without it, a refresh inside the TTL window
    // returned the very same 25 items in a few milliseconds, which looked like the
    // refresh button did nothing (and there was no log line to argue otherwise,
    // because the cache-hit path never touches the network). The fresh response is
    // still written back below, so the next ordinary read stays warm.
    if max_age_secs > 0 {
        if let Ok(Some(bytes)) = CACHE_ENGINE
            .get_cached_within(&cache_key, max_age_secs)
            .await
        {
            if let Ok(cached) = serde_json::from_slice::<crate::parser::GalleryPage>(&bytes) {
                if !cached.items.is_empty() {
                    log::info!("List cache hit ({} items): {}", cached.items.len(), url);
                    return Ok(cached);
                }
            }
        }
    } else {
        log::info!("List cache bypassed (forced refresh): {}", url);
    }

    let html = NETWORK_CLIENT.get_html(url).await?;
    let page = crate::parser::parse_gallery_list(&html)?;

    // Cache the *parsed* page, not the HTML: re-parsing 60 KB of markup on every
    // re-entry was most of the cost of "coming back".
    if let Ok(json_bytes) = serde_json::to_vec(&page) {
        let _ = CACHE_ENGINE.store(&cache_key, &json_bytes).await;
    }

    Ok(page)
}

/// The listing entry point the UI uses: raw listing, then blocked-tag filter.
///
/// The filter runs on the way *out*, after the raw page has been cached, so the
/// cache keeps the server's real response. Filtering before the cache write
/// would mean un-blocking a tag left the gallery missing until the TTL expired.
///
/// Reminder on coverage: a listing carries no tags, so only galleries whose tags
/// this device has already seen (i.e. opened at least once) can be filtered here.
/// When the user typed a query, the Kotlin layer additionally appends the blocked
/// tags as `-ns:tag` exclusions and the server filters the whole result set.
async fn fetch_list_with_cache(url: &str, force_refresh: bool) -> Result<crate::parser::GalleryPage> {
    let max_age = if force_refresh { 0 } else { LIST_CACHE_TTL_SECS };
    let mut page = fetch_list_cached_raw(url, max_age).await?;
    page.items = crate::blocked::filter_blocked(std::mem::take(&mut page.items));
    Ok(page)
}

/// Fetch a custom gallery list (like watched, popular, toplist, favorites)
pub async fn fetch_custom_list(path: String, page: u32, page_url: Option<String>, query: Option<String>, options: Option<SearchOptions>, force_refresh: bool) -> Result<crate::parser::GalleryPage> {
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

    // Same TTL-cached path as the normal listing; the next_url fix-up below is
    // derived from the URL, so it applies identically to cached and fresh pages.
    let mut result = fetch_list_with_cache(&url, force_refresh).await?;

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
    let cache_key = format!("detail:{}", id);

    // 1. Fast L2 Disk cache lookup for detail — time-bounded, see
    //    DETAIL_CACHE_TTL_SECS for why this one cannot be unbounded.
    if let Ok(Some(cached_bytes)) = CACHE_ENGINE
        .get_cached_within(&cache_key, DETAIL_CACHE_TTL_SECS)
        .await
    {
        if let Ok(cached_detail) = serde_json::from_slice::<GalleryDetail>(&cached_bytes) {
            if !cached_detail.title.is_empty() {
                // Learn from cached opens too: re-visiting a gallery is exactly
                // the case where its tags are worth remembering, and skipping
                // it here would keep the index empty for everything the user
                // had already browsed before this build.
                crate::tag_translator::learn_tags(&cached_detail.tag_groups);
                // Same reasoning for the blocked-tag index: this is what lets a
                // blocked gallery disappear from listings.
                crate::blocked::record_tags(&id, &cached_detail.tag_groups);
                return Ok(cached_detail);
            }
        }
    }

    let html = match NETWORK_CLIENT.get_html(&url).await {
        Ok(html) => html,
        Err(e) => {
            log::error!("Failed to fetch gallery detail {}: {}", id, e);
            return Err(e);
        }
    };

    let detail = match crate::parser::parse_gallery_detail(&html) {
        Ok(mut d) => {
            d.id = id.clone();
            d
        }
        Err(e) => {
            log::error!("Failed to parse gallery detail: {}", e);
            return Err(e);
        }
    };

    // Feed English autocomplete: these are tags that provably exist on
    // E-Hentai, learned with no download required from the user.
    crate::tag_translator::learn_tags(&detail.tag_groups);
    // And remember which tags this gallery carries, so blocked-tag filtering can
    // act on it in listings from now on.
    crate::blocked::record_tags(&id, &detail.tag_groups);

    // Store in disk cache for instant subsequent opens
    if let Ok(json_bytes) = serde_json::to_vec(&detail) {
        let _ = CACHE_ENGINE.store(&cache_key, &json_bytes).await;
    }

    // Spawn background preloader for the first 3 images.
    //
    // Runs through `fetch_and_cache_image` — the exact path the reader uses —
    // so the preload warms the *same cache keys* the reader will look up
    // (viewer URLs). Warming resolved URLs here would leave the reader with a
    // cold cache while having paid for the download.
    //
    // Concurrent, not sequential: the previous `for ... await` loop cost three
    // round-trips of latency end to end, which is precisely the delay before
    // the first pages paint.
    let urls = detail.image_urls.clone();
    tokio::spawn(async move {
        let warm = urls
            .iter()
            .take(3)
            .map(|url| fetch_and_cache_image(url.clone()));
        futures::future::join_all(warm).await;
    });

    Ok(detail)
}

/// Fetch a specific page of gallery thumbnails
pub async fn fetch_gallery_page(id: String, token: String, page: u32) -> Result<GalleryDetail> {
    let site_url = NETWORK_CLIENT.get_site_url().await;
    let url = format!("{}/g/{}/{}/?p={}", site_url, id, token, page);
    let cache_key = format!("page:{}:{}:{}", id, token, page);

    if let Ok(Some(cached_bytes)) = CACHE_ENGINE.get_cached(&cache_key).await {
        if let Ok(cached_page) = serde_json::from_slice::<GalleryDetail>(&cached_bytes) {
            if !cached_page.image_urls.is_empty() {
                return Ok(cached_page);
            }
        }
    }

    let html = NETWORK_CLIENT.get_html(&url).await?;
    let mut detail = crate::parser::parse_gallery_detail(&html)?;
    detail.id = id;

    if let Ok(json_bytes) = serde_json::to_vec(&detail) {
        let _ = CACHE_ENGINE.store(&cache_key, &json_bytes).await;
    }

    Ok(detail)
}

/// Fetch image bytes, using L2 (disk) caching.
/// The cache read/write is self-synchronizing, never held across a network
/// request, so one slow image cannot block other loads.
pub async fn get_image(url: String) -> Result<Bytes> {
    fetch_image_cached(&url, &CACHE_ENGINE, &NETWORK_CLIENT).await
}

/// L2 + L3 cache-aware fetch.
/// `cache` is `&Arc<CacheEngine>` — no outer lock; CacheEngine is self-synchronizing.
async fn fetch_image_cached(
    url: &str,
    cache: &Arc<CacheEngine>,
    network: &Arc<NetworkClient>,
) -> Result<Bytes> {
    // L2: disk hit — returned as-is, no copy
    if let Some(bytes) = cache.get_cached(url).await? {
        return Ok(bytes);
    }

    // L3: network fetch (fully concurrent — no cache lock held during I/O)
    let bytes = network.get_bytes(url).await?;

    // Persist to disk (HTML error bodies are rejected inside store()).
    // `store` writes from the slice, so this does not consume `bytes`.
    let _ = cache.store(url, &bytes).await;

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
        thumb_width: 0,
        thumb_height: 0,
    }]
}

/// Resolves the actual image URL from an E-Hentai viewer page URL
pub async fn resolve_image_url(viewer_url: String) -> Result<String> {
    let html = NETWORK_CLIENT.get_html(&viewer_url).await?;
    crate::parser::parse_image_url(&html)
}

/// Unified reader image pipeline entry point.
///
/// Flow:
///   1. Resolve viewer page URL → real CDN image URL
///   2. Attempt fetch via `reqwest` (same session + UA + proxy as the rest of Rust)
///   3. On 509 quota / 403 / HTML error body → parse `nl` reload param and retry once
///   4. Write bytes to Disk LRU cache (md5-keyed file under app cache dir)
///   5. Return `file://` absolute path so Coil can decode locally without touching the network
///
/// This makes Coil act as a pure local decoder: all EH-specific session management
/// (cookies, UA, proxy, nl-retry) is handled exclusively by the Rust network layer.
pub async fn fetch_and_cache_image(viewer_url: String) -> Result<String> {
    set_image_progress(&viewer_url, 0.05);

    // Step 1: check the disk cache FIRST, keyed by the *viewer* URL.
    //
    // This ordering is the whole point. The cache used to be keyed by the
    // resolved CDN URL, and resolving means fetching the viewer HTML page — so
    // even a fully-cached gallery still paid one HTML request per page just to
    // discover the key it already knew. Reopening a 200-page gallery re-fetched
    // 200 pages from the network for nothing.
    //
    // The reader identifies pages by viewer URL, so that value is known before
    // any I/O, and it is stable for a given page. Keying on it makes a warm
    // reopen literally zero network requests.
    //
    // Note the downloader deliberately keeps using resolved URLs as keys (it
    // never sees viewer URLs) and evicts its own entries when a gallery
    // finishes, so the two conventions do not fight over the same bytes.
    let cache_file = CACHE_ENGINE.get_disk_file_path(&viewer_url);
    if cache_file.exists() {
        set_image_progress(&viewer_url, 1.0);
        return Ok(format!("file://{}", cache_file.to_string_lossy()));
    }

    // Step 2: cache miss — resolve viewer page → real CDN image URL.
    let real_url = resolve_image_url(viewer_url.clone()).await?;
    set_image_progress(&viewer_url, 0.15);

    // Step 3: fetch with nl-retry on quota/stale-key errors.
    let bytes = fetch_with_nl_retry(&real_url, &viewer_url).await?;
    set_image_progress(&viewer_url, 0.98);

    // Step 4: persist under the viewer-URL key so step 1 hits next time.
    let _ = CACHE_ENGINE.store(&viewer_url, &bytes).await;
    set_image_progress(&viewer_url, 1.0);

    // Step 5: return file:// URI so Coil reads from disk
    Ok(format!("file://{}", cache_file.to_string_lossy()))
}

async fn fetch_bytes_reporting_progress(url: &str, viewer_url: &str) -> Result<Bytes> {
    let vu = viewer_url.to_string();
    NETWORK_CLIENT.get_bytes_with_progress(url, move |downloaded, total_opt| {
        let u = vu.clone();
        async move {
            if let Some(total) = total_opt {
                if total > 0 {
                    let ratio = (downloaded as f32) / (total as f32);
                    let p = 0.15 + 0.80 * ratio.clamp(0.0, 1.0);
                    set_image_progress(&u, p);
                }
            }
        }
    }).await
}

/// Fetch image bytes from the real URL, retrying with an `nl` reload parameter
/// when the server returns a quota-exceeded / stale-key error response.
/// E-Hentai returns HTTP 200 with an HTML error page or a redirect for expired
/// image keys. We detect the HTML body and re-resolve via dynamic `nl` tokens
/// parsed from `#loadfail` onclick, allowing source-switching across retries.
async fn fetch_with_nl_retry(real_url: &str, viewer_url: &str) -> Result<Bytes> {
    // First attempt
    match fetch_bytes_reporting_progress(real_url, viewer_url).await {
        Ok(b) if !b.is_empty() && !b.starts_with(b"<") => return Ok(b),
        Ok(_) | Err(_) => {
            log::warn!(
                "fetch_with_nl_retry: first attempt failed or returned HTML for {}; attempting nl reload",
                real_url
            );
        }
    }

    // nl dynamic retry loop (up to 2 rounds of source-switching)
    let mut current_viewer_url = viewer_url.to_string();
    let mut last_error = anyhow::anyhow!("nl-retry: image unavailable");

    for attempt in 1..=2 {
        // Courteous backoff between retries to avoid triggering 509 Bandwidth Exceeded / IP rate limits
        if attempt > 1 {
            tokio::time::sleep(std::time::Duration::from_millis(800)).await;
        }

        let viewer_html = match NETWORK_CLIENT.get_html(&current_viewer_url).await {
            Ok(h) => h,
            Err(e) => {
                last_error = anyhow::anyhow!("nl-retry: failed to read viewer page {}: {}", current_viewer_url, e);
                break;
            }
        };

        let nl_token = crate::parser::parse_nl_token(&viewer_html);
        let nl_url = match &nl_token {
            Some(tok) => append_nl_param(&current_viewer_url, tok),
            None => {
                log::warn!("nl-retry: no nl token found in viewer page {}", current_viewer_url);
                current_viewer_url.clone()
            }
        };

        let target_html = if nl_url != current_viewer_url {
            tokio::time::sleep(std::time::Duration::from_millis(300)).await;
            match NETWORK_CLIENT.get_html(&nl_url).await {
                Ok(h) => {
                    current_viewer_url = nl_url;
                    h
                }
                Err(e) => {
                    last_error = anyhow::anyhow!("nl-retry: failed to reload viewer with nl: {}", e);
                    continue;
                }
            }
        } else {
            viewer_html
        };

        let new_real_url = match crate::parser::parse_image_url(&target_html) {
            Ok(u) => u,
            Err(e) => {
                last_error = anyhow::anyhow!("nl-retry: failed to parse image url: {}", e);
                continue;
            }
        };

        match fetch_bytes_reporting_progress(&new_real_url, viewer_url).await {
            Ok(bytes) if !bytes.is_empty() && !bytes.starts_with(b"<") => {
                // Deliberately does NOT cache here. The cache key is the
                // caller's choice: `fetch_and_cache_image` keys by viewer URL
                // while the downloader keys by resolved URL, and this helper
                // cannot know which convention applies. Letting it store under
                // `new_real_url` too would create a second, unfindable copy.
                return Ok(bytes);
            }
            Ok(_) => {
                last_error = anyhow::anyhow!("nl-retry (attempt {}): returned HTML instead of image data", attempt);
            }
            Err(e) => {
                last_error = anyhow::anyhow!("nl-retry (attempt {}): request failed: {}", attempt, e);
            }
        }
    }

    Err(last_error)
}

/// Appends the real `nl={token}` reload parameter to a viewer URL,
/// cleanly stripping any existing stale `nl=...` query parameter.
fn append_nl_param(viewer_url: &str, token: &str) -> String {
    let clean_url = if let Some(idx) = viewer_url.find("nl=") {
        let before = &viewer_url[..idx];
        let after = &viewer_url[idx..];
        let end_idx = after.find('&').map(|e| idx + e + 1).unwrap_or(viewer_url.len());
        let before_clean = before.trim_end_matches('&').trim_end_matches('?');
        let after_clean = &viewer_url[end_idx..];
        if after_clean.is_empty() {
            before_clean.to_string()
        } else if before_clean.contains('?') {
            format!("{}&{}", before_clean, after_clean)
        } else {
            format!("{}?{}", before_clean, after_clean)
        }
    } else {
        viewer_url.to_string()
    };
    let sep = if clean_url.contains('?') { '&' } else { '?' };
    format!("{}{}nl={}", clean_url, sep, token)
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

/// Restart a stopped or failed download from its stored page list.
pub async fn resume_download(gid: String) -> Result<()> {
    crate::downloader::resume_download(gid).await
}

/// Absolute paths of a gallery's downloaded pages, in page order. Empty when
/// the gallery has nothing on disk. Backs the offline reader.
pub async fn get_downloaded_pages(gid: String) -> Vec<String> {
    crate::downloader::get_downloaded_pages(&gid).await
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
    // CacheEngine is self-synchronizing — call directly without outer lock.
    let _ = CACHE_ENGINE.remove(&url).await;
}

/// Delete disk cache files older than `days` days and flush L1 memory cache.
/// Called by the Kotlin advanced settings "auto-clean" action.
/// Returns the number of bytes freed.
pub async fn clear_expired_cache(days: u32) -> anyhow::Result<u64> {
    CACHE_ENGINE.clear_expired(days as u64).await
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
        ("postcomment", "Post Comment"),
    ];
    
    let res = NETWORK_CLIENT.post_form(&url, &form).await?;
    let lower = res.to_lowercase();
    if lower.contains("requires you to log on") || lower.contains("must be logged in") || lower.contains("not logged in") || lower.contains("have to register") {
        anyhow::bail!("评论发送失败：账号未登录或 Cookie 已失效");
    }
    if lower.contains("your comment was not posted") || lower.contains("posting comments too quickly") {
        anyhow::bail!("评论未被发布：发送过于频繁或被服务器拦截");
    }
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

/// Remove a tag from the account's watched tags (My Tags).
pub async fn remove_watched_tag(tag: String) -> Result<String> {
    let site_url = NETWORK_CLIENT.get_site_url().await;
    let url = format!("{}/mytags", site_url);
    let html = NETWORK_CLIENT.get_html(&url).await?;
    if html.contains("requires you to log on") {
        anyhow::bail!("需要先登录 E-Hentai 账号才能管理标签");
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
                let remaining: Vec<&str> = trimmed.lines().filter(|l| l.trim() != tag).collect();
                if remaining.len() != trimmed.lines().count() {
                    value = remaining.join("\n");
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
        anyhow::bail!("取消关注失败：会话已失效，请重新登录");
    }

    if watch_updated {
        Ok(format!("已取消关注标签: {}", tag))
    } else {
        Ok(format!("标签不在关注列表中: {}", tag))
    }
}

/// Vote (like/dislike) on a gallery comment.
/// `url` is the vote link parsed from the detail page HTML
/// (`...gallerycomments.php?gid=...&act=vote&comment_id=...&vote=1`).
/// Relative links are completed against the current site URL.
pub async fn vote_comment(url: String) -> Result<String> {
    let site = NETWORK_CLIENT.get_site_url().await;
    let full = if url.starts_with("http") {
        url.clone()
    } else if url.starts_with('/') {
        format!("{}{}", site, url)
    } else {
        format!("{}/{}", site, url)
    };
    log::info!("Vote comment → {}", full);

    // Attempt POST form submission first (as E-Hentai JS vote_comment function uses POST)
    if let Some(pos) = full.find('?') {
        let base_path = &full[..pos];
        let query_str = &full[pos + 1..];
        let mut params = Vec::new();
        for pair in query_str.split('&') {
            if let Some((k, v)) = pair.split_once('=') {
                params.push((k, v));
            }
        }
        if !params.is_empty() {
            if let Ok(post_res) = NETWORK_CLIENT.post_form(base_path, &params).await {
                let lower = post_res.to_lowercase();
                if lower.contains("not logged in") || lower.contains("insufficient") || lower.contains("requires you to log on") {
                    anyhow::bail!("评论投票失败：请确认已登录账号");
                }
                return Ok(post_res);
            }
        }
    }

    let res = NETWORK_CLIENT.get_html(&full).await?;
    let lower = res.to_lowercase();
    if lower.contains("not logged in") || lower.contains("insufficient") || lower.contains("requires you to log on") {
        anyhow::bail!("评论投票失败：请确认已登录账号");
    }
    Ok(res)
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

/// Fetch user configuration from uconfig.php
pub async fn fetch_eh_web_config() -> Result<EhWebConfig> {
    let site_url = NETWORK_CLIENT.get_site_url().await;
    let url = format!("{}/uconfig.php", site_url);
    let html = NETWORK_CLIENT.get_html(&url).await?;
    if html.contains("requires you to log on") || html.contains("You must be logged in") {
        anyhow::bail!("未登录账号或登录态失效，请重新登录");
    }
    crate::parser::parse_eh_web_config(&html)
}

#[cfg(test)]
mod tests {
    use super::{sanitize_filename, append_nl_param};

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

    #[test]
    fn test_append_nl_param_fresh_url() {
        let u = "https://e-hentai.org/s/123/456-1";
        assert_eq!(append_nl_param(u, "98765-11223"), "https://e-hentai.org/s/123/456-1?nl=98765-11223");
    }

    #[test]
    fn test_append_nl_param_replaces_existing_nl() {
        let u = "https://e-hentai.org/s/123/456-1?nl=111-222";
        assert_eq!(append_nl_param(u, "98765-11223"), "https://e-hentai.org/s/123/456-1?nl=98765-11223");
    }

    #[test]
    fn test_append_nl_param_preserves_other_params() {
        let u = "https://e-hentai.org/s/123/456-1?foo=bar&nl=111-222&baz=qux";
        assert_eq!(append_nl_param(u, "98765-11223"), "https://e-hentai.org/s/123/456-1?foo=bar&baz=qux&nl=98765-11223");
    }
}
