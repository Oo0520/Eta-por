package io.github.mangi.eta.ui.model

import io.github.mangi.eta.data.model.ReasoningEffort

sealed interface AgentHomeAction {
    data object ResumeInterruptedRun : AgentHomeAction
    data object DismissInterruptedRun : AgentHomeAction
    data object CompactContext : AgentHomeAction
    data class ReasoningEffortChanged(val effort: ReasoningEffort) : AgentHomeAction
    data class ModelSelected(val modelId: String) : AgentHomeAction
    data class SubmitMessage(val text: String) : AgentHomeAction
    data class InterruptAndSubmitMessage(val text: String) : AgentHomeAction
    data class QueueMessage(val text: String) : AgentHomeAction
    data class SteerQueuedMessage(val id: String) : AgentHomeAction
    data class RecallQueuedMessage(val id: String, val currentDraftText: String) : AgentHomeAction
    data class RemoveQueuedMessage(val id: String) : AgentHomeAction
    data object StopRun : AgentHomeAction
    data object PauseRun : AgentHomeAction
    data object ResumeRun : AgentHomeAction
    data class ImageAttached(val uri: String) : AgentHomeAction
    data class RemoveImage(val id: String) : AgentHomeAction
    data class FilesAttached(val uris: List<String>) : AgentHomeAction
    data class FolderAttached(val uri: String) : AgentHomeAction
    data class FilePathAttached(val path: String) : AgentHomeAction
    data class RemoveFileReference(val id: String) : AgentHomeAction
    data class EditMessage(val id: String) : AgentHomeAction
    data object CancelMessageEdit : AgentHomeAction
    data object SearchJumpConsumed : AgentHomeAction
    /** 会话框上方小白条上滑：打开会话导航面板。 */
    data object OpenConversationNavigator : AgentHomeAction
    /** 命令确认卡：确认执行 / 收起。 */
    data object ConfirmAgentCommand : AgentHomeAction
    /** 面板里点了一条（命令 / 技能 / 模型），按条目 key 回查。 */
    data class PickPaletteEntry(val entryKey: String) : AgentHomeAction
    data object CloseCommandPalette : AgentHomeAction
    /** 长按面板条目：摊开完整描述。 */
    data class ShowPaletteEntryDetail(val entryKey: String) : AgentHomeAction
    data object DismissCommandResult : AgentHomeAction
    /** 工具与技能面板：切换某个 MCP 服务器 / 技能的启用状态。 */
    data class ToggleMcpServer(val serverId: String, val enabled: Boolean) : AgentHomeAction
    data class ToggleSkill(val skillId: String, val enabled: Boolean) : AgentHomeAction
    /** @ 引用：挂上 / 去掉一条历史消息。 */
    data class AttachMessageReference(val reference: MessageReferenceUi) : AgentHomeAction
    data class RemoveMessageReference(val messageId: String) : AgentHomeAction
    data object DismissAgentCommandConfirm : AgentHomeAction
    data class DeleteMessage(val id: String) : AgentHomeAction
    data class RegenerateMessage(val id: String) : AgentHomeAction
    data class SelectReplyCandidate(val id: String, val index: Int) : AgentHomeAction
    /** 以这条消息为分叉点，复制出一条新会话。 */
    data class ForkMessage(val id: String) : AgentHomeAction
    data object OpenTools : AgentHomeAction
    data object OpenSkills : AgentHomeAction
    data object OpenPermissions : AgentHomeAction
    data object OpenSystemEnhance : AgentHomeAction
    data object OpenSettings : AgentHomeAction
    data object OpenBrowser : AgentHomeAction
    data object ExpandRunTrace : AgentHomeAction
}

sealed interface PermissionHealthAction {
    data class OpenItemAction(val itemId: String) : PermissionHealthAction
    data object NavigateBack : PermissionHealthAction
}

sealed interface AgentToolsAction {
    data object OpenEnhancements : AgentToolsAction
    data object OpenPermissions : AgentToolsAction
    data object NavigateBack : AgentToolsAction
    data object OpenBrowser : AgentToolsAction
    data object OpenWebToolsSettings : AgentToolsAction
}

sealed interface AgentSkillsAction {
    data object NavigateBack : AgentSkillsAction
    data class ImportZip(val uri: String) : AgentSkillsAction
    data object ConfirmZipReplacement : AgentSkillsAction
    data object CancelZipReplacement : AgentSkillsAction
    data object DismissNotice : AgentSkillsAction
    data class ToggleSkill(val skillId: String, val enabled: Boolean) : AgentSkillsAction
    data class DeleteSkill(val skillId: String) : AgentSkillsAction
    data class ReinstallBuiltin(val skillId: String) : AgentSkillsAction
}

sealed interface AgentSystemEnhanceAction {
    data object NavigateBack : AgentSystemEnhanceAction
    data object RequestRoot : AgentSystemEnhanceAction
    data object RefreshRoot : AgentSystemEnhanceAction
}

sealed interface AgentMemoryAction {
    data object NavigateBack : AgentMemoryAction
    data class ToggleEnabled(val enabled: Boolean) : AgentMemoryAction
    data class ToggleAutoMemory(val enabled: Boolean) : AgentMemoryAction
    data class ToggleAutoSkills(val enabled: Boolean) : AgentMemoryAction
    data class ToggleAutoCompact(val enabled: Boolean) : AgentMemoryAction
    data class DraftChanged(val content: String) : AgentMemoryAction
    data object Save : AgentMemoryAction
    data object Clear : AgentMemoryAction
    data object DismissNotice : AgentMemoryAction
}
