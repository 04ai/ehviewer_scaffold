import 'dart:async';
import 'dart:io';
import 'dart:isolate';
import 'package:flutter/material.dart';
import 'package:path_provider/path_provider.dart';
import '../src/rust/api.dart';
import '../src/rust/downloader.dart';
import '../utils/haptics.dart';
import '../utils/reader_progress.dart';
import 'offline_viewer_page.dart';

class DownloadsPage extends StatefulWidget {
  const DownloadsPage({super.key});

  @override
  State<DownloadsPage> createState() => _DownloadsPageState();
}

class _DownloadsPageState extends State<DownloadsPage> {
  List<DownloadTask> _tasks = [];
  Timer? _timer;

  // ── Selection / batch mode ──────────────────────────────────────────────
  bool _selectionMode = false;
  final Set<String> _selected = {};

  // Per-task disk size, keyed by gid and invalidated when the downloaded
  // page count changes (cheap: only recomputed after new pages land).
  final Map<String, int> _sizeCache = {};
  final Map<String, Future<int>> _sizeFutures = {};

  Future<int> _taskSize(DownloadTask task) {
    final key = '${task.gid}_${task.downloadedPages}';
    final cached = _sizeCache[key];
    if (cached != null) return Future.value(cached);
    final future = _sizeFutures[key] ??= _computeSize(task.gid);
    future.then((size) => _sizeCache[key] = size);
    return future;
  }

  Future<int> _computeSize(String gid) async {
    try {
      final base = await getDownloadDir() ??
          '${(await getTemporaryDirectory()).path}/eh_downloads';
      final dirPath = '$base/$gid';
      // Sum file sizes on a background isolate so big galleries don't jank.
      return await Isolate.run(() {
        final dir = Directory(dirPath);
        if (!dir.existsSync()) return 0;
        return dir
            .listSync(recursive: true, followLinks: false)
            .whereType<File>()
            .fold<int>(0, (sum, f) => sum + f.lengthSync());
      });
    } catch (_) {
      return 0;
    }
  }

  void _forgetSize(String gid) {
    _sizeCache.removeWhere((key, _) => key.startsWith('${gid}_'));
    _sizeFutures.removeWhere((key, _) => key.startsWith('${gid}_'));
  }

  String _formatSize(int bytes) {
    if (bytes >= 1024 * 1024) return '${(bytes / (1024 * 1024)).toStringAsFixed(1)} MB';
    if (bytes >= 1024) return '${(bytes / 1024).toStringAsFixed(0)} KB';
    return '$bytes B';
  }

