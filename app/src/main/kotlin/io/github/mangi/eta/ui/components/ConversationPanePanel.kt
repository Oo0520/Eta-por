package io.github.mangi.eta.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddComment
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.rounded.Add
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.app.rememberDeviceCapabilities
import io.github.mangi.eta.ui.model.ConversationPaneUiState
import io.github.mangi.eta.ui.model.ConversationSummaryUi
import io.github.mangi.eta.ui.onboarding.OnboardingKeys
import io.github.mangi.eta.ui.onboarding.tourAnchor
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.ListPopupDefaults
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.ExpandLess
import top.yukonga.miuix.kmp.icon.extended.ExpandMore
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Layers
import top.yukonga.miuix.kmp.icon.extended.Lock
import top.yukonga.miuix.kmp.icon.extended.Messages
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.icon.extended.Pin
import top.yukonga.miuix.kmp.icon.extended.Rename
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Unpin
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowListPopup

private object ConversationPanelMetrics {
    val PaneHorizontalPadding = 16.dp
    val TopInset = 6.dp
    val AfterActionBar = 8.dp
    val BottomInset = 8.dp
    val ActionIconSize = 20.dp
    val RowMinHeight = 48.dp
    val RowGap = 4.dp
    val RowCornerRadius = 12.dp
    val RowHorizontalPadding = 12.dp
    val RowVerticalPadding = 12.dp
    val ActiveDotSize = 6.dp
    val ActiveDotGap = 10.dp
    val EmptyVerticalPadding = 28.dp
    val DockTopGap = 8.dp
}

/**
 * 侧栏与外层推移容器共用的背景色。
 *
 * 深色下聊天区保持 surface（纯黑/近黑），侧栏沿用同一颜色会在展开后与聊天区融为一体；
 * 抬高一档到 surfaceContainer，保证两侧始终可分。浅色维持 surface，避免改变现有观感。
 */
@Composable
internal fun conversationPaneContainerColor(): Color {
    val colors = MiuixTheme.colorScheme
    return if (colors.background.luminance() > 0.5f) colors.surface else colors.surfaceContainer
}

