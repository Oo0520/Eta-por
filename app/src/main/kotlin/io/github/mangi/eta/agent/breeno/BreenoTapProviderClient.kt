package io.github.mangi.eta.agent.breeno

import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.agent.model.AgentProviderClient
import io.github.mangi.eta.agent.model.AssistantBlockKind
import io.github.mangi.eta.agent.model.EndpointKind
import io.github.mangi.eta.agent.model.ProviderCapabilities
import io.github.mangi.eta.agent.model.ProviderEvent
import io.github.mangi.eta.agent.model.ProviderRequest
import io.github.mangi.eta.agent.model.ProviderResponse
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.config.Prefs
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * 把「小布助手」包装成一个 Eta 模型通道。
 *
 * 不是 HTTP provider：请求经 [BreenoTapClient] 投给小布，回答由小布进程内的
 * 只读旁路推回。小布不流式吐字，因此拿到终稿后一次性补齐 block 事件。
 */
internal object BreenoTapProviderClient : AgentProviderClient {

    override val id: String = "breeno_tap"

    override val capabilities: ProviderCapabilities = ProviderCapabilities(
        endpoint = EndpointKind.CHAT_COMPLETIONS,
        streamingText = true,
        streamingToolCalls = false,
        imageInput = false,
        toolResultImages = false,
        strictTools = false,
        parallelToolCalls = false,
    )

    override fun complete(
        request: ProviderRequest,
        runController: AgentRunController,
        onEvent: (ProviderEvent) -> Unit,
    ): ProviderResponse {
        require(borrowEnabled()) {
            "先在 设置 → 厂商助手兼容 里打开「借用小布模型」"
        }
        val prompt = lastUserText(request.messages)
        require(prompt.isNotBlank()) { "小布模型只支持文本提问" }

        runController.throwIfCancelled()
        onEvent(ProviderEvent.RequestStarted)
        val text = runBlocking { BreenoTapClient.ask(prompt) }
        runController.throwIfCancelled()

        onEvent(ProviderEvent.BlockStart(AssistantBlockKind.TEXT, 0))
        onEvent(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 0, text))
        onEvent(ProviderEvent.BlockEnd(AssistantBlockKind.TEXT, 0, content = text))
        val assistantMessage = JSONObject()
            .put("role", "assistant")
            .put("content", text)
            .put("reasoning_content", "")
            .put("finish_reason", "stop")
        onEvent(ProviderEvent.Completed("stop"))
        return ProviderResponse(assistantMessage)
    }

    private fun borrowEnabled(): Boolean {
        // 读不到 RemotePreferences（模块服务未注入）时不要误拦，交给 hook 侧判断开关。
        val prefs = Prefs.remotePreferencesForUi(EtaApp.serviceInstance) ?: return true
        return prefs.getBoolean(Prefs.Keys.AGENT_BORROW_BREENO, false)
    }

    private fun lastUserText(messages: JSONArray): String {
        for (index in messages.length() - 1 downTo 0) {
            val message = messages.optJSONObject(index) ?: continue
            if (message.optString("role") != "user") continue
            when (val content = message.opt("content")) {
                is String -> if (content.isNotBlank()) return content
                is JSONArray -> {
                    val builder = StringBuilder()
                    for (partIndex in 0 until content.length()) {
                        val part = content.optJSONObject(partIndex) ?: continue
                        if (part.optString("type") != "text") continue
                        builder.append(part.optString("text"))
                    }
                    if (builder.isNotBlank()) return builder.toString()
                }
            }
        }
        return ""
    }
}
