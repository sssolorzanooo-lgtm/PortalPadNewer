package com.portalpad.app.service

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * Read-only view of Android's real editor connection exposed to accessibility
 * services on API 33+. This does not become the selected keyboard and never
 * commits text, changes selection, requests focus, or shows/hides the IME.
 *
 * Keep every reference to the API-33 InputMethod classes behind [Api33] so the
 * accessibility service itself remains loadable on PortalPad's API-30 minimum.
 */
internal object NativeInputConnectionMonitor {

    enum class Verdict {
        /** Android reports a started connection owned by the expected app. */
        READY,

        /** Android definitively reports no started/current connection or the wrong owner. */
        DEAD,

        /** API unavailable, monitor not initialized yet, or target identity is incomplete. */
        UNKNOWN,
    }

    data class Snapshot(
        val verdict: Verdict,
        val supported: Boolean,
        val monitorAvailable: Boolean,
        val inputStarted: Boolean?,
        val hasConnection: Boolean?,
        val packageName: String?,
        val fieldId: Int?,
        val inputType: Int?,
        val reason: String,
    ) {
        fun diag(): String =
            "verdict=$verdict supported=$supported available=$monitorAvailable " +
                "started=$inputStarted connection=$hasConnection pkg=$packageName " +
                "fieldId=$fieldId inputType=$inputType reason=$reason"
    }

    fun snapshot(
        service: AccessibilityService,
        expectedPackage: String?,
    ): Snapshot {
        if (Build.VERSION.SDK_INT < 33) {
            return Snapshot(
                verdict = Verdict.UNKNOWN,
                supported = false,
                monitorAvailable = false,
                inputStarted = null,
                hasConnection = null,
                packageName = null,
                fieldId = null,
                inputType = null,
                reason = "api<33",
            )
        }
        return Api33.snapshot(service, expectedPackage)
    }

    /**
     * One non-mutating IPC probe after an otherwise healthy native show. Android
     * documents a null result for an invalid, timed-out, or unsupported remote
     * connection, so this first build records the result but does not treat null
     * alone as failure. The fast structural [snapshot] remains the fallback gate.
     */
    fun probeSurroundingTextAsync(
        service: AccessibilityService,
        attempt: Long,
        expectedPackage: String?,
    ) {
        if (Build.VERSION.SDK_INT < 33) return
        Api33.probeSurroundingTextAsync(service, attempt, expectedPackage)
    }

    @RequiresApi(33)
    private object Api33 {
        private const val TAG = "PortalPadNativeIC"
        private val probeExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "portalpad-native-ic").apply { isDaemon = true }
            }

        fun snapshot(
            service: AccessibilityService,
            expectedPackage: String?,
        ): Snapshot {
            return try {
                val inputMethod = service.inputMethod
                    ?: return Snapshot(
                        verdict = Verdict.UNKNOWN,
                        supported = true,
                        monitorAvailable = false,
                        inputStarted = null,
                        hasConnection = null,
                        packageName = null,
                        fieldId = null,
                        inputType = null,
                        reason = "inputMethod=null; service may need rebind",
                    )
                val started = inputMethod.currentInputStarted
                val connection = inputMethod.currentInputConnection
                val editor = inputMethod.currentInputEditorInfo
                val pkg = editor?.packageName
                val packageMismatch = expectedPackage != null && pkg != null && pkg != expectedPackage
                val verdict = when {
                    !started -> Verdict.DEAD
                    connection == null -> Verdict.DEAD
                    packageMismatch -> Verdict.DEAD
                    expectedPackage != null && pkg == null -> Verdict.UNKNOWN
                    else -> Verdict.READY
                }
                val reason = when {
                    !started -> "input-not-started"
                    connection == null -> "connection-null"
                    packageMismatch -> "owner-mismatch expected=$expectedPackage actual=$pkg"
                    expectedPackage != null && pkg == null -> "owner-unknown"
                    else -> "started-current-connection"
                }
                Snapshot(
                    verdict = verdict,
                    supported = true,
                    monitorAvailable = true,
                    inputStarted = started,
                    hasConnection = connection != null,
                    packageName = pkg,
                    fieldId = editor?.fieldId,
                    inputType = editor?.inputType,
                    reason = reason,
                )
            } catch (t: Throwable) {
                Log.w(TAG, "snapshot failed", t)
                Snapshot(
                    verdict = Verdict.UNKNOWN,
                    supported = true,
                    monitorAvailable = false,
                    inputStarted = null,
                    hasConnection = null,
                    packageName = null,
                    fieldId = null,
                    inputType = null,
                    reason = "snapshot-exception:${t.javaClass.simpleName}",
                )
            }
        }

        fun probeSurroundingTextAsync(
            service: AccessibilityService,
            attempt: Long,
            expectedPackage: String?,
        ) {
            probeExecutor.execute {
                val startedAt = android.os.SystemClock.elapsedRealtime()
                try {
                    val inputMethod = service.inputMethod
                    val editor = inputMethod?.currentInputEditorInfo
                    val connection = inputMethod?.currentInputConnection
                    val result = connection?.getSurroundingText(1, 1, 0)
                    val elapsed = android.os.SystemClock.elapsedRealtime() - startedAt
                    val packageMatches = expectedPackage == null ||
                        editor?.packageName == null || editor.packageName == expectedPackage
                    Log.i(
                        TAG,
                        "DIAG-NATIVE-IC probe attempt=$attempt " +
                            "result=${if (result != null) "OK" else "NULL"} " +
                            "elapsed=${elapsed}ms started=${inputMethod?.currentInputStarted} " +
                            "connection=${connection != null} pkg=${editor?.packageName} " +
                            "expected=$expectedPackage packageMatches=$packageMatches " +
                            "selection=${result?.selectionStart}",
                    )
                } catch (t: Throwable) {
                    val elapsed = android.os.SystemClock.elapsedRealtime() - startedAt
                    Log.w(
                        TAG,
                        "DIAG-NATIVE-IC probe attempt=$attempt threw=" +
                            "${t.javaClass.simpleName} elapsed=${elapsed}ms expected=$expectedPackage",
                        t,
                    )
                }
            }
        }
    }
}
