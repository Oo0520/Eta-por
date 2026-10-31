package io.github.mangi.eta.agent.accessibility

/** 可在 JVM 中验证的节点身份指纹；viewId 不是列表项唯一标识。 */
internal data class AccessibilityNodeIdentity(
    val uniqueId: String,
    val windowId: Int,
    val packageName: String,
    val className: String,
    val viewId: String,
    val text: String,
    val description: String,
    val password: Boolean,
) {
    val strong: Boolean
        get() = uniqueId.isNotBlank() || text.isNotBlank() || description.isNotBlank()

    fun matches(refreshed: AccessibilityNodeIdentity): Boolean {
        if (windowId != refreshed.windowId) return false
        if (packageName != refreshed.packageName) return false
        if (className != refreshed.className) return false
        if (password != refreshed.password) return false
        if (uniqueId != refreshed.uniqueId) return false
        if (viewId.isNotBlank() && viewId != refreshed.viewId) return false
        // uniqueId 只证明还是同一个虚拟节点，不证明它仍表达模型观察时的动作语义。
        // 但「列表项里只有未读数/时间在跳」这种情况不该判成换了目标：QQ 会话列表、美团商家卡片
        // 的文本每秒都在变，严格相等会让这些页面的节点动作全部失效。这里只忽略数字差异，
        // 文字部分必须一致，配合 windowId / class / viewId 已经足够定位。
        if (text != refreshed.text && text.withoutDigits() != refreshed.text.withoutDigits()) return false
        if (description != refreshed.description &&
            description.withoutDigits() != refreshed.description.withoutDigits()
        ) return false
        return true
    }

    private fun String.withoutDigits(): String = filterNot { it.isDigit() }
}

/**
 * 窗口内容变化后，只有真正稳定且在观察范围内可证明唯一的身份才能继续使用。
 * 截断快照无法证明 text/desc 指纹在窗口其余部分不存在重复项。
 */
internal object AccessibilityIdentityFreshnessPolicy {
    fun canBypassContentChange(
        hasUniqueId: Boolean,
        snapshotTruncated: Boolean,
        identityMatchCount: Int,
        clickable: Boolean = false,
    ): Boolean =
        // 语义唯一就够用：可点击节点（列表项、按钮）即使快照被截断、viewId 为空，
        // 也不会因为「旁边还有别的行」就判成无法定位 —— 那正是 QQ / 美团这类
        // 长列表最容易踩的坑。
        identityMatchCount == 1 && (hasUniqueId || !snapshotTruncated || clickable)
}
