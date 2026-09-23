package com.niki914.zafiro.app.ui.content

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.niki914.uikit.infra.ConfirmationLiquidDialog
import com.niki914.uikit.infra.LiquidDialog
import com.niki914.uikit.infra.ProvideLiquidScreenContentForPreview
import com.niki914.uikit.infra.component.LiquidTextField
import com.niki914.uikit.infra.component.MaterialTintLiquidButton
import com.niki914.uikit.infra.component.SettingsGroupCard
import com.niki914.uikit.infra.component.SettingsListPageContent
import com.niki914.uikit.infra.component.SwipeDismissSettingsItemCard
import com.niki914.uikit.infra.nav.pageViewModel
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.PageBackHandler
import com.niki914.zafiro.app.ui.PageChromeContribution
import com.niki914.zafiro.app.ui.RegisterPageChrome
import com.niki914.zafiro.app.ui.model.TokenVaultSettingsIntent
import com.niki914.zafiro.app.ui.model.TokenVaultSettingsViewModel
import com.niki914.zafiro.app.ui.model.TokenVaultUiState
import com.niki914.zafiro.app.ui.nav.TopBarActionSpec
import kotlinx.coroutines.delay

/**
 * 设置 → Tokens：加密凭证库管理。
 * Agent 可通过 vault_token 工具读取这些凭据（list 仅名称/备注，get 取值）。
 */
@Composable
fun TokenVaultSettingsContent() {
    val viewModel = pageViewModel<TokenVaultSettingsViewModel>()
    val uiState by viewModel.uiStateFlow.collectAsState()
    val latestUiState by rememberUpdatedState(uiState)
    val latestViewModel by rememberUpdatedState(viewModel)
    val pageChromeContribution = remember(viewModel) {
        PageChromeContribution(
            rightAction = TopBarActionSpec(
                icon = Icons.Default.Add,
                onClick = {
                    viewModel.sendIntent(TokenVaultSettingsIntent.StartCreate)
                },
            ),
            backHandler = PageBackHandler(
                shouldConsumeBack = {
                    latestUiState.editingDialog != null || latestUiState.deleteConfirmation != null
                },
                onConsumeBack = {
                    if (latestUiState.editingDialog != null) {
                        latestViewModel.sendIntent(TokenVaultSettingsIntent.DismissEditDialog)
                    } else if (latestUiState.deleteConfirmation != null) {
                        latestViewModel.sendIntent(TokenVaultSettingsIntent.DismissDeleteConfirmation)
                    }
                },
            ),
        )
    }
    RegisterPageChrome(pageChromeContribution)

    LaunchedEffect(Unit) {
        viewModel.sendIntent(TokenVaultSettingsIntent.Load)
    }

    TokenVaultSettingsContentBody(
        uiState = uiState,
        onStartEdit = { name ->
            viewModel.sendIntent(TokenVaultSettingsIntent.StartEdit(name))
        },
        onRequestDelete = { name ->
            viewModel.sendIntent(TokenVaultSettingsIntent.RequestDelete(name))
        },
        onDialogValueChange = { name, value, note ->
            viewModel.sendIntent(
                TokenVaultSettingsIntent.EditValueChanged(name = name, value = value, note = note)
            )
        },
        onRequestReveal = { name ->
            viewModel.sendIntent(TokenVaultSettingsIntent.RequestReveal(name))
        },
        onDialogDismiss = {
            viewModel.sendIntent(TokenVaultSettingsIntent.DismissEditDialog)
        },
        onDialogSave = {
            viewModel.sendIntent(TokenVaultSettingsIntent.SaveEditDialog)
        },
        onDeleteConfirmationDismiss = {
            viewModel.sendIntent(TokenVaultSettingsIntent.DismissDeleteConfirmation)
        },
        onDeleteConfirmationConfirm = {
            viewModel.sendIntent(TokenVaultSettingsIntent.ConfirmDeleteItem)
        },
    )
}

