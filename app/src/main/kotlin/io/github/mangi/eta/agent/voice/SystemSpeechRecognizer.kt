package io.github.mangi.eta.agent.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.provider.Settings
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

internal object SystemSpeechRecognizer {
    internal data class Source(val component: ComponentName?) {
        val label: String get() = component?.flattenToShortString() ?: "on_device"
    }

    internal data class Selection(val recognizer: SpeechRecognizer, val source: Source)

    fun create(context: Context): SpeechRecognizer? = select(context)?.recognizer

    fun select(context: Context): Selection? {
        val appContext = context.applicationContext
        resolveExternalService(appContext)?.let { component ->
            return Selection(SpeechRecognizer.createSpeechRecognizer(appContext, component), Source(component))
        }
        return if (SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)) {
            Selection(SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext), Source(null))
        } else {
            null
        }
    }

    fun create(context: Context, source: Source): SpeechRecognizer =
        if (source.component == null) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context.applicationContext)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context.applicationContext, source.component)
        }

    fun resolveExternalService(context: Context): ComponentName? {
        val configured = Settings.Secure.getString(
            context.contentResolver,
            VOICE_RECOGNITION_SERVICE,
        )?.let(ComponentName::unflattenFromString)
        val services = context.packageManager.queryIntentServices(
            Intent(RecognitionService.SERVICE_INTERFACE),
            PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
        )
        return selectExternalService(services, context.packageName, configured)
    }

    /**
     * 本机可能并存正式版、测试版等多个同签名的 Eta 副本，它们各自导出一个
     * [EtaRecognitionService]。谁被选为「系统默认语音识别」，它内部再调用这里取服务时
     * 会选回自己的兄弟包，形成 Eta → Eta 回环（表现为一句都识别不出来）。
     * 因此这里把所有 Eta 系副本都视为自身，只挑真正的外部识别服务。
     */
    internal fun selectExternalService(
        services: List<ResolveInfo>,
        ownPackage: String,
        configured: ComponentName?,
    ): ComponentName? = services
            .asSequence()
            .mapNotNull { it.serviceInfo }
            .filter {
                it.packageName != ownPackage && it.exported && it.enabled &&
                    it.applicationInfo?.enabled != false
            }
            .filter { !isEtaRecognitionHost(it) }
            .filter {
                it.permission == BIND_SPEECH_RECOGNITION_SERVICE
            }
            .sortedWith(
                compareByDescending<android.content.pm.ServiceInfo> {
                    ComponentName(it.packageName, it.name) == configured
                }.thenByDescending {
                    (it.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM != 0
                },
            )
            .map { ComponentName(it.packageName, it.name) }
            .firstOrNull()

    /**
     * 只认 Eta 自己的委托实现：`<包名>.agent.voice.EtaRecognitionService`。
     * 不能用「包名以 io.github.mangi 开头」判断——那会漏掉 io.github.mangi.etb 这类改包名副本，
     * 也会误伤恰好同名类的外部应用。
     */
    internal fun isEtaRecognitionHost(service: android.content.pm.ServiceInfo): Boolean {
        val declared = service.name ?: return false
        val expected = "${service.packageName}${ETA_RECOGNITION_SUFFIX}"
        return declared == expected || declared.endsWith(ETA_RECOGNITION_SUFFIX)
    }

    private const val VOICE_RECOGNITION_SERVICE = "voice_recognition_service"
    private const val BIND_SPEECH_RECOGNITION_SERVICE = "android.permission.BIND_SPEECH_RECOGNITION_SERVICE"
    private const val ETA_RECOGNITION_SUFFIX = ".agent.voice.EtaRecognitionService"
}
