package io.github.mangi.eta.ui.onboarding

/**
 * 手势教学的脚本：三步，按顺序教，做到哪一步记哪一步。
 *
 * 与旧的 `TourPack` 分开：手势不需要锚点、不需要步骤按钮，
 * 只需要「教哪个方向 + 用户有没有真的做过」。
 */
internal object GestureCoachScript {

    data class Step(
        /** 记录用的键：做到就写进 `seenTourPacks`。 */
        val key: String,
        val direction: GestureDirection,
        val title: String,
        val body: String,
    )

    val steps: List<Step> = listOf(
        Step(
            key = OnboardingKeys.SwipeRight,
            direction = GestureDirection.SwipeRight,
            title = "往右滑，拉出会话列表",
            body = "在聊天页任意位置往右拖都行，不用找边缘。不想滑就点左上角那个按钮。",
        ),
        Step(
            key = OnboardingKeys.SwipeLeft,
            direction = GestureDirection.SwipeLeft,
            title = "往左滑，进工作区",
            body = "反过来往左拖就是文件与预览，两个方向随时来回。",
        ),
        Step(
            key = OnboardingKeys.SwipeUp,
            direction = GestureDirection.SwipeUp,
            title = "上滑小白条，打开会话导航",
            body = "输入框上方那条小白条，上滑或点一下能跳到本会话任意一条消息，长按其中一条可以直接引用。",
        ),
    )

    /** 下一个该教的方向；三步都做过返回 null。 */
    fun next(done: Map<String, Int>): Step? = steps.firstOrNull { (done[it.key] ?: 0) < 1 }
}
