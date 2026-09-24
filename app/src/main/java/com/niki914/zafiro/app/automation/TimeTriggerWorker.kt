package com.niki914.zafiro.app.automation

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.niki914.logging.Logger
import java.util.concurrent.TimeUnit

/**
 * Phase 2：时间触发器的 WorkManager 周期兜底（item 2 的第三层）。
 *
 * 覆盖两类缺口：
 *  - 精确闹钟被 OEM/Doze 吞掉（setExactAndAllowWhileIdle 在深度 Doze 下有
 *    每应用节流，One UI 深度睡眠更激进）；
 *  - 闹钟触发时进程已被杀、进程又被系统拉起投递失败等边缘时序。
 *
 * 15 分钟是 WorkManager 周期任务的下限 —— 这不是实时通道，只是把
 * 「闹钟层失守」的误差收敛到一个周期；精确性由 AlarmManager 层承担。
 * 同一发生时刻的三路径重复命中由 AutomationHub 的 occurrence 去重挡住。
 */
class TimeTriggerWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            TimeTriggerScheduler.catchUp(applicationContext)
            Result.success()
        } catch (t: Throwable) {
            // 周期任务：单次失败不重试，等下一周期（结果无消费方，日志留痕）
            Logger.w(TAG, "time catch-up failed: ${t.message}")
            Result.success()
        }
    }

    companion object {
        private const val TAG = "niki914_nexus_TimeWorker"
        private const val UNIQUE_NAME = "zafiro_automation_time_catchup"

        /**
         * 注册周期兜底（KEEP：已存在不替换）。调用点：App.onCreate 主进程初始化。
         * WorkManager 采用按需初始化（App 实现 Configuration.Provider，
         * 默认 initializer 已在 manifest 移除），首次调用时自动装配。
         */
        fun ensureScheduled(context: Context) {
            try {
                val request = PeriodicWorkRequestBuilder<TimeTriggerWorker>(
                    TimeTriggerScheduler.WORKER_PERIOD_MIN, TimeUnit.MINUTES,
                ).build()
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    UNIQUE_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request,
                )
                Logger.i(TAG, "periodic catch-up scheduled (15min)")
            } catch (t: Throwable) {
                Logger.w(TAG, "schedule failed: ${t.message}")
            }
        }
    }
}
