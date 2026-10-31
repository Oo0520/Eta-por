package io.github.mangi.eta.agent.device

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import io.github.mangi.eta.agent.display.dashboard.AgentControlMode
import io.github.mangi.eta.agent.display.dashboard.AgentLiveActivityBridge
import io.github.mangi.eta.agent.display.dashboard.AgentLiveCoordinator
import io.github.mangi.eta.data.repository.NotificationHistoryRepository

class AgentNotificationHistoryService : NotificationListenerService() {
    private val repository by lazy { NotificationHistoryRepository(this) }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        record(sbn)
        publishTrigger("notification_posted", sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        handleRemoved(sbn, -1)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap?, reason: Int) {
        handleRemoved(sbn, reason)
    }

    /**
     * 自家流体云卡片消失。
     *
     * 只有「用户亲手划掉」且当前正处在待确认态，才算拒绝；应用自己撤卡（REASON_APP_CANCEL）
     * 与系统回收（COLOROS 9251018 一类）都不改变执行权 ——
     * 早前把任何移除都当挂起信号，结果卡片一没注入门就被焊死，连界面工具都点不动。
     */
    private fun handleRemoved(sbn: StatusBarNotification, @Suppress("UNUSED_PARAMETER") reason: Int) {
        if (sbn.packageName == packageName) {
            // 判定只看「当前是否在等用户确认」：应用自己撤卡、系统回收都不该改变执行权。
            if ((sbn.id == io.github.mangi.eta.agent.runtime.SessionNotificationIds.IDLE_NOTIFICATION_ID ||
                    io.github.mangi.eta.agent.runtime.SessionNotificationIds.isSessionCard(sbn.id)) &&
                AgentLiveCoordinator.currentMode() == AgentControlMode.AWAITING_CONFIRM
            ) {
                AgentLiveCoordinator.onCardDismissed(this, "user_dismiss")
            }
            return
        }
        publishTrigger("notification_removed", sbn)
    }

    private fun publishTrigger(type: String, sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val extras = sbn.notification.extras
        io.github.mangi.eta.agent.automation.AgentTaskScheduler.publish(this, type, "${sbn.key}:${sbn.postTime}", org.json.JSONObject()
            .put("packageName", sbn.packageName).put("title", extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.take(1000).orEmpty())
            .put("text", extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.take(2000).orEmpty()))
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        connectedService = this
        activeNotifications?.forEach(::record)
    }

    override fun onListenerDisconnected() {
        if (connectedService === this) connectedService = null
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        if (connectedService === this) connectedService = null
        super.onDestroy()
    }

    private fun record(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        repository.record(
            key = sbn.key,
            packageName = sbn.packageName,
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
            postedAt = sbn.postTime,
        )
    }

    companion object {
        @Volatile
        private var connectedService: AgentNotificationHistoryService? = null

        internal fun currentNotifications(): Array<StatusBarNotification>? {
            val service = connectedService ?: return null
            return try {
                service.activeNotifications
            } catch (_: RuntimeException) {
                null
            }
        }

        fun isEnabled(context: Context): Boolean {
            val manager = context.getSystemService(android.app.NotificationManager::class.java)
                ?: return false
            return manager.isNotificationListenerAccessGranted(
                ComponentName(context, AgentNotificationHistoryService::class.java),
            )
        }
    }
}
