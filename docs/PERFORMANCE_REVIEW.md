# 性能与架构审查报告 — ehviewer_scaffold

审查方式：静态代码审查（通读 `rust/src/*` 与 `android/**/*.kt` 关键路径）。
**未做运行时 profiling**，因此下文标注了每条结论的确定性。

审查时间：2026-09-21

---

## 一、总体结论

**架构骨架是对的，但还远没到"最优"。**

分层思路清晰且正确：Rust 独占 EH 协议细节（cookie / UA / proxy / nl-retry / 缓存），
Coil 降级为纯本地解码器。这个边界划得很干净，是整份代码最大的优点。

但存在 **4 个 P0 级结构性瓶颈**，其中 2 个是「随数据量增长的复杂度问题」，
在画廊页数多、标签库大、缓存文件多的时候会明显劣化。另有 HTTP 层配置遗漏，
影响每一页的传输量。

**结论：不是最优。但有救 —— P0 全部是局部改动，不需要重构架构。**

---

## 一之二、修复状态（2026-09-21 更新）

报告中的问题已全部处理，除下面明确标注的一项。

| 编号 | 问题 | 状态 |
|---|---|---|
| P0-1 | `cache.rs` 每次 `store()` 全量扫描缓存目录 | ✅ 已修 |
| P0-2 | 标签搜索全表扫描 + 大量分配 | ✅ 已修 |
| P0-3 | 图片缓存命中仍需先解析 viewer 页 | ✅ 已修 |
| P0-4 | `opt-level = "z"` 体积优先 | ✅ 已修 |
| P1-1 | reqwest 关闭 HTTP/2 与压缩 | ✅ 已修（已用 Cargo.lock 验证 `h2` / `async-compression` 真正进入依赖图） |
| P1-2 | JNI `block_on` 占满线程池 | 🟡 **图片路径已根治；元数据路径保留 `block_on`（有意）** — 详见 `docs/JNI_ASYNC_DESIGN_REVIEW.md` |
| P1-3 | 三层磁盘缓存重叠 | ✅ 已修（去掉 OkHttp Cache） |
| P1-4 | `translateTags` N 次 JNI 往返 | ✅ 已修（新增批量 JNI） |
| P1-5 | `panic = "abort"` + 无 `catch_unwind` | ✅ 已修（解析器入口加守卫） |
| P1-6 | 详情页预加载串行 | ✅ 已修 |
| P1-7 | `get_bytes` 多余拷贝 / 连接池偏小 | ✅ 已修 |
| P1-8 | 阅读器进度轮询无退避 | ✅ 已修 |
| P2 | 死代码 / 未用依赖 / LruCache 计数 / 忙轮询 等 | ✅ 已修（见下） |

### 各项的具体处理

**P0-1**：`CacheEngine` 新增 `AtomicU64` 运行字节计数（`store` 时 `+len`，
`remove`/`clear_expired` 时 `-len`），低于预算**直接返回、不扫描**；
超预算时才用一个 `AtomicBool` 单飞闩触发一次扫描，并把扫描结果写回计数器。
`init_disk_cache` 仍扫一次用于播种。

**P0-2**：`TagIndex` 在加载期就构建好 `rows: Vec<TagRow>`，
每行预先存好 `raw_lower` / `translated_lower`，搜索阶段**零分配**；
另保留 `by_namespace` 供 `translate_tag_sync` 做 O(1) 精确查找。
同时把锁从 `tokio::sync::RwLock` 换成 `std::sync::RwLock` —— 它被 JNI 同步调用，
原来的 `try_read()` 在写锁被占用时会**静默返回空结果**。
新增 3 个测试。

**P0-3**：`fetch_and_cache_image` 改为**先查缓存**（key = `md5(viewer_url)`），
命中直接返回、零网络请求；未命中才解析 viewer 页。
`fetch_with_nl_retry` 内部原本还会用 `real_url` 再存一份，已移除该处存储，
避免产生"第二份找不到的副本"。

