package io.github.mangi.eta.agent.subagent

import io.github.mangi.eta.agent.model.AgentToolSchema
import org.json.JSONArray
import org.json.JSONObject

/**
 * 声明给主 Agent 的 `subagent` 与 `subagent_profile` 工具 Schema。
 */
/** 披露给主 Agent 的单个授权模型：id + 可用档位。 */
internal data class SubagentModelOption(
    val modelId: String,
    val efforts: List<String> = emptyList(),
    /** 上下文窗口（token）；null 表示未知。 */
    val contextWindow: Int? = null,
    /** 是否支持思考强度。 */
    val supportsThinking: Boolean = false,
) {
    /** 给模型看的紧凑描述：只陈述客观事实，不做强弱排序。 */
    fun describe(): String = buildString {
        append(modelId)
        val facts = buildList {
            contextWindow?.let { add("上下文 " + humanTokens(it)) }
            when {
                efforts.isNotEmpty() -> add("档位 " + efforts.joinToString("/"))
                // 只有 reasoning=true 但没有档位声明：思考固定开/关，传档位会被拒。
                supportsThinking -> add("思考固定，无档位")
            }
        }
        if (facts.isNotEmpty()) append("（").append(facts.joinToString("，")).append("）")
    }

    private fun humanTokens(tokens: Int): String = when {
        tokens >= 1_000_000 -> (tokens / 1_000_000).toString() + "M"
        tokens >= 1_000 -> (tokens / 1_000).toString() + "K"
        else -> tokens.toString()
    }
}

internal object AgentSubagentToolCatalog {
    const val TOOL_NAME = "subagent"
    const val PROFILE_TOOL_NAME = "subagent_profile"

    val names = setOf(TOOL_NAME, PROFILE_TOOL_NAME)

    fun appendSchemas(
        tools: JSONArray,
        profiles: List<SubagentProfile> = SubagentProfileCatalog.listAll(),
        authorizedModels: List<SubagentModelOption> = emptyList(),
    ) {
        tools.put(schema(profiles, authorizedModels))
        tools.put(profileToolSchema())
    }

    fun schema(
        profiles: List<SubagentProfile> = SubagentProfileCatalog.listAll(),
        authorizedModels: List<SubagentModelOption> = emptyList(),
    ): JSONObject {
        // 只告诉模型「必须是被授权的模型」而不给 id，它只能猜或者干脆不传——
        // 实测就是这个原因导致「允许主 Agent 选模型」看着没生效。
        val modelHint = if (authorizedModels.isEmpty()) {
            "Optional model id for THIS subagent; must be one of the models the user authorized in Subagent settings. " +
                "Only honored when the user enabled \"allow main agent to pick subagent models\" in Subagent settings."
        } else {
            "Optional model id for THIS subagent. Authorized models: " +
                authorizedModels.joinToString("；") { it.describe() } +
                ". 选择规则（主动执行，不必等用户提醒）：" +
                "① 先看上下文：要读大量材料、多文件或长日志时，只选上下文够大的（192K 装不下 1M 级别的材料）；" +
                "② 再看能否自动验证：能靠测试/格式校验判定对错的机械任务，用便宜模型或低档位省成本；" +
                "多步且出错不可回滚（写库、发消息、改动代码）的，给更稳的模型或更高档位；" +
                "注意：标了「思考固定，无档位」的模型不要传 reasoning_effort，会被拒绝；" +
                "③ 一次派多个子代理时，主动分散到不同模型，别全部堆在同一个上；" +
                "④ 想省成本就复用同一个模型 + 低档位，而不是每个子任务换一个。" +
                "Pick model + reasoning_effort for each subagent based on these constraints. " +
                "Omit reasoning_effort to use the model default; an unsupported value is rejected with the allowed list."
        }
        val profileLines = profiles.joinToString("\n") { profile ->
            val tag = if (profile.builtin) "" else " [custom]"
            "- ${profile.name}$tag: ${profile.whenToUse} (Tools: ${profile.toolHighlights.joinToString(", ")})"
        }
        val description = buildString {
            append("Launch a subagent in an isolated context to handle an autonomous subtask and return a concise summary. ")
            if (authorizedModels.isNotEmpty()) {
                append("When authorized models are available, ALWAYS pick a model explicitly for each `subagent` call — ")
                append("match it to the subtask (cheap/fast model for lookups and summarization, stronger model for reasoning and code). ")
                append("Also set `reasoning_effort` when the chosen model exposes more than one level. ")
                append("Example: {\"prompt\":\"...\",\"description\":\"...\",\"subagent_type\":\"explore\",\"model\":\"<one of the authorized ids>\",\"reasoning_effort\":\"low\"}. ")
                append("If you omit `model`, the runtime falls back to the per-type default, which may not fit the task.\n")
            }
            append("You can freely spawn as many specialized ephemeral subagents as needed for the conversation without saving them to disk, ")
            append("or use one of the preset types below. When multiple independent subtasks can run in parallel, issue multiple `subagent` tool calls in the same turn — the runtime executes them concurrently.\n\n")
            append("Writing the prompt:\n")
            append("- A fresh subagent starts with zero conversation history. State the goal clearly and include any known file paths, time ranges, package names, or constraints.\n")
            append("- Prefer resuming an existing subagent (pass its `resume` id) when continuing earlier work so it retains its prior context.\n")
            append("- For ad-hoc specialized roles (e.g. `bill-analyzer`, `log-auditor`, `schedule-checker`), pass the role name in `subagent_type` and optionally specify `role` or `tools` — it runs immediately as an ephemeral subagent.\n")
            append("- The subagent's output is only returned to you; summarize relevant findings for the user in your own reply.\n\n")
            append("Preset agent types (or pass any custom kebab-case `subagent_type` for an ephemeral subagent):\n")
            append(profileLines)
        }
        val properties = JSONObject()
            .put(
                "prompt",
                JSONObject()
                    .put("type", "string")
                    .put("minLength", 1)
                    .put("maxLength", 16_000)
                    .put("description", "Complete task instructions and necessary context for the subagent."),
            )
            .put(
                "description",
                JSONObject()
                    .put("type", "string")
                    .put("minLength", 1)
                    .put("maxLength", 120)
                    .put("description", "Short task label (3-5 words) displayed in the timeline UI."),
            )
            .put(
                "subagent_type",
                JSONObject()
                    .put("type", "string")
                    .put("maxLength", 48)
                    .put(
                        "description",
                        "Preset type (\"explore\", \"coder\", \"plan\", or a saved custom profile) OR any ad-hoc kebab-case role name (e.g. \"bill-analyzer\", \"apk-inspector\") to spawn an ephemeral specialized subagent on the fly. Defaults to \"explore\" when omitted. Do not set when `resume` is provided.",
                    ),
            )
            .put(
                "role",
                JSONObject()
                    .put("type", "string")
                    .put("maxLength", 2_000)
                    .put(
                        "description",
                        "Optional system role or persona instructions for an ad-hoc ephemeral subagent (only used for this subagent instance, not saved to disk).",
                    ),
            )
            .put(
                "tools",
                JSONObject()
                    .put("type", "array")
                    .put("items", JSONObject().put("type", "string").put("maxLength", 80))
                    .put("maxItems", 48)
                    .put(
                        "description",
                        "Optional tool allowlist for an ad-hoc subagent (e.g. [\"summarize_bills\", \"search_bills\"]). Omit to use the preset's tools or all non-GUI tools.",
                    ),
            )
            .put(
                "model",
                JSONObject()
                    .put("type", "string")
                    .put("maxLength", 120)
                    .put("description", modelHint),
            )
            .put(
                "reasoning_effort",
                JSONObject()
                    .put("type", "string")
                    .put(
                        "description",
                        "Optional reasoning effort for THIS subagent (off / low / medium / high …). " +
                            "Only honored when the user enabled \"allow main agent to pick subagent models\" in Subagent settings.",
                    ),
            )
            .put(
                "resume",
                JSONObject()
                    .put("type", "string")
                    .put("maxLength", 64)
                    .put(
                        "description",
                        "Optional subagent_id from a previous `subagent` result in this conversation to continue its existing context.",
                    ),
            )
        val parameters = JSONObject()
            .put("type", "object")
            .put("properties", properties)
            .put("required", JSONArray(listOf("prompt", "description")))
            .put("additionalProperties", false)
        return AgentToolSchema.function(
            name = TOOL_NAME,
            description = description,
            parameters = parameters,
        )
    }

