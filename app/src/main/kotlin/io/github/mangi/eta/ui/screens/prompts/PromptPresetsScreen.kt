package io.github.mangi.eta.ui.screens.prompts

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.foundation.layout.padding
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
import io.github.mangi.eta.agent.prompt.AgentToolPolicyStore
import io.github.mangi.eta.agent.prompt.PromptCustomization
import io.github.mangi.eta.agent.prompt.PromptCustomizationStore
import io.github.mangi.eta.agent.prompt.PromptPreset
import io.github.mangi.eta.agent.prompt.PromptPresetCodec
import io.github.mangi.eta.agent.prompt.PromptPresetRepository
import io.github.mangi.eta.ui.components.EtaArrowPreference
import io.github.mangi.eta.ui.components.EtaActionPreference
import io.github.mangi.eta.ui.components.EtaActionTone
import io.github.mangi.eta.ui.components.EtaExpandPreference
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceColors
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaPreferenceIcon
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 预设页：内置预设一键套用、导入 zip/JSON 包、导出当前或某一份预设、一键恢复系统默认。
 *
 * 导入不做"半份套用"：解析失败直接报错；成功则应用并留档，随时可删。
 */
@Composable
internal fun PromptPresetsScreen(
    onBack: () -> Unit,
    onEditPreset: (String?) -> Unit,
) {
    val context = LocalContext.current
    var profile by remember { mutableStateOf(PromptCustomizationStore.load()) }
    var imported by remember { mutableStateOf(PromptPresetRepository.imported()) }
    val builtins = remember { PromptPresetRepository.builtins(context) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<PromptPreset?>(null) }
    var policy by remember { mutableStateOf(AgentToolPolicyStore.load()) }

    fun persist(next: PromptCustomization, message: String) {
        if (PromptCustomizationStore.save(next)) {
            profile = next
            toast(context, message)
        } else {
            toast(context, context.getString(R.string.prompt_studio_save_failed))
        }
    }

    fun applyPreset(preset: PromptPreset) {
        val next = PromptPresetRepository.applyPreset(profile, preset)
        // 提示词与工具范围一起换，这才是"模式"的完整语义。
        val nextPolicy = PromptPresetRepository.policyFor(preset)
        val policySaved = AgentToolPolicyStore.save(nextPolicy)
        if (policySaved) policy = nextPolicy
        persist(next, context.getString(R.string.prompt_preset_applied, preset.name, next.version))
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        val preset = pendingExport
        pendingExport = null
        if (uri == null || preset == null) return@rememberLauncherForActivityResult
        when (val result = PromptPresetCodec.write(context, uri, preset)) {
            is PromptPresetCodec.Result.Ok -> toast(context, context.getString(R.string.prompt_preset_exported))
            is PromptPresetCodec.Result.Failure -> toast(context, result.reason)
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        when (val result = PromptPresetCodec.read(context, uri)) {
            is PromptPresetCodec.Result.Ok -> {
                val preset = result.value
                val next = PromptPresetRepository.applyPreset(profile, preset)
                if (PromptCustomizationStore.save(next)) {
                    profile = next
                    imported = PromptPresetRepository.addImported(preset)
                    toast(context, context.getString(R.string.prompt_preset_applied, preset.name, next.version))
                } else {
                    toast(context, context.getString(R.string.prompt_studio_save_failed))
                }
            }
            is PromptPresetCodec.Result.Failure -> toast(context, result.reason)
        }
    }

    fun export(preset: PromptPreset) {
        pendingExport = preset
        exportLauncher.launch(exportFileName(preset.name))
    }

    MiuixScaffoldPage(
        title = stringResource(R.string.prompt_preset_title),
        onBack = onBack,
    ) {
        item(key = "actions") {
            EtaPreferenceGroup(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                EtaActionPreference(
                    title = stringResource(R.string.prompt_preset_import),
                    summary = stringResource(R.string.prompt_preset_import_hint),
                    startAction = {
                        EtaPreferenceIcon(icon = Icons.Rounded.FileOpen, tint = EtaPreferenceColors.Blue)
                    },
                    onClick = { importLauncher.launch(arrayOf("application/zip", "application/json", "*/*")) },
                )
                EtaPreferenceDivider(hasLeading = false)
                EtaArrowPreference(
                    title = stringResource(R.string.prompt_preset_new),
                    summary = stringResource(R.string.prompt_preset_new_summary),
                    onClick = { onEditPreset(null) },
                )
                EtaPreferenceDivider(hasLeading = false)
                EtaActionPreference(
                    title = stringResource(R.string.prompt_preset_export_current),
                    summary = stringResource(
                        R.string.prompt_preset_chars,
                        profile.baseSystemPrompt.length + profile.personaName.length +
                            profile.personaStyle.length + profile.appendInstructions.length,
                        stringResource(R.string.prompt_studio_version_current, profile.version, profile.revision),
                    ),
                    startAction = {
                        EtaPreferenceIcon(icon = Icons.Rounded.IosShare, tint = EtaPreferenceColors.Green)
                    },
                    onClick = {
                        export(
                            PromptPreset.fromCustomization(
                                profile,
                                policy,
                                name = context.getString(R.string.prompt_preset_current_name),
                            ),
                        )
                    },
                )
                EtaPreferenceDivider(hasLeading = false)
                EtaActionPreference(
                    title = stringResource(
                        if (confirmReset) R.string.prompt_preset_reset_confirm
                        else R.string.prompt_preset_reset,
                    ),
                    startAction = {
                        EtaPreferenceIcon(icon = Icons.Rounded.RestartAlt, tint = EtaPreferenceColors.Orange)
                    },
                    onClick = {
                        if (!confirmReset) {
                            confirmReset = true
                        } else {
                            confirmReset = false
                            persist(
                                profile.resetToDefault(),
                                context.getString(R.string.prompt_preset_reset_done, profile.version + 1),
                            )
                        }
                    },
                )
            }
        }

        item(key = "builtin_title") {
            EtaPreferenceGroupTitle(stringResource(R.string.prompt_preset_group_builtin))
        }
        item(key = "builtin") {
            EtaPreferenceGroup(modifier = Modifier.padding(horizontal = 16.dp)) {
                builtins.forEachIndexed { index, preset ->
                    if (index > 0) EtaPreferenceDivider(hasLeading = false)
                    PresetRow(
                        preset = preset,
                        expanded = expanded == preset.name,
                        onToggle = { expanded = if (expanded == preset.name) null else preset.name },
                        onApply = { applyPreset(preset) },
                        onExport = { export(preset) },
                        onDelete = null,
                        onEdit = { onEditPreset(preset.name) },
                    )
                }
            }
        }

        item(key = "imported_title") {
            EtaPreferenceGroupTitle(stringResource(R.string.prompt_preset_group_imported, imported.size))
        }
        item(key = "imported") {
            EtaPreferenceGroup(modifier = Modifier.padding(horizontal = 16.dp)) {
                if (imported.isEmpty()) {
                    Text(
                        text = stringResource(R.string.prompt_preset_imported_empty),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                imported.forEachIndexed { index, preset ->
                    if (index > 0) EtaPreferenceDivider(hasLeading = false)
                    PresetRow(
                        preset = preset,
                        expanded = expanded == preset.name,
                        onToggle = { expanded = if (expanded == preset.name) null else preset.name },
                        onApply = { applyPreset(preset) },
                        onExport = { export(preset) },
                        onDelete = {
                            imported = PromptPresetRepository.removeImported(preset.name)
                            expanded = null
                            toast(context, context.getString(R.string.prompt_preset_deleted, preset.name))
                        },
                        onEdit = { onEditPreset(preset.name) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PresetRow(
    preset: PromptPreset,
    expanded: Boolean,
    onToggle: () -> Unit,
    onApply: () -> Unit,
    onExport: () -> Unit,
    onDelete: (() -> Unit)?,
    onEdit: () -> Unit,
) {
    EtaExpandPreference(
expanded = expanded,
        title = preset.name,
        summary = buildString {
            append(
                preset.description.ifBlank {
                    stringResource(R.string.prompt_preset_chars_only, preset.contentLength)
                },
            )
            append(" · ")
            append(
                if (preset.toolMode == io.github.mangi.eta.agent.prompt.ToolPolicyMode.ALLOW_ALL) {
                    stringResource(R.string.prompt_preset_tools_all)
                } else {
                    stringResource(R.string.prompt_preset_tools_count, preset.allowedTools.size)
                },
            )
        },
        startAction = {
            EtaPreferenceIcon(
                icon = if (preset.builtin) Icons.Rounded.Inventory2 else Icons.Rounded.Bookmark,
                tint = if (preset.builtin) EtaPreferenceColors.Blue else EtaPreferenceColors.Yellow,
            )
        },
        onClick = onToggle,
    )
    if (!expanded) return
    Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp)) {
        Text(
            text = stringResource(R.string.prompt_preset_detail, preset.mode.name, preset.contentLength),
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
    EtaActionPreference(title = stringResource(R.string.prompt_preset_edit), onClick = onEdit)
    EtaActionPreference(title = stringResource(R.string.prompt_preset_apply), onClick = onApply)
    EtaActionPreference(title = stringResource(R.string.prompt_preset_export), onClick = onExport)
    if (onDelete != null) {
        EtaActionPreference(
tone = EtaActionTone.Danger,title = stringResource(R.string.prompt_preset_delete), onClick = onDelete)
    }
}

private fun exportFileName(name: String): String {
    val safe = name.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "-").take(40).ifBlank { "preset" }
    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.getDefault()).format(Date())
    return "Eta提示词-" + safe + "-" + stamp + ".zip"
}
