package com.portalpad.app

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.portalpad.app.data.DockConfig
import com.portalpad.app.service.ElevatedAccess
import com.portalpad.app.service.InputInjector
import com.portalpad.app.ui.dock.computeDockChromeMetrics
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DockCompactAndPointerHandoffTest {

    private class FakeAccess : ElevatedAccess {
        override val mode: ElevatedAccess.Mode = ElevatedAccess.Mode.SHIZUKU
        override val isReady: Boolean = false
        override val canInjectInProcess: Boolean = false
        override fun execShell(command: String): String = ""
        override fun startActivityOnDisplay(componentName: String, displayId: Int): Boolean = false
        override fun resizeTask(taskId: Int, left: Int, top: Int, right: Int, bottom: Int) {}
    }

    @Test
    fun dockConfig_showLabelsDefaultsTrueAndRoundTrips() {
        val json = Json { ignoreUnknownKeys = true }
        val legacy = json.decodeFromString<DockConfig>("{}")
        assertTrue(legacy.showLabels)

        val hidden = DockConfig(showLabels = false, labelSizeSp = 14)
        val encoded = json.encodeToString(DockConfig.serializer(), hidden)
        val decoded = json.decodeFromString<DockConfig>(encoded)
        assertFalse(decoded.showLabels)
        assertEquals(14, decoded.labelSizeSp)
    }

    @Test
    fun computeDockChromeMetrics_hidingTitlesSlimsDockAndCompactsStatusChrome() {
        val shownCfg = DockConfig(showLabels = true, iconSizeDp = 60, labelSizeSp = 13)
        val hiddenCfg = shownCfg.copy(showLabels = false)

        val shown = computeDockChromeMetrics(shownCfg, dpiComp = 1f)
        val hidden = computeDockChromeMetrics(hiddenCfg, dpiComp = 1f)

        assertTrue(shown.showTileTitles)
        assertEquals(13f, shown.tileLabelSizeSp, 0.01f)
        assertEquals(1f, shown.statusCompactScale, 0.01f)

        assertFalse(hidden.showTileTitles)
        assertEquals(0f, hidden.tileLabelSizeSp, 0.01f)
        assertTrue(hidden.tileBandDp < shown.tileBandDp)
        assertTrue(hidden.approxThicknessDp < shown.approxThicknessDp)
        assertTrue(hidden.labelZoneDp < shown.labelZoneDp)
        assertTrue(hidden.statusIconSizeDp < shown.statusIconSizeDp)
        assertTrue(hidden.statusLabelSp < shown.statusLabelSp)
        assertTrue(hidden.statusLabelSp >= 8.5f)
    }

    @Test
    fun inputInjector_unifiedBlackPointerHidesWhiteCursorUntilMouseDisconnectsOrRemoteMode() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val injector = InputInjector({ FakeAccess() }, context)

        // Display 0 (no external display): white cursor stays enabled regardless.
        injector.updateExternalMouseConnected(true)
        assertFalse(injector.blackPointerModeActive)
        assertTrue(injector.cursorVisible.value)

        // External display attaches while mouse is connected: unified black pointer takes over.
        injector.displayId = 2
        assertTrue(injector.blackPointerModeActive)
        assertFalse(injector.cursorVisible.value)

        // Touchpad movement must NOT resurrect the white cursor while external mouse is attached.
        injector.notifyTouchpadOrAirMouseActive()
        assertFalse(injector.cursorVisible.value)

        // Remote mode pauses black-pointer unification.
        injector.setExternalMouseRemoteMode(true)
        assertFalse(injector.blackPointerModeActive)
        assertTrue(injector.cursorVisible.value)

        // Returning to Mouse mode re-engages unified black pointer.
        injector.setExternalMouseRemoteMode(false)
        assertTrue(injector.blackPointerModeActive)
        assertFalse(injector.cursorVisible.value)

        // Unplugging the external mouse restores PortalPad's white cursor.
        injector.updateExternalMouseConnected(false)
        assertFalse(injector.blackPointerModeActive)
        assertTrue(injector.cursorVisible.value)
    }
}
