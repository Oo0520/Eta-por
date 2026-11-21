package io.github.mangi.eta.agent.prompt

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** 自定义提示词的生效方式。 */
internal enum class PromptCustomizationMode {
    /** 作为附加约束追加，内置身份段保持原样。 */
    APPEND,

    /** 作为主身份设定前置，并声明优先级高于内置身份描述。 */
    REPLACE,
}

/**
 * 用户自定义的身份、人格与追加指令。
 *
 * 版本语义：[version] 是"历史条目序号"，只在内容真正变化时 +1；清空历史或恢复默认会归零。
 * [revision] 是内容哈希，用来判断"同一编号是否被外部改写"，也是请求快照的比对依据。
 * 两者一起用于判断"同一版本号是否被外部改写"，也是请求快照与提示词前缀缓存的比对依据。
 */
internal data class PromptCustomization(
    val enabled: Boolean = false,
    val mode: PromptCustomizationMode = PromptCustomizationMode.APPEND,
    val baseSystemPrompt: String = "",
    val personaName: String = "",
    val personaStyle: String = "",
    val appendInstructions: String = "",
    val version: Int = 0,
    val updatedAt: Long = 0L,
    val history: List<PromptVersionRecord> = emptyList(),
) {
    val revision: String get() = sha256(fingerprint()).take(REVISION_LENGTH)

    val hasContent: Boolean
        get() = baseSystemPrompt.isNotBlank() || personaName.isNotBlank() ||
            personaStyle.isNotBlank() || appendInstructions.isNotBlank()

    /** 真正参与组装的判定：开关打开且至少有一段内容。 */
    val active: Boolean get() = enabled && hasContent

    /** 内容变化才递增版本；只改开关或只点保存不会产生新版本。 */
    fun edit(
        enabled: Boolean = this.enabled,
        mode: PromptCustomizationMode = this.mode,
        baseSystemPrompt: String = this.baseSystemPrompt,
        personaName: String = this.personaName,
        personaStyle: String = this.personaStyle,
        appendInstructions: String = this.appendInstructions,
        now: Long = System.currentTimeMillis(),
    ): PromptCustomization {
        val next = copy(
            enabled = enabled,
            mode = mode,
            baseSystemPrompt = baseSystemPrompt,
            personaName = personaName,
            personaStyle = personaStyle,
            appendInstructions = appendInstructions,
        )
        if (next.fingerprint() == fingerprint()) return next
        return next.copy(
            version = version + 1,
            updatedAt = now,
            history = (listOf(snapshot()) + history).take(HISTORY_LIMIT),
        )
    }

    /** 回滚到某个历史版本：内容取旧值，版本号继续向前，不复用旧编号。 */
    fun restore(record: PromptVersionRecord, now: Long = System.currentTimeMillis()): PromptCustomization =
        copy(
            enabled = record.enabled,
            mode = record.mode,
            baseSystemPrompt = record.baseSystemPrompt,
            personaName = record.personaName,
            personaStyle = record.personaStyle,
            appendInstructions = record.appendInstructions,
            version = version + 1,
            updatedAt = now,
            history = (listOf(snapshot()) + history).take(HISTORY_LIMIT),
        )

    /**
     * 一键恢复系统默认：回到"没有自定义内容"的出厂状态。
     *
     * 编号一并归零、历史清空——恢复默认不该给计数器 +1，否则清空历史后仍会看到 v26 这种怪数字。
     */
    fun resetToDefault(now: Long = System.currentTimeMillis()): PromptCustomization {
        if (!hasContent && !enabled && version == 0 && history.isEmpty()) return this
        return PromptCustomization(updatedAt = now)
    }

    /** 内容与该历史版本完全一致时不需要回滚，避免造出一个重复版本。 */
    fun matches(record: PromptVersionRecord): Boolean =
        record.enabled == enabled &&
            record.mode == mode &&
            record.baseSystemPrompt == baseSystemPrompt &&
            record.personaName == personaName &&
            record.personaStyle == personaStyle &&
            record.appendInstructions == appendInstructions

    /** 只删历史条目，不动当前版本。 */
    fun removeHistory(version: Int): PromptCustomization =
        copy(history = history.filterNot { it.version == version })

    /** 清空历史同时把编号归零：编号是"历史条目序号"，不该与历史脱钩。 */
    fun clearHistory(): PromptCustomization = copy(history = emptyList(), version = 0)

    /** 只丢掉空内容的历史记录，保留有内容的。 */
    fun dropEmptyHistory(): PromptCustomization =
        copy(history = history.filter { it.hasContent })

    private fun snapshot(): PromptVersionRecord = PromptVersionRecord(
        version = version,
        revision = revision,
        updatedAt = updatedAt,
        enabled = enabled,
        mode = mode,
        baseSystemPrompt = baseSystemPrompt,
        personaName = personaName,
        personaStyle = personaStyle,
        appendInstructions = appendInstructions,
    )

    /** 只对「实际内容」取指纹：开关这类不改变提示词文本的设置不该影响版本号与请求前缀。 */
    private fun fingerprint(): String = buildString {
        append(mode.name)
        append('\u0000')
        append(baseSystemPrompt)
        append('\u0000')
        append(personaName)
        append('\u0000')
        append(personaStyle)
        append('\u0000')
        append(appendInstructions)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("enabled", enabled)
        .put("mode", mode.name)
        .put("base", baseSystemPrompt)
        .put("persona_name", personaName)
        .put("persona_style", personaStyle)
        .put("append", appendInstructions)
        .put("version", version)
        .put("updated_at", updatedAt)
        .put("history", JSONArray().also { array -> history.forEach { array.put(it.toJson()) } })

    companion object {
        const val HISTORY_LIMIT = 5
        const val REVISION_LENGTH = 12

        fun decode(raw: String?): PromptCustomization =
            if (raw.isNullOrBlank()) {
                PromptCustomization()
            } else {
                runCatching { fromJson(JSONObject(raw)) }.getOrElse { PromptCustomization() }
            }

        fun fromJson(json: JSONObject): PromptCustomization = PromptCustomization(
            enabled = json.optBoolean("enabled", false),
            mode = runCatching { PromptCustomizationMode.valueOf(json.optString("mode")) }
                .getOrDefault(PromptCustomizationMode.APPEND),
            baseSystemPrompt = json.optString("base"),
            personaName = json.optString("persona_name"),
            personaStyle = json.optString("persona_style"),
            appendInstructions = json.optString("append"),
            version = json.optInt("version", 0),
            updatedAt = json.optLong("updated_at", 0L),
            history = json.optJSONArray("history")?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    array.optJSONObject(index)?.let(PromptVersionRecord::fromJson)
                }
            }.orEmpty(),
        )

        private fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte) }
    }
}

