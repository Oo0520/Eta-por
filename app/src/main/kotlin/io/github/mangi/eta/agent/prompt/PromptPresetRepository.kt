package io.github.mangi.eta.agent.prompt

import android.content.Context
import io.github.mangi.eta.config.Prefs
import org.json.JSONArray

/**
 * 内置预设来自 assets，导入的预设存在 App 私有配置里。
 *
 * 两边都是纯内容，不带版本号与历史；应用一份预设 = 在当前配置上生成一个新版本。
 */
internal object PromptPresetRepository {
    private const val BUILTIN_DIR = "prompt_presets"
    private const val KEY_IMPORTED = "agent_prompt_presets_json"
    private const val MAX_IMPORTED = 20

    /** 内置清单固定：顺序就是界面顺序，第一项是"系统默认"。 */
    private val BUILTIN_FILES = listOf(
        "default.json",
        "concise.json",
        "rigorous.json",
        "technical.json",
        "coding.json",
        "research.json",
        "life.json",
    )

    fun builtins(context: Context): List<PromptPreset> =
        BUILTIN_FILES.mapNotNull { file -> PromptPresetCodec.readAsset(context, "$BUILTIN_DIR/$file") }

    fun imported(): List<PromptPreset> {
        val preferences = Prefs.localAgentPreferences() ?: return emptyList()
        val raw = runCatching { preferences.getString(KEY_IMPORTED, null) }.getOrNull() ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let(PromptPreset::fromJson)
        }
    }

    fun addImported(preset: PromptPreset): List<PromptPreset> {
        val next = (listOf(preset.copy(builtin = false)) + imported().filterNot { it.name == preset.name })
            .take(MAX_IMPORTED)
        persist(next)
        return next
    }

    /** 保存用户预设；名字已存在则覆盖。内置预设只读，调用方应先复制成副本。 */
    fun upsert(preset: PromptPreset): List<PromptPreset> {
        val existing = imported().filterNot { it.name == preset.name }
        val next = (listOf(preset.copy(builtin = false)) + existing).take(MAX_IMPORTED)
        persist(next)
        return next
    }

    fun find(context: android.content.Context, name: String): PromptPreset? =
        builtins(context).firstOrNull { it.name == name }
            ?: imported().firstOrNull { it.name == name }

    fun removeImported(name: String): List<PromptPreset> {
        val next = imported().filterNot { it.name == name }
        persist(next)
        return next
    }

    /** 把预设写进当前配置：内容变化才会升版本，版本号由 [PromptCustomization.edit] 统一处理。 */
    fun applyPreset(
        current: PromptCustomization,
        preset: PromptPreset,
        now: Long = System.currentTimeMillis(),
    ): PromptCustomization = current.edit(
        enabled = preset.hasContent,
        mode = preset.mode,
        baseSystemPrompt = preset.baseSystemPrompt,
        personaName = preset.personaName,
        personaStyle = preset.personaStyle,
        appendInstructions = preset.appendInstructions,
        now = now,
    )

    /** 预设带的工具范围；切预设时和提示词一起落盘。 */
    fun policyFor(preset: PromptPreset): AgentToolPolicy =
        AgentToolPolicy(mode = preset.toolMode, allowedTools = preset.allowedTools)

    private fun persist(presets: List<PromptPreset>) {
        val preferences = Prefs.localAgentPreferences() ?: return
        val array = JSONArray()
        presets.forEach { array.put(it.toJson()) }
        runCatching { preferences.edit().putString(KEY_IMPORTED, array.toString()).commit() }
    }
}
