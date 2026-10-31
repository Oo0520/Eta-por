package io.github.mangi.eta.agent.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.overlay.toolDisplayNameResource
import io.github.mangi.eta.agent.voice.EtaAssistantOverlayService
import io.github.mangi.eta.core.EtaTrace
import io.github.mangi.eta.ui.MainActivity

/**
 * 长任务里程碑汇报——**每个会话一张流体云卡**。
 *
 * 并发的会话各自占一条通知（ID 由 [SessionNotificationIds] 按会话分配）：
 * 卡片标题是那个会话的标题，正文是它自己的「已跑多久 · 在做什么」，
 * 三个动作（暂停／继续、停止、阶段进度）都带自己的 runId，互不牵连。
 *
 * Android 16 起按「实时活动」（live update）发布，ColorOS 放进流体云；
 * 系统实测允许多条同时提升。开启「流体云常驻」后，空闲时额外保留一条（ID 4772）。
 */
internal object RunProgressNotifier {

    private const val CHANNEL = "eta_live_progress"

    /** 旧渠道当初建成 LOW，系统不允许应用事后提高重要性，只能弃用。 */
    private const val LEGACY_CHANNEL = "eta_run_progress"

    private const val TICK_MS = 10_000L

    /** 兜底：这么久没有任何事件，就当这个会话已经哑了，撤掉它那一张。 */
    private const val IDLE_STOP_MS = 30 * 60_000L

    /** Android 16（API 36）起支持 promoted ongoing 提升。 */
    private const val LIVE_UPDATE_SDK = 36

    private const val ACTION_PAUSE_REQUEST = 2
    private const val ACTION_STOP_REQUEST = 3

    /** 单个会话的卡片状态：所有字段都是这一张卡自己的。 */
    private class RunCard(
        val runId: String,
        val conversationKey: String,
        var title: String,
        val startedAt: Long = System.currentTimeMillis(),
    ) {
        var step = "正在处理"
        var actionShort = "处理中"
        var stage = 1
        var lastEventAt = startedAt

        /** 卡片身份：优先会话，没有会话 key（系统入口）时退回 runId。 */
        val key: String get() = conversationKey.ifBlank { runId }
    }

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var appContext: Context? = null

    /** 会话 key → 卡片。LinkedHashMap 保持启动顺序，撤卡时不动别人。 */
    private val cards = LinkedHashMap<String, RunCard>()

    /** 虚拟屏接管的那几条会话：只有它们让位给仪表盘，其余照常发卡。 */
    private val delegatedRuns = LinkedHashSet<String>()

    private var active = false
    private var idle = false
    private var runningCount = 0
    private var concurrencyMax = 1

    private fun isMainThread(): Boolean = Looper.myLooper() == Looper.getMainLooper()

    private val tick = object : Runnable {
        override fun run() {
            if (!active) return
            val now = System.currentTimeMillis()
            cards.values
                .filter { now - it.lastEventAt > IDLE_STOP_MS }
                .forEach { stale -> removeRun(stale.runId, pinned = false) }
            post()
            if (active) handler.postDelayed(this, TICK_MS)
        }
    }

    fun start(context: Context) {
        if (!isMainThread()) {
            handler.post { start(context) }
            return
        }
        appContext = context.applicationContext
        active = true
        idle = false
        handler.removeCallbacks(tick)
        handler.post(tick)
        // 有任务了：撤掉空闲常驻卡，别让它和会话卡并排挂两条；然后立刻画一次，
        // 否则第一张卡要等 10 秒 tick 才出现。
        val app = appContext
        if (app != null) {
            runCatching { manager(app).cancel(SessionNotificationIds.IDLE_NOTIFICATION_ID) }
            post()
        }
        EtaTrace.log("run.progress.start", "开始汇报运行进度（多卡）")
    }

    /** 虚拟屏会话在跑时，只让这一张卡让位给仪表盘，其余会话不受影响。 */
    fun delegateToVirtualScreen(runId: String, value: Boolean) {
        if (!isMainThread()) {
            handler.post { delegateToVirtualScreen(runId, value) }
            return
        }
        if (runId.isBlank()) return
        val changed = if (value) delegatedRuns.add(runId) else delegatedRuns.remove(runId)
        if (changed && !value) post()
    }

    /** 记住这次运行属于哪个会话：卡片身份、点击落点、通知 ID 都按它定。 */
    fun noteConversation(runId: String, conversationKey: String, title: String = "") {
        if (!isMainThread()) {
            handler.post { noteConversation(runId, conversationKey, title) }
            return
        }
        if (runId.isBlank()) return
        val key = conversationKey.ifBlank { runId }
        val card = cards[key] ?: RunCard(runId, conversationKey, title).also { cards[key] = it }
        if (title.isNotBlank()) card.title = title
        card.lastEventAt = System.currentTimeMillis()
        if (active) postCard(card)
    }

