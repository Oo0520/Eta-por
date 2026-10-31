package io.github.mangi.eta.agent.model

import org.json.JSONObject

/** 一条 system 消息属于哪一类：靠首行特征识别，用于快照页给人看的标签。 */
internal enum class SystemSegmentKind {
    REVIEW,
    IDENTITY,
    TERMINAL,
    WEB,
    MEMORY,
    SKILL,
    CUSTOM_IDENTITY,
    CUSTOM_PERSONA,
    CUSTOM_INSTRUCTIONS,
    BASE,
    OTHER,
}

/** 请求类型：一轮对话里会混着这几种，快照必须标出来。 */
internal enum class SnapshotKind {
    CHAT,
    REVIEW,
    CHAT_ONLY,
    COMPACTION,
    MEMORY,
}

internal enum class SegmentChangeState { ADDED, REMOVED, CHANGED }

internal data class SegmentChange(
    val kind: SystemSegmentKind,
    val beforeChars: Int,
    val afterChars: Int,
    val state: SegmentChangeState,
)

internal data class ToolDescChange(val name: String, val beforeChars: Int, val afterChars: Int)

/**
 * 两份快照的结构化差异。
 *
 * 只在"能看出结论"的层面聚合：system 按段类型比，工具按新增/移除/描述改写分组，
 * 不再把 170 个工具逐行列成"38 →（移除）字"。
 */
internal data class SnapshotDiff(
    val systemCountBefore: Int,
    val systemCountAfter: Int,
    val systemCharsBefore: Int,
    val systemCharsAfter: Int,
    val toolsBefore: Int,
    val toolsAfter: Int,
    val toolsCharsBefore: Int,
    val toolsCharsAfter: Int,
    val systemChanges: List<SegmentChange>,
    val unchangedSegments: Int,
    val toolsAdded: List<String>,
    val toolsRemoved: List<String>,
    val toolDescRewritten: List<ToolDescChange>,
) {
    val identical: Boolean
        get() = systemChanges.isEmpty() && toolsAdded.isEmpty() && toolsRemoved.isEmpty() &&
            toolDescRewritten.isEmpty()
}

internal object AgentSnapshotAnalysis {

    fun classifySegment(text: String): SystemSegmentKind {
        val head = text.take(160)
        return when {
            head.contains("后台经验复盘器") -> SystemSegmentKind.REVIEW
            head.startsWith("<custom_identity") -> SystemSegmentKind.CUSTOM_IDENTITY
            head.startsWith("<custom_persona") -> SystemSegmentKind.CUSTOM_PERSONA
            head.startsWith("<custom_instructions") -> SystemSegmentKind.CUSTOM_INSTRUCTIONS
            head.startsWith("持久记忆已启用") -> SystemSegmentKind.MEMORY
            head.startsWith("已启用 Skills 索引") -> SystemSegmentKind.SKILL
            head.startsWith("Eta 内置文件与终端工具") -> SystemSegmentKind.TERMINAL
            head.startsWith("查找公开网页") || head.contains("浏览器") && head.contains("web_search") ->
                SystemSegmentKind.WEB
            head.startsWith("你是 Eta。") -> SystemSegmentKind.IDENTITY
            head.startsWith("你是 Eta，") -> SystemSegmentKind.BASE
            else -> SystemSegmentKind.OTHER
        }
    }

    fun kindOf(json: JSONObject): SnapshotKind {
        val purpose = json.optString("purpose")
        if (purpose == "COMPACTION") return SnapshotKind.COMPACTION
        val system = json.optJSONArray("system")
        if (system != null && system.length() > 0) {
            val head = system.optJSONObject(0)?.optString("text").orEmpty().take(160)
            if (head.contains("后台经验复盘器")) return SnapshotKind.REVIEW
        }
        val tools = json.optJSONObject("counts")?.optInt("tools") ?: 0
        if (tools == 0) return SnapshotKind.CHAT_ONLY
        // 后台记忆抽取等任务同样以 purpose=CHAT 发出，只能靠工具指纹区分：
        // 只带 memory_* 的请求不是主对话。
        val names = json.optJSONArray("tools")
        if (names != null && names.length() > 0) {
            val allMemory = (0 until names.length()).all { index ->
                names.optJSONObject(index)?.optString("name").orEmpty().startsWith("memory_")
            }
            if (allMemory) return SnapshotKind.MEMORY
        }
        return SnapshotKind.CHAT
    }

