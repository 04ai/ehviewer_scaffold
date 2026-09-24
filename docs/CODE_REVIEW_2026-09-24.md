# 代码审查：EhViewer (Compose + Rust) — 2026-09-24

> 审查范围：`android/app/src/main/kotlin`（11.0k 行，33 个文件）+ `rust/src`（5.4k 行，9 个文件）。
> 全部结论**基于当前工作区代码**（而非历史文档），关键条目已逐条回源验证。
> 上一轮审查：`CODE_REVIEW_2026-09-22.md` + `PERFORMANCE_REVIEW.md`（其修复状态见文末交叉核对）。

---

## 一、结论：**未达到最佳状态**

分层看：

| 层面 | 评价 |
|---|---|
| **Rust 内核** | ✅ **已经很扎实**。历史 4 个 P0 + 8 个 P1 全部核实落地；23 个单测通过 |
| **Android UI** | 🟡 **仍有 3 个 A 级功能缺陷**（收藏假成功、投票分数错、刷新竞态）+ 一批 B/C 级 |
| **工程/交付** | 🔴 **有硬伤：41 个文件、6787 行改动从未提交** |

一句话：**"骨架最佳、细节未收敛"。** Rust 侧可以打 90 分，UI 侧约 75 分，工程管理不及格。

---

## 二、已经很好、不要动的部分

### 2.1 Rust 内核（逐项回源验证）

| 项 | 位置 | 状态 |
|---|---|---|
| `opt-level = 3` + 无 `panic = "abort"` | `Cargo.toml` | ✅ 且注释解释了取舍 |
| http2 / gzip / brotli / charset | `Cargo.toml`，`h2`+`async-compression` 确认在 `Cargo.lock` | ✅ |
| 缓存写入不做全目录扫描 | `cache.rs:64,67,76` `AtomicU64 disk_bytes` + `AtomicBool evicting`（单飞） | ✅ |
| 图片缓存 key = `md5(viewer_url)` | `api.rs` 命中路径 | ✅ |
| 标签搜索零分配索引 | `tag_translator.rs`（`rows_cache_lowercase_forms` 测试通过） | ✅ |
| `IMAGE_PROGRESS` 有界 | `api.rs:41` `IMAGE_PROGRESS_CAP = 256` | ✅ |
| 下载并发用真 Semaphore | `downloader.rs:48,452,508`，含 `PENDING_RECLAIM` 防上限漂移 | ✅ |
| 详情/列表缓存 TTL | `api.rs:52` 120s / `:461` 300s | ✅ |
| **刻意保留**：元数据路径 `block_on` | `EhRustBridge.kt:40` `JNI_MAX_INFLIGHT = 24` 兜底 | 设计取舍，见 `JNI_ASYNC_DESIGN_REVIEW.md` |

单测结果：`cargo test --lib` → **23 passed / 0 failed / 2 ignored**（ignored 的两个缺 fixture，设计如此）。

### 2.2 已被上一轮修好、本轮复核确认的点（**不要重复怀疑**）

- 下载"播放键绑暂停" → 已修（`DownloadsScreen.kt:249-259`，`onResume` → `resumeDownloadTask`）
- 离线图片传裸路径 → 已修（`pageImageModel` 返回 `java.io.File`）
- 捏合缩放失效 → 已修（Initial pass 观察指针数，`enabled = multiTouch || scale > 1.05f`）
- 进度轮询忙等 → 已有退避（50ms ×1.5，封顶 400ms）
- 评论投票未接线 / 评论分页未接线 / 发表评论全量重拉 → 均已接线，发表评论改为局部 append
- 跨会话"编造功能"（评论头像圆圈、树形回复）→ 代码中明确注释规避，**无伪功能**

---

## 三、新发现的真问题（A 级）

### A1. 收藏失败被判成功 —— Rust 侧没有未登录校验 ⭐

**位置**：`rust/src/api.rs:1054-1067`（Rust）+ `GalleryDetailScreen.kt:705`（Kotlin）

```rust
pub async fn add_favorite(...) -> Result<String> {
    let res = NETWORK_CLIENT.post_form(&url, &form).await?;
    Ok(res)          // ← 无条件 Ok，没有校验响应内容
}
```

