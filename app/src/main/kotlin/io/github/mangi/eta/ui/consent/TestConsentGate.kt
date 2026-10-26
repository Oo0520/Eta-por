package io.github.mangi.eta.ui.consent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.BuildConfig
import io.github.mangi.eta.data.repository.TestConsentRepository
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 同意门控。
 *
 * - 未同意（首次启动 / 条款版本或文本变了 / 已撤回）→ 只显示同意页，主界面挂不上；
 * - 已同意 → 正常界面；测试包里额外挂一条常驻提示条，正式包完全不受影响。
 *
 * 门控放在 Compose 层不是唯一防线：运行时服务、无障碍入口还会再查一次
 * [TestConsentRepository.isAccepted]，防止外部 App 直接拉起服务绕过界面。
 */
@Composable
internal fun TestConsentGate(content: @Composable () -> Unit) {
    // 正式包不是测试分发，不该出现测试协议与「非正式发布」字样：直接放行，连状态都不读。
    if (!BuildConfig.BETA_BUILD) {
        content()
        return
    }
    val state by TestConsentRepository.state.collectAsState()
    val accepted = state.isAcceptedFor(
        TestConsentRepository.CONSENT_VERSION,
        TestConsentRepository.agreementHash(),
    )

    if (!accepted) {
        TestConsentScreen(onAccepted = { /* 状态流会自己刷新，无需额外动作 */ })
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (BuildConfig.BETA_BUILD) {
            BetaBuildBanner()
        }
        Box(modifier = Modifier.fillMaxSize().weight(1f)) {
            content()
        }
    }
}

/**
 * 测试版常驻提示条。
 *
 * 关闭状态写入本地（按 versionCode 记）：本版本内关掉后不再出现；升级到新的测试版会再提示一次，
 * 既不会每次启动都打扰，也不会让人彻底忘了自己在用测试包。
 */
@Composable
private fun BetaBuildBanner() {
    val versionCode = BuildConfig.VERSION_CODE
    var dismissed by remember {
        mutableStateOf(TestConsentRepository.bannerDismissedVersion() >= versionCode)
    }
    var showDetail by remember { mutableStateOf(false) }
    if (dismissed) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // 背景铺到状态栏下面，文字让开状态栏/刘海，避免和信号电量叠在一起。
            .background(MiuixTheme.colorScheme.secondaryContainer)
            .statusBarsPadding(),
    ) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "测试版 ${BuildConfig.VERSION_NAME}",
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSecondaryContainer,
        )
        Text(
            text = "｜仅用于测试，AI 输出需自行验证",
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f),
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "说明",
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier
                .clickable { showDetail = true }
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
        Text(
            text = "×",
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f),
            modifier = Modifier
                .clickable {
                    TestConsentRepository.dismissBanner(versionCode)
                    dismissed = true
                }
                .padding(start = 4.dp, end = 2.dp),
        )
    }
        // 暗色下 secondaryContainer 与正文背景接近，用一条淡分隔线交代边界。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MiuixTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.12f)),
        )
    }

    if (showDetail) {
        AgreementTextDialog(
            title = "测试协议与免责声明",
            assetName = TestConsentRepository.ASSET_TERMS,
            onDismiss = { showDetail = false },
        )
    }
}
