package com.niki914.zafiro.repo

import com.niki914.logging.Logger
import com.niki914.store.StoreDescriptorRegistry
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.array
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.orEmptyObjects
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.parseObject
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.string
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.stringArray
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.stringValues
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

/**
 * 主动自动化（Proactive Automation）触发器模型与持久化。
 *
 * 触发源：
 *  - NOTIFICATION：系统通知（NotificationListenerService 捕获）
 *  - FILE_DOWNLOAD：Download 目录新文件（FileObserver 监听）
 *  - BATTERY：电池事件（电量阈值/充电/充满，v1.7.0）
 *  - TIME：定时触发（HH:mm + 星期几，v1.7.0）
 *  - LOCATION：地点进/出（经纬度 + 半径，v1.7.0）
 *
 * 动作：
 *  - AGENT：唤醒 LLM Agent，携带事件上下文自主执行
 *  - ALERT：不消耗 token，直接发一条高优先级提醒通知
 */
enum class AutomationTriggerSource {
    NOTIFICATION,
    FILE_DOWNLOAD,
    BATTERY,
    TIME,
    LOCATION,
}

enum class AutomationTriggerAction {
    AGENT,
    ALERT,
}

/** BATTERY 源：电池事件类型。 */
enum class AutomationBatteryEvent {
    LOW,       // 电量 ≤ batteryLevel 阈值且未充电
    CHARGING,  // 开始充电
    FULL,      // 充满（≥ 95% 视为满）
    OKAY,      // 电量从低位回升到阈值之上（解除低电）
    // v1.8.0 Samsung/One UI：省电模式翻转（One UI 专属系统事件）
    POWER_SAVE_ON,   // One UI 省电模式开启
    POWER_SAVE_OFF,  // One UI 省电模式关闭
}

/** LOCATION 源：进入/离开模式。 */
enum class AutomationLocationMode { ENTER, EXIT }

data class AutomationTrigger(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val source: AutomationTriggerSource = AutomationTriggerSource.NOTIFICATION,
    /** NOTIFICATION 源：应用包名；空 = 任意应用。 */
    val appPackage: String = "",
    /** NOTIFICATION 源：发送者/标题包含匹配（不区分大小写）；空 = 不限制。 */
    val senderContains: String = "",
    /** 关键词列表（任一命中即触发，不区分大小写）；空 = 不限制。 */
    val keywords: List<String> = emptyList(),
    val action: AutomationTriggerAction = AutomationTriggerAction.AGENT,
    /** AGENT 动作：注入给 Agent 的任务指令模板。 */
    val prompt: String = "",
    /** 同一触发器两次触发之间的最小间隔（秒），防止风暴。 */
    val cooldownSeconds: Int = DEFAULT_COOLDOWN_SECONDS,
    // ---- v1.7.0 新增字段 ----
    /** BATTERY 源：电量阈值百分比（LOW/OKAY 判定线）。 */
    val batteryLevel: Int = 20,
    /** BATTERY 源：电池事件类型。 */
    val batteryEvent: AutomationBatteryEvent = AutomationBatteryEvent.LOW,
    /** TIME 源：触发时刻 "HH:mm"（24 小时制，设备本地时区）。 */
    val timeOfDay: String = "",
    /** TIME 源：生效星期（1=周一 … 7=周日）；空集 = 每天。 */
    val daysOfWeek: Set<Int> = emptySet(),
    /** LOCATION 源：目标纬度。 */
    val latitude: Double = 0.0,
    /** LOCATION 源：目标经度。 */
    val longitude: Double = 0.0,
    /** LOCATION 源：半径（米）。 */
    val radiusMeters: Int = 200,
    /** LOCATION 源：进入还是离开时触发。 */
    val locationMode: AutomationLocationMode = AutomationLocationMode.ENTER,
) {
    companion object {
        const val DEFAULT_COOLDOWN_SECONDS: Int = 30
        const val MAX_COOLDOWN_SECONDS: Int = 3600
    }
}

/** 触发器 JSON 编解码（存储格式 {"triggers":[...]}）。 */
internal object AutomationTriggersCodec {

