package io.github.mangi.eta.ui.screens.prompts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.prompt.ToolPolicyMode
import io.github.mangi.eta.agent.tool.AgentToolNaming
import io.github.mangi.eta.agent.tool.AgentToolRequirements
import io.github.mangi.eta.ui.components.EtaArrowPreference
import io.github.mangi.eta.ui.components.EtaActionPreference
import io.github.mangi.eta.ui.components.EtaPreferenceColors
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaPreferenceIcon
import io.github.mangi.eta.ui.components.EtaSwitchPreference
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 一种分组方式的结果：分类顺序 + 每类包含的工具。 */
internal data class ToolGrouping(
    val categories: List<String>,
    val grouped: Map<String, List<String>>,
)

internal val ALL_TOOL_NAMES: List<String> by lazy { AgentToolRequirements.toolNames.sorted() }

/** 当前 MCP 服务器带来的工具名（mt_*），用于工具边界与预览同口径统计。 */
internal suspend fun loadMcpToolNames(): List<String> = runCatching {
    io.github.mangi.eta.agent.mcp.McpRunSnapshot.load().tools.map { it.modelName }
}.getOrElse { emptyList() }

/** 本地工具 + MCP 工具的完整可选集合。 */
internal fun allToolNames(extra: List<String>): List<String> =
    (ALL_TOOL_NAMES + extra).distinct().sorted()

internal fun groupingOf(state: ToolSelectorState, allTools: List<String> = ALL_TOOL_NAMES): ToolGrouping {
    val grouped: Map<String, List<String>> = when (state.grouping) {
        "权限" -> allTools.groupBy { name -> AgentToolRequirements.rootRequirement(name).name }
        "字母" -> allTools.groupBy { name -> name.first().uppercaseChar().toString() }
        else -> allTools.groupBy { name -> AgentToolNaming.face(name).category }
    }
    val categories = when (state.grouping) {
        "权限" -> listOf("NONE", "PARTIAL", "REQUIRED").filter { grouped[it].orEmpty().isNotEmpty() }
        "字母" -> grouped.keys.sorted()
        else -> (AgentToolNaming.categoryOrder + grouped.keys.filterNot { it in AgentToolNaming.categoryOrder })
            .filter { grouped[it].orEmpty().isNotEmpty() }
    }
    return ToolGrouping(categories = categories, grouped = grouped)
}

internal fun categoryLabelOf(category: String): String = when (category) {
    "NONE" -> "无需 Root"
    "PARTIAL" -> "部分 Root"
    "REQUIRED" -> "需要 Root"
    else -> category
}

/** 细进度条：分类行与状态卡共用，比纯文字更容易扫。 */
@Composable
internal fun ToolProgressBar(selected: Int, total: Int, modifier: Modifier = Modifier) {
    val fraction = if (total <= 0) 0f else selected.toFloat() / total.toFloat()
    val track = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.16f)
    val fill = if (fraction >= 1f) EtaPreferenceColors.Green else EtaPreferenceColors.Blue
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(4.dp)
            .background(color = track, shape = RoundedCornerShape(50)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(4.dp)
                .background(color = fill, shape = RoundedCornerShape(50)),
        )
    }
}

@Composable
internal fun SelectedMark() {
    Text(
        text = stringResource(R.string.prompt_studio_mode_selected),
        style = MiuixTheme.textStyles.footnote2,
        color = EtaPreferenceColors.Blue,
    )
}

/** 模式二选一 + 已选进度，两个页面共用同一份呈现。 */
@Composable
internal fun ToolModeGroup(state: ToolSelectorState, onChange: (ToolSelectorState) -> Unit) {
    EtaPreferenceGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        EtaActionPreference(
            title = stringResource(R.string.prompt_studio_tool_all),
            summary = stringResource(R.string.prompt_studio_tool_all_hint),
            startAction = { EtaPreferenceIcon(icon = Icons.Rounded.LockOpen, tint = EtaPreferenceColors.Blue) },
            endActions = { if (state.mode == ToolPolicyMode.ALLOW_ALL) SelectedMark() },
            onClick = { onChange(state.copy(mode = ToolPolicyMode.ALLOW_ALL, pickerCategory = null)) },
        )
        EtaPreferenceDivider(hasLeading = true)
        EtaActionPreference(
            title = stringResource(R.string.prompt_studio_tool_whitelist),
            summary = stringResource(R.string.prompt_tool_whitelist_summary, state.allowed.size),
            startAction = { EtaPreferenceIcon(icon = Icons.Rounded.Checklist, tint = EtaPreferenceColors.Orange) },
            endActions = { if (state.mode == ToolPolicyMode.ALLOW_LIST) SelectedMark() },
            onClick = { onChange(state.copy(mode = ToolPolicyMode.ALLOW_LIST)) },
        )
    }
}

