package io.github.mangi.eta.ui.screens.prompts

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Terminal
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
import io.github.mangi.eta.agent.model.SystemSegmentKind
import io.github.mangi.eta.agent.tool.AgentToolNaming
import io.github.mangi.eta.ui.components.EtaActionPreference
import io.github.mangi.eta.ui.components.EtaArrowPreference
import io.github.mangi.eta.ui.components.EtaPreferenceColors
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaPreferenceIcon
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import org.json.JSONArray
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File

/**
 * 当前生效提示词：直接展示最近一份「主对话」请求快照。
 *
 * 不再用配置重建一份（那条路反复与实发不一致），这里看到的与快照页完全同源。
 */
@Composable
internal fun PromptEffectiveScreen(
    onBack: () -> Unit,
    onOpenSegment: (Int) -> Unit,
) {
    var snapshot by remember { mutableStateOf<EffectiveSnapshot?>(null) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        snapshot = loadLatestMainChatSnapshot()
        loaded = true
    }
    val context = LocalContext.current
    val pv = snapshot

    MiuixScaffoldPage(
        title = stringResource(R.string.prompt_effective_title),
        onBack = onBack,
    ) {
        if (!loaded) {
            item(key = "loading") {
                EtaPreferenceGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text(
                        text = stringResource(R.string.prompt_effective_loading),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            return@MiuixScaffoldPage
        }
        if (pv == null) {
            item(key = "empty") {
                EtaPreferenceGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text(
                        text = stringResource(R.string.prompt_effective_no_snapshot),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            return@MiuixScaffoldPage
        }

        item(key = "summary") {
            EtaPreferenceGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Text(
                        text = stringResource(R.string.prompt_studio_preview_model, pv.model.ifBlank { "—" }),
                        style = MiuixTheme.textStyles.body2,
                    )
                    Text(
                        text = stringResource(
                            R.string.prompt_effective_summary,
                            pv.system.length(),
                            pv.systemChars,
                            pv.tools.length(),
                        ),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Text(
                        text = stringResource(
                            R.string.prompt_effective_source,
                            formatPromptTime(pv.at),
                            pv.file.name,
                        ),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }

        item(key = "segments-title") {
            EtaPreferenceGroupTitle(stringResource(R.string.prompt_effective_segments_group, pv.system.length()))
        }
        items(pv.system.length(), key = { index -> "seg-$index" }) { index ->
            val segment = pv.system.optJSONObject(index)
            val text = segment?.optString("text").orEmpty()
            val kind = AgentSnapshotAnalysis.classifySegment(text)
            EtaPreferenceGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)) {
                EtaArrowPreference(
                    title = segmentLabel(kind),
                    summary = stringResource(R.string.prompt_effective_chars, segment?.optInt("chars") ?: 0),
                    onClick = { onOpenSegment(index) },
                )
            }
        }

        item(key = "tools-title") { EtaPreferenceGroupTitle(stringResource(R.string.prompt_effective_tools_group)) }
        item(key = "tools-entry") {
            EtaPreferenceGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)) {
                EtaArrowPreference(
                    title = stringResource(R.string.prompt_effective_tools_entry, pv.tools.length()),
                    summary = stringResource(R.string.prompt_effective_tools_chars, pv.toolsChars),
                    startAction = { EtaPreferenceIcon(icon = Icons.Rounded.Terminal, tint = EtaPreferenceColors.Orange) },
                    onClick = { onOpenSegment(-1) },
                )
            }
        }

        item(key = "actions") {
            EtaPreferenceGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                EtaActionPreference(
                    title = stringResource(R.string.prompt_studio_preview_copy),
                    summary = stringResource(R.string.prompt_studio_preview_count, pv.system.length()),
                    startAction = { EtaPreferenceIcon(icon = Icons.Rounded.ContentCopy, tint = EtaPreferenceColors.Blue) },
                    onClick = { copyPromptText(context, systemTextOf(pv.system)) },
                )
                EtaPreferenceDivider(hasLeading = true)
                ExportRow(snapshot = pv)
            }
        }
    }
}

@Composable
private fun ExportRow(snapshot: EffectiveSnapshot) {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<File?>(null) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val target = pending
        pending = null
        if (uri == null || target == null) return@rememberLauncherForActivityResult
        val ok = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { output -> output.write(target.readBytes()) } != null
        }.getOrDefault(false)
        toast(
            context,
            context.getString(if (ok) R.string.prompt_snapshot_exported else R.string.prompt_snapshot_export_failed),
        )
    }
    EtaActionPreference(
        title = stringResource(R.string.prompt_effective_export),
        summary = snapshot.file.name,
        startAction = { EtaPreferenceIcon(icon = Icons.Rounded.IosShare, tint = EtaPreferenceColors.Green) },
        onClick = {
            pending = snapshot.file
            launcher.launch(snapshot.file.name)
        },
    )
}

