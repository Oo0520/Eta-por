package io.github.mangi.eta.ui.screens.prompts

import io.github.mangi.eta.agent.model.AgentSnapshotAnalysis
import io.github.mangi.eta.agent.model.SystemSegmentKind
import io.github.mangi.eta.agent.prompt.PromptCustomization
import org.json.JSONArray

/**
 * 编辑前后的差异（只读计算，不改配置）。
 *
 * 用户要的是"边改边看这次改了哪些段、多了哪些工具"，而不是把 8 条 system 全铺开。
 */
internal data class PromptEditDiff(
    val changedSegments: List<SegmentDelta>,
    val toolsAdded: List<String>,
    val toolsRemoved: List<String>,
    val systemBefore: Int,
    val systemAfter: Int,
    val systemCharsBefore: Int,
    val systemCharsAfter: Int,
) {
    data class SegmentDelta(
        val kind: SystemSegmentKind,
        val beforeChars: Int,
        val afterChars: Int,
    )

    val hasContentChanges: Boolean
        get() = changedSegments.isNotEmpty() || toolsAdded.isNotEmpty() || toolsRemoved.isNotEmpty()

    companion object {
        fun of(before: EffectivePreview, after: EffectivePreview): PromptEditDiff {
            val beforeSegments = segments(before.system)
            val afterSegments = segments(after.system)
            val changes = mutableListOf<SegmentDelta>()
            SystemSegmentKind.entries.forEach { kind ->
                val b = beforeSegments[kind]
                val a = afterSegments[kind]
                if (b != a) {
                    changes += SegmentDelta(kind, b ?: 0, a ?: 0)
                }
            }
            val beforeTools = toolNames(before.tools)
            val afterTools = toolNames(after.tools)
            return PromptEditDiff(
                changedSegments = changes,
                toolsAdded = afterTools.filterNot(beforeTools::contains).sorted(),
                toolsRemoved = beforeTools.filterNot(afterTools::contains).sorted(),
                systemBefore = before.system.length(),
                systemAfter = after.system.length(),
                systemCharsBefore = beforeSegments.values.sum(),
                systemCharsAfter = afterSegments.values.sum(),
            )
        }

        private fun segments(system: JSONArray): Map<SystemSegmentKind, Int> {
            val result = linkedMapOf<SystemSegmentKind, Int>()
            for (index in 0 until system.length()) {
                val text = system.optJSONObject(index)?.optString("content").orEmpty()
                val kind = AgentSnapshotAnalysis.classifySegment(text)
                result[kind] = (result[kind] ?: 0) + text.length
            }
            return result
        }

        private fun toolNames(tools: JSONArray): Set<String> {
            val names = linkedSetOf<String>()
            for (index in 0 until tools.length()) {
                tools.optJSONObject(index)?.optJSONObject("function")
                    ?.optString("name")?.takeIf { it.isNotBlank() }?.let(names::add)
            }
            return names
        }
    }
}
