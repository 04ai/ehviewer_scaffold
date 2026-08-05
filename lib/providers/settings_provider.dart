import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:path_provider/path_provider.dart';
import '../utils/haptics.dart';
import '../src/rust/api.dart';

// --- Appearance Settings ---

class AppearanceSettings extends ChangeNotifier {
  bool followSystemDark = true;
  bool amoledBlack = false;
  bool pixelShift = true;
  bool glassEffect = true;
  String themeColor = '纯净白';
  bool showJpnTitle = false;
  bool showTagTranslation = false;
  bool autoClearCache = true;
  int autoClearCacheDays = 7;
  bool enableHaptics = true;

  AppearanceSettings() {
    _load();
  }

  Future<void> _load() async {
    final prefs = await SharedPreferences.getInstance();
    followSystemDark = prefs.getBool('appearance_system_dark') ?? true;
    amoledBlack = prefs.getBool('appearance_amoled_black') ?? false;
    pixelShift = prefs.getBool('appearance_pixel_shift') ?? true;
    glassEffect = prefs.getBool('appearance_glass_effect') ?? true;
    themeColor = prefs.getString('appearance_theme_color') ?? '纯净白';
    showJpnTitle = prefs.getBool('appearance_show_jpn_title') ?? false;
    showTagTranslation = prefs.getBool('appearance_show_tag_translation') ?? false;
    autoClearCache = prefs.getBool('appearance_auto_clear_cache') ?? true;
    autoClearCacheDays = prefs.getInt('appearance_auto_clear_cache_days') ?? 7;
    enableHaptics = prefs.getBool('appearance_enable_haptics') ?? true;
    Haptics.enabled = enableHaptics;
    notifyListeners();
  }

