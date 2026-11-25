package com.portalpad.app

import android.app.Application
import com.portalpad.app.data.DockConfig
import com.portalpad.app.service.MouseLeftHoldTracker
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RecentAppsAndMouseHoldTest {

    private class RecordingMouseActions : MouseLeftHoldTracker.Actions {
        var pointerMoves = mutableListOf<Pair<Float, Float>>()
        var leftClicks = 0
        var rightClicks = 0
        var longPressStarts = 0
        var longPressEnds = 0
        var dragStarts = mutableListOf<Boolean>()
        var dragMoves = mutableListOf<Pair<Float, Float>>()
        var dragEnds = 0

        override fun onPointerMove(dx: Float, dy: Float) {
            pointerMoves += dx to dy
        }

        override fun onLeftClick() {
            leftClicks++
        }

        override fun onRightClick() {
            rightClicks++
        }

        override fun onLongPressStart() {
            longPressStarts++
        }

        override fun onLongPressEnd() {
            longPressEnds++
        }

        override fun onDragStart(captionMove: Boolean) {
            dragStarts += captionMove
        }

        override fun onDragMove(dx: Float, dy: Float) {
            dragMoves += dx to dy
        }

        override fun onDragEnd() {
            dragEnds++
        }
    }

    private class FakeHoldScheduler(private val clockMs: () -> Long) : MouseLeftHoldTracker.HoldScheduler {
        data class Entry(val fireAtMs: Long, val task: Runnable, var cancelled: Boolean = false)

        private val entries = mutableListOf<Entry>()

        override fun schedule(delayMs: Long, task: Runnable): Any {
            val e = Entry(clockMs() + delayMs, task)
            entries += e
            return e
        }

        override fun cancel(handle: Any) {
            (handle as? Entry)?.cancelled = true
        }

        fun runDue() {
            val now = clockMs()
            val due = entries.filter { !it.cancelled && it.fireAtMs <= now }
                .sortedBy { it.fireAtMs }
            due.forEach { entry ->
                entry.cancelled = true
                entry.task.run()
            }
        }
    }

    @Test
    fun dockConfig_showRecentAppsDefaultsTrueAndRoundTrips() {
        val json = Json { ignoreUnknownKeys = true }
        val legacy = json.decodeFromString<DockConfig>("{}")
        assertTrue(legacy.showRecentAppsButton)

        val disabled = DockConfig(showRecentAppsButton = false)
        val encoded = json.encodeToString(DockConfig.serializer(), disabled)
        val decoded = json.decodeFromString<DockConfig>(encoded)
        assertFalse(decoded.showRecentAppsButton)
    }

    @Test
    fun mouseHoldTracker_quickClickFiresLeftClick() {
        var now = 1000L
        val actions = RecordingMouseActions()
        val scheduler = FakeHoldScheduler { now }
        val tracker = MouseLeftHoldTracker(actions, clockMs = { now }, scheduler = scheduler)

        tracker.onLeftDown(onResizeEdge = false, onCaption = false, onHandle = false, onButton = false)
        now += 120L
        scheduler.runDue()
        tracker.onLeftUp()

        assertEquals(1, actions.leftClicks)
        assertEquals(0, actions.rightClicks)
        assertEquals(0, actions.longPressStarts)
        assertEquals(0, actions.dragStarts.size)
    }

    @Test
    fun mouseHoldTracker_stationaryHoldFiresLongPressStartAndRightClickOnRelease() {
        var now = 2000L
        val actions = RecordingMouseActions()
        val scheduler = FakeHoldScheduler { now }
        val tracker = MouseLeftHoldTracker(actions, clockMs = { now }, scheduler = scheduler)

        tracker.onLeftDown(onResizeEdge = false, onCaption = false, onHandle = false, onButton = false)
        now += 350L
        scheduler.runDue()
        assertEquals(1, actions.longPressStarts)
        assertEquals(0, actions.rightClicks)

        now += 200L
        tracker.onLeftUp()
        assertEquals(1, actions.longPressEnds)
        assertEquals(1, actions.rightClicks)
        assertEquals(0, actions.leftClicks)
        assertEquals(0, actions.dragStarts.size)
    }

    @Test
    fun mouseHoldTracker_holdThenMovePromotesToDragAndStopsLongPress() {
        var now = 3000L
        val actions = RecordingMouseActions()
        val scheduler = FakeHoldScheduler { now }
        val tracker = MouseLeftHoldTracker(actions, clockMs = { now }, scheduler = scheduler)

        tracker.onLeftDown(onResizeEdge = false, onCaption = false, onHandle = false, onButton = false)
        now += 360L
        scheduler.runDue()
        assertEquals(1, actions.longPressStarts)

        tracker.onMove(15f, 0f)
        assertEquals(1, actions.longPressEnds)
        assertEquals(listOf(false), actions.dragStarts)

        tracker.onMove(8f, 4f)
        assertEquals(listOf(8f to 4f), actions.dragMoves)

        tracker.onLeftUp()
        assertEquals(1, actions.dragEnds)
        assertEquals(0, actions.leftClicks)
        assertEquals(0, actions.rightClicks)
    }

    @Test
    fun mouseHoldTracker_immediateMovePromotesToDrag() {
        var now = 4000L
        val actions = RecordingMouseActions()
        val scheduler = FakeHoldScheduler { now }
        val tracker = MouseLeftHoldTracker(actions, clockMs = { now }, scheduler = scheduler)

        tracker.onLeftDown(onResizeEdge = false, onCaption = false, onHandle = false, onButton = false)
        tracker.onMove(12f, 5f)
        assertEquals(listOf(false), actions.dragStarts)

        tracker.onMove(10f, 10f)
        assertEquals(listOf(10f to 10f), actions.dragMoves)

        now += 50L
        tracker.onLeftUp()
        assertEquals(1, actions.dragEnds)
        assertEquals(0, actions.leftClicks)
        assertEquals(0, actions.rightClicks)
    }

    @Test
    fun mouseHoldTracker_edgeAndCaptionRestHoldPromoteToDrag() {
        var now = 5000L
        val actions = RecordingMouseActions()
        val scheduler = FakeHoldScheduler { now }
        val tracker = MouseLeftHoldTracker(actions, clockMs = { now }, scheduler = scheduler)

        tracker.onLeftDown(onResizeEdge = true, onCaption = false, onHandle = false, onButton = false)
        now += 300L
        scheduler.runDue()
        assertEquals(listOf(false), actions.dragStarts)
        tracker.onLeftUp()
        assertEquals(1, actions.dragEnds)

        tracker.onLeftDown(onResizeEdge = false, onCaption = true, onHandle = false, onButton = false)
        now += 300L
        scheduler.runDue()
        assertEquals(listOf(false, true), actions.dragStarts)
        tracker.onLeftUp()
        assertEquals(2, actions.dragEnds)
    }

    @Test
    fun mouseHoldTracker_buttonPressNeverPromotesToWindowDragAndCancelCleansUp() {
        var now = 6000L
        val actions = RecordingMouseActions()
        val scheduler = FakeHoldScheduler { now }
        val tracker = MouseLeftHoldTracker(actions, clockMs = { now }, scheduler = scheduler)

        tracker.onLeftDown(onResizeEdge = true, onCaption = true, onHandle = false, onButton = true)
        now += 300L
        scheduler.runDue()
        tracker.onMove(16f, 0f)
        assertTrue(actions.dragStarts.isEmpty())
        tracker.onLeftUp()
        assertEquals(1, actions.leftClicks)

        tracker.onLeftDown(onResizeEdge = false, onCaption = false, onHandle = false, onButton = false)
        tracker.onMove(20f, 0f)
        tracker.cancel()
        assertEquals(1, actions.dragEnds)
    }

    @Test
    fun freeform_parsePhonePipTasks_extractsPinnedPhoneAppsAndIgnoresExternalAndSystem() {
        val manager = com.portalpad.app.service.FreeformManager {
            error("No elevated access needed for dump parsing")
        }
        val dump = """
            Display #0
              RootTask id=99 displayId=0 windowingMode=1
                taskId=99 com.android.chrome/com.google.android.apps.chrome.Main bounds=[0,0][1080,2400]
              RootTask id=101 displayId=0 windowingMode=2 mode=pinned
                taskId=101 com.google.android.youtube/com.google.android.youtube.WatchActivity bounds=[580,1400][1040,1700] visible=true
              mPinnedTaskController
                mPinnedTask=ActivityRecord{abc u0 tv.twitch.android.app/.LiveActivity t204}
                ActivityRecord{def u0 com.portalpad.app/.MainActivity t205}
                ActivityRecord{ghi u0 com.google.android.apps.nexuslauncher/.NexusLauncherActivity t1}
            Display #3
              RootTask id=300 displayId=3 windowingMode=2 mode=pinned
                taskId=300 com.netflix.mediaclient/.PlayerActivity displayId=3 bounds=[0,0][1920,1080]
        """.trimIndent()

        val tasks = manager.parsePhonePipTasks(dump)
        assertEquals(listOf("com.google.android.youtube", "tv.twitch.android.app"), tasks.map { it.packageName })
        assertEquals(listOf(101, 204), tasks.map { it.taskId })
        assertTrue(tasks.all { it.displayId == 0 })
    }

    @Test
    fun portalPadApp_recordExternalApp_updateActiveFalsePreservesLastDisplayedAppAndOrder() {
        val app = PortalPadApp()
        app.recordExternalApp("com.google.android.youtube/.MainActivity", force = true, updateActive = true)
        app.recordExternalApp("com.android.chrome/com.google.android.apps.chrome.Main", force = true, updateActive = true)

        assertEquals("com.android.chrome", app.activeExternalApp?.packageName)
        assertEquals(
            listOf("com.android.chrome", "com.google.android.youtube"),
            app.externalRecentApps.value.map { it.packageName },
        )

        // Background stack sweeps must never overwrite the frontmost displayed app
        // with the second-to-last app or reorder the recent list.
        app.recordExternalApp("com.google.android.youtube/.MainActivity", force = false, updateActive = false)
        app.recordExternalApp("com.spotify.music/.MainActivity", force = false, updateActive = false)

        assertEquals("com.android.chrome", app.activeExternalApp?.packageName)
        assertEquals(
            listOf("com.android.chrome", "com.google.android.youtube", "com.spotify.music"),
            app.externalRecentApps.value.map { it.packageName },
        )
    }
}
