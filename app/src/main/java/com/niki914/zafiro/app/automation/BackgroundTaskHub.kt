package com.niki914.zafiro.app.automation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.niki914.logging.Logger
import com.niki914.okia.message.ContentBlock
import com.niki914.zafiro.app.MainActivity
import com.niki914.zafiro.app.R
import com.niki914.zafiro.chat.LLMController
import com.niki914.zafiro.chat.LlmStreamEvent
import com.niki914.zafiro.repo.BackgroundTaskRecord
import com.niki914.zafiro.repo.BackgroundTaskStatus
import com.niki914.zafiro.repo.XRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

/**
 * 后台任务中枢（Feature: Asynchronous Background Execution）。
 *
 * 职责：把用户发起的回合从 UI 生命周期解耦——LLMController.stream 的收集
 * 跑在本 Hub 的应用级 scope（ZafiroTaskService FGS 保活），ViewModel 只是
 * 事件观察者；聊天页销毁/退到桌面任务继续执行，结束后发完成通知。
 *
 * 事件转发采用 MutableSharedFlow（replay=0 + 64 缓冲）：无订阅者时 emit
 * 不阻塞（UI 不在也能跑完回合）；VM 重挂后只看到后续事件（当前段 TextDelta
 * 携带全量坐标，UI 自然续上）。
 *
 * 台账持久化：提交记 RUNNING，终态记 COMPLETED/FAILED/CANCELLED；进程死亡
 * 遗留的 RUNNING 在 init 时标记 INTERRUPTED。
 */
object BackgroundTaskHub {

    private const val LOG_TAG = "niki914_nexus_BackgroundTaskHub"

    const val CHANNEL_RUNNING = "background_task_running"
    const val CHANNEL_RESULTS = "background_task_results"
    private const val RESULT_NOTIFICATION_ID_BASE = 3000
    private const val FGS_STOP_DELAY_MS = 8_000L
    private const val SUBSCRIBER_WAIT_MS = 3_000L

    private var appContext: Context? = null
    private var scope: CoroutineScope? = null

    private val resultNotificationId = AtomicInteger(RESULT_NOTIFICATION_ID_BASE)

    /** 当前运行中的任务（进程内单任务：LLMController 单回合契约）。 */
    private val _runningTask = MutableStateFlow<RunningTask?>(null)
    val runningTask: StateFlow<RunningTask?> = _runningTask

    data class RunningTask(
        val id: String,
        val conversationId: String,
        val query: String,
        val documentNames: List<String>,
        val startedAtMs: Long,
        val events: MutableSharedFlow<LlmStreamEvent>,
        val job: Job,
    )

    // ------------------------------------------------------------- lifecycle

    fun init(context: Context, applicationScope: CoroutineScope) {
        if (appContext != null) return
        appContext = context.applicationContext
        scope = applicationScope
        ensureChannels(appContext!!)
        applicationScope.launch {
            try {
                // 进程死亡遗留：上一进程的 RUNNING 全部标记中断
                val stale = XRepo.backgroundTasks.list()
                    .filter { it.status == BackgroundTaskStatus.RUNNING }
                    .map { it.id }
                XRepo.backgroundTasks.markInterrupted(stale)
            } catch (t: Throwable) {
                Logger.w(LOG_TAG, "init stale-mark failed reason=${t.message}")
            }
        }
        Logger.i(LOG_TAG, "init ok")
    }

    fun hasRunningTasks(): Boolean = _runningTask.value != null

    fun onServiceStarted() = Unit

    fun onServiceStopping() = Unit

    fun onServiceStopped() {
        // FGS 意外销毁（系统回收）时同步运行态，避免状态漂移
        if (_runningTask.value == null) return
        Logger.w(LOG_TAG, "FGS stopped while task running")
    }

    // ---------------------------------------------------------------- submit

    /**
     * Prepared task handle：两段式提交（prepare → UI 先订阅 → start）。
     * SharedFlow replay=0 语义要求订阅者在回合开始前就位，否则首批事件
     * （RoundStarted / 早期 TextDelta）会被派发时序静默吞掉。
     */
    class PreparedTask internal constructor(
        val id: String,
        val conversationId: String,
        val query: String,
        val documentNames: List<String>,
        internal val events: MutableSharedFlow<LlmStreamEvent>,
        internal val record: BackgroundTaskRecord,
    )