/** 单段全文 / 工具清单页（segment = -1 表示工具清单），同样读那份快照。 */
@Composable
internal fun PromptEffectiveItemScreen(
    segment: Int,
    onBack: () -> Unit,
) {
    var snapshot by remember { mutableStateOf<EffectiveSnapshot?>(null) }
    LaunchedEffect(Unit) { snapshot = loadLatestMainChatSnapshot() }
    val context = LocalContext.current
    val pv = snapshot

    if (segment < 0) {
        MiuixScaffoldPage(
            title = stringResource(R.string.prompt_effective_tools_group),
            onBack = onBack,
        ) {
            val tools = pv?.tools ?: JSONArray()
            item(key = "tools-meta") {
                EtaPreferenceGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                        Text(
                            text = stringResource(R.string.prompt_effective_tools_meta, tools.length(), pv?.toolsChars ?: 0),
                            style = MiuixTheme.textStyles.body2,
                        )
                    }
                }
            }
            items(tools.length(), key = { index -> "tool-$index" }) { index ->
                val tool = tools.optJSONObject(index) ?: return@items
                val name = tool.optString("name")
                EtaPreferenceGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 1.dp)) {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text(text = AgentToolNaming.face(name).label, style = MiuixTheme.textStyles.body2)
                        Text(
                            text = name + " · " + stringResource(R.string.prompt_effective_chars, tool.optInt("chars")),
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }
        return
    }

    val segmentJson: JSONObject? = pv?.system?.optJSONObject(segment)
    val content = segmentJson?.optString("text").orEmpty()
    val kind = AgentSnapshotAnalysis.classifySegment(content)
    MiuixScaffoldPage(
        title = stringResource(R.string.prompt_effective_segment_title, segment + 1, segmentLabel(kind)),
        onBack = onBack,
        compactTitle = true,
    ) {
        item(key = "actions") {
            EtaPreferenceGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                EtaActionPreference(
                    title = stringResource(R.string.prompt_effective_copy_segment),
                    summary = stringResource(R.string.prompt_effective_chars, content.length),
                    startAction = { EtaPreferenceIcon(icon = Icons.Rounded.ContentCopy, tint = EtaPreferenceColors.Blue) },
                    onClick = { copyPromptText(context, content) },
                )
            }
        }
        item(key = "body") {
            EtaPreferenceGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text(
                    text = content.ifBlank { stringResource(R.string.prompt_effective_empty_segment) },
                    style = MiuixTheme.textStyles.footnote2,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

/** 概览页那一行的摘要：同样读快照，避免与页面里显示的不是同一个来源。 */
@Composable
internal fun rememberEffectiveSnapshotSummary(): String {
    var snap by remember { mutableStateOf<EffectiveSnapshot?>(null) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        snap = loadLatestMainChatSnapshot()
        loaded = true
    }
    val value = snap
    return when {
        !loaded -> stringResource(R.string.prompt_effective_loading)
        value == null -> stringResource(R.string.prompt_effective_no_snapshot_short)
        else -> stringResource(
            R.string.prompt_effective_entry_summary,
            value.system.length(),
            value.systemChars,
            value.tools.length(),
        )
    }
}

internal fun segmentKindOf(text: String): SystemSegmentKind = AgentSnapshotAnalysis.classifySegment(text)

internal fun systemCharsOf(system: JSONArray): Int {
    var chars = 0
    for (index in 0 until system.length()) {
        chars += system.optJSONObject(index)?.optString("content")?.length
            ?: system.optJSONObject(index)?.optString("text")?.length ?: 0
    }
    return chars
}

internal fun toolsCharsOf(tools: JSONArray): Int {
    var chars = 0
    for (index in 0 until tools.length()) {
        chars += tools.optJSONObject(index)?.optString("description")?.length ?: 0
    }
    return chars
}

internal fun systemTextOf(system: JSONArray): String = buildString {
    for (index in 0 until system.length()) {
        val item = system.optJSONObject(index)
        append(item?.optString("text").orEmpty().ifBlank { item?.optString("content").orEmpty() })
        append("\n\n")
    }
}
