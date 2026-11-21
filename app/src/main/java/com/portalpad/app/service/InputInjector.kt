package com.portalpad.app.service

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.portalpad.app.PortalPadApp
import com.portalpad.app.data.RunningTask
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlin.math.roundToLong

/**
 * Injects mouse, key, and touch events to the system.
 *
 * Every public injection method is wrapped in try/catch — a misbehaving inject
 * must not be allowed to crash the host activity. Errors are logged and the
 * call silently no-ops.
 *
 * Cursor state is exposed via [cursorPosition] so the dock overlay can render
 * a custom cursor on the external display. This works regardless of whether
 * the OEM renders a system mouse cursor — many don't render one for injected
 * SOURCE_MOUSE events on secondary displays.
 */
/** Shape the cursor overlay should render. Driven by resize-edge hover in
 *  desktop-windows mode; ARROW everywhere else. */
enum class CursorType { ARROW, RESIZE_H, RESIZE_V, RESIZE_NWSE, RESIZE_NESW, MOVE }

class InputInjector(
    private val accessProvider: () -> ElevatedAccess,
    private val context: Context,
) {

    /**
     * Cached InputManager reflection — looking these up via reflection on every
     * single inject was the dominant latency contributor for d-pad repeat. We
     * resolve them once on first use and reuse forever.
     */
    private val cachedInputManager: Any? by lazy {
        runCatching {
            Class.forName("android.hardware.input.InputManager")
                .getMethod("getInstance").invoke(null)
        }.getOrNull()
    }
    private val cachedInjectMethod: java.lang.reflect.Method? by lazy {
        cachedInputManager?.let {
            runCatching {
                it.javaClass.getMethod(
                    "injectInputEvent",
                    android.view.InputEvent::class.java,
                    Int::class.javaPrimitiveType,
                )
            }.getOrNull()
        }
    }

    private fun injectInProcess(event: android.view.InputEvent): Boolean {
        val mgr = cachedInputManager ?: return false
        val method = cachedInjectMethod ?: return false
        return try {
            method.invoke(mgr, event, 0 /* ASYNC */)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "cached inject failed", t)
            false
        }
    }

    private val access get() = accessProvider()

    /**
     * Executor for key injection. Keeps the Shizuku IPC off the UI thread (so
     * buttons stay responsive) and serializes presses so a press's DOWN/UP
     * pair never interleaves with another's. Tasks are fast now (no blocking
     * sleep server-side — see injectKeyPress), so an unbounded queue won't
     * build a backlog under rapid tapping, and we never drop a press.
     */
    private val keyExecutor: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "portalpad-keys").apply { isDaemon = true }
        }

    /**
     * User-tunable input feel, updated from prefs by the foreground service.
     *  - cursorSpeed: multiplier on pointer-move deltas (1.0 = default)
     *  - scrollSpeed: multiplier on scroll deltas (1.0 = default)
     *  - invertScroll: flip scroll direction
     */
    @Volatile var cursorSpeed: Float = 1.0f
    @Volatile var scrollSpeed: Float = 1.0f
    @Volatile var invertScroll: Boolean = false

    /** Display the events are targeted at. Setting this resets the cursor. */
    var displayId: Int = 0
        set(value) {
            if (field == value) return
            val old = field
            // Never carry a held Remote > Swipe touch onto a replacement VD
            // after a reconnect or screen-off display recreation.
            if (remoteTouchDragActive) remoteTouchDragEnd()
            field = value
            // Previous display's hover registration is invalid for the new
            // display — next HOVER_MOVE will re-issue HOVER_ENTER on the new
            // display via the hoverActive flag check.
            hoverActive = false
            cachedInputMethodBounds = emptyList()
            lastInputMethodBoundsAt = 0L
            _cursorOverNativeCaptionHandle.value = false
            _nativeCaptionDragActive.value = false
            Log.d(TAG, "displayId change $old → $value")
            refreshDisplayMetrics()
            updateEffectiveCursorVisibility()
            syncHardwareMousePointerPreference()
            if (blackPointerModeActive && old == 0) {
                scheduleHoverInject(cursorX, cursorY)
            }
            runCatching { repinImePolicy(aggressive = false) }
        }

    /** True when injecting into a trusted VirtualDisplay wrap; false when we fell
     *  back to the raw physical display (trusted VD couldn't be created). On the
     *  raw display, Shizuku touches often don't land in foreign apps, so taps are
     *  routed through the accessibility framework instead. Set by the foreground
     *  service alongside [displayId]. */
    @Volatile var usingVd: Boolean = false

    /** True when falling back to a physical display (!usingVd) that is currently
     *  mirroring Display.DEFAULT_DISPLAY (e.g., Pixel mirroring mode). Events are
     *  scaled onto Display.DEFAULT_DISPLAY (0) so clicks reach app windows. */
    @Volatile var mirrorTargetDisplayZero: Boolean = false

    /** Whether the active injection backend — Shizuku OR root shell — can deliver
     *  input right now. When it can't, gestures fall back to a11y. (Previously
     *  this checked only the Shizuku backend, which wrongly forced a11y on root
     *  even though root injects fine.) */
    private fun activeBackendReady(): Boolean =
        runCatching { PortalPadApp.instance.clickBackend.isReady }.getOrDefault(false)

    /** Route this gesture through the accessibility framework: external display,
     *  and either untrusted (no VD) or the active backend unavailable, and the
     *  a11y service is connected to dispatch. Mirrors the tap/long-press gate.
     *
     *  When [mirrorTargetDisplayZero] is true and the privileged backend is ready,
     *  prefer injecting directly onto Display.DEFAULT_DISPLAY instead of sending
     *  a11y gestures to a mirrored secondary display id that owns no app windows.
     *
     *  [op] only labels the LANE log — it does not affect the decision. */
    private fun useA11y(op: String = "gesture"): Boolean {
        val backendReady = activeBackendReady()
        val preferA11y = displayId != 0 &&
            !(mirrorTargetDisplayZero && backendReady) &&
            (!usingVd || !backendReady)
        val a11y = preferA11y && PortalPadAccessibilityService.instance != null
        logLane(op, a11y)
        return a11y
    }

    /** LANE DIAG: one line per gesture family whenever its delivery lane
     *  CHANGES (shizuku ↔ a11y), so a capture shows which path the trackpad is
     *  really using without spamming a line per scroll sample. */
    private val laneState = java.util.concurrent.ConcurrentHashMap<String, String>()
    private fun logLane(op: String, a11y: Boolean) {
        val lane = if (a11y) "a11y" else "shizuku"
        if (laneState.put(op, lane) != lane) {
            Log.w(
                TAG,
                "LANE $op → $lane (disp=$displayId usingVd=$usingVd " +
                    "backendReady=${activeBackendReady()} " +
                    "a11yBound=${PortalPadAccessibilityService.instance != null})",
            )
        }
    }

    // One-shot a11y drag: record the start point; cursor tracks through dragMove;
    // dispatch a single start→end swipe on dragEnd.
    private var a11yDragActive = false
    private var a11yDragStartX = 0f
    private var a11yDragStartY = 0f
    // Caption/handle MOVE via shell setBounds instead of a faked touch-drag on the pill:
    // the injected touch only moves the window on long sustained strokes (Samsung ignores
    // short ones), so we move the window directly. Captured at dragStart; each dragMove
    // resizes the window to startBounds + cursor delta (throttled, on the inject thread).
    @Volatile private var captionMoveActive = false
    @Volatile private var moveTaskId = -1
    @Volatile private var moveStartLeft = 0
    @Volatile private var moveStartTop = 0
    @Volatile private var moveW = 0
    @Volatile private var moveH = 0
    @Volatile private var moveCursorStartX = 0f
    @Volatile private var moveCursorStartY = 0f
    @Volatile private var lastMoveResizeAt = 0L
    // Compatibility edge-resize path. Pixel's PortalPad backdrop is a normal
    // fullscreen activity behind freeform windows, so injecting a native resize
    // touch on the OUTSIDE pixel focuses that backdrop and moves the app TO_BACK.
    // Repeated programmatic resizes also create one WM transition per command,
    // producing ghosted surfaces and a cursor that outruns the real edge. The
    // compatibility path therefore moves a lightweight outline during the hold
    // and applies the real task bounds once on release. Pixel opts in today; the
    // overlay/service plumbing is device-neutral for future OEM fallbacks.
    @Volatile private var pixelEdgeResizeActive = false
    @Volatile private var edgeResizeTaskId = -1
    @Volatile private var edgeResizeStartLeft = 0
    @Volatile private var edgeResizeStartTop = 0
    @Volatile private var edgeResizeStartRight = 0
    @Volatile private var edgeResizeStartBottom = 0
    @Volatile private var edgeResizeCursorStartX = 0f
    @Volatile private var edgeResizeCursorStartY = 0f
    @Volatile private var edgeResizeLeft = false
    @Volatile private var edgeResizeRight = false
    @Volatile private var edgeResizeTop = false
    @Volatile private var edgeResizeBottom = false
    @Volatile private var edgeResizePreviewBounds: com.portalpad.app.data.WindowBounds? = null
    // One-shot a11y pinch: accumulate scale; dispatch a 2-stroke pinch on commit.
    private var a11yPinchActive = false
    private var a11yPinchScale = 1f

    // ─── Resize-cursor hover feedback (desktop-windows only) ──────────────────
    /** Pushed by the foreground service from the DESKTOP_MODE_ENABLED pref. When
     *  off, the resize-cursor feature is fully dormant (cursor stays an arrow). */
    @Volatile var desktopModeEnabled: Boolean = false
    private val _cursorType = MutableStateFlow(CursorType.ARROW)
    val cursorType: StateFlow<CursorType> = _cursorType.asStateFlow()

    /** One widget's on-screen rect (display px) while the widget overlay is in
     *  EDIT mode, with which axes its provider allows resizing. Published by
     *  the overlay (set on edit-enter, cleared on edit-exit/dismiss) so the
     *  resize-cursor scan can show the same glyphs freeform windows get. */
    data class WidgetEditRect(
        val rect: android.graphics.Rect,
        val hResize: Boolean,
        val vResize: Boolean,
    )

    /** Edit-mode widget rects — empty when the overlay isn't editing. Volatile:
     *  written from the overlay's UI thread, read on the cursor-move path. */
    @Volatile var widgetEditRects: List<WidgetEditRect> = emptyList()
    private val _onCaption = MutableStateFlow(false)
    /** True when the cursor is over a freeform window's top caption strip (below
     *  the top-resize zone, above the content). Caption grabs use the same quick
     *  press-hold engage as the resize edges but move the window 1:1 (no lock). */
    val cursorOnCaption: StateFlow<Boolean> = _onCaption.asStateFlow()
    private val _cursorOverNativeCaptionHandle = MutableStateFlow(false)
    /**
     * True only while the pointer is inside an Android/SystemUI caption-handle
     * node. This intentionally ignores PortalPad's own top bar even when the two
     * overlap, allowing that bar to yield before a press reaches the native
     * green mini bar or expanded caption strip underneath.
     */
    val cursorOverNativeCaptionHandle: StateFlow<Boolean> =
        _cursorOverNativeCaptionHandle.asStateFlow()
    private val _nativeCaptionDragActive = MutableStateFlow(false)
    /** True for the lifetime of PortalPad's shell-backed native caption move. */
    val nativeCaptionDragActive: StateFlow<Boolean> =
        _nativeCaptionDragActive.asStateFlow()
    // Throttled snapshot of freeform windows — listTasks() hits the task system,
    // so we never call it per-move; geometry runs against this cache.
    @Volatile private var cachedResizeTasks: List<RunningTask> = emptyList()
    // Live collapsed-caption handle rects (screen coords), refreshed alongside the
    // resize-task cache on the scan thread. Over one of these the cursor is a MOVE
    // zone, not a resize edge.
    @Volatile private var cachedHandles: List<CaptionHandle> = emptyList()
    // Tap-only caption controls (window buttons + any open handle menu) to keep drag off.
    @Volatile private var cachedButtonRects: List<android.graphics.Rect> = emptyList()
    /**
     * Drag strip of the external-display keyboard, in screen coords, or null when
     * that keyboard is hidden. Published by the overlay itself on show, move and
     * dismiss.
     *
     * The strip doubles as the keyboard's title bar — buttons on it, draggable
     * between them — and a MOVE cursor is the strongest available signal that it
     * can be grabbed, because it appears exactly when the pointer is somewhere
     * draggable. Checked before window geometry: the keyboard floats above every
     * app window, so whatever is underneath must not win.
     */
    @Volatile var extKeyboardDragRect: android.graphics.Rect? = null

    /**
     * FULL bounds of the external-display keyboard, or null when hidden.
     *
     * The keyboard floats above every app window, so while the pointer is over it
     * NOTHING underneath may decide the cursor. Publishing only the drag strip was
     * not enough: over the rest of the keys the resolver kept reading the window
     * beneath and drew resize arrows near its edges — a resize cursor for a window
     * the user cannot even see at that point, and one that would have engaged a
     * real resize on click.
     */
    @Volatile var extKeyboardBounds: android.graphics.Rect? = null

    /** True while the keyboard's grab bar is being held. Forces MOVE for the whole
     *  gesture: the bar travels with the window, so the pointer leaves its rect
     *  instantly and the cursor would otherwise flicker back to an arrow the
     *  moment the drag started. */
    @Volatile var extKeyboardDragging: Boolean = false
    // When ANY clickable (collapsed-pill) handle was last seen. While this memory is
    // fresh, an empty handle list means "the pill's node transiently vanished"
    // (SystemUI rebuilds the caption decor for a few seconds after pill
    // interactions), NOT "this window has no caption" — so the geometric
    // caption-band fallback must stay OFF. Field bug: the fallback turned the top
    // 52px of a collapsed-pill window into a MOVE zone during those windows,
    // letting drags engage beside/below the pill.
    @Volatile private var lastClickableHandleSeenAt = 0L
    // TRUE while the pill's popup menu is open (mirrored from the a11y scan).
    // The pill is TAP-ONLY then: no drag may engage from it, because the next
    // press there is nearly always "move the cursor to a menu item" (field
    // log: five for five unintended window drags, all from a pill press with
    // the menu open, an aiming pause, then movement toward the item).
    @Volatile private var cachedMenuOpen = false
    // Visible TYPE_INPUT_METHOD rectangles on the external display. A system
    // keyboard occludes the app below it, so cursor and press-time window-grab
    // logic must use this same topmost-region model.
    @Volatile private var cachedInputMethodBounds: List<android.graphics.Rect> = emptyList()
    @Volatile private var lastInputMethodBoundsAt = 0L
    // When the handle rects last came back NON-empty. Used to hold the last good
    // rects across a transient empty (the pill menu opening/closing briefly drops
    // the caption_handle node) so the MOVE zone does not flicker to a resize edge.
    @Volatile private var lastHandleRectsAt = 0L
    @Volatile private var lastHandleCursorLog = 0L
    @Volatile private var lastEditRectLog = 0L
    @Volatile private var lastDragDiag = 0L
    @Volatile private var lastDragRateDiag = 0L
    @Volatile private var captionMoveEvents = 0L
    @Volatile private var lastDragRateCount = 0L
    // DIAG-DRAGPOS (caption drags, ~10 Hz): integrated drag TARGET vs the
    // COMMANDED window origin, plus raw-input stats between samples. The
    // rendered cursor is deliberately glued to the most recent command;
    // dOff measures how far the live target has advanced beyond it. It returns
    // near zero whenever the next ~11 Hz command catches up.
    @Volatile private var lastDragPosDiag = 0L
    @Volatile private var dragRawSumX = 0f
    @Volatile private var dragRawSumY = 0f
    @Volatile private var dragMaxStep = 0f
    @Volatile private var dragGrabOffX = 0f
    @Volatile private var dragGrabOffY = 0f
    @Volatile private var lastCmdLeft = 0
    @Volatile private var lastCmdTop = 0
    @Volatile private var lastRawDragDiag = 0L
    @Volatile private var lastResizeTaskCacheAt = 0L
    @Volatile private var resizeScanInFlight = false

    /** Dedicated thread for the resize-hover window scan. `listTasks` is a
     *  full shell round-trip (`am stack list` through the privileged backend,
     *  20-100ms) — it previously ran SYNCHRONOUSLY inside [pointerMove] every
     *  time the 250ms cache expired, i.e. on the thread delivering touch
     *  events, hitching the cursor 4x/sec in desktop mode. Not the inject
     *  executor: parking a shell call there would delay queued hover/click
     *  injections behind it. */
    private val resizeScanExecutor =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "portalpad-resize-scan").apply { isDaemon = true }
        }

    private fun refreshInputMethodBounds(force: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastInputMethodBoundsAt < IME_BOUNDS_CACHE_TTL_MS) return
        lastInputMethodBoundsAt = now
        val fresh = runCatching {
            PortalPadAccessibilityService.instance?.inputMethodBounds(displayId)
        }.getOrNull().orEmpty()
        if (fresh != cachedInputMethodBounds) {
            cachedInputMethodBounds = fresh
            Log.i(
                TAG,
                "DIAG-IME-OCCLUSION disp=$displayId bounds=" +
                    fresh.joinToString(prefix = "[", postfix = "]") { it.toShortString() },
            )
        }
    }

    private fun cursorOccludedByInputMethod(refresh: Boolean = false): Boolean {
        refreshInputMethodBounds(force = refresh)
        val x = cursorX.toInt()
        val y = cursorY.toInt()
        return cachedInputMethodBounds.any { it.contains(x, y) }
    }

    /** On cursor move, if it's hovering a resizable freeform window's edge/corner,
     *  switch the cursor to the matching resize glyph. Gated so it's never a dead
     *  affordance: desktop mode on, a resize drag can actually land (trusted VD +
     *  ready backend), not over the dock, and the window isn't display-filling
     *  (maximized). Cheap rectangle math against a throttled task cache. */
    /** DIAG (#2): string sampled at trackpad-press time so the caption-move vs resize
     *  engage decision can be correlated with the actual cursor state and a FRESH
     *  onHandle re-test at the current cursor position (same outsets as the live hit
     *  test). Read by TrackpadActivity's pressDiag() override. */
    fun handlePressDiag(): String {
        val x = cursorX
        val y = cursorY
        val imeOccluded = cursorOccludedByInputMethod(refresh = true)
        val handles = cachedHandles
        val onHandleNow = handles.any { h ->
            h.clickable &&
                x >= h.rect.left - PILL_OUT && x <= h.rect.right + PILL_OUT &&
                y >= h.rect.top - PILL_OUT && y <= h.rect.bottom
        }
        return "type=${_cursorType.value} imeOccluded=$imeOccluded onCaptionFlow=${_onCaption.value} " +
            "onHandleNow=$onHandleNow cur=${x.toInt()},${y.toInt()} rects=${handles.size}"
    }

    /** Authoritative press-time onHandle test (see TrackpadSurface.pressOnHandleNow).
     *  Re-tests the live cursor position against the handle rects, querying ONE fresh
     *  set if the cache was momentarily empty — fixes both the stale-flow grab loss and
     *  the empty-cache fall-through to resize. */
    fun pressOnHandleNow(): Boolean {
        if (cursorOccludedByInputMethod(refresh = true)) return false
        // Pill is TAP-ONLY while its menu is open — the next press there is
        // "move the cursor to a menu item", not "drag the window" (field log:
        // five of five unintended drags were exactly this).
        if (cachedMenuOpen) return false
        var handles = cachedHandles
        if (handles.isEmpty()) {
            handles = runCatching {
                PortalPadAccessibilityService.instance?.captionHandles()
            }.getOrNull().orEmpty()
            if (handles.isNotEmpty()) {
                cachedHandles = handles
                lastHandleRectsAt = SystemClock.uptimeMillis()
                if (handles.any { it.clickable }) {
                    lastClickableHandleSeenAt = SystemClock.uptimeMillis()
                }
            }
        }
        val x = cursorX
        val y = cursorY
        return handles.any { h ->
            // Only the clickable COLLAPSED pill forces a caption-move; the non-clickable
            // expanded caption strip must not, or its top edge could never resize.
            // Vertically EXACT (no PILL_OUT above/below), matching the region model.
            h.clickable &&
                x >= h.rect.left - PILL_OUT && x <= h.rect.right + PILL_OUT &&
                y >= h.rect.top && y <= h.rect.bottom
        }
    }

    /** Press-time test: is the cursor on a caption tap-only control (window
     *  buttons / open pill menu)? The REGION model already keeps our quick
     *  caption grab off these, but the generic touch-and-hold drag path
     *  injects a REAL touch there — and the ROM reads a touch drag starting
     *  on caption chrome as a window move, buttons included (field: windows
     *  dragged from the caption's far-right icons). The gesture layer uses
     *  this to refuse ANY drag engage on the buttons: taps only. */
    fun pressOnButtonNow(): Boolean {
        if (cursorOccludedByInputMethod(refresh = true)) return false
        val x = cursorX
        val y = cursorY
        return PortalPadForegroundService.cursorOverTopWindowBarSurface(x, y) ||
            cachedButtonRects.any { r ->
                x >= r.left && x <= r.right && y >= r.top && y <= r.bottom
            }
    }

    /** Synchronous cached hit-test used by top-surface ownership arbitration. */
    fun isNativeCaptionHandleAt(x: Float, y: Float): Boolean =
        cachedHandles.any { handle ->
            x >= handle.rect.left && x <= handle.rect.right &&
                y >= handle.rect.top && y <= handle.rect.bottom
        }

    /** True when PortalPad's dock layer, rather than an Android app/backdrop,
     * owns this coordinate. The measured band closes the brief Compose-state
     * race on entry; modal/folder state covers the dock's expanded windows. */
    private fun isPortalPadDockSurfaceAt(x: Float, y: Float): Boolean {
        if (dockModalOpen || folderWindowsOpen > 0 || cursorOverDock) return true
        val bounds = runCatching {
            PortalPadApp.instance.dockBandBounds.value
        }.getOrNull() ?: return false
        return bounds.size >= 4 &&
            x >= bounds[0] && x < bounds[2] &&
            y >= bounds[1] && y < bounds[3]
    }

    private fun updateResizeCursor() {
        // The native keyboard is the topmost interactive surface. Do this
        // before widget/freeform geometry so no covered window can leak a
        // resize or move cursor through it.
        if (cursorOccludedByInputMethod()) {
            if (_cursorOverNativeCaptionHandle.value) {
                _cursorOverNativeCaptionHandle.value = false
            }
            if (_cursorType.value != CursorType.ARROW) _cursorType.value = CursorType.ARROW
            if (_onCaption.value) _onCaption.value = false
            return
        }
        // Widget-overlay EDIT mode: the layer sits above every window, so its
        // published rects win over freeform task edges — and they're deliberately
        // NOT gated on desktop mode (the layer works regardless of it).
        val editRects = widgetEditRects
        if (editRects.isNotEmpty()) {
            if (_cursorOverNativeCaptionHandle.value) {
                _cursorOverNativeCaptionHandle.value = false
            }
            val t = editRects.firstOrNull { er ->
                val bb = er.rect
                cursorX >= bb.left - WIDGET_EDGE_OUT && cursorX <= bb.right + WIDGET_EDGE_OUT &&
                    cursorY >= bb.top - WIDGET_EDGE_OUT && cursorY <= bb.bottom + WIDGET_EDGE_OUT
            }?.let { er ->
                val bb = er.rect
                // Three honest zones (field spec, all edit-mode only since these
                // rects only exist then): RESIZE along the full borders — which
                // now ARE whole-edge drag targets, not just the dots — reaching
                // WIDGET_EDGE_OUT outward but only WIDGET_EDGE_IN inward, so the
                // glyph can never sit on the widget's face (the old ±30 dot
                // boxes swallowed a 1x1's whole interior); the top-bar GRAB
                // cursor across the interior, inset past the edge reach,
                // because the interior really does drag the widget; ARROW
                // everywhere else. E/W checked first so corners read horizontal,
                // matching the strips' touch priority.
                val onE = cursorX >= bb.right - WIDGET_EDGE_IN && cursorX <= bb.right + WIDGET_EDGE_OUT &&
                    cursorY >= bb.top - WIDGET_EDGE_OUT && cursorY <= bb.bottom + WIDGET_EDGE_OUT
                val onW = cursorX >= bb.left - WIDGET_EDGE_OUT && cursorX <= bb.left + WIDGET_EDGE_IN &&
                    cursorY >= bb.top - WIDGET_EDGE_OUT && cursorY <= bb.bottom + WIDGET_EDGE_OUT
                val onN = cursorY >= bb.top - WIDGET_EDGE_OUT && cursorY <= bb.top + WIDGET_EDGE_IN &&
                    cursorX >= bb.left - WIDGET_EDGE_OUT && cursorX <= bb.right + WIDGET_EDGE_OUT
                val onS = cursorY >= bb.bottom - WIDGET_EDGE_IN && cursorY <= bb.bottom + WIDGET_EDGE_OUT &&
                    cursorX >= bb.left - WIDGET_EDGE_OUT && cursorX <= bb.right + WIDGET_EDGE_OUT
                val inside = cursorX >= bb.left + WIDGET_MOVE_INSET && cursorX <= bb.right - WIDGET_MOVE_INSET &&
                    cursorY >= bb.top + WIDGET_MOVE_INSET && cursorY <= bb.bottom - WIDGET_MOVE_INSET
                val result = when {
                    onE || onW -> CursorType.RESIZE_H
                    onN || onS -> CursorType.RESIZE_V
                    inside -> CursorType.MOVE
                    else -> CursorType.ARROW
                }
                val wnow = SystemClock.uptimeMillis()
                if (wnow - lastEditRectLog > 1000) {
                    lastEditRectLog = wnow
                    android.util.Log.d(
                        TAG,
                        "DIAG-EDITCURSOR cursor=(${cursorX.toInt()},${cursorY.toInt()}) " +
                            "rect=[${bb.left},${bb.top} ${bb.width()}x${bb.height()}] " +
                            "onE=$onE onW=$onW onN=$onN onS=$onS inside=$inside → $result",
                    )
                }
                result
            } ?: CursorType.ARROW
            if (_cursorType.value != t) _cursorType.value = t
            if (_onCaption.value) _onCaption.value = false
            return
        }
        // Widget overlay open (non-edit): the modal layer covers every window,
        // but the freeform task cache below still holds THEIR rects — the
        // resize/caption cursor was leaking through onto widgets sitting over a
        // hidden window's edge (field). Windows aren't interactable behind the
        // layer, so the window cursor model goes fully dormant.
        if (runCatching { PortalPadApp.instance.widgetOverlayOpen.value }.getOrDefault(false)) {
            if (_cursorOverNativeCaptionHandle.value) {
                _cursorOverNativeCaptionHandle.value = false
            }
            if (_cursorType.value != CursorType.ARROW) _cursorType.value = CursorType.ARROW
            if (_onCaption.value) _onCaption.value = false
            return
        }
        if (
            !desktopModeEnabled ||
            !usingVd ||
            !activeBackendReady() ||
            isPortalPadDockSurfaceAt(cursorX, cursorY)
        ) {
            if (_cursorOverNativeCaptionHandle.value) {
                _cursorOverNativeCaptionHandle.value = false
            }
            if (_cursorType.value != CursorType.ARROW) _cursorType.value = CursorType.ARROW
            if (_onCaption.value) _onCaption.value = false
            return
        }
        // Refresh the window-bounds cache ASYNCHRONOUSLY (single-flight): keep
        // hit-testing against the stale bounds until fresh ones land — 250ms
        // staleness was already the accepted design, so an extra ~50ms of
        // shell latency is invisible, whereas paying it on this thread was
        // not. Monotonic clock: wall time jumps (NTP/user) broke the TTL.
        val now = SystemClock.uptimeMillis()
        if (now - lastResizeTaskCacheAt > RESIZE_CACHE_TTL_MS && !resizeScanInFlight) {
            resizeScanInFlight = true
            val scanDisplayId = displayId
            runCatching {
                resizeScanExecutor.execute {
                    try {
                        cachedResizeTasks = runCatching {
                            PortalPadApp.instance.freeform.listTasks(scanDisplayId)
                        }.getOrDefault(emptyList())
                        val freshHandles = runCatching {
                            PortalPadAccessibilityService.instance?.captionHandles()
                        }.getOrNull() ?: emptyList()
                        cachedButtonRects = runCatching {
                            PortalPadAccessibilityService.instance?.captionButtonRects()
                        }.getOrNull() ?: emptyList()
                        // captionButtonRects just stamped menu visibility; mirror it
                        // here so the region model and press tests read one flag.
                        cachedMenuOpen = runCatching {
                            PortalPadAccessibilityService.instance?.handleMenuVisibleNow
                        }.getOrNull() ?: false
                        if (freshHandles.isNotEmpty()) {
                            cachedHandles = freshHandles
                            lastHandleRectsAt = SystemClock.uptimeMillis()
                            if (freshHandles.any { it.clickable }) {
                                lastClickableHandleSeenAt = SystemClock.uptimeMillis()
                            }
                        } else if (SystemClock.uptimeMillis() - lastHandleRectsAt > HANDLE_RECTS_GRACE_MS) {
                            // Grace expired: the handle is genuinely gone — expanded to
                            // the caption bar, or the window closed. Safe to drop the zone.
                            cachedHandles = emptyList()
                        }
                        // else: transient empty — the pill menu opening/closing momentarily
                        // drops the caption_handle node, so hold the last rects and the MOVE
                        // zone does not flicker to a resize edge under the cursor.
                        lastResizeTaskCacheAt = SystemClock.uptimeMillis()
                    } finally {
                        resizeScanInFlight = false
                    }
                }
            }.onFailure { resizeScanInFlight = false }
        }
        // ===== Window-top region model — the single source of truth for cursor + drag. =====
        // Resolve the window under the cursor (thin edge margins so the cursor sits on the
        // visible border, not deep in chrome).
        val handles = cachedHandles
        val overNativeCaptionHandle = handles.any { handle ->
            cursorX >= handle.rect.left && cursorX <= handle.rect.right &&
                cursorY >= handle.rect.top && cursorY <= handle.rect.bottom
        }
        if (_cursorOverNativeCaptionHandle.value != overNativeCaptionHandle) {
            _cursorOverNativeCaptionHandle.value = overNativeCaptionHandle
        }
        val b = cachedResizeTasks.firstNotNullOfOrNull { t ->
            t.bounds?.takeIf { bb ->
                cursorX >= bb.left - RESIZE_EDGE_OUT && cursorX <= bb.right + RESIZE_EDGE_OUT &&
                    cursorY >= bb.top - RESIZE_TOP_OUT && cursorY <= bb.bottom + RESIZE_EDGE_OUT
            }
        }
        // The caption_handle for the window under the cursor. Its CLICKABLE flag classifies
        // it (width-independent, so resizing / resolution can't fool it): clickable ⇒ the
        // COLLAPSED mini pill; non-clickable ⇒ the EXPANDED caption drag strip.
        val capH = handles.firstOrNull { h ->
            b == null || (h.rect.centerX() >= b.left && h.rect.centerX() <= b.right && h.rect.top <= b.top + 40)
        }
        val collapsed = capH != null && capH.clickable
        val expandedCap = capH != null && !capH.clickable
        // On a tap-only caption control (window buttons / open handle menu)? Never drag there
        // — keyed off the controls' real accessibility bounds, so no window sizing exposes them.
        val onButton =
            PortalPadForegroundService.cursorOverTopWindowBarSurface(cursorX, cursorY) ||
                cachedButtonRects.any { r ->
                    cursorX >= r.left && cursorX <= r.right &&
                        cursorY >= r.top && cursorY <= r.bottom
                }
        // On the mini green pill? TIGHT: +PILL_OUT sides/top, NOTHING below (the pill menu
        // opens below and must stay tappable). Clickable pill only — never the wide strip.
        // Pill MOVE zone: tap-only while its menu is open (!cachedMenuOpen), and
        // vertically EXACT — no tolerance above or below the bar (field request:
        // the grab zone stays within the mini bar). Horizontal PILL_OUT kept as
        // an aiming aid; no sideways complaints and the pill is narrow.
        val onPill = collapsed && capH != null && !onButton && !cachedMenuOpen &&
            cursorX >= capH.rect.left - PILL_OUT && cursorX <= capH.rect.right + PILL_OUT &&
            cursorY >= capH.rect.top && cursorY <= capH.rect.bottom
        val hnow = SystemClock.uptimeMillis()
        val maximized = b != null && b.left <= 2 && b.top <= 2 &&
            b.right >= displayWidth - 2 && b.bottom >= displayHeight - 2
        var type = CursorType.ARROW
        var onCap = false
        val kbDrag = extKeyboardDragRect
        val kbAll = extKeyboardBounds
        val overKeyboard = kbAll != null &&
            cursorX >= kbAll.left && cursorX <= kbAll.right &&
            cursorY >= kbAll.top && cursorY <= kbAll.bottom
        if (extKeyboardDragging) {
            // Held: stay MOVE wherever the pointer has got to.
            type = CursorType.MOVE
        } else if (kbDrag != null &&
            cursorX >= kbDrag.left && cursorX <= kbDrag.right &&
            cursorY >= kbDrag.top && cursorY <= kbDrag.bottom
        ) {
            // Drag strip: MOVE, and it wins over anything beneath.
            type = CursorType.MOVE
        } else if (overKeyboard) {
            // Anywhere else on the keyboard: plain ARROW. Skipping the window
            // geometry below is the point — it would otherwise resolve resize
            // edges from whatever window happens to sit under the keys, and
            // onCap stays false so a click can't engage a caption drag either.
            type = CursorType.ARROW
        } else if (onPill) {
            // Mini green bar → MOVE + drag.
            type = CursorType.MOVE
            onCap = true
        } else if (b != null && !maximized) {
            val nearLeft = cursorX >= b.left - RESIZE_EDGE_OUT && cursorX <= b.left + RESIZE_EDGE_IN
            val nearRight = cursorX >= b.right - RESIZE_EDGE_IN && cursorX <= b.right + RESIZE_EDGE_OUT
            val nearTop = cursorY >= b.top - RESIZE_TOP_OUT && cursorY <= b.top + RESIZE_TOP_IN
            val nearBottom = cursorY >= b.bottom - RESIZE_EDGE_IN && cursorY <= b.bottom + RESIZE_EDGE_OUT
            // Caption DRAG body, MINUS the top resize strip (so the very top edge resizes) and
            // MINUS the tap-only buttons. Expanded: the a11y caption_handle strip. No handle at
            // all: geometric top band minus the button zone — but ONLY when no collapsed pill
            // was seen recently, or a transiently-vanished pill node would hand its whole top
            // band to MOVE (field: drags engaged beside/below the pill for the few seconds
            // SystemUI rebuilds the decor). Collapsed: none (pill is onPill).
            val inCaptionBody = !nearTop && !nearBottom && !onButton && when {
                expandedCap && capH != null ->
                    cursorX >= capH.rect.left && cursorX <= capH.rect.right &&
                        cursorY >= capH.rect.top && cursorY <= capH.rect.bottom
                capH == null &&
                    SystemClock.uptimeMillis() - lastClickableHandleSeenAt > COLLAPSED_HANDLE_MEMORY_MS ->
                    cursorX >= b.left && cursorX <= b.right - CAPTION_BUTTON_ZONE &&
                        cursorY >= b.top && cursorY <= b.top + CAPTION_STRIP_H
                else -> false
            }
            type = when {
                (nearLeft && nearTop) || (nearRight && nearBottom) -> CursorType.RESIZE_NWSE
                (nearRight && nearTop) || (nearLeft && nearBottom) -> CursorType.RESIZE_NESW
                nearLeft || nearRight -> CursorType.RESIZE_H
                nearTop || nearBottom -> CursorType.RESIZE_V
                inCaptionBody -> CursorType.MOVE
                else -> CursorType.ARROW
            }
            onCap = type == CursorType.MOVE
        }
        // else: no window under the cursor, or a maximized window → ARROW, no drag.
        // Region DIAG now logs AFTER the final glyph is decided — the old
        // pre-decision line could never show WHICH cursor the zones resolved
        // to (field: "no drag cursor over the expanded caption bar" was
        // unverifiable because type was missing).
        if (hnow - lastHandleCursorLog > 1500) {
            lastHandleCursorLog = hnow
            android.util.Log.d(
                "PortalPadSleep",
                "region: cursor=(${cursorX.toInt()},${cursorY.toInt()}) type=$type onCap=$onCap onPill=$onPill onButton=$onButton capClick=${capH?.clickable} winTop=${b?.top} winBot=${b?.bottom} handles=${handles.map { it.rect.toShortString() }}",
            )
        }
        if (_cursorType.value != type) _cursorType.value = type
        if (_onCaption.value != onCap) _onCaption.value = onCap
    }

    /**
     * When true, pressKey() skips its trailing repinImePolicy() call.
     *
     * The "Type to external display" relay (KeyboardRelayActivity) keeps the
     * PHONE keyboard up on display 0 and forwards each keystroke to the VD
     * via pressKey(). But pressKey() normally re-pins the IME policy after
     * every key — and that re-pin re-evaluates the IME across displays,
     * which yanks the phone keyboard serving the relay field (it collapses
     * after each keystroke even though the text gets through). While the
     * relay screen is open we set this flag so typing doesn't fight the
     * phone keyboard. The relay sets it true in onResume and false in
     * onPause.
     */
    @Volatile var suppressImeRepin: Boolean = false

    // One owner for every Relay -> native transition. The Relay window and the
    // returning Trackpad window detach/resume in either order on Samsung; route
    // restoration is committed only after (a) the dying Relay window is gone
    // and (b) Trackpad has cleared its old phone-side requested-visible state.
    // A serial rejects delayed callbacks from an older Relay instance.
    private val nativeImeSessionLock = Any()
    private var nativeImeSessionSerial = 0L
    @Volatile private var activeRelayImeSession = 0L
    private data class PendingNativeImeHandoff(
        val token: Long,
        val relaySession: Long,
        val restoreExternal: Boolean,
        var relayDetached: Boolean = false,
        var phoneHostCleared: Boolean = false,
    )
    private var pendingNativeImeHandoff: PendingNativeImeHandoff? = null

    fun beginRelayImeSession(): Long {
        val session = synchronized(nativeImeSessionLock) {
            nativeImeSessionSerial += 1L
            activeRelayImeSession = nativeImeSessionSerial
            pendingNativeImeHandoff = null
            activeRelayImeSession
        }
        setRelayImeMode(true)
        Log.i(TAG, "DIAG-IME-SESSION relay begin session=$session")
        return session
    }

    fun resumeRelayImeSession(session: Long): Boolean {
        val current = synchronized(nativeImeSessionLock) {
            activeRelayImeSession == session && pendingNativeImeHandoff == null
        }
        if (current) setRelayImeMode(true)
        else Log.i(TAG, "DIAG-IME-SESSION stale relay resume ignored session=$session")
        return current
    }

    fun pauseRelayImeSession(session: Long) {
        val current = synchronized(nativeImeSessionLock) {
            activeRelayImeSession == session && pendingNativeImeHandoff == null
        }
        if (current) setRelayImeMode(false)
    }

    fun isRelayImeSessionActive(session: Long): Boolean =
        synchronized(nativeImeSessionLock) {
            activeRelayImeSession == session && pendingNativeImeHandoff == null
        }

    fun beginRelayImeTeardown(session: Long, restoreExternal: Boolean): Long {
        var releaseRelayPolicy = false
        val token = synchronized(nativeImeSessionLock) {
            pendingNativeImeHandoff?.takeIf { it.relaySession == session }?.token
                ?: if (activeRelayImeSession == session) {
                    activeRelayImeSession = 0L
                    nativeImeSessionSerial += 1L
                    PendingNativeImeHandoff(
                        token = nativeImeSessionSerial,
                        relaySession = session,
                        restoreExternal = restoreExternal,
                    ).also {
                        pendingNativeImeHandoff = it
                        releaseRelayPolicy = true
                    }.token
                } else {
                    0L
                }
        }
        if (releaseRelayPolicy) setRelayImeMode(false)
        if (token != 0L) {
            Log.i(
                TAG,
                "DIAG-IME-SESSION relay teardown session=$session handoff=$token " +
                    "restoreExternal=$restoreExternal",
            )
        }
        return token
    }

    fun markRelayWindowDetached(handoffToken: Long) {
        if (handoffToken == 0L) return
        synchronized(nativeImeSessionLock) {
            pendingNativeImeHandoff?.takeIf { it.token == handoffToken }?.relayDetached = true
        }
        Log.i(TAG, "DIAG-IME-SESSION relay detached handoff=$handoffToken")
        completeNativeImeHandoffIfReady(handoffToken)
        // Lifecycle callbacks should provide both halves. This bounded fallback
        // prevents an Activity teardown edge case from stranding the selected
        // route; the serial still makes it impossible for an old Relay to win.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
            { completeNativeImeHandoffIfReady(handoffToken, allowMissingHost = true) },
            NATIVE_IME_HANDOFF_FALLBACK_MS,
        )
    }

    fun markPhoneImeHostClearedAfterRelay(): Long {
        val token = synchronized(nativeImeSessionLock) {
            pendingNativeImeHandoff?.also { it.phoneHostCleared = true }?.token ?: 0L
        }
        if (token != 0L) {
            Log.i(TAG, "DIAG-IME-SESSION phone host cleared handoff=$token")
            completeNativeImeHandoffIfReady(token)
        }
        return token
    }

    private fun completeNativeImeHandoffIfReady(
        handoffToken: Long,
        allowMissingHost: Boolean = false,
    ) {
        val routeExternal = synchronized(nativeImeSessionLock) {
            val pending = pendingNativeImeHandoff
            if (
                pending == null || pending.token != handoffToken || !pending.relayDetached ||
                (!pending.phoneHostCleared && !allowMissingHost)
            ) {
                null
            } else {
                pendingNativeImeHandoff = null
                pending.restoreExternal
            }
        } ?: return

        if (routeExternal) showImeOnExternal() else routeImeToPhone()
        Log.i(
            TAG,
            "DIAG-IME-SESSION native handoff committed token=$handoffToken " +
                "route=${if (routeExternal) "EXTERNAL" else "DIRECT"} " +
                "fallback=$allowMissingHost",
        )
    }

    private var displayWidth: Int = 1920
    private var displayHeight: Int = 1080
    // While TRUE the setters below keep updating the INTERNAL position (it
    // drives the caption shell-move target math) but stop publishing to the
    // rendered cursor — during a caption drag the visual cursor is GLUED to
    // the window instead: postCaptionMoveResize publishes grab-point-on-the-
    // commanded-bounds each step, so cursor and window move as one object on
    // one rhythm (field: the smooth cursor over the stepped window read as
    // the cursor slipping off the bar).
    @Volatile private var suppressCursorPublish = false
    private var cursorX: Float = 100f
        set(value) {
            field = value
            if (!suppressCursorPublish) _cursorPosition.value = Pair(value, cursorY)
        }
    private var cursorY: Float = 100f
        set(value) {
            field = value
            if (!suppressCursorPublish) _cursorPosition.value = Pair(cursorX, value)
        }
    // Last glued cursor point (grab point on the last COMMANDED bounds) — the
    // internal cursor snaps here at dragEnd so movement resumes from where the
    // user SEES the cursor, not from an internal position that kept going past
    // a clamp.
    @Volatile private var lastGlueX = 0f
    @Volatile private var lastGlueY = 0f
    private var downTime: Long = 0

    private val _cursorPosition = MutableStateFlow(Pair(100f, 100f))

    /** (cursorX, cursorY) updated on each pointer move. Observable by the cursor overlay. */
    val cursorPosition: StateFlow<Pair<Float, Float>> = _cursorPosition.asStateFlow()

    /**
     * The pointer's ABSOLUTE position on the injected display, read directly
     * rather than through [cursorPosition].
     *
     * Exists for the external keyboard's grab bar. That drag must be driven by a
     * reference the keyboard window CANNOT move, because window-local pointer
     * coordinates are not one: `updateViewLayout` is async, so the frame those
     * coordinates are measured against lags the position we last asked for, and
     * every reposition re-delivers the still-pressed pointer with new local
     * coordinates. The window's own motion becomes the input and the loop runs
     * away (extkb4.txt 17:28:19.0-19.8: 35 repositions, y 546→246→796, with ZERO
     * touch MOVE events delivered — a still finger, and the keyboard drove itself
     * off the display).
     *
     * Deliberately NOT the StateFlow: that one is gated by suppressCursorPublish
     * (glued-cursor caption drags), so it can hold a stale value exactly while a
     * drag is in flight. This reads the live field.
     */
    val cursorPointAbsolute: Pair<Float, Float> get() = Pair(cursorX, cursorY)

    // ─── Dock right-click (in-process) ──────────────────────────────────────
    // The dock is PortalPad's own overlay; an injected touch long-press reaches
    // it as a quick tap (it can't carry mouse-button state), so it launched
    // instead of opening the item menu. Instead, when the cursor is over the
    // visible dock, the dock sets [cursorOverDock] = true; rightClick() then
    // emits [dockRightClickTick] and SKIPS injection, and the dock tile under
    // the cursor opens its own menu in-process. The non-dock path is unchanged.
    @Volatile var cursorOverDock: Boolean = false
    /** True while the dock host is expanded for any PortalPad-owned modal
     *  (folder, item menu, reorder/remove, or minimized/open-windows menu).
     *  These surfaces cover Android windows and wallpaper, so Pixel's backdrop
     *  protection must never consume their injected taps. */
    @Volatile var dockModalOpen: Boolean = false
    private val _dockRightClickTick = kotlinx.coroutines.flow.MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val dockRightClickTick: kotlinx.coroutines.flow.SharedFlow<Long> = _dockRightClickTick.asSharedFlow()

    // Widget overlay: same in-process right-click routing as the dock. While the
    // overlay is showing, it sets [cursorOverWidgetOverlay] = true; rightClick()
    // then emits [widgetOverlayRightClickTick] and skips injection (an injected
    // long-press reaches this NOT_FOCUSABLE cursor-driven window as a plain tap,
    // never a hold). The overlay collects the tick to toggle its edit mode — so
    // the trackpad's press-and-hold (which drives rightClick) enters/exits edit.
    @Volatile var cursorOverWidgetOverlay: Boolean = false
    private val _widgetOverlayRightClickTick = kotlinx.coroutines.flow.MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val widgetOverlayRightClickTick: kotlinx.coroutines.flow.SharedFlow<Long> = _widgetOverlayRightClickTick.asSharedFlow()

    // Folder window: same in-process right-click routing as the dock. While
    // any folder popup is open (COUNTER - popups nest), press-and-hold routes
    // as a tick that the tile under the cursor collects to open its item menu
    // (SH: an injected long-press reached our own window as a plain tap and
    // just launched the item). Counter, not boolean: a sub-folder popup can be
    // composed while its parent is still disposing.
    @Volatile var folderWindowsOpen: Int = 0

    // ── Keyboard seek-button hold (SH: hold ◀◀/▶▶ = continuous seek). The
    // keyboard window itself can't see hold duration (injected presses land
    // there as instant taps), so the TRACKPAD's streaming hold drives it:
    // the probe publishes the buttons' display rects, the recognizer streams
    // hold start/end, and this repeats the 10s seek until release.
    @Volatile var kbSeekRewindRect: android.graphics.Rect? = null
    @Volatile var kbSeekFfRect: android.graphics.Rect? = null
    @Volatile private var kbSeekHoldDir = 0
    @Volatile private var kbSeekHoldStamp = 0L

    fun kbSeekHoldStart() {
        val x = cursorX.toInt()
        val y = cursorY.toInt()
        val dir = when {
            kbSeekRewindRect?.contains(x, y) == true -> -1
            kbSeekFfRect?.contains(x, y) == true -> 1
            else -> 0
        }
        if (dir == 0) return
        kbSeekHoldDir = dir
        kbSeekHoldStamp = android.os.SystemClock.uptimeMillis()
        val media = PortalPadApp.instance.media
        Thread {
            while (kbSeekHoldDir == dir) {
                runCatching { if (dir < 0) media.rewind() else media.fastForward() }
                kbSeekHoldStamp = android.os.SystemClock.uptimeMillis()
                Thread.sleep(500)
            }
        }.start()
    }

    fun kbSeekHoldEnd() {
        if (kbSeekHoldDir != 0) kbSeekHoldStamp = android.os.SystemClock.uptimeMillis()
        kbSeekHoldDir = 0
    }
    private val _folderRightClickTick = kotlinx.coroutines.flow.MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val folderRightClickTick: kotlinx.coroutines.flow.SharedFlow<Long> = _folderRightClickTick.asSharedFlow()

    // Dock reorder ("wiggle") mode state, hoisted here so it SURVIVES the dock
    // overlay recomposing/remounting when the dock config refreshes after a drop.
    // (Held as remember{} inside the dock, it reset to false on that remount and
    // ended reorder mode after the first move.)
    val dockReorderMode = androidx.compose.runtime.mutableStateOf(false)
    val dockReorderPickedUpId = androidx.compose.runtime.mutableStateOf<String?>(null)
    // Remove ("wiggle") mode: when active, top-level dock icons wiggle and a tap
    // removes that item (looping so the user can keep removing until Done). Held
    // here (not in the dock) for the same reason as reorder mode above.
    val dockRemoveMode = androidx.compose.runtime.mutableStateOf(false)

    // ─── Search query relay channel ─────────────────────────────────────────
    // The keyboard-relay activity (on the phone) writes the live search text
    // here as the user types; the on-display SearchOverlay observes it and
    // filters its list. This bypasses the unreliable "inject keys → focused
    // field on the external display" path: we already know the typed text in
    // our own process, so we share it directly instead of depending on
    // cross-display IME focus.
    private val _searchQuery = MutableStateFlow("")

    /** Live search text from the relay, observed by the on-display search overlay. */
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    /** Replace the live search query (relay → overlay). */
    fun setSearchQuery(text: String) { _searchQuery.value = text }

    /** A relayed Enter/submit signal (increment to notify observers). */
    private val _searchSubmit = MutableStateFlow(0)
    val searchSubmit: StateFlow<Int> = _searchSubmit.asStateFlow()
    fun submitSearch() { _searchSubmit.value += 1 }

    // ─── Click haptic (external-display dock / top-bar taps) ────────────────
    // A short tactile tick on each left-click so dock and top-bar presses feel
    // confirmed, matching the phone-side search haptics. Click-only: hover does
    // not route through leftClick(), so the cursor drifting over buttons never
    // buzzes.
    //
    // Global haptic strength in ms (0 = off), mirrored from the VIBRATION_MS
    // pref by a collector. The VibrationEffect and VibrationAttributes are
    // pre-built and cached so each click just fires the cached effect with NO
    // per-click allocation — this removes the construction latency that made
    // the buzz feel slightly delayed after a tap.
    @Volatile var vibrationMs: Int = 25
        set(value) {
            field = value
            // Rebuild the cached effect to match the new strength.
            cachedEffect = if (value > 0 &&
                android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
            ) {
                runCatching {
                    android.os.VibrationEffect.createOneShot(
                        value.toLong(),
                        android.os.VibrationEffect.DEFAULT_AMPLITUDE,
                    )
                }.getOrNull()
            } else null
        }
    // Dormant: the per-click/key aggressive IME re-pin. Default OFF (display-change
    // pinning still runs via repinImePolicy(aggressive = false)). Kept as a one-flag
    // revival point — no UI currently writes it.
    @Volatile var aggressiveImeRepin: Boolean = false
    @Volatile var imeOnExternalEnabled: Boolean = false
    // Mirrors the auto-open-relay pref. When true (and in phone-keyboard mode),
    // the glasses display is pinned to HIDE so the native keyboard never pops for
    // a glasses field — the relay is the sole on-phone keyboard. See repinImePolicy.
    @Volatile var autoOpenRelayEnabled: Boolean = false

    /** Mirrors PORTALPAD_KB_ON_DISPLAY. When true a field tap on the external
     *  display raises PortalPad's own keyboard there, and neither the typing page
     *  nor a native IME request is made. Read on the accessibility service's
     *  event path, hence @Volatile like its neighbours. */
    @Volatile var portalPadKbOnDisplayEnabled: Boolean = false

    /** Mirrors PORTALPAD_KB_FLOATING. When true a field tap raises PortalPad's
     *  keyboard FLOATING over the phone (keys-only overlay), and neither the
     *  typing page nor a native IME request is made. Read on the accessibility
     *  event path (Build 2 wires field-tap; Build 1 uses the button only). */
    @Volatile var portalPadKbFloatingEnabled: Boolean = false

    // Pre-built effect for the current strength (rebuilt only on change above).
    @Volatile private var cachedEffect: android.os.VibrationEffect? =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            runCatching {
                android.os.VibrationEffect.createOneShot(
                    25L, android.os.VibrationEffect.DEFAULT_AMPLITUDE,
                )
            }.getOrNull()
        } else null

    // Attributes never change — build once.
    private val touchAttrs: android.os.VibrationAttributes? by lazy {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            runCatching {
                android.os.VibrationAttributes.Builder()
                    .setUsage(android.os.VibrationAttributes.USAGE_TOUCH)
                    .build()
            }.getOrNull()
        } else null
    }

    private val vibrator: android.os.Vibrator? by lazy {
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                    as android.os.VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            }
        }.getOrNull()
    }

    private fun clickHaptic() = buzz(longPress = false)

    /**
     * Fire a preset-strength haptic tick. Public so the whole PortalPad UI
     * (trackpad bars, media controls, power button, dock/overlays via clicks)
     * can route through the SAME Off/Light/Medium/Strong preset instead of the
     * View-level system haptic. [longPress] gives a stronger/longer buzz for
     * long-press actions (≈2× the preset duration), preserving the tap-vs-hold
     * distinction. No-op when the preset is Off (vibrationMs <= 0).
     */
    fun buzz(longPress: Boolean = false) {
        val base = vibrationMs
        if (base <= 0) return
        val v = vibrator ?: return
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                // Common case (a click tick) fires the PRE-BUILT effect with no
                // per-call allocation — that construction was the avoidable latency.
                // Only the rarer long-press variant (2× duration) builds on demand.
                val effect = if (longPress) {
                    android.os.VibrationEffect.createOneShot(
                        (base * 2).toLong(), android.os.VibrationEffect.DEFAULT_AMPLITUDE,
                    )
                } else {
                    cachedEffect ?: android.os.VibrationEffect.createOneShot(
                        base.toLong(), android.os.VibrationEffect.DEFAULT_AMPLITUDE,
                    )
                }
                val attrs = touchAttrs
                if (attrs != null) v.vibrate(effect, attrs) else v.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(if (longPress) (base * 2).toLong() else base.toLong())
            }
        }
    }

    /**
     * Two short ticks for a hard control boundary (currently Gesture volume
     * at 0% / 100%). It uses the same configured haptic preset as [buzz], so
     * Off remains off and the user's chosen strength remains authoritative.
     */
    fun buzzBoundary() {
        val base = vibrationMs
        if (base <= 0) return
        val v = vibrator ?: return
        runCatching {
            val pattern = longArrayOf(0L, base.toLong(), 42L, base.toLong())
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val effect = android.os.VibrationEffect.createWaveform(pattern, -1)
                val attrs = touchAttrs
                if (attrs != null) v.vibrate(effect, attrs) else v.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(pattern, -1)
            }
        }
    }

    private val _baseCursorVisible = MutableStateFlow(true)
    private val _externalMouseHidingCursor = MutableStateFlow(false)
    private val _externalMouseConnected = MutableStateFlow(false)
    private val _externalMouseRemoteMode = MutableStateFlow(false)
    private val _cursorVisible = MutableStateFlow(true)
    private val cursorVisibilityHandler =
        android.os.Handler(android.os.Looper.getMainLooper())
    private val restoreCursorAfterExternalMouse = Runnable {
        if (_externalMouseHidingCursor.value) {
            _externalMouseHidingCursor.value = false
            updateEffectiveCursorVisibility()
        }
    }

    /** True when external mouse is connected, we're on an external display, and not in Remote mode. */
    val externalMouseConnected: StateFlow<Boolean> = _externalMouseConnected.asStateFlow()

    val blackPointerModeActive: Boolean
        get() = _externalMouseConnected.value && displayId != 0 && !_externalMouseRemoteMode.value

    @Volatile private var lastPushedHardwareMouseBackend: BoundShellBackend? = null
    @Volatile private var lastPushedHardwareMouseMode: Boolean? = null

    private fun updateEffectiveCursorVisibility() {
        _cursorVisible.value = _baseCursorVisible.value &&
            !_externalMouseHidingCursor.value &&
            !blackPointerModeActive
    }

    private fun syncHardwareMousePointerPreference() {
        val want = blackPointerModeActive
        val backend = runCatching { PortalPadApp.instance.activeBoundBackend }.getOrNull() ?: return
        if (!backend.isReady) {
            lastPushedHardwareMouseBackend = null
            lastPushedHardwareMouseMode = null
            return
        }
        if (lastPushedHardwareMouseBackend === backend && lastPushedHardwareMouseMode == want) return
        lastPushedHardwareMouseBackend = backend
        lastPushedHardwareMouseMode = want
        runCatching {
            injectExecutor.execute {
                runCatching { backend.setUseHardwareMousePointer(want) }
            }
        }
    }

    /**
     * Switch between unified black pointer mode (when a physical external mouse
     * is connected on an external display) and PortalPad's custom white cursor
     * (when disconnected).
     */
    fun updateExternalMouseConnected(connected: Boolean) {
        val wasBlackMode = blackPointerModeActive
        val changed = _externalMouseConnected.value != connected
        _externalMouseConnected.value = connected
        cursorVisibilityHandler.removeCallbacks(restoreCursorAfterExternalMouse)
        if (!connected) {
            _externalMouseHidingCursor.value = false
        }
        updateEffectiveCursorVisibility()
        syncHardwareMousePointerPreference()
        val nowBlackMode = blackPointerModeActive
        if (changed || wasBlackMode != nowBlackMode) {
            Log.i(
                TAG,
                "externalMouseConnected=$connected remote=${_externalMouseRemoteMode.value} " +
                    "disp=$displayId blackPointerMode=$nowBlackMode cursorVisible=${_cursorVisible.value}",
            )
        }
        if (nowBlackMode && !wasBlackMode) {
            scheduleHoverInject(cursorX, cursorY)
        } else if (!nowBlackMode && wasBlackMode) {
            dismissSystemPointer()
        }
    }

    /**
     * Remote mode uses exclusive evdev capture without moving a pointer, so
     * black-pointer unification pauses while Remote mode is selected.
     */
    fun setExternalMouseRemoteMode(remoteMode: Boolean) {
        if (_externalMouseRemoteMode.value == remoteMode) return
        val wasBlackMode = blackPointerModeActive
        _externalMouseRemoteMode.value = remoteMode
        updateEffectiveCursorVisibility()
        syncHardwareMousePointerPreference()
        val nowBlackMode = blackPointerModeActive
        Log.i(
            TAG,
            "externalMouseRemoteMode=$remoteMode blackPointerMode=$nowBlackMode " +
                "cursorVisible=${_cursorVisible.value}",
        )
        if (nowBlackMode && !wasBlackMode) {
            scheduleHoverInject(cursorX, cursorY)
        } else if (!nowBlackMode && wasBlackMode) {
            dismissSystemPointer()
        }
    }

    /**
     * Whether the cursor overlay should currently render. Controlled by:
     *  - The CursorOverlay observes this and short-circuits onDraw when false
     *  - TrackpadActivity sets this based on (mode == Media || hideInAppPref)
     *  - Service can also force it false if no display is attached
     *  - Unified black-pointer handoff when an external mouse is connected
     */
    val cursorVisible: StateFlow<Boolean> = _cursorVisible.asStateFlow()

    fun setCursorVisible(visible: Boolean) {
        _baseCursorVisible.value = visible
        updateEffectiveCursorVisibility()
    }

    /**
     * Hide PortalPad's white cursor while an external mouse drives the pointer.
     */
    fun notifyExternalMouseMoving(idleRestoreMs: Long = EXTERNAL_MOUSE_IDLE_RESTORE_MS) {
        cursorVisibilityHandler.removeCallbacks(restoreCursorAfterExternalMouse)
        if (!_externalMouseHidingCursor.value) {
            _externalMouseHidingCursor.value = true
            updateEffectiveCursorVisibility()
        }
        if (idleRestoreMs > 0L && !blackPointerModeActive) {
            cursorVisibilityHandler.postDelayed(
                restoreCursorAfterExternalMouse,
                idleRestoreMs.coerceAtLeast(150L),
            )
        }
    }

    /** Restore PortalPad's white cursor when Touchpad or Air Mouse resumes
     *  (suppressed while unified black pointer mode is active with an external mouse). */
    fun notifyTouchpadOrAirMouseActive() {
        cursorVisibilityHandler.removeCallbacks(restoreCursorAfterExternalMouse)
        if (_externalMouseHidingCursor.value) {
            _externalMouseHidingCursor.value = false
            updateEffectiveCursorVisibility()
        }
    }

    /**
     * Move Android's black system mouse pointer to the bottom-right corner edge
     * (width - 1, height - 1) and dismiss it across every relevant display (target
     * VD, physical external display, and default display) so only PortalPad's
     * custom white cursor remains visible. No-op while unified black-pointer
     * mode is active.
     */
    fun dismissSystemPointer() = safe {
        if (blackPointerModeActive) return@safe
        val backend = PortalPadApp.instance.activeBoundBackend ?: return@safe
        if (!backend.isReady) return@safe
        refreshDisplayMetrics()
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager
        fun cornerFor(dispId: Int, fallbackW: Int, fallbackH: Int): Pair<Float, Float> {
            val d = dm?.getDisplay(dispId)
            if (d != null) {
                val m = android.util.DisplayMetrics().also { @Suppress("DEPRECATION") d.getRealMetrics(it) }
                if (m.widthPixels > 1 && m.heightPixels > 1) {
                    return (m.widthPixels - 1).toFloat() to (m.heightPixels - 1).toFloat()
                }
            }
            return (fallbackW - 1).coerceAtLeast(0).toFloat() to (fallbackH - 1).coerceAtLeast(0).toFloat()
        }

        val (tDisp, _, _) = resolveTargetDisplayAndCoords(0f, 0f)
        val physId = PortalPadApp.instance.physicalExternalDisplayId.value
        val targets = linkedSetOf<Triple<Int, Float, Float>>().apply {
            val (tw, th) = cornerFor(tDisp, displayWidth, displayHeight)
            add(Triple(tDisp, tw, th))
            if (physId != null && physId != tDisp) {
                val (pw, ph) = cornerFor(physId, displayWidth, displayHeight)
                add(Triple(physId, pw, ph))
            }
            if (tDisp != android.view.Display.DEFAULT_DISPLAY &&
                physId != android.view.Display.DEFAULT_DISPLAY
            ) {
                val (dw, dh) = cornerFor(android.view.Display.DEFAULT_DISPLAY, displayWidth, displayHeight)
                add(Triple(android.view.Display.DEFAULT_DISPLAY, dw, dh))
            }
        }
        parkCornerCoords = targets.map { it.second to it.third }
        lastParkHoverAtMs = SystemClock.uptimeMillis()
        injectExecutor.execute {
            runCatching {
                lastParkHoverAtMs = SystemClock.uptimeMillis()
                for ((disp, px, py) in targets) {
                    backend.parkSystemPointer(disp, px, py)
                    val now = SystemClock.uptimeMillis()
                    backend.pointer(
                        action = MotionEvent.ACTION_DOWN,
                        x = 0f, y = 0f, displayId = disp,
                        source = InputDevice.SOURCE_TOUCHSCREEN, buttonState = 0, downTime = now,
                    )
                    backend.pointer(
                        action = MotionEvent.ACTION_CANCEL,
                        x = 0f, y = 0f, displayId = disp,
                        source = InputDevice.SOURCE_TOUCHSCREEN, buttonState = 0, downTime = now,
                    )
                }
                Log.d(
                    TAG,
                    "dismissSystemPointer -> parked at bottom-right & dismissed on " +
                        targets.joinToString { "d=${it.first}(${it.second.toInt()},${it.third.toInt()})" },
                )
            }
        }
    }

    /**
     * Update PortalPad's cursor coordinates to match an observed un-grabbed
     * external mouse position without emitting synthetic HOVER_MOVE back.
     */
    fun syncExternalCursorPositionQuietly(x: Float, y: Float) {
        cursorX = x.coerceIn(0f, (displayWidth - 1).coerceAtLeast(0).toFloat())
        cursorY = y.coerceIn(0f, (displayHeight - 1).coerceAtLeast(0).toFloat())
    }

    /**
     * Directional page scroll signal for scroll-to-hide dock.
     * Negative = page scrolling down (hide dock); Positive = page scrolling up (show dock).
     */
    private val _dockScrollSignal = MutableSharedFlow<Float>(
        replay = 0,
        extraBufferCapacity = 32,
    )
    val dockScrollSignal: SharedFlow<Float> = _dockScrollSignal.asSharedFlow()

    fun notifyPageScroll(pageDeltaY: Float) {
        if (pageDeltaY != 0f && !pageDeltaY.isNaN()) {
            _dockScrollSignal.tryEmit(pageDeltaY)
        }
    }

    /**
     * Emits the cursor coordinate on each committed left click. Desktop-mode
     * window controls observe this to drive a click-to-grab / move / click-to-
     * drop interaction, since injected taps don't stream MOVE events to overlay
     * windows the way a physical finger would. Replay 0, extra buffer so a
     * click is never dropped if the collector is briefly busy.
     */
    private val _clickEvents = MutableSharedFlow<Pair<Float, Float>>(
        replay = 0, extraBufferCapacity = 4,
    )
    val clickEvents: SharedFlow<Pair<Float, Float>> = _clickEvents.asSharedFlow()
    @Volatile private var lastTopBarSurfaceClickAt = 0L

    /** Called by leftClick() after a tap is injected. */
    private fun emitClickEvent(x: Float, y: Float) {
        _clickEvents.tryEmit(x to y)
    }

    private fun isTopBarSurfaceAt(x: Float, y: Float): Boolean =
        PortalPadForegroundService.cursorOverTopWindowBarSurface(x, y)

    private fun markTopBarSurfaceClick() {
        lastTopBarSurfaceClickAt = SystemClock.uptimeMillis()
    }

    private fun topBarSurfaceClickWasRecent(): Boolean =
        SystemClock.uptimeMillis() - lastTopBarSurfaceClickAt <= 650L

    @Volatile private var defaultDisplayWidth: Int = 0
    @Volatile private var defaultDisplayHeight: Int = 0

    private fun refreshDefaultDisplayMetrics(dm: DisplayManager? = null) = runCatching {
        val mgr = dm ?: (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
        val defDisplay = mgr.getDisplay(android.view.Display.DEFAULT_DISPLAY) ?: return@runCatching
        val m = DisplayMetrics().also { defDisplay.getRealMetrics(it) }
        if (m.widthPixels > 0 && m.heightPixels > 0) {
            defaultDisplayWidth = m.widthPixels
            defaultDisplayHeight = m.heightPixels
        }
    }

    /** Resolve target (displayId, x, y). When mirroring physical output without a VD wrap,
     *  app windows live on Display.DEFAULT_DISPLAY (0), so scale pointer coords there. */
    private fun resolveTargetDisplayAndCoords(x: Float, y: Float): Triple<Int, Float, Float> {
        if (!mirrorTargetDisplayZero || usingVd || displayId == 0) {
            return Triple(displayId, x, y)
        }
        if (defaultDisplayWidth <= 0 || defaultDisplayHeight <= 0) {
            refreshDefaultDisplayMetrics()
        }
        val dstW = defaultDisplayWidth
        val dstH = defaultDisplayHeight
        if (dstW <= 0 || dstH <= 0) {
            return Triple(android.view.Display.DEFAULT_DISPLAY, x, y)
        }
        val (sx, sy) = scaleCoordsForMirror(x, y, displayWidth, displayHeight, dstW, dstH)
        return Triple(android.view.Display.DEFAULT_DISPLAY, sx, sy)
    }

    private fun targetDisplayFlag(targetDisplayId: Int): String =
        if (targetDisplayId != 0) "-d $targetDisplayId " else ""

    private fun refreshDisplayMetrics() = runCatching {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        refreshDefaultDisplayMetrics(dm)
        if (displayId == 0) {
            displayWidth = defaultDisplayWidth.takeIf { it > 0 } ?: 1920
            displayHeight = defaultDisplayHeight.takeIf { it > 0 } ?: 1080
            cursorX = 100f; cursorY = 100f
            return@runCatching
        }
        val display = dm.getDisplay(displayId) ?: return@runCatching
        val metrics = DisplayMetrics().also { display.getRealMetrics(it) }
        displayWidth = metrics.widthPixels
        displayHeight = metrics.heightPixels
        cursorX = displayWidth / 2f
        cursorY = displayHeight / 2f
    }.onFailure { Log.w(TAG, "refreshDisplayMetrics failed", it) }

    /**
     * Re-read the display size WITHOUT recentering the cursor. A portrait-only
     * app (Reddit, etc.) can rotate the external display to portrait (taller),
     * changing its height; if we don't update [displayWidth]/[displayHeight] the
     * cursor stays clamped to the old (landscape) bounds and can't reach the
     * bottom. Called from the display-changed listener on rotation. Cheap no-op
     * when the size is unchanged (so it's safe to call on every change event).
     */
    fun updateDisplaySizeForRotation() = runCatching {
        refreshDefaultDisplayMetrics()
        if (displayId == 0) return@runCatching
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = dm.getDisplay(displayId) ?: return@runCatching
        val metrics = DisplayMetrics().also { display.getRealMetrics(it) }
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        if (w == displayWidth && h == displayHeight) return@runCatching
        Log.d(TAG, "display size ${displayWidth}x${displayHeight} -> ${w}x${h} (rotation?) — updating cursor bounds")
        displayWidth = w
        displayHeight = h
        // Clamp the existing cursor into the new bounds — do NOT recenter, so the
        // cursor doesn't teleport on minor display events.
        cursorX = cursorX.coerceIn(0f, (displayWidth - 1).toFloat())
        cursorY = cursorY.coerceIn(0f, (displayHeight - 1).toFloat())
    }.onFailure { Log.w(TAG, "updateDisplaySizeForRotation failed", it) }

    // ─────────────────────────── mouse ───────────────────────────

    /**
     * Move the virtual cursor by (dx, dy). Updates [cursorPosition] so the
     * custom overlay renders at the right place, and best-effort emits a
     * HOVER_MOVE event so apps tracking mouse hover can react.
     */
    /**
     * Cursor moved. Updates the local cursor coordinates and emits an
     * ACTION_HOVER_MOVE event with SOURCE_MOUSE so apps tracking real mouse
     * hover can respond (web pages, dropdowns, etc.). The event is sent via
     * the Shizuku UserService when available — IN-PROCESS hover injection
     * fails silently because our app lacks INJECT_EVENTS permission.
     *
     * Critically, this is a MOUSE-source hover event, not a TOUCHSCREEN move
     * event. The latter looks to Android like a finger dragging without
     * pressure, which causes focus loss on text fields (IME hides) and
     * clears text selections. the reference implementation uses the same hover-mouse pattern.
     */
    fun pointerMove(dx: Float, dy: Float) = safe {
        // SINGLE-WRITER rule during a caption drag: this pipeline (fed by the
        // air-mouse gyro among others) was writing the cursor IN PARALLEL with
        // dragMove's integration — every phone movement in the user's hand
        // fought the drag for the cursor, teleporting it hundreds of px
        // between samples while the window chased the chaos (drift3.txt: raw
        // drag deltas of ±5px with 300-700px cursor jumps between them; and
        // the original field report's "stabilizes when I leave the phone
        // alone" = gyro going quiet). Forwarding into dragMove keeps ONE
        // integration path — so air-mouse-driven window drags, where phone
        // motion IS the intent, still work.
        if (captionMoveActive || pixelEdgeResizeActive) {
            dragMove(dx, dy)
            return@safe
        }
        val sx = dx * cursorSpeed
        val sy = dy * cursorSpeed
        cursorX = (cursorX + sx).coerceIn(0f, (displayWidth - 1).toFloat())
        cursorY = (cursorY + sy).coerceIn(0f, (displayHeight - 1).toFloat())
        scheduleHoverInject(cursorX, cursorY)
        updateResizeCursor()
    }

    // ─── Live double-tap-drag (touchscreen down→move→up) ─────────────────────
    // A real held touch drag, driven frame-by-frame from the trackpad. Used for
    // moving freeform windows (and text selection). We use TOUCHSCREEN source —
    // same reason leftClick does: the window manager / apps follow a touch drag,
    // whereas injected mouse-button drags are widely ignored. downTime is held
    // constant across the whole gesture so the dispatcher treats DOWN/MOVE/UP as
    // one continuous pointer stream.
    @Volatile private var dragDownTime: Long = 0L
    // When a drag grabs a window edge, we lock the touch X and/or Y to that edge
    // so the injected MOVE stream stays on the resize region (the cursor itself
    // can drift; the touch point follows the resized edge). null = free (move).
    @Volatile private var dragLockX: Float? = null
    @Volatile private var dragLockY: Float? = null

    // ─── Pinch-zoom (two-pointer, continuous) ───────────────────────────────
    // The two-pointer gesture is held open in the helper and its span is streamed
    // live as the user pinches, so the app's zoom tracks the fingers in real time
    // (vs. one jump on release). [pinchUpdate] begins the gesture on its first
    // call and streams thereafter; [pinchCommit] releases it. Shizuku-only — the
    // shell `input` path has no multitouch. The helper runs a watchdog that
    // force-releases a stale gesture so a dropped "end" can't leave a stuck touch.
    @Volatile private var pinchSessionActive = false
    private var lastPinchSpan = PINCH_BASE_SPAN

    fun pinchUpdate(scale: Float) = safe {
        if (useA11y("pinch")) {
            // One-shot a11y pinch: just accumulate; dispatched on commit.
            a11yPinchActive = true
            a11yPinchScale = scale
            return@safe
        }
        val backend = PortalPadApp.instance.clickBackend as? ClickBackend.ShizukuUserService ?: return@safe
        if (!backend.backend.isReady) return@safe
        val maxSpan = (displayWidth - 1).coerceAtLeast(80).toFloat()
        val span = (PINCH_BASE_SPAN * scale).coerceIn(40f, maxSpan)
        if (!pinchSessionActive) {
            pinchSessionActive = true
            lastPinchSpan = PINCH_BASE_SPAN
            // Center the zoom where the cursor is when the pinch starts.
            backend.backend.pinchBegin(displayId, cursorX, cursorY, PINCH_BASE_SPAN)
        }
        // Throttle redundant updates (finger jitter) to keep binder traffic sane.
        if (kotlin.math.abs(span - lastPinchSpan) >= 2f) {
            lastPinchSpan = span
            backend.backend.pinchMove(span)
        }
    }

    fun pinchCommit() = safe {
        if (a11yPinchActive) {
            a11yPinchActive = false
            val scale = a11yPinchScale
            a11yPinchScale = 1f
            if (kotlin.math.abs(scale - 1f) < 0.05f) return@safe
            val maxSpan = (displayWidth - 1).coerceAtLeast(80).toFloat()
            val endSpan = (PINCH_BASE_SPAN * scale).coerceIn(40f, maxSpan)
            PortalPadAccessibilityService.instance?.dispatchPinch(
                displayId, cursorX, cursorY, PINCH_BASE_SPAN, endSpan, 200L,
            )
            debugToast("Pinch → a11y zoom x${"%.2f".format(scale)} disp=$displayId")
            return@safe
        }
        if (!pinchSessionActive) return@safe
        pinchSessionActive = false
        val backend = PortalPadApp.instance.clickBackend as? ClickBackend.ShizukuUserService ?: return@safe
        backend.backend.pinchEnd(lastPinchSpan)
        debugToast("Pinch end → zoom x${"%.2f".format(lastPinchSpan / PINCH_BASE_SPAN)} disp=$displayId")
    }

    /**
     * Discrete zoom step for the Reader page — a quick pinch centered where the
     * cursor is (same centering the trackpad pinch uses). [zoomIn] magnifies
     * (pinch out); otherwise shrinks. The a11y path dispatches a single animated
     * 200ms pinch; the Shizuku path ramps the span over a few frames so it reads
     * as a real gesture before releasing.
     */
    fun zoomStep(zoomIn: Boolean) {
        val target = if (zoomIn) 1.4f else 0.72f
        if (useA11y("zoom")) {
            pinchUpdate(target)
            pinchCommit()
            return
        }
        pinchUpdate(1.0f)
        pinchUpdate((1.0f + target) / 2f)
        pinchUpdate(target)
        pinchCommit()
    }

    // Move the grabbed window to startBounds + cursor delta, clamped to keep it mostly
    // on-screen (caption reachable). Runs the shell resize on the inject thread so the
    // input path never blocks on it; latest call wins.
    private fun postCaptionMoveResize() {
        val ddx = (cursorX - moveCursorStartX).toInt()
        val ddy = (cursorY - moveCursorStartY).toInt()
        val left = (moveStartLeft + ddx).coerceIn(160 - moveW, (displayWidth - 160).coerceAtLeast(0))
        val top = (moveStartTop + ddy).coerceIn(0, (displayHeight - 80).coerceAtLeast(0))
        lastCmdLeft = left; lastCmdTop = top
        val tid = moveTaskId
        val bounds = com.portalpad.app.data.WindowBounds(left, top, left + moveW, top + moveH)
        // The input target updates at 150-240 events/s on the Pixel, while
        // WindowManager can safely accept only ~11 bounds commands/s. Publish
        // the cursor at this command's original grab point, not at the faster
        // target, so the cursor and window travel as one object instead of the
        // cursor visibly racing ahead between commands.
        lastGlueX = (left + dragGrabOffX).coerceIn(0f, (displayWidth - 1).toFloat())
        lastGlueY = (top + dragGrabOffY).coerceIn(0f, (displayHeight - 1).toFloat())
        _cursorPosition.value = Pair(lastGlueX, lastGlueY)
        submitCaptionResize(tid, bounds)
    }

    /** Start the compatibility outline resize for the glyph currently shown
     *  on Pixel. Returns false when this isn't a Pixel edge grab, so the normal
     *  native-touch drag path remains unchanged on Samsung and other devices. */
    private fun startPixelEdgeResize(): Boolean {
        if (
            !desktopModeEnabled ||
            !usingVd ||
            !com.portalpad.app.presentation.PixelDesktopGuardOverlay.isPixelDevice() ||
            !PortalPadApp.instance.freeform.isReady
        ) return false
        val type = _cursorType.value
        if (
            type != CursorType.RESIZE_H &&
            type != CursorType.RESIZE_V &&
            type != CursorType.RESIZE_NWSE &&
            type != CursorType.RESIZE_NESW
        ) return false

        val tasks = runCatching {
            PortalPadApp.instance.freeform.listTasks(displayId)
        }.getOrDefault(cachedResizeTasks)
        val task = tasks.firstOrNull { t ->
            val b = t.bounds ?: return@firstOrNull false
            t.visible &&
                cursorX >= b.left - RESIZE_EDGE_OUT &&
                cursorX <= b.right + RESIZE_EDGE_OUT &&
                cursorY >= b.top - RESIZE_TOP_OUT &&
                cursorY <= b.bottom + RESIZE_EDGE_OUT
        } ?: return false
        val b = task.bounds ?: return false
        val maximized = b.left <= 2 && b.top <= 2 &&
            b.right >= displayWidth - 2 && b.bottom >= displayHeight - 2
        if (maximized) return false

        val onLeft =
            cursorX >= b.left - RESIZE_EDGE_OUT && cursorX <= b.left + RESIZE_EDGE_IN
        val onRight =
            cursorX >= b.right - RESIZE_EDGE_IN && cursorX <= b.right + RESIZE_EDGE_OUT
        val onTop =
            cursorY >= b.top - RESIZE_TOP_OUT && cursorY <= b.top + RESIZE_TOP_IN
        val onBottom =
            cursorY >= b.bottom - RESIZE_EDGE_IN && cursorY <= b.bottom + RESIZE_EDGE_OUT
        if (!onLeft && !onRight && !onTop && !onBottom) return false

        pixelEdgeResizeActive = true
        edgeResizeTaskId = task.taskId
        edgeResizeStartLeft = b.left
        edgeResizeStartTop = b.top
        edgeResizeStartRight = b.right
        edgeResizeStartBottom = b.bottom
        edgeResizeCursorStartX = cursorX
        edgeResizeCursorStartY = cursorY
        edgeResizeLeft = onLeft
        edgeResizeRight = onRight
        edgeResizeTop = onTop
        edgeResizeBottom = onBottom
        lastMoveResizeAt = 0L
        lastGlueX = cursorX
        lastGlueY = cursorY
        suppressCursorPublish = true
        edgeResizePreviewBounds = com.portalpad.app.data.WindowBounds(
            b.left,
            b.top,
            b.right,
            b.bottom,
        )
        edgeResizePreviewBounds?.let {
            PortalPadForegroundService.showCompatibilityResizePreview(it)
        }
        runCatching { PortalPadApp.instance.freeform.resetResizePathLog() }
        clickHaptic()
        Log.d(
            TAG,
            "dragStart PIXEL compatibility-resize task=$edgeResizeTaskId " +
                "L=$edgeResizeLeft R=$edgeResizeRight " +
                "T=$edgeResizeTop B=$edgeResizeBottom " +
                "bounds=[$edgeResizeStartLeft,$edgeResizeStartTop " +
                "$edgeResizeStartRight,$edgeResizeStartBottom]",
        )
        return true
    }

    /**
     * Apply the Pixel edge drag's latest cursor delta to the compatibility
     * outline. This does not resize the real task; the cursor and preview can
     * therefore move together at full input cadence without WM transitions.
     */
    private fun updatePixelEdgeResizePreview(): com.portalpad.app.data.WindowBounds {
        val dx = (cursorX - edgeResizeCursorStartX).toInt()
        val dy = (cursorY - edgeResizeCursorStartY).toInt()
        var left = edgeResizeStartLeft
        var top = edgeResizeStartTop
        var right = edgeResizeStartRight
        var bottom = edgeResizeStartBottom
        if (edgeResizeLeft) {
            left = (edgeResizeStartLeft + dx)
                .coerceIn(0, (edgeResizeStartRight - MIN_SHELL_WINDOW_PX).coerceAtLeast(0))
        }
        if (edgeResizeRight) {
            right = (edgeResizeStartRight + dx)
                .coerceIn(
                    (edgeResizeStartLeft + MIN_SHELL_WINDOW_PX).coerceAtMost(displayWidth),
                    displayWidth,
                )
        }
        if (edgeResizeTop) {
            top = (edgeResizeStartTop + dy)
                .coerceIn(0, (edgeResizeStartBottom - MIN_SHELL_WINDOW_PX).coerceAtLeast(0))
        }
        if (edgeResizeBottom) {
            bottom = (edgeResizeStartBottom + dy)
                .coerceIn(
                    (edgeResizeStartTop + MIN_SHELL_WINDOW_PX).coerceAtMost(displayHeight),
                    displayHeight,
                )
        }
        val bounds = com.portalpad.app.data.WindowBounds(left, top, right, bottom)
        lastGlueX = when {
            edgeResizeLeft -> left.toFloat()
            edgeResizeRight -> right.toFloat().coerceAtMost((displayWidth - 1).toFloat())
            else -> edgeResizeCursorStartX
        }
        lastGlueY = when {
            edgeResizeTop -> top.toFloat()
            edgeResizeBottom -> bottom.toFloat().coerceAtMost((displayHeight - 1).toFloat())
            else -> edgeResizeCursorStartY
        }
        _cursorPosition.value = Pair(lastGlueX, lastGlueY)
        edgeResizePreviewBounds = bounds
        PortalPadForegroundService.updateCompatibilityResizePreview(bounds)
        return bounds
    }

    /** Pixel's custom backdrop is a fullscreen activity, not a real launcher.
     *  A touch on visible empty wallpaper focuses that activity and minimizes
     *  every freeform window above it. Consume only when we have at least one
     *  visible window, the point is outside all of their bounds, and no
     *  PortalPad-owned dock surface owns that coordinate. */
    private fun shouldConsumePixelWallpaperInput(x: Float, y: Float): Boolean {
        if (
            displayId == 0 ||
            !desktopModeEnabled ||
            !usingVd ||
            !com.portalpad.app.presentation.PixelDesktopGuardOverlay.isPixelDevice()
        ) return false
        if (cursorOccludedByInputMethod(refresh = true)) return false
        val keyboard = extKeyboardBounds
        if (
            keyboard != null &&
            x >= keyboard.left && x < keyboard.right &&
            y >= keyboard.top && y < keyboard.bottom
        ) return false
        if (cursorOverWidgetOverlay) return false
        // The Pixel backdrop guard runs before the touch is injected. Without
        // this priority check, a dock click that sits outside the app window is
        // mislabeled as wallpaper and returned early, so the dock never receives
        // it (21:28:30-59 field trace). Use both the live Compose hover state and
        // the dock's measured glass bounds: the latter closes the one-frame race
        // while the cursor enters the band. A modal owns the whole expanded dock
        // window so outside taps can dismiss it normally.
        if (isPortalPadDockSurfaceAt(x, y)) return false
        val visibleWindows = cachedResizeTasks.filter { it.visible && it.bounds != null }
        if (visibleWindows.isEmpty()) return false
        return visibleWindows.none { task ->
            val b = task.bounds ?: return@none false
            x >= b.left && x < b.right && y >= b.top && y < b.bottom
        }
    }

    // ── Coalescing resize submitter ────────────────────────────────────────
    // Never issue a new resize while one is in flight: stash only the LATEST
    // target and fire it the instant the previous completes. Without this,
    // slow resize commands (the shell fallback, or a loaded binder) queued
    // faster than they completed — the COMMANDED position ran far ahead of the
    // ACTUAL window, so the glued cursor sailed off the caption bar while the
    // window lagged behind (field: drift early in a session, tight once the
    // fast path warmed up). With coalescing, commanded can never lead executed
    // by more than one step on ANY path; slow paths just take chunkier steps.
    private val captionResizeLock = Any()
    private var captionResizeBusy = false
    private var captionResizePendingTask = -1
    private var captionResizePendingBounds: com.portalpad.app.data.WindowBounds? = null

    private fun submitCaptionResize(taskId: Int, bounds: com.portalpad.app.data.WindowBounds) {
        synchronized(captionResizeLock) {
            if (captionResizeBusy) {
                captionResizePendingTask = taskId
                captionResizePendingBounds = bounds
                return
            }
            captionResizeBusy = true
        }
        injectExecutor.execute { runCaptionResizeLoop(taskId, bounds) }
    }

    private fun runCaptionResizeLoop(firstTask: Int, firstBounds: com.portalpad.app.data.WindowBounds) {
        var tid = firstTask
        var b: com.portalpad.app.data.WindowBounds? = firstBounds
        while (true) {
            val bb = b ?: break
            runCatching { PortalPadApp.instance.freeform.resize(tid, bb) }
            synchronized(captionResizeLock) {
                val nb = captionResizePendingBounds
                if (nb != null) {
                    tid = captionResizePendingTask
                    b = nb
                    captionResizePendingBounds = null
                } else {
                    b = null
                    captionResizeBusy = false
                }
            }
        }
    }

    /** If this click landed on a SystemUI close (X) control and the
     *  close-removes-from-Recents pref is on, the window is about to finish
     *  itself via Samsung's own handler — schedule a removeTask shortly after
     *  so the leftover Recents card is purged too (the DeX behavior).
     *  Entirely off-main; a scan miss or an already-gone task is a harmless
     *  no-op, so a normal content click costs one background node scan at
     *  most. The task is resolved from the click point BEFORE the window
     *  starts closing. */
    private fun maybePurgeClosedTask(x: Float, y: Float) {
        if (displayId == 0) return
        if (!runCatching { PortalPadApp.instance.freeform.closeRemovesFromRecents }.getOrDefault(false)) return
        val task = cachedResizeTasks.firstOrNull { t ->
            t.bounds?.let { b -> x >= b.left && x <= b.right && y >= b.top && y <= b.bottom } == true
        } ?: return
        Thread {
            try {
                val hit = PortalPadAccessibilityService.instance
                    ?.isCloseButtonAt(x.toInt(), y.toInt()) == true
                if (!hit) return@Thread
                Log.i(TAG, "DIAG-CLOSEX close control clicked → purging task=${task.taskId} in 600ms")
                Thread.sleep(600)
                val ok = runCatching {
                    PortalPadApp.instance.activeBoundBackend?.removeTask(task.taskId)
                }.getOrNull()
                Log.i(TAG, "DIAG-CLOSEX removeTask(${task.taskId}) → $ok")
            } catch (t: Throwable) {
                Log.w(TAG, "DIAG-CLOSEX purge failed", t)
            }
        }.start()
    }

    fun dragStart(captionMove: Boolean = false) = safe {
        // Fresh drag = fresh publish state (belt-and-braces against a leaked
        // suppression from an aborted caption move freezing the cursor).
        suppressCursorPublish = false
        // The external keyboard drives ITSELF — it must not also be dragged as a
        // window. Folded into the SAME gate as IME occlusion rather than an early
        // return, and that distinction is the whole fix: this path still injects
        // the touch DOWN/MOVE/UP ("forwarding ordinary drag"), which is exactly
        // what the keyboard's own Compose drag needs. Returning early would have
        // left it undraggable instead of doubly-dragged.
        //
        // EVIDENCE (extkb3.txt, 16:02:49.163): pressing the grab bar produced BOTH
        //     InputInjector: dragStart → touch DOWN display=98 resize=false
        //     Gesture      : DRAG START (hold + move)
        // and the keyboard's own drag. Two implementations then moved one window
        // from different inputs, and the relayouts walked it across the screen a
        // pixel at a time — the "flies away the moment I hold it" report. Neither
        // drag was wrong; they were simply both running.
        val kb = extKeyboardBounds
        val overExternalKeyboard = kb != null &&
            cursorX >= kb.left && cursorX <= kb.right &&
            cursorY >= kb.top && cursorY <= kb.bottom
        val windowChromeOccluded =
            cursorOccludedByInputMethod(refresh = true) || overExternalKeyboard
        if (overExternalKeyboard) {
            Log.d(
                TAG,
                "dragStart: over external keyboard at " +
                    "(${cursorX.toInt()},${cursorY.toInt()}) — window grab suppressed, " +
                    "forwarding ordinary drag",
            )
        }
        if (windowChromeOccluded && !overExternalKeyboard) {
            Log.i(
                TAG,
                "DIAG-IME-OCCLUSION window grab suppressed at " +
                    "(${cursorX.toInt()},${cursorY.toInt()}); forwarding ordinary drag",
            )
        }
        if (!captionMove && !windowChromeOccluded && startPixelEdgeResize()) {
            return@safe
        }
        if (
            !captionMove &&
            !windowChromeOccluded &&
            shouldConsumePixelWallpaperInput(cursorX, cursorY)
        ) {
            Log.d(
                TAG,
                "dragStart CONSUMED Pixel wallpaper " +
                    "display=$displayId (${cursorX.toInt()},${cursorY.toInt()})",
            )
            return@safe
        }
        if (useA11y("drag")) {
            a11yDragActive = true
            a11yDragStartX = cursorX
            a11yDragStartY = cursorY
            clickHaptic()
            Log.d(TAG, "a11y dragStart @(${cursorX.toInt()},${cursorY.toInt()})")
            return@safe
        }
        // Caption/handle MOVE → shell setBounds. Find the window under the cursor (the
        // handle sits at its top, so allow the cursor a little above the top), capture its
        // bounds, and move it via resize() on each dragMove. No touch injected on the pill
        // (so it also stops accidentally toggling the pill menu).
        if (captionMove && !windowChromeOccluded) {
            // LIVE menu check (not the TTL cache — full log 2026-07-17
            // 19:41:08.966: onPill=true while the menu was visibly open
            // because the cached flag was stale): with the pill menu up, a
            // hold on the pill area must NOT start a window move — the menu
            // items live right there and the user is aiming at them. The scan
            // stamps handleMenuVisibleNow fresh; on true we return with no
            // drag armed, so the gesture's dragMove/dragEnd fall through as
            // no-ops.
            val menuOpen = runCatching {
                PortalPadAccessibilityService.instance?.captionButtonRects()
                PortalPadAccessibilityService.instance?.handleMenuVisibleNow == true
            }.getOrDefault(false)
            if (menuOpen) {
                cachedMenuOpen = true
                Log.d(TAG, "dragStart CAPTION suppressed — pill menu open")
                return@safe
            }
            val task = runCatching {
                PortalPadApp.instance.freeform.listTasks(displayId).firstOrNull { t ->
                    val b = t.bounds
                    b != null && cursorX >= b.left && cursorX <= b.right &&
                        cursorY >= b.top - 80 && cursorY <= b.bottom
                }
            }.getOrNull()
            val b = task?.bounds
            if (task != null && b != null) {
                captionMoveActive = true
                _nativeCaptionDragActive.value = true
                // Every drag logs its resize path (see resetResizePathLog).
                runCatching { PortalPadApp.instance.freeform.resetResizePathLog() }
                moveTaskId = task.taskId
                moveStartLeft = b.left
                moveStartTop = b.top
                moveW = b.right - b.left
                moveH = b.bottom - b.top
                moveCursorStartX = cursorX
                moveCursorStartY = cursorY
                lastMoveResizeAt = 0L
                lastDragPosDiag = 0L
                dragRawSumX = 0f; dragRawSumY = 0f; dragMaxStep = 0f
                dragGrabOffX = cursorX - moveStartLeft
                dragGrabOffY = cursorY - moveStartTop
                lastCmdLeft = moveStartLeft; lastCmdTop = moveStartTop
                lastGlueX = cursorX
                lastGlueY = cursorY
                // Keep integrating the full-rate input into cursorX/Y for the
                // window target, but stop publishing those faster coordinates.
                // postCaptionMoveResize publishes the matching grab point at
                // the same cadence as the window commands.
                suppressCursorPublish = true
                clickHaptic()
                Log.d(TAG, "dragStart CAPTION shell-move task=$moveTaskId from=($moveStartLeft,$moveStartTop) size=${moveW}x$moveH")
                Log.d(TAG, "DIAG-DRAGPOS start cursor=(${cursorX.toInt()},${cursorY.toInt()}) grabOff=(${dragGrabOffX.toInt()},${dragGrabOffY.toInt()})")
                return@safe
            }
            // Couldn't resolve the window — fall through to the touch-drag as a best effort.
        }
        val backend = PortalPadApp.instance.clickBackend as? ClickBackend.ShizukuUserService
            ?: return@safe
        if (!backend.backend.isReady) return@safe
        val now = SystemClock.uptimeMillis()
        dragDownTime = now
        dragLockX = null
        dragLockY = null

        // Edge detection: if the cursor is near the focused window's edge/corner,
        // snap the touch-down EXACTLY onto that edge so the injected touch lands on
        // Android's resize region (thin, ~a few px) — giving native resize its best
        // chance to trigger. Otherwise the touch-down is at the cursor (a move).
        var startX = cursorX
        var startY = cursorY
        // A caption/handle grab is an explicit MOVE: keep the touch-down AT the cursor
        // so Android moves the window. The collapsed handle sits ON the window's top
        // resize edge, so WITHOUT this guard the edge-snap below turned every handle
        // drag into a vertical resize. Only non-caption grabs snap to a resize edge.
        if (!captionMove || windowChromeOccluded) {
        if (!windowChromeOccluded) runCatching {
            val tasks = PortalPadApp.instance.freeform.listTasks(displayId)
            // Focused = the task whose bounds contain the cursor (topmost wins;
            // listTasks is roughly front-to-back, so first match is fine).
            val b = tasks.firstNotNullOfOrNull { it.bounds?.takeIf { bb ->
                cursorX >= bb.left - RESIZE_EDGE_OUT && cursorX <= bb.right + RESIZE_EDGE_OUT &&
                    cursorY >= bb.top - RESIZE_TOP_OUT && cursorY <= bb.bottom + RESIZE_EDGE_OUT
            } }
            if (b != null) {
                // Match the visible cursor strips exactly. A drag must never
                // become a resize while the user still sees an arrow.
                val nearLeft =
                    cursorX >= b.left - RESIZE_EDGE_OUT && cursorX <= b.left + RESIZE_EDGE_IN
                val nearRight =
                    cursorX >= b.right - RESIZE_EDGE_IN && cursorX <= b.right + RESIZE_EDGE_OUT
                val nearTop =
                    cursorY >= b.top - RESIZE_TOP_OUT && cursorY <= b.top + RESIZE_TOP_IN
                val nearBottom =
                    cursorY >= b.bottom - RESIZE_EDGE_IN && cursorY <= b.bottom + RESIZE_EDGE_OUT
                // left/top are INCLUSIVE coords (first pixel inside the window), so
                // landing exactly on them touches app content and won't resize.
                // Nudge the touch-down 1px OUTSIDE into Android's resize region —
                // right/bottom are exclusive coords and already sit just outside.
                if (nearLeft) { startX = b.left.toFloat() - 1f; dragLockX = startX }
                if (nearRight) { startX = b.right.toFloat(); dragLockX = startX }
                if (nearTop) { startY = b.top.toFloat() - 1f; dragLockY = startY }
                if (nearBottom) { startY = b.bottom.toFloat(); dragLockY = startY }
                if (dragLockX != null || dragLockY != null) {
                    Log.d(TAG, "dragStart EDGE grab L=$nearLeft R=$nearRight T=$nearTop B=$nearBottom @($startX,$startY)")
                }
            }
        }
        } else {
            // Snap the touch-down onto the ACTUAL handle pill. The grab ZONE is generous
            // (±HANDLE_GRAB_OUT), so the raw cursor can sit off the real pill; Samsung only
            // starts a handle-drag when the touch lands ON the pill (like a real finger in
            // DeX). Land it on the center of the pill under the cursor's x-span, and move
            // the cursor there so the drag is continuous and the cursor stays glued to it.
            val pill = cachedHandles.firstOrNull { h ->
                h.clickable &&
                    cursorX >= h.rect.left - HANDLE_GRAB_OUT_X && cursorX <= h.rect.right + HANDLE_GRAB_OUT_X &&
                    cursorY >= h.rect.top - 80f && cursorY <= h.rect.bottom + 80f
            }?.rect
            if (pill != null) {
                startX = (pill.left + pill.right) / 2f
                startY = (pill.top + pill.bottom) / 2f
                cursorX = startX
                cursorY = startY
                Log.d(TAG, "dragStart HANDLE snap → pill center ($startX,$startY)")
            }
        } // end if (!captionMove)

        val (tDisp, tx, ty) = resolveTargetDisplayAndCoords(startX, startY)
        pauseHoverForTouch(startX, startY)
        try {
            backend.backend.pointer(
                action = MotionEvent.ACTION_DOWN,
                x = tx, y = ty, displayId = tDisp,
                source = InputDevice.SOURCE_TOUCHSCREEN, buttonState = 0, downTime = now,
            )
        } catch (t: Throwable) {
            // Do not leave hover permanently parked if the touch DOWN could not
            // be delivered (for example while the UserService is rebinding).
            dragDownTime = 0L
            dragLockX = null
            dragLockY = null
            resumeHoverAfterTouch()
            throw t
        }
        clickHaptic()
        Log.d(TAG, "dragStart → touch DOWN display=$tDisp ($tx,$ty) resize=${dragLockX != null || dragLockY != null}")
        // Instant resize border: Android paints the freeform resize outline only
        // once it sees motion, so nudge the grab 1px in the resize axis right after
        // the DOWN. Invisible (1px), but makes the border appear the moment you
        // grab, before you actually move.
        if (dragLockX != null || dragLockY != null) {
            val nudgeX = if (dragLockX != null) tx + 1f else tx
            val nudgeY = if (dragLockY != null) ty + 1f else ty
            backend.backend.pointer(
                action = MotionEvent.ACTION_MOVE,
                x = nudgeX, y = nudgeY, displayId = tDisp,
                source = InputDevice.SOURCE_TOUCHSCREEN, buttonState = 0, downTime = now,
            )
        }
    }

    fun dragMove(dx: Float, dy: Float) = safe {
        if (pixelEdgeResizeActive) {
            val sx = dx * cursorSpeed
            val sy = dy * cursorSpeed
            if (edgeResizeLeft || edgeResizeRight) {
                cursorX = (cursorX + sx).coerceIn(0f, (displayWidth - 1).toFloat())
            }
            if (edgeResizeTop || edgeResizeBottom) {
                cursorY = (cursorY + sy).coerceIn(0f, (displayHeight - 1).toFloat())
            }
            updatePixelEdgeResizePreview()
            return@safe
        }
        if (captionMoveActive) {
            // Keep the full-rate finger target for accurate final placement.
            // Its setters are publication-suppressed during a caption move;
            // postCaptionMoveResize renders the cursor at the window's matching
            // grab point on each safe WindowManager command instead.
            cursorX = (cursorX + dx * cursorSpeed).coerceIn(0f, (displayWidth - 1).toFloat())
            cursorY = (cursorY + dy * cursorSpeed).coerceIn(0f, (displayHeight - 1).toFloat())
            captionMoveEvents++
            val now = SystemClock.uptimeMillis()
            // DIAG-DRAG rate: confirms move events are actually ARRIVING during
            // the drag (drift.txt showed a 14s caption drag with resizes only
            // at start/end — either dragMove wasn't called, or the throttle
            // starved it). One line/sec with the event count since last line.
            if (now - lastDragRateDiag > 1000) {
                lastDragRateDiag = now
                Log.d(TAG, "DIAG-DRAG move events in last ~1s: ${captionMoveEvents - lastDragRateCount}")
                lastDragRateCount = captionMoveEvents
            }
            // DIAG-DRAGPOS sample (~10 Hz; see the field block for what each
            // column discriminates). ASCII-only values — PowerShell captures
            // mangle non-ASCII.
            val stepX = kotlin.math.abs(dx * cursorSpeed)
            val stepY = kotlin.math.abs(dy * cursorSpeed)
            dragRawSumX += dx * cursorSpeed
            dragRawSumY += dy * cursorSpeed
            val step = if (stepX > stepY) stepX else stepY
            if (step > dragMaxStep) dragMaxStep = step
            if (now - lastDragPosDiag > 100) {
                lastDragPosDiag = now
                Log.d(
                    TAG,
                    "DIAG-DRAGPOS cursor=(${cursorX.toInt()},${cursorY.toInt()}) " +
                        "cmd=($lastCmdLeft,$lastCmdTop) " +
                        "dOff=(${(cursorX - lastCmdLeft - dragGrabOffX).toInt()}," +
                        "${(cursorY - lastCmdTop - dragGrabOffY).toInt()}) " +
                        "rawSum=(${dragRawSumX.toInt()},${dragRawSumY.toInt()}) " +
                        "maxStep=${dragMaxStep.toInt()}",
                )
                dragRawSumX = 0f; dragRawSumY = 0f; dragMaxStep = 0f
            }
            // Cadence: both resize paths pace at ~11 steps/s. The old claim
            // that the fast binder "sustains ~33 steps/s" measured SUBMISSION,
            // not application — WM wraps every resizeTask in a queued
            // transition and drains ~25-30/s max, so faster submits only grew
            // a playback backlog (see CAPTION_MOVE_THROTTLE_* for the drag-
            // test evidence).
            val moveThrottle = if (runCatching {
                    PortalPadApp.instance.freeform.lastResizeWasFast
                }.getOrDefault(false)
            ) CAPTION_MOVE_THROTTLE_FAST_MS else CAPTION_MOVE_THROTTLE_MS
            if (now - lastMoveResizeAt >= moveThrottle) {
                lastMoveResizeAt = now
                postCaptionMoveResize()
            }
            return@safe
        }
        if (a11yDragActive) {
            // Cursor tracks the finger so the user sees the drag; the actual drag
            // gesture is dispatched once on dragEnd (start→end).
            cursorX = (cursorX + dx * cursorSpeed).coerceIn(0f, (displayWidth - 1).toFloat())
            cursorY = (cursorY + dy * cursorSpeed).coerceIn(0f, (displayHeight - 1).toFloat())
            return@safe
        }
        val backend = PortalPadApp.instance.clickBackend as? ClickBackend.ShizukuUserService
            ?: return@safe
        if (!backend.backend.isReady || dragDownTime == 0L) return@safe
        val sx = dx * cursorSpeed
        val sy = dy * cursorSpeed
        // Resize axis lock: when a single edge was grabbed, pin the off-axis so the
        // cursor stays glued to the edge being dragged (corner grabs move both;
        // a plain move has no lock and moves both).
        val lockedX = dragLockX != null // vertical edge → X is the resize axis
        val lockedY = dragLockY != null // horizontal edge → Y is the resize axis
        val isResize = lockedX || lockedY
        if (!isResize || lockedX) cursorX = (cursorX + sx).coerceIn(0f, (displayWidth - 1).toFloat())
        if (!isResize || lockedY) cursorY = (cursorY + sy).coerceIn(0f, (displayHeight - 1).toFloat())
        // For a resize-edge grab, the touch follows the cursor on the free axis
        // but we still send the cursor coords (the dragged edge tracks the finger).
        val (tDisp, tx, ty) = resolveTargetDisplayAndCoords(cursorX, cursorY)
        backend.backend.pointer(
            action = MotionEvent.ACTION_MOVE,
            x = tx, y = ty, displayId = tDisp,
            source = InputDevice.SOURCE_TOUCHSCREEN, buttonState = 0, downTime = dragDownTime,
        )
    }

    fun dragEnd() = safe {
        if (pixelEdgeResizeActive) {
            val finalBounds = updatePixelEdgeResizePreview()
            pixelEdgeResizeActive = false
            cursorX = lastGlueX
            cursorY = lastGlueY
            suppressCursorPublish = false
            _cursorPosition.value = Pair(cursorX, cursorY)
            val taskId = edgeResizeTaskId
            edgeResizeTaskId = -1
            edgeResizeLeft = false
            edgeResizeRight = false
            edgeResizeTop = false
            edgeResizeBottom = false
            edgeResizePreviewBounds = null
            lastResizeTaskCacheAt = 0L
            // Exactly one real task resize. Keep the final outline up briefly
            // while Pixel applies its bounds transition, then remove it.
            submitCaptionResize(taskId, finalBounds)
            PortalPadForegroundService.finishCompatibilityResizePreview()
            Log.d(
                TAG,
                "dragEnd PIXEL compatibility-resize -> " +
                    "cursor=(${cursorX.toInt()},${cursorY.toInt()}) " +
                    "bounds=[${finalBounds.left},${finalBounds.top} " +
                    "${finalBounds.right},${finalBounds.bottom}]",
            )
            return@safe
        }
        if (captionMoveActive) {
            captionMoveActive = false
            _nativeCaptionDragActive.value = false
            postCaptionMoveResize() // land the final position exactly
            // Resume normal cursor publication from the point the user just
            // saw attached to the final window command. Without this sync, the
            // hidden full-rate target could reappear on release as a jump.
            cursorX = lastGlueX
            cursorY = lastGlueY
            suppressCursorPublish = false
            _cursorPosition.value = Pair(cursorX, cursorY)
            moveTaskId = -1
            // The pill just MOVED with its window: the cached handle rect now
            // points at the pill's OLD position, and the 250ms scan + 600ms
            // grace kept honoring it — field bug: a phantom grab zone lingered
            // where the pill used to be (~100px off after a long move). Drop
            // the cache and force an immediate rescan; the press-time test
            // queries fresh rects on an empty cache, so the next grab is
            // judged against the pill's REAL position.
            cachedHandles = emptyList()
            lastHandleRectsAt = 0L
            lastResizeTaskCacheAt = 0L
            Log.d(TAG, "dragEnd CAPTION shell-move → ($cursorX,$cursorY) — handle cache invalidated")
            return@safe
        }
        if (a11yDragActive) {
            a11yDragActive = false
            val (tDisp, tsx, tsy) = resolveTargetDisplayAndCoords(a11yDragStartX, a11yDragStartY)
            val (_, tex, tey) = resolveTargetDisplayAndCoords(cursorX, cursorY)
            // Slow swipe (350ms) so it reads as a drag, not a fling. Window-move
            // works; text-select may need a long-press dwell a11y can't easily add.
            PortalPadAccessibilityService.instance?.dispatchSwipe(
                tDisp, tsx, tsy, tex, tey, 350L, "drag",
            )
            Log.d(TAG, "a11y dragEnd disp=$tDisp (${tsx.toInt()},${tsy.toInt()})→(${tex.toInt()},${tey.toInt()})")
            return@safe
        }
        if (dragDownTime == 0L) return@safe
        val (tDisp, tx, ty) = resolveTargetDisplayAndCoords(cursorX, cursorY)
        try {
            val backend = PortalPadApp.instance.clickBackend as? ClickBackend.ShizukuUserService
            if (backend?.backend?.isReady == true) {
                backend.backend.pointer(
                    action = MotionEvent.ACTION_UP,
                    x = tx, y = ty, displayId = tDisp,
                    source = InputDevice.SOURCE_TOUCHSCREEN, buttonState = 0, downTime = dragDownTime,
                )
            }
        } finally {
            dragDownTime = 0L
            dragLockX = null
            dragLockY = null
            resumeHoverAfterTouch()
        }
        Log.d(TAG, "dragEnd → touch UP display=$tDisp (${tx.toInt()},${ty.toInt()})")
        repinImePolicy()
    }

    /**
     * Snap the cursor to the center of the current external display. Used by the
     * trackpad long-press menu's "Recenter Cursor" action when the pointer has
     * drifted off-screen or gotten lost. Updates the visible overlay cursor via
     * the same hover path as a normal move.
     */
    fun recenterCursor() = safe {
        refreshDisplayMetrics()
        cursorX = displayWidth / 2f
        cursorY = displayHeight / 2f
        scheduleHoverInject(cursorX, cursorY)
        clickHaptic()
    }

    /**
     * Sends a single MOUSE-source HOVER_MOVE event via the active click
     * backend. Hover is always emitted (it drives hover-state UI like Chrome's
     * tab highlights); the only kill switch is the compile-time [EMIT_HOVER].
     */
    @Volatile private var lastHoverLogAt: Long = 0

    /**
     * Mirrors a reference mouse-active flag: true when we have an active mouse
     * hover registered with the input dispatcher (i.e. we sent HOVER_ENTER
     * since the last HOVER_EXIT or display change). HOVER_MOVE events without
     * a preceding HOVER_ENTER may be treated as stray pointers by Android,
     * which can cause Chrome's PopupWindow suggestion dropdown (and similar
     * anchor popups) to dismiss when the cursor moves into them. the reference implementation
     * always sends HOVER_ENTER first, then HOVER_MOVE on subsequent updates.
     */
    @Volatile private var hoverActive: Boolean = false

    /**
     * A touchscreen tap/drag and a MOUSE hover pointer cannot coexist for the
     * Shizuku backend: InputDispatcher rejects HOVER_ENTER/MOVE while the touch
     * pointer is down. Keep hover paused from the serialized HOVER_EXIT through
     * the matching touch UP, then resume from the current cursor position.
     */
    @Volatile private var hoverPausedForTouch: Boolean = false

    // ─── Coalesced, off-thread hover injection (#5) ─────────────────────────
    // injectPointer is a BLOCKING binder round-trip to the UserService. Running
    // it on the gesture thread per trackpad delta blocks finger tracking. We
    // instead publish the LATEST position and let a single injector thread emit
    // only the most recent one (collapsing a burst of deltas into ~one inject
    // per cycle). The cursor VISUAL is unaffected — it still moves instantly via
    // the cursorPosition StateFlow on the caller's thread. sendHoverViaBackend
    // is therefore only ever invoked on this injector thread.
    private val injectExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "PortalPad-Inject").apply { isDaemon = true }
    }
    private val pendingHover = java.util.concurrent.atomic.AtomicReference<FloatArray?>(null)
    private val hoverDrainScheduled = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Publish the latest cursor position and ensure a drain is scheduled.
     *  Returns immediately — no IPC on the caller (gesture) thread. */
    private fun scheduleHoverInject(x: Float, y: Float) {
        if (hoverPausedForTouch) {
            pendingHover.set(null)
            return
        }
        // While the a11y input path is active (untrusted display), backend
        // hover events are at best useless (Shizuku events don't land in
        // foreign windows there) and at worst they RACE the a11y gestures and
        // get them cancelled — the Sony trackpad bug. Suppress the stream and
        // park any pointer left over from before the a11y state applied.
        // Throttled log so user-submitted captures show this mode is active.
        if (useA11y("hover")) {
            pendingHover.set(null)
            val now = SystemClock.uptimeMillis()
            if (now - lastHoverLogAt > 1000) {
                Log.d(TAG, "hover SUPPRESSED (a11y input path active) disp=$displayId")
                lastHoverLogAt = now
            }
            if (hoverActive) {
                runCatching { injectExecutor.execute { sendHoverExitOnInjectThread(x, y) } }
            }
            return
        }
        pendingHover.set(floatArrayOf(x, y))
        if (hoverDrainScheduled.compareAndSet(false, true)) {
            runCatching {
                injectExecutor.execute {
                    hoverDrainScheduled.set(false)
                    val p = pendingHover.getAndSet(null) ?: return@execute
                    runCatching { sendHoverViaBackend(p[0], p[1]) }
                }
            }
        }
    }

    /**
     * Hover events provide live mouse-position feedback to whatever's on
     * the target display. With overlays now using
     * TYPE_ACCESSIBILITY_OVERLAY (2032) when accessibility is bound,
     * Chrome's popup behavior should be stable. Re-enabling hover so
     * users see proper hover state in apps (highlights, tooltips, etc.)
     *
     *   true  — hover events fire normally (default for production)
     *   false — hover injection entirely disabled at compile time
     */
        private val EMIT_HOVER = true

    @Volatile private var activeHoverDisplayId: Int = -1
    @Volatile private var lastInjectedHoverAtMs: Long = 0L
    @Volatile private var lastInjectedHoverX: Float = -1f
    @Volatile private var lastInjectedHoverY: Float = -1f
    @Volatile private var lastParkHoverAtMs: Long = 0L
    @Volatile private var parkCornerCoords: List<Pair<Float, Float>> = emptyList()
    @Volatile private var lastInjectedScrollAtMs: Long = 0L

    fun isLikelyOwnInjectedHover(x: Float, y: Float, windowMs: Long = 160L): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lastParkHoverAtMs in 0..600L) {
            if (parkCornerCoords.any { (px, py) ->
                    kotlin.math.hypot((x - px).toDouble(), (y - py).toDouble()) <= 24.0
                }
            ) {
                return true
            }
        }
        val dt = now - lastInjectedHoverAtMs
        if (dt !in 0..windowMs) return false
        return kotlin.math.hypot((x - lastInjectedHoverX).toDouble(), (y - lastInjectedHoverY).toDouble()) <= 8.0
    }

    fun isLikelyOwnInjectedScroll(windowMs: Long = 160L): Boolean {
        val dt = SystemClock.uptimeMillis() - lastInjectedScrollAtMs
        return dt in 0..windowMs
    }

    private fun sendHoverViaBackend(x: Float, y: Float) {
        if (hoverPausedForTouch) return
        if (!EMIT_HOVER) {
            // Hard kill — bypass pref read entirely. Cursor overlay still
            // moves visually via the cursorPosition StateFlow; only the
            // injected MotionEvents are skipped.
            //
            // Log once per second so it's visible in logcat that this mode
            // is active, without spamming.
            val now = SystemClock.uptimeMillis()
            if (now - lastHoverLogAt > 1000) {
                Log.d(TAG, "hover HARD-DISABLED at compile time (EMIT_HOVER=false) disp=$displayId pos=(${x.toInt()},${y.toInt()})")
                lastHoverLogAt = now
            }
            return
        }

        val backend = PortalPadApp.instance.clickBackend
        when (backend) {
            is ClickBackend.ShizukuUserService -> {
                if (!backend.backend.isReady) return
                val wantHwPointer = blackPointerModeActive
                if (lastPushedHardwareMouseBackend !== backend.backend ||
                    lastPushedHardwareMouseMode != wantHwPointer
                ) {
                    lastPushedHardwareMouseBackend = backend.backend
                    lastPushedHardwareMouseMode = wantHwPointer
                    runCatching { backend.backend.setUseHardwareMousePointer(wantHwPointer) }
                }
                val (tDisp, tx, ty) = resolveTargetDisplayAndCoords(x, y)
                val now = SystemClock.uptimeMillis()
                // Mirror the reference implementation's pattern: send HOVER_ENTER (action 9) before
                // the first HOVER_MOVE following any HOVER_EXIT. The dispatcher
                // needs to see a registered enter event to treat subsequent
                // moves as the same hovering pointer; without it, individual
                // moves can be treated as stray pointers and cause anchor
                // popups (Chrome address-bar suggestions, autocomplete menus)
                // to dismiss when the cursor enters them.
                if (!hoverActive || activeHoverDisplayId != tDisp) {
                    // Action 9 = ACTION_HOVER_ENTER, source 8194 = MOUSE | POINTER
                    backend.backend.pointer(
                        action = 9, x = tx, y = ty, displayId = tDisp,
                        source = 8194, buttonState = 0, downTime = now,
                    )
                    hoverActive = true
                    activeHoverDisplayId = tDisp
                    Log.d(TAG, "hover ENTER disp=$tDisp pos=(${tx.toInt()},${ty.toInt()})")
                }
                // 8194 = SOURCE_MOUSE | CLASS_POINTER, action 7 = HOVER_MOVE
                lastInjectedHoverAtMs = SystemClock.uptimeMillis()
                lastInjectedHoverX = tx
                lastInjectedHoverY = ty
                backend.backend.pointer(
                    action = MotionEvent.ACTION_HOVER_MOVE,
                    x = tx, y = ty, displayId = tDisp,
                    source = 8194, buttonState = 0, downTime = now,
                )
                // Throttled — every 150ms — to avoid log flooding while still
                // showing motion presence around suspected dismiss events.
                if (PortalPadApp.verboseHoverLog && now - lastHoverLogAt > 150) {
                    Log.d(TAG, "hover disp=$tDisp pos=(${tx.toInt()},${ty.toInt()})")
                    lastHoverLogAt = now
                }
            }
            is ClickBackend.Shell -> {
                // No-op: shell `input` command doesn't support hover events.
                // The cursor overlay still moves visibly.
            }
        }
    }

    /**
     * Park the virtual mouse cursor with a HOVER_EXIT event before injecting
     * a touch sequence. Without this, Android's input dispatcher sees a
     * mouse mid-hover when a touch arrives at the same coordinates and may
     * reject the touch or misroute it. A reference mirror-cancel / hover-exit
     * routine does the same dance.
     */
    /** Must run on [injectExecutor], so no HOVER event can cross a touch event. */
    private fun sendHoverExitOnInjectThread(x: Float, y: Float) {
        if (!EMIT_HOVER) return
        val backend = PortalPadApp.instance.clickBackend
        if (backend !is ClickBackend.ShizukuUserService) return
        if (!backend.backend.isReady) return
        pendingHover.set(null)
        if (!hoverActive) return
        val (_, tx, ty) = resolveTargetDisplayAndCoords(x, y)
        val exitDisp = if (activeHoverDisplayId >= 0) activeHoverDisplayId else displayId
        val now = SystemClock.uptimeMillis()
        backend.backend.pointer(
            action = 10, x = tx, y = ty, displayId = exitDisp,
            source = 8194, buttonState = 0, downTime = now,
        )
        hoverActive = false
        activeHoverDisplayId = -1
        Log.d(TAG, "hover EXIT disp=$exitDisp pos=(${tx.toInt()},${ty.toInt()})")
    }

    private fun sendHoverExitViaBackend(x: Float, y: Float) {
        // Hover MOVE/EXIT share one injector queue. Waiting for the queued EXIT
        // establishes HOVER_EXIT → touch-DOWN order on the input dispatcher.
        runCatching {
            injectExecutor.submit { sendHoverExitOnInjectThread(x, y) }
                .get(100, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
    }

    private fun pauseHoverForTouch(x: Float, y: Float) {
        hoverPausedForTouch = true
        pendingHover.set(null)
        sendHoverExitViaBackend(x, y)
    }

    private fun resumeHoverAfterTouch() {
        hoverPausedForTouch = false
        scheduleHoverInject(cursorX, cursorY)
    }

    /**
     * Click via whichever backend the user picked in Settings → System → Click method.
     * No silent fallback — if the chosen backend isn't ready, we fail loudly via
     * the debug toast so the user can fix it.
     */
    fun leftClick() = safe {
        clickHaptic()
        val x = cursorX.toInt(); val y = cursorY.toInt()
        // Record deliberate glasses taps so the accessibility detector can require
        // a recent tap before opening the relay (a tapped field opens it; an app's
        // launch-autofocused field does not). Only count taps on the glasses (the
        // VD), not the phone's own display 0.
        if (displayId != 0) {
            PortalPadApp.instance.lastGlassesTapAt = android.os.SystemClock.elapsedRealtime()
            // On-display keyboard: report taps OUTSIDE its window. Taps on empty
            // non-clickable space emit no accessibility event, so the a11y
            // dismiss branch never hears them; the probe dismisses after a grace
            // window unless a click event arrives (then a11y is the authority).
            com.portalpad.app.presentation.ExternalKeyboardProbe.let { kb ->
                if (kb.isVisible() && !kb.containsPoint(cursorX, cursorY)) {
                    kb.onInjectedTapOutside()
                }
            }
            // Floating keyboard lives on the PHONE, so every external-display tap
            // is outside it by definition - same watchdog, no bounds check.
            com.portalpad.app.presentation.FloatingKeyboardOverlay.let { fkb ->
                if (fkb.isVisible()) fkb.onInjectedTapOutside()
            }
        }
        // Notify desktop-mode window controls of the click coordinate (used for
        // click-to-grab window move/resize). Cheap no-op when nothing observes.
        val topBarSurfaceClick = isTopBarSurfaceAt(x.toFloat(), y.toFloat())
        emitClickEvent(x.toFloat(), y.toFloat())
        // PortalPad's bar is a coordinate-driven, non-touchable overlay. Once
        // its observer receives the click, do not inject the same tap through
        // to Android caption chrome underneath.
        if (topBarSurfaceClick) {
            markTopBarSurfaceClick()
            Log.d(TAG, "Left click CONSUMED PortalPad top-bar surface ($x,$y)")
            return@safe
        }
        // DeX-style Recents purge for SAMSUNG's own close controls (caption X /
        // pill-menu X) — SystemUI-owned buttons whose clicks never route
        // through FreeformManager.close.
        maybePurgeClosedTask(x.toFloat(), y.toFloat())
        // Pixel's backdrop is a normal fullscreen activity. Let PortalPad's
        // coordinate observers (dock/top bar/etc.) see the click above, but do
        // not inject a touch into genuinely empty wallpaper while a window is
        // visible: focusing the backdrop moves that window TO_BACK.
        if (shouldConsumePixelWallpaperInput(x.toFloat(), y.toFloat())) {
            Log.d(TAG, "Left click CONSUMED Pixel wallpaper display=$displayId ($x,$y)")
            return@safe
        }
        // A click on the external display can open/close the pill menu, which
        // changes the whole zone model (the pill stops being a drag target
        // while its menu is up). Invalidate the TTL cache now AND after the
        // menu's animate-in, so the next region pass reads fresh state instead
        // of dragging on stale zones (19:41:08.966: onPill=true with the menu
        // open).
        if (displayId != 0) {
            lastResizeTaskCacheAt = 0L
            Thread {
                runCatching {
                    Thread.sleep(400)
                    lastResizeTaskCacheAt = 0L
                }
            }.start()
        }
        // Accessibility fallback: on a NON-trusted display (the trusted VD
        // couldn't be created), Shizuku-injected touches frequently don't land in
        // foreign app windows. An a11y-dispatched gesture is system-sourced and
        // honored without display trust, so prefer it there. Falls through to the
        // normal backend if the a11y service isn't connected / can't dispatch.
        //
        // Same condition as useA11y(), spelled out because this branch also
        // needs the svc null-check ordering below. (The non-Samsung
        // preferA11yOnDevice gate that briefly lived here is gone — see the
        // history note on useA11y.)
        val (tDisp, tx, ty) = resolveTargetDisplayAndCoords(x.toFloat(), y.toFloat())
        val backendReady = activeBackendReady()
        if (displayId != 0 && !(mirrorTargetDisplayZero && backendReady) && (!usingVd || !backendReady)) {
            val svc = PortalPadAccessibilityService.instance
            if (svc != null) {
                sendHoverExitViaBackend(x.toFloat(), y.toFloat())
                if (svc.dispatchTap(tDisp, tx, ty)) {
                    debugToast("Left click → a11y gesture display=$tDisp (${tx.toInt()},${ty.toInt()})")
                    return@safe
                }
            }
        }
        val backend = PortalPadApp.instance.clickBackend

        when (backend) {
            is ClickBackend.ShizukuUserService -> {
                if (!backend.backend.isReady) {
                    debugToast("Left click BLOCKED — Shizuku UserService not bound")
                    return@safe
                }
                // 1) Park the mouse cursor with HOVER_EXIT so the touch event
                //    isn't filtered by Android's input dispatcher as a
                //    mouse-button spoof. A reference implementation does the same.
                pauseHoverForTouch(x.toFloat(), y.toFloat())
                // 2) Verified tap (Touch -> Mouse -> Shell) via ShellUserService.
                var ok = try {
                    backend.backend.tap(tDisp, tx, ty)
                } finally {
                    resumeHoverAfterTouch()
                }
                if (!ok) {
                    ok = PortalPadAccessibilityService.instance?.dispatchTap(tDisp, tx, ty) == true
                }
                debugToast(
                    if (ok) "Left click → Shizuku display=$tDisp (${tx.toInt()},${ty.toInt()})"
                    else "Left click → Shizuku tap returned false"
                )
                // Re-pin IME so it doesn't bounce displays after the click.
                repinImePolicy()
            }
            is ClickBackend.Shell -> {
                if (!backend.access.isReady) {
                    debugToast("Left click BLOCKED — ${backend.displayName} not ready")
                    Log.w(TAG, "leftClick: ${backend.displayName} not ready")
                    return@safe
                }
                debugToast("Left click → ${backend.displayName} display=$tDisp (${tx.toInt()},${ty.toInt()})")
                backend.access.execShell("input ${targetDisplayFlag(tDisp)}tap ${tx.toInt()} ${ty.toInt()}")
            }
        }
    }

    /**
     * Tap at an EXPLICIT point on the external display (not the cursor), via the
     * same proven backend path as [leftClick]. Used to raise a freeform window
     * by tapping its caption strip — the one reliable way to reorder freeform
     * windows on Samsung (move-to-front is a no-op there). No haptic, doesn't
     * move the cursor.
     */
    fun tapAt(x: Float, y: Float) = safe {
        val (tDisp, tx, ty) = resolveTargetDisplayAndCoords(x, y)
        val ix = tx.toInt(); val iy = ty.toInt()
        when (val backend = PortalPadApp.instance.clickBackend) {
            is ClickBackend.ShizukuUserService -> {
                if (!backend.backend.isReady) return@safe
                pauseHoverForTouch(x, y)
                val ok = try {
                    backend.backend.tap(tDisp, tx, ty)
                } finally {
                    resumeHoverAfterTouch()
                }
                if (!ok) {
                    PortalPadAccessibilityService.instance?.dispatchTap(tDisp, tx, ty)
                }
                repinImePolicy()
            }
            is ClickBackend.Shell -> {
                if (!backend.access.isReady) return@safe
                backend.access.execShell("input ${targetDisplayFlag(tDisp)}tap $ix $iy")
            }
        }
    }

    /**
     * Right-click. Long-press on TOUCHSCREEN source — most apps interpret this
     * as a context-menu request (where supported) or fall back to the standard
     * long-press behavior (which on text fields is select-word). Mouse
     * BUTTON_SECONDARY would be semantically cleaner but most mobile apps
     * don't react to mouse-button events at all, so we use the touch path
     * that universally works.
     *
     * Like leftClick, we send HOVER_EXIT first so any in-flight mouse-hover
     * state is cleared before the touch sequence — otherwise Android's input
     * dispatcher may reject or misroute the touch.
     */
    fun rightClick() = rightClickInternal(emitHaptic = true)

    /** Gesture Pointer already emits its firm hold-confirmation at activation. */
    fun rightClickWithoutHaptic() = rightClickInternal(emitHaptic = false)

    private fun rightClickInternal(emitHaptic: Boolean) = safe {
        // A hold over the keyboard's seek buttons already streamed continuous
        // seeking - the release-time hold classification must not ALSO fire a
        // long-press tap there (it would land as one extra seek step).
        if (kbSeekHoldDir != 0 ||
            android.os.SystemClock.uptimeMillis() - kbSeekHoldStamp < 600
        ) {
            return@safe
        }
        if (emitHaptic) clickHaptic()
        // If the cursor is over PortalPad's own dock overlay, handle the
        // right-click IN-PROCESS (open the item menu) and skip injection — an
        // injected long-press reaches the dock as a tap and would just launch.
        // Folder popup first: it floats over everything (including the dock's
        // expanded window), so while one is open the hold belongs to it.
        if (folderWindowsOpen > 0) {
            _folderRightClickTick.tryEmit(android.os.SystemClock.uptimeMillis())
            return@safe
        }
        if (cursorOverDock) {
            _dockRightClickTick.tryEmit(android.os.SystemClock.uptimeMillis())
            return@safe
        }
        // Same in-process handling for the widget overlay (see the flag's doc):
        // an injected long-press would land as a tap, so route the hold to the
        // overlay's edit-mode toggle instead of injecting.
        if (cursorOverWidgetOverlay) {
            _widgetOverlayRightClickTick.tryEmit(android.os.SystemClock.uptimeMillis())
            return@safe
        }
        val x = cursorX.toInt(); val y = cursorY.toInt()
        if (isTopBarSurfaceAt(x.toFloat(), y.toFloat())) {
            Log.d(TAG, "Right click CONSUMED PortalPad top-bar surface ($x,$y)")
            return@safe
        }
        if (shouldConsumePixelWallpaperInput(x.toFloat(), y.toFloat())) {
            Log.d(TAG, "Right click CONSUMED Pixel wallpaper display=$displayId ($x,$y)")
            return@safe
        }
        val (tDisp, tx, ty) = resolveTargetDisplayAndCoords(x.toFloat(), y.toFloat())
        val backendReady = activeBackendReady()
        // Accessibility fallback (see leftClick): on an untrusted display, dispatch
        // the long-press through the a11y framework so it lands in foreign apps.
        if (displayId != 0 && !(mirrorTargetDisplayZero && backendReady) && (!usingVd || !backendReady)) {
            val svc = PortalPadAccessibilityService.instance
            if (svc != null) {
                // Park any injected hover first — see leftClick.
                sendHoverExitViaBackend(x.toFloat(), y.toFloat())
                if (svc.dispatchLongPress(tDisp, tx, ty, 600L)) {
                    debugToast("Right click → a11y long-press display=$tDisp (${tx.toInt()},${ty.toInt()})")
                    return@safe
                }
            }
        }
        val backend = PortalPadApp.instance.clickBackend

        when (backend) {
            is ClickBackend.ShizukuUserService -> {
                if (!backend.backend.isReady) {
                    debugToast("Right click BLOCKED — Shizuku UserService not bound")
                    return@safe
                }
                pauseHoverForTouch(x.toFloat(), y.toFloat())
                var ok = try {
                    backend.backend.longPress(tDisp, tx, ty, 1200L)
                } finally {
                    resumeHoverAfterTouch()
                }
                if (!ok) {
                    ok = PortalPadAccessibilityService.instance?.dispatchLongPress(tDisp, tx, ty, 600L) == true
                }
                debugToast(
                    if (ok) "Right click → Shizuku (touch long-press) display=$tDisp"
                    else "Right click → Shizuku long-press returned false"
                )
                repinImePolicy()
            }
            is ClickBackend.Shell -> {
                if (!backend.access.isReady) {
                    debugToast("Right click BLOCKED — ${backend.displayName} not ready")
                    return@safe
                }
                debugToast("Right click → ${backend.displayName} display=$tDisp (${tx.toInt()},${ty.toInt()})")
                backend.access.execShell("input ${targetDisplayFlag(tDisp)}swipe ${tx.toInt()} ${ty.toInt()} ${tx.toInt()} ${ty.toInt()} 1200")
            }
        }
    }

    /**
     * Complete a double-tap. This is reached ONLY after the first tap of the
     * pair already fired as an eager [leftClick] (see the single-tap path in
     * TrackpadSurface). So a double-tap is finished by injecting exactly ONE
     * more tap through the SAME proven path as leftClick — hover-exit → touch
     * tap → repin — not a fresh pair of raw taps. Two clean taps separated by
     * the user's real inter-tap gap read as a proper double-tap in apps; the
     * previous two back-to-back taps with NO hover-exit were the ones the
     * dispatcher dropped, which is why double-taps landed as singles or were
     * ignored, and why one physical double-tap injected three taps total.
     */
    fun doubleClick() = safe {
        clickHaptic()
        val x = cursorX.toInt(); val y = cursorY.toInt()
        if (displayId != 0) {
            PortalPadApp.instance.lastGlassesTapAt = android.os.SystemClock.elapsedRealtime()
        }
        // The eager first tap already activated and consumed the PortalPad
        // control. Do not dispatch the double-tap completion underneath a bar
        // that may have hidden itself after that action.
        if (
            isTopBarSurfaceAt(x.toFloat(), y.toFloat()) ||
            topBarSurfaceClickWasRecent()
        ) {
            Log.d(TAG, "Double click CONSUMED after PortalPad top-bar surface ($x,$y)")
            return@safe
        }
        emitClickEvent(x.toFloat(), y.toFloat())
        if (shouldConsumePixelWallpaperInput(x.toFloat(), y.toFloat())) {
            Log.d(TAG, "Double click CONSUMED Pixel wallpaper display=$displayId ($x,$y)")
            return@safe
        }
        val (tDisp, tx, ty) = resolveTargetDisplayAndCoords(x.toFloat(), y.toFloat())
        val backendReady = activeBackendReady()
        if (displayId != 0 && !(mirrorTargetDisplayZero && backendReady) && (!usingVd || !backendReady)) {
            val svc = PortalPadAccessibilityService.instance
            if (svc != null) {
                sendHoverExitViaBackend(x.toFloat(), y.toFloat())
                if (svc.dispatchDoubleTap(tDisp, tx, ty)) {
                    debugToast("Double click → a11y double-tap display=$tDisp (${tx.toInt()},${ty.toInt()})")
                    return@safe
                }
            }
        }
        val backend = PortalPadApp.instance.clickBackend

        when (backend) {
            is ClickBackend.ShizukuUserService -> {
                if (!backend.backend.isReady) {
                    debugToast("Double click BLOCKED — Shizuku UserService not bound")
                    return@safe
                }
                pauseHoverForTouch(x.toFloat(), y.toFloat())
                var ok = try {
                    backend.backend.tap(tDisp, tx, ty)
                } finally {
                    resumeHoverAfterTouch()
                }
                if (!ok) {
                    ok = PortalPadAccessibilityService.instance?.dispatchTap(tDisp, tx, ty) == true
                }
                debugToast(
                    if (ok) "Double click → Shizuku display=$tDisp (${tx.toInt()},${ty.toInt()})"
                    else "Double click → Shizuku tap returned false"
                )
                repinImePolicy()
            }
            is ClickBackend.Shell -> {
                if (!backend.access.isReady) {
                    debugToast("Double click BLOCKED — ${backend.displayName} not ready")
                    return@safe
                }
                debugToast("Double click → ${backend.displayName} display=$tDisp (${tx.toInt()},${ty.toInt()})")
                backend.access.execShell("input ${targetDisplayFlag(tDisp)}tap ${tx.toInt()} ${ty.toInt()}")
            }
        }
    }

    // Scroll remainder accumulation — small/slow scroll deltas that don't yet
    // amount to a real swipe are KEPT and added to the next ones, instead of
    // being thrown away. That dropping is what made scrolling choppy at 1.0x
    // (sub-threshold steps discarded) yet smooth at 1.5x (steps big enough to
    // clear the threshold every time).
    private var scrollAccumX = 0f
    private var scrollAccumY = 0f
    private var lastScrollAt = 0L

    /** Scroll via short swipe — same backend logic as click. */
    fun scroll(dx: Float, dy: Float) = safe {
        val mult = scrollSpeed * if (invertScroll) -1f else 1f
        val now = SystemClock.uptimeMillis()
        // A pause resets the accumulator so a later flick doesn't combine with a
        // stale leftover from a previous, unrelated scroll.
        if (now - lastScrollAt > 250) { scrollAccumX = 0f; scrollAccumY = 0f }
        lastScrollAt = now
        scrollAccumX += dx * mult
        scrollAccumY += dy * mult
        val plan = computeScrollSwipePlan(
            cursorX = cursorX,
            cursorY = cursorY,
            rawDeltaX = -scrollAccumX * 2f,
            rawDeltaY = -scrollAccumY * 2f,
            displayWidth = displayWidth,
            displayHeight = displayHeight,
            minTravel = 24.0,
        ) ?: return@safe
        // The accumulated motion is being delivered as this swipe — clear it.
        scrollAccumX = 0f
        scrollAccumY = 0f
        if (plan.pageScrollDeltaY != 0f) notifyPageScroll(plan.pageScrollDeltaY)
        if (useA11y("scroll")) {
            val (tDisp, tsx, tsy) = resolveTargetDisplayAndCoords(plan.startX, plan.startY)
            val (_, tex, tey) = resolveTargetDisplayAndCoords(plan.endX, plan.endY)
            // Feed the swipe vector to the paced/coalesced a11y scroller — one
            // gesture in flight at a time, so swipes don't cancel each other.
            PortalPadAccessibilityService.instance?.scrollGesture(
                tDisp, tsx, tsy,
                tex - tsx, tey - tsy,
                displayWidth, displayHeight,
            )
            return@safe
        }
        val (tDisp, tsx, tsy) = resolveTargetDisplayAndCoords(plan.startX, plan.startY)
        val (_, tex, tey) = resolveTargetDisplayAndCoords(plan.endX, plan.endY)
        val backend = PortalPadApp.instance.clickBackend

        when (backend) {
            is ClickBackend.ShizukuUserService -> {
                if (!backend.backend.isReady) return@safe
                backend.backend.swipe(
                    tDisp, tsx, tsy,
                    tex, tey, durationMs = 80L,
                )
            }
            is ClickBackend.Shell -> {
                if (!backend.access.isReady) return@safe
                backend.access.execShell(
                    "input ${targetDisplayFlag(tDisp)}swipe ${tsx.toInt()} ${tsy.toInt()} ${tex.toInt()} ${tey.toInt()} 80",
                )
            }
        }
    }

    /**
     * Mouse-wheel scroll via a discrete ACTION_SCROLL event at the cursor — no
     * fling/momentum, unlike [scrollPixels]'s swipe. [vNotches] +1 = up.
     */
    fun wheelScroll(vNotches: Float) = safe {
        if (vNotches != 0f) notifyPageScroll(vNotches * 40f)
        val be = PortalPadApp.instance.activeBoundBackend ?: return@safe
        if (!be.isReady) return@safe
        lastInjectedScrollAtMs = SystemClock.uptimeMillis()
        val (tDisp, tx, ty) = resolveTargetDisplayAndCoords(cursorX, cursorY)
        be.injectScroll(tx, ty, vNotches, 0f, tDisp)
    }

    /** Horizontal wheel. The backend has always accepted an h argument; nothing
     *  ever passed one because REL_HWHEEL was not captured, so the fourth
     *  parameter above sat hardcoded at 0f. */
    fun wheelScrollHorizontal(hNotches: Float) = safe {
        val be = PortalPadApp.instance.activeBoundBackend ?: return@safe
        if (!be.isReady) return@safe
        val (tDisp, tx, ty) = resolveTargetDisplayAndCoords(cursorX, cursorY)
        be.injectScroll(tx, ty, 0f, hNotches, tDisp)
    }

    /**
     * Scroll by an explicit pixel delta from the cursor, WITHOUT the trackpad's
     * scrollSpeed / invertScroll (the external mouse manages its own speed and
     * direction). A positive [dy] swipes the finger downward, which scrolls page
     * content UP (toward the top) — matching a standard mouse wheel-up.
     */
    fun scrollPixels(dx: Float, dy: Float) = safe {
        val plan = computeScrollSwipePlan(
            cursorX = cursorX,
            cursorY = cursorY,
            rawDeltaX = dx,
            rawDeltaY = dy,
            displayWidth = displayWidth,
            displayHeight = displayHeight,
            minTravel = 8.0,
        ) ?: return@safe
        if (plan.pageScrollDeltaY != 0f) notifyPageScroll(plan.pageScrollDeltaY)
        val (tDisp, tsx, tsy) = resolveTargetDisplayAndCoords(plan.startX, plan.startY)
        val (_, tex, tey) = resolveTargetDisplayAndCoords(plan.endX, plan.endY)
        when (val backend = PortalPadApp.instance.clickBackend) {
            is ClickBackend.ShizukuUserService -> {
                if (!backend.backend.isReady) return@safe
                backend.backend.swipe(
                    tDisp, tsx, tsy,
                    tex, tey, durationMs = 80L,
                )
            }
            is ClickBackend.Shell -> {
                if (!backend.access.isReady) return@safe
                backend.access.execShell(
                    "input ${targetDisplayFlag(tDisp)}swipe ${tsx.toInt()} ${tsy.toInt()} ${tex.toInt()} ${tey.toInt()} 80",
                )
            }
        }
    }

    // ── Remote D-pad "Scroll" mode ────────────────────────────────────────
    // In Scroll mode the remote's D-pad arrows fire a directional swipe anchored
    // at the centre of the external display (for apps that ignore D-pad keys),
    // and OK becomes a tap / long-press at that centre. Mirrors the backend
    // dispatch used by scroll() / tapAt() / rightClick().
    private fun remoteSwipe(
        dx: Float,
        dy: Float,
        durationMs: Long = 180L,
    ) = safe {
        val cx = displayWidth / 2f
        val cy = displayHeight / 2f
        val ex = (cx + dx).coerceIn(0f, (displayWidth - 1).toFloat())
        val ey = (cy + dy).coerceIn(0f, (displayHeight - 1).toFloat())
        val (tDisp, tsx, tsy) = resolveTargetDisplayAndCoords(cx, cy)
        val (_, tex, tey) = resolveTargetDisplayAndCoords(ex, ey)
        when (val backend = PortalPadApp.instance.clickBackend) {
            is ClickBackend.ShizukuUserService -> {
                if (!backend.backend.isReady) return@safe
                backend.backend.swipe(tDisp, tsx, tsy, tex, tey, durationMs = durationMs)
            }
            is ClickBackend.Shell -> {
                if (!backend.access.isReady) return@safe
                backend.access.execShell(
                    "input ${targetDisplayFlag(tDisp)}swipe ${tsx.toInt()} ${tsy.toInt()} " +
                        "${tex.toInt()} ${tey.toInt()} $durationMs",
                )
            }
        }
    }

    /**
     * One-shot Remote scrolls are intentionally short and slow enough to avoid
     * a large fling. This makes carousels and compact lists (Netflix, streaming
     * launchers, etc.) practical to navigate one controlled movement at a time.
     * "Down" reveals content below, so the injected finger travels upward.
     */
    private fun remoteScrollScale(strength: Float): Float =
        strength.coerceIn(0.65f, 1.15f)

    private fun remoteScrollDuration(strength: Float): Long {
        val normalized = (remoteScrollScale(strength) - 0.65f) / 0.50f
        return (280f - normalized * 100f).roundToLong().coerceIn(180L, 280L)
    }

    fun remoteScrollDown(strength: Float = 1f) {
        notifyPageScroll(-40f * strength.coerceIn(0.65f, 1.15f))
        val scale = remoteScrollScale(strength)
        remoteSwipe(0f, -displayHeight / 8f * scale, remoteScrollDuration(strength))
    }

    fun remoteScrollUp(strength: Float = 1f) {
        notifyPageScroll(40f * strength.coerceIn(0.65f, 1.15f))
        val scale = remoteScrollScale(strength)
        remoteSwipe(0f, displayHeight / 8f * scale, remoteScrollDuration(strength))
    }

    fun remoteScrollRight(strength: Float = 1f) {
        val scale = remoteScrollScale(strength)
        remoteSwipe(-displayWidth / 8f * scale, 0f, remoteScrollDuration(strength))
    }

    fun remoteScrollLeft(strength: Float = 1f) {
        val scale = remoteScrollScale(strength)
        remoteSwipe(displayWidth / 8f * scale, 0f, remoteScrollDuration(strength))
    }

    /**
     * Held Remote scrolls use small wheel deltas at the display center. Unlike
     * repeatedly injecting touch swipes, these add no fling momentum and stop
     * as soon as the phone-side hold ends. The 0.36 notch paired with the
     * gesture surface's 60ms repeat is modestly faster than the original
     * 0.32/70ms cadence without creating an asynchronous swipe backlog.
     */
    private fun remoteHeldScroll(vNotches: Float, hNotches: Float) = safe {
        if (vNotches != 0f) notifyPageScroll(vNotches * 40f)
        val backend = PortalPadApp.instance.activeBoundBackend
        if (backend != null && backend.isReady) {
            val (tDisp, tx, ty) = resolveTargetDisplayAndCoords(
                displayWidth / 2f,
                displayHeight / 2f,
            )
            backend.injectScroll(
                tx,
                ty,
                vNotches,
                hNotches,
                tDisp,
            )
        }
    }

    fun remoteHeldScrollDown() = remoteHeldScroll(vNotches = -0.36f, hNotches = 0f)
    fun remoteHeldScrollUp() = remoteHeldScroll(vNotches = 0.36f, hNotches = 0f)
    fun remoteHeldScrollRight() = remoteHeldScroll(vNotches = 0f, hNotches = 0.36f)
    fun remoteHeldScrollLeft() = remoteHeldScroll(vNotches = 0f, hNotches = -0.36f)

    // Live Remote > Gesture > Swipe touch. This is deliberately separate from
    // the cursor/window drag state: it never moves the PortalPad cursor or
    // participates in resize/caption hit testing.
    @Volatile private var remoteTouchDragActive = false
    @Volatile private var remoteTouchDragDownTime = 0L
    @Volatile private var remoteTouchDragDisplayId = 0
    @Volatile private var remoteTouchDragX = 0f
    @Volatile private var remoteTouchDragY = 0f
    @Volatile private var remoteTouchDragLeft = 0f
    @Volatile private var remoteTouchDragTop = 0f
    @Volatile private var remoteTouchDragRight = 0f
    @Volatile private var remoteTouchDragBottom = 0f
    @Volatile private var remoteTouchDragBackend: BoundShellBackend? = null

    /**
     * Begin a literal touchscreen gesture in the foreground app once the phone
     * movement crosses Swipe's direction threshold. The accumulated phone
     * displacement is mapped proportionally into that app, then subsequent MOVE
     * events follow the finger until release. Returns false when the bound
     * privileged backend is unavailable; the UI then falls back to the bounded
     * one-shot swipe.
     */
    @Synchronized
    fun remoteTouchDragBegin(initialDxFraction: Float, initialDyFraction: Float): Boolean {
        if (remoteTouchDragActive) remoteTouchDragEnd()
        val backend = PortalPadApp.instance.activeBoundBackend
            ?.takeIf { it.isReady }
            ?: return false
        return try {
            val appBounds = PortalPadAccessibilityService.instance
                ?.let { runCatching { it.foregroundAppWindowBounds(displayId) }.getOrNull() }
            val left = appBounds?.left?.toFloat() ?: 0f
            val top = appBounds?.top?.toFloat() ?: 0f
            val right = appBounds?.right?.toFloat() ?: displayWidth.toFloat()
            val bottom = appBounds?.bottom?.toFloat() ?: displayHeight.toFloat()
            val width = (right - left).coerceAtLeast(1f)
            val height = (bottom - top).coerceAtLeast(1f)
            // A small inset keeps a continued drag from leaking into a window
            // resize edge, caption control, or the display boundary.
            val insetX = (width * 0.04f)
                .coerceAtMost(32f)
                .coerceAtMost(width / 2f)
            val insetY = (height * 0.04f)
                .coerceAtMost(32f)
                .coerceAtMost(height / 2f)
            remoteTouchDragLeft = left + insetX
            remoteTouchDragTop = top + insetY
            remoteTouchDragRight = right - insetX
            remoteTouchDragBottom = bottom - insetY

            val cx = left + width / 2f
            val cy = top + height / 2f
            // Direction may lock after one unusually large phone event. Cap
            // that first external MOVE more tightly than the old delayed hold
            // path so a fast initial sample cannot recreate a multi-card fling.
            // Later live MOVE events still follow the finger at the UI's damped
            // gain.
            val offsetX = initialDxFraction.coerceIn(-0.12f, 0.12f) * width
            val offsetY = initialDyFraction.coerceIn(-0.12f, 0.12f) * height
            val sx = (cx - offsetX / 2f).coerceIn(remoteTouchDragLeft, remoteTouchDragRight)
            val sy = (cy - offsetY / 2f).coerceIn(remoteTouchDragTop, remoteTouchDragBottom)
            val ex = (cx + offsetX / 2f).coerceIn(remoteTouchDragLeft, remoteTouchDragRight)
            val ey = (cy + offsetY / 2f).coerceIn(remoteTouchDragTop, remoteTouchDragBottom)
            val now = SystemClock.uptimeMillis()

            pauseHoverForTouch(sx, sy)
            if (
                !backend.pointer(
                    action = MotionEvent.ACTION_DOWN,
                    x = sx,
                    y = sy,
                    displayId = displayId,
                    source = InputDevice.SOURCE_TOUCHSCREEN,
                    buttonState = 0,
                    downTime = now,
                )
            ) {
                resumeHoverAfterTouch()
                return false
            }

            remoteTouchDragActive = true
            remoteTouchDragDownTime = now
            remoteTouchDragDisplayId = displayId
            remoteTouchDragBackend = backend
            remoteTouchDragX = ex
            remoteTouchDragY = ey
            backend.pointer(
                action = MotionEvent.ACTION_MOVE,
                x = ex,
                y = ey,
                displayId = displayId,
                source = InputDevice.SOURCE_TOUCHSCREEN,
                buttonState = 0,
                downTime = now,
            )
            Log.d(
                TAG,
                "remote swipe drag BEGIN display=$displayId " +
                    "(${sx.toInt()},${sy.toInt()}) -> (${ex.toInt()},${ey.toInt()})",
            )
            true
        } catch (t: Throwable) {
            remoteTouchDragActive = false
            remoteTouchDragDownTime = 0L
            remoteTouchDragBackend = null
            resumeHoverAfterTouch()
            Log.e(TAG, "remote swipe drag begin failed", t)
            false
        }
    }

    /** Continue the live touch using phone-surface movement normalized to the
     *  gesture pad. Both axes follow the finger after the initial direction is
     *  recognized, which keeps sliders, maps, and other draggables natural. */
    @Synchronized
    fun remoteTouchDragMove(dxFraction: Float, dyFraction: Float) {
        if (!remoteTouchDragActive) return
        val backend = remoteTouchDragBackend
        if (backend == null || !backend.isReady) {
            remoteTouchDragEnd()
            return
        }
        try {
            val width = (remoteTouchDragRight - remoteTouchDragLeft).coerceAtLeast(1f)
            val height = (remoteTouchDragBottom - remoteTouchDragTop).coerceAtLeast(1f)
            val nextX = (remoteTouchDragX + dxFraction * width)
                .coerceIn(remoteTouchDragLeft, remoteTouchDragRight)
            val nextY = (remoteTouchDragY + dyFraction * height)
                .coerceIn(remoteTouchDragTop, remoteTouchDragBottom)
            if (kotlin.math.abs(nextX - remoteTouchDragX) < 0.25f &&
                kotlin.math.abs(nextY - remoteTouchDragY) < 0.25f
            ) {
                return
            }
            remoteTouchDragX = nextX
            remoteTouchDragY = nextY
            if (dyFraction != 0f) notifyPageScroll(dyFraction * height)
            backend.pointer(
                action = MotionEvent.ACTION_MOVE,
                x = nextX,
                y = nextY,
                displayId = remoteTouchDragDisplayId,
                source = InputDevice.SOURCE_TOUCHSCREEN,
                buttonState = 0,
                downTime = remoteTouchDragDownTime,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "remote swipe drag move failed", t)
            remoteTouchDragEnd()
        }
    }

    /** Lift the live external finger. Safe and idempotent for gesture
     * cancellation, a second finger, activity teardown, and display changes. */
    @Synchronized
    fun remoteTouchDragEnd() {
        if (!remoteTouchDragActive) return
        val backend = remoteTouchDragBackend
        val x = remoteTouchDragX
        val y = remoteTouchDragY
        val targetDisplay = remoteTouchDragDisplayId
        val touchDownTime = remoteTouchDragDownTime
        try {
            if (backend?.isReady == true) {
                backend.pointer(
                    action = MotionEvent.ACTION_UP,
                    x = x,
                    y = y,
                    displayId = targetDisplay,
                    source = InputDevice.SOURCE_TOUCHSCREEN,
                    buttonState = 0,
                    downTime = touchDownTime,
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "remote swipe drag end failed", t)
        } finally {
            remoteTouchDragActive = false
            remoteTouchDragDownTime = 0L
            remoteTouchDragBackend = null
            resumeHoverAfterTouch()
        }
        Log.d(TAG, "remote swipe drag END display=$targetDisplay (${x.toInt()},${y.toInt()})")
    }

    /**
     * Literal touch swipe for Remote > Gesture > Swipe. Unlike Scroll, the
     * injected finger travels in the same direction as the user's finger. Use
     * a short, distance-timed stroke so app carousels move deliberately instead
     * of interpreting it as a high-momentum fling. The path stays inside the
     * foreground freeform window when accessibility can resolve it, with the
     * full display as a safe fallback.
     */
    private fun remoteTouchSwipe(
        horizontal: Int,
        vertical: Int,
        strength: Float,
    ) = safe {
        if (vertical != 0) notifyPageScroll(vertical * 40f)
        val appBounds = PortalPadAccessibilityService.instance
            ?.let { runCatching { it.foregroundAppWindowBounds(displayId) }.getOrNull() }
        val left = appBounds?.left?.toFloat() ?: 0f
        val top = appBounds?.top?.toFloat() ?: 0f
        val right = appBounds?.right?.toFloat() ?: displayWidth.toFloat()
        val bottom = appBounds?.bottom?.toFloat() ?: displayHeight.toFloat()
        val width = (right - left).coerceAtLeast(1f)
        val height = (bottom - top).coerceAtLeast(1f)
        val cx = left + width / 2f
        val cy = top + height / 2f
        // Finger distance and speed choose a narrow 14–20% travel range.
        // Strong clamping keeps a fast phone swipe from becoming the large
        // multi-item fling that the controlled Swipe preset is meant to avoid.
        val travelFraction = (0.18f + (strength - 1f) * 0.10f).coerceIn(0.14f, 0.20f)
        val travelX = width * travelFraction
        val travelY = height * travelFraction
        val travel = if (horizontal != 0) travelX else travelY
        val speedScale = strength.coerceIn(0.80f, 1.20f)
        val durationMs = (travel / 0.7f / speedScale)
            .roundToLong()
            .coerceIn(220L, 420L)
        val sx = cx - horizontal * travelX / 2f
        val sy = cy - vertical * travelY / 2f
        val ex = cx + horizontal * travelX / 2f
        val ey = cy + vertical * travelY / 2f
        val (tDisp, tsx, tsy) = resolveTargetDisplayAndCoords(sx, sy)
        val (_, tex, tey) = resolveTargetDisplayAndCoords(ex, ey)
        when (val backend = PortalPadApp.instance.clickBackend) {
            is ClickBackend.ShizukuUserService -> {
                if (!backend.backend.isReady) return@safe
                backend.backend.swipe(tDisp, tsx, tsy, tex, tey, durationMs = durationMs)
            }
            is ClickBackend.Shell -> {
                if (!backend.access.isReady) return@safe
                backend.access.execShell(
                    "input ${targetDisplayFlag(tDisp)}swipe ${tsx.toInt()} ${tsy.toInt()} " +
                        "${tex.toInt()} ${tey.toInt()} $durationMs",
                )
            }
        }
    }

    fun remoteTouchSwipeUp(strength: Float = 1f) =
        remoteTouchSwipe(horizontal = 0, vertical = -1, strength = strength)

    fun remoteTouchSwipeDown(strength: Float = 1f) =
        remoteTouchSwipe(horizontal = 0, vertical = 1, strength = strength)

    fun remoteTouchSwipeLeft(strength: Float = 1f) =
        remoteTouchSwipe(horizontal = -1, vertical = 0, strength = strength)

    fun remoteTouchSwipeRight(strength: Float = 1f) =
        remoteTouchSwipe(horizontal = 1, vertical = 0, strength = strength)

    /** Tap point for the scroll-mode / reader center tap: the centre of the
     *  foreground app window on the injection display, or the display centre when
     *  that can't be resolved (a11y unbound, no app window, empty bounds). Never
     *  worse than the plain display-centre tap — only more accurate for a freeform
     *  window, where the app isn't at display centre. Cheap: in-memory a11y snapshot. */
    private fun centerTapPoint(): Pair<Float, Float> {
        val c = com.portalpad.app.service.PortalPadAccessibilityService.instance
            ?.let { runCatching { it.foregroundAppWindowCenter(displayId) }.getOrNull() }
        return c ?: (displayWidth / 2f to displayHeight / 2f)
    }

    /** Scroll-mode / reader OK: a tap at the centre of the foreground app window
     *  on the external display (display centre as fallback). Also what the Reader
     *  "Exit Zoom" button sends — a tap is what dismisses Kindle's zoom overlay. */
    fun remoteCenterTap() {
        val (x, y) = centerTapPoint()
        tapAt(x, y)
    }

    /** Scroll-mode long-press OK: a touch long-press at the centre. */
    fun remoteCenterLongPress() = safe {
        val (x, y) = centerTapPoint()
        val (tDisp, tx, ty) = resolveTargetDisplayAndCoords(x, y)
        when (val backend = PortalPadApp.instance.clickBackend) {
            is ClickBackend.ShizukuUserService -> {
                if (!backend.backend.isReady) return@safe
                val ok = backend.backend.longPress(tDisp, tx, ty, 1200L)
                if (!ok) {
                    PortalPadAccessibilityService.instance?.dispatchLongPress(tDisp, tx, ty, 650L)
                }
            }
            is ClickBackend.Shell -> {
                if (!backend.access.isReady) return@safe
                backend.access.execShell(
                    "input ${targetDisplayFlag(tDisp)}swipe ${tx.toInt()} ${ty.toInt()} ${tx.toInt()} ${ty.toInt()} 1200",
                )
            }
        }
    }

    private fun setEventDisplay(event: MotionEvent) {
        if (displayId == 0) return
        runCatching {
            MotionEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
                .invoke(event, displayId)
        }
    }

    private fun displayFlag(): String = if (displayId != 0) "-d $displayId " else ""

    /**
     * Debug breadcrumb — when the SHOW_CLICK_TOASTS pref is on, surfaces every
     * click attempt as a short toast on the phone screen. Lets the user verify
     * whether clicks are being processed at all, and at what coordinates,
     * without needing to run logcat.
     */
    private fun debugToast(message: String) {
        // Always log to logcat so the in-app logcat viewer captures it
        // regardless of the toast pref.
        Log.d(TAG, message)
        // Toast is gated by the SHOW_CLICK_TOASTS pref.
        val show = runCatching {
            kotlinx.coroutines.runBlocking {
                com.portalpad.app.PortalPadApp.instance.prefs.bool(
                    com.portalpad.app.data.PreferencesRepository.Keys.SHOW_CLICK_TOASTS,
                    default = false,
                ).first()
            }
        }.getOrDefault(false)
        if (!show) return
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching {
                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ─────────────────────────── legacy touchscreen tap ───────────────────────────

    fun tap(x: Float, y: Float) = safe {
        val now = SystemClock.uptimeMillis()
        downTime = now
        sendTouchscreen(MotionEvent.ACTION_DOWN, x, y)
        sendTouchscreen(MotionEvent.ACTION_UP, x, y)
    }

    private fun sendTouchscreen(action: Int, x: Float, y: Float) {
        if (!access.canInjectInProcess) {
            if (access.isReady && action == MotionEvent.ACTION_UP) {
                runCatching {
                    access.execShell("input ${displayFlag()}tap ${x.toInt()} ${y.toInt()}")
                }
            }
            return
        }
        val event = try {
            MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0).also {
                it.source = InputDevice.SOURCE_TOUCHSCREEN
                setEventDisplay(it)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "MotionEvent.obtain failed for touchscreen", t)
            return
        }
        try {
            injectInProcess(event)
        } catch (t: Throwable) {
            Log.w(TAG, "touchscreen inject failed", t)
            if (access.isReady && action == MotionEvent.ACTION_UP) {
                runCatching {
                    access.execShell("input ${displayFlag()}tap ${x.toInt()} ${y.toInt()}")
                }
            }
        } finally {
            event.recycle()
        }
    }

    // ─────────────────────────── keys ───────────────────────────

    fun pressKey(keycode: Int, repin: Boolean = true) =
        pressKeyInternal(keycode, repin, emitHaptic = true)

    private fun pressKeyInternal(
        keycode: Int,
        repin: Boolean,
        emitHaptic: Boolean,
    ) = safe {
        if (emitHaptic) clickHaptic()
        // Run the actual injection off the UI thread so the button's visual
        // feedback stays instant while the key is delivered in the background.
        val backend = PortalPadApp.instance.clickBackend
        val targetDisplay = displayId
        val tapNs = System.nanoTime()
        keyExecutor.execute {
            val startNs = System.nanoTime()
            // Guard the whole injection: when Shizuku's binder is dead, the IPC
            // calls below (backend.key / execShell) throw DeadObjectException /
            // RemoteException on this background thread — an uncaught throw here
            // is FATAL and crashes PortalPad. Swallow it so a key press during a
            // Shizuku outage is a no-op instead of a crash (the on-screen banner
            // already tells the user Shizuku is down).
            try {
                // KEYDIAG-ROUTE: which lane actually carried this key. Added
                // 2026-07-25 — the "KEY <code>" line below prints even when NO
                // branch injected anything (helper unbound AND shell not
                // ready), so it was never proof of delivery. Pixel D-pad hunt.
                when (backend) {
                    is ClickBackend.ShizukuUserService -> {
                        if (backend.backend.isReady) {
                            val ok = backend.backend.key(targetDisplay, keycode)
                            Log.w(TAG, "KEYDIAG-ROUTE userService key=$keycode disp=$targetDisplay ok=$ok")
                            if (!ok && access.isReady) {
                                Log.w(TAG, "KEYDIAG-ROUTE shell-fallback key=$keycode disp=$targetDisplay")
                                access.execShell("input ${displayFlag()}keyevent $keycode")
                            }
                        } else if (access.isReady) {
                            Log.w(TAG, "KEYDIAG-ROUTE shell (helper unbound) key=$keycode disp=$targetDisplay")
                            access.execShell("input ${displayFlag()}keyevent $keycode")
                        } else {
                            Log.w(TAG, "KEYDIAG-ROUTE DROPPED key=$keycode — helper unbound AND shell not ready")
                        }
                    }
                    is ClickBackend.Shell -> {
                        if (access.isReady) {
                            Log.w(TAG, "KEYDIAG-ROUTE shell-backend key=$keycode disp=$targetDisplay")
                            access.execShell("input ${displayFlag()}keyevent $keycode")
                        } else {
                            Log.w(TAG, "KEYDIAG-ROUTE DROPPED key=$keycode — shell backend not ready")
                        }
                    }
                }
                // Re-pin IME so a key event doesn't bounce the IME display — but
                // ONLY for text-input keys. D-pad, media, volume, and nav keys
                // pass repin=false: they don't affect IME placement, and the
                // re-pin (a blocking DataStore read + a Shizuku IPC) added
                // noticeable latency. Skipped while the relay keyboard is active.
                if (repin && !suppressImeRepin) repinImePolicy()
            } catch (t: Throwable) {
                Log.w(TAG, "key inject failed (backend unavailable?) keycode=$keycode", t)
                return@execute
            }
            // Latency diagnostics: queue-wait (tap→worker start) + inject time.
            val doneNs = System.nanoTime()
            Log.d(
                TAG,
                "KEY $keycode queueWait=${(startNs - tapNs) / 1_000_000}ms " +
                    "inject=${(doneNs - startNs) / 1_000_000}ms " +
                    "total=${(doneNs - tapNs) / 1_000_000}ms",
            )
        }
    }

    /**
     * A complete key PRESS (down → 12ms → up) carrying [metaState], for typing
     * shifted characters from the relay keyboard: capitals and symbols like
     * @ # $ & * ( ) " : ! ? need META_SHIFT_ON on the key events themselves.
     * The 12ms gap matches the server-side injectKeyPressInternal spacing —
     * without a real gap the dispatcher silently drops occasional presses.
     * Runs on keyExecutor (12ms there costs nothing user-visible). Shell
     * fallback is degraded (input keyevent can't carry meta) and only fires
     * for meta-less presses so it can never type the WRONG character.
     */
    fun pressKeyWithMeta(keycode: Int, metaState: Int) = safe {
        val targetDisplay = displayId
        keyExecutor.execute {
            try {
                when (val backend = PortalPadApp.instance.clickBackend) {
                    is ClickBackend.ShizukuUserService -> if (backend.backend.isReady) {
                        backend.backend.injectKey(keycode, KeyEvent.ACTION_DOWN, metaState, targetDisplay)
                        try { Thread.sleep(12) } catch (_: InterruptedException) {}
                        backend.backend.injectKey(keycode, KeyEvent.ACTION_UP, metaState, targetDisplay)
                    }
                    is ClickBackend.Shell -> if (metaState == 0 && access.isReady) {
                        access.execShell("input ${displayFlag()}keyevent $keycode")
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "pressKeyWithMeta failed keycode=$keycode meta=$metaState", t)
            }
        }
    }

    /**
     * Inject a raw hardware-keyboard key edge on the external display for the
     * physical-keyboard relay (the combo device captured by the mouse pipeline).
     * Forwards the literal DOWN/UP and [metaState] (Shift/Ctrl/Alt) so capitals,
     * symbols and chords compose. No haptic (unlike pressKey — this fires once per
     * keystroke), no IME re-pin. No-op when there's no external display.
     */
    fun injectKeyEvent(keycode: Int, down: Boolean, metaState: Int) = safe {
        val targetDisplay = displayId
        if (targetDisplay != 0) {
            val action = if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
            keyExecutor.execute {
                try {
                    when (val backend = PortalPadApp.instance.clickBackend) {
                        is ClickBackend.ShizukuUserService -> {
                            if (backend.backend.isReady) {
                                backend.backend.injectKey(keycode, action, metaState, targetDisplay)
                            }
                        }
                        is ClickBackend.Shell -> {
                            // Shell `input keyevent` can't carry meta or split down/up;
                            // emit a plain press on DOWN only (degraded fallback).
                            if (down && access.isReady) {
                                access.execShell("input ${displayFlag()}keyevent $keycode")
                            }
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "injectKeyEvent failed keycode=$keycode down=$down", t)
                }
            }
        }
    }

    fun back() = pressKey(KeyEvent.KEYCODE_BACK, repin = false)
    fun backWithoutHaptic() =
        pressKeyInternal(KeyEvent.KEYCODE_BACK, repin = false, emitHaptic = false)

    /**
     * Back that won't black out the wallpaper. Pressing Back at the bare
     * wallpaper/home (no app open on this display) makes the system tear down the
     * wallpaper backdrop — there's nothing to pop, so it destroys the only layer
     * showing, leaving black. This checks for a REAL app on the display first and
     * only injects Back if one exists; at the wallpaper it's a no-op. On query
     * error we default to sending Back (don't break the primary function).
     */
    fun backGuarded() {
        keyExecutor.execute {
            val hasRealApp = runCatching {
                PortalPadApp.instance.freeform.listTasks(displayId).any { rt ->
                    !rt.packageName.contains("launcher", ignoreCase = true) &&
                        !rt.packageName.contains("nexuslauncher", ignoreCase = true) &&
                        rt.packageName != com.portalpad.app.BuildConfig.APPLICATION_ID
                }
            }.getOrDefault(true)
            if (hasRealApp) pressKey(KeyEvent.KEYCODE_BACK, repin = false)
        }
    }
    fun home() = pressKey(KeyEvent.KEYCODE_HOME, repin = false)
    fun recents() = pressKey(KeyEvent.KEYCODE_APP_SWITCH, repin = false)

    fun mediaPlayPause() = pressKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, repin = false)
    fun mediaNext() = pressKey(KeyEvent.KEYCODE_MEDIA_NEXT, repin = false)
    fun mediaPrev() = pressKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS, repin = false)
    fun mediaFf() = pressKey(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, repin = false)
    fun mediaRew() = pressKey(KeyEvent.KEYCODE_MEDIA_REWIND, repin = false)
    fun mediaStop() = pressKey(KeyEvent.KEYCODE_MEDIA_STOP, repin = false)
    fun mediaSkip10s(forward: Boolean) { if (forward) mediaFf() else mediaRew() }
    fun volUp() = pressKey(KeyEvent.KEYCODE_VOLUME_UP, repin = false)
    fun volDown() = pressKey(KeyEvent.KEYCODE_VOLUME_DOWN, repin = false)
    fun volMute() = pressKey(KeyEvent.KEYCODE_VOLUME_MUTE, repin = false)

    fun dpadUp() = pressKey(KeyEvent.KEYCODE_DPAD_UP, repin = false)
    fun dpadDown() = pressKey(KeyEvent.KEYCODE_DPAD_DOWN, repin = false)
    fun dpadLeft() = pressKey(KeyEvent.KEYCODE_DPAD_LEFT, repin = false)
    fun dpadRight() = pressKey(KeyEvent.KEYCODE_DPAD_RIGHT, repin = false)
    fun dpadUpWithoutHaptic() =
        pressKeyInternal(KeyEvent.KEYCODE_DPAD_UP, repin = false, emitHaptic = false)
    fun dpadDownWithoutHaptic() =
        pressKeyInternal(KeyEvent.KEYCODE_DPAD_DOWN, repin = false, emitHaptic = false)
    fun dpadLeftWithoutHaptic() =
        pressKeyInternal(KeyEvent.KEYCODE_DPAD_LEFT, repin = false, emitHaptic = false)
    fun dpadRightWithoutHaptic() =
        pressKeyInternal(KeyEvent.KEYCODE_DPAD_RIGHT, repin = false, emitHaptic = false)

    // Reader page-flip keys (focus-following, so they work in freeform windows).
    fun pageUp() = pressKey(KeyEvent.KEYCODE_PAGE_UP, repin = false)
    fun pageDown() = pressKey(KeyEvent.KEYCODE_PAGE_DOWN, repin = false)
    fun dpadCenter() = pressKey(KeyEvent.KEYCODE_DPAD_CENTER, repin = false)
    fun dpadCenterWithoutHaptic() =
        pressKeyInternal(KeyEvent.KEYCODE_DPAD_CENTER, repin = false, emitHaptic = false)
    fun enter() = pressKey(KeyEvent.KEYCODE_ENTER)

    // Programmable shortcut keys — visually colored Red/Green/Yellow/Blue on
    // the Remote tab, but they send the generic gamepad button keycodes
    // (BUTTON_1..4) tagged with SOURCE_GAMEPAD. Reasoning:
    //  • BUTTON_1..4 are obscure enough that nothing on Android claims them
    //    natively (unlike A/B/X/Y which some games may bind).
    //  • SOURCE_GAMEPAD routes through the gamepad input pipeline, which is
    //    more permissive to app-injected events than the keyboard pipeline.
    //  • Key Mapper / Button Mapper have first-class gamepad-button support,
    //    so these should be capturable for remapping. (Still a known wall:
    //    injected events from a synthetic device id may bypass accessibility
    //    listeners regardless — gamepad is our best shot.)
    fun progRed() = pressGamepadKey(KeyEvent.KEYCODE_BUTTON_1)
    fun progGreen() = pressGamepadKey(KeyEvent.KEYCODE_BUTTON_2)
    fun progYellow() = pressGamepadKey(KeyEvent.KEYCODE_BUTTON_3)
    fun progBlue() = pressGamepadKey(KeyEvent.KEYCODE_BUTTON_4)

    // Display-0 (phone) variants used ONLY by the floating remap helper. The
    // helper feeds a key-mapping app that runs on the PHONE, whereas normal
    // program-key taps target the external display (injector.displayId). Without
    // forcing display 0 here, the gamepad press was injected at the glasses and
    // the phone-side recorder never saw it — the cause of "remap stopped working".
    fun progRedOnPhone() = pressGamepadKey(KeyEvent.KEYCODE_BUTTON_1, displayOverride = 0)
    fun progGreenOnPhone() = pressGamepadKey(KeyEvent.KEYCODE_BUTTON_2, displayOverride = 0)
    fun progYellowOnPhone() = pressGamepadKey(KeyEvent.KEYCODE_BUTTON_3, displayOverride = 0)
    fun progBlueOnPhone() = pressGamepadKey(KeyEvent.KEYCODE_BUTTON_4, displayOverride = 0)

    /**
     * Like [pressKey] but injects with SOURCE_GAMEPAD. Used by the program
     * keys; never re-pins IME (gamepad buttons aren't text input).
     */
    private fun pressGamepadKey(keycode: Int, displayOverride: Int? = null) = safe {
        val backend = PortalPadApp.instance.clickBackend
        val targetDisplay = displayOverride ?: displayId
        // Shell-fallback display flag honoring the override (displayFlag() reads the
        // injector's own displayId, which the override exists to bypass).
        val flag = if (targetDisplay != 0) "-d $targetDisplay " else ""
        Log.d(
            TAG,
            "pressGamepadKey keycode=$keycode target=$targetDisplay " +
                "override=$displayOverride backend=${backend?.javaClass?.simpleName} " +
                "shizukuReady=${(backend as? ClickBackend.ShizukuUserService)?.backend?.isReady} " +
                "accessReady=${access.isReady}",
        )
        keyExecutor.execute {
            // Same dead-binder guard as pressKey: IPC on this background thread
            // would otherwise crash the app when Shizuku is down.
            try {
                if (backend is ClickBackend.ShizukuUserService && backend.backend.isReady) {
                    Log.d(TAG, "gamepad → Shizuku gamepadKey(disp=$targetDisplay, $keycode)")
                    backend.backend.gamepadKey(targetDisplay, keycode)
                } else if (access.isReady) {
                    // Shell fallback — `input keyevent` doesn't accept a source
                    // override, but at least the keycode goes out.
                    Log.d(TAG, "gamepad → shell: input ${flag}keyevent $keycode")
                    access.execShell("input ${flag}keyevent $keycode")
                } else {
                    Log.w(TAG, "gamepad → NO backend ready (key dropped) keycode=$keycode")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "gamepad key inject failed (backend unavailable?) keycode=$keycode", t)
            }
        }
    }

    /** Universal safety wrapper — any inject failure logs but never crashes. */
    private inline fun safe(block: () -> Unit) {
        try { block() } catch (t: Throwable) { Log.e(TAG, "inject failed", t) }
    }

    /**
     * Re-pin the IME policy for the external display. the reference implementation's b() pattern:
     * call this after every click, every key event, every display change.
     * Without continuous re-pinning, Android's IME bounces between displays
     * when focus changes (which happens on every hover event over a text
     * field), causing the keyboard to disappear mid-type.
     *
     * Policy values from android.view.WindowManager:
     *  0 = DISPLAY_IME_POLICY_LOCAL    → IME on the focused display (external)
     *  1 = DISPLAY_IME_POLICY_FALLBACK → IME on default display (phone)
     *  2 = DISPLAY_IME_POLICY_HIDE     → no IME for this display
     *
     * IME policy is THE confirmed root cause of the Chrome address-bar
     * dropdown bug (found by comparing dumpsys window/input between the reference implementation
     * and PortalPad with a live dropdown):
     *
     *   the reference implementation: InputMethod window on displayId=71 (the VD, with Chrome).
     *            IME policy = LOCAL (0). Chrome's omnibox dropdown shares a
     *            display with its IME, so focus/IME re-evaluation stays
     *            within the VD and the dropdown survives cursor motion.
     *
     *   PortalPad (old): we forced IME policy = HIDE (2), so the
     *            InputMethod window lived on displayId=0 (the phone) while
     *            Chrome (the imeInputTarget) was on the VD. That cross-
     *            display IME relationship meant ANY display event (cursor
     *            move, screencap, relayout) re-evaluated the IME target
     *            across displays and dismissed Chrome's dropdown. This is
     *            why even a screenshot — which injects no input — killed
     *            the dropdown.
     *
     * Fix: default to LOCAL (0) so the IME lives on the VD with Chrome,
     * exactly like the reference implementation. The IME_ON_EXTERNAL pref still lets the user
     * force it, but the DEFAULT is now LOCAL, not HIDE.
     *
     * The [aggressive] parameter gates the per-click/key re-pin on the dormant
     * [aggressiveImeRepin] flag (currently always off). Display-change re-pins
     * pass aggressive = false and always run (one-shot policy setups).
     */
    private var lastImePolicyDisplay: Int = -1
    /** Every display id we've ever applied an IME policy to this process — so
     *  teardown can reset each one back to LOCAL and never strand the phone
     *  keyboard. (A non-default policy left on a VD that's then destroyed wedges
     *  IME placement on some OEMs until reboot.) */
    private val touchedImeDisplays = java.util.Collections.synchronizedSet(mutableSetOf<Int>())

    /** Last IME policy successfully APPLIED per display. Every write goes
     *  through a dedupe against this: setting a display's IME policy — even
     *  to the SAME value — forces a display-wide IME re-layout, and Chrome
     *  dismisses its omnibox suggestion dropdown on that relayout (measured:
     *  suggestions died at relay open AND close, both redundant writes for
     *  the auto-relay config). Skipping no-op writes keeps the dropdown
     *  alive through the relay's whole lifecycle. Entries clear on
     *  resetImePolicy so a fresh session re-applies for real. */
    private val imePolicyApplied = java.util.concurrent.ConcurrentHashMap<Int, Int>()
    fun repinImePolicy(aggressive: Boolean = true) {
        if (aggressive) {
            val aggressiveOn = aggressiveImeRepin
            if (!aggressiveOn) {
                Log.d(TAG, "repin SUPPRESSED (aggressive disabled)")
                return
            }
        }
        // While the typing page is up it OWNS the external display's IME policy
        // (FALLBACK, so the keyboard stays on the phone). suppressImeRepin was
        // only ever checked on the per-key path, so a display event could re-pin
        // mid-page and silently break the page's own keyboard routing. Caught in
        // kb1.txt 2026-07-19:
        //   14:53:49.117 relay IME mode ON:  policy=1(FALLBACK)
        //   14:53:51.134 repin disp=44 policy=0   <-- page still open
        //   14:53:52.757 relay IME mode OFF: restored policy=0
        // setRelayImeMode(false) clears the flag when the page closes, so the
        // next real native keyboard request can apply its normal policy.
        if (suppressImeRepin) {
            Log.d(TAG, "repin SUPPRESSED (typing page owns the policy)")
            return
        }
        val targetDisplay = displayId
        if (targetDisplay == 0) return    // local display — no pinning needed
        val backend = PortalPadApp.instance.clickBackend
        val shizuku = (backend as? ClickBackend.ShizukuUserService)?.backend ?: return
        if (!shizuku.isReady) return
        // IME_ON_EXTERNAL pref means "force IME onto external (LOCAL, policy 0)".
        //  • glasses mode (IME_ON_EXTERNAL on) → LOCAL(0): keep the IME on the VD,
        //    the reference implementation's confirmed-good behavior.
        //  • phone mode → FALLBACK(1), relay on or off. History: relay-on used
        //    to pin HIDE(2) so the native keyboard "never leaks" for a VD field
        //    while the relay typed via INJECTED KEYSTROKES. That injection path
        //    is gone (FORWARD_LIVE_KEYSTROKES, KeyboardRelayActivity), and HIDE
        //    became the direct cause of the relay's blink cycle: every debounced
        //    a11y write migrates the IME target to the VD field for a beat, and
        //    a HIDE-policy target fires HIDE_DISPLAY_IME_POLICY_HIDE (kb tests
        //    2026-07-17: hide ~25ms after each write, recovery seconds later).
        //    Under FALLBACK the same migration keeps the keyboard UP on the
        //    phone, briefly bound cross-display, until the relay refocuses —
        //    no hide to recover from. (A 2025-era note says FALLBACK failed for
        //    the relay — that failure was per-key Chrome focus gain from the
        //    injected keystrokes, which no longer exist.)
        // Constants: 0=LOCAL, 1=FALLBACK_DISPLAY, 2=HIDE.
        val imeOnExternal = imeOnExternalEnabled
        val policy = if (imeOnExternal) 0 else 1
        if (imePolicyApplied[targetDisplay] == policy) {
            Log.d(TAG, "repin NO-OP disp=$targetDisplay policy=$policy already applied (dropdown-safe)")
            return
        }
        val ok = runCatching { shizuku.setImePolicy(targetDisplay, policy) }.getOrDefault(false)
        Log.d(TAG, "repin disp=$targetDisplay policy=$policy (imeOnExternal=$imeOnExternal autoOpenRelay=$autoOpenRelayEnabled) aggressive=$aggressive ok=$ok")
        if (ok) imePolicyApplied[targetDisplay] = policy
        lastImePolicyDisplay = targetDisplay
        touchedImeDisplays.add(targetDisplay)
    }


    /**
     * Enter/exit "relay typing" IME mode for the "Type to external display"
     * screen.
     *
     * The relay keeps the PHONE keyboard up on display 0 and forwards each
     * keystroke to Chrome on the VD. The logcat trace showed the failure
     * mechanism: each forwarded key makes Chrome (on the VD) gain window
     * focus and request the IME *visible on the VD*. Samsung's
     * `semComputeImeDisplayIdForTarget` then returns displayToShowIme=-1
     * (it can't place the IME on the VD) and fires
     * HIDE_DISPLAY_IME_POLICY_HIDE — which collapses the phone keyboard
     * serving the relay field. Result: one key types, keyboard vanishes.
     *
     * Fix (current): while relaying, set the VD's IME policy to
     * FALLBACK_DISPLAY (1) so any moment the VD field becomes the IME target
     * (the debounced a11y writes) the keyboard stays up on the phone instead
     * of hiding. HIDE (2) was the keystroke-forwarding-era fix and became the
     * blink cycle's cause once forwarding was removed — see the inline
     * comment below for the full history.
     * On Relay exit we leave the normal policy alone. A forced policy write at
     * that moment relayouts the display IME but does not create an editor-owned
     * keyboard request, and it proved no more reliable for Direct or External.
     */
    fun setRelayImeMode(active: Boolean) {
        suppressImeRepin = active
        val targetDisplay = displayId
        if (targetDisplay == 0) return
        val backend = PortalPadApp.instance.clickBackend
        val shizuku = (backend as? ClickBackend.ShizukuUserService)?.backend ?: return
        if (!shizuku.isReady) return
        if (active) {
            // 1 = DISPLAY_IME_POLICY_FALLBACK_DISPLAY. HISTORY: this was 2
            // (HIDE) through the keystroke-forwarding era — each INJECTED key
            // made Chrome-on-the-VD gain focus and request IME placement
            // there, and HIDE was the only thing that broke Samsung's failed-
            // placement → hide cascade (FALLBACK was tried then and failed).
            // That per-key focus gain died with FORWARD_LIVE_KEYSTROKES
            // (typing is a11y SET_TEXT now); what remained of HIDE was pure
            // downside: every debounced write briefly makes the VD field the
            // IME target, and a HIDE-policy target fires
            // HIDE_DISPLAY_IME_POLICY_HIDE — the relay's blink cycle (kb
            // tests 2026-07-17). FALLBACK keeps the keyboard up on the phone
            // through those migrations. If the one-key-then-vanish pattern
            // ever returns, capture a log before touching this — the old
            // mechanism CANNOT fire without key injection.
            if (imePolicyApplied[targetDisplay] == 1) {
                Log.d(TAG, "relay IME mode ON: disp=$targetDisplay already FALLBACK — NO-OP (dropdown survives)")
                return
            }
            val ok = runCatching { shizuku.setImePolicy(targetDisplay, 1) }.getOrDefault(false)
            if (ok) imePolicyApplied[targetDisplay] = 1
            touchedImeDisplays.add(targetDisplay)
            Log.d(TAG, "relay IME mode ON: disp=$targetDisplay policy=1(FALLBACK) ok=$ok")
        } else {
            // LAZY restore — deliberately NO policy write here. The eager
            // repin at relay close forced the IME relayout that dismissed
            // Chrome's suggestion dropdown the instant the user tapped X,
            // making suggestions unclickable. The per-key repin machinery
            // (deduped above) restores the pref-correct policy the next time
            // a path actually needs it, and resetImePolicy still cleans up at
            // disconnect. Until then the dropdown stays up for the trackpad.
            Log.d(TAG, "relay IME mode OFF: policy left as-is (lazy restore; dropdown survives)")
        }
    }

    /**
     * "Keyboard on the glasses" button action (the pill tap when
     * IME_ON_EXTERNAL is ON). Guarantees the external display's IME policy is
     * LOCAL (0) so the system keyboard renders on the glasses for any field
     * focused there. There is no reliable shell command to *force* an arbitrary
     * app to pop its IME, so this just nails the routing — the user taps a text
     * field on the glasses to bring the keyboard up. Best-effort and guarded.
     */
    fun showImeOnExternal() {
        imeOnExternalEnabled = true
        repinImePolicy(aggressive = false)
        Log.i(TAG, "DIAG-NATIVEKB route selected EXTERNAL disp=$displayId")
    }

    /**
     * Route an editor-owned native IME to the phone without focusing, clicking,
     * replaying, or force-showing anything. This is used only after an explicit
     * user choice to switch from External to Direct, so the policy changes now
     * instead of waiting for the asynchronous preference collector.
     */
    fun routeImeToPhone() {
        imeOnExternalEnabled = false
        repinImePolicy(aggressive = false)
        Log.i(TAG, "DIAG-NATIVEKB route selected DIRECT disp=$displayId")
    }

    /**
     * Force the native soft keyboard to show on the currently focused editor.
     * For phone-as-keyboard mode: after a glasses field is focused, IME routing
     * resolves to the phone (display 0) but the field may not auto-raise the
     * keyboard. This asks the privileged process to show it explicitly. Returns
     * true only if the call completed (not a guarantee the keyboard became
     * visible — some fields/Android versions still refuse). No-op without a
     * ready Shizuku backend.
     */
    fun forceShowImeOnPhone(): Boolean {
        val backend = PortalPadApp.instance.clickBackend
        val shizuku = (backend as? ClickBackend.ShizukuUserService)?.backend
        if (shizuku == null || !shizuku.isReady) {
            Log.w(TAG, "forceShowImeOnPhone SKIPPED: backend not ready")
            return false
        }
        val ok = runCatching { shizuku.showImeOnFocusedEditor() }.getOrDefault(false)
        Log.d(TAG, "forceShowImeOnPhone ok=$ok")
        return ok
    }

    /**
     * Hide the current native IME from system_server. Direct mode's editor lives
     * in the external app, so TrackpadActivity cannot supply the served editor
     * token required by normal WindowInsets/IMM hide calls. This is a single
     * explicit user-action request, not a visibility poll or recovery loop.
     */
    fun hideNativeImeFromServer(): Boolean {
        val backend = PortalPadApp.instance.clickBackend
        val shell = (backend as? ClickBackend.ShizukuUserService)?.backend
        if (shell == null || !shell.isReady) {
            Log.w(TAG, "hideNativeImeFromServer SKIPPED: backend not ready")
            return false
        }
        val ok = runCatching { shell.hideImeFromServer() }.getOrDefault(false)
        Log.i(TAG, "DIAG-NATIVEKB server-side hide result=$ok")
        return ok
    }

    /** Dismiss Direct mode through the editor that actually owns the IME.
     *
     * Samsung renders the FALLBACK-display keyboard on display 0, but Chrome's
     * editor and Back callback still live on the current external display. A
     * server-only hide removes the surface while leaving Chrome's requested-
     * visible bit set, so the keyboard reappears a few hundred milliseconds
     * later. Injecting one Back to the live external display lets the owning
     * editor clear that request. The server hide runs once afterward only as a
     * visual fallback; this is one explicit user action, never a retry loop. */
    fun dismissDirectImeFromOwner(): Boolean {
        val targetDisplay = displayId
        val backend = PortalPadApp.instance.clickBackend
        val shell = (backend as? ClickBackend.ShizukuUserService)?.backend
        if (
            targetDisplay == 0 || imeOnExternalEnabled || shell == null || !shell.isReady
        ) {
            Log.w(
                TAG,
                "DIAG-NATIVEKB owner dismiss unavailable disp=$targetDisplay " +
                    "external=$imeOnExternalEnabled ready=${shell?.isReady == true}",
            )
            return false
        }
        keyExecutor.execute {
            try {
                val ownerBack = shell.key(targetDisplay, KeyEvent.KEYCODE_BACK)
                try {
                    Thread.sleep(DIRECT_IME_SERVER_HIDE_FALLBACK_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                val serverHide = shell.hideImeFromServer()
                Log.i(
                    TAG,
                    "DIAG-NATIVEKB owner dismiss disp=$targetDisplay " +
                        "back=$ownerBack serverFallback=$serverHide",
                )
            } catch (t: Throwable) {
                Log.w(TAG, "DIAG-NATIVEKB owner dismiss failed disp=$targetDisplay", t)
            }
        }
        Log.i(TAG, "DIAG-NATIVEKB owner dismiss queued disp=$targetDisplay")
        return true
    }

    /**
     * True when a soft keyboard is currently on screen. The native-keyboard path
     * uses this to VERIFY that tapping/focusing an external-display field raised
     * the phone keyboard; false means fall back to the relay page.
     *
     * Deliberately tolerant: any missing backend / missing method / thrown call
     * answers false, and false only ever costs a relay page — never a dead
     * keyboard. Uses the shared shell backend so root and Shizuku both work.
     */
    fun isNativeImeShown(): Boolean {
        // ShizukuUserService is the one wrapper variant — despite the historical
        // name it carries the root-bound service too, so this covers both modes.
        val backend = PortalPadApp.instance.clickBackend
        val shell = (backend as? ClickBackend.ShizukuUserService)?.backend
        if (shell == null || !shell.isReady) {
            Log.w(TAG, "isNativeImeShown SKIPPED: backend not ready")
            return false
        }
        return runCatching { shell.isImeShown() }.getOrDefault(false)
    }

    /**
     * Reset IME policy to LOCAL (0 = the system default) on every display this
     * process touched, plus display 0 defensively. MUST be called on every
     * session teardown BEFORE the virtual display is released — leaving a VD at
     * a non-default IME policy when it's destroyed wedges IME placement on some
     * OEMs (Samsung), which kills the PHONE keyboard until a reboot. Also called
     * on service start as a safety net in case a prior session crashed before
     * resetting. Best-effort and fully guarded; no-ops without a bound backend.
     */
    fun resetImePolicy() {
        val backend = PortalPadApp.instance.clickBackend
        val shizuku = (backend as? ClickBackend.ShizukuUserService)?.backend
        if (shizuku == null) {
            Log.w(TAG, "resetImePolicy SKIPPED: backend is not Shizuku (backend=${backend?.javaClass?.simpleName}) — displays stay DIRTY for retry. dirty=${touchedImeDisplays}")
            return
        }
        if (!shizuku.isReady) {
            Log.w(TAG, "resetImePolicy SKIPPED: Shizuku not ready — displays stay DIRTY for retry. dirty=${touchedImeDisplays}")
            return
        }
        // Display 0 (phone) always reset to LOCAL so the phone is a valid IME
        // host again; plus every external/VD display we ever set a policy on.
        // Only REMOVE a display from the dirty set if its reset actually
        // succeeded — a failed one stays dirty so a later retry (on Shizuku
        // (re)connect / app start) cleans it up rather than stranding the
        // keyboard until reboot.
        val displays = (touchedImeDisplays.toSet() + 0)
        Log.d(TAG, "resetImePolicy: resetting displays=$displays")
        for (d in displays) {
            val ok = runCatching { shizuku.setImePolicy(d, 0) }.getOrDefault(false)
            Log.d(TAG, "resetImePolicy disp=$d -> 0(LOCAL) ok=$ok")
            if (ok) {
                touchedImeDisplays.remove(d)
                imePolicyApplied.remove(d)
            }
        }
        if (touchedImeDisplays.isEmpty()) lastImePolicyDisplay = -1
    }

    /**
     * Retry resetting any IME policies that are still "dirty" (set earlier but
     * never successfully reset — e.g. the disconnect reset ran while the backend
     * was Accessibility, not Shizuku, and got skipped). Called when a Shizuku
     * backend becomes available again, so a stranded policy SELF-HEALS instead of
     * requiring a reboot. No-op when nothing is dirty or no display is active
     * that we'd disturb. Safe to call repeatedly.
     */
    fun flushDirtyImePolicies() {
        if (touchedImeDisplays.isEmpty()) return
        Log.d(TAG, "flushDirtyImePolicies: dirty=${touchedImeDisplays} — attempting reset")
        resetImePolicy()
    }

    companion object {
        private const val TAG = "InputInjector"
        /** How close (px) the cursor must be to a window edge at drag-start for the
         *  drag to grab that edge (resize) rather than move the window. */
        private const val EDGE_GRAB_PX = 48f
        // Resize-cursor hover: refresh the freeform-task snapshot at most this
        // often (windows only move on drag, so mild staleness is invisible).
        private const val RESIZE_CACHE_TTL_MS = 250L
        // Resize-cursor hover strip: how far outside / inside each visible edge
        // the resize arrow appears. Kept thin so it hugs the border.
        private const val RESIZE_EDGE_OUT = 8f
        private const val RESIZE_EDGE_IN = 14f
        // The top edge's inside reach is a bit wider than the other edges' arrow
        // strip so it's easier to catch, while still leaving the caption below it
        // for title-bar moves.
        private const val RESIZE_TOP_IN = 12f
        // How far ABOVE the window's top edge the resize/move cursors reach. Small, so the
        // cursor never floats out in the wallpaper above the window — it only appears once
        // you're actually on the top edge.
        private const val RESIZE_TOP_OUT = 8f
        // Height of the freeform caption strip (below the top-resize zone) that a
        // grab treats as "move the window". Android captions run ~40–48px; tunable.
        private const val CAPTION_STRIP_H = 52f
        // Direct Pixel edge-resize minimum. Matches the existing top-bar resize
        // proxy so both PortalPad resizing paths clamp consistently.
        private const val MIN_SHELL_WINDOW_PX = 320
        // Margin around the collapsed caption handle pill that still counts as "on the
        // handle" — makes the small pill easier to land the cursor on. Asymmetric:
        // the pill is only ~33px tall and sits dead-center on the window's full-width
        // top resize-V band, so a thin resize sliver hugs it (mostly above). A larger
        // VERTICAL outset swallows that sliver; a modest HORIZONTAL outset keeps the
        // top edge to the left/right of the pill available for real resizing.
        private const val HANDLE_GRAB_OUT_X = 16f
        // TIGHT mini-green-bar zone: a small margin on the sides/top of the pill, and
        // NOTHING below it (the pill menu opens right below and must stay tappable, not
        // draggable). Used for both the hover cursor and the press re-test.
        private const val PILL_OUT = 8f
        // Right-side carve-out on the Android caption bar: the maximize/close/fullscreen
        // button cluster lives here, so the drag body stops this far in from the right.
        private const val CAPTION_BUTTON_ZONE = 250f
        // How long to keep the last non-empty handle rects after captionHandles
        // momentarily returns empty (the pill menu opening/closing drops the node),
        // so the MOVE zone does not flicker to a resize edge mid-interaction.
        private const val HANDLE_RECTS_GRACE_MS = 600L
        // Accessibility windows are already in memory; 100ms keeps IME
        // occlusion responsive without scanning on every raw pointer sample.
        private const val IME_BOUNDS_CACHE_TTL_MS = 100L
        private const val NATIVE_IME_HANDOFF_FALLBACK_MS = 750L
        private const val DIRECT_IME_SERVER_HIDE_FALLBACK_MS = 350L
        // Widget edit-mode cursor zones (px). Edges reach generously OUTWARD
        // (covers the half-in dots and the 1-tall widget's fully-out S dot) but
        // barely inward, so resize glyphs never sit on the widget's face; the
        // interior MOVE zone insets past the inward reach so the two can't
        // overlap.
        private const val WIDGET_EDGE_IN = 10f
        private const val WIDGET_EDGE_OUT = 28f
        private const val WIDGET_MOVE_INSET = 14f
        // How long "we saw a collapsed pill" suppresses the geometric caption
        // fallback after the handle node vanishes. Long enough to ride out
        // SystemUI's decor rebuild (observed multi-second flicker after pill
        // interactions), short enough that a window GENUINELY losing its
        // handle regains the geometric band quickly.
        private const val COLLAPSED_HANDLE_MEMORY_MS = 4000L
        // How often the caption/handle shell-move re-sends bounds during a drag.
        // BOTH paths are throttled to ~11 steps/s — NOT for IPC cost, but
        // because on this ROM every resizeTask (fast binder OR shell) wraps
        // the bounds change in a WM TRANSITION that takes ~30-80ms to play,
        // serialized on one thread (~25-30/s max drain). The old 16ms fast
        // cadence submitted 60/s: WM queued nearly every one (drag test
        // 2026-07-17: 1621 of 1627 resizes hit "Queueing transition") and the
        // window played back a RECORDING of the finger — laggy tracking,
        // cursor off the caption bar, and 10.5 SECONDS of the window moving
        // by itself after release while the backlog drained. Coalescing can't
        // prevent this: resizeTask RETURNS when the transition is queued, not
        // when it's applied, so "not busy" never meant "caught up". At 90ms
        // the queue can't form; worst-case visual lag is one pending step
        // (~90ms) + one play (~40ms), and the window STOPS when the finger
        // stops. If drags still feel wrong, tune with a new DIAG-DRAGPOS
        // capture — do NOT drop this below the measured drain rate. (Future
        // smooth path: WindowContainerTransaction/applyTransaction, the
        // transition-less mechanism DeX-style drags use — a research project,
        // not a tweak.)
        private const val CAPTION_MOVE_THROTTLE_MS = 90L
        private const val CAPTION_MOVE_THROTTLE_FAST_MS = 90L
        /** Reference finger span (px) for scale=1.0 in the injected pinch; the
         *  live span is PINCH_BASE_SPAN * pinchScale. */
        private const val PINCH_BASE_SPAN = 240f
        private const val EXTERNAL_MOUSE_IDLE_RESTORE_MS = 1200L

        internal data class ScrollSwipePlan(
            val startX: Float,
            val startY: Float,
            val endX: Float,
            val endY: Float,
            val pageScrollDeltaY: Float,
        )

        internal fun computeScrollSwipePlan(
            cursorX: Float,
            cursorY: Float,
            rawDeltaX: Float,
            rawDeltaY: Float,
            displayWidth: Int,
            displayHeight: Int,
            minTravel: Double,
        ): ScrollSwipePlan? {
            val rawTravel = kotlin.math.hypot(rawDeltaX.toDouble(), rawDeltaY.toDouble())
            if (rawTravel < minTravel) return null
            val maxX = (displayWidth - 1).coerceAtLeast(1).toFloat()
            val maxY = (displayHeight - 1).coerceAtLeast(1).toFloat()
            val cx = cursorX.coerceIn(0f, maxX)
            val cy = cursorY.coerceIn(0f, maxY)
            val desiredEndX = cx + rawDeltaX
            val desiredEndY = cy + rawDeltaY
            val endX = desiredEndX.coerceIn(0f, maxX)
            val endY = desiredEndY.coerceIn(0f, maxY)
            val startX = if (desiredEndX != endX) (endX - rawDeltaX).coerceIn(0f, maxX) else cx
            val startY = if (desiredEndY != endY) (endY - rawDeltaY).coerceIn(0f, maxY) else cy
            return ScrollSwipePlan(
                startX = startX,
                startY = startY,
                endX = endX,
                endY = endY,
                pageScrollDeltaY = rawDeltaY,
            )
        }

        internal fun scaleCoordsForMirror(
            x: Float,
            y: Float,
            srcW: Int,
            srcH: Int,
            dstW: Int,
            dstH: Int,
        ): Pair<Float, Float> {
            if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) return x to y
            val sx = (x / srcW.toFloat() * dstW.toFloat()).coerceIn(0f, (dstW - 1).toFloat())
            val sy = (y / srcH.toFloat() * dstH.toFloat()).coerceIn(0f, (dstH - 1).toFloat())
            return sx to sy
        }
    }
}
