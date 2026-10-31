package io.github.mangi.eta.agent.subagent

import android.os.SystemClock
import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentModelExecutionException
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.skill.SkillContext
import io.github.mangi.eta.agent.terminal.TerminalExecutionContext
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/**
 * 执行单次 `subagent` 工具调用：管理独立子上下文、过滤工具目录、实时镜像内部步骤，并向父 Agent 返回结构化摘要。
 */
internal class SubagentExecutor(
    private val conversationKey: String,
    private val parentController: AgentRunController,
    private val configProvider: () -> AgentModelClient.ModelConfig,
    /** 主 Agent 指定模型时的配置来源（modelId, effortWire）；未开启放行时不会被调用。 */
    private val configForOverride: (suspend (String?, String?) -> AgentModelClient.ModelConfig)? = null,
    private val capabilitiesProvider: () -> AgentToolCapabilities,
    private val baseToolExecutor: AgentModelClient.ToolExecutor,
    private val skillContext: SkillContext,
    private val memoryContext: AgentMemoryContext,
    private val mcpTools: JSONArray,
    private val executionContext: TerminalExecutionContext?,
    private val onProgress: (AgentEvent.SubagentProgress) -> Unit,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val latestSnapshots = ConcurrentHashMap<String, String>()

    fun latestSnapshotJson(toolCallId: String): String? = latestSnapshots[toolCallId]

    /** 同一主 Agent 下存活的子智能体计数（按对话隔离）；达到并行上限时拒绝新的派发。 */
    private val concurrencyKey: String get() = conversationKey

    fun execute(
        parentRound: Int,
        toolCall: AgentModelClient.ToolCall,
    ): AgentModelClient.ToolResult {
        val limit = runCatching {
            kotlinx.coroutines.runBlocking {
                io.github.mangi.eta.data.datastore.SettingsDataStore.subagentSettings()
            }.maxParallel
        }.getOrDefault(8)
        if (!SubagentConcurrency.tryAcquire(concurrencyKey, limit)) {
            return errorResult(
                "SUBAGENT_LIMIT_REACHED",
                "同一主 Agent 下已有 $limit 个子智能体在运行，本次派发被拒绝；" +
                    "请等现有子智能体结束，或在设置里调高并行上限。",
            )
        }
        return try {
            executeInternal(parentRound, toolCall)
        } finally {
            SubagentConcurrency.release(concurrencyKey)
        }
    }

    private fun executeInternal(
        parentRound: Int,
        toolCall: AgentModelClient.ToolCall,
    ): AgentModelClient.ToolResult {
        parentController.throwIfCancelled()
        val args = runCatching { JSONObject(toolCall.argumentsJson) }.getOrNull()
            ?: return errorResult("INVALID_ARGUMENTS", "subagent 参数必须是合法的 JSON 对象")

        val prompt = args.optString("prompt").trim()
        val description = args.optString("description").trim()
            .replace(Regex("\\s+"), " ")
            .take(120)
        if (prompt.isEmpty()) {
            return errorResult("INVALID_ARGUMENTS", "prompt 不能为空")
        }
        if (description.isEmpty()) {
            return errorResult("INVALID_ARGUMENTS", "description 不能为空")
        }

        val resumeId = args.optString("resume").trim()
        val requestedType = args.optString("subagent_type").trim().lowercase()
        val isResume = resumeId.isNotEmpty()

        if (isResume && requestedType.isNotEmpty()) {
            return errorResult(
                "RESUME_WITH_TYPE_UNAVAILABLE",
                "resume 续接已有 Subagent 时不得同时指定 subagent_type；请仅传递 resume 与 prompt。",
            )
        }

        val entry: SubagentSessionStore.Entry
        val priorHistory: List<AgentModelClient.ConversationMessage>
        if (isResume) {
            val existing = SubagentSessionStore.get(conversationKey, resumeId)
                ?: return errorResult(
                    "SUBAGENT_NOT_FOUND",
                    "未找到可续接的 Subagent：$resumeId（可能已过期或不属于当前会话）",
                )
            if (!existing.running.compareAndSet(false, true)) {
                return errorResult(
                    "SUBAGENT_ALREADY_RUNNING",
                    "Subagent $resumeId 当前正在运行中，无法并发续接",
                )
            }
            entry = existing
            priorHistory = existing.history
        } else {
            val catalog = SubagentProfileCatalog.listAll()
            val customRole = args.optString("role").trim()
            val customTools = args.optJSONArray("tools").toStringList()
            val profile = SubagentProfile.resolve(
                name = requestedType,
                catalog = catalog,
                customRole = customRole,
                customTools = customTools,
            )
            entry = SubagentSessionStore.create(conversationKey, profile)
            priorHistory = emptyList()
        }

        val profile = entry.profile
        val subagentId = entry.subagentId
        val childController = AgentRunController()
        val controllerBinding = parentController.register(childController::cancel)

        // 登记到注册表：只有占用灵屏的子智能体，才应该收到「人工接管结束」这类定向消息。
        SubagentRunRegistry.register(
            subagentId = subagentId,
            controller = childController,
            ownsScreen = profile.isToolAllowed("observe_screen"),
        )

        val requestedModel = args.optString("model").trim().takeIf {
            it.isNotBlank() && it.lowercase() !in setOf("default", "primary", "inherit", "auto")
        }
        val requestedEffort = args.optString("reasoning_effort").trim().takeIf { it.isNotBlank() }
        val allowModelPick = runCatching {
            kotlinx.coroutines.runBlocking {
                io.github.mangi.eta.data.datastore.SettingsDataStore.subagentSettings()
            }.allowModelSelection
        }.getOrDefault(false)
        if (allowModelPick && requestedModel != null) {
            val allowed = runCatching {
                kotlinx.coroutines.runBlocking {
                    io.github.mangi.eta.data.datastore.SettingsDataStore.subagentSettings()
                }.allowedModelIds
            }.getOrDefault(emptyList())
            // 模型 id 可能带服务商前缀（cn: / openai: …），比较时去掉前缀再比，避免勾选后仍被判未授权。
            val target = normalizeModelId(requestedModel)
            val matched = allowed.isEmpty() || allowed.any { normalizeModelId(it) == target }
            if (!matched) {
                SubagentSessionStore.releaseRunning(conversationKey, subagentId)
                controllerBinding.close()
                return errorResult(
                    "MODEL_NOT_AUTHORIZED",
                    "模型 $requestedModel 没有在子智能体设置里授权；请让用户在「授权模型」里勾选后再派发。",
                )
            }
        }
        val useOverride = allowModelPick && configForOverride != null &&
            (requestedModel != null || requestedEffort != null)
        val resolvedConfig = runCatching {
            if (useOverride) {
                kotlinx.coroutines.runBlocking { configForOverride!!.invoke(requestedModel, requestedEffort) }
            } else {
                configProvider()
            }
        }.getOrElse { throwable ->
            SubagentSessionStore.releaseRunning(conversationKey, subagentId)
            controllerBinding.close()
            return errorResult("SUBAGENT_CONFIG_ERROR", throwable.message ?: "无法加载 Subagent 模型配置")
        }
        val modelLabel = resolvedConfig.modelDisplayName.ifBlank { resolvedConfig.model }
        val tracker = StepTracker(
            subagentId = subagentId,
            subagentType = profile.name,
            description = description,
            prompt = prompt,
            model = modelLabel,
            resumed = isResume,
        )

        var lastEmitNanos = 0L
        fun publishSnapshot(force: Boolean) {
            val now = nanoTime()
            if (!force && now - lastEmitNanos < PROGRESS_THROTTLE_NANOS) return
            lastEmitNanos = now
            val encoded = tracker.snapshot().encode()
            latestSnapshots[toolCall.id] = encoded
            onProgress(
                AgentEvent.SubagentProgress(
                    round = parentRound,
                    toolCallId = toolCall.id,
                    subagentId = subagentId,
                    subagentType = profile.name,
                    snapshotJson = encoded,
                ),
            )
        }

        publishSnapshot(force = true)

        val childToolExecutor = AgentModelClient.ToolExecutor { subCall ->
            childController.throwIfCancelled()
            if (!profile.isToolAllowed(subCall.name)) {
                AgentModelClient.ToolResult(
                    content = JSONObject()
                        .put("ok", false)
                        .put("code", "SUBAGENT_TOOL_FORBIDDEN")
                        .put(
                            "message",
                            "当前 Subagent (${profile.name}) 不允许调用工具 ${subCall.name}",
                        )
                        .toString(),
                )
            } else {
                baseToolExecutor.execute(subCall)
            }
        }

        val subagentConfig = resolvedConfig.copy(
            systemPrompt = profile.buildSystemPrompt(),
            subagentTools = false,
            supportsSearchSuggestions = false,
        )

        // 两道硬约束：轮次上限与重复动作中止。取值来自设置页，0 表示关闭重复检测。
        val guard = runCatching {
            kotlinx.coroutines.runBlocking {
                io.github.mangi.eta.data.datastore.SettingsDataStore.subagentSettings()
            }
        }.getOrNull()
        val maxRounds = (guard?.maxRounds ?: 12).coerceIn(1, 200)
        val repeatAbort = (guard?.repeatAbortThreshold ?: 3).coerceIn(0, 50)
        var abortCode: String? = null
        var abortReason: String? = null
        var lastToolSignature: String? = null
        var repeatCount = 0

        return try {
            val response = AgentModelClient.complete(
                config = subagentConfig,
                prompt = prompt,
                toolExecutor = childToolExecutor,
                history = priorHistory,
                runController = childController,
                skillContext = skillContext,
                memoryContext = memoryContext,
                additionalTools = profile.filterTools(mcpTools),
                executionContext = executionContext,
                // 灵屏工具是否可见由 profile 决定（screen 允许，其余禁用）；
                // capabilities 沿用父任务的实际状态，让 screen 子代理与父共用同一块灵屏。
                capabilitiesProvider = capabilitiesProvider,
                sessionId = "$conversationKey-$subagentId",
                operationId = "${toolCall.id}-$subagentId",
                toolFilter = profile::filterTools,
                onEvent = { childEvent ->
                    if (childEvent is AgentEvent.ToolStarted && abortCode == null) {
                        if (childEvent.round > maxRounds) {
                            abortCode = "SUBAGENT_ROUND_LIMIT"
                            abortReason = "已到轮次上限（$maxRounds 轮），子智能体被自动中止；" +
                                "任务确实需要更多轮次时，可在子智能体设置里调高上限后重新派发。"
                            childController.cancel()
                        } else if (repeatAbort > 0) {
                            // 同一工具 + 同一参数连续出现，视为在原地打转。
                            val signature = childEvent.name + "|" + childEvent.argsPreview
                            if (signature == lastToolSignature) {
                                repeatCount += 1
                            } else {
                                lastToolSignature = signature
                                repeatCount = 1
                            }
                            if (repeatCount >= repeatAbort) {
                                abortCode = "SUBAGENT_REPEAT_ABORT"
                                abortReason = "同一动作已连续重复 $repeatCount 次（${childEvent.name}），" +
                                    "判断为无法推进，子智能体被自动中止并回报。"
                                childController.cancel()
                            }
                        }
                    }
                    val force = tracker.onChildEvent(childEvent)
                    publishSnapshot(force = force)
                },
            )
            val updatedHistory = priorHistory +
                AgentModelClient.buildUserHistoryMessage(prompt, emptyList()) +
                response.transcript
            SubagentSessionStore.complete(conversationKey, subagentId, updatedHistory)
            tracker.markCompleted(response.content)
            publishSnapshot(force = true)

            AgentModelClient.ToolResult(
                content = JSONObject()
                    .put("ok", true)
                    .put("subagent_id", subagentId)
                    .put("subagent_type", profile.name)
                    .put("description", description)
                    .put("model", modelLabel)
                    .put("status", SubagentActivitySnapshot.STATUS_COMPLETED)
                    .put("tool_calls", tracker.toolCount())
                    .put("summary", response.content)
                    .put(
                        "resume_hint",
                        "如需在此 Subagent 已有上下文基础上继续追问或深挖，可调用 subagent(resume=\"$subagentId\", prompt=\"...\", description=\"...\")。",
                    )
                    .toString(),
                command = latestSnapshots[toolCall.id],
            )
        } catch (throwable: Throwable) {
            val partial = (throwable as? AgentModelExecutionException)?.let { ex ->
                val partialHistory = priorHistory +
                    AgentModelClient.buildUserHistoryMessage(prompt, emptyList()) +
                    ex.transcript
                SubagentSessionStore.complete(conversationKey, subagentId, partialHistory)
                tracker.partialOutput()
            } ?: run {
                SubagentSessionStore.releaseRunning(conversationKey, subagentId)
                tracker.partialOutput()
            }

            // 命中硬约束时 childController 已被 cancel，这里要把它和「用户主动停止」区分开，
            // 否则回报会变成含糊的「已停止」，父 Agent 不知道该不该重试。
            val aborted = abortCode != null
            val cancelled = !aborted && (
                parentController.isCancelled ||
                    childController.isCancelled ||
                    throwable is AgentRunCancelledException
                )
            val errorMessage = when {
                aborted -> abortReason ?: "子智能体已中止"
                cancelled -> "已停止"
                else -> throwable.message ?: throwable.javaClass.simpleName
            }
            if (cancelled) {
                tracker.markCancelled(partial)
            } else {
                tracker.markFailed(errorMessage, partial)
            }
            publishSnapshot(force = true)

            if (parentController.isCancelled) {
                throw AgentRunCancelledException()
            }

            AgentModelClient.ToolResult(
                content = JSONObject()
                    .put("ok", false)
                    .put(
                        "code",
                        when {
                            aborted -> abortCode
                            cancelled -> "SUBAGENT_CANCELLED"
                            else -> "SUBAGENT_FAILED"
                        },
                    )
                    .put("subagent_id", subagentId)
                    .put("subagent_type", profile.name)
                    .put("description", description)
                    .put("model", modelLabel)
                    .put(
                        "status",
                        if (cancelled) {
                            SubagentActivitySnapshot.STATUS_CANCELLED
                        } else {
                            SubagentActivitySnapshot.STATUS_FAILED
                        },
                    )
                    .put("message", errorMessage)
                    .apply {
                        if (partial.isNotBlank()) put("partial_output", partial)
                        if (!cancelled) {
                            put(
                                "resume_hint",
                                "可调用 subagent(resume=\"$subagentId\", prompt=\"继续完成未完部分\", description=\"$description\") 恢复执行，或由主 Agent 直接接手。",
                            )
                        }
                    }
                    .toString(),
                command = latestSnapshots[toolCall.id],
            )
        } finally {
            SubagentRunRegistry.unregister(subagentId)
            controllerBinding.close()
        }
    }

    /** 去掉 "cn:" 这类服务商前缀，只比较模型本体。 */
    private fun normalizeModelId(raw: String): String =
        raw.trim().lowercase().substringAfterLast(':')

    fun executeProfileManagement(toolCall: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
        parentController.throwIfCancelled()
        val args = runCatching { JSONObject(toolCall.argumentsJson) }.getOrNull()
            ?: return errorResult("INVALID_ARGUMENTS", "subagent_profile 参数必须是合法的 JSON 对象")
        return when (val action = args.optString("action").trim().lowercase()) {
            "list" -> {
                val profiles = SubagentProfileCatalog.listAll()
                val items = JSONArray().also { array ->
                    profiles.forEach { profile ->
                        array.put(
                            JSONObject()
                                .put("name", profile.name)
                                .put("builtin", profile.builtin)
                                .put("description", profile.summary)
                                .put("when_to_use", profile.whenToUse)
                                .put("tools", JSONArray(profile.toolHighlights)),
                        )
                    }
                }
                AgentModelClient.ToolResult(
                    content = JSONObject()
                        .put("ok", true)
                        .put("action", "list")
                        .put("agents_dir", SubagentProfileCatalog.defaultAgentsDir().absolutePath)
                        .put("profiles", items)
                        .toString(),
                )
            }

            "get" -> {
                val name = args.optString("name").trim().lowercase()
                if (name.isEmpty()) return errorResult("INVALID_ARGUMENTS", "get 需要提供 name")
                val profile = SubagentProfileCatalog.get(name)
                    ?: return errorResult("SUBAGENT_NOT_FOUND", "未找到 Subagent Profile：$name")
                AgentModelClient.ToolResult(
                    content = JSONObject()
                        .put("ok", true)
                        .put("action", "get")
                        .put("name", profile.name)
                        .put("builtin", profile.builtin)
                        .put("description", profile.summary)
                        .put("when_to_use", profile.whenToUse)
                        .put("tools", JSONArray(profile.toolHighlights))
                        .put("disallowed_tools", JSONArray(profile.disallowedTools.toList()))
                        .put("prompt", profile.roleInstruction)
                        .apply { profile.filePath?.let { put("file_path", it) } }
                        .toString(),
                )
            }

            "save" -> {
                val name = args.optString("name").trim().lowercase()
                val description = args.optString("description").trim()
                val whenToUse = args.optString("when_to_use").trim()
                val prompt = args.optString("prompt").trim()
                val tools = args.optJSONArray("tools").toStringList()
                val disallowedTools = args.optJSONArray("disallowed_tools").toStringList()
                runCatching {
                    SubagentProfileCatalog.saveCustomProfile(
                        name = name,
                        description = description,
                        whenToUse = whenToUse,
                        tools = tools,
                        disallowedTools = disallowedTools,
                        prompt = prompt,
                    )
                }.fold(
                    onSuccess = { saved ->
                        AgentModelClient.ToolResult(
                            content = JSONObject()
                                .put("ok", true)
                                .put("action", "save")
                                .put("name", saved.name)
                                .put("description", saved.summary)
                                .put("tools", JSONArray(saved.toolHighlights))
                                .put("file_path", saved.filePath.orEmpty())
                                .put("message", "已保存自定义 Subagent \"${saved.name}\"，现在可直接通过 subagent(subagent_type=\"${saved.name}\", ...) 调用。")
                                .toString(),
                        )
                    },
                    onFailure = { error ->
                        errorResult("SUBAGENT_SAVE_FAILED", error.message ?: "保存自定义 Subagent 失败")
                    },
                )
            }

            "delete" -> {
                val name = args.optString("name").trim().lowercase()
                if (name.isEmpty()) return errorResult("INVALID_ARGUMENTS", "delete 需要提供 name")
                if (name in SubagentProfile.BUILTIN_NAMES) {
                    return errorResult("BUILTIN_SUBAGENT_IMMUTABLE", "内置 Subagent ($name) 不可删除")
                }
                val deleted = SubagentProfileCatalog.deleteCustomProfile(name)
                if (!deleted) {
                    return errorResult("SUBAGENT_NOT_FOUND", "未找到可删除的自定义 Subagent：$name")
                }
                AgentModelClient.ToolResult(
                    content = JSONObject()
                        .put("ok", true)
                        .put("action", "delete")
                        .put("name", name)
                        .toString(),
                )
            }

            else -> errorResult("INVALID_ARGUMENTS", "未知的 action：$action（可选：list, get, save, delete）")
        }
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length())
            .mapNotNull { optString(it)?.trim()?.takeIf(String::isNotEmpty) }
    }

    private fun errorResult(code: String, message: String): AgentModelClient.ToolResult =
        AgentModelClient.ToolResult(
            content = JSONObject()
                .put("ok", false)
                .put("code", code)
                .put("message", message)
                .toString(),
        )

    private class StepTracker(
        private val subagentId: String,
        private val subagentType: String,
        private val description: String,
        private val prompt: String,
        private val model: String,
        private val resumed: Boolean,
    ) {
        private val lock = Any()
        private val steps = mutableListOf<SubagentStepSnapshot>()
        private var status = SubagentActivitySnapshot.STATUS_RUNNING
        private var currentAction = "正在思考…"
        private val roundTexts = linkedMapOf<Int, StringBuilder>()
        private var finalOutput = ""
        private var errorText: String? = null

        fun onChildEvent(event: AgentEvent): Boolean = synchronized(lock) {
            when (event) {
                is AgentEvent.RoundStarted -> {
                    currentAction = if (event.round <= 1) "正在思考…" else "第 ${event.round} 轮思考…"
                    true
                }

                is AgentEvent.AssistantBlockDelta -> when (event.kind) {
                    AgentEvent.AssistantBlockKind.THINKING -> {
                        val stepId = "thinking-${event.round}-${event.index}"
                        val existingIndex = steps.indexOfFirst { it.id == stepId }
                        if (existingIndex >= 0) {
                            val current = steps[existingIndex]
                            steps[existingIndex] = current.copy(
                                detail = (current.detail + event.delta).takeLast(MAX_STEP_DETAIL_CHARS),
                                status = SubagentStepSnapshot.STATUS_RUNNING,
                            )
                        } else {
                            steps += SubagentStepSnapshot(
                                id = stepId,
                                kind = SubagentStepSnapshot.KIND_THINKING,
                                title = "思考",
                                detail = event.delta.takeLast(MAX_STEP_DETAIL_CHARS),
                                status = SubagentStepSnapshot.STATUS_RUNNING,
                            )
                        }
                        currentAction = "正在思考…"
                        false
                    }

                    AgentEvent.AssistantBlockKind.TEXT -> {
                        roundTexts.getOrPut(event.round) { StringBuilder() }.append(event.delta)
                        currentAction = "正在整理汇报…"
                        false
                    }

                    AgentEvent.AssistantBlockKind.TOOL_CALL -> false
                }

                is AgentEvent.AssistantBlockEnd -> when (event.kind) {
                    AgentEvent.AssistantBlockKind.THINKING -> {
                        val stepId = "thinking-${event.round}-${event.index}"
                        val existingIndex = steps.indexOfFirst { it.id == stepId }
                        if (existingIndex >= 0) {
                            val current = steps[existingIndex]
                            steps[existingIndex] = current.copy(
                                detail = (event.replacementContent ?: current.detail)
                                    .trim()
                                    .takeLast(MAX_STEP_DETAIL_CHARS),
                                status = SubagentStepSnapshot.STATUS_SUCCESS,
                            )
                        }
                        true
                    }

                    AgentEvent.AssistantBlockKind.TEXT -> {
                        if (event.replacementContent != null) {
                            roundTexts[event.round] = StringBuilder(event.replacementContent)
                        }
                        true
                    }

                    AgentEvent.AssistantBlockKind.TOOL_CALL -> false
                }

                is AgentEvent.ToolStarted -> {
                    finishRunningThinkingSteps()
                    val stepId = "tool-${event.round}-${event.toolCallId}"
                    val title = event.argsPreview.ifBlank { event.name }
                    val detail = event.command?.trim().orEmpty().take(MAX_STEP_DETAIL_CHARS)
                    if (steps.none { it.id == stepId }) {
                        steps += SubagentStepSnapshot(
                            id = stepId,
                            kind = SubagentStepSnapshot.KIND_TOOL,
                            title = title,
                            detail = detail,
                            status = SubagentStepSnapshot.STATUS_RUNNING,
                            toolName = event.name,
                        )
                    }
                    currentAction = title
                    true
                }

                is AgentEvent.ToolFinished -> {
                    val stepId = "tool-${event.round}-${event.toolCallId}"
                    val existingIndex = steps.indexOfLast { it.id == stepId }
                    val stepStatus = if (event.success == false) {
                        SubagentStepSnapshot.STATUS_FAILED
                    } else {
                        SubagentStepSnapshot.STATUS_SUCCESS
                    }
                    if (existingIndex >= 0) {
                        val current = steps[existingIndex]
                        val combinedDetail = listOfNotNull(
                            current.detail.takeIf { it.isNotBlank() },
                            event.resultSummary.trim().takeIf { it.isNotBlank() },
                        ).joinToString("\n").take(MAX_STEP_DETAIL_CHARS)
                        steps[existingIndex] = current.copy(
                            status = stepStatus,
                            detail = combinedDetail,
                        )
                    }
                    currentAction = "正在分析结果…"
                    true
                }

                else -> false
            }
        }

        fun markCompleted(content: String) = synchronized(lock) {
            finishRunningThinkingSteps()
            status = SubagentActivitySnapshot.STATUS_COMPLETED
            finalOutput = content.trim().take(MAX_OUTPUT_CHARS)
            val tools = toolCount()
            currentAction = if (tools > 0) "完成 · $tools 次工具调用" else "完成"
        }

        fun markFailed(error: String, partial: String) = synchronized(lock) {
            finishAllRunningSteps(SubagentStepSnapshot.STATUS_FAILED)
            status = SubagentActivitySnapshot.STATUS_FAILED
            errorText = error.take(1_000)
            if (partial.isNotBlank()) finalOutput = partial.take(MAX_OUTPUT_CHARS)
            currentAction = error.lineSequence().firstOrNull()?.take(80) ?: "执行失败"
        }

        fun markCancelled(partial: String) = synchronized(lock) {
            finishAllRunningSteps(SubagentStepSnapshot.STATUS_FAILED)
            status = SubagentActivitySnapshot.STATUS_CANCELLED
            errorText = "已停止"
            if (partial.isNotBlank()) finalOutput = partial.take(MAX_OUTPUT_CHARS)
            currentAction = "已停止"
        }

        fun partialOutput(): String = synchronized(lock) {
            if (finalOutput.isNotBlank()) return finalOutput
            roundTexts.values
                .map { it.toString().trim() }
                .filter { it.isNotEmpty() }
                .joinToString("\n\n")
                .takeLast(MAX_OUTPUT_CHARS)
        }

        fun toolCount(): Int = synchronized(lock) {
            steps.count { it.kind == SubagentStepSnapshot.KIND_TOOL }
        }

        fun snapshot(): SubagentActivitySnapshot = synchronized(lock) {
            val liveOutput = if (finalOutput.isNotBlank()) {
                finalOutput
            } else {
                roundTexts.values.lastOrNull()?.toString()?.trim().orEmpty().takeLast(MAX_OUTPUT_CHARS)
            }
            SubagentActivitySnapshot(
                subagentId = subagentId,
                subagentType = subagentType,
                description = description,
                prompt = prompt,
                model = model,
                resumed = resumed,
                status = status,
                currentAction = currentAction,
                steps = steps.takeLast(MAX_STEPS),
                output = liveOutput,
                error = errorText,
            )
        }

        private fun finishRunningThinkingSteps() {
            for (index in steps.indices) {
                val step = steps[index]
                if (step.kind == SubagentStepSnapshot.KIND_THINKING &&
                    step.status == SubagentStepSnapshot.STATUS_RUNNING
                ) {
                    steps[index] = step.copy(status = SubagentStepSnapshot.STATUS_SUCCESS)
                }
            }
        }

        private fun finishAllRunningSteps(terminalStatus: String) {
            for (index in steps.indices) {
                val step = steps[index]
                if (step.status == SubagentStepSnapshot.STATUS_RUNNING) {
                    steps[index] = step.copy(status = terminalStatus)
                }
            }
        }
    }

    companion object {
        private const val PROGRESS_THROTTLE_NANOS = 80_000_000L
        private const val MAX_STEP_DETAIL_CHARS = 2_000
        private const val MAX_OUTPUT_CHARS = 12_000
        private const val MAX_STEPS = 48
    }
}

/** 子智能体并行计数：同一主 Agent（按对话）下同时存活的子智能体总数。 */
internal object SubagentConcurrency {
    private val active = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()

    fun activeCount(key: String): Int = active[key]?.get() ?: 0

    fun tryAcquire(key: String, limit: Int): Boolean {
        if (limit <= 0) return false
        val counter = active.getOrPut(key) { java.util.concurrent.atomic.AtomicInteger() }
        while (true) {
            val current = counter.get()
            if (current >= limit) return false
            if (counter.compareAndSet(current, current + 1)) return true
        }
    }

    fun release(key: String) {
        val counter = active[key] ?: return
        if (counter.decrementAndGet() <= 0) active.remove(key, counter)
    }
}
