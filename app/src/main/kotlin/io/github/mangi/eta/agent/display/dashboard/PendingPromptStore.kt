package io.github.mangi.eta.agent.display.dashboard

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** 卡片上的一个可选项。`id` 是回灌给模型的值，`label` 给用户看。 */
internal data class PromptOption(
    val id: String,
    val label: String,
    val description: String = "",
)

/** 系统内置选项 id。模型也能用这些 id 表达意图。 */
internal object PromptOptionId {
    const val APPROVE = "approve"
    const val REJECT = "reject"
    const val MANUAL = "manual"
    const val REMEMBER = "remember"
}

/**
 * 待答问题 / 待审批项的落盘。
 *
 * 存的是**数组**：每个会话可以各挂一张卡，互不覆盖。
 * 早先存单个对象，导致 A 会话有卡时 B 会话完全问不了（实测被用户吐槽）。
 *
 * 用独立 SharedPreferences 而不是 DataStore：
 * 1. 写入必须同步可见（UI 立刻读），DataStore 是挂起 API；
 * 2. 只有几条记录，不值得为它扩 Settings 的 schema。
 */
internal object PendingPromptStore {

    private const val FILE = "eta_pending_prompt"
    private const val KEY = "current"

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        }
    }

    fun save(pendings: List<PendingApproval>) {
        val store = prefs ?: return
        if (pendings.isEmpty()) {
            store.edit().remove(KEY).apply()
            return
        }
        val array = JSONArray()
        pendings.forEach { pending ->
            array.put(
                JSONObject()
                    .put("token", pending.token)
                    .put("source", pending.source)
                    .put("risk", pending.risk.name)
                    .put("title", pending.title)
                    .put("body", pending.body)
                    .put("quiet", pending.quiet)
                    .put("subject", pending.subject)
                    .put("repeatCount", pending.repeatCount)
                    .put("conversationKey", pending.conversationKey)
                    .put("createdAt", pending.createdAt)
                    .put("fields", JSONArray().apply {
                        pending.fields.forEach {
                            put(JSONObject().put("label", it.label).put("value", it.value))
                        }
                    })
                    .put("options", JSONArray().apply {
                        pending.options.forEach {
                            put(
                                JSONObject()
                                    .put("id", it.id)
                                    .put("label", it.label)
                                    .put("description", it.description),
                            )
                        }
                    }),
            )
        }
        store.edit().putString(KEY, array.toString()).apply()
    }

    fun load(): List<PendingApproval> {
        val raw = prefs?.getString(KEY, null)?.takeIf { it.isNotBlank() } ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let(::decode)
            }
        }.getOrDefault(emptyList())
    }

    private fun decode(json: JSONObject): PendingApproval? = runCatching {
        val risk = runCatching { ConfirmRisk.valueOf(json.optString("risk")) }
            .getOrDefault(ConfirmRisk.IRREVERSIBLE)
        val fieldsJson = json.optJSONArray("fields") ?: JSONArray()
        val fields = (0 until fieldsJson.length()).mapNotNull { i ->
            fieldsJson.optJSONObject(i)?.let { ConfirmField(it.optString("label"), it.optString("value")) }
        }
        val optionsJson = json.optJSONArray("options") ?: JSONArray()
        val options = (0 until optionsJson.length()).mapNotNull { i ->
            optionsJson.optJSONObject(i)?.let {
                PromptOption(it.optString("id"), it.optString("label"), it.optString("description"))
            }
        }
        val token = json.optString("token")
        if (token.isBlank()) return null
        PendingApproval(
            token = token,
            source = json.optString("source").ifBlank { PendingApproval.SOURCE_GUARD },
            risk = risk,
            title = json.optString("title"),
            fields = fields,
            quiet = json.optBoolean("quiet"),
            subject = json.optString("subject"),
            repeatCount = json.optInt("repeatCount", 1),
            body = json.optString("body"),
            options = options,
            conversationKey = json.optString("conversationKey"),
            createdAt = json.optLong("createdAt", System.currentTimeMillis()),
        )
    }.getOrNull()

    fun clear() {
        prefs?.edit()?.remove(KEY)?.apply()
    }
}
