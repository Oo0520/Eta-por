package io.github.mangi.eta.data.provider

import io.github.mangi.eta.data.model.GeminiProviderSetting
import io.github.mangi.eta.data.model.AnthropicProviderSetting
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.ProviderSourceTypes

internal object BuiltinProviders {
    const val DEFAULT_SYSTEM_PROMPT =
        "你是 Eta，运行在 Android 设备上的 AI 助手。你可以回答问题、与用户交流，也可以通过当前可用的工具了解设备情况并执行操作。" +
            "回答使用用户的语言，简洁、直接、自然。"

    const val OPENAI_ID = "builtin-openai"
    const val ANTHROPIC_ID = "builtin-anthropic"
    const val BAILIAN_ID = "builtin-dashscope"
    const val DEEPSEEK_ID = "builtin-deepseek"
    const val KIMI_ID = "builtin-kimi"
    const val MIMO_ID = "builtin-mimo"
    const val MINIMAX_ID = "builtin-minimax"
    const val STEPFUN_ID = "builtin-stepfun"
    const val SILICONFLOW_ID = "builtin-siliconflow"
    const val OPENROUTER_ID = "builtin-openrouter"
    const val XAI_ID = "builtin-xai"
    const val GEMINI_ID = "builtin-gemini"
    const val ZHIPU_ID = "builtin-zhipu"
    const val VOLCENGINE_ID = "builtin-volcengine"
    const val QIANFAN_ID = "builtin-qianfan"
    const val HUNYUAN_ID = "builtin-hunyuan"
    const val LOCAL_ID = "builtin-local"

    /** 借用厂商助手（小布）自身模型；不联网、不走 API Key。 */
    const val BREENO_ID = "builtin-breeno"

    /** 订阅账号（Codex / Grok）登录入口，删掉就没有登录入口了。 */
    val SUBSCRIPTION_PROVIDER_IDS: List<String> = listOf(OPENAI_ID, XAI_ID)

    /**
     * 真正会写进数据库的内置 provider：全部大厂预置项。
     *
     * ensureBuiltInsMerged() 只播种这个集合里的条目，并删除不在集合内的历史内置项，
     * 所以新增内置 provider 必须同时登记到这里，否则重启后会被清掉。
     * 借用厂商（小布）不在此列——它单独靠 [BREENO_ID] 注入，且不出现在提供商列表。
     */
    val SEEDED_PROVIDER_IDS: List<String> by lazy {
        PROVIDERS
            .filterNot { ProviderAuthModes.isBorrowedVendor(it.authMode) }
            .map { it.id }
    }

