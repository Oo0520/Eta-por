package io.github.mangi.eta.agent.display.dashboard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.runtime.AgentRuntimeClient
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.EtaTrace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一次待用户裁决的拦截：会话内审批卡与通知共用同一份，避免两个入口口径分裂。
 *
 * [token] 是唯一凭据：批准/拒绝必须带它回来，过期或不匹配一律作废，
 * 防止用户点开旧通知时误批了后面那一单。
 */
internal data class PendingApproval(
    val token: String,
    /** 来源：拦截产生（guard）还是模型主动提问（ask）。决定用户选完怎么继续。 */
    val source: String = SOURCE_GUARD,
    val risk: ConfirmRisk,
    val title: String,
    val fields: List<ConfirmField>,
    /** 静默档：只在聊天页留卡，不发通知、不振动、不抢焦点。 */
    val quiet: Boolean,
    /** 主体签名：同一主体在宽限期内不再重复询问。 */
    val subject: String = "",
    /** 同一主体被重复拦截的次数（>1 时卡上提示，不再重复通知）。 */
    val repeatCount: Int = 1,
    /** 卡片正文：说明「这一步要做什么、为什么问你」。 */
    val body: String = "",
    /** 可选项，至少两个。空表示用默认的批准/拒绝。 */
    val options: List<PromptOption> = emptyList(),
    /** 是否允许多选（当前 UI 只做单选，字段先留着给多选批次）。 */
    val allowMultiple: Boolean = false,
    /** 归属会话：新开会话不再继承上一会话的卡。 */
    val conversationKey: String = "",
    val createdAt: Long = System.currentTimeMillis(),
) {
    val riskLabel: String
        get() = when (risk) {
            ConfirmRisk.MONEY -> "资金"
            ConfirmRisk.PRIVACY -> "个人信息"
            ConfirmRisk.IRREVERSIBLE -> "不可撤销"
        }

    /** 资金类不提供「以后不问」：钱每次都该人批。 */
    val canRemember: Boolean get() = risk != ConfirmRisk.MONEY

    fun expired(now: Long = System.currentTimeMillis()): Boolean = now - createdAt > LIFETIME_MS

    companion object {
        /** 超过这个时间，用户再点就当作没看见：不批、不拒，只作废。 */
        const val LIFETIME_MS = 10 * 60 * 1000L

        const val SOURCE_GUARD = "guard"
        const val SOURCE_ASK = "ask"
    }
}

/**
 * 决策拦截器的执行侧：把 [ConfirmRiskDetector] 的判定结果变成「拦下来 + 等用户裁决」。
 *
 * 判定本身是纯函数，这里只负责四件事：
 * 1. 记住最近一次的屏上文本，供判定当上下文；
 * 2. 命中时生成 [PendingApproval]，交给聊天页内联卡与（按风险档位的）通知；
 * 3. 给调用方一个可直接返回给模型的 `NEED_CONFIRM` 结果，**不执行这次注入**；
 * 4. 用户裁决后，把结论通过 steering 通道回灌给运行中的 AI。
 *
 * 为什么批准不「重放旧坐标」：拦截到批准之间可能过了几十秒，快照早过期，
 * 盲目注入正是最危险的来源。正确语义是「给 AI 一个可以继续的许可」，
 * 由它自己重新观察再走一步——Muse / Grok Bot 也是这个模型。
 *
 * 取舍：宁可多拦一次，也不放行一次扣款。误拦的代价是模型多问一句，
 * 漏拦的代价是钱已经花出去。
 */
internal object AgentConfirmGuard {

    private const val CHANNEL = "eta_confirm_required"
    /** 静默渠道：IMPORTANCE_LOW，不响不振动不抬头，只在通知栏里等着被翻到。 */
    private const val CHANNEL_QUIET = "eta_confirm_quiet"
    private const val NOTIFICATION_ID = 8891

    /** 批准后同一主体在宽限期内直接放行：同一个按钮不该被连着问三遍。 */
    private const val GRANT_WINDOW_MS = 30 * 60 * 1000L

    /** 用户显式选择「24 小时内不再问」时的宽限期。同样只在内存里，重启即失效。 */
    private const val GRANT_LONG_MS = 24 * 60 * 60 * 1000L

