package io.github.mangi.eta.ui.onboarding

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.navigation.AppRoute

/**
 * 一步引导。
 *
 * 锚点用字符串 id 而不是坐标：控件用 `Modifier.tourAnchor(id)` 报名，位置随布局实时更新。
 */
internal data class TourStep(
    /** 锚点 id；手势演示步骤不指向具体控件，留空即可。 */
    val anchor: String = "",
    val title: String,
    val body: String,
    /** 手势包专用：这一步演示往哪个方向滑。聚光灯包忽略。 */
    val gesture: GestureDirection? = null,
    /**
     * 进入这一步之前先跳到该路由。
     *
     * 跨页步骤要传：不传的话气泡只会指着当前页上不存在的锚点，然后被超时跳过。
     * 传了但宿主没给导航回调时同样按「跳过该步」处理，不会卡住整条引导。
     */
    val navigateTo: AppRoute? = null,
    /** 气泡相对锚点的位置；Auto 在上下空间不足时自动翻到另一侧。 */
    val placement: TourPlacement = TourPlacement.Auto,
    /** 放行锚点范围内的触摸：需要对真实控件做点击示范时打开，默认拦截整屏。 */
    val allowTouch: Boolean = false,
    /** 挖洞的形状与留边。 */
    val shape: TourHoleShape = TourHoleShape.RoundedRect(cornerRadius = 24.dp, padding = 6.dp),
)

internal enum class TourPlacement { Auto, Above, Below }

/** 手势演示方向：往右拉出侧栏，往左进工作区。 */
internal enum class GestureDirection { SwipeRight, SwipeLeft, SwipeUp }

/** 包的渲染形态。 */
internal enum class TourStyle {
    /** 聚光灯气泡：指着某个控件讲。 */
    Spotlight,

    /** 边缘手势演示：不暗化、不拦触摸，让用户当场滑。 */
    Gesture,

    /**
     * 只浮一张说明卡：不挖洞、不遮罩、不拦触摸。
     *
     * 给「遮罩坐标系对不上」的场景兜底——工作区面板是被 graphicsLayer 平移的，
     * 布局坐标还在原位，挖洞会挖在左半屏而面板显示在右边。与其硬凑坐标，不如不挖。
     */
    Hint,
}

internal sealed interface TourHoleShape {
    /** 卡片类控件用圆角矩形；圆形头像、FAB 用 [Circle]。 */
    data class RoundedRect(val cornerRadius: Dp, val padding: Dp) : TourHoleShape

    data object Circle : TourHoleShape
}

/** 触发时机：决定一个包什么时候该弹。 */
internal sealed interface TourTrigger {
    /**
     * 首次启动（还没看过任何包）。
     *
     * 这里用「进度表为空」判断首启，不额外加字段：第一个包播完进度表就非空了。
     * 代价是如果用户跳过了第一个包，首启语义就结束——这正好符合「跳过就是不想看」。
     */
    data object FirstLaunch : TourTrigger

    /** 第一次进入某个屏；key 由 [screenKeyOf] 统一生成，避免各处各写一套。 */
    data class FirstEnterScreen(val screenKey: String) : TourTrigger

    /**
     * 第一次打开某个覆盖层。
     *
     * 侧栏与工作区挂在同一个路由（Home）里，路由不会变，靠路由触发不了；
     * 所以由宿主在覆盖层真正打开时上报 [SurfaceKey]，这里按 surface 匹配。
     */
    data class OnSurfaceOpen(val surface: String) : TourTrigger

    /**
     * 升到某个 versionCode 之后对所有人播一次（新功能上线用）。
     *
     * 与「首次」的区别：老用户也会看到，因为功能对他同样是新的。
     */
    data class AfterUpgrade(val versionCode: Int) : TourTrigger
}

/** 一个引导包：一件事，2~4 步，可单独重播。 */
internal data class TourPack(
    val id: String,
    /** 文案改版就 +1：已看过旧版本的会被重播一次。 */
    val version: Int,
    val trigger: TourTrigger,
    val steps: List<TourStep>,
    /** 默认聚光灯；手势包显式传 [TourStyle.Gesture]。 */
    val style: TourStyle = TourStyle.Spotlight,
) {
    init {
        require(steps.isNotEmpty()) { "引导包 $id 至少要有一页" }
    }
}

/** 覆盖层的稳定标识：宿主打开侧栏 / 工作区时上报，触发对应引导包。 */
internal object SurfaceKey {
    const val Sidebar = "sidebar"
    const val Workspace = "workspace"
}

/** 路由 → 屏 key。只区分「引导包关心的那几屏」，其余返回 FQCN，不会误命中。 */
internal fun screenKeyOf(route: AppRoute?): String = when (route) {
    null -> "none"
    else -> route::class.simpleName ?: "unknown"
}
