package com.niki914.zafiro.repo

import java.net.URI

/**
 * Cloudflare AI Gateway 端点改写（纯函数，便于单测）。
 *
 * 把形如 `https://api.openai.com/v1/responses` 的官方端点改写为
 * `https://gateway.ai.cloudflare.com/v1/{accountId}/{gatewayName}/{slug}/v1/responses`，
 * 请求头与 API Key 原样透传（BYOK），即可获得网关的缓存 / 重试 / 日志能力。
 *
 * 已知 slug 只收录 Cloudflare 文档长期稳定提供的 provider；
 * 其余 provider 通过设置里的 customProviderSlug 手动指定。
 */
object CloudflareGateway {

    /** AI Gateway 基础主机。 */
    const val GATEWAY_HOST: String = "gateway.ai.cloudflare.com"

    /** Gateway Authentication 开启时必需的鉴权请求头（值 = Bearer <CF API Token>）。 */
    const val AIG_AUTH_HEADER: String = "cf-aig-authorization"

    /** Workers AI 端点中的 Account ID 占位符（运行时由设置里的 Account ID 替换）。 */
    const val ACCOUNT_PLACEHOLDER: String = "{account_id}"

    /** app provider id → AI Gateway slug。未收录的 provider 走 customProviderSlug。 */
    private val providerSlugs: Map<String, String> = mapOf(
        "openai" to "openai",
        "anthropic" to "anthropic",
        "deepseek" to "deepseek",
        "openrouter" to "openrouter",
        // Google 品牌走 generativelanguage.googleapis.com 的 OpenAI 兼容端点
        //（ProviderSpec.GoogleSpec），对应 AI Gateway 的 google-ai-studio。
        "google" to "google-ai-studio",
    )

    /**
     * 解析 provider 对应的 gateway slug；不可改写时返回 null。
     * customSlug 非空时优先（用户显式指定的 slug 拥有最高优先级）。
     */
    fun providerSlug(providerId: String, customSlug: String): String? {
        val custom = customSlug.trim()
        if (custom.isNotEmpty()) return custom
        val known = providerSlugs[providerId.trim().lowercase()]
        return known
    }

    /**
     * 把官方端点改写为 AI Gateway 端点。
     * 以下情况返回 null（保持直连）：
     *  - 任何输入为空
     *  - 原端点不是绝对 https URL（本地 Ollama / 明文地址不经过网关）
     *  - 原端点本身就是网关地址（防二次包裹）
     * 改写保留原 URL 的 path 与 query。
     */
    fun rewriteEndpoint(
        originalEndpoint: String,
        accountId: String,
        gatewayName: String,
        slug: String,
    ): String? {
        val account = accountId.trim()
        val gateway = gatewayName.trim()
        val slugTrimmed = slug.trim()
        val original = originalEndpoint.trim()
        if (account.isEmpty() || gateway.isEmpty() || slugTrimmed.isEmpty() || original.isEmpty()) {
            return null
        }
        val uri = runCatching { URI(original) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()
        if (scheme != "https" || host.isNullOrEmpty()) return null
        if (host == GATEWAY_HOST) return null

        val path = uri.rawPath.orEmpty()
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        val suffix = if (path.isEmpty()) "/" else path
        return "https://$GATEWAY_HOST/v1/$account/$gateway/$slugTrimmed$suffix$query"
    }

    /** 组合入口：settings + provider → 改写后端点；不可改写返回 null（调用方保持原样）。 */
    fun gatewayEndpointFor(
        providerId: String,
        customSlug: String,
        originalEndpoint: String,
        accountId: String,
        gatewayName: String,
    ): String? {
        val slug = providerSlug(providerId, customSlug) ?: return null
        return rewriteEndpoint(originalEndpoint, accountId, gatewayName, slug)
    }

    /**
     * 把端点中的 {account_id} 占位符替换为真实 Account ID。
     * 无占位符或 accountId 为空时原样返回（Workers AI 直连端点使用；
     * 同一逻辑也适用于模型目录 /models 地址）。
     */
    fun substituteAccountId(endpoint: String, accountId: String): String {
        val original = endpoint.trim()
        if (!original.contains(ACCOUNT_PLACEHOLDER)) return endpoint
        val account = accountId.trim()
        if (account.isEmpty()) return endpoint
        return original.replace(ACCOUNT_PLACEHOLDER, account)
    }
}
