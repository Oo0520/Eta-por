package io.github.mangi.eta.agent.confirm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import io.github.mangi.eta.R
import io.github.mangi.eta.core.AndroidAgentLogger
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * 审批闸门：工具执行前判一次「这一下会不会花钱 / 泄隐私 / 不可逆」，命中就挂起等用户裁决。
 *
 * 设计取舍（相对 fork 的 767 行版本做了减法）：
 * - 不做聊天内联卡、不做 Activity，只发一条带「批准 / 拒绝」两个动作的通知；
 * - 判据全部来自 [ConfirmRiskDetector]，不看屏幕文本，因此只需要 ToolCall 的参数；
 * - 批准后按「主体签名」给一段宽限期，同一操作不会连环追问；
 * - 挂起项落盘（[PendingPromptStore]），进程重启后仍在。
 */
internal object AgentConfirmGuard {

    private const val GRANT_WINDOW_MS = 24 * 60 * 60 * 1000L
    private const val CHANNEL_ID = "eta_confirm_gate"
    private const val CHANNEL_NAME = "操作确认"

    private val mutablePendings = MutableStateFlow<List<PendingApproval>>(emptyList())
    val pendings: StateFlow<List<PendingApproval>> = mutablePendings.asStateFlow()

    /** 主体签名 → 宽限截止时间。批准过后同一主体在窗口内直接放行。 */
    private val grants = ConcurrentHashMap<String, Long>()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var currentConversation: String = ""

    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        PendingPromptStore.init(app)
        val stored = PendingPromptStore.load()
        val restored = stored.filterNot { it.expired() }
        mutablePendings.value = restored
        if (restored.size != stored.size) PendingPromptStore.save(restored)
    }

    fun bindConversation(conversationKey: String) {
        currentConversation = conversationKey
    }

    fun pendingFor(conversationKey: String): PendingApproval? {
        val key = conversationKey.ifBlank { currentConversation }
        return mutablePendings.value.firstOrNull { it.conversationKey == key && !it.expired() }
    }

    fun activeCount(): Int = mutablePendings.value.count { !it.expired() }

    /**
     * 判定并挂起。返回 null 表示放行；非 null 表示必须拦下，
     * 调用方应把返回的草稿转成工具结果交回模型，不要执行这次注入。
     */
    fun inspect(
        context: Context?,
        toolName: String,
        args: JSONObject,
        runId: String = "",
    ): ConfirmDraft? {
        val target = when (toolName) {
            "terminal", "terminal_job", "terminal_daemon" -> args.optString("command")
            else -> args.optString("text").ifBlank { args.optString("path") }
        }
        val draft = ConfirmRiskDetector.inspectTool(toolName, args)
            ?: target.takeIf { it.isNotBlank() }
                ?.let { ConfirmRiskDetector.inspect(target = it.take(60), texts = emptyList()) }
            ?: return null
        return register(context, draft, target, toolName, runId)
    }

    private fun register(
        context: Context?,
        draft: ConfirmDraft,
        target: String?,
        action: String,
        runId: String,
    ): ConfirmDraft? {
        val subject = subjectOf(draft, target)
        val now = System.currentTimeMillis()
        grants[subject]?.let { until ->
            if (until > now) {
                AndroidAgentLogger.info("confirm.granted subject=$subject")
                return null
            }
            grants.remove(subject)
        }

        val owner = currentConversation
        val quiet = draft.risk == ConfirmRisk.PRIVACY
        val body = when (draft.risk) {
            ConfirmRisk.MONEY -> "这一步涉及资金，确认不是手滑再批准。"
            ConfirmRisk.PRIVACY -> "这一步会上传个人信息，确认目标正确再批准。"
            ConfirmRisk.IRREVERSIBLE -> "这一步不可撤销，确认后无法回滚。"
        }
        val pending = PendingApproval(
            token = UUID.randomUUID().toString(),
            source = PendingApproval.SOURCE_GUARD,
            risk = draft.risk,
            title = draft.title,
            fields = draft.fields,
            quiet = quiet,
            subject = subject,
            body = body,
            options = listOf(
                PromptOption(PromptOptionId.APPROVE, "批准这一步", "按它说的做"),
                PromptOption(PromptOptionId.REJECT, "拒绝", "停下，让它换个做法"),
            ),
            conversationKey = owner,
        )
        val others = mutablePendings.value.filterNot { it.conversationKey == owner }
        val merged = others + pending
        mutablePendings.value = merged
        PendingPromptStore.save(merged)
        if (!quiet) notify(context ?: appContext, pending)
        AndroidAgentLogger.info("confirm.block action=$action risk=${draft.risk} subject=$subject run=$runId")
        return draft
    }

    fun approve(context: Context?, token: String, remember: Boolean = false): Boolean =
        resolve(context, token) { pending ->
            if (remember && pending.canRemember) {
                grants[pending.subject] = System.currentTimeMillis() + GRANT_WINDOW_MS
            }
        }

    fun reject(context: Context?, token: String): Boolean = resolve(context, token) { }

    fun dismissAll(context: Context?) {
        mutablePendings.value.forEach { cancel(context ?: appContext, it.token) }
        mutablePendings.value = emptyList()
        PendingPromptStore.clear()
    }

    private fun resolve(context: Context?, token: String, beforeRemove: (PendingApproval) -> Unit): Boolean {
        val pending = mutablePendings.value.firstOrNull { it.token == token } ?: return false
        if (pending.expired()) {
            remove(context, token)
            return false
        }
        beforeRemove(pending)
        remove(context, token)
        return true
    }

    private fun remove(context: Context?, token: String) {
        val merged = mutablePendings.value.filterNot { it.token == token }
        mutablePendings.value = merged
        PendingPromptStore.save(merged)
        cancel(context ?: appContext, token)
    }

    private fun subjectOf(draft: ConfirmDraft, target: String?): String {
        val primary = draft.fields.firstOrNull()?.value.orEmpty()
        return listOf(draft.risk.name, target?.take(40).orEmpty(), primary.take(40)).joinToString("|")
    }

    private fun notify(context: Context?, pending: PendingApproval) {
        val app = context ?: return
        runCatching {
            val manager = app.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH),
                )
            }
            val approve = actionIntent(app, ConfirmActionReceiver.ACTION_APPROVE, pending.token)
            val reject = actionIntent(app, ConfirmActionReceiver.ACTION_REJECT, pending.token)
            val notification = NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(pending.title)
                .setContentText(pending.body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(pending.body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .addAction(0, "批准", approve)
                .addAction(0, "拒绝", reject)
                .build()
            manager.notify(pending.token.hashCode(), notification)
        }.onFailure { AndroidAgentLogger.warn("confirm.notify.fail type=${it.javaClass.simpleName}") }
    }

    private fun cancel(context: Context?, token: String) {
        val app = context ?: return
        runCatching { app.getSystemService(NotificationManager::class.java)?.cancel(token.hashCode()) }
    }

    private fun actionIntent(context: Context, action: String, token: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            token.hashCode(),
            Intent(context, ConfirmActionReceiver::class.java)
                .setAction(action)
                .putExtra(ConfirmActionReceiver.EXTRA_TOKEN, token),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
