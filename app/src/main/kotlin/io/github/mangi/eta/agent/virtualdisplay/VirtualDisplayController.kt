package io.github.mangi.eta.agent.virtualdisplay

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.display.VirtualDisplayConfig
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Display
import android.view.WindowManager
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.scale
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import io.github.mangi.eta.agent.device.DeviceToolContract
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.terminal.ShellProcessSupervisor
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * 虚拟屏会话与生命周期中枢。
 *
 * 画面通过常驻 [ImageReader] 接收：
 * - 收起态（仅胶囊微缩取景器可见）按 ~6fps 节流更新，兼顾真实动感与低功耗；
 * - 展开态（画廊装裱视窗 / 用户手动接管）提升至 ~30fps 实时更新；
 * - Agent 调用 observe_screen(include_screenshot=true) 时直接提取最新原尺寸 Bitmap，无需等待无障碍截图冷却。
 */
internal object VirtualDisplayController {

    private val lock = Any()
    private val creationLock = Any()
    private val sessions = VirtualDisplaySessionRegistry()

    private fun stateOf(owner: String): VirtualDisplaySessionState = sessions.slot(owner)

    /** 当前持有活屏的 owner（表里第一个活槽，只作兜底判定）。 */
    private fun activeOwner(): String? = sessions.activeOwner()

    /**
     * 预览跟随焦点：焦点屏活着就镜像它；焦点屏没活（或从没设过）时退回任一活屏。
     * 预览只有 [mutableSnapshots] 一份，其它 owner 的帧只进各自的帧状态。
     */
    private fun previewOwnerLocked(): String? =
        sessions.focusedOwner?.takeIf { sessions.activeOf(it) != null } ?: sessions.activeOwner()

    /** 由 displayId 反查 owner；只认活屏，查不到返回 null。 */
    private fun ownerOfDisplayLocked(displayId: Int): String? =
        sessions.activeOwners().firstOrNull { sessions.activeOf(it)?.displayId == displayId }

    private val mutableSnapshots = MutableStateFlow(VirtualDisplaySnapshot.Inactive)
    val snapshots: StateFlow<VirtualDisplaySnapshot> = mutableSnapshots.asStateFlow()

    private val frameSequenceGenerator = AtomicLong(0L)
    private val touchSequenceGenerator = AtomicLong(0L)

    @Volatile
    private var highFrequencyPreview: Boolean = false

    /** 每个 owner 一块屏的底层资源（HandlerThread / ImageReader / Root 桥 / DisplayListener）。 */
    private val resourcesByOwner = HashMap<String, VirtualDisplaySessionResources>()

    /** 每个 owner 最近一帧的原始位图与 UI 节流时间戳；预览只读焦点那一份。 */
    private class FrameState(val reader: ImageReader) {
        var latest: Bitmap? = null
        var lastUiAt: Long = 0L
    }

    private val framesByOwner = HashMap<String, FrameState>()

    /** 音频焦点静音按 owner 记账：释放谁就只还原谁静音过的包。 */
    private val mutedPackagesByOwner = HashMap<String, LinkedHashSet<String>>()

    fun isBackendAvailable(rootAvailable: Boolean, lsposedAvailable: Boolean): Boolean =
        rootAvailable || lsposedAvailable

    fun setStageExpanded(expanded: Boolean) {
        highFrequencyPreview = expanded
    }

    fun setUserControlling(controlling: Boolean) {
        synchronized(lock) {
            val current = mutableSnapshots.value
            if (!current.active || current.userControlling == controlling) return
            mutableSnapshots.value = current.copy(userControlling = controlling)
            if (controlling) highFrequencyPreview = true
        }
    }

    fun currentDisplayId(): Int = synchronized(lock) {
        previewOwnerLocked()?.let { sessions.activeOf(it)?.displayId } ?: Display.DEFAULT_DISPLAY
    }

    /**
     * 切换预览焦点：只把快照切到该 owner 最近一帧，别的屏照常跑。
     * 焦点屏不存在时返回 false，不动当前快照。
     */
    fun focus(owner: String): Boolean {
        if (!sessions.focus(owner)) return false
        synchronized(lock) { publishPreviewLocked(owner) }
        return true
    }

    /**
     * 把 [owner] 那块屏的最近一帧发布成预览快照（只此一份 [mutableSnapshots]）。
     * 没有可用帧时清空 frame，保留该屏的 displayId / 尺寸；屏已不在则置 Inactive。
     */
    private fun publishPreviewLocked(owner: String) {
        val active = sessions.activeOf(owner)
        if (active == null) {
            mutableSnapshots.value = VirtualDisplaySnapshot.Inactive
            return
        }
        val frame = framesByOwner[owner]
        val previous = mutableSnapshots.value
        val sameDisplay = previous.active && previous.displayId == active.displayId
        val preview = frame?.latest?.takeIf { !it.isRecycled }?.let { createUiPreviewBitmap(it).asImageBitmap() }
        val width = frame?.reader?.width?.takeIf { it > 0 } ?: previous.width
        val height = frame?.reader?.height?.takeIf { it > 0 } ?: previous.height
        mutableSnapshots.value = VirtualDisplaySnapshot(
            active = true,
            displayId = active.displayId,
            width = width,
            height = height,
            appName = if (sameDisplay) previous.appName else "",
            packageName = if (sameDisplay) previous.packageName else "",
            userControlling = sameDisplay && previous.userControlling,
            materializingToMain = false,
            frame = preview ?: if (sameDisplay) previous.frame else null,
            frameSequence = if (preview != null) {
                frameSequenceGenerator.incrementAndGet()
            } else {
                previous.frameSequence
            },
            touchEffect = if (sameDisplay) previous.touchEffect else null,
        )
    }

