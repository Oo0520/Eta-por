package io.github.mangi.eta.ui.onboarding

/**
 * 引导进度在 `Settings.seenTourPacks` 里的键。
 *
 * 集中在一处：设置页的「重置新手引导」按这份清单清，加新引导时不会漏。
 */
internal object OnboardingKeys {
    const val SwipeRight = "gesture.swipeRight"
    const val SwipeLeft = "gesture.swipeLeft"
    const val SwipeUp = "gesture.swipeUp"
    const val SidebarHint = "sidebar.hint"
    const val WorkspaceHint = "workspace.hint"

    val All: Set<String> = setOf(
        SwipeRight,
        SwipeLeft,
        SwipeUp,
        SidebarHint,
        WorkspaceHint,
    )
}
