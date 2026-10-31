package io.github.mangi.eta.ui.screens.prompts

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Compare
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.ViewAgenda
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
import io.github.mangi.eta.agent.model.AgentRequestSnapshot
import io.github.mangi.eta.agent.model.AgentSnapshotAnalysis
import io.github.mangi.eta.agent.model.SegmentChangeState
import io.github.mangi.eta.agent.model.SystemSegmentKind
import io.github.mangi.eta.ui.components.EtaArrowPreference
import io.github.mangi.eta.ui.components.EtaActionPreference
import io.github.mangi.eta.ui.components.EtaExpandPreference
import io.github.mangi.eta.ui.components.EtaPreferenceColors
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaPreferenceIcon
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File

/**
 * 快照详情：概览 → 与基准的差异 → system 分段 → 工具清单。
 *
 * 每一段只显示一行摘要，正文按需展开；差异只给"能得出结论"的聚合，不逐个工具刷屏。
 */
@Composable
internal fun PromptSnapshotDetailScreen(
    fileName: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val file = remember(fileName) { AgentRequestSnapshot.list().firstOrNull { it.name == fileName } }
    val json = remember(fileName) { file?.let(AgentRequestSnapshot::read) }
    var baseName by remember { mutableStateOf(AgentRequestSnapshot.baseName()) }
    var expandedSegment by remember { mutableStateOf<Int?>(null) }
    var showTools by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<File?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val target = pendingExport
        pendingExport = null
        if (uri == null || target == null) return@rememberLauncherForActivityResult
        val ok = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { output -> output.write(target.readBytes()) } != null
        }.getOrDefault(false)
        toast(context, context.getString(if (ok) R.string.prompt_snapshot_exported else R.string.prompt_snapshot_export_failed))
    }

    val baseJson = remember(baseName, fileName) {
        if (baseName == null || baseName == fileName) {
            null
        } else {
            AgentRequestSnapshot.list().firstOrNull { it.name == baseName }?.let(AgentRequestSnapshot::read)
        }
    }
    val diff = remember(baseJson, json) {
        if (baseJson != null && json != null) AgentSnapshotAnalysis.diff(baseJson, json) else null
    }

    if (json == null) {
        MiuixScaffoldPage(title = stringResource(R.string.prompt_snapshot_detail_title), onBack = onBack) {
            item(key = "missing") {
                EtaPreferenceGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Text(
                        text = stringResource(R.string.prompt_snapshot_missing),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
        return
    }

    val counts = json.optJSONObject("counts")
    val system = json.optJSONArray("system")
    val tools = json.optJSONArray("tools")

    MiuixScaffoldPage(
        title = stringResource(R.string.prompt_snapshot_detail_title),
        onBack = onBack,
    ) {
        item(key = "overview_title") {
            EtaPreferenceGroupTitle(stringResource(R.string.prompt_snapshot_overview_group))
        }
        item(key = "overview") {
            EtaPreferenceGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        text = stringResource(
                            R.string.prompt_snapshot_row_title,
                            formatPromptTime(json.optLong("at")),
                            kindLabel(AgentSnapshotAnalysis.kindOf(json)),
                        ),
                        style = MiuixTheme.textStyles.body2,
                    )
                    Text(
                        text = stringResource(
                            R.string.prompt_snapshot_overview_meta,
                            json.optString("model"),
                            counts?.optInt("system") ?: 0,
                            counts?.optInt("system_chars") ?: 0,
                            counts?.optInt("tools") ?: 0,
                            counts?.optInt("tools_chars") ?: 0,
                        ),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                EtaPreferenceDivider(hasLeading = true)
                EtaActionPreference(
                    title = if (baseName == fileName) {
                        stringResource(R.string.prompt_snapshot_base_clear)
                    } else {
                        stringResource(R.string.prompt_snapshot_base_set)
                    },
                    summary = if (baseName == fileName) null else stringResource(R.string.prompt_snapshot_base_hint),
                    startAction = { EtaPreferenceIcon(icon = Icons.Rounded.Compare, tint = EtaPreferenceColors.Green) },
                    onClick = {
                        val next = if (baseName == fileName) null else fileName
                        AgentRequestSnapshot.setBase(next)
                        baseName = next
                    },
                )
                EtaPreferenceDivider(hasLeading = true)
                EtaActionPreference(
                    title = stringResource(R.string.prompt_snapshot_copy_all),
                    summary = stringResource(R.string.prompt_snapshot_copy_all_hint),
                    startAction = { EtaPreferenceIcon(icon = Icons.Rounded.ContentCopy, tint = EtaPreferenceColors.Blue) },
                    onClick = {
                        copyPromptText(context, systemText(system))
                    },
                )
                EtaPreferenceDivider(hasLeading = true)
                EtaActionPreference(
                    title = stringResource(R.string.prompt_snapshot_export),
                    summary = file?.name.orEmpty(),
                    startAction = { EtaPreferenceIcon(icon = Icons.Rounded.IosShare, tint = EtaPreferenceColors.Orange) },
                    onClick = {
                        val target = file ?: return@EtaActionPreference
                        pendingExport = target
                        exportLauncher.launch(target.name)
                    },
                )
            }
        }

        if (diff != null) {
            item(key = "diff_title") {
                EtaPreferenceGroupTitle(stringResource(R.string.prompt_snapshot_diff_group))
            }
            item(key = "diff_summary") {
                EtaPreferenceGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(
                            text = stringResource(
                                R.string.prompt_snapshot_diff_headline,
                                diff.systemCountAfter - diff.systemCountBefore,
                                diff.systemCharsAfter - diff.systemCharsBefore,
                                diff.toolsAfter - diff.toolsBefore,
                            ),
                            style = MiuixTheme.textStyles.body2,
                            color = EtaPreferenceColors.Blue,
                        )
                        if (diff.identical) {
                            Text(
                                text = stringResource(R.string.prompt_snapshot_diff_same),
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        diff.systemChanges.forEach { change ->
                            Text(
                                text = stringResource(
                                    R.string.prompt_snapshot_diff_segment,
                                    segmentLabel(change.kind),
                                    change.beforeChars,
                                    change.afterChars,
                                    when (change.state) {
                                        SegmentChangeState.ADDED -> stringResource(R.string.prompt_snapshot_diff_added)
                                        SegmentChangeState.REMOVED -> stringResource(R.string.prompt_snapshot_diff_removed)
                                        SegmentChangeState.CHANGED -> stringResource(R.string.prompt_snapshot_diff_changed)
                                    },
                                ),
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        if (diff.unchangedSegments > 0) {
                            Text(
                                text = stringResource(R.string.prompt_snapshot_diff_unchanged, diff.unchangedSegments),
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                    if (diff.toolsRemoved.isNotEmpty()) {
                        EtaPreferenceDivider(hasLeading = true)
                        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                            Text(
                                text = stringResource(R.string.prompt_snapshot_diff_tools_removed, diff.toolsRemoved.size),
                                style = MiuixTheme.textStyles.body2,
                                color = EtaPreferenceColors.Orange,
                            )
                            Text(
                                text = diff.toolsRemoved.take(NAME_LIMIT).joinToString("、") +
                                    if (diff.toolsRemoved.size > NAME_LIMIT) {
                                        " 等 ${diff.toolsRemoved.size} 个"
                                    } else {
                                        ""
                                    },
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                    if (diff.toolsAdded.isNotEmpty()) {
                        EtaPreferenceDivider(hasLeading = true)
                        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                            Text(
                                text = stringResource(R.string.prompt_snapshot_diff_tools_added, diff.toolsAdded.size),
                                style = MiuixTheme.textStyles.body2,
                                color = EtaPreferenceColors.Green,
                            )
                            Text(
                                text = diff.toolsAdded.take(NAME_LIMIT).joinToString("、"),
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                    if (diff.toolDescRewritten.isNotEmpty()) {
                        EtaPreferenceDivider(hasLeading = true)
                        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                            Text(
                                text = stringResource(
                                    R.string.prompt_snapshot_diff_tools_rewritten,
                                    diff.toolDescRewritten.size,
                                ),
                                style = MiuixTheme.textStyles.body2,
                            )
                            val rewrittenParts = mutableListOf<String>()
                            for (change in diff.toolDescRewritten.take(NAME_LIMIT)) {
                                rewrittenParts += stringResource(
                                    R.string.prompt_snapshot_diff_tools_rewritten_item,
                                    change.name,
                                    change.beforeChars,
                                    change.afterChars,
                                )
                            }
                            Text(
                                text = rewrittenParts.joinToString("、"),
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        }

        item(key = "system_title") {
            EtaPreferenceGroupTitle(stringResource(R.string.prompt_snapshot_system_group, system?.length() ?: 0))
        }
        items(system?.length() ?: 0, key = { index -> "seg-$index" }) { index ->
            val message = system?.optJSONObject(index) ?: return@items
            val text = message.optString("text")
            val kind = AgentSnapshotAnalysis.classifySegment(text)
            val expanded = expandedSegment == index
            EtaPreferenceGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)) {
                EtaExpandPreference(
expanded = expanded,
                    title = stringResource(
                        R.string.prompt_snapshot_segment_row,
                        segmentLabel(kind),
                        message.optInt("chars"),
                    ),
                    summary = text.replace('\n', ' ').take(48),
                    startAction = { EtaPreferenceIcon(icon = Icons.Rounded.ViewAgenda, tint = EtaPreferenceColors.Blue) },
                    onClick = { expandedSegment = if (expanded) null else index },
                )
                if (expanded) {
                    EtaPreferenceDivider(hasLeading = true)
                    Text(
                        text = text,
                        style = MiuixTheme.textStyles.footnote2,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }

        item(key = "tools_title") {
            EtaPreferenceGroupTitle(stringResource(R.string.prompt_snapshot_tools_group, tools?.length() ?: 0))
        }
        item(key = "tools_toggle") {
            EtaPreferenceGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                EtaExpandPreference(
expanded = showTools,
                    title = stringResource(
                        if (showTools) R.string.prompt_snapshot_collapse
                        else R.string.prompt_snapshot_tools_expand,
                    ),
                    summary = stringResource(
                        R.string.prompt_snapshot_tools_summary,
                        tools?.length() ?: 0,
                        counts?.optInt("tools_chars") ?: 0,
                    ),
                    startAction = { EtaPreferenceIcon(icon = Icons.Rounded.Terminal, tint = EtaPreferenceColors.Orange) },
                    onClick = { showTools = !showTools },
                )
            }
        }
        if (showTools) {
            items(tools?.length() ?: 0, key = { index -> "tool-$index" }) { index ->
                val tool = tools?.optJSONObject(index) ?: return@items
                EtaPreferenceGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 1.dp)) {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(
                            text = tool.optString("name"),
                            style = MiuixTheme.textStyles.body2,
                        )
                        Text(
                            text = stringResource(R.string.prompt_snapshot_tool_chars, tool.optInt("chars")),
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }
    }
}

private fun systemText(system: org.json.JSONArray?): String = buildString {
    for (index in 0 until (system?.length() ?: 0)) {
        append(system?.optJSONObject(index)?.optString("text").orEmpty())
        append("\n\n")
    }
}

private const val NAME_LIMIT = 10
