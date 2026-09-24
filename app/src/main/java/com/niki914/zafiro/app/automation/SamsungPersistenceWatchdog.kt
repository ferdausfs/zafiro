package com.niki914.zafiro.app.automation

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.niki914.logging.Logger
import com.niki914.zafiro.app.MainActivity
import com.niki914.zafiro.app.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * v1.8.0 Samsung Persistence Watchdog —— One UI「深度睡眠应用」防线。
 *
 * Samsung Special Directive: Persistence ——
 * One UI 的电池优化（Put unused apps to sleep / Deep sleeping apps）会强制停止
 * 前台服务甚至冻结整个进程。本监视器：
 *  1. 记录 FGS 每次启停；若上一次停止不是用户主动（ACTION_STOP），视为系统强杀；
 *  2. 维护 24 小时窗口内的强杀计数（StateFlow 供设置页展示）；
 *  3. 强杀频发时（≥2 次/24h，12 小时限速）发一条高优通知，引导用户去
 *     One UI 电池设置把 Zafiro 移出深度睡眠列表。
 *
 * 全部状态落在 SharedPreferences：进程被杀重建后依然可以判断。
 */
object SamsungPersistenceWatchdog {

    private const val LOG_TAG = "niki914_nexus_SamsungWatchdog"

    private const val PREFS = "samsung_persistence_watchdog"
    private const val KEY_LAST_STOP_AT = "last_stop_at"
    private const val KEY_LAST_STOP_USER = "last_stop_user"
    private const val KEY_KILL_TIMES = "kill_times"
    private const val KEY_LAST_WARN_AT = "last_warn_at"
    private const val KEY_LAST_BOOT_COUNT = "last_boot_count"

    private const val KILL_WINDOW_MS = 24 * 60 * 60 * 1000L
    private const val WARN_COOLDOWN_MS = 12 * 60 * 60 * 1000L
    private const val WARN_THRESHOLD = 2
    private const val KILLED_NOTIFICATION_ID = 1003

    /** 由 ZafiroAutomationService 在处理 ACTION_STOP 时置位（用户主动停止）。 */
    @Volatile
    var userStopRequested: Boolean = false

    private val _killCount24h = MutableStateFlow(0)
    val killCount24h: StateFlow<Int> = _killCount24h

    private val _lastKillAtMs = MutableStateFlow(0L)
    val lastKillAtMs: StateFlow<Long> = _lastKillAtMs

    // ------------------------------------------------------------ lifecycle

    fun onServiceStarted(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val bootCount = currentBootCount(context)
        val lastStopUser = prefs.getBoolean(KEY_LAST_STOP_USER, true)
        val storedBoot = prefs.getInt(KEY_LAST_BOOT_COUNT, -1)
        if (!lastStopUser) {
            // 进程上次是「无 onDestroy 的突然结束」：
            //  - boot_count 没变 → 系统强杀（One UI 深度睡眠等）
            //  - boot_count 变了 → 只是重启，不算被杀
            if (storedBoot in 0..bootCount) {
                recordKill(context)
                maybeWarnKilled(context)
            } else {
                Logger.i(LOG_TAG, "abrupt end was a reboot (boot $storedBoot->$bootCount), not a kill")
            }
        }
        // 服务运行期间：期望运行中；若下次启动时仍是此状态 → 说明进程被突然杀掉
        // E：kill 检测状态必须 commit() —— 这些记录的全部意义就是在进程被
        // 突然杀掉后仍可判定；apply() 的异步落盘在进程死亡时会丢失未刷写数据，
        // 恰好是本监视器最需要写盘成功的场景。
        prefs.edit()
            .putLong(KEY_LAST_STOP_AT, System.currentTimeMillis())
            .putBoolean(KEY_LAST_STOP_USER, false)
            .putInt(KEY_LAST_BOOT_COUNT, bootCount)
            .commit()
        refreshFlows(context)
    }

    fun onServiceStopped(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // E：同 onServiceStarted —— 停止状态记录必须跨进程死亡存活（commit）。
        prefs.edit()
            .putLong(KEY_LAST_STOP_AT, System.currentTimeMillis())
            .putBoolean(KEY_LAST_STOP_USER, userStopRequested)
            .putInt(KEY_LAST_BOOT_COUNT, currentBootCount(context))
            .commit()
        userStopRequested = false
        Logger.i(LOG_TAG, "service stopped (userStopRequested handled)")
    }

