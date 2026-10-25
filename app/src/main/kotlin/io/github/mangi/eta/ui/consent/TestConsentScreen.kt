package io.github.mangi.eta.ui.consent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.BuildConfig
import io.github.mangi.eta.data.repository.TestConsentRepository
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.EtaWindowDialog
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 一段可折叠的条款：标题 + 正文（正文来自 assets 里的 Markdown，运行期解析）。 */
private data class ConsentSection(val title: String, val body: String)

private fun parseAgreement(markdown: String): Pair<String, List<ConsentSection>> {
    val intro = StringBuilder()
    val sections = mutableListOf<ConsentSection>()
    var currentTitle: String? = null
    var currentBody = StringBuilder()
    fun flush() {
        val title = currentTitle ?: return
        sections += ConsentSection(title, currentBody.toString().trim())
        currentBody = StringBuilder()
    }
    markdown.lineSequence().forEach { line ->
        when {
            line.startsWith("## ") -> {
                flush()
                currentTitle = line.removePrefix("## ").trim()
            }
            line.startsWith("# ") -> intro.appendLine(line.removePrefix("# ").trim())
            currentTitle == null -> intro.appendLine(line)
            else -> currentBody.appendLine(line)
        }
    }
    flush()
    return intro.toString().trim() to sections
}

/**
 * 测试版首次启动的同意页（全屏）。
 *
 * 用全屏页而不是对话框：条款长、要勾选、还要能随时回看；对话框里滚动 + 勾选体验差，
 * 且历史上 Dialog 里嵌浮层踩过层级崩溃。
 *
 * 三个必需勾选分别对应三项独立告知（协议 / 敏感个人信息 / AI 输出性质）；
 * 数据只用于本次测试与故障排查，不做模型微调/训练，因此没有额外可选项。
 */
@Composable
internal fun TestConsentScreen(
    modifier: Modifier = Modifier,
    onAccepted: () -> Unit,
) {
    val introAndSections = remember {
        parseAgreement(TestConsentRepository.readAgreement(TestConsentRepository.ASSET_TERMS))
    }
    val title = introAndSections.first
    val sections = introAndSections.second

    var expanded by remember {
        mutableStateOf(sections.indices.take(1).toSet())
    }
    var ackTerms by remember { mutableStateOf(false) }
    var ackSensitive by remember { mutableStateOf(false) }
    var ackAiOutput by remember { mutableStateOf(false) }
    var showPrivacy by remember { mutableStateOf(false) }
    var confirmDecline by remember { mutableStateOf(false) }

    val canAccept = ackTerms && ackSensitive && ackAiOutput

    Box(modifier = modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = title.ifBlank { "Eta 测试版 · 使用须知" },
                style = MiuixTheme.textStyles.headline1,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）· ${BuildConfig.APPLICATION_ID}",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "测试包 · 非正式发布 · 与正式版数据不互通",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.error,
            )
            Spacer(Modifier.height(12.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                sections.forEachIndexed { index, section ->
                    val isOpen = index in expanded
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                expanded = if (isOpen) expanded - index else expanded + index
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = section.title,
                            style = MiuixTheme.textStyles.body1,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = if (isOpen) "收起" else "展开",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.primary,
                        )
                    }
                    if (isOpen) {
                        Text(
                            text = section.body,
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(bottom = 10.dp),
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))
                Text(
                    text = "点此查看《隐私说明》全文",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showPrivacy = true }
                        .padding(vertical = 8.dp),
                )
                Spacer(Modifier.height(8.dp))
            }

            Column(modifier = Modifier.fillMaxWidth()) {
                ConsentCheckRow(
                    checked = ackTerms,
                    onCheckedChange = { ackTerms = it },
                    text = "我已阅读并同意《测试协议与免责声明》与《隐私说明》",
                )
                ConsentCheckRow(
                    checked = ackSensitive,
                    onCheckedChange = { ackSensitive = it },
                    text = "我知悉并同意：助手可能读取当前屏幕内容，其中可能包含我或他人的敏感个人信息" +
                        "（证件号、金融账户、健康、行踪等）；我承诺不向助手输入我无权处分的第三方个人信息",
                )
                ConsentCheckRow(
                    checked = ackAiOutput,
                    onCheckedChange = { ackAiOutput = it },
                    text = "我知悉 AI 输出基于概率生成、可能出错，需我自行验证，且不构成法律/医疗/财务等专业建议",
                )
                Spacer(Modifier.height(8.dp))
                EtaTextButton(
                    text = "同意并继续",
                    enabled = canAccept,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = {
                        TestConsentRepository.accept(
                            ackTerms = ackTerms,
                            ackSensitiveScreen = ackSensitive,
                            ackAiOutput = ackAiOutput,
                            versionName = BuildConfig.VERSION_NAME,
                            versionCode = BuildConfig.VERSION_CODE,
                        )
                        onAccepted()
                    },
                )
                EtaTextButton(
                    text = "不同意",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { confirmDecline = true },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "数据只用于本次测试与故障排查，不会用于模型微调或训练；可随时在「设置 → 测试与隐私」撤回。",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
            }
        }
    }

    if (showPrivacy) {
        AgreementTextDialog(
            title = "隐私说明",
            assetName = TestConsentRepository.ASSET_PRIVACY,
            onDismiss = { showPrivacy = false },
        )
    }

    if (confirmDecline) {
        EtaWindowDialog(
            show = true,
            title = "不同意并退出？",
            summary = "不同意思就不能使用测试版。正式版 Eta 不受影响，可以继续用它。",
            onDismissRequest = { confirmDecline = false },
        ) {
            Column {
                EtaTextButton(
                    text = "退出测试版",
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = {
                        confirmDecline = false
                        exitBetaProcess()
                    },
                )
                EtaTextButton(
                    text = "再看看条款",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { confirmDecline = false },
                )
            }
        }
    }
}

@Composable
private fun ConsentCheckRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    text: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Checkbox(
            state = if (checked) ToggleableState.On else ToggleableState.Off,
            onClick = { onCheckedChange(!checked) },
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote1,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 协议 / 隐私全文的只读弹窗。 */
@Composable
internal fun AgreementTextDialog(
    title: String,
    assetName: String,
    onDismiss: () -> Unit,
) {
    val body = remember(assetName) { TestConsentRepository.readAgreement(assetName) }
    EtaWindowDialog(
        show = true,
        title = title,
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = body,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        Spacer(Modifier.height(8.dp))
        EtaTextButton(
            text = "关闭",
            modifier = Modifier.fillMaxWidth(),
            onClick = onDismiss,
        )
    }
}

/** 不同意：直接退出进程，不启动任何服务、不上报任何东西。 */
internal fun exitBetaProcess(): Nothing {
    android.os.Process.killProcess(android.os.Process.myPid())
    kotlin.system.exitProcess(0)
}
