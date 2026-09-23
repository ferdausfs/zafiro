package com.niki914.zafiro.repo

import com.niki914.logging.Logger
import com.niki914.store.StoreDescriptorRegistry
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.array
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.boolean
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.parseObject
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.string
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.stringValues
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 模型回退链（Feature: Intelligent Model Fallback）。
 *
 * {"enabled": bool, "order": ["cfg-a", "cfg-b", …]}
 *
 * order 中的 id 指向 SavedLlmConfig（llm.saved_configs）；运行时主模型失败
 * （429 限流 / 5xx 过载 / 上下文超限 / 配额不足）且自动重试耗尽后，按 order
 * 顺序切到下一个可用配置续跑同一回合——历史经会话树无缝携带，用户无需重启
 * 提问或手动换模型。
 */
data class FallbackDocument(
    val enabled: Boolean = false,
    /** 回退优先级顺序（index 0 最先顶上）；不含当前 active 配置（运行时过滤）。 */
    val order: List<String> = emptyList(),
)

internal object FallbackCodec {

    private const val ENABLED_KEY = "enabled"
    private const val ORDER_KEY = "order"

    fun parse(json: String): FallbackDocument {
        val root = parseObject(json)
        return FallbackDocument(
            enabled = root.boolean(ENABLED_KEY, false),
            order = root.array(ORDER_KEY).stringValues(),
        )
    }

    fun encode(document: FallbackDocument): String {
        return JsonObject(
            mapOf(
                ENABLED_KEY to JsonPrimitive(document.enabled),
                ORDER_KEY to SettingsJsonCodecUtils.stringArray(document.order),
            )
        ).toString()
    }
}

/** 回退链领域 API：XRepo.fallback */
class FallbackApi internal constructor(
    private val repo: XRepo,
) {

    suspend fun document(): FallbackDocument {
        return FallbackCodec.parse(repo.readJson(storeId()))
    }

    suspend fun save(document: FallbackDocument) {
        repo.updateJson(storeId()) { json ->
            val current = FallbackCodec.parse(json)
            if (current == document) return@updateJson json
            FallbackCodec.encode(document)
        }
        Logger.i(
            LOG_TAG,
            "save fallback enabled=${document.enabled} chain=${document.order.size}",
        )
    }

    suspend fun setEnabled(enabled: Boolean) {
        save(document().copy(enabled = enabled))
    }

    /**
     * 解析回退链为运行时配置（按 order 顺序；跳过缺失/无效条目与当前 active）。
     * key 经 vault 引用解析；全部失败返回空列表（调用方按无回退处理）。
     */
    suspend fun resolveConfigs(activeConfigId: String?): List<SavedLlmConfig> {
        val doc = document()
        if (!doc.enabled || doc.order.isEmpty()) return emptyList()
        val configs = repo.llmConfigs.list()
        return doc.order.mapNotNull { id ->
            configs.firstOrNull { it.id == id }
        }.filter { it.id != activeConfigId }
            .filter { it.endpoint.isNotBlank() || it.model.isNotBlank() }
    }

    private fun storeId(): String = StoreDescriptorRegistry.LLM_FALLBACK_ID

    private companion object {
        private const val LOG_TAG = "niki914_nexus_Fallback"
    }
}
