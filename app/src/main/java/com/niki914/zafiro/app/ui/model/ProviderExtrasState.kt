package com.niki914.zafiro.app.ui.model

import androidx.lifecycle.viewModelScope
import com.niki914.logging.Logger
import com.niki914.uikit.base.ComposeMVIViewModel
import com.niki914.zafiro.app.automation.BackgroundTaskHub
import com.niki914.zafiro.repo.BackgroundTaskRecord
import com.niki914.zafiro.repo.CustomProvider
import com.niki914.zafiro.repo.FallbackDocument
import com.niki914.zafiro.repo.ProviderScriptImporter
import com.niki914.zafiro.repo.SavedLlmConfig
import com.niki914.zafiro.repo.XRepo
import kotlinx.coroutines.launch

/**
 * Script-to-Config 导入（Feature: Dynamic Provider Framework）：
 * 粘贴脚本/JSON/cURL → 解析三要素 → 保存自定义 Provider（key 进凭证库）→
 * 自动创建一份 vault-backed SavedLlmConfig 并置 active。
 */
data class ProviderImportUiState(
    val visible: Boolean = false,
    val input: String = "",
    val parsed: Boolean = false,
    val nameInput: String = "",
    val baseUrlInput: String = "",
    val modelInput: String = "",
    val apiKeyInput: String = "",
    val apiKeyVisible: Boolean = false,
    val error: String? = null,
    val isSaving: Boolean = false,
    val customProviders: List<CustomProvider> = emptyList(),
)

sealed interface ProviderImportIntent {
    data object Show : ProviderImportIntent

    /** 手动添加自定义 Provider：跳过脚本粘贴，直接展开表单。 */
    data object ShowManual : ProviderImportIntent
    data object Dismiss : ProviderImportIntent
    data class UpdateInput(val value: String) : ProviderImportIntent
    data object Parse : ProviderImportIntent
    data class UpdateName(val value: String) : ProviderImportIntent
    data class UpdateBaseUrl(val value: String) : ProviderImportIntent
    data class UpdateModel(val value: String) : ProviderImportIntent
    data class UpdateApiKey(val value: String) : ProviderImportIntent
    data object ToggleApiKeyVisibility : ProviderImportIntent
    data object Save : ProviderImportIntent
    data class DeleteProvider(val id: String) : ProviderImportIntent
}

class ProviderImportViewModel : ComposeMVIViewModel<ProviderImportIntent, ProviderImportUiState, Nothing>() {

    override fun initUiState() = ProviderImportUiState()

    init {
        refreshProviders()
    }

    override suspend fun handleIntent(intent: ProviderImportIntent) {
        when (intent) {
            ProviderImportIntent.Show -> updateState { copy(visible = true, error = null) }
            ProviderImportIntent.ShowManual -> updateState {
                copy(visible = true, parsed = true, error = null)
            }
            ProviderImportIntent.Dismiss -> updateState {
                ProviderImportUiState(customProviders = customProviders)
            }

            is ProviderImportIntent.UpdateInput -> updateState { copy(input = intent.value) }
            ProviderImportIntent.Parse -> parseInput()
            is ProviderImportIntent.UpdateName -> updateState { copy(nameInput = intent.value) }
            is ProviderImportIntent.UpdateBaseUrl -> updateState { copy(baseUrlInput = intent.value) }
            is ProviderImportIntent.UpdateModel -> updateState { copy(modelInput = intent.value) }
            is ProviderImportIntent.UpdateApiKey -> updateState { copy(apiKeyInput = intent.value) }
            ProviderImportIntent.ToggleApiKeyVisibility -> updateState {
                copy(apiKeyVisible = !apiKeyVisible)
            }

            ProviderImportIntent.Save -> saveImported()
            is ProviderImportIntent.DeleteProvider -> deleteProvider(intent.id)
        }
    }

    private fun parseInput() {
        val imported = ProviderScriptImporter.parse(currentState.input)
        if (imported == null) {
            updateState { copy(parsed = false, error = "parse: nothing recognized") }
            return
        }
        updateState {
            copy(
                parsed = true,
                error = null,
                nameInput = nameInput.ifBlank { imported.suggestedName },
                baseUrlInput = imported.baseUrl,
                modelInput = imported.modelIds.firstOrNull().orEmpty(),
                apiKeyInput = imported.apiKey,
            )
        }
    }

    private fun refreshProviders() {
        viewModelScope.launch {
            try {
                val providers = XRepo.customProviders.list()
                updateState { copy(customProviders = providers) }
            } catch (t: Throwable) {
                Logger.w(LOG_TAG, "list providers failed reason=${t.message}")
            }
        }
    }

