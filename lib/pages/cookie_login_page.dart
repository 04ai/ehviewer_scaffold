import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../src/rust/api.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../main.dart';
import '../utils/secure_cookies.dart';

// ── Storage keys ───────────────────────────────────────────────────────────
/// Load saved cookies from disk and sync to Rust
Future<bool> loadAndSyncCookies() async {
  try {
    // One-time migration from the old plaintext SharedPreferences keys.
    await migrateLegacyCookies();
    final cookies = await readAllCookies();
    final memberId = cookies[kCookieMemberId] ?? '';
    final passHash = cookies[kCookiePassHash] ?? '';
    if (memberId.isEmpty || passHash.isEmpty) return false;

    final cookieStr = _buildCookieString(
      memberId,
      passHash,
      cookies[kCookieIgneous] ?? '',
      cookies[kCookieSk] ?? '',
    );
    await syncCookies(cookieString: cookieStr);
    return true;
  } catch (_) {
    return false;
  }
}

String _buildCookieString(
  String memberId, String passHash, String igneous, String sk) {
  final parts = <String>[
    'ipb_member_id=$memberId',
    'ipb_pass_hash=$passHash',
    'nw=1', // Bypass content warning
    'inline_set=ts_m', // Force Minimal layout for parser compatibility
  ];
  if (igneous.isNotEmpty) parts.add('igneous=$igneous');
  if (sk.isNotEmpty)      parts.add('sk=$sk');
  return parts.join('; ');
}

// ── Settings Page ───────────────────────────────────────────────────────────

class CookieLoginPage extends ConsumerStatefulWidget {
  const CookieLoginPage({super.key});

  @override
  ConsumerState<CookieLoginPage> createState() => _CookieLoginPageState();
}

class _CookieLoginPageState extends ConsumerState<CookieLoginPage> {
  final _memberIdCtrl  = TextEditingController();
  final _passHashCtrl  = TextEditingController();
  final _igneousCtrl   = TextEditingController();
  final _skCtrl        = TextEditingController();
  bool _saving         = false;
  bool _loggedIn       = false;

  @override
  void initState() {
    super.initState();
    _loadSaved();
  }

  Future<void> _loadSaved() async {
    try {
      final cookies = await readAllCookies();
      if (!mounted) return;
      setState(() {
        _memberIdCtrl.text = cookies[kCookieMemberId] ?? '';
        _passHashCtrl.text = cookies[kCookiePassHash] ?? '';
        _igneousCtrl.text  = cookies[kCookieIgneous]  ?? '';
        _skCtrl.text       = cookies[kCookieSk]       ?? '';
        _loggedIn = _memberIdCtrl.text.isNotEmpty && _passHashCtrl.text.isNotEmpty;
      });
    } catch (_) {
      // ignore: secure storage unavailable (e.g. widget tests)
    }
  }

  Future<void> _save() async {
    if (_memberIdCtrl.text.trim().isEmpty || _passHashCtrl.text.trim().isEmpty) {
      _showSnack('ipb_member_id 和 ipb_pass_hash 为必填项', isError: true);
      return;
    }

    setState(() => _saving = true);
    try {
      await writeCookie(kCookieMemberId, _memberIdCtrl.text.trim());
      await writeCookie(kCookiePassHash, _passHashCtrl.text.trim());
      await writeCookie(kCookieIgneous, _igneousCtrl.text.trim());
      await writeCookie(kCookieSk, _skCtrl.text.trim());

      // Sync to Rust network client immediately
      final cookieStr = _buildCookieString(
        _memberIdCtrl.text.trim(),
        _passHashCtrl.text.trim(),
        _igneousCtrl.text.trim(),
        _skCtrl.text.trim(),
      );
      await syncCookies(cookieString: cookieStr);

      ref.read(accountInfoProvider.notifier).update('UID: ${_memberIdCtrl.text.trim()}', '');

      setState(() { _loggedIn = true; _saving = false; });
      _showSnack('✅ Cookie 已保存并同步到 Rust 引擎！');
    } catch (e) {
      setState(() => _saving = false);
      _showSnack('保存失败: $e', isError: true);
    }
  }

