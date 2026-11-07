package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.window.WindowListPopup

/**
 * 输入框右上角那三个入口（MCP / 技能 / 内存）共用的弹层。
 *
 * 动画不在这里实现：用对话页右上角 ⋯ 菜单用的同一个组件 `WindowListPopup`，
 * 进场、退场、返回、遮罩全部由 miuix 的 `ListPopupLayout` / `ListPopupContent` 驱动。
 *
 * 位置按「它属于输入框」来定：
 * - 右缘对齐锚点（输入框卡片）右缘，左缘比卡片再收 24dp，不铺到接近全宽；
 * - 底边压在锚点上方 8dp；上方放不下才翻到下方；
 * - 宽度 = 卡片宽 − 24dp；高度沿用 [heightFraction]，超出滚动。
 */
@Composable
internal fun CornerScaleDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    @Suppress("UNUSED_PARAMETER") widthFraction: Float = 0.92f,
    heightFraction: Float = 0.62f,
    /** 内容自带高度时关掉固定高度：上下文/用量这类小面板按内容收边，不铺满一屏。 */
    wrapContent: Boolean = false,
    /** 展开方向与对齐边：true 时贴锚点左缘、从左侧展开；默认仍贴右缘。 */
    alignStart: Boolean = false,
    @Suppress("UNUSED_PARAMETER") bottomPadding: Dp = CornerDialogBottomPadding,
    content: @Composable (requestClose: () -> Unit) -> Unit,
) {
    val density = LocalDensity.current
    val container = LocalWindowInfo.current.containerSize
    // 卡片两侧各留 16dp 再收 24dp，比输入框窄一档；不用百分比，避免窄屏/宽屏都贴着屏幕边。
    val windowWidth = with(density) { container.width.toDp() }
    val popupWidth = (windowWidth - 56.dp).coerceAtLeast(240.dp).coerceAtMost(windowWidth - 24.dp)
    val popupHeight = with(density) { (container.height * heightFraction).toDp() }
    val anchorGap = with(density) { 8.dp.roundToPx() }

    val positionProvider = remember(anchorGap, alignStart) {
        object : PopupPositionProvider {
            override fun getMargins(): PaddingValues = PaddingValues(0.dp)

            override fun calculatePosition(
                anchorBounds: IntRect,
                windowBounds: IntRect,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
                popupMargin: IntRect,
                alignment: PopupPositionProvider.Align,
            ): IntOffset {
                // 默认右缘贴住锚点右缘（入口在最右），不按窗口居中；
                // alignStart 时改成左缘贴锚点左缘，展开方向跟着从左开始。
                val maxX = (windowBounds.right - popupContentSize.width).coerceAtLeast(windowBounds.left)
                val x = if (alignStart) {
                    anchorBounds.left.coerceIn(windowBounds.left, maxX)
                } else {
                    (anchorBounds.right - popupContentSize.width).coerceIn(windowBounds.left, maxX)
                }
                val above = anchorBounds.top - popupContentSize.height - anchorGap
                val below = anchorBounds.bottom + anchorGap
                val y = when {
                    above >= windowBounds.top -> above
                    below + popupContentSize.height <= windowBounds.bottom -> below
                    else -> (windowBounds.bottom - popupContentSize.height).coerceAtLeast(windowBounds.top)
                }
                return IntOffset(x, y)
            }
        }
    }

    // 弹层自己维护「请求关闭 → 播完退场 → 真正消失」的过程，外部 show 只负责进出。
    var visible by remember { mutableStateOf(show) }
    LaunchedEffect(show) { visible = show }

    WindowListPopup(
        show = visible,
        popupPositionProvider = positionProvider,
        alignment = if (alignStart) PopupPositionProvider.Align.Start else PopupPositionProvider.Align.End,
        onDismissRequest = {
            if (visible) visible = false
            onDismiss()
        },
        minWidth = popupWidth,
    ) {
        Box(
            modifier = if (wrapContent) {
                Modifier.width(popupWidth)
            } else {
                Modifier
                    .width(popupWidth)
                    .height(popupHeight)
            },
        ) {
            content {
                if (visible) visible = false
                onDismiss()
            }
        }
    }
}

/** 旧实现里卡片距屏幕底部的留白；现在位置交给弹层定位器，保留给调用方兼容。 */
internal val CornerDialogBottomPadding = 104.dp

/** 面板内容区统一高度：由调用方决定，这里只给个默认值参考。 */
internal val CornerDialogDefaultHeightFraction = 0.62f
