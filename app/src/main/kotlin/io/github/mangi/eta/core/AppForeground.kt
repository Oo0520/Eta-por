package io.github.mangi.eta.core

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.concurrent.atomic.AtomicInteger

/**
 * 进程内「用户是否正看着 Eta」的最轻量判断。
 *
 * 只服务一个决策：审批该不该发系统通知。
 * - 用户就在 App 里 → 会话末尾的审批卡已经在他眼前，再叠一条通知纯属噪音；
 * - 用户不在 → 通知是唯一能找到他的路。
 *
 * 用 started/stopped 计数而不是 resumed/paused：Activity 切换时前者不会出现
 * 「先 resume 新的、后 pause 旧的」那种瞬时假前台。多窗口下也只会多算，不会漏算。
 */
internal object AppForeground {

    private val startedCount = AtomicInteger(0)

    val isForeground: Boolean get() = startedCount.get() > 0

    fun install(application: Application) {
        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) {
                    startedCount.incrementAndGet()
                }

                override fun onActivityStopped(activity: Activity) {
                    startedCount.updateAndGet { if (it > 0) it - 1 else 0 }
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
    }
}
