package io.github.mangi.eta.agent.display

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import io.github.mangi.eta.R
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType

internal object VirtualScreenNotification {
    private const val CHANNEL = "eta_virtual_screen"
    private const val ID = 1110

    fun show(context: Context, sessionId: String) {
        if (android.os.Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) return
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL, context.getString(R.string.virtual_screen_notification_channel), NotificationManager.IMPORTANCE_LOW,
            ))
            val open = PendingIntent.getActivity(context, 0,
                android.content.Intent(context, io.github.mangi.eta.ui.MainActivity::class.java)
                    .setData(Uri.parse("eta-virtual-screen://view/$sessionId"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            manager.notify(sessionId, ID, Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.virtual_screen_notification_title))
                .setContentText(context.getString(R.string.virtual_screen_notification_content))
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).build())
        }.onFailure { AndroidAgentLogger.warn("Virtual screen notification failed: type=${it.safeLogType()}") }
    }

    fun cancel(context: Context, sessionId: String) {
        context.getSystemService(NotificationManager::class.java)?.cancel(sessionId, ID)
    }

    /** v2：旧 ID 一旦被系统建过就可能已被降级/关闭，重开渠道才能保证 heads-up。 */
    private const val COMPLETION_CHANNEL = "eta_task_completion_v2"
    private const val COMPLETION_ID = 1111
    private const val RETURN_CHANNEL = "eta_lingscreen_return"
    private const val RETURN_ID = 1112

    /** 灵屏指令运行完毕后，走一条高优先级通知通道，由 ColorOS 尽量归入流体云展示。 */
    /**
     * 任务结束通知：用户离开手机时，这是唯一的结果反馈。
     *
     * 三点必须注意：
     * 1. 不能受「会话是否还在」影响——会话被接管/替换过就提前 return，通知就永远不发；
     * 2. 渠道用 v2：旧渠道 ID 一旦被系统或用户降级，再提高重要性无效；
     * 3. 正文取结果摘要并截断：完整结果在 App 里看，通知栏只给一句能读懂的话。
     */
    /**
     * 「回到灵屏」常驻通知：从接管页跳到真机后挂出来，点它回到接管页。
     * 用独立 ID，不和任务完成通知抢位置。
     */
    fun showReturnToLingscreen(context: Context, pkg: String) {
        if (android.os.Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(
                RETURN_CHANNEL,
                "回到灵屏",
                NotificationManager.IMPORTANCE_DEFAULT,
            ))
            val open = PendingIntent.getActivity(
                context,
                RETURN_ID,
                Intent(context, TakeoverActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            manager.notify(RETURN_ID, Notification.Builder(context, RETURN_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.lingscreen_return_title))
                .setContentText(context.getString(R.string.lingscreen_return_body, pkg))
                .setCategory(Notification.CATEGORY_STATUS)
                .setOngoing(true)
                .setContentIntent(open).build())
            io.github.mangi.eta.core.EtaTrace.log("lingscreen.jump", "pkg=" + pkg)
        }.onFailure {
            io.github.mangi.eta.core.EtaTrace.log("lingscreen.jump.fail", "type=${it.safeLogType()}")
        }
    }

    fun showCompletion(context: Context, ok: Boolean, detail: String, stopped: Boolean = false) {
        if (android.os.Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            io.github.mangi.eta.core.EtaTrace.log("completion.skip", "POST_NOTIFICATIONS 未授权")
            AndroidAgentLogger.warn("Completion notification skipped: POST_NOTIFICATIONS not granted")
            return
        }
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(
                COMPLETION_CHANNEL,
                context.getString(R.string.virtual_screen_completion_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "灵屏任务结束时提醒结果"
                enableVibration(true)
                setShowBadge(true)
            })
            val title = when {
                stopped -> context.getString(R.string.virtual_screen_completion_stopped_title)
                ok -> context.getString(R.string.virtual_screen_completion_ok_title)
                else -> context.getString(R.string.virtual_screen_completion_fail_title)
            }
            val body = when {
                stopped -> context.getString(R.string.virtual_screen_completion_stopped_body)
                else -> detail.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
                    ?.take(120)
                    ?.ifBlank { context.getString(R.string.virtual_screen_completion_ok_body) }
                    ?: context.getString(R.string.virtual_screen_completion_ok_body)
            }
            val open = PendingIntent.getActivity(context, 0,
                Intent(context, io.github.mangi.eta.ui.MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            manager.notify(COMPLETION_ID, Notification.Builder(context, COMPLETION_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setCategory(Notification.CATEGORY_STATUS)
                .setShowWhen(true)
                .setAutoCancel(true)
                .setContentIntent(open).build())
            io.github.mangi.eta.core.EtaTrace.log(
                "completion.post",
                "ok=$ok stopped=$stopped chars=" + detail.length,
            )
        }.onFailure {
            io.github.mangi.eta.core.EtaTrace.log("completion.fail", "type=${it.safeLogType()} msg=${it.message?.take(80)}")
            AndroidAgentLogger.warn("Virtual screen completion notification failed: type=${it.safeLogType()}")
        }
    }
}
