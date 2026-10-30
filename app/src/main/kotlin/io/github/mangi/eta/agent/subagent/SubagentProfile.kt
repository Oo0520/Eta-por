package io.github.mangi.eta.agent.subagent

import io.github.mangi.eta.agent.context.PersonalSearchTools
import io.github.mangi.eta.agent.model.AgentFileToolCatalog
import io.github.mangi.eta.agent.model.AgentPhoneToolCatalog
import io.github.mangi.eta.agent.model.AgentTerminalToolCatalog
import org.json.JSONArray
import org.json.JSONObject

/**
 * Subagent 角色定义与工具白名单（涵盖内置 Profile 与用户在 `agents/<name>.md` 定义的自定义 Profile）。
 *
 * 所有 Subagent 均剥夺 `subagent` / `subagent_profile` 自身（禁止递归派发与自修改）
 * 以及独占式物理屏幕 GUI 与交互式浏览器工具，确保多个 Subagent 并发运行时互不干扰。
 */
internal data class SubagentProfile(
    val name: String,
    val summary: String,
    val whenToUse: String,
    val toolHighlights: List<String>,
    val roleInstruction: String,
    private val allowWriteFilesAndTerminal: Boolean = false,
    /** 是否允许屏幕/浏览器/灵屏工具（目前只有 screen 这类专用 Subagent 打开）。 */
    val allowScreenTools: Boolean = false,
    val builtin: Boolean = true,
    val override: Boolean = false,
    val explicitTools: Set<String>? = null,
    val disallowedTools: Set<String> = emptySet(),
    val filePath: String? = null,
) {
    fun buildSystemPrompt(): String =
        "$SUBAGENT_ROLE_PREFIX\n\n$roleInstruction"

    fun filterTools(allTools: JSONArray): JSONArray = JSONArray().also { filtered ->
        for (index in 0 until allTools.length()) {
            val item = allTools.optJSONObject(index) ?: continue
            val toolName = item.optJSONObject("function")?.optString("name").orEmpty()
            if (isToolAllowed(toolName)) {
                filtered.put(JSONObject(item.toString()))
            }
        }
    }

    fun isToolAllowed(toolName: String): Boolean {
        if (toolName.isBlank() || toolName in RECURSION_TOOLS || toolName in ALWAYS_FORBIDDEN ||
            toolName in disallowedTools
        ) {
            return false
        }
        // screen 这类专用 Subagent 可以使用屏幕/灵屏工具，并可把成功流程沉淀成技能。
        if (allowScreenTools && (toolName in GUI_TOOLS || toolName == "skills_manage")) return true
        if (!allowScreenTools && toolName in GUI_TOOLS) return false
        val customAllowlist = explicitTools
        if (customAllowlist != null) {
            if ("*" in customAllowlist) return true
            return toolName in customAllowlist ||
                ("personal_data" in customAllowlist && (toolName in PersonalSearchTools.names || toolName in AgentPhoneToolCatalog.reads))
        }
        if (toolName in READ_ONLY_TOOLS || toolName in PersonalSearchTools.names || toolName in AgentPhoneToolCatalog.reads) {
            return true
        }
        if (!allowWriteFilesAndTerminal) return false
        return toolName in CODER_EXTRA_TOOLS || toolName.startsWith("mcp_")
    }

    companion object {
        const val TYPE_EXPLORE = "explore"
        const val TYPE_CODER = "coder"
        const val TYPE_PLAN = "plan"
        const val TYPE_SCREEN = "screen"
        const val DEFAULT_TYPE = TYPE_EXPLORE

        private const val SUBAGENT_ROLE_PREFIX =
            "你当前作为 Subagent（子智能体）运行。你收到的 user 消息均由主 Agent 派发，而非终端用户直接输入。" +
                "主 Agent 看不到你的中间思考与工具调用过程，只能看到你结束时的最后一条回复（final message）。" +
                "不要向终端用户直接提问；如果任务存在歧义或缺少前置条件，请在最终回复中向主 Agent 清晰说明。"

        /** 任何 Subagent 都不能用：递归派发与自修改。 */
        private val RECURSION_TOOLS = setOf(
            AgentSubagentToolCatalog.TOOL_NAME,
            AgentSubagentToolCatalog.PROFILE_TOOL_NAME,
        )

        /** 屏幕/浏览器/灵屏工具：默认禁，只有 screen 这类专用 Subagent 才放开。 */
        private val GUI_TOOLS = setOf(
            "virtual_screen",
            "observe_screen",
            "release_gui",
            "tap",
            "tap_area",
            "tap_element",
            "long_press",
            "long_press_element",
            "swipe",
            "scroll",
            "scroll_element",
            "type_text",
            "input_text",
            "replace_text",
            "clear_text",
            "paste_text",
            "set_clipboard",
            "press_key",
            "open_system_panel",
            "wait",
            "wait_for_text",
            "wait_for_package",
            "launch_app",
            "open_uri",
            "browser_use",
        )

        /** 任何 Subagent 都不能用：改记忆、装技能、常驻后台。 */
        private val ALWAYS_FORBIDDEN = setOf(
            "memory_write",
            "character_memory_write",
            "skills_install_from_github",
            "terminal_daemon",
        )

        /** 默认禁用集合（递归 + GUI + 恒定禁用），供 coder 这类 profile 做差集。 */
        val FORBIDDEN_TOOLS = RECURSION_TOOLS + GUI_TOOLS + ALWAYS_FORBIDDEN

        val READ_ONLY_TOOLS = setOf(
            "read_file",
            "stat_file",
            "list_directory",
            "glob_files",
            "grep_files",
            "read_image",
            "web_search",
            "fetch_url",
            "get_current_context",
            "search_apps",
            "inspect_app",
            "device_status",
            "network_info",
            "top_memory_apps",
            "top_storage_apps",
            "get_setting",
            "get_logcat",
            "get_current_location",
            "get_device_environment",
            "list_alarms",
            "list_active_timers",
            "get_health_summary",
            "recent_notifications",
            "search_notification_history",
            "recent_app_activity",
            "app_usage_summary",
            "search_clipboard_history",
            "get_clipboard",
            "get_flashlight",
            "get_device_state",
            "get_display_state",
            "get_sound_state",
            "get_hotspot",
            "read_personal_item",
            "summarize_bills",
            "search_media",
            "search_audio",
            "search_recordings",
            "search_files",
            "search_calendar_events",
            "search_contacts",
            "search_call_history",
            "search_messages",
            "search_downloads",
            "search_notes",
            "search_coloros_recordings",
            "search_recording_summaries",
            "search_system_memories",
            "search_saved_places",
            "search_personal_orders",
            "search_qq_chat_images",
            "search_wechat_chat_images",
            "memory_get",
            "character_memory_get",
            "skills_list",
            "skills_read",
            "skills_read_resource",
        )

        private val CODER_EXTRA_TOOLS =
            (AgentFileToolCatalog.names + AgentTerminalToolCatalog.names) - FORBIDDEN_TOOLS

        val EXPLORE = SubagentProfile(
            name = TYPE_EXPLORE,
            summary = "只读探索与多源信息检索",
            whenToUse = "Fast read-only agent specialized for exploring files, personal device data (calendar, notes, messages, notifications, bills, media, system memories), logs, and web pages. Specify desired thoroughness in the prompt: \"quick\", \"medium\", or \"thorough\".",
            toolHighlights = listOf("read_file", "glob_files", "grep_files", "web_search", "fetch_url", "personal_data", "get_logcat"),
            roleInstruction = "你是只读探索 Subagent（explore）。你的职责是快速、准确地检索文件、设备个人数据、系统状态或网页资料，" +
                "严禁尝试修改任何文件或设备状态。在最终回复中直接给出结构化证据、关键路径/时间点与结论摘要。",
            allowWriteFilesAndTerminal = false,
            builtin = true,
        )

        val CODER = SubagentProfile(
            name = TYPE_CODER,
            summary = "工程执行、文件编辑与终端任务",
            whenToUse = "General engineering and terminal agent with file-editing and shell execution tools; use it for delegated tasks that run commands, analyze APKs, edit files, or execute scripts.",
            toolHighlights = listOf("terminal", "read_file", "write_file", "edit_file", "glob_files", "grep_files", "web_search"),
            roleInstruction = "你是工程与终端执行 Subagent（coder）。你的最后一条消息就是交给主 Agent 的完整交接报告：" +
                "请清楚说明你执行了哪些命令或修改了哪些文件、关键输出与验证结果，以及任何未完成或需后续处理的事项。",
            allowWriteFilesAndTerminal = true,
            builtin = true,
        )

        val PLAN = SubagentProfile(
            name = TYPE_PLAN,
            summary = "只读架构分析与分步方案设计",
            whenToUse = "Read-only planning agent. Use when you need a step-by-step implementation or execution plan, key file/data identification, and trade-off analysis before making changes.",
            toolHighlights = listOf("read_file", "glob_files", "grep_files", "web_search", "fetch_url", "skills_read"),
            roleInstruction = "你是只读规划 Subagent（plan）。你拥有只读检索能力，不得修改文件或执行破坏性操作。" +
                "请在最终回复中清晰列出：1. 已确认的上下文事实；2. 仍待核实的关键问题；3. 分步执行方案与风险权衡。",
            allowWriteFilesAndTerminal = false,
            builtin = true,
        )

        /**
         * 灵屏操作专用 Subagent。
         *
         * 与 explore/coder/plan 不同，它允许屏幕与灵屏工具，因为职责就是在灵屏里完成多步界面操作；
         * 但仍禁用递归派发、改记忆、装技能与常驻后台。操作纪律写在 roleInstruction：
         * 先观察、优先无障碍节点操作、每步验证、失败再退坐标，并把跑通的流程沉淀成技能。
         */
        val SCREEN = SubagentProfile(
            name = TYPE_SCREEN,
            summary = "灵屏界面操作：无障碍优先、逐动验证、成功流程沉淀技能",
            whenToUse = "GUI operator running inside the virtual screen. Use it for multi-step UI tasks in third-party apps: " +
                "open app, navigate pages, fill forms, toggle switches, verify results. It prefers accessibility node actions " +
                "and falls back to coordinate taps only when node actions fail, then records proven flows as skills.",
            toolHighlights = listOf(
                "virtual_screen", "observe_screen", "tap_element", "scroll_element",
                "tap_area", "scroll", "type_text", "press_key", "wait_for_text", "skills_manage",
            ),
            roleInstruction = "你是灵屏操作 Subagent（screen）。你负责在灵屏（独立 display）里完成多步界面任务，" +
                "并把真正跑通的流程沉淀成技能。\n\n" +
                "操作纪律（必须按顺序）：\n" +
                "1. 动手前先 observe_screen 读取当前界面节点，确认目标在哪里；不清楚就多观察一次，不要盲点。\n" +
                "2. 优先无障碍：能用 tap_element / long_press_element / scroll_element / wait_for_text 就用它们，" +
                "这类操作按节点执行，不依赖坐标、失败率低。\n" +
                "3. 每一步动作后必须 observe_screen 验证结果（页面变化、文本出现、开关状态），不要连续盲点。\n" +
                "4. 无障碍路径不可用（节点缺失、点击后界面无变化、控件不可点击）时，才退回坐标操作：" +
                "tap_area / tap / swipe；坐标以同一次观察的截图与节点 bounds 为准。\n" +
                "5. 输入文本优先 type_text 并指定 index；输入前先确认输入框已聚焦。\n" +
                "6. 遇到弹窗、权限页、广告先按正常路径关闭或授权，不要绕过系统权限界面。\n" +
                "7. 同一处连续三次尝试仍无进展就停止，在最终回复里说明卡在哪一步、当前界面状态、建议的下一步。\n\n" +
                "技能沉淀（越用越快）：\n" +
                "- 只在任务确实完成并验证后，才用 skills_manage(action=create) 写技能；\n" +
                "- 技能内容写清：适用 App、前置条件、关键节点的定位方式（文本/描述/层级）、步骤序列、" +
                "每步验证点、常见失败与回退方式；\n" +
                "- 已有同名技能时用 update 补充差异，不要重复创建；\n" +
                "- 不要在失败任务上写技能，也不要把临时猜测写成步骤。\n\n" +
                "最终回复：成功时给出操作序列摘要、最终界面状态、是否已沉淀技能（技能名）；" +
                "失败时给出卡住的位置、已尝试的路径与当前界面证据。",
            allowWriteFilesAndTerminal = false,
            allowScreenTools = true,
            // 只用灵屏：不放开内置浏览器，避免子代理绕开灵屏直接用离屏 WebView。
            disallowedTools = setOf("browser_use"),
            builtin = true,
        )

        val BUILTIN_PROFILES: List<SubagentProfile> = listOf(EXPLORE, CODER, PLAN, SCREEN)
        val BUILTIN_NAMES: Set<String> = BUILTIN_PROFILES.mapTo(linkedSetOf()) { it.name }

        fun resolve(
            name: String?,
            catalog: List<SubagentProfile> = SubagentProfileCatalog.listAll(),
            customRole: String? = null,
            customTools: List<String>? = null,
        ): SubagentProfile {
            val normalized = name?.trim()?.lowercase()
                ?.replace(Regex("[^a-z0-9_-]+"), "-")
                ?.trim('-')
                .orEmpty()
            val matched = if (normalized.isEmpty()) {
                catalog.firstOrNull { it.name == DEFAULT_TYPE } ?: EXPLORE
            } else {
                catalog.firstOrNull { it.name == normalized }
            }
            val cleanRole = customRole?.trim().orEmpty()
            val cleanTools = customTools
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() && it !in FORBIDDEN_TOOLS }
                ?.distinct()
                .orEmpty()

            if (matched != null) {
                if (cleanRole.isEmpty() && cleanTools.isEmpty()) return matched
                return matched.copy(
                    roleInstruction = if (cleanRole.isNotEmpty()) {
                        "${matched.roleInstruction}\n\n补充角色要求：$cleanRole"
                    } else {
                        matched.roleInstruction
                    },
                    explicitTools = if (cleanTools.isNotEmpty()) cleanTools.toSet() else matched.explicitTools,
                    toolHighlights = if (cleanTools.isNotEmpty()) cleanTools.take(8) else matched.toolHighlights,
                )
            }

            // 未命中预设时，当场创建仅用于本次会话的临时专职 Subagent，不落盘保存。
            val ephemeralName = normalized.ifEmpty { "general" }
            val instruction = cleanRole.ifEmpty {
                "你是专职处理当前子任务的 Subagent（$ephemeralName）。请高效利用可用工具收集证据或完成操作，" +
                    "并在最后一条消息中向主 Agent 返回结构化、完整的结论摘要。"
            }
            return SubagentProfile(
                name = ephemeralName,
                summary = cleanRole.ifEmpty { "临时专职 Subagent" },
                whenToUse = cleanRole,
                toolHighlights = if (cleanTools.isNotEmpty()) {
                    cleanTools.take(8)
                } else {
                    listOf("read_file", "glob_files", "grep_files", "web_search", "personal_data", "terminal")
                },
                roleInstruction = instruction,
                allowWriteFilesAndTerminal = true,
                builtin = false,
                explicitTools = if (cleanTools.isNotEmpty()) cleanTools.toSet() else setOf("*"),
            )
        }
    }
}
