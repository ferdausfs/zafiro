package com.niki914.zafiro.app.ui.content

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.niki914.uikit.infra.component.SettingsGroupCard
import com.niki914.uikit.infra.component.SettingsListPageContent
import com.niki914.uikit.infra.component.SettingNavigationItem
import com.niki914.uikit.infra.nav.pageViewModel
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.model.BackgroundTasksIntent
import com.niki914.zafiro.app.ui.model.BackgroundTasksViewModel
import com.niki914.zafiro.app.ui.model.FallbackIntent
import com.niki914.zafiro.app.ui.model.FallbackViewModel
import com.niki914.zafiro.app.ui.model.ProviderImportIntent
import com.niki914.zafiro.app.ui.model.ProviderImportViewModel
import com.niki914.zafiro.repo.BackgroundTaskStatus

/**
 * Provider 导入对话框（Feature: Dynamic Provider Framework / Script-to-Config）：
 * 上半粘贴区 + Parse；解析成功展开编辑表单（name/baseUrl/model/key）；
 * 保存 = 自定义 Provider（key 入凭证库）+ vault-backed SavedLlmConfig 置 active。
 */
@Composable
fun ProviderImportDialog(
    onDismiss: () -> Unit,
) {
    val viewModel = pageViewModel<ProviderImportViewModel>(key = "provider-import")
    val state by viewModel.uiStateFlow.collectAsState()

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.ui_settings_import_dialog_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )

                if (!state.parsed) {
                    LabeledTextField(
                        label = stringResource(R.string.ui_settings_import_input_hint),
                        value = state.input,
                        onValueChange = { viewModel.sendIntent(ProviderImportIntent.UpdateInput(it)) },
                        minLines = 5,
                        maxLines = 8,
                    )
                    androidx.compose.material3.TextButton(
                        onClick = { viewModel.sendIntent(ProviderImportIntent.Parse) },
                    ) {
                        Text(stringResource(R.string.ui_settings_import_parse))
                    }
                } else {
                    LabeledTextField(
                        label = stringResource(R.string.ui_settings_import_name_label),
                        value = state.nameInput,
                        onValueChange = { viewModel.sendIntent(ProviderImportIntent.UpdateName(it)) },
                        singleLine = true,
                    )
                    LabeledTextField(
                        label = stringResource(R.string.ui_settings_import_base_url_label),
                        value = state.baseUrlInput,
                        onValueChange = { viewModel.sendIntent(ProviderImportIntent.UpdateBaseUrl(it)) },
                        singleLine = true,
                    )
                    LabeledTextField(
                        label = stringResource(R.string.ui_settings_import_model_label),
                        value = state.modelInput,
                        onValueChange = { viewModel.sendIntent(ProviderImportIntent.UpdateModel(it)) },
                        singleLine = true,
                    )
                    LabeledTextField(
                        label = stringResource(R.string.ui_settings_import_api_key_label),
                        value = state.apiKeyInput,
                        onValueChange = { viewModel.sendIntent(ProviderImportIntent.UpdateApiKey(it)) },
                        singleLine = true,
                        visualTransformation = if (state.apiKeyVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        keyboardType = KeyboardType.Password,
                        trailing = {
                            androidx.compose.material3.TextButton(
                                onClick = { viewModel.sendIntent(ProviderImportIntent.ToggleApiKeyVisibility) },
                            ) {
                                Text(
                                    stringResource(
                                        if (state.apiKeyVisible) {
                                            R.string.ui_settings_import_hide
                                        } else {
                                            R.string.ui_settings_import_show
                                        }
                                    )
                                )
                            }
                        },
                    )
                }

                state.error?.let { error ->
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    androidx.compose.material3.TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.dialog_cancel))
                    }
                    if (state.parsed) {
                        androidx.compose.material3.Button(
                            onClick = {
                                viewModel.sendIntent(ProviderImportIntent.Save)
                            },
                            enabled = !state.isSaving,
                        ) {
                            Text(stringResource(R.string.ui_settings_import_save))
                        }
                    } else {
                        androidx.compose.material3.Button(
                            onClick = {
                                viewModel.sendIntent(ProviderImportIntent.Parse)
                            },
                        ) {
                            Text(stringResource(R.string.ui_settings_import_parse))
                        }
                    }
                }
            }
        }
    }

    // 保存成功 = state.visible 变 false（Dismiss 分支重置），同步关闭对话框。
    // 用 wasVisible 防 init 时立即 onDismiss（首帧 visible=false）
    var dialogWasVisible by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(state.visible) {
        if (state.visible) {
            dialogWasVisible = true
        } else if (dialogWasVisible) {
            onDismiss()
        }
    }
}

