package com.portalpad.app.presentation

import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.portalpad.app.service.PortalPadAccessibilityService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.portalpad.app.ui.theme.AbAccent
import com.portalpad.app.ui.theme.AbBackground
import com.portalpad.app.ui.theme.AbOnSurface
import com.portalpad.app.ui.theme.AbOnSurfaceDim
import com.portalpad.app.ui.theme.AbOnSurfaceMuted
import com.portalpad.app.ui.theme.AbPrimary
import com.portalpad.app.ui.theme.AbPrimaryBright
import com.portalpad.app.ui.theme.AbPrimaryDim
import com.portalpad.app.ui.theme.AbSurfaceElevated
import com.portalpad.app.ui.theme.AbSurfaceVariant
import com.portalpad.app.ui.theme.PortalPadTheme

/**
 * PROTOTYPE — validates the one assumption the external-display custom keyboard
 * rests on, before any keyboard gets built.
 *
 * THE QUESTION: can a TOUCHABLE overlay on the external display drive the focused
 * app's text field via accessibility, without the touch disturbing that field's
 * input focus?
 *
 * WHY IT SHOULD WORK: this bypasses the IME entirely. Every failure this project
 * has fought — dead InputConnections, input-not-started, focus flips from injected
 * keys, WM aborting a show post-layout — needs an IME in the loop. ACTION_SET_TEXT
 * has none. It is also the mechanism the typing page's custom keyboard already
 * uses, which is the only keyboard path here that has never dropped a keystroke.
 *
 * WHY IT MIGHT NOT: the overlay must be touchable to receive cursor taps, and a
 * touch could pull input focus off the Chrome field. Without a focused field,
 * setFieldText() has nothing to write to. QuickSettingsOverlay proves a touchable
 * NOT_FOCUSABLE overlay works on this display; it just never had to care whether
 * an editor kept focus underneath it.
 *
 * READ THE VERDICT FROM THE LOG, not the screen:
 *   DIAG-EXTKB tap key=a wrote=true  → the architecture holds; build the keyboard.
 *   DIAG-EXTKB tap key=a wrote=false → focus was lost; the design needs rethinking
 *                                      before any layout work.
 * "wrote" is setFieldText's own return value, so it reports the write actually
 * landing rather than us assuming it did.
 *
 * Deliberately minimal: one row of keys, a backspace, and a drag handle. No
 * symbol pages, no resize, no persistence. Those are only worth building once
 * this answers yes.
 */
/** Matches DockBar.BASELINE_DENSITY / QS_BASELINE_DENSITY so every external
 *  display surface scales identically and none of them follow the DPI slider. */
// 1.33 -> 1.53 (SH, 2026-07-24): the whole keyboard reads ~15% bigger on the
// display - keys, gaps, fonts, numpad, calculator, both symbol pages - via
// this one knob, since every dp in the window converts through it. Width with
// numpad at 1.53 is ~1620px on a 1920 display; height with the suggestion
// panel open still clears 1080 (the top-edge clamp guards the rest).
private const val EXTKB_BASELINE_DENSITY = 1.53f

// Layout metrics, shared by the composable AND the window size. They have to come
// from one source: WRAP_CONTENT measured the window ~2 keys narrower than the
// content and silently clipped the right-hand column of every row (o, p, l, del
// all vanished). An explicit size can't drift from what's drawn.
private const val KEY_W_DP = 64
/** 70, not 60: at 60 the letter column ended 40dp short of the numpad column and
 *  left a dead gap under the letters. 70 makes both columns exactly 342dp, and
 *  it closes most of the size difference against HoneyBoard's keys. */
private const val KEY_H_DP = 70
private const val KEY_WIDE_W_DP = 100
private const val KEY_GAP_DP = 8
private const val KB_PAD_DP = 14
/** Full-width grab strip along the top edge. Full width so the centred pill never
 *  has to be repositioned when the numpad toggles the panel's width by ~300dp. */
private const val KB_STRIP_H_DP = 14

/** The painted handle, sized to the system's freeform caption pill on this
 *  display (~57px at 1.33 density) so the gesture reads as the same kind of grab
 *  the user already makes on freeform windows. Violet rather than that handle's
 *  green: same shape and size, deliberately not the same colour, because this is
 *  not a freeform window and does not behave like one. */
private const val KB_PILL_W_DP = 44
private const val KB_PILL_H_DP = 4

/** How much of the panel must stay reachable when it is dragged or resized.
 *  One value, so a resize cannot park the keyboard somewhere a drag would have
 *  refused to. */
private const val KEEP_ON_SCREEN_PX = 80
// 22 -> 26 (SH): the function row was proportionally smaller than the keys -
// fine print over full-size keys. Safe to change: KB_COLUMN_H_DP derives from
// it, so the window math follows automatically.
private const val KB_FNROW_H_DP = 26
/**
 * Two stacked lines while the calculator is on: the running expression above,
 * the result below.
 *
 * It was one line shared with the fn row's height so the columns lined up. But
 * one line meant both strings competed for the same 208dp, and at their own caps
 * (22 chars @ 9sp + 12 @ 15sp) they needed 227dp — they could never both fit.
 * Worse, Row measures unweighted children in order, so the expression was
 * measured FIRST and the result got whatever was left: the longer the running
 * sum grew, the fewer digits of the answer survived. Stacking gives each line
 * the full width and removes that inversion.
 */
// 44 -> 48: the expression line grew to 13sp (see LCD_EXPR_SP); two lines need
// the extra room or the taller one clips. Window height derives via
// lcdHeightDp -> windowHeightDp, so the panel resizes with it automatically.
private const val LCD_H_OPEN_DP = 48

/**
 * One line when the calculator is off, because there is no expression to put on
 * the second one. Held equal to the fn row so the two columns line up exactly as
 * they did before any of this, which is also what returns the shut keyboard to
 * its original 392dp height instead of carrying 20dp of empty black.
 */
private const val LCD_H_SHUT_DP = KB_FNROW_H_DP

private fun lcdHeightDp(calc: Boolean) = if (calc) LCD_H_OPEN_DP else LCD_H_SHUT_DP

private const val CALC_ACTION_H_DP = 26

private const val KB_CONTENT_W_DP = KEY_W_DP * 10 + KEY_GAP_DP * 9
private const val KB_STAGGER_DP = (KEY_W_DP + KEY_GAP_DP) / 2
private const val NUMPAD_COLS = 4
private const val NUMPAD_W_DP = KEY_W_DP * NUMPAD_COLS + KEY_GAP_DP * (NUMPAD_COLS - 1)
private const val NUMPAD_SPLIT_DP = 18
private const val LCD_TOGGLE_W_DP = 52
private const val LCD_PAD_H_DP = 6

/** Text width inside the LCD. DERIVED, so it cannot drift from the geometry it
 *  describes — the old caps were the literals 22 and 12, which were already over
 *  budget for the box they were meant to fit. */
private const val LCD_TEXT_W_DP =
    NUMPAD_W_DP - LCD_TOGGLE_W_DP - KEY_GAP_DP - LCD_PAD_H_DP * 2

/** 11 -> 13 (SH, 2026-07-24): at 11 the operators were hard to tell apart on
 *  the glass - ÷ and + differ by two pixels' worth of stroke at that size.
 *  13 fits ~25 characters, still more than the old single-line display's 22;
 *  LCD_EXPR_CHARS derives, so truncation follows automatically. */
private const val LCD_EXPR_SP = 13
private const val LCD_RESULT_SP = 15
/** How far the result may shrink before falling back to scientific notation. */
private const val LCD_RESULT_MIN_SP = 10
/** Monospace advance as a fraction of font size. 0.62 rather than the nominal 0.6
 *  so a font whose metrics run slightly wide still fits instead of clipping. */
private const val MONO_ADVANCE = 0.62f

/** Characters that fit across [LCD_TEXT_W_DP] at a given size. */
private fun monoFit(sp: Int): Int = (LCD_TEXT_W_DP / (sp * MONO_ADVANCE)).toInt()

private val LCD_EXPR_CHARS = monoFit(LCD_EXPR_SP)
private val LCD_RESULT_CHARS = monoFit(LCD_RESULT_SP)
/** Every segment lit, the way an unlit LCD shows its whole grid. Eight 8s in a
 *  box that fits 22 read as "empty box"; a full row reads as "screen". */
private val LCD_GHOST_TEXT = "8".repeat(LCD_RESULT_CHARS)
/** Widest the result can ever be — at the smallest font step. */
private val LCD_RESULT_CHARS_MAX = monoFit(LCD_RESULT_MIN_SP)

/** Largest size at which [len] characters still fit, floored at the smallest
 *  step. BOM 2024.06 predates BasicText's built-in autoSize, but monospace makes
 *  the fit exactly predictable from the character count, so a step-down is all
 *  it takes. */
private fun lcdResultSp(len: Int): Int {
    for (sp in LCD_RESULT_SP downTo LCD_RESULT_MIN_SP) if (len <= monoFit(sp)) return sp
    return LCD_RESULT_MIN_SP
}

/** A Double carries ~15-17 significant digits, so past 15 the entry is not exact
 *  no matter how wide the display is. A precision limit is a real reason to stop
 *  accepting keys; the old 12 was a pixel guess that also counted "." and "-",
 *  so "0.0000000001" hit it with one significant digit entered. */
private const val CALC_SIG_DIGITS = 15

/** Both columns are this tall, which is why their bottom edges line up. */
private const val KB_COLUMN_H_DP = KB_FNROW_H_DP + KEY_GAP_DP + (KEY_H_DP + KEY_GAP_DP) * 4

private fun windowWidthDp(numpad: Boolean): Int =
    KB_CONTENT_W_DP + (if (numpad) NUMPAD_SPLIT_DP + NUMPAD_W_DP else 0) + KB_PAD_DP * 2

/**
 * Window height for a given configuration. ONE formula, consumed by both the
 * initial params and every resize.
 *
 * They used to be two separate expressions and they had already drifted: the
 * initial one omitted CALC_ACTION_H_DP entirely, and the resize path was only
 * ever called from the numpad toggle. So turning CALC on — which adds the
 * AC/%/⌫/Insert row — grew the content by 34dp inside a window that stayed 392,
 * and the bottom of the keyboard was clipped until you happened to toggle the
 * numpad off and on. Taking the taller of the two columns states the real
 * constraint instead of assuming the letters always win.
 */
private fun windowHeightDp(numpad: Boolean, calc: Boolean, suggHDp: Int = 0): Int {
    val numericColumn = lcdHeightDp(calc) + KEY_GAP_DP +
        (if (calc) CALC_ACTION_H_DP + KEY_GAP_DP else 0) +
        (KEY_H_DP + KEY_GAP_DP) * 4
    val tallest = if (numpad) maxOf(KB_COLUMN_H_DP, numericColumn) else KB_COLUMN_H_DP
    // The suggestion panel reserves space between the grab strip and the
    // keyboard body: resizeWindow grows the window upward holding the bottom
    // edge, so opening it reads as the panel unfolding above the keys - and at
    // the top of the display the clamp flips that into the keyboard shifting
    // down instead (SH's spec). suggHDp is the MEASURED panel height for the
    // snapshot being shown (0 = closed), not a fixed block - a fixed reserve
    // left a dead gap over short lists (SH).
    return KB_STRIP_H_DP + KEY_GAP_DP + tallest + KB_PAD_DP * 2 +
        (if (suggHDp > 0) suggHDp + KEY_GAP_DP else 0)
}

/** Cap on the on-display suggestion panel height; longer lists scroll inside. */
private const val SUGG_PANEL_MAX_H_DP = 232

/** Panel height for a snapshot: header + per-row estimate (title row, plus the
 *  dimmed address line on navigate rows), capped. Estimates err a hair TALL -
 *  a couple of dp of slack under the list is invisible, while an underestimate
 *  would clip rows (the internal scroll is the backstop either way). */
