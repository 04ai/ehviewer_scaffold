import 'package:flutter/material.dart';
import 'package:flutter/cupertino.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../src/rust/api.dart';
import 'cookie_login_page.dart';
import 'settings/appearance_settings_page.dart';
import 'settings/read_settings_page.dart';
import 'settings/download_settings_page.dart';
import 'settings/advanced_settings_page.dart';
import 'settings/security_settings_page.dart';
import 'settings/search_settings_page.dart';
import 'settings/about_settings_page.dart';

import '../providers/settings_provider.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

class SettingsPage extends ConsumerStatefulWidget {
  const SettingsPage({super.key});

  @override
  ConsumerState<SettingsPage> createState() => _SettingsPageState();
}

class _SettingsPageState extends ConsumerState<SettingsPage> {
  bool _isExHentai = false;

  @override
  void initState() {
    super.initState();
    _loadSettings();
  }

  Future<void> _loadSettings() async {
    final prefs = await SharedPreferences.getInstance();
    setState(() {
      _isExHentai = prefs.getBool('setting_is_exhentai') ?? false;
    });
  }

  Future<void> _toggleSite(bool isEx) async {
    if (_isExHentai == isEx) return;
    
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool('setting_is_exhentai', isEx);
    setState(() {
      _isExHentai = isEx;
    });
    
    // Sync to Rust backend
    final url = isEx ? "https://exhentai.org" : "https://e-hentai.org";
    await setSiteUrl(url: url);
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colorScheme = theme.colorScheme;
    return Scaffold(
      backgroundColor: colorScheme.surfaceContainerLowest.withOpacity(0.95),
      appBar: AppBar(
        backgroundColor: colorScheme.surface,
        foregroundColor: colorScheme.onSurface,
        elevation: 0,
        centerTitle: true,
        title: Text('设置', style: TextStyle(fontWeight: FontWeight.bold, color: colorScheme.onSurface)),
      ),
      body: ListView(
        children: [
          const SizedBox(height: 20),
          
          const Padding(
            padding: EdgeInsets.only(left: 32, bottom: 8),
            child: Text("E-HENTAI 站点", style: TextStyle(color: Colors.grey, fontSize: 13)),
          ),
          // ── Group 1: E-Hentai Site Settings ─────────────────────────────────
          _SettingsGroup(
            children: [
              _SettingsRow(
                title: '画廊站点',
                trailing: Container(
                  height: 32,
                  decoration: BoxDecoration(
                    color: const Color(0xFFF2F2F7),
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      _SegmentButton(
                        title: 'E-Hentai',
                        isSelected: !_isExHentai,
                        onTap: () => _toggleSite(false),
                      ),
                      _SegmentButton(
                        title: 'ExHentai',
                        isSelected: _isExHentai,
                        onTap: () => _toggleSite(true),
                      ),
                    ],
                  ),
                ),
              ),
              const Divider(height: 1, indent: 16),
              _SettingsRow(
                title: 'Cookie 登录',
                subtitle: '设置账号凭证以访问受限内容',
                isLink: true,
                onTap: () {
                  Navigator.push(
                    context,
                    CupertinoPageRoute(builder: (_) => const CookieLoginPage()),
                  );
                },
              ),

            ],
          ),

          const SizedBox(height: 24),
          
          const Padding(
            padding: EdgeInsets.only(left: 32, bottom: 8),
            child: Text("显示设置", style: TextStyle(color: Colors.grey, fontSize: 13)),
          ),
          // ── Group: Display ─────────────────────────────────
          _SettingsGroup(
            children: [
              _SettingsRow(
                title: '显示日文标题',
                subtitle: '画廊详情优先显示日文原标题',
                trailing: CupertinoSwitch(
                  value: ref.watch(appearanceProvider).showJpnTitle,
                  onChanged: (v) {
                    ref.read(appearanceProvider.notifier).setShowJpnTitle(v);
                  },
                ),
              ),
              const Divider(height: 1, indent: 16),
              _SettingsRow(
                title: '显示标签翻译',
                subtitle: '在详情页和画廊列表显示中文标签',
                trailing: CupertinoSwitch(
                  value: ref.watch(appearanceProvider).showTagTranslation,
                  onChanged: (v) {
                    ref.read(appearanceProvider.notifier).setShowTagTranslation(v);
                  },
                ),
              ),
            ],
          ),

          const SizedBox(height: 24),
          
          const Padding(
            padding: EdgeInsets.only(left: 32, bottom: 8),
            child: Text("应用功能", style: TextStyle(color: Colors.grey, fontSize: 13)),
          ),
          // ── Group 2: App Modules ─────────────────────────────────
          _SettingsGroup(
            children: [
              _SettingsRow(
                title: '阅读设置',
                icon: Icons.menu_book,
                iconColor: Colors.blue,
                isLink: true,
                onTap: () => Navigator.push(context, CupertinoPageRoute(builder: (_) => const ReadSettingsPage())),
              ),
              const Divider(height: 1, indent: 48),
              _SettingsRow(
                title: '外观设置',
                icon: Icons.palette,
                iconColor: Colors.purple,
                isLink: true,
                onTap: () => Navigator.push(context, CupertinoPageRoute(builder: (_) => const AppearanceSettingsPage())),
              ),
              const Divider(height: 1, indent: 48),
              _SettingsRow(
                title: '下载设置',
                icon: Icons.download,
                iconColor: Colors.teal,
                isLink: true,
                onTap: () => Navigator.push(context, CupertinoPageRoute(builder: (_) => const DownloadSettingsPage())),
              ),
              const Divider(height: 1, indent: 48),
              _SettingsRow(
                title: '搜索设置',
                icon: Icons.search,
                iconColor: Colors.orange,
                isLink: true,
                onTap: () => Navigator.push(context, CupertinoPageRoute(builder: (_) => const SearchSettingsPage())),
              ),
              const Divider(height: 1, indent: 48),
              _SettingsRow(
                title: '高级设置',
                icon: Icons.settings_applications,
                iconColor: Colors.grey.shade700,
                isLink: true,
                onTap: () => Navigator.push(context, CupertinoPageRoute(builder: (_) => const AdvancedSettingsPage())),
              ),
              const Divider(height: 1, indent: 48),
              _SettingsRow(
                title: '安全与隐私',
                icon: Icons.security,
                iconColor: Colors.redAccent,
                isLink: true,
                onTap: () => Navigator.push(context, CupertinoPageRoute(builder: (_) => const SecuritySettingsPage())),
              ),
            ],
          ),
          
          const SizedBox(height: 24),
          
          const Padding(
            padding: EdgeInsets.only(left: 32, bottom: 8),
            child: Text("关于", style: TextStyle(color: Colors.grey, fontSize: 13)),
          ),
          // ── Group 3: About ─────────────────────────────────
          _SettingsGroup(
            children: [
              _SettingsRow(
                title: '关于 EH Viewer',
                icon: Icons.info_outline,
                iconColor: Colors.blueGrey,
                isLink: true,
                onTap: () => Navigator.push(context, CupertinoPageRoute(builder: (_) => const AboutSettingsPage())),
              ),
            ],
          ),

          const SizedBox(height: 40),
        ],
      ),
    );
  }
}

