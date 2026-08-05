import 'dart:convert';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../src/rust/parser.dart';

class HistoryEntry {
  final GalleryItem item;
  final int viewedAt;

  const HistoryEntry({required this.item, required this.viewedAt});
}

final historyProvider = StateNotifierProvider<HistoryNotifier, List<HistoryEntry>>((ref) {
  return HistoryNotifier();
});

class HistoryNotifier extends StateNotifier<List<HistoryEntry>> {
  HistoryNotifier() : super([]) {
    _load();
  }

  static const _key = 'eh_history_list';

  Future<void> _load() async {
    final prefs = await SharedPreferences.getInstance();
    final data = prefs.getStringList(_key);
    if (data != null && data.isNotEmpty) {
      try {
        final entries = <HistoryEntry>[];
        for (final e in data) {
          final map = jsonDecode(e) as Map<String, dynamic>;
          entries.add(HistoryEntry(
            item: GalleryItem(
              gid: map['gid'] ?? '',
              token: map['token'] ?? '',
              title: map['title'] ?? '',
              thumbUrl: map['thumb_url'] ?? '',
              category: map['category'] ?? '',
              uploader: map['uploader'] ?? '',
              postDate: map['post_date'] ?? '',
            ),
            viewedAt: (map['viewed_at'] as num?)?.toInt() ?? 0,
          ));
        }
        entries.sort((a, b) => b.viewedAt.compareTo(a.viewedAt));
        state = entries;
      } catch (e) {
        // Ignore parsing errors
      }
    }
  }

  void _save(List<HistoryEntry> entries) async {
    final prefs = await SharedPreferences.getInstance();
    final data = entries.map((e) => jsonEncode({
      'gid': e.item.gid,
      'token': e.item.token,
      'title': e.item.title,
      'thumb_url': e.item.thumbUrl,
      'category': e.item.category,
      'uploader': e.item.uploader,
      'post_date': e.item.postDate,
      'viewed_at': e.viewedAt,
    })).toList();
    await prefs.setStringList(_key, data);
  }

  void addHistory(GalleryItem item) {
    // Remove if already exists, then insert at top with a fresh timestamp
    final newList = state.where((e) => e.item.gid != item.gid).toList();
    newList.insert(0, HistoryEntry(item: item, viewedAt: DateTime.now().millisecondsSinceEpoch));

    // Limit to 500 items to save space
    if (newList.length > 500) {
      newList.removeRange(500, newList.length);
    }

    state = newList;
    _save(state);
  }

  void clearHistory() {
    state = [];
    _save(state);
  }

  void removeHistory(String gid) {
    state = state.where((e) => e.item.gid != gid).toList();
    _save(state);
  }

  void removeHistories(List<String> gids) {
    final set = gids.toSet();
    state = state.where((e) => !set.contains(e.item.gid)).toList();
    _save(state);
  }
}
