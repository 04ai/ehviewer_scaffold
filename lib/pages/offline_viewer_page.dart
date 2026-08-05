import 'dart:async';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:path_provider/path_provider.dart';
import 'package:screen_brightness/screen_brightness.dart';
import 'package:battery_plus/battery_plus.dart';
import 'package:intl/intl.dart';
import '../providers/read_settings_provider.dart';
import '../src/rust/api.dart';
import '../utils/haptics.dart';
import '../utils/reader_progress.dart';

/// Offline reader for galleries downloaded by the Rust downloader.
/// Images live under `{tempDir}/eh_downloads/{gid}/page_N.{ext}` and are read
/// straight from disk, so no network or Rust FFI is needed. It mirrors the
/// online reader's settings: fullscreen, brightness, reading direction
/// (LTR/RTL/vertical), auto-flip, and clock/battery indicators.
class OfflineViewerPage extends ConsumerStatefulWidget {
  final String gid;
  final String title;

  const OfflineViewerPage({super.key, required this.gid, required this.title});

  @override
  ConsumerState<OfflineViewerPage> createState() => _OfflineViewerPageState();
}

class _OfflineViewerPageState extends ConsumerState<OfflineViewerPage> {
  List<File> _files = const [];
  bool _isLoading = true;
  late PageController _pageController;
  final ScrollController _listController = ScrollController();
  // Render-box keys for the vertical (webtoon) list, used to derive the
  // current page from scroll position (progress save + page indicator).
  final Map<int, GlobalKey> _verticalPageKeys = {};

  int _currentPage = 0;
  bool _showOverlay = true;
  bool _isDisposed = false;

  Timer? _autoFlipTimer;
  Timer? _clockTimer;
  Timer? _pageIndicatorTimer;
  double _pageIndicatorOpacity = 0.0;
  DateTime _currentTime = DateTime.now();

  final Battery _battery = Battery();
  int _batteryLevel = 100;

  Timer? _progressSaveTimer;
  int _restorePage = 0;

  @override
  void initState() {
    super.initState();
    _load();
    _pageController = PageController();
    _listController.addListener(_handleVerticalScroll);

    WidgetsBinding.instance.addPostFrameCallback((_) {
      _applyScreenSettings();
      _startTimers();
    });
  }

