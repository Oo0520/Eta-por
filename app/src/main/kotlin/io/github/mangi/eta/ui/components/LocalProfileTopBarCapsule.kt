package io.github.mangi.eta.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.model.LocalToolProfile
import io.github.mangi.eta.agent.model.LocalToolProfileStore
import io.github.mangi.eta.ui.model.AgentModelOptionUi
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 顶栏的模式开关：只有「全部工具」和「纯聊天」两种，图标分段式，点一下就切。
 *
 * 模式只决定发给模型的工具清单，不影响工具自身的执行能力。
 *
 * 工作区是这颗胶囊的第三段：点它进入文件面板，此时高亮滑到第三格；
 * 点回「全部工具」或「纯聊天」即退出面板。三者共用一个滑动指示器，观感是一颗东西。
 * 语音播报在最左边，不再和会话列表抢位置。
 */

/**
 * 段顺序：纯聊天在左、全部工具居中、工作区在右。
 *
 * 纯聊天放最左是因为它是「收窄」的方向，往左让；全部工具是默认状态放中间最稳，
 * 手指落在中间也不容易误切；工作区是跳转入口，放最右与系统「更多」的位置一致。
 */
private val profileOptions = listOf(LocalToolProfile.CHAT, LocalToolProfile.FULL)

/** 旧配置里的中间档一律归到「全部工具」：存储值不动，等用户主动切一次才被改写。 */
internal fun profileOption(profile: LocalToolProfile): LocalToolProfile =
    if (profile == LocalToolProfile.CHAT) LocalToolProfile.CHAT else LocalToolProfile.FULL

/** 胶囊三段的统一高度：指示器和图标都按这个尺寸排。 */
private val CapsuleSegmentHeight = 27.dp

/** 指示器滑动时长：与面板滑入同一条曲线，看起来是同一个动作带出的。 */
private val CapsuleIndicatorMs = 280

/**
 * 顶栏模式胶囊：三段合并成一颗长胶囊——纯聊天 / 全部工具 / 工作区。
 *
 * 选中态用一颗滑动指示器表达，而不是每段各自画背景：各自画背景时高亮会「瞬移」到
 * 新段上，滑动则让三段读起来是同一个控件的不同位置。
 *
 * @param workspaceActive 工作区面板是否打开。打开时高亮停在第三格，
 *   点「全部工具」或「纯聊天」由调用方负责退出面板（本组件只负责把高亮滑回去）。
 */
@Composable
internal fun LocalProfileTopBarCapsule(
    model: AgentModelOptionUi?,
    compact: Boolean = false,
    workspaceActive: Boolean = false,
    onOpenWorkspace: () -> Unit = {},
    onCloseWorkspace: () -> Unit = {},
) {
    val profileKey = model?.let { it.providerId to it.modelId }
    var profile by remember(profileKey) {
        mutableStateOf(LocalToolProfileStore.current(profileKey?.first, profileKey?.second))
    }
    val current = profileOption(profile)
    val haptic = LocalHapticFeedback.current
    val workspaceLabel = stringResource(R.string.workspace_browser_title)

    // 三段等宽：选中索引 → 指示器位移。索引 0=纯聊天 1=全部工具 2=工作区。
    val selectedIndex = when {
        workspaceActive -> 2
        current == LocalToolProfile.CHAT -> 0
        else -> 1
    }
    // 记住上一段工作区状态：从工作区退出时高亮要滑回工具/聊天，而不是瞬移。
    var lastIndex by remember { mutableIntStateOf(if (current == LocalToolProfile.CHAT) 0 else 1) }
    if (!workspaceActive) lastIndex = selectedIndex

    // 半透明高亮：alpha 压到 0.42 一档，能透出底下的 surfaceContainer，
    // 不至于像一颗实心色块糊在顶栏上。指示器画在图标下层，所以图标不会被染混色。
    val selectedBrush = Brush.linearGradient(
        colors = listOf(
            MiuixTheme.colorScheme.primary.copy(alpha = 0.42f),
            MiuixTheme.colorScheme.primary.copy(alpha = 0.30f),
        ),
    )

    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(MiuixTheme.colorScheme.surfaceContainer)
            .padding(3.dp)
            .height(CapsuleSegmentHeight),
    ) {
        // 指示器先声明 = 画在底层，图标盖在它上面。之前画在上层，半透明时会把图标颜色染混。
        // 段宽固定，位移就是「索引 × 段宽」，不需要量父级宽度。
        val indicatorOffset by animateDpAsState(
            targetValue = CapsuleSegmentWidth * selectedIndex,
            animationSpec = tween(CapsuleIndicatorMs, easing = FastOutSlowInEasing),
            label = "capsule-indicator",
        )
        Box(
            modifier = Modifier
                .offset(x = indicatorOffset)
                .padding(3.dp)
                .width(CapsuleSegmentWidth - 6.dp)
                .height(CapsuleSegmentHeight - 6.dp)
                .clip(CircleShape)
                .background(selectedBrush),
        )
        Row(
            modifier = Modifier.fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 第一段：纯聊天
            CapsuleSegment(
                icon = Icons.AutoMirrored.Rounded.Chat,
                label = stringResource(R.string.local_profile_chat),
                selected = selectedIndex == 0,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    LocalToolProfileStore.save(LocalToolProfile.CHAT, profileKey?.first, profileKey?.second)
                    profile = LocalToolProfile.CHAT
                    if (workspaceActive) onCloseWorkspace()
                    LocalProfileSwitchNotice.publish(LocalToolProfileSwitch.Chat)
                },
            )
            // 第二段：全部工具（默认）
            CapsuleSegment(
                icon = Icons.Rounded.Widgets,
                label = stringResource(R.string.local_profile_full),
                selected = selectedIndex == 1,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    LocalToolProfileStore.save(LocalToolProfile.FULL, profileKey?.first, profileKey?.second)
                    profile = LocalToolProfile.FULL
                    if (workspaceActive) onCloseWorkspace()
                },
            )
            // 第三段：工作区
            CapsuleSegment(
                icon = Icons.Rounded.Folder,
                label = workspaceLabel,
                selected = selectedIndex == 2,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    if (workspaceActive) onCloseWorkspace() else onOpenWorkspace()
                },
            )
        }

    }
}

/** 胶囊单段的宽度：三段必须等宽，否则指示器按 1/3 算出的位移会与图标错位。 */
private val CapsuleSegmentWidth = 33.dp

/** 胶囊单段的宽度：三段必须等宽，否则指示器按 1/3 算出的位移会与图标错位。 */

/** 胶囊里的一段：图标 + 选中色。背景由滑动指示器画，这里只负责图标和点击。 */
@Composable
private fun CapsuleSegment(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .width(CapsuleSegmentWidth)
            .height(CapsuleSegmentHeight)
            .clip(CircleShape)
            .clickable { onClick() }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(17.dp),
            tint = if (selected) {
                MiuixTheme.colorScheme.onPrimary
            } else {
                MiuixTheme.colorScheme.onSurfaceVariantSummary
            },
        )
    }
}

