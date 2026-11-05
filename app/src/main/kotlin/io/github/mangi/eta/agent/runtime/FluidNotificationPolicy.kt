package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.data.datastore.SettingsDataStore
import kotlinx.coroutines.runBlocking

/**
 * 「流体云通知降级」开关的进程内快照。
 *
 * 通知卡每秒都可能重建，不能每次构建都去读 DataStore；所以这里缓存一份值，
 * 只在首次访问、设置页切换、运行时刷新流体云时重新读取。
 *
 * 开启后，各处通知不再调用 `setShortCriticalText` / `setRequestPromotedOngoing`，
 * 也就是不再申请 promoted ongoing：ColorOS 不会把它放进流体云，
 * 只在通知栏按普通常驻通知呈现，锁屏与状态栏也不会被胶囊接管。
 */
internal object FluidNotificationPolicy {

    @Volatile
    private var downgraded = false

    @Volatile
    private var loaded = false

    /** 是否已把流体云通知降级为普通通知。 */
    fun isDowngraded(): Boolean {
        if (!loaded) refresh()
        return downgraded
    }

    /** 还允许走流体云（未开启降级）。 */
    fun allowsFluid(): Boolean = !isDowngraded()

    /** 从设置重新读一次，返回当前值。 */
    fun refresh(): Boolean {
        downgraded = runCatching {
            runBlocking { SettingsDataStore.settings().overlayFluidDowngrade }
        }.getOrDefault(false)
        loaded = true
        return downgraded
    }

    /** 设置页已经拿到新值，直接写入，省掉一次 IO。 */
    fun set(value: Boolean) {
        downgraded = value
        loaded = true
    }
}
