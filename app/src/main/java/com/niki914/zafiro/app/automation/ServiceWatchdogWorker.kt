package com.niki914.zafiro.app.automation

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.niki914.logging.Logger
import com.niki914.zafiro.app.MainActivity
import com.niki914.zafiro.app.PermissionHolder
import com.niki914.zafiro.app.R
import java.util.concurrent.TimeUnit

/**
 * Phase 2（item 3）：服务看门狗 —— WorkManager 周期任务（15 分钟）。
 *
 * 职责（与既有 START_STICKY / BootReceiver 互补，覆盖 OEM 杀进程后不重 sticky
 * 的场景 —— One UI 深度睡眠强杀后 START_STICKY 链就断了）：
 *  1. 服务重启：上次停止不是用户主动（持久化台账）且 FGS 不在 → 重新拉起；
 *  2. 电池白名单：root/Shizuku 已就绪时经 PermissionManager 静默执行
 *     `dumpsys deviceidle whitelist +pkg` 与 RUN_*_IN_BACKGROUND appop
 *     （命令文本收敛在 permission-manager 内部，见 PermissionEntryGuardTest
 *     的守卫约束）；未就绪则发一条用户可见提示通知 —— 不在后台 startActivity；
 *  3. 时间触发器兜底：顺手跑一次 TimeTriggerScheduler.catchUp（进程死亡后的
 *     补发台账也由此推进）。
 *
 * 全部动作限速/幂等：重启只在「预期运行但不在」时发生；白名单尝试 12h 冷却。
 */
class ServiceWatchdogWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        restartServiceIfExpected(context)
        maybeSilentBatteryWhitelist(context)
        try {
            TimeTriggerScheduler.catchUp(context)
        } catch (t: Throwable) {
            Logger.w(TAG, "time catch-up failed: ${t.message}")
        }
        return Result.success()
    }

    /** 预期运行（上次停止非用户主动）但服务不在 → 重启；用户显式停止过则不动。 */
    private fun restartServiceIfExpected(context: Context) {
        if (SamsungPersistenceWatchdog.wasLastStopUser(context)) return
        if (AutomationHub.serviceRunning.value) return
        try {
            ZafiroAutomationService.start(context)
            Logger.i(TAG, "watchdog restarted automation service")
        } catch (e: IllegalStateException) {
            // 覆盖 Android 12+ ForegroundServiceStartNotAllowedException（超类捕获，
            // 与 ZafiroBootReceiver 同口径）；下一周期再试。
            Logger.w(TAG, "FGS restart rejected by background-start limits: ${e.message}")
        } catch (t: Throwable) {
            Logger.w(TAG, "watchdog restart failed: ${t.message}")
        }
    }

    private suspend fun maybeSilentBatteryWhitelist(context: Context) {
        if (AutomationHub.isIgnoringBatteryOptimizations(context)) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_BATTERY_TRY_AT, 0L) < BATTERY_TRY_COOLDOWN_MS) return
        prefs.edit().putLong(KEY_LAST_BATTERY_TRY_AT, now).apply()

        // PermissionHolder 仅主进程可用；Worker 跑在主进程，但仍兜一层防误用。
        val granted = runCatching {
            PermissionHolder.get(context).trySilentBatteryWhitelist()
        }.getOrElse { t ->
            Logger.w(TAG, "silent whitelist unavailable: ${t.message}")
            false
        }
        if (granted && AutomationHub.isIgnoringBatteryOptimizations(context)) {
            Logger.i(TAG, "battery whitelist applied via privileged shell")
            appendLog(context, "[watchdog] battery whitelist applied (shell)")
            return
        }
        // 未授权（无 root/Shizuku）或命令被 ROM 拒收：用户可见提示。
        // 不 startActivity（后台禁止），点按通知由用户主动进入应用。
        postWhitelistPrompt(context)
        appendLog(context, "[watchdog] battery whitelist needs manual setup")
    }

    private fun postWhitelistPrompt(context: Context) {
        val nm = androidx.core.app.NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        val contentIntent = PendingIntent.getActivity(
            context,
            BATTERY_PROMPT_REQUEST_CODE,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(
            context, AutomationHub.CHANNEL_ALERTS,
        )
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(context.getString(R.string.watchdog_battery_prompt_title))
            .setContentText(context.getString(R.string.watchdog_battery_prompt_body))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(context.getString(R.string.watchdog_battery_prompt_body))
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        try {
            nm.notify(BATTERY_PROMPT_NOTIFICATION_ID, notification)
        } catch (t: Throwable) {
            Logger.w(TAG, "battery prompt notify failed: ${t.message}")
        }
    }

    private fun appendLog(context: Context, entry: String) {
        // 复用 AutomationHub 的活动日志（设置页可见）；进程刚被 Worker 拉起时
        // Hub 可能尚未 init —— 此时丢日志不影响功能。
        runCatching { AutomationHub.appendActivityLog(entry) }
    }

    companion object {
        private const val TAG = "niki914_nexus_SvcWatchdog"
        private const val PREFS = "automation_service_watchdog"
        private const val KEY_LAST_BATTERY_TRY_AT = "last_battery_try_at"
        private const val BATTERY_TRY_COOLDOWN_MS = 12 * 60 * 60 * 1000L
        private const val BATTERY_PROMPT_REQUEST_CODE = 2002
        private const val BATTERY_PROMPT_NOTIFICATION_ID = 1005
        private const val UNIQUE_NAME = "zafiro_service_watchdog"

        fun ensureScheduled(context: Context) {
            try {
                val request = PeriodicWorkRequestBuilder<ServiceWatchdogWorker>(
                    15, TimeUnit.MINUTES,
                ).build()
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    UNIQUE_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request,
                )
                Logger.i(TAG, "service watchdog scheduled (15min)")
            } catch (t: Throwable) {
                Logger.w(TAG, "schedule failed: ${t.message}")
            }
        }
    }
}