@Composable
private fun LabeledTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else 6,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardType: KeyboardType = KeyboardType.Text,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        androidx.compose.material3.OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp),
            singleLine = singleLine,
            minLines = minLines,
            maxLines = maxLines,
            visualTransformation = visualTransformation,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = keyboardType,
            ),
            trailingIcon = trailing,
        )
    }
}

/**
 * 自定义 Provider 手动添加对话框（Feature: Universal Custom Providers）：
 * 跳过脚本粘贴，直接填 name / base URL / model / key — 任何 OpenAI 兼容
 * /v1/chat/completions 端点填入 base URL 即可使用。保存逻辑与脚本导入共用
 * （key 入 TokenVault，保存后自动创建并激活 vault-backed SavedLlmConfig）。
 */
@Composable
fun CustomProviderEntryDialog(
    onDismiss: () -> Unit,
) {
    val viewModel = pageViewModel<ProviderImportViewModel>(key = "provider-custom")
    val state by viewModel.uiStateFlow.collectAsState()

    androidx.compose.runtime.LaunchedEffect(viewModel) {
        viewModel.sendIntent(ProviderImportIntent.ShowManual)
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.ui_provider_custom_add_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.ui_provider_custom_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                LabeledTextField(
                    label = stringResource(R.string.ui_settings_import_name_label),
                    value = state.nameInput,
                    onValueChange = { viewModel.sendIntent(ProviderImportIntent.UpdateName(it)) },
                    singleLine = true,
                )
                LabeledTextField(
                    label = stringResource(R.string.ui_settings_import_base_url_label),
                    value = state.baseUrlInput,
                    onValueChange = { viewModel.sendIntent(ProviderImportIntent.UpdateBaseUrl(it)) },
                    singleLine = true,
                )
                LabeledTextField(
                    label = stringResource(R.string.ui_settings_import_model_label),
                    value = state.modelInput,
                    onValueChange = { viewModel.sendIntent(ProviderImportIntent.UpdateModel(it)) },
                    singleLine = true,
                )
                LabeledTextField(
                    label = stringResource(R.string.ui_settings_import_api_key_label),
                    value = state.apiKeyInput,
                    onValueChange = { viewModel.sendIntent(ProviderImportIntent.UpdateApiKey(it)) },
                    singleLine = true,
                    visualTransformation = if (state.apiKeyVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardType = KeyboardType.Password,
                    trailing = {
                        androidx.compose.material3.TextButton(
                            onClick = { viewModel.sendIntent(ProviderImportIntent.ToggleApiKeyVisibility) },
                        ) {
                            Text(
                                stringResource(
                                    if (state.apiKeyVisible) {
                                        R.string.ui_settings_import_hide
                                    } else {
                                        R.string.ui_settings_import_show
                                    }
                                )
                            )
                        }
                    },
                )

                state.error?.let { error ->
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    androidx.compose.material3.TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.dialog_cancel))
                    }
                    androidx.compose.material3.Button(
                        onClick = { viewModel.sendIntent(ProviderImportIntent.Save) },
                        enabled = !state.isSaving,
                    ) {
                        Text(stringResource(R.string.ui_settings_import_save))
                    }
                }
            }
        }
    }

    // 保存成功后 state.visible 复位为 false → 同步关闭对话框。
    // dialogWasVisible 防止首帧 visible=false 时立即回调 onDismiss。
    var entryDialogWasVisible by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(state.visible) {
        if (state.visible) {
            entryDialogWasVisible = true
        } else if (entryDialogWasVisible) {
            onDismiss()
        }
    }
}