  Future<void> setSystemDark(bool val) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool('appearance_system_dark', val);
    followSystemDark = val;
    notifyListeners();
  }

  Future<void> setAmoledBlack(bool val) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool('appearance_amoled_black', val);
    amoledBlack = val;
    notifyListeners();
  }

  Future<void> setPixelShift(bool val) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool('appearance_pixel_shift', val);
    pixelShift = val;
    notifyListeners();
  }

  Future<void> setGlassEffect(bool val) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool('appearance_glass_effect', val);
    glassEffect = val;
    notifyListeners();
  }

  Future<void> setEnableHaptics(bool val) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool('appearance_enable_haptics', val);
    enableHaptics = val;
    Haptics.enabled = val;
    notifyListeners();
  }

  Future<void> setAutoClearCache(bool val) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool('appearance_auto_clear_cache', val);
    autoClearCache = val;
    notifyListeners();
  }

  Future<void> setAutoClearCacheDays(int val) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setInt('appearance_auto_clear_cache_days', val);
    autoClearCacheDays = val;
    notifyListeners();
  }

  Future<void> setThemeColor(String val) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString('appearance_theme_color', val);
    themeColor = val;
    notifyListeners();
  }

  /// Re-evaluate the theme (e.g. when the system brightness changes while
  /// following the system dark mode).
  void refreshTheme() {
    notifyListeners();
  }

  Future<void> setShowJpnTitle(bool val) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool('appearance_show_jpn_title', val);
    showJpnTitle = val;
    notifyListeners();
  }

  Future<void> setShowTagTranslation(bool val) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool('appearance_show_tag_translation', val);
    showTagTranslation = val;
    notifyListeners();
  }

  ThemeData get themeData {
    Color seed;
    switch (themeColor) {
      case '纯净白': seed = const Color(0xFFFAFAFA); break;
      case '樱花粉': seed = const Color(0xFFF48FB1); break;
      case '夜空蓝': seed = const Color(0xFF1E88E5); break;
      case '极客黑': seed = const Color(0xFF212121); break;
      case '默认 (紫)':
      default: seed = const Color(0xFF6750A4); break;
    }

    // 明暗优先级（从高到低）：
    //  1. AMOLED 黑开关：开了必然深色 + 纯黑（最高优先级，覆盖一切）
    //  2. 主题色强制：极客黑 → 深色；纯净白 → 浅色
    //  3. 跟随系统开关 → 系统明暗；否则浅色
    final bool forceDark = themeColor == '极客黑' || amoledBlack;
    final bool forceLight = themeColor == '纯净白' && !amoledBlack;
    final Brightness baseBrightness;
    if (forceDark) {
      baseBrightness = Brightness.dark;
    } else if (forceLight) {
      baseBrightness = Brightness.light;
    } else {
      baseBrightness = followSystemDark
          ? WidgetsBinding.instance.platformDispatcher.platformBrightness
          : Brightness.light;
    }
    final isDark = baseBrightness == Brightness.dark;

    // Premium dark mode: Deep OLED black or very dark slate.
    // AMOLED 黑 / 极客黑 均强制纯黑。
    final darkScaffold = (amoledBlack || forceDark) ? Colors.black : const Color(0xFF121212);
    final darkSurface = (amoledBlack || forceDark) ? Colors.black : const Color(0xFF1E1E1E);
    
    // Premium light mode
    final lightScaffold = themeColor == '纯净白' ? Colors.white : const Color(0xFFF7F7F9);
    const lightSurface = Colors.white;

    return ThemeData(
      useMaterial3: true,
      brightness: baseBrightness,
      colorScheme: ColorScheme.fromSeed(
        seedColor: seed,
        brightness: baseBrightness,
        surface: isDark ? darkSurface : lightSurface,
      ),
      scaffoldBackgroundColor: isDark ? darkScaffold : lightScaffold,
      cardColor: isDark ? darkSurface : lightSurface,
      splashFactory: InkSparkle.splashFactory,
      pageTransitionsTheme: const PageTransitionsTheme(
        builders: {
          TargetPlatform.android: CupertinoPageTransitionsBuilder(),
          TargetPlatform.iOS: CupertinoPageTransitionsBuilder(),
          TargetPlatform.windows: ZoomPageTransitionsBuilder(),
          TargetPlatform.macOS: CupertinoPageTransitionsBuilder(),
        },
      ),
      appBarTheme: AppBarTheme(
        backgroundColor: isDark ? darkSurface : lightSurface,
        foregroundColor: isDark ? Colors.white : Colors.black87,
        elevation: 0,
        scrolledUnderElevation: 0,
        centerTitle: false,
      ),
      dividerColor: isDark ? Colors.white12 : Colors.black12,
      dividerTheme: DividerThemeData(
        color: isDark ? Colors.white12 : Colors.black12,
        space: 1,
        thickness: 1,
      ),
      iconTheme: IconThemeData(
        color: isDark ? Colors.white : Colors.black87,
      ),
      textTheme: TextTheme(
        bodyLarge: TextStyle(color: isDark ? Colors.white : Colors.black87),
        bodyMedium: TextStyle(color: isDark ? Colors.white70 : Colors.black54),
        titleLarge: TextStyle(
          color: isDark ? Colors.white : Colors.black87,
          fontWeight: FontWeight.w600,
        ),
      ),
      // ── Component-level polish: rounded, soft, consistent ───────────────
      cardTheme: CardTheme(
        elevation: isDark ? 1.5 : 0.5,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
        clipBehavior: Clip.antiAlias,
        margin: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
        color: isDark ? darkSurface : lightSurface,
        surfaceTintColor: Colors.transparent,
      ),
      dialogTheme: DialogTheme(
        backgroundColor: isDark ? (amoledBlack ? Colors.black : const Color(0xFF1E1E1E)) : Colors.white,
        surfaceTintColor: Colors.transparent,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(24)),
        elevation: 8,
      ),
      snackBarTheme: SnackBarThemeData(
        behavior: SnackBarBehavior.floating,
        backgroundColor: isDark ? (amoledBlack ? Colors.black : const Color(0xFF2C2C2E)) : const Color(0xFF323232),
        contentTextStyle: const TextStyle(color: Colors.white),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
        elevation: 4,
        insetPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      ),
      bottomSheetTheme: const BottomSheetThemeData(
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
        ),
        surfaceTintColor: Colors.transparent,
        backgroundColor: Colors.transparent,
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: isDark ? Colors.white10 : Colors.black.withOpacity(0.04),
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(16),
          borderSide: BorderSide.none,
        ),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(16),
          borderSide: BorderSide.none,
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(16),
          borderSide: BorderSide.none,
        ),
        contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
      ),
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
          padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
        ),
      ),
      textButtonTheme: TextButtonThemeData(
        style: TextButton.styleFrom(
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
        ),
      ),
      outlinedButtonTheme: OutlinedButtonThemeData(
        style: OutlinedButton.styleFrom(
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
          padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
        ),
      ),
      iconButtonTheme: IconButtonThemeData(
        style: IconButton.styleFrom(
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
        ),
      ),
      listTileTheme: ListTileThemeData(
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
        iconColor: isDark ? Colors.white70 : Colors.black54,
        textColor: isDark ? Colors.white : Colors.black87,
      ),
      switchTheme: SwitchThemeData(
        thumbColor: WidgetStateProperty.resolveWith(
          (states) => states.contains(WidgetState.selected)
              ? const Color(0xFFFFFFFF)
              : null,
        ),
        trackColor: WidgetStateProperty.resolveWith(
          (states) => states.contains(WidgetState.selected)
              ? null
              : isDark ? Colors.white24 : Colors.black26,
        ),
      ),
      progressIndicatorTheme: ProgressIndicatorThemeData(
        color: seed,
        linearTrackColor: isDark ? Colors.white12 : Colors.black12,
      ),
      floatingActionButtonTheme: FloatingActionButtonThemeData(
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
        elevation: 4,
        highlightElevation: 8,
        extendedPadding: const EdgeInsets.symmetric(horizontal: 20, vertical: 14),
      ),
      tooltipTheme: TooltipThemeData(
        decoration: BoxDecoration(
          color: isDark ? (amoledBlack ? Colors.black : const Color(0xFF2C2C2E)) : const Color(0xFF323232),
          borderRadius: BorderRadius.circular(8),
        ),
        textStyle: const TextStyle(color: Colors.white, fontSize: 12),
        waitDuration: const Duration(milliseconds: 400),
      ),
      scrollbarTheme: ScrollbarThemeData(
        thumbColor: WidgetStateProperty.resolveWith(
          (states) => isDark ? Colors.white24 : Colors.black26,
        ),
        radius: const Radius.circular(8),
        thickness: const WidgetStatePropertyAll(5),
        thumbVisibility: const WidgetStatePropertyAll(false),
      ),
      chipTheme: ChipThemeData(
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
        side: BorderSide.none,
      ),
      tabBarTheme: TabBarTheme(
        labelColor: isDark ? Colors.white : Colors.black87,
        unselectedLabelColor: isDark ? Colors.white60 : Colors.black45,
        indicatorSize: TabBarIndicatorSize.tab,
      ),
    );
  }
}

