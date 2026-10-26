package io.github.mangi.eta.ui.screens.prompts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.prompt.AgentToolPolicy
import io.github.mangi.eta.agent.prompt.AgentToolPolicyStore
import io.github.mangi.eta.agent.prompt.ToolGroupStore
import io.github.mangi.eta.agent.prompt.ToolPolicyMode
import io.github.mangi.eta.agent.tool.AgentToolNaming
import io.github.mangi.eta.ui.components.EtaArrowPreference
import io.github.mangi.eta.ui.components.EtaActionPreference
import io.github.mangi.eta.ui.components.EtaPreferenceColors
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaPreferenceIcon
import io.github.mangi.eta.ui.components.MiuixScaffold
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 工具边界页：目录 → 分类（左右滑页）→ 单个工具。
 *
 * 目录页只放状态与分类入口，不再把 135 个工具和分类清单堆在同一屏。
 */
@Composable
internal fun PromptToolsScreen(
    onBack: () -> Unit,
    onManageGroups: () -> Unit = {},
) {
    val context = LocalContext.current
    var saved by remember { mutableStateOf(AgentToolPolicyStore.load()) }
    var selector by remember {
        mutableStateOf(
            ToolSelectorState(
                mode = saved.mode,
                allowed = saved.allowedTools,
                customGroups = ToolGroupStore.load(),
            ),
        )
    }
    var searchMode by remember { mutableStateOf(false) }
    var mcpNames by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(Unit) { mcpNames = loadMcpToolNames() }
    val allTools = allToolNames(mcpNames)
    val dirty = selector.mode != saved.mode || selector.allowed != saved.allowedTools

    val save: () -> Unit = {
        val next = AgentToolPolicy(
            mode = selector.mode,
            allowedTools = selector.allowed,
            version = saved.version + 1,
        )
        if (AgentToolPolicyStore.save(next)) {
            saved = next
            toast(context, context.getString(R.string.prompt_studio_tool_saved))
        } else {
            toast(context, context.getString(R.string.prompt_studio_save_failed))
        }
    }

    val grouping = groupingOf(selector, allTools)
    val category = selector.pickerCategory?.takeIf { it in grouping.categories }

    when {
        searchMode -> ToolSearchPage(
            state = selector,
            allTools = allTools,
            onChange = { selector = it },
            onExit = { searchMode = false },
        )

        category != null -> ToolCategoryPager(
            categories = grouping.categories,
            grouped = grouping.grouped,
            state = selector,
            initialCategory = category,
            dirty = dirty,
            onChange = { selector = it },
            onSave = save,
            onExit = { selector = selector.copy(pickerCategory = null) },
        )

        else -> ToolDirectoryPage(
            state = selector,
            allTools = allTools,
            grouping = grouping,
            dirty = dirty,
            onChange = { selector = it },
            onSave = save,
            onBack = onBack,
            onSearch = { searchMode = true },
            onManageGroups = onManageGroups,
        )
    }
}

