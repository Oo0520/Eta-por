package io.github.mangi.eta.agent.display.dashboard

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 控制权状态机：仪表盘的唯一真相源。
 *
 * 约束（对应重构目标 2、4）：
 * 1. [AgentControlMode.aiMayInject] 是注入层唯一放行条件，AI 每次注入前都要问一遍；
 * 2. 用户接管与 AI 执行互斥，切换必经 HANDING_BACK，先把手里的手势收干净再交权；
 * 3. 敏感操作走 AWAITING_CONFIRM，此时既不允许 AI 继续注入，也不允许静默接管；
 * 4. 流体云卡片被划掉（含 ColorOS 侧 9251018 类拒绝）一律落 SUSPENDED，
 *    不销毁会话，用户回 App 还能接着干。
 */
internal enum class AgentControlMode {
    IDLE,
    AI_RUNNING,
    AI_PAUSED,
    AWAITING_CONFIRM,
    USER_TAKEOVER,
    HANDING_BACK,
    SUSPENDED,
    TERMINATED,
    ;

    /** AI 是否允许往虚拟屏注入事件。 */
    val aiMayInject: Boolean get() = this == AI_RUNNING

    /** 触摸归用户所有（接管中，以及交还前的收尾窗口）。 */
    val inputOwnedByUser: Boolean get() = this == USER_TAKEOVER || this == HANDING_BACK

    /** 仪表盘是否该出现在悬浮窗上。 */
    val visible: Boolean get() = this != IDLE && this != TERMINATED

    /**
     * 面向用户的四态（+ 三个补充态）。
     * 浮窗、接管页、流体云共用这一份文案，不允许各自拼字符串。
     */
    val label: String
        get() = when (this) {
            IDLE -> "空闲"
            AI_RUNNING -> "AI 执行中"
            AI_PAUSED -> "AI 已暂停"
            AWAITING_CONFIRM -> "需要人"
            USER_TAKEOVER -> "人在操作"
            HANDING_BACK -> "正在交还"
            SUSPENDED -> "已挂起"
            TERMINATED -> "已结束"
        }
}

internal enum class ConfirmRisk { MONEY, PRIVACY, IRREVERSIBLE }

internal data class ConfirmField(
    val label: String,
    val value: String,
    val editable: Boolean = false,
)

/** 一次决策拦截：字段是给用户看「到底要发生什么」的最小信息集。 */
internal data class PendingConfirm(
    val token: String,
    val risk: ConfirmRisk,
    val title: String,
    val fields: List<ConfirmField>,
    /** 真实画面局部截图的本地路径，不用生成式卡片。 */
    val previewPath: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val deadlineAt: Long = createdAt + 120_000L,
) {
    fun expired(now: Long = System.currentTimeMillis()): Boolean = now >= deadlineAt
}

internal data class AgentDashboardState(
    val mode: AgentControlMode = AgentControlMode.IDLE,
    val runId: String? = null,
    val sessionId: String? = null,
    /** 该 run 所属会话：接管卡靠它落到同一张会话卡上，而不是另占一个 ID。 */
    val conversationKey: String = "",
    val step: String = "",
    val stepIndex: Int = 0,
    val stepTotal: Int = 0,
    val confirm: PendingConfirm? = null,
    val takeoverStartedAt: Long = 0L,
    val suspendedReason: String = "",
    val steerCount: Int = 0,
) {
    val progress: Float get() = if (stepTotal <= 0) 0f else (stepIndex.toFloat() / stepTotal).coerceIn(0f, 1f)

    val needsAttention: Boolean
        get() = mode == AgentControlMode.AWAITING_CONFIRM || mode == AgentControlMode.SUSPENDED
}

internal sealed interface DashboardEvent {
    data class RunStarted(
        val runId: String,
        val sessionId: String,
        val stepTotal: Int = 0,
        val conversationKey: String = "",
    ) : DashboardEvent
    data class StepChanged(val text: String, val index: Int, val total: Int = 0) : DashboardEvent
    data class RunFinished(val success: Boolean) : DashboardEvent

    /** 用户点了「接管控制」。 */
    data object TakeoverRequested : DashboardEvent

    /** 用户点了「交还给 AI」。 */
    data object HandBackRequested : DashboardEvent

    /** 上层把在飞手势收干净了，这时才真正回到 AI。 */
    data object HandBackCompleted : DashboardEvent

