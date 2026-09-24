# JNI 全异步架构方案 —— 评审意见

评审对象：用户提供的「`block_on` → 全异步非阻塞 + 协作式取消」方案
（Kotlin `suspendCancellableCoroutine` + continuation 注册表 ↔ Rust `spawn` + `AbortHandle` + 反向 JNI 回调）

评审时间：2026-09-21

---

## 一、结论

**方向完全正确 —— 这就是 P1-2 的正解。**

现在 `Dispatchers.IO` 的 64 个线程被 `block_on` 一个对一个地钉死，
在途请求数一超过线程数就排队。我把 JNI 调用加了个并发度 24 的闸，
**只是把伤害限制住，没有解决"一个在途请求 = 一个 JVM 线程"这个根本问题**。
这套方案把它换成"一个在途请求 = 堆上一个轻量协程对象"，是正确的终局架构。

而且方案里几处关键判断都对：

| 判断 | 评价 |
|---|---|
| `suspendCancellableCoroutine` + `ConcurrentHashMap<Long, Continuation>` | 正确，标准做法 |
| `invokeOnCancellation` → `nativeCancel(taskId)` | 正确，而且**我们现在完全没有取消能力**，这是净增收益 |
| 用 `attach_current_thread_as_daemon` | 选型正确；"耗时几纳秒"的说法也站得住 —— Tokio worker 是长生命周期线程，attach 只在**首次**付费，之后走线程本地 `JNIEnv*` 缓存 |
| 竞态分析（谁先 `remove` 谁赢） | 正确，`ConcurrentHashMap.remove` 是原子的，两个方向都安全 |
| CPU 密集解析要用 `spawn_blocking` 隔离 | 正确，而且这条对我们的标签搜索/HTML 解析特别相关 |
| `dashmap` 依赖可用 | 已核实：`dashmap 5.5.3 / 6.2.1`、`once_cell 1.21.4` **都在本地 cargo 缓存里**，离线构建没问题 |
| `catch_unwind` 可用 | 成立 —— 我们已把 `panic = "abort"` 去掉了 |

**但有 6 处必须补，其中第 1 条会导致协程永久挂起，是必须修的。**

---

## 二、必须补的问题

### ❌ 1（致命）没有保证回调一定被调用 → 协程永久挂起

```rust
let join_handle = rt.spawn(async move {
    let result = do_fetch_pipeline(&url).await;   // ← 这里 panic 会怎样？
    get_tasks().remove(&task_id);                 // ← 不会执行
    match result { Ok(..) => dispatch_success(..), Err(..) => dispatch_error(..) }
});
```

Tokio 会**捕获** task 内的 panic 并让该 task 静默结束 ——
于是 `get_tasks().remove()` 和 `dispatch_*` **全都不会执行**。

后果：Kotlin 侧 `pendingTasks[taskId]` 永远不被移除，那个协程**永远挂起**，
UI 卡在 loading，而且没有任何日志。

**这是新引入的失败模式。** 现在的 `block_on` 模型下 panic 会直接向上传播
（进程崩掉，你能看见）；改成回调后它会变成一个静默的永久挂起。

**修法**：用 RAII guard 保证回调一定发生，或整个 task body 包 `catch_unwind`
并在 Err 分支也走 `dispatch_error`。类似：

```rust
struct CompletionGuard { task_id: i64, done: bool }
impl Drop for CompletionGuard {
    fn drop(&mut self) {
        if !self.done {
            // 无论是 panic 还是提前 return，都保证 Kotlin 侧被唤醒
            dispatch_error(self.task_id, -3, "native task aborted unexpectedly");
        }
    }
}
```

### ❌ 2（严重）对图片路径传 `ByteArray` 是性能倒退

方案里 `fetchImage(url): ByteArray`。但我们阅读器路径现在返回的是
**`file://` 路径字符串**，Coil 自己从磁盘解码。

改成 `ByteArray` 意味着每张图（500KB ~ 5MB）都要在 JNI 边界**完整拷贝一次**
（`byte_array_from_slice` 新建 jbyteArray + memcpy），而且**绕过了现有的磁盘缓存**，
内存里同时挂着 Rust 的 `Vec` 和 JVM 的 `byte[]`。

这等于把我们刚做的 P1-7（消掉 `to_vec()` 那一次整块拷贝）**又加回来两遍**。

**修法**：回调返回**路径字符串**。真正需要字节的（`.torrent`、JSON 元数据）才传 byte[]。
阅读器图片必须走路径 + Coil 本地解码这条既有设计。

