package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import org.json.JSONArray

/** 重试只包围模型请求；完整响应返回前不提交历史或执行本地工具。 */
internal class AgentModelRetry(
    private val nanoTime: () -> Long = System::nanoTime,
    private val waitBeforeRetry: (AgentRunController, Long) -> Unit = { controller, delay ->
        controller.awaitRetryDelay(delay)
    },
) {
    data class Result(val round: Int, val response: ProviderResponse)

    fun complete(
        initialRound: Int,
        initialRequest: ProviderRequest,
        provider: AgentProviderClient,
        controller: AgentRunController,
        onEvent: (AgentEvent) -> Unit,
        onProviderEvent: (Int, ProviderEvent) -> Unit,
        discardAttemptReasoning: () -> Unit,
    ): Result {
        var round = initialRound
        var retries = 0
        var request = initialRequest
        // 一次 complete 只抓一份快照：重试复用同一次调用的进程内证据。
        var snapshotTaken = false
        while (true) {
            controller.throwIfCancelled()
            onEvent(AgentEvent.RoundStarted(round, request.messages.length()))
            if (!snapshotTaken) {
                snapshotTaken = true
                AgentRequestSnapshot.capture(request)
            }
            var hostedToolStarted = false
            var callbackFailed = false
            var startedAt = nanoTime()
            var firstDeltaAt = 0L
            var usage: AgentTokenUsage? = null
            try {
                val response = provider.complete(request, controller) { event ->
                    if (event == ProviderEvent.RequestStarted) {
                        startedAt = nanoTime()
                        firstDeltaAt = 0L
                    }
                    if (event is ProviderEvent.BlockDelta && firstDeltaAt == 0L) firstDeltaAt = nanoTime()
                    if (event is ProviderEvent.Usage) usage = (usage ?: AgentTokenUsage()).merge(event.usage)
                    if (event is ProviderEvent.HostedToolStarted) hostedToolStarted = true
                    try {
                        onProviderEvent(round, event)
                    } catch (failure: Exception) {
                        callbackFailed = true
                        throw failure
                    }
                }
                usage?.let {
                    try {
                        onEvent(AgentEvent.UsageReceived(round, it.enrich(request.messages, request.effectiveTools, startedAt, firstDeltaAt, nanoTime())))
                    } catch (failure: Exception) {
                        callbackFailed = true
                        throw failure
                    }
                }
                return Result(round, response)
            } catch (failure: Exception) {
                controller.throwIfCancelled()
                if (callbackFailed || Thread.currentThread().isInterrupted) throw failure
                val classified = AgentModelFailure.transport(failure) ?: throw failure
                if (hostedToolStarted) throw AgentModelFailure(
                    classified.code, false, classified.message.orEmpty(), classified,
                )
                if (!classified.retryable) throw classified
                if (retries == MAX_RETRIES) {
                    throw AgentModelFailure(
                        classified.code, false,
                        "${classified.message} 已重试 $MAX_RETRIES 次仍未恢复，已保留此前完成的工具结果。",
                        classified,
                    )
                }
                retries += 1
                val delayMs = BASE_DELAY_MS shl (retries - 1)
                onEvent(AgentEvent.ModelRetryScheduled(round, retries, MAX_RETRIES, delayMs.toInt(), classified.code))
                waitBeforeRetry(controller, delayMs)
                controller.throwIfCancelled()
                // 展示保留失败尝试，模型上下文与最终思考摘要只接纳成功尝试。
                discardAttemptReasoning()
                round += 1
            }
        }
    }

    /**
     * 给本次响应定稿用量：请求耗时、首 token 延迟，以及上下文构成估算。
     *
     * 这一段是**唯一**带耗时的事件（流式期间的 usage 没有时长），所以构成也必须在这里补齐，
     * 否则后到的这份会把先到的估算覆盖成空 —— 面板就会只剩头部和进度条。
     */
    private fun AgentTokenUsage.enrich(
        messages: JSONArray,
        tools: JSONArray,
        startedAt: Long,
        firstDeltaAt: Long,
        finishedAt: Long,
    ): AgentTokenUsage {
        val composition = AgentContextComposition.estimate(messages, tools)
        val enriched = copy(
            requestDurationMs = ((finishedAt - startedAt) / 1_000_000).coerceAtLeast(1),
            firstTokenMs = firstDeltaAt.takeIf { it > 0 }
                ?.let { at -> ((at - startedAt) / 1_000_000).coerceAtLeast(1) },
        )
        if (composition.isEmpty) return enriched
        return enriched.copy(
            systemPromptTokens = composition.systemPromptTokens,
            toolDefinitionTokens = composition.toolDefinitionTokens,
            conversationTokens = composition.conversationTokens,
        )
    }

    companion object {
        private const val MAX_RETRIES = 3
        private const val BASE_DELAY_MS = 2_000L
    }
}
