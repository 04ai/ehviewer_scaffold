import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:cached_network_image/cached_network_image.dart';
import '../src/rust/parser.dart';
import '../src/rust/api.dart';
import '../pages/gallery_detail_page.dart';
import '../providers/settings_provider.dart';
import '../providers/downloads_provider.dart';
import 'bouncing_widget.dart';

class GalleryItemWidget extends ConsumerWidget {
  final GalleryItem item;

  const GalleryItemWidget({super.key, required this.item});

  Color _getCategoryColor(String category) {
    switch (category.toLowerCase()) {
      case 'doujinshi':
        return Colors.red;
      case 'manga':
        return Colors.orange;
      case 'artistcg':
        return Colors.amber.shade600;
      case 'gamecg':
        return Colors.green;
      case 'western':
        return Colors.lightGreen;
      case 'non-h':
        return Colors.lightBlue;
      case 'imageset':
        return Colors.blue;
      case 'cosplay':
        return Colors.purple;
      case 'asianporn':
        return Colors.pink;
      case 'misc':
        return const Color(0xFFF48FB1);
      default:
        return Colors.black54;
    }
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final showTranslation = ref.watch(appearanceProvider).showTagTranslation;
    final downloadedGids = ref.watch(downloadsProvider).downloadedGids;
    final isDownloaded = downloadedGids.contains(item.gid.split('/').first);

    String displayCategory = item.category.toUpperCase();
    if (showTranslation) {
      displayCategory = translateTagSync(namespace: 'rows', tag: displayCategory.toLowerCase());
      if (displayCategory.toLowerCase() == item.category.toLowerCase()) {
         displayCategory = displayCategory.toUpperCase();
      }
    }

    return Material(
      // 显式 Material 祖先：保证文本/波纹样式来自当前主题，
      // 防止页面缺 Scaffold 时 Text 继承到异常的 DefaultTextStyle。
      color: Theme.of(context).cardColor,
      child: Container(
        decoration: BoxDecoration(
          border: Border(bottom: BorderSide(color: Theme.of(context).dividerColor, width: 0.5)),
        ),
        child: BouncingWidget(
          onTap: () {
            Navigator.push(
              context,
              MaterialPageRoute(
                builder: (_) => GalleryDetailPage(item: item),
              ),
            );
          },
          child: SizedBox(
            height: 120, // Slightly more compact like image 1
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                // Left side: Thumbnail
                Hero(
                  tag: 'gallery_thumb_${item.gid}',
                  child: SizedBox(
                    width: 90,
                    height: 120,
                    child: Stack(
                      fit: StackFit.expand,
                      children: [
                        CachedNetworkImage(
                          imageUrl: item.thumbUrl,
                          fit: BoxFit.cover,
                          placeholder: (context, url) => Container(color: Theme.of(context).dividerColor),
                          errorWidget: (context, url, error) => Icon(Icons.broken_image, color: Theme.of(context).dividerColor),
                        ),
                        if (isDownloaded)
                          const Positioned(
                            right: 4,
                            top: 4,
                            child: Icon(
                              Icons.check_circle,
                              size: 18,
                              color: Colors.green,
                              shadows: [Shadow(color: Colors.black54, blurRadius: 4)],
                            ),
                          ),
                      ],
                    ),
                  ),
                ),

                // Right side: Details
                Expanded(
                  child: Padding(
                    padding: const EdgeInsets.fromLTRB(10, 8, 10, 8),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        // Title
                        Text(
                          item.title,
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            fontSize: 15,
                            color: Theme.of(context).textTheme.bodyLarge?.color,
                            height: 1.3,
                            // 显式无装饰，防止继承父级 DefaultTextStyle 的异常下划线。
                            decoration: TextDecoration.none,
                          ),
                        ),
                        const SizedBox(height: 6),

                        // Uploader
                        Text(
                          item.uploader.isEmpty ? "Unknown" : item.uploader,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            fontSize: 13,
                            color: Theme.of(context).textTheme.bodyMedium?.color,
                            decoration: TextDecoration.none,
                          ),
                        ),

                        const Spacer(),

                        // Bottom row: Category and Date
                        Row(
                          crossAxisAlignment: CrossAxisAlignment.end,
                          children: [
                            Container(
                              padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 3),
                              color: _getCategoryColor(item.category),
                              child: Text(
                                displayCategory,
                                style: const TextStyle(
                                  color: Colors.white,
                                  fontSize: 11,
                                  fontWeight: FontWeight.bold,
                                  decoration: TextDecoration.none,
                                ),
                              ),
                            ),
                            const Spacer(),
                            Text(
                              item.postDate,
                              style: TextStyle(
                                fontSize: 12,
                                color: Theme.of(context).textTheme.bodyMedium?.color,
                                decoration: TextDecoration.none,
                              ),
                            ),
                          ],
                        ),
                      ],
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
