package io.github.mangi.eta.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal enum class InlineAlertLevel { Error, Warning, Info }

/**
 * 行内校验/提示条：贴在输入框或表单项下方，跟内容一起占位，不浮在界面上。
 *
 * 【硬约束】绝不自动消失，也不靠 toast 顶替。
 * 只有调用方把状态改成「已修正」时，这个组件才会从组合里移除。
 * 想加 `delay(3000)` 自动收起的，先想清楚用户还没改完时他去哪看错误信息。
 *
 * 与 toast 的分工：
 * - toast 只用于「一次性、无需用户修正」的结果（复制成功之类）；
 * - 需要用户动手改的（Key 填错、地址不通、名称重复）一律走这里。
 */
@Composable
internal fun InlineAlert(
    text: String,
    modifier: Modifier = Modifier,
    level: InlineAlertLevel = InlineAlertLevel.Error,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val accent = when (level) {
        InlineAlertLevel.Error -> MiuixTheme.colorScheme.error
        InlineAlertLevel.Warning -> MiuixTheme.colorScheme.primary
        InlineAlertLevel.Info -> MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    val icon = when (level) {
        InlineAlertLevel.Error -> Icons.Rounded.ErrorOutline
        InlineAlertLevel.Warning -> Icons.Rounded.Warning
        InlineAlertLevel.Info -> Icons.Rounded.Info
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(accent.copy(alpha = 0.09f))
            .padding(horizontal = 12.dp, vertical = 10.dp)
            // 文案由短变长时平滑撑开，避免输入框被顶得跳一下。
            .animateContentSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = accent,
        )
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote1,
            color = accent,
            modifier = Modifier.weight(1f),
        )
        if (actionLabel != null && onAction != null) {
            EtaTextButton(
                text = actionLabel,
                onClick = onAction,
                minWidth = 0.dp,
                insideMargin = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}
