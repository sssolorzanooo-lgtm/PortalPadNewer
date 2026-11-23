package com.portalpad.app

import android.app.Application
import com.portalpad.app.service.FloatingBubbleManager
import com.portalpad.app.service.PhoneHomeExitCoordinator
import com.portalpad.app.service.PhoneHomeExitCoordinator.LeaveDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PhoneHomeAndBubblePositionTest {

    @Test
    fun floatingBubbleInitialY_shiftsDownByOneButtonWidthAndClamps() {
        val heightPx = 2400
        val bubbleWidthPx = 168 // 56dp at 3.0 density
        val expected = (heightPx * 0.35f).toInt() + bubbleWidthPx
        assertEquals(expected, FloatingBubbleManager.computeInitialBubbleY(heightPx, bubbleWidthPx))

        // Clamps within viewport on very short displays.
        assertEquals(120, FloatingBubbleManager.computeInitialBubbleY(heightPixels = 220, sizePx = 100))
        assertEquals(0, FloatingBubbleManager.computeInitialBubbleY(heightPixels = 80, sizePx = 100))
    }

    @Test
    fun firstHomeInterceptsAndSecondHomeWithin1500msExitsSilently() {
        var now = 1000L
        val coordinator = PhoneHomeExitCoordinator(clockMs = { now })

        val first = coordinator.onUserLeaveHint(
            featureEnabled = true,
            isTouchpadOrAirMouse = true,
            hasExternalDisplay = true,
        )
        assertEquals(LeaveDecision.TRIGGER_EXTERNAL_HOME_AND_REFRONT, first)
        val token = coordinator.activeReboundToken()
        assertNotEquals(0L, token)
        assertTrue(coordinator.shouldRefrontForToken(token))
        assertTrue(coordinator.shouldSuppressBubble())

        // Simulated pause -> resume rebound completes before the user presses Home a 2nd time.
        now += 150L
        coordinator.markPaused()
        assertTrue(coordinator.consumePendingReboundOnResume())
        assertFalse(coordinator.shouldRefrontForToken(token))
        assertFalse(coordinator.shouldSuppressBubble())

        // Second Home at +1400ms (within 1.5s of the 1st Home) exits to phone.
        now = 2400L
        val second = coordinator.onUserLeaveHint(
            featureEnabled = true,
            isTouchpadOrAirMouse = true,
            hasExternalDisplay = true,
        )
        assertEquals(LeaveDecision.EXIT_ON_DOUBLE_HOME, second)
        assertEquals(0L, coordinator.activeReboundToken())
        assertFalse(coordinator.shouldSuppressBubble())
    }

    @Test
    fun secondHomeAfterWindowExpiryTriggersExternalHomeAgain() {
        var now = 5000L
        val coordinator = PhoneHomeExitCoordinator(clockMs = { now })

        assertEquals(
            LeaveDecision.TRIGGER_EXTERNAL_HOME_AND_REFRONT,
            coordinator.onUserLeaveHint(true, isTouchpadOrAirMouse = true, hasExternalDisplay = true),
        )
        val firstToken = coordinator.activeReboundToken()

        // Advance past 1500ms window -> acts as a fresh 1st Home.
        now += PhoneHomeExitCoordinator.DOUBLE_HOME_WINDOW_MS + 1L
        val next = coordinator.onUserLeaveHint(
            featureEnabled = true,
            isTouchpadOrAirMouse = true,
            hasExternalDisplay = true,
        )
        assertEquals(LeaveDecision.TRIGGER_EXTERNAL_HOME_AND_REFRONT, next)
        assertNotEquals(firstToken, coordinator.activeReboundToken())
    }

    @Test
    fun disabledSettingRemoteModeNoDisplayOrInternalNavBypassIntercept() {
        var now = 10_000L
        val coordinator = PhoneHomeExitCoordinator(clockMs = { now })

        // Disabled setting exits normally.
        assertEquals(
            LeaveDecision.EXIT_NORMALLY,
            coordinator.onUserLeaveHint(featureEnabled = false, isTouchpadOrAirMouse = true, hasExternalDisplay = true),
        )
        // Remote / Media mode exits normally.
        assertEquals(
            LeaveDecision.EXIT_NORMALLY,
            coordinator.onUserLeaveHint(featureEnabled = true, isTouchpadOrAirMouse = false, hasExternalDisplay = true),
        )
        // No external display exits normally.
        assertEquals(
            LeaveDecision.EXIT_NORMALLY,
            coordinator.onUserLeaveHint(featureEnabled = true, isTouchpadOrAirMouse = true, hasExternalDisplay = false),
        )
        // Launching Settings / Relay / Permissions suppresses Home intercept.
        coordinator.markInternalNavigation(guardDurationMs = 1500L)
        now += 100L
        assertEquals(
            LeaveDecision.IGNORE_INTERNAL_NAVIGATION,
            coordinator.onUserLeaveHint(featureEnabled = true, isTouchpadOrAirMouse = true, hasExternalDisplay = true),
        )
    }

    @Test
    fun explicitHide_suppressesExternalHomeAndClearsOnResumeOrConsume() {
        var now = 20_000L
        val coordinator = PhoneHomeExitCoordinator(clockMs = { now })

        // Clicking Hide marks explicit hide, cancelling any pending intercept window.
        coordinator.markExplicitHide()
        now += 50L
        assertEquals(
            LeaveDecision.IGNORE_INTERNAL_NAVIGATION,
            coordinator.onUserLeaveHint(featureEnabled = true, isTouchpadOrAirMouse = true, hasExternalDisplay = true),
        )
        assertEquals(0L, coordinator.activeReboundToken())
        assertFalse(coordinator.shouldSuppressBubble())

        // Consuming or returning to TrackpadActivity (onResume) clears hide suppression immediately.
        now += 50L
        assertEquals(
            LeaveDecision.TRIGGER_EXTERNAL_HOME_AND_REFRONT,
            coordinator.onUserLeaveHint(featureEnabled = true, isTouchpadOrAirMouse = true, hasExternalDisplay = true),
        )

        // If Android did not fire onUserLeaveHint during moveTaskToBack, clearExplicitHideSuppression()
        // on next onResume guarantees the next Home press still works immediately.
        coordinator.markExplicitHide()
        coordinator.clearExplicitHideSuppression()
        now += 20L
        assertEquals(
            LeaveDecision.TRIGGER_EXTERNAL_HOME_AND_REFRONT,
            coordinator.onUserLeaveHint(featureEnabled = true, isTouchpadOrAirMouse = true, hasExternalDisplay = true),
        )
    }
}
