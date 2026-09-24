# 代码审查：EhViewer (Compose + Rust) — 2026-09-22

> **状态更新（同日）**：A、B、C 各级问题已全部修复并重新出包。
> 唯一保留的是 **C2（去重 O(n)）**，原因见文末「修复状态」。
> **B7（屏蔽标签）** 起初被跳过，用户澄清需求后已补做 —— 不是只做客户端过滤，
> 而是查询侧注入 `-ns:tag` 让服务端全量过滤 + 列表侧按 gid→tags 索引过滤。
> 下述问题描述保留原样，便于对照。

审查范围：`android/app/src/main/kotlin`（10.3k 行）+ `rust/src`（4.2k 行）。
只做静态审查，**未修改任何代码**。每条都给出 `文件:行号`，便于直接定位。

审查手法：
- 桥接对账 `grep -rhoP 'EhRustBridge\.\w+' | sort | uniq -c` → 找"声明了但 UI 从不调用"的方法
- 设置对账 `grep -rhoP 'AppSettings\.\w+' | sort | uniq -c` → 找"只写不读"的死设置
- 冷启动对账：`EhApplication.onCreate` 里到底同步了什么给 Rust

---

## A. 严重 — 功能断了 / 数据错

### A1. 下载管理里的"继续"按钮执行的是暂停
`DownloadsScreen.kt:206-213`

```kotlin
if (task.status == 1) { IconButton(onClick = onPause) { Icon(Pause) } }
else if (task.status != 2) { IconButton(onClick = onPause) { Icon(PlayArrow) } }  // ← 显示播放，绑定暂停
```
`onPause` 只调 `EhRustBridge.pauseDownload(gid)`。对 `已暂停(0)` / `失败(-1)` 的任务点
"播放"，实际是再暂停一次，**永远无法继续**。桥接层也没有 resume 包装器
（只有 `triggerDownload`，需要 `imageUrls`）。

修法：新增 `EhRustBridge.resumeDownload(gid)` → Rust `downloader::start_download`
（传空 `image_urls` 触发从 `stored_urls` 续传，这条路径 `downloader.rs:177-181` 已经写好了），
UI 上播放按钮绑新回调。

### A2. 冷启动不同步站点 / 下载目录 / 并发 → 设置"看起来生效，实际丢了"
`EhApplication.kt:135-152` 只做了 `initBackend` + `setUserAgent` + `loadTagDb`。

| 设置项 | Rust 侧状态 | 后果 |
|---|---|---|
| ExHentai 开关 | `network.rs:23` `site_url: RwLock<String>`，纯内存 | 重启后回到 e-hentai.org，但设置页仍显示 ExHentai |
| 下载目录 | `downloader.rs:37` `DOWNLOAD_DIR: Mutex<Option<PathBuf>>`，纯内存 | 重启后为 `None` → `queue_file=None`（`downloader.rs:79`）→ **既不 load 也不 persist，下载列表清空** |
| 下载并发 | `downloader.rs:48` `DOWNLOAD_SLOTS`，纯内存 | 重启后回到默认 |

修法：`onCreate` 的后台线程里补
`setSiteUrl(if (AppSettings.isExHentai(this)) EX else EH)`、
`setDownloadDir(AppSettings.getDownloadDir(this))`、`setDownloadConcurrency(...)`，
**并且在 `setDownloadDir` 之后再 load 队列**（顺序重要）。

### A3. 下载进度用"最大页号+1"当成功数 → 缺页却标"已完成"
`downloader.rs:458` 和 `:499` 都是 `downloaded.fetch_max(i + 1, ...)`。

100 页的画廊，只要第 100 页（index 99）成功，计数器就跳到 100，
而 index 0~98 里失败的页不贡献计数。于是 `sync_progress`(`:397`) 和 finalize(`:523`)
都会判定 `done >= total_pages` → `status = 2` 已完成。
用户看到"已完成"，实际缺页；而因为已完成，`start_download` 的
`if t.downloaded_pages >= t.total_pages { return Ok(()) }`(`downloader.rs:174`)
会直接拒绝再次启动，缺页**永不自愈**。

修法：改成累计成功数（`fetch_add(1)`），并在 finalize 时用
`page_file_exists` 实扫磁盘决定 completed。

### A4. 清空搜索框不重置列表 → 首页结果混进搜索结果
`HomeScreen.kt:787`

```kotlin
loadGalleries(selectedCategory, null)          // 缺 isFullReset = true
```
`loadGalleries`(`HomeScreen.kt:344`) 在 `isFullReset=false` 时不 `clear()`，
走 `:388` 的 `galleryItems.addAll(0, newItems)` 分支 → 首页的 20 条被**前插**到旧搜索结果之上。
全文件其它 11 处调用都显式传了 `isFullReset = true`，只有这一处漏了。

---

## B. 中等 — 未接线 / 明显体验缺陷

