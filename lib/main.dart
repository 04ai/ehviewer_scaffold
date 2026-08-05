import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:isolate';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:flutter_cache_manager/flutter_cache_manager.dart';
import 'package:flutter_staggered_animations/flutter_staggered_animations.dart';
import 'package:path_provider/path_provider.dart';
import 'src/rust/frb_generated.dart';
import 'src/rust/api.dart';
import 'src/rust/parser.dart';
import 'pages/settings_page.dart';
import 'pages/cookie_login_page.dart';
import 'pages/downloads_page.dart';
import 'pages/favorites_page.dart';
import 'pages/ranking_page.dart';

import 'widgets/pixel_shift_wrapper.dart';
import 'widgets/app_lock_wrapper.dart';
import 'utils/secure_cookies.dart';
import 'widgets/gallery_item_widget.dart';
import 'widgets/glass.dart';
import 'package:permission_handler/permission_handler.dart';
import 'providers/history_provider.dart';
import 'providers/settings_provider.dart';
import 'providers/downloads_provider.dart';
import 'utils/reader_progress.dart';
import 'widgets/home_tab_list_view.dart';

// ─── 全部分类定义 ────────────────────────────────────────────────────────────
const _kCategories = [
  _CategoryInfo('Doujinshi', 'doujinshi', Color(0xFFEF5350)),
  _CategoryInfo('Manga',     'manga',     Color(0xFFFF9800)),
  _CategoryInfo('Artist CG', 'artistcg',  Color(0xFFCDDC39)),
  _CategoryInfo('Game CG',   'gamecg',    Color(0xFF4CAF50)),
  _CategoryInfo('Western',   'western',   Color(0xFF66BB6A)),
  _CategoryInfo('Non-H',     'non-h',     Color(0xFF26C6DA)),
  _CategoryInfo('Image Set', 'imageset',  Color(0xFF1565C0)),
  _CategoryInfo('Cosplay',   'cosplay',   Color(0xFF7B1FA2)),
  _CategoryInfo('Asian Porn','asianporn', Color(0xFFE91E63)),
  _CategoryInfo('Misc',      'misc',      Color(0xFFF48FB1)),
];

class _CategoryInfo {
  final String label;
  final String key;
  final Color color;
  const _CategoryInfo(this.label, this.key, this.color);
}

// ─── 多瀑布流(HomeTab) 数据模型与 Provider ───────────────────────────────────
class HomeTab {
  final String id;
  final String name;
  final Set<String> activeCategories;
  final String? searchQuery;

  HomeTab({required this.id, required this.name, required this.activeCategories, this.searchQuery});

  Map<String, dynamic> toJson() => {
    'id': id,
    'name': name,
    'activeCategories': activeCategories.toList(),
    'searchQuery': searchQuery,
  };

  factory HomeTab.fromJson(Map<String, dynamic> json) => HomeTab(
    id: json['id'],
    name: json['name'],
    activeCategories: Set<String>.from(json['activeCategories'] ?? []),
    searchQuery: json['searchQuery'],
  );
}

final homeTabsProvider = StateNotifierProvider<HomeTabsNotifier, List<HomeTab>>((ref) {
  return HomeTabsNotifier();
});

class HomeTabsNotifier extends StateNotifier<List<HomeTab>> {
  HomeTabsNotifier() : super([
    HomeTab(id: 'default', name: '全部', activeCategories: _kCategories.map((c) => c.key).toSet())
  ]) {
    _load();
  }

  Future<void> _load() async {
    final prefs = await SharedPreferences.getInstance();
    final data = prefs.getStringList('eh_home_tabs');
    if (data != null && data.isNotEmpty) {
      try {
        state = data.map((e) => HomeTab.fromJson(jsonDecode(e))).toList();
      } catch (e) {
        // ignore
      }
    }
  }

  void _save(List<HomeTab> tabs) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setStringList('eh_home_tabs', tabs.map((e) => jsonEncode(e.toJson())).toList());
  }

  void addTab(String name) {
    final newTab = HomeTab(
      id: DateTime.now().millisecondsSinceEpoch.toString(),
      name: name,
      activeCategories: _kCategories.map((c) => c.key).toSet()
    );
    state = [...state, newTab];
    _save(state);
  }

  void updateSearch(String id, String? query) {
    state = [
      for (final t in state)
        if (t.id == id) HomeTab(id: t.id, name: t.name, activeCategories: t.activeCategories, searchQuery: query) else t
    ];
    _save(state);
  }

  void removeTab(String id) {
    if (state.length <= 1) return;
    state = state.where((t) => t.id != id).toList();
    _save(state);
  }

  void renameTab(String id, String newName) {
    state = state.map((t) => t.id == id ? HomeTab(id: t.id, name: newName, activeCategories: t.activeCategories, searchQuery: t.searchQuery) : t).toList();
    _save(state);
  }

  void toggleCategory(String id, String category) {
    state = state.map((t) {
      if (t.id == id) {
        final newCats = Set<String>.from(t.activeCategories);
        if (newCats.contains(category)) {
          newCats.remove(category);
        } else {
          newCats.add(category);
        }
        return HomeTab(
          id: t.id,
          name: t.name,
          activeCategories: newCats,
          searchQuery: t.searchQuery,
        );
      }
      return t;
    }).toList();
    _save(state);
  }
}

// ─── 账号信息 Provider ────────────────────────────────────────────────────────
final accountInfoProvider = StateNotifierProvider<AccountInfoNotifier, AccountInfo>((ref) {
  return AccountInfoNotifier();
});

class AccountInfo {
  final String username;
  final String avatarUrl;
  final bool isLoggedIn;
  const AccountInfo({this.username = '', this.avatarUrl = '', this.isLoggedIn = false});
}

class AccountInfoNotifier extends StateNotifier<AccountInfo> {
  AccountInfoNotifier() : super(const AccountInfo()) {
    _load();
  }

  Future<void> _load() async {
    final prefs = await SharedPreferences.getInstance();
    String username = prefs.getString('eh_username') ?? '';
    final avatarUrl = prefs.getString('eh_avatar_url') ?? '';
    
    // Fallback if already logged in via cookies but username wasn't set
    if (username.isEmpty) {
      String memberId = '';
      try {
        memberId = await readCookie(kCookieMemberId) ?? '';
      } catch (_) {
        // ignore: secure storage unavailable
      }
      if (memberId.isNotEmpty) {
        username = 'UID: $memberId';
      }
    }

    state = AccountInfo(
      username: username,
      avatarUrl: avatarUrl,
      isLoggedIn: username.isNotEmpty,
    );
  }

  void update(String username, String avatarUrl) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString('eh_username', username);
    await prefs.setString('eh_avatar_url', avatarUrl);
    state = AccountInfo(username: username, avatarUrl: avatarUrl, isLoggedIn: username.isNotEmpty);
  }

  void logout() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove('eh_username');
    await prefs.remove('eh_avatar_url');
    state = const AccountInfo();
  }
}

// ─── 当前页面 Provider ────────────────────────────────────────────────────────
final currentPageProvider = StateProvider<String>((ref) => 'home');