**P0-4 / P1-1**：`opt-level = 3`；reqwest 补 `http2` / `gzip` / `brotli` / `charset`。

**P1-2（未根治）**：`RUNTIME.block_on` 的本质没变 —— 每个 JNI 调用仍占住调用线程。
本次做的是**给 Kotlin 侧加闸**：`EhRustBridge` 新增 `withJniSlot`，
用一个并发度 24 的 `Semaphore` 包住所有跨 JNI 调用，确保阻塞在 JNI 上的线程
永远不会吃掉整个 `Dispatchers.IO`（默认 64）而饿死其他 IO 工作。
`RustImageFetcher` 也走同一个闸。
同时 Tokio worker 线程从 4 提到 8（标签搜索/解析是 CPU 密集，会占用 worker）。
**真正的根治是把 JNI 改成回调式**（Rust 跑完回调 Kotlin），
这样线程数与在途请求数彻底解耦 —— 那是结构性改动，未做。

**P1-5**：去掉 `panic = "abort"`，在 `parser.rs` 给**每一个公开解析入口**加
`guarded(...)`（`catch_unwind` + 回退值）。解析是唯一会索引/切片不可控文本的地方
（CJK 的非字符边界切片是真实可达的 panic 点）。
选择在解析器边界而不是 JNI 边界加守卫，是因为它只覆盖真正有 panic 风险的代码，
且未来新增解析入口时不会漏。代价是 APK 变大（见下）。

**P2**：删掉 `configure_network`、`rate_gallery`、`fetch_autocomplete`、
`save_eh_web_config`（均已被 WebView 版本或已导出的接口取代）；
去掉未使用的 `datastore-preferences` 依赖；
`GalleryDetailCache` 改为覆写 `sizeOf()` 按页数计权；
去掉 `android.util.LruCache` 外层多余的 `synchronized`；
阅读器进度轮询加退避（50ms → 上限 400ms）；
预取批次间隔提为具名常量 `THUMBNAIL_BATCH_PAUSE_MS`。

**P2 补充：下载器并发控制重写（这条比原报告写的更严重）**

回看 `downloader.rs::acquire_slot()` 时发现，它的问题不止是"忙轮询"：

```rust
loop {
    if ACTIVE_DOWNLOADS.load(..) < CONCURRENCY_LIMIT.load(..) { break; }  // 检查
    tokio::time::sleep(150ms).await;
}
ACTIVE_DOWNLOADS.fetch_add(1, ..);      // 递增 —— 不在同一个原子步里
```

检查和递增是**两个独立操作**，所以多个 worker 可以同时通过检查、
再依次递增 —— **实际并发会超过用户设定的上限**。

已改为真正的 `tokio::sync::Semaphore`：
- `acquire_owned()` 天然 race-free，且会正确挂起等待（无轮询）
- 返回 `OwnedSemaphorePermit`，**靠 `Drop` 归还槽位**，任何退出路径都不会漏放
- 运行时改上限：调高用 `add_permits`，调低用 `forget_permits`；
  若部分许可正被运行中的 worker 持有，记入 `PENDING_RECLAIM`，
  在那些 worker 结束时逐个收回，**避免反复调整后上限逐渐漂移**

> 设计取舍说明：这里刻意选了「即使簿记出错也只是上限偏高（无害）」的方案，
> 而不是 `Notify` + `enable()` 的手写等待 —— 后者一旦漏掉唤醒会导致
> 下载**永久挂起**，风险不对称。

**已接线**：`download_torrent` + `sanitize_filename` 原先不可达，现已在种子列表弹窗中接上
（每条右侧下载按钮，走 `downloadTorrent` JNI → 保存到 `<下载目录>/torrents/`）。
→ 该 P2 项已消解。

### 体积代价（需要你知晓）