  Future<void> _clear() async {
    for (final key in kCookieKeys) {
      await deleteCookie(key);
    }
    _memberIdCtrl.clear();
    _passHashCtrl.clear();
    _igneousCtrl.clear();
    _skCtrl.clear();
    ref.read(accountInfoProvider.notifier).logout();
    setState(() => _loggedIn = false);
    _showSnack('已退出登录');
  }

  void _showSnack(String msg, {bool isError = false}) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(
      content: Text(msg),
      backgroundColor: isError ? const Color(0xFFE94560) : const Color(0xFF4DB6AC),
      behavior: SnackBarBehavior.floating,
    ));
  }

  @override
  void dispose() {
    _memberIdCtrl.dispose();
    _passHashCtrl.dispose();
    _igneousCtrl.dispose();
    _skCtrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFFF2F2F7), // iOS style background
      appBar: AppBar(
        backgroundColor: Colors.white,
        foregroundColor: Colors.black,
        elevation: 0,
        title: const Text('Cookie 登录', style: TextStyle(fontWeight: FontWeight.bold)),
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // ── Status Card ──────────────────────────────────────────
            Container(
              width: double.infinity,
              padding: const EdgeInsets.all(16),
              decoration: BoxDecoration(
                color: Colors.white,
                borderRadius: BorderRadius.circular(12),
                border: Border.all(
                  color: _loggedIn ? Colors.green : Colors.red,
                  width: 1,
                ),
              ),
              child: Row(
                children: [
                  Icon(
                    _loggedIn ? Icons.check_circle : Icons.warning_rounded,
                    color: _loggedIn ? Colors.green : Colors.red,
                    size: 28,
                  ),
                  const SizedBox(width: 12),
                  Text(
                    _loggedIn ? '已登录 ✓' : '未登录 — 请填写 Cookie',
                    style: TextStyle(
                      color: _loggedIn ? Colors.green : Colors.red,
                      fontWeight: FontWeight.bold,
                      fontSize: 16,
                    ),
                  ),
                ],
              ),
            ),

            const SizedBox(height: 24),
            
            const Text(
              '从浏览器复制 e-hentai.org 的 Cookie 值填入下方。\n'
              'ipb_member_id 和 ipb_pass_hash 为必填项。\n'
              '填写 igneous 可访问 ExHentai（前提是账号已开通）。',
              style: TextStyle(color: Colors.black54, fontSize: 12, height: 1.6),
            ),

            const SizedBox(height: 20),

            Container(
              decoration: BoxDecoration(
                color: Colors.white,
                borderRadius: BorderRadius.circular(12),
              ),
              padding: const EdgeInsets.all(16),
              child: Column(
                children: [
                  _CookieField(
                    label: 'ipb_member_id',
                    hint: '例: 4578421',
                    controller: _memberIdCtrl,
                    required: true,
                  ),
                  const Divider(height: 24),
                  _CookieField(
                    label: 'ipb_pass_hash',
                    hint: '例: 351277a3a...',
                    controller: _passHashCtrl,
                    required: true,
                    obscure: true,
                  ),
                  const Divider(height: 24),
                  _CookieField(
                    label: 'igneous',
                    hint: '例: 6s7me9yrs5rz... (ExHentai 需要)',
                    controller: _igneousCtrl,
                  ),
                  const Divider(height: 24),
                  _CookieField(
                    label: 'sk',
                    hint: '例: fe0jeznqn7hbm...',
                    controller: _skCtrl,
                  ),
                ],
              ),
            ),

            const SizedBox(height: 24),

            // ── Buttons ──────────────────────────────────────────────
            SizedBox(
              width: double.infinity,
              height: 50,
              child: ElevatedButton(
                onPressed: _saving ? null : _save,
                style: ElevatedButton.styleFrom(
                  backgroundColor: Colors.blue,
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(12),
                  ),
                ),
                child: _saving
                    ? const SizedBox(
                        width: 20,
                        height: 20,
                        child: CircularProgressIndicator(
                          color: Colors.white,
                          strokeWidth: 2,
                        ),
                      )
                    : const Text(
                        '保存并登录',
                        style: TextStyle(
                          fontSize: 16,
                          fontWeight: FontWeight.bold,
                          color: Colors.white,
                        ),
                      ),
              ),
            ),

            if (_loggedIn) ...[
              const SizedBox(height: 12),
              SizedBox(
                width: double.infinity,
                height: 50,
                child: TextButton(
                  onPressed: _clear,
                  style: TextButton.styleFrom(
                    foregroundColor: Colors.red,
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(12),
                    ),
                  ),
                  child: const Text('退出登录'),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class _CookieField extends StatefulWidget {
  final String label;
  final String hint;
  final TextEditingController controller;
  final bool required;
  final bool obscure;

  const _CookieField({
    required this.label,
    required this.hint,
    required this.controller,
    this.required = false,
    this.obscure = false,
  });

  @override
  State<_CookieField> createState() => _CookieFieldState();
}

class _CookieFieldState extends State<_CookieField> {
  late bool _obscured;

  @override
  void initState() {
    super.initState();
    _obscured = widget.obscure;
  }

  @override
  Widget build(BuildContext context) {
    final controller = widget.controller;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Text(
              widget.label,
              style: const TextStyle(
                color: Colors.black87,
                fontSize: 14,
                fontWeight: FontWeight.w500,
              ),
            ),
            if (widget.required)
              const Text(
                ' *',
                style: TextStyle(color: Colors.red, fontSize: 14),
              ),
          ],
        ),
        const SizedBox(height: 8),
        TextField(
          controller: controller,
          obscureText: _obscured,
          style: const TextStyle(color: Colors.black87, fontSize: 14),
          decoration: InputDecoration(
            hintText: widget.hint,
            hintStyle: const TextStyle(color: Colors.black38, fontSize: 13),
            filled: true,
            fillColor: const Color(0xFFF2F2F7),
            contentPadding: const EdgeInsets.symmetric(
              horizontal: 14, vertical: 12),
            border: OutlineInputBorder(
              borderRadius: BorderRadius.circular(8),
              borderSide: BorderSide.none,
            ),
            suffixIcon: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                if (widget.obscure)
                  IconButton(
                    icon: Icon(
                      _obscured ? Icons.visibility_off : Icons.visibility,
                      color: Colors.black38,
                      size: 20,
                    ),
                    tooltip: _obscured ? '显示明文' : '隐藏',
                    padding: EdgeInsets.zero,
                    constraints: const BoxConstraints(minWidth: 32, minHeight: 32),
                    visualDensity: VisualDensity.compact,
                    onPressed: () => setState(() => _obscured = !_obscured),
                  ),
                IconButton(
                  icon: const Icon(Icons.copy, color: Colors.black38, size: 20),
                  tooltip: '复制',
                  padding: EdgeInsets.zero,
                  constraints: const BoxConstraints(minWidth: 32, minHeight: 32),
                  visualDensity: VisualDensity.compact,
                  onPressed: () {
                    final text = controller.text.trim();
                    if (text.isEmpty) return;
                    Clipboard.setData(ClipboardData(text: text));
                    ScaffoldMessenger.of(context).showSnackBar(
                      SnackBar(content: Text('已复制 ${widget.label}')),
                    );
                  },
                ),
                IconButton(
                  icon: const Icon(Icons.content_paste, color: Colors.black38, size: 20),
                  tooltip: '从剪贴板粘贴',
                  padding: EdgeInsets.zero,
                  constraints: const BoxConstraints(minWidth: 32, minHeight: 32),
                  visualDensity: VisualDensity.compact,
                  onPressed: () async {
                    final data = await Clipboard.getData('text/plain');
                    if (data?.text != null) {
                      controller.text = data!.text!.trim();
                    }
                  },
                ),
              ],
            ),
          ),
        ),
      ],
    );
  }
}
