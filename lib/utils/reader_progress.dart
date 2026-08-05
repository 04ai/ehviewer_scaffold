import 'package:shared_preferences/shared_preferences.dart';

const String _keyPrefix = 'reader_progress_';

/// Last read page (0-indexed) for a gallery, or null if never read.
Future<int?> getReaderProgress(String gid) async {
  final prefs = await SharedPreferences.getInstance();
  return prefs.getInt('$_keyPrefix$gid');
}

Future<void> saveReaderProgress(String gid, int page) async {
  final prefs = await SharedPreferences.getInstance();
  await prefs.setInt('$_keyPrefix$gid', page);
}

Future<void> clearReaderProgress(String gid) async {
  final prefs = await SharedPreferences.getInstance();
  await prefs.remove('$_keyPrefix$gid');
}
