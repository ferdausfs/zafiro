package com.niki914.zafiro.app.ui.content

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Timelapse
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.niki914.uikit.infra.component.SettingsGroupCard
import com.niki914.uikit.infra.component.SettingsListPageContent
import com.niki914.uikit.infra.nav.pageViewModel
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.model.AgentPlanIntent
import com.niki914.zafiro.app.ui.model.AgentPlanItem

/**
 * 设置 → Agent Plan：实时查看 Agent 通过 todo_write 维护的任务计划。
 * 页面可见期间每 1.5s 轮询 agent.todo store，Agent 执行进度即时呈现。
 */
@Composable
fun TODOPageContent() {
    val viewModel = pageViewModel<com.niki914.zafiro.app.ui.model.AgentPlanViewModel>()
    val uiState by viewModel.uiStateFlow.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.sendIntent(AgentPlanIntent.StartPolling)
    }

    SettingsListPageContent(
        description = stringResource(R.string.ui_todo_page_description),
    ) {
        if (uiState.isLoading) {
            SettingsGroupCard {
                PlanMessage(text = stringResource(R.string.ui_todo_page_loading))
            }
            return@SettingsListPageContent
        }

        if (uiState.items.isEmpty()) {
            SettingsGroupCard {
                PlanMessage(text = stringResource(R.string.ui_todo_page_empty))
            }
        } else {
            val completed = uiState.items.count { it.isCompleted }
            SettingsGroupCard {
                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.ui_todo_page_progress, completed, uiState.items.size),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    uiState.items.forEach { item ->
                        PlanItemRow(item = item)
                    }
                }
            }
        }
    }
}

@Composable
private fun PlanItemRow(item: AgentPlanItem) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        val (icon, tint) = when {
            item.isCompleted -> Icons.Default.CheckCircle to MaterialTheme.colorScheme.primary
            item.isInProgress -> Icons.Default.Timelapse to MaterialTheme.colorScheme.tertiary
            else -> Icons.Default.RadioButtonUnchecked to MaterialTheme.colorScheme.onSurfaceVariant
        }
        Icon(
            imageVector = icon,
            contentDescription = item.status,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = item.content,
            style = MaterialTheme.typography.bodyMedium,
            color = if (item.isCompleted) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            textDecoration = if (item.isCompleted) TextDecoration.LineThrough else null,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun PlanMessage(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}
