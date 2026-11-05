package io.github.mangi.eta.agent.display

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MotionEvent
import android.view.Surface
import android.graphics.SurfaceTexture
import android.view.TextureView
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import io.github.mangi.eta.agent.overlay.GestureIndicatorHost
import org.json.JSONObject
import kotlin.math.hypot

/**
 * 灵屏画面宿主。两种出帧方式共用同一个控件：
 * - 直通（direct）：TextureView 的 Surface 交给 root helper 当灵屏输出面，合成器直接画进来，零拷贝。
 * - 回落（fallback）：沿用截屏→JPEG→base64 的整帧管线，画到 ImageView 上。
 * TextureView 用普通 View 合成，不挖窗口空洞，手势指示器可以正常叠在上面。
 */
internal class VirtualScreenSurfaceView(context: Context) : FrameLayout(context) {
    /**
     * 直通宿主面用 SurfaceView，不用 TextureView。
     *
     * 实测：overlay 窗口里 TextureView 的 isAvailable 恒为 false、onSurfaceTextureAvailable
     * 一次都不回调，即使补了 FLAG_HARDWARE_ACCELERATED 也一样——直通因此永远建立不起来。
     * SurfaceView 的 holder.surface 是真正的可写 Surface，由 SurfaceFlinger 直接分配，
     * 不依赖窗口硬件加速，交给 VirtualDisplay.setSurface 就能出画面。
     *
     * Z 序：默认 SurfaceView 的 surface 位于窗口内容「之下」，其他 View 画在它上面，
     * 所以直通时把 image 置 GONE、indicators 照常画，白点仍然盖在画面之上。
     */
    /**
     * 画面宿主用 TextureView，不用 SurfaceView。
     *
     * SurfaceView 有三条硬伤，直通关掉之后完全得不偿失：
     * 1. 内容在独立图层，父容器的 clipToOutline 裁不了它 → 浮窗圆角丢失、变直角；
     * 2. View.setAlpha 不影响它的洞 → 浮窗半透明失效、变纯黑不透明；
     * 3. 它参与 View 层级里的触摸分发，会吃掉一部分点击/滑动。
     * 直通（把 Surface 交给合成器）确实需要真正的可写 Surface，但那条路当前已关闭，
     * 等重新启用时再用独立的宿主控件实现，不要拿浮窗的画面宿主去换。
     */
    private val video = TextureView(context).apply {
        isOpaque = true
        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                texture = st
                applyBufferSize()
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                applyBufferSize()
            }

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                texture = null
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
    }
    private val image = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val indicators = GestureIndicatorHost(context)
    private var bitmap: Bitmap? = null
    private var info: VirtualDisplayInfo? = null
    private var lastGesture = 0L
    private var down: MotionEvent? = null
    private var downSession: String? = null
    private var hostSurface: Surface? = null
    private var texture: SurfaceTexture? = null

    /** direct=true 时画面由合成器直出，不再吃整帧。 */
    var direct = false
        private set

    /** true=等比完整显示（旧小窗 FIT_CENTER 行为）；false=等比铺满裁切（查看页全屏行为）。 */
    var fitContent = false

    var onInput: (String, JSONObject) -> Unit = { _, _ -> }
    var onHostSurface: (Surface?) -> Unit = {}
    var touchEnabled = true

    /** 真实帧尺寸：窗口比例与触控换算都以它为准，保证投影铺满、不出现信箱黑边。 */
    private var frameWidth = 0
    private var frameHeight = 0
    private var lastViewportLog = ""
    var onFrameSize: ((Int, Int) -> Unit)? = null

    init {
        video.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            applyBufferSize()
            tryAttachHost("layout")
        }
        video.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                VscrDiag.line("直通 宿主 SurfaceView attach 到窗口")
                postDelayed({ tryAttachHost("attached") }, 120)
            }
            override fun onViewDetachedFromWindow(v: View) {
                VscrDiag.line("直通 宿主 SurfaceView 从窗口分离")
            }
        })
        addView(video, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(indicators, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    /** 切换直通/回落。回落时把 TextureView 收起，释放取面，画面交回整帧管线。 */
    fun setDirectMode(enabled: Boolean) {
        if (direct == enabled) return
        direct = enabled
        if (enabled) {
            image.setImageDrawable(null)
            bitmap?.let { runCatching { it.recycle() } }
            bitmap = null
        } else {
        }
        // 直通切换不触发布局：必须自己重算一次变换，否则会留着上一次的缩放矩阵，
        // 合成器写进 buffer 的整屏画面会被再缩一次，小窗里就只剩中间一小块。
        applyViewport()
        VscrDiag.line("直通切换 direct=" + direct + " 视图=" + width + "x" + height +
            " 帧=" + frameWidth + "x" + frameHeight)
    }

    fun setFrame(next: Bitmap, display: VirtualDisplayInfo) {
        // 直通已经建立：画面由合成器直出，这一帧是切换瞬间的残留，
        // 直接丢掉——否则它会触发 onFrameSize，让浮窗按 JPEG 帧尺寸把自己缩掉
        // （实测直通成功后外框从 288x632 掉到 72x158dp）。
        if (direct) {
            next.recycle()
            return
        }
        info = display
        if (next.width > 0 && next.height > 0 && (next.width != frameWidth || next.height != frameHeight)) {
            frameWidth = next.width
            frameHeight = next.height
            onFrameSize?.invoke(next.width, next.height)
        }
        image.setImageBitmap(next)
        bitmap?.takeIf { it !== next }?.recycle()
        bitmap = next
    }

    /** 当前已推给控件的源尺寸，供外层判断要不要再推一次。 */
    val infoWidth: Int get() = info?.width ?: 0
    val infoHeight: Int get() = info?.height ?: 0

    fun setDisplayInfo(display: VirtualDisplayInfo?) {
        info = display
        applyBufferSize()
        applyViewport()
        // 不依赖 onSurfaceTextureAvailable：实测该回调在 overlay 窗口里可能一次都不来。
        // 这里主动探一次，再排一次延迟重试，覆盖「布局还没量出尺寸」的时机。
        tryAttachHost("setDisplayInfo")
        postDelayed({ tryAttachHost("retry") }, 400)
    }

    /**
     * 主动从 TextureView 取宿主 Surface 交给直通。
     *
     * 只在 [onSurfaceTextureAvailable] 里取面是不够的：那个回调在部分 overlay 场景下
     * 根本不触发（实测 vscr.log 里连探针都没有），直通就永远是 direct=false。
     * 这里改成「谁都可以叫一次，取到了就不重复取」。
     */
    private var attachProbeCount = 0

    /**
     * SurfaceView 的 buffer 尺寸。
     *
     * 直通：必须等于 display 尺寸，合成器写入的整屏才能 1:1 落进 buffer；
     *       用采集尺寸（800x1760）会让 1440x3168 的画面被缩一次、再被控件拉伸一次，糊且错位。
     * 回落：用采集尺寸，JPEG 帧本来就是那个大小。
     */
    private fun applyBufferSize() {
        val d = info ?: return
        // 回落管线用采集尺寸；直通若将来启用，再用 display 尺寸。
        val w = if (direct) d.width else d.captureWidth
        val h = if (direct) d.height else d.captureHeight
        texture?.let { runCatching { it.setDefaultBufferSize(w, h) } }
    }

    private fun tryAttachHost(from: String) {
        if (hostSurface != null) return
        val vw = width
        val vh = height
        val available = runCatching { video.isAvailable }.getOrDefault(false)
        // 布局会反复触发，只记前几次，避免把 vscr.log 刷爆。
        if (attachProbeCount < 6) {
            attachProbeCount++
            VscrDiag.line("直通 主动取面 from=$from view=" + vw + "x" + vh + " available=" + available)
        }
        if (!available || vw <= 0 || vh <= 0) return
        val st = runCatching { video.surfaceTexture }.getOrNull() ?: return
        val next = runCatching { Surface(st) }.getOrNull() ?: return
        if (!next.isValid) return
        applyBufferSize()
        hostSurface = next
        VscrDiag.line("直通 主动取面成功 valid=" + next.isValid)
        onHostSurface?.invoke(next)
    }

    // fill the whole box, keep the source aspect ratio (center-crop like a full screen)
    private fun applyViewport() {
        val display = info ?: return
        val vw = width
        val vh = height
        // 直通时画面是合成器按 display 尺寸直出的，比例必须按 display 算。
        // 用 JPEG 帧尺寸（800x1760）会把 1440x3168 的内容放大 1.8 倍，
        // 实测表现就是「设置」标题撑满半屏、右侧内容被裁掉。
        val w = if (!direct && frameWidth > 0) frameWidth else display.width
        val h = if (!direct && frameHeight > 0) frameHeight else display.height
        if (vw <= 0 || vh <= 0 || w <= 0 || h <= 0) return
        // TextureView resets the buffer size to its own view size on layout; force it back to the
        // virtual display size or SurfaceFlinger only fills a corner of the buffer.
        applyBufferSize()
        // 直通时也一律按“装得下”算：换横竖屏的中间几帧窗口还没跟上，
        // 用“铺满”会把画面放大到视图外，看起来就只剩一半、其余发白。
        val scale = if (fitContent || direct) {
            minOf(vw.toFloat() / w, vh.toFloat() / h)
        } else {
            maxOf(vw.toFloat() / w, vh.toFloat() / h)
        }
        val matrix = android.graphics.Matrix()
        // 直通：合成器把整屏写进 buffer，TextureView 默认已铺满控件，任何缩放都会让画面缩成中间一小块。
        // 回落：画面由 ImageView(FIT_CENTER) 整帧显示，TextureView 必须收起且不得残留缩放矩阵。
        // TextureView 用空矩阵铺满控件：buffer 尺寸与控件宽高比一致，不需要额外缩放。
        //
        // video 必须常驻 VISIBLE：GONE/INVISIBLE 的 TextureView 不会创建 SurfaceTexture，
        // 也就永远不会回调 onSurfaceTextureAvailable，直通建立不起来（和 SurfaceView 同一个坑）。
        // 回落时它没有内容，被上层的 image（JPEG 整帧）完全盖住，看不出差别。
        video.setTransform(android.graphics.Matrix())
        video.visibility = View.VISIBLE
        image.visibility = if (direct) View.GONE else View.VISIBLE
        val tag = "视图=" + vw + "x" + vh + " 源=" + w + "x" + h + " direct=" + direct +
            " fitContent=" + fitContent + " scale=" + String.format("%.4f", scale) +
            " 实画=" + (w * scale).toInt() + "x" + (h * scale).toInt()
        if (tag != lastViewportLog) {
            lastViewportLog = tag
            VscrDiag.line("画面 " + tag)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyViewport()
    }

    fun clearFrame() {
        info = null
        frameWidth = 0
        frameHeight = 0
        image.setImageDrawable(null)
        bitmap?.recycle()
        bitmap = null
        cancelTouch()
    }

    fun showGesture(gesture: VirtualScreenGesture?) {
        val display = info ?: return
        if (gesture == null || gesture.id == lastGesture || gesture.sessionId != display.sessionId) return
        lastGesture = gesture.id
        if (SystemClock.elapsedRealtime() - gesture.startedAt > gesture.durationMs + 700) return
        val viewport = viewport() ?: return
        val x = viewport.left + gesture.x * viewport.scale
        val y = viewport.top + gesture.y * viewport.scale
        if (gesture.action == "swipe") {
            indicators.showSwipe(
                x, y, viewport.left + gesture.endX * viewport.scale,
                viewport.top + gesture.endY * viewport.scale, gesture.durationMs
            )
        } else indicators.showPress(x, y, gesture.action == "long_press", gesture.durationMs)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!touchEnabled || info == null || (!direct && bitmap == null)) {
            cancelTouch(); return false
        }
        if (event.pointerCount != 1 || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            cancelTouch(); return true
        }
        val viewport = viewport() ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelTouch()
                if (viewport.toDisplay(event.x, event.y) == null) return false
                down = MotionEvent.obtain(event)
                downSession = info?.sessionId
                parent?.requestDisallowInterceptTouchEvent(true)
                // 接管页里用户自己的点击也给一个白点：不开这个的话，手指按在玻璃上
                // 完全没有视觉落点，尤其在画面延迟一帧以上时很难判断点没点中。
                indicators.showPress(event.x, event.y, longPress = false, durationMs = 260)
            }

            MotionEvent.ACTION_UP -> {
                val first = down ?: return true
                val session = downSession
                val start = viewport.toDisplay(first.x, first.y)
                val end = viewport.toDisplay(event.x, event.y, clamp = true)
                if (session != null && session == info?.sessionId && start != null && end != null) {
                    val duration = (event.eventTime - first.eventTime).toInt().coerceIn(100, 3000)
                    val moved = hypot(
                        event.x - first.x,
                        event.y - first.y
                    ) > ViewConfiguration.get(context).scaledTouchSlop
                    val args = if (moved) JSONObject().put("action", "swipe")
                        .put("x1", start.x).put("y1", start.y).put("x2", end.x).put("y2", end.y)
                        .put("durationMs", duration.coerceAtMost(2000))
                    else JSONObject().put(
                        "action",
                        if (duration >= ViewConfiguration.getLongPressTimeout()) "long_press" else "tap"
                    )
                        .put("x", start.x).put("y", start.y).put("durationMs", duration)
                    if (moved) {
                        indicators.showSwipe(first.x, first.y, event.x, event.y, duration)
                    }
                    onInput(session, args)
                    performClick()
                }
                cancelTouch()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick(); return true
    }

    // 与 applyViewport 同一套比例：小窗等比完整显示，查看页等比裁切。
    private fun viewport(): VirtualScreenViewport? = info?.let {
        val vw = width
        val vh = height
        if (vw <= 0 || vh <= 0) return null
        // 触摸坐标系必须用 display 尺寸（例如 1440x3168），不能用 JPEG 帧尺寸（800x1760）。
        // 两者宽高比相同、显示效果看着一样，但注入是按 display 坐标走的：
        // 用帧尺寸算出来的是 0..800 / 0..1760，实际注进 1440x3168 的屏，整体偏移 1.8 倍。
        val w = it.width
        val h = it.height
        if (w <= 0 || h <= 0) return null
        val scale = if (fitContent || direct) minOf(vw.toFloat() / w, vh.toFloat() / h)
        else maxOf(vw.toFloat() / w, vh.toFloat() / h)
        VirtualScreenViewport((vw - w * scale) / 2f, (vh - h * scale) / 2f, scale, w, h)
    }

    /** 控件坐标 -> 灵屏像素（触控直通用）。 */
    fun toDisplay(x: Float, y: Float, clamp: Boolean = false): VirtualScreenViewport.Point? =
        viewport()?.toDisplay(x, y, clamp)

    private fun cancelTouch() {
        down?.recycle()
        down = null
        downSession = null
    }

    override fun onDetachedFromWindow() {
        clearFrame()
        super.onDetachedFromWindow()
    }
}
