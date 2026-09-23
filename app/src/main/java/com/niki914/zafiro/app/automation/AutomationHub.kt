package com.niki914.zafiro.app.automation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.niki914.logging.Logger
import com.niki914.zafiro.app.MainActivity
import com.niki914.zafiro.app.R
import com.niki914.zafiro.chat.ActiveTurnStore
import com.niki914.zafiro.chat.LLMController
import com.niki914.zafiro.chat.LlmStreamEvent
import com.niki914.zafiro.repo.AutomationTrigger
import com.niki914.zafiro.repo.AutomationTriggerAction
import com.niki914.zafiro.repo.AutomationTriggerSource
import com.niki914.zafiro.repo.XRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** 一次命中的触发事件（触发器 + 事件上下文）。 */
data class TriggerHit(
    val trigger: AutomationTrigger,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val filePath: String = "",
)

/**
 * 主动自动化中枢：
 * 事件源（NotificationListener / DownloadObserver）→ 匹配触发器 → 队列 →
 * AGENT（唤醒 LLMController.stream）或 ALERT（高优先级系统通知）。
 *
 * 单例；进程内存活，UI 通过 StateFlow 观察状态。
 */
object AutomationHub {

    private const val LOG_TAG = "niki914_nexus_AutomationHub"

    const val CHANNEL_LISTENER = "automation_listener"
    const val CHANNEL_ALERTS = "automation_alerts"
    const val CHANNEL_RESULTS = "automation_results"
    private const val FGS_NOTIFICATION_ID = 1002
    private const val ALERT_NOTIFICATION_ID_BASE = 2000
    private const val RESULT_NOTIFICATION_ID = 1999
    private const val MAX_LOG_ENTRIES = 20
    private const val AGENT_TURN_TIMEOUT_MS = 5 * 60 * 1000L
    private const val BUSY_WAIT_ATTEMPTS = 15
    private const val BUSY_WAIT_INTERVAL_MS = 4_000L

    private var appContext: Context? = null
    private var scope: CoroutineScope? = null
    private var consumerJob: Job? = null
    private var downloadObserver: DownloadObserver? = null

    private val queue = Channel<TriggerHit>(Channel.UNLIMITED)
    private val lastFiredAtMs = HashMap<String, Long>()
    private var alertNotificationId = ALERT_NOTIFICATION_ID_BASE

    private val _listenerConnected = MutableStateFlow(false)
    val listenerConnected: StateFlow<Boolean> = _listenerConnected

    private val _serviceRunning = MutableStateFlow(false)
    val serviceRunning: StateFlow<Boolean> = _serviceRunning

    private val _armedTriggerCount = MutableStateFlow(0)
    val armedTriggerCount: StateFlow<Int> = _armedTriggerCount

    private val _recentActivity = MutableStateFlow<List<String>>(emptyList())
    val recentActivity: StateFlow<List<String>> = _recentActivity

    private val _triggers = MutableStateFlow<List<AutomationTrigger>>(emptyList())

    // ---------------------------------------------------------------- init

    fun init(context: Context, applicationScope: CoroutineScope) {
        if (appContext != null) return
        appContext = context.applicationContext
        scope = applicationScope
        ensureChannels(appContext!!)

        consumerJob = applicationScope.launch {
            for (hit in queue) {
                processHit(hit)
            }
        }

        applicationScope.launch {
            reloadTriggers()
        }
        Logger.i(LOG_TAG, "init ok")
    }

    /** 重新加载触发器（UI 增删改后调用）；同时维护 DownloadObserver。 */
    fun reloadTriggers() {
        val ctx = appContext ?: return
        val sc = scope ?: return
        sc.launch {
            try {
                val triggers = XRepo.automation.list()
                _triggers.value = triggers
                _armedTriggerCount.value = triggers.count { it.enabled }
                syncDownloadObserver(ctx, triggers)
                Logger.d(
                    LOG_TAG,
                    "reloadTriggers total=${triggers.size} armed=${_armedTriggerCount.value}"
                )
            } catch (t: Throwable) {
                Logger.w(LOG_TAG, "reloadTriggers failed reason=${t.message}")
            }
        }
    }