    val PROVIDERS: List<ProviderSetting> = listOf(
        OpenAiCompatibleProviderSetting(
            id = BREENO_ID,
            name = "小布助手（借用）",
            baseUrl = "http://breeno.local/tap",
            sourceType = ProviderSourceTypes.CUSTOM,
            authMode = ProviderAuthModes.BREENO_TAP,
            isBuiltIn = true,
            sortOrder = 99,
            systemPrompt = DEFAULT_SYSTEM_PROMPT,
            models = listOf(
                Model(
                    id = "builtin-breeno-default",
                    modelId = "breeno",
                    displayName = "小布助手",
                    isBuiltIn = true,
                ),
            ),
        ),
        OpenAiCompatibleProviderSetting(
            id = OPENAI_ID,
            name = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            sourceType = ProviderSourceTypes.OPENAI,
            isBuiltIn = true,
            sortOrder = 0,
            systemPrompt = DEFAULT_SYSTEM_PROMPT,
            endpointMode = OpenAiEndpointMode.RESPONSES,
        ),
        AnthropicProviderSetting(
            id = ANTHROPIC_ID,
            name = "Anthropic",
            baseUrl = "https://api.anthropic.com",
            sourceType = ProviderSourceTypes.ANTHROPIC,
            isBuiltIn = true,
            sortOrder = 1,
            systemPrompt = DEFAULT_SYSTEM_PROMPT,
        ),
        OpenAiCompatibleProviderSetting(
            id = BAILIAN_ID,
            name = "阿里百炼",
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            sourceType = ProviderSourceTypes.BAILIAN,
            isBuiltIn = true,
            sortOrder = 2,
            systemPrompt = DEFAULT_SYSTEM_PROMPT,
        ),
        OpenAiCompatibleProviderSetting(
            id = DEEPSEEK_ID,
            name = "DeepSeek",
            baseUrl = "https://api.deepseek.com",
            sourceType = ProviderSourceTypes.DEEPSEEK,
            isBuiltIn = true,
            sortOrder = 3,
            systemPrompt = DEFAULT_SYSTEM_PROMPT,
        ),
        OpenAiCompatibleProviderSetting(
            id = KIMI_ID,
            name = "Kimi",
            baseUrl = "https://api.moonshot.cn/v1",
            sourceType = ProviderSourceTypes.MOONSHOT,
            isBuiltIn = true,
            sortOrder = 4,
            systemPrompt = DEFAULT_SYSTEM_PROMPT,
        ),
        OpenAiCompatibleProviderSetting(
            id = MIMO_ID,
            name = "MiMo",
            baseUrl = "https://api.xiaomimimo.com/v1",
            sourceType = ProviderSourceTypes.MIMO,
            isBuiltIn = true,
            sortOrder = 5,
            systemPrompt = DEFAULT_SYSTEM_PROMPT
        ),
        OpenAiCompatibleProviderSetting(
            id = MINIMAX_ID,
            name = "MiniMax",
            baseUrl = "https://api.minimaxi.com/v1",
            sourceType = ProviderSourceTypes.MINIMAX,
            isBuiltIn = true,
            sortOrder = 6,
            systemPrompt = DEFAULT_SYSTEM_PROMPT
        ),
        OpenAiCompatibleProviderSetting(
            id = STEPFUN_ID,
            name = "StepFun",
            baseUrl = "https://api.stepfun.com/v1",
            sourceType = ProviderSourceTypes.STEPFUN,
            isBuiltIn = true,
            sortOrder = 7,
            systemPrompt = DEFAULT_SYSTEM_PROMPT
        ),
        OpenAiCompatibleProviderSetting(
            id = SILICONFLOW_ID,
            name = "硅基流动",
            baseUrl = "https://api.siliconflow.cn/v1",
            sourceType = ProviderSourceTypes.SILICONFLOW,
            isBuiltIn = true,
            sortOrder = 8,
            systemPrompt = DEFAULT_SYSTEM_PROMPT
        ),
        OpenAiCompatibleProviderSetting(
            id = OPENROUTER_ID,
            name = "OpenRouter",
            baseUrl = "https://openrouter.ai/api/v1",
            sourceType = ProviderSourceTypes.OPENROUTER,
            isBuiltIn = true,
            sortOrder = 9,
            systemPrompt = DEFAULT_SYSTEM_PROMPT
        ),
        OpenAiCompatibleProviderSetting(
            id = XAI_ID,
            name = "xAI",
            baseUrl = "https://api.x.ai/v1",
            sourceType = ProviderSourceTypes.XAI,
            isBuiltIn = true,
            sortOrder = 10,
            systemPrompt = DEFAULT_SYSTEM_PROMPT,
        ),
        GeminiProviderSetting(
            id = GEMINI_ID,
            name = "Gemini",
            isBuiltIn = true,
            sortOrder = 11,
            systemPrompt = DEFAULT_SYSTEM_PROMPT,
        ),
        OpenAiCompatibleProviderSetting(
            id = ZHIPU_ID,
            name = "智谱 GLM",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            sourceType = ProviderSourceTypes.ZHIPU,
            isBuiltIn = true,
            sortOrder = 12,
            systemPrompt = DEFAULT_SYSTEM_PROMPT,
        ),
        OpenAiCompatibleProviderSetting(
            id = VOLCENGINE_ID,
            name = "火山方舟",
            baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
            sourceType = ProviderSourceTypes.VOLCENGINE,
            isBuiltIn = true,
            sortOrder = 13,
            systemPrompt = DEFAULT_SYSTEM_PROMPT,
        ),
        OpenAiCompatibleProviderSetting(
            id = QIANFAN_ID,
            name = "百度千帆",
            baseUrl = "https://qianfan.baidubce.com/v2",
            sourceType = ProviderSourceTypes.QIANFAN,
            isBuiltIn = true,
            sortOrder = 14,
            systemPrompt = DEFAULT_SYSTEM_PROMPT,
        ),
        OpenAiCompatibleProviderSetting(
            id = HUNYUAN_ID,
            name = "腾讯混元",
            baseUrl = "https://api.hunyuan.cloud.tencent.com/v1",
            sourceType = ProviderSourceTypes.HUNYUAN,
            isBuiltIn = true,
            sortOrder = 15,
            systemPrompt = DEFAULT_SYSTEM_PROMPT,
        ),
        /**
         * 本地/局域网代理（Ollama、LM Studio、llama.cpp、one-api 等）。
         * 这类端点普遍不校验 Key，运行时对本地地址放行空 Key；填了也照常带上。
         */
        OpenAiCompatibleProviderSetting(
            id = LOCAL_ID,
            name = "本地地址代理",
            baseUrl = "http://127.0.0.1:8080/v1",
            sourceType = ProviderSourceTypes.CUSTOM,
            isBuiltIn = true,
            sortOrder = 16,
            systemPrompt = DEFAULT_SYSTEM_PROMPT,
        ),
    )

    fun providerById(id: String): ProviderSetting? =
        PROVIDERS.firstOrNull { it.id == id }
}