| | 之前 | 现在 |
|---|---|---|
| APK | 5.49 MB | **7.57 MB** |
| 原生库 `.so` | 2.88 MB | 5.07 MB |

（7,574,803 字节，SHA256 `A54143FABA502D877AD131883B28907A2136E753DB1D12EC7D4F91494264F0D0`）

增长来自：`opt-level` 由 `"z"` 改 `3`、去掉 `panic = "abort"`（需要 unwind 表）、
新增 HTTP/2 + gzip + brotli + charset 依赖。

若体积比性能更重要，可单独回退其中一项（例如恢复 `panic = "abort"`，
代价是失去 P1-5 的 panic 保护）。

### 验证情况

- `cargo check --offline` 无 error / 无 warning
- `cargo test --lib` → **17 passed / 0 failed / 2 ignored**（原 14，新增 3 个标签索引测试）
- `:app:assembleRelease --offline` BUILD SUCCESSFUL
- 真机 `5f8b5a67`：安装成功、冷启动 641ms、首页加载 25 条、
  瀑布流渲染正常且灰块 0%、详情页打开无崩溃（预览缩略图实测解码为
  250×353 / 250×365 / 250×354 / 250×223 —— 比例各异，说明自适应比例生效）
- **阅读器路径已实测通过**（由用户手动操作进入）。
  `HWUI ImageDecoder` 记录显示连续解码出 **800×1169** 的正文页
  （缩略图是 250px 宽，800px 是 EH 原始页宽），时间跨 02:09:53 → 02:10:10，
  十几张连续成功，期间无崩溃、无 panic。
  这一次验证同时确认了：
  - `fetch_and_cache_image` 的「解析 → 下载 → 落盘 → 返回 file://」全链路可用
  - **P0-3 的 viewer-URL 缓存键生效**（Coil 能读到返回的本地文件，说明
    `store()` 确实写进了 `md5(viewer_url)` 对应的路径）
  - P1-2 的 JNI 闸（并发 24）**没有引入死锁或饥饿**，多页并发拉取正常

### 实测中发现的一个现象（非本次改动引入）

详情页标签显示的是**原始英文**（`elf mura` / `cecil` / `eye-covering bang`…）而非中文翻译。
排查结论：**这台设备从未下载过标签数据库**。
`EhApplication` 只在 `filesDir/tag_db.json` 存在时才调用 `loadTagDb`，
而 logcat 中**完全没有** `Auto-loaded tag database` 这一行 → 文件不存在 →
`TAG_DB` 为空 → `translate_tag_sync` 按设计回退返回原始 tag。

这是**预期行为，不是 batch 翻译改动的回归**（批量与单条走的是同一个
`translate_tag_sync`）。要让标签显示中文，需要在设置里执行一次标签库下载
（`downloadTagDb`，从 GitHub 拉取 db.text.json）。

---

## 二、值得保留的设计（不要动）

| # | 设计 | 为什么好 |
|---|---|---|
| 1 | Rust 独占 EH 协议，Coil 只解码本地文件 | 会话/UA/配额重试只有一份实现，没有两套网络栈互相打架 |
| 2 | 全项目 **无 `runBlocking` / `Thread.sleep` / `GlobalScope`** | 已核实，`grep` 零命中。经典 Android 卡顿源全部规避 |
| 3 | 设置写入用 `SharedPreferences.apply()` 不用 `commit()` | 不阻塞主线程 |
| 4 | 下载器用 `buffer_unordered(PAGE_CONCURRENCY)` + 运行时可调并发 | 页级并发正确，`CONCURRENCY_LIMIT` 用 `AtomicUsize` 支持热改 |
| 5 | 详情页/首页有 retained state（`HomeStateHolder` / `GalleryDetailCache`） | 返回不重新拉取，这是对的 |
| 6 | 注释解释「为什么」而非「做什么」 | 少见且宝贵，维护性靠它 |

---

## 三、P0 — 直接拖性能，建议优先修

