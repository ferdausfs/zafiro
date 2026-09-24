package com.niki914.zafiro.app.automation

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.niki914.logging.Logger
import com.niki914.zafiro.repo.AutomationTrigger
import com.niki914.zafiro.repo.AutomationTriggerSource
import java.util.Calendar

/**
 * Phase 2：时间触发器的系统级调度（替换纯 30s 进程内轮询，Phase 1 报告遗留项 #2）。
 *
 * 三层架构：
 *  1. [AutomationHub.onTimeTick] 的进程内 30s ticker —— 进程活着时最精确（≤30s 延迟），保留为第一兜底；
 *  2. AlarmManager（[scheduleNext]）：对「下一次发生时刻」设
 *     setExactAndAllowWhileIdle（有 SCHEDULE_EXACT_ALARM 授权时）或
 *     setAndAllowWhileIdle（降级，One UI/Doze 下仍会以 idle 窗口补发）——
 *     进程死亡后闹钟仍触发（manifest receiver 拉起进程）；
 *  3. [TimeTriggerWorker] 周期任务（15min）：闹钟被 OEM 吞掉时的兜底扫描。
 *
 * 三条路径全部汇入 [AutomationHub.fireDueTimeTriggers]，以
 * "triggerId:occurrenceEpochMinute" 去重 —— 同一分钟内多路径命中只入队一次。
 *
 * 补发语义（诚实边界）：漏发补发只覆盖「新鲜」发生时刻（见
 * [FRESHNESS_MS]）；设备关机/深度休眠超过该窗口的定时触发视为过期，不补发。
 */
object TimeTriggerScheduler {

    private const val TAG = "niki914_nexus_TimeSched"

    private const val PREFS = "automation_time_schedule"
    private const val KEY_LAST_CHECK_MIN = "last_check_epoch_min"
    private const val KEY_NEXT_ALARM_AT = "next_alarm_at_ms"

    /** WorkManager 周期（框架下限 15 分钟）。 */
    internal const val WORKER_PERIOD_MIN = 15L

    /** 补发新鲜窗口：晚于此的发生时刻不再补发（含 Doze 延迟余量）。 */
    private const val FRESHNESS_MS = 30 * 60 * 1000L

    private const val ALARM_REQUEST_CODE = 2001
    private const val MILLIS_PER_MINUTE = 60_000L

    // ------------------------------------------------------- pure math utils

    /** "HH:mm" → (h, m)；非法返回 null（与既有触发器编辑器格式契约一致）。 */
    internal fun parseTimeOfDay(value: String): Pair<Int, Int>? {
        val parts = value.trim().split(":")
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h to m
    }

    /** 与 AutomationHub.onTimeTick 既有口径一致：1=周一 … 7=周日。 */
    internal fun dayOfWeekOneToSeven(epochMillis: Long): Int {
        val cal = Calendar.getInstance()
        cal.timeInMillis = epochMillis
        return (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1
    }

    internal fun dayMatches(daysOfWeek: Set<Int>, epochMillis: Long): Boolean =
        daysOfWeek.isEmpty() || dayOfWeekOneToSeven(epochMillis) in daysOfWeek

    /**
     * 触发器在 [fromMin] 之后的下一次发生时刻（epoch 分钟）；7 天内无匹配
     * （daysOfWeek 全部不在未来一周 —— 不可能，除非数据损坏）返回 null。
     */
    internal fun nextOccurrenceMin(trigger: AutomationTrigger, fromMin: Long): Long? {
        val (h, m) = parseTimeOfDay(trigger.timeOfDay) ?: return null
        for (dayOffset in 0..7) {
            val cal = Calendar.getInstance()
            cal.timeInMillis = fromMin * MILLIS_PER_MINUTE
            cal.add(Calendar.DAY_OF_YEAR, dayOffset)
            cal.set(Calendar.HOUR_OF_DAY, h)
            cal.set(Calendar.MINUTE, m)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val occMin = cal.timeInMillis / MILLIS_PER_MINUTE
            if (occMin < fromMin) continue // 今天该时刻已过
            if (dayMatches(trigger.daysOfWeek, cal.timeInMillis)) return occMin
        }
        return null
    }

    // ------------------------------------------------------------ scheduling

    /**
     * 依据当前触发器快照重排下一发精确闹钟。调用点：reloadTriggersNow（增删改
     * 触发器后）、闹钟到点后、WorkManager 周期兜底、（间接）开机路径。
     * 快照为空（进程刚复活、触发器未加载）时不动现有闹钟，等下一次 reload。
     */
    fun rearm(context: Context, triggersSnapshot: List<AutomationTrigger>) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        if (am == null) {
            Logger.w(TAG, "AlarmManager unavailable")
            return
        }
        val pending = alarmPendingIntent(context)

        val armedTimes = triggersSnapshot.filter {
            it.enabled && it.source == AutomationTriggerSource.TIME
        }
        if (armedTimes.isEmpty()) {
            // 只有拿到过真实快照（非空列表）才允许撤销：避免进程复活瞬间
            // 空快照把还有效的闹钟清掉。
            if (triggersSnapshot.isNotEmpty()) {
                runCatching { am.cancel(pending) }
                setNextAlarmPref(context, 0L)
                Logger.d(TAG, "no TIME triggers armed; alarm cancelled")
            }
            return
        }

        val nowMin = System.currentTimeMillis() / MILLIS_PER_MINUTE
        val nextMin = armedTimes
            .mapNotNull { nextOccurrenceMin(it, nowMin) }
            .minOrNull()
        if (nextMin == null) {
            Logger.w(TAG, "no next occurrence for ${armedTimes.size} TIME triggers")
            return
        }
        scheduleNext(context, am, pending, nextMin)
    }