final appearanceProvider = ChangeNotifierProvider<AppearanceSettings>((ref) {
  return AppearanceSettings();
});

// --- Security Settings ---

class SecuritySettings extends ChangeNotifier {
  bool enableBiometrics = false;
  bool isLocked = false;

  SecuritySettings() {
    _load();
  }

  Future<void> _load() async {
    final prefs = await SharedPreferences.getInstance();
    enableBiometrics = prefs.getBool('security_enable_biometrics') ?? false;
    // Initial state depends on biometrics, but usually starts locked if enabled
    isLocked = enableBiometrics;
    notifyListeners();
  }

  Future<void> setBiometrics(bool val) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool('security_enable_biometrics', val);
    enableBiometrics = val;
    notifyListeners();
  }
  
  void setLocked(bool val) {
    if (enableBiometrics || val == false) {
      isLocked = val;
      notifyListeners();
    }
  }
}

final securityProvider = ChangeNotifierProvider<SecuritySettings>((ref) {
  return SecuritySettings();
});

// --- Download Settings ---

class DownloadSettings extends ChangeNotifier {
  int concurrentDownloads = 2;
  String downloadPath = '';

  DownloadSettings() {
    _load();
  }

  Future<void> _load() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      concurrentDownloads = prefs.getInt('download_concurrent') ?? 2;
      // The legacy default pointed at public storage, which modern Android no
      // longer allows writing to by path; fall back to the app cache dir that
      // the Rust downloader already uses by default.
      final tempDir = await getTemporaryDirectory();
      final defaultPath = '${tempDir.path}/eh_downloads';
      final stored = prefs.getString('download_path');
      downloadPath =
          (stored == null || stored.isEmpty || stored == '/storage/emulated/0/Download/EHviewer')
              ? defaultPath
              : stored;
      notifyListeners();
      // Push the persisted values into the Rust backend so they take effect
      // across restarts (silently ignored if the FFI isn't up yet).
      _applyToBackend();
    } catch (_) {
      // ignore: settings simply keep their defaults
    }
  }

  Future<void> _applyToBackend() async {
    try {
      await setDownloadConcurrency(n: concurrentDownloads);
      if (downloadPath.isNotEmpty) {
        await setDownloadDir(path: downloadPath);
      }
    } catch (_) {
      // ignore: FFI not ready (e.g. during widget tests)
    }
  }

  Future<void> setConcurrent(int val) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setInt('download_concurrent', val);
    concurrentDownloads = val;
    notifyListeners();
    try {
      await setDownloadConcurrency(n: val);
    } catch (_) {
      // ignore: FFI not ready
    }
  }

  Future<void> setDownloadPath(String val) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString('download_path', val);
    downloadPath = val;
    notifyListeners();
    try {
      await setDownloadDir(path: val);
    } catch (_) {
      // ignore: FFI not ready
    }
  }
}

