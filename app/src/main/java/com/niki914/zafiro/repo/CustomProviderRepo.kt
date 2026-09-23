package com.niki914.zafiro.repo

import com.niki914.logging.Logger
import com.niki914.store.StoreDescriptorRegistry
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.array
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.long
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.orEmptyObjects
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.parseObject
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.string
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.stringValues
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

/**
 * 动态自定义 Provider（任何 OpenAI 兼容 /v1/chat/completions 端点）。
 *
 * 与 ProviderSpec 的内置品牌不同：自定义 Provider 是持久化在 store 里的运行时
 * 条目，由「脚本/配置导入器」（[ProviderScriptImporter]）或手动表单创建。
 * API key 一律进 TokenVault（AES-256/GCM 加密），本记录只保留 vault 引用名
 * 与尾 4 位掩码用于展示。
 *
 * 使用方式：由它生成一份 SavedLlmConfig（protocol = openai-chat-completions，
 * key 以 vault 引用挂载），进入既有的模型配置体系，无需新协议客户端。
 */
data class CustomProvider(
    val id: String,
    val name: String,
    /** 形如 https://host/v1（不带 /chat/completions，运行时按协议补齐）。 */
    val baseUrl: String,
    /** TokenVault 条目名；明文 key 绝不进本 store。 */
    val apiKeyVaultRef: String,
    /** 展示用尾 4 位掩码，如 "sk-***abcd"。 */
    val apiKeyMask: String = "",
    /** 导入时发现/填写的模型 id 列表（首个作为默认）。 */
    val models: List<String> = emptyList(),
    /** LlmProtocol.wireId；自定义 Provider 默认 openai-chat-completions。 */
    val protocol: String = "openai-chat-completions",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    companion object {
        /** vault 条目命名规则：与 provider id 一一对应，删除时同步清理。 */
        fun vaultRefFor(providerId: String): String = "provider-$providerId"
    }
}

/** 自定义 Provider JSON 编解码（{"providers":[...]}）。 */
internal object CustomProvidersCodec {

    private const val PROVIDERS_KEY = "providers"
    private const val ID_KEY = "id"
    private const val NAME_KEY = "name"
    private const val BASE_URL_KEY = "baseUrl"
    private const val VAULT_REF_KEY = "apiKeyVaultRef"
    private const val MASK_KEY = "apiKeyMask"
    private const val MODELS_KEY = "models"
    private const val PROTOCOL_KEY = "protocol"
    private const val CREATED_AT_KEY = "createdAt"
    private const val UPDATED_AT_KEY = "updatedAt"

    fun parse(json: String): List<CustomProvider> {
        return parseObject(json)
            .array(PROVIDERS_KEY)
            .orEmptyObjects()
            .mapNotNull { obj ->
                val id = obj.string(ID_KEY).trim()
                val name = obj.string(NAME_KEY).trim()
                if (id.isBlank() || name.isBlank()) return@mapNotNull null
                CustomProvider(
                    id = id,
                    name = name,
                    baseUrl = obj.string(BASE_URL_KEY).trim(),
                    apiKeyVaultRef = obj.string(VAULT_REF_KEY).trim(),
                    apiKeyMask = obj.string(MASK_KEY),
                    models = obj.array(MODELS_KEY).stringValues(),
                    protocol = obj.string(PROTOCOL_KEY).trim().ifBlank {
                        "openai-chat-completions"
                    },
                    createdAt = obj.long(CREATED_AT_KEY, 0L),
                    updatedAt = obj.long(UPDATED_AT_KEY, 0L),
                )
            }
    }

    fun encode(providers: List<CustomProvider>): String {
        return JsonObject(
            mapOf(
                PROVIDERS_KEY to JsonArray(
                    providers.map { provider ->
                        JsonObject(
                            mapOf(
                                ID_KEY to JsonPrimitive(provider.id),
                                NAME_KEY to JsonPrimitive(provider.name),
                                BASE_URL_KEY to JsonPrimitive(provider.baseUrl),
                                VAULT_REF_KEY to JsonPrimitive(provider.apiKeyVaultRef),
                                MASK_KEY to JsonPrimitive(provider.apiKeyMask),
                                MODELS_KEY to SettingsJsonCodecUtils.stringArray(provider.models),
                                PROTOCOL_KEY to JsonPrimitive(provider.protocol),
                                CREATED_AT_KEY to JsonPrimitive(provider.createdAt),
                                UPDATED_AT_KEY to JsonPrimitive(provider.updatedAt),
                            )
                        )
                    }
                ),
            )
        ).toString()
    }
}

