package com.niki914.uikit.base

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring

/**
 * Phase 1 UI polish：集中动效参数（spring / easing / 时长 / 常量）。
 *
 * 只收敛「规格」，不改组件布局；新动效一律从这里取参，禁止散落魔法数字。
 * 命名对齐 Material Motion 3 词汇（standard / emphasized / decelerate）。
 */
object MotionTheme {

    // ── 常量 ────────────────────────────────────────────────────────────────

    /** 按压缩放（LiquidButton / 发送按钮等可点组件统一取值）。 */
    const val PRESS_SCALE = 0.97f

    // ── 时长（ms）────────────────────────────────────────────────────────────

    const val DURATION_SHORT = 120
    const val DURATION_MEDIUM = 220
    const val DURATION_LONG = 320

    // ── Spring 规格 ──────────────────────────────────────────────────────────

    /**
     * 按压反馈：中等刚度 + 欠阻尼，按下有「陷下去」的弹性，松手回弹干脆。
     */
    fun <T> pressSpring(): SpringSpec<T> =
        spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium)

    /**
     * 标准动效（列表重排、展开收起、位置变化）：接近临界阻尼，不抢戏。
     */
    fun <T> standardSpring(): SpringSpec<T> =
        spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)

    /**
     * 强调动效（新消息入场、结果出现）：略欠阻尼，带一点生命力。
     */
    fun <T> emphasizedSpring(): SpringSpec<T> =
        spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMedium)

    // ── Easing（tween / infiniteRepeatable 用）───────────────────────────────

    /** 标准：进出均衡。 */
    val EasingStandard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** 强调减速：入场（出现、展开）。 */
    val EasingEmphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** 强调加速：退场（消失、收起）。 */
    val EasingEmphasizedAccelerate: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
}
