use jni::JNIEnv;
use jni::objects::{GlobalRef, JClass, JString, JValue};
use jni::sys::{jboolean, jint, jfloat, jlong, jstring};
use jni::JavaVM;
use crate::api;
use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};
use tokio::sync::Semaphore;

lazy_static::lazy_static! {
    static ref RUNTIME: tokio::runtime::Runtime = tokio::runtime::Builder::new_multi_thread()
        // 8 rather than 4: this runtime also drives CPU-bound work (HTML parsing,
        // the tag-database search), which would otherwise occupy workers that
        // concurrent image downloads need. The tasks are mostly I/O-bound, so
        // the extra threads cost little.
        .worker_threads(8)
        .enable_all()
        .build()
        .expect("Failed to initialize Tokio runtime for Eh-ru JNI");
}

fn jstring_to_string(env: &mut JNIEnv, js: JString) -> Option<String> {
    if js.is_null() {
        return None;
    }
    match env.get_string(&js) {
        Ok(s) => Some(s.into()),
        Err(_) => None,
    }
}

fn string_to_jstring<'local>(env: &mut JNIEnv<'local>, s: &str) -> jstring {
    match env.new_string(s) {
        Ok(js) => js.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

fn result_to_json<T: serde::Serialize>(res: anyhow::Result<T>) -> String {
    match res {
        Ok(data) => serde_json::to_string(&data).unwrap_or_else(|e| format!("{{\"error\":\"{}\"}}", e)),
        Err(e) => serde_json::json!({ "error": e.to_string() }).to_string(),
    }
}

#[no_mangle]
pub extern "C" fn Java_com_example_ehviewer_1scaffold_rust_EhRustBridge_healthCheck<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    name: JString<'local>,
) -> jstring {
    let name_str = jstring_to_string(&mut env, name).unwrap_or_else(|| "Android".to_string());
    let res = RUNTIME.block_on(api::health_check(name_str));
    string_to_jstring(&mut env, &res)
}

#[no_mangle]
pub extern "C" fn Java_com_example_ehviewer_1scaffold_rust_EhRustBridge_initBackend<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    cache_dir: JString<'local>,
    enable_eh_host: jboolean,
) -> jboolean {
    let cache_dir_str = jstring_to_string(&mut env, cache_dir).unwrap_or_default();
    let res = RUNTIME.block_on(api::init_backend(cache_dir_str, enable_eh_host != 0));
    (res.is_ok()) as jboolean
}

#[no_mangle]
pub extern "C" fn Java_com_example_ehviewer_1scaffold_rust_EhRustBridge_syncCookies<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    cookie_str: JString<'local>,
) -> jboolean {
    let s = jstring_to_string(&mut env, cookie_str).unwrap_or_default();
    let res = RUNTIME.block_on(api::sync_cookies(s));
    (res.is_ok()) as jboolean
}

#[no_mangle]
pub extern "C" fn Java_com_example_ehviewer_1scaffold_rust_EhRustBridge_setUserAgent<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    user_agent: JString<'local>,
) -> jboolean {
    let ua = jstring_to_string(&mut env, user_agent).unwrap_or_default();
    let res = RUNTIME.block_on(api::set_user_agent(ua));
    (res.is_ok()) as jboolean
}

#[no_mangle]
pub extern "C" fn Java_com_example_ehviewer_1scaffold_rust_EhRustBridge_setSiteUrl<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    url: JString<'local>,
) -> jboolean {
    let u = jstring_to_string(&mut env, url).unwrap_or_default();
    let res = RUNTIME.block_on(api::set_site_url(u));
    (res.is_ok()) as jboolean
}

#[no_mangle]
pub extern "C" fn Java_com_example_ehviewer_1scaffold_rust_EhRustBridge_fetchFrontPage<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    query: JString<'local>,
    options_json: JString<'local>,
) -> jstring {
    let q = jstring_to_string(&mut env, query);
    let opt: Option<api::SearchOptions> = jstring_to_string(&mut env, options_json)
        .and_then(|j| serde_json::from_str(&j).ok());
    
    let items = RUNTIME.block_on(api::fetch_front_page(q, opt));
    let json = serde_json::to_string(&items).unwrap_or_else(|e| format!("{{\"error\":\"{}\"}}", e));
    string_to_jstring(&mut env, &json)
}

