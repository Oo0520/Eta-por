package io.github.mangi.eta.core

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

/**
 * 开机实证：记录「上次收到 BOOT_COMPLETED 的时间」和「收到后闹钟有没有排回来」。
 *
 * 为什么需要：ColorOS 的「自启动」白名单 App 读不到，光看接收器有没有被禁用证明不了
 * 重启后到底能不能跑；而这个能力一旦失效，表现是「定时任务不准时」，用户很难察觉。
 * 每次开机由 [DiagnosticsPatrolReceiver] 落一份记录，自检页据此把「自启动」从「未实测」
 * 升级成「正常」，读不到就老实显示未实测。
 */
internal object BootReceipt {

    private const val FILE_NAME = "boot-receipt.json"

    data class Stats(
        val lastAt: Long = 0L,
        val installAt: Long = 0L,
        val alarmRescheduled: Boolean = false,
        val receivers: Int = 0,
    )

    private fun file(context: Context): File =
        File(context.filesDir, FILE_NAME)

    fun load(context: Context): Stats? = runCatching {
        val text = file(context).readText()
        val json = JSONObject(text)
        Stats(
            lastAt = json.optLong("lastAt", 0L),
            installAt = json.optLong("installAt", 0L),
            alarmRescheduled = json.optBoolean("alarmRescheduled", false),
            receivers = json.optInt("receivers", 0),
        )
    }.getOrNull()

    /** 开机时调用；[alarmRescheduled] 表示开机后确实把巡检/备份闹钟重新排上了。 */
    fun record(context: Context, alarmRescheduled: Boolean, receivers: Int) {
        runCatching {
            val app = context.applicationContext
            val previous = load(app)
            val installAt = previous?.installAt?.takeIf { it > 0L } ?: installTime(app)
            val json = JSONObject()
                .put("lastAt", System.currentTimeMillis())
                .put("installAt", installAt)
                .put("alarmRescheduled", alarmRescheduled)
                .put("receivers", receivers)
            file(app).writeText(json.toString())
        }
    }

    /** 装包时间：用 APK 的 mtime，比 PackageInfo 的 lastUpdateTime 稳（后者覆盖安装才变）。 */
    private fun installTime(context: Context): Long = runCatching {
        File(context.applicationInfo.sourceDir).lastModified()
    }.getOrDefault(0L)

    fun describe(stats: Stats): String {
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val whenText = format.format(Date(stats.lastAt))
        return "上次开机 $whenText · 闹钟${if (stats.alarmRescheduled) "已重排" else "未重排"}"
    }
}