@Composable
private fun TokenVaultSettingsContentBody(
    uiState: TokenVaultUiState,
    onStartEdit: (String) -> Unit,
    onRequestDelete: (String) -> Unit,
    onDialogValueChange: (String, String, String) -> Unit,
    onRequestReveal: (String) -> Unit,
    onDialogDismiss: () -> Unit,
    onDialogSave: () -> Unit,
    onDeleteConfirmationDismiss: () -> Unit,
    onDeleteConfirmationConfirm: () -> Unit,
) {
    val pageDescription = when {
        uiState.isLoading || uiState.items.isNotEmpty() -> {
            stringResource(R.string.token_vault_page_description)
        }

        else -> stringResource(R.string.token_vault_page_empty_description)
    }
    SettingsListPageContent(
        description = pageDescription,
    ) {
        if (uiState.isLoading) {
            SettingsGroupCard {
                TokenVaultListMessage(text = stringResource(R.string.token_vault_loading))
            }
        } else if (uiState.items.isNotEmpty()) {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                uiState.items.forEach { item ->
                    key(item.name) {
                        SwipeDismissSettingsItemCard(
                            title = item.name,
                            summary = item.note.takeIf(String::isNotBlank),
                            enabled = !uiState.isSaving,
                            onClick = {
                                onStartEdit(item.name)
                            },
                            onDismissRequest = {
                                onRequestDelete(item.name)
                            },
                        )
                    }
                }
            }
        }

        uiState.inlineError?.let { error ->
            TokenVaultInlineErrorText(error = error)
        }
    }

    TokenVaultEditDialog(
        state = uiState.editingDialog,
        revealedValue = uiState.revealed?.value.takeIf {
            uiState.revealed?.name == uiState.editingDialog?.oldName
        },
        isSaving = uiState.isSaving,
        onValueChange = onDialogValueChange,
        onRequestReveal = onRequestReveal,
        onDismissRequest = onDialogDismiss,
        onSaveClick = onDialogSave,
    )

    TokenVaultDeleteConfirmationDialog(
        state = uiState.deleteConfirmation,
        onDismissRequest = onDeleteConfirmationDismiss,
        onConfirmClick = onDeleteConfirmationConfirm,
    )
}

@Composable
private fun TokenVaultDeleteConfirmationDialog(
    state: com.niki914.zafiro.app.ui.model.TokenVaultDeleteConfirmationState?,
    onDismissRequest: () -> Unit,
    onConfirmClick: () -> Unit,
) {
    ConfirmationLiquidDialog(
        visible = state != null,
        onDismissRequest = onDismissRequest,
        title = stringResource(R.string.token_vault_delete_dialog_title),
        text = stringResource(R.string.token_vault_delete_dialog_text, state?.name.orEmpty()),
        negativeButtonText = stringResource(R.string.token_vault_delete_dialog_cancel),
        positiveButtonText = stringResource(R.string.token_vault_delete_dialog_confirm),
        onNegativeClick = onDismissRequest,
        onPositiveClick = onConfirmClick,
    )
}

