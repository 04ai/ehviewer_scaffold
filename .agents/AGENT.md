# Role & Architecture: EhViewer Scaffold
You are the lead engineer for EhViewer Scaffold. The architecture strictly isolates responsibilities:
- **Rust Core (`rust/src/`)**: 100% of network, parsing, caching, and download logic.
- **Android Native (`android/app/`)**: 100% Jetpack Compose UI, Android system integration, and gestures.

---

## 1. Architectural Boundaries (Zero-Tolerance Rules)
- **DO NOT** write network requests, HTML parsers (Jsoup/Regex), or disk/memory caches in Kotlin. All business and data logic MUST remain in Rust.
- **DO NOT** execute blocking JNI calls on `Dispatchers.Main`. Wrap all `EhRustBridge` invocations in `withContext(Dispatchers.IO)`.
- **DO NOT** transfer large binary blobs (e.g. full-resolution images) across JNI. Pass local file paths (`file://`) and let Coil decode from disk.

---

## 2. Rust Core Implementation Standards
- **Network (`network.rs`)**:
  - Use `reqwest` with `rustls-tls` only (no native OpenSSL).
  - Dynamically inject WebView UA & Cookies per-request to survive 30x redirects.
  - Fail fast on 4xx; apply exponential backoff on 5xx/timeouts. Enforce `STREAM_IDLE_TIMEOUT_SECS` on chunk streaming.
- **Cache & Safety (`cache.rs`, `api.rs`)**:
  - Never hold `CACHE_ENGINE` locks during network requests.
  - Validate image headers and first 512 bytes with `looks_like_html_error`. Never cache Cloudflare, 403, or 509 HTML pages.
  - Trigger `nl=1` URL resolver flow when encountering 509 quota errors or broken images.
- **Downloader & Parser (`downloader.rs`, `parser.rs`)**:
  - Always verify existing disk files via `page_file_exists` for self-healing resumption.
  - Limit per-gallery concurrency to prevent IP bans. Evict HTTP L1/L2 cache once a gallery download finishes.
  - Parse HTML via `scraper`. Support both table view (`table.itg tr`) and thumbnail view (`div.gl1t`) with CSS sprite offset calculation.

---

## 3. Android & Compose Standards
- **UI & Architecture**:
  - 100% Jetpack Compose + Material 3. Follow MVI (`ViewModel` + `StateFlow<UiState>`).
  - Always provide stable keys for Lazy lists (`key = { item.gid }`).
- **Image Pipeline (`RustImageFetcher.kt`)**:
  - Coil 3 must delegate gallery image URLs (`/s/`) to Rust's fetch pipeline, returning an `ImageSource` pointing to the cached file.
- **System & Lifecycle**:
  - Background downloads must run in a Foreground Service with `foregroundServiceType="dataSync"`.
  - Target Android 15 (API 35), Min SDK 26.
  - Enforce 16KB page size alignment for all native compilation (`-Clink-args=-Wl,-z,max-page-size=16384`).

---

## 4. Workflow for New Features
1. Implement and test core logic in `rust/src/`.
2. Expose methods via `jni_bridge.rs` using safe type conversions (avoid bare `.unwrap()`).
3. Bind in `EhRustBridge.kt` using coroutine `suspend` functions on `Dispatchers.IO`.
4. Consume in Compose `ViewModel` and render via declarative UI components.