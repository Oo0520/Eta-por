package io.github.mangi.eta.agent.display.dashboard

/*
 * 注意：悬浮窗当前使用 AgentDashboardView（View 自绘版）。
 * 这一份 Compose 组合保留作 Activity 内仪表盘 / 设计参考：
 * 挂到 Application 级 overlay 上时，release 包会因为找不到
 * ViewTreeLifecycleOwner 在 ComposeView attach 阶段整进程闪退（2026102702 实测）。
 */

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.agent.display.VirtualScreenViewport

internal data class VisionTouch(
    val x: Int,
    val y: Int,
    val startedAt: Long,
    val label: String = "",
)

internal data class VisionFocus(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val label: String = "",
)

/** Agent 视线层的输入：灵屏像素坐标，绘制时再经 viewport 换算。 */
internal data class AgentVisionState(
    val displayWidth: Int = 0,
    val displayHeight: Int = 0,
    val touch: VisionTouch? = null,
    val focus: VisionFocus? = null,
)

private val ScrimTop = Color(0xD90A0C0F)
private val ScrimBottom = Color(0xB30A0C0F)
private val AccentAi = Color(0xFF7BD8FF)
private val AccentUser = Color(0xFF5FE08C)
private val AccentWarn = Color(0xFFFFC44D)
private val AccentStop = Color(0xFFFF6B6B)
private val PillIdle = Color(0x33FFFFFF)

private fun stepTextStyle(size: Int = 12) = TextStyle(
    color = Color(0xFFF2F4F7),
    fontSize = size.sp,
    fontWeight = FontWeight.Medium,
)

/**
 * 仪表盘主体。宿主只负责把它塞进悬浮窗（ComposeView），
 * 画面由 [surface] 提供，chrome 全部浮在画面上方，不改变原窗口尺寸。
 */
@Composable
internal fun AgentDashboard(
    state: AgentDashboardState,
    vision: AgentVisionState,
    viewport: VirtualScreenViewport?,
    expanded: Boolean,
    onPauseResume: () -> Unit,
    onCollapse: () -> Unit,
    onTakeover: () -> Unit,
    onHandBack: () -> Unit,
    onSteer: () -> Unit,
    onTerminate: () -> Unit,
    surface: @Composable BoxScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        surface()
        if (!state.mode.visible) return@Box

        AgentVisionLayer(vision, viewport, Modifier.matchParentSize())

        Column(Modifier.fillMaxSize()) {
            AgentTopStatusBar(state, onPauseResume, onCollapse)
            Spacer(Modifier.weight(1f))
            AnimatedVisibility(
                visible = expanded,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                AgentBottomToolbar(state, onTakeover, onHandBack, onSteer, onTerminate)
            }
        }

        if (state.mode == AgentControlMode.USER_TAKEOVER || state.mode == AgentControlMode.HANDING_BACK) {
            TakeoverFrame(Modifier.matchParentSize())
        }
    }
}

/** 顶部状态条：状态点 + 一句话步骤 + 进度 + 暂停/继续 + 收起。总高 34dp。 */
@Composable
internal fun AgentTopStatusBar(
    state: AgentDashboardState,
    onPauseResume: () -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(ScrimTop, Color(0x000A0C0F))))
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(34.dp)
                .padding(start = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AgentStatusDot(state.mode)
            Spacer(Modifier.width(6.dp))
            BasicText(
                text = state.step.ifBlank { "待命" },
                modifier = Modifier.weight(1f),
                style = stepTextStyle(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (state.stepTotal > 0) {
                Spacer(Modifier.width(6.dp))
                BasicText(
                    text = "${state.stepIndex.coerceAtMost(state.stepTotal)}/${state.stepTotal}",
                    style = TextStyle(color = Color(0xB3FFFFFF), fontSize = 11.sp),
                )
            }
            Spacer(Modifier.width(4.dp))
            DashIconButton(
                kind = if (state.mode == AgentControlMode.AI_PAUSED) DashIcon.PLAY else DashIcon.PAUSE,
                onClick = onPauseResume,
            )
            DashIconButton(kind = DashIcon.COLLAPSE, onClick = onCollapse)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(Color(0x1FFFFFFF))
        ) {
            Box(
                Modifier
                    .fillMaxWidth(state.progress)
                    .height(2.dp)
                    .background(
                        when (state.mode) {
                            AgentControlMode.USER_TAKEOVER, AgentControlMode.HANDING_BACK -> AccentUser
                            AgentControlMode.AWAITING_CONFIRM, AgentControlMode.SUSPENDED -> AccentWarn
                            else -> AccentAi
                        }
                    )
            )
        }
    }
}

/** 底部工具栏：接管 / 输入指令 / 终止，取代旧的全屏·键盘·关闭三件套。 */
@Composable
internal fun AgentBottomToolbar(
    state: AgentDashboardState,
    onTakeover: () -> Unit,
    onHandBack: () -> Unit,
    onSteer: () -> Unit,
    onTerminate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val userOwns = state.mode.inputOwnedByUser
    Box(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color(0x000A0C0F), ScrimBottom)))
            .padding(start = 8.dp, end = 8.dp, top = 14.dp, bottom = 8.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (userOwns) {
                DashPill("交还给 AI", AccentUser, filled = true, onClick = onHandBack)
            } else {
                DashPill("接管控制", AccentAi, filled = false, onClick = onTakeover)
            }
            DashPill("输入指令", Color(0xFFB6BEC9), filled = false, onClick = onSteer)
            Spacer(Modifier.weight(1f))
            DashPill("终止任务", AccentStop, filled = false, onClick = onTerminate)
        }
    }
}

