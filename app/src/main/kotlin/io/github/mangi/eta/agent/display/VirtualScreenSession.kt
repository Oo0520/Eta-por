package io.github.mangi.eta.agent.display

import android.app.KeyguardManager
import android.content.Context
import android.util.Base64
import android.os.SystemClock
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.display.dashboard.AgentInjectionGate
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentExecutionService
import io.github.mangi.eta.data.datastore.SettingsDataStore
import java.io.BufferedReader
import java.io.BufferedWriter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * 灵屏会话表：**每个会话（owner）一块屏**，可以多开。
 *
 * - [sessions] 以 owner（会话 id）为键；同一个会话跨 run 复用同一块屏；
 * - 并发上限 [MAX_SESSIONS]，超限时先回收别的会话里空闲（无 run 租约）的屏；
 * - 工具全部按 owner 路由，A 会话的操作不会落到 B 的屏上；
 * - [state] 只镜像「焦点屏」的视图状态，供悬浮窗/查看页显示；切焦点时把该屏的快照推出去。
 *
 * 宿主面（直通出帧）按 owner 分槽：焦点屏拿得到 Surface 就走直通，其余屏自动回落到
 * 整帧 JPEG 管线，画面不会互相抢占。
 */
internal object VirtualScreenSession {
    private val lock = Any()

    /**
     * 手机是否处于锁屏（亮屏锁屏也算）。
     *
     * helper 是裸 app_process，Android 17 上它自己问不到 KeyguardManager（构造会碰
     * WindowManagerGlobal → ApplicationSharedMemory），所以由主进程算好随 create / lifecycle 下发。
     */
    internal fun mainScreenLocked(context: Context): Boolean =
        runCatching { context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true }
            .getOrDefault(false)

