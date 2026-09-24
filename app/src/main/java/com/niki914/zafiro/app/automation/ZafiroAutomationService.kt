package com.niki914.zafiro.app.automation

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.niki914.logging.Logger

/**
 * 前台服务（specialUse）：保活整个进程，防止系统杀掉通知监听与触发引擎。
 * 常驻低优先级通知由 [AutomationHub.buildForegroundNotification] 提供。
 */
class ZafiroAutomationService : Service() {

    override fun onCreate() {
        super.onCreate()
        startForeground(
            AUTOMATION_FGS_ID,
            AutomationHub.buildForegroundNotification(
                this,
                AutomationHub.armedTriggerCount.value,
            ),
        )
        AutomationHub.onServiceStarted()
        SamsungPersistenceWatchdog.onServiceStarted(this)
        Logger.i(LOG_TAG, "foreground service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 刷新常驻通知（armed 数量可能变化）
        startForeground(
            AUTOMATION_FGS_ID,
            AutomationHub.buildForegroundNotification(
                this,
                AutomationHub.armedTriggerCount.value,
            ),
        )
        if (intent?.action == ACTION_STOP) {
            // 用户主动停止：watchdog 不得把下一次启动判成系统强杀
            SamsungPersistenceWatchdog.userStopRequested = true
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        AutomationHub.onServiceStopped()
        SamsungPersistenceWatchdog.onServiceStopped(this)
        Logger.i(LOG_TAG, "foreground service stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val LOG_TAG = "niki914_nexus_AutomationSvc"
        const val AUTOMATION_FGS_ID = 1002
        const val ACTION_STOP = "com.niki914.zafiro.automation.STOP"

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, ZafiroAutomationService::class.java)
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ZafiroAutomationService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
