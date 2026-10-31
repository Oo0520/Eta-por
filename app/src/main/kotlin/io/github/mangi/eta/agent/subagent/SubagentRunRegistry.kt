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

    /** 子智能体所属会话：输入框的目标胶囊必须按会话过滤，否则会串到别的对话里。 */
    private val conversationOf = ConcurrentHashMap<String, String>()

    /** 处于暂停态的子智能体 id（只用于界面回显，真正的挂起由控制器内部完成）。 */
    private val pausedIds: MutableSet<String> =
        java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /** 当前占用灵屏的子智能体 id；同一时刻只可能有一个（灵屏是单例）。 */
    @Volatile
    private var screenOwnerId: String? = null

    fun register(
        conversationKey: String,
        subagentId: String,
        controller: AgentRunController,
        ownsScreen: Boolean,
    ) {
        controllers[subagentId] = controller
        conversationOf[subagentId] = conversationKey
        if (ownsScreen) screenOwnerId = subagentId
    }

    fun unregister(subagentId: String) {
        controllers.remove(subagentId)
        conversationOf.remove(subagentId)
        pausedIds.remove(subagentId)
        if (screenOwnerId == subagentId) screenOwnerId = null
    }

    /**
     * 暂停指定子智能体：它在下一个检查点（下一轮开始 / 下一步之前）挂起，进度不丢。
     *
     * @return false 表示它已经结束。
     */
    fun pause(subagentId: String): Boolean {
        val controller = controllers[subagentId] ?: return false
        controller.pause()
        pausedIds.add(subagentId)
        return true
    }

    /** 继续执行被暂停的子智能体；返回 false 表示它已经结束。 */
    fun resume(subagentId: String): Boolean {
        val controller = controllers[subagentId] ?: return false
        controller.resume()
        pausedIds.remove(subagentId)
        return true
    }

    fun isPaused(subagentId: String): Boolean = pausedIds.contains(subagentId)

    /** 每个子智能体最近一次进度快照（进程内）。会话切走导致消息更新丢失时，界面靠它兜底。 */
    private val latestSnapshots = ConcurrentHashMap<String, String>()

    fun publishSnapshot(subagentId: String, snapshotJson: String) {
        latestSnapshots[subagentId] = snapshotJson
    }

    fun latestSnapshot(subagentId: String): String? = latestSnapshots[subagentId]

    fun screenOwner(): String? = screenOwnerId

    /**
     * 指定会话里存活的子智能体 id 列表。
     *
     * 必须按会话过滤：注册表是进程级的，不过滤会把别的对话正在跑的子代理也列到当前输入框上。
     */
    fun liveIds(conversationKey: String?): List<String> {
        val key = conversationKey?.takeIf { it.isNotBlank() } ?: return emptyList()
        return controllers.keys.filter { conversationOf[it] == key }
    }

    /** 该子智能体是否还活着（控制器仍在册）。 */
    fun isAlive(subagentId: String): Boolean = controllers.containsKey(subagentId)

    /**
     * 向指定子智能体投递一条补充指令。
     *
     * 和定向接管同一条通道：在下一个回合边界注入，不打断正在执行的工具批次。
     *
     * @return false 表示它已经结束（或 id 不存在），调用方应提示用户。
     */
    fun steer(subagentId: String, text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        val controller = controllers[subagentId] ?: return false
        return controller.steer(trimmed)
    }

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
