package io.github.mangi.eta.agent.display.dashboard

import android.content.Context
import android.view.MotionEvent
import io.github.mangi.eta.agent.display.VirtualScreenInputPump
import io.github.mangi.eta.agent.display.VirtualScreenViewport
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

/**
 * 注入门。AI 侧每次注入前问一句，用户接管期间一律拒绝。
 *
 * 接线点：`VirtualScreenSession.execute()` 里 `action in setOf("tap","swipe","touch","long_press")`
 * 且调用方是模型（`manual == false`）时 `require(AgentInjectionGate.isOpen())`，
 * 被拦时返回 `USER_TAKEOVER`，让模型知道「人接手了，先等」。
 */
internal object AgentInjectionGate {
    private val forcedClosed = AtomicBoolean(false)

    fun close() {
        forcedClosed.set(true)
    }

    fun open() {
        forcedClosed.set(false)
    }

    /**
     * 门是否放行。
     *
     * 判定以状态机为准，而不是一个粘滞布尔：只有「用户正在接管 / 交还收尾 / 等待确认」
     * 这三种态才真的封门。任务挂起、空闲、结束都不封 ——
     * 否则一次误报（比如流体云卡片被系统回收）会把注入面永久焊死，
     * 连界面工具自己都点不动（2026102703 实测踩到）。
     */
    fun isOpen(): Boolean {
        val mode = AgentLiveCoordinator.currentMode() ?: return true
        val needsBlock = mode == AgentControlMode.USER_TAKEOVER ||
            mode == AgentControlMode.HANDING_BACK ||
            mode == AgentControlMode.AWAITING_CONFIRM
        if (!needsBlock) return true
        return !forcedClosed.get()
    }
}

/**
 * 接管触摸桥：把悬浮窗上的手指动作，按 1:1 映射回 VirtualDisplay 并注入。
 *
 * 关键行为：
 * - 一切坐标经 [VirtualScreenViewport] 换算，FIT_CENTER 的留白区不参与（返回 false 让窗口拖动接管）；
 * - 用户一按下就抢占控制权（`onPreempt`），再补这一帧 DOWN，避免 AI 与人的手势互相踩；
 * - MOVE 只保留最新一格（[pendingMove]），拖动时不会把队列撑爆，也不会出现「慢半拍」；
 * - 抬指/取消/多指一律做手势收尾，绝不留半截 DOWN 在虚拟屏里。
 */
internal class TakeoverTouchBridge(
    private val context: Context,
    private val sessionId: String,
    private val stateSnapshot: () -> AgentDashboardState,
    private val onPreempt: () -> Unit,
) {
    private var viewport: VirtualScreenViewport? = null
    private val pendingMove = AtomicReference<VirtualScreenViewport.Point?>(null)
    private val moving = AtomicBoolean(false)
    private val pressed = AtomicBoolean(false)
    private var lastPoint: VirtualScreenViewport.Point? = null

    fun updateLayout(viewWidth: Int, viewHeight: Int, displayWidth: Int, displayHeight: Int) {
        viewport = VirtualScreenViewport.fit(viewWidth, viewHeight, displayWidth, displayHeight)
    }

    fun mapPoint(x: Float, y: Float, clamp: Boolean = false): VirtualScreenViewport.Point? =
        viewport?.toDisplay(x, y, clamp)

    /** 供上层做「点哪儿了」的调试与可视化。 */
    fun isPressed(): Boolean = pressed.get()

    fun onTouchEvent(event: MotionEvent): Boolean {
        val vp = viewport ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val point = vp.toDisplay(event.x, event.y) ?: return false
                if (!stateSnapshot().mode.inputOwnedByUser) onPreempt()
                pressed.set(true)
                lastPoint = point
                submit("down", point)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!pressed.get()) return false
                val point = vp.toDisplay(event.x, event.y, clamp = true) ?: return true
                lastPoint = point
                pendingMove.set(point)
                pumpMoves()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!pressed.compareAndSet(true, false)) return false
                pendingMove.set(null)
                val point = vp.toDisplay(event.x, event.y, clamp = true) ?: lastPoint
                if (point != null) {
                    submit(if (event.actionMasked == MotionEvent.ACTION_CANCEL) "cancel" else "up", point)
                }
                lastPoint = null
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // 单指协议，多指只做收尾：不留半截 DOWN 给虚拟屏。
                val point = lastPoint
                if (pressed.compareAndSet(true, false) && point != null) submit("cancel", point)
                return true
            }
        }
        return false
    }

    /** 交还控制权前把在飞手势收干净。 */
    fun cancelInflight() {
        pendingMove.set(null)
        val point = lastPoint
        if (pressed.compareAndSet(true, false) && point != null) submit("cancel", point)
        lastPoint = null
    }

    private fun pumpMoves() {
        if (!moving.compareAndSet(false, true)) return
        val point = pendingMove.getAndSet(null)
        if (point == null) {
            moving.set(false)
            return
        }
        submit("move", point) {
            moving.set(false)
            if (pendingMove.get() != null) pumpMoves()
        }
    }

    private fun submit(
        phase: String,
        point: VirtualScreenViewport.Point,
        onDone: ((Boolean) -> Unit)? = null,
    ) {
        val args = JSONObject()
            .put("action", "touch")
            .put("phase", phase)
            .put("x", point.x)
            .put("y", point.y)
        VirtualScreenInputPump.submit(context, sessionId, args, onDone)
    }
}
