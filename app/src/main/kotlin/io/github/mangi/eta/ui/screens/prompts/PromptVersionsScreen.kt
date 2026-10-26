package io.github.mangi.eta.ui.screens.prompts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.prompt.PromptCustomization
import io.github.mangi.eta.agent.prompt.PromptCustomizationMode
import io.github.mangi.eta.agent.prompt.PromptCustomizationStore
import io.github.mangi.eta.ui.components.EtaArrowPreference
import io.github.mangi.eta.ui.components.EtaActionPreference
import io.github.mangi.eta.ui.components.EtaActionTone
import io.github.mangi.eta.ui.components.EtaExpandPreference
import io.github.mangi.eta.ui.components.EtaPreferenceColors
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaPreferenceIcon
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 版本历史页：点行只展开详情，恢复与删除都是显式按钮。
 *
 * 卡片统一 fillMaxWidth，避免内容短的卡片比列表行窄一截；
 * 空内容的历史记录默认不列出来（那是早期"空保存"留下的噪声）。
 */
@Composable
internal fun PromptVersionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var profile by remember { mutableStateOf(PromptCustomizationStore.load()) }
    var expanded by remember { mutableStateOf<Int?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    val visibleHistory = profile.history.filter { it.hasContent }
    val hiddenCount = profile.history.size - visibleHistory.size

    fun persist(next: PromptCustomization, message: String) {
        if (PromptCustomizationStore.save(next)) {
            profile = next
            toast(context, message)
        } else {
            toast(context, context.getString(R.string.prompt_studio_save_failed))
        }
    }

    MiuixScaffoldPage(
        title = stringResource(R.string.prompt_studio_versions_title),
        onBack = onBack,
    ) {
        item(key = "current") {
            EtaPreferenceGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        text = if (profile.hasContent) {
                            stringResource(R.string.prompt_studio_state_custom_title)
                        } else {
                            stringResource(R.string.prompt_studio_state_default_title)
                        },
                        style = MiuixTheme.textStyles.body2,
                    )
                    Text(
                        text = stringResource(
                            R.string.prompt_studio_version_meta,
                            formatPromptTime(profile.updatedAt),
                            profile.baseSystemPrompt.length + profile.personaName.length +
                                profile.personaStyle.length + profile.appendInstructions.length,
                        ) + " · rev " + profile.revision,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }

        item(key = "history_title") {
            EtaPreferenceGroupTitle(stringResource(R.string.prompt_studio_versions_history_group))
        }
        item(key = "history") {
            EtaPreferenceGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                if (visibleHistory.isEmpty()) {
                    Text(
                        text = stringResource(R.string.prompt_studio_history_empty),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                visibleHistory.forEachIndexed { index, record ->
                    if (index > 0) EtaPreferenceDivider(hasLeading = true)
                    EtaExpandPreference(
expanded = expanded == record.version,
                        title = stringResource(R.string.prompt_studio_history_item, record.version, record.summary),
                        summary = stringResource(R.string.prompt_studio_history_time, formatPromptTime(record.updatedAt)),
                        startAction = {
                            EtaPreferenceIcon(icon = Icons.Rounded.History, tint = EtaPreferenceColors.Green)
                        },
                        onClick = { expanded = if (expanded == record.version) null else record.version },
                    )
                    if (expanded == record.version) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.prompt_studio_history_detail,
                                    if (record.mode == PromptCustomizationMode.APPEND) {
                                        stringResource(R.string.prompt_studio_mode_append)
                                    } else {
                                        stringResource(R.string.prompt_studio_mode_replace)
                                    },
                                    record.baseSystemPrompt.length,
                                    record.appendInstructions.length,
                                ) + " · rev " + record.revision,
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        EtaPreferenceDivider(hasLeading = true)
                        EtaActionPreference(
                            title = stringResource(R.string.prompt_studio_version_restore),
                            summary = if (profile.matches(record)) {
                                stringResource(R.string.prompt_studio_version_same)
                            } else {
                                null
                            },
                            startAction = {
                                EtaPreferenceIcon(icon = Icons.Rounded.Restore, tint = EtaPreferenceColors.Blue)
                            },
                            onClick = {
                                if (profile.matches(record)) {
                                    toast(context, context.getString(R.string.prompt_studio_version_same))
                                } else {
                                    val next = profile.restore(record)
                                    expanded = null
                                    persist(next, context.getString(R.string.prompt_studio_restored, next.version))
                                }
                            },
                        )
                        EtaPreferenceDivider(hasLeading = true)
                        EtaActionPreference(
tone = EtaActionTone.Danger,
                            title = stringResource(R.string.prompt_studio_version_delete),
                            onClick = {
                                val next = profile.removeHistory(record.version)
                                expanded = null
                                persist(next, context.getString(R.string.prompt_studio_version_deleted, record.version))
                            },
                        )
                    }
                }
            }
        }

        if (hiddenCount > 0) {
            item(key = "hidden_empty") {
                EtaPreferenceGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    EtaActionPreference(
                        title = stringResource(R.string.prompt_studio_history_hidden_empty, hiddenCount),
                        startAction = {
                            EtaPreferenceIcon(icon = Icons.Rounded.Tune, tint = EtaPreferenceColors.Orange)
                        },
                        onClick = {
                            persist(
                                profile.dropEmptyHistory(),
                                context.getString(R.string.prompt_studio_history_empty_dropped, hiddenCount),
                            )
                        },
                    )
                }
            }
        }

        if (profile.history.isNotEmpty()) {
            item(key = "clear") {
                EtaPreferenceGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    EtaActionPreference(
tone = EtaActionTone.Danger,
                        title = stringResource(
                            if (confirmClear) R.string.prompt_studio_history_clear_confirm
                            else R.string.prompt_studio_history_clear,
                        ),
                        summary = stringResource(R.string.prompt_studio_history_clear_hint),
                        startAction = {
                            EtaPreferenceIcon(icon = Icons.Rounded.DeleteSweep, tint = EtaPreferenceColors.Orange)
                        },
                        onClick = {
                            if (!confirmClear) {
                                confirmClear = true
                            } else {
                                confirmClear = false
                                expanded = null
                                persist(
                                    profile.clearHistory(),
                                    context.getString(R.string.prompt_studio_history_cleared),
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}
