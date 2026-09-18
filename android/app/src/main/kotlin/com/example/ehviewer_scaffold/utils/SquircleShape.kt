package com.example.ehviewer_scaffold.utils

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/**
 * 苹果风格的超椭圆/平滑圆角形状 (Squircle)。
 * 这是一个简化的三次贝塞尔曲线近似实现。
 */
class SquircleShape(private val smoothing: Float = 0.6f) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val width = size.width
        val height = size.height
        val radius = (width.coerceAtMost(height) / 2) * smoothing

        val path = Path().apply {
            moveTo(radius, 0f)
            lineTo(width - radius, 0f)
            cubicTo(width, 0f, width, 0f, width, radius)
            lineTo(width, height - radius)
            cubicTo(width, height, width, height, width - radius, height)
            lineTo(radius, height)
            cubicTo(0f, height, 0f, height, 0f, height - radius)
            lineTo(0f, radius)
            cubicTo(0f, 0f, 0f, 0f, radius, 0f)
            close()
        }
        return Outline.Generic(path)
    }
}
