package io.github.mangi.eta.agent.display

import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.graphics.drawable.InsetDrawable
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import android.text.InputType
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.view.Surface
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ScaleGestureDetector
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.app.PendingIntent
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.display.dashboard.AgentControlMode
import io.github.mangi.eta.agent.display.dashboard.AgentControlStore
import io.github.mangi.eta.agent.display.dashboard.AgentDashboardView
import io.github.mangi.eta.agent.display.dashboard.AgentInjectionGate
import io.github.mangi.eta.agent.display.dashboard.AgentLiveActivityBridge
import io.github.mangi.eta.agent.display.dashboard.AgentLiveCoordinator
import io.github.mangi.eta.agent.display.dashboard.AgentVisionState
import io.github.mangi.eta.agent.display.dashboard.DashboardEffect
import io.github.mangi.eta.agent.display.dashboard.DashboardEvent
import io.github.mangi.eta.agent.display.dashboard.VisionTouch
import io.github.mangi.eta.agent.runtime.AgentRuntimeService
import io.github.mangi.eta.agent.runtime.RunControl
import io.github.mangi.eta.agent.runtime.RunProgressNotifier
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 灵屏悬浮小窗【最终版·无标题栏】
 * 纯监控：画面区单指拖拽移动、双指缩放、双击跳全屏；无顶部黑条；四角圆角；贴边折叠；
 * 透明度、尺寸/位置持久化、一键重置、帧异常捕获。
 */
internal object VirtualScreenFloatWindow {
    private var context: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val wm: WindowManager?
        get() = context?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager

    private var rootView: FrameLayout? = null
    private var borderDrawable: GradientDrawable? = null
    private var surfaceView: VirtualScreenSurfaceView? = null
    private var triggerBar: View? = null
    private var dotsButton: View? = null

    // 仪表盘：控制权状态机 + Compose 叠层。浮窗只做宿主，不自己判状态。
    private var controlStore: AgentControlStore? = null
    private var dashboardView: AgentDashboardView? = null
    private var dashboardJob: Job? = null
    /** 接管全屏期间压住浮窗：否则 overlay 会盖在查看页上面。 */
    private var suppressed = false
    /** 接管态：浮窗自己铺满整屏，触摸直通；退出时恢复原来的档位。 */
    private var takeoverFull = false
    private var savedW = 0
    private var savedH = 0
    private var savedX = 0
    private var savedY = 0
    private val dashboardExpanded = kotlinx.coroutines.flow.MutableStateFlow(false)
    /** 凸在窗口上沿外侧的“半圆小帽子”，独立浮层，不被小窗边界裁剪。 */
    private var dotsHat: View? = null
    private var dotsHatParams: WindowManager.LayoutParams? = null
    private val dotsHatSync = object : Runnable {
        override fun run() {
            syncDotsHat()
            rootView?.postDelayed(this, 200L)
        }
    }
    private val longPress = Runnable { enterTakeoverFullScreen() }

    // 触控直通：单指在画面里就是真手指，两指仍旧缩放小窗，移动小窗改成拖白边框。
    private var touchStreamActive = false
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private val touchMoveInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    private var touchPump: kotlinx.coroutines.channels.Channel<JSONObject>? = null
    private var lp: WindowManager.LayoutParams? = null

    private var info: VirtualDisplayInfo? = null
    private var lastDisplayRotation = -1
    private var lastShapeProbeAt = 0L
    private var sensorManager: SensorManager? = null
    private var tiltHoldSince = 0L
    private var tiltLandscape = false
    private var lastFrameId = 0L
    private var frameJob: Job? = null
    private var observeJob: Job? = null
    private var cachedSettings: Settings? = null

    private var collapsed = false
    private var collapsedSide = SIDE_LEFT
    /** 贴边后 2 秒无操作再收成一条圆弧。 */
    private var sinkEnabled = true
    private var sinked = false
    /** 上一次看到的运行 id：用于判断任务是否刚结束。 */
    private var lastRunId: String? = null

    /**
     * 因「任务结束」被自动收起的 session。
     *
     * 收起时只是隐藏了浮窗，屏本身仍然存在（成功后按设计要保留一段时间）。如果没有这个记录，
     * 之后任何一次状态变化——典型就是切换会话把焦点换到另一块屏——都会走进 show() 分支，
     * 把刚收起的灵屏又弹回来。
     */
    private var dismissedSessionId: String? = null
    private var swallowingBubble = false
    private val sinkRunnable = Runnable { sinkToEdge() }
    /** 拖拽途中已经折叠过：松手不再重复吸附。 */
    private var collapsedDuringDrag = false
    /** 实机键盘桥：灵屏要键盘但系统没弹时，用主屏隐形编辑区申请真机输入法。 */
    private var imeBridgeOn = false
    private var imeSinkView: View? = null
    /** 系统 inset 真实报告的“键盘在屏”状态：收起过一次之后 showSoftInput 的返回值不可信。 */
    private var imeVisible = false
    private var imeReassertAt = 0L
    /** 内容区短边（dp）。横竖屏切换时保持不变，只换比例，保证两种朝向的小窗“大小手感”一致。 */
    private var innerShortDp = 0f
    private var edgeCollapseEnabled = true
    private var windowAlpha = 0.85f
    private var preCollapseX = 0
    private var preCollapseY = 0

    /** 本次手势是否发生在“最小档”：是则单指只搬窗、点一下放大，不直通灵屏。 */
    private var miniGesture = false
    private var miniMoved = false
    private var miniDownRawX = 0f
    private var miniDownRawY = 0f
    private var miniDownWinX = 0
    private var miniDownWinY = 0

    private var savedFloatWinX = 16
    private var savedFloatWinY = 120

    private lateinit var scaleDetector: ScaleGestureDetector
    private var curWidthDp = 0
    private var curHeightDp = 0
    private val minWidthDp = 72

    /**
     * 悬浮窗固定档位（内容区短边 dp）。双击在四档间循环，
     * 不再靠捏合微调，免得每次都得重新掰到「勉强能看清」的大小。
     */
    private val sizeTiersDp = listOf(96, 144, 200, 272)
    private var tierIndex = 0

    /** 常规大小档：内容短边 160dp，够手指点按，又不占满屏。 */
    private val NORMAL_INNER_DP = 160f

    /** 点一下小窗时切换到的另一档：最小档 + 1/3。 */
    private val TAP_GROW_SCALE = 4f / 3f
    private val maxWidthDp = 420

    /** surface 四周留出的边框宽度（dp，单边），窗口尺寸换算时必须扣掉，否则内容区比例 ≠ 内容比例会出现信箱黑边。 */
    private val BORDER_DP = 0f

    /** 长边最多占屏幕对应边长的比例；留出余量，保证横屏后单指拖拽仍有可移动空间。 */
    private val MAX_LONG_SCREEN_RATIO = 0.85f

    /** 真实帧比例；>0 时优先用它锁定内容区比例，保证投影铺满、无黑边。 */
    private var frameAspect = 0f

    private var downRawX = 0f
    private var downRawY = 0f
    private var downWinX = 0
    private var downWinY = 0
    private var dragMoved = false
    private var dotsDragMode = false
    private val dotsLongPress = Runnable { dotsDragMode = true }
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private val doubleTapMaxIntervalMs = 300L
    private val doubleTapMaxDeltaDp = 20f

