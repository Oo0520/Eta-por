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
 * 长任务里程碑汇报。
 *
 * 会话在跑的时候挂一条通知，每 10 秒刷新一句「已跑多久 · 当前在做什么」，
 * 编译、爬取这类要等几分钟的活儿不用盯着屏幕；任务结束（成功／失败／取消）立刻撤掉。
 *
 * Android 16 起同一条通知按「实时活动」（live update）发布，ColorOS 会放进流体云：
 * 卡片 = 会话标题 + 状态正文 + 三个纯图标动作（暂停／继续、停止、阶段进度），
 * 点卡片本体回到发起这次运行的那次会话。开启「流体云常驻」后，空闲时也保留一条。
 */
internal object RunProgressNotifier {

    private const val CHANNEL = "eta_live_progress"

    /** 旧渠道当初建成 LOW，系统不允许应用事后提高重要性，只能弃用。 */
    private const val LEGACY_CHANNEL = "eta_run_progress"

    private const val NOTIFICATION_ID = 4772
    private const val TICK_MS = 10_000L

    /** 兜底：这么久没有任何会话活动，就当这次运行已经结束，把通知撤掉。 */
    private const val IDLE_STOP_MS = 30 * 60_000L

    /** Android 16（API 36）起支持 promoted ongoing 提升。 */
    private const val LIVE_UPDATE_SDK = 36

    private const val ACTION_PAUSE_REQUEST = 2
    private const val ACTION_STOP_REQUEST = 3
    private const val ACTION_STAGE_REQUEST = 4

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var appContext: Context? = null

    private var startedAt = 0L
    private var lastEventAt = 0L
    private var limitNote = ""
    private var step = "正在处理"
    private var actionShort = "处理中"
    private var active = false
    private var idle = false
    private var runId = ""
    private var conversationKey = ""
    private var conversationTitle = ""
    private var paused = false
    private var runningCount = 0
    private var concurrencyMax = 1

    /** 阶段进度：1 准备 / 2 思考 / 3 工具 / 4 收尾。 */
    private var stage = 1

    private val tick = object : Runnable {
        override fun run() {
            if (!active) return
            if (System.currentTimeMillis() - lastEventAt > IDLE_STOP_MS) {
                // 通知一直在刷「已跑 N 分钟」，看着像还在干活；兜底撤掉，避免僵尸通知。
                stop(appContext ?: return, reason = "超时兜底：30 分钟没有任何会话活动，自动撤销进度通知")
                return
            }
            post()
            handler.postDelayed(this, TICK_MS)
        }
    }

    fun start(context: Context) {
        appContext = context.applicationContext
        startedAt = System.currentTimeMillis()
        lastEventAt = startedAt
        step = "正在处理"
        actionShort = "处理中"
        stage = 1
        paused = false
        idle = false
        active = true
        handler.removeCallbacks(tick)
        handler.post(tick)
        EtaTrace.log("run.progress.start", "开始汇报运行进度")
    }

    /** 记住这次运行属于哪个会话；流体云／通知被点开时直接落到那次会话。 */
    fun noteConversation(runId: String, conversationKey: String, title: String = "") {
        this.runId = runId
        this.conversationKey = conversationKey
        if (title.isNotBlank()) conversationTitle = title
        if (active) post()
    }

    /** 暂停／继续时改按钮图标与状态行。 */
    fun setPaused(value: Boolean) {
        if (paused == value) return
        paused = value
        step = if (value) "已暂停" else "正在处理"
        actionShort = if (value) "已暂停" else "处理中"
        lastEventAt = System.currentTimeMillis()
        if (active) post()
    }

    /** 并发上限被内存压低时，把原因留在trace里；卡片上只留并发数。 */
    fun noteSessionLimit(limit: Int, max: Int) {
        concurrencyMax = max.coerceAtLeast(1)
        limitNote = if (limit < max) "内存紧张 · 上限 $limit/$max" else ""
    }

    /** 当前在跑的会话数，卡片副标题只在多会话时出现。 */
    fun noteConcurrency(running: Int) {
        runningCount = running.coerceAtLeast(0)
        if (active) post()
    }

    /** 由事件流驱动，更新「当前在做什么」与阶段进度。 */
    fun note(event: AgentEvent) {
        if (!active) return
        lastEventAt = System.currentTimeMillis()
        when (event) {
            is AgentEvent.ToolStarted -> {
                val name = label(event.name)
                step = "正在执行：$name"
                actionShort = shortLabel(name)
                stage = 3
            }
            is AgentEvent.AssistantBlockDelta -> {
                step = "正在输出回复"
                actionShort = "输出回复"
                stage = 4
            }
            is AgentEvent.ContextCompaction -> {
                step = "正在整理上下文"
                actionShort = "整理上下文"
                stage = 2
            }
            is AgentEvent.ModelRetryScheduled -> {
                step = "模型重试中"
                actionShort = "重试模型"
                stage = 2
            }
            else -> Unit
        }
    }

