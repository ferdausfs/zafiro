package com.niki914.zafiro.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** CloudflareGateway 纯函数测试：slug 映射 + 端点改写边界。 */
class CloudflareGatewayTest {

    // -------------------------------------------------------- providerSlug

    @Test
    fun slug_knownProviders() {
        assertEquals("openai", CloudflareGateway.providerSlug("openai", ""))
        assertEquals("anthropic", CloudflareGateway.providerSlug("anthropic", ""))
        assertEquals("deepseek", CloudflareGateway.providerSlug("deepseek", ""))
        assertEquals("openrouter", CloudflareGateway.providerSlug("openrouter", ""))
        assertEquals("google-ai-studio", CloudflareGateway.providerSlug("google", ""))
        assertEquals("google-ai-studio", CloudflareGateway.providerSlug("Google", " "))
    }

    @Test
    fun slug_unknownProviders_returnNullWithoutCustom() {
        assertNull(CloudflareGateway.providerSlug("kimi", ""))
        assertNull(CloudflareGateway.providerSlug("bailian", ""))
        assertNull(CloudflareGateway.providerSlug("siliconflow", ""))
        assertNull(CloudflareGateway.providerSlug("command-code", ""))
        assertNull(CloudflareGateway.providerSlug("opencode", ""))
        assertNull(CloudflareGateway.providerSlug("ollama", ""))
        assertNull(CloudflareGateway.providerSlug("", ""))
    }

    @Test
    fun slug_customOverridesKnown() {
        assertEquals("my-slug", CloudflareGateway.providerSlug("openai", " my-slug "))
    }

    // ----------------------------------------------------- rewriteEndpoint

    @Test
    fun rewrite_openaiChatPath() {
        val rewritten = CloudflareGateway.rewriteEndpoint(
            originalEndpoint = "https://api.openai.com/v1/responses",
            accountId = "acc123",
            gatewayName = "zafiro",
            slug = "openai",
        )
        assertEquals(
            "https://gateway.ai.cloudflare.com/v1/acc123/zafiro/openai/v1/responses",
            rewritten,
        )
    }

    @Test
    fun rewrite_anthropicMessages() {
        val rewritten = CloudflareGateway.rewriteEndpoint(
            originalEndpoint = "https://api.anthropic.com/v1/messages",
            accountId = "acc123",
            gatewayName = "zafiro",
            slug = "anthropic",
        )
        assertEquals(
            "https://gateway.ai.cloudflare.com/v1/acc123/zafiro/anthropic/v1/messages",
            rewritten,
        )
    }

    @Test
    fun rewrite_deepseekWithoutV1Segment() {
        val rewritten = CloudflareGateway.rewriteEndpoint(
            originalEndpoint = "https://api.deepseek.com/responses",
            accountId = "acc123",
            gatewayName = "zafiro",
            slug = "deepseek",
        )
        assertEquals(
            "https://gateway.ai.cloudflare.com/v1/acc123/zafiro/deepseek/responses",
            rewritten,
        )
    }

    @Test
    fun rewrite_preservesQuery() {
        val rewritten = CloudflareGateway.rewriteEndpoint(
            originalEndpoint = "https://api.openai.com/v1/chat/completions?api-version=2024",
            accountId = "acc",
            gatewayName = "gw",
            slug = "openai",
        )
        assertEquals(
            "https://gateway.ai.cloudflare.com/v1/acc/gw/openai/v1/chat/completions?api-version=2024",
            rewritten,
        )
    }

    @Test
    fun rewrite_skipsNonHttps() {
        assertNull(
            CloudflareGateway.rewriteEndpoint(
                "http://127.0.0.1:11434/v1/chat/completions", "acc", "gw", "openai",
            )
        )
    }

    @Test
    fun rewrite_skipsPlainHostWithoutScheme() {
        assertNull(
            CloudflareGateway.rewriteEndpoint("localhost:11434/v1", "acc", "gw", "openai")
        )
    }

    @Test
    fun rewrite_skipsAlreadyGatewayUrl() {
        val gatewayUrl = "https://gateway.ai.cloudflare.com/v1/acc/gw/openai/v1/chat/completions"
        assertNull(
            CloudflareGateway.rewriteEndpoint(gatewayUrl, "acc", "gw", "openai")
        )
    }

    @Test
    fun rewrite_skipsBlankInputs() {
        assertNull(CloudflareGateway.rewriteEndpoint("https://api.openai.com/v1", "", "gw", "openai"))
        assertNull(CloudflareGateway.rewriteEndpoint("https://api.openai.com/v1", "acc", "", "openai"))
        assertNull(CloudflareGateway.rewriteEndpoint("https://api.openai.com/v1", "acc", "gw", ""))
        assertNull(CloudflareGateway.rewriteEndpoint("", "acc", "gw", "openai"))
    }

    // ------------------------------------------------ gatewayEndpointFor

    @Test
    fun combined_endpointFor() {
        assertEquals(
            "https://gateway.ai.cloudflare.com/v1/acc/gw/openai/v1/chat/completions",
            CloudflareGateway.gatewayEndpointFor(
                providerId = "openai",
                customSlug = "",
                originalEndpoint = "https://api.openai.com/v1/chat/completions",
                accountId = "acc",
                gatewayName = "gw",
            ),
        )
        assertNull(
            CloudflareGateway.gatewayEndpointFor(
                providerId = "kimi",
                customSlug = "",
                originalEndpoint = "https://api.moonshot.cn/v1/chat/completions",
                accountId = "acc",
                gatewayName = "gw",
            ),
        )
    }
}