    fun profileToolSchema(): JSONObject {
        val properties = JSONObject()
            .put(
                "action",
                JSONObject()
                    .put("type", "string")
                    .put("enum", JSONArray(listOf("list", "get", "save", "delete")))
                    .put("description", "Action to perform: list all subagent profiles, get one profile's details, save (create/update) a custom subagent profile in `agents/<name>.md`, or delete a custom profile."),
            )
            .put(
                "name",
                JSONObject()
                    .put("type", "string")
                    .put("maxLength", 48)
                    .put("description", "Subagent profile name in kebab-case (e.g. \"bill-analyzer\", \"log-auditor\"). Required for get, save, and delete."),
            )
            .put(
                "description",
                JSONObject()
                    .put("type", "string")
                    .put("maxLength", 240)
                    .put("description", "Required for save: concise one-line summary of what this custom subagent does."),
            )
            .put(
                "when_to_use",
                JSONObject()
                    .put("type", "string")
                    .put("maxLength", 500)
                    .put("description", "Optional for save: guidance on when the main agent should delegate to this subagent."),
            )
            .put(
                "tools",
                JSONObject()
                    .put("type", "array")
                    .put("items", JSONObject().put("type", "string").put("maxLength", 80))
                    .put("maxItems", 64)
                    .put("description", "Optional for save: list of allowed tool names for this subagent (e.g. [\"summarize_bills\", \"search_bills\", \"search_personal_orders\"]). Omit or pass [\"*\"] to allow all non-GUI tools."),
            )
            .put(
                "disallowed_tools",
                JSONObject()
                    .put("type", "array")
                    .put("items", JSONObject().put("type", "string").put("maxLength", 80))
                    .put("maxItems", 64)
                    .put("description", "Optional for save: list of tool names to explicitly disallow."),
            )
            .put(
                "prompt",
                JSONObject()
                    .put("type", "string")
                    .put("maxLength", 16_000)
                    .put("description", "Required for save: system instructions / role prompt for the custom subagent."),
            )
        val parameters = JSONObject()
            .put("type", "object")
            .put("properties", properties)
            .put("required", JSONArray(listOf("action")))
            .put("additionalProperties", false)
        return AgentToolSchema.function(
            name = PROFILE_TOOL_NAME,
            description = "Manage persistent reusable Subagent templates (`agents/<name>.md`). ONLY call `save` or `delete` when the user explicitly asks to save a reusable custom Subagent template for future sessions. For spawning specialized subagents to do work during a conversation, call `subagent` directly with an ad-hoc `subagent_type` and `role` without saving a profile.",
            parameters = parameters,
        )
    }
}
