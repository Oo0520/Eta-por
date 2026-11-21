package io.github.mangi.eta.agent.virtualdisplay

/**
 * 虚拟屏会话槽位表（多开的地基）。
 *
 * 上游原本只有一块屏：`VirtualDisplaySessionState` 单槽、`token` 认身份。这里把「槽位」
 * 拆成按 owner 索引的表，语义沿用 fork（灵屏）那套：
 *
 * - **owner = 会话 id**，每个会话一块屏；同一会话内仍只允许一个 run（租约互斥，防两路输入互踩）；
 * - **上限 [MAX_SESSIONS]**，到顶时只能回收**别的会话**里判为空闲的槽；
 * - `focusedOwner` 只是「当前查看的是哪块」，不影响别的屏继续跑。
 *
 * 现在控制器只使用 [primary] 这一个默认槽，行为与上游完全一致；多开是在这层之上
 * 逐个把 `displayResources` / host surface 拆成按 owner 存。
 */
internal class VirtualDisplaySessionRegistry(
    private val maxSessions: Int = MAX_SESSIONS,
) {
    private val slots = LinkedHashMap<String, VirtualDisplaySessionState>()

    var focusedOwner: String? = null
        private set

    /** 取某个 owner 的槽位，没有就建一个；不改变它是否已激活。 */
    fun slot(owner: String): VirtualDisplaySessionState = synchronized(this) {
        slots.getOrPut(owner) { VirtualDisplaySessionState() }.also {
            if (focusedOwner == null) focusedOwner = owner
        }
    }

    /** 默认槽：控制器当前唯一在用的那一块。 */
    fun primary(): VirtualDisplaySessionState = slot(DEFAULT_OWNER)

    fun activeOwner(): String? = synchronized(this) {
        slots.entries.firstOrNull { it.value.active != null }?.key
    }

    fun activeOwners(): List<String> = synchronized(this) {
        slots.filterValues { it.active != null }.keys.toList()
    }

    fun activeOf(owner: String): VirtualDisplaySessionState.Active? = synchronized(this) {
        slots[owner]?.active
    }

    /** 该 owner 还能不能开新屏：自己已有活屏就直接放行，否则看总上限。 */
    fun canOpenNew(owner: String): Boolean = synchronized(this) {
        slots[owner]?.active != null || slots.count { it.value.active != null } < maxSessions
    }

    /**
     * 到上限时挑一个**别的 owner** 的空闲槽回收，返回被回收的 owner；没有可回收的返回 null。
     * [isIdle] 由调用方给判据（例如「没有在跑的 run」）。
     */
    fun reclaimIdle(requester: String, isIdle: (String) -> Boolean): String? = synchronized(this) {
        val victim = slots.entries.firstOrNull { (owner, state) ->
            owner != requester && state.active != null && isIdle(owner)
        } ?: return null
        victim.value.invalidate()
        victim.key
    }

    /** 关掉某个 owner 的槽；返回是否确实关掉了一块活屏。 */
    fun closeOwner(owner: String): Boolean = synchronized(this) {
        val state = slots[owner] ?: return false
        val wasActive = state.active != null
        state.invalidate()
        slots.remove(owner)
        if (focusedOwner == owner) {
            focusedOwner = slots.keys.firstOrNull()
        }
        wasActive
    }

    fun focus(owner: String): Boolean = synchronized(this) {
        if (slots[owner]?.active == null) return false
        focusedOwner = owner
        true
    }

    companion object {
        /** 与会话级多开的上限保持一致：两块屏足够对照，再多屏系统调度扛不住。 */
        const val MAX_SESSIONS = 2

        /** 上游单屏时代的默认 owner；多开后仍作为「没有指定会话」时的落点。 */
        const val DEFAULT_OWNER = "default"
    }
}