    private val deadlines = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "eta-display-deadline").apply { isDaemon = true }
    }

    /** owner → 屏。LinkedHashMap 保持创建顺序，回收时先尝试最早的。 */
    private val sessions = LinkedHashMap<String, Session>()

    /** 焦点屏镜像：浮窗与查看页只订阅它。 */
    private val viewerState = MutableStateFlow(VirtualScreenViewerState())
    val state = viewerState.asStateFlow()

    @Volatile
    private var focusedOwner: String? = null

    /**
     * 灵屏数量不再设数字上限，改由内存把关。
     *
     * 每块屏常驻约 80MB（1440x3168 全尺寸采集缓冲 + 复用位图），
     * 硬性限个数没有意义：内存够就多开，不够就拒绝，判据用 [hasMemoryForNewSession]。
     */
    private const val MIN_FREE_MB_FOR_NEW_SESSION = 1200L

    /** 剩余内存够不够再开一块屏；读不到 meminfo 时按「够」处理，不拦正常流程。 */
    private fun hasMemoryForNewSession(): Boolean = runCatching {
        java.io.File("/proc/meminfo").useLines { lines ->
            lines.firstOrNull { it.startsWith("MemAvailable:") }
                ?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull()
                ?.let { it / 1024 >= MIN_FREE_MB_FOR_NEW_SESSION } ?: true
        }
    }.getOrDefault(true)

    private val gestureIds = AtomicLong()

    /** 编码长边上限：q100 下的性能红线（见 create 里的注释）。 */
    private const val MAX_ENCODE_LONG_SIDE = 1760

    /**
     * 出帧档位。只在没走直通（JPEG 回落管线）时生效；直通建立后由合成器直出，与档位无关。
     *
     * - [FLOAT]：浮窗展开可见，25fps / q80，跟手优先；
     * - [FLOAT_COLLAPSED]：浮窗贴边收起成气泡或渐变条，画面已隐藏，5fps 只为偶尔瞄一眼；
     * - [TAKEOVER]：接管页在前台，帧间隔对齐真机屏幕刷新率，质量 q90。
     */
    internal enum class WatchProfile(val intervalMs: Int, val quality: Int, val label: String) {
        /** 浮窗展开：25fps。 */
        FLOAT(40, 80, "浮窗"),

        /** 浮窗收起：5fps。 */
        FLOAT_COLLAPSED(200, 80, "浮窗收起"),

        /** 接管页：intervalMs 留 0，下发时按真机刷新率换算。 */
        TAKEOVER(0, 90, "接管"),
    }

    /**
     * 读屏档（无人在看）：5fps / q80，与浮窗收起同档。
     * 区别只在 helper 侧：读屏档还要满足「有客户端在取帧」才编码，纯挂机时不烧 CPU。
     */
    private const val READ_SCREEN_INTERVAL_MS = 200
    private const val READ_SCREEN_QUALITY = 80

    /** 灵屏合成刷新率上限：再高对「Agent 读屏 + 人工接管」没有收益，只多耗电。 */
    private const val MAX_TARGET_HZ = 90

    /** 可用的刷新率档位，从高到低匹配设备能力。 */
    private val REFRESH_TIERS = listOf(MAX_TARGET_HZ, 60)

    /** 各来源当前申请的档位（float / takeover），全部释放后回落到读屏档。 */
    private val watchProfiles = LinkedHashMap<String, WatchProfile>()

    /** 合并后的当前档位；null = 读屏档（自适应 1–3fps + 用户设置的质量）。 */
    @Volatile
    private var watchProfile: WatchProfile? = null

    /** 最近一次 execute 的 App 上下文：给刷新率查询用，避免为了一个数字去抢 [lock]。 */
    @Volatile
    private var appContext: android.content.Context? = null

    /**
     * 屏幕刷新率档位：取「设备主屏支持的最高刷新率」与 [MAX_TARGET_HZ] 的较小值，
     * 对齐到 60 / 90 / 120 三档。
     *
     * 绝不能无条件报 120：设备只有 60Hz 时虚屏声明 120Hz，App 会按 8.3ms 排帧、
     * 实际 vsync 只有 16.6ms，动画反而比自己声明 60Hz 更卡。
     */
    private fun targetRefreshHz(): Int {
        val maxHz = runCatching {
            val ctx = appContext ?: return 60
            val dm = ctx.getSystemService(android.hardware.display.DisplayManager::class.java)
                ?: return 60
            val display = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY) ?: return 60
            display.supportedModes.maxOfOrNull { it.refreshRate } ?: display.refreshRate
        }.getOrDefault(60f)
        return REFRESH_TIERS.firstOrNull { it <= maxHz + 0.5f } ?: 60
    }

    /** 接管档据此对齐屏刷新率；与 [targetRefreshHz] 同源，不再单独读 display.refreshRate。 */
    private fun mainRefreshHz(): Float = targetRefreshHz().toFloat()

    private val operationIds = AtomicLong()

    /** 会被写入灵屏的输入类动作；接管态下一律要过注入门。 */
    private val AGENT_INPUT_ACTIONS = setOf("tap", "swipe", "touch", "long_press", "text", "key", "back")

    private val cleanup = Executors.newSingleThreadExecutor { r ->
        Thread(r, "eta-display-cleanup").apply { isDaemon = true }
    }

    private class Session(
        val context: Context,
        val owner: String,
        val process: java.lang.Process,
        val input: BufferedWriter,
        val output: BufferedReader,
        var width: Int,
        var height: Int,
        val allowOff: Boolean,
        runId: String?,
    ) {
        val id = UUID.randomUUID().toString()
        var displayId: Int = -1
        val closed = AtomicBoolean()
        val runLease = VirtualScreenRunLease(runId)

        /** 这块屏自己的视图状态；只有它是焦点时才抄到外层的 [viewerState]。 */
        val viewer = MutableStateFlow(
            VirtualScreenViewerState(
                taskPhase = if (runId == null) VirtualScreenTaskPhase.IDLE else VirtualScreenTaskPhase.RUNNING,
                activeRunId = runId,
            ),
        )

        fun close() {
            if (!closed.compareAndSet(false, true)) return
            synchronized(lock) {
                if (sessions[owner] === this) sessions.remove(owner)
                if (focusedOwner == owner) {
                    focusedOwner = sessions.keys.lastOrNull()
                }
                // 无条件重算：早前只在「自己是 focused」时更新，导致非 focus 的会话关闭后
                // 对外状态仍指向已销毁的 display，悬浮窗就定格在最后一帧（实测卡死）。
                viewerState.value = focusedOwner?.let { sessions[it]?.viewer?.value } ?: VirtualScreenViewerState()
            }
            viewer.update { it.copy(display = null, gesture = null, activeRunId = null) }
            VirtualScreenNotification.cancel(context, id)
            AgentExecutionService.release("virtual-screen-$id")
            // 不等待 execute 持有的锁或正在读取的帧；主线程取消必须立即返回。
            process.destroy()
            cleanup.execute {
                runCatching { process.outputStream.close() }
                runCatching { input.close() }
                runCatching { output.close() }
            }
        }
    }

    /** 把某块屏的状态抄到外层镜像（仅当它是焦点）。 */
    private fun publish(session: Session) {
        if (focusedOwner == session.owner) viewerState.value = session.viewer.value
    }

    /** 切焦点：浮窗/查看页立刻换成这块屏。 */
    private fun focus(session: Session) {
        if (focusedOwner == session.owner) return
        VscrDiag.line("灵屏焦点 " + (focusedOwner?.take(8) ?: "无") + " → " + session.owner.take(8))
        focusedOwner = session.owner
        viewerState.value = session.viewer.value
        VirtualScreenHostSurfaces.setFocusOwner(session.owner)
    }

    fun isActive(): Boolean = synchronized(lock) {
        sessions.values.any { !it.closed.get() && it.process.isAlive }
    }

    fun isActive(owner: String): Boolean = synchronized(lock) {
        sessions[owner]?.let { !it.closed.get() && it.process.isAlive } == true
    }

    private fun mainScreenWidth(context: android.content.Context): Int =
        context.resources.displayMetrics.widthPixels

    private fun mainScreenHeight(context: android.content.Context): Int =
        context.resources.displayMetrics.heightPixels

    private fun mainScreenDensity(context: android.content.Context): Int =
        context.resources.displayMetrics.densityDpi

    /** 当前是否有任何一块灵屏活着（用于让 UI 工具自动跟随路由）。 */
    fun hasActiveSession(): Boolean = synchronized(lock) {
        sessions.values.any { !it.closed.get() && it.process.isAlive }
    }

    fun isOwnedBy(owner: String): Boolean = isActive(owner)

    fun currentOwner(): String? = synchronized(lock) { focusedOwner }

    fun activeOwners(): List<String> = synchronized(lock) {
        sessions.values.filter { !it.closed.get() && it.process.isAlive }.map { it.owner }
    }

    private fun aliveSession(owner: String): Session? = sessions[owner]?.takeIf {
        !it.closed.get() && it.process.isAlive
    }

    /**
     * 进入工具前准备：本会话已有屏就走租约；没有屏则在上限内允许新建。
     * 别的会话的屏不再挡住本会话（多开），只有到上限时才尝试回收对方空闲的屏。
     */
    fun prepareForRun(owner: String, runId: String): Boolean = synchronized(lock) {
        sessions[owner]?.takeIf { it.closed.get() || !it.process.isAlive }?.let { stale ->
            sessions.remove(owner)
            stale.close()
        }

        val current = aliveSession(owner)
        if (current != null) {
            val acquired = current.runLease.acquire(runId)
            if (acquired) {
                // 只有当前没有可用的聚焦会话时才切画面。
                // 早前这里无条件 focus：B 会话一开跑就把 A 的浮窗画面顶掉，
                // 用户看到的是 B 的屏、A 的操作却在 A 的屏生效 —— 就是「串台」。
                val focused = focusedOwner?.let { sessions[it] }
                if (focused == null || focused.closed.get()) focus(current)
                if (current.viewer.value.activeRunId != runId) {
                    current.viewer.update { it.beginRun(runId) }
                    publish(current)
                    updateIdleTimeout(owner)
                }
            }
            return@synchronized acquired
        }
        // 数量不设限，只看内存：够就再开一块，不够就明确告诉调用方，而不是悄悄关掉别人的屏。
        if (hasMemoryForNewSession()) return@synchronized true
        VscrDiag.line(
            "灵屏 内存不足，拒绝新建 现有=" + sessions.size +
                " 可用=" + runCatching {
                    java.io.File("/proc/meminfo").useLines { l ->
                        l.firstOrNull { it.startsWith("MemAvailable:") }?.split(Regex("\\s+"))?.getOrNull(1)
                    }
                }.getOrNull() + "kB"
        )
        val holder = sessions.values.firstOrNull { it.owner != owner }
        VscrDiag.line(
            "灵屏占用 busy holder=" + (holder?.owner?.take(8) ?: "无") + " 请求=" + owner.take(8)
        )
        false
    }

    fun releaseRun(owner: String, runId: String, retain: Boolean, cancelled: Boolean = false) = synchronized(lock) {
        val current = sessions[owner] ?: return@synchronized
        if (!current.runLease.release(runId, retain)) return@synchronized
        current.viewer.update { it.finishRun(runId, retain, cancelled) }
        publish(current)
        if (!retain) current.close() else updateIdleTimeout(owner)
    }

    fun updateIdleTimeout(owner: String? = null) = synchronized(lock) {
        val targets = if (owner != null) listOfNotNull(sessions[owner]) else sessions.values.toList()
        if (targets.isEmpty()) return@synchronized
        val settings = runBlocking { SettingsDataStore.settings() }
        val profile = watchProfile
        val interval = when {
            profile == null -> READ_SCREEN_INTERVAL_MS
            profile == WatchProfile.TAKEOVER ->
                (1000f / mainRefreshHz()).toInt().coerceIn(4, 200)
            else -> profile.intervalMs
        }
        val quality = profile?.quality ?: READ_SCREEN_QUALITY
        targets.forEach { current ->
            execute(
                current.context,
                current.owner,
                JSONObject().put("action", "lifecycle")
                    .put("idleTimeoutMinutes", settings.virtualScreenIdleTimeoutMinutes)
                    .put("activeRun", current.viewer.value.activeRunId != null)
                    .put("mainScreenLocked", mainScreenLocked(current.context))
                    .put("frameIntervalMs", interval)
                    .put("jpegQuality", quality)
                    .put("watchMode", profile != null),
            )
        }
        Unit
    }

    /**
     * 申请/释放某个来源的出帧档位。多来源各占一个名额，取最激进的那个生效：
     * 接管页开着时浮窗即使收起也不会把帧率拖到 5fps。
     * 传 null 表示该来源释放。无会话时只记档位，下次建屏随 create 生效。
     */
    fun setWatchProfile(source: String, profile: WatchProfile?): Boolean {
        val snapshot: List<WatchProfile>
        val detail: String
        synchronized(watchProfiles) {
            if (profile == null) watchProfiles.remove(source) else watchProfiles[source] = profile
            snapshot = watchProfiles.values.toList()
            detail = watchProfiles.entries.joinToString(",") { it.key + "=" + it.value.label }
        }
        // 最激进 = 间隔最小（接管档按真机刷新率算）。放在锁外：resolveInterval 可能去问 DisplayManager。
        val merged = snapshot.minByOrNull { resolveInterval(it) }
        if (watchProfile == merged) return false
        watchProfile = merged
        VscrDiag.line(
            "出帧档位=" + (merged?.label ?: "读屏") +
                (if (merged == null) "" else " interval=" + resolveInterval(merged) + "ms q=" + merged.quality) +
                " 来源=[" + detail + "]"
        )
        runCatching { updateIdleTimeout() }
        return true
    }

    private fun resolveInterval(profile: WatchProfile): Int =
        if (profile == WatchProfile.TAKEOVER) (1000f / mainRefreshHz()).toInt().coerceIn(4, 200)
        else profile.intervalMs

    fun currentWatchProfile(): WatchProfile? = watchProfile

    /** 当前生效的出帧间隔（ms）。浮窗的拉帧节奏与 helper 出帧共用同一口径。 */
    fun currentFrameIntervalMs(): Int =
        watchProfile?.let { resolveInterval(it) } ?: READ_SCREEN_INTERVAL_MS

    fun recordOperation(owner: String, runId: String?, name: String, success: Boolean, manual: Boolean = false) {
        val current = synchronized(lock) { sessions[owner]?.takeIf { !it.closed.get() } } ?: return
        val operation = VirtualScreenOperation(operationIds.incrementAndGet(), name, System.currentTimeMillis(), success, manual)
        current.viewer.update {
            if (runId != null && it.activeRunId != runId) it else it.record(operation)
        }
        publish(current)
    }

    fun execute(
        context: Context,
        owner: String,
        args: JSONObject,
        isCancelled: () -> Boolean = { false },
        runId: String? = null,
        appRestartApproved: Boolean = false,
        /** true = 用户在悬浮窗/查看页里的手动输入；不参与注入门控。 */
        manual: Boolean = false,
    ): AgentModelClient.ToolResult = synchronized(lock) {
        try {
            if (appContext == null) appContext = context.applicationContext
            check(!isCancelled()) { "DISPLAY_CANCELLED" }
            require(android.os.Build.VERSION.SDK_INT >= 34) { "DEVICE_UNSUPPORTED" }
            val settings = runBlocking { SettingsDataStore.settings() }
            require(settings.virtualScreenEnabled) { "VIRTUAL_SCREEN_DISABLED" }
            if (!RootAccess.isGranted) {
                VscrDiag.line("RootAccess 未授权，主动申请一次")
                runBlocking { runCatching { RootAccess.request(context).join() } }
                VscrDiag.line("RootAccess 申请结果 granted=" + RootAccess.isGranted)
            }
            require(RootAccess.isGranted) { "ROOT_REQUIRED" }
            val action = args.getString("action")
            // 用户接管期间，模型不许再往屏里塞手势/按键；用户自己的输入（manual）不受影响。
            if (!manual && action in AGENT_INPUT_ACTIONS) {
                require(AgentInjectionGate.isOpen()) { "USER_TAKEOVER" }
            }
            if (action == "launch" && args.optBoolean("restartApp")) {
                require(appRestartApproved || settings.virtualScreenAutoRestartApps) { "APP_RESTART_PERMISSION_REQUIRED" }
            }
            if (action == "create") {
                require(owner.isNotBlank()) { "DISPLAY_OWNER_REQUIRED" }
                sessions[owner]?.takeIf { it.closed.get() || !it.process.isAlive }?.let { stale ->
                    sessions.remove(owner)
                    stale.close()
                }
                require(sessions[owner] == null) { "DISPLAY_ALREADY_ACTIVE" }
                if (!hasMemoryForNewSession()) {
                    VscrDiag.line("灵屏 内存不足，拒绝建屏 现有=" + sessions.size)
                    error("INSUFFICIENT_MEMORY")
                }
                val allowOff = args.optBoolean("allowScreenOff", false)
                require(!allowOff || settings.virtualScreenOffEnabled) { "SCREEN_OFF_PERMISSION_REQUIRED" }
                val profile = VirtualScreenProfile.from(context)
                var width = bounded(args, "width", 320, VirtualScreenProfile.MAX_WIDTH, profile.width)
                var height = bounded(args, "height", 480, VirtualScreenProfile.MAX_HEIGHT, profile.height)
                // JPEG 质量固定 100 后，原生 1440x3168 单帧编码要几百毫秒，回落管线直接黑屏。
                // 统一把长边压到 1760 以内：q100 下编码 ~120ms，3fps 能跑满，文字依旧清晰。
                var density = bounded(args, "density", 120, VirtualScreenProfile.MAX_DENSITY, profile.density)
                val longSide = maxOf(width, height)
                if (longSide > MAX_ENCODE_LONG_SIDE) {
                    val scale = MAX_ENCODE_LONG_SIDE.toFloat() / longSide
                    width = ((width * scale).roundToInt() / 2 * 2).coerceAtLeast(320)
                    height = ((height * scale).roundToInt() / 2 * 2).coerceAtLeast(480)
                    // 关键：像素缩小后 density 必须同比缩，否则布局宽度从 360dp 掉到 200dp，
                    // 应用会以为屏幕变窄（实测酷安 viewport=200x440，字巨大、卡片竖排）。
                    density = (density * scale).roundToInt()
                        .coerceIn(120, VirtualScreenProfile.MAX_DENSITY)
                }
                io.github.mangi.eta.core.EtaTrace.log(
                    "vscr",
                    "helper 启动 runId=$runId owner=$owner ${width}x$height" +
                        " density=$density allowOff=$allowOff 多开=${sessions.size + 1} 块",
                )
                HelperExitLog.started()
                VscrDiag.line("helper 启动 " + width + "x" + height + " d=" + density + " owner=" + owner)
                val apk = context.applicationInfo.sourceDir
                fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
                val process = ProcessBuilder(
                    "su",
                    "-c",
                    "CLASSPATH=${quote(apk)} app_process /system/bin io.github.mangi.eta.agent.display.RootDisplayCommandMain"
                ).start()
                VscrDiag.line("helper 进程已创建")
                Thread {
                    runCatching {
                        // 持续排空，同时把诊断文本留给 HelperExitLog；它只收短行，
                        // 超长行与疑似 base64 数据一律丢弃，不会带出画面或私有数据。
                        process.errorStream.bufferedReader().use { reader ->
                            while (true) {
                                val line = reader.readLine() ?: break
                                HelperExitLog.stderr(line)
                                VscrDiag.line("helper stderr: " + line)
                            }
                        }
                    }
                }.apply { isDaemon = true; start() }
                val input = process.outputStream.bufferedWriter()
                val output = process.inputStream.bufferedReader()
                val current = Session(context.applicationContext, owner, process, input, output, width, height, allowOff, runId)
                sessions[owner] = current
                focus(current)
                VirtualScreenWakeLock.sync(context, settings.virtualScreenKeepScreenOn)
                Thread {
                    val exitCode = runCatching { process.waitFor() }.getOrDefault(-1)
                    // 退出码是判断 native 崩溃还是被系统回收的唯一线索，必须留下。
                    io.github.mangi.eta.core.EtaTrace.log("vscr", "helper 退出 owner=$owner exitCode=$exitCode")
                    HelperExitLog.finished(exitCode)
                    VscrDiag.line("helper 退出 owner=" + owner + " exitCode=" + exitCode)
                    current.close()
                }.apply { name = "eta-display-exit"; isDaemon = true; start() }
                if (!AgentExecutionService.acquire(context, "virtual-screen-${current.id}", onStop = current::close)) {
                    current.close(); error("DISPLAY_LIFECYCLE_UNAVAILABLE")
                }
                if (isCancelled() || current.closed.get()) {
                    AgentExecutionService.release("virtual-screen-${current.id}")
                    current.close(); error("DISPLAY_CANCELLED")
                }
                val deadline = deadlines.schedule({ current.close() }, 15, TimeUnit.SECONDS)
                val response = try {
                    input.write(
                        JSONObject().put("action", "create").put("width", width)
                            .put("height", height).put("density", density)
                            // 显示尺寸由 App 下发：helper 是 app_process 起的，
                            // 部分机型上 getRealMetrics 拿不到主屏，dispW 会退化成采集尺寸（800x1760），
                            // 于是所有坐标都被钳在半屏范围内。
                            .put("displayWidth", mainScreenWidth(context))
                            .put("displayHeight", mainScreenHeight(context))
                            .put("displayDensity", mainScreenDensity(context))
                            .put("sessionId", current.id)
                            .put("owner", owner)
                            .put("selfPackage", context.packageName)
                            .put("surfaceToken", VirtualScreenHostSurfaces.token)
                            .put("idleTimeoutMinutes", settings.virtualScreenIdleTimeoutMinutes)
                            // 虚拟屏自身的合成刷新率：按设备实际支持的最高档取（≤90Hz），
                            // 与出帧档位无关（档位只决定往 App 传图的频率）。
                            .put("refreshRate", targetRefreshHz())
                            .put("frameIntervalMs", READ_SCREEN_INTERVAL_MS)
                            .put("jpegQuality", READ_SCREEN_QUALITY)
                            .put("watchMode", watchProfile != null)
                            .put("activeRun", runId != null)
                            .put("allowScreenOff", allowOff)
                            .put("mainScreenLocked", mainScreenLocked(context)).toString()
                    ); input.newLine(); input.flush()
                    VscrDiag.line("create 已发送，等待 helper 响应")
                    readResponse(output)
                } catch (error: Exception) {
                    VscrDiag.line("create 等待失败: " + error.message)
                    current.close(); throw error
                } finally {
                    deadline.cancel(false)
                }
                VscrDiag.line("helper 响应: " + response.toString().take(300))
                if (!response.optBoolean("ok") || response.optInt("displayId") <= 0 || current.closed.get()) {
                    current.close()
                    error(response.optString("code", "ROOT_DISPLAY_UNAVAILABLE"))
                }
                current.displayId = response.getInt("displayId")
                // helper 用宿主面建屏成功才允许界面切直通；否则保持整帧管线。
                VirtualScreenHostSurfaces.setLive(owner, response.optBoolean("direct"))
                current.viewer.value = VirtualScreenViewerState(
                    // width/height 取 helper 回传的 display 坐标系（与主屏一致），
                    // 触摸注入就按它算；采集尺寸更小，仅用于画面等比显示。
                    display = VirtualDisplayInfo(
                        current.id, owner, current.displayId,
                        response.optInt("width", width),
                        response.optInt("height", height),
                        density = response.optInt("density", density),
                        captureWidth = response.optInt("captureWidth", width),
                        captureHeight = response.optInt("captureHeight", height),
                    ),
                    lastAction = "create",
                    taskPhase = if (runId == null) VirtualScreenTaskPhase.IDLE else VirtualScreenTaskPhase.RUNNING,
                    activeRunId = runId,
                )
                publish(current)
                VirtualScreenNotification.show(context, current.id)
                if (current.closed.get()) {
                    current.viewer.update { VirtualScreenViewerState() }
                    publish(current)
                    VirtualScreenNotification.cancel(context, current.id)
                    error("DISPLAY_CANCELLED")
                }
                return@synchronized AgentModelClient.ToolResult(response.toString())
            }
            val current = sessions[owner] ?: error("NO_VIRTUAL_SCREEN")
            require(!current.closed.get() && current.process.isAlive) { "DISPLAY_GONE" }
            require(!current.allowOff || settings.virtualScreenOffEnabled) { "SCREEN_OFF_PERMISSION_REQUIRED" }
            focus(current)
            val timeout = deadlines.schedule({ current.close() }, 15, TimeUnit.SECONDS)
            val result = try {
                publishGesture(current, args)
                current.input.write(args.toString()); current.input.newLine(); current.input.flush()
                readResponse(current.output)
            } finally {
                timeout.cancel(false)
            }
            if (result.optBoolean("ok")) {
                // 横屏应用会让 Root 进程把 display 换成横屏尺寸，这里同步尺寸供小窗、坐标系与截图共用。
                val nextWidth = result.optInt("width", 0)
                val nextHeight = result.optInt("height", 0)
                if (nextWidth > 0 && nextHeight > 0) {
                    current.width = nextWidth
                    current.height = nextHeight
                }
                if (action in setOf("observe", "probe", "launch", "switch_task")) {
                    current.viewer.update { state -> state.copy(display = state.display?.let { info ->
                        info.copy(
                            width = current.width,
                            height = current.height,
                            focusedPackage = when (action) {
                                "probe" -> result.optString("packageName")
                                "launch", "switch_task" -> ""
                                else -> info.focusedPackage
                            },
                        )
                    }) }
                    publish(current)
                }
            }
            if (action !in setOf("observe", "probe")) {
                current.viewer.update { it.copy(lastAction = action) }
                publish(current)
            }
            if (action == "close") {
                current.close(); sessions.remove(owner)
            }
            val encoded = result.optString("image")
            result.remove("image")
            val images = if (encoded.isNotBlank() && result.optBoolean("ok")) listOf(
                AgentModelClient.ModelImage(
                    "data:image/jpeg;base64,$encoded",
                    "image/jpeg",
                    encoded.length * 3 / 4,
                    current.width,
                    current.height,
                    "virtual_display",
                    true
                )
            ) else emptyList()
            AgentModelClient.ToolResult(result.toString(), images, sensitive = true)
        } catch (error: Exception) {
            VscrDiag.line("execute 失败 action=" + args.optString("action") + " 原因=" + error.message)
            sessions[owner]?.let { current ->
                if (current.closed.get() || !current.process.isAlive ||
                    error.message in setOf("ROOT_REQUIRED", "VIRTUAL_SCREEN_DISABLED", "SCREEN_OFF_PERMISSION_REQUIRED")) {
                    current.close()
                    sessions.remove(owner)
                }
            }
            AgentModelClient.ToolResult(
                JSONObject().put("ok", false).put(
                    "code",
                    if (error is IllegalArgumentException || error is IllegalStateException) error.message
                        ?: "VIRTUAL_SCREEN_FAILED" else "VIRTUAL_SCREEN_FAILED"
                )
                    .put(
                        "message",
                        "灵屏操作未执行；不会回退到主屏。请确认 Root、灵屏开关和设备接口。"
                    ).toString()
            )
        }
    }

    /** A′ 直通：宿主面变了（查看页/小窗重建、互切），让 helper 立刻重新取面。 */
    fun notifyHostSurface(context: Context) = synchronized(lock) {
        val targets = sessions.values.toList()
        if (targets.isEmpty()) VscrDiag.line("直通 notify 无会话")
        targets.forEach { current ->
            if (current.closed.get()) return@forEach
            val result = execute(context, current.owner, JSONObject().put("action", "host_surface"))
            val payload = runCatching { JSONObject(result.content) }.getOrNull()
            val direct = payload?.optBoolean("direct") == true
            val info = payload?.optString("info").orEmpty()
            VirtualScreenHostSurfaces.setLive(current.owner, direct)
            VscrDiag.line(
                "直通 notify owner=" + current.owner.take(8) +
                    " direct=" + direct +
                    (if (info.isEmpty()) "" else " info=" + info) +
                    (if (payload?.optBoolean("ok") == true) ""
                    else " 原样=" + result.content.take(160))
            )
        }
    }

    /** 顶层 Activity 最近一次回传的方向诉求：true=横屏，false=竖屏，null=未知/跟随。 */
    @Volatile
    var lastLandscape: Boolean? = null
        private set

    /** 只探尺寸与前台包，不拉整帧：直通模式下小窗靠它跟上灵屏的横竖屏切换。 */
    fun refreshForViewer(context: Context, owner: String? = null): AgentModelClient.ToolResult = synchronized(lock) {
        val target = chooseSession(owner) ?: return@synchronized failure("NO_VIRTUAL_SCREEN")
        execute(context, target.owner, JSONObject().put("action", "probe"))
    }

    fun observeForViewer(context: Context, afterFrameId: Long = 0, owner: String? = null): AgentModelClient.ToolResult = synchronized(lock) {
        val target = chooseSession(owner) ?: return@synchronized failure("NO_VIRTUAL_SCREEN")
        execute(context, target.owner, JSONObject().put("action", "observe").put("afterFrameId", afterFrameId))
    }

    fun inputForViewer(context: Context, sessionId: String, args: JSONObject): AgentModelClient.ToolResult = synchronized(lock) {
        val current = sessions.values.firstOrNull { it.id == sessionId }
        if (current == null || args.optString("action") !in setOf("tap", "swipe", "long_press", "back", "key", "switch_task", "text", "touch", "ime")) {
            return@synchronized failure("STALE_VIRTUAL_SCREEN")
        }
        // 直通拖动每秒上百个事件：只有按下和抬起值得记一条历史。
        val liveMove = args.optString("action") == "touch" && args.optString("phase") in setOf("down", "move")
        if (!liveMove) {
            current.viewer.update { state -> state.copy(display = state.display?.let {
                it.copy(manualInputGeneration = it.manualInputGeneration + 1)
            }) }
            publish(current)
        }
        execute(context, current.owner, args, manual = true).also { result ->
            if (!liveMove) {
                val name = if (args.optString("action") == "key") args.optString("button").lowercase() else args.optString("action")
                recordOperation(current.owner, null, name, JSONObject(result.content).optBoolean("ok"), manual = true)
            }
        }
    }

    fun tasksForViewer(context: Context, sessionId: String): AgentModelClient.ToolResult = synchronized(lock) {
        val current = sessions.values.firstOrNull { it.id == sessionId } ?: return@synchronized failure("STALE_VIRTUAL_SCREEN")
        execute(context, current.owner, JSONObject().put("action", "tasks"))
    }

    /** 手动在小窗里点开某个应用：只在 display 内启动，绝不把主屏任务迁进来。 */
    fun launchForViewer(context: Context, sessionId: String, component: String): AgentModelClient.ToolResult = synchronized(lock) {
        val current = sessions.values.firstOrNull { it.id == sessionId } ?: return@synchronized failure("STALE_VIRTUAL_SCREEN")
        execute(context, current.owner, JSONObject().put("action", "launch").put("component", component)).also { result ->
            recordOperation(current.owner, null, "launch", JSONObject(result.content).optBoolean("ok"), manual = true)
        }
    }

    /** 在 display 内打开 Eta 自带导航页；mode 用 HOME / MENU / APPS。 */
    fun homeForViewer(context: Context, sessionId: String, mode: String): AgentModelClient.ToolResult = synchronized(lock) {
        val current = sessions.values.firstOrNull { it.id == sessionId } ?: return@synchronized failure("STALE_VIRTUAL_SCREEN")
        val action = mode.takeIf { it in setOf("HOME", "MENU", "APPS") } ?: return@synchronized failure("VIRTUAL_ACTION_UNSUPPORTED")
        execute(context, current.owner, JSONObject().put("action", "key").put("button", action))
    }

    /** Probe the Root process while holding the same lock as manual input and other tools. */
    fun withDisplay(
        context: Context,
        owner: String,
        isCancelled: () -> Boolean = { false },
        block: (VirtualDisplayInfo) -> AgentModelClient.ToolResult,
    ): AgentModelClient.ToolResult = synchronized(lock) {
        val probe = execute(context, owner, JSONObject().put("action", "probe"), isCancelled)
        if (!JSONObject(probe.content).optBoolean("ok")) return@synchronized probe
        val info = sessions[owner]?.viewer?.value?.display ?: return@synchronized failure("NO_VIRTUAL_SCREEN")
        block(info.copy(focusedPackage = JSONObject(probe.content).optString("packageName")))
    }

    fun showNodeGesture(info: VirtualDisplayInfo, action: String, x: Int, y: Int, durationMs: Int = 500, endX: Int = x, endY: Int = y) {
        val current = synchronized(lock) { sessions.values.firstOrNull { it.id == info.sessionId } } ?: return
        publishGesture(current, JSONObject().put("action", action).put("x", x).put("y", y)
            .put("x1", x).put("y1", y).put("x2", endX).put("y2", endY).put("durationMs", durationMs))
    }

    private fun publishGesture(current: Session, args: JSONObject) {
        val action = args.optString("action")
        if (action !in setOf("tap", "long_press", "swipe")) return
        val x = args.optInt(if (action == "swipe") "x1" else "x", -1)
        val y = args.optInt(if (action == "swipe") "y1" else "y", -1)
        val endX = if (action == "swipe") args.optInt("x2", -1) else x
        val endY = if (action == "swipe") args.optInt("y2", -1) else y
        if (x !in 0 until current.width || y !in 0 until current.height || endX !in 0 until current.width || endY !in 0 until current.height) return
        current.viewer.update { state -> state.copy(gesture = VirtualScreenGesture(
            gestureIds.incrementAndGet(), current.id, action, x, y, endX, endY,
            args.optInt("durationMs", 500), SystemClock.elapsedRealtime(),
        )) }
        publish(current)
    }

    private fun chooseSession(owner: String? = null): Session? {
        val target = if (owner != null) sessions[owner] else sessions[focusedOwner.orEmpty()]
        return target ?: sessions.values.lastOrNull { !it.closed.get() }
    }

    private fun failure(code: String) = AgentModelClient.ToolResult(
        JSONObject().put("ok", false).put("code", code).toString(), sensitive = true,
    )

    fun closeOwner(owner: String) {
        synchronized(lock) {
            sessions[owner]?.let { current ->
                sessions.remove(owner)
                current.close()
            }
            // 多开：还有别的屏在跑就别放开唤醒锁。
            if (sessions.isEmpty()) VirtualScreenWakeLock.release()
        }
    }

    fun revokePermission() {
        synchronized(lock) {
            val all = sessions.values.toList()
            sessions.clear()
            focusedOwner = null
            viewerState.value = VirtualScreenViewerState()
            all.forEach { it.close() }
            VirtualScreenWakeLock.release()
        }
    }

    private fun readResponse(output: BufferedReader): JSONObject {
        repeat(20) {
            val line = output.readLine() ?: error("ROOT_DISPLAY_DISCONNECTED")
            require(line.length <= 2_800_000) { "DISPLAY_OUTPUT_TOO_LARGE" }
            if (line.startsWith("ETA_DISPLAY_RESULT:")) return JSONObject(line.removePrefix("ETA_DISPLAY_RESULT:"))
        }
        error("DISPLAY_OUTPUT_INVALID")
    }

    private fun bounded(args: JSONObject, name: String, min: Int, max: Int, default: Int): Int {
        if (!args.has(name)) return default
        val value = args.get(name)
        require(
            value is Number && value.toDouble() == value.toInt()
                .toDouble() && value.toInt() in min..max
        ) { "INVALID_DISPLAY_DIMENSIONS" }
        return value.toInt()
    }
}
