package io.github.mangi.eta.agent.runtime

internal data class AgentTokenUsage(
    val contextTokens: Int? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val reasoningTokens: Int? = null,
    val cachedTokens: Int? = null,
    val requestDurationMs: Long? = null,
    /**
     * 上下文构成估算：本次请求里系统提示词、工具定义（schema）与对话消息各占多少 token。
     *
     * 由请求侧按实际发出去的内容折算（CJK 1 字 1 token、其余 4 字符 1 token），
     * 只用于上下文面板的量级展示；服务端返回的 [contextTokens] 仍是唯一口径的实测值。
     */
    val systemPromptTokens: Int? = null,
    val toolDefinitionTokens: Int? = null,
    val conversationTokens: Int? = null,
    /** 本次 run 累计的首 token 延迟（毫秒）：每个请求「发出 → 第一个增量」之和。 */
    val firstTokenMs: Long? = null,
    /** 本次 run 累计的工具执行耗时（毫秒）。 */
    val toolDurationMs: Long? = null,
    /** 本次 run 里已计入用量的请求数（工具循环每多一轮就多一次）。 */
    val requestCount: Int? = null,
) {
    fun merge(other: AgentTokenUsage): AgentTokenUsage = AgentTokenUsage(
        other.contextTokens ?: contextTokens, other.inputTokens ?: inputTokens,
        other.outputTokens ?: outputTokens, other.reasoningTokens ?: reasoningTokens,
        other.cachedTokens ?: cachedTokens, other.requestDurationMs ?: requestDurationMs,
        other.systemPromptTokens ?: systemPromptTokens,
        other.toolDefinitionTokens ?: toolDefinitionTokens,
        other.conversationTokens ?: conversationTokens,
        other.firstTokenMs ?: firstTokenMs,
        other.toolDurationMs ?: toolDurationMs,
        other.requestCount ?: requestCount,
    )

    val isEmpty: Boolean
        get() = contextTokens == null &&
            inputTokens == null &&
            outputTokens == null &&
            reasoningTokens == null &&
            cachedTokens == null

    /** 三段构成是否至少拿到一段（拿到才画分段条与明细行）。 */
    val hasContextComposition: Boolean
        get() = systemPromptTokens != null ||
            toolDefinitionTokens != null ||
            conversationTokens != null
}
