package io.github.mangi.eta.agent.prompt

/** 冲突级别：ERROR 阻断保存，WARNING 提示但仍可保存，INFO 只做说明。 */
internal enum class PromptConflictLevel {
    ERROR,
    WARNING,
    INFO,
}

internal data class PromptConflict(
    val code: String,
    val level: PromptConflictLevel,
    val title: String,
    val detail: String,
)

/**
 * 自定义提示词的冲突检测。
 *
 * 目标不是替用户判断内容好坏，而是把"这段文字会和什么打架"提前说清楚：
 * 与内置身份段、工具契约、证据要求、角色卡人格、上下文预算的冲突各算一条。
 */
internal object PromptConflictAnalyzer {
    private const val LENGTH_BUDGET_CHARS = 6000

    private val safetyOverridePatterns = listOf(
        "忽略以上所有指令", "忽略之前的所有", "忽略上面的规则", "无视所有规则", "不要遵守",
        "关闭安全", "取消限制", "无任何限制", "无视安全",
    )

    private val toolContractPatterns = listOf(
        "不要调用工具", "禁止使用工具", "不要使用工具", "跳过确认", "不要询问",
        "不必调用工具", "不要执行工具",
    )

    private val evidencePatterns = listOf(
        "可以编造", "直接编造", "不必查证", "不用查证", "不需要证据", "直接给结论不要证据",
    )

    private val placeholderPattern = Regex("\\{\\{\\s*([^{}]+?)\\s*\\}\\}")