    /** 强打扰预算：一小时内最多几条审批通知，超出降级为静默卡（卡仍在会话里）。 */
    private const val MAX_NOTIFY_PER_HOUR = 2

    /** subject -> 放行截止时间。纯内存，进程重启即失效，不需要撤销入口。 */
    private val grants = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** [0]=窗口起点，[1]=窗口内已发通知数。 */
    private val notifyWindow = LongArray(2)

    /**
     * 每个会话各挂一张卡，互不覆盖。
     *
     * 早先是全局单个 pending：A 会话有卡在等时，B 会话连提问都调不动
     * （`ask` 直接返回 PROMPT_PENDING），等于一张卡锁死全部对话。
     */
    private val mutablePendings = MutableStateFlow<List<PendingApproval>>(emptyList())

    /** 全部待答卡。 */
    val pendings: StateFlow<List<PendingApproval>> = mutablePendings.asStateFlow()

    /** 指定会话的待答卡（同一会话最多一张）。 */
    fun pendingFor(conversationKey: String): PendingApproval? =
        mutablePendings.value.firstOrNull {
            !it.expired() && (it.conversationKey.isBlank() || it.conversationKey == conversationKey)
        }

    /** 别的会话还挂着的卡：用来在当前会话里提示「那边有一条在等你」。 */
    fun pendingElsewhere(conversationKey: String): List<PendingApproval> =
        mutablePendings.value.filter {
            !it.expired() && it.conversationKey.isNotBlank() && it.conversationKey != conversationKey
        }

    private var tokenSeed = 0L

    /** 当前会话 key：由 UI 层在切换会话时写入，用来做卡片的会话归属。 */
    @Volatile
    private var currentConversation: String = ""

    /**
     * 启动时恢复未答的卡。
     *
     * 之前 pending 只在内存里，进程被 LMK 杀掉后卡片直接消失，用户回来什么也看不到。
     * 重启后把落盘那条读回来，过期（>10 分钟）的直接丢掉。
     */
    fun init(context: Context) {
        PendingPromptStore.init(context)
        val restored = PendingPromptStore.load().filter { !it.expired() }
        if (restored.isEmpty()) {
            PendingPromptStore.clear()
            return
        }
        if (mutablePendings.value.isEmpty()) mutablePendings.value = restored
    }

    /** UI 切会话时调用：只记录「现在看的是哪个」，卡的取舍交给 [pendingFor]/[pendingElsewhere]。 */
    fun bindConversation(conversationKey: String) {
        currentConversation = conversationKey
    }

    /**
     * 由 runId 反查它属于哪个会话。
     *
     * 卡片的归属必须是「发起这次调用/提问的会话」，不是「UI 现在正在看的会话」。
     * 之前用 currentConversation 当归属，A 会话弹卡时如果你正看着 B，
     * 卡就被打上 B 的标签、显示在 B、选完也发去 B（实测踩到）。
     */
    private fun conversationOfRun(runId: String): String {
        if (runId.isBlank()) return ""
        return runCatching {
            io.github.mangi.eta.agent.runtime.RunControl.state.value.conversationOfRun[runId].orEmpty()
        }.getOrDefault("")
    }

    /** 用户此刻正在看的会话（由 UI 的 bindConversation 写入）。 */
    val viewedConversation: String get() = currentConversation

    /** 卡片是否该在当前会话显示。 */
    fun visibleIn(conversationKey: String): Boolean = pendingFor(conversationKey) != null

    /** 屏上一个可点区域：文本 + 中心坐标。 */
    data class Spot(val label: String, val x: Int, val y: Int)

    /** 最近一次观测到的可点区域。只在内存里，不出进程。 */
    @Volatile
    private var spots: List<Spot> = emptyList()

    /** 最近一次观测到的全部可见文本。 */
    @Volatile
    private var texts: List<String> = emptyList()

    /** 这些动作会真的落到 App 上，注入前必须过一遍判定。 */
    private val GUARDED_ACTIONS = setOf(
        "tap", "tap_area", "long_press", "tap_smart", "tap_element", "long_press_element", "touch",
    )

    fun remember(nodes: List<Spot>, visible: List<String>) {
        spots = nodes
        texts = visible
    }

    fun shouldGuard(action: String): Boolean = action in GUARDED_ACTIONS

