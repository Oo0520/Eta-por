package io.github.mangi.eta.hook.breeno

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import io.github.mangi.eta.agent.runtime.AgentAppContext
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

/**
 * 小布下行文本卡片的只读旁路（借用小布自己的模型）。
 *
 * 与 BreenoStreamRenderer 方向相反：
 * - 既有链路是「拦截用户 query → 用 Eta 的模型生成 → 注入小布 UI」；
 * - 这里只做拷贝：小布云端回来的 StreamTextCard 原样放行，同时把正文推给 Eta。
 *
 * 两个挂载点：
 * - [observeJson]：`MessageProcessor.processMessage` 的入站 JSON（云端下发主路径）；
 * - [observe]：`Agent.dispatchDirectives` 的对象列表（注入/派发路径，作补充）。
 *
 * 收口策略（实测小布这版 payload 不一定带 isFinal）：
 * 1. 有终帧标记 → 立刻发布；
 * 2. 没有终帧标记 → 1200ms 内没有新帧就视为结束，flush 发布。
 * 正文既可能是增量也可能是全量，按前缀关系自适应。
 */
internal object BreenoModelTap {

    /** 由模块 applicationId 拼出的 authority（beta 共存包同样适用）。 */
    const val AUTHORITY_SUFFIX = ".breeno-tap"
    const val METHOD_TAP = "tap"

    private const val NAMESPACE_MYAI = "MyAI"
    private const val NAME_STREAM_TEXT_CARD = "StreamTextCard"
    private const val MAX_BUFFER_CHARS = 60_000
    private const val IDLE_FLUSH_MS = 1_200L

    private data class TapKey(val roomId: String, val query: String)

    private val buffers = ConcurrentHashMap<TapKey, StringBuilder>()
    private val lastPublished = ConcurrentHashMap<TapKey, String>()
    private val seenLabels = ConcurrentHashMap.newKeySet<String>()
    private val pendingFlush = ConcurrentHashMap<TapKey, Runnable>()

    /** 最近一次 flush 的结果，挂在下一帧摘要里带出，便于排查「发布了但没到 Eta」。 */
    @Volatile
    private var lastFlushNote: String? = null
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * 入站 JSON 入口。返回需要写进模块日志的摘要（只在出现新指令名或真的投递时返回，
     * 避免每帧刷屏），没有值得记录的内容时返回 null。
     */
    fun observeJson(content: String?, authority: String): String? {
        if (content.isNullOrBlank()) return null
        val json = runCatching { JSONObject(content) }.getOrNull() ?: return null
        val directives = json.optJSONArray("directives") ?: return null
        if (directives.length() == 0) return null

        val notes = ArrayList<String>(4)
        var published = false
        for (index in 0 until directives.length()) {
            val directive = directives.optJSONObject(index) ?: continue
            val header = directive.optJSONObject("header")
            val namespace = header?.optString("namespace").orEmpty()
            val name = header?.optString("name").orEmpty()
            val label = "$namespace.$name"
            if (seenLabels.add(label)) notes += "new=$label"
            if (namespace != NAMESPACE_MYAI || name != NAME_STREAM_TEXT_CARD) continue

            val payload = directive.optJSONObject("payload") ?: continue
            val text = payload.optString("content")
            if (text.isEmpty()) continue
            val query = payload.optString("query")
            val roomId = payload.optString("roomId")
            val isFinal = payload.optBoolean("isFinal") ||
                payload.optBoolean("final") ||
                payload.optBoolean("isEnd")
            if (accumulate(authority, roomId, query, text, isFinal)) published = true
        }

        val flushNote = lastFlushNote
        lastFlushNote = null
        if (notes.isEmpty() && !published && flushNote == null) return null
        return "tap: ${notes.joinToString()} published=$published" +
            (flushNote?.let { " $it" } ?: "")
    }

    /** dispatchDirectives 入口（对象形态），行为与 [observeJson] 一致。 */
    fun observe(directives: Any?, authority: String): String? {
        val list = directives as? List<*> ?: return null
        var published = false
        val notes = ArrayList<String>(4)
        for (item in list) {
            val result = runCatching { observeDirective(item, authority) }.getOrNull() ?: continue
            if (result.first != null) notes += result.first!!
            if (result.second) published = true
        }
        if (notes.isEmpty() && !published) return null
        return "tap(directives): ${notes.joinToString()} published=$published"
    }