/**
 * 自定义 Provider 领域 API：XRepo.customProviders。
 * 保存时 key 写入 TokenVault（name = provider-<id>），store 只存引用与掩码。
 */
class CustomProviderApi internal constructor(
    private val repo: XRepo,
) {

    suspend fun list(): List<CustomProvider> {
        return CustomProvidersCodec.parse(repo.readJson(storeId()))
    }

    suspend fun get(id: String): CustomProvider? = list().firstOrNull { it.id == id }

    /**
     * 保存（新建或更新）。[apiKey] 非空时写入 vault 并刷新掩码；为空且是更新时
     * 保留原 vault 引用（不触碰凭证库）。返回 null = 成功；非 null = 用户可读原因。
     */
    suspend fun save(
        provider: CustomProvider,
        apiKey: String,
    ): String? {
        val name = provider.name.trim()
        val baseUrl = provider.baseUrl.trim()
        if (name.isBlank()) return "name is required"
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            return "base URL must start with http(s)://"
        }
        val now = System.currentTimeMillis()
        val id = provider.id.trim().ifBlank { "prov-" + UUID.randomUUID().toString().replace("-", "").take(10) }
        val existing = get(id)
        val vaultRef = provider.apiKeyVaultRef.trim().ifBlank { CustomProvider.vaultRefFor(id) }

        var mask = existing?.apiKeyMask.orEmpty()
        if (apiKey.isNotBlank()) {
            try {
                TokenVault.put(name = vaultRef, value = apiKey.trim(), note = "provider: $name")
            } catch (t: Throwable) {
                Logger.w(LOG_TAG, "vault put failed reason=${t.message}")
                return t.message ?: "failed to store API key"
            }
            mask = maskKey(apiKey.trim())
        } else if (existing == null) {
            return "API key is required"
        }

        val normalized = provider.copy(
            id = id,
            name = name,
            baseUrl = baseUrl.trimEnd('/'),
            apiKeyVaultRef = vaultRef,
            apiKeyMask = mask,
            models = provider.models.map(String::trim).filter(String::isNotEmpty).distinct(),
            protocol = provider.protocol.trim().ifBlank { "openai-chat-completions" },
            createdAt = existing?.createdAt?.takeIf { it > 0L } ?: now,
            updatedAt = now,
        )
        repo.updateJson(storeId()) { json ->
            val providers = CustomProvidersCodec.parse(json)
            val updated = if (providers.any { it.id == id }) {
                providers.map { if (it.id == id) normalized else it }
            } else {
                providers + normalized
            }
            CustomProvidersCodec.encode(updated)
        }
        Logger.i(LOG_TAG, "save provider id=$id name=$name baseUrl=$baseUrl")
        return null
    }

    /** 删除 provider；vault 条目一并清理。返回是否删除了记录。 */
    suspend fun delete(id: String): Boolean {
        val target = get(id) ?: return false
        repo.updateJson(storeId()) { json ->
            CustomProvidersCodec.encode(
                CustomProvidersCodec.parse(json).filterNot { it.id == id }
            )
        }
        if (target.apiKeyVaultRef.isNotBlank()) {
            try {
                TokenVault.delete(target.apiKeyVaultRef)
            } catch (t: Throwable) {
                Logger.w(LOG_TAG, "vault delete failed ref=${target.apiKeyVaultRef}")
            }
        }
        Logger.i(LOG_TAG, "delete provider id=$id")
        return true
    }

    /** "sk-abcd1234" → "sk-***1234"（尾 4 位）。 */
    fun maskKey(key: String): String {
        val trimmed = key.trim()
        val tail = if (trimmed.length > 4) trimmed.takeLast(4) else trimmed
        val prefix = trimmed.takeWhile { it.isLetter() && it.isLowerCase() || it == '-' }
        return "${prefix.ifBlank { "key" }}-***$tail"
    }

    private fun storeId(): String = StoreDescriptorRegistry.LLM_PROVIDERS_ID

    private companion object {
        private const val LOG_TAG = "niki914_nexus_CustomProviders"
    }
}