    private const val TRIGGERS_KEY = "triggers"
    private const val ID_KEY = "id"
    private const val NAME_KEY = "name"
    private const val ENABLED_KEY = "enabled"
    private const val SOURCE_KEY = "source"
    private const val APP_PACKAGE_KEY = "appPackage"
    private const val SENDER_KEY = "senderContains"
    private const val KEYWORDS_KEY = "keywords"
    private const val ACTION_KEY = "action"
    private const val PROMPT_KEY = "prompt"
    private const val COOLDOWN_KEY = "cooldownSeconds"
    // v1.7.0
    private const val BATTERY_LEVEL_KEY = "batteryLevel"
    private const val BATTERY_EVENT_KEY = "batteryEvent"
    private const val TIME_OF_DAY_KEY = "timeOfDay"
    private const val DAYS_OF_WEEK_KEY = "daysOfWeek"
    private const val LATITUDE_KEY = "latitude"
    private const val LONGITUDE_KEY = "longitude"
    private const val RADIUS_KEY = "radiusMeters"
    private const val LOCATION_MODE_KEY = "locationMode"

    fun parse(json: String): List<AutomationTrigger> {
        return parseObject(json)
            .array(TRIGGERS_KEY)
            .orEmptyObjects()
            .mapNotNull { obj ->
                val id = obj.string(ID_KEY).trim()
                val name = obj.string(NAME_KEY).trim()
                if (id.isBlank() || name.isBlank()) return@mapNotNull null
                AutomationTrigger(
                    id = id,
                    name = name,
                    enabled = obj.string(ENABLED_KEY) != "false",
                    source = AutomationTriggerSource.entries.firstOrNull {
                        it.name == obj.string(SOURCE_KEY)
                    } ?: AutomationTriggerSource.NOTIFICATION,
                    appPackage = obj.string(APP_PACKAGE_KEY).trim(),
                    senderContains = obj.string(SENDER_KEY).trim(),
                    keywords = obj.array(KEYWORDS_KEY).stringValues()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() },
                    action = AutomationTriggerAction.entries.firstOrNull {
                        it.name == obj.string(ACTION_KEY)
                    } ?: AutomationTriggerAction.AGENT,
                    prompt = obj.string(PROMPT_KEY).trim(),
                    cooldownSeconds = obj.string(COOLDOWN_KEY).toIntOrNull()
                        ?.coerceIn(0, AutomationTrigger.MAX_COOLDOWN_SECONDS)
                        ?: AutomationTrigger.DEFAULT_COOLDOWN_SECONDS,
                    batteryLevel = obj.string(BATTERY_LEVEL_KEY).toIntOrNull()
                        ?.coerceIn(1, 100) ?: 20,
                    batteryEvent = AutomationBatteryEvent.entries.firstOrNull {
                        it.name == obj.string(BATTERY_EVENT_KEY)
                    } ?: AutomationBatteryEvent.LOW,
                    timeOfDay = obj.string(TIME_OF_DAY_KEY).trim(),
                    daysOfWeek = obj.array(DAYS_OF_WEEK_KEY).stringValues()
                        .mapNotNull(String::toIntOrNull)
                        .filter { it in 1..7 }
                        .toSet(),
                    latitude = obj.string(LATITUDE_KEY).toDoubleOrNull() ?: 0.0,
                    longitude = obj.string(LONGITUDE_KEY).toDoubleOrNull() ?: 0.0,
                    radiusMeters = obj.string(RADIUS_KEY).toIntOrNull()
                        ?.coerceIn(30, 10_000) ?: 200,
                    locationMode = AutomationLocationMode.entries.firstOrNull {
                        it.name == obj.string(LOCATION_MODE_KEY)
                    } ?: AutomationLocationMode.ENTER,
                )
            }
    }

    fun encode(triggers: List<AutomationTrigger>): String {
        return JsonObject(
            mapOf(
                TRIGGERS_KEY to kotlinx.serialization.json.JsonArray(
                    triggers.map { trigger ->
                        JsonObject(
                            mapOf(
                                ID_KEY to JsonPrimitive(trigger.id),
                                NAME_KEY to JsonPrimitive(trigger.name),
                                ENABLED_KEY to JsonPrimitive(trigger.enabled),
                                SOURCE_KEY to JsonPrimitive(trigger.source.name),
                                APP_PACKAGE_KEY to JsonPrimitive(trigger.appPackage),
                                SENDER_KEY to JsonPrimitive(trigger.senderContains),
                                KEYWORDS_KEY to SettingsJsonCodecUtils.stringArray(trigger.keywords),
                                ACTION_KEY to JsonPrimitive(trigger.action.name),
                                PROMPT_KEY to JsonPrimitive(trigger.prompt),
                                COOLDOWN_KEY to JsonPrimitive(trigger.cooldownSeconds),
                                BATTERY_LEVEL_KEY to JsonPrimitive(trigger.batteryLevel),
                                BATTERY_EVENT_KEY to JsonPrimitive(trigger.batteryEvent.name),
                                TIME_OF_DAY_KEY to JsonPrimitive(trigger.timeOfDay),
                                DAYS_OF_WEEK_KEY to
                                        SettingsJsonCodecUtils.stringArray(
                                            trigger.daysOfWeek.map(Int::toString).sorted()
                                        ),
                                LATITUDE_KEY to JsonPrimitive(trigger.latitude),
                                LONGITUDE_KEY to JsonPrimitive(trigger.longitude),
                                RADIUS_KEY to JsonPrimitive(trigger.radiusMeters),
                                LOCATION_MODE_KEY to JsonPrimitive(trigger.locationMode.name),
                            )
                        )
                    }
                ),
            )
        ).toString()
    }
}

