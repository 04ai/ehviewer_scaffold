import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../providers/settings_provider.dart';

import '../providers/history_provider.dart';
import '../providers/favorites_provider.dart';
import 'package:cached_network_image/cached_network_image.dart';
import '../src/rust/api.dart';
import '../src/rust/parser.dart';
import '../src/rust/downloader.dart';
import 'gallery_viewer_page.dart';
import 'gallery_comments_page.dart';
import 'offline_viewer_page.dart';
import 'thumbnails_page.dart';
import '../main.dart';
import '../utils/haptics.dart';
import '../utils/reader_progress.dart';
import '../utils/gallery_detail_cache.dart';

class GalleryDetailPage extends ConsumerStatefulWidget {
  final GalleryItem item;

  const GalleryDetailPage({super.key, required this.item});

  @override
  ConsumerState<GalleryDetailPage> createState() => _GalleryDetailPageState();
}

class _GalleryDetailPageState extends ConsumerState<GalleryDetailPage> {
  GalleryDetail? _detail;
  bool _isLoading = true;
  String? _error;
  bool _isFavorited = false;
  DownloadTask? _downloadTask;
  int? _readerProgress;
  bool _isRefreshing = false;
  bool _forceRefresh = false;

  String get _detailId => '${widget.item.gid}/${widget.item.token}/';

  @override
  void initState() {
    super.initState();
    // Render instantly from cache when reopening a gallery we already viewed
    // (no network, no spinner), then refresh in the background.
    final cached = peekGalleryDetailCached(_detailId);
    if (cached != null) {
      _detail = cached;
      _isLoading = false;
      _isFavorited = cached.isFavorited;
    }
    _loadDetail();
    _checkFavoritedStatus();
    _refreshDownloadTask();
    _loadReaderProgress();

    // Add to history
    WidgetsBinding.instance.addPostFrameCallback((_) {
      ref.read(historyProvider.notifier).addHistory(widget.item);
    });
  }

  String get _gid => widget.item.gid.split('/').first;

  Future<void> _loadReaderProgress() async {
    final p = await getReaderProgress(_gid);
    if (!mounted) return;
    setState(() => _readerProgress = p);
  }

  Future<void> _refreshDownloadTask() async {
    try {
      final gid = widget.item.gid.split('/').first;
      final tasks = await getDownloadTasks();
      if (!mounted) return;
      final matched = tasks.where((t) => t.gid == gid).toList();
      setState(() => _downloadTask = matched.isEmpty ? null : matched.first);
    } catch (_) {
      // ignore: the download button simply keeps its default state
    }
  }

  Future<void> _refreshDetail() async {
    if (_isRefreshing) return;
    setState(() => _isRefreshing = true);
    _forceRefresh = true;
    await _loadDetail();
    if (mounted) setState(() => _isRefreshing = false);
  }

  Future<void> _checkFavoritedStatus() async {
    final prefs = await SharedPreferences.getInstance();
    final favList = prefs.getStringList('favorited_gids') ?? [];
    if (favList.contains(widget.item.gid)) {
      if (mounted) setState(() => _isFavorited = true);
    }
  }