#[no_mangle]
pub extern "C" fn Java_com_example_ehviewer_1scaffold_rust_EhRustBridge_fetchGalleryList<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    page: jint,
    page_url: JString<'local>,
    query: JString<'local>,
    options_json: JString<'local>,
    force_refresh: jboolean,
) -> jstring {
    let pu = jstring_to_string(&mut env, page_url);
    let q = jstring_to_string(&mut env, query);
    let opt: Option<api::SearchOptions> = jstring_to_string(&mut env, options_json)
        .and_then(|j| serde_json::from_str(&j).ok());

    let res = RUNTIME.block_on(api::fetch_gallery_list(
        page as u32,
        pu,
        q,
        opt,
        force_refresh != 0,
    ));
    let json = result_to_json(res);
    string_to_jstring(&mut env, &json)
}

#[no_mangle]
pub extern "C" fn Java_com_example_ehviewer_1scaffold_rust_EhRustBridge_fetchCustomList<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    path: JString<'local>,
    page: jint,
    page_url: JString<'local>,
    query: JString<'local>,
    options_json: JString<'local>,
    force_refresh: jboolean,
) -> jstring {
    let p = jstring_to_string(&mut env, path).unwrap_or_default();
    let pu = jstring_to_string(&mut env, page_url);
    let q = jstring_to_string(&mut env, query);
    let opt: Option<api::SearchOptions> = jstring_to_string(&mut env, options_json)
        .and_then(|j| serde_json::from_str(&j).ok());

    let res = RUNTIME.block_on(api::fetch_custom_list(
        p,
        page as u32,
        pu,
        q,
        opt,
        force_refresh != 0,
    ));
    let json = result_to_json(res);
    string_to_jstring(&mut env, &json)
}

#[no_mangle]
pub extern "C" fn Java_com_example_ehviewer_1scaffold_rust_EhRustBridge_fetchGalleryDetail<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    id: JString<'local>,
) -> jstring {
    let id_str = jstring_to_string(&mut env, id).unwrap_or_default();
    let res = RUNTIME.block_on(api::fetch_gallery_detail(id_str));
    let json = result_to_json(res);
    string_to_jstring(&mut env, &json)
}

#[no_mangle]
pub extern "C" fn Java_com_example_ehviewer_1scaffold_rust_EhRustBridge_fetchGalleryPage<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    id: JString<'local>,
    token: JString<'local>,
    page: jint,
) -> jstring {
    let id_str = jstring_to_string(&mut env, id).unwrap_or_default();
    let token_str = jstring_to_string(&mut env, token).unwrap_or_default();
    let res = RUNTIME.block_on(api::fetch_gallery_page(id_str, token_str, page as u32));
    let json = result_to_json(res);
    string_to_jstring(&mut env, &json)
}

#[no_mangle]
pub extern "C" fn Java_com_example_ehviewer_1scaffold_rust_EhRustBridge_resolveImageUrl<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    viewer_url: JString<'local>,
) -> jstring {
    let u = jstring_to_string(&mut env, viewer_url).unwrap_or_default();
    let res = RUNTIME.block_on(api::resolve_image_url(u));
    let s = res.unwrap_or_default();
    string_to_jstring(&mut env, &s)
}

/// Unified reader image pipeline: resolve + nl-retry + LRU disk cache.
/// Returns a "file://<absolute_path>" string that Coil reads locally,
/// or an empty string on unrecoverable error.
#[no_mangle]
pub extern "C" fn Java_com_example_ehviewer_1scaffold_rust_EhRustBridge_fetchAndCacheImage<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    viewer_url: JString<'local>,
) -> jstring {
    let u = jstring_to_string(&mut env, viewer_url).unwrap_or_default();
    let res = RUNTIME.block_on(api::fetch_and_cache_image(u));
    match res {
        Ok(path) => string_to_jstring(&mut env, &path),
        Err(e) => {
            log::error!("fetchAndCacheImage failed: {}", e);
            string_to_jstring(&mut env, "")
        }
    }
}

/// Retrieve current image download progress for a reader viewer URL (0.0 .. 1.0)
#[no_mangle]
pub extern "C" fn Java_com_example_ehviewer_1scaffold_rust_EhRustBridge_getImageProgress<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    viewer_url: JString<'local>,
) -> jfloat {
    let u = jstring_to_string(&mut env, viewer_url).unwrap_or_default();
    crate::api::get_image_progress(&u)
}

