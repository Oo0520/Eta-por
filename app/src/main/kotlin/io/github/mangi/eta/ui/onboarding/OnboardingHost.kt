package io.github.mangi.eta.ui.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.Settings
import io.github.mangi.eta.ui.navigation.AppRoute

/**
 * 引导宿主：只负责手势教学。
 *
 * 完成判定不靠触摸监听，而是靠「目标界面真的被打开了」：
 * - 右滑 → 侧栏打开（`surface == Sidebar`）
 * - 左滑 → 工作区打开（`surface == Workspace`）
 * - 上滑小白条 → 会话导航面板打开（`navigatorOpen`）
 *
 * 这样教练层完全不需要 pointerInput，也就不会抢走侧栏的拖动手势。
 *
 * 【重要】所有 `remember` / `LaunchedEffect` 都写在最前面，任何 early return 之前。
 * 之前把 `remember` 放在 `if (settings == null) return` 后面，首次组合会跳过这些槽位，
 * 第二次有设置时位置就错开了——状态会串，表现为「条件明明满足却不生效」。
 */
@Composable
internal fun OnboardingHost(
    currentRoute: AppRoute?,
    /** 当前打开的覆盖层；非空时不显示教练，但用于判定完成。 */
    surface: String? = null,
    /** 会话导航面板是否打开：上滑小白条那一步的完成条件。 */
    navigatorOpen: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val settingsState = produceState<Settings?>(initialValue = null) {
        SettingsDataStore.settingsFlow().collect { value = it }
    }
    val settings = settingsState.value

    var skippedKey by remember { mutableStateOf<String?>(null) }
    // 面板打开后可能被别处立刻重置成 false（比如选中会话时统一收敛），
    // 记一个「曾经打开过」，避免刚滑出来就被判成没做。
    var navigatorEverOpened by remember { mutableStateOf(false) }
    LaunchedEffect(navigatorOpen) {
        if (navigatorOpen) navigatorEverOpened = true
    }

    val step = settings?.let { GestureCoachScript.next(it.seenTourPacks) }
    val onHome = currentRoute is AppRoute.Home
    val satisfied = step != null && when (step.direction) {
        GestureDirection.SwipeRight -> surface == SurfaceKey.Sidebar
        GestureDirection.SwipeLeft -> surface == SurfaceKey.Workspace
        GestureDirection.SwipeUp -> navigatorOpen || navigatorEverOpened
    }

    LaunchedEffect(step?.key, satisfied) {
        val current = step ?: return@LaunchedEffect
        if (!satisfied) return@LaunchedEffect
        SettingsDataStore.updateSettings { s ->
            s.copy(seenTourPacks = s.seenTourPacks + (current.key to 1))
        }
    }


    if (settings == null || !onHome || step == null) return
    if (satisfied) return
    // 面板开着但还没到位（只拖了一点点又弹回去）时先不显示，避免两层叠着。
    if (surface != null) return
    if (step.key == skippedKey) return

    GestureCoach(
        step = step,
        onSkip = { skippedKey = step.key },
        modifier = modifier,
    )
}
