import 'dart:convert';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../src/rust/parser.dart';

final favoritesProvider = StateNotifierProvider<FavoritesNotifier, List<GalleryItem>>((ref) {
  return FavoritesNotifier();
});

class FavoritesNotifier extends StateNotifier<List<GalleryItem>> {
  FavoritesNotifier() : super([]) {
    _load();
  }

  static const _key = 'local_favorites_list';

  Future<void> _load() async {
    final prefs = await SharedPreferences.getInstance();
    final data = prefs.getStringList(_key);
    if (data != null && data.isNotEmpty) {
      try {
        state = data.map((e) {
          final map = jsonDecode(e) as Map<String, dynamic>;
          return GalleryItem(
            gid: map['gid'] ?? '',
            token: map['token'] ?? '',
            title: map['title'] ?? '',
            thumbUrl: map['thumb_url'] ?? '',
            category: map['category'] ?? '',
            uploader: map['uploader'] ?? '',
            postDate: map['post_date'] ?? '',
          );
        }).toList();
      } catch (e) {
        // Ignore parsing errors
      }
    }
  }

  void _save(List<GalleryItem> items) async {
    final prefs = await SharedPreferences.getInstance();
    final data = items.map((e) => jsonEncode({
      'gid': e.gid,
      'token': e.token,
      'title': e.title,
      'thumb_url': e.thumbUrl,
      'category': e.category,
      'uploader': e.uploader,
      'post_date': e.postDate,
    })).toList();
    await prefs.setStringList(_key, data);
  }

  bool isFavorite(String gid) {
    return state.any((e) => e.gid == gid);
  }

  void addFavorite(GalleryItem item) {
    if (isFavorite(item.gid)) return;
    final newList = [item, ...state];
    state = newList;
    _save(state);
  }

  void removeFavorite(String gid) {
    state = state.where((e) => e.gid != gid).toList();
    _save(state);
  }
}
