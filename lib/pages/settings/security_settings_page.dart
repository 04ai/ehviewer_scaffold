import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:flutter_windowmanager/flutter_windowmanager.dart';
import '../../providers/settings_provider.dart';

class SecuritySettingsPage extends ConsumerStatefulWidget {
  const SecuritySettingsPage({super.key});

  @override
  ConsumerState<SecuritySettingsPage> createState() => _SecuritySettingsPageState();
}

class _SecuritySettingsPageState extends ConsumerState<SecuritySettingsPage> {
  bool _blurOnRecent = true;

  @override
  void initState() {
    super.initState();
    _loadSettings();
  }

  Future<void> _loadSettings() async {
    final prefs = await SharedPreferences.getInstance();
    setState(() {
      _blurOnRecent = prefs.getBool('security_blur_recent') ?? true;
    });
    _applySecureFlag(_blurOnRecent);
  }

  Future<void> _setBlur(bool val) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool('security_blur_recent', val);
    setState(() {
      _blurOnRecent = val;
    });
    _applySecureFlag(val);
  }

  Future<void> _applySecureFlag(bool secure) async {
    try {
      if (secure) {
        await FlutterWindowManager.addFlags(FlutterWindowManager.FLAG_SECURE);
      } else {
        await FlutterWindowManager.clearFlags(FlutterWindowManager.FLAG_SECURE);
      }
    } catch (e) {
      debugPrint("FlutterWindowManager error: $e");
    }
  }

  @override
  Widget build(BuildContext context) {
    final security = ref.watch(securityProvider);
    final appearance = ref.watch(appearanceProvider);

    return Scaffold(
      backgroundColor: appearance.themeData.scaffoldBackgroundColor,
      appBar: AppBar(title: const Text('安全设置')),
      body: ListView(
        children: [
          SwitchListTile(
            title: const Text('指纹/面容解锁'),
            subtitle: const Text('开启后，每次进入 App 需验证生物信息'),
            value: security.enableBiometrics,
            onChanged: (val) {
              ref.read(securityProvider.notifier).setBiometrics(val);
            },
          ),
          SwitchListTile(
            title: const Text('在最近任务中模糊界面'),
            subtitle: const Text('防止应用在后台多任务卡片中泄露隐私'),
            value: _blurOnRecent,
            onChanged: _setBlur,
          ),
          ListTile(
            title: const Text('立即锁定应用'),
            subtitle: const Text('手动进入锁定状态，需要验证才能继续'),
            trailing: const Icon(Icons.lock_outline),
            onTap: () {
              ref.read(securityProvider.notifier).setLocked(true);
              ScaffoldMessenger.of(context).showSnackBar(
                const SnackBar(content: Text('应用已锁定')),
              );
            },
          ),
        ],
      ),
    );
  }
}
