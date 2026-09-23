package com.niki914.zafiro.chat.agentic.buildin.impl

import com.niki914.zafiro.chat.agentic.buildin.BuiltinTool
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolResult
import com.niki914.zafiro.chat.agentic.buildin.RawJsonBuiltinTool
import com.niki914.zafiro.settings.RuntimeEnvironment
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 凭证库访问工具：用户在 设置 → Tokens 中保存的凭据（GitHub token、Cloudflare API token 等）。
 * list 只返回 name/note；get 按精确名称取回明文（供 shell/HTTP 认证使用）。
 */
class TokenVaultBuiltin : BuiltinTool(), RawJsonBuiltinTool {
    override val name: String = "vault_token"

    override val description: String =
        "Access the user's credential vault (Setup → Tokens): GitHub tokens, Cloudflare API " +
                "tokens, and other secrets the user stored for you to use.\n\n" +
                "Actions: list (see available credential names and notes — never values), " +
                "get (fetch one credential's value by its exact name). " +
                "Workflow: list first when unsure what is available, then get the exact entry you " +
                "need. Use fetched values for authentication (e.g. export GH_TOKEN=<value> before " +
                "gh/git commands, or as Bearer tokens in HTTP calls) instead of writing them into " +
                "files. Do not repeat secret values in your visible answer unless the user asks."

    override val defaultEnabled: Boolean = true

    override val inputSchemaJson: String? get() = VAULT_SCHEMA

    override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult {
        return BuiltinToolResult.failure(
            code = "RAW_JSON_ONLY",
            message = "$name must be executed through invokeRawJson().",
            hint = """Example: {"action":"get","name":"github_token"}""",
        )
    }

    override suspend fun invokeRawJson(request: BuiltinToolRequest): String {
        val args = try {
            parseArgs(request.argumentsJson)
        } catch (error: CancellationException) {
            throw error
        } catch (error: IllegalArgumentException) {
            return BuiltinToolResult.failure(
                code = "INVALID_ARGUMENTS",
                message = error.message ?: "Invalid arguments.",
                hint = """Example: {"action":"get","name":"github_token"}""",
            ).toJsonString()
        }

        return try {
            val gateway = RuntimeEnvironment.awaitSettingsGateway()
            when (args.action) {
                Action.LIST -> {
                    val summaries = gateway.listVaultTokens()
                    buildJsonObject {
                        put("ok", true)
                        put("action", "list")
                        put("count", summaries.size)
                        put(
                            "tokens",
                            kotlinx.serialization.json.JsonArray(
                                summaries.map { summary ->
                                    buildJsonObject {
                                        put("name", summary.name)
                                        put("note", summary.note)
                                    }
                                }
                            )
                        )
                    }.toString()
                }

                Action.GET -> {
                    val name = args.name!!.trim()
                    val value = gateway.vaultTokenValue(name)
                    if (value == null) {
                        BuiltinToolResult.failure(
                            code = "NOT_FOUND",
                            message = "No credential named '$name' in the vault.",
                            hint = "Call {\"action\":\"list\"} to see available credential names.",
                        ).toJsonString()
                    } else {
                        buildJsonObject {
                            put("ok", true)
                            put("action", "get")
                            put("name", name)
                            put("value", value)
                        }.toString()
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            BuiltinToolResult.failure(
                code = "VAULT_UNAVAILABLE",
                message = "Failed to access the credential vault: " +
                        "${error.message ?: error::class.java.simpleName}.",
                hint = "The vault may be temporarily unavailable; inform the user if it persists.",
            ).toJsonString()
        }
    }

    private fun parseArgs(argumentsJson: String): Args {
        val element = try {
            Json.parseToJsonElement(argumentsJson)
        } catch (error: SerializationException) {
            throw IllegalArgumentException("argumentsJson is not valid JSON.")
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("argumentsJson is not valid JSON.")
        }
        val obj = element as? JsonObject
            ?: throw IllegalArgumentException("argumentsJson must be a JSON object.")

        val action = Action.from(obj["action"]?.jsonPrimitive?.contentOrNull)
        val name = obj["name"]?.jsonPrimitive?.contentOrNull?.trim()
        return Args(action, name)
    }

    private enum class Action {
        LIST, GET;

        companion object {
            fun from(wire: String?): Action {
                return when (wire?.trim()?.lowercase()) {
                    "list" -> LIST
                    "get" -> GET
                    else -> throw IllegalArgumentException(
                        "Unknown action '${wire?.trim().orEmpty()}'. Expected list or get."
                    )
                }
            }
        }
    }

    private data class Args(
        val action: Action,
        val name: String?,
    )

    private companion object {
        private const val VAULT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "action": {
                  "type": "string",
                  "enum": ["list", "get"],
                  "description": "list = show credential names and notes; get = fetch one credential's value."
                },
                "name": {
                  "type": "string",
                  "description": "Exact credential name. Required for 'get'."
                }
              },
              "required": ["action"]
            }
        """
    }
}
