package com.niki914.zafiro.repo

import com.niki914.logging.Logger
import com.niki914.store.StoreDescriptorRegistry
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.array
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.long
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.orEmptyObjects
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.parseObject
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.string
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.stringValues
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 后台任务（Feature: Asynchronous Background Execution）持久化。
 *
 * 用户发起的回合经 BackgroundTaskHub 在应用进程 scope 执行（FGS 保活），
 * 与聊天 UI 生命周期解耦：关掉聊天页/退到桌面任务继续跑，结束后通知。
 * 本 store 记录任务台账（运行中/完成/失败/取消/中断），用于任务列表 UI、
 * 通知文案与进程死亡后的中断标记。
 */
enum class BackgroundTaskStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
    /** 进程被杀等导致的中断（启动时对上一进程遗留 RUNNING 的标记）。 */
    INTERRUPTED,
}

data class BackgroundTaskRecord(
    val id: String,
    val conversationId: String = "",
    /** 用户原始输入（不含文档上下文前缀）。 */
    val query: String = "",
    /** 附件图片落盘路径（image_cache，进程死亡后仍可用于重试展示）。 */
    val imagePaths: List<String> = emptyList(),
    /** 附件文档名列表（展示用）。 */
    val documentNames: List<String> = emptyList(),
    val status: BackgroundTaskStatus = BackgroundTaskStatus.RUNNING,
    val createdAt: Long = 0L,
    val finishedAt: Long = 0L,
    /** 完成时助手回复末段预览（通知正文 / 列表展示）。 */
    val resultPreview: String = "",
    val error: String = "",
    /** 进程内：本任务产生的事件流 id（重连 UI 用）。 */
    val modelFallbacks: Int = 0,
) {
    val isTerminal: Boolean
        get() = status != BackgroundTaskStatus.RUNNING
}

internal object BackgroundTasksCodec {

    private const val TASKS_KEY = "tasks"
    private const val ID_KEY = "id"
    private const val CONVERSATION_KEY = "conversationId"
    private const val QUERY_KEY = "query"
    private const val IMAGES_KEY = "imagePaths"
    private const val DOCS_KEY = "documentNames"
    private const val STATUS_KEY = "status"
    private const val CREATED_KEY = "createdAt"
    private const val FINISHED_KEY = "finishedAt"
    private const val RESULT_KEY = "resultPreview"
    private const val ERROR_KEY = "error"
    private const val FALLBACKS_KEY = "modelFallbacks"
    private const val LEGACY_IMAGES_PLURAL = "images"

    fun parse(json: String): List<BackgroundTaskRecord> {
        return parseObject(json)
            .array(TASKS_KEY)
            .orEmptyObjects()
            .mapNotNull { obj ->
                val id = obj.string(ID_KEY).trim()
                if (id.isBlank()) return@mapNotNull null
                BackgroundTaskRecord(
                    id = id,
                    conversationId = obj.string(CONVERSATION_KEY),
                    query = obj.string(QUERY_KEY),
                    imagePaths = obj.array(IMAGES_KEY)?.stringValues()
                        ?: obj.array(LEGACY_IMAGES_PLURAL)?.stringValues()
                        ?: emptyList(),
                    documentNames = obj.array(DOCS_KEY)?.stringValues() ?: emptyList(),
                    status = BackgroundTaskStatus.entries.firstOrNull {
                        it.name == obj.string(STATUS_KEY)
                    } ?: BackgroundTaskStatus.RUNNING,
                    createdAt = obj.long(CREATED_KEY, 0L),
                    finishedAt = obj.long(FINISHED_KEY, 0L),
                    resultPreview = obj.string(RESULT_KEY),
                    error = obj.string(ERROR_KEY),
                    modelFallbacks = obj.long(FALLBACKS_KEY, 0L).toInt(),
                )
            }
    }

    fun encode(tasks: List<BackgroundTaskRecord>): String {
        return JsonObject(
            mapOf(
                TASKS_KEY to JsonArray(
                    tasks.map { task ->
                        JsonObject(
                            mapOf(
                                ID_KEY to JsonPrimitive(task.id),
                                CONVERSATION_KEY to JsonPrimitive(task.conversationId),
                                QUERY_KEY to JsonPrimitive(task.query),
                                IMAGES_KEY to SettingsJsonCodecUtils.stringArray(task.imagePaths),
                                DOCS_KEY to SettingsJsonCodecUtils.stringArray(task.documentNames),
                                STATUS_KEY to JsonPrimitive(task.status.name),
                                CREATED_KEY to JsonPrimitive(task.createdAt),
                                FINISHED_KEY to JsonPrimitive(task.finishedAt),
                                RESULT_KEY to JsonPrimitive(task.resultPreview),
                                ERROR_KEY to JsonPrimitive(task.error),
                                FALLBACKS_KEY to JsonPrimitive(task.modelFallbacks),
                            )
                        )
                    }
                ),
            )
        ).toString()
    }
}

/** 后台任务台账 API：XRepo.backgroundTasks */
class BackgroundTaskApi internal constructor(
    private val repo: XRepo,
) {

    suspend fun list(): List<BackgroundTaskRecord> {
        return BackgroundTasksCodec.parse(repo.readJson(storeId()))
            .sortedByDescending { it.createdAt }
    }

    suspend fun get(id: String): BackgroundTaskRecord? = list().firstOrNull { it.id == id }

    suspend fun upsert(record: BackgroundTaskRecord) {
        repo.updateJson(storeId()) { json ->
            val tasks = BackgroundTasksCodec.parse(json)
            val updated = if (tasks.any { it.id == record.id }) {
                tasks.map { if (it.id == record.id) record else it }
            } else {
                tasks + record
            }
            BackgroundTasksCodec.encode(updated.takeLast(MAX_RECORDS))
        }
    }

    /** 把 [staleIds] 标记为 INTERRUPTED（进程死亡后遗留的 RUNNING）。 */
    suspend fun markInterrupted(staleIds: Collection<String>): Int {
        if (staleIds.isEmpty()) return 0
        var marked = 0
        repo.updateJson(storeId()) { json ->
            val tasks = BackgroundTasksCodec.parse(json)
            val updated = tasks.map { task ->
                if (task.id in staleIds && !task.isTerminal) {
                    marked += 1
                    task.copy(
                        status = BackgroundTaskStatus.INTERRUPTED,
                        finishedAt = System.currentTimeMillis(),
                        error = task.error.ifBlank { "interrupted by app exit" },
                    )
                } else {
                    task
                }
            }
            BackgroundTasksCodec.encode(updated)
        }
        if (marked > 0) {
            Logger.i(LOG_TAG, "marked interrupted tasks=$marked")
        }
        return marked
    }

    suspend fun delete(id: String): Boolean {
        var removed = false
        repo.updateJson(storeId()) { json ->
            val tasks = BackgroundTasksCodec.parse(json)
            removed = tasks.any { it.id == id }
            BackgroundTasksCodec.encode(tasks.filterNot { it.id == id })
        }
        return removed
    }

    suspend fun clearFinished() {
        repo.updateJson(storeId()) { json ->
            BackgroundTasksCodec.encode(
                BackgroundTasksCodec.parse(json).filterNot { it.isTerminal }
            )
        }
    }

    private fun storeId(): String = StoreDescriptorRegistry.AGENT_TASKS_ID

    private companion object {
        private const val LOG_TAG = "niki914_nexus_BackgroundTasks"
        private const val MAX_RECORDS = 50
    }
}