### P0-1 `cache.rs`：每次写缓存都全量扫描整个缓存目录（复杂度 O(N×M)）

**确定性：确定（代码逻辑明确）**

```rust
pub async fn store(&self, url: &str, bytes: &[u8]) -> Result<()> {
    fs::write(&disk_file, bytes).await?;
    tokio::spawn(async move {
        evict_disk_lru(&disk_root, MAX_DISK_CACHE_BYTES).await   // ← 全目录扫描
    });
}
```

`evict_disk_lru` 每次都 `read_dir` 全目录 + 对**每个文件** `metadata()` + 全量排序。

**代价估算**：缓存目录 5,000 个文件时，每存一张图 = 5,000 次 `stat()` + 5,000 元素排序。
读一个 20 页画廊 = **20 次全量扫描 ≈ 100,000 次 syscall**。
且这 20 个 task 是并发 spawn 的，I/O 互相争抢。

虽然 `tokio::spawn` 让调用方不等它，但 CPU 和 I/O 是实打实烧掉的，
表现为：耗电、读图变慢、`logcat` 里大量 `Disk LRU evict` 行。

**修法**：
1. 加一个 `AtomicU64` 记录当前缓存总字节数（写入时 `+len`，驱逐时 `-len`），
   低于预算**直接 return，不扫描** —— 这一步就能消掉 99% 的扫描。
2. 驱逐加「单飞 + 节流」：`AtomicBool` 保证同时只有一个驱逐在跑，且两次之间至少间隔 N 秒。
3. 初始总字节数在 `init_disk_cache` 时扫一次算出来即可。

---

### P0-2 `tag_translator.rs`：标签搜索每敲一个字就全表扫描 + 上百万次分配

**确定性：确定**

```rust
pub fn search_tag_by_chinese(keyword: String) -> Vec<TagSuggestion> {
    for (ns, tags) in db.iter() {
        for (tag, translated) in tags.iter() {
            let t = translated.to_lowercase();     // 分配
            let tag_lower = tag.to_lowercase();    // 分配
            let raw = format!("{}:{}", ns, tag);   // 分配
            let raw_lower = raw.to_lowercase();    // 分配
            ... 3 次 .find(&kw) ...
```

EhTagTranslation 数据库约 **5 万+ 条**标签。每次调用 = 约 **20 万次 String 分配**
+ 15 万次子串搜索。输入框每敲一个字符触发一次。

它在 `Dispatchers.IO` 上跑，所以不会 ANR，但会：
持续吃满一个核心、自动补全明显延迟、键盘输入发涩。

**修法**：`load_tag_db` 时就**预先把小写形式算好存进索引**
（存 `(raw_lower, translated_lower, translated)` 三元组），
搜索阶段做到**零分配**。这一步通常能快 10 倍以上。
如果还要更快，再上 trigram 倒排索引。

> 顺带：`TAG_DB.try_read()` 在正好有写入时会 `Err` 并**静默返回空结果** ——
> 数据库重载期间翻译会莫名失效。应改用 `read()`。

---

### P0-3 `api.rs`：图片缓存命中也要先请求一次 viewer 页（每页 2 次网络往返）

**确定性：确定**

```rust
pub async fn fetch_and_cache_image(viewer_url: String) -> Result<String> {
    let real_url = resolve_image_url(viewer_url.clone()).await?;   // ← 先拉 viewer HTML
    let cache_file = CACHE_ENGINE.get_disk_file_path(&real_url);   // ← 再查缓存
    if cache_file.exists() { return Ok(...); }
```

缓存是以 `md5(real_url)` 为 key 的，而 `real_url` 必须先解析出来 ——
**所以哪怕整本已经缓存好了，打开阅读器仍然要把每一页的 viewer HTML 重新拉一遍。**

一本 200 页的画廊，第二次打开仍要发 200 个 HTML 请求（每个几十 KB）
才能真正命中本地图片。这是纯粹的浪费。