对比同一文件里的**其他三个写操作都有校验**：

| 函数 | 校验 |
|---|---|
| `post_comment` `api.rs:1081-1086` | 检测 "requires you to log on" / "not logged in" → `bail!` |
| `vote_gallery` `api.rs:1111-1113` | 检测 "not logged in" / "insufficient" → `bail!` |
| `vote_comment` `api.rs:1302-1304` | 同上 |
| **`add_favorite`** | **无** |

Kotlin 侧靠 `res.contains("\"error\"")` 判定成败（`GalleryDetailScreen.kt:705`），
而该错误串只在 Rust 返回 `Err` 时由 `result_to_json` 生成。

**后果**：未登录时点收藏 → EH 返回 HTML（HTTP 200）→ 判定为成功 →
弹出 **"已添加到收藏夹"**、心形保持填充、**不回滚**。用户以为收藏成功，实际没有。
反向风险同样存在：若返回 HTML 里恰好含 `"error"` 子串，会把成功误判为失败。

**修法**（二选一，推荐前者）：
1. Rust `add_favorite` 补上与 `post_comment` 一致的文案校验；
2. 更彻底：Rust 返回结构化结果（`{"ok":true}`），Kotlin 改判 `ok` 字段，消除子串匹配的双向误判。

> 上一轮 `B9 收藏/评分返回值检查` 标记为"已修"，但**只改了 Kotlin 侧的回滚逻辑，
> Rust 侧从未产出错误信号**，所以这条修复对收藏路径实际无效。这是本轮最重要的发现。

### A2. 评论投票本地分数可无限叠加，与服务端幂等语义冲突

**位置**：`GalleryCommentsScreen.kt:135-138`

```kotlin
val delta = if (up) 1 else -1
comments = comments.map {
    if (it.voteUrl == comment.voteUrl) it.copy(score = it.score + delta) else it
}
```

E-Hentai 的评论投票是**按 (用户 × 评论) 只记一票**，重复调用是"改票"而非"累加"。

**后果**：
- 连点 3 次"顶" → 本地显示 +3，服务端仍是 +1；
- 先顶后踩 → 本地净 0，服务端应为一票反对（−1），**分数直接错**；
- 没有"已投票"状态、不 disable 按钮、无撤销/切换，且 `vote_comment` 不回读新分数（`api.rs:1278-1316`），错误无法自愈。

**修法**：每条评论保存 `myVote ∈ {-1,0,1}`，按"旧票 → 新票"的差值更新
（无票→顶 = +1；顶→踩 = −2），已投票时禁止重复累加。

### A3. 下拉刷新与"加载更多"并发，会写坏分页游标 → 静默缺页

**位置**：`HomeScreen.kt:455`（守卫）、`:613-673`（刷新）、`:457-481`（loadMore 回写）、`:1236-1241`（onRefresh）

```kotlin
// 455 守卫：漏了 isRefreshing
if (!nearEnd || isLoadingMore || !hasMore || isLoading || !isPlaybackActive) return@collect
```

```kotlin
// 1236 onRefresh 只置 isRefreshing；refreshGalleries() 内部既不设也不查 isLoading/isLoadingMore
onRefresh = { isRefreshing = true; refreshGalleries() }
```

已核实 `refreshGalleries()` 全程不触碰 `isLoading` / `isLoadingMore`（grep 无命中）。

**后果**：刷新不立即清列表（等结果回来才替换），所以在途时旧列表仍可滚动 →
用户继续下滑触发 loadMore。两条协程交错后，loadMore 用**刷新前的旧值**回写：

```kotlin
currentPage = nextPage            // ← 覆盖刷新刚写好的 currentPage = 0
currentNextUrl = result.nextUrl   // ← 覆盖刷新刚写好的 nextUrl
```

列表变成"第 0 页 + 第 N 页"，**第 1..N-1 页永远拿不到**，且分页从 N 继续，只有手动再刷新才能恢复。
刷新 `finally` 里的 `delay`（`:665-670`）还把 `isRefreshing` 多留最多 450ms，进一步放大窗口。