#[no_mangle]
pub extern "C" fn Java_com_example_ehviewer_1scaffold_rust_EhRustBridge_startDownload<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    gid: JString<'local>,
    token: JString<'local>,
    title: JString<'local>,
    image_urls_json: JString<'local>,
    total_pages: jint,
) -> jboolean {
    let g = jstring_to_string(&mut env, gid).unwrap_or_default();
    let tok = jstring_to_string(&mut env, token).unwrap_or_default();
    let t = jstring_to_string(&mut env, title).unwrap_or_default();
    let urls: Vec<String> = jstring_to_string(&mut env, image_urls_json)
        .and_then(|j| serde_json::from_str(&j).ok())
        .unwrap_or_default();

    let res = RUNTIME.block_on(api::start_download(g, tok, t, urls, total_pages as u32));
    (res.is_ok()) as jboolean
}

#[no_mangle]
pub extern "C" fn Java_com_example_ehviewer_1scaffold_rust_EhRustBridge_pauseDownload<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    gid: JString<'local>,
) -> jboolean {
    let g = jstring_to_string(&mut env, gid).unwrap_or_default();
    let res = RUNTIME.block_on(api::pause_download(g));
    (res.is_ok()) as jboolean
}

#[no_mangle]
pub extern "C" fn Java_com_example_ehviewer_1scaffold_rust_EhRustBridge_resumeDownload<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    gid: JString<'local>,
) -> jboolean {
    let g = jstring_to_string(&mut env, gid).unwrap_or_default();
    let res = RUNTIME.block_on(api::resume_download(g));
    (res.is_ok()) as jboolean
}

/// Absolute paths of a gallery's downloaded pages, page-ordered, as a JSON
/// array. Backs the offline reader (opening a finished download).
#[no_mangle]
pub extern "C" fn Java_com_example_ehviewer_1scaffold_rust_EhRustBridge_getDownloadedPages<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    gid: JString<'local>,
) -> jstring {
    let g = jstring_to_string(&mut env, gid).unwrap_or_default();
    let pages = RUNTIME.block_on(api::get_downloaded_pages(g));
    let json = serde_json::to_string(&pages).unwrap_or_else(|_| "[]".to_string());
    string_to_jstring(&mut env, &json)
}

#[no_mangle]
pub extern "C" fn Java_com_example_ehviewer_1scaffold_rust_EhRustBridge_deleteDownload<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    gid: JString<'local>,
) -> jboolean {
    let g = jstring_to_string(&mut env, gid).unwrap_or_default();
    let res = RUNTIME.block_on(api::delete_download(g));
    (res.is_ok()) as jboolean
}

#[no_mangle]
pub extern "C" fn Java_com_example_ehviewer_1scaffold_rust_EhRustBridge_getDownloadTasks<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jstring {
    let tasks = RUNTIME.block_on(api::get_download_tasks());
    let json = serde_json::to_string(&tasks).unwrap_or_else(|e| format!("{{\"error\":\"{}\"}}", e));
    string_to_jstring(&mut env, &json)
}

#[no_mangle]
pub extern "C" fn Java_com_example_ehviewer_1scaffold_rust_EhRustBridge_setDownloadConcurrency<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    n: jint,
) -> jboolean {
    let res = RUNTIME.block_on(api::set_download_concurrency(n as u32));
    (res.is_ok()) as jboolean
}

#[no_mangle]
pub extern "C" fn Java_com_example_ehviewer_1scaffold_rust_EhRustBridge_setDownloadDir<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    path: JString<'local>,
) -> jboolean {
    let p = jstring_to_string(&mut env, path).unwrap_or_default();
    let res = RUNTIME.block_on(api::set_download_dir(p));
    (res.is_ok()) as jboolean
}

#[no_mangle]
pub extern "C" fn Java_com_example_ehviewer_1scaffold_rust_EhRustBridge_getDownloadDir<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jstring {
    let dir = RUNTIME.block_on(api::get_download_dir()).unwrap_or_default();
    string_to_jstring(&mut env, &dir)
}

