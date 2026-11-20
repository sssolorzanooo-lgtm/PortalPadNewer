package com.portalpad.app.service

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import com.portalpad.app.PortalPadApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Phase 1 physical-mouse capture (app side).
 *
 * A real Bluetooth/USB mouse normally drives only the phone's system pointer.
 * This routes it to PortalPad's external-display cursor instead: the privileged
 * [ShellUserService] opens the device, (tries to) EVIOCGRAB it, and streams
 * 16-byte delta records over a pipe; here we read them and feed the same
 * [InputInjector.pointerMove] the trackpad/air-mouse already use.
 *
 * Phase 1 is movement only — buttons and scroll are captured into the record by
 * native but ignored here. The capture also reports whether the grab succeeded,
 * which is the spike's whole purpose: it tells us if the shell domain can
 * exclusively grab input on this device (screen-off capable) or if that needs
 * root on this hardware.
 */
class BluetoothMouseController(private val injector: InputInjector) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var job: Job? = null
    /** Bounded re-arm poller: retries enable() while a mouse is expected but not
     *  yet enumerated (BT settling on connect, or the mouse waking from sleep). */
    @Volatile private var pollJob: Job? = null
    /** Monotonic capture-session id. disable() records the session it stops in
     *  [stoppedSessionId]; the read loop uses the mismatch to tell a deliberate
     *  stop (no self-heal) from the device going away on its own (self-heal). */
    @Volatile private var sessionId = 0
    @Volatile private var stoppedSessionId = -1

    /** Linear sensitivity multiplier applied to raw mouse deltas (live from prefs). */
    @Volatile private var sensitivity: Float = DEFAULT_SENSITIVITY
    /** Mouse-specific wheel speed multiplier (live from prefs). */
    @Volatile private var scrollSpeed: Float = DEFAULT_SCROLL_SPEED
    /** Reverse the wheel direction (for devices whose wheel sign is inverted). */
    @Volatile private var reverseScroll: Boolean = false
    /** User opted into the external mouse (persisted) — drives auto-resume. */
    @Volatile private var userEnabled: Boolean = false
    /** Automatically capture connected USB/BT mice when external display is active (Default ON). */
    @Volatile private var autoCaptureEnabled: Boolean = true
    /** True if the user explicitly toggled Enable OFF during this session. */
    @Volatile private var userExplicitlyDisabled: Boolean = false
    /** True when the active capture session exclusively grabbed evdev (EVIOCGRAB=OK). */
    @Volatile var isActivelyGrabbed: Boolean = false
        private set
    /** Persisted exclusive-grab preference, used when auto-arming. */
    @Volatile private var userGrab: Boolean = true
    /** Chosen pointer by NAME; empty means take the first found. */
    @Volatile private var preferDevice: String = ""
    /** Remote swipe threshold, live from prefs (replaces the fixed constant). */
    @Volatile private var swipeDistance: Int = 60
    /** Reverse two-finger seek direction; default true corrects tested hardware. */
    @Volatile private var reverseSeek: Boolean = true
    /** 0 = Mouse (cursor), 1 = Remote (D-pad gesture vocabulary). */
    @Volatile private var inputMode: Int = MODE_MOUSE

    // Remote-mode recogniser state. Single-threaded: only readLoop touches these.
    private var accDx = 0
    private var accDy = 0
    private var accWheel = 0
    private var accHWheel = 0
    private var lastRemoteAt = 0L
    // Auto-repeat: which action is currently repeating, and when it last fired.
    private var repeatDir = REPEAT_NONE
    private var lastFireAt = 0L
    /** Independent repeat clock. remoteMotion() only runs when a record ARRIVES,
     *  and a stationary two-finger hold sends none — so the timer that sustains a
     *  held seek cannot live on the input stream. This job ticks on its own. */
    private var repeatJob: kotlinx.coroutines.Job? = null
    private var leftDownAt = 0L
    private var rightDownAt = 0L

    init {
        // Keep mouse settings in sync with prefs so sliders/toggles take effect
        // immediately, even between enable/disable cycles.
        val keys = com.portalpad.app.data.PreferencesRepository.Keys
        scope.launch {
            PortalPadApp.instance.prefs.float(keys.EXT_MOUSE_SENSITIVITY, DEFAULT_SENSITIVITY)
                .collect { sensitivity = if (it > 0f) it else DEFAULT_SENSITIVITY }
        }
        scope.launch {
            PortalPadApp.instance.prefs.float(keys.EXT_MOUSE_SCROLL_SPEED, DEFAULT_SCROLL_SPEED)
                .collect { scrollSpeed = if (it > 0f) it else DEFAULT_SCROLL_SPEED }
        }
        scope.launch {
            PortalPadApp.instance.prefs.bool(keys.EXT_MOUSE_NATURAL_SCROLL, false)
                .collect { reverseScroll = it }
        }
        scope.launch {
            PortalPadApp.instance.prefs.int(keys.EXT_MOUSE_INPUT_MODE, MODE_MOUSE)
                .collect { inputMode = it }
        }
        scope.launch {
            PortalPadApp.instance.prefs.string(keys.EXT_MOUSE_DEVICE, "")
                .collect { preferDevice = it }
        }
        scope.launch {
            PortalPadApp.instance.prefs.int(keys.EXT_MOUSE_SWIPE_DISTANCE, 60)
                .collect { swipeDistance = it.coerceIn(20, 150) }
        }
        scope.launch {
            PortalPadApp.instance.prefs.bool(keys.EXT_MOUSE_REVERSE_SEEK, true)
                .collect { reverseSeek = it }
        }
        scope.launch {
            PortalPadApp.instance.prefs.bool(keys.EXT_MOUSE_ENABLED, false)
                .collect {
                    userEnabled = it
                    // Opted out → stop any in-flight re-arm polling.
                    if (!it && !autoCaptureEnabled) { pollJob?.cancel(); pollJob = null }
                }
        }
        scope.launch {
            PortalPadApp.instance.prefs.bool(keys.EXT_MOUSE_AUTO_CAPTURE, true)
                .collect { auto ->
                    autoCaptureEnabled = auto
                    if (auto) {
                        userExplicitlyDisabled = false
                        if (!running && PortalPadApp.instance.injector.displayId != 0 &&
                            hasExternalPointerConnected(PortalPadApp.instance)
                        ) {
                            maybeAutoArm(delayMs = 200L)
                        }
                    } else if (!userEnabled) {
                        pollJob?.cancel(); pollJob = null
                        if (running) stopCapture()
                    }
                }
        }
        scope.launch {
            // FOLDED INTO ENABLE (SH, 2026-07-24): grabbing is what Enable MEANS,
            // so the separate toggle is gone from Settings and the stored value
            // is deliberately ignored - a user who once turned it off must not
            // be stranded half-enabled by a pref they can no longer see. The
            // pref key stays registered for one-flag revert.
            PortalPadApp.instance.prefs.bool(keys.EXT_MOUSE_GRAB, true)
                .collect { userGrab = true }
        }
        scope.launch {
            PortalPadApp.instance.prefs.string(keys.EXT_MOUSE_MODE_TOGGLE_MAP, "")
                .collect { modeToggleMap = decodeToggleMap(it) }
        }
        scope.launch {
            PortalPadApp.instance.prefs.int(keys.EXT_MOUSE_TOGGLE_ACTIVATION, ACT_TAP)
                .collect {
                    toggleActivation = it.coerceIn(ACT_TAP, ACT_HOLD)
                    Log.i(TAG, "DIAG-REMAP activation pref -> $toggleActivation")
                }
        }
    }

    // ─── Mode-toggle button remap (SH feature, 2026-07-24) ──────────────────
    // One button per device flips input mode mouse<->remote. The native reader
    // already forwards every non-left/right/middle EV_KEY on the grabbed stream
    // as a tagged key record, so side/back/forward buttons (and keyboard keys on
    // combo devices) arrive here with their raw Linux code - no native changes.
    // Structurally, left/right/middle CANNOT be captured: they travel in the
    // button bitmask, never as key records, so the "don't let anyone map left
    // click" guard is guaranteed by the pipe shape rather than by a check.
    // Everything is inert while the display is disconnected because capture
    // itself only runs with a display - the user's device works normally.

    /** One learned trigger, carrying TWO facts (SH's redesign, 2026-07-24):
     *  [gesture] - which gesture this mapping REPRESENTS (tap / double / hold,
     *  from the dropdown at learn time). The dropdown is a LIVE arming
     *  selector: only mappings whose gesture matches the current selection
     *  fire, so switching the dropdown changes behavior immediately.
     *  [pattern] - how the gesture shows up on the wire: PATTERN_TIMING for
     *  conventional buttons (one code repeated; we time the presses) or
     *  PATTERN_ARRIVAL for firmware-gesture devices (the gesture is encoded
     *  in the code itself and arrives once; we fire per arrival). */
    data class ToggleMapping(
        val device: String,
        val code: Int,
        val gesture: Int,
        val pattern: Int,
    )

    @Volatile private var modeToggleMap: List<ToggleMapping> = emptyList()
    @Volatile private var learnDeadline = 0L
    @Volatile private var lastModeToggleAt = 0L
    /** The dropdown's chosen gesture. Seeds the NEXT learn; runtime firing
     *  uses each mapping's own stored activation. */
    @Volatile private var toggleActivation = ACT_TAP
    // Learn-in-progress state (all guarded by the single readLoop thread).
    @Volatile private var learnIntent = ACT_TAP
    @Volatile private var learnPendingCode = -1
    @Volatile private var learnPendingAt = 0L
    @Volatile private var learnPendingHeld = false
    private var learnFinalizeJob: Job? = null
    // Runtime firing state for double/hold mappings.
    private var lastTapCode = -1
    private var lastMappedTapAt = 0L
    @Volatile private var mappedButtonDown = false
    private var holdJob: Job? = null

    /** null = idle; "listening" = learn window open; "mapped\tdev\tbutton" =
     *  just captured (transient, cleared after a beat); "timeout" = window
     *  closed with nothing pressed. */
    private val _learnState = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val learnStateFlow: kotlinx.coroutines.flow.StateFlow<String?> = _learnState

    /** True while a temporary LISTEN-ONLY capture backs the learn window (no
     *  display connected). The read loop discards everything but the learn
     *  branch; released the moment the window resolves. */
    @Volatile private var learnOnlySession = false

    /** Open an 8s window; the next qualifying gesture on the captured device
     *  becomes a toggle mapping, with its activation resolved from what
     *  actually arrives (see [handleModeToggleKey]'s learn branch). Works
     *  WITHOUT a display too (SH): if no capture is running, a listen-only
     *  one is started just for the window and released with it. */
    fun startLearn() {
        if (!running) {
            val st = enable(grab = true, learnOnly = true)
            if (!st.startsWith("OK")) {
                Log.i(TAG, "learn-only capture unavailable: $st")
                _learnState.value = "no-device"
                scope.launch {
                    kotlinx.coroutines.delay(2500)
                    if (_learnState.value == "no-device") _learnState.value = null
                }
                return
            }
            learnOnlySession = true
        }
        learnDeadline = SystemClock.uptimeMillis() + LEARN_WINDOW_MS
        learnIntent = toggleActivation
        learnPendingCode = -1
        learnPendingHeld = false
        learnFinalizeJob?.cancel()
        _learnState.value = "listening"
        scope.launch {
            kotlinx.coroutines.delay(LEARN_WINDOW_MS + 100)
            if (_learnState.value == "listening" &&
                SystemClock.uptimeMillis() >= learnDeadline
            ) {
                endLearnOnlySession()
                _learnState.value = "timeout"
                kotlinx.coroutines.delay(2500)
                if (_learnState.value == "timeout") _learnState.value = null
            }
        }
    }

    /** Release the temporary listen-only capture, if one backed this learn. */
    private fun endLearnOnlySession() {
        if (!learnOnlySession) return
        learnOnlySession = false
        scope.launch { runCatching { stopCapture() } }
        Log.i(TAG, "learn-only capture released")
    }

    /** Commit a learned mapping and close the window. */
    private fun finalizeLearn(code: Int, gesture: Int, pattern: Int) {
        learnDeadline = 0
        learnFinalizeJob?.cancel()
        endLearnOnlySession()
        val dev = grabbedDeviceName().ifBlank { "Unknown device" }
        // APPEND, never replace: several gestures of one device can each hold
        // a mapping (SH). Same (device, code) re-learned updates in place.
        val next = modeToggleMap
            .filterNot { it.device == dev && it.code == code } +
            ToggleMapping(dev, code, gesture, pattern)
        modeToggleMap = next
        scope.launch {
            PortalPadApp.instance.prefs.setString(
                com.portalpad.app.data.PreferencesRepository.Keys.EXT_MOUSE_MODE_TOGGLE_MAP,
                encodeToggleMap(next),
            )
        }
        _learnState.value = "mapped\t$dev\t${buttonLabel(code)}${gestureSuffix(gesture)}"
        Log.i(
            TAG,
            "mode-toggle learned: '$dev' -> ${buttonLabel(code)} ($code) " +
                "gesture=$gesture pattern=$pattern",
        )
        scope.launch {
            kotlinx.coroutines.delay(3500)
            if (_learnState.value?.startsWith("mapped") == true) _learnState.value = null
        }
    }

    /** Whole seconds left in the learn window (rounded up), 0 when closed —
     *  drives the countdown beside "Press a button…" (SH). */
    fun learnRemainingSecs(): Int {
        val ms = learnDeadline - SystemClock.uptimeMillis()
        return if (ms <= 0) 0 else ((ms + 999) / 1000).toInt()
    }

    fun clearMapping(device: String, code: Int) {
        val next = modeToggleMap.filterNot { it.device == device && it.code == code }
        modeToggleMap = next
        scope.launch {
            PortalPadApp.instance.prefs.setString(
                com.portalpad.app.data.PreferencesRepository.Keys.EXT_MOUSE_MODE_TOGGLE_MAP,
                    encodeToggleMap(next),
            )
        }
        Log.i(TAG, "mode-toggle mapping cleared for '$device' code=$code")
    }

    /** The captured device's name, parsed from the status string ("OK name=..
     *  grab=.."), same parse the settings page uses. */
    private fun grabbedDeviceName(): String {
        val s = lastStatus
        if (!s.startsWith("OK")) return ""
        return s.substringAfter("name=", "").substringBefore(" grab=").trim()
    }

    /** Consume a tagged key record when it belongs to the remap system. Returns
     *  true to swallow the event (both edges - a mapped button must not ALSO
     *  type into the keyboard relay path). */
    private fun handleModeToggleKey(code: Int, down: Boolean): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now < learnDeadline) {
            // GESTURE-AWARE LEARNING (SH's model): the dropdown states the
            // intended gesture; what the device actually SENDS during that
            // gesture decides how the mapping fires. A conventional button
            // repeats one code, so a double tap shows up as two presses and a
            // hold as a long-held press - store that timing. A firmware-
            // gesture device (SH's puck: single=158, double=172, hold=nothing)
            // encodes the gesture into the CODE itself and sends it once -
            // store fire-on-press. Either way, the gesture performed while
            // mapping is the gesture that toggles.
            when (learnIntent) {
                ACT_DOUBLE -> if (down) {
                    when {
                        learnPendingCode == code && now - learnPendingAt <= DOUBLE_TAP_MS ->
                            // Real repeated code: a conventional button - the
                            // double is OURS to time at runtime.
                            finalizeLearn(code, ACT_DOUBLE, PATTERN_TIMING)
                        learnPendingCode < 0 -> {
                            learnPendingCode = code
                            learnPendingAt = now
                            // Keep the timeout clock clear of the resolver.
                            learnDeadline = now + 1000
                            learnFinalizeJob = scope.launch {
                                kotlinx.coroutines.delay(DOUBLE_LEARN_RESOLVE_MS)
                                if (learnPendingCode == code) {
                                    // One arrival, no repeat: the firmware
                                    // encoded the double into this code.
                                    finalizeLearn(code, ACT_DOUBLE, PATTERN_ARRIVAL)
                                }
                            }
                        }
                        else ->
                            // A DIFFERENT code mid-gesture: the firmware sent
                            // its own double-press signal - that one wins.
                            finalizeLearn(code, ACT_DOUBLE, PATTERN_ARRIVAL)
                    }
                }
                ACT_HOLD -> {
                    if (down && learnPendingCode < 0) {
                        learnPendingCode = code
                        learnPendingAt = now
                        learnPendingHeld = true
                        learnDeadline = now + 1000
                        learnFinalizeJob = scope.launch {
                            kotlinx.coroutines.delay(HOLD_MS + 60)
                            if (learnPendingHeld && learnPendingCode == code) {
                                // Genuinely held past the threshold: ours to time.
                                finalizeLearn(code, ACT_HOLD, PATTERN_TIMING)
                            }
                        }
                    } else if (!down && learnPendingCode == code) {
                        // Released before the threshold while the user was told
                        // to hold: whatever the firmware sent for that hold IS
                        // the hold signal - fire per arrival.
                        learnPendingHeld = false
                        finalizeLearn(code, ACT_HOLD, PATTERN_ARRIVAL)
                    }
                }
                else -> if (down) finalizeLearn(code, ACT_TAP, PATTERN_TIMING)
            }
            return true
        }
        val dev = grabbedDeviceName()
        val entry = modeToggleMap.firstOrNull { it.device == dev && it.code == code }
            ?: return false
        Log.i(
            TAG,
            "DIAG-REMAP mapped key code=$code down=$down gesture=${entry.gesture} " +
                "pattern=${entry.pattern} armed=$toggleActivation " +
                "heldPrev=$mappedButtonDown sincePrevTap=${now - lastMappedTapAt}ms",
        )
        // EVERY MAPPING IS ALWAYS ACTIVE, each firing on its own recorded
        // gesture (SH's final model, 2026-07-24 - REVERSED the short-lived
        // "dropdown arms one gesture" build): the dropdown only matters while
        // RECORDING ("Gesture to map"). Don't want a gesture toggling? Clear
        // its row. No hidden mode switch to explain.
        //
        // FIRST EDGE ONLY: keyboard-class codes AUTOREPEAT while held - the
        // kernel streams extra "down" records; a press only counts when the
        // button was actually up before it.
        if (down) {
            val firstEdge = !mappedButtonDown
            mappedButtonDown = true
            if (!firstEdge) return true // kernel autorepeat, not a new press
            if (entry.pattern == PATTERN_ARRIVAL) {
                // The firmware already verified the gesture; each arrival IS
                // one performed gesture.
                fireModeToggle()
                return true
            }
            when (entry.gesture) {
                ACT_DOUBLE -> {
                    val prev = if (lastTapCode == code) lastMappedTapAt else 0L
                    lastTapCode = code
                    lastMappedTapAt = now
                    if (now - prev <= DOUBLE_TAP_MS) {
                        lastMappedTapAt = 0
                        fireModeToggle()
                    }
                }
                ACT_HOLD -> {
                    holdJob?.cancel()
                    holdJob = scope.launch {
                        kotlinx.coroutines.delay(HOLD_MS)
                        if (mappedButtonDown) fireModeToggle()
                    }
                }
                else -> fireModeToggle()
            }
        } else {
            mappedButtonDown = false
            holdJob?.cancel()
        }
        return true
    }

    private fun fireModeToggle() {
        val now = SystemClock.uptimeMillis()
        if (now - lastModeToggleAt <= MODE_TOGGLE_DEBOUNCE_MS) return
        lastModeToggleAt = now
        val newMode = if (inputMode == MODE_REMOTE) MODE_MOUSE else MODE_REMOTE
        setInputModeNow(newMode)
        scope.launch {
            PortalPadApp.instance.prefs.setInt(
                com.portalpad.app.data.PreferencesRepository.Keys.EXT_MOUSE_INPUT_MODE,
                newMode,
            )
        }
        // Confirm on the display at the moment it happens - a hardware
        // button with no feedback leaves the user guessing until they
        // move the mouse (SH-approved popup). EXTERNAL display only:
        // displayId falls back to 0 (the phone) when no session is up, and
        // this popup on the phone screen is exactly what SH reported after
        // a service stop left the capture running.
        runCatching {
            val app = PortalPadApp.instance
            val dispId = app.injector.displayId
            if (dispId != 0) {
                val dm = app.getSystemService(android.hardware.display.DisplayManager::class.java)
                val d = dm?.getDisplay(dispId)
                if (d != null) {
                    com.portalpad.app.presentation.GlassesToast.show(
                        app, d,
                        if (newMode == MODE_REMOTE) "Remote mode" else "Mouse mode",
                    )
                }
            }
        }
        Log.i(TAG, "mode-toggle button -> ${if (newMode == MODE_REMOTE) "REMOTE" else "MOUSE"}")
    }


    /**
     * Auto-resume: if the user opted in, capture isn't already running, and an
     * external display is present, (re)arm capture. Called from the external
     * display-connect and Bluetooth-connect hooks so an opted-in user doesn't have
     * to re-toggle each session. No-ops (silently) when not opted in, already
     * running, no external display, or no mouse present (enable() returns ERR).
     * [delayMs] lets the display-connect path wait for the display to finish
     * setting up before discovery runs.
     */
    /**
     * The external display went away. Release the mouse.
     *
     * The capture had no display awareness at all: nothing disarmed it, so on
     * disconnect the device stayed exclusively grabbed and kept injecting arrows
     * and media keys at a displayId that was dead or had fallen back to the
     * phone — the mouse captured, doing something invisible.
     *
     * [userEnabled] is deliberately left set, so reconnecting the display
     * auto-arms again without you re-enabling anything. This is "the display
     * left", not "the user opted out".
     */
    fun onExternalDisplayGone() {
        pollJob?.cancel()
        pollJob = null
        isActivelyGrabbed = false
        if (!running) return
        Log.i(TAG, "external display gone -> releasing mouse")
        stopCapture()
        lastStatus = "no-display"
    }

    /** Called by InputDevice / BT listeners when physical pointer hardware attaches. */
    fun onPointerHardwareAttached() {
        userExplicitlyDisabled = false
        if (running) {
            if (isActivelyGrabbed) injector.dismissSystemPointer()
            return
        }
        if (!userEnabled && !autoCaptureEnabled) return
        if (PortalPadApp.instance.injector.displayId == 0) return
        maybeAutoArm(delayMs = 50L)
    }

    private fun shouldAutoCaptureNow(): Boolean =
        userEnabled ||
            (autoCaptureEnabled && !userExplicitlyDisabled && hasExternalPointerConnected(PortalPadApp.instance))

    fun maybeAutoArm(delayMs: Long = 0L) {
        if (!userEnabled && (!autoCaptureEnabled || userExplicitlyDisabled)) return
        val requestedAt = SystemClock.uptimeMillis()
        scope.launch {
            if (delayMs > 0L) kotlinx.coroutines.delay(delayMs)
            if (running) return@launch
            if (!shouldAutoCaptureNow()) return@launch
            val disp = PortalPadApp.instance.injector.displayId
            Log.i(
                TAG,
                "ARM-TIMING autoArm fired after ${SystemClock.uptimeMillis() - requestedAt}ms " +
                    "(requested delay=${delayMs}ms) displayId=$disp userEnabled=$userEnabled auto=$autoCaptureEnabled",
            )
            if (disp == 0) {
                Log.w(TAG, "ARM-TIMING GAVE UP: no external display yet, nothing will retry")
                return@launch
            }
            val t0 = SystemClock.uptimeMillis()
            val st = enable(userGrab)
            Log.i(TAG, "ARM-TIMING enable() took ${SystemClock.uptimeMillis() - t0}ms -> $st")
        }
    }

    // Observable, not polled. The settings page used to snapshot these once at
    // composition, so when the re-arm poller armed the mouse in the background the
    // strip kept showing stale text — which is why pressing Reconnect "fixed" it:
    // the press reassigned the local copy. A flow updates exactly when something
    // changes and costs nothing while idle, where a ticker would burn work to
    // rediscover state that was never observable in the first place.
    private val _lastStatus = kotlinx.coroutines.flow.MutableStateFlow("idle")
    val lastStatusFlow: kotlinx.coroutines.flow.StateFlow<String> = _lastStatus
    private val _running = kotlinx.coroutines.flow.MutableStateFlow(false)
    val runningFlow: kotlinx.coroutines.flow.StateFlow<Boolean> = _running

    /** True while the re-arm poller is retrying. Surfaced so the UI can say the
     *  mouse is being looked for, instead of showing nothing while a sleeping
     *  Bluetooth device takes ten-plus seconds to wake and enumerate. */
    private val _searching = kotlinx.coroutines.flow.MutableStateFlow(false)
    val searchingFlow: kotlinx.coroutines.flow.StateFlow<Boolean> = _searching

    /** Last status string returned by the privileged start call (for the UI).
     *  Reads and writes are unchanged for callers; the backing store is a flow. */
    var lastStatus: String
        get() = _lastStatus.value
        private set(v) { _lastStatus.value = v }
    var running: Boolean
        get() = _running.value
        private set(v) { _running.value = v }

    /** Pointer devices currently present, for the picker. Raw service output:
     *  "eventN<TAB>name" lines plus a trailing "OTHER<TAB>n" count. Returns
     *  empty when no privileged backend is bound, which the caller renders the
     *  same as "nothing found" — from the user's side those are the same thing. */
    fun listPointerDevices(): String {
        val backend = PortalPadApp.instance.activeBoundBackend ?: return ""
        if (!backend.isReady) return ""
        return runCatching { backend.listPointerDevices() }.getOrNull().orEmpty()
    }

    /**
     * Apply the input mode IMMEDIATELY, ahead of the persisted value.
     *
     * The pref write still happens and remains the source of truth; this only
     * removes the wait. A DataStore edit serialises to disk and the collector
     * re-emits afterwards, which is tens to hundreds of milliseconds — invisible
     * for a slider you drag, obvious for a mode you expect to snap. Same pattern
     * the keyboard picker uses when it pins its mode before the collector catches
     * up. Any in-flight scrub is dropped, since it belongs to the mode you left.
     */
    fun setInputModeNow(mode: Int) {
        if (inputMode == mode) return
        inputMode = mode
        stopSeek()
        Log.d(TAG, "input mode -> $mode (immediate)")
    }

    /** Start capture. [grab] requests exclusive access (suppresses the phone's
     *  own pointer). [learnOnly] starts a LISTEN-ONLY session for mapping with
     *  no display connected: nothing is ever injected (the read loop discards
     *  everything except the learn branch) and the session is released the
     *  moment the learn window resolves. Returns the status string (also
     *  stored in [lastStatus]). */
    @Synchronized
    fun enable(grab: Boolean = true, learnOnly: Boolean = false): String {
        // Internal teardown only — does NOT cancel the re-arm poller (this is
        // often called BY the poller). Public disable() handles opt-out.
        stopCapture()
        // No external display, no capture. Every AUTOMATIC path already checked
        // this — maybeAutoArm, startRearmPoller, and the readLoop re-arm all bail
        // on displayId 0 — but the three settings controls that call enable()
        // directly (Enable, Exclusive grab, Reconnect) did not. Any of them could
        // exclusively grab the mouse and inject at display 0, the phone: exactly
        // the invisible-capture state onExternalDisplayGone() exists to prevent.
        // Guarding here rather than in the UI covers all callers, including any
        // added later. "no-display" is already the string the status strip knows
        // how to render, so nothing downstream needs to change.
        // A learn-only session is EXEMPT deliberately (SH): it injects nothing
        // by construction, lives seconds, and the grab dies with the process,
        // so the invisible-capture hazard the guard exists for cannot occur.
        if (!learnOnly && PortalPadApp.instance.injector.displayId == 0) {
            Log.i(TAG, "enable() refused: no external display")
            lastStatus = "no-display"
            return lastStatus
        }
        val backend = PortalPadApp.instance.activeBoundBackend
        if (backend == null || !backend.isReady) {
            lastStatus = "ERR no privileged backend (select Shizuku or Root)"
            return lastStatus
        }
        val pipe = ParcelFileDescriptor.createPipe() // [read, write]
        val readEnd = pipe[0]
        val writeEnd = pipe[1]
        val libDir = PortalPadApp.instance.applicationInfo.nativeLibraryDir
        val status = try {
            backend.startMouseCapture(grab, libDir, preferDevice, writeEnd)
        } catch (t: Throwable) {
            Log.w(TAG, "startMouseCapture threw", t)
            "ERR ${t.message}"
        } finally {
            // The service dup'd its own copy across the binder; release ours so
            // EOF propagates correctly when the service stops.
            runCatching { writeEnd.close() }
        }
        lastStatus = status
        Log.i(TAG, "enable(grab=$grab learnOnly=$learnOnly) → $status")
        if (!learnOnly) userExplicitlyDisabled = false
        if (status.startsWith("ERR")) {
            isActivelyGrabbed = false
            runCatching { readEnd.close() }
            if (!learnOnly && (userEnabled || (autoCaptureEnabled && !userExplicitlyDisabled && hasExternalPointerConnected(PortalPadApp.instance))) &&
                status.contains("no-mouse-found")
            ) {
                startRearmPoller("enable-miss")
            }
            return status
        }
        val grabOk = status.contains("grab=OK")
        isActivelyGrabbed = grabOk
        if (grabOk) {
            injector.notifyTouchpadOrAirMouseActive()
            injector.dismissSystemPointer()
            scope.launch {
                for (waitMs in longArrayOf(120L, 250L, 450L)) {
                    kotlinx.coroutines.delay(waitMs)
                    if (isActivelyGrabbed) injector.dismissSystemPointer()
                }
            }
        }
        running = true
        val mySession = ++sessionId
        job = scope.launch { readLoop(readEnd, mySession, grabOk) }
        return status
    }

    /**
     * Resolve motion into one remote action, or none.
     *
     * NET accumulation over a window, then the dominant axis wins — never
     * per-tick. The capture is unambiguous about why: a horizontal two-finger
     * swipe produced 11 hwheel ticks AND 27 wheel ticks, and a vertical one
     * produced 31 horizontal ticks. Firing per tick would emit arrows on both
     * axes continuously. The jitter cancels in the SUM though (that same
     * horizontal swipe netted -11 against -1), so summing and comparing
     * magnitudes picked the right axis on all 14 movement bursts.
     *
     * Two guards, because one is not enough. [REMOTE_MIN] stops a stray tick
     * counting as a swipe; [REMOTE_DOMINANCE] stops a near-diagonal flick firing
     * a coin-toss direction — one burst came in at 1.1x, which is exactly the
     * case that should do nothing rather than something arbitrary.
     */
    private fun remoteMotion(dx: Int, dy: Int, wheel: Int, hwheel: Int) {
        val now = SystemClock.uptimeMillis()
        val active = dx != 0 || dy != 0 || wheel != 0 || hwheel != 0
        // Idle gap ends a stream-driven gesture (arrows). Seek does NOT reset here
        // — it is held by its own timer, and a stationary hold is silence, which
        // this reset would otherwise treat as the end.
        val seeking = repeatDir == REPEAT_REW || repeatDir == REPEAT_FF
        if (!seeking && now - lastRemoteAt > REMOTE_IDLE_MS) {
            accDx = 0; accDy = 0; accWheel = 0; accHWheel = 0
            repeatDir = REPEAT_NONE
        }
        if (active) lastRemoteAt = now

        // ARROWS repeat only while you keep moving: remoteMotion() runs per record,
        // so ongoing records ARE the "still swiping" signal, and this fires at most
        // once per REMOTE_REPEAT_MS while the same direction holds.
        if (repeatDir != REPEAT_NONE && !seeking) {
            if (!active) return
            if (now - lastFireAt >= REMOTE_REPEAT_MS) { fireRepeat(); lastFireAt = now }
            return
        }

        // Latched seek is running. Motion no longer sustains it (the device stops
        // reporting the moment your fingers go still), so the only thing that
        // matters here is a swipe the OTHER way, which flips direction. Everything
        // else is ignored: firing arrows mid-scrub would be noise, and a
        // same-direction swipe is already what is happening.
        if (seeking) {
            if (hwheel == 0) return
            val wantFf = if (reverseSeek) hwheel < 0 else hwheel > 0
            val nowDir = if (wantFf) REPEAT_FF else REPEAT_REW
            if (nowDir != repeatDir) startSeek(nowDir)
            return
        }

        accWheel += wheel
        accHWheel += hwheel
        if (fire(accHWheel, accWheel, REMOTE_WHEEL_MIN)) {
            val ff = if (reverseSeek) accHWheel < 0 else accHWheel > 0
            startSeek(if (ff) REPEAT_FF else REPEAT_REW)
            accWheel = 0; accHWheel = 0
            return
        }
        if (fire(accWheel, accHWheel, REMOTE_WHEEL_MIN)) {
            // Up only, matching the pad (Home on two-finger up; down unbound).
            if (accWheel > 0) { repeatDir = REPEAT_HOME; fireRepeat(); lastFireAt = now }
            accWheel = 0; accHWheel = 0
            return
        }

        accDx += dx
        accDy += dy
        val move = swipeDistance
        if (fire(accDx, accDy, move)) {
            repeatDir = if (accDx > 0) REPEAT_RIGHT else REPEAT_LEFT
            fireRepeat(); lastFireAt = now
            accDx = 0; accDy = 0
        } else if (fire(accDy, accDx, move)) {
            // Screen Y grows downward, so positive dy is a downward swipe.
            repeatDir = if (accDy > 0) REPEAT_DOWN else REPEAT_UP
            fireRepeat(); lastFireAt = now
            accDx = 0; accDy = 0
        }
    }

    /**
     * Start (or redirect) continuous seek. Runs until [stopSeek] — it is a LATCH,
     * not a hold.
     *
     * The raw capture settled why. A Bluetooth trackpad reports MOTION, not
     * contact: notifications arrive in 200-700ms bursts while your fingers move,
     * then stop within ~160ms of them going still, even though they are still
     * down. So "held" and "lifted" produce byte-identical data — nothing — and no
     * amount of logic can separate them. Seek-while-held is therefore not
     * implementable on this hardware.
     *
     * A latch sidesteps the problem entirely: swipe to start scrubbing, tap to
     * drop out, which is also how most media remotes behave. The safety cap stops
     * a forgotten seek running forever.
     */
    private fun startSeek(dir: Int) {
        repeatDir = dir
        repeatJob?.cancel()
        repeatJob = scope.launch {
            val startedAt = SystemClock.uptimeMillis()
            Log.d(TAG, "REMOTE seek START dir=$dir (tap to stop)")
            while (isActive) {
                if (repeatDir == REPEAT_FF) injector.mediaFf() else injector.mediaRew()
                delay(REMOTE_REPEAT_MS)
                if (SystemClock.uptimeMillis() - startedAt > REMOTE_SEEK_MAX_MS) {
                    Log.d(TAG, "REMOTE seek auto-stop (cap)")
                    break
                }
            }
            if (repeatDir == REPEAT_FF || repeatDir == REPEAT_REW) {
                repeatDir = REPEAT_NONE
                accWheel = 0; accHWheel = 0
            }
        }
    }

    /** Drop out of continuous seek. Returns true if a seek was actually running,
     *  so the caller can CONSUME the tap that stopped it rather than also sending
     *  OK — stopping and selecting on one tap would be two actions at once. */
    private fun stopSeek(): Boolean {
        if (repeatDir != REPEAT_FF && repeatDir != REPEAT_REW) return false
        repeatJob?.cancel()
        repeatJob = null
        repeatDir = REPEAT_NONE
        accWheel = 0; accHWheel = 0
        Log.d(TAG, "REMOTE seek STOP")
        return true
    }

    /** Emit the action for the current repeat direction. Home does NOT repeat —
     *  re-firing Home while a swipe drifts would bounce you to the launcher over
     *  and over; it latches once and waits for the idle reset. */
    private fun fireRepeat() {
        when (repeatDir) {
            REPEAT_LEFT -> injector.dpadLeft()
            REPEAT_RIGHT -> injector.dpadRight()
            REPEAT_UP -> injector.dpadUp()
            REPEAT_DOWN -> injector.dpadDown()
            // REW / FF are NOT here — they run on the independent startSeek timer,
            // because a stationary hold sends no records for this to ride on.
            REPEAT_HOME -> { injector.home(); repeatDir = REPEAT_DONE }
        }
        Log.d(TAG, "REMOTE repeat=$repeatDir")
    }

    /** [axis] has crossed [min] and beats [other] by enough to be deliberate. */
    private fun fire(axis: Int, other: Int, min: Int): Boolean =
        kotlin.math.abs(axis) >= min &&
            kotlin.math.abs(axis) >= kotlin.math.abs(other) * REMOTE_DOMINANCE

    private suspend fun readLoop(
        readEnd: ParcelFileDescriptor,
        mySession: Int,
        grabSucceeded: Boolean,
    ) {
        val input = ParcelFileDescriptor.AutoCloseInputStream(readEnd)
        // MUST equal writeRecord()'s size in portalpad_mouse.cpp. A mismatch does
        // not fail loudly: reads stay aligned to the wrong stride and every field
        // slides, so dx starts arriving as dy. Six int32s: type, dx, dy, buttons,
        // wheel, hwheel.
        val buf = ByteArray(RECORD_BYTES)
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        var lastButtons = 0
        // True when the loop ended because the device's stream hit EOF (mouse
        // gone / asleep) rather than a deliberate stop — gates the self-heal.
        var endedByEof = false
        // Held modifier meta (Shift/Ctrl/Alt/Meta) for the keyboard relay — reset
        // per session so a modifier can't get "stuck" across reconnects.
        var heldMeta = 0
        // Left-button click-vs-drag state (per session).
        var leftHeld = false
        var leftDragging = false
        var dragResolved = false
        var dragAccX = 0f
        var dragAccY = 0f
        try {
            while (currentCoroutineContext().isActive) {
                var off = 0
                while (off < RECORD_BYTES) {
                    val n = input.read(buf, off, RECORD_BYTES - off)
                    if (n < 0) { endedByEof = true; return } // EOF — service stopped or device gone
                    off += n
                }
                // Tagged records: type 0 = mouse, type 1 = keyboard key.
                // The keyboard half of a combo device rides the SAME grabbed stream.
                // Map the Linux code to an Android keycode, track held modifiers,
                // and inject on the external display with the right meta so capitals,
                // symbols and chords (Ctrl+A, etc.) compose. Unmapped codes drop.
                val type = bb.getInt(0)
                // LISTEN-ONLY learn session (no display): the ONLY consumer is
                // the learn branch. Nothing is injected, moved, or typed -
                // that is the whole contract that makes the no-display
                // exemption in enable() safe.
                if (learnOnlySession) {
                    if (type == 1) {
                        handleModeToggleKey(bb.getInt(4), bb.getInt(8) != 0)
                    }
                    continue
                }
                if (type == 1) {
                    val linux = bb.getInt(4)
                    val down = bb.getInt(8) != 0
                    // Remap first: a learn window or a mapped toggle button
                    // consumes the event entirely - it must not also type.
                    // (The temporary DIAG-KEYREC per-record log that lived here
                    // solved the firmware-gesture mystery - SH's puck emits a
                    // DIFFERENT code per gesture: 158 single, 172 double. If a
                    // remap mystery returns, restore it before theorizing.)
                    if (handleModeToggleKey(linux, down)) continue
                    // External mouse Back button (KEY_BACK=158, BTN_SIDE=0x113, BTN_BACK=0x116):
                    // dispatch guarded Back directly to the external display.
                    if (linux == 158 || linux == 0x113 || linux == 0x116) {
                        if (down) {
                            Log.d(TAG, "MOUSE BACK linux=$linux -> injector.backGuarded()")
                            injector.backGuarded()
                        }
                        continue
                    }
                    KeyboardRelayMap.modifierMeta(linux)?.let { bits ->
                        heldMeta = if (down) heldMeta or bits else heldMeta and bits.inv()
                    }
                    KeyboardRelayMap.toAndroid(linux)?.let { androidCode ->
                        Log.d(TAG, "KEY linux=$linux→android=$androidCode down=$down meta=$heldMeta")
                        injector.injectKeyEvent(androidCode, down, heldMeta)
                    }
                    continue
                }
                val dx = bb.getInt(4)
                val dy = bb.getInt(8)
                val buttons = bb.getInt(12)
                val wheel = bb.getInt(16)
                val hwheel = bb.getInt(20)

                val pressed = buttons and lastButtons.inv()
                val released = lastButtons and buttons.inv()

                if (inputMode == MODE_REMOTE) {
                    // REMOTE. Same vocabulary as the Remote tab's gesture pad, fed
                    // by a grabbed device instead of the touchscreen. No cursor
                    // moves and no clicks are injected: in this mode the device is
                    // a remote control, not a pointer.
                    //
                    // Mapping is EVIDENCE-BASED, from a capture of this hardware
                    // (gestures.txt): one finger arrives as dx/dy, one-finger tap
                    // as BTN_LEFT, two-finger swipes as wheel/hwheel because the
                    // firmware resolves the finger count itself, and two-finger
                    // tap/hold as BTN_RIGHT. Middle never appears on that device,
                    // which is why nothing is bound to it.
                    if (pressed and BTN_LEFT != 0) leftDownAt = SystemClock.uptimeMillis()
                    if (pressed and BTN_RIGHT != 0) rightDownAt = SystemClock.uptimeMillis()

                    if (released and BTN_LEFT != 0) {
                        val held = SystemClock.uptimeMillis() - leftDownAt
                        // A tap while scrubbing DROPS OUT of seek and does nothing
                        // else. Sending OK as well would fire two actions on one
                        // tap, and "stop" is unmistakably what you meant.
                        if (stopSeek()) {
                            Log.d(TAG, "REMOTE left tap consumed to stop seek")
                        } else {
                            // Measured on-device: taps landed at 87-97ms, holds at
                            // 681ms+. Nothing fell in between, so this split has
                            // real clearance rather than being a guess.
                            if (held < REMOTE_HOLD_MS) injector.dpadCenter() else injector.back()
                            Log.d(TAG, "REMOTE left ${held}ms -> ${if (held < REMOTE_HOLD_MS) "OK" else "BACK"}")
                        }
                    }
                    if (released and BTN_RIGHT != 0) {
                        val held = SystemClock.uptimeMillis() - rightDownAt
                        if (stopSeek()) {
                            Log.d(TAG, "REMOTE right tap consumed to stop seek")
                        } else {
                            if (held < REMOTE_HOLD_MS) injector.mediaPlayPause() else injector.mediaStop()
                            Log.d(TAG, "REMOTE right ${held}ms")
                        }
                    }

                    remoteMotion(dx, dy, wheel, hwheel)
                    lastButtons = buttons
                    continue
                }

                val s = sensitivity
                val sdx = dx * s
                val sdy = dy * s

                // Left DOWN: begin a pending press (click vs drag decided by
                // movement). Right DOWN: immediate context tap. Middle: unmapped.
                if (pressed and BTN_LEFT != 0) {
                    leftHeld = true; leftDragging = false; dragResolved = false
                    dragAccX = 0f; dragAccY = 0f
                }
                if (pressed and BTN_RIGHT != 0) injector.rightClick()

                // Movement: while left is held, accumulate until it crosses the
                // drag threshold, then promote to a real held touch-drag (window
                // move, or resize when grabbing an edge); below threshold it's
                // just cursor movement, so a quick press/release stays a click.
                if (dx != 0 || dy != 0) {
                    if (grabSucceeded) {
                        // Captured exclusively: Android's black pointer is suppressed,
                        // so PortalPad's cursor IS the active pointer and must stay visible.
                        injector.notifyTouchpadOrAirMouseActive()
                    } else {
                        // Un-grabbed fallback: keep white cursor hidden while external mouse
                        // is driving Android's black pointer so two cursors never coexist.
                        injector.notifyExternalMouseMoving(-1L)
                    }
                    if (leftHeld && leftDragging) {
                        injector.dragMove(sdx, sdy)
                    } else if (leftHeld && !dragResolved) {
                        injector.pointerMove(sdx, sdy)
                        dragAccX += sdx; dragAccY += sdy
                        if (kotlin.math.hypot(dragAccX.toDouble(), dragAccY.toDouble()) > DRAG_THRESHOLD) {
                            dragResolved = true
                            leftDragging = true
                            injector.dragStart()
                        }
                    } else {
                        injector.pointerMove(sdx, sdy)
                    }
                }

                // Left UP: end the drag, or — if it never became one — click.
                if (released and BTN_LEFT != 0) {
                    if (leftDragging) injector.dragEnd() else injector.leftClick()
                    leftHeld = false; leftDragging = false; dragResolved = false
                }

                lastButtons = buttons

                // Wheel → discrete ACTION_SCROLL (no fling): content moves only
                // while the wheel turns. vNotches +1 = up; reverseScroll flips
                // for devices whose wheel reports the opposite sign.
                if (wheel != 0) {
                    var v = wheel * scrollSpeed
                    if (reverseScroll) v = -v
                    injector.wheelScroll(v)
                }
                // Horizontal wheel. On a trackpad-style device this IS the
                // two-finger sideways swipe: the firmware resolves the second
                // finger for us and reports it here. It was dropped in the native
                // reader, so sideways swipes did nothing at all until now.
                //
                // Not inverted by reverseScroll: that setting exists for the
                // vertical direction argument (wheel-up scrolls content up or
                // down, which people disagree about). Sideways has no equivalent
                // dispute, and flipping it would just be wrong half the time.
                if (hwheel != 0) {
                    injector.wheelScrollHorizontal(hwheel * scrollSpeed)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "mouse read loop ended", t)
        } finally {
            runCatching { input.close() }
            running = false
            isActivelyGrabbed = false
            // Self-heal: if the DEVICE went away on its own (EOF) — not a stop we
            // initiated (which records this session in stoppedSessionId) — and the
            // user is still opted in with an external display, poll to re-arm so
            // the mouse reconnects on wake without toggling anything.
            if (endedByEof && stoppedSessionId != mySession && shouldAutoCaptureNow() &&
                PortalPadApp.instance.injector.displayId != 0
            ) {
                startRearmPoller("device-gone")
            }
        }
    }

    @Synchronized
    fun disable(explicit: Boolean = false) {
        if (explicit) userExplicitlyDisabled = true
        // User opt-out: stop polling too, then tear the capture down.
        pollJob?.cancel()
        pollJob = null
        stopCapture()
    }

    /** Tear down the active capture without touching the re-arm poller. Records
     *  the current session as deliberately stopped so its read loop doesn't
     *  self-heal. Used by both [enable] (re-arm) and [disable] (opt-out). */
    @Synchronized
    private fun stopCapture() {
        stoppedSessionId = sessionId
        job?.cancel()
        job = null
        repeatJob?.cancel()
        repeatJob = null
        repeatDir = REPEAT_NONE
        running = false
        isActivelyGrabbed = false
        runCatching { PortalPadApp.instance.activeBoundBackend?.stopMouseCapture() }
        if (lastStatus != "idle" && !lastStatus.startsWith("ERR")) lastStatus = "stopped"
    }

    /**
     * Re-arm poller: while the user is opted in and an external display is
     * present but no capture is running, retry [enable] on an interval until a
     * mouse is found (BT finished settling / the mouse woke), the user opts out,
     * the display goes away, or the bounded window elapses. Started on a
     * no-mouse-found arm attempt and on an unsolicited device-gone EOF; only one
     * runs at a time. Keeps polling only while the result is still
     * "no-mouse-found" — a hard error won't fix itself by waiting.
     */
    private fun startRearmPoller(reason: String) {
        synchronized(this) {
            if (!shouldAutoCaptureNow() || running) return
            if (PortalPadApp.instance.injector.displayId == 0) return
            if (pollJob?.isActive == true) return
            pollJob = scope.launch {
                Log.i(TAG, "rearm poll start ($reason)")
                _searching.value = true
                try {
                val deadline = System.currentTimeMillis() + REARM_WINDOW_MS
                while (System.currentTimeMillis() < deadline) {
                    kotlinx.coroutines.delay(REARM_INTERVAL_MS)
                    if (!shouldAutoCaptureNow() || PortalPadApp.instance.injector.displayId == 0) {
                        Log.i(TAG, "rearm poll stop (opted out / no display)")
                        return@launch
                    }
                    if (running) return@launch
                    val st = enable(userGrab)
                    if (!st.startsWith("ERR")) {
                        Log.i(TAG, "rearm poll → armed ($st)")
                        return@launch
                    }
                    if (!st.contains("no-mouse-found")) {
                        Log.i(TAG, "rearm poll stop (hard error: $st)")
                        return@launch
                    }
                }
                Log.i(TAG, "rearm poll window elapsed")
                } finally { _searching.value = false }
            }
        }
    }

    companion object {
        private const val TAG = "BtMouseController"

        fun isExternalPointerDevice(device: android.view.InputDevice?): Boolean {
            if (device == null || device.isVirtual) return false
            return (device.sources and android.view.InputDevice.SOURCE_MOUSE) ==
                android.view.InputDevice.SOURCE_MOUSE
        }

        fun hasExternalPointerConnected(context: android.content.Context): Boolean {
            val im = context.getSystemService(android.content.Context.INPUT_SERVICE)
                as? android.hardware.input.InputManager ?: return false
            return im.inputDeviceIds.any { id ->
                isExternalPointerDevice(android.view.InputDevice.getDevice(id))
            }
        }

        /** Bytes per record on the pipe: six little-endian int32s. Mirrors
         *  writeRecord() in portalpad_mouse.cpp and must change with it. */
        private const val RECORD_BYTES = 24

        const val MODE_MOUSE = 0
        const val MODE_REMOTE = 1

        /** Tap-versus-hold split. Measured on-device: taps 87-97ms, holds 681ms+,
         *  nothing between. Matches GesturePad's 380ms so the two surfaces feel
         *  the same. */
        private const val REMOTE_HOLD_MS = 380L

        /** A pause ends the gesture and clears the accumulators, so a slow drift
         *  cannot add up across unrelated movements and fire on its own. */
        private const val REMOTE_IDLE_MS = 350L

        /** Net wheel/hwheel ticks before a two-finger swipe counts. Observed
         *  deliberate swipes netted 5-28; jitter on the off-axis netted 0-6. */
        private const val REMOTE_WHEEL_MIN = 4

        /** How far the winning axis must beat the other. One burst came in at
         *  1.1x, near-diagonal and genuinely ambiguous; 1.5 makes that do nothing
         *  rather than guess. */
        private const val REMOTE_DOMINANCE = 1.5f

        /** Auto-repeat interval while a swipe is held. ~3 arrows/sec — brisk
         *  enough to cross a grid, slow enough not to overshoot. */
        private const val REMOTE_REPEAT_MS = 300L

        /** Safety cap on a latched seek, so one forgotten swipe cannot scrub
         *  forever if the stop tap is never given. */
        private const val REMOTE_SEEK_MAX_MS = 60_000L

        // Repeat directions. DONE means "fired and must not repeat" (Home).
        private const val REPEAT_NONE = 0
        private const val REPEAT_LEFT = 1
        private const val REPEAT_RIGHT = 2
        private const val REPEAT_UP = 3
        private const val REPEAT_DOWN = 4
        private const val REPEAT_REW = 5
        private const val REPEAT_FF = 6
        private const val REPEAT_HOME = 7
        private const val REPEAT_DONE = 8
        // Re-arm poll: total window to keep looking for the mouse, and the gap
        // between attempts. Bounded so an opted-in user with no mouse paired
        // doesn't spawn discovery forever.
        private const val REARM_WINDOW_MS = 60_000L
        private const val REARM_INTERVAL_MS = 1_500L
        private const val DEFAULT_SENSITIVITY = 2.5f
        private const val DEFAULT_SCROLL_SPEED = 1.0f
        // Cursor-space pixels of movement (post-sensitivity) before a held-left
        // press promotes from a click to a drag.
        private const val DRAG_THRESHOLD = 10.0
        private const val BTN_LEFT = 1   // record bit 0
        private const val BTN_RIGHT = 2  // record bit 1
        // private const val BTN_MIDDLE = 4 // bit 2 — reserved (Phase 3 mapping)

        // ── Mode-toggle button remap ──
        private const val LEARN_WINDOW_MS = 8000L
        private const val MODE_TOGGLE_DEBOUNCE_MS = 400L
        // Activation styles for the mapped button (EXT_MOUSE_TOGGLE_ACTIVATION).
        const val ACT_TAP = 0
        const val ACT_DOUBLE = 1
        const val ACT_HOLD = 2
        // How a mapping's gesture shows up on the wire (ToggleMapping.pattern).
        const val PATTERN_TIMING = 0
        const val PATTERN_ARRIVAL = 1
        // 600, not 400: measured down-to-down for a casual double tap on a
        // side button runs 300-500ms (it includes the first press's own
        // duration); 400 rejected real doubles. System double-click windows
        // are ~500ms for the same reason.
        private const val DOUBLE_TAP_MS = 600L
        private const val HOLD_MS = 500L
        /** How long a double-intent learn waits for the second press before
         *  concluding the code is a firmware gesture (fires on press). */
        private const val DOUBLE_LEARN_RESOLVE_MS = 700L

        /** Lines of "device\tcode[\tgesture[\tpattern]]". Legacy 2/3-field
         *  lines read as timing-pattern entries (3-field act becomes the
         *  gesture; firmware learns from that era were stored as tap and need
         *  one re-learn - accepted with SH). */
        fun decodeToggleMap(raw: String): List<ToggleMapping> =
            raw.lineSequence().mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size < 2 || parts[0].isBlank()) return@mapNotNull null
                val code = parts[1].toIntOrNull() ?: return@mapNotNull null
                val gesture = (parts.getOrNull(2)?.toIntOrNull() ?: ACT_TAP)
                    .coerceIn(ACT_TAP, ACT_HOLD)
                val pattern = (parts.getOrNull(3)?.toIntOrNull() ?: PATTERN_TIMING)
                    .coerceIn(PATTERN_TIMING, PATTERN_ARRIVAL)
                ToggleMapping(parts[0], code, gesture, pattern)
            }.distinct().toList()

        fun encodeToggleMap(map: List<ToggleMapping>): String =
            map.joinToString("\n") { "${it.device}\t${it.code}\t${it.gesture}\t${it.pattern}" }

        /** Row suffix naming the GESTURE a mapping represents - always shown,
         *  so the user can see which entries the dropdown currently arms. */
        fun gestureSuffix(gesture: Int): String = when (gesture) {
            ACT_DOUBLE -> " · double tap"
            ACT_HOLD -> " · hold"
            else -> " · tap"
        }

        /** Human name for a Linux input code, for the mapped-devices list and
         *  the just-mapped strip. Real names wherever one exists (SH): the
         *  mouse-button table first, then common navigation/media codes, then
         *  the keyboard relay map's Android name; the bare number only when
         *  nothing at all is known. */
        fun buttonLabel(code: Int): String = when (code) {
            0x113 -> "Side button"
            0x114 -> "Extra button"
            0x115 -> "Forward button"
            0x116 -> "Back button"
            0x117 -> "Task button"
            158 -> "Back"          // KEY_BACK - the classic thumb button
            159 -> "Forward"       // KEY_FORWARD
            172 -> "Home"          // KEY_HOMEPAGE
            164 -> "Play/Pause"
            163 -> "Next track"
            165 -> "Previous track"
            113 -> "Mute"
            114 -> "Volume down"
            115 -> "Volume up"
            217 -> "Search"
            else -> {
                val androidCode = KeyboardRelayMap.toAndroid(code)
                if (androidCode != null) {
                    android.view.KeyEvent.keyCodeToString(androidCode)
                        .removePrefix("KEYCODE_")
                        .replace('_', ' ')
                        .lowercase()
                        .replaceFirstChar { it.uppercase() }
                } else {
                    "Button $code"
                }
            }
        }
    }
}
