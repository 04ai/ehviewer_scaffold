package com.example.ehviewer_scaffold.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import kotlin.math.abs

/**
 * 苹果风格物理动画配置字典与非线性动力学数学模型。
 */
object Physics {
    /**
     * 极具 iOS 质感的弹簧参数配置
     * 稍微有一点点轻微回弹（Damping ratio ~0.75）
     */
    val AppleSpringSpec = spring<Float>(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessMediumLow
    )

    /**
     * 菜单收起、快速响应无回弹
     */
    val AppleSnappySpring = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium
    )

    /**
     * Apple 官方的 Rubber-banding (橡皮筋) 公式。
     * 当手势超出滚动边界时，提供非线性的渐近双曲函数阻力。
     *
     * @param rawOffset 手势实际拖拽的原始物理距离（px，带符号）
     * @param dimension 当前视口的绝对尺寸（屏幕宽度或高度，px）
     * @param constant 阻尼系数，iOS 标准取值通常为 0.55
     * @return 应用阻尼后的实际位移（px，带符号）
     */
    fun rubberBand(rawOffset: Float, dimension: Float, constant: Float = 0.55f): Float {
        if (dimension <= 0f) return 0f
        val absRaw = abs(rawOffset)
        // x = (x_raw * c * d) / (d + c * x_raw)
        val rubberBanded = (absRaw * constant * dimension) / (dimension + constant * absRaw)
        return if (rawOffset < 0) -rubberBanded else rubberBanded
    }
}
