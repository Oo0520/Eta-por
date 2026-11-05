package io.github.mangi.eta.agent.subagent

/**
 * 内置的**官方价目参考**（每百万 tokens）。
 *
 * 只收录能在厂商官方价目页查到的数值，并带采集日期与来源；查不到的一律不填，
 * 由界面显示「价格未知」，绝不估算。价格会变，所以这里只作为**参考事实**展示给
 * 主 Agent，不参与任何硬性判断。
 */
internal object ModelPriceCatalog {

    data class Price(
        /** 输入价（原始币种/百万 tokens）。 */
        val input: String,
        /** 输出价（原始币种/百万 tokens）。 */
        val output: String,
        /** 币种符号，例如 ¥ 或 $。 */
        val currency: String,
        /** 阶梯/峰谷等附加说明。 */
        val note: String = "",
        /** 采集日期。 */
        val collectedAt: String = "2026-10-31",
    ) {
        fun display(): String = buildString {
            append("输入 ").append(currency).append(input)
            append(" / 输出 ").append(currency).append(output)
            append("（每百万 tokens")
            if (note.isNotBlank()) append("，").append(note)
            append("）")
        }
    }

    private val PRICES: Map<String, Price> = mapOf(
        // —— 已从官方价目页取到 ——
        "cn:deepseek-v4.1-flash" to Price(
            input = "0.15（闲）/0.30（忙）",
            output = "0.60（闲）/1.20（忙）",
            currency = "$",
            note = "峰谷计价，UTC 01-04/06-10 为忙时",
        ),
        "cn:minimax-m3.1-flash" to Price(input = "1.00", output = "4.00", currency = "¥"),
        "cn:glm-5.2" to Price(input = "8", output = "28", currency = "¥", note = "缓存命中 ¥2"),
        "cn:glm-5.3" to Price(input = "8", output = "28", currency = "¥", note = "缓存命中 ¥2"),
        "cn:glm-5.3-flash" to Price(input = "0.8", output = "2.8", currency = "¥", note = "缓存命中 ¥0.23"),
        "cn:kimi-k3.1-pro" to Price(input = "13", output = "68", currency = "¥", note = "缓存命中 ¥1.3"),
        "cn:kimi-k3-1" to Price(input = "20", output = "100", currency = "¥", note = "缓存命中 ¥2"),
        "cn:step-5-preview" to Price(input = "7", output = "20", currency = "¥", note = "缓存命中 ¥0.7"),
        "cn:mimo-v2.6-pro" to Price(input = "3.00", output = "6.00", currency = "¥", note = "缓存命中 ¥0.025"),
    )

    /** 查询价格；未收录返回 null（界面显示「价格未知」，不做估算）。 */
    fun priceOf(modelApiId: String): Price? =
        PRICES[modelApiId.trim().lowercase()]

    /** 未知价格的模型清单（便于后续补齐）。 */
    val known: Set<String> get() = PRICES.keys
}
