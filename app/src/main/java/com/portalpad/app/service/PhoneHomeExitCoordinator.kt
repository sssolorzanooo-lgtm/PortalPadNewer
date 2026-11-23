package com.portalpad.app.service

import android.os.SystemClock

/**
 * Coordinates single- vs. double-Home interception when [com.portalpad.app.TrackpadActivity]
 * is actively showing Touchpad or Air Mouse with an external display connected.
 *
 * First phone Home press/gesture triggers the configured external display Home action and
 * immediately re-anchors the input surface on the phone. A second Home within
 * [DOUBLE_HOME_WINDOW_MS] lets the user exit cleanly and silently to their phone launcher.
 */
class PhoneHomeExitCoordinator(
    private val clockMs: () -> Long = { SystemClock.elapsedRealtime() },
) {

    enum class LeaveDecision {
        /** First Home tap/gesture: fire external Home action and re-front TrackpadActivity. */
        TRIGGER_EXTERNAL_HOME_AND_REFRONT,

        /** Second Home tap/gesture within [DOUBLE_HOME_WINDOW_MS]: exit silently to phone. */
        EXIT_ON_DOUBLE_HOME,

        /** Feature disabled, wrong mode (Remote), or no external display: normal OS exit. */
        EXIT_NORMALLY,

        /** Internal navigation (Settings, Keyboard Relay, App Picker, dialogs): do not rebound. */
        IGNORE_INTERNAL_NAVIGATION,
    }

    private val lock = Any()
    private var lastInterceptAtMs: Long? = null
    private var internalNavigationUntilMs: Long = 0L
    private var hideLeaveSuppressUntilMs: Long = 0L
    private var bubbleSuppressUntilMs: Long = 0L
    private var nextToken: Long = 0L
    private var pendingReboundToken: Long = 0L
    private var reboundSawPause: Boolean = false

    fun onUserLeaveHint(
        featureEnabled: Boolean,
        isTouchpadOrAirMouse: Boolean,
        hasExternalDisplay: Boolean,
    ): LeaveDecision = synchronized(lock) {
        val now = clockMs()
        if (now < hideLeaveSuppressUntilMs) {
            hideLeaveSuppressUntilMs = 0L
            clearReboundLocked()
            lastInterceptAtMs = null
            return@synchronized LeaveDecision.IGNORE_INTERNAL_NAVIGATION
        }
        if (now < internalNavigationUntilMs) {
            clearReboundLocked()
            lastInterceptAtMs = null
            return@synchronized LeaveDecision.IGNORE_INTERNAL_NAVIGATION
        }
        if (!featureEnabled || !isTouchpadOrAirMouse || !hasExternalDisplay) {
            clearReboundLocked()
            lastInterceptAtMs = null
            return@synchronized LeaveDecision.EXIT_NORMALLY
        }

        val prev = lastInterceptAtMs
        if (prev != null && (now - prev) in 0L..DOUBLE_HOME_WINDOW_MS) {
            clearReboundLocked()
            lastInterceptAtMs = null
            return@synchronized LeaveDecision.EXIT_ON_DOUBLE_HOME
        }

        lastInterceptAtMs = now
        pendingReboundToken = ++nextToken
        reboundSawPause = false
        bubbleSuppressUntilMs = now + BUBBLE_SUPPRESS_WINDOW_MS
        LeaveDecision.TRIGGER_EXTERNAL_HOME_AND_REFRONT
    }

    /**
     * Marks an imminent internal activity launch (e.g. Settings, App Picker, permission
     * prompt, or Keyboard Relay) so Android's accompanying `onUserLeaveHint` does not
     * get mistaken for a phone Home action.
     */
    fun markInternalNavigation(guardDurationMs: Long = INTERNAL_NAV_GUARD_MS) = synchronized(lock) {
        val now = clockMs()
        internalNavigationUntilMs = maxOf(internalNavigationUntilMs, now + guardDurationMs)
        clearReboundLocked()
        lastInterceptAtMs = null
    }

    /**
     * Marks an explicit Hide tap from the top bar so returning to the previous phone app
     * never triggers external Home or rebounds TrackpadActivity back to the foreground.
     */
    fun markExplicitHide(guardDurationMs: Long = INTERNAL_NAV_GUARD_MS) = synchronized(lock) {
        val now = clockMs()
        hideLeaveSuppressUntilMs = maxOf(hideLeaveSuppressUntilMs, now + guardDurationMs)
        clearReboundLocked()
        lastInterceptAtMs = null
    }

    fun clearExplicitHideSuppression() = synchronized(lock) {
        hideLeaveSuppressUntilMs = 0L
    }

    fun markPaused() = synchronized(lock) {
        if (pendingReboundToken != 0L) {
            reboundSawPause = true
        }
    }

    fun consumePendingReboundOnResume(): Boolean = synchronized(lock) {
        if (pendingReboundToken != 0L && reboundSawPause) {
            clearReboundLocked()
            return@synchronized true
        }
        false
    }

    fun activeReboundToken(): Long = synchronized(lock) { pendingReboundToken }

    fun shouldRefrontForToken(token: Long): Boolean = synchronized(lock) {
        token != 0L && pendingReboundToken == token
    }

    fun shouldSuppressBubble(): Boolean = synchronized(lock) {
        clockMs() < bubbleSuppressUntilMs
    }

    fun clearBubbleSuppression() = synchronized(lock) {
        bubbleSuppressUntilMs = 0L
    }

    fun reset() = synchronized(lock) {
        clearReboundLocked()
        lastInterceptAtMs = null
        internalNavigationUntilMs = 0L
        hideLeaveSuppressUntilMs = 0L
    }

    private fun clearReboundLocked() {
        pendingReboundToken = 0L
        reboundSawPause = false
        bubbleSuppressUntilMs = 0L
    }

    companion object {
        const val DOUBLE_HOME_WINDOW_MS = 1500L
        const val BUBBLE_SUPPRESS_WINDOW_MS = 650L
        const val INTERNAL_NAV_GUARD_MS = 1500L
    }
}