  Future<void> _loadDetail() async {
    final force = _forceRefresh;
    _forceRefresh = false;
    try {
      final detail = await fetchGalleryDetailCached(id: _detailId, force: force);
      if (mounted) {
        setState(() {
          _detail = detail;
          _isLoading = false;
          _isFavorited = detail.isFavorited;
        });

        // Sync with local SharedPreferences
        final prefs = await SharedPreferences.getInstance();
        final favList = prefs.getStringList('favorited_gids') ?? [];
        if (detail.isFavorited && !favList.contains(widget.item.gid)) {
          favList.add(widget.item.gid);
          await prefs.setStringList('favorited_gids', favList);
        } else if (!detail.isFavorited && favList.contains(widget.item.gid)) {
          favList.remove(widget.item.gid);
          await prefs.setStringList('favorited_gids', favList);
        }
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          _error = e.toString();
          _isLoading = false;
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Theme.of(context).scaffoldBackgroundColor,
      body: Consumer(
        builder: (context, ref, _) {
          final appearance = ref.watch(appearanceProvider);
          final showJpn = appearance.showJpnTitle;
          final displayTitle = (showJpn && _detail != null && _detail!.titleJpn.isNotEmpty) 
              ? _detail!.titleJpn 
              : widget.item.title;

          return RefreshIndicator(
            onRefresh: _refreshDetail,
            color: const Color(0xFFE94560),
            child: CustomScrollView(
            physics: const AlwaysScrollableScrollPhysics(),
            slivers: [
              // App Bar with blurred background image
              SliverAppBar(
                actions: [
                  _isRefreshing
                      ? const Padding(
                          padding: EdgeInsets.only(right: 16),
                          child: Center(
                            child: SizedBox(
                              width: 18,
                              height: 18,
                              child: CircularProgressIndicator(strokeWidth: 2),
                            ),
                          ),
                        )
                      : IconButton(
                          icon: const Icon(Icons.refresh),
                          tooltip: '刷新',
                          onPressed: _refreshDetail,
                        ),
                ],
                expandedHeight: 220.0,
                pinned: true,
                flexibleSpace: FlexibleSpaceBar(
                  title: Text(
                    displayTitle,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(fontSize: 14, shadows: [
                      Shadow(color: Colors.black54, blurRadius: 4, offset: Offset(0, 1))
                    ]),
                  ),
                  background: Stack(
                    fit: StackFit.expand,
                    children: [
                      Hero(
                        tag: 'gallery_thumb_${widget.item.gid}',
                        child: CachedNetworkImage(
                          imageUrl: _detail?.coverUrl.isNotEmpty == true 
                              ? _detail!.coverUrl 
                              : widget.item.thumbUrl,
                          fit: BoxFit.cover,
                          errorWidget: (context, url, error) => const Center(child: Icon(Icons.error)),
                        ),
                      ),
                      const DecoratedBox(
                        decoration: BoxDecoration(
                          gradient: LinearGradient(
                            begin: Alignment.topCenter,
                            end: Alignment.bottomCenter,
                            colors: [Colors.black54, Colors.transparent, Colors.black87],
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              ),
              
              if (_isLoading)
                const SliverFillRemaining(
                  child: Center(child: CircularProgressIndicator()),
                )
              else if (_error != null)
                SliverFillRemaining(
                  child: Center(child: Text("加载失败: $_error\n尝试下拉刷新")),
                )
              else if (_detail != null) ...[
                // Top metadata: main line + favorite count on its own row
                //   Japanese  87P  410m  2003.11.21
                //   ♥46
                SliverToBoxAdapter(
                  child: Padding(
                    padding: const EdgeInsets.fromLTRB(16, 14, 16, 6),
                    child: Column(
                      children: [
                        Wrap(
                          alignment: WrapAlignment.center,
                          crossAxisAlignment: WrapCrossAlignment.center,
                          spacing: 12,
                          runSpacing: 4,
                          children: _buildCompactMeta(),
                        ),
                        const SizedBox(height: 6),
                        Row(
                          mainAxisSize: MainAxisSize.min,
                          mainAxisAlignment: MainAxisAlignment.center,
                          children: [
                            const Icon(Icons.favorite, size: 15, color: Colors.redAccent),
                            const SizedBox(width: 4),
                            Text(
                              _detail!.favoritesCount,
                              style: TextStyle(
                                fontSize: 14,
                                fontWeight: FontWeight.bold,
                                color: Theme.of(context).colorScheme.onSurface.withOpacity(0.85),
                              ),
                            ),
                          ],
                        ),
                      ],
                    ),
                  ),
                ),
                
                SliverToBoxAdapter(
                  child: Center(
                    child: TextButton(
                      onPressed: () {
                        showDialog(
                          context: context,
                          builder: (context) => AlertDialog(
                            title: const Text("画廊信息"),
                            content: SingleChildScrollView(
                              child: Table(
                                columnWidths: const {
                                  0: FlexColumnWidth(1),
                                  1: FlexColumnWidth(3),
                                },
                                children: [
                                  _buildInfoRow("Gid", widget.item.gid),
                                  _buildInfoRow("Token", widget.item.token),
                                  _buildInfoRow("链接", "https://e-hentai.org/g/${widget.item.gid}/${widget.item.token}/"),
                                  _buildInfoRow("标题", _detail!.title),
                                  _buildInfoRow("日文标题", _detail!.titleJpn),
                                  _buildInfoRow("分类", widget.item.category),
                                  _buildInfoRow("上传者", _detail!.uploader),
                                  _buildInfoRow("上传时间", _detail!.postDate),
                                  _buildInfoRow("语言", _detail!.language),
                                  _buildInfoRow("页数", "${_detail!.totalPages}"),
                                  _buildInfoRow("大小", _detail!.fileSize),
                                  _buildInfoRow("收藏次数", _detail!.favoritesCount),
                                  _buildInfoRow("评分", _detail!.rating),
                                  _buildInfoRow("种子个数", _detail!.torrentCount),
                                ],
                              ),
                            ),
                            actions: [
                              TextButton(onPressed: () => Navigator.pop(context), child: const Text("关闭"))
                            ],
                          ),
                        );
                      },
                      child: Text("查看更多信息", style: TextStyle(color: Theme.of(context).colorScheme.primary)),
                    ),
                  ),
                ),
                
                const SliverToBoxAdapter(child: Divider()),

                // Action Buttons
                SliverToBoxAdapter(
                  child: Padding(
                    padding: const EdgeInsets.symmetric(vertical: 8.0, horizontal: 16.0),
                    child: SingleChildScrollView(
                      scrollDirection: Axis.horizontal,
                      child: Row(
                        mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                        children: [
                          _buildActionBtn(
                            _isFavorited ? Icons.favorite : Icons.favorite_border,
                            _isFavorited ? "已收藏" : "收藏",
                            _isFavorited ? Colors.pinkAccent : Colors.teal,
                            onTap: () async {
                              final isLocalFav = ref.read(favoritesProvider.notifier).isFavorite(widget.item.gid);
                              final favcat = await showDialog<String>(
                                context: context,
                                builder: (context) => SimpleDialog(
                                  title: const Text('添加到收藏夹'),
                                  children: [
                                    SimpleDialogOption(
                                      onPressed: () => Navigator.pop(context, isLocalFav ? 'local_remove' : 'local'),
                                      child: Padding(
                                        padding: const EdgeInsets.all(8.0),
                                        child: Row(
                                          children: [
                                            Icon(isLocalFav ? Icons.star : Icons.star_border,
                                                color: isLocalFav ? Colors.amber : Colors.grey),
                                            const SizedBox(width: 12),
                                            Text(isLocalFav ? '从本地收藏移除' : '收藏到本地',
                                                style: TextStyle(color: isLocalFav ? Colors.redAccent : null)),
                                          ],
                                        ),
                                      ),
                                    ),
                                    const Divider(height: 1),
                                    ...List.generate(10, (i) => SimpleDialogOption(
                                      onPressed: () => Navigator.pop(context, '$i'),
                                      child: Padding(
                                        padding: const EdgeInsets.all(8.0),
                                        child: Text('云收藏夹 $i'),
                                      ),
                                    )),
                                    if (_isFavorited)
                                      SimpleDialogOption(
                                        onPressed: () => Navigator.pop(context, 'favdel'),
                                        child: const Padding(
                                          padding: EdgeInsets.all(8.0),
                                          child: Text('取消云端收藏', style: TextStyle(color: Colors.redAccent)),
                                        ),
                                      ),
                                  ],
                                ),
                              );
                              if (favcat == null) return;
                              if (favcat == 'local' || favcat == 'local_remove') {
                                if (favcat == 'local') {
                                  ref.read(favoritesProvider.notifier).addFavorite(widget.item);
                                } else {
                                  ref.read(favoritesProvider.notifier).removeFavorite(widget.item.gid);
                                }
                                if (!context.mounted) return;
                                ScaffoldMessenger.of(context).showSnackBar(SnackBar(
                                    content: Text(favcat == 'local' ? '已收藏到本地' : '已从本地收藏移除')));
                                return;
                              }
                              try {
                                final gidStr = widget.item.gid.split('/')[0];
                                await addFavorite(gid: gidStr, token: widget.item.token, favcat: favcat, favnote: '');
                                if (!mounted) return;

                                final prefs = await SharedPreferences.getInstance();
                                final favList = prefs.getStringList('favorited_gids') ?? [];
                                if (favcat == 'favdel') {
                                  favList.remove(widget.item.gid);
                                  await prefs.setStringList('favorited_gids', favList);
                                  if (!context.mounted) return;
                                  setState(() => _isFavorited = false);
                                  ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('已从云端收藏夹移除')));
                                } else {
                                  if (!favList.contains(widget.item.gid)) {
                                    favList.add(widget.item.gid);
                                    await prefs.setStringList('favorited_gids', favList);
                                  }
                                  if (!context.mounted) return;
                                  setState(() => _isFavorited = true);
                                  ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('已同步至云端收藏夹')));
                                }
                              } catch (e) {
                                if (!context.mounted) return;
                                ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('操作失败: $e')));
                              }
                            },
                              ),
                          _buildActionBtn(Icons.thumb_up, "评分", Colors.teal, onTap: () => _showRateDialog()),
                          _buildActionBtn(
                            _downloadTask == null
                                ? Icons.download
                                : _downloadTask!.status == 1
                                    ? Icons.downloading
                                    : _downloadTask!.status == 2
                                        ? Icons.check_circle
                                        : Icons.play_circle_outline,
                            _downloadTask == null
                                ? "下载"
                                : _downloadTask!.status == 1
                                    ? "下载中"
                                    : _downloadTask!.status == 2
                                        ? "已下载"
                                        : "继续下载",
                            _downloadTask?.status == 2 ? Colors.green : Colors.blue,
                            onTap: () => _handleDownloadTap(),
                          ),
                          _buildActionBtn(Icons.wb_twilight, "种子 (${_detail!.torrentCount})", Colors.teal, onTap: () => _showTorrents()),
                          _buildActionBtn(Icons.archive, "档案", Colors.teal, onTap: () {
                            ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('档案下载需消耗GP，请在网页端操作')));
                          }),
                          _buildActionBtn(Icons.reply, "分享", Colors.teal, onTap: () {
                            final url = "https://e-hentai.org/g/${widget.item.gid.split('/')[0]}/${widget.item.token}/";
                            ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('分享链接: $url')));
                          }),
                        ],
                      ),
                    ),
                  ),
                ),
                
                const SliverToBoxAdapter(child: Divider()),

                // Tags
                SliverToBoxAdapter(
                  child: Padding(
                    padding: const EdgeInsets.all(16.0),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: _detail!.tagGroups.map((group) {
                        return Padding(
                          padding: const EdgeInsets.only(bottom: 8.0),
                          child: Row(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Container(
                                width: 70,
                                padding: const EdgeInsets.symmetric(vertical: 4),
                                decoration: BoxDecoration(
                                  color: Theme.of(context).colorScheme.primary,
                                  borderRadius: BorderRadius.circular(12),
                                ),
                                child: Text(
                                  ref.watch(appearanceProvider).showTagTranslation
                                      ? translateTagSync(namespace: 'rows', tag: group.groupName)
                                      : group.groupName,
                                  style: const TextStyle(fontWeight: FontWeight.bold, color: Colors.white, fontSize: 12),
                                  textAlign: TextAlign.center,
                                ),
                              ),
                              const SizedBox(width: 8),
                              Expanded(
                                child: Wrap(
                                  spacing: 6,
                                  runSpacing: 6,
                                  children: group.tags.map((t) {
                                    final String fullTag = '${group.groupName}:$t';
                                    return GestureDetector(
                                      onTap: () {
                                        Haptics.tap();
                                        Navigator.push(
                                          context,
                                          MaterialPageRoute(
                                            builder: (_) => CustomListView(
                                              path: '',
                                              title: '搜索: $fullTag',
                                              initialQuery: fullTag,
                                              standalone: true,
                                            ),
                                          ),
                                        );
                                      },
                                      onLongPress: () {
                                        Haptics.longPress();
                                        showModalBottomSheet(
                                          context: context,
                                          backgroundColor: Theme.of(context).colorScheme.surfaceContainer,
                                          builder: (ctx) => SafeArea(
                                            child: Column(
                                              mainAxisSize: MainAxisSize.min,
                                              children: [
                                                Padding(
                                                  padding: const EdgeInsets.fromLTRB(20, 14, 20, 4),
                                                  child: Text(
                                                    fullTag,
                                                    maxLines: 2,
                                                    overflow: TextOverflow.ellipsis,
                                                    style: TextStyle(
                                                      color: Theme.of(context).textTheme.bodyLarge?.color,
                                                      fontSize: 14,
                                                    ),
                                                  ),
                                                ),
                                                ListTile(
                                                  leading: Icon(Icons.star_outline, color: Theme.of(context).colorScheme.primary),
                                                  title: const Text('关注此标签'),
                                                  subtitle: const Text('匹配该标签的画廊将显示在「订阅」中（需登录）'),
                                                  onTap: () async {
                                                    final messenger = ScaffoldMessenger.of(context);
                                                    Navigator.pop(ctx);
                                                    try {
                                                      final msg = await addWatchedTag(tag: fullTag);
                                                      if (!mounted) return;
                                                      messenger.showSnackBar(SnackBar(content: Text(msg)));
                                                    } catch (e) {
                                                      if (!mounted) return;
                                                      messenger.showSnackBar(SnackBar(content: Text('关注失败: $e')));
                                                    }
                                                  },
                                                ),
                                                ListTile(
                                                  leading: const Icon(Icons.block, color: Colors.redAccent),
                                                  title: const Text('屏蔽此标签'),
                                                  subtitle: const Text('在全屏和搜索中隐藏该标签的画廊'),
                                                  onTap: () {
                                                    Navigator.pop(ctx);
                                                    ref.read(searchProvider.notifier).addBlockedTag(fullTag);
                                                    ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('已添加至本地屏蔽列表')));
                                                  },
                                                ),
                                                const SizedBox(height: 8),
                                              ],
                                            ),
                                          ),
                                        );
                                      },
                                      child: Container(
                                        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
                                        decoration: BoxDecoration(
                                          color: Colors.teal.shade400,
                                          borderRadius: BorderRadius.circular(14),
                                        ),
                                        child: Text(
                                          ref.watch(appearanceProvider).showTagTranslation
                                              ? translateTagSync(namespace: group.groupName, tag: t)
                                              : t,
                                          style: const TextStyle(fontSize: 13, height: 1.2, color: Colors.white, fontWeight: FontWeight.w500)
                                        ),
                                      ),
                                    );
                                  }).toList(),
                                ),
                              ),
                            ],
                          ),
                        );
                      }).toList(),
                    ),
                  ),
                ),
                
                const SliverToBoxAdapter(child: Divider()),
                
                // Comments Preview
                if (_detail!.comments.isNotEmpty)
                  SliverToBoxAdapter(
                    child: Padding(
                      padding: const EdgeInsets.all(16.0),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          ..._detail!.comments.take(2).map((c) => Padding(
                            padding: const EdgeInsets.only(bottom: 16.0),
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Row(
                                  children: [
                                    Text(c.author, style: TextStyle(fontWeight: FontWeight.bold, color: Theme.of(context).colorScheme.onSurface.withOpacity(0.6))),
                                    const Spacer(),
                                    Text(c.time, style: TextStyle(color: Theme.of(context).colorScheme.onSurface.withOpacity(0.4), fontSize: 12)),
                                  ],
                                ),
                                const SizedBox(height: 4),
                                Text(c.content, maxLines: 3, overflow: TextOverflow.ellipsis),
                              ],
                            ),
                          )),
                          Center(
                            child: TextButton(
                              onPressed: () {
                                Navigator.push(
                                  context,
                                  MaterialPageRoute(
                                    builder: (_) => GalleryCommentsPage(
                                      detail: _detail!,
                                      gid: widget.item.gid.split('/').first,
                                      token: widget.item.token,
                                    ),
                                  ),
                                );
                              },
                              child: Text("查看更多评论", style: TextStyle(color: Theme.of(context).colorScheme.primary)),
                            ),
                          ),
                        ],
                      ),
                    ),
                  )
                else
                  const SliverToBoxAdapter(
                    child: Padding(
                      padding: EdgeInsets.all(16.0),
                      child: Center(child: Text("暂无评论", style: TextStyle(color: Colors.grey))),
                    ),
                  ),

                SliverToBoxAdapter(
                  child: Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 16.0, vertical: 8.0),
                    child: Row(
                      children: [
                        Expanded(
                          child: TextField(
                            decoration: InputDecoration(
                              hintText: '添加评论...',
                              border: OutlineInputBorder(borderRadius: BorderRadius.circular(20)),
                              contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
                            ),
                            onSubmitted: (val) async {
                              if (val.trim().isEmpty) return;
                              try {
                                final gidStr = widget.item.gid.split('/')[0];
                                await postComment(gid: gidStr, token: widget.item.token, content: val);
                                if (!context.mounted) return;
                                ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('评论发布成功')));
                              } catch (e) {
                                if (!context.mounted) return;
                                ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('评论失败: $e')));
                              }
                            },
                          ),
                        ),
                      ],
                    ),
                  ),
                ),

                const SliverToBoxAdapter(child: Divider()),
                
                // Thumbnails
                SliverPadding(
                  padding: const EdgeInsets.all(12.0),
                  sliver: SliverGrid(
                    gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
                      crossAxisCount: 3,
                      mainAxisSpacing: 8.0,
                      crossAxisSpacing: 8.0,
                      childAspectRatio: 0.7,
                    ),
                    delegate: SliverChildBuilderDelegate(
                      (context, index) {
                        final isThumbAvailable = index < _detail!.thumbnails.length;
                        
                        return InkWell(
                          onTap: () async {
                            await Navigator.push(
                              context,
                              MaterialPageRoute(
                                builder: (_) => GalleryViewerPage(detail: _detail!, initialPage: index),
                              ),
                            );
                            await _loadReaderProgress();
                          },
                          child: Column(
                            children: [
                              Expanded(
                                child: Container(
                                  decoration: BoxDecoration(
                                    borderRadius: BorderRadius.circular(4),
                                    border: Border.all(color: Colors.grey.shade300),
                                  ),
                                  clipBehavior: Clip.antiAlias,
                                  child: isThumbAvailable
                                      ? _buildThumbnailSprite(_detail!.thumbnails[index], index)
                                      : Container(
                                          color: Colors.black12,
                                          child: Center(
                                            child: Text("${index + 1}", style: const TextStyle(color: Colors.black54, fontSize: 18)),
                                          ),
                                        ),
                                ),
                              ),
                              const SizedBox(height: 4),
                              Text("${index + 1}", style: const TextStyle(color: Colors.grey, fontSize: 14)),
                            ],
                          ),
                        );
                      },
                      childCount: _detail!.thumbnails.length > 20 ? 20 : _detail!.thumbnails.length,
                    ),
                  ),
                ),
                
                // View More Thumbnails Button
                if (_detail!.totalPages > (_detail!.thumbnails.length > 20 ? 20 : _detail!.thumbnails.length))
                  SliverToBoxAdapter(
                    child: Padding(
                      padding: const EdgeInsets.only(bottom: 24.0, top: 8.0),
                      child: Center(
                        child: OutlinedButton(
                          onPressed: () {
                            Navigator.push(
                              context,
                              MaterialPageRoute(
                                builder: (_) => ThumbnailsPage(item: widget.item, initialDetail: _detail!),
                              ),
                            );
                          },
                          style: OutlinedButton.styleFrom(
                            side: BorderSide(color: Theme.of(context).colorScheme.primary),
                            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
                          ),
                          child: Text("查看更多缩略图", style: TextStyle(color: Theme.of(context).colorScheme.primary)),
                        ),
                      ),
                    ),
                  ),
                  
                const SliverToBoxAdapter(child: SizedBox(height: 100)), // Bottom padding for FAB
              ]
            ],
            ),
          );
        },
      ),
      floatingActionButton: _detail != null 
          ? FloatingActionButton.extended(
              onPressed: _startReading,
              icon: const Icon(Icons.menu_book),
              label: const Text('开始阅读'),
            )
          : null,
    );
  }

  Future<void> _startReading() async {
    final gid = widget.item.gid.split('/').first;
    final saved = await getReaderProgress(gid);
    if (!mounted) return;
    if (saved == null || saved <= 0 || saved >= _detail!.totalPages) {
      _openViewer(0);
      return;
    }

    final choice = await showDialog<int>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('继续阅读'),
        content: Text('上次读到第 ${saved + 1} 页，是否继续？'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, 0), child: const Text('从头开始')),
          TextButton(onPressed: () => Navigator.pop(context, 1), child: const Text('继续')),
          TextButton(onPressed: () => Navigator.pop(context, 2), child: const Text('取消')),
        ],
      ),
    );
    if (!mounted || choice == null || choice == 2) return;

    if (choice == 1) {
      await _openViewer(saved);
    } else {
      await clearReaderProgress(gid);
      if (!mounted) return;
      await _openViewer(0);
    }
  }

  Future<void> _openViewer(int page) async {
    await Navigator.push(
      context,
      MaterialPageRoute(
        builder: (_) => GalleryViewerPage(detail: _detail!, initialPage: page),
      ),
    );
    // Reader may have saved new progress while open; refresh the label.
    await _loadReaderProgress();
  }

  Future<void> _handleDownloadTap() async {
    final gid = widget.item.gid.split('/').first;
    final task = _downloadTask;

    // Already fully downloaded: open the offline reader directly.
    if (task != null && task.status == 2) {
      await Navigator.push(
        context,
        MaterialPageRoute(
          builder: (_) => OfflineViewerPage(gid: task.gid, title: task.title),
        ),
      );
      await _loadReaderProgress();
      return;
    }

    try {
      // Resuming tasks pass an empty URL list; the Rust downloader uses the
      // URLs persisted with the stored task.
      await startDownload(
        gid: gid,
        token: widget.item.token,
        title: widget.item.title,
        imageUrls: task == null ? _detail!.imageUrls : const [],
        totalPages: task == null ? _detail!.totalPages : task.totalPages,
      );
      Haptics.confirm();
      await _refreshDownloadTask();
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(task == null ? '已加入下载队列: ${widget.item.title}' : '已开始续传: ${widget.item.title}')),
        );
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('操作失败: $e')));
      }
    }
  }

  Future<void> _showTorrents() async {
    Haptics.tap();
    await showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (_) => _TorrentSheet(gid: widget.item.gid.split('/').first, token: widget.item.token),
    );
  }

  /// Star-rating dialog: 1..5 stars, submitted via the web vote form.
  Future<void> _showRateDialog() async {
    Haptics.tap();
    var rating = 0;
    final submitted = await showDialog<int>(
      context: context,
      builder: (dialogContext) => StatefulBuilder(
        builder: (dialogContext, setDialogState) => AlertDialog(
          title: const Text('给这个画廊评分'),
          content: Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: List.generate(5, (i) {
              final star = i + 1;
              return IconButton(
                icon: Icon(
                  star <= rating ? Icons.star : Icons.star_border,
                  color: Colors.amber,
                  size: 36,
                ),
                onPressed: () => setDialogState(() => rating = star),
              );
            }),
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(dialogContext), child: const Text('取消')),
            ElevatedButton(
              onPressed: rating == 0
                  ? null
                  : () => Navigator.pop(dialogContext, rating),
              child: const Text('提交'),
            ),
          ],
        ),
      ),
    );
    if (submitted == null || !mounted) return;

    try {
      final gidStr = widget.item.gid.split('/').first;
      await voteGallery(gid: gidStr, token: widget.item.token, rating: submitted);
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('已提交 $submitted 星评分')));
      _refreshDetail();
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('评分失败: $e')));
    }
  }

  /// "87P", or "3/87P" while reading, or "87/87" once finished.
  String _pageLabel() {
    final total = _detail!.totalPages;
    final p = _readerProgress;
    if (p == null || p <= 0) {
      return '${total}P';
    }
    final page = p + 1; // progress is 0-indexed
    if (page >= total) {
      return '$total/$total'; // finished the last page
    }
    return '$page/$total' 'P';
  }

  /// Compact one-line metadata (no favorites): "Japanese  87P  410m  2003.11.21"
  List<Widget> _buildCompactMeta() {
    final colorScheme = Theme.of(context).colorScheme;
    final style = TextStyle(fontSize: 13.5, color: colorScheme.onSurface.withOpacity(0.8));
    return [
      Text(_detail!.language.isEmpty ? "Unknown" : _detail!.language, style: style),
      Text(_pageLabel(), style: style),
      Text(_compactSize(_detail!.fileSize), style: style),
      Text(_detail!.postDate, style: style),
    ];
  }

  /// "410.32 MiB" -> "410.32m", "1.20 GiB" -> "1.20g"
  String _compactSize(String size) {
    return size
        .replaceAll(' MiB', 'm')
        .replaceAll(' GiB', 'g')
        .replaceAll(' KiB', 'k');
  }

  Widget _buildActionBtn(IconData icon, String label, Color color, {VoidCallback? onTap}) {
    final onSurface = Theme.of(context).colorScheme.onSurface;
    return InkWell(
      onTap: onTap ?? () {},
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 12.0),
        child: Column(
          children: [
            Icon(icon, color: color, size: 32),
            const SizedBox(height: 4),
            Text(label, style: TextStyle(fontSize: 12, color: onSurface)),
          ],
        ),
      ),
    );
  }

  Widget _buildThumbnailSprite(GalleryThumbnail thumb, int index) {
    if (thumb.url.isEmpty) {
      return Center(child: Text("${index + 1}", style: const TextStyle(color: Colors.black38)));
    }
    
    return FittedBox(
      fit: BoxFit.contain,
      child: ClipRect(
        child: SizedBox(
          width: thumb.width.toDouble(),
          height: thumb.height.toDouble(),
          child: Stack(
            children: [
              Positioned(
                left: -thumb.offsetX.toDouble(),
                top: -thumb.offsetY.toDouble(),
                child: CachedNetworkImage(
                  imageUrl: thumb.url,
                  fit: BoxFit.none,
                  errorWidget: (context, url, error) => Container(
                    width: thumb.width.toDouble(),
                    height: thumb.height.toDouble(),
                    color: Colors.black12,
                    child: Center(child: Text("${index + 1}", style: const TextStyle(color: Colors.black38))),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  TableRow _buildInfoRow(String key, String value) {
    return TableRow(
      children: [
        Padding(
          padding: const EdgeInsets.symmetric(vertical: 8.0),
          child: Text(key, style: const TextStyle(color: Colors.grey, fontWeight: FontWeight.bold)),
        ),
        Padding(
          padding: const EdgeInsets.symmetric(vertical: 8.0),
          child: Text(value),
        ),
      ],
    );
  }
}

/// Bottom sheet listing a gallery's torrents (from /gallerytorrents.php).
/// Tap to download the .torrent file, long-press to copy the magnet link.
class _TorrentSheet extends ConsumerStatefulWidget {
  final String gid;
  final String token;

  const _TorrentSheet({required this.gid, required this.token});

  @override
  ConsumerState<_TorrentSheet> createState() => _TorrentSheetState();
}

class _TorrentSheetState extends ConsumerState<_TorrentSheet> {
  List<TorrentItem>? _torrents;
  String? _error;
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final list = await fetchTorrents(gid: widget.gid, token: widget.token);
      if (!mounted) return;
      setState(() {
        _torrents = list;
        _loading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = e.toString();
        _loading = false;
      });
    }
  }

  Future<void> _download(TorrentItem item) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('下载种子'),
        content: Text('将把「${item.name}」种子文件保存到本机，确定吗？'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('取消')),
          ElevatedButton(
            style: ElevatedButton.styleFrom(backgroundColor: Theme.of(ctx).colorScheme.primary),
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('下载'),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;
    try {
      final path = await downloadTorrent(name: item.name, hash: item.hash, token: item.token);
      if (!mounted) return;
      Haptics.success();
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('种子已保存: $path')));
    } catch (e) {
      if (!mounted) return;
      Haptics.error();
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('种子下载失败: $e')));
    }
  }

  void _copyMagnet(TorrentItem item) {
    Clipboard.setData(ClipboardData(text: 'magnet:?xt=urn:btih:${item.hash}'));
    Haptics.tap();
    ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('磁力链接已复制')));
  }

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: SizedBox(
        height: MediaQuery.of(context).size.height * 0.7,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 8, 20, 8),
              child: Text(
                '种子下载',
                style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold, color: Theme.of(context).colorScheme.onSurface),
              ),
            ),
            Expanded(child: _buildBody()),
          ],
        ),
      ),
    );
  }

  Widget _buildBody() {
    if (_loading) {
      return const Center(child: CircularProgressIndicator());
    }
    if (_error != null) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Icon(Icons.cloud_off, size: 40, color: Colors.grey),
              const SizedBox(height: 12),
              Text('加载失败: $_error', style: const TextStyle(color: Colors.grey), textAlign: TextAlign.center),
              const SizedBox(height: 12),
              OutlinedButton(
                onPressed: () {
                  setState(() {
                    _loading = true;
                    _error = null;
                  });
                  _load();
                },
                child: const Text('重试'),
              ),
            ],
          ),
        ),
      );
    }
    final list = _torrents ?? [];
    if (list.isEmpty) {
      return const Center(child: Text('该画廊暂无种子', style: TextStyle(color: Colors.grey)));
    }
    return ListView.separated(
      padding: const EdgeInsets.only(bottom: 24),
      itemCount: list.length,
      separatorBuilder: (_, __) => const Divider(height: 1),
      itemBuilder: (context, index) {
        final item = list[index];
        final subtitle = [
          item.sizeText,
          item.seeds.isNotEmpty ? '做种: ${item.seeds}' : '',
          item.downloads.isNotEmpty ? '下载: ${item.downloads}' : '',
        ].where((s) => s.isNotEmpty).join(' · ');
        return ListTile(
          leading: const Icon(Icons.bolt, color: Colors.orange),
          title: Text(item.name, maxLines: 2, overflow: TextOverflow.ellipsis, style: const TextStyle(fontSize: 14)),
          subtitle: Text(subtitle, style: const TextStyle(fontSize: 12)),
          onTap: () => _download(item),
          onLongPress: () => _copyMagnet(item),
          trailing: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              IconButton(
                icon: const Icon(Icons.link, size: 18),
                tooltip: '复制磁力链接',
                onPressed: () => _copyMagnet(item),
              ),
              IconButton(
                icon: const Icon(Icons.download, size: 20, color: Colors.green),
                tooltip: '下载种子文件',
                onPressed: () => _download(item),
              ),
            ],
          ),
        );
      },
    );
  }
}
