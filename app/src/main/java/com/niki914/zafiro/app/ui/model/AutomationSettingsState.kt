package com.niki914.zafiro.app.ui.model

import androidx.annotation.StringRes
import androidx.lifecycle.viewModelScope
import com.niki914.logging.Logger
import com.niki914.uikit.base.ComposeMVIViewModel
import com.niki914.zafiro.app.R
import com.niki914.zafiro.repo.AutomationBatteryEvent
import com.niki914.zafiro.repo.AutomationLocationMode
import com.niki914.zafiro.repo.AutomationTrigger
import com.niki914.zafiro.repo.AutomationTriggerAction
import com.niki914.zafiro.repo.AutomationTriggerSource
import com.niki914.zafiro.app.automation.AutomationHub
import com.niki914.zafiro.repo.XRepo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import java.util.UUID

data class AutomationTriggerItem(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val source: AutomationTriggerSource,
    val appPackage: String,
    val senderContains: String,
    val keywords: List<String>,
    val action: AutomationTriggerAction,
    val prompt: String,
    val cooldownSeconds: Int,
    // v1.7.0
    val batteryLevel: Int = 20,
    val batteryEvent: AutomationBatteryEvent = AutomationBatteryEvent.LOW,
    val timeOfDay: String = "",
    val daysOfWeek: Set<Int> = emptySet(),
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val radiusMeters: Int = 200,
    val locationMode: AutomationLocationMode = AutomationLocationMode.ENTER,
)

/** 触发器编辑对话框状态（创建或编辑）。 */
data class AutomationTriggerEditState(
    val isCreate: Boolean,
    val id: String? = null,
    val name: String = "",
    val source: AutomationTriggerSource = AutomationTriggerSource.NOTIFICATION,
    val appPackage: String = "",
    val senderContains: String = "",
    val keywordsInput: String = "",
    val action: AutomationTriggerAction = AutomationTriggerAction.AGENT,
    val prompt: String = "",
    val cooldownInput: String = AutomationTrigger.DEFAULT_COOLDOWN_SECONDS.toString(),
    // v1.7.0
    val batteryLevelInput: String = "20",
    val batteryEvent: AutomationBatteryEvent = AutomationBatteryEvent.LOW,
    val timeOfDayInput: String = "",
    val daysOfWeekInput: String = "",
    val latitudeInput: String = "",
    val longitudeInput: String = "",
    val radiusInput: String = "200",
    val locationMode: AutomationLocationMode = AutomationLocationMode.ENTER,
    @param:StringRes val nameErrorResId: Int? = null,
    @param:StringRes val promptErrorResId: Int? = null,
    @param:StringRes val sourceFieldErrorResId: Int? = null,
)

data class AutomationSettingsUiState(
    val items: List<AutomationTriggerItem> = emptyList(),
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val editState: AutomationTriggerEditState? = null,
    val deleteTarget: AutomationTriggerItem? = null,
    @param:StringRes val inlineErrorResId: Int? = null,
)

sealed interface AutomationSettingsIntent {
    data object Load : AutomationSettingsIntent
    data object StartCreate : AutomationSettingsIntent
    data class StartEdit(val index: Int) : AutomationSettingsIntent
    data class ItemEnabledChanged(val index: Int, val value: Boolean) : AutomationSettingsIntent
    data object DismissEditor : AutomationSettingsIntent

    data class NameChanged(val value: String) : AutomationSettingsIntent
    data class SourceChanged(val value: AutomationTriggerSource) : AutomationSettingsIntent
    data class AppPackageChanged(val value: String) : AutomationSettingsIntent
    data class SenderChanged(val value: String) : AutomationSettingsIntent
    data class KeywordsChanged(val value: String) : AutomationSettingsIntent
    data class ActionChanged(val value: AutomationTriggerAction) : AutomationSettingsIntent
    data class PromptChanged(val value: String) : AutomationSettingsIntent
    data class CooldownChanged(val value: String) : AutomationSettingsIntent

    // v1.7.0 新字段
    data class BatteryLevelChanged(val value: String) : AutomationSettingsIntent
    data class BatteryEventChanged(val value: AutomationBatteryEvent) : AutomationSettingsIntent
    data class TimeOfDayChanged(val value: String) : AutomationSettingsIntent
    data class DaysOfWeekChanged(val value: String) : AutomationSettingsIntent
    data class LatitudeChanged(val value: String) : AutomationSettingsIntent
    data class LongitudeChanged(val value: String) : AutomationSettingsIntent
    data class RadiusChanged(val value: String) : AutomationSettingsIntent
    data class LocationModeChanged(val value: AutomationLocationMode) : AutomationSettingsIntent

