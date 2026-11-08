package io.github.mangi.eta.agent.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import io.github.mangi.eta.R

/**
 * Overlay 不等同于前台 Activity；只在可见浮窗采集或播放音频期间提升服务类型。
 *
 * 只在真的把麦克风交给本进程（AudioRecord）时提升 microphone 类型。系统识别
 * （[AsrProvider.SYSTEM]）由外部识别服务录音，本进程不持有麦克风；在助手浮窗的
 * Service 上提升 microphone 类型会被系统判定为非前台来源而拒绝，用户就会在权限
 * 早已授予的情况下看到「请允许 Eta 使用麦克风」。
 */
internal class AssistantSpeechForeground(private val service: Service) {
    fun recording() = start(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE, "Eta 正在聆听")
    fun playback() = start(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK, "Eta 正在朗读")

    private fun start(type: Int, title: String) {
        service.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "助手语音", NotificationManager.IMPORTANCE_LOW),
        )
        val stop = PendingIntent.getService(service, 0,
            Intent(service, EtaAssistantOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(service, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText("仅在助手浮窗可见时运行")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, "停止", stop).build())
            .build()
        try {
            service.startForeground(NOTIFICATION_ID, notification, type)
        } catch (_: RuntimeException) {
            throw SpeechFailure(SpeechErrorCode.PERMISSION, "系统不允许当前浮窗使用音频，请检查麦克风权限和默认数字助理设置")
        }
    }

    fun stop() { service.stopForeground(Service.STOP_FOREGROUND_REMOVE) }

    companion object {
        const val ACTION_STOP = "io.github.mangi.eta.STOP_ASSISTANT_SPEECH"
        private const val CHANNEL = "eta_assistant_speech"
        private const val NOTIFICATION_ID = 4203
    }
}
