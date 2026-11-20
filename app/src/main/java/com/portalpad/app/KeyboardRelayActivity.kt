package com.portalpad.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import android.view.KeyEvent
import com.portalpad.app.service.InputInjector
import com.portalpad.app.ui.common.MenuScrollThumb
import com.portalpad.app.service.PortalPadAccessibilityService
import com.portalpad.app.ui.theme.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Keyboard relay screen.
 *
 * Why this exists: Android doesn't natively render an IME on a secondary display
 * for regular (non-platform-signed) apps. So we host the system keyboard on the
 * **phone** in a big text field, and forward what's typed to the external display
 * as KeyEvents in real time.
 *
 * Behavior:
 * - Auto-focuses on launch, opening the user's default keyboard immediately
 * - LIVE typing: each character is forwarded as a [KeyEvent.KEYCODE_*] to the
 *   external display as you type; backspace as KEYCODE_DEL
 * - Tradeoff (accepted): injecting keys at the external display can make Android
 *   steal focus there and collapse this phone keyboard; the field re-asserts
 *   focus to recover. This is the cost of live typing vs. buffer-and-send.
 * - Live search also rides [InputInjector.setSearchQuery] (a StateFlow side
 *   channel, no focus impact) for PortalPad's own on-display search overlay
 * - The top-bar send icon flushes any pending text, injects a real ENTER, and
 *   closes the page
 * - Close button (or Back) dismisses
 *
 * Limitations of the relay model (honestly stated):
 * - Predictions / autocorrect happen on the phone, then get forwarded — fine
 *   for most use but may double-apply if the target also corrects
 * - Emoji and characters outside US-ASCII don't map to KeyEvent codes; they
 *   won't transmit. The text field will accept them but the external display
 *   won't see them
 * - Swipe typing works on the phone — the resulting word gets streamed as
 *   individual key events
 */
class KeyboardRelayActivity : PinnedDensityActivity() {

