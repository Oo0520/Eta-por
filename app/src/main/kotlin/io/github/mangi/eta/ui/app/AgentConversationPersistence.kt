package io.github.mangi.eta.ui.app

import android.content.Context
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.roleplay.RoleplayBinding
import io.github.mangi.eta.agent.roleplay.RoleplayMessageState
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 只在事务提交后推进保存基线，不持有输入草稿、待发送图片等临时 UI 状态。 */
internal class AgentConversationPersistence(initial: AgentConversationStore.Snapshot? = null) {
    private val mutex = Mutex()
    // 未对齐的会话不进入基线，首次保存按新会话整段写入，重排 sort_index。
    private var saved = initial?.let { snapshot -> project(snapshot) - snapshot.unalignedConversationIds }.orEmpty()
    /** 只加载了元数据的会话：保存时只落元数据，绝不碰 messages / checkpoint（#006）。 */
    private var partiallyLoaded: Set<String> = initial?.partiallyLoadedConversationIds.orEmpty()

    suspend fun save(
        context: Context,
        snapshot: AgentConversationStore.Snapshot,
        /** 见 [AgentConversationStore.saveChanges]：null 表示本次不改归属。 */
        categoryIds: Map<String, String>? = null,
    ) = mutex.withLock {
        val current = project(snapshot)
        partiallyLoaded = snapshot.partiallyLoadedConversationIds
        AgentConversationStore.saveChanges(
            context = context,
            selectedConversationId = snapshot.selectedConversationId,
            current = current,
            previous = saved,
            partiallyLoadedConversationIds = partiallyLoaded,
            categoryIds = categoryIds,
        )
        saved = current
    }

    /** 会话历史已按需补齐：并入保存基线，避免下次保存把它当新会话整段重写。 */
    fun markLoaded(conversationId: String, state: AgentChatHomeUiState, title: String, updatedAt: Long) {
        partiallyLoaded = partiallyLoaded - conversationId
        saved = saved + (conversationId to Content(state, title, updatedAt))
    }

    /** 会话被删除：解除占位保护，让保存路径真正删掉这一行。 */
    fun markDropped(conversationId: String) {
        partiallyLoaded = partiallyLoaded - conversationId
        saved = saved - conversationId
    }

    private fun project(snapshot: AgentConversationStore.Snapshot): Map<String, Content> =
        snapshot.conversationsById.mapValues { (id, state) ->
            Content(state, snapshot.titles[id].orEmpty(), snapshot.updatedAt[id] ?: 0L)
        }

    internal data class Content(
        val title: String,
        val updatedAt: Long,
        val reasoningEffort: ReasoningEffort,
        val appliedRuntimeRunIds: List<String>,
        val roleplay: RoleplayBinding?,
        val roleplayMessages: RoleplayMessageState,
        val modelId: String?,
        val messages: List<AgentChatMessageUi>,
        val history: List<AgentModelClient.ConversationMessage>,
        val journal: List<AgentModelClient.ConversationMessage>,
        val lastModelUsage: io.github.mangi.eta.ui.model.TokenUsageUi?,
    ) {
        constructor(state: AgentChatHomeUiState, title: String, updatedAt: Long) : this(
            title, updatedAt, state.reasoningEffort, state.appliedRuntimeRunIds,
            state.roleplay, if (state.roleplay == null) RoleplayMessageState() else state.roleplayMessages,
            state.modelId, state.messages, state.history, state.journal.ifEmpty { state.history },
            state.lastModelUsage,
        )

        fun sameMetadata(other: Content): Boolean =
            title == other.title && updatedAt == other.updatedAt && reasoningEffort == other.reasoningEffort &&
                appliedRuntimeRunIds == other.appliedRuntimeRunIds && roleplay == other.roleplay &&
                roleplayMessages == other.roleplayMessages && modelId == other.modelId && lastModelUsage == other.lastModelUsage
    }
}
