package io.github.mangi.eta.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.model.AgentContextUsageUi
import io.github.mangi.eta.ui.model.formatContextUsage
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 输入框里的上下文环。
 *
 * 只负责「显示用量 + 通知外部打开面板」：面板本身挂在输入框那一层（和内存 / Token 面板同层），
 * 这样三个面板的位置、展开方向完全一致，不会各自按自己的锚点算出不同的左缘。
 */
@Composable
internal fun AgentContextUsageButton(
    usage: AgentContextUsageUi,
    onOpenPanel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress = usage.progress
    val progressColor = when {
        progress == null -> MiuixTheme.colorScheme.onSurfaceVariantActions
        progress >= 0.95f -> StatusError
        progress >= 0.80f -> StatusWarning
        else -> MiuixTheme.colorScheme.primary
    }
    val locale = LocalConfiguration.current.locales[0]
    val summary = formatContextUsage(
        usage = usage,
        noUsageText = stringResource(R.string.context_no_previous_usage),
        noLimitText = stringResource(R.string.context_no_model_limit),
        locale = locale,
    )
    val usageDescription = stringResource(
        R.string.context_usage_description,
        summary.replace('\n', ' '),
    )
    IconButton(
        onClick = onOpenPanel,
        minWidth = ChatInputActionSize,
        minHeight = ChatInputActionSize,
        modifier = modifier,
    ) {
        CircularProgressIndicator(
            progress = progress ?: 0f,
            colors = ProgressIndicatorDefaults.progressIndicatorColors(
                foregroundColor = progressColor,
                disabledForegroundColor = progressColor,
                backgroundColor = MiuixTheme.colorScheme.secondaryContainer,
            ),
            strokeWidth = 2.5.dp,
            size = ChatInputActionIconSize,
            modifier = Modifier.semantics {
                contentDescription = usageDescription
            },
        )
    }
}
