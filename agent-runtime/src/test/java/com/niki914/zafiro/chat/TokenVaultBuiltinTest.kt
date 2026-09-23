package com.niki914.zafiro.chat

import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.zafiro.chat.agentic.buildin.impl.TokenVaultBuiltin
import com.niki914.zafiro.settings.RuntimeEnvironment
import com.niki914.zafiro.settings.model.RuntimeVaultTokenSummary
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenVaultBuiltinTest {
    @After
    fun tearDown() {
        RuntimeEnvironment.clearForTest()
    }

    @Test
    fun vault_listReturnsNamesAndNotesOnly() = runTest {
        val store = installRuntimeSettingsGatewayForTest(
            FakeRuntimeSettingsGateway().apply {
                vaultTokens = listOf(
                    RuntimeVaultTokenSummary(name = "github_token", note = "PAT"),
                    RuntimeVaultTokenSummary(name = "cloudflare_token"),
                )
                vaultValues = mapOf("github_token" to "ghp_secret")
            }
        )

        val resultJson = TokenVaultBuiltin().invokeRawJson(
            BuiltinToolRequest(name = "vault_token", argumentsJson = """{"action":"list"}""")
        )

        val json = Json.parseToJsonElement(resultJson).jsonObject
        assertTrue(json["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(2, json["count"]!!.jsonPrimitive.content.toInt())
        val serialized = json["tokens"]!!.jsonArray.toString()
        assertTrue(serialized.contains("github_token"))
        // list 绝不携带明文值
        assertFalse(serialized.contains("ghp_secret"))
    }

    @Test
    fun vault_getReturnsValueByExactName() = runTest {
        installRuntimeSettingsGatewayForTest(
            FakeRuntimeSettingsGateway().apply {
                vaultValues = mapOf("github_token" to "ghp_secret")
            }
        )

        val resultJson = TokenVaultBuiltin().invokeRawJson(
            BuiltinToolRequest(
                name = "vault_token",
                argumentsJson = """{"action":"get","name":"github_token"}""",
            )
        )

        val json = Json.parseToJsonElement(resultJson).jsonObject
        assertTrue(json["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("ghp_secret", json["value"]!!.jsonPrimitive.content)
    }

    @Test
    fun vault_getUnknownNameReturnsStructuredNotFound() = runTest {
        installRuntimeSettingsGatewayForTest()

        val resultJson = TokenVaultBuiltin().invokeRawJson(
            BuiltinToolRequest(
                name = "vault_token",
                argumentsJson = """{"action":"get","name":"missing"}""",
            )
        )

        val json = Json.parseToJsonElement(resultJson).jsonObject
        assertFalse(json["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("NOT_FOUND", json["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun vault_invalidActionReturnsInvalidArguments() = runTest {
        installRuntimeSettingsGatewayForTest()

        val resultJson = TokenVaultBuiltin().invokeRawJson(
            BuiltinToolRequest(
                name = "vault_token",
                argumentsJson = """{"action":"steal"}""",
            )
        )

        val json = Json.parseToJsonElement(resultJson).jsonObject
        assertEquals("INVALID_ARGUMENTS", json["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun vault_gatewayFailureReturnsVaultUnavailable() = runTest {
        installRuntimeSettingsGatewayForTest(
            FakeRuntimeSettingsGateway().apply { failVault = IllegalStateException("boom") }
        )

        val resultJson = TokenVaultBuiltin().invokeRawJson(
            BuiltinToolRequest(name = "vault_token", argumentsJson = """{"action":"list"}""")
        )

        val json = Json.parseToJsonElement(resultJson).jsonObject
        assertEquals("VAULT_UNAVAILABLE", json["code"]!!.jsonPrimitive.content)
    }
}
