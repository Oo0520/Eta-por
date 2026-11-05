package io.github.mangi.eta.agent.display

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.display.VirtualDisplayConfig
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.json.JSONArray

/** Root 子进程持有独立 Surface；只接受固定操作。管道结束即释放 display。 */
@androidx.annotation.RequiresApi(34)
internal object RootDisplayCommandMain {
    private const val MARKER = "ETA_DISPLAY_RESULT:"

    /**
     * 灵屏所在 DisplayGroup 的唤醒锁参数。
     *
     * `PowerManager.newWakeLock(int, String, int)` 是 @hide 三参重载，按 displayId 把锁绑到该显示
     * 所属的 DisplayGroup（灵屏带 OWN_DISPLAY_GROUP，独占一组，不会点亮手机屏）。
     * 实测（ColorOS / Android 16）：只给 FULL_WAKE_LOCK 时对已经睡下的组无效——
     * display state 仍 OFF、窗口 focused=false、节点树为空；必须并上 ACQUIRE_CAUSES_WAKEUP
     * 才能真正把组唤醒。锁带超时，靠后台线程每 20s 续期一次。
     */
    private const val DISPLAY_WAKE_HOLD_MS = 60_000L
    private const val DISPLAY_WAKE_RENEW_MS = 20_000L

    /** 当前 display id 与 Eta 自身包名：finally 清理里面的任务时要用。 */
    private var activeDisplayId = 0
    private var selfPackage = ""
    // 帧回调线程读取的画布尺寸快照：切尺寸只在帧线程上发生，这里保证跨线程可见。
    @Volatile private var renderWidth = 0
    @Volatile private var renderHeight = 0
    /** A′ 直通出帧：向本应用 provider 取当前宿主 Surface；root/system 身份调用走权限豁免。 */
    /** 直通出帧排查日志：helper 是 root，可直接写公共目录。 */
    private fun hlog(text: String) {
        runCatching {
            java.io.File("/data/local/tmp/xaw-helper.log")
                .appendText(android.os.SystemClock.elapsedRealtime().toString() + " " + text + "\n")
        }
    }

    /**
     * Android 17（SDK 37）起，`IWindowManager` 这类「可缓存」系统接口在客户端要读
     * `com.android.internal.os.ApplicationSharedMemory` 的 nonce，而这块内存只由 AMS 在
     * bindApplication 时下发的 fd 初始化。helper 是 su 起的裸 app_process，没走过
     * bindApplication，`getInstance()` 直接抛 IllegalStateException（实测：KeyguardManager.<init>
     * → WindowManagerGlobal.getWindowManagerService），建屏第一步就挂。
     *
     * 这里自建一块私有共享内存顶上：system_server 不会往里写 nonce，缓存永远判过期，
     * 退化成普通 binder 调用——只是不再吃缓存，能力是全的。初始化失败不阻断建屏。
     */
    private fun initApplicationSharedMemory() {
        val cls = runCatching {
            Class.forName("com.android.internal.os.ApplicationSharedMemory")
        }.getOrNull()
        if (cls == null) {
            hlog("asm: class absent")
            return
        }
        if (runCatching { cls.getMethod("getInstance").invoke(null) }.isSuccess) {
            hlog("asm: already initialized")
            return
        }
        val done = runCatching {
            val instance = cls.getMethod("create").invoke(null)
            cls.getMethod("setInstance", cls).invoke(null, instance)
        }
        hlog("asm: init=" + done.isSuccess + (done.exceptionOrNull()?.let { " err=" + it } ?: ""))
    }

    /** helper 拿不到 KeyguardManager（Android 17）时，用 App 主进程下发的锁屏状态兜底。 */
    @Volatile private var mainScreenLockedHint = false

    private fun mainScreenLocked(keyguard: KeyguardManager?): Boolean =
        runCatching { keyguard?.isDeviceLocked }.getOrNull() ?: mainScreenLockedHint

    /**
     * 宿主包名与取面 provider 的 authority。
     * 不能写死：正式版是 io.github.mangi.eta，测试版是 io.github.mangi.eta.beta，
     * 硬编码会让其中一边永远拉不到 Surface（日志表现为 pull NPE → create direct=false）。
     * 由 create 请求里的 selfPackage 推导。
     */
    private var hostPackage = "io.github.mangi.eta"
    private fun hostAuthority(): String = hostPackage + ".hostsurface"
    /** helper 是裸 app_process，没有 AMS 里的 app 记录；必须走 external provider 通道。 */
    private var extProvider: Any? = null

    // root app_process has no app identity: use uid 0 AttributionSource.
    private fun attrSource(): Any? = runCatching {
        val cls = Class.forName("android.content.AttributionSource")
        runCatching { cls.getMethod("myAttributionSource").invoke(null) }.getOrElse {
            cls.getDeclaredConstructor(
                Int::class.javaPrimitiveType, String::class.java, String::class.java
            ).also { it.isAccessible = true }.newInstance(0, hostPackage, null)
        }
    }.getOrNull()

    private fun callProvider(method: String, extras: android.os.Bundle): android.os.Bundle? {
        val am = ActivityManager::class.java.getDeclaredMethod("getService").invoke(null)
        val provider = extProvider ?: run {
            val holder = am.javaClass.getMethod(
                "getContentProviderExternal", String::class.java, Int::class.javaPrimitiveType,
                android.os.IBinder::class.java, String::class.java
            ).invoke(am, hostAuthority(), 0, null, "eta-vscr")
            // holder 为 null = 宿主 App 没发布该 provider（未注册 / 进程未起 / 版本不匹配）。
            // 以前这里直接 holder.javaClass 会抛 NPE，日志只剩 "pull: fail ... getClass() on null"，
            // 看不出真正原因；现在给明确结论并回落到 ImageReader 出帧。
            if (holder == null) {
                error("PROVIDER_NOT_PUBLISHED:" + hostAuthority())
            }
            holder.javaClass.getField("provider").get(holder).also { extProvider = it }
        }
        // A15+ 的 IContentProvider.call 首参是 AttributionSource（5 参），更早是 callingPackage+attributionTag（6 参）。
        val calls = provider!!.javaClass.methods.filter {
            it.name == "call" && it.parameterTypes.last() == android.os.Bundle::class.java
        }
        val call = calls.firstOrNull {
            it.parameterTypes.size == 5 &&
                it.parameterTypes[0].name == "android.content.AttributionSource"
        } ?: calls.firstOrNull { it.parameterTypes.size == 6 }
        ?: throw NoSuchMethodException("IContentProvider.call")
        val args = if (call.parameterTypes.size == 6) {
            arrayOf<Any?>(hostPackage, null, hostAuthority(), method, null, extras)
        } else {
            arrayOf<Any?>(attrSource(), hostAuthority(), method, null, extras)
        }
        return call.invoke(provider, *args) as android.os.Bundle?
    }

