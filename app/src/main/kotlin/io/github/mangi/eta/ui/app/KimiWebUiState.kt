package io.github.mangi.eta.ui.app

import android.content.Context
import io.github.mangi.eta.R

internal enum class KimiWebPhase { CHECKING, NOT_INSTALLED, READY, STARTING, RUNNING, FAILED }

internal data class KimiWebUiState(
    val phase: KimiWebPhase = KimiWebPhase.CHECKING,
    val errorCode: String? = null,
) {
    val canStop: Boolean get() = phase == KimiWebPhase.STARTING || phase == KimiWebPhase.RUNNING

    /**
     * 顶栏菜单文案。只有本机确实装了 Kimi Code（READY/RUNNING/STARTING/FAILED）才返回文案，
     * 未安装或仍在检测时返回 null：菜单里就不出现这一项，安装入口由「Linux 环境」页承担。
     */
    fun menuLabel(context: Context): String? = when (phase) {
        KimiWebPhase.CHECKING, KimiWebPhase.NOT_INSTALLED -> null
        KimiWebPhase.READY -> context.getString(R.string.action_launch_kimi_web)
        KimiWebPhase.STARTING -> context.getString(R.string.capability_kimi_preparing)
        KimiWebPhase.RUNNING -> context.getString(R.string.capability_kimi_open)
        KimiWebPhase.FAILED -> context.getString(R.string.capability_kimi_retry)
    }
}

internal fun KimiWebLaunchResult.Failed.message(context: Context): String = context.getString(when (code) {
    "ROOT_REQUIRED" -> R.string.capability_kimi_root_required
    "BACKGROUND_START_NOT_ALLOWED" -> R.string.capability_background_failed
    "KIMI_EXITED", "PROCESS_EXITED" -> R.string.capability_kimi_exited
    "LINUX_ENVIRONMENT_NOT_READY", "PROFILE_NOT_INSTALLED" -> R.string.capability_kimi_not_installed
    "PROOT_UNAVAILABLE" -> R.string.capability_kimi_proot_unavailable
    "LOGS_UNAVAILABLE" -> R.string.capability_kimi_logs_unavailable
    "URL_TIMEOUT" -> R.string.linux_kimi_web_failed_url
    "BROWSER_UNAVAILABLE" -> R.string.linux_kimi_web_failed_browser
    else -> R.string.linux_kimi_web_failed_start
})
