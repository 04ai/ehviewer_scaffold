import 'package:flutter/material.dart';
import 'package:flutter/cupertino.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:webview_flutter/webview_flutter.dart';
import '../../src/rust/api.dart';
import '../../utils/secure_cookies.dart';
import '../cookie_login_page.dart';

class EhWebSettingsPage extends ConsumerStatefulWidget {
  const EhWebSettingsPage({super.key});

  @override
  ConsumerState<EhWebSettingsPage> createState() => _EhWebSettingsPageState();
}

class _EhWebSettingsPageState extends ConsumerState<EhWebSettingsPage> {
  bool _loading = true;
  bool _saving = false;
  String? _errorMessage;
  bool _isWebViewMode = false;
  
  // Native Form State
  String _loadHath = '0';
  String _imageSize = '0';
  final _widthCtrl = TextEditingController();
  final _heightCtrl = TextEditingController();
  String _titleDisplay = '0';
  String _archiverSettings = '0';
  String _displayMode = '0';
  final List<TextEditingController> _favControllers = List.generate(10, (_) => TextEditingController());
  Map<String, String> _rawParams = {};

  WebViewController? _webViewController;

  @override
  void initState() {
    super.initState();
    _loadConfig();
  }

  Future<void> _loadConfig() async {
    setState(() {
      _loading = true;
      _errorMessage = null;
    });

    try {
      final config = await fetchEhWebConfig();
      if (!mounted) return;
      setState(() {
        _loadHath = config.loadHath;
        _imageSize = config.imageSize;
        _widthCtrl.text = config.imageWidth;
        _heightCtrl.text = config.imageHeight;
        _titleDisplay = config.titleDisplay;
        _archiverSettings = config.archiverSettings;
        _displayMode = config.displayMode;
        
        for (int i = 0; i < 10 && i < config.favoriteNames.length; i++) {
          _favControllers[i].text = config.favoriteNames[i];
        }
        
        _rawParams = Map<String, String>.from(config.rawParams);
        _loading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _errorMessage = e.toString().replaceAll('Exception: ', '');
        _loading = false;
      });
    }
  }

