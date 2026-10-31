package io.github.mangi.eta.agent.display.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentControlMachineTest {

    private fun running(): AgentDashboardState =
        AgentControlMachine.reduce(
            AgentDashboardState(),
            DashboardEvent.RunStarted(runId = "run-1", sessionId = "session-1", stepTotal = 5),
        ).first

    @Test
    fun takeoverClosesGateAndPausesRuntime() {
        val (state, effects) = AgentControlMachine.reduce(running(), DashboardEvent.TakeoverRequested)

        assertEquals(AgentControlMode.USER_TAKEOVER, state.mode)
        assertTrue(state.mode.inputOwnedByUser)
        assertFalse(state.mode.aiMayInject)
        assertTrue(effects.contains(DashboardEffect.PauseRuntime))
        assertTrue(effects.contains(DashboardEffect.CloseInjectionGate))
        assertTrue(effects.contains(DashboardEffect.CancelInflightTouch))
    }

    @Test
    fun pendingConfirmRejectsTakeover() {
        val pending = running().copy(
            mode = AgentControlMode.AWAITING_CONFIRM,
            confirm = PendingConfirm("token-1", ConfirmRisk.MONEY, "提交订单", emptyList()),
        )
        val (state, effects) = AgentControlMachine.reduce(pending, DashboardEvent.TakeoverRequested)

        assertEquals(AgentControlMode.AWAITING_CONFIRM, state.mode)
        assertEquals(listOf(DashboardEffect.Notice("CONFIRM_PENDING")), effects)
    }

    @Test
    fun handBackRequiresHandingBackState() {
        val taken = AgentControlMachine.reduce(running(), DashboardEvent.TakeoverRequested).first

        val (handingBack, effects) = AgentControlMachine.reduce(taken, DashboardEvent.HandBackRequested)
        assertEquals(AgentControlMode.HANDING_BACK, handingBack.mode)
        assertFalse(handingBack.mode.aiMayInject)
        assertTrue(effects.contains(DashboardEffect.CancelInflightTouch))

        val (back, backEffects) = AgentControlMachine.reduce(handingBack, DashboardEvent.HandBackCompleted)
        assertEquals(AgentControlMode.AI_RUNNING, back.mode)
        assertTrue(back.mode.aiMayInject)
        assertTrue(backEffects.contains(DashboardEffect.OpenInjectionGate))
        assertTrue(backEffects.contains(DashboardEffect.ResumeRuntime))
    }

    @Test
    fun liveCardDismissSuspendsOnlyWhileWaitingConfirm() {
        val pending = running().copy(
            mode = AgentControlMode.AWAITING_CONFIRM,
            confirm = PendingConfirm("token-live", ConfirmRisk.MONEY, "提交订单", emptyList()),
        )
        val (state, effects) = AgentControlMachine.reduce(
            pending,
            DashboardEvent.LiveCardDismissed("user_dismiss"),
        )

        assertEquals(AgentControlMode.SUSPENDED, state.mode)
        assertEquals("run-1", state.runId)
        assertFalse(state.mode.aiMayInject)
        assertTrue(effects.contains(DashboardEffect.PauseRuntime))
        // 挂起后可以从 App 侧恢复
        val (resumed, _) = AgentControlMachine.reduce(state, DashboardEvent.ResumeRequested)
        assertEquals(AgentControlMode.AI_RUNNING, resumed.mode)
    }

    @Test
    fun liveCardGoneWhileRunningKeepsExecutionRight() {
        // 卡片被系统回收只是展示层掉了，不许影响正在跑的任务，更不许封注入门。
        val (state, effects) = AgentControlMachine.reduce(
            running(),
            DashboardEvent.LiveCardDismissed("app_cancel"),
        )

        assertEquals(AgentControlMode.AI_RUNNING, state.mode)
        assertTrue(state.mode.aiMayInject)
        assertFalse(effects.contains(DashboardEffect.CloseInjectionGate))
        assertTrue(effects.contains(DashboardEffect.Notice("LIVE_CARD_GONE:app_cancel")))
    }

    @Test
    fun confirmApproveResumesRejectStops() {
        val pending = running().copy(
            mode = AgentControlMode.AWAITING_CONFIRM,
            confirm = PendingConfirm("token-2", ConfirmRisk.MONEY, "提交订单", emptyList()),
        )

        val (approved, _) = AgentControlMachine.reduce(pending, DashboardEvent.ConfirmResolved("token-2", true))
        assertEquals(AgentControlMode.AI_RUNNING, approved.mode)
        assertTrue(approved.mode.aiMayInject)

        val (rejected, effects) = AgentControlMachine.reduce(pending, DashboardEvent.ConfirmResolved("token-2", false))
        assertEquals(AgentControlMode.SUSPENDED, rejected.mode)
        assertTrue(effects.contains(DashboardEffect.StopRuntime))
    }

    @Test
    fun staleTokenNeverApproves() {
        val pending = running().copy(
            mode = AgentControlMode.AWAITING_CONFIRM,
            confirm = PendingConfirm("token-3", ConfirmRisk.MONEY, "提交订单", emptyList()),
        )
        val (state, effects) = AgentControlMachine.reduce(pending, DashboardEvent.ConfirmResolved("token-9", true))

        assertEquals(AgentControlMode.AWAITING_CONFIRM, state.mode)
        assertEquals(listOf(DashboardEffect.Notice("CONFIRM_TOKEN_STALE")), effects)
    }
}
