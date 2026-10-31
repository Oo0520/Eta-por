package io.github.mangi.eta.agent.model

/**
 * 「工具档」：控制发给模型的工具 schema 与系统提示体量。
 * - 正常档为原样发送（默认）；
 * - 纯聊天档完全不声明工具，适合只支持对话的模型。
 * 只影响请求内容，不影响工具自身的执行能力。
 */
internal enum class LocalToolProfile(val id: String) {
    /** 正常：工具与描述原样发送，不做任何压缩。 */
    FULL("full"),

    /** 纯聊天：不发送任何工具定义，只做对话（仍可接收图片）。 */
    CHAT("chat");

    companion object {
        /** 旧配置里的中间档已移除，读到时一律归到正常档。 */
        fun fromId(id: String?): LocalToolProfile =
            values().firstOrNull { it.id == id } ?: FULL
    }
}
