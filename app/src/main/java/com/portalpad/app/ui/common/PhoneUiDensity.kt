package com.portalpad.app.ui.common

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.portalpad.app.PinnedDensityActivity

/**
 * Re-pins PortalPad's phone-only layout inside a separate Compose Dialog/Popup
 * window. Those windows receive Android's live display configuration even when
 * their activity extends [PinnedDensityActivity], which can otherwise resize a
 * radial menu after the user changes Display size, Font size, or smallest width.
 *
 * Never use this around external-display content. The service's dock, top bar,
 * widgets and other overlays have their own display-specific density contract.
 */
@Composable
fun PhoneUiDensityScope(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = remember(
        context,
        configuration.orientation,
        configuration.densityDpi,
        configuration.fontScale,
    ) {
        Density(
            density = phoneUiDensity(context)
                ?: context.resources.displayMetrics.density,
            fontScale = 1.0f,
        )
    }
    CompositionLocalProvider(LocalDensity provides density, content = content)
}

/**
 * Makes the current Compose host's navigation-bar region transparent while a
 * full-screen wheel is visible. Dialog-hosted callers use their dialog window;
 * an in-activity wheel uses its Activity window.
 *
 * Use this only for dialogs whose own content deliberately paints behind the
 * system bar. Interactive controls must still apply their normal bottom inset.
 */
@Composable
fun TransparentWheelNavigationBar() {
    val view = LocalView.current
    val context = LocalContext.current
    DisposableEffect(view, context) {
        val dialogWindow = (view.parent as? DialogWindowProvider)?.window
        val window = dialogWindow ?: context.findActivity()?.window
        if (window == null) {
            onDispose {}
        } else {
            @Suppress("DEPRECATION")
            val previousColor = window.navigationBarColor
            val previousContrast = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced
            } else {
                null
            }

            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
            @Suppress("DEPRECATION")
            run {
                window.navigationBarColor = Color.TRANSPARENT
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }

            onDispose {
                // The Activity normally fits its content above system bars.
                // Restore that contract when the in-activity wheel closes;
                // otherwise Trackpad/Remote/Air Mouse remain laid out beneath
                // Android's navigation bar after the first wheel dismissal.
                if (dialogWindow == null) {
                    WindowCompat.setDecorFitsSystemWindows(window, true)
                }
                @Suppress("DEPRECATION")
                run {
                    window.navigationBarColor = previousColor
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && previousContrast != null) {
                    window.isNavigationBarContrastEnforced = previousContrast
                }
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun phoneUiDensity(context: Context): Float? {
    val realWidthPx = runCatching {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val display = wm?.defaultDisplay
        if (display != null) {
            val real = DisplayMetrics()
            @Suppress("DEPRECATION") display.getRealMetrics(real)
            real.widthPixels
        } else {
            0
        }
    }.getOrDefault(0)
    val configWidthPx = context.resources.displayMetrics.widthPixels
    val widthPx = when {
        realWidthPx >= 320 -> realWidthPx
        configWidthPx >= 320 -> configWidthPx
        else -> return null
    }
    return widthPx.toFloat() / PinnedDensityActivity.TARGET_WIDTH_DP
}
