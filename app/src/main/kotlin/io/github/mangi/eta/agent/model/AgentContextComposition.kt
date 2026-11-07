package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 上下文构成估算：把「这次真正发出去的请求」拆成系统提示词 / 工具定义 / 对话消息三份。
 *
 * - 只读，不改请求：消息数组与工具目录都不会被改动；
 * - 口径与 [AgentHistoryTrimmer.estimateTokens] 一致（CJK 1 字 1 token，其余 4 字符 1 token），
 *   面板上因此统一带「~」前缀，表示量级而不是服务端实测值；
 * - 服务端返回的上下文 token 是实测值，两者可能对不上（图片、缓存、供应商模板等都会被折算掉），
 *   面板用「实测总量 + 构成比例」画条，不去强行凑平。
 */
internal object AgentContextComposition {

    data class Estimate(
        val systemPromptTokens: Int = 0,
        val toolDefinitionTokens: Int = 0,
        val conversationTokens: Int = 0,
    ) {
        val total: Int
            get() = systemPromptTokens + toolDefinitionTokens + conversationTokens

        val isEmpty: Boolean
            get() = total <= 0
    }

    /**
     * 直接从请求本身估算：系统段按「前导 role=system 的消息」判定。
     *
     * 请求侧只发一次真实的 payload，所以这里不需要调用方再另外传段数；
     * 重试时 `request.messages` 就是这一次要发的内容，估出来的比例与真正发出的请求一致。
     */
    fun estimate(messages: JSONArray, tools: JSONArray): Estimate =
        estimate(messages, leadingSystemCount(messages), tools)

    /**
     * @param messages 完整请求消息（含系统段与最后一条当前输入）
     * @param systemCount 前多少条是系统消息
     * @param tools 本轮发出去的工具目录
     */
    fun estimate(messages: JSONArray, systemCount: Int, tools: JSONArray): Estimate {
        val length = messages.length()
        val systemEnd = systemCount.coerceIn(0, length)
        var system = 0
        for (index in 0 until systemEnd) {
            system += messageTokens(messages.optJSONObject(index))
        }
        var conversation = 0
        for (index in systemEnd until length) {
            conversation += messageTokens(messages.optJSONObject(index))
        }
        val toolTokens = if (tools.length() == 0) 0 else {
            AgentHistoryTrimmer.estimateTokens(tools.toString())
        }
        return Estimate(
            systemPromptTokens = system,
            toolDefinitionTokens = toolTokens,
            conversationTokens = conversation,
        )
    }

    /** 前导系统消息条数；role 缺失时停止计数，避免把普通消息当系统段。 */
    private fun leadingSystemCount(messages: JSONArray): Int {
        var count = 0
        while (count < messages.length()) {
            val role = messages.optJSONObject(count)?.optString("role").orEmpty()
            if (role != "system") break
            count += 1
        }
        return count
    }

    /** 单条消息的估算：正文 + 工具调用参数 + 思考内容，跳过 JSON 键名这类固定开销。 */
    private fun messageTokens(message: JSONObject?): Int {
        if (message == null) return 0
        val rawContent = message.opt("content")
        val content = when {
            rawContent == null || rawContent === JSONObject.NULL -> ""
            rawContent is String -> rawContent
            else -> rawContent.toString()
        }
        var total = AgentHistoryTrimmer.estimateTokens(content)
        message.optJSONArray("tool_calls")?.let { total += AgentHistoryTrimmer.estimateTokens(it.toString()) }
        val reasoning = message.optString("reasoning_content")
        if (reasoning.isNotEmpty()) total += AgentHistoryTrimmer.estimateTokens(reasoning)
        return total
    }
}
