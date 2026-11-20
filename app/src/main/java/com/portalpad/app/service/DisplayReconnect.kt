package com.portalpad.app.service

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.SystemClock
import android.util.Log

/**
 * Softly removes and re-adds Android's external Display object on devices that
 * expose the required `cmd display` commands.
 *
 * This never changes USB state or drops the physical USB-C/DisplayPort link.
 * Pixel devices are deliberately excluded: their display-cycle command does not
 * restore the Desktop/Mirror choice that the former reconnect action promised.
 *
 * Every entry point here blocks on a shell round trip and must run on
 * Dispatchers.IO.
 */
object DisplayReconnect {
    private const val TAG = "PortalPadDisplayReconnect"
    private const val DOWN_SECONDS = 2
    const val USER_INITIATED_FLOOR_MS = 10_000L

    @Volatile private var lastReconnectAt = 0L

    enum class ReconnectMethod { DISPLAY_CYCLE, NONE }

    @Volatile private var cachedMethod: ReconnectMethod? = null

    /**
     * Returns the safe reconnect method available on this device.
     *
     * Capability detection remains the default. Pixel is the one explicit
     * exclusion because the same command is present there but does not provide
     * the recovery the Start-page action was intended to perform.
     */
    fun reconnectMethod(access: ElevatedAccess?): ReconnectMethod {
        if (isPixelDevice()) return ReconnectMethod.NONE
        cachedMethod?.let { return it }
        if (access == null) return ReconnectMethod.NONE

        val helpOut = runCatching { access.execShell("cmd display help") }.getOrNull()
        if (helpOut.isNullOrBlank()) {
            Log.w(TAG, "RECONNECT-METHOD probe could not run (backend not ready?) - NOT caching")
            return ReconnectMethod.NONE
        }

        val resolved = if (
            helpOut.contains("disable-display") && helpOut.contains("enable-display")
        ) {
            ReconnectMethod.DISPLAY_CYCLE
        } else {
            ReconnectMethod.NONE
        }
        cachedMethod = resolved
        Log.w(TAG, "RECONNECT-METHOD $resolved")
        return resolved
    }

    /** The current real external display id, resolved fresh for every reconnect. */
    private fun externalDisplayId(context: Context): Int? = runCatching {
        val dm = context.getSystemService(DisplayManager::class.java)
        dm.displays.firstOrNull {
            com.portalpad.app.DisplayUtil.isRealExternalDisplay(it)
        }?.displayId
    }.getOrNull()

    /** True when Android has registered a real external display. */
    fun externalDisplayRegistered(context: Context): Boolean = runCatching {
        val dm = context.getSystemService(DisplayManager::class.java)
        dm.displays.any { com.portalpad.app.DisplayUtil.isRealExternalDisplay(it) }
    }.getOrDefault(false)

    private fun rateAllows(reason: String, minIntervalMs: Long): Boolean {
        val now = SystemClock.elapsedRealtime()
        val since = now - lastReconnectAt
        if (lastReconnectAt != 0L && since < minIntervalMs) {
            Log.w(
                TAG,
                "DISPLAY-RECONNECT skipped ($reason): last was ${since}ms ago, " +
                    "floor ${minIntervalMs}ms",
            )
            return false
        }
        lastReconnectAt = now
        return true
    }

    /** Softly cycles the current external Display object. */
    fun reconnectDisplay(
        context: Context,
        access: ElevatedAccess?,
        reason: String,
        minIntervalMs: Long = USER_INITIATED_FLOOR_MS,
    ): Boolean = when (reconnectMethod(access)) {
        ReconnectMethod.DISPLAY_CYCLE ->
            cycleDisplay(context, access, reason, minIntervalMs)
        ReconnectMethod.NONE -> {
            Log.w(TAG, "DISPLAY-RECONNECT skipped ($reason): no safe method")
            false
        }
    }

    /**
     * Cycles the external Display object unconditionally when `cmd display disable-display` /
     * `enable-display` is exposed by the OS, bypassing the Start-page Pixel exclusion. Used ONLY
     * when enforcing "Force separate display" after flipping the Pixel connection preference out
     * of Mirror so the change takes effect immediately.
     */
    fun forceCycleDisplayIfSupported(
        context: Context,
        access: ElevatedAccess?,
        reason: String,
        minIntervalMs: Long = USER_INITIATED_FLOOR_MS,
    ): Boolean {
        if (access == null || !access.isReady) return false
        val helpOut = runCatching { access.execShell("cmd display help") }.getOrNull().orEmpty()
        if (!helpOut.contains("disable-display") || !helpOut.contains("enable-display")) {
            Log.w(TAG, "DISPLAY-FORCE-CYCLE skipped ($reason): cmd display cycle unsupported")
            return false
        }
        return cycleDisplay(context, access, reason, minIntervalMs)
    }

    /**
     * Disable and re-enable in one shell command so the display cannot be left
     * disabled if PortalPad is killed between the two operations.
     */
    private fun cycleDisplay(
        context: Context,
        access: ElevatedAccess?,
        reason: String,
        minIntervalMs: Long,
    ): Boolean {
        if (access == null) {
            Log.w(TAG, "DISPLAY-CYCLE skipped ($reason): no privileged backend")
            return false
        }
        val id = externalDisplayId(context)
        if (id == null) {
            Log.w(TAG, "DISPLAY-CYCLE skipped ($reason): no external display")
            return false
        }
        if (!rateAllows(reason, minIntervalMs)) return false

        Log.w(TAG, "DISPLAY-CYCLE ($reason): cycling display $id")
        return runCatching {
            val out = access.execShell(
                "cmd display disable-display $id; sleep $DOWN_SECONDS; " +
                    "cmd display enable-display $id",
            )
            Log.w(TAG, "DISPLAY-CYCLE done ($reason) out='${out.trim().take(120)}'")
            true
        }.getOrElse {
            Log.w(TAG, "DISPLAY-CYCLE failed ($reason)", it)
            false
        }
    }

    private fun isPixelDevice(): Boolean =
        (Build.BRAND.equals("google", ignoreCase = true) ||
            Build.MANUFACTURER.equals("google", ignoreCase = true)) &&
            Build.MODEL.startsWith("Pixel", ignoreCase = true)
}
