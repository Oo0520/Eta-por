package io.github.mangi.eta.ui.screens.prompts

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material.icons.rounded.Visibility
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
import io.github.mangi.eta.agent.model.AgentSnapshotAnalysis
import io.github.mangi.eta.agent.prompt.AgentToolPolicyStore
import io.github.mangi.eta.agent.prompt.PromptConflictAnalyzer
import io.github.mangi.eta.agent.prompt.PromptConflictLevel
import io.github.mangi.eta.agent.prompt.PromptCustomizationStore
import io.github.mangi.eta.agent.prompt.ToolPolicyMode
import io.github.mangi.eta.ui.components.EtaArrowPreference
import io.github.mangi.eta.ui.components.EtaExpandPreference
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceColors
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaPreferenceIcon
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.navigation.AppRoute
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 概览页：只读状态 + 入口。
 *
 * 每行都有图标与配色；冲突不再跳到编辑页，而是在这里就地展开，
 * 避免"未启用"和"冲突检测"点进去是同一页。
 */
@Composable
internal fun PromptStudioScreen(
    onBack: () -> Unit,
    onNavigate: (AppRoute) -> Unit,
) {
    val context = LocalContext.current
    // 概览只读：每次组合都重新读一次，从编辑页返回时状态立即是新的。
    val profile = PromptCustomizationStore.load()
    val policy = AgentToolPolicyStore.load()
    val conflicts = remember(profile.revision, profile.enabled, policy.version) {
        PromptConflictAnalyzer.analyze(profile, roleplayActive = false, toolPolicy = policy)
    }
    val blocking = conflicts.count { it.level == PromptConflictLevel.ERROR }

    var showConflicts by remember { mutableStateOf(false) }
    val effectiveSummary = rememberEffectiveSnapshotSummary()


    MiuixScaffoldPage(
        title = stringResource(R.string.prompt_studio_title),
        onBack = onBack,
    ) {
        item(key = "state_title") {
            EtaPreferenceGroupTitle(stringResource(R.string.prompt_studio_state_group))
        }
        item(key = "state") {
            EtaPreferenceGroup(modifier = Modifier.padding(horizontal = 16.dp)) {
                EtaArrowPreference(
                    title = if (profile.hasContent) {
                        stringResource(R.string.prompt_studio_state_custom_title)
                    } else {
                        stringResource(R.string.prompt_studio_state_default_title)
                    },
                    summary = customizationContentText(profile),
                    startAction = {
                        EtaPreferenceIcon(icon = Icons.Rounded.Psychology, tint = EtaPreferenceColors.Blue)
                    },
                    onClick = { onNavigate(AppRoute.PromptEditor) },
                )
                EtaPreferenceDivider(hasLeading = true)
                EtaArrowPreference(
                    title = stringResource(R.string.prompt_studio_versions_title),
                    summary = stringResource(
                        R.string.prompt_studio_rev_meta,
                        profile.revision,
                        formatPromptTime(profile.updatedAt),
                        profile.history.size,
                    ),
                    startAction = {
                        EtaPreferenceIcon(icon = Icons.Rounded.History, tint = EtaPreferenceColors.Green)
                    },
                    onClick = { onNavigate(AppRoute.PromptVersions) },
                )
                EtaPreferenceDivider(hasLeading = true)
                EtaArrowPreference(
                    title = if (policy.mode == ToolPolicyMode.ALLOW_ALL) {
                        stringResource(R.string.prompt_studio_tool_all)
                    } else {
                        stringResource(R.string.prompt_studio_tool_summary, policy.allowedTools.size)
                    },
                    summary = stringResource(R.string.prompt_studio_tool_group),
                    startAction = {
                        EtaPreferenceIcon(icon = Icons.Rounded.Terminal, tint = EtaPreferenceColors.Orange)
                    },
                    onClick = { onNavigate(AppRoute.PromptTools) },
                )
                EtaPreferenceDivider(hasLeading = true)
                EtaArrowPreference(
                    title = stringResource(R.string.prompt_preset_entry),
                    summary = stringResource(R.string.prompt_preset_entry_summary),
                    startAction = {
                        EtaPreferenceIcon(icon = Icons.Rounded.Inventory2, tint = EtaPreferenceColors.Blue)
                    },
                    onClick = { onNavigate(AppRoute.PromptPresets) },
                )
                EtaPreferenceDivider(hasLeading = true)
                EtaArrowPreference(
                    title = stringResource(R.string.prompt_snapshot_open),
                    summary = stringResource(R.string.prompt_snapshot_open_summary),
                    startAction = {
                        EtaPreferenceIcon(icon = Icons.Rounded.Description, tint = EtaPreferenceColors.Yellow)
                    },
                    onClick = { onNavigate(AppRoute.PromptSnapshots) },
                )
                EtaPreferenceDivider(hasLeading = true)
                EtaArrowPreference(
                    title = stringResource(R.string.prompt_integrity_title),
                    summary = stringResource(R.string.prompt_integrity_entry_summary),
                    startAction = {
                        EtaPreferenceIcon(icon = Icons.Rounded.Shield, tint = EtaPreferenceColors.Yellow)
                    },
                    onClick = { onNavigate(AppRoute.PromptIntegrity) },
                )
            }
        }

        item(key = "conflict_title") {
            EtaPreferenceGroupTitle(stringResource(R.string.prompt_studio_conflict_group, conflicts.size))
        }
        item(key = "conflict") {
            EtaPreferenceGroup(modifier = Modifier.padding(horizontal = 16.dp)) {
                EtaExpandPreference(
expanded = showConflicts,
                    title = if (conflicts.isEmpty()) {
                        stringResource(R.string.prompt_studio_conflict_none)
                    } else {
                        stringResource(R.string.prompt_studio_conflict_banner, conflicts.size, blocking)
                    },
                    summary = if (conflicts.isEmpty()) {
                        null
                    } else if (showConflicts) {
                        stringResource(R.string.prompt_studio_conflict_collapse)
                    } else {
                        stringResource(R.string.prompt_studio_conflict_expand)
                    },
                    startAction = {
                        EtaPreferenceIcon(
                            icon = Icons.Rounded.GppMaybe,
                            tint = if (blocking > 0) EtaPreferenceColors.Orange else EtaPreferenceColors.Green,
                        )
                    },
                    onClick = { if (conflicts.isNotEmpty()) showConflicts = !showConflicts },
                )
                if (showConflicts) {
                    conflicts.forEach { conflict ->
                        EtaPreferenceDivider(hasLeading = false)
                        ConflictRow(conflict)
                    }
                }
            }
        }

        item(key = "preview_title") {
            EtaPreferenceGroupTitle(stringResource(R.string.prompt_studio_preview_group))
        }
        item(key = "preview_entry") {
            EtaPreferenceGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                EtaArrowPreference(
                    title = stringResource(R.string.prompt_effective_entry_title),
                    summary = effectiveSummary,
                    startAction = {
                        EtaPreferenceIcon(icon = Icons.Rounded.ViewAgenda, tint = EtaPreferenceColors.Blue)
                    },
                    onClick = { onNavigate(AppRoute.PromptEffective) },
                )
            }
        }
    }
}
