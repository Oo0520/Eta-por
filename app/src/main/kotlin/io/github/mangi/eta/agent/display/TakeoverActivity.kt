package io.github.mangi.eta.agent.display

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import io.github.mangi.eta.agent.display.dashboard.AgentLiveCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 接管页（Human-in-the-loop 的主路径）。
 *
 * 与浮窗彻底解耦：独立 Activity、透明窗口、自己的退出口径。
 * 关键约束（踩过的坑都在这）：
 * 1. `insetsController` 只能在 setContentView 之后取，否则 PhoneWindow.mDecor 为 null 直接 NPE（Bug #005）；
 * 2. 换宿主 Surface 后必须 `notifyHostSurface`，否则 helper 还画到浮窗那块已 detach 的 Surface 上（黑屏）；
 * 3. 光边只能移动渐变起点，不能 rotate 图形，否则圆角矩形被转成斜线穿过屏幕。
 */
internal class TakeoverActivity : ComponentActivity() {

    private var surfaceView: TakeoverHostView? = null
    private var frameJob: Job? = null
    private var watchJob: Job? = null
    private var sessionId: String = ""

    /** 进入时的前台包名，用于「用户把事做完了」的自动交还判据。 */
    private var baselinePackage: String = ""
    private var enteredAt: Long = SystemClock.elapsedRealtime()
    private var movedStreak = 0
    /** 只在 onDestroy 里统一输出，避免多条 reason 散落。 */
    private var exitReason: String? = null
    @Volatile
    private var firstFrameLogged = false

