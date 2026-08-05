import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../providers/settings_provider.dart';

class AppearanceSettingsPage extends ConsumerWidget {
  const AppearanceSettingsPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final appearance = ref.watch(appearanceProvider);
    final theme = Theme.of(context);
    final colorScheme = theme.colorScheme;

    return Scaffold(
      // 与其它设置页一致：跟随主题的 scaffold 背景，
      // 而不是硬编码半透明 surfaceContainerLowest（导致主题色覆盖不到位）。
      backgroundColor: theme.scaffoldBackgroundColor,
      appBar: AppBar(
        title: Text('样式设置',
            style: TextStyle(color: colorScheme.onSurface)),
      ),
      body: ListView(
        children: [
          SwitchListTile(
            title: Text('跟随系统深色模式',
                style: TextStyle(color: colorScheme.onSurface)),
            value: appearance.followSystemDark, 
            onChanged: (val) {
              ref.read(appearanceProvider.notifier).setSystemDark(val);
            },
          ),
          SwitchListTile(
            title: Text('纯粹 AMOLED 黑',
                style: TextStyle(color: colorScheme.onSurface)),
            subtitle: Text('针对 OLED 屏幕优化，深色模式下使用纯黑背景防烧屏且极度省电',
                style: TextStyle(color: colorScheme.onSurface.withOpacity(0.6))),
            value: appearance.amoledBlack, 
            onChanged: (val) {
              ref.read(appearanceProvider.notifier).setAmoledBlack(val);
            },
          ),
          SwitchListTile(
            title: Text('像素偏移 (防烧屏引擎)',
                style: TextStyle(color: colorScheme.onSurface)),
            subtitle: Text('每隔一分钟让全界面极其缓慢地微动 1-2 像素，防止固定图案长期点亮老化屏幕',
                style: TextStyle(color: colorScheme.onSurface.withOpacity(0.6))),
            value: appearance.pixelShift, 
            onChanged: (val) {
              ref.read(appearanceProvider.notifier).setPixelShift(val);
            },
          ),
          SwitchListTile(
            title: Text('毛玻璃效果',
                style: TextStyle(color: colorScheme.onSurface)),
            subtitle: Text('搜索栏、阅读器浮层等使用半透明磨砂玻璃质感（Glassmorphism），关闭则恢复纯色',
                style: TextStyle(color: colorScheme.onSurface.withOpacity(0.6))),
            value: appearance.glassEffect,
            onChanged: (val) {
              ref.read(appearanceProvider.notifier).setGlassEffect(val);
            },
          ),
          SwitchListTile(
            title: Text('触觉震动反馈',
                style: TextStyle(color: colorScheme.onSurface)),
            subtitle: Text('在下拉刷新、长按标签等关键节点提供微弱且现代的物理震动确认感',
                style: TextStyle(color: colorScheme.onSurface.withOpacity(0.6))),
            value: appearance.enableHaptics, 
            onChanged: (val) {
              ref.read(appearanceProvider.notifier).setEnableHaptics(val);
            },
          ),
          ListTile(
            title: Text('主题颜色',
                style: TextStyle(color: colorScheme.onSurface)),
            trailing: Text(appearance.themeColor,
                style: TextStyle(color: colorScheme.onSurface.withOpacity(0.6))),
            onTap: () {
              showDialog(
                context: context,
                builder: (context) => SimpleDialog(
                  title: const Text('选择主题颜色'),
                  children: ['纯净白', '默认 (紫)', '樱花粉', '夜空蓝', '极客黑'].map((color) => 
                    SimpleDialogOption(
                      onPressed: () {
                        ref.read(appearanceProvider.notifier).setThemeColor(color);
                        Navigator.pop(context);
                      },
                      child: Text(color),
                    )
                  ).toList(),
                ),
              );
            },
          ),
          ListTile(
            title: const Text('列表模式'), 
            trailing: const Text('瀑布流'),
            onTap: () {
              showDialog(
                context: context,
                builder: (context) => SimpleDialog(
                  title: const Text('列表模式'),
                  children: ['瀑布流', '精简列表', '网格'].map((mode) => 
                    SimpleDialogOption(
                      onPressed: () {
                        Navigator.pop(context);
                        ScaffoldMessenger.of(context).showSnackBar(
                          const SnackBar(content: Text('列表模式切换将在完整版实装')),
                        );
                      },
                      child: Text(mode),
                    )
                  ).toList(),
                ),
              );
            },
          ),
        ],
      ),
    );
  }
}
