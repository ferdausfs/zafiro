package com.niki914.zafiro.app.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.niki914.logging.Logger

/**
 * Phase 2：时间触发器精确闹钟的到点入口。
 *
 * manifest receiver：进程死亡时由系统拉起进程（App.onCreate → AutomationHub.init
 * 先于 onReceive），因此这里可以直接走 Hub 的静态路径。onReceive 内全部是
 * 内存读 + 共偏好的快速操作，不需要 goAsync。
 *
 * 漏发补发与重排逻辑见 [TimeTriggerScheduler.onAlarmFired]；proactive 服务
 * 未运行时命中会被 Hub 的闸门挡掉（时间触发器是主动模式能力，与既有语义一致）。
 */
class TimeTriggerAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext ?: return
        try {
            TimeTriggerScheduler.onAlarmFired(appContext)
        } catch (t: Throwable) {
            Logger.w(TAG, "alarm fire handling failed: ${t.message}")
        }
    }

    private companion object {
        private const val TAG = "niki914_nexus_TimeAlarm"
    }
}
