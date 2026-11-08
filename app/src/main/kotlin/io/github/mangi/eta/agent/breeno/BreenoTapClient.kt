package io.github.mangi.eta.agent.breeno

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import io.github.mangi.eta.agent.runtime.AgentAppContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/**
 * 把小布当成一个模型调用。
 *
 * 主通道（静默）：广播 → 小布进程内的 hook 直接构造并发送 query，**不打开任何界面**；
 * 兜底通道（仅当静默无响应）：`ACTION_PROCESS_TEXT` 投进小布对话页。
 * 回答统一由 hook 的只读旁路经 [BreenoTapBus] 回来。
 */
internal object BreenoTapClient {

    private const val TAG = "EtaBreeno"
    private const val TARGET_PACKAGE = "com.heytap.speechassist"
    private const val TARGET_ACTIVITY = "$TARGET_PACKAGE.sharereceive.AIChatShareReceiveActivity"
    private const val DEEPLINK_BASE = "aichat://speechassist.heytap.com"

    /** 与小布进程内 BreenoQueryInjector 保持一致。 */
    private const val ACTION_QUERY = "io.github.mangi.eta.action.BREENO_QUERY"
    private const val EXTRA_PROMPT = "prompt"
    private const val ACTION_ENGINE_API = "com.heytap.speechassist.action.ENGINE_API"

    private const val DEFAULT_TIMEOUT_MS = 120_000L

    suspend fun ask(prompt: String, timeoutMillis: Long = DEFAULT_TIMEOUT_MS): String = coroutineScope {
        val normalized = prompt.trim()
        require(normalized.isNotEmpty()) { "小布模型收到的提示词为空" }
        val context: Context = AgentAppContext.resolve() ?: error("Eta 进程上下文不可用")

        val startedAt = System.currentTimeMillis()
        // UNDISPATCHED：先把订阅挂上，再投喂，避免无 replay 时丢事件。
        val answer = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            BreenoTapBus.events
                .filter {
                    // 只认本次提问之后产生的回包；小布卡片里的 query 可能为空，
                    // 投喂串行，空 query 也认。
                    it.at >= startedAt &&
                        (it.query.isBlank() || it.query.trim() == normalized)
                }
                .first()
                .text
        }

        dispatchSilently(context, normalized)
        withTimeout(timeoutMillis) { answer.await() }
            .also { Log.i(TAG, "小布静默回包 chars=${it.length}") }
    }

    /** 拉起小布进程 + 发广播让它自己发一条 query（不弹界面）。 */
    private fun dispatchSilently(context: Context, prompt: String) {
        runCatching {
            context.startService(
                Intent(ACTION_ENGINE_API).setPackage(TARGET_PACKAGE),
            )
        }.onFailure { Log.w(TAG, "拉起小布服务失败: ${it.javaClass.simpleName}") }

        val broadcast = Intent(ACTION_QUERY)
            .setPackage(TARGET_PACKAGE)
            .putExtra(EXTRA_PROMPT, prompt)
        context.sendBroadcast(broadcast)
        Log.i(TAG, "静默投喂 promptChars=${prompt.length}")
    }

    private fun launchBreeno(context: Context, prompt: String) {
        val processText = Intent(Intent.ACTION_PROCESS_TEXT).apply {
            setComponent(ComponentName(TARGET_PACKAGE, TARGET_ACTIVITY))
            type = "text/plain"
            putExtra(Intent.EXTRA_PROCESS_TEXT, prompt)
            putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val launched = runCatching { context.startActivity(processText) }.isSuccess
        if (launched) return
        val deeplink = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("$DEEPLINK_BASE?input_text=${Uri.encode(prompt)}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(deeplink)
    }
}
