package io.github.mangi.eta.agent.terminal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Linux 工具环境的安装执行宿主。
 *
 * 之前安装协程挂在页面的 `rememberCoroutineScope()` 上：一退出设置页，协程就被取消，
 * 下载随之中断（进度条停在原地）。这里改成进程级作用域——退页面、切后台都不影响，
 * 只有进程真正结束才会停；配合前台执行租约还能避免系统冻结后台。
 *
 * `tryAdopt/release` 做互斥：安装进行中再次点击会被告知“已有任务在后台进行”，
 * 不会并行启动两套 apt，避免互相抢 dpkg 锁。
 */
internal object LinuxInstallRunner {
    private val supervisor = SupervisorJob()
    val scope = CoroutineScope(supervisor + Dispatchers.Main.immediate)
    private val running = AtomicBoolean(false)

    val busy: Boolean get() = running.get()

    /** 抢占安装权；已有任务在跑返回 false。 */
    fun tryAdopt(): Boolean = running.compareAndSet(false, true)

    fun release() {
        running.set(false)
    }
}
