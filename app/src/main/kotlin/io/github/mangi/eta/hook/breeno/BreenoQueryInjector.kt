package io.github.mangi.eta.hook.breeno

import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.UUID

/**
 * 主动向小布投一条 query（不打开任何界面）。
 *
 * 思路：小布自己发 query 走的是 `MessageQueueManager.send(Message)`。
 * 我们已经在 outbound hook 里拿到了真实调用点和真实 Message，于是：
 * 1. 缓存最近一条「用户文本提问」的 Message 作为模板；
 * 2. 注入时按模板重建一条同结构消息，只替换 `Nlp.Text` 的正文、换一个 recordId；
 * 3. 用缓存下来的 send 方法与 MessageQueueManager 实例发出去。
 *
 * 这样小布完全按自己的链路跑（含它自己的账号、额度和云端模型），Eta 只做旁路收口。
 */
internal object BreenoQueryInjector {

    /** Eta 主进程 → 小布进程的一次注入请求（广播）。 */
    const val ACTION_QUERY = "io.github.mangi.eta.action.BREENO_QUERY"
    const val EXTRA_PROMPT = "prompt"

    @Volatile
    private var sendMethod: java.lang.reflect.Method? = null

    @Volatile
    private var queueManager: Any? = null

    @Volatile
    private var templateArgs: Array<Any?>? = null

    /** 注入期间为 true：outbound hook 据此跳过模板缓存与自定义模型接管。 */
    @Volatile
    private var injecting = false

    fun isInjecting(): Boolean = injecting

    @Volatile
    private var template: Any? = null

    @Volatile
    private var templateAt: Long = 0L

    /** outbound hook 每次触发时刷新发送目标（实例与完整参数表随进程变化）。 */
    fun rememberSendTarget(
        method: java.lang.reflect.Method,
        thisObject: Any?,
        args: Array<Any?>,
    ) {
        sendMethod = method
        queueManager = thisObject
        templateArgs = args
    }

    /** 只缓存「带 Nlp.Text 的用户提问」消息，避免把系统事件当模板。 */
    fun rememberTemplate(message: Any?) {
        if (message == null) return
        if (!hasTextEvent(message)) return
        template = message
        templateAt = System.currentTimeMillis()
    }

    fun hasTemplate(): Boolean = template != null

    fun templateAgeMillis(): Long =
        if (template == null) -1L else System.currentTimeMillis() - templateAt

    fun canInject(): Boolean = template != null && sendMethod != null && templateArgs != null

    /**
     * 投一条 query。返回是否成功调用到小布的发送方法。
     * 失败原因交给调用方写日志，这里不抛。
     */
    fun inject(prompt: String): String? {
        val text = prompt.trim()
        if (text.isEmpty()) return "prompt 为空"
        val source = template ?: return "没有模板（小布还没发过文本提问）"
        val method = sendMethod ?: return "没有发送方法"
        val args = templateArgs ?: return "没有发送参数表"

        // 直接复用真实 Message 实例：小布对 Message 内部状态（final 字段/嵌套事件）有要求，
        // 重建出来的副本在构造上可能缺字段，发出去不会真的开一轮对话。
        val outgoing = source
        if (!rewriteText(outgoing, text)) return "模板里找不到 Nlp.Text 可改写"
        rotateRecordIds(outgoing)

        val callArgs = args.copyOf()
        if (callArgs.isEmpty()) return "发送参数表为空"
        callArgs[0] = outgoing
        injecting = true
        return try {
            runCatching {
                method.isAccessible = true
                method.invoke(queueManager, *callArgs)
                null
            }.getOrElse { throwable ->
                val cause = throwable.cause ?: throwable
                "invoke 失败: ${cause.javaClass.simpleName} ${cause.message.orEmpty().take(80)}"
            }
        } finally {
            injecting = false
        }
    }

    private fun rebuild(source: Any): Any? {
        val clazz = source.javaClass
        val copy = runCatching {
            clazz.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        }.getOrNull() ?: return null
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            current.declaredFields.forEach { field ->
                if (Modifier.isStatic(field.modifiers) || Modifier.isFinal(field.modifiers)) return@forEach
                runCatching {
                    field.isAccessible = true
                    field.set(copy, field.get(source))
                }
            }
            current = current.superclass
        }
        return copy
    }

    private fun rewriteText(message: Any, text: String): Boolean {
        var rewritten = false
        forEachEvent(message) { event ->
            val header = noArgs(event, "getHeader") ?: return@forEachEvent
            val namespace = asString(noArgs(header, "getNamespace"))
            val name = asString(noArgs(header, "getName"))
            if (namespace != "Nlp" || name != "Text") return@forEachEvent
            val payload = noArgs(event, "getPayload") ?: return@forEachEvent
            if (setString(payload, "setText", text)) rewritten = true
        }
        return rewritten
    }

    private fun rotateRecordIds(message: Any) {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(12)
        setString(message, "setRecordId", suffix)
        setString(message, "setOriginalRecordId", suffix)
    }

    private fun hasTextEvent(message: Any): Boolean {
        var found = false
        forEachEvent(message) { event ->
            val header = noArgs(event, "getHeader") ?: return@forEachEvent
            if (
                asString(noArgs(header, "getNamespace")) == "Nlp" &&
                asString(noArgs(header, "getName")) == "Text"
            ) {
                found = true
            }
        }
        return found
    }

    private inline fun forEachEvent(message: Any, block: (Any) -> Unit) {
        val events = noArgs(message, "getEvents") as? Iterable<*> ?: return
        events.forEach { event -> if (event != null) block(event) }
    }

    private fun noArgs(target: Any, methodName: String): Any? {
        val method = findMethod(target.javaClass, methodName) ?: return null
        return runCatching { method.invoke(target) }.getOrNull()
    }

    private fun setString(target: Any, methodName: String, value: String): Boolean {
        val method = findMethod(target.javaClass, methodName, value) ?: return false
        return runCatching { method.invoke(target, value); true }.getOrDefault(false)
    }

    private fun asString(value: Any?): String = value as? String ?: ""

    private fun findMethod(clazz: Class<*>, name: String, vararg args: Any?): java.lang.reflect.Method? {
        var current: Class<*>? = clazz
        while (current != null) {
            val found = current.declaredMethods.firstOrNull { method ->
                method.name == name &&
                    method.parameterTypes.size == args.size &&
                    method.parameterTypes.zip(args).all { (type, arg) ->
                        arg == null || type.wrap().isAssignableFrom(arg.javaClass)
                    }
            }
            if (found != null) {
                found.isAccessible = true
                return found
            }
            current = current.superclass
        }
        return null
    }

    private fun Class<*>.wrap(): Class<*> = when (this) {
        java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
        Integer.TYPE -> Integer::class.java
        java.lang.Long.TYPE -> java.lang.Long::class.java
        java.lang.Double.TYPE -> java.lang.Double::class.java
        java.lang.Float.TYPE -> java.lang.Float::class.java
        java.lang.Short.TYPE -> java.lang.Short::class.java
        java.lang.Byte.TYPE -> java.lang.Byte::class.java
        Character.TYPE -> Character::class.java
        else -> this
    }

    @Suppress("unused")
    private fun fieldOf(clazz: Class<*>, name: String): Field? =
        runCatching { clazz.getDeclaredField(name).apply { isAccessible = true } }.getOrNull()
}