    private val app get() = application as PortalPadApp
    private val injector get() = app.injector
    private var launchedOnAndroidTab = false
    private var temporaryRelay = false
    private var restoreExternalRouteOnClose = false
    private var ownedPhoneImeCleanedUp = false
    private var relayImeSession = 0L
    private var nativeHandoffToken = 0L
    private var handoffCompletionScheduled = false
    @Volatile private var closing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lockOrientationDefault()
        // Prefill text comes from the accessibility detector (it reads the tapped
        // field's current text off the node). Empty when opened via the Shizuku
        // poller, which has no node to read from.
        val prefill = intent?.getStringExtra(EXTRA_PREFILL).orEmpty()
        // Caret/selection seed read off the field at open time by the detector
        // (-1 = absent/unusable → RelayScreen falls back to end-of-text).
        val selS = intent?.getIntExtra(EXTRA_CARET_START, -1) ?: -1
        val selE = intent?.getIntExtra(EXTRA_CARET_END, -1) ?: -1
        temporaryRelay = intent?.getBooleanExtra(EXTRA_TEMPORARY_RELAY, false) ?: false
        restoreExternalRouteOnClose =
            intent?.getBooleanExtra(EXTRA_RESTORE_EXTERNAL_ROUTE, false) ?: false
        relayImeSession = injector.beginRelayImeSession()
        // Which keyboard this page opens on, resolved SYNCHRONOUSLY here rather
        // than collected inside the composable.
        //
        // BUG THIS FIXES: collectAsState(initial = false) hands the first
        // composition a PLACEHOLDER before DataStore has read anything. A
        // LaunchedEffect seeding "once" off that placeholder always seeded
        // Custom, and its own already-seeded guard then discarded the real value
        // when it arrived a moment later — so "Android keyboard (with text box)"
        // landed on the Custom tab every single time.
        //
        // Only the "with text box" mode actually lands on the Android keyboard.
        // Custom, direct and external-display all land on Custom: the last two
        // have no page of their own, so arriving here is a detour (via the
        // keyboard button) and the sensible thing to hand the user is PortalPad's
        // own keyboard rather than whichever tab they last used months ago.
        val storedStartOnAndroid = runCatching {
            kotlinx.coroutines.runBlocking {
                val p = app.prefs
                val mode = com.portalpad.app.ui.common.KeyboardMode.of(
                    onExternal = p.bool(
                        com.portalpad.app.data.PreferencesRepository.Keys.IME_ON_EXTERNAL,
                        default = false,
                    ).first(),
                    autoOpen = p.bool(
                        com.portalpad.app.data.PreferencesRepository.Keys.AUTO_OPEN_RELAY_ON_FIELD,
                        default = true,
                    ).first(),
                    androidTab = p.bool(
                        com.portalpad.app.data.PreferencesRepository.Keys.RELAY_OPENS_ON_ANDROID_TAB,
                        default = false,
                    ).first(),
                    ppOnDisplay = p.bool(
                        com.portalpad.app.data.PreferencesRepository.Keys.PORTALPAD_KB_ON_DISPLAY,
                        default = false,
                    ).first(),
                    ppFloating = p.bool(
                        com.portalpad.app.data.PreferencesRepository.Keys.PORTALPAD_KB_FLOATING,
                        default = false,
                    ).first(),
                )
                mode == com.portalpad.app.ui.common.KeyboardMode.TEXT_BOX
            }
        }.getOrDefault(false)
        // The temporary fallback popup explicitly chooses which of the two
        // Relay-compatible keyboards to start with. Normal launches continue
        // to resolve solely from the saved preference.
        val startOnAndroid = if (intent?.hasExtra(EXTRA_START_ON_ANDROID) == true) {
            intent?.getBooleanExtra(EXTRA_START_ON_ANDROID, false) ?: false
        } else {
            storedStartOnAndroid
        }
        launchedOnAndroidTab = startOnAndroid
        setContent {
            PortalPadTheme {
                RelayScreen(
                    injector = injector,
                    initialText = prefill,
                    initialSelStart = selS,
                    initialSelEnd = selE,
                    startOnAndroid = startOnAndroid,
                    temporaryRelay = temporaryRelay,
                    canReclaimTextBoxFocus = {
                        !closing && !isFinishing && !isDestroyed &&
                            injector.isRelayImeSessionActive(relayImeSession)
                    },
                    onBeginClose = {
                        // The composable first removes its editor and cancels
                        // every local focus/show path. Mark the Activity closing
                        // at that same instant so lifecycle/window-focus work
                        // cannot reclaim the dying Text Box during its grace
                        // frame, then start the idempotent phone-IME cleanup.
                        closing = true
                        beginNativeHandoffIfNeeded()
                        cleanupOwnedPhoneIme("staged-compose-teardown")
                    },
                    onClose = { finish() },
                )
            }
        }
        // showBanner=false: the relay renders its own DisconnectBanner in-tree
        // (with a custom return action), so it must not also get the base overlay.
        finishWhenExternalDisplayGone(showBanner = false)
    }

    companion object {
        /** Optional launch extra: the tapped field's existing text, to prefill the
         *  relay box. Set only by the accessibility detector (never the poller). */
        const val EXTRA_PREFILL = "prefill_text"

        /** Optional launch extras: the tapped field's selection at open time,
         *  so the relay opens with its caret where the user actually clicked
         *  instead of end-of-text. -1 = unknown (fall back to end). */
        const val EXTRA_CARET_START = "caret_start"
        const val EXTRA_CARET_END = "caret_end"
        const val EXTRA_TEMPORARY_RELAY = "temporary_relay"
        const val EXTRA_START_ON_ANDROID = "start_on_android"
        const val EXTRA_RESTORE_EXTERNAL_ROUTE = "restore_external_route"
    }

    override fun onResume() {
        super.onResume()
        android.util.Log.w(
            "DIAG-RELAY",
            "onResume startOnAndroid=$launchedOnAndroidTab temporary=$temporaryRelay",
        )
        applyForcedOrientation()
        // Tell the auto-open poller the relay is up so it goes dormant and can't
        // re-trigger itself while we're typing.
        app.relayOpen = true
        // While the relay screen is up, the user types on the PHONE keyboard
        // and we forward keys to the external display. Enter relay IME mode:
        // this suppresses the per-key IME re-pin AND tells the VD to not show
        // an IME (policy=HIDE), which stops Chrome on the VD from requesting
        // IME placement that would otherwise collapse the phone keyboard
        // after each keystroke (HIDE_DISPLAY_IME_POLICY_HIDE in the trace).
        injector.resumeRelayImeSession(relayImeSession)
    }

    override fun onPause() {
        // Stamp BEFORE super/anything else: TrackpadActivity's onResume (which
        // runs refocusExternalDisplay) fires while this activity is merely
        // PAUSED — onDestroy comes ~250ms later, AFTER the refocus already ran.
        // Stamping only in onDestroy left the skip-window unarmed at the exact
        // moment it existed for (measured: refocus at +265ms after onPause,
        // destroy at +513ms — dropdown relaunch-killed with the skip asleep).
        (application as? PortalPadApp)?.relayClosedAt = android.os.SystemClock.elapsedRealtime()
        android.util.Log.w(
            "DIAG-RELAY",
            "onPause startOnAndroid=$launchedOnAndroidTab temporary=$temporaryRelay " +
                "restoreExternal=$restoreExternalRouteOnClose",
        )
        // The Text Box page owns a phone-side editor. A show request can finish
        // asynchronously after this Activity is already going away, leaving
        // HoneyBoard attached to a dead Relay InputConnection and poisoning the
        // next Direct session. End the local editor session before destruction.
        if (isFinishing) {
            closing = true
            beginNativeHandoffIfNeeded()
            cleanupOwnedPhoneIme("onPause-finishing")
        }
        // Release Relay's suppression now, while this page begins closing. Do
        // not change the external policy yet: the phone text field may still
        // own a live/stale InputConnection during onPause.
        if (closing || isFinishing) {
            beginNativeHandoffIfNeeded()
        } else {
            injector.pauseRelayImeSession(relayImeSession)
        }
        super.onPause()
    }

    override fun finish() {
        // finish() is the earliest common point for X, Back, and Send. Cleaning
        // here cancels a still-pending local IME show before the window teardown
        // begins; onPause is an idempotent fallback for system-driven finishes.
        closing = true
        beginNativeHandoffIfNeeded()
        cleanupOwnedPhoneIme("finish")
        super.finish()
    }

    @Suppress("DEPRECATION")
    private fun cleanupOwnedPhoneIme(reason: String) {
        // The Activity's initial-tab flag is not enough here: the user can open
        // Custom and switch to Text Box before closing. Cleanup is safe even if
        // no phone IME was used because it only targets this dying window.
        if (ownedPhoneImeCleanedUp) return
        ownedPhoneImeCleanedUp = true
        val decor = window.decorView
        val insets = androidx.core.view.ViewCompat.getRootWindowInsets(decor)
        val visible = insets?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
        val bottom = insets
            ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())
            ?.bottom ?: 0

        // Remove the editor focus first so a queued Compose show() no longer has
        // a valid target, then ask both modern and legacy controllers to hide.
        currentFocus?.clearFocus()
        decor.clearFocus()
        window.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
        )
        androidx.core.view.WindowInsetsControllerCompat(window, decor)
            .hide(androidx.core.view.WindowInsetsCompat.Type.ime())
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as?
            android.view.inputmethod.InputMethodManager
        val legacyHide = runCatching {
            imm?.hideSoftInputFromWindow(decor.windowToken, 0) ?: false
        }.getOrDefault(false)
        // The closing Activity must not become an editor/control target again
        // during its exit transition. The log showed HoneyBoard starting one
        // final input session for PortalPad after focus had already been cleared.
        window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
        )
        android.util.Log.i(
            "DIAG-RELAY",
            "text box phone IME cleanup reason=$reason visible=$visible " +
                "bottom=$bottom legacyHide=$legacyHide",
        )
    }

    private fun beginNativeHandoffIfNeeded(): Long {
        if (nativeHandoffToken == 0L) {
            nativeHandoffToken = injector.beginRelayImeTeardown(
                session = relayImeSession,
                restoreExternal = restoreExternalRouteOnClose,
            )
        }
        return nativeHandoffToken
    }

    private fun completeNativeHandoffAfterWindowDetach(handoffToken: Long) {
        if (handoffToken == 0L || handoffCompletionScheduled) return
        handoffCompletionScheduled = true
        val decor = window.decorView
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var completed = false
        lateinit var listener: android.view.View.OnAttachStateChangeListener
        fun complete(forced: Boolean) {
            if (completed) return
            completed = true
            runCatching { decor.removeOnAttachStateChangeListener(listener) }
            android.util.Log.i(
                "DIAG-RELAY",
                "relay window detached handoff=$handoffToken forced=$forced",
            )
            injector.markRelayWindowDetached(handoffToken)
        }
        listener = object : android.view.View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: android.view.View) = Unit
            override fun onViewDetachedFromWindow(v: android.view.View) = complete(forced = false)
        }
        if (decor.isAttachedToWindow) {
            decor.addOnAttachStateChangeListener(listener)
        } else {
            complete(forced = false)
            return
        }
        // Defensive lifecycle fallback. Normal completion is the exact detach
        // callback a few milliseconds after onDestroy; this only prevents an
        // OEM callback omission from stranding the user's selected route.
        handler.postDelayed({ complete(forced = true) }, 1200L)
    }

    override fun onDestroy() {
        closing = true
        android.util.Log.w("DIAG-RELAY", "onDestroy startOnAndroid=$launchedOnAndroidTab")
        // Relay is closing (back button or the X). Re-enable the auto-open poller
        // so the next field tap can open it again.
        app.relayOpen = false
        app.relayClosedAt = android.os.SystemClock.elapsedRealtime()
        // Register before super so the exact View detachment cannot race past
        // us. Route restoration is coordinated with Trackpad's phone-host clear;
        // no focus, click, show, or field replay is performed here.
        val handoffToken = beginNativeHandoffIfNeeded()
        completeNativeHandoffAfterWindowDetach(handoffToken)
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RelayScreen(
    injector: InputInjector,
    initialText: String = "",
    initialSelStart: Int = -1,
    initialSelEnd: Int = -1,
    startOnAndroid: Boolean = false,
    temporaryRelay: Boolean = false,
    canReclaimTextBoxFocus: () -> Boolean,
    onBeginClose: () -> Unit,
    onClose: () -> Unit,
) {
    // Seed with the tapped field's existing text (prefill). Cursor at end → append
    // mode. Because onValueChange forwards only the DIFF vs. this baseline, the
    // prefilled text is NOT re-injected into the field — only new keystrokes are.
    // Showing the real text is what makes the keyboard's backspace work: there's
    // something in the box to delete. Empty fields open blank.
    var fieldValue by remember {
        // Seed the caret/selection from the open-time read when valid; a full
        // select-all carries through (first keystroke replaces, platform
        // convention). Anything unusable → end-of-text, the old behavior.
        val sel = if (initialSelStart in 0..initialText.length &&
            initialSelEnd in initialSelStart..initialText.length
        ) TextRange(initialSelStart, initialSelEnd) else TextRange(initialText.length)
        mutableStateOf(TextFieldValue(initialText, selection = sel))
    }
    val focusRequester = remember { FocusRequester() }
    @OptIn(ExperimentalComposeUiApi::class)
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val relayScope = rememberCoroutineScope()
    val relayView = LocalView.current

    // Input mode. Custom = our own on-screen keyboard (drives the field via
    // accessibility SET_TEXT/SET_SELECTION — reliable backspace + caret, no system
    // IME to fight). Android = the phone's keyboard typing into a local box that
    // is flushed to the external field on a typing pause (NOT keystroke
    // forwarding — that path is dead; see FORWARD_LIVE_KEYSTROKES).
    // Landing keyboard comes from the Settings choice (resolved synchronously in
    // onCreate — see the note there), and changing it here writes it back, so the
    // setting always describes what you actually get, from either direction.
    var customKeyboard by remember { mutableStateOf(!startOnAndroid) }
    val textBoxActive = rememberUpdatedState(!customKeyboard)
    var reclaimTextBoxFocusOnReturn by remember { mutableStateOf(false) }
    var relayClosing by remember { mutableStateOf(false) }
    // Bumped by the top-bar send icon. Each tab owns what "send" means for it
    // (the Android tab must flush its pending text FIRST or ENTER would submit
    // the external field's stale contents), so the icon just raises the request
    // and the active tab fulfils it.
    var sendNonce by remember { mutableStateOf(0) }

    // Close in two stages. The log showed HoneyBoard being re-shown on display
    // 0 after onDestroy and binding back to KeyboardRelayActivity's already-dead
    // InputConnection. Removing the Compose editor first makes Android finish
    // that connection while the window is still alive. Only after a frame and
    // a short IME-detach grace period do we finish the Activity. This is purely
    // local teardown: it never focuses, taps, retries, or shows an IME.
    val requestClose: () -> Unit = {
        if (!relayClosing) {
            val hadTextBoxEditor = !customKeyboard
            relayClosing = true
            reclaimTextBoxFocusOnReturn = false
            android.util.Log.i(
                "DIAG-RELAY",
                "staged close begin textBoxEditor=$hadTextBoxEditor",
            )
            runCatching { keyboardController?.hide() }
            runCatching { focusManager.clearFocus(force = true) }
            runCatching { relayView.clearFocus() }
            onBeginClose()
            relayScope.launch {
                // relayClosing removes the editor branch on this frame. Its
                // DisposableEffect flushes pending text before disposal.
                androidx.compose.runtime.withFrameNanos { }
                kotlinx.coroutines.delay(if (hadTextBoxEditor) 350L else 40L)
                android.util.Log.i(
                    "DIAG-RELAY",
                    "staged close editor detached; committing activity finish",
                )
                onClose()
            }
        }
    }

    // ComponentActivity's default Back would call finish() immediately and
    // bypass the editor-detach stage. Consume Back here and use the same path
    // as X, Send, suggestion pick, and mode changes. A second Back during the
    // short grace period is intentionally consumed rather than cutting it off.
    androidx.activity.compose.BackHandler { requestClose() }

    // Re-open the phone keyboard whenever the user TAPS the box. The box keeps
    // focus the whole time the relay is up (we requested it on open and the
    // reasserts hold it), so tapping it again normally produces NO focus change —
    // which means Compose won't re-show a keyboard the user dismissed (back/swipe)
    // or that collapsed. Observing tap "release" interactions lets us force the
    // keyboard back up on every tap without consuming the touch (so cursor
    // placement still works). Typing then forwards to the external display as usual.
    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    LaunchedEffect(interactionSource, customKeyboard, relayClosing) {
        if (customKeyboard || relayClosing) return@LaunchedEffect
        interactionSource.interactions.collect { interaction ->
            if (!relayClosing &&
                interaction is androidx.compose.foundation.interaction.PressInteraction.Release
            ) {
                // This is a real, local user tap. Ask once for this field's
                // keyboard; do not clear focus, inject a tap, or schedule a
                // retry. Those recovery actions competed with Samsung's own
                // IME negotiation and produced a show/cancel loop.
                android.util.Log.i("PortalPadRelay", "manual field tap -> focus + show")
                runCatching { focusRequester.requestFocus() }
                runCatching { keyboardController?.show() }
            }
        }
    }

    LaunchedEffect(customKeyboard, relayClosing) {
        // Pop the keyboard so the user can start typing. Wait a beat so the
        // text field node is actually attached/positioned before requesting
        // focus — requesting too early throws "FocusRequester is not
        // initialized", and because this runs on the main looper that
        // exception is FATAL and takes the whole PortalPad process down (which
        // is what made tapping Search drop the user back to the launcher).
        // The runCatching guarantees it can never crash the process again.
        if (!customKeyboard && !relayClosing) {
            kotlinx.coroutines.delay(120)
            if (!relayClosing) {
                runCatching { focusRequester.requestFocus() }
                runCatching { keyboardController?.show() }
            }
        }
        // Start from a clean query each time the relay opens.
        injector.setSearchQuery("")
    }

    // The external display can briefly become Android's focused display while
    // this page remains visible. On Samsung that drops the local TextField's
    // InputConnection; when the phone returns to Relay, HoneyBoard can be left
    // attached to the activity's non-editor window (inputType=0), so keys reach
    // neither this box nor the external field. Reclaim the actual TextField once
    // per real window-focus return. This is local-only and intentionally has no
    // retry loop, external focus action, or injected input.
    DisposableEffect(relayView) {
        val listener = android.view.ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
            if (!hasFocus) {
                if (!relayClosing && textBoxActive.value && canReclaimTextBoxFocus()) {
                    reclaimTextBoxFocusOnReturn = true
                }
            } else if (
                !relayClosing &&
                reclaimTextBoxFocusOnReturn &&
                textBoxActive.value &&
                canReclaimTextBoxFocus()
            ) {
                reclaimTextBoxFocusOnReturn = false
                relayScope.launch {
                    kotlinx.coroutines.delay(80)
                    if (
                        !relayClosing &&
                        textBoxActive.value &&
                        relayView.hasWindowFocus() &&
                        canReclaimTextBoxFocus()
                    ) {
                        android.util.Log.i("PortalPadRelay", "text box reclaim after window-focus return")
                        // Restore the editor as the focused local view, but do
                        // not force-show the IME. Samsung treats a show during a
                        // returning window-focus transition as a new request and
                        // may restart or cancel the active input connection.
                        runCatching { focusRequester.requestFocus() }
                    } else {
                        android.util.Log.i(
                            "PortalPadRelay",
                            "text box reclaim cancelled: relay closing or focus unavailable",
                        )
                    }
                }
            } else if (reclaimTextBoxFocusOnReturn && textBoxActive.value) {
                reclaimTextBoxFocusOnReturn = false
                android.util.Log.i(
                    "PortalPadRelay",
                    "text box reclaim cancelled: relay is closing",
                )
            }
        }
        relayView.viewTreeObserver.addOnWindowFocusChangeListener(listener)
        onDispose {
            if (relayView.viewTreeObserver.isAlive) {
                relayView.viewTreeObserver.removeOnWindowFocusChangeListener(listener)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Type to external display", color = PhoneOnSurface) },
                navigationIcon = {
                    IconButton(onClick = requestClose) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = PhoneOnSurface)
                    }
                },
                actions = {
                    // Sends what you typed, then closes. It was previously wired to
                    // onClose — the same handler as the X on the left — so the page
                    // had two buttons doing one thing, one of them drawn as a paper
                    // plane that never sent anything.
                    IconButton(onClick = { sendNonce++ }) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = PhoneAccent)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PhoneBackground),
            )
        },
        containerColor = PhoneBackground,
    ) { padding ->
      var showSuggestions by remember { mutableStateOf(false) }
      var overlaySuggestions by remember {
          mutableStateOf(listOf<com.portalpad.app.service.PortalPadAccessibilityService.MirrorSuggestion>())
      }
      // Scope for the pick fallback (SET_TEXT → brief settle → Enter).
      val suggestionScope = rememberCoroutineScope()
      // When the popup closes, restore field focus (the reassert watcher stood
      // down while it was open). Guarded so it doesn't fire on first composition.
      var popupWasOpen by remember { mutableStateOf(false) }
      LaunchedEffect(showSuggestions) {
          if (!relayClosing && popupWasOpen && !showSuggestions) {
              kotlinx.coroutines.delay(60)
              if (!relayClosing) runCatching { focusRequester.requestFocus() }
          }
          popupWasOpen = showSuggestions
      }
      Box(Modifier.fillMaxSize().padding(padding)) {
        Column(
            Modifier
                // Tighter top than sides (SH): the 16dp all-round left a big gap
                // under the title and between every section. Sides stay 16; the
                // vertical rhythm drops to 8 so the header, picker, and keyboard
                // pack close and the freed height flows into the weight(1f)
                // keyboard below. Covers both the PortalPad and Android
                // with-text-box relays (one RelayScreen).
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Same disconnect banner as the trackpad interface — the relay
            // covers TrackpadActivity, so its banner fires unseen underneath.
            // Keyed to the PHYSICAL id (instant at unplug); under the grace
            // model the app being typed into stays ALIVE, so this is
            // actionable ("replug and continue"), and the relay deliberately
            // does NOT auto-finish: the draft text survives the grace window.
            val physId by com.portalpad.app.PortalPadApp.instance
                .physicalExternalDisplayId.collectAsState()
            com.portalpad.app.ui.common.DisconnectBanner(
                externalDisplayId = physId,
                onReturnNow = requestClose,
            )
            RelayDependencyChip(customKeyboard)

            // ── Suggestion mirror (overlay dropdown) ───────────────────
            // The on-display dropdown is UNSELECTABLE from the phone: any relay
            // touch kills it (system IME arbitration), and DPAD selection is
            // cleared by Chrome's fill-in/rebuild loop. So the accessibility
            // service READS the suggestions off the display's node tree; here
            // they surface as a compact trigger row that opens a floating,
            // scrollable popup OVER the keyboard (nothing below reflows). A tap
            // clicks the real suggestion via ACTION_CLICK on its node — no focus
            // events, no key injection. Invisible when nothing is readable
            // (fallback by design: Chrome's view ids can shift across versions).
            var suggestions by remember {
                mutableStateOf(listOf<com.portalpad.app.service.PortalPadAccessibilityService.MirrorSuggestion>())
            }
            LaunchedEffect(Unit) {
                // STICKY + BURST reliability (field report 2026-07-09: chips
                // appeared intermittently — a single empty read mid-dropdown-
                // rebuild blanked the list even when the next poll would have
                // refilled it, and the fixed 600ms cadence aliased against apps
                // that populate their dropdown a few hundred ms AFTER each
                // keystroke).
                //  - STICKY: a non-empty chip set is held through empty reads
                //    while the query is unchanged; it clears only after 4
                //    consecutive empties (the dropdown is genuinely gone).
                //    On a query change the old chips hold briefly too — the
                //    burst below replaces them within ~250ms, which reads as
                //    progressive refinement rather than flicker.
                //  - BURST: the first 5 reads after each query change run at
                //    250ms, then the loop settles back to 600ms.
                var lastQuery = ""
                var emptyStreak = 0
                var sinceChange = 0
                // Seed the shared query flow with THIS session's starting text:
                // it's a process-wide StateFlow, so without seeding, a stale
                // query from a previous relay session (another app entirely)
                // leaks into the first scans until the first keystroke.
                injector.setSearchQuery(fieldValue.text)
                while (true) {
                    // Query source follows the ACTIVE keyboard path. The old
                    // "fieldValue, else custom buffer" precedence served the
                    // PREFILL forever on the custom path whenever the tapped
                    // field opened with leftover text (DIAG 2026-07-09: every
                    // scan said q='goo' while the user typed 'boss bab' — the
                    // relevance gate then rejected perfectly good rows).
                    val typed = if (customKeyboard) {
                        injector.searchQuery.value.trim()
                    } else {
                        fieldValue.text.trim()
                    }
                    if (typed != lastQuery) {
                        lastQuery = typed
                        sinceChange = 0
                        emptyStreak = 0
                    } else {
                        sinceChange++
                    }
                    val raw = runCatching {
                        com.portalpad.app.service.PortalPadAccessibilityService.instance
                            ?.readSuggestions(injector.displayId, query = typed)
                    }.getOrNull() ?: emptyList()
                    // Filter Chrome's verbatim echo of the typed text (Send Enter
                    // already covers "search exactly this") and trim the trailing
                    // two-line separator dot that bleeds into line_1.
                    val processed = raw
                        .map { it.copy(title = it.title.trimEnd(' ', '\u00B7', '\u2013', '\u2014').trim()) }
                        .filter { it.title.isNotBlank() && !it.title.equals(typed, ignoreCase = true) }
                        .distinctBy { it.title }
                    // BROWSERS keep their NATIVE order (and full list): the
                    // engine's ranking encodes history/popularity/personal
                    // signals that ABC would destroy, and its list is already
                    // all-relevant by its own judgment so nothing is pruned.
                    // Everything else (streaming tiles, Play Store, unknown
                    // apps) gets word-prefix tiers + ABC within each tier, with
                    // non-matches dropped once 3+ real matches exist.
                    val srcPkg = com.portalpad.app.service.PortalPadAccessibilityService
                        .instance?.lastSuggestionSourcePkg
                    val isBrowser = com.portalpad.app.service.PortalPadAccessibilityService
                        .isBrowserPkg(srcPkg)
                    val ranked = if (isBrowser) processed
                    else com.portalpad.app.ui.common.SearchRank.rank(processed, typed) { it.title }
                    if (ranked.isNotEmpty()) {
                        emptyStreak = 0
                        suggestions = ranked
                    } else {
                        emptyStreak++
                        if (emptyStreak >= 4) suggestions = emptyList()
                    }
                    kotlinx.coroutines.delay(if (sinceChange < 5) 250L else 600L)
                }
            }
            // NOTE deliberately NO live `overlaySuggestions = suggestions` here.
            // The popup shows a SNAPSHOT taken when the trigger is tapped: the
            // trigger tap itself kills Chrome's real dropdown (IMMS arbitration —
            // the proven dropdown3.txt floor), so the next poll reads EMPTY and a
            // live-bound popup drains and vanishes within one tick ("list appears
            // and cancels quickly"). The snapshot keeps the popup stable; the live
            // list only controls the trigger row's visibility.

            // The info box yields to typing (SH, 2026-07-23): once there is ANY
            // text in the buffer the explainer has done its job, and the space it
            // frees is what keeps the text field full-size when the suggestions
            // row appears - the field was shrinking "way too much" (screenshots).
            // Gated on buffer-non-empty rather than suggestions-non-empty on
            // purpose: suggestions come and go BETWEEN words mid-typing, and a
            // box popping in and out with them would bounce the whole layout.
            // The buffer source follows the active tab, same as the suggestion
            // poll above.
            val liveQuery by injector.searchQuery.collectAsState()
            val typingNow = if (customKeyboard) liveQuery.isNotEmpty()
                else fieldValue.text.isNotEmpty()
            if (!typingNow) Surface(
                color = PhoneSurface,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        if (customKeyboard) {
                            "Keystrokes appear on the external display as you type."
                        } else {
                            "Typed text is sent to the external display when you pause."
                        },
                        color = PhoneAccent, style = MaterialTheme.typography.bodyMedium,
                    )
                    // Describes ONLY the mode you're in, using the same words
                    // Settings uses for it.
                    com.portalpad.app.ui.common.KeyboardModeNotes(
                        if (customKeyboard) {
                            com.portalpad.app.ui.common.KeyboardMode.CUSTOM
                        } else {
                            com.portalpad.app.ui.common.KeyboardMode.TEXT_BOX
                        },
                        // The band already carries the reliability point; the page
                        // is single-family, so the comparison line is redundant.
                        showReliability = false,
                        // And the description explains how a field tap gets you
                        // HERE - noise to someone already on the page (SH).
                        showDescription = false,
                    )
                }
            }

            // PICKER REINSTATED (SH, 2026-07-23) - PortalPad family only, and the
            // old removal's objection is answered rather than ignored: a picker was
            // removed here because switching to a page-less mode closed the page
            // you stood on and stranded you. Now a pick that leaves this page also
            // RAISES the replacement keyboard in the same gesture, so the switch
            // reads as the keyboard transforming, not as being dumped. Persisted
            // like the Settings picker (this is a real preference change).
            // Hidden on a temporary relay, which must never rewrite the stored
            // preference that opened it.
            // Known limitation, accepted: text already typed lives in the external
            // field, but the floating keyboard starts with an empty buffer, so its
            // backspace does not know that text until Build 2's field-tap seeding.
            if (!temporaryRelay) {
                // No wrapping spacers: the column's spacedBy(8) already gives the
                // picker its gap on both sides. (Earlier explicit spacers here just
                // stacked ON TOP of spacedBy and were the real cause of the big
                // picker gaps - SH screenshot.)
                com.portalpad.app.ui.common.KeyboardModeDropdown(
                    modifier = Modifier.padding(vertical = 4.dp),
                    // Attached-down on BOTH tabs (SH revised). The height cap
                    // ALONE lost on the Android tab: the IME-halved window was so
                    // short even 260dp didn't fit below the picker and the menu
                    // still flipped upward (SH verify). pinMenuBelowAnchor renders
                    // a custom popup that physically cannot flip (and hides the
                    // system keyboard on open); the cap becomes its scroll limit.
                    menuMaxHeight = 260.dp,
                    pinMenuBelowAnchor = true,
                    availableModes = listOf(
                        com.portalpad.app.ui.common.KeyboardMode.FLOATING,
                        com.portalpad.app.ui.common.KeyboardMode.CUSTOM,
                        com.portalpad.app.ui.common.KeyboardMode.ON_DISPLAY,
                    ),
                    onPicked = { picked ->
                        when (picked) {
                            com.portalpad.app.ui.common.KeyboardMode.FLOATING -> {
                                com.portalpad.app.presentation.FloatingKeyboardOverlay
                                    .show(com.portalpad.app.PortalPadApp.instance)
                                requestClose()
                            }
                            com.portalpad.app.ui.common.KeyboardMode.ON_DISPLAY -> {
                                val papp = com.portalpad.app.PortalPadApp.instance
                                val disp = papp.virtualDisplayId ?: papp.externalDisplayId.value
                                if (disp == null) {
                                    // Pref is written (same as picking it in Settings
                                    // with no display); stay here so typing continues.
                                    android.widget.Toast.makeText(
                                        papp, "Connect the external display first",
                                        android.widget.Toast.LENGTH_SHORT,
                                    ).show()
                                } else {
                                    com.portalpad.app.presentation.ExternalKeyboardProbe
                                        .show(papp, disp)
                                    requestClose()
                                }
                            }
                            // CUSTOM = this page; nothing to do.
                        }
                    },
                )
            }

            // Suggestion trigger — directly above the input area, below the
            // keyboard-mode tabs. Opens the floating popup (declared later).
            if (suggestions.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            // Snapshot NOW — this very tap dismisses Chrome's real
                            // dropdown, so the poll goes empty right after. The
                            // popup must not depend on the live list.
                            overlaySuggestions = suggestions
                            showSuggestions = true
                        }
                        .background(PhoneSurfaceElevated)
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("\u2605", color = PhonePrimaryBright, fontSize = 13.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${suggestions.size} suggestion${if (suggestions.size == 1) "" else "s"}",
                        color = PhoneOnSurface, modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text("\u25BE", color = PhoneOnSurfaceMuted, fontSize = 12.sp)
                }
            }
            // No `else { showSuggestions = false }`: the live list going empty is
            // EXPECTED the instant the popup opens (trigger tap → Chrome dropdown
            // dies → poll drains). It hides the trigger row above, but the open
            // popup keeps its snapshot; only the scrim tap or a pick closes it.

            if (relayClosing) {
                // Removing both editor branches for at least one frame is the
                // critical part of the staged shutdown: Compose disposes the
                // Text Box InputConnection before Activity destruction.
                Spacer(Modifier.weight(1f).fillMaxWidth())
            } else if (customKeyboard) {
                // Top-bar send, custom tab: this keyboard types live, so the
                // external field already holds the text — nothing to flush.
                LaunchedEffect(sendNonce) {
                    if (sendNonce > 0) {
                        injector.submitSearch()
                        injector.pressKey(KeyEvent.KEYCODE_ENTER)
                        requestClose()
                    }
                }
                // Size the keyboard to the space left below the toggle so it fits
                // without scrolling on any screen. Keys and the mirror field shrink on
                // short screens and cap out on tall ones (where the bottom-anchored
                // arrangement leaves empty space above — thumbs stay low either way).
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    // Keys derive from the SCREEN height, not the remaining space:
                    // maxHeight shrinks when the suggestions row appears, and the
                    // first elastic build sized keys from it - so keys still dipped
                    // slightly with suggestions up (SH). Screen height is constant,
                    // so keys are now truly fixed; only the mirror breathes. The
                    // /16 fraction lands at the 50dp cap on any normal phone and
                    // only bites on very short screens.
                    val keyH = (
                        androidx.compose.ui.platform.LocalConfiguration.current
                            .screenHeightDp.dp / 16
                        ).coerceIn(36.dp, 50.dp)
                    CompositionLocalProvider(LocalKbKeyHeight provides keyH) {
                        Column(Modifier.fillMaxSize()) {
                            // fieldValue is the SINGLE cross-tab truth: the
                            // custom section seeds from it on entry and reports
                            // every buffer/caret change back into it, so
                            // switching tabs carries text AND caret both ways.
                            // (Before this, the section's remember{} state was
                            // DISPOSED on switch — custom edits vanished and
                            // returning reset to the original prefill.)
                            CustomKeyboardSection(
                                injector,
                                fieldValue.text,
                                fieldValue.selection.end.coerceIn(0, fieldValue.text.length),
                                // Carried selection (open-time select-all, or one
                                // made on the Android tab) seeds the anchor so the
                                // custom keyboard shows the same selection.
                                initialAnchor = if (fieldValue.selection.min != fieldValue.selection.max) {
                                    fieldValue.selection.min.coerceIn(0, fieldValue.text.length)
                                } else -1,
                            ) { t, c ->
                                fieldValue = TextFieldValue(t, selection = TextRange(c))
                            }
                        }
                    }
                }
            } else {

            // ── Debounced external sync (Android path) ─────────────────────
            // Local edits are instant; the a11y write to the TARGET field is
            // debounced (one push ~450ms after the burst ends). Every write
            // migrates the IME target to the external field, whose display
            // policy is HIDE — per-keystroke writes made the phone keyboard
            // dip on EVERY backspace (field). One push per burst = at most one
            // dip per burst, and the proactive re-claim below often beats the
            // delayed hide entirely. lastSyncedText is what the external field
            // actually holds, so the keystroke fallback (no a11y node) can
            // diff against reality.
            var pendingSyncText by remember { mutableStateOf<String?>(null) }
            var pendingSyncCaret by remember { mutableIntStateOf(0) }
            var syncJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
            // Uptime of the last flush attempt (diagnostic stamp).
            var lastFlushAt by remember { mutableLongStateOf(0L) }
            // Uptime of the last TEXT edit — distinguishes typing-residue
            // caret moves (suppressed: no write, no migration) from deliberate
            // caret repositioning (synced).
            var lastTextEditAt by remember { mutableLongStateOf(0L) }
            // Pause threshold before the external write fires (Settings →
            // keyboard section). Shorter = snappier external sync; longer =
            // fewer mid-thought flushes = fewer chances to drop a keystroke.
            val pauseFlushMs by com.portalpad.app.PortalPadApp.instance.prefs
                .int(com.portalpad.app.data.PreferencesRepository.Keys.RELAY_PAUSE_FLUSH_MS, default = 600)
                .collectAsState(initial = 600)
            var lastSyncedText by remember { mutableStateOf(fieldValue.text) }
            // Where the EXTERNAL field's caret is, as far as we know: seeded
            // from the open/tab-entry state, updated by our pushes, keystroke
            // appends/deletes, and the caret back-sync. Lets caret-anchored
            // deletions go out as DEL keystrokes (no a11y write, no IME hide).
            var lastSyncedCaret by remember {
                mutableIntStateOf(fieldValue.selection.end.coerceIn(0, fieldValue.text.length))
            }
            val flushSync: () -> Unit = flush@{
                syncJob?.cancel()
                lastFlushAt = android.os.SystemClock.uptimeMillis()
                val t = pendingSyncText ?: return@flush
                pendingSyncText = null
                val caret = pendingSyncCaret
                val svc = PortalPadAccessibilityService.instance
                if (t == lastSyncedText) {
                    // TYPING-RESIDUE caret-only change: SKIP the write
                    // entirely. A SET_SELECTION migrates the IME target
                    // exactly like SET_TEXT — a whole focus war for a caret
                    // nudge the next text write will carry anyway. Only
                    // deliberate caret moves (no text edit in the last 1.5s)
                    // are worth a migration.
                    if (android.os.SystemClock.uptimeMillis() - lastTextEditAt < 1500) {
                        lastSyncedCaret = caret
                        return@flush
                    }
                    // Deliberate selection-only move: sync it. The
                    // SET_SELECTION still migrates focus/IME target (the
                    // 17:31:16.056 migration was a bare
                    // performAccessibilityAction). Do not try to reclaim the
                    // IME here: recovery taps and focus toggles made Samsung's
                    // IME show/cancel continuously.
                    runCatching { svc?.setFieldSelection(caret, caret) }
                    lastSyncedCaret = caret
                    return@flush
                }
                val ok = runCatching { svc?.setFieldText(t) == true }.getOrDefault(false)
                if (ok) {
                    runCatching { svc?.setFieldSelection(caret, caret) }
                    lastSyncedCaret = caret
                    // The external write may change Android's input target.
                    // Leave the following IME decision to the system until the
                    // user taps the relay field again; repeated focus/tap/show
                    // recovery here was the source of the keyboard flicker.
                } else {
                    // No a11y target (poller-opened relay): legacy keystrokes,
                    // diffed against what the external field ACTUALLY holds.
                    val base = lastSyncedText
                    val common = base.commonPrefixWith(t).length
                    repeat(base.length - common) { injector.pressKey(KeyEvent.KEYCODE_DEL) }
                    t.substring(common).forEach { forwardCharacter(it, injector) }
                }
                lastSyncedText = t
            }
            // Leaving the tab (switch or relay close) flushes any pending push
            // so the external field can't be left stale.
            DisposableEffect(Unit) {
                onDispose {
                    val hadPendingText = pendingSyncText != null
                    val flushOk = runCatching { flushSync() }.isSuccess
                    android.util.Log.i(
                        "DIAG-RELAY",
                        "text box editor disposed closing=$relayClosing " +
                            "pendingText=$hadPendingText flushOk=$flushOk",
                    )
                }
            }
            // Top-bar send, Android tab: the pending debounced write MUST go out
            // before ENTER, or the external field submits its stale text.
            LaunchedEffect(sendNonce) {
                if (sendNonce > 0) {
                    runCatching { flushSync() }
                    injector.submitSearch()
                    injector.pressKey(KeyEvent.KEYCODE_ENTER)
                    requestClose()
                }
            }
            // EXTERNAL→RELAY caret sync: while on the Android path, the target
            // field's selection changes (published by the a11y back-sync) move
            // this field's caret to match — clicking in the glasses field now
            // updates the typing indicator here, like the custom keyboard's
            // model. Clamped to the current text; no-ops when already equal, so
            // it can't loop with the outbound push above.
            LaunchedEffect(Unit) {
                com.portalpad.app.service.PortalPadAccessibilityService
                    .relayFieldSelection.collect { sel ->
                        // A selection notification can be the echo of our own
                        // debounced write. Replacing the local TextFieldValue
                        // while Samsung is composing (or during a live typing
                        // burst) invalidates its composing span and can restart
                        // the local InputConnection. Keep the local editor in
                        // charge until the user pauses; a later deliberate
                        // external tap will publish a fresh selection.
                        val now = android.os.SystemClock.uptimeMillis()
                        val composing = fieldValue.composition != null
                        val typing = now - lastTextEditAt < 1500L
                        if (composing || typing) {
                            android.util.Log.i(
                                "PortalPadRelay",
                                "DIAG-CARETSYNC skip(android) sel=${sel.first}..${sel.second} composing=$composing typing=$typing",
                            )
                        } else {
                            val len = fieldValue.text.length
                            val a = sel.first.coerceIn(0, len)
                            val b = sel.second.coerceIn(0, len)
                            android.util.Log.i("PortalPadRelay",
                                "DIAG-CARETSYNC apply(android) sel=${sel.first}..${sel.second} → $a..$b (was ${fieldValue.selection.min}..${fieldValue.selection.max}, len=$len)")
                            lastSyncedCaret = b
                            if (fieldValue.selection.min != a || fieldValue.selection.max != b) {
                                fieldValue = fieldValue.copy(
                                    selection = androidx.compose.ui.text.TextRange(a, b),
                                )
                            }
                        }
                    }
            }
            // FIELD FILLS THE LEFTOVER (SH): the window already ends at the top
            // of the Android keyboard (adjustResize), so weight(1f) stretches
            // the field down to the column's 16dp bottom padding - the "bit of
            // gap" above the keyboard - without measuring the keyboard and
            // without shrinking the fixed rows above (they measure first; the
            // field takes only what is left). Previously heightIn(min) only:
            // freed space (hidden info box) pooled BELOW the field as dead gap.
            // SCROLLBAR: the field grows to its content inside this scroll
            // container (min = viewport so the border always fills the area);
            // overflow scrolls the container, and the thumb - same treatment as
            // the mini dropdowns - appears only when there is overflow. The
            // container scrolls rather than the field internally because
            // OutlinedTextField does not expose its internal scroll state to
            // draw a thumb from; the cursor still stays visible (text fields
            // publish the cursor rect to the enclosing scrollable).
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val fieldViewportH = maxHeight
            val fieldScroll = rememberScrollState()
            Column(Modifier.fillMaxSize().verticalScroll(fieldScroll)) {
            OutlinedTextField(
                // Keep the editor genuinely empty when it is empty. A former
                // zero-width sentinel made backspace observable for a haptic
                // cue, but it changed the text and selection Samsung's IME saw
                // at the exact empty-field boundary. That caused InputConnection
                // restart/unbind cycles, after which typed keys reached neither
                // this field nor the external display.
                value = fieldValue,
                onValueChange = { newValue ->
                    // LIVE typing on the ANDROID-keyboard path. Pure ASCII
                    // APPENDS forward as key events (live, cheap, works even
                    // without an a11y target). Everything else — any deletion,
                    // an autocorrect REWRITE (the old code forwarded the new
                    // suffix without deleting the divergent tail: teh→the
                    // desynced the target), emoji / non-ASCII (unmappable to
                    // key codes) — goes through the SAME accessibility
                    // SET_TEXT mechanism the custom keyboard uses, which is
                    // deterministic. If no a11y target exists (relay opened by
                    // the Shizuku poller, which has no node), fall back to the
                    // legacy best-effort keystrokes.
                    val newText = newValue.text
                    val oldText = fieldValue.text
                    // FALLBACK-TAP ECHO: inside the guard window, a
                    // selection-only change is the recovery tap's own caret
                    // jump — keep the user's caret, schedule NOTHING (see
                    // suppressTapEchoUntil's declaration for the oscillation
                    // this prevents).
                    // "Field just cleared" cue: the last observable moment on
                    // the Android path (an IME backspace on an ALREADY-empty
                    // field produces no event we can see — the key listener
                    // below catches it only on IMEs that send key events).
                    if (oldText.isNotEmpty() && newText.isEmpty()) {
                        injector.buzz()
                        relayScope.launch {
                            kotlinx.coroutines.delay(110)
                            injector.buzz()
                        }
                    }
                    val pureAppend = newText.length > oldText.length && newText.startsWith(oldText)
                    val appended = if (pureAppend) newText.substring(oldText.length) else ""
                    // Appends stream live as keystrokes ONLY while no debounced
                    // push is pending — keystroking over a not-yet-synced
                    // deletion would type onto STALE text.
                    // GATED OFF — see FORWARD_LIVE_KEYSTROKES (bottom of file):
                    // injected keys on the external display kill this window's
                    // InputConnection via display-focus migration.
                    val asciiAppend = FORWARD_LIVE_KEYSTROKES &&
                        pureAppend && pendingSyncText == null &&
                        appended.all { it.code in 0x20..0x7E || it == '\n' }
                    // PURE END-TRUNCATION (the hold-backspace case): a prefix
                    // of the synced text with a collapsed caret at its end goes
                    // out as plain DEL keystrokes — keystrokes never touch the
                    // external field's input focus, so there is NO IME-target
                    // migration and NO policy-hide, ever (field: an 11-wave
                    // hide storm in 700ms outlasted every recovery scheme —
                    // prevention is the only fix that meets "the keyboard
                    // should never go away"). Requires the external field to be
                    // known-synced (lastSyncedText == oldText, nothing
                    // pending); anything murkier takes the debounced SET_TEXT
                    // path, which also self-heals any drift on its next write.
                    // Deletion of exactly the chars BEFORE the caret (end OR
                    // mid-text — the back-sync means we KNOW the external
                    // caret): DEL keystrokes, no a11y write, no IME hide
                    // (field: a single mid-text backspace after an external
                    // caret click still stormed). ASCII-only deleted span so a
                    // DEL-per-char matches; anything else self-heals via the
                    // SET_TEXT path.
                    val delCount = oldText.length - newText.length
                    val caretPos = newValue.selection.end.coerceIn(0, newText.length)
                    // GATED OFF — same reason as asciiAppend above.
                    val pureTruncation = FORWARD_LIVE_KEYSTROKES &&
                        pendingSyncText == null &&
                        lastSyncedText == oldText && delCount > 0 &&
                        newValue.selection.min == newValue.selection.max &&
                        caretPos + delCount <= oldText.length &&
                        lastSyncedCaret == caretPos + delCount &&
                        oldText.removeRange(caretPos, caretPos + delCount) == newText &&
                        oldText.substring(caretPos, caretPos + delCount)
                            .all { it.code in 0x20..0x7E }
                    when {
                        newText == oldText && newValue.selection == fieldValue.selection -> Unit
                        asciiAppend -> {
                            appended.forEach { forwardCharacter(it, injector) }
                            lastSyncedText = newText
                            lastSyncedCaret = newText.length
                        }
                        pureTruncation -> {
                            repeat(delCount) { injector.pressKey(KeyEvent.KEYCODE_DEL) }
                            lastSyncedText = newText
                            lastSyncedCaret = caretPos
                        }
                        else -> {
                            // Deletions, rewrites, emoji, selection moves — and
                            // appends over a pending push — all coalesce into
                            // ONE debounced external write.
                            pendingSyncText = newText
                            pendingSyncCaret = newValue.selection.end.coerceIn(0, newText.length)
                            syncJob?.cancel()
                            // PAUSE-GATED: each keystroke pushes the flush
                            // out; it fires only after pauseFlushMs of true
                            // stillness, so NOTHING touches the external node
                            // while keys are flowing (see the pause-gate doc
                            // at the bottom of this file for the evidence).
                            if (newText != oldText) {
                                lastTextEditAt = android.os.SystemClock.uptimeMillis()
                            }
                            val flushWait = pauseFlushMs.toLong().coerceIn(300L, 1500L)
                            syncJob = relayScope.launch {
                                kotlinx.coroutines.delay(flushWait)
                                flushSync()
                            }
                        }
                    }
                    // Preserve the IME's composing span. Reconstructing a fresh
                    // TextFieldValue on every key silently discarded it, which
                    // makes Samsung restart the local input connection while the
                    // Android text-box keyboard is in use.
                    fieldValue = newValue.copy(
                        selection = TextRange(
                            newValue.selection.start.coerceIn(0, newText.length),
                            newValue.selection.end.coerceIn(0, newText.length),
                        ),
                    )
                    injector.setSearchQuery(newText)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    // Min = the scroll container's viewport, so the bordered box
                    // always fills the visible area and only grows past it when
                    // the CONTENT overflows (which is what makes the thumb show).
                    .heightIn(min = fieldViewportH)
                    .focusRequester(focusRequester)
                    // OPPORTUNISTIC empty-backspace cue: fires only on IMEs
                    // that deliver delete as key events (some do, many don't —
                    // stated limitation; the guaranteed cue is the clear-
                    // transition buzz in onValueChange). Never consumes.
                    .onPreviewKeyEvent { ev ->
                        if (ev.type == KeyEventType.KeyDown &&
                            ev.key == Key.Backspace &&
                            fieldValue.text.isEmpty()
                        ) {
                            injector.buzz()
                            relayScope.launch {
                                kotlinx.coroutines.delay(110)
                                injector.buzz()
                            }
                        }
                        false
                    },
                placeholder = { Text("Tap here and start typing…", color = PhoneOnSurfaceMuted) },
                keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.None),
                interactionSource = interactionSource,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = PhoneOnSurface, unfocusedTextColor = PhoneOnSurface,
                    focusedBorderColor = PhonePrimary, unfocusedBorderColor = PhoneSurfaceElevated,
                    cursorColor = PhonePrimaryBright,
                ),
            )
            } // end field scroll column
            // Visible thumb only when the content overflows the viewport (SH).
            // Corner chevrons beside it (bright = can scroll that way, tap =
            // nudge); deliberately NARROW, hugging the right edge - full-width
            // strips would steal caret taps from the first and last lines.
            if (fieldScroll.maxValue > 0) {
                val fieldChevScope = rememberCoroutineScope()
                Text(
                    "▲",
                    color = if (fieldScroll.value > 0) PhonePrimaryBright
                    else PhonePrimaryBright.copy(alpha = 0.25f),
                    fontSize = 9.sp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 8.dp)
                        .clickable(enabled = fieldScroll.value > 0) {
                            fieldChevScope.launch {
fieldScroll.animateScrollBy(-240f)
                            }
                        }
                        .padding(4.dp),
                )
                Text(
                    "▼",
                    color = if (fieldScroll.value < fieldScroll.maxValue) PhonePrimaryBright
                    else PhonePrimaryBright.copy(alpha = 0.25f),
                    fontSize = 9.sp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 8.dp)
                        .clickable(enabled = fieldScroll.value < fieldScroll.maxValue) {
                            fieldChevScope.launch {
fieldScroll.animateScrollBy(240f)
                            }
                        }
                        .padding(4.dp),
                )
            }
            if (fieldScroll.maxValue > 0) {
                // chevZone: this viewport overlays its own corner chevrons, so
                // the track stops clear of them (page-scrollbar treatment).
                MenuScrollThumb(fieldScroll, endPadding = 3.dp, chevZone = 20.dp)
            }
            } // end BoxWithConstraints (field viewport)

            } // end Android-keyboard branch
        }

        // ── Floating suggestion popup (over the keyboard) ──────────────────
        if (showSuggestions && overlaySuggestions.isNotEmpty()) {
            // Scrim: tap anywhere outside dismisses without choosing.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f))
                    .clickable(
                        indication = null,
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    ) { showSuggestions = false },
            )
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 96.dp, start = 16.dp, end = 16.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(PhoneSurface)
                    .border(1.dp, PhonePrimary, RoundedCornerShape(14.dp)),
            ) {
                Text(
                    "Suggestions \u00B7 tap to open",
                    color = PhoneOnSurfaceMuted,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                )
                val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                val suggOverflow = listState.canScrollBackward || listState.canScrollForward
                if (suggOverflow) {
                    com.portalpad.app.ui.common.ScrollEdgeChevron(listState, up = true)
                }
                androidx.compose.foundation.layout.Box {
                    val suggThumb = PhoneOnSurfaceMuted.copy(alpha = 0.6f)
                    androidx.compose.foundation.lazy.LazyColumn(
                        state = listState,
                        modifier = Modifier
                            // Content-sized up to this cap: 8 chips (some with
                            // 2-line URL rows) fit without clipping; beyond it
                            // the list scrolls, with the thumb below as the
                            // affordance (a clipped row used to read as broken
                            // — field report 2026-07-09).
                            .heightIn(max = 420.dp)
                            .fillMaxWidth()
                            // Scrollbar thumb (the dock list's pattern): drawn
                            // only while content overflows; sized to the
                            // visible fraction, tracks scroll position.
                            .drawWithContent {
                                drawContent()
                                val info = listState.layoutInfo
                                val total = info.totalItemsCount
                                if (total > 0 && info.visibleItemsInfo.isNotEmpty()) {
                                    val visible = info.visibleItemsInfo.size.toFloat()
                                    val frac = (visible / total).coerceIn(0.1f, 1f)
                                    if (frac < 1f) {
                                        val firstIdx = info.visibleItemsInfo.first().index.toFloat()
                                        val progress = (firstIdx / (total - visible)).coerceIn(0f, 1f)
                                        val thumbH = size.height * frac
                                        val thumbY = (size.height - thumbH) * progress
                                        val w = 4.dp.toPx()
                                        drawRoundRect(
                                            color = suggThumb,
                                            topLeft = androidx.compose.ui.geometry.Offset(size.width - w - 3.dp.toPx(), thumbY),
                                            size = androidx.compose.ui.geometry.Size(w, thumbH),
                                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w / 2),
                                        )
                                    }
                                }
                            },
                    ) {
                        itemsIndexed(overlaySuggestions) { i, s ->
                            androidx.compose.foundation.layout.Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showSuggestions = false
                                        suggestionScope.launch {
                                            val svc = com.portalpad.app.service
                                                .PortalPadAccessibilityService.instance
                                            // Tier 1: click the LIVE node — free win in
                                            // the rare case Chrome's dropdown outlived
                                            // the trigger tap. Preserves the row's exact
                                            // semantics, whatever they were.
                                            var ok = runCatching {
                                                svc?.clickSuggestion(injector.displayId, s.title, i)
                                            }.getOrNull() == true
                                            // Tier 2 (expected path): the real dropdown
                                            // died when the trigger was tapped, so the
                                            // node is gone. SET_TEXT into the retained
                                            // field node (same primitive the relay uses
                                            // for deletes) and submit with a real Enter.
                                            // URL rows submit their line_2 ADDRESS (the
                                            // omnibox navigates); search rows submit the
                                            // title (the omnibox searches) — same
                                            // outcome the real row would have had.
                                            if (!ok) {
                                                val submitText = s.url ?: s.title
                                                val set = runCatching { svc?.setFieldText(submitText) }
                                                    .getOrNull() == true
                                                if (set) {
                                                    injector.setSearchQuery(submitText)
                                                    // Let Chrome's editor ingest the
                                                    // SET_TEXT before the submit key.
                                                    kotlinx.coroutines.delay(120)
                                                    injector.submitSearch()
                                                    injector.pressKey(KeyEvent.KEYCODE_ENTER)
                                                    ok = true
                                                }
                                            }
                                            android.util.Log.d(
                                                "PortalPadRelay",
                                                "suggestion pick '${s.title}' idx=$i url=${s.url != null} ok=$ok",
                                            )
                                            if (ok) {
                                                injector.buzz(longPress = false)
                                                requestClose()
                                            }
                                        }
                                    }
                                    .padding(start = 14.dp, end = 20.dp, top = 11.dp, bottom = 11.dp),
                            ) {
                                Text(
                                    s.title,
                                    color = PhoneOnSurface,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                // Dimmed address line — present only on rows whose
                                // pick will NAVIGATE rather than search.
                                if (s.url != null) {
                                    Text(
                                        s.url,
                                        color = PhoneOnSurfaceMuted,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        }
                    }
                    // Persistent proportional scrollbar — visible ONLY when the
                    // list actually overflows (no false signal on short lists).
                    val info = listState.layoutInfo
                    val total = info.totalItemsCount
                    val visible = info.visibleItemsInfo.size
                    if (total > visible && total > 0) {
                        val frac = visible.toFloat() / total
                        val progress = if (total > visible)
                            listState.firstVisibleItemIndex.toFloat() / (total - visible) else 0f
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 4.dp, bottom = 4.dp, end = 3.dp)
                                .width(4.dp)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(2.dp))
                                .background(PhoneSurfaceElevated),
                        ) {
                            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxHeight()) {
                                val thumbH = maxHeight * frac
                                val room = maxHeight - thumbH
                                Box(
                                    Modifier
                                        .offset(y = room * progress)
                                        .width(4.dp)
                                        .height(thumbH)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(PhonePrimary),
                                )
                            }
                        }
                    }
                }
                if (suggOverflow) {
                    com.portalpad.app.ui.common.ScrollEdgeChevron(listState, up = false)
                }
            }
        }
      }
    }
}