/** 一次历史快照，供回滚与"改了什么"说明使用。 */
internal data class PromptVersionRecord(
    val version: Int,
    val revision: String,
    val updatedAt: Long,
    val enabled: Boolean,
    val mode: PromptCustomizationMode,
    val baseSystemPrompt: String,
    val personaName: String,
    val personaStyle: String,
    val appendInstructions: String,
) {
    /** 空内容版本（历史遗留的一次空保存）不展示，避免列表里全是"system 0 字"。 */
    val hasContent: Boolean
        get() = baseSystemPrompt.isNotBlank() || personaName.isNotBlank() ||
            personaStyle.isNotBlank() || appendInstructions.isNotBlank()

    val summary: String
        get() = buildString {
            append(if (mode == PromptCustomizationMode.REPLACE) "替换" else "追加")
            append(" · system ${baseSystemPrompt.length} 字")
            if (personaName.isNotBlank()) append(" · 人格 $personaName")
            if (appendInstructions.isNotBlank()) append(" · 追加 ${appendInstructions.length} 字")
        }

    fun toJson(): JSONObject = JSONObject()
        .put("version", version)
        .put("revision", revision)
        .put("updated_at", updatedAt)
        .put("enabled", enabled)
        .put("mode", mode.name)
        .put("base", baseSystemPrompt)
        .put("persona_name", personaName)
        .put("persona_style", personaStyle)
        .put("append", appendInstructions)

    companion object {
        fun fromJson(json: JSONObject): PromptVersionRecord = PromptVersionRecord(
            version = json.optInt("version", 0),
            revision = json.optString("revision"),
            updatedAt = json.optLong("updated_at", 0L),
            enabled = json.optBoolean("enabled", false),
            mode = runCatching { PromptCustomizationMode.valueOf(json.optString("mode")) }
                .getOrDefault(PromptCustomizationMode.APPEND),
            baseSystemPrompt = json.optString("base"),
            personaName = json.optString("persona_name"),
            personaStyle = json.optString("persona_style"),
            appendInstructions = json.optString("append"),
        )
    }
}
