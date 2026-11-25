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
            val exactCenter: Boolean = false,
        ) : Model
        data class Seek(
            val deltaSeconds: Int,
            val direction: VolumeDirection?,
        ) : Model
        data class PlayPause(val triggerSeq: Long) : Model
    }

    private data class Attached(
        val displayId: Int,
        val centeredMiddle: Boolean,
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
        show(
            serviceContext = serviceContext,
            display = display,
            model = Model.Preset(label),
            durationMs = durationMs,
            centeredMiddle = false,
            autoFade = true,
        )
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
        centeredMiddle: Boolean = false,
        autoFade: Boolean = true,
    ) {
        show(
            serviceContext = serviceContext,
            display = display,
            model = Model.Volume(
                percent = percent.coerceIn(0, 100),
                muted = muted,
                direction = direction,
                unmuted = unmuted,
                teachRotation = teachRotation,
                exactCenter = centeredMiddle,
            ),
            durationMs = durationMs,
            centeredMiddle = centeredMiddle,
            autoFade = autoFade,
        )
    }

    fun showSeek(
        serviceContext: Context,
        display: Display,
        deltaSeconds: Int,
        direction: VolumeDirection?,
        durationMs: Long = 1500L,
        autoFade: Boolean = false,
    ) {
        val clamped = deltaSeconds.coerceIn(-60, 60)
        show(
            serviceContext = serviceContext,
            display = display,
            model = Model.Seek(
                deltaSeconds = clamped,
                direction = direction,
            ),
            durationMs = durationMs,
            centeredMiddle = true,
            autoFade = autoFade,
        )
    }

    private var playPauseSeq = 0L

    fun showPlayPause(
        serviceContext: Context,
        display: Display,
        durationMs: Long = 720L,
    ) {
        val seq = ++playPauseSeq
        show(
            serviceContext = serviceContext,
            display = display,
            model = Model.PlayPause(seq),
            durationMs = durationMs,
            centeredMiddle = true,
            autoFade = true,
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
        centeredMiddle: Boolean,
        autoFade: Boolean,
    ) {
        mainHandler.post {
            val attached = current?.takeIf {
                it.displayId == display.displayId && it.centeredMiddle == centeredMiddle
            } ?: attach(serviceContext, display, centeredMiddle)
                ?: return@post
            fadeRunnable?.let(mainHandler::removeCallbacks)
            fadeRunnable = null
            attached.view.animate().cancel()
            attached.view.alpha = 1f
            attached.view.setModel(model)
            if (autoFade) {
                scheduleFade(attached, durationMs)
            }
        }
    }

    private fun attach(
        serviceContext: Context,
        display: Display,
        centeredMiddle: Boolean,
    ): Attached? {
        dismissNow()
        val host = OverlayHost.forDisplay(display, serviceContext)
        @Suppress("DEPRECATION")
        val metrics = android.util.DisplayMetrics().also { display.getRealMetrics(it) }
        val shortSide = minOf(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(480)
        val widthPx = if (centeredMiddle) {
            (shortSide * 0.25f).toInt().coerceIn(176, 300)
        } else {
            (shortSide * 0.42f).toInt().coerceIn(280, 520)
        }
        val heightPx = if (centeredMiddle) {
            widthPx
        } else {
            (shortSide * 0.23f).toInt().coerceIn(156, 286)
        }
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
            if (centeredMiddle) {
                gravity = Gravity.CENTER
                x = 0
                y = 0
            } else {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = ((metrics.heightPixels * 0.55f) - heightPx / 2f).toInt()
            }
            title = "PortalPad gesture feedback"
        }
        return runCatching {
            host.windowManager.addView(feedbackView, params)
            Attached(display.displayId, centeredMiddle, host.windowManager, feedbackView).also {
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
        // Remote / Preset violet palette
        private val cardFill = "#F216161B".toColorInt()
        private val ringTrack = "#5A554F68".toColorInt()
        private val accent = "#A465FF".toColorInt()
        private val accentBright = "#D0ACFF".toColorInt()
        private val primaryText = "#F4EFFA".toColorInt()
        private val mutedText = "#B9AEC8".toColorInt()
        // Touchpad HUD Crimson Red palette (matches app Phone* theme)
        private val redCardFill = "#F216161B".toColorInt()
        private val redRingTrack = "#5A444452".toColorInt()
        private val redAccent = "#DC2626".toColorInt()
        private val redAccentBright = "#F87171".toColorInt()
        private val redPrimaryText = "#F4F4F6".toColorInt()
        private val redMutedText = "#B4B4C0".toColorInt()

        private var model: Model = Model.Preset("D-pad")
        private var rotationCueAngle = 0f
        private var rotationCueAnimator: ValueAnimator? = null
        private var playPausePopProgress = 1f
        private var playPauseAnimator: ValueAnimator? = null

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

            if (value is Model.PlayPause) {
                playPauseAnimator?.cancel()
                playPausePopProgress = 0f
                playPauseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 240L
                    addUpdateListener {
                        playPausePopProgress = it.animatedValue as Float
                        invalidate()
                    }
                    start()
                }
            } else {
                playPauseAnimator?.cancel()
                playPauseAnimator = null
                playPausePopProgress = 1f
            }

            contentDescription = when (value) {
                is Model.Preset -> "Gesture preset: ${value.label}"
                is Model.Volume -> when {
                    value.muted -> "Muted"
                    value.unmuted -> "Unmuted, ${value.percent} percent"
                    else -> "Volume ${value.percent} percent"
                }
                is Model.Seek -> when {
                    value.deltaSeconds > 0 -> "Seek forward ${value.deltaSeconds} seconds"
                    value.deltaSeconds < 0 -> "Rewind ${kotlin.math.abs(value.deltaSeconds)} seconds"
                    else -> "Seek 0 seconds"
                }
                is Model.PlayPause -> "Play or pause media"
            }
            invalidate()
        }

        override fun onDetachedFromWindow() {
            rotationCueAnimator?.cancel()
            rotationCueAnimator = null
            playPauseAnimator?.cancel()
            playPauseAnimator = null
            super.onDetachedFromWindow()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            when (val value = model) {
                is Model.Preset -> drawPreset(canvas, value)
                is Model.Volume -> drawVolume(canvas, value)
                is Model.Seek -> drawSeek(canvas, value)
                is Model.PlayPause -> drawPlayPause(canvas)
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
            val useRed = value.exactCenter
            val cFill = if (useRed) redCardFill else cardFill
            val cTrack = if (useRed) redRingTrack else ringTrack
            val cAccent = if (useRed) redAccent else accent
            val cBright = if (useRed) redAccentBright else accentBright
            val cText = if (useRed) redPrimaryText else primaryText
            val cMuted = if (useRed) redMutedText else mutedText

            val cx = width / 2f
            val cy = if (value.exactCenter) height / 2f else height * 0.54f
            val radius = minOf(width, height) * 0.35f
            val ringWidth = (shortSide * 0.010f).coerceIn(8f, 14f)
            val ringRect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)

            paint.style = Paint.Style.FILL
            paint.color = cFill
            canvas.drawCircle(cx, cy, radius + ringWidth * 0.85f, paint)

            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = ringWidth
            paint.color = cTrack
            canvas.drawArc(ringRect, -90f, 360f, false, paint)
            if (value.percent > 0) {
                paint.color = if (value.muted) {
                    if (useRed) Color.argb(86, 220, 38, 38) else Color.argb(78, 164, 101, 255)
                } else {
                    cAccent
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
                paint.color = if (useRed) {
                    Color.argb(175, 248, 113, 113)
                } else {
                    Color.argb(150, 208, 172, 255)
                }
                canvas.drawArc(cueRect, levelAngle - cueSweep, cueSweep, false, paint)
            }
            val handleRadians = Math.toRadians(levelAngle.toDouble())
            val handleX = cx + cos(handleRadians).toFloat() * radius
            val handleY = cy + sin(handleRadians).toFloat() * radius
            paint.style = Paint.Style.FILL
            paint.color = cFill
            canvas.drawCircle(handleX, handleY, ringWidth * 0.92f, paint)
            paint.color = if (value.muted) {
                if (useRed) Color.argb(140, 248, 113, 113) else Color.argb(130, 208, 172, 255)
            } else {
                cBright
            }
            canvas.drawCircle(handleX, handleY, ringWidth * 0.62f, paint)
            paint.strokeCap = Paint.Cap.BUTT

            if (value.muted) {
                drawSpeaker(canvas, cx, cy - radius * 0.18f, radius * 0.42f, crossed = true, color = cBright)
                drawCenteredText(
                    canvas,
                    "Muted",
                    cx,
                    cy + radius * 0.48f,
                    (shortSide * 0.031f).coerceIn(24f, 40f),
                    cText,
                    bold = true,
                )
            } else {
                drawCenteredText(
                    canvas,
                    "${value.percent}%",
                    cx,
                    cy + radius * 0.08f,
                    (shortSide * 0.043f).coerceIn(34f, 54f),
                    cText,
                    bold = true,
                )
                if (value.unmuted) {
                    drawCenteredText(
                        canvas,
                        "Unmuted",
                        cx,
                        cy + radius * 0.52f,
                        (shortSide * 0.020f).coerceIn(17f, 26f),
                        cMuted,
                        bold = false,
                    )
                }
            }
        }

        private fun drawSeek(canvas: Canvas, value: Model.Seek) {
            val cx = width / 2f
            val cy = height / 2f
            val radius = minOf(width, height) * 0.35f
            val ringWidth = (shortSide * 0.010f).coerceIn(8f, 14f)
            val ringRect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)

            paint.style = Paint.Style.FILL
            paint.color = redCardFill
            canvas.drawCircle(cx, cy, radius + ringWidth * 0.85f, paint)

            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = ringWidth
            paint.color = redRingTrack
            canvas.drawArc(ringRect, -90f, 360f, false, paint)

            // Max seek is +/-60s (six 10s increments), so each 10s step fills 60 deg (360 deg full circle at +/-60s).
            val stepCount = kotlin.math.abs(value.deltaSeconds) / 10
            val rawSweep = (stepCount.coerceAtMost(6) * 60f).let {
                if (value.deltaSeconds < 0) -it else it
            }
            if (rawSweep != 0f) {
                paint.color = redAccent
                canvas.drawArc(ringRect, -90f, rawSweep, false, paint)
                val endAngle = -90f + rawSweep
                val handleRad = Math.toRadians(endAngle.toDouble())
                val hx = cx + cos(handleRad).toFloat() * radius
                val hy = cy + sin(handleRad).toFloat() * radius
                paint.style = Paint.Style.FILL
                paint.color = redCardFill
                canvas.drawCircle(hx, hy, ringWidth * 0.92f, paint)
                paint.color = redAccentBright
                canvas.drawCircle(hx, hy, ringWidth * 0.62f, paint)
            }

            if (value.direction != null) {
                val cueSweep = when (value.direction) {
                    VolumeDirection.CLOCKWISE -> 28f
                    VolumeDirection.COUNTERCLOCKWISE -> -28f
                }
                val cueRadius = radius + ringWidth * 1.15f
                val cueRect = RectF(
                    cx - cueRadius,
                    cy - cueRadius,
                    cx + cueRadius,
                    cy + cueRadius,
                )
                val anchorAngle = -90f + rawSweep
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeWidth = (ringWidth * 0.42f).coerceAtLeast(3f)
                paint.color = Color.argb(175, 248, 113, 113)
                canvas.drawArc(cueRect, anchorAngle - cueSweep, cueSweep, false, paint)
            }
            paint.strokeCap = Paint.Cap.BUTT

            val primaryLabel = when {
                value.deltaSeconds > 0 -> "+${value.deltaSeconds}s"
                value.deltaSeconds < 0 -> "${value.deltaSeconds}s"
                else -> "0s"
            }
            val subLabel = when {
                value.deltaSeconds > 0 -> "Seek"
                value.deltaSeconds < 0 -> "Rewind"
                else -> "Release"
            }
            drawCenteredText(
                canvas,
                primaryLabel,
                cx,
                cy - radius * 0.08f,
                (shortSide * 0.039f).coerceIn(28f, 48f),
                redPrimaryText,
                bold = true,
            )
            drawCenteredText(
                canvas,
                subLabel,
                cx,
                cy + radius * 0.42f,
                (shortSide * 0.020f).coerceIn(15f, 24f),
                redMutedText,
                bold = false,
            )
        }

        private fun drawPlayPause(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val radius = minOf(width, height) * 0.35f
            val ringWidth = (shortSide * 0.010f).coerceIn(8f, 14f)

            // Crisp elastic pop-in scale: 0.84 -> 1.05 -> 1.0
            val p = playPausePopProgress.coerceIn(0f, 1f)
            val scale = if (p < 0.65f) {
                val t = p / 0.65f
                0.84f + (1.05f - 0.84f) * t
            } else {
                val t = (p - 0.65f) / 0.35f
                1.05f - (1.05f - 1.0f) * t
            }

            canvas.save()
            canvas.scale(scale, scale, cx, cy)

            paint.style = Paint.Style.FILL
            paint.color = redCardFill
            canvas.drawCircle(cx, cy, radius + ringWidth * 0.85f, paint)

            // Outer pulse halo ring that settles cleanly
            val pulseAlpha = ((1f - p * 0.55f) * 140f).toInt().coerceIn(45, 140)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = (ringWidth * 0.45f).coerceAtLeast(3f)
            paint.color = Color.argb(pulseAlpha, 248, 113, 113)
            canvas.drawCircle(cx, cy, radius + ringWidth * 0.55f, paint)

            // Solid crimson accent ring
            paint.strokeWidth = ringWidth * 0.85f
            paint.color = redAccent
            canvas.drawCircle(cx, cy, radius, paint)

            // Single combined Play/Pause symbol centered in the circle:
            // Play triangle on the left + two vertical pause bars on the right.
            val symH = radius * 0.72f
            val halfH = symH / 2f
            val triLeft = cx - radius * 0.42f
            val triRight = cx - radius * 0.02f
            val triPath = Path().apply {
                moveTo(triLeft, cy - halfH)
                lineTo(triRight, cy)
                lineTo(triLeft, cy + halfH)
                close()
            }
            paint.style = Paint.Style.FILL
            paint.color = redPrimaryText
            canvas.drawPath(triPath, paint)

            val barW = (radius * 0.14f).coerceAtLeast(6f)
            val barCorner = barW / 2f
            val bar1Left = cx + radius * 0.08f
            val bar2Left = cx + radius * 0.28f
            val barRect1 = RectF(bar1Left, cy - halfH, bar1Left + barW, cy + halfH)
            val barRect2 = RectF(bar2Left, cy - halfH, bar2Left + barW, cy + halfH)
            canvas.drawRoundRect(barRect1, barCorner, barCorner, paint)
            canvas.drawRoundRect(barRect2, barCorner, barCorner, paint)

            canvas.restore()
        }

        private fun drawSpeaker(
            canvas: Canvas,
            cx: Float,
            cy: Float,
            size: Float,
            crossed: Boolean,
            color: Int = accentBright,
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
            paint.color = color
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