/** Height of the multi-line mirror field (text wraps + scrolls vertically within). */
private val MIRROR_HEIGHT = 120.dp

/** Key height, set responsively per screen so the keyboard fits without scrolling. */
private val LocalKbKeyHeight = compositionLocalOf { 46.dp }

// Swap layer / accent tiers REMOVED (SH, 2026-07-23): the cycling-layers UI row
// above the keys is gone, and symbols mode is paged 1/2 <-> 2/2 instead, sharing
// SYM_PAGE_1 / SYM_PAGE_2 with the on-display keyboard so all PortalPad keyboards
// emit one symbol set.

/**
 * Grapheme-cluster boundaries. Emoji are multiple UTF-16 units (surrogate pairs, and
 * ZWJ/flag/skin-tone sequences are several code points), so moving or deleting one
 * unit at a time would split them. BreakIterator gives whole-glyph boundaries.
 */
private fun prevGrapheme(s: String, index: Int): Int {
    if (index <= 0) return 0
    val bi = java.text.BreakIterator.getCharacterInstance()
    bi.setText(s)
    val b = bi.preceding(index.coerceAtMost(s.length))
    return if (b == java.text.BreakIterator.DONE) 0 else b
}
private fun nextGrapheme(s: String, index: Int): Int {
    if (index >= s.length) return s.length
    val bi = java.text.BreakIterator.getCharacterInstance()
    bi.setText(s)
    val b = bi.following(index.coerceAtLeast(0))
    return if (b == java.text.BreakIterator.DONE) s.length else b
}