/** 触发器领域 API：XRepo.automation */
class AutomationApi internal constructor(
    private val repo: XRepo,
) {

    suspend fun list(): List<AutomationTrigger> {
        val json = repo.readJson(storeId())
        return AutomationTriggersCodec.parse(json)
    }

    suspend fun get(id: String): AutomationTrigger? {
        return list().firstOrNull { it.id == id }
    }

    suspend fun save(trigger: AutomationTrigger) {
        val normalized = trigger.normalized()
        repo.updateJson(storeId()) { json ->
            val triggers = AutomationTriggersCodec.parse(json)
            val updated = if (triggers.any { it.id == normalized.id }) {
                triggers.map { if (it.id == normalized.id) normalized else it }
            } else {
                triggers + normalized
            }
            AutomationTriggersCodec.encode(updated)
        }
        Logger.i(LOG_TAG, "save trigger id=${normalized.id} name=${normalized.name}")
    }

    suspend fun delete(id: String) {
        repo.updateJson(storeId()) { json ->
            AutomationTriggersCodec.encode(
                AutomationTriggersCodec.parse(json).filterNot { it.id == id }
            )
        }
        Logger.i(LOG_TAG, "delete trigger id=$id")
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        repo.updateJson(storeId()) { json ->
            AutomationTriggersCodec.encode(
                AutomationTriggersCodec.parse(json).map { trigger ->
                    if (trigger.id == id) trigger.copy(enabled = enabled) else trigger
                }
            )
        }
    }

    private fun AutomationTrigger.normalized(): AutomationTrigger {
        return copy(
            id = id.ifBlank { "trigger-${UUID.randomUUID()}" },
            name = name.trim(),
            appPackage = appPackage.trim(),
            senderContains = senderContains.trim(),
            keywords = keywords.map(String::trim).filter(String::isNotEmpty).distinct(),
            prompt = prompt.trim(),
            cooldownSeconds = cooldownSeconds
                .coerceIn(0, AutomationTrigger.MAX_COOLDOWN_SECONDS),
            batteryLevel = batteryLevel.coerceIn(1, 100),
            timeOfDay = timeOfDay.trim().take(5),
            daysOfWeek = daysOfWeek.filter { it in 1..7 }.toSet(),
            radiusMeters = radiusMeters.coerceIn(30, 10_000),
        )
    }

    private fun storeId(): String = StoreDescriptorRegistry.AUTOMATION_TRIGGERS_ID

    private companion object {
        private const val LOG_TAG = "niki914_nexus_AutomationApi"
    }
}