  Future<void> _load() async {
    final base = await getDownloadDir() ??
        '${(await getTemporaryDirectory()).path}/eh_downloads';
    final galleryDir = Directory('$base/${widget.gid}');
    final files = <File>[];
    if (galleryDir.existsSync()) {
      files.addAll(galleryDir.listSync().whereType<File>());
      files.sort((a, b) {
        int pageNum(String path) {
          final match = RegExp(r'page_(\d+)').firstMatch(File(path).uri.pathSegments.last);
          return match != null ? int.parse(match.group(1)!) : 0;
        }

        return pageNum(a.path).compareTo(pageNum(b.path));
      });
    }

    // Resume where the user left off (logical index, direction-independent).
    final saved = await getReaderProgress(widget.gid);
    int restore = 0;
    if (saved != null && saved > 0 && saved < files.length) {
      restore = saved;
    }

    if (!mounted) return;
    setState(() {
      _files = files;
      _isLoading = false;
      _restorePage = restore;
    });
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_restorePage > 0 && _pageController.hasClients) {
        _pageController.jumpToPage(_restorePage);
      }
      if (_restorePage > 0) setState(() => _currentPage = _restorePage);
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
      if (_listController.hasClients) {
        _listController.animateTo(
          _listController.offset + MediaQuery.of(context).size.height * 0.8,
          duration: const Duration(milliseconds: 300),
          curve: Curves.easeInOut,
        );
      }
    } else {
      if (_currentPage < _files.length - 1) {
        _pageController.nextPage(
          duration: const Duration(milliseconds: 300),
          curve: Curves.easeInOut,
        );
      }
    }
  }

  void _onPageChanged(int index) {
    Haptics.pageFlip();
    _onCurrentPageChanged(index);
  }

  /// Shared by paged (PageView) and vertical (ListView) readers.
  void _onCurrentPageChanged(int index) {
    if (!mounted) return;
    setState(() {
      _currentPage = index;
      _pageIndicatorOpacity = 1.0;
    });
    _progressSaveTimer?.cancel();
    _progressSaveTimer = Timer(const Duration(seconds: 2), () {
      saveReaderProgress(widget.gid, index);
    });
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

  @override
  void dispose() {
    _isDisposed = true;
    _autoFlipTimer?.cancel();
    _clockTimer?.cancel();
    _pageIndicatorTimer?.cancel();
    _progressSaveTimer?.cancel();
    // Final progress flush (fire-and-forget).
    saveReaderProgress(widget.gid, _currentPage);
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
      body: _isLoading
          ? const Center(child: CircularProgressIndicator())
          : _files.isEmpty
              ? const Center(
                  child: Text('没有找到已下载的文件', style: TextStyle(color: Colors.white70)),
                )
              : _buildViewer(settings),
    );
  }

  Widget _buildViewer(ReadSettings settings) {
    final total = _files.length;
    return Stack(
      children: [
        GestureDetector(
          behavior: HitTestBehavior.opaque,
          onTap: _toggleOverlay,
          child: settings.readDirection == 2
              ? _buildListView()
              : PageView.builder(
                  controller: _pageController,
                  reverse: settings.readDirection == 1,
                  itemCount: total,
                  onPageChanged: _onPageChanged,
                  itemBuilder: (context, index) => _buildImagePage(_files[index]),
                ),
        ),
        if (settings.showClock || settings.showBattery)
          _buildStatusIndicators(settings),
        if (_showOverlay) _buildTopOverlay(total),
        if (_showOverlay) _buildBottomOverlay(total),
        // Fading page indicator pill
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
                    '${_currentPage + 1} / $total',
                    style: const TextStyle(color: Colors.white, fontSize: 14, fontWeight: FontWeight.bold),
                  ),
                ),
              ),
            ),
          ),
        ),
      ],
    );
  }

  Widget _buildListView() {
    return ListView.separated(
      controller: _listController,
      itemCount: _files.length,
      separatorBuilder: (_, __) => SizedBox(height: ref.read(readSettingsProvider).pageInterval),
      itemBuilder: (context, index) => KeyedSubtree(
        key: _verticalPageKeys.putIfAbsent(index, () => GlobalKey()),
        child: _buildImagePage(_files[index]),
      ),
    );
  }

  Widget _buildImagePage(File file) {
    return InteractiveViewer(
      minScale: 1,
      maxScale: 5,
      child: Center(
        child: Image.file(
          file,
          fit: BoxFit.contain,
          errorBuilder: (context, error, stackTrace) => const Icon(
            Icons.broken_image,
            color: Colors.white38,
            size: 64,
          ),
        ),
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

  Widget _buildTopOverlay(int total) {
    return Positioned(
      top: 0, left: 0, right: 0,
      child: Container(
        color: Colors.black.withOpacity(0.7),
        padding: EdgeInsets.only(top: MediaQuery.of(context).padding.top),
        child: Row(
          children: [
            IconButton(
              icon: const Icon(Icons.arrow_back, color: Colors.white),
              onPressed: () => Navigator.pop(context),
            ),
            Expanded(
              child: Text(
                widget.title,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(color: Colors.white, fontSize: 15),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildBottomOverlay(int total) {
    return Positioned(
      bottom: 0, left: 0, right: 0,
      child: Container(
        color: Colors.black.withOpacity(0.7),
        padding: EdgeInsets.only(bottom: MediaQuery.of(context).padding.bottom),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
          child: Text(
            '${_currentPage + 1} / $total',
            style: const TextStyle(color: Colors.white, fontSize: 13),
          ),
        ),
      ),
    );
  }
}
