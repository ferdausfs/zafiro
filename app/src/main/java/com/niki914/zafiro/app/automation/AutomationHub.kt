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
import com.niki914.zafiro.repo.AutomationBatteryEvent
import com.niki914.zafiro.repo.AutomationLocationMode
import com.niki914.zafiro.repo.AutomationTrigger
import com.niki914.zafiro.repo.AutomationTriggerAction
import com.niki914.zafiro.repo.AutomationTriggerSource
import com.niki914.zafiro.repo.XRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
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
    /** v1.7.0：事件附加上下文（电池电量、触发时刻、坐标等）。 */
    val extra: String = "",
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
    /** C1：队列容量上限（DROP_OLDEST 满时丢最旧，保留最新事件）。 */
    private const val QUEUE_CAPACITY = 32
    // v1.7.0
    private const val TIME_TICK_INTERVAL_MS = 30_000L
    private const val FULL_BATTERY_PERCENT = 95

    // Phase 2（item 2）：时间触发器 occurrence 去重上限与扫描窗口钳制
    private const val TIME_FIRED_SET_LIMIT = 1024
    private const val MILLIS_PER_MINUTE = 60_000L
    private const val FRESHNESS_WINDOW_MIN = 31L // 与 TimeTriggerScheduler.FRESHNESS_MS 对齐

    private var appContext: Context? = null
    private var scope: CoroutineScope? = null
    private var consumerJob: Job? = null
    private var downloadObserver: DownloadObserver? = null
    // v1.7.0：电池/定时/地点事件源
    private var batteryReceiver: android.content.BroadcastReceiver? = null
    private var timeTickerJob: Job? = null
    private var locationListener: android.location.LocationListener? = null
    private var lastBatteryState: BatteryState? = null
    private val locationInsideState = HashMap<String, Boolean>()
    // v1.8.0：One UI 省电模式事件源
    private var powerSaveReceiver: android.content.BroadcastReceiver? = null
    private var lastPowerSaveState: Boolean? = null

    private data class BatteryState(val level: Int, val charging: Boolean)

    /**
     * C1：有界队列（容量 [QUEUE_CAPACITY]）+ 明确丢弃策略 DROP_OLDEST。
     * 此前 Channel.UNLIMITED：consumer 单协程串行消费（AGENT 事件一次可占
     * 1-6 分钟：waitUntilAgentFree 最多 60s + withTimeout 5min），触发风暴下
     * 队列无界增长直到 OOM。DROP_OLDEST 保证最新事件优先存活 —— 旧事件的
     * 上下文（如电池电量、通知内容）在队头积压过久已失效，丢旧合理。
     */
    private val queue = Channel<TriggerHit>(QUEUE_CAPACITY, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val lastFiredAtMs = HashMap<String, Long>()
    private var alertNotificationId = ALERT_NOTIFICATION_ID_BASE

    /** Phase 2：已入队的时间触发器发生时刻（"triggerId:occMin"），ticker/闹钟/Worker 三路径共用去重。 */
    private val firedTimeOccurrences =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

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
        val appCtx = context.applicationContext
        appContext = appCtx
        scope = applicationScope
        // B：!! → 局部 val（避免对可变字段二次解引用）
        ensureChannels(appCtx)

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
        val sc = scope ?: return
        sc.launch {
            try {
                reloadTriggersNow()
            } catch (t: Throwable) {
                Logger.w(LOG_TAG, "reloadTriggers failed reason=${t.message}")
            }
        }
    }

    /** 同步加载触发器并维护各事件源（挂起；供 [onServiceStarted] armed 前调用）。 */
    private suspend fun reloadTriggersNow() {
        val ctx = appContext ?: return
        val triggers = XRepo.automation.list()
        _triggers.value = triggers
        _armedTriggerCount.value = triggers.count { it.enabled }
        syncDownloadObserver(ctx, triggers)
        syncBatteryReceiver(ctx, triggers)
        syncPowerSaveReceiver(ctx, triggers)
        syncTimeTicker(triggers)
        syncLocationWatch(ctx, triggers)
        // Phase 2（item 2）：触发器变化后重排下一发精确闹钟
        TimeTriggerScheduler.rearm(ctx, triggers)
        Logger.d(
            LOG_TAG,
            "reloadTriggers total=${triggers.size} armed=${_armedTriggerCount.value}"
        )
    }

    private fun syncDownloadObserver(context: Context, triggers: List<AutomationTrigger>) {
        val needFileWatch = triggers.any {
            it.enabled && it.source == AutomationTriggerSource.FILE_DOWNLOAD
        }
        if (needFileWatch && downloadObserver == null) {
            val observer = DownloadObserver(context) { path ->
                onFileCreated(path)
            }
            // C2：start() 返回实际结果 —— Download 目录缺失时不再让 Hub 持有
            // 一个从未真正 watch 的 observer（此前会一直 believed-running，
            // 且目录恢复后也不会重试）；置 null 让下次 reloadTriggers 重试。
            if (observer.start()) {
                downloadObserver = observer
                Logger.i(LOG_TAG, "DownloadObserver started")
            } else {
                Logger.w(LOG_TAG, "DownloadObserver start failed (download dir missing), will retry on next reload")
            }
        } else if (!needFileWatch && downloadObserver != null) {
            downloadObserver?.stop()
            downloadObserver = null
            Logger.i(LOG_TAG, "DownloadObserver stopped")
        }
    }

    // ------------------------------------------- v1.7.0 event source syncing

    /** 电池事件源：有 BATTERY 触发器时注册 ACTION_BATTERY_CHANGED 监听。 */
    private fun syncBatteryReceiver(context: Context, triggers: List<AutomationTrigger>) {
        val needBattery = triggers.any { it.enabled && it.source == AutomationTriggerSource.BATTERY }
        if (needBattery && batteryReceiver == null) {
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: android.content.Intent) {
                    if (intent.action != android.content.Intent.ACTION_BATTERY_CHANGED) return
                    val level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100)
                    val plugged = intent.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0)
                    if (level < 0 || scale <= 0) return
                    onBatteryChanged(level * 100 / scale, plugged != 0)
                }
            }
            runCatching {
                androidx.core.content.ContextCompat.registerReceiver(
                    context,
                    receiver,
                    android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED),
                    androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
                )
            }
            batteryReceiver = receiver
            Logger.i(LOG_TAG, "battery receiver registered")
        } else if (!needBattery && batteryReceiver != null) {
            // B：!! → ?.let。并发 reloadTriggers 可能已把 receiver 置空，
            // 判空后二次解引用会 NPE。
            batteryReceiver?.let { receiver ->
                runCatching { context.unregisterReceiver(receiver) }
            }
            batteryReceiver = null
            lastBatteryState = null
            Logger.i(LOG_TAG, "battery receiver unregistered")
        }
    }

    /** One UI 省电模式事件源（v1.8.0）：有 POWER_SAVE_* 触发器时注册系统广播。 */
    private fun syncPowerSaveReceiver(context: Context, triggers: List<AutomationTrigger>) {
        val needPowerSave = triggers.any {
            it.enabled && it.source == AutomationTriggerSource.BATTERY && (
                    it.batteryEvent == AutomationBatteryEvent.POWER_SAVE_ON ||
                            it.batteryEvent == AutomationBatteryEvent.POWER_SAVE_OFF)
        }
        if (needPowerSave && powerSaveReceiver == null) {
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: android.content.Intent) {
                    if (intent.action != android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED) return
                    val pm = ctx.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager ?: return
                    onPowerSaveChanged(pm.isPowerSaveMode)
                }
            }
            runCatching {
                androidx.core.content.ContextCompat.registerReceiver(
                    context,
                    receiver,
                    android.content.IntentFilter(android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
                    androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
                )
            }
            // sticky 初始化：只记录当前状态，不触发
            lastPowerSaveState = (context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager)?.isPowerSaveMode
            powerSaveReceiver = receiver
            Logger.i(LOG_TAG, "power-save receiver registered (initial=$lastPowerSaveState)")
        } else if (!needPowerSave && powerSaveReceiver != null) {
            // B：!! → ?.let（同 batteryReceiver，防并发 reload 竞态 NPE）
            powerSaveReceiver?.let { receiver ->
                runCatching { context.unregisterReceiver(receiver) }
            }
            powerSaveReceiver = null
            lastPowerSaveState = null
            Logger.i(LOG_TAG, "power-save receiver unregistered")
        }
    }

    /** 定时事件源：有 TIME 触发器时启动 30s 粒度的分钟匹配协程。 */
    private fun syncTimeTicker(triggers: List<AutomationTrigger>) {
        val needTime = triggers.any { it.enabled && it.source == AutomationTriggerSource.TIME }
        if (needTime && timeTickerJob == null) {
            timeTickerJob = scope?.launch {
                Logger.i(LOG_TAG, "time ticker started")
                while (isActive) {
                    delay(TIME_TICK_INTERVAL_MS)
                    runCatching { onTimeTick() }
                }
            }
        } else if (!needTime && timeTickerJob != null) {
            timeTickerJob?.cancel()
            timeTickerJob = null
            Logger.i(LOG_TAG, "time ticker stopped")
        }
    }

    /** 地点事件源：有 LOCATION 触发器且权限就绪时请求位置更新。 */
    private fun syncLocationWatch(context: Context, triggers: List<AutomationTrigger>) {
        val needLocation = triggers.any { it.enabled && it.source == AutomationTriggerSource.LOCATION }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
        val hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (needLocation && locationListener == null) {
            if (lm == null || !hasPermission) {
                appendLog("[location] waiting for fine-location permission")
                Logger.w(LOG_TAG, "location triggers armed but permission missing")
                return
            }
            val listener = android.location.LocationListener { location ->
                onLocationUpdate(location.latitude, location.longitude)
            }
            locationListener = listener
            runCatching {
                val providers = buildList {
                    if (lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)) {
                        add(android.location.LocationManager.GPS_PROVIDER)
                    }
                    if (lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)) {
                        add(android.location.LocationManager.NETWORK_PROVIDER)
                    }
                }
                if (providers.isEmpty()) {
                    appendLog("[location] no location provider enabled")
                    return
                }
                providers.forEach { provider ->
                    lm.requestLocationUpdates(provider, 60_000L, 30f, listener, context.mainLooper)
                }
                // 先用最近一次已知位置做一次即时判定
                lm.getLastKnownLocation(providers.first())?.let { last ->
                    onLocationUpdate(last.latitude, last.longitude)
                }
            }
            appendLog("[location] watching ${_triggers.value.count {
                it.enabled && it.source == AutomationTriggerSource.LOCATION
            }} trigger(s)")
            Logger.i(LOG_TAG, "location watch started")
        } else if (!needLocation && locationListener != null) {
            // B：!! → ?.let（防并发 reload 竞态 NPE）
            locationListener?.let { listener -> lm?.removeUpdates(listener) }
            locationListener = null
            locationInsideState.clear()
            Logger.i(LOG_TAG, "location watch stopped")
        }
    }

    // ------------------------------------------ v1.7.0 event entry points

    /** 电池状态变化入口（BroadcastReceiver 回调，主线程，快速返回）。 */
    fun onBatteryChanged(level: Int, charging: Boolean) {
        if (!_serviceRunning.value) return
        val previous = lastBatteryState
        lastBatteryState = BatteryState(level, charging)
        if (previous == null) return // 首个 sticky 广播只初始化状态，不触发

        val fired = _triggers.value.filter { trigger ->
            trigger.enabled && trigger.source == AutomationTriggerSource.BATTERY && when (trigger.batteryEvent) {
                AutomationBatteryEvent.LOW ->
                    !charging && level <= trigger.batteryLevel &&
                            !(previous.charging == false && previous.level <= trigger.batteryLevel)

                AutomationBatteryEvent.CHARGING ->
                    charging && !previous.charging

                AutomationBatteryEvent.FULL ->
                    charging && level >= FULL_BATTERY_PERCENT &&
                            !(previous.charging && previous.level >= FULL_BATTERY_PERCENT)

                AutomationBatteryEvent.OKAY ->
                    !charging && level > trigger.batteryLevel &&
                            previous.level <= trigger.batteryLevel && !previous.charging

                // v1.8.0：省电模式事件由 onPowerSaveChanged 处理，不走电量广播
                AutomationBatteryEvent.POWER_SAVE_ON, AutomationBatteryEvent.POWER_SAVE_OFF -> false
            }
        }
        fired.forEach { trigger ->
            enqueue(
                TriggerHit(
                    trigger = trigger,
                    packageName = "",
                    appLabel = "Battery",
                    title = "$level%",
                    text = if (charging) "charging" else "on battery",
                    extra = "level: $level%${if (charging) " (charging)" else ""}",
                )
            )
        }
    }

    /** One UI 省电模式翻转入口（v1.8.0，BroadcastReceiver 回调，主线程，快速返回）。 */
    fun onPowerSaveChanged(enabled: Boolean) {
        if (!_serviceRunning.value) return
        val previous = lastPowerSaveState
        lastPowerSaveState = enabled
        if (previous == null || previous == enabled) return // 首个广播只初始化状态

        val fired = _triggers.value.filter { trigger ->
            trigger.enabled && trigger.source == AutomationTriggerSource.BATTERY &&
                    ((enabled && trigger.batteryEvent == AutomationBatteryEvent.POWER_SAVE_ON) ||
                            (!enabled && trigger.batteryEvent == AutomationBatteryEvent.POWER_SAVE_OFF))
        }
        fired.forEach { trigger ->
            enqueue(
                TriggerHit(
                    trigger = trigger,
                    packageName = "",
                    appLabel = "PowerSave",
                    title = if (enabled) "power_save_on" else "power_save_off",
                    text = "One UI power saving mode ${if (enabled) "enabled" else "disabled"}",
                    extra = "power_saving: $enabled",
                )
            )
        }
    }

    /** 定时入口（30s 粒度协程回调）；Phase 2 起闹钟/Worker 兜底共用 fireDueTimeTriggers。 */
    fun onTimeTick() {
        val nowMin = System.currentTimeMillis() / MILLIS_PER_MINUTE
        fireDueTimeTriggers(fromMin = nowMin, toMin = nowMin)
    }

    /** 触发器快照（供 TimeTriggerScheduler 重排闹钟；只读）。 */
    fun triggersSnapshot(): List<AutomationTrigger> = _triggers.value

    /**
     * Phase 2（item 2）：时间触发器的统一判定入口 —— 进程内 30s ticker、
     * AlarmManager 精确闹钟、WorkManager 周期兑底三条路径都汇到这里。
     *
     * 扫描 [fromMin, toMin]（epoch 分钟，闭区间）内属于启用 TIME 触发器的发生
     * 时刻；"triggerId:occurrenceMin" 进程内去重（多路径同一分钟只入队一次）
     * + 既有冷却；服务未运行时直接返回（时间触发器是主动模式能力，语义不变）。
     * 分钟匹配口径与旧 onTimeTick 一致（本地时区 HH:mm，dayOfWeek 1=Mon..7=Sun）。
     *
     * @return 实际入队次数（诊断用）
     */
    fun fireDueTimeTriggers(fromMin: Long, toMin: Long): Int {
        if (!_serviceRunning.value) return 0
        if (toMin < fromMin) return 0
        val from = maxOf(fromMin, toMin - FRESHNESS_WINDOW_MIN) // 窗口钳制，防御异常入参
        val armed = _triggers.value.filter {
            it.enabled && it.source == AutomationTriggerSource.TIME &&
                    TimeTriggerScheduler.parseTimeOfDay(it.timeOfDay) != null
        }
        if (armed.isEmpty()) return 0
        var firedCount = 0
        for (occMin in from..toMin) {
            val cal = java.util.Calendar.getInstance()
            cal.timeInMillis = occMin * MILLIS_PER_MINUTE
            val hhmm = String.format(
                java.util.Locale.US, "%02d:%02d",
                cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE),
            )
            val dayOfWeek = (cal.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7 + 1 // 1=Mon..7=Sun
            for (trigger in armed) {
                if (trigger.timeOfDay != hhmm) continue
                if (!(trigger.daysOfWeek.isEmpty() || dayOfWeek in trigger.daysOfWeek)) continue
                val key = "${trigger.id}:$occMin"
                if (!firedTimeOccurrences.add(key)) continue // 本进程已触发过该发生时刻
                if (isCooldownActive(trigger)) {
                    // 冷却中：不入队；不记 occurrence（同分钟后续 tick 仍有机会补上）
                    firedTimeOccurrences.remove(key)
                    continue
                }
                if (firedTimeOccurrences.size > TIME_FIRED_SET_LIMIT) {
                    // 分钟粒度键（带 occMin 时间戳语义），整体清理的代价远小于维护 LRU
                    firedTimeOccurrences.clear()
                    firedTimeOccurrences.add(key)
                }
                enqueue(
                    TriggerHit(
                        trigger = trigger,
                        packageName = "",
                        appLabel = "Schedule",
                        title = hhmm,
                        text = "scheduled time reached",
                        extra = "time: $hhmm (day $dayOfWeek)",
                    )
                )
                firedCount++
            }
        }
        return firedCount
    }

    /** 位置更新入口；ENTER/EXIT 通过进/出状态翻转判定（带迟滞）。 */
    fun onLocationUpdate(latitude: Double, longitude: Double) {
        if (!_serviceRunning.value) return
        _triggers.value.filter { it.enabled && it.source == AutomationTriggerSource.LOCATION }
            .forEach { trigger ->
                val distance = distanceMeters(
                    lat1 = latitude, lon1 = longitude,
                    lat2 = trigger.latitude, lon2 = trigger.longitude,
                )
                val nowInside = distance <= trigger.radiusMeters
                val wasInside = locationInsideState[trigger.id]
                locationInsideState[trigger.id] = nowInside
                val crossed = when (trigger.locationMode) {
                    AutomationLocationMode.ENTER -> nowInside && wasInside == false
                    AutomationLocationMode.EXIT -> !nowInside && wasInside == true
                }
                if (crossed) {
                    enqueue(
                        TriggerHit(
                            trigger = trigger,
                            packageName = "",
                            appLabel = "Location",
                            title = trigger.locationMode.name.lowercase(),
                            text = "%.0fm from target".format(java.util.Locale.US, distance),
                            extra = "mode: ${trigger.locationMode.name.lowercase()} · " +
                                    "distance: %.0fm (radius ${trigger.radiusMeters}m) · " +
                                    "coords: %.4f, %.4f"
                                        .format(java.util.Locale.US, distance, latitude, longitude),
                        )
                    )
                }
            }
    }

    /** Haversine 距离（米）。 */
    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
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

    /** 活跃通知快照保留上限（进程内小存库，防长通知栏场景内存膨胀）。 */
    private const val SNAPSHOT_STORE_LIMIT = 64
    /** 重连回放窗口：只回放窗口内新到的通知，老通知视为已处理（防重启后重复触发）。 */
    private const val SNAPSHOT_REPLAY_WINDOW_MS = 5 * 60 * 1000L

    /** 重连回放期间已入队的通知 key（防同一快照在多次 connect 间重复入队）。 */
    private val replayedSnapshotKeys = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    @Volatile
    private var lastListenerSnapshot: List<NotificationSnapshot> = emptyList()

    /**
     * Phase 2（item 5）：监听服务重连/冷启时的活跃通知快照回放。
     *
     * 目的：监听器被系统断开/进程死亡期间新到的通知，只要此刻仍在通知栏，
     * 就在这里补一轮匹配 —— 不因服务重启而丢事件。策略：
     *  - 只回放 [SNAPSHOT_REPLAY_WINDOW_MS] 内 postTime 的通知（老通知视为
     *    上个进程已处理过，避免每次重启都重复触发）;
     *  - 通知 key 进程内去重（同一条通知多次 connect 只回放一次）；
     *  - 既有冷却照常生效（短时间 rebind 的重复投递由冷却挡住）。
     *
     * 诚实边界：断连窗口内「到达且已被用户划走」的通知系统不留底，无法回放。
     */
    fun onListenerSnapshot(snapshots: List<NotificationSnapshot>) {
        lastListenerSnapshot = snapshots.takeLast(SNAPSHOT_STORE_LIMIT)
        val ctx = appContext ?: return
        if (!_serviceRunning.value) return
        val cutoff = System.currentTimeMillis() - SNAPSHOT_REPLAY_WINDOW_MS
        var replayed = 0
        for (snap in snapshots) {
            if (snap.postTime < cutoff) continue
            if (!replayedSnapshotKeys.add(snap.key)) continue
            val trigger = matchNotificationTrigger(snap.packageName, snap.title, snap.text) ?: continue
            if (isCooldownActive(trigger)) {
                // 冷却挡住不入队时也别记 key：同一条通知在本窗口内的后续重试仍有机会
                replayedSnapshotKeys.remove(snap.key)
                continue
            }
            if (replayedSnapshotKeys.size > SNAPSHOT_STORE_LIMIT * 4) replayedSnapshotKeys.clear()
            enqueue(
                TriggerHit(
                    trigger = trigger,
                    packageName = snap.packageName,
                    appLabel = appLabelOf(ctx, snap.packageName),
                    title = snap.title,
                    text = snap.text,
                    extra = "replayed from listener snapshot (postTime=${snap.postTime})",
                )
            )
            replayed++
        }
        if (replayed > 0) {
            appendLog("[listener] replayed $replayed notification(s) after reconnect")
            Logger.i(LOG_TAG, "snapshot replay hits=$replayed snapshotSize=${snapshots.size}")
        }
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
        // C1：armed 时机后移 —— 等首次触发器加载完成再置 _serviceRunning=true。
        // 此前立刻置 true 而触发器列表仍为空，服务启动瞬间到达的事件全部
        // 匹配不到触发器（matchNotificationTrigger 对空列表恒 null）→ 静默丢失。
        // 先加载再 armed：加载窗口内事件照旧走 "service 未运行" 门（事件源如
        // 通知本来就是瞬态的、无法回放），但 armed 后不再有空列表丢失窗口。
        val sc = scope
        if (sc == null) {
            _serviceRunning.value = true
            appendLog("[service] proactive mode ON (no scope, armed immediately)")
            return
        }
        sc.launch {
            val loaded = try {
                reloadTriggersNow()
                true
            } catch (t: Throwable) {
                Logger.w(LOG_TAG, "arm-time trigger load failed reason=${t.message}")
                false
            }
            _serviceRunning.value = true
            appendLog("[service] proactive mode ON${if (loaded) "" else " (trigger load failed)"}")
        }
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
        if (isCooldownActive(hit.trigger)) {
            Logger.d(
                LOG_TAG,
                "cooldown active trigger=${hit.trigger.id}"
            )
            return
        }
        lastFiredAtMs[hit.trigger.id] = System.currentTimeMillis()
        appendLog("[hit] ${hit.trigger.name} <- ${hit.appLabel.ifBlank { hit.packageName }}")
        // C1：DROP_OLDEST 策略下 trySend 恒成功（满时内部丢最旧）；不再有
        // 「记了冷却但事件被丢」的死分支 —— 冷却记录与入队结果始终一致。
        queue.trySend(hit)
    }

    /** Phase 2：冷却判定独立成谓词 —— 时间触发器的 occurrence 去重需要先探冷却再记账。 */
    private fun isCooldownActive(trigger: AutomationTrigger): Boolean {
        val cooldownMs = trigger.cooldownSeconds * 1000L
        val last = lastFiredAtMs[trigger.id] ?: 0L
        return System.currentTimeMillis() - last < cooldownMs
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

            AutomationTriggerSource.BATTERY ->
                buildString {
                    appendLine("source: device battery event")
                    if (hit.trigger.batteryEvent == AutomationBatteryEvent.POWER_SAVE_ON ||
                        hit.trigger.batteryEvent == AutomationBatteryEvent.POWER_SAVE_OFF
                    ) {
                        appendLine("trigger type: One UI power saving mode ${hit.trigger.batteryEvent.name.lowercase().removePrefix("power_save_")}")
                    } else {
                        appendLine("trigger type: ${hit.trigger.batteryEvent.name.lowercase()} (threshold ${hit.trigger.batteryLevel}%)")
                    }
                    append("current state: ${hit.extra}")
                }

            AutomationTriggerSource.TIME ->
                buildString {
                    appendLine("source: scheduled time trigger")
                    append("${hit.extra}")
                }

            AutomationTriggerSource.LOCATION ->
                buildString {
                    appendLine("source: location geofence event")
                    append("${hit.extra}")
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
                "Act autonomously with your tools (system_data for device/contacts/calendar " +
                        "data, screen_operation_accessibility to open apps and operate UI, " +
                        "execute_python and file_manager for files/data, notify to report, " +
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

    /** Phase 2：看门狗等自动化组件写入活动日志的公开入口（设置页可见）。 */
    fun appendActivityLog(entry: String) = appendLog(entry)
}
