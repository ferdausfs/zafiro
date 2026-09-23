package com.niki914.zafiro.app.ui.nav

import androidx.annotation.StringRes
import com.niki914.zafiro.app.R

enum class ZafiroSettingsGroup(
    @StringRes val titleRes: Int,
    @StringRes val summaryRes: Int,
    val routeSuffix: String,
) {
    ModelConfig(
        titleRes = R.string.ui_settings_model_config,
        summaryRes = R.string.ui_settings_model_config_summary,
        routeSuffix = "model-config",
    ),
    Memory(
        titleRes = R.string.ui_settings_memory,
        summaryRes = R.string.ui_settings_memory_summary,
        routeSuffix = "memory",
    ),
    BuiltinTools(
        titleRes = R.string.ui_settings_builtin_tools,
        summaryRes = R.string.ui_settings_builtin_tools_summary,
        routeSuffix = "builtin-tools",
    ),
    Skills(
        titleRes = R.string.ui_settings_skills,
        summaryRes = R.string.ui_settings_skills_summary,
        routeSuffix = "skills",
    ),
    Mcp(
        titleRes = R.string.ui_settings_mcp,
        summaryRes = R.string.ui_settings_mcp_summary,
        routeSuffix = "mcp",
    ),
    TokenVault(
        titleRes = R.string.ui_settings_token_vault,
        summaryRes = R.string.ui_settings_token_vault_summary,
        routeSuffix = "token-vault",
    ),
    AgentPlan(
        titleRes = R.string.ui_settings_agent_plan,
        summaryRes = R.string.ui_settings_agent_plan_summary,
        routeSuffix = "agent-plan",
    ),
    Takeover(
        titleRes = R.string.ui_settings_takeover,
        summaryRes = R.string.ui_settings_takeover_summary,
        routeSuffix = "takeover",
    ),
    ExecutionRules(
        titleRes = R.string.ui_settings_execution_rules,
        summaryRes = R.string.ui_settings_execution_rules_summary,
        routeSuffix = "execution-rules",
    ),
    GeneralSettings(
        titleRes = R.string.ui_settings_general,
        summaryRes = R.string.ui_settings_general_summary,
        routeSuffix = "general-settings",
    ),
    Storage(
        titleRes = R.string.ui_settings_storage,
        summaryRes = R.string.ui_settings_storage_summary,
        routeSuffix = "storage",
    ),
    Automation(
        titleRes = R.string.ui_settings_automation,
        summaryRes = R.string.ui_settings_automation_summary,
        routeSuffix = "automation",
    ),
    Tasks(
        titleRes = R.string.ui_settings_tasks,
        summaryRes = R.string.ui_settings_tasks_summary,
        routeSuffix = "tasks",
    ),
    About(
        titleRes = R.string.ui_settings_about,
        summaryRes = R.string.ui_settings_about_summary,
        routeSuffix = "about",
    ),
}
