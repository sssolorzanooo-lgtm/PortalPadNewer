package com.portalpad.app

import android.app.Application
import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.test.core.app.ApplicationProvider
import com.portalpad.app.data.GestureAction
import com.portalpad.app.data.PreferencesRepository
import com.portalpad.app.ui.trackpad.GestureRecognizer
import com.portalpad.app.ui.trackpad.GestureSink
import com.portalpad.app.ui.trackpad.TapEnabledRef
import com.portalpad.app.ui.trackpad.ThreeFingerSwipeAxes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TouchpadGesturesAndTimingTest {

    private class RecordingSink : GestureSink {
        var taps = 0
        var secondaryTaps = 0
        var longPressStarts = 0
        var longPressEnds = 0
        var threeFingerTaps = 0
        var threeFingerSwipes = 0
        var scrolls = 0
        var pinches = 0
        var dragStarts = 0
        var onEdge = false
        var volumeSwipeStarts = 0
        val volumeSteps = mutableListOf<Boolean>()
        val volumeSwipeEnds = mutableListOf<Boolean>()
        var seekSwipeStarts = 0
        val seekUpdates = mutableListOf<Pair<Int, Boolean>>()
        val seekEnds = mutableListOf<Triple<Int, Boolean, Boolean>>()

        override fun onMove(dx: Float, dy: Float) {}
        override fun onTap() { taps++ }
        override fun onSecondaryTap() { secondaryTaps++ }
        override fun onDoubleTap() {}
        override fun onLongPressStart() { longPressStarts++ }
        override fun onLongPressEnd() { longPressEnds++ }
        override fun onScroll(dx: Float, dy: Float) { scrolls++ }
        override fun onPinch(scale: Float) { pinches++ }
        override fun onThreeFingerSwipe(dx: Float, dy: Float) { threeFingerSwipes++ }
        override fun onThreeFingerTap() { threeFingerTaps++ }
        override fun onThreeFingerVolumeSwipeStart() { volumeSwipeStarts++ }
        override fun onThreeFingerVolumeSwipeStep(up: Boolean) { volumeSteps.add(up) }
        override fun onThreeFingerVolumeSwipeEnd(autoFadeAfterFlick: Boolean) {
            volumeSwipeEnds.add(autoFadeAfterFlick)
        }
        override fun onThreeFingerSeekSwipeStart() { seekSwipeStarts++ }
        override fun onThreeFingerSeekSwipeUpdate(totalSeconds: Int, forward: Boolean) {
            seekUpdates.add(totalSeconds to forward)
        }
        override fun onThreeFingerSeekSwipeEnd(
            totalSeconds: Int,
            commit: Boolean,
            autoFadeAfterFlick: Boolean,
        ) {
            seekEnds.add(Triple(totalSeconds, commit, autoFadeAfterFlick))
        }
        override fun onDragStart(captionMove: Boolean) { dragStarts++ }
        override fun isOnResizeEdge(): Boolean = onEdge
    }

    private fun pointer(
        id: Long,
        pos: Offset,
        pressed: Boolean,
        previousPressed: Boolean,
        timeMs: Long,
        prevPos: Offset = pos,
    ): PointerInputChange = PointerInputChange(
        id = PointerId(id),
        uptimeMillis = timeMs,
        position = pos,
        pressed = pressed,
        pressure = if (pressed) 1f else 0f,
        previousUptimeMillis = timeMs - 8L,
        previousPosition = prevPos,
        previousPressed = previousPressed,
        isInitiallyConsumed = false,
        type = PointerType.Touch,
        scrollDelta = Offset.Zero,
    )

    private fun triadAt(
        center: Offset,
        timeMs: Long,
        pressed: Boolean = true,
        prevPressed: Boolean = true,
    ): List<PointerInputChange> = listOf(
        pointer(1L, Offset(center.x - 50f, center.y), pressed, prevPressed, timeMs),
        pointer(2L, center, pressed, prevPressed, timeMs),
        pointer(3L, Offset(center.x + 50f, center.y), pressed, prevPressed, timeMs),
    )

    @Test
    fun oneFingerTiming_250msTap_250To349DeadZone_350msLongPress_380msHold_300msEdge() {
        var now = 1000L
        var scheduledDelay: Long? = null
        var scheduledRunnable: Runnable? = null
        val sink = RecordingSink()
        val recognizer = GestureRecognizer(
            sink = sink,
            tapEnabled = TapEnabledRef { true },
            densityScale = 1f,
            clockMs = { now },
            scheduleHoldCallback = { delayMs, block ->
                scheduledDelay = delayMs
                scheduledRunnable = block
            },
        )

        // 1) Normal click at 249ms (< 250L) -> fires left click.
        recognizer.onPointerEvent(listOf(pointer(1L, Offset(200f, 200f), true, false, now)), 800f)
        assertEquals(380L, scheduledDelay)
        now += 249L
        recognizer.onPointerEvent(listOf(pointer(1L, Offset(202f, 202f), false, true, now)), 800f)
        assertEquals(1, sink.taps)
        assertEquals(0, sink.secondaryTaps)

        // 2) Dead zone at 250ms -> neither tap nor long press.
        now = 2000L
        recognizer.onPointerEvent(listOf(pointer(2L, Offset(200f, 200f), true, false, now)), 800f)
        now += 250L
        recognizer.onPointerEvent(listOf(pointer(2L, Offset(200f, 200f), false, true, now)), 800f)
        assertEquals(1, sink.taps)
        assertEquals(0, sink.secondaryTaps)

        // 3) Dead zone at 349ms -> still neither tap nor long press.
        now = 3000L
        recognizer.onPointerEvent(listOf(pointer(3L, Offset(200f, 200f), true, false, now)), 800f)
        now += 349L
        recognizer.onPointerEvent(listOf(pointer(3L, Offset(200f, 200f), false, true, now)), 800f)
        assertEquals(1, sink.taps)
        assertEquals(0, sink.secondaryTaps)

        // 4) Hold at 350ms with small jitter (15px <= dragEngagePx=24px <= longPressSlopPx=40px) -> fires long press.
        now = 4000L
        recognizer.onPointerEvent(listOf(pointer(4L, Offset(200f, 200f), true, false, now)), 800f)
        now += 350L
        recognizer.onPointerEvent(listOf(pointer(4L, Offset(215f, 200f), true, true, now)), 800f)
        recognizer.onPointerEvent(listOf(pointer(4L, Offset(215f, 200f), false, true, now)), 800f)
        assertEquals(1, sink.secondaryTaps)

        // 5) Quick drift > 40px during hold does NOT fire long press or drag.
        now = 5000L
        recognizer.onPointerEvent(listOf(pointer(5L, Offset(200f, 200f), true, false, now)), 800f)
        now += 100L
        recognizer.onPointerEvent(listOf(pointer(5L, Offset(245f, 200f), true, true, now)), 800f)
        now += 260L
        recognizer.onPointerEvent(listOf(pointer(5L, Offset(245f, 200f), false, true, now)), 800f)
        assertEquals(1, sink.secondaryTaps)
        assertEquals(0, sink.dragStarts)

        // 6) Streaming hold callback at 380ms invokes onLongPressStart.
        now = 6000L
        recognizer.onPointerEvent(listOf(pointer(6L, Offset(200f, 200f), true, false, now)), 800f)
        now += 380L
        scheduledRunnable?.run()
        assertEquals(1, sink.longPressStarts)
        recognizer.onPointerEvent(listOf(pointer(6L, Offset(200f, 200f), false, true, now)), 800f)
        assertEquals(1, sink.longPressEnds)

        // 7) Edge-hold remains 300ms (unchanged).
        sink.onEdge = true
        now = 7000L
        recognizer.onPointerEvent(listOf(pointer(7L, Offset(200f, 200f), true, false, now)), 800f)
        now += 299L
        recognizer.onPointerEvent(listOf(pointer(7L, Offset(201f, 200f), true, true, now)), 800f)
        assertEquals(0, sink.dragStarts)
        now += 1L
        recognizer.onPointerEvent(listOf(pointer(7L, Offset(202f, 200f), true, true, now)), 800f)
        assertEquals(1, sink.dragStarts)
        recognizer.onPointerEvent(listOf(pointer(7L, Offset(202f, 200f), false, true, now)), 800f)
    }

    @Test
    fun threeFingerTap_firesOnceAndSuppressesStaggeredRightAndLeftClick() {
        var now = 1000L
        val sink = RecordingSink()
        val recognizer = GestureRecognizer(
            sink = sink,
            tapEnabled = TapEnabledRef { true },
            densityScale = 1f,
            clockMs = { now },
        )

        val p1 = Offset(200f, 300f)
        val p2 = Offset(260f, 300f)
        val p3 = Offset(320f, 300f)
        recognizer.onPointerEvent(
            listOf(
                pointer(1L, p1, true, false, now),
                pointer(2L, p2, true, false, now),
                pointer(3L, p3, true, false, now),
            ),
            800f,
        )
        now += 90L
        recognizer.onPointerEvent(
            listOf(
                pointer(1L, p1, true, true, now),
                pointer(2L, p2, true, true, now),
                pointer(3L, p3, false, true, now),
            ),
            800f,
        )
        now += 20L
        recognizer.onPointerEvent(
            listOf(
                pointer(1L, p1, true, true, now),
                pointer(2L, p2, false, true, now),
            ),
            800f,
        )
        now += 20L
        recognizer.onPointerEvent(
            listOf(pointer(1L, p1, false, true, now)),
            800f,
        )

        assertEquals(1, sink.threeFingerTaps)
        assertEquals(0, sink.secondaryTaps)
        assertEquals(0, sink.taps)
        assertEquals(0, sink.threeFingerSwipes)
    }

    @Test
    fun threeFingerSwipes_neverTriggerThreeFingerTap() {
        var now = 1000L
        val sink = RecordingSink()
        val recognizer = GestureRecognizer(
            sink = sink,
            tapEnabled = TapEnabledRef { true },
            densityScale = 1f,
            clockMs = { now },
        )

        // 1) Short aborted swipe (30px > 12px tap slop, < 48px swipe threshold) lifted quickly (90ms)
        recognizer.onPointerEvent(triadAt(Offset(300f, 300f), now, pressed = true, prevPressed = false), 800f)
        now += 40L
        recognizer.onPointerEvent(triadAt(Offset(330f, 300f), now, pressed = true, prevPressed = true), 800f)
        now += 40L
        recognizer.onPointerEvent(triadAt(Offset(330f, 300f), now, pressed = false, prevPressed = true), 800f)
        assertEquals(0, sink.threeFingerTaps)
        assertTrue(sink.seekUpdates.isEmpty())

        // 2) Fast flick where displacement past tap slop arrives ON the lift frame itself (count 3 -> 2)
        now = 2000L
        recognizer.onPointerEvent(triadAt(Offset(300f, 300f), now, pressed = true, prevPressed = false), 800f)
        now += 60L
        recognizer.onPointerEvent(
            listOf(
                pointer(1L, Offset(250f, 275f), true, true, now),
                pointer(2L, Offset(300f, 275f), true, true, now),
                pointer(3L, Offset(350f, 275f), false, true, now),
            ),
            800f,
        )
        assertEquals(0, sink.threeFingerTaps)

        // 3) Third finger touches while 2 fingers were already scrolling/moving -> never triggers tap
        now = 3000L
        recognizer.onPointerEvent(
            listOf(
                pointer(1L, Offset(250f, 300f), true, false, now),
                pointer(2L, Offset(350f, 300f), true, false, now),
            ),
            800f,
        )
        now += 20L
        recognizer.onPointerEvent(
            listOf(
                pointer(1L, Offset(250f, 340f), true, true, now),
                pointer(2L, Offset(350f, 340f), true, true, now),
            ),
            800f,
        )
        now += 20L
        recognizer.onPointerEvent(
            listOf(
                pointer(1L, Offset(250f, 340f), true, true, now),
                pointer(2L, Offset(350f, 340f), true, true, now),
                pointer(3L, Offset(300f, 340f), true, false, now),
            ),
            800f,
        )
        now += 40L
        recognizer.onPointerEvent(
            listOf(
                pointer(1L, Offset(250f, 340f), false, true, now),
                pointer(2L, Offset(350f, 340f), false, true, now),
                pointer(3L, Offset(300f, 340f), false, true, now),
            ),
            800f,
        )
        assertEquals(0, sink.threeFingerTaps)
    }

    @Test
    fun threeFingerSwipeUpDown_controlsVolumeWithLiveSteps() {
        var now = 1000L
        val sink = RecordingSink()
        val recognizer = GestureRecognizer(
            sink = sink,
            tapEnabled = TapEnabledRef { true },
            densityScale = 1f,
            clockMs = { now },
        )

        // Swipe up past 48px threshold -> VOLUME_UP, then drag further up and back down.
        recognizer.onPointerEvent(triadAt(Offset(300f, 400f), now, pressed = true, prevPressed = false), 800f)
        now += 30L
        recognizer.onPointerEvent(triadAt(Offset(300f, 345f), now), 800f) // -55px -> latch Volume Up (+1 step)
        assertEquals(1, sink.volumeSwipeStarts)
        assertEquals(listOf(true), sink.volumeSteps)

        now += 150L
        recognizer.onPointerEvent(triadAt(Offset(300f, 285f), now), 800f) // -60px further (2x28px = +2 up steps)
        assertEquals(listOf(true, true, true), sink.volumeSteps)

        now += 250L // sustained drag (> 380ms)
        recognizer.onPointerEvent(triadAt(Offset(300f, 320f), now), 800f) // +35px down -> -1 step
        assertEquals(listOf(true, true, true, false), sink.volumeSteps)

        now += 20L
        recognizer.onPointerEvent(triadAt(Offset(300f, 320f), now, pressed = false, prevPressed = true), 800f)
        assertEquals(listOf(false), sink.volumeSwipeEnds) // held drag -> dismisses immediately on lift
        assertEquals(0, sink.threeFingerTaps)
    }

    @Test
    fun threeFingerSwipeLeftRight_seeksAndRewindsClampedAt60sAndCommitsOnLift() {
        var now = 1000L
        val sink = RecordingSink()
        val recognizer = GestureRecognizer(
            sink = sink,
            tapEnabled = TapEnabledRef { true },
            densityScale = 1f,
            clockMs = { now },
        )

        // Swipe right -> SEEK_FORWARD (+10s), scrub up to +60s cap, reverse once to +50s.
        recognizer.onPointerEvent(triadAt(Offset(200f, 300f), now, pressed = true, prevPressed = false), 800f)
        now += 20L
        recognizer.onPointerEvent(triadAt(Offset(255f, 300f), now), 800f) // +55px -> +10s
        assertEquals(1, sink.seekSwipeStarts)
        assertEquals(10 to true, sink.seekUpdates.last())

        // Drag far right (+290px more -> well past 5*42px = 210px) -> caps cleanly at +60s
        now += 250L
        recognizer.onPointerEvent(triadAt(Offset(545f, 300f), now), 800f)
        assertEquals(60, sink.seekUpdates.last().first)
        assertTrue(sink.seekUpdates.all { it.first in -60..60 })

        // Reverse left 45px (> 42px) -> immediately drops from +60s to +50s
        now += 200L
        recognizer.onPointerEvent(triadAt(Offset(500f, 300f), now), 800f)
        assertEquals(50 to false, sink.seekUpdates.last())

        now += 20L
        recognizer.onPointerEvent(triadAt(Offset(500f, 300f), now, pressed = false, prevPressed = true), 800f)
        assertEquals(listOf(Triple(50, true, false)), sink.seekEnds)
        assertEquals(0, sink.threeFingerTaps)

        // Swipe left -> REWIND (-10s), quick flick (< 380ms) -> autoFadeAfterFlick = true
        now = 3000L
        recognizer.onPointerEvent(triadAt(Offset(400f, 300f), now, pressed = true, prevPressed = false), 800f)
        now += 40L
        recognizer.onPointerEvent(triadAt(Offset(345f, 300f), now), 800f) // -55px -> -10s
        assertEquals(-10 to false, sink.seekUpdates.last())
        now += 40L
        recognizer.onPointerEvent(triadAt(Offset(345f, 300f), now, pressed = false, prevPressed = true), 800f)
        assertEquals(Triple(-10, true, true), sink.seekEnds.last())
        assertEquals(0, sink.threeFingerTaps)
    }

    @Test
    fun threeFingerCustomAction_stillFiresOnceWhenAssigned() {
        var now = 1000L
        val sink = RecordingSink()
        val recognizer = GestureRecognizer(
            sink = sink,
            tapEnabled = TapEnabledRef { true },
            densityScale = 1f,
            threeFingerSwipeAxes = {
                ThreeFingerSwipeAxes(
                    up = GestureAction.NOTIF_CLOSE,
                    down = GestureAction.NOTIF_OPEN,
                    left = GestureAction.HOME,
                    right = GestureAction.BACK,
                )
            },
            clockMs = { now },
        )

        recognizer.onPointerEvent(triadAt(Offset(300f, 300f), now, pressed = true, prevPressed = false), 800f)
        now += 40L
        recognizer.onPointerEvent(triadAt(Offset(365f, 300f), now), 800f)
        now += 40L
        recognizer.onPointerEvent(triadAt(Offset(420f, 300f), now), 800f)
        now += 20L
        recognizer.onPointerEvent(triadAt(Offset(420f, 300f), now, pressed = false, prevPressed = true), 800f)
        assertEquals(1, sink.threeFingerSwipes)
        assertEquals(0, sink.threeFingerTaps)
    }

    @Test
    fun twoFingerScroll_verticalAndHorizontalWorkWithoutVolumeInterference() {
        var now = 1000L
        val sink = RecordingSink()
        val recognizer = GestureRecognizer(
            sink = sink,
            tapEnabled = TapEnabledRef { true },
            densityScale = 1f,
            clockMs = { now },
        )

        recognizer.onPointerEvent(
            listOf(
                pointer(1L, Offset(250f, 300f), true, false, now),
                pointer(2L, Offset(350f, 300f), true, false, now),
            ),
            800f,
        )
        for (i in 1..6) {
            now += 16L
            val y = 300f + i * 20f
            recognizer.onPointerEvent(
                listOf(
                    pointer(1L, Offset(250f, y), true, true, now),
                    pointer(2L, Offset(350f, y), true, true, now),
                ),
                800f,
            )
        }
        assertTrue(sink.scrolls > 0)
        assertEquals(0, sink.volumeSwipeStarts)
    }

    @Test
    fun preferencesRepository_threeFingerSwipeDefaultsAreVolumeAndSeek() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val prefs = PreferencesRepository(context)

        assertTrue(prefs.gestureThreeFingerTapEnabled.first())
        assertEquals(GestureAction.PLAY_PAUSE_MEDIA, prefs.gestureThreeFingerTap.first())
        assertEquals(GestureAction.VOLUME_UP, prefs.gestureSwipeUp.first())
        assertEquals(GestureAction.VOLUME_DOWN, prefs.gestureSwipeDown.first())
        assertEquals(GestureAction.REWIND, prefs.gestureSwipeLeft.first())
        assertEquals(GestureAction.SEEK_FORWARD, prefs.gestureSwipeRight.first())

        prefs.setGestureSwipeUp(GestureAction.HOME)
        prefs.setGestureThreeFingerTapEnabled(false)
        assertEquals(GestureAction.HOME, prefs.gestureSwipeUp.first())
        assertFalse(prefs.gestureThreeFingerTapEnabled.first())
    }
}