#[no_mangle]
pub extern "C" fn Java_com_example_ehviewer_1scaffold_rust_EhRustBridge_fetchTorrents<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    gid: JString<'local>,
    token: JString<'local>,
) -> jstring {
    let g = jstring_to_string(&mut env, gid).unwrap_or_default();
    let t = jstring_to_string(&mut env, token).unwrap_or_default();
    let res = RUNTIME.block_on(api::fetch_torrents(g, t));
    let json = result_to_json(res);
    string_to_jstring(&mut env, &json)
}

/// Fetch one `.torrent` file from the tracker and save it under the downloads
/// directory (`<downloads>/torrents/<sanitized name>.torrent`).
///
/// Returns the saved absolute path as a JSON string, or `{"error": "..."}`.
#[no_mangle]
pub extern "C" fn Java_com_example_ehviewer_1scaffold_rust_EhRustBridge_downloadTorrent<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    name: JString<'local>,
    hash: JString<'local>,
    token: JString<'local>,
) -> jstring {
    let n = jstring_to_string(&mut env, name).unwrap_or_default();
    let h = jstring_to_string(&mut env, hash).unwrap_or_default();
    let t = jstring_to_string(&mut env, token).unwrap_or_default();
    let res = RUNTIME.block_on(api::download_torrent(n, h, t));
    let json = result_to_json(res);
    string_to_jstring(&mut env, &json)
}

#[no_mangle]
pub extern "C" fn Java_com_example_ehviewer_1scaffold_rust_EhRustBridge_addFavorite<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    gid: JString<'local>,
    token: JString<'local>,
    favcat: JString<'local>,
    favnote: JString<'local>,
) -> jstring {
    let g = jstring_to_string(&mut env, gid).unwrap_or_default();
    let t = jstring_to_string(&mut env, token).unwrap_or_default();
    let cat = jstring_to_string(&mut env, favcat).unwrap_or_default();
    let note = jstring_to_string(&mut env, favnote).unwrap_or_default();
    let res = RUNTIME.block_on(api::add_favorite(g, t, cat, note));
    let json = result_to_json(res);
    string_to_jstring(&mut env, &json)
}

#[no_mangle]
pub extern "C" fn Java_com_example_ehviewer_1scaffold_rust_EhRustBridge_postComment<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    gid: JString<'local>,
    token: JString<'local>,
    content: JString<'local>,
) -> jstring {
    let g = jstring_to_string(&mut env, gid).unwrap_or_default();
    let t = jstring_to_string(&mut env, token).unwrap_or_default();
    let c = jstring_to_string(&mut env, content).unwrap_or_default();
    let res = RUNTIME.block_on(api::post_comment(g, t, c));
    let json = result_to_json(res);
    string_to_jstring(&mut env, &json)
}

#[no_mangle]
pub extern "C" fn Java_com_example_ehviewer_1scaffold_rust_EhRustBridge_voteGallery<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    gid: JString<'local>,
    token: JString<'local>,
    rating: jint,
) -> jstring {
    let g = jstring_to_string(&mut env, gid).unwrap_or_default();
    let t = jstring_to_string(&mut env, token).unwrap_or_default();
    let res = RUNTIME.block_on(api::vote_gallery(g, t, rating as u32));
    let json = result_to_json(res);
    string_to_jstring(&mut env, &json)
}

#[no_mangle]
pub extern "C" fn Java_com_example_ehviewer_1scaffold_rust_EhRustBridge_voteComment<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    url: JString<'local>,
) -> jstring {
    let u = jstring_to_string(&mut env, url).unwrap_or_default();
    let res = RUNTIME.block_on(api::vote_comment(u));
    let json = result_to_json(res);
    string_to_jstring(&mut env, &json)
}

#[no_mangle]
pub extern "C" fn Java_com_example_ehviewer_1scaffold_rust_EhRustBridge_fetchMoreComments<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    gid: JString<'local>,
    token: JString<'local>,
    page: jint,
) -> jstring {
    let g = jstring_to_string(&mut env, gid).unwrap_or_default();
    let t = jstring_to_string(&mut env, token).unwrap_or_default();
    let comments = RUNTIME.block_on(api::fetch_more_comments(g, t, page as u32));
    let json = serde_json::to_string(&comments).unwrap_or_else(|e| format!("{{\"error\":\"{}\"}}", e));
    string_to_jstring(&mut env, &json)
}

