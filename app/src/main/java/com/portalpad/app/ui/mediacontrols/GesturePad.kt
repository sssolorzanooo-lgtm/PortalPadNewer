package com.portalpad.app.ui.mediacontrols

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.portalpad.app.PortalPadApp
import com.portalpad.app.data.RemoteGesturePreset
import com.portalpad.app.ui.common.GesturePresetIcon
import com.portalpad.app.ui.theme.PhoneAccentSoft
import com.portalpad.app.ui.theme.PhoneOnSurface
import com.portalpad.app.ui.theme.PhoneOnSurfaceMuted
import com.portalpad.app.ui.theme.PhonePrimary
import com.portalpad.app.ui.theme.PhonePrimaryBright
import com.portalpad.app.ui.theme.PhoneSurface
import com.portalpad.app.ui.theme.PhoneSurfaceElevated
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

private val GUIDE_DIM = PhoneOnSurfaceMuted
private val GUIDE_GLOW = PhoneAccentSoft

/**
 * One-finger preset surface for Remote > Gesture.
 *
 * A second finger cancels the current gesture instead of changing its meaning.
 * That keeps the whole surface predictable when it is operated without looking.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GesturePad(
    preset: RemoteGesturePreset,
    volumePercent: Int,
    isMuted: Boolean,
    onDpadUp: () -> Unit,
    onDpadDown: () -> Unit,
    onDpadLeft: () -> Unit,
    onDpadRight: () -> Unit,
    onDpadCenter: () -> Unit,
    onBack: () -> Unit,
    onScrollUp: (strength: Float) -> Unit,
    onScrollDown: (strength: Float) -> Unit,
    onScrollLeft: (strength: Float) -> Unit,
    onScrollRight: (strength: Float) -> Unit,
    onScrollTap: () -> Unit,
    onScrollLongPress: () -> Unit,
    onSwipeUp: (strength: Float) -> Unit,
    onSwipeDown: (strength: Float) -> Unit,
    onSwipeLeft: (strength: Float) -> Unit,
    onSwipeRight: (strength: Float) -> Unit,
    onSwipeTap: () -> Unit,
    onSwipeLongPress: () -> Unit,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onRewind: () -> Unit,
    onFastForward: () -> Unit,
    onStop: () -> Unit,
    onVolumePercentChange: (percent: Int, clockwise: Boolean) -> Unit,
    onMuteToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val injector = PortalPadApp.instance.injector
    val pointerAcceleration by PortalPadApp.instance.prefs.float(
        com.portalpad.app.data.PreferencesRepository.Keys.POINTER_ACCEL,
        1.0f,
    ).collectAsState(initial = 1.0f)
    val currentPointerAcceleration by rememberUpdatedState(pointerAcceleration)
    val density = LocalDensity.current
    val swipeThresholdPx = with(density) { 34.dp.toPx() }
    val liveScrollThresholdPx = with(density) { 8.dp.toPx() }
    val volumeDeadZonePx = with(density) { 66.dp.toPx() }
    val pointerMotionEpsilonPx = with(density) { 0.7.dp.toPx() }
    val pointerTapSlopPx = with(density) { 8.dp.toPx() }
    val pointerRestSlopPx = with(density) { 5.dp.toPx() }
    val directionalHoldRestSlopPx = with(density) { 6.dp.toPx() }
    val holdMs = 380L
    val directionalHoldRestMs = 180L
    val liveSwipeGain = 0.65f
    val currentVolumePercent by rememberUpdatedState(volumePercent)

    var activeDirection by remember { mutableStateOf<String?>(null) }
    var feedbackSequence by remember { mutableIntStateOf(0) }
    var centerSequence by remember { mutableIntStateOf(0) }
    LaunchedEffect(feedbackSequence) {
        if (feedbackSequence == 0) return@LaunchedEffect
        kotlinx.coroutines.delay(320L)
        activeDirection = null
    }
    val centerPulse = remember { Animatable(1f) }
    LaunchedEffect(centerSequence) {
        if (centerSequence == 0) return@LaunchedEffect
        centerPulse.snapTo(1f)
        centerPulse.animateTo(1.12f, tween(100))
        centerPulse.animateTo(1f, tween(180))
    }

    fun light(direction: String?) {
        activeDirection = direction
        feedbackSequence++
    }

    fun fireDirection(direction: String, gestureStrength: Float = 1f) {
        light(direction)
        when (preset) {
            RemoteGesturePreset.DPAD -> when (direction) {
                "up" -> onDpadUp()
                "down" -> onDpadDown()
                "left" -> onDpadLeft()
                "right" -> onDpadRight()
            }
            RemoteGesturePreset.SCROLL -> when (direction) {
                "up" -> onScrollUp(gestureStrength)
                "down" -> onScrollDown(gestureStrength)
                "left" -> onScrollLeft(gestureStrength)
                "right" -> onScrollRight(gestureStrength)
            }
            RemoteGesturePreset.SWIPE -> when (direction) {
                "up" -> onSwipeUp(gestureStrength)
                "down" -> onSwipeDown(gestureStrength)
                "left" -> onSwipeLeft(gestureStrength)
                "right" -> onSwipeRight(gestureStrength)
            }
            RemoteGesturePreset.MEDIA -> when (direction) {
                "up" -> onPrevious()
                "down" -> onNext()
                "left" -> onRewind()
                "right" -> onFastForward()
            }
            RemoteGesturePreset.VOLUME,
            RemoteGesturePreset.POINTER,
            -> Unit
        }
    }

    fun fireHeldScrollDirection(direction: String) {
        light(direction)
        when (direction) {
            "up" -> injector.remoteHeldScrollUp()
            "down" -> injector.remoteHeldScrollDown()
            "left" -> injector.remoteHeldScrollLeft()
            "right" -> injector.remoteHeldScrollRight()
        }
    }

    Box(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(PhoneSurface)
            .border(1.dp, PhonePrimary.copy(alpha = 0.34f), RoundedCornerShape(16.dp))
            .pointerInput(preset) {
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false)
                    val startPosition = first.position
                    val startTime = System.currentTimeMillis()
                    var cancelledBySecondFinger = false

                    if (preset == RemoteGesturePreset.POINTER) {
                        var lastPosition = startPosition
                        var restAnchor = startPosition
                        var restStartedAt = startTime
                        var movedBeyondTap = false
                        var holdReady = false
                        var dragActive = false
                        var holdOnButton = false
                        var holdOnCaption = false

                        fun armPointerHold() {
                            holdReady = true
                            holdOnButton = injector.pressOnButtonNow()
                            holdOnCaption = !holdOnButton &&
                                (
                                    injector.pressOnHandleNow() ||
                                    injector.cursorOnCaption.value
                                    )
                            // A firm pulse marks the exact moment Pointer changes
                            // from move/click into drag/right-click behavior.
                            if (!holdOnButton) injector.buzz(longPress = true)
                        }

                        while (true) {
                            val event = withTimeoutOrNull(32L) { awaitPointerEvent() }
                            val now = System.currentTimeMillis()
                            if (event == null) {
                                if (!cancelledBySecondFinger && !holdReady &&
                                    now - restStartedAt >= holdMs
                                ) {
                                    armPointerHold()
                                }
                                continue
                            }

                            event.changes.forEach { if (it.pressed) it.consume() }
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            if (pressed.size > 1) {
                                cancelledBySecondFinger = true
                                if (dragActive) {
                                    injector.dragEnd()
                                    dragActive = false
                                }
                                continue
                            }
                            if (cancelledBySecondFinger) continue

                            val position = pressed.first().position
                            val dx = position.x - lastPosition.x
                            val dy = position.y - lastPosition.y
                            val step = hypot(dx, dy)
                            val travel = hypot(
                                position.x - startPosition.x,
                                position.y - startPosition.y,
                            )
                            if (travel > pointerTapSlopPx) movedBeyondTap = true

                            if (!holdReady) {
                                val restDrift = hypot(
                                    position.x - restAnchor.x,
                                    position.y - restAnchor.y,
                                )
                                if (restDrift > pointerRestSlopPx) {
                                    restAnchor = position
                                    restStartedAt = now
                                } else if (now - restStartedAt >= holdMs) {
                                    armPointerHold()
                                }
                            }

                            if (step > pointerMotionEpsilonPx) {
                                if (holdReady && !holdOnButton) {
                                    if (!dragActive) {
                                        dragActive = true
                                        injector.dragStart(captionMove = holdOnCaption)
                                    }
                                    injector.dragMove(dx, dy)
                                } else {
                                    val gain = (
                                        1f +
                                            0.04f *
                                            currentPointerAcceleration *
                                            step
                                        ).coerceIn(1f, 2.5f)
                                    injector.pointerMove(dx * gain, dy * gain)
                                }
                                lastPosition = position
                            }
                        }

                        when {
                            cancelledBySecondFinger -> Unit
                            dragActive -> injector.dragEnd()
                            // The firm activation pulse already confirmed this
                            // hold. Do not add a second release-time click tick.
                            holdReady && !holdOnButton ->
                                injector.rightClickWithoutHaptic()
                            !holdReady && !movedBeyondTap -> injector.leftClick()
                        }
                    } else if (preset == RemoteGesturePreset.VOLUME) {
                        val center = Offset(size.width / 2f, size.height / 2f)
                        var previousAngle: Float? = null
                        var accumulatedAngle = 0f
                        var workingPercent = currentVolumePercent.coerceIn(0, 100)
                        var lastHapticStep =
                            PortalPadApp.instance.media.volumeStepForPercent(workingPercent)
                        var announcedBoundary: Int? = null
                        val angleStep = (PI / 10.0).toFloat() // 18 degrees per step

                        while (true) {
                            val event = withTimeoutOrNull(40L) { awaitPointerEvent() }
                            if (event == null) {
                                // A still finger produces no move events. Keep
                                // waiting until Android reports an actual up.
                                continue
                            }
                            event.changes.forEach { if (it.pressed) it.consume() }
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            if (pressed.size > 1) {
                                cancelledBySecondFinger = true
                                previousAngle = null
                                continue
                            }
                            if (cancelledBySecondFinger) continue

                            val p = pressed.first().position
                            val radius = hypot((p.x - center.x).toDouble(), (p.y - center.y).toDouble())
                            if (radius < volumeDeadZonePx) {
                                previousAngle = null
                                continue
                            }
                            val angle = atan2(p.y - center.y, p.x - center.x)
                            val prior = previousAngle
                            previousAngle = angle
                            if (prior == null) continue

                            var delta = angle - prior
                            val halfTurn = PI.toFloat()
                            val fullTurn = (2.0 * PI).toFloat()
                            if (delta > halfTurn) delta -= fullTurn
                            if (delta < -halfTurn) delta += fullTurn
                            accumulatedAngle += delta

                            while (abs(accumulatedAngle) >= angleStep) {
                                val clockwise = accumulatedAngle > 0f
                                val next = (workingPercent + if (clockwise) 5 else -5)
                                    .coerceIn(0, 100)
                                accumulatedAngle -= if (clockwise) angleStep else -angleStep
                                if (next != workingPercent) {
                                    workingPercent = next
                                    light(if (clockwise) "clockwise" else "counterclockwise")
                                    val nextHapticStep =
                                        PortalPadApp.instance.media.volumeStepForPercent(next)
                                    if (next == 0 || next == 100) {
                                        announcedBoundary = next
                                        injector.buzzBoundary()
                                    } else if (nextHapticStep != lastHapticStep) {
                                        announcedBoundary = null
                                        injector.buzz(longPress = false)
                                    }
                                    lastHapticStep = nextHapticStep
                                    onVolumePercentChange(next, clockwise)
                                } else if (announcedBoundary != workingPercent) {
                                    // Announce each visit to 0% / 100% once.
                                    // Continuing to spin against the same stop
                                    // stays quiet until the user moves away.
                                    announcedBoundary = workingPercent
                                    injector.buzzBoundary()
                                }
                            }
                        }
                    } else {
                        var lastPosition = startPosition
                        var direction: String? = null
                        var directionLockedAt = 0L
                        var directionalRestAnchor = startPosition
                        var directionalRestStartedAt = startTime
                        var holdEngaged = false
                        var stationaryHoldFired = false
                        var lastRepeatAt = 0L
                        var latestTravel = 0f
                        var swipeDragActive = false
                        var swipeHoldFeedbackFired = false
                        var heldLastPosition = startPosition
                        var scrollLiveLastPosition = startPosition

                        try {
                            while (true) {
                                val event = withTimeoutOrNull(40L) { awaitPointerEvent() }
                                val now = System.currentTimeMillis()
                                if (event != null) {
                                    event.changes.forEach { if (it.pressed) it.consume() }
                                    val pressed = event.changes.filter { it.pressed }
                                    if (pressed.isEmpty()) break
                                    if (pressed.size > 1) {
                                        cancelledBySecondFinger = true
                                        if (swipeDragActive) {
                                            injector.remoteTouchDragEnd()
                                            swipeDragActive = false
                                        }
                                        continue
                                    }
                                    if (!cancelledBySecondFinger) {
                                        val position = pressed.first().position
                                        if (swipeDragActive) {
                                            val surfaceWidth = size.width.toFloat().coerceAtLeast(1f)
                                            val surfaceHeight = size.height.toFloat().coerceAtLeast(1f)
                                            val moveX = position.x - heldLastPosition.x
                                            val moveY = position.y - heldLastPosition.y
                                            injector.remoteTouchDragMove(
                                                dxFraction =
                                                    if (direction == "left" || direction == "right") {
                                                        moveX / surfaceWidth * liveSwipeGain
                                                    } else {
                                                        0f
                                                    },
                                                dyFraction =
                                                    if (direction == "up" || direction == "down") {
                                                        moveY / surfaceHeight * liveSwipeGain
                                                    } else {
                                                        0f
                                                    },
                                            )
                                            heldLastPosition = position
                                        } else if (
                                            preset == RemoteGesturePreset.SCROLL &&
                                            direction != null &&
                                            !holdEngaged
                                        ) {
                                            val moveX = position.x - scrollLiveLastPosition.x
                                            val moveY = position.y - scrollLiveLastPosition.y
                                            val scrollX =
                                                if (direction == "left" || direction == "right") {
                                                    moveX
                                                } else {
                                                    0f
                                                }
                                            val scrollY =
                                                if (direction == "up" || direction == "down") {
                                                    moveY
                                                } else {
                                                    0f
                                                }
                                            // Match the edge strip: keep tiny
                                            // deltas banked locally until the
                                            // finger has moved far enough to
                                            // deliver a useful scroll step.
                                            if (abs(scrollX) > 2f || abs(scrollY) > 2f) {
                                                val currentDirection =
                                                    if (scrollX != 0f) {
                                                        if (scrollX > 0f) "right" else "left"
                                                    } else {
                                                        if (scrollY > 0f) "down" else "up"
                                                    }
                                                if (currentDirection != direction) {
                                                    direction = currentDirection
                                                    light(direction)
                                                }
                                                injector.scroll(scrollX, scrollY)
                                                scrollLiveLastPosition = position
                                            }
                                        }
                                        lastPosition = position
                                    }
                                }
                                if (cancelledBySecondFinger) continue

                                val dx = lastPosition.x - startPosition.x
                                val dy = lastPosition.y - startPosition.y
                                val absX = abs(dx)
                                val absY = abs(dy)
                                latestTravel = max(absX, absY)

                                val directionThresholdPx =
                                    if (preset == RemoteGesturePreset.SCROLL) {
                                        liveScrollThresholdPx
                                    } else {
                                        swipeThresholdPx
                                    }
                                if (direction == null && latestTravel > directionThresholdPx) {
                                    direction = if (absX > absY) {
                                        if (dx > 0f) "right" else "left"
                                    } else {
                                        if (dy > 0f) "down" else "up"
                                    }
                                    directionLockedAt = now
                                    directionalRestAnchor = lastPosition
                                    directionalRestStartedAt = now
                                    light(direction)
                                    when (preset) {
                                        RemoteGesturePreset.SCROLL -> {
                                            injector.scroll(
                                                if (absX > absY) dx else 0f,
                                                if (absY >= absX) dy else 0f,
                                            )
                                            scrollLiveLastPosition = lastPosition
                                            injector.buzz(longPress = false)
                                        }
                                        RemoteGesturePreset.SWIPE -> {
                                            val surfaceWidth =
                                                size.width.toFloat().coerceAtLeast(1f)
                                            val surfaceHeight =
                                                size.height.toFloat().coerceAtLeast(1f)
                                            swipeDragActive = injector.remoteTouchDragBegin(
                                                initialDxFraction =
                                                    if (absX > absY) {
                                                        dx / surfaceWidth * liveSwipeGain
                                                    } else {
                                                        0f
                                                    },
                                                initialDyFraction =
                                                    if (absY >= absX) {
                                                        dy / surfaceHeight * liveSwipeGain
                                                    } else {
                                                        0f
                                                    },
                                            )
                                            heldLastPosition = lastPosition
                                            if (swipeDragActive) {
                                                // A live Swipe is already the
                                                // complete directional action.
                                                // Keeping this true suppresses
                                                // the old release-time swipe.
                                                holdEngaged = true
                                                injector.buzz(longPress = false)
                                            }
                                        }
                                        else -> Unit
                                    }
                                } else if (
                                    direction != null &&
                                    (
                                        (!holdEngaged &&
                                            preset == RemoteGesturePreset.SCROLL) ||
                                            (swipeDragActive &&
                                                !swipeHoldFeedbackFired &&
                                                preset == RemoteGesturePreset.SWIPE)
                                        )
                                ) {
                                    val restDx = lastPosition.x - directionalRestAnchor.x
                                    val restDy = lastPosition.y - directionalRestAnchor.y
                                    if (hypot(restDx.toDouble(), restDy.toDouble()) >
                                        directionalHoldRestSlopPx
                                    ) {
                                        directionalRestAnchor = lastPosition
                                        directionalRestStartedAt = now
                                    }
                                }

                                if (
                                    preset == RemoteGesturePreset.SWIPE &&
                                    swipeDragActive &&
                                    !swipeHoldFeedbackFired &&
                                    now - directionalRestStartedAt >= holdMs
                                ) {
                                    // Swipe is already a live attached touch;
                                    // this pulse confirms that the finger has
                                    // settled into its hold, without changing
                                    // or repeating the external touch.
                                    swipeHoldFeedbackFired = true
                                    injector.buzz(longPress = true)
                                }

                                val directionalHoldReady =
                                    when (preset) {
                                        RemoteGesturePreset.SCROLL ->
                                            now - directionalRestStartedAt >= directionalHoldRestMs
                                        // Swipe becomes one live virtual touch
                                        // at direction lock, so holding is a
                                        // natural continuation of that touch.
                                        RemoteGesturePreset.SWIPE -> false
                                        else -> now - directionLockedAt >= holdMs
                                    }
                                if (
                                    direction != null &&
                                    !holdEngaged &&
                                    directionalHoldReady
                                ) {
                                    holdEngaged = true
                                    injector.buzz(longPress = true)
                                    when (preset) {
                                        RemoteGesturePreset.SCROLL ->
                                            fireHeldScrollDirection(direction)
                                        RemoteGesturePreset.SWIPE -> Unit
                                        else -> fireDirection(direction)
                                    }
                                    lastRepeatAt = now
                                }

                                if (holdEngaged && direction != null) {
                                    val repeats = when (preset) {
                                        RemoteGesturePreset.DPAD,
                                        RemoteGesturePreset.SCROLL,
                                        -> true
                                        RemoteGesturePreset.MEDIA ->
                                            direction == "left" || direction == "right"
                                        RemoteGesturePreset.SWIPE,
                                        RemoteGesturePreset.VOLUME,
                                        RemoteGesturePreset.POINTER,
                                        -> false
                                    }
                                    if (repeats) {
                                        val interval = when (preset) {
                                            RemoteGesturePreset.DPAD -> 120L
                                            RemoteGesturePreset.MEDIA -> 420L
                                        RemoteGesturePreset.SCROLL -> 60L
                                            RemoteGesturePreset.SWIPE,
                                            RemoteGesturePreset.VOLUME,
                                            RemoteGesturePreset.POINTER,
                                            -> Long.MAX_VALUE
                                        }
                                        if (now - lastRepeatAt >= interval) {
                                            if (preset == RemoteGesturePreset.SCROLL) {
                                                fireHeldScrollDirection(direction)
                                            } else {
                                                fireDirection(direction)
                                            }
                                            lastRepeatAt = now
                                        }
                                    }
                                }

                                if (
                                    direction == null &&
                                    !stationaryHoldFired &&
                                    now - startTime >= holdMs
                                ) {
                                    stationaryHoldFired = true
                                    injector.buzz(longPress = true)
                                    when (preset) {
                                        RemoteGesturePreset.DPAD -> onBack()
                                        RemoteGesturePreset.SCROLL -> onScrollLongPress()
                                        RemoteGesturePreset.SWIPE -> onSwipeLongPress()
                                        RemoteGesturePreset.MEDIA -> onStop()
                                        RemoteGesturePreset.VOLUME,
                                        RemoteGesturePreset.POINTER,
                                        -> Unit
                                    }
                                }
                            }

                            if (!cancelledBySecondFinger) {
                                if (direction != null && !holdEngaged) {
                                    if (preset != RemoteGesturePreset.SCROLL) {
                                        injector.buzz(longPress = false)
                                        // The privileged Swipe path already
                                        // produced a live touch and marks the
                                        // action engaged. Reaching here means
                                        // that backend was unavailable, so keep
                                        // the bounded one-shot fallback.
                                        val elapsedMs =
                                            (System.currentTimeMillis() - startTime)
                                                .coerceAtLeast(1L)
                                        val distanceFactor =
                                            (latestTravel / (swipeThresholdPx * 2f))
                                                .coerceIn(0.65f, 1.25f)
                                        val velocityPxPerMs =
                                            latestTravel / elapsedMs.toFloat()
                                        val velocityFactor =
                                            (velocityPxPerMs / 0.9f)
                                                .coerceIn(0.65f, 1.25f)
                                        val gestureStrength =
                                            (
                                                distanceFactor * 0.55f +
                                                    velocityFactor * 0.45f
                                                ).coerceIn(0.70f, 1.30f)
                                        fireDirection(direction, gestureStrength)
                                    }
                                } else if (direction == null && !stationaryHoldFired) {
                                    injector.buzz(longPress = false)
                                    centerSequence++
                                    when (preset) {
                                        RemoteGesturePreset.DPAD -> onDpadCenter()
                                        RemoteGesturePreset.SCROLL -> onScrollTap()
                                        RemoteGesturePreset.SWIPE -> onSwipeTap()
                                        RemoteGesturePreset.MEDIA -> onPlayPause()
                                        RemoteGesturePreset.VOLUME,
                                        RemoteGesturePreset.POINTER,
                                        -> Unit
                                    }
                                }
                            }
                        } finally {
                            if (swipeDragActive) injector.remoteTouchDragEnd()
                        }
                    }
                }
            },
    ) {
        Text(
            text = "${preset.label} gestures",
            color = PhoneOnSurfaceMuted,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 14.dp),
        )

        when (preset) {
            RemoteGesturePreset.DPAD,
            RemoteGesturePreset.SCROLL,
            RemoteGesturePreset.SWIPE,
            -> {
                GestureCrosshair()
                val prefix = when (preset) {
                    RemoteGesturePreset.SCROLL -> "Scroll "
                    RemoteGesturePreset.SWIPE -> "Swipe "
                    else -> ""
                }
                DirectionHint(
                    "$prefix↑",
                    activeDirection == "up",
                    Modifier.align(Alignment.TopCenter).padding(top = 64.dp),
                )
                DirectionHint(
                    "$prefix↓",
                    activeDirection == "down",
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 34.dp),
                )
                DirectionHint(
                    when (preset) {
                        RemoteGesturePreset.SCROLL -> "← Scroll"
                        RemoteGesturePreset.SWIPE -> "← Swipe"
                        else -> "←"
                    },
                    activeDirection == "left",
                    Modifier.align(Alignment.CenterStart).padding(start = 12.dp),
                )
                DirectionHint(
                    when (preset) {
                        RemoteGesturePreset.SCROLL -> "Scroll →"
                        RemoteGesturePreset.SWIPE -> "Swipe →"
                        else -> "→"
                    },
                    activeDirection == "right",
                    Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
                )
                CenterDisc(
                    label = when (preset) {
                        RemoteGesturePreset.DPAD -> "OK"
                        RemoteGesturePreset.SWIPE -> "Tap\ncenter"
                        else -> "Click"
                    },
                    scale = centerPulse.value,
                )
            }

            RemoteGesturePreset.MEDIA -> {
                DirectionHint(
                    "Previous",
                    activeDirection == "up",
                    Modifier.align(Alignment.TopCenter).padding(top = 64.dp),
                )
                DirectionHint(
                    "Next",
                    activeDirection == "down",
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 34.dp),
                )
                DirectionHint(
                    "−10s",
                    activeDirection == "left",
                    Modifier.align(Alignment.CenterStart).padding(start = 14.dp),
                )
                DirectionHint(
                    "+10s",
                    activeDirection == "right",
                    Modifier.align(Alignment.CenterEnd).padding(end = 14.dp),
                )
                CenterDisc(label = "▶ Ⅱ", scale = centerPulse.value)
            }

            RemoteGesturePreset.POINTER -> {
                Column(
                    Modifier.align(Alignment.Center).padding(horizontal = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        Icons.Filled.Mouse,
                        contentDescription = null,
                        tint = PhonePrimaryBright,
                        modifier = Modifier.size(42.dp),
                    )
                    Text(
                        "Move cursor",
                        color = PhonePrimaryBright,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Tap: click",
                        color = PhoneOnSurfaceMuted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        // The Pointer badge occupies the rail beside this lower
                        // band. Center the two-line group between that badge's
                        // top edge and the gesture surface's bottom edge.
                        .padding(start = 18.dp, end = 18.dp, bottom = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        "Hold + move: drag",
                        color = GUIDE_DIM,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        "Hold + release: right click",
                        color = GUIDE_DIM,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            RemoteGesturePreset.VOLUME -> {
                VolumeSurface(
                    volumePercent = volumePercent,
                    isMuted = isMuted,
                    activeDirection = activeDirection,
                    onMuteToggle = {
                        injector.buzz(longPress = false)
                        onMuteToggle()
                    },
                )
            }
        }
    }
}

@Composable
fun GesturePresetScrubber(
    selected: RemoteGesturePreset,
    onPreview: (RemoteGesturePreset) -> Unit,
    onCommitted: (RemoteGesturePreset) -> Unit,
    labelTowardEnd: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val injector = PortalPadApp.instance.injector
    val density = LocalDensity.current
    val order = remember {
        listOf(
            RemoteGesturePreset.VOLUME,
            RemoteGesturePreset.MEDIA,
            RemoteGesturePreset.DPAD,
            RemoteGesturePreset.SWIPE,
            RemoteGesturePreset.SCROLL,
            RemoteGesturePreset.POINTER,
        )
    }
    val latestSelected by rememberUpdatedState(selected)
    val latestPreview by rememberUpdatedState(onPreview)
    val latestCommit by rememberUpdatedState(onCommitted)
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(Float.NaN) }

    fun stopFraction(index: Int): Float = (index + 0.5f) / order.size

    BoxWithConstraints(
        modifier
            .semantics {
                contentDescription = "Gesture preset selector"
                stateDescription = selected.label
                progressBarRangeInfo = ProgressBarRangeInfo(
                    order.indexOf(selected).coerceAtLeast(0).toFloat(),
                    0f..(order.lastIndex.toFloat()),
                    order.size - 2,
                )
                setProgress { value ->
                    val index = value.roundToInt().coerceIn(0, order.lastIndex)
                    val preset = order[index]
                    if (preset != latestSelected) {
                        injector.buzz(longPress = false)
                    }
                    latestPreview(preset)
                    latestCommit(preset)
                    true
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val startingPreset = latestSelected
                    var previewIndex = order.indexOf(startingPreset).coerceAtLeast(0)
                    var cancelled = false

                    fun updatePreview(y: Float) {
                        val raw = (y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                        dragFraction = raw.coerceIn(
                            stopFraction(0),
                            stopFraction(order.lastIndex),
                        )
                        val next = (raw * order.size).toInt().coerceIn(0, order.lastIndex)
                        if (next != previewIndex) {
                            previewIndex = next
                            injector.buzz(longPress = false)
                            latestPreview(order[next])
                        }
                    }

                    dragging = true
                    updatePreview(down.position.y)
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        event.changes.forEach { it.consume() }
                        if (pressed.isEmpty()) break
                        if (pressed.size > 1) {
                            cancelled = true
                            continue
                        }
                        if (!cancelled) updatePreview(pressed.first().position.y)
                    }

                    dragging = false
                    dragFraction = Float.NaN
                    if (cancelled) {
                        latestPreview(startingPreset)
                    } else {
                        val committed = order[previewIndex]
                        latestPreview(committed)
                        // Crossing a stop already emitted the selector tick.
                        // Release/commit stays quiet so it cannot be mistaken
                        // for a gesture action on the newly selected surface.
                        latestCommit(committed)
                    }
                }
            },
    ) {
        val selectedIndex = order.indexOf(selected).coerceAtLeast(0)
        val thumbFraction = if (dragging && !dragFraction.isNaN()) {
            dragFraction
        } else {
            stopFraction(selectedIndex)
        }
        val thumbHeightPx = with(density) { 54.dp.toPx() }
        val thumbTopPx = constraints.maxHeight * thumbFraction - thumbHeightPx / 2f

        // One continuous pill and groove make this read as a stepped slider,
        // while the six icons retain immediate preset recognition.
        Box(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(30.dp))
                .background(PhoneSurface)
                .border(1.dp, PhonePrimary.copy(alpha = 0.38f), RoundedCornerShape(30.dp)),
        )
        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxHeight(0.72f)
                .width(3.dp)
                .clip(RoundedCornerShape(50))
                .background(PhonePrimary.copy(alpha = 0.34f)),
        )
        Icon(
            Icons.Filled.ExpandLess,
            contentDescription = null,
            tint = PhoneOnSurfaceMuted,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 3.dp).size(18.dp),
        )
        Icon(
            Icons.Filled.ExpandMore,
            contentDescription = null,
            tint = PhoneOnSurfaceMuted,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp).size(18.dp),
        )

        Column(
            Modifier.fillMaxSize().padding(vertical = 18.dp),
        ) {
            order.forEach { preset ->
                Box(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    GesturePresetIcon(
                        preset = preset,
                        tint = if (preset == selected) {
                            Color.Transparent
                        } else {
                            PhoneOnSurfaceMuted
                        },
                        modifier = Modifier.size(23.dp),
                    )
                }
            }
        }

        // The selected label intentionally extends toward the gesture surface:
        // it names the categorical stop without making the rail look like a
        // numeric level control.
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .offset {
                    IntOffset(
                        x = with(density) {
                            (if (labelTowardEnd) 76 else -76).dp.roundToPx()
                        },
                        y = thumbTopPx.roundToInt() + with(density) { 8.dp.roundToPx() },
                    )
                }
                .width(72.dp)
                .height(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(PhoneSurfaceElevated)
                .border(1.dp, PhonePrimaryBright, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                selected.label,
                color = PhonePrimaryBright,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
        }

        Box(
            Modifier
                .align(Alignment.TopCenter)
                .offset { IntOffset(0, thumbTopPx.roundToInt()) }
                .size(width = 56.dp, height = 54.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(PhonePrimary)
                .border(2.dp, PhonePrimaryBright, RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                GesturePresetIcon(
                    preset = selected,
                    tint = PhoneOnSurface,
                    modifier = Modifier.size(24.dp),
                )
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    repeat(2) {
                        Box(
                            Modifier
                                .width(21.dp)
                                .height(2.dp)
                                .clip(RoundedCornerShape(50))
                                .background(Color.White.copy(alpha = 0.68f)),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BoxScope.GestureCrosshair() {
    Box(
        Modifier
            .align(Alignment.Center)
            .fillMaxHeight()
            .width(1.dp)
            .background(PhonePrimary.copy(alpha = 0.12f)),
    )
    Box(
        Modifier
            .align(Alignment.Center)
            .fillMaxWidth()
            .height(1.dp)
            .background(PhonePrimary.copy(alpha = 0.12f)),
    )
}

@Composable
private fun DirectionHint(text: String, active: Boolean, modifier: Modifier) {
    val color by animateColorAsState(
        if (active) GUIDE_GLOW else GUIDE_DIM,
        tween(150),
        label = "gestureGuideColor",
    )
    val scale by animateFloatAsState(
        if (active) 1.16f else 1f,
        tween(150),
        label = "gestureGuideScale",
    )
    Text(
        text = text,
        color = color,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        modifier = modifier.scale(scale),
    )
}

@Composable
private fun BoxScope.CenterDisc(label: String, scale: Float) {
    Box(
        Modifier
            .align(Alignment.Center)
            .scale(scale)
            .size(72.dp)
            .clip(RoundedCornerShape(50))
            .background(PhonePrimary.copy(alpha = 0.16f))
            .border(2.dp, PhonePrimaryBright, RoundedCornerShape(50)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = PhonePrimaryBright,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BoxScope.VolumeSurface(
    volumePercent: Int,
    isMuted: Boolean,
    activeDirection: String?,
    onMuteToggle: () -> Unit,
) {
    // Wordless rotary affordance: each time Volume is entered, the endpoint
    // handle briefly rocks both ways without changing the real volume.
    val rotationCue = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        rotationCue.snapTo(0f)
        rotationCue.animateTo(14f, tween(180))
        rotationCue.animateTo(-14f, tween(280))
        rotationCue.animateTo(0f, tween(180))
    }
    Box(
        Modifier
            .align(Alignment.Center)
            .size(220.dp),
        contentAlignment = Alignment.Center,
    ) {
        val track = PhonePrimary.copy(alpha = 0.18f)
        val progress = PhonePrimaryBright
        Canvas(Modifier.fillMaxSize()) {
            val strokeWidth = 10.dp.toPx()
            val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            val ringInset = strokeWidth / 2f
            val ringSize = androidx.compose.ui.geometry.Size(
                size.width - ringInset * 2f,
                size.height - ringInset * 2f,
            )
            val ringRadius = ringSize.minDimension / 2f
            drawArc(
                color = track,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(ringInset, ringInset),
                size = ringSize,
                style = stroke,
            )
            if (!isMuted && volumePercent > 0) {
                drawArc(
                    color = progress,
                    startAngle = -90f,
                    sweepAngle = 360f * volumePercent.coerceIn(0, 100) / 100f,
                    useCenter = false,
                    topLeft = Offset(ringInset, ringInset),
                    size = ringSize,
                    style = stroke,
                )
            }
            // The handle stays visible at 100%, where a full progress circle
            // otherwise looks static. A short outer glow shows live direction.
            val levelAngle = -90f +
                360f * volumePercent.coerceIn(0, 100) / 100f +
                rotationCue.value
            val radians = Math.toRadians(levelAngle.toDouble())
            val handleCenter = Offset(
                x = center.x + cos(radians).toFloat() * ringRadius,
                y = center.y + sin(radians).toFloat() * ringRadius,
            )
            activeDirection?.let { direction ->
                val cueSweep = if (direction == "clockwise") 22f else -22f
                drawArc(
                    color = PhonePrimaryBright.copy(alpha = 0.48f),
                    startAngle = levelAngle - cueSweep,
                    sweepAngle = cueSweep,
                    useCenter = false,
                    topLeft = Offset(4.dp.toPx(), 4.dp.toPx()),
                    size = androidx.compose.ui.geometry.Size(
                        size.width - 8.dp.toPx(),
                        size.height - 8.dp.toPx(),
                    ),
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
                )
            }
            drawCircle(
                color = PhoneSurface,
                radius = 9.dp.toPx(),
                center = handleCenter,
            )
            drawCircle(
                color = if (isMuted) PhonePrimaryBright.copy(alpha = 0.48f) else PhonePrimaryBright,
                radius = 6.dp.toPx(),
                center = handleCenter,
            )
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = if (isMuted) "Muted" else "$volumePercent%",
                color = PhonePrimaryBright,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(PhoneSurfaceElevated)
                    .border(1.dp, PhonePrimary.copy(alpha = 0.52f), RoundedCornerShape(50))
                    .combinedClickable(
                        onClick = {},
                        onLongClickLabel = if (isMuted) "Unmute" else "Mute",
                        onLongClick = onMuteToggle,
                    )
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (isMuted) {
                        Icons.AutoMirrored.Filled.VolumeUp
                    } else {
                        Icons.AutoMirrored.Filled.VolumeOff
                    },
                    contentDescription = null,
                    tint = PhonePrimaryBright,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = if (isMuted) "Hold to unmute" else "Hold to mute",
                    color = PhonePrimaryBright,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}