    /** 暂停/恢复后立刻重画：真源在 [RunControl]，这里只负责把新状态画出来。 */
    fun refresh() {
        if (!isMainThread()) {
            handler.post { refresh() }
            return
        }
        if (!active) return
        val now = System.currentTimeMillis()
        cards.values.forEach { it.lastEventAt = now }
        post()
    }

    /** 并发上限被内存压低时，把原因留在 trace 里；卡片副标题只留并发数。 */
    fun noteSessionLimit(limit: Int, max: Int) {
        if (!isMainThread()) {
            handler.post { noteSessionLimit(limit, max) }
            return
        }
        concurrencyMax = max.coerceAtLeast(1)
        if (limit < max) {
            EtaTrace.log("run.progress.limit", "内存紧张 · 并发上限 $limit/$max")
        }
    }

    /** 当前在跑的会话数：多会话时每张卡副标题都标一下。 */
    fun noteConcurrency(running: Int) {
        if (!isMainThread()) {
            handler.post { noteConcurrency(running) }
            return
        }
        runningCount = running.coerceAtLeast(0)
        if (active) post()
    }

    /**
     * 事件流驱动：只更新这个 run 自己的卡。
     * 暂停后不再被在途事件改回「正在执行」。
     */
    fun note(runId: String, event: AgentEvent) {
        if (!isMainThread()) {
            handler.post { note(runId, event) }
            return
        }
        if (!active) return
        val card = cards.values.firstOrNull { it.runId == runId } ?: return
        card.lastEventAt = System.currentTimeMillis()
        if (RunControl.state.value.isPaused(runId)) return
        when (event) {
            is AgentEvent.ToolStarted -> {
                val name = label(event.name)
                card.step = "正在执行：$name"
                card.actionShort = shortLabel(name)
                card.stage = 3
            }
            is AgentEvent.AssistantBlockDelta -> {
                card.step = "正在输出回复"
                card.actionShort = "输出回复"
                card.stage = 4
            }
            is AgentEvent.ContextCompaction -> {
                card.step = "正在整理上下文"
                card.actionShort = "整理上下文"
                card.stage = 2
            }
            is AgentEvent.ModelRetryScheduled -> {
                card.step = "模型重试中"
                card.actionShort = "重试模型"
                card.stage = 2
            }
            else -> Unit
        }
        postCard(card)
    }

    /** 单个会话结束：只撤它那一张，别的会话继续挂着。 */
    fun removeRun(runId: String, pinned: Boolean) {
        if (!isMainThread()) {
            handler.post { removeRun(runId, pinned) }
            return
        }
        val card = cards.values.firstOrNull { it.runId == runId } ?: return
        cards.remove(card.key)
        delegatedRuns.remove(runId)
        // 先算好要撤的 ID 再释放映射：ID 分配器与通知管理器都按同一张卡走。
        val cardId = SessionNotificationIds.idFor(card.key)
        SessionNotificationIds.release(card.key)
        val app = appContext ?: return
        runCatching { manager(app).cancel(cardId) }
        if (cards.isNotEmpty()) return
        active = false
        handler.removeCallbacks(tick)
        if (pinned) publishIdle(app) else {
            idle = false
            runCatching { manager(app).cancel(SessionNotificationIds.IDLE_NOTIFICATION_ID) }
        }
    }

    /** 服务整体收尾（没有会话在跑了）：撤掉所有会话卡。 */
    fun stop(context: Context, pinned: Boolean = false, reason: String = "已撤销进度通知") {
        if (!isMainThread()) {
            handler.post { stop(context, pinned, reason) }
            return
        }
        val app = appContext ?: context.applicationContext
        val keys = cards.keys.toList()
        cards.clear()
        delegatedRuns.clear()
        active = false
        runningCount = 0
        handler.removeCallbacks(tick)
        keys.forEach { key ->
            runCatching { manager(app).cancel(SessionNotificationIds.idFor(key)) }
            SessionNotificationIds.release(key)
        }
        if (pinned) publishIdle(app) else {
            idle = false
            runCatching { manager(app).cancel(SessionNotificationIds.IDLE_NOTIFICATION_ID) }
        }
        EtaTrace.log("run.progress.stop", reason)
    }

    /** 设置里切换「流体云常驻」时立即生效：没任务就跑空闲条目，没开就清掉。 */
    fun refreshIdle(context: Context, pinned: Boolean) {
        if (!isMainThread()) {
            handler.post { refreshIdle(context, pinned) }
            return
        }
        val app = context.applicationContext
        appContext = app
        if (active || cards.isNotEmpty()) return
        if (pinned) publishIdle(app) else {
            idle = false
            runCatching { manager(app).cancel(SessionNotificationIds.IDLE_NOTIFICATION_ID) }
        }
    }