/** Emoji picker categories. Tab glyphs for the row are in EMOJI_TABS (Recent first). */
private val EMOJI_TABS = listOf("\uD83D\uDD58", "\uD83D\uDE00", "\uD83D\uDC36", "\uD83C\uDF4E", "\u26BD", "\u2708\uFE0F", "\uD83D\uDCA1", "\uD83D\uDD23")
private val EMOJI_CATEGORIES: List<List<String>> = listOf(
    // Smileys & people
    "😀 😃 😄 😁 😆 😅 😂 🤣 🥲 😊 😇 🙂 🙃 😉 😌 😍 🥰 😘 😗 😙 😚 😋 😛 😝 😜 🤪 🤨 🧐 🤓 😎 🥳 😏 😒 😞 😔 😟 😕 🙁 😣 😖 😫 😩 🥺 😢 😭 😤 😠 😡 🤬 🤯 😳 🥵 🥶 😱 😨 😰 😥 😓 🤗 🤔 🤭 🤫 🤥 😶 😐 😑 😬 🙄 😮 😲 🥱 😴 🤤 😪 🤐 🥴 🤢 🤮 🤧 😷 🤒 🤕 👍 👎 👌 ✌️ 🤞 🙏 👏 🙌 💪 👋",
    // Animals & nature
    "🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐨 🐯 🦁 🐮 🐷 🐸 🐵 🙈 🙉 🙊 🐔 🐧 🐦 🐤 🦆 🦅 🦉 🦇 🐺 🐗 🐴 🦄 🐝 🐛 🦋 🐌 🐞 🐜 🐢 🐍 🦎 🦖 🐙 🦑 🦐 🦀 🐡 🐠 🐟 🐬 🐳 🐋 🦈 🐊 🐅 🐆 🦓 🦍 🐘 🦏 🦛 🐪 🐫 🦒 🦘 🐎 🐖 🐏 🐑 🐐 🌵 🌲 🌳 🌴 🌱 🌿 ☘️ 🍀 🍃 🌷 🌹 🌺 🌻 🌼 🌸 💐 🍄 🌰",
    // Food & drink
    "🍏 🍎 🍐 🍊 🍋 🍌 🍉 🍇 🍓 🫐 🍈 🍒 🍑 🥭 🍍 🥥 🥝 🍅 🍆 🥑 🥦 🥬 🥒 🌶️ 🌽 🥕 🧄 🧅 🥔 🍠 🥐 🥯 🍞 🥖 🥨 🧀 🥚 🍳 🥞 🧇 🥓 🥩 🍗 🍖 🌭 🍔 🍟 🍕 🥪 🌮 🌯 🥙 🥗 🍣 🍤 🍙 🍚 🍛 🍜 🍲 🍥 🍢 🍡 🍧 🍨 🍦 🥧 🧁 🍰 🎂 🍮 🍭 🍬 🍫 🍿 🍩 🍪 ☕ 🍵 🥤 🍶 🍺 🍷 🥂 🥃 🍸 🍹",
    // Activities
    "⚽ 🏀 🏈 ⚾ 🥎 🎾 🏐 🏉 🥏 🎱 🪀 🏓 🏸 🏒 🏑 🥍 🏏 🥅 ⛳ 🪁 🏹 🎣 🤿 🥊 🥋 🎽 🛹 🛼 🛷 ⛸️ 🥌 🎿 ⛷️ 🏂 🏋️ 🤼 🤸 ⛹️ 🤺 🏌️ 🏇 🧘 🏄 🏊 🚣 🧗 🚴 🚵 🏆 🏅 🥇 🥈 🥉 🎫 🎪 🤹 🎭 🎨 🎬 🎤 🎧 🎼 🎹 🥁 🎷 🎺 🎸 🎻 🎲 ♟️ 🎯 🎳 🎮 🎰 🧩",
    // Travel & places
    "🚗 🚕 🚙 🚌 🚎 🏎️ 🚓 🚑 🚒 🚐 🚚 🚛 🚜 🛴 🚲 🛵 🏍️ 🚨 🚔 🚍 🚘 🚖 🚡 🚠 🚟 🚃 🚋 🚞 🚝 🚄 🚅 🚈 🚂 🚆 🚇 🚊 🚉 ✈️ 🛫 🛬 🛩️ 💺 🚀 🛸 🚁 🛶 ⛵ 🚤 🛳️ ⛴️ 🚢 ⚓ ⛽ 🚧 🚦 🚥 🗺️ 🗿 🗽 🗼 🏰 🏯 🏟️ 🎡 🎢 🎠 ⛲ 🏖️ 🏝️ 🏜️ 🌋 ⛰️ 🏔️ 🗻 🏕️ ⛺ 🏠 🏡 🏘️ 🏭 🏢 🏬 🏥 🏦 🏨 🏪 🏫 ⛪ 🕌",
    // Objects
    "⌚ 📱 💻 ⌨️ 🖥️ 🖨️ 🖱️ 🕹️ 💽 💾 💿 📀 📷 📸 📹 🎥 📽️ 🎞️ 📞 ☎️ 📟 📠 📺 📻 🎙️ 🧭 ⏱️ ⏰ 🕰️ ⌛ ⏳ 📡 🔋 🔌 💡 🔦 🕯️ 🧯 💸 💵 💴 💶 💷 🪙 💰 💳 🧾 💎 ⚖️ 🔧 🔨 🛠️ ⛏️ 🔩 ⚙️ 🧲 🔫 💣 🧨 🪓 🔪 🗡️ ⚔️ 🛡️ 🚬 ⚰️ 🏺 🔮 📿 🧿 🔭 🔬 💊 💉 🩸 🧬 🦠 🧫 🧪 🌡️ 🧹 🧺 🧻 🚽 🚿 🛁 🧼 🧽 🔑 🗝️ 🚪 🛋️ 🛏️ 🧸 🖼️ 🛍️ 🛒 🎁 🎈 🎀 🎉 🎊 ✉️ 📩 📨 📧 💌 📦 🏷️ 📫 📮 📜 📄 📊 📈 📉 📆 📅 📇 📋 📁 📂 🗂️ 📰 📕 📗 📘 📙 📚 📖 🔖 📎 📐 📏 🧮 📌 📍 ✂️ 🖊️ 🖋️ ✒️ 🖌️ 🖍️ 📝 ✏️ 🔍 🔒 🔓",
    // Symbols
    "❤️ 🧡 💛 💚 💙 💜 🖤 🤍 🤎 💔 ❣️ 💕 💞 💓 💗 💖 💘 💝 💟 ☮️ ✝️ ☪️ 🕉️ ☸️ ✡️ 🔯 🕎 ☯️ ☦️ 🛐 ⛎ ♈ ♉ ♊ ♋ ♌ ♍ ♎ ♏ ♐ ♑ ♒ ♓ ⚛️ ☢️ ☣️ ✴️ 🆚 ❌ ⭕ 🛑 ⛔ 🚫 💯 💢 ♨️ 🔞 📵 🚭 ❗ ❓ ❔ ❕ ‼️ ⁉️ ⚠️ 🚸 🔱 ⚜️ 🔰 ♻️ ✅ 💹 ❇️ ✳️ ❎ 🌐 💠 🌀 🏧 🚾 ♿ 🅿️ 🚹 🚺 🚼 🚻 🚮 🔣 🔤 🔡 🔠 🆗 🆙 🆒 🆕 🆓 🔟 ➕ ➖ ➗ ✖️ 🟰 ♾️ 💲 💱 ©️ ®️ ™️ 🔴 🟠 🟡 🟢 🔵 🟣 ⚫ ⚪ 🟥 🟧 🟨 🟩 🟦 🟪 ⬛ ⬜",
).map { it.split(" ").filter { e -> e.isNotBlank() } }

