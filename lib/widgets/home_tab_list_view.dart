import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_staggered_animations/flutter_staggered_animations.dart';
import '../src/rust/api.dart';
import '../src/rust/parser.dart';
import '../providers/settings_provider.dart';
import 'gallery_item_widget.dart';
import '../main.dart';

class HomeTabListView extends ConsumerStatefulWidget {
  final HomeTab tab;
  final String? searchQuery;
  final List<String> blockedTags;
  final int refreshCount;

  const HomeTabListView({
    super.key,
    required this.tab,
    required this.searchQuery,
    required this.blockedTags,
    required this.refreshCount,
  });

  @override
  ConsumerState<HomeTabListView> createState() => _HomeTabListViewState();
}

class _HomeTabListViewState extends ConsumerState<HomeTabListView> with AutomaticKeepAliveClientMixin {
  @override
  bool get wantKeepAlive => true;
  final List<GalleryItem> _items = [];
  int _currentPage = 0;
  String? _nextPageUrl;
  bool _isLoadingInitial = true;
  bool _isLoadingMore = false;
  bool _hasMore = true;
  String? _error;
  
  // Custom Pull-to-refresh / Quick Settings Panel
  bool _showQuickSettings = false;
  Timer? _pullTimer;
  bool _isPulling = false;
  bool _needsRefreshOnClose = false;

