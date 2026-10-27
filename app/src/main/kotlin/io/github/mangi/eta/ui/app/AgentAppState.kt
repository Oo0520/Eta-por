package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.model.AgentContextSnapshot

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateFormat
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.device.AgentFileReferenceGateway
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.media.AgentImageCodec
import io.github.mangi.eta.agent.model.AgentSkillCommandPromptCodec
import io.github.mangi.eta.agent.skill.SkillRuntime
import io.github.mangi.eta.agent.model.AgentFileReference
import io.github.mangi.eta.agent.model.AgentFileReferenceKind
import io.github.mangi.eta.agent.model.AgentFileReferencePolicy
import io.github.mangi.eta.agent.model.AgentFileReferencePromptCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.roleplay.RoleplayBinding
import io.github.mangi.eta.agent.roleplay.CharacterMacros
import io.github.mangi.eta.agent.roleplay.CharacterCardCodec
import io.github.mangi.eta.agent.roleplay.RoleplayMessageLink
import io.github.mangi.eta.agent.roleplay.RoleplayMessageState
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.repository.CharacterRepository
import io.github.mangi.eta.data.usage.TokenUsageRecorder
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentExecutionService
import io.github.mangi.eta.agent.runtime.AgentExternalArchivePayload
import io.github.mangi.eta.agent.runtime.AgentRunArchiveStore
import io.github.mangi.eta.agent.runtime.AgentRunCheckpointStore
import io.github.mangi.eta.agent.runtime.AgentRuntimeClient
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.agent.runtime.AgentUiHandoffPayload
import io.github.mangi.eta.agent.runtime.RunControl
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.db.ConversationCategoryEntity
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.model.enabledModel
import io.github.mangi.eta.data.repository.EtaBackupRepository
import io.github.mangi.eta.data.repository.EtaBackupSummary
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.app.AgentCommands
import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.MessageReferenceUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.AgentModelPickerProjector
import io.github.mangi.eta.ui.model.AgentModelPickerUiState
import io.github.mangi.eta.ui.model.AgentCommand
import io.github.mangi.eta.ui.model.AgentCommandInput
import io.github.mangi.eta.ui.model.AgentCommandKind
import io.github.mangi.eta.ui.model.AgentPaletteEntry
import io.github.mangi.eta.ui.model.AgentPalettePayload
import io.github.mangi.eta.ui.model.CommandPaletteUi
import io.github.mangi.eta.ui.model.CommandResultUi
import io.github.mangi.eta.ui.model.commandPaletteEntries
import io.github.mangi.eta.ui.model.CommandConfirmUi
import io.github.mangi.eta.ui.model.ConversationModeUi
import io.github.mangi.eta.ui.model.ConversationNavEntry
import io.github.mangi.eta.ui.model.ConversationPaneUiState
import io.github.mangi.eta.ui.model.ConversationSummaryUi
import io.github.mangi.eta.ui.model.MessageEditUiState
import io.github.mangi.eta.ui.model.PendingFileReferenceUi
import io.github.mangi.eta.ui.model.PendingImageUi
import io.github.mangi.eta.ui.model.QueuedMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import io.github.mangi.eta.ui.model.contentMatches
import io.github.mangi.eta.ui.model.SearchJumpUiState
import io.github.mangi.eta.ui.model.SkillItemUi
import io.github.mangi.eta.ui.model.ResumableRunUiState
import io.github.mangi.eta.ui.model.firstMatchMessageId
import io.github.mangi.eta.ui.model.navEntries
import io.github.mangi.eta.ui.model.parseAgentCommand
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

