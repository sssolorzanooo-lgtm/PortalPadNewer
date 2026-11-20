package com.portalpad.app.ui.common

import android.content.Context
import android.util.DisplayMetrics
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/**
 * Geometry a full-screen overlay needs INSIDE a
 * [androidx.compose.ui.window.Dialog], re-measured whenever the window changes.
 *
 * WHY THIS EXISTS AT ALL (Pixel 8 Pro, 2026-07-25/26):
 *
 * 1. SIZE. The phone UI pins its own density (PinnedDensityActivity,
 *    TARGET_WIDTH_DP = 411) so every device lays out 411dp wide. A Dialog gets
 *    its OWN window, sized from the DISPLAY's configuration — which honours the
 *    user's Display-size setting, not our override. When the two disagree,
 *    `fillMaxSize()` fills `realPx * (deviceDpWidth / 411)`. SH's Pixel reports
 *    a 527dp-wide screen (1008x2244px, density override 306) → canvas 1.28x too
 *    wide → the radial wheels were pushed ~140px right with their lower half
 *    off the bottom edge. The S24 is asymptomatic only because its device width
 *    (~360dp) is NARROWER than 411, so the error pulls content inward.
 *
 * 2. NAV BAR. The trackpad window is fullscreen, so the navigation bar floats
 *    over it and NOTHING dispatches an inset: the wheel's DIAG-INSET read
 *    `dialog=0.0.dp fallback=0.0.dp`. `getInsetsIgnoringVisibility` answers the
 *    real question — how much room does the bar want — even when nothing is
 *    dispatched.
 *
 * 3. RE-MEASUREMENT (the reason this is a listener and not a one-shot read).
 *    SH: with the wheel already OPEN, a screen off→on left it overlapping the
 *    nav bar until the phone was unlocked — but DISMISSING and REOPENING the
 *    wheel while still on the lock screen laid it out correctly. Nothing was
 *    wrong with the query; the composition simply never re-ran, so a value
 *    captured before the lock cycle stuck. A global-layout listener re-samples
 *    on the layout pass that the keyguard transition triggers.
 *    The listener updates state ONLY when the measured pixels actually change,
 *    which is what stops the classic layout→recompose→layout feedback loop.
 *
 * None of this is device-gated: any phone whose Display-size setting yields a
 * dp width above 411 hits (1), and any fullscreen window hits (2).
 */
data class DialogGeometry(
    /** Real display size in the CURRENT composition's dp, or null if unreadable. */
    val screen: DpSize?,
    /** Navigation-bar height in the current composition's dp (0 if none/unknown). */
    val navBottom: Dp,
)

/** Raw pixel sample — compared by value so the listener only pushes real changes. */
private data class GeometryPx(val width: Int, val height: Int, val navBottom: Int)

/** Largest genuine nav-bar height seen this process. A 0 reading is never a real
 *  answer on a device that HAS a bar (it means "nobody dispatched anything"),
 *  so it must not be allowed to collapse the padding; a fresh NON-zero reading
 *  always wins, so a 3-button ↔ gesture switch still applies immediately. */
@Volatile private var lastGoodNavBottomPx: Int = 0

private fun sampleGeometry(ctx: Context): GeometryPx {
    val wm = runCatching {
        ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }.getOrNull() ?: return GeometryPx(0, 0, lastGoodNavBottomPx)

    val metrics = runCatching {
        val m = DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
        m.widthPixels to m.heightPixels
    }.getOrDefault(0 to 0)

    val navPx = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            wm.currentWindowMetrics.windowInsets
                .getInsetsIgnoringVisibility(
                    android.view.WindowInsets.Type.navigationBars(),
                ).bottom
        } else {
            0
        }
    }.getOrDefault(0).coerceAtLeast(0)

    if (navPx > 0) lastGoodNavBottomPx = navPx
    return GeometryPx(metrics.first, metrics.second, if (navPx > 0) navPx else lastGoodNavBottomPx)
}

@Composable
fun rememberDialogGeometry(): DialogGeometry {
    val ctx = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current

    var px by remember(ctx) { mutableStateOf(sampleGeometry(ctx)) }

    DisposableEffect(view, ctx) {
        val observer = view.viewTreeObserver
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            val fresh = sampleGeometry(ctx)
            // Only publish real changes — an unconditional assignment here would
            // recompose on every layout pass and re-trigger layout forever.
            if (fresh != px) px = fresh
        }
        runCatching { observer.addOnGlobalLayoutListener(listener) }
        onDispose {
            runCatching {
                if (observer.isAlive) {
                    observer.removeOnGlobalLayoutListener(listener)
                } else {
                    view.viewTreeObserver.removeOnGlobalLayoutListener(listener)
                }
            }
        }
    }

    val snapshot = px
    return with(density) {
        DialogGeometry(
            screen = if (snapshot.width > 0 && snapshot.height > 0) {
                DpSize(snapshot.width.toDp(), snapshot.height.toDp())
            } else {
                null
            },
            navBottom = snapshot.navBottom.coerceAtLeast(0).toDp(),
        )
    }
}

/** Convenience for callers that only need the size. */
@Composable
fun rememberRealScreenSizeDp(): DpSize? = rememberDialogGeometry().screen

/** Convenience for callers that only need the navigation-bar height. */
@Composable
fun rememberNavigationBarBottomDp(): Dp = rememberDialogGeometry().navBottom
