package io.github.mangi.eta.agent.runtime

import android.os.Handler
import android.os.Looper
import io.github.mangi.eta.core.EtaTrace
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 运行控制状态的唯一真源，按 runId 记账。
 *
 * 约定：
 * 1. 写入只发生在执行侧（[AgentRuntimeService]）：暂停/恢复落到 controller 之后才改这里；
 * 2. 输入框、流体云、仪表盘、胶囊都只读，点击只发指令，不再各自维护布尔；
 * 3. 一个会话暂停不影响另一个会话：pausedRuns 是集合而不是全局开关。
 */
internal data class RunControlState(
    val pausedRuns: Set<String> = emptySet(),
    val conversationOfRun: Map<String, String> = emptyMap(),
) {
    fun isPaused(runId: String?): Boolean = runId != null && runId in pausedRuns

    fun pausedForConversation(conversationId: String?): Boolean =
        conversationId != null && pausedRuns.any { conversationOfRun[it] == conversationId }

    fun runIdOfConversation(conversationId: String?): String? {
        if (conversationId == null) return null
        return conversationOfRun.entries.firstOrNull { it.value == conversationId }?.key
    }
}

internal object RunControl {

    private val _state = MutableStateFlow(RunControlState())
    val state: StateFlow<RunControlState> = _state.asStateFlow()

    private val listeners = CopyOnWriteArrayList<(RunControlState) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 记住 run 属于哪个会话：输入框和卡片靠它把「会话」映射回 runId。 */
    fun bindRun(runId: String, conversationKey: String) {
        if (runId.isBlank() || conversationKey.isBlank()) return
        update { current ->
            if (current.conversationOfRun[runId] == conversationKey) {
                current
            } else {
                current.copy(conversationOfRun = current.conversationOfRun + (runId to conversationKey))
            }
        }
    }

    /** 唯一权威写点：执行侧确认暂停/恢复后调用。同值幂等。 */
    fun setPaused(runId: String, value: Boolean, source: String) {
        if (runId.isBlank()) return
        var changed = false
        update { current ->
            val paused = current.pausedRuns
            val next = if (value) paused + runId else paused - runId
            if (next == paused) {
                current
            } else {
                changed = true
                current.copy(pausedRuns = next)
            }
        }
        if (changed) {
            EtaTrace.log(
                "run.control",
                "runId=$runId ${if (value) "暂停" else "恢复"} from=$source " +
                    "剩余暂停=${_state.value.pausedRuns.size}",
            )
        }
    }

    /** 单个 run 结束或被停止：只清它自己，别的会话不受影响。 */
    fun clearRun(runId: String, reason: String) {
        if (runId.isBlank()) return
        update { current ->
            if (runId !in current.pausedRuns && runId !in current.conversationOfRun) {
                current
            } else {
                current.copy(
                    pausedRuns = current.pausedRuns - runId,
                    conversationOfRun = current.conversationOfRun - runId,
                )
            }
        }
        EtaTrace.log("run.control", "runId=$runId 清理 from=$reason")
    }

    fun observe(consumer: (RunControlState) -> Unit) {
        listeners += consumer
        mainHandler.post { consumer(_state.value) }
    }

    fun unobserve(consumer: (RunControlState) -> Unit) {
        listeners -= consumer
    }

    private fun update(transform: (RunControlState) -> RunControlState) {
        while (true) {
            val current = _state.value
            val next = transform(current)
            if (next == current) return
            if (_state.compareAndSet(current, next)) {
                notifyListeners(next)
                return
            }
        }
    }

    private fun notifyListeners(next: RunControlState) {
        if (listeners.isEmpty()) return
        mainHandler.post {
            listeners.forEach { listener -> runCatching { listener(next) } }
        }
    }
}
