package com.portalpad.app.ui.common

import android.content.Context
import android.content.Intent
import android.net.Uri

/** Shizuku's package id, whatever build it was installed from. */
const val SHIZUKU_PKG = "moe.shizuku.privileged.api"

/**
 * The Shizuku build PortalPad recommends: start-on-boot, a watchdog that restarts
 * it after a crash, and TCP mode, so it stays running reliably. The Resources page
 * links to the same URL.
 */
const val SHIZUKU_BUILD_URL = "https://github.com/thedjchi/Shizuku"

fun isShizukuInstalled(context: Context): Boolean = runCatching {
    context.packageManager.getLaunchIntentForPackage(SHIZUKU_PKG) != null
}.getOrDefault(false)

/**
 * Open Shizuku, or its build page when it is not installed.
 *
 * WHY THIS IS SHARED. Three places used to do this by hand - SettingsScreen's
 * private openShizukuApp, ModeStatusLabel's Shizuku dialog, and the mode strip -
 * and every one of them was written as:
 *
 *     val launch = pm.getLaunchIntentForPackage(SHIZUKU_PKG)
 *     if (launch != null) { startActivity(launch) }
 *     // ...and nothing else
 *
 * With no else branch, "Open Shizuku" did LITERALLY NOTHING for exactly the users
 * who most needed it - the ones without Shizuku. Fixing one copy in build 44 left
 * the others broken, which is the argument for one implementation rather than
 * three. Every caller now degrades to the install page instead of a dead tap.
 *
 * Callers should also label the control by what it can DO - "Install Shizuku" when
 * [isShizukuInstalled] is false - so the button never promises the impossible.
 */
fun openShizukuOrInstall(context: Context) {
    val launch = runCatching {
        context.packageManager.getLaunchIntentForPackage(SHIZUKU_PKG)
    }.getOrNull()
    if (launch != null) {
        runCatching {
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        return
    }
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_BUILD_URL))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
