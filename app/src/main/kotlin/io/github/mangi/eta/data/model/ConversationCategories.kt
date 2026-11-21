package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 用户自建的会话分类；删除分类只解散归属，不删除会话。 */
@Serializable
data class ConversationCategory(
    val id: String,
    val name: String,
    val sortOrder: Int = 0,
    val createdAt: Long = 0L,
)

/**
 * 分类表 + 会话归属表。
 *
 * 刻意不建 Room 表：分类是纯用户元数据，条目量小、变更稀疏，
 * 放 DataStore 的一个 JSON 域里可以完全避开数据库版本迁移
 * （上游 EtaDatabase 已到 version 30，每加一列都要维护一条 MIGRATION，
 * 将来跟随上游升级时容易撞车）。
 *
 * [assignments] 的 key 是会话 id，value 是分类 id；分类被删除时对应条目一并清掉。
 */
@Serializable
data class ConversationCategoryState(
    val categories: List<ConversationCategory> = emptyList(),
    val assignments: Map<String, String> = emptyMap(),
) {
    fun categoryOf(conversationId: String): ConversationCategory? {
        val id = assignments[conversationId] ?: return null
        return categories.firstOrNull { it.id == id }
    }

    fun countOf(categoryId: String): Int = assignments.count { it.value == categoryId }

    fun add(name: String, now: Long = System.currentTimeMillis()): ConversationCategoryState {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return this
        val next = ConversationCategory(
            id = "cat-${now}-${categories.size}",
            name = trimmed,
            sortOrder = (categories.maxOfOrNull { it.sortOrder } ?: -1) + 1,
            createdAt = now,
        )
        return copy(categories = categories + next)
    }

    fun rename(categoryId: String, name: String): ConversationCategoryState {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return this
        return copy(categories = categories.map { if (it.id == categoryId) it.copy(name = trimmed) else it })
    }

    /** 删除分类：归属一并清空，会话本身不动。 */
    fun remove(categoryId: String): ConversationCategoryState = copy(
        categories = categories.filterNot { it.id == categoryId },
        assignments = assignments.filterValues { it != categoryId },
    )

    fun assign(conversationId: String, categoryId: String?): ConversationCategoryState =
        if (categoryId == null) {
            copy(assignments = assignments - conversationId)
        } else {
            copy(assignments = assignments + (conversationId to categoryId))
        }

    fun toJson(): String = json.encodeToString(ConversationCategoryState.serializer(), this)

    companion object {
        val EMPTY = ConversationCategoryState()

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun decode(raw: String?): ConversationCategoryState {
            val text = raw?.takeIf { it.isNotBlank() } ?: return EMPTY
            return runCatching {
                json.decodeFromString(ConversationCategoryState.serializer(), text)
            }.getOrDefault(EMPTY)
        }
    }
}
