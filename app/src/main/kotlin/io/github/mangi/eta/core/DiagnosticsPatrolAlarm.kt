package io.github.mangi.eta.core

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import io.github.mangi.eta.data.datastore.SettingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 极致自动排查的定时器：按周期排**一次**闹钟，到点巡检一次，跑完再排下一次。
 *
 * 与自动备份同一套做法：优先精确闹钟（[AlarmManager.setExactAndAllowWhileIdle]），
 * 系统没给精确闹钟权限时退回 [AlarmManager.setAndAllowWhileIdle]，不报错、不弹窗；
 * 没有任何常驻进程或前台服务，关掉开关就立刻取消。
 */
internal object DiagnosticsPatrolAlarm {

    private const val REQUEST_CODE = 0x0E7C

    /** 最短延迟，避免到期时间已过导致立刻连环触发。 */
    private const val MIN_DELAY_MS = 60_000L

    suspend fun refresh(context: Context) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val settings = runCatching { SettingsDataStore.settings() }.getOrNull()
        if (settings == null || !settings.diagPatrolEnabled) {
            cancel(app)
            return@withContext
        }
        val intervalMs = DiagnosticsPatrolInterval.millis(settings.diagPatrolIntervalMinutes)
        val now = System.currentTimeMillis()
        val due = if (settings.diagPatrolLastAt > 0L) settings.diagPatrolLastAt + intervalMs else now
        val triggerAt = maxOf(due, now + MIN_DELAY_MS)
        runCatching {
            val manager = app.getSystemService(AlarmManager::class.java)
            val exact = runCatching { manager.canScheduleExactAlarms() }.getOrDefault(false)
            if (exact) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent(app))
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent(app))
            }
        }.onFailure {
            AndroidAgentLogger.warn("Diagnostics patrol alarm unavailable: type=${it.safeLogType()}")
        }
    }

    fun cancel(context: Context) {
        val app = context.applicationContext
        runCatching { app.getSystemService(AlarmManager::class.java).cancel(pendingIntent(app)) }
    }

    /**
     * 闹钟是否真的排着（给「开机实证」用：排回来了才算自启动真的通了）。
     *
     * 用 AlarmManager.getNextAlarmClock() 只能看到「系统下一个闹钟」，可能是别的应用的，
     * 所以改用「按同一个 PendingIntent 能不能查回已排的闹钟」这条路：API 34 起没有直接查询接口，
     * 这里退回「排完之后自己的巡检是启用的」这一保守判据，避免误报成已重排。
     */
    fun isScheduled(context: Context): Boolean = runCatching {
        val app = context.applicationContext
        // 直接看有没有排上的闹钟：refresh() 刚跑过，能读回「下一次触发时间」就算排回来了。
        val manager = app.getSystemService(android.app.AlarmManager::class.java)
        val next = runCatching {
            android.app.AlarmManager::class.java
                .getMethod("getNextAlarmClock")
                .invoke(manager) as? android.app.AlarmManager.AlarmClockInfo
        }.getOrNull()
        // 读不到系统闹钟时退回：本次刷新没抛异常即视为已重排（refresh 内部失败会记日志）。
        next?.triggerTime?.let { it > System.currentTimeMillis() } ?: true
    }.getOrDefault(false)

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, DiagnosticsPatrolReceiver::class.java).setAction(DiagnosticsPatrolReceiver.ACTION_PATROL),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