    private fun observeDirective(directive: Any?, authority: String): Pair<String?, Boolean> {
        directive ?: return null to false
        val header = read(directive, "getHeader") ?: return null to false
        val name = read(header, "getName") as? String ?: return null to false
        val label = "directive.$name"
        val isNew = seenLabels.add(label)
        if (name != NAME_STREAM_TEXT_CARD) return (if (isNew) "new=$label" else null) to false

        val payload = read(directive, "getPayload") ?: return null to false
        val content = read(payload, "getContent") as? String ?: return null to false
        if (content.isEmpty()) return null to false
        val query = read(payload, "getQuery") as? String ?: ""
        val roomId = read(payload, "getRoomId") as? String ?: ""
        val isFinal = read(payload, "getFinal") as? Boolean ?: false
        return (if (isNew) "new=$label" else null) to
            accumulate(authority, roomId, query, content, isFinal)
    }

    /**
     * 累积一帧文本；终帧时立刻投递，否则安排一次空闲 flush。
     * 返回是否在本次调用里真的投递了。
     */
    private fun accumulate(
        authority: String,
        roomId: String,
        query: String,
        content: String,
        isFinal: Boolean,
    ): Boolean {
        val key = TapKey(roomId = roomId, query = query)
        val builder = buffers.getOrPut(key) { StringBuilder() }
        val current = builder.toString()
        when {
            current.isEmpty() -> builder.append(content)
            // 全量模式：新内容包含已有内容，直接替换。
            content.startsWith(current) -> {
                builder.setLength(0)
                builder.append(content)
            }
            // 增量模式：已有内容包含新内容则视为重复帧，忽略。
            current.endsWith(content) && content.length <= 4 -> Unit
            else -> builder.append(content)
        }
        if (builder.length > MAX_BUFFER_CHARS) {
            buffers.remove(key)
            pendingFlush.remove(key)?.let { mainHandler.removeCallbacks(it) }
            lastPublished.remove(key)
            return false
        }

        pendingFlush.remove(key)?.let { mainHandler.removeCallbacks(it) }
        if (isFinal) {
            flush(key, authority)
            return true
        }
        val task = Runnable { flush(key, authority) }
        pendingFlush[key] = task
        mainHandler.postDelayed(task, IDLE_FLUSH_MS)
        return false
    }

    private fun flush(key: TapKey, authority: String) {
        pendingFlush.remove(key)
        val text = buffers.remove(key)?.toString().orEmpty()
        if (text.isBlank()) {
            lastFlushNote = "flush 空文本"
            return
        }
        if (lastPublished.put(key, text) == text) {
            lastFlushNote = "flush 去重跳过 chars=${text.length}"
            return
        }
        val delivered = publish(authority = authority, query = key.query, roomId = key.roomId, text = text)
        lastFlushNote = "flush chars=${text.length} delivered=$delivered"
    }

    private fun publish(authority: String, query: String, roomId: String, text: String): Boolean {
        val context = AgentAppContext.resolve() ?: return false
        return runCatching {
            context.contentResolver.call(
                Uri.parse("content://$authority/tap"),
                METHOD_TAP,
                null,
                Bundle().apply {
                    putString("query", query)
                    putString("roomId", roomId)
                    putString("text", text)
                },
            )
            true
        }.getOrDefault(false)
    }

    private fun read(target: Any, methodName: String): Any? {
        val method = findNoArgMethod(target.javaClass, methodName) ?: return null
        return runCatching { method.invoke(target) }.getOrNull()
    }

    private fun findNoArgMethod(clazz: Class<*>, methodName: String): Method? {
        var current: Class<*>? = clazz
        while (current != null) {
            current.declaredMethods.firstOrNull { method ->
                method.name == methodName && method.parameterTypes.isEmpty()
            }?.let { method ->
                method.isAccessible = true
                return method
            }
            current = current.superclass
        }
        return null
    }
}
