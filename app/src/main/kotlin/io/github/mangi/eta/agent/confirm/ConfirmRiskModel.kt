package io.github.mangi.eta.agent.confirm

/** 一次决策拦截的风险等级，命中即拦截，不做「大概没事就放行」。 */
internal enum class ConfirmRisk { MONEY, PRIVACY, IRREVERSIBLE }

internal data class ConfirmField(
    val label: String,
    val value: String,
    val editable: Boolean = false,
)

/** 一次决策拦截：字段是给用户看「到底要发生什么」的最小信息集。 */
internal data class PendingConfirm(
    val token: String,
    val risk: ConfirmRisk,
    val title: String,
    val fields: List<ConfirmField>,
    /** 真实画面局部截图的本地路径，不用生成式卡片。 */
    val previewPath: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val deadlineAt: Long = createdAt + 120_000L,
) {
    fun expired(now: Long = System.currentTimeMillis()): Boolean = now >= deadlineAt
}

/** 卡片上的一个可选项。`id` 是回灌给模型的值，`label` 给用户看。 */
internal data class PromptOption(
    val id: String,
    val label: String,
    val description: String = "",
)

/** 系统内置选项 id。模型也能用这些 id 表达意图。 */
internal object PromptOptionId {
    const val APPROVE = "approve"
    const val REJECT = "reject"
    const val MANUAL = "manual"
    const val REMEMBER = "remember"
}

/**
 * 一次待用户裁决的拦截：会话内审批卡与通知共用同一份，避免两个入口口径分裂。
 *
 * [token] 是唯一凭据：批准/拒绝必须带它回来，过期或不匹配一律作废，
 * 防止用户点开旧通知时误批了后面那一单。
 */
internal data class PendingApproval(
    val token: String,
    /** 来源：拦截产生（guard）还是模型主动提问（ask）。决定用户选完怎么继续。 */
    val source: String = SOURCE_GUARD,
    val risk: ConfirmRisk,
    val title: String,
    val fields: List<ConfirmField>,
    /** 静默档：只在聊天页留卡，不发通知、不振动、不抢焦点。 */
    val quiet: Boolean,
    /** 主体签名：同一主体在宽限期内不再重复询问。 */
    val subject: String = "",
    /** 同一主体被重复拦截的次数（>1 时卡上提示，不再重复通知）。 */
    val repeatCount: Int = 1,
    /** 卡片正文：说明「这一步要做什么、为什么问你」。 */
    val body: String = "",
    /** 可选项，至少两个。空表示用默认的批准/拒绝。 */
    val options: List<PromptOption> = emptyList(),
    /** 是否允许多选（当前 UI 只做单选，字段先留着给多选批次）。 */
    val allowMultiple: Boolean = false,
    /** 归属会话：新开会话不再继承上一会话的卡。 */
    val conversationKey: String = "",
    val createdAt: Long = System.currentTimeMillis(),
) {
    val riskLabel: String
        get() = when (risk) {
            ConfirmRisk.MONEY -> "资金"
            ConfirmRisk.PRIVACY -> "个人信息"
            ConfirmRisk.IRREVERSIBLE -> "不可撤销"
        }

    /** 资金类不提供「以后不问」：钱每次都该人批。 */
    val canRemember: Boolean get() = risk != ConfirmRisk.MONEY

    fun expired(now: Long = System.currentTimeMillis()): Boolean = now - createdAt > LIFETIME_MS

    companion object {
        /** 超过这个时间，用户再点就当作没看见：不批、不拒，只作废。 */
        const val LIFETIME_MS = 10 * 60 * 1000L

        const val SOURCE_GUARD = "guard"
        const val SOURCE_ASK = "ask"
    }
}
