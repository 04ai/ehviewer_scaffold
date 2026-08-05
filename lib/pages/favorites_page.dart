import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../main.dart';
import '../providers/favorites_provider.dart';
import '../src/rust/api.dart';
import '../src/rust/parser.dart';
import '../widgets/gallery_item_widget.dart';
import '../utils/haptics.dart';

class FavoritesPage extends ConsumerStatefulWidget {
  const FavoritesPage({super.key});

  @override
  ConsumerState<FavoritesPage> createState() => _FavoritesPageState();
}

class _FavoritesPageState extends ConsumerState<FavoritesPage> {
  static const _cloudCount = 10;
  int _selected = 0; // 0 = 全部, 1 = 本地, 2..11 = 云 0..9

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Theme.of(context).scaffoldBackgroundColor,
      appBar: AppBar(
        title: const Text('收藏'),
        backgroundColor: Theme.of(context).appBarTheme.backgroundColor,
        foregroundColor: Theme.of(context).appBarTheme.foregroundColor,
      ),
      body: Column(
        children: [
          _buildChipRow(),
          Expanded(child: _buildContent()),
        ],
      ),
    );
  }

  Widget _buildChipRow() {
    final items = <String>['全部', '本地', ...List.generate(_cloudCount, (i) => '云 $i')];
    final primary = Theme.of(context).colorScheme.primary;
    return SizedBox(
      height: 48,
      child: ListView.separated(
        scrollDirection: Axis.horizontal,
        padding: const EdgeInsets.symmetric(horizontal: 12),
        itemCount: items.length,
        separatorBuilder: (_, __) => const SizedBox(width: 8),
        itemBuilder: (context, i) {
          return ChoiceChip(
            label: Text(items[i]),
            selected: _selected == i,
            selectedColor: primary,
            labelStyle: TextStyle(
              fontSize: 13,
              color: _selected == i
                  ? Colors.white
                  : Theme.of(context).textTheme.bodyLarge?.color,
            ),
            onSelected: (_) => setState(() => _selected = i),
          );
        },
      ),
    );
  }

  Widget _buildContent() {
    if (_selected >= 2) {
      final favcat = _selected - 2;
      return CustomListView(
        key: ValueKey('favcat_$favcat'),
        path: 'favorites.php?favcat=$favcat',
        title: '云收藏 $favcat',
        showMenu: false,
      );
    }
    if (_selected == 1) {
      return const _LocalFavoritesView();
    }
    return const _MergedFavoritesView();
  }
}

/// 本地收藏列表（离线，无需登录）。左滑或长按可移除。
class _LocalFavoritesView extends ConsumerWidget {
  const _LocalFavoritesView();

  Future<void> _confirmRemove(BuildContext context, WidgetRef ref, GalleryItem item) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('移除本地收藏'),
        content: Text('确定移除「${item.title}」吗？'),
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
    if (confirmed == true && context.mounted) {
      ref.read(favoritesProvider.notifier).removeFavorite(item.gid);
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('已从本地收藏移除')));
    }
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final favorites = ref.watch(favoritesProvider);
    if (favorites.isEmpty) {
      return Center(
        child: Text('暂无本地收藏，可在画廊详情页收藏到本地',
            style: TextStyle(color: Theme.of(context).textTheme.bodyMedium?.color?.withOpacity(0.6), fontSize: 15)),
      );
    }
    return ListView.builder(
      padding: const EdgeInsets.symmetric(vertical: 8),
      itemCount: favorites.length,
      itemBuilder: (context, index) {
        final item = favorites[index];
        return GestureDetector(
          onLongPress: () => _confirmRemove(context, ref, item),
          child: Dismissible(
            key: ValueKey('local_fav_${item.gid}'),
            direction: DismissDirection.endToStart,
            background: Container(
              color: const Color(0xFFE94560),
              alignment: Alignment.centerRight,
              padding: const EdgeInsets.only(right: 24),
              child: const Icon(Icons.delete, color: Colors.white),
            ),
            onDismissed: (_) {
              ref.read(favoritesProvider.notifier).removeFavorite(item.gid);
              ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('已从本地收藏移除')));
            },
            child: GalleryItemWidget(item: item),
          ),
        );
      },
    );
  }
}

/// 全部收藏：云端收藏（favorites.php 第一页）+ 本地收藏，按 gid 去重。
/// 云端加载失败（未登录/网络）时仅显示本地，并给出提示。
class _MergedFavoritesView extends ConsumerStatefulWidget {
  const _MergedFavoritesView();

  @override
  ConsumerState<_MergedFavoritesView> createState() => _MergedFavoritesViewState();
}

class _MergedFavoritesViewState extends ConsumerState<_MergedFavoritesView> {
  List<GalleryItem>? _cloudItems;
  String? _cloudError;
  bool _loadingCloud = true;

  @override
  void initState() {
    super.initState();
    _loadCloud();
  }

  Future<void> _loadCloud() async {
    setState(() {
      _loadingCloud = true;
      _cloudError = null;
    });
    try {
      final page = await fetchCustomList(path: 'favorites.php', page: 0, pageUrl: null, query: null, options: null);
      if (!mounted) return;
      setState(() {
        _cloudItems = page.items;
        _loadingCloud = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _cloudError = e.toString();
        _loadingCloud = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final local = ref.watch(favoritesProvider);

    final merged = <GalleryItem>[];
    final seen = <String>{};
    for (final item in _cloudItems ?? <GalleryItem>[]) {
      if (seen.add(item.gid)) merged.add(item);
    }
    for (final item in local) {
      if (seen.add(item.gid)) merged.add(item);
    }

    if (_loadingCloud) {
      return const Center(child: CircularProgressIndicator());
    }

    if (merged.isEmpty) {
      return Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            if (_cloudError != null && local.isEmpty)
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 24),
                child: Text(
                  '云端收藏加载失败（未登录或网络异常），可在详情页收藏到本地离线查看。\n$_cloudError',
                  textAlign: TextAlign.center,
                  style: TextStyle(
                    color: Theme.of(context).textTheme.bodyMedium?.color?.withOpacity(0.6),
                    fontSize: 13,
                  ),
                ),
              )
            else
              Text('暂无收藏',
                  style: TextStyle(color: Theme.of(context).textTheme.bodyMedium?.color?.withOpacity(0.6), fontSize: 15)),
          ],
        ),
      );
    }

    return Column(
      children: [
        if (_cloudError != null)
          Container(
            width: double.infinity,
            color: Theme.of(context).colorScheme.error.withOpacity(0.12),
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
            child: InkWell(
              onTap: () {
                Haptics.tap();
                _loadCloud();
              },
              child: Text(
                '云端收藏加载失败，仅显示本地收藏。点此重试',
                style: TextStyle(color: Theme.of(context).colorScheme.error, fontSize: 13),
              ),
            ),
          ),
        Expanded(
          child: ListView.builder(
            padding: const EdgeInsets.symmetric(vertical: 8),
            itemCount: merged.length,
            itemBuilder: (context, index) {
              return GalleryItemWidget(item: merged[index]);
            },
          ),
        ),
      ],
    );
  }
}
