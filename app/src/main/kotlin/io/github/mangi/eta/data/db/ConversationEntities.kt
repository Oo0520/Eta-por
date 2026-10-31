package io.github.mangi.eta.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.mangi.eta.data.model.ReasoningEffort
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "conversations",
    indices = [Index("branch_root_id")],
)
internal data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    @ColumnInfo(name = "thinking_enabled") val thinkingEnabled: Boolean,
    @ColumnInfo(name = "reasoning_effort", defaultValue = "'default'")
    val reasoningEffort: String = ReasoningEffort.DEFAULT.wireValue,
    @ColumnInfo(name = "history_json") val historyJson: String = "[]",
    @ColumnInfo(name = "applied_runtime_run_ids_json") val appliedRuntimeRunIdsJson: String = "[]",
    @ColumnInfo(name = "roleplay_json", defaultValue = "''") val roleplayJson: String = "",
    @ColumnInfo(name = "revisions_json", defaultValue = "''") val revisionsJson: String = "",
    @ColumnInfo(name = "last_model_usage_json", defaultValue = "''") val lastModelUsageJson: String = "",
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    /** 会话最近使用的本地 Model.id；null 表示跟随默认模型。 */
    @ColumnInfo(name = "model_id") val modelId: String? = null,
    /** 收藏置顶：置顶会话排在最前，批量删除时跳过，避免误删。 */
    @ColumnInfo(name = "pinned", defaultValue = "0") val pinned: Boolean = false,
    /** 所属会话分类；null 表示未分类，分类被删除后回落到未分类。 */
    @ColumnInfo(name = "category_id") val categoryId: String? = null,
    /** 分叉来源会话；null 表示这是一棵分支树的根（或从未分叉过）。 */
    @ColumnInfo(name = "parent_conversation_id") val parentConversationId: String? = null,
    /** 分叉点消息 id：分支从这条消息处分出，画布用它显示分叉位置。 */
    @ColumnInfo(name = "parent_message_id") val parentMessageId: String? = null,
    /** 整棵分支树共享的根会话 id；根会话自身填自己的 id，老数据由迁移回填。 */
    @ColumnInfo(name = "branch_root_id") val branchRootId: String? = null,
)

internal data class ConversationMetadata(
    val id: String,
    val title: String,
    @ColumnInfo(name = "thinking_enabled") val thinkingEnabled: Boolean,
    @ColumnInfo(name = "reasoning_effort") val reasoningEffort: String,
    @ColumnInfo(name = "applied_runtime_run_ids_json") val appliedRuntimeRunIdsJson: String,
    @ColumnInfo(name = "roleplay_json") val roleplayJson: String = "",
    @ColumnInfo(name = "revisions_json") val revisionsJson: String = "",
    @ColumnInfo(name = "last_model_usage_json") val lastModelUsageJson: String = "",
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "model_id") val modelId: String? = null,
    @ColumnInfo(name = "pinned") val pinned: Boolean = false,
    /** 所属会话分类；null 表示未分类，分类被删除后回落到未分类。 */
    @ColumnInfo(name = "category_id") val categoryId: String? = null,
    @ColumnInfo(name = "parent_conversation_id") val parentConversationId: String? = null,
    @ColumnInfo(name = "parent_message_id") val parentMessageId: String? = null,
    @ColumnInfo(name = "branch_root_id") val branchRootId: String? = null,
)

/** 会话侧栏只读取定长预览，不读取上下文、角色卡与修订正文。 */
internal data class ConversationIndexRow(
    val id: String,
    val title: String,
    @ColumnInfo(name = "reasoning_effort") val reasoningEffort: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "model_id") val modelId: String?,
    @ColumnInfo(name = "message_id") val messageId: String?,
    @ColumnInfo(name = "message_type") val messageType: String?,
    @ColumnInfo(name = "message_preview") val messagePreview: String?,
    @ColumnInfo(name = "tool_name") val toolName: String?,
    @ColumnInfo(name = "has_roleplay") val hasRoleplay: Boolean,
)

internal data class ConversationIdentity(
    val id: String,
    val title: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

internal data class ConversationMessageContent(val id: String, val content: String)

@Serializable
@Entity(
    tableName = "conversation_context_checkpoints",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class ConversationContextCheckpointEntity(
    @PrimaryKey
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "history_json") val historyJson: String,
    @ColumnInfo(name = "journal_json", defaultValue = "''") val journalJson: String = "",
)

@Serializable
@Entity(tableName = "conversation_state")
internal data class ConversationStateEntity(
    @PrimaryKey val id: String = SINGLETON_ID,
    @ColumnInfo(name = "selected_conversation_id") val selectedConversationId: String,
) {
    companion object {
        const val SINGLETON_ID = "main"
    }
}

@Serializable
@Entity(
    tableName = "conversation_messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("conversation_id"),
        Index(value = ["conversation_id", "sort_index"], unique = true),
    ],
)
internal data class ConversationMessageEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "sort_index") val sortIndex: Int,
    val type: String,
    val content: String,
    @ColumnInfo(name = "images_json") val imagesJson: String = "[]",
    @ColumnInfo(name = "is_edited", defaultValue = "0") val isEdited: Boolean = false,
    @ColumnInfo(name = "render_markdown") val renderMarkdown: Boolean? = null,
    @ColumnInfo(name = "context_tokens") val contextTokens: Int? = null,
    @ColumnInfo(name = "input_tokens") val inputTokens: Int? = null,
    @ColumnInfo(name = "output_tokens") val outputTokens: Int? = null,
    @ColumnInfo(name = "reasoning_tokens") val reasoningTokens: Int? = null,
    @ColumnInfo(name = "cached_tokens") val cachedTokens: Int? = null,
    @ColumnInfo(name = "request_duration_ms") val requestDurationMs: Long? = null,
    @ColumnInfo(name = "elapsed_seconds") val elapsedSeconds: Int? = null,
    @ColumnInfo(name = "tool_name") val toolName: String? = null,
    @ColumnInfo(name = "tool_status") val toolStatus: String? = null,
    @ColumnInfo(name = "arguments_summary") val argumentsSummary: String? = null,
    @ColumnInfo(name = "result_summary") val resultSummary: String? = null,
    @ColumnInfo(name = "image_count") val imageCount: Int = 0,
    @ColumnInfo(name = "tools_json") val toolsJson: String = "[]",
    /** 消息写入时刻（毫秒）；0 表示这条消息来自没有该列的旧版本，界面按「不显示时间」处理。 */
    @ColumnInfo(name = "created_at", defaultValue = "0") val createdAt: Long = 0L,
)

/** 分支树画布节点：一次查询取出整棵树，不需要递归。 */
internal data class ConversationBranchNodeRow(
    val id: String,
    val title: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "parent_conversation_id") val parentConversationId: String?,
    @ColumnInfo(name = "parent_message_id") val parentMessageId: String?,
    @ColumnInfo(name = "message_count") val messageCount: Int,
    @ColumnInfo(name = "fork_preview") val forkPreview: String?,
)
