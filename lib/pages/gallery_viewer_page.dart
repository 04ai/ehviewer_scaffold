import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:screen_brightness/screen_brightness.dart';
import 'package:battery_plus/battery_plus.dart';
import 'package:intl/intl.dart';

import '../src/rust/api.dart';
import '../src/rust/parser.dart';
import '../providers/read_settings_provider.dart';
import '../utils/haptics.dart';
import '../utils/reader_progress.dart';
import '../utils/gallery_detail_cache.dart';
import '../widgets/glass.dart';

/// Cache of viewer-page URL → real image URL, shared across page widgets so a
/// page that is disposed and re-created (no KeepAlive) doesn't re-fetch the
/// viewer HTML. Bounded so browsing many galleries can't grow it unboundedly.
final Map<String, String> _resolvedViewerUrls = {};
const int _kResolvedUrlCacheCap = 5000;

class GalleryViewerPage extends ConsumerStatefulWidget {
  final GalleryDetail detail;
  final int initialPage;

  const GalleryViewerPage({
    super.key,
    required this.detail,
    this.initialPage = 0,
  });

  @override
  ConsumerState<GalleryViewerPage> createState() => _GalleryViewerPageState();
}

class _GalleryViewerPageState extends ConsumerState<GalleryViewerPage> {
  late PageController _pageController;
  final ScrollController _listController = ScrollController();
  // Render-box keys for the vertical (webtoon) list, used to derive the
  // current page from scroll position (progress save + page indicator).
  final Map<int, GlobalKey> _verticalPageKeys = {};

  // ── All viewer-page URLs, lazily extended across gallery pages ──────────
  late List<String> _allImageUrls;
  int _currentApiPage = 0; // which gallery page (≈20 imgs each) last fetched
  bool _isLoadingMore = false;
  bool _hasMorePages = true;
  late int _totalPages;

  int _currentPage = 0;
  // Temporary slider value while the user is dragging (UI preview only; the
  // actual jump happens in onChangeEnd once enough pages are loaded).
  int? _sliderDragValue;
  bool _showOverlay = false;

  Timer? _autoFlipTimer;
  Timer? _clockTimer;
  Timer? _pageIndicatorTimer;
  double _pageIndicatorOpacity = 0.0;
  DateTime _currentTime = DateTime.now();

  final Battery _battery = Battery();
  int _batteryLevel = 100;

  bool _isDisposed = false;

  // ── Prefetch state: viewer URLs already prefetched + a single-flight guard ──
  final Set<String> _prefetchedViewerUrls = {};
  bool _isPrefetching = false;

  // ── Reading progress: debounced save of the current page ────────────────
  Timer? _progressSaveTimer;

  String get _gid => widget.detail.id.split('/').first;

  void _scheduleProgressSave(int page) {
    _progressSaveTimer?.cancel();
    _progressSaveTimer = Timer(const Duration(seconds: 2), () {
      saveReaderProgress(_gid, page);
    });
  }

  @override
  void initState() {
    super.initState();
    _currentPage = widget.initialPage;
    _allImageUrls = List<String>.from(widget.detail.imageUrls);
    _totalPages = widget.detail.totalPages;
    _hasMorePages = _allImageUrls.length < _totalPages;

    _pageController = PageController(initialPage: widget.initialPage);
    _listController.addListener(_handleVerticalScroll);

    WidgetsBinding.instance.addPostFrameCallback((_) {
      // Bug 3 fix: explicit jump so the correct page is shown regardless of
      // whether PageController honoured initialPage during the first layout.
      if (widget.initialPage > 0 && _pageController.hasClients) {
        _pageController.jumpToPage(widget.initialPage);
      }
      _applyScreenSettings();
      _startTimers();
    });
  }

