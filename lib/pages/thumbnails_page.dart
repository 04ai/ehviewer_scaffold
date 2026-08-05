import 'package:flutter/material.dart';
import 'package:cached_network_image/cached_network_image.dart';
import '../src/rust/parser.dart';
import '../utils/gallery_detail_cache.dart';
import 'gallery_viewer_page.dart';

class ThumbnailsPage extends StatefulWidget {
  final GalleryItem item;
  final GalleryDetail initialDetail;

  const ThumbnailsPage({
    super.key,
    required this.item,
    required this.initialDetail,
  });

  @override
  State<ThumbnailsPage> createState() => _ThumbnailsPageState();
}

class _ThumbnailsPageState extends State<ThumbnailsPage> {
  final ScrollController _scrollController = ScrollController();
  final List<String> _imageUrls = [];
  final List<GalleryThumbnail> _thumbnails = [];
  
  bool _isLoadingMore = false;
  int _currentPage = 0;
  bool _hasMore = true;
  
  @override
  void initState() {
    super.initState();
    // Initialize with first page
    _imageUrls.addAll(widget.initialDetail.imageUrls);
    _thumbnails.addAll(widget.initialDetail.thumbnails);
    
    // Check if we need to load more immediately
    if (_imageUrls.length >= widget.initialDetail.totalPages) {
      _hasMore = false;
    }
    
    _scrollController.addListener(_onScroll);
  }
  
  @override
  void dispose() {
    _scrollController.dispose();
    super.dispose();
  }

  void _onScroll() {
    if (_scrollController.position.pixels >= _scrollController.position.maxScrollExtent - 200) {
      _loadMore();
    }
  }

  Future<void> _loadMore() async {
    if (_isLoadingMore || !_hasMore) return;
    
    setState(() {
      _isLoadingMore = true;
    });
    
    try {
      _currentPage++;
      final parts = widget.item.gid.split('/');
      final gid = parts.isNotEmpty ? parts[0] : widget.item.gid;
      
      final pageDetail = await fetchGalleryPageCached(
        gid: gid,
        token: widget.item.token,
        page: _currentPage,
      );
      
      setState(() {
        if (pageDetail.imageUrls.isEmpty || _imageUrls.length >= widget.initialDetail.totalPages) {
          _hasMore = false;
        } else {
          _imageUrls.addAll(pageDetail.imageUrls);
          _thumbnails.addAll(pageDetail.thumbnails);
        }
        _isLoadingMore = false;
      });
    } catch (e) {
      setState(() {
        _isLoadingMore = false;
        // Optionally decrement page on error to retry
        _currentPage--;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('所有页面'),
        centerTitle: true,
      ),
      body: GridView.builder(
        controller: _scrollController,
        padding: const EdgeInsets.all(8),
        gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
          crossAxisCount: 3,
          childAspectRatio: 0.7,
          crossAxisSpacing: 8,
          mainAxisSpacing: 8,
        ),
        itemCount: _thumbnails.length + (_isLoadingMore ? 3 : 0),
        itemBuilder: (context, index) {
          if (index >= _thumbnails.length) {
            return const Center(child: CircularProgressIndicator());
          }
          
          final thumb = _thumbnails[index];
          return GestureDetector(
            onTap: () {
              // We need to create a synthetic detail that contains ALL urls loaded so far
              // so the viewer can swipe through them seamlessly
              final syntheticDetail = GalleryDetail(
                id: widget.initialDetail.id,
                title: widget.initialDetail.title,
                titleJpn: widget.initialDetail.titleJpn,
                coverUrl: widget.initialDetail.coverUrl,
                uploader: widget.initialDetail.uploader,
                rating: widget.initialDetail.rating,
                language: widget.initialDetail.language,
                fileSize: widget.initialDetail.fileSize,
                postDate: widget.initialDetail.postDate,
                favoritesCount: widget.initialDetail.favoritesCount,
                torrentCount: widget.initialDetail.torrentCount,
                tagGroups: widget.initialDetail.tagGroups,
                totalPages: widget.initialDetail.totalPages,
                imageUrls: _imageUrls,
                thumbnails: _thumbnails,
                comments: widget.initialDetail.comments,
                isFavorited: widget.initialDetail.isFavorited,
              );
              
              Navigator.push(
                context,
                MaterialPageRoute(
                  builder: (_) => GalleryViewerPage(
                    detail: syntheticDetail,
                    initialPage: index,
                  ),
                ),
              );
            },
            child: Container(
              decoration: BoxDecoration(
                border: Border.all(color: Colors.grey.shade300),
                borderRadius: BorderRadius.circular(4),
              ),
              child: ClipRRect(
                borderRadius: BorderRadius.circular(4),
                child: Stack(
                  children: [
                    if (thumb.url.isNotEmpty)
                      Positioned.fill(
                        child: CachedNetworkImage(
                          imageUrl: thumb.url,
                          fit: BoxFit.cover,
                          alignment: FractionalOffset(
                            thumb.offsetX == 0 ? 0 : 0.5, // Rough approximation since we don't have full sprite support here yet, usually E-Hentai sends direct links now if signed in
                            0,
                          ),
                          placeholder: (context, url) => Container(color: Colors.grey[200]),
                          errorWidget: (context, url, error) => const Icon(Icons.error),
                        ),
                      ),
                    Positioned(
                      bottom: 4,
                      right: 4,
                      child: Container(
                        padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 2),
                        color: Colors.black54,
                        child: Text(
                          '${index + 1}',
                          style: const TextStyle(color: Colors.white, fontSize: 10),
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          );
        },
      ),
    );
  }
}