    @Volatile
    private var lastTouchAt: Long = SystemClock.elapsedRealtime()
    private var phase by mutableStateOf("人在操作")
    /** 接管页的键盘输入面板。 */
    private var showKeyboard by mutableStateOf(false)
    private var keyboardText by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val display = VirtualScreenSession.state.value.display
        if (display == null) {
            finish()
            return
        }
        sessionId = display.sessionId
        VscrDiag.line("接管页 进入 session=" + display.sessionId + " 源=" + display.width + "x" + display.height)
        // 原应用只支持竖屏时，接管页也锁竖屏，避免真机转横屏后画面被压扁。
        // 先让 helper 报一次顶层 Activity 的真实方向；拿不到再退回包级判断。
        runCatching { VirtualScreenSession.refreshForViewer(this) }
        val landscapeOk = VirtualScreenSession.lastLandscape ?: appSupportsLandscape(display.focusedPackage)
        requestedOrientation = if (landscapeOk) {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        VscrDiag.line(
            "接管页 方向 pkg=" + display.focusedPackage +
                " helper=" + VirtualScreenSession.lastLandscape +
                " 最终=" + landscapeOk
        )
        window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        AgentLiveCoordinator.beginTakeover(this)
        // 接管页在前台 = 人眼在看：帧率对齐真机屏幕刷新率（q90），退出时在 onDestroy 释放。
        VirtualScreenSession.setWatchProfile("takeover", VirtualScreenSession.WatchProfile.TAKEOVER)
        baselinePackage = display.focusedPackage
        enteredAt = SystemClock.elapsedRealtime()
        lastTouchAt = SystemClock.elapsedRealtime()
        startExitWatcher()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // 系统返回键作用在灵屏里的 App 上（返回上一页），不退出接管。
                // 退出接管统一走右上角 ✕ —— 两者混在一起时，用户想返回上一页却退出了整个接管。
                val session = VirtualScreenSession.state.value.display?.sessionId ?: return@handleOnBackPressed
                VscrDiag.line("接管页 返回键 → 注入 back")
                VirtualScreenInputPump.submit(
                    this@TakeoverActivity,
                    session,
                    org.json.JSONObject().put("action", "back"),
                )
            }
        })

        val cornerPx = screenCornerRadiusPx()
        setContent {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val boxW = constraints.maxWidth
                val boxH = constraints.maxHeight
                val vp = VirtualScreenViewport.fit(boxW, boxH, display.width, display.height)
                val contentRect = vp?.let {
                    Rect(
                        it.left,
                        it.top,
                        it.left + display.width * it.scale,
                        it.top + display.height * it.scale,
                    )
                } ?: Rect(0f, 0f, boxW.toFloat(), boxH.toFloat())

                // 兜底底色：画面还没上来时不要把下层（Eta 聊天页）透出来。
                Box(Modifier.fillMaxSize().background(ComposeColor(0xCC07090C))) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            TakeoverHostView(ctx).apply {
                                fitContent = true
                                touchEnabled = true
                                onHostSurface = { host ->
                                    // 接管页单独走直通：它是 Activity，硬件加速可用，
                                    // TextureView 在这里能正常拿到 SurfaceTexture（overlay 浮窗不行）。
                                    // 浮窗那边保持 JPEG 回落，两边互不影响。
                                    VscrDiag.line("直通 接管页回调 host=" + (host != null))
                                    VirtualScreenHostSurfaces.attach(
                                        VirtualScreenSession.currentOwner().orEmpty(), "takeover", host,
                                    )
                                    val live = VirtualScreenHostSurfaces.live && host != null
                                    setDirectMode(live)
                                    if (live) {
                                        frameJob?.cancel()
                                    } else if (frameJob?.isActive != true) {
                                        startFallbackFrames(this)
                                    }
                                    if (host != null) {
                                        val app = applicationContext
                                        Thread {
                                            runCatching { VirtualScreenSession.notifyHostSurface(app) }
                                        }.apply { isDaemon = true }.start()
                                    }
                                }
                                onInput = { session, args ->
                                    VirtualScreenInputPump.submit(this@TakeoverActivity, session, args)
                                }
                                setDisplayInfo(VirtualScreenSession.state.value.display)
                                surfaceView = this
                                // Compose 的 AndroidView 是惰性的：setContent 返回时 factory 还没执行，
                                // 在 onCreate 末尾取 surfaceView 一定是 null，回落帧就永远起不来。
                                startFallbackFrames(this)
                            }
                        },
                    )

                    // 只记录手指活动，不消费事件（触摸要直通灵屏）
                    Box(
                        Modifier
                            .fillMaxSize()
                            .pointerInteropFilter {
                                lastTouchAt = SystemClock.elapsedRealtime()
                                false
                            }
                    )

                    NeonBorder(contentRect, cornerPx, Modifier.fillMaxSize())

                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 14.dp, top = 22.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(ComposeColor(0xFF5FE08C))
                        )
                        Spacer(Modifier.width(8.dp))
                        BasicText(
                            phase,
                            style = TextStyle(
                                // 与左侧状态点同色系（绿），80% 不透明，比纯白柔和。
                                color = ComposeColor(0xCC5FE08C),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                            ),
                        )
                        Spacer(Modifier.weight(1f))
                        Box(
                            Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(ComposeColor(0x99000000))
                                .clickable { showKeyboard = true },
                            contentAlignment = Alignment.Center,
                        ) {
                            BasicText("⌨", style = TextStyle(color = ComposeColor.White, fontSize = 15.sp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(ComposeColor(0x99000000))
                                .clickable { jumpToPhysicalApp() },
                            contentAlignment = Alignment.Center,
                        ) {
                            // ↗：把当前灵屏里的 App 直接在真机上打开，操作完用通知回到灵屏。
                            BasicText("↗", style = TextStyle(color = ComposeColor.White, fontSize = 16.sp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(ComposeColor(0x99000000))
                                .clickable {
                                    exitReason = "done"
                                    AgentLiveCoordinator.endTakeover(this@TakeoverActivity)
                                    VirtualScreenFloatWindow.resumeAfterTakeover()
                                    finish()
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            BasicText("✕", style = TextStyle(color = ComposeColor.White, fontSize = 15.sp))
                        }
                    }
                }

                if (showKeyboard) {
                    androidx.compose.ui.window.Dialog(onDismissRequest = { showKeyboard = false }) {
                        Column(
                            Modifier
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                                .background(ComposeColor(0xFF14171B))
                                .padding(18.dp),
                        ) {
                            BasicText(
                                "向灵屏里的应用输入文字",
                                style = TextStyle(color = ComposeColor(0xFFF2F4F7), fontSize = 14.sp),
                            )
                            Spacer(Modifier.height(10.dp))
                            androidx.compose.foundation.text.BasicTextField(
                                value = keyboardText,
                                onValueChange = { keyboardText = it },
                                textStyle = TextStyle(color = ComposeColor(0xFFF2F4F7), fontSize = 14.sp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                                    .background(ComposeColor(0xFF1E232A))
                                    .padding(12.dp),
                            )
                            Spacer(Modifier.height(12.dp))
                            Row(Modifier.fillMaxWidth()) {
                                Spacer(Modifier.weight(1f))
                                BasicText(
                                    "取消",
                                    style = TextStyle(color = ComposeColor(0xFF9AA4B2), fontSize = 14.sp),
                                    modifier = Modifier.clickable { showKeyboard = false }.padding(10.dp),
                                )
                                BasicText(
                                    "发送",
                                    style = TextStyle(
                                        color = ComposeColor(0xFF5FE08C),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                    ),
                                    modifier = Modifier.clickable {
                                        val text = keyboardText
                                        if (text.isNotBlank()) {
                                            runCatching {
                                                VirtualScreenInputPump.submit(
                                                    this@TakeoverActivity,
                                                    sessionId,
                                                    JSONObject().put("action", "text").put("text", text),
                                                )
                                            }
                                            VscrDiag.line("接管页 键盘输入 " + text.length + " 字")
                                        }
                                        keyboardText = ""
                                        showKeyboard = false
                                    }.padding(10.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
        applyImmersiveMode()
    }

    /**
     * 全屏沉浸。必须在 setContentView 之后：`PhoneWindow.getInsetsController()`
     * 要求 mDecor 已安装，提前取会 NPE。
     */
    /**
     * 跳去真机继续操作。
     *
     * 灵屏和真机跑的是同一个应用、同一个账号，但少数操作（生物识别、系统级授权、
     * 需要真机传感器）只能在真机上做。这里直接把当前包在真机拉起，
     * 同时挂一条常驻通知，点它就回到灵屏接管，不用重新找入口。
     */
    private fun jumpToPhysicalApp() {
        val pkg = VirtualScreenSession.state.value.display?.focusedPackage.orEmpty()
        if (pkg.isBlank()) {
            VscrDiag.line("跳真机 失败：拿不到当前包名")
            return
        }
        val intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            VscrDiag.line("跳真机 失败：应用不可启动 pkg=$pkg")
            return
        }
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
            .onFailure { VscrDiag.line("跳真机 启动失败 pkg=$pkg err=" + it) }
            .onSuccess { VscrDiag.line("跳真机 已启动 pkg=$pkg") }
        VirtualScreenNotification.showReturnToLingscreen(this, pkg)
    }

    private fun applyImmersiveMode() {
        runCatching {
            window.decorView
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
            window.insetsController?.let {
                it.hide(android.view.WindowInsets.Type.systemBars())
                it.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }.onFailure { VscrDiag.line("接管页 沉浸模式失败 " + it) }
    }

    /** 读取本机真实屏幕圆角（API 31+ 的 RoundedCorner），拿不到按 33dp 兜底。 */
    private fun screenCornerRadiusPx(): Float {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            runCatching {
                display?.getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_LEFT)?.radius
            }.getOrNull()?.let { if (it > 0) return it.toFloat() }
        }
        return 33f * resources.displayMetrics.density
    }

    /**
     * 退出的三条兜底（任一生效）：
     * 1. 点「完成」/ 返回键 —— 手动路径；
     * 2. 前台包名变化 —— 用户做完去了别处；
     * 3. 静默 100 秒 —— 人离开或忘了退出。
     */
    private fun startExitWatcher() {
        watchJob?.cancel()
        watchJob = lifecycleScope.launch {
            while (isActive) {
                delay(700)
                val current = VirtualScreenSession.state.value.display
                if (current == null) {
                    exitReason = "session_lost"
                    finish()
                    return@launch
                }
                val elapsed = SystemClock.elapsedRealtime() - enteredAt
                val differs = baselinePackage.isNotBlank() &&
                    current.focusedPackage.isNotBlank() &&
                    current.focusedPackage != baselinePackage
                // 宽限 10 秒，并要求连续两次采样都不同：刚进接管页时灵屏前台包会短暂跳动，
                // 早前没有宽限，导致接管层 3–5 秒就被自动交还（日志里 4 秒一轮重建）。
                if (differs) {
                    movedStreak++
                } else {
                    movedStreak = 0
                }
                val moved = elapsed > 10_000L && movedStreak >= 2
                val idleMs = SystemClock.elapsedRealtime() - lastTouchAt
                if (moved || idleMs > 100_000L) {
                    exitReason = if (moved) "package_changed" else "idle"
                    phase = "正在交还"
                    delay(220)
                    AgentLiveCoordinator.endTakeover(this@TakeoverActivity)
                    VirtualScreenFloatWindow.resumeAfterTakeover()
                    finish()
                    return@launch
                }
            }
        }
    }

    /** 直通不可用时整帧回落，保证不是黑屏。 */
    private fun startFallbackFrames(view: TakeoverHostView) {
        frameJob?.cancel()
        frameJob = lifecycleScope.launch {
            while (isActive) {
                val startedAt = android.os.SystemClock.elapsedRealtime()
                val result = withContext(Dispatchers.IO) {
                    runCatching { VirtualScreenSession.observeForViewer(this@TakeoverActivity) }.getOrNull()
                }
                val display = VirtualScreenSession.state.value.display
                if (result == null) VscrDiag.line("接管页 取帧失败（命令超时/无会话）")
                val dataUrl = result?.images?.firstOrNull()?.reference
                if (result != null && dataUrl == null) {
                    VscrDiag.line("接管页 取帧无图 code=" + (result.content.take(120)))
                }
                if (dataUrl != null && display != null) {
                    val base64 = dataUrl.substringAfter("base64,", "")
                    if (base64.isNotBlank()) {
                        runCatching {
                            val bytes = Base64.decode(base64, Base64.DEFAULT)
                            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            if (bitmap != null) {
                                view.setFrame(bitmap, display)
                                if (!firstFrameLogged) {
                                    firstFrameLogged = true
                                    VscrDiag.line("接管页 首帧 ok " + bitmap.width + "x" + bitmap.height)
                                }
                            }
                        }
                    }
                }
                // 节奏跟随出帧档位（接管档 = 真机刷新率）。取帧本身要耗时，
                // 只补差值；追不上就不等，跑到这条链路的物理上限为止。
                val interval = VirtualScreenSession.currentFrameIntervalMs().toLong()
                val spent = android.os.SystemClock.elapsedRealtime() - startedAt
                val budget = interval - spent
                if (budget > 0L) delay(budget)
            }
        }
    }

    /** 原应用是否声明支持横屏；查不到就按支持处理（不擅自锁死）。 */
    private fun appSupportsLandscape(pkg: String): Boolean {
        if (pkg.isBlank()) return true
        return runCatching {
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                .setPackage(pkg)
            val info = packageManager.queryIntentActivities(intent, 0).firstOrNull()?.activityInfo
                ?: return true
            when (info.screenOrientation) {
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE -> true
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT,
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED -> false
                else -> true
            }
        }.getOrDefault(true)
    }

    override fun onDestroy() {
        VscrDiag.line(
            "接管页 存活 " + (SystemClock.elapsedRealtime() - enteredAt) + "ms" +
                " 退出原因=" + (exitReason ?: "unknown")
        )
        watchJob?.cancel()
        watchJob = null
        frameJob?.cancel()
        frameJob = null
        VirtualScreenSession.setWatchProfile("takeover", null)
        surfaceView?.let { VirtualScreenHostSurfaces.attach("takeover", null) }
        // 退出接管 = 撤掉直通：通知 helper 把输出面切回采集面，
        // 否则灵屏画面会停在最后一个直通帧上（浮窗也会显示冻结画面）。
        runCatching {
            val app = applicationContext
            Thread { runCatching { VirtualScreenSession.notifyHostSurface(app) } }
                .apply { isDaemon = true }.start()
        }
        surfaceView = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_TAKEOVER = "eta.takeover"

        fun launch(context: Context) {
            runCatching {
                context.startActivity(
                    Intent(context, TakeoverActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        .putExtra(EXTRA_TAKEOVER, true)
                )
            }
        }
    }
}

/**
 * 四边跑马灯：沿对角线扫过的线性渐变，贴着画面矩形走。
 *
 * 不能旋转图形 —— `rotate{}` 会把圆角矩形本身转成斜四边形，屏幕上就是两条斜线穿过去。
 */
@Composable
private fun NeonBorder(rect: Rect, cornerRadiusPx: Float, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "neon")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )
    Canvas(modifier) {
        val stroke = 2.5.dp.toPx()
        val target = Rect(
            rect.left + stroke,
            rect.top + stroke,
            rect.right - stroke,
            rect.bottom - stroke,
        )
        val radius = CornerRadius(cornerRadiusPx)
        val w = target.width.coerceAtLeast(1f)
        val travel = w + target.height

        val head = target.left - w + phase * travel * 1.6f
        drawRoundRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    ComposeColor(0x007BD8FF),
                    ComposeColor(0xFF7BD8FF),
                    ComposeColor(0x007BD8FF),
                ),
                start = Offset(head, target.bottom),
                end = Offset(head + w * 0.55f, target.top),
            ),
            topLeft = target.topLeft,
            size = target.size,
            cornerRadius = radius,
            style = Stroke(stroke),
        )

        val head2 = target.right + w - phase * travel * 1.6f
        drawRoundRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    ComposeColor(0x005FE08C),
                    ComposeColor(0x995FE08C),
                    ComposeColor(0x005FE08C),
                ),
                start = Offset(head2, target.top),
                end = Offset(head2 - w * 0.45f, target.bottom),
            ),
            topLeft = target.topLeft,
            size = target.size,
            cornerRadius = radius,
            style = Stroke(stroke * 0.7f),
        )
    }
}
