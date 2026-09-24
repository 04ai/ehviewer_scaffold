# 搜索框标签补全：为什么它依赖下载，以及现在怎么工作

## 问题

输入英文前缀（比如 `kaf`）得不到任何补全建议，除非用户先下载了中文标签库。
追下去发现匹配邏輯本身是对的，**问题在于它只有一个数据源**：

```rust
// rust/src/tag_translator.rs（修改前）
let Ok(db) = TAG_DB.read() else { return Vec::new() };
for row in db.rows.iter() {          // ← rows 只有下载后才非空
    let matched = row.translated_lower.contains(&kw) || row.raw_lower.contains(&kw);
```

`TAG_DB.rows` 由 `tag_db.json`（来自 EhTagTranslation 社区精选库）构建。
没下载 → `rows` 为空 → 返回空列表 → 下拉框一条都不出，
于是**纯英文的功能变成了依赖 10 MB 中文数据库的功能**。

补充事实：本机实际上**已经下载过**标签库（启动时日志
`Tag database loaded into memory (44264 entries)`）。所以用户遇到的
「`kaf` 匹配不到」不是"没下载"，而是**精选库只有 4.4 万条，覆盖不了 EH 全部vocabulary**——
长尾标签照样搜不到。两个场景的根因同一个：单一数据源不够。

## 现在的三层数据源

| 层 | 来源 | 是否需要下载 | 覆盖什么 |
|---|---|---|---|
| 1 | 精选中文标签库（`tag_db.json`） | 是（可选） | 中文反查 + 4.4 万条常见标签 |
| 2 | **浏览中学到的标签**（`SEEN_TAGS`） | **否** | 你实际打开过的画廊上真实存在的标签 |
| 3 | **EH 服务端补全**（`api.php` → `tagsuggest`） | 否（需网络） | 站点全量vocabulary，含长尾 |

### 第 2 层：浏览学到的标签

`GalleryDetail` 解析完成后（**含缓存命中路径**），`tag_translator::learn_tags()`
把每个标签记进一个插入序索引：

- 上限 `SEEN_TAGS_CAP = 20_000` 条，满了静默停止学习（它只是建议源，不影响正确性）
- 去重键是 `ns:tag`，重复打开同一画廊不会让索引膨胀
- 检索时按 `0=精确 / 1=前缀 / 2=子串` 排序，和标签库的排序规则一致

**为什么特意在缓存命中路径也学**：缓存命中直接 `return`，如果只挂在新解析路径上，
那恰好是"用户以前看过、现在又打开"的那些画廊不会被记住——也就是最该记住的那些。

### 第 3 层：服务端补全

```
POST {site}/api.php
Content-Type: application/json
{"method":"tagsuggest","text":"kaf"}
```

响应体形如 `{"tags":[{"ns":"female","tn":"kafka"}]}`。
不同版本/站点可能把 `tags` 返成数组或对象，所以两种都接受；
返回非 JSON 时降级为空列表并 `warn` 一条日志。

这一层在 UI 里由已经存在的 220ms 防抖触发，**不进每次按键的路径**。
离线时它返回空，前两层继续工作。

## 合并顺序

Kotlin 侧（`HomeScreen` 的防抖块）：

1. 先本地扫描（标签库 + 学到的标签），立即更新下拉框
2. 再发服务端请求，成功后合并去重
3. **英文输入** → 服务端结果排前面（站点最清楚自己的词表）；
   **中文输入** → 本地标签库排前面（服务端是英文词表，中文反查不出来）

合并前用 `if (!isActive)` 拦一道：`withContext(Dispatchers.IO)` 里跑的是阻塞式
`block_on`，取消无法协作地打断它，所以旧任务可能在被取消后仍跑完回写，
否则会把新输入的结果覆盖掉。

## 顺带修的：Rust 日志此前全部被丢弃

排查时发现这个 crate **从来没有安装 logger**——没有依赖 `android_logger`，
也没有 `set_logger`。于是所有 `log::info!` / `log::error!` 在 Android 上
被编译进去却直接丢掉，**一条都没进过 logcat**。

这解释了一件事：之前几轮排查里"logcat 搜不到 `Auto-loaded tag database`"
被当成证据推断"用户没下载标签库"，而实际上日志根本不会输出。

现在在 `init_backend()` 里装了：

```rust
let _ = android_logger::init_once(
    android_logger::Config::default()
        .with_tag("EhRust")
        .with_max_level(log::LevelFilter::Info),
);
```

- `Info` 级别：hot path（图片下载、进度轮询）里没有高于 Debug 的日志，不会拖慢翻页
- `init_once` 幂等，Activity 重启重复初始化无害
- 查 Rust 日志：`adb logcat -s EhRust`

## 下拉框的退出机制

原先只有一种退出方式：**点到输入框以外的地方**。原因是 `showDropdown` 完全由
`isSearchFocused` 驱动，而滑动列表不会让输入框失焦 —— 于是单手滑动时看起来像"卡住了"。

现在三个触发点，都复用同一个 `dismissSuggestions()`（保持行为完全一致，
且**不会清掉已输入的词**，与原先"点外部"的表现一致）：

| 触发 | 实现 |
|---|---|
| 滑动结果列表 | `derivedStateOf { gridState.isScrollInProgress \|\| listState.isScrollInProgress }` → `LaunchedEffect` 的 true 沿触发 |
| 在下拉面板本身上滑动 | 面板 `Surface` 加 `detectVerticalDragGestures(onDragStart = { ... })` |
| 按返回键 | `BackHandler(enabled = isSearchFocused)` |

第三条挂决策：返回键先收面板，再谈退出页面，否则第一次返回就直接离开当前页了。

面板必须自己处理滑动是因为**它盖住了列表** —— 在面板上滑动根本到不了列表，
也就没有滚动事件可用，只剩底下那一小条列表可以点/滑。

## 验证状态

| 项 | 结果 |
|---|---|
| `cargo check --offline` | 无 error / 无 warning |
| `cargo test --lib` | **19 passed / 0 failed / 2 ignored** |
| 新增单测 | `english_search_works_without_translation_db`、`learn_tags_deduplicates` |
| `:app:assembleRelease` | BUILD SUCCESSFUL |
| 真机 | 安装成功、冷启动 1034ms、无崩溃 |
| 日志器 | 已确认生效（`Tag database loaded into memory (44264 entries)` 等均已输出） |
| **服务端补全实际返回** | ⚠️ **待真机验证**——公网环境到 `api.e-hentai.org` 不通，无法本地印证；需用户在搜索框实际输入触发 |

APK：**7,810,683 字节**（含 `android_logger`）。
