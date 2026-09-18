package com.example.ehviewer_scaffold.utils

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import com.example.ehviewer_scaffold.ui.theme.Physics

/**
 * 苹果风格的卡片点击反馈 Modifier。
 * 移除默认 Ripple，点击时轻微下沉并带有触觉反馈（长按时），松开时以物理弹簧恢复。
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.appleCardClickable(
    haptic: HapticFeedbackManager,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = Physics.AppleSpringSpec,
        label = "card_scale"
    )

    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            // Optional: lower opacity slightly when pressed
            alpha = if (isPressed) 0.9f else 1f
        }
        .combinedClickable(
            interactionSource = interactionSource,
            indication = null, // Disable ripple
            onClick = onClick,
            onLongClick = {
                haptic.heavyClick()
                onLongClick?.invoke()
            }
        )
}