#[no_mangle]
pub extern "C" fn Java_com_example_ehviewer_1scaffold_rust_EhRustBridge_translateTagSync<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    namespace: JString<'local>,
    tag: JString<'local>,
) -> jstring {
    let ns = jstring_to_string(&mut env, namespace).unwrap_or_default();
    let t = jstring_to_string(&mut env, tag).unwrap_or_default();
    let trans = api::translate_tag_sync(ns, t);
    string_to_jstring(&mut env, &trans)
}

/// Batch variant of [`translateTagSync`]: takes a JSON array of
/// `[["namespace","tag"], ...]` and returns a JSON object of
/// `{"namespace:tag": "translation"}`.
///
/// Exists because a gallery can carry 60–120 tags, and one JNI round-trip plus
/// one `TAG_DB` lock acquisition *per tag* was the dominant cost of opening a
/// detail page. One call replaces the whole batch.
#[no_mangle]
pub extern "C" fn Java_com_example_ehviewer_1scaffold_rust_EhRustBridge_translateTagsJson<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    pairs_json: JString<'local>,
) -> jstring {
    let raw = jstring_to_string(&mut env, pairs_json).unwrap_or_default();
    let pairs: Vec<(String, String)> = serde_json::from_str(&raw).unwrap_or_default();
    let map = api::translate_tags_batch(pairs);
    let json = serde_json::to_string(&map).unwrap_or_else(|_| "{}".to_string());
    string_to_jstring(&mut env, &json)
}

#[no_mangle]
pub extern "C" fn Java_com_example_ehviewer_1scaffold_rust_EhRustBridge_searchTagByChinese<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    keyword: JString<'local>,
) -> jstring {
    let kw = jstring_to_string(&mut env, keyword).unwrap_or_default();
    let suggestions = api::search_tag_by_chinese(kw);
    let json = serde_json::to_string(&suggestions).unwrap_or_else(|e| format!("{{\"error\":\"{}\"}}", e));
    string_to_jstring(&mut env, &json)
}

/// Server-side tag completion (`api.php` method `tagsuggest`).
///
/// Kept separate from `searchTagByChinese` on purpose: that one is a purely
/// in-memory scan safe to run on every keystroke, while this one is a network
/// round-trip the UI fires debounced and merges with the local results.
#[no_mangle]
pub extern "C" fn Java_com_example_ehviewer_1scaffold_rust_EhRustBridge_suggestTagsOnline<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    keyword: JString<'local>,
) -> jstring {
    let kw = jstring_to_string(&mut env, keyword).unwrap_or_default();
    let suggestions = RUNTIME.block_on(api::suggest_tags_online(kw));
    let json = match suggestions {
        Ok(list) => serde_json::to_string(&list).unwrap_or_else(|_| "[]".to_string()),
        Err(e) => {
            log::warn!("suggestTagsOnline failed: {}", e);
            "[]".to_string()
        }
    };
    string_to_jstring(&mut env, &json)
}

#[no_mangle]
pub extern "C" fn Java_com_example_ehviewer_1scaffold_rust_EhRustBridge_fetchEhWebConfig<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jstring {
    let res = RUNTIME.block_on(api::fetch_eh_web_config());
    let json = result_to_json(res);
    string_to_jstring(&mut env, &json)
}

#[no_mangle]
pub extern "C" fn Java_com_example_ehviewer_1scaffold_rust_EhRustBridge_downloadTagDb<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    path: JString<'local>,
) -> jboolean {
    let p = jstring_to_string(&mut env, path).unwrap_or_default();
    let res = RUNTIME.block_on(api::download_tag_db(p));
    (res.is_ok()) as jboolean
}

#[no_mangle]
pub extern "C" fn Java_com_example_ehviewer_1scaffold_rust_EhRustBridge_loadTagDb<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    path: JString<'local>,
) -> jboolean {
    let p = jstring_to_string(&mut env, path).unwrap_or_default();
    let res = RUNTIME.block_on(api::load_tag_db(p));
    (res.is_ok()) as jboolean
}

