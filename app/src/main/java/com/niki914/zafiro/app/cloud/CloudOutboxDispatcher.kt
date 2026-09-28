package com.niki914.zafiro.app.cloud

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.niki914.logging.Logger
import com.niki914.zafiro.repo.CloudOutboxType
import com.niki914.zafiro.repo.XRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.IOException

/**
 * 发件箱调度器：
 *  - ConnectivityManager 回调：网络恢复瞬间触发一次全量重放；
 *  - 周期扫描（90s）兜底：回调丢失 / 进程重启后仍能续传；
 *  - 重放失败计数，超过上限自动丢弃（防永久坏条目堵队列）。
 */
object CloudOutboxDispatcher {

    private const val LOG_TAG = "niki914_nexus_CloudOutboxDispatcher"
    private const val SWEEP_INTERVAL_MS = 90_000L

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    private var scope: CoroutineScope? = null
    private var sweepJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var flushing = false

    private val _pendingCount = MutableStateFlow(0)
    val pendingCount: StateFlow<Int> = _pendingCount

    private val _lastDeliveredAtMs = MutableStateFlow(0L)
    val lastDeliveredAtMs: StateFlow<Long> = _lastDeliveredAtMs

    fun init(context: Context, applicationScope: CoroutineScope) {
        if (scope != null) return
        scope = applicationScope

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm != null && networkCallback == null) {
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Logger.i(LOG_TAG, "network available -> flush outbox")
                    applicationScope.launch { flush() }
                }
            }
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            runCatching { cm.registerNetworkCallback(request, callback) }
                .onSuccess { networkCallback = callback }
                .onFailure { Logger.w(LOG_TAG, "registerNetworkCallback failed ${it.message}") }
        }

        sweepJob = applicationScope.launch {
            while (true) {
                delay(SWEEP_INTERVAL_MS)
                refreshCount()
                if (_pendingCount.value > 0) {
                    flush()
                }
            }
        }

        applicationScope.launch { refreshCount() }
        Logger.i(LOG_TAG, "init ok")
    }

    suspend fun refreshCount() {
        _pendingCount.value = runCatching { XRepo.cloudOutbox.count() }.getOrDefault(0)
    }

    /**
     * 按序重放发件箱；返回成功投递条数。
     * 逐条处理：成功即删；网络类失败立即中止本轮（保持顺序）；其他失败记 attempts 继续下一条。
     */
    suspend fun flush(): Int {
        if (flushing) return 0
        flushing = true
        try {
            val settings = XRepo.cloud.settings()
            if (!settings.brainReady()) return 0
            val client = CloudBrainClient(settings.workerUrl, settings.brainSecret)
            var delivered = 0
            val dropped = XRepo.cloudOutbox.dropExhausted()
            if (dropped > 0) {
                Logger.w(LOG_TAG, "dropped $dropped exhausted outbox items")
            }
            for (item in XRepo.cloudOutbox.pending()) {
                try {
                    when (item.type) {
                        CloudOutboxType.SUBMIT_TASK -> {
                            val submit = json.decodeFromString(
                                CloudTaskSubmit.serializer(), item.payloadJson,
                            )
                            val snapshot = client.submitTask(submit)
                            XRepo.cloudOutbox.remove(item.id)
                            delivered++
                            _lastDeliveredAtMs.value = System.currentTimeMillis()
                            // 出队成功但会话未完：异步接管后续驱动，不阻塞队列
                            if (!snapshot.isTerminalStatus()) {
                                scope?.launch {
                                    runCatching { CloudBrainManager.runSession(client, snapshot) }
                                }
                            }
                        }

                        CloudOutboxType.POST_RESULTS -> {
                            val envelope = json.decodeFromString(
                                CloudResultsEnvelope.serializer(), item.payloadJson,
                            )
                            client.postResults(envelope.sessionId, CloudResultsPost(envelope.results))
                            XRepo.cloudOutbox.remove(item.id)
                            delivered++
                            _lastDeliveredAtMs.value = System.currentTimeMillis()
                        }
                    }
                } catch (io: IOException) {
                    // 无网/对端不可达：保持顺序，停止本轮，等下一次触发
                    XRepo.cloudOutbox.markAttempted(item.id, io.message ?: "network")
                    Logger.w(LOG_TAG, "flush paused by network: ${io.message}")
                    break
                } catch (e: Exception) {
                    // 协议/服务端错误：计数后跳过该条
                    XRepo.cloudOutbox.markAttempted(item.id, e.message ?: e.javaClass.simpleName)
                    Logger.w(LOG_TAG, "flush item ${item.id} failed: ${e.message}")
                }
            }
            refreshCount()
            if (delivered > 0) {
                Logger.i(LOG_TAG, "flush delivered=$delivered")
            }
            return delivered
        } finally {
            flushing = false
        }
    }
}
