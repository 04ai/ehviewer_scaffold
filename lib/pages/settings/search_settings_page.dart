import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../providers/settings_provider.dart';

class SearchSettingsPage extends ConsumerWidget {
  const SearchSettingsPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final searchSettings = ref.watch(searchProvider);
    final appearance = ref.watch(appearanceProvider);

    return Scaffold(
      backgroundColor: appearance.themeData.scaffoldBackgroundColor,
      appBar: AppBar(title: const Text('搜索设置')),
      body: ListView(
        children: [
          SwitchListTile(
            title: const Text('搜索时包含画廊名称'), 
            value: searchSettings.includeName, 
            onChanged: (v) => ref.read(searchProvider.notifier).setIncludeName(v),
          ),
          SwitchListTile(
            title: const Text('搜索时包含标签'), 
            value: searchSettings.includeTags, 
            onChanged: (v) => ref.read(searchProvider.notifier).setIncludeTags(v),
          ),
          SwitchListTile(
            title: const Text('搜索时包含描述'), 
            value: searchSettings.includeDesc, 
            onChanged: (v) => ref.read(searchProvider.notifier).setIncludeDesc(v),
          ),
          ListTile(
            title: const Text('默认搜索类别'), 
            subtitle: const Text('搜索将只在主页当前点亮的主标签内进行'),
            trailing: const Text('跟随主页'),
            onTap: () {
              ScaffoldMessenger.of(context).showSnackBar(
                const SnackBar(content: Text('当您在主页或分类页面搜索时，只会在点亮的标签内进行搜索')),
              );
            },
          ),
          const Divider(),
          ListTile(
            title: const Text('屏蔽标签管理', style: TextStyle(color: Colors.redAccent)),
            subtitle: Text('已屏蔽 ${searchSettings.blockedTags.length} 个标签'),
            trailing: const Icon(Icons.block, color: Colors.redAccent),
            onTap: () {
              showDialog(
                context: context,
                builder: (context) => const BlockedTagsDialog(),
              );
            },
          ),
        ],
      ),
    );
  }
}

class BlockedTagsDialog extends ConsumerWidget {
  const BlockedTagsDialog({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final searchSettings = ref.watch(searchProvider);
    final tags = searchSettings.blockedTags;
    
    return AlertDialog(
      title: const Text('屏蔽标签管理'),
      content: SizedBox(
        width: double.maxFinite,
        child: tags.isEmpty
            ? const Text('暂无屏蔽的标签')
            : ListView.builder(
                shrinkWrap: true,
                itemCount: tags.length,
                itemBuilder: (context, index) {
                  final tag = tags[index];
                  return ListTile(
                    title: Text(tag),
                    trailing: IconButton(
                      icon: const Icon(Icons.delete_outline, color: Colors.redAccent),
                      onPressed: () {
                        ref.read(searchProvider.notifier).removeBlockedTag(tag);
                      },
                    ),
                  );
                },
              ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('关闭'),
        ),
      ],
    );
  }
}
