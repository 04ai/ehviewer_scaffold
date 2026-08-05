import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:local_auth/local_auth.dart';
import '../providers/settings_provider.dart';

class AppLockWrapper extends ConsumerStatefulWidget {
  final Widget child;

  const AppLockWrapper({super.key, required this.child});

  @override
  ConsumerState<AppLockWrapper> createState() => _AppLockWrapperState();
}

class _AppLockWrapperState extends ConsumerState<AppLockWrapper> with WidgetsBindingObserver {
  final LocalAuthentication _auth = LocalAuthentication();
  bool _isAuthenticating = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    // Attempt authentication immediately if locked on startup
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (ref.read(securityProvider).isLocked) {
        _authenticate();
      }
    });
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    final security = ref.read(securityProvider);
    if (!security.enableBiometrics) return;

    if (state == AppLifecycleState.paused || state == AppLifecycleState.hidden) {
      // App went to background. `hidden` (Android 11+) is used instead of
      // `inactive` so transient system dialogs (notifications, permissions)
      // don't lock the app mid-reading.
      security.setLocked(true);
    } else if (state == AppLifecycleState.resumed) {
      // App came to foreground
      if (security.isLocked && !_isAuthenticating) {
        _authenticate();
      }
    }
  }

  Future<void> _authenticate() async {
    if (_isAuthenticating) return;
    
    setState(() {
      _isAuthenticating = true;
    });
    
    bool authenticated = false;
    try {
      authenticated = await _auth.authenticate(
        localizedReason: '请验证指纹/面容以解锁画廊',
        options: const AuthenticationOptions(
          stickyAuth: true,
          biometricOnly: false,
        ),
      );
    } catch (e) {
      debugPrint("Authentication error: $e");
    }
    
    setState(() {
      _isAuthenticating = false;
    });

    if (authenticated) {
      ref.read(securityProvider.notifier).setLocked(false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final isLocked = ref.watch(securityProvider.select((s) => s.isLocked));

    return Directionality(
      textDirection: TextDirection.ltr,
      child: Stack(
        children: [
        widget.child,
        if (isLocked)
          Positioned.fill(
            child: Container(
              color: Theme.of(context).scaffoldBackgroundColor,
              child: Center(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    const Icon(Icons.lock_outline, size: 64, color: Colors.grey),
                    const SizedBox(height: 16),
                    const Text('画廊已锁定', style: TextStyle(fontSize: 20, color: Colors.grey)),
                    const SizedBox(height: 32),
                    ElevatedButton.icon(
                      onPressed: _isAuthenticating ? null : _authenticate,
                      icon: const Icon(Icons.fingerprint),
                      label: const Text('点击解锁'),
                      style: ElevatedButton.styleFrom(
                        padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 12),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
      ],
    ),
    );
  }
}
