package io.github.mangi.eta.agent.display

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
 * 虚拟屏会话表：**每个会话（owner）一块屏**，可以多开。
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

    /** 同时最多几块虚拟屏：再多内存与合成压力都顶不住。 */
    private const val MAX_SESSIONS = 1

    private val gestureIds = AtomicLong()

    /** 编码长边上限：q100 下的性能红线（见 create 里的注释）。 */
    private const val MAX_ENCODE_LONG_SIDE = 1760

    private val operationIds = AtomicLong()

    /** 会被写入虚拟屏的输入类动作；接管态下一律要过注入门。 */
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
        if (sessions.size < MAX_SESSIONS) return@synchronized true
        // 单屏策略：占着屏的是别的会话时不抢、不回收，直接告诉调用方 busy。
        // 早前会关掉对方「空闲」的屏，表现为另一个会话突然被暂停。
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
        val fps = io.github.mangi.eta.data.model.VirtualScreenFrameRate.normalize(settings.virtualScreenFrameRate)
        targets.forEach { current ->
            execute(
                current.context,
                current.owner,
                JSONObject().put("action", "lifecycle")
                    .put("idleTimeoutMinutes", settings.virtualScreenIdleTimeoutMinutes)
                    .put("activeRun", current.viewer.value.activeRunId != null)
                    .put("frameIntervalMs", io.github.mangi.eta.data.model.VirtualScreenFrameRate.intervalMs(fps))
                    .put("jpegQuality", io.github.mangi.eta.data.model.VirtualScreenFrameRate.jpegQuality(fps)),
            )
        }
        Unit
    }

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
                if (sessions.size >= MAX_SESSIONS) {
                    val holder = sessions.values.firstOrNull()
                    VscrDiag.line("灵屏已占用 holder=" + (holder?.owner?.take(8) ?: "无"))
                    error("DISPLAY_BUSY")
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
                        " density=$density allowOff=$allowOff 多开=${sessions.size + 1}/$MAX_SESSIONS",
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
                            .put("sessionId", current.id)
                            .put("owner", owner)
                            .put("selfPackage", context.packageName)
                            .put("surfaceToken", VirtualScreenHostSurfaces.token)
                            .put("idleTimeoutMinutes", settings.virtualScreenIdleTimeoutMinutes)
                            .put("refreshRate", settings.virtualScreenFrameRate)
                            .put("frameIntervalMs", io.github.mangi.eta.data.model.VirtualScreenFrameRate.intervalMs(settings.virtualScreenFrameRate))
                            .put("jpegQuality", io.github.mangi.eta.data.model.VirtualScreenFrameRate.jpegQuality(settings.virtualScreenFrameRate))
                            .put("activeRun", runId != null)
                            .put("allowScreenOff", allowOff).toString()
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
                    display = VirtualDisplayInfo(current.id, owner, current.displayId, width, height, density = density),
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
                        "虚拟屏操作未执行；不会回退到主屏。请确认 Root、虚拟屏开关和设备接口。"
                    ).toString()
            )
        }
    }

    /** A′ 直通：宿主面变了（查看页/小窗重建、互切），让 helper 立刻重新取面。 */
    fun notifyHostSurface(context: Context) = synchronized(lock) {
        val targets = sessions.values.toList()
        targets.forEach { current ->
            if (current.closed.get()) return@forEach
            val result = execute(context, current.owner, JSONObject().put("action", "host_surface"))
            VirtualScreenHostSurfaces.setLive(current.owner, JSONObject(result.content).optBoolean("direct"))
        }
    }

    /** 顶层 Activity 最近一次回传的方向诉求：true=横屏，false=竖屏，null=未知/跟随。 */
    @Volatile
    var lastLandscape: Boolean? = null
        private set

    /** 只探尺寸与前台包，不拉整帧：直通模式下小窗靠它跟上虚拟屏的横竖屏切换。 */
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
