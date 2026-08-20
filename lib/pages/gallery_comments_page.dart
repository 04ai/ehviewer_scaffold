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
  final Set<BigInt> _voting = {};
  final Map<BigInt, int> _scoreDelta = {};
  final TextEditingController _commentCtrl = TextEditingController();
  final FocusNode _commentFocus = FocusNode();
  String? _replyTo;
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

  bool _didPostComment = false;

  @override
  void dispose() {
    _commentCtrl.dispose();
    _commentFocus.dispose();
    super.dispose();
  }

  /// 点赞/点踩（EH 评论投票接口）。
  Future<void> _vote(GalleryComment c, {required bool up}) async {
    final keyId = c.id != BigInt.zero ? c.id : BigInt.from(c.hashCode);
    if (_voting.contains(keyId)) return;
    if (c.id == BigInt.zero && c.voteUrl.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('该评论暂无法进行点赞/点踩操作')),
      );
      return;
    }
    _voting.add(keyId);
    try {
      String url;
      if (c.voteUrl.isNotEmpty) {
        url = c.voteUrl;
        if (up) {
          url = url.replaceFirst(RegExp(r'vote=-\d+|vote=\d+'), 'vote=1');
        } else {
          url = url.replaceFirst(RegExp(r'vote=-\d+|vote=\d+'), 'vote=-1');
        }
      } else {
        url = '/gallerycomments.php?gid=${widget.gid}&t=${widget.token}&act=vote&comment_id=${c.id}&vote=${up ? 1 : -1}';
      }

      await voteComment(url: url);
      if (!mounted) return;
      setState(() {
        _scoreDelta[keyId] = (_scoreDelta[keyId] ?? 0) + (up ? 1 : -1);
        _voting.remove(keyId);
      });
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(up ? '已点赞' : '已点踩')),
      );
    } catch (e) {
      if (!mounted) return;
      setState(() => _voting.remove(keyId));
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('操作失败: $e')));
    }
  }

  /// 回复某条评论：聚焦输入框并预填作者引用（EH 的"回复"即带引用的新评论）。
  void _startReply(GalleryComment c) {
    setState(() => _replyTo = c.author);
    _commentCtrl.text = '@${c.author} ';
    _commentCtrl.selection = TextSelection.collapsed(offset: _commentCtrl.text.length);
    _commentFocus.requestFocus();
  }

  Future<void> _submitComment() async {
    final val = _commentCtrl.text.trim();
    if (val.isEmpty) return;
    try {
      await postComment(gid: widget.gid, token: widget.token, content: val);
      if (!mounted) return;
      _commentCtrl.clear();
      FocusManager.instance.primaryFocus?.unfocus();
      setState(() {
        _replyTo = null;
        _didPostComment = true;
      });
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('评论发布成功')));
      _refreshComments();
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('评论失败: $e')));
    }
  }

  Future<void> _refreshComments() async {
    setState(() {
      _loading = true;
      _error = null;
      _page = 0;
      _comments.clear();
      _seen.clear();
    });
    await _loadMore();
  }

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
    return PopScope(
      canPop: true,
      onPopInvoked: (didPop) {},
      child: Scaffold(
        appBar: AppBar(
          leading: IconButton(
            icon: const Icon(Icons.arrow_back),
            onPressed: () => Navigator.pop(context, _didPostComment),
          ),
          title: Text('全部评论 (${_comments.length})',
              style: TextStyle(color: colorScheme.onSurface)),
        ),
        body: Column(
          children: [
            Expanded(
              child: _comments.isEmpty && _loading
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
                        final keyId = c.id != BigInt.zero ? c.id : BigInt.from(c.hashCode);
                        final voting = _voting.contains(keyId);
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
                              const SizedBox(height: 6),
                              // 评分 + 赞/踩 + 回复（与 E-Hentai 评论同步）
                              Row(
                                children: [
                                  IconButton(
                                    visualDensity: VisualDensity.compact,
                                    iconSize: 18,
                                    icon: voting
                                        ? const SizedBox(
                                            width: 14, height: 14,
                                            child: CircularProgressIndicator(strokeWidth: 2))
                                        : Icon(Icons.thumb_up_outlined,
                                            color: colorScheme.onSurface.withOpacity(0.6)),
                                    tooltip: '点赞',
                                    onPressed: !voting
                                        ? () => _vote(c, up: true)
                                        : null,
                                  ),
                                  Text(
                                    '${c.score + (_scoreDelta[keyId] ?? 0)}',
                                    style: TextStyle(
                                      fontSize: 13,
                                      fontWeight: FontWeight.w600,
                                      color: (c.score + (_scoreDelta[keyId] ?? 0)) > 0
                                          ? Colors.green
                                          : ((c.score + (_scoreDelta[keyId] ?? 0)) < 0
                                              ? colorScheme.error
                                              : colorScheme.onSurface.withOpacity(0.6)),
                                    ),
                                  ),
                                  IconButton(
                                    visualDensity: VisualDensity.compact,
                                    iconSize: 18,
                                    icon: Icon(Icons.thumb_down_outlined,
                                        color: colorScheme.onSurface.withOpacity(0.6)),
                                    tooltip: '点踩',
                                    onPressed: !voting
                                        ? () => _vote(c, up: false)
                                        : null,
                                  ),
                                  const Spacer(),
                                  TextButton.icon(
                                    style: TextButton.styleFrom(
                                      visualDensity: VisualDensity.compact,
                                      foregroundColor: colorScheme.onSurface.withOpacity(0.5),
                                    ),
                                    onPressed: () => _startReply(c),
                                    icon: const Icon(Icons.reply, size: 15),
                                    label: const Text('回复', style: TextStyle(fontSize: 12)),
                                  ),
                                ],
                              ),
                              Divider(color: colorScheme.onSurface.withOpacity(0.08), height: 1),
                            ],
                          ),
                        );
                      },
                    ),
          ),
          // 底部评论输入栏（回复时预填 @作者）
          SafeArea(
            top: false,
            child: Container(
              padding: const EdgeInsets.fromLTRB(12, 8, 8, 8),
              decoration: BoxDecoration(
                color: colorScheme.surface,
                border: Border(top: BorderSide(color: colorScheme.onSurface.withOpacity(0.08))),
              ),
              child: Row(
                children: [
                  Expanded(
                    child: TextField(
                      controller: _commentCtrl,
                      focusNode: _commentFocus,
                      minLines: 1,
                      maxLines: 3,
                      decoration: InputDecoration(
                        hintText: _replyTo == null ? '写评论...' : '回复 $_replyTo...',
                        border: OutlineInputBorder(
                          borderRadius: BorderRadius.circular(20),
                          borderSide: BorderSide(color: colorScheme.onSurface.withOpacity(0.15)),
                        ),
                        contentPadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
                      ),
                      onSubmitted: (_) => _submitComment(),
                    ),
                  ),
                  IconButton(
                    icon: Icon(Icons.send, color: colorScheme.primary),
                    tooltip: '发送',
                    onPressed: _submitComment,
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    ),
  );
}
}
