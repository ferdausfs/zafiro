package com.niki914.zafiro.app.ui.content

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.niki914.logging.Logger
import com.niki914.uikit.infra.LiquidDialog
import com.niki914.uikit.infra.component.LiquidTextField
import com.niki914.uikit.infra.component.MaterialTintLiquidButton
import com.niki914.uikit.infra.component.settings.SettingsPageSpec
import com.niki914.uikit.infra.component.settings.SettingsRowAction
import com.niki914.uikit.infra.component.settings.SettingsRowSpec
import com.niki914.uikit.infra.component.settings.SettingsSectionLayout
import com.niki914.uikit.infra.component.settings.SettingsSectionSpec
import com.niki914.uikit.infra.component.settings.SettingsSpecPageContent
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.cloud.CloudBrainClient
import com.niki914.zafiro.app.cloud.CloudBrainPing
import com.niki914.zafiro.app.cloud.CloudOutboxDispatcher
import com.niki914.zafiro.repo.CloudflareSettings
import com.niki914.zafiro.repo.XRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Cloudflare 设置页：AI Gateway 路由 + Cloud Brain 服务器 + 诊断。
 * 容器与 General 同款（SettingsSpecPageContent + GroupedCard）。
 */
private const val GATEWAY_ENABLE_ROW_ID = "cloud.gateway.enable"
private const val GATEWAY_ACCOUNT_ROW_ID = "cloud.gateway.account"
private const val GATEWAY_NAME_ROW_ID = "cloud.gateway.name"
private const val GATEWAY_SLUG_ROW_ID = "cloud.gateway.slug"
private const val BRAIN_ENABLE_ROW_ID = "cloud.brain.enable"
private const val BRAIN_URL_ROW_ID = "cloud.brain.url"
private const val BRAIN_SECRET_ROW_ID = "cloud.brain.secret"
private const val BRAIN_TEST_ROW_ID = "cloud.brain.test"
private const val OUTBOX_ROW_ID = "cloud.outbox"

