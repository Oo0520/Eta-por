package io.github.mangi.eta.ui.screens.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import io.github.mangi.eta.ui.app.AgentCommands
import io.github.mangi.eta.ui.components.AgentChatBody
import io.github.mangi.eta.ui.components.LocalRoleplayChat
import io.github.mangi.eta.ui.components.chatConversationCompositionKey
import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.model.AgentCommand
import io.github.mangi.eta.ui.model.CommandPaletteUi
import io.github.mangi.eta.ui.model.CommandResultUi
import io.github.mangi.eta.ui.model.ToolAccessUiState
import io.github.mangi.eta.ui.model.ToolToggleKind
import io.github.mangi.eta.ui.model.AgentHomeAction
import io.github.mangi.eta.ui.model.AgentModelPickerUiState
import io.github.mangi.eta.ui.model.roleplayChatUi

/**
 * AgentChatHome：首屏为聊天主舞台。
 *
 * 顶部入口统一由 [io.github.mangi.eta.ui.app.AgentAppShell] 提供，
 * 本 Screen 只负责消息流、Run trace、工具摘要和底部输入框。
 */
@Composable
internal fun AgentHomeScreen(
    state: AgentChatHomeUiState,
    modelPickerState: AgentModelPickerUiState,
    conversationKey: String?,
    /** 斜杠命令表：内置命令 + 已启用技能，由上层注入。 */
    commands: List<AgentCommand> = AgentCommands.builtIns,
    /** 命令结果卡与主动展开的命令面板。 */
    commandResult: CommandResultUi? = null,
    commandPalette: CommandPaletteUi? = null,
    isPaused: Boolean = false,
    onAction: (AgentHomeAction) -> Unit,
    /** 输入框上方两个入口用的工具/技能开关状态。 */
    toolAccess: ToolAccessUiState = ToolAccessUiState(),
    onRefreshToolAccess: () -> Unit = {},
    isDrawerOpen: Boolean = false,
    modifier: Modifier = Modifier,
) {
    key(chatConversationCompositionKey(conversationKey)) {
        CompositionLocalProvider(LocalRoleplayChat provides state.roleplayChatUi()) {
        AgentChatBody(
            messages = state.messages,
            draftKey = conversationKey,
            modelPickerState = modelPickerState,
            isCompacting = state.isCompacting,
            input = state.input,
            isStreaming = state.isStreaming,
            reasoningEffort = state.reasoningEffort,
            availableReasoningEfforts = state.availableReasoningEfforts,
            pendingImages = state.pendingImages,
            pendingFileReferences = state.pendingFileReferences,
            messageEdit = state.messageEdit,
            onReasoningEffortChange = { onAction(AgentHomeAction.ReasoningEffortChanged(it)) },
            onCompactContext = { onAction(AgentHomeAction.CompactContext) },
            canCompactContext = state.canCompactContext,
            onModelSelected = { onAction(AgentHomeAction.ModelSelected(it)) },
            onSubmit = { text -> onAction(AgentHomeAction.SubmitMessage(text)) },
            onInterruptAndSubmit = { text -> onAction(AgentHomeAction.InterruptAndSubmitMessage(text)) },
            onQueueMessage = { text -> onAction(AgentHomeAction.QueueMessage(text)) },
            queuedMessages = state.queuedMessages,
            onSteerQueuedMessage = { id -> onAction(AgentHomeAction.SteerQueuedMessage(id)) },
            onRecallQueuedMessage = { id, draft -> onAction(AgentHomeAction.RecallQueuedMessage(id, draft)) },
            onRemoveQueuedMessage = { id -> onAction(AgentHomeAction.RemoveQueuedMessage(id)) },
            onStop = { onAction(AgentHomeAction.StopRun) },
            isPaused = isPaused,
            onPause = { onAction(AgentHomeAction.PauseRun) },
            onResume = { onAction(AgentHomeAction.ResumeRun) },
            onAttachImage = { uri -> onAction(AgentHomeAction.ImageAttached(uri)) },
            onRemoveImage = { id -> onAction(AgentHomeAction.RemoveImage(id)) },
            onAttachFiles = { uris -> onAction(AgentHomeAction.FilesAttached(uris)) },
            onAttachFolder = { uri -> onAction(AgentHomeAction.FolderAttached(uri)) },
            onAttachFilePath = { path -> onAction(AgentHomeAction.FilePathAttached(path)) },
            onRemoveFileReference = { id -> onAction(AgentHomeAction.RemoveFileReference(id)) },
            onEditMessage = { id -> onAction(AgentHomeAction.EditMessage(id)) },
            onCancelMessageEdit = { onAction(AgentHomeAction.CancelMessageEdit) },
            searchJump = state.searchJump,
            onSearchJumpConsumed = { onAction(AgentHomeAction.SearchJumpConsumed) },
            resumableRun = state.resumableRun,
            onResumeInterruptedRun = { onAction(AgentHomeAction.ResumeInterruptedRun) },
            onDismissInterruptedRun = { onAction(AgentHomeAction.DismissInterruptedRun) },
            onDeleteMessage = { id -> onAction(AgentHomeAction.DeleteMessage(id)) },
            onRegenerateMessage = { id -> onAction(AgentHomeAction.RegenerateMessage(id)) },
            onSelectReplyCandidate = { id, index -> onAction(AgentHomeAction.SelectReplyCandidate(id, index)) },
            onForkMessage = { id -> onAction(AgentHomeAction.ForkMessage(id)) },
            onOpenNavigator = { onAction(AgentHomeAction.OpenConversationNavigator) },
            commands = commands,
            commandResult = commandResult,
            onDismissCommandResult = { onAction(AgentHomeAction.DismissCommandResult) },
            commandPalette = commandPalette,
            onPickPaletteEntry = { entry -> onAction(AgentHomeAction.PickPaletteEntry(entry.key)) },
            onCloseCommandPalette = { onAction(AgentHomeAction.CloseCommandPalette) },
            onLongPressPaletteEntry = { entry -> onAction(AgentHomeAction.ShowPaletteEntryDetail(entry.key)) },
            // 外部要往输入框写字（点了技能把 /名字 填回去）必须带上同步令牌，否则输入框不跟随。
            inputSyncVersion = state.inputSyncVersion,
            commandConfirm = state.commandConfirm,
            inputResetToken = state.inputResetToken,
            onConfirmCommand = { onAction(AgentHomeAction.ConfirmAgentCommand) },
            onDismissCommandConfirm = { onAction(AgentHomeAction.DismissAgentCommandConfirm) },
            toolAccess = toolAccess,
            pendingMessageRefs = state.pendingMessageRefs,
            onAttachReference = { onAction(AgentHomeAction.AttachMessageReference(it)) },
            onRemoveReference = { onAction(AgentHomeAction.RemoveMessageReference(it)) },
            onRefreshToolAccess = onRefreshToolAccess,
            onToggleTool = { item, enabled ->
                when (item.kind) {
                    ToolToggleKind.Mcp -> onAction(AgentHomeAction.ToggleMcpServer(item.id, enabled))
                    ToolToggleKind.Skill -> onAction(AgentHomeAction.ToggleSkill(item.id, enabled))
                }
            },
            onSuggestionClick = { prompt ->
                onAction(AgentHomeAction.SubmitMessage(prompt))
            },
            onRunTraceClick = { onAction(AgentHomeAction.ExpandRunTrace) },
            onOpenBrowser = { onAction(AgentHomeAction.OpenBrowser) },
            isDrawerOpen = isDrawerOpen,
            modifier = modifier,
        )
        }
    }
}
