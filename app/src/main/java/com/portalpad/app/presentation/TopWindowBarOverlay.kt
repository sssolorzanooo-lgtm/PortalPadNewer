package com.portalpad.app.presentation

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.toColorInt
import com.portalpad.app.data.RunningTask
import com.portalpad.app.data.toGradientDrawableType
import com.portalpad.app.data.WindowBounds
import com.portalpad.app.service.FreeformManager
import com.portalpad.app.service.InputInjector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * DeX-style window controls for desktop mode (phase 2). Two parts:
 *
 *  1. A top-edge control bar (revealed when the cursor reaches the top of the
 *     display) with: Move, Resize, Tile-left, Tile-right, Maximize, Close —
 *     all acting on the frontmost task.
 *
 *  2. A click-to-grab interaction for free Move / Resize. Because injected
 *     taps don't stream MOVE events to overlay windows the way a physical
 *     finger does, we can't use a normal touch-drag. Instead:
 *        - tap "Move"  -> the window then follows the CURSOR; a translucent
 *          proxy outline tracks it; the next left-click commits the window's
 *          new top-left there (size preserved).
 *        - tap "Resize" -> the proxy's bottom-right corner follows the cursor;
 *          the next left-click commits the new size (top-left preserved).
 *     The cursor position comes from [InputInjector.cursorPosition] and the
 *     commit click from [InputInjector.clickEvents] -- the same injected-cursor
 *     plumbing the rest of the app uses.
 *
 * Constructed by PortalPadForegroundService in BOTH modes, with [isDesktopMode]
 * selecting the control set: desktop gets the window operations (minimize,
 * maximize, unmaximize, caption, arrange, stack, session, close), standard gets
 * navigation only (back, home, close). An earlier version of this note claimed the
 * bar existed only in desktop mode, which was wrong and would have ruled out
 * putting anything mode-independent on it.
 */
