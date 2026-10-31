package io.github.mangi.eta.agent.prompt

import io.github.mangi.eta.config.Prefs

/**
 * 自定义提示词的持久化。
 *
 * 存在 App 私有的 Agent 配置里（[Prefs.localAgentPreferences]），不经过 Hook 进程，
 * 也不读取任何外部目录；外部写入的配置文件永远不会被这里当成提示词。
 */
internal object PromptCustomizationStore {
    private const val KEY = "agent_prompt_customization_json"

    @Volatile
    private var cachedRaw: String? = null

    @Volatile
    private var cachedValue: PromptCustomization = PromptCustomization()

    fun load(): PromptCustomization {
        val preferences = Prefs.localAgentPreferences() ?: return PromptCustomization()
        val raw = runCatching { preferences.getString(KEY, null) }.getOrNull()
        if (raw == cachedRaw) return cachedValue
        // 历史已空却留着旧编号（旧版本清历史不归零）时自动归零，避免"默认状态 v26"这种残留。
        val value = PromptCustomization.decode(raw).let {
            if (it.history.isEmpty() && it.version != 0) it.copy(version = 0) else it
        }
        cachedRaw = raw
        cachedValue = value
        return value
    }

    fun save(value: PromptCustomization): Boolean {
        val preferences = Prefs.localAgentPreferences() ?: return false
        val raw = value.toJson().toString()
        val written = runCatching { preferences.edit().putString(KEY, raw).commit() }.getOrDefault(false)
        if (written) {
            cachedRaw = raw
            cachedValue = value
        }
        return written
    }

    fun clear(): Boolean = save(PromptCustomization())
}
