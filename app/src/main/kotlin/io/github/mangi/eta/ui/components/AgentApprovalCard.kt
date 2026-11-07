package io.github.mangi.eta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 卡片上的一个选项。 */
internal data class AgentApprovalOptionUi(
    val id: String,
    val label: String,
    val description: String = "",
)

/**
 * 会话内的提问 / 审批卡。
 *
 * 不是「同意-拒绝」二元对话框：选项由来源决定。
 * - 拦截类：批准 / 拒绝 / 我自己来 /（非资金）这类以后不问；
 * - AI 主动提问 [ask_user]：模型自己给出的 2~4 个选项。
 *
 * 三种提交方式：
 * 1. 单选 —— 点一下立即提交；
 * 2. 多选 —— 勾选后按「确认（N 项）」一次性提交；
 * 3. 其他 —— 展开成**唯一**的输入态：选项列表收起，只留一个输入框并自动聚焦。
 *    之前选项和输入框同时在场，和底部聊天输入框叠成三个可写的地方，用户不知道该往哪儿写。
 */
internal data class AgentApprovalUi(
    val token: String,
    val title: String,
    /** 风险标签：资金 / 个人信息 / 不可撤销。空串表示不显示。 */
    val riskLabel: String = "",
    val fields: List<Pair<String, String>> = emptyList(),
    /** 静默档（个人信息类）：提醒不弹通知，只在这里留一条。 */
    val quiet: Boolean = false,
    /** 同一主体被重复拦截的次数；>1 时卡上显式提示，避免看起来像卡住了。 */
    val repeatCount: Int = 1,
    /** 正文：为什么问你、这一步会发生什么。 */
    val body: String = "",
    val options: List<AgentApprovalOptionUi> = emptyList(),
    val allowMultiple: Boolean = false,
)

