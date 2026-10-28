package io.github.mangi.eta.agent.model

/**
 * 斜杠命令调用技能时写进用户消息的技能上下文块。
 *
 * 设计取舍：
 * - 正文直接内联，不依赖 `skills_read` —— 省 token 档位没有技能工具，靠工具读正文会拿不到内容；
 * - 块放在消息最前面，模型先看到技能步骤，再看到本次任务；
 * - 展示层用 [strip] 把块剥掉，气泡里只留用户自己写的那句话（与文件引用块同一套处理）。
 */
internal object AgentSkillCommandPromptCodec {
    private const val OPEN_TAG = "<skill_command"
    private const val CLOSE_TAG = "</skill_command>"

    /** 单次注入的 SKILL.md 正文上限；超出部分只留路径，让模型按需读文件。 */
    const val BodyLimit = 6_000

    fun format(
        skillId: String,
        skillName: String,
        skillPath: String,
        body: String,
        truncated: Boolean,
        request: String,
    ): String = buildString {
        append(OPEN_TAG)
        append(" id=\"").append(skillId.escapeAttribute()).append('"')
        append(" name=\"").append(skillName.escapeAttribute()).append('"')
        append(" path=\"").append(skillPath.escapeAttribute()).append('"')
        appendLine(">")
        appendLine("用户用斜杠命令显式调用了这个技能：按下面的步骤完成本次任务，不要复述技能正文。")
        appendLine("需要技能附带的 references/scripts 时按 path 所在目录读取，或调用 skills_read_resource。")
        appendLine()
        appendLine(body.trim())
        if (truncated) {
            appendLine()
            appendLine("（SKILL.md 正文已截断，需要更多细节时按 path 读取完整文件）")
        }
        appendLine(CLOSE_TAG)
        val trimmedRequest = request.trim()
        if (trimmedRequest.isNotEmpty()) {
            appendLine()
            append(trimmedRequest)
        }
    }.trimEnd()

    /** 用户消息里那次技能调用的抬头，供气泡区分普通消息。 */
    internal data class SkillCommandHeader(
        val skillId: String,
        val skillName: String,
    )

    fun parseHeader(content: String): SkillCommandHeader? {
        if (!content.startsWith(OPEN_TAG)) return null
        val end = content.indexOf(CLOSE_TAG)
        val head = if (end >= 0) content.substring(0, end) else content.take(512)
        val skillId = attributeOf(head, "id")?.unescapeAttribute()?.takeIf { it.isNotBlank() }
            ?: return null
        return SkillCommandHeader(
            skillId = skillId,
            skillName = attributeOf(head, "name")?.unescapeAttribute().orEmpty(),
        )
    }

    /** 内容以技能块开头时剥掉它，返回用户真正输入的那句话；不是技能块则原样返回。 */
    fun strip(content: String): String {
        if (!content.startsWith(OPEN_TAG)) return content
        val end = content.indexOf(CLOSE_TAG)
        if (end < 0) return content
        return content.substring(end + CLOSE_TAG.length).trim()
    }

    /**
     * 历史里只留一行技能标记。
     *
     * SKILL.md 正文只在当轮注入有意义；原样留在历史里会让之后每一轮都重复几 k 字符，所以压成自闭合标记。
     */
    fun compactForHistory(content: String): String {
        if (!content.startsWith(OPEN_TAG)) return content
        val end = content.indexOf(CLOSE_TAG)
        if (end < 0) return content
        val head = content.substring(0, end + CLOSE_TAG.length)
        val rest = content.substring(end + CLOSE_TAG.length).trim()
        return buildString {
            append("<skill_command")
            listOf("id", "name", "path").forEach { key ->
                attributeOf(head, key)?.let { value ->
                    append(' ').append(key).append("=\"").append(value).append('"')
                }
            }
            append(" />")
            appendLine()
            append("（本轮已通过斜杠命令注入该技能正文，此处不重复；需要细节时按 path 读取或调用 skills_read）")
            if (rest.isNotEmpty()) {
                appendLine()
                append(rest)
            }
        }
    }

    private fun attributeOf(head: String, key: String): String? =
        Regex(" " + Regex.escape(key) + "=\"([^\"]*)\"").find(head)?.groupValues?.getOrNull(1)

    private fun String.unescapeAttribute(): String =
        replace("&quot;", "\"")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")

    private fun String.escapeAttribute(): String =
        replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace('\n', ' ')
            .replace('\r', ' ')
}
