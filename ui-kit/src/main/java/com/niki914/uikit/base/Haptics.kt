package com.niki914.uikit.base

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * Phase 1 UI polish：语义化触感封装。
 *
 * 统一触感词汇（light / success / failure），组件侧不再直接挑
 * [HapticFeedbackType]；底层映射集中在这一处，改手感不用全库搜索。
 *
 * 语义映射（compose-ui 1.8+ 类型）：
 *  - [ZafiroHaptics.light]   → ContextClick：发送、轻量确认（ActionBarButton 已内置）
 *  - [ZafiroHaptics.success] → Confirm：工具执行成功
 *  - [ZafiroHaptics.failure] → Reject：工具执行失败
 */
interface ZafiroHaptics {
    fun light()
    fun success()
    fun failure()
}

@Composable
fun rememberHaptics(): ZafiroHaptics {
    val feedback: HapticFeedback = LocalHapticFeedback.current
    return remember(feedback) {
        object : ZafiroHaptics {
            override fun light() = feedback.performHapticFeedback(HapticFeedbackType.ContextClick)
            override fun success() = feedback.performHapticFeedback(HapticFeedbackType.Confirm)
            override fun failure() = feedback.performHapticFeedback(HapticFeedbackType.Reject)
        }
    }
}
