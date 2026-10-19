package io.github.mangi.eta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.model.AgentModelOptionUi
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 顶栏状态胶囊组：内存占用 + 工具档。
 *
 * 上下文用量不在这里——输入栏右侧已经有圆环按钮（同一份数字），顶栏再放一遍是重复，
 * 省下来的宽度正好留给工具档，工具档才有机会显示全称。
 *
 * 宽度仍然自己算：顶栏 actions 行给不给可信约束不一定，按「屏宽 − 其余顶栏控件占位」兜底，
 * 再与父级约束取较小值，预算内逐级压缩文字，所以胶囊组永远不会超出可用宽度。
 */

/** 顶栏里除胶囊组以外必须占住的位置：导航图标、标题、朗读、新建、⋯ 与两侧留白。 */
private val TopBarCapsuleReserve = 216.dp

/** 父级给不出可信约束时的兜底下限。 */
private val TopBarCapsuleFloor = 56.dp

/** 各胶囊的估算占位；估偏大只会让胶囊早一点变短标。 */
private val FullMemoryCapsuleWidth = 76.dp
private val CompactMemoryCapsuleWidth = 40.dp
private val FullProfileCapsuleWidth = 100.dp
private val CompactProfileCapsuleWidth = 56.dp
private val CapsuleSpacing = 4.dp

/**
 * 预算档位。工具档优先：它是能点的入口，只要放得下就写全称；
 * 内存是只读诊断信息，宽度不够时先变短标、再整枚收起。
 */
private val ProfileFullBudget = FullProfileCapsuleWidth
private val MemoryWithProfileBudget = FullProfileCapsuleWidth + CompactMemoryCapsuleWidth + CapsuleSpacing
private val MemoryFullBudget = FullProfileCapsuleWidth + FullMemoryCapsuleWidth + CapsuleSpacing

@Composable
internal fun ConversationStatusCapsules(
    model: AgentModelOptionUi?,
    modifier: Modifier = Modifier,
) {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    BoxWithConstraints(modifier) {
        val budget = minOf(maxWidth, (screenWidth - TopBarCapsuleReserve).coerceAtLeast(TopBarCapsuleFloor))
        val showProfile = budget >= CompactProfileCapsuleWidth
        val profileFull = budget >= ProfileFullBudget
        val showMemory = budget >= MemoryWithProfileBudget
        val memoryFull = budget >= MemoryFullBudget
        Row(
            horizontalArrangement = Arrangement.spacedBy(CapsuleSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 工具档写在前面：Row 按声明顺序分配剩余宽度，放前面才能保住它不被内存挤掉。
            if (showProfile) LocalProfileTopBarCapsule(model, compact = !profileFull)
            if (showMemory) MemoryUsageCapsule(compact = !memoryFull)
        }
    }
}

@Composable
internal fun StatusCapsule(text: String, modifier: Modifier, description: String = text) {
    Text(text,
        modifier = modifier.background(MiuixTheme.colorScheme.surfaceContainer, CircleShape)
            .semantics { contentDescription = description }.padding(horizontal = 8.dp, vertical = 5.dp),
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        style = MiuixTheme.textStyles.footnote1, fontSize = 11.sp,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}
