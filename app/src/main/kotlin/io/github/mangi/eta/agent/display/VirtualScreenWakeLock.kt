package io.github.mangi.eta.agent.display

import android.content.Context
import android.os.PowerManager

/**
 * 灵屏运行期间的「不息屏」保活。
 *
 * 只在会话存在、且用户在设置里打开时持有；用 SCREEN_DIM 而不是 SCREEN_BRIGHT：
 * 屏幕会变暗但不会熄灭，既不中断 Agent 操作，也不会一直亮到刺眼。
 *
 * 为什么不用 Activity 的 FLAG_KEEP_SCREEN_ON：灵屏的主体是悬浮窗 + 接管页，
 * 悬浮窗根本没有 Activity 窗口可挂；接管页自己也短暂，靠 WakeLock 更统一。
 */
internal object VirtualScreenWakeLock {
    private var wakeLock: PowerManager.WakeLock? = null

    fun sync(context: Context, enabled: Boolean) {
        if (enabled) acquire(context) else release()
    }

    private fun acquire(context: Context) {
        val current = wakeLock
        if (current?.isHeld == true) return
        val outcome = runCatching {
            val pm = context.applicationContext.getSystemService(PowerManager::class.java)
                ?: error("POWER_SERVICE 不可用")
            wakeLock = pm.newWakeLock(
                PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
                "eta:lingscreen:keepon",
            ).apply {
                setReferenceCounted(false)
                // 兜底上限：即使 release 漏掉，也不会整夜占着屏幕。
                acquire(4 * 60 * 60 * 1000L)
            }
            "held"
        }
        VscrDiag.line("不息屏 acquire " + outcome.getOrElse { "失败 " + it })
    }

    fun release() {
        val current = wakeLock ?: return
        VscrDiag.line("不息屏 release")
        wakeLock = null
        runCatching { if (current.isHeld) current.release() }
    }
}
