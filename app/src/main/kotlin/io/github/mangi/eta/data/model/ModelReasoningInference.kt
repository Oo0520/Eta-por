package io.github.mangi.eta.data.model

/**
 * 自定义/第三方提供商没有远端能力元数据时的**兜底推断**。
 *
 * 远端目录（OfficialModelCatalog / 远端同步）会给出 model.reasoning 与 supportedEfforts；
 * 但 WBAPI 这类网关只回一个模型 id，能力全靠用户手勾。这里按模型 id 的家族特征推断一份
 * 默认档位，仅用于**预填编辑页**——用户可以随时改，派发时后端还会再做一次合法性校验。
 *
 * 原则：只对名字能明确归族的模型给结论，认不出来就返回 null（保持「未知」，不瞎猜）。
 */
internal object ModelReasoningInference {

    private val LOW_MEDIUM_HIGH = listOf(
        ReasoningEffort.LOW,
        ReasoningEffort.MEDIUM,
        ReasoningEffort.HIGH,
    )

    private val MINIMAL_TO_HIGH = listOf(
        ReasoningEffort.MINIMAL,
        ReasoningEffort.LOW,
        ReasoningEffort.MEDIUM,
        ReasoningEffort.HIGH,
    )

    private val STEP_FAMILY = listOf(
        ReasoningEffort.MINIMAL,
        ReasoningEffort.LOW,
        ReasoningEffort.MEDIUM,
        ReasoningEffort.HIGH,
        ReasoningEffort.XHIGH,
        ReasoningEffort.MAX,
    )

    /**
     * 按模型 id 推断思考能力；认不出来返回 null。
     *
     * @param modelId 形如 `step-5-preview`、`gpt-5`、`cn:hy3` 的外部 id。
     */
    fun infer(modelId: String): ModelReasoningCapabilities? {
        val id = modelId.trim().lowercase().substringAfterLast(':')
        if (id.isEmpty()) return null

        // 阶跃 Step 系列：从 step-3 起支持多档，step-5 延伸到 xhigh/max。
        if (id.startsWith("step-5") || id.startsWith("step-4")) {
            return capabilities(STEP_FAMILY, ReasoningEffort.HIGH, canDisable = true)
        }
        if (id.startsWith("step-3") || id.startsWith("step-2")) {
            return capabilities(LOW_MEDIUM_HIGH, ReasoningEffort.MEDIUM, canDisable = true)
        }

        // OpenAI 推理系：o 系列不支持关闭思考。
        if (id.startsWith("o1") || id.startsWith("o3") || id.startsWith("o4") ||
            id.startsWith("gpt-5")
        ) {
            return capabilities(LOW_MEDIUM_HIGH, ReasoningEffort.MEDIUM, canDisable = false)
        }

        // Anthropic：3.7 起支持思考预算，映射为 low/medium/high。
        if (id.contains("claude") &&
            (id.contains("3-7") || id.contains("4-") || id.contains("sonnet-4") || id.contains("opus-4"))
        ) {
            return capabilities(LOW_MEDIUM_HIGH, ReasoningEffort.MEDIUM, canDisable = true)
        }

        // Gemini 2.5 / 3：官方给出 minimal 起步。
        if (id.contains("gemini-2.5") || id.contains("gemini-3")) {
            return capabilities(MINIMAL_TO_HIGH, ReasoningEffort.MEDIUM, canDisable = true)
        }

        // 其它常见开源/国产推理模型，统一按 low/medium/high。
        val reasoningFamilies = listOf(
            "deepseek-r", "deepseek-reasoner", "qwq", "qwen3", "glm-4.5", "glm-5",
            "kimi-k2", "k2-", "grok-4", "minimax-m2", "hunyuan-t1", "ernie-x1",
        )
        if (reasoningFamilies.any { id.contains(it) }) {
            return capabilities(LOW_MEDIUM_HIGH, ReasoningEffort.MEDIUM, canDisable = true)
        }

        return null
    }

    private fun capabilities(
        efforts: List<ReasoningEffort>,
        defaultEffort: ReasoningEffort,
        canDisable: Boolean,
    ): ModelReasoningCapabilities = ModelReasoningCapabilities(
        supportedEfforts = efforts,
        defaultEffort = defaultEffort,
        defaultEnabled = true,
        mandatory = !canDisable,
        canDisable = canDisable,
    )
}