    /** 纯坐标点击：按最近的节点缓存反查这一下落在哪个控件文本上。 */
    fun labelAt(x: Int, y: Int): String? = spots.lastOrNull { spot ->
        kotlin.math.abs(spot.x - x) <= 48 && kotlin.math.abs(spot.y - y) <= 48
    }?.label

    /**
     * 判定并提醒。返回 null 表示放行；非 null 表示必须拦下，
     * 调用方应当直接把它作为工具结果返回，不要执行注入。
     */
    fun inspect(context: Context?, target: String?, action: String, runId: String = ""): ConfirmDraft? {
        // 先按控件文本判；判不出来再看整屏是不是收银台——
        // 拼多多/淘宝这类 App 的支付按钮常在自定义 View 里，无障碍拿到的 text 是空的，
        // 只靠控件文本会漏掉最关键的那一下（实测：点「立即支付」没拦，钱真扣了）。
        val draft = ConfirmRiskDetector.inspect(target, texts) ?: checkoutFallback() ?: return null
        EtaTrace.log("confirm.block", "action=$action risk=${draft.risk} target=${target?.take(40)}")
        // 个人信息类降级为静默：卡留在会话里，但不弹通知、不振动。
        // 资金与不可撤销仍然要高优先级喊人——这两类漏一次的代价不可挽回。
        return register(context, draft, target, action, runId)
    }

    /**
     * 非 GUI 工具（terminal / write_file …）的拦截入口。
     *
     * 与 GUI 共用同一张卡、同一套 grant 与回灌，只是判定换成
     * [ConfirmRiskDetector.inspectTool]：命令与参数就是证据，没有屏幕可看。
     */
    fun inspectTool(
        context: Context?,
        toolName: String,
        args: org.json.JSONObject,
        runId: String = "",
    ): ConfirmDraft? {
        val draft = ConfirmRiskDetector.inspectTool(toolName, args) ?: return null
        val target = when (toolName) {
            "terminal", "terminal_job", "terminal_daemon" -> args.optString("command").take(40)
            else -> args.optString("path").take(40)
        }
        return register(context, draft, target, toolName, runId)
    }

    /** GUI 与非 GUI 共用的登记路径：宽限期、去重、落卡、通知、暂停。 */
    private fun register(
        context: Context?,
        draft: ConfirmDraft,
        target: String?,
        action: String,
        runId: String = "",
    ): ConfirmDraft? {
        val subject = subjectOf(draft, target)
        // 归属优先取 run 自己的会话；查不到才退回 UI 当前会话（单会话场景下等价）。
        val owner = conversationOfRun(runId).ifBlank { currentConversation }
        val now = System.currentTimeMillis()
        // 刚批准过同一主体：宽限期内直接放行，模型可以继续，不再问第二遍。
        val grantedUntil = grants[subject]
        if (grantedUntil != null && grantedUntil > now) {
            EtaTrace.log("confirm.granted", "subject=$subject")
            return null
        }
        if (grantedUntil != null) grants.remove(subject)

        val quiet = draft.risk == ConfirmRisk.PRIVACY
        val options = buildList {
            add(PromptOption(PromptOptionId.APPROVE, "批准这一步", "按它说的做"))
            add(PromptOption(PromptOptionId.REJECT, "拒绝", "停下，让它换个做法"))
            add(PromptOption(PromptOptionId.MANUAL, "我自己来", "停下，你接管手动完成"))
            if (draft.risk != ConfirmRisk.MONEY) {
                add(PromptOption(PromptOptionId.REMEMBER, "这类以后不问", "24 小时内同一操作直接放行"))
            }
        }
        val body = when (draft.risk) {
            ConfirmRisk.MONEY -> "它准备付钱，这一步花出去就收不回来。"
            ConfirmRisk.PRIVACY -> "它准备提交你的个人信息。"
            ConfirmRisk.IRREVERSIBLE -> "这一步提交后无法撤销。"
        }
        // 同一会话里同一主体重复拦：不刷新卡片、不重复通知，只累加次数。
        // 别的会话的卡一律保留，不能因为这边又拦了一次就把它们冲掉。
        val previous = mutablePendings.value.firstOrNull { it.conversationKey == owner && !it.expired() }
        val pending = if (previous != null && previous.subject == subject) {
            previous.copy(repeatCount = previous.repeatCount + 1)
        } else {
            PendingApproval(
                token = nextToken(),
                risk = draft.risk,
                title = draft.title,
                fields = draft.fields,
                quiet = quiet,
                subject = subject,
                body = body,
                options = options,
                conversationKey = owner,
            )
        }
        mutablePendings.value = mutablePendings.value.filter {
            it.conversationKey != owner && !it.expired()
        } + pending
        PendingPromptStore.save(mutablePendings.value)
        // 刻意不 pause。
        // 早先的做法是命中即 pauseRun()，想着「别让模型继续跑」。实测两次翻车：
        // ① 模型拿到 NEED_CONFIRM 后本来就把这一轮收尾了，run 变 terminal，
        //    用户裁决时 resumeRun() 找不到活动 run，setPaused(false) 根本不执行，
        //    界面永远停在「已暂停 · 点继续恢复」；
        // ② 就算清了标记，运行线程仍卡在 pauseCondition.await()，模型照样不动。
        // 结论：拦截不需要暂停，它只需要「这一句不执行 + 把结论当成新消息推进下一轮」。
        if (pending.repeatCount == 1) {
            context?.let { runCatching { notify(it, pending) } }
        } else {
            PendingPromptStore.save(mutablePendings.value)
            EtaTrace.log("confirm.repeat", "subject=$subject count=${pending.repeatCount}")
        }
        return draft
    }