    fun diff(before: JSONObject, after: JSONObject): SnapshotDiff {
        val beforeCounts = before.optJSONObject("counts")
        val afterCounts = after.optJSONObject("counts")
        val beforeSegments = segmentsByKind(before)
        val afterSegments = segmentsByKind(after)

        val changes = mutableListOf<SegmentChange>()
        var unchanged = 0
        SystemSegmentKind.entries.forEach { kind ->
            val b = beforeSegments[kind]
            val a = afterSegments[kind]
            when {
                b == null && a == null -> Unit
                b == null && a != null -> changes += SegmentChange(kind, 0, a, SegmentChangeState.ADDED)
                b != null && a == null -> changes += SegmentChange(kind, b, 0, SegmentChangeState.REMOVED)
                b != null && a != null -> if (b != a) {
                    changes += SegmentChange(kind, b, a, SegmentChangeState.CHANGED)
                } else {
                    unchanged++
                }
            }
        }

        val beforeTools = toolsOf(before)
        val afterTools = toolsOf(after)
        val removed = beforeTools.keys.filterNot(afterTools::containsKey).sorted()
        val added = afterTools.keys.filterNot(beforeTools::containsKey).sorted()
        val rewritten = beforeTools.keys.filter { name ->
            val oldChars = beforeTools[name] ?: return@filter false
            val newChars = afterTools[name] ?: return@filter false
            newChars != oldChars && kotlin.math.abs(newChars - oldChars) * 5 > oldChars
        }.sorted().map { name -> ToolDescChange(name, beforeTools.getValue(name), afterTools.getValue(name)) }

        return SnapshotDiff(
            systemCountBefore = beforeCounts?.optInt("system") ?: 0,
            systemCountAfter = afterCounts?.optInt("system") ?: 0,
            systemCharsBefore = beforeCounts?.optInt("system_chars") ?: 0,
            systemCharsAfter = afterCounts?.optInt("system_chars") ?: 0,
            toolsBefore = beforeCounts?.optInt("tools") ?: 0,
            toolsAfter = afterCounts?.optInt("tools") ?: 0,
            toolsCharsBefore = beforeCounts?.optInt("tools_chars") ?: 0,
            toolsCharsAfter = afterCounts?.optInt("tools_chars") ?: 0,
            systemChanges = changes,
            unchangedSegments = unchanged,
            toolsAdded = added,
            toolsRemoved = removed,
            toolDescRewritten = rewritten,
        )
    }

    private fun segmentsByKind(json: JSONObject): Map<SystemSegmentKind, Int> {
        val result = linkedMapOf<SystemSegmentKind, Int>()
        val system = json.optJSONArray("system") ?: return result
        for (index in 0 until system.length()) {
            val message = system.optJSONObject(index) ?: continue
            val kind = classifySegment(message.optString("text"))
            // 同类型多条时合并字数，避免同名段把列表撑长。
            result[kind] = (result[kind] ?: 0) + message.optInt("chars")
        }
        return result
    }

    private fun toolsOf(json: JSONObject): Map<String, Int> {
        val tools = json.optJSONArray("tools") ?: return emptyMap()
        val map = linkedMapOf<String, Int>()
        for (index in 0 until tools.length()) {
            val tool = tools.optJSONObject(index) ?: continue
            map[tool.optString("name")] = tool.optInt("chars")
        }
        return map
    }
}