/** 已选进度卡：一眼看出选了多少。 */
@Composable
internal fun ToolProgressCard(state: ToolSelectorState, total: Int) {
    EtaPreferenceGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                text = if (state.mode == ToolPolicyMode.ALLOW_ALL) {
                    stringResource(R.string.prompt_tools_status_all, total)
                } else {
                    stringResource(R.string.prompt_tools_status, state.allowed.size, total)
                },
                style = MiuixTheme.textStyles.body2,
            )
            ToolProgressBar(
                selected = if (state.mode == ToolPolicyMode.ALLOW_ALL) total else state.allowed.size,
                total = total,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

/** 分组方式三选一；不做 TabRow，避免露出直角。 */
@Composable
internal fun ToolGroupingGroup(state: ToolSelectorState, onChange: (ToolSelectorState) -> Unit) {
    val options = listOf(
        "功能" to R.string.prompt_tool_group_by_feature,
        "权限" to R.string.prompt_tool_group_by_root,
        "字母" to R.string.prompt_tool_group_by_letter,
    )
    EtaPreferenceGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        options.forEachIndexed { index, (value, labelRes) ->
            if (index > 0) EtaPreferenceDivider(hasLeading = true)
            EtaActionPreference(
                title = stringResource(labelRes),
                endActions = { if (state.grouping == value) SelectedMark() },
                onClick = { onChange(state.copy(grouping = value, pickerCategory = null)) },
            )
        }
    }
}

@Composable
internal fun ToolSwitchRow(
    name: String,
    state: ToolSelectorState,
    onChange: (ToolSelectorState) -> Unit,
) {
    EtaPreferenceGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 1.dp)) {
        EtaSwitchPreference(
            title = AgentToolNaming.face(name).label,
            summary = name,
            checked = name in state.allowed,
            onCheckedChange = { checked ->
                onChange(state.copy(allowed = if (checked) state.allowed + name else state.allowed - name))
            },
        )
    }
}

internal fun LazyListScope.toolSwitchRow(
    name: String,
    state: ToolSelectorState,
    onChange: (ToolSelectorState) -> Unit,
    keyPrefix: String,
) {
    item(key = keyPrefix + name) { ToolSwitchRow(name = name, state = state, onChange = onChange) }
}

/**
 * 编辑器内联使用的工具选择器。
 *
 * 与独立的「工具边界」页共用同一套小组件（模式、进度、分组、开关行），
 * 但这里不分页，直接在同一个列表里下钻分类，方便与提示词一起改。
 */