**修法**（最小改动）：loadMore 守卫补 `|| isRefreshing`，并在 `refreshGalleries()` 开头复位 `isLoadingMore = false`（避免被卡死）。
彻底方案：引入世代令牌，loadMore 恢复后校验未变才回写。

---

## 四、B 级（明确缺陷，影响可控）

| # | 问题 | 位置 | 说明 |
|---|---|---|---|
| B1 | **进度轮询无异常保护 → 可崩溃** | `GalleryReaderScreen.kt:676-678` | `LaunchedEffect` 里裸调 `EhRustBridge.getImageProgress`，无 try/catch。异常会向上传播**直接崩溃**。同文件其他 JNI 调用都有防护，唯独这条高频轮询没有 |
| B2 | **预取内存缓存 key 不匹配，且按全尺寸解码** | `GalleryReaderScreen.kt:373-382` | `.memoryCacheKey(url)` 但未指定 `.size()`；Coil 的内存 key 会拼入尺寸，`AsyncImage` 用控件约束尺寸、裸请求默认 `ORIGINAL` → **预热根本命不中**，却按原图尺寸解码一张大图，白烧内存与 CPU |
| B3 | **评论分页把"本页无新增"当"没有更多"** | `GalleryCommentsScreen.kt:96-98` | `fresh.isEmpty()` 直接 `hasMoreComments = false`。Rust 明确注释"页间会重复"（`api.rs:1319`），中间某页恰好全重复时会**静默截断**后续全部评论 |
| B4 | **快速切分类/重复搜索无世代校验** | `HomeScreen.kt:509-601` | `scope.launch` 之间无序号或取消；先点"主页"再点"热门"，**谁后返回谁生效** → 标题与列表不符，且污染 `HomeStateHolder` 落盘状态 |
| B5 | **详情页内存缓存无 TTL，绕过 Rust 的 120s** | `GalleryDetailScreen.kt:93,204` + `MemoryCache.kt:21-35` | `GalleryDetailCache` 只有 LRU 无时间戳；`if (detail == null \|\| force)` 使内存命中后**永不重拉**，收藏数/评分/评论在进程存活期内一直陈旧 |
| B6 | **下载服务每秒 start/stop** | `DownloadsScreen.kt:69-73` | 1s 轮询里无条件调 `DownloadService.start/stop`，每个 tick 都触发 `startForegroundService` + 通知重建，属无意义跨进程 churn。应按**状态跃迁**触发 |
| B7 | **离线阅读不恢复进度** | `MainAppNavHost.kt:104-105` | 路由 `reader/$gid/$token?offline=true` **不带 page**；offline 分支只写进度不读（`GalleryReaderScreen.kt:240-247`）。从下载列表进入永远从第 1 页开始 |
| B8 | **评论加载失败被吞成"暂无评论"** | `api.rs:1321-1327` + `GalleryCommentsScreen.kt:104-106` | Rust 网络出错返回空 `Vec`，Kotlin 空 catch。用户无法区分"真的没评论"和"加载失败" |
| B9 | **`refreshUid()` 在主线程做 CookieManager（binder IPC）** | `HomeScreen.kt:675` | 同文件 `:302-305` 已明确用 `withContext(Dispatchers.IO)` 做同一件事，此处是回归式不一致 |

---

## 五、C 级（轻微 / 清理）

- **主线程 O(n) 全量遍历**：`HomeScreen.kt:473`、`:283` 在每次 loadMore / 屏蔽变化时 `galleryItems.map{}.toSet()`，列表可达上千项
- **死代码**：`HomeScreen.kt:576-580` 的 `isFullReset=false` 前置插入分支**已不可达**（13 处调用全部传 `true`）
- **未接线的桥接方法**（可清理，非 bug）：`getEhWebConfig` / `getFrontPage` / `getResolvedImageUrl` / `translateTag`
  - 其中 `getResolvedImageUrl`（→ `resolveImageUrl`）确认**零调用**：图片已全部走 `fetchImagePath` 异步路径
