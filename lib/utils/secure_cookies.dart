import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// Cookie keys stored encrypted: session secrets (ipb_pass_hash / igneous)
/// must not sit in plaintext SharedPreferences.
const kCookieMemberId = 'cookie_ipb_member_id';
const kCookiePassHash = 'cookie_ipb_pass_hash';
const kCookieIgneous = 'cookie_igneous';
const kCookieSk = 'cookie_sk';

const List<String> kCookieKeys = [
  kCookieMemberId,
  kCookiePassHash,
  kCookieIgneous,
  kCookieSk,
];

/// Values are encrypted with a Keystore-backed AES key by the plugin.
const FlutterSecureStorage _storage = FlutterSecureStorage();

Future<String?> readCookie(String key) => _storage.read(key: key);

Future<void> writeCookie(String key, String value) =>
    _storage.write(key: key, value: value);

Future<void> deleteCookie(String key) => _storage.delete(key: key);

/// Read all saved cookies at once (only non-empty values).
Future<Map<String, String>> readAllCookies() async {
  final result = <String, String>{};
  for (final key in kCookieKeys) {
    final value = await readCookie(key);
    if (value != null && value.isNotEmpty) result[key] = value;
  }
  return result;
}

/// One-time migration from the old plaintext SharedPreferences keys. Safe to
/// call on every start: it is a no-op once the legacy keys are gone.
Future<void> migrateLegacyCookies() async {
  final prefs = await SharedPreferences.getInstance();
  for (final key in kCookieKeys) {
    final legacy = prefs.getString(key);
    if (legacy != null && legacy.isNotEmpty) {
      final current = await readCookie(key);
      if (current == null) {
        await writeCookie(key, legacy);
      }
      await prefs.remove(key);
    }
  }
}