@Composable
private fun ToolDirectoryPage(
    state: ToolSelectorState,
    allTools: List<String>,
    grouping: ToolGrouping,
    dirty: Boolean,
    onChange: (ToolSelectorState) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onManageGroups: () -> Unit,
) {
    MiuixScaffoldPage(
        title = stringResource(R.string.prompt_studio_tools_title),
        onBack = onBack,
        modifier = Modifier.imePadding(),
    ) {
        item(key = "search-entry") {
            EtaPreferenceGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                EtaArrowPreference(
                    title = stringResource(R.string.prompt_tools_search_entry),
                    summary = stringResource(R.string.prompt_tools_search_entry_summary),
                    startAction = { EtaPreferenceIcon(icon = Icons.Rounded.Search, tint = EtaPreferenceColors.Blue) },
                    onClick = onSearch,
                )
            }
        }

        item(key = "mode") { ToolModeGroup(state = state, onChange = onChange) }

        item(key = "progress") { ToolProgressCard(state = state, total = allTools.size) }

        if (dirty) {
            item(key = "save") {
                EtaPreferenceGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    EtaActionPreference(
                        title = stringResource(R.string.prompt_studio_tool_save),
                        summary = if (state.mode == ToolPolicyMode.ALLOW_ALL) {
                            stringResource(R.string.prompt_studio_tool_all_hint)
                        } else {
                            stringResource(R.string.prompt_studio_tool_whitelist_hint, state.allowed.size)
                        },
                        startAction = { EtaPreferenceIcon(icon = Icons.Rounded.Save, tint = EtaPreferenceColors.Blue) },
                        onClick = onSave,
                    )
                }
            }
        }

        if (state.mode == ToolPolicyMode.ALLOW_LIST) {
            item(key = "batch") {
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
        }

        item(key = "groups-title") { EtaPreferenceGroupTitle(stringResource(R.string.prompt_tool_groups_title)) }
        item(key = "groups-entry") {
            EtaPreferenceGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                EtaArrowPreference(
                    title = stringResource(R.string.prompt_groups_entry_title),
                    summary = stringResource(R.string.prompt_groups_entry_summary, state.customGroups.size),
                    startAction = { EtaPreferenceIcon(icon = Icons.Rounded.Checklist, tint = EtaPreferenceColors.Green) },
                    onClick = onManageGroups,
                )
            }
        }

        item(key = "grouping-title") { EtaPreferenceGroupTitle(stringResource(R.string.prompt_tools_sidebar_group)) }
        item(key = "grouping") { ToolGroupingGroup(state = state, onChange = onChange) }

        if (state.mode == ToolPolicyMode.ALLOW_LIST) {
            item(key = "cat-title") {
                EtaPreferenceGroupTitle(stringResource(R.string.prompt_tool_categories_group, grouping.categories.size))
            }
            grouping.categories.forEach { category ->
                val tools = grouping.grouped[category].orEmpty()
                val selected = tools.count { it in state.allowed }
                item(key = "cat-" + category) {
                    EtaPreferenceGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)) {
                        EtaArrowPreference(
                            title = categoryLabelOf(category),
                            summary = stringResource(R.string.prompt_tool_category_progress, selected, tools.size),
                            onClick = { onChange(state.copy(pickerCategory = category)) },
                        )
                        Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                            ToolProgressBar(selected = selected, total = tools.size)
                        }
                    }
                }
            }
        }
    }
}

/** 分类页：一页一个分类，左右滑动切换；顶部标题跟着当前页走。 */
@Composable
private fun ToolCategoryPager(
    categories: List<String>,
    grouped: Map<String, List<String>>,
    state: ToolSelectorState,
    initialCategory: String,
    dirty: Boolean,
    onChange: (ToolSelectorState) -> Unit,
    onSave: () -> Unit,
    onExit: () -> Unit,
) {
    val initialIndex = categories.indexOf(initialCategory).coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = initialIndex) { categories.size }
    val currentLabel = categories.getOrNull(pagerState.currentPage)?.let(::categoryLabelOf).orEmpty()

    LaunchedEffect(pagerState.currentPage, categories) {
        val page = categories.getOrNull(pagerState.currentPage) ?: return@LaunchedEffect
        if (state.pickerCategory != page) onChange(state.copy(pickerCategory = page))
    }

    val scope = rememberCoroutineScope()
    val page = pagerState.currentPage
    MiuixScaffold(title = currentLabel, onBack = onExit) { paddingValues, _, sidePadding ->
        Column(modifier = Modifier.fillMaxSize().padding(top = paddingValues.calculateTopPadding())) {
            // 左右滑动的可发现性：两侧箭头 + 页码 + 一行文字提示，避免"不知道能滑"。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                SwipeArrow(
                    icon = Icons.Rounded.ChevronLeft,
                    description = stringResource(R.string.prompt_tool_swipe_prev),
                    enabled = page > 0,
                    onClick = { scope.launch { pagerState.animateScrollToPage(page - 1) } },
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(
                            if (categories.size <= 1) R.string.prompt_tool_swipe_first
                            else R.string.prompt_tool_swipe_hint,
                            page + 1,
                            categories.size,
                        ),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                SwipeArrow(
                    icon = Icons.Rounded.ChevronRight,
                    description = stringResource(R.string.prompt_tool_swipe_next),
                    enabled = page < categories.lastIndex,
                    onClick = { scope.launch { pagerState.animateScrollToPage(page + 1) } },
                )
            }
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                val category = categories.getOrNull(page) ?: return@HorizontalPager
                val tools = grouped[category].orEmpty()
                val selected = tools.count { it in state.allowed }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = sidePadding,
                        end = sidePadding,
                        top = 8.dp,
                        bottom = 32.dp,
                    ),
                ) {
                    item(key = "pager-progress") {
                        EtaPreferenceGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                                Text(
                                    text = stringResource(R.string.prompt_tool_category_progress, selected, tools.size),
                                    style = MiuixTheme.textStyles.body2,
                                )
                                ToolProgressBar(
                                    selected = selected,
                                    total = tools.size,
                                    modifier = Modifier.padding(top = 10.dp),
                                )
                            }
                        }
                    }
                    item(key = "pager-batch") {
                        EtaPreferenceGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                            EtaActionPreference(
                                title = stringResource(
                                    if (selected == tools.size && tools.isNotEmpty()) R.string.prompt_tool_category_clear
                                    else R.string.prompt_tool_category_select_all,
                                ),
                                startAction = { EtaPreferenceIcon(icon = Icons.Rounded.Checklist, tint = EtaPreferenceColors.Green) },
                                onClick = {
                                    onChange(
                                        state.copy(
                                            allowed = if (selected == tools.size) state.allowed - tools.toSet()
                                            else state.allowed + tools,
                                        ),
                                    )
                                },
                            )
                        }
                    }
                    if (dirty) {
                        item(key = "pager-save") {
                            EtaPreferenceGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                                EtaActionPreference(
                                    title = stringResource(R.string.prompt_studio_tool_save),
                                    summary = stringResource(R.string.prompt_studio_tool_whitelist_hint, state.allowed.size),
                                    startAction = { EtaPreferenceIcon(icon = Icons.Rounded.Save, tint = EtaPreferenceColors.Blue) },
                                    onClick = onSave,
                                )
                            }
                        }
                    }
                    tools.forEach { name ->
                        item(key = "pager-tool-" + name) {
                            ToolSwitchRow(name = name, state = state, onChange = onChange)
                        }
                    }
                }
            }
        }
    }
}

