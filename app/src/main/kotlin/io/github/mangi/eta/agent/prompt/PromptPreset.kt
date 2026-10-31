package io.github.mangi.eta.agent.prompt

import org.json.JSONObject

/**
 * 一份可导入导出的提示词预设。
 *
 * 包格式：zip（推荐）或裸 JSON 文件，里面只需要一个 `preset.json`；
 * 字段与自定义提示词一一对应，不带任何运行期状态（版本号、历史都不进包）。
 */
internal data class PromptPreset(
    val name: String,
    val description: String = "",
    val mode: PromptCustomizationMode = PromptCustomizationMode.APPEND,
    val baseSystemPrompt: String = "",
    val personaName: String = "",
    val personaStyle: String = "",
    val appendInstructions: String = "",
    val author: String = "",
    val createdAt: Long = 0L,
    val builtin: Boolean = false,
    /** 预设可以一并带工具范围：切换预设时提示词与工具集一起换，像 harness 的"模式"。 */
    val toolMode: ToolPolicyMode = ToolPolicyMode.ALLOW_ALL,
    val allowedTools: Set<String> = emptySet(),
) {
    val contentLength: Int
        get() = baseSystemPrompt.length + personaName.length + personaStyle.length + appendInstructions.length

    val hasContent: Boolean
        get() = baseSystemPrompt.isNotBlank() || personaName.isNotBlank() ||
            personaStyle.isNotBlank() || appendInstructions.isNotBlank()

    fun toJson(): JSONObject = JSONObject()
        .put("schema", SCHEMA)
        .put("name", name)
        .put("description", description)
        .put("mode", mode.name)
        .put("base_system_prompt", baseSystemPrompt)
        .put("persona_name", personaName)
        .put("persona_style", personaStyle)
        .put("append_instructions", appendInstructions)
        .put("author", author)
        .put("created_at", createdAt)
        .put("tool_mode", toolMode.name)
        .put("allowed_tools", org.json.JSONArray().also { array -> allowedTools.sorted().forEach(array::put) })

    fun sameContentAs(other: PromptPreset): Boolean =
        mode == other.mode && baseSystemPrompt == other.baseSystemPrompt &&
            personaName == other.personaName && personaStyle == other.personaStyle &&
            appendInstructions == other.appendInstructions &&
            toolMode == other.toolMode && allowedTools == other.allowedTools

    companion object {
        const val SCHEMA = 2
        const val MAX_FIELD_CHARS = 20_000
        const val MAX_BUNDLE_BYTES = 256 * 1024

        fun fromCustomization(
            customization: PromptCustomization,
            name: String,
            description: String = "",
            now: Long = System.currentTimeMillis(),
        ): PromptPreset = PromptPreset(
            name = name,
            description = description,
            mode = customization.mode,
            baseSystemPrompt = customization.baseSystemPrompt,
            personaName = customization.personaName,
            personaStyle = customization.personaStyle,
            appendInstructions = customization.appendInstructions,
            createdAt = now,
        )

        fun fromCustomization(
            customization: PromptCustomization,
            policy: AgentToolPolicy,
            name: String,
            description: String = "",
            now: Long = System.currentTimeMillis(),
        ): PromptPreset = fromCustomization(customization, name, description, now).copy(
            toolMode = policy.mode,
            allowedTools = policy.allowedTools,
        )

        /** 解析失败返回 null，由调用方给出明确错误，不静默套用半份配置。 */
        fun fromJson(json: JSONObject): PromptPreset? {
            val schema = json.optInt("schema", SCHEMA)
            if (schema > SCHEMA) return null
            val name = json.optString("name").trim()
            if (name.isEmpty()) return null
            fun field(key: String): String? = json.optString(key).takeIf { it.length <= MAX_FIELD_CHARS }
            val base = field("base_system_prompt") ?: return null
            val personaName = field("persona_name") ?: return null
            val personaStyle = field("persona_style") ?: return null
            val append = field("append_instructions") ?: return null
            return PromptPreset(
                name = name.take(60),
                description = json.optString("description").take(280),
                mode = runCatching { PromptCustomizationMode.valueOf(json.optString("mode")) }
                    .getOrDefault(PromptCustomizationMode.APPEND),
                baseSystemPrompt = base,
                personaName = personaName,
                personaStyle = personaStyle,
                appendInstructions = append,
                author = json.optString("author").take(60),
                createdAt = json.optLong("created_at", 0L),
                toolMode = runCatching { ToolPolicyMode.valueOf(json.optString("tool_mode")) }
                    .getOrDefault(ToolPolicyMode.ALLOW_ALL),
                allowedTools = json.optJSONArray("allowed_tools")?.let { array ->
                    (0 until array.length()).mapNotNull { index ->
                        array.optString(index).takeIf(String::isNotBlank)
                    }.toSet()
                }.orEmpty(),
            )
        }
    }
}
