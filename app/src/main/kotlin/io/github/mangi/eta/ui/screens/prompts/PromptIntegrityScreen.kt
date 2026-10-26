package io.github.mangi.eta.ui.screens.prompts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GppGood
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.security.IntegrityLevel
import io.github.mangi.eta.agent.security.IntegrityReport
import io.github.mangi.eta.agent.security.PromptIntegrityGuard
import io.github.mangi.eta.ui.components.EtaArrowPreference
import io.github.mangi.eta.ui.components.EtaActionPreference
import io.github.mangi.eta.ui.components.EtaPreferenceColors
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaPreferenceIcon
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 提示词完整性自检（只读）。
 *
 * 不阻止任何请求，只把"提示词链路被人动过"的证据摊开：注入文件、注入框架痕迹、
 * 护栏句子缺失、最近一轮请求里的越狱特征。
 */
@Composable
internal fun PromptIntegrityScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var report by remember { mutableStateOf<IntegrityReport?>(null) }
    var scanning by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        scanning = true
        report = PromptIntegrityGuard.scan(context)
        scanning = false
    }

    val current = report
    MiuixScaffoldPage(
        title = stringResource(R.string.prompt_integrity_title),
        onBack = onBack,
    ) {
        item(key = "status") {
            EtaPreferenceGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                EtaActionPreference(
                    title = when {
                        scanning && current == null -> stringResource(R.string.prompt_integrity_scanning)
                        current == null -> stringResource(R.string.prompt_integrity_scanning)
                        current.worst == IntegrityLevel.OK -> stringResource(R.string.prompt_integrity_ok)
                        current.worst == IntegrityLevel.WARNING -> stringResource(R.string.prompt_integrity_warning)
                        else -> stringResource(R.string.prompt_integrity_critical)
                    },
                    summary = current?.let {
                        stringResource(
                            R.string.prompt_integrity_summary,
                            formatPromptTime(it.checkedAt),
                            it.findings.size,
                        )
                    } ?: stringResource(R.string.prompt_integrity_summary_none),
                    startAction = {
                        EtaPreferenceIcon(
                            icon = if (current?.worst == IntegrityLevel.OK) {
                                Icons.Rounded.GppGood
                            } else {
                                Icons.Rounded.GppMaybe
                            },
                            tint = when (current?.worst) {
                                IntegrityLevel.CRITICAL -> EtaPreferenceColors.Orange
                                IntegrityLevel.WARNING -> EtaPreferenceColors.Yellow
                                else -> EtaPreferenceColors.Green
                            },
                        )
                    },
                    onClick = {
                        scanning = true
                        report = PromptIntegrityGuard.scan(context)
                        scanning = false
                    },
                )
                EtaPreferenceDivider(hasLeading = true)
                EtaActionPreference(
                    title = stringResource(R.string.prompt_integrity_rescan),
                    startAction = { EtaPreferenceIcon(icon = Icons.Rounded.Refresh, tint = EtaPreferenceColors.Blue) },
                    onClick = {
                        scanning = true
                        report = PromptIntegrityGuard.scan(context)
                        scanning = false
                    },
                )
                EtaPreferenceDivider(hasLeading = true)
                EtaActionPreference(
                    title = stringResource(R.string.prompt_integrity_copy),
                    summary = stringResource(R.string.prompt_integrity_copy_hint),
                    startAction = { EtaPreferenceIcon(icon = Icons.Rounded.Shield, tint = EtaPreferenceColors.Blue) },
                    onClick = {
                        val text = buildString {
                            val snapshot = report ?: return@EtaActionPreference
                            appendLine("Eta 提示词完整性报告 " + formatPromptTime(snapshot.checkedAt))
                            appendLine("结论：" + snapshot.worst.name + "，共 ${snapshot.findings.size} 条")
                            snapshot.findings.forEach { finding ->
                                appendLine("- [${finding.level.name}] ${finding.code}：${finding.title}")
                                appendLine("  ${finding.detail}")
                            }
                        }
                        copyPromptText(context, text)
                    },
                )
            }
        }

        if (current != null && current.findings.isNotEmpty()) {
            item(key = "findings_title") {
                EtaPreferenceGroupTitle(stringResource(R.string.prompt_integrity_findings, current.findings.size))
            }
            items(current.findings.size, key = { index -> "finding-$index" }) { index ->
                val finding = current.findings[index]
                EtaPreferenceGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)) {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(
                            text = finding.title,
                            style = MiuixTheme.textStyles.body2,
                            color = if (finding.level == IntegrityLevel.CRITICAL) {
                                EtaPreferenceColors.Orange
                            } else {
                                MiuixTheme.colorScheme.onSurface
                            },
                        )
                        Text(
                            text = finding.detail,
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Text(
                            text = finding.code,
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }

        item(key = "scope_title") {
            EtaPreferenceGroupTitle(stringResource(R.string.prompt_integrity_scope_group))
        }
        item(key = "scope") {
            EtaPreferenceGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    listOf(
                        R.string.prompt_integrity_scope_files,
                        R.string.prompt_integrity_scope_xposed,
                        R.string.prompt_integrity_scope_maps,
                        R.string.prompt_integrity_scope_anchors,
                        R.string.prompt_integrity_scope_snapshot,
                    ).forEach { res ->
                        Text(
                            text = "· " + stringResource(res),
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                    }
                    Text(
                        text = stringResource(R.string.prompt_integrity_scope_readonly),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }
}
