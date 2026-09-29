package com.niki914.zafiro.repo

import com.niki914.logging.Logger
import com.niki914.store.StoreDescriptorRegistry
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.parseObject
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.string
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Cloudflare 集成设置（AI Gateway 路由 + Cloud Brain 服务器）。
 *
 * 两个字段组：
 *  - AI Gateway：把现有 LLM 请求改写到 gateway.ai.cloudflare.com（缓存/重试/日志）。
 *    只需要 accountId + gatewayName；网关侧鉴权（cf-aig-authorization）v1 不启用，
 *    需要在 Cloudflare 控制台保持网关鉴权为关闭。
 *  - Cloud Brain：自部署 Worker + Durable Object 的地址与共享密钥。
 */
data class CloudflareSettings(
    /** AI Gateway 总开关：关闭时端点保持原样。 */
    val gatewayEnabled: Boolean = false,
    /** Cloudflare 账户 ID（32 位十六进制）。 */
    val accountId: String = "",
    /** AI Gateway 名称（Cloudflare 控制台创建的 Gateway 名）。 */
    val gatewayName: String = "",
    /** 未在已知映射里的 provider 使用此自定义 slug；空 = 不改写（直连）。 */
    val customProviderSlug: String = "",
    /** Cloud Brain 总开关：自动化触发走云端大脑。 */
    val brainEnabled: Boolean = false,
    /** Worker 地址，如 https://zafiro-cloud-brain.<account>.workers.dev */
    val workerUrl: String = "",
    /** Worker 共享密钥（部署时以 CLOUD_BRAIN_SECRET 注入）。 */
    val brainSecret: String = "",
    /** CF API Token（可选）：Gateway Authentication 开启时以 cf-aig-authorization 发送；
     *  也是 Cloudflare AI (Workers AI) provider 的 API Key。 */
    val cfApiToken: String = "",
) {
    /** AI Gateway 是否具备改写条件。 */
    fun gatewayReady(): Boolean =
        gatewayEnabled && accountId.isNotBlank() && gatewayName.isNotBlank()

    /** Cloud Brain 是否具备连接条件。 */
    fun brainReady(): Boolean =
        brainEnabled && workerUrl.isNotBlank() && brainSecret.isNotBlank()
}

/** Cloudflare 设置 JSON 编解码（存储格式 {}，字段缺省即默认值）。 */
internal object CloudflareSettingsCodec {

    private const val GATEWAY_ENABLED_KEY = "gatewayEnabled"
    private const val ACCOUNT_ID_KEY = "accountId"
    private const val GATEWAY_NAME_KEY = "gatewayName"
    private const val CUSTOM_SLUG_KEY = "customProviderSlug"
    private const val BRAIN_ENABLED_KEY = "brainEnabled"
    private const val WORKER_URL_KEY = "workerUrl"
    private const val BRAIN_SECRET_KEY = "brainSecret"
    private const val CF_API_TOKEN_KEY = "cfApiToken"

    fun parse(json: String): CloudflareSettings {
        val obj = parseObject(json)
        return CloudflareSettings(
            gatewayEnabled = obj.string(GATEWAY_ENABLED_KEY) == "true",
            accountId = obj.string(ACCOUNT_ID_KEY).trim(),
            gatewayName = obj.string(GATEWAY_NAME_KEY).trim(),
            customProviderSlug = obj.string(CUSTOM_SLUG_KEY).trim(),
            brainEnabled = obj.string(BRAIN_ENABLED_KEY) == "true",
            workerUrl = obj.string(WORKER_URL_KEY).trim().trimEnd('/'),
            brainSecret = obj.string(BRAIN_SECRET_KEY).trim(),
            cfApiToken = obj.string(CF_API_TOKEN_KEY).trim(),
        )
    }

    fun encode(settings: CloudflareSettings): String {
        return JsonObject(
            mapOf(
                GATEWAY_ENABLED_KEY to JsonPrimitive(settings.gatewayEnabled),
                ACCOUNT_ID_KEY to JsonPrimitive(settings.accountId),
                GATEWAY_NAME_KEY to JsonPrimitive(settings.gatewayName),
                CUSTOM_SLUG_KEY to JsonPrimitive(settings.customProviderSlug),
                BRAIN_ENABLED_KEY to JsonPrimitive(settings.brainEnabled),
                WORKER_URL_KEY to JsonPrimitive(settings.workerUrl),
                BRAIN_SECRET_KEY to JsonPrimitive(settings.brainSecret),
                CF_API_TOKEN_KEY to JsonPrimitive(settings.cfApiToken),
            )
        ).toString()
    }
}

/** Cloudflare 领域 API：XRepo.cloud */
class CloudflareApi internal constructor(
    private val repo: XRepo,
) {

    suspend fun settings(): CloudflareSettings {
        return CloudflareSettingsCodec.parse(repo.readJson(storeId()))
    }

    suspend fun save(settings: CloudflareSettings) {
        val normalized = settings.normalized()
        repo.writeJson(storeId(), CloudflareSettingsCodec.encode(normalized))
        Logger.i(LOG_TAG, "save gatewayEnabled=${normalized.gatewayEnabled} " +
                "brainEnabled=${normalized.brainEnabled}")
    }

    suspend fun gatewayEnabled(): Boolean = settings().gatewayReady()

    suspend fun brainEnabled(): Boolean = settings().brainReady()

    private fun CloudflareSettings.normalized(): CloudflareSettings = copy(
        accountId = accountId.trim(),
        gatewayName = gatewayName.trim(),
        customProviderSlug = customProviderSlug.trim(),
        workerUrl = workerUrl.trim().trimEnd('/'),
        brainSecret = brainSecret.trim(),
        cfApiToken = cfApiToken.trim(),
    )

    private fun storeId(): String = StoreDescriptorRegistry.CLOUD_SETTINGS_ID

    private companion object {
        private const val LOG_TAG = "niki914_nexus_CloudflareApi"
    }
}