    private fun syncDownloadObserver(context: Context, triggers: List<AutomationTrigger>) {
        val needFileWatch = triggers.any {
            it.enabled && it.source == AutomationTriggerSource.FILE_DOWNLOAD
        }
        if (needFileWatch && downloadObserver == null) {
            downloadObserver = DownloadObserver(context) { path ->
                onFileCreated(path)
            }.also { it.start() }
            Logger.i(LOG_TAG, "DownloadObserver started")
        } else if (!needFileWatch && downloadObserver != null) {
            downloadObserver?.stop()
            downloadObserver = null
            Logger.i(LOG_TAG, "DownloadObserver stopped")
        }
    }

    // ------------------------------------------------------- event sources

    /** NotificationListenerService 回调入口（系统主线程，必须快速返回）。 */
    fun onNotificationPosted(sbn: android.service.notification.StatusBarNotification) {
        val context = appContext ?: return
        if (!_serviceRunning.value) return
        if (sbn.packageName == context.packageName) return // 防自触发死循环
        if (sbn.isOngoing) return

        val extras = sbn.notification.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)
            ?.toString().orEmpty()
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)
            ?.toString().orEmpty()
        val bigText = extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?.toString().orEmpty()
        val body = if (bigText.length > text.length) bigText else text
        if (title.isBlank() && body.isBlank()) return

        val trigger = matchNotificationTrigger(sbn.packageName, title, body) ?: return
        val hit = TriggerHit(
            trigger = trigger,
            packageName = sbn.packageName,
            appLabel = appLabelOf(context, sbn.packageName),
            title = title,
            text = body,
        )
        enqueue(hit)
    }

    fun onListenerConnected() {
        _listenerConnected.value = true
        appendLog("[listener] notification access connected")
        Logger.i(LOG_TAG, "listener connected")
    }

    fun onListenerDisconnected() {
        _listenerConnected.value = false
        appendLog("[listener] notification access disconnected")
        Logger.i(LOG_TAG, "listener disconnected")
    }

    /** DownloadObserver 回调：Download 目录出现新文件。 */
    fun onFileCreated(filePath: String) {
        if (!_serviceRunning.value) return
        val file = java.io.File(filePath)
        if (!file.isFile || file.name.startsWith(".")) return
        val trigger = _triggers.value.firstOrNull {
            it.enabled &&
                    it.source == AutomationTriggerSource.FILE_DOWNLOAD &&
                    matchesKeywords(it, file.name)
        } ?: return
        enqueue(
            TriggerHit(
                trigger = trigger,
                packageName = "",
                appLabel = "Download",
                title = file.name,
                text = filePath,
                filePath = filePath,
            )
        )
    }

    fun onServiceStarted() {
        _serviceRunning.value = true
        reloadTriggers()
        appendLog("[service] proactive mode ON")
    }

    fun onServiceStopped() {
        _serviceRunning.value = false
        appendLog("[service] proactive mode OFF")
    }

    // ------------------------------------------------------------- matching

    private fun matchNotificationTrigger(
        packageName: String,
        title: String,
        body: String,
    ): AutomationTrigger? {
        return _triggers.value.firstOrNull { trigger ->
            trigger.enabled &&
                    trigger.source == AutomationTriggerSource.NOTIFICATION &&
                    (trigger.appPackage.isBlank() || trigger.appPackage == packageName) &&
                    (trigger.senderContains.isBlank() ||
                            title.contains(trigger.senderContains, ignoreCase = true)) &&
                    matchesKeywords(trigger, "$title\n$body")
        }
    }

    private fun matchesKeywords(trigger: AutomationTrigger, haystack: String): Boolean {
        if (trigger.keywords.isEmpty()) return true
        return trigger.keywords.any { keyword ->
            haystack.contains(keyword, ignoreCase = true)
        }
    }

    private fun enqueue(hit: TriggerHit) {
        val now = System.currentTimeMillis()
        val cooldownMs = hit.trigger.cooldownSeconds * 1000L
        val last = lastFiredAtMs[hit.trigger.id] ?: 0L
        if (now - last < cooldownMs) {
            Logger.d(
                LOG_TAG,
                "cooldown active trigger=${hit.trigger.id} remainingMs=${cooldownMs - (now - last)}"
            )
            return
        }
        lastFiredAtMs[hit.trigger.id] = now
        appendLog("[hit] ${hit.trigger.name} <- ${hit.appLabel.ifBlank { hit.packageName }}")
        val accepted = queue.trySend(hit).isSuccess
        if (!accepted) {
            Logger.w(LOG_TAG, "queue full, dropped trigger=${hit.trigger.id}")
        }
    }

    // ------------------------------------------------------------ execution

    private suspend fun processHit(hit: TriggerHit) {
        try {
            when (hit.trigger.action) {
                AutomationTriggerAction.ALERT -> fireAlert(hit)
                AutomationTriggerAction.AGENT -> runAgent(hit)
            }
        } catch (t: Throwable) {
            Logger.w(LOG_TAG, "processHit failed trigger=${hit.trigger.id} reason=${t.message}")
            appendLog("[error] ${hit.trigger.name}: ${t.message ?: "unknown"}")
        }
    }

    /** ALERT：不唤醒 LLM，直接高优先级系统提醒。 */
    private fun fireAlert(hit: TriggerHit) {
        val context = appContext ?: return
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return

        val intent = PendingIntent.getActivity(
            context,
            alertNotificationId,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(
                context.getString(R.string.automation_alert_title, hit.trigger.name)
            )
            .setContentText("${hit.appLabel.ifBlank { hit.packageName }} · ${hit.title}")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("${hit.appLabel.ifBlank { hit.packageName }} · ${hit.title}\n${hit.text}")
            )
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(Notification.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(intent)
            .build()
        try {
            nm.notify(alertNotificationId, notification)
            alertNotificationId += 1
        } catch (security: SecurityException) {
            Logger.w(LOG_TAG, "alert notify denied: ${security.message}")
            return
        }
        appendLog("[alert] ${hit.trigger.name}")
        Logger.i(LOG_TAG, "alert fired trigger=${hit.trigger.id}")
    }

    /** AGENT：唤醒 LLM Agent 自主执行。 */
    private suspend fun runAgent(hit: TriggerHit) {
        if (!waitUntilAgentFree()) {
            appendLog("[skip] ${hit.trigger.name}: agent busy")
            return
        }
        val prompt = buildAgentPrompt(hit)
        appendLog("[agent] ${hit.trigger.name} -> working…")
        var lastText = ""
        var errorMessage: String? = null
        try {
            withTimeout(AGENT_TURN_TIMEOUT_MS) {
                LLMController.stream(prompt).collect { event ->
                    when (event) {
                        is LlmStreamEvent.TextDelta -> lastText = event.fullText
                        is LlmStreamEvent.Error -> errorMessage = event.message
                        else -> Unit
                    }
                }
            }
        } catch (t: Throwable) {
            errorMessage = t.message ?: t.javaClass.simpleName
        }
        if (errorMessage != null) {
            appendLog("[agent] ${hit.trigger.name}: failed (${errorMessage?.take(80)})")
            postResultNotification(
                title = hit.appLabel.ifBlank { hit.trigger.name },
                body = errorMessage ?: "automation turn failed",
            )
            return
        }
        val summary = lastText.lines()
            .lastOrNull { it.isNotBlank() }
            ?: lastText.takeLast(200).ifBlank { "done" }
        appendLog("[agent] ${hit.trigger.name}: ${summary.take(80)}")
        postResultNotification(
            title = hit.appLabel.ifBlank { hit.trigger.name },
            body = summary.take(300),
        )
        Logger.i(LOG_TAG, "agent turn done trigger=${hit.trigger.id}")
    }

    /** Agent 忙碌时等待；返回 false 表示等待超时放弃。 */
    private suspend fun waitUntilAgentFree(): Boolean {
        repeat(BUSY_WAIT_ATTEMPTS) {
            if (!LLMController.keepScreenOn.value && !ActiveTurnStore.hasActiveTurn()) {
                return true
            }
            delay(BUSY_WAIT_INTERVAL_MS)
        }
        return false
    }

    private fun buildAgentPrompt(hit: TriggerHit): String {
        val contextBlock = when (hit.trigger.source) {
            AutomationTriggerSource.NOTIFICATION ->
                buildString {
                    appendLine("source: system notification")
                    appendLine("app: ${hit.packageName} (${hit.appLabel})")
                    appendLine("title: ${hit.title}")
                    append("text: ${hit.text}")
                }

            AutomationTriggerSource.FILE_DOWNLOAD ->
                buildString {
                    appendLine("source: new file in Download folder")
                    append("file: ${hit.filePath}")
                }
        }
        val instruction = hit.trigger.prompt.ifBlank {
            "Analyze the event and take any obviously helpful action."
        }
        return buildString {
            appendLine("[PROACTIVE_AUTOMATION] trigger=\"${hit.trigger.name}\"")
            appendLine(contextBlock)
            appendLine()
            appendLine("user instruction:")
            appendLine(instruction)
            appendLine()
            appendLine(
                "Act autonomously with your tools (screen_operation_accessibility to open " +
                        "apps and operate UI, execute_python for files/data, notify to report, " +
                        "launch_app/open_uri to navigate). Stay minimal and safe; if the task " +
                        "cannot be completed, state why and stop."
            )
        }
    }

    private fun postResultNotification(title: String, body: String) {
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
            .setContentTitle(context.getString(R.string.automation_result_title, title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(intent)
            .build()
        try {
            nm.notify(RESULT_NOTIFICATION_ID, notification)
        } catch (security: SecurityException) {
            Logger.w(LOG_TAG, "result notify denied: ${security.message}")
        }
    }

    // ----------------------------------------------------------- status util

    fun isNotificationAccessGranted(context: Context): Boolean {
        return NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun notificationListenerSettingsIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

    fun batteryOptimizationIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(android.net.Uri.parse("package:${context.packageName}"))

    fun serviceStartIntent(context: Context): Intent =
        Intent(context, ZafiroAutomationService::class.java)

    // -------------------------------------------------------------- helpers

    private fun appLabelOf(context: Context, packageName: String): String {
        return try {
            val pm = context.packageManager
            pm.getApplicationLabel(
                pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            ).toString()
        } catch (t: Throwable) {
            packageName
        }
    }

    fun buildForegroundNotification(context: Context, armedCount: Int): Notification {
        ensureChannels(context)
        val intent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = if (armedCount > 0) {
            context.getString(R.string.automation_fgs_active_with_triggers, armedCount)
        } else {
            context.getString(R.string.automation_fgs_active_no_triggers)
        }
        return NotificationCompat.Builder(context, CHANNEL_LISTENER)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(context.getString(R.string.automation_fgs_title))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(intent)
            .build()
    }

    private fun ensureChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_LISTENER,
                context.getString(R.string.automation_channel_listener),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERTS,
                context.getString(R.string.automation_channel_alerts),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                enableVibration(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RESULTS,
                context.getString(R.string.automation_channel_results),
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        )
    }

    private fun appendLog(entry: String) {
        val stamped = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        val next = (_recentActivity.value + "$stamped $entry").takeLast(MAX_LOG_ENTRIES)
        _recentActivity.value = next
    }
}