    /**
     * 用户批准。返回 false 表示这次裁决无效（token 过期/已被别的入口处理）。
     *
     * [conversationId] 用于把许可回灌到对应会话；留空则由运行时自行路由。
     */
    /**
     * 用户选了某个选项。
     *
     * 所有出口都收敛到这里：批准、拒绝、自己来、以后不问、以及 AI 主动提问的任意自定义选项，
     * 不允许各写一套回灌文案，否则「卡上说的」和「模型收到的」迟早对不上。
     */
    fun choose(context: Context?, token: String, optionId: String, conversationId: String = ""): String? {
        val current = takeIfCurrent(token) ?: return null
        clearNotification(context, token)
        val chosen = current.options.firstOrNull { it.id == optionId }
        val label = chosen?.label ?: optionId
        val detail = chosen?.description?.takeIf { it.isNotBlank() }?.let { "（$it）" } ?: ""

        // 两种来源现在走同一条路：拦截与提问都不再依赖 steer/resume，
        // 而是把「用户选了什么」作为一条新用户消息交给会话，由它开下一轮。
        // 这样既不会卡在暂停态，也不怕那一轮已经结束。

        val reply = when (optionId) {
            PromptOptionId.APPROVE -> {
                grantFor(current, remember = false)
                approvalText(current)
            }

            PromptOptionId.REMEMBER -> {
                grantFor(current, remember = true)
                approvalText(current)
            }

            PromptOptionId.REJECT ->
                "用户拒绝了「${current.title}」。请停止这个操作，不要换方式重试，并说明接下来可行的替代做法。"

            PromptOptionId.MANUAL ->
                "用户选择自己手动完成「${current.title}」。请立刻停下所有操作，" +
                    "说明当前进度和已经做过的改动，等他做完再继续。"

            else ->
                "用户对「${current.title}」选择了「$label」$detail。请按这个选择继续，不要再问一遍。"
        }
        // 只为清掉历史残留的暂停标记；已经没有 run 需要恢复。
        resume(context)
        AndroidAgentLogger.info("Confirm chose token=$token option=$optionId")
        EtaTrace.log("confirm.choose", "option=$optionId title=${current.title.take(40)}")
        return reply
    }

    /**
     * 多选提交：一次把勾中的几项一起回灌。
     *
     * 单选走的 [choose] 会立刻结束这张卡；多选必须等用户按「确认」，
     * 否则勾第一项就把卡收了，后面的选项没机会选。
     */
    fun chooseMany(
        context: Context?,
        token: String,
        optionIds: List<String>,
        conversationId: String = "",
    ): String? {
        val current = takeIfCurrent(token) ?: return null
        clearNotification(context, token)
        val labels = optionIds.mapNotNull { id ->
            current.options.firstOrNull { it.id == id }?.label
        }.distinct()
        if (labels.isEmpty()) return null
        val joined = labels.joinToString("、")
        val others = current.options.map { it.label }.filter { it !in labels }
        val hint = if (others.isEmpty()) "" else {
            "（未选：${others.joinToString("、")}。用户之后如果说要改，以他最新的说法为准。）"
        }
        val text = "用户对「${current.title}」选择了：$joined。请按这些选择继续，不要再问一遍。$hint"
        AndroidAgentLogger.info("Prompt chosen many token=$token count=${labels.size}")
        EtaTrace.log("confirm.chooseMany", "count=${labels.size} title=${current.title.take(40)}")
        resume(context)
        return text
    }

