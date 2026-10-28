package io.github.mangi.eta.agent.display.dashboard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.runtime.AgentRuntimeClient
import kotlinx.coroutines.launch
import io.github.mangi.eta.agent.runtime.AgentRuntimeService
import io.github.mangi.eta.agent.runtime.SessionNotificationIds
import io.github.mangi.eta.ui.MainActivity

internal enum class LiveMode { NORMAL, PAUSED, NEEDS_CONFIRM, TAKEN_OVER, SUSPENDED }

internal enum class LiveActionKind { CONFIRM, REJECT, OPEN, PAUSE, RESUME, STOP }

internal data class LiveAction(
    val kind: LiveActionKind,
    val label: String,
    /** 资金类动作要求先解锁再点，防误触。 */
    val requiresUnlock: Boolean = false,
)

/**
 * 流体云卡片规格。由状态机产出，Bridge 只负责翻译成 Notification。
 *
 * - NORMAL：进度胶囊（「正在比价 2/5」），动作 暂停 / 终止 / 打开；
 * - NEEDS_CONFIRM：卡片转黄，动作 同意 / 拒绝 / 查看，本体点击进半屏确认页；
 * - TAKEN_OVER：卡片转绿，动作 继续 / 终止；
 * - SUSPENDED：卡片转红，动作 恢复 / 终止。
 */
internal data class AgentLiveSpec(
    val token: String,
    val runId: String,
    val title: String,
    val step: String,
    val stepIndex: Int,
    val stepTotal: Int,
    val mode: LiveMode,
    val confirm: PendingConfirm? = null,
    val conversationKey: String = "",
)

/**
 * 流体云（ColorOS 实时活动 / Android 16 promoted ongoing）交互层。
 *
 * 关键点：
 * 1. 复用现有渠道 `eta_live_progress` + 通知 ID 4772，和 [io.github.mangi.eta.agent.runtime.RunProgressNotifier] 同一张卡片，
 *    避免出现两条并行进度；
 * 2. 敏感动作走 `setAuthenticationRequired(true)`，点下去先要求解锁，扫码/误触不会直接提交；
 * 3. 卡片被划掉走 `setDeleteIntent`；ColorOS 侧错误码 9251018（live view 服务拒绝/回收）拿不到回调时，
 *    由 `AgentNotificationHistoryService.onNotificationRemoved` 兜底，两条路都收敛到
 *    `DashboardEvent.LiveCardDismissed`，结果只会是「挂起」，不会静默继续花钱。
 */
internal object AgentLiveActivityBridge {
    const val CHANNEL = "eta_live_progress"
    /** 兼容旧引用：空闲卡 ID；会话卡请用 SessionNotificationIds.idFor。 */
    const val NOTIFICATION_ID = SessionNotificationIds.IDLE_NOTIFICATION_ID
    const val ACTION_ACTION = "io.github.mangi.eta.action.AGENT_LIVE_ACTION"
    const val ACTION_DISMISS = "io.github.mangi.eta.action.AGENT_LIVE_DISMISS"
    const val ACTION_OPEN_CONFIRM = "io.github.mangi.eta.action.AGENT_LIVE_CONFIRM"
    /** 卡片本体点击：不要确认页的时候直接进全屏接管。 */
    const val ACTION_TAKEOVER = "io.github.mangi.eta.action.AGENT_LIVE_TAKEOVER"
    const val EXTRA_TOKEN = "live_token"
    const val EXTRA_KIND = "live_kind"
    const val EXTRA_RUN_ID = "live_run_id"
    const val EXTRA_CONVERSATION = "live_conversation"
    const val EXTRA_REASON = "live_close_reason"
    const val EXTRA_RISK = "live_risk"
    const val EXTRA_TITLE = "live_title"
    const val EXTRA_FIELDS = "live_fields"
    const val EXTRA_PREVIEW_PATH = "live_preview_path"

    private const val SCHEME = "eta-live"
    private const val LIVE_SDK = 36

