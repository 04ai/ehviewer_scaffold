import 'package:flutter/material.dart';

class AboutSettingsPage extends StatelessWidget {
  const AboutSettingsPage({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFFF2F2F7),
      appBar: AppBar(title: const Text('关于')),
      body: ListView(
        children: [
          const SizedBox(height: 40),
          const Center(
            child: Icon(Icons.book, size: 80, color: Colors.blue),
          ),
          const SizedBox(height: 16),
          const Center(
            child: Text('EH Viewer Scaffold', style: TextStyle(fontSize: 24, fontWeight: FontWeight.bold)),
          ),
          Center(
            child: Text('版本 1.0.0',
                style: TextStyle(
                    color: Theme.of(context).colorScheme.onSurface.withOpacity(0.6))),
          ),
          const SizedBox(height: 40),
          const Divider(height: 1, indent: 16),
          ListTile(title: const Text('检查更新'), trailing: const Icon(Icons.chevron_right), onTap: () {}),
          const Divider(height: 1, indent: 16),
          ListTile(title: const Text('开源许可'), trailing: const Icon(Icons.chevron_right), onTap: () {}),
          const Divider(height: 1, indent: 16),
          const ListTile(title: Text('作者'), trailing: Text('xiaojieonly / AI Agent')),
          const Divider(height: 1, indent: 16),
        ],
      ),
    );
  }
}