    /**
     * 自由输入：用户不选给好的选项，自己写答案。
     *
     * 选项卡永远只能覆盖模型想到的那几种；「其他」是它的兜底出口，
     * 没有它，用户遇到「都不合适」就只能关掉卡片重新打字，等于把选择白做了。
     */
    fun chooseFreeform(
        context: Context?,
        token: String,
        text: String,
        /** 多选时已勾中的项：自由输入是**补充**，不是把勾选覆盖掉。 */
        extraIds: List<String> = emptyList(),
        conversationId: String = "",
    ): String? {
        val current = takeIfCurrent(token) ?: return null
        clearNotification(context, token)
        val answer = text.trim().take(500)
        if (answer.isEmpty()) return null
        val extraLabels = extraIds.mapNotNull { id ->
            current.options.firstOrNull { it.id == id }?.label
        }.distinct()
        val reply = if (extraLabels.isEmpty()) {
            "用户对「${current.title}」自己填写了：$answer。请按这个回答继续，不要再问一遍。"
        } else {
            "用户对「${current.title}」勾选了「${extraLabels.joinToString("、")}」，另外补充：$answer。" +
                "请把勾选项和这段补充一起当作他的答案。"
        }
        AndroidAgentLogger.info("Prompt freeform token=$token")
        EtaTrace.log("confirm.freeform", "title=${current.title.take(40)}")
        resume(context)
        return reply
    }

    /**
     * 跳过：不回答，让模型自己往下走。
     *
     * 和「拒绝」的区别：拒绝是「这件事别做」，跳过是「这个问题我不答」。
     * 没有这个出口，用户遇到不想答的问题只能关掉卡片重开一段对话。
     */
    fun skip(context: Context?, token: String, conversationId: String = ""): String? {
        val current = takeIfCurrent(token) ?: return null
        clearNotification(context, token)
        val reply = "用户跳过了「${current.title}」，没有选也没有回答。" +
            "不要重复问同一个问题，按你认为最合理的方式继续；若这一步不可撤销，就停下来说明为什么需要他决定。"
        AndroidAgentLogger.info("Prompt skipped token=$token")
        EtaTrace.log("confirm.skip", "title=${current.title.take(40)}")
        resume(context)
        return reply
    }

    /** 兼容旧入口：批准。 */
    fun approve(context: Context?, token: String, conversationId: String = "", remember: Boolean = false): Boolean {
        if (mutablePendings.value.none { it.token == token }) return false
        choose(context, token, if (remember) PromptOptionId.REMEMBER else PromptOptionId.APPROVE, conversationId)
        return true
    }

    /** 兼容旧入口：拒绝。 */
    fun reject(context: Context?, token: String, conversationId: String = ""): Boolean {
        if (mutablePendings.value.none { it.token == token }) return false
        choose(context, token, PromptOptionId.REJECT, conversationId)
        return true
    }

