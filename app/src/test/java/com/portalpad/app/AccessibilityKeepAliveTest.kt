package com.portalpad.app

import android.app.Application
import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.portalpad.app.data.PreferencesRepository
import com.portalpad.app.service.AccessibilityKeepAlive
import com.portalpad.app.service.ElevatedAccess
import kotlinx.coroutines.Dispatchers
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
class AccessibilityKeepAliveTest {

    private class FakeShellAccess(
        override val isReady: Boolean = true,
        private val onPutSecure: (String, String) -> Unit = { _, _ -> },
        private val currentA11yList: () -> String = { "" },
    ) : ElevatedAccess {
        override val mode = ElevatedAccess.Mode.SHIZUKU
        override val canInjectInProcess = true
        val commands = mutableListOf<String>()

        override fun execShell(command: String): String {
            commands += command
            return when {
                command == "settings get secure enabled_accessibility_services" -> currentA11yList()
                command.startsWith("settings put secure enabled_accessibility_services ") -> {
                    onPutSecure(
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        command.removePrefix("settings put secure enabled_accessibility_services "),
                    )
                    ""
                }
                command == "settings put secure accessibility_enabled 1" -> {
                    onPutSecure(Settings.Secure.ACCESSIBILITY_ENABLED, "1")
                    ""
                }
                else -> ""
            }
        }

        override fun startActivityOnDisplay(componentName: String, displayId: Int) = false
        override fun resizeTask(taskId: Int, left: Int, top: Int, right: Int, bottom: Int) = Unit
    }

    @Test
    fun ensureEnabledIfWanted_skipsWhenSetupAccessibilityIsNotYes() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val prefs = PreferencesRepository(context)
        prefs.clearAllForFactoryReset()
        val shell = FakeShellAccess()

        val resultUnset = AccessibilityKeepAlive.ensureEnabledIfWanted(
            context = context,
            prefs = prefs,
            accessBackends = listOf(shell),
            ioDispatcher = Dispatchers.Unconfined,
            hasWriteSecureSettings = { true },
        )
        assertFalse(resultUnset)
        assertTrue(shell.commands.isEmpty())

        prefs.setString(PreferencesRepository.Keys.SETUP_WANTS_ACCESSIBILITY, "no")
        val resultNo = AccessibilityKeepAlive.ensureEnabledIfWanted(
            context = context,
            prefs = prefs,
            accessBackends = listOf(shell),
            ioDispatcher = Dispatchers.Unconfined,
            hasWriteSecureSettings = { true },
        )
        assertFalse(resultNo)
        assertTrue(shell.commands.isEmpty())
    }

    @Test
    fun ensureEnabledIfWanted_restoresViaWriteSecureSettingsWithoutShell() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val prefs = PreferencesRepository(context)
        prefs.clearAllForFactoryReset()
        prefs.setString(PreferencesRepository.Keys.SETUP_WANTS_ACCESSIBILITY, "yes")

        val secureStrings = mutableMapOf(
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES to "com.other/.OtherService",
        )
        val secureInts = mutableMapOf(Settings.Secure.ACCESSIBILITY_ENABLED to 0)
        val offlineShell = FakeShellAccess(isReady = false)

        val restored = AccessibilityKeepAlive.ensureEnabledIfWanted(
            context = context,
            prefs = prefs,
            accessBackends = listOf(offlineShell),
            reason = "test-direct",
            ioDispatcher = Dispatchers.Unconfined,
            hasWriteSecureSettings = { true },
            readSecureString = { secureStrings[it] },
            readSecureInt = { k, d -> secureInts[k] ?: d },
            writeSecureString = { k, v -> secureStrings[k] = v; true },
            writeSecureInt = { k, v -> secureInts[k] = v; true },
        )

        val expected = AccessibilityKeepAlive.componentName(context)
        assertTrue(restored)
        assertEquals(
            "com.other/.OtherService:$expected",
            secureStrings[Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES],
        )
        assertEquals(1, secureInts[Settings.Secure.ACCESSIBILITY_ENABLED])
        assertTrue(offlineShell.commands.isEmpty())
    }

    @Test
    fun ensureEnabledIfWanted_fallsBackToShellAndGrantsWriteSecureSettings() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val prefs = PreferencesRepository(context)
        prefs.clearAllForFactoryReset()
        prefs.setString(PreferencesRepository.Keys.SETUP_WANTS_ACCESSIBILITY, "yes")

        val secureStrings = mutableMapOf(
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES to "com.other/.OtherService",
        )
        val secureInts = mutableMapOf(Settings.Secure.ACCESSIBILITY_ENABLED to 0)
        val readyShell = FakeShellAccess(
            isReady = true,
            currentA11yList = { secureStrings[Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES].orEmpty() },
            onPutSecure = { k, v ->
                if (k == Settings.Secure.ACCESSIBILITY_ENABLED) secureInts[k] = v.toInt()
                else secureStrings[k] = v
            },
        )

        val restored = AccessibilityKeepAlive.ensureEnabledIfWanted(
            context = context,
            prefs = prefs,
            accessBackends = listOf(readyShell),
            reason = "test-shell-fallback",
            ioDispatcher = Dispatchers.Unconfined,
            hasWriteSecureSettings = { false },
            readSecureString = { secureStrings[it] },
            readSecureInt = { k, d -> secureInts[k] ?: d },
        )

        val expected = AccessibilityKeepAlive.componentName(context)
        assertTrue(restored)
        assertTrue(readyShell.commands.contains("appops set ${context.packageName} ACCESS_RESTRICTED_SETTINGS allow"))
        assertTrue(readyShell.commands.contains("pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"))
        assertEquals(
            "com.other/.OtherService:$expected",
            secureStrings[Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES],
        )
        assertEquals(1, secureInts[Settings.Secure.ACCESSIBILITY_ENABLED])
    }
}