    private suspend fun saveImported() {
        val state = currentState
        if (state.isSaving) return
        if (state.nameInput.isBlank() || state.baseUrlInput.isBlank() ||
            state.apiKeyInput.isBlank() || state.modelInput.isBlank()
        ) {
            updateState { copy(error = "all fields are required") }
            return
        }
        updateState { copy(isSaving = true, error = null) }
        try {
            val provider = CustomProvider(
                id = "",
                name = state.nameInput.trim(),
                baseUrl = state.baseUrlInput.trim(),
                apiKeyVaultRef = "",
                models = listOf(state.modelInput.trim()),
            )
            val error = XRepo.customProviders.save(provider, apiKey = state.apiKeyInput.trim())
            if (error != null) {
                updateState { copy(isSaving = false, error = error) }
                return
            }
            val saved = XRepo.customProviders.list().firstOrNull {
                it.name == state.nameInput.trim()
            } ?: return

            // 生成 vault-backed SavedLlmConfig：endpoint 为完整 chat/completions 路径
            val endpoint = chatCompletionsEndpoint(saved.baseUrl)
            val config = SavedLlmConfig(
                id = "",
                name = saved.name,
                provider = "custom",
                endpoint = endpoint,
                apiKey = "",
                model = state.modelInput.trim(),
                protocol = saved.protocol,
                supportsImages = false,
                proxy = "",
                apiKeyVaultRef = saved.apiKeyVaultRef,
            )
            val upsertError = XRepo.llmConfigs.upsert(config)
            if (upsertError != null) {
                updateState { copy(isSaving = false, error = upsertError) }
                return
            }
            Logger.i(LOG_TAG, "imported provider name=${saved.name} config=${endpoint}")
            val providers = XRepo.customProviders.list()
            updateState { ProviderImportUiState(customProviders = providers) }
        } catch (t: Throwable) {
            Logger.w(LOG_TAG, "save imported failed reason=${t.message}")
            updateState { copy(isSaving = false, error = t.message ?: "save failed") }
        }
    }

    private fun deleteProvider(id: String) {
        viewModelScope.launch {
            try {
                XRepo.customProviders.delete(id)
                refreshProviders()
            } catch (t: Throwable) {
                Logger.w(LOG_TAG, "delete provider failed reason=${t.message}")
            }
        }
    }

    /** https://host/v1 → https://host/v1/chat/completions（已是完整路径则原样）。 */
    private fun chatCompletionsEndpoint(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        return when {
            trimmed.endsWith("/chat/completions") -> trimmed
            else -> "$trimmed/chat/completions"
        }
    }

    private companion object {
        private const val LOG_TAG = "niki914_nexus_ProviderImport"
    }
}

/**
 * 模型回退链配置（Feature: Intelligent Model Fallback）：
 * 启用开关 + 从已保存配置中按顺序挑选回退模型。每次改动即时持久化。
 */
data class FallbackUiState(
    val visible: Boolean = false,
    val enabled: Boolean = false,
    /** 有序回退链（index 0 最先顶上）。 */
    val chain: List<FallbackEntry> = emptyList(),
    /** 尚未加入链中的其他配置。 */
    val available: List<FallbackEntry> = emptyList(),
    val isLoaded: Boolean = false,
)

sealed interface FallbackIntent {
    data object Show : FallbackIntent
    data object Dismiss : FallbackIntent
    data class SetEnabled(val enabled: Boolean) : FallbackIntent
    data class AddModel(val configId: String) : FallbackIntent
    data class RemoveModel(val configId: String) : FallbackIntent
    data class Move(val configId: String, val direction: Int) : FallbackIntent
}

class FallbackViewModel : ComposeMVIViewModel<FallbackIntent, FallbackUiState, Nothing>() {

    override fun initUiState() = FallbackUiState()

    override suspend fun handleIntent(intent: FallbackIntent) {
        when (intent) {
            FallbackIntent.Show -> {
                load()
                updateState { copy(visible = true, isLoaded = true) }
            }

            FallbackIntent.Dismiss -> updateState { copy(visible = false) }
            is FallbackIntent.SetEnabled -> setEnabled(intent.enabled)
            is FallbackIntent.AddModel -> addToChain(intent.configId)
            is FallbackIntent.RemoveModel -> removeFromChain(intent.configId)
            is FallbackIntent.Move -> move(intent.configId, intent.direction)
        }
    }