    private val floatWinOutline = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, cornerRadiusPx(view.width, view.height))
        }
    }

    private val configCallback = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            if (rootView == null) return
            val d = info ?: return
            fitContentShape(d)
        }
        override fun onLowMemory() {}
    }

    private val scaleListener = object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (collapsed) return false
            val factor = detector.scaleFactor
            curWidthDp = (curWidthDp * factor).roundToInt().coerceAtLeast(1)
            curHeightDp = (curHeightDp * factor).roundToInt().coerceAtLeast(1)
            normalizeSize()
            applySize()
            return true
        }
        override fun onScaleEnd(detector: ScaleGestureDetector) {
            if (collapsed) return
            scope.launch {
                runCatching {
                    SettingsDataStore.updateSettings {
                        it.copy(virtualScreenFloatWindowWidthDp = curWidthDp,
                            virtualScreenFloatWindowHeightDp = curHeightDp)
                    }
                }
            }
        }
    }

    fun init(appContext: Context) {
        if (context != null) return
        context = appContext.applicationContext
        context?.registerComponentCallbacks(configCallback)
        if (controlStore == null) {
            val store = AgentControlStore()
            controlStore = store
            AgentLiveCoordinator.attach(store)
            scope.launch { store.effects.collect { handleEffect(it) } }
        }
        observeJob = scope.launch {
            combine(SettingsDataStore.settingsFlow(), VirtualScreenSession.state) { s, v -> s to v }
                .collect { (settings, vsState) ->
                    cachedSettings = settings
                    edgeCollapseEnabled = settings.virtualScreenFloatEdgeCollapseEnabled
                    windowAlpha = settings.virtualScreenFloatWindowAlpha.coerceIn(0.1f, 1.0f)
                    val next = vsState.display
                    // 任务刚结束（有 run → 无 run）就主动收起浮窗，别把最后一帧留在屏幕上。
                    val runId = vsState.activeRunId
                    if (runId == null && lastRunId != null && !takeoverFull) {
                        VscrDiag.line("任务结束，收起浮窗")
                        lastRunId = null
                        dismissedSessionId = next?.sessionId
                        hide()
                        return@collect
                    }
                    lastRunId = runId
                    // 有新任务在跑，说明这次是「要用屏」，之前的收起标记作废。
                    if (runId != null) dismissedSessionId = null
                    when {
                        suppressed -> Unit
                        !settings.virtualScreenFloatWindowEnabled || next == null -> {
                            dismissedSessionId = null
                            hide()
                        }
                        // 这块屏是被「任务结束」收起的，且当前没有新任务：保持隐藏，
                        // 等它真的被关掉、或有新任务要用屏时再考虑显示。
                        next.sessionId == dismissedSessionId -> {
                            if (rootView != null) {
                                VscrDiag.line("已收起的灵屏不再自动弹出 " + next.sessionId.take(8))
                                hide()
                            }
                        }
                        next.sessionId != info?.sessionId -> show(next, settings)
                        else -> {
                            val shapeChanged = info?.let { it.width != next.width || it.height != next.height } == true
                            info = next
                            // 接管态要 100% 不透明；这里如果无脑写回 windowAlpha，
                            // 会话状态每变一次就把透明度打回 0.85（实测就是这个原因）。
                            rootView?.alpha = if (takeoverFull) 1f else windowAlpha
                            // 灵屏横竖变了，画面控件必须同步拿到新尺寸：TextureView 的默认缓冲尺寸按它设置，
                            // 留着旧的竖屏尺寸就会把横屏合成结果塞进竖缓冲里，看着只有左边一条。
                            surfaceView?.setDisplayInfo(next)
                            surfaceView?.post { surfaceView?.setDisplayInfo(next) }
                            if (shapeChanged) fitContentShape(next)
                        }
                    }
                    syncControl(vsState)
                }
        }
    }

    fun setWindowAlpha(alpha: Float) {
        windowAlpha = alpha.coerceIn(0.1f, 1.0f)
        rootView?.alpha = windowAlpha
    }

    fun resetWindowConfig() {
        scope.launch {
            runCatching {
                SettingsDataStore.updateSettings {
                    it.copy(
                        virtualScreenFloatWindowWidthDp = 0,
                        virtualScreenFloatWindowHeightDp = 0,
                        virtualScreenFloatWindowAlpha = 0.85f,
                        virtualScreenFloatWindowPosX = 16,
                        virtualScreenFloatWindowPosY = 120,
                    )
                }
            }
            val ctx = context ?: return@launch
            val d = ctx.resources.displayMetrics.density
            val short = min(ctx.resources.displayMetrics.widthPixels, ctx.resources.displayMetrics.heightPixels) / d
            val long = max(ctx.resources.displayMetrics.widthPixels, ctx.resources.displayMetrics.heightPixels) / d
            curWidthDp = minWidthDp
            curHeightDp = (curWidthDp * (long / short)).roundToInt()
            windowAlpha = 0.85f
            savedFloatWinX = 16; savedFloatWinY = 120
            rootView?.alpha = windowAlpha
            lp?.x = 16; lp?.y = 120
            innerShortDp = 0f
            alignToContent()
            applySize()
        }
    }

    private fun show(next: VirtualDisplayInfo, settings: Settings) {
        if (!canOverlay()) { requestOverlayPermission(); return }
        hide()
        info = next
        lastFrameId = 0
        val ctx = context ?: return
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Float) = (v * d).roundToInt()

        scaleDetector = ScaleGestureDetector(ctx, scaleListener)

        val short = min(ctx.resources.displayMetrics.widthPixels, ctx.resources.displayMetrics.heightPixels) / d
        val long = max(ctx.resources.displayMetrics.widthPixels, ctx.resources.displayMetrics.heightPixels) / d
        // 每次出现都从最小档开始：贴边展开、直接弹出都一致；之后的大小由双指决定。
        val defaultW = minWidthDp
        curWidthDp = defaultW; curHeightDp = (defaultW * (long / short)).roundToInt()
        innerShortDp = 0f
        alignToContent()
        normalizeSize()
        savedFloatWinX = settings.virtualScreenFloatWindowPosX
        savedFloatWinY = settings.virtualScreenFloatWindowPosY

        val border = (BORDER_DP * d).roundToInt()

        val root = FrameLayout(ctx)
        // 白透边自身也带圆角：即使个别 ROM 的 clipToOutline 生效延迟，角上也不会是直角。
        borderDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerRadiusPx(dp(curWidthDp.toFloat()), dp(curHeightDp.toFloat()))
            // 无边框：只留形状给圆角裁剪用，颜色全透明。
            setColor(0x00000000)
        }
        root.background = borderDrawable
        root.alpha = windowAlpha
        root.outlineProvider = floatWinOutline
        root.clipToOutline = true

        val surface = VirtualScreenSurfaceView(ctx)
        // 沿用旧小窗参数：窗口内容区比例已严格等于灵屏比例，画面等比完整显示即可，不裁切。
        surface.fitContent = true
        surface.touchEnabled = false
        surface.onFrameSize = { w, h -> onFrameSize(w, h) }
        surface.setOnTouchListener { _, ev -> onSurfaceTouch(ev) }
        val appContext = ctx.applicationContext
        surface.onHostSurface = { host ->
            VirtualScreenHostSurfaces.attach(
                VirtualScreenSession.currentOwner().orEmpty(), "float", host,
            )
            val live = VirtualScreenHostSurfaces.live && host != null
            surface.post { surface.setDirectMode(live) }
            if (VirtualScreenHostSurfaces.live) {
                Thread {
                    runCatching { VirtualScreenSession.notifyHostSurface(appContext) }
                }.apply { isDaemon = true }.start()
            }
        }
        root.addView(surface, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ).apply { leftMargin = border; rightMargin = border; topMargin = border; bottomMargin = border })

        val trigger = View(ctx).apply {
            visibility = View.GONE
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCC3D3D3D.toInt())
                setStroke(dp(1f), 0x55FFFFFF.toInt())
            }
            // 收起时换成应用图标：必须裁成圆形，方形图标贴在屏幕上很突兀。
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            clipToOutline = true
        }
        // 气泡态：整块都是“点开 / 上下拖”；展开态仍是原来的拖动把手。
        trigger.setOnTouchListener { _, ev -> if (collapsed) onBubbleTouch(ev) else onTriggerTouch(ev); true }
        root.addView(trigger, FrameLayout.LayoutParams(dp(35f), dp(35f), Gravity.START or Gravity.CENTER_VERTICAL))

        // 旧的“⋮”帽子（全屏/常规大小/键盘/关闭）已彻底移除：
        // 入口改为顶部状态栏 + 长按白边框，避免再留下点不动的装饰。

        // 实机键盘桥：隐形编辑区只负责向系统申请真机输入法，提交的文字再回灌灵屏。
        val imeSink = ImeSinkView(ctx, { text -> forwardText(text) }, { code -> forwardKey(code) }).apply {
            alpha = 0f
            isFocusable = true
            isFocusableInTouchMode = true
        }
        root.addView(imeSink, FrameLayout.LayoutParams(dp(1f), dp(1f), Gravity.TOP or Gravity.START))
        imeSinkView = imeSink
        runCatching { attachDashboard(root, next) }
            .onFailure { VscrDiag.line("仪表盘挂载失败 " + it) }
        // 只认系统 inset：IME 真的在屏上才算“键盘出来了”。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            root.setOnApplyWindowInsetsListener { _, insets ->
                val v = insets.isVisible(WindowInsets.Type.ime())
                if (v != imeVisible) {
                    imeVisible = v
                    VscrDiag.line("键盘桥 可见=" + v)
                }
                insets
            }
        }

        val p = WindowManager.LayoutParams(
            dp(curWidthDp.toFloat()), dp(curHeightDp.toFloat()),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = savedFloatWinX; y = savedFloatWinY }

        runCatching { wm?.addView(root, p) }.onFailure { info = null; return }
        surface.setDisplayInfo(next)
        root.post { surface.setDisplayInfo(next) }
        rootView = root; surfaceView = surface; triggerBar = trigger; lp = p
        root.postDelayed(dotsHatSync, 200L)
        // 画面里的手指交给灵屏里的应用；白边框才用来移动小窗。
        root.setOnTouchListener { _, ev -> onBorderTouch(ev) }
        startTouchPump()
        startFrameLoop()
        startTiltWatcher()
        VscrDiag.line(
            "小窗显示 外框=" + p.width + "x" + p.height + "px 内容区=" + (p.width - border * 2) + "x" +
                (p.height - border * 2) + "px 外框比例=" + String.format("%.4f", p.width.toFloat() / p.height) +
                " 灵屏=" + next.width + "x" + next.height + " 投影比例=" + String.format("%.4f", contentAspect())
        )
    }

    private fun hide() {
        frameJob?.cancel(); frameJob = null
        surfaceView?.clearFrame()
        rootView?.let { runCatching { wm?.removeView(it) } }
        dotsHat?.let { v -> runCatching { wm?.removeView(v) } }
        dotsHat = null; dotsHatParams = null
        dashboardJob?.cancel()
        dashboardJob = null
        dashboardView = null
        dashboardExpanded.value = false
        rootView = null; surfaceView = null; triggerBar = null; lp = null; dotsButton = null
        info = null; lastFrameId = 0; collapsed = false; frameAspect = 0f
        stopTouchPump()
        stopTiltWatcher()
    }

    private fun startFrameLoop() {
        frameJob = scope.launch {
            // 直接按设定帧率出帧：以前除以 2，120fps 档实际只有 30fps，跟手感被砍掉一半。
            val frameDelay = io.github.mangi.eta.data.model.VirtualScreenFrameRate.intervalMs(
                io.github.mangi.eta.data.datastore.SettingsDataStore.settings().virtualScreenFrameRate).toLong()
                .coerceIn(4L, 66L)
            val idleDelay = frameDelay.coerceAtLeast(40L)
            while (isActive) {
                syncDisplayRotation()
                val startedAt = android.os.SystemClock.elapsedRealtime()
                var fresh = false
                val current = info ?: break
                // 直通生效后画面由合成器直出，不再请求整帧（省电、省内存）。
                val view = surfaceView
                if (view != null) {
                    val wantDirect = VirtualScreenHostSurfaces.live &&
                        VirtualScreenHostSurfaces.currentHost() != null
                    if (wantDirect != view.direct) view.post { view.setDirectMode(wantDirect) }
                }
                // 直通模式不拉整帧，但每秒探一次尺寸：灵屏切成横屏后小窗才会跟着横过来。
                if (view?.direct == true && !collapsed) {
                    val nowMs = android.os.SystemClock.elapsedRealtime()
                    if (nowMs - lastShapeProbeAt > 400L) {
                        lastShapeProbeAt = nowMs
                        val ctx = context
                        if (ctx != null) {
                            runCatching { VirtualScreenSession.refreshForViewer(ctx) }
                            VirtualScreenSession.state.value.display?.let { d ->
                                if (d.width != view.infoWidth || d.height != view.infoHeight) {
                                    view.post { view.setDisplayInfo(d) }
                                }
                            }
                        }
                    }
                }
                if (!collapsed && !VirtualScreenFrameGate.paused && view?.direct != true) {
                    val frame = readFrame(current)
                    if (frame != null) {
                        if (info?.sessionId == current.sessionId) {
                            surfaceView?.setFrame(frame.bitmap, current)
                            lastFrameId = frame.id
                            fresh = true
                        } else frame.bitmap.recycle()
                    }
                }
                // 延迟只补差值；查看页在前台时小窗半速出帧，避免两份整帧解码互抢 CPU。
                val spent = android.os.SystemClock.elapsedRealtime() - startedAt
                val target = if (VirtualScreenFrameGate.viewerForeground) frameDelay * 2 else frameDelay
                val base = if (fresh || VirtualScreenFrameGate.sinceInputMs() < 1200L) target else idleDelay
                val budget = base - spent
                if (budget > 0L) delay(budget)
            }
        }
    }

    private suspend fun readFrame(current: VirtualDisplayInfo): Frame? = withContext(Dispatchers.IO) {
        return@withContext try {
            val result = VirtualScreenSession.observeForViewer(context ?: return@withContext null, lastFrameId)
            val json = JSONObject(result.content)
            if (!json.optBoolean("ok")) return@withContext null
            val encoded = result.images.firstOrNull()?.reference?.substringAfter("base64,")
                ?: return@withContext null
            val bytes = Base64.decode(encoded, Base64.DEFAULT)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
            }) ?: return@withContext null
            Frame(bitmap, json.optLong("frameId"))
        } catch (_: Exception) { null }
    }

    private data class Frame(val bitmap: Bitmap, val id: Long)

    private fun startTouchPump() {
        val ch = kotlinx.coroutines.channels.Channel<JSONObject>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        touchPump = ch
        scope.launch(Dispatchers.IO) {
            for (msg in ch) {
                val ctx = context ?: continue
                val session = info?.sessionId ?: continue
                val outcome = runCatching { VirtualScreenSession.inputForViewer(ctx, session, msg) }
                if (msg.optString("phase") == "move") touchMoveInFlight.set(false)
                if (msg.optString("phase") == "down") {
                    val body = outcome.getOrNull()?.content ?: ""
                    VscrDiag.line("直通按下结果 " + body.take(120))
                }
            }
        }
    }

    private fun stopTouchPump() {
        touchPump?.close()
        touchPump = null
        touchStreamActive = false
        touchMoveInFlight.set(false)
    }

    /** 直通事件入队；拖动只保留最新一跳，避免排队越拖越慢。 */
    private fun sendTouch(phase: String, x: Float, y: Float) {
        if (phase == "move" && !touchMoveInFlight.compareAndSet(false, true)) return
        val q = touchPump
        if (q == null) {
            if (phase == "move") touchMoveInFlight.set(false)
            return
        }
        val sent = q.trySend(
            JSONObject().put("action", "touch").put("phase", phase)
                .put("x", x.roundToInt()).put("y", y.roundToInt())
        )
        if (sent.isFailure && phase == "move") touchMoveInFlight.set(false)
    }

    /**
     * 触控直通：画面里的单指就是真手指，点按、长按、拖动都由灵屏里的应用处理。
     * 两指仍旧缩放小窗；移动小窗改成拖白边框。
     */
    /**
     * 画面区触摸。
     *
     * 小窗只做观察：单指拖动搬窗、点一下展开工具栏，绝不把手指灌进灵屏里的 App
     * （误触会真的点掉别人的按钮）。要亲手操作，点「接管控制」进全屏。
     * 双指仍然缩放窗口尺寸。
     */
    /**
     * 画面区触摸。
     *
     * - 普通态：只观察。单指拖动搬窗、双击切换档位、长按进全屏接管；绝不注入。
     * - 接管态：全屏铺开，手指直通灵屏（只有这一种情况会注入）。
     * 双指缩放已移除：大小改用固定档位循环。
     */
    private fun onSurfaceTouch(ev: MotionEvent): Boolean {
        if (collapsed) return false
        if (takeoverFull) return onTakeoverTouch(ev)
        if (ev.pointerCount >= 2) return true
        return onObserveTouch(ev)
    }

    /** 接管态直通：只有这里会把手指写进灵屏。 */
    private fun onTakeoverTouch(ev: MotionEvent): Boolean {
        val view = surfaceView ?: return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val pt = view.toDisplay(ev.x, ev.y) ?: return false
                lastTouchX = pt.x.toFloat()
                lastTouchY = pt.y.toFloat()
                touchStreamActive = true
                sendTouch("down", lastTouchX, lastTouchY)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!touchStreamActive) return true
                val pt = view.toDisplay(ev.x, ev.y, clamp = true) ?: return true
                lastTouchX = pt.x.toFloat()
                lastTouchY = pt.y.toFloat()
                sendTouch("move", lastTouchX, lastTouchY)
            }
            MotionEvent.ACTION_UP -> {
                if (!touchStreamActive) return true
                view.toDisplay(ev.x, ev.y, clamp = true)?.let {
                    lastTouchX = it.x.toFloat()
                    lastTouchY = it.y.toFloat()
                }
                touchStreamActive = false
                sendTouch("up", lastTouchX, lastTouchY)
            }
            MotionEvent.ACTION_CANCEL -> cancelTouchStream()
        }
        return true
    }

    /** 观察态手势：拖动 = 搬窗，轻点 = 最小档放大，否则展开/收起工具栏。 */
    private fun onObserveTouch(ev: MotionEvent): Boolean {
        val p = lp ?: return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                miniDownRawX = ev.rawX; miniDownRawY = ev.rawY
                miniDownWinX = p.x; miniDownWinY = p.y
                miniMoved = false
                rootView?.postDelayed(longPress, 550)
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = (ev.rawX - miniDownRawX).roundToInt()
                val dy = (ev.rawY - miniDownRawY).roundToInt()
                if (abs(dx) > 4 || abs(dy) > 4) miniMoved = true
                if (miniMoved) {
                    cancelLongPress()
                    p.x = (miniDownWinX + dx).coerceIn(0, (screenW() - p.width).coerceAtLeast(0))
                    p.y = (miniDownWinY + dy).coerceIn(0, (screenH() - p.height).coerceAtLeast(0))
                    runCatching { wm?.updateViewLayout(rootView, p) }
                    if (collapseIfAtEdge()) collapsedDuringDrag = true
                }
            }

            MotionEvent.ACTION_UP -> {
                cancelLongPress()
                val snapped = collapsedDuringDrag
                collapsedDuringDrag = false
                if (!miniMoved) {
                    if (detectDoubleTap(ev)) cycleSizeTier()
                } else if (!snapped) {
                    savedFloatWinX = p.x; savedFloatWinY = p.y
                    scope.launch {
                        runCatching {
                            SettingsDataStore.updateSettings {
                                it.copy(virtualScreenFloatWindowPosX = p.x, virtualScreenFloatWindowPosY = p.y)
                            }
                        }
                    }
                }
            }
        }
        return true
    }

    private fun isMinimalSize(): Boolean {
        val aspect = contentAspect()
        val inner = (if (aspect >= 1f) curHeightDp else curWidthDp) - BORDER_DP * 2f
        // 留 6dp 容差：捏合回最小档时取整误差不会把窗口误判成“常规大小”而放行触控。
        return inner <= minWidthDp + 6f
    }

    /** 最小档手势：单指拖动整窗，原地松手就放大；贴到边缘才吸附/折叠。 */
    private fun onMiniTouch(ev: MotionEvent): Boolean {
        val p = lp ?: return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                miniDownRawX = ev.rawX; miniDownRawY = ev.rawY
                miniDownWinX = p.x; miniDownWinY = p.y
                miniMoved = false
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = (ev.rawX - miniDownRawX).roundToInt()
                val dy = (ev.rawY - miniDownRawY).roundToInt()
                if (abs(dx) > 4 || abs(dy) > 4) miniMoved = true
                if (miniMoved) {
                    p.x = (miniDownWinX + dx).coerceIn(0, (screenW() - p.width).coerceAtLeast(0))
                    p.y = (miniDownWinY + dy).coerceIn(0, (screenH() - p.height).coerceAtLeast(0))
                    runCatching { wm?.updateViewLayout(rootView, p) }
                    // 小窗拖到边也立刻成气泡。
                    if (collapseIfAtEdge()) collapsedDuringDrag = true
                }
            }

            MotionEvent.ACTION_UP -> {
                val snapped = collapsedDuringDrag
                collapsedDuringDrag = false
                if (!miniMoved) {
                    cycleSizeTier()
                } else {
                    if (!snapped) { if (edgeCollapseEnabled) maybeCollapse() else snapToEdge() }
                    savedFloatWinX = p.x; savedFloatWinY = p.y
                    scope.launch {
                        runCatching {
                            SettingsDataStore.updateSettings {
                                it.copy(virtualScreenFloatWindowPosX = p.x, virtualScreenFloatWindowPosY = p.y)
                            }
                        }
                    }
                }
            }
        }
        return true
    }

    private fun onBorderTouch(ev: MotionEvent): Boolean {
        if (collapsed) return onBubbleTouch(ev)
        val p = lp ?: return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = ev.rawX; downRawY = ev.rawY
                downWinX = p.x; downWinY = p.y
                dragMoved = false
                // 操作条在就表示用户正要选动作：这一下只用来收起它，不重新计时。
                rootView?.postDelayed(longPress, 550)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (ev.rawX - downRawX).roundToInt()
                val dy = (ev.rawY - downRawY).roundToInt()
                if (abs(dx) > 4 || abs(dy) > 4) dragMoved = true
                if (dragMoved) cancelLongPress()
                if (collapsedDuringDrag) return true
                if (dragMoved) {
                    // 拖拽全程限制在屏幕内：一碰到左右边缘就立刻收成气泡。
                    p.x = (downWinX + dx).coerceIn(0, (screenW() - p.width).coerceAtLeast(0))
                    p.y = (downWinY + dy).coerceIn(0, (screenH() - p.height).coerceAtLeast(0))
                    runCatching { wm?.updateViewLayout(rootView, p) }
                    if (collapseIfAtEdge()) { collapsedDuringDrag = true; cancelLongPress() }
                }
            }
            MotionEvent.ACTION_UP -> {
                cancelLongPress()
                val snapped = collapsedDuringDrag
                collapsedDuringDrag = false
                if (!dragMoved) {
                    if (detectDoubleTap(ev)) cycleSizeTier()
                } else {
                    if (!snapped) { if (edgeCollapseEnabled) maybeCollapse() else snapToEdge() }
                    savedFloatWinX = p.x; savedFloatWinY = p.y
                    scope.launch {
                        runCatching {
                            SettingsDataStore.updateSettings {
                                it.copy(virtualScreenFloatWindowPosX = p.x, virtualScreenFloatWindowPosY = p.y)
                            }
                        }
                    }
                }
            }
        }
        return true
    }

    private fun detectDoubleTap(ev: MotionEvent): Boolean {
        val now = SystemClock.uptimeMillis()
        val d = context?.resources?.displayMetrics?.density ?: 1f
        val dx = ev.x - lastTapX
        val dy = ev.y - lastTapY
        val distDp = sqrt(dx * dx + dy * dy) / d
        val isDouble = (now - lastTapTime) < doubleTapMaxIntervalMs && distDp < doubleTapMaxDeltaDp
        lastTapTime = now; lastTapX = ev.x; lastTapY = ev.y
        if (isDouble) lastTapTime = 0L
        return isDouble
    }

    private fun applySize() {
        val p = lp ?: return
        val d = context?.resources?.displayMetrics?.density ?: 1f
        p.width = (curWidthDp * d).roundToInt()
        p.height = (curHeightDp * d).roundToInt()
        borderDrawable?.let { it.cornerRadius = cornerRadiusPx(p.width, p.height) }
        rootView?.invalidateOutline()
        runCatching { wm?.updateViewLayout(rootView, p) }
    }

    /**
     * 让窗口形状跟随灵屏内容方向，并让"内容区"比例严格等于内容比例（消除信箱黑边）。
     * 拿到内容尺寸时总是按内容比例重算并返回 true；没有内容信息时返回 false，由调用方退回按手机横竖屏对调。
     */
    private fun alignToContent(): Boolean {
        val display = info ?: return false
        if (display.width <= 0 || display.height <= 0) return false
        val aspect = display.width.toFloat() / display.height
        if (!aspect.isFinite() || aspect <= 0f) return false
        // 保留“内容区短边”，只换比例：竖屏多大，转横屏还是多大，包边厚度与圆角比例在两种朝向一致，
        // 也不会一转到横屏就把小窗撑满整块屏幕。
        val innerShort = if (innerShortDp > 0f) {
            innerShortDp
        } else {
            (if (aspect >= 1f) curHeightDp else curWidthDp) - BORDER_DP * 2f
        }
        setFromInnerShort(innerShort, aspect)
        return true
    }

    /**
     * 按内容比例约束尺寸：内容区短边限制在 [minWidthDp, maxWidthDp]，长边不超过屏幕对应边长。
     * 竖屏内容等价于原来的"宽度 45–420dp"；横屏内容允许窗口铺到屏幕宽度，保证拖拽与双指缩放仍可用。
     * 有内容信息时走 setFromInnerShort（扣边框、严格等比）；没有内容信息时才退回按当前外框等比缩放。
     */
    // 小窗自己判方向：看手机倾斜，只转小窗，不动系统屏幕方向。
    private val tiltListener = object : SensorEventListener {
        override fun onSensorChanged(ev: SensorEvent) {
            if (collapsed || rootView == null) return
            val ax = abs(ev.values[0]); val ay = abs(ev.values[1])
            if (ax < 2f && ay < 2f) { tiltHoldSince = 0L; return }   // 平放：保持现状
            val want = ax > ay * 1.25f
            if (want == tiltLandscape) { tiltHoldSince = 0L; return }
            val now = SystemClock.elapsedRealtime()
            if (tiltHoldSince == 0L) { tiltHoldSince = now; return }
            if (now - tiltHoldSince < 600L) return
            tiltHoldSince = 0L
            tiltLandscape = want
            applyPhoneOrientation(want, "倾斜")
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun startTiltWatcher() {
        // v30: float window orientation follows virtual display content, not phone tilt
    }

    private fun stopTiltWatcher() {
        sensorManager?.let { runCatching { it.unregisterListener(tiltListener) } }
        sensorManager = null
        tiltHoldSince = 0L
    }

    /** 默认屏当前旋转；拿不到时返回 -1。 */
    private fun displayRotation(): Int = runCatching {
        val dm = context?.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return@runCatching -1
        dm.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: -1
    }.getOrDefault(-1)

    /**
     * 浮窗进程常被判为 cached，系统转屏不会给它派发 onConfigurationChanged，
     * 所以自己盯默认屏旋转：一转就让小窗跟着横竖切换。
     */
    private fun syncDisplayRotation() {
        val rot = displayRotation()
        if (rot < 0 || rot == lastDisplayRotation) return
        val first = lastDisplayRotation < 0
        lastDisplayRotation = rot
        if (first) return
        val isLandscape = rot == Surface.ROTATION_90 || rot == Surface.ROTATION_270
        VscrDiag.line("转屏检测 手机=" + (if (isLandscape) "横" else "竖") + " rotation=" + rot + " 不跟手机")
    }

    /** 按手机方向切换小窗横竖：短边不变，只换比例，位置再夹回屏幕内。 */
    private fun applyPhoneOrientation(isLandscape: Boolean, from: String = "系统") {
        if (rootView == null) return
        if (collapsed) { reCollapse(); return }
        if (isLandscape != (curWidthDp >= curHeightDp)) {
            val tmp = curWidthDp; curWidthDp = curHeightDp; curHeightDp = tmp
            innerShortDp = 0f
            normalizeSize(ignoreContent = true)
        } else {
            normalizeSize()
        }
        applySize()
        clampToScreen()
        VscrDiag.line("转屏 来源=" + from + " 方向=" + (if (isLandscape) "横" else "竖") +
            " 小窗=" + curWidthDp + "x" + curHeightDp + "dp")
    }

    private fun normalizeSize(ignoreContent: Boolean = false) {
        if (curWidthDp <= 0 || curHeightDp <= 0) return
        val display = info
        if (!ignoreContent && display != null && display.width > 0 && display.height > 0) {
            val aspect = display.width.toFloat() / display.height
            if (aspect.isFinite() && aspect > 0f) {
                val innerShort = (if (aspect >= 1f) curHeightDp else curWidthDp) - BORDER_DP * 2f
                setFromInnerShort(innerShort, aspect)
                return
            }
        }
        val d = context?.resources?.displayMetrics?.density ?: 1f
        val landscape = curWidthDp > curHeightDp
        val short = if (landscape) curHeightDp else curWidthDp
        val long = if (landscape) curWidthDp else curHeightDp
        val boundedShort = short.coerceIn(minWidthDp, maxWidthDp)
        val limit = (if (landscape) (screenW() / d).roundToInt() else (screenH() / d).roundToInt())
            .coerceAtLeast(boundedShort)
        val boundedLong = (long.toFloat() * boundedShort / short).roundToInt().coerceIn(boundedShort, limit)
        if (landscape) {
            curWidthDp = boundedLong; curHeightDp = boundedShort
        } else {
            curWidthDp = boundedShort; curHeightDp = boundedLong
        }
    }

    /**
     * 由"内容区短边 + 内容比例"换算出窗口外框尺寸（dp）。
     * 外框 = 内容区在两侧各加 BORDER_DP，与 show() 里 surface 的边距一致，
     * 这样 ImageView(FIT_CENTER) 的可用区域与内容等比，横竖屏都不会出现信箱黑边。
     */
    private fun setFromInnerShort(innerShortIn: Float, aspect: Float) {
        val landscape = aspect >= 1f
        val d = context?.resources?.displayMetrics?.density ?: 1f
        val b = (BORDER_DP * 2f).roundToInt()
        var innerShort = innerShortIn.coerceIn(minWidthDp.toFloat(), maxWidthDp.toFloat())
        var innerLong = innerShort * (if (landscape) aspect else 1f / aspect)
        val limitDp = (if (landscape) screenW() else screenH()) / d * MAX_LONG_SCREEN_RATIO
        if (innerLong + b > limitDp) {
            // 两个方向一起缩，绝不单独压一条边，否则内容区比例会漂离投影比例、又出现黑边。
            innerLong = (limitDp - b).coerceAtLeast(1f)
            innerShort = innerLong / (if (landscape) aspect else 1f / aspect)
            if (innerShort > maxWidthDp.toFloat()) innerShort = maxWidthDp.toFloat()
            if (innerShort > innerLong) innerShort = innerLong
        }
        val wDp = (if (landscape) innerLong else innerShort).roundToInt() + b
        val hDp = (if (landscape) innerShort else innerLong).roundToInt() + b
        // 下限只防塌成 0，不再单边抬高，否则同样会把比例拉歪。
        val floor = b + 8
        curWidthDp = wDp.coerceAtLeast(floor)
        curHeightDp = hDp.coerceAtLeast(floor)
        innerShortDp = innerShort
    }

    /** 内容横竖切换后重算窗口形状与位置；拖拽与双指缩放沿用同一套手势逻辑，不因形状变化失效。 */
    private fun fitContentShape(display: VirtualDisplayInfo) {
        if (collapsed || display.width <= 0 || display.height <= 0) return
        val wantLandscape = display.width > display.height
        if (wantLandscape != (curWidthDp >= curHeightDp)) {
            val tmp = curWidthDp; curWidthDp = curHeightDp; curHeightDp = tmp
            innerShortDp = 0f
        }
        alignToContent()
        normalizeSize()
        applySize()
        clampToScreen()
        VscrDiag.line("跟内容 源=" + display.width + "x" + display.height +
            " 小窗=" + curWidthDp + "x" + curHeightDp + "dp")
    }

    private fun onTriggerTouch(ev: MotionEvent) {
        val p = lp ?: return
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = ev.rawX; downRawY = ev.rawY
                downWinX = p.x; downWinY = p.y; dragMoved = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (ev.rawX - downRawX).roundToInt()
                val dy = (ev.rawY - downRawY).roundToInt()
                if (abs(dx) > 8 || abs(dy) > 8) dragMoved = true
                if (dragMoved) {
                    expand()
                    p.x = (downWinX + dx).coerceIn(0, screenW() - expandedWidthPx())
                    p.y = (downWinY + dy).coerceIn(0, screenH() - expandedHeightPx())
                    runCatching { wm?.updateViewLayout(rootView, p) }
                }
            }
            MotionEvent.ACTION_UP -> if (!dragMoved) expand() else snapToEdge()
        }
    }

    /** 气泡状态：整块都是热区。点一下放大，上下拖仍在同一边贴边。 */
    private fun onBubbleTouch(ev: MotionEvent): Boolean {
        // 收纳态的第一下只负责恢复成图标气泡，整段手势吞掉，避免同一手势又触发展开。
        if (sinked && ev.actionMasked == MotionEvent.ACTION_DOWN) {
            restoreFromSink()
            swallowingBubble = true
            return true
        }
        if (swallowingBubble) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                swallowingBubble = false
            }
            return true
        }
        val p = lp ?: return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = ev.rawX; downRawY = ev.rawY
                downWinX = p.x; downWinY = p.y; dragMoved = false
            }

            MotionEvent.ACTION_MOVE -> {
                val dy = (ev.rawY - downRawY).roundToInt()
                if (abs(ev.rawX - downRawX) > 6 || abs(dy) > 6) dragMoved = true
                if (dragMoved) {
                    p.y = (downWinY + dy).coerceIn(0, (screenH() - p.height).coerceAtLeast(0))
                    runCatching { wm?.updateViewLayout(rootView, p) }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragMoved) {
                    expand()
                } else {
                    VscrDiag.line("气泡拖动 y=" + p.y)
                    scope.launch {
                        runCatching {
                            SettingsDataStore.updateSettings {
                                it.copy(virtualScreenFloatWindowPosX = p.x, virtualScreenFloatWindowPosY = p.y)
                            }
                        }
                    }
                }
                dragMoved = false
            }
        }
        return true
    }

    /** 碰到左右边缘就折叠：边拖边收成气泡，不必等松手。 */
    private fun collapseIfAtEdge(): Boolean {
        if (!edgeCollapseEnabled) return false
        val p = lp ?: return false
        // 只用窗口真实宽度判断：必须真正贴到屏幕边缘才折叠，差一点不算。
        val sw = screenW()
        if (p.x <= 1) { collapse(SIDE_LEFT); return true }
        if (p.x + p.width >= sw - 1) { collapse(SIDE_RIGHT); return true }
        return false
    }

    /** 一次点击直通到灵屏之后，真机键盘常被系统顺手收掉，这里自动补回来。 */
    private fun ensureImeAfterTap() {
        if (collapsed || !imeBridgeOn) return
        val now = SystemClock.elapsedRealtime()
        if (now - imeReassertAt < 800L) return
        imeReassertAt = now
        rootView?.postDelayed({
            if (collapsed || !imeBridgeOn) return@postDelayed
            if (imeVisible) return@postDelayed
            VscrDiag.line("键盘桥 补弹 可见=false")
            // 补弹前仍要走一遍“灵屏是否真要键盘”的核对，否则用过一次键盘后，
            // 任何直通点击都会把真机正在用的键盘抢回来。
            offerImeBridge()
        }, 1200L)
    }

    /** 真机键盘没弹出来时，用主屏这块隐形编辑区把系统输入法叫到真机上。 */
    private fun openImeBridge(clean: Boolean = true) {
        val ctx = context ?: return
        val p = lp ?: return
        val sink = imeSinkView ?: return
        if (collapsed) return
        imeBridgeOn = true
        if (p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0) {
            p.flags = p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            runCatching { wm?.updateViewLayout(rootView, p) }
        }
        sink.isFocusable = true
        sink.isFocusableInTouchMode = true
        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        // 收起过一次之后，系统里会残留一个“已显示”的假状态：
        // 这时候再 showSoftInput 一律被丢掉（自检照样 active=true、accepting=true），
        // 表现就是“只有第一次弹得出来”。所以每次打开都先 hide 一下把状态清干净再申请。
        if (clean) runCatching { imm?.hideSoftInputFromWindow(sink.windowToken, 0) }
        var shown = false
        sink.postDelayed({
            sink.requestFocus()
            shown = runCatching {
                imm?.showSoftInput(sink, InputMethodManager.SHOW_IMPLICIT) == true
            }.getOrDefault(false)
            VscrDiag.line("键盘桥 申请1 ok=" + shown)
        }, 120L)
        sink.postDelayed({
            if (!shown) {
                runCatching { imm?.restartInput(sink) }
                sink.clearFocus()
                sink.requestFocus()
                shown = runCatching {
                    imm?.showSoftInput(sink, InputMethodManager.SHOW_IMPLICIT) == true
                }.getOrDefault(false)
                VscrDiag.line("键盘桥 申请2 ok=" + shown)
            }
        }, 480L)
        sink.postDelayed({
            if (!shown) {
                runCatching { imm?.toggleSoftInput(InputMethodManager.SHOW_IMPLICIT, 0) }
                VscrDiag.line("键盘桥 兜底 toggle")
            }
        }, 900L)
        sink.postDelayed({
            VscrDiag.line(
                "键盘桥 自检 accepting=" + (imm?.isAcceptingText == true) +
                    " active=" + (imm?.isActive(sink) == true) +
                    " shown=" + shown +
                    " flags=" + Integer.toHexString(lp?.flags ?: 0)
            )
        }, 1400L)
        VscrDiag.line("键盘桥 打开 flags=" + Integer.toHexString(p.flags))
    }

    private fun closeImeBridge() {
        if (!imeBridgeOn) return
        imeBridgeOn = false
        val ctx = context ?: return
        val p = lp ?: return
        val sink = imeSinkView
        runCatching {
            (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(sink?.windowToken, 0)
        }
        sink?.clearFocus()
        if (p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE == 0) {
            p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            runCatching { wm?.updateViewLayout(rootView, p) }
        }
        VscrDiag.line("键盘桥 关闭")
    }

    /**
     * 点过小窗内容之后核对：灵屏里的编辑框要是申请了输入法、系统却没弹，
     * 就由主屏接手把真机键盘叫出来；最多重试 2 次，避免每点一下都误弹。
     */
    private fun offerImeBridge() {
        if (collapsed) return
        // 键盘真的在屏上就什么都不做；被系统收掉了（或从没弹出来）就再叫一次。
        if (imeBridgeOn && imeVisible) return
        val session = info?.sessionId ?: return
        val ctx = context ?: return
        rootView?.postDelayed({
            if (collapsed) return@postDelayed
            if (imeBridgeOn && imeVisible) return@postDelayed
            scope.launch(Dispatchers.IO) {
                val json = runCatching {
                    val res = VirtualScreenSession.inputForViewer(
                        ctx, session, JSONObject().put("action", "ime"),
                    )
                    JSONObject(res.content)
                }.getOrNull()
                val requested = json?.optString("displayRequested") == "true"
                val shown = json?.optString("inputShown") == "true"
                withContext(Dispatchers.Main) {
                    if (shown || imeVisible) {
                        VscrDiag.line("键盘桥 跳过：系统键盘已在")
                    } else {
                        // 灵屏那侧经常“以为自己申请到了键盘、实际没渲染出来”，
                        // 所以只要点过小窗内容，就由主屏把真机输入法叫出来。
                        VscrDiag.line("键盘桥 检查 申请=" + requested + " 已显示=" + shown)
                        if (requested) openImeBridge() else VscrDiag.line("键盘桥 跳过：灵屏未请求键盘")
                    }
                }
            }
        }, 700L)
    }

    /** 灵屏里已提交的文字：拼音候选阶段不会打到灵屏应用里。 */
    private fun forwardText(text: String) {
        if (text.isEmpty()) return
        val session = info?.sessionId ?: return
        val ctx = context ?: return
        VscrDiag.line("键盘桥 提交=" + text.length + "字")
        scope.launch(Dispatchers.IO) {
            runCatching {
                VirtualScreenSession.inputForViewer(
                    ctx, session, JSONObject().put("action", "text").put("text", text),
                )
            }
        }
    }

    private fun forwardKey(code: Int) {
        val session = info?.sessionId ?: return
        val ctx = context ?: return
        val button = when (code) {
            KeyEvent.KEYCODE_DEL -> "DEL"
            KeyEvent.KEYCODE_ENTER -> "ENTER"
            else -> return
        }
        scope.launch(Dispatchers.IO) {
            runCatching {
                VirtualScreenSession.inputForViewer(
                    ctx, session, JSONObject().put("action", "key").put("button", button),
                )
            }
        }
    }

    private fun cancelLongPress() {
        runCatching { rootView?.removeCallbacks(longPress) }
    }

    /**
     * 旧的居中菜单（全屏 / 常规大小 / 键盘 / 关闭）已废弃。
     * 轻点“⋮”（已隐藏）或长按白边框，统一改到「展开/收起底部工具栏」。
     */
    private fun toggleDashboardToolbar() {
        if (collapsed) return
        dashboardExpanded.value = !dashboardExpanded.value
        VscrDiag.line("仪表盘 工具栏展开=" + dashboardExpanded.value)
    }

    /**
     * 接管：先把控制权收过来（关 AI 注入门、暂停运行时），再把浮窗藏起来，
     * 拉起全屏查看页让用户亲手操作 —— 小窗里永远不直接注入。
     */
    /**
     * 接管：浮窗自己铺满整屏（不跳转别的界面），触摸直通，顶栏显示「你正在操作」。
     * 退出（顶栏 ← 或系统返回）时恢复原档位并把控制权交还 AI。
     */
    /**
     * 接管：进独立的全屏接管页（TakeoverActivity），浮窗先收起。
     * 不再把浮窗拉成全屏 —— 那是这两天反复出问题的根源。
     */
    private fun enterTakeoverFullScreen() {
        val ctx = context ?: return
        cancelTouchStream()
        suppressed = true
        hide()
        TakeoverActivity.launch(ctx)
        VscrDiag.line("接管 转独立页")
    }

    /** 接管页关闭后恢复浮窗（会话仍在时重新出现）。 */
    fun resumeAfterTakeover() {
        scope.launch {
            suppressed = false
            val display = VirtualScreenSession.state.value.display ?: return@launch
            val settings = cachedSettings ?: return@launch
            show(display, settings)
        }
    }

    /** 顶栏 ×：结束这次灵屏会话并收起浮窗。 */
    private fun closeVirtualScreen() {
        val owner = VirtualScreenSession.state.value.display?.owner
        runCatching {
            if (owner.isNullOrBlank()) VirtualScreenSession.revokePermission()
            else VirtualScreenSession.closeOwner(owner)
        }
        takeoverFull = false
        rootView?.alpha = windowAlpha
        rootView?.background = borderDrawable
        hide()
    }

    /**
     * 兜底看护：会话已经不存在，但浮窗还在屏幕上（定格最后一帧）时强制收起。
     * 这类状态机漏更新从外部看不出来，只能靠周期校验兜住。
     */
    private fun startOrphanWatch() {
        scope.launch {
            while (true) {
                delay(2000)
                val showing = rootView != null
                if (!showing) continue
                if (takeoverFull) continue
                if (!VirtualScreenSession.isActive()) {
                    VscrDiag.line("浮窗兜底 会话已结束，收起浮窗")
                    hide()
                }
            }
        }
    }

    private fun attachDashboard(root: FrameLayout, display: VirtualDisplayInfo) {
        val ctx = context ?: return
        val store = controlStore ?: return
        if (dashboardView != null) return
        val view = AgentDashboardView(ctx).apply {
            onExit = { closeVirtualScreen() }
            onClose = { closeVirtualScreen() }
            // 接管 / 终止已挪到流体云；长按画面进全屏由 onObserveTouch 处理。
        }
        root.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        dashboardView = view
        dashboardJob?.cancel()
        dashboardJob = scope.launch {
            combine(store.state, VirtualScreenSession.state, dashboardExpanded) { s, v, e -> Triple(s, v, e) }
                .collect { (dash, vs, isExpanded) ->
                    val target = vs.display ?: display
                    view.render(dash, visionOf(vs), target.width, target.height, isExpanded, takeoverFull)
                }
        }
    }

    /** 会话里的手势就是 Agent 的“视线”：最近 1.2 秒内的落点画成 ripple。 */
    private fun visionOf(vs: VirtualScreenViewerState): AgentVisionState {
        val display = vs.display ?: return AgentVisionState()
        val gesture = vs.gesture
        val age = if (gesture != null) SystemClock.elapsedRealtime() - gesture.startedAt else Long.MAX_VALUE
        return AgentVisionState(
            displayWidth = display.width,
            displayHeight = display.height,
            touch = if (gesture != null && age in 0..1200L) {
                VisionTouch(gesture.x, gesture.y, gesture.startedAt, gesture.action)
            } else {
                null
            },
        )
    }

    private fun handleEffect(effect: DashboardEffect) {
        val ctx = context ?: return
        when (effect) {
            DashboardEffect.CloseInjectionGate -> AgentInjectionGate.close()
            DashboardEffect.OpenInjectionGate -> AgentInjectionGate.open()
            DashboardEffect.CancelInflightTouch -> cancelTouchStream()
            // 按会话下发：并发时只动自己这一条，别把别人的 run 一起暂停/停掉。
            DashboardEffect.PauseRuntime -> withRunId { runId ->
                if (!RunControl.state.value.isPaused(runId)) {
                    sendRuntimeAction(AgentRuntimeService.ACTION_PAUSE_RUN, runId)
                }
            }
            DashboardEffect.ResumeRuntime -> withRunId { runId ->
                if (RunControl.state.value.isPaused(runId)) {
                    sendRuntimeAction(AgentRuntimeService.ACTION_RESUME_RUN, runId)
                }
            }
            DashboardEffect.StopRuntime -> withRunId { runId ->
                sendRuntimeAction(AgentRuntimeService.ACTION_STOP_RUN, runId)
            }
            is DashboardEffect.PublishLive -> AgentLiveActivityBridge.publish(ctx, effect.spec)
            DashboardEffect.ClearLive -> AgentLiveActivityBridge.clear(ctx)
            // 半屏确认页与指令回传属于下一批：先把浮窗入口跑通。
            is DashboardEffect.OpenConfirmSheet -> Unit
            is DashboardEffect.EmitSteer -> Unit
            is DashboardEffect.Notice -> Unit
        }
    }

    /** 仪表盘 effect 只作用于当前这张卡对应的 run；没有 runId 就什么也不做。 */
    private inline fun withRunId(block: (String) -> Unit) {
        val runId = controlStore?.state?.value?.runId.orEmpty()
        if (runId.isNotBlank()) block(runId)
    }

    /** 主屏侧发运行时动作：和流体云按钮同一条通道，避免多一条状态路径。 */
    private fun sendRuntimeAction(action: String, runId: String = "") {
        val ctx = context ?: return
        scope.launch {
            runCatching {
                val intent = Intent(ctx, AgentRuntimeService::class.java).setAction(action)
                if (runId.isNotBlank()) {
                    intent.putExtra(AgentRuntimeService.EXTRA_RUN_ID, runId)
                }
                PendingIntent.getService(
                    ctx,
                    (action + runId).hashCode(),
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ).send()
            }.onFailure { VscrDiag.line("运行时动作失败 " + action + " " + it) }
        }
    }

    private fun cancelTouchStream() {
        if (!touchStreamActive) return
        touchStreamActive = false
        sendTouch("cancel", lastTouchX, lastTouchY)
    }

    /** 用户一碰画面就抢占控制权：先关 AI 的门，再让这一下照常直通。 */
    private fun preemptForUser() {
        val store = controlStore ?: return
        if (store.state.value.mode == AgentControlMode.AI_RUNNING) {
            store.dispatch(DashboardEvent.UserPressedContent)
        }
    }

    /** 会话状态 → 仪表盘状态：只在阶段切换时发事件，别每帧都改。 */
    private fun syncControl(vs: VirtualScreenViewerState) {
        val store = controlStore ?: return
        val current = store.state.value
        val runId = vs.activeRunId
        if (vs.taskPhase == VirtualScreenTaskPhase.RUNNING && runId != null && current.runId != runId) {
            // 会话 key 取自 RunControl 的绑定表：接管卡与进度卡据此落到同一个通知 ID。
            store.dispatch(
                DashboardEvent.RunStarted(
                    runId = runId,
                    sessionId = vs.display?.sessionId.orEmpty(),
                    conversationKey = RunControl.state.value.conversationOfRun[runId].orEmpty(),
                ),
            )
            RunProgressNotifier.delegateToVirtualScreen(runId, true)
        } else if (vs.taskPhase != VirtualScreenTaskPhase.RUNNING && current.runId != null) {
            val finishedRunId = current.runId.orEmpty()
            store.dispatch(DashboardEvent.RunFinished(vs.taskPhase == VirtualScreenTaskPhase.COMPLETED))
            RunProgressNotifier.delegateToVirtualScreen(finishedRunId, false)
        }
        val step = vs.operations.lastOrNull()?.name
        if (vs.taskPhase == VirtualScreenTaskPhase.RUNNING && !step.isNullOrBlank() && step != current.step) {
            store.dispatch(DashboardEvent.StepChanged(step, vs.operations.size, 0))
        }
    }

    /** 回到“常规大小”（内容短边 160dp）：放大后单指触控才恢复直通。 */
    private fun restoreNormalSize() {
        if (collapsed) return
        val aspect = contentAspect()
        if (aspect > 0f) {
            setFromInnerShort(NORMAL_INNER_DP, aspect)
        } else {
            val ratio = if (curWidthDp > 0) curHeightDp.toFloat() / curWidthDp else 1f
            curWidthDp = NORMAL_INNER_DP.roundToInt()
            curHeightDp = (curWidthDp * ratio).roundToInt().coerceAtLeast(curWidthDp)
        }
        applySize()
        clampToScreen()
        VscrDiag.line("常规大小 外框=" + curWidthDp + "x" + curHeightDp + "dp")
    }

    /**
     * 只有窗口真正贴到屏幕左右边缘（横竖屏同规则）才折叠成气泡。
     * 原先 4dp 阈值会让“还差一点碰到”也折叠，横屏窗口更宽时尤其容易被误判。
     * 拖拽已经限制在屏幕内，所以贴边时 x 恰好是 0 或 屏宽-窗宽，这里留 1px 容差兜住取整误差。
     */
    private fun maybeCollapse() {
        val p = lp ?: return
        if (p.x <= 1) collapse(SIDE_LEFT)
        else if (p.x + p.width >= screenW() - 1) collapse(SIDE_RIGHT)
    }

    /** 小帽子跟随窗口：水平居中、贴在上沿外侧。 */
    private fun syncDotsHat() {
        val v = dotsHat ?: return
        val p = lp ?: return
        val hp = dotsHatParams ?: return
        if (v.visibility != View.VISIBLE) return
        hp.x = (p.x + (p.width - hp.width) / 2).coerceIn(0, (screenW() - hp.width).coerceAtLeast(0))
        hp.y = (p.y - hp.height).coerceAtLeast(0)
        runCatching { wm?.updateViewLayout(v, hp) }
    }

    /** 旧的“⋮”帽子已由顶部状态栏取代，恒隐藏；调用点保留，折叠/展开流程不用改。 */
    private fun setDotsHatVisible(on: Boolean) {
        val v = dotsHat ?: dotsButton ?: return
        v.visibility = View.GONE
    }

    private fun collapse(side: Int) {
        val p = lp ?: return
        val d = context?.resources?.displayMetrics?.density ?: 1f
        collapsed = true; collapsedSide = side
        preCollapseX = p.x; preCollapseY = p.y
        surfaceView?.visibility = View.GONE
        // 气泡只有 35dp：仪表盘不藏起来会盖满整个气泡，点哪儿都像没反应。
        dashboardView?.visibility = View.GONE
        // 收起后用被投屏应用的图标，而不是一个灰圆；取不到就退回 Eta 自己的图标（一定有）。
        runCatching {
            val icon = focusedAppIcon() ?: context?.let { ctx ->
                runCatching { ctx.packageManager.getApplicationIcon(ctx.packageName) }.getOrNull()
            }
            if (icon != null) {
                triggerBar?.background = icon
                VscrDiag.line("收气泡 图标=" + (VirtualScreenSession.state.value.display?.focusedPackage ?: "self"))
            } else {
                VscrDiag.line("收气泡 图标为空")
            }
        }
        triggerBar?.visibility = View.VISIBLE
        (triggerBar?.layoutParams as? FrameLayout.LayoutParams)?.gravity =
            if (side == SIDE_LEFT) Gravity.START or Gravity.CENTER_VERTICAL else Gravity.END or Gravity.CENTER_VERTICAL
        val bubble = (35f * d).roundToInt()
        p.y = (p.y + (p.height - bubble) / 2).coerceIn(0, (screenH() - bubble).coerceAtLeast(0))
        p.width = bubble
        p.height = bubble
        p.x = if (side == SIDE_LEFT) 0 else screenW() - bubble
        runCatching { wm?.updateViewLayout(rootView, p) }
        // 气泡阶段 ⋮ 藏起来，整块热区都归“点开 / 上下拖”。
        setDotsHatVisible(false)
        closeImeBridge()
        sinked = false
        rootView?.removeCallbacks(sinkRunnable)
        if (sinkEnabled) rootView?.postDelayed(sinkRunnable, 2000L)
        VscrDiag.line("折叠成气泡 side=" + side + " 位置=" + p.x + "," + p.y + " 边长=" + bubble + " 屏宽=" + screenW() + " 窗宽=" + p.width)
    }

    private fun expand() {
        val p = lp ?: return
        rootView?.removeCallbacks(sinkRunnable)
        sinked = false
        collapsed = false
        surfaceView?.visibility = View.VISIBLE
        dashboardView?.visibility = View.VISIBLE
        triggerBar?.visibility = View.GONE
        setDotsHatVisible(true)
        VscrDiag.line("气泡展开 " + curWidthDp + "x" + curHeightDp + "dp")
        resetToMinimumSize()
        p.width = expandedWidthPx()
        p.height = expandedHeightPx()
        p.x = preCollapseX.coerceIn(0, (screenW() - p.width).coerceAtLeast(0))
        p.y = preCollapseY.coerceIn(0, (screenH() - p.height).coerceAtLeast(0))
        runCatching { wm?.updateViewLayout(rootView, p) }
    }

    /** 当前被投屏应用（灵屏里的前台应用）的图标。 */
    private fun focusedAppIcon(): android.graphics.drawable.Drawable? {
        val pkg = VirtualScreenSession.state.value.display?.focusedPackage ?: return null
        return runCatching { context?.packageManager?.getApplicationIcon(pkg) }.getOrNull()
    }

    /**
     * 贴边收纳：窗口收成一条细圆弧，只露出边缘一小截。
     * 点它或往外拖就展开回气泡。
     */
    private fun sinkToEdge() {
        val p = lp ?: return
        if (!collapsed) return
        val d = context?.resources?.displayMetrics?.density ?: 1f
        val thin = (8f * d).roundToInt()
        val tall = (36f * d).roundToInt()
        sinked = true
        p.width = thin
        p.height = tall
        p.x = if (collapsedSide == SIDE_LEFT) 0 else (screenW() - thin).coerceAtLeast(0)
        p.y = p.y.coerceIn(0, (screenH() - tall).coerceAtLeast(0))
        borderDrawable?.let { it.cornerRadius = thin / 2f }
        // 收纳态必须自己有一条底色，否则透明背景下这条细边等于隐形。
        // 颜色取应用图标的四个角色色做横向渐变，整体 50% 透明。
        rootView?.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            sinkedGradientColors(),
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = thin / 2f
        }
        rootView?.invalidateOutline()
        runCatching { wm?.updateViewLayout(rootView, p) }
        // 只露圆弧：图标先藏起来，避免 12dp 宽里糊成一团。
        triggerBar?.alpha = 0f
        VscrDiag.line("贴边收纳 thin=" + thin)
    }

    /**
     * 收纳条的渐变色：从当前应用图标上采四个点，作为四个角色色。
     * 统一 50% 透明（alpha=128），四色缺省时退回蓝色系。
     */
    private fun sinkedGradientColors(): IntArray {
        val fallback = intArrayOf(0x807BD8FF.toInt(), 0x805FE08C.toInt(), 0x80FFC44D.toInt(), 0x80E5484D.toInt())
        val ctx = context ?: return fallback
        val icon = focusedAppIcon() ?: runCatching {
            ctx.packageManager.getApplicationIcon(ctx.packageName)
        }.getOrNull() ?: return fallback
        return runCatching {
            val size = 48
            val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            icon.setBounds(0, 0, size, size)
            icon.draw(canvas)
            val points = arrayOf(12 to 12, 36 to 12, 12 to 36, 36 to 36)
            val colors = IntArray(4) { index ->
                val (x, y) = points[index]
                val c = bmp.getPixel(x, y)
                // 保留 RGB，统一 50% 透明；全透明像素说明那角没内容，退回兜底色。
                if (android.graphics.Color.alpha(c) < 8) fallback[index]
                else (c and 0x00FFFFFF) or 0x80000000.toInt()
            }
            bmp.recycle()
            colors
        }.getOrDefault(fallback)
    }

    /** 从收纳态回到气泡态。 */
    private fun restoreFromSink() {
        if (!sinked) return
        sinked = false
        val p = lp ?: return
        val d = context?.resources?.displayMetrics?.density ?: 1f
        val bubble = (35f * d).roundToInt()
        p.width = bubble
        p.height = bubble
        p.x = if (collapsedSide == SIDE_LEFT) 0 else (screenW() - bubble).coerceAtLeast(0)
        borderDrawable?.let { it.cornerRadius = cornerRadiusPx(p.width, p.height) }
        rootView?.invalidateOutline()
        runCatching { wm?.updateViewLayout(rootView, p) }
        triggerBar?.alpha = 1f
        rootView?.background = borderDrawable
        rootView?.removeCallbacks(sinkRunnable)
        if (sinkEnabled) rootView?.postDelayed(sinkRunnable, 2000L)
    }

    /** 真实帧尺寸回报：比例一变就重算窗口，保证内容区比例等于投影比例。 */
    private fun onFrameSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val aspect = width.toFloat() / height
        if (!aspect.isFinite() || aspect <= 0f) return
        if (frameAspect > 0f && abs(frameAspect - aspect) / aspect < 0.005f) return
        frameAspect = aspect
        VscrDiag.line("帧尺寸 " + width + "x" + height + " 帧比例=" + String.format("%.4f", aspect))
        rootView?.post {
            if (collapsed) return@post
            val innerShort = if (innerShortDp > 0f) innerShortDp
            else (if (aspect >= 1f) curHeightDp else curWidthDp) - BORDER_DP * 2f
            setFromInnerShort(innerShort, aspect)
            applySize()
            clampToScreen()
            VscrDiag.line("按帧比例重算 外框=" + curWidthDp + "x" + curHeightDp + "dp")
        }
    }

    /** 内容比例（宽/高）：优先真实帧比例；没有内容信息时返回 0。 */
    private fun contentAspect(): Float {
        if (frameAspect.isFinite() && frameAspect > 0f) return frameAspect
        val display = info ?: return 0f
        if (display.width <= 0 || display.height <= 0) return 0f
        val aspect = display.width.toFloat() / display.height
        return if (aspect.isFinite() && aspect > 0f) aspect else 0f
    }

    /** 回到最小档：贴边展开、重新出现都用它，横竖屏都按内容比例等比。 */
    private fun resetToMinimumSize() {
        val aspect = contentAspect()
        if (aspect > 0f) {
            setFromInnerShort(minWidthDp.toFloat(), aspect)
            return
        }
        val ratio = if (curWidthDp > 0) curHeightDp.toFloat() / curWidthDp else 1f
        curWidthDp = minWidthDp
        curHeightDp = (minWidthDp * ratio).roundToInt().coerceAtLeast(minWidthDp)
    }

    /**
     * 点一下小窗：在“最小档”和“最小档 +1/3”之间来回切。
     * 已经比这一档大（自己捏过）时，点一下回到最小档。
     */
    /**
     * 双击悬浮窗：在固定档位间循环（96 → 144 → 200 → 272 → 96）。
     * 按内容短边定位档位，横竖屏用同一套大小；切换后按内容比例重算外框，不留黑边。
     */
    private fun cycleSizeTier() {
        if (collapsed) return
        val aspect = contentAspect()
        if (aspect <= 0f) return
        val border = BORDER_DP * 2f
        val currentShort = innerShortDp.takeIf { it > 0f }
            ?: ((if (aspect >= 1f) curHeightDp else curWidthDp) - border)
        // 停在两档之间（被捏合过）时先归到最近档，再往下走。
        val nearest = sizeTiersDp.indices.minByOrNull { abs(sizeTiersDp[it] - currentShort) } ?: 0
        val next = nearest + 1
        tierIndex = if (next >= sizeTiersDp.size) 0 else next
        setFromInnerShort(sizeTiersDp[tierIndex].toFloat(), aspect)
        applySize()
        clampToScreen()
        storeSizeTier()
        VscrDiag.line("双击切档 " + sizeTiersDp[tierIndex] + "dp 外框=" + curWidthDp + "x" + curHeightDp)
    }

    /** 记住当前档位，下次浮窗出现还用它。 */
    private fun storeSizeTier() {
        scope.launch {
            runCatching {
                SettingsDataStore.updateSettings {
                    it.copy(
                        virtualScreenFloatWindowWidthDp = curWidthDp,
                        virtualScreenFloatWindowHeightDp = curHeightDp,
                    )
                }
            }
        }
    }

    /** 松手时贴边吸附：只有真的碰到屏幕边缘（留 1px 取整容差）才齐边，差一点就保持原位。 */
    private fun snapToEdge() {
        val p = lp ?: return
        val gap = 1
        val maxX = (screenW() - p.width).coerceAtLeast(0)
        val maxY = (screenH() - p.height).coerceAtLeast(0)
        var nx = p.x
        var ny = p.y
        if (p.x <= gap) nx = 0 else if (maxX - p.x <= gap) nx = maxX
        if (p.y <= gap) ny = 0 else if (maxY - p.y <= gap) ny = maxY
        if (nx != p.x || ny != p.y) {
            p.x = nx; p.y = ny
            runCatching { wm?.updateViewLayout(rootView, p) }
            VscrDiag.line("贴边吸附 x=" + nx + " y=" + ny)
        }
    }

    private fun clampToScreen() {
        val p = lp ?: return
        val maxX = (screenW() - p.width).coerceAtLeast(0)
        val maxY = (screenH() - p.height).coerceAtLeast(0)
        val nx = p.x.coerceIn(0, maxX); val ny = p.y.coerceIn(0, maxY)
        if (nx != p.x || ny != p.y) {
            p.x = nx; p.y = ny
            runCatching { wm?.updateViewLayout(rootView, p) }
        }
    }

    /** 折叠状态：窗口保持气泡大小并贴到当前方向的边缘，避免旋转后变成又大又淡的白框。 */
    private fun reCollapse() {
        val p = lp ?: return
        val d = context?.resources?.displayMetrics?.density ?: 1f
        val bubble = (35f * d).roundToInt()
        p.width = bubble
        p.height = bubble
        p.x = if (collapsedSide == SIDE_LEFT) 0 else (screenW() - bubble).coerceAtLeast(0)
        p.y = p.y.coerceIn(0, (screenH() - bubble).coerceAtLeast(0))
        runCatching { wm?.updateViewLayout(rootView, p) }
    }

    /**
     * 圆角半径（px）：贴齐真实屏幕的 R 角观感 —— 短边约 7%，并收在 8–18dp。
     * 以前固定 16–24dp，小窗上看着像一枚圆钮，不像一块屏幕。
     */
    private fun cornerRadiusPx(widthPx: Int, heightPx: Int): Float {
        val d = context?.resources?.displayMetrics?.density ?: 1f
        if (widthPx <= 0 || heightPx <= 0) return 12f * d
        val shortDp = min(widthPx, heightPx) / d
        return (shortDp * 0.07f).coerceIn(8f, 18f) * d
    }

    private fun expandedWidthPx() = (curWidthDp * (context?.resources?.displayMetrics?.density ?: 1f)).roundToInt()
    private fun expandedHeightPx() = (curHeightDp * (context?.resources?.displayMetrics?.density ?: 1f)).roundToInt()
    private fun screenW() = context?.resources?.displayMetrics?.widthPixels ?: 0
    private fun screenH() = context?.resources?.displayMetrics?.heightPixels ?: 0

    private fun canOverlay() = context?.let { android.provider.Settings.canDrawOverlays(it) } == true
    private fun requestOverlayPermission() {
        val ctx = context ?: return
        runCatching {
            ctx.startActivity(
                Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** 主屏上的隐形编辑区：只上报“已提交”的文字和删除键，拼音候选交给输入法自己。 */
    private class ImeSinkView(
        context: Context,
        private val onCommit: (String) -> Unit,
        private val onKey: (Int) -> Unit,
    ) : View(context) {
        override fun onCheckIsTextEditor() = true

        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
            outAttrs.inputType = InputType.TYPE_CLASS_TEXT
            outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_FULLSCREEN
            outAttrs.initialCapsMode = 0
            return object : BaseInputConnection(this@ImeSinkView, true) {
                override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                    val ok = super.commitText(text, newCursorPosition)
                    text?.toString()?.takeIf { it.isNotEmpty() }?.let(onCommit)
                    return ok
                }

                override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                    repeat(beforeLength.coerceIn(0, 32)) { onKey(KeyEvent.KEYCODE_DEL) }
                    return super.deleteSurroundingText(beforeLength, afterLength)
                }

                override fun sendKeyEvent(event: KeyEvent): Boolean {
                    if (event.action == KeyEvent.ACTION_DOWN &&
                        (event.keyCode == KeyEvent.KEYCODE_DEL || event.keyCode == KeyEvent.KEYCODE_ENTER)
                    ) onKey(event.keyCode)
                    return super.sendKeyEvent(event)
                }
            }
        }

        override fun onTouchEvent(event: MotionEvent) = true
    }

    private const val SIDE_LEFT = 0
    private const val SIDE_RIGHT = 1
}