    /**
     * AI 主动提问：带选项、暂停 run、等用户选。
     *
     * 与拦截的区别只在来源：拦截是系统判定的「危险动作」，这里是模型自己觉得
     * 「我拿不准，需要人来定」。两者共用同一张卡和同一套回灌。
     */
    fun ask(
        context: Context?,
        question: String,
        body: String,
        options: List<PromptOption>,
        allowMultiple: Boolean = false,
        runId: String = "",
    ): String {
        // 一次只留一张卡：直接覆盖会让上一问凭空消失，用户还没答就丢了。
        val owner = conversationOfRun(runId).ifBlank { currentConversation }
        val existing = mutablePendings.value.firstOrNull { it.conversationKey == owner && !it.expired() }
        if (existing != null) {
            return JSONObject()
                .put("ok", false)
                .put("code", "PROMPT_PENDING")
                .put("message", "已经有一张卡在等用户回答，不要重复提问；等他答完再问下一题。")
                .toString()
        }

        val clean = options.filter { it.label.isNotBlank() }
        if (question.isBlank() || clean.size < 2) {
            return JSONObject()
                .put("ok", false)
                .put("code", "INVALID_ASK")
                .put("message", "ask_user 至少需要一句问题和两个选项。")
                .toString()
        }
        val pending = PendingApproval(
            token = nextToken(),
            risk = ConfirmRisk.IRREVERSIBLE,
            title = question.take(60),
            fields = emptyList(),
            // 不再强制静默：用户不在 App 里时必须能收到提醒，否则他根本不知道有人在等回答。
            // 在不在前台由 notify 用 AppForeground 判断。
            quiet = false,
            subject = "",
            body = body,
            options = clean,
            allowMultiple = allowMultiple,
            conversationKey = owner,
            source = PendingApproval.SOURCE_ASK,
        )
        mutablePendings.value = mutablePendings.value.filter {
            it.conversationKey != owner && !it.expired()
        } + pending
        PendingPromptStore.save(mutablePendings.value)
        // 提问也要发通知：用户可能正在别的应用里，不提醒他根本不知道有人在等。
        // （早先只有拦截路径调了 notify，提问这条线一直是静默的。）
        context?.let { runCatching { notify(it, pending) } }
        // 刻意不 pause：本轮会拿到 AWAITING_USER 后自然收尾。
        // 挂住反而不对——用户选完时 run 往往已经结束，resume/steer 都会落空。
        AndroidAgentLogger.info("Ask user: ${question.take(40)} options=${clean.size}")
        return JSONObject()
            .put("ok", true)
            .put("code", "AWAITING_USER")
            .put("token", pending.token)
            .put("message", "问题已经展示给用户，本轮已暂停。用户选完会以消息形式告诉你他选了什么，不要自己猜答案，也不要重复提问。")
            .toString()
    }

    /** 批准回灌文案：字段带上，模型才知道批的是哪一单。 */
    private fun approvalText(current: PendingApproval): String {
        val detail = current.fields.joinToString(" · ") { "${it.label} ${it.value}" }.take(160)
        val suffix = if (detail.isBlank()) "" else "（$detail）"
        return "用户已确认「${current.title}」$suffix。请继续执行这一步，不要重复询问同一操作。"
    }

    private fun grantFor(current: PendingApproval, remember: Boolean) {
        if (current.subject.isBlank() || current.risk == ConfirmRisk.MONEY) return
        val window = if (remember) GRANT_LONG_MS else GRANT_WINDOW_MS
        grants[current.subject] = System.currentTimeMillis() + window
    }

    private fun pause(context: Context?) {
        context ?: return
        runCatching {
            AgentRuntimeClient(context.applicationContext, AndroidAgentLogger).pauseRun()
        }.onFailure { AndroidAgentLogger.warn("Confirm pause failed: ${it.message}") }
    }

    private fun resume(context: Context?) {
        // 1) 让还活着的 run 继续执行
        context?.let {
            runCatching {
                AgentRuntimeClient(it.applicationContext, AndroidAgentLogger).resumeRun()
            }.onFailure { e -> AndroidAgentLogger.warn("Confirm resume failed: ${e.message}") }
        }
        // 2) 无条件清掉暂停标记：那一轮很可能已经结束，
        //    requestResume 找不到活动 run 就不会回调 setPaused(false)，
        //    UI 会永远停在「已暂停」。
        io.github.mangi.eta.agent.runtime.RunControl.clearAllPaused("confirm.resolve")
    }

    /** 只把卡收起来（用户划掉/会话切走），不产生任何许可。 */
    fun dismiss(token: String) {
        takeIfCurrent(token)
    }

    /** 会话卡与通知共用：token 对得上、没过期，才允许消费，消费即清空。 */
    private fun takeIfCurrent(token: String): PendingApproval? {
        val current = mutablePendings.value.firstOrNull { it.token == token }
        if (current == null) {
            EtaTrace.log("confirm.stale", "token=$token")
            return null
        }
        // 不管成不成功，先把它从列表里摘掉
        mutablePendings.value = mutablePendings.value.filter { it.token != token }
        PendingPromptStore.save(mutablePendings.value)
        if (current.expired()) {
            EtaTrace.log("confirm.stale", "token=$token expired")
            return null
        }
        return current
    }

