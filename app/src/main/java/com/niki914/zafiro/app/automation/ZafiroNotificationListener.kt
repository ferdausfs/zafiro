package com.niki914.zafiro.app.automation

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.niki914.logging.Logger

/** 活跃通知的轻量快照（重连回放用；只保留触发匹配所需字段）。 */
data class NotificationSnapshot(
    val key: String,
    val packageName: String,
    val title: String,
    val text: String,
    val postTime: Long,
)

/**
 * 系统通知监听服务：需要用户在系统设置授予「通知使用权」。
 * 只做事件转发，全部逻辑在 [AutomationHub]（主线程回调，必须快速返回）。
 *
 * Phase 2（item 5）健壮性：
 *  - 断连（onListenerDisconnected，多为低内存回收绑定）：官方模式立即
 *    requestRebind，再加 5s/20s 两次兜底重试 —— 个别 ROM 上首次请求会被
 *    系统静默忽略；重连成功（onListenerConnected）即撤销重试。
 *  - 活跃通知快照：连接建立时把系统当前活跃通知做成轻量快照交给 Hub 回放
 *    匹配 —— 服务重启/断连期间新到的通知只要此刻仍在通知栏就不会丢
 *    （回放窗口与去重策略见 [AutomationHub.onListenerSnapshot]）。
 *    诚实边界：断连窗口内「到达且已被划走」的通知系统不留底，无法回放。
 */
class ZafiroNotificationListener : NotificationListenerService() {

    private val retryHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var connected = false

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = true
        retryHandler.removeCallbacksAndMessages(null)
        AutomationHub.onListenerConnected()
        snapshotAndForward()
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
        connected = false
        AutomationHub.onListenerDisconnected()
        // 系统可能因低内存断开绑定；请求重连（官方推荐模式）+ 有限重试兜底。
        requestRebind(componentName(this))
        scheduleRebindRetry(RETRY_DELAY_SHORT_MS)
        scheduleRebindRetry(RETRY_DELAY_LONG_MS)
    }

    override fun onDestroy() {
        retryHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun scheduleRebindRetry(delayMs: Long) {
        retryHandler.postDelayed({
            if (!connected) {
                Logger.w(LOG_TAG, "still disconnected after ${delayMs}ms; retrying requestRebind")
                runCatching { requestRebind(componentName(this)) }
            }
        }, delayMs)
    }

    private fun snapshotAndForward() {
        val active = runCatching { activeNotifications }.getOrNull() ?: return
        val snapshots = active.mapNotNull(::toSnapshot)
        try {
            AutomationHub.onListenerSnapshot(snapshots)
        } catch (t: Throwable) {
            Logger.w(LOG_TAG, "snapshot forward failed reason=${t.message}")
        }
    }

    private fun toSnapshot(sbn: StatusBarNotification): NotificationSnapshot? {
        return runCatching {
            if (sbn.packageName == packageName) return null // 防自触发死循环
            if (sbn.isOngoing) return null
            val extras = sbn.notification.extras
            val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
            val bigText = extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
            val body = if (bigText.length > text.length) bigText else text
            if (title.isBlank() && body.isBlank()) return null
            NotificationSnapshot(
                key = sbn.key ?: "${sbn.packageName}:${sbn.postTime}",
                packageName = sbn.packageName,
                title = title,
                text = body,
                postTime = sbn.postTime,
            )
        }.getOrNull()
    }

    companion object {
        private const val LOG_TAG = "niki914_nexus_NotifListener"
        private const val RETRY_DELAY_SHORT_MS = 5_000L
        private const val RETRY_DELAY_LONG_MS = 20_000L

        fun componentName(context: Context): ComponentName =
            ComponentName(context, ZafiroNotificationListener::class.java)
    }
}
