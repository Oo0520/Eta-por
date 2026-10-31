package io.github.mangi.eta.testsupport

import org.junit.Assume.assumeTrue
import org.junit.runner.notification.RunNotifier
import org.junit.runners.model.FrameworkMethod
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric 的 native runtime 只发布三种平台产物（见 nativeruntime-dist-compat 包内 native/ 目录）：
 * linux-x86_64、macos-aarch64、windows-x86_64。
 *
 * 在其它平台（例如跑在 arm64 手机上的 Debian chroot）里，Robolectric 4.17 会在环境初始化阶段
 * 直接抛 AssertionError "The Robolectric native runtime is not supported on Linux (aarch64)"，
 * 于是整类用例全部变红——但这不是用例本身的问题，本机也没有可用的二进制可以补。
 *
 * 所以在这里先做一次平台判定：不支持时用 Assume 让用例标记为 skipped，而不是 fail。
 * 支持的平台上（CI 的 x86_64 / macOS arm64）行为与直接继承 RobolectricTestRunner 完全一致。
 */
class EtaRobolectricTestRunner(testClass: Class<*>) : RobolectricTestRunner(testClass) {

    override fun runChild(method: FrameworkMethod, notifier: RunNotifier) {
        assumeTrue(SKIP_REASON, nativeRuntimeAvailable())
        super.runChild(method, notifier)
    }

    private fun nativeRuntimeAvailable(): Boolean {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val arch = System.getProperty("os.arch").orEmpty().lowercase()
        return when {
            os.contains("linux") -> arch == "amd64" || arch == "x86_64"
            os.contains("mac") || os.contains("darwin") -> arch == "aarch64" || arch == "arm64" || arch == "x86_64"
            os.contains("windows") -> arch == "amd64" || arch == "x86_64"
            else -> false
        }
    }

    private companion object {
        const val SKIP_REASON =
            "Robolectric native runtime 没有当前平台的二进制产物（仅 linux-x86_64 / macos / windows-x86_64），跳过"
    }
}
