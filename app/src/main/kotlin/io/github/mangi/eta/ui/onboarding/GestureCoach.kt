package io.github.mangi.eta.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.components.EtaTextButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 手势教学：蒙层 + 手指动画，只负责「看」，**不接管任何触摸**。
 *
 * 【为什么这里没有任何 pointerInput】
 * Compose 的触摸是「命中测试」：同一父节点下，只有最上层命中的那条链能收到事件，
 * 兄弟节点不会因为「上层没消费」而补收。之前在这里挂了一个只观察不消费的 pointerInput，
 * 结果整条手势全被它接走，侧栏根本拖不动。
 *
 * 所以完成判定不在这里做：由 [OnboardingHost] 看侧栏/工作区/导航面板是否真的被打开。
 * 这样既不需要抢事件，也能保证「真的滑出来了」才算学会。
 */
@Composable
internal fun GestureCoach(
    step: GestureCoachScript.Step,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(enabled = true) { onSkip() }

    val transition = rememberInfiniteTransition(label = "gesture-coach")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "gesture-coach-finger",
    )

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val screenWidth = maxWidth
        val screenHeight = maxHeight
        val cardWidth = (screenWidth - 40.dp).coerceAtMost(320.dp)

        // 蒙层：纯背景，没有 pointerInput，触摸直接穿透到下面的侧栏与聊天页。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.42f)),
        )

        // 手指轨迹
        val startX: androidx.compose.ui.unit.Dp
        val startY: androidx.compose.ui.unit.Dp
        val endX: androidx.compose.ui.unit.Dp
        val endY: androidx.compose.ui.unit.Dp
        when (step.direction) {
            GestureDirection.SwipeRight -> {
                startX = screenWidth * 0.14f
                endX = screenWidth * 0.66f
                startY = screenHeight * 0.46f
                endY = startY
            }
            GestureDirection.SwipeLeft -> {
                startX = screenWidth * 0.86f
                endX = screenWidth * 0.34f
                startY = screenHeight * 0.46f
                endY = startY
            }
            GestureDirection.SwipeUp -> {
                startX = screenWidth * 0.50f
                endX = startX
                startY = screenHeight * 0.80f
                endY = screenHeight * 0.60f
            }
        }
        Box(
            modifier = Modifier
                .offset(
                    x = startX + (endX - startX) * progress - 14.dp,
                    y = startY + (endY - startY) * progress - 14.dp,
                )
                .size(28.dp)
                .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.9f), CircleShape),
        )

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                // 顶栏下面一点：顶栏约 56dp，再留 8dp 呼吸。
                .padding(top = 64.dp)
                .width(cardWidth)
                .shadow(elevation = 10.dp, shape = RoundedCornerShape(20.dp), clip = false)
                .squircleSurface(color = Color.White, cornerRadius = 20.dp)
                .squircleBorder(
                    width = 1.dp,
                    color = Color.Black.copy(alpha = 0.12f),
                    cornerRadius = 20.dp,
                )
                .padding(16.dp),
        ) {
            Text(
                text = step.title,
                style = MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.SemiBold,
                color = Color.Black,
            )
            Text(
                text = step.body,
                style = MiuixTheme.textStyles.footnote1,
                color = Color.Black.copy(alpha = 0.68f),
                modifier = Modifier.padding(top = 6.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "滑一下试试",
                    style = MiuixTheme.textStyles.footnote1,
                    color = Color.Black.copy(alpha = 0.45f),
                )
                EtaTextButton(
                    text = "以后再说",
                    onClick = onSkip,
                    minWidth = 0.dp,
                )
            }
        }
    }
}