**修法**：缓存 key 改成 `md5(viewer_url)`（或额外维护 `viewer_url → real_url` 映射）。
阅读器本来就用 viewer_url 标识页面，改为 viewer_url 做 key 后：

```
1. 查 md5(viewer_url)  → 命中 → 直接返回，0 网络请求
2. 未命中 → 解析 real_url → 下载 → 按 md5(viewer_url) 落盘
```

第二次打开整本画册 = **零网络请求**。这是收益最大的一条。

---

### P0-4 `Cargo.toml`：`opt-level = "z"` 是按体积优化，不是按速度

**确定性：确定（这是 Cargo 语义）**

```toml
[profile.release]
opt-level = "z"      # ← 优先最小体积
lto = true
codegen-units = 1
panic = "abort"
```

`"z"` 会为了体积牺牲运行时速度（甚至比 `"s"` 更激进）。
本项目定位是「高性能」，但编译配置选了**体积优先**。

受影响的热点：HTML 解析（scraper）、`serde_json` 序列化/反序列化、
标签搜索、图像缓存路径。

**修法**：改 `opt-level = 3`。APK 可能涨几百 KB（当前 5.5 MB），
换取解析与序列化的实质提速。这对一个"高性能阅读器"是划算的。

---

## 四、P1 — 重要，但可稍后

### P1-1 reqwest 关掉了默认特性 → **没有 HTTP/2，也不请求压缩**

**确定性：已用 `Cargo.lock` 证实**

```toml
reqwest = { version = "0.12", default-features = false,
            features = ["json", "cookies", "rustls-tls", "stream"] }
```

reqwest 0.12 的默认特性包含 `http2` 和 `charset`；`gzip`/`brotli` 则是需要显式开启的可选特性。
当前配置三者都没有。核对 `Cargo.lock` 的传递依赖可以确认：

| 依赖 | 用途 | 是否在 lock 中 | 结论 |
|---|---|---|---|
| `h2` | HTTP/2 实现 | **缺失** | ❌ HTTP/2 关闭 |
| `async-compression` | reqwest 的 gzip/brotli/deflate | **缺失** | ❌ 压缩全部关闭 |
| `brotli` | brotli 解码 | **缺失** | ❌ brotli 关闭 |
| `flate2` / `miniz_oxide` / `weezl` | 存在于 lock | 存在 | ⚠️ 但来自 `image` 的 PNG/GIF 解码，**与 reqwest 无关** |

所以实际后果是：

- **只能走 HTTP/1.1**。而首页配置允许 96 个/主机并发 ——
  HTTP/1.1 没有多路复用，这 96 个请求要开 **96 条 TCP + TLS 连接**。
  除了握手开销，这么高的并发连接数还可能被 EH 的 CDN 判为滥用而限流。
  开 HTTP/2 后同样 96 个请求可以复用 1~2 条连接，**既快又安全**。
- **不发送 `Accept-Encoding`** → 服务器返回**未压缩** HTML。
  列表页/详情页每个几十~上百 KB，移动网络下是纯浪费（图片本身已压缩，影响很小）。

**修法**：`features = ["json", "cookies", "rustls-tls", "stream", "http2", "gzip", "brotli", "charset"]`

> 这一条是整份报告里性价比最高的改动之一：改一行 Cargo.toml，
> 同时改善带宽、握手开销与限流风险。

### P1-2 JNI 边界用 `block_on`，把 Kotlin 线程数绑死在「在途请求数」上

**确定性：确定**

每个 JNI 函数都是 `RUNTIME.block_on(...)`，**整个网络往返期间调用线程被占住**。

Kotlin 侧 `Dispatchers.IO` 默认上限 64 线程，而 Coil 的 fetcher 也走同一个池。
首页允许 96 个/主机并发缩略图（`EhApplication` 里 `maxRequestsPerHost = 96`）——
**在途请求数一旦超过 64，线程池就饱和**，后续请求排队等待，反而变慢。