private fun suggPanelHeightDp(
    items: List<com.portalpad.app.service.PortalPadAccessibilityService.MirrorSuggestion>,
): Int {
    val header = 34
    val rows = items.sumOf { 40 + (if (it.url != null) 17 else 0) }
    return (header + rows).coerceAtMost(SUGG_PANEL_MAX_H_DP)
}

private val OPERATOR_KEYS = setOf("/", "×", "-", "+", "=", ".")
/** Amber for STANDALONE operators on the expression line - matches the pad's
 *  operator keys; + and ÷ were indistinguishable in plain green (SH). */
private val LCD_OP_TINT = androidx.compose.ui.graphics.Color(0xFFFFB547)
private val LCD_OFF_BG = androidx.compose.ui.graphics.Color(0xFF0B1410)
/** Both raised: at 0xFF21301F the bezel was invisible and at 0xFF16301C the
 *  ghost segments sat within a hair of the background luminance, so the panel
 *  read as a hole punched in the keyboard rather than as a display that is off.
 *  Still far below the lit 0xFF7BF7A6, which is what keeps the states distinct. */
private val LCD_OFF_EDGE = androidx.compose.ui.graphics.Color(0xFF2E4A33)
private val LCD_GHOST = androidx.compose.ui.graphics.Color(0xFF1F4429)
private val LCD_ON_BG = androidx.compose.ui.graphics.Color(0xFF0E1F14)
private val LCD_ON_EDGE = androidx.compose.ui.graphics.Color(0xFF3FBF6A)
private val LCD_ON_TEXT = androidx.compose.ui.graphics.Color(0xFF7BF7A6)
private val LCD_ON_DIM = androidx.compose.ui.graphics.Color(0xFF62D98A)
/** Three tiers, not two. The expression line and the live preview both used
 *  LCD_ON_DIM, so they rendered identically — and the whole point of dimming the
 *  preview was to make committing it with "=" visibly change something. Faintest
 *  is history, mid is a prediction, brightest is an answer. */
private val LCD_ON_FAINT = androidx.compose.ui.graphics.Color(0xFF4E9E6A)
private val LCD_ACTION_TEXT = androidx.compose.ui.graphics.Color(0xFFFFB43C)
private val CALC_OP_BG = androidx.compose.ui.graphics.Color(0xFF4C3320)
private val CALC_OP_FG = androidx.compose.ui.graphics.Color(0xFFFFB43C)
/**
 * Brand mark colours, declared together rather than as five literals scattered
 * through the draw calls, so a change to one cannot leave the others behind.
 *
 * All lifted about 20%. The text was 0xFF8C7FB0 while the function-row labels
 * beside it are AbOnSurfaceMuted (0xFFA89BB8), so the one element on the panel
 * that is NOT a control was also the dimmest thing on it, which reads as
 * unfinished rather than as deliberately quiet. Level with its neighbours and no
 * brighter is the right place: it should not outshine anything you can press.
 * The mark moves with it by the same step so the two stay balanced, and the
 * relative palette inside the mark is preserved (the outer ring simply takes the
 * value the inner one used to have).
 */
private val BRAND_TEXT = androidx.compose.ui.graphics.Color(0xFFA89BB8)
private val BRAND_MARK_OUTER = androidx.compose.ui.graphics.Color(0xFF7A68B0)
private val BRAND_MARK_RING = androidx.compose.ui.graphics.Color(0xFF9684C4)
private val BRAND_MARK_ACCENT = androidx.compose.ui.graphics.Color(0xFFDFAF5E)
private val CLOSE_HOVER = androidx.compose.ui.graphics.Color(0xFFE05A5A)

// ─── Key tables ──────────────────────────────────────────────────────────────
// Written as explicit lists, NOT as one string per row. Every entry is both the
// label and the typed character — one value, so a key physically cannot show one
// thing and type another. Lists also make the Kotlin escapes ($ \ ") visible and
// auditable instead of hidden inside a long literal.

private val ROW_1 = listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p")
private val ROW_2 = listOf("a", "s", "d", "f", "g", "h", "j", "k", "l")
private val ROW_3 = listOf("z", "x", "c", "v", "b", "n", "m")

/**
 * Symbols page 1/2 — everything you actually reach for typing a URL, an email,
 * a search query or a snippet of code, on ONE page.
 *
 * Digits used to occupy row 1. The attached numeric block now carries 0-9 plus
 * / x - + . = permanently, so ten slots were redundant here and `?` had no home
 * at all while `: ; ' " -` were stranded on page 2. This is that space spent
 * properly.
 */
// internal (was private): shared with the relay/floating keyboard's ?123 pager,
// so every PortalPad keyboard emits ONE symbol set. Edit here, all three follow.
internal val SYM_PAGE_1 = listOf(
    listOf("(", ")", "[", "]", "{", "}", "<", ">", "\"", "'"),
    listOf("!", "?", "@", "#", "\$", "%", "^", "&", "*", "~"),
    listOf("_", ";", ":", "|", "\\", "`", "€"),
)

/**
 * Symbols page 2/2 — the genuine rarities. Deliberately conservative on glyphs:
 * card suits and similar ornaments render as tofu boxes in some fonts, and a key
 * that draws an empty rectangle is worse than one that isn't there.
 */
internal val SYM_PAGE_2 = listOf(
    listOf("£", "¥", "₩", "¢", "°", "•", "±", "§", "¶", "÷"),
    listOf("©", "®", "™", "µ", "¡", "¿", "«", "»", "—", "…"),
    listOf("¤", "¬", "√", "π", "¼", "½", "¾"),
)

/** Row 1 of symbols page 1 when the numeric block is HIDDEN. With the pad on,
 *  0-9 are always visible and this space is spent on brackets and quotes
 *  instead; with it off, neither surface would have digits at all. */
private val DIGITS_ROW = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")

/** Attached numeric block: the desktop calculator arrangement people expect. */
private val NUMPAD = listOf(
    listOf("7", "8", "9", "/"),
    listOf("4", "5", "6", "\u00D7"),
    listOf("1", "2", "3", "-"),
    listOf("0", ".", "=", "+"),
)

/** Second tap within this window latches caps lock. */
private const val CAPS_DOUBLE_TAP_MS = 300L

