package io.github.mangi.eta.ui.screens.github

import android.widget.Toast
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.github.GitHubAccountManager
import io.github.mangi.eta.agent.github.GitHubAccountState
import io.github.mangi.eta.ui.components.EtaArrowPreference
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaSwitchPreference
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun GitHubAccountScreen(onBack: () -> Unit) {
    val state by GitHubAccountManager.state.collectAsState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    var syncEnabled by remember { mutableStateOf(GitHubAccountManager.syncToTerminalEnabled()) }

    LaunchedEffect(Unit) { GitHubAccountManager.syncIfConfigured() }

    MiuixScaffoldPage(title = "GitHub 账号", onBack = onBack) {
        // 首页条目只留标题，能力说明收进这一层。
        item(key = "intro") {
            Text(
                text = "一键连接，之后可直接 clone/push、提 issue/PR",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 4.dp, bottom = 12.dp),
            )
        }

        item(key = "account") {
            EtaPreferenceGroupTitle("账号")
            EtaPreferenceGroup {
                when (val current = state) {
                    GitHubAccountState.Disconnected -> EtaArrowPreference(
                        title = "连接 GitHub",
                        summary = "点一下走设备码授权，不需要手动建 token",
                        onClick = { GitHubAccountManager.connect() },
                    )

                    GitHubAccountState.Connecting -> EtaPreference(
                        title = "正在申请设备码…",
                        summary = "马上会给出 8 位代码",
                    )

                    is GitHubAccountState.Waiting -> {
                        EtaPreference(
                            title = "在浏览器输入：${current.userCode}",
                            summary = "打开 ${current.verificationUri}，输入这串代码并点 Authorize",
                            onClick = {
                                clipboard.setText(AnnotatedString(current.userCode))
                                Toast.makeText(context, "已复制 ${current.userCode}", Toast.LENGTH_SHORT).show()
                            },
                        )
                        EtaPreferenceDivider(hasLeading = false)
                        EtaArrowPreference(
                            title = "打开授权页面",
                            summary = "会先把代码复制到剪贴板，粘贴即用",
                            onClick = {
                                clipboard.setText(AnnotatedString(current.userCode))
                                Toast.makeText(context, "已复制 ${current.userCode}", Toast.LENGTH_SHORT).show()
                                runCatching { uriHandler.openUri(current.verificationUri) }
                            },
                        )
                        EtaPreferenceDivider(hasLeading = false)
                        EtaArrowPreference(
                            title = "取消",
                            onClick = { GitHubAccountManager.cancel() },
                        )
                    }

                    is GitHubAccountState.Failed -> EtaArrowPreference(
                        title = "连接失败",
                        summary = current.message,
                        onClick = { GitHubAccountManager.connect() },
                    )

                    is GitHubAccountState.Connected -> EtaPreference(
                        title = "已连接 @${current.login}",
                        summary = "权限：${current.scopes}",
                    )
                }
            }
        }

        if (state is GitHubAccountState.Connected) {
            item(key = "terminal") {
                EtaPreferenceGroupTitle("终端")
                EtaPreferenceGroup {
                    EtaSwitchPreference(
                        title = "同步给终端 git",
                        summary = terminalSummary(state as GitHubAccountState.Connected),
                        checked = syncEnabled,
                        onCheckedChange = { enabled ->
                            syncEnabled = enabled
                            GitHubAccountManager.setSyncToTerminal(enabled)
                        },
                    )
                    EtaPreferenceDivider(hasLeading = false)
                    EtaArrowPreference(
                        title = "断开连接",
                        summary = "清除本机 token、自动注册的 MCP 服务器与终端凭据",
                        onClick = { GitHubAccountManager.disconnect() },
                    )
                }
            }
        }

        item(key = "about") {
            EtaPreferenceGroupTitle("说明")
            EtaPreferenceGroup {
                EtaPreference(
                    title = "token 存在哪",
                    summary = "用 Android Keystore 加密后只存在本机；开启终端同步时会写入 Linux 环境的 git 凭据文件（仅 root 可读）。",
                )
                EtaPreferenceDivider(hasLeading = false)
                EtaPreference(
                    title = "怎么撤销",
                    summary = "GitHub → Settings → Applications → Authorized OAuth Apps，随时可以撤销；这里断开只是清本机凭据。",
                )
                EtaPreferenceDivider(hasLeading = false)
                EtaPreference(
                    title = "连接后会得到什么",
                    summary = "自动注册一条 GitHub 官方远程 MCP 服务器（issue、PR、代码搜索、合并等），并让终端里的 git clone/push 走同一份凭据。",
                )
            }
        }
    }
}

private fun terminalSummary(state: GitHubAccountState.Connected): String = when {
    state.syncError != null -> "写入失败：${state.syncError}"

    state.syncedTerminals.isNotEmpty() && state.pendingTerminals.isEmpty() ->
        "已写入：${state.syncedTerminals.joinToString("、")}"

    state.syncedTerminals.isNotEmpty() ->
        "已写入：${state.syncedTerminals.joinToString("、")}；未就绪：${state.pendingTerminals.joinToString("、")}"

    state.pendingTerminals.isNotEmpty() ->
        "等 Linux 环境就绪后再同步（${state.pendingTerminals.joinToString("、")}）"

    else -> "开启后会把凭据写进 Linux 环境"
}
