package io.github.mangi.eta.agent.model

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * 请求快照：把每一轮真正发给模型的请求留一份本地副本。
 *
 * 记录内容：每条 system 消息全文、每个工具的名称与描述全文、其余消息只记 role 与长度、
 * 以及 provider/model/purpose 与三项字数统计。不记 API key、鉴权头与图片字节。
 *
 * 用途：确认提示词与工具描述真的生效、切换预设前后对比、排查"模型像用了旧提示词"、
 * 量化 system 与 tools 各占多少上下文。
 */
internal object AgentRequestSnapshot {
    private const val DIR = "prompt-snapshots"
    private const val DEFAULT_RETAIN = 20

    /** 上限：0 表示不记录快照；最高 30 份。 */
    const val MAX_RETAIN = 30
    private const val KEY_RETAIN = "agent_snapshot_retain"
    private const val MAX_BYTES = 8L * 1024 * 1024

    @Volatile
    private var appContext: Context? = null

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "eta-request-snapshot").apply { isDaemon = true }
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        runCatching { prune() }
    }

    fun directory(): File? {
        val context = appContext ?: return null
        return File(context.filesDir, DIR)
    }

    /** 发请求前调用；写盘在独立线程，不阻塞对话。 */
    fun capture(request: ProviderRequest, now: Long = System.currentTimeMillis()) {
        val context = appContext ?: return
        // 保留 0 份 = 不记录：直接不写盘，也不再清理（此时目录应当是空的/由 prune 清空）。
        if (retainLimit() == 0) return
        executor.execute { runCatching { write(context, request, now) } }
    }

    /** 最新在前。 */
    fun list(): List<File> {
        val dir = directory() ?: return emptyList()
        return dir.listFiles { file -> file.isFile && file.name.startsWith("snapshot-") }
            ?.sortedByDescending { it.name }
            .orEmpty()
    }

    fun read(file: File): JSONObject? =
        runCatching { JSONObject(file.readText()) }.getOrNull()

    /** 保留份数：界面上可选 10/20/50，取不到就用默认 20。 */
    fun retainLimit(): Int {
        val raw = runCatching {
            io.github.mangi.eta.config.Prefs.localAgentPreferences()?.getString(KEY_RETAIN, null)
        }.getOrNull()
        return raw?.toIntOrNull()?.coerceIn(0, MAX_RETAIN) ?: DEFAULT_RETAIN
    }

    fun setRetainLimit(limit: Int) {
        val value = limit.coerceIn(0, MAX_RETAIN).toString()
        runCatching {
            io.github.mangi.eta.config.Prefs.localAgentPreferences()
                ?.edit()?.putString(KEY_RETAIN, value)?.commit()
        }
        prune()
    }

    /** 对比基准跨页面共享：存在私有配置里，切页不会丢。 */
    fun baseName(): String? = runCatching {
        io.github.mangi.eta.config.Prefs.localAgentPreferences()?.getString("agent_snapshot_base", null)
    }.getOrNull()?.takeIf { it.isNotBlank() }

    fun setBase(name: String?) {
        runCatching {
            val editor = io.github.mangi.eta.config.Prefs.localAgentPreferences()?.edit() ?: return
            if (name == null) editor.remove("agent_snapshot_base") else editor.putString("agent_snapshot_base", name)
            editor.commit()
        }
    }

    fun delete(file: File): Boolean = runCatching { file.delete() }.getOrDefault(false)

    fun clearAll(): Int {
        val files = list()
        files.forEach { runCatching { it.delete() } }
        return files.size
    }

    private fun write(context: Context, request: ProviderRequest, now: Long) {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val system = JSONArray()
        val tools = JSONArray()
        val messages = JSONArray()
        var systemChars = 0
        var toolsChars = 0

        for (index in 0 until request.messages.length()) {
            val message = request.messages.optJSONObject(index) ?: continue
            val role = message.optString("role")
            val content = message.optString("content")
            if (role == "system") {
                system.put(
                    JSONObject()
                        .put("i", index)
                        .put("chars", content.length)
                        .put("text", content),
                )
                systemChars += content.length
            } else {
                messages.put(JSONObject().put("role", role).put("chars", content.length))
            }
        }

        val effectiveTools = request.effectiveTools
        for (index in 0 until effectiveTools.length()) {
            val function = effectiveTools.optJSONObject(index)?.optJSONObject("function") ?: continue
            val description = function.optString("description")
            tools.put(
                JSONObject()
                    .put("name", function.optString("name"))
                    .put("chars", description.length)
                    .put("description", description),
            )
            toolsChars += description.length
        }

        // 同一次对话里会混着不同工具集的请求（主对话 185 个、自动复盘 2 个、纯聊天 0 个）。
        // 抓一个"配置指纹"，让对比只跟同配置的请求比，不会拿 185 个工具的去比 2 个工具的。
        val signature = runCatching {
            val joined = (0 until tools.length())
                .map { tools.getJSONObject(it).optString("name") }
                .sorted()
                .joinToString(",")
            java.security.MessageDigest.getInstance("SHA-256")
                .digest((request.purpose.name + "|" + joined).toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte) }
                .take(10)
        }.getOrDefault("")

        val config = request.effectiveConfig
        val json = JSONObject()
            .put("at", now)
            .put("signature", signature)
            .put("session", request.sessionId)
            .put("purpose", request.purpose.name)
            .put("provider", config.providerType)
            .put("model", config.model)
            .put("endpoint", config.openAiEndpointMode)
            .put("system", system)
            .put("tools", tools)
            .put("messages", messages)
            .put(
                "counts",
                JSONObject()
                    .put("system", system.length())
                    .put("system_chars", systemChars)
                    .put("tools", tools.length())
                    .put("tools_chars", toolsChars)
                    .put("messages", messages.length()),
            )

        File(dir, "snapshot-$now.json").writeText(json.toString())
        prune(dir)
    }

    private fun prune(target: File? = directory()) {
        val dir = target ?: return
        val files = dir.listFiles { file -> file.isFile && file.name.startsWith("snapshot-") }
            ?.sortedByDescending { it.name }
            .orEmpty()
        var total = 0L
        val limit = retainLimit()
        files.forEachIndexed { index, file ->
            total += file.length()
            if (index >= limit || total > MAX_BYTES) runCatching { file.delete() }
        }
    }
}
