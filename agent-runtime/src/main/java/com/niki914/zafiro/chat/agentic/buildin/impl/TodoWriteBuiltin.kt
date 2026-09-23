package com.niki914.zafiro.chat.agentic.buildin.impl

import com.niki914.zafiro.chat.agentic.buildin.BuiltinTool
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolResult
import com.niki914.zafiro.chat.agentic.buildin.RawJsonBuiltinTool
import com.niki914.zafiro.settings.RuntimeEnvironment
import com.niki914.zafiro.settings.model.RuntimeTodoItem
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Agent 计划工具（语义同 Claude Code 的 TodoWrite）：整表替换当前任务计划。
 * 计划持久化到 agent.todo store，用户可在 Zafiro 的 TODO 页实时查看进度。
 */
class TodoWriteBuiltin : BuiltinTool(), RawJsonBuiltinTool {
    override val name: String = "todo_write"

    override val description: String =
        "Maintain your visible task plan. The plan is shown to the user in real time.\n\n" +
                "Send the COMPLETE list every time (it replaces the previous plan). Each item has " +
                "content and status: pending, in_progress, or completed. Rules:\n" +
                "- Start any multi-step task (3+ steps) by writing the full plan.\n" +
                "- Exactly ONE item may be in_progress at a time — the thing you are doing now.\n" +
                "- Mark items completed IMMEDIATELY after the step is done and verified.\n" +
                "- Add newly discovered work; remove items that became irrelevant.\n" +
                "- When the whole task is finished, send the final list with everything completed.\n" +
                "- Skip the tool for trivial single-step questions."

    override val defaultEnabled: Boolean = true

    override val inputSchemaJson: String? get() = TODO_SCHEMA

    override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult {
        return BuiltinToolResult.failure(
            code = "RAW_JSON_ONLY",
            message = "$name must be executed through invokeRawJson().",
            hint = """Example: {"todos":[{"content":"Clone repo","status":"completed"},""" +
                    """{"content":"Fix bug","status":"in_progress"}]}""",
        )
    }

    override suspend fun invokeRawJson(request: BuiltinToolRequest): String {
        val items = try {
            parseItems(request.argumentsJson)
        } catch (error: CancellationException) {
            throw error
        } catch (error: IllegalArgumentException) {
            return BuiltinToolResult.failure(
                code = "INVALID_ARGUMENTS",
                message = error.message ?: "Invalid arguments.",
                hint = """Example: {"todos":[{"content":"Step 1","status":"pending"}]}""",
            ).toJsonString()
        }

        if (items.size > MAX_ITEMS) {
            return BuiltinToolResult.failure(
                code = "TOO_MANY_ITEMS",
                message = "Plan exceeds $MAX_ITEMS items; split the task or raise the abstraction level.",
            ).toJsonString()
        }

        return try {
            val gateway = RuntimeEnvironment.awaitSettingsGateway()
            gateway.writeTodoItems(items)
            JsonArray(
                items.map { item ->
                    JsonObject(
                        mapOf(
                            "content" to kotlinx.serialization.json.JsonPrimitive(item.content),
                            "status" to kotlinx.serialization.json.JsonPrimitive(item.status),
                        )
                    )
                }
            ).let { list ->
                JsonObject(
                    mapOf(
                        "ok" to kotlinx.serialization.json.JsonPrimitive(true),
                        "todos" to list,
                    )
                )
            }.toString()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            BuiltinToolResult.failure(
                code = "TODO_WRITE_FAILED",
                message = "Failed to persist the plan: ${error.message ?: error::class.java.simpleName}.",
                hint = "Continue the task; the plan view may be temporarily unavailable.",
            ).toJsonString()
        }
    }

    private fun parseItems(argumentsJson: String): List<RuntimeTodoItem> {
        val element = try {
            Json.parseToJsonElement(argumentsJson)
        } catch (error: SerializationException) {
            throw IllegalArgumentException("argumentsJson is not valid JSON.")
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("argumentsJson is not valid JSON.")
        }
        val obj = element as? JsonObject
            ?: throw IllegalArgumentException("argumentsJson must be a JSON object.")
        val todos = obj["todos"]?.jsonArray
            ?: throw IllegalArgumentException("Field 'todos' (array) is required.")

        val items = mutableListOf<RuntimeTodoItem>()
        todos.forEachIndexed { index, element ->
            val itemObj = element as? JsonObject
                ?: throw IllegalArgumentException("todos[$index] must be an object.")
            val content = itemObj["content"]?.jsonPrimitive?.contentOrNull?.trim()
            if (content.isNullOrEmpty()) {
                throw IllegalArgumentException("todos[$index].content must be a non-empty string.")
            }
            val status = RuntimeTodoItem.normalizeStatus(
                itemObj["status"]?.jsonPrimitive?.contentOrNull
            )
            items += RuntimeTodoItem(content = content, status = status)
        }
        if (items.isEmpty()) {
            throw IllegalArgumentException("'todos' must contain at least one item.")
        }
        if (items.count { it.status == RuntimeTodoItem.TODO_STATUS_IN_PROGRESS } > 1) {
            throw IllegalArgumentException(
                "At most one item may be in_progress; keep the rest pending or completed."
            )
        }
        return items
    }

    private companion object {
        private const val MAX_ITEMS = 50

        private const val TODO_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "todos": {
                  "type": "array",
                  "description": "The COMPLETE task plan; replaces the previous list.",
                  "items": {
                    "type": "object",
                    "properties": {
                      "content": {
                        "type": "string",
                        "description": "Imperative step description, e.g. 'Run unit tests'."
                      },
                      "status": {
                        "type": "string",
                        "enum": ["pending", "in_progress", "completed"],
                        "description": "Exactly one item should be in_progress while working."
                      }
                    },
                    "required": ["content", "status"]
                  }
                }
              },
              "required": ["todos"]
            }
        """
    }
}
