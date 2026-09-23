package com.niki914.zafiro.app.automation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.niki914.logging.Logger
import com.niki914.zafiro.app.MainActivity
import com.niki914.zafiro.app.R

/**
 * 后台任务前台服务（Feature: Asynchronous Background Execution）。
 *
 * 聊天回合由 BackgroundTaskHub 在应用进程 scope 执行；本服务只承担一个职责：
 * 挂一条常驻前台通知提升进程优先级，让「关掉聊天页 / 退到桌面」后回合不被
 * 系统回收打断。任务队列清空后由 Hub 延迟拉起 stopSelf。
 *
 * specialUse FGS 类型（与 ZafiroAutomationService 同模式）：Android 14 要求
 * manifest 声明 foregroundServiceType + PROPERTY_SPECIAL_USE_FGS_SUBTYPE。
 */
class ZafiroTaskService : Service() {

    override fun onCreate() {
        super.onCreate()
        BackgroundTaskHub.ensureChannels(this)
        val notification = BackgroundTaskHub.buildForegroundNotification(this)
        startForeground(FOREGROUND_NOTIFICATION_ID, notification)
        BackgroundTaskHub.onServiceStarted()
        Logger.i(LOG_TAG, "task FGS started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            BackgroundTaskHub.onServiceStopping()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        // 任务运行期间进程被回收后重启：无任务则立即退场
        if (!BackgroundTaskHub.hasRunningTasks()) {
            BackgroundTaskHub.onServiceStopping()
            stopSelf()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        BackgroundTaskHub.onServiceStopped()
        Logger.i(LOG_TAG, "task FGS destroyed")
        super.onDestroy()
    }

    companion object {
        private const val LOG_TAG = "niki914_nexus_TaskService"
        private const val FOREGROUND_NOTIFICATION_ID = 1003
        private const val ACTION_STOP = "com.niki914.zafiro.action.TASK_SERVICE_STOP"

        fun start(context: Context) {
            val intent = Intent(context, ZafiroTaskService::class.java)
            try {
                context.startForegroundService(intent)
            } catch (t: Throwable) {
                Logger.w(LOG_TAG, "startForegroundService failed reason=${t.message}")
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ZafiroTaskService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