@Composable
internal fun AgentApprovalCard(
    approval: AgentApprovalUi,
    onChoose: (String) -> Unit,
    /** 多选提交：一次把勾中的几项一起送回。 */
    onChooseMany: (List<String>) -> Unit = { onChoose(it.firstOrNull().orEmpty()) },
    /** 自由输入：用户自己写答案；第二个参数是同时勾中的选项（补充而非覆盖）。 */
    onChooseFreeform: (String, List<String>) -> Unit = { _, _ -> },
    /** 正在卡内输入：告诉宿主收起底部聊天输入框，避免两个输入框同时在场。 */
    onEditingChanged: (Boolean) -> Unit = {},
    /** 跳过：不回答，让模型自己往下走。 */
    onSkip: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // 换一张卡就重置勾选和输入，避免把上一题的答案带到下一题。
    var picked by remember(approval.token) { mutableStateOf(emptySet<String>()) }
    var freeformOpen by remember(approval.token) { mutableStateOf(false) }
    var freeformText by remember(approval.token) { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(freeformOpen) {
        onEditingChanged(freeformOpen)
        if (freeformOpen) runCatching { focusRequester.requestFocus() }
    }

    // 提交后这张卡会整个从 pending 里移除，LaunchedEffect 没机会补发 false。
    // 不在这里收尾，底部输入框会永久消失（实测踩到）。
    DisposableEffect(Unit) {
        onDispose { onEditingChanged(false) }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = MiuixTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(18.dp),
            )
            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 8.dp),
    ) {
        // 标题一直在：写到一半也知道自己在答哪一题。
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = approval.title,
                style = MiuixTheme.textStyles.body2,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (approval.repeatCount > 1) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "已拦 ${approval.repeatCount} 次",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                )
            }
            if (approval.riskLabel.isNotBlank()) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = approval.riskLabel,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.error,
                    maxLines = 1,
                )
            }
        }

        if (freeformOpen) {
            // 输入态：选项全部收起，屏上只留这一个可写的地方。
            val pickedLabels = approval.options.filter { it.id in picked }.map { it.label }
            Text(
                text = if (pickedLabels.isEmpty()) {
                    "在这里写你的答案，发送后这张卡就收起来。"
                } else {
                    "已选：${pickedLabels.joinToString("、")}。下面是补充，发送时一起交上去。"
                },
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 6.dp),
            )
            Spacer(Modifier.height(6.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = MiuixTheme.colorScheme.surface,
                        shape = RoundedCornerShape(12.dp),
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                BasicTextField(
                    value = freeformText,
                    onValueChange = { freeformText = it.take(500) },
                    textStyle = TextStyle(
                        color = MiuixTheme.colorScheme.onSurface,
                        fontSize = 15.sp,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    decorationBox = { inner ->
                        if (freeformText.isEmpty()) {
                            Text(
                                text = "写你要的答案…",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        inner()
                    },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    text = "返回选项",
                    onClick = {
                        freeformOpen = false
                        freeformText = ""
                    },
                    minWidth = 0.dp,
                    minHeight = 34.dp,
                    insideMargin = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    colors = ButtonDefaults.textButtonColors(),
                )
                TextButton(
                    text = "发送",
                    onClick = {
                        val answer = freeformText.trim()
                        if (answer.isNotEmpty()) {
                            onChooseFreeform(answer, approval.options.map { it.id }.filter { it in picked })
                        }
                    },
                    minWidth = 0.dp,
                    minHeight = 34.dp,
                    insideMargin = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
            return@Column
        }

        if (approval.body.isNotBlank()) {
            Text(
                text = approval.body,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        if (approval.fields.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            approval.fields.take(4).forEach { (label, value) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                ) {
                    Text(
                        text = label,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        modifier = Modifier.width(52.dp),
                    )
                    Text(
                        text = value,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        approval.options.forEachIndexed { index, option ->
            if (index > 0) {
                Spacer(Modifier.height(6.dp))
            }
            val checked = option.id in picked
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = if (checked) {
                            MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)
                        } else {
                            MiuixTheme.colorScheme.surface
                        },
                        shape = RoundedCornerShape(12.dp),
                    )
                    .clickable {
                        if (approval.allowMultiple) {
                            picked = if (checked) picked - option.id else picked + option.id
                        } else {
                            onChoose(option.id)
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (approval.allowMultiple) {
                        Text(
                            text = if (checked) "✓" else "○",
                            style = MiuixTheme.textStyles.body2,
                            color = if (checked) {
                                MiuixTheme.colorScheme.primary
                            } else {
                                MiuixTheme.colorScheme.onSurfaceVariantSummary
                            },
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        text = option.label,
                        style = MiuixTheme.textStyles.body2,
                        fontWeight = FontWeight.Medium,
                        color = if (approval.allowMultiple) {
                            MiuixTheme.colorScheme.onSurface
                        } else if (index == 0) {
                            MiuixTheme.colorScheme.primary
                        } else {
                            MiuixTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (option.description.isNotBlank()) {
                    Text(
                        text = option.description,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }

        // 「其他」：切换成输入态，不是并排再放一个输入框。
        Spacer(Modifier.height(6.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = MiuixTheme.colorScheme.surface,
                    shape = RoundedCornerShape(12.dp),
                )
                .clickable { freeformOpen = true }
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(
                text = "其他…",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }

        if (approval.allowMultiple) {
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (picked.isEmpty()) {
                    Text(
                        text = "可多选",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                } else {
                    TextButton(
                        text = "确认（${picked.size} 项）",
                        onClick = {
                            val chosen = approval.options.map { it.id }.filter { it in picked }
                            if (chosen.isNotEmpty()) onChooseMany(chosen)
                        },
                        minWidth = 0.dp,
                        minHeight = 34.dp,
                        insideMargin = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        }

        Spacer(Modifier.height(2.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                text = "跳过",
                onClick = onSkip,
                minWidth = 0.dp,
                minHeight = 30.dp,
                insideMargin = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                colors = ButtonDefaults.textButtonColors(),
            )
        }
    }
}
