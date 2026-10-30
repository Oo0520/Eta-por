package io.github.mangi.eta.ui.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ShortText
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.rounded.AddComment
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.model.LocalToolProfileStore
import io.github.mangi.eta.ui.components.WorkspaceShiftHost
import io.github.mangi.eta.ui.components.AdaptiveTopAppBar
import io.github.mangi.eta.ui.components.CharacterAvatar
import io.github.mangi.eta.ui.components.ConversationSidePaneScaffold
import io.github.mangi.eta.ui.components.MiuixBackButton
import io.github.mangi.eta.ui.components.RoleplayChatUi
import io.github.mangi.eta.ui.components.ShortcutIcon
import io.github.mangi.eta.ui.components.openVirtualScreen
import io.github.mangi.eta.ui.components.rememberVirtualScreenMenuEntries
import io.github.mangi.eta.ui.components.TopBarBackdrop
import io.github.mangi.eta.ui.components.captureForTopBar
import io.github.mangi.eta.ui.components.rememberTopBarBackdrop
import io.github.mangi.eta.ui.components.topBarContainerColor
import io.github.mangi.eta.ui.model.ConversationPaneUiState
import io.github.mangi.eta.ui.model.ConversationSummaryUi
import io.github.mangi.eta.ui.navigation.AppRoute
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowListPopup
import io.github.mangi.eta.ui.voice.SpeechAutoReadToggle

/**
 * Agent App 统一壳层。
 *
 * - 负责全局 Scaffold、状态栏/横向安全边距、顶层工具栏。
 * - 首页工具栏只保留历史入口与溢出菜单（新建对话、终端、浏览器），保持聊天舞台干净。
 * - 非首页子路由统一提供返回按钮与标题，避免每个页面各自像独立设置页。
 * - Settings 由标准二级页骨架自己提供 TopAppBar，壳层在此路由不重复绘制。
 */