### ❌ 3（重要）无界并发会把"线程瓶颈"换成"socket/带宽瓶颈"

方案说"几百个图片请求可以在 Kotlin 侧同时并发挂起"。
但**线程数不再是瓶颈 ≠ 不需要限流**。几百个并发 socket 会：
- 打爆带宽（移动网络尤其明显）
- 触发 EH / Cloudflare 限流（这是我们前面调 `maxRequestsPerHost` 时就在担心的事）

现在的"JNI 闸 24 + `maxRequestsPerHost 96`"是真实存在的保护。

**修法**：保留一个显式的 in-flight 上限 —— 用 `Semaphore`，
但它只约束"并发策略"，不再钉死线程。概念上从"线程约束"变成"带宽/礼貌约束"。

### ❌ 4（重要）缓存写入必须原子化 —— 取消会让这个问题浮出水面

一旦任务可以被 `abort()`，`fs::write(cache_file, bytes)` 就可能**写到一半被丢弃**，
留下半截文件。下次读缓存会读到损坏图片，而且 Coil 会当成"文件存在"直接用。

现在没有取消能力，所以这个问题还不存在；**引入取消后就存在了**。

**修法**：写临时文件 + `rename`（同目录 rename 在 POSIX 下是原子的），
在 `CacheEngine::store` 里做。这条不管采不采纳方案都值得做。

### ❌ 5（中等）`spawn` 与 `insert` 之间的窗口会丢取消

```rust
let join_handle = rt.spawn(async move { ... });
get_tasks().insert(task_id, join_handle.abort_handle());   // ← 在这之前取消会丢
```

如果 Kotlin 在 `spawn` 之后、`insert` 之前调了 `nativeCancel`，
表里还没有这个 id → 取消失效 → 任务白跑到底。
（回调时找不到 continuation，所以无害，但白跑。）

方案的竞态分析只覆盖了"回调 vs 取消"这一个方向，**漏了"提交 vs 取消"这个方向**。

**修法**：先登记再 spawn（占位），或维护一个已取消 id 集合在 spawn 前检查。

### ❌ 6（小）`dashmap` 不必要 / `JNI_OnLoad` 里 `expect` 会 panic

- 任务表最多几百条，读写频率远低于网络操作本身。
  `std::sync::Mutex<HashMap<i64, AbortHandle>>` 足够，能少一个依赖。
- `JNI_OnLoad` 里 `find_class(...).expect(...)`：
  若查找失败，panic 穿过 JNI 边界是 UB 边缘行为。应返回 `0`（JNI_ERR）而不是 panic。

### ⚠️ 7（注意）`worker_threads(4)` 与 `spawn_blocking` 是配套的

方案说"JNI 解耦后无需盲目扩大 worker 数"。这个结论**只有在同方案第 3 点
（CPU 密集解析全部搬到 `spawn_blocking`）一起落地时才成立**。

我们刚把 worker 从 4 调到 8，原因正是**标签搜索/解析是 CPU 密集，会占住 worker**。
两者不能只改一个：要么"4 workers + 解析走 spawn_blocking"，
要么"8 workers + 解析可以裸跑"。混搭会退化。

---

## 三、如果要做，我建议怎么切

不要一次把 35 个 JNI 入口全改掉 —— 那是个大爆炸式重构。
**按"收益 × 风险"分层推进：**

| 层 | 内容 | 理由 |
|---|---|---|
| **第一批** | 只改**图片路径**（`fetchAndCacheImage`） | 唯一高频、天然可取消（用户划走就该掐断）、返回值是**小字符串**的路径。收益最大、改动最小、正好避开问题 2 |
| **第二批** | 下载器的分页抓取 | 同样高频可取消，而且取消能**真正省流量** |
| **保持不动** | 列表/详情/评论/收藏/评分等元数据调用 | 低频、用户主动等待结果、"取消"没有意义。继续 `block_on` + JNI 闸，风险为零 |

这样能拿到绝大部分收益（在途请求数从"受 64 线程限制"变成"只受策略限制"），
而改动面集中在 1~2 个函数上，回归风险小得多。

---

## 四、实施状态（2026-09-21 更新）

**已按上面的修正意见实现，范围＝建议的第一批（只改图片路径）。**

