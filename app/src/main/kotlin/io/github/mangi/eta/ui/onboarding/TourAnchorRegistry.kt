package io.github.mangi.eta.ui.onboarding

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 引导锚点登记处：控件自己报名「我在这儿」，引导层只按 id 取矩形。
 *
 * 为什么不写死坐标：Eta 的顶栏高度会随模式胶囊、语音按钮、返回箭头变化，
 * 侧栏宽度会随抽屉动画变化，输入框高度会随附件条与引用条变化。写死的坐标必然漂，
 * 而 [tourAnchor] 在每次布局后都会更新最新位置，引导层永远拿的是当帧的值。
 *
 * 坐标系是 root（同一 window）。Dialog / Popup 里的控件不在同一棵 root 上，
 * 涉及它们的步骤要单独量，不要和这里的矩形混用。
 *
 * 第一版只登记不主动注销：id 是稳定字符串，重复登记会覆盖旧值；
 * 锚点丢失的场景由 [awaitAnchor] 的等待超时兜底（等不到就跳过该步），
 * 不做「清空整表」是因为清了不会触发布局，反而让已渲染的控件不再上报。
 */
internal object TourAnchors {
    private val rects = mutableStateMapOf<String, Rect>()

    fun put(id: String, rect: Rect) {
        rects[id] = rect
    }

    fun get(id: String): Rect? = rects[id]

    fun clear() {
        rects.clear()
    }
}

/** 把当前控件登记为引导锚点。应在被指的控件自身 modifier 上调用。 */
internal fun Modifier.tourAnchor(id: String): Modifier = onGloballyPositioned { coordinates ->
    if (coordinates.isAttached) {
        TourAnchors.put(id, coordinates.boundsInRoot())
    }
}

/**
 * 同 [tourAnchor]，但只在还没有登记时写入。
 *
 * 给「列表里每一行都渲染同一个 modifier」的场景用：否则最后一行会覆盖前面的，
 * 锚点可能落到屏幕外面去。第一条渲染出来的行通常就是最靠上的那条，指向它最合理。
 */
internal fun Modifier.tourAnchorFirst(id: String): Modifier = onGloballyPositioned { coordinates ->
    if (coordinates.isAttached && TourAnchors.get(id) == null) {
        TourAnchors.put(id, coordinates.boundsInRoot())
    }
}

/**
 * 等锚点出现并拿到有效矩形；超时返回 null。
 *
 * 页内步骤给 500ms 够用；跨路由步骤（先导航再等目标页渲染）要给更长的超时，
 * 由调用方传参决定。返回 null 时调用方应跳过该步而不是让整个引导卡住。
 */
internal suspend fun awaitAnchor(id: String, timeoutMillis: Long = 1200L): Rect? =
    withTimeoutOrNull(timeoutMillis) {
        var rect = TourAnchors.get(id)
        while (rect == null || rect.width <= 0f || rect.height <= 0f) {
            delay(32L)
            rect = TourAnchors.get(id)
        }
        rect
    }