  Future<void> _applyScreenSettings() async {
    final settings = ref.read(readSettingsProvider);
    if (settings.fullScreen) {
      SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersiveSticky);
    }
    if (settings.customBrightness >= 0) {
      try {
        await ScreenBrightness().setScreenBrightness(settings.customBrightness);
      } catch (e) {
        debugPrint(e.toString());
      }
    }
  }

  void _startTimers() {
    _clockTimer = Timer.periodic(const Duration(seconds: 1), (timer) {
      if (!_isDisposed && mounted) {
        setState(() => _currentTime = DateTime.now());
      }
    });
    _updateBattery();
    Timer.periodic(const Duration(minutes: 1), (timer) {
      if (_isDisposed) {
        timer.cancel();
      } else {
        _updateBattery();
      }
    });
    _resetAutoFlip();
  }

  Future<void> _updateBattery() async {
    final level = await _battery.batteryLevel;
    if (mounted) setState(() => _batteryLevel = level);
  }

  void _resetAutoFlip() {
    _autoFlipTimer?.cancel();
    final settings = ref.read(readSettingsProvider);
    if (settings.autoFlipInterval > 0) {
      _autoFlipTimer = Timer.periodic(
        Duration(seconds: settings.autoFlipInterval),
        (timer) {
          if (!_isDisposed && mounted) _flipNext();
        },
      );
    }
  }

  void _flipNext() {
    final settings = ref.read(readSettingsProvider);
    if (settings.readDirection == 2) {
      _listController.animateTo(
        _listController.offset + MediaQuery.of(context).size.height * 0.8,
        duration: const Duration(milliseconds: 300),
        curve: Curves.easeInOut,
      );
    } else {
      if (_currentPage < _allImageUrls.length - 1) {
        _pageController.nextPage(
          duration: const Duration(milliseconds: 300),
          curve: Curves.easeInOut,
        );
      }
    }
  }

  // ── Bug 2a: lazy-load more pages as user approaches the end ─────────────
  void _onPageChanged(int index) {
    Haptics.pageFlip();
    _onCurrentPageChanged(index);

    if (_hasMorePages && !_isLoadingMore && index >= _allImageUrls.length - 5) {
      _loadMoreImages();
    }

    _prefetchNextPages(index);
  }

  /// Shared by paged (PageView) and vertical (ListView) readers.
  void _onCurrentPageChanged(int index) {
    if (!mounted) return;
    setState(() {
      _currentPage = index;
      _pageIndicatorOpacity = 1.0;
    });
    _scheduleProgressSave(index);

    _pageIndicatorTimer?.cancel();
    _pageIndicatorTimer = Timer(const Duration(seconds: 2), () {
      if (mounted) setState(() => _pageIndicatorOpacity = 0.0);
    });
  }

  /// Vertical reader: pick the page that occupies the most space in the
  /// viewport, so mixed page heights / very tall images still report a
  /// sensible current page for the indicator and progress saving.
  void _handleVerticalScroll() {
    if (!mounted || !_listController.hasClients) return;
    final vpCtx = _listController.position.context.notificationContext;
    final vpBox = vpCtx?.findRenderObject();
    if (vpBox is! RenderBox) return;
    final viewportTop = vpBox.localToGlobal(Offset.zero).dy;
    final viewportBottom = viewportTop + vpBox.size.height;

    int best = _currentPage;
    double bestVisible = -1;
    for (final entry in _verticalPageKeys.entries) {
      final ctx = entry.value.currentContext;
      if (ctx == null) continue;
      final box = ctx.findRenderObject();
      if (box is! RenderBox || !box.attached) continue;
      try {
        final top = box.localToGlobal(Offset.zero).dy;
        final bottom = top + box.size.height;
        if (bottom <= viewportTop || top >= viewportBottom) continue;
        final visible = (bottom < viewportBottom ? bottom : viewportBottom) -
            (top > viewportTop ? top : viewportTop);
        if (visible > bestVisible) {
          bestVisible = visible;
          best = entry.key;
        }
      } catch (_) {
        // ignore detached/animating boxes
      }
    }
    if (best != _currentPage) {
      _onCurrentPageChanged(best);
    }
  }

  /// Resolve + download the next few pages in the background so swiping is
  /// instant (the bytes land in the Rust L1/L2 cache via getImage).
  /// Failures are swallowed: the page shows its own retry UI when built.
  void _prefetchNextPages(int currentIndex) {
    if (_isPrefetching) return;
    _isPrefetching = true;
    _prefetchRange(currentIndex + 1, currentIndex + 5)
        .whenComplete(() => _isPrefetching = false);
  }

  Future<void> _prefetchRange(int start, int endExclusive) async {
    const maxConcurrent = 3;
    final pending = <String>[];
    for (var i = start; i < endExclusive && i < _allImageUrls.length; i++) {
      final viewerUrl = _allImageUrls[i];
      if (_prefetchedViewerUrls.contains(viewerUrl)) continue;
      _prefetchedViewerUrls.add(viewerUrl);
      pending.add(viewerUrl);
    }
    for (var i = 0; i < pending.length; i += maxConcurrent) {
      await Future.wait(pending.skip(i).take(maxConcurrent).map(_prefetchOne));
    }
  }

  Future<void> _prefetchOne(String viewerUrl) async {
    try {
      final realUrl = await resolveImageUrl(viewerUrl: viewerUrl);
      _cacheResolvedUrl(viewerUrl, realUrl);
      await getImage(url: realUrl);
    } catch (_) {
      // ignore: handled by the page-level load/retry flow
    }
  }

  void _cacheResolvedUrl(String viewerUrl, String realUrl) {
    if (_resolvedViewerUrls.length >= _kResolvedUrlCacheCap) {
      _resolvedViewerUrls.clear();
    }
    _resolvedViewerUrls[viewerUrl] = realUrl;
  }

  Future<void> _loadMoreImages() async {
    if (_isLoadingMore || !_hasMorePages || !mounted) return;
    setState(() => _isLoadingMore = true);

    try {
      _currentApiPage++;
      // detail.id is formatted as "{gid}/{token}/" in fetchGalleryDetail call
      final idParts = widget.detail.id.split('/');
      final gid = idParts.isNotEmpty ? idParts[0] : widget.detail.id;
      final token = idParts.length > 1 ? idParts[1] : '';

      final pageDetail = await fetchGalleryPageCached(
        gid: gid,
        token: token,
        page: _currentApiPage,
      );

      if (!mounted) return;
      setState(() {
        if (pageDetail.imageUrls.isEmpty) {
          _hasMorePages = false;
        } else {
          _allImageUrls.addAll(pageDetail.imageUrls);
          // Correct the total-page count with the freshest parsed value: the
          // first load may have fallen back to "20" when the Length row was
          // missing, and every ?p=N page reports the same authoritative count.
          if (pageDetail.totalPages > _totalPages) {
            _totalPages = pageDetail.totalPages;
          }
          // Never let the progress range shrink below what is already loaded.
          if (_totalPages < _allImageUrls.length) {
            _totalPages = _allImageUrls.length;
          }
          if (_allImageUrls.length >= _totalPages) _hasMorePages = false;
        }
        _isLoadingMore = false;
      });
    } catch (e) {
      if (mounted) {
        setState(() {
          _isLoadingMore = false;
          _currentApiPage--; // allow retry on next scroll
        });
        debugPrint('Failed to load more reader images: $e');
      }
    }
  }

  /// Jump to [target] (0-indexed), lazily loading enough pages first so the
  /// PageView can actually reach it. Clamps to the last loaded page when the
  /// target is beyond everything that could be loaded.
  Future<void> _jumpToPage(int target) async {
    while (target >= _allImageUrls.length && _hasMorePages && mounted) {
      final before = _allImageUrls.length;
      await _loadMoreImages();
      if (!mounted || _allImageUrls.length <= before) break; // no progress
    }
    if (!mounted || !_pageController.hasClients) return;
    final clamped = target.clamp(0, _allImageUrls.length - 1);
    if (clamped == _currentPage) return;
    _pageController.jumpToPage(clamped);
  }

  @override
  void dispose() {
    _isDisposed = true;
    _autoFlipTimer?.cancel();
    _clockTimer?.cancel();
    _pageIndicatorTimer?.cancel();
    _progressSaveTimer?.cancel();
    // Final progress flush (fire-and-forget).
    saveReaderProgress(_gid, _currentPage);
    _pageController.dispose();
    _listController.dispose();
    // Restore the normal (non-immersive) system UI so the rest of the app is
    // not left with a transparent status bar / hidden nav bar.
    SystemChrome.setEnabledSystemUIMode(
      SystemUiMode.manual,
      overlays: [SystemUiOverlay.top, SystemUiOverlay.bottom],
    );
    ScreenBrightness().resetScreenBrightness().catchError((_) {});
    super.dispose();
  }

  void _toggleOverlay() => setState(() => _showOverlay = !_showOverlay);

  @override
  Widget build(BuildContext context) {
    final settings = ref.watch(readSettingsProvider);

    ref.listen(readSettingsProvider, (previous, next) {
      if (previous?.autoFlipInterval != next.autoFlipInterval) _resetAutoFlip();
      if (previous?.customBrightness != next.customBrightness) {
        if (next.customBrightness >= 0) {
          ScreenBrightness().setScreenBrightness(next.customBrightness).catchError((_) {});
        } else {
          ScreenBrightness().resetScreenBrightness().catchError((_) {});
        }
      }
    });

    return Scaffold(
      backgroundColor: Colors.black,
      body: Stack(
        children: [
          GestureDetector(
            onTap: _toggleOverlay,
            child: settings.readDirection == 2
                ? _buildListView(settings)
                : _buildPageView(settings),
          ),

          if (settings.showClock || settings.showBattery)
            _buildStatusIndicators(settings),

          if (_showOverlay) _buildTopOverlay(),
          if (_showOverlay) _buildBottomOverlay(),

          // Loading-more indicator (bottom-center pill)
          if (_isLoadingMore)
            Positioned(
              bottom: MediaQuery.of(context).padding.bottom + 88,
              left: 0,
              right: 0,
              child: const Center(
                child: Card(
                  color: Colors.black54,
                  child: Padding(
                    padding: EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        SizedBox(
                          width: 14, height: 14,
                          child: CircularProgressIndicator(
                              strokeWidth: 2, color: Colors.white70),
                        ),
                        SizedBox(width: 8),
                        Text('加载更多图片...',
                            style: TextStyle(color: Colors.white70, fontSize: 12)),
                      ],
                    ),
                  ),
                ),
              ),
            ),

          // Fading Page Indicator
          Positioned(
            top: MediaQuery.of(context).padding.top + 16,
            left: 0,
            right: 0,
            child: IgnorePointer(
              child: Center(
                child: AnimatedOpacity(
                  opacity: _pageIndicatorOpacity,
                  duration: const Duration(milliseconds: 300),
                  child: Container(
                    padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
                    decoration: BoxDecoration(
                      color: Colors.black54,
                      borderRadius: BorderRadius.circular(16),
                    ),
                    child: Text(
                      '${_currentPage + 1} / $_totalPages',
                      style: const TextStyle(color: Colors.white, fontSize: 14, fontWeight: FontWeight.bold),
                    ),
                  ),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildStatusIndicators(ReadSettings settings) {
    return Positioned(
      top: MediaQuery.of(context).padding.top + 8,
      right: 16,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
        decoration: BoxDecoration(
          color: Colors.black45,
          borderRadius: BorderRadius.circular(12),
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            if (settings.showClock)
              Text(DateFormat('HH:mm').format(_currentTime),
                  style: const TextStyle(color: Colors.white, fontSize: 12)),
            if (settings.showClock && settings.showBattery)
              const SizedBox(width: 8),
            if (settings.showBattery) ...[
              Text('$_batteryLevel%',
                  style: const TextStyle(color: Colors.white, fontSize: 12)),
              const SizedBox(width: 4),
              const Icon(Icons.battery_full, color: Colors.white, size: 14),
            ],
          ],
        ),
      ),
    );
  }

  Widget _buildPageView(ReadSettings settings) {
    return PageView.builder(
      controller: _pageController,
      reverse: settings.readDirection == 1,
      itemCount: _allImageUrls.length,
      onPageChanged: _onPageChanged,
      itemBuilder: (context, index) => _ViewerImagePage(
        viewerUrl: _allImageUrls[index],
        pageIndex: index,
        onLoaded: _onPageLoaded,
      ),
    );
  }

  Widget _buildListView(ReadSettings settings) {
    return ListView.separated(
      controller: _listController,
      itemCount: _allImageUrls.length,
      separatorBuilder: (_, __) => SizedBox(height: settings.pageInterval),
      itemBuilder: (context, index) => KeyedSubtree(
        key: _verticalPageKeys.putIfAbsent(index, () => GlobalKey()),
        child: _ViewerImagePage(
          viewerUrl: _allImageUrls[index],
          pageIndex: index,
          onLoaded: _onPageLoaded,
        ),
      ),
    );
  }

  void _onPageLoaded(int pageIndex) {
    if (!mounted) return;
    _prefetchNextPages(pageIndex);
  }

  Widget _buildTopOverlay() {
    return Positioned(
      top: 0, left: 0, right: 0,
      child: GlassContainer(
        tint: Colors.black,
        tintOpacity: 0.45,
        // 阅读器浮层不需要描边（默认白色高光边框在浅色主题下会变成突兀的四边框）。
        border: Border.all(color: Colors.transparent),
        showHighlight: false,
        padding: EdgeInsets.only(top: MediaQuery.of(context).padding.top),
        child: Row(
          children: [
            IconButton(
              icon: const Icon(Icons.arrow_back, color: Colors.white),
              onPressed: () => Navigator.pop(context),
            ),
            Expanded(
              child: Text(
                widget.detail.title,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(color: Colors.white, fontSize: 16),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildBottomOverlay() {
    final settings = ref.read(readSettingsProvider);
    final isVertical = settings.readDirection == 2;
    // Progress range spans the FULL gallery (actual total pages), not just the
    // pages lazily loaded so far, so the thumb position matches real progress.
    final total = _totalPages > _allImageUrls.length
        ? _totalPages
        : _allImageUrls.length;
    final sliderMax = (total > 0 ? total - 1 : 0).toDouble();
    final sliderValue = (_sliderDragValue ?? _currentPage)
        .toDouble()
        .clamp(0.0, sliderMax);

    return Positioned(
      bottom: 0, left: 0, right: 0,
      child: GlassContainer(
        tint: Colors.black,
        tintOpacity: 0.45,
        border: Border.all(color: Colors.transparent),
        showHighlight: false,
        padding: EdgeInsets.only(
          bottom: MediaQuery.of(context).padding.bottom + 16,
          top: 16, left: 24, right: 24,
        ),
        child: Row(
          children: [
            Text(
              _allImageUrls.isEmpty ? '0' : '${_currentPage + 1}',
              style: const TextStyle(color: Colors.white, fontWeight: FontWeight.bold),
            ),
            Expanded(
              child: Slider(
                value: sliderValue,
                min: 0,
                max: sliderMax,
                activeColor: Colors.blue,
                inactiveColor: Colors.white24,
                // Vertical (webtoon) mode has no paged slider.
                onChanged: (_allImageUrls.isEmpty || isVertical)
                    ? null
                    : (val) => setState(() => _sliderDragValue = val.toInt()),
                onChangeEnd: isVertical
                    ? null
                    : (val) {
                        setState(() => _sliderDragValue = null);
                        _jumpToPage(val.toInt());
                      },
              ),
            ),
            Text(
              '$total',
              style: const TextStyle(color: Colors.white54),
            ),
          ],
        ),
      ),
    );
  }
}

// ════════════════════════════════════════════════════════════════════════════
// Individual image page
// Bug 2b fix: pages are NOT kept alive (KeepAlive caused unbounded memory on
//             large galleries). Off-screen pages are disposed; re-visiting
//             re-reads the Rust L1/L2 image cache and the in-memory
//             viewer-URL cache instead of hitting the network again.
// Bug 5  fix: getImage() goes through the Rust HTTP client, which honours
//             the configureNetwork() EH/Ex hosts mapping.
// ════════════════════════════════════════════════════════════════════════════
class _ViewerImagePage extends StatefulWidget {
  final String viewerUrl;
  final int pageIndex;
  final void Function(int pageIndex)? onLoaded;

  const _ViewerImagePage({
    required this.viewerUrl,
    required this.pageIndex,
    this.onLoaded,
  });

  @override
  State<_ViewerImagePage> createState() => _ViewerImagePageState();
}

class _ViewerImagePageState extends State<_ViewerImagePage> {

  Uint8List? _imageBytes;
  bool _isResolvingUrl = true;
  bool _isDownloadingImage = false;
  // Real download progress (0.0-1.0) reported by the Rust downloader; only
  // meaningful while [_isDownloadingImage] is true.
  double _downloadProgress = 0.0;
  String? _error;
  String? _lastError;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void didUpdateWidget(covariant _ViewerImagePage oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.viewerUrl != widget.viewerUrl) _load();
  }

  Future<void> _load() async {
    if (!mounted) return;
    setState(() {
      _isResolvingUrl = true;
      _isDownloadingImage = false;
      _downloadProgress = 0.0;
      _imageBytes = null;
      _error = null;
      _lastError = null;
    });

    // First attempt (uses the cached resolved URL when available).
    if (await _tryDownload() || !mounted) return;

    // Auto-retry once with a FRESH viewer-URL resolution: the failure may be
    // a stale keystamp inside the cached real URL (EH image links expire),
    // which only a re-resolve can fix. Rust already retried transient
    // network errors, so this second attempt targets URL staleness.
    _resolvedViewerUrls.remove(widget.viewerUrl);
    if (!mounted) return;
    if (await _tryDownload() || !mounted) return;

    setState(() {
      _error = _lastError ?? '加载失败';
      _isResolvingUrl = false;
      _isDownloadingImage = false;
    });
  }

  /// One full resolve+download pass. Returns true on success; on failure the
  /// error message is stashed in [_lastError] and false is returned.
  Future<bool> _tryDownload() async {
    try {
      // Step 1: resolve viewer page URL → real image CDN URL (cached so a
      // re-created page after dispose doesn't hit the network again).
      final cached = _resolvedViewerUrls[widget.viewerUrl];
      final url = cached ?? await resolveImageUrl(viewerUrl: widget.viewerUrl);
      if (cached == null) {
        if (_resolvedViewerUrls.length >= _kResolvedUrlCacheCap) {
          _resolvedViewerUrls.clear();
        }
        _resolvedViewerUrls[widget.viewerUrl] = url;
      }
      if (!mounted) return false;

      setState(() {
        _isResolvingUrl = false;
        _isDownloadingImage = true;
        _downloadProgress = 0.0;
      });

      // Step 2: download via Rust (respects EH hosts, uses L1+L2 cache).
      // Progress is streamed back chunk-by-chunk so the loading ring fills up
      // with the real download percentage; cache hits report instant 100%.
      final bytes = await getImageWithProgress(
        url: url,
        onProgress: (downloaded, total) {
          if (!mounted) return;
          final p = (total != null && total > BigInt.zero)
              ? downloaded.toDouble() / total.toDouble()
              : 0.0;
          // Throttle rebuilds to ≥1% steps (still always land on 100%).
          if (p < 1.0 && p - _downloadProgress < 0.01) return;
          setState(() => _downloadProgress = p.clamp(0.0, 1.0));
        },
      );
      if (!mounted) return false;

      setState(() {
        _imageBytes = bytes;
        _isDownloadingImage = false;
        _downloadProgress = 1.0;
      });

      widget.onLoaded?.call(widget.pageIndex);
      return true;
    } catch (e) {
      if (mounted) _lastError = e.toString();
      return false;
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_isResolvingUrl) {
      return _loadingWidget();
    }

    if (_isDownloadingImage) {
      return _loadingWidget();
    }

    if (_error != null || _imageBytes == null) {
      final errLine = (_error ?? '未知错误')
          .split('\n')
          .firstWhere((l) => l.trim().isNotEmpty, orElse: () => '未知错误');
      final brief = errLine.length > 100 ? '${errLine.substring(0, 100)}…' : errLine;
      return SizedBox(
        height: MediaQuery.of(context).size.height * 0.8,
        child: Center(
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 24),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                const Icon(Icons.broken_image, color: Colors.white54, size: 48),
                const SizedBox(height: 16),
                const Text('加载失败', style: TextStyle(color: Colors.white)),
                const SizedBox(height: 8),
                Text(
                  '图片链接可能已过期或网络连接不稳定，请检查网络后重试。\n$brief',
                  textAlign: TextAlign.center,
                  style: const TextStyle(color: Colors.white54, fontSize: 12),
                  maxLines: 4,
                  overflow: TextOverflow.ellipsis,
                ),
                const SizedBox(height: 16),
                ElevatedButton(onPressed: _load, child: const Text('重试')),
              ],
            ),
          ),
        ),
      );
    }

    return InteractiveViewer(
      minScale: 1.0,
      maxScale: 4.0,
      child: Center(
        child: Image.memory(
          _imageBytes!,
          fit: BoxFit.contain,
          gaplessPlayback: true,
          errorBuilder: (_, __, ___) => Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Icon(Icons.error, color: Colors.red, size: 48),
              const SizedBox(height: 8),
              Text('显示失败 第 ${widget.pageIndex + 1} 页',
                  textAlign: TextAlign.center,
                  style: const TextStyle(color: Colors.white54, fontSize: 12)),
            ],
          ),
        ),
      ),
    );
  }

  Widget _loadingWidget() {
    final downloading = _isDownloadingImage;
    return SizedBox(
      height: MediaQuery.of(context).size.height * 0.8,
      child: Center(
        child: SizedBox(
          width: 72,
          height: 72,
          child: Stack(
            alignment: Alignment.center,
            children: [
              // Connecting/resolving: indeterminate spinner. Downloading:
              // determinate ring that fills with the real progress (half ring
              // = 50%, full ring = done → the image itself replaces it).
              CircularProgressIndicator(
                value: downloading ? _downloadProgress : null,
                strokeWidth: 3,
                color: Colors.white54,
              ),
              Text(
                downloading
                    ? '${(_downloadProgress * 100).round()}%'
                    : '${widget.pageIndex + 1}',
                style: const TextStyle(
                    color: Colors.white70, fontSize: 16, fontWeight: FontWeight.bold),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
