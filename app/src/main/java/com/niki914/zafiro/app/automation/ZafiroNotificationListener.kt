package com.niki914.zafiro.app.automation

import com.niki914.logging.Logger
import com.niki914.zafiro.app.MainActivity
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.content.ComponentName
import android.content.Context

/**
 * 系统通知监听服务：需要用户在系统设置授予「通知使用权」。
 * 只做事件转发，全部逻辑在 [AutomationHub]（主线程回调，必须快速返回）。
 */
class ZafiroNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        AutomationHub.onListenerConnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            AutomationHub.onNotificationPosted(sbn)
        } catch (t: Throwable) {
            Logger.w(LOG_TAG, "onNotificationPosted failed reason=${t.message}")
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        AutomationHub.onListenerDisconnected()
        // 系统可能因低内存断开绑定；请求重连。
        requestRebind(ComponentName(this, ZafiroNotificationListener::class.java))
    }

    companion object {
        private const val LOG_TAG = "niki914_nexus_NotifListener"

        fun componentName(context: Context): ComponentName =
            ComponentName(context, ZafiroNotificationListener::class.java)
    }
}