/** 全屏搜索：搜索框固定在顶部，键盘弹出只压缩结果区，不会遮住结果。 */
@Composable
private fun ToolSearchPage(
    state: ToolSelectorState,
    allTools: List<String>,
    onChange: (ToolSelectorState) -> Unit,
    onExit: () -> Unit,
) {
    val keyword = state.search.trim().lowercase()
    val results = remember(keyword, allTools) {
        if (keyword.isEmpty()) emptyList()
        else allTools.filter { name ->
            name.lowercase().contains(keyword) || AgentToolNaming.face(name).label.lowercase().contains(keyword)
        }
    }

    MiuixScaffoldPage(
        title = stringResource(R.string.prompt_tools_search_title),
        onBack = onExit,
        modifier = Modifier.imePadding(),
    ) {
        stickyHeader(key = "search-field") {
            EtaPreferenceGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                TextField(
                    value = state.search,
                    onValueChange = { onChange(state.copy(search = it)) },
                    label = stringResource(R.string.prompt_studio_tool_search),
                    modifier = Modifier.padding(16.dp),
                    singleLine = true,
                )
            }
        }
        if (keyword.isEmpty()) {
            item(key = "hint") {
                EtaPreferenceGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Text(
                        text = stringResource(R.string.prompt_tools_search_hint),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            return@MiuixScaffoldPage
        }
        item(key = "meta") {
            EtaPreferenceGroupTitle(
                stringResource(R.string.prompt_tool_search_results, results.size, state.allowed.count { it in results }),
            )
        }
        if (results.isEmpty()) {
            item(key = "empty") {
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
        results.forEach { name -> toolSwitchRow(name, state, onChange, "s-") }
    }
}

/** 滑动切换的左右箭头：可点、禁用态变淡，兼顾可发现性与可达性。 */
@Composable
private fun SwipeArrow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Icon(
        imageVector = icon,
        contentDescription = description,
        modifier = Modifier
            .size(40.dp)
            .clickable(enabled = enabled, onClick = onClick),
        tint = if (enabled) {
            MiuixTheme.colorScheme.onBackground.copy(alpha = 0.6f)
        } else {
            MiuixTheme.colorScheme.onBackground.copy(alpha = 0.15f)
        },
    )
}
