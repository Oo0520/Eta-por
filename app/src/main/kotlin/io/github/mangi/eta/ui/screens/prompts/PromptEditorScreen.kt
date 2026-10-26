package io.github.mangi.eta.ui.screens.prompts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Compare
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Tune
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
import io.github.mangi.eta.agent.prompt.AgentToolPolicy
import io.github.mangi.eta.agent.prompt.AgentToolPolicyStore
import io.github.mangi.eta.agent.prompt.PromptConflictAnalyzer
import io.github.mangi.eta.agent.prompt.PromptCustomizationMode
import io.github.mangi.eta.agent.prompt.PromptCustomizationStore
import io.github.mangi.eta.agent.prompt.PromptPreset
import io.github.mangi.eta.agent.prompt.PromptPresetRepository
import io.github.mangi.eta.agent.prompt.ToolPolicyMode
import io.github.mangi.eta.ui.components.EtaArrowPreference
import io.github.mangi.eta.ui.components.EtaActionPreference
import io.github.mangi.eta.ui.components.EtaExpandPreference
import io.github.mangi.eta.ui.components.EtaPreferenceColors
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaPreferenceIcon
import io.github.mangi.eta.ui.components.EtaSwitchPreference
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 提示词编辑器：一份 UI 同时服务两种目标。
 *
 * - [presetMode] = false：编辑"当前配置"（自定义提示词 + 工具边界），保存即生效；
 * - [presetMode] = true：编辑预设（提示词 + 工具集一起），[presetName] 为空表示新建。
 *
 * 两种目标共用同一套工具选择器（[toolSelectionItems]），不再各写一份。
 */
