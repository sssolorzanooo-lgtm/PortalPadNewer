package com.portalpad.app.service

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.math.hypot

/**
 * Unified press-and-hold + drag state machine for external mouse left-button
 * input (shared by captured BT mouse readLoop and uncaptured MotionEvent fallback).
 *
 * Mirrors PortalPad's touchpad hold behavior:
 *  - Quick press & release (< holdThresholdMs, within drag threshold) -> leftClick
 *  - Stationary hold (>= holdThresholdMs) -> fires onLongPressStart while held
 *    (continuous keyboard seek hold), and onLongPressEnd + rightClick (context /
 *    touch long-press select) on release
 *  - Rest-hold on a resize edge / caption strip (>= edgeHoldMs) OR moving past
 *    drag threshold while held -> promotes to dragStart(captionMove) -> dragMove
 *    -> dragEnd
 */
class MouseLeftHoldTracker(
    private val actions: Actions,
    private val clockMs: () -> Long = { SystemClock.uptimeMillis() },
    private val scheduler: HoldScheduler = MainLooperHoldScheduler(),
    private val holdThresholdMs: Long = DEFAULT_HOLD_THRESHOLD_MS,
    private val edgeHoldMs: Long = DEFAULT_EDGE_HOLD_MS,
    private val dragThresholdPx: Double = DEFAULT_DRAG_THRESHOLD_PX,
    private val handleDragThresholdPx: Double = DEFAULT_HANDLE_DRAG_THRESHOLD_PX,
    private val longPressSlopPx: Double = DEFAULT_LONG_PRESS_SLOP_PX,
) {
    interface Actions {
        fun onPointerMove(dx: Float, dy: Float)
        fun onLeftClick()
        fun onRightClick()
        fun onLongPressStart()
        fun onLongPressEnd()
        fun onDragStart(captionMove: Boolean)
        fun onDragMove(dx: Float, dy: Float)
        fun onDragEnd()
    }

    interface HoldScheduler {
        fun schedule(delayMs: Long, task: Runnable): Any
        fun cancel(handle: Any)
    }

    private class MainLooperHoldScheduler : HoldScheduler {
        private val handler = Handler(Looper.getMainLooper())
        override fun schedule(delayMs: Long, task: Runnable): Any {
            handler.postDelayed(task, delayMs)
            return task
        }

        override fun cancel(handle: Any) {
            (handle as? Runnable)?.let { handler.removeCallbacks(it) }
        }
    }

    private var leftHeld = false
    private var leftDragging = false
    private var dragResolved = false
    private var holdStreamActive = false
    private var pressOnEdge = false
    private var pressOnCaption = false
    private var pressOnHandle = false
    private var pressOnButton = false
    private var pressedAtMs = 0L
    private var dragAccX = 0f
    private var dragAccY = 0f
    private var generation = 0
    private var holdTimerHandle: Any? = null
    private var quickZoneTimerHandle: Any? = null

    @Synchronized
    fun isLeftHeld(): Boolean = leftHeld

    @Synchronized
    fun onLeftDown(
        onResizeEdge: Boolean,
        onCaption: Boolean,
        onHandle: Boolean,
        onButton: Boolean,
    ) {
        if (leftHeld) return
        clearTimersLocked()
        generation++
        val gen = generation
        leftHeld = true
        leftDragging = false
        dragResolved = false
        holdStreamActive = false
        dragAccX = 0f
        dragAccY = 0f
        pressedAtMs = clockMs()

        pressOnHandle = onHandle
        pressOnButton = onButton
        pressOnCaption = (onCaption || onHandle) && !onButton
        pressOnEdge = onResizeEdge && !onHandle && !onButton

        val quickZone = pressOnEdge || pressOnCaption
        if (quickZone) {
            val quickTask = Runnable { onQuickZoneHoldElapsed(gen) }
            quickZoneTimerHandle = scheduler.schedule(edgeHoldMs, quickTask)
        }
        val holdTask = Runnable { onLongHoldElapsed(gen) }
        holdTimerHandle = scheduler.schedule(holdThresholdMs, holdTask)
    }

    @Synchronized
    fun onMove(dx: Float, dy: Float) {
        if (dx == 0f && dy == 0f) return
        if (!leftHeld) {
            actions.onPointerMove(dx, dy)
            return
        }
        if (leftDragging) {
            actions.onDragMove(dx, dy)
            return
        }
        if (!dragResolved) {
            actions.onPointerMove(dx, dy)
            dragAccX += dx
            dragAccY += dy
            val dist = hypot(dragAccX.toDouble(), dragAccY.toDouble())
            val threshold = if (pressOnHandle) handleDragThresholdPx else dragThresholdPx
            if (!pressOnButton && dist > threshold) {
                promoteToDragLocked()
            }
        } else {
            actions.onPointerMove(dx, dy)
        }
    }

    @Synchronized
    fun onLeftUp() {
        if (!leftHeld) return
        clearTimersLocked()
        generation++
        val heldMs = clockMs() - pressedAtMs
        val totalMotion = hypot(dragAccX.toDouble(), dragAccY.toDouble())
        val wasDragging = leftDragging
        val wasHoldStream = holdStreamActive

        leftHeld = false
        leftDragging = false
        dragResolved = false
        holdStreamActive = false

        when {
            wasDragging -> actions.onDragEnd()
            wasHoldStream -> {
                actions.onLongPressEnd()
                actions.onRightClick()
            }
            heldMs >= holdThresholdMs && totalMotion <= longPressSlopPx -> {
                actions.onRightClick()
            }
            else -> actions.onLeftClick()
        }
    }

    @Synchronized
    fun cancel() {
        clearTimersLocked()
        generation++
        val wasDragging = leftDragging
        val wasHoldStream = holdStreamActive
        leftHeld = false
        leftDragging = false
        dragResolved = false
        holdStreamActive = false
        dragAccX = 0f
        dragAccY = 0f
        if (wasHoldStream) actions.onLongPressEnd()
        if (wasDragging) actions.onDragEnd()
    }

    @Synchronized
    private fun onQuickZoneHoldElapsed(gen: Int) {
        if (gen != generation || !leftHeld || leftDragging || dragResolved || pressOnButton) return
        val dist = hypot(dragAccX.toDouble(), dragAccY.toDouble())
        if (dist <= REST_SLOP_PX) {
            promoteToDragLocked()
        }
    }

    @Synchronized
    private fun onLongHoldElapsed(gen: Int) {
        if (gen != generation || !leftHeld || leftDragging || dragResolved) return
        val dist = hypot(dragAccX.toDouble(), dragAccY.toDouble())
        if (dist <= longPressSlopPx && !holdStreamActive) {
            holdStreamActive = true
            actions.onLongPressStart()
        }
    }

    private fun promoteToDragLocked() {
        clearTimersLocked()
        if (holdStreamActive) {
            holdStreamActive = false
            actions.onLongPressEnd()
        }
        dragResolved = true
        leftDragging = true
        actions.onDragStart(captionMove = pressOnCaption)
    }

    private fun clearTimersLocked() {
        holdTimerHandle?.let { scheduler.cancel(it) }
        holdTimerHandle = null
        quickZoneTimerHandle?.let { scheduler.cancel(it) }
        quickZoneTimerHandle = null
    }

    companion object {
        const val DEFAULT_HOLD_THRESHOLD_MS = 350L
        const val DEFAULT_EDGE_HOLD_MS = 300L
        const val DEFAULT_DRAG_THRESHOLD_PX = 10.0
        const val DEFAULT_HANDLE_DRAG_THRESHOLD_PX = 2.0
        const val DEFAULT_LONG_PRESS_SLOP_PX = 40.0
        private const val REST_SLOP_PX = 18.0
    }
}
