package io.github.mangi.eta.agent.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import io.github.mangi.eta.R
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.EtaTrace
import io.github.mangi.eta.core.safeLogType

/**
 * 任务结束通知：所有对话共用。
 *
 * 以前只有灵屏路由的 run 会在结束时发一条「任务完成」，普通对话跑完什么都不留，
 * 用户切走后不知道结果。这里把它抽成通用出口：
 *
 * - 一条对话一个稳定 ID（[SessionNotificationIds.completionIdFor]），并发不会互相覆盖；
 * - 点通知直接落到对应会话（[io.github.mangi.eta.ui.MainActivity.EXTRA_CONVERSATION_ID]）；
 * - SDK 36 起补 `setShortCriticalText`，和运行中的实时活动卡一致，进流体云。
 *
 * 通知不常驻（autoCancel），用户划掉或点开即消失；运行中的进度卡仍由
 * [RunProgressNotifier] 负责，两者 ID 分段，互不打扰。
 */
internal object TaskCompletionNotifier {

    private const val CHANNEL = "eta_task_completion"
    private const val LIVE_SDK = 36

    fun show(
        context: Context,
        ok: Boolean,
        detail: String,
        stopped: Boolean = false,
        conversationId: String = "",
        conversationTitle: String = "",
        runId: String = "",
    ) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            EtaTrace.log("completion.skip", "POST_NOTIFICATIONS 未授权")
            AndroidAgentLogger.warn("Completion notification skipped: POST_NOTIFICATIONS not granted")
            return
        }
        // 前台时只跳过「你正在看的那条会话」——结果已经直接写进对话了，再弹横幅是噪音。
        // 但别的会话跑完仍要提醒：你在 B 里，A 跑完了你得知道。
        if (io.github.mangi.eta.core.AppForeground.isForeground) {
            val viewed = io.github.mangi.eta.agent.display.dashboard.AgentConfirmGuard.viewedConversation
            val sameConversation = conversationId.isBlank() || viewed.isBlank() || viewed == conversationId
            if (sameConversation) {
                EtaTrace.log("completion.skip", "前台且在看该会话")
                return
            }
        }
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    context.getString(R.string.task_completion_channel),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "对话跑完时提醒结果"
                    enableVibration(true)
                    setShowBadge(true)
                },
            )
            val title = when {
                stopped -> context.getString(R.string.task_completion_stopped_title)
                ok -> context.getString(R.string.task_completion_ok_title)
                else -> context.getString(R.string.task_completion_fail_title)
            }
            val summary = detail.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
                ?.take(120)
                ?.ifBlank { context.getString(R.string.task_completion_ok_body) }
                ?: context.getString(R.string.task_completion_ok_body)
            val body = if (conversationTitle.isBlank()) summary else "$conversationTitle · $summary"
            val contentIntent = openIntent(context, conversationId)
            val builder = Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setShowWhen(true)
                .setAutoCancel(true)
                .setContentIntent(contentIntent)
                // 完成通知属于「要你回来处理」：CATEGORY_STATUS 在 ColorOS 上会被归进折叠区，
                // 换成 REMINDER 才可能弹横幅（实测 category=status 的通知没出过横幅）。
                .setCategory(Notification.CATEGORY_REMINDER)
            if (Build.VERSION.SDK_INT >= LIVE_SDK && FluidNotificationPolicy.allowsFluid()) {
                builder.setShortCriticalText(
                    when {
                        stopped -> context.getString(R.string.task_completion_capsule_stopped)
                        ok -> context.getString(R.string.task_completion_capsule_ok)
                        else -> context.getString(R.string.task_completion_capsule_fail)
                    },
                )
            }
            manager.notify(
                SessionNotificationIds.completionIdFor(conversationId.ifBlank { runId }),
                builder.build(),
            )
            EtaTrace.log(
                "completion.post",
                "ok=$ok stopped=$stopped conv=" + conversationId.take(8) + " chars=" + detail.length,
            )
        }.onFailure {
            EtaTrace.log("completion.fail", "type=${it.safeLogType()} msg=${it.message?.take(80)}")
            AndroidAgentLogger.warn("Completion notification failed: type=${it.safeLogType()}")
        }
    }

    /** 点通知直达对应会话；没有会话（系统入口、子智能体）时只把 App 拉到前台。 */
    private fun openIntent(context: Context, conversationId: String): PendingIntent {
        val intent = Intent(context, io.github.mangi.eta.ui.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (conversationId.isNotBlank()) {
            // 光塞 extra 不够：MainActivity 只在 action == ACTION_OPEN_CONVERSATION_ID 时才读它，
            // 少了这一行就是「看起来绑了会话、实际只把 App 拉到前台」（一直如此，用户实测发现）。
            intent.setAction(io.github.mangi.eta.ui.MainActivity.ACTION_OPEN_CONVERSATION_ID)
            intent.putExtra(io.github.mangi.eta.ui.MainActivity.EXTRA_CONVERSATION_ID, conversationId)
        }
        return PendingIntent.getActivity(
            context,
            conversationId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
