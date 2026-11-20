package com.portalpad.app.data

/**
 * One-finger behavior selected by the stepped pill inside Remote > Gesture.
 *
 * The persisted ids are deliberately explicit: adding presets must not change
 * any existing user's saved selection. Visual order is Volume, Media, Scroll,
 * Swipe, D-pad, Pointer; the safe first-run default remains D-pad.
 */
enum class RemoteGesturePreset(val id: Int, val label: String) {
    DPAD(0, "D-pad"),
    SCROLL(1, "Scroll"),
    MEDIA(2, "Media"),
    VOLUME(3, "Volume"),
    POINTER(4, "Pointer"),
    SWIPE(5, "Swipe"),
    ;

    companion object {
        fun fromId(id: Int): RemoteGesturePreset =
            entries.firstOrNull { it.id == id } ?: DPAD
    }
}
