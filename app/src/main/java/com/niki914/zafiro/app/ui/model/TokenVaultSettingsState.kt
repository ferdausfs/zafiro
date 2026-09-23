package com.niki914.zafiro.app.ui.model

import androidx.annotation.StringRes
import androidx.lifecycle.viewModelScope
import com.niki914.logging.Logger
import com.niki914.uikit.base.ComposeMVIViewModel
import com.niki914.zafiro.app.R
import com.niki914.zafiro.repo.TokenVault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 列表条目：不含明文，value 仅在 reveal 后由调用方按需装载。 */
data class TokenVaultItem(
    val name: String,
    val note: String,
    val updatedAt: Long = 0L,
)

data class TokenVaultEditDialogState(
    /** null = 新建；编辑时 oldName 定位原条目。 */
    val oldName: String?,
    val name: String,
    val value: String,
    val note: String,
    /** 编辑态：value 留空 = 保留原值。 */
    val isCreate: Boolean,
)

data class TokenVaultRevealState(
    val name: String,
    val value: String,
)

data class TokenVaultDeleteConfirmationState(
    val name: String,
)

sealed interface TokenVaultInlineError {
    data class LoadFailed(val message: String?, @StringRes val fallbackResId: Int) :
        TokenVaultInlineError

    data class SaveFailed(val message: String?, @StringRes val fallbackResId: Int) :
        TokenVaultInlineError

    data class DeleteFailed(val message: String?, @StringRes val fallbackResId: Int) :
        TokenVaultInlineError
}

data class TokenVaultUiState(
    val items: List<TokenVaultItem> = emptyList(),
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val editingDialog: TokenVaultEditDialogState? = null,
    val revealed: TokenVaultRevealState? = null,
    val deleteConfirmation: TokenVaultDeleteConfirmationState? = null,
    val inlineError: TokenVaultInlineError? = null,
)

sealed interface TokenVaultSettingsIntent {
    data object Load : TokenVaultSettingsIntent
    data object StartCreate : TokenVaultSettingsIntent
    data class StartEdit(val name: String) : TokenVaultSettingsIntent
    data class EditValueChanged(val name: String, val value: String, val note: String) :
        TokenVaultSettingsIntent

    data object DismissEditDialog : TokenVaultSettingsIntent
    data object SaveEditDialog : TokenVaultSettingsIntent
    data class RequestReveal(val name: String) : TokenVaultSettingsIntent
    data object DismissReveal : TokenVaultSettingsIntent
    data class RequestDelete(val name: String) : TokenVaultSettingsIntent
    data object DismissDeleteConfirmation : TokenVaultSettingsIntent
    data object ConfirmDeleteItem : TokenVaultSettingsIntent
}

