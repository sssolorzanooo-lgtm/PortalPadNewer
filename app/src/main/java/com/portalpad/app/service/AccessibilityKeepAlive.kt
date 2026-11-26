package com.portalpad.app.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import com.portalpad.app.data.PreferencesRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Keeps [PortalPadAccessibilityService] enabled whenever Setup's
 * "Auto-open typing page" option (`SETUP_WANTS_ACCESSIBILITY`) is `"yes"`.
 *
 * Force-stopping PortalPad causes Android's AccessibilityManagerService to strip
 * PortalPad from `enabled_accessibility_services`. When PortalPad next starts,
 * resumes privileged access (Shizuku/Root), or starts its foreground service,
 * this coordinator restores the entry via direct `WRITE_SECURE_SETTINGS` first
 * (works without waiting for Shizuku) and falls back to privileged shell when
 * direct write is unavailable or denied.
 */
object AccessibilityKeepAlive {
    private const val TAG = "AccessibilityKeepAlive"
    private val mutex = Mutex()

    fun componentName(context: Context): String =
        "${context.packageName}/${PortalPadAccessibilityService::class.java.name}"

    fun mergeComponent(current: String, component: String): String {
        val trimmed = current.trim()
        if (trimmed.isEmpty() || trimmed == "null") return component
        val parts = trimmed.split(":").filter { it.isNotBlank() }
        return if (parts.contains(component)) {
            parts.joinToString(":")
        } else {
            (parts + component).joinToString(":")
        }
    }

    fun isEnabledInSettings(
        context: Context,
        readSecureString: (String) -> String? = { key ->
            runCatching { Settings.Secure.getString(context.contentResolver, key) }.getOrNull()
        },
        readSecureInt: (String, Int) -> Int = { key, default ->
            runCatching { Settings.Secure.getInt(context.contentResolver, key, default) }
                .getOrDefault(default)
        },
    ): Boolean {
        val pkgPrefix = "${context.packageName}/"
        val enabledList = readSecureString(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val hasComponent = enabledList
            .split(":")
            .any { entry -> entry.trim().startsWith(pkgPrefix) }
        val accessibilityMasterOn = readSecureInt(Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
        return hasComponent && accessibilityMasterOn
    }

    suspend fun ensureEnabledIfWanted(
        context: Context,
        prefs: PreferencesRepository,
        accessBackends: List<ElevatedAccess>,
        reason: String = "unspecified",
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        hasWriteSecureSettings: () -> Boolean = {
            context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
                PackageManager.PERMISSION_GRANTED
        },
        readSecureString: (String) -> String? = { key ->
            runCatching { Settings.Secure.getString(context.contentResolver, key) }.getOrNull()
        },
        readSecureInt: (String, Int) -> Int = { key, default ->
            runCatching { Settings.Secure.getInt(context.contentResolver, key, default) }
                .getOrDefault(default)
        },
        writeSecureString: (String, String) -> Boolean = { key, value ->
            runCatching { Settings.Secure.putString(context.contentResolver, key, value) }
                .getOrDefault(false)
        },
        writeSecureInt: (String, Int) -> Boolean = { key, value ->
            runCatching { Settings.Secure.putInt(context.contentResolver, key, value) }
                .getOrDefault(false)
        },
    ): Boolean = mutex.withLock {
        val wantsA11y = runCatching {
            prefs.string(PreferencesRepository.Keys.SETUP_WANTS_ACCESSIBILITY).first()
        }.getOrDefault("")
        if (wantsA11y != "yes") {
            return@withLock false
        }

        val comp = componentName(context)
        val pkg = context.packageName
        val shell = accessBackends.firstOrNull { it.isReady }

        if (isEnabledInSettings(context, readSecureString, readSecureInt)) {
            // Proactively grant WRITE_SECURE_SETTINGS / ACCESS_RESTRICTED_SETTINGS
            // when a shell backend is active so future Force-stop recovery works
            // instantaneously on cold launch before Shizuku connects.
            if (!hasWriteSecureSettings() && shell != null) {
                withContext(ioDispatcher) {
                    runCatching { shell.execShell("appops set $pkg ACCESS_RESTRICTED_SETTINGS allow") }
                    runCatching { shell.execShell("pm grant $pkg ${Manifest.permission.WRITE_SECURE_SETTINGS}") }
                }
            }
            return@withLock true
        }

        // Fast path: WRITE_SECURE_SETTINGS already granted -> write directly via ContentResolver.
        if (hasWriteSecureSettings()) {
            val cur = readSecureString(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val next = mergeComponent(cur, comp)
            val wroteList = writeSecureString(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next)
            val wroteMaster = writeSecureInt(Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            if (wroteList && wroteMaster && isEnabledInSettings(context, readSecureString, readSecureInt)) {
                Log.i(TAG, "Restored accessibility service via WRITE_SECURE_SETTINGS ($reason)")
                return@withLock true
            }
        }

        // Fallback path: privileged shell via Shizuku or Root.
        if (shell == null) {
            Log.d(TAG, "Skipped shell accessibility restore ($reason): no privileged backend ready")
            return@withLock false
        }

        withContext(ioDispatcher) {
            runCatching { shell.execShell("appops set $pkg ACCESS_RESTRICTED_SETTINGS allow") }
            runCatching { shell.execShell("pm grant $pkg ${Manifest.permission.WRITE_SECURE_SETTINGS}") }
            val cur = runCatching {
                shell.execShell("settings get secure enabled_accessibility_services").trim()
            }.getOrDefault("")
            val next = mergeComponent(cur, comp)
            runCatching {
                shell.execShell("settings put secure enabled_accessibility_services $next")
            }
            runCatching {
                shell.execShell("settings put secure accessibility_enabled 1")
            }
        }
        val restored = isEnabledInSettings(context, readSecureString, readSecureInt)
        Log.i(TAG, "Shell accessibility restore ($reason) -> enabled=$restored")
        restored
    }
}
