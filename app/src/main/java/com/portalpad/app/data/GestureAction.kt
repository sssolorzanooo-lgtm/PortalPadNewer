package com.portalpad.app.data

/**
 * An action assignable to a 3-finger swipe direction on the trackpad / air-mouse
 * surface. Stage 1: system actions only (Back / Home / the on-glasses
 * notification panel). "Launch a specific app" can be added later (it would reuse
 * the Home/Back app picker).
 *
 * Stored in prefs by [id]. [label] is the full settings-picker label; [short] is
 * the compact label used in the on-glasses gesture guide (single-line + ellipsis,
 * so it stays tidy).
 */
enum class GestureAction(val id: String, val label: String, val short: String) {
    NONE("none", "None", "—"),
    PLAY_PAUSE_MEDIA("play_pause_media", "Play / pause media", "Play/Pause"),
    VOLUME_UP("volume_up", "Volume up", "Vol +"),
    VOLUME_DOWN("volume_down", "Volume down", "Vol -"),
    SEEK_FORWARD("seek_forward", "Seek forward (+10s)", "Seek +10s"),
    REWIND("rewind", "Rewind (-10s)", "Rewind -10s"),
    BACK("back", "Back", "Back"),
    HOME("home", "Home", "Home"),
    NOTIF_OPEN("notif_open", "Open notifications panel", "Notifications"),
    NOTIF_CLOSE("notif_close", "Close notifications panel", "Close panel"),
    LAUNCH_APP("launch_app", "Launch app…", "Open app"),
    ;

    val isVolumeControl: Boolean get() = this == VOLUME_UP || this == VOLUME_DOWN
    val isSeekControl: Boolean get() = this == SEEK_FORWARD || this == REWIND

    companion object {
        fun fromId(id: String?): GestureAction =
            entries.firstOrNull { it.id == id } ?: NONE
    }
}
