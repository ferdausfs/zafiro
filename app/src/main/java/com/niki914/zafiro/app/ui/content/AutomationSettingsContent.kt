package com.niki914.zafiro.app.ui.content

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.niki914.uikit.infra.ConfirmationLiquidDialog
import com.niki914.uikit.infra.LiquidDialog
import com.niki914.uikit.infra.component.LiquidTextField
import com.niki914.uikit.infra.component.MaterialTintLiquidButton
import com.niki914.uikit.infra.component.settings.SettingsPageSpec
import com.niki914.uikit.infra.component.settings.SettingsRowAction
import com.niki914.uikit.infra.component.settings.SettingsRowSpec
import com.niki914.uikit.infra.component.settings.SettingsSectionLayout
import com.niki914.uikit.infra.component.settings.SettingsSectionSpec
import com.niki914.uikit.infra.component.settings.SettingsSpecPageContent
import com.niki914.uikit.infra.nav.pageViewModel
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.automation.AutomationHub
import com.niki914.zafiro.app.automation.ZafiroAutomationService
import com.niki914.zafiro.app.ui.PageChromeContribution
import com.niki914.zafiro.app.ui.RegisterPageChrome
import com.niki914.zafiro.app.ui.model.AutomationSettingsIntent
import com.niki914.zafiro.app.ui.model.AutomationSettingsViewModel
import com.niki914.zafiro.app.ui.model.AutomationTriggerEditState
import com.niki914.zafiro.app.ui.model.AutomationTriggerItem
import com.niki914.zafiro.app.ui.nav.TopBarActionSpec
import com.niki914.zafiro.repo.AutomationTriggerAction
import com.niki914.zafiro.repo.AutomationTriggerSource

private const val TRIGGER_ROW_ID_PREFIX = "automation.trigger."

