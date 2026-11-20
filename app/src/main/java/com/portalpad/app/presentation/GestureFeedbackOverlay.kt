package com.portalpad.app.presentation

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.graphics.toColorInt
import kotlin.math.cos
import kotlin.math.sin

/**
 * Brief, non-interactive Gesture feedback on the active PortalPad display.
 *
 * The window is centered horizontally at 55% display height so it remains in
 * the natural field of view on ultrawide monitors. Its dimensions are derived
 * from physical display pixels, not Android density, so phone DPI/font changes
 * cannot resize it.
 */
object GestureFeedbackOverlay {

    enum class VolumeDirection {
        CLOCKWISE,
        COUNTERCLOCKWISE,
    }

    private sealed interface Model {
        data class Preset(val label: String) : Model
        data class Volume(
            val percent: Int,
            val muted: Boolean,
            val direction: VolumeDirection?,
            val unmuted: Boolean,
            val teachRotation: Boolean,
        ) : Model
    }

    private data class Attached(
        val displayId: Int,
        val windowManager: WindowManager,
        val view: FeedbackView,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private var current: Attached? = null
    private var fadeRunnable: Runnable? = null

    fun showPreset(
        serviceContext: Context,
        display: Display,
        label: String,
        durationMs: Long = 1500L,
    ) {
        show(serviceContext, display, Model.Preset(label), durationMs)
    }

    fun showVolume(
        serviceContext: Context,
        display: Display,
        percent: Int,
        muted: Boolean,
        direction: VolumeDirection?,
        unmuted: Boolean = false,
        teachRotation: Boolean = false,
        durationMs: Long = 1500L,
    ) {
        show(
            serviceContext,
            display,
            Model.Volume(
                percent = percent.coerceIn(0, 100),
                muted = muted,
                direction = direction,
                unmuted = unmuted,
                teachRotation = teachRotation,
            ),
            durationMs,
        )
    }

    fun dismiss() {
        mainHandler.post { dismissNow() }
    }

    private fun show(
        serviceContext: Context,
        display: Display,
        model: Model,
        durationMs: Long,
    ) {
        mainHandler.post {
            val attached = current?.takeIf { it.displayId == display.displayId }
                ?: attach(serviceContext, display)
                ?: return@post
            attached.view.animate().cancel()
            attached.view.alpha = 1f
            attached.view.setModel(model)
            scheduleFade(attached, durationMs)
        }
    }

    private fun attach(serviceContext: Context, display: Display): Attached? {
        dismissNow()
        val host = OverlayHost.forDisplay(display, serviceContext)
        @Suppress("DEPRECATION")
        val metrics = android.util.DisplayMetrics().also { display.getRealMetrics(it) }
        val shortSide = minOf(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(480)
        val widthPx = (shortSide * 0.42f).toInt().coerceIn(280, 520)
        val heightPx = (shortSide * 0.23f).toInt().coerceIn(156, 286)
        val feedbackView = FeedbackView(host.context, shortSide)
        val params = WindowManager.LayoutParams(
            widthPx,
            heightPx,
            host.windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = ((metrics.heightPixels * 0.55f) - heightPx / 2f).toInt()
            title = "PortalPad gesture feedback"
        }
        return runCatching {
            host.windowManager.addView(feedbackView, params)
            Attached(display.displayId, host.windowManager, feedbackView).also {
                current = it
            }
        }.onFailure {
            Log.w(TAG, "attach failed display=${display.displayId}", it)
        }.getOrNull()
    }

    private fun scheduleFade(attached: Attached, durationMs: Long) {
        fadeRunnable?.let(mainHandler::removeCallbacks)
        val runnable = Runnable {
            if (current !== attached) return@Runnable
            attached.view.animate()
                .alpha(0f)
                .setDuration(180L)
                .withEndAction {
                    if (current === attached) dismissNow()
                }
                .start()
        }
        fadeRunnable = runnable
        mainHandler.postDelayed(runnable, durationMs.coerceAtLeast(300L))
    }

    private fun dismissNow() {
        fadeRunnable?.let(mainHandler::removeCallbacks)
        fadeRunnable = null
        current?.let { attached ->
            attached.view.animate().cancel()
            runCatching { attached.windowManager.removeViewImmediate(attached.view) }
        }
        current = null
    }

    private class FeedbackView(
        context: Context,
        private val shortSide: Int,
    ) : View(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val cardFill = "#F21A1326".toColorInt()
        private val ringTrack = "#5A554F68".toColorInt()
        private val accent = "#A465FF".toColorInt()
        private val accentBright = "#D0ACFF".toColorInt()
        private val primaryText = "#F4EFFA".toColorInt()
        private val mutedText = "#B9AEC8".toColorInt()
        private var model: Model = Model.Preset("D-pad")
        private var rotationCueAngle = 0f
        private var rotationCueAnimator: ValueAnimator? = null

        fun setModel(value: Model) {
            val shouldTeachRotation =
                value is Model.Volume && value.teachRotation && model !is Model.Volume
            model = value
            if (shouldTeachRotation) {
                rotationCueAnimator?.cancel()
                rotationCueAnimator = ValueAnimator.ofFloat(0f, 14f, -14f, 0f).apply {
                    duration = 640L
                    addUpdateListener {
                        rotationCueAngle = it.animatedValue as Float
                        invalidate()
                    }
                    start()
                }
            } else if (value !is Model.Volume || !value.teachRotation) {
                rotationCueAnimator?.cancel()
                rotationCueAnimator = null
                rotationCueAngle = 0f
            }
            contentDescription = when (value) {
                is Model.Preset -> "Gesture preset: ${value.label}"
                is Model.Volume -> when {
                    value.muted -> "Muted"
                    value.unmuted -> "Unmuted, ${value.percent} percent"
                    else -> "Volume ${value.percent} percent"
                }
            }
            invalidate()
        }

        override fun onDetachedFromWindow() {
            rotationCueAnimator?.cancel()
            rotationCueAnimator = null
            super.onDetachedFromWindow()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            when (val value = model) {
                is Model.Preset -> drawPreset(canvas, value)
                is Model.Volume -> drawVolume(canvas, value)
            }
        }

        private fun drawPreset(canvas: Canvas, value: Model.Preset) {
            val text = "Gesture preset: ${value.label}"
            val textSize = (shortSide * 0.032f).coerceIn(24f, 42f)
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            paint.textSize = textSize
            val textWidth = paint.measureText(text)
            val padH = shortSide * 0.034f
            val pillWidth = (textWidth + padH * 2f).coerceAtMost(width * 0.94f)
            val pillHeight = (textSize * 2.15f).coerceAtMost(height * 0.58f)
            val rect = RectF(
                (width - pillWidth) / 2f,
                (height - pillHeight) / 2f,
                (width + pillWidth) / 2f,
                (height + pillHeight) / 2f,
            )
            paint.style = Paint.Style.FILL
            paint.color = cardFill
            canvas.drawRoundRect(rect, pillHeight / 2f, pillHeight / 2f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = (shortSide * 0.0025f).coerceAtLeast(2f)
            paint.color = accent
            canvas.drawRoundRect(rect, pillHeight / 2f, pillHeight / 2f, paint)
            paint.style = Paint.Style.FILL
            paint.color = primaryText
            paint.textAlign = Paint.Align.CENTER
            val baseline = rect.centerY() - (paint.ascent() + paint.descent()) / 2f
            canvas.drawText(text, rect.centerX(), baseline, paint)
        }

        private fun drawVolume(canvas: Canvas, value: Model.Volume) {
            val cx = width / 2f
            val cy = height * 0.54f
            val radius = minOf(width, height) * 0.35f
            val ringWidth = (shortSide * 0.010f).coerceIn(8f, 14f)
            val ringRect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)

            paint.style = Paint.Style.FILL
            paint.color = cardFill
            canvas.drawCircle(cx, cy, radius + ringWidth * 0.85f, paint)

            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = ringWidth
            paint.color = ringTrack
            canvas.drawArc(ringRect, -90f, 360f, false, paint)
            if (value.percent > 0) {
                paint.color = if (value.muted) {
                    Color.argb(78, 164, 101, 255)
                } else {
                    accent
                }
                canvas.drawArc(
                    ringRect,
                    -90f,
                    360f * value.percent / 100f,
                    false,
                    paint,
                )
            }
            paint.strokeCap = Paint.Cap.BUTT

            val levelAngle =
                -90f + 360f * value.percent.coerceIn(0, 100) / 100f + rotationCueAngle
            if (value.direction != null && !value.muted) {
                val cueSweep = when (value.direction) {
                    VolumeDirection.CLOCKWISE -> 24f
                    VolumeDirection.COUNTERCLOCKWISE -> -24f
                }
                val cueRadius = radius + ringWidth * 1.15f
                val cueRect = RectF(
                    cx - cueRadius,
                    cy - cueRadius,
                    cx + cueRadius,
                    cy + cueRadius,
                )
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeWidth = (ringWidth * 0.42f).coerceAtLeast(3f)
                paint.color = Color.argb(150, 208, 172, 255)
                canvas.drawArc(cueRect, levelAngle - cueSweep, cueSweep, false, paint)
            }
            val handleRadians = Math.toRadians(levelAngle.toDouble())
            val handleX = cx + cos(handleRadians).toFloat() * radius
            val handleY = cy + sin(handleRadians).toFloat() * radius
            paint.style = Paint.Style.FILL
            paint.color = cardFill
            canvas.drawCircle(handleX, handleY, ringWidth * 0.92f, paint)
            paint.color = if (value.muted) {
                Color.argb(130, 208, 172, 255)
            } else {
                accentBright
            }
            canvas.drawCircle(handleX, handleY, ringWidth * 0.62f, paint)
            paint.strokeCap = Paint.Cap.BUTT

            if (value.muted) {
                drawSpeaker(canvas, cx, cy - radius * 0.18f, radius * 0.42f, crossed = true)
                drawCenteredText(
                    canvas,
                    "Muted",
                    cx,
                    cy + radius * 0.48f,
                    (shortSide * 0.031f).coerceIn(24f, 40f),
                    primaryText,
                    bold = true,
                )
            } else {
                drawCenteredText(
                    canvas,
                    "${value.percent}%",
                    cx,
                    cy + radius * 0.08f,
                    (shortSide * 0.043f).coerceIn(34f, 54f),
                    primaryText,
                    bold = true,
                )
                if (value.unmuted) {
                    drawCenteredText(
                        canvas,
                        "Unmuted",
                        cx,
                        cy + radius * 0.52f,
                        (shortSide * 0.020f).coerceIn(17f, 26f),
                        mutedText,
                        bold = false,
                    )
                }
            }
        }

        private fun drawSpeaker(
            canvas: Canvas,
            cx: Float,
            cy: Float,
            size: Float,
            crossed: Boolean,
        ) {
            val path = Path().apply {
                moveTo(cx - size * 0.48f, cy - size * 0.16f)
                lineTo(cx - size * 0.20f, cy - size * 0.16f)
                lineTo(cx + size * 0.10f, cy - size * 0.43f)
                lineTo(cx + size * 0.10f, cy + size * 0.43f)
                lineTo(cx - size * 0.20f, cy + size * 0.16f)
                lineTo(cx - size * 0.48f, cy + size * 0.16f)
                close()
            }
            paint.style = Paint.Style.FILL
            paint.color = accentBright
            canvas.drawPath(path, paint)
            if (crossed) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = (size * 0.12f).coerceAtLeast(3f)
                paint.strokeCap = Paint.Cap.ROUND
                canvas.drawLine(
                    cx - size * 0.48f,
                    cy - size * 0.48f,
                    cx + size * 0.48f,
                    cy + size * 0.48f,
                    paint,
                )
                paint.strokeCap = Paint.Cap.BUTT
            }
        }

        private fun drawCenteredText(
            canvas: Canvas,
            text: String,
            x: Float,
            centerY: Float,
            textSize: Float,
            color: Int,
            bold: Boolean,
        ) {
            paint.style = Paint.Style.FILL
            paint.color = color
            paint.textSize = textSize
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = Typeface.create(
                Typeface.DEFAULT,
                if (bold) Typeface.BOLD else Typeface.NORMAL,
            )
            canvas.drawText(text, x, centerY - (paint.ascent() + paint.descent()) / 2f, paint)
        }
    }

    private const val TAG = "GestureFeedback"
}
