package io.github.mangi.eta.agent.subagent

import io.github.mangi.eta.agent.runtime.AgentRunController
import java.util.concurrent.ConcurrentHashMap

/**
 * 记录正在运行的子智能体与它的运行控制器，并标出当前占用灵屏的那一个。
 *
 * 用途：把「人工接管结束」这类**只对当前灵屏持有者有意义**的消息，定向投递给正在干活的
 * 子智能体，而不是一律塞给父 Agent。父 Agent 收到时子代理还在跑，等于没说。
 */
internal object SubagentRunRegistry {

    private val controllers = ConcurrentHashMap<String, AgentRunController>()

    /** 当前占用灵屏的子智能体 id；同一时刻只可能有一个（灵屏是单例）。 */
    @Volatile
    private var screenOwnerId: String? = null

    fun register(subagentId: String, controller: AgentRunController, ownsScreen: Boolean) {
        controllers[subagentId] = controller
        if (ownsScreen) screenOwnerId = subagentId
    }

    fun unregister(subagentId: String) {
        controllers.remove(subagentId)
        if (screenOwnerId == subagentId) screenOwnerId = null
    }

    fun screenOwner(): String? = screenOwnerId

    /**
     * 把一条补充指令投给当前占用灵屏的子智能体。
     *
     * 走的是它自己的 steering 通道：不打断正在执行的工具批次，在下一个回合边界注入。
     *
     * @return true 表示已投递（或有子代理占用灵屏但投递失败时不适用），false 表示当前没有
     *         占用灵屏的子智能体，调用方应回退到父 Agent。
     */
    fun steerScreenOwner(text: String): Boolean {
        val id = screenOwnerId ?: return false
        val controller = controllers[id] ?: return false
        return controller.steer(text)
    }
}