@Composable
private fun TokenVaultEditDialog(
    state: com.niki914.zafiro.app.ui.model.TokenVaultEditDialogState?,
    revealedValue: String?,
    isSaving: Boolean,
    onValueChange: (String, String, String) -> Unit,
    onRequestReveal: (String) -> Unit,
    onDismissRequest: () -> Unit,
    onSaveClick: () -> Unit,
) {
    var retainedState by remember { mutableStateOf<com.niki914.zafiro.app.ui.model.TokenVaultEditDialogState?>(null) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(state) {
        if (state != null) {
            retainedState = state
            delay(100)
            focusRequester.requestFocus()
        }
    }
    val dialogState = state ?: retainedState

    // reveal 后把真实值填入输入框（编辑态留空 = 保留原值）
    LaunchedEffect(revealedValue, dialogState?.oldName) {
        val current = dialogState ?: return@LaunchedEffect
        if (!current.isCreate && revealedValue != null && current.value.isEmpty()) {
            onValueChange(current.name, revealedValue, current.note)
        }
    }

    LiquidDialog(
        visible = state != null,
        onDismissRequest = onDismissRequest,
        title = {
            Text(
                text = stringResource(
                    if (dialogState?.isCreate == true) {
                        R.string.token_vault_editor_title_create
                    } else {
                        R.string.token_vault_editor_title_edit
                    }
                ),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Start,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        content = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(horizontal = 2.dp),
            ) {
                Text(
                    text = stringResource(R.string.token_vault_field_name),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth(),
                )
                LiquidTextField(
                    value = dialogState?.name.orEmpty(),
                    onValueChange = { value ->
                        onValueChange(value, dialogState?.value.orEmpty(), dialogState?.note.orEmpty())
                    },
                    placeholder = stringResource(R.string.token_vault_field_name_hint),
                    enabled = !isSaving,
                    singleLine = true,
                    minLines = 1,
                    maxLines = 1,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                )

                Row(
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.token_vault_field_value),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.weight(1f),
                    )
                    if (dialogState?.isCreate == false) {
                        TextButton(
                            onClick = {
                                val name = dialogState.oldName.orEmpty()
                                if (name.isNotEmpty()) onRequestReveal(name)
                            },
                            enabled = !isSaving,
                        ) {
                            Text(text = stringResource(R.string.token_vault_reveal_action))
                        }
                    }
                }
                LiquidTextField(
                    value = dialogState?.value.orEmpty(),
                    onValueChange = { value ->
                        onValueChange(dialogState?.name.orEmpty(), value, dialogState?.note.orEmpty())
                    },
                    placeholder = stringResource(
                        if (dialogState?.isCreate == true) {
                            R.string.token_vault_field_value_hint
                        } else {
                            R.string.token_vault_field_value_keep_hint
                        }
                    ),
                    enabled = !isSaving,
                    singleLine = false,
                    minLines = 1,
                    maxLines = 4,
                )

                Text(
                    text = stringResource(R.string.token_vault_field_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth(),
                )
                LiquidTextField(
                    value = dialogState?.note.orEmpty(),
                    onValueChange = { note ->
                        onValueChange(dialogState?.name.orEmpty(), dialogState?.value.orEmpty(), note)
                    },
                    placeholder = stringResource(R.string.token_vault_field_note_hint),
                    enabled = !isSaving,
                    singleLine = true,
                    minLines = 1,
                    maxLines = 1,
                )
            }
        },
        actions = {
            MaterialTintLiquidButton(
                text = stringResource(R.string.token_vault_save_action),
                enabled = !isSaving,
                onClick = onSaveClick,
                modifier = Modifier.weight(1f),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
            MaterialTintLiquidButton(
                text = stringResource(R.string.token_vault_cancel_action),
                enabled = !isSaving,
                onClick = onDismissRequest,
                modifier = Modifier.weight(1f),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurface,
            )
        },
    )
}

@Composable
private fun TokenVaultListMessage(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun TokenVaultInlineErrorText(
    error: com.niki914.zafiro.app.ui.model.TokenVaultInlineError,
) {
    val message = when (error) {
        is com.niki914.zafiro.app.ui.model.TokenVaultInlineError.LoadFailed -> stringResource(
            R.string.token_vault_error_load_failed,
            error.message ?: stringResource(error.fallbackResId),
        )

        is com.niki914.zafiro.app.ui.model.TokenVaultInlineError.SaveFailed -> stringResource(
            R.string.token_vault_error_save_failed,
            error.message ?: stringResource(error.fallbackResId),
        )

        is com.niki914.zafiro.app.ui.model.TokenVaultInlineError.DeleteFailed -> stringResource(
            R.string.token_vault_error_delete_failed,
            error.message ?: stringResource(error.fallbackResId),
        )
    }
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
    )
}

@Preview(name = "Token Vault Edit Dialog", showBackground = true, widthDp = 420, heightDp = 900)
@Composable
private fun TokenVaultEditDialogPreview() {
    MaterialTheme {
        ProvideLiquidScreenContentForPreview(topPadding = 0.dp) {
            TokenVaultEditDialog(
                state = com.niki914.zafiro.app.ui.model.TokenVaultEditDialogState(
                    oldName = null,
                    name = "github_token",
                    value = "",
                    note = "",
                    isCreate = true,
                ),
                revealedValue = null,
                isSaving = false,
                onValueChange = { _, _, _ -> },
                onRequestReveal = {},
                onDismissRequest = {},
                onSaveClick = {},
            )
        }
    }
}