class _SettingsGroup extends StatelessWidget {
  final List<Widget> children;
  
  const _SettingsGroup({required this.children});

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    return Container(
      margin: const EdgeInsets.symmetric(horizontal: 16),
      decoration: BoxDecoration(
        color: colorScheme.surface,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Column(
        children: children,
      ),
    );
  }
}

class _SettingsRow extends StatelessWidget {
  final String title;
  final String? subtitle;
  final Widget? trailing;
  final bool isLink;
  final VoidCallback? onTap;
  final IconData? icon;
  final Color? iconColor;

  const _SettingsRow({
    required this.title,
    this.subtitle,
    this.trailing,
    this.isLink = false,
    this.onTap,
    this.icon,
    this.iconColor,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colorScheme = theme.colorScheme;
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(12),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
        child: Row(
          children: [
            if (icon != null) ...[
              Container(
                padding: const EdgeInsets.all(6),
                decoration: BoxDecoration(
                  color: iconColor?.withOpacity(0.1) ?? colorScheme.surfaceContainerHighest,
                  borderRadius: BorderRadius.circular(8),
                ),
                child: Icon(icon, size: 20, color: iconColor),
              ),
              const SizedBox(width: 12),
            ],
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Text(
                    title,
                    style: TextStyle(
                      fontSize: 16,
                      color: colorScheme.onSurface,
                    ),
                  ),
                  if (subtitle != null) ...[
                    const SizedBox(height: 4),
                    Text(
                      subtitle!,
                      style: TextStyle(
                        fontSize: 12,
                        color: colorScheme.onSurface.withOpacity(0.55),
                      ),
                    ),
                  ],
                ],
              ),
            ),
            if (trailing != null) trailing!,
            if (isLink)
              Icon(Icons.chevron_right, color: colorScheme.onSurface.withOpacity(0.3)),
          ],
        ),
      ),
    );
  }
}

class _SegmentButton extends StatelessWidget {
  final String title;
  final bool isSelected;
  final VoidCallback onTap;

  const _SegmentButton({
    required this.title,
    required this.isSelected,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 12),
        alignment: Alignment.center,
        decoration: BoxDecoration(
          color: isSelected ? Colors.white : Colors.transparent,
          borderRadius: BorderRadius.circular(6),
          boxShadow: isSelected
              ? [
                  BoxShadow(
                    color: Colors.black.withOpacity(0.04),
                    blurRadius: 2,
                    offset: const Offset(0, 1),
                  )
                ]
              : null,
        ),
        child: Text(
          title,
          style: TextStyle(
            fontSize: 13,
            fontWeight: isSelected ? FontWeight.w600 : FontWeight.normal,
            color: isSelected ? Colors.black87 : Colors.black54,
          ),
        ),
      ),
    );
  }
}
