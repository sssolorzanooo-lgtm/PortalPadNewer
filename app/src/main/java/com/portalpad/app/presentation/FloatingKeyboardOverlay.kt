package com.portalpad.app.presentation

import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
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
import com.portalpad.app.CustomKeyboardSection
import com.portalpad.app.PortalPadApp
import com.portalpad.app.ui.common.SuggestionFeed
import com.portalpad.app.ui.theme.AbBackground
import com.portalpad.app.ui.theme.AbOnSurface
import com.portalpad.app.ui.theme.AbOnSurfaceMuted
import com.portalpad.app.ui.theme.AbPrimaryBright
import com.portalpad.app.ui.theme.AbSurfaceElevated
import com.portalpad.app.ui.theme.PortalPadTheme

/**
 * PortalPad's own keyboard, floating over the PHONE — the on-phone sibling of the
 * on-display keyboard ([ExternalKeyboardProbe]). Keys only: it reuses the relay's
 * [CustomKeyboardSection] (same keys, swap-layer, emoji, mic, and the cut / copy /
 * paste row) with NO text box and none of the relay page's chrome, and it types
 * into the focused field the same way — the injector's accessibility SET_TEXT
 * path, so text lands in the field you tapped on the external display.
 *
 * Full-screen window with the keyboard docked at the bottom; the transparent area
 * above it is a dismiss scrim, which is how "tap the trackpad to put it away"
 * works — the tap dismisses, then the next tap reaches the trackpad. FLAG_NOT_
 * FOCUSABLE for the same reason [ExternalKeyboardProbe] uses it: the overlay must
 * never become the input target, or the external-display field loses the
 * accessibility focus we type into.
 *
 * Singleton like [ExternalKeyboardProbe], and its lifecycle scaffolding follows
 * the same hard-won rules: performAttach + ON_CREATE happen ONCE for the process,
 * never re-entered from a destroyed state (that crash is documented there).
 *
 * Build 1 raises this from the keyboard button only. Field-tap parity (raise on a
 * field tap, mirroring the on-display branch) is Build 2.
 */
