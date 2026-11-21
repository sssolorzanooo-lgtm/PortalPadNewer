package com.portalpad.app

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.portalpad.app.data.DockConfig
import com.portalpad.app.service.CursorStyle
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
    fun inputInjector_seamlessBlackMouseAndWhiteTouchpadCursorHandoff() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val injector = InputInjector({ FakeAccess() }, context)

        // Default state: crisp white cursor, visible.
        assertEquals(CursorStyle.WHITE, injector.cursorStyle.value)
        assertTrue(injector.cursorVisible.value)

        // External mouse connects on external display: cursor remains visible for touchpad too.
        injector.displayId = 2
        injector.updateExternalMouseConnected(true)
        assertTrue(injector.externalMouseConnected.value)
        assertTrue(injector.cursorVisible.value)

        // External mouse moves: switches style to BLACK and continues from shared cursor coordinate.
        val beforeMouse = injector.cursorPosition.value
        injector.notifyExternalMouseSourceActive(grabbed = true)
        injector.pointerMove(24f, 16f)
        assertEquals(CursorStyle.BLACK, injector.cursorStyle.value)
        assertTrue(injector.cursorVisible.value)
        val afterMouse = injector.cursorPosition.value
        assertEquals(beforeMouse.first + 24f, afterMouse.first, 0.01f)
        assertEquals(beforeMouse.second + 16f, afterMouse.second, 0.01f)

        // Touchpad immediately takes over from the external mouse's exact coordinates and turns WHITE.
        injector.notifyTouchpadOrAirMouseActive()
        injector.pointerMove(-10f, 8f)
        assertEquals(CursorStyle.WHITE, injector.cursorStyle.value)
        assertTrue(injector.cursorVisible.value)
        val afterTouchpad = injector.cursorPosition.value
        assertEquals(afterMouse.first - 10f, afterTouchpad.first, 0.01f)
        assertEquals(afterMouse.second + 8f, afterTouchpad.second, 0.01f)

        // External mouse takes back over seamlessly.
        injector.notifyExternalMouseSourceActive(grabbed = true)
        assertEquals(CursorStyle.BLACK, injector.cursorStyle.value)
        assertTrue(injector.cursorVisible.value)

        // Remote mode resets style back to WHITE.
        injector.setExternalMouseRemoteMode(true)
        assertEquals(CursorStyle.WHITE, injector.cursorStyle.value)
        assertTrue(injector.cursorVisible.value)
        injector.setExternalMouseRemoteMode(false)

        // Un-grabbed fallback briefly hides overlay while updating BLACK style, but touchpad restores it at once.
        injector.notifyExternalMouseMoving(idleRestoreMs = 500L)
        assertEquals(CursorStyle.BLACK, injector.cursorStyle.value)
        assertFalse(injector.cursorVisible.value)
        injector.notifyTouchpadOrAirMouseActive()
        assertEquals(CursorStyle.WHITE, injector.cursorStyle.value)
        assertTrue(injector.cursorVisible.value)

        // Disconnecting resets style to WHITE and leaves touchpad cursor visible.
        injector.notifyExternalMouseSourceActive(grabbed = true)
        assertEquals(CursorStyle.BLACK, injector.cursorStyle.value)
        injector.updateExternalMouseConnected(false)
        assertEquals(CursorStyle.WHITE, injector.cursorStyle.value)
        assertTrue(injector.cursorVisible.value)
    }
}
