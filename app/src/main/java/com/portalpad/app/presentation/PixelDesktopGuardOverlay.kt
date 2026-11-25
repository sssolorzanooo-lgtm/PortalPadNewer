package com.portalpad.app.presentation

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.graphics.toColorInt

/**
 * Opaque Pixel-only underlay for the physical external display.
 *
 * Stock Pixel desktop is the native content behind PortalPad's VD mirror. This
 * window is added before the mirror and deliberately kept underneath it, so a
 * mirror attach/rebuild reveals PortalPad branding instead of Pixel desktop.
 * While the service is stopped and MainActivity is visible, it also explains
 * the one action needed on the phone.
 *
 * The guard never accepts input. It prefers the accessibility-overlay layer so
 * it covers Pixel's taskbar/status chrome too. If the actual mirror later has
 * to fall back to a lower 2038 application-overlay window, the mirror reports
 * that to the service and the guard is removed rather than covering PortalPad.
 */
object PixelDesktopGuardOverlay {

    enum class State {
        SERVICE_STOPPED,
        STARTING,
        RUNNING,
    }

    private data class Attached(
        val displayId: Int,
        val windowManager: WindowManager,
        val root: View,
        val title: TextView,
        val detail: TextView,
        val spinner: ProgressBar,
        val windowType: Int,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private var current: Attached? = null

    fun isPixelDevice(): Boolean =
        (Build.BRAND.equals("google", ignoreCase = true) ||
            Build.MANUFACTURER.equals("google", ignoreCase = true)) &&
            Build.MODEL.startsWith("Pixel", ignoreCase = true)

    fun showServiceStopped(context: Context, display: Display) {
        show(context.applicationContext, display, State.SERVICE_STOPPED)
    }

    fun showStarting(context: Context, display: Display) {
        show(context.applicationContext, display, State.STARTING)
    }

    fun showRunning(context: Context, display: Display) {
        show(context.applicationContext, display, State.RUNNING)
    }

    /** Update an existing underlay without creating a new window above a live mirror. */
    fun markRunningIfShowing() {
        runOnMain {
            current?.let { applyState(it, State.RUNNING) }
        }
    }

    fun dismiss() {
        runOnMain { dismissNow() }
    }

    private fun show(context: Context, display: Display, state: State) {
        if (!isPixelDevice()) return
        runOnMain { showNow(context, display, state) }
    }

    private fun showNow(context: Context, display: Display, state: State) {
        current?.takeIf { it.displayId == display.displayId }?.let {
            applyState(it, state)
            return
        }
        dismissNow()

        val host = OverlayHost.forDisplay(display, context)
        val ctx = host.context
        @Suppress("DEPRECATION")
        val metrics = android.util.DisplayMetrics().also { display.getRealMetrics(it) }
        val widthPx = metrics.widthPixels.coerceAtLeast(640)
        val density = ctx.resources.displayMetrics.density.takeIf { it > 0.1f } ?: 2f

        val title = TextView(ctx).apply {
                setTextColor("#FFFFFF".toColorInt())
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, widthPx * 0.038f)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                gravity = Gravity.CENTER
            }
            val detail = TextView(ctx).apply {
                setTextColor("#E2E2E8".toColorInt())
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, widthPx * 0.024f)
                gravity = Gravity.CENTER
                setPadding(0, (widthPx * 0.018f).toInt(), 0, 0)
            }
            val spinner = ProgressBar(ctx).apply {
                val size = (widthPx * 0.045f).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = (widthPx * 0.025f).toInt()
                }
            }
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                val padH = (widthPx * 0.055f).toInt()
                val padV = (widthPx * 0.038f).toInt()
                setPadding(padH, padV, padH, padV)
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 24f * density
                    setColor("#F216161B".toColorInt())
                    setStroke((2f * density).toInt(), "#DC2626".toColorInt())
                }
                elevation = 24f * density
                addView(
                    title,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ),
                )
                addView(
                    detail,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ),
                )
                addView(spinner)
            }
            val root = android.widget.FrameLayout(ctx).apply {
                setBackgroundColor(Color.rgb(7, 5, 12))
                addView(
                    card,
                    android.widget.FrameLayout.LayoutParams(
                        (widthPx * 0.78f).toInt(),
                        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER,
                    ),
                )
            }
            val lp = WindowManager.LayoutParams(
                metrics.widthPixels.coerceAtLeast(1),
                metrics.heightPixels.coerceAtLeast(1),
                host.windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN,
                PixelFormat.OPAQUE,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
            }

        runCatching {
            host.windowManager.addView(root, lp)
            val attached = Attached(
                display.displayId,
                host.windowManager,
                root,
                title,
                detail,
                spinner,
                host.windowType,
            )
            current = attached
            applyState(attached, state)
            @Suppress("DEPRECATION")
            root.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            Log.d(TAG, "guard added display=${display.displayId} type=${host.windowType} state=$state")
        }.onFailure {
            Log.w(TAG, "guard add failed display=${display.displayId}", it)
        }
    }

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action()
        else mainHandler.post(action)
    }

    private fun applyState(attached: Attached, state: State) {
        when (state) {
            State.SERVICE_STOPPED -> {
                attached.title.text = "PortalPad is not running"
                attached.detail.text =
                    "Start the service on your phone to replace the Pixel desktop with PortalPad."
                attached.spinner.visibility = View.GONE
            }
            State.STARTING -> {
                attached.title.text = "PortalPad"
                attached.detail.text = "Starting the external display..."
                attached.spinner.visibility = View.VISIBLE
            }
            State.RUNNING -> {
                attached.title.text = "PortalPad"
                attached.detail.text = "Connecting to the external display..."
                attached.spinner.visibility = View.VISIBLE
            }
        }
    }

    private fun dismissNow() {
        current?.let { attached ->
            runCatching { attached.windowManager.removeView(attached.root) }
            Log.d(
                TAG,
                "guard removed display=${attached.displayId} type=${attached.windowType}",
            )
        }
        current = null
    }

    private const val TAG = "PixelDesktopGuard"
}