  SearchOptions _buildSearchOptions() {
    final searchSettings = ref.read(searchProvider);
    final activeCategories = widget.tab.activeCategories.map((e) => e.toLowerCase()).toList();
    final allCats = {
      'misc': 1, 'doujinshi': 2, 'manga': 4, 'artistcg': 8, 'gamecg': 16,
      'imageset': 32, 'cosplay': 64, 'asianporn': 128, 'non-h': 256, 'western': 512,
    };
    
    int mask = 0;
    allCats.forEach((key, val) {
      if (!activeCategories.contains(key)) {
        mask |= val;
      }
    });

    return SearchOptions(
      fSname: searchSettings.includeName,
      fStags: searchSettings.includeTags,
      fSdesc: searchSettings.includeDesc,
      fCats: mask == 1023 ? 1023 : mask, // 1023 is all filters disabled
    );
  }

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _loadInitial();
    });
  }

  @override
  void didUpdateWidget(covariant HomeTabListView oldWidget) {
    super.didUpdateWidget(oldWidget);
    
    bool needsRefresh = false;
    if (oldWidget.searchQuery != widget.searchQuery || 
        oldWidget.refreshCount != widget.refreshCount) {
      needsRefresh = true;
    }
    
    if (oldWidget.tab.activeCategories != widget.tab.activeCategories) {
      if (_showQuickSettings) {
        _needsRefreshOnClose = true;
      } else {
        needsRefresh = true;
      }
    }
    
    if (needsRefresh) {
      _loadInitial();
    }
  }

  Future<void> _loadInitial() async {
    if (!mounted) return;
    setState(() {
      _isLoadingInitial = true;
      _error = null;
      _currentPage = 0;
      _nextPageUrl = null;
      _hasMore = true;
      _items.clear();
    });

    String? actualQuery = widget.searchQuery;
    if (widget.blockedTags.isNotEmpty) {
      final blockString = widget.blockedTags.map((t) => '-$t').join(' ');
      actualQuery = actualQuery == null ? blockString : '$actualQuery $blockString';
    }

    try {
      final pageData = await fetchGalleryList(page: 0, pageUrl: null, query: actualQuery, options: _buildSearchOptions());
      if (!mounted) return;
      setState(() {
        _items.addAll(pageData.items);
        _nextPageUrl = pageData.nextUrl;
        if (pageData.nextUrl == null || pageData.items.isEmpty) _hasMore = false;
        _isLoadingInitial = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = e.toString();
        _isLoadingInitial = false;
      });
    }
  }

  Future<void> _loadMore() async {
    if (_isLoadingMore || !_hasMore || _isLoadingInitial) return;
    setState(() => _isLoadingMore = true);

    String? actualQuery = widget.searchQuery;
    if (widget.blockedTags.isNotEmpty) {
      final blockString = widget.blockedTags.map((t) => '-$t').join(' ');
      actualQuery = actualQuery == null ? blockString : '$actualQuery $blockString';
    }

    try {
      final nextPage = _currentPage + 1;
      final pageData = await fetchGalleryList(page: nextPage, pageUrl: _nextPageUrl, query: actualQuery, options: _buildSearchOptions());
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

  @override
  void dispose() {
    _pullTimer?.cancel();
    super.dispose();
  }

  bool _handleScrollNotification(ScrollNotification notification) {
    if (notification is ScrollEndNotification) {
      _pullTimer?.cancel();
      _pullTimer = null;
      _isPulling = false;
    }
    
    if (notification is ScrollUpdateNotification) {
      if (notification.metrics.pixels >= notification.metrics.maxScrollExtent - 300) {
        if (!_isLoadingMore && !_isLoadingInitial && _hasMore && _error == null) {
          _loadMore();
        }
      }

      // Custom Pull down 2s logic
      if (notification.metrics.pixels < -10) {
        if (!_isPulling) {
          _isPulling = true;
          _pullTimer = Timer(const Duration(seconds: 2), () {
            if (mounted && !_showQuickSettings) {
              setState(() {
                _showQuickSettings = true;
              });
            }
          });
        }
      } else if (notification.metrics.pixels > 0) {
        _pullTimer?.cancel();
        _pullTimer = null;
        _isPulling = false;
        
        // Hide panel if scrolling down slightly
        if (notification.metrics.pixels > 50 && _showQuickSettings) {
           setState(() {
              _showQuickSettings = false;
           });
           if (_needsRefreshOnClose) {
             _needsRefreshOnClose = false;
             _loadInitial();
           }
        }
      }
    }
    return false;
  }

  Widget _buildQuickSettingsPanel() {
    const categories = [
      ('Doujinshi', 'doujinshi', Color(0xFFEF5350)),
      ('Manga',     'manga',     Color(0xFFFF9800)),
      ('Artist CG', 'artistcg',  Color(0xFFCDDC39)),
      ('Game CG',   'gamecg',    Color(0xFF4CAF50)),
      ('Western',   'western',   Color(0xFF66BB6A)),
      ('Non-H',     'non-h',     Color(0xFF26C6DA)),
      ('Image Set', 'imageset',  Color(0xFF1565C0)),
      ('Cosplay',   'cosplay',   Color(0xFF7B1FA2)),
      ('Asian Porn','asianporn', Color(0xFFE91E63)),
      ('Misc',      'misc',      Color(0xFFF48FB1)),
    ];

    return AnimatedSize(
      duration: const Duration(milliseconds: 300),
      curve: Curves.easeInOut,
      child: _showQuickSettings
          ? Container(
              width: double.infinity,
              padding: const EdgeInsets.all(12),
              color: Theme.of(context).cardColor,
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.center,
                children: [
                  Text('${widget.tab.name} - 快速设置 (上划收起)', style: const TextStyle(fontSize: 13, fontWeight: FontWeight.bold, color: Colors.white70)),
                  const SizedBox(height: 12),
                  Wrap(
                    spacing: 8,
                    runSpacing: 8,
                    alignment: WrapAlignment.center,
                    children: categories.map((cat) {
                      final isActive = widget.tab.activeCategories.contains(cat.$2);
                      return GestureDetector(
                        onTap: () {
                          ref.read(homeTabsProvider.notifier).toggleCategory(widget.tab.id, cat.$2);
                        },
                        child: AnimatedContainer(
                          duration: const Duration(milliseconds: 200),
                          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                          decoration: BoxDecoration(
                            color: isActive ? cat.$3 : Colors.grey[800],
                            borderRadius: BorderRadius.circular(6),
                            border: Border.all(
                              color: isActive ? Colors.transparent : Colors.white24,
                            ),
                          ),
                          child: Text(
                            cat.$1,
                            style: TextStyle(
                              color: isActive ? Colors.white : Colors.white54,
                              fontSize: 13,
                              fontWeight: FontWeight.w600,
                            ),
                          ),
                        ),
                      );
                    }).toList(),
                  ),
                  const SizedBox(height: 16),
                  GestureDetector(
                    onTap: () {
                      setState(() => _showQuickSettings = false);
                      if (_needsRefreshOnClose) {
                        _needsRefreshOnClose = false;
                        _loadInitial();
                      }
                    },
                    child: const Icon(Icons.keyboard_arrow_up, color: Colors.white38, size: 28),
                  )
                ],
              ),
            )
          : const SizedBox.shrink(),
    );
  }

  @override
  Widget build(BuildContext context) {
    super.build(context);
    if (_isLoadingInitial) {
      return const Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            CircularProgressIndicator(color: Color(0xFFE94560)),
            SizedBox(height: 16),
            Text('正在请求 E-Hentai...', style: TextStyle(color: Colors.white54)),
          ],
        ),
      );
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
              onPressed: _loadInitial,
              style: ElevatedButton.styleFrom(backgroundColor: const Color(0xFFE94560)),
              child: const Text('重试'),
            ),
          ],
        ),
      );
    }

    if (_items.length == 1 && _items[0].gid == '0') {
      return const Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(Icons.lock_outline, color: Colors.white38, size: 64),
            SizedBox(height: 16),
            Text('需要登录才能浏览内容', style: TextStyle(color: Colors.white70, fontSize: 16, fontWeight: FontWeight.w500)),
            SizedBox(height: 8),
            Text('请点击左上角菜单 → 设置/登录', style: TextStyle(color: Colors.white38, fontSize: 13)),
          ],
        ),
      );
    }

    if (_items.isEmpty) {
      return RefreshIndicator(
        color: const Color(0xFFE94560),
        onRefresh: () async => _loadInitial(),
        child: CustomScrollView(
          physics: const BouncingScrollPhysics(parent: AlwaysScrollableScrollPhysics()),
          slivers: [
            SliverToBoxAdapter(child: _buildQuickSettingsPanel()),
            const SliverFillRemaining(
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Icon(Icons.filter_list_off, color: Colors.white38, size: 48),
                  SizedBox(height: 12),
                  Text('当前没有匹配的画廊', style: TextStyle(color: Colors.white54, fontSize: 15)),
                  SizedBox(height: 8),
                  Text('提示: 下拉刷新，或滑动切换其它瀑布流/更换搜索词', style: TextStyle(color: Colors.white38, fontSize: 12)),
                ],
              ),
            ),
          ],
        ),
      );
    }

    return NotificationListener<ScrollNotification>(
      onNotification: _handleScrollNotification,
      child: RefreshIndicator(
        color: const Color(0xFFE94560),
        onRefresh: () async {
          if (_showQuickSettings) return;
          await _loadInitial();
        },
        child: Column(
          children: [
            _buildQuickSettingsPanel(),
            Expanded(
              child: AnimationLimiter(
                child: ListView.builder(
                  physics: const BouncingScrollPhysics(parent: AlwaysScrollableScrollPhysics()),
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
            ),
          ],
        ),
      ),
    );
  }
}