@Composable
fun CloudflareSettingsContent() {
    val scope = rememberCoroutineScope()

    // CloudflareSettings 非 Bundle 类型：remember（旋转后 LaunchedEffect 会重读磁盘）
    var settings by remember { mutableStateOf(CloudflareSettings()) }
    var pingOk by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var pingMessage by rememberSaveable { mutableStateOf("") }
    var pingRunning by rememberSaveable { mutableStateOf(false) }
    var pendingCount by rememberSaveable { mutableStateOf(0) }

    // 弹窗编辑：null = 关闭；非空 = 正在编辑该字段
    var editingField by rememberSaveable { mutableStateOf<String?>(null) }
    var editingInitial by rememberSaveable { mutableStateOf("") }

    suspend fun reload() {
        settings = XRepo.cloud.settings()
        pendingCount = XRepo.cloudOutbox.count()
    }

    LaunchedEffect(Unit) {
        runCatching { reload() }.onFailure {
            Logger.w("niki914_nexus_CloudSettings", "load failed ${it.message}")
        }
    }

    LaunchedEffect(Unit) {
        CloudOutboxDispatcher.pendingCount.collect { pendingCount = it }
    }

    fun persist(updated: CloudflareSettings) {
        settings = updated
        scope.launch {
            runCatching { XRepo.cloud.save(updated) }
                .onFailure { Logger.w("niki914_nexus_CloudSettings", "save failed ${it.message}") }
        }
    }

    fun editField(field: String, current: String) {
        editingField = field
        editingInitial = current
    }

    val secretDisplay = settings.brainSecret.takeIf { it.isNotBlank() }
        ?.let { "••••" + it.takeLast(2) }
        ?: ""

    val spec = SettingsPageSpec(
        description = null,
        sections = listOf(
            SettingsSectionSpec(
                layout = SettingsSectionLayout.GroupedCard,
                rows = listOf(
                    SettingsRowSpec.Toggle(
                        id = GATEWAY_ENABLE_ROW_ID,
                        title = stringResource(R.string.cloud_gateway_enable),
                        checked = settings.gatewayEnabled,
                    ),
                    SettingsRowSpec.Navigation(
                        id = GATEWAY_ACCOUNT_ROW_ID,
                        title = stringResource(R.string.cloud_gateway_account_id),
                        currentState = settings.accountId,
                    ),
                    SettingsRowSpec.Navigation(
                        id = GATEWAY_NAME_ROW_ID,
                        title = stringResource(R.string.cloud_gateway_name),
                        currentState = settings.gatewayName,
                    ),
                    SettingsRowSpec.Navigation(
                        id = GATEWAY_SLUG_ROW_ID,
                        title = stringResource(R.string.cloud_gateway_custom_slug),
                        currentState = settings.customProviderSlug,
                    ),
                ),
            ),
            SettingsSectionSpec(
                layout = SettingsSectionLayout.GroupedCard,
                rows = listOf(
                    SettingsRowSpec.Toggle(
                        id = BRAIN_ENABLE_ROW_ID,
                        title = stringResource(R.string.cloud_brain_enable),
                        checked = settings.brainEnabled,
                    ),
                    SettingsRowSpec.Navigation(
                        id = BRAIN_URL_ROW_ID,
                        title = stringResource(R.string.cloud_brain_worker_url),
                        currentState = settings.workerUrl,
                    ),
                    SettingsRowSpec.Navigation(
                        id = BRAIN_SECRET_ROW_ID,
                        title = stringResource(R.string.cloud_brain_secret),
                        currentState = secretDisplay,
                    ),
                    SettingsRowSpec.Navigation(
                        id = BRAIN_TEST_ROW_ID,
                        title = stringResource(R.string.cloud_test_connection),
                        currentState = when {
                            pingRunning -> stringResource(R.string.cloud_test_running)
                            pingOk != null -> stringResource(
                                if (pingOk == true) R.string.cloud_test_ok else R.string.cloud_test_fail,
                                pingMessage.take(80),
                            )
                            else -> ""
                        },
                    ),
                ),
            ),
            SettingsSectionSpec(
                layout = SettingsSectionLayout.GroupedCard,
                rows = listOf(
                    SettingsRowSpec.Navigation(
                        id = OUTBOX_ROW_ID,
                        title = stringResource(R.string.cloud_outbox_pending),
                        currentState = pendingCount.toString(),
                    ),
                ),
            ),
        ),
    )

    SettingsSpecPageContent(
        spec = spec,
        onAction = { action ->
            when (action) {
                is SettingsRowAction.ToggleChanged ->
                    when (action.id) {
                        GATEWAY_ENABLE_ROW_ID ->
                            persist(settings.copy(gatewayEnabled = action.checked))

                        BRAIN_ENABLE_ROW_ID ->
                            persist(settings.copy(brainEnabled = action.checked))

                        else -> Unit
                    }

                is SettingsRowAction.Navigate ->
                    when (action.id) {
                        GATEWAY_ACCOUNT_ROW_ID -> editField("accountId", settings.accountId)
                        GATEWAY_NAME_ROW_ID -> editField("gatewayName", settings.gatewayName)
                        GATEWAY_SLUG_ROW_ID -> editField("customProviderSlug", settings.customProviderSlug)
                        BRAIN_URL_ROW_ID -> editField("workerUrl", settings.workerUrl)
                        BRAIN_SECRET_ROW_ID -> editField("brainSecret", settings.brainSecret)
                        BRAIN_TEST_ROW_ID -> {
                            if (!pingRunning) {
                                pingRunning = true
                                pingOk = null
                                pingMessage = ""
                                scope.launch {
                                    val target = settings
                                    val ping = withContext(Dispatchers.IO) {
                                        if (!target.brainReady()) {
                                            CloudBrainPing(
                                                ok = false,
                                                message = "not configured",
                                            )
                                        } else {
                                            CloudBrainClient(
                                                target.workerUrl,
                                                target.brainSecret,
                                            ).ping()
                                        }
                                    }
                                    pingRunning = false
                                    pingOk = ping.ok
                                    pingMessage = ping.message
                                }
                            }
                        }

                        OUTBOX_ROW_ID -> {
                            scope.launch {
                                CloudOutboxDispatcher.flush()
                                pendingCount = XRepo.cloudOutbox.count()
                            }
                        }

                        else -> Unit
                    }

                else -> Unit
            }
        },
    )

    CloudTextDialog(
        visible = editingField != null,
        title = editingField?.let { fieldTitle(it) }.orEmpty(),
        initial = editingInitial,
        placeholder = editingField?.let { fieldHint(it) }.orEmpty(),
        onDismiss = { editingField = null },
        onConfirm = { value ->
            val updated = when (editingField) {
                "accountId" -> settings.copy(accountId = value.trim())
                "gatewayName" -> settings.copy(gatewayName = value.trim())
                "customProviderSlug" -> settings.copy(customProviderSlug = value.trim())
                "workerUrl" -> settings.copy(workerUrl = value.trim().trimEnd('/'))
                "brainSecret" -> settings.copy(brainSecret = value.trim())
                else -> settings
            }
            editingField = null
            persist(updated)
        },
    )
}

@Composable
private fun fieldTitle(field: String): String = when (field) {
    "accountId" -> stringResource(R.string.cloud_gateway_account_id)
    "gatewayName" -> stringResource(R.string.cloud_gateway_name)
    "customProviderSlug" -> stringResource(R.string.cloud_gateway_custom_slug)
    "workerUrl" -> stringResource(R.string.cloud_brain_worker_url)
    "brainSecret" -> stringResource(R.string.cloud_brain_secret)
    else -> field
}

@Composable
private fun fieldHint(field: String): String = when (field) {
    "accountId" -> stringResource(R.string.cloud_hint_account_id)
    "gatewayName" -> stringResource(R.string.cloud_hint_gateway_name)
    "customProviderSlug" -> stringResource(R.string.cloud_hint_custom_slug)
    "workerUrl" -> stringResource(R.string.cloud_hint_worker_url)
    "brainSecret" -> stringResource(R.string.cloud_hint_secret)
    else -> ""
}

/** 单行文本编辑弹窗（LiquidDialog + LiquidTextField）。 */
@Composable
private fun CloudTextDialog(
    visible: Boolean,
    title: String,
    initial: String,
    placeholder: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember(visible) { mutableStateOf(initial) }
    LiquidDialog(
        visible = visible,
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
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
                    value = value,
                    onValueChange = { value = it },
                    placeholder = placeholder,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        actions = {
            MaterialTintLiquidButton(
                text = stringResource(R.string.cloud_dialog_save),
                onClick = { onConfirm(value) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
    )
}