    fun actionsFor(spec: AgentLiveSpec): List<LiveAction> = when (spec.mode) {
        LiveMode.NEEDS_CONFIRM -> listOf(
            LiveAction(LiveActionKind.CONFIRM, "同意提交", requiresUnlock = spec.confirm?.risk == ConfirmRisk.MONEY),
            LiveAction(LiveActionKind.REJECT, "拒绝"),
            LiveAction(LiveActionKind.OPEN, "接管"),
        )
        LiveMode.PAUSED -> listOf(
            LiveAction(LiveActionKind.RESUME, "继续"),
            LiveAction(LiveActionKind.STOP, "终止"),
        )
        LiveMode.TAKEN_OVER -> listOf(
            LiveAction(LiveActionKind.RESUME, "继续"),
            LiveAction(LiveActionKind.STOP, "终止"),
        )
        LiveMode.SUSPENDED -> listOf(
            LiveAction(LiveActionKind.RESUME, "恢复"),
            LiveAction(LiveActionKind.STOP, "终止"),
        )
        LiveMode.NORMAL -> listOf(
            LiveAction(LiveActionKind.PAUSE, "暂停"),
            LiveAction(LiveActionKind.STOP, "终止"),
            LiveAction(LiveActionKind.OPEN, "接管"),
        )
    }

    /** 上一次发布的会话 key：clear 时用它算出该撤哪一张。 */
    @Volatile
    private var lastConversationKey = ""

    private fun idFor(conversationKey: String): Int =
        if (conversationKey.isBlank()) SessionNotificationIds.IDLE_NOTIFICATION_ID
        else SessionNotificationIds.idFor(conversationKey)

    fun publish(context: Context, spec: AgentLiveSpec) {
        val app = context.applicationContext
        val manager = app.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Eta 实时活动", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "长任务运行时在流体云显示进度与待确认动作"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
        lastConversationKey = spec.conversationKey
        runCatching { manager.notify(idFor(spec.conversationKey), build(app, spec)) }
    }