/// 申请应用运行所需的核心权限：
/// - 联网：系统自动处理，无需运行时申请
/// - 存储：Android 10+ 使用 Scoped Storage，不需要显式申请
///         Android 13+ 需要细粒度媒体权限（READ_MEDIA_IMAGES）
///         Android 9 及以下需要 READ/WRITE_EXTERNAL_STORAGE
/// - 通知：Android 13+ 需要运行时申请（POST_NOTIFICATIONS）
Future<void> _requestRequiredPermissions() async {
  if (!Platform.isAndroid) return;

  final List<Permission> permissionsToRequest = [];

  // 存储权限（按 Android 版本区分）
  if (await Permission.storage.isDenied) {
    // Android 9 (SDK 28) 及以下
    permissionsToRequest.add(Permission.storage);
  }
  if (await Permission.photos.isDenied) {
    // Android 13+ (SDK 33) - READ_MEDIA_IMAGES
    permissionsToRequest.add(Permission.photos);
  }

  // 通知权限（Android 13+）
  if (await Permission.notification.isDenied) {
    permissionsToRequest.add(Permission.notification);
  }

  if (permissionsToRequest.isNotEmpty) {
    await permissionsToRequest.request();
  }

  // 如果存储权限被永久拒绝，引导用户去设置页面手动开启
  final storageStatus = await Permission.storage.status;
  final photosStatus = await Permission.photos.status;
  if (storageStatus.isPermanentlyDenied || photosStatus.isPermanentlyDenied) {
    // 用户需要去系统设置里手动开启，App 继续运行（下载功能会失败，其余正常）
    debugPrint('[Permission] 存储权限被永久拒绝，下载功能将不可用');
  }
}

// ══════════════════════════════════════════════════════════════════════════════
void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await RustLib.init();
  
  final tempDir = await getTemporaryDirectory();
  final prefs = await SharedPreferences.getInstance();

  await initBackend(cacheDir: "${tempDir.path}/eh_image_cache", enableEhHost: false);

  // ─── 运行时权限申请 ────────────────────────────────────────
  await _requestRequiredPermissions();

  final isExHentai = prefs.getBool('setting_is_exhentai') ?? false;
  await setSiteUrl(url: isExHentai ? "https://exhentai.org" : "https://e-hentai.org");
  await loadAndSyncCookies();
  
  try {
    final docDir = await getApplicationDocumentsDirectory();
    final dbPath = "${docDir.path}/eh_tag_db.json";
    if (await File(dbPath).exists()) {
      await loadTagDb(path: dbPath);
    }
  } catch (e) {
    debugPrint("Failed to load tag db: $e");
  }
  
  await _performAutoCacheClear();
  
  runApp(const ProviderScope(child: MyApp()));
}

Future<void> _performAutoCacheClear() async {
  try {
    final prefs = await SharedPreferences.getInstance();
    final autoClearCache = prefs.getBool('appearance_auto_clear_cache') ?? true;
    final autoClearDays = prefs.getInt('appearance_auto_clear_cache_days') ?? 7;
    
    if (!autoClearCache) return;

    final lastClearMs = prefs.getInt('last_cache_clear_time') ?? 0;
    final now = DateTime.now();
    final lastClearDate = DateTime.fromMillisecondsSinceEpoch(lastClearMs);
    
    if (now.difference(lastClearDate).inDays >= autoClearDays) {
      debugPrint("Auto clearing cache after $autoClearDays days...");
      await DefaultCacheManager().emptyCache();
      
      final tempDir = await getTemporaryDirectory();
      final tempPath = tempDir.path;
      // Delete on a background isolate: recursive synchronous deletion of a
      // large cache would otherwise jank the UI thread on every cold start.
      await Isolate.run(() {
        final dir = Directory(tempPath);
        if (!dir.existsSync()) return;
        dir.listSync(recursive: true, followLinks: false).forEach((entity) {
          // Keep downloaded galleries intact: eh_downloads lives inside the
          // temp dir but is never part of the image cache.
          if (entity is File && !entity.path.contains('eh_downloads')) {
            try { entity.deleteSync(); } catch (_) {}
          }
        });
      });
      
      await prefs.setInt('last_cache_clear_time', now.millisecondsSinceEpoch);
    }
  } catch (e) {
    debugPrint("Failed to auto clear cache: $e");
  }
}

class MyApp extends ConsumerStatefulWidget {
  const MyApp({super.key});

  @override
  ConsumerState<MyApp> createState() => _MyAppState();
}

class _MyAppState extends ConsumerState<MyApp> with WidgetsBindingObserver {
  final GlobalKey<ScaffoldMessengerState> _messengerKey = GlobalKey<ScaffoldMessengerState>();
  Timer? _downloadsPoller;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    // Global download-status poller: drives the downloaded badges on gallery
    // cards and shows a toast when a background download finishes.
    _downloadsPoller = Timer.periodic(const Duration(seconds: 3), (_) => _pollDownloads());
  }

  Future<void> _pollDownloads() async {
    final status = ref.read(downloadsProvider);
    await status.poll();
    final title = status.takeCompletedTitle();
    if (title != null) {
      _messengerKey.currentState
        ?..hideCurrentSnackBar()
        ..showSnackBar(SnackBar(content: Text('下载完成: $title')));
    }
  }

  @override
  void didChangePlatformBrightness() {
    // Re-evaluate the theme when following the system dark mode.
    ref.read(appearanceProvider.notifier).refreshTheme();
  }

  @override
  void dispose() {
    _downloadsPoller?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final appearance = ref.watch(appearanceProvider);
    return PixelShiftWrapper(
      child: AppLockWrapper(
        // Smoothly crossfades every color/style when the theme changes
        // (light/dark switch, color accent picker, OLED mode, ...).
        child: TweenAnimationBuilder<ThemeData?>(
          tween: ThemeTween(end: appearance.themeData),
          duration: const Duration(milliseconds: 350),
          curve: Curves.easeOutCubic,
          builder: (context, theme, child) => MaterialApp(
            title: 'EHviewer Scaffold',
            theme: theme ?? appearance.themeData,
            scaffoldMessengerKey: _messengerKey,
            home: const GalleryListPage(),
            debugShowCheckedModeBanner: false,
          ),
        ),
      ),
    );
  }
}

/// Animates between two ThemeData instances so theme changes crossfade
/// smoothly instead of snapping.
class ThemeTween extends Tween<ThemeData?> {
  ThemeTween({super.begin, super.end});

  @override
  ThemeData? lerp(double t) {
    if (begin == null) return end;
    return ThemeData.lerp(begin!, end!, t);
  }

  // Tween 默认不覆写 ==，而 builder 每次 build 都会 new 一个 ThemeTween，
  // 导致主题未变化时也重放 350ms 渐变（每次 setState 都闪一下）。
  // ThemeData 实现了深比较 ==，相同参数构建的 ThemeData 相等。
  @override
  bool operator ==(Object other) =>
      other is ThemeTween && other.begin == begin && other.end == end;

  @override
  int get hashCode => Object.hash(begin, end);
}

// ══════════════════════════════════════════════════════════════════════════════
class SearchSuggestion {
  final String text;
  final bool isHistory;
  final String subtitle;
  SearchSuggestion(this.text, this.isHistory, {this.subtitle = ''});

  @override
  bool operator ==(Object other) => identical(this, other) || other is SearchSuggestion && runtimeType == other.runtimeType && text == other.text;
  
  @override
  int get hashCode => text.hashCode;
}

class GalleryListPage extends ConsumerStatefulWidget {
  const GalleryListPage({super.key});

  @override
  ConsumerState<GalleryListPage> createState() => _GalleryListPageState();
}

class _GalleryListPageState extends ConsumerState<GalleryListPage> with TickerProviderStateMixin {
  final GlobalKey<ScaffoldState> _scaffoldKey = GlobalKey<ScaffoldState>();
  final TextEditingController _searchController = TextEditingController();
  final FocusNode _searchFocusNode = FocusNode();
  String? _currentSearchQuery;
  
