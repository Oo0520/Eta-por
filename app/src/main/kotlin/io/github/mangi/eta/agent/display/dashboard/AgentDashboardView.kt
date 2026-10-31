package io.github.mangi.eta.agent.display.dashboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import io.github.mangi.eta.agent.display.VirtualScreenViewport
import kotlin.math.sin

/**
 * 仪表盘（View 自绘版）。
 *
 * 为什么不用 ComposeView：浮窗挂在 Application 上，没有 Activity/Service 可当
 * ViewTreeLifecycleOwner；release 包经 R8 之后 ComposeView 在 attach 阶段
 * 抛 `ViewTreeLifecycleOwner not found`，整个进程闪退。
 * 仪表盘只有一条状态栏 + 一条工具栏 + 两个叠加绘制层，自绘既避开这个坑，
 * 也比每帧 recomposition 省电（浮窗本身已经在跑 60fps 的帧循环）。
 *
 * 触摸纪律：只有按钮区域消费事件，其余一律返回 false，
 * 让 ViewGroup 继续派发给下层的 VirtualScreenSurfaceView（手指直通虚拟屏）。
 */
internal class AgentDashboardView(context: Context) : View(context) {

    /** 接管态顶栏 ←：退出接管，交还给 AI。 */
    var onExit: (() -> Unit)? = null
    /** 普通态顶栏 ✕：结束虚拟屏会话。 */
    var onClose: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF2F4F7.toInt()
        textSize = 12f * scaledDensity
    }
    private val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xB3FFFFFF.toInt()
        textSize = 11f * scaledDensity
    }

    private var state = AgentDashboardState()
    private var vision = AgentVisionState()
    private var expanded = false
    /** 由浮窗直接下发：接管态不依赖 run 状态，避免没任务时全屏却没有按钮。 */
    private var takeover = false
    private var displayWidth = 0
    private var displayHeight = 0
    private var viewport: VirtualScreenViewport? = null

    private val exitRect = RectF()


    private val topHeight = 34f * density
    private val progressHeight = 2f * density
    private val iconBox = 28f * density

    private val scratch = RectF()
    private val path = Path()

    fun render(
        state: AgentDashboardState,
        vision: AgentVisionState,
        displayWidth: Int,
        displayHeight: Int,
        expanded: Boolean,
        takeover: Boolean,
    ) {
        this.state = state
        this.vision = vision
        this.displayWidth = displayWidth
        this.displayHeight = displayHeight
        this.expanded = expanded
        this.takeover = takeover
        updateViewport()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateViewport()
    }

    private fun updateViewport() {
        viewport = if (displayWidth > 0 && displayHeight > 0 && width > 0 && height > 0) {
            VirtualScreenViewport.fit(width, height, displayWidth, displayHeight)
        } else {
            null
        }
    }

    private fun dp(value: Float): Float = value * density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        if (takeover) {
            // 接管态：顶栏（右上角 ←）+ 绿框，与 run 状态无关。
            drawVision(canvas)
            drawTopBar(canvas)
            drawTakeover(canvas)
            if (needsAnimation()) postInvalidateOnAnimation()
            return
        }

        // 普通态：没有任务时也画顶栏，保证始终有 ✕ 可关。
        drawTopBar(canvas)
        if (!state.mode.visible) return
        drawVision(canvas)
        if (needsAnimation()) postInvalidateOnAnimation()
    }

    private fun needsAnimation(): Boolean {
        if (state.mode.inputOwnedByUser) return true
        val touch = vision.touch ?: return false
        return SystemClock.uptimeMillis() - touch.startedAt < 1200L
    }

    // ---- 顶部状态栏 ----

    private fun drawTopBar(canvas: Canvas) {
        val w = width.toFloat()
        val tier = dashTierFor(w / density)
        val barHeight = when (tier) {
            DashTier.MICRO -> dp(20f)
            DashTier.COMPACT -> dp(28f)
            DashTier.REGULAR -> topHeight
        }
        scratch.set(0f, 0f, w, barHeight)
        fill.shader = LinearGradient(
            0f, 0f, 0f, barHeight,
            intArrayOf(0xD90A0C0F.toInt(), 0x000A0C0F),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(scratch, fill)
        fill.shader = null

        val accent = accentColor()
        // 文字也跟着状态色走，比纯白更容易和左侧状态点对上。
        label.color = (accent and 0x00FFFFFF) or 0xCC000000.toInt()
        val pulse = 0.5f + 0.5f * sin(SystemClock.uptimeMillis() / 420.0).toFloat()
        val dotY = barHeight / 2f
        val dotX = if (tier == DashTier.MICRO) barHeight / 2f else dp(14f)
        fill.color = (accent and 0x00FFFFFF) or ((40 + 30 * pulse).toInt() shl 24)
        canvas.drawCircle(dotX, dotY, dp(6f), fill)
        fill.color = accent
        canvas.drawCircle(dotX, dotY, dp(3.2f), fill)

        exitRect.setEmpty()

        val box = if (tier == DashTier.REGULAR) iconBox else dp(26f)
        val margin = if (tier == DashTier.REGULAR) dp(4f) else dp(1f)
        exitRect.set(w - margin - box, (barHeight - box) / 2f, w - margin, (barHeight + box) / 2f)
        drawIcon(canvas, exitRect, if (takeover) Icon.BACK else Icon.CLOSE)
        var textRight = exitRect.left - dp(6f)

        if (tier != DashTier.MICRO) {
            if (state.stepTotal > 0 && tier == DashTier.REGULAR) {
                val counter = "${state.stepIndex.coerceAtMost(state.stepTotal)}/${state.stepTotal}"
                small.textAlign = Paint.Align.RIGHT
                canvas.drawText(counter, textRight, barHeight / 2f + small.textSize / 3f, small)
                small.textAlign = Paint.Align.LEFT
                textRight -= small.measureText(counter) + dp(6f)
            }
            val textLeft = dotX + dp(12f)
            val avail = (textRight - textLeft).coerceAtLeast(dp(16f))
            val step = state.mode.label + (state.step.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
            val size = if (tier == DashTier.COMPACT) 11f * scaledDensity else label.textSize
            val paint = if (tier == DashTier.COMPACT) small.apply { color = 0xFFF2F4F7.toInt() } else label
            paint.textSize = size
            if (avail > dp(24f)) {
                val shown = TextUtils.ellipsize(step, paint, avail, TextUtils.TruncateAt.END).toString()
                canvas.drawText(shown, textLeft, barHeight / 2f + paint.textSize / 3f, paint)
            }
            if (tier == DashTier.COMPACT) {
                small.color = 0xB3FFFFFF.toInt()
                small.textSize = 11f * scaledDensity
                label.textSize = 12f * scaledDensity
            }
        }

        val progress = state.progress
        fill.color = 0x1FFFFFFF
        canvas.drawRect(0f, barHeight, w, barHeight + progressHeight, fill)
        if (progress > 0f) {
            fill.color = accent
            canvas.drawRect(0f, barHeight, w * progress, barHeight + progressHeight, fill)
        }
    }

    private fun drawIcon(canvas: Canvas, box: RectF, icon: Icon) {
        val c = box.centerX()
        val cy = box.centerY()
        val s = dp(5.5f)
        line.color = 0xE6FFFFFF.toInt()
        line.strokeWidth = dp(1.6f)
        line.strokeCap = Paint.Cap.ROUND
        when (icon) {
            Icon.CLOSE -> {
                canvas.drawLine(c - s, cy - s, c + s, cy + s, line)
                canvas.drawLine(c + s, cy - s, c - s, cy + s, line)
            }
            Icon.BACK -> {
                // 一个「←」：退出接管，交还给 AI
                canvas.drawLine(c + s, cy, c - s, cy, line)
                canvas.drawLine(c - s, cy, c - s * 0.2f, cy - s * 0.75f, line)
                canvas.drawLine(c - s, cy, c - s * 0.2f, cy + s * 0.75f, line)
            }
        }
        line.strokeCap = Paint.Cap.BUTT
    }

    // ---- Agent 视线 ----

    private fun drawVision(canvas: Canvas) {
        val vp = viewport ?: return
        val now = SystemClock.uptimeMillis()

        vision.focus?.let { focus ->
            val left = vp.left + focus.left * vp.scale
            val top = vp.top + focus.top * vp.scale
            val right = vp.left + focus.right * vp.scale
            val bottom = vp.top + focus.bottom * vp.scale
            if (right > left && bottom > top) {
                line.color = 0xD97BD8FF.toInt()
                line.strokeWidth = dp(1.4f)
                line.pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(6f), dp(4f)), 0f)
                scratch.set(left, top, right, bottom)
                canvas.drawRoundRect(scratch, dp(4f), dp(4f), line)
                line.pathEffect = null
            }
        }

        val touch = vision.touch ?: return
        val age = now - touch.startedAt
        if (age > 1200L) return
        val cx = vp.left + touch.x * vp.scale
        val cy = vp.top + touch.y * vp.scale
        val phase = (age % 900L) / 900f
        fill.color = 0x80FFFFFF.toInt()
        line.color = (0x80FFFFFF.toInt() and 0x00FFFFFF) or ((128 * (1f - phase)).toInt().coerceIn(0, 255) shl 24)
        line.strokeWidth = dp(1.4f)
        canvas.drawCircle(cx, cy, dp(16f) * (0.35f + 0.65f * phase), line)
        fill.color = 0x59FFFFFF
        canvas.drawCircle(cx, cy, dp(8f), fill)
        fill.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(cx, cy, dp(3.5f), fill)
    }

    // ---- 接管态 ----

    private fun drawTakeover(canvas: Canvas) {
        val breath = 0.45f + 0.55f * (0.5f + 0.5f * sin(SystemClock.uptimeMillis() / 700.0).toFloat())
        line.color = (UserGreen and 0x00FFFFFF) or ((breath * 255).toInt().coerceIn(0, 255) shl 24)
        line.strokeWidth = dp(2f)
        scratch.set(dp(1f), dp(1f), width - dp(1f), height - dp(1f))
        canvas.drawRoundRect(scratch, dp(14f), dp(14f), line)

        val tag = "你正在操作"
        small.textAlign = Paint.Align.LEFT
        val tw = small.measureText(tag) + dp(16f)
        val th = dp(18f)
        val right = width - dp(8f)
        val top = dp(38f)
        scratch.set(right - tw, top, right, top + th)
        fill.color = 0xF25FE08C.toInt()
        canvas.drawRoundRect(scratch, th / 2f, th / 2f, fill)
        small.color = 0xFF0A1013.toInt()
        canvas.drawText(tag, scratch.left + dp(8f), scratch.centerY() + small.textSize / 3f, small)
        small.color = 0xB3FFFFFF.toInt()
    }

    private fun accentColor(): Int = when (state.mode) {
        AgentControlMode.USER_TAKEOVER, AgentControlMode.HANDING_BACK -> UserGreen
        AgentControlMode.AWAITING_CONFIRM, AgentControlMode.SUSPENDED -> WarnAmber
        AgentControlMode.AI_PAUSED -> 0xFF9AA4B2.toInt()
        AgentControlMode.TERMINATED -> StopRed
        else -> AgentBlue
    }

    // ---- 触摸：只吃按钮 ----

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_DOWN) return false
        if (hitButton(event.x, event.y, fire = true)) return true
        return false
    }

    /** 右上角唯一按钮：普通态 ✕ 关闭会话，接管态 ← 退出接管。 */
    private fun hitButton(x: Float, y: Float, fire: Boolean): Boolean {
        if (exitRect.isEmpty || !exitRect.contains(x, y)) return false
        if (fire) {
            if (takeover) onExit?.invoke() else onClose?.invoke()
        }
        return true
    }

    private enum class Icon { CLOSE, BACK }

    private companion object {
        val AgentBlue = 0xFF7BD8FF.toInt()
        val UserGreen = 0xFF5FE08C.toInt()
        val WarnAmber = 0xFFFFC44D.toInt()
        val StopRed = 0xFFFF6B6B.toInt()
        val ColorGray = 0xFFB6BEC9.toInt()
    }
}

/**
 * 窗口太窄时降级显示，而不是把按钮挤在一起。
 * 阈值按内容区宽度（dp）算：<132dp 只留状态点；<208dp 收成两个等宽按钮。
 */
internal enum class DashTier { MICRO, COMPACT, REGULAR }

internal fun dashTierFor(widthDp: Float): DashTier = when {
    widthDp < 132f -> DashTier.MICRO
    widthDp < 208f -> DashTier.COMPACT
    else -> DashTier.REGULAR
}
