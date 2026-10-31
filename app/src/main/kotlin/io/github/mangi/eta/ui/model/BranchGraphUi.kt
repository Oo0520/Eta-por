package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable

/**
 * 分支画布上的一个节点。
 *
 * 只带画布需要的字段：分叉归属、分叉时刻、消息数、分叉点消息摘要。
 * 正文与上下文留在会话自己的加载链路里，画布不碰它们。
 */
@Immutable
data class BranchNodeUi(
    val id: String,
    val title: String,
    /** 会话创建时间：分支会话即「分叉那一刻」，画布按它排时间顺序。 */
    val createdAt: Long,
    val parentConversationId: String? = null,
    val parentMessageId: String? = null,
    val messageCount: Int = 0,
    /** 分叉点那条消息的开头一段，用来让人认出「从哪句分出」。 */
    val forkPreview: String = "",
) {
    val isBranch: Boolean get() = parentConversationId != null
}
