import 'dart:math';
import 'dart:ui' as ui;
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../providers/settings_provider.dart';

/// 伪毛玻璃容器（Glassmorphic Container，超高性能优化版）：
/// 半透明底色 + 噪点颗粒 + 顶部高光描边，模拟磨砂玻璃质感。
///
/// **不使用 `BackdropFilter` 实时模糊**：滚动/动画下几乎零额外开销
/// （真正的 BackdropFilter 每帧重采样背景，低端 GPU 容易掉帧）。
///
/// 受外观设置中「毛玻璃效果」开关（`glassEffect`）控制：
/// 关闭时降级为较实的半透明容器（无噪点/高光）。
class GlassContainer extends ConsumerWidget {
  final Widget child;

  /// 底色不透明度：值越小越通透。
  final double tintOpacity;

  /// 底色；默认深色主题用黑、浅色主题用白。
  final Color? tint;

  final BorderRadius? borderRadius;
  final EdgeInsetsGeometry padding;
  final Border? border;

  /// 固定高度（可选）。
  final double? height;

  /// 忽略全局开关强制启用（用于需要始终毛玻璃的装饰场景）。
  final bool forceEnabled;

  /// 强制禁用玻璃效果（哪怕全局开关开启）。用于特殊场景。
  final bool forceDisable;

  /// 是否绘制顶部高光细线（模拟玻璃反射）。
  final bool showHighlight;

  const GlassContainer({
    super.key,
    required this.child,
    this.tintOpacity = 0.3,
    this.tint,
    this.borderRadius,
    this.padding = EdgeInsets.zero,
    this.border,
    this.height,
    this.forceEnabled = false,
    this.forceDisable = false,
    this.showHighlight = true,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final enabled = !forceDisable &&
        (forceEnabled || ref.watch(appearanceProvider.select((s) => s.glassEffect)));
    final isDark = Theme.of(context).brightness == Brightness.dark;
    final radius = borderRadius ?? BorderRadius.zero;

    final glassGradient = isDark
        ? LinearGradient(
            begin: Alignment.topLeft,
            end: Alignment.bottomRight,
            colors: [
              Colors.white.withOpacity(enabled ? 0.12 : 0.08),
              Colors.white.withOpacity(enabled ? 0.04 : 0.04),
              effectiveTint.withOpacity(enabled ? tintOpacity : 0.7),
            ],
          )
        : LinearGradient(
            begin: Alignment.topLeft,
            end: Alignment.bottomRight,
            colors: [
              Colors.white.withOpacity(enabled ? 0.85 : 0.95),
              Colors.white.withOpacity(enabled ? 0.65 : 0.85),
            ],
          );

    final glassDecoration = BoxDecoration(
      gradient: glassGradient,
      borderRadius: radius,
      border: border ??
          Border.all(
            color: isDark
                ? Colors.white.withOpacity(enabled ? 0.22 : 0.08)
                : Colors.white.withOpacity(enabled ? 0.7 : 0.3),
            width: 1.0,
          ),
      boxShadow: enabled
          ? [
              BoxShadow(
                color: Colors.black.withOpacity(isDark ? 0.2 : 0.06),
                blurRadius: 12,
                spreadRadius: 0,
              ),
            ]
          : null,
    );

    final innerContent = Container(
      padding: padding,
      decoration: glassDecoration,
      child: enabled
          ? Stack(
              children: [
                const Positioned.fill(
                  child: IgnorePointer(
                    child: CustomPaint(painter: _NoisePainter(seed: 42)),
                  ),
                ),
                if (showHighlight)
                  Positioned(
                    top: 0,
                    left: 12,
                    right: 12,
                    child: IgnorePointer(
                      child: Container(
                        height: 1.5,
                        decoration: BoxDecoration(
                          gradient: LinearGradient(
                            colors: [
                              Colors.transparent,
                              isDark ? Colors.white.withOpacity(0.4) : Colors.white.withOpacity(0.85),
                              Colors.transparent,
                            ],
                          ),
                        ),
                      ),
                    ),
                  ),
                child,
              ],
            )
          : child,
    );

    return SizedBox(
      height: height,
      child: ClipRRect(
        borderRadius: radius,
        child: innerContent,
      ),
    );
  }

  Color get effectiveTint => tint ?? Colors.black;
}

/// 磨砂颗粒噪点（硬件加速 + 图形录制缓存版）：
class _NoisePainter extends CustomPainter {
  const _NoisePainter({required this.seed});
  final int seed;

  static final Map<String, ui.Picture> _pictureCache = {};
  static const int _maxCacheSize = 20;

  @override
  void paint(Canvas canvas, Size size) {
    if (size.width <= 0 || size.height <= 0) return;

    final key = '${size.width.round()}x${size.height.round()}_$seed';
    final cached = _pictureCache[key];
    if (cached != null) {
      canvas.drawPicture(cached);
      return;
    }

    if (_pictureCache.length >= _maxCacheSize) {
      _pictureCache.clear();
    }

    final recorder = ui.PictureRecorder();
    final c = Canvas(recorder);
    final rnd = Random(seed);
    const step = 5.0;

    final whitePoints = <Offset>[];
    final blackPoints = <Offset>[];

    for (double y = 0; y < size.height; y += step) {
      for (double x = 0; x < size.width; x += step) {
        if (rnd.nextDouble() < 0.25) {
          if (rnd.nextBool()) {
            whitePoints.add(Offset(x, y));
          } else {
            blackPoints.add(Offset(x, y));
          }
        }
      }
    }

    final whitePaint = Paint()
      ..color = Colors.white.withOpacity(0.09)
      ..strokeWidth = 1.0;
    final blackPaint = Paint()
      ..color = Colors.black.withOpacity(0.09)
      ..strokeWidth = 1.0;

    if (whitePoints.isNotEmpty) {
      c.drawPoints(ui.PointMode.points, whitePoints, whitePaint);
    }
    if (blackPoints.isNotEmpty) {
      c.drawPoints(ui.PointMode.points, blackPoints, blackPaint);
    }

    final picture = recorder.endRecording();
    _pictureCache[key] = picture;
    canvas.drawPicture(picture);
  }

  @override
  bool shouldRepaint(covariant _NoisePainter oldDelegate) => oldDelegate.seed != seed;
}