    /**
     * 阶段一：创建任务句柄（不启动任何协程）。调用方先经 [observe] 建立订阅，
     * 再调用 [start] 启动回合。
     */
    fun prepare(
        query: String,
        images: List<ContentBlock.Image>,
        documentNames: List<String>,
        conversationId: String,
        imagePaths: List<String>,
    ): PreparedTask {
        val taskId = "task-${System.currentTimeMillis()}"
        val events = MutableSharedFlow<LlmStreamEvent>(
            replay = 0,
            extraBufferCapacity = 64,
        )
        val record = BackgroundTaskRecord(
            id = taskId,
            conversationId = conversationId,
            query = query,
            imagePaths = imagePaths,
            documentNames = documentNames,
            status = BackgroundTaskStatus.RUNNING,
            createdAt = System.currentTimeMillis(),
        )
        return PreparedTask(
            id = taskId,
            conversationId = conversationId,
            query = query,
            documentNames = documentNames,
            events = events,
            record = record,
        )
    }

    /** 任务事件流（订阅须发生在 [start] 之前）。 */
    fun observe(task: PreparedTask): SharedFlow<LlmStreamEvent> = task.events

    /**
     * 阶段二：启动回合。[runner] 由调用方注入（生产 = LLMController.stream，
     * 测试 = ViewModel seam 的 fake），保持既有可测性。
     * 内部先等首个订阅者就位（最多 [SUBSCRIBER_WAIT_MS]，超时容忍无观察者
     * 场景，任务照跑），随后启动回合——本方法不等回合终态，任务生命周期与
     * 调用方 scope 解耦。
     */
    fun start(
        task: PreparedTask,
        images: List<ContentBlock.Image>,
        runner: suspend (String, List<ContentBlock.Image>) -> Flow<LlmStreamEvent>,
    ) {
        val context = appContext
        // scope 未 init（单测环境）时回落 Main.immediate：测试把 Main 设为
        // UnconfinedTestDispatcher，虚拟时间确定性得以保留；生产由 App.init 设置
        val taskScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val registered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val job = taskScope.launch {
            // 门闩一：注册先行（瞬间失败的回合不得在注册前跑完 finalize）；
            // 门闩二：订阅者就位（首帧事件不丢）
            registered.await()
            withTimeoutOrNull(SUBSCRIBER_WAIT_MS) {
                task.events.subscriptionCount.first { it > 0 }
            }
            var lastText = ""
            var fallbackCount = 0
            var errorMessage: String? = null
            var sawTerminal = false
            var status = BackgroundTaskStatus.COMPLETED
            try {
                // 任务级超时不用 withTimeout：虚拟时间测试环境会把远期 timer
                // 提前触发（advanceUntilIdle 直跳 30min）；回合内卡死由 okia 的
                // idleTimeout 兑底，用户也可手动停止
                runner(task.query, images).collect { event ->
                    when (event) {
                        is LlmStreamEvent.TextDelta -> lastText = event.fullText
                        is LlmStreamEvent.ModelSwitched -> fallbackCount += 1
                        is LlmStreamEvent.Error -> {
                            errorMessage = event.message ?: errorMessage
                            sawTerminal = true
                        }

                        is LlmStreamEvent.Completed -> sawTerminal = true
                        else -> Unit
                    }
                    task.events.emit(event)
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) {
                    finalize(task.record.copy(), lastText, null, BackgroundTaskStatus.CANCELLED, 0)
                    throw t
                }
                errorMessage = t.message?.trim()?.ifEmpty { null }
                status = BackgroundTaskStatus.FAILED
            }
            // 合成终态事件：UI 收集器靠 terminal 自停；流本身未发过
            // Completed/Error 时补发，保证观察者不悬挂。失败但无错误原文
            // （契约违例）时按 Completed 收束——与旧 VM 行为一致：无消息不渲染
            // 错误卡片（失败事实由台账 + 通知承载）
            if (!sawTerminal) {
                if (status == BackgroundTaskStatus.FAILED && errorMessage != null) {
                    task.events.emit(LlmStreamEvent.Error(message = errorMessage))
                } else {
                    task.events.emit(LlmStreamEvent.Completed)
                }
            }
            finalize(task.record, lastText, errorMessage, status, fallbackCount)
        }

        _runningTask.value = RunningTask(
            id = task.id,
            conversationId = task.conversationId,
            query = task.query,
            documentNames = task.documentNames,
            startedAtMs = task.record.createdAt,
            events = task.events,
            job = job,
        )
        registered.complete(Unit)
        context?.let { ZafiroTaskService.start(it) }
        taskScope.launch {
            try {
                XRepo.backgroundTasks.upsert(task.record)
            } catch (t: Throwable) {
                Logger.w(LOG_TAG, "task record persist failed reason=${t.message}")
            }
        }
        Logger.i(LOG_TAG, "started task=${task.id} conversation=${task.conversationId}")
    }

