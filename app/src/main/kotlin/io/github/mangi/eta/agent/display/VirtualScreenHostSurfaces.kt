package io.github.mangi.eta.agent.display

import android.view.Surface
import java.util.UUID

/**
 * 直通出帧（A′）：把本机 Surface 交给 root helper，当灵屏的输出面。
 * 画面由 SurfaceFlinger 直接合成进本 Surface，不再走截屏→JPEG→base64→解码。
 *
 * 多开后每个会话一块屏，宿主面按 **会话（owner）** 分槽：
 * 只有拿到自己 owner 那个 Surface 的 helper 才走直通，其余屏自动回落到整帧 JPEG，
 * 不会互相抢同一块 BufferQueue。
 */
internal object VirtualScreenHostSurfaces {
    /** 与 AndroidManifest 的 ${applicationId}.hostsurface 对应；正式版 io.github.mangi.eta.hostsurface。 */
    const val AUTHORITY = "io.github.mangi.eta.hostsurface"

    const val EXTRA_OWNER = "owner"

    val token: String = UUID.randomUUID().toString()

    /** "$owner|$host" → Surface。 */
    private val slots = LinkedHashMap<String, Surface>()
    private val activeHostByOwner = HashMap<String, String>()
    private val liveOwners = HashSet<String>()

    /** 当前焦点会话：浮窗/查看页显示的就是它，未显式指定 owner 的调用都落到这里。 */
    @Volatile
    var focusedOwner: String? = null
        private set

    /** 最近一次真正下发过的 (host, surface) 指纹，避免重复下发。 */
    @Volatile
    private var sentKey: String? = null

    /** 活动 host 变化（含置空）时回调，由持有会话的一侧去下发指令。 */
    @Volatile
    var onActiveChanged: (() -> Unit)? = null

    fun setFocusOwner(owner: String?) {
        if (focusedOwner == owner) return
        focusedOwner = owner
        onActiveChanged?.invoke()
    }

    fun attach(owner: String, host: String, surface: Surface?) {
        synchronized(slots) {
            val key = "$owner|$host"
            if (surface == null) {
                slots.remove(key)
                if (activeHostByOwner[owner] == host) {
                    val fallback = slots.keys
                        .filter { it.startsWith("$owner|") }
                        .lastOrNull()
                        ?.substringAfter('|')
                    if (fallback != null) activeHostByOwner[owner] = fallback
                    else activeHostByOwner.remove(owner)
                }
            } else {
                slots[key] = surface
                activeHostByOwner[owner] = host
            }
        }
        onActiveChanged?.invoke()
    }

    /** 兼容旧调用：没有 owner 的宿主面落到空槽，只对没带 owner 的 helper 生效。 */
    fun attach(host: String, surface: Surface?) = attach("", host, surface)

    fun currentHost(owner: String = focusedOwner.orEmpty()): String? = synchronized(slots) {
        activeHostByOwner[owner]
    }

    fun currentSurface(owner: String = focusedOwner.orEmpty()): Surface? = synchronized(slots) {
        activeHostByOwner[owner]?.let { slots["$owner|$it"] }
    }

    /** helper 按自己的 owner 取面；取不到就回落整帧管线。 */
    fun surfaceFor(owner: String): Surface? = currentSurface(owner)

    /** (host, surface 代次) 指纹：同一次交接不重复下发。 */
    fun key(): String = "$token:${focusedOwner.orEmpty()}:${currentHost() ?: ""}:${currentSurface()?.hashCode() ?: 0}"

    fun markSent(value: String) {
        sentKey = value
    }

    fun alreadySent(): Boolean = sentKey == key()

    fun resetSent() {
        sentKey = null
    }

    fun setLive(owner: String, value: Boolean) {
        synchronized(liveOwners) {
            if (value) liveOwners.add(owner) else liveOwners.remove(owner)
        }
    }

    /** 任一屏直通中即为 true：浮窗据此判断要不要切直通渲染。 */
    val live: Boolean get() = synchronized(liveOwners) { liveOwners.isNotEmpty() }

    fun isLive(owner: String): Boolean = synchronized(liveOwners) { owner in liveOwners }
}
