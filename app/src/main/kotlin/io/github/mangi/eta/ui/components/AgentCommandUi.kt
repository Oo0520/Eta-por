package io.github.mangi.eta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.model.AgentPaletteBadge
import io.github.mangi.eta.ui.model.AgentPaletteEntry
import io.github.mangi.eta.ui.model.CommandConfirmUi
import io.github.mangi.eta.ui.model.CommandResultUi
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 命令面板：输入 `/` 后浮在输入框上方，列出命中的命令。
 *
 * 高度封在 320dp 以内，内容本身可滚动：候选多、描述长都不会被截掉或省略。
 * 手机上没有上下键，交互收敛成「点一条」：无参数命令点了直接执行，
 * 需要补参数的命令（含技能命令）点了补全成 `/name ` 继续输入。
 */
@Composable
internal fun AgentCommandPalette(
    entries: List<AgentPaletteEntry>,
    onPick: (AgentPaletteEntry) -> Unit,
    modifier: Modifier = Modifier,
    /** 主动展开时的标题（全部命令 / 技能 / 模型）；输入前缀过滤时为 null。 */
    title: String? = null,
    onClose: (() -> Unit)? = null,
    /** 可用高度上限：由输入框上沿到状态栏的空间决定，保证输入框不被顶出屏幕。 */
    maxHeight: Dp = CommandPaletteMaxHeight,
    /** 长按条目：把完整描述摊开看（列表里只留两行）。 */
    onLongPressEntry: ((AgentPaletteEntry) -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            // 面板只占屏幕高度的一小段，列表内部可滚动：候选多、描述长都不会被截断。
            .heightIn(max = maxHeight)
            .background(
                color = MiuixTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(18.dp),
            ),
    ) {
        if (title != null) {
            // 标题行压到 28dp：标题只是「这面板是什么」，不需要占一整行按钮的高度。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 2.dp, top = 4.dp)
                    .heightIn(min = 28.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MiuixTheme.textStyles.footnote1,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.weight(1f),
                )
                if (onClose != null) {
                    IconButton(onClick = onClose, minWidth = 28.dp, minHeight = 28.dp) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.agent_command_close),
                            modifier = Modifier.size(16.dp),
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 6.dp),
        ) {
            var lastSection: String? = null
            entries.forEach { entry ->
                val section = entry.section
                if (section != null && section != lastSection) {
                    lastSection = section
                    Text(
                        text = section,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 2.dp),
                    )
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { onPick(entry) },
                            onLongClick = onLongPressEntry?.let { callback -> { callback(entry) } },
                        )
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (entry.current) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MiuixTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(
                            text = entry.title,
                            style = MiuixTheme.textStyles.body2,
                            fontWeight = FontWeight.Medium,
                            color = MiuixTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        entry.badge?.let { badge ->
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = when (badge) {
                                    AgentPaletteBadge.Skill ->
                                        stringResource(R.string.agent_command_skill_tag)
                                    AgentPaletteBadge.SkillDisabled ->
                                        stringResource(R.string.agent_command_skill_disabled)
                                },
                                style = MiuixTheme.textStyles.footnote1,
                                color = if (badge == AgentPaletteBadge.SkillDisabled) {
                                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                                } else {
                                    MiuixTheme.colorScheme.primary
                                },
                                maxLines = 1,
                            )
                        }
                    }
                    // 列表里只占两行；完整描述长按条目展开，避免一条技能把面板塞满。
                    Text(
                        text = entry.subtitle,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

/** 命令面板的高度上限；实际运行时会取「输入框上沿到状态栏」的可用空间里更小的那个。 */
private val CommandPaletteMaxHeight = 260.dp

/**
 * 命令结果卡：`/status`、`/effort`、用法错误这类信息停留在这里，替代 2 秒就消失的 toast。
 */
@Composable
internal fun AgentCommandResultCard(
    result: CommandResultUi,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 240.dp)
            .background(
                color = MiuixTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(18.dp),
            )
            .padding(start = 14.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Row(
            modifier = Modifier.heightIn(min = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = result.title,
                style = MiuixTheme.textStyles.footnote1,
                fontWeight = FontWeight.Medium,
                color = if (result.isError) {
                    MiuixTheme.colorScheme.error
                } else {
                    MiuixTheme.colorScheme.onSurface
                },
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss, minWidth = 28.dp, minHeight = 28.dp) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.agent_command_close),
                    modifier = Modifier.size(16.dp),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        if (result.lines.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
            ) {
                result.lines.forEach { line ->
                    Text(
                        text = line,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 2.dp, end = 8.dp),
                    )
                }
            }
        }
    }
}

/**
 * 命令确认卡：未知命令（要不要按原样发出去）与带副作用的命令（压缩上下文、停止任务）共用。
 * 位置就在输入框上方，跟命令面板同一块区域，视线不用来回跳。
 */
@Composable
internal fun AgentCommandConfirmCard(
    confirm: CommandConfirmUi,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = MiuixTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(18.dp),
            )
            .padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
    ) {
        Text(
            text = confirm.title,
            style = MiuixTheme.textStyles.body2,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = confirm.detail,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                text = stringResource(R.string.agent_command_cancel),
                onClick = onDismiss,
                minWidth = 0.dp,
                minHeight = 34.dp,
                insideMargin = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                colors = ButtonDefaults.textButtonColors(),
            )
            TextButton(
                text = stringResource(
                    if (confirm.commandName == null) {
                        R.string.agent_command_send_as_text
                    } else {
                        R.string.agent_command_confirm_action
                    },
                ),
                onClick = onAccept,
                minWidth = 0.dp,
                minHeight = 34.dp,
                insideMargin = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}