  TabController? _tabController;
  int _currentTabIndex = 0;
  bool _showTabIndicator = false;
  Timer? _tabIndicatorTimer;
  int _refreshCount = 0;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _refresh();
    });
  }

  void _initTabController(int length) {
    if (_tabController?.length != length) {
      _tabController?.dispose();
      _tabController = TabController(length: length, vsync: this);
      _tabController!.addListener(_handleTabChange);
    }
  }

  void _handleTabChange() {
    if (_tabController!.index != _currentTabIndex) {
      setState(() {
        _currentTabIndex = _tabController!.index;
        _showTabIndicator = true;
      });
      _tabIndicatorTimer?.cancel();
      _tabIndicatorTimer = Timer(const Duration(milliseconds: 2000), () {
        if (mounted) setState(() => _showTabIndicator = false);
      });
    }
  }

  @override
  void dispose() {
    _tabController?.dispose();
    _searchController.dispose();
    _searchFocusNode.dispose();
    _tabIndicatorTimer?.cancel();
    _drawerBlurTimer?.cancel();
    super.dispose();
  }

  // ── 抽屉毛玻璃：动画期间降级为纯半透明，动画结束再启用模糊 ──────────
  // BackdropFilter 在抽屉开合动画的 ~250ms 内每帧重采样背景，低端 GPU
  // 会掉帧；动画结束后背景静止，模糊只算一次，等价于"静态模糊快照"。
  bool _drawerBlurOn = false;
  Timer? _drawerBlurTimer;

  void _onDrawerChanged(bool open) {
    _drawerBlurTimer?.cancel();
    if (open) {
      // 等动画结束后再开模糊（抽屉动画约 250ms）。
      _drawerBlurTimer = Timer(const Duration(milliseconds: 350), () {
        if (mounted) setState(() => _drawerBlurOn = true);
      });
    } else {
      if (mounted) setState(() => _drawerBlurOn = false);
    }
  }

  void _refresh() {
    setState(() {
      _currentSearchQuery = _searchController.text.trim().isEmpty ? null : _searchController.text.trim();
      _refreshCount++;
    });
    if (_currentSearchQuery != null) {
      ref.read(searchProvider.notifier).addHistory(_currentSearchQuery!);
    }
  }

  Future<void> _openSettings() async {
    final loggedIn = await Navigator.of(context).push<bool>(
      MaterialPageRoute(builder: (_) => const SettingsPage()),
    );
    if (loggedIn == true) _refresh();
  }

  // ─── 侧边栏头部：账号头像 + 名字 ──────────────────────────────────────────
  Widget _buildDrawerHeader(AccountInfo account) {
    return InkWell(
      onTap: () {
        Navigator.pop(context);
        Navigator.push(context, MaterialPageRoute(builder: (_) => const CookieLoginPage()));
      },
      child: Container(
        padding: const EdgeInsets.fromLTRB(16, 48, 16, 16),
        color: Theme.of(context).colorScheme.primary,
        child: Row(
          children: [
            CircleAvatar(
              radius: 28,
              backgroundColor: const Color(0xFFE94560),
              backgroundImage: account.avatarUrl.isNotEmpty
                  ? NetworkImage(account.avatarUrl)
                  : null,
              child: account.avatarUrl.isEmpty
                  ? Text(
                      account.isLoggedIn
                          ? account.username.substring(0, 1).toUpperCase()
                          : '?',
                      style: const TextStyle(fontSize: 22, color: Colors.white, fontWeight: FontWeight.bold),
                    )
                  : null,
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Text(
                    account.isLoggedIn ? account.username : '未登录',
                    style: const TextStyle(
                      color: Colors.white,
                      fontSize: 17,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    account.isLoggedIn ? '点击管理账号' : '点击登录 E-Hentai',
                    style: const TextStyle(color: Colors.white60, fontSize: 12),
                  ),
                ],
              ),
            ),
            const Icon(Icons.arrow_forward_ios, color: Colors.white38, size: 14),
          ],
        ),
      ),
    );
  }

  // ─── 侧边栏菜单项 ─────────────────────────────────────────────────────────
  Widget _buildDrawerItem({
    required IconData icon,
    required String label,
    required String pageKey,
    required VoidCallback onTap,
    bool isActive = false,
    Widget? trailing,
  }) {
    return Container(
      margin: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
      decoration: BoxDecoration(
        color: isActive
            ? Theme.of(context).colorScheme.primaryContainer.withOpacity(0.35)
            : Colors.transparent,
        borderRadius: BorderRadius.circular(12),
      ),
      child: ListTile(
        leading: Icon(
          icon,
          color: isActive
              ? Theme.of(context).colorScheme.primary
              : Theme.of(context).iconTheme.color?.withOpacity(0.65),
        ),
        title: Text(
          label,
          style: TextStyle(
            fontSize: 16,
            fontWeight: isActive ? FontWeight.bold : FontWeight.normal,
            color: isActive
                ? Theme.of(context).colorScheme.primary
                : Theme.of(context).textTheme.bodyLarge?.color,
          ),
        ),
        trailing: trailing,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
        onTap: () {
          onTap();
        },
      ),
    );
  }

  // ─── 管理瀑布流弹窗 ─────────────────────────────────────────────────────────
  void _showManageTabsDialog() {
    showDialog(
      context: context,
      builder: (context) => Consumer(
        builder: (context, ref, _) {
          final tabs = ref.watch(homeTabsProvider);
          return AlertDialog(
            title: Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                const Text('管理瀑布流', style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
                IconButton(
                  icon: const Icon(Icons.add_circle, color: Color(0xFFE94560)),
                  tooltip: '新建瀑布流',
                  onPressed: () {
                    showDialog(
                      context: context,
                      builder: (ctx) {
                        final ctrl = TextEditingController();
                        return AlertDialog(
                          title: const Text('新建瀑布流'),
                          content: TextField(
                            controller: ctrl,
                            decoration: const InputDecoration(hintText: '输入名称（如：纯爱漫画）'),
                          ),
                          actions: [
                            TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('取消')),
                            ElevatedButton(
                              onPressed: () {
                                if (ctrl.text.trim().isNotEmpty) {
                                  ref.read(homeTabsProvider.notifier).addTab(ctrl.text.trim());
                                }
                                Navigator.pop(ctx);
                              },
                              child: const Text('保存'),
                            ),
                          ],
                        );
                      }
                    );
                  },
                )
              ],
            ),
            content: SizedBox(
              width: double.maxFinite,
              child: ListView.builder(
                shrinkWrap: true,
                itemCount: tabs.length,
                itemBuilder: (context, index) {
                  final tab = tabs[index];
                  return ExpansionTile(
                    title: Text(tab.name, style: const TextStyle(fontWeight: FontWeight.bold)),
                    trailing: tabs.length > 1 ? IconButton(
                      icon: const Icon(Icons.delete, color: Colors.redAccent, size: 20),
                      onPressed: () => ref.read(homeTabsProvider.notifier).removeTab(tab.id),
                    ) : null,
                    children: [
                      Padding(
                        padding: const EdgeInsets.all(8.0),
                        child: Wrap(
                          spacing: 6,
                          runSpacing: 6,
                          children: _kCategories.map((c) {
                            final isOn = tab.activeCategories.contains(c.key);
                            return GestureDetector(
                              onTap: () => ref.read(homeTabsProvider.notifier).toggleCategory(tab.id, c.key),
                              child: Container(
                                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                                decoration: BoxDecoration(
                                  color: isOn ? c.color : c.color.withOpacity(0.15),
                                  borderRadius: BorderRadius.circular(4),
                                  border: Border.all(color: isOn ? c.color : c.color.withOpacity(0.3)),
                                ),
                                child: Text(
                                  c.label,
                                  style: TextStyle(
                                    fontSize: 12,
                                    fontWeight: FontWeight.bold,
                                    color: isOn ? Colors.white : c.color,
                                  ),
                                ),
                              ),
                            );
                          }).toList(),
                        ),
                      )
                    ],
                  );
                },
              ),
            ),
            actions: [
              TextButton(
                onPressed: () => Navigator.pop(context),
                child: const Text('完成'),
              )
            ],
          );
        },
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final account = ref.watch(accountInfoProvider);
    final currentPage = ref.watch(currentPageProvider);
    final tabs = ref.watch(homeTabsProvider);

    _initTabController(tabs.length);

    return PopScope(
      canPop: currentPage == 'home',
      onPopInvoked: (didPop) {
        if (!didPop) {
          ref.read(currentPageProvider.notifier).state = 'home';
        }
      },
      child: Scaffold(
        key: _scaffoldKey,
        backgroundColor: Theme.of(context).scaffoldBackgroundColor,
        // 边缘滑出宽度默认 20px 偏窄（费力），36px 更易滑出；
        // 不算太大，竖直滚动瀑布流时不易误触（Flutter 只识别边缘水平拖拽）。
        drawerEdgeDragWidth: 36,
        onDrawerChanged: _onDrawerChanged,
        drawer: Drawer(
          backgroundColor: Colors.transparent,
          // 毛玻璃抽屉：模糊背后的主体页面（Drawer 是 overlay 层，BackdropFilter 有效）。
          // 开合动画期间 forceDisable 降级为纯半透明，动画结束恢复模糊，
          // 避免动画期每帧重采样背景导致掉帧。
          child: GlassContainer(
            tint: Theme.of(context).colorScheme.surface,
            tintOpacity: 0.45,
            borderRadius: BorderRadius.zero,
            forceDisable: !_drawerBlurOn,
            child: ListView(
            padding: EdgeInsets.zero,
            children: [
              _buildDrawerHeader(account),
              const SizedBox(height: 8),

              _buildDrawerItem(
                icon: Icons.home,
                label: '主页',
                pageKey: 'home',
                isActive: currentPage == 'home',
                onTap: () {
                  ref.read(currentPageProvider.notifier).state = 'home';
                  Navigator.pop(context);
                },
                trailing: IconButton(
                  icon: const Icon(Icons.settings, size: 20, color: Colors.black45),
                  onPressed: () {
                    Navigator.pop(context);
                    _showManageTabsDialog();
                  },
                  tooltip: '管理瀑布流',
                )
              ),

              const Padding(padding: EdgeInsets.symmetric(horizontal: 16, vertical: 4), child: Divider(height: 1)),

              _buildDrawerItem(
                icon: Icons.subscriptions, label: '订阅', pageKey: 'subscription',
                isActive: currentPage == 'subscription',
                onTap: () {
                  ref.read(currentPageProvider.notifier).state = 'subscription';
                  Navigator.pop(context);
                },
              ),
              _buildDrawerItem(
                icon: Icons.local_fire_department, label: '热门', pageKey: 'popular',
                isActive: currentPage == 'popular',
                onTap: () {
                  ref.read(currentPageProvider.notifier).state = 'popular';
                  Navigator.pop(context);
                },
              ),
              _buildDrawerItem(
                icon: Icons.leaderboard, label: '排行榜', pageKey: 'ranking',
                isActive: false,
                onTap: () {
                  Navigator.pop(context);
                  Navigator.push(
                    context,
                    MaterialPageRoute(builder: (_) => const RankingPage()),
                  );
                },
              ),
              _buildDrawerItem(
                icon: Icons.favorite, label: '收藏', pageKey: 'favorites',
                isActive: currentPage == 'favorites',
                onTap: () {
                  Navigator.pop(context);
                  Navigator.push(context, MaterialPageRoute(builder: (_) => const FavoritesPage()));
                },
              ),
              _buildDrawerItem(
                icon: Icons.history, label: '历史', pageKey: 'history',
                isActive: currentPage == 'history',
                onTap: () {
                  ref.read(currentPageProvider.notifier).state = 'history';
                  Navigator.pop(context);
                },
              ),

              const Padding(padding: EdgeInsets.symmetric(horizontal: 16, vertical: 4), child: Divider(height: 1)),

              _buildDrawerItem(
                icon: Icons.download, label: '下载', pageKey: 'downloads',
                isActive: currentPage == 'downloads',
                onTap: () {
                  ref.read(currentPageProvider.notifier).state = 'downloads';
                  Navigator.pop(context);
                  Navigator.push(context, MaterialPageRoute(builder: (_) => const DownloadsPage()));
                },
              ),
              _buildDrawerItem(
                icon: Icons.settings, label: '设置', pageKey: 'settings',
                isActive: currentPage == 'settings',
                onTap: () {
                  Navigator.pop(context);
                  _openSettings();
                },
              ),
            ],
          ),
        ),
      ),
      body: Builder(builder: (context) {
        switch (currentPage) {
          case 'subscription':
            return const CustomListView(path: 'watched', title: '订阅');
          case 'popular':
            return const CustomListView(path: 'popular', title: '热门');
          case 'history':
            return const HistoryView();
          case 'home':
          default:
            return _buildHomeView(context, account, tabs);
        }
      }),
    ));
  }

  /// 主界面顶部毛玻璃搜索栏。
  /// 必须作为 Stack 浮层绘制在列表**之上**（先画列表、后画搜索栏），
  /// `BackdropFilter` 才能采样到滚动中的画廊内容；放在 SliverAppBar
  /// 里会因 sliver 先于 body 绘制而永远模糊不到东西。
  Widget _buildHomeSearchBar(BuildContext context) {
    return GlassContainer(
      height: 52,
      borderRadius: BorderRadius.circular(26),
      tintOpacity: 0.25,
      child: Row(
        children: [
          IconButton(
            icon: Icon(Icons.menu, color: Theme.of(context).iconTheme.color),
            onPressed: () => _scaffoldKey.currentState?.openDrawer(),
          ),
          Expanded(
            child: RawAutocomplete<SearchSuggestion>(
              textEditingController: _searchController,
              focusNode: _searchFocusNode,
              displayStringForOption: (SearchSuggestion option) => option.text,
              optionsBuilder: (TextEditingValue textEditingValue) async {
                final text = textEditingValue.text.trimLeft();
                final history = ref.read(searchProvider).history;

                List<SearchSuggestion> suggestions = [];

                if (text.isNotEmpty) {
                  try {
                    final lastWord = text.split(RegExp(r'\s+')).last;
                    if (lastWord.length >= 2) {
                      if (RegExp(r'[\u4e00-\u9fff]').hasMatch(lastWord)) {
                        final matches = searchTagByChinese(keyword: lastWord);
                        suggestions
                            .addAll(matches.map((m) => SearchSuggestion(m.raw, false, subtitle: m.translated)));
                      }
                      final res = await fetchAutocomplete(prefix: lastWord);
                      final json = jsonDecode(res) as Map;
                      if (json.containsKey('tags')) {
                        final tags = json['tags'];
                        if (tags is List) {
                          suggestions.addAll(tags.map((e) => SearchSuggestion(e.toString(), false)));
                        } else if (tags is Map) {
                          suggestions.addAll(tags.values.map((e) {
                            if (e is Map && e.containsKey('ns') && e.containsKey('tn')) {
                              return SearchSuggestion("${e['ns']}:${e['tn']}", false);
                            }
                            return SearchSuggestion(e.toString(), false);
                          }));
                        }
                      }
                    }
                  } catch (_) {}
                }

                if (text.isEmpty) {
                  suggestions.addAll(history.map((h) => SearchSuggestion(h, true)));
                } else {
                  suggestions.addAll(history
                      .where((h) => h.toLowerCase().contains(text.toLowerCase()))
                      .map((h) => SearchSuggestion(h, true)));
                }

                final unique = <String, SearchSuggestion>{};
                for (var s in suggestions) {
                  if (!unique.containsKey(s.text)) unique[s.text] = s;
                }
                return unique.values.toList();
              },
              onSelected: (SearchSuggestion selection) {
                if (selection.isHistory) {
                  _searchController.text = selection.text;
                } else {
                  final text = _searchController.text;
                  final words = text.split(RegExp(r'\s+'));
                  if (words.isNotEmpty) {
                    words.removeLast();
                    words.add(selection.text);
                    _searchController.text = '${words.join(' ')} ';
                  } else {
                    _searchController.text = '${selection.text} ';
                  }
                }
                _searchFocusNode.unfocus();
                _refresh();
              },
              fieldViewBuilder: (BuildContext context, TextEditingController textEditingController,
                  FocusNode focusNode, VoidCallback onFieldSubmitted) {
                return TextField(
                  controller: textEditingController,
                  focusNode: focusNode,
                  style: TextStyle(color: Theme.of(context).textTheme.bodyLarge?.color, fontSize: 17),
                  decoration: InputDecoration(
                    hintText: '搜索画廊...',
                    hintStyle: TextStyle(color: Theme.of(context).textTheme.bodyMedium?.color, fontSize: 17),
                    border: InputBorder.none,
                    isDense: true,
                    contentPadding: const EdgeInsets.symmetric(vertical: 16),
                  ),
                  onSubmitted: (_) {
                    onFieldSubmitted();
                    _refresh();
                  },
                );
              },
              optionsViewBuilder: (BuildContext context, AutocompleteOnSelected<SearchSuggestion> onSelected,
                  Iterable<SearchSuggestion> options) {
                return Align(
                  alignment: Alignment.topLeft,
                  // 建议列表也做毛玻璃（overlay 浮层，BackdropFilter 有效）。
                  child: GlassContainer(
                    tintOpacity: 0.5,
                    borderRadius: BorderRadius.circular(12),
                    child: ConstrainedBox(
                      constraints: BoxConstraints(
                          maxHeight: 280, maxWidth: MediaQuery.of(context).size.width - 64),
                      child: ListView.builder(
                        padding: EdgeInsets.zero,
                        shrinkWrap: true,
                        itemCount: options.length,
                        itemBuilder: (BuildContext context, int index) {
                          final SearchSuggestion option = options.elementAt(index);
                          return InkWell(
                            onTap: () => onSelected(option),
                            child: Padding(
                              padding: const EdgeInsets.symmetric(vertical: 12.0, horizontal: 16.0),
                              child: Row(
                                children: [
                                  Icon(option.isHistory ? Icons.history : Icons.local_offer_outlined,
                                      color: Theme.of(context).iconTheme.color?.withOpacity(0.5),
                                      size: 18),
                                  const SizedBox(width: 8),
                                  Expanded(
                                    child: Column(
                                      crossAxisAlignment: CrossAxisAlignment.start,
                                      mainAxisSize: MainAxisSize.min,
                                      children: [
                                        Text(option.text,
                                            style: TextStyle(
                                                color: Theme.of(context).textTheme.bodyLarge?.color)),
                                        if (option.subtitle.isNotEmpty)
                                          Text(option.subtitle,
                                              style: TextStyle(
                                                  color: Theme.of(context)
                                                      .textTheme
                                                      .bodyMedium
                                                      ?.color
                                                      ?.withOpacity(0.6),
                                                  fontSize: 12)),
                                      ],
                                    ),
                                  ),
                                ],
                              ),
                            ),
                          );
                        },
                      ),
                    ),
                  ),
                );
              },
            ),
          ),
          if (_searchController.text.isNotEmpty)
            IconButton(
              icon: Icon(Icons.clear, color: Theme.of(context).iconTheme.color?.withOpacity(0.5), size: 20),
              onPressed: () {
                _searchController.clear();
                _refresh();
              },
            ),
          IconButton(
            icon: Icon(Icons.search, color: Theme.of(context).iconTheme.color),
            onPressed: _refresh,
          ),
        ],
      ),
    );
  }

  Widget _buildHomeView(BuildContext context, AccountInfo account, List<HomeTab> tabs) {
    return Stack(
      children: [
        NotificationListener<UserScrollNotification>(
            onNotification: (notification) {
              FocusManager.instance.primaryFocus?.unfocus();
              return false;
            },
            child: NestedScrollView(
            physics: const AlwaysScrollableScrollPhysics(),
            floatHeaderSlivers: true,
            headerSliverBuilder: (context, innerBoxIsScrolled) => [
              const SliverSafeArea(
                bottom: false,
                sliver: SliverAppBar(
                  floating: true,
                  snap: true,
                  backgroundColor: Colors.transparent,
                  elevation: 0,
                  toolbarHeight: 84,
                  automaticallyImplyLeading: false,
                  // 搜索栏已移出 SliverAppBar，作为 Stack 浮层绘制在列表之上，
                  // 这样 BackdropFilter 才能采样到滚动内容（见 _buildHomeSearchBar）。
                  title: SizedBox.shrink(),
                ),
              ),
            ],
            body: _buildHomeBody(tabs),
          ),
          ),
        // 顶部毛玻璃搜索栏：必须绘制在列表之后（Stack 上层），
        // 否则 SliverAppBar 中的 BackdropFilter 因 sliver 绘制顺序采样不到 body。
        Positioned(
          top: MediaQuery.of(context).padding.top + 8,
          left: 16,
          right: 16,
          child: _buildHomeSearchBar(context),
        ),
        if (_showTabIndicator && tabs.isNotEmpty)
          Positioned(
            top: MediaQuery.of(context).padding.top + 120,
            left: 0, right: 0,
            child: IgnorePointer(
              child: Center(
                child: AnimatedOpacity(
                  opacity: _showTabIndicator ? 1.0 : 0.0,
                  duration: const Duration(milliseconds: 300),
                  child: Container(
                    padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 12),
                    decoration: BoxDecoration(
                      // 跟随主题强调色，不再硬编码玫红。
                      color: Theme.of(context).colorScheme.primary,
                      borderRadius: BorderRadius.circular(24),
                      boxShadow: const [BoxShadow(color: Colors.black26, blurRadius: 8, offset: Offset(0, 4))]
                    ),
                    child: Text(
                      _currentTabIndex < tabs.length ? tabs[_currentTabIndex].name : '',
                      style: TextStyle(
                        color: Theme.of(context).colorScheme.onPrimary,
                        fontSize: 16,
                        fontWeight: FontWeight.bold,
                        letterSpacing: 1.0,
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ),
      ],
    );
  }

  Widget _buildHomeBody(List<HomeTab> tabs) {
    if (tabs.isEmpty) {
      return const Center(
        child: Text('没有配置任何瀑布流', style: TextStyle(color: Colors.white54)),
      );
    }

    return TabBarView(
      key: ValueKey(tabs.length),
      controller: _tabController,
      children: tabs.map((tab) {
        return HomeTabListView(
          tab: tab,
          searchQuery: _currentSearchQuery,
          blockedTags: ref.watch(searchProvider).blockedTags,
          refreshCount: _refreshCount,
        );
      }).toList(),
    );
  }
}

class CustomListView extends ConsumerStatefulWidget {
  final String path;
  final String title;
  final String? initialQuery;
  final bool standalone;
  final bool showMenu;

  const CustomListView({
    super.key,
    required this.path,
    required this.title,
    this.initialQuery,
    this.standalone = false,
    this.showMenu = true,
  });

  @override
  ConsumerState<CustomListView> createState() => _CustomListViewState();
}

class _CustomListViewState extends ConsumerState<CustomListView> {
  final List<GalleryItem> _items = [];
  int _currentPage = 0;
  String? _nextPageUrl;
  bool _isLoadingInitial = true;
  bool _isLoadingMore = false;
  bool _hasMore = true;
  bool _isRefreshing = false;
  String? _error;
  final ScrollController _scrollController = ScrollController();
  final TextEditingController _searchController = TextEditingController();
  final FocusNode _searchFocusNode = FocusNode();
  String? _currentSearchQuery;

  SearchOptions _buildSearchOptions() {
    final searchSettings = ref.read(searchProvider);
    return SearchOptions(
      fSname: searchSettings.includeName,
      fStags: searchSettings.includeTags,
      fSdesc: searchSettings.includeDesc,
      fCats: null,
    );
  }

  @override
  void initState() {
    super.initState();
    if (widget.initialQuery != null) {
      _searchController.text = widget.initialQuery!;
    }
    _scrollController.addListener(_onScroll);
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _loadInitial();
    });
  }

  void _onScroll() {
    if (_scrollController.hasClients &&
        _scrollController.position.pixels >= _scrollController.position.maxScrollExtent - 300) {
      if (!_isLoadingMore && !_isLoadingInitial && _hasMore && _error == null) {
        _loadMore();
      }
    }
  }

  @override
  void dispose() {
    _scrollController.dispose();
    _searchController.dispose();
    _searchFocusNode.dispose();
    super.dispose();
  }

  Future<void> _loadInitial() async {
    setState(() {
      _isLoadingInitial = true;
      _isRefreshing = true;
      _error = null;
      _currentPage = 0;
      _nextPageUrl = null;
      _hasMore = true;
      _items.clear();
    });

    _currentSearchQuery = _searchController.text.trim().isEmpty ? null : _searchController.text.trim();
    if (_currentSearchQuery != null) {
      ref.read(searchProvider.notifier).addHistory(_currentSearchQuery!);
    }

    try {
      final pageData = await fetchCustomList(path: widget.path, page: 0, pageUrl: null, query: _currentSearchQuery, options: _buildSearchOptions());
      if (!mounted) return;
      setState(() {
        _items.addAll(pageData.items);
        _nextPageUrl = pageData.nextUrl;
        if (pageData.nextUrl == null || pageData.items.isEmpty) _hasMore = false;
        _isLoadingInitial = false;
        _isRefreshing = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = e.toString();
        _isLoadingInitial = false;
        _isRefreshing = false;
      });
    }
  }

  Future<void> _loadMore() async {
    if (_isLoadingMore || !_hasMore || _isLoadingInitial) return;
    setState(() => _isLoadingMore = true);

    try {
      final nextPage = _currentPage + 1;
      final pageData = await fetchCustomList(path: widget.path, page: nextPage, pageUrl: _nextPageUrl, query: _currentSearchQuery, options: _buildSearchOptions());
      if (!mounted) return;
      setState(() {
        _currentPage = nextPage;
        _nextPageUrl = pageData.nextUrl;
        if (pageData.items.isEmpty) {
          _hasMore = false;
        } else {
          final existingGids = _items.map((i) => i.gid).toSet();
          final uniqueNewItems = pageData.items.where((i) => !existingGids.contains(i.gid)).toList();
          _items.addAll(uniqueNewItems);
          if (_nextPageUrl == null || uniqueNewItems.isEmpty) _hasMore = false;
        }
        _isLoadingMore = false;
      });
    } catch (e) {
      if (mounted) setState(() => _isLoadingMore = false);
    }
  }

  void _refresh() {
    _loadInitial();
  }

  /// 搜索结果页顶部毛玻璃搜索栏（Stack 浮层，绘制在列表之上，
  /// BackdropFilter 才能采样到滚动内容；放 SliverAppBar 内会因
  /// sliver 先于 body 绘制而无法模糊）。
  Widget _buildCustomSearchBar(BuildContext context) {
    return GlassContainer(
      height: 52,
      borderRadius: BorderRadius.circular(26),
      tintOpacity: 0.25,
      child: Row(
        children: [
          widget.standalone
              ? IconButton(
                  icon: const Icon(Icons.arrow_back),
                  onPressed: () => Navigator.maybePop(context),
                )
              : (widget.showMenu
                  ? IconButton(
                      icon: Icon(Icons.menu, color: Theme.of(context).iconTheme.color),
                      onPressed: () => Scaffold.of(context).openDrawer(),
                    )
                  : const SizedBox.shrink()),
          Expanded(
            child: widget.path.startsWith('toplist.php')
                ? Text(widget.title,
                    style: TextStyle(color: Theme.of(context).textTheme.bodyLarge?.color, fontSize: 16))
                : RawAutocomplete<SearchSuggestion>(
                    textEditingController: _searchController,
                    focusNode: _searchFocusNode,
                    displayStringForOption: (SearchSuggestion option) => option.text,
                    optionsBuilder: (TextEditingValue textEditingValue) async {
                      final text = textEditingValue.text.trimLeft();
                      final history = ref.read(searchProvider).history;

                      List<SearchSuggestion> suggestions = [];

                      if (text.isNotEmpty) {
                        try {
                          final lastWord = text.split(RegExp(r'\s+')).last;
                          if (lastWord.length >= 2) {
                            if (RegExp(r'[\u4e00-\u9fff]').hasMatch(lastWord)) {
                              final matches = searchTagByChinese(keyword: lastWord);
                              suggestions.addAll(
                                  matches.map((m) => SearchSuggestion(m.raw, false, subtitle: m.translated)));
                            }
                            final res = await fetchAutocomplete(prefix: lastWord);
                            final json = jsonDecode(res) as Map;
                            if (json.containsKey('tags')) {
                              final tags = json['tags'];
                              if (tags is List) {
                                suggestions.addAll(tags.map((e) => SearchSuggestion(e.toString(), false)));
                              } else if (tags is Map) {
                                suggestions.addAll(tags.keys.map((e) => SearchSuggestion(e.toString(), false)));
                              }
                            }
                          }
                        } catch (_) {}
                      }

                      if (text.isEmpty) {
                        suggestions.addAll(history.map((h) => SearchSuggestion(h, true)));
                      } else {
                        suggestions.addAll(history
                            .where((h) => h.toLowerCase().contains(text.toLowerCase()))
                            .map((h) => SearchSuggestion(h, true)));
                      }

                      final unique = <String, SearchSuggestion>{};
                      for (var s in suggestions) {
                        if (!unique.containsKey(s.text)) unique[s.text] = s;
                      }
                      return unique.values.toList();
                    },
                    onSelected: (SearchSuggestion selection) {
                      if (selection.isHistory) {
                        _searchController.text = selection.text;
                      } else {
                        final text = _searchController.text;
                        final words = text.split(RegExp(r'\s+'));
                        if (words.isNotEmpty) {
                          words.removeLast();
                          words.add(selection.text);
                          _searchController.text = '${words.join(' ')} ';
                        } else {
                          _searchController.text = '${selection.text} ';
                        }
                      }
                      _searchFocusNode.unfocus();
                      _refresh();
                    },
                    fieldViewBuilder: (BuildContext context, TextEditingController textEditingController,
                        FocusNode focusNode, VoidCallback onFieldSubmitted) {
                      return TextField(
                        controller: textEditingController,
                        focusNode: focusNode,
                        style: TextStyle(color: Theme.of(context).textTheme.bodyLarge?.color),
                        decoration: InputDecoration(
                          hintText: '在${widget.title}中搜索...',
                          hintStyle: TextStyle(color: Theme.of(context).textTheme.bodyMedium?.color),
                          border: InputBorder.none,
                          isDense: true,
                          contentPadding: const EdgeInsets.symmetric(vertical: 14),
                        ),
                        onSubmitted: (_) {
                          onFieldSubmitted();
                          _refresh();
                        },
                      );
                    },
                    optionsViewBuilder: (BuildContext context, AutocompleteOnSelected<SearchSuggestion> onSelected,
                        Iterable<SearchSuggestion> options) {
                      return Align(
                        alignment: Alignment.topLeft,
                        // 建议列表也做毛玻璃（overlay 浮层，BackdropFilter 有效）。
                        child: GlassContainer(
                          tintOpacity: 0.5,
                          borderRadius: BorderRadius.circular(12),
                          child: ConstrainedBox(
                            constraints:
                                BoxConstraints(maxHeight: 250, maxWidth: MediaQuery.of(context).size.width - 64),
                            child: ListView.builder(
                              padding: EdgeInsets.zero,
                              shrinkWrap: true,
                              itemCount: options.length,
                              itemBuilder: (BuildContext context, int index) {
                                final SearchSuggestion option = options.elementAt(index);
                                return InkWell(
                                  onTap: () => onSelected(option),
                                  child: Padding(
                                    padding: const EdgeInsets.symmetric(vertical: 12.0, horizontal: 16.0),
                                    child: Row(
                                      children: [
                                        Icon(option.isHistory ? Icons.history : Icons.local_offer_outlined,
                                            color: Theme.of(context).iconTheme.color?.withOpacity(0.5),
                                            size: 18),
                                        const SizedBox(width: 8),
                                        Expanded(
                                          child: Column(
                                            crossAxisAlignment: CrossAxisAlignment.start,
                                            mainAxisSize: MainAxisSize.min,
                                            children: [
                                              Text(option.text,
                                                  style: TextStyle(
                                                      color: Theme.of(context).textTheme.bodyLarge?.color)),
                                              if (option.subtitle.isNotEmpty)
                                                Text(option.subtitle,
                                                    style: TextStyle(
                                                        color: Theme.of(context)
                                                            .textTheme
                                                            .bodyMedium
                                                            ?.color
                                                            ?.withOpacity(0.6),
                                                        fontSize: 12)),
                                            ],
                                          ),
                                        ),
                                      ],
                                    ),
                                  ),
                                );
                              },
                            ),
                          ),
                        ),
                      );
                    },
                  ),
          ),
          if (_searchController.text.isNotEmpty && !widget.path.startsWith('toplist.php'))
            IconButton(
              icon: Icon(Icons.clear, color: Theme.of(context).iconTheme.color?.withOpacity(0.5), size: 20),
              onPressed: () {
                _searchController.clear();
                _refresh();
              },
            ),
          if (_isRefreshing)
            const Padding(
              padding: EdgeInsets.symmetric(horizontal: 14.0),
              child: SizedBox(
                width: 20, height: 20,
                child: CircularProgressIndicator(strokeWidth: 2, color: Color(0xFFE94560)),
              ),
            )
          else
            IconButton(
              icon: Icon(Icons.refresh, color: Theme.of(context).iconTheme.color),
              onPressed: _refresh,
            ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final content = Stack(
      children: [
        NotificationListener<UserScrollNotification>(
          onNotification: (notification) {
            FocusManager.instance.primaryFocus?.unfocus();
            return false;
          },
          child: NestedScrollView(
      headerSliverBuilder: (context, innerBoxIsScrolled) => [
        const SliverSafeArea(
          bottom: false,
          sliver: SliverAppBar(
            floating: true,
            snap: true,
            backgroundColor: Colors.transparent,
            elevation: 0,
            toolbarHeight: 84,
            automaticallyImplyLeading: false,
            title: SizedBox.shrink(),
          ),
        ),
      ],
          body: _buildCustomListBody(),
        ),
        ),
        // 顶部毛玻璃搜索栏（浮层绘制在列表之上，BackdropFilter 才能生效）
        Positioned(
          top: MediaQuery.of(context).padding.top + 8,
          left: 16,
          right: 16,
          child: _buildCustomSearchBar(context),
        ),
      ],
    );
    // 独立页面（如详情页点标签进入的搜索结果页）经 MaterialPageRoute 推入，
    // 自身没有 Scaffold，文本样式链不完整。补上 Scaffold 提供正确的
    // Material/DefaultTextStyle 祖先。嵌入到其它页面的用法保持不变。
    if (widget.standalone) {
      return Scaffold(body: content);
    }
    return content;
  }

  Widget _buildCustomListBody() {
    if (_isLoadingInitial) {
      return const Center(child: CircularProgressIndicator(color: Color(0xFFE94560)));
    }

    if (_error != null) {
      return Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            const Icon(Icons.error_outline, color: Color(0xFFE94560), size: 48),
            const SizedBox(height: 16),
            Text('请求失败\n$_error', textAlign: TextAlign.center, style: const TextStyle(color: Colors.white54)),
            const SizedBox(height: 16),
            ElevatedButton(
              onPressed: _refresh,
              style: ElevatedButton.styleFrom(backgroundColor: const Color(0xFFE94560)),
              child: const Text('重试'),
            ),
          ],
        ),
      );
    }

    if (_items.isEmpty) {
      return const Center(
        child: Text('没有找到画廊内容', style: TextStyle(color: Colors.white54, fontSize: 15)),
      );
    }

    return RefreshIndicator(
      color: const Color(0xFFE94560),
      onRefresh: () async => _refresh(),
      child: AnimationLimiter(
        child: ListView.builder(
          controller: _scrollController,
          padding: const EdgeInsets.symmetric(vertical: 8),
          itemCount: _items.length + 1,
          itemBuilder: (context, index) {
            if (index == _items.length) {
              if (_isLoadingMore) {
                return const Padding(
                  padding: EdgeInsets.symmetric(vertical: 16.0),
                  child: Center(
                    child: CircularProgressIndicator(color: Color(0xFFE94560)),
                  ),
                );
              } else if (!_hasMore) {
                return const Padding(
                  padding: EdgeInsets.symmetric(vertical: 16.0),
                  child: Center(
                    child: Text('没有更多画廊了', style: TextStyle(color: Colors.white38, fontSize: 13)),
                  ),
                );
              } else {
                return const SizedBox.shrink();
              }
            }

            return AnimationConfiguration.staggeredList(
              position: index,
              duration: const Duration(milliseconds: 375),
              child: SlideAnimation(
                verticalOffset: 50.0,
                child: FadeInAnimation(
                  child: GalleryItemWidget(item: _items[index]),
                ),
              ),
            );
          },
        ),
      ),
    );
  }
}

class HistoryView extends ConsumerStatefulWidget {
  const HistoryView({super.key});

  @override
  ConsumerState<HistoryView> createState() => _HistoryViewState();
}

class _HistoryViewState extends ConsumerState<HistoryView> {
  final TextEditingController _searchController = TextEditingController();
  final FocusNode _searchFocusNode = FocusNode();
  String _query = '';

  bool _selectionMode = false;
  final Set<String> _selected = {};

  @override
  void dispose() {
    _searchController.dispose();
    _searchFocusNode.dispose();
    super.dispose();
  }

  void _remove(String gid, String title) {
    ref.read(historyProvider.notifier).removeHistory(gid);
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text('已从历史移除: $title')),
    );
  }

  void _enterSelection(GalleryItem item) {
    setState(() {
      _selectionMode = true;
      _selected.add(item.gid);
    });
  }

  void _toggleSelected(GalleryItem item) {
    setState(() {
      if (!_selected.remove(item.gid)) {
        _selected.add(item.gid);
      }
    });
  }

  void _exitSelection() {
    setState(() {
      _selectionMode = false;
      _selected.clear();
    });
  }

  Future<void> _confirmBatchRemove() async {
    final count = _selected.length;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('批量移除历史'),
        content: Text('确定移除选中的 $count 条历史记录吗？'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('取消')),
          ElevatedButton(
            style: ElevatedButton.styleFrom(backgroundColor: const Color(0xFFE94560)),
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('移除'),
          ),
        ],
      ),
    );
    if (confirmed == true && mounted) {
      ref.read(historyProvider.notifier).removeHistories(_selected.toList());
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('已移除 $count 条历史记录')),
      );
      _exitSelection();
    }
  }

  String _formatTime(int ts) {
    if (ts <= 0) return '';
    final t = DateTime.fromMillisecondsSinceEpoch(ts);
    final now = DateTime.now();
    final today = DateTime(now.year, now.month, now.day);
    final day = DateTime(t.year, t.month, t.day);
    final diffDays = today.difference(day).inDays;
    final hm = '${t.hour.toString().padLeft(2, '0')}:${t.minute.toString().padLeft(2, '0')}';
    if (diffDays <= 0) return '今天 $hm';
    if (diffDays == 1) return '昨天 $hm';
    if (diffDays < 7) return '$diffDays 天前';
    return '${t.month}月${t.day}日';
  }

  /// 历史页顶部毛玻璃搜索栏（Stack 浮层，绘制在列表之上，BackdropFilter 才能生效）。
  Widget _buildHistorySearchBar(BuildContext context) {
    final history = ref.watch(historyProvider);
    return GlassContainer(
      height: 52,
      borderRadius: BorderRadius.circular(26),
      tintOpacity: 0.25,
      child: _selectionMode
          ? Row(
              children: [
                IconButton(
                  icon: Icon(Icons.close, color: Theme.of(context).iconTheme.color),
                  onPressed: _exitSelection,
                ),
                Expanded(
                  child: Text(
                    '已选 ${_selected.length} 项',
                    style: TextStyle(
                      color: Theme.of(context).textTheme.bodyLarge?.color,
                      fontSize: 16,
                    ),
                  ),
                ),
                if (_selected.isNotEmpty)
                  IconButton(
                    icon: const Icon(Icons.delete_outline, color: Color(0xFFE94560)),
                    onPressed: _confirmBatchRemove,
                  ),
              ],
            )
          : Row(
              children: [
                IconButton(
                  icon: Icon(Icons.menu, color: Theme.of(context).iconTheme.color),
                  onPressed: () => Scaffold.of(context).openDrawer(),
                ),
                Expanded(
                  child: TextField(
                    controller: _searchController,
                    focusNode: _searchFocusNode,
                    style: TextStyle(color: Theme.of(context).textTheme.bodyLarge?.color, fontSize: 17),
                    decoration: InputDecoration(
                      hintText: '搜索历史...',
                      hintStyle: TextStyle(color: Theme.of(context).textTheme.bodyMedium?.color, fontSize: 17),
                      border: InputBorder.none,
                      isDense: true,
                      contentPadding: const EdgeInsets.symmetric(vertical: 16),
                      prefixIcon: Icon(Icons.search, size: 20, color: Theme.of(context).iconTheme.color?.withOpacity(0.5)),
                      suffixIcon: _query.isNotEmpty
                          ? IconButton(
                              icon: Icon(Icons.clear, size: 18, color: Theme.of(context).iconTheme.color?.withOpacity(0.5)),
                              onPressed: () {
                                _searchController.clear();
                                _searchFocusNode.unfocus();
                                setState(() => _query = '');
                              },
                            )
                          : null,
                    ),
                    onChanged: (val) => setState(() => _query = val),
                  ),
                ),
                if (history.isNotEmpty)
                  IconButton(
                    icon: Icon(Icons.delete_outline, color: Theme.of(context).iconTheme.color?.withOpacity(0.6)),
                    onPressed: () {
                      showDialog(
                        context: context,
                        builder: (ctx) => AlertDialog(
                          title: const Text('清除历史记录'),
                          content: const Text('确定要清除所有本地浏览历史吗？'),
                          actions: [
                            TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('取消')),
                            ElevatedButton(
                              style: ElevatedButton.styleFrom(backgroundColor: const Color(0xFFE94560)),
                              onPressed: () {
                                ref.read(historyProvider.notifier).clearHistory();
                                Navigator.pop(ctx);
                              },
                              child: const Text('清除'),
                            )
                          ],
                        ),
                      );
                    },
                  ),
              ],
            ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final history = ref.watch(historyProvider);
    final query = _query.trim().toLowerCase();
    final filtered = query.isEmpty
        ? history
        : history
            .where((e) => e.item.title.toLowerCase().contains(query))
            .toList();

    return Stack(
      children: [
        NotificationListener<UserScrollNotification>(
          onNotification: (notification) {
            FocusManager.instance.primaryFocus?.unfocus();
            return false;
          },
          child: NestedScrollView(
      headerSliverBuilder: (context, innerBoxIsScrolled) => [
        const SliverSafeArea(
          bottom: false,
          sliver: SliverAppBar(
            floating: true,
            snap: true,
            backgroundColor: Colors.transparent,
            elevation: 0,
            toolbarHeight: 72,
            automaticallyImplyLeading: false,
            title: SizedBox.shrink(),
          ),
        ),
      ],
      body: history.isEmpty
        ? Center(
            child: Text('没有历史记录',
                style: TextStyle(color: Theme.of(context).textTheme.bodyMedium?.color?.withOpacity(0.6), fontSize: 15)),
          )
        : Column(
            children: [
              Expanded(
                child: filtered.isEmpty
                  ? Center(
                      child: Text('没有匹配的记录',
                          style: TextStyle(color: Theme.of(context).textTheme.bodyMedium?.color?.withOpacity(0.6), fontSize: 15)),
                    )
                  : ListView.builder(
                      padding: const EdgeInsets.symmetric(vertical: 8),
                      itemCount: filtered.length,
                      itemBuilder: (context, index) {
                        final entry = filtered[index];
                        final item = entry.item;
                        final isSelected = _selected.contains(item.gid);
                        return GestureDetector(
                          onTap: _selectionMode ? () => _toggleSelected(item) : null,
                          onLongPress: () => _enterSelection(item),
                          child: Dismissible(
                            key: ValueKey('history_${item.gid}'),
                            direction: _selectionMode
                                ? DismissDirection.none
                                : DismissDirection.endToStart,
                            background: Container(
                              color: const Color(0xFFE94560),
                              alignment: Alignment.centerRight,
                              padding: const EdgeInsets.only(right: 24),
                              child: const Icon(Icons.delete, color: Colors.white),
                            ),
                            onDismissed: (_) => _remove(item.gid, item.title),
                            child: Column(
                              children: [
                                Container(
                                  color: isSelected
                                      ? Theme.of(context).colorScheme.primary.withOpacity(0.08)
                                      : Theme.of(context).cardColor,
                                  child: Opacity(
                                    opacity: isSelected ? 0.6 : 1.0,
                                    child: AbsorbPointer(
                                      absorbing: _selectionMode,
                                      child: GalleryItemWidget(item: item),
                                    ),
                                  ),
                                ),
                                Container(
                                  color: Theme.of(context).cardColor,
                                  padding: const EdgeInsets.fromLTRB(16, 2, 16, 8),
                                  child: Row(
                                    children: [
                                      if (_selectionMode)
                                        Icon(
                                          isSelected ? Icons.check_circle : Icons.radio_button_unchecked,
                                          size: 18,
                                          color: isSelected
                                              ? Theme.of(context).colorScheme.primary
                                              : Theme.of(context).iconTheme.color?.withOpacity(0.4),
                                        ),
                                      if (_selectionMode) const SizedBox(width: 10),
                                      Icon(Icons.history,
                                          size: 13,
                                          color: Theme.of(context).iconTheme.color?.withOpacity(0.4)),
                                      const SizedBox(width: 4),
                                      Text(
                                        _formatTime(entry.viewedAt),
                                        style: TextStyle(
                                          fontSize: 12,
                                          color: Theme.of(context).textTheme.bodyMedium?.color?.withOpacity(0.6),
                                        ),
                                      ),
                                      const Spacer(),
                                      FutureBuilder<int?>(
                                        future: getReaderProgress(item.gid.split('/').first),
                                        builder: (context, snapshot) {
                                          if (snapshot.connectionState != ConnectionState.done ||
                                              snapshot.data == null) {
                                            return const SizedBox.shrink();
                                          }
                                          return Row(
                                            children: [
                                              Icon(Icons.menu_book_outlined,
                                                  size: 13,
                                                  color: Theme.of(context).iconTheme.color?.withOpacity(0.4)),
                                              const SizedBox(width: 4),
                                              Text(
                                                '读到第 ${snapshot.data! + 1} 页',
                                                style: TextStyle(
                                                  fontSize: 12,
                                                  color: Theme.of(context).textTheme.bodyMedium?.color?.withOpacity(0.6),
                                                ),
                                              ),
                                            ],
                                          );
                                        },
                                      ),
                                    ],
                                  ),
                                ),
                              ],
                            ),
                          ),
                        );
                      },
                    ),
              ),
            ],
          ),
        ),
        ),
        // 顶部毛玻璃搜索栏（Stack 浮层）
        Positioned(
          top: MediaQuery.of(context).padding.top + 8,
          left: 16,
          right: 16,
          child: _buildHistorySearchBar(context),
        ),
      ],
    );
  }
}
