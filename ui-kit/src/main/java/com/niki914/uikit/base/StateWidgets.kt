package com.niki914.uikit.base

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Phase 2 UI polish：状态指示 / 骨架屏 / 空状态三类通用小件。
 *
 * 与 Phase 1 的 [MotionTheme] 同源取参 —— 呼吸节奏用 MotionTheme 时长与
 * easing，入场用 emphasizedSpring；组件只管形态，不携带魔法数字。
 */

/**
 * 呼吸状态点：[pulse] = true 时 alpha/scale 缓慢呼吸（运行中警示、
 * kill 计数告警等需要「活着」视觉的位置用）。
 */
@Composable
fun PulsingDot(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    pulse: Boolean = true,
    dotSize: Dp = 8.dp,
) {
    val transition = rememberInfiniteTransition(label = "pulsingDot")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = MotionTheme.DURATION_MEDIUM * 2, easing = MotionTheme.EasingStandard),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulsingDotAlpha",
    )
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = MotionTheme.DURATION_MEDIUM * 2, easing = MotionTheme.EasingStandard),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulsingDotScale",
    )
    Box(
        modifier = modifier
            .size(dotSize)
            .graphicsLayer {
                this.alpha = if (pulse) alpha else 1f
                scaleX = if (pulse) scale else 1f
                scaleY = if (pulse) scale else 1f
            }
            .background(color, CircleShape),
    )
}

/**
 * 状态点 + 文本行：颜色/文本变化时点色平滑过渡（设置页服务状态、监听器
 * 状态等场景）。
 */
@Composable
fun StatusDotRow(
    text: String,
    color: Color,
    pulse: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val animatedColor by animateColorAsState(
        targetValue = color,
        animationSpec = tween(durationMillis = MotionTheme.DURATION_MEDIUM, easing = MotionTheme.EasingStandard),
        label = "statusDotColor",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        PulsingDot(color = animatedColor, pulse = pulse)
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 骨架屏行组：加载中的占位（行高与设置卡片行近似），alpha 呼吸代替内容；
 * 逐行 delay 做相位错开。
 */
@Composable
fun SkeletonList(
    rows: Int,
    modifier: Modifier = Modifier,
    rowHeight: Dp = 52.dp,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        repeat(rows) { index ->
            SkeletonRow(rowHeight = rowHeight, startDelayMillis = index * 120)
        }
    }
}

@Composable
private fun SkeletonRow(rowHeight: Dp, startDelayMillis: Int) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = MotionTheme.DURATION_LONG * 2,
                delayMillis = startDelayMillis,
                easing = MotionTheme.EasingStandard,
            ),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "skeletonAlpha",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(alpha)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(14.dp))
            .heightIn(min = rowHeight),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .padding(start = 16.dp)
                .size(width = 140.dp, height = 12.dp)
                .alpha(0.7f)
                .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(6.dp)),
        )
    }
}

/**
 * 空状态：图标（可选）+ 标题 + 说明，入场 alpha/scale emphasizedSpring。
 */
@Composable
fun EmptyStateView(
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    icon: ImageVector? = null,
) {
    val visibleState = remember { MutableTransitionState(false).apply { targetState = true } }
    val progress by animateFloatAsState(
        targetValue = if (visibleState.targetState) 1f else 0f,
        animationSpec = MotionTheme.emphasizedSpring(),
        label = "emptyStateEnter",
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .alpha(progress)
            .graphicsLayer {
                scaleX = 0.96f + 0.04f * progress
                scaleY = 0.96f + 0.04f * progress
            }
            .padding(horizontal = 16.dp, vertical = 20.dp),
    ) {
        if (icon != null) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(52.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (!body.isNullOrBlank()) {
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
