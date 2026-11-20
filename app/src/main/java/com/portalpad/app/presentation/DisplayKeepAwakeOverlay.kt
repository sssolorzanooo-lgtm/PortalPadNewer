package com.portalpad.app.presentation

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * A one-pixel, fully transparent window whose only job is to hold Android's
 * display-local KEEP_SCREEN_ON contract for PortalPad's independently unlocked
 * virtual display.
 *
 * A normal process wake lock would affect every display. WindowManager turns
 * FLAG_KEEP_SCREEN_ON into a wake lock tied to this window's display ID, which
 * prevents the VD's own power group timing out without keeping the phone panel
 * awake. This host is deliberately separate from optional cursor/dock UI so it
 * remains attached regardless of user customization.
 */
class DisplayKeepAwakeOverlay(
    serviceContext: Context,
    private val display: Display,
) {
    private val overlayHost = OverlayHost.forDisplay(display, serviceContext)
    private val context = overlayHost.context
    private val windowManager = overlayHost.windowManager
    private var view: View? = null

    val displayId: Int get() = display.displayId
    val isAttached: Boolean get() = view != null

    fun show() {
        if (view != null) return
        val keepAwakeView = View(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
        }
        val params = WindowManager.LayoutParams(
            1,
            1,
            overlayHost.windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            title = "PortalPad display keep-awake"
        }
        try {
            windowManager.addView(keepAwakeView, params)
            view = keepAwakeView
            Log.i(
                TAG,
                "attached display=$displayId type=${overlayHost.windowType} " +
                    "a11y=${overlayHost.isAccessibilityOverlay}",
            )
        } catch (t: Throwable) {
            Log.w(TAG, "attach failed display=$displayId", t)
        }
    }

    fun dismiss() {
        val attached = view ?: return
        runCatching { windowManager.removeViewImmediate(attached) }
        view = null
        Log.i(TAG, "dismissed display=$displayId")
    }

    companion object {
        private const val TAG = "PortalPadKeepAwake"
    }
}