    @Suppress("UNUSED_PARAMETER")
    private fun pullHostSurface(context: Context, token: String?, owner: String = ""): android.view.Surface? {
        if (token.isNullOrEmpty()) { hlog("pull: no token"); return null }
        return runCatching {
            val extras = android.os.Bundle().apply {
                putString("token", token)
                putString(VirtualScreenHostSurfaces.EXTRA_OWNER, owner)
            }
            val result = try {
                callProvider("surface", extras)
            } catch (first: Throwable) {
                hlog("pull: retry after " + first)
                extProvider = null
                callProvider("surface", extras)
            }
            if (result == null) { hlog("pull: provider null"); return null }
            @Suppress("DEPRECATION")
            result.getParcelable<android.view.Surface>("surface").also { hlog("pull: got=" + (it != null)) }
        }.onFailure { hlog("pull: fail " + it) }.getOrNull()
    }

    @JvmStatic
    fun main(args: Array<String>) {
        // App 侧会收集 stderr：非受控异常必须留下类型与堆栈，否则只看到"灵屏不可用"。
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            System.err.println("ETA_HELPER_UNCAUGHT " + thread.name)
            error.printStackTrace()
            System.err.flush()
        }
        var display: VirtualDisplay? = null
        var reader: ImageReader? = null
        val readerRef = java.util.concurrent.atomic.AtomicReference<ImageReader?>(null)
        var frames: HandlerThread? = null
        var wakeLock: PowerManager.WakeLock? = null
        /** 灵屏所在显示组的唤醒锁；拿不到时为 null（退回仅探活，不影响其它逻辑）。 */
        var displayWakeLock: PowerManager.WakeLock? = null
        try {
            require(Process.myUid() == 0) { "ROOT_REQUIRED" }
            if (Looper.myLooper() == null) Looper.prepareMainLooper()
            // Android 17：先把进程级共享内存装上，否则第一次碰 IWindowManager 就抛异常。
            initApplicationSharedMemory()
            val activityThread = Class.forName("android.app.ActivityThread")
            val thread = activityThread.getDeclaredMethod("systemMain").invoke(null)
            val context =
                activityThread.getDeclaredMethod("getSystemContext").invoke(thread) as Context
            val input = System.`in`.bufferedReader()
            val setup = JSONObject(input.readLine() ?: return)
            // 宿主包名要先拿到：下面 pullHostSurface 用的 authority 由它推导。
            selfPackage = setup.optString("selfPackage").ifBlank { "io.github.mangi.eta" }
            hostPackage = selfPackage
            require(setup.optString("action") == "create")
            var width = integer(setup, "width", 320, VirtualScreenProfile.MAX_WIDTH)
            var height = integer(setup, "height", 480, VirtualScreenProfile.MAX_HEIGHT)
            val dpi = integer(setup, "density", 120, VirtualScreenProfile.MAX_DENSITY)
            // 屏合成刷新率由 App 按设备实际能力算好再下发；这里只挡非法值，
            // 不擅自抬到 60 以上——设备本来只有 60Hz 时抬高只会让 App 按错的帧预算排帧。
            val refreshRate = setup.optInt("refreshRate", 60).coerceIn(24, 240).toFloat()
            val allowOff = setup.optBoolean("allowScreenOff", false)
            mainScreenLockedHint = setup.optBoolean("mainScreenLocked", false)
            val idleTimer = VirtualScreenIdleTimer(setup.optInt("idleTimeoutMinutes", 20),
                SystemClock::elapsedRealtime, setup.optBoolean("activeRun"))
            var frameIntervalMs = setup.optInt("frameIntervalMs", 16).coerceIn(4, 200)
            var jpegQuality = setup.optInt("jpegQuality", 100).coerceIn(40, 100)
            /**
             * 双档出帧：
             *  - watchMode=true（浮窗可见 / 接管页在前台）：人眼在看，按 frameIntervalMs 出帧（默认 50ms ≈ 20fps），
             *    JPEG 压到 85，优先保证跟手；
             *  - watchMode=false（纯 Agent 读屏）：自适应 1–3fps + 原质量，优先保证文字清晰、省电。
             * 直通建立后本回调不参与出图，两档都无意义。
             */
            var watchMode = setup.optBoolean("watchMode", false)
            Thread {
                while (true) {
                    Thread.sleep(1000)
                    if (idleTimer.expired()) Process.killProcess(Process.myPid())
                }
            }.apply { name = "eta-root-display-idle"; isDaemon = true; start() }
            val displayManager = context.getSystemService(DisplayManager::class.java)
            // Android 17 上 KeyguardManager 构造会碰 WindowManagerGlobal：拿不到时不能让整块屏起不来。
            val keyguard = runCatching { context.getSystemService(KeyguardManager::class.java) }
                .onFailure { hlog("keyguard unavailable: " + it) }
                .getOrNull()
            val power = context.getSystemService(PowerManager::class.java)
            frames = HandlerThread("eta-virtual-frames").apply { start() }
            val handler = Handler(frames.looper)
            val frameStore = FrameStore()
            // 传输尺寸（长边 ≤1760）：帧回调要用，先声明后赋值。
            var capW = 0
            var capH = 0
            val frameListener = ImageReader.OnImageAvailableListener { source ->
                try {
                // 尺寸切换后旧 reader 队列里可能还有迟到回调，直接丢弃，避免碰到已释放的帧缓冲。
                if (readerRef.get() !== source) return@OnImageAvailableListener
                source.acquireLatestImage()?.use { image ->
                    val now = SystemClock.elapsedRealtime()
                    // 没有客户端在取帧时不编码：只保持画面队列流动，省电、少发热。
                    // 观看态例外：人眼盯着画面，必须持续出帧，不能等 observe 来拉。
                    if (!watchMode && now - frameStore.lastDemandAt > 1500L) return@use
                    val plane = runCatching { image.planes.firstOrNull() }.getOrNull() ?: return@use
                    if (plane.pixelStride != 4) return@use
                    val paddedWidth = plane.rowStride / plane.pixelStride
                    // 出帧节奏完全由 App 按档位下发（见 VirtualScreenSession.WatchProfile）。
                    val buffer = runCatching { plane.buffer }.getOrNull() ?: return@use
                    val signature = runCatching { sampleSignature(buffer) }.getOrDefault(0L)
                    frameStore.differs(signature)
                    // 统一按 App 下发的目标间隔出帧（浮窗 40ms/接管 屏幕刷新率/收起与读屏 200ms），
                    // 再用「上一帧编码实际耗时 ×1.2」兜底：接管档按真机刷新率下发（120Hz→8ms），
                    // JPEG 编码远追不上，不设这个下限会把编码线程跑满却只出几帧，白烧 CPU。
                    val cost = frameStore.lastEncodeCostMs
                    val minInterval = maxOf(frameIntervalMs.toLong(), (cost * 6 / 5).coerceAtLeast(0L))
                    if (now - frameStore.lastEncoded < minInterval) return@use
                    // 尺寸对不上的帧直接丢：切换期间在途的旧尺寸帧，其缓冲可能已被释放。
                    val frameWidth = renderWidth
                    val frameHeight = renderHeight
                    if (frameWidth <= 0 || frameHeight <= 0 || paddedWidth < frameWidth) return@use
                    if (image.width != frameWidth || image.height != frameHeight) return@use
                    val padded = frameStore.padded(paddedWidth, frameHeight) ?: return@use
                    if (padded.width < frameWidth || padded.height < frameHeight) return@use
                    val cropped = if (paddedWidth == frameWidth) padded else {
                        runCatching { Bitmap.createBitmap(padded, 0, 0, frameWidth, frameHeight) }.getOrNull()
                            ?: return@use
                    }
                    // 先按帧缓冲实际长度拷进受保护的堆数组，再写位图：直接 copyPixelsFromBuffer
                    // 在缓冲比位图小时会在 native memmove 越界读并 SIGSEGV，整块灵屏服务一起挂掉。
                    try {
                        val required = padded.rowBytes * padded.height
                        val bytes = frameStore.copyBuffer(required) ?: return@use
                        buffer.rewind()
                        val available = minOf(required, buffer.remaining())
                        if (available < required) {
                            // 短读：拷贝缓冲不完整。以前只判 <=0，没填满的部分留着「上一帧」的像素，
                            // 于是画面变成上截新、下截旧的叠影（实测微信聊天页常见：标题栏与列表错位）。
                            // padded 是复用位图，不丢帧就一定会出现这种残留。
                            frameStore.shortReads++
                            if (frameStore.shortReads % 60 == 1L) {
                                hlog("frame short read available=" + available + " required=" + required)
                            }
                            return@use
                        }
                        buffer.get(bytes, 0, available)
                        padded.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(bytes))
                    } catch (_: Throwable) {
                        return@use
                    }
                    // 全尺寸帧（1440x3168）缩到传输尺寸（长边 ≤1760）再编码：
                    // 缩放比压缩便宜得多，而且编码的输入小了 3 倍。
                    val scaled = if (cropped.width == capW && cropped.height == capH) cropped
                    else frameStore.scaled(cropped, capW, capH) ?: return@use
                    val encoded = ByteArrayOutputStream()
                    try {
                        // 质量由 App 按档下发（浮窗 80 / 接管 90 / 读屏沿用用户设置），这里不再就地打折。
                        val startedAt = SystemClock.elapsedRealtime()
                        scaled.compress(Bitmap.CompressFormat.JPEG, jpegQuality, encoded)
                        frameStore.lastEncodeCostMs = SystemClock.elapsedRealtime() - startedAt
                        // q100 不压缩后单帧明显变大（1080x2376 常到 3~6MB），上限跟着放宽，否则整帧会被丢掉。
                        encoded.toByteArray().takeIf { it.size <= 12_000_000 }?.let {
                            frameStore.lastSignature = signature
                            frameStore.frame = EncodedFrame(it, SystemClock.elapsedRealtime())
                        }
                    } finally {
                        if (cropped !== padded) cropped.recycle()
                    }
                }
                } catch (_: Throwable) {
                    // 丢帧即可：帧回调线程抛异常会直接杀死 root helper 进程（表现为打开灵屏闪退）。
                }
            }
            val surfaceToken = setup.optString("surfaceToken")
            // 多开：每块屏只取自己会话的宿主面，取不到就回落整帧管线。
            val surfaceOwner = setup.optString("owner")
            var hostSurface = pullHostSurface(context, surfaceToken, surfaceOwner)
            /**
             * 显示尺寸必须跟随主屏，不能是压缩后的采集尺寸。
             *
             * 原因（实测）：灵屏原先用 800x1760@356 建屏，拼多多把「当前屏幕物理尺寸」当成
             * 自己的缩放基准存了下来，回到主屏后整界面变小、字体错乱，且重启 App 也修不好。
             * 现在让 App 看到的是真实的 1440x3168@640，只把「采集」这一层压到 800x1760：
             * SurfaceFlinger 合成全尺寸后缩放到小 surface，内存与带宽不变，App 的认知与主屏一致。
             */
            val mainDisplay = runCatching { displayManager.getDisplay(android.view.Display.DEFAULT_DISPLAY) }.getOrNull()
            val mainMetrics = android.util.DisplayMetrics().also { metrics ->
                runCatching { mainDisplay?.getRealMetrics(metrics) }
            }
            // 优先用 App 下发的显示尺寸；自己读主屏只在拿不到时兜底。
            var dispW = setup.optInt("displayWidth", 0).takeIf { it > 0 }
                ?: mainMetrics.widthPixels.takeIf { it > 0 } ?: width
            var dispH = setup.optInt("displayHeight", 0).takeIf { it > 0 }
                ?: mainMetrics.heightPixels.takeIf { it > 0 } ?: height
            var dispDpi = setup.optInt("displayDensity", 0).takeIf { it > 0 }
                ?: mainMetrics.densityDpi.takeIf { it > 0 } ?: dpi
            // 传输尺寸：长边压到 MAX_ENCODE_LONG_SIDE，编码前把整帧缩放到这个尺寸。
            // 实测结论：SurfaceFlinger 不会把「全尺寸 display」缩放到「小尺寸 output surface」，
            // 而是按左上角裁剪——所以采集必须与 display 同尺寸，压缩只能放在编码前做。
            capW = width
            capH = height
            hlog("create direct=" + (hostSurface != null) + " capture=" + capW + "x" + capH +
                " display=" + dispW + "x" + dispH + "@" + dispDpi)
            val initialReader = if (hostSurface == null) {
                // 全尺寸采集：1440x3168 RGBA 单帧 18MB，缓冲降到 2 个（约 36MB）。
                ImageReader.newInstance(dispW, dispH, PixelFormat.RGBA_8888, 2)
                    .apply { setOnImageAvailableListener(frameListener, handler) }
            } else {
                null
            }
            reader = initialReader
            if (initialReader != null) readerRef.set(initialReader)
            renderWidth = dispW
            renderHeight = dispH
            // Android 14+ own-focus 和不抢顶层焦点；不使用 AUTO_MIRROR，也不捕获安全 Surface。
            var flags =
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                        flag("VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH") or flag("VIRTUAL_DISPLAY_FLAG_TRUSTED") or
                        flag("VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP") or flag("VIRTUAL_DISPLAY_FLAG_OWN_FOCUS") or
                        flag("VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED") or flag("VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL")
            if (allowOff) flags = flags or flag("VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED")
            display = displayManager.createVirtualDisplay(
                VirtualDisplayConfig.Builder("Eta virtual task", dispW, dispH, dispDpi)
                    .setSurface(hostSurface ?: initialReader!!.surface).setFlags(flags)
                    .setRequestedRefreshRate(refreshRate).build(),
            )
                ?: error("DISPLAY_CREATE_FAILED")
            val id = display.display.displayId
            require(id > 0) { "INVALID_DISPLAY" }
            activeDisplayId = id
            // 打开灵屏先拉起本应用自己的灵屏桌面（应用导航页），按 Home/应用键也回这儿。
            selfPackage = setup.optString("selfPackage")
            // 不再拉起灵屏自带的「主页」（应用菜单那套导航页）：
            // 建屏后直接由 App 侧的 launchComponent 打开目标应用，
            // 中间多一层主页只会让接管页先闪一屏无关界面。
            // 万一上层没有立刻拉起应用，屏上就是空的——比闪一下主页更不打扰。
            val localIme = configureLocalIme(id)
            val textInput = VirtualScreenRootTextInput(id)
            // 横屏场景：灵屏自身不能旋转，按顶层应用请求的方向把 display 换成横屏尺寸（画面不旋转、
            // 坐标系仍是 0°），否则系统会把横屏应用信箱化成长条，小窗里看起来"横不过来"。
            var shapeProbeAt = 0L
            fun applyShape(landscape: Boolean) {
                if (landscape == (width > height)) return
                val nextWidth = height
                val nextHeight = width
                // 整个切换动作排到帧线程上串行执行：与正在进行的帧拷贝互斥。
                // 否则换 surface 的瞬间旧帧缓冲被释放，正在拷贝的那一帧会踩到已解除的映射，
                // native memmove 越界 → root helper 进程直接被杀（表现为切几次杀一次）。
                handler.post {
                    try {
                        val previous = reader
                        runCatching { previous?.setOnImageAvailableListener(null, null) }
                        // A′ 直通：宿主面不变。改灵屏尺寸会让直通面重新分配缓冲，实测会崩 native，
                        // 因此直通下建屏尺寸保持不变（横屏应用按信箱化显示）。
                        if (hostSurface != null) {
                            // 直通下也要能横过来：先把直通面摘下来，改完尺寸再挂回去，
                            // 不让合成器在挂着宿主面的情况下重分配缓冲（以前正是在这一步崩 native）。
                            val host = hostSurface
                            val applied = runCatching {
                                display?.setSurface(null)
                                // 显示尺寸按主屏横竖交换；采集尺寸按同样交换，两者比例保持一致。
                                val nextDispW = if (dispW > dispH) dispH else dispW
                                val nextDispH = if (dispW > dispH) dispW else dispH
                                display?.resize(nextDispW, nextDispH, dispDpi)
                                dispW = nextDispW
                                dispH = nextDispH
                                display?.setSurface(host)
                            }
                            if (applied.isFailure) {
                                runCatching { display?.setSurface(host) }
                                hlog("shape direct fail landscape=" + landscape)
                                return@post
                            }
                            width = nextWidth
                            height = nextHeight
                            capW = nextWidth
                            capH = nextHeight
                            renderWidth = dispW
                            renderHeight = dispH
                            frameStore.reset()
                            hlog("shape direct ok landscape=" + landscape + " display=" + dispW + "x" + dispH +
                                " capture=" + capW + "x" + capH)
                            return@post
                        }
                        val nextDispW = if (dispW > dispH) dispH else dispW
                        val nextDispH = if (dispW > dispH) dispW else dispH
                        val next = ImageReader.newInstance(nextDispW, nextDispH, PixelFormat.RGBA_8888, 2)
                        val applied = runCatching {
                            display?.setSurface(next.surface)
                            display?.resize(nextDispW, nextDispH, dispDpi)
                            dispW = nextDispW
                            dispH = nextDispH
                            capW = nextWidth
                            capH = nextHeight
                        }
                        if (applied.isFailure) {
                            next.close()
                            runCatching { previous?.setOnImageAvailableListener(frameListener, handler) }
                            return@post
                        }
                        reader = next
                        readerRef.set(next)
                        width = nextWidth
                        height = nextHeight
                        renderWidth = dispW
                        renderHeight = dispH
                        frameStore.reset()
                        next.setOnImageAvailableListener(frameListener, handler)
                        runCatching { previous?.close() }
                    } catch (_: Throwable) {
                        // 切换失败就保持原状，下一轮 syncShape 会再试。
                    }
                }
            }
            fun syncShape(force: Boolean = false) {
                val now = SystemClock.elapsedRealtime()
                if (!force && now - shapeProbeAt < 5000) return
                shapeProbeAt = now
                val landscape = requestedLandscape(id) ?: return
                runCatching { applyShape(landscape) }
            }
            if (allowOff) wakeLock =
                power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Eta:virtual-task")
                    .apply { acquire() }
            // 灵屏整组的唤醒锁：三参 newWakeLock 按 displayId 绑定该显示所属 DisplayGroup。
            // 只给 FULL_WAKE_LOCK 对已经睡下的组无效，必须并上 ACQUIRE_CAUSES_WAKEUP。
            displayWakeLock = runCatching {
                val method = PowerManager::class.java.getMethod(
                    "newWakeLock",
                    Int::class.javaPrimitiveType,
                    String::class.java,
                    Int::class.javaPrimitiveType,
                )
                (method.invoke(
                    power,
                    PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "Eta:virtual-display-$id",
                    id,
                ) as PowerManager.WakeLock).apply {
                    setReferenceCounted(false)
                    acquire(DISPLAY_WAKE_HOLD_MS)
                }
            }.onFailure { hlog("display wakelock unavailable: " + it) }.getOrNull()
            hlog("display wakelock=" + (displayWakeLock != null) + " id=" + id)
            Thread {
                // 续期 + 探活：锁带超时，且 display 被系统重建后旧 id 会失效。
                while (true) {
                    Thread.sleep(DISPLAY_WAKE_RENEW_MS)
                    val lock = displayWakeLock ?: break
                    if (displayManager.getDisplay(id) == null) {
                        hlog("display gone id=" + id + "，停止续期")
                        break
                    }
                    // allowScreenOff=false 时跟随手机屏：手机熄屏或锁屏就让灵屏睡回去。
                    if (!allowOff && (!power.isInteractive || mainScreenLocked(keyguard))) continue
                    runCatching { lock.acquire(DISPLAY_WAKE_HOLD_MS) }
                }
            }.apply { name = "eta-display-wakelock"; isDaemon = true; start() }
            reply(
                JSONObject().put("ok", true).put("displayId", id).put("width", dispW)
                    .put("height", dispH).put("density", dispDpi).put("refreshRate", display.display.refreshRate)
                    // 采集尺寸单独给：画面比例按它算，坐标一律按上面的 width/height。
                    .put("captureWidth", capW).put("captureHeight", capH)
                    .put("independentFocus", true)
                    .put("direct", hostSurface != null)
                    .put("localIme", localIme)
            )
            while (true) {
                val line = input.readLine() ?: break
                if (line.length > 128_000) {
                    reply(failure("INVALID_ARGUMENT")); continue
                }
                val request = runCatching { JSONObject(line) }.getOrNull()
                if (request == null) {
                    reply(failure("INVALID_ARGUMENT")); continue
                }
                if (request.optString("action") == "close") {
                    reply(JSONObject().put("ok", true)); break
                }
                try {
                    require(displayManager.getDisplay(id) != null) { "DISPLAY_GONE" }
                    require(allowOff || (power.isInteractive && !mainScreenLocked(keyguard))) { "SCREEN_OFF_PERMISSION_REQUIRED" }
                    runCatching { syncShape() }
                    val result = when (request.getString("action")) {
                        "host_surface" -> {
                            // 查看页/小窗换了宿主面：重新取一次并直接换面，失败保持原面不动。
                            val next = pullHostSurface(context, surfaceToken, surfaceOwner)
                            // 失败原因必须回传给 App：helper 自己的 hlog 落在
                            // /data/local/tmp/xaw-helper.log，用户在 vscr.log 里看不到。
                            val info = if (next == null) {
                                // 退出直通（接管页关闭）：必须把输出面切回采集用的 ImageReader，
                                // 否则画面会冻在最后一个直通帧上，浮窗也拿不到新帧。
                                hostSurface = null
                                // 先摘后挂：只 setSurface 的话，画面内容没变化时合成器不会重绘，
                                // ImageReader 就一帧都拿不到，浮窗会一直停在接管前那一帧。
                                runCatching {
                                    display?.setSurface(null)
                                    display?.setSurface(reader?.surface)
                                }
                                frameStore.lastSignature = -1L
                                "pull=null 回落采集面"
                            } else {
                                hostSurface = next
                                val set = runCatching { display?.setSurface(next) }
                                "pull=ok set=" + set.isSuccess +
                                    (set.exceptionOrNull()?.let { " err=" + it } ?: "")
                            }
                            hlog("host_surface direct=" + (hostSurface != null) + " " + info)
                            JSONObject().put("ok", true).put("direct", hostSurface != null)
                                .put("info", info)
                        }
                        "lifecycle" -> {
                            idleTimer.update(request.optInt("idleTimeoutMinutes", 20), request.optBoolean("activeRun"))
                            // Android 17 上 helper 自己问不到锁屏状态，由 App 主进程顺带下发。
                            if (request.has("mainScreenLocked")) {
                                mainScreenLockedHint = request.optBoolean("mainScreenLocked")
                            }
                            if (request.has("frameIntervalMs")) frameIntervalMs = request.optInt("frameIntervalMs", frameIntervalMs).coerceIn(4, 200)
                            if (request.has("jpegQuality")) jpegQuality = request.optInt("jpegQuality", jpegQuality).coerceIn(40, 100)
                            if (request.has("watchMode")) {
                                val next = request.optBoolean("watchMode", watchMode)
                                if (next != watchMode) {
                                    watchMode = next
                                    // 不能 reset：reset 会连 frame 一起清空，
                                    // 切到接管档的那一瞬间客户端恰好来取帧，就会拿到 DISPLAY_FRAME_PENDING，
                                    // 画面上就是「进接管页先黑一片」（实测 5 秒内都没恢复）。
                                    // 只作废签名、逼下一帧重编码，已有帧继续可读。
                                    frameStore.lastSignature = -1L
                                    hlog("watchMode=" + watchMode + " interval=" + frameIntervalMs + " q=" + jpegQuality)
                                }
                            }
                            JSONObject().put("ok", true).put("displayId", id)
                        }
                        "probe" -> JSONObject().put("ok", true).put("displayId", id)

                            .put("width", dispW).put("height", dispH)
                            .put("captureWidth", capW).put("captureHeight", capH)
                            .put("refreshRate", display.display.refreshRate)
                            .put("packageName", runningTasks().firstOrNull { taskDisplayId(it) == id }?.topActivity?.packageName.orEmpty())
                            .apply {
                                // 顶层 Activity 的真实方向诉求（页面级，比包级准确）。
                                // dumpsys 较慢，3 秒内复用上一次结果。
                                val now = SystemClock.elapsedRealtime()
                                if (now - lastOrientationAtMs > 3_000L) {
                                    lastOrientation = requestedLandscape(id)
                                    lastOrientationAtMs = now
                                }
                                val known = lastOrientation
                                if (known != null) put("landscape", known) else put("landscapeKnown", false)
                            }

                        // 真机键盘状态：灵屏里的编辑框有没有申请输入法、系统到底弹没弹。
                        "ime" -> {
                            val dump = runCatching {
                                command(listOf("/system/bin/dumpsys", "input_method"), 400_000)
                            }.getOrDefault("")
                            var requested = false
                            var sessioned = false
                            dump.split("ClientState{").forEach { block ->
                                if (Regex("mSelfReportedDisplayId=" + id + "\\b").containsMatchIn(block)) {
                                    if (block.contains("sessionRequested=true")) requested = true
                                    if (block.contains("curSession=")) sessioned = true
                                }
                            }
                            JSONObject().put("ok", true)
                                .put("inputShown", if (dump.contains("isShowing: true")) "true" else "false")
                                .put("displayRequested", if (requested) "true" else "false")
                                .put("displaySession", if (sessioned) "true" else "false")
                        }
                        "tasks" -> JSONObject().put("ok", true).put("tasks", JSONArray(
                            runningTasks().filter { taskDisplayId(it) == id &&
                                    it.baseActivity?.className != VirtualScreenHomeActivity::class.java.name }
                                .mapNotNull { task -> task.baseActivity?.let { component ->
                                JSONObject().put("taskId", task.taskId).put("packageName", component.packageName)
                            } },
                        ))
                        "switch_task" -> {
                            val taskId = integer(request, "taskId", 1, Int.MAX_VALUE)
                            switchTask(id, taskId)
                            JSONObject().put("ok", true).put("displayId", id)
                        }
                        "observe" -> run {
                            frameStore.lastDemandAt = SystemClock.elapsedRealtime()
                            frameStore.frame
                        }?.let { frame ->
                            JSONObject().put("ok", true).put("displayId", id)
                                .put("width", dispW).put("height", dispH)
                                .put("captureWidth", capW).put("captureHeight", capH)
                                .put("frameId", frame.id)
                                .put("mimeType", "image/jpeg")
                                .put(
                                    "frameAgeMs",
                                    SystemClock.elapsedRealtime() - frame.id
                                )
                                .apply {
                                    if (request.optLong("afterFrameId") == frame.id) put("unchanged", true)
                                    else put("image", Base64.encodeToString(frame.jpeg, Base64.NO_WRAP))
                                }
                        } ?: failure("DISPLAY_FRAME_PENDING")

                        "launch" -> {
                            val component = request.getString("component")
                            require(
                                Regex("[a-zA-Z0-9_]+(?:\\.[a-zA-Z0-9_]+)+/[a-zA-Z0-9_.$]+").matches(
                                    component
                                )
                            ) { "INVALID_COMPONENT" }
                            val packageName = component.substringBefore('/')
                            val target = ComponentName.unflattenFromString(component)
                                ?: error("INVALID_COMPONENT")
                            val info = context.packageManager.getActivityInfo(target, 0)
                            val resizeMode =
                                ActivityInfo::class.java.getField("resizeMode").getInt(info)
                            val resizable = ActivityInfo::class.java.getDeclaredMethod(
                                "isResizeableMode",
                                Int::class.javaPrimitiveType
                            ).invoke(null, resizeMode) as Boolean
                            require(info.enabled && info.exported && resizable && info.launchMode <= ActivityInfo.LAUNCH_SINGLE_TASK) { "DISPLAY_APP_UNSUPPORTED" }
                            // Allow re-entry to our own display, but never migrate a user's task.
                            val runningElsewhere = runningTasks().any {
                                (it.baseActivity?.packageName == packageName || it.topActivity?.packageName == packageName) && taskDisplayId(it) != id
                            }
                            if (runningElsewhere) {
                                require(request.optBoolean("restartApp")) { "APP_ALREADY_RUNNING" }
                                require(packageName != selfPackage && info.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0) { "APP_RESTART_UNSUPPORTED" }
                                command(listOf("/system/bin/am", "force-stop", packageName))
                                require(runningTasks().none {
                                    it.baseActivity?.packageName == packageName || it.topActivity?.packageName == packageName
                                }) { "APP_STOP_FAILED" }
                            }
                            val uri = request.optString("uri")
                            require(uri.length <= 8192 && !uri.contains('\u0000')) { "INVALID_URI" }
                            val output = command(
                                listOf(
                                    "/system/bin/am",
                                    "start",
                                    "--display",
                                    id.toString(),
                                    "-f",
                                    "0x18800000",
                                    "-n",
                                    component
                                ) + if (uri.isNotBlank()) listOf("-a", "android.intent.action.VIEW", "-d", uri) else emptyList()
                            )
                            require(!output.contains("Error:") && !output.contains("Warning: Activity not started")) { "DISPLAY_LAUNCH_REJECTED" }
                            val launched =
                                runningTasks().filter { it.baseActivity?.packageName == packageName || it.topActivity?.packageName == packageName }
                            require(launched.isNotEmpty() && launched.all {
                                taskDisplayId(it) == id
                            }) { "DISPLAY_LAUNCH_MISMATCH" }
                            JSONObject().put("ok", true).put("displayId", id)
                                .put("component", component)
                        }

                        "touch" -> {
                            val phase = request.getString("phase")
                            val tx = request.optDouble("x", 0.0).toFloat().coerceIn(0f, (dispW - 1).toFloat())
                            val ty = request.optDouble("y", 0.0).toFloat().coerceIn(0f, (dispH - 1).toFloat())
                            val taken = when (phase) {
                                "down" -> touchInjector.down(id, tx, ty)
                                "move" -> touchInjector.move(id, tx, ty)
                                "cancel" -> touchInjector.up(id, tx, ty, true)
                                "up" -> touchInjector.up(id, tx, ty)
                                else -> false
                            }
                            // 注入不可用时按老办法补一次点击，至少保证点得动。
                            if (!taken && (phase == "up" || phase == "cancel")) {
                                input(id, "tap", tx.toInt().toString(), ty.toInt().toString())
                            }
                            JSONObject().put("ok", true).put("displayId", id)
                                .put("phase", phase).put("injected", taken)
                        }

                        "tap" -> {
                            input(
                                id,
                                "tap",
                                integer(request, "x", 0, dispW - 1).toString(),
                                integer(request, "y", 0, dispH - 1).toString()
                            ); JSONObject().put("ok", true).put("displayId", id)
                        }

                        "text" -> textInput.insert(request.getString("text"), request.optBoolean("replace"))

                        "swipe" -> {
                            input(
                                id,
                                "swipe",
                                integer(request, "x1", 0, dispW - 1).toString(),
                                integer(request, "y1", 0, dispH - 1).toString(),
                                integer(request, "x2", 0, dispW - 1).toString(),
                                integer(request, "y2", 0, dispH - 1).toString(),
                                integer(request, "durationMs", 100, 2000).toString()
                            ); JSONObject().put("ok", true).put("displayId", id)
                        }

                        "back" -> {
                            input(id, "keyevent", "4")
                            // 按返回退到「桌面」时，灵屏上必须有东西可显示。
                            // 建屏时已经不再自动拉起灵屏主页，如果不在这里兜底，
                            // App 退出后这块屏就是纯黑（实测接管页按一次返回就黑屏）。
                            handler.postDelayed({
                                runCatching {
                                    val top = runningTasks().firstOrNull { taskDisplayId(it) == id }
                                        ?.topActivity?.packageName.orEmpty()
                                    if (top.isBlank() || top == "android" || top.endsWith("launcher")) {
                                        VscrDiag.line("灵屏 返回退到桌面，拉起灵屏主页兜底")
                                        startVirtualHome(id, setup.optString("sessionId"), "HOME")
                                    }
                                }
                            }, 350L)
                            JSONObject().put("ok", true).put("displayId", id)
                        }

                        "key" -> {
                            val button = request.getString("button")
                            val code = when (button) {
                                "BACK" -> 4
                                "ENTER" -> 66
                                "DEL" -> 67
                                "HOME", "MENU", "APPS" -> {
                                    // 灵屏里按 Home/应用键直接回系统桌面：不再用 Eta 自己的导航页接管这个 display。
                                    val sid = setup.optString("sessionId")
                                    require(Regex("[a-fA-F0-9-]{36}").matches(sid)) { "DISPLAY_OWNER_REQUIRED" }
                                    startVirtualHome(id, sid, button)
                                    null
                                }
                                else -> error("VIRTUAL_ACTION_UNSUPPORTED")
                            }
                            if (code != null) input(id, "keyevent", code.toString())
                            JSONObject().put("ok", true).put("displayId", id)
                        }

                        "long_press" -> {
                            val x = integer(request, "x", 0, dispW - 1).toString()
                            val y = integer(request, "y", 0, dispH - 1).toString()
                            input(id, "swipe", x, y, x, y, integer(request, "durationMs", 300, 3000).toString())
                            JSONObject().put("ok", true).put("displayId", id)
                        }

                        else -> failure("UNKNOWN_DISPLAY_ACTION")
                    }
                    if (request.optString("action") !in setOf("observe", "probe", "tasks", "lifecycle")) idleTimer.touch()
                    reply(result)
                } catch (error: Exception) {
                    reply(
                        failure(
                            if (error is IllegalArgumentException || error is IllegalStateException) error.message
                                ?: "DISPLAY_ACTION_FAILED" else "DISPLAY_ACTION_FAILED"
                        ).apply {
                            put("error_type", error.javaClass.simpleName)
                            if (request.optString("action") == "launch") {
                                put("component", request.optString("component"))
                                put("uri", request.optString("uri"))
                            }
                        }
                    )
                }
            }
        } catch (error: Exception) {
            error.printStackTrace()
            reply(failure("ROOT_DISPLAY_UNAVAILABLE"))
        } finally {
            // 正常收尾同样留痕：配合 App 侧记录的退出码，区分主动退出与异常退出。
            System.err.println("ETA_HELPER_EXIT")
            System.err.flush()
            runCatching { finishTasksOnDisplay(activeDisplayId, selfPackage) }
            runCatching { display?.release() }
            runCatching { reader?.close() }
            frames?.quitSafely()
            if (wakeLock?.isHeld == true) runCatching { wakeLock?.release() }
            if (displayWakeLock?.isHeld == true) runCatching { displayWakeLock?.release() }
        }
    }

    private data class EncodedFrame(val jpeg: ByteArray, val id: Long)

    /** 画面采样指纹：按固定间隔取像素做 FNV 哈希，只读不改，开销远小于整帧拷贝与 JPEG 压缩。 */
    private fun sampleSignature(buffer: java.nio.ByteBuffer, samples: Int = 512): Long {
        val limit = buffer.limit()
        if (limit < 16) return 0L
        val stride = ((limit - 4) / samples).coerceAtLeast(4)
        var hash = -0x340d631b7bdddcdbL
        var index = 0
        var i = 0
        while (i < samples && index + 4 <= limit) {
            hash = (hash xor (buffer.getInt(index).toLong() and 0xffffffffL)) * 0x100000001b3L
            index += stride
            i++
        }
        return hash
    }

    private class FrameStore {
        @Volatile var frame: EncodedFrame? = null

        /** 最近一次客户端取帧时间；长时间没人看就不再编码。 */
        @Volatile var lastDemandAt: Long = 0L
        @Volatile var lastSignature: Long = 0L

        /** 上一帧 JPEG 编码实际耗时（ms）：观看态用它给目标间隔兜底。 */
        @Volatile var lastEncodeCostMs: Long = 0L

        /** 累计短读帧数（拷贝缓冲小于一帧）：用于判断是不是持续异常。 */
        @Volatile var shortReads: Long = 0L

        private var scaledBitmap: Bitmap? = null

        /** 把全尺寸帧缩到传输尺寸；目标位图复用，避免每帧新分配。 */
        fun scaled(src: Bitmap, width: Int, height: Int): Bitmap? {
            val cached = scaledBitmap
            val target = if (cached != null && !cached.isRecycled && cached.width == width && cached.height == height) {
                cached
            } else {
                runCatching { cached?.recycle() }
                runCatching { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }
                    .getOrNull()?.also { scaledBitmap = it } ?: return null
            }
            return runCatching {
                android.graphics.Canvas(target).apply {
                    drawBitmap(src, null, android.graphics.Rect(0, 0, width, height), android.graphics.Paint().apply {
                        isFilterBitmap = true
                        isAntiAlias = true
                    })
                }
                target
            }.getOrNull()
        }
        private var paddedBitmap: Bitmap? = null
        private var frameBytes: ByteArray? = null

        /** 复用整帧拷贝缓冲，避免每帧新分配数兆字节触发 GC。 */
        fun copyBuffer(size: Int): ByteArray? {
            val cached = frameBytes
            if (cached != null && cached.size == size) return cached
            return runCatching { ByteArray(size) }.getOrNull()?.also { frameBytes = it }
        }

        val lastEncoded: Long get() = frame?.id ?: 0

        fun differs(signature: Long): Boolean = frame == null || signature != lastSignature

        /** 复用整帧位图，避免每帧新分配大块内存；只在帧线程上换新并回收旧对象。 */
        fun padded(width: Int, height: Int): Bitmap? {
            val cached = paddedBitmap
            if (cached != null && !cached.isRecycled && cached.width == width && cached.height == height) {
                return cached
            }
            paddedBitmap = null
            runCatching { cached?.recycle() }
            return runCatching { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }
                .getOrNull()?.also { paddedBitmap = it }
        }

        /** 切尺寸后整体丢弃缓存：否则每切换一次就多留一份整帧位图与拷贝缓冲。 */
        fun reset() {
            frame = null
            val old = paddedBitmap
            val oldScaled = scaledBitmap
            paddedBitmap = null
            scaledBitmap = null
            frameBytes = null
            runCatching { old?.recycle() }
            runCatching { oldScaled?.recycle() }
        }
    }

    /** 在灵屏上拉起本应用自己的灵屏桌面（应用导航页）。 */
    private fun startVirtualHome(displayId: Int, sessionId: String, mode: String): String {
        val home = VirtualScreenHomeActivity::class.java.name
        val output = command(listOf("/system/bin/am", "start", "--display", displayId.toString(),
            "-f", "0x10820000", "--activity-single-top", "-n", "$selfPackage/$home",
            "--es", "sessionId", sessionId, "--es", "mode", mode))
        require(!output.contains("Error:")) { "DISPLAY_LAUNCH_REJECTED" }
        // 任务栈落地是异步的：轮询最多约 1.8s 再判定，避免刚起屏就把自桌面当成失败。
        var tries = 0
        while (tries < 12) {
            if (runningTasks().any { it.topActivity?.className == home && taskDisplayId(it) == displayId }) return selfPackage
            Thread.sleep(150)
            tries++
        }
        error("DISPLAY_LAUNCH_MISMATCH")
    }

    /** 在灵屏上拉起系统桌面，返回桌面包名；解析不出来时返回空串。 */
    private fun startSystemHome(displayId: Int): String {
        val output = command(listOf("/system/bin/am", "start", "--display", displayId.toString(),
            "-f", "0x10820000", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME"))
        require(!output.contains("Error:")) { "DISPLAY_LAUNCH_REJECTED" }
        val homePackage = command(listOf("/system/bin/cmd", "package", "resolve-activity", "--brief",
            "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME"))
            .lineSequence().map { it.trim() }.lastOrNull { it.contains("/") }
            ?.substringBefore("/")
            .orEmpty()
        if (homePackage.isNotEmpty()) {
            require(runningTasks().any { it.topActivity?.packageName == homePackage && taskDisplayId(it) == displayId }) {
                "DISPLAY_LAUNCH_MISMATCH"
            }
        }
        return homePackage
    }

    private fun flag(name: String) = DisplayManager::class.java.getField(name).getInt(null)

    @SuppressLint("BlockedPrivateApi")
    private fun configureLocalIme(displayId: Int): Boolean = runCatching {
        val service = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null)
        val type = Class.forName("android.view.IWindowManager")
        // IWindowManager 的取值：LOCAL=0，FALLBACK_DEFAULT_DISPLAY=1，HIDE=2。
        // 要“灵屏里点输入框、真机屏幕弹键盘”，只能用 1（IME 交给默认屏显示）；
        // 之前用的 2 是 HIDE，等于把灵屏的输入法整个关掉，所以点了输入框什么都不弹。
        val set = type.getMethod("setDisplayImePolicy", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        val get = type.getMethod("getDisplayImePolicy", Int::class.javaPrimitiveType)
        set.invoke(service, displayId, 1)
        var policy = get.invoke(service, displayId) as Int
        if (policy != 1) {
            set.invoke(service, displayId, 0)
            policy = get.invoke(service, displayId) as Int
        }
        hlog("ime policy want=1 got=" + policy)
        policy == 1
    }.onFailure { hlog("ime policy fail " + it) }.getOrDefault(false)

    private fun taskDisplayId(task: ActivityManager.RunningTaskInfo): Int =
        android.app.TaskInfo::class.java.getField("displayId").getInt(task)

    // This runs only in the UID 0 helper; application-process hidden API limits do not apply.
    @SuppressLint("BlockedPrivateApi")
    private fun switchTask(displayId: Int, taskId: Int) {
        require(runningTasks().any { it.taskId == taskId && taskDisplayId(it) == displayId }) { "VIRTUAL_TASK_UNAVAILABLE" }
        val service = Class.forName("android.app.ActivityTaskManager").getDeclaredMethod("getService").invoke(null)
        val method = Class.forName("android.app.IActivityTaskManager").getMethod(
            "startActivityFromRecents", Int::class.javaPrimitiveType, android.os.Bundle::class.java,
        )
        val result = method.invoke(service, taskId, ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()) as Int
        require(result >= 0 && runningTasks().any { it.taskId == taskId && taskDisplayId(it) == displayId }) { "VIRTUAL_TASK_UNAVAILABLE" }
    }

    // Called only by the UID 0 app_process helper, never in Eta's application process.
    // An unavailable task API aborts launch before any activity or input is sent.
    @SuppressLint("BlockedPrivateApi")
    @Suppress("UNCHECKED_CAST")
    private fun runningTasks(): List<ActivityManager.RunningTaskInfo> {
        val type = Class.forName("android.app.ActivityTaskManager")
        val manager = type.getDeclaredMethod("getInstance").invoke(null)
        return type.getDeclaredMethod("getTasks", Int::class.javaPrimitiveType)
            .invoke(manager, 1000) as List<ActivityManager.RunningTaskInfo>
    }

    /**
     * 关闭灵屏前把里面的任务一并结束，否则应用只是被“藏起来”，还会在后台继续跑。
     * 先 removeTask（等于从最近任务划掉，销毁 Activity、停掉播放），
     * 仍残留的包再退回 force-stop；Eta 自己的任务永远不动。
     */
    @SuppressLint("BlockedPrivateApi")
    private fun finishTasksOnDisplay(displayId: Int, selfPackage: String) {
        val taskIds = runCatching {
            runningTasks().filter { taskDisplayId(it) == displayId }.map { it.taskId }
        }.getOrDefault(emptyList())
        if (taskIds.isEmpty()) return
        runCatching {
            val service = Class.forName("android.app.ActivityTaskManager").getDeclaredMethod("getService").invoke(null)
            val remove = Class.forName("android.app.IActivityTaskManager")
                .getMethod("removeTask", Int::class.javaPrimitiveType)
            taskIds.forEach { taskId -> runCatching { remove.invoke(service, taskId) } }
        }
        Thread.sleep(400)
        val leftover = runCatching {
            runningTasks().filter { taskDisplayId(it) == displayId }
                .mapNotNull { info -> info.topActivity?.packageName }
                .filter { it.isNotBlank() && it != selfPackage }
                .toSet()
        }.getOrDefault(emptySet())
        leftover.forEach { packageName -> runCatching { command(listOf("am", "force-stop", packageName)) } }
    }

    /**
     * 读取指定 display 顶层 Activity 请求的屏幕方向。
     * true=请求横屏，false=请求竖屏，null=跟随当前形状（sensor/unspecified 等，不做改动）。
     */
    private fun requestedLandscape(displayId: Int): Boolean? {
        val dump = runCatching {
            command(listOf("/system/bin/dumpsys", "activity", "activities"), 600_000)
        }.getOrNull() ?: return null
        val header = "Display #$displayId (activities from top to bottom)"
        val start = dump.indexOf(header)
        if (start < 0) return null
        val next = dump.indexOf("Display #", start + header.length)
        val section = dump.substring(start, if (next < 0) dump.length else next)
        val value = Regex("requestedOrientation=(SCREEN_ORIENTATION_[A-Z_]+)")
            .find(section)?.groupValues?.get(1) ?: return null
        return when (value) {
            "SCREEN_ORIENTATION_LANDSCAPE", "SCREEN_ORIENTATION_SENSOR_LANDSCAPE",
            "SCREEN_ORIENTATION_REVERSE_LANDSCAPE", "SCREEN_ORIENTATION_USER_LANDSCAPE" -> true
            "SCREEN_ORIENTATION_PORTRAIT", "SCREEN_ORIENTATION_REVERSE_PORTRAIT",
            "SCREEN_ORIENTATION_SENSOR_PORTRAIT", "SCREEN_ORIENTATION_USER_PORTRAIT" -> false
            else -> null
        }
    }

    private fun integer(args: JSONObject, name: String, min: Int, max: Int): Int {
        val number = args.get(name)
        require(
            number is Number && number.toDouble() == number.toInt()
                .toDouble() && number.toInt() in min..max
        ) { "INVALID_ARGUMENT" }
        return number.toInt()
    }

    @Volatile
    private var lastOrientation: Boolean? = null
    @Volatile
    private var lastOrientationAtMs = 0L

    private val touchInjector = RootTouchInjector()

    private fun input(id: Int, vararg args: String) {
        val kind = args.firstOrNull()
        if (kind == "tap" || kind == "swipe") {
            val points = args.drop(1).mapNotNull { it.toFloatOrNull() }
            val taken = runCatching {
                if (kind == "tap") {
                    points.size >= 2 && touchInjector.startTap(id, points[0], points[1])
                } else {
                    points.size >= 4 &&
                        touchInjector.startSwipe(id, points[0], points[1], points[2], points[3], (points.getOrNull(4) ?: 300f).toLong())
                }
            }.getOrDefault(false)
            if (taken) return
        }
        val source = if (kind in setOf("tap", "swipe")) "touchscreen" else "keyboard"
        command(listOf("/system/bin/input", source, "-d", id.toString()) + args)
    }

    private fun command(args: List<String>, limit: Int = 16_000): String {
        val process = ProcessBuilder(args).redirectErrorStream(true).start()
        val bytes = ByteArrayOutputStream()
        val drain = Thread {
            process.inputStream.use { stream ->
                val buffer = ByteArray(4096); while (true) {
                val n =
                    stream.read(buffer); if (n < 0) break; if (bytes.size() + n <= limit) bytes.write(
                    buffer,
                    0,
                    n
                ) else {
                    process.destroy(); break
                }
            }
            }
        }.apply { start() }
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly(); error("DISPLAY_ACTION_TIMEOUT")
        }
        drain.join(1000)
        require(process.exitValue() == 0) { "DISPLAY_ACTION_FAILED" }
        return bytes.toString("UTF-8")
    }

    private fun failure(code: String) = JSONObject().put("ok", false).put("code", code)
        .put("message", "灵屏操作未执行；不会回退到主屏")

    private fun reply(value: JSONObject) {
        System.out.println(MARKER + value.toString()); System.out.flush()
    }
}
