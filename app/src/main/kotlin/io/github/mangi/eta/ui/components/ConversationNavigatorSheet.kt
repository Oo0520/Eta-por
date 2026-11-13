package io.github.mangi.eta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.model.ConversationNavEntry
import io.github.mangi.eta.ui.model.ConversationNavRole
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.window.WindowBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 会话导航面板：长会话里翻旧内容用的「目录 + 站内搜索」。
 *
 * 默认按轮次列出每一条用户提问（目录），输入关键词后列出所有命中消息；
 * 点任意一条就跳过去并高亮，和侧栏搜索命中用的是同一套跳转通道。
 *
 * 容器必须用 Window 系组件：本面板在 AgentAppRoot 的 entry 层组合，
 * 那里没有 miuix Scaffold 祖先，Overlay 系（依赖 Scaffold 的 MiuixPopupHost）
 * 在这个位置永远渲染不出来，表现成小白条点了没反应。
 */
@Composable
internal fun ConversationNavigatorSheet(
    show: Boolean,
    messagesKey: Any?,
    latestMessageId: String?,
    onDismiss: () -> Unit,
    onJump: (String) -> Unit,
    /** 长按一条直接把它挂成 @ 引用。 */
    onReference: (String) -> Unit = {},
    entriesFor: (String) -> List<ConversationNavEntry>,
) {
    // 面板高度按主窗口算，必须在弹窗组合外取：WindowBottomSheet 的内容
 // 跑在独立窗口里，在里面取 containerSize 拿到的是弹窗自身尺寸。
    val density = LocalDensity.current
    val sheetHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() } * 0.82f

    WindowBottomSheet(
        show = show,
        title = stringResource(R.string.conversation_navigator_title),
        onDismissRequest = onDismiss,
        insideMargin = DpSize(0.dp, 0.dp),
    ) {
        // 退场动画期间 show 已为 false，保留最后一次内容避免闪空。
        var query by remember { mutableStateOf("") }
        LaunchedEffect(show) {
            if (show) query = ""
        }
        val entries = remember(query, messagesKey) { entriesFor(query) }
        val listState = rememberLazyListState()
        // 目录默认停在最新的那一轮：多数时候要找的是「刚才那几句」。
        LaunchedEffect(show, query) {
            if (show && query.isBlank() && entries.isNotEmpty()) {
                listState.scrollToItem(entries.lastIndex)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(sheetHeight)
                .navigationBarsPadding(),
        ) {
            InputField(
                query = query,
                onQueryChange = { query = it },
                onSearch = {},
                expanded = false,
                onExpandedChange = {},
                label = stringResource(R.string.conversation_navigator_search_hint),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(top = 4.dp, bottom = 6.dp),
            )
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                if (entries.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            text = if (query.isBlank()) {
                                stringResource(R.string.conversation_navigator_empty)
                            } else {
                                stringResource(R.string.conversation_navigator_no_match, query)
                            },
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 48.dp),
                        )
                    }
                }
                items(items = entries, key = { entry -> entry.messageId }) { entry ->
                    ConversationNavRow(
                        entry = entry,
                        onClick = { onJump(entry.messageId) },
                        onLongClick = { onReference(entry.messageId) },
                    )
                }
            }
            if (latestMessageId != null) {
                TextButton(
                    text = stringResource(R.string.conversation_navigator_jump_latest),
                    onClick = { onJump(latestMessageId) },
                    minWidth = 0.dp,
                    minHeight = 34.dp,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    insideMargin = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 12.dp),
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ConversationNavRow(
    entry: ConversationNavEntry,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val isPrompt = entry.role == ConversationNavRole.User
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.Top,
    ) {
        ConversationNavBadge(entry)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.text.ifBlank { stringResource(R.string.conversation_navigator_blank) },
                style = MiuixTheme.textStyles.body2,
                color = if (isPrompt) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontWeight = if (isPrompt) FontWeight.Medium else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            entry.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                Text(
                    text = detail,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 左侧标签：提问显示轮次号，其余显示说话方，便于在搜索命中里分清是谁说的。 */
@Composable
private fun ConversationNavBadge(entry: ConversationNavEntry) {
    val isPrompt = entry.role == ConversationNavRole.User
    val label = when {
        isPrompt && entry.turn > 0 -> entry.turn.toString()
        entry.role == ConversationNavRole.User -> stringResource(R.string.conversation_navigator_you)
        entry.role == ConversationNavRole.System -> stringResource(R.string.conversation_navigator_system)
        else -> stringResource(R.string.conversation_navigator_assistant)
    }
    Box(
        modifier = Modifier
            .height(22.dp)
            .width(if (label.length >= 3) 34.dp else 24.dp)
            .background(
                color = if (isPrompt) {
                    MiuixTheme.colorScheme.primary.copy(alpha = 0.14f)
                } else {
                    MiuixTheme.colorScheme.surfaceContainerHigh
                },
                shape = RoundedCornerShape(7.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote1,
            color = if (isPrompt) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}