/// Delete disk-cache files older than `older_than_days` days and flush the
/// L1 memory cache.  Returns the number of bytes freed (as a jlong / i64).
#[no_mangle]
pub extern "C" fn Java_com_example_ehviewer_1scaffold_rust_EhRustBridge_clearExpiredCache<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    older_than_days: jni::sys::jint,
) -> jni::sys::jlong {
    let days = older_than_days.max(1) as u32;
    match RUNTIME.block_on(api::clear_expired_cache(days)) {
        Ok(freed) => freed as jni::sys::jlong,
        Err(e) => {
            log::error!("clearExpiredCache failed: {}", e);
            -1
        }
    }
}

/// Add a tag to the account's watched tag list (EH My Tags / /watched page).
/// Returns the result message or empty string on error.
#[no_mangle]
pub extern "C" fn Java_com_example_ehviewer_1scaffold_rust_EhRustBridge_addWatchedTag<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    tag: JString<'local>,
) -> jstring {
    let t = jstring_to_string(&mut env, tag).unwrap_or_default();
    match RUNTIME.block_on(api::add_watched_tag(t)) {
        Ok(msg) => string_to_jstring(&mut env, &msg),
        Err(e) => {
            log::error!("addWatchedTag failed: {}", e);
            string_to_jstring(&mut env, "")
        }
    }
}

/// Replace the blocked-tag list.
///
/// `tags_json` is a JSON array; each entry is either `namespace:tag` (what
/// long-pressing a tag on the detail screen produces) or a bare tag typed by
/// hand in the settings screen. Kotlin's SharedPreferences stays authoritative
/// and replays this at cold start.
///
/// Always returns true: the list is stored unconditionally, and a malformed
/// payload degrades to an empty list rather than an error worth surfacing.
#[no_mangle]
pub extern "C" fn Java_com_example_ehviewer_1scaffold_rust_EhRustBridge_setBlockedTags<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    tags_json: JString<'local>,
) -> jboolean {
    let raw = jstring_to_string(&mut env, tags_json).unwrap_or_default();
    let tags: Vec<String> = serde_json::from_str(&raw).unwrap_or_default();
    api::set_blocked_tags(tags);
    1
}

/// Keep only the non-blocked gids out of a JSON array, returning a JSON array.
///
/// Backs the "block list changed while a list was on screen" case: the UI prunes
/// what it already has instead of refetching and losing the scroll position.
#[no_mangle]
pub extern "C" fn Java_com_example_ehviewer_1scaffold_rust_EhRustBridge_filterBlockedGalleryIds<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    gids_json: JString<'local>,
) -> jstring {
    let raw = jstring_to_string(&mut env, gids_json).unwrap_or_default();
    let gids: Vec<String> = serde_json::from_str(&raw).unwrap_or_default();
    let kept = api::filter_blocked_ids(gids);
    let json = serde_json::to_string(&kept).unwrap_or_else(|_| "[]".to_string());
    string_to_jstring(&mut env, &json)
}