@Composable
internal fun ConversationPanePanel(
    state: ConversationPaneUiState,
    width: androidx.compose.ui.unit.Dp,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onSearchChange: (String) -> Unit,
    onNewConversation: () -> Unit,
    /** 在指定分类下新建会话（null 表示未分类）。 */
    onNewConversationInCategory: (String?) -> Unit = { _ -> onNewConversation() },
    onConversationSelected: (String) -> Unit,
    onConversationRename: (ConversationSummaryUi) -> Unit,
    onMoveToCategory: (ConversationSummaryUi) -> Unit,
    /** 拖放落点：第二个参数是目标分组 id，null 表示移出分组。未提供时回落到旧的弹窗选组。 */
    onDropConversationToCategory: ((ConversationSummaryUi, String?) -> Unit)? = null,
    onConversationExport: (ConversationSummaryUi) -> Unit,
    onConversationDelete: (ConversationSummaryUi) -> Unit,
    onConversationPin: (ConversationSummaryUi) -> Unit,
    onBatchDelete: (List<ConversationSummaryUi>) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenRootSettings: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenModelProviders: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenCharacters: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenTasks: () -> Unit,
    onCreateCategory: (String) -> Unit,
    onRenameCategory: (String, String) -> Unit,
    onMoveCategory: (String, Int) -> Unit,
    onDeleteCategoryConfirmed: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // state.conversations 已由 AgentAppState 按标题、预览与消息内容过滤。
    val query = state.searchQuery.trim()
    // 最近打开过的会话：越靠前越新；搜索和批量模式下不显示，免得干扰结果。
    val recentOpened = state.recentOpenedIds
        .mapNotNull { id -> state.conversations.firstOrNull { it.id == id } }
    var batchMode by remember { mutableStateOf(false) }
    // 侧栏内联分类：新建/重命名共用一个输入对话框，不再跳分类页。
    var categoryEditing by remember { mutableStateOf<CategoryEditTarget?>(null) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var collapsedSections by rememberSaveable(
        stateSaver = listSaver<Set<String>, String>(save = { it.toList() }, restore = { it.toSet() }),
    ) { mutableStateOf(emptySet<String>()) }
    // 拖放：记录每个分组标题的屏幕范围，拖动会话时据此判断落点。
    // 拖放：只登记「分类」标题的范围（置顶/今天/日期这些分区不是落点）。
    // 未分类分区没有 id，用空串作哨兵，避免和「没命中任何标题」混为一谈。
    val categoryBounds = remember { mutableStateMapOf<String, androidx.compose.ui.geometry.Rect>() }
    // 拖拽状态挂在列表容器上（参考 Google LazyColumnDragAndDropDemo 的做法：
    // 手势在容器上统一处理，行只负责按 offset 位移，避免每行各自抢手势导致卡死）。
    var draggingConversation by remember { mutableStateOf<ConversationSummaryUi?>(null) }
    var dragTranslateY by remember { mutableFloatStateOf(0f) }
    var contentRootTop by remember { mutableFloatStateOf(0f) }
    var dragPointerRootY by remember { mutableFloatStateOf(0f) }
    var dragGrabOffsetPx by remember { mutableFloatStateOf(0f) }
    var menuConversationId by remember { mutableStateOf<String?>(null) }
    var swallowClickConversationId by remember { mutableStateOf<String?>(null) }
    val dragScope = rememberCoroutineScope()
    var dragMenuJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    // 悬停高亮：拖动时命中的分组标题会亮起，让落点可见。
    var hoverCategoryKey by remember { mutableStateOf<String?>(null) }
    // 命中的含义扩成「这一段落」：从某个分类标题开始，到下一个分类标题之前的区域都算它。
    // 于是拖到分类下面的会话条目上也能落进该分类，不必精确压在标题上。
    fun hitCategory(y: Float): String? =
        categoryBounds.entries
            .sortedBy { it.value.top }
            .lastOrNull { y >= it.value.top }
            ?.key
    fun updateHover(y: Float) {
        val key = hitCategory(y)
        if (key != hoverCategoryKey) hoverCategoryKey = key
    }
    fun dropAt(y: Float) {
        hoverCategoryKey = null
        val conv = draggingConversation ?: return
        draggingConversation = null
        dragTranslateY = 0f
        val key = hitCategory(y) ?: return
        onDropConversationToCategory?.invoke(conv, key.takeIf { it.isNotEmpty() })
    }
    fun cancelDrag() {
        hoverCategoryKey = null
        draggingConversation = null
        dragTranslateY = 0f
        dragMenuJob?.cancel()
        dragMenuJob = null
    }
    // 拖到列表上下边缘时自动滚动，否则分类在屏幕外就拖不过去。
    val dragDensity = LocalDensity.current
    val autoScrollEdgePx = with(dragDensity) { 64.dp.toPx() }
    LaunchedEffect(draggingConversation?.id) {
        if (draggingConversation == null) return@LaunchedEffect
        while (draggingConversation != null) {
            val viewport = listState.layoutInfo.viewportSize.height.toFloat()
            val top = contentRootTop
            val bottom = top + viewport
            val step = with(dragDensity) { 12.dp.toPx() }
            if (dragPointerRootY < top + autoScrollEdgePx) {
                listState.scrollBy(-step)
            } else if (dragPointerRootY > bottom - autoScrollEdgePx) {
                listState.scrollBy(step)
            }
            kotlinx.coroutines.delay(16)
        }
    }
        val capabilities = rememberDeviceCapabilities()
    // 搜索兜底：状态层已过滤过一轮，这里再按标题/预览过滤一次，避免深层过滤失效时列表纹丝不动。
    val visibleConversations = remember(state.conversations, query) {
        if (query.isBlank()) {
            state.conversations
        } else {
            state.conversations.filter { conversation ->
                conversation.title.contains(query, ignoreCase = true) ||
                    conversation.preview.contains(query, ignoreCase = true)
            }
        }
    }
    val groups = remember(visibleConversations, state.categories) {
        visibleConversations.groupForDrawer(state.categories)
    }

    Surface(
        modifier = modifier
            .width(width)
            .fillMaxHeight()
            .tourAnchor("sidebar.panel"),
        color = conversationPaneContainerColor(),
        contentColor = MiuixTheme.colorScheme.onSurface,
    ) {
        // 顶部品牌与底部搜索固定，历史列表在中间滚动。
        Column(modifier = Modifier.fillMaxSize().imePadding()) {
            PaneFixedRegion(anchorId = "sidebar.top") {
                Column(
                    modifier = Modifier
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                        )
                        .padding(horizontal = ConversationPanelMetrics.PaneHorizontalPadding),
                ) {
                    Spacer(Modifier.height(ConversationPanelMetrics.TopInset))
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, tint = Color.Unspecified,
                            modifier = Modifier.size(40.dp).clip(CircleShape)
                                .background(MiuixTheme.colorScheme.surfaceContainerHighest).padding(4.dp))
                        Row(
                            modifier = Modifier.weight(1f).padding(start = 10.dp, end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                stringResource(R.string.app_name),
                                style = MiuixTheme.textStyles.headline2,
                                fontWeight = FontWeight.Bold,
                                color = MiuixTheme.colorScheme.onSurface,
                                maxLines = 1,
                            )
                            PaneModeBadges(
                                rootGranted = capabilities.root.isGranted,
                                accessibilityAvailable = capabilities.accessibilityAvailable,
                                onOpenRootSettings = onOpenRootSettings,
                                onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                                onOpenSettings = onOpenSettings,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    Spacer(Modifier.height(ConversationPanelMetrics.AfterActionBar))
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clipToBounds()
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
                    )
                    .padding(horizontal = ConversationPanelMetrics.PaneHorizontalPadding)
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .onGloballyPositioned { coords -> contentRootTop = coords.boundsInRoot().top }
                    .pointerInput(listState, state.conversations) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { offset ->
                                val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                                    offset.y.toInt() in item.offset..(item.offset + item.size)
                                }
                                val key = info?.key as? String
                                val conversation = key?.let { id -> state.conversations.firstOrNull { it.id == id } }
                                if (conversation != null) {
                                    draggingConversation = conversation
                                    dragTranslateY = 0f
                                    dragPointerRootY = offset.y + contentRootTop
                                    dragGrabOffsetPx = (offset.y.toInt() - info.offset).coerceAtLeast(0).toFloat()
                                }
                            },
                            onDrag = { change, dragAmount ->
                                if (draggingConversation == null) return@detectDragGesturesAfterLongPress
                                if (dragAmount.y != 0f) dragMenuJob?.cancel()
                                change.consume()
                                dragTranslateY += dragAmount.y
                                dragPointerRootY = change.position.y + contentRootTop
                                updateHover(dragPointerRootY)
                            },
                            onDragEnd = {
                                if (draggingConversation != null) dropAt(dragPointerRootY)
                            },
                            onDragCancel = { cancelDrag() },
                        )
                    },
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(ConversationPanelMetrics.RowGap),
                overscrollEffect = null,
            ) {
                if (query.isBlank() && !batchMode && recentOpened.isNotEmpty()) {
                    item(key = "recent-opened") {
                        RecentOpenedSection(
                            conversations = recentOpened,
                            onOpen = onConversationSelected,
                        )
                    }
                }
                item(key = "history-heading") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 10.dp, bottom = 4.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (batchMode) "已选 ${selectedIds.size} 项" else stringResource(R.string.action_conversation_history),
                            style = MiuixTheme.textStyles.body2,
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f).semantics { heading() },
                        )
                        if (batchMode) {
                            Text("全选", color = MiuixTheme.colorScheme.primary, fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 8.dp).clickable {
                                    selectedIds = state.conversations.filterNot { it.isPinned || state.categories.any { c -> c.first == it.categoryId } }.map { it.id }.toSet()
                                })
                            Text("取消", color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 8.dp).clickable {
                                    batchMode = false; selectedIds = emptySet()
                                })
                            Text("删除", color = if (selectedIds.isEmpty()) MiuixTheme.colorScheme.onSurfaceVariantSummary else MiuixTheme.colorScheme.error,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 8.dp).clickable(enabled = selectedIds.isNotEmpty()) {
                                    onBatchDelete(state.conversations.filter { it.id in selectedIds && !it.isPinned })
                                    batchMode = false; selectedIds = emptySet()
                                })
                        } else {
                            Text("批量删除", color = MiuixTheme.colorScheme.primary, fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 8.dp).clickable { batchMode = true; selectedIds = emptySet() })
                        }
                    }
                }
                // 引导文案以提示条为准（B 组），空列表也用它；
                // EmptyConversations 那句「还没有对话」只在搜索无结果时用，避免两条并排。
                item(key = "pane-hint") {
                    PaneHintBar(
                        id = OnboardingKeys.SidebarHint,
                        text = if (visibleConversations.isEmpty()) {
                            "对话会出现在这里。对话后新建分类，之后把它们拖进分类。"
                        } else {
                            "会话可改名 / 置顶 / 导出/分组；按住拖动可以快速调整分组。"
                        },
                    )
                }
                if (visibleConversations.isEmpty()) {
                    // 完全没会话时由上面的提示条说明；只有搜索无结果才额外给一句。
                    if (query.isNotBlank()) {
                        item {
                            EmptyConversations(isSearching = true)
                        }
                    }
                } else {
                    groups.forEach { group ->
                        val sectionKey = group.section.storageKey()
                        val collapsed = sectionKey in collapsedSections
                        item(key = "section-$sectionKey") {
                            ConversationSectionHeader(
                                group = group,
                                collapsed = collapsed,
                                onToggle = {
                                    collapsedSections = if (collapsed) collapsedSections - sectionKey else collapsedSections + sectionKey
                                },
                                categoryId = (group.section as? ConversationDrawerSection.Category)?.id,
                            onHeaderBounds = { _, rect ->
                                (group.section as? ConversationDrawerSection.Category)?.let { category ->
                                    categoryBounds[category.id ?: ""] = rect
                                }
                            },
                            highlighted = hoverCategoryKey != null &&
                                hoverCategoryKey == (group.section as? ConversationDrawerSection.Category)?.let { it.id ?: "" },
                                isCategorySection = group.section is ConversationDrawerSection.Category,
                                onNewConversationInCategory = onNewConversationInCategory,
                                anchorId = if (group == groups.first()) "sidebar.header" else null,
                                onEditCategory = { id, currentName ->
                                    categoryEditing = CategoryEditTarget.Rename(id, currentName)
                                },
                                onMoveCategory = onMoveCategory,
                                onDeleteCategory = { id -> onDeleteCategoryConfirmed(id) },
                            )
                        }
                        if (collapsed) return@forEach
                        val visibleItems = group.items
                        items(
                            items = visibleItems,
                            key = { it.id },
                        ) { conversation ->
                            ConversationTextRow(
                                conversation = conversation,
                                selected = conversation.id == state.selectedConversationId,
                                batchMode = batchMode,
                                checked = conversation.id in selectedIds,
                                onClick = {
                                    if (swallowClickConversationId == conversation.id) {
                                        // 长按刚弹出的菜单：这一下点击吞掉，既不切会话也不关菜单。
                                        swallowClickConversationId = null
                                    } else if (batchMode) {
                                        selectedIds = if (conversation.id in selectedIds) selectedIds - conversation.id else selectedIds + conversation.id
                                    } else {
                                        onConversationSelected(conversation.id)
                                    }
                                },
                                onRename = { onConversationRename(conversation) },
                                onExport = { onConversationExport(conversation) },
                                onDelete = { onConversationDelete(conversation) },
                                onPin = { onConversationPin(conversation) },
                                onMoveToCategory = { onMoveToCategory(conversation) },
                                anchorId = if (
                                    group == groups.first() && conversation == visibleItems.first()
                                ) {
                                    "sidebar.item"
                                } else {
                                    null
                                },
                                dragging = draggingConversation?.id == conversation.id,
                                dragTranslation = if (draggingConversation?.id == conversation.id) dragTranslateY else 0f,
                                menuOpen = menuConversationId == conversation.id,
                                onRequestMenu = { menuConversationId = conversation.id },
                                onDismissMenu = { if (menuConversationId == conversation.id) menuConversationId = null },
                            )
                        }
                    }
                }
            }
            draggingConversation?.let { ghostConversation ->
                if (true) {
                    // 浮层用 Popup 画：脱离 LazyColumn 的裁剪和绘制顺序，拖动时不会被下面的行盖住。
                    androidx.compose.ui.window.Popup(
                        offset = androidx.compose.ui.unit.IntOffset(
                            x = 0,
                            y = (dragPointerRootY - dragGrabOffsetPx).toInt(),
                        ),
                        properties = androidx.compose.ui.window.PopupProperties(
                            focusable = false,
                            clippingEnabled = false,
                            dismissOnBackPress = false,
                            dismissOnClickOutside = false,
                        ),
                    ) {
                        ConversationDragGhost(
                            conversation = ghostConversation,
                            modifier = Modifier
                                .width(width)
                                .padding(horizontal = ConversationPanelMetrics.PaneHorizontalPadding),
                        )
                    }
                }
            }
            PaneFixedRegion {
                Column(
                    modifier = Modifier
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                        )
                        .padding(horizontal = ConversationPanelMetrics.PaneHorizontalPadding),
                ) {
                    Spacer(Modifier.height(ConversationPanelMetrics.DockTopGap))
                    PaneSearchAndSettings(
                        query = state.searchQuery,
                        onSearchChange = onSearchChange,
                        onOpenSettings = onOpenSettings,
                        onNewCategory = { categoryEditing = CategoryEditTarget.New },
                    )
                    Spacer(Modifier.height(ConversationPanelMetrics.BottomInset))
                }
            }
        }

        categoryEditing?.let { target ->
            CategoryNameDialog(
                target = target,
                onDismiss = { categoryEditing = null },
                onConfirm = { name ->
                    when (target) {
                        is CategoryEditTarget.New -> onCreateCategory(name)
                        is CategoryEditTarget.Rename -> onRenameCategory(target.id, name)
                    }
                    categoryEditing = null
                },
            )
        }
    }
}

