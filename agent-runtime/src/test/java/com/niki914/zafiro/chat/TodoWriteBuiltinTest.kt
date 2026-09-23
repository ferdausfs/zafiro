package com.niki914.zafiro.chat

import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.zafiro.chat.agentic.buildin.impl.TodoWriteBuiltin
import com.niki914.zafiro.settings.RuntimeEnvironment
import com.niki914.zafiro.settings.model.RuntimeTodoItem
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

class TodoWriteBuiltinTest {
    @After
    fun tearDown() {
        RuntimeEnvironment.clearForTest()
    }

    @Test
    fun todoWrite_replacesPlanAndReturnsNormalizedTodos() = runTest {
        val store = installRuntimeSettingsGatewayForTest()

        val resultJson = TodoWriteBuiltin().invokeRawJson(
            BuiltinToolRequest(
                name = "todo_write",
                argumentsJson = """
                    {"todos":[
                      {"content":" Clone repo ","status":"completed"},
                      {"status":"in_progress","content":"Fix bug"},
                      {"content":"Run tests","status":"WEIRD"}
                    ]}
                """.trimIndent(),
            )
        )

        val json = Json.parseToJsonElement(resultJson).jsonObject
        assertTrue(json["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(3, json["todos"]!!.jsonArray.size)

        val persisted = store.todoItems
        assertEquals(
            listOf(
                RuntimeTodoItem("Clone repo", RuntimeTodoItem.TODO_STATUS_COMPLETED),
                RuntimeTodoItem("Fix bug", RuntimeTodoItem.TODO_STATUS_IN_PROGRESS),
                RuntimeTodoItem("Run tests", RuntimeTodoItem.TODO_STATUS_PENDING),
            ),
            persisted,
        )
        assertEquals(1, store.todoWriteCount)
    }

    @Test
    fun todoWrite_rejectsMultipleInProgress() = runTest {
        installRuntimeSettingsGatewayForTest()

        val resultJson = TodoWriteBuiltin().invokeRawJson(
            BuiltinToolRequest(
                name = "todo_write",
                argumentsJson = """
                    {"todos":[
                      {"content":"A","status":"in_progress"},
                      {"content":"B","status":"in_progress"}
                    ]}
                """.trimIndent(),
            )
        )

        val json = Json.parseToJsonElement(resultJson).jsonObject
        assertFalse(json["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("INVALID_ARGUMENTS", json["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun todoWrite_rejectsMissingTodosField() = runTest {
        installRuntimeSettingsGatewayForTest()

        val resultJson = TodoWriteBuiltin().invokeRawJson(
            BuiltinToolRequest(
                name = "todo_write",
                argumentsJson = """{"items":[{"content":"A","status":"pending"}]}""",
            )
        )

        val json = Json.parseToJsonElement(resultJson).jsonObject
        assertEquals("INVALID_ARGUMENTS", json["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun todoWrite_directInvokeReturnsRawJsonOnlyError() = runTest {
        installRuntimeSettingsGatewayForTest()

        val result = TodoWriteBuiltin().invoke(
            BuiltinToolRequest(name = "todo_write", argumentsJson = """{"todos":[]}""")
        )

        val json = Json.parseToJsonElement(result.toJsonString()).jsonObject
        assertEquals("RAW_JSON_ONLY", json["code"]!!.jsonPrimitive.content)
    }
}