@Composable
internal fun PromptEditorScreen(
    onBack: () -> Unit,
    presetMode: Boolean = false,
    presetName: String? = null,
    onOpenNewPreset: () -> Unit = {},
    onManageGroups: () -> Unit = {},
) {
    val context = LocalContext.current
    var mcpToolNames by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(Unit) { mcpToolNames = loadMcpToolNames() }
    val currentProfile = remember { PromptCustomizationStore.load() }
    val currentPolicy = remember { AgentToolPolicyStore.load() }
    val existing = remember(presetName, presetMode) {
        if (presetMode && presetName != null) PromptPresetRepository.find(context, presetName) else null
    }

    var savedProfile by remember { mutableStateOf(currentProfile) }
    var name by remember {
        mutableStateOf(
            existing?.name?.let { if (existing.builtin) context.getString(R.string.prompt_preset_copy_name, it) else it }
                ?: "",
        )
    }
    var description by remember { mutableStateOf(existing?.description.orEmpty()) }
    var enabled by remember { mutableStateOf(existing?.let { true } ?: currentProfile.enabled) }
    var mode by remember { mutableStateOf(existing?.mode ?: currentProfile.mode) }
    var base by remember { mutableStateOf(existing?.baseSystemPrompt ?: currentProfile.baseSystemPrompt) }
    var personaName by remember { mutableStateOf(existing?.personaName ?: currentProfile.personaName) }
    var personaStyle by remember { mutableStateOf(existing?.personaStyle ?: currentProfile.personaStyle) }
    var append by remember { mutableStateOf(existing?.appendInstructions ?: currentProfile.appendInstructions) }
    var selector by remember {
        mutableStateOf(
            ToolSelectorState(
                mode = existing?.toolMode ?: currentPolicy.mode,
                allowed = existing?.allowedTools ?: currentPolicy.allowedTools,
                customGroups = io.github.mangi.eta.agent.prompt.ToolGroupStore.load(),
            ),
        )
    }
    val toolMode = selector.mode
    val allowed = selector.allowed

    val draft = savedProfile.copy(
        enabled = enabled,
        mode = mode,
        baseSystemPrompt = base,
        personaName = personaName,
        personaStyle = personaStyle,
        appendInstructions = append,
    )
    val draftPolicy = AgentToolPolicy(mode = toolMode, allowedTools = allowed)
    // 大段文本每次重组都跑一次正则分词会明显卡；改成停止输入 400ms 后再算。
    var settled by remember { mutableStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(draft, draftPolicy) {
        kotlinx.coroutines.delay(400)
        settled++
    }
    val conflicts = remember(settled, draftPolicy) {
        PromptConflictAnalyzer.analyze(draft, roleplayActive = false, toolPolicy = draftPolicy)
    }
    val blocking = PromptConflictAnalyzer.hasError(conflicts)

    // 改前/改后对比：按需展开才计算，避免每敲一个字就重组 8 条 system。
    var showEditDiff by remember { mutableStateOf(false) }
    var beforePreview by remember { mutableStateOf<EffectivePreview?>(null) }
    var afterPreview by remember { mutableStateOf<EffectivePreview?>(null) }
    var diffTick by remember { mutableStateOf(0) }
    LaunchedEffect(draft, toolMode, allowed, showEditDiff) {
        if (!showEditDiff) return@LaunchedEffect
        kotlinx.coroutines.delay(500)
        diffTick++
    }
    LaunchedEffect(showEditDiff, diffTick) {
        if (!showEditDiff) {
            beforePreview = null
            afterPreview = null
            return@LaunchedEffect
        }
        // 前后都要带上各自的白名单，否则工具集永远相同，增删恒为 0。
        beforePreview = buildEffectivePreview(context, savedProfile, currentPolicy)
        afterPreview = buildEffectivePreview(context, draft, draftPolicy)
    }
    val dirty = if (presetMode) {
        true
    } else {
        draft != savedProfile || draftPolicy.mode != currentPolicy.mode ||
            draftPolicy.allowedTools != currentPolicy.allowedTools
    }

    fun saveCurrent() {
        val next = savedProfile.edit(
            enabled = enabled,
            mode = mode,
            baseSystemPrompt = base,
            personaName = personaName,
            personaStyle = personaStyle,
            appendInstructions = append,
        )
        val profileSaved = PromptCustomizationStore.save(next)
        val policySaved = AgentToolPolicyStore.save(currentPolicy.copy(mode = toolMode, allowedTools = allowed))
        if (profileSaved && policySaved) {
            savedProfile = next
            toast(context, context.getString(R.string.prompt_studio_saved, next.version))
        } else {
            toast(context, context.getString(R.string.prompt_studio_save_failed))
        }
    }

    fun savePreset() {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            toast(context, context.getString(R.string.prompt_preset_editor_need_name))
            return
        }
        val template = existing ?: PromptPreset.fromCustomization(currentProfile, currentPolicy, trimmed)
        PromptPresetRepository.upsert(
            template.copy(
                name = trimmed,
                description = description.trim(),
                mode = mode,
                baseSystemPrompt = base,
                personaName = personaName,
                personaStyle = personaStyle,
                appendInstructions = append,
                toolMode = toolMode,
                allowedTools = allowed,
                builtin = false,
            ),
        )
        toast(context, context.getString(R.string.prompt_preset_editor_saved, trimmed))
        onBack()
    }

    fun applyToCurrent() {
        val next = PromptPresetRepository.applyPreset(savedProfile, existing ?: PromptPreset(name = name))
        PromptCustomizationStore.save(next)
        AgentToolPolicyStore.save(currentPolicy.copy(mode = toolMode, allowedTools = allowed))
        toast(context, context.getString(R.string.prompt_preset_applied, name, next.version))
    }

    MiuixScaffoldPage(
        title = stringResource(
            when {
                !presetMode -> R.string.prompt_studio_editor_title
                existing == null -> R.string.prompt_preset_editor_new
                else -> R.string.prompt_preset_editor_title
            },
        ),
        onBack = onBack,
        modifier = Modifier.imePadding(),
    ) {
        item(key = "save") {
            EtaPreferenceGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                EtaActionPreference(
                    title = when {
                        blocking -> stringResource(R.string.prompt_studio_save_blocked)
                        presetMode -> stringResource(R.string.prompt_preset_editor_save)
                        !dirty -> stringResource(R.string.prompt_studio_save_clean_title)
                        !draft.hasContent -> stringResource(R.string.prompt_studio_save_empty)
                        else -> stringResource(R.string.prompt_studio_save)
                    },
                    summary = if (blocking) {
                        stringResource(R.string.prompt_studio_save_blocked_hint)
                    } else if (presetMode) {
                        if (existing?.builtin == true) {
                            stringResource(R.string.prompt_preset_editor_copy_hint)
                        } else {
                            stringResource(R.string.prompt_preset_editor_save_hint)
                        }
                    } else {
                        stringResource(R.string.prompt_studio_save_hint)
                    },
                    startAction = { EtaPreferenceIcon(icon = Icons.Rounded.Save, tint = EtaPreferenceColors.Blue) },
                    onClick = {
                        if (blocking) return@EtaActionPreference
                        if (presetMode) savePreset() else saveCurrent()
                    },
                )
                if (presetMode && existing != null) {
                    EtaPreferenceDivider(hasLeading = true)
                    EtaActionPreference(
                        title = stringResource(R.string.prompt_preset_apply_here),
                        summary = stringResource(R.string.prompt_preset_apply_here_hint),
                        startAction = { EtaPreferenceIcon(icon = Icons.Rounded.PlayArrow, tint = EtaPreferenceColors.Green) },
                        onClick = { applyToCurrent() },
                    )
                }
                if (!presetMode) {
                    EtaPreferenceDivider(hasLeading = true)
                    EtaArrowPreference(
                        title = stringResource(R.string.prompt_preset_save_as),
                        summary = stringResource(R.string.prompt_preset_save_as_hint),
                        startAction = { EtaPreferenceIcon(icon = Icons.Rounded.Bookmark, tint = EtaPreferenceColors.Yellow) },
                        onClick = onOpenNewPreset,
                    )
                }
            }
        }

        if (presetMode) {
            item(key = "meta_title") {
                EtaPreferenceGroupTitle(stringResource(R.string.prompt_preset_editor_meta_group))
            }
            item(key = "meta") {
                EtaPreferenceGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        TextField(
                            value = name,
                            onValueChange = { name = it },
                            label = stringResource(R.string.prompt_preset_editor_name),
                            modifier = Modifier.padding(bottom = 12.dp),
                            singleLine = true,
                        )
                        TextField(
                            value = description,
                            onValueChange = { description = it },
                            label = stringResource(R.string.prompt_preset_editor_description),
                            singleLine = false,
                        )
                    }
                }
            }
        } else {
            item(key = "enable_title") {
                EtaPreferenceGroupTitle(stringResource(R.string.prompt_studio_identity_group))
            }
            item(key = "enable") {
                EtaPreferenceGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    EtaSwitchPreference(
                        title = stringResource(R.string.prompt_studio_enable),
                        summary = customizationStateText(draft),
                        checked = enabled,
                        onCheckedChange = { enabled = it },
                    )
                }
            }
        }

        item(key = "mode_title") {
            EtaPreferenceGroupTitle(stringResource(R.string.prompt_studio_mode_group))
        }
        item(key = "mode") {
            EtaPreferenceGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                EtaActionPreference(
                    title = stringResource(R.string.prompt_studio_mode_append),
                    summary = stringResource(R.string.prompt_studio_mode_append_hint),
                    startAction = { EtaPreferenceIcon(icon = Icons.Rounded.Tune, tint = EtaPreferenceColors.Blue) },
                    endActions = { if (mode == PromptCustomizationMode.APPEND) Selected() },
                    onClick = { mode = PromptCustomizationMode.APPEND },
                )
                EtaPreferenceDivider(hasLeading = true)
                EtaActionPreference(
                    title = stringResource(R.string.prompt_studio_mode_replace),
                    summary = stringResource(R.string.prompt_studio_mode_replace_hint),
                    startAction = { EtaPreferenceIcon(icon = Icons.Rounded.Tune, tint = EtaPreferenceColors.Orange) },
                    endActions = { if (mode == PromptCustomizationMode.REPLACE) Selected() },
                    onClick = { mode = PromptCustomizationMode.REPLACE },
                )
            }
        }

        item(key = "content_title") {
            EtaPreferenceGroupTitle(stringResource(R.string.prompt_studio_content_group))
        }
        item(key = "content") {
            EtaPreferenceGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    PromptTextField(
                        label = stringResource(R.string.prompt_studio_base_prompt),
                        value = base,
                        onValueChange = { base = it },
                    )
                    PromptTextField(
                        label = stringResource(R.string.prompt_studio_persona_name),
                        value = personaName,
                        onValueChange = { personaName = it },
                        singleLine = true,
                    )
                    PromptTextField(
                        label = stringResource(R.string.prompt_studio_persona_style),
                        value = personaStyle,
                        onValueChange = { personaStyle = it },
                    )
                    PromptTextField(
                        label = stringResource(R.string.prompt_studio_append),
                        value = append,
                        onValueChange = { append = it },
                    )
                }
            }
        }

        item(key = "grouping") {
            EtaPreferenceGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                listOf("功能", "权限", "字母").forEachIndexed { index, option ->
                    if (index > 0) EtaPreferenceDivider(hasLeading = true)
                    EtaActionPreference(
                        title = stringResource(
                            when (option) {
                                "功能" -> R.string.prompt_tool_group_by_feature
                                "权限" -> R.string.prompt_tool_group_by_root
                                else -> R.string.prompt_tool_group_by_letter
                            },
                        ),
                        endActions = {
                            if (selector.grouping == option) {
                                Text(
                                    text = stringResource(R.string.prompt_studio_mode_selected),
                                    style = MiuixTheme.textStyles.footnote2,
                                    color = EtaPreferenceColors.Blue,
                                )
                            }
                        },
                        onClick = { selector = selector.copy(grouping = option) },
                    )
                }
            }
        }

        toolSelectionItems(
            state = selector,
            onChange = { selector = it },
            onManageGroups = onManageGroups,
            extraTools = mcpToolNames,
        )

        item(key = "diff_title") {
            EtaPreferenceGroupTitle(stringResource(R.string.prompt_edit_diff_group))
        }
        item(key = "diff_entry") {
            EtaPreferenceGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                EtaExpandPreference(
expanded = showEditDiff,
                    title = stringResource(
                        if (showEditDiff) R.string.prompt_edit_diff_hide else R.string.prompt_edit_diff_show,
                    ),
                    summary = stringResource(R.string.prompt_edit_diff_hint),
                    startAction = {
                        EtaPreferenceIcon(icon = Icons.Rounded.Compare, tint = EtaPreferenceColors.Green)
                    },
                    onClick = { showEditDiff = !showEditDiff },
                )
            }
        }

        if (showEditDiff && beforePreview != null && afterPreview != null) {
            item(key = "diff_body") {
                EditDiffCard(beforePreview!!, afterPreview!!)
            }
        }

        item(key = "conflict_title") {
            EtaPreferenceGroupTitle(stringResource(R.string.prompt_studio_conflict_group, conflicts.size))
        }
        item(key = "conflicts") {
            EtaPreferenceGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                if (conflicts.isEmpty()) {
                    Text(
                        text = stringResource(R.string.prompt_studio_conflict_none),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                conflicts.forEachIndexed { index, conflict ->
                    if (index > 0) EtaPreferenceDivider(hasLeading = false)
                    ConflictRow(conflict)
                }
            }
        }
    }
}

@Composable
private fun Selected() {
    Text(
        text = stringResource(R.string.prompt_studio_mode_selected),
        style = MiuixTheme.textStyles.footnote2,
        color = EtaPreferenceColors.Blue,
    )
}