    /** 用户在画面里按住 120ms：不点按钮，直接接管。 */
    data object UserPressedContent : DashboardEvent

    data class ConfirmRequested(val confirm: PendingConfirm) : DashboardEvent
    data class ConfirmResolved(val token: String, val approved: Boolean) : DashboardEvent

    /** 流体云卡片被划掉或被系统回收。 */
    data class LiveCardDismissed(val reason: String) : DashboardEvent

    data class SteerSubmitted(val text: String) : DashboardEvent

    /** 用户按了「暂停」：只是停下执行，控制权仍在 AI 手里。 */
    data object PauseRequested : DashboardEvent

    /** 从暂停恢复执行。 */
    data object ResumeFromPause : DashboardEvent

    /**
     * 执行侧真源的暂停变化：输入框、别的卡片、通知栏点暂停都会收敛到这里。
     * 只同步展示，不回发 PauseRuntime，避免「点一下来回跑两次」。
     */
    data class RunPausedChanged(val runId: String, val paused: Boolean, val source: String) : DashboardEvent

    /** 从挂起（卡片被拒/被回收）恢复。 */
    data object ResumeRequested : DashboardEvent
    data object TerminateRequested : DashboardEvent
}

internal sealed interface DashboardEffect {
    data object PauseRuntime : DashboardEffect
    data object ResumeRuntime : DashboardEffect
    data object StopRuntime : DashboardEffect

    /** 关闭注入门：AI 侧下一次注入会被拒绝。 */
    data object CloseInjectionGate : DashboardEffect
    data object OpenInjectionGate : DashboardEffect

    /** 让触摸桥补一个 CANCEL，别把残留下压点留给用户。 */
    data object CancelInflightTouch : DashboardEffect

    data object OpenConfirmSheet : DashboardEffect
    data class EmitSteer(val text: String) : DashboardEffect
    data class PublishLive(val spec: AgentLiveSpec) : DashboardEffect
    data object ClearLive : DashboardEffect
    data class Notice(val code: String) : DashboardEffect
}

internal object AgentControlMachine {

