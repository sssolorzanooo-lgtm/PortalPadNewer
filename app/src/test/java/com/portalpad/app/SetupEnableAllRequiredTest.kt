package com.portalpad.app

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.portalpad.app.data.PreferencesRepository
import com.portalpad.app.service.ElevatedAccess
import com.portalpad.app.ui.settings.EnableAllSettingsResult
import com.portalpad.app.ui.settings.buildEnableAllShellCommands
import com.portalpad.app.ui.settings.formatEnableAllResultNote
import com.portalpad.app.ui.settings.mergeColonSeparatedComponent
import com.portalpad.app.ui.settings.runEnableAllRequiredSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SetupEnableAllRequiredTest {

    private class RecordingAccess(
        private val existingNotif: String = "",
        private val existingA11y: String = "com.other/.OtherA11yService",
    ) : ElevatedAccess {
        override val mode = ElevatedAccess.Mode.SHIZUKU
        override val isReady = true
        override val canInjectInProcess = true
        val commands = mutableListOf<String>()

        override fun execShell(command: String): String {
            commands += command
            return when (command) {
                "settings get secure enabled_notification_listeners" -> existingNotif
                "settings get secure enabled_accessibility_services" -> existingA11y
                else -> ""
            }
        }

        override fun startActivityOnDisplay(componentName: String, displayId: Int) = false
        override fun resizeTask(taskId: Int, left: Int, top: Int, right: Int, bottom: Int) = Unit
    }

    @Test
    fun markUnansweredSetupPermissionsYes_setsBlankSetupCardsToYes_andPreservesExplicitNo() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val prefs = PreferencesRepository(context)
        prefs.clearAllForFactoryReset()

        // Explicit user opt-out remains untouched; unanswered External mouse enables.
        prefs.setString(PreferencesRepository.Keys.SETUP_WANTS_VOICE_SEARCH, "no")

        prefs.markUnansweredSetupPermissionsYes()

        assertEquals("yes", prefs.string(PreferencesRepository.Keys.SETUP_WANTS_NOTIF_ACCESS).first())
        assertEquals("no", prefs.string(PreferencesRepository.Keys.SETUP_WANTS_VOICE_SEARCH).first())
        assertEquals("yes", prefs.string(PreferencesRepository.Keys.SETUP_WANTS_BACKGROUND).first())
        assertEquals("yes", prefs.string(PreferencesRepository.Keys.SETUP_WANTS_PHONE_STATE).first())
        assertEquals("yes", prefs.string(PreferencesRepository.Keys.SETUP_WANTS_ACCESSIBILITY).first())
        assertEquals("yes", prefs.string(PreferencesRepository.Keys.SETUP_MOUSE_ANSWER).first())
        assertTrue(prefs.bool(PreferencesRepository.Keys.EXT_MOUSE_ENABLED, false).first())

        // Explicit "no" on External mouse must remain untouched.
        prefs.setString(PreferencesRepository.Keys.SETUP_MOUSE_ANSWER, "no")
        prefs.setBool(PreferencesRepository.Keys.EXT_MOUSE_ENABLED, false)
        prefs.markUnansweredSetupPermissionsYes()
        assertEquals("no", prefs.string(PreferencesRepository.Keys.SETUP_MOUSE_ANSWER).first())
        assertFalse(prefs.bool(PreferencesRepository.Keys.EXT_MOUSE_ENABLED, true).first())
    }

    @Test
    fun mergeColonSeparatedComponent_handlesNullEmptyExistingAndDuplicates() {
        val a11y = "com.portalpad.app/com.portalpad.app.service.PortalPadAccessibilityService"
        assertEquals(a11y, mergeColonSeparatedComponent("", a11y))
        assertEquals(a11y, mergeColonSeparatedComponent("null", a11y))
        assertEquals(
            "com.other/.Service:$a11y",
            mergeColonSeparatedComponent("com.other/.Service", a11y),
        )
        assertEquals(
            "com.other/.Service:$a11y",
            mergeColonSeparatedComponent("com.other/.Service:$a11y", a11y),
        )
    }

    @Test
    fun buildEnableAllShellCommands_omitsCameraAndSecureOverlaySettings() {
        val cmds = buildEnableAllShellCommands(
            pkg = "com.aistudio.portalpad.kxmpzq",
            notifComponent = "com.aistudio.portalpad.kxmpzq/com.portalpad.app.service.PortalPadNotificationListenerService",
            sdkInt = 35,
        )
        assertTrue(cmds.any { it.contains("SYSTEM_ALERT_WINDOW allow") })
        assertTrue(cmds.any { it.contains("RECORD_AUDIO") })
        assertTrue(cmds.any { it.contains("POST_NOTIFICATIONS") })
        assertTrue(cmds.any { it.contains("BLUETOOTH_CONNECT") })
        assertFalse(cmds.any { it.contains("CAMERA") })
        assertFalse(cmds.any { it.contains("secure_overlay_settings") })
    }

    @Test
    fun runEnableAllRequiredSettings_attemptsAccessibilityEnable_andReportsBlockedWhenOsRejects() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val fakeAccess = RecordingAccess()
        var ticks = 0

        val result = runEnableAllRequiredSettings(
            context = context,
            shellAccess = fakeAccess,
            onTick = { ticks++ },
            ioDispatcher = Dispatchers.Unconfined,
            pollIntervalMs = 0L,
            pollAttempts = 2,
            checkOverlay = { true },
            checkNotif = { true },
            checkA11y = { false },
        )

        val expectedA11y = "${context.packageName}/com.portalpad.app.service.PortalPadAccessibilityService"
        assertTrue(
            fakeAccess.commands.contains(
                "settings put secure enabled_accessibility_services com.other/.OtherA11yService:$expectedA11y",
            ),
        )
        assertTrue(fakeAccess.commands.contains("settings put secure accessibility_enabled 1"))
        assertFalse(fakeAccess.commands.any { it.contains("secure_overlay_settings") })
        assertFalse(result.a11yOk)
        assertTrue(ticks >= 2)

        val note = formatEnableAllResultNote(
            EnableAllSettingsResult(overlayOk = true, notifOk = true, a11yOk = false),
        )
        assertTrue(note.contains("Accessibility"))
    }
}
