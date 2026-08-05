import 'package:flutter/material.dart';
import '../src/rust/api.dart';
import '../src/rust/parser.dart';

/// Full comment list for a gallery, paginated over the detail pages.
/// Comments repeat across image pagination, so pages are deduped by
/// (author, time, content).
class GalleryCommentsPage extends StatefulWidget {
  final GalleryDetail detail;
  final String gid;
  final String token;

  const GalleryCommentsPage({
    super.key,
    required this.detail,
    required this.gid,
    required this.token,
  });

  @override
  State<GalleryCommentsPage> createState() => _GalleryCommentsPageState();
}

class _GalleryCommentsPageState extends State<GalleryCommentsPage> {
  static const int _maxPages = 30;

  late final List<GalleryComment> _comments = List.of(widget.detail.comments);
  final Set<String> _seen = {};
  int _page = 0;
  bool _loading = false;
  bool _hasMore = true;
  String? _error;

  @override
  void initState() {
    super.initState();
    for (final c in _comments) {
      _seen.add(_key(c));
    }
    _loadMore();
  }

  String _key(GalleryComment c) => '${c.author}|${c.time}|${c.content}';

  Future<void> _loadMore() async {
    if (_loading || !_hasMore) return;
    setState(() {
      _loading = true;
      _error = null;
    });

    try {
      final fetched = await fetchMoreComments(
        gid: widget.gid,
        token: widget.token,
        page: _page,
      );
      if (!mounted) return;
      setState(() {
        _page++;
        var added = 0;
        for (final c in fetched) {
          if (_seen.add(_key(c))) {
            _comments.add(c);
            added++;
          }
        }
        if (fetched.isEmpty || added == 0 || _page >= _maxPages) {
          _hasMore = false;
        }
        _loading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _error = '加载失败: $e';
        _hasMore = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    return Scaffold(
      appBar: AppBar(
        title: Text('全部评论 (${_comments.length})',
            style: TextStyle(color: colorScheme.onSurface)),
      ),
      body: _comments.isEmpty && _loading
          ? const Center(child: CircularProgressIndicator())
          : ListView.builder(
              padding: const EdgeInsets.symmetric(vertical: 8),
              itemCount: _comments.length + 1,
              itemBuilder: (context, index) {
                if (index == _comments.length) {
                  if (_error != null) {
                    return Padding(
                      padding: const EdgeInsets.all(16),
                      child: Center(
                        child: Text(_error!,
                            style: TextStyle(color: colorScheme.error, fontSize: 13)),
                      ),
                    );
                  }
                  if (!_hasMore) {
                    return Padding(
                      padding: const EdgeInsets.all(16),
                      child: Center(
                        child: Text('没有更多评论了',
                            style: TextStyle(
                                color: colorScheme.onSurface.withOpacity(0.4),
                                fontSize: 13)),
                      ),
                    );
                  }
                  return Padding(
                    padding: const EdgeInsets.all(16),
                    child: Center(
                      child: _loading
                          ? const CircularProgressIndicator()
                          : TextButton.icon(
                              onPressed: _loadMore,
                              icon: const Icon(Icons.expand_more),
                              label: const Text('加载更多评论'),
                            ),
                    ),
                  );
                }

                final c = _comments[index];
                return Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          Expanded(
                            child: Text(
                              c.author,
                              style: TextStyle(
                                fontWeight: FontWeight.bold,
                                color: colorScheme.onSurface.withOpacity(0.6),
                              ),
                            ),
                          ),
                          Text(
                            c.time,
                            style: TextStyle(
                              color: colorScheme.onSurface.withOpacity(0.4),
                              fontSize: 12,
                            ),
                          ),
                        ],
                      ),
                      const SizedBox(height: 4),
                      Text(c.content, style: TextStyle(color: colorScheme.onSurface)),
                      const SizedBox(height: 8),
                      Divider(color: colorScheme.onSurface.withOpacity(0.08), height: 1),
                    ],
                  ),
                );
              },
            ),
    );
  }
}
