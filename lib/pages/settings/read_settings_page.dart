import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../providers/read_settings_provider.dart';
import '../../providers/settings_provider.dart';

class ReadSettingsPage extends ConsumerWidget {
  const ReadSettingsPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final readSettings = ref.watch(readSettingsProvider);
    final appearance = ref.watch(appearanceProvider);

    return Scaffold(
      backgroundColor: appearance.themeData.scaffoldBackgroundColor,
      appBar: AppBar(title: const Text('阅读设置')),
      body: ListView(
        children: [
          ListTile(
            title: const Text('阅读方向'), 
            trailing: Text(
              readSettings.readDirection == 0 ? '从左向右' :
              readSettings.readDirection == 1 ? '从右向左' : '从上向下'
            ),
            onTap: () {
              showDialog(
                context: context,
                builder: (context) => SimpleDialog(
                  title: const Text('阅读方向'),
                  children: [
                    SimpleDialogOption(
                      onPressed: () {
                        ref.read(readSettingsProvider.notifier).setReadDirection(1);
                        Navigator.pop(context);
                      },
                      child: const Text('从右向左 (日漫)'),
                    ),
                    SimpleDialogOption(
                      onPressed: () {
                        ref.read(readSettingsProvider.notifier).setReadDirection(0);
                        Navigator.pop(context);
                      },
                      child: const Text('从左向右 (美漫/条漫)'),
                    ),
                    SimpleDialogOption(
                      onPressed: () {
                        ref.read(readSettingsProvider.notifier).setReadDirection(2);
                        Navigator.pop(context);
                      },
                      child: const Text('从上向下 (瀑布流)'),
                    ),
                  ],
                ),
              );
            },
          ),
          SwitchListTile(
            title: const Text('全屏阅读'), 
            value: readSettings.fullScreen, 
            onChanged: (val) {
              ref.read(readSettingsProvider.notifier).setFullScreen(val);
            },
          ),
          SwitchListTile(
            title: const Text('显示系统时钟'), 
            value: readSettings.showClock, 
            onChanged: (val) {
              ref.read(readSettingsProvider.notifier).setShowClock(val);
            },
          ),
          SwitchListTile(
            title: const Text('显示电池电量'), 
            value: readSettings.showBattery, 
            onChanged: (val) {
              ref.read(readSettingsProvider.notifier).setShowBattery(val);
            },
          ),
          ListTile(
            title: const Text('自动翻页 (秒)'),
            subtitle: Slider(
              value: readSettings.autoFlipInterval.toDouble(),
              min: 0,
              max: 10,
              divisions: 10,
              label: readSettings.autoFlipInterval == 0 ? '关闭' : '${readSettings.autoFlipInterval}秒',
              onChanged: (val) {
                ref.read(readSettingsProvider.notifier).setAutoFlipInterval(val.toInt());
              },
            ),
            trailing: Text(readSettings.autoFlipInterval == 0 ? '关闭' : '${readSettings.autoFlipInterval}秒'),
          ),
          ListTile(
            title: const Text('页面间隔 (上下模式)'),
            subtitle: Slider(
              value: readSettings.pageInterval,
              min: 0,
              max: 50,
              divisions: 10,
              label: '${readSettings.pageInterval.toInt()}px',
              onChanged: (val) {
                ref.read(readSettingsProvider.notifier).setPageInterval(val);
              },
            ),
            trailing: Text('${readSettings.pageInterval.toInt()}px'),
          ),
          ListTile(
            title: const Text('自定义屏幕亮度'),
            subtitle: Slider(
              value: readSettings.customBrightness,
              min: -1.0,
              max: 1.0,
              label: readSettings.customBrightness == -1.0 ? '跟随系统' : '${(readSettings.customBrightness * 100).toInt()}%',
              onChanged: (val) {
                // If it's close to -1.0, snap to -1.0
                if (val < -0.9) val = -1.0;
                ref.read(readSettingsProvider.notifier).setCustomBrightness(val);
              },
            ),
            trailing: Text(readSettings.customBrightness == -1.0 ? '跟随系统' : '${(readSettings.customBrightness * 100).toInt()}%'),
          ),
        ],
      ),
    );
  }
}