@Composable
private fun PaneFixedRegion(
    /** 只有顶部那块要当引导锚点；底部传 null。 */
    anchorId: String? = null,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxWidth()
            .background(conversationPaneContainerColor())
            // 用普通 tourAnchor（每帧更新）而不是 tourAnchorFirst（只认第一次）：
            // 侧栏关闭时这块在屏幕外，首次登记的坐标是负的，之后不会自动纠正。
            .then(if (anchorId != null) Modifier.tourAnchor(anchorId) else Modifier),
    ) {
        content()
    }
}

@Composable
private fun PaneSearchAndSettings(
    query: String,
    onSearchChange: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onNewCategory: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SearchBar(
            modifier = Modifier.weight(1f),
            insideMargin = DpSize.Zero,
            expanded = false,
            onExpandedChange = {},
            inputField = {
                InputField(
                    query = query,
                    onQueryChange = onSearchChange,
                    onSearch = onSearchChange,
                    expanded = false,
                    onExpandedChange = {},
                    label = stringResource(R.string.conversation_search_hint),
                    // 深色下侧栏已抬高到 surfaceContainer，搜索框需再高一档才能显出轮廓；
                    // 浅色的 surfaceContainerHigh 与 surfaceContainerHighest 相同，观感不变。
                    color = MiuixTheme.colorScheme.surfaceContainerHighest,
                )
            },
            content = {},
        )
        // 新建分类就地弹输入框，不再跳分类页。
        IconButton(
            onClick = onNewCategory,
            modifier = Modifier.size(48.dp).clip(CircleShape)
                .background(MiuixTheme.colorScheme.surfaceContainerHighest),
        ) {
            Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.conversation_category_new), modifier = Modifier.size(22.dp))
        }
        IconButton(
            onClick = onOpenSettings,
            modifier = Modifier.size(48.dp).clip(CircleShape)
                .background(MiuixTheme.colorScheme.surfaceContainerHighest),
        ) {
            Icon(MiuixIcons.Settings, contentDescription = stringResource(R.string.sidebar_settings), modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun PaneModeBadges(
    rootGranted: Boolean,
    accessibilityAvailable: Boolean,
    onOpenRootSettings: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (rootGranted) PaneModeBadge(stringResource(R.string.sidebar_mode_root), onClick = onOpenRootSettings)
        if (!rootGranted && !accessibilityAvailable) PaneModeBadge(stringResource(R.string.sidebar_mode_basic), onClick = onOpenSettings, active = false)
    }
}

@Composable
private fun PaneModeBadge(label: String, onClick: () -> Unit, active: Boolean = true) {
    val description = stringResource(R.string.sidebar_mode_description, label)
    val color = if (active) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary
    Text(
        text = label,
        modifier = Modifier.clip(CircleShape).background(color.copy(alpha = 0.12f))
            .semantics { contentDescription = description }
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.sidebar_settings), onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 3.dp),
        color = color,
        style = MiuixTheme.textStyles.footnote1,
        fontSize = 11.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun ConversationSectionHeader(
    group: ConversationDrawerGroup,
    collapsed: Boolean,
    onToggle: () -> Unit,
    categoryId: String?,
    onHeaderBounds: (String?, androidx.compose.ui.geometry.Rect) -> Unit = { _, _ -> },
    highlighted: Boolean = false,
    onEditCategory: (String, String) -> Unit,
    onMoveCategory: (String, Int) -> Unit,
    onDeleteCategory: (String) -> Unit,
    isCategorySection: Boolean = false,
    onNewConversationInCategory: (String?) -> Unit = {},
    /** 只有第一个分组标题挂锚点，避免每个分组都覆盖同一个 id。 */
    anchorId: String? = null,
) {
    var showMenu by remember { mutableStateOf(false) }
    val headerLabel = group.localizedLabel()
    // 外层只负责左右留白，内层负责圆角与点击：这样按压/悬停色块是和会话行一致的圆角块，
    // 而不是通栏直角带（之前 clickable 直接贴在全宽 Row 上，涟漪是方的）。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ConversationPanelMetrics.RowHorizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
    Row(
        modifier = Modifier.weight(1f)
            // 记录本分组的屏幕范围：拖动会话时据此判断松手落在哪个分组上。
            .then(if (anchorId != null) Modifier.tourAnchor(anchorId) else Modifier)
            .onGloballyPositioned { coords -> onHeaderBounds(categoryId, coords.boundsInRoot()) }
            .clip(RoundedCornerShape(ConversationPanelMetrics.RowCornerRadius))
            .then(
                if (highlighted) {
                    Modifier.background(MiuixTheme.colorScheme.surfaceContainerHighest)
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onToggle)
            .padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .padding(end = 10.dp)
                .size(width = 3.dp, height = 16.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(
                    if (collapsed) {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.4f)
                    } else {
                        MiuixTheme.colorScheme.primary
                    },
                ),
        )
        Text(headerLabel, color = MiuixTheme.colorScheme.onSurface,
            style = MiuixTheme.textStyles.body1, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f).semantics { heading() })
        if (categoryId != null && collapsed) {
            Box {
                IconButton(onClick = { showMenu = true }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Outlined.MoreHoriz,
                        contentDescription = stringResource(R.string.conversation_category_rename),
                        modifier = Modifier.size(18.dp),
                    )
                }
                WindowListPopup(
                    show = showMenu,
                    alignment = PopupPositionProvider.Align.End,
                    onDismissRequest = { showMenu = false },
                ) {
                    // 与会话菜单一致：带图标，删除用红色强调。
                    val iconSize = ConversationPanelMetrics.ActionIconSize
                    val items = listOf(
                        DropdownItem(text = stringResource(R.string.conversation_category_rename), icon = { modifier ->
                            Icon(MiuixIcons.Rename, null, modifier.size(iconSize), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }),
                        DropdownItem(text = stringResource(R.string.conversation_category_move_up), icon = { modifier ->
                            Icon(Icons.Rounded.KeyboardArrowUp, null, modifier.size(iconSize), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }),
                        DropdownItem(text = stringResource(R.string.conversation_category_move_down), icon = { modifier ->
                            Icon(Icons.Rounded.KeyboardArrowDown, null, modifier.size(iconSize), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }),
                        DropdownItem(text = stringResource(R.string.conversation_category_delete), icon = { modifier ->
                            Icon(MiuixIcons.Delete, null, modifier.size(iconSize), tint = MiuixTheme.colorScheme.error)
                        }),
                    )
                    val deleteColors = DropdownDefaults.dropdownColors(
                        contentColor = MiuixTheme.colorScheme.error,
                        selectedContentColor = MiuixTheme.colorScheme.error,
                        selectedIndicatorColor = MiuixTheme.colorScheme.error,
                    )
                    ListPopupColumn {
                        items.forEachIndexed { index, item ->
                            DropdownImpl(
                                item = item,
                                optionSize = items.size,
                                isSelected = false,
                                index = index,
                                dropdownColors = if (index == 3) deleteColors else DropdownDefaults.dropdownColors(),
                                onSelectedIndexChange = {
                                    showMenu = false
                                    when (index) {
                                        0 -> onEditCategory(categoryId, headerLabel)
                                        1 -> onMoveCategory(categoryId, -1)
                                        2 -> onMoveCategory(categoryId, 1)
                                        else -> onDeleteCategory(categoryId)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        } else if (categoryId != null && collapsed) {
            // 只有真实分类（有 id）、且收起时给一个 ▸；「置顶 / 今天 / 未分类」都不需要三角。
            Text(
                "▸",
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.footnote1, fontSize = 12.sp,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
        if (isCategorySection && collapsed) {
            IconButton(
                onClick = { onNewConversationInCategory(categoryId) },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    Icons.Rounded.AddComment,
                    contentDescription = stringResource(R.string.action_new_conversation),
                    modifier = Modifier.size(19.dp),
                    tint = MiuixTheme.colorScheme.primary,
                )
            }
        }
    }
    }
}

@OptIn(ExperimentalFoundationApi::class)
/** 侧栏最上面一栏：最近打开过的对话，最多 3 条，多的折叠成 +N。 */
@Composable
private fun RecentOpenedSection(
    conversations: List<ConversationSummaryUi>,
    onOpen: (String) -> Unit,
) {
    val expanded = remember { mutableStateOf(false) }
    val shown = if (expanded.value) conversations else conversations.take(3)
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 10.dp, bottom = 4.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "最近打开",
                style = MiuixTheme.textStyles.body2,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (conversations.size > 3) {
                Text(
                    if (expanded.value) "收起" else "+${conversations.size - 3}",
                    color = MiuixTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 8.dp).clickable { expanded.value = !expanded.value },
                )
            }
        }
        shown.forEach { conversation ->
            Text(
                text = conversation.title.ifBlank { conversation.preview }.ifBlank { "会话" },
                color = MiuixTheme.colorScheme.onSurface,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onOpen(conversation.id) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun ConversationTextRow(
    conversation: ConversationSummaryUi,
    selected: Boolean,
    batchMode: Boolean = false,
    checked: Boolean = false,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onPin: () -> Unit,
    onMoveToCategory: () -> Unit,
    dragging: Boolean = false,
    dragTranslation: Float = 0f,
    menuOpen: Boolean = false,
    onRequestMenu: () -> Unit = {},
    onDismissMenu: () -> Unit = {},
    /** 只有第一个会话行挂锚点。 */
    anchorId: String? = null,
) {
    // 本节自身在屏幕上的纵向位置：拖动时用它把手指坐标换算成屏幕坐标，判断落点。
    val hapticFeedback = LocalHapticFeedback.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (dragging) 10f else 0f)
            .then(if (anchorId != null) Modifier.tourAnchor(anchorId) else Modifier),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = ConversationPanelMetrics.RowMinHeight)
                .clip(RoundedCornerShape(ConversationPanelMetrics.RowCornerRadius))
                .background(
                    if (selected) {
                        // 与侧栏背景保持一档亮度差；浅色下与 surfaceContainerHigh 相同。
                        MiuixTheme.colorScheme.surfaceContainerHighest
                    } else {
                        Color.Transparent
                    },
                )
                .alpha(if (dragging) 0f else 1f)
                .clickable(onClick = onClick)
                .padding(
                    horizontal = ConversationPanelMetrics.RowHorizontalPadding,
                    vertical = ConversationPanelMetrics.RowVerticalPadding,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (batchMode) {
                Checkbox(state = if (checked) ToggleableState.On else ToggleableState.Off, onClick = onClick, modifier = Modifier.padding(end = 4.dp))
            }
            conversation.characterName?.let { name ->
                CharacterAvatar(
                    name = name,
                    avatarPath = conversation.characterAvatarPath,
                    size = 36.dp,
                    shape = CircleShape,
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
            val title = conversation.title.ifBlank { conversation.preview }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (conversation.isPinned) {
                Icon(
                    imageVector = MiuixIcons.Pin,
                    contentDescription = stringResource(R.string.conversation_pin_marker),
                    tint = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.size(13.dp),
                )
            }
            Text(
                modifier = Modifier.weight(1f),
                text = title,
                color = if (selected) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.onSurface
                },
                style = MiuixTheme.textStyles.body2,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            }
            // 标题与角色名相同（如未改名的角色会话）时不再重复第二行。
            conversation.characterName?.takeIf { it != title }?.let { name ->
                Text(name, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            }
            if (conversation.isActiveRun) {
                Box(
                    modifier = Modifier
                        .padding(start = ConversationPanelMetrics.ActiveDotGap)
                        .size(ConversationPanelMetrics.ActiveDotSize)
                        .clip(CircleShape)
                        .background(MiuixTheme.colorScheme.primary),
                )
            }
            // 三点只在选中行出现：常驻会让每行都多一个图标，侧栏本来就密；
            // 选中态本身表示「正在操作这条」，三点放在这里最自然。
            if (!batchMode) {
                IconButton(
                    onClick = {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onRequestMenu()
                    },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        Icons.Outlined.MoreHoriz,
                        contentDescription = stringResource(R.string.action_more),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        WindowListPopup(
            show = menuOpen,
            popupPositionProvider = ListPopupDefaults.ContextMenuPositionProvider,
            alignment = PopupPositionProvider.Align.BottomEnd,
            onDismissRequest = onDismissMenu,
        ) {
            val pinText = stringResource(if (conversation.isPinned) R.string.action_unpin else R.string.action_pin)
            val moveText = stringResource(R.string.conversation_category_move)
            val renameText = stringResource(R.string.action_rename)
            val exportText = stringResource(R.string.action_export)
            val deleteText = stringResource(R.string.action_delete)
            val moveItem = remember(moveText) {
                DropdownItem(
                    text = moveText,
                    icon = { modifier ->
                        Icon(
                            imageVector = MiuixIcons.Notes,
                            contentDescription = null,
                            modifier = modifier.size(ConversationPanelMetrics.ActionIconSize),
                        )
                    },
                )
            }

            val renameItem = remember(renameText) {
                DropdownItem(
                    text = renameText,
                    icon = { modifier ->
                        Icon(
                            imageVector = MiuixIcons.Rename,
                            contentDescription = null,
                            modifier = modifier.size(ConversationPanelMetrics.ActionIconSize),
                        )
                    },
                )
            }
            val exportItem = remember(exportText) {
                DropdownItem(
                    text = exportText,
                    icon = { modifier ->
                        Icon(
                            imageVector = MiuixIcons.Download,
                            contentDescription = null,
                            modifier = modifier.size(ConversationPanelMetrics.ActionIconSize),
                        )
                    },
                )
            }
            val deleteItem = remember(deleteText) {
                DropdownItem(
                    text = deleteText,
                    icon = { modifier ->
                        Icon(
                            imageVector = MiuixIcons.Delete,
                            contentDescription = null,
                            modifier = modifier.size(ConversationPanelMetrics.ActionIconSize),
                            tint = MiuixTheme.colorScheme.error,
                        )
                    },
                )
            }
            val pinItem = remember(pinText, conversation.isPinned) {
                DropdownItem(
                    text = pinText,
                    icon = { modifier ->
                        Icon(
                            imageVector = if (conversation.isPinned) MiuixIcons.Unpin else MiuixIcons.Pin,
                            contentDescription = null,
                            modifier = modifier.size(ConversationPanelMetrics.ActionIconSize),
                            tint = MiuixTheme.colorScheme.primary,
                        )
                    },
                )
            }
            val deleteColors = DropdownDefaults.dropdownColors(
                contentColor = MiuixTheme.colorScheme.error,
                selectedContentColor = MiuixTheme.colorScheme.error,
                selectedIndicatorColor = MiuixTheme.colorScheme.error,
            )
            ListPopupColumn {
                DropdownImpl(
                    item = moveItem,
                    optionSize = 5,
                    isSelected = false,
                    index = 0,
                    onSelectedIndexChange = {
                        onDismissMenu()
                        onMoveToCategory()
                    },
                )
                DropdownImpl(
                    item = pinItem,
                    optionSize = 5,
                    isSelected = false,
                    index = 1,
                    onSelectedIndexChange = {
                        onDismissMenu()
                        onPin()
                    },
                )
                DropdownImpl(
                    item = renameItem,
                    optionSize = 5,
                    isSelected = false,
                    index = 2,
                    onSelectedIndexChange = {
                        onDismissMenu()
                        onRename()
                    },
                )
                DropdownImpl(
                    item = exportItem,
                    optionSize = 5,
                    isSelected = false,
                    index = 3,
                    onSelectedIndexChange = {
                        onDismissMenu()
                        onExport()
                    },
                )
                DropdownImpl(
                    item = deleteItem,
                    optionSize = 5,
                    isSelected = false,
                    index = 4,
                    dropdownColors = deleteColors,
                    onSelectedIndexChange = {
                        onDismissMenu()
                        onDelete()
                    },
                )
            }
        }
    }
}

@Composable
private fun EmptyConversations(isSearching: Boolean) {
    Text(
        text = stringResource(
            if (isSearching) R.string.conversation_no_results else R.string.conversation_empty,
        ),
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        style = MiuixTheme.textStyles.body2,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(
            horizontal = ConversationPanelMetrics.RowHorizontalPadding,
            vertical = ConversationPanelMetrics.EmptyVerticalPadding,
        ),
    )
}

@Composable
private fun PaneShortcut(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
        .clickable(onClickLabel = label, onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp), tint = MiuixTheme.colorScheme.onSurface)
        Text(label, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}


/** 拖动时跟着手指走的一份行副本（不改列表布局，只做视觉反馈）。 */
@Composable
private fun ConversationDragGhost(
    conversation: ConversationSummaryUi,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .heightIn(min = ConversationPanelMetrics.RowMinHeight)
            .clip(RoundedCornerShape(ConversationPanelMetrics.RowCornerRadius))
            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
            .padding(
                horizontal = ConversationPanelMetrics.RowHorizontalPadding,
                vertical = ConversationPanelMetrics.RowVerticalPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = conversation.title.ifBlank { stringResource(R.string.conversation_unnamed) },
            style = MiuixTheme.textStyles.body1,
            color = MiuixTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

private data class ConversationDrawerGroup(
    val section: ConversationDrawerSection,
    val items: List<ConversationSummaryUi>,
)

private sealed interface ConversationDrawerSection {
    data object Pinned : ConversationDrawerSection
    data object Today : ConversationDrawerSection
    data class Dated(val label: String) : ConversationDrawerSection
    data class Category(val id: String?, val name: String) : ConversationDrawerSection
}

@Composable
private fun ConversationDrawerGroup.localizedLabel(): String = when (val value = section) {
    ConversationDrawerSection.Pinned -> stringResource(R.string.conversation_section_pinned)
    ConversationDrawerSection.Today -> stringResource(R.string.conversation_section_today)
    is ConversationDrawerSection.Dated -> value.label
    is ConversationDrawerSection.Category -> value.name.ifBlank { stringResource(R.string.conversation_category_uncategorized) }
}

private fun ConversationDrawerSection.storageKey(): String = when (this) {
    ConversationDrawerSection.Pinned -> "pinned"
    ConversationDrawerSection.Today -> "today"
    is ConversationDrawerSection.Dated -> "dated:$label"
    is ConversationDrawerSection.Category -> "category:${id ?: "-"}"
}

private fun List<ConversationSummaryUi>.groupForDrawer(categories: List<Pair<String, String>>): List<ConversationDrawerGroup> {
    // 只要建过分类就按「置顶 / 各分类 / 未分类」分组；空分类也要露出来，
    // 否则刚建完分类看不到东西，像没保存成功。没有分类时才回退到时间分组。
    val known = categories.toMap()
    if (known.isNotEmpty()) {
        val categorizedGroups = mutableListOf<ConversationDrawerGroup>()
        val pinnedItems = filter { it.isPinned }
        if (pinnedItems.isNotEmpty()) {
            categorizedGroups += ConversationDrawerGroup(ConversationDrawerSection.Pinned, pinnedItems)
        }
        val rest = filterNot { it.isPinned }
        categories.forEach { (categoryId, categoryName) ->
            val items = rest.filter { it.categoryId == categoryId }
            categorizedGroups += ConversationDrawerGroup(
                ConversationDrawerSection.Category(categoryId, categoryName),
                items,
            )
        }
        val loose = rest.filter { it.categoryId == null || !known.containsKey(it.categoryId) }
        if (loose.isNotEmpty()) {
            categorizedGroups += ConversationDrawerGroup(ConversationDrawerSection.Category(null, ""), loose)
        }
        return categorizedGroups
    }
    if (isEmpty()) return emptyList()
    val groups = mutableListOf<ConversationDrawerGroup>()
    for (conversation in this) {
        val section = conversation.drawerSection()
        val last = groups.lastOrNull()
        if (last?.section == section) {
            groups[groups.lastIndex] = last.copy(items = last.items + conversation)
        } else {
            groups += ConversationDrawerGroup(section = section, items = listOf(conversation))
        }
    }
    return groups
}

private fun ConversationSummaryUi.drawerSection(): ConversationDrawerSection = when {
    isPinned -> ConversationDrawerSection.Pinned
    isActiveRun || isUpdatedToday(updatedAtMillis) -> ConversationDrawerSection.Today
    else -> ConversationDrawerSection.Dated(timeLabel)
}

private fun isUpdatedToday(timestampMillis: Long): Boolean {
    if (timestampMillis <= 0L) return true
    val now = java.util.Calendar.getInstance()
    val target = java.util.Calendar.getInstance().apply { timeInMillis = timestampMillis }
    return now.get(java.util.Calendar.ERA) == target.get(java.util.Calendar.ERA) &&
        now.get(java.util.Calendar.YEAR) == target.get(java.util.Calendar.YEAR) &&
        now.get(java.util.Calendar.DAY_OF_YEAR) == target.get(java.util.Calendar.DAY_OF_YEAR)
}

/** 侧栏内联分类对话框的两种用途。 */
private sealed interface CategoryEditTarget {
    data object New : CategoryEditTarget
    data class Rename(val id: String, val name: String) : CategoryEditTarget
}

/** 新建 / 重命名分类的输入对话框：只收一个名字，确认后交给上层写库。 */
@Composable
private fun CategoryNameDialog(
    target: CategoryEditTarget,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember {
        mutableStateOf(if (target is CategoryEditTarget.Rename) target.name else "")
    }
    val isNew = target is CategoryEditTarget.New
    EtaWindowDialog(
        show = true,
        title = stringResource(
            if (isNew) R.string.conversation_category_new else R.string.conversation_category_rename,
        ),
        onDismissRequest = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainerHighest)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                if (name.isEmpty()) {
                    Text(
                        text = stringResource(R.string.conversation_category_name_hint),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = MiuixTheme.colorScheme.onSurface,
                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            MiuixDialogActions(
                confirmText = stringResource(R.string.conversation_category_save),
                confirmEnabled = name.isNotBlank(),
                onCancel = onDismiss,
                onConfirm = { onConfirm(name.trim()) },
            )
        }
    }
}