class TopWindowBarOverlay(
    serviceContext: Context,
    private val display: Display,
    private val freeform: FreeformManager,
    private val injector: InputInjector,
    private val isDesktopMode: Boolean,
) {
    private val overlayHost = OverlayHost.forDisplay(display, serviceContext)
    private val displayContext = overlayHost.context
    private val windowManager = overlayHost.windowManager

    private var container: LinearLayout? = null
    private var barParams: WindowManager.LayoutParams? = null
    // Intrinsic min width of the assembled control row (buttons + padding), so the
    // bar window is never set narrower than its controls (which would clip them).
    private var barContentMinW: Int = 0
    private var appIconView: android.widget.ImageView? = null
    private var appTitleView: TextView? = null
    /** The window-control buttons, kept so the cursor-click flow can hit-test
     *  them by on-screen bounds (robust against injected taps not reaching a
     *  TextView inside a top-edge accessibility overlay). */
    private var controlButtons: List<TextView> = emptyList()

    // Proxy outline shown during a Move/Resize grab.
    private var proxy: View? = null
    private var proxyParams: WindowManager.LayoutParams? = null
    // Compatibility resize preview used by devices whose native freeform edge
    // drag is unsafe or unsupported. Unlike the top-bar click-to-grab proxy,
    // this follows a held edge drag and is committed by InputInjector on lift.
    private var compatibilityResizeProxy: View? = null
    private var compatibilityResizeParams: WindowManager.LayoutParams? = null
    private var pendingCompatibilityResizeHide: Runnable? = null
    private val compatibilityResizeLock = Any()
    private var pendingCompatibilityResizeBounds: WindowBounds? = null
    private var compatibilityResizeUpdatePosted = false

    private var scope: CoroutineScope? = null
    private var revealJob: Job? = null
    private var topEdgeDwellJob: Job? = null
    private var lastRevealDiag = 0L
    private var autoHideJob: Job? = null
    // Persistent observer (separate from [scope], which is recreated on show/
    // dismiss) that watches the top-bar config and restyles the live bar when the
    // user changes settings in Workspace — so changes preview live on the glasses.
    private var configObserverScope: CoroutineScope? = null
    // Translucent overlay rectangle showing the detection zone while the user is
    // tuning it on the Top Bar customization sub-page. Preview-only.
    private var zoneHighlightView: View? = null
    private var zoneHighlightParams: WindowManager.LayoutParams? = null
    @Volatile private var zonePreviewActive = false
    // After a *timed* auto-hide, suppress re-revealing until the cursor has left
    // the reveal band at least once — otherwise a lingering cursor would
    // instantly re-show the bar and it would never stay hidden.
    @Volatile private var suppressRevealUntilExit = false
    private enum class TopInteractionOwner { NONE, PORTALPAD, NATIVE_CAPTION }
    /**
     * First surface entered at the top keeps ownership until the pointer moves
     * below the whole top interaction area. This prevents PortalPad and Android
     * caption chrome from repeatedly hiding each other while the user aims.
     */
    @Volatile private var topInteractionOwner = TopInteractionOwner.NONE
    // Layout actions move caption chrome underneath the cursor. Keep the bar
    // hidden until one deliberate exit so those fresh captions cannot re-open it.
    @Volatile private var layoutActionYieldUntilExit = false
    private var cursorJob: Job? = null
    private var clickJob: Job? = null
    private var hoverJob: Job? = null
    // Tier-2 availability greying: a poll (while the bar is shown) dims each
    // control whose action doesn't currently apply. controlAvailability pairs each
    // desktop button with its precondition over the live window state.
    private var availabilityPollJob: Job? = null
    private data class WinState(
        val count: Int,
        val hasMaximized: Boolean,
        val hasSession: Boolean,
        /** User windows a maximize could still act on (not already maximized by
         *  tracked id or flagged package). Mirrors hasMaximized's philosophy:
         *  a control lights only when it would actually do something. */
        val maximizable: Int,
        /**
         * A `caption_handle` node exists on the external display right now, so
         * there is genuinely a caption to collapse or expand.
         *
         * REPLACES `count >= 1` for the caption button, same philosophy as
         * maximizable: not every device HAS an Android caption bar. Pixel
         * freeform windows render completely bare (confirmed on device;
         * `dumpsys window` returns zero caption matches), so the toggle there
         * was a control for something that does not exist.
         *
         * DELIBERATELY REACTIVE, NOT A REMEMBERED DEVICE FACT. SH spotted the
         * flaw in caching it: a FULLSCREEN window has no caption even on a
         * device that supports them, so a probe taken at the wrong moment would
         * conclude "this device has no caption bar" and permanently hide a
         * working control on the S24. Asking "is there a caption to act on RIGHT
         * NOW" is always answerable and self-corrects the moment a freeform
         * window opens. Both reasons for absence - device lacks captions, or
         * this window is fullscreen - imply the same correct action: dim the
         * caption action and explain why it is unavailable when selected.
         *
         * Note a MAXIMIZED desktop window still exposes a handle (the collapsed
         * mini pill is itself a caption_handle node, see [CaptionHandle]), so
         * the button stays lit there; only genuinely immersive windows dim it.
         */
        val hasCaption: Boolean,
    )
    private var lastWinState: WinState? = null
    private var controlAvailability: List<Pair<TextView, (WinState) -> Boolean>> = emptyList()
    private var hoveredButton: View? = null
    /** The caption-toggle button, kept for the directional tooltip refresh
     *  (desktop bar only; null on the standard bar and after dismiss). */
    private var captionButton: TextView? = null

    // Hover tooltip: a small pill shown below the hovered control button (mirrors
    // the dock's mic/search tooltip, but View-based since this bar isn't Compose).
    private var tooltipView: TextView? = null
    private var tooltipParams: WindowManager.LayoutParams? = null

    // Close-all confirmation card (cursor-clickable, hit-tested like the bar
    // buttons). Shown when ✕ is tapped; "Close all" closes every app on the
    // external display, "Cancel" dismisses.
    private var confirmView: View? = null
    private var confirmParams: WindowManager.LayoutParams? = null
    private var confirmButtons: List<TextView> = emptyList()

    @Volatile private var shown = false
    @Volatile private var displayW = 0
    @Volatile private var displayH = 0
    @Volatile private var density = 1f
    @Volatile private var topBarCfg = com.portalpad.app.data.TopBarConfig()

    // Coalesces top-bar "Bar size" slider ticks into a single rebuild after the
    // drag settles (a size change alters `density`, which is baked into the views,
    // so it needs a full rebuild rather than a live restyle).
    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // 2032-attach retry (dock parity). A transient a11y-token error at attach
    // used to be SWALLOWED here (try/catch { log; return }), leaving a zombie
    // overlay object with no window until the next full attachVdOverlays pass —
    // the "top bar just never shows" bug (window dump: dock + taskbar attached,
    // no top-bar window). The dock got a 6×400ms retry in v1.3; this is the
    // same cadence. Cancelled by dismiss().
    private var attachRetries = 0
    private var pendingAttachRetry: Runnable? = null

    /** True when the bar's window is actually added (container live). */
    val isAttached: Boolean get() = container != null
    private var pendingSizeRecreate: Runnable? = null

    private enum class Mode { IDLE, MOVE, RESIZE }
    @Volatile private var mode = Mode.IDLE

    // The task currently being manipulated, and a working copy of its bounds.
    @Volatile private var grabTask: RunningTask? = null
    @Volatile private var workBounds: WindowBounds? = null
    @Volatile private var lastPreview: WindowBounds? = null

    fun show() {
        if (container != null) return
        // Read the user's top-bar config (position / width / duration / reveal
        // zone) up front, same pattern as the dock overlay.
        topBarCfg = runCatching {
            kotlinx.coroutines.runBlocking {
                com.portalpad.app.PortalPadApp.instance.prefs.topBarConfig
                    .first()
            }
        }.getOrDefault(com.portalpad.app.data.TopBarConfig())
        // Pin the bar's sizing to the 360-dpi look regardless of the glasses'
        // actual VD density. At 360 dpi this equals the real density (no change);
        // at other DPIs it keeps the bar from overflowing/overlapping by holding
        // the 360 physical size. 360/160 = 2.25.
        //
        // Then apply the user's uniform size scale on top: because EVERY dimension
        // in this bar (icons, text, padding, and thus the content-driven height) is
        // derived from `density`, multiplying it here scales the whole bar together
        // — taller bar ⇒ proportionally bigger icons. Clamped 60–160% so icons can
        // never become untappable-small or absurdly large.
        val sizeScale = topBarCfg.sizePct.coerceIn(60, 160) / 100f
        density = (360f / 160f) * sizeScale

        @Suppress("DEPRECATION")
        val metrics = DisplayMetrics().also { display.getRealMetrics(it) }
        displayW = metrics.widthPixels
        displayH = metrics.heightPixels

        val bar = LinearLayout(displayContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // Mirror the dock's gradient (same slate-blue colors + transparency)
            // but in the OPPOSITE direction: the dock fades light→dark top→bottom,
            // so the top bar fades dark→light top→bottom. Rounded on all corners
            // to match the dock's pill shape.
            background = GradientDrawable(
                topBarCfg.style.drawableOrientation(),
                topBarCfg.style.drawableColors(),
            ).apply {
                gradientType = topBarCfg.style.gradientType.toGradientDrawableType()
                if (gradientType != GradientDrawable.LINEAR_GRADIENT) {
                    setGradientCenter(topBarCfg.style.centerXFrac(), topBarCfg.style.centerYFrac())
                    gradientRadius = topBarCfg.style.radiusFrac() * displayW
                }
                // Biased balance → custom stop offsets (API 29+; minSdk is 30).
                topBarCfg.style.drawableOffsets()?.let { setColors(topBarCfg.style.drawableColors(), it) }
                cornerRadius = topBarCfg.style.cornerRadiusDp * density   // rounded on ALL corners
                setStroke((1 * density).toInt(), "#26FFFFFF".toColorInt())
            }
            elevation = 16f * density
            val padH = (14 * density).toInt()
            val padV = (3 * density).toInt()   // shorter bar; icon size unchanged
            setPadding(padH, padV, padH, padV)
            visibility = View.GONE
        }

        // Left slot is intentionally empty for now — reserved for the status
        // bar (clock / battery / wifi / notifications) added in a later build.
        // A flexible spacer pushes the controls to the right edge of the wide bar.
        bar.addView(View(displayContext).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })

        // Controls depend on mode. Desktop: window controls (minimize / ❐ /
        // close). Non-desktop (single fullscreen app): Back / Home / Close.
        // Kept as references so the click flow can hit-test them by on-screen
        // bounds (injected taps don't reliably reach a TextView inside a
        // top-edge accessibility overlay on all OEMs).
        // Controls, grouped so a hairline separator can sit between categories:
        //   desktop  → single-window ops | layout-all | session | close
        //   standard → navigation | close
        // Each control's onClick is guarded by its precondition (window count /
        // maximized / saved session) so it never opens empty UI — e.g. the
        // on-phone arrange/maximize/minimize pickers with no windows to act on.
        // controlButtons stays the FLAT list (the click hit-test walks it by
        // on-screen bounds); the bar gets buttons + separators between groups.
        val controlGroups: List<List<TextView>> = if (isDesktopMode) {
            val btnMinimize = controlButton("\u2212", "Minimize window\u2026") {   // − — one window: minimize it; several: pick on phone
                onSingleOrPick(
                    emptyMsg = "No windows to minimize",
                    single = { task ->
                        withContext(Dispatchers.IO) {
                            runCatching { freeform.minimizeByClose(task.taskId, task.packageName, task.bounds) }
                        }
                        maybeShowWallpaperIfEmpty()
                    },
                    multi = { launchMinimizePickerOnPhone() },
                )
            }
            // Font glyph on purpose, NOT a drawn mirror of Unmaximize: a mirrored pair
            // differed only in arrow direction, which SH judged too easy to confuse
            // at bar size (tried 2026-07-22, reverted same day). Glyph-vs-icon keeps
            // the adjacent pair unmistakable at a glance.
            val btnMaximize = controlButton("\u26F6", "Maximize window\u2026") {   // ⛶ — one window: maximize it; several: pick on phone
                onSingleOrPick(
                    emptyMsg = "No windows to maximize",
                    single = { task ->
                        val m = DisplayMetrics().also { @Suppress("DEPRECATION") display.getRealMetrics(it) }
                        withContext(Dispatchers.IO) {
                            runCatching {
                                freeform.maximizeByResize(
                                    taskId = task.taskId,
                                    component = task.topActivity,
                                    packageName = task.packageName,
                                    current = task.bounds,
                                    displayId = display.displayId,
                                    displayW = m.widthPixels.coerceAtLeast(1),
                                    displayH = m.heightPixels.coerceAtLeast(1),
                                )
                            }
                        }
                    },
                    multi = { launchMaximizePickerOnPhone() },
                )
            }
            // Drawn icon (big window, arrow shrinking toward a small one): SH's
            // composition; the old drop-shadow glyph read as "copy".
            val btnRestoreFreeform = iconControlButton(
                com.portalpad.app.R.drawable.ic_window_unmaximize,
                "Unmaximize",
            ) {  // ❐ — shrink the maximized window back to a normal floating window
                restoreFreeform()  // self-guards: toasts when there's no maximized window
            }
            // "Window top bar" named the wrong bar: it reads as THIS bar, but it acts
            // on the focused window's caption. Neutral wording covers both directions,
            // because nothing here knows which way the caption is currently set -
            // toggleCaptionHandle() flips it blind and returns only success. A
            // directional label needs that call to report the resulting state plus a
            // WinState field to reconcile it; deliberately a separate change.
            val btnCaptionHandle = iconControlButton(
                com.portalpad.app.R.drawable.ic_window_titlebar,
                "Show or hide window title bar",
            ) {  // ▭ — collapse the window top bar to a slim handle (or expand back)
                requestWindowTopBarAction()
            }
            captionButton = btnCaptionHandle
            val btnArrange = controlButton("\u2637", "Arrange evenly") { // ☷ (even columns)
                requireWindows(2, "Need at least 2 windows to arrange") { arrangeEvenly() }
            }
            val btnArrangeOrder = controlButton("\u229E", "Arrange in order\u2026") { // ⊞ — order then tile (on phone)
                requireWindows(2, "Need at least 2 windows to arrange") { launchArrangeOnPhone() }
            }
            val btnStack = controlButton("\u29C9", "Stack windows") {     // ⧉ — gather all windows into a cascade
                requireWindows(2, "Need at least 2 windows to stack") { stackWindows() }
            }
            val btnRestore = controlButton("\u21BB", "Restore last session") {     // ↻ — relaunch the last saved session
                restoreLastSession()  // self-guards: toasts when there's no saved session
            }
            val btnClose = controlButton("\u2715", "Close all") {         // ✕
                requireWindows(1, "No windows to close") { showCloseAllConfirm() }
            }
            controlAvailability = listOf(
                btnMinimize to { st: WinState -> st.count >= 1 },
                // maximizable, not count: with every open window already maximized
                // the button stayed bright while doing nothing (SH field report).
                btnMaximize to { st: WinState -> st.maximizable >= 1 },
                btnRestoreFreeform to { st: WinState -> st.hasMaximized },
                // hasCaption, not count: a window being open does not mean it
                // HAS a caption to toggle (see WinState.hasCaption).
                btnCaptionHandle to { st: WinState -> st.hasCaption },
                btnArrange to { st: WinState -> st.count >= 2 },
                btnArrangeOrder to { st: WinState -> st.count >= 2 },
                btnStack to { st: WinState -> st.count >= 2 },
                btnRestore to { st: WinState -> st.hasSession },
                btnClose to { st: WinState -> st.count >= 1 },
            )
            // Mode switch leads, in its own group. It is the only control here that
            // is not a window operation, and the leading slot is the one position
            // that stays put in BOTH modes - the row is centered and the desktop set
            // is three times the size of the standard one, so no interior slot is
            // ordinally stable. It is also as far from ✕ as the row allows, which
            // matters because both close everything.
            // Deliberately ABSENT from controlAvailability above: every other button
            // dims with no windows open, and that is exactly when you are most
            // likely to want to change mode.
            listOf(
                listOf(modeSwitchButton()),
                listOf(btnMinimize, btnMaximize, btnRestoreFreeform, btnCaptionHandle),
                listOf(btnArrange, btnArrangeOrder, btnStack),
                listOf(btnRestore),
                listOf(btnClose),
            )
        } else {
            val btnGestureControls = iconControlButton(
                com.portalpad.app.R.drawable.ic_gesture_controls,
                "Open Gesture controls on phone",
            ) {
                launchGestureControlsOnPhone()
            }
            val btnBack = controlButton("\u2190", "Back") {           // ←
                injector.backGuarded()
            }
            val btnHome = controlButton("\u2302", "Home") {           // ⌂
                // Same behavior as the trackpad's Home-on-wallpaper-default
                // (field request): dismisses the widget overlay if it's up,
                // else lays the wallpaper backdrop. This bar only shows in
                // non-desktop mode, where the minimize-all pass is skipped, so
                // the wallpaper outcome is identical to the old direct call.
                com.portalpad.app.service.PortalPadForegroundService.goHomeWallpaper()
            }
            val btnClose = controlButton("\u2715", "Close all") {         // ✕
                showCloseAllConfirm()
            }
            controlAvailability = emptyList()
            captionButton = null
            // Same leading slot as the desktop set, so the mode switch is "the first
            // icon" in both. This is the direction that would otherwise be
            // impossible: without it, theater mode has no way back to desktop from
            // the display itself, since the trackpad's Arrange pill and its wheel
            // only exist in desktop mode.
            listOf(
                listOf(modeSwitchButton()),
                listOf(btnGestureControls),
                listOf(btnBack, btnHome),
                listOf(btnClose),
            )
        }
        controlButtons = controlGroups.flatten()
        controlGroups.forEachIndexed { gi, group ->
            if (gi > 0) bar.addView(controlSeparator())
            group.forEach { bar.addView(it) }
        }

        // Balancing spacer on the right, equal weight to the left spacer, so the
        // control-button group sits CENTERED in the bar (equal flexible space on
        // both sides) rather than pushed to the right edge.
        bar.addView(View(displayContext).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })

        // Floor the bar width to its content so controls never clip. Measure the
        // assembled bar: the weighted spacers collapse to 0 under UNSPECIFIED, so
        // this is the intrinsic width of the button group + padding. The user's
        // widthPct can still make the bar WIDER, but never narrower than the
        // controls need — otherwise the rightmost icon (e.g. ✕) gets clipped when
        // more buttons exist than fit the configured width.
        bar.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        barContentMinW = bar.measuredWidth
        val widthCap = displayW.coerceAtMost(1600)
        val barWidth = (displayW * (topBarCfg.widthPct.coerceIn(30, 100) / 100f)).toInt()
            .coerceAtLeast(barContentMinW)
            .coerceAtMost(widthCap)

        val lp = WindowManager.LayoutParams(
            // Width is a user-configurable percent of the display, capped so it
            // doesn't stretch the whole super-ultrawide canvas, and floored to the
            // control row's intrinsic width so icons never clip. Centered/aligned
            // via gravity below per the configured position.
            barWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayHost.windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or when (topBarCfg.position) {
                com.portalpad.app.data.TopBarPosition.LEFT -> Gravity.START
                com.portalpad.app.data.TopBarPosition.RIGHT -> Gravity.END
                else -> Gravity.CENTER_HORIZONTAL
            }
            x = 0
            y = 0   // flush to the very top edge
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }

        try {
            windowManager.addView(bar, lp)
            container = bar
            barParams = lp
            attachRetries = 0
            Log.d(TAG, "Window control bar attached on display ${display.displayId}")
            refreshCaptionLabel()
        } catch (t: Throwable) {
            // Retry marker (grep: "TopBar 2032 attach retry") — dock parity:
            // transient a11y-token errors settle within a couple of cycles.
            Log.e(TAG, "Could not attach window control bar (attempt ${attachRetries + 1}/6)", t)
            if (attachRetries < 5) {
                attachRetries++
                val r = Runnable { pendingAttachRetry = null; show() }
                pendingAttachRetry = r
                uiHandler.postDelayed(r, 400L)
                Log.w(TAG, "TopBar 2032 attach retry $attachRetries/6 scheduled in 400ms")
            } else {
                Log.e(TAG, "TopBar 2032 attach gave up after 6 attempts")
            }
            return
        }

        // Always-visible mode (autoHideAfterSec <= 0): pin the bar shown right after
        // attach, so it's visible without waiting for a first cursor event to fire
        // the reveal logic. (Timed-hide mode starts hidden and reveals on top-edge.)
        if (topBarCfg.autoHideAfterSec <= 0) setShown(true)

        val s = CoroutineScope(Dispatchers.Main)
        scope = s

        // Tier-2 availability greying: poll the live window state and dim controls
        // whose action doesn't currently apply. Desktop mode only.
        startAvailabilityPolling()

        // Observe top-bar config changes and restyle the live bar in place, so
        // edits in Workspace (gradient / opacity / corners / width / position)
        // preview live on the glasses without needing to recreate the bar.
        configObserverScope?.cancel()
        configObserverScope = CoroutineScope(Dispatchers.Main).also { obs ->
            obs.launch {
                com.portalpad.app.PortalPadApp.instance.prefs.topBarConfig.collect { cfg ->
                    // `sizePct` feeds `density`, which is baked into every view's
                    // dimensions at build — so a size change needs a real rebuild,
                    // not a live restyle. Coalesce rapid slider ticks into one
                    // rebuild ~150ms after the drag settles so dragging doesn't
                    // thrash the window. Everything else restyles in place.
                    val sizeChanged = cfg.sizePct != topBarCfg.sizePct
                    topBarCfg = cfg
                    // If the user switches to always-visible (autoHideAfterSec <= 0)
                    // while the bar is attached, pin it shown now — don't wait for a
                    // cursor event. (No-op if a size rebuild is about to run below.)
                    if (cfg.autoHideAfterSec <= 0 && !shown && container != null && !sizeChanged) {
                        setShown(true)
                    }
                    if (sizeChanged && container != null) {
                        pendingSizeRecreate?.let { uiHandler.removeCallbacks(it) }
                        val r = Runnable { dismiss(); show() }
                        pendingSizeRecreate = r
                        uiHandler.postDelayed(r, 150L)
                    } else {
                        restyleInPlace()
                        updateZoneHighlight()
                    }
                }
            }
            obs.launch {
                com.portalpad.app.PortalPadApp.instance.zonePreviewActive.collect { active ->
                    zonePreviewActive = active
                    updateZoneHighlight()
                    // While previewing on the customization page, PIN the actual bar
                    // shown too — the user is on the phone and can't drive the glasses
                    // cursor into the top zone to reveal it, so otherwise only the
                    // zone highlight appeared. On preview end, hide it again.
                    if (active) setShown(true) else setShown(false)
                }
            }
        }

        // Reveal bar at the top edge, then KEEP it shown while the cursor is
        // anywhere over the bar (so you can move down onto a button and click
        // it). Previously the bar hid as soon as the cursor left an 8px strip —
        // below the buttons — so clicks never landed. We hide only once the
        // cursor moves well below the bar's actual height. While a grab is in
        // progress we keep the bar shown.
        revealJob = s.launch {
            kotlinx.coroutines.flow.combine(
                injector.cursorPosition,
                injector.cursorOverNativeCaptionHandle,
                injector.nativeCaptionDragActive,
            ) { position, overNativeCaption, nativeCaptionDrag ->
                Triple(position, overNativeCaption, nativeCaptionDrag)
            }.collect { (position, overNativeCaption, nativeCaptionDrag) ->
                val (x, y) = position
                val nativeCaptionAtPoint =
                    overNativeCaption || injector.isNativeCaptionHandleAt(x, y)
                // DIAG-REVEAL: the reveal path had NO logging, so a "bar won't
                // activate" report couldn't be discriminated (stuck overlay
                // flag? suppress latch? dead collector? config?). One throttled
                // line near the top edge names every gate. No lines at all
                // while the cursor rides the top = this collector isn't running
                // (attach/scope problem — see the attach lines).
                val dnow = android.os.SystemClock.uptimeMillis()
                if (y <= 200f && dnow - lastRevealDiag > 1000) {
                    lastRevealDiag = dnow
                    Log.d(TAG, "DIAG-REVEAL y=${y.toInt()} shown=$shown mode=$mode " +
                        "overlayOpen=${com.portalpad.app.PortalPadApp.instance.widgetOverlayOpen.value} " +
                        "hideCfg=${topBarCfg.autoHideAfterSec} suppress=$suppressRevealUntilExit")
                }
                // Widget overlay is MODAL: while its full-screen layer is up the
                // bar is unreachable beneath the scrim, so suppress reveal (and
                // hide if already shown). Returns to normal on layer dismissal.
                if (com.portalpad.app.PortalPadApp.instance.widgetOverlayOpen.value) {
                    topInteractionOwner = TopInteractionOwner.NONE
                    if (shown && mode == Mode.IDLE) setShown(false)
                    return@collect
                }
                if (mode != Mode.IDLE) {
                    topInteractionOwner = TopInteractionOwner.PORTALPAD
                    if (!shown) setShown(true)
                    return@collect
                }
                // Reveal zone: a band of the configured width, positioned to match
                // the bar (left/center/right), and a configured top-edge height.
                val zoneH = topBarCfg.revealZoneHeightPx.coerceIn(2, 200).toFloat()
                val band = displayW * (topBarCfg.revealZoneWidthPct.coerceIn(10, 100) / 100f)
                val xLo = when (topBarCfg.position) {
                    com.portalpad.app.data.TopBarPosition.LEFT -> 0f
                    com.portalpad.app.data.TopBarPosition.RIGHT -> displayW - band
                    else -> (displayW - band) / 2f
                }
                val inBand = x >= xLo && x <= xLo + band
                val exitY = topInteractionExitY(zoneH)

                // A drag already in progress is conclusive Android intent and
                // overrides any earlier PortalPad hover ownership.
                if (nativeCaptionDrag) {
                    topInteractionOwner = TopInteractionOwner.NATIVE_CAPTION
                }

                // Ownership is released only below the whole bar/caption area,
                // never by a sideways move across overlapping controls.
                if (y > exitY && !nativeCaptionDrag) {
                    topInteractionOwner = TopInteractionOwner.NONE
                    layoutActionYieldUntilExit = false
                }

                // Arrange/stack actions dismiss the bar before moving windows.
                // Freshly moved captions cannot reveal it again until one exit.
                if (layoutActionYieldUntilExit && y <= exitY) {
                    suppressRevealUntilExit = true
                    if (shown) setShown(false)
                    return@collect
                }

                when (topInteractionOwner) {
                    TopInteractionOwner.NATIVE_CAPTION -> {
                        suppressRevealUntilExit = true
                        if (shown) setShown(false)
                        return@collect
                    }
                    TopInteractionOwner.PORTALPAD -> {
                        if (y <= exitY) {
                            if (!shown) setShown(true)
                            return@collect
                        }
                    }
                    TopInteractionOwner.NONE -> Unit
                }

                // With no owner, the surface entered first wins. Direct entry
                // onto caption chrome keeps PortalPad hidden. Entry onto the bar
                // elsewhere claims PortalPad, so crossing buttons and gaps does
                // not let Android steal the interaction halfway through.
                if (nativeCaptionAtPoint) {
                    topInteractionOwner = TopInteractionOwner.NATIVE_CAPTION
                    if (shown) setShown(false)
                    return@collect
                }
                if (shown && cursorOverBarAt(x, y)) {
                    topInteractionOwner = TopInteractionOwner.PORTALPAD
                    return@collect
                }
                // Clear the post-timed-hide suppression once the cursor leaves
                // the reveal zone in EITHER direction — horizontally out of the
                // band, or vertically below the top strip. Previously this only
                // cleared on a horizontal exit, so re-approaching the top edge
                // vertically (the natural gesture) left the latch stuck and the
                // bar refused to reveal until the cursor happened to drift
                // sideways out of the band.
                if (!inBand || y > zoneH) suppressRevealUntilExit = false
                // autoHideAfterSec == 0 is normally pinned, but only after the
                // native-caption handoff above has had first refusal.
                if (topBarCfg.autoHideAfterSec <= 0) { if (!shown) setShown(true); return@collect }
                // While previewing on the customization page, the bar is pinned
                // shown — don't let the cursor-below rule hide it.
                if (zonePreviewActive) { if (!shown) setShown(true); return@collect }
                if (y <= zoneH && inBand && !shown && !suppressRevealUntilExit) {
                    if (topEdgeDwellJob?.isActive != true) {
                        topEdgeDwellJob = s.launch {
                            kotlinx.coroutines.delay(1_000L)
                            topEdgeDwellJob = null
                            if (!shown && !suppressRevealUntilExit &&
                                !com.portalpad.app.PortalPadApp.instance.widgetOverlayOpen.value &&
                                topInteractionOwner != TopInteractionOwner.NATIVE_CAPTION
                            ) {
                                topInteractionOwner = TopInteractionOwner.PORTALPAD
                                setShown(true)
                            }
                        }
                    }
                } else {
                    topEdgeDwellJob?.cancel()
                    topEdgeDwellJob = null
                }
                // Timed hide (>0) is handled by scheduleAutoHide(); the
                // reveal-on-top-edge above brings a timed-hidden bar back.
            }
        }

        // While in MOVE/RESIZE, the proxy outline follows the cursor.
        cursorJob = s.launch {
            injector.cursorPosition.collect { (x, y) ->
                if (mode == Mode.IDLE) return@collect
                updateProxy(x, y)
            }
        }

        // Cursor clicks: in MOVE/RESIZE commit the grab; otherwise, when the
        // bar is shown, hit-test the control buttons by on-screen bounds and
        // fire the one under the cursor. This is the reliable click path — it
        // doesn't depend on the injected touch event reaching a TextView inside
        // the top-edge overlay (which it didn't on-device).
        clickJob = s.launch {
            injector.clickEvents.collect { (x, y) ->
                if (mode != Mode.IDLE) { commitGrab(); return@collect }
                // If the close-all confirmation is showing, its buttons take
                // priority over the bar buttons.
                if (confirmView != null && confirmButtons.isNotEmpty()) {
                    val cloc = IntArray(2)
                    var chit = false
                    for (b in confirmButtons) {
                        b.getLocationOnScreen(cloc)
                        val l = cloc[0]; val t = cloc[1]
                        if (x >= l && x <= l + b.width && y >= t && y <= t + b.height) {
                            flashButton(b); b.performClick(); chit = true; break
                        }
                    }
                    // Tap anywhere outside the card's buttons dismisses (treat the
                    // whole confirmation as modal). Always consume the click.
                    if (!chit) dismissConfirm()
                    return@collect
                }
                if (!shown) { Log.d(TAG, "CLICK ($x,$y) ignored — bar not shown"); return@collect }
                val loc = IntArray(2)
                var hit = false
                for (b in controlButtons) {
                    if (b.visibility != View.VISIBLE || !b.isEnabled) continue
                    b.getLocationOnScreen(loc)
                    val left = loc[0]; val top = loc[1]
                    val right = left + b.width; val bottom = top + b.height
                    Log.d(TAG, "CLICK ($x,$y) test '${b.contentDescription}' bounds=($left,$top,$right,$bottom)")
                    if (x >= left && x <= right && y >= top && y <= bottom) {
                        Log.d(TAG, "CLICK HIT '${b.contentDescription}'")
                        flashButton(b)
                        b.performClick()
                        hit = true
                        break
                    }
                }
                if (!hit) Log.d(TAG, "CLICK ($x,$y) no button hit")
            }
        }

        // Hover highlight: as the cursor moves, tint whichever control button it
        // is over so the user gets feedback that the button is live and
        // clickable (matching how a mouse pointer highlights buttons on a PC).
        hoverJob = s.launch {
            injector.cursorPosition.collect { (x, y) ->
                if (!shown) { setHovered(null); return@collect }
                val loc = IntArray(2)
                var over: View? = null
                for (b in controlButtons) {
                    if (b.visibility != View.VISIBLE || !b.isEnabled) continue
                    b.getLocationOnScreen(loc)
                    if (x >= loc[0] && x <= loc[0] + b.width &&
                        y >= loc[1] && y <= loc[1] + b.height
                    ) { over = b; break }
                }
                setHovered(over)
            }
        }
    }

    /** Show a centered confirmation card before closing all external-display
     *  apps (the ✕ is destructive). Buttons are cursor-clickable, hit-tested in
     *  the click flow like the bar's control buttons. */
    private fun showCloseAllConfirm() {
        container?.post {
            if (confirmView != null) return@post
            val pad = (16 * density).toInt()
            val card = LinearLayout(displayContext).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(pad, pad, pad, pad)
                background = GradientDrawable().apply {
                    cornerRadius = 18f * density
                    setColor("#F21A1226".toColorInt())
                    // Standard popup hairline (SH): every display popup gets
                    // the violet border the phone dialogs use (AbPrimaryDim).
                    setStroke((1 * density).toInt(), "#5B3CAA".toColorInt())
                }
            }
            val msg = TextView(displayContext).apply {
                text = if (isDesktopMode) "Close windows?" else "Close all apps?"
                setTextColor("#F2F2F5".toColorInt())
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 18f * density)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, (14 * density).toInt())
            }
            card.addView(msg)
            fun pill(label: String, textColor: Int, fill: Int, onTap: () -> Unit): TextView =
                confirmPill(label, textColor, fill, onTap)
            fun hgap() = TextView(displayContext).apply { width = (12 * density).toInt() }
            val btnCancel = pill("Cancel", "#F2F2F5".toColorInt(), "#332A2140".toColorInt()) {
                dismissConfirm()
            }
            // Destructive action — kept red, but sized to its text (not a full-width
            // bar) and set apart on its own centered row below the safe actions.
            val btnCloseAll = pill("Close all windows", "#FFFFFFFF".toColorInt(), "#CCFF6B6B".toColorInt()) {
                dismissConfirm()
                closeAllExternalApps()
            }
            if (isDesktopMode) {
                // Row 1: safe actions side by side.
                val btnManage = pill("Manage Windows", "#FFC8A6FF".toColorInt(), "#332A2140".toColorInt()) {
                    dismissConfirm()
                    launchManageOnPhone()
                }
                val row1 = LinearLayout(displayContext).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                }
                row1.addView(btnCancel); row1.addView(hgap()); row1.addView(btnManage)
                card.addView(row1)
                // Vertical gap, then the destructive action centered on its own row.
                card.addView(TextView(displayContext).apply { height = (12 * density).toInt() })
                val row2 = LinearLayout(displayContext).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                }
                row2.addView(btnCloseAll)
                card.addView(row2)
                confirmButtons = listOf(btnCancel, btnManage, btnCloseAll)
            } else {
                // Non-desktop: simple Cancel / Close all on one row.
                val row = LinearLayout(displayContext).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                }
                row.addView(btnCancel); row.addView(hgap()); row.addView(btnCloseAll)
                card.addView(row)
                confirmButtons = listOf(btnCancel, btnCloseAll)
            }

            attachConfirmCard(card, confirmButtons)
        }
    }

    /** One confirm-card pill. Extracted so the close-all card and the mode-switch
     *  card cannot drift apart in size, weight or corner radius. */
    private fun confirmPill(label: String, textColor: Int, fill: Int, onTap: () -> Unit): TextView =
        TextView(displayContext).apply {
            text = label
            contentDescription = label
            setTextColor(textColor)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 16f * density)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            val ph = (20 * density).toInt(); val pv = (10 * density).toInt()
            setPadding(ph, pv, ph, pv)
            isClickable = true
            background = GradientDrawable().apply {
                cornerRadius = 12f * density
                setColor(fill)
            }
            // Same double-fire protection as the bar buttons; see debouncedClick.
            setOnClickListener(debouncedClick(label, onTap))
        }

    /** Attach a built confirm card centered on the display and record it, so the
     *  cursor hit-test flow and [dismissConfirm] find it. Shared by both cards;
     *  each builds its own rows, since their layouts genuinely differ. */
    private fun attachConfirmCard(card: LinearLayout, buttons: List<TextView>) {
        confirmButtons = buttons
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayHost.windowType,
            // Not focusable; clicks come via the injected-cursor click flow.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.CENTER }
        runCatching { windowManager.addView(card, params) }
            .onSuccess { confirmView = card; confirmParams = params }
        com.portalpad.app.service.PortalPadForegroundService.raiseCursor()
    }

    private fun dismissConfirm() {
        container?.post {
            confirmView?.let { runCatching { windowManager.removeView(it) } }
            confirmView = null; confirmParams = null
            confirmButtons = emptyList()
        }
    }

    /** Close every real app on the external display (both modes). */
    private fun closeAllExternalApps() {
        val s = scope ?: return
        s.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val tasks = freeform.listExternalTasks()
                        .filter { rt ->
                            !rt.packageName.contains("launcher", ignoreCase = true) &&
                                !rt.packageName.contains("nexuslauncher", ignoreCase = true) &&
                                rt.packageName != com.portalpad.app.BuildConfig.APPLICATION_ID
                        }
                    Log.d(TAG, "CLOSE ALL: ${tasks.size} task(s): " +
                        tasks.joinToString { "${it.packageName}#${it.taskId}" })
                    for (t in tasks) runCatching { freeform.close(t.taskId) }
                }
            }
            maybeShowWallpaperIfEmpty()
        }
    }

    /** Open the per-window manager on the phone (reuses WindowLimitActivity) so
     *  the user can close windows individually. Mirrors the cap flow's launch:
     *  a brief toast on the external display + a real phone activity (its own
     *  task affinity, no CLEAR_TOP, so it can't disturb the wallpaper backdrop). */
    private fun launchManageOnPhone() {
        val app = com.portalpad.app.PortalPadApp.instance
        runCatching {
            com.portalpad.app.presentation.GlassesToast.show(
                app, display, "Manage windows on your phone", 3500L,
            )
        }
        runCatching {
            app.phoneHomeExitCoordinator.markInternalNavigation()
            val opts = android.app.ActivityOptions.makeBasic()
                .setLaunchDisplayId(android.view.Display.DEFAULT_DISPLAY)
            app.startActivity(
                android.content.Intent(app, com.portalpad.app.ui.settings.WindowLimitActivity::class.java)
                    .putExtra("mode", "manage")
                    .addFlags(
                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                            android.content.Intent.FLAG_ACTIVITY_NO_USER_ACTION,
                    ),
                opts.toBundle(),
            )
            Log.d(TAG, "top-bar: launched WindowLimitActivity (manage) on phone")
        }.onFailure { Log.e(TAG, "manage-on-phone launch failed", it) }
    }

    /**
     * Theater-only shortcut that brings the phone to Remote > Gesture without
     * changing the user's selected gesture preset. The live bridge handles an
     * already-composed phone interface, while its pending flag carries the same
     * request through a cold activity launch.
     */
    private fun launchGestureControlsOnPhone() {
        val app = com.portalpad.app.PortalPadApp.instance
        com.portalpad.app.TrackpadActivity.requestGestureControls()
        runCatching {
            val opts = android.app.ActivityOptions.makeBasic()
                .setLaunchDisplayId(android.view.Display.DEFAULT_DISPLAY)
            app.startActivity(
                android.content.Intent(app, com.portalpad.app.TrackpadActivity::class.java)
                    .putExtra(
                        com.portalpad.app.TrackpadActivity.EXTRA_MODE,
                        com.portalpad.app.TrackpadActivity.MODE_MEDIA,
                    )
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                opts.toBundle(),
            )
            com.portalpad.app.presentation.GlassesToast.show(
                app,
                display,
                "Gesture controls opened on phone",
                2500L,
            )
            Log.d(TAG, "top-bar: opened Remote > Gesture on phone")
        }.onFailure {
            Log.e(TAG, "gesture-controls-on-phone launch failed", it)
            runCatching {
                com.portalpad.app.presentation.GlassesToast.show(
                    app,
                    display,
                    "Could not open Gesture controls",
                    2500L,
                )
            }
        }
    }

    /** Open the window-ordering screen on the phone (ArrangeWindowsActivity).
     *  The user orders the open windows, then they tile into even columns in
     *  that order — reuses the same resize/evenColumn path as "Arrange evenly". */
    private fun launchArrangeOnPhone() {
        yieldForLayoutAction()
        val app = com.portalpad.app.PortalPadApp.instance
        runCatching {
            com.portalpad.app.presentation.GlassesToast.show(
                app, display, "Order your windows on your phone", 3500L,
            )
        }
        runCatching {
            app.phoneHomeExitCoordinator.markInternalNavigation()
            val opts = android.app.ActivityOptions.makeBasic()
                .setLaunchDisplayId(android.view.Display.DEFAULT_DISPLAY)
            app.startActivity(
                android.content.Intent(app, com.portalpad.app.ui.settings.ArrangeWindowsActivity::class.java)
                    .addFlags(
                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                            android.content.Intent.FLAG_ACTIVITY_NO_USER_ACTION,
                    ),
                opts.toBundle(),
            )
            Log.d(TAG, "top-bar: launched ArrangeWindowsActivity on phone")
        }.onFailure { Log.e(TAG, "arrange-on-phone launch failed", it) }
    }

    /** Open the maximize-window picker on the phone (MaximizeWindowActivity):
     *  a list of the open external-display windows; tapping one brings it to
     *  full view. Replaces the old "maximize the front window" button so the
     *  user can jump straight to any window, not just the frontmost. */
    private fun launchMaximizePickerOnPhone() {
        val app = com.portalpad.app.PortalPadApp.instance
        runCatching {
            com.portalpad.app.presentation.GlassesToast.show(
                app, display, "Choose a window on your phone", 3500L,
            )
        }
        runCatching {
            app.phoneHomeExitCoordinator.markInternalNavigation()
            val opts = android.app.ActivityOptions.makeBasic()
                .setLaunchDisplayId(android.view.Display.DEFAULT_DISPLAY)
            app.startActivity(
                android.content.Intent(app, com.portalpad.app.ui.settings.MaximizeWindowActivity::class.java)
                    .addFlags(
                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                            android.content.Intent.FLAG_ACTIVITY_NO_USER_ACTION,
                    ),
                opts.toBundle(),
            )
            Log.d(TAG, "top-bar: launched MaximizeWindowActivity on phone")
        }.onFailure { Log.e(TAG, "maximize-picker launch failed", it) }
    }

    /** Open the minimize-window picker on the phone (MinimizeWindowActivity):
     *  a list of the open external-display windows; tapping one minimizes it.
     *  Replaces the old "minimize the highlighted window" button so the user can
     *  minimize any window directly. */
    private fun launchMinimizePickerOnPhone() {
        val app = com.portalpad.app.PortalPadApp.instance
        runCatching {
            com.portalpad.app.presentation.GlassesToast.show(
                app, display, "Choose a window on your phone", 3500L,
            )
        }
        runCatching {
            app.phoneHomeExitCoordinator.markInternalNavigation()
            val opts = android.app.ActivityOptions.makeBasic()
                .setLaunchDisplayId(android.view.Display.DEFAULT_DISPLAY)
            app.startActivity(
                android.content.Intent(app, com.portalpad.app.ui.settings.MinimizeWindowActivity::class.java)
                    .addFlags(
                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                            android.content.Intent.FLAG_ACTIVITY_NO_USER_ACTION,
                    ),
                opts.toBundle(),
            )
            Log.d(TAG, "top-bar: launched MinimizeWindowActivity on phone")
        }.onFailure { Log.e(TAG, "minimize-picker launch failed", it) }
    }

    /** Restore the last saved session (relaunch + retile the windows that were
     *  open when the session was last captured). Delegates to the foreground
     *  service, which owns the saved-session restore path used by Settings and
     *  the reconnect dialog. */
    private fun restoreLastSession() {
        val app = com.portalpad.app.PortalPadApp.instance
        val svc = com.portalpad.app.service.PortalPadForegroundService.instance
        if (svc == null) {
            runCatching {
                com.portalpad.app.presentation.GlassesToast.show(
                    app, display, "Restore unavailable right now", 3500L,
                )
            }
            return
        }
        val s = scope ?: return
        s.launch {
            val hasSession = withContext(Dispatchers.IO) {
                runCatching { app.prefs.lastSession.first().windows.isNotEmpty() }.getOrDefault(false)
            }
            if (!hasSession) {
                runCatching {
                    com.portalpad.app.presentation.GlassesToast.show(
                        app, display, "No saved session to restore", 2500L,
                    )
                }
                return@launch
            }
            runCatching {
                com.portalpad.app.presentation.GlassesToast.show(
                    app, display, "Restoring your last session\u2026", 3500L,
                )
            }
            runCatching { svc.restoreSavedSession() }
                .onFailure { Log.e(TAG, "restore-last-session failed", it) }
        }
    }

    /** After closing an app from the top bar, check whether the external display
     *  still has any real app on it. If it's now empty (only launcher/PortalPad,
     *  or nothing), show the PortalPad home-backdrop so the user never lands on a
     *  blank display. Best-effort: closing is async, so we poll briefly. */
    private fun maybeShowWallpaperIfEmpty() {
        val s = scope ?: return
        s.launch {
            // Give the close a moment to actually remove the task.
            kotlinx.coroutines.delay(450)
            val empty = withContext(Dispatchers.IO) {
                runCatching {
                    val tasks = freeform.listExternalTasks()
                    // "Real app" = not a launcher and not PortalPad itself.
                    tasks.none { rt ->
                        !rt.packageName.contains("launcher", ignoreCase = true) &&
                            !rt.packageName.contains("nexuslauncher", ignoreCase = true) &&
                            rt.packageName != com.portalpad.app.BuildConfig.APPLICATION_ID
                    }
                }.getOrDefault(false)
            }
            if (empty) {
                com.portalpad.app.service.PortalPadForegroundService.showWallpaper()
            }
        }
    }

    /** Show/update/hide the translucent detection-zone highlight on the glasses.
     *  Shown only while [zonePreviewActive]; sized to the configured zone
     *  (top-edge height × band width, positioned per the bar's L/C/R). */
    private fun updateZoneHighlight() {
        if (!zonePreviewActive) {
            zoneHighlightView?.let { runCatching { windowManager.removeView(it) } }
            zoneHighlightView = null
            zoneHighlightParams = null
            return
        }
        val zoneH = topBarCfg.revealZoneHeightPx.coerceIn(2, 200)
        val bandW = (displayW * (topBarCfg.revealZoneWidthPct.coerceIn(10, 100) / 100f)).toInt()
        val gravityX = when (topBarCfg.position) {
            com.portalpad.app.data.TopBarPosition.LEFT -> Gravity.START
            com.portalpad.app.data.TopBarPosition.RIGHT -> Gravity.END
            else -> Gravity.CENTER_HORIZONTAL
        }
        runCatching {
            val existing = zoneHighlightView
            if (existing == null) {
                val v = View(displayContext).apply {
                    setBackgroundColor(topBarCfg.zoneHighlightColor.toInt())
                }
                val lp = WindowManager.LayoutParams(
                    bandW.coerceAtLeast(1),
                    zoneH,
                    overlayHost.windowType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or gravityX
                    x = 0; y = 0
                }
                windowManager.addView(v, lp)
                zoneHighlightView = v
                zoneHighlightParams = lp
            } else {
                existing.setBackgroundColor(topBarCfg.zoneHighlightColor.toInt())
                zoneHighlightParams?.let { p ->
                    p.width = bandW.coerceAtLeast(1)
                    p.height = zoneH
                    p.gravity = Gravity.TOP or gravityX
                    windowManager.updateViewLayout(existing, p)
                }
            }
        }.onFailure { Log.w(TAG, "updateZoneHighlight failed", it) }
    }

    /** Re-apply the user's style + size/position to the already-attached bar
     *  view, so Workspace edits show live. No teardown — just updates the
     *  background drawable and the window LayoutParams. */
    private fun restyleInPlace() {
        val bar = container ?: return
        runCatching {
            bar.background = android.graphics.drawable.GradientDrawable(
                topBarCfg.style.drawableOrientation(),
                topBarCfg.style.drawableColors(),
            ).apply {
                gradientType = topBarCfg.style.gradientType.toGradientDrawableType()
                if (gradientType != android.graphics.drawable.GradientDrawable.LINEAR_GRADIENT) {
                    setGradientCenter(topBarCfg.style.centerXFrac(), topBarCfg.style.centerYFrac())
                    gradientRadius = topBarCfg.style.radiusFrac() * displayW
                }
                topBarCfg.style.drawableOffsets()?.let { setColors(topBarCfg.style.drawableColors(), it) }
                cornerRadius = topBarCfg.style.cornerRadiusDp * density
                setStroke((1 * density).toInt(), "#26FFFFFF".toColorInt())
            }
            // Keep the control glyphs + title legible when the theme luminance
            // flips (e.g. user drags to a light gradient).
            val gc = barGlyphColor()
            for (b in controlButtons) (b as? TextView)?.setTextColor(gc)
            appTitleView?.setTextColor(gc)
            // The loop above re-colored every control to full glyph color; restore
            // the availability dimming on top so a theme change doesn't un-grey
            // controls that still don't apply.
            lastWinState?.let { applyAvailability(it) }
            barParams?.let { p ->
                p.width = (displayW * (topBarCfg.widthPct.coerceIn(30, 100) / 100f))
                    .toInt()
                    .coerceAtLeast(barContentMinW)
                    .coerceAtMost(displayW.coerceAtMost(1600))
                p.gravity = Gravity.TOP or when (topBarCfg.position) {
                    com.portalpad.app.data.TopBarPosition.LEFT -> Gravity.START
                    com.portalpad.app.data.TopBarPosition.RIGHT -> Gravity.END
                    else -> Gravity.CENTER_HORIZONTAL
                }
                runCatching { windowManager.updateViewLayout(bar, p) }
            }
        }.onFailure { Log.w(TAG, "restyleInPlace failed", it) }
    }

    private fun setShown(visible: Boolean) {
        shown = visible
        container?.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) {
            val c = container
            val loc = IntArray(2)
            c?.getLocationOnScreen(loc)
            Log.d(TAG, "BAR shown=true mode=${if (isDesktopMode) "desktop" else "standard"} onScreen=(${loc[0]},${loc[1]}) size=(${c?.width}x${c?.height}) buttons=${controlButtons.size}")
            // Duration auto-hide (mirrors the dock): if configured (>0), hide the
            // bar after N seconds. Re-showing restarts the timer. 0 = never
            // auto-hide on a timer (the cursor-below rule handles hiding instead).
            scheduleAutoHide()
        } else {
            autoHideJob?.cancel(); autoHideJob = null
            Log.d(TAG, "BAR shown=false")
        }
    }

    private fun scheduleAutoHide() {
        autoHideJob?.cancel(); autoHideJob = null
        // Pinned shown during customization preview — never schedule a hide.
        if (zonePreviewActive) return
        val secs = topBarCfg.autoHideAfterSec
        if (secs <= 0) return
        val s = scope ?: return
        autoHideJob = s.launch {
            while (true) {
                kotlinx.coroutines.delay(secs * 1000L)
                if (!shown) return@launch
                // HOVER HOLD: a cursor parked anywhere over the bar (or a grab
                // in progress) must not have the bar hide under it — re-arm a
                // full window and check again, so the bar hides only a full
                // duration after the cursor LEAVES.
                if (mode != Mode.IDLE || cursorOverBar()) continue
                suppressRevealUntilExit = true
                topInteractionOwner = TopInteractionOwner.NONE
                setShown(false)
                return@launch
            }
        }
    }

    /** Whether the injected cursor currently sits over the visible bar's
     *  on-screen rect (small grace margin so the very edge still counts). */
    private fun cursorOverBar(): Boolean = runCatching {
        val (x, y) = injector.cursorPosition.value
        cursorOverBarAt(x, y)
    }.getOrDefault(false)

    private fun cursorOverBarAt(x: Float, y: Float, pad: Float = 12f): Boolean = runCatching {
        val c = container ?: return@runCatching false
        if (c.visibility != View.VISIBLE || c.width == 0) return@runCatching false
        val loc = IntArray(2)
        c.getLocationOnScreen(loc)
        x >= loc[0] - pad && x <= loc[0] + c.width + pad &&
             y >= loc[1] - pad && y <= loc[1] + c.height + pad
    }.getOrDefault(false)

    /** Bottom boundary that must be crossed to choose a different top surface. */
    private fun topInteractionExitY(zoneH: Float): Float = runCatching {
        val c = container ?: return@runCatching zoneH
        val loc = IntArray(2)
        c.getLocationOnScreen(loc)
        maxOf(zoneH, loc[1] + c.height + 12f)
    }.getOrDefault(zoneH)

    /** Hide before a layout action moves fresh Android caption chrome under us. */
    private fun yieldForLayoutAction() {
        layoutActionYieldUntilExit = true
        topInteractionOwner = TopInteractionOwner.NONE
        suppressRevealUntilExit = true
        if (shown) setShown(false)
    }

    /**
     * True when [x], [y] lands on a visible top-bar control. InputInjector uses
     * this before resolving the app window underneath, so a PortalPad button
     * keeps the normal arrow cursor and can never become a caption drag zone.
     */
    fun isVisibleControlAt(x: Float, y: Float): Boolean = runCatching {
        if (!shown) return@runCatching false
        val loc = IntArray(2)
        controlButtons.any { button ->
            if (button.visibility != View.VISIBLE || !button.isEnabled) {
                false
            } else {
                button.getLocationOnScreen(loc)
                x >= loc[0] && x <= loc[0] + button.width &&
                    y >= loc[1] && y <= loc[1] + button.height
            }
        }
    }.getOrDefault(false)

    /** True for the whole visible bar, including gaps between controls. */
    fun isVisibleBarAt(x: Float, y: Float): Boolean =
        shown && cursorOverBarAt(x, y, pad = 0f)

    /** Update the proxy outline as the cursor moves. MOVE translates the whole
     *  rect so its top-left follows the cursor; RESIZE holds the top-left and
     *  moves the bottom-right corner to the cursor. */
    private fun updateProxy(cx: Float, cy: Float) {
        val base = workBounds ?: return
        val next = when (mode) {
            Mode.MOVE -> {
                val w = base.width; val h = base.height
                val left = cx.toInt().coerceIn(0, (displayW - w).coerceAtLeast(0))
                val top = cy.toInt().coerceIn(0, (displayH - h).coerceAtLeast(0))
                WindowBounds(left, top, left + w, top + h)
            }
            Mode.RESIZE -> {
                val left = base.left; val top = base.top
                val right = cx.toInt().coerceAtLeast(left + MIN_WINDOW_PX).coerceAtMost(displayW)
                val bottom = cy.toInt().coerceAtLeast(top + MIN_WINDOW_PX).coerceAtMost(displayH)
                WindowBounds(left, top, right, bottom)
            }
            Mode.IDLE -> return
        }
        proxyParams?.let { p ->
            p.x = next.left; p.y = next.top
            p.width = next.width; p.height = next.height
            proxy?.let { runCatching { windowManager.updateViewLayout(it, p) } }
        }
        lastPreview = next
    }

    /** Commit the grab on click: apply the previewed bounds to the real window. */
    private fun commitGrab() {
        val task = grabTask
        val bounds = lastPreview ?: workBounds
        mode = Mode.IDLE
        hideProxy()
        grabTask = null
        workBounds = null
        lastPreview = null
        if (task != null && bounds != null) {
            scope?.launch {
                withContext(Dispatchers.IO) {
                    runCatching {
                        freeform.setWindowingModeFreeform(task.taskId)
                        freeform.resize(task.taskId, bounds)
                    }
                }
            }
        }
    }

    private fun showProxy(b: WindowBounds) {
        hideProxy()
        val v = View(displayContext).apply {
            background = GradientDrawable().apply {
                setColor("#3327E0C0".toColorInt())   // translucent teal fill
                setStroke((2 * density).toInt(), "#CC27E0C0".toColorInt())
                cornerRadius = 10f * density
            }
        }
        val lp = WindowManager.LayoutParams(
            b.width, b.height,
            overlayHost.windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = b.left; y = b.top
        }
        try {
            windowManager.addView(v, lp)
            proxy = v
            proxyParams = lp
        } catch (t: Throwable) {
            Log.e(TAG, "Could not show window proxy", t)
        }
    }

    private fun hideProxy() {
        proxy?.let { runCatching { windowManager.removeView(it) } }
        proxy = null
        proxyParams = null
    }

    /**
     * Show a lightweight task-bounds preview for compatibility edge resizing.
     *
     * Some Android builds animate every programmatic `resizeTask` call as a
     * separate window transition. Repeating those calls during a held drag
     * produces ghosted surfaces and leaves the cursor ahead of the real edge.
     * This overlay can move at input cadence without reconfiguring the app; the
     * injector applies the real bounds once when the user releases.
     */
    fun showCompatibilityResizePreview(bounds: WindowBounds) {
        uiHandler.post {
            pendingCompatibilityResizeHide?.let { uiHandler.removeCallbacks(it) }
            pendingCompatibilityResizeHide = null
            if (compatibilityResizeProxy == null) {
                val view = View(displayContext).apply {
                    background = GradientDrawable().apply {
                        setColor("#2427E0C0".toColorInt())
                        setStroke((2 * density).toInt().coerceAtLeast(2), "#E627E0C0".toColorInt())
                        cornerRadius = 10f * density
                    }
                }
                val params = WindowManager.LayoutParams(
                    bounds.width,
                    bounds.height,
                    overlayHost.windowType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = bounds.left
                    y = bounds.top
                }
                try {
                    windowManager.addView(view, params)
                    compatibilityResizeProxy = view
                    compatibilityResizeParams = params
                } catch (t: Throwable) {
                    Log.e(TAG, "Could not show compatibility resize preview", t)
                }
            } else {
                updateCompatibilityResizePreviewNow(bounds)
            }
        }
    }

    /** Move the compatibility preview at cursor cadence; no task resize occurs. */
    fun updateCompatibilityResizePreview(bounds: WindowBounds) {
        val shouldPost = synchronized(compatibilityResizeLock) {
            pendingCompatibilityResizeBounds = bounds
            if (compatibilityResizeUpdatePosted) {
                false
            } else {
                compatibilityResizeUpdatePosted = true
                true
            }
        }
        if (!shouldPost) return
        // One layout update per display frame is enough for a smooth outline
        // and prevents 150-240 Hz trackpad input from flooding WindowManager.
        uiHandler.postDelayed({
            val latest = synchronized(compatibilityResizeLock) {
                compatibilityResizeUpdatePosted = false
                pendingCompatibilityResizeBounds.also {
                    pendingCompatibilityResizeBounds = null
                }
            }
            latest?.let { updateCompatibilityResizePreviewNow(it) }
        }, 16L)
    }

    /**
     * Keep the final outline briefly while WindowManager applies the one real
     * resize, then remove it. The delay bridges Pixel's short bounds transition
     * so the cursor never appears parked on empty space between preview and app.
     */
    fun finishCompatibilityResizePreview(delayMs: Long = 300L) {
        uiHandler.post {
            pendingCompatibilityResizeHide?.let { uiHandler.removeCallbacks(it) }
            val hide = Runnable {
                hideCompatibilityResizePreviewNow()
                pendingCompatibilityResizeHide = null
            }
            pendingCompatibilityResizeHide = hide
            uiHandler.postDelayed(hide, delayMs.coerceAtLeast(0L))
        }
    }

    /** Remove a compatibility preview immediately (teardown/cancel path). */
    fun cancelCompatibilityResizePreview() {
        uiHandler.post {
            pendingCompatibilityResizeHide?.let { uiHandler.removeCallbacks(it) }
            pendingCompatibilityResizeHide = null
            synchronized(compatibilityResizeLock) {
                pendingCompatibilityResizeBounds = null
                compatibilityResizeUpdatePosted = false
            }
            hideCompatibilityResizePreviewNow()
        }
    }

    private fun updateCompatibilityResizePreviewNow(bounds: WindowBounds) {
        val params = compatibilityResizeParams ?: return
        params.x = bounds.left
        params.y = bounds.top
        params.width = bounds.width
        params.height = bounds.height
        compatibilityResizeProxy?.let { view ->
            runCatching { windowManager.updateViewLayout(view, params) }
        }
    }

    private fun hideCompatibilityResizePreviewNow() {
        compatibilityResizeProxy?.let { runCatching { windowManager.removeView(it) } }
        compatibilityResizeProxy = null
        compatibilityResizeParams = null
    }

    private fun applyToFront(action: (taskId: Int) -> Unit) {
        applyToFrontTask { action(it.taskId) }
    }

    /** Like [applyToFront] but hands the resolved [RunningTask] to [action] (so
     *  callers can read its bounds, e.g. minimize parks it off-screen). */
    private fun applyToFrontTask(action: (task: RunningTask) -> Unit) {
        val s = scope ?: return
        s.launch {
            val front = withContext(Dispatchers.IO) {
                runCatching {
                    // The click arrives on this overlay's display id (the VD),
                    // but the app's task may be registered under a different
                    // display id (physical vs VD wrapper). Try this display
                    // first, then the injector's, then ANY non-launcher task —
                    // otherwise the control "hits" but finds nothing to act on.
                    val ids = listOf(display.displayId, injector.displayId).distinct()
                    var t: RunningTask? = null
                    for (id in ids) {
                        t = freeform.listTasks(id).firstOrNull()
                        if (t != null) break
                    }
                    t ?: freeform.listTasks(-1).firstOrNull { rt ->
                        !rt.packageName.contains("launcher", ignoreCase = true) &&
                            !rt.packageName.contains("nexuslauncher", ignoreCase = true)
                    }
                }.getOrNull()
            } ?: return@launch
            withContext(Dispatchers.IO) { action(front) }
        }
    }

    /**
     * "Arrange evenly" — lay every open freeform window out side by side in
     * equal-width columns spanning the display (2 -> halves, 3 -> thirds, ...
     * up to 6). On the super-ultrawide canvas this makes real multi-column
     * layouts; on a normal display it's the usual halves/thirds. Windows are
     * ordered left-to-right by their current horizontal position so the result
     * is predictable and minimally disruptive. Resizes are issued sequentially
     * with a small gap to avoid hitching the shell with a burst of commands.
     */
    private fun arrangeEvenly() {
        val s = scope ?: return
        yieldForLayoutAction()
        // Shared with the trackpad's arrange button. Pass our cached width as the
        // floor (the ultrawide stale-metrics guard) and our display as primary.
        WindowArranger.arrangeEvenly(s, display.displayId, floorW = displayW)
    }

    /** "Restore to freeform": demote the maximized window back to a floating
     *  freeform window. Prefers the window PortalPad tracked as maximized; if that
     *  can't be resolved (e.g. several windows open, or it was maximized outside
     *  our tracking), falls back to the window whose bounds fill ~the whole display
     *  — the one that visually IS maximized. No picker. Logs its decision under
     *  "PortalPadRestore" so a logcat shows what it acted on. */
    private fun restoreFreeform() {
        val s = scope ?: return
        s.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val ids = listOf(display.displayId, injector.displayId).distinct()
                    val tracked = freeform.currentlyMaximizedTaskId()
                    var id = display.displayId
                    var task: RunningTask? = null

                    // 1) Prefer a window whose PACKAGE is flagged maximized. This
                    //    survives relaunches (task ids change on every switch/relaunch,
                    //    so the old task-id tracking goes stale and restore grabbed the
                    //    wrong window — field bug).
                    for (cand in ids) {
                        val t = freeform.listTasks(cand).firstOrNull { rt ->
                            freeform.isMaximizedPackage(rt.packageName) &&
                                !rt.packageName.contains("launcher", ignoreCase = true) &&
                                !rt.packageName.contains("nexuslauncher", ignoreCase = true) &&
                                rt.packageName != com.portalpad.app.BuildConfig.APPLICATION_ID
                        }
                        if (t != null) { id = cand; task = t; break }
                    }

                    // 2. Same-session tracked task id (pre-relaunch).
                    if (task == null && tracked != null) {
                        for (cand in ids) {
                            val t = freeform.listTasks(cand).firstOrNull { it.taskId == tracked }
                            if (t != null) { id = cand; task = t; break }
                        }
                    }

                    // 2) Fallback: the largest non-launcher window that fills most
                    //    of the display (≈ visually maximized). Robust with several
                    //    windows open and regardless of how it got maximized.
                    if (task == null) {
                        for (cand in ids) {
                            val tasks = freeform.listTasks(cand).filter { rt ->
                                !rt.packageName.contains("launcher", ignoreCase = true) &&
                                    !rt.packageName.contains("nexuslauncher", ignoreCase = true) &&
                                    rt.packageName != com.portalpad.app.BuildConfig.APPLICATION_ID
                            }
                            val big = tasks.maxByOrNull { rt ->
                                val b = rt.bounds ?: return@maxByOrNull 0L
                                (b.right - b.left).toLong() * (b.bottom - b.top).toLong()
                            }
                            val b = big?.bounds
                            if (big != null && b != null && displayW > 0 && displayH > 0) {
                                val area = (b.right - b.left).toLong() * (b.bottom - b.top).toLong()
                                val full = displayW.toLong() * displayH.toLong()
                                if (area >= full * 70 / 100) { id = cand; task = big; break }
                            }
                        }
                    }

                    if (task == null) {
                        Log.d("PortalPadRestore", "no maximized/large window to restore (tracked=$tracked)")
                        runCatching {
                            com.portalpad.app.presentation.GlassesToast.show(
                                com.portalpad.app.PortalPadApp.instance, display, "No maximized window", 2500L,
                            )
                        }
                        return@runCatching
                    }
                    Log.d(
                        "PortalPadRestore",
                        "restoring task=${task.taskId} pkg=${task.packageName} bounds=${task.bounds} " +
                            "display=$id tracked=$tracked",
                    )
                    freeform.restoreToFreeform(
                        taskId = task.taskId,
                        component = task.topActivity,
                        packageName = task.packageName,
                        displayId = id,
                        displayW = displayW,
                        displayH = displayH,
                    )
                }.onFailure { Log.e(TAG, "restoreFreeform failed", it) }
            }
        }
    }

    /** "Stack windows": cascade every open window into one spot (slight offset
     *  so each peeks out). Resolves the right display id the same way the other
     *  window actions do (VD id can mismatch where tasks are registered). */
    private fun stackWindows() {
        val s = scope ?: return
        yieldForLayoutAction()
        s.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    // Prefer the display that actually has windows on it.
                    val ids = listOf(display.displayId, injector.displayId).distinct()
                    val id = ids.firstOrNull { freeform.listTasks(it).any { t ->
                        !t.packageName.contains("launcher", ignoreCase = true) &&
                            t.packageName != com.portalpad.app.BuildConfig.APPLICATION_ID
                    } } ?: display.displayId
                    freeform.cascadeWindows(id, displayW, displayH)
                }.onFailure { Log.e(TAG, "stackWindows failed", it) }
            }
        }
    }

    /** Tint the button under the cursor (hover feedback) and clear the rest.
     *  Runs on the UI thread; safe to call from the cursor-position collector. */
    private fun setHovered(button: View?) {
        if (button === hoveredButton) return
        val prev = hoveredButton
        hoveredButton = button
        container?.post {
            // No background highlight tint — instead scale the button up while
            // hovered (matching the dock's voice/search hover), keeping the
            // background transparent.
            (prev?.background as? GradientDrawable)?.setColor(Color.TRANSPARENT)
            (button?.background as? GradientDrawable)?.setColor(Color.TRANSPARENT)
            prev?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(120)?.start()
            button?.animate()?.scaleX(1.18f)?.scaleY(1.18f)?.setDuration(120)?.start()
            // Tooltip: show the button's description below it (gap above), or hide.
            val tv = button as? TextView
            if (tv != null) showTooltip(tv, tv.contentDescription?.toString() ?: "")
            else hideTooltip()
        }
    }

    /** Show a small pill tooltip centered below [anchor] with a gap, matching the
     *  dock's hover tooltip style (View-based since this bar isn't Compose). */
    private fun showTooltip(anchor: View, text: String) {
        if (text.isBlank()) { hideTooltip(); return }
        hideTooltip()
        val tip = TextView(displayContext).apply {
            this.text = text
            setTextColor("#F2F2F5".toColorInt())
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 13f * density)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            gravity = Gravity.CENTER
            val padH = (10 * density).toInt()
            val padV = (5 * density).toInt()
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable().apply {
                cornerRadius = 10f * density
                setColor("#E61A1226".toColorInt())   // dark, slightly translucent
                setStroke((1 * density).toInt(), "#5B3CAA".toColorInt())
            }
        }
        // Measure to size the window and center it under the anchor.
        tip.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val tipW = tip.measuredWidth
        val tipH = tip.measuredHeight
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val gap = (8 * density).toInt()   // gap above tooltip so it doesn't touch the icon
        val x = loc[0] + anchor.width / 2 - tipW / 2
        val y = loc[1] + anchor.height + gap
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayHost.windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x.coerceAtLeast(0)
            this.y = y
        }
        runCatching { windowManager.addView(tip, params) }
            .onSuccess { tooltipView = tip; tooltipParams = params }
    }

    private fun hideTooltip() {
        tooltipView?.let { runCatching { windowManager.removeView(it) } }
        tooltipView = null
        tooltipParams = null
    }

    /** Click "pulse" to confirm a click registered — quick scale-down then back
     *  to the resting scale (mirrors the dock voice/search click pulse). */
    private fun flashButton(button: View) {
        container?.post {
            val resting = if (button === hoveredButton) 1.18f else 1f
            button.animate().scaleX(0.86f).scaleY(0.86f).setDuration(80).withEndAction {
                button.animate().scaleX(resting).scaleY(resting).setDuration(120).start()
            }.start()
        }
    }

    private fun controlButton(
        glyph: String,
        description: String,
        onClick: () -> Unit,
    ): TextView = TextView(displayContext).apply {
        text = glyph
        contentDescription = description
        setTextColor(barGlyphColor())   // off-white on dark bars, dark slate on light
        // Use a PIXEL size computed from the PINNED density, not `textSize = 26f`
        // (which is sp and scales with the live display DPI — the cause of the
        // top-bar icons resizing when the external-display DPI slider moves).
        // 26 * pinned-density keeps the glyph a fixed size at any DPI.
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 26f * density)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        gravity = Gravity.CENTER
        val padH = (16 * density).toInt()
        val padV = (4 * density).toInt()        // trimmed further; glyph size unchanged
        setPadding(padH, padV, padH, padV)
        isClickable = true
        isFocusable = false
        background = GradientDrawable().apply {
            cornerRadius = 14f * density
            setColor(Color.TRANSPARENT)
        }
        setOnClickListener(debouncedClick(description, onClick))
    }

    /**
     * Wrap a button action so a double-fire counts once.
     *
     * Evidence (modeswitch.txt, 2026-07-22): on this hardware an injected tap BOTH
     * dispatches natively to the TextView AND is hit-tested by the cursor click
     * flow, so every bar and confirm button fired twice, 2-5ms apart. The
     * mode-switch survived only by accident - the confirm-card null guard deduped
     * the second card and DataStore collapsed the second identical write - but
     * "Manage Windows" and the on-phone pickers have no such guards. Debouncing at
     * the listener covers both fire paths (the hit-test calls performClick, which
     * lands here too) for every current and future button. 300ms is generous for a
     * 5ms echo while staying below any deliberate repeat tap.
     */
    private fun debouncedClick(label: String, action: () -> Unit): View.OnClickListener {
        var lastAt = 0L
        return View.OnClickListener {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastAt < CLICK_DEBOUNCE_MS) {
                Log.d(TAG, "click '$label' debounced (${now - lastAt}ms after previous)")
            } else {
                lastAt = now
                action()
            }
        }
    }

    /**
     * The display-mode switch, for whichever mode we are NOT in.
     *
     * Glyph shows the DESTINATION, not the current state, matching every other
     * button here (✕ closes, ← goes back) and matching the tooltip, which reads
     * "switch to X". A glyph showing the current mode would contradict its own
     * label.
     *
     * NO chip background, deliberately - there was one (low-alpha fill), and it
     * failed twice over. SH read it as a stray highlight, not a category marker;
     * and the hover system, whose contract is "all button backgrounds are
     * transparent GradientDrawables, hover = scale", actively wiped the fill on
     * the first cursor pass - so it only ever showed between bar creation and
     * the first hover, flickering off exactly when you looked at it. The leading
     * separator group and the drawn icon carry the category distinction alone.
     * Do not reintroduce a background fill on any bar button without changing
     * the hover contract first.
     */
    private fun modeSwitchButton(): TextView {
        val toDesktop = !isDesktopMode
        val label = if (toDesktop) "Switch to desktop mode" else "Switch to theater mode"
        // Drawn icons (TV / monitor), not font glyphs: after four rounds of glyph
        // shopping, no character said "TV" or "monitor" without a wrong
        // connotation (antenna = live broadcast, curved = the curved-virtual-
        // display feature other XR apps ship). The vectors ride in an ImageSpan
        // so the button stays a TextView and all plumbing (hit-test list,
        // tooltip, debounce, chip) is untouched. Bounds use the PINNED density,
        // same as controlButton's text size, so the icon does not resize when
        // the external-display DPI slider moves. Tint = barGlyphColor(), the
        // adaptation emoji would have broken.
        val iconRes = if (toDesktop) {
            com.portalpad.app.R.drawable.ic_mode_desktop
        } else {
            com.portalpad.app.R.drawable.ic_mode_theater
        }
        return iconControlButton(iconRes, label) { showModeSwitchConfirm(toDesktop) }
    }

    /**
     * A [controlButton] whose face is a drawn vector instead of a font glyph.
     * The drawable rides in an ImageSpan so the button stays a TextView and all
     * plumbing (flat hit-test list, tooltip, debounce, chip) is untouched. Bounds
     * use the PINNED density, matching controlButton's text sizing, so icons do
     * not resize when the external-display DPI slider moves. Tint =
     * [barGlyphColor], same adaptation the glyphs get from setTextColor.
     */
    private fun iconControlButton(
        iconRes: Int,
        description: String,
        onClick: () -> Unit,
    ): TextView = controlButton(" ", description, onClick).apply {
        val icon = displayContext.getDrawable(iconRes)?.mutate() ?: return@apply
        icon.setTint(barGlyphColor())
        val px = (26 * density).toInt()
        icon.setBounds(0, 0, px, px)
        text = android.text.SpannableString(" ").apply {
            setSpan(
                android.text.style.ImageSpan(icon, android.text.style.ImageSpan.ALIGN_CENTER),
                0, 1,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }

    /**
     * Confirm before switching, because this closes everything open on the display.
     *
     * Note the bar destroys ITSELF as a result: the foreground service tears down
     * and reattaches this overlay on the DESKTOP_MODE trigger with the other button
     * set. So the card is dismissed before the pref write, never after — otherwise
     * teardown races a view that is still attached.
     */
    private fun showModeSwitchConfirm(toDesktop: Boolean) {
        // MODE-SWITCH log prefix (also used by the service's DESKTOP_MODE handler)
        // so one grep shows the whole chain: tap → confirm → write → teardown.
        // Added after a one-time first-run-after-install failure where the pref
        // write landed but the display ended up showing PortalPad's phone UI;
        // unreproducible since, so the path logs every step for next time.
        Log.i(TAG, "MODE-SWITCH tap: confirm requested (toDesktop=$toDesktop)")
        container?.post {
            if (confirmView != null) return@post
            val pad = (16 * density).toInt()
            val card = LinearLayout(displayContext).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(pad, pad, pad, pad)
                background = GradientDrawable().apply {
                    cornerRadius = 18f * density
                    setColor("#F21A1226".toColorInt())
                    // Standard popup hairline (SH): every display popup gets
                    // the violet border the phone dialogs use (AbPrimaryDim).
                    setStroke((1 * density).toInt(), "#5B3CAA".toColorInt())
                }
            }
            card.addView(TextView(displayContext).apply {
                text = if (toDesktop) "Switch to desktop mode?" else "Switch to theater mode?"
                setTextColor("#F2F2F5".toColorInt())
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 18f * density)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, (6 * density).toInt())
            })
            card.addView(TextView(displayContext).apply {
                // WHAT the target mode is (SH) - one voice across all three
                // switch popups; the cost line follows below.
                text = if (toDesktop) {
                    "Desktop mode opens apps as movable, resizable windows on the external display."
                } else {
                    "Theater mode shows one app at a time, filling the external display."
                }
                setTextColor("#F2F2F5".toColorInt())
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 14f * density)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, (8 * density).toInt())
            })
            card.addView(TextView(displayContext).apply {
                text = "This closes anything open on the external display. " +
                    "Reopen apps from the dock afterward."
                setTextColor("#CCF2F2F5".toColorInt())
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 14f * density)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, (14 * density).toInt())
            })
            val btnCancel = confirmPill("Cancel", "#F2F2F5".toColorInt(), "#332A2140".toColorInt()) {
                Log.i(TAG, "MODE-SWITCH cancelled (toDesktop=$toDesktop)")
                dismissConfirm()
            }
            val btnSwitch = confirmPill(
                if (toDesktop) "Switch to desktop" else "Switch to theater",
                "#FFC8A6FF".toColorInt(),
                "#332A2140".toColorInt(),
            ) {
                dismissConfirm()
                // scope is nulled in dismiss(); if the bar is being torn down at
                // this exact moment the write would silently never happen. Say so
                // instead — a silent no-op here looks identical to the teardown
                // bug this logging exists to catch.
                val s = scope
                if (s == null) {
                    Log.w(TAG, "MODE-SWITCH confirmed but scope is null; pref NOT written")
                } else {
                    Log.i(TAG, "MODE-SWITCH confirmed (toDesktop=$toDesktop); writing pref")
                    s.launch {
                        runCatching {
                            com.portalpad.app.PortalPadApp.instance.prefs.setBool(
                                com.portalpad.app.data.PreferencesRepository.Keys.DESKTOP_MODE_ENABLED,
                                toDesktop,
                            )
                        }.onSuccess {
                            Log.i(TAG, "MODE-SWITCH pref write done (toDesktop=$toDesktop)")
                        }.onFailure {
                            Log.w(TAG, "MODE-SWITCH pref write FAILED: ${it.message}")
                        }
                    }
                }
            }
            val row = LinearLayout(displayContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            row.addView(btnCancel)
            row.addView(TextView(displayContext).apply { width = (12 * density).toInt() })
            row.addView(btnSwitch)
            card.addView(row)
            attachConfirmCard(card, listOf(btnCancel, btnSwitch))
        }
    }

    /** A hairline separator between control-button groups: a thin vertical rule in
     *  the bar's glyph color at low alpha. NOT a member of [controlButtons] — it's
     *  non-interactive and never hit-tested. */
    private fun controlSeparator(): View = View(displayContext).apply {
        val w = (1.5f * density).toInt().coerceAtLeast(1)
        val h = (18 * density).toInt()
        val m = (7 * density).toInt()
        layoutParams = LinearLayout.LayoutParams(w, h).also {
            it.leftMargin = m
            it.rightMargin = m
            it.gravity = Gravity.CENTER_VERTICAL
        }
        setBackgroundColor((barGlyphColor() and 0x00FFFFFF) or 0x33000000)
    }

    /** VISIBLE user windows on the external display (excludes launchers and
     *  PortalPad itself) — the basis for the top bar's availability guards and the
     *  single-vs-picker branch. Runs a shell enumeration, so call it OFF the main
     *  thread. */
    private fun isUserWindow(rt: RunningTask): Boolean =
        rt.visible &&
            !rt.packageName.contains("launcher", ignoreCase = true) &&
            !rt.packageName.contains("nexuslauncher", ignoreCase = true) &&
            rt.packageName != com.portalpad.app.BuildConfig.APPLICATION_ID

    private fun userWindows(): List<RunningTask> = runCatching {
        freeform.listExternalTasks().filter { isUserWindow(it) }
    }.getOrDefault(emptyList())

    private fun userWindowCount(): Int = userWindows().size

    /** Window controls that act on ONE window directly but defer to a phone picker
     *  when there are several: enumerate, then 0 → [emptyMsg] toast, exactly 1 →
     *  [single] on that window (no picker), 2+ → [multi] (the picker). Enumeration
     *  is async; [single] runs in the same coroutine so it can suspend for its
     *  own shell work. */
    private fun onSingleOrPick(
        emptyMsg: String,
        single: suspend (RunningTask) -> Unit,
        multi: () -> Unit,
    ) {
        val s = scope ?: return
        s.launch {
            val users = withContext(Dispatchers.IO) { userWindows() }
            when {
                users.isEmpty() -> runCatching {
                    com.portalpad.app.presentation.GlassesToast.show(
                        com.portalpad.app.PortalPadApp.instance, display, emptyMsg, 2500L,
                    )
                }
                users.size == 1 -> single(users[0])
                else -> multi()
            }
        }
    }

    /** Run [action] only when at least [min] user windows are open; otherwise
     *  surface [emptyMsg] on the glasses and do nothing, so a control never opens
     *  empty UI (e.g. the on-phone arrange picker with no windows to arrange).
     *  Enumeration is async, so [action] runs just after the tap, on the main thread. */
    private fun requireWindows(min: Int, emptyMsg: String, action: () -> Unit) {
        val s = scope ?: return
        s.launch {
            val n = withContext(Dispatchers.IO) { userWindowCount() }
            if (n >= min) {
                action()
            } else {
                runCatching {
                    com.portalpad.app.presentation.GlassesToast.show(
                        com.portalpad.app.PortalPadApp.instance, display, emptyMsg, 2500L,
                    )
                }
            }
        }
    }

    /**
     * Run a window action by id, from another surface (the trackpad radial menu),
     * using the SAME delegates as the top-bar buttons. Each delegate self-guards
     * (enumerates windows, toasts on the glasses when not applicable) and runs on
     * [scope], so callers don't pre-check. [closeAll] runs the close directly: the
     * radial does its own two-tap confirm, so the on-glasses confirm is skipped.
     * Unknown ids no-op. Mirrors the handlers at [controlGroups] verbatim.
     */
    fun runAction(action: String) {
        when (action) {
            "minimize" -> onSingleOrPick(
                emptyMsg = "No windows to minimize",
                single = { task ->
                    withContext(Dispatchers.IO) {
                        runCatching { freeform.minimizeByClose(task.taskId, task.packageName, task.bounds) }
                    }
                    maybeShowWallpaperIfEmpty()
                },
                multi = { launchMinimizePickerOnPhone() },
            )
            "maximize" -> onSingleOrPick(
                emptyMsg = "No windows to maximize",
                single = { task ->
                    val m = DisplayMetrics().also { @Suppress("DEPRECATION") display.getRealMetrics(it) }
                    withContext(Dispatchers.IO) {
                        runCatching {
                            freeform.maximizeByResize(
                                taskId = task.taskId,
                                component = task.topActivity,
                                packageName = task.packageName,
                                current = task.bounds,
                                displayId = display.displayId,
                                displayW = m.widthPixels.coerceAtLeast(1),
                                displayH = m.heightPixels.coerceAtLeast(1),
                            )
                        }
                    }
                },
                multi = { launchMaximizePickerOnPhone() },
            )
            "restoreFreeform" -> restoreFreeform()
            "arrangeEvenly" -> requireWindows(2, "Need at least 2 windows to arrange") { arrangeEvenly() }
            "arrangeInOrder" -> requireWindows(2, "Need at least 2 windows to arrange") { launchArrangeOnPhone() }
            "stack" -> requireWindows(2, "Need at least 2 windows to stack") { stackWindows() }
            "restoreSession" -> restoreLastSession()
            "closeAll" -> closeAllExternalApps()
            "windowTopBar" -> requestWindowTopBarAction()
        }
    }

    /** Explain why the caption action cannot run instead of silently ignoring it. */
    private fun requestWindowTopBarAction() {
        val state = lastWinState
        when {
            state == null || state.count == 0 -> showWindowTopBarMessage("Open an app window first.")
            !com.portalpad.app.service.PortalPadAccessibilityService.captionAvailable.value ->
                showWindowTopBarMessage("This action requires an Android window title bar or mini bar.")
            else -> toggleWindowTopBar()
        }
    }

    private fun showWindowTopBarMessage(message: String) {
        runCatching {
            com.portalpad.app.presentation.GlassesToast.show(
                com.portalpad.app.PortalPadApp.instance,
                display,
                message,
                3000L,
            )
        }
    }

    /** Collapse/expand the freeform window top bar (Android caption) to a slim handle,
     *  via the accessibility service. Shared by the top-bar ▭ button and the Window
     *  Wheel entry. The toggle is global — flips every freeform window. */
    private fun toggleWindowTopBar() {
        val svc = com.portalpad.app.service.PortalPadAccessibilityService.instance
        if (svc == null) {
            runCatching {
                com.portalpad.app.presentation.GlassesToast.show(
                    com.portalpad.app.PortalPadApp.instance, display, "Enable accessibility for this", 2500L,
                )
            }
            return
        }
        scope?.launch(Dispatchers.IO) {
            val ok = runCatching { svc.toggleCaptionHandle(injector.displayId) }.getOrDefault(false)
            if (!ok) runCatching {
                com.portalpad.app.presentation.GlassesToast.show(
                    com.portalpad.app.PortalPadApp.instance, display, "Couldn't toggle window top bar", 2500L,
                )
            }
            // The toggle path settle-verifies internally, so the state read here
            // reflects the flip - the tooltip goes directional right away.
            refreshCaptionLabel()
        }
    }

    /**
     * Directional caption tooltip: read the actual state and label the ACTION -
     * "Collapse…" while the caption is showing, "Expand…" while it is the slim
     * handle - falling back to the neutral wording whenever the caption nodes are
     * unreadable (they go dark for seconds around launches and decor rebuilds).
     * Refreshed on bar build and after each toggle. An external caption change
     * between those moments (an app's own fullscreen convert) can leave the label
     * stale until the next refresh - accepted: polling a11y nodes every 2s to
     * keep a tooltip honest would be the tail wagging the dog, and the fallback
     * wording is never WRONG, only less specific.
     */
    private fun refreshCaptionLabel() {
        val btn = captionButton ?: return
        val state = lastWinState
        val unavailableLabel = when {
            state == null || state.count == 0 -> "Open a window to check support"
            !state.hasCaption -> "Window title bar unavailable"
            else -> null
        }
        if (unavailableLabel != null) {
            uiHandler.post { btn.contentDescription = unavailableLabel }
            return
        }
        scope?.launch(Dispatchers.IO) {
            val collapsed = runCatching {
                com.portalpad.app.service.PortalPadAccessibilityService.instance?.captionCollapsedState()
            }.getOrNull()
            val label = when (collapsed) {
                true -> "Expand window title bar"
                false -> "Collapse window title bar"
                null -> "Show or hide window title bar"
            }
            uiHandler.post { btn.contentDescription = label }
        }
    }

    /** Start the availability poll (desktop mode only): every ~2s recompute the
     *  window state and, when it changes, update each control. Lives in [scope],
     *  so it's cancelled in dismiss(). */
    private fun startAvailabilityPolling() {
        availabilityPollJob?.cancel()
        if (!isDesktopMode) { availabilityPollJob = null; return }
        val s = scope ?: return
        availabilityPollJob = s.launch {
            var lastSig: String? = null
            // Caption nodes can settle after the task list has already stopped
            // changing, so they need a small independent poll. WindowMonitor's
            // StateFlow deliberately deduplicates identical task snapshots.
            val captionFlow = kotlinx.coroutines.flow.flow {
                while (true) {
                    emit(
                        runCatching {
                            com.portalpad.app.service.PortalPadAccessibilityService
                                .instance?.captionHandles()?.isNotEmpty() == true
                        }.getOrDefault(false),
                    )
                    kotlinx.coroutines.delay(2000L)
                }
            }
            // Derive availability from the shared WindowMonitor snapshot (one poll
            // for the whole app) combined with the saved-session flow, instead of
            // running our own ~2s shell enumeration. count/hasMaximized refresh on
            // each snapshot; hasSession now updates instantly on save, not on poll.
            kotlinx.coroutines.flow.combine(
                com.portalpad.app.PortalPadApp.instance.windowMonitor.snapshot,
                com.portalpad.app.PortalPadApp.instance.prefs.lastSession,
                captionFlow,
            ) { snap, session, hasCaption ->
                WinState(
                    snap.tasks.count { isUserWindow(it) },
                    snap.hasMaximized,
                    session.windows.isNotEmpty(),
                    snap.tasks.count {
                        isUserWindow(it) && it.taskId != snap.maximizedId &&
                            !freeform.isMaximizedPackage(it.packageName)
                    },
                    hasCaption = hasCaption,
                )
            }.collect { state ->
                val sig = "${state.count}|${state.hasMaximized}|${state.hasSession}" +
                    "|${state.maximizable}|${state.hasCaption}"
                if (sig != lastSig) {
                    lastSig = sig
                    lastWinState = state
                    applyAvailability(state)
                    // Window set changed - re-read the caption state too. Covers
                    // the first window opening (attach-time read found no caption
                    // nodes and left the neutral label; SH saw it stay neutral
                    // until the first manual toggle).
                    refreshCaptionLabel()
                }
            }
        }
    }

    /** Dim controls whose action doesn't currently apply (full glyph color when
     *  applicable, ~30% alpha otherwise). Purely visual — the tap-time guards still
     *  do the real refusing; this just surfaces availability at a glance. */
    private fun applyAvailability(state: WinState) {
        val on = barGlyphColor()
        val off = (on and 0x00FFFFFF) or 0x4D000000
        controlAvailability.forEach { (btn, applies) ->
            val color = if (applies(state)) on else off
            btn.setTextColor(color)
            // Icon-faced buttons: setTextColor does nothing to an ImageSpan's
            // drawable, whose tint was set once at creation - so the drawn icons
            // (unmaximize, title bar) stayed bright with zero windows open while
            // every glyph beside them dimmed (SH field report, 2026-07-22).
            // Re-tint the span drawables with the same color the glyphs get.
            (btn.text as? android.text.Spanned)
                ?.getSpans(0, btn.text.length, android.text.style.ImageSpan::class.java)
                ?.forEach { span -> span.drawable.setTint(color) }
            btn.invalidate()
        }
    }

    /** Glyph/title color for the bar, chosen from the bar gradient's luminance so
     *  the window controls + title stay legible whatever theme the user picks:
     *  off-white on a dark bar, dark slate on a light bar. */
    private fun barGlyphColor(): Int {
        val lum = (android.graphics.Color.luminance(topBarCfg.style.colorAInt()) +
            android.graphics.Color.luminance(topBarCfg.style.colorBInt())) / 2f
        return if (lum < 0.5f) "#F2F2F5".toColorInt() else "#15131A".toColorInt()
    }

    fun dismiss() {
        pendingAttachRetry?.let { uiHandler.removeCallbacks(it) }; pendingAttachRetry = null
        attachRetries = 0
        pendingSizeRecreate?.let { uiHandler.removeCallbacks(it) }; pendingSizeRecreate = null
        revealJob?.cancel(); revealJob = null
        autoHideJob?.cancel(); autoHideJob = null
        topInteractionOwner = TopInteractionOwner.NONE
        layoutActionYieldUntilExit = false
        configObserverScope?.cancel(); configObserverScope = null
        zoneHighlightView?.let { runCatching { windowManager.removeView(it) } }
        zoneHighlightView = null
        zoneHighlightParams = null
        cursorJob?.cancel(); cursorJob = null
        clickJob?.cancel(); clickJob = null
        hoverJob?.cancel(); hoverJob = null
        availabilityPollJob?.cancel(); availabilityPollJob = null
        lastWinState = null
        controlAvailability = emptyList()
        hoveredButton = null
        captionButton = null
        hideTooltip()
        confirmView?.let { runCatching { windowManager.removeView(it) } }
        confirmView = null; confirmParams = null; confirmButtons = emptyList()
        scope = null
        mode = Mode.IDLE
        shown = false
        hideProxy()
        pendingCompatibilityResizeHide?.let { uiHandler.removeCallbacks(it) }
        pendingCompatibilityResizeHide = null
        synchronized(compatibilityResizeLock) {
            pendingCompatibilityResizeBounds = null
            compatibilityResizeUpdatePosted = false
        }
        hideCompatibilityResizePreviewNow()
        container?.let { runCatching { windowManager.removeView(it) } }
        container = null
        barParams = null
        grabTask = null
        workBounds = null
        lastPreview = null
    }

    companion object {
        private const val TAG = "WindowControlBar"
        private const val MIN_WINDOW_PX = 320
        /** Double-fire suppression window; see [debouncedClick]. */
        private const val CLICK_DEBOUNCE_MS = 300L
    }
}
