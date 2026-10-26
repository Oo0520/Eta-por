package io.github.mangi.eta.ui.screens.prompts

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.model.AgentPromptBuilder
import io.github.mangi.eta.agent.model.AgentToolCatalog
import io.github.mangi.eta.agent.model.AgentConversationToolCatalog
import io.github.mangi.eta.agent.model.AgentToolDirectory
import io.github.mangi.eta.agent.model.LocalToolProfileStore
import io.github.mangi.eta.agent.prompt.PromptConflict
import io.github.mangi.eta.agent.prompt.PromptConflictLevel
import io.github.mangi.eta.agent.model.SystemSegmentKind
import io.github.mangi.eta.agent.prompt.PromptCustomization
import io.github.mangi.eta.agent.skill.SkillContext
import org.json.JSONArray
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 自定义提示词三态：未启用 / 已启用但没内容 / 生效中。 */
@Composable
internal fun customizationStateText(profile: PromptCustomization): String = when {
    !profile.enabled -> stringResource(R.string.prompt_studio_state_disabled)
    !profile.hasContent -> stringResource(R.string.prompt_studio_state_no_content)
    else -> stringResource(R.string.prompt_studio_state_active)
}

@Composable
internal fun customizationContentText(profile: PromptCustomization): String = stringResource(
    R.string.prompt_studio_state_content,
    profile.baseSystemPrompt.length +
        profile.personaName.length +
        profile.personaStyle.length +
        profile.appendInstructions.length,
    profile.baseSystemPrompt.length,
    (profile.personaName.length + profile.personaStyle.length),
    profile.appendInstructions.length,
)

@Composable
internal fun ConflictRow(conflict: PromptConflict) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            text = conflict.title,
            style = MiuixTheme.textStyles.body2,
            color = if (conflict.level == PromptConflictLevel.ERROR) {
                MiuixTheme.colorScheme.error
            } else {
                MiuixTheme.colorScheme.onSurface
            },
        )
        Text(
            text = conflict.detail,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** 真实组装结果：模型配置取自 RuntimeConfigRepository（与聊天同一个来源），不是硬编码默认值。 */
internal data class EffectivePreview(
    val system: JSONArray,
    val tools: JSONArray,
    val model: String,
)

internal suspend fun buildEffectivePreview(
    context: android.content.Context,
    customization: PromptCustomization,
    policy: io.github.mangi.eta.agent.prompt.AgentToolPolicy = io.github.mangi.eta.agent.prompt.AgentToolPolicyStore.load(),
): EffectivePreview? = runCatching {
    val rawConfig = io.github.mangi.eta.data.repository.RuntimeConfigRepository.currentRuntimeConfig()
        ?: return@runCatching null
    // 工具开关不在 buildRuntimeConfig 里：ModelConfig 的默认值是「终端 false、敏感读取 false」，
    // 真实请求由入口按 Prefs 填充、再与本地权限取交集。预览照同一口径补一步，
    // 否则会整组少掉终端与文件工具（本次 34 → 21 就是差这一组 13 个）。
    val permissions = io.github.mangi.eta.agent.runtime.AgentRuntimePolicy.permissions(
        io.github.mangi.eta.config.Prefs.localAgentPreferences(),
    )
    val config = rawConfig.copy(
        terminalTools = permissions.terminalTools,
        browserTools = permissions.browserTools,
        deviceDirectTools = permissions.deviceDirectTools,
        deviceSensitiveReadTools = permissions.deviceSensitiveReadTools,
        deviceSensitiveActionTools = permissions.deviceSensitiveActionTools,
    )
    val capabilities = io.github.mangi.eta.agent.tool.AgentToolCapabilities.capture(context)
    // 记忆与技能上下文按真实来源取：缺了它们预览会少掉"记忆快照 / 技能索引"两段，
    // 也会少掉 memory_* 工具，用户就会看到"19 个"而实际发"34 个"。
    val memoryEnabled = io.github.mangi.eta.data.repository.AgentMemoryRepository.isEnabled()
    val memoryContext = if (memoryEnabled) {
        runCatching {
            io.github.mangi.eta.agent.memory.AgentMemoryContextBuilder.build(
                snapshot = io.github.mangi.eta.data.repository.AgentMemoryRepository.snapshot(),
                contextWindow = config.knownContextWindow,
            )
        }.getOrElse { io.github.mangi.eta.agent.memory.AgentMemoryContext.DISABLED }
    } else {
        io.github.mangi.eta.agent.memory.AgentMemoryContext.DISABLED
    }
    val skillContext = runCatching {
        SkillContext(
            io.github.mangi.eta.agent.skill.SkillRuntime.createIndexService(context)
                .listInstalledSkills()
                .filter { io.github.mangi.eta.agent.skill.SkillCompatibilityChecker.evaluate(it).available },
        )
    }.getOrElse { SkillContext.EMPTY }
    val system = AgentPromptBuilder.buildSystemMessages(
        config = config,
        skillContext = skillContext,
        memoryContext = memoryContext,
        rootAvailable = capabilities.rootAvailable,
        customization = customization,
    )
    // 真实请求还会追加两样：MCP 工具、会话历史工具（conversation_history）。
    // 少了它们预览会永远比实际少（上次 114 vs 115 就差分这一个会话工具）。
    val additionalTools = runCatching {
        org.json.JSONArray().also { tools ->
            io.github.mangi.eta.agent.mcp.McpRunSnapshot.load().appendModelTools(tools)
            tools.put(AgentConversationToolCatalog.schema())
        }
    }.getOrElse { org.json.JSONArray() }
    val tools = AgentToolDirectory.build(
        terminalTools = config.terminalTools,
        browserTools = config.browserTools,
        deviceDirectTools = config.deviceDirectTools,
        deviceSensitiveReadTools = config.deviceSensitiveReadTools,
        deviceSensitiveActionTools = config.deviceSensitiveActionTools,
        skillGitHubDiscovery = true,
        skillGitHubInstall = true,
        memoryTools = memoryContext.enabled,
        memoryWritable = true,
        capabilities = capabilities,
        localWebSearch = !config.usesHostedWebSearch,
        localProfile = LocalToolProfileStore.current(config.providerId, config.model),
        policy = policy,
        additionalTools = additionalTools,
    )
    EffectivePreview(system = system, tools = tools, model = config.model)
}.getOrNull()

internal fun formatPromptTime(timestamp: Long): String =
    if (timestamp <= 0L) "—" else SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))

