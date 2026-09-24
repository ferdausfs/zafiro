package com.niki914.zafiro.app.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.niki914.logging.Logger

/**
 * Phase 2：开机自启（Phase 1 报告遗留项 #1 的第一块拼图）。
 *
 * - [Intent.ACTION_LOCKED_BOOT_COMPLETED]：FBE 直接启动阶段。本应用的全部数据
 *   （触发器存储、SharedPreferences）都在凭据加密存储上，且三个服务均非
 *   directBootAware —— 此刻拉起只会失败或读到空状态，因此只记录日志，
 *   把真正的启动推迟到用户解锁后系统补发的 [Intent.ACTION_BOOT_COMPLETED]。
 * - [Intent.ACTION_BOOT_COMPLETED]：用户已解锁、凭据存储可读。若上一次服务
 *   停止不是用户主动（SamsungPersistenceWatchdog 台账），重新拉起
 *   [ZafiroAutomationService]；用户主动停止过的保持停止（尊重显式停止）。
 *
 * Android 12+ 后台启动 FGS 限制：开机完成广播属于 FGS 后台启动豁免场景，
 * 但 OEM（One UI 等）仍可能拦截。拒绝时抛出的
 * ForegroundServiceStartNotAllowedException 继承自 IllegalStateException
 * （ForegroundServiceStartNotAllowedException → ServiceStartNotAllowedException
 * → IllegalStateException；API 31+ 类型在 minSdk 26-30 设备上不存在，
 * 用超类捕获可跨版本命中同一拒绝场景，无需运行时类型名判断）。
 * 拒绝不补救：后台禁止 startActivity（系统级限制，也不该骚扰用户），
 * 兜底重试交给 ServiceWatchdogWorker 周期任务。
 */
class ZafiroBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                // 直接启动阶段：数据未解锁，只记录，等 BOOT_COMPLETED。
                Logger.i(TAG, "locked boot completed; defer service start until unlock")
            }

            Intent.ACTION_BOOT_COMPLETED -> {
                if (SamsungPersistenceWatchdog.wasLastStopUser(context)) {
                    Logger.i(
                        TAG,
                        "boot completed; last stop was user-initiated, keep automation off",
                    )
                    return
                }
                Logger.i(TAG, "boot completed; restarting automation service")
                tryStartAutomation(context)
            }

            else -> Unit
        }
    }

    private fun tryStartAutomation(context: Context) {
        try {
            ZafiroAutomationService.start(context)
        } catch (e: IllegalStateException) {
            // 覆盖 Android 12+ ForegroundServiceStartNotAllowedException（见类注释）。
            Logger.w(TAG, "FGS start rejected at boot: ${e.message}")
        } catch (t: Throwable) {
            Logger.w(TAG, "automation service boot start failed: ${t.message}")
        }
    }

    private companion object {
        private const val TAG = "niki914_nexus_BootRecv"
    }
}
