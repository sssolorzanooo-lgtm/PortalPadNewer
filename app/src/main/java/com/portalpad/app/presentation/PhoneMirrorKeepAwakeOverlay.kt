package com.portalpad.app.presentation

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.portalpad.app.data.MirrorKeepAwakeMode

/**
 * A 1x1 transparent window on [Display.DEFAULT_DISPLAY] holding [WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON]
 * whenever the user selects a mirroring keep-awake mode other than [MirrorKeepAwakeMode.OFF].
 *
 * On Pixel devices in System Mirroring mode, the external display mirrors the phone's default power
 * group and turns off as soon as the phone display times out. Holding FLAG_KEEP_SCREEN_ON on
 * Display 0 keeps the mirrored framebuffer active even when the user switches away from
 * TrackpadActivity or leaves the phone idle.
 */
class PhoneMirrorKeepAwakeOverlay(
    serviceContext: Context,
    private val defaultDisplay: Display,
) {
    private val overlayHost = OverlayHost.forDisplay(defaultDisplay, serviceContext)
    private val context = overlayHost.context
    private val windowManager = overlayHost.windowManager
    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var activeMode: MirrorKeepAwakeMode = MirrorKeepAwakeMode.OFF

    val isAttached: Boolean get() = view != null

    fun show(mode: MirrorKeepAwakeMode) {
        if (mode == MirrorKeepAwakeMode.OFF) {
            dismiss()
            return
        }
        activeMode = mode
        if (view != null) {
            update(mode)
            return
        }
        val keepAwakeView = View(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
        }
        val lp = WindowManager.LayoutParams(
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
            title = "PortalPad mirror keep-awake"
        }
        try {
            windowManager.addView(keepAwakeView, lp)
            view = keepAwakeView
            params = lp
            Log.i(TAG, "attached on default display mode=${mode.id} type=${overlayHost.windowType}")
        } catch (t: Throwable) {
            Log.w(TAG, "attach failed on default display mode=${mode.id}", t)
        }
    }

    fun update(mode: MirrorKeepAwakeMode) {
        if (mode == MirrorKeepAwakeMode.OFF) {
            dismiss()
            return
        }
        activeMode = mode
        val attached = view
        val lp = params
        if (attached == null || lp == null) {
            show(mode)
            return
        }
        runCatching { windowManager.updateViewLayout(attached, lp) }
    }

    fun dismiss() {
        val attached = view ?: return
        runCatching { windowManager.removeViewImmediate(attached) }
        view = null
        params = null
        activeMode = MirrorKeepAwakeMode.OFF
        Log.i(TAG, "dismissed mirror keep-awake")
    }

    companion object {
        private const val TAG = "PortalPadMirrorAwake"
    }
}
