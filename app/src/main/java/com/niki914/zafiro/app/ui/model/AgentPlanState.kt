package com.niki914.zafiro.app.ui.model

import androidx.lifecycle.viewModelScope
import com.niki914.logging.Logger
import com.niki914.uikit.base.ComposeMVIViewModel
import com.niki914.zafiro.repo.XRepo
import com.niki914.zafiro.settings.model.RuntimeTodoItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 计划条目 UI 模型：来自 agent.todo store（todo_write 工具维护）。 */
data class AgentPlanItem(
    val content: String,
    val status: String,
) {
    val isCompleted: Boolean get() = status == RuntimeTodoItem.TODO_STATUS_COMPLETED
    val isInProgress: Boolean get() = status == RuntimeTodoItem.TODO_STATUS_IN_PROGRESS
    val isPending: Boolean get() = status == RuntimeTodoItem.TODO_STATUS_PENDING
}

data class AgentPlanUiState(
    val items: List<AgentPlanItem> = emptyList(),
    val isLoading: Boolean = true,
    /** 最近一次变更时间（agent.todo 的 updated_at），0 = 从未写入。 */
    val updatedAt: Long = 0L,
    /** 最近一次成功拉取的墙钟时间，用于"实时跟随"提示。 */
    val syncedAt: Long = 0L,
)

sealed interface AgentPlanIntent {
    /** 立即刷新一次。 */
    data object Refresh : AgentPlanIntent

    /** 进入页面后启动轮询（页面离开时协程随 LaunchedEffect 取消）。 */
    data object StartPolling : AgentPlanIntent
}

/**
 * Agent 计划页（设置 → Agent Plan）：
 * 实时展示 todo_write 工具维护的任务计划，让用户像看 Claude Code / OpenCode 的
 * TODO 面板一样看到 Agent 当前的执行进度。轮询读取 agent.todo store。
 */
class AgentPlanViewModel : ComposeMVIViewModel<AgentPlanIntent, AgentPlanUiState, Nothing>() {

    override fun initUiState(): AgentPlanUiState = AgentPlanUiState()

    override suspend fun handleIntent(intent: AgentPlanIntent) {
        when (intent) {
            AgentPlanIntent.Refresh -> refresh()
            AgentPlanIntent.StartPolling -> startPolling()
        }
    }

    private suspend fun refresh() {
        try {
            val todos = XRepo.todo.list()
            val updatedAt = XRepo.todo.updatedAt()
            updateState {
                copy(
                    items = todos.map { AgentPlanItem(content = it.content, status = it.status) },
                    isLoading = false,
                    updatedAt = updatedAt,
                    syncedAt = System.currentTimeMillis(),
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Logger.w(LOG_TAG, "refresh failed reason=${error.message}")
            // 保持已有内容，仅结束 loading：计划页失败不应打断用户查看。
            updateState { copy(isLoading = false) }
        }
    }

    private fun startPolling() {
        viewModelScope.launch {
            while (isActive) {
                refresh()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private companion object {
        private const val LOG_TAG = "niki914_nexus_AgentPlanViewModel"

        /** 与 Agent 执行节奏相比足够实时，又不至于频繁拉 IPC。 */
        private const val POLL_INTERVAL_MS = 1500L
    }
}