object FloatingKeyboardOverlay : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private const val TAG = "PortalPadFloatKb"

    private var view: ComposeView? = null
    private var windowManager: WindowManager? = null
    private var isShowing = false
    private var stateAttached = false

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private val savedStateController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    private val store = ViewModelStore()
    override val viewModelStore: ViewModelStore get() = store

    fun isVisible(): Boolean = isShowing

    // Field-tap seeding (Build 2): the a11y branch passes the tapped field's
    // text + caret so backspace knows the existing content. Button-raised shows
    // keep the empty default. Parity limitation shared with the on-display
    // keyboard: a second field tap while visible does NOT reseed (show() early-
    // returns) - same accepted behavior there.
    private var seedText: String = ""
    private var seedCaret: Int = 0

    private val outsideTapHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Injected tap on the EXTERNAL display while the floating keyboard is up on
     *  the phone - every display tap is outside its window by definition. Same
     *  watchdog contract as the on-display keyboard: dismiss after a grace
     *  window UNLESS an a11y click event arrived for the tap (then the a11y
     *  branch is the authority - it keeps/reseeds on fields, dismisses on other
     *  clickables). Covers empty-space taps that emit no event at all. */
    fun onInjectedTapOutside() {
        if (!isShowing) return
        val tapAt = android.os.SystemClock.elapsedRealtime()
        outsideTapHandler.postDelayed({
            if (!isShowing) return@postDelayed
            val svc = com.portalpad.app.service.PortalPadAccessibilityService.instance
            if (svc != null && svc.lastClickEventElapsed >= tapAt) {
                Log.i(TAG, "DIAG-FLOATKB outside-tap watchdog stood down (a11y click handled it)")
            } else {
                Log.i(TAG, "DIAG-FLOATKB empty-space display tap -> dismiss floating keyboard")
                dismiss()
            }
        }, OUTSIDE_TAP_GRACE_MS)
    }

    private const val OUTSIDE_TAP_GRACE_MS = 450L

    fun toggle(context: Context) {
        if (isShowing) dismiss() else show(context)
    }

    fun show(context: Context, initialText: String = "", initialCaret: Int = -1) {
        if (isShowing) return
        seedText = initialText
        seedCaret = if (initialCaret in 0..initialText.length) initialCaret else initialText.length
        val appCtx = context.applicationContext
        val wm = appCtx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: run {
            Log.w(TAG, "show failed: no WindowManager")
            return
        }

        // ONCE for the process — see ExternalKeyboardProbe for why re-entering
        // these transitions crashes.
        if (!stateAttached) {
            savedStateController.performAttach()
            savedStateController.performRestore(null)
            stateAttached = true
        }
        if (lifecycleRegistry.currentState == Lifecycle.State.INITIALIZED) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        }

        val composeView = ComposeView(appCtx).apply {
            setViewTreeLifecycleOwner(this@FloatingKeyboardOverlay)
            setViewTreeViewModelStoreOwner(this@FloatingKeyboardOverlay)
            setViewTreeSavedStateRegistryOwner(this@FloatingKeyboardOverlay)
            // Phone-native density on purpose (unlike the on-display keyboard,
            // which pins density against the DPI slider): the keys are aimed with
            // thumbs on the phone, so they should be the phone's own size.
            setContent { PortalPadTheme(phoneTheme = false) { FloatingContent() } }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // NOT_FOCUSABLE: never steal focus from the external-display field we
            // type into. Touchable (no NOT_TOUCHABLE) so the keys receive taps.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        runCatching { wm.addView(composeView, params) }
            .onSuccess {
                view = composeView
                windowManager = wm
                isShowing = true
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
                Log.i(TAG, "DIAG-FLOATKB shown")
            }
            .onFailure { Log.w(TAG, "DIAG-FLOATKB addView failed", it) }
    }

    fun dismiss() {
        if (!isShowing) return
        isShowing = false
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        view?.let { v -> runCatching { windowManager?.removeView(v) } }
        view = null
        windowManager = null
        Log.i(TAG, "DIAG-FLOATKB dismissed")
    }

    @Composable
    private fun FloatingContent() {
        val injector = PortalPadApp.instance.injector
        // Live suggestion feed - the shared engine polls the display app's own
        // suggestion UI keyed to whatever the keyboard has typed (the section
        // pushes every keystroke into searchQuery, same as the relay's custom
        // tab). Snapshot-on-open semantics, same as the relay: the popup shows
        // the list as it was when the trigger was tapped, because the live list
        // routinely drains right after (the app's dropdown dies).
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
        var showSuggList by remember { mutableStateOf(false) }
        val suggScope = rememberCoroutineScope()
        LaunchedEffect(Unit) {
            // Seed the query with the field's existing text so a suggestion list
            // for it is available before the first keystroke (relay parity).
            injector.setSearchQuery(seedText)
            SuggestionFeed.run(
                displayId = { injector.displayId },
                query = { injector.searchQuery.value },
            ) { suggestions = it }
        }
        Box(Modifier.fillMaxSize()) {
            // Everything above the keyboard is a transparent tap-to-exit region:
            // the trackpad / remote / air-mouse interface shows through it, and a
            // tap there dismisses the overlay, dropping you back onto that
            // interface (then the next tap reaches it). This IS the "see the
            // interface above and tap to go back" behaviour SH asked for - no
            // header bar, no page. With the suggestion popup open, the first
            // tap closes THAT (popup convention) and the keyboard stays.
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures {
                            if (showSuggList) {
                                showSuggList = false
                            } else {
                                // The one deliberate silent-dismiss path used to be
                                // unloggable - SH's mid-typing hides took a capture
                                // to pin down because of it. Name it.
                                Log.i(TAG, "DIAG-FLOATKB tap-to-exit region -> dismiss")
                                dismiss()
                            }
                        }
                    },
            )
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
            ) {
                // Suggestion popup, attached directly above the keyboard panel -
                // it opens UP (the trigger's chevron direction) into the
                // interface area, which stays tappable around it.
                if (showSuggList && overlaySugg.isNotEmpty()) {
                    SuggestionFeed.SuggestionListPanel(
                        items = overlaySugg,
                        listMaxHeight = 280.dp,
                        onPick = { i, s ->
                            showSuggList = false
                            suggScope.launch {
                                // A successful pick submits and the page
                                // navigates - the keyboard's job is done, same
                                // as the relay closing after a pick.
                                if (SuggestionFeed.pick(s, i)) dismiss()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp)
                            .padding(bottom = 4.dp),
                    )
                }
                // Only as tall as the keyboard, docked at the bottom, so the interface
                // stays visible above it. showMirror = false: no text box, clip row
                // packed against the keys.
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                        // AbBackground, NOT AbSurface: the digit keys draw with
                        // AbSurface backgrounds, so an AbSurface container swallowed
                        // their chrome and the digits floated as bare text (SH
                        // screenshot). The relay page's darker backdrop is what makes
                        // the same keys read as keys - match it.
                        .background(AbBackground)
                        // Swallow every touch inside the panel's bounds: without
                        // this, a press landing in the gaps BETWEEN keys fell
                        // through to the tap-to-exit layer behind and closed the
                        // keyboard mid-typing (SH's capture: bare dismissed lines
                        // seconds after shown). A missed key must be a dead tap,
                        // like on the relay page - not an exit.
                        .pointerInput(Unit) { detectTapGestures { } }
                        .navigationBarsPadding()
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                ) {
                    // Trigger row (relay parity, chevron UP - the popup opens
                    // above). Visibility follows the LIVE list; the open popup
                    // keeps its snapshot regardless.
                    if (suggestions.isNotEmpty()) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(bottom = 4.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    if (!showSuggList) overlaySugg = suggestions
                                    showSuggList = !showSuggList
                                }
                                .background(AbSurfaceElevated)
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("★", color = AbPrimaryBright, fontSize = 13.sp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "${suggestions.size} suggestion" +
                                    if (suggestions.size == 1) "" else "s",
                                color = AbOnSurface,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text("▴", color = AbOnSurfaceMuted, fontSize = 12.sp)
                        }
                    }
                    CustomKeyboardSection(
                        injector = injector,
                        initialText = seedText,
                        initialCaret = seedCaret,
                        showMirror = false,
                    )
                }
            }
        }
    }
}