    private suspend fun load() {
        try {
            val doc = XRepo.fallback.document()
            val summaries = XRepo.llmConfigs.list().map { config ->
                FallbackEntry(
                    id = config.id,
                    name = config.name,
                    modelId = config.model,
                )
            }
            val byId = summaries.associateBy { it.id }
            updateState {
                copy(
                    enabled = doc.enabled,
                    chain = doc.order.mapNotNull(byId::get),
                    available = summaries.filterNot { it.id in doc.order },
                )
            }
        } catch (t: Throwable) {
            Logger.w(LOG_TAG, "fallback load failed reason=${t.message}")
        }
    }

    private suspend fun persist() {
        val state = currentState
        try {
            XRepo.fallback.save(
                FallbackDocument(
                    enabled = state.enabled,
                    order = state.chain.map { it.id },
                )
            )
        } catch (t: Throwable) {
            Logger.w(LOG_TAG, "fallback persist failed reason=${t.message}")
        }
    }

    private suspend fun setEnabled(enabled: Boolean) {
        updateState { copy(enabled = enabled) }
        persist()
    }

    private suspend fun addToChain(configId: String) {
        val candidate = currentState.available.firstOrNull { it.id == configId } ?: return
        updateState {
            copy(
                chain = chain + candidate,
                available = available.filterNot { it.id == configId },
            )
        }
        persist()
    }

    private suspend fun removeFromChain(configId: String) {
        val removed = currentState.chain.firstOrNull { it.id == configId } ?: return
        updateState {
            copy(
                chain = chain.filterNot { it.id == configId },
                available = available + removed,
            )
        }
        persist()
    }

    private suspend fun move(configId: String, direction: Int) {
        val chain = currentState.chain.toMutableList()
        val index = chain.indexOfFirst { it.id == configId }
        val target = index + direction
        if (index == -1 || target < 0 || target >= chain.size) return
        chain[index] = chain[target].also { chain[target] = chain[index] }
        updateState { copy(chain = chain) }
        persist()
    }

    private companion object {
        private const val LOG_TAG = "niki914_nexus_FallbackVM"
    }
}

/** 回退链条目展示模型（SavedConfigSummary 的 repo 侧轻量版，避免 UI 层反向依赖）。 */
data class FallbackEntry(
    val id: String,
    val name: String,
    val modelId: String,
)

/**
 * 后台任务台账（Feature: Asynchronous Background Execution）：列表页观察。
 */
data class BackgroundTasksUiState(
    val tasks: List<BackgroundTaskRecord> = emptyList(),
    val runningTaskId: String? = null,
    val isLoading: Boolean = false,
)

sealed interface BackgroundTasksIntent {
    data object Refresh : BackgroundTasksIntent
    data class Delete(val id: String) : BackgroundTasksIntent
    data object ClearFinished : BackgroundTasksIntent
}

class BackgroundTasksViewModel :
    ComposeMVIViewModel<BackgroundTasksIntent, BackgroundTasksUiState, Nothing>() {

    override fun initUiState() = BackgroundTasksUiState()

    init {
        refresh()
    }

    override suspend fun handleIntent(intent: BackgroundTasksIntent) {
        when (intent) {
            BackgroundTasksIntent.Refresh -> refresh()
            is BackgroundTasksIntent.Delete -> delete(intent.id)
            BackgroundTasksIntent.ClearFinished -> clearFinished()
        }
    }

    private fun refresh() {
        viewModelScope.launch {
            updateState { copy(isLoading = true) }
            try {
                val tasks = XRepo.backgroundTasks.list()
                updateState {
                    copy(
                        tasks = tasks,
                        runningTaskId = BackgroundTaskHub.runningTask.value?.id,
                        isLoading = false,
                    )
                }
            } catch (t: Throwable) {
                Logger.w(LOG_TAG, "tasks load failed reason=${t.message}")
                updateState { copy(isLoading = false) }
            }
        }
    }

    private fun delete(id: String) {
        viewModelScope.launch {
            try {
                XRepo.backgroundTasks.delete(id)
                refresh()
            } catch (t: Throwable) {
                Logger.w(LOG_TAG, "task delete failed reason=${t.message}")
            }
        }
    }

    private fun clearFinished() {
        viewModelScope.launch {
            try {
                XRepo.backgroundTasks.clearFinished()
                refresh()
            } catch (t: Throwable) {
                Logger.w(LOG_TAG, "clear finished failed reason=${t.message}")
            }
        }
    }

    private companion object {
        private const val LOG_TAG = "niki914_nexus_TasksVM"
    }
}