/// Remove a tag from the account's watched tag list (EH My Tags / /watched page).
/// Returns the result message or empty string on error.
#[no_mangle]
pub extern "C" fn Java_com_example_ehviewer_1scaffold_rust_EhRustBridge_removeWatchedTag<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    tag: JString<'local>,
) -> jstring {
    let t = jstring_to_string(&mut env, tag).unwrap_or_default();
    match RUNTIME.block_on(api::remove_watched_tag(t)) {
        Ok(msg) => string_to_jstring(&mut env, &msg),
        Err(e) => {
            log::error!("removeWatchedTag failed: {}", e);
            string_to_jstring(&mut env, "")
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Async image fetch — Rust owns the task, Kotlin just parks a coroutine
// ─────────────────────────────────────────────────────────────────────────────
//
// Every other entry point in this file ends in `RUNTIME.block_on(...)`, which
// pins the *calling* Kotlin thread for the whole network round-trip. That makes
// "requests in flight" and "JVM threads consumed" the same number, so reader
// throughput is capped by `Dispatchers.IO`'s 64 threads no matter how fast the
// network is.
//
// The image path is the one worth fixing: it is high frequency, it is naturally
// cancellable (scroll past a page and the fetch should die), and its result is a
// small string. So it is the one path that runs fully async.
//
// Metadata calls (list / detail / comments / favourites) deliberately stay on
// `block_on`: they are low frequency, the user is actively waiting on them, and
// cancellation would mean nothing. See docs/JNI_ASYNC_DESIGN_REVIEW.md.

/// Upper bound on concurrently running image tasks.
///
/// Decoupling from JVM threads does NOT mean the limit can be dropped: hundreds
/// of parallel sockets would saturate the link and invite CDN rate limiting.
/// This is now a *policy* cap instead of an accident of the thread pool.
const MAX_CONCURRENT_IMAGE_TASKS: usize = 24;

/// `JavaVM` + `EhRustBridge` class ref, captured lazily on the first submit.
///
/// Deliberately not done in `JNI_OnLoad`: the values are already in hand at
/// every JNI call, there is nothing to do before the first one, and it avoids
/// hand-writing an FFI entry point whose exact signature is easy to get wrong.
static JVM: OnceLock<JavaVM> = OnceLock::new();
static BRIDGE_CLASS: OnceLock<GlobalRef> = OnceLock::new();

struct ImageTask {
    /// Set by `nativeCancelImageFetch`. Checked again after `spawn`, because a
    /// cancel can land while the `AbortHandle` does not exist yet.
    abort_requested: bool,
    /// Filled in immediately after `spawn`.
    handle: Option<tokio::task::AbortHandle>,
}

static IMAGE_TASKS: OnceLock<Mutex<HashMap<i64, ImageTask>>> = OnceLock::new();
static IMAGE_SLOTS: OnceLock<Semaphore> = OnceLock::new();

fn image_tasks() -> std::sync::MutexGuard<'static, HashMap<i64, ImageTask>> {
    IMAGE_TASKS
        .get_or_init(|| Mutex::new(HashMap::new()))
        .lock()
        // A panic while the map was locked must not turn every later call into
        // another panic; it only holds bookkeeping, so recover the data.
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

fn image_slots() -> &'static Semaphore {
    IMAGE_SLOTS.get_or_init(|| Semaphore::new(MAX_CONCURRENT_IMAGE_TASKS))
}

/// Capture the JVM handle and bridge class on first use.
fn cache_jvm_and_class(env: &mut JNIEnv, class: &JClass) {
    if JVM.get().is_none() {
        match env.get_java_vm() {
            Ok(vm) => {
                let _ = JVM.set(vm);
            }
            Err(e) => log::error!("could not cache JavaVM: {}", e),
        }
    }
    if BRIDGE_CLASS.get().is_none() {
        match env.new_global_ref(class) {
            Ok(g) => {
                let _ = BRIDGE_CLASS.set(g);
            }
            Err(e) => log::error!("could not cache bridge class ref: {}", e),
        }
    }
}

/// Guarantees Kotlin is always woken and the registry entry always removed.
///
/// This is the fix for the one *fatal* flaw in the originally proposed design:
/// if the task panicked — or the future was dropped by an abort — the plain
/// "call dispatch at the end of the async block" approach never reaches
/// `dispatch_*`, so the Kotlin coroutine stays suspended **forever**, with no
/// log and no recovery. A `Drop` guard runs on both those paths.
struct CompletionGuard {
    task_id: i64,
    /// Set as soon as a result exists, so the guard does not fire a second,
    /// contradictory callback.
    completed: bool,
}

impl Drop for CompletionGuard {
    fn drop(&mut self) {
        image_tasks().remove(&self.task_id);
        if !self.completed {
            // Cancelled, or panicked. If Kotlin already discarded this task id
            // the callback is a harmless no-op — which is exactly why the Kotlin
            // side removes the continuation atomically.
            dispatch_error(self.task_id, "image request ended without a result");
        }
    }
}

/// Attach the current (Tokio worker) thread as a daemon and hand the closure a
/// usable `JNIEnv` plus the bridge class.
///
/// `attach_current_thread_as_daemon` is cheap on repeat calls: the thread is
/// attached once and the `JNIEnv*` is reused from thread-local storage, so a
/// long-lived Tokio worker pays this only on its first callback. Attaching as a
/// daemon also means those threads never block JVM shutdown.
fn with_bridge_env<F: FnOnce(&mut JNIEnv, &JClass)>(f: F) {
    let (Some(vm), Some(class_ref)) = (JVM.get(), BRIDGE_CLASS.get()) else {
        log::error!("image callback dropped: JVM or bridge class not cached yet");
        return;
    };
    let Ok(mut env) = vm.attach_current_thread_as_daemon() else {
        log::error!("image callback dropped: could not attach thread to JVM");
        return;
    };
    // `GlobalRef::as_obj()` is a `&JObject`; jni provides a *safe* reference
    // cast from `&JObject` to `&JClass`, so no raw pointers are needed here.
    let class: &JClass = class_ref.as_obj().into();
    f(&mut env, class);
}

fn dispatch_success(task_id: i64, path: &str) {
    with_bridge_env(|env, class| {
        let Ok(jpath) = env.new_string(path) else {
            log::error!("image callback dropped: could not allocate result string");
            return;
        };
        let _ = env.call_static_method(
            class,
            "onNativeImageSuccess",
            "(JLjava/lang/String;)V",
            &[JValue::Long(task_id), JValue::Object(&jpath)],
        );
    });
}

fn dispatch_error(task_id: i64, message: &str) {
    with_bridge_env(|env, class| {
        let Ok(jmsg) = env.new_string(message) else {
            return;
        };
        let _ = env.call_static_method(
            class,
            "onNativeImageError",
            "(JLjava/lang/String;)V",
            &[JValue::Long(task_id), JValue::Object(&jmsg)],
        );
    });
}

/// Submit an image fetch. Returns immediately — this only registers the task and
/// hands it to the Tokio runtime.
///
/// The callback delivers an absolute `file://`-less path (the caller prefixes
/// the scheme). Note it is a *path*, not the image bytes: shipping a 500 KB –
/// 5 MB byte array across JNI would copy the whole payload and bypass the disk
/// cache that the reader is designed around.
#[no_mangle]
pub extern "C" fn Java_com_example_ehviewer_1scaffold_rust_EhRustBridge_nativeSubmitImageFetch<'local>(
    mut env: JNIEnv<'local>,
    class: JClass<'local>,
    task_id: jlong,
    viewer_url: JString<'local>,
) {
    cache_jvm_and_class(&mut env, &class);

    let url = match jstring_to_string(&mut env, viewer_url) {
        Some(u) if !u.is_empty() => u,
        _ => {
            dispatch_error(task_id, "empty viewer url");
            return;
        }
    };

    // Register before spawning, preserving any abort that already arrived, so a
    // cancel landing in the window around `spawn` is not lost.
    {
        let mut tasks = image_tasks();
        let already_aborted = tasks.get(&task_id).map(|t| t.abort_requested).unwrap_or(false);
        tasks.insert(
            task_id,
            ImageTask {
                abort_requested: already_aborted,
                handle: None,
            },
        );
    }

    let handle = RUNTIME.spawn(async move {
        // Policy cap on sockets/bandwidth. Acquired inside the task so `submit`
        // itself never blocks.
        let _slot = image_slots().acquire().await;

        let mut guard = CompletionGuard {
            task_id,
            completed: false,
        };

        let outcome = api::fetch_and_cache_image(url).await;
        // A result now exists, so the guard must not add its own callback.
        guard.completed = true;

        match outcome {
            Ok(path) => dispatch_success(task_id, &path),
            Err(e) => dispatch_error(task_id, &e.to_string()),
        }
    });

    // Publish the abort handle, and honour a cancel that beat us to it.
    let abort_now = {
        let mut tasks = image_tasks();
        match tasks.get_mut(&task_id) {
            Some(t) => {
                t.handle = Some(handle.abort_handle());
                t.abort_requested
            }
            // Already finished and cleaned up — nothing to abort.
            None => false,
        }
    };
    if abort_now {
        handle.abort();
    }
}

/// Cancel an in-flight image fetch (the coroutine was cancelled, or the page
/// scrolled away). Dropping the future closes the socket.
#[no_mangle]
pub extern "C" fn Java_com_example_ehviewer_1scaffold_rust_EhRustBridge_nativeCancelImageFetch<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    task_id: jlong,
) {
    let handle = {
        let mut tasks = image_tasks();
        // `or_insert` matters: a cancel can arrive before `submit` registered,
        // and the tombstone it leaves is what the submitter then honours.
        let entry = tasks.entry(task_id).or_insert(ImageTask {
            abort_requested: true,
            handle: None,
        });
        entry.abort_requested = true;
        entry.handle.take()
    };
    if let Some(h) = handle {
        h.abort();
    }
}