  @override
  void initState() {
    super.initState();
    _fetchTasks();
    // Poll every 2s: the FFI call serializes the whole queue, so a 500ms poll
    // wastes battery/CPU for a progress display that updates a few times/sec.
    _timer = Timer.periodic(const Duration(seconds: 2), (_) => _fetchTasks());
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  Future<void> _fetchTasks() async {
    try {
      final tasks = await getDownloadTasks();
      if (mounted) {
        setState(() {
          // Active first, then paused/errored, then completed (stable sort).
          int rank(DownloadTask t) => t.status == 1
              ? 0
              : (t.status == 0 || t.status == -1) ? 1 : 2;
          _tasks = List<DownloadTask>.from(tasks)
            ..sort((a, b) => rank(a).compareTo(rank(b)));
        });
      }
    } catch (e) {
      // Ignore errors for now
    }
  }

  // ── Selection helpers ───────────────────────────────────────────────────

  void _enterSelection(String gid) {
    setState(() {
      _selectionMode = true;
      _selected.add(gid);
    });
  }

  void _toggleSelect(String gid) {
    setState(() {
      if (!_selected.add(gid)) {
        _selected.remove(gid);
      }
    });
  }

  void _exitSelection() {
    setState(() {
      _selectionMode = false;
      _selected.clear();
    });
  }

  void _toggleSelectAll() {
    setState(() {
      if (_selected.length == _tasks.length) {
        _selected.clear();
      } else {
        _selected.addAll(_tasks.map((t) => t.gid));
      }
    });
  }

  bool _anySelectedStatus(bool Function(int) test) => _selected.any(
        (gid) => _tasks.any((t) => t.gid == gid && test(t.status)),
      );

  // ── Batch operations ────────────────────────────────────────────────────

  Future<void> _batchPause() async {
    for (final t in _tasks.where((t) => _selected.contains(t.gid) && t.status == 1)) {
      await pauseDownload(gid: t.gid);
    }
  }

  Future<void> _batchResume() async {
    var count = 0;
    for (final t in _tasks
        .where((t) => _selected.contains(t.gid) && (t.status == 0 || t.status == -1))) {
      try {
        // Empty imageUrls: the Rust downloader resumes from the URLs
        // persisted with the task itself.
        await startDownload(gid: t.gid, token: t.token, title: t.title, imageUrls: const [], totalPages: t.totalPages);
        count++;
      } catch (_) {
        // skip failed resumes, continue with the rest
      }
    }
    if (mounted && count > 0) {
      Haptics.confirm();
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('已开始续传 $count 个任务')));
    }
  }

  Future<void> _batchDelete() async {
    if (_selected.isEmpty) return;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('批量删除'),
        content: Text('将删除 ${_selected.length} 个任务及其已下载的文件，确定吗？'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('取消')),
          TextButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('删除', style: TextStyle(color: Colors.redAccent)),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;
    for (final gid in _selected.toList()) {
      await deleteDownload(gid: gid);
      await clearReaderProgress(gid);
      _forgetSize(gid);
    }
    setState(() {
      _selectionMode = false;
      _selected.clear();
    });
    if (mounted) {
      Haptics.error();
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('已删除所选任务')));
    }
  }

  // ── Single-task operations ──────────────────────────────────────────────

  Future<void> _deleteAllCompleted() async {
    final completed = _tasks.where((t) => t.status == 2).toList();
    if (completed.isEmpty) return;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('删除所有已完成'),
        content: Text('将删除 ${completed.length} 个已完成任务及其已下载的文件，确定吗？'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('取消')),
          TextButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('删除', style: TextStyle(color: Colors.redAccent)),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;
    for (final task in completed) {
      await deleteDownload(gid: task.gid);
      await clearReaderProgress(task.gid);
      _forgetSize(task.gid);
    }
    if (mounted) {
      Haptics.error();
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('已删除所有已完成任务')),
      );
    }
  }

  Future<void> _pause(String gid) async {
    try {
      await pauseDownload(gid: gid);
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('暂停失败: $e')));
      }
    }
  }

  Future<void> _resume(DownloadTask task) async {
    try {
      // Empty imageUrls tells the Rust downloader to resume from the
      // URLs/token/title persisted with the task itself.
      await startDownload(
        gid: task.gid,
        token: task.token,
        title: task.title,
        imageUrls: const [],
        totalPages: task.totalPages,
      );
      Haptics.confirm();
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('已开始续传')));
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('续传失败: $e')));
      }
    }
  }

  Future<void> _delete(DownloadTask task) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('删除下载'),
        content: Text('确定删除「${task.title}」吗？已下载的文件也会被移除。'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('取消')),
          TextButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('删除', style: TextStyle(color: Colors.redAccent)),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;
    try {
      await deleteDownload(gid: task.gid);
      await clearReaderProgress(task.gid);
      _forgetSize(task.gid);
      if (mounted) {
        Haptics.error();
        ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('已删除下载')));
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('删除失败: $e')));
      }
    }
  }

  void _read(DownloadTask task) {
    Navigator.push(
      context,
      MaterialPageRoute(
        builder: (_) => OfflineViewerPage(gid: task.gid, title: task.title),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final hasCompleted = _tasks.any((t) => t.status == 2);
    return Scaffold(
      appBar: AppBar(
        title: Text(_selectionMode ? '已选 ${_selected.length} 项' : '下载管理'),
        leading: _selectionMode
            ? IconButton(
                icon: const Icon(Icons.close),
                tooltip: '退出批量选择',
                onPressed: _exitSelection,
              )
            : null,
        actions: _selectionMode
            ? [
                IconButton(
                  icon: Icon(
                    _selected.length == _tasks.length && _tasks.isNotEmpty
                        ? Icons.deselect
                        : Icons.select_all,
                  ),
                  tooltip: '全选/取消全选',
                  onPressed: _tasks.isEmpty ? null : _toggleSelectAll,
                ),
              ]
            : [
                if (hasCompleted)
                  IconButton(
                    icon: const Icon(Icons.delete_sweep),
                    tooltip: '删除所有已完成',
                    onPressed: _deleteAllCompleted,
                  ),
                if (_tasks.isNotEmpty)
                  IconButton(
                    icon: const Icon(Icons.checklist),
                    tooltip: '批量操作',
                    onPressed: () => setState(() => _selectionMode = true),
                  ),
              ],
      ),
      body: _tasks.isEmpty
          ? const Center(child: Text('当前没有下载任务', style: TextStyle(color: Colors.black54)))
          : ListView.builder(
              itemCount: _tasks.length,
              itemBuilder: (context, index) {
                final task = _tasks[index];
                final isSelected = _selected.contains(task.gid);
                final progress = task.totalPages > 0 
                    ? task.downloadedPages / task.totalPages 
                    : 0.0;
                final statusText = task.status == 2 ? '已完成' 
                    : task.status == 1 ? '下载中' 
                    : task.status == -1 ? '错误' : '已暂停';
                final statusColor = task.status == 2 ? Colors.green 
                    : task.status == -1 ? Colors.red 
                    : task.status == 0 ? Colors.grey : Colors.blue;
                
                return Card(
                  margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                  color: _selectionMode && isSelected
                      ? Colors.blue.withOpacity(0.12)
                      : null,
                  child: InkWell(
                    onTap: _selectionMode ? () => _toggleSelect(task.gid) : null,
                    onLongPress: _selectionMode ? null : () => _enterSelection(task.gid),
                    child: Padding(
                      padding: const EdgeInsets.all(16.0),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Row(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              if (_selectionMode)
                                Padding(
                                  padding: const EdgeInsets.only(right: 8, top: 2),
                                  child: Icon(
                                    isSelected
                                        ? Icons.check_circle
                                        : Icons.radio_button_unchecked,
                                    color: isSelected ? Colors.blue : Colors.grey,
                                    size: 20,
                                  ),
                                ),
                              Expanded(
                                child: Text(
                                  task.title,
                                  maxLines: 2,
                                  overflow: TextOverflow.ellipsis,
                                  style: const TextStyle(fontWeight: FontWeight.bold),
                                ),
                              ),
                            ],
                          ),
                          const SizedBox(height: 8),
                          Row(
                            children: [
                              Text(
                                statusText,
                                style: TextStyle(
                                  color: statusColor,
                                  fontWeight: FontWeight.bold,
                                ),
                              ),
                              const Spacer(),
                              Text('${task.downloadedPages} / ${task.totalPages}'),
                            ],
                          ),
                          const SizedBox(height: 4),
                          Row(
                            children: [
                              FutureBuilder<int>(
                                future: _taskSize(task),
                                builder: (context, snapshot) {
                                  final size = snapshot.data ?? 0;
                                  return Text(
                                    '已占用: ${_formatSize(size)}',
                                    style: TextStyle(
                                      color: Colors.grey.shade600,
                                      fontSize: 12,
                                    ),
                                  );
                                },
                              ),
                            ],
                          ),
                          const SizedBox(height: 8),
                          LinearProgressIndicator(
                            value: progress,
                            backgroundColor: Colors.grey.shade300,
                            color: statusColor,
                          ),
                          if (task.errorMsg != null)
                            Padding(
                              padding: const EdgeInsets.only(top: 8.0),
                              child: Text(task.errorMsg!, style: const TextStyle(color: Colors.red, fontSize: 12)),
                            ),
                          if (!_selectionMode)
                            Padding(
                              padding: const EdgeInsets.only(top: 8.0),
                              child: Row(
                                children: [
                                  if (task.status == 1)
                                    TextButton.icon(
                                      onPressed: () => _pause(task.gid),
                                      icon: const Icon(Icons.pause, size: 18),
                                      label: const Text('暂停'),
                                    )
                                  else if (task.status == 2)
                                    TextButton.icon(
                                      onPressed: () => _read(task),
                                      icon: const Icon(Icons.menu_book, size: 18),
                                      label: const Text('阅读'),
                                    )
                                  else if (task.status == 0 || task.status == -1)
                                    TextButton.icon(
                                      onPressed: () => _resume(task),
                                      icon: const Icon(Icons.play_arrow, size: 18),
                                      label: const Text('继续'),
                                    ),
                                  const Spacer(),
                                  TextButton.icon(
                                    onPressed: () => _delete(task),
                                    icon: const Icon(Icons.delete_outline, size: 18),
                                    label: const Text('删除'),
                                    style: TextButton.styleFrom(foregroundColor: Colors.redAccent),
                                  ),
                                ],
                              ),
                            ),
                        ],
                      ),
                    ),
                  ),
                );
              },
            ),
      bottomNavigationBar: _selectionMode
          ? SafeArea(
              child: Container(
                color: Theme.of(context).appBarTheme.backgroundColor,
                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.spaceAround,
                  children: [
                    _batchButton(
                      icon: Icons.pause,
                      label: '暂停',
                      onPressed: _anySelectedStatus((s) => s == 1) ? _batchPause : null,
                    ),
                    _batchButton(
                      icon: Icons.play_arrow,
                      label: '继续',
                      onPressed: _anySelectedStatus((s) => s == 0 || s == -1) ? _batchResume : null,
                    ),
                    _batchButton(
                      icon: Icons.delete,
                      label: '删除',
                      onPressed: _selected.isNotEmpty ? _batchDelete : null,
                      color: Colors.redAccent,
                    ),
                  ],
                ),
              ),
            )
          : null,
    );
  }

  Widget _batchButton({
    required IconData icon,
    required String label,
    required VoidCallback? onPressed,
    Color color = Colors.blueGrey,
  }) {
    final enabled = onPressed != null;
    return InkWell(
      onTap: onPressed,
      borderRadius: BorderRadius.circular(8),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(icon, color: enabled ? color : Colors.grey.shade500, size: 22),
            const SizedBox(height: 2),
            Text(
              label,
              style: TextStyle(
                fontSize: 12,
                color: enabled ? Colors.black87 : Colors.grey.shade500,
              ),
            ),
          ],
        ),
      ),
    );
  }
}
