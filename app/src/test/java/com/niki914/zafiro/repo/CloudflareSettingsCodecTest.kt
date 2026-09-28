package com.niki914.zafiro.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** CloudflareSettingsCodec 编解码往返 + 默认值解析（纯 JVM，无 Android 依赖）。 */
class CloudflareSettingsCodecTest {

    @Test
    fun parse_defaultJson_returnsAllDefaults() {
        val settings = CloudflareSettingsCodec.parse("{}")

        assertFalse(settings.gatewayEnabled)
        assertFalse(settings.brainEnabled)
        assertEquals("", settings.accountId)
        assertEquals("", settings.gatewayName)
        assertEquals("", settings.customProviderSlug)
        assertEquals("", settings.workerUrl)
        assertEquals("", settings.brainSecret)
        assertFalse(settings.gatewayReady())
        assertFalse(settings.brainReady())
    }

    @Test
    fun encode_thenParse_roundTripsAllFields() {
        val original = CloudflareSettings(
            gatewayEnabled = true,
            accountId = "023e105f4ecef8ad9ca31a8372d0c353",
            gatewayName = "zafiro-gw",
            customProviderSlug = "openai-compat",
            brainEnabled = true,
            // parse() 会 trimEnd('/')：round-trip 用无尾斜杠形式
            workerUrl = "https://zafiro-cloud-brain.example.workers.dev",
            brainSecret = "s3cret",
        )

        val parsed = CloudflareSettingsCodec.parse(CloudflareSettingsCodec.encode(original))

        assertEquals(original, parsed)
    }

    @Test
    fun readiness_flags() {
        val gatewayOnly = CloudflareSettings(
            gatewayEnabled = true,
            accountId = "acc",
            gatewayName = "gw",
        )
        assertTrue(gatewayOnly.gatewayReady())
        assertFalse(gatewayOnly.brainReady())

        val brainOnly = CloudflareSettings(
            brainEnabled = true,
            workerUrl = "https://w.workers.dev",
            brainSecret = "s",
        )
        assertTrue(brainOnly.brainReady())
        assertFalse(brainOnly.gatewayReady())

        val enabledButIncomplete = CloudflareSettings(gatewayEnabled = true, brainEnabled = true)
        assertFalse(enabledButIncomplete.gatewayReady())
        assertFalse(enabledButIncomplete.brainReady())
    }
}
