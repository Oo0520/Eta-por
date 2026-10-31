package io.github.mangi.eta.agent.terminal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Linux 工具环境的安装执行宿主。
 *
 * 之前安装协程挂在页面的 `rememberCoroutineScope()` 上：一退出设置页，协程就被取消，
 * 下载随之中断（进度条停在原地）。这里改成进程级作用域——退页面、切后台都不影响，
 * 只有进程真正结束才会停；配合前台执行租约还能避免系统冻结后台。
 *
 * [state] 把「后台正在装什么、装到哪」暴露给界面：页面重建后仍能显示，而不是像以前
 * 那样一切回页面就只剩「基础工具已就绪」。`tryAdopt/release` 同时兼作互斥，
 * 安装进行中再点按钮会被告知「已有安装任务在后台进行」，不会并行启动两套 apt。
 */
internal object LinuxInstallRunner {

    /** 一条可跨页面重建保留的安装状态；detail 为空时至少显示标题。 */
    data class UiState(val title: String, val detail: String? = null)

    private val supervisor = SupervisorJob()
    val scope = CoroutineScope(supervisor + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow<UiState?>(null)
    val state: StateFlow<UiState?> = _state

    val busy: Boolean get() = _state.value != null

    /** 抢占安装权；已有任务在跑返回 false。 */
    fun tryAdopt(title: String): Boolean {
        while (true) {
            if (_state.value != null) return false
            if (_state.compareAndSet(null, UiState(title))) return true
        }
    }

    /** 更新进度描述（安装器回调里调用）。 */
    fun updateDetail(detail: String?) {
        _state.update { it?.copy(detail = detail) }
    }

    fun release() {
        _state.value = null
    }
}
