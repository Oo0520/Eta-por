package io.github.mangi.eta.ui.screens.prompts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Compare
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.model.AgentRequestSnapshot
import io.github.mangi.eta.agent.model.AgentSnapshotAnalysis
import io.github.mangi.eta.agent.model.SnapshotKind
import io.github.mangi.eta.ui.components.EtaArrowPreference
import io.github.mangi.eta.ui.components.EtaActionPreference
import io.github.mangi.eta.ui.components.EtaActionTone
import io.github.mangi.eta.ui.components.EtaExpandPreference
import io.github.mangi.eta.ui.components.EtaWindowDialog
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.EtaPreferenceColors
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaPreferenceIcon
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

/**
 * 快照列表：一行一份，只给摘要；正文与差异全部下沉到详情页。
 */
@Composable
internal fun PromptSnapshotsScreen(
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val context = LocalContext.current
    var files by remember { mutableStateOf(AgentRequestSnapshot.list()) }
    var baseName by remember { mutableStateOf(AgentRequestSnapshot.baseName()) }
    var confirmClear by remember { mutableStateOf(false) }
    var showBasePicker by remember { mutableStateOf(false) }
    var showCleanup by remember { mutableStateOf(false) }
    var retain by remember { mutableStateOf(AgentRequestSnapshot.retainLimit()) }
    var sliderDraft by remember { mutableStateOf(retain.toFloat()) }
    var manualInput by remember { mutableStateOf(retain.toString()) }
    // 从详情页"设为基准"返回时，本页可能没被重建，remember 里的旧值会让标题仍显示未选择。
    LaunchedEffect(Unit) {
        baseName = AgentRequestSnapshot.baseName()
        files = AgentRequestSnapshot.list()
    }
    fun applyRetain(value: Int) {
        val next = value.coerceIn(MIN_RETAIN, MAX_RETAIN)
        AgentRequestSnapshot.setRetainLimit(next)
        retain = next
        sliderDraft = next.toFloat()
        manualInput = next.toString()
        files = AgentRequestSnapshot.list()
        // 目录读不到时（初始化缺失、权限异常、刚清空）不要把用户选的基准静默清掉：
        // 之前 list() 恒为空会导致每次进页面都把基准擦除，看起来像"选了没生效"。
        if (files.isNotEmpty() && AgentRequestSnapshot.baseName() !in files.map { it.name }) {
            AgentRequestSnapshot.setBase(null)
            baseName = null
        }
    }

    MiuixScaffoldPage(
        title = stringResource(R.string.prompt_snapshot_title),
        onBack = onBack,
    ) {
        item(key = "actions") {
            EtaPreferenceGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                EtaActionPreference(
                    title = stringResource(R.string.prompt_snapshot_refresh),
                    summary = stringResource(R.string.prompt_snapshot_hint),
                    startAction = { EtaPreferenceIcon(icon = Icons.Rounded.Refresh, tint = EtaPreferenceColors.Blue) },
                    onClick = { files = AgentRequestSnapshot.list() },
                )
                EtaPreferenceDivider(hasLeading = true)
                // 点这一行弹窗挑基准：不再就地铺一长串列表。
                EtaArrowPreference(
                    title = if (baseName == null) {
                        stringResource(R.string.prompt_snapshot_base_none)
                    } else {
                        stringResource(R.string.prompt_snapshot_base_set_named, baseName.orEmpty())
                    },
                    summary = stringResource(R.string.prompt_snapshot_base_hint),
                    startAction = { EtaPreferenceIcon(icon = Icons.Rounded.Compare, tint = EtaPreferenceColors.Green) },
                    onClick = { showBasePicker = true },
                )
                EtaPreferenceDivider(hasLeading = true)
                EtaExpandPreference(
expanded = showCleanup,
                    title = stringResource(R.string.prompt_snapshot_manage),
                    summary = stringResource(R.string.prompt_snapshot_manage_summary, files.size, retain),
                    startAction = { EtaPreferenceIcon(icon = Icons.Rounded.DeleteSweep, tint = EtaPreferenceColors.Orange) },
                    onClick = { showCleanup = !showCleanup },
                )
                if (showCleanup) {
                    EtaPreferenceDivider(hasLeading = true)
                    EtaActionPreference(
                        title = stringResource(R.string.prompt_snapshot_keep_value, retain),
                        summary = if (retain == 0) {
                            stringResource(R.string.prompt_snapshot_keep_off)
                        } else {
                            stringResource(R.string.prompt_snapshot_keep_range)
                        },
                        endActions = {
                            Text(
                                text = retain.toString(),
                                style = MiuixTheme.textStyles.body2,
                                color = EtaPreferenceColors.Blue,
                            )
                        },
                        bottomAction = {
                            Slider(
                                value = sliderDraft,
                                onValueChange = { sliderDraft = it },
                                valueRange = 0f..30f,
                                modifier = Modifier.fillMaxWidth(),
                                showKeyPoints = true,
                                keyPoints = listOf(0f, 10f, 20f, 30f),
                                hapticEffect = SliderDefaults.SliderHapticEffect.Step,
                                onValueChangeFinished = {
                                    val next = sliderDraft.roundToInt().coerceIn(MIN_RETAIN, MAX_RETAIN)
                                    sliderDraft = next.toFloat()
                                    applyRetain(next)
                                },
                            )
                        },
                    )
                    EtaPreferenceDivider(hasLeading = true)
                    Column(modifier = Modifier.padding(16.dp)) {
                        TextField(
                            value = manualInput,
                            onValueChange = { raw ->
                                val digits = raw.filter { it.isDigit() }.take(2)
                                manualInput = digits
                                digits.toIntOrNull()?.let { typed ->
                                    if (typed in MIN_RETAIN..MAX_RETAIN) applyRetain(typed)
                                }
                            },
                            label = stringResource(R.string.prompt_snapshot_keep_manual),
                            singleLine = true,
                        )
                        Text(
                            text = stringResource(R.string.prompt_snapshot_keep_manual_hint),
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    EtaPreferenceDivider(hasLeading = true)
                    EtaActionPreference(
                        title = stringResource(R.string.prompt_snapshot_prune_now),
                        onClick = {
                            AgentRequestSnapshot.setRetainLimit(retain)
                            files = AgentRequestSnapshot.list()
                            toast(context, context.getString(R.string.prompt_snapshot_kept, retain))
                        },
                    )
                    EtaPreferenceDivider(hasLeading = true)
                    EtaActionPreference(
tone = EtaActionTone.Danger,
                        title = stringResource(
                            if (confirmClear) R.string.prompt_snapshot_clear_confirm
                            else R.string.prompt_snapshot_clear_all,
                        ),
                        onClick = {
                            if (!confirmClear) {
                                confirmClear = true
                            } else {
                                confirmClear = false
                                val removed = AgentRequestSnapshot.clearAll()
                                AgentRequestSnapshot.setBase(null)
                                baseName = null
                                files = emptyList()
                                toast(context, context.getString(R.string.prompt_snapshot_cleared, removed))
                            }
                        },
                    )
                }
            }
        }

        if (files.isEmpty()) {
            item(key = "empty") {
                EtaPreferenceGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Text(
                        text = stringResource(R.string.prompt_snapshot_empty),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        } else {
            item(key = "list_title") {
                EtaPreferenceGroupTitle(stringResource(R.string.prompt_snapshot_list_group, files.size))
            }
        }

        items(files.size, key = { index -> files[index].name }) { index ->
            val file = files[index]
            val json = remember(file.name) { AgentRequestSnapshot.read(file) }
            val counts = json?.optJSONObject("counts")
            val isBase = file.name == baseName
            val kind = json?.let(AgentSnapshotAnalysis::kindOf)
            EtaPreferenceGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)) {
                EtaArrowPreference(
                    title = stringResource(
                        R.string.prompt_snapshot_row_title,
                        formatPromptTime(json?.optLong("at") ?: 0L),
                        kindLabel(kind),
                    ),
                    summary = stringResource(
                        R.string.prompt_snapshot_row_summary,
                        counts?.optInt("system") ?: 0,
                        counts?.optInt("system_chars") ?: 0,
                        counts?.optInt("tools") ?: 0,
                        (file.length() / 1024).coerceAtLeast(1),
                    ),
                    startAction = {
                        EtaPreferenceIcon(
                            icon = Icons.Rounded.Description,
                            tint = if (isBase) EtaPreferenceColors.Green else EtaPreferenceColors.Blue,
                        )
                    },
                    endActions = {
                        if (isBase) {
                            Text(
                                text = stringResource(R.string.prompt_snapshot_base_badge),
                                style = MiuixTheme.textStyles.footnote2,
                                color = EtaPreferenceColors.Green,
                            )
                        }
                    },
                    onClick = { onOpen(file.name) },
                )
            }
        }
    }

    if (showBasePicker) {
        BasePickerDialog(
            files = files,
            baseName = baseName,
            onPick = { name ->
                AgentRequestSnapshot.setBase(name)
                baseName = name
                showBasePicker = false
            },
            onDismiss = { showBasePicker = false },
        )
    }
}

@Composable
internal fun kindLabel(kind: SnapshotKind?): String = stringResource(
    when (kind) {
        SnapshotKind.REVIEW -> R.string.prompt_snapshot_kind_review
        SnapshotKind.CHAT_ONLY -> R.string.prompt_snapshot_kind_chat_only
        SnapshotKind.COMPACTION -> R.string.prompt_snapshot_kind_compaction
        SnapshotKind.MEMORY -> R.string.prompt_snapshot_kind_memory
        else -> R.string.prompt_snapshot_kind_chat
    },
)

private const val MIN_RETAIN = 0
private const val MAX_RETAIN = 30

/** 选择对比基准的弹窗：列表滚动，取消基准是底部一个小按钮。 */
@Composable
private fun BasePickerDialog(
    files: List<java.io.File>,
    baseName: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    EtaWindowDialog(
        show = true,
        title = stringResource(R.string.prompt_snapshot_base_picker_title),
        summary = stringResource(R.string.prompt_snapshot_base_hint),
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            files.forEach { file ->
                val selected = file.name == baseName
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(file.name) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = formatPromptTime(file.lastModified()),
                            style = MiuixTheme.textStyles.body2,
                            color = if (selected) EtaPreferenceColors.Green else MiuixTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = file.name,
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    if (selected) {
                        Text(
                            text = stringResource(R.string.prompt_snapshot_base_badge),
                            style = MiuixTheme.textStyles.footnote2,
                            color = EtaPreferenceColors.Green,
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            EtaTextButton(
                text = stringResource(R.string.prompt_snapshot_base_clear),
                onClick = { onPick(null) },
                enabled = baseName != null,
            )
            EtaTextButton(
                text = stringResource(R.string.prompt_snapshot_base_close),
                onClick = onDismiss,
            )
        }
    }
}
