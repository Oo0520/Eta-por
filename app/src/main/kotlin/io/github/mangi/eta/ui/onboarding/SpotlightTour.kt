package io.github.mangi.eta.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.navigation.AppRoute
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 聚光灯式漫游引导。
 *
 * 挂载要求：必须是整屏最上层（高于页面、低于 Dialog），且与锚点控件在同一个 window
 * 的同一棵 root 上——否则 `boundsInRoot` 的坐标对不上，洞会挖在别处。
 *
 * 挖洞方式：一次 `drawPath` 画半透明黑，路径用「整屏 − 洞」的差集。
 * 不用 `BlendMode.Clear` 是因为那样要开离屏层，容易把下层真实界面一起啃掉。
 */
@Composable
internal fun SpotlightTour(
    pack: TourPack,
    onFinished: () -> Unit,
    /**
     * 一步都没能显示出来时调用（锚点全丢）。
     *
     * 和 [onFinished] 的区别：这条路径**不能**把包标记成已看过，否则用户一次没看到
     * 就再也不会被引导；但也不能立刻重试，否则会原地死循环。调用方负责本次会话内拉黑。
     */
    onAborted: () -> Unit = onFinished,
    onStepShown: (Int, TourStep) -> Unit = { _, _ -> },
    onNavigate: ((AppRoute) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var index by remember(pack.id) { mutableIntStateOf(0) }
    var hole by remember(pack.id) { mutableStateOf<Rect?>(null) }
    // 真正露出过几步：用来区分「用户走完了」和「锚点全没找到、被静默跳完」。
    var shownCount by remember(pack.id) { mutableIntStateOf(0) }
    val step = pack.steps.getOrNull(index)
    // 屏幕范围：判断锚点是否真的落在可见区域里。
    // Canvas 的 draw lambda 不是 @Composable，主题色必须在这里取好。
    val holeStrokeColor = MiuixTheme.colorScheme.primary.copy(alpha = 0.9f)
    val windowSize = LocalWindowInfo.current.containerSize
    val screenRect = remember(windowSize) {
        Rect(0f, 0f, windowSize.width.toFloat(), windowSize.height.toFloat())
    }

    val advance: () -> Unit = {
        when {
            index + 1 < pack.steps.size -> index += 1
            shownCount > 0 -> onFinished()
            else -> onAborted()
        }
    }

    // 每步重新取锚点：等不到就跳过这一步，绝不把整条引导卡在首帧。
    LaunchedEffect(pack.id, index) {
        val current = pack.steps.getOrNull(index) ?: return@LaunchedEffect
        hole = null
        current.navigateTo?.let { route -> onNavigate?.invoke(route) }
        val target = awaitAnchor(
            id = current.anchor,
            timeoutMillis = if (current.navigateTo != null) 2000L else 1200L,
        )
        // 只有「中心点落在屏幕内」才算有效。
        // 侧栏/工作区的锚点控件在关闭状态下就存在，只是被推到屏幕外，
        // boundsInRoot 会是负坐标；只看 width>0 会把洞挖到屏幕外，用户以为没弹引导。
        // 第一次等不到（或等到屏幕外的旧坐标）就再等一轮：
        // 侧栏/工作区的吸附动画期间布局还没稳定，单次等待容易误判成「锚点丢失」。
        var resolved = target
        if (resolved == null || screenRect.contains(resolved.center).not()) {
            delay(250)
            resolved = awaitAnchor(current.anchor, 800L)
        }
        val onScreen = resolved?.let { screenRect.contains(it.center) } == true
        if (resolved == null || !onScreen) {
            advance()
        } else {
            hole = resolved
            shownCount += 1
            onStepShown(index, current)
        }
    }

    // 返回键 = 退出整条引导，不是退页面。
    BackHandler(enabled = true) { onFinished() }

    val currentStep = step ?: return
    val density = LocalDensity.current

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val screenWidth = maxWidth
        val screenHeight = maxHeight
        // 初值给估计高度：先按估计画上去，onSizeChanged 量到真实高度后当帧修正。
        // 若初值给 0 并用「量到才渲染」当条件，会形成死锁——不渲染就永远量不到。
        var bubbleHeight by remember { mutableStateOf(160.dp) }
        val bubbleWidth = (screenWidth - 32.dp).coerceAtMost(320.dp)

        Box(
            modifier = Modifier
                .fillMaxSize()
                // 洞外吞掉所有触摸：Conpose 的 Main pass 是子节点先处理，
                // 所以气泡上的按钮照常可点，只有洞外与未放行的洞内会被拦下。
                .pointerInput(currentStep.anchor, hole, currentStep.allowTouch) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val allowTouch = currentStep.allowTouch
                            val position = event.changes.firstOrNull()?.position
                            val insideHole = allowTouch && position != null &&
                                hole?.contains(position) == true
                            if (!insideHole) {
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                },
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val target = hole ?: return@Canvas
                val shape = currentStep.shape
                val holePath = Path().apply {
                    when (shape) {
                        is TourHoleShape.RoundedRect -> {
                            val pad = shape.padding.toPx()
                            addRoundRect(
                                RoundRect(
                                    rect = Rect(
                                        left = target.left - pad,
                                        top = target.top - pad,
                                        right = target.right + pad,
                                        bottom = target.bottom + pad,
                                    ),
                                    cornerRadius = CornerRadius(
                                        shape.cornerRadius.toPx(),
                                        shape.cornerRadius.toPx(),
                                    ),
                                ),
                            )
                        }

                        TourHoleShape.Circle -> {
                            val radius = maxOf(target.width, target.height) / 2f + 6.dp.toPx()
                            addOval(Rect(center = target.center, radius = radius))
                        }
                    }
                }
                val mask = Path().apply {
                    addRect(Rect(Offset.Zero, size))
                    // op() 是成员函数：结果写回 receiver，参数是「另一个路径 + 运算类型」。
                    op(this, holePath, PathOperation.Difference)
                }
                drawPath(path = mask, color = Color.Black.copy(alpha = 0.62f))
                // 深色主题下黑色遮罩几乎看不出，再用一圈主色把洞框出来。
                drawPath(
                    path = holePath,
                    color = holeStrokeColor,
                    style = Stroke(width = 2.dp.toPx()),
                )
            }

            // 气泡：先量高度，再按上下剩余空间决定放哪一侧，最后夹进安全区不越界。
            val anchorRect = hole
            if (anchorRect != null) {
                val holeTop = with(density) { anchorRect.top.toDp() }
                val holeBottom = with(density) { anchorRect.bottom.toDp() }
                val spaceAbove = holeTop
                val spaceBelow = screenHeight - holeBottom
                val placeAbove = when (currentStep.placement) {
                    TourPlacement.Above -> true
                    TourPlacement.Below -> false
                    TourPlacement.Auto -> spaceBelow < bubbleHeight + 24.dp && spaceAbove > spaceBelow
                }
                val rawY = if (placeAbove) {
                    holeTop - bubbleHeight - 12.dp
                } else {
                    holeBottom + 12.dp
                }
                val maxY = (screenHeight - bubbleHeight - 12.dp).coerceAtLeast(12.dp)
                val bubbleY: Dp = rawY.coerceIn(12.dp, maxY)

                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = bubbleY)
                        .width(bubbleWidth)
                        // 和空状态卡同样避坑：不用 EtaCard 的 insideMargin，改 padding 保内边距。
                        .shadow(
                            elevation = 10.dp,
                            shape = RoundedCornerShape(20.dp),
                            clip = false,
                        )
                        // 与手势卡片同一套：固定白底黑字，不跟主题。
                        .squircleSurface(color = Color.White, cornerRadius = 20.dp)
                        .squircleBorder(
                            width = 1.dp,
                            color = Color.Black.copy(alpha = 0.12f),
                            cornerRadius = 20.dp,
                        )
                        .onSizeChanged { size ->
                            bubbleHeight = with(density) { size.height.toDp() }
                        }
                        .padding(16.dp),
                ) {
                        Text(
                            text = currentStep.title,
                            style = MiuixTheme.textStyles.body1,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.Black,
                        )
                        Text(
                            text = currentStep.body,
                            style = MiuixTheme.textStyles.body2,
                            color = Color.Black.copy(alpha = 0.68f),
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        Row(
                            modifier = Modifier
                                .padding(top = 12.dp)
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "${index + 1}/${pack.steps.size}",
                                style = MiuixTheme.textStyles.footnote1,
                                color = Color.Black.copy(alpha = 0.45f),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                EtaTextButton(
                                    text = "跳过",
                                    onClick = onFinished,
                                    minWidth = 0.dp,
                                )
                                EtaTextButton(
                                    text = if (index + 1 < pack.steps.size) "下一步" else "知道了",
                                    onClick = advance,
                                    minWidth = 0.dp,
                                )
                            }
                        }
                }
            }
        }
    }
}
