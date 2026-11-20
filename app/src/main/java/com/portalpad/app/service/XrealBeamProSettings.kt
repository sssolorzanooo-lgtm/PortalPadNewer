package com.portalpad.app.service

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.portalpad.app.MainActivity
import com.portalpad.app.presentation.PhoneExitBandsOverlay

/**
 * The Beam Pro's My Glasses 2.2.0 settings verified on SH's device.
 *
 * Default Air Casting is not one value: My Glasses persists its displayed
 * switch in an exported provider and separately changes a protected persistent
 * system property. A shell/Shizuku process can update the provider but cannot
 * write the property, so PortalPad exposes automatic control only through a
 * genuinely ready root backend.
 */
object XrealBeamProSettings {
    const val PACKAGE_NAME = "com.xreal.evapro.nebula"

    private const val SETTINGS_ACTIVITY =
        "ai.nreal.nebula.activity.setting.SettingActivity"
    private const val CAST_SCREEN_NAME = "castScreen"
    private const val MIRROR_PROPERTY = "persist.sys.mirror_enable"
    private val SETTINGS_URI: Uri =
        Uri.parse("content://$PACKAGE_NAME/settings/")

    data class AirCastingState(
        val storedEnabled: Boolean?,
        val systemEnabled: Boolean?,
    ) {
        val verifiedEnabled: Boolean?
            get() = if (
                storedEnabled != null &&
                systemEnabled != null &&
                storedEnabled == systemEnabled
            ) {
                storedEnabled
            } else {
                null
            }
    }

    private data class StoredRow(
        val uri: Uri,
        val enabled: Boolean,
    )

    fun isAvailable(context: Context): Boolean =
        runCatching {
            context.packageManager.getLaunchIntentForPackage(PACKAGE_NAME) != null
        }.getOrDefault(false)

    /**
     * Reads both halves of Air Casting. [root] may be null when only the My
     * Glasses saved value is needed (for example, warning before TrackPad).
     */
    fun readAirCastingState(
        context: Context,
        root: ElevatedAccess? = null,
    ): AirCastingState {
        val stored = findStoredRow(context)?.enabled
        val system = if (
            root?.mode == ElevatedAccess.Mode.ROOT &&
            runCatching { root.isReady }.getOrDefault(false)
        ) {
            readBooleanProperty(root, MIRROR_PROPERTY)
        } else {
            null
        }
        return AirCastingState(stored, system)
    }

    /**
     * Applies and reads back both values. If either write cannot be verified,
     * the previous values are restored where possible and false is returned.
     */
    fun setAirCastingEnabled(
        context: Context,
        root: ElevatedAccess,
        enabled: Boolean,
    ): Boolean {
        if (
            root.mode != ElevatedAccess.Mode.ROOT ||
            !runCatching { root.isReady }.getOrDefault(false)
        ) {
            return false
        }

        val beforeRow = findStoredRow(context) ?: return false
        val beforeSystem = readBooleanProperty(root, MIRROR_PROPERTY)
        if (!writeStoredValue(context, beforeRow.uri, enabled)) return false

        val propertyValue = if (enabled) "1" else "0"
        runCatching {
            root.execShell("setprop $MIRROR_PROPERTY $propertyValue")
        }

        val confirmed = readAirCastingState(context, root).verifiedEnabled == enabled
        if (confirmed) return true

        // Avoid leaving the My Glasses UI and system behavior disagreeing after
        // a partial failure.
        writeStoredValue(context, beforeRow.uri, beforeRow.enabled)
        beforeSystem?.let { previous ->
            runCatching {
                root.execShell(
                    "setprop $MIRROR_PROPERTY ${if (previous) "1" else "0"}",
                )
            }
        }
        return false
    }

    /**
     * Opens My Glasses directly on its exported settings screen, pinned to the
     * phone display. Falls back to the package launcher if a future My Glasses
     * version renames that activity.
     */
    fun openAirCastingSettings(
        context: Context,
        showExitPill: Boolean = true,
    ): Boolean {
        val options = ActivityOptions.makeBasic()
            .setLaunchDisplayId(0)
            .toBundle()
        val explicit = Intent()
            .setComponent(ComponentName(PACKAGE_NAME, SETTINGS_ACTIVITY))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opened = runCatching {
            context.startActivity(explicit, options)
            true
        }.recoverCatching {
            val fallback = context.packageManager.getLaunchIntentForPackage(PACKAGE_NAME)
                ?: return@recoverCatching false
            fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(fallback, options)
            true
        }.getOrElse {
            Log.w(TAG, "Unable to open My Glasses settings", it)
            false
        }

        if (opened && showExitPill) {
            val appContext = context.applicationContext
            PhoneExitBandsOverlay.showStandalone(
                appContext,
                onExit = {
                    runCatching {
                        val back = Intent(appContext, MainActivity::class.java)
                            .addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                            )
                        val backOptions = ActivityOptions.makeBasic()
                            .setLaunchDisplayId(0)
                            .toBundle()
                        appContext.startActivity(back, backOptions)
                    }
                },
            )
        }
        return opened
    }

    private fun findStoredRow(context: Context): StoredRow? =
        runCatching {
            context.contentResolver.query(
                SETTINGS_URI,
                arrayOf("_id", "name", "value"),
                null,
                null,
                null,
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndex("_id")
                val nameColumn = cursor.getColumnIndex("name")
                val valueColumn = cursor.getColumnIndex("value")
                if (idColumn < 0 || nameColumn < 0 || valueColumn < 0) {
                    return@use null
                }
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameColumn) == CAST_SCREEN_NAME) {
                        val enabled = cursor.getString(valueColumn)
                            .equals("true", ignoreCase = true)
                        return@use StoredRow(
                            ContentUris.withAppendedId(
                                SETTINGS_URI,
                                cursor.getLong(idColumn),
                            ),
                            enabled,
                        )
                    }
                }
                null
            }
        }.onFailure {
            Log.w(TAG, "Unable to read My Glasses Air Casting state", it)
        }.getOrNull()

    private fun writeStoredValue(
        context: Context,
        rowUri: Uri,
        enabled: Boolean,
    ): Boolean =
        runCatching {
            val values = ContentValues().apply {
                put("value", enabled.toString())
            }
            context.contentResolver.update(rowUri, values, null, null) > 0
        }.onFailure {
            Log.w(TAG, "Unable to update My Glasses Air Casting state", it)
        }.getOrDefault(false)

    private fun readBooleanProperty(
        root: ElevatedAccess,
        property: String,
    ): Boolean? =
        runCatching {
            when (
                root.execShell("getprop $property")
                    .lineSequence()
                    .map(String::trim)
                    .lastOrNull { it.isNotEmpty() }
            ) {
                "1", "true" -> true
                "0", "false" -> false
                else -> null
            }
        }.onFailure {
            Log.w(TAG, "Unable to read $property", it)
        }.getOrNull()

    private const val TAG = "XrealBeamSettings"
}
