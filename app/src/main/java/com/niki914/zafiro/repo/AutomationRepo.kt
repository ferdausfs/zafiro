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
 *
 * 动作：
 *  - AGENT：唤醒 LLM Agent，携带事件上下文自主执行
 *  - ALERT：不消耗 token，直接发一条高优先级提醒通知
 */
enum class AutomationTriggerSource {
    NOTIFICATION,
    FILE_DOWNLOAD,
}

enum class AutomationTriggerAction {
    AGENT,
    ALERT,
}

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
        )
    }

    private fun storeId(): String = StoreDescriptorRegistry.AUTOMATION_TRIGGERS_ID

    private companion object {
        private const val LOG_TAG = "niki914_nexus_AutomationApi"
    }
}