    private fun steer(context: Context?, text: String, conversationId: String) {
        val app = context?.applicationContext ?: return
        runCatching {
            AgentRuntimeClient(app, AndroidAgentLogger).steerRun(text, conversationId)
        }.onFailure {
            AndroidAgentLogger.warn("Confirm steer failed: ${it.message}")
        }
        EtaTrace.log("confirm.steer", text.take(60))
    }

    /** 每张卡一个通知 id：多会话时不能互相覆盖。 */
    private fun notificationIdOf(token: String): Int = NOTIFICATION_ID + (token.hashCode() and 0x7FFF)

    private fun clearNotification(context: Context?, token: String = "") {
        val app = context?.applicationContext ?: return
        runCatching {
            val manager = app.getSystemService(NotificationManager::class.java)
            if (token.isBlank()) manager?.cancel(NOTIFICATION_ID)
            else manager?.cancel(notificationIdOf(token))
        }
    }

    /** 主体签名：风险 + 卡片标题 + 控件文本。同一个「立即支付」按钮落在这里。 */
    private fun subjectOf(draft: ConfirmDraft, target: String?): String =
        draft.risk.name + "|" + draft.title + "|" + target.orEmpty().trim().take(24)

    /** 一小时窗口内的通知预算；超出就只留会话卡，不再响人。 */
    private fun takeNotifyBudget(): Boolean = synchronized(notifyWindow) {
        val now = System.currentTimeMillis()
        if (now - notifyWindow[0] > 3_600_000L) {
            notifyWindow[0] = now
            notifyWindow[1] = 0
        }
        if (notifyWindow[1] >= MAX_NOTIFY_PER_HOUR) return@synchronized false
        notifyWindow[1] += 1
        true
    }

    /** 清空宽限期（设置页「撤销批准记忆」用；当前没有 UI 入口）。 */
    fun clearGrants() {
        grants.clear()
    }

    private fun nextToken(): String {
        tokenSeed += 1
        return "guard-" + System.currentTimeMillis() + "-" + tokenSeed
    }

    private val amountPattern = Regex("""(?:¥|￥)\s?\d+(?:[.,]\d{1,2})?|\d+(?:[.,]\d{1,2})?\s?元""")

    /**
     * 页面级兜底：拿不到控件文本时，用整屏特征判。
     * 只在「金额 + 支付动作词」同时出现时成立，避免把普通浏览页也拦下来。
     */
    private fun checkoutFallback(): ConfirmDraft? {
        val all = texts.joinToString(" ")
        if (all.isBlank()) return null
        val amount = amountPattern.find(all)?.value ?: return null
        val payWord = ConfirmRiskDetector.irreversibleWords.firstOrNull { all.contains(it) } ?: return null
        val fields = buildList {
            add(ConfirmField("动作", payWord))
            add(ConfirmField("金额", amount))
            texts.firstOrNull { it.contains("实付") || it.contains("应付") || it.contains("合计") }
                ?.take(40)?.let { add(ConfirmField("订单", it)) }
            texts.firstOrNull { it.contains("收货") || it.contains("地址") }
                ?.take(40)?.let { add(ConfirmField("收货", it)) }
        }
        EtaTrace.log("confirm.checkout", "页面级命中 word=$payWord amount=$amount")
        return ConfirmDraft(ConfirmRisk.MONEY, "确认支付", fields)
    }

    /** 组装成给模型的回执：明确「没点」以及为什么没点，避免它换个坐标硬来。 */
    fun blockedResult(draft: ConfirmDraft): String = JSONObject()
        .put("ok", false)
        .put("code", "NEED_CONFIRM")
        .put("risk", draft.risk.name)
        .put("title", draft.title)
        .put(
            "message",
            "这一步涉及" + when (draft.risk) {
                ConfirmRisk.MONEY -> "资金支出"
                ConfirmRisk.PRIVACY -> "个人信息"
                ConfirmRisk.IRREVERSIBLE -> "不可撤销的提交"
            } + "，已拦下，没有执行。已给用户发提醒：请让用户自己确认或接管，不要换坐标重试。",
        )
        .put(
            "fields",
            JSONArray().apply {
                draft.fields.forEach { field ->
                    put(JSONObject().put("label", field.label).put("value", field.value))
                }
            },
        )
        .toString()

