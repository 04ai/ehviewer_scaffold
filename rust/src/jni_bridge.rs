use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jint, jstring};
use crate::api;

lazy_static::lazy_static! {
    static ref RUNTIME: tokio::runtime::Runtime = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(4)
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
) -> jstring {
    let pu = jstring_to_string(&mut env, page_url);
    let q = jstring_to_string(&mut env, query);
    let opt: Option<api::SearchOptions> = jstring_to_string(&mut env, options_json)
        .and_then(|j| serde_json::from_str(&j).ok());

    let res = RUNTIME.block_on(api::fetch_gallery_list(page as u32, pu, q, opt));
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
) -> jstring {
    let p = jstring_to_string(&mut env, path).unwrap_or_default();
    let pu = jstring_to_string(&mut env, page_url);
    let q = jstring_to_string(&mut env, query);
    let opt: Option<api::SearchOptions> = jstring_to_string(&mut env, options_json)
        .and_then(|j| serde_json::from_str(&j).ok());

    let res = RUNTIME.block_on(api::fetch_custom_list(p, page as u32, pu, q, opt));
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