/**
 * Press-and-hold key repeat. Fires [action] once on press, then (if still held after
 * a short delay) repeats it on an accelerating interval until release/cancel. Used
 * for backspace and the caret arrows. When [enabled] is false the modifier is inert.
 */
private fun Modifier.holdRepeat(
    scope: CoroutineScope,
    enabled: Boolean,
    action: () -> Unit,
): Modifier = if (!enabled) this else this.then(
    Modifier.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            action()
            val job = scope.launch {
                kotlinx.coroutines.delay(380)
                var d = 90L
                while (true) {
                    action()
                    kotlinx.coroutines.delay(d)
                    d = (d * 85 / 100).coerceAtLeast(35L)
                }
            }
            try {
                waitForUpOrCancellation()
            } finally {
                job.cancel()
            }
        }
    },
)

/**
 * A compact pill that opens a small menu of insertable tokens (domains, email
 * hosts, URL schemes). Picking one inserts it at the caret via [onPick]. The menu
 * matches the pill width, styled like the keyboard's surfaces.
 */
@Composable
private fun QuickInsertDropdown(
    label: String,
    items: List<String>,
    modifier: Modifier = Modifier,
    onPick: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    // Menu matches the pill width (SH), like the keyboard-mode picker: the menu
    // otherwise wraps its content and floats narrower than its button.
    var pillWidth by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current
    Box(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .onGloballyPositioned { pillWidth = with(density) { it.size.width.toDp() } }
                .clip(RoundedCornerShape(8.dp))
                .background(PhoneSurface)
                .border(1.dp, PhonePrimaryDim, RoundedCornerShape(8.dp))
                .clickable { open = true }
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                color = PhoneOnSurface,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Filled.ArrowDropUp,
                contentDescription = null,
                tint = PhoneOnSurfaceMuted,
            )
        }
        // A custom above-anchor Popup, NOT DropdownMenu: the whole keyboard sits
        // below these pills inside the same window, so Compose saw plenty of room
        // and opened the menu DOWNWARD over the keys while the chevron pointed up
        // (SH). This provider pins the menu attached ABOVE the pill, as the
        // chevron promises; a height cap + internal scroll (with a visible thumb)
        // handles lists taller than the space.
        if (open) {
            androidx.compose.ui.window.Popup(
                popupPositionProvider = remember {
                    object : androidx.compose.ui.window.PopupPositionProvider {
                        override fun calculatePosition(
                            anchorBounds: androidx.compose.ui.unit.IntRect,
                            windowSize: androidx.compose.ui.unit.IntSize,
                            layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                            popupContentSize: androidx.compose.ui.unit.IntSize,
                        ): androidx.compose.ui.unit.IntOffset {
                            val x = anchorBounds.left
                                .coerceAtMost(windowSize.width - popupContentSize.width)
                                .coerceAtLeast(0)
                            val y = (anchorBounds.top - popupContentSize.height)
                                .coerceAtLeast(0)
                            return androidx.compose.ui.unit.IntOffset(x, y)
                        }
                    }
                },
                onDismissRequest = { open = false },
                properties = androidx.compose.ui.window.PopupProperties(focusable = true),
            ) {
                Box(
                    Modifier
                        .width(pillWidth)
                        .heightIn(max = 240.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(PhoneSurfaceElevated)
                        .border(1.dp, PhonePrimaryDim, RoundedCornerShape(10.dp)),
                ) {
                    val menuScroll = rememberScrollState()
                    val menuOverflow = menuScroll.maxValue > 0
                    Column(Modifier.fillMaxWidth()) {
                        if (menuOverflow) {
                            com.portalpad.app.ui.common.ScrollEdgeChevron(menuScroll, up = true)
                        }
                        Box(Modifier.weight(1f, fill = false)) {
                            Column(Modifier.verticalScroll(menuScroll).fillMaxWidth()) {
                                items.forEachIndexed { i, item ->
                                    if (i > 0) HorizontalDivider(color = PhonePrimaryDim.copy(alpha = 0.5f))
                                    Text(
                                        item,
                                        color = PhoneOnSurface,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { open = false; onPick(item) }
                                            .padding(horizontal = 12.dp, vertical = 12.dp),
                                    )
                                }
                            }
                            // Visible thumb when the list overflows the cap (SH).
                            if (menuOverflow) {
                                MenuScrollThumb(menuScroll)
                            }
                        }
                        if (menuOverflow) {
                            com.portalpad.app.ui.common.ScrollEdgeChevron(menuScroll, up = false)
                        }
                    }
                }
            }
        }
    }
}

/** One clipboard action button (Select all / Cut / Copy / Paste / Mic / Clear). */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun RowScope.KbClipButton(
    label: String,
    active: Boolean = false,
    icon: ImageVector? = null,
    fixedWidth: Dp? = null,
    weight: Float = 1f,
    restingColor: Color? = null,
    iconTint: Color? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .then(if (fixedWidth != null) Modifier.width(fixedWidth) else Modifier.weight(weight))
            .height(40.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (active) PhonePrimary else (restingColor ?: PhoneSurfaceElevated))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = label,
                tint = if (active) PhoneOnSurface else (iconTint ?: PhoneOnSurface),
                modifier = Modifier.size(20.dp),
            )
        } else {
            Text(label, color = PhoneOnSurface, style = MaterialTheme.typography.labelMedium, fontSize = 12.sp)
        }
    }
}


/** One key on the custom keyboard. If [repeatScope] is set, the key auto-repeats while held. */
@Composable
private fun RowScope.KbKey(
    label: String,
    weight: Float = 1f,
    bg: Color = PhoneSurfaceElevated,
    fg: Color = PhoneOnSurface,
    repeatScope: CoroutineScope? = null,
    preview: Boolean = false,
    onClick: () -> Unit,
) {
    val keyH = LocalKbKeyHeight.current
    // holdRepeat captures its action inside a pointerInput(Unit) coroutine that
    // OUTLIVES recomposition — so when ?123 swapped the rows, each positionally
    // reused key kept typing its FIRST label's character ("1" typed "q", "@"
    // typed "a", "?" typed "m": every symbol key sent the letter from the same
    // grid slot). Route the call through rememberUpdatedState so the long-lived
    // gesture always invokes the CURRENT key's action; letter mode never showed
    // it because the slot's k never changes there.
    val currentOnClick by rememberUpdatedState(onClick)
    // Press state, tracked on the Initial pass so it works for BOTH the clickable and
    // the hold-repeat paths (the character keys repeat) without consuming — so it can't
    // interfere with the actual click/repeat handling layered after it.
    var pressed by remember { mutableStateOf(false) }
    // Outer wrapper carries the row weight + key height as the layout footprint but does
    // NOT clip, so the press-preview bubble can render ABOVE the key. The clipped key
    // surface is a child; the bubble is a sibling drawn on top.
    Box(Modifier.weight(weight).height(keyH)) {
        val surface = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(
                        requireUnconsumed = false,
                        pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial,
                    )
                    pressed = true
                    do {
                        val e = awaitPointerEvent(
                            androidx.compose.ui.input.pointer.PointerEventPass.Initial,
                        )
                    } while (e.changes.any { it.pressed })
                    pressed = false
                }
            }
            .clip(RoundedCornerShape(7.dp))
            .background(if (pressed) androidx.compose.ui.graphics.lerp(bg, PhonePrimary, 0.3f) else bg)
        val clickMod = if (repeatScope != null) {
            surface.holdRepeat(repeatScope, true) { currentOnClick() }
        } else {
            surface.clickable { currentOnClick() }
        }
        Box(clickMod, contentAlignment = Alignment.Center) {
            Text(label, color = fg, style = MaterialTheme.typography.titleMedium)
        }
        // Press preview: a bubble popped ABOVE the pressed key showing its character, so
        // it's visible above the finger. Character keys only (preview = true). Same
        // window (no Popup → no gesture cancel) and outside the key's rounded clip.
        if (preview && pressed) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .offset { IntOffset(0, -(keyH.roundToPx() + 12.dp.roundToPx())) }
                    .size(48.dp, keyH + 8.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(PhoneSurfaceElevated)
                    .border(1.5.dp, PhonePrimary, RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = fg, style = MaterialTheme.typography.headlineSmall)
            }
        }
    }
}

/**
 * A draggable selection handle (one per selection end). The visual is a small circle
 * hanging just below the line; the touch target is larger. Dragging maps the finger
 * (tracked in text space via accumulated drag deltas, so vertical scroll doesn't
 * skew it) to a character offset and reports it live via [onMoveTo]; [onRelease]
 * fires on lift so the glasses field is only re-selected once, not every frame.
 */
@Composable
private fun SelectionHandle(
    cursorRect: androidx.compose.ui.geometry.Rect,
    scrollPx: Int,
    currentOffset: () -> Int,
    layout: () -> androidx.compose.ui.text.TextLayoutResult?,
    onGrab: () -> Unit,
    onMoveTo: (Int) -> Unit,
    onRelease: () -> Unit,
) {
    val half = with(LocalDensity.current) { 14.dp.toPx() }
    val xPx = cursorRect.left
    val yPx = cursorRect.bottom - scrollPx
    var dragPos by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
    // Safety net: if this handle is removed while a drag is still in progress — e.g. a
    // selection collapses to zero width and its handle leaves the tree before onDragEnd
    // fires — release it anyway so the magnifier flag can't get stranded on.
    val latestRelease by rememberUpdatedState(onRelease)
    DisposableEffect(Unit) {
        onDispose { if (dragPos != null) latestRelease() }
    }
    Box(
        Modifier
            .offset { IntOffset((xPx - half).roundToInt(), yPx.roundToInt()) }
            .size(28.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                        onGrab()
                        val r = layout()?.let { runCatching { it.getCursorRect(currentOffset()) }.getOrNull() }
                        dragPos = if (r != null) {
                            androidx.compose.ui.geometry.Offset(r.left, (r.top + r.bottom) / 2f)
                        } else {
                            null
                        }
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val l = layout()
                        val dp = dragPos
                        if (l != null && dp != null) {
                            val cur = dp + dragAmount
                            dragPos = cur
                            onMoveTo(l.getOffsetForPosition(cur))
                        }
                    },
                    onDragEnd = { dragPos = null; onRelease() },
                    onDragCancel = { dragPos = null; onRelease() },
                )
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(Modifier.size(14.dp).clip(CircleShape).background(PhonePrimaryBright))
    }
}

/**
 * Custom on-screen keyboard. Unlike the Android-keyboard path, there is NO system
 * IME and NO focused proxy text field here — each key drives the glasses field
 * directly through the accessibility service (SET_TEXT for insert/delete, SET_SELECTION
 * for the caret). That removes the entire focus/IME-desync problem: the buttons don't
 * need IME focus, so SET_TEXT stealing focus to the external display can't collapse
 * anything, and backspace/caret are exact because we own the buffer. Falls back to
 * keystroke forwarding only if the accessibility service isn't connected.
 */