### B1. 「E-Hentai 网站设置」页面是个与设置无关的网页壳
`EhWebConfigScreen.kt:55` 硬编码 `loadUrl("https://e-hentai.org/uconfig.php")`。
- 不跟随 A2 里的 ExHentai 开关 → Ex 用户点进去必然失败
- Rust 的 `fetch_eh_web_config` / Kotlin `getEhWebConfig` **全项目 0 次调用**
- 页面标题写着「网站设置」，实际只是一个浏览器

### B2. 评论不能投票
`GalleryComment.voteUrl`(`Models.kt:78`) 已解析，Rust `vote_comment` +
JNI `voteComment` + Kotlin `voteOnComment` 全套都在，但 **0 处 UI 调用**。
`GalleryCommentsScreen` 只展示了 `comment.score`。

### B3. 评论不能翻页
`EhRustBridge.getMoreComments` / Rust `fetch_more_comments` 同样 0 次调用。
`GalleryCommentsScreen.kt:49` 只 `getGalleryDetail()` 拿首页那几条评论，
详情页"查看更多评论"只是换个页面展示同一批。

### B4. 已下载的画廊在 App 内打不开
`DownloadsScreen` 的列表项没有 click，`DownloadTaskCard` 也没有 `onClick` 参数。
下载完成后没有任何入口读本地磁盘的 `page_N.jpg`。
（README 路线图里"离线下载管理的进一步优化"也正是这条。）

### B5. 阅读器双指捏合放大失效
`GalleryReaderScreen.kt:635`
```kotlin
.transformable(state = transformState, enabled = scale > 1.05f)
```
未放大时 `enabled=false`，手势根本收不到，`scale` 永远涨不上去 → **只有双击能放大**。
修法：`enabled = true`，在 lambda 里当 `scale <= 1f` 时忽略 pan/zoomOffset 即可。

### B6. "自动清理过期缓存"实际只在打开设置页时跑一次
`isAutoCleanExpiredCache` 全项目只在 `AdvancedSettingsScreen.kt:50` 读了一次（显示开关），
真正的清理在 `:79-90` 的 `LaunchedEffect(Unit)` 里 —— 只有**进入该页面**才触发。
既没有 Application 启动时检查，也没有周期任务。

### B7. 屏蔽标签不过滤列表
`getBlockedTags` 只在 `GalleryDetailScreen.kt:750` 用于给标签打标记，
首页 / 搜索结果 / 分类页**完全不过滤**。屏蔽的含义应该是从结果里消失。

### B8. `suggest_tags_online` 漏了注释里承诺的顶层数组分支
`api.rs:168-180`：注释写"payload 可能是 JSON 数组，也可能是按 tag id 索引的对象"，
实现只处理 `value.get("tags")`，顶层数组落到 `_ => Vec::new()` → 在线补全静默返回空。
需实测一次 `api.php method=tagsuggest` 的真实返回来定分支
（`adb logcat -s EhRust` 看 `tagsuggest('xxx') → N suggestions`）。

### B9. 收藏 / 评分不看返回值
`GalleryDetailScreen.kt:649-665`、`1221-1229`：`submitFavorite` / `rate` 的返回值丢弃，
无论成败都 Toast"成功"，失败的乐观更新也不回滚。

### B10. 下载每完成一页就全量重写 downloads.json
`downloader.rs:402`（`sync_progress` 内）与 `:542`：4 并发 × 200 页 = 200 次
"全量序列化 + 落盘"，且每次都拿 `QUEUE` 写锁。建议改成防抖/批量（如 2s 一次）。

---

## C. 轻微

- **`api.rs:19` `IMAGE_PROGRESS` 无界增长**：只 `insert` 无 `remove`，每个看过的
  viewer URL 永久驻留。单个画廊看几千页就是几千条字符串。建议 LRU 或完成后清理。
- **`HomeScreen.kt:320/386/443`**：每次 loadMore / 刷新都 `galleryItems.map{it.gid}.toSet()`，
  主线程 O(n) 遍历 + 分配。维护一个伴随 `HashSet` 更好。
- **`HomeScreen.kt:460 / 485` 双发窗口**：两个 `LaunchedEffect` 都能触发初始加载，
  `isLoading = true` 在 `scope.launch` 协程体内才置位，同帧内守卫可能失效。
  实际后果只是多发一次请求（`isFullReset=true` 会清列表，不会重复项），优先级低。
- **`GalleryDetailScreen.kt:939-954`**：发完评论整页 `loadGalleryDetail()` 重拉，
  还连带重新预热缩略图。局部 append 更合适。
- **`getDefaultSearchCategory` 全项目只被 `SearchSettingsScreen.kt:36` 自己读取** → 死设置。
- **`GalleryDetailScreen.kt:750-751`**：`remember(detail)` 快照屏蔽/关注集合，
  在设置页改完后返回详情页不会刷新。
- **抽屉没有内容分类**：`SearchOptions.fCats` 恒为 `null`（`HomeScreen.kt:273`），
  `DrawerNavCategory` 只有 主页/订阅/热门/排行榜/收藏/历史，
  EH 的 10 个内容分类（Doujinshi/Manga/…）无入口。