/** Agent 视线：触控点 ripple + 关注节点虚线框。纯绘制，不拦触摸。 */
@Composable
internal fun AgentVisionLayer(
    vision: AgentVisionState,
    viewport: VirtualScreenViewport?,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "agent-vision")
    val ripple by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Restart),
        label = "ripple",
    )
    Canvas(modifier) {
        val vp = viewport ?: return@Canvas

        vision.focus?.let { focus ->
            val left = vp.left + focus.left * vp.scale
            val top = vp.top + focus.top * vp.scale
            val width = (focus.right - focus.left) * vp.scale
            val height = (focus.bottom - focus.top) * vp.scale
            if (width > 0f && height > 0f) {
                drawRoundRect(
                    color = AccentAi.copy(alpha = 0.85f),
                    topLeft = Offset(left, top),
                    size = Size(width, height),
                    cornerRadius = CornerRadius(4.dp.toPx()),
                    style = Stroke(
                        width = 1.4.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f)),
                    ),
                )
                focus.label.takeIf { it.isNotBlank() }?.let {
                    // 标签只用一个小色块占位，文字交给 Java 层 Canvas 绘制成本更低；
                    // 这里保留色块，让「Agent 正在看这里」一眼可辨。
                    drawRoundRect(
                        color = AccentAi.copy(alpha = 0.9f),
                        topLeft = Offset(left, (top - 4.dp.toPx()).coerceAtLeast(0f)),
                        size = Size(10.dp.toPx(), 2.dp.toPx()),
                        cornerRadius = CornerRadius(1.dp.toPx()),
                    )
                }
            }
        }

        vision.touch?.let { touch ->
            val center = Offset(vp.left + touch.x * vp.scale, vp.top + touch.y * vp.scale)
            val reach = 16.dp.toPx()
            drawCircle(
                color = Color(0x80FFFFFF),
                radius = reach * (0.35f + 0.65f * ripple),
                center = center,
                style = Stroke(width = 1.4.dp.toPx()),
            )
            drawCircle(color = Color(0x59FFFFFF), radius = 8.dp.toPx(), center = center)
            drawCircle(color = Color.White, radius = 3.5.dp.toPx(), center = center)
        }
    }
}

/** 接管态：绿色呼吸边框 + 角标，和 AI 态一眼可分。 */
@Composable
internal fun TakeoverFrame(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "takeover")
    val breath by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse),
        label = "breath",
    )
    Box(modifier) {
        Box(
            Modifier
                .matchParentSize()
                .border(width = 2.dp, color = AccentUser.copy(alpha = breath), shape = RoundedCornerShape(14.dp))
        )
        Box(
            Modifier
                .padding(8.dp)
                .align(Alignment.TopEnd)
                .clip(RoundedCornerShape(50))
                .background(AccentUser.copy(alpha = 0.92f))
                .padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            BasicText(
                "你正在操作",
                style = TextStyle(color = Color(0xFF0A1013), fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
            )
        }
    }
}

@Composable
private fun AgentStatusDot(mode: AgentControlMode) {
    val color = when (mode) {
        AgentControlMode.USER_TAKEOVER, AgentControlMode.HANDING_BACK -> AccentUser
        AgentControlMode.AWAITING_CONFIRM, AgentControlMode.SUSPENDED -> AccentWarn
        AgentControlMode.AI_PAUSED -> Color(0xFF9AA4B2)
        AgentControlMode.TERMINATED -> AccentStop
        else -> AccentAi
    }
    val transition = rememberInfiniteTransition(label = "dot")
    val pulse by transition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse",
    )
    Canvas(Modifier.size(12.dp)) {
        val r = size.minDimension / 2f
        drawCircle(color = color.copy(alpha = 0.28f * pulse), radius = r)
        drawCircle(color = color, radius = r * 0.52f)
    }
}

@Composable
private fun DashPill(
    label: String,
    accent: Color,
    filled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (filled) accent else accent.copy(alpha = 0.16f))
            .border(1.dp, accent.copy(alpha = if (filled) 0f else 0.55f), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        BasicText(
            label,
            style = TextStyle(
                color = if (filled) Color(0xFF0A1013) else Color(0xFFF2F4F7),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
    }
}

private enum class DashIcon { PAUSE, PLAY, COLLAPSE }

@Composable
private fun DashIconButton(kind: DashIcon, onClick: () -> Unit) {
    Box(
        Modifier
            .size(28.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(12.dp)) {
            val w = size.width
            val h = size.height
            when (kind) {
                DashIcon.PAUSE -> {
                    val bar = w * 0.22f
                    drawRect(Color(0xE6FFFFFF), topLeft = Offset(w * 0.22f, h * 0.18f), size = Size(bar, h * 0.64f))
                    drawRect(Color(0xE6FFFFFF), topLeft = Offset(w * 0.56f, h * 0.18f), size = Size(bar, h * 0.64f))
                }
                DashIcon.PLAY -> {
                    val path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(w * 0.30f, h * 0.16f)
                        lineTo(w * 0.82f, h * 0.50f)
                        lineTo(w * 0.30f, h * 0.84f)
                        close()
                    }
                    drawPath(path, Color(0xE6FFFFFF))
                }
                DashIcon.COLLAPSE -> {
                    drawRect(
                        Color(0xE6FFFFFF),
                        topLeft = Offset(w * 0.14f, h * 0.46f),
                        size = Size(w * 0.72f, h * 0.10f),
                    )
                }
            }
        }
    }
}
