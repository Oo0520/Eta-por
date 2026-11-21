package io.github.mangi.eta.agent.confirm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.mangi.eta.core.AndroidAgentLogger

/** 通知上的「批准 / 拒绝」按钮落点。 */
internal class ConfirmActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val token = intent.getStringExtra(EXTRA_TOKEN) ?: return
        val handled = when (intent.action) {
            ACTION_APPROVE -> AgentConfirmGuard.approve(context, token)
            ACTION_REJECT -> AgentConfirmGuard.reject(context, token)
            else -> false
        }
        AndroidAgentLogger.info("confirm.action action=${intent.action} handled=$handled")
    }

    companion object {
        const val ACTION_APPROVE = "io.github.mangi.eta.action.CONFIRM_APPROVE"
        const val ACTION_REJECT = "io.github.mangi.eta.action.CONFIRM_REJECT"
        const val EXTRA_TOKEN = "token"
    }
}
