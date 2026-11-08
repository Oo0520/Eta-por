package io.github.mangi.eta.agent.voice

import android.app.Application
import android.content.ComponentName
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import io.github.mangi.eta.testsupport.EtaRobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(EtaRobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SystemSpeechRecognizerTest {
    private fun service(
        packageName: String,
        name: String,
        permission: String? = null,
        exported: Boolean = true,
    ) = ResolveInfo().apply {
        serviceInfo = ServiceInfo().apply {
            this.packageName = packageName
            this.name = name
            this.permission = permission
            this.exported = exported
            this.enabled = true
            applicationInfo = ApplicationInfo().apply { enabled = true }
        }
    }

    @Test fun configuredRecognitionServiceCanOmitBindPermission() {
        val configured = ComponentName("com.google.android.tts", "GoogleTTSRecognitionService")
        assertEquals(
            configured,
            SystemSpeechRecognizer.selectExternalService(
                listOf(service(configured.packageName, configured.className)),
                "io.github.mangi.eta",
                configured,
            ),
        )
    }

    @Test fun unconfiguredServiceWithoutBindPermissionIsNotSelected() {
        assertNull(
            SystemSpeechRecognizer.selectExternalService(
                listOf(service("third.party", "Recognizer")),
                "io.github.mangi.eta",
                null,
            ),
        )
    }

    @Test fun unexportedAndSelfRecognitionServicesAreExcluded() {
        val configured = ComponentName("third.party", "Hidden")
        assertNull(
            SystemSpeechRecognizer.selectExternalService(
                listOf(
                    service("third.party", "Hidden", exported = false),
                    service(
                        "io.github.mangi.eta",
                        "io.github.mangi.eta.agent.voice.EtaRecognitionService",
                        permission = BIND_SPEECH_RECOGNITION_SERVICE,
                    ),
                ),
                "io.github.mangi.eta",
                configured,
            ),
        )
    }

    /**
     * 正式版 / 测试版 / 改包名副本会同时出现在查询结果里。任一 Eta 副本被设为系统默认识别服务时，
     * 它内部取外部服务都不能选中自己或兄弟包，否则会 Eta → Eta 回环。
     */
    @Test fun siblingEtaRecognitionServicesAreExcluded() {
        val third = ComponentName("third.party", "third.party.Recognizer")
        assertEquals(
            third,
            SystemSpeechRecognizer.selectExternalService(
                listOf(
                    service(
                        "io.github.mangi.eta.beta",
                        "io.github.mangi.eta.agent.voice.EtaRecognitionService",
                        permission = BIND_SPEECH_RECOGNITION_SERVICE,
                    ),
                    service(
                        "io.github.mangi.etb",
                        "io.github.mangi.eta.agent.voice.EtaRecognitionService",
                        permission = BIND_SPEECH_RECOGNITION_SERVICE,
                    ),
                    service(third.packageName, third.className, permission = BIND_SPEECH_RECOGNITION_SERVICE),
                ),
                "io.github.mangi.eta.beta",
                null,
            ),
        )
    }

    @Test fun configuredEtaServiceIsIgnoredInFavorOfExternalOne() {
        val third = ComponentName("third.party", "third.party.Recognizer")
        assertEquals(
            third,
            SystemSpeechRecognizer.selectExternalService(
                listOf(
                    service(
                        "io.github.mangi.etb",
                        "io.github.mangi.eta.agent.voice.EtaRecognitionService",
                        permission = BIND_SPEECH_RECOGNITION_SERVICE,
                    ),
                    service(third.packageName, third.className, permission = BIND_SPEECH_RECOGNITION_SERVICE),
                ),
                "io.github.mangi.eta",
                ComponentName("io.github.mangi.etb", "io.github.mangi.eta.agent.voice.EtaRecognitionService"),
            ),
        )
    }

    private companion object {
        const val BIND_SPEECH_RECOGNITION_SERVICE = "android.permission.BIND_SPEECH_RECOGNITION_SERVICE"
    }
}