    data object Save : AutomationSettingsIntent
    data class RequestDelete(val index: Int) : AutomationSettingsIntent
    data object DismissDeleteConfirmation : AutomationSettingsIntent
    data object ConfirmDelete : AutomationSettingsIntent
}

class AutomationSettingsViewModel :
    ComposeMVIViewModel<AutomationSettingsIntent, AutomationSettingsUiState, Nothing>() {

    init {
        viewModelScope.launch {
            settingsChanges.collect {
                load()
            }
        }
    }

    override fun initUiState(): AutomationSettingsUiState = AutomationSettingsUiState()

    override suspend fun handleIntent(intent: AutomationSettingsIntent) {
        when (intent) {
            AutomationSettingsIntent.Load -> load()
            AutomationSettingsIntent.StartCreate -> updateState {
                copy(editState = AutomationTriggerEditState(isCreate = true))
            }

            is AutomationSettingsIntent.StartEdit -> startEdit(intent.index)
            is AutomationSettingsIntent.ItemEnabledChanged -> toggleItem(
                index = intent.index,
                enabled = intent.value,
            )

            AutomationSettingsIntent.DismissEditor -> updateState {
                copy(editState = null, inlineErrorResId = null)
            }

            is AutomationSettingsIntent.NameChanged -> updateState {
                copy(
                    editState = editState?.copy(name = intent.value, nameErrorResId = null),
                    inlineErrorResId = null,
                )
            }

            is AutomationSettingsIntent.SourceChanged -> updateState {
                copy(editState = editState?.copy(source = intent.value))
            }

            is AutomationSettingsIntent.AppPackageChanged -> updateState {
                copy(editState = editState?.copy(appPackage = intent.value))
            }

            is AutomationSettingsIntent.SenderChanged -> updateState {
                copy(editState = editState?.copy(senderContains = intent.value))
            }

            is AutomationSettingsIntent.KeywordsChanged -> updateState {
                copy(editState = editState?.copy(keywordsInput = intent.value))
            }

            is AutomationSettingsIntent.ActionChanged -> updateState {
                copy(editState = editState?.copy(action = intent.value))
            }

            is AutomationSettingsIntent.PromptChanged -> updateState {
                copy(
                    editState = editState?.copy(prompt = intent.value, promptErrorResId = null),
                    inlineErrorResId = null,
                )
            }

            is AutomationSettingsIntent.CooldownChanged -> updateState {
                copy(editState = editState?.copy(cooldownInput = intent.value))
            }

            // v1.7.0 新字段
            is AutomationSettingsIntent.BatteryLevelChanged -> updateState {
                copy(
                    editState = editState?.copy(
                        batteryLevelInput = intent.value.filter(Char::isDigit).take(3),
                        sourceFieldErrorResId = null,
                    )
                )
            }

            is AutomationSettingsIntent.BatteryEventChanged -> updateState {
                copy(editState = editState?.copy(batteryEvent = intent.value))
            }

            is AutomationSettingsIntent.TimeOfDayChanged -> updateState {
                copy(
                    editState = editState?.copy(
                        timeOfDayInput = intent.value.filter { it.isDigit() || it == ':' }.take(5),
                        sourceFieldErrorResId = null,
                    )
                )
            }

            is AutomationSettingsIntent.DaysOfWeekChanged -> updateState {
                copy(
                    editState = editState?.copy(
                        daysOfWeekInput = intent.value,
                    )
                )
            }

            is AutomationSettingsIntent.LatitudeChanged -> updateState {
                copy(
                    editState = editState?.copy(
                        latitudeInput = intent.value.take(12),
                        sourceFieldErrorResId = null,
                    )
                )
            }

            is AutomationSettingsIntent.LongitudeChanged -> updateState {
                copy(
                    editState = editState?.copy(
                        longitudeInput = intent.value.take(12),
                        sourceFieldErrorResId = null,
                    )
                )
            }

            is AutomationSettingsIntent.RadiusChanged -> updateState {
                copy(
                    editState = editState?.copy(
                        radiusInput = intent.value.filter(Char::isDigit).take(5),
                    )
                )
            }

            is AutomationSettingsIntent.LocationModeChanged -> updateState {
                copy(editState = editState?.copy(locationMode = intent.value))
            }

            AutomationSettingsIntent.Save -> save()
            is AutomationSettingsIntent.RequestDelete -> {
                val item = currentState.items.getOrNull(intent.index) ?: return
                updateState { copy(deleteTarget = item) }
            }

            AutomationSettingsIntent.DismissDeleteConfirmation -> updateState {
                copy(deleteTarget = null)
            }

            AutomationSettingsIntent.ConfirmDelete -> confirmDelete()
        }
    }

    private suspend fun load() {
        updateState { copy(isLoading = true) }
        try {
            val items = XRepo.automation.list().map { it.toItem() }
            updateState { copy(items = items, isLoading = false, inlineErrorResId = null) }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Logger.w(LOG_TAG, "load failed reason=${t.message}")
            updateState {
                copy(
                    isLoading = false,
                    inlineErrorResId = R.string.automation_error_load_failed,
                )
            }
        }
    }

    private suspend fun startEdit(index: Int) {
        val item = currentState.items.getOrNull(index) ?: return
        try {
            val full = XRepo.automation.get(item.id) ?: item.toModel()
            updateState {
                copy(
                    editState = AutomationTriggerEditState(
                        isCreate = false,
                        id = full.id,
                        name = full.name,
                        source = full.source,
                        appPackage = full.appPackage,
                        senderContains = full.senderContains,
                        keywordsInput = full.keywords.joinToString(", "),
                        action = full.action,
                        prompt = full.prompt,
                        cooldownInput = full.cooldownSeconds.toString(),
                        batteryLevelInput = full.batteryLevel.toString(),
                        batteryEvent = full.batteryEvent,
                        timeOfDayInput = full.timeOfDay,
                        daysOfWeekInput = full.daysOfWeek.sorted().joinToString(", "),
                        latitudeInput = if (full.latitude == 0.0) "" else full.latitude.toString(),
                        longitudeInput = if (full.longitude == 0.0) "" else full.longitude.toString(),
                        radiusInput = full.radiusMeters.toString(),
                        locationMode = full.locationMode,
                    ),
                    inlineErrorResId = null,
                )
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Logger.w(LOG_TAG, "startEdit failed reason=${t.message}")
        }
    }

    private suspend fun toggleItem(index: Int, enabled: Boolean) {
        val item = currentState.items.getOrNull(index) ?: return
        updateState { copy(isSaving = true) }
        try {
            XRepo.automation.setEnabled(item.id, enabled)
            load()
            AutomationHub.reloadTriggers()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Logger.w(LOG_TAG, "toggleItem failed reason=${t.message}")
            updateState {
                copy(
                    isSaving = false,
                    inlineErrorResId = R.string.automation_error_save_failed,
                )
            }
        }
    }

    private suspend fun save() {
        val edit = currentState.editState ?: return
        val name = edit.name.trim()
        val prompt = edit.prompt.trim()
        val keywords = edit.keywordsInput.split(',', '\n')
            .map(String::trim)
            .filter(String::isNotEmpty)
        val cooldown = edit.cooldownInput.trim().toIntOrNull()
            ?.coerceIn(0, AutomationTrigger.MAX_COOLDOWN_SECONDS)
            ?: AutomationTrigger.DEFAULT_COOLDOWN_SECONDS

        val nameError = if (name.isBlank()) R.string.automation_error_name_required else null
        val promptError = if (
            edit.action == AutomationTriggerAction.AGENT && prompt.isBlank()
        ) {
            R.string.automation_error_prompt_required
        } else {
            null
        }

        // v1.7.0：新来源的必填字段校验
        var sourceFieldError: Int? = null
        val batteryLevel = edit.batteryLevelInput.toIntOrNull()?.coerceIn(1, 100) ?: 20
        val timeOfDay = normalizeTimeOfDay(edit.timeOfDayInput)
        val days = parseDaysOfWeek(edit.daysOfWeekInput)
        val latitude = edit.latitudeInput.trim().toDoubleOrNull()
        val longitude = edit.longitudeInput.trim().toDoubleOrNull()
        val radius = edit.radiusInput.toIntOrNull()?.coerceIn(30, 10_000) ?: 200
        when (edit.source) {
            AutomationTriggerSource.TIME ->
                if (timeOfDay == null) {
                    sourceFieldError = R.string.automation_error_time_required
                }

            AutomationTriggerSource.LOCATION -> {
                when {
                    latitude == null || longitude == null ||
                            kotlin.math.abs(latitude) > 90.0 ||
                            kotlin.math.abs(longitude) > 180.0 ->
                        sourceFieldError = R.string.automation_error_coords_required
                }
            }

            else -> Unit
        }

        if (nameError != null || promptError != null || sourceFieldError != null) {
            updateState {
                copy(
                    editState = edit.copy(
                        name = name,
                        nameErrorResId = nameError,
                        promptErrorResId = promptError,
                        sourceFieldErrorResId = sourceFieldError,
                    ),
                )
            }
            return
        }

        updateState { copy(isSaving = true) }
        try {
            val trigger = AutomationTrigger(
                id = edit.id ?: "trigger-${UUID.randomUUID()}",
                name = name,
                enabled = true,
                source = edit.source,
                appPackage = edit.appPackage.trim(),
                senderContains = edit.senderContains.trim(),
                keywords = keywords,
                action = edit.action,
                prompt = prompt,
                cooldownSeconds = cooldown,
                batteryLevel = batteryLevel,
                batteryEvent = edit.batteryEvent,
                timeOfDay = timeOfDay ?: "",
                daysOfWeek = days,
                latitude = latitude ?: 0.0,
                longitude = longitude ?: 0.0,
                radiusMeters = radius,
                locationMode = edit.locationMode,
            )
            XRepo.automation.save(trigger)
            Logger.i(LOG_TAG, "save ok id=${trigger.id}")
            updateState { copy(editState = null, isSaving = false) }
            load()
            AutomationHub.reloadTriggers()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Logger.w(LOG_TAG, "save failed reason=${t.message}")
            updateState {
                copy(
                    isSaving = false,
                    inlineErrorResId = R.string.automation_error_save_failed,
                )
            }
        }
    }

    /** "9:5" → "09:05"；非法返回 null。 */
    private fun normalizeTimeOfDay(raw: String): String? {
        val parts = raw.trim().split(':')
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return String.format(java.util.Locale.US, "%02d:%02d", h, m)
    }

    /** "1, 3,5" → [1,3,5]；非法项忽略。 */
    private fun parseDaysOfWeek(raw: String): Set<Int> {
        return raw.split(',', ' ', ';')
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 1..7 }
            .toSet()
    }

    private suspend fun confirmDelete() {
        val target = currentState.deleteTarget ?: return
        updateState { copy(deleteTarget = null, isSaving = true) }
        try {
            XRepo.automation.delete(target.id)
            load()
            AutomationHub.reloadTriggers()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Logger.w(LOG_TAG, "confirmDelete failed reason=${t.message}")
            updateState {
                copy(
                    isSaving = false,
                    inlineErrorResId = R.string.automation_error_delete_failed,
                )
            }
        }
    }

    private companion object {
        private const val LOG_TAG = "niki914_nexus_AutomationSettingsVM"
        val settingsChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    }
}