    /**
     * 确保受信任虚拟屏已就绪并返回其 displayId；不可用时返回 null，由调用方平滑回退至物理主屏。
     */
    fun ensureVirtualDisplay(
        context: Context,
        owner: String = VirtualDisplaySessionRegistry.DEFAULT_OWNER,
        rootAvailable: () -> Boolean = { RootAccess.isGranted },
    ): Int? {
        val appContext = context.applicationContext
        val manager = appContext.getSystemService(DisplayManager::class.java) ?: return null
        val state = stateOf(owner)
        // 创建可包含 Binder 与 Root 等待；发布状态和释放不等待这把创建锁。
        synchronized(creationLock) {
            // 上限判定放在创建锁内，避免两个 owner 同时穿过「还能再开一个」的检查。
            if (!sessions.canOpenNew(owner)) {
                // 到上限只回绝这一个请求，调用方自己回退主屏；已在跑的屏不受影响。
                AndroidAgentLogger.warnThrottled("virtual_display_busy") {
                    "Virtual display limit reached: active=${sessions.activeOwners()} " +
                        "requester=$owner max=${VirtualDisplaySessionRegistry.MAX_SESSIONS}"
                }
                return null
            }
            val existing = synchronized(lock) { state.active }
            if (existing != null) {
                val bridge = synchronized(lock) { resourcesByOwner[owner]?.bridge }
                if ((bridge == null || bridge.isAlive) && isDisplayUsable(manager, existing.displayId)) {
                    synchronized(lock) {
                        if (state.isCurrent(existing.token)) return existing.displayId
                    }
                }
                releaseSession(appContext, rootAvailable, existing.token, owner)
            }
            val token = synchronized(lock) { state.begin() }
            val frameWorker = HandlerThread("eta-virtual-display-frame-$owner")
            var reader: ImageReader? = null
            var inProcessDisplay: VirtualDisplay? = null
            var rootBridge: RootVirtualDisplayBridge? = null
            var listener: DisplayManager.DisplayListener? = null
            var accepted = false
            try {
                val (width, height, densityDpi) = resolvePhysicalDisplayMetrics(appContext)
                frameWorker.start()
                val frames = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
                reader = frames
                val handler = Handler(frameWorker.looper)
                frames.setOnImageAvailableListener(::onFrameAvailable, handler)
                if (rootAvailable()) {
                    rootBridge = RootVirtualDisplayBridge.create(
                        appContext, frames.surface, width, height, densityDpi, rootAvailable,
                    ) { process ->
                        val ownsSession = synchronized(lock) {
                            state.isCurrent(token) && resourcesByOwner[owner]?.bridge?.process === process
                        }
                        if (ownsSession) releaseSession(appContext, rootAvailable, token, owner)
                    }
                }
                val id = rootBridge?.displayId ?: run {
                    if (EtaApp.serviceInstance == null) return null
                    inProcessDisplay = tryCreateInProcessVirtualDisplay(
                        appContext, frames.surface, width, height, densityDpi,
                    )
                    inProcessDisplay?.display?.displayId ?: return null
                }
                listener = object : DisplayManager.DisplayListener {
                    override fun onDisplayAdded(displayId: Int) = Unit
                    override fun onDisplayChanged(displayId: Int) {
                        if (displayId == id && !isDisplayUsable(manager, id)) {
                            releaseSession(appContext, rootAvailable, token, owner)
                        }
                    }
                    override fun onDisplayRemoved(displayId: Int) {
                        if (displayId == id) releaseSession(appContext, rootAvailable, token, owner)
                    }
                }
                manager.registerDisplayListener(listener, handler)
                if (!isDisplayUsable(manager, id)) {
                    val display = runCatching { manager.getDisplay(id) }.getOrNull()
                    val flags = display?.flags ?: 0
                    AndroidAgentLogger.warn(
                        "Virtual display rejected: displayId=$id reason=DISPLAY_NOT_USABLE " +
                            "present=${display != null} valid=${display?.isValid == true} " +
                            "flags=0x${Integer.toHexString(flags)} " +
                            "missing=${CanvasRoutingPolicy.missingDisplayFlagsDescription(flags)}",
                    )
                    return null
                }
                synchronized(lock) {
                    if (rootBridge?.isAlive == false || !state.activate(token, id)) return null
                    val replacedResources = resourcesByOwner.put(
                        owner,
                        VirtualDisplaySessionResources(
                            frames, frameWorker, inProcessDisplay, rootBridge, manager, listener,
                        ),
                    )
                    val replacedFrame = framesByOwner.put(owner, FrameState(frames))
                    replacedFrame?.latest?.let { if (!it.isRecycled) it.recycle() }
                    if (replacedResources != null) {
                        thread(name = "eta-vd-cleanup-$owner", isDaemon = true) { replacedResources.close() }
                    }
                    // 只有它本来就是预览焦点时才刷快照；第二块屏不许抢别人的画面。
                    if (previewOwnerLocked() == owner) publishPreviewLocked(owner)
                    accepted = true
                }
                AndroidAgentLogger.info("Virtual display ready: displayId=$id size=${width}x$height")
                return id
            } catch (exception: Exception) {
                AndroidAgentLogger.warn("Virtual display creation failed: type=${exception.safeLogType()}")
                return null
            } finally {
                if (!accepted) {
                    synchronized(lock) { state.invalidate(token) }
                    VirtualDisplaySessionResources(
                        reader, frameWorker, inProcessDisplay, rootBridge, manager, listener,
                    ).close()
                }
            }
        }
    }

