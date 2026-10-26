package io.github.mangi.eta.data.repository

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 测试版首启同意状态。
 *
 * 之所以逐项落键而不是只存一个「已同意」布尔：敏感个人信息与「用于模型训练」在法律上
 * 需要**单独同意**，将来要举证「单独同意过哪一项」，只能靠逐项记录。
 */
data class TestConsentState(
    val consentVersion: Int = 0,
    val acceptedAt: Long = 0L,
    val acceptedVersionName: String = "",
    val acceptedVersionCode: Int = 0,
    val agreementHash: String = "",
    val ackTerms: Boolean = false,
    val ackSensitiveScreen: Boolean = false,
    val ackAiOutput: Boolean = false,
    val revokedAt: Long = 0L,
) {
    /** 三个必需勾选齐全 + 协议版本与文本未变 + 未撤回，才算有效同意。 */
    fun isAcceptedFor(currentVersion: Int, currentHash: String): Boolean =
        revokedAt == 0L &&
            consentVersion == currentVersion &&
            currentHash.isNotEmpty() &&
            agreementHash == currentHash &&
            ackTerms && ackSensitiveScreen && ackAiOutput
}

/**
 * 测试版同意记录的唯一真相。
 *
 * 界面只读 [state]，不自己算「是否已同意」；运行时（服务、无障碍、任务调度）也读这里，
 * 避免出现「界面说同意了、后台认为没同意」两份判定。
 */
object TestConsentRepository {

    /** 协议版本：改动条款要递增，会让已同意用户重新看到同意页。 */
    const val CONSENT_VERSION = 1

    /** 协议与隐私全文；全文算 hash，文案改动即触发重新同意。 */
    const val ASSET_TERMS = "legal/beta-terms-zh-Hans.md"
    const val ASSET_PRIVACY = "legal/beta-privacy-zh-Hans.md"

    private const val PREFS_NAME = "eta_test_consent"
    private const val KEY_CONSENT_VERSION = "consent_version"
    private const val KEY_AGREEMENT_HASH = "agreement_sha256"
    private const val KEY_ACCEPTED_AT = "accepted_at"
    private const val KEY_ACCEPTED_VERSION_NAME = "accepted_version_name"
    private const val KEY_ACCEPTED_VERSION_CODE = "accepted_version_code"
    private const val KEY_ACK_TERMS = "ack_terms"
    private const val KEY_ACK_SENSITIVE_SCREEN = "ack_sensitive_screen"
    private const val KEY_ACK_AI_OUTPUT = "ack_ai_output"
    private const val KEY_REVOKED_AT = "revoked_at"

    /** 提示条关闭记录：按版本号记，装了新测试版会再提示一次。 */
    private const val KEY_BANNER_DISMISSED_VERSION = "banner_dismissed_version"

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var prefs: SharedPreferences? = null

    @Volatile
    private var cachedAgreementHash: String? = null

    private val _state = MutableStateFlow(TestConsentState())
    val state: StateFlow<TestConsentState> = _state.asStateFlow()

    /** 由 [io.github.mangi.eta.EtaApp] 在进程启动时调用；服务与 UI 共用同一份状态。 */
    fun init(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            val ctx = context.applicationContext
            appContext = ctx
            prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
        reload()
    }

    fun current(): TestConsentState = _state.value

    /** 运行时守门：服务、无障碍、悬浮窗启动前都问这一句。 */
    fun isAccepted(): Boolean =
        _state.value.isAcceptedFor(CONSENT_VERSION, agreementHash())

    /** 两份全文的 SHA-256；文本一变，旧同意自动失效。 */
    fun agreementHash(): String {
        cachedAgreementHash?.let { return it }
        val ctx = appContext ?: return ""
        val digest = MessageDigest.getInstance("SHA-256")
        val loaded = runCatching {
            listOf(ASSET_TERMS, ASSET_PRIVACY).forEach { name ->
                ctx.assets.open(name).use { input ->
                    val buffer = ByteArray(8 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        digest.update(buffer, 0, read)
                    }
                }
            }
        }
        if (loaded.isFailure) return ""
        val hash = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        cachedAgreementHash = hash
        return hash
    }

    /** 已关闭提示条的版本号；小于当前版本号（含 0）表示这次仍要显示。 */
    fun bannerDismissedVersion(): Int =
        prefs?.getInt(KEY_BANNER_DISMISSED_VERSION, 0) ?: 0

    fun dismissBanner(versionCode: Int) {
        prefs?.edit()?.putInt(KEY_BANNER_DISMISSED_VERSION, versionCode)?.apply()
    }

    fun readAgreement(assetName: String): String {
        val ctx = appContext ?: return ""
        return runCatching {
            ctx.assets.open(assetName).bufferedReader().use { it.readText() }
        }.getOrDefault("")
    }

    fun accept(
        ackTerms: Boolean,
        ackSensitiveScreen: Boolean,
        ackAiOutput: Boolean,
        versionName: String,
        versionCode: Int,
    ) {
        val store = prefs ?: return
        val hash = agreementHash()
        store.edit()
            .putInt(KEY_CONSENT_VERSION, CONSENT_VERSION)
            .putString(KEY_AGREEMENT_HASH, hash)
            .putLong(KEY_ACCEPTED_AT, System.currentTimeMillis())
            .putString(KEY_ACCEPTED_VERSION_NAME, versionName)
            .putInt(KEY_ACCEPTED_VERSION_CODE, versionCode)
            .putBoolean(KEY_ACK_TERMS, ackTerms && ackSensitiveScreen && ackAiOutput)
            .putBoolean(KEY_ACK_SENSITIVE_SCREEN, ackSensitiveScreen)
            .putBoolean(KEY_ACK_AI_OUTPUT, ackAiOutput)
            .putLong(KEY_REVOKED_AT, 0L)
            .apply()
        reload()
    }

    /** 撤回全部同意：清空所有 ack/opt 位并记撤回时间；撤回前的处理不受影响。 */
    fun revoke() {
        val store = prefs ?: return
        store.edit()
            .putInt(KEY_CONSENT_VERSION, 0)
            .putString(KEY_AGREEMENT_HASH, "")
            .putBoolean(KEY_ACK_TERMS, false)
            .putBoolean(KEY_ACK_SENSITIVE_SCREEN, false)
            .putBoolean(KEY_ACK_AI_OUTPUT, false)
            .putLong(KEY_REVOKED_AT, System.currentTimeMillis())
            .apply()
        reload()
    }

    private fun reload() {
        val store = prefs ?: return
        _state.value = TestConsentState(
            consentVersion = store.getInt(KEY_CONSENT_VERSION, 0),
            acceptedAt = store.getLong(KEY_ACCEPTED_AT, 0L),
            acceptedVersionName = store.getString(KEY_ACCEPTED_VERSION_NAME, "").orEmpty(),
            acceptedVersionCode = store.getInt(KEY_ACCEPTED_VERSION_CODE, 0),
            agreementHash = store.getString(KEY_AGREEMENT_HASH, "").orEmpty(),
            ackTerms = store.getBoolean(KEY_ACK_TERMS, false),
            ackSensitiveScreen = store.getBoolean(KEY_ACK_SENSITIVE_SCREEN, false),
            ackAiOutput = store.getBoolean(KEY_ACK_AI_OUTPUT, false),
            revokedAt = store.getLong(KEY_REVOKED_AT, 0L),
        )
    }
}