/**
 * 模型回退链配置对话框（Feature: Intelligent Model Fallback）：
 * 启用开关 + 有序链（上移/下移/移除）+ 剩余配置添加。改动即时持久化。
 */
@Composable
fun FallbackSettingsDialog(
    onDismiss: () -> Unit,
) {
    val viewModel = pageViewModel<FallbackViewModel>(key = "fallback-settings")
    val state by viewModel.uiStateFlow.collectAsState()

    androidx.compose.runtime.LaunchedEffect(viewModel) {
        viewModel.sendIntent(FallbackIntent.Show)
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.ui_settings_fallback_dialog_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.ui_settings_fallback_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.ui_settings_fallback_enabled),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Switch(
                        checked = state.enabled,
                        onCheckedChange = { viewModel.sendIntent(FallbackIntent.SetEnabled(it)) },
                    )
                }

                if (state.chain.isEmpty()) {
                    Text(
                        text = stringResource(R.string.ui_settings_fallback_empty_chain),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    state.chain.forEachIndexed { index, entry ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "${index + 1}. ${entry.name} (${entry.modelId})",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            androidx.compose.material3.IconButton(
                                onClick = { viewModel.sendIntent(FallbackIntent.Move(entry.id, -1)) },
                                enabled = index > 0,
                            ) {
                                Text("↑")
                            }
                            androidx.compose.material3.IconButton(
                                onClick = { viewModel.sendIntent(FallbackIntent.Move(entry.id, +1)) },
                                enabled = index < state.chain.lastIndex,
                            ) {
                                Text("↓")
                            }
                            androidx.compose.material3.IconButton(
                                onClick = { viewModel.sendIntent(FallbackIntent.RemoveModel(entry.id)) },
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null)
                            }
                        }
                    }
                }

                if (state.available.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.ui_settings_fallback_add),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    state.available.forEach { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.sendIntent(FallbackIntent.AddModel(entry.id))
                                }
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "${entry.name} (${entry.modelId})",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(Icons.Default.Add, contentDescription = null)
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    androidx.compose.material3.TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.dialog_done))
                    }
                }
            }
        }
    }
}

/**
 * 后台任务列表页（Feature: Asynchronous Background Execution）：任务台账 +
 * 运行中指示 + 清理已完成。
 */
@Composable
fun BackgroundTasksSettingsContent() {
    val viewModel = pageViewModel<BackgroundTasksViewModel>(key = "background-tasks")
    val state by viewModel.uiStateFlow.collectAsState()

    SettingsListPageContent {
        SettingsGroupCard {
            SettingNavigationItem(
                title = stringResource(R.string.ui_settings_tasks_clear_finished),
                summary = null,
                onClick = { viewModel.sendIntent(BackgroundTasksIntent.ClearFinished) },
            )
        }

        if (state.tasks.isEmpty()) {
            SettingsGroupCard {
                Text(
                    text = stringResource(R.string.task_list_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        } else {
            SettingsGroupCard {
                state.tasks.forEach { task ->
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = task.query.ifBlank { task.id },
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                )
                                Text(
                                    text = taskStatusLabel(task.status) +
                                            timeLabel(task.createdAt) +
                                            fallbackLabel(task.modelFallbacks),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            androidx.compose.material3.IconButton(
                                onClick = { viewModel.sendIntent(BackgroundTasksIntent.Delete(task.id)) },
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun taskStatusLabel(status: BackgroundTaskStatus): String = stringResource(
    when (status) {
        BackgroundTaskStatus.RUNNING -> R.string.task_status_running
        BackgroundTaskStatus.COMPLETED -> R.string.task_status_completed
        BackgroundTaskStatus.FAILED -> R.string.task_status_failed
        BackgroundTaskStatus.CANCELLED -> R.string.task_status_cancelled
        BackgroundTaskStatus.INTERRUPTED -> R.string.task_status_interrupted
    }
)

@Composable
private fun timeLabel(createdAt: Long): String {
    if (createdAt <= 0L) return ""
    val formatter = remember {
        java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
    }
    return " · " + formatter.format(java.util.Date(createdAt))
}

private fun fallbackLabel(fallbacks: Int): String =
    if (fallbacks > 0) " · ×$fallbacks" else ""