    fun clear(context: Context) {
        val app = context.applicationContext
        val manager = app.getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.cancel(idFor(lastConversationKey)) }
        lastConversationKey = ""
    }

    fun build(app: Context, spec: AgentLiveSpec): Notification {
        val accent = when (spec.mode) {
            LiveMode.PAUSED -> 0xFF9AA4B2.toInt()
            LiveMode.NEEDS_CONFIRM -> 0xFFFFC44D.toInt()
            LiveMode.SUSPENDED -> 0xFFFF6B6B.toInt()
            LiveMode.TAKEN_OVER -> 0xFF5FE08C.toInt()
            LiveMode.NORMAL -> 0xFF7BD8FF.toInt()
        }
        val builder = Notification.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(spec.title)
            .setContentText(body(spec))
            .setSubText(if (spec.stepTotal > 0) "${spec.stepIndex.coerceAtMost(spec.stepTotal)}/${spec.stepTotal}" else null)
            .setColor(accent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(openPendingIntent(app, spec))
            .setDeleteIntent(dismissIntent(app, spec))
        actionsFor(spec).forEach { builder.addAction(action(app, spec, it)) }
        if (Build.VERSION.SDK_INT >= LIVE_SDK) {
            builder.setShortCriticalText(capsule(spec))
            builder.setRequestPromotedOngoing(true)
        }
        return builder.build()
    }

    private fun body(spec: AgentLiveSpec): String = when (spec.mode) {
        LiveMode.NEEDS_CONFIRM -> "待确认 · " + (spec.confirm?.title ?: "敏感操作")
        LiveMode.SUSPENDED -> "已挂起 · 点开决定是否继续"
        LiveMode.PAUSED -> "已暂停 · 点继续恢复"
        LiveMode.TAKEN_OVER -> "你正在接管悬浮窗"
        LiveMode.NORMAL -> spec.step.ifBlank { "执行中" }
    }

    /** 状态栏胶囊只有几个字的位置：先给「待确认」这种必须被看见的词。 */
    private fun capsule(spec: AgentLiveSpec): String = when (spec.mode) {
        LiveMode.NEEDS_CONFIRM -> "待确认"
        LiveMode.SUSPENDED -> "已挂起"
        LiveMode.PAUSED -> "已暂停"
        LiveMode.TAKEN_OVER -> "你操作中"
        LiveMode.NORMAL -> if (spec.stepTotal > 0) {
            spec.step.ifBlank { "执行中" }.take(6) + " " + spec.stepIndex.coerceAtMost(spec.stepTotal) + "/" + spec.stepTotal
        } else {
            spec.step.ifBlank { "执行中" }.take(8)
        }
    }

    private fun action(app: Context, spec: AgentLiveSpec, live: LiveAction): Notification.Action {
        val pending = when (live.kind) {
            // 继续/终止也要过状态机：只调运行时的话，控制权仍停在「用户接管」，
            // 卡片会一直显示「你操作中」，点继续看着像没反应。
            LiveActionKind.CONFIRM, LiveActionKind.REJECT,
            LiveActionKind.PAUSE, LiveActionKind.RESUME, LiveActionKind.STOP -> PendingIntent.getBroadcast(
                app,
                requestCode(spec, live),
                Intent(app, AgentLiveActionReceiver::class.java)
                    .setAction(ACTION_ACTION)
                    .setData(Uri.parse("$SCHEME://action/${spec.token}/${live.kind.name.lowercase()}"))
                    .putExtra(EXTRA_TOKEN, spec.token)
                    .putExtra(EXTRA_KIND, live.kind.name)
                    .putExtra(EXTRA_RUN_ID, spec.runId)
                    .putExtra(EXTRA_CONVERSATION, spec.conversationKey),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            // 「接管」按钮必须真的进接管页；卡片本体点击走的是 contentIntent，两者不能共用。
            LiveActionKind.OPEN -> PendingIntent.getBroadcast(
                app,
                0x0A12,
                takeoverIntent(app, spec),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        return Notification.Action.Builder(null, live.label, pending)
            .setAuthenticationRequired(live.requiresUnlock)
            .build()
    }

    /** data 里带 token + kind，保证每个动作是独立 PendingIntent，不会被 extras 复用串味。 */
    private fun requestCode(spec: AgentLiveSpec, live: LiveAction): Int =
        (spec.token.hashCode() * 31 + live.kind.ordinal) and 0x00FFFFFF

    private fun openPendingIntent(app: Context, spec: AgentLiveSpec): PendingIntent =
        if (spec.mode == LiveMode.NEEDS_CONFIRM) {
            PendingIntent.getActivity(
                app,
                0x0A11,
                confirmIntent(app, spec),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        } else {
            // 点卡片本体只回 App，不再直接跳接管；接管只由卡片上的「接管」按钮触发。
            PendingIntent.getActivity(
                app,
                0x0A13,
                android.content.Intent(app, io.github.mangi.eta.ui.MainActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

    /**
     * 点卡片 = 直接进全屏接管页：展示的是 VirtualDisplay 的真实画面，
     * 而不是一张生成式卡片。小窗上已经没有接管按钮，这里是唯一入口。
     */
    /** 待确认 → 半屏确认页；其余 → 直接全屏接管（不再跳旧查看页）。 */
    private fun confirmIntent(app: Context, spec: AgentLiveSpec): Intent =
        Intent(app, AgentConfirmActivity::class.java)
            .setAction(ACTION_OPEN_CONFIRM)
            .setData(Uri.parse("$SCHEME://confirm/${spec.token}"))
            .putExtra(AgentConfirmActivity.EXTRA_TOKEN, spec.token)
            .putExtra(AgentConfirmActivity.EXTRA_TITLE, spec.confirm?.title ?: spec.title)
            .putExtra(AgentConfirmActivity.EXTRA_RISK, spec.confirm?.risk?.name.orEmpty())
            .putExtra(AgentConfirmActivity.EXTRA_CONVERSATION, spec.conversationKey)
            .putExtra(AgentConfirmActivity.EXTRA_PREVIEW_PATH, spec.confirm?.previewPath.orEmpty())
            .putExtra(AgentConfirmActivity.EXTRA_FIELDS, confirmFieldsJson(spec.confirm))

    private fun takeoverIntent(app: Context, spec: AgentLiveSpec): Intent =
        Intent(app, AgentLiveActionReceiver::class.java)
            .setAction(ACTION_TAKEOVER)
            .setData(Uri.parse("$SCHEME://takeover/${spec.token}"))
            .putExtra(EXTRA_TOKEN, spec.token)
            .putExtra(EXTRA_RUN_ID, spec.runId)
            .putExtra(EXTRA_CONVERSATION, spec.conversationKey)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    private fun dismissIntent(app: Context, spec: AgentLiveSpec): PendingIntent =
        PendingIntent.getBroadcast(
            app,
            requestCode(spec, LiveAction(LiveActionKind.STOP, "")),
            Intent(app, AgentLiveActionReceiver::class.java)
                .setAction(ACTION_DISMISS)
                .setData(Uri.parse("$SCHEME://dismiss/${spec.token}"))
                .putExtra(EXTRA_TOKEN, spec.token)
                .putExtra(EXTRA_RUN_ID, spec.runId)
                .putExtra(EXTRA_REASON, "swipe"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun confirmFieldsJson(confirm: PendingConfirm?): String {
        val fields = confirm?.fields ?: return ""
        return fields.joinToString("|") { it.label + "=" + it.value }
    }
}

/** 流体云动作的落地端：把点击翻译成状态机事件。 */
class AgentLiveActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AgentLiveActivityBridge.ACTION_ACTION -> {
                val token = intent.getStringExtra(AgentLiveActivityBridge.EXTRA_TOKEN).orEmpty()
                val kind = runCatching {
                    LiveActionKind.valueOf(intent.getStringExtra(AgentLiveActivityBridge.EXTRA_KIND).orEmpty())
                }.getOrNull() ?: return
                AgentLiveCoordinator.onAction(context, token, kind)
            }
            AgentLiveActivityBridge.ACTION_TAKEOVER -> {
                AgentLiveCoordinator.requestTakeover(context)
            }
            AgentLiveActivityBridge.ACTION_DISMISS -> {
                val reason = intent.getStringExtra(AgentLiveActivityBridge.EXTRA_REASON).orEmpty().ifBlank { "swipe" }
                AgentLiveCoordinator.onCardDismissed(context, reason)
            }
        }
    }
}

/**
 * 协调器：把状态机的 [DashboardEffect] 与流体云、悬浮窗、探针串起来。
 * 只保有一份 [AgentControlStore] 引用，避免通知与浮窗各持一套状态。
 */
internal object AgentLiveCoordinator {
    private var store: AgentControlStore? = null

    private var runControlConsumer: ((io.github.mangi.eta.agent.runtime.RunControlState) -> Unit)? = null

    fun attach(store: AgentControlStore) {
        this.store = store
        runControlConsumer?.let { io.github.mangi.eta.agent.runtime.RunControl.unobserve(it) }
        // 真源一变就把暂停态灌进状态机；只认本卡自己的 runId，别的会话不动。
        val consumer: (io.github.mangi.eta.agent.runtime.RunControlState) -> Unit = { state ->
            val runId = store.state.value.runId
            if (!runId.isNullOrBlank()) {
                store.dispatch(DashboardEvent.RunPausedChanged(runId, state.isPaused(runId), "runtime"))
            }
        }
        runControlConsumer = consumer
        io.github.mangi.eta.agent.runtime.RunControl.observe(consumer)
    }

    /** 注入门据此判断「现在到底该不该封门」。 */
    fun currentMode(): AgentControlMode? = store?.state?.value?.mode

    fun detach() {
        runControlConsumer?.let { io.github.mangi.eta.agent.runtime.RunControl.unobserve(it) }
        runControlConsumer = null
        store = null
    }

    fun publish(context: Context, spec: AgentLiveSpec) {
        AgentLiveActivityBridge.publish(context, spec)
    }

    fun clear(context: Context) {
        AgentLiveActivityBridge.clear(context)
    }

    fun onAction(context: Context, token: String, kind: LiveActionKind) {
        val current = store ?: return
        when (kind) {
            LiveActionKind.CONFIRM -> current.dispatch(DashboardEvent.ConfirmResolved(token, approved = true))
            LiveActionKind.REJECT -> current.dispatch(DashboardEvent.ConfirmResolved(token, approved = false))
            LiveActionKind.PAUSE -> {
                // 只发指令 + 推进本机状态机：暂停位由执行侧写进 RunControl，再回灌到所有入口。
                val runId = current.state.value.runId.orEmpty()
                current.dispatch(DashboardEvent.PauseRequested)
                if (runId.isNotBlank()) {
                    sendRuntime(context, AgentRuntimeService.ACTION_PAUSE_RUN, runId)
                }
            }
            LiveActionKind.RESUME -> {
                val runId = current.state.value.runId.orEmpty()
                if (current.state.value.mode == AgentControlMode.AI_PAUSED) {
                    current.dispatch(DashboardEvent.ResumeFromPause)
                    if (runId.isNotBlank()) {
                        sendRuntime(context, AgentRuntimeService.ACTION_RESUME_RUN, runId)
                    }
                    return
                }
                // 先把控制权交回 AI，再让运行时继续；顺序反了会留一段「已继续但仍显示接管中」。
                current.dispatch(DashboardEvent.HandBackRequested)
                current.dispatch(DashboardEvent.HandBackCompleted)
                if (runId.isNotBlank()) {
                    sendRuntime(context, AgentRuntimeService.ACTION_RESUME_RUN, runId)
                }
            }
            LiveActionKind.STOP -> {
                // 停止同样按会话：并发时不能把别人的 run 一起端掉。
                val runId = current.state.value.runId.orEmpty()
                current.dispatch(DashboardEvent.TerminateRequested)
                if (runId.isNotBlank()) {
                    sendRuntime(context, AgentRuntimeService.ACTION_STOP_RUN, runId)
                }
            }
            LiveActionKind.OPEN -> Unit
        }
    }

    private fun sendRuntime(context: Context, action: String, runId: String = "") {
        runCatching {
            val intent = Intent(context.applicationContext, AgentRuntimeService::class.java).setAction(action)
            if (runId.isNotBlank()) {
                intent.putExtra(AgentRuntimeService.EXTRA_RUN_ID, runId)
            }
            PendingIntent.getService(
                context.applicationContext,
                (action + runId).hashCode(),
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ).send()
        }
    }

    /**
     * 卡片消失。两条来源：
     * - deleteIntent（用户划掉）；
     * - NotificationListenerService 兜底（ColorOS 以 9251018 之类错误码回收卡片时，deleteIntent 可能不来）。
     * 两者都只落到「挂起」，交给用户回 App 决定，不自动继续。
     */
    /** 卡片点「接管」：进独立接管页（全屏、不透明、自带退出口径）。 */
    fun requestTakeover(context: Context) {
        runCatching { io.github.mangi.eta.agent.display.TakeoverActivity.launch(context) }
    }

    /** 接管页进入：关 AI 注入门、暂停运行时。 */
    fun beginTakeover(context: Context) {
        val current = store ?: return
        current.dispatch(DashboardEvent.TakeoverRequested)
        sendRuntime(context, io.github.mangi.eta.agent.runtime.AgentRuntimeService.ACTION_PAUSE_RUNS)
    }

    /**
     * 接管页退出：控制权交回 AI，并**强制重读现场**。
     *
     * 人手操作过之后，页面与模型记忆里的已经不一致；所以这里先抓一帧刷新快照，
     * 再通过 steering 通道告一条「刚有人动过，先 observe 再继续」。
     * 两条都失败也不影响交还本身。
     */
    fun endTakeover(context: Context) {
        val current = store ?: return
        current.dispatch(DashboardEvent.HandBackRequested)
        current.dispatch(DashboardEvent.HandBackCompleted)
        val app = context.applicationContext
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching { io.github.mangi.eta.agent.display.VirtualScreenSession.observeForViewer(app) }
            runCatching {
                AgentRuntimeClient(app, io.github.mangi.eta.core.AndroidAgentLogger).steerRun(
                    "【人工接管结束】用户刚在灵屏里手动操作过，页面状态可能已变化。" +
                        "请先 observe_screen 重新读屏，再决定下一步，不要沿用接管前的页面记忆。"
                )
            }
        }
    }

    fun onCardDismissed(context: Context, reason: String) {
        store?.dispatch(DashboardEvent.LiveCardDismissed(reason))
        AgentLiveActivityBridge.clear(context)
    }
}
