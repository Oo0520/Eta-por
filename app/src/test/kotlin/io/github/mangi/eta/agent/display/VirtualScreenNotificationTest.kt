package io.github.mangi.eta.agent.display

import android.app.Application
import android.app.NotificationManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import io.github.mangi.eta.testsupport.EtaRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(EtaRobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class VirtualScreenNotificationTest {
    @Test
    fun notificationOpensMainAppAndOldCleanupCannotRemoveNewSessionNotification() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val manager = context.getSystemService(NotificationManager::class.java)
        VirtualScreenNotification.show(context, "first")
        val notification = manager.activeNotifications.single().notification
        val intent = shadowOf(notification.contentIntent).savedIntent
        // 旧的全屏查看页已删除：通知点击落到主界面。
        assertEquals(io.github.mangi.eta.ui.MainActivity::class.java.name, intent.component!!.className)
        assertEquals("eta-virtual-screen://view/first", intent.dataString)
        assertTrue(notification.contentIntent.isImmutable)
        VirtualScreenNotification.cancel(context, "first")
        VirtualScreenNotification.show(context, "second")
        VirtualScreenNotification.cancel(context, "first")
        assertEquals("second", manager.activeNotifications.single().tag)
        VirtualScreenNotification.cancel(context, "second")
        assertTrue(manager.activeNotifications.isEmpty())
    }
}