@Composable
internal fun AgentAppShell(
    currentRoute: AppRoute?,
    isCurrentRoute: Boolean,
    conversationPaneState: ConversationPaneUiState?,
    isConversationPaneOpen: Boolean,
    onBack: () -> Unit,
    onOpenConversationPane: () -> Unit,
    onDismissConversationPane: () -> Unit,
    onSearchConversations: (String) -> Unit,
    onNewConversation: () -> Unit,
    onNewConversationInCategory: (String?) -> Unit = { _ -> onNewConversation() },
    onOpenTerminal: () -> Unit,
    onLaunchKimiWeb: () -> Unit,
    kimiWebLabel: String,
    canStopKimiWeb: Boolean,
    onStopKimiWeb: () -> Unit,
    onRefreshKimiWeb: () -> Unit,
    onOpenBrowser: () -> Unit,
    onSelectConversation: (String) -> Unit,
    onConversationRename: (ConversationSummaryUi) -> Unit,
    onMoveToCategory: (ConversationSummaryUi) -> Unit,
    onConversationExport: (ConversationSummaryUi) -> Unit,
    onConversationDelete: (ConversationSummaryUi) -> Unit,
    onConversationPin: (ConversationSummaryUi) -> Unit,
    onBatchDelete: (List<ConversationSummaryUi>) -> Unit,
    onOpenTools: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenCharacters: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenRootSettings: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenModelProviders: () -> Unit,
    onOpenTasks: () -> Unit,
    onCreateCategory: (String) -> Unit,
    onRenameCategory: (String, String) -> Unit,
    onMoveCategory: (String, Int) -> Unit,
    onDeleteCategoryConfirmed: (String) -> Unit,
    conversationStatus: (@Composable () -> Unit)? = null,
    /** 工作区面板打开时，只把顶栏以下的内容左移，顶栏那一行保持原样。 */
    workspaceShifted: Boolean = false,
    onDropConversationToCategory: ((io.github.mangi.eta.ui.model.ConversationSummaryUi, String?) -> Unit)? = null,
    workspaceVisible: Boolean = false,
    onWorkspaceOpen: () -> Unit = {},
    onWorkspaceDismiss: () -> Unit = {},
    workspaceContent: (@Composable () -> Unit)? = null,
    /** 顶栏放不下的低频状态提示，改在 ⋯ 菜单里以文字列出。 */
    conversationTrimmed: Pair<Int, Int>? = null,
    modifier: Modifier = Modifier,
    homeCharacter: RoleplayChatUi? = null,
    onOpenCharacter: () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberTopBarBackdrop()
    val topBarColor = topBarContainerColor(backdrop)
    val pageContent: @Composable () -> Unit = {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets.safeDrawing.only(
                WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
            ),
            topBar = {
                if (currentRoute !is AppRoute.Settings) {
                    TopBarBackdrop(backdrop) {
                        AgentTopBar(
                            route = currentRoute,
                            scrollBehavior = scrollBehavior,
                            color = topBarColor,
                            onBack = onBack,
                            onOpenConversationPane = onOpenConversationPane,
                            onNewConversation = onNewConversation,
                            onOpenTerminal = onOpenTerminal,
                            onLaunchKimiWeb = onLaunchKimiWeb,
                            kimiWebLabel = kimiWebLabel,
                            canStopKimiWeb = canStopKimiWeb,
                            onStopKimiWeb = onStopKimiWeb,
                            onRefreshKimiWeb = onRefreshKimiWeb,
                            onOpenBrowser = onOpenBrowser,
                            homeCharacter = homeCharacter,
                            onOpenCharacter = onOpenCharacter,
                            conversationStatus = conversationStatus,
                            conversationTrimmed = conversationTrimmed,
                        )
                    }
                }
            },
        ) { padding ->
            // 首页顶栏是自绘的，不参与折叠；此时再把内容接到 MiuixScrollBehavior 上，
            // 它的 onPreScroll 会持续吞掉上滑（想把工具栏往上收却没人收），表现就是只能下滑不能上滑。
            val contentScrollConnection = remember(currentRoute) {
                if (currentRoute is AppRoute.Home) null else scrollBehavior.nestedScrollConnection
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .captureForTopBar(backdrop)
                    .then(
                        if (contentScrollConnection != null) {
                            Modifier.nestedScroll(contentScrollConnection)
                        } else {
                            Modifier
                        },
                    ),
            ) {
                // 只移内容层：顶栏在 Scaffold 的 topBar 里，不在这棵子树，所以不会跟着走。
                // 这就是「顶栏不动、会话页左移」与「整页滑出」的区别。
                WorkspaceShiftHost(shifted = workspaceShifted) {
                    content(padding)
                }
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (conversationPaneState != null && currentRoute is AppRoute.Home) {
            ConversationSidePaneScaffold(
                state = conversationPaneState,
                visible = isConversationPaneOpen,
                backHandlerEnabled = isCurrentRoute,
                onOpen = onOpenConversationPane,
                onDismiss = onDismissConversationPane,
                onSearchChange = onSearchConversations,
                onNewConversation = onNewConversation,
                onConversationSelected = onSelectConversation,
                onConversationRename = onConversationRename,
                onMoveToCategory = onMoveToCategory,
                onConversationExport = onConversationExport,
                onConversationDelete = onConversationDelete,
                onConversationPin = onConversationPin,
                onBatchDelete = onBatchDelete,
                onOpenSettings = onOpenSettings,
                onOpenRootSettings = onOpenRootSettings,
                onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                onOpenModelProviders = onOpenModelProviders,
                onOpenTools = onOpenTools,
                onOpenSkills = onOpenSkills,
                onOpenCharacters = onOpenCharacters,
                onOpenPermissions = onOpenPermissions,
                onOpenTasks = onOpenTasks,
                onCreateCategory = onCreateCategory,
                onNewConversationInCategory = onNewConversationInCategory,
                onRenameCategory = onRenameCategory,
                onMoveCategory = onMoveCategory,
                onDeleteCategoryConfirmed = onDeleteCategoryConfirmed,
                onDropConversationToCategory = onDropConversationToCategory,
                workspaceVisible = workspaceVisible,
                onWorkspaceOpen = onWorkspaceOpen,
                onWorkspaceDismiss = onWorkspaceDismiss,
                workspaceContent = workspaceContent,
            ) {
                pageContent()
            }
        } else {
            pageContent()
        }
    }
}

@Composable
private fun AgentTopBar(
    route: AppRoute?,
    scrollBehavior: ScrollBehavior,
    color: Color,
    onBack: () -> Unit,
    onOpenConversationPane: () -> Unit,
    onNewConversation: () -> Unit,
    onOpenTerminal: () -> Unit,
    onLaunchKimiWeb: () -> Unit,
    kimiWebLabel: String,
    canStopKimiWeb: Boolean,
    onStopKimiWeb: () -> Unit,
    onRefreshKimiWeb: () -> Unit,
    onOpenBrowser: () -> Unit,
    homeCharacter: RoleplayChatUi?,
    onOpenCharacter: () -> Unit,
    conversationStatus: (@Composable () -> Unit)?,
    conversationTrimmed: Pair<Int, Int>?,
) {
    val isHome = route is AppRoute.Home
    val navigationIcon: @Composable () -> Unit = {
        if (isHome) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onOpenConversationPane) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ShortText,
                        modifier = Modifier.size(24.dp),
                        contentDescription = stringResource(R.string.action_conversation_history),
                    )
                }
                // 语音播报放在会话列表右边：两者都在左起第一个位置会互相压住。
                SpeechAutoReadToggle()
                // 角色对话的身份标识放在顶栏左侧，点按进入角色详情；普通对话保持空标题。
                homeCharacter?.let { character ->
                    CharacterTopBarIdentity(character = character, onClick = onOpenCharacter)
                }
            }
        } else {
            MiuixBackButton(onClick = onBack)
        }
    }
    val actions: @Composable RowScope.() -> Unit = {
        if (isHome) {
            // 顶栏第一排：紧挨速度/内存/工具档，放一个新建对话入口。
            IconButton(onClick = onNewConversation) {
                Icon(
                    imageVector = Icons.Rounded.AddComment,
                    contentDescription = stringResource(R.string.action_new_conversation),
                    modifier = Modifier.size(TopBarMenuIconSize),
                )
            }
            TopBarOverflowMenu(
                onNewConversation = onNewConversation,
                onOpenTerminal = onOpenTerminal,
                onLaunchKimiWeb = onLaunchKimiWeb,
                kimiWebLabel = kimiWebLabel,
                canStopKimiWeb = canStopKimiWeb,
                onStopKimiWeb = onStopKimiWeb,
                onRefreshKimiWeb = onRefreshKimiWeb,
                onOpenBrowser = onOpenBrowser,
                conversationTrimmed = conversationTrimmed,
            )
        }
    }

    // 首页聊天舞台与浏览器、终端这类整屏工具页没有可驱动折叠的滚动列表，大标题会一直展开、
    // 白占一屏顶部；它们使用紧凑标题，其余二级内容页统一使用可折叠大标题。
    val compactTitle = isHome || route is AppRoute.Browser || route is AppRoute.Terminal
    if (isHome) {
        // 首页自绘一行：pump 两侧等权留白，胶囊才真正居中；SmallTopAppBar 的 title 只收 String，
        // 塞不进去 Composable。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(color)
                .statusBarsPadding()
                .height(TopBarRowHeight)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            navigationIcon()
            Spacer(Modifier.weight(1f))
            conversationStatus?.invoke()
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onNewConversation) {
                Icon(
                    imageVector = Icons.Rounded.AddComment,
                    contentDescription = stringResource(R.string.action_new_conversation),
                    modifier = Modifier.size(TopBarMenuIconSize),
                )
            }
            TopBarOverflowMenu(
                onNewConversation = onNewConversation,
                onOpenTerminal = onOpenTerminal,
                onLaunchKimiWeb = onLaunchKimiWeb,
                kimiWebLabel = kimiWebLabel,
                canStopKimiWeb = canStopKimiWeb,
                onStopKimiWeb = onStopKimiWeb,
                onRefreshKimiWeb = onRefreshKimiWeb,
                onOpenBrowser = onOpenBrowser,
                conversationTrimmed = conversationTrimmed,
            )
        }
        return
    }
    if (compactTitle) {
        SmallTopAppBar(
            title = titleForRoute(route),
            color = color,
            scrollBehavior = scrollBehavior,
            navigationIcon = navigationIcon,
            actions = actions,
            // miuix 的 actions 行本身是 Arrangement.End（靠右），右边原来有 16dp 默认留白，
            // 收到 6dp：胶囊组、＋、⋯ 一起往右贴，行尾也不空。
            actionIconPadding = 6.dp,
        )
    } else {
        AdaptiveTopAppBar(
            title = titleForRoute(route),
            color = color,
            scrollBehavior = scrollBehavior,
            navigationIcon = navigationIcon,
            actions = actions,
        )
    }
}