---

## D. 架构层面值得做的（非 bug）

1. **元数据路径仍是 `block_on`**：图片路径已异步化（Rust 持有任务 + 回调续程，
   `jni_bridge.rs:736+`），这点做得对。但 list/detail/comments 仍占满调用线程，
   靠 `JNI_MAX_INFLIGHT=24` 限流兜底。下一步可把 list/detail 也异步化。
2. **首页列表缓存 TTL 300s**（上一轮加的 `fetch_list_with_cache`）：
   详情页缓存 `get_cached` **没有 TTL**（`api.rs:389`），评分/收藏数/是否已收藏
   会永久陈旧。建议给详情页单独一个较短 TTL 或下拉刷新时强制失效。
3. **下载状态的双轮询**：`DownloadsScreen` 1s 轮询 + `DownloadService` 3s 轮询，
   且都调 `getDownloads()`（一次 JNI + 全量序列化）。可合并为单一数据源 + Flow。
4. **`EhApplication` 的 Coil 配置**（25% heap + 256MB disk）与 Rust 的 512MB 图片缓存
   叠加，这台设备 `MemFree` 只有 ~393MB（上一轮实测）。读完长篇后
   `lowmemorykiller` 压力大，可考虑按 `ActivityManager.isLowRamDevice` 分级。

---

## 附：建议修复顺序

1. A2（冷启动同步）— 一行代码级别投入，修掉三个"设置不生效"
2. A1（继续下载）— 用户能直接感知的功能缺失
3. A4（清空搜索）— 一个参数
4. A3（下载计数）— 数据正确性
5. B5（捏合）— 阅读器核心手势
6. B1/B2/B3/B4（接线）— 按产品优先级排

---

## 修复状态（2026-09-22 同日完成）

| 编号 | 状态 | 落地要点 |
|---|---|---|
| A1 继续下载 | ✅ | 新增 JNI `resumeDownload`，播放按钮改绑 `onResume` |
| A2 冷启动同步 | ✅ | `EhApplication` 重放 siteUrl / downloadDir / concurrency |
| A3 下载计数 | ✅ | 改"磁盘真实页数"：扫盘基线 + `fetch_add(1)` + finalize 实扫 |
| A4 清空搜索 | ✅ | 补 `isFullReset = true` |
| B1 网站配置页 | ✅ | 跟随 `isExHentai`（分享链接同样修了） |
| B2 评论投票 | ✅ | 接入 `voteOnComment`，按 voteUrl 回写本地分数 |
| B3 评论分页 | ✅ | 接入 `getMoreComments` + `commentKey` 去重 + 「加载更多」 |
| B4 离线阅读 | ✅ | 新路由 `?offline=`，`getDownloadedPagePaths` → Coil `File` |
| B5 捏合放大 | ✅ | Initial pass 观察指针数，`enabled = multiTouch \|\| scale > 1.05f` |
| B6 自动清理 | ✅ | 挪到冷启动执行，删掉"进设置页才清理" |
| B8 tagsuggest | ✅ | 兼容顶层数组 + 空结果告警日志 |
| B9 收藏/评分 | ✅ | 检查返回值、失败回滚、0 星本地拦截 |
| B10 persist 写放大 | ✅ | 进度路径 2s 节流，状态转换仍强制写 |
| C1 进度表泄漏 | ✅ | 加 256 上限，先清 1.0 再兜底 clear |
| C3 首屏双发 | ✅ | `isLoading` 提到协程之外同步置位 |
| C4 默认搜索类别 | ✅ | `f_cats` 排除掩码映射表 |
| D2 detail 缓存 TTL | ✅ | `get_cached_within(120s)` |
| B7 屏蔽标签过滤列表 | ✅ | 两层：查询侧 `-ns:tag` 排除（服务端全量）+ 列表侧 `blocked.rs` 的 gid→tags 索引；新 JNI `setBlockedTags` / `filterBlockedGalleryIds`；详情页加提示条 |
| **C2 去重 O(n)** | ⏭️ 跳过 | `galleryItems` 跨屏共享，伴生 Set 同步遗漏会导致列表缺项；收益仅一次几百项遍历，风险 > 收益 |

### 验证

- `cargo check --offline` 无 error 无 warning；`cargo test --lib` **19 passed / 2 ignored**（无回归）
- BUILD SUCCESSFUL（6m33s），仅 3 条既有弃用警告
- APK **7,879,123 字节**
- 进包物证：dex 含 `本地没有已下载的图片` / `加载更多评论` / `这条评论有帮助` /
  `收藏失败，请检查登录状态` / `请先选择星数`；`.so` 含
  `EhRustBridge_resumeDownload` / `EhRustBridge_getDownloadedPages` 符号
- ⚠️ **真机未验证**：`adb devices` 为空（设备未连接）。插上后需确认冷启动日志
  `Restored site URL` / `Restored download directory` / `Restored download concurrency`，
  并试用离线阅读与评论分页