final downloadProvider = ChangeNotifierProvider<DownloadSettings>((ref) {
  return DownloadSettings();
});


// --- Search Settings ---

class SearchSettings extends ChangeNotifier {
  bool includeName = true;
  bool includeTags = true;
  bool includeDesc = false;
  List<String> history = [];
  List<String> blockedTags = [];

  SearchSettings() {
    _load();
  }

  Future<void> _load() async {
    final prefs = await SharedPreferences.getInstance();
    includeName = prefs.getBool('search_include_name') ?? true;
    includeTags = prefs.getBool('search_include_tags') ?? true;
    includeDesc = prefs.getBool('search_include_desc') ?? false;
    history = prefs.getStringList('search_history_list') ?? [];
    blockedTags = prefs.getStringList('search_blocked_tags') ?? [];
    notifyListeners();
  }

  Future<void> addHistory(String query) async {
    if (query.trim().isEmpty) return;
    history.remove(query);
    history.insert(0, query);
    if (history.length > 30) history = history.sublist(0, 30);
    
    final prefs = await SharedPreferences.getInstance();
    await prefs.setStringList('search_history_list', history);
    notifyListeners();
  }

  Future<void> clearHistory() async {
    history.clear();
    final prefs = await SharedPreferences.getInstance();
    await prefs.setStringList('search_history_list', history);
    notifyListeners();
  }
  
  Future<void> addBlockedTag(String tag) async {
    if (blockedTags.contains(tag)) return;
    blockedTags.add(tag);
    final prefs = await SharedPreferences.getInstance();
    await prefs.setStringList('search_blocked_tags', blockedTags);
    notifyListeners();
  }

  Future<void> removeBlockedTag(String tag) async {
    blockedTags.remove(tag);
    final prefs = await SharedPreferences.getInstance();
    await prefs.setStringList('search_blocked_tags', blockedTags);
    notifyListeners();
  }

  Future<void> setIncludeName(bool val) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool('search_include_name', val);
    includeName = val;
    notifyListeners();
  }

  Future<void> setIncludeTags(bool val) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool('search_include_tags', val);
    includeTags = val;
    notifyListeners();
  }

  Future<void> setIncludeDesc(bool val) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool('search_include_desc', val);
    includeDesc = val;
    notifyListeners();
  }
}

final searchProvider = ChangeNotifierProvider<SearchSettings>((ref) {
  return SearchSettings();
});
