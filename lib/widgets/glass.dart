import 'dart:math';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../providers/settings_provider.dart';

/// 伪毛玻璃容器（Glassmorphism，低性能开销版）：
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

  /// 强制禁用玻璃效果（哪怕全局开关开启）。用于抽屉等动画场景。
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
    final effectiveTint = tint ?? (isDark ? Colors.black : Colors.white);
    final radius = borderRadius ?? BorderRadius.zero;
    final effectiveBorder = border ??
        Border.all(
          color: isDark ? Colors.white12 : Colors.white.withOpacity(0.7),
          width: 0.5,
        );

    final base = Container(
      padding: padding,
      decoration: BoxDecoration(
        color: effectiveTint.withOpacity(enabled ? tintOpacity : (tintOpacity + 0.25).clamp(0.0, 0.8)),
        border: effectiveBorder,
      ),
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
                        height: 1,
                        decoration: const BoxDecoration(
                          gradient: LinearGradient(
                            colors: [
                              Colors.transparent,
                              Colors.white10,
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

    return SizedBox(height: height, child: ClipRRect(borderRadius: radius, child: base));
  }
}

/// 磨砂颗粒噪点：固定 seed 保证重绘一致（不闪烁），
/// 白色/黑色小点交错，模拟玻璃表面颗粒。
class _NoisePainter extends CustomPainter {
  const _NoisePainter({required this.seed});
  final int seed;

  @override
  void paint(Canvas canvas, Size size) {
    final rnd = Random(seed);
    final paint = Paint();
    const step = 5.0;
    for (double y = 0; y < size.height; y += step) {
      for (double x = 0; x < size.width; x += step) {
        if (rnd.nextDouble() < 0.22) {
          paint.color = (rnd.nextBool() ? Colors.white : Colors.black).withOpacity(0.05);
          canvas.drawRect(Rect.fromLTWH(x, y, 1, 1), paint);
        }
      }
    }
  }

  @override
  bool shouldRepaint(covariant _NoisePainter oldDelegate) => oldDelegate.seed != seed;
}