    fun reduce(
        state: AgentDashboardState,
        event: DashboardEvent,
    ): Pair<AgentDashboardState, List<DashboardEffect>> = when (event) {
        is DashboardEvent.RunStarted -> {
            val next = state.copy(
                mode = AgentControlMode.AI_RUNNING,
                runId = event.runId,
                sessionId = event.sessionId,
                conversationKey = event.conversationKey,
                step = "准备中",
                stepIndex = 0,
                stepTotal = event.stepTotal,
                confirm = null,
                suspendedReason = "",
            )
            next to listOf(
                DashboardEffect.OpenInjectionGate,
                DashboardEffect.PublishLive(liveOf(next)),
            )
        }

        is DashboardEvent.StepChanged -> {
            if (state.mode == AgentControlMode.TERMINATED) {
                state to emptyList()
            } else {
                val next = state.copy(
                    step = event.text,
                    stepIndex = event.index,
                    stepTotal = if (event.total > 0) event.total else state.stepTotal,
                )
                next to listOf(DashboardEffect.PublishLive(liveOf(next)))
            }
        }

        is DashboardEvent.RunFinished -> {
            val keepUserControl =
                state.mode == AgentControlMode.USER_TAKEOVER || state.mode == AgentControlMode.HANDING_BACK
            val next = state.copy(
                mode = if (keepUserControl) state.mode else AgentControlMode.IDLE,
                runId = null,
                step = if (event.success) "已完成" else "已结束",
                stepIndex = state.stepTotal,
            )
            next to listOf(DashboardEffect.ClearLive)
        }

        DashboardEvent.TakeoverRequested -> when (state.mode) {
            AgentControlMode.AWAITING_CONFIRM -> state to listOf(DashboardEffect.Notice("CONFIRM_PENDING"))
            AgentControlMode.AI_RUNNING, AgentControlMode.AI_PAUSED -> {
                val next = state.copy(
                    mode = AgentControlMode.USER_TAKEOVER,
                    takeoverStartedAt = System.currentTimeMillis(),
                )
                next to listOf(
                    DashboardEffect.PauseRuntime,
                    DashboardEffect.CloseInjectionGate,
                    DashboardEffect.CancelInflightTouch,
                    DashboardEffect.PublishLive(liveOf(next)),
                )
            }
            else -> state to emptyList()
        }

        DashboardEvent.UserPressedContent -> when (state.mode) {
            AgentControlMode.AI_RUNNING -> {
                val next = state.copy(
                    mode = AgentControlMode.USER_TAKEOVER,
                    takeoverStartedAt = System.currentTimeMillis(),
                )
                next to listOf(
                    DashboardEffect.PauseRuntime,
                    DashboardEffect.CloseInjectionGate,
                    DashboardEffect.CancelInflightTouch,
                    DashboardEffect.Notice("AUTO_TAKEOVER"),
                )
            }
            else -> state to emptyList()
        }

        DashboardEvent.HandBackRequested -> when (state.mode) {
            AgentControlMode.USER_TAKEOVER ->
                state.copy(mode = AgentControlMode.HANDING_BACK) to listOf(DashboardEffect.CancelInflightTouch)
            else -> state to emptyList()
        }

        DashboardEvent.HandBackCompleted -> when (state.mode) {
            AgentControlMode.HANDING_BACK, AgentControlMode.USER_TAKEOVER -> {
                val next = state.copy(
                    mode = if (state.runId != null) AgentControlMode.AI_RUNNING else AgentControlMode.IDLE,
                    takeoverStartedAt = 0L,
                )
                next to listOf(
                    DashboardEffect.OpenInjectionGate,
                    DashboardEffect.ResumeRuntime,
                    DashboardEffect.Notice("AI_RESUMED"),
                )
            }
            else -> state to emptyList()
        }

        is DashboardEvent.ConfirmRequested -> when (state.mode) {
            AgentControlMode.AI_RUNNING, AgentControlMode.AI_PAUSED -> {
                val next = state.copy(
                    mode = AgentControlMode.AWAITING_CONFIRM,
                    confirm = event.confirm,
                )
                next to listOf(
                    DashboardEffect.PauseRuntime,
                    DashboardEffect.CloseInjectionGate,
                    DashboardEffect.CancelInflightTouch,
                    DashboardEffect.PublishLive(liveOf(next)),
                    DashboardEffect.OpenConfirmSheet,
                )
            }
            else -> state to emptyList()
        }

        is DashboardEvent.ConfirmResolved -> {
            val confirm = state.confirm
            if (state.mode != AgentControlMode.AWAITING_CONFIRM || confirm == null || confirm.token != event.token) {
                state to listOf(DashboardEffect.Notice("CONFIRM_TOKEN_STALE"))
            } else if (event.approved) {
                val next = state.copy(mode = AgentControlMode.AI_RUNNING, confirm = null, suspendedReason = "")
                next to listOf(
                    DashboardEffect.OpenInjectionGate,
                    DashboardEffect.ResumeRuntime,
                    DashboardEffect.PublishLive(liveOf(next)),
                )
            } else {
                val next = state.copy(
                    mode = AgentControlMode.SUSPENDED,
                    confirm = null,
                    suspendedReason = "用户取消",
                )
                next to listOf(
                    DashboardEffect.PauseRuntime,
                    DashboardEffect.CloseInjectionGate,
                    DashboardEffect.StopRuntime,
                    DashboardEffect.ClearLive,
                )
            }
        }

        is DashboardEvent.LiveCardDismissed -> when (state.mode) {
            // 只有「等用户拍板」时卡片消失才算用户拒绝/无视，这时必须停手。
            AgentControlMode.AWAITING_CONFIRM -> {
                val next = state.copy(mode = AgentControlMode.SUSPENDED, suspendedReason = event.reason)
                next to listOf(
                    DashboardEffect.PauseRuntime,
                    DashboardEffect.CloseInjectionGate,
                    DashboardEffect.CancelInflightTouch,
                    DashboardEffect.Notice("SUSPENDED:" + event.reason),
                )
            }
            // 其它态卡片被系统回收只是展示层掉了，不该改变执行权。
            AgentControlMode.IDLE, AgentControlMode.TERMINATED -> state to emptyList()
            else -> state to listOf(DashboardEffect.Notice("LIVE_CARD_GONE:" + event.reason))
        }

        // 外部入口改的暂停：只认自己这张卡对应的 run，别的会话一律不插手。
        is DashboardEvent.RunPausedChanged -> {
            if (state.runId.isNullOrBlank() || state.runId != event.runId) {
                state to emptyList()
            } else {
                when {
                    state.mode == AgentControlMode.AI_RUNNING && event.paused -> {
                        val next = state.copy(mode = AgentControlMode.AI_PAUSED)
                        next to listOf(DashboardEffect.PublishLive(liveOf(next)))
                    }
                    state.mode == AgentControlMode.AI_PAUSED && !event.paused -> {
                        val next = state.copy(mode = AgentControlMode.AI_RUNNING)
                        next to listOf(DashboardEffect.PublishLive(liveOf(next)))
                    }
                    else -> state to emptyList()
                }
            }
        }

        DashboardEvent.PauseRequested -> when (state.mode) {
            AgentControlMode.AI_RUNNING -> {
                val next = state.copy(mode = AgentControlMode.AI_PAUSED, step = state.step)
                next to listOf(DashboardEffect.PauseRuntime, DashboardEffect.PublishLive(liveOf(next)))
            }
            else -> state to emptyList()
        }

        DashboardEvent.ResumeFromPause -> when (state.mode) {
            AgentControlMode.AI_PAUSED -> {
                val next = state.copy(mode = AgentControlMode.AI_RUNNING)
                next to listOf(
                    DashboardEffect.ResumeRuntime,
                    DashboardEffect.OpenInjectionGate,
                    DashboardEffect.PublishLive(liveOf(next)),
                )
            }
            else -> state to emptyList()
        }

        is DashboardEvent.SteerSubmitted -> {
            val text = event.text.trim()
            if (text.isEmpty()) {
                state to emptyList()
            } else {
                state.copy(steerCount = state.steerCount + 1, step = "已收到指令") to
                    listOf(DashboardEffect.EmitSteer(text))
            }
        }

        DashboardEvent.ResumeRequested -> when (state.mode) {
            AgentControlMode.SUSPENDED -> {
                val canRun = state.runId != null
                val next = state.copy(
                    mode = if (canRun) AgentControlMode.AI_RUNNING else AgentControlMode.IDLE,
                    suspendedReason = "",
                )
                next to if (canRun) {
                    listOf(
                        DashboardEffect.OpenInjectionGate,
                        DashboardEffect.ResumeRuntime,
                        DashboardEffect.PublishLive(liveOf(next)),
                    )
                } else {
                    listOf(DashboardEffect.ClearLive)
                }
            }
            else -> state to emptyList()
        }

        DashboardEvent.TerminateRequested -> state.copy(
            mode = AgentControlMode.TERMINATED,
            runId = null,
            confirm = null,
            step = "已终止",
        ) to listOf(
            DashboardEffect.CancelInflightTouch,
            DashboardEffect.CloseInjectionGate,
            DashboardEffect.StopRuntime,
            DashboardEffect.ClearLive,
        )
    }