internal fun LazyListScope.toolSelectionItems(
    state: ToolSelectorState,
    onChange: (ToolSelectorState) -> Unit,
    onManageGroups: () -> Unit = {},
    extraTools: List<String> = emptyList(),
) {
    val allTools = allToolNames(extraTools)
    val keyword = state.search.trim().lowercase()
    val matches = { name: String ->
        keyword.isEmpty() ||
            name.lowercase().contains(keyword) ||
            AgentToolNaming.face(name).label.lowercase().contains(keyword)
    }

    item(key = "tools-title") { EtaPreferenceGroupTitle(stringResource(R.string.prompt_studio_tool_group)) }

    item(key = "tools-mode") { ToolModeGroup(state = state, onChange = onChange) }

    if (state.mode == ToolPolicyMode.ALLOW_LIST) {
        item(key = "tools-progress") { ToolProgressCard(state = state, total = allTools.size) }
    }

    item(key = "tools-search") {
        EtaPreferenceGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            top.yukonga.miuix.kmp.basic.TextField(
                value = state.search,
                onValueChange = { onChange(state.copy(search = it, pickerCategory = null)) },
                label = stringResource(R.string.prompt_studio_tool_search),
                modifier = Modifier.padding(16.dp),
                singleLine = true,
            )
        }
    }

    if (state.mode != ToolPolicyMode.ALLOW_LIST) return

    if (keyword.isNotEmpty()) {
        val results = allTools.filter(matches)
        item(key = "tools-search-meta") {
            EtaPreferenceGroupTitle(
                stringResource(R.string.prompt_tool_search_results, results.size, state.allowed.count { it in results }),
            )
        }
        if (results.isEmpty()) {
            item(key = "tools-search-empty") {
                EtaPreferenceGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Text(
                        text = stringResource(R.string.prompt_tool_search_empty),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
        results.forEach { name -> toolSwitchRow(name, state, onChange, "search-") }
        return
    }

    item(key = "tools-batch") {
        EtaPreferenceGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            EtaActionPreference(
                title = stringResource(R.string.prompt_tool_selected_summary, state.allowed.size, allTools.size),
                startAction = { EtaPreferenceIcon(icon = Icons.Rounded.Tune, tint = EtaPreferenceColors.Blue) },
                onClick = { onChange(state.copy(allowed = allTools.toSet())) },
            )
            EtaPreferenceDivider(hasLeading = true)
            EtaActionPreference(
                title = stringResource(R.string.prompt_tool_clear_all_visible),
                startAction = { EtaPreferenceIcon(icon = Icons.Rounded.DeleteSweep, tint = EtaPreferenceColors.Orange) },
                onClick = { onChange(state.copy(allowed = emptySet())) },
            )
        }
    }

    item(key = "tools-groups-title") { EtaPreferenceGroupTitle(stringResource(R.string.prompt_tool_groups_title)) }
    item(key = "tools-groups-entry") {
        EtaPreferenceGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            EtaArrowPreference(
                title = stringResource(R.string.prompt_groups_entry_title),
                summary = stringResource(R.string.prompt_groups_entry_summary, state.customGroups.size),
                startAction = { EtaPreferenceIcon(icon = Icons.Rounded.Checklist, tint = EtaPreferenceColors.Green) },
                onClick = onManageGroups,
            )
        }
    }

    item(key = "tools-grouping-title") { EtaPreferenceGroupTitle(stringResource(R.string.prompt_tools_sidebar_group)) }
    item(key = "tools-grouping") { ToolGroupingGroup(state = state, onChange = onChange) }

    val grouping = groupingOf(state, allTools)
    val current = state.pickerCategory?.takeIf { it in grouping.categories }

    if (current != null) {
        val tools = grouping.grouped[current].orEmpty()
        val selectedCount = tools.count { it in state.allowed }
        item(key = "tools-picker-title") {
            EtaPreferenceGroupTitle(stringResource(R.string.prompt_tool_category_title2, categoryLabelOf(current), selectedCount, tools.size))
        }
        item(key = "tools-picker-head") {
            EtaPreferenceGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                EtaArrowPreference(
                    title = stringResource(R.string.prompt_tool_back_to_categories),
                    summary = stringResource(R.string.prompt_tool_category_progress, selectedCount, tools.size),
                    onClick = { onChange(state.copy(pickerCategory = null)) },
                )
                EtaPreferenceDivider(hasLeading = true)
                EtaActionPreference(
                    title = stringResource(
                        if (selectedCount == tools.size && tools.isNotEmpty()) R.string.prompt_tool_category_clear
                        else R.string.prompt_tool_category_select_all,
                    ),
                    onClick = {
                        onChange(
                            state.copy(
                                allowed = if (selectedCount == tools.size) state.allowed - tools.toSet()
                                else state.allowed + tools,
                            ),
                        )
                    },
                )
            }
        }
        tools.forEach { name -> toolSwitchRow(name, state, onChange, "cat-") }
        return
    }

    item(key = "tools-categories-title") {
        EtaPreferenceGroupTitle(stringResource(R.string.prompt_tool_categories_group, grouping.categories.size))
    }
    grouping.categories.forEach { category ->
        val tools = grouping.grouped[category].orEmpty()
        val selectedCount = tools.count { it in state.allowed }
        item(key = "tools-cat-" + category) {
            EtaPreferenceGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)) {
                EtaArrowPreference(
                    title = categoryLabelOf(category),
                    summary = stringResource(R.string.prompt_tool_category_progress, selectedCount, tools.size),
                    onClick = { onChange(state.copy(pickerCategory = category)) },
                )
                Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                    ToolProgressBar(selected = selectedCount, total = tools.size)
                }
            }
        }
    }
}

/** 内置常用组合：只读，可在「工具组合」页一键套用。 */
internal val COMMON_TOOL_SETS: List<Pair<String, List<String>>> = listOf(
    "只读检索" to listOf(
        "web_search", "fetch_url", "search_files", "search_downloads", "read_file",
        "read_image", "stat_file", "list_directory", "glob_files", "grep_files",
        "get_current_context", "memory_get", "skills_list", "skills_read", "skills_read_resource",
    ),
    "文件编辑" to listOf(
        "read_file", "write_file", "edit_file", "stat_file", "list_directory", "glob_files",
        "grep_files", "terminal", "terminal_job", "read_image", "get_current_context",
        "web_search", "fetch_url",
    ),
    "设备控制" to listOf(
        "device_status", "get_device_state", "set_device_state", "get_display_state",
        "set_brightness", "set_screen_timeout", "get_sound_state", "set_sound_mode",
        "set_do_not_disturb", "get_flashlight", "set_flashlight", "get_hotspot", "set_hotspot",
        "media_control", "set_volume", "get_current_context",
    ),
)