object ExternalKeyboardProbe : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private const val TAG = "PortalPadExtKb"

    private var view: ComposeView? = null
    // Local (window-space) bounds of the seek buttons, published to the
    // injector as display rects so a trackpad hold over them can stream
    // continuous seeking (see InputInjector.kbSeekHoldStart).
    @Volatile private var rewindSeekLocal: android.graphics.Rect? = null
    @Volatile private var ffSeekLocal: android.graphics.Rect? = null

    internal fun noteSeekButton(ff: Boolean, coords: androidx.compose.ui.layout.LayoutCoordinates) {
        val b = coords.boundsInRoot()
        val r = android.graphics.Rect(
            b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt(),
        )
        if (ff) ffSeekLocal = r else rewindSeekLocal = r
        updateSeekRects()
    }

    private fun updateSeekRects() {
        val injector = com.portalpad.app.PortalPadApp.instance.injector
        val lp = view?.layoutParams as? WindowManager.LayoutParams
        if (lp == null) {
            injector.kbSeekRewindRect = null
            injector.kbSeekFfRect = null
            return
        }
        fun toDisplay(r: android.graphics.Rect?) = r?.let {
            android.graphics.Rect(lp.x + it.left, lp.y + it.top, lp.x + it.right, lp.y + it.bottom)
        }
        injector.kbSeekRewindRect = toDisplay(rewindSeekLocal)
        injector.kbSeekFfRect = toDisplay(ffSeekLocal)
    }
    private var windowManager: WindowManager? = null
    private var isShowing = false
    private var stateAttached = false

    /** Our own copy of the field's text. ACTION_SET_TEXT replaces the whole value
     *  rather than inserting, so the caller must track it — exactly as the typing
     *  page's custom keyboard does. */
    private var buffer = StringBuilder()

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private val savedStateController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    private val store = ViewModelStore()
    override val viewModelStore: ViewModelStore get() = store

    fun isVisible(): Boolean = isShowing

    /** True when display coords [x],[y] fall inside the keyboard window. Valid
     *  because the window is TOP|START gravity, so lp.x / lp.y are absolute. */
    fun containsPoint(x: Float, y: Float): Boolean {
        val v = view ?: return false
        val lp = v.layoutParams as? WindowManager.LayoutParams ?: return false
        return x >= lp.x && x <= lp.x + v.width && y >= lp.y && y <= lp.y + v.height
    }

    private val outsideTapHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Injected tap OUTSIDE the keyboard while it is showing, reported by the
     * injector's click path. Needed because the a11y dismiss branch only hears
     * TYPE_VIEW_CLICKED - and a tap on NON-CLICKABLE space (wallpaper, an inert
     * container) emits no accessibility event at all, so "tap off the keyboard
     * to put it away" silently never worked there (SH flash report 2026-07-23).
     *
     * Dismisses after a short grace window UNLESS a click event arrived for the
     * same tap - in that case the a11y branch is the authority: it keeps the
     * keyboard for editable fields, dismisses for other clickables, and leaves
     * browser behavior to its own gate. Known residual: an in-page web field
     * that emits no event reads as empty space and dismisses - web fields were
     * already documented manual-keyboard territory (see the browser gate).
     */
    fun onInjectedTapOutside() {
        if (!isShowing) return
        val tapAt = android.os.SystemClock.elapsedRealtime()
        outsideTapHandler.postDelayed({
            if (!isShowing) return@postDelayed
            val svc = com.portalpad.app.service.PortalPadAccessibilityService.instance
            if (svc != null && svc.lastClickEventElapsed >= tapAt) {
                Log.i(TAG, "DIAG-KBSTATE outside-tap watchdog stood down (a11y click event handled it)")
            } else {
                Log.i(TAG, "DIAG-KBSTATE empty-space tap (no a11y event) -> dismiss on-display keyboard")
                dismiss()
            }
        }, OUTSIDE_TAP_GRACE_MS)
    }

    private const val OUTSIDE_TAP_GRACE_MS = 450L

    fun toggle(context: Context, displayId: Int) {
        if (isShowing) dismiss() else show(context, displayId)
    }

    fun show(context: Context, displayId: Int) {
        if (isShowing) return
        val display = context.getSystemService(android.hardware.display.DisplayManager::class.java)
            ?.getDisplay(displayId) ?: run {
            Log.w(TAG, "DIAG-EXTKB show failed: no display $displayId")
            return
        }
        // Same host every other external-display overlay uses: prefers
        // TYPE_ACCESSIBILITY_OVERLAY (2032), which survives
        // HIDE_NON_SYSTEM_OVERLAY_WINDOWS and behaves correctly with Chrome's
        // dropdown — both of which matter for a keyboard.
        val overlayHost = OverlayHost.forDisplay(display, context)
        val displayContext = overlayHost.context
        val wm = overlayHost.windowManager

        // ONCE, ever. This object is a singleton, so its SavedStateRegistryController
        // is created once and lives for the process — and performAttach() is only
        // legal during an owner's initialisation. Calling it on every show() threw
        // "Restarter must be created only during owner's initialization stage" the
        // SECOND time the keyboard was opened, killing the process (field report:
        // dropped to the home screen with no bubble; logcat 15:38:39 FATAL).
        if (!stateAttached) {
            savedStateController.performAttach()
            savedStateController.performRestore(null)
            stateAttached = true
        }
        // ON_CREATE is likewise a one-way transition for a registry that outlives
        // any single show; re-entering it from DESTROYED is not a legal move.
        if (lifecycleRegistry.currentState == Lifecycle.State.INITIALIZED) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        }

        buffer = StringBuilder()
        caret = 0
        suggPanelHdp = 0

        val composeView = ComposeView(displayContext).apply {
            setViewTreeLifecycleOwner(this@ExternalKeyboardProbe)
            setViewTreeViewModelStoreOwner(this@ExternalKeyboardProbe)
            setViewTreeSavedStateRegistryOwner(this@ExternalKeyboardProbe)
            setContent {
                // Pinned density — the DPI slider must not resize this keyboard.
                // Same treatment (and same 1.33 baseline) as the dock and the
                // quick-settings panel: Compose converts every dp with whatever
                // density it is handed, so a live density lets the slider scale
                // the keys out from under the user.
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalDensity provides
                        androidx.compose.ui.unit.Density(EXTKB_BASELINE_DENSITY, 1f),
                ) {
                    PortalPadTheme(phoneTheme = false) { ProbeContent() }
                }
            }
        }

        fun px(dp: Int) = (dp * EXTKB_BASELINE_DENSITY).toInt()
        val params = WindowManager.LayoutParams(
            px(windowWidthDp(numpad = true)),
            px(windowHeightDp(numpad = true, calc = false)),
            overlayHost.windowType,
            // NOT_FOCUSABLE is the whole point: the overlay must never become the
            // input target, or the editor underneath loses focus and there is
            // nothing to write to. Touchable (no FLAG_NOT_TOUCHABLE) so injected
            // cursor taps land on the keys.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            // Bottom centre, clear of the dock's reveal zone. The old hardcoded
            // 320/620 put the last key row below the visible area on this display
            // and would have been wrong on every other resolution.
            val metrics = android.util.DisplayMetrics().also {
                @Suppress("DEPRECATION") display.getRealMetrics(it)
            }
            // revealZoneHeightPx is a Workspace setting, so read it rather than
            // assume — a user who widened the zone would otherwise get a keyboard
            // sitting on top of their dock trigger.
            val dockZone = runCatching {
                kotlinx.coroutines.runBlocking {
                    com.portalpad.app.PortalPadApp.instance.prefs.dockConfig.first()
                        .revealZoneHeightPx
                }
            }.getOrDefault(3).coerceIn(0, 200)
            val margin = (KEY_GAP_DP * EXTKB_BASELINE_DENSITY).toInt()
            x = ((metrics.widthPixels - width) / 2).coerceAtLeast(0)
            y = (metrics.heightPixels - height - dockZone - margin).coerceAtLeast(0)
        }

        runCatching { wm.addView(composeView, params) }
            .onFailure { Log.e(TAG, "DIAG-EXTKB addView failed", it); return }
        windowManager = wm
        view = composeView
        isShowing = true
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        com.portalpad.app.service.PortalPadForegroundService.raiseCursor()
        publishDragRect()
        Log.i(TAG, "DIAG-EXTKB shown on display $displayId")
    }

    fun dismiss() {
        if (!isShowing) return
        // PAUSE/STOP only. ON_DESTROY is terminal — a destroyed registry cannot be
        // started again, so the next show() would have had no valid state to enter.
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        view?.let { v -> runCatching { windowManager?.removeView(v) } }
        view = null
        isShowing = false
        runCatching {
            val injector = com.portalpad.app.PortalPadApp.instance.injector
            injector.extKeyboardDragRect = null
            injector.extKeyboardBounds = null
            injector.extKeyboardDragging = false
            injector.kbSeekRewindRect = null
            injector.kbSeekFfRect = null
        }
        Log.i(TAG, "DIAG-EXTKB dismissed")
    }

    // ─── Calculator ─────────────────────────────────────────────────────────
    // Operator precedence: × and ÷ bind before + and -, so 12 + 7 × 5 = 47.
    // This REVERSED an earlier deliberate left-to-right choice (SH decision
    // 2026-07-23): every phone calculator, including the one this device ships
    // with, uses precedence, and PortalPad disagreeing with them reads as a
    // wrong answer, not as desk-calc fidelity.
    //
    // The committed expression is a token list - calcVals joined by calcOps -
    // instead of a single running accumulator, because precedence means earlier
    // terms cannot be folded until their neighbours are known. Invariant at
    // rest: calcOps.size == calcVals.size (every committed value carries the
    // operator typed after it; the entry being typed is the next value).

    private val calcVals = mutableListOf<Double>()
    private val calcOps = mutableListOf<String>()
    private var calcEntry = StringBuilder()
    private var calcError = false
    private var calcDone = false

    /** Evaluate [vals] joined by [ops] (ops.size == vals.size - 1) with × ÷
     *  before + -. Null on divide-by-zero. Pure - shared by the live preview,
     *  the running-total display, "=", and Insert, so all four cannot disagree
     *  about what an expression means. */
    private fun calcEval(vals: List<Double>, ops: List<String>): Double? {
        if (vals.isEmpty()) return null
        // Pass 1: fold × and ÷ into their left term.
        val terms = mutableListOf(vals[0])
        val addOps = mutableListOf<String>()
        for (i in ops.indices) {
            val next = vals[i + 1]
            when (ops[i]) {
                "×" -> terms[terms.size - 1] = terms.last() * next
                "÷" -> {
                    if (next == 0.0) return null
                    terms[terms.size - 1] = terms.last() / next
                }
                else -> { addOps.add(ops[i]); terms.add(next) }
            }
        }
        // Pass 2: sum what's left.
        var acc = terms[0]
        for (i in addOps.indices) {
            acc = if (addOps[i] == "+") acc + terms[i + 1] else acc - terms[i + 1]
        }
        return acc
    }

    /** The committed tokens evaluated on their own (dangling trailing operator
     *  dropped) - what the old single accumulator used to hold. */
    private fun calcCommittedValue(): Double? =
        calcEval(calcVals, calcOps.take((calcVals.size - 1).coerceAtLeast(0)))

    /** What the LCD shows: the pending operation evaluated live, else the entry
     *  being typed, else the running total, else zero.
     *
     *  The live preview is the whole point of the second line. A one-line display
     *  has to show what you are entering, which is why every desk calculator makes
     *  you press = to see anything — but the expression line above already shows
     *  the entry, so the result line is free to answer before you ask. */
    private fun calcDisplay(): String {
        if (calcError) return "ERROR"
        calcPreview()?.let { return lcdNumber(it) }
        // A COMPLETE pending expression whose preview is null is a divide by
        // zero: the result line says ERROR NOW (SH - it showed the "0" entry
        // until "="). Transient at this stage: backspace the offending digit
        // and it recovers. The error only latches (AC required) on "=".
        if (calcVals.isNotEmpty() && calcOps.isNotEmpty() &&
            calcEntry.toString().toDoubleOrNull() != null
        ) {
            return "ERROR"
        }
        if (calcEntry.isNotEmpty()) return grouped(calcEntry.toString())
        if (calcVals.isEmpty()) return "0"
        // Null here means the COMMITTED tokens already divide by zero ("8 ÷ 0 ×"):
        // honest to say so now, though the error only latches on "=".
        val acc = calcCommittedValue() ?: return "ERROR"
        return lcdNumber(acc)
    }

    /** The value "=" would produce right now, or null when there is no complete
     *  pending operation to evaluate. Deliberately pure: [calcCommit] does
     *  the same arithmetic but mutates, and a preview that commits as a side
     *  effect of being drawn would be a recomposition away from corrupting the
     *  running total. */
    private fun calcPreview(): Double? {
        if (calcDone) return null
        if (calcVals.isEmpty() || calcOps.isEmpty()) return null
        val entry = calcEntry.toString().toDoubleOrNull() ?: return null
        val vals = calcVals + entry
        return calcEval(vals, calcOps.take(vals.size - 1))
    }

    /**
     * Render a value for the LCD without ever changing it.
     *
     * The old body was `if (raw.length > 12) raw.take(12)`, which keeps the FIRST
     * twelve characters — it drops a number's tail, and dropping a number's tail
     * changes its value. 999999999999 × 9 = 8999999999991 displayed as
     * 899999999999: a factor of ten out, with no ellipsis and no error to say so.
     * calcSend() meanwhile inserted the full correct value, so the field and the
     * display disagreed.
     *
     * Plain while it fits at the smallest font step, scientific after that.
     * Scientific is longer to read but it is TRUE, which truncation never was.
     */
    private fun lcdNumber(v: Double): String {
        val plain = grouped(fmt(v))
        if (plain.length <= LCD_RESULT_CHARS_MAX) return plain
        val exact = shortestSci(v)
        if (exact.length <= LCD_RESULT_CHARS_MAX) return exact
        // Still too wide: shed significant figures. A rounded value in exponent
        // form is an honest approximation and reads as one; a truncated one reads
        // as an exact answer that happens to be wrong.
        for (digits in 15 downTo 0) {
            val s = sciFormat(v, digits)
            if (s.length <= LCD_RESULT_CHARS_MAX) return s
        }
        return sciFormat(v, 0)
    }

    /**
     * 1,234,567. The separator earns its width on a calculator whose job is
     * handing a number to a form: the failure mode is misreading a digit count,
     * and grouping is the standard fix.
     *
     * DISPLAY ONLY. calcSend() formats straight from fmt(), so Insert still
     * writes plain digits into the field and nothing downstream has to strip
     * separators back out — which is also why this is safe to apply liberally.
     *
     * Left alone once the value is in exponent form: "1.0E20" has no integer part
     * worth grouping and a grouped mantissa would just read as wrong.
     */
    private fun grouped(s: String): String {
        if (s.any { it == 'e' || it == 'E' }) return s
        val negative = s.startsWith("-")
        val body = if (negative) s.substring(1) else s
        val dot = body.indexOf('.')
        val whole = if (dot < 0) body else body.substring(0, dot)
        val fraction = if (dot < 0) "" else body.substring(dot)
        if (whole.length <= 3 || !whole.all { it.isDigit() }) return s
        val out = StringBuilder()
        whole.forEachIndexed { i, ch ->
            if (i > 0 && (whole.length - i) % 3 == 0) out.append(',')
            out.append(ch)
        }
        return (if (negative) "-" else "") + out + fraction
    }

    /** Fewest digits that still parse back to exactly [v]. Asking for a Double's
     *  full 17 significant figures instead surfaces binary representation noise —
     *  1.2345e-16 comes back as 1.2344999999999999e-16. */
    private fun shortestSci(v: Double): String {
        for (digits in 1..16) {
            val s = sciFormat(v, digits)
            if (s.toDoubleOrNull() == v) return s
        }
        return sciFormat(v, 16)
    }

    /** "1.2345e-16" — Java's %e gives "1.234500000e-16": a padded exponent and
     *  trailing zeros, and on a display this narrow both cost real digits. */
    private fun sciFormat(v: Double, digits: Int): String {
        val raw = java.lang.String.format(java.util.Locale.US, "%.${digits}e", v)
        val at = raw.indexOf('e')
        if (at < 0) return raw
        val mantissa = raw.substring(0, at).trimEnd('0').trimEnd('.')
        val rawExp = raw.substring(at + 1)
        val negative = rawExp.startsWith("-")
        val magnitude = rawExp.trimStart('+', '-').trimStart('0').ifEmpty { "0" }
        return mantissa + "e" + (if (negative) "-" else "") + magnitude
    }

    /** The expression so far, INCLUDING the digits being typed right now.
     *
     *  It previously showed only the accumulator and pending operator, so the
     *  left half of the display sat frozen while you typed — the running sum has
     *  to grow as you enter it, the way any calculator does. */
    private fun calcExpr(): String {
        // Committed. The head would otherwise be the result plus an empty
        // operator, so the answer appeared on both lines at once — the same
        // double-echo the live-entry guard below fixes at the other end.
        // Clearing is also what makes "=" visibly do something now that the
        // result line no longer waits for it.
        if (calcDone) return ""
        // The FULL token list, not a folded total: with precedence the earlier
        // terms stay unfolded until = anyway, and "12 + 7 ×" is what tells the
        // user which term the entry is about to multiply.
        val head = buildString {
            calcVals.forEachIndexed { i, v ->
                if (i > 0) append(" ")
                append(grouped(fmt(v)))
                calcOps.getOrNull(i)?.let { append(" ").append(it) }
            }
        }
        // Only once there is something to append TO. With no committed tokens the
        // expression would just be the entry, which the result line is already
        // showing — the first number you typed appeared on both lines at once.
        val live = if (calcVals.isNotEmpty() && entryVisibleInExpr && calcEntry.isNotEmpty()) {
            " " + grouped(calcEntry.toString())
        } else {
            ""
        }
        val full = (head + live).trim()
        // Truncate from the LEFT: the most recent terms are the ones being
        // checked, so they are the ones to keep. Note this is the opposite of
        // what TextOverflow.Ellipsis would do, which is why the truncation is
        // done here rather than left to layout.
        return if (full.length > LCD_EXPR_CHARS) "…" + full.takeLast(LCD_EXPR_CHARS) else full
    }

    /** After = the entry is folded into the total, so showing it twice would read
     *  as "84 84". */
    private var entryVisibleInExpr = true

    /** Trim the trailing .0 that Double formatting insists on. */
    private fun fmt(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    /** Fold entry + tokens into one result (= and Insert share this). The token
     *  list collapses to [result] so the next operator chains from the answer,
     *  exactly like the old accumulator did after =. Null latches ERROR. */
    private fun calcCommit(): Double? {
        val vals = calcVals.toMutableList()
        calcEntry.toString().toDoubleOrNull()?.let { vals.add(it) }
        calcEntry.setLength(0)
        val ops = calcOps.take((vals.size - 1).coerceAtLeast(0))
        if (vals.isEmpty()) return null
        val r = calcEval(vals, ops)
        if (r == null) { calcError = true; return null }
        calcVals.clear(); calcOps.clear(); calcVals.add(r)
        return r
    }

    /** Route one key press into the calculator. Returns true if it was consumed. */
    private fun calcKey(k: String): Boolean {
        if (calcError && k != "AC") return true
        when (k) {
            "AC" -> {
                calcVals.clear(); calcOps.clear(); calcEntry.setLength(0); calcError = false
                calcDone = false; entryVisibleInExpr = true
            }
            "±" -> {
                if (calcEntry.isNotEmpty()) {
                    if (calcEntry.startsWith("-")) calcEntry.deleteCharAt(0)
                    else calcEntry.insert(0, "-")
                } else if (calcVals.isNotEmpty()) {
                    // No entry: negate the most recent committed term (after "="
                    // that IS the result - the old accumulator behavior).
                    calcVals[calcVals.size - 1] = -calcVals.last()
                }
            }
            // "/" is what the numpad row actually sends; only "÷" was accepted, so
            // the key fell through to the digit branch, returned false, and the
            // caller discards that — division was a silent dead key. Normalised to
            // "÷" here so calcEval and the expression line stay canonical,
            // and so the label can stay "/" (which is what it must type into a
            // field when the calculator is off).
            "+", "-", "×", "÷", "/" -> {
                if (calcDone) { calcDone = false; entryVisibleInExpr = true }
                val op = if (k == "/") "÷" else k
                val e = calcEntry.toString().toDoubleOrNull()
                when {
                    // Entry in progress: commit it as the next token. NOT folded -
                    // precedence decides at "=" what it combines with.
                    e != null -> { calcVals.add(e); calcOps.add(op); calcEntry.setLength(0) }
                    // No entry, dangling operator: "2 + ×" replaces + with ×.
                    calcOps.size >= calcVals.size && calcOps.isNotEmpty() ->
                        calcOps[calcOps.size - 1] = op
                    // No entry, no dangling op (right after "="): chain from result.
                    calcVals.isNotEmpty() -> calcOps.add(op)
                    // Operator with nothing before it: dead key, same as before.
                    else -> {}
                }
                entryVisibleInExpr = true
            }
            "=" -> {
                // Once. Repeat presses on a finished sum previously kept appending
                // " = " to the expression line.
                if (calcDone || (calcOps.isEmpty() && calcEntry.isEmpty())) return true
                calcCommit(); calcDone = true; entryVisibleInExpr = false
            }
            "%" -> {
                val e = calcEntry.toString().toDoubleOrNull()
                if (e != null) {
                    // Percent means two different things depending on the operator
                    // it follows, and treating them alike was wrong for half of
                    // them. After + or - it is a percentage OF the running total,
                    // so 200 + 15% is 230 and the entry becomes 30. After × or ÷
                    // it is a plain hundredth: 200 × 15% is 30 — multiplying by
                    // the base as well gave 200 × 30 = 6000. "Running total" is
                    // the committed tokens evaluated with precedence.
                    val pendingOp = calcOps.lastOrNull()
                    val proportional = pendingOp == "+" || pendingOp == "-"
                    val base = if (proportional) calcCommittedValue() else null
                    val v = if (base != null && proportional) base * e / 100.0 else e / 100.0
                    calcEntry = StringBuilder(fmt(v))
                }
            }
            "⌫" -> if (calcEntry.isNotEmpty()) calcEntry.setLength(calcEntry.length - 1)
            "." -> if (!calcEntry.contains(".")) {
                if (calcEntry.isEmpty()) calcEntry.append("0")
                calcEntry.append(".")
            }
            else -> if (k.length == 1 && k[0].isDigit()) {
                if (calcDone) {
                    calcVals.clear(); calcOps.clear(); calcDone = false; entryVisibleInExpr = true
                }
                // Count DIGITS, not characters: the old `length < 12` charged the
                // budget for "." and "-" too, so "0.0000000001" was locked out
                // after one significant digit.
                if (calcEntry.count { it.isDigit() } < CALC_SIG_DIGITS) {
                    calcEntry.append(k)
                } else {
                    // A key that does nothing and says nothing reads as a broken
                    // keyboard. Same buzz the rest of the app uses.
                    runCatching { com.portalpad.app.PortalPadApp.instance.injector.buzz() }
                    Log.i(TAG, "DIAG-EXTKB calc entry at $CALC_SIG_DIGITS-digit precision limit, key=$k rejected")
                }
            } else return false
        }
        Log.i(TAG, "DIAG-EXTKB calc key=$k display=${calcDisplay()}")
        return true
    }

    /** Put the result into the text field — the piece a calculator with no way to
     *  hand over its answer would be missing. */
    private fun calcSend(): Boolean {
        if (calcError) return false
        val result = calcCommit()
        calcDone = true
        entryVisibleInExpr = false
        val out = result?.let { fmt(it) } ?: return false
        var ok = true
        out.forEach { ch -> if (!typeChar(ch.toString())) ok = false }
        Log.i(TAG, "DIAG-EXTKB calc send=$out ok=$ok")
        return ok
    }

    /** Where our caret is within [buffer]. ACTION_SET_TEXT rewrites the whole
     *  field, so we have to know the insertion point ourselves. */
    private var caret = 0

    /** Pull the field's real text back into our copy. MUST run after anything
     *  that edits the field outside our own writes — paste and cut especially.
     *  Without it the next keypress rewrites the field from a stale buffer and
     *  silently destroys whatever was pasted. */
    private fun resync(): Boolean {
        val a11y = PortalPadAccessibilityService.instance ?: return false
        val text = a11y.readFieldText() ?: return false
        buffer = StringBuilder(text)
        caret = buffer.length
        Log.i(TAG, "DIAG-EXTKB resync len=${buffer.length}")
        return true
    }

    /** Move the caret. Pure selection change — no text is rewritten, so this can
     *  never disturb the field's contents. Phone keyboards have no arrow keys;
     *  with a cursor the only alternative is aiming at a character. */
    private fun moveCaret(delta: Int, toEdge: Boolean = false): Boolean {
        val a11y = PortalPadAccessibilityService.instance ?: return false
        caret = when {
            toEdge && delta < 0 -> 0
            toEdge -> buffer.length
            else -> (caret + delta).coerceIn(0, buffer.length)
        }
        val ok = runCatching { a11y.setFieldSelection(caret, caret) }.getOrDefault(false)
        Log.i(TAG, "DIAG-EXTKB caret=$caret ok=$ok")
        return ok
    }

    /** Select all / cut / copy / paste, performed by the system. Cut and paste
     *  change the field behind our back, so both resync afterwards. */
    private fun edit(kind: String): Boolean {
        val a11y = PortalPadAccessibilityService.instance ?: return false
        val ok = when (kind) {
            "selectAll" -> a11y.selectAllField()
            "cut" -> a11y.fieldEditAction(
                android.view.accessibility.AccessibilityNodeInfo.ACTION_CUT,
            )
            "copy" -> a11y.fieldEditAction(
                android.view.accessibility.AccessibilityNodeInfo.ACTION_COPY,
            )
            "paste" -> a11y.fieldEditAction(
                android.view.accessibility.AccessibilityNodeInfo.ACTION_PASTE,
            )
            else -> false
        }
        // Copy leaves the field untouched; the other two do not.
        if (ok && kind != "copy") resync()
        Log.i(TAG, "DIAG-EXTKB edit=$kind ok=$ok len=${buffer.length}")
        return ok
    }

    /** Enter. The field already holds the text (we wrote it), so this only needs
     *  to fire the action — a real ENTER keypress on the phone-side injector,
     *  which is what the typing page's own enter key does. */
    private fun submit(): Boolean {
        val injector = com.portalpad.app.PortalPadApp.instance.injector
        return runCatching {
            injector.submitSearch()
            injector.pressKey(android.view.KeyEvent.KEYCODE_ENTER)
            Log.i(TAG, "DIAG-EXTKB tap key=ENTER len=${buffer.length}")
            true
        }.getOrDefault(false)
    }

    /** Latch the MOVE cursor for the whole drag. Without this the glyph reverts
     *  the moment the pointer leaves the bar's rect — which it does immediately,
     *  because the bar moves with the window and the pointer is chasing it. The
     *  cursor should feel stuck to the bar until the button is released. */
    private fun setDragging(on: Boolean) {
        runCatching {
            com.portalpad.app.PortalPadApp.instance.injector.extKeyboardDragging = on
        }
    }

    /** Tell the cursor system where our drag strip is, so the pointer shows MOVE
     *  over it. Recomputed whenever the window moves or resizes. */
    private fun publishDragRect() {
        val v = view ?: return
        val lp = v.layoutParams as? WindowManager.LayoutParams ?: return
        fun px(dp: Int) = (dp * EXTKB_BASELINE_DENSITY).toInt()
        val injector = com.portalpad.app.PortalPadApp.instance.injector
        val left = lp.x + px(KB_PAD_DP)
        // The grab strip is ALWAYS the window's topmost element - the
        // suggestion panel opens BELOW it (SH) - so no offset dance here.
        val top = lp.y + px(KB_PAD_DP)
        // The whole strip is grabbable, minus the close button at its right end —
        // deliberately wider than the pill painted on it. The pill says where to
        // aim; this rect decides what actually catches, and being the more
        // forgiving of the two is the point. The function row below is excluded
        // because showing MOVE over Cut/Copy/Paste would be a lie about what a
        // click there does.
        injector.extKeyboardDragRect = android.graphics.Rect(
            left,
            top,
            left + lp.width - px(KB_PAD_DP * 2) - px(30),
            top + px(KB_STRIP_H_DP),
        )
        // Full footprint too: while the pointer is anywhere over the keyboard the
        // window underneath must not get to decide the cursor.
        injector.extKeyboardBounds = android.graphics.Rect(
            lp.x,
            lp.y,
            lp.x + lp.width,
            lp.y + lp.height,
        )
        // Window moved/resized - the seek buttons' display rects moved with it.
        updateSeekRects()
    }

    /** Keep at least [KEEP_ON_SCREEN_PX] of the panel reachable. Shared by the
     *  drag and by every resize so the two cannot disagree about what counts as
     *  on screen. */
    private fun clampToDisplay(v: android.view.View, lp: WindowManager.LayoutParams, w: Int) {
        val bounds = v.context.getSystemService(WindowManager::class.java)
            ?.maximumWindowMetrics?.bounds ?: return
        if (w <= 0) return
        lp.x = lp.x.coerceIn(KEEP_ON_SCREEN_PX - w, bounds.width() - KEEP_ON_SCREEN_PX)
        lp.y = lp.y.coerceIn(0, bounds.height() - KEEP_ON_SCREEN_PX)
    }

    /** Resize the window when the numeric block or the calculator row is toggled.
     *  Without this the panel keeps its old footprint and either leaves an
     *  invisible but touchable dead zone beside the keys, or, for CALC, clips
     *  its own bottom row. */
    /** Mirror of ProbeContent's suggestion-panel height (0 = closed) for the
     *  NON-composable geometry paths (window sizing). Reset on every show(). */
    @Volatile private var suggPanelHdp = 0

    private fun resizeWindow(numpad: Boolean, calc: Boolean, suggHDp: Int = suggPanelHdp) {
        val v = view ?: return
        val wm = windowManager ?: return
        val lp = v.layoutParams as? WindowManager.LayoutParams ?: return
        val newHeight = (windowHeightDp(numpad, calc, suggHDp) * EXTKB_BASELINE_DENSITY).toInt()
        // Grow UPWARD from the bottom edge, not downward from the top. The
        // keyboard is normally parked near the bottom of the display, so opening
        // the calculator used to push its own bottom rows off screen and force a
        // manual drag before you could use them. Holding the bottom still also
        // matches what the gesture looks like: the panel unfolds rather than
        // sliding. Near the top the clamp takes over and it grows down instead.
        lp.y -= newHeight - lp.height
        lp.width = (windowWidthDp(numpad) * EXTKB_BASELINE_DENSITY).toInt()
        lp.height = newHeight
        // Width is deliberately NOT anchored the same way. The numeric block
        // appears to the RIGHT of the letters, so pinning the right edge would
        // slide every letter key sideways on each toggle — the keys are what you
        // are aiming at, and moving them is worse than growing rightward. Clamp
        // only, so it cannot leave the display.
        clampToDisplay(v, lp, lp.width)
        runCatching { wm.updateViewLayout(v, lp) }
        publishDragRect()
    }

    // Offset from the pointer to the window's origin, captured on press. Both
    // terms are ABSOLUTE display coordinates, so nothing here is measured inside
    // the window this drag is moving.
    private var dragGrabDx = 0f
    private var dragGrabDy = 0f

    private fun beginDrag() {
        val lp = view?.layoutParams as? WindowManager.LayoutParams ?: return
        val (cx, cy) = com.portalpad.app.PortalPadApp.instance.injector.cursorPointAbsolute
        dragGrabDx = cx - lp.x
        dragGrabDy = cy - lp.y
    }

    private fun endDrag() {
        publishDragRect()
    }

    /**
     * Move the window so the grabbed point stays under the pointer.
     *
     * Driven by the injector's ABSOLUTE cursor, never by window-local pointer
     * coordinates. That distinction is the whole fix, and the previous version
     * got it wrong in a way that looked right on paper: `lp.x += (local - grab)`
     * IS self-correcting, but only while `lp` still equals the window's real
     * on-screen position. It doesn't. `updateViewLayout` is async and on the
     * mirrored virtual display it runs frames behind, so local coordinates are
     * measured against a frame we've already moved past — and every reposition
     * re-delivers the still-pressed pointer with fresh local coordinates. The
     * window's own motion became the input.
     *
     * EVIDENCE (extkb4.txt): between 17:28:19.015 and 19.793 this window was
     * repositioned 35 times, once per frame, while InputDispatcher delivered
     * exactly TWO events for the whole gesture — HOVER_EXIT and DOWN, no MOVE at
     * all. y ran 546→526→551→478→620→246→796 with the half-cycle amplitude
     * roughly doubling each time. A still finger, and the keyboard drove itself
     * off the bottom of the display. That is a control loop, not a drag: at one
     * frame of lag it merely oscillates, at two or more it diverges.
     *
     * The cursor cannot be moved by moving the window, so this loop has no gain.
     * A still pointer now yields byte-identical x/y, which the no-op guard below
     * turns into no `updateViewLayout` at all — the re-dispatch that fed the
     * runaway stops being generated.
     *
     * (Same coordinate space as `lp`, verified: InputInjector.dragStart already
     * hit-tests raw cursorX/cursorY against `extKeyboardBounds`, which is built
     * straight from lp.x/lp.y.)
     */
    private fun dragTo() {
        val v = view ?: return
        val wm = windowManager ?: return
        val lp = v.layoutParams as? WindowManager.LayoutParams ?: return
        val (cx, cy) = com.portalpad.app.PortalPadApp.instance.injector.cursorPointAbsolute
        var x = (cx - dragGrabDx).toInt()
        var y = (cy - dragGrabDy).toInt()
        val bounds = v.context.getSystemService(WindowManager::class.java)
            ?.maximumWindowMetrics?.bounds
        if (bounds != null && v.width > 0 && v.height > 0) {
            x = x.coerceIn(KEEP_ON_SCREEN_PX - v.width, bounds.width() - KEEP_ON_SCREEN_PX)
            y = y.coerceIn(0, bounds.height() - KEEP_ON_SCREEN_PX)
        }
        // Nothing moved — say nothing. Beyond saving a relayout, this is what
        // starves the feedback path: no updateViewLayout, no window frame change,
        // no re-delivered pointer, no further dragTo. It also makes the clamp
        // safe, where the old code kept issuing writes against a wall.
        if (x == lp.x && y == lp.y) return
        lp.x = x
        lp.y = y
        runCatching { wm.updateViewLayout(v, lp) }
        publishDragRect()
    }

    /** Append one character by rewriting the whole field, then park the caret at
     *  the end. Returns what the accessibility write actually reported. */
    private fun typeChar(ch: String): Boolean {
        val a11y = PortalPadAccessibilityService.instance ?: run {
            Log.w(TAG, "DIAG-EXTKB tap key=$ch wrote=false reason=no-a11y-service")
            return false
        }
        val at = caret.coerceIn(0, buffer.length)
        buffer.insert(at, ch)
        val wrote = runCatching { a11y.setFieldText(buffer.toString()) }.getOrDefault(false)
        if (wrote) {
            caret = at + ch.length
            runCatching { a11y.setFieldSelection(caret, caret) }
        } else {
            // Keep our copy honest: a failed write means the field does not hold
            // what we think it does.
            buffer.delete(at, at + ch.length)
        }
        // Label and typed value come from the SAME variable at every key site, so
        // a mismatch should be impossible — this logs both anyway so a report of
        // "the wrong character appeared" can be settled from a capture instead of
        // from reading source. \ and " are the ones needing Kotlin escapes.
        Log.i(
            TAG,
            "DIAG-EXTKB tap label=[$ch] typed=[$ch] cp=${ch.map { it.code }} " +
                "wrote=$wrote len=${buffer.length}",
        )
        return wrote
    }

    private fun backspace(): Boolean {
        if (buffer.isEmpty()) {
            Log.i(TAG, "DIAG-EXTKB tap key=BKSP wrote=skip len=0")
            return false
        }
        val a11y = PortalPadAccessibilityService.instance ?: return false
        val at = caret.coerceIn(1, buffer.length)
        buffer.deleteCharAt(at - 1)
        val wrote = runCatching { a11y.setFieldText(buffer.toString()) }.getOrDefault(false)
        if (wrote) {
            caret = at - 1
            runCatching { a11y.setFieldSelection(caret, caret) }
        }
        Log.i(TAG, "DIAG-EXTKB tap key=BKSP wrote=$wrote len=${buffer.length}")
        return wrote
    }

    /** Shift has three states, not two. Colour carries the distinction as much as
     *  the glyph: a one-shot ⇧ and a latched ⇪ are easy to confuse mid-typing,
     *  and on an additive display contrast varies with whatever is behind us. */
    private enum class ShiftState { OFF, SHIFT, CAPS }

    @Composable
    private fun ProbeContent() {
        var lastWrote by remember { mutableStateOf<Boolean?>(null) }
        var shift by remember { mutableStateOf(ShiftState.OFF) }
        var symbols by remember { mutableStateOf(false) }
        var symPage2 by remember { mutableStateOf(false) }
        var lastShiftTapAt by remember { mutableStateOf(0L) }
        var numpad by remember { mutableStateOf(true) }
        var calcMode by remember { mutableStateOf(false) }
        // The calculator's state lives in plain fields outside composition. This
        // counter is how the LCD learns it changed — and it MUST be read inside
        // the LCD, not merely incremented here. Compose only recomposes on state
        // a composable actually reads; incrementing a counter nothing observes is
        // why pressing keys in CAL mode left the display frozen.
        var calcTick by remember { mutableStateOf(0) }

        // Suggestions (SH's three-pill redesign): live feed from the shared
        // engine, snapshot-on-open popup semantics (relay parity), rendered in
        // space RESERVED above the keyboard body by resizeWindow.
        var suggOpen by remember { mutableStateOf(false) }
        var suggestions by remember {
            mutableStateOf(
                emptyList<com.portalpad.app.service.PortalPadAccessibilityService.MirrorSuggestion>(),
            )
        }
        var overlaySugg by remember {
            mutableStateOf(
                emptyList<com.portalpad.app.service.PortalPadAccessibilityService.MirrorSuggestion>(),
            )
        }
        val suggScope = androidx.compose.runtime.rememberCoroutineScope()
        androidx.compose.runtime.LaunchedEffect(Unit) {
            com.portalpad.app.ui.common.SuggestionFeed.run(
                displayId = {
                    com.portalpad.app.PortalPadApp.instance.injector.displayId
                },
                query = { buffer.toString() },
            ) { suggestions = it }
        }

        val upper = shift != ShiftState.OFF
        fun typeKey(ch: String): Boolean {
            val out = if (upper) ch.uppercase() else ch
            val ok = typeChar(out)
            if (shift == ShiftState.SHIFT) shift = ShiftState.OFF
            return ok
        }

        Column(
            Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(AbBackground.copy(alpha = 0.92f))
                .border(1.dp, AbPrimaryDim, RoundedCornerShape(14.dp))
                .padding(KB_PAD_DP.dp),
        ) {
            // Strip FIRST, always - the drag handle stays in one place whether
            // or not the panel is open (SH). The suggestion panel unfolds
            // directly beneath it, sized to its SNAPSHOT (the window was
            // resized by exactly this much on open - a fixed reserve left a
            // dead gap over short lists).
            GrabStrip()
            Spacer(Modifier.height(KEY_GAP_DP.dp))
            if (suggOpen && suggPanelHdp > 0) {
                com.portalpad.app.ui.common.SuggestionFeed.SuggestionListPanel(
                    items = overlaySugg,
                    listMaxHeight = (suggPanelHdp - 34).dp,
                    onPick = { i, s ->
                        suggOpen = false
                        suggPanelHdp = 0
                        resizeWindow(numpad, calcMode, 0)
                        suggScope.launch {
                            // A pick rewrites the field externally - resync
                            // pulls the result back so the next keystroke
                            // can't rewrite the field from a stale buffer.
                            if (com.portalpad.app.ui.common.SuggestionFeed.pick(s, i)) {
                                runCatching { resync() }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(KEY_GAP_DP.dp))
            }

            Row {
                Column(Modifier.width(KB_CONTENT_W_DP.dp)) {
                    // SH's three-pill top row: brand, caret-nav pill, the
                    // suggestion trigger stretched between (its popup opens
                    // UPWARD - chevron agrees), and the edit pill. The letter
                    // column is always 712dp wide, so nothing here shifts when
                    // the numpad toggles.
                    Row(
                        Modifier.fillMaxWidth().height(KB_FNROW_H_DP.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BrandMark()
                        Spacer(Modifier.width(8.dp))
                        NavPill()
                        Spacer(Modifier.width(6.dp))
                        SuggTrigger(
                            count = suggestions.size,
                            open = suggOpen,
                            modifier = Modifier.weight(1f),
                            onToggle = {
                                if (!suggOpen) overlaySugg = suggestions
                                suggOpen = !suggOpen
                                suggPanelHdp =
                                    if (suggOpen) suggPanelHeightDp(overlaySugg) else 0
                                resizeWindow(numpad, calcMode, suggPanelHdp)
                            },
                        )
                        Spacer(Modifier.width(6.dp))
                        MediaPill()
                        Spacer(Modifier.width(6.dp))
                        EditPill(
                            onNumpad = { numpad = !numpad; resizeWindow(numpad, calcMode) },
                            numpadOn = numpad,
                        )
                    }
                    Spacer(Modifier.height(KEY_GAP_DP.dp))
                    KeyRows(
                        symbols = symbols,
                        symPage2 = symPage2,
                        shift = shift,
                        upper = upper,
                        numpad = numpad,
                        onType = { k -> lastWrote = typeKey(k) },
                        onTypeRaw = { k -> lastWrote = typeChar(k) },
                        onShift = {
                            val now = android.os.SystemClock.uptimeMillis()
                            shift = when {
                                shift == ShiftState.SHIFT &&
                                    now - lastShiftTapAt < CAPS_DOUBLE_TAP_MS -> ShiftState.CAPS
                                shift == ShiftState.OFF -> ShiftState.SHIFT
                                else -> ShiftState.OFF
                            }
                            lastShiftTapAt = now
                        },
                        onSymPage = { symPage2 = !symPage2 },
                        onSymbols = { symbols = !symbols; symPage2 = false },
                        onBackspace = { lastWrote = backspace() },
                        onSubmit = { lastWrote = submit() },
                    )
                }

                if (numpad) {
                    Spacer(Modifier.width(NUMPAD_SPLIT_DP.dp))
                    Column(Modifier.width(NUMPAD_W_DP.dp)) {
                        Lcd(
                            calcOn = calcMode,
                            tick = calcTick,
                            onToggle = {
                                calcMode = !calcMode
                                calcTick++
                                resizeWindow(numpad, calcMode)
                            },
                        )
                        Spacer(Modifier.height(KEY_GAP_DP.dp))
                        if (calcMode) {
                            // AC at the far left: it is the destructive one, and an
                            // edge has only one neighbour to slip in from. ⌫ sits
                            // beside Insert, where a mis-hit costs nothing. ± was
                            // dropped — it only negates an entry, which never comes
                            // up when the job is "work out a number for this form".
                            Row(horizontalArrangement = Arrangement.spacedBy(KEY_GAP_DP.dp)) {
                                CalcAction("AC") { calcKey("AC"); calcTick++ }
                                CalcAction("%") { calcKey("%"); calcTick++ }
                                CalcAction("⌫") { calcKey("⌫"); calcTick++ }
                                CalcAction("Insert", accent = true) {
                                    lastWrote = calcSend(); calcTick++
                                }
                            }
                            Spacer(Modifier.height(KEY_GAP_DP.dp))
                        }
                        NUMPAD.forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(KEY_GAP_DP.dp)) {
                                row.forEach { k ->
                                    KbKey(k, calcOperator = calcMode && k in OPERATOR_KEYS) {
                                        if (calcMode) { calcKey(k); calcTick++ }
                                        else lastWrote = typeChar(k)
                                    }
                                }
                            }
                            Spacer(Modifier.height(KEY_GAP_DP.dp))
                        }
                    }
                }
            }
        }
    }

    /** The four key rows, identical in both columns' terms so the caller can size
     *  around them. Split out only to keep ProbeContent readable. */
    @Composable
    private fun ColumnScope.KeyRows(
        symbols: Boolean,
        symPage2: Boolean,
        shift: ShiftState,
        upper: Boolean,
        numpad: Boolean,
        onType: (String) -> Unit,
        onTypeRaw: (String) -> Unit,
        onShift: () -> Unit,
        onSymPage: () -> Unit,
        onSymbols: () -> Unit,
        onBackspace: () -> Unit,
        onSubmit: () -> Unit,
    ) {
        if (symbols) {
            val base = if (symPage2) SYM_PAGE_2 else SYM_PAGE_1
            val page = if (!numpad && !symPage2) listOf(DIGITS_ROW) + base.drop(1) else base
            page.take(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(KEY_GAP_DP.dp)) {
                    row.forEach { k -> KbKey(k) { onTypeRaw(k) } }
                }
                Spacer(Modifier.height(KEY_GAP_DP.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(KEY_GAP_DP.dp)) {
                KbKey(if (symPage2) "2/2" else "1/2", wide = true) { onSymPage() }
                page[2].forEach { k -> KbKey(k) { onTypeRaw(k) } }
                KbKey("⌫", wide = true, repeatable = true) { onBackspace() }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(KEY_GAP_DP.dp)) {
                ROW_1.forEach { k -> KbKey(dis(k, upper)) { onType(k) } }
            }
            Spacer(Modifier.height(KEY_GAP_DP.dp))
            Row(
                Modifier.padding(horizontal = KB_STAGGER_DP.dp),
                horizontalArrangement = Arrangement.spacedBy(KEY_GAP_DP.dp),
            ) {
                ROW_2.forEach { k -> KbKey(dis(k, upper)) { onType(k) } }
            }
            Spacer(Modifier.height(KEY_GAP_DP.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(KEY_GAP_DP.dp)) {
                KbKey(
                    if (shift == ShiftState.CAPS) "⇪" else "⇧",
                    wide = true,
                    shiftState = shift,
                ) { onShift() }
                ROW_3.forEach { k -> KbKey(dis(k, upper)) { onType(k) } }
                KbKey("⌫", wide = true, repeatable = true) { onBackspace() }
            }
        }
        Spacer(Modifier.height(KEY_GAP_DP.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(KEY_GAP_DP.dp)) {
            KbKey(if (symbols) "abc" else "#@!", wide = true) { onSymbols() }  // label matches the phone keyboards (SH)
            KbKey(",") { onTypeRaw(",") }
            KbKey("space", spaceBar = true) { onTypeRaw(" ") }
            KbKey(".") { onTypeRaw(".") }
            KbKey("Go", wide = true, accent = true) { onSubmit() }
        }
    }

    /** Uppercase only what has an uppercase form — punctuation must pass through
     *  unchanged, or shift starts mangling keys it has no business touching. */
    private fun dis(k: String, upper: Boolean) = if (upper) k.uppercase() else k

    /**
     * Grab strip. The Row is full width and is the hit target; what is painted on
     * it is a faint track the width of the letter column with a short pill on top.
     *
     * The pill is centred on the LETTER COLUMN, not on the panel. The panel swings
     * ~300dp when the numpad toggles, so a panel-centred pill would jump sideways
     * under the cursor on every toggle — the letter column is always 712dp, the
     * same invariant the function row relies on to keep 123 still.
     *
     * Painting less does not catch less: the hit rect published by
     * publishDragRect() is the whole strip regardless. Close is pinned right, and
     * is NOT draggable.
     */
    @Composable
    private fun GrabStrip() {
        var hovered by remember { mutableStateOf(false) }
        var dragging by remember { mutableStateOf(false) }
        Row(
            Modifier
                .fillMaxWidth()
                .height(KB_STRIP_H_DP.dp)
                .clip(RoundedCornerShape(5.dp))
                // No background: highlighting the strip AND the pill lit two
                // things for one hover. The pill is the control; the strip is only
                // its hit area, and an invisible hit area is fine when the thing
                // you aim at is visible.
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent()
                            when (e.type) {
                                androidx.compose.ui.input.pointer.PointerEventType.Enter -> hovered = true
                                androidx.compose.ui.input.pointer.PointerEventType.Exit -> hovered = false
                                else -> Unit
                            }
                        }
                    }
                }
                .pointerInput(Unit) {
                    // ABSOLUTE positioning from the injector's cursor, never from
                    // deltas or positions measured INSIDE this window — the window
                    // this drag is moving. See dragTo() for why the window-local
                    // version diverged rather than merely drifted.
                    //
                    // The gesture detector is still what tells us a drag is in
                    // progress; its reported coordinates are simply not used to
                    // decide where the window goes.
                    detectDragGestures(
                        onDragStart = {
                            dragging = true
                            setDragging(true)
                            beginDrag()
                        },
                        onDragEnd = { dragging = false; setDragging(false); endDrag() },
                        onDragCancel = { dragging = false; setDragging(false); endDrag() },
                    ) { change, _ ->
                        change.consume()
                        dragTo()
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Balances the close button + its gap (30 + 6) so the weighted box's
            // center IS the panel's center - without this the "centred" pill
            // still sat 18dp left of true center.
            Spacer(Modifier.width(36.dp))
            Box(Modifier.weight(1f).height(KB_PILL_H_DP.dp)) {
                // Track AND pill centred on the PANEL (SH, 2026-07-24): same
                // widths as before, just symmetric in every state - suggestions
                // open or closed, numpad on or off. This REVERSED the earlier
                // letter-column anchoring (whose rationale was that a panel-
                // centred pill shifts sideways when the numpad toggles - SH
                // weighed that and prefers the centred look).
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .width(KB_CONTENT_W_DP.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(2.dp))
                        .background(AbPrimaryDim.copy(alpha = 0.22f)),
                )
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .width(KB_PILL_W_DP.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            when {
                                dragging -> AbPrimaryBright
                                hovered -> AbPrimary
                                else -> AbPrimaryDim
                            },
                        ),
                )
            }
            Spacer(Modifier.width(6.dp))
            CloseButton()
        }
    }

    @Composable
    private fun CloseButton() {
        var hovered by remember { mutableStateOf(false) }
        Box(
            // Generous target: the glyph is small and sits near the panel edge,
            // where the cursor tends to overshoot.
            Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(5.dp))
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent()
                            when (e.type) {
                                androidx.compose.ui.input.pointer.PointerEventType.Enter -> hovered = true
                                androidx.compose.ui.input.pointer.PointerEventType.Exit -> hovered = false
                                androidx.compose.ui.input.pointer.PointerEventType.Press -> dismiss()
                                else -> Unit
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "✕",
                // Red on hover: desktop convention, and it marks the one
                // destructive control in a strip where nothing else is.
                color = if (hovered) CLOSE_HOVER else AbOnSurfaceMuted,
                fontSize = 13.sp,
            )
        }
    }

    /** App icon + wordmark. The icon is the launcher FOREGROUND only — the full
     *  adaptive icon carries a near-black background tile that would be invisible
     *  here and just eat width. Cropped to the glyph's bounds because the
     *  foreground layer is authored with adaptive-icon safe-zone padding. */
    @Composable
    private fun BrandMark() {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.Canvas(Modifier.size(17.dp)) {
                val s = size.minDimension / 66f
                fun px(v: Float) = v * s
                val cx = px(54f - 21f)
                val cy = px(40f - 22f)
                drawOval(
                    color = BRAND_MARK_OUTER,
                    topLeft = androidx.compose.ui.geometry.Offset(cx - px(23f), cy - px(16f)),
                    size = androidx.compose.ui.geometry.Size(px(46f), px(32f)),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(px(1.5f)),
                )
                drawOval(
                    color = BRAND_MARK_ACCENT,
                    topLeft = androidx.compose.ui.geometry.Offset(cx - px(18f), cy - px(12f)),
                    size = androidx.compose.ui.geometry.Size(px(36f), px(24f)),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(px(3f)),
                )
                drawLine(
                    color = BRAND_MARK_ACCENT,
                    start = androidx.compose.ui.geometry.Offset(cx, px(49f - 22f)),
                    end = androidx.compose.ui.geometry.Offset(cx, px(69f - 22f)),
                    strokeWidth = px(2f),
                )
                drawRoundRect(
                    color = BRAND_MARK_RING,
                    topLeft = androidx.compose.ui.geometry.Offset(px(23f - 21f), px(60f - 22f)),
                    size = androidx.compose.ui.geometry.Size(px(62f), px(24f)),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(px(9f)),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(px(2f)),
                )
                drawCircle(
                    color = BRAND_MARK_ACCENT,
                    radius = px(3f),
                    center = androidx.compose.ui.geometry.Offset(cx, px(72f - 22f)),
                )
            }
            Spacer(Modifier.width(5.dp))
            Text("PortalPad", color = BRAND_TEXT, fontSize = 12.sp)
        }
    }

    /** Shared pill chrome for the top-row groups (SH's three-pill redesign
     *  replaced the old single FunctionBar). */
    @Composable
    private fun FnPill(bordered: Boolean = true, content: @Composable RowScope.() -> Unit) {
        Row(
            Modifier
                .clip(RoundedCornerShape(6.dp))
                // Wrapper fill + border (SH): the app-wide "bordered = control"
                // language. The media pill opts OUT of BOTH - its keys carry
                // their own borders/fills, so the wrapper's box AND its faint
                // tinted background were redundant around them.
                .then(
                    if (bordered) {
                        Modifier
                            .background(AbSurfaceVariant)
                            .border(1.dp, AbPrimaryDim.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = 5.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }

    /** Left pill: caret navigation. */
    @Composable
    private fun NavPill() {
        FnPill {
            FnKey("◀") { moveCaret(-1) }
            FnKey("▶") { moveCaret(1) }
            FnKey("Home", text = true) { moveCaret(-1, toEdge = true) }
            FnKey("End", text = true) { moveCaret(1, toEdge = true) }
        }
    }

    /** Right pill: clipboard operations + the numpad toggle. */
    @Composable
    private fun EditPill(onNumpad: () -> Unit, numpadOn: Boolean) {
        FnPill {
            FnKey("Select all", text = true) { edit("selectAll") }
            FnKey("Cut", text = true) { edit("cut") }
            FnKey("Copy", text = true) { edit("copy") }
            FnKey("Paste", text = true) { edit("paste") }
            FnDivider()
            FnKey("123", text = true, active = numpadOn) { onNumpad() }
        }
    }

    /** Center trigger: opens the suggestion panel ABOVE the keyboard (chevron
     *  agrees). Dimmed and inert while the live list is empty. */
    @Composable
    private fun SuggTrigger(
        count: Int,
        open: Boolean,
        modifier: Modifier = Modifier,
        onToggle: () -> Unit,
    ) {
        val enabled = count > 0
        var hovered by remember { mutableStateOf(false) }
        val toggle by rememberUpdatedState(onToggle)
        Row(
            modifier
                .clip(RoundedCornerShape(6.dp))
                .background(
                    when {
                        open -> AbPrimaryDim
                        hovered && enabled -> AbPrimaryDim.copy(alpha = 0.6f)
                        else -> AbSurfaceVariant
                    },
                )
                .border(1.dp, AbPrimaryDim.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                .pointerInput(enabled) {
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent()
                            when (e.type) {
                                androidx.compose.ui.input.pointer.PointerEventType.Enter -> hovered = true
                                androidx.compose.ui.input.pointer.PointerEventType.Exit -> hovered = false
                                androidx.compose.ui.input.pointer.PointerEventType.Press ->
                                    if (enabled) toggle()
                                else -> Unit
                            }
                        }
                    }
                }
                .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            // Content CENTERED in the bar (SH): left-anchored text with a
            // far-right chevron read as a stretched strip, not a button.
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                "★",
                color = if (enabled) AbPrimaryBright else AbOnSurfaceMuted,
                fontSize = 11.sp,
            )
            Spacer(Modifier.width(5.dp))
            Text(
                if (enabled) {
                    "$count suggestion" + (if (count == 1) "" else "s")
                } else {
                    "Suggestions"
                },
                color = if (enabled) AbOnSurface else AbOnSurfaceMuted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(5.dp))
            Text("▴", color = AbOnSurfaceMuted, fontSize = 11.sp)
        }
    }

    /** Media transport: same MediaController the remote's panel drives, so a
     *  keyboard tap and a remote tap are indistinguishable to the player. Just
     *  the three transport keys - the remote keeps the full panel (seek bar,
     *  position, rewind/fast-forward); play-pause stays a static combo glyph
     *  because apps misreport play state (SH). */
    @Composable
    private fun MediaPill() {
        val media = com.portalpad.app.PortalPadApp.instance.media
        // No wrapper border (SH): the media KEYS keep their own borders; the
        // outer box around them was the one to drop.
        FnPill(bordered = false) {
            // SH's shape language: PILL skip keys flanking a CIRCLE play-pause,
            // each with a faint border - the border is what makes them read as
            // buttons instead of tinted patches. Play-pause keeps its violet
            // tint; drawn glyphs (font ⏮⏯⏭ read as fine print).
            MediaKey(
                shape = RoundedCornerShape(50),
                idleBg = AbSurfaceElevated,
                modifier = Modifier
                    .width(34.dp)
                    .height(20.dp)
                    .onGloballyPositioned { noteSeekButton(ff = false, coords = it) },
                onTap = { runCatching { media.rewind() } },
                onHoldRepeat = { runCatching { media.rewind() } },
            ) { TransportGlyph(0) }
            Spacer(Modifier.width(4.dp))
            MediaKey(
                shape = androidx.compose.foundation.shape.CircleShape,
                idleBg = AbPrimaryDim.copy(alpha = 0.45f),
                // Gentle violet gradient so play-pause reads as the transport's
                // centerpiece (SH: "a little more fun").
                idleBrush = androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(
                        AbPrimary.copy(alpha = 0.60f),
                        AbPrimaryDim.copy(alpha = 0.35f),
                    ),
                ),
                // 30dp: the centerpiece must read BIGGER than the 34x20 pills
                // (SH - width dominates perception, 24dp read smaller).
                modifier = Modifier.size(30.dp),
                onTap = { runCatching { media.playPause() } },
            ) { TransportGlyph(1) }
            Spacer(Modifier.width(4.dp))
            MediaKey(
                shape = RoundedCornerShape(50),
                idleBg = AbSurfaceElevated,
                modifier = Modifier
                    .width(34.dp)
                    .height(20.dp)
                    .onGloballyPositioned { noteSeekButton(ff = true, coords = it) },
                onTap = { runCatching { media.fastForward() } },
                onHoldRepeat = { runCatching { media.fastForward() } },
            ) { TransportGlyph(2) }
        }
    }

    /** A shaped transport button: faint border + hover/press states in the
     *  keyboard's own colors. Separate from FnKey on purpose - these carry
     *  shapes and fixed sizes the flat fn keys never need. */
    @Composable
    private fun MediaKey(
        shape: androidx.compose.ui.graphics.Shape,
        idleBg: androidx.compose.ui.graphics.Color,
        modifier: Modifier,
        idleBrush: androidx.compose.ui.graphics.Brush? = null,
        onTap: () -> Unit,
        onHoldRepeat: (() -> Unit)? = null,
        glyph: @Composable () -> Unit,
    ) {
        var hovered by remember { mutableStateOf(false) }
        var pressed by remember { mutableStateOf(false) }
        val tap by rememberUpdatedState(onTap)
        // Press-and-hold repeat (SH): after a short hold threshold, re-fire
        // ~2x/sec until release - with the 10s seek step that reads as
        // continuous rewind/fast-forward. Tap semantics unchanged (the Press
        // event already fired one step).
        if (onHoldRepeat != null) {
            val holdTick by rememberUpdatedState(onHoldRepeat)
            LaunchedEffect(pressed) {
                if (pressed) {
                    kotlinx.coroutines.delay(400)
                    while (true) {
                        holdTick()
                        kotlinx.coroutines.delay(500)
                    }
                }
            }
        }
        Box(
            modifier
                // Press pop: a quick shrink confirms the hit (SH: "fun").
                .graphicsLayer {
                    val sc = if (pressed) 0.88f else 1f
                    scaleX = sc
                    scaleY = sc
                }
                .clip(shape)
                .then(
                    when {
                        hovered -> Modifier.background(AbPrimaryDim.copy(alpha = 0.7f))
                        idleBrush != null -> Modifier.background(idleBrush)
                        else -> Modifier.background(idleBg)
                    },
                )
                .border(1.dp, AbPrimaryDim.copy(alpha = 0.55f), shape)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent()
                            when (e.type) {
                                androidx.compose.ui.input.pointer.PointerEventType.Enter -> hovered = true
                                androidx.compose.ui.input.pointer.PointerEventType.Exit -> {
                                    hovered = false
                                    pressed = false
                                }
                                androidx.compose.ui.input.pointer.PointerEventType.Press -> {
                                    pressed = true
                                    tap()
                                }
                                androidx.compose.ui.input.pointer.PointerEventType.Release ->
                                    pressed = false
                                else -> Unit
                            }
                        }
                    }
                }
                // Breathing room: the glyph must never graze the button edge
                // (SH - they were nearly touching).
                .padding(3.dp),
            contentAlignment = Alignment.Center,
        ) {
            glyph()
        }
    }

    /** Crisp transport shapes in the keyboard's own colors: 0 = previous
     *  (bar + left triangle), 1 = play-pause combo (triangle + two bars,
     *  static on purpose - apps misreport play state), 2 = next. */
    @Composable
    private fun TransportGlyph(kind: Int) {
        androidx.compose.foundation.Canvas(
            Modifier.width(14.dp).height(9.dp),
        ) {
            // TWO-TONE (SH: "more fun"): triangles in the violet accent, bars
            // in amber - echoing the calculator's amber operators, so the
            // playfulness stays inside the keyboard's own palette.
            val triC = AbPrimaryBright
            val barC = AbAccent
            val h = size.height
            val w = size.width
            fun tri(tipX: Float, baseX: Float) {
                drawPath(
                    androidx.compose.ui.graphics.Path().apply {
                        moveTo(baseX, 0f)
                        lineTo(tipX, h / 2f)
                        lineTo(baseX, h)
                        close()
                    },
                    triC,
                )
            }
            val bar = w * 0.16f
            when (kind) {
                0 -> { // rewind: double left-pointing triangles (SH - seek,
                    // not track-skip; the bar was skip language)
                    tri(tipX = 0f, baseX = w * 0.48f)
                    tri(tipX = w * 0.52f, baseX = w)
                }
                1 -> { // play-pause: triangle pointing right + two pause bars
                    tri(tipX = w * 0.44f, baseX = 0f)
                    drawRect(
                        barC,
                        topLeft = androidx.compose.ui.geometry.Offset(w * 0.60f, 0f),
                        size = androidx.compose.ui.geometry.Size(bar, h),
                    )
                    drawRect(
                        barC,
                        topLeft = androidx.compose.ui.geometry.Offset(w * 0.84f, 0f),
                        size = androidx.compose.ui.geometry.Size(bar, h),
                    )
                }
                else -> { // fast-forward: double right-pointing triangles
                    tri(tipX = w * 0.48f, baseX = 0f)
                    tri(tipX = w, baseX = w * 0.52f)
                }
            }
        }
    }

    /**
     * The LCD. Off draws GHOST SEGMENTS at low opacity rather than an empty box —
     * a real display with its backlight off still shows its segments, and that
     * detail is what makes it read as a screen.
     *
     * [tick] exists to be READ. The calculator's state is plain fields, so without
     * observing something that changes, this never recomposes and the display sits
     * frozen while the keys work perfectly.
     */
    @Composable
    private fun Lcd(calcOn: Boolean, tick: Int, onToggle: () -> Unit) {
        @Suppress("UNUSED_EXPRESSION") tick
        var hovered by remember { mutableStateOf(false) }
        Row(
            Modifier.fillMaxWidth().height(lcdHeightDp(calcOn).dp),
            horizontalArrangement = Arrangement.spacedBy(KEY_GAP_DP.dp),
        ) {
            Box(
                Modifier
                    .width(LCD_TOGGLE_W_DP.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(5.dp))
                    .background(
                        when {
                            // Active + hovered brightens (SH): selected CALC gave
                            // no cursor feedback - calcOn won the branch first.
                            calcOn && hovered -> AbPrimaryBright
                            calcOn -> AbPrimary
                            hovered -> AbPrimaryDim
                            else -> AbSurfaceVariant
                        },
                    )
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val e = awaitPointerEvent()
                                when (e.type) {
                                    androidx.compose.ui.input.pointer.PointerEventType.Enter -> hovered = true
                                    androidx.compose.ui.input.pointer.PointerEventType.Exit -> hovered = false
                                    androidx.compose.ui.input.pointer.PointerEventType.Press -> onToggle()
                                    else -> Unit
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "CALC",
                    color = if (calcOn) AbBackground else AbOnSurfaceMuted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (calcOn) LCD_ON_BG else LCD_OFF_BG)
                    .border(
                        1.dp,
                        if (calcOn) LCD_ON_EDGE else LCD_OFF_EDGE,
                        RoundedCornerShape(5.dp),
                    )
                    // No vertical padding when shut: the box is 22dp and the ghost
                    // is 15sp, which needs ~19 of it. The 3dp that gives the two
                    // lines room to breathe would clip the single one.
                    .padding(
                        horizontal = LCD_PAD_H_DP.dp,
                        vertical = if (calcOn) 3.dp else 0.dp,
                    ),
                verticalArrangement =
                    if (calcOn) Arrangement.SpaceBetween else Arrangement.Center,
                horizontalAlignment = Alignment.End,
            ) {
                // Both lines right-aligned, the way a desk calculator is: the
                // digits being entered stay at a fixed point on the glass instead
                // of sliding as the expression grows. The expression line is only
                // present at all while the calculator is on — when it is off the
                // box is one line tall and there is nothing to put here.
                if (calcOn) {
                    // Operators AMBER, digits green (SH: + and ÷ were still hard
                    // to tell apart at any readable size - color separates them
                    // without costing characters, and it rhymes with the amber
                    // operator keys on the pad). Only STANDALONE operators are
                    // tinted (space on both sides): a negative number's minus is
                    // part of the number and stays untinted.
                    val exprStr = calcExpr()
                    Text(
                        androidx.compose.ui.text.buildAnnotatedString {
                            exprStr.forEachIndexed { i, ch ->
                                val op = ch in "+-×÷" &&
                                    (i == 0 || exprStr[i - 1] == ' ') &&
                                    (i == exprStr.length - 1 || exprStr[i + 1] == ' ')
                                if (op) {
                                    pushStyle(
                                        androidx.compose.ui.text.SpanStyle(color = LCD_OP_TINT),
                                    )
                                    append(ch)
                                    pop()
                                } else {
                                    append(ch)
                                }
                            }
                        },
                        color = LCD_ON_FAINT,
                        fontSize = LCD_EXPR_SP.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        maxLines = 1,
                        // Backstop only — calcExpr() has already truncated from the
                        // left. If this ever fires the cap is wrong, not the text.
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
                val preview = calcOn && calcPreview() != null
                val result = if (calcOn) calcDisplay() else LCD_GHOST_TEXT
                Text(
                    result,
                    // Dim while it is a prediction, full brightness once "=" has
                    // committed it. That difference is what stops the preview from
                    // making "=" look pointless: the key still visibly does
                    // something, it just does it to the state rather than to the
                    // arithmetic.
                    color = when {
                        !calcOn -> LCD_GHOST
                        preview -> LCD_ON_DIM
                        else -> LCD_ON_TEXT
                    },
                    // Shrink before resorting to scientific notation: a plain
                    // number a little smaller reads better than a correct one in
                    // exponent form. DELIBERATELY no ellipsis here — Compose
                    // ellipsizes on the right, which on a number is the silent
                    // wrong-value failure this whole change exists to remove.
                    fontSize = lcdResultSp(result.length).sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    maxLines = 1,
                )
            }
        }
    }

    @Composable
    private fun FnDivider() {
        Box(
            Modifier
                .padding(horizontal = 3.dp)
                .width(1.dp)
                .height(12.dp)
                .background(AbOnSurfaceDim),
        )
    }

    @Composable
    private fun FnKey(
        label: String,
        text: Boolean = false,
        active: Boolean = false,
        // Override for dense glyphs (the media transport symbols) that read
        // smaller than letters at the same nominal size.
        fontSize: androidx.compose.ui.unit.TextUnit? = null,
        // Idle background. Null = transparent (the classic flat fn key). The
        // media transport passes key-style backgrounds so those read as
        // BUTTONS, in the keyboard's own key-on-surface language (SH: they
        // looked plain/out of place).
        idleBg: androidx.compose.ui.graphics.Color? = null,
        // Drawn content instead of the text label (label then serves as the
        // accessible name only). Used by the media transport glyphs.
        icon: (@Composable () -> Unit)? = null,
        onTap: () -> Unit,
    ) {
        var hovered by remember { mutableStateOf(false) }
        // Same stale-lambda hazard as KbKey: pointerInput keeps the lambda from
        // first composition unless the current one is read through state.
        val tap by rememberUpdatedState(onTap)
        Box(
            Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(
                    when {
                        // Active + hovered brightens (SH: the selected "123"
                        // gave no cursor feedback - active alone won the when).
                        active && hovered -> AbPrimary.copy(alpha = 0.75f)
                        active -> AbPrimaryDim
                        hovered -> AbPrimaryDim.copy(alpha = 0.6f)
                        else -> idleBg ?: androidx.compose.ui.graphics.Color.Transparent
                    },
                )
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent()
                            when (e.type) {
                                androidx.compose.ui.input.pointer.PointerEventType.Enter -> hovered = true
                                androidx.compose.ui.input.pointer.PointerEventType.Exit -> hovered = false
                                androidx.compose.ui.input.pointer.PointerEventType.Press -> tap()
                                else -> Unit
                            }
                        }
                    }
                }
                .padding(horizontal = 5.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) {
                icon()
            } else {
                Text(
                    label,
                    color = if (active) AbBackground else AbPrimaryBright,
                    // 10/11 -> 11/12 (SH): the row read as fine print over
                    // full-size keys, even after the global density bump.
                    fontSize = fontSize ?: if (text) 11.sp else 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }

    @Composable
    private fun RowScope.CalcAction(
        label: String,
        accent: Boolean = false,
        onTap: () -> Unit,
    ) {
        var hovered by remember { mutableStateOf(false) }
        val tap by rememberUpdatedState(onTap)
        Box(
            Modifier
                .weight(1f)
                .height(CALC_ACTION_H_DP.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(
                    when {
                        hovered -> AbPrimaryDim
                        accent -> AbPrimary
                        else -> AbSurfaceVariant
                    },
                )
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent()
                            when (e.type) {
                                androidx.compose.ui.input.pointer.PointerEventType.Enter -> hovered = true
                                androidx.compose.ui.input.pointer.PointerEventType.Exit -> hovered = false
                                androidx.compose.ui.input.pointer.PointerEventType.Press -> tap()
                                else -> Unit
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                color = if (accent) AbBackground else LCD_ACTION_TEXT,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }

    /**
     * One key. [spaceBar] takes the leftover width so row 4 ends flush with the
     * others; [repeatable] gives backspace hold-to-repeat, which a cursor needs
     * as much as a finger does.
     */
    @Composable
    private fun RowScope.KbKey(
        label: String,
        wide: Boolean = false,
        spaceBar: Boolean = false,
        accent: Boolean = false,
        shiftState: ShiftState? = null,
        calcOperator: Boolean = false,
        repeatable: Boolean = false,
        onTap: () -> Unit,
    ) {
        var hovered by remember { mutableStateOf(false) }
        var pressed by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        // pointerInput only restarts when its KEY changes. Switching 1/2 -> 2/2
        // reuses these call sites, so `repeatable` stays the same and the gesture
        // handler kept the onTap lambda captured on FIRST composition — page 1's.
        // The label recomposed and showed page-2 glyphs while every key still
        // typed its page-1 character. rememberUpdatedState keeps the handler
        // pointing at the current lambda without restarting the gesture loop.
        val tap by rememberUpdatedState(onTap)

        val sizing =
            if (spaceBar) Modifier.weight(1f)
            else Modifier.width(if (wide) KEY_WIDE_W_DP.dp else KEY_W_DP.dp)

        Box(
            sizing
                .height(KEY_H_DP.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(
                    when {
                        pressed -> AbPrimaryBright
                        // Shift-selected keys brighten ABOVE their active tint on
                        // hover (SH): plain `hovered -> AbPrimaryDim` gave CAPS
                        // (idle AbPrimary) a DARKER hover and SHIFT (idle
                        // AbPrimaryDim) no change at all - so a selected shift
                        // key looked inert under the cursor.
                        hovered && shiftState == ShiftState.CAPS -> AbPrimaryBright
                        hovered && shiftState == ShiftState.SHIFT -> AbPrimary
                        hovered -> AbPrimaryDim
                        accent -> AbPrimary
                        // Shift's three states are carried by COLOUR first and the
                        // glyph second: dim → mid → full violet. ⇧ vs ⇪ alone is
                        // too subtle to read mid-typing, and caps lock sharing
                        // Enter's saturation is the signal that it is latched.
                        shiftState == ShiftState.CAPS -> AbPrimary
                        shiftState == ShiftState.SHIFT -> AbPrimaryDim
                        calcOperator -> CALC_OP_BG
                        wide || spaceBar -> AbSurfaceVariant
                        else -> AbSurfaceElevated
                    },
                )
                .pointerInput(repeatable) {
                    awaitPointerEventScope {
                        while (true) {
                            val down = awaitPointerEvent()
                            when (down.type) {
                                androidx.compose.ui.input.pointer.PointerEventType.Enter ->
                                    hovered = true
                                androidx.compose.ui.input.pointer.PointerEventType.Exit -> {
                                    hovered = false
                                    pressed = false
                                }
                                androidx.compose.ui.input.pointer.PointerEventType.Press -> {
                                    pressed = true
                                    tap()
                                    if (repeatable) {
                                        scope.launch {
                                            kotlinx.coroutines.delay(400)
                                            while (pressed) {
                                                tap()
                                                kotlinx.coroutines.delay(60)
                                            }
                                        }
                                    }
                                }
                                androidx.compose.ui.input.pointer.PointerEventType.Release ->
                                    pressed = false
                                else -> Unit
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    label,
                    color = when {
                        accent || shiftState == ShiftState.CAPS -> AbBackground
                        calcOperator -> CALC_OP_FG
                        else -> AbOnSurface
                    },
                    // Size by CONTENT, not by key width. ⇧ ⌫ ↵ are single glyphs
                    // that happen to sit on wide keys; at the 16sp meant for text
                    // labels like "?123" and "space" they were nearly unreadable —
                    // the enter arrow especially.
                    fontSize = when {
                        label.length == 1 && (wide || spaceBar) -> 26.sp
                        wide || spaceBar -> 16.sp
                        else -> 24.sp
                    },
                    fontWeight = FontWeight.Medium,
                )
                // The bar under a latched shift — a second, independent cue, so
                // the state survives a display where colour is washed out.
                if (shiftState == ShiftState.CAPS) {
                    Spacer(Modifier.height(3.dp))
                    Box(
                        Modifier
                            .width(22.dp)
                            .height(2.dp)
                            .background(AbBackground),
                    )
                }
            }
        }
    }
}
