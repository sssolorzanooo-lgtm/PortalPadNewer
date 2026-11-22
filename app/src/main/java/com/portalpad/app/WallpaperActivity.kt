package com.portalpad.app

import android.os.Bundle
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.res.painterResource
import com.portalpad.app.data.Wallpaper
import kotlinx.coroutines.flow.first

/**
 * Full-screen "home backdrop" shown on the EXTERNAL DISPLAY when it would
 * otherwise be empty (no home app assigned, or all apps closed). Launched onto
 * the virtual display via `am start --display <vdId>` so it behaves like the
 * display's home/launcher — real apps launched later simply cover it.
 *
 * This is a normal PortalPad activity drawing a PortalPad-owned image; it does
 * NOT touch the Android system wallpaper in any way.
 */
class WallpaperActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        val app = applicationContext as PortalPadApp
        // Safety guard: this backdrop is only meant for the EXTERNAL display.
        // If Android ever relocates it to the phone's default display (e.g. when
        // the glasses disconnect and the system migrates the orphaned task), it
        // would hijack the phone screen. Refuse to run there — finish instead.
        if (isOnDefaultDisplay()) { finish(); return }
        // This backdrop has no text fields and must NEVER summon a keyboard.
        //
        // EVIDENCE (kb1.txt, 2026-07-19): with no soft-input mode declared, this
        // window reached IMMS as softInputMode=STATE_UNSPECIFIED|ADJUST_PAN, and
        // Samsung decided for itself — "startInputOrWindowGainedFocus ...
        // mSelfReportedDisplayId=44" from our own uid, then "IME window vis: 3",
        // i.e. a keyboard on screen with no text field anywhere. It fired every
        // time this window took focus: at session start, and again each time the
        // typing page closed and focus returned here.
        runCatching {
            window.setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN,
            )
            window.decorView.pointerIcon =
                android.view.PointerIcon.getSystemIcon(this, android.view.PointerIcon.TYPE_NULL)
        }
        // Read the selected wallpaper + custom path SYNCHRONOUSLY up front so the
        // very first composition already knows what to draw. Without this, the two
        // prefs flows resolve independently and there's a startup window where
        // `wallpaper` is CUSTOM but `customPath` is still null — and because CUSTOM
        // has no drawable fallback (unlike the presets), it falls through to black
        // and may not repaint on the VD until something re-launches it. Seeding the
        // initial values removes that race. (Presets never hit it: their drawable
        // paints immediately.) One-shot DataStore reads, same pattern the service
        // uses for homeAction.
        // Canvas-aware default: on an ultrawide canvas a fresh setup (no explicit
        // pick) should open on a native ultrawide image, not the cropped standard
        // default. Classify the canvas from the display's real size.
        val canvasUltrawide = runCatching {
            val m = android.util.DisplayMetrics().also { @Suppress("DEPRECATION") display?.getRealMetrics(it) }
            Wallpaper.isUltrawideCanvas(m.widthPixels, m.heightPixels)
        }.getOrDefault(false)
        val initialRaw: Wallpaper? = runCatching {
            kotlinx.coroutines.runBlocking { app.prefs.homeWallpaperRaw(canvasUltrawide).first() }
        }.getOrNull()
        val initialCustomPath = runCatching {
            kotlinx.coroutines.runBlocking { app.prefs.customWallpaperPath.first() }
        }.getOrNull()
        setContent {
            // LIVE canvas classification, keyed on the configuration: a
            // resolution switch resizes the VD IN PLACE and (per the manifest's
            // configChanges=screenSize) does NOT recreate this activity — so the
            // onCreate-time classification went stale and the STANDARD wallpaper
            // stuck on an ultrawide canvas (log: scheduleConfigurationChanged
            // delivered to the live record at the 3840x1080 resize, no restart).
            // Compose updates LocalConfiguration on exactly that delivery, so
            // keying the re-read on it re-classifies at every resize, both
            // directions. The onCreate-time value stays as the seed/fallback.
            val cfg = androidx.compose.ui.platform.LocalConfiguration.current
            val canvasUltrawideLive = remember(cfg.screenWidthDp, cfg.screenHeightDp) {
                runCatching {
                    val m = android.util.DisplayMetrics().also {
                        @Suppress("DEPRECATION") display?.getRealMetrics(it)
                    }
                    Wallpaper.isUltrawideCanvas(m.widthPixels, m.heightPixels)
                }.getOrDefault(canvasUltrawide)
            }
            val raw by remember(canvasUltrawideLive) {
                app.prefs.homeWallpaperRaw(canvasUltrawideLive)
            }.collectAsState(initial = initialRaw)
            val customPath by app.prefs.customWallpaperPath.collectAsState(initial = initialCustomPath)
            val wallpaper = raw ?: Wallpaper.effectiveDefault(canvasUltrawideLive)
            // GREY-BACKDROP FIX: on a freshly (re)created VD (e.g. replug) the first
            // presented frame can be the grey window background before the wallpaper
            // Image composes/draws, and the follow-up frame doesn't reliably fire on
            // its own on the VD — leaving a grey backdrop until something relaunches
            // it. Nudge a few extra recomposition+draw passes shortly after launch so
            // the content is guaranteed to paint, with no relaunch/flicker. Harmless
            // once it's already painted (redraws the same content).
            val repaintKick = remember { androidx.compose.runtime.mutableStateOf(0) }
            androidx.compose.runtime.LaunchedEffect(Unit) {
                repeat(4) { kotlinx.coroutines.delay(200); repaintKick.value++ }
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .pointerInput(Unit) {
                        detectVerticalDragGestures { _, dragAmount ->
                            if (dragAmount != 0f) {
                                app.injector.notifyPageScroll(dragAmount)
                            }
                        }
                    },
            ) {
                androidx.compose.runtime.key(repaintKick.value) {
                    val res = wallpaper.drawableRes
                    when {
                    wallpaper == Wallpaper.CUSTOM && customPath != null -> {
                        val bmp = remember(customPath) {
                            runCatching { android.graphics.BitmapFactory.decodeFile(customPath) }.getOrNull()
                        }
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                        }
                    }
                    res != null -> {
                        Image(
                            painter = painterResource(res),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    }
                    // NONE (or CUSTOM with no path) → black background above.
                }
                }
            }
        }
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        val app = applicationContext as? PortalPadApp
        if (app != null &&
            (ev.source and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
        ) {
            if (ev.actionMasked == MotionEvent.ACTION_SCROLL) {
                val v = ev.getAxisValue(MotionEvent.AXIS_VSCROLL)
                if (v != 0f && !app.injector.isLikelyOwnInjectedScroll()) {
                    app.injector.notifyPageScroll(v * 40f)
                }
            }
            if (!app.btMouse.running && app.btMouse.isUserEnabled) {
                val dev = InputDevice.getDevice(ev.deviceId)
                val isRealExternal = dev != null && !dev.isVirtual
                if (isRealExternal) {
                    when (ev.actionMasked) {
                        MotionEvent.ACTION_HOVER_MOVE,
                        MotionEvent.ACTION_MOVE,
                        -> {
                            if (!app.injector.isLikelyOwnInjectedHover(ev.x, ev.y)) {
                                app.injector.syncExternalCursorPositionQuietly(ev.x, ev.y)
                                app.injector.notifyExternalMouseMoving()
                            }
                        }
                        MotionEvent.ACTION_BUTTON_PRESS -> {
                            app.injector.syncExternalCursorPositionQuietly(ev.x, ev.y)
                            when (ev.actionButton) {
                                MotionEvent.BUTTON_PRIMARY -> {
                                    app.injector.leftClick()
                                    return true
                                }
                                MotionEvent.BUTTON_SECONDARY -> {
                                    app.injector.rightClick()
                                    return true
                                }
                            }
                        }
                    }
                }
            }
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        // Re-check on resume: if the task was migrated to the phone display while
        // backgrounded (display removed), bail before the user sees it.
        if (isOnDefaultDisplay()) finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        val wasIntentional = intentionalFinish
        intentionalFinish = false
        if (instance === this) instance = null
        // If the backdrop's TASK was torn down out from under us — e.g. swiped
        // from recents, or swept by trimInactiveRecentTasks when another app is
        // cleared — nothing else relaunches it and the VD goes black behind the
        // overlays. Ask the service to self-heal; it only relaunches while the
        // external is still connected, so a normal unplug (which calls
        // finishIfShowing() → intentional) and any stray destroy after the
        // display is gone are both ignored.
        if (!wasIntentional) {
            com.portalpad.app.service.PortalPadForegroundService.onBackdropDestroyedUnexpectedly()
        }
    }

    private fun isOnDefaultDisplay(): Boolean {
        val displayId = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            runCatching { display?.displayId }.getOrNull()
        } else {
            @Suppress("DEPRECATION")
            runCatching { windowManager.defaultDisplay?.displayId }.getOrNull()
        }
        return displayId == android.view.Display.DEFAULT_DISPLAY
    }

    companion object {
        @Volatile private var instance: WallpaperActivity? = null

        /** Set by [finishIfShowing] so onDestroy can distinguish OUR finish
         *  (external unplug) from an external task removal (recents swipe /
         *  trim). Only the latter triggers a self-heal relaunch. */
        @Volatile private var intentionalFinish = false

        /** Finish the backdrop activity if it's currently showing. Called on
         *  external-display disconnect so the orphaned task can't migrate to the
         *  phone screen. */
        fun finishIfShowing() {
            intentionalFinish = true
            instance?.let { act -> act.runOnUiThread { runCatching { act.finish() } } }
            instance = null
        }

    }
}