    private fun isDisplayUsable(manager: DisplayManager, displayId: Int): Boolean = try {
        val display = manager.getDisplay(displayId)
        display != null && display.isValid && CanvasRoutingPolicy.hasRequiredDisplayFlags(display.flags)
    } catch (exception: Exception) {
        AndroidAgentLogger.warnThrottled("virtual_display_validation_failed") {
            "Virtual display validation failed: type=${exception.safeLogType()}"
        }
        false
    }

    /**
     * 在虚拟屏上启动指定应用，并对其施加后台音频焦点静默保护。
     */
    fun launchAppOnVirtualDisplay(
        context: Context,
        packageName: String,
        appName: String,
        launchIntent: Intent,
        rootAvailable: () -> Boolean = { RootAccess.isGranted },
        checkCancellation: () -> Unit = {},
    ): Int? {
        checkCancellation()
        val displayId = ensureVirtualDisplay(context, rootAvailable = rootAvailable) ?: return null
        checkCancellation()
        val appContext = context.applicationContext
        val sessionOwner = synchronized(lock) { ownerOfDisplayLocked(displayId) }
            ?: VirtualDisplaySessionRegistry.DEFAULT_OWNER
        mutePackageAudioFocusIfPossible(appContext, sessionOwner, packageName, rootAvailable)

        var launchedByRoot = false
        if (rootAvailable()) {
            val userId = DeviceToolContract.appUserId(appContext)
            val component = launchIntent.component?.flattenToShortString()
            if (!component.isNullOrBlank()) {
                val cmd = "am start --user $userId --display $displayId -n ${DeviceToolContract.quote(component)}"
                val exec = BoundedRootCommandExecutor(AndroidAgentLogger, rootAvailable = rootAvailable)
                try {
                    launchedByRoot = exec.execute(cmd, timeoutMillis = 6_000L).exitCode == 0
                } finally {
                    exec.close()
                }
            }
        }

        if (!launchedByRoot) {
            checkCancellation()
            val startedInProcess = runCatching {
                val intent = Intent(launchIntent).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                }
                val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
                appContext.startActivity(intent, options.toBundle())
                true
            }.getOrElse { throwable ->
                AndroidAgentLogger.debug {
                    "Virtual display startActivity in-process fallback: type=${throwable.safeLogType()}"
                }
                false
            }
            if (!startedInProcess) return null
        }