| 评审问题 | 落地情况 |
|---|---|
| ❌ 1 回调可能不触发 → 协程永久挂起 | ✅ `CompletionGuard`（`Drop` 里兜底 `dispatch_error`）—— panic 与 abort 两条路径都覆盖 |
| ❌ 2 图片传 ByteArray 是倒退 | ✅ 回调传 **`String` 路径**（`onNativeImageSuccess(taskId, path)`），字节一个都不拷贝 |
| ❌ 3 无界并发 | ✅ `MAX_CONCURRENT_IMAGE_TASKS = 24` 的 `Semaphore`，在任务内获取（`submit` 自身不阻塞）。它是**策略上限**，不再钉线程 |
| ❌ 4 缓存写入要原子化 | ✅ `CacheEngine::store` 改为写 `<md5>.tmp` → `rename` 落位 |
| ❌ 5 `spawn`/`insert` 间丢取消 | ✅ 先注册（并用 `or_insert` 留墓碑）→ 再 `spawn` → 再回填 `AbortHandle` 并复查 `abort_requested` |
| ❌ 6 `dashmap` 不必要 / panic 跨 JNI | ✅ 用 `Mutex<HashMap>`（无新依赖）；`JVM`/类引用**惰性获取**，避开了手写 `JNI_OnLoad` FFI 签名的风险 |
| ⚠️ 7 `worker_threads` 与 `spawn_blocking` 配套 | ✅ 保持 `worker_threads(8)`；图片路径是 I/O 型，不占 CPU worker |

**另外没采纳方案的一点**：方案在 `JNI_OnLoad` 里缓存 `JavaVM` + `GlobalRef`。
我改成**在第一次 `nativeSubmitImageFetch` 调用时惰性获取** ——
那时 env 和 class 都在手上，没有任何需要在首次调用前完成的事情，
还省掉了一个容易写错签名的 FFI 入口。

**关于 `GlobalRef` → `JClass` 的转换**：查了 jni 0.21 源码，`jclass.rs` 里有
`impl<'a,'b> From<&'b JObject<'a>> for &'b JClass<'a>` —— 是**安全引用转换**，
所以全程不需要 `unsafe`。

### 改动文件

| 文件 | 改动 |
|---|---|
| `rust/src/cache.rs` | `store()` 改 tmp + rename |
| `rust/src/jni_bridge.rs` | 新增 `nativeSubmitImageFetch` / `nativeCancelImageFetch`、`CompletionGuard`、`with_bridge_env`、`dispatch_success/error`、惰性 `JVM`/`BRIDGE_CLASS` 缓存 |
| `EhRustBridge.kt` | `pendingImageTasks: ConcurrentHashMap<Long, CancellableContinuation<String>>`、`fetchImagePath()`、`onNativeImageSuccess/Error` 回调 |
| `RustImageFetcher.kt` | 改走 `fetchImagePath()`，不再占用 `Dispatchers.IO` 线程 |

### 保持不动的部分（有意为之）

`withJniSlot`（并发 24 的闸）**保留**，因为列表/详情/评论/收藏/评分/标签翻译
这些元数据调用仍然走 `block_on` —— 它们低频、用户主动等结果、取消没有意义，
改它们风险大收益小。图片路径现在会**绕过**这个闸（用 `fetchImagePath` 而不是 `withJniSlot`），
两条路径的并发上限是独立配置的。

`fetchAndCacheImage`（同步版）**保留** —— 作为诊断/回退入口，
排查时能直接同步调用一次拿到路径。

### 验证情况

- `cargo check --offline`：无 error、无 warning
- `:app:assembleRelease` BUILD SUCCESSFUL
- JNI 符号已确认编入 `.so`：`nativeSubmitImageFetch` / `nativeCancelImageFetch` /
  `onNativeImageSuccess` / `onNativeImageError`
- 真机：安装 Success、冷启动 877ms、首页 25 条、crash buffer 为空
- ⚠️ **阅读器异步路径尚未在真机上跑通验证** —— 需要用户手动打开阅读器
  （本机 MIUI 的 adb 合成触摸不可靠）。这是本次唯一未闭环的一项。
  要确认的点：进入阅读器后正文页正常显示（说明 submit → 回调 → resume 全链路通）；
  以及快速划走时不再有残留请求（取消生效）。

---

## 五、附带：本次同时完成了种子下载 UI 接线

（与 P1-2 无关，顺带记录）

- `rust/src/jni_bridge.rs`：新增导出 `downloadTorrent(name, hash, token)`
- `EhRustBridge.kt`：新增 `downloadTorrent` 声明 + `fetchTorrentFile()` 挂起封装（走 `withJniSlot`）
- `GalleryDetailScreen.kt`：种子列表每条右侧加下载按钮，下载中显示 spinner，
  完成/失败弹 Toast（含保存路径）
- 已核验 JNI 符号 `downloadTorrent` 确实编入了 `libehviewer_rust_backend.so`
