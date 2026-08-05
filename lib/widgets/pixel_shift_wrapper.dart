import 'dart:async';
import 'dart:math';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../providers/settings_provider.dart';

/// A wrapper that slowly shifts its child randomly by a few pixels over time.
/// This prevents OLED screen burn-in for static UI elements.
class PixelShiftWrapper extends ConsumerStatefulWidget {
  final Widget child;

  const PixelShiftWrapper({super.key, required this.child});

  @override
  ConsumerState<PixelShiftWrapper> createState() => _PixelShiftWrapperState();
}

class _PixelShiftWrapperState extends ConsumerState<PixelShiftWrapper> {
  Timer? _timer;
  Offset _currentOffset = Offset.zero;
  final Random _random = Random();
  
  // Maximum shift radius in pixels
  static const double _maxShift = 2.0;

  @override
  void initState() {
    super.initState();
    // Shift every 1 minute
    _timer = Timer.periodic(const Duration(minutes: 1), (_) {
      _updateShift();
    });
  }

  void _updateShift() {
    // Only shift if the feature is enabled
    if (ref.read(appearanceProvider).pixelShift) {
      setState(() {
        // Random angle and distance within max radius
        final double angle = _random.nextDouble() * 2 * pi;
        final double distance = _random.nextDouble() * _maxShift;
        _currentOffset = Offset(
          cos(angle) * distance,
          sin(angle) * distance,
        );
      });
    } else if (_currentOffset != Offset.zero) {
      setState(() {
        _currentOffset = Offset.zero;
      });
    }
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final isEnabled = ref.watch(appearanceProvider.select((s) => s.pixelShift));
    
    if (!isEnabled) {
      return widget.child;
    }

    return AnimatedContainer(
      // We use AnimatedContainer with Transform for a super slow, unnoticeable transition
      duration: const Duration(seconds: 5), 
      curve: Curves.linear,
      transform: Matrix4.translationValues(_currentOffset.dx, _currentOffset.dy, 0.0),
      child: widget.child,
    );
  }
}