    /**
     * 任务结束：默认撤掉通知；开启「流体云常驻」时改为发布空闲条目，让流体云一直留着入口。
     */
    fun stop(context: Context, pinned: Boolean = false, reason: String = "已撤销进度通知") {
        if (!active) return
        active = false
        limitNote = ""
        runId = ""
        conversationKey = ""
        conversationTitle = ""
        paused = false
        runningCount = 0
        stage = 1
        handler.removeCallbacks(tick)
        val app = (appContext ?: context.applicationContext)
        if (pinned) publishIdle(app) else {
            idle = false
            runCatching { manager(app).cancel(NOTIFICATION_ID) }
        }
        EtaTrace.log("run.progress.stop", reason)
    }

    /** 设置里切换「流体云常驻」时立即生效：没任务就跑空闲条目，没开就清掉。 */
    fun refreshIdle(context: Context, pinned: Boolean) {
        val app = context.applicationContext
        appContext = app
        if (active) return
        if (pinned) publishIdle(app) else {
            idle = false
            runCatching { manager(app).cancel(NOTIFICATION_ID) }
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
                .setContentIntent(launchIntent(app))
            if (Build.VERSION.SDK_INT >= LIVE_UPDATE_SDK) {
                builder.setShortCriticalText("空闲")
                builder.setRequestPromotedOngoing(true)
            }
            manager(app).notify(NOTIFICATION_ID, builder.build())
        }
    }

    private fun label(tool: String): String {
        val app = appContext ?: return tool
        val resource = toolDisplayNameResource(tool) ?: return tool
        return runCatching { app.getString(resource) }.getOrDefault(tool)
    }

    private fun post() {
        val app = appContext ?: return
        val minutes = ((System.currentTimeMillis() - startedAt) / 60_000L).coerceAtLeast(0L)
        runCatching {
            manager(app).notify(NOTIFICATION_ID, build(app, minutes))
        }
    }

    private fun build(app: Context, minutes: Long): Notification {
        val elapsed = if (minutes <= 0L) "刚跑起来" else "已跑 $minutes 分钟"
        val title = conversationTitle.ifBlank { "Eta 正在运行" }
        val detail = if (paused) "已暂停 · 点继续恢复" else "$elapsed · $step"
        val open = launchIntent(app)
        val builder = Notification.Builder(app, CHANNEL)
            .setSmallIcon(app.applicationInfo.icon)
            .setContentTitle(title)
            .setContentText(detail)
            .setSubText(if (runningCount > 1) "并发 $runningCount/$concurrencyMax" else null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .addAction(
                action(
                    app,
                    if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                    if (paused) AgentRuntimeService.ACTION_RESUME_RUNS else AgentRuntimeService.ACTION_PAUSE_RUNS,
                    ACTION_PAUSE_REQUEST,
                ),
            )
            .addAction(
                action(
                    app,
                    R.drawable.ic_stat_stop,
                    AgentRuntimeService.ACTION_STOP_RUNS,
                    ACTION_STOP_REQUEST,
                ),
            )
        if (open != null) {
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(app, progressIcon(stage)),
                    "",
                    open,
                ).build(),
            )
        }
        if (Build.VERSION.SDK_INT >= LIVE_UPDATE_SDK) {
            builder.setShortCriticalText(if (paused) "已暂停" else actionShort)
            builder.setRequestPromotedOngoing(true)
        }
        return builder.build()
    }

    private fun action(
        app: Context,
        iconRes: Int,
        serviceAction: String,
        requestCode: Int,
    ): Notification.Action {
        val intent = Intent(app, AgentRuntimeService::class.java).setAction(serviceAction)
        val pending = PendingIntent.getService(
            app,
            requestCode,
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

    private fun launchIntent(app: Context): PendingIntent? {
        val intent = Intent(app, MainActivity::class.java)
            .setAction(MainActivity.ACTION_VIEW_EXECUTION)
            .setData(Uri.Builder().scheme("eta-run-progress").authority("view").build())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (conversationKey.isNotBlank()) {
            intent.putExtra(MainActivity.EXTRA_EXECUTION_SOURCE, AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE)
            intent.putExtra(EtaAssistantOverlayService.EXTRA_CONVERSATION_KEY, conversationKey)
        }
        return PendingIntent.getActivity(
            app,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