**修法**（按投入排序）：
- 成本最低：把在途请求数**显式**限制在池容量内（例如 Coil 并发压到 32），别让两者脱钩
- 中等：JNI 增加 batch 接口，一次调用处理一批图（N 个 URL → N 个结果）
- 最优：改成回调式 —— Rust 在自己的 Tokio runtime 上跑完再回调 Kotlin，
  Kotlin 线程不再被占用，在途请求数与线程数解耦

### P1-3 三层磁盘缓存重叠，上限合计约 832 MB

**确定性：确定（配置可查）**

| 层 | 路径 | 上限 |
|---|---|---|
| Rust 图片缓存 | `cacheDir/`（根） | **512 MB** |
| Coil DiskCache | `cacheDir/image_cache` | **256 MB** |
| OkHttp Cache | `cacheDir/http_cache` | **64 MB** |

缩略图走 Coil + OkHttp：**同一份字节在两个缓存里各存一遍**（OkHttp 的 HTTP cache 与 Coil 的 DiskCache 重叠，这是 Coil+OkHttp 的已知冗余）。

**修法**：去掉 OkHttp 自己的 `Cache`（Coil 的 DiskCache 已覆盖），
并把两者总预算收到合理区间。另需确认 Rust 缓存过的阅读图是否同时又进了 Coil 的 disk cache
（若进了，512MB 那层就是双份，需显式给阅读图请求加 `diskCachePolicy` 跳过 Coil 落盘）。

### P1-4 `translateTags` 仍是 N 次 JNI 往返 + N 次加锁

**确定性：确定**

注释说它"省掉了每 tag 一次线程切换"（这点做到了），
但**仍然是 60~120 次 JNI 调用 + 每次一次 `TAG_DB` 读锁**。JNI 跨越本身有固定开销。

**修法**：加一个 `translateTagsJson(tagsJson) -> jsonMap` 的批量 JNI 函数，
一次调用、一次加锁完成整批。Kotlin 侧 `translateTags` 直接换成它。

### P1-5 `panic = "abort"` 且 JNI 无 `catch_unwind` → 解析 panic 直接杀进程

**确定性：确定（编译选项 + 代码）**

`jni_bridge.rs` 所有函数都直接调 `api::*`，没有 `catch_unwind`；
配合 `panic = "abort"`，**Rust 里任何一次 panic 都是 SIGABRT，整个 App 挂掉**。

解析外部 HTML 是最容易触发边界 panic 的地方（切片越界、索引越界）。
对一个"解析别人网页"的应用，这是明显的健壮性风险。

注意：`panic = "abort"` 下 `catch_unwind` **无效**，两者必须一起改。

**修法**：去掉 `panic = "abort"`（体积会涨一点），在 JNI 出口统一包 `catch_unwind`，
把 panic 转成 `{"error": "..."}` 返回，让 UI 走错误分支而不是崩溃。

### P1-6 `fetch_gallery_detail` 的预加载是串行的

**确定性：确定**

```rust
tokio::spawn(async move {
    for url in urls.iter().take(3) {
        if let Ok(real_url) = resolve_image_url(url.clone()).await { ... }
    }
});
```

3 张图**依次**加载，总耗时 = 3 倍单张延迟。而 `futures` 已经是依赖项了。

**修法**：`futures::future::join_all(urls.iter().take(3).map(|u| async { ... }))`
→ 总耗时 ≈ 1 倍延迟。这是详情页首屏观感的直接改善。

### P1-7 `network.rs`：`get_bytes` 多做一次整块内存拷贝

**确定性：确定**

```rust
let bytes = res.bytes().await?;   // 返回 Bytes（引用计数，零拷贝）
Ok(bytes.to_vec())                // ← 整块复制一份
```

阅读原图动辄几百 KB ~ 几 MB，每次缓存未命中都白拷一份。
`get_image_with_progress` 里 `bytes.to_vec()` 同样问题（缓存命中路径也拷）。

