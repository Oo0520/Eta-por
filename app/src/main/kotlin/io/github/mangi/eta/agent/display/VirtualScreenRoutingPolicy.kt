package io.github.mangi.eta.agent.display

import java.util.concurrent.atomic.AtomicBoolean

/** Once a run uses virtual coordinates, revoking permission must not redirect it to the main screen. */
internal class VirtualScreenRoutingPolicy(initiallyRouted: Boolean = false) {
    private val routed = AtomicBoolean(initiallyRouted)
    private val primaryApproved = AtomicBoolean(false)
    val usesPrimary: Boolean get() = primaryApproved.get()

    fun shouldRoute(name: String, enabled: Boolean): Boolean {
        if (name !in uiTools || usesPrimary) return false
        if (!enabled) return false
        // 只有显式入口能「开启」灵屏路由。
        // 以前任何 UI 工具都会把 routed 置真，于是「打开微信」用真机打开之后，
        // 紧接着的一次 tap 又会把整段会话拽回灵屏（还顺手新建一块屏），
        // 用户看到的就是「我只是让它打开微信，怎么屏幕没了」。
        if (name == LINGSCREEN_ENTRY) {
            // 它是「开关」不是「灵屏内的工具」：本工具本身由 AgentLocalTools 执行
            // （内部调 virtualUiTools.launchComponent），不能再被路由回 VirtualScreenUiTools，
            // 否则会落到那里的 else 分支报 VIRTUAL_ACTION_UNSUPPORTED。
            routed.set(true)
            return false
        }
        // 已经有活跃灵屏会话时，UI 工具自动跟随，不必依赖入口工具来开启路由。
        // 否则用 virtual_screen 直接建屏后，observe_screen 会打到主屏（读到 Eta 自己的界面）。
        if (!routed.get() && VirtualScreenSession.hasActiveSession()) routed.set(true)
        return routed.get()
    }

    fun approvePrimary() { primaryApproved.set(true) }

    companion object {
        /** 唯一能开启灵屏路由的工具：其余 UI 工具只在它之后跟随。 */
        const val LINGSCREEN_ENTRY = "lingscreen_open_app"

        val fallbackErrors = setOf(
            "ROOT_REQUIRED", "DEVICE_UNSUPPORTED", "ROOT_DISPLAY_UNAVAILABLE", "VIRTUAL_SCREEN_FAILED",
            "DISPLAY_LIFECYCLE_UNAVAILABLE",
            "DISPLAY_GONE", "NO_VIRTUAL_SCREEN", "ROOT_DISPLAY_DISCONNECTED", "DISPLAY_FRAME_PENDING",
            "APP_ALREADY_RUNNING", "DISPLAY_APP_UNSUPPORTED", "DISPLAY_LAUNCH_REJECTED", "DISPLAY_LAUNCH_MISMATCH",
            "VIRTUAL_ACTION_UNSUPPORTED", "ACCESSIBILITY_UNAVAILABLE",
        )
        val allowedBeforePrimaryObservation = setOf("launch_app", "open_uri", "observe_screen", "wait", "wait_for_text", "wait_for_package")
        val uiTools = setOf(
            // launch_app 已改为「永远真机」，不再触发灵屏路由；
            // 需要把后续点击都投进虚拟屏时，改用 lingscreen_open_app。
            "lingscreen_open_app",
            "open_uri",
            "observe_screen",
            "tap",
            "tap_area",
            "tap_smart",
            "tap_element",
            "long_press",
            "long_press_element",
            "swipe",
            "scroll",
            "scroll_element",
            "type_text",
            "input_text",
            "replace_text",
            "clear_text",
            "set_clipboard",
            "get_clipboard",
            "paste_text",
            "press_key",
            "wait",
            "wait_for_text",
            "wait_for_package",
            "open_system_panel",
        )
        // browser_use 操作的是 App 内自有的离屏 WebView，不经灵屏窗口转发，
        // 因此不应算作 UI 工具，也不能因为灵屏开启而被判为不可用。
        // 闹钟与计时器走 AlarmClock + EXTRA_SKIP_UI，不产生界面，灵屏会话里照常可用；
        // 只有真正要把系统面板拉到屏幕上的 open_system_panel 仍然禁用。
        val unavailableTools = setOf("open_system_panel")
        val coordinateTools = setOf(
            "observe_screen",
            "tap",
            "tap_area",
            "tap_smart",
            "long_press",
            "swipe",
            "scroll",
            "press_key",
            "wait_for_package",
            "type_text", "input_text", "replace_text", "clear_text", "paste_text", "set_clipboard", "get_clipboard",
        )
        val nodeTools = setOf("tap_element", "long_press_element", "scroll_element", "wait_for_text")
    }
}