    private fun publishIdle(app: Context) {
        idle = true
        runCatching {
            val builder = Notification.Builder(app, CHANNEL)
                .setSmallIcon(app.applicationInfo.icon)
                .setContentTitle("Eta")
                .setContentText("空闲 · 点开继续")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(launchIntent(app, "", SessionNotificationIds.IDLE_NOTIFICATION_ID))
            if (Build.VERSION.SDK_INT >= LIVE_UPDATE_SDK) {
                builder.setShortCriticalText("空闲")
                builder.setRequestPromotedOngoing(true)
            }
            manager(app).notify(SessionNotificationIds.IDLE_NOTIFICATION_ID, builder.build())
        }
    }

    private fun label(tool: String): String {
        val app = appContext ?: return tool
        val resource = toolDisplayNameResource(tool) ?: return tool
        return runCatching { app.getString(resource) }.getOrDefault(tool)
    }

    private fun post() {
        if (!active) return
        cards.values.toList().forEach { card -> postCard(card) }
    }

    private fun postCard(card: RunCard) {
        val app = appContext ?: return
        if (!active || card.runId in delegatedRuns) return
        runCatching { manager(app).notify(SessionNotificationIds.idFor(card.key), build(app, card)) }
    }

    private fun build(app: Context, card: RunCard): Notification {
        val cardId = SessionNotificationIds.idFor(card.key)
        val paused = RunControl.state.value.isPaused(card.runId)
        val minutes = ((System.currentTimeMillis() - card.startedAt) / 60_000L).coerceAtLeast(0L)
        val elapsed = if (minutes <= 0L) "刚跑起来" else "已跑 $minutes 分钟"
        val open = launchIntent(app, card.conversationKey, cardId)
        val builder = Notification.Builder(app, CHANNEL)
            .setSmallIcon(app.applicationInfo.icon)
            .setContentTitle(card.title.ifBlank { "Eta 正在运行" })
            .setContentText(if (paused) "已暂停 · 点继续恢复" else "$elapsed · ${card.step}")
            .setSubText(if (runningCount > 1) "并发 $runningCount/$concurrencyMax" else null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .addAction(
                action(
                    app,
                    if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                    if (paused) AgentRuntimeService.ACTION_RESUME_RUN else AgentRuntimeService.ACTION_PAUSE_RUN,
                    ACTION_PAUSE_REQUEST,
                    card.runId,
                    cardId,
                ),
            )
            .addAction(
                action(
                    app,
                    R.drawable.ic_stat_stop,
                    AgentRuntimeService.ACTION_STOP_RUN,
                    ACTION_STOP_REQUEST,
                    card.runId,
                    cardId,
                ),
            )
        if (open != null) {
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(app, progressIcon(card.stage)),
                    "",
                    open,
                ).build(),
            )
        }
        if (Build.VERSION.SDK_INT >= LIVE_UPDATE_SDK) {
            builder.setShortCriticalText(if (paused) "已暂停" else card.actionShort)
            builder.setRequestPromotedOngoing(true)
        }
        return builder.build()
    }

    private fun action(
        app: Context,
        iconRes: Int,
        serviceAction: String,
        requestBase: Int,
        runId: String,
        cardId: Int,
    ): Notification.Action {
        val intent = Intent(app, AgentRuntimeService::class.java)
            .setAction(serviceAction)
            .putExtra(AgentRuntimeService.EXTRA_RUN_ID, runId)
        val pending = PendingIntent.getService(
            app,
            cardId * 10 + requestBase,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(Icon.createWithResource(app, iconRes), "", pending).build()
    }

    /** 状态栏胶囊只有几个字的位置，优先放「在干什么」，时间让位给卡片正文。 */
    private fun shortLabel(text: String): String = if (text.length <= 6) text else text.take(6)

    /** 卡片第三个图标：4 格阶段进度，纯图形不写百分比。 */
    private fun progressIcon(stage: Int): Int = when (stage) {
        1 -> R.drawable.ic_progress_1
        2 -> R.drawable.ic_progress_2
        3 -> R.drawable.ic_progress_3
        else -> R.drawable.ic_progress_4
    }

    private fun manager(app: Context): NotificationManager {
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Eta 实时活动", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "长任务运行时在流体云显示经过时间与当前动作"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
        runCatching { manager.deleteNotificationChannel(LEGACY_CHANNEL) }
        return manager
    }

    private fun launchIntent(app: Context, conversationKey: String, cardId: Int): PendingIntent? {
        val intent = Intent(app, MainActivity::class.java)
            .setAction(MainActivity.ACTION_VIEW_EXECUTION)
            .setData(Uri.Builder().scheme("eta-run-progress").authority("view").appendPath(cardId.toString()).build())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (conversationKey.isNotBlank()) {
            intent.putExtra(MainActivity.EXTRA_EXECUTION_SOURCE, AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE)
            intent.putExtra(EtaAssistantOverlayService.EXTRA_CONVERSATION_KEY, conversationKey)
        }
        return PendingIntent.getActivity(
            app,
            cardId,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