- **吞掉 CancellationException**：`GalleryCommentsScreen.kt:104`、`GalleryDetailScreen.kt:239` 的 `catch (_: Exception)` 会把协程取消当失败处理，破坏结构化并发
- **`getImageProgress` 绕过 `withJniSlot`**：`GalleryReaderScreen.kt:676` 直接 `withContext(Dispatchers.IO)`，违背项目 JNI 闸约定（有退避所以影响小）
- **`commentKey` 用 (author,time,content)**：`GalleryCommentsScreen.kt:50` 会丢弃同作者同内容的正当重复评论；注释已说明是有意取舍，可接受
- **`derivedStateOf` 捕获陈旧阈值**：`GalleryDetailScreen.kt:1221` 无 key

---

## 六、工程 / 交付层面（🔴 需要立刻处理）

1. **41 个文件、6787 行改动从未提交**
   最后一次提交是 `212f5db`。之后所有工作（性能审查全部修复 + 代码审查全部修复 + 本轮相关改动）都在工作区。
   **一次误操作即全丢。** 且有 3 个 `screen_*.png` 处于删除态进了索引，`git status` 混乱。
2. **根目录堆积产物**（项目已约定禁止）：
   - `verify_waterfall_fixed.png`（2.3 MB，验证截图，约定"交付后随手删"）
   - `ehviewer-arm64-v8a-release.apk`（7.9 MB，与 `android/app/build/outputs/apk/release/` 重复）
3. **`AGENT.md` 与 `.agents/AGENT.md` 重复**
4. ✅ **好消息**：APK 与源码是同步的（APK `09-23 19:40` ≥ 最新源码 `09-23 19:37`，`.so` `09-22 21:57` ≥ 最新 Rust 源 `09-22 21:54`），交付物未过期。

---

## 七、排除的误报（避免重复排查）

以下几条在审查中被怀疑，但**经回源验证为不成立**：

| 怀疑 | 验证结果 |
|---|---|
| 离线路径 `file://` 前缀导致 Coil 加载失败 | ❌ **误报**。`downloader.rs:402` 返回的是 `p.to_string_lossy()` **绝对路径，无 `file://`**，`java.io.File(source)` 正确 |
| Rust 磁盘缓存与 Coil 磁盘缓存双份落盘 | ❌ **误报**。`RustImageFetcher` 直接返回 `SourceFetchResult`，从不接触 Coil DiskCache |
| 截断详情页缓存 TTL 不生效 | ⚠️ 部分成立：Rust 侧 TTL 确实生效，但被 Kotlin 内存缓存前置拦截（已列为 B5） |
| 状态声明顺序前向引用 | ❌ **未发现**。全部 `var X by remember` 与使用点已比对，无误 |
| 回顶 token 漏 key | ❌ **正确**。两个消费者（`:381`、`:439`）都带了 `scrollResetToken` |
| 阅读器索引越界 / 除零 | ❌ **安全**。`pageSize` 有 `coerceAtLeast(1)`，Map 取值有 `?: continue` |

---

## 八、建议的修复顺序

**第 1 批（A 级，用户可直接感知）**
1. **A1 收藏假成功** — Rust `add_favorite` 补未登录校验（改一处，收益最大）
2. **A3 刷新竞态** — loadMore 守卫补 `isRefreshing` + 刷新入口复位 `isLoadingMore`
3. **A2 投票分数** — 引入 `myVote` 差值更新

**第 2 批（B 级，健壮性 / 体验）**
4. B1 轮询加 try/catch（**崩溃风险，建议提到第 1 批**）
5. B2 预取补 `.size()` 或直接删除（收益小、代价大）
6. B4 世代校验（顺带解决 A3 的彻底方案）
7. B3 / B5 / B7 / B8（正确性与体验）

**第 3 批（工程）**
8. **提交所有改动**（最高优先级的"非代码"问题）
9. 清理根目录产物 + 处理 `AGENT.md` 重复
10. C 级清理

---

## 九、复核说明

- 本报告所有 A 级条目与 B1/B5/B7/B9 均**已回源验证**（直接读取对应行号代码）。
- B2/B3/B4/B6/B8 依据代码结构与 Rust 侧契约推断，逻辑链在代码层面确定，但**建议修复前先看一次实际行号**。
- 所有结论均为静态审查；B2（预取命中）与 B1（崩溃）建议在真机上用 `adb logcat -s EhRust` 复现确认。
