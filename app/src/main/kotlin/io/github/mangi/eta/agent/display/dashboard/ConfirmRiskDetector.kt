package io.github.mangi.eta.agent.display.dashboard

/**
 * 决策拦截器：在注入前判一次「这一下会不会花钱 / 泄隐私 / 不可逆」。
 *
 * 刻意做成纯函数：输入是「即将点到的控件文本」+「当前屏可见文本」，
 * 输出是一份待确认草稿。这样悬浮窗、流体云、单元测试共用同一套判定，
 * 不会出现「浮窗拦了、通知没拦」的口径分裂。
 *
 * 风险优先级：MONEY > PRIVACY > IRREVERSIBLE。命中即拦截，不做「大概没事就放行」。
 */
internal data class ConfirmDraft(
    val risk: ConfirmRisk,
    val title: String,
    val fields: List<ConfirmField>,
)

internal object ConfirmRiskDetector {

    private val moneyPattern = Regex("""(?:¥|￥|\$|USD|RMB)\s?\d+(?:[.,]\d{1,2})?|\d+(?:[.,]\d{1,2})?\s?元""")
    private val accountPattern = Regex("""\b\d{12,19}\b""")
    private val phonePattern = Regex("""(?<!\d)1[3-9]\d{9}(?!\d)""")

    private val privacyWords = listOf("身份证", "验证码", "银行卡", "收货地址", "手机号", "密码", "人脸", "定位")
    private val irreversibleWords = listOf(
        "发送", "提交订单", "立即支付", "确认支付", "确认付款", "转账", "删除", "注销", "退订",
        "清空", "解绑", "解约", "发布", "同意并", "确认提交",
    )

    fun inspect(target: String?, texts: List<String>): ConfirmDraft? {
        val tapped = target.orEmpty().trim()
        val visible = texts.asSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val haystack = (listOf(tapped) + visible).joinToString(" ")

        val money = moneyPattern.find(haystack)?.value
        val tappedIsSensitive = privacyWords.any { tapped.contains(it) } ||
            irreversibleWords.any { tapped.contains(it) }

        if (money != null && (tappedIsSensitive || irreversibleWords.any { tapped.contains(it) })) {
            return ConfirmDraft(
                risk = ConfirmRisk.MONEY,
                title = "确认支付",
                fields = collect(visible, keep = payeeOf(visible) + listOf("金额" to money) + amountLineOf(visible, money)),
            )
        }
        if (privacyWords.any { tapped.contains(it) } || phonePattern.containsMatchIn(tapped) || accountPattern.containsMatchIn(tapped)) {
            return ConfirmDraft(
                risk = ConfirmRisk.PRIVACY,
                title = "确认提交个人信息",
                fields = collect(visible, keep = privacyOf(visible) + phoneOrAccountOf(haystack)),
            )
        }
        if (irreversibleWords.any { tapped.contains(it) }) {
            return ConfirmDraft(
                risk = ConfirmRisk.IRREVERSIBLE,
                title = "确认不可撤销操作",
                fields = collect(visible, keep = emptyList()),
            )
        }
        return null
    }

    /** 最多 4 条，去重、截断，避免确认卡片自己变成一屏长文。 */
    private fun collect(visible: List<String>, keep: List<Pair<String, String>>): List<ConfirmField> {
        val fields = LinkedHashMap<String, String>()
        keep.forEach { (label, value) ->
            if (value.isNotBlank()) fields.putIfAbsent(label, value)
        }
        if (fields.size < 4) {
            visible.forEach { line ->
                if (fields.size >= 4) return@forEach
                val trimmed = line.take(60)
                if (trimmed.length in 2..60) fields.putIfAbsent("屏上", trimmed)
            }
        }
        return fields.entries.map { ConfirmField(it.key, it.value) }.take(4)
    }

    private fun payeeOf(visible: List<String>): List<Pair<String, String>> =
        visible.firstOrNull { it.contains("收款") || it.contains("商家") || it.contains("店铺") }
            ?.let { listOf("收款方" to it.take(40)) }
            .orEmpty()

    private fun amountLineOf(visible: List<String>, amount: String): List<Pair<String, String>> =
        visible.firstOrNull { it.contains(amount) && it != amount }
            ?.let { listOf("订单" to it.take(40)) }
            .orEmpty()

    private fun privacyOf(visible: List<String>): List<Pair<String, String>> =
        visible.mapNotNull { line ->
            when {
                phonePattern.containsMatchIn(line) -> "手机号" to (phonePattern.find(line)?.value ?: line)
                accountPattern.containsMatchIn(line) -> "账号" to (accountPattern.find(line)?.value ?: line)
                else -> null
            }
        }.distinctBy { it.first }.take(2)

    private fun phoneOrAccountOf(haystack: String): List<Pair<String, String>> {
        phonePattern.find(haystack)?.let { return listOf("手机号" to it.value) }
        accountPattern.find(haystack)?.let { return listOf("账号" to it.value) }
        return emptyList()
    }
}