// internal (was private): the floating-keyboard overlay reuses this exact
// keyboard so the two hosts cannot drift. Its private helpers stay private -
// they are only called from within this function, which stays in this file.
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun ColumnScope.CustomKeyboardSection(
    injector: InputInjector,
    initialText: String,
    initialCaret: Int,
    initialAnchor: Int = -1,
    // false = the floating keyboard: no mirror/text-box preview and none of the
    // centering slack, so the clip row + keys pack compactly to the bottom. The
    // buffer/caret still exist internally (the clip actions and SET_TEXT tracking
    // need them); only the on-phone preview of them is dropped. You watch the text
    // appear on the external display instead - the whole point of the mode.
    showMirror: Boolean = true,
    onState: (String, Int) -> Unit = { _, _ -> },
) {
    var buffer by remember { mutableStateOf(initialText) }
    var caret by remember { mutableIntStateOf(initialCaret.coerceIn(0, initialText.length)) }
    var shift by remember { mutableStateOf(false) }
    var capsLock by remember { mutableStateOf(false) }
    var lastShiftTapAt by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    var symbols by remember { mutableStateOf(false) }
    // Symbols pager: false = page 1/2, true = 2/2. Reset when leaving ?123.
    var symPage2 by remember { mutableStateOf(false) }
    // Emoji picker: when on, the keyboard is replaced by the emoji grid. emojiCat is the
    // selected tab (0 = Recent, 1.. = EMOJI_CATEGORIES). Recent is persisted across opens.
    var emoji by remember { mutableStateOf(false) }
    var emojiCat by remember { mutableIntStateOf(1) }
    val ctx = LocalContext.current
    val prefs = remember {
        ctx.getSharedPreferences("portalpad_kb", android.content.Context.MODE_PRIVATE)
    }
    var recent by remember {
        mutableStateOf(
            prefs.getString("recent_emoji", "")
                ?.split("\n")?.filter { it.isNotEmpty() } ?: emptyList(),
        )
    }
    // Mic search: listening drives the listening panel; voiceMsg surfaces errors /
    // permission prompts; speechRef holds the active recognizer so it can be torn down.
    var listening by remember { mutableStateOf(false) }
    var voiceMsg by remember { mutableStateOf<String?>(null) }
    // Continuous (latched) dictation: engaged by long-pressing the mic. Keeps re-arming
    // the recognizer after each phrase. lastResultAt drives a 60s silence auto-timeout.
    var continuousMic by remember { mutableStateOf(false) }
    val lastResultAt = remember { mutableStateOf(0L) }
    // Clear-all undo: holds (text, caret) from just before a clear so it can be restored.
    // Non-null = the "Field cleared · Undo" strip is showing in place of the action row.
    var clearedBackup by remember { mutableStateOf<Pair<String, Int>?>(null) }
    val speechRef = remember { mutableStateOf<android.speech.SpeechRecognizer?>(null) }
    DisposableEffect(Unit) {
        onDispose { runCatching { speechRef.value?.destroy() } }
    }
    // Gesture (handwriting) mode: draw letters that ML Kit recognizes and types.
    // strokes = completed strokes; currentStroke = the one being drawn (for live ink).
    // gestureGen bumps to reset the pointer-capture after each committed letter.
    var gesture by remember { mutableStateOf(false) }
    var gestureReady by remember { mutableStateOf(false) }
    var gestureMsg by remember { mutableStateOf<String?>(null) }
    var gestureFailed by remember { mutableStateOf(false) }
    val gestureInk = remember { GestureInkRecognizer() }
    val strokes = remember { mutableStateListOf<List<InkPoint>>() }
    var currentStroke by remember { mutableStateOf<List<InkPoint>>(emptyList()) }
    var gestureCandidates by remember { mutableStateOf<List<String>>(emptyList()) }
    var lastCommitLen by remember { mutableIntStateOf(0) }
    var gestureGen by remember { mutableIntStateOf(0) }
    // Sticky Caps Lock for gesture mode: forces committed letters to upper/lower case
    // deterministically (ML Kit's returned case is unreliable for single letters).
    var gestureCaps by remember { mutableStateOf(false) }
    val gestureCommitJob = remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    DisposableEffect(Unit) {
        onDispose { gestureInk.close() }
    }
    // Prepares the handwriting model. Three outcomes drive the on-canvas message:
    //   • downloading  → "Downloading gesture kit…" (first run / not yet cached)
    //   • ready        → message cleared, canvas live
    //   • failed       → "tap to retry" (e.g. the USB-Ethernet/DNS case), retry re-runs this
    fun prepareGesture() {
        gestureFailed = false
        gestureMsg = "Preparing handwriting…"
        gestureInk.prepare(
            onReady = { gestureReady = true; gestureMsg = null; gestureFailed = false },
            onError = { gestureMsg = "Couldn't download gesture kit — tap to retry"; gestureFailed = true },
            onDownloading = { gestureMsg = "Downloading gesture kit…" },
        )
    }
    LaunchedEffect(gesture) {
        if (gesture && !gestureReady) {
            prepareGesture()
        }
    }
    // Caret blink: caretVisible toggles on a timer; blinkReset is bumped on any
    // caret activity so the caret snaps solid the instant you type/move it, then
    // resumes blinking (standard caret feel). caretLayout is the measured glyph
    // layout of the mirror text, used to draw the caret and map taps to a char index.
    var caretVisible by remember { mutableStateOf(true) }
    var blinkReset by remember { mutableIntStateOf(0) }
    var caretLayout by remember {
        mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null)
    }
    // Selection: an anchor (null = no selection). The selected span runs between the
    // anchor and the caret. Only "Select all" creates one; any edit/move/tap clears it.
    var selAnchor by remember {
        mutableStateOf<Int?>(
            initialAnchor.takeIf { it in 0..initialText.length && it != initialCaret },
        )
    }
    // EXTERNAL→CUSTOM caret sync: a tap in the external field (published by the
    // a11y back-sync) moves this keyboard's caret to match. drop(1) skips the
    // StateFlow's replay of the LAST selection on entry — without it, merely
    // switching to this tab would yank the caret to a stale external position,
    // stomping the tab-switch handoff. Applied locally only (no pushCaret):
    // the external field is ALREADY at this position — pushing it back would
    // just round-trip through the echo window.
    LaunchedEffect(Unit) {
        PortalPadAccessibilityService.relayFieldSelection.collect { sel ->
            val c = sel.first.coerceIn(0, buffer.length)
            android.util.Log.i("PortalPadRelay",
                "DIAG-CARETSYNC apply(custom) sel=${sel.first}..${sel.second} → caret=$c (was $caret, len=${buffer.length})")
            if (c != caret) {
                caret = c
                selAnchor = null
                onState(buffer, caret)
            }
        }
    }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    fun selRange(): Pair<Int, Int>? {
        val a = selAnchor ?: return null
        val lo = minOf(a, caret).coerceIn(0, buffer.length)
        val hi = maxOf(a, caret).coerceIn(0, buffer.length)
        return if (lo == hi) null else lo to hi
    }

    fun pushText() {
        val svc = PortalPadAccessibilityService.instance
        val ok = runCatching { svc?.setFieldText(buffer) == true }.getOrDefault(false)
        if (ok) runCatching { svc?.setFieldSelection(caret, caret) }
        injector.setSearchQuery(buffer)
        onState(buffer, caret)
        android.util.Log.i(
            "PortalPadRelay",
            "customKb text: buffer='${buffer.take(40)}'(len=${buffer.length}) caret=$caret setText=$ok",
        )
        // No service / SET_TEXT failed → we can't mirror; nothing else to do (the
        // mirror bar still reflects the buffer so the user sees their edit).
    }
    fun pushCaret() {
        runCatching { PortalPadAccessibilityService.instance?.setFieldSelection(caret, caret) }
        onState(buffer, caret)
        android.util.Log.i("PortalPadRelay", "customKb caret=$caret")
    }
    fun insert(s: String) {
        injector.buzz()
        blinkReset++
        val sel = selRange()
        if (sel != null) {
            buffer = buffer.substring(0, sel.first) + s + buffer.substring(sel.second)
            caret = sel.first + s.length
            selAnchor = null
        } else {
            val c = caret.coerceIn(0, buffer.length)
            buffer = buffer.substring(0, c) + s + buffer.substring(c)
            caret = c + s.length
        }
        if (shift && !capsLock) shift = false
        pushText()
    }
    fun backspace() {
        val sel = selRange()
        if (sel != null) {
            injector.buzz()
            blinkReset++
            buffer = buffer.substring(0, sel.first) + buffer.substring(sel.second)
            caret = sel.first
            selAnchor = null
            pushText()
        } else {
            val c = caret.coerceIn(0, buffer.length)
            if (c > 0) {
                val start = prevGrapheme(buffer, c)
                injector.buzz()
                blinkReset++
                buffer = buffer.substring(0, start) + buffer.substring(c)
                caret = start
                pushText()
            } else {
                // NOTHING to delete — say so with a distinct DOUBLE buzz
                // (field: silent empty-backspace felt like dead keys). One
                // per press, so held repeat gives a clear "you're done" pulse.
                injector.buzz()
                scope.launch {
                    kotlinx.coroutines.delay(110)
                    injector.buzz()
                }
            }
        }
    }
    fun moveCaret(delta: Int) {
        if ((delta < 0 && caret <= 0) || (delta > 0 && caret >= buffer.length)) return
        injector.buzz()
        blinkReset++
        selAnchor = null
        caret = if (delta < 0) prevGrapheme(buffer, caret) else nextGrapheme(buffer, caret)
        pushCaret()
    }
    fun setCaret(pos: Int) {
        injector.buzz()
        blinkReset++
        selAnchor = null
        caret = pos.coerceIn(0, buffer.length)
        pushCaret()
    }
    // Long-press the field to select the word under the finger (the standard way to
    // start a selection without "Select all"). Whitespace/punctuation/empty → just
    // place the caret. Stronger haptic so the long-press registers tactilely.
    fun selectWordAt(off: Int) {
        injector.buzz(longPress = true)
        blinkReset++
        if (buffer.isEmpty()) {
            selAnchor = null
            caret = 0
            pushCaret()
            return
        }
        val o = off.coerceIn(0, buffer.length)
        val idx = when {
            o < buffer.length && buffer[o].isLetterOrDigit() -> o
            o > 0 && buffer[o - 1].isLetterOrDigit() -> o - 1
            else -> -1
        }
        if (idx < 0) {
            selAnchor = null
            caret = o
            pushCaret()
            return
        }
        var start = idx
        while (start > 0 && buffer[start - 1].isLetterOrDigit()) start--
        var end = idx + 1
        while (end < buffer.length && buffer[end].isLetterOrDigit()) end++
        selAnchor = start
        caret = end
        runCatching {
            PortalPadAccessibilityService.instance?.setFieldSelection(start, end)
        }
    }
    fun selectAll() {
        injector.buzz()
        if (buffer.isEmpty()) return
        selAnchor = 0
        caret = buffer.length
        runCatching {
            PortalPadAccessibilityService.instance?.setFieldSelection(0, buffer.length)
        }
    }
    fun copy() {
        injector.buzz()
        val sel = selRange()
        val text = if (sel != null) buffer.substring(sel.first, sel.second) else buffer
        if (text.isNotEmpty()) clipboard.setText(AnnotatedString(text))
    }
    fun cut() {
        injector.buzz()
        blinkReset++
        val sel = selRange()
        val text = if (sel != null) buffer.substring(sel.first, sel.second) else buffer
        if (text.isNotEmpty()) clipboard.setText(AnnotatedString(text))
        if (sel != null) {
            buffer = buffer.substring(0, sel.first) + buffer.substring(sel.second)
            caret = sel.first
        } else {
            buffer = ""
            caret = 0
        }
        selAnchor = null
        pushText()
    }
    fun paste() {
        injector.buzz()
        blinkReset++
        val clip = clipboard.getText()?.text ?: return
        if (clip.isEmpty()) return
        val sel = selRange()
        if (sel != null) {
            buffer = buffer.substring(0, sel.first) + clip + buffer.substring(sel.second)
            caret = sel.first + clip.length
        } else {
            val c = caret.coerceIn(0, buffer.length)
            buffer = buffer.substring(0, c) + clip + buffer.substring(c)
            caret = c + clip.length
        }
        selAnchor = null
        pushText()
    }
    fun clearAll() {
        if (buffer.isEmpty()) return
        injector.buzz()
        blinkReset++
        clearedBackup = buffer to caret
        buffer = ""
        caret = 0
        selAnchor = null
        pushText()
    }
    fun undoClear() {
        val b = clearedBackup ?: return
        injector.buzz()
        blinkReset++
        buffer = b.first
        caret = b.second.coerceIn(0, b.first.length)
        selAnchor = null
        clearedBackup = null
        pushText()
    }
    fun mirrorSelection() {
        val sel = selRange()
        runCatching {
            val svc = PortalPadAccessibilityService.instance
            if (sel != null) svc?.setFieldSelection(sel.first, sel.second)
            else svc?.setFieldSelection(caret, caret)
        }
    }

    // Blink loop. Restarts whenever blinkReset changes — that's what makes the caret
    // snap solid on a keypress before resuming the ~530ms blink.
    LaunchedEffect(blinkReset) {
        caretVisible = true
        while (true) {
            kotlinx.coroutines.delay(530)
            caretVisible = !caretVisible
        }
    }

    // Vertical scroll for the multi-line mirror, plus the viewport height in px so
    // we can keep the caret's line in view as you type / move it.
    val mirrorScroll = rememberScrollState()
    // MEASURED, not derived: the field is elastic now, so its height is known
    // only at layout. Seeded with the old fixed height until the first pass.
    var mirrorViewportPx by remember {
        androidx.compose.runtime.mutableFloatStateOf(0f)
    }
    with(androidx.compose.ui.platform.LocalDensity.current) {
        if (mirrorViewportPx == 0f) mirrorViewportPx = MIRROR_HEIGHT.toPx()
    }
    // True while the caret handle is being dragged, so the caret renders solid (not
    // blinking) and stays visible under your finger as you scrub it.
    var caretDragging by remember { mutableStateOf(false) }
    // Auto-fading scrollbar thumb: visible while scrolling/typing, fades after a beat.
    val scrollbarAlpha = remember { Animatable(0f) }
    LaunchedEffect(mirrorScroll.value, buffer) {
        if (mirrorScroll.maxValue > 0) {
            scrollbarAlpha.snapTo(1f)
            kotlinx.coroutines.delay(900)
            scrollbarAlpha.animateTo(0f, tween(400))
        }
    }
    // Undo strip lifetime: auto-dismiss after 4s, and dismiss the moment new text is
    // typed (so an accidental Undo can't clobber what you just started writing).
    LaunchedEffect(clearedBackup) {
        if (clearedBackup != null) {
            kotlinx.coroutines.delay(4000)
            clearedBackup = null
        }
    }
    LaunchedEffect(buffer) {
        if (clearedBackup != null && buffer.isNotEmpty()) clearedBackup = null
    }
    LaunchedEffect(caret, caretLayout, buffer) {
        val l = caretLayout ?: return@LaunchedEffect
        val safe = caret.coerceIn(0, buffer.length)
        val rect = runCatching { l.getCursorRect(safe) }.getOrNull() ?: return@LaunchedEffect
        val cur = mirrorScroll.value.toFloat()
        when {
            rect.top < cur ->
                runCatching { mirrorScroll.scrollTo(rect.top.toInt().coerceAtLeast(0)) }
            rect.bottom > cur + mirrorViewportPx ->
                runCatching { mirrorScroll.scrollTo((rect.bottom - mirrorViewportPx).toInt().coerceAtLeast(0)) }
        }
    }

    // ELASTIC (2026-07-23): the mirror weight-fills whatever the fixed rows leave
    // over. Keys are fixed-size and NEVER shrink; anything that appears above
    // (the suggestions row, the picker) shrinks THIS field instead - SH's
    // priority, stated exactly. This replaced two generations of estimate-based
    // sizing whose overhead constant was wrong in both directions at once
    // (dead gap below the clip row + shrunken keys, SH screenshots).
    // The field is wrapped in a non-clipping Box so the magnifier bubble (added
    // right after the Surface) can render ABOVE the field — same window as the
    // drag — outside the field's rounded clip.
    if (showMirror) Box(Modifier.fillMaxWidth().weight(1f)) {
    // Mirror bar: read-only multi-line view of the field's text with our caret +
    // move arrows. Wraps long text and scrolls vertically when it overflows.
    Surface(color = PhoneSurface, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp).fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .onGloballyPositioned { mirrorViewportPx = it.size.height.toFloat() }
                    .pointerInput(buffer) {
                        // Tap anywhere in the field — including blank space past the
                        // text or below the last line — to place the caret. Map the
                        // touch into text space (add the vertical scroll offset);
                        // getOffsetForPosition picks the right line + char and clamps
                        // to the end when you tap past everything.
                        detectTapGestures(
                            onLongPress = { pos ->
                                val l = caretLayout
                                if (buffer.isNotEmpty() && l != null) {
                                    val ty = pos.y + mirrorScroll.value
                                    val off = l.getOffsetForPosition(
                                        androidx.compose.ui.geometry.Offset(pos.x, ty),
                                    )
                                    selectWordAt(off)
                                } else {
                                    injector.buzz(longPress = true)
                                }
                            },
                            onTap = { pos ->
                                val l = caretLayout
                                if (buffer.isNotEmpty() && l != null) {
                                    val ty = pos.y + mirrorScroll.value
                                    val off = l.getOffsetForPosition(
                                        androidx.compose.ui.geometry.Offset(pos.x, ty),
                                    )
                                    setCaret(off)
                                }
                            },
                        )
                    },
                contentAlignment = Alignment.TopStart,
            ) {
                if (buffer.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.width(2.dp).height(22.dp).background(
                                if (caretVisible) PhoneAccent else Color.Transparent,
                            ),
                        )
                        Text(
                            "Type with the keyboard below…",
                            color = PhoneOnSurfaceMuted,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                } else {
                    Box(Modifier.verticalScroll(mirrorScroll).fillMaxWidth()) {
                        Text(
                            text = buffer,
                            color = PhoneOnSurface,
                            style = MaterialTheme.typography.bodyLarge,
                            onTextLayout = { caretLayout = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .drawWithContent {
                                    val l = caretLayout
                                    // Highlight the selection (behind the text).
                                    val a = selAnchor
                                    val selLo = if (a != null) minOf(a, caret).coerceIn(0, buffer.length) else caret
                                    val selHi = if (a != null) maxOf(a, caret).coerceIn(0, buffer.length) else caret
                                    val hasSel = a != null && selLo != selHi
                                    if (l != null && hasSel) {
                                        drawPath(
                                            l.getPathForRange(selLo, selHi),
                                            color = PhonePrimary.copy(alpha = 0.40f),
                                        )
                                    }
                                    drawContent()
                                    // Caret only when there is no active selection.
                                    if (l != null &&
                                        (caretVisible || caretDragging) &&
                                        !hasSel
                                    ) {
                                        val safe = caret.coerceIn(0, buffer.length)
                                        val rect = runCatching { l.getCursorRect(safe) }.getOrNull()
                                        if (rect != null) {
                                            drawRect(
                                                color = PhoneAccent,
                                                topLeft = androidx.compose.ui.geometry.Offset(rect.left, rect.top),
                                                size = androidx.compose.ui.geometry.Size(2.dp.toPx(), rect.height),
                                            )
                                        }
                                    }
                                },
                        )
                    }
                }
                // Draggable selection handles — one at each end of the highlight.
                // Mirror-on-release: the on-screen selection updates live as you drag,
                // and the glasses field is re-selected only when you lift (in onRelease).
                val hAnchor = selAnchor
                val hLo = if (hAnchor != null) minOf(hAnchor, caret).coerceIn(0, buffer.length) else caret
                val hHi = if (hAnchor != null) maxOf(hAnchor, caret).coerceIn(0, buffer.length) else caret
                val hLayout = caretLayout
                if (buffer.isNotEmpty() && hAnchor != null && hLo != hHi && hLayout != null) {
                    val loRect = runCatching { hLayout.getCursorRect(hLo) }.getOrNull()
                    val hiRect = runCatching { hLayout.getCursorRect(hHi) }.getOrNull()
                    if (loRect != null) {
                        SelectionHandle(
                            cursorRect = loRect,
                            scrollPx = mirrorScroll.value,
                            currentOffset = { selRange()?.first ?: caret },
                            layout = { caretLayout },
                            onGrab = {
                                injector.buzz()
                                selRange()?.let { selAnchor = it.second; caret = it.first }
                                caretDragging = true
                            },
                            onMoveTo = { off ->
                                caret = off.coerceIn(0, buffer.length)
                            },
                            onRelease = {
                                caretDragging = false
                                mirrorSelection()
                            },
                        )
                    }
                    if (hiRect != null) {
                        SelectionHandle(
                            cursorRect = hiRect,
                            scrollPx = mirrorScroll.value,
                            currentOffset = { selRange()?.second ?: caret },
                            layout = { caretLayout },
                            onGrab = {
                                injector.buzz()
                                selRange()?.let { selAnchor = it.first; caret = it.second }
                                caretDragging = true
                            },
                            onMoveTo = { off ->
                                caret = off.coerceIn(0, buffer.length)
                            },
                            onRelease = {
                                caretDragging = false
                                mirrorSelection()
                            },
                        )
                    }
                } else if (buffer.isNotEmpty() && hLayout != null) {
                    // No selection: a single draggable handle under the caret so it can be
                    // slid to an exact spot (taps still place it; this fine-tunes). The
                    // on-screen caret follows the finger live; the glasses-field cursor is
                    // synced once on release (pushCaret), matching the selection handles.
                    val caretRect = runCatching {
                        hLayout.getCursorRect(caret.coerceIn(0, buffer.length))
                    }.getOrNull()
                    if (caretRect != null) {
                        SelectionHandle(
                            cursorRect = caretRect,
                            scrollPx = mirrorScroll.value,
                            currentOffset = { caret },
                            layout = { caretLayout },
                            onGrab = { injector.buzz(); blinkReset++; caretDragging = true },
                            onMoveTo = { off ->
                                caret = off.coerceIn(0, buffer.length)
                                blinkReset++
                            },
                            onRelease = {
                                caretDragging = false
                                pushCaret()
                            },
                        )
                    }
                }
                // Auto-fading scrollbar thumb on the right edge — shown only when the
                // text overflows the field. Geometry from mirrorScroll; fades via alpha.
                if (mirrorScroll.maxValue > 0) {
                    val dens = LocalDensity.current
                    val trackPx = mirrorViewportPx
                    val minThumb = with(dens) { 18.dp.toPx() }
                    val thumbPx = (trackPx * trackPx / (trackPx + mirrorScroll.maxValue))
                        .coerceIn(minThumb, trackPx)
                    val span = (trackPx - thumbPx).coerceAtLeast(0f)
                    val topPx = (mirrorScroll.value.toFloat() / mirrorScroll.maxValue) * span
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .offset { IntOffset(0, topPx.roundToInt()) }
                            .width(3.dp)
                            .height(with(dens) { thumbPx.toDp() })
                            .clip(RoundedCornerShape(2.dp))
                            .background(PhoneOnSurfaceMuted.copy(alpha = 0.6f * scrollbarAlpha.value)),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            val canLeft = caret > 0
            val canRight = caret < buffer.length
            Column(
                Modifier.fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    Modifier.width(42.dp).weight(1f).clip(RoundedCornerShape(8.dp))
                        .background(if (canLeft) PhoneSurfaceElevated else PhoneSurface)
                        .holdRepeat(scope, canLeft) { moveCaret(-1) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "◄",
                        color = if (canLeft) PhonePrimaryBright else PhoneOnSurfaceMuted,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Box(
                    Modifier.width(42.dp).weight(1f).clip(RoundedCornerShape(8.dp))
                        .background(if (canRight) PhoneSurfaceElevated else PhoneSurface)
                        .holdRepeat(scope, canRight) { moveCaret(1) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "►",
                        color = if (canRight) PhonePrimaryBright else PhoneOnSurfaceMuted,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }
    // In-window magnifier bubble: same window as the drag (no Popup → no gesture cancel),
    // sits just above the field via a negative offset into the slack above it. The bubble
    // stays centred over the field; the zoom transform centres the dragged caret offset
    // inside it, so it shows the exact spot regardless of the bubble's own position.
    val magLayout = caretLayout
    if (caretDragging && magLayout != null) {
        val cr = runCatching { magLayout.getCursorRect(caret.coerceIn(0, buffer.length)) }.getOrNull()
        if (cr != null) {
            val zoom = 1.8f
            val crCenterY = (cr.top + cr.bottom) / 2f
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .offset { IntOffset(0, -(56.dp.roundToPx() + 10.dp.roundToPx())) }
                    .size(132.dp, 56.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(PhoneSurfaceElevated)
                    .border(1.dp, PhonePrimary.copy(alpha = 0.6f), RoundedCornerShape(10.dp)),
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    translate(cx - zoom * cr.left, cy - zoom * crCenterY) {
                        scale(zoom, zoom, pivot = Offset.Zero) {
                            val a = selAnchor
                            val selLo = if (a != null) minOf(a, caret).coerceIn(0, buffer.length) else caret
                            val selHi = if (a != null) maxOf(a, caret).coerceIn(0, buffer.length) else caret
                            val hasSel = a != null && selLo != selHi
                            if (hasSel) {
                                drawPath(magLayout.getPathForRange(selLo, selHi), color = PhonePrimary.copy(alpha = 0.40f))
                            }
                            drawText(magLayout)
                            if (!hasSel) {
                                drawRect(
                                    color = PhoneAccent,
                                    topLeft = Offset(cr.left, cr.top),
                                    size = androidx.compose.ui.geometry.Size(1.5.dp.toPx(), cr.height),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    }

    // Voice dictation. Plays a short activation beep + haptic so it's clear the mic is
    // live, then starts the recognizer; the result is inserted at the caret like a paste.
    // Android's recognizer is single-utterance, so continuous mode (continuousMic) just
    // re-arms it after each phrase. playBeep is false on those re-arms so the tone/haptic
    // don't fire every phrase.
    fun startVoice(playBeep: Boolean = true) {
        val avail = android.speech.SpeechRecognizer.isRecognitionAvailable(ctx)
        if (!avail) {
            voiceMsg = "Voice recognition unavailable on this device"
            listening = false
            continuousMic = false
            return
        }
        voiceMsg = null
        val tone = if (playBeep) {
            injector.buzz()
            runCatching {
                android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 80)
            }.getOrNull().also { runCatching { it?.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 150) } }
        } else {
            null
        }
        runCatching { speechRef.value?.destroy() }
        val sr = android.speech.SpeechRecognizer.createSpeechRecognizer(ctx)
        speechRef.value = sr
        sr.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onReadyForSpeech(params: android.os.Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buf: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                // If the user already stopped (tapped the mic to cancel), this is just the
                // recognizer's parting no-match from stopListening() — not a real failure.
                // Ignore it so it doesn't flash a red "didn't catch that" after a clean stop.
                if (!listening) return
                // In continuous mode a silence error (no match / speech timeout) is normal
                // between phrases — re-arm rather than stop, unless we've been silent for a
                // minute (auto-timeout backstop). Any other error ends the session cleanly.
                val silence = error == android.speech.SpeechRecognizer.ERROR_NO_MATCH ||
                    error == android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                when {
                    continuousMic && silence &&
                        System.currentTimeMillis() - lastResultAt.value <= 60_000L -> {
                        startVoice(playBeep = false)
                    }
                    continuousMic && silence -> {
                        listening = false
                        continuousMic = false
                        voiceMsg = "Continuous mic stopped after a minute of silence"
                    }
                    else -> {
                        listening = false
                        continuousMic = false
                        voiceMsg = "Didn't catch that — tap the mic to try again (err $error)"
                    }
                }
            }
            override fun onPartialResults(partialResults: android.os.Bundle?) {}
            override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
            override fun onResults(results: android.os.Bundle?) {
                // Drop a result that arrives after a clean stop (see onError note above).
                if (!listening) return
                val text = results
                    ?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                if (text.isNotEmpty()) {
                    lastResultAt.value = System.currentTimeMillis()
                    val prefix = if (buffer.isEmpty() || buffer.endsWith(" ")) "" else " "
                    insert(prefix + text)
                }
                if (continuousMic) {
                    startVoice(playBeep = false)
                } else {
                    listening = false
                }
            }
        })
        val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(android.speech.RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
        }
        listening = true
        // Start after the beep finishes (so the tone isn't transcribed); re-arms have no
        // beep so they only need a short gap to let the previous recognizer release.
        scope.launch {
            kotlinx.coroutines.delay(if (playBeep) 220 else 80)
            runCatching { tone?.release() }
            // If a stop landed during the gap, don't restart.
            if (!listening) return@launch
            runCatching { sr.startListening(intent) }
                .onFailure {
                    listening = false
                    continuousMic = false
                    voiceMsg = "Couldn't start the microphone"
                }
        }
    }
    fun stopVoice() {
        continuousMic = false
        runCatching { speechRef.value?.stopListening() }
        listening = false
    }
    // Mic permission helpers, shared by the tap and long-press handlers.
    fun micPermitted(): Boolean =
        ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    fun promptMicPermission() {
        runCatching {
            ctx.startActivity(
                android.content.Intent(ctx, MicPermissionActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        voiceMsg = "Grant microphone permission on your phone, then tap the mic again"
    }
    // Gesture mode helpers. commitGesture recognizes the drawn strokes and types the
    // best letter; scheduleCommit fires it after a short pause (so multi-stroke letters
    // like E/F/A finish first); chooseGestureCandidate swaps in an alternate guess.
    fun commitGesture() {
        if (strokes.isEmpty()) return
        val snapshot = strokes.map { it.toList() }
        gestureInk.recognize(snapshot) { cands ->
            // A–Z mode: prefer single-letter guesses; fall back to whatever came back.
            // Case is forced by the Caps toggle, not ML Kit's (unreliable) returned case.
            fun cased(s: String) = if (gestureCaps) s.uppercase() else s.lowercase()
            val letters = cands.filter { it.length == 1 && it[0].isLetter() }
            val pick = letters.firstOrNull() ?: cands.firstOrNull()
            if (pick != null && pick.isNotEmpty()) {
                insert(cased(pick))
                lastCommitLen = pick.length
                gestureCandidates = (if (letters.isNotEmpty()) letters else cands)
                    .take(6).map { cased(it) }
                gestureMsg = null
            } else if (gestureReady) {
                gestureMsg = "Didn't recognize that — try again"
            }
            strokes.clear()
            currentStroke = emptyList()
            gestureGen++
        }
    }
    fun scheduleCommit() {
        gestureCommitJob.value?.cancel()
        gestureCommitJob.value = scope.launch {
            kotlinx.coroutines.delay(750)
            commitGesture()
        }
    }
    fun cancelCommit() {
        gestureCommitJob.value?.cancel()
        gestureCommitJob.value = null
    }
    fun clearGesture() {
        cancelCommit()
        strokes.clear()
        currentStroke = emptyList()
        gestureCandidates = emptyList()
        gestureGen++
    }
    fun chooseGestureCandidate(c: String) {
        if (c.isEmpty()) return
        injector.buzz()
        blinkReset++
        val cur = caret.coerceIn(0, buffer.length)
        val start = (cur - lastCommitLen).coerceAtLeast(0)
        buffer = buffer.substring(0, start) + c + buffer.substring(cur)
        caret = start + c.length
        lastCommitLen = c.length
        selAnchor = null
        pushText()
    }

    // Gap below the mirror; gone with the mirror on the floating keyboard.
    if (showMirror) Spacer(Modifier.height(12.dp))
    // Clipboard actions. Select all highlights the whole field (mirrored to the glasses
    // field); Cut/Copy act on the selection or, if none, the whole text; Paste drops the
    // clipboard at the caret; the trash icon clears the field. While an Undo is on offer
    // the strip replaces this row (so the icon buttons stay a fixed narrow width, the four
    // text labels keep their room).
    if (clearedBackup != null) {
        Row(
            Modifier.fillMaxWidth().height(40.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(9.dp))
                    .background(PhoneSurfaceElevated),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    "Field cleared",
                    color = PhoneOnSurfaceMuted,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
            }
            Box(
                Modifier
                    .width(104.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(9.dp))
                    .background(PhonePrimary)
                    .clickable { undoClear() },
                contentAlignment = Alignment.Center,
            ) {
                Text("Undo", color = PhoneOnSurface, style = MaterialTheme.typography.labelMedium)
            }
        }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            KbClipButton("Select all", weight = 1.5f) { selectAll() }
            KbClipButton("Cut") { cut() }
            KbClipButton("Copy") { copy() }
            KbClipButton("Paste") { paste() }
            KbClipButton(
                "Mic",
                active = listening,
                icon = Icons.Filled.Mic,
                fixedWidth = 40.dp,
                restingColor = PhoneMicTint,
                iconTint = PhonePrimaryBright,
                onLongClick = {
                    // Press-and-hold = latched continuous dictation.
                    if (!listening) {
                        if (micPermitted()) {
                            injector.buzz(longPress = true)
                            continuousMic = true
                            lastResultAt.value = System.currentTimeMillis()
                            startVoice()
                        } else {
                            promptMicPermission()
                        }
                    }
                },
            ) {
                // Tap = stop if live, else a single phrase that auto-stops.
                injector.buzz()
                when {
                    listening -> stopVoice()
                    micPermitted() -> {
                        continuousMic = false
                        startVoice()
                    }
                    else -> promptMicPermission()
                }
            }
            KbClipButton(
                "Clear all",
                icon = Icons.Filled.Delete,
                fixedWidth = 40.dp,
                restingColor = PhoneClearTint,
                iconTint = PhoneClearIcon,
            ) { clearAll() }
        }
    }
    if (voiceMsg != null) {
        Text(
            voiceMsg ?: "",
            color = PhoneDanger,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        )
    }

    // The keys.
    val letterRows = listOf(
        listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
        listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
        listOf("z", "x", "c", "v", "b", "n", "m"),
    )
    // Shared with the on-display keyboard - one symbol set for every PortalPad
    // keyboard. Digits live in the persistent number row (both modes) instead of
    // being page 1's first row.
    val symbolRows =
        if (symPage2) com.portalpad.app.presentation.SYM_PAGE_2
        else com.portalpad.app.presentation.SYM_PAGE_1
    val rows = if (symbols) symbolRows else letterRows

    // Label for a letter key: shift only (accent layers removed with Swap layer).
    fun keyLabel(k: String): String {
        if (symbols) return k
        return if (shift) k.uppercase() else k
    }
    // Record an emoji into the persisted recent list (most-recent-first, deduped, capped).
    fun recordRecent(e: String) {
        val updated = (listOf(e) + recent.filter { it != e }).take(32)
        recent = updated
        prefs.edit().putString("recent_emoji", updated.joinToString("\n")).apply()
    }

    val emojiScroll = rememberScrollState()
    // Bottom slack: mirrors the top spacer so the field + clipboard group sits centered
    // in the space above the keyboard, which stays pinned to the bottom. The extra fixed
    // 16dp matches the outer Column's spacedBy(16) gap that sits ABOVE this BoxWithConstraints
    // (between the keyboard-source toggle and the field) — without it the top gap would read
    // 16dp larger than the bottom gap and the group would look low rather than centered.
    // No slack spacers at all any more: the elastic mirror above absorbs every
    // spare dp, so the clip row and keys pack at the bottom by construction.
    Spacer(Modifier.height(16.dp))
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        if (listening) {
            // Listening panel replaces the keyboard while the mic is live.
            val inf = rememberInfiniteTransition(label = "mic")
            val pulse by inf.animateFloat(
                initialValue = 1f,
                targetValue = 1.12f,
                animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
                label = "pulse",
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(LocalKbKeyHeight.current * 5)
                    .clip(RoundedCornerShape(12.dp))
                    .background(PhoneSurface),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        Modifier.size(64.dp).scale(pulse).clip(CircleShape).background(PhonePrimary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Mic,
                            contentDescription = "Microphone",
                            tint = PhoneOnSurface,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                    Text(
                        if (continuousMic) "Listening — continuous" else "Listening…",
                        color = PhoneOnSurface,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        if (continuousMic) "Keep talking — tap the mic to stop" else "Tap the mic again to stop",
                        color = PhoneOnSurfaceMuted,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        } else if (gesture) {
            // Candidate / status row.
            Row(
                Modifier.fillMaxWidth().height(34.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    gestureMsg != null -> Text(
                        gestureMsg ?: "",
                        color = if (gestureReady) PhoneOnSurfaceMuted else PhoneAccent,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    gestureCandidates.isNotEmpty() -> {
                        gestureCandidates.forEachIndexed { i, c ->
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(if (i == 0) PhonePrimary else PhoneSurfaceElevated)
                                    .clickable { chooseGestureCandidate(c) }
                                    .padding(horizontal = 12.dp, vertical = 5.dp),
                            ) {
                                Text(c, color = PhoneOnSurface, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                    else -> Text(
                        "Draw a letter",
                        color = PhoneOnSurfaceMuted,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            // Drawing canvas.
            val canvasH = LocalKbKeyHeight.current * 4
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(canvasH)
                    .clip(RoundedCornerShape(12.dp))
                    .background(PhoneSurface)
                    .pointerInput(gestureGen) {
                        awaitEachGesture {
                            val first = awaitFirstDown()
                            cancelCommit()
                            gestureCandidates = emptyList()
                            val pts = ArrayList<InkPoint>()
                            val t0 = System.currentTimeMillis()
                            pts.add(InkPoint(first.position.x, first.position.y, 0L))
                            currentStroke = ArrayList(pts)
                            first.consume()
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull()
                                if (change == null || !change.pressed) break
                                pts.add(
                                    InkPoint(
                                        change.position.x,
                                        change.position.y,
                                        System.currentTimeMillis() - t0,
                                    ),
                                )
                                currentStroke = ArrayList(pts)
                                change.consume()
                            }
                            strokes.add(ArrayList(pts))
                            currentStroke = emptyList()
                            scheduleCommit()
                        }
                    },
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val allStrokes = strokes + listOf(currentStroke)
                    for (pts in allStrokes) {
                        if (pts.size < 2) continue
                        val path = Path()
                        path.moveTo(pts[0].x, pts[0].y)
                        for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)
                        drawPath(
                            path,
                            color = PhonePrimaryBright,
                            style = Stroke(width = 9f, cap = StrokeCap.Round),
                        )
                    }
                }
                if (!gestureReady) {
                    Text(
                        gestureMsg ?: "Preparing handwriting…",
                        color = if (gestureFailed) PhoneAccent else PhoneOnSurfaceMuted,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(horizontal = 24.dp)
                            .then(
                                if (gestureFailed) {
                                    Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { prepareGesture() }
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                } else {
                                    Modifier
                                }
                            ),
                    )
                } else if (strokes.isEmpty() && currentStroke.isEmpty()) {
                    Text(
                        "✍  draw here",
                        color = PhoneOnSurfaceMuted,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
            // Gesture-mode bottom row.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                KbKey("ABC", weight = 2f, bg = PhoneSurface, fg = PhoneOnSurfaceMuted) {
                    injector.buzz()
                    clearGesture()
                    gesture = false
                }
                KbKey(
                    "Caps",
                    weight = 1.7f,
                    bg = if (gestureCaps) PhonePrimary else PhoneSurface,
                    fg = if (gestureCaps) PhoneOnSurface else PhoneOnSurfaceMuted,
                ) {
                    injector.buzz()
                    gestureCaps = !gestureCaps
                }
                KbKey("clear", weight = 1.6f, bg = PhoneSurface, fg = PhoneOnSurfaceMuted) {
                    injector.buzz()
                    clearGesture()
                }
                KbKey("space", weight = 2.5f, fg = PhoneOnSurfaceMuted, repeatScope = scope) { insert(" ") }
                KbKey("⌫", weight = 1.6f, bg = PhoneSurface, fg = PhoneDanger, repeatScope = scope) { backspace() }
            }
        } else if (emoji) {
            // Emoji tabs (Recent first, then the fixed categories).
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                EMOJI_TABS.forEachIndexed { i, glyph ->
                    Box(
                        Modifier
                            .weight(1f)
                            .height(34.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(if (i == emojiCat) PhonePrimary else PhoneSurfaceElevated)
                            .clickable { injector.buzz(); emojiCat = i },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(glyph, fontSize = 17.sp)
                    }
                }
            }
            // Grid for the selected category (8 columns, scrolls vertically).
            val emojiList = if (emojiCat == 0) recent else EMOJI_CATEGORIES[emojiCat - 1]
            val gridH = LocalKbKeyHeight.current * 4
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(gridH)
                    .clip(RoundedCornerShape(10.dp))
                    .background(PhoneSurface),
            ) {
                if (emojiList.isEmpty()) {
                    Text(
                        "No recent emoji yet",
                        color = PhoneOnSurfaceMuted,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    Column(
                        Modifier.fillMaxWidth().verticalScroll(emojiScroll).padding(4.dp),
                    ) {
                        emojiList.chunked(8).forEach { rowItems ->
                            Row(Modifier.fillMaxWidth()) {
                                rowItems.forEach { e ->
                                    Box(
                                        Modifier
                                            .weight(1f)
                                            .height(LocalKbKeyHeight.current)
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable { insert(e); recordRecent(e) },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(e, fontSize = 22.sp)
                                    }
                                }
                                repeat(8 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
            // Emoji-mode bottom row: back to letters, space, backspace.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                KbKey("ABC", weight = 2f, bg = PhoneSurface, fg = PhoneOnSurfaceMuted) {
                    injector.buzz()
                    emoji = false
                }
                KbKey("space", weight = 4f, fg = PhoneOnSurfaceMuted, repeatScope = scope) { insert(" ") }
                KbKey("⌫", weight = 2f, bg = PhoneSurface, fg = PhoneDanger, repeatScope = scope) { backspace() }
            }
        } else {
        // Quick-insert dropdowns: the keyboard's own first row (inherits the 7dp
        // row rhythm, so the gap below matches every key row - SH's spec). Lives
        // inside the keyboard branch, so the emoji/gesture/mic panels replace it
        // along with the keys. Menus open UPWARD here (no room below), hence the
        // up chevron on the pills.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            QuickInsertDropdown(
                label = ".com",
                items = listOf(".com", ".net", ".org", ".io", ".co", ".app", ".dev", ".gg"),
                modifier = Modifier.weight(1f),
            ) { insert(it) }
            QuickInsertDropdown(
                label = "@mail",
                items = listOf(
                    "@gmail.com", "@yahoo.com", "@outlook.com", "@icloud.com",
                    "@proton.me", "@hotmail.com",
                ),
                modifier = Modifier.weight(1f),
            ) { insert(it) }
            QuickInsertDropdown(
                label = "https://",
                items = listOf("https://", "http://", "www.", "ftp://"),
                modifier = Modifier.weight(1f),
            ) { insert(it) }
        }
        // Persistent number row in BOTH modes: the paged symbol layout (shared with
        // the on-display keyboard) has no digit row of its own.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0").forEach { d ->
                KbKey(d, bg = PhoneSurface, fg = PhonePrimaryBright, repeatScope = scope, preview = true) { insert(d) }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            rows[0].forEach { k ->
                KbKey(
                    keyLabel(k),
                    repeatScope = scope,
                    preview = true,
                ) { insert(keyLabel(k)) }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = if (symbols) 0.dp else 14.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            rows[1].forEach { k ->
                KbKey(
                    keyLabel(k),
                    repeatScope = scope,
                    preview = true,
                ) { insert(keyLabel(k)) }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            if (symbols) {
                // Symbols pager, in the shift key's slot - "1/2" shows the page you
                // are ON; tapping flips (same convention as the on-display keyboard).
                KbKey(
                    if (symPage2) "2/2" else "1/2",
                    weight = 1.5f, bg = PhoneSurface, fg = PhonePrimaryBright,
                ) {
                    injector.buzz()
                    symPage2 = !symPage2
                }
            } else {
                KbKey(
                    if (capsLock) "⇪" else "⇧",
                    weight = 1.5f,
                    bg = if (capsLock) PhonePrimary else PhoneSurface,
                    fg = if (capsLock) PhoneOnSurface else if (shift) PhoneAccent else PhonePrimaryBright,
                ) {
                    injector.buzz()
                    val now = android.os.SystemClock.uptimeMillis()
                    if (capsLock) {
                        capsLock = false
                        shift = false
                        lastShiftTapAt = 0L
                    } else if (now - lastShiftTapAt <= CAPS_LOCK_DOUBLE_TAP_MS) {
                        capsLock = true
                        shift = true
                        lastShiftTapAt = 0L
                    } else {
                        shift = !shift
                        lastShiftTapAt = now
                    }
                }
            }
            rows[2].forEach { k ->
                KbKey(
                    keyLabel(k),
                    repeatScope = scope,
                    preview = true,
                ) { insert(keyLabel(k)) }
            }
            KbKey("⌫", weight = 1.5f, bg = PhoneSurface, fg = PhoneDanger, repeatScope = scope) { backspace() }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            // "#@!", not "?123" (SH): the digit row is always visible now, so a
            // label promising numbers described keys the toggle no longer reveals.
            KbKey(if (symbols) "ABC" else "#@!", weight = 1.5f, bg = PhoneSurface, fg = PhoneOnSurfaceMuted) {
                injector.buzz()
                symbols = !symbols
                // Always land on page 1 when entering (or re-entering) symbols.
                symPage2 = false
            }
            if (!symbols) {
                KbKey("🙂", weight = 1f, bg = PhoneSurface) {
                    android.util.Log.w("DIAG-RELAY", "emoji mode -> true")
                    injector.buzz()
                    emoji = true
                }
                KbKey("✍", weight = 1f, bg = PhoneSurface) {
                    injector.buzz()
                    gesture = true
                }
            }
            KbKey(",", weight = 1f, repeatScope = scope, preview = true) { insert(",") }
            KbKey("space", weight = 2.8f, fg = PhoneOnSurfaceMuted, repeatScope = scope) { insert(" ") }
            KbKey(".", weight = 1f, repeatScope = scope, preview = true) { insert(".") }
            KbKey("↵", weight = 1.6f, bg = PhoneAccent, fg = PhoneBackground) {
                injector.buzz(longPress = true)
                injector.submitSearch()
                injector.pressKey(KeyEvent.KEYCODE_ENTER)
            }
        }
        }
    }
}

/**
 * Map a Char to a KeyEvent and inject. Uses the system's VIRTUAL_KEYBOARD
 * [android.view.KeyCharacterMap] to resolve ANY mappable character to its key +
 * shift metaState, then presses it via [InputInjector.pressKeyWithMeta] (which
 * carries META_SHIFT_ON on the key events, the part text widgets actually read).
 *
 * History: the old hand-rolled table silently DROPPED every symbol not in it
 * (@ # $ _ & + ( ) * " : ! ? — the whole ?123 second/third rows), and typed
 * capitals as lowercase because it "held" shift as a separate press-RELEASE
 * that completed before the letter went down. Field report: "keys send the
 * wrong keys" in both ?123 and ABC mode.
 */
// LIVE KEY FORWARDING — DISABLED (root cause of "Android tab types ONE letter
// then dies", full log 2026-07-17 15:43:09.988): forwarding a typed character
// (or a DEL) as an injected key event onto the EXTERNAL display trips this
// ROM's key-on-unfocused-display policy — PhoneWindowManager
// .interceptKeyBeforeQueueing → moveDisplayToTopInternal → InputDispatcher
// "Focused display: 0 -> 40" — the relay window loses WINDOW focus and the
// system keyboard's InputConnection goes permanently INACTIVE
// ("setComposingText on inactive InputConnection" on every later key). The
// injector was OUR ShellUserService (uid 2000/shell, the PortalPadHud
// process), NOT HoneyBoard. The old rationale ("keystrokes never touch the
// external field's input focus → no IME-target migration") was true at the
// IMMS layer but missed this display-focus layer beneath it — and it also
// explains the historical "can only delete one letter" on this tab. All
// Android-tab edits now coalesce into the debounced a11y SET_TEXT write,
// which never enters InputDispatcher; its IME-target migration is handled by
// the post-write editor-focus re-request. Tradeoff: the external field
// updates ~450ms after a typing pause instead of live per key. Flip this
// back only with fresh log evidence.
private const val FORWARD_LIVE_KEYSTROKES = false
private const val CAPS_LOCK_DOUBLE_TAP_MS = 400L

// FLUSHES ARE PAUSE-GATED — the rolling ceiling is deliberately GONE.
// Evidence (full log 2026-07-17 22:11): once a write migrates the IME target
// to the external field, every HoneyBoard KEYPRESS re-steals display focus
// (handleTapOutsideFocusInsideSelf — the keyboard's own keys become
// external-display territory until the re-target completes), so recovery can
// never outrun actively-moving fingers. The rolling ceiling guaranteed a
// write mid-burst every ~700ms = guaranteed casualties ("types 7-10 chars
// then stops"). The ONLY stable regime while keys are flowing is ZERO writes;
// one write per genuine pause, with the recovery chain settling inside the
// pause. The pause threshold is the RELAY_PAUSE_FLUSH_MS pref (tunable in
// Settings; default 600ms). Do NOT reintroduce mid-typing writes without new
// evidence that the ROM stopped treating IME taps this way.

private val VIRTUAL_KEYMAP: android.view.KeyCharacterMap by lazy {
    android.view.KeyCharacterMap.load(android.view.KeyCharacterMap.VIRTUAL_KEYBOARD)
}

private fun forwardCharacter(ch: Char, injector: InputInjector) {
    // Fast common paths the keymap also covers, kept explicit for clarity.
    when (ch) {
        ' ' -> { injector.pressKeyWithMeta(KeyEvent.KEYCODE_SPACE, 0); return }
        '\n' -> { injector.pressKeyWithMeta(KeyEvent.KEYCODE_ENTER, 0); return }
    }
    val events = runCatching { VIRTUAL_KEYMAP.getEvents(charArrayOf(ch)) }.getOrNull()
    if (events == null) {
        android.util.Log.w(
            "PortalPadRelay",
            "forwardCharacter: no keymap events for '$ch' (U+${"%04X".format(ch.code)}) — dropped",
        )
        return
    }
    // The keymap returns a full physical sequence (SHIFT down, key down/up,
    // SHIFT up). The principal key's DOWN carries the metaState; pressing it
    // with that metaState is sufficient — text widgets resolve the character
    // from (keyCode, metaState), a physically held shift key isn't required.
    val principal = events.firstOrNull {
        it.action == KeyEvent.ACTION_DOWN &&
            it.keyCode != KeyEvent.KEYCODE_SHIFT_LEFT &&
            it.keyCode != KeyEvent.KEYCODE_SHIFT_RIGHT
    } ?: return
    injector.pressKeyWithMeta(principal.keyCode, principal.metaState)
}

/**
 * Contextual dependency warning — the first row of the relay. Each keyboard path has
 * ONE hard dependency, surfaced only for the SELECTED path so the two never stack:
 *   - Custom keyboard types via the accessibility service (SET_TEXT); if it isn't
 *     connected, typing silently goes nowhere. Tap shell-enables it (the user already
 *     has Shizuku/Root here), then falls back to the accessibility settings screen if
 *     the service still doesn't bind (e.g. an OEM restricted-settings gate).
 *   - Android keyboard forwards every key via Shizuku/Root (pressKey); if privilege
 *     isn't ready, keystrokes go nowhere. Tap opens the same Shizuku popup / root-grant
 *     the mode strip uses (None routes to Start > Setup).
 * Renders nothing once the selected path's dependency is satisfied.
 */
@Composable
private fun RelayDependencyChip(customKeyboard: Boolean) {
    val app = com.portalpad.app.PortalPadApp.instance
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val accessMode by app.prefs.accessMode.collectAsState(
        initial = com.portalpad.app.data.AccessMode.NONE,
    )
    val shizukuStatus by app.shizuku.status.collectAsState()
    val privilegeReady = when (accessMode) {
        com.portalpad.app.data.AccessMode.SHIZUKU ->
            shizukuStatus == com.portalpad.app.shizuku.ShizukuManager.Status.READY
        com.portalpad.app.data.AccessMode.ROOT -> app.root.isReady
        com.portalpad.app.data.AccessMode.NONE -> false
    }

    // The accessibility service exposes a plain static `instance`, not a flow, so it
    // won't recompose on its own. Re-evaluate on every ON_RESUME (returning from the
    // settings screen) and whenever accessTick is bumped after a fix attempt.
    var accessTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) accessTick++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val accessibilityConnected = remember(accessTick) {
        PortalPadAccessibilityService.instance != null
    }

    var showShizukuDialog by remember { mutableStateOf(false) }

    var lead: String? = null
    var rest = ""
    var action = ""
    var onTap: () -> Unit = {}

    if (customKeyboard) {
        if (!accessibilityConnected) {
            lead = "Accessibility off"
            rest = " — the on-screen keyboard can't reach the field. "
            action = "· Tap to enable"
            onTap = {
                app.injector.buzz()
                scope.launch {
                    runCatching {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            enableAccessibilityViaShell(app)
                        }
                    }
                    // Poll for the service to actually bind; if it never does (privilege
                    // down, or an OEM restricted-settings gate), fall back to the
                    // accessibility settings screen so it can be flipped by hand.
                    var connected = false
                    var i = 0
                    while (i < 6 && !connected) {
                        kotlinx.coroutines.delay(400)
                        accessTick++
                        if (PortalPadAccessibilityService.instance != null) connected = true
                        i++
                    }
                    if (!connected) openAccessibilitySettings(context)
                }
            }
        }
    } else if (!privilegeReady) {
        rest = " — keystrokes won't reach the field. "
        when (accessMode) {
            com.portalpad.app.data.AccessMode.ROOT -> {
                lead = "Root not connected"
                action = "· Tap to fix"
                onTap = {
                    app.injector.buzz()
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        app.root.refresh()
                        if (app.root.isReady) {
                            app.root.grantInjectEvents()
                            app.rootClickBackend.bind()
                        }
                    }
                }
            }
            com.portalpad.app.data.AccessMode.NONE -> {
                lead = "No input source set"
                action = "· Tap to set up"
                onTap = {
                    app.injector.buzz()
                    runCatching {
                        context.startActivity(
                            android.content.Intent(context, com.portalpad.app.MainActivity::class.java)
                                .putExtra(com.portalpad.app.MainActivity.EXTRA_OPEN_SETUP, true)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
            }
            else -> {
                lead = "Shizuku not connected"
                action = "· Tap to fix"
                onTap = {
                    app.injector.buzz()
                    showShizukuDialog = true
                }
            }
        }
    }

    if (lead != null) {
        val warnYellow = Color(0xFFE6B800)
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(warnYellow.copy(alpha = 0.10f))
                .border(1.dp, warnYellow.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                .clickable { onTap() }
                .padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = androidx.compose.ui.Alignment.Top,
        ) {
            Text("⚠", color = warnYellow, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(9.dp))
            Text(
                text = androidx.compose.ui.text.buildAnnotatedString {
                    pushStyle(
                        androidx.compose.ui.text.SpanStyle(
                            color = warnYellow,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                        ),
                    )
                    append(lead!!)
                    pop()
                    pushStyle(androidx.compose.ui.text.SpanStyle(color = PhoneOnSurfaceMuted))
                    append(rest)
                    pop()
                    pushStyle(
                        androidx.compose.ui.text.SpanStyle(
                            color = warnYellow,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                        ),
                    )
                    append(action)
                    pop()
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    if (showShizukuDialog) {
        com.portalpad.app.ui.common.ShizukuControlDialog(onDismiss = { showShizukuDialog = false })
    }
}

private fun enableAccessibilityViaShell(app: com.portalpad.app.PortalPadApp) {
    val comp = "${com.portalpad.app.BuildConfig.APPLICATION_ID}/com.portalpad.app.service.PortalPadAccessibilityService"
    val cur = runCatching {
        app.access.execShell("settings get secure enabled_accessibility_services").trim()
    }.getOrDefault("")
    val list = when {
        cur.isEmpty() || cur == "null" -> comp
        cur.split(":").contains(comp) -> cur
        else -> "$cur:$comp"
    }
    app.access.execShell("settings put secure enabled_accessibility_services $list")
    app.access.execShell("settings put secure accessibility_enabled 1")
}

private fun openAccessibilitySettings(context: android.content.Context) {
    runCatching {
        context.startActivity(
            android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
