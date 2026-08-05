import 'dart:io';
import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:path_provider/path_provider.dart';
import 'package:flutter_cache_manager/flutter_cache_manager.dart';
import 'package:file_picker/file_picker.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../../providers/settings_provider.dart';
import '../../utils/haptics.dart';
import '../../src/rust/api.dart'; // To call clear_cache
class AdvancedSettingsPage extends ConsumerStatefulWidget {
  const AdvancedSettingsPage({super.key});

  @override
  ConsumerState<AdvancedSettingsPage> createState() => _AdvancedSettingsPageState();
}

class _AdvancedSettingsPageState extends ConsumerState<AdvancedSettingsPage> {
  String _cacheSize = "计算中...";

  @override
  void initState() {
    super.initState();
    _calculateCache();
  }

  Future<void> _calculateCache() async {
    try {
      final tempDir = await getTemporaryDirectory();
      int totalSize = 0;
      if (tempDir.existsSync()) {
        tempDir.listSync(recursive: true, followLinks: false).forEach((FileSystemEntity entity) {
          // Downloaded galleries are not cache: exclude them from the size.
          if (entity is File && !entity.path.contains('eh_downloads')) {
            totalSize += entity.lengthSync();
          }
        });
      }
      if (mounted) {
        setState(() {
          _cacheSize = "${(totalSize / (1024 * 1024)).toStringAsFixed(1)} MB";
        });
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          _cacheSize = "计算失败";
        });
      }
    }
  }

  Future<void> _clearCache() async {
    showDialog(
      context: context,
      barrierDismissible: false,
      builder: (context) => const Center(child: CircularProgressIndicator()),
    );
    
    try {
      await DefaultCacheManager().emptyCache();
      PaintingBinding.instance.imageCache.clear();
      PaintingBinding.instance.imageCache.clearLiveImages();
      
      final tempDir = await getTemporaryDirectory();
      if (tempDir.existsSync()) {
        tempDir.listSync(recursive: true, followLinks: false).forEach((FileSystemEntity entity) {
          // Keep downloaded galleries intact (same rule as the auto-clear in main.dart).
          if (entity is File && !entity.path.contains('eh_downloads')) {
            try { entity.deleteSync(); } catch (_) {}
          }
        });
      }
      
      if (mounted) {
        Navigator.pop(context); // close dialog
        setState(() {
          _cacheSize = "0.0 MB";
        });
        Haptics.success();
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('缓存已深度清理')),
        );
      }
    } catch (e) {
      if (mounted) Navigator.pop(context);
    }
  }

  Future<void> _exportData() async {
    final prefs = await SharedPreferences.getInstance();
    final keys = prefs.getKeys();
    final Map<String, dynamic> data = {};
    for (String key in keys) {
      data[key] = prefs.get(key);
    }
    final jsonString = jsonEncode(data);
    
    String? outputFile = await FilePicker.platform.saveFile(
      dialogTitle: '保存应用数据备份',
      fileName: 'ehviewer_backup.json',
      type: FileType.custom,
      allowedExtensions: ['json'],
    );
    
    if (outputFile != null) {
      final file = File(outputFile);
      await file.writeAsString(jsonString);
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('已导出到: $outputFile')));
      }
    }
  }

  Future<void> _importData() async {
    FilePickerResult? result = await FilePicker.platform.pickFiles(
      type: FileType.custom,
      allowedExtensions: ['json'],
    );
    
    if (result != null && result.files.single.path != null) {
      final file = File(result.files.single.path!);
      final jsonString = await file.readAsString();
      try {
        final Map<String, dynamic> data = jsonDecode(jsonString);
        final prefs = await SharedPreferences.getInstance();
        for (String key in data.keys) {
          final value = data[key];
          if (value is String) {
            prefs.setString(key, value);
          } else if (value is bool) {
            prefs.setBool(key, value);
          } else if (value is int) {
            prefs.setInt(key, value);
          } else if (value is double) {
            prefs.setDouble(key, value);
          } else if (value is List) {
            prefs.setStringList(key, List<String>.from(value));
          }
        }
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('导入成功，请重启应用以生效全部配置')));
        }
      } catch (e) {
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('导入失败: 格式错误')));
        }
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final appearanceSettings = ref.watch(appearanceProvider);
    
    final colorScheme = Theme.of(context).colorScheme;
    return Scaffold(
      backgroundColor: colorScheme.surfaceContainerLowest.withOpacity(0.95),
      appBar: AppBar(title: Text('高级', style: TextStyle(color: colorScheme.onSurface))),
      body: ListView(
        children: [
          SwitchListTile(
            title: const Text('显示标签翻译'),
            subtitle: const Text('将英文标签翻译为中文'),
            value: appearanceSettings.showTagTranslation,
            activeColor: Theme.of(context).colorScheme.primary,
            onChanged: (val) {
              ref.read(appearanceProvider.notifier).setShowTagTranslation(val);
            },
          ),
          const Divider(height: 1),
          ListTile(
            title: const Text('更新标签翻译数据'),
            subtitle: const Text('从 Github 下载最新的 EhTagTranslation 数据库'),
            onTap: () async {
              showDialog(
                context: context,
                barrierDismissible: false,
                builder: (context) => const Center(child: CircularProgressIndicator()),
              );
              try {
                final docDir = await getApplicationDocumentsDirectory();
                final dbPath = "${docDir.path}/eh_tag_db.json";
                await downloadTagDb(path: dbPath);
                if (context.mounted) {
                  Navigator.pop(context);
                  ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('标签翻译数据更新成功！')));
                }
              } catch (e) {
                if (context.mounted) {
                  Navigator.pop(context);
                  ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('下载失败: $e')));
                }
              }
            },
          ),
          const Divider(height: 1),
          ListTile(
            title: const Text('清除图片缓存'),
            subtitle: Text('当前大小: $_cacheSize\n深度清理将释放所有磁盘空间（不影响已下载内容）'),
            onTap: () {
              if (_cacheSize != "0.0 MB" && _cacheSize != "计算中...") {
                _clearCache();
              }
            },
          ),
          SwitchListTile(
            title: const Text('自动清理过期缓存'),
            subtitle: const Text('到达设定的天数后，App 将在后台自动清理旧图片'),
            value: appearanceSettings.autoClearCache,
            activeColor: Theme.of(context).colorScheme.primary,
            onChanged: (val) {
              ref.read(appearanceProvider.notifier).setAutoClearCache(val);
            },
          ),
          if (appearanceSettings.autoClearCache)
            ListTile(
              title: const Text('自动清理周期'),
              subtitle: Padding(
                padding: const EdgeInsets.only(top: 8.0),
                child: SegmentedButton<int>(
                  segments: const [
                    ButtonSegment(value: 3, label: Text('3天')),
                    ButtonSegment(value: 7, label: Text('7天')),
                    ButtonSegment(value: 15, label: Text('15天')),
                    ButtonSegment(value: 30, label: Text('30天')),
                  ],
                  selected: {appearanceSettings.autoClearCacheDays},
                  onSelectionChanged: (Set<int> newSelection) {
                    ref.read(appearanceProvider.notifier).setAutoClearCacheDays(newSelection.first);
                  },
                ),
              ),
            ),
          const Divider(height: 1),
          ListTile(
            title: const Text('导出应用数据'),
            subtitle: const Text('将历史记录和设置导出为备份文件'),
            onTap: _exportData,
          ),
          ListTile(
            title: const Text('导入应用数据'),
            onTap: _importData,
          ),
        ],
      ),
    );
  }
}