    /** Settings.Global "boot_count"（读系统设置不需要权限）。 */
    private fun currentBootCount(context: Context): Int = try {
        Settings.Global.getInt(context.contentResolver, "boot_count", -1)
    } catch (t: Throwable) {
        -1
    }

    /**
     * Phase 2（BootReceiver / ServiceWatchdogWorker 共用）：上一次服务停止是否为
     * 用户主动。台账即持久化状态（onServiceStopped 落盘），无记录（首次安装）
     * 按「用户未开启过」处理 —— 开机不自启，等用户首次打开后再跟随。
     */
    fun wasLastStopUser(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_LAST_STOP_USER, true)
    }

    // ------------------------------------------------------------- internals

    private fun recordKill(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val windowStart = now - KILL_WINDOW_MS
        val times = prefs.getString(KEY_KILL_TIMES, "")
            .orEmpty()
            .split(',')
            .mapNotNull(String::toLongOrNull)
            .filter { it >= windowStart } + now
        // E：强杀记录用 commit() —— 若 apply 丢失，24h 强杀计数与提醒冷却都会失真。
        prefs.edit()
            .putString(KEY_KILL_TIMES, times.joinToString(","))
            .commit()
        Logger.w(LOG_TAG, "system kill detected; kills in 24h window=${times.size}")
    }

    private fun maybeWarnKilled(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val kills = prefs.getString(KEY_KILL_TIMES, "")
            .orEmpty()
            .split(',')
            .mapNotNull(String::toLongOrNull)
            .filter { it >= now - KILL_WINDOW_MS }
        if (kills.size < WARN_THRESHOLD) return
        val lastWarn = prefs.getLong(KEY_LAST_WARN_AT, 0L)
        if (now - lastWarn < WARN_COOLDOWN_MS) return

        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        val contentIntent = PendingIntent.getActivity(
            context,
            KILLED_NOTIFICATION_ID,
            buildBatterySettingsIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(
            context, AutomationHub.CHANNEL_ALERTS
        )
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(context.getString(R.string.samsung_fgs_killed_title))
            .setContentText(context.getString(R.string.samsung_fgs_killed_body))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(context.getString(R.string.samsung_fgs_killed_body))
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        try {
            nm.notify(KILLED_NOTIFICATION_ID, notification)
            // E：提醒冷却时间戳同批 commit，避免重复提醒。
            prefs.edit().putLong(KEY_LAST_WARN_AT, now).commit()
            Logger.w(LOG_TAG, "persistence warning notification posted")
        } catch (t: Throwable) {
            Logger.w(LOG_TAG, "persistence warning failed: ${t.message}")
        }
    }

    private fun refreshFlows(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val kills = prefs.getString(KEY_KILL_TIMES, "")
            .orEmpty()
            .split(',')
            .mapNotNull(String::toLongOrNull)
            .filter { it >= now - KILL_WINDOW_MS }
        _killCount24h.value = kills.size
        _lastKillAtMs.value = kills.maxOrNull() ?: 0L
    }

    // ------------------------------------------------------------ deep links

    /**
     * 打开 One UI 电池/设备维护页；返回实际使用的路径描述
     * （device_care_battery / device_care_main / battery_optimization / app_details）。
     */
    fun buildBatterySettingsIntent(context: Context): Intent {
        // 1) Device Care battery 页
        runCatching {
            return Intent().setComponent(
                ComponentName(
                    "com.samsung.android.lool",
                    "com.samsung.android.sm.ui.battery.BatteryActivity",
                )
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // 2) Device Care 主页
        runCatching {
            context.packageManager.getLaunchIntentForPackage("com.samsung.android.lool")
                ?.let { return it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        }
        // 3) 系统电池优化
        runCatching {
            return Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // 4) 应用详情
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** 设置页直接调用的打开入口（无返回值版）。 */
    fun openBatterySettings(context: Context) {
        runCatching { context.startActivity(buildBatterySettingsIntent(context)) }
    }
}
