import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../src/rust/api.dart';

/// Global download status (downloaded gids + one-shot completion signal).
/// Polled by the app's periodic timer; widgets subscribe via this provider
/// to show downloaded badges without each page polling on its own.
final downloadsProvider = ChangeNotifierProvider<DownloadsStatus>((ref) => DownloadsStatus());

class DownloadsStatus extends ChangeNotifier {
  Set<String> _downloadedGids = {};
  final Map<String, int> _lastStatus = {};
  String? _completedTitle;

  Set<String> get downloadedGids => _downloadedGids;

  /// One-shot: the title of a task that completed since the last poll, if any.
  String? takeCompletedTitle() {
    final title = _completedTitle;
    _completedTitle = null;
    return title;
  }

  Future<void> poll() async {
    try {
      final tasks = await getDownloadTasks();
      final completed = <String>{};
      for (final t in tasks) {
        if (t.status == 2) {
          completed.add(t.gid);
        }
        final prev = _lastStatus[t.gid];
        // Only notify for tasks seen in a previous poll, so previously
        // completed downloads don't flash a "finished" toast on app start.
        if (prev != null && prev != 2 && t.status == 2) {
          _completedTitle = t.title;
        }
        _lastStatus[t.gid] = t.status;
      }
      final known = tasks.map((t) => t.gid).toSet();
      _lastStatus.removeWhere((gid, _) => !known.contains(gid));

      if (!setEquals(_downloadedGids, completed)) {
        _downloadedGids = completed;
        notifyListeners();
      }
    } catch (_) {
      // transient failures are ignored; next poll will retry
    }
  }
}
