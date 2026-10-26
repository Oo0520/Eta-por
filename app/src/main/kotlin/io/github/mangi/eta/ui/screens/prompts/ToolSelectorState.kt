package io.github.mangi.eta.ui.screens.prompts

import io.github.mangi.eta.agent.prompt.NamedToolGroup
import io.github.mangi.eta.agent.prompt.ToolPolicyMode

/** 工具选择器的可变状态，调用方以 `var selector by remember { ... }` 持有。 */
internal data class ToolSelectorState(
    val mode: ToolPolicyMode,
    val allowed: Set<String>,
    val search: String = "",
    val expanded: Set<String> = emptySet(),
    val grouping: String = "功能",
    val selectedCategory: String = "",
    val customGroups: List<NamedToolGroup> = emptyList(),
    val newGroupName: String = "",
    /** null = 显示分类列表；非空 = 已进入某个分类的工具页。 */
    val pickerCategory: String? = null,
    val renamingGroup: String? = null,
    val renameInput: String = "",
)
