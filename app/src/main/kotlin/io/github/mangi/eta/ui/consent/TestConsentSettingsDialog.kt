package io.github.mangi.eta.ui.consent

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.EtaWindowDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「设置 → 测试与隐私」：看同意状态、改训练用途、撤回全部同意、回看全文。
 *
 * 撤回是即时生效的：仓库状态一改，门控立刻把主界面换成同意页。
 */
@Composable
internal fun TestConsentSettingsDialog(
    onDismiss: () -> Unit,
) {
    val state by TestConsentRepository.state.collectAsState()
    var showTerms by remember { mutableStateOf(false) }
    var showPrivacy by remember { mutableStateOf(false) }
    var confirmRevoke by remember { mutableStateOf(false) }

    val accepted = state.isAcceptedFor(
        TestConsentRepository.CONSENT_VERSION,
        TestConsentRepository.agreementHash(),
    )
    val formatter = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    EtaWindowDialog(
        show = true,
        title = "测试与隐私",
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            InfoLine("当前包", BuildConfig.APPLICATION_ID)
            InfoLine("当前版本", "${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）")
            InfoLine("同意状态", if (accepted) "已同意" else "未同意 / 已失效")
            if (state.acceptedAt > 0L) {
                InfoLine("同意时间", formatter.format(Date(state.acceptedAt)))
                InfoLine("同意时的版本", "${state.acceptedVersionName}（${state.acceptedVersionCode}）")
            }
            if (state.revokedAt > 0L) {
                InfoLine("最近撤回", formatter.format(Date(state.revokedAt)))
            }
            InfoLine("协议指纹", state.agreementHash.take(12).ifEmpty { "—" })

            Spacer(Modifier.height(6.dp))
            InfoLine("数据用途", "仅用于本次测试与故障排查")
            Text(
                text = "不会用于模型微调或新模型训练；撤回同意后助手立即停止运行。",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(bottom = 4.dp),
            )

            Spacer(Modifier.height(8.dp))
            EtaTextButton(
                text = "查看测试协议全文",
                modifier = Modifier.fillMaxWidth(),
                onClick = { showTerms = true },
            )
            EtaTextButton(
                text = "查看隐私说明全文",
                modifier = Modifier.fillMaxWidth(),
                onClick = { showPrivacy = true },
            )
        }

        Spacer(Modifier.height(8.dp))
        EtaTextButton(
            text = "撤回全部同意",
            modifier = Modifier.fillMaxWidth(),
            enabled = accepted,
            colors = ButtonDefaults.textButtonColorsPrimary(),
            onClick = { confirmRevoke = true },
        )
        EtaTextButton(
            text = "关闭",
            modifier = Modifier.fillMaxWidth(),
            onClick = onDismiss,
        )
    }

    if (showTerms) {
        AgreementTextDialog(
            title = "测试协议与免责声明",
            assetName = TestConsentRepository.ASSET_TERMS,
            onDismiss = { showTerms = false },
        )
    }
    if (showPrivacy) {
        AgreementTextDialog(
            title = "隐私说明",
            assetName = TestConsentRepository.ASSET_PRIVACY,
            onDismiss = { showPrivacy = false },
        )
    }
    if (confirmRevoke) {
        EtaWindowDialog(
            show = true,
            title = "撤回全部同意？",
            summary = "撤回后助手会立即停止运行，需要重新同意才能继续使用；撤回不影响撤回前的处理。",
            onDismissRequest = { confirmRevoke = false },
        ) {
            Column {
                EtaTextButton(
                    text = "确认撤回",
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = {
                        confirmRevoke = false
                        TestConsentRepository.revoke()
                        onDismiss()
                    },
                )
                EtaTextButton(
                    text = "取消",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { confirmRevoke = false },
                )
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.width(96.dp),
        )
        Text(
            text = value,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}