internal fun toast(context: Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}

internal fun copyPromptText(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("eta-prompt", text))
    toast(context, context.getString(R.string.prompt_studio_copied))
}

@Composable
internal fun rememberContext(): Context = LocalContext.current

/** system 段的中文标签：概览预览与快照详情共用一份，避免两处叫法不一致。 */
@Composable
internal fun segmentLabel(kind: SystemSegmentKind): String = stringResource(
    when (kind) {
        SystemSegmentKind.REVIEW -> R.string.prompt_snapshot_segment_review
        SystemSegmentKind.IDENTITY -> R.string.prompt_snapshot_segment_identity
        SystemSegmentKind.TERMINAL -> R.string.prompt_snapshot_segment_terminal
        SystemSegmentKind.WEB -> R.string.prompt_snapshot_segment_web
        SystemSegmentKind.MEMORY -> R.string.prompt_snapshot_segment_memory
        SystemSegmentKind.SKILL -> R.string.prompt_snapshot_segment_skill
        SystemSegmentKind.CUSTOM_IDENTITY -> R.string.prompt_snapshot_segment_custom_identity
        SystemSegmentKind.CUSTOM_PERSONA -> R.string.prompt_snapshot_segment_custom_persona
        SystemSegmentKind.CUSTOM_INSTRUCTIONS -> R.string.prompt_snapshot_segment_custom_instructions
        SystemSegmentKind.BASE -> R.string.prompt_snapshot_segment_base
        SystemSegmentKind.OTHER -> R.string.prompt_snapshot_segment_other
    },
)

/** 「当前生效提示词」的数据来源：最近一份真实的主对话请求快照，不再另算一份。 */
internal data class EffectiveSnapshot(
    val file: java.io.File,
    val json: org.json.JSONObject,
) {
    val system: JSONArray get() = json.optJSONArray("system") ?: JSONArray()
    val tools: JSONArray get() = json.optJSONArray("tools") ?: JSONArray()
    val model: String get() = json.optString("model")
    val at: Long get() = json.optLong("at")
    val systemChars: Int
        get() = (0 until system.length()).sumOf { system.optJSONObject(it)?.optInt("chars") ?: 0 }
    val toolsChars: Int
        get() = (0 until tools.length()).sumOf { tools.optJSONObject(it)?.optInt("chars") ?: 0 }
}

/**
 * 取最近一份「主对话」快照。
 *
 * 只读真实请求落盘的那份，避免预览自己重建一套口径（历史上反复出现 19/114/115 对不上）。
 */
internal suspend fun loadLatestMainChatSnapshot(): EffectiveSnapshot? =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        io.github.mangi.eta.agent.model.AgentRequestSnapshot.list().asSequence()
            .take(16)
            .firstNotNullOfOrNull { file ->
                val json = io.github.mangi.eta.agent.model.AgentRequestSnapshot.read(file)
                    ?: return@firstNotNullOfOrNull null
                if (io.github.mangi.eta.agent.model.AgentSnapshotAnalysis.kindOf(json) ==
                    io.github.mangi.eta.agent.model.SnapshotKind.CHAT
                ) {
                    EffectiveSnapshot(file, json)
                } else {
                    null
                }
            }
    }
