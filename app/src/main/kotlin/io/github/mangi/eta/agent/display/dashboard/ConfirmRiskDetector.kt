package io.github.mangi.eta.agent.display.dashboard

import org.json.JSONObject

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
    /**
     * 不可撤销动作词表：**唯一一份**。
     *
     * 之前点击判定和页面级兜底各写一套，结果「立即付款」在兜底里能拦、点按钮时漏拦
     * （2026-11-06 真机实测踩到）。要加词只加这里，别在别的文件另起一份。
     */
    internal val irreversibleWords = listOf(
        "发送", "提交订单", "提交订单并支付", "确认提交", "同意并",
        "立即支付", "确认支付", "去支付", "立即付款", "确认付款", "去付款", "付款",
        "去结算", "结算", "立即购买", "确认下单", "下单",
        "转账", "删除", "清空", "注销", "退订", "解绑", "解约", "发布",
    )

    /** 供页面级兜底复用同一份词表。 */
    internal fun isIrreversible(text: String): Boolean = irreversibleWords.any { text.contains(it) }

    /** 终端类工具：命令本身就是判定依据。 */
    private val terminalTools = setOf("terminal", "terminal_job", "terminal_daemon")

    /**
     * 明确的高危形态。刻意用最短的精确子串而不是宽泛正则——
     * 宁可少拦几个变体，也不要因为「rm -rf 一个临时目录」天天烦人。
     */
    private val destructiveSnippets = listOf(
        "rm -rf /", "rm -fr /", "rm -rf ~",
        "mkfs", "dd if=", "of=/dev/block", "> /dev/block/",
        "pm clear", "cmd package clear",
        "wipe data", "wipe userdata", "fastboot flash", "fastboot erase",
        "chmod -R 777 /",
    )

    /** 写这些位置等于改系统，出错不可回滚。 */
    private val protectedWritePrefixes = listOf(
        "/system", "/vendor", "/data/adb", "/init.rc", "/default.prop",
    )

    /**
     * 非 GUI 工具的危险判定：只看工具名与参数。
     *
     * GUI 那条线由 [inspect] 处理（要屏上文本）；这里是另一条独立入口——
     * terminal 里一条 `rm -rf /data` 的破坏力不比误点「确认支付」小，
     * 而它原先完全不在拦截范围内。
     */
    fun inspectTool(toolName: String, args: JSONObject): ConfirmDraft? {
        if (toolName in terminalTools) {
            val command = args.optString("command").trim()
            if (command.isEmpty()) return null
            val hit = destructiveSnippets.firstOrNull { command.contains(it) } ?: return null
            // rm -rf /data/local/tmp/xxx 是常规清理，别拦。
            if (hit.startsWith("rm -rf /") && command.contains("/data/local/tmp")) return null
            return ConfirmDraft(
                risk = ConfirmRisk.IRREVERSIBLE,
                title = "确认执行高危命令",
                fields = listOf(ConfirmField("命令", command.take(80))),
            )
        }
        if (toolName == "write_file" || toolName == "edit_file") {
            val path = args.optString("path").trim()
            if (path.isEmpty()) return null
            if (protectedWritePrefixes.none { path.startsWith(it) }) return null
            return ConfirmDraft(
                risk = ConfirmRisk.IRREVERSIBLE,
                title = "确认写入系统目录",
                fields = listOf(ConfirmField("路径", path.take(80))),
            )
        }
        return null
    }

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
                fields = collect(
                    visible,
                    keep = listOf("动作" to tapped) + privacyOf(visible) + phoneOrAccountOf(haystack),
                ),
            )
        }
        if (isIrreversible(tapped)) {
            // 至少要写清「点在什么上」：屏上文本可能是空的（没 observe 过就直接点），
            // 那时 keep 里这一条就是用户唯一能看到的依据。
            return ConfirmDraft(
                risk = ConfirmRisk.IRREVERSIBLE,
                title = "确认不可撤销操作",
                fields = collect(visible, keep = listOf("动作" to tapped)),
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