private val TopBarMenuIconSize = 20.dp

/** 首页自绘顶栏的高度（与 miuix 紧凑顶栏一致）。 */
private val TopBarRowHeight = 56.dp

/**
 * 首页顶栏溢出菜单。WindowListPopup 以父布局为锚点，因此与触发按钮包在同一个 Box 中，
 * 弹层从按钮下方右对齐展开。
 */
@Composable
private fun TopBarOverflowMenu(
    onNewConversation: () -> Unit,
    onOpenTerminal: () -> Unit,
    onLaunchKimiWeb: () -> Unit,
    kimiWebLabel: String,
    canStopKimiWeb: Boolean,
    onStopKimiWeb: () -> Unit,
    onRefreshKimiWeb: () -> Unit,
    onOpenBrowser: () -> Unit,
    conversationTrimmed: Pair<Int, Int>?,
) {
    var showMenu by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { onRefreshKimiWeb(); showMenu = true }) {
            Icon(
                imageVector = Icons.Outlined.MoreHoriz,
                modifier = Modifier.size(24.dp),
                contentDescription = stringResource(R.string.action_more),
            )
        }
        WindowListPopup(
            show = showMenu,
            alignment = PopupPositionProvider.Align.End,
            onDismissRequest = { showMenu = false },
        ) {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            val virtualEntries = rememberVirtualScreenMenuEntries()
            val openTerminalText = stringResource(R.string.action_open_terminal)
            val stopKimiWebText = stringResource(R.string.capability_kimi_stop)
            val openBrowserText = stringResource(R.string.action_open_browser)
            // 灵屏入口与收藏应用平铺在菜单最上方：点开菜单就能直达，不再有二级页面。
            // 菜单项与动作成对：原来按下标硬分发，插一项就会串位（点「打开浏览器」打到别处）。
            val kimiMenuIcon = if (canStopKimiWeb) Icons.Rounded.Stop else Icons.Rounded.Code
            val rows = buildList<Pair<DropdownItem, () -> Unit>> {
                virtualEntries.forEach { entry ->
                    add(
                        DropdownItem(
                            text = entry.label,
                            icon = { modifier ->
                                val bitmap = entry.icon
                                if (bitmap != null) {
                                    ShortcutIcon(bitmap, modifier.size(TopBarMenuIconSize))
                                } else {
                                    Icon(
                                        imageVector = Icons.Rounded.Smartphone,
                                        contentDescription = null,
                                        modifier = modifier.size(TopBarMenuIconSize),
                                    )
                                }
                            },
                        ) to { openVirtualScreen(context, scope, entry.component) },
                    )
                }
                add(
                    DropdownItem(
                        text = openTerminalText,
                        icon = { modifier ->
                            Icon(
                                imageVector = Icons.Rounded.Terminal,
                                contentDescription = null,
                                modifier = modifier.size(TopBarMenuIconSize),
                            )
                        },
                    ) to onOpenTerminal,
                )
                add(
                    DropdownItem(
                        text = openBrowserText,
                        icon = { modifier ->
                            Icon(
                                imageVector = Icons.Rounded.Language,
                                contentDescription = null,
                                modifier = modifier.size(TopBarMenuIconSize),
                            )
                        },
                    ) to onOpenBrowser,
                )
                // Kimi Code 只占一个位置：没跑给启动（未装则跳去安装页），跑着给停止。
                if (canStopKimiWeb) {
                    add(
                        DropdownItem(
                            text = stopKimiWebText,
                            icon = { modifier ->
                                Icon(
                                    imageVector = kimiMenuIcon,
                                    contentDescription = null,
                                    modifier = modifier.size(TopBarMenuIconSize),
                                )
                            },
                        ) to onStopKimiWeb,
                    )
                } else {
                    add(
                        DropdownItem(
                            text = kimiWebLabel,
                            icon = { modifier ->
                                Icon(
                                    imageVector = kimiMenuIcon,
                                    contentDescription = null,
                                    modifier = modifier.size(TopBarMenuIconSize),
                                )
                            },
                        ) to onLaunchKimiWeb,
                    )
                }
                // 以下两类只展示状态，点了只关菜单。
                conversationTrimmed?.let { (_, count) ->
                    add(DropdownItem(text = stringResource(R.string.conversation_trimmed_capsule, count)) to {})
                }
            }
            val menuItems = rows.map { it.first }
            ListPopupColumn {
                menuItems.forEachIndexed { index, item ->
                    DropdownImpl(
                        item = item,
                        optionSize = menuItems.size,
                        isSelected = false,
                        index = index,
                        onSelectedIndexChange = {
                            showMenu = false
                            rows.getOrNull(index)?.second?.invoke()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun titleForRoute(route: AppRoute?): String = when (route) {
    is AppRoute.Home -> ""
    is AppRoute.Browser -> stringResource(R.string.route_browser)
    is AppRoute.Terminal -> stringResource(R.string.route_terminal)
    is AppRoute.Tools -> stringResource(R.string.route_tools)
    is AppRoute.WebToolsSettings -> stringResource(R.string.web_tools_settings_title)
    is AppRoute.Skills -> stringResource(R.string.route_skills)
    is AppRoute.Characters -> "角色"
    is AppRoute.CharacterDetail -> "角色详情"
    is AppRoute.CharacterEditor -> "编辑角色"
    is AppRoute.CharacterPersona -> "我的人设"
    is AppRoute.CharacterMemory -> "剧情记忆"
    is AppRoute.Permissions -> stringResource(R.string.route_permissions)
    is AppRoute.ConversationCategories -> stringResource(R.string.conversation_categories_title)
    is AppRoute.SystemEnhance -> stringResource(R.string.route_system_enhancements)
    is AppRoute.Settings -> stringResource(R.string.route_settings)
    is AppRoute.TokenUsage -> stringResource(R.string.usage_stats_title)
    is AppRoute.DataUsage -> stringResource(R.string.data_usage_title)
    is AppRoute.SelfCheck -> "功能自检"
    is AppRoute.DiagnosticsPatrol -> stringResource(R.string.diag_patrol_title)
    is AppRoute.DiagnosticsCenter -> "诊断与日志"
    is AppRoute.MemoryDocument -> "MEMORY.md"
    is AppRoute.PromptStudio -> stringResource(R.string.prompt_studio_title)
    is AppRoute.PromptEditor -> stringResource(R.string.prompt_studio_editor_title)
    is AppRoute.PromptVersions -> stringResource(R.string.prompt_studio_versions_title)
    is AppRoute.PromptTools -> stringResource(R.string.prompt_studio_tools_title)
    is AppRoute.PromptPresets -> stringResource(R.string.prompt_preset_title)
    is AppRoute.PromptSnapshots -> stringResource(R.string.prompt_snapshot_title)
    is AppRoute.PromptSnapshotDetail -> stringResource(R.string.prompt_snapshot_detail_title)
    is AppRoute.PromptPresetEditor -> stringResource(R.string.prompt_preset_editor_title)
    is AppRoute.PromptIntegrity -> stringResource(R.string.prompt_integrity_title)
    is AppRoute.PromptEffective -> stringResource(R.string.prompt_effective_title)
    is AppRoute.PromptEffectiveItem -> stringResource(R.string.prompt_effective_title)
    is AppRoute.PromptToolGroups -> stringResource(R.string.prompt_groups_title)
    is AppRoute.SpeechSettings -> stringResource(R.string.speech_settings_title)
    is AppRoute.SpeechRecognition -> stringResource(R.string.speech_recognition_title)
    is AppRoute.SpeechSynthesis -> stringResource(R.string.speech_synthesis_title)
    is AppRoute.SpeechOss -> stringResource(R.string.speech_oss_title)
    is AppRoute.AppearanceSettings -> stringResource(R.string.appearance_title)
    is AppRoute.DataBackup -> stringResource(R.string.data_backup_title)
    is AppRoute.Memory -> stringResource(R.string.route_memory)
    is AppRoute.Tasks -> stringResource(R.string.automation_title)
    is AppRoute.VirtualScreen -> stringResource(R.string.virtual_screen_title)
    is AppRoute.LinuxEnvironment -> stringResource(R.string.route_linux_environment)
    is AppRoute.Workspace -> stringResource(R.string.capability_workspace)
    is AppRoute.SharedFolders -> stringResource(R.string.route_shared_folders)
    is AppRoute.LinuxFiles -> stringResource(R.string.route_linux_files)
    is AppRoute.ModelProviders -> stringResource(R.string.route_model_providers)
    is AppRoute.CommunityCatalog -> "从目录添加"
    is AppRoute.CommunityCatalogProvider -> "选择模型"
    is AppRoute.McpServers -> stringResource(R.string.route_mcp_servers)
    is AppRoute.GitHubAccount -> "GitHub 账号"
    is AppRoute.McpServerDetail -> stringResource(R.string.route_mcp_server_detail)
    is AppRoute.ModelProviderDetail -> stringResource(R.string.route_provider_details)
    is AppRoute.ModelProviderNew -> stringResource(R.string.route_new_provider)
    null -> stringResource(R.string.app_name)
}

@Composable
private fun CharacterTopBarIdentity(character: RoleplayChatUi, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CharacterAvatar(
            name = character.characterName,
            avatarPath = character.avatarPath,
            size = 32.dp,
            shape = CircleShape,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = character.characterName,
            style = MiuixTheme.textStyles.body1,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 180.dp),
        )
    }
}