    private fun scheduleNext(context: Context, am: AlarmManager, pending: PendingIntent, nextMin: Long) {
        val triggerAt = nextMin * MILLIS_PER_MINUTE
        // SCHEDULE_EXACT_ALARM 在 API 31+ 默认拒绝（用户可在系统设置授予）；
        // 无授权时降级 setAndAllowWhileIdle —— One UI/Doze 下仍以 idle 窗口补发，
        // 配合 WorkManager 兜底把误差收敛到一个 worker 周期内。
        val canExact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        try {
            if (canExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            }
            setNextAlarmPref(context, triggerAt)
            Logger.i(
                TAG,
                "alarm armed at=$triggerAt (${if (canExact) "exact" else "inexact"})",
            )
        } catch (t: Throwable) {
            // SecurityException（精确权限被运行时撤销）等：不影响主流程，
            // WorkManager 兜底仍然生效。
            Logger.w(TAG, "alarm schedule failed: ${t.message}")
        }
    }

    private fun alarmPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, TimeTriggerAlarmReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    // ------------------------------------------------------------ fire paths

    /** 精确闹钟到点：补发新鲜窗口内的漏发 + 重排下一次。 */
    fun onAlarmFired(context: Context) {
        fireAndBookkeep(context, usePersistedWindow = true)
    }

    /** WorkManager 周期兜底：同闹钟语义（窗口取持久化 lastCheck）。 */
    fun catchUp(context: Context) {
        fireAndBookkeep(context, usePersistedWindow = true)
    }

    private fun fireAndBookkeep(context: Context, usePersistedWindow: Boolean) {
        val nowMin = System.currentTimeMillis() / MILLIS_PER_MINUTE
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var fromMin = nowMin
        if (usePersistedWindow) {
            val lastCheck = prefs.getLong(KEY_LAST_CHECK_MIN, 0L)
            val freshnessFloorMin = (System.currentTimeMillis() - FRESHNESS_MS) / MILLIS_PER_MINUTE
            fromMin = maxOf(lastCheck, freshnessFloorMin)
        }
        val fired = AutomationHub.fireDueTimeTriggers(fromMin = fromMin, toMin = nowMin)
        // 台账推进：无论是否命中（service 关闭时命中被闸门挡掉）都推进，
        // 避免旧窗口被反复扫描；补发只看本窗口，跨窗口遗漏属新鲜度边界外。
        prefs.edit().putLong(KEY_LAST_CHECK_MIN, nowMin).apply()
        rearm(context, AutomationHub.triggersSnapshot())
        if (fired > 0) {
            Logger.i(TAG, "time triggers fired=$fired window=[$fromMin..$nowMin]")
        }
    }

    private fun setNextAlarmPref(context: Context, atMs: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_NEXT_ALARM_AT, atMs).apply()
    }
}