    /** 观察任务事件流（UI 收集；任务与订阅者生命周期解耦）。 */
    fun events(taskId: String): SharedFlow<LlmStreamEvent>? {
        return _runningTask.value?.takeIf { it.id == taskId }?.events
    }

    /** 挂起等待任务终态（UI 收集器与任务本体解耦后的同步点）。 */
    suspend fun awaitTask(taskId: String) {
        val task = _runningTask.value?.takeIf { it.id == taskId } ?: return
        task.job.join()
    }

    fun activeTaskFor(conversationId: String?): RunningTask? {
        val running = _runningTask.value ?: return null
        return if (conversationId == null || running.conversationId == conversationId) {
            running
        } else {
            null
        }
    }

    /** 用户主动停止。取消即 CANCELLED 终态（不通知）。 */
    fun cancel(taskId: String) {
        val running = _runningTask.value?.takeIf { it.id == taskId } ?: return
        Logger.i(LOG_TAG, "cancel requested task=$taskId")
        running.job.cancel()
    }

    /** 取消并等待回合终结（换会话/删会话前必须保证 okia 无活跃回合）。 */
    suspend fun cancelAndJoin(taskId: String) {
        val task = _runningTask.value?.takeIf { it.id == taskId } ?: return
        Logger.i(LOG_TAG, "cancel and join task=$taskId")
        task.job.cancel()
        task.job.join()
    }

    // -------------------------------------------------------------- terminal

    private suspend fun finalize(
        record: BackgroundTaskRecord,
        lastText: String,
        errorMessage: String?,
        status: BackgroundTaskStatus,
        fallbackCount: Int,
    ) {
        if (_runningTask.value?.id == record.id) {
            _runningTask.value = null
        }
        val finishedAt = System.currentTimeMillis()
        val summary = summaryOf(lastText, errorMessage)
        val finished = record.copy(
            status = status,
            finishedAt = finishedAt,
            resultPreview = summary,
            error = errorMessage.orEmpty(),
            modelFallbacks = fallbackCount,
        )
        try {
            XRepo.backgroundTasks.upsert(finished)
        } catch (t: Throwable) {
            Logger.w(LOG_TAG, "final record persist failed reason=${t.message}")
        }
        if (status != BackgroundTaskStatus.CANCELLED) {
            postResultNotification(record, status, summary)
        }
        scheduleServiceStop()
        Logger.i(
            LOG_TAG,
            "task finalized id=${record.id} status=$status fallbacks=$fallbackCount"
        )
    }

    private fun summaryOf(lastText: String, errorMessage: String?): String {
        if (errorMessage != null) return errorMessage.take(300)
        val line = lastText.lines().lastOrNull { it.isNotBlank() }
        return (line ?: lastText.takeLast(200)).take(300).ifBlank { "done" }
    }

    private fun scheduleServiceStop() {
        val context = appContext ?: return
        scope?.launch {
            delay(FGS_STOP_DELAY_MS)
            if (!hasRunningTasks()) {
                ZafiroTaskService.stop(context)
            }
        }
    }

    // ---------------------------------------------------------- notification

    fun postResultNotification(
        record: BackgroundTaskRecord,
        status: BackgroundTaskStatus,
        summary: String,
    ) = postResultNotification(
        title = notificationTitle(status),
        body = summary,
    )

    private fun notificationTitle(status: BackgroundTaskStatus): String {
        val context = appContext ?: return "Zafiro"
        return when (status) {
            BackgroundTaskStatus.COMPLETED -> context.getString(R.string.task_done_title)
            BackgroundTaskStatus.FAILED -> context.getString(R.string.task_failed_title)
            BackgroundTaskStatus.INTERRUPTED -> context.getString(R.string.task_interrupted_title)
            else -> context.getString(R.string.task_done_title)
        }
    }

    fun postResultNotification(title: String, body: String) {
        val context = appContext ?: return
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        val intent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(intent)
            .build()
        try {
            nm.notify(resultNotificationId.getAndIncrement(), notification)
        } catch (security: SecurityException) {
            Logger.w(LOG_TAG, "result notify denied: ${security.message}")
        }
    }

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RUNNING,
                context.getString(R.string.task_channel_running),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RESULTS,
                context.getString(R.string.task_channel_results),
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        )
    }

    fun buildForegroundNotification(context: Context): Notification {
        ensureChannels(context)
        val intent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_RUNNING)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(context.getString(R.string.task_fgs_title))
            .setContentText(context.getString(R.string.task_fgs_text))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(intent)
            .build()
    }
}
