package com.niki914.zafiro.repo

import com.niki914.logging.Logger
import com.niki914.store.StoreDescriptorRegistry
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.array
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.orEmptyObjects
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.parseObject
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

/**
 * Cloud Brain 离线发件箱（outbox）。
 *
 * 断网时提交不出去的云端任务 / 回传的动作结果，先落盘排队；
 * 网络恢复（ConnectivityManager 回调）或周期扫描时按序重放。
 * 典型场景：短信通知触发的回复任务——发送方不依赖网络，
 * 但生成回复需要云端；断网期间入队，联网后自动补交。
 */
enum class CloudOutboxType {
    /** payload = CloudTaskSubmit JSON。 */
    SUBMIT_TASK,

    /** payload = CloudResultsPost JSON。 */
    POST_RESULTS,
}

data class CloudOutboxItem(
    val id: String,
    val type: CloudOutboxType,
    /** 与类型对应的请求体 JSON（重放时原样提交）。 */
    val payloadJson: String,
    val createdAtMs: Long,
    val attempts: Int = 0,
    val lastError: String = "",
)

/** 发件箱 JSON 编解码（存储格式 {"items":[...]}，按创建时间升序）。 */
internal object CloudOutboxCodec {

    private const val ITEMS_KEY = "items"
    private const val ID_KEY = "id"
    private const val TYPE_KEY = "type"
    private const val PAYLOAD_KEY = "payloadJson"
    private const val CREATED_KEY = "createdAtMs"
    private const val ATTEMPTS_KEY = "attempts"
    private const val LAST_ERROR_KEY = "lastError"

    fun parse(json: String): List<CloudOutboxItem> {
        return parseObject(json)
            .array(ITEMS_KEY)
            .orEmptyObjects()
            .mapNotNull { obj ->
                val payload = obj.string(PAYLOAD_KEY)
                if (payload.isBlank()) return@mapNotNull null
                CloudOutboxItem(
                    id = obj.string(ID_KEY).trim(),
                    type = CloudOutboxType.entries.firstOrNull {
                        it.name == obj.string(TYPE_KEY)
                    } ?: CloudOutboxType.SUBMIT_TASK,
                    payloadJson = payload,
                    createdAtMs = obj.string(CREATED_KEY).toLongOrNull() ?: 0L,
                    attempts = obj.string(ATTEMPTS_KEY).toIntOrNull() ?: 0,
                    lastError = obj.string(LAST_ERROR_KEY),
                )
            }
            .sortedBy { it.createdAtMs }
    }

    fun encode(items: List<CloudOutboxItem>): String {
        return JsonObject(
            mapOf(
                ITEMS_KEY to JsonArray(
                    items.map { item ->
                        JsonObject(
                            mapOf(
                                ID_KEY to JsonPrimitive(item.id),
                                TYPE_KEY to JsonPrimitive(item.type.name),
                                PAYLOAD_KEY to JsonPrimitive(item.payloadJson),
                                CREATED_KEY to JsonPrimitive(item.createdAtMs),
                                ATTEMPTS_KEY to JsonPrimitive(item.attempts),
                                LAST_ERROR_KEY to JsonPrimitive(item.lastError),
                            )
                        )
                    }
                ),
            )
        ).toString()
    }
}

/** 发件箱领域 API：XRepo.cloudOutbox */
class CloudOutboxApi internal constructor(
    private val repo: XRepo,
) {

    suspend fun pending(): List<CloudOutboxItem> {
        return CloudOutboxCodec.parse(repo.readJson(storeId()))
    }

    suspend fun count(): Int = pending().size

    suspend fun add(type: CloudOutboxType, payloadJson: String): CloudOutboxItem {
        val item = CloudOutboxItem(
            id = "outbox-${UUID.randomUUID()}",
            type = type,
            payloadJson = payloadJson,
            createdAtMs = System.currentTimeMillis(),
        )
        repo.updateJson(storeId()) { json ->
            CloudOutboxCodec.encode(
                (CloudOutboxCodec.parse(json) + item).takeLast(MAX_ITEMS)
            )
        }
        Logger.i(LOG_TAG, "enqueue type=$type size=${item.payloadJson.length}")
        return item
    }

    suspend fun remove(id: String) {
        repo.updateJson(storeId()) { json ->
            CloudOutboxCodec.encode(
                CloudOutboxCodec.parse(json).filterNot { it.id == id }
            )
        }
    }

    suspend fun markAttempted(id: String, error: String) {
        repo.updateJson(storeId()) { json ->
            CloudOutboxCodec.encode(
                CloudOutboxCodec.parse(json).map { item ->
                    if (item.id == id) {
                        item.copy(attempts = item.attempts + 1, lastError = error.take(200))
                    } else {
                        item
                    }
                }
            )
        }
    }

    /** 放弃超龄条目（重试次数超限），返回被清除的数量。 */
    suspend fun dropExhausted(): Int {
        var dropped = 0
        repo.updateJson(storeId()) { json ->
            val kept = CloudOutboxCodec.parse(json).filter { item ->
                if (item.attempts >= MAX_ATTEMPTS) {
                    dropped++
                    false
                } else {
                    true
                }
            }
            CloudOutboxCodec.encode(kept)
        }
        return dropped
    }

    private fun storeId(): String = StoreDescriptorRegistry.CLOUD_OUTBOX_ID

    private companion object {
        private const val LOG_TAG = "niki914_nexus_CloudOutboxApi"
        private const val MAX_ITEMS = 50
        private const val MAX_ATTEMPTS = 30
    }
}