private fun AutomationTrigger.toItem(): AutomationTriggerItem {
    return AutomationTriggerItem(
        id = id,
        name = name,
        enabled = enabled,
        source = source,
        appPackage = appPackage,
        senderContains = senderContains,
        keywords = keywords,
        action = action,
        prompt = prompt,
        cooldownSeconds = cooldownSeconds,
        batteryLevel = batteryLevel,
        batteryEvent = batteryEvent,
        timeOfDay = timeOfDay,
        daysOfWeek = daysOfWeek,
        latitude = latitude,
        longitude = longitude,
        radiusMeters = radiusMeters,
        locationMode = locationMode,
    )
}

private fun AutomationTriggerItem.toModel(): AutomationTrigger {
    return AutomationTrigger(
        id = id,
        name = name,
        enabled = enabled,
        source = source,
        appPackage = appPackage,
        senderContains = senderContains,
        keywords = keywords,
        action = action,
        prompt = prompt,
        cooldownSeconds = cooldownSeconds,
        batteryLevel = batteryLevel,
        batteryEvent = batteryEvent,
        timeOfDay = timeOfDay,
        daysOfWeek = daysOfWeek,
        latitude = latitude,
        longitude = longitude,
        radiusMeters = radiusMeters,
        locationMode = locationMode,
    )
}
