package com.niki914.zafiro.repo

import com.niki914.zafiro.repo.SettingsJsonCodecUtils.array
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.orEmptyObjects
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.parseObject
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.string
import com.niki914.zafiro.settings.model.RuntimeTodoItem
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * agent.todo store 的文档结构：
 * {"todos":[{"content":"...","status":"pending"}],"updated_at":0}
 * todo_write 工具整表替换；TODO 设置页读取展示。
 */
internal object TodoSettingsCodec {

    fun parse(json: String): List<RuntimeTodoItem> {
        val root = parseObject(json)
        return root.array(TODOS_KEY).orEmptyObjects().mapNotNull(::parseItem)
    }

    fun encode(items: List<RuntimeTodoItem>): String {
        return JsonObject(
            mapOf(
                TODOS_KEY to JsonArray(items.map(::encodeItem)),
                UPDATED_AT_KEY to JsonPrimitive(System.currentTimeMillis()),
            )
        ).toString()
    }

    private fun parseItem(obj: JsonObject): RuntimeTodoItem? {
        val content = obj.string(CONTENT_KEY).trim().takeIf(String::isNotBlank) ?: return null
        return RuntimeTodoItem(
            content = content,
            status = RuntimeTodoItem.normalizeStatus(obj.string(STATUS_KEY)),
        )
    }

    private fun encodeItem(item: RuntimeTodoItem): JsonObject {
        return JsonObject(
            mapOf(
                CONTENT_KEY to JsonPrimitive(item.content),
                STATUS_KEY to JsonPrimitive(item.status),
            )
        )
    }

    private const val TODOS_KEY = "todos"
    private const val UPDATED_AT_KEY = "updated_at"
    private const val CONTENT_KEY = "content"
    private const val STATUS_KEY = "status"
}