@Composable
fun AutomationSettingsContent() {
    val context = LocalContext.current
    val viewModel = pageViewModel<AutomationSettingsViewModel>()
    val uiState by viewModel.uiStateFlow.collectAsState()
    val serviceRunning by AutomationHub.serviceRunning.collectAsState()
    val listenerConnected by AutomationHub.listenerConnected.collectAsState()
    val recentActivity by AutomationHub.recentActivity.collectAsState()

    var notifGranted by remember { mutableStateOf(false) }
    var batteryExempt by remember { mutableStateOf(false) }

    fun refreshStatus() {
        notifGranted = AutomationHub.isNotificationAccessGranted(context)
        batteryExempt = AutomationHub.isIgnoringBatteryOptimizations(context)
    }

    LaunchedEffect(Unit) {
        viewModel.sendIntent(AutomationSettingsIntent.Load)
    }
    LaunchedEffect(Unit) {
        refreshStatus()
    }

    // 从系统设置页返回时刷新状态
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val createTitle = stringResource(R.string.automation_editor_title_create)
    val latestOnAdd by rememberUpdatedState({
        viewModel.sendIntent(AutomationSettingsIntent.StartCreate)
    })
    val pageChromeContribution = remember(createTitle) {
        PageChromeContribution(
            rightAction = TopBarActionSpec(
                icon = Icons.Default.Add,
                onClick = { latestOnAdd() },
                contentDescription = createTitle,
            ),
        )
    }
    RegisterPageChrome(pageChromeContribution)

    AutomationSettingsContentBody(
        uiState = uiState,
        notifGranted = notifGranted,
        batteryExempt = batteryExempt,
        serviceRunning = serviceRunning,
        listenerConnected = listenerConnected,
        recentActivity = recentActivity,
        onToggleService = { enabled ->
            if (enabled) {
                ZafiroAutomationService.start(context)
            } else {
                ZafiroAutomationService.stop(context)
            }
        },
        onGrantNotificationAccess = {
            context.startActivity(
                AutomationHub.notificationListenerSettingsIntent()
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        },
        onRequestBatteryExemption = {
            try {
                context.startActivity(
                    AutomationHub.batteryOptimizationIntent(context)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (t: Throwable) {
                context.startActivity(
                    android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
                        .let { android.content.Intent(it) }
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        },
        onTriggerClick = { index ->
            viewModel.sendIntent(AutomationSettingsIntent.StartEdit(index))
        },
        onTriggerToggle = { index, value ->
            viewModel.sendIntent(AutomationSettingsIntent.ItemEnabledChanged(index, value))
        },
        onAddClick = {
            viewModel.sendIntent(AutomationSettingsIntent.StartCreate)
        },
    )

    AutomationTriggerEditorDialog(
        editState = uiState.editState,
        isSaving = uiState.isSaving,
        viewModel = viewModel,
        onDismiss = {
            viewModel.sendIntent(AutomationSettingsIntent.DismissEditor)
        },
        onSave = {
            viewModel.sendIntent(AutomationSettingsIntent.Save)
        },
    )

    ConfirmationLiquidDialog(
        visible = uiState.deleteTarget != null,
        onDismissRequest = {
            viewModel.sendIntent(AutomationSettingsIntent.DismissDeleteConfirmation)
        },
        title = stringResource(R.string.automation_delete_dialog_title),
        text = stringResource(
            R.string.automation_delete_dialog_text,
            uiState.deleteTarget?.name.orEmpty(),
        ),
        negativeButtonText = stringResource(R.string.delete_dialog_cancel),
        positiveButtonText = stringResource(R.string.delete_dialog_confirm),
        onNegativeClick = {
            viewModel.sendIntent(AutomationSettingsIntent.DismissDeleteConfirmation)
        },
        onPositiveClick = {
            viewModel.sendIntent(AutomationSettingsIntent.ConfirmDelete)
        },
    )
}

@Composable
private fun AutomationSettingsContentBody(
    uiState: com.niki914.zafiro.app.ui.model.AutomationSettingsUiState,
    notifGranted: Boolean,
    batteryExempt: Boolean,
    serviceRunning: Boolean,
    listenerConnected: Boolean,
    recentActivity: List<String>,
    onToggleService: (Boolean) -> Unit,
    onGrantNotificationAccess: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
    onTriggerClick: (Int) -> Unit,
    onTriggerToggle: (Int, Boolean) -> Unit,
    onAddClick: () -> Unit,
) {
    val sections = mutableListOf<SettingsSectionSpec>()

    // ---- 状态区
    sections += SettingsSectionSpec(
        layout = SettingsSectionLayout.GroupedCard,
        rows = listOf(
            SettingsRowSpec.Toggle(
                id = "automation.proactive",
                title = stringResource(R.string.automation_proactive_mode),
                summary = stringResource(R.string.automation_proactive_mode_summary),
                checked = serviceRunning,
            ),
            SettingsRowSpec.Action(
                id = "automation.notif_access",
                title = stringResource(R.string.automation_notification_access),
                summary = stringResource(
                    if (notifGranted && listenerConnected) {
                        R.string.automation_notification_access_ok
                    } else if (notifGranted) {
                        R.string.automation_notification_access_granted_idle
                    } else {
                        R.string.automation_notification_access_missing
                    }
                ),
            ),
            SettingsRowSpec.Action(
                id = "automation.battery",
                title = stringResource(R.string.automation_battery_title),
                summary = stringResource(
                    if (batteryExempt) {
                        R.string.automation_battery_ok
                    } else {
                        R.string.automation_battery_missing
                    }
                ),
            ),
        ),
    )

    // ---- 触发器列表区
    val triggerRows = when {
        uiState.isLoading -> listOf(
            SettingsRowSpec.Message(
                title = stringResource(R.string.automation_loading),
                verticalPadding = 12.dp,
            )
        )

        uiState.items.isNotEmpty() -> uiState.items.mapIndexed { index, item ->
            SettingsRowSpec.ToggleNavigation(
                id = triggerRowId(index),
                title = item.name,
                summary = item.summarize(),
                checked = item.enabled,
            )
        }

        else -> listOf(
            SettingsRowSpec.Message(
                title = stringResource(R.string.automation_empty_hint),
                verticalPadding = 16.dp,
            )
        )
    }
    sections += SettingsSectionSpec(
        layout = SettingsSectionLayout.CardList,
        rows = triggerRows,
    )

    // ---- 最近活动区
    if (recentActivity.isNotEmpty()) {
        sections += SettingsSectionSpec(
            layout = SettingsSectionLayout.GroupedCard,
            rows = recentActivity.reversed().map { entry ->
                SettingsRowSpec.Message(
                    title = entry,
                    verticalPadding = 6.dp,
                )
            },
        )
    }

    if (uiState.inlineErrorResId != null) {
        sections += SettingsSectionSpec(
            layout = SettingsSectionLayout.GroupedCard,
            rows = listOf(
                SettingsRowSpec.Message(
                    title = stringResource(uiState.inlineErrorResId),
                    verticalPadding = 10.dp,
                )
            ),
        )
    }

    SettingsSpecPageContent(
        spec = SettingsPageSpec(
            description = stringResource(R.string.automation_page_description),
            sections = sections,
        ),
        onAction = { action ->
            when (action) {
                is SettingsRowAction.Click -> when (action.id) {
                    "automation.notif_access" -> onGrantNotificationAccess()
                    "automation.battery" -> onRequestBatteryExemption()
                    "automation.proactive" -> onToggleService(!serviceRunning)
                    else -> Unit
                }

                is SettingsRowAction.Navigate -> {
                    if (action.id == "automation.proactive") {
                        onToggleService(!serviceRunning)
                    } else {
                        triggerIndexFromRowId(action.id)?.let(onTriggerClick)
                    }
                }

                is SettingsRowAction.ToggleChanged -> when {
                    action.id == "automation.proactive" ->
                        onToggleService(action.checked)

                    else -> triggerIndexFromRowId(action.id)?.let { index ->
                        onTriggerToggle(index, action.checked)
                    }
                }
            }
        },
    )
}

/** 触发器摘要行：来源 · 应用 · 关键词。 */
private fun AutomationTriggerItem.summarize(): String {
    val parts = mutableListOf<String>()
    parts += when (source) {
        AutomationTriggerSource.NOTIFICATION -> {
            val appPart = appPackage.ifBlank { "*" }
            val senderPart = senderContains.takeIf(String::isNotBlank)
                ?.let { " @$it" }
                .orEmpty()
            "$appPart$senderPart"
        }

        AutomationTriggerSource.FILE_DOWNLOAD -> "Download/"
    }
    if (keywords.isNotEmpty()) {
        parts += keywords.joinToString(prefix = "#", separator = " #")
    }
    parts += when (action) {
        AutomationTriggerAction.AGENT -> "Agent"
        AutomationTriggerAction.ALERT -> "Alert"
    }
    return parts.joinToString(" · ")
}

@Composable
private fun AutomationTriggerEditorDialog(
    editState: AutomationTriggerEditState?,
    isSaving: Boolean,
    viewModel: AutomationSettingsViewModel,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    var retained by remember {
        mutableStateOf<AutomationTriggerEditState?>(null)
    }
    LaunchedEffect(editState) {
        if (editState != null) retained = editState
    }
    val state = editState ?: retained

    LiquidDialog(
        visible = editState != null,
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(
                    if (state?.isCreate == true) {
                        R.string.automation_editor_title_create
                    } else {
                        R.string.automation_editor_title_edit
                    }
                ),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        content = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 2.dp),
            ) {
                LiquidTextField(
                    value = state?.name.orEmpty(),
                    onValueChange = { viewModel.sendIntent(AutomationSettingsIntent.NameChanged(it)) },
                    placeholder = stringResource(R.string.automation_field_name_hint),
                    enabled = !isSaving,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                state?.nameErrorResId?.let {
                    FieldErrorText(it)
                }

                // 来源选择（点击循环切换）
                SettingsRowSpecSelector(
                    label = stringResource(R.string.automation_field_source),
                    value = when (state?.source) {
                        AutomationTriggerSource.NOTIFICATION ->
                            stringResource(R.string.automation_source_notification)

                        AutomationTriggerSource.FILE_DOWNLOAD ->
                            stringResource(R.string.automation_source_file)

                        null -> ""
                    },
                    enabled = !isSaving,
                ) {
                    val next = when (state?.source) {
                        AutomationTriggerSource.NOTIFICATION ->
                            AutomationTriggerSource.FILE_DOWNLOAD

                        else -> AutomationTriggerSource.NOTIFICATION
                    }
                    viewModel.sendIntent(AutomationSettingsIntent.SourceChanged(next))
                }

                if (state?.source == AutomationTriggerSource.NOTIFICATION) {
                    LiquidTextField(
                        value = state.appPackage,
                        onValueChange = {
                            viewModel.sendIntent(AutomationSettingsIntent.AppPackageChanged(it))
                        },
                        placeholder = stringResource(R.string.automation_field_app_hint),
                        enabled = !isSaving,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    LiquidTextField(
                        value = state.senderContains,
                        onValueChange = {
                            viewModel.sendIntent(AutomationSettingsIntent.SenderChanged(it))
                        },
                        placeholder = stringResource(R.string.automation_field_sender_hint),
                        enabled = !isSaving,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                LiquidTextField(
                    value = state?.keywordsInput.orEmpty(),
                    onValueChange = {
                        viewModel.sendIntent(AutomationSettingsIntent.KeywordsChanged(it))
                    },
                    placeholder = stringResource(R.string.automation_field_keywords_hint),
                    enabled = !isSaving,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                // 动作选择（点击循环切换）
                SettingsRowSpecSelector(
                    label = stringResource(R.string.automation_field_action),
                    value = when (state?.action) {
                        AutomationTriggerAction.AGENT ->
                            stringResource(R.string.automation_action_agent)

                        AutomationTriggerAction.ALERT ->
                            stringResource(R.string.automation_action_alert)

                        null -> ""
                    },
                    enabled = !isSaving,
                ) {
                    val next = when (state?.action) {
                        AutomationTriggerAction.AGENT -> AutomationTriggerAction.ALERT
                        else -> AutomationTriggerAction.AGENT
                    }
                    viewModel.sendIntent(AutomationSettingsIntent.ActionChanged(next))
                }

                if (state?.action == AutomationTriggerAction.AGENT) {
                    LiquidTextField(
                        value = state.prompt,
                        onValueChange = {
                            viewModel.sendIntent(AutomationSettingsIntent.PromptChanged(it))
                        },
                        placeholder = stringResource(R.string.automation_field_prompt_hint),
                        enabled = !isSaving,
                        singleLine = false,
                        minLines = 3,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    state.promptErrorResId?.let {
                        FieldErrorText(it)
                    }
                }

                LiquidTextField(
                    value = state?.cooldownInput.orEmpty(),
                    onValueChange = { value ->
                        viewModel.sendIntent(
                            AutomationSettingsIntent.CooldownChanged(
                                value.filter(Char::isDigit).take(5)
                            )
                        )
                    },
                    placeholder = stringResource(R.string.automation_field_cooldown_hint),
                    enabled = !isSaving,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        actions = {
            MaterialTintLiquidButton(
                text = stringResource(R.string.automation_editor_save),
                enabled = !isSaving,
                onClick = onSave,
                modifier = Modifier.weight(1f),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
            MaterialTintLiquidButton(
                text = stringResource(R.string.delete_dialog_cancel),
                enabled = !isSaving,
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurface,
            )
        },
    )
}

@Composable
private fun SettingsRowSpecSelector(
    label: String,
    value: String,
    enabled: Boolean,
    onCycle: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Start,
            modifier = Modifier.fillMaxWidth(),
        )
        androidx.compose.material3.OutlinedButton(
            onClick = onCycle,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = value)
        }
    }
}

@Composable
private fun FieldErrorText(@androidx.annotation.StringRes resId: Int) {
    Text(
        text = stringResource(resId),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Start,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun triggerRowId(index: Int): String = "$TRIGGER_ROW_ID_PREFIX$index"

private fun triggerIndexFromRowId(id: String): Int? {
    if (!id.startsWith(TRIGGER_ROW_ID_PREFIX)) return null
    return id.removePrefix(TRIGGER_ROW_ID_PREFIX).toIntOrNull()
}
