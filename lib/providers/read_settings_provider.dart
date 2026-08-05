import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

class ReadSettings extends ChangeNotifier {
  // 0 = Left to Right, 1 = Right to Left, 2 = Top to Bottom
  int readDirection = 1;
  bool showClock = true;
  bool showBattery = true;
  bool fullScreen = true;
  int autoFlipInterval = 0; // 0 means disabled
  double pageInterval = 10.0;
  double customBrightness = -1.0; // -1 means use system brightness

  ReadSettings() {
    _load();
  }

  Future<void> _load() async {
    final prefs = await SharedPreferences.getInstance();
    readDirection = prefs.getInt('read_direction') ?? 1;
    showClock = prefs.getBool('read_show_clock') ?? true;
    showBattery = prefs.getBool('read_show_battery') ?? true;
    fullScreen = prefs.getBool('read_full_screen') ?? true;
    autoFlipInterval = prefs.getInt('read_auto_flip') ?? 0;
    pageInterval = prefs.getDouble('read_page_interval') ?? 10.0;
    customBrightness = prefs.getDouble('read_custom_brightness') ?? -1.0;
    notifyListeners();
  }

  Future<void> setReadDirection(int val) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setInt('read_direction', val);
    readDirection = val;
    notifyListeners();
  }

  Future<void> setShowClock(bool val) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool('read_show_clock', val);
    showClock = val;
    notifyListeners();
  }

  Future<void> setShowBattery(bool val) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool('read_show_battery', val);
    showBattery = val;
    notifyListeners();
  }

  Future<void> setFullScreen(bool val) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool('read_full_screen', val);
    fullScreen = val;
    notifyListeners();
  }

  Future<void> setAutoFlipInterval(int val) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setInt('read_auto_flip', val);
    autoFlipInterval = val;
    notifyListeners();
  }

  Future<void> setPageInterval(double val) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setDouble('read_page_interval', val);
    pageInterval = val;
    notifyListeners();
  }

  Future<void> setCustomBrightness(double val) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setDouble('read_custom_brightness', val);
    customBrightness = val;
    notifyListeners();
  }
}

final readSettingsProvider = ChangeNotifierProvider<ReadSettings>((ref) {
  return ReadSettings();
});
