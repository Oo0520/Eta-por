package io.github.mangi.eta.ui.screens.prompts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Save
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
import io.github.mangi.eta.agent.prompt.AgentToolPolicy
import io.github.mangi.eta.agent.prompt.AgentToolPolicyStore
import io.github.mangi.eta.agent.prompt.ToolGroupStore
import io.github.mangi.eta.agent.prompt.ToolPolicyMode
import io.github.mangi.eta.agent.tool.AgentToolNaming
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
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 工具组合管理：内置三组只读可直接套用，自定义组合可新建、改名、删除。
 *
 * 每个控件独占一组卡片，避免输入框与行标题画在同一坐标（之前那处文字重叠）。
 */
@Composable
internal fun PromptToolGroupsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var policy by remember { mutableStateOf(AgentToolPolicyStore.load()) }
    var groups by remember { mutableStateOf(ToolGroupStore.load()) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var newName by remember { mutableStateOf("") }
    var renameInput by remember { mutableStateOf("") }

    val applyGroup: (String, Set<String>) -> Unit = { label, tools ->
        val existing = tools.filter { it in ALL_TOOL_NAMES }.toSet()
        val next = AgentToolPolicy(
            mode = ToolPolicyMode.ALLOW_LIST,
            allowedTools = existing,
            version = policy.version + 1,
        )
        if (AgentToolPolicyStore.save(next)) {
            policy = next
            toast(context, context.getString(R.string.prompt_groups_applied, label, existing.size))
        } else {
            toast(context, context.getString(R.string.prompt_studio_save_failed))
        }
    }

    MiuixScaffoldPage(
        title = stringResource(R.string.prompt_groups_title),
        onBack = onBack,
        modifier = Modifier.imePadding(),
    ) {
        item(key = "current") {
            EtaPreferenceGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                EtaActionPreference(
                    title = stringResource(R.string.prompt_groups_current_count, policy.allowedTools.size),
                    summary = stringResource(R.string.prompt_groups_current_hint),
                    startAction = { EtaPreferenceIcon(icon = Icons.Rounded.Checklist, tint = EtaPreferenceColors.Blue) },
                )
            }
        }

        item(key = "builtin-title") { EtaPreferenceGroupTitle(stringResource(R.string.prompt_groups_builtin)) }
        item(key = "builtin-body") {
            EtaPreferenceGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                COMMON_TOOL_SETS.forEachIndexed { index, (label, names) ->
                    if (index > 0) EtaPreferenceDivider(hasLeading = true)
                    val existing = names.filter { it in ALL_TOOL_NAMES }
                    EtaActionPreference(
                        title = label,
                        actionLabel = stringResource(R.string.prompt_action_apply),
                        summary = stringResource(R.string.prompt_groups_tools_count, existing.size) +
                            " · " + stringResource(R.string.prompt_groups_apply_hint),
                        startAction = { EtaPreferenceIcon(icon = Icons.Rounded.Checklist, tint = EtaPreferenceColors.Green) },
                        onClick = { applyGroup(label, existing.toSet()) },
                    )
                }
            }
        }

        item(key = "custom-title") {
            EtaPreferenceGroupTitle(stringResource(R.string.prompt_groups_custom, groups.size))
        }
        if (groups.isEmpty()) {
            item(key = "custom-empty") {
                EtaPreferenceGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Text(
                        text = stringResource(R.string.prompt_groups_none),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
        groups.forEach { group ->
            item(key = "custom-" + group.name) {
                EtaPreferenceGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)) {
                    EtaExpandPreference(
expanded = expanded == group.name,
                        title = group.name,
                        summary = stringResource(R.string.prompt_groups_tools_count, group.tools.size) +
                            if (expanded == group.name) {
                                " · " + stringResource(R.string.prompt_groups_expanded_hint)
                            } else {
                                " · " + stringResource(R.string.prompt_groups_apply_hint)
                            },
                        startAction = { EtaPreferenceIcon(icon = Icons.Rounded.Checklist, tint = EtaPreferenceColors.Yellow) },
                        onClick = {
                            if (expanded == group.name) {
                                expanded = null
                            } else {
                                expanded = group.name
                                renameInput = group.name
                            }
                        },
                    )
                }
            }
            if (expanded == group.name) {
                item(key = "edit-body-" + group.name) {
                    EtaPreferenceGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        EtaActionPreference(
                            title = stringResource(R.string.prompt_groups_apply_now, group.tools.size),
                            startAction = { EtaPreferenceIcon(icon = Icons.Rounded.Checklist, tint = EtaPreferenceColors.Blue) },
                            onClick = { applyGroup(group.name, group.tools) },
                        )
                        EtaPreferenceDivider(hasLeading = true)
                        EtaActionPreference(
tone = EtaActionTone.Danger,
                            title = stringResource(R.string.prompt_groups_delete),
                            onClick = {
                                if (ToolGroupStore.deleteGroup(group.name)) {
                                    groups = ToolGroupStore.load()
                                    expanded = null
                                    toast(context, context.getString(R.string.prompt_groups_deleted, group.name))
                                }
                            },
                        )
                    }
                }
                item(key = "edit-name-" + group.name) {
                    EtaPreferenceGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        TextField(
                            value = renameInput,
                            onValueChange = { renameInput = it },
                            label = stringResource(R.string.prompt_groups_rename_hint),
                            modifier = Modifier.padding(16.dp),
                            singleLine = true,
                        )
                    }
                }
                item(key = "edit-name-save-" + group.name) {
                    EtaPreferenceGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        EtaActionPreference(
                            title = stringResource(R.string.prompt_groups_rename),
                            startAction = { EtaPreferenceIcon(icon = Icons.Rounded.Save, tint = EtaPreferenceColors.Blue) },
                            onClick = {
                                val target = renameInput.trim()
                                if (target.isEmpty()) {
                                    toast(context, context.getString(R.string.prompt_groups_name_required))
                                } else if (ToolGroupStore.renameGroup(group.name, target)) {
                                    groups = ToolGroupStore.load()
                                    expanded = target
                                    toast(context, context.getString(R.string.prompt_groups_renamed))
                                }
                            },
                        )
                    }
                }
                item(key = "edit-tools-" + group.name) {
                    EtaPreferenceGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                            Text(
                                text = stringResource(
                                    R.string.prompt_groups_tools_preview,
                                    group.tools.take(8).joinToString("、") { AgentToolNaming.face(it).label },
                                ),
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
            }
        }

        item(key = "new-title") { EtaPreferenceGroupTitle(stringResource(R.string.prompt_groups_new_title)) }
        item(key = "new-name") {
            EtaPreferenceGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                TextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = stringResource(R.string.prompt_groups_rename_hint),
                    modifier = Modifier.padding(16.dp),
                    singleLine = true,
                )
            }
        }
        item(key = "new-save") {
            EtaPreferenceGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                EtaActionPreference(
                    title = stringResource(R.string.prompt_groups_save),
                    summary = stringResource(R.string.prompt_groups_new_summary, policy.allowedTools.size),
                    startAction = { EtaPreferenceIcon(icon = Icons.Rounded.CreateNewFolder, tint = EtaPreferenceColors.Green) },
                    onClick = {
                        val target = newName.trim()
                        if (target.isEmpty()) {
                            toast(context, context.getString(R.string.prompt_groups_name_required))
                        } else if (ToolGroupStore.saveGroup(target, policy.allowedTools.filter { it in ALL_TOOL_NAMES }.toSet())) {
                            groups = ToolGroupStore.load()
                            newName = ""
                            toast(context, context.getString(R.string.prompt_groups_saved, target))
                        }
                    },
                )
            }
        }
    }
}
