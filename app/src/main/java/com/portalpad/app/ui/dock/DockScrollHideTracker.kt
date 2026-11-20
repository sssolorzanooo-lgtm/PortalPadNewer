package com.portalpad.app.ui.dock

/**
 * Pure directional scroll-to-hide state machine for the external dock.
 *
 * Sign convention for [onPageScroll]:
 * - negative delta = page scrolling DOWN (content moves upward -> hide dock)
 * - positive delta = page scrolling UP (content moves downward -> restore dock)
 *
 * Uses directional accumulation thresholds so tiny trackpad/wheel jitter does not
 * flicker the dock, while a deliberate scroll down dismisses it (including on the
 * wallpaper screen) and scrolling up immediately brings it back.
 */
class DockScrollHideTracker(
    private val hideThresholdPx: Float = DEFAULT_HIDE_THRESHOLD_PX,
    private val showThresholdPx: Float = DEFAULT_SHOW_THRESHOLD_PX,
) {
    var isHidden: Boolean = false
        private set

    private var accum: Float = 0f

    fun onPageScroll(pageDeltaY: Float, enabled: Boolean): Boolean {
        if (!enabled) {
            reset()
            return isHidden
        }
        if (pageDeltaY == 0f || pageDeltaY.isNaN()) return isHidden
        if ((accum > 0f && pageDeltaY < 0f) || (accum < 0f && pageDeltaY > 0f)) {
            accum = 0f
        }
        accum = (accum + pageDeltaY).coerceIn(-160f, 160f)
        if (!isHidden && accum <= -hideThresholdPx) {
            isHidden = true
            accum = 0f
        } else if (isHidden && accum >= showThresholdPx) {
            isHidden = false
            accum = 0f
        }
        return isHidden
    }

    fun reveal() {
        isHidden = false
        accum = 0f
    }

    fun reset() {
        isHidden = false
        accum = 0f
    }

    companion object {
        const val DEFAULT_HIDE_THRESHOLD_PX = 24f
        const val DEFAULT_SHOW_THRESHOLD_PX = 16f

        fun shouldShowDock(
            baseVisible: Boolean,
            scrollHidden: Boolean,
            hideOnScrollEnabled: Boolean,
            dockCustomizing: Boolean,
            reorderMode: Boolean,
            removeMode: Boolean,
            quickSettingsOpen: Boolean,
        ): Boolean {
            if (!baseVisible) return false
            if (!hideOnScrollEnabled) return true
            if (dockCustomizing || reorderMode || removeMode || quickSettingsOpen) return true
            return !scrollHidden
        }
    }
}
