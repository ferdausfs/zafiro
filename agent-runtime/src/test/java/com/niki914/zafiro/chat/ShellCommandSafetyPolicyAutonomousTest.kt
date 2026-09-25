package com.niki914.zafiro.chat

import com.niki914.zafiro.chat.agentic.shell.ShellCommandSafetyPolicy
import com.niki914.zafiro.chat.agentic.shell.ToolPermissionCoordinator
import com.niki914.zafiro.chat.util.SilentLoggerRule
import com.niki914.zafiro.settings.model.RuntimeExecutionRule
import com.niki914.zafiro.settings.model.RuntimeExecutionRuleEnabledMode
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * v2.0.0 Jarvis Mode：自主执行模式下，后台轮次（无 UI 可确认）的 CONFIRM 规则
 * 直接放行，不再以 CONFIRM_UNAVAILABLE 失败收场；开关关闭时保持旧行为。
 */
class ShellCommandSafetyPolicyAutonomousTest {

    @get:Rule
    val silentLogger = SilentLoggerRule()

    private var originalUiResumed: Boolean = false
    private var originalHandler: (suspend (com.niki914.zafiro.chat.agentic.shell.ToolPermissionRequest) -> com.niki914.zafiro.chat.agentic.shell.ToolPermissionResponse)? = null

    @Before
    fun setUp() {
        originalUiResumed = ToolPermissionCoordinator.isUiResumed
        originalHandler = ToolPermissionCoordinator.backgroundConfirmationHandler
        ToolPermissionCoordinator.isUiResumed = false
        ToolPermissionCoordinator.backgroundConfirmationHandler = null
    }

    @After
    fun tearDown() {
        ToolPermissionCoordinator.isUiResumed = originalUiResumed
        ToolPermissionCoordinator.backgroundConfirmationHandler = originalHandler
    }

    private fun policy(autonomous: Boolean): ShellCommandSafetyPolicy =
        ShellCommandSafetyPolicy(
            listExecutionRules = {
                listOf(
                    RuntimeExecutionRule(
                        id = "rule-1",
                        name = "Confirm reboot",
                        enabledMode = RuntimeExecutionRuleEnabledMode.CONFIRM,
                        patterns = listOf("\\breboot\\b"),
                    )
                )
            },
            isUnlocked = { true },
            autonomousExecution = { autonomous },
        )

    @Test
    fun confirmUnavailable_autonomousOn_autoApproves() = runTest {
        val decision = policy(autonomous = true).evaluate("reboot now", "terminal")
        assertTrue("autonomous mode must auto-approve when no UI is available", decision.allowed)
    }

    @Test
    fun confirmUnavailable_autonomousOff_stillDenied() = runTest {
        val decision = policy(autonomous = false).evaluate("reboot now", "terminal")
        assertFalse(decision.allowed)
        assertTrue(decision.code, decision.code == "CONFIRM_UNAVAILABLE")
    }
}