    private fun notify(context: Context, pending: PendingApproval) {
        // 静默档不打扰人，也就不必要求通知权限：没有权限时直接跳过，会话卡还在。
        if (pending.quiet) {
            AndroidAgentLogger.info("Confirm pending (quiet) risk=${pending.risk} title=${pending.title}")
            return
        }
        // 人就在 App 里：审批卡已经在会话末尾，再叠一条通知只是噪音。
        if (io.github.mangi.eta.core.AppForeground.isForeground) {
            AndroidAgentLogger.info("Confirm pending (in-app) risk=${pending.risk} title=${pending.title}")
            return
        }
        // 打扰预算只约束「拦截类」：那类可能一分钟内连拦好几次。
        // 提问类（ask_user）是模型等你拍板才发起的，一小时内本来就没几条，
        // 用预算把它静默掉等于让对话卡死——实测确实会被前面的拦截把额度吃光。
        if (pending.source != PendingApproval.SOURCE_ASK && !takeNotifyBudget()) {
            AndroidAgentLogger.info("Confirm notify budget exhausted risk=${pending.risk} title=${pending.title}")
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            AndroidAgentLogger.warn("Confirm notification skipped: POST_NOTIFICATIONS not granted")
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                context.getString(R.string.confirm_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.confirm_channel_desc)
                enableVibration(true)
            },
        )
        val token = pending.token
        // 通知正文要能看懂「在等什么」：有字段列字段，纯提问就列选项。
        val summary = if (pending.fields.isNotEmpty()) {
            pending.fields.joinToString(" · ") { "${it.label} ${it.value}" }.take(120)
        } else {
            pending.options.joinToString(" / ") { it.label }.take(120)
        }
        val title = if (pending.source == PendingApproval.SOURCE_ASK) {
            "Eta 有问题要问你"
        } else {
            context.getString(R.string.confirm_notify_title, pending.title)
        }
        // 证据图：从当前灵屏真帧裁一块，确认页展示真实画面而不是生成式卡片。
        val preview = runCatching { AgentConfirmActivity.capturePreview(context, token) }.getOrDefault("")
        // 提问的答案只能在会话卡上选，半屏确认页帮不上忙：直接进**那一张卡所属的会话**。
        // 带着会话 id 走 ACTION_OPEN_CONVERSATION_ID，MainActivity 会切到对应会话；
        // 用户在通知上长按/下拉进小窗，就能一边看别的应用一边把这一题答了。
        val askIntent = Intent(context, io.github.mangi.eta.ui.MainActivity::class.java)
            .setAction(io.github.mangi.eta.ui.MainActivity.ACTION_OPEN_CONVERSATION_ID)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(
                io.github.mangi.eta.ui.MainActivity.EXTRA_CONVERSATION_ID,
                pending.conversationKey,
            )
        val confirmIntent = Intent(context, AgentConfirmActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val open = PendingIntent.getActivity(
            context,
            notificationIdOf(token),
            (if (pending.source == PendingApproval.SOURCE_ASK) askIntent else confirmIntent)
                // 跳转必须带上归属会话：不然只会停在上次那个会话，
                // 卡片按 conversationKey 过滤后就消失，用户看到「跳了但没东西」。
                .putExtra(io.github.mangi.eta.ui.MainActivity.EXTRA_CONVERSATION_KEY, pending.conversationKey)
                .putExtra(AgentConfirmActivity.EXTRA_TOKEN, token)
                .putExtra(AgentConfirmActivity.EXTRA_TITLE, pending.title)
                .putExtra(AgentConfirmActivity.EXTRA_RISK, pending.risk.name)
                .putExtra(
                    AgentConfirmActivity.EXTRA_FIELDS,
                    pending.fields.joinToString("|") { "${it.label}=${it.value}" },
                )
                .putExtra(AgentConfirmActivity.EXTRA_PREVIEW_PATH, preview),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            notificationIdOf(token),
            Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(summary.ifBlank { context.getString(R.string.confirm_notify_body) })
                .setStyle(Notification.BigTextStyle().bigText(summary))
                .setCategory(Notification.CATEGORY_REMINDER)
                // heads-up（悬浮通知）：不锁屏也能直接看到，且不会把当前应用顶走。
                .setPriority(Notification.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build(),
        )
        AndroidAgentLogger.info("Confirm required risk=${pending.risk} fields=${pending.fields.size}")
    }
}
