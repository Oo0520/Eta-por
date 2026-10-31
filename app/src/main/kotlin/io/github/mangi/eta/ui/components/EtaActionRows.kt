package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 行尾动作的语气：主操作 / 破坏性操作。
 *
 * 规范要点：只有"进入下一页"才用右箭头；就地执行的动作用文字按钮，
 * 就地展开的用上下折角图标。混用箭头会让用户误判点击后果。
 */
enum class EtaActionTone { Primary, Danger }

/**
 * 就地执行的动作行：行尾是文字按钮（如「保存」「套用」「删除」），不带右箭头。
 *
 * [actionLabel] 为空时行尾不画任何东西（纯信息行或标题本身已是动词）。
 */
@Composable
internal fun EtaActionPreference(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    actionLabel: String? = null,
    endIcon: ImageVector? = null,
    tone: EtaActionTone = EtaActionTone.Primary,
    startAction: (@Composable () -> Unit)? = null,
    endActions: (@Composable RowScope.() -> Unit)? = null,
    bottomAction: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    holdDownState: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val accent = when (tone) {
        EtaActionTone.Primary -> EtaPreferenceColors.Blue
        EtaActionTone.Danger -> MiuixTheme.colorScheme.error
    }
    // 破坏性动作整行变红并带垃圾桶图标，不只看行尾文字才认得出。
    val trailingIcon = endIcon ?: if (tone == EtaActionTone.Danger) Icons.Rounded.Delete else null
    EtaPreference(
        title = title,
        modifier = modifier,
        summary = summary,
        startAction = startAction,
        bottomAction = bottomAction,
        titleColor = if (tone == EtaActionTone.Danger) {
            BasicComponentDefaults.titleColor(color = MiuixTheme.colorScheme.error)
        } else {
            BasicComponentDefaults.titleColor()
        },
        enabled = enabled,
        holdDownState = holdDownState,
        onClick = onClick,
        endActions = {
            if (endActions != null) endActions()
            if (trailingIcon != null) {
                if (endActions != null || actionLabel != null) Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = trailingIcon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (tone == EtaActionTone.Danger) {
                        MiuixTheme.colorScheme.error
                    } else {
                        MiuixTheme.colorScheme.onBackground.copy(alpha = 0.35f)
                    },
                )
            }
            if (actionLabel != null) {
                if (endActions != null || trailingIcon != null) Spacer(Modifier.width(6.dp))
                Text(
                    text = actionLabel,
                    style = MiuixTheme.textStyles.body2,
                    fontWeight = FontWeight.Medium,
                    color = accent,
                )
            }
        },
    )
}

/** 就地展开/收起的行：行尾是上下折角图标，跟随 [expanded] 变化。 */
@Composable
internal fun EtaExpandPreference(
    title: String,
    expanded: Boolean,
    modifier: Modifier = Modifier,
    summary: String? = null,
    startAction: (@Composable () -> Unit)? = null,
    bottomAction: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    EtaPreference(
        title = title,
        modifier = modifier,
        summary = summary,
        startAction = startAction,
        bottomAction = bottomAction,
        enabled = enabled,
        onClick = onClick,
        endActions = {
            Icon(
                imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = stringResource(
                    if (expanded) R.string.eta_row_collapse else R.string.eta_row_expand,
                ),
                modifier = Modifier.size(22.dp),
                tint = MiuixTheme.colorScheme.onBackground.copy(alpha = 0.35f),
            )
        },
    )
}

/** 多选一的行：行尾是「已选」标记，不带右箭头（选了就停在这一页）。 */
@Composable
internal fun EtaSelectPreference(
    title: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    summary: String? = null,
    startAction: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    EtaPreference(
        title = title,
        modifier = modifier,
        summary = summary,
        startAction = startAction,
        enabled = enabled,
        onClick = onClick,
        endActions = {
            if (selected) {
                Text(
                    text = stringResource(R.string.prompt_studio_mode_selected),
                    style = MiuixTheme.textStyles.footnote2,
                    color = EtaPreferenceColors.Blue,
                )
            }
        },
    )
}