class TokenVaultSettingsViewModel :
    ComposeMVIViewModel<TokenVaultSettingsIntent, TokenVaultUiState, Nothing>() {

    override fun initUiState(): TokenVaultUiState = TokenVaultUiState()

    override suspend fun handleIntent(intent: TokenVaultSettingsIntent) {
        when (intent) {
            TokenVaultSettingsIntent.Load -> load()
            TokenVaultSettingsIntent.StartCreate -> startCreate()
            is TokenVaultSettingsIntent.StartEdit -> startEdit(intent.name)
            is TokenVaultSettingsIntent.EditValueChanged -> updateEditValue(intent)
            TokenVaultSettingsIntent.DismissEditDialog -> updateState {
                copy(editingDialog = null, inlineError = null)
            }

            TokenVaultSettingsIntent.SaveEditDialog -> saveEditDialog()
            is TokenVaultSettingsIntent.RequestReveal -> reveal(intent.name)
            TokenVaultSettingsIntent.DismissReveal -> updateState { copy(revealed = null) }
            is TokenVaultSettingsIntent.RequestDelete -> requestDelete(intent.name)
            TokenVaultSettingsIntent.DismissDeleteConfirmation -> updateState {
                copy(deleteConfirmation = null, inlineError = null)
            }

            TokenVaultSettingsIntent.ConfirmDeleteItem -> confirmDelete()
        }
    }

    private suspend fun load() {
        updateState { copy(isLoading = true) }
        try {
            val summaries = TokenVault.list()
            val currentItems = currentState.items
            updateState {
                copy(
                    items = summaries.map { summary ->
                        TokenVaultItem(
                            name = summary.name,
                            note = summary.note,
                            updatedAt = summary.updatedAt,
                        )
                    },
                    isLoading = false,
                    // 已展开的值随之失效，避免展示已删除/已轮换的值
                    revealed = revealed?.takeIf { state ->
                        summaries.any { it.name == state.name }
                    },
                    inlineError = null,
                )
            }
            Logger.d(LOG_TAG, "load items=${summaries.size} previous=${currentItems.size}")
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            Logger.w(LOG_TAG, "load failed reason=${throwable.message}")
            updateState {
                copy(
                    isLoading = false,
                    inlineError = TokenVaultInlineError.LoadFailed(
                        message = throwable.message,
                        fallbackResId = R.string.token_vault_error_load_failed,
                    ),
                )
            }
        }
    }

    private fun startCreate() {
        updateState {
            copy(
                editingDialog = TokenVaultEditDialogState(
                    oldName = null,
                    name = "",
                    value = "",
                    note = "",
                    isCreate = true,
                ),
                deleteConfirmation = null,
                inlineError = null,
            )
        }
    }

    private suspend fun startEdit(name: String) {
        val item = currentState.items.firstOrNull { it.name == name } ?: return
        updateState {
            copy(
                editingDialog = TokenVaultEditDialogState(
                    oldName = item.name,
                    name = item.name,
                    value = "",
                    note = item.note,
                    isCreate = false,
                ),
                inlineError = null,
            )
        }
    }

    private fun updateEditValue(intent: TokenVaultSettingsIntent.EditValueChanged) {
        val dialog = currentState.editingDialog ?: return
        updateState {
            copy(
                editingDialog = dialog.copy(
                    name = intent.name,
                    value = intent.value,
                    note = intent.note,
                ),
                inlineError = null,
            )
        }
    }

    private suspend fun saveEditDialog() {
        val dialog = currentState.editingDialog ?: return
        val name = dialog.name.trim()
        val value = dialog.value.trim()
        val note = dialog.note.trim()
        if (name.isEmpty()) {
            updateState {
                copy(
                    inlineError = TokenVaultInlineError.SaveFailed(
                        message = null,
                        fallbackResId = R.string.token_vault_error_name_required,
                    ),
                )
            }
            return
        }
        // 编辑时允许留空 = 保留原值
        val valueOrPreserved = if (dialog.isCreate && value.isEmpty()) {
            updateState {
                copy(
                    inlineError = TokenVaultInlineError.SaveFailed(
                        message = null,
                        fallbackResId = R.string.token_vault_error_value_required,
                    ),
                )
            }
            return
        } else {
            value
        }

        updateState { copy(isSaving = true, inlineError = null) }
        try {
            if (dialog.isCreate) {
                TokenVault.put(name = name, value = valueOrPreserved, note = note)
            } else {
                val resolvedValue = value.ifEmpty {
                    TokenVault.value(dialog.oldName.orEmpty()).orEmpty()
                }
                if (dialog.oldName != null && !dialog.oldName.equals(name, ignoreCase = true)) {
                    TokenVault.put(name = name, value = resolvedValue, note = note)
                    TokenVault.delete(dialog.oldName)
                } else {
                    TokenVault.put(name = name, value = resolvedValue, note = note)
                }
            }
            Logger.i(LOG_TAG, "saveEditDialog succeeded name=$name isCreate=${dialog.isCreate}")
            updateState {
                copy(
                    isSaving = false,
                    editingDialog = null,
                    deleteConfirmation = null,
                    inlineError = null,
                )
            }
            load()
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            Logger.w(LOG_TAG, "saveEditDialog failed reason=${throwable.message}")
            updateState {
                copy(
                    isSaving = false,
                    inlineError = TokenVaultInlineError.SaveFailed(
                        message = throwable.message,
                        fallbackResId = R.string.token_vault_error_save_failed,
                    ),
                )
            }
        }
    }

    private suspend fun reveal(name: String) {
        try {
            val value = TokenVault.value(name)
            if (value == null) {
                updateState {
                    copy(
                        inlineError = TokenVaultInlineError.LoadFailed(
                            message = null,
                            fallbackResId = R.string.token_vault_error_reveal_failed,
                        ),
                    )
                }
                return
            }
            updateState { copy(revealed = TokenVaultRevealState(name = name, value = value)) }
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            updateState {
                copy(
                    inlineError = TokenVaultInlineError.LoadFailed(
                        message = throwable.message,
                        fallbackResId = R.string.token_vault_error_reveal_failed,
                    ),
                )
            }
        }
    }

    private fun requestDelete(name: String) {
        if (currentState.editingDialog != null) return
        updateState {
            copy(
                deleteConfirmation = TokenVaultDeleteConfirmationState(name = name),
                inlineError = null,
            )
        }
    }

    private suspend fun confirmDelete() {
        val confirmation = currentState.deleteConfirmation ?: return
        val name = confirmation.name
        updateState { copy(isSaving = true, deleteConfirmation = null, inlineError = null) }
        try {
            TokenVault.delete(name)
            Logger.i(LOG_TAG, "confirmDelete succeeded name=$name")
            updateState { copy(isSaving = false) }
            load()
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            Logger.w(LOG_TAG, "confirmDelete failed name=$name reason=${throwable.message}")
            updateState {
                copy(
                    isSaving = false,
                    inlineError = TokenVaultInlineError.DeleteFailed(
                        message = throwable.message,
                        fallbackResId = R.string.token_vault_error_delete_failed,
                    ),
                )
            }
        }
    }

    private companion object {
        private const val LOG_TAG = "niki914_nexus_TokenVaultSettingsViewModel"
    }
}
