package com.niki914.zafiro.repo

import android.content.Context
import com.niki914.zafiro.app.R
import com.niki914.zafiro.settings.model.RuntimeExecutionRule
import com.niki914.zafiro.settings.model.RuntimeExecutionRuleEnabledMode

internal object LocalSettingsDefaults {
    const val DEFAULT_SYSTEM_PROMPT = ""

    // Seed memories live in res/raw/seed_memories.txt，一行一条（与 seed_py_*.py 同一模式）。
    fun defaultMemories(context: Context): List<String> {
        val text = context.resources.openRawResource(R.raw.seed_memories)
            .bufferedReader().use { it.readText() }
        return text.lines().map(String::trim).filter(String::isNotEmpty)
    }

    /** v2.0.0 起的默认危险模式（不含 su / setprop）。 */
    val DANGEROUS_PATTERNS = listOf(
        // 危险删改
        "\\brm\\s+-rf\\b",
        "\\brm\\s+-(?=[^\\s]*r)(?=[^\\s]*f)[^\\s]*\\b",
        "\\brm\\s+-r\\s+-f\\b",
        "\\brm\\s+(?=[^\\n]*--recursive\\b)(?=[^\\n]*--force\\b)[^\\n]*",
        "\\brm\\s+(?=[^\\n]*-(?:[^\\s-]*r[^\\s-]*|-[^-\\s]*recursive)\\b)(?=[^\\n]*-(?:[^\\s-]*f[^\\s-]*|-[^-\\s]*force)\\b)[^\\n]*",
        "\\bmkfs\\b",
        // 卸载相关
        "\\bpm\\s+uninstall\\b",
        "\\bcmd\\s+package\\s+uninstall\\b",
        // 破坏性系统操作
        "\\bdd\\b",
        "\\breboot\\b",
    )

    /**
     * v1.x 默认规则全文（含 su / setprop）——仅用于迁移判定：
     * 存储里的规则与此完全一致 = 用户从未改过，可安全升级到新默认；否则不动。
     */
    val LEGACY_V1_DANGEROUS_PATTERNS = DANGEROUS_PATTERNS + listOf("\\bsu\\b", "\\bsetprop\\b")

    /**
     * v2.0.0 Jarvis Mode：默认 CONFIRM 集合去掉 `\bsu\b` 与 `\bsetprop\b`。
     *  - su 本身不是破坏性操作 —— 特权 shell 的真正闸门是 Magisk/su 授权弹窗；
     *    应用层再拦一次只会制造 "operation was denied" 的死循环（后台轮次拿不到确认）。
     *  - setprop 同理：需要 root 才生效，root 已被 su 闸门保护。
     * 破坏性模式（rm -rf / mkfs / dd / reboot / pm uninstall）保留 CONFIRM。
     */
    val defaultExecutionRules = listOf(
        RuntimeExecutionRule(
            id = "builtin-dangerous",
            name = "高危操作",
            enabledMode = RuntimeExecutionRuleEnabledMode.CONFIRM,
            patterns = DANGEROUS_PATTERNS,
        ),
    )
}