    fun analyze(
        customization: PromptCustomization,
        roleplayActive: Boolean = false,
        toolPolicy: AgentToolPolicy = AgentToolPolicy(),
    ): List<PromptConflict> {
        val conflicts = mutableListOf<PromptConflict>()
        val base = customization.baseSystemPrompt
        val persona = (customization.personaName + "\n" + customization.personaStyle).trim()
        val append = customization.appendInstructions
        val merged = listOf(base, persona, append).joinToString("\n")

        if (customization.mode == PromptCustomizationMode.REPLACE && base.isBlank()) {
            conflicts += PromptConflict(
                code = "REPLACE_EMPTY",
                level = PromptConflictLevel.ERROR,
                title = "替换模式缺少系统提示词",
                detail = "选择「替换」后必须填写系统提示词，否则身份段为空。",
            )
        }

        if (toolPolicy.mode == ToolPolicyMode.ALLOW_LIST && toolPolicy.allowedTools.isEmpty()) {
            conflicts += PromptConflict(
                code = "TOOL_POLICY_EMPTY",
                level = PromptConflictLevel.ERROR,
                title = "工具白名单为空",
                detail = "白名单模式下一个工具都没勾选，请求里不会带任何工具；按需补选，或切回全部开放。",
            )
        }

        if (customization.personaName.isNotBlank() &&
            (customization.personaName.contains('<') || customization.personaName.contains('>') ||
                customization.personaName.contains('{') || customization.personaName.contains('}') ||
                customization.personaName.contains('\n') || customization.personaName.length > 24)
        ) {
            conflicts += PromptConflict(
                code = "PERSONA_NAME_UNSAFE",
                level = PromptConflictLevel.WARNING,
                title = "人格名称可能破坏标记结构",
                detail = "名称里含尖括号、花括号、换行或超过 24 字，可能干扰自定义段的边界标记。",
            )
        }

        safetyOverridePatterns.firstOrNull(merged::contains)?.let { hit ->
            conflicts += PromptConflict(
                code = "SAFETY_OVERRIDE",
                level = PromptConflictLevel.WARNING,
                title = "与内置规则直接冲突",
                detail = "自定义文本包含「$hit」。内置身份段与安全约束仍会注入并排在其后，两者同时存在时模型行为不可预期。",
            )
        }

        toolContractPatterns.firstOrNull(merged::contains)?.let { hit ->
            conflicts += PromptConflict(
                code = "TOOL_CONTRACT",
                level = PromptConflictLevel.WARNING,
                title = "与工具契约冲突",
                detail = "自定义文本包含「$hit」，但工具契约段始终注入。真到执行时模型仍会调用工具，出现「说了不用却调用了」的反复。",
            )
        }

        evidencePatterns.firstOrNull(merged::contains)?.let { hit ->
            conflicts += PromptConflict(
                code = "EVIDENCE_REQUIREMENT",
                level = PromptConflictLevel.WARNING,
                title = "与证据要求冲突",
                detail = "自定义文本包含「$hit」，与内置「不得编造未取得的数据」相反，容易被理解为允许臆测。",
            )
        }

        val totalChars = base.length + persona.length + append.length
        if (totalChars > LENGTH_BUDGET_CHARS) {
            conflicts += PromptConflict(
                code = "LENGTH_BUDGET",
                level = PromptConflictLevel.WARNING,
                title = "自定义段过长",
                detail = "当前 $totalChars 字，超过建议上限 $LENGTH_BUDGET_CHARS 字；这段每轮都占上下文，且改动会让提示词前缀缓存失效。",
            )
        }

        if (append.length >= 40 && base.length >= 40) {
            val overlap = jaccard(tokenize(append), tokenize(base))
            if (overlap > 0.6) {
                conflicts += PromptConflict(
                    code = "DUPLICATE_INSTRUCTION",
                    level = PromptConflictLevel.INFO,
                    title = "追加指令与系统提示词高度重复",
                    detail = "两段内容重合度约 ${(overlap * 100).toInt()}%，可以去掉其中一份以省上下文。",
                )
            }
        }

        val placeholders = placeholderPattern.findAll(merged).map { it.groupValues[1] }.distinct().toList()
        if (placeholders.isNotEmpty()) {
            conflicts += PromptConflict(
                code = "UNRESOLVED_PLACEHOLDER",
                level = PromptConflictLevel.INFO,
                title = "存在未替换的占位符",
                detail = "以下占位符不会被 Eta 展开，会按原样发给模型：${placeholders.joinToString("、") { "{{$it}}" }}",
            )
        }

        if (persona.isNotBlank() && roleplayActive) {
            conflicts += PromptConflict(
                code = "ROLEPLAY_OVERLAP",
                level = PromptConflictLevel.INFO,
                title = "与角色卡人格叠加",
                detail = "当前会话正在使用角色卡。内置组装会在角色卡会话里跳过自定义人格段，避免两套人设同时生效。",
            )
        }

        if (customization.mode == PromptCustomizationMode.REPLACE) {
            conflicts += PromptConflict(
                code = "CONTRACT_ALWAYS_ON",
                level = PromptConflictLevel.INFO,
                title = "替换模式只替换身份段",
                detail = "工具、设备、屏幕观察等规则段属于能力契约，替换模式下仍然注入；这是为了让工具调用继续成立，不是遗漏。",
            )
        }

        if (customization.hasContent && !customization.enabled) {
            conflicts += PromptConflict(
                code = "DISABLED_WITH_CONTENT",
                level = PromptConflictLevel.INFO,
                title = "已填写但未启用",
                detail = "内容已保存，开关处于关闭状态，当前不会注入任何一段。",
            )
        }

        return conflicts
    }

    fun hasError(conflicts: List<PromptConflict>): Boolean =
        conflicts.any { it.level == PromptConflictLevel.ERROR }

    private fun tokenize(text: String): Set<String> =
        text.lowercase()
            .split(Regex("[\\s，,。.;；:：!！?？、\\-—()（）\\[\\]【】\"'`]+"))
            .filter { it.length >= 2 }
            .toSet()

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val intersection = a.count(b::contains)
        val union = a.size + b.size - intersection
        return if (union == 0) 0.0 else intersection.toDouble() / union
    }
}
