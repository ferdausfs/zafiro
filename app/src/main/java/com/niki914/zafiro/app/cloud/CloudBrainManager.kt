package com.niki914.zafiro.app.cloud

import com.niki914.logging.Logger
import com.niki914.zafiro.repo.CloudOutboxType
import com.niki914.zafiro.repo.XRepo
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import java.io.IOException

/**
 * Cloud Brain 会话驱动器：提交任务 → 长轮询 → 执行手机动作 → 回传结果 → 拿最终答复。
 *
 * 断网韧性（关键）：
 *  - 提交失败（无网）→ 任务进入发件箱，CloudOutboxDispatcher 网络恢复后重放；
 *  - 会话中途断网 → poll 指数退避重试到会话超时为止，服务器大脑照常推进，
 *    网络恢复后无缝续上（这正是「SMS 发出后断网回复丢失」的修复）。
 */
object CloudBrainManager {

    private const val LOG_TAG = "niki914_nexus_CloudBrainManager"

    /** 单会话最长生命周期：10 分钟。 */
    private const val SESSION_TIMEOUT_MS = 10 * 60 * 1000L

    /** poll 失败后的重试间隔（指数退避上限 60s）。 */
    private const val POLL_RETRY_BASE_MS = 3_000L
    private const val POLL_RETRY_MAX_MS = 60_000L

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    /** 进程内设备标识（服务端仅用于日志聚合，不参与鉴权）。 */
    private val deviceId: String = "android-${java.util.UUID.randomUUID()}"

    /**
     * 提交并驱动一个云端任务，阻塞直至完成/失败/超时，返回最终答复文本。
     * 提交失败且属网络问题时：入发件箱并抛出 [CloudBrainQueuedOffline]。
     */
    suspend fun runTask(
        task: String,
        context: String,
        allowedTools: List<String> = CloudBrainActionExecutor.ALLOWED_TOOLS.toList(),
        source: String,
    ): String {
        val settings = XRepo.cloud.settings()
        if (!settings.brainReady()) {
            throw IllegalStateException("cloud brain not configured")
        }
        val client = CloudBrainClient(settings.workerUrl, settings.brainSecret)
        val submit = CloudTaskSubmit(
            deviceId = deviceId,
            task = task,
            context = context,
            allowedTools = allowedTools,
            tools = CloudBrainActionExecutor.toolSpecs(allowedTools),
        )
        val initial = try {
            client.submitTask(submit)
        } catch (io: IOException) {
            // 断网：入发件箱，等网络恢复后由 dispatcher 重放
            XRepo.cloudOutbox.add(
                CloudOutboxType.SUBMIT_TASK,
                json.encodeToString(CloudTaskSubmit.serializer(), submit),
            )
            Logger.w(LOG_TAG, "submit offline -> outbox (source=$source)")
            throw CloudBrainQueuedOffline()
        }
        Logger.i(LOG_TAG, "session started id=${initial.sessionId} source=$source")
        return runSession(client, initial)
    }

    /** 驱动会话直至终态；返回最终答复（失败时返回错误摘要）。 */
    suspend fun runSession(
        client: CloudBrainClient,
        initial: CloudSessionSnapshot,
    ): String {
        var snapshot = initial
        val sessionId = snapshot.sessionId
        val executed = HashSet<String>()
        val deadline = System.currentTimeMillis() + SESSION_TIMEOUT_MS
        var pollRetry = 0
        while (!snapshot.isTerminalStatus()) {
            if (System.currentTimeMillis() > deadline) {
                runCatching { client.cancel(sessionId) }
                Logger.w(LOG_TAG, "session $sessionId timeout")
                return "cloud session timeout"
            }
            val pending = snapshot.pendingActions.filter {
                it.id !in executed && CloudBrainActionExecutor.isAllowed(it.tool)
            }
            snapshot = if (pending.isNotEmpty()) {
                val results = pending.map { action ->
                    executed += action.id
                    CloudBrainActionExecutor.execute(action)
                }
                try {
                    client.postResults(sessionId, CloudResultsPost(results))
                } catch (io: IOException) {
                    // 结果回传断网：稍后整体重试（executed 集合保证幂等）
                    delay(backoffMs(pollRetry++))
                    continue
                }
            } else {
                try {
                    client.poll(sessionId, snapshot.cursor)
                } catch (io: IOException) {
                    // 中途断网：退避重试，会话状态在服务器侧，恢复后继续
                    delay(backoffMs(pollRetry++))
                    continue
                }
            }
            pollRetry = 0
            snapshot.newEvents.forEach { event ->
                Logger.d(LOG_TAG, "session $sessionId [${event.kind}] ${event.text.take(120)}")
            }
        }
        val finalText = snapshot.reply
            ?: snapshot.error
            ?: ""
        Logger.i(LOG_TAG, "session $sessionId done status=${snapshot.status}")
        return finalText
    }

    /** 断网重试退避。 */
    private fun backoffMs(retry: Int): Long {
        val exp = POLL_RETRY_BASE_MS shl retry.coerceAtMost(5)
        return exp.coerceAtMost(POLL_RETRY_MAX_MS)
    }
}

/** 任务已入离线发件箱（非错误：稍后自动重放）。 */
class CloudBrainQueuedOffline : Exception("queued offline, will retry when network returns")
