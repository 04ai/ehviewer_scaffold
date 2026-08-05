import '../src/rust/api.dart';
import '../src/rust/parser.dart';

/// In-memory cache for parsed gallery details so reopening the same gallery
/// (back from the list, from history/favorites, ...) doesn't re-fetch the
/// page from the network. Entries expire after a short TTL; the detail page
/// exposes a manual refresh for anyone who wants fresh data.
class _CacheEntry {
  final GalleryDetail detail;
  final DateTime fetchedAt;

  _CacheEntry(this.detail, this.fetchedAt);
}

final Map<String, _CacheEntry> _cache = {};

const Duration _ttl = Duration(minutes: 15);
const int _maxEntries = 200;

/// Returns the cached detail immediately if a fresh entry exists, else null.
/// Lets the detail page render instantly on reopen without a spinner.
GalleryDetail? peekGalleryDetailCached(String id) {
  final entry = _cache[id];
  if (entry != null && DateTime.now().difference(entry.fetchedAt) < _ttl) {
    return entry.detail;
  }
  return null;
}

/// Returns cached detail when fresh, otherwise fetches and caches it.
/// [force] bypasses the cache (used by the manual refresh action).
Future<GalleryDetail> fetchGalleryDetailCached({
  required String id,
  bool force = false,
}) async {
  if (!force) {
    final cached = peekGalleryDetailCached(id);
    if (cached != null) return cached;
  }
  final detail = await fetchGalleryDetail(id: id);
  // Keep the cache bounded: drop expired entries first, then fall back to a
  // hard clear so browsing many galleries can't grow it unboundedly.
  if (_cache.length >= _maxEntries) {
    _cache.removeWhere((_, e) => DateTime.now().difference(e.fetchedAt) > _ttl);
    if (_cache.length >= _maxEntries) _cache.clear();
  }
  _cache[id] = _CacheEntry(detail, DateTime.now());
  return detail;
}

/// Per-gallery-page (?p=N) cache shared by the thumbnails grid and the
/// reader's lazy loading, so paging back/forth or reopening doesn't re-fetch
/// the same ?p=N HTML over and over.
final Map<String, GalleryDetail> _pageCache = {};
const int _maxPageEntries = 400;

String galleryPageCacheKey({
  required String gid,
  required String token,
  required int page,
}) =>
    '$gid/$token/p$page';

/// Returns the cached page when available, otherwise fetches and caches it.
Future<GalleryDetail> fetchGalleryPageCached({
  required String gid,
  required String token,
  required int page,
  bool force = false,
}) async {
  final key = galleryPageCacheKey(gid: gid, token: token, page: page);
  if (!force) {
    final cached = _pageCache[key];
    if (cached != null) return cached;
  }
  final detail = await fetchGalleryPage(id: gid, token: token, page: page);
  if (_pageCache.length >= _maxPageEntries) _pageCache.clear();
  _pageCache[key] = detail;
  return detail;
}
