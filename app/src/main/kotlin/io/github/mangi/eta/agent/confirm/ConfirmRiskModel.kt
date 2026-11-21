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