  Future<void> _saveConfig() async {
    setState(() => _saving = true);
    try {
      final paramsMap = Map<String, String>.from(_rawParams);
      paramsMap['uh'] = _loadHath;
      paramsMap['xr'] = _imageSize;
      paramsMap['xr_w'] = _widthCtrl.text.trim();
      paramsMap['xr_h'] = _heightCtrl.text.trim();
      paramsMap['lt'] = _titleDisplay;
      paramsMap['ar'] = _archiverSettings;
      paramsMap['dm'] = _displayMode;
      
      for (int i = 0; i < 10; i++) {
        paramsMap['fn$i'] = _favControllers[i].text.trim();
      }
      
      paramsMap['apply'] = 'Apply';

      final paramsList = paramsMap.entries.map((e) => (e.key, e.value)).toList();
      final msg = await saveEhWebConfig(params: paramsList);

      if (!mounted) return;
      setState(() => _saving = false);
      
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
        content: Text('✅ $msg'),
        backgroundColor: const Color(0xFF4DB6AC),
        behavior: SnackBarBehavior.floating,
      ));
    } catch (e) {
      if (!mounted) return;
      setState(() => _saving = false);
      
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
        content: Text('❌ 保存失败: $e'),
        backgroundColor: const Color(0xFFE94560),
        behavior: SnackBarBehavior.floating,
      ));
    }
  }

  Future<void> _initWebView() async {
    final cookies = await readAllCookies();
    final cookieManager = WebViewCookieManager();
    const domains = ['e-hentai.org', 'exhentai.org', '.e-hentai.org', '.exhentai.org'];

    for (final domain in domains) {
      if (cookies.containsKey(kCookieMemberId)) {
        await cookieManager.setCookie(WebViewCookie(name: 'ipb_member_id', value: cookies[kCookieMemberId]!, domain: domain));
      }
      if (cookies.containsKey(kCookiePassHash)) {
        await cookieManager.setCookie(WebViewCookie(name: 'ipb_pass_hash', value: cookies[kCookiePassHash]!, domain: domain));
      }
      if (cookies.containsKey(kCookieIgneous)) {
        await cookieManager.setCookie(WebViewCookie(name: 'igneous', value: cookies[kCookieIgneous]!, domain: domain));
      }
      if (cookies.containsKey(kCookieSk)) {
        await cookieManager.setCookie(WebViewCookie(name: 'sk', value: cookies[kCookieSk]!, domain: domain));
      }
    }

    final controller = WebViewController()
      ..setJavaScriptMode(JavaScriptMode.unrestricted)
      ..loadRequest(Uri.parse('https://e-hentai.org/uconfig.php'));

    setState(() {
      _webViewController = controller;
    });
  }

  @override
  void dispose() {
    _widthCtrl.dispose();
    _heightCtrl.dispose();
    for (final ctrl in _favControllers) {
      ctrl.dispose();
    }
    super.dispose();
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
        title: const Text('E-Hentai 网站设置', style: TextStyle(fontWeight: FontWeight.bold)),
        actions: [
          IconButton(
            icon: Icon(_isWebViewMode ? Icons.tune : Icons.language),
            tooltip: _isWebViewMode ? '切换原生界面' : '切换网页模式',
            onPressed: () {
              setState(() {
                _isWebViewMode = !_isWebViewMode;
                if (_isWebViewMode && _webViewController == null) {
                  _initWebView();
                }
              });
            },
          ),
          if (!_isWebViewMode)
            IconButton(
              icon: const Icon(Icons.refresh),
              tooltip: '刷新',
              onPressed: _loading ? null : _loadConfig,
            ),
        ],
      ),
      body: _isWebViewMode
          ? (_webViewController == null
              ? const Center(child: CircularProgressIndicator())
              : WebViewWidget(controller: _webViewController!))
          : _buildNativeBody(context),
      bottomNavigationBar: !_isWebViewMode && _errorMessage == null && !_loading
          ? Container(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
              decoration: BoxDecoration(
                color: colorScheme.surface,
                boxShadow: [
                  BoxShadow(
                    color: Colors.black.withOpacity(0.05),
                    blurRadius: 8,
                    offset: const Offset(0, -2),
                  ),
                ],
              ),
              child: SafeArea(
                child: SizedBox(
                  width: double.infinity,
                  height: 48,
                  child: ElevatedButton.icon(
                    onPressed: _saving ? null : _saveConfig,
                    icon: _saving
                        ? const SizedBox(
                            width: 20,
                            height: 20,
                            child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white),
                          )
                        : const Icon(Icons.cloud_upload),
                    label: Text(_saving ? '正在保存...' : '保存设置到 E-Hentai 网站'),
                    style: ElevatedButton.styleFrom(
                      backgroundColor: colorScheme.primary,
                      foregroundColor: colorScheme.onPrimary,
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(12),
                      ),
                      textStyle: const TextStyle(fontSize: 16, fontWeight: FontWeight.bold),
                    ),
                  ),
                ),
              ),
            )
          : null,
    );
  }

  Widget _buildNativeBody(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;

    if (_loading) {
      return const Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            CircularProgressIndicator(),
            SizedBox(height: 16),
            Text('正在从 e-hentai.org 获取账号设置...', style: TextStyle(color: Colors.grey)),
          ],
        ),
      );
    }

    if (_errorMessage != null) {
      final isAuthErr = _errorMessage!.contains('未登录') || _errorMessage!.contains('会话已失效');
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(24.0),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Icon(
                isAuthErr ? Icons.lock_outline : Icons.error_outline,
                size: 64,
                color: Colors.redAccent,
              ),
              const SizedBox(height: 16),
              Text(
                _errorMessage!,
                textAlign: TextAlign.center,
                style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w500),
              ),
              const SizedBox(height: 24),
              if (isAuthErr)
                ElevatedButton.icon(
                  onPressed: () {
                    Navigator.pushReplacement(
                      context,
                      CupertinoPageRoute(builder: (_) => const CookieLoginPage()),
                    );
                  },
                  icon: const Icon(Icons.login),
                  label: const Text('前往 Cookie 登录'),
                  style: ElevatedButton.styleFrom(
                    padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 12),
                  ),
                )
              else
                ElevatedButton.icon(
                  onPressed: _loadConfig,
                  icon: const Icon(Icons.refresh),
                  label: const Text('重新加载'),
                ),
            ],
          ),
        ),
      );
    }

    return ListView(
      padding: const EdgeInsets.symmetric(vertical: 16),
      children: [
        // ── Group: Image & H@H Settings ───────────────────────────
        const Padding(
          padding: EdgeInsets.only(left: 32, bottom: 8),
          child: Text("图片加载与 Hentai@Home", style: TextStyle(color: Colors.grey, fontSize: 13, fontWeight: FontWeight.bold)),
        ),
        _CardGroup(
          children: [
            _DropdownTile(
              title: 'Hentai@Home 加载网络',
              subtitle: '控制是否通过 H@H 分布式网络节点获取画廊图片',
              value: _loadHath,
              items: const [
                DropdownMenuItem(value: '0', child: Text('任何客户端 (推荐)')),
                DropdownMenuItem(value: '1', child: Text('仅默认端口 (80/443)')),
                DropdownMenuItem(value: '2', child: Text('禁用 H@H (直连 HTTP)')),
              ],
              onChanged: (v) => setState(() => _loadHath = v!),
            ),
            const Divider(height: 1, indent: 16),
            _DropdownTile(
              title: '图片最大缩放限制',
              subtitle: '限制图片在网页/客户端获取时的最大像素宽度',
              value: _imageSize,
              items: const [
                DropdownMenuItem(value: '0', child: Text('Auto (原图/自动)')),
                DropdownMenuItem(value: '1', child: Text('780px 宽度')),
                DropdownMenuItem(value: '2', child: Text('980px 宽度')),
                DropdownMenuItem(value: '3', child: Text('1280px 宽度')),
                DropdownMenuItem(value: '4', child: Text('1600px 宽度')),
                DropdownMenuItem(value: '5', child: Text('2400px 宽度')),
              ],
              onChanged: (v) => setState(() => _imageSize = v!),
            ),
          ],
        ),

        const SizedBox(height: 24),

        // ── Group: Gallery Display ─────────────────────────────────
        const Padding(
          padding: EdgeInsets.only(left: 32, bottom: 8),
          child: Text("画廊名称与排版显示", style: TextStyle(color: Colors.grey, fontSize: 13, fontWeight: FontWeight.bold)),
        ),
        _CardGroup(
          children: [
            _DropdownTile(
              title: '默认标题语言',
              subtitle: '若画廊存在日文原标题，优先选择展示哪种语言标题',
              value: _titleDisplay,
              items: const [
                DropdownMenuItem(value: '0', child: Text('默认标题 (Default Title)')),
                DropdownMenuItem(value: '1', child: Text('日文标题 (Japanese Title)')),
              ],
              onChanged: (v) => setState(() => _titleDisplay = v!),
            ),
            const Divider(height: 1, indent: 16),
            _DropdownTile(
              title: '首页画廊列表布局',
              subtitle: 'E-Hentai 网页及部分默认排版方式',
              value: _displayMode,
              items: const [
                DropdownMenuItem(value: '0', child: Text('Compact (紧凑模式)')),
                DropdownMenuItem(value: '1', child: Text('Extended (扩展模式)')),
                DropdownMenuItem(value: '2', child: Text('Thumbnail (缩略图模式)')),
                DropdownMenuItem(value: '3', child: Text('Minimal (极简模式)')),
              ],
              onChanged: (v) => setState(() => _displayMode = v!),
            ),
            const Divider(height: 1, indent: 16),
            _DropdownTile(
              title: '归档下载设置 (Archiver)',
              subtitle: '图包压缩归档打包时的行为偏好',
              value: _archiverSettings,
              items: const [
                DropdownMenuItem(value: '0', child: Text('手动选择 (Manual Select)')),
                DropdownMenuItem(value: '1', child: Text('自动选择原图 (Auto Original)')),
                DropdownMenuItem(value: '2', child: Text('自动选择压缩图 (Auto Resample)')),
              ],
              onChanged: (v) => setState(() => _archiverSettings = v!),
            ),
          ],
        ),

        const SizedBox(height: 24),

        // ── Group: Favorite Categories ─────────────────────────────
        const Padding(
          padding: EdgeInsets.only(left: 32, bottom: 8),
          child: Text("收藏夹自定义类别名称", style: TextStyle(color: Colors.grey, fontSize: 13, fontWeight: FontWeight.bold)),
        ),
        _CardGroup(
          children: List.generate(10, (index) {
            return Column(
              children: [
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                  child: Row(
                    children: [
                      Container(
                        width: 24,
                        height: 24,
                        alignment: Alignment.center,
                        decoration: BoxDecoration(
                          color: colorScheme.primaryContainer,
                          shape: BoxShape.circle,
                        ),
                        child: Text(
                          '$index',
                          style: TextStyle(
                            fontSize: 12,
                            fontWeight: FontWeight.bold,
                            color: colorScheme.onPrimaryContainer,
                          ),
                        ),
                      ),
                      const SizedBox(width: 12),
                      Expanded(
                        child: TextField(
                          controller: _favControllers[index],
                          decoration: InputDecoration(
                            hintText: 'Favorites $index',
                            border: InputBorder.none,
                            isDense: true,
                            contentPadding: const EdgeInsets.symmetric(vertical: 8),
                          ),
                          style: const TextStyle(fontSize: 14),
                        ),
                      ),
                    ],
                  ),
                ),
                if (index < 9) const Divider(height: 1, indent: 48),
              ],
            );
          }),
        ),

        const SizedBox(height: 80),
      ],
    );
  }
}

class _CardGroup extends StatelessWidget {
  final List<Widget> children;

  const _CardGroup({required this.children});

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

class _DropdownTile extends StatelessWidget {
  final String title;
  final String subtitle;
  final String value;
  final List<DropdownMenuItem<String>> items;
  final ValueChanged<String?> onChanged;

  const _DropdownTile({
    required this.title,
    required this.subtitle,
    required this.value,
    required this.items,
    required this.onChanged,
  });

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(title, style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w500)),
                    const SizedBox(height: 2),
                    Text(
                      subtitle,
                      style: TextStyle(fontSize: 12, color: colorScheme.onSurface.withOpacity(0.55)),
                    ),
                  ],
                ),
              ),
              DropdownButton<String>(
                value: items.any((it) => it.value == value) ? value : items.first.value,
                items: items,
                onChanged: onChanged,
                underline: const SizedBox(),
                style: TextStyle(fontSize: 14, color: colorScheme.primary, fontWeight: FontWeight.w600),
              ),
            ],
          ),
        ],
      ),
    );
  }
}
