import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../providers/settings_provider.dart';

class DownloadSettingsPage extends ConsumerWidget {
  const DownloadSettingsPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final download = ref.watch(downloadProvider);
    final theme = Theme.of(context);
    final colorScheme = theme.colorScheme;

    return Scaffold(
      backgroundColor: colorScheme.surfaceContainerLowest.withOpacity(0.95),
      appBar: AppBar(title: Text('下载设置',
          style: TextStyle(color: colorScheme.onSurface))),
      body: ListView(
        children: [
          ListTile(
            title: Text('下载目录',
                style: TextStyle(color: colorScheme.onSurface)),
            subtitle: Text(download.downloadPath,
                style: TextStyle(color: colorScheme.onSurface.withOpacity(0.55),
                    fontSize: 12)),
            trailing: Icon(Icons.folder_open, color: colorScheme.primary),
            onTap: () async {
              // Show a dialog to let the user type a custom path
              final ctrl = TextEditingController(text: download.downloadPath);
              final result = await showDialog<String>(
                context: context,
                builder: (ctx) => AlertDialog(
                  title: const Text('设置下载目录'),
                  content: Column(
                    mainAxisSize: MainAxisSize.min,
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text('请输入完整目录路径：',
                          style: TextStyle(color: colorScheme.onSurface.withOpacity(0.6),
                              fontSize: 13)),
                      const SizedBox(height: 8),
                      TextField(
                        controller: ctrl,
                        decoration: const InputDecoration(
                          hintText: '/storage/emulated/0/Download/EHviewer',
                          border: OutlineInputBorder(),
                          isDense: true,
                        ),
                      ),
                      const SizedBox(height: 8),
                      Text('常用目录：',
                          style: TextStyle(color: colorScheme.onSurface.withOpacity(0.6),
                              fontSize: 12)),
                      ...['/storage/emulated/0/Download/EHviewer',
                            '/storage/emulated/0/Pictures/EHviewer'].map(
                        (p) => TextButton(
                          onPressed: () => Navigator.pop(ctx, p),
                          child: Text(p, style: const TextStyle(fontSize: 12)),
                        ),
                      ),
                    ],
                  ),
                  actions: [
                    TextButton(
                        onPressed: () => Navigator.pop(ctx),
                        child: const Text('取消')),
                    ElevatedButton(
                      onPressed: () => Navigator.pop(ctx, ctrl.text.trim()),
                      child: const Text('确定'),
                    ),
                  ],
                ),
              );
              if (result != null && result.isNotEmpty) {
                ref.read(downloadProvider.notifier).setDownloadPath(result);
              }
            },
          ),
          ListTile(
            title: const Text('同时下载任务数'),
            trailing: DropdownButton<int>(
              value: download.concurrentDownloads,
              underline: const SizedBox(),
              items: [1, 2, 3, 5, 10].map((int value) {
                return DropdownMenuItem<int>(
                  value: value,
                  child: Text(value.toString()),
                );
              }).toList(),
              onChanged: (val) {
                if (val != null) {
                  ref.read(downloadProvider.notifier).setConcurrent(val);
                }
              },
            ),
          ),

        ],
      ),
    );
  }
}