        synchronized(lock) {
            val current = mutableSnapshots.value
            if (current.active && current.displayId == displayId) {
                mutableSnapshots.value = current.copy(
                    appName = appName,
                    packageName = packageName,
                    materializingToMain = false,
                )
            }
        }
        return displayId
    }

    /**
     * 在虚拟屏上打开外部 URI。
     */
    fun openUriOnVirtualDisplay(
        context: Context,
        intent: Intent,
        targetLabel: String,
        rootAvailable: () -> Boolean = { RootAccess.isGranted },
        checkCancellation: () -> Unit = {},
    ): Int? {
        checkCancellation()
        val displayId = ensureVirtualDisplay(context, rootAvailable = rootAvailable) ?: return null
        checkCancellation()
        val appContext = context.applicationContext
        val started = runCatching {
            val launch = Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
            appContext.startActivity(launch, options.toBundle())
            true
        }.getOrDefault(false)

        if (!started && rootAvailable()) {
            checkCancellation()
            val uriString = intent.dataString ?: return null
            val userId = DeviceToolContract.appUserId(appContext)
            val exec = BoundedRootCommandExecutor(AndroidAgentLogger, rootAvailable = rootAvailable)
            val ok = try {
                exec.execute(
                    "am start --user $userId --display $displayId -a android.intent.action.VIEW -d ${DeviceToolContract.quote(uriString)}",
                    timeoutMillis = 6_000L,
                ).exitCode == 0
            } finally {
                exec.close()
            }
            if (!ok) return null
        } else if (!started) {
            return null
        }

        synchronized(lock) {
            val current = mutableSnapshots.value
            if (current.active && current.displayId == displayId) {
                mutableSnapshots.value = current.copy(
                    appName = targetLabel.ifBlank { current.appName },
                    materializingToMain = false,
                )
            }
        }
        return displayId
    }

    /**
     * 将虚拟屏中正在运行的应用无缝破壁移交到物理主屏（DEFAULT_DISPLAY）。
     * 先触发 UI 层的 260ms 全屏放大贴合过渡，同时在底层原位迁移 RootTask。
     */
    fun handoffToMainDisplay(
        context: Context,
        rootAvailable: () -> Boolean = { RootAccess.isGranted },
    ): Boolean {
        val snapshot = synchronized(lock) {
            val current = mutableSnapshots.value
            if (!current.active || !CanvasRoutingPolicy.isVirtualDisplay(current.displayId)) {
                return false
            }
            mutableSnapshots.value = current.copy(
                userControlling = false,
                materializingToMain = true,
            )
            highFrequencyPreview = true
            current
        }

        val appContext = context.applicationContext
        // 破壁只作用于当前这块屏；别的 owner 的会话不受牵连。
        val owner = synchronized(lock) { ownerOfDisplayLocked(snapshot.displayId) }
        if (owner != null) {
            val muted = synchronized(lock) { takeMutedPackagesLocked(owner) }
            restoreMutedPackages(appContext, rootAvailable, muted)
        }

        val moved = moveVirtualTaskToMainDisplay(
            context = appContext,
            displayId = snapshot.displayId,
            packageName = snapshot.packageName,
            rootAvailable = rootAvailable,
        )

        // 留出 260ms 让 VirtualDisplayMaterializeOverlay 完成从画板到物理全屏的弹簧贴合动画
        try {
            Thread.sleep(260L)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        if (owner != null) {
            releaseSession(appContext, rootAvailable, expected = null, owner = owner)
            sessions.closeOwner(owner)
        }
        return moved
    }

    /**
     * 当虚拟屏内部发生跨应用跳转（如从应用 A 跳往应用 B 或拉起授权页）时，同步更新当前虚拟屏承载的包名与应用名。
     */
    fun onTopPackageObserved(
        context: Context,
        observedPackage: String,
        rootAvailable: () -> Boolean = { RootAccess.isGranted },
    ) {
        val pkg = observedPackage.trim()
        if (pkg.isBlank() || pkg == "android" || pkg == "com.android.systemui" || pkg == context.packageName) return
        val current = synchronized(lock) { mutableSnapshots.value }
        if (!current.active || current.packageName == pkg) return
        val appContext = context.applicationContext
        val resolvedName = runCatching {
            val pm = appContext.packageManager
            val info = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(info).toString().trim().takeIf { it.isNotBlank() }
        }.getOrNull() ?: pkg
        val sessionOwner = synchronized(lock) { ownerOfDisplayLocked(current.displayId) }
            ?: VirtualDisplaySessionRegistry.DEFAULT_OWNER
        mutePackageAudioFocusIfPossible(appContext, sessionOwner, pkg, rootAvailable)
        synchronized(lock) {
            val latest = mutableSnapshots.value
            if (latest.active && latest.packageName != pkg) {
                mutableSnapshots.value = latest.copy(
                    packageName = pkg,
                    appName = resolvedName,
                )
            }
        }
    }
    /**
     * 取当前预览焦点屏（[VirtualDisplaySessionRegistry.focusedOwner]，没有就退到任一活屏）的最近一帧。
     * 签名保持不变：observe_screen 等既有调用方不用改。
     */
    fun captureScreenshotBitmap(waitUpToMs: Long = 450L): Bitmap? =
        captureScreenshotForOwner(null, waitUpToMs)

    /** 取指定会话那一块屏的最近一帧；该会话没有活屏时返回 null。 */
    fun captureScreenshotBitmapFor(owner: String, waitUpToMs: Long = 450L): Bitmap? =
        captureScreenshotForOwner(owner, waitUpToMs)

    private fun captureScreenshotForOwner(owner: String?, waitUpToMs: Long): Bitmap? {
        val deadline = SystemClock.elapsedRealtime() + waitUpToMs.coerceAtLeast(0L)
        while (true) {
            synchronized(lock) {
                val target = owner ?: previewOwnerLocked() ?: return null
                if (owner != null && sessions.activeOf(owner) == null) return null
                val bmp = framesByOwner[target]?.latest
                if (bmp != null && !bmp.isRecycled) {
                    return runCatching { bmp.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
                }
            }
            if (SystemClock.elapsedRealtime() >= deadline) return null
            try {
                Thread.sleep(40L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
    }

    fun emitTapEffect(x: Int, y: Int, longPress: Boolean = false) {
        synchronized(lock) {
            val current = mutableSnapshots.value
            if (!current.active || current.width <= 0 || current.height <= 0) return
            val nx = (x.toFloat() / current.width).coerceIn(0f, 1f)
            val ny = (y.toFloat() / current.height).coerceIn(0f, 1f)
            val effect = VirtualTouchEffect.Tap(
                sequence = touchSequenceGenerator.incrementAndGet(),
                createdAtMs = SystemClock.uptimeMillis(),
                normalizedX = nx,
                normalizedY = ny,
                longPress = longPress,
            )
            mutableSnapshots.value = current.copy(touchEffect = effect)
        }
    }

    fun emitSwipeEffect(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 420) {
        synchronized(lock) {
            val current = mutableSnapshots.value
            if (!current.active || current.width <= 0 || current.height <= 0) return
            val effect = VirtualTouchEffect.Swipe(
                sequence = touchSequenceGenerator.incrementAndGet(),
                createdAtMs = SystemClock.uptimeMillis(),
                startX = (x1.toFloat() / current.width).coerceIn(0f, 1f),
                startY = (y1.toFloat() / current.height).coerceIn(0f, 1f),
                endX = (x2.toFloat() / current.width).coerceIn(0f, 1f),
                endY = (y2.toFloat() / current.height).coerceIn(0f, 1f),
                durationMs = durationMs.coerceIn(160, 1_500),
            )
            mutableSnapshots.value = current.copy(touchEffect = effect)
        }
    }

    /**
     * 用户在画廊视窗画布上直接触摸时，将归一化坐标注入虚拟屏。
     */
    fun dispatchUserTapOnCanvas(
        normalizedX: Float,
        normalizedY: Float,
        longPress: Boolean = false,
        rootAvailable: () -> Boolean = { RootAccess.isGranted },
    ) {
        val snapshot = mutableSnapshots.value
        if (!snapshot.active || !CanvasRoutingPolicy.isVirtualDisplay(snapshot.displayId)) return
        val x = (normalizedX.coerceIn(0f, 1f) * (snapshot.width - 1)).roundToInt()
        val y = (normalizedY.coerceIn(0f, 1f) * (snapshot.height - 1)).roundToInt()
        emitTapEffect(x, y, longPress = longPress)
        thread(name = "eta-vd-user-tap", isDaemon = true) {
            val duration = if (longPress) 650L else 55L
            val dispatched = AgentAccessibilityService.current()
                ?.gestureTap(x.toFloat(), y.toFloat(), durationMs = duration, displayId = snapshot.displayId)
                ?.ok == true
            if (!dispatched && rootAvailable()) {
                val exec = BoundedRootCommandExecutor(AndroidAgentLogger, rootAvailable = rootAvailable)
                try {
                    val cmd = if (longPress) {
                        "input -d ${snapshot.displayId} swipe $x $y $x $y 650"
                    } else {
                        "input -d ${snapshot.displayId} tap $x $y"
                    }
                    exec.execute(cmd, timeoutMillis = 5_000L)
                } finally {
                    exec.close()
                }
            }
        }
    }

    fun dispatchUserSwipeOnCanvas(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Int = 320,
        rootAvailable: () -> Boolean = { RootAccess.isGranted },
    ) {
        val snapshot = mutableSnapshots.value
        if (!snapshot.active || !CanvasRoutingPolicy.isVirtualDisplay(snapshot.displayId)) return
        val x1 = (startX.coerceIn(0f, 1f) * (snapshot.width - 1)).roundToInt()
        val y1 = (startY.coerceIn(0f, 1f) * (snapshot.height - 1)).roundToInt()
        val x2 = (endX.coerceIn(0f, 1f) * (snapshot.width - 1)).roundToInt()
        val y2 = (endY.coerceIn(0f, 1f) * (snapshot.height - 1)).roundToInt()
        val safeDuration = durationMs.coerceIn(140, 1_000)
        emitSwipeEffect(x1, y1, x2, y2, safeDuration)
        thread(name = "eta-vd-user-swipe", isDaemon = true) {
            val dispatched = AgentAccessibilityService.current()
                ?.gestureSwipe(
                    x1.toFloat(),
                    y1.toFloat(),
                    x2.toFloat(),
                    y2.toFloat(),
                    durationMs = safeDuration.toLong(),
                    displayId = snapshot.displayId,
                )?.ok == true
            if (!dispatched && rootAvailable()) {
                val exec = BoundedRootCommandExecutor(AndroidAgentLogger, rootAvailable = rootAvailable)
                try {
                    exec.execute(
                        "input -d ${snapshot.displayId} swipe $x1 $y1 $x2 $y2 $safeDuration",
                        timeoutMillis = 5_000L,
                    )
                } finally {
                    exec.close()
                }
            }
        }
    }

    /** 释放全部 owner（对外语义不变：停服务、收工时一块屏都不留）。 */
    fun release(
        context: Context? = null,
        rootAvailable: () -> Boolean = { RootAccess.isGranted },
    ) {
        val owners = synchronized(lock) {
            val table = LinkedHashSet<String>()
            table.addAll(resourcesByOwner.keys)
            table.addAll(framesByOwner.keys)
            table.addAll(sessions.activeOwners())
            table
        }
        if (owners.isEmpty() && !mutableSnapshots.value.active) return
        for (owner in owners) {
            releaseSession(context, rootAvailable, expected = null, owner = owner)
        }
        synchronized(lock) {
            if (mutableSnapshots.value.active) mutableSnapshots.value = VirtualDisplaySnapshot.Inactive
            highFrequencyPreview = false
        }
    }

    /**
     * 只关掉某个 owner 的虚拟屏：资源、帧缓存、它自己静音过的包，别家一点不动。
     */
    fun releaseOwner(
        owner: String,
        context: Context? = null,
        rootAvailable: () -> Boolean = { RootAccess.isGranted },
    ) {
        releaseSession(context, rootAvailable, expected = null, owner = owner)
        sessions.closeOwner(owner)
        synchronized(lock) {
            val next = previewOwnerLocked()
            if (next == null) {
                mutableSnapshots.value = VirtualDisplaySnapshot.Inactive
            } else {
                publishPreviewLocked(next)
            }
        }
    }

    /**
     * 释放单个 owner：[expected] 不为空时必须仍是同一个 token；只摘这个 owner 的资源与帧状态。
     * 只有在它正处于预览焦点时，快照才切到下一个活屏（没有就 Inactive）。
     */
    private fun releaseSession(
        context: Context?,
        rootAvailable: () -> Boolean,
        expected: VirtualDisplaySessionState.Token? = null,
        owner: String,
    ) {
        var detached: VirtualDisplaySessionResources? = null
        var frameBitmap: Bitmap? = null
        var mutedRestore: List<String> = emptyList()
        synchronized(lock) {
            val active = sessions.activeOf(owner)
            val resources = resourcesByOwner[owner]
            val frames = framesByOwner[owner]
            if (active == null && resources == null && frames == null) return
            val state = sessions.slot(owner)
            if (expected != null && !state.isCurrent(expected)) return
            val wasPreview = previewOwnerLocked() == owner
            state.invalidate()
            detached = resourcesByOwner.remove(owner)
            frameBitmap = framesByOwner.remove(owner)?.latest
            mutedRestore = takeMutedPackagesLocked(owner)
            if (wasPreview) {
                val next = previewOwnerLocked()
                if (next == null) {
                    mutableSnapshots.value = VirtualDisplaySnapshot.Inactive
                    highFrequencyPreview = false
                } else {
                    publishPreviewLocked(next)
                }
            }
        }
        val bitmapToRecycle = frameBitmap
        if (bitmapToRecycle != null && !bitmapToRecycle.isRecycled) bitmapToRecycle.recycle()
        val resourcesToClose = detached
        if (resourcesToClose != null) {
            thread(name = "eta-vd-cleanup-$owner", isDaemon = true) { resourcesToClose.close() }
        }
        if (context != null && mutedRestore.isNotEmpty()) {
            restoreMutedPackages(context.applicationContext, rootAvailable, mutedRestore)
        }
    }

    /**
     * 帧回调按 [reader] 身份反查 owner：找不到就丢弃这一帧（别的会话的旧 reader 迟到帧）。
     * 只有预览焦点那一块屏刷 UI，其它 owner 的帧只更新自己的最近帧供截图。
     */
    private fun onFrameAvailable(reader: ImageReader) {
        val image = try {
            reader.acquireLatestImage()
        } catch (_: Throwable) {
            null
        } ?: return

        try {
            val now = SystemClock.elapsedRealtime()
            val minIntervalMs = if (highFrequencyPreview) 30L else 160L

            val bitmap = decodeImageToBitmap(image) ?: return
            var oldBitmap: Bitmap? = null
            synchronized(lock) {
                val owner = framesByOwner.entries.firstOrNull { it.value.reader === reader }?.key
                val frame = owner?.let { framesByOwner[it] }
                if (owner == null || frame == null) {
                    bitmap.recycle()
                    return
                }
                val active = sessions.activeOf(owner)
                if (active == null || resourcesByOwner[owner]?.reader !== reader) {
                    bitmap.recycle()
                    return
                }
                val shouldUpdateUi = previewOwnerLocked() == owner &&
                    (now - frame.lastUiAt >= minIntervalMs || frame.latest == null)
                oldBitmap = frame.latest
                frame.latest = bitmap
                if (shouldUpdateUi) {
                    frame.lastUiAt = now
                    val current = mutableSnapshots.value
                    if (current.active && current.displayId == active.displayId) {
                        mutableSnapshots.value = current.copy(
                            frame = createUiPreviewBitmap(bitmap).asImageBitmap(),
                            frameSequence = frameSequenceGenerator.incrementAndGet(),
                        )
                    } else {
                        publishPreviewLocked(owner)
                    }
                }
            }
            oldBitmap?.let { old ->
                if (!old.isRecycled) old.recycle()
            }
        } catch (_: Throwable) {
            // 单帧解码异常不影响后续帧流
        } finally {
            runCatching { image.close() }
        }
    }

    private fun decodeImageToBitmap(image: Image): Bitmap? {
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) return null

        // 优先直接通过 HardwareBuffer 映射，避免不同 ROM Gralloc 行尾 padding 差异导致的 BufferUnderflowException
        runCatching {
            image.hardwareBuffer?.use { hb ->
                val wrapped = Bitmap.wrapHardwareBuffer(hb, ColorSpace.get(ColorSpace.Named.SRGB))
                if (wrapped != null) {
                    try {
                        return wrapped.copy(Bitmap.Config.ARGB_8888, false)
                    } finally {
                        if (!wrapped.isRecycled) wrapped.recycle()
                    }
                }
            }
        }

        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer ?: return null
        buffer.rewind()
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        if (pixelStride <= 0 || rowStride <= 0) return null
        val rowPadding = rowStride - pixelStride * width
        val paddedWidth = width + rowPadding / pixelStride
        if (paddedWidth < width) return null

        val requiredBytes = paddedWidth * height * 4
        val safeHeight = if (buffer.remaining() >= requiredBytes) {
            height
        } else {
            (buffer.remaining() / (paddedWidth * 4)).coerceAtMost(height)
        }
        if (safeHeight <= 0) return null

        val rawBitmap = Bitmap.createBitmap(paddedWidth, safeHeight, Bitmap.Config.ARGB_8888)
        rawBitmap.copyPixelsFromBuffer(buffer)
        return if (paddedWidth == width && safeHeight == height) {
            rawBitmap
        } else {
            val cropped = Bitmap.createBitmap(rawBitmap, 0, 0, width, safeHeight)
            rawBitmap.recycle()
            cropped
        }
    }

    private fun createUiPreviewBitmap(source: Bitmap): Bitmap {
        val maxTargetHeight = if (highFrequencyPreview) 1080 else 480
        if (source.height <= maxTargetHeight) {
            return source.copy(Bitmap.Config.ARGB_8888, false)
        }
        val ratio = maxTargetHeight.toFloat() / source.height.toFloat()
        val targetW = (source.width * ratio).roundToInt().coerceAtLeast(1)
        val targetH = maxTargetHeight
        return source.scale(targetW, targetH)
    }

    @android.annotation.SuppressLint("WrongConstant")
    private fun tryCreateInProcessVirtualDisplay(
        appContext: Context,
        surface: android.view.Surface,
        width: Int,
        height: Int,
        densityDpi: Int,
    ): VirtualDisplay? = runCatching {
        runCatching { HiddenApiBypass.addHiddenApiExemptions("Landroid/hardware/display/") }
        val dm = appContext.getSystemService(DisplayManager::class.java) ?: return@runCatching null
        val config = VirtualDisplayConfig.Builder(
            CanvasRoutingPolicy.VIRTUAL_DISPLAY_NAME,
            width,
            height,
            densityDpi,
        )
            .setSurface(surface)
            .setFlags(CanvasRoutingPolicy.TRUSTED_INTERACTIVE_FLAGS)
            .build()
        dm.createVirtualDisplay(config)
    }.getOrNull()

    private fun moveVirtualTaskToMainDisplay(
        context: Context,
        displayId: Int,
        packageName: String,
        rootAvailable: () -> Boolean,
    ): Boolean {
        // 1. 尝试进程内通过 system_server Hook 放行的 moveRootTaskToDisplay 迁移
        val movedInProcess = runCatching {
            HiddenApiBypass.addHiddenApiExemptions("Landroid/app/ActivityTaskManager", "Landroid/app/IActivityTaskManager")
            val atmClass = Class.forName("android.app.ActivityTaskManager")
            val service = atmClass.getMethod("getService").invoke(null) ?: return@runCatching false
            val getTasks = service.javaClass.methods.firstOrNull { it.name == "getTasks" && it.parameterCount >= 1 }
            val tasks = when (getTasks?.parameterCount) {
                1 -> getTasks.invoke(service, 40)
                3 -> getTasks.invoke(service, 40, false, false)
                4 -> getTasks.invoke(service, 40, false, false, Display.INVALID_DISPLAY)
                else -> null
            } as? List<*> ?: emptyList<Any?>()
            var targetTaskId = -1
            for (item in tasks) {
                val info = item as? android.app.TaskInfo ?: continue
                val taskDisplay = runCatching {
                    android.app.TaskInfo::class.java.getField("displayId").getInt(info)
                }.getOrDefault(Display.INVALID_DISPLAY)
                val topPkg = info.topActivity?.packageName ?: info.baseActivity?.packageName
                if (taskDisplay == displayId || (packageName.isNotEmpty() && topPkg == packageName)) {
                    targetTaskId = info.taskId
                    if (taskDisplay == displayId) break
                }
            }
            if (targetTaskId > 0) {
                val moveMethod = service.javaClass.getMethod(
                    "moveRootTaskToDisplay",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                )
                moveMethod.invoke(service, targetTaskId, Display.DEFAULT_DISPLAY)
                true
            } else {
                false
            }
        }.getOrDefault(false)

        if (movedInProcess) return true

        // 2. Root 特权桥调用 moveRootTaskToDisplay
        if (rootAvailable()) {
            val apkPath = context.applicationInfo.sourceDir
            val cmd = "CLASSPATH=${DeviceToolContract.quote(apkPath)} app_process /system/bin " +
                "io.github.mangi.eta.agent.virtualdisplay.VirtualDisplayCommandMain"
            val req = JSONObject()
                .put("action", "move_to_main")
                .put("display_id", displayId)
                .put("package_name", packageName)
                .toString() + "\n"
            val supervisor = ShellProcessSupervisor(rootAvailable = rootAvailable)
            val proc = supervisor.startShellProcess("root", cmd, mergeStderr = true)
            if (proc != null) {
                val movedByRoot = try {
                    proc.outputStream.write(req.toByteArray(Charsets.UTF_8))
                    proc.outputStream.flush()
                    proc.outputStream.close()
                    val out = proc.inputStream.bufferedReader().readText()
                    proc.waitFor(4, TimeUnit.SECONDS)
                    out.contains("\"ok\":true")
                } catch (_: Throwable) {
                    false
                } finally {
                    supervisor.beginClosing()
                    if (proc.isAlive) supervisor.terminateAndReap(proc) else supervisor.reapProcess(proc)
                    supervisor.unregisterProcess(proc)
                }
                if (movedByRoot) return true
            }
        }

        // 3. 兜底：以 SINGLE_TOP + NEW_TASK 在 DEFAULT_DISPLAY 唤起该应用
        if (packageName.isNotBlank()) {
            return runCatching {
                val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    ?: return@runCatching false
                val options = ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY)
                context.startActivity(intent, options.toBundle())
                true
            }.getOrDefault(false)
        }
        return false
    }

    private fun mutePackageAudioFocusIfPossible(
        context: Context,
        owner: String,
        packageName: String,
        rootAvailable: () -> Boolean,
    ) {
        if (!rootAvailable() || !DeviceToolContract.validPackageName(packageName)) return
        val added = synchronized(lock) {
            mutedPackagesByOwner.getOrPut(owner) { LinkedHashSet() }.add(packageName)
        }
        if (!added) return
        val userId = DeviceToolContract.appUserId(context)
        thread(name = "eta-vd-audio-shield", isDaemon = true) {
            val exec = BoundedRootCommandExecutor(AndroidAgentLogger, rootAvailable = rootAvailable)
            try {
                exec.execute(
                    "cmd appops set --user $userId ${DeviceToolContract.quote(packageName)} TAKE_AUDIO_FOCUS ignore",
                    timeoutMillis = 3_000L,
                )
            } catch (_: Throwable) {
                // 忽略部分 ROM appops 差异
            } finally {
                exec.close()
            }
        }
    }

    /** 调用方持有 [lock]：把某个 owner 的静音账本整本摘走，返回内容供异步还原。 */
    private fun takeMutedPackagesLocked(owner: String): List<String> =
        mutedPackagesByOwner.remove(owner)?.toList().orEmpty()

    private fun restoreMutedPackages(
        context: Context,
        rootAvailable: () -> Boolean,
        packages: List<String>,
    ) {
        if (packages.isEmpty() || !rootAvailable()) return
        val userId = DeviceToolContract.appUserId(context)
        thread(name = "eta-vd-audio-restore", isDaemon = true) {
            val exec = BoundedRootCommandExecutor(AndroidAgentLogger, rootAvailable = rootAvailable)
            try {
                for (pkg in packages) {
                    if (!DeviceToolContract.validPackageName(pkg)) continue
                    exec.execute(
                        "cmd appops set --user $userId ${DeviceToolContract.quote(pkg)} TAKE_AUDIO_FOCUS allow",
                        timeoutMillis = 3_000L,
                    )
                }
            } catch (_: Throwable) {
            } finally {
                exec.close()
            }
        }
    }

    private fun resolvePhysicalDisplayMetrics(context: Context): Triple<Int, Int, Int> {
        val dm = context.resources.displayMetrics
        var width = dm.widthPixels.coerceAtLeast(720)
        var height = dm.heightPixels.coerceAtLeast(1280)
        val density = dm.densityDpi.coerceIn(160, 640)
        runCatching {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            val point = Point()
            @Suppress("DEPRECATION")
            wm?.defaultDisplay?.getRealSize(point)
            if (point.x > 0 && point.y > 0) {
                width = point.x
                height = point.y
            }
        }
        return Triple(width, height, density)
    }
}