    private fun liveOf(state: AgentDashboardState): AgentLiveSpec {
        val mode = when (state.mode) {
            AgentControlMode.AI_PAUSED -> LiveMode.PAUSED
            AgentControlMode.AWAITING_CONFIRM -> LiveMode.NEEDS_CONFIRM
            AgentControlMode.SUSPENDED -> LiveMode.SUSPENDED
            AgentControlMode.USER_TAKEOVER, AgentControlMode.HANDING_BACK -> LiveMode.TAKEN_OVER
            else -> LiveMode.NORMAL
        }
        return AgentLiveSpec(
            token = state.confirm?.token ?: state.runId.orEmpty(),
            runId = state.runId.orEmpty(),
            title = state.confirm?.title ?: "Eta 正在执行",
            step = state.step,
            stepIndex = state.stepIndex,
            stepTotal = state.stepTotal,
            mode = mode,
            confirm = state.confirm,
            conversationKey = state.conversationKey,
        )
    }
}

/** 线程安全外壳：UI 只 dispatch，不自己改状态。 */
internal class AgentControlStore {
    private val mutableState = MutableStateFlow(AgentDashboardState())
    val state: StateFlow<AgentDashboardState> = mutableState.asStateFlow()

    private val mutableEffects = MutableSharedFlow<DashboardEffect>(extraBufferCapacity = 32)
    val effects: SharedFlow<DashboardEffect> = mutableEffects.asSharedFlow()

    fun dispatch(event: DashboardEvent) {
        while (true) {
            val current = mutableState.value
            val (next, effects) = AgentControlMachine.reduce(current, event)
            if (mutableState.compareAndSet(current, next)) {
                effects.forEach { mutableEffects.tryEmit(it) }
                return
            }
        }
    }

    /** 注入层的快速判断：AI 侧每次注入前调用。 */
    fun aiMayInject(): Boolean = mutableState.value.mode.aiMayInject
}
