import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ehviewer_scaffold/providers/settings_provider.dart';

// 主题色选项的明暗行为：
//  - 极客黑：强制深色（纯黑背景），无论"跟随系统"如何
//  - 纯净白：强制浅色
//  - 其它（默认紫/樱花粉/夜空蓝）：跟随系统
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  Future<AppearanceSettings> makeSettings({
    required String themeColor,
    bool followSystemDark = true,
    bool amoledBlack = false,
  }) async {
    SharedPreferences.setMockInitialValues({
      'appearance_theme_color': themeColor,
      'appearance_system_dark': followSystemDark,
      'appearance_amoled_black': amoledBlack,
    });
    final s = AppearanceSettings();
    await Future<void>.delayed(Duration.zero); // 等待异步 _load 完成
    return s;
  }

  test('极客黑：强制深色且背景纯黑', () async {
    final s = await makeSettings(themeColor: '极客黑', followSystemDark: false);
    final t = s.themeData;
    expect(t.brightness, Brightness.dark, reason: '极客黑应强制深色，不受跟随系统开关影响');
    expect(t.scaffoldBackgroundColor, Colors.black, reason: '极客黑背景应为纯黑');
  });

  test('极客黑：即使系统为浅色也保持深色', () async {
    final s = await makeSettings(themeColor: '极客黑', followSystemDark: true);
    final t = s.themeData;
    expect(t.brightness, Brightness.dark);
  });

  test('纯净白：强制浅色', () async {
    final s = await makeSettings(themeColor: '纯净白', followSystemDark: true);
    final t = s.themeData;
    expect(t.brightness, Brightness.light);
  });

  test('AMOLED 黑优先级最高：覆盖纯净白的强制浅色', () async {
    final s = await makeSettings(themeColor: '纯净白', amoledBlack: true);
    final t = s.themeData;
    expect(t.brightness, Brightness.dark, reason: 'AMOLED 黑应优先于纯净白');
    expect(t.scaffoldBackgroundColor, Colors.black);
  });

  test('纯净白（无 AMOLED）：保持浅色纯白背景', () async {
    final s = await makeSettings(themeColor: '纯净白', amoledBlack: false);
    final t = s.themeData;
    expect(t.brightness, Brightness.light);
    expect(t.scaffoldBackgroundColor, Colors.white);
  });

  test('樱花粉：不强制明暗（浅色下背景为默认浅灰）', () async {
    final s = await makeSettings(themeColor: '樱花粉', followSystemDark: false);
    final t = s.themeData;
    expect(t.brightness, Brightness.light);
    expect(t.colorScheme.primary, isNot(Colors.white), reason: '亮色主题下强调色应可见');
  });
}