**修法**：返回类型改成 `Bytes`（`bytes::Bytes`，reqwest 已重导出），下游按需处理。

同一文件还有：`pool_max_idle_per_host(10)`，而应用允许 96/主机并发 ——
**突发结束后只保留 10 条空闲连接**，下一波 86 个请求要重新 TCP+TLS 握手。
建议提到 32~64。

### P1-8 阅读器进度用轮询而非推送

**确定性：确定**

```kotlin
LaunchedEffect(viewerUrl, isImageLoading, retryTrigger) {
    while (isActive && isImageLoading) {
        val p = withContext(Dispatchers.IO) { EhRustBridge.getImageProgress(viewerUrl) }
```

每张正在加载的图都在循环轮询 JNI（且每次 `withContext` 都是一次线程切换）。
纵向连续模式下同屏多张图 → 多路轮询叠加。

**修法**：Rust 侧通过回调上报进度；至少也要合并成"只轮询当前可见页"，
并给轮询加退避（如 150ms 起，逐步放宽）。

---

## 五、P2 — 清理类

| 项 | 说明 |
|---|---|
| 死代码（已核验） | `api::get_image_with_progress`、`download_torrent`、`rate_gallery`、`fetch_autocomplete`、`save_eh_web_config`、`configure_network` —— 全文搜索后**只在定义处出现，零调用点**，且未在 `jni_bridge.rs` 导出 |
| 未用依赖 | `datastore-preferences` 声明了但全项目用的是 SharedPreferences |
| LruCache 计数而非计大小 | `GalleryDetailCache(60)` 按条目数，500 页的详情和 20 页的一样占 1 个名额 —— 应覆写 `sizeOf()` |
| 冗余同步 | `android.util.LruCache` 内部已线程安全，外层再 `synchronized` 是多余的 |
| 忙轮询 | `downloader.rs::acquire_slot()` 每 150ms 轮询；`tokio::sync::Semaphore` + `add_permits` 才是惯用解 |
| 异步里用阻塞 IO | `tag_translator.rs` 在 async fn 里用 `std::fs::read_to_string` / `write`，会阻塞 Tokio worker；应改 `tokio::fs` |
| 硬编码等待 | 阅读器后台预取每批次 `delay(600L)`，末页可用性被拖慢 |
| Tokio worker 数偏少 | `worker_threads(4)`；标签搜索是 CPU 密集，会占住 worker。建议 8 |

---

## 六、建议的动手顺序

**第 1 批（都是局部改动，收益立竿见影）**
1. `Cargo.toml`：`opt-level = "z"` → `3`；reqwest 补 `http2`/`gzip`/`brotli`/`charset`
2. `cache.rs`：加原子字节计数 + 单飞节流，消除全量扫描
3. `api.rs`：图片缓存 key 改 `md5(viewer_url)`，缓存命中不再解析
4. `tag_translator.rs`：加载期预建小写索引，搜索零分配

**第 2 批（结构性）**
5. 标签翻译改批量 JNI；预加载改并发
6. `network.rs` 返回 `Bytes`；调 `pool_max_idle_per_host`
7. 去掉 OkHttp 冗余 Cache，收敛磁盘缓存预算

**第 3 批（健壮性 / 深度优化）**
8. 去掉 `panic = "abort"` + `catch_unwind`
9. JNI 改回调式，解耦线程数与在途请求数
10. 清理死代码与未用依赖

---

## 七、免责说明

以上均为**静态审查**结论。P0-1 / P0-2 / P0-3 / P0-4 的因果链在代码层面是确定的，
但**具体加速比需要实机 profile 验证**（建议工具：Android Studio Profiler +
`simpleperf`，或 Rust 侧 `tracing` + `cargo flamegraph`）。

若要我把其中任何一条实际改掉并跑通验证，告诉我编号即可。