internal class AgentAppState(
    context: Context,
    private val scope: CoroutineScope,
    initialConversations: AgentConversationStore.Snapshot = AgentConversationStore.load(context),
) {
    private val appContext = context.applicationContext
    private val runConversationIds = mutableMapOf<String, String>()
    private val runMessageProjector = AgentRunMessageProjector()
    private val runEventCoalescer = AgentRunEventCoalescer()
    private val runEventFlushJobs = mutableMapOf<String, Job>()
    private var currentRunId: String? = null
    private var currentRunJob: Job? = null

    /** 同时运行的会话上限，与 Runtime 侧保持一致。 */
    private val maxConcurrentRuns = 3

    /** 进行中的 run 记在 runConversationIds 里，用它当并发计数。 */
    private fun hasRunCapacity(): Boolean = runConversationIds.size < maxConcurrentRuns
    /** 最近一次请求被裁掉的轮次/条数：只用于顶栏提示，界面与数据库都不删。 */
    var historyTrimNotice by mutableStateOf<Pair<Int, Int>?>(null)
        private set

    /**
     * 斜杠命令表：内置命令 + 已启用技能展开出的技能命令。
     *
     * 界面层在技能开关变化时回填，保证「面板里看到的命令」与「能被解析执行的命令」是同一份。
     */
    var agentCommands by mutableStateOf(AgentCommands.builtIns)
        private set

    fun updateSkillCatalog(skills: List<SkillItemUi>) {
        agentCommands = AgentCommands.withSkills(skills)
    }

    /** 命令面板的主动展开（/help、/skills、/model）；null 表示只按输入前缀显示。 */
    var commandPalette by mutableStateOf<CommandPaletteUi?>(null)
        private set
    private val persistenceLock = Any()
    private var persistenceJob: Job? = null
    // 导入期间暂停保存：旧状态的增量保存会删除刚导入的会话；导入成功后整体重载，失败时数据库已回滚。
    private var persistencePaused = false
    private val runtimeRecoveryInProgress = AtomicBoolean(false)
    private val defaultThinkingEnabled = agentBooleanForUi(Prefs.Keys.AGENT_THINKING_ENABLED)
    /** 导入备份期间串行化会话加载，避免与保存/重载交叉。 */
    private val conversationLoadMutex = Mutex()

    @Volatile
    private var conversationPersistence = AgentConversationPersistence(initialConversations)
    /** 新建会话待落库的分类（会话在首次发送时才真正生成）。 */
    private var pendingConversationCategory: String? = null
    private var currentReasoningCapabilities: ModelReasoningCapabilities? = null
    private var providers: List<ProviderSetting> = emptyList()
    private var defaultModelId: String? = null
    private var defaultProviderId: String? = null
    private var fileAttachmentOwnerVersion = 0L

    private var selectedConversationId: String? = initialConversations.selectedConversationId
    private var conversationsById: Map<String, AgentChatHomeUiState> = initialConversations.conversationsById

    /**
     * 只加载了元数据的会话（#006 内存修复）：启动不再把全库历史读进堆，
     * 用户切过去时由 [ensureConversationLoaded] 按需补载。
     */
    private var partiallyLoadedConversationIds: MutableSet<String> =
        initialConversations.partiallyLoadedConversationIds.toMutableSet()

    /** 会话列表预览：占位会话没有 messages，用它兜住摘要。 */
    private var conversationPreviews: Map<String, String> = initialConversations.previews

    /** 每个会话各自的补载任务；发送前要等目标会话落地，避免用空 history 请求模型。 */
    private val conversationLoadJobs = mutableMapOf<String, Job>()

    /** 最近打开过的会话 id（最新在前）：侧栏顶部那一栏用，存进设置，重启后还在。 */
    /** 内部最多记 8 条，侧栏只显示 3 条，多的折叠。 */
    private val recentOpenedLimit = 8

    private var recentOpenedIds: List<String> = loadRecentOpenedIds()

    private fun loadRecentOpenedIds(): List<String> = runCatching {
        runBlocking { SettingsDataStore.settings().recentOpenedConversationIds }
    }.getOrDefault("")
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .take(recentOpenedLimit)

    private fun persistRecentOpened(ids: List<String>) {
        runCatching {
            runBlocking {
                SettingsDataStore.updateSettings {
                    it.copy(recentOpenedConversationIds = ids.joinToString(","))
                }
            }
        }
    }

    private fun noteRecentOpened(conversationId: String) {
        val updated = (listOf(conversationId) + recentOpenedIds.filterNot { it == conversationId })
            .take(recentOpenedLimit)
        recentOpenedIds = updated
        conversationPaneState = conversationPaneState.copy(recentOpenedIds = updated)
        persistRecentOpened(updated)
    }

    private var conversationTitles: Map<String, String> = initialConversations.titles
    private var conversationUpdatedAt: Map<String, Long> = initialConversations.updatedAt
    private var conversationPinned: Map<String, Boolean> = initialConversations.pinned

    /** 用户自建的会话分类；数据库为唯一真源。 */
    var conversationCategories by mutableStateOf(initialConversations.categories)
        private set

    /** 会话归属分类（id → categoryId）。 */
    var conversationCategoryIds by mutableStateOf(initialConversations.categoryIds)
        private set

    var homeState by mutableStateOf(
        selectedConversationId?.let(conversationsById::get) ?: emptyChatState(defaultThinkingEnabled)
    )
        private set

    var modelPickerState by mutableStateOf(AgentModelPickerUiState())
        private set

    var conversationPaneState by mutableStateOf(
        ConversationPaneUiState(
            conversations = emptyList(),
            selectedConversationId = selectedConversationId,
            searchQuery = "",
        )
    )
        private set

    /**
     * 执行侧真源的镜像。按「当前会话的 run 是否暂停」派生：
     * 并发时别的会话暂停不会让这个会话的按钮变成播放图标。
     */
    private var runControlState by mutableStateOf(RunControl.state.value)

    val runPaused: Boolean
        get() = runControlState.pausedForConversation(selectedConversationId)

    /** 当前会话对应的 runId；输入框的暂停／恢复／停止都按它定位。 */
    private fun runIdForSelectedConversation(): String? =
        runConversationIds.entries.firstOrNull { it.value == selectedConversationId }?.key

    init {
        RunControl.observe { state -> runControlState = state }
        refreshConversationSummaries()
        observeRuntimeSelection()
        runtimeRecoveryInProgress.set(true)
        scope.launch(Dispatchers.IO) {
            try {
                recoverRuntimeRuns()
                importArchivedExternalRuns()
            } finally {
                runtimeRecoveryInProgress.set(false)
            }
        }
    }

    private fun observeRuntimeSelection() {
        scope.launch(Dispatchers.IO) {
            combine(
                RuntimeConfigRepository.selectedProviderIdFlow(),
                RuntimeConfigRepository.selectedModelIdFlow(),
                ProviderRepository.providersFlow(),
            ) { providerId, modelId, providers ->
                Triple(providerId, modelId, providers)
            }
                .distinctUntilChanged()
                .collectLatest { (providerId, modelId, providers) ->
                    withContext(Dispatchers.Main) {
                        this@AgentAppState.providers = providers
                        defaultProviderId = providerId
                        defaultModelId = modelId
                        refreshConversationModel()
                    }
                }
        }
    }

    /**
     * 选择器、思考强度与上下文窗口统一投影自当前会话的有效模型：
     * 会话绑定的模型仍可用时用它，否则用默认模型。回落只影响显示与发送，不改写会话绑定，
     * 模型重新启用后会话自动回到原模型；真正发送后才把实际模型写入绑定。
     */
    private fun refreshConversationModel() {
        val bound = providers.enabledModel(homeState.modelId)
        val pickerState = if (bound != null) {
            AgentModelPickerProjector.project(providers, bound.provider.id, bound.model.id)
        } else {
            AgentModelPickerProjector.project(providers, defaultProviderId, defaultModelId)
        }
        modelPickerState = pickerState.copy(isChanging = modelPickerState.isChanging)
        val effective = bound ?: providers.enabledModel(pickerState.selectedModel?.id)
        currentReasoningCapabilities = effective?.let { (provider, model) ->
            RuntimeConfigRepository.reasoningCapabilities(provider, model)
        }
        val resolved = homeState.withCurrentReasoningCapabilities()
        val conversationId = selectedConversationId
        if (conversationId == null) homeState = resolved
        else updateConversation(conversationId, resolved, updateTimestamp = false)
    }

    private fun AgentChatHomeUiState.withCurrentReasoningCapabilities(): AgentChatHomeUiState {
        val normalized = currentReasoningCapabilities?.normalize(reasoningEffort) ?: ReasoningEffort.OFF
        return copy(
            thinkingEnabled = normalized.enablesReasoning,
            reasoningEffort = normalized,
            availableReasoningEfforts = currentReasoningCapabilities?.selectableEfforts.orEmpty(),
        )
    }

    private val AgentChatHomeUiState.effectiveModelId: String?
        get() = providers.enabledModel(modelId)?.model?.id ?: modelPickerState.selectedModel?.id

    fun refreshRuntimeResults() {
        if (!runtimeRecoveryInProgress.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            try {
                recoverRuntimeRuns()
                importArchivedExternalRuns()
            } finally {
                runtimeRecoveryInProgress.set(false)
            }
        }
    }

    suspend fun exportBackup(output: OutputStream): EtaBackupSummary =
        EtaBackupRepository.export(appContext, output)

    suspend fun importBackup(input: InputStream): EtaBackupSummary {
        val locallyBusy = withContext(Dispatchers.Main.immediate) {
            currentRunId != null || conversationsById.values.any { it.isStreaming }
        }
        if (locallyBusy) {
            throw IllegalStateException("请先停止正在运行的 Agent 任务")
        }

        val activeRunQuery = withContext(Dispatchers.IO) {
            AgentRuntimeClient(appContext, AndroidAgentLogger).queryActiveRun()
        }
        when (val active = activeRunQuery) {
            is AgentRuntimeClient.ActiveRunQuery.Known -> {
                if (active.runId != null) {
                    throw IllegalStateException("请先停止正在运行的 Agent 任务")
                }
            }
            AgentRuntimeClient.ActiveRunQuery.Unavailable -> {
                throw IllegalStateException("无法确认 Agent Runtime 状态，请稍后重试")
            }
        }

        val pendingPersistence = synchronized(persistenceLock) {
            check(!persistencePaused) { "备份正在导入" }
            persistencePaused = true
            persistenceJob
        }
        var imported = false
        var reloaded = false
        try {
            pendingPersistence?.join()
            return conversationLoadMutex.withLock {
                try {
                    val summary = EtaBackupRepository.import(appContext, input, onCommitted = { imported = true })
                    reloadConversationsAfterBackup()
                    reloaded = true
                    summary
                } catch (failure: Throwable) {
                    if (imported && !reloaded) {
                        try {
                            withContext(NonCancellable) { reloadConversationsAfterBackup() }
                            reloaded = true
                        } catch (reloadFailure: Throwable) {
                            failure.addSuppressed(reloadFailure)
                        }
                    }
                    throw failure
                }
            }
        } finally {
            // 导入已提交但重载失败时内存仍是旧状态，继续暂停，避免覆盖导入结果；重启后按库内数据加载。
            if (!imported || reloaded) {
                synchronized(persistenceLock) { persistencePaused = false }
            }
            // 仅提交前失败才回写旧内存；提交后的清理失败或取消仍以导入数据为准。
            if (!imported) withContext(Dispatchers.Main.immediate + NonCancellable) { persistConversations() }
        }
    }

    private suspend fun reloadConversationsAfterBackup() {
        val snapshot = withContext(Dispatchers.IO) {
            AgentConversationStore.load(appContext)
        }
        withContext(Dispatchers.Main.immediate) {
            selectedConversationId = snapshot.selectedConversationId
            conversationsById = snapshot.conversationsById
            partiallyLoadedConversationIds = snapshot.partiallyLoadedConversationIds.toMutableSet()
            conversationPreviews = snapshot.previews
            conversationTitles = snapshot.titles
            conversationUpdatedAt = snapshot.updatedAt
            conversationPinned = snapshot.pinned
            conversationCategories = snapshot.categories
            conversationCategoryIds = snapshot.categoryIds
            conversationPersistence = AgentConversationPersistence(snapshot)
            fileAttachmentOwnerVersion += 1
            homeState = selectedConversationId
                ?.let(conversationsById::get)
                ?: emptyChatState(defaultThinkingEnabled)
            refreshConversationModel()
            conversationPaneState = conversationPaneState.copy(
            recentOpenedIds = recentOpenedIds,
                selectedConversationId = selectedConversationId,
                searchQuery = "",
            )
            refreshConversationSummaries()
        }
    }

    /** 用 checkpoint、终态 outbox 与 active session 一次性对账，避免用进程存活推断 run 状态。 */
    private suspend fun recoverRuntimeRuns() {
        val client = AgentRuntimeClient(appContext, AndroidAgentLogger)
        val checkpoints = withContext(Dispatchers.IO) {
            AgentRunCheckpointStore.list(appContext)
        }
        val initialCompletedQuery = client.queryCompletedRuns()
        if (initialCompletedQuery is AgentRuntimeClient.CompletedRunsQuery.Unavailable) {
            AndroidAgentLogger.warnThrottled("agent_ui_drain_results_failed") {
                "Agent UI pending result recovery failed"
            }
        }
        val initialCompletedRuns =
            (initialCompletedQuery as? AgentRuntimeClient.CompletedRunsQuery.Known)
                ?.runs
                .orEmpty()
        val activeRunQuery = client.queryActiveRun()
        val terminalRaceQuery = if (
            activeRunQuery is AgentRuntimeClient.ActiveRunQuery.Known && checkpoints.isNotEmpty()
        ) {
            client.queryCompletedRuns()
        } else {
            initialCompletedQuery
        }
        val terminalRaceCompletedRuns =
            (terminalRaceQuery as? AgentRuntimeClient.CompletedRunsQuery.Known)
                ?.runs
                .orEmpty()
        val completedRuns = (initialCompletedRuns + terminalRaceCompletedRuns)
            .associateBy { completed ->
                completed.result.runId.ifBlank { completed.handoff.id }
            }
            .values
            .toList()
        val activeStateKnown = activeRunQuery is AgentRuntimeClient.ActiveRunQuery.Known
        val terminalStateKnown = terminalRaceQuery is AgentRuntimeClient.CompletedRunsQuery.Known
        val activeRunId = (activeRunQuery as? AgentRuntimeClient.ActiveRunQuery.Known)?.runId
        val activeRunIds =
            (activeRunQuery as? AgentRuntimeClient.ActiveRunQuery.Known)?.runIds.orEmpty()
        val locallyObservedRunId = withContext(Dispatchers.Main) { currentRunId }
        val plan = AgentRunRecoveryCoordinator.plan(
            checkpoints = checkpoints,
            completedRuns = completedRuns,
            activeStateKnown = activeStateKnown,
            terminalStateKnown = terminalStateKnown,
            activeRunId = activeRunId,
            locallyObservedRunId = locallyObservedRunId,
            activeRunIds = activeRunIds,
        )
        val orphanRewrites = if (activeStateKnown && terminalStateKnown) withContext(Dispatchers.Main) {
            val observed = checkpoints.mapTo(mutableSetOf()) { it.runId }.apply {
                addAll(completedRuns.map { it.result.runId })
                activeRunId?.let(::add)
                addAll(activeRunIds)
                currentRunId?.let(::add)
            }
            conversationsById.flatMap { (id, state) ->
                state.roleplayMessages.pendingRewrites.keys.filterNot { it in observed }.map { id to it }
            }
        } else emptyList()
        if (
            plan.completed.isEmpty() &&
            plan.interrupted.isEmpty() &&
            plan.reattach == null && orphanRewrites.isEmpty()
        ) {
            return
        }

        // 轨迹要落到具体会话上：先把这些会话的历史补齐，别往占位状态里写（#006）。
        buildSet {
            plan.completed.forEach { add(AgentUiHandoffPayload.from(it.result.handoff.payload).conversationId) }
            plan.interrupted.forEach { add(AgentUiHandoffPayload.from(it.handoff.payload).conversationId) }
            orphanRewrites.forEach { add(it.first) }
        }.filter(String::isNotBlank).forEach { awaitConversationLoaded(it) }

        val acknowledgeAfterSave = mutableListOf<String>()
        val removeAfterSave = mutableListOf<String>()
        val changed = withContext(Dispatchers.Main) {
            var stateChanged = false
            plan.completed.forEach { recoveryPlan ->
                val completedRun = recoveryPlan.result
                val runId = completedRun.result.runId.ifBlank { completedRun.handoff.id }
                val payload = AgentUiHandoffPayload.from(completedRun.handoff.payload)
                val conversationId = payload.conversationId
                val state = conversationsById[conversationId] ?: return@forEach
                recoveryPlan.checkpoint?.let { checkpoint ->
                    stateChanged = restoreCheckpointTrace(
                        checkpoint = checkpoint,
                        interrupted = false,
                    ) || stateChanged
                }
                val result = completedRun.result
                val recovery = AgentPendingResultRecovery.apply(
                    state = conversationsById[conversationId] ?: state,
                    runId = runId,
                    result = result,
                    promptSupplement = payload.promptSupplement,
                    supplements = payload.supplements,
                )
                if (recovery.alreadyApplied) {
                    acknowledgeAfterSave += runId
                    return@forEach
                }
                updateConversation(conversationId, recovery.state)
                acknowledgeAfterSave += runId
                stateChanged = true
            }

            plan.interrupted.forEach { checkpoint ->
                removeAfterSave += checkpoint.runId
                stateChanged = restoreCheckpointTrace(
                    checkpoint = checkpoint,
                    interrupted = true,
                ) || stateChanged
            }
            orphanRewrites.forEach { (conversationId, runId) ->
                conversationsById[conversationId]?.let { state ->
                    updateConversation(conversationId, RoleplayConversationReducer.applyRewrite(state, runId,
                        AgentRuntimeWire.RunResult(runId, false, "", "重新生成已中断，原回复已保留。",
                            operation = AgentRuntimeWire.OP_REWRITE_REPLY)))
                    stateChanged = true
                }
            }
            if (stateChanged) refreshConversationSummaries()
            stateChanged || acknowledgeAfterSave.isNotEmpty() || removeAfterSave.isNotEmpty()
        }

        if (changed) {
            val saved = withContext(Dispatchers.Main) { persistConversations() }.await()
            if (saved) {
                acknowledgeAfterSave.forEach(client::ackResult)
                removeAfterSave.forEach { runId ->
                    AgentRunCheckpointStore.remove(appContext, runId)
                }
            }
        }

        plan.reattach?.let { checkpoint ->
            withContext(Dispatchers.Main) { startReattachedRun(checkpoint) }
        }
    }

    /** 把安全事件恢复为 UI 轨迹；半截回复不进入模型 history，设备工具也不会重放。 */
    private fun restoreCheckpointTrace(
        checkpoint: AgentRunCheckpointStore.Checkpoint,
        interrupted: Boolean,
    ): Boolean {
        val runId = checkpoint.runId
        if (runId.isBlank()) return false
        val conversationId = AgentUiHandoffPayload
            .from(checkpoint.handoff.payload)
            .conversationId
        val existing = conversationsById[conversationId] ?: return false
        if (AgentRuntimeHistoryReducer.wasApplied(existing, runId)) return false
        if (checkpoint.operation == AgentRuntimeWire.OP_REWRITE_REPLY) {
            val restored = RoleplayConversationReducer.restorePendingRewrite(existing, runId, checkpoint.rewriteTargetMessageId)
            if (interrupted) updateConversation(conversationId, RoleplayConversationReducer.applyRewrite(
                restored, runId, AgentRuntimeWire.RunResult(runId, false, "", "重新生成已中断，原回复已保留。",
                    operation = AgentRuntimeWire.OP_REWRITE_REPLY, rewriteTargetMessageId = checkpoint.rewriteTargetMessageId),
            )) else updateConversation(conversationId, restored)
            return interrupted || restored != existing
        }

        runConversationIds[runId] = conversationId
        updateConversation(conversationId, existing.copy(isStreaming = true, isCompacting = checkpoint.operation == AgentRuntimeWire.OP_COMPACT))
        restoreRunEvents(runId, checkpoint.events)
        flushPendingRunDelta(runId)
        updateRunTrace(runId) { messages ->
            val finalizedThinking = runMessageProjector.finalizeThinking(runId, messages)
            val finalizedText = runMessageProjector.finalizeText(runId, finalizedThinking)
            if (interrupted) {
                val interruptedTools = runMessageProjector.interruptRunningTools(
                    reason = appContext.getString(R.string.system_notice_interrupted),
                    messages = runMessageProjector.finishContextCompaction(runId, finalizedText, "上下文压缩已中断"),
                )
                val noticeId = "interrupted-$runId"
                if (interruptedTools.any { it.id == noticeId }) {
                    interruptedTools
                } else {
                    interruptedTools + SystemNoticeMessageUi(
                        id = noticeId,
                        code = SystemNoticeCode.Interrupted,
                    )
                }
            } else {
                runMessageProjector.finalizeRun(runId, finalizedText)
            }
        }
        if (interrupted && (checkpoint.contextSnapshot != null || checkpoint.transcript.isNotEmpty())) {
            applyConversationHistoryResult(runId, checkpoint.transcript, checkpoint.contextSnapshot, true)
        }
        // 只对普通对话回合提供中断续跑：改写回复与上下文压缩没有可接着跑的剩余步骤。
        if (interrupted && checkpoint.operation == AgentRuntimeWire.OP_CHAT) {
            conversationsById[conversationId]?.let { state ->
                updateConversation(
                    conversationId,
                    state.copy(
                        resumableRun = ResumableRunUiState(
                            runId = runId,
                            interruptedAt = checkpoint.updatedAt,
                            summary = io.github.mangi.eta.ui.model.resumableRunSummary(
                                checkpoint.transcript,
                            ),
                        ),
                    ),
                )
            }
        }
        conversationsById[conversationId]?.let { state ->
            updateConversation(conversationId, RoleplayConversationReducer.linkRun(state, runId))
        }
        setConversationStreaming(runId, false)
        runMessageProjector.clearRun(runId)
        runConversationIds.remove(runId)
        conversationUpdatedAt = conversationUpdatedAt +
            (conversationId to checkpoint.updatedAt)
        return true
    }

    private fun startReattachedRun(checkpoint: AgentRunCheckpointStore.Checkpoint) {
        val runId = checkpoint.runId
        val conversationId = AgentUiHandoffPayload
            .from(checkpoint.handoff.payload)
            .conversationId
        val existing = conversationsById[conversationId] ?: return
        if (!hasRunCapacity() || AgentRuntimeHistoryReducer.wasApplied(existing, runId)) return

        runConversationIds[runId] = conversationId
        currentRunId = runId
        val restored = if (checkpoint.operation == AgentRuntimeWire.OP_REWRITE_REPLY) {
            RoleplayConversationReducer.restorePendingRewrite(existing, runId, checkpoint.rewriteTargetMessageId)
        } else existing
        updateConversation(conversationId, restored.copy(isStreaming = true, isCompacting = checkpoint.operation == AgentRuntimeWire.OP_COMPACT))
        refreshConversationSummaries()
        currentRunJob = scope.launch(Dispatchers.IO) {
            val client = AgentRuntimeClient(appContext, AndroidAgentLogger)
            val outcome = client.attachRun(
                runId = runId,
                onReplay = { events -> restoreRunEvents(runId, events) },
                onEvent = { event -> enqueueRunEvent(runId, event) },
            )
            when (outcome) {
                is AgentRuntimeClient.AttachOutcome.Completed -> withContext(Dispatchers.Main) {
                    applyRunResult(
                        runId = runId,
                        result = outcome.result,
                        acknowledgeRuntimeResult = true,
                    )
                }
                AgentRuntimeClient.AttachOutcome.NotActive -> {
                    withContext(Dispatchers.Main) {
                        if (currentRunId == runId) {
                            currentRunId = null
                            currentRunJob = null
                            setConversationStreaming(runId, false)
                        }
                    }
                    recoverRuntimeRuns()
                }
                AgentRuntimeClient.AttachOutcome.Unavailable -> withContext(Dispatchers.Main) {
                    if (currentRunId == runId) {
                        currentRunId = null
                        currentRunJob = null
                        setConversationStreaming(runId, false)
                        refreshConversationSummaries()
                    }
                }
            }
        }
    }

    private suspend fun importArchivedExternalRuns() {
        val archivedRuns = withContext(Dispatchers.IO) {
            AgentRunArchiveStore.list(appContext)
                .filter { AgentExternalArchivePayload.from(it.handoff.payload) != null }
        }
        if (archivedRuns.isEmpty()) return

        withContext(Dispatchers.Main) {
            val importedRunIds = archivedRuns.mapNotNull { archivedRun ->
                importExternalRun(archivedRun)
            }
            refreshConversationSummaries()
            persistConversations {
                importedRunIds.forEach { runId ->
                    AgentRunArchiveStore.remove(appContext, runId)
                }
            }
        }
    }

    suspend fun openAssistantConversation(
        conversationKey: String,
        source: String = AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE,
    ): Boolean = openArchivedConversation(source, conversationKey)

    /** 打开外部入口按 [source] 与 [conversationKey] 归档的会话；先导入尚未入库的归档结果。 */
    suspend fun openArchivedConversation(source: String, conversationKey: String): Boolean {
        if (source.isBlank() || conversationKey.isBlank()) return false
        if (source != AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE) importArchivedExternalRuns()
        return withContext(Dispatchers.Main.immediate) {
            val conversationId = if (source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE) {
                conversationKey
            } else {
                archiveConversationId(source = source, conversationKey = conversationKey)
            }
            if (conversationsById[conversationId] == null) {
                false
            } else {
                selectConversation(conversationId)
                true
            }
        }
    }

    private fun importExternalRun(archivedRun: AgentRunArchiveStore.ArchivedRun): String? {
        val runId = archivedRun.result.runId.ifBlank { archivedRun.handoff.id }
        if (runId.isBlank()) return null
        val payload = AgentExternalArchivePayload.from(archivedRun.handoff.payload) ?: return null
        val conversationId = archiveConversationId(
            source = archivedRun.handoff.source,
            conversationKey = payload.conversationKey,
        )
        val archivedEffort = payload.reasoningEffort
            ?: payload.thinkingEnabled?.let(ReasoningEffort::fromLegacy)
            ?: ReasoningEffort.fromLegacy(defaultThinkingEnabled)
        val existingState = conversationsById[conversationId] ?: emptyChatState(
            archivedEffort.enablesReasoning
        ).copy(reasoningEffort = archivedEffort)
        val alreadyImported = AgentRuntimeHistoryReducer.wasApplied(existingState, runId) ||
            existingState.messages.any {
                it is AgentMessageUi &&
                    (it.id == "assistant-$runId" || it.id.startsWith("assistant-$runId-")) &&
                    !it.isStreaming
            }
        if (alreadyImported) return runId

        if (conversationTitles[conversationId].isNullOrBlank()) {
            conversationTitles = conversationTitles + (conversationId to payload.title)
        }
        runConversationIds[runId] = conversationId
        updateConversation(
            conversationId,
            ExternalConversationHistory.appendUser(existingState, runId, payload.userText).copy(
                input = "",
                isStreaming = true,
                thinkingEnabled = archivedEffort.enablesReasoning,
                reasoningEffort = archivedEffort,
                pendingImages = emptyList(),
                messages = existingState.messages +
                    UserMessageUi(
                        id = "user-$runId",
                        content = payload.userText,
                        images = archivedRun.userImagePreviews,
                    ) +
                    AgentMessageUi(
                        id = "assistant-$runId",
                        content = "",
                        isStreaming = true,
                        renderMarkdown = false,
                    ),
            )
        )
        archivedRun.events.forEach { event -> applyRunEvent(runId, event) }
        applyRunResult(runId, archivedRun.result)
        conversationUpdatedAt = conversationUpdatedAt + (conversationId to archivedRun.createdAt)
        return runId
    }

    fun updateThinkingEnabled(enabled: Boolean) {
        updateReasoningEffort(ReasoningEffort.fromLegacy(enabled))
    }

    fun updateReasoningEffort(effort: ReasoningEffort) {
        val normalized = currentReasoningCapabilities?.normalize(effort) ?: ReasoningEffort.OFF
        updateCurrentConversation(
            homeState.copy(
                thinkingEnabled = normalized.enablesReasoning,
                reasoningEffort = normalized,
            )
        )
        if (selectedConversationId != null) persistConversations()
    }

    /** 聊天内切换同时改当前会话绑定与默认模型；默认模型供新会话和系统助手入口使用。 */
    fun selectModel(modelId: String) {
        if (
            homeState.isStreaming ||
            modelPickerState.isChanging ||
            modelPickerState.selectedModel?.id == modelId
        ) {
            return
        }
        updateCurrentConversation(homeState.copy(modelId = modelId))
        refreshConversationModel()
        if (selectedConversationId != null) persistConversations()
        modelPickerState = modelPickerState.copy(isChanging = true)
        scope.launch(Dispatchers.IO) {
            try {
                RuntimeConfigRepository.setSelectedModelId(modelId)
                RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, appContext.getString(R.string.state_ui_model_switching_failed_please_try_again_later_4af439), Toast.LENGTH_SHORT).show()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    modelPickerState = modelPickerState.copy(isChanging = false)
                }
            }
        }
    }

    fun updateSearchQuery(query: String) {
        conversationPaneState = conversationPaneState.copy(
            recentOpenedIds = recentOpenedIds,searchQuery = query)
        refreshConversationSummaries()
    }

    /** 搜索跳转令牌：让同一条命中消息也能被重复定位。 */
    private var searchJumpToken = 0L

    /** 搜索跳转已被界面消费（已滚到命中消息）：立即清空，避免重组时反复跳回。 */
    fun consumeSearchJump() {
        if (homeState.searchJump != null) homeState = homeState.copy(searchJump = null)
    }

    /**
     * 执行内置命令。
     *
     * 返回 true 表示这条输入已经被命令消费，不再走模型；false 交给上层当普通文本处理。
     * 带副作用的命令第一次调用只弹确认卡，确认后用 confirmed = true 再进来一次。
     */
    fun runAgentCommand(command: AgentCommand, args: String, confirmed: Boolean = false): Boolean {
        if (command.requiresConfirmation && !confirmed) {
            val usage = if (args.isBlank()) "/" + command.name else "/" + command.name + " " + args
            homeState = homeState.copy(
                commandConfirm = CommandConfirmUi(
                    title = appContext.getString(R.string.agent_command_confirm_title, usage),
                    detail = command.summary,
                    commandName = command.name,
                    payload = usage,
                ),
            )
            return true
        }
        return when (command.name) {
            AgentCommands.NEW -> {
                createConversation()
                true
            }

            AgentCommands.COMPACT -> {
                compactCurrentContext()
                true
            }

            AgentCommands.MODEL -> {
                resolveModelCommand(args)
                true
            }

            AgentCommands.EFFORT -> {
                resolveEffortCommand(args)
                true
            }

            AgentCommands.STOP -> {
                stopCurrentRun()
                true
            }

            AgentCommands.PAUSE -> {
                pauseCurrentRun()
                true
            }

            AgentCommands.RESUME -> {
                resumeCurrentRun()
                true
            }

            AgentCommands.RENAME -> {
                val name = args.trim()
                val target = selectedConversationId
                if (name.isBlank()) {
                    showCommandResult(
                        CommandResultUi(
                            title = appContext.getString(R.string.agent_command_rename_usage),
                            isError = true,
                        ),
                    )
                } else if (target != null) {
                    renameConversation(target, name)
                    showCommandResult(
                        CommandResultUi(appContext.getString(R.string.agent_command_rename_done, name)),
                    )
                }
                true
            }

            AgentCommands.STATUS -> {
                showCommandResult(statusResult())
                true
            }

            AgentCommands.SKILLS -> {
                openSkillsPalette()
                true
            }

            AgentCommands.HELP -> {
                // /help 不再弹一句话：直接把面板展开成全量清单，可滚动、可点选。
                openHelpPalette()
                true
            }

            else -> if (command.kind == AgentCommandKind.Skill) runSkillCommand(command, args) else false
        }
    }

    /** 命令结果卡：多行、停留到用户手动关掉，不再用一闪而过的 toast。 */
    fun showCommandResult(result: CommandResultUi) {
        updateCurrentConversation(homeState.copy(commandResult = result), updateTimestamp = false)
    }

    fun dismissCommandResult() {
        if (homeState.commandResult == null) return
        updateCurrentConversation(homeState.copy(commandResult = null), updateTimestamp = false)
    }

    /** 面板主动展开时点选条目：模型直接切，命令按既有规则执行或补全。 */
    fun runPaletteEntry(entryKey: String) {
        // 前缀面板的条目没有进 commandPalette，按 key 现查一次。
        val entry = commandPalette?.entries?.firstOrNull { it.key == entryKey }
            ?: entryFromKey(entryKey)
            ?: return
        when (val payload = entry.payload) {
            is AgentPalettePayload.SwitchModel -> {
                commandPalette = null
                val target = modelPickerState.providerGroups
                    .flatMap { it.models }
                    .firstOrNull { it.id == payload.modelId }
                if (homeState.isStreaming || modelPickerState.isChanging) {
                    showCommandResult(
                        CommandResultUi(
                            appContext.getString(R.string.agent_command_model_busy),
                            isError = true,
                        ),
                    )
                } else {
                    selectModel(payload.modelId)
                    showCommandResult(
                        CommandResultUi(
                            appContext.getString(
                                R.string.agent_command_model_switched,
                                target?.displayName ?: payload.modelId,
                            ),
                        ),
                    )
                }
            }

            is AgentPalettePayload.Run -> {
                commandPalette = null
                val command = payload.command
                if (command.kind == AgentCommandKind.Skill || command.argHint != null) {
                    // 需要补参数：把 `/名字 ` 写回输入框并聚焦，用户接着补一句就能发。
                    updateCurrentConversation(
                        homeState.copy(
                            input = "/" + command.name + " ",
                            inputSyncVersion = homeState.inputSyncVersion + 1,
                        ),
                        updateTimestamp = false,
                    )
                } else {
                    val consumed = runAgentCommand(command, "", confirmed = true)
                    if (consumed) {
                        updateCurrentConversation(
                            homeState.copy(input = "", inputResetToken = homeState.inputResetToken + 1),
                            updateTimestamp = false,
                        )
                    }
                }
            }
        }
    }

    fun closeCommandPalette() {
        commandPalette = null
    }

    /** 长按面板条目：列表里只显示两行描述，完整说明摊到结果卡里看。 */
    fun showPaletteEntryDetail(entryKey: String) {
        val entry = commandPalette?.entries?.firstOrNull { it.key == entryKey }
            ?: entryFromKey(entryKey)
            ?: return
        showCommandResult(
            CommandResultUi(
                title = entry.title,
                lines = entry.subtitle.split("\n").filter { it.isNotBlank() },
            ),
        )
    }

    /** 按条目 key 现查：`cmd:名字` 查命令表，`model:id` 查模型清单。 */
    private fun entryFromKey(key: String): AgentPaletteEntry? = when {
        key.startsWith("cmd:") -> agentCommands
            .firstOrNull { it.name == key.removePrefix("cmd:") }
            ?.let { command -> commandPaletteEntries(listOf(command)).firstOrNull() }
        key.startsWith("model:") -> modelPalette()?.entries?.firstOrNull { it.key == key }
        else -> null
    }

    private fun openHelpPalette() {
        val builtInSection = appContext.getString(R.string.agent_palette_section_builtin)
        val skillSection = appContext.getString(R.string.agent_palette_section_skills)
        val builtIns = agentCommands.filter { it.kind == AgentCommandKind.BuiltIn }
        val skills = agentCommands.filter { it.kind != AgentCommandKind.BuiltIn }
        val entries = commandPaletteEntries(builtIns).map { it.copy(section = builtInSection) } +
            commandPaletteEntries(skills).map { it.copy(section = skillSection) }
        commandPalette = CommandPaletteUi(
            title = appContext.getString(R.string.agent_palette_help_title),
            entries = entries,
        )
    }

    private fun openSkillsPalette() {
        val skills = agentCommands.filter { it.kind == AgentCommandKind.Skill }
        if (skills.isEmpty()) {
            showCommandResult(
                CommandResultUi(appContext.getString(R.string.agent_command_skills_empty), isError = true),
            )
            commandPalette = null
            return
        }
        commandPalette = CommandPaletteUi(
            title = appContext.getString(R.string.agent_palette_skills_title),
            entries = commandPaletteEntries(skills),
        )
    }

    /** 模型清单；没有可用模型时返回 null，由调用方给结果卡。 */
    private fun modelPalette(): CommandPaletteUi? {
        val selectedId = modelPickerState.selectedModel?.id
        val entries = modelPickerState.providerGroups.flatMap { group ->
            group.models.map { model ->
                AgentPaletteEntry(
                    key = "model:" + model.id,
                    title = model.displayName,
                    subtitle = model.modelId,
                    payload = AgentPalettePayload.SwitchModel(model.id),
                    section = group.providerName,
                    current = model.id == selectedId,
                )
            }
        }
        if (entries.isEmpty()) return null
        return CommandPaletteUi(
            title = appContext.getString(R.string.agent_palette_models_title),
            entries = entries,
        )
    }

    /** 技能调用留痕：消息流里的居中胶囊，不参与模型上下文。 */
    private fun appendCommandNote(text: String) {
        val notice = SystemNoticeMessageUi(
            id = "command-note-" + UUID.randomUUID(),
            code = SystemNoticeCode.CommandNote,
            detail = text,
        )
        updateCurrentConversation(
            homeState.copy(messages = homeState.messages + notice),
            updateTimestamp = false,
        )
    }

    /**
     * 技能命令：把 SKILL.md 正文内联成技能块，带着任务描述发出去。
     *
     * 正文在 IO 线程读取，回到主线程发送；返回 true 表示这条输入已被命令消费。
     * 没带任务描述时只提示一句、不发送，草稿由调用方保留。
     */
    private fun runSkillCommand(command: AgentCommand, args: String): Boolean {
        val skillId = command.skillId ?: return false
        val task = args.trim()
        if (task.isEmpty()) {
            showCommandResult(
                CommandResultUi(
                    title = appContext.getString(R.string.agent_command_skill_need_task, command.name),
                    isError = true,
                ),
            )
            return true
        }
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    // 显式调用不看开关：技能关了也能用，只是不会自动出现在提示词索引里。
                    val entry = SkillRuntime.createIndexService(appContext)
                        .listSkillsForManagement()
                        .firstOrNull { it.id == skillId && it.installed }
                        ?: error("未找到技能：$skillId")
                    val resolved = SkillRuntime.createLoader(appContext)
                        .load(entry, "用户通过斜杠命令调用")
                        ?: error("读取 SKILL.md 失败：${entry.skillFilePath}")
                    val body = resolved.bodyMarkdown.trim()
                    val truncated = body.length > AgentSkillCommandPromptCodec.BodyLimit
                    LoadedSkillCommand(
                        prompt = AgentSkillCommandPromptCodec.format(
                            skillId = entry.id,
                            skillName = entry.name,
                            skillPath = entry.skillFilePath,
                            body = if (truncated) body.take(AgentSkillCommandPromptCodec.BodyLimit) else body,
                            truncated = truncated,
                            request = task,
                        ),
                        skillName = entry.name,
                        enabled = entry.enabled,
                        truncated = truncated,
                    )
                }
            }
            loaded.onSuccess { result ->
                withContext(Dispatchers.Main.immediate) {
                    if (!result.enabled) {
                        appendCommandNote(
                            appContext.getString(R.string.agent_command_skill_disabled_note, result.skillName),
                        )
                    }
                    if (result.truncated) {
                        appendCommandNote(
                            appContext.getString(
                                R.string.agent_command_skill_truncated_note,
                                result.skillName,
                                AgentSkillCommandPromptCodec.BodyLimit,
                            ),
                        )
                    }
                    sendCurrentMessage(result.prompt)
                }
            }.onFailure { throwable ->
                withContext(Dispatchers.Main.immediate) {
                    showCommandResult(
                        CommandResultUi(
                            title = appContext.getString(
                                R.string.agent_command_skill_load_failed,
                                throwable.message ?: skillId,
                            ),
                            isError = true,
                        ),
                    )
                }
            }
        }
        return true
    }

    /** 技能正文加载结果：正文块 + 需要留痕的状态（关着的技能、正文被截断）。 */
    private data class LoadedSkillCommand(
        val prompt: String,
        val skillName: String,
        val enabled: Boolean,
        val truncated: Boolean,
    )

    /** 确认卡上的两个按钮：确认则真正执行，取消只收起卡片。 */
    fun resolveCommandConfirm(accept: Boolean) {
        val confirm = homeState.commandConfirm ?: return
        homeState = homeState.copy(commandConfirm = null)
        if (!accept) return
        val command = confirm.commandName?.let { name ->
            agentCommands.firstOrNull { it.name == name }
        }
        if (command != null) {
            val args = (parseAgentCommand(confirm.payload, agentCommands) as? AgentCommandInput.Run)?.args.orEmpty()
            runAgentCommand(command, args, confirmed = true)
        } else {
            // 未知命令按原文发送：// 转义后重新走一遍，避免再次被当成命令。
            sendCurrentMessage("//" + confirm.payload.removePrefix("/"))
        }
    }

    /** 挂上一条 @ 引用：最多 3 条，重复的直接忽略。 */
    fun attachMessageReference(reference: MessageReferenceUi) {
        val current = homeState.pendingMessageRefs
        if (current.any { it.messageId == reference.messageId }) return
        if (current.size >= MessageReferenceLimit) {
            Toast.makeText(
                appContext,
                appContext.getString(R.string.chat_mention_limit, MessageReferenceLimit),
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        updateCurrentConversation(homeState.copy(pendingMessageRefs = current + reference))
    }

    fun removeMessageReference(messageId: String) {
        val current = homeState.pendingMessageRefs
        if (current.none { it.messageId == messageId }) return
        updateCurrentConversation(
            homeState.copy(pendingMessageRefs = current.filterNot { it.messageId == messageId }),
        )
    }

    private fun buildMessageReferenceBlock(
        references: List<MessageReferenceUi>,
        messages: List<AgentChatMessageUi>,
    ): String {
        if (references.isEmpty()) return ""
        val builder = StringBuilder()
        references.forEach { reference ->
            val text = messages.firstOrNull { it.id == reference.messageId }?.let { message ->
                when (message) {
                    is UserMessageUi -> message.content
                    is AgentMessageUi -> message.content
                    else -> null
                }
            }?.trim().orEmpty()
            if (text.isEmpty()) return@forEach
            val clipped = if (text.length <= MessageReferenceInlineLimit) {
                text
            } else {
                text.take(MessageReferenceInlineLimit).trimEnd() + "…"
            }
            builder.append("[引用 #").append(reference.sequence)
                .append(if (reference.fromUser) " · 我" else " · Eta")
                .append("]「").append(clipped).append("」\n")
        }
        if (builder.isEmpty()) return ""
        // 被引用内容可能已经被上下文压缩摘掉了，给模型一条回到原文的路。
        builder.append("（引用的原文可能已被摘要，需要更多上下文时可用 conversation_history 按关键词检索）\n")
        return builder.toString()
    }

    private fun resolveModelCommand(args: String) {
        val query = args.trim()
        val models = modelPickerState.providerGroups.flatMap { it.models }
        if (query.isBlank()) {
            // 不带参数：面板列模型，点一条直接切；不再是两秒就消失的名字列表。
            val palette = modelPalette()
            if (palette == null) {
                showCommandResult(
                    CommandResultUi(appContext.getString(R.string.agent_command_model_empty), isError = true),
                )
            } else {
                commandPalette = palette
            }
            return
        }
        val hit = models.firstOrNull { it.id == query }
            ?: models.firstOrNull { it.modelId.equals(query, ignoreCase = true) }
            ?: models.firstOrNull { it.displayName.contains(query, ignoreCase = true) }
        if (hit == null) {
            showCommandResult(
                CommandResultUi(appContext.getString(R.string.agent_command_model_missing, query), isError = true),
            )
        } else {
            commandPalette = null
            selectModel(hit.id)
            showCommandResult(
                CommandResultUi(appContext.getString(R.string.agent_command_model_switched, hit.displayName)),
            )
        }
    }

    private fun resolveEffortCommand(args: String) {
        val value = args.trim().lowercase()
        val current = homeState.reasoningEffort
        if (value.isBlank()) {
            showCommandResult(
                CommandResultUi(
                    title = appContext.getString(R.string.agent_command_effort_title),
                    lines = ReasoningEffort.entries.map { effort ->
                        (if (effort == current) "· " else "  ") + effort.wireValue
                    },
                ),
            )
            return
        }
        val effort = ReasoningEffort.entries.firstOrNull {
            it.wireValue.equals(value, ignoreCase = true) || it.displayName.equals(value, ignoreCase = true)
        }
        if (effort == null) {
            showCommandResult(
                CommandResultUi(appContext.getString(R.string.agent_command_effort_missing, value), isError = true),
            )
        } else {
            updateReasoningEffort(effort)
            showCommandResult(
                CommandResultUi(appContext.getString(R.string.agent_command_effort_set, effort.wireValue)),
            )
        }
    }

    private fun statusResult(): CommandResultUi {
        val model = modelPickerState.selectedModel?.displayName
            ?: appContext.getString(R.string.agent_command_status_no_model)
        val turnCount = homeState.messages.count { it is UserMessageUi }
        return CommandResultUi(
            title = appContext.getString(R.string.agent_command_status_title),
            lines = listOf(
                appContext.getString(R.string.agent_command_status_line_model, model),
                appContext.getString(R.string.agent_command_status_line_effort, homeState.reasoningEffort.wireValue),
                appContext.getString(R.string.agent_command_status_line_messages, turnCount, homeState.messages.size),
            ),
        )
    }

    private fun toastText(text: String) {
        Toast.makeText(appContext, text, Toast.LENGTH_SHORT).show()
    }

    /** 会话导航：跳到当前会话里的指定消息，走的是搜索命中同一条滚动+高亮通道。 */
    fun jumpToMessage(messageId: String) {
        if (homeState.messages.none { message -> message.id == messageId }) return
        searchJumpToken += 1
        homeState = homeState.copy(searchJump = SearchJumpUiState(messageId, searchJumpToken))
    }

    /** 会话导航面板的条目：空查询给目录，有关键词给命中列表；notice 文案在这里注入。 */
    fun conversationNavEntries(query: String): List<ConversationNavEntry> =
        homeState.navEntries(query) { code -> noticeText(code) }

    /** 中断续跑：用一条继续指令重发，接上 checkpoint 恢复出来的半截上下文。 */
    fun resumeInterruptedRun() {
        if (homeState.resumableRun == null) return
        if (homeState.isStreaming) return
        homeState = homeState.copy(resumableRun = null)
        sendCurrentMessage(appContext.getString(R.string.agent_run_resume_prompt))
    }

    /** 忽略中断续跑提示：只清当前会话里的提示条。 */
    fun dismissInterruptedRun() {
        if (homeState.resumableRun == null) return
        homeState = homeState.copy(resumableRun = null)
    }

    /** 统一入口：进任意会话都走这里，按盘上的草稿贴回输入框。
     *  原来只有 selectConversation() 挂着草稿钩子，从别处切走（角色扮演开局、删除后接手、
     *  回调里直接改 id）既不存盘、切回来也没得贴，所以偶尔会看到"文字不见了"。 */
    private fun enterConversation(conversationId: String?) {
        if (conversationId == null) {
            selectedConversationId = null
            return
        }
        conversationsById[conversationId]?.let { cached ->
            val draft = ChatDraftStore.load(appContext, conversationId)
            if (draft != cached.input) {
                conversationsById = conversationsById + (conversationId to cached.copy(input = draft))
            }
        }
        selectedConversationId = conversationId
    }

    fun selectConversation(conversationId: String) {
        if (homeState.messageEdit != null) cancelMessageEdit()
        // 切换前先把当前会话没发出去的输入写回缓存，切回来时文字还在。
        selectedConversationId
            ?.takeIf { it != conversationId }
            ?.let { previous ->
                conversationsById = conversationsById + (previous to homeState)
                // 同时落盘：只靠内存时，会话重载一次草稿就没了。
                ChatDraftStore.save(appContext, previous, homeState.input)
            }
        // 记一笔"最近打开"：侧栏最上面那一栏按这个顺序排。
        noteRecentOpened(conversationId)
        if (conversationsById[conversationId] == null) return
        fileAttachmentOwnerVersion += 1
        enterConversation(conversationId)
        // enterConversation 已把盘上草稿贴回，取更新后的那份当当前状态。
        val state = conversationsById.getValue(conversationId)
        homeState = state
        // 占位会话只带了元数据：切过来时把消息与上下文补进内存。
        ensureConversationLoaded(conversationId)
        // 从搜索结果里点开：定位该会话第一条命中消息；只有标题/预览命中时不做跳转。
        state.firstMatchMessageId(conversationPaneState.searchQuery.trim()) { code -> noticeText(code) }
            ?.let { target ->
                searchJumpToken += 1
                homeState = state.copy(searchJump = SearchJumpUiState(target, searchJumpToken))
            }
        refreshConversationModel()
        if (state.modelId != null && providers.enabledModel(state.modelId) == null) {
            modelPickerState.selectedModel?.let { fallback ->
                Toast.makeText(
                    appContext,
                    appContext.getString(R.string.conversation_model_unavailable, fallback.displayName),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
        conversationPaneState = conversationPaneState.copy(
            recentOpenedIds = recentOpenedIds,selectedConversationId = conversationId)
        persistConversations()
    }

    /** 顶栏新建：沿用当前会话所属分类；没有分类就落到未分类。 */
    fun createConversation() {
        createConversationInCategory(selectedConversationId?.let { conversationCategoryIds[it] })
    }

    /**
     * 在指定分类下新建会话。分类归属要等会话真正生成（首次发送）时才写库，
     * 所以这里先记下来，分配新会话 id 时一并落库。
     */
    fun createConversationInCategory(categoryId: String?) {
        // 分类表可能还没加载完（升级后首启、快照重载途中），此时按「分类不存在」丢掉意图，
        // 用户看到的就是「在分类下新建，结果跑到未分类」。这里只做非空校验，存在性在渲染与写库时再判。
        pendingConversationCategory = categoryId?.takeIf { candidate -> candidate.isNotBlank() }
        if (homeState.messageEdit != null) cancelMessageEdit()
        // 新建会话前同样把草稿写回，别把没发出去的字丢了。
        selectedConversationId
            ?.let { previous ->
                conversationsById = conversationsById + (previous to homeState)
                ChatDraftStore.save(appContext, previous, homeState.input)
            }
        fileAttachmentOwnerVersion += 1
        selectedConversationId = null
        homeState = emptyChatState(defaultThinkingEnabled)
        refreshConversationModel()
        conversationPaneState = conversationPaneState.copy(
            recentOpenedIds = recentOpenedIds,
            selectedConversationId = null,
            searchQuery = "",
        )
        refreshConversationSummaries()
    }

    fun startCharacterConversation(binding: RoleplayBinding, greeting: String) {
        createConversation()
        val id = newConversationId()
        val greetingId = "greeting-$id"
        val text = CharacterMacros.expand(
            text = greeting, card = CharacterCardCodec.decodeJson(binding.cardSnapshotJson),
            userName = binding.userName, userDescription = binding.userDescription,
        )
        val transcript = if (text.isBlank()) emptyList() else listOf(
            AgentModelClient.ConversationMessage(role = "assistant", content = text, messageId = greetingId),
        )
        // 角色扮演开局也算一次会话切换：先把当前草稿落盘，再进新会话。
        selectedConversationId?.takeIf { it != id }?.let { previous ->
            conversationsById = conversationsById + (previous to homeState)
            ChatDraftStore.save(appContext, previous, homeState.input)
        }
        ChatDraftStore.save(appContext, id, "")
        selectedConversationId = id
        homeState = emptyChatState(defaultThinkingEnabled).withCurrentReasoningCapabilities().copy(
            roleplay = binding,
            history = transcript,
            journal = transcript,
            messages = if (text.isBlank()) emptyList() else listOf(AgentMessageUi(greetingId, text, characterEditable = true)),
            roleplayMessages = RoleplayMessageState(links = if (text.isBlank()) emptyMap() else mapOf(
                greetingId to RoleplayMessageLink(greetingId),
            )),
        )
        conversationTitles = conversationTitles + (id to binding.characterName)
        updateConversation(id, homeState)
        conversationPaneState = conversationPaneState.copy(
            recentOpenedIds = recentOpenedIds,selectedConversationId = id, searchQuery = "")
        refreshConversationSummaries()
        persistConversations()
    }

    fun selectReplyCandidate(messageId: String, index: Int) {
        if (homeState.isStreaming || homeState.messageEdit != null) return
        val updated = RoleplayConversationReducer.select(homeState, messageId, index) ?: return
        updateCurrentConversation(updated)
        refreshConversationSummaries()
        persistConversations()
    }

    fun deleteConversation(conversationId: String) {
        val wasSelected = selectedConversationId == conversationId
        forgetLoadedConversation(conversationId)
        conversationsById = conversationsById - conversationId
        conversationTitles = conversationTitles - conversationId
        conversationUpdatedAt = conversationUpdatedAt - conversationId
        conversationPinned = conversationPinned - conversationId
        if (wasSelected) {
            fileAttachmentOwnerVersion += 1
            val nextId = conversationsById.keys.firstOrNull()
            if (nextId != null) {
                enterConversation(nextId)
                homeState = conversationsById.getValue(nextId)
            } else {
                selectedConversationId = null
                homeState = emptyChatState(defaultThinkingEnabled)
            }
            refreshConversationModel()
        }
        conversationPaneState = conversationPaneState.copy(
            recentOpenedIds = recentOpenedIds,selectedConversationId = selectedConversationId)
        refreshConversationSummaries()
        persistConversations()
    }

    fun renameConversation(conversationId: String, title: String) {
        val trimmed = title.trim()
        if (trimmed.isBlank()) return
        conversationTitles = conversationTitles + (conversationId to trimmed)
        conversationUpdatedAt = conversationUpdatedAt + (conversationId to System.currentTimeMillis())
        refreshConversationSummaries()
        persistConversations()
    }

    /** 收藏置顶：最多同时置顶 MAX_PINNED_CONVERSATIONS 条；置顶会话排到列表最前；直写数据库单列，不触发整段会话保存。
     *  返回 false 表示已达上限、本次未写入。 */
    fun setConversationPinned(conversationId: String, pinned: Boolean): Boolean {
        if (conversationPinned[conversationId] == pinned) return true
        if (pinned && conversationPinned.count { it.key != conversationId && it.value } >= MAX_PINNED_CONVERSATIONS) {
            return false
        }
        conversationPinned = conversationPinned + (conversationId to pinned)
        refreshConversationSummaries()
        scope.launch {
            runCatching {
                AgentConversationStore.setPinned(appContext, conversationId, pinned)
            }.onFailure { error ->
                AndroidAgentLogger.warn("Conversation pin persist failed: type=${error.safeLogType()}")
            }
        }
        return true
    }

    /** 新建分类；名字为空或重名时返回 null。 */
    fun createConversationCategory(name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || conversationCategories.any { it.name == trimmed }) return null
        val category = ConversationCategoryEntity(
            id = UUID.randomUUID().toString(),
            name = trimmed,
            sortOrder = (conversationCategories.maxOfOrNull { it.sortOrder } ?: 0) + 1,
            createdAt = System.currentTimeMillis(),
        )
        conversationCategories = conversationCategories + category
        refreshConversationSummaries()
        scope.launch {
            runCatching { AgentConversationStore.saveCategory(appContext, category) }
                .onFailure { error -> AndroidAgentLogger.warn("Conversation category create failed: type=${error.safeLogType()}") }
        }
        return category.id
    }

    /** 重命名分类；名字为空、未变化或与其它分类重名时忽略。 */
    fun renameConversationCategory(categoryId: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val target = conversationCategories.firstOrNull { it.id == categoryId } ?: return
        if (target.name == trimmed || conversationCategories.any { it.id != categoryId && it.name == trimmed }) return
        val updated = target.copy(name = trimmed)
        conversationCategories = conversationCategories.map { if (it.id == categoryId) updated else it }
        refreshConversationSummaries()
        scope.launch {
            runCatching { AgentConversationStore.saveCategory(appContext, updated) }
                .onFailure { error -> AndroidAgentLogger.warn("Conversation category rename failed: type=${error.safeLogType()}") }
        }
    }

    fun conversationCategoryOf(conversationId: String): String? = conversationCategoryIds[conversationId]

    /** 会话归属分类；categoryId 为 null 表示移回未分类。 */
    fun setConversationCategory(conversationId: String, categoryId: String?) {
        val normalized = categoryId?.takeIf { candidate -> conversationCategories.any { it.id == candidate } }
        if (conversationCategoryIds[conversationId] == normalized) return
        conversationCategoryIds = if (normalized == null) {
            conversationCategoryIds - conversationId
        } else {
            conversationCategoryIds + (conversationId to normalized)
        }
        refreshConversationSummaries()
        scope.launch {
            runCatching { AgentConversationStore.setCategory(appContext, conversationId, normalized) }
                .onFailure { error -> AndroidAgentLogger.warn("Conversation category assign failed: type=${error.safeLogType()}") }
        }
    }

    /** 删除分类：其下会话回到未分类，会话本身保留。 */
    fun deleteConversationCategory(categoryId: String) {
        if (conversationCategories.none { it.id == categoryId }) return
        conversationCategories = conversationCategories.filterNot { it.id == categoryId }
        conversationCategoryIds = conversationCategoryIds.filterValues { it != categoryId }
        refreshConversationSummaries()
        scope.launch {
            runCatching { AgentConversationStore.removeCategory(appContext, categoryId) }
                .onFailure { error -> AndroidAgentLogger.warn("Conversation category delete failed: type=${error.safeLogType()}") }
        }
    }

    /** 上移或下移分类；delta 为 -1 或 1，越界不动。 */
    fun moveConversationCategory(categoryId: String, delta: Int) {
        val ordered = conversationCategories.sortedWith(compareBy({ it.sortOrder }, { it.createdAt })).toMutableList()
        val index = ordered.indexOfFirst { it.id == categoryId }
        val target = index + delta
        if (index < 0 || target < 0 || target >= ordered.size) return
        ordered.add(target, ordered.removeAt(index))
        conversationCategories = ordered.mapIndexed { position, category -> category.copy(sortOrder = position) }
        refreshConversationSummaries()
        scope.launch {
            runCatching { conversationCategories.forEach { AgentConversationStore.saveCategory(appContext, it) } }
                .onFailure { error -> AndroidAgentLogger.warn("Conversation category reorder failed: type=${error.safeLogType()}") }
        }
    }


    /** 分配新会话 id；若此前指定了分类，就在这里把归属写进库。 */
    private fun allocateConversationId(): String {
        val id = newConversationId()
        // #002：此刻库里还没有这一行，直写 `UPDATE conversations SET category_id` 会打在 0 行上，
        // 随后持久化又把 null 写进去，新会话就掉进未分类。这里只更新内存映射，
        // 归属由 persistConversations 里的保存路径随会话行一起插入。
        pendingConversationCategory?.let { categoryId ->
            conversationCategoryIds = conversationCategoryIds + (id to categoryId)
        }
        pendingConversationCategory = null
        return id
    }

    fun exportConversationMarkdown(conversationId: String): String? {
        val state = conversationsById[conversationId] ?: return null
        val title = conversationTitles[conversationId]?.takeIf { it.isNotBlank() }
            ?: appContext.getString(R.string.conversation_unnamed)
        return ConversationMarkdownExporter.export(
            title = title,
            messages = state.messages,
            labels = ConversationMarkdownExporter.Labels(
                user = appContext.getString(R.string.conversation_export_user),
                assistant = appContext.getString(R.string.conversation_export_assistant),
                thinking = appContext.getString(R.string.conversation_export_thinking),
                toolLineFormat = appContext.getString(R.string.conversation_export_tool_line),
                toolsLineFormat = appContext.getString(R.string.conversation_export_tools_line),
                argumentsFormat = appContext.getString(R.string.conversation_export_tool_arguments),
                resultFormat = appContext.getString(R.string.conversation_export_tool_result),
                imagesFormat = appContext.getString(R.string.conversation_export_images),
                toolStatusRunning = appContext.getString(R.string.tool_status_running),
                toolStatusSuccess = appContext.getString(R.string.tool_status_success),
                toolStatusFailed = appContext.getString(R.string.tool_status_failed),
                toolStatusUnknown = appContext.getString(R.string.tool_status_unknown),
                noticeStopped = noticeText(SystemNoticeCode.Stopped),
                noticeEmptyResult = noticeText(SystemNoticeCode.EmptyResult),
                noticeModelRetry = noticeText(SystemNoticeCode.ModelRetry),
                noticeContextCompaction = noticeText(SystemNoticeCode.ContextCompaction),
                noticeRuntimeFailed = noticeText(SystemNoticeCode.RuntimeFailed),
                noticeInterrupted = noticeText(SystemNoticeCode.Interrupted),
            ),
        )
    }

    private fun noticeText(code: SystemNoticeCode): String = appContext.getString(
        when (code) {
            SystemNoticeCode.Stopped -> R.string.system_notice_stopped
            SystemNoticeCode.EmptyResult -> R.string.system_notice_empty_result
            SystemNoticeCode.ContextCompaction -> R.string.context_compaction
            SystemNoticeCode.ModelRetry -> R.string.system_notice_model_retry
            SystemNoticeCode.RuntimeFailed -> R.string.system_notice_runtime_failed
            SystemNoticeCode.Interrupted -> R.string.system_notice_interrupted
            SystemNoticeCode.CommandNote -> R.string.system_notice_command_note
        },
    )

    fun sendCurrentMessage(submittedText: String? = null) {
        submitOrQueueCurrentMessage(submittedText = submittedText, immediateInterrupt = false)
    }

    fun interruptAndSendCurrentMessage(submittedText: String? = null) {
        submitOrQueueCurrentMessage(submittedText = submittedText, immediateInterrupt = true)
    }

    /** 长按发送：只排队等待，不打断当前运行。 */
    fun queueCurrentMessage(submittedText: String? = null) {
        submitOrQueueCurrentMessage(submittedText = submittedText, immediateInterrupt = false, queueOnly = true)
    }

    /** 队列里点「立即插话」：置队首并打断当前运行，收尾后自动发出。 */
    fun steerQueuedMessage(id: String) {
        val (nextState, promoted) = AgentMessageQueueReducer.promoteToInterrupt(homeState, id)
        if (promoted == null) return
        updateCurrentConversation(nextState, updateTimestamp = false)
        if (nextState.isStreaming && !nextState.isCompacting) {
            requestInterruptCurrentRun()
        } else if (!nextState.isStreaming) {
            dispatchQueuedMessage(selectedConversationId, promoted)
        }
    }

    /** 把排队消息退回输入框继续编辑（与当前草稿合并）。 */
    fun recallQueuedMessage(id: String, currentDraftText: String) {
        val nextState = AgentMessageQueueReducer.recallToDraft(
            state = homeState,
            currentInputText = currentDraftText,
            id = id,
        ) ?: return
        updateCurrentConversation(nextState, updateTimestamp = false)
    }

    fun removeQueuedMessage(id: String) {
        val nextState = AgentMessageQueueReducer.remove(homeState, id)
        if (nextState !== homeState) {
            updateCurrentConversation(nextState, updateTimestamp = false)
        }
    }

    private fun requestInterruptCurrentRun() {
        currentRunId?.let { runId -> stopRun(runId) }
    }

    private fun submitOrQueueCurrentMessage(
        submittedText: String?,
        immediateInterrupt: Boolean,
        queueOnly: Boolean = false,
    ) {
        // 会话历史还在补载：等它落地再发，避免用空 history 请求模型。
        val pendingConversationId = selectedConversationId
        if (pendingConversationId != null && pendingConversationId in partiallyLoadedConversationIds) {
            ensureConversationLoaded(pendingConversationId)
            val deferredText = submittedText
            scope.launch {
                conversationLoadJobs[pendingConversationId]?.join()
                withContext(Dispatchers.Main.immediate) {
                    if (selectedConversationId == pendingConversationId) {
                        submitOrQueueCurrentMessage(deferredText, immediateInterrupt, queueOnly)
                    }
                }
            }
            return
        }
        if (homeState.isCompacting) return
        // 用户开始新的输入：命令结果卡让位。
        if (homeState.commandResult != null) {
            updateCurrentConversation(homeState.copy(commandResult = null), updateTimestamp = false)
        }
        // 斜杠命令：本地解析，命中就直接执行，不产生模型请求。
        val rawInput = submittedText ?: homeState.input
        val parsedCommand = parseAgentCommand(rawInput.trim(), agentCommands)
        if (parsedCommand is AgentCommandInput.Run && runAgentCommand(parsedCommand.command, parsedCommand.args)) {
            // 技能命令没带任务描述时保留草稿，用户接着补一句就能直接发。
            val keepDraft = parsedCommand.command.kind == AgentCommandKind.Skill &&
                parsedCommand.args.isBlank()
            if (!keepDraft) {
                updateCurrentConversation(
                    homeState.copy(input = "", inputResetToken = homeState.inputResetToken + 1),
                )
            }
            return
        }
        if (parsedCommand is AgentCommandInput.Unknown) {
            homeState = homeState.copy(
                commandConfirm = CommandConfirmUi(
                    title = appContext.getString(R.string.agent_command_unknown_title, "/" + parsedCommand.name),
                    detail = appContext.getString(R.string.agent_command_unknown_detail),
                    commandName = null,
                    payload = parsedCommand.raw,
                ),
            )
            return
        }
        val typedPrompt = when (parsedCommand) {
            is AgentCommandInput.Text -> parsedCommand.text.trim()
            else -> rawInput.trim()
        }
        // @ 引用：把被引用的历史消息以摘录形式放在正文前面，明确标出是谁说的第几条。
        val referenceBlock = buildMessageReferenceBlock(homeState.pendingMessageRefs, homeState.messages)
        val prompt = (referenceBlock + typedPrompt).trim()
        val pendingImages = homeState.pendingImages
        val pendingFileReferences = homeState.pendingFileReferences
        if (prompt.isBlank() && pendingImages.isEmpty() && pendingFileReferences.isEmpty()) {
            return
        }
        homeState.messageEdit?.takeIf { it.preserveFollowingMessages }?.let { edit ->
            val updated = RoleplayConversationReducer.edit(homeState, edit.targetMessageId, prompt) ?: return
            updateCurrentConversation(updated.copy(
                input = edit.previousInput,
                pendingImages = edit.previousImages,
                pendingFileReferences = edit.previousFileReferences,
                messageEdit = null,
            ))
            refreshConversationSummaries()
            persistConversations()
            return
        }
        val fileReferences = pendingFileReferences.map { it.reference }
        if (
            !AgentFileReferencePolicy.canSend(
                references = fileReferences,
                terminalToolsEnabled = agentBooleanForUi(Prefs.Keys.AGENT_TERMINAL_TOOLS),
            )
        ) {
            Toast.makeText(
                appContext,
                appContext.getString(R.string.state_ui_file_path_reference_requires_opening_the_termina_deca4c),
                Toast.LENGTH_SHORT,
            ).show()
            return
        }

        if (homeState.isStreaming) {
            if (queueOnly) {
                // 长按发送：只入队，等当前运行自然收尾后发出。
                val queued = QueuedMessageUi(
                    id = "queue-${UUID.randomUUID()}",
                    content = prompt,
                    pendingImages = pendingImages,
                    pendingFileReferences = pendingFileReferences,
                    immediateInterrupt = false,
                )
                updateCurrentConversation(
                    state = AgentMessageQueueReducer.enqueue(homeState, queued),
                    updateTimestamp = false,
                )
            } else if (immediateInterrupt) {
                // 显式插话：入队置首并打断当前运行，收尾后自动发出。
                val queued = QueuedMessageUi(
                    id = "queue-${UUID.randomUUID()}",
                    content = prompt,
                    pendingImages = pendingImages,
                    pendingFileReferences = pendingFileReferences,
                    immediateInterrupt = true,
                )
                updateCurrentConversation(
                    state = AgentMessageQueueReducer.enqueue(homeState, queued),
                    updateTimestamp = false,
                )
                requestInterruptCurrentRun()
            } else {
                // 运行中直接插话：走 steering 通道，注入下一个 turn，不打断当前请求或工具批次。
                updateCurrentConversation(homeState.copy(input = "", pendingMessageRefs = emptyList()))
                val conversationId = selectedConversationId.orEmpty()
                scope.launch(Dispatchers.IO) {
                    AgentRuntimeClient(appContext, AndroidAgentLogger).steerRun(prompt, conversationId)
                }
            }
            return
        }

        val edit = homeState.messageEdit

        val editBoundary = edit?.let {
            AgentConversationRevisionReducer.boundary(homeState, it.targetMessageId)
        }
        if (edit != null && editBoundary == null) {
            cancelMessageEdit()
            return
        }

        val conversationId = selectedConversationId ?: allocateConversationId().also {
            selectedConversationId = it
            ChatDraftStore.save(appContext, it, "")
        }
        dispatchMessageToConversation(
            conversationId = conversationId,
            state = homeState,
            prompt = prompt,
            pendingImages = pendingImages,
            pendingFileReferences = pendingFileReferences,
            editBoundary = editBoundary,
        )
    }

    private fun dispatchQueuedMessage(
        conversationId: String?,
        item: QueuedMessageUi,
    ) {
        val targetConversationId = conversationId ?: selectedConversationId ?: allocateConversationId().also {
            selectedConversationId = it
        }
        val state = conversationsById[targetConversationId] ?: homeState
        if (state.isStreaming || state.isCompacting) return
        val fileReferences = item.pendingFileReferences.map { it.reference }
        if (
            !AgentFileReferencePolicy.canSend(
                references = fileReferences,
                terminalToolsEnabled = agentBooleanForUi(Prefs.Keys.AGENT_TERMINAL_TOOLS),
            )
        ) {
            Toast.makeText(
                appContext,
                appContext.getString(R.string.state_ui_file_path_reference_requires_opening_the_termina_deca4c),
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        dispatchMessageToConversation(
            conversationId = targetConversationId,
            state = state,
            prompt = item.content,
            pendingImages = item.pendingImages,
            pendingFileReferences = item.pendingFileReferences,
            editBoundary = null,
            preserveCurrentDraft = true,
        )
    }

    private fun dispatchMessageToConversation(
        conversationId: String,
        state: AgentChatHomeUiState,
        prompt: String,
        pendingImages: List<PendingImageUi>,
        pendingFileReferences: List<PendingFileReferenceUi>,
        editBoundary: AgentConversationRevisionReducer.Boundary?,
        preserveCurrentDraft: Boolean = false,
    ) {
        val fileReferences = pendingFileReferences.map { it.reference }
        val runtimePrompt = AgentFileReferencePromptCodec.format(prompt, fileReferences)
        val runId = "run-${UUID.randomUUID()}"
        val userMessage = UserMessageUi(
            id = editBoundary?.userMessage?.id ?: "user-$runId",
            content = runtimePrompt,
            images = pendingImages.map { it.dataUrl },
            isEdited = editBoundary != null,
        )
        val history = editBoundary?.historyPrefix ?: state.history
        val messages = if (editBoundary == null) {
            state.messages + userMessage
        } else {
            state.messages.take(editBoundary.userMessageIndex) + userMessage
        }
        val userHistoryMessage = AgentModelClient.buildUserHistoryMessage(
            // 正文只在当轮注入：历史里压成一行标记，避免之后每轮都重复几 k 字符。
            text = AgentSkillCommandPromptCodec.compactForHistory(runtimePrompt),
            images = pendingImages.toHistoryImages(),
        ).copy(messageId = userMessage.id)

        val currentTitle = conversationTitles[conversationId]
        val oldAutoTitle = editBoundary
            ?.takeIf { it.userMessageIndex == 0 }
            ?.userMessage
            ?.content
            ?.defaultConversationTitleFromMessage()
        val nextAutoTitle = defaultConversationTitle(prompt, fileReferences)
        val title = if (
            editBoundary?.userMessageIndex == 0 &&
            (currentTitle == oldAutoTitle || currentTitle.isNullOrBlank())
        ) {
            nextAutoTitle
        } else {
            currentTitle?.takeIf(String::isNotBlank) ?: nextAutoTitle
        }

        conversationTitles = conversationTitles + (conversationId to title)
        conversationPaneState = conversationPaneState.copy(
            recentOpenedIds = recentOpenedIds,selectedConversationId = conversationId)
        launchConversationRun(
            conversationId = conversationId,
            runId = runId,
            prompt = runtimePrompt,
            images = pendingImages,
            history = history,
            userHistoryMessage = userHistoryMessage,
            messages = messages,
            state = homeState.copy(
                journal = editBoundary?.journalPrefix ?: homeState.journal,
                input = "",
                pendingImages = emptyList(),
                pendingFileReferences = emptyList(),
                pendingMessageRefs = emptyList(),
                messageEdit = null,
            ),
            reasoningEffort = homeState.reasoningEffort,
        )
    }

    fun beginMessageEdit(messageId: String) {
        if (homeState.isStreaming || homeState.messageEdit != null) return
        if (homeState.roleplay != null) {
            if (messageId !in homeState.roleplayMessages.links) return
            val content = when (val message = homeState.messages.firstOrNull { it.id == messageId }) {
                is UserMessageUi -> message.content
                is AgentMessageUi -> message.content
                else -> return
            }
            updateCurrentConversation(homeState.copy(
                input = content, pendingImages = emptyList(), pendingFileReferences = emptyList(),
                messageEdit = MessageEditUiState(messageId, homeState.input, homeState.pendingImages,
                    homeState.pendingFileReferences, hasLaterTurns = false, preserveFollowingMessages = true),
            ))
            return
        }
        val boundary = AgentConversationRevisionReducer.boundary(homeState, messageId) ?: return
        val images = boundary.userMessage.images.mapIndexed { index, dataUrl ->
            PendingImageUi(
                id = "edit-${boundary.userMessage.id}-$index",
                uri = dataUrl,
                dataUrl = dataUrl,
                mimeType = dataUrl.imageMimeType(),
            )
        }
        val parsedPrompt = AgentFileReferencePromptCodec.parse(boundary.userMessage.content)
        val fileReferences = parsedPrompt.references.mapIndexed { index, reference ->
            PendingFileReferenceUi(
                id = "edit-${boundary.userMessage.id}-file-$index",
                reference = reference,
            )
        }
        updateCurrentConversation(
            homeState.copy(
                input = parsedPrompt.request,
                pendingImages = images,
                pendingFileReferences = fileReferences,
                messageEdit = MessageEditUiState(
                    targetMessageId = boundary.userMessage.id,
                    previousInput = homeState.input,
                    previousImages = homeState.pendingImages,
                    previousFileReferences = homeState.pendingFileReferences,
                    hasLaterTurns = boundary.laterTurnCount > 0,
                ),
            )
        )
        if (boundary.contextWasCompacted) showCompactedRevisionNotice()
    }

    fun cancelMessageEdit() {
        val edit = homeState.messageEdit ?: return
        updateCurrentConversation(
            homeState.copy(
                input = edit.previousInput,
                pendingImages = edit.previousImages,
                pendingFileReferences = edit.previousFileReferences,
                messageEdit = null,
            )
        )
    }

    fun messageRevisionImpact(messageId: String): MessageRevisionImpact? =
        if (homeState.roleplay != null && messageId.startsWith("greeting-")) MessageRevisionImpact(0)
        else AgentConversationRevisionReducer.boundary(homeState, messageId)?.let { boundary ->
            MessageRevisionImpact(laterTurnCount = boundary.laterTurnCount)
        }

    fun deleteMessageTurn(messageId: String) {
        if (homeState.isStreaming || homeState.messageEdit != null) return
        val conversationId = selectedConversationId ?: return
        if (homeState.roleplay != null && messageId.startsWith("greeting-")) {
            val originalId = homeState.roleplayMessages.links[messageId]?.transcriptMessageId ?: return
            val updated = homeState.copy(
                messages = homeState.messages.filterNot { it.id == messageId },
                journal = homeState.journal.filterNot { it.messageId == originalId },
                history = homeState.history.filterNot { it.messageId == originalId || it.contextSummary },
                roleplayMessages = homeState.roleplayMessages.copy(
                    links = homeState.roleplayMessages.links - messageId,
                    revisions = homeState.roleplayMessages.revisions - messageId,
                ),
            )
            updateConversation(conversationId, updated.copy(history = RoleplayConversationReducer.projectJournal(updated)))
            refreshConversationSummaries()
            persistConversations()
            return
        }
        val removed = AgentConversationRevisionReducer.deleteFromTurn(homeState, messageId) ?: return
        val retainedIds = removed.messages.mapTo(mutableSetOf()) { it.id }
        val revised = if (removed.roleplay == null) removed else removed.copy(roleplayMessages = removed.roleplayMessages.copy(
            links = removed.roleplayMessages.links.filterKeys { it in retainedIds },
            revisions = removed.roleplayMessages.revisions.filterKeys { it in retainedIds },
        ))
        if (revised.messages.isEmpty()) {
            forgetLoadedConversation(conversationId)
            conversationsById = conversationsById - conversationId
            conversationTitles = conversationTitles - conversationId
            conversationUpdatedAt = conversationUpdatedAt - conversationId
            conversationPinned = conversationPinned - conversationId
            fileAttachmentOwnerVersion += 1
            selectedConversationId = null
            homeState = emptyChatState(defaultThinkingEnabled)
            refreshConversationModel()
            conversationPaneState = conversationPaneState.copy(
            recentOpenedIds = recentOpenedIds,selectedConversationId = null)
            refreshConversationSummaries()
            persistConversations()
            return
        }
        updateConversation(conversationId, revised)
        refreshConversationSummaries()
        persistConversations()
    }

    fun regenerateMessage(messageId: String) {
        if (homeState.isStreaming || homeState.messageEdit != null) return
        val conversationId = selectedConversationId ?: return
        if (homeState.roleplay != null) {
            val target = homeState.messages.filterIsInstance<AgentMessageUi>().firstOrNull { it.id == messageId } ?: return
            val prefix = RoleplayConversationReducer.rewriteHistory(homeState, messageId) ?: return
            val runId = "run-${UUID.randomUUID()}"
            launchConversationRun(
                conversationId, runId, target.content, emptyList(), prefix, null, homeState.messages,
                homeState.copy(roleplayMessages = homeState.roleplayMessages.copy(
                    pendingRewrites = homeState.roleplayMessages.pendingRewrites + (runId to messageId),
                )), homeState.reasoningEffort, operation = AgentRuntimeWire.OP_REWRITE_REPLY,
                rewriteTargetMessageId = messageId,
            )
            return
        }
        val boundary = AgentConversationRevisionReducer.boundary(homeState, messageId) ?: return
        val images = boundary.userMessage.images.mapIndexed { index, dataUrl ->
            PendingImageUi(
                id = "regenerate-${boundary.userMessage.id}-$index",
                uri = dataUrl,
                dataUrl = dataUrl,
                mimeType = dataUrl.imageMimeType(),
            )
        }
        val runId = "run-${UUID.randomUUID()}"
        val userHistoryMessage = AgentModelClient.buildUserHistoryMessage(
            text = AgentSkillCommandPromptCodec.compactForHistory(boundary.userMessage.content),
            images = images.toHistoryImages(),
        )
        if (boundary.contextWasCompacted) showCompactedRevisionNotice()
        launchConversationRun(
            conversationId = conversationId,
            runId = runId,
            prompt = boundary.userMessage.content,
            images = images,
            history = boundary.historyPrefix,
            userHistoryMessage = userHistoryMessage,
            messages = homeState.messages.take(boundary.userMessageIndex + 1),
            state = homeState.copy(journal = boundary.journalPrefix),
            reasoningEffort = homeState.reasoningEffort,
        )
    }

    fun compactCurrentContext() {
        val conversationId = selectedConversationId ?: return
        if (!hasRunCapacity() || !homeState.canCompactContext || modelPickerState.isChanging) return
        launchConversationRun(
            conversationId = conversationId,
            runId = java.util.UUID.randomUUID().toString(),
            prompt = "", images = emptyList(), history = homeState.history,
            userHistoryMessage = null, messages = homeState.messages, state = homeState,
            reasoningEffort = homeState.reasoningEffort, operation = AgentRuntimeWire.OP_COMPACT,
        )
    }

    private fun launchConversationRun(
        conversationId: String,
        runId: String,
        prompt: String,
        images: List<PendingImageUi>,
        history: List<AgentModelClient.ConversationMessage>,
        userHistoryMessage: AgentModelClient.ConversationMessage?,
        messages: List<AgentChatMessageUi>,
        state: AgentChatHomeUiState,
        reasoningEffort: ReasoningEffort,
        operation: String = AgentRuntimeWire.OP_CHAT,
        rewriteTargetMessageId: String? = null,
    ) {
        runConversationIds[runId] = conversationId
        currentRunId = runId
        // 发送即绑定：会话记住本轮实际使用的模型，回落到默认模型时也随之更新。
        val runModelId = state.effectiveModelId

        updateConversation(
            conversationId,
            state.copy(
                modelId = runModelId,
                isStreaming = true,
                history = if (operation == AgentRuntimeWire.OP_REWRITE_REPLY) state.history else history + listOfNotNull(userHistoryMessage),
                journal = state.journal.ifEmpty { state.history } + listOfNotNull(userHistoryMessage),
                isCompacting = operation == AgentRuntimeWire.OP_COMPACT,
                messages = messages,
                messageEdit = null,
            )
        )
        refreshConversationSummaries()
        val initialPersistence = persistConversations()

        val preparationJob = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            // write-ahead：用户消息未提交前不把可能产生副作用的 run 交给 Runtime。
            if (!initialPersistence.await()) {
                withContext(Dispatchers.Main) {
                    applyRunResult(
                        runId,
                        AgentRuntimeWire.RunResult(
                            runId = runId,
                            ok = false,
                            content = "",
                            error = appContext.getString(R.string.conversation_persistence_failed),
                        )
                    )
                }
                return@launch
            }
            state.roleplay?.let { binding ->
                try {
                    CharacterRepository.initialize(appContext)
                    CharacterRepository.get(binding.characterId)?.let { profile ->
                        val updatedBinding = binding.copy(
                            cardSnapshotJson = CharacterCardCodec.encodeJson(profile.card),
                            characterName = profile.card.name, avatarPath = profile.avatarPath,
                        )
                        val saved = withContext(Dispatchers.Main) {
                            conversationsById[conversationId]?.let { current ->
                                updateConversation(conversationId, current.copy(roleplay = updatedBinding))
                            }
                            persistConversations()
                        }.await()
                        check(saved) { "无法保存角色会话设定" }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    AndroidAgentLogger.warnThrottled("character_run_prepare_failed") {
                        "Character preparation failed: type=${failure.safeLogType()}"
                    }
                    withContext(Dispatchers.Main) {
                        applyRunResult(runId, AgentRuntimeWire.RunResult(runId, false, "", "无法读取或保存角色设定，请重试。",
                            operation = operation, rewriteTargetMessageId = rewriteTargetMessageId))
                    }
                    return@launch
                }
            }
            val permittedReasoningEffort = if (
                agentBooleanForUi(Prefs.Keys.AGENT_THINKING_ENABLED)
            ) {
                reasoningEffort
            } else {
                ReasoningEffort.OFF
            }
            val config = RuntimeConfigRepository.runtimeConfigFor(runModelId)?.copy(
                terminalTools = agentBooleanForUi(Prefs.Keys.AGENT_TERMINAL_TOOLS),
                browserTools = agentBooleanForUi(Prefs.Keys.AGENT_BROWSER_TOOLS),
                deviceDirectTools = agentBooleanForUi(Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS),
                deviceSensitiveReadTools =
                    agentBooleanForUi(Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS),
                deviceSensitiveActionTools =
                    agentBooleanForUi(Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS),
                subagentTools = agentBooleanForUi(Prefs.Keys.AGENT_SUBAGENT_ENABLED),
                thinkingEnabled = permittedReasoningEffort.enablesReasoning,
                reasoningEffort = permittedReasoningEffort,
            )
            if (config == null) {
                withContext(Dispatchers.Main) {
                    applyRunResult(
                        runId,
                        AgentRuntimeWire.RunResult(
                            runId = runId,
                            ok = false,
                            content = "",
                            error = appContext.getString(R.string.state_ui_please_configure_the_model_provider_and_model_fi_a36e15),
                        )
                    )
                }
                return@launch
            }
            val modelImages = images.map { p ->
                AgentModelClient.ModelImage(
                    reference = p.uri,
                    mimeType = p.mimeType,
                    bytes = 0,
                    source = "user_attach",
                )
            }
            if (withContext(Dispatchers.Main) { runId in stopRequestedRunIds }) {
                withContext(Dispatchers.Main) {
                    applyRunResult(runId, AgentRuntimeWire.RunResult(runId, false, "", "已停止", operation = operation))
                }
                return@launch
            }
            val result = runInterruptible {
                AgentRuntimeClient(appContext, AndroidAgentLogger).run(
                    request = AgentRuntimeWire.RunRequest(
                        operation = operation,
                        rewriteTargetMessageId = rewriteTargetMessageId,
                        runId = runId,
                        prompt = prompt,
                        config = config.copy(supportsSearchSuggestions = true),
                        images = modelImages,
                        history = history,
                        handoff = AgentRuntimeWire.EntryHandoff(
                            id = runId,
                            source = AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE,
                            payload = io.github.mangi.eta.agent.runtime.AgentUiHandoffPayload(
                                conversationId = conversationId,
                                title = conversationTitles[conversationId].orEmpty(),
                            ).toJson(),
                        ),
                    ),
                    onEvent = { event -> enqueueRunEvent(runId, event) },
                )
            }
            withContext(Dispatchers.Main) {
                applyRunResult(runId, result, acknowledgeRuntimeResult = true)
            }
        }
        currentRunJob = preparationJob
        if (!RootAccess.isGranted) {
            val leaseId = "prepare:$runId"
            val acquired = AgentExecutionService.acquire(appContext, leaseId) {
                scope.launch(Dispatchers.Main.immediate) {
                    stopRun(runId)
                }
            }
            if (!acquired) {
                preparationJob.cancel()
                applyRunResult(runId, AgentRuntimeWire.RunResult(
                    runId = runId,
                    ok = false,
                    content = "",
                    error = appContext.getString(R.string.capability_background_failed),
                ))
                return
            }
            preparationJob.invokeOnCompletion { AgentExecutionService.release(leaseId) }
        }
        preparationJob.start()
    }

    private fun List<PendingImageUi>.toHistoryImages(): List<AgentModelClient.ModelImage> =
        map { image ->
            AgentModelClient.ModelImage(
                reference = image.dataUrl,
                mimeType = image.mimeType,
                bytes = image.dataUrl.length,
                source = image.uri,
            )
        }

    private fun String.imageMimeType(): String =
        takeIf { startsWith("data:") }
            ?.substringAfter("data:")
            ?.substringBefore(';')
            ?.takeIf { it.startsWith("image/") }
            ?: "image/jpeg"

    private fun String.defaultConversationTitle(): String =
        lineSequence().firstOrNull().orEmpty().trim().take(MAX_TITLE_CHARS)

    private fun defaultConversationTitle(
        request: String,
        references: List<AgentFileReference>,
    ): String = AgentFileReferencePolicy
        .titleSource(request, references)
        .defaultConversationTitle()

    private fun String.defaultConversationTitleFromMessage(): String {
        val parsed = AgentFileReferencePromptCodec.parse(this)
        return defaultConversationTitle(parsed.request, parsed.references)
    }

    private fun showCompactedRevisionNotice() {
        Toast.makeText(
            appContext,
            appContext.getString(R.string.state_ui_the_earlier_context_has_been_compressed_and_will_cf6c86),
            Toast.LENGTH_LONG,
        ).show()
    }

    /**
     * 分享进来的文字：走输入框的注入通道（文本框自己有状态，只改业务 input 显示不出来），
     * 同时同步业务状态，用户改好自己发。
     */
    fun applySharedText(text: String) {
        io.github.mangi.eta.ui.components.AgentInputDrafts.inject(text)
        val current = homeState.input
        val merged = if (current.isBlank()) text else current.trimEnd() + "\n" + text
        updateCurrentConversation(homeState.copy(input = merged))
        io.github.mangi.eta.core.EtaTrace.log("share.apply", "注入输入框 ${text.length} 字")
        Toast.makeText(appContext, "分享的内容已填入输入框，改好就发。", Toast.LENGTH_SHORT).show()
    }

    fun attachImage(uri: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val image = AgentImageCodec.fromReference(
                    context = appContext,
                    value = uri,
                    source = "user_attach",
                )
                if (image == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            appContext,
                            appContext.getString(R.string.state_ui_unable_to_read_this_image_please_try_again_or_us_d94978),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    return@launch
                }
                val preview = AgentImageCodec.previewFromReference(appContext, image) ?: image
                val pending = PendingImageUi(
                    id = "img-${UUID.randomUUID()}",
                    // 后续发送使用首次读取后的稳定引用，不再依赖 ROM Photo Picker URI 的授权生命周期。
                    uri = image.reference,
                    dataUrl = preview.reference,
                    mimeType = image.mimeType,
                )
                withContext(Dispatchers.Main) {
                    updateCurrentConversation(homeState.copy(pendingImages = homeState.pendingImages + pending))
                }
            } finally {
                val selectedUri = Uri.parse(uri)
                if (selectedUri.scheme == ContentResolver.SCHEME_CONTENT) {
                    runCatching {
                        appContext.contentResolver.releasePersistableUriPermission(
                            selectedUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                }
            }
        }
    }

    fun removePendingImage(id: String) {
        updateCurrentConversation(homeState.copy(pendingImages = homeState.pendingImages.filterNot { it.id == id }))
    }

    fun attachFiles(uris: List<String>) {
        if (uris.isEmpty()) return
        resolveAndAttachFileReferences {
            val gateway = AgentFileReferenceGateway(appContext, AndroidAgentLogger)
            uris.map { uri ->
                gateway.resolveDocumentUri(
                    uri = Uri.parse(uri),
                    expectedKind = AgentFileReferenceKind.File,
                )
            }
        }
    }

    fun attachFolder(uri: String) {
        resolveAndAttachFileReferences {
            val gateway = AgentFileReferenceGateway(appContext, AndroidAgentLogger)
            listOf(
                gateway.resolveDocumentUri(
                    uri = Uri.parse(uri),
                    expectedKind = AgentFileReferenceKind.Directory,
                )
            )
        }
    }

    fun attachFilePath(path: String) {
        resolveAndAttachFileReferences {
            listOf(AgentFileReferenceGateway(AndroidAgentLogger).resolveAbsolutePath(path))
        }
    }

    fun removePendingFileReference(id: String) {
        updateCurrentConversation(
            homeState.copy(
                pendingFileReferences = homeState.pendingFileReferences.filterNot { it.id == id }
            )
        )
    }

    private fun resolveAndAttachFileReferences(
        resolver: () -> List<AgentFileReferenceGateway.Resolution>,
    ) {
        val ownerVersion = fileAttachmentOwnerVersion
        scope.launch(Dispatchers.IO) {
            val resolutions = resolver()
            val references = resolutions.mapNotNull { resolution ->
                (resolution as? AgentFileReferenceGateway.Resolution.Success)?.reference
            }
            val failures = resolutions.mapNotNull { resolution ->
                (resolution as? AgentFileReferenceGateway.Resolution.Failure)?.error
            }
            withContext(Dispatchers.Main) {
                if (ownerVersion != fileAttachmentOwnerVersion) {
                    Toast.makeText(appContext, appContext.getString(R.string.state_ui_conversation_switched_selected_path_not_added_5bf91e), Toast.LENGTH_SHORT).show()
                    return@withContext
                }
                val existingPaths = homeState.pendingFileReferences
                    .mapTo(mutableSetOf()) { it.reference.absolutePath }
                val additions = references
                    .distinctBy { it.absolutePath }
                    .filter { existingPaths.add(it.absolutePath) }
                    .map { reference ->
                        PendingFileReferenceUi(
                            id = "file-${UUID.randomUUID()}",
                            reference = reference,
                        )
                    }
                if (additions.isNotEmpty()) {
                    updateCurrentConversation(
                        homeState.copy(
                            pendingFileReferences = homeState.pendingFileReferences + additions
                        )
                    )
                }
                val message = when {
                    failures.size == 1 && references.isEmpty() -> failures.single().userMessage
                    failures.isNotEmpty() -> appContext.resources.getQuantityString(
                        R.plurals.file_references_added_with_failures,
                        failures.size,
                        additions.size,
                        failures.size,
                    )
                    additions.isEmpty() -> appContext.getString(R.string.state_ui_the_selected_path_has_been_added_42b432)
                    else -> null
                }
                if (message != null) {
                    Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private val AgentFileReferenceGateway.Error.userMessage: String
        get() = when (this) {
            AgentFileReferenceGateway.Error.UnsupportedDocumentProvider ->
                appContext.getString(R.string.capability_import_denied)
            AgentFileReferenceGateway.Error.InvalidPath -> appContext.getString(R.string.state_ui_please_enter_a_valid_absolute_path_6afeb4)
            AgentFileReferenceGateway.Error.PathNotFound -> appContext.getString(R.string.state_ui_the_path_does_not_exist_or_is_no_longer_accessib_a9776e)
            AgentFileReferenceGateway.Error.UnsupportedFileType -> appContext.getString(R.string.state_ui_only_supports_normal_files_and_folders_4adea0)
            AgentFileReferenceGateway.Error.TypeMismatch -> appContext.getString(R.string.state_ui_the_selected_project_type_does_not_match_3a5c49)
            AgentFileReferenceGateway.Error.RootUnavailable -> appContext.getString(R.string.state_ui_root_is_not_available_and_the_path_cannot_be_ver_fc4c81)
            AgentFileReferenceGateway.Error.AccessDenied -> appContext.getString(R.string.capability_import_denied)
            AgentFileReferenceGateway.Error.ImportFailed -> appContext.getString(R.string.capability_import_failed)
            AgentFileReferenceGateway.Error.ImportTooLarge -> appContext.getString(R.string.capability_import_too_large)
            AgentFileReferenceGateway.Error.ValidationTimedOut -> appContext.getString(R.string.state_ui_path_verification_timed_out_please_try_again_703687)
        }

    private val stopRequestedRunIds = mutableSetOf<String>()
    private val userStoppedRunIds = mutableSetOf<String>()

    fun stopCurrentRun() {
        // 并发时按当前会话定位它自己的 run，找不到再退回最近一次 run。
        val runId = runIdForSelectedConversation() ?: currentRunId ?: return
        stopRun(runId)
    }

    /** 停止指定 run；非当前会话的 run 也能停。 */
    private fun stopRun(runId: String) {
        if (!stopRequestedRunIds.add(runId)) return
        scope.launch(Dispatchers.IO) {
            AgentRuntimeClient(appContext, AndroidAgentLogger).cancelRun(runId)
        }
    }

    /** 暂停当前会话的运行：只发指令，暂停位由执行侧写进真源，再回灌到按钮。 */
    fun pauseCurrentRun() {
        val runId = runIdForSelectedConversation() ?: return
        scope.launch(Dispatchers.IO) {
            AgentRuntimeClient(appContext, AndroidAgentLogger).pauseRun(runId)
        }
    }

    /** 恢复当前会话已暂停的运行。 */
    fun resumeCurrentRun() {
        val runId = runIdForSelectedConversation() ?: return
        scope.launch(Dispatchers.IO) {
            AgentRuntimeClient(appContext, AndroidAgentLogger).resumeRun(runId)
        }
    }

    private fun isReplyRewrite(runId: String): Boolean =
        conversationsById[conversationIdForRun(runId)]?.roleplayMessages?.pendingRewrites?.containsKey(runId) == true

    private fun restoreRunEvents(runId: String, events: List<AgentEvent>) {
        if (isReplyRewrite(runId)) return
        // 恢复是完整快照：先清除同一 run 的旧投影，再一次发布，避免历史增量重复追加
        // 或中途的 Running 状态使已结束的思考重新展开、播放动画。
        Snapshot.withMutableSnapshot {
            flushPendingRunDelta(runId)
            updateMessages(runId, updateTimestamp = false) { messages ->
                runMessageProjector.resetForReplay(
                    runId = runId,
                    messages = messages,
                    replaySupplementIndexes = events.filterIsInstance<AgentEvent.UserSupplementReceived>()
                        .mapTo(mutableSetOf()) { it.index },
                )
            }
            events.forEach { event -> applyRunEvent(runId, event, persistSupplement = false) }
        }
    }

    private fun enqueueRunEvent(runId: String, event: AgentEvent) {
        if (event is AgentEvent.AssistantBlockDelta) {
            if (event.kind == AgentEvent.AssistantBlockKind.TOOL_CALL || event.delta.isEmpty()) return

            runEventCoalescer.append(runId, event)?.let { ready ->
                applyRunEvent(runId, ready)
            }
            scheduleRunDeltaFlush(runId)
            return
        }

        flushPendingRunDelta(runId)
        applyRunEvent(runId, event)
    }

    private fun scheduleRunDeltaFlush(runId: String) {
        if (runEventFlushJobs[runId]?.isActive == true) return
        runEventFlushJobs[runId] = scope.launch {
            delay(STREAM_UI_UPDATE_INTERVAL_MS)
            runEventFlushJobs.remove(runId)
            flushPendingRunDelta(runId)
        }
    }

    private fun flushPendingRunDelta(runId: String) {
        runEventFlushJobs.remove(runId)?.cancel()
        runEventCoalescer.flush(runId)?.let { event ->
            applyRunEvent(runId, event)
        }
    }

    private fun applyRunEvent(
        runId: String,
        event: AgentEvent,
        persistSupplement: Boolean = true,
    ) {
        if (isReplyRewrite(runId)) {
            if (event is AgentEvent.RunStarted && runId in stopRequestedRunIds) scope.launch(Dispatchers.IO) {
                AgentRuntimeClient(appContext, AndroidAgentLogger).cancelRun(runId)
            }
            return
        }
        // 请求侧裁剪提示：新一轮开始清空，本次确实裁了才显示。
        when (event) {
            is AgentEvent.RunStarted -> {
                historyTrimNotice = null
            }
            is AgentEvent.HistoryTrimmed -> historyTrimNotice = event.omittedUserRounds to event.omittedMessages
            else -> Unit
        }
        when (event) {
            is AgentEvent.UserSupplementReceived ->
                insertSupplementMessage(runId, event.index, event.text, persist = persistSupplement)
            is AgentEvent.RunStarted -> if (runId in stopRequestedRunIds) scope.launch(Dispatchers.IO) {
                AgentRuntimeClient(appContext, AndroidAgentLogger).cancelRun(runId)
            }
            // 流式增量与 Subagent 过程快照只刷新消息，不推进会话时间戳，也不重算侧栏摘要；其余可见变化两者都更新。
            is AgentEvent.AssistantBlockDelta,
            is AgentEvent.SubagentProgress,
            -> updateMessages(runId, updateTimestamp = false) { messages ->
                runMessageProjector.applyEvent(runId, event, messages)
            }
            is AgentEvent.UsageReceived -> {
                val usage = event.usage.toUi()
                if (!usage.isEmpty) conversationIdForRun(runId)?.let { id -> conversationsById[id]?.let {
                    updateConversation(id, it.copy(lastModelUsage = usage))
                    scope.launch(Dispatchers.IO) {
                        TokenUsageRecorder.recordUsage(
                            context = appContext,
                            conversationId = id,
                            modelId = it.modelId,
                            contextTokens = usage.contextTokens,
                            inputTokens = usage.inputTokens,
                            outputTokens = usage.outputTokens,
                            reasoningTokens = usage.reasoningTokens,
                            cachedTokens = usage.cachedTokens,
                            durationMs = usage.requestDurationMs,
                        )
                    }
                } }
                updateRunTrace(runId) { messages -> runMessageProjector.applyEvent(runId, event, messages) }
            }
            is AgentEvent.ContextCompaction -> {
                if (event.phase == AgentEvent.ContextCompaction.PHASE_COMPLETED) conversationIdForRun(runId)?.let { id ->
                    conversationsById[id]?.let { current -> updateConversation(id, current.copy(
                        lastModelUsage = current.lastModelUsage?.copy(contextTokens = null),
                    )) }
                }
                updateRunTrace(runId) { messages -> runMessageProjector.applyEvent(runId, event, messages) }
            }
            else -> updateRunTrace(runId) { messages -> runMessageProjector.applyEvent(runId, event, messages) }
        }
    }

    private fun applyRunResult(
        runId: String,
        result: AgentRuntimeWire.RunResult,
        acknowledgeRuntimeResult: Boolean = false,
    ) {
        flushPendingRunDelta(runId)
        val completedConversationId = conversationIdForRun(runId)
        val explicitlyStoppedByUser = userStoppedRunIds.remove(runId)
        val rewriting = result.operation == AgentRuntimeWire.OP_REWRITE_REPLY || isReplyRewrite(runId)
        stopRequestedRunIds.remove(runId)
        if (runId == currentRunId) {
            currentRunId = null
            currentRunJob = null
        }
        if (rewriting) {
            val conversationId = conversationIdForRun(runId)
            val existing = conversationsById[conversationId]
            if (conversationId != null && existing != null && result.contextSnapshotRef.isBlank()) {
                updateConversation(conversationId, RoleplayConversationReducer.applyRewrite(existing, runId, result))
            } else setConversationStreaming(runId, false)
            runMessageProjector.clearRun(runId)
            runConversationIds.remove(runId)
            refreshConversationSummaries()
            persistConversations(onSaved = if (acknowledgeRuntimeResult && result.contextSnapshotRef.isBlank()) {
                { AgentRuntimeClient(appContext, AndroidAgentLogger).ackResult(runId) }
            } else null)
            return
        }
        updateRunTrace(runId) { messages ->
            runMessageProjector.finishContextCompaction(runId,
                runMessageProjector.finalizeRun(runId, messages),
                if (result.ok) "上下文压缩完成" else result.error ?: "上下文压缩已停止")
        }
        if (result.contextSnapshotRef.isBlank()) {
            applyConversationHistoryResult(runId, result.transcript, result.contextSnapshot, !result.ok || result.contextSnapshot != null)
        }
        when {
            result.operation == AgentRuntimeWire.OP_COMPACT ||
                conversationsById[conversationIdForRun(runId)]?.isCompacting == true -> updateMessages(runId) { messages ->
                    AgentRunMessageProjector.mergeCompactionResultNotice(
                        runId = runId,
                        messages = messages,
                        ok = result.ok,
                        detail = if (result.ok) "上下文压缩完成"
                        else AgentErrorText.toChinese(result.error, "上下文压缩失败"),
                    )
                }
            else -> {
                val notice = when {
                    result.ok && result.content.isNotBlank() -> null
                    result.ok -> SystemNoticeCode.EmptyResult
                    result.error == LEGACY_STOPPED_ERROR || result.error == SYNTHETIC_STATUS_STOPPED ->
                        SystemNoticeCode.Stopped
                    else -> SystemNoticeCode.RuntimeFailed
                }
                updateMessages(runId) { messages ->
                    AgentRunMessageProjector.applyResult(
                        runId, messages, result.content, notice,
                        detail = result.error
                            .takeIf { notice == SystemNoticeCode.RuntimeFailed }
                            ?.let { raw -> AgentErrorText.toChinese(raw) },
                    )
                }
            }
        }
        setConversationStreaming(runId, false)
        conversationIdForRun(runId)?.let { id -> conversationsById[id]?.let {
            updateConversation(id, RoleplayConversationReducer.linkRun(it, runId))
        } }
        runMessageProjector.clearRun(runId)
        runConversationIds.remove(runId)
        refreshConversationSummaries()
        persistConversations(
            onSaved = if (acknowledgeRuntimeResult && result.contextSnapshotRef.isBlank()) {
                {
                    AgentRuntimeClient(appContext, AndroidAgentLogger).ackResult(runId)
                }
            } else {
                null
            }
        )
        if (completedConversationId != null && currentRunId == null) {
            val finishedState = conversationsById[completedConversationId]
            if (finishedState != null && finishedState.queuedMessages.isNotEmpty()) {
                val stoppedByUser = explicitlyStoppedByUser ||
                    (!result.ok && (result.error == LEGACY_STOPPED_ERROR || result.error == SYNTHETIC_STATUS_STOPPED))
                val (afterPopState, nextQueued) = AgentMessageQueueReducer.pollNextToSendAfterRun(
                    state = finishedState,
                    stoppedByUser = stoppedByUser,
                )
                updateConversation(completedConversationId, afterPopState, updateTimestamp = false)
                if (nextQueued != null) {
                    dispatchQueuedMessage(completedConversationId, nextQueued)
                }
            }
        }
    }

    private fun updateRunTrace(
        runId: String,
        transform: (List<AgentChatMessageUi>) -> List<AgentChatMessageUi>,
    ) {
        if (updateMessages(runId, transform = transform)) refreshConversationSummaries()
    }

    private fun insertSupplementMessage(
        runId: String,
        index: Int,
        text: String,
        persist: Boolean = true,
    ) {
        updateMessages(runId) { messages ->
            AgentPendingResultRecovery.mergeSupplements(
                runId = runId,
                supplements = listOf(
                    AgentUiHandoffPayload.Supplement(
                        index = index,
                        text = text,
                        createdAt = System.currentTimeMillis(),
                    )
                ),
                messages = messages,
            )
        }
        refreshConversationSummaries()
        if (persist) persistConversations()
    }

    private fun updateMessages(
        runId: String,
        updateTimestamp: Boolean = true,
        transform: (List<AgentChatMessageUi>) -> List<AgentChatMessageUi>,
    ): Boolean {
        val conversationId = conversationIdForRun(runId) ?: return false
        val state = conversationsById[conversationId] ?: return false
        val messages = transform(state.messages)
        // 投影对无可见变化的事件返回原列表；此时不推进会话时间戳，避免请求开始、轮次开始等事件改变排序。
        if (messages === state.messages) return false
        updateConversation(
            conversationId = conversationId,
            state = state.copy(messages = messages),
            updateTimestamp = updateTimestamp,
        )
        return true
    }

    private fun applyConversationHistoryResult(
        runId: String,
        additions: List<AgentModelClient.ConversationMessage>,
        snapshot: AgentContextSnapshot? = null,
        retainPendingSupplements: Boolean = snapshot != null,
    ) {
        val conversationId = conversationIdForRun(runId) ?: return
        val state = conversationsById[conversationId] ?: return
        val outcome = AgentRuntimeHistoryReducer.apply(state, runId, additions, snapshot, retainPendingSupplements)
        if (!outcome.alreadyApplied) updateConversation(conversationId, outcome.state)
    }

    private fun updateCurrentConversation(
        state: AgentChatHomeUiState,
        updateTimestamp: Boolean = true,
    ) {
        val conversationId = selectedConversationId
        if (conversationId == null) {
            homeState = state
        } else {
            updateConversation(conversationId, state, updateTimestamp = updateTimestamp)
        }
    }

    private fun moveCurrentDraftToNewConversation() {
        val draft = homeState
        selectedConversationId = null
        homeState = emptyChatState(defaultThinkingEnabled).copy(
            input = draft.input,
            thinkingEnabled = draft.reasoningEffort.enablesReasoning,
            reasoningEffort = draft.reasoningEffort,
            availableReasoningEfforts = currentReasoningCapabilities?.selectableEfforts.orEmpty(),
            pendingImages = draft.pendingImages,
            pendingFileReferences = draft.pendingFileReferences,
            modelId = draft.modelId,
        )
        conversationPaneState = conversationPaneState.copy(
            recentOpenedIds = recentOpenedIds,selectedConversationId = null)
    }

    private fun updateConversation(
        conversationId: String,
        state: AgentChatHomeUiState,
        updateTimestamp: Boolean = true,
    ) {
        // 占位会话的消息与上下文不在内存里：真有数据写进来时只接住草稿类瞬时字段，
        // 数据字段沿用占位并从库里补载，避免把空列表当新状态覆盖库内历史（#006）。
        val existing = conversationsById[conversationId]
        val safeState = if (existing != null && conversationId in partiallyLoadedConversationIds && state.messages.isNotEmpty()) {
            ensureConversationLoaded(conversationId)
            existing.copy(
                input = state.input,
                inputResetToken = state.inputResetToken,
                isCompacting = state.isCompacting,
                availableReasoningEfforts = state.availableReasoningEfforts,
                pendingImages = state.pendingImages,
                pendingFileReferences = state.pendingFileReferences,
                queuedMessages = state.queuedMessages,
                messageEdit = state.messageEdit,
                commandConfirm = state.commandConfirm,
            )
        } else {
            state
        }
        conversationsById = conversationsById + (conversationId to safeState)
        // 草稿落盘：输入框每次变化/清空都同步，切会话或重启后还能贴回来。
        ChatDraftStore.save(appContext, conversationId, safeState.input)
        if (updateTimestamp) {
            conversationUpdatedAt = conversationUpdatedAt + (conversationId to System.currentTimeMillis())
        }
        if (conversationId == selectedConversationId) {
            homeState = safeState
        }
    }

    private fun setConversationStreaming(runId: String, isStreaming: Boolean) {
        if (!isStreaming) RunControl.clearRun(runId, "ui.finished")
        val conversationId = conversationIdForRun(runId) ?: return
        val state = conversationsById[conversationId] ?: return
        updateConversation(conversationId, state.copy(isStreaming = isStreaming, isCompacting = state.isCompacting && isStreaming))
    }

    private fun conversationIdForRun(runId: String): String? = runConversationIds[runId]

    private fun conversationStateForRun(runId: String): AgentChatHomeUiState {
        val conversationId = conversationIdForRun(runId) ?: return emptyChatState(defaultThinkingEnabled)
        return conversationsById[conversationId] ?: emptyChatState(defaultThinkingEnabled)
    }

    private fun refreshConversationSummaries() {
        val summaries = conversationsById.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, AgentChatHomeUiState>> { conversationPinned[it.key] == true }
                    .thenByDescending { conversationUpdatedAt[it.key] ?: 0L },
            )
            .map { (id, state) ->
                val lastMessage = state.messages.lastOrNull()
                ConversationSummaryUi(
                    id = id,
                    title = conversationTitles[id].orEmpty().ifBlank {
                        appContext.getString(R.string.conversation_unnamed)
                    },
                    preview = when (lastMessage) {
                        is UserMessageUi -> AgentFileReferencePromptCodec
                            .parse(lastMessage.content)
                            .let { parsed ->
                                AgentFileReferencePolicy.titleSource(
                                    request = parsed.request,
                                    references = parsed.references,
                                )
                            }
                        is AgentMessageUi -> lastMessage.content.ifBlank {
                            appContext.getString(R.string.conversation_preview_reasoning)
                        }
                        is SystemNoticeMessageUi -> noticeText(lastMessage.code)
                        is ThinkingMessageUi -> appContext.getString(R.string.conversation_preview_reasoning)
                        is ToolActivityMessageUi -> appContext.getString(
                            R.string.conversation_preview_tool_call,
                            lastMessage.toolName,
                        )
                        else -> conversationPreviews[id]
                            ?: appContext.getString(R.string.conversation_preview_empty)
                    }.take(MAX_PREVIEW_CHARS),
                    timeLabel = if (state.isStreaming) {
                        appContext.getString(R.string.time_now)
                    } else {
                        conversationUpdatedAt[id]?.let { timestamp ->
                            ConversationTimeLabels.label(
                                timestampMillis = timestamp,
                                locale = appContext.resources.configuration.locales[0],
                                use24HourClock = DateFormat.is24HourFormat(appContext),
                                yesterdayLabel = appContext.getString(R.string.time_yesterday),
                                recentLabel = appContext.getString(R.string.time_recent),
                            )
                        } ?: appContext.getString(R.string.time_recent)
                    },
                    updatedAtMillis = conversationUpdatedAt[id] ?: 0L,
                    mode = ConversationModeUi.Chat,
                    characterName = state.roleplay?.characterName,
                    characterAvatarPath = state.roleplay?.avatarPath,
                    isPinned = conversationPinned[id] == true,
                    categoryId = conversationCategoryIds[id],
                    categoryName = conversationCategoryIds[id]?.let { cid -> conversationCategories.firstOrNull { it.id == cid }?.name },
                    isActiveRun = state.isStreaming,
                )
            }
        val query = conversationPaneState.searchQuery.trim()
        conversationPaneState = conversationPaneState.copy(
            recentOpenedIds = recentOpenedIds,
            selectedConversationId = selectedConversationId,
            categories = conversationCategories.sortedWith(compareBy({ it.sortOrder }, { it.createdAt })).map { it.id to it.name },
            conversations = if (query.isBlank()) {
                summaries
            } else {
                contentMatchCache.keys.retainAll(conversationsById.keys)
                summaries.filter { summary ->
                    summary.title.contains(query, ignoreCase = true) ||
                        summary.preview.contains(query, ignoreCase = true) ||
                        conversationContentMatches(summary.id, query)
                }
            },
        )
    }

    // 内容匹配按（查询词, 会话状态引用）缓存：刷新摘要时未变化的会话不重复全文扫描。
    private val contentMatchCache = mutableMapOf<String, ContentMatchCacheEntry>()

    private fun conversationContentMatches(conversationId: String, query: String): Boolean {
        val state = conversationsById[conversationId] ?: return false
        val cached = contentMatchCache[conversationId]
        if (cached != null && cached.query == query && cached.state === state) {
            return cached.matches
        }
        val matches = state.contentMatches(query) { code -> noticeText(code) }
        contentMatchCache[conversationId] = ContentMatchCacheEntry(query, state, matches)
        return matches
    }

    /**
     * #006：启动只把选中会话读进内存，其余会话只放元数据。
     * 用户切过来时在这里按需补载；失败保留占位状态，不阻塞其它操作。
     */
    private fun ensureConversationLoaded(conversationId: String) {
        if (conversationId !in partiallyLoadedConversationIds) return
        if (conversationLoadJobs[conversationId]?.isActive == true) return
        val placeholderInput = conversationsById[conversationId]?.input.orEmpty()
        val job = scope.launch {
            try {
                val loaded = runCatching {
                    AgentConversationStore.loadConversation(appContext, conversationId)
                }.getOrNull()
                if (loaded != null) {
                    withContext(Dispatchers.Main.immediate) {
                        applyLoadedConversation(conversationId, loaded, placeholderInput)
                    }
                }
            } finally {
                withContext(Dispatchers.Main.immediate) { conversationLoadJobs.remove(conversationId) }
            }
        }
        conversationLoadJobs[conversationId] = job
    }

    /** suspend 版本：运行时恢复要在写入前确认目标会话已完整加载。 */
    private suspend fun awaitConversationLoaded(conversationId: String) {
        if (conversationId !in partiallyLoadedConversationIds) return
        val placeholderInput = conversationsById[conversationId]?.input.orEmpty()
        val loaded = runCatching {
            AgentConversationStore.loadConversation(appContext, conversationId)
        }.getOrNull() ?: return
        withContext(Dispatchers.Main.immediate) {
            applyLoadedConversation(conversationId, loaded, placeholderInput)
        }
    }

    private fun applyLoadedConversation(
        conversationId: String,
        loaded: AgentChatHomeUiState,
        placeholderInput: String,
    ) {
        partiallyLoadedConversationIds -= conversationId
        val placeholder = conversationsById[conversationId]
        // 只有 messages / journal / history 是库里的真值；草稿、待发送图片、排队消息、
        // 编辑态这些都在内存里，补载必须原样留住，否则切会话会丢一次输入。
        val base = if (selectedConversationId == conversationId) homeState else placeholder
        val merged = if (base == null) {
            loaded
        } else {
            loaded.copy(
                input = base.input.ifBlank { placeholderInput },
                isStreaming = base.isStreaming,
                isCompacting = base.isCompacting,
                thinkingEnabled = base.thinkingEnabled,
                reasoningEffort = base.reasoningEffort,
                availableReasoningEfforts = base.availableReasoningEfforts,
                pendingImages = base.pendingImages,
                pendingFileReferences = base.pendingFileReferences,
                queuedMessages = base.queuedMessages,
                inputSyncVersion = base.inputSyncVersion,
                messageEdit = base.messageEdit,
                searchJump = base.searchJump,
                resumableRun = base.resumableRun,
                commandConfirm = base.commandConfirm,
                inputResetToken = base.inputResetToken,
                pendingMessageRefs = base.pendingMessageRefs,
            )
        }
        conversationsById = conversationsById + (conversationId to merged)
        conversationPersistence.markLoaded(
            conversationId,
            merged,
            conversationTitles[conversationId].orEmpty(),
            conversationUpdatedAt[conversationId] ?: 0L,
        )
        if (selectedConversationId == conversationId) {
            homeState = merged.copy(inputResetToken = homeState.inputResetToken)
            refreshConversationModel()
        }
        refreshConversationSummaries()
    }

    /** 会话被删：解除占位保护，保存时才会真正删掉这一行。 */
    private fun forgetLoadedConversation(conversationId: String) {
        partiallyLoadedConversationIds -= conversationId
        conversationLoadJobs.remove(conversationId)?.cancel()
        conversationPersistence.markDropped(conversationId)
    }

    private fun persistConversations(onSaved: (() -> Unit)? = null): Deferred<Boolean> {
        val selected = selectedConversationId
        val conversations = conversationsById
        val titles = conversationTitles
        val timestamps = conversationUpdatedAt
        return synchronized(persistenceLock) {
            // 未保存会阻止结果回执和 write-ahead 运行，由导入后的重载或失败后的补写收尾。
            if (persistencePaused) return CompletableDeferred(false)
            val previous = persistenceJob
            val persistence = conversationPersistence
            scope.async(Dispatchers.IO) {
                try {
                    previous?.join()
                    persistence.save(appContext,
                        AgentConversationStore.Snapshot(
                            selectedConversationId = selected,
                            conversationsById = conversations,
                            titles = titles,
                            updatedAt = timestamps,
                            partiallyLoadedConversationIds = partiallyLoadedConversationIds.toSet(),
                        ),
                        // #002：归属跟着会话行一起落库，新会话第一次保存就带上分类。
                        conversationCategoryIds.toMap(),
                    )
                    onSaved?.invoke()
                    true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (throwable: Throwable) {
                    AndroidAgentLogger.error(
                        "Agent conversation persistence failed: type=${throwable.safeLogType()}"
                    )
                    false
                }
            }.also { persistenceJob = it }
        }
    }

    private companion object {
        const val MAX_PINNED_CONVERSATIONS = 3
        const val MAX_TITLE_CHARS = 24
        const val MAX_PREVIEW_CHARS = 48
        const val LEGACY_STOPPED_ERROR = "已停止"
        const val SYNTHETIC_STATUS_STOPPED = "eta_status:stopped"
        // 数据状态以较粗粒度发布，文字显现由独立的帧时钟连续推进。
        const val STREAM_UI_UPDATE_INTERVAL_MS = 80L

        fun emptyChatState(thinkingEnabled: Boolean): AgentChatHomeUiState =
            AgentChatHomeUiState(
                messages = emptyList(),
                history = emptyList(),
                journal = emptyList(),
                input = "",
                isStreaming = false,
                thinkingEnabled = thinkingEnabled,
            )

        fun newConversationId(): String = "conv-${UUID.randomUUID()}"
    }
}

internal data class MessageRevisionImpact(
    val laterTurnCount: Int,
)

private data class ContentMatchCacheEntry(
    val query: String,
    val state: AgentChatHomeUiState,
    val matches: Boolean,
)

private fun archiveConversationId(source: String, conversationKey: String): String =
    AgentExternalArchivePayload.conversationId(source, conversationKey)

private fun agentBooleanForUi(key: String): Boolean {
    return Prefs.isEnabled(key)
}

/** 单条引用最多内联的字数；超出的部分让模型用 conversation_history 去取。 */
private const val MessageReferenceInlineLimit = 800

/** 一次最多引用几条，避免把上下文塞满。 */
private const val MessageReferenceLimit = 3
