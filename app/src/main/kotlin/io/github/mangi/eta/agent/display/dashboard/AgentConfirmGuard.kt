package io.github.mangi.eta.agent.display.dashboard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import io.github.mangi.eta.R
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.EtaTrace
import org.json.JSONArray
import org.json.JSONObject

/**
 * 决策拦截器的执行侧：把 [ConfirmRiskDetector] 的判定结果变成「拦下来 + 提醒用户」。
 *
 * 判定本身是纯函数，这里只负责三件事：
 * 1. 记住最近一次的屏上文本，供判定当上下文；
 * 2. 命中时发一条高优先级通知（带真实字段，不做生成式摘要）；
 * 3. 给调用方一个可直接返回给模型的 `NEED_CONFIRM` 结果，**不执行这次注入**。
 *
 * 取舍：宁可多拦一次，也不放行一次扣款。误拦的代价是模型多问一句，
 * 漏拦的代价是钱已经花出去。
 */
internal object AgentConfirmGuard {

    private const val CHANNEL = "eta_confirm_required"
    private const val NOTIFICATION_ID = 8891

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
    fun inspect(context: Context?, target: String?, action: String): ConfirmDraft? {
        // 先按控件文本判；判不出来再看整屏是不是收银台——
        // 拼多多/淘宝这类 App 的支付按钮常在自定义 View 里，无障碍拿到的 text 是空的，
        // 只靠控件文本会漏掉最关键的那一下（实测：点「立即支付」没拦，钱真扣了）。
        val draft = ConfirmRiskDetector.inspect(target, texts) ?: checkoutFallback() ?: return null
        EtaTrace.log("confirm.block", "action=$action risk=${draft.risk} target=${target?.take(40)}")
        context?.let { runCatching { notify(it, draft) } }
        return draft
    }

    /** 收银台特征词：出现任何一个 + 屏上有金额，就认为下一步可能是扣款。 */
    private val checkoutWords = listOf(
        "立即支付", "确认支付", "去支付", "立即付款", "确认付款",
        "提交订单", "提交订单并支付", "去结算", "确认下单", "立即购买",
    )

    private val amountPattern = Regex("""(?:¥|￥)\s?\d+(?:[.,]\d{1,2})?|\d+(?:[.,]\d{1,2})?\s?元""")

    /**
     * 页面级兜底：拿不到控件文本时，用整屏特征判。
     * 只在「金额 + 支付动作词」同时出现时成立，避免把普通浏览页也拦下来。
     */
    private fun checkoutFallback(): ConfirmDraft? {
        val all = texts.joinToString(" ")
        if (all.isBlank()) return null
        val amount = amountPattern.find(all)?.value ?: return null
        val payWord = checkoutWords.firstOrNull { all.contains(it) } ?: return null
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

    private fun notify(context: Context, draft: ConfirmDraft) {
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
        val token = "guard-" + System.currentTimeMillis()
        val summary = draft.fields.joinToString(" · ") { "${it.label} ${it.value}" }.take(120)
        // 证据图：从当前灵屏真帧裁一块，确认页展示真实画面而不是生成式卡片。
        val preview = runCatching { AgentConfirmActivity.capturePreview(context, token) }.getOrDefault("")
        val open = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(context, AgentConfirmActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(AgentConfirmActivity.EXTRA_TOKEN, token)
                .putExtra(AgentConfirmActivity.EXTRA_TITLE, draft.title)
                .putExtra(AgentConfirmActivity.EXTRA_RISK, draft.risk.name)
                .putExtra(
                    AgentConfirmActivity.EXTRA_FIELDS,
                    draft.fields.joinToString("|") { "${it.label}=${it.value}" },
                )
                .putExtra(AgentConfirmActivity.EXTRA_PREVIEW_PATH, preview),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            NOTIFICATION_ID,
            Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.confirm_notify_title, draft.title))
                .setContentText(summary.ifBlank { context.getString(R.string.confirm_notify_body) })
                .setStyle(Notification.BigTextStyle().bigText(summary))
                .setCategory(Notification.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build(),
        )
        AndroidAgentLogger.info("Confirm required risk=${draft.risk} fields=${draft.fields.size}")
    }
}
