package com.portalpad.app.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "portalpad_prefs")

class PreferencesRepository(private val context: Context) {

    object Keys {
        val HOME_ACTION = stringPreferencesKey("home_action")          // JSON AppEntry
        val WHEEL_SLOTS = stringPreferencesKey("wheel_slots")          // JSON List<AppEntry?> (Quick Wheel)
        val BACK_ACTION = stringPreferencesKey("back_action")          // "system" or JSON AppEntry
        val AUTO_LAUNCH_ON_START = stringPreferencesKey("auto_launch_on_start") // "wallpaper" | "last" | JSON AppEntry
        val AUTO_LAUNCH_INTERFACE = stringPreferencesKey("auto_launch_interface") // "air_mouse" | "trackpad" | "remote"
        val LAST_FOREGROUND_APP = stringPreferencesKey("last_foreground_app")  // JSON AppEntry (for Last-app restore)
        val LAST_SESSION = stringPreferencesKey("last_session")        // JSON SavedSession (open apps + bounds)
        val SESSIONS_BY_WIDTH = stringPreferencesKey("sessions_by_width") // JSON SavedSessions (per-resolution layout memory)
        val OFFER_RESTORE_ON_RECONNECT = booleanPreferencesKey("offer_restore_on_reconnect") // auto-popup on trackpad open
        val AUTO_RESUME_LAST_APP_30M = booleanPreferencesKey("auto_resume_last_app_30m")
        val FORWARD_VIDEO_ON_CONNECT = booleanPreferencesKey("forward_video_on_connect")
        val AUDIO_OUTPUT_MODE = stringPreferencesKey("audio_output_mode")
        val LAST_DISCONNECTED_APP = stringPreferencesKey("last_disconnected_app")
        val BACK_BUTTON_STYLE = stringPreferencesKey("back_button_style")  // JSON ButtonAppearance
        val HOME_BUTTON_STYLE = stringPreferencesKey("home_button_style")  // JSON ButtonAppearance
        val APPDRAWER_BUTTON_STYLE = stringPreferencesKey("appdrawer_button_style")  // JSON ButtonAppearance
        val KEYBOARD_BUTTON_STYLE = stringPreferencesKey("keyboard_button_style")  // JSON ButtonAppearance
        val NAV_BUTTON_ORDER = stringPreferencesKey("nav_button_order")  // CSV of nav ids, e.g. "back,home,appdrawer,keyboard"
        val SAVED_COLOR_PRESETS = stringPreferencesKey("saved_color_presets")  // JSON List<Int> (ARGB)
        val HOME_WALLPAPER = stringPreferencesKey("home_wallpaper")     // legacy single pick (pre per-canvas; migrates → STD)
        val HOME_WALLPAPER_STD = stringPreferencesKey("home_wallpaper_std") // standard-canvas pick
        val HOME_WALLPAPER_UW = stringPreferencesKey("home_wallpaper_uw")   // ultrawide-canvas pick
        val CUSTOM_WALLPAPER_PATH = stringPreferencesKey("custom_wallpaper_path")  // file path of user-picked image
        val INPUT_MODE = stringPreferencesKey("input_mode")            // "trackpad" or "air_mouse"
        val ACCESS_MODE = stringPreferencesKey("access_mode")          // "none" | "shizuku" | "root"
        val DISPLAY_GLASSES_MODE = booleanPreferencesKey("display_glasses_mode")  // wrap external display in virtual display
        val MIRROR_KEEP_AWAKE_MODE = stringPreferencesKey("mirror_keep_awake_mode") // "off" | "keep_screen_on" | "dim_screen" | "oled_black_overlay"
        val FORCE_SEPARATE_DISPLAY = booleanPreferencesKey("force_separate_display") // enforce separate display pref on Pixel Android 17+
        val DOCK_CONFIG = stringPreferencesKey("dock_config")          // JSON DockConfig
        val TOP_BAR_CONFIG = stringPreferencesKey("top_bar_config")    // JSON TopBarConfig
        val FOLDER_WINDOW_CONFIG = stringPreferencesKey("folder_window_config") // JSON FolderWindowConfig
        val WIDGET_OVERLAY_CONFIG = stringPreferencesKey("widget_overlay_config") // JSON WidgetOverlayConfig
        val SIDE_MODE_CONFIG = stringPreferencesKey("side_mode_config") // JSON SideModeConfig (per-interface)
        val DISPLAY_DPI = intPreferencesKey("display_dpi")             // 0 = use 213 default
        val SHIZUKU_AUTH_KEY = stringPreferencesKey("shizuku_auth_key") // Shizuku automation 'auth' Extras value
        // Aspect ratio override for the virtual display. "AUTO" (or empty) =
        // use the glasses' native resolution; otherwise one of "4:3", "16:9",
        // "16:10", "32:9", "32:10" — the VD is sized to that ratio within the
        // native panel (letterboxed/pillarboxed by the physical panel).
        val ASPECT_RATIO = stringPreferencesKey("aspect_ratio")        // "" / "AUTO" = native
        // Virtual-screen SIZE as a percent of the (aspect-fitted) panel area,
        // 50–100. 100 = full size; lower shrinks the VD pixel dimensions AND dpi
        // together so the desktop LAYOUT is unchanged but the screen renders into a
        // smaller, letterboxed region — i.e. a smaller screen in the glasses' FOV
        // (not a resolution/UI-scale change; Display DPI handles that separately).
        val SCREEN_SIZE_PCT = intPreferencesKey("screen_size_pct")     // 0/100 = full
        // 3-finger swipe gesture actions (store GestureAction.id). Defaults
        // preserve the original hardcoded behavior.
        val GESTURE_SWIPE_UP = stringPreferencesKey("gesture_swipe_up")
        val GESTURE_SWIPE_DOWN = stringPreferencesKey("gesture_swipe_down")
        val GESTURE_SWIPE_LEFT = stringPreferencesKey("gesture_swipe_left")
        val GESTURE_SWIPE_RIGHT = stringPreferencesKey("gesture_swipe_right")
        // When a direction's action is LAUNCH_APP, the app to launch (JSON AppEntry).
        val GESTURE_APP_UP = stringPreferencesKey("gesture_app_up")
        val GESTURE_APP_DOWN = stringPreferencesKey("gesture_app_down")
        val GESTURE_APP_LEFT = stringPreferencesKey("gesture_app_left")
        val GESTURE_APP_RIGHT = stringPreferencesKey("gesture_app_right")
        // Color tuning (GPU pipeline). 7 comma-separated floats:
        // brightness,contrast,saturation,temp,rGain,gGain,bGain. Empty = defaults
        // (all 1.0, temp 0). Re-applied to the glasses on each connect.
        val COLOR_TUNING = stringPreferencesKey("color_tuning")
        // GPU color pipeline on/off. Default true. When false, the VD renders
        // straight to the glasses surface (no GL color pass) — disables color
        // tuning but removes the per-frame overhead, for low-end devices.
        val GPU_COLOR_PIPELINE = booleanPreferencesKey("gpu_color_pipeline")
        // Performance overlay (HUD) rendered on the external display.
        val HUD_ENABLED = booleanPreferencesKey("hud_enabled")                 // master on/off (default off)
        val HUD_CORNER = stringPreferencesKey("hud_corner")                    // top_left|top_right|bottom_left|bottom_right
        val HUD_SHOW_FPS = booleanPreferencesKey("hud_show_fps")               // delivered fps row
        val HUD_SHOW_FRAMETIME = booleanPreferencesKey("hud_show_frametime")   // GL draw+swap ms row
        val HUD_SHOW_MODE = booleanPreferencesKey("hud_show_mode")             // active resolution + Hz row
        val HUD_SHOW_DROPPED = booleanPreferencesKey("hud_show_dropped")       // vsync + dropped-frame estimate row
        val SHOW_FINGER_DOTS = booleanPreferencesKey("finger_dots")
        val CAPTURE_INTRO_SHOWN = booleanPreferencesKey("capture_intro_shown") // first-tap screenshot/record explainer
        val ENABLE_MOUSE_HOVER = booleanPreferencesKey("mouse_hover")
        /**
         * EXPERIMENT: when an app launched to the external display triggers a
         * runtime permission dialog, keep everything ON the external display by
         * flipping the host task to fullscreen in place (so the transparent
         * prompt paints over the now-opaque app) instead of moving the app to
         * the phone. Default OFF — the phone-move path is the reliable default;
         * this is opt-in because whether the in-place fullscreen flip renders
         * the prompt (vs. relocating the black rect) is hardware-dependent. The
         * phone-move remains the fallback if the flip doesn't take.
         */
        val PERM_DIALOG_KEEP_ON_EXTERNAL = booleanPreferencesKey("perm_dialog_keep_on_external")
        /** EXPERIMENT (permfix / AirBeam parity): drive the glasses via a
         *  SurfaceControl layerStack retarget (no app overlay) instead of the
         *  overlay + GL-shader mirror. Immune to the security overlay-hide that
         *  blanks the panel during USB/permission dialogs; trades the non-linear
         *  gamma stage (linear color is preserved via setDisplayColorTransform).
         *  Auto-falls-back to the overlay path if the retarget is refused. */
        val PANEL_SYSTEM_MIRROR = booleanPreferencesKey("panel_system_mirror")
        /**
         * Master gate for the DeX-style desktop-windows experience (beta):
         * freeform resizable windows, a bottom taskbar, and a top window-bar.
         * Default OFF — when off, nothing in the freeform/taskbar path runs and
         * the external display behaves exactly as before. See FreeformManager.
         */
        val DESKTOP_MODE_ENABLED = booleanPreferencesKey("desktop_mode_enabled")
        /**
         * When desktop mode is on AND the external display is showing wallpaper
         * (no open windows), keep the dock pinned visible instead of auto-hiding.
         * The moment any window opens, the dock reverts to its normal auto-hide
         * timer. Off = the dock always obeys the auto-hide timer.
         */
        val ALWAYS_SHOW_DOCK_ON_WALLPAPER = booleanPreferencesKey("always_show_dock_on_wallpaper")
        /**
         * When desktop mode is on, controls whether tapping a dock item opens
         * the app in a freeform window (true) or fullscreen on the display
         * (false). No effect when desktop mode is off.
         */
        val DOCK_OPENS_FREEFORM = booleanPreferencesKey("dock_opens_freeform")
        /** When true, dock app-search types via the phone keyboard relay; when
         *  false, the user types directly on the external-display search field. */
        val SEARCH_KEYBOARD_ON_PHONE = booleanPreferencesKey("search_keyboard_on_phone")
        /** PortalPad phone-UI orientation lock. One of: "portrait" (default),
         *  "portrait_reverse", "landscape", "landscape_reverse", "auto". Affects
         *  only PortalPad's own activities, never apps on the external display. */
        val FORCE_ORIENTATION = stringPreferencesKey("force_orientation")
        // Global haptic strength in milliseconds. 0 = Off. Presets:
        // Light=10, Medium=25 (default), Strong=40. Drives every vibration in
        // the app (trackpad clicks + phone-side taps).
        val VIBRATION_MS = intPreferencesKey("vibration_ms")
        /**
         * Maximum number of simultaneous freeform windows on the external
         * display in desktop mode. Beyond this, the user is prompted to close
         * a window before opening another. Default 5; sensible range 3–6 (AR
         * glasses + a trusted VD get compositor/memory strain past ~5).
         */
        val MAX_WINDOWS = intPreferencesKey("max_windows")
        val FLOATING_NAV = booleanPreferencesKey("floating_nav")
        val FLOATING_BUBBLE = booleanPreferencesKey("floating_bubble") // background bubble toggle
        val SHOW_KEYBOARD = booleanPreferencesKey("show_keyboard")
        // Remembers whether the Remote tab was the last-used interface, so the
        // trackpad reopens on Remote (Trackpad/AirMouse already persist via
        // InputMode; Remote isn't a pointing mode, so it needs its own flag).
        val LAST_MODE_WAS_REMOTE = booleanPreferencesKey("last_mode_was_remote")
        /**
         * Where the soft keyboard appears when a text field on the external
         * display gets focus. When true → keyboard pops up on the external
         * display itself (more natural visually, but Android often hides it
         * when the cursor moves over the field). When false → keyboard falls
         * back to the phone screen (more reliable typing). Default: false (phone).
         */
        val IME_ON_EXTERNAL = booleanPreferencesKey("ime_on_external")
        /**
         * When true (DEFAULT): while in phone-as-keyboard mode (IME_ON_EXTERNAL
         * off), tapping a text field on the external display opens the relay
         * ("Type to external display") page.
         *
         * When false, the same tap instead runs the NATIVE path: focus the field
         * by accessibility node so the app itself asks for the IME, then verify
         * with isImeShown() and fall back to the relay page if no keyboard came
         * up. Native works because phone mode pins the external display's IME
         * policy to FALLBACK(1), under which the field tap alone raises the
         * phone's own keyboard bound straight to the external field — verified
         * end to end on SM-S921U 2026-07-19 (IMMS bound HoneyBoard to the VD
         * client, displayToShowIme=0, then native commitText, with ZERO injected
         * keys, ZERO focused-display flips and ZERO tap-outside focus steals
         * across 70 keypresses). That gives real autocorrect, swipe and voice.
         *
         * HISTORY — do not repeat: v1.5 first shipped this defaulting FALSE while
         * the native chain existed ONLY on the keyboard button. The field-tap path
         * begins with `if (!autoOpenRelayEnabled) return`, so a field tap did
         * NOTHING AT ALL: no relay, no keyboard. The default is back to TRUE, and
         * the false branch now always ends in a keyboard (native, else relay).
         *
         * NOTE: this default is declared in THREE places that must stay in sync —
         * the SettingsScreen toggle, the PortalPadApp collect, and Reset.
         */
        val AUTO_OPEN_RELAY_ON_FIELD = booleanPreferencesKey("auto_open_relay_on_field")
        /**
         * Which tab the typing page lands on: true = Android keyboard (with text
         * box), false = Custom keyboard. Default FALSE (custom is the shipped
         * default keyboard).
         *
         * Before this the tab was `remember { mutableStateOf(true) }` — it reset
         * to Custom on every open and was never stored, so a user who preferred
         * the Android tab re-picked it every single time. Written from BOTH the
         * Settings dropdown and from switching tabs inside the page, so the
         * setting always describes what you'll actually get.
         */
        val RELAY_OPENS_ON_ANDROID_TAB = booleanPreferencesKey("relay_opens_on_android_tab")

        /**
         * PortalPad's own keyboard is drawn ON the external display, and a field
         * tap there raises it. A fourth switch rather than a new combination of
         * the three above: those encode exactly four modes between them, and
         * there is no spare state to overload without one mode's settings
         * becoming another mode's by accident.
         *
         * Takes priority when resolving the mode, so a stale IME_ON_EXTERNAL or
         * landing-tab value left by an earlier session cannot outvote it.
         */
        val PORTALPAD_KB_ON_DISPLAY = booleanPreferencesKey("portalpad_kb_on_display")
        /**
         * PortalPad's own keyboard floats over the PHONE (a keys-only overlay,
         * no text box) and types into the focused field — the on-phone sibling of
         * the on-display keyboard. A fifth switch for the same reason the fourth
         * one exists: the others encode exactly their own modes and there is no
         * spare state to overload. Resolved with the same first-wins priority as
         * PORTALPAD_KB_ON_DISPLAY so a stale flag can't outvote a deliberate pick.
         */
        val PORTALPAD_KB_FLOATING = booleanPreferencesKey("portalpad_kb_floating")
        /** Closing a window (Close all, Manage windows, radial/top-bar close,
         *  or Samsung's own caption/pill X) also removes its card from phone
         *  Recents via the removeTask binder. Default ON per SH; turning it
         *  off restores the historical behavior (Recents card may remain). */
        val CLOSE_REMOVES_FROM_RECENTS = booleanPreferencesKey("close_removes_from_recents")
        /** Android-keyboard relay: ms of typing stillness before the external
         *  write fires. Writes NEVER happen mid-typing (each one migrates the
         *  IME target and the user's own keypresses then re-steal display
         *  focus — the "types a few chars then stops" ROM behavior). */
        val RELAY_PAUSE_FLUSH_MS = intPreferencesKey("relay_pause_flush_ms")
        /**
         * When true (default): a quick finger tap on the trackpad surface
         * fires a left click. When false: the trackpad is motion-only — taps
         * are ignored; the user clicks via the dedicated left/right buttons.
         * Useful for users who want precise cursor positioning without the
         * risk of accidental clicks from small adjustments.
         */
        val TAP_TO_CLICK = booleanPreferencesKey("tap_to_click")
        val EDGE_SCROLL = booleanPreferencesKey("edge_scroll")
        // Which side the edge-scroll strip sits on: "left" or "right" (default).
        val EDGE_SCROLL_SIDE = stringPreferencesKey("edge_scroll_side")
        val CURSOR_SPEED = androidx.datastore.preferences.core.floatPreferencesKey("cursor_speed")
        val CURSOR_SMOOTHING = androidx.datastore.preferences.core.floatPreferencesKey("cursor_smoothing")
        // Auto-hide the external-display cursor while media is actively playing.
        // Requires the notification listener (media-session detection). The cursor
        // reappears instantly on the next trackpad movement.
        val MEDIA_AUTOHIDE_CURSOR = booleanPreferencesKey("media_autohide_cursor")
        val MEDIA_AUTOHIDE_CURSOR_SEC = intPreferencesKey("media_autohide_cursor_sec")
        val READER_FLIP_METHOD = intPreferencesKey("reader_flip_method") // 0 = Page up/down, 1 = Arrows, 2 = Scroll
        // Remote-interface D-pad-area sub-mode (the "Input" dropdown in
        // MediaControlsPanel): 0 = D-pad, 1 = Scroll, 2 = Gesture, 3 = Reader.
        // Distinct from the pointing-mode InputMode enum. Persisted so Reader
        // (and Scroll/Gesture) survive disconnect / relay round-trip / recreate.
        val REMOTE_INPUT_MODE = intPreferencesKey("remote_input_mode")
        // One-finger preset inside Remote > Gesture. See RemoteGesturePreset;
        // kept separate from REMOTE_INPUT_MODE so leaving/re-entering Gesture
        // returns to the same muscle-memory position.
        val REMOTE_GESTURE_PRESET = intPreferencesKey("remote_gesture_preset")
        // Phone-side placement of the Remote > Gesture preset rail.
        // false = right (default), true = left.
        val REMOTE_GESTURE_RAIL_LEFT =
            booleanPreferencesKey("remote_gesture_rail_left")
        val SCROLL_SPEED = androidx.datastore.preferences.core.floatPreferencesKey("scroll_speed")
        val INVERT_SCROLL = booleanPreferencesKey("invert_scroll")
        // Velocity-based pointer acceleration strength. 1.0 = tuned default,
        // 0 = off (constant speed, linear delta×speed). Scales the base accel
        // constant in the trackpad recognizer.
        val POINTER_ACCEL = androidx.datastore.preferences.core.floatPreferencesKey("pointer_accel")
        val AIR_MOUSE_SENSITIVITY = androidx.datastore.preferences.core.floatPreferencesKey("air_mouse_sensitivity")
        val AIR_MOUSE_SMOOTHING = androidx.datastore.preferences.core.floatPreferencesKey("air_mouse_smoothing")
        // Same as POINTER_ACCEL but for the air-mouse surface (separate constant
        // because its deltas are ~100× larger than trackpad pixels).
        val AIR_MOUSE_ACCEL = androidx.datastore.preferences.core.floatPreferencesKey("air_mouse_accel")
        val AIR_MOUSE_INVERT_X = booleanPreferencesKey("air_mouse_invert_x")
        // External (Bluetooth/USB) physical-mouse sensitivity — a linear multiplier
        // applied to its raw evdev deltas. Separate from trackpad/air-mouse because
        // it's a distinct input source the user tunes independently.
        val EXT_MOUSE_SENSITIVITY = androidx.datastore.preferences.core.floatPreferencesKey("ext_mouse_sensitivity")
        // External-mouse wheel: its own scroll-speed multiplier and direction,
        // independent of the trackpad's scroll settings.
        val EXT_MOUSE_SCROLL_SPEED = androidx.datastore.preferences.core.floatPreferencesKey("ext_mouse_scroll_speed")
        val EXT_MOUSE_NATURAL_SCROLL = booleanPreferencesKey("ext_mouse_natural_scroll")
        // Whether to exclusively grab (EVIOCGRAB) the mouse — suppresses the
        // phone's own pointer. Off = shared (both cursors move); useful where the
        // grab is denied or the user wants the mouse on the phone too.
        val EXT_MOUSE_GRAB = booleanPreferencesKey("ext_mouse_grab")
        // User opted into the external mouse (persisted). Drives auto-resume:
        // re-arm capture on display/Bluetooth connect without re-toggling.
        val EXT_MOUSE_ENABLED = booleanPreferencesKey("ext_mouse_enabled")
        // Automatically capture connected USB/Bluetooth mice into PortalPad's
        // cursor when an external display session is active. Default ON.
        val EXT_MOUSE_AUTO_CAPTURE = booleanPreferencesKey("ext_mouse_auto_capture")
        // Per-device mode-toggle button map: "deviceName<TAB>linuxCode" lines,
        // one per device. Pressing the mapped button while that device is
        // captured flips input mode mouse<->remote (BluetoothMouseController).
        val EXT_MOUSE_MODE_TOGGLE_MAP = stringPreferencesKey("ext_mouse_mode_toggle_map")
        // How the mapped button fires: 0 tap, 1 double tap, 2 hold (500ms).
        val EXT_MOUSE_TOGGLE_ACTIVATION = intPreferencesKey("ext_mouse_toggle_activation")

        /**
         * What a grabbed external mouse DOES. 0 = Mouse (cursor), 1 = Remote
         * (the Remote tab's gesture vocabulary: swipes become D-pad arrows).
         *
         * Belongs to the mouse, not to whichever tab the phone is showing. It is
         * a separate physical device, so setting it once should hold no matter
         * what you are looking at.
         */
        val EXT_MOUSE_INPUT_MODE = androidx.datastore.preferences.core.intPreferencesKey(
            "ext_mouse_input_mode",
        )

        /** Which pointer to capture, by device NAME. Empty = first found, which
         *  is the historical behaviour and stays the default. Stored by name
         *  because eventN is not stable across reboots or re-pairings, so a
         *  stored number would silently come to mean a different device. */
        val EXT_MOUSE_DEVICE = androidx.datastore.preferences.core.stringPreferencesKey(
            "ext_mouse_device",
        )

        /** Remote-mode swipe distance: net device counts before a one-finger
         *  swipe fires an arrow. Higher = must swipe further. Replaces the
         *  hardcoded REMOTE_MOVE_MIN so it can be felt-tuned instead of guessed.
         *  Only meaningful in Remote input mode. */
        val EXT_MOUSE_SWIPE_DISTANCE = androidx.datastore.preferences.core.intPreferencesKey(
            "ext_mouse_swipe_distance",
        )

        /** Reverse two-finger seek in Remote mode. REL_HWHEEL sign is not
         *  standardised across devices, so on tested hardware a left swipe
         *  reports positive and mapped to fast-forward, which is backwards.
         *  Default TRUE corrects that; anyone whose device reports the opposite
         *  sign turns it off. Same escape hatch as reverse-scroll in Mouse mode. */
        val EXT_MOUSE_REVERSE_SEEK = booleanPreferencesKey("ext_mouse_reverse_seek")
        // One-time hint: shown the first time the trackpad arrange button is
        // tapped, teaching the long-press (arrange in order) gesture.
        val ARRANGE_LONGPRESS_HINT_SEEN = booleanPreferencesKey("arrange_longpress_hint_seen")
        val WIDGET_OVERLAY_NUDGE_SEEN = booleanPreferencesKey("widget_overlay_nudge_seen") // one-time Workspace discovery card
        val WIDGET_OVERLAY_EDIT_HINT_COUNT = intPreferencesKey("widget_overlay_edit_hint_count") // times user entered edit; hint retires at 3
        val AIR_MOUSE_INVERT_Y = booleanPreferencesKey("air_mouse_invert_y")
        val SCREEN_OFF_EXPLAINED = booleanPreferencesKey("screen_off_explained")
        // Start-setup page: did the user say they'll use media controls / the
        // notification panel on the glasses? "" = unanswered, "yes", "no".
        val SETUP_WANTS_NOTIF_ACCESS = stringPreferencesKey("setup_wants_notif_access")
        val SETUP_WANTS_VOICE_SEARCH = stringPreferencesKey("setup_wants_voice_search")
        val SETUP_WANTS_BACKGROUND = stringPreferencesKey("setup_wants_background")
        // Setup: required Yes/No answer for the external-mouse question ("" = unanswered,
        // so it blocks setup completion until the user picks). Drives EXT_MOUSE_ENABLED.
        val SETUP_MOUSE_ANSWER = stringPreferencesKey("setup_mouse_answer")
        val SETUP_WANTS_ACCESSIBILITY = stringPreferencesKey("setup_wants_accessibility")
        val SETUP_WANTS_PHONE_STATE = stringPreferencesKey("setup_wants_phone_state")
        val PROG_KEY_NAME_RED = stringPreferencesKey("prog_key_name_red")
        val PROG_KEY_NAME_GREEN = stringPreferencesKey("prog_key_name_green")
        val PROG_KEY_NAME_YELLOW = stringPreferencesKey("prog_key_name_yellow")
        val PROG_KEY_NAME_BLUE = stringPreferencesKey("prog_key_name_blue")
        // Optional launch action per color key (JSON AppEntry). When set, tapping
        // the key launches this app/activity/shortcut instead of sending its key
        // event. Cleared when the user switches the key back to remap-helper mode.
        val PROG_KEY_ACTION_RED = stringPreferencesKey("prog_key_action_red")
        val PROG_KEY_ACTION_GREEN = stringPreferencesKey("prog_key_action_green")
        val PROG_KEY_ACTION_YELLOW = stringPreferencesKey("prog_key_action_yellow")
        val PROG_KEY_ACTION_BLUE = stringPreferencesKey("prog_key_action_blue")
        // Per-key visual style (JSON ProgKeyAppearance). Absent = built-in look.
        val PROG_KEY_STYLE_RED = stringPreferencesKey("prog_key_style_red")
        val PROG_KEY_STYLE_GREEN = stringPreferencesKey("prog_key_style_green")
        val PROG_KEY_STYLE_YELLOW = stringPreferencesKey("prog_key_style_yellow")
        val PROG_KEY_STYLE_BLUE = stringPreferencesKey("prog_key_style_blue")
        val AUTO_LAUNCH_AFTER_MOVE = booleanPreferencesKey("auto_after_move")
        val AUTO_LAUNCH_WHEN_ENABLED = booleanPreferencesKey("auto_when_enabled")
        val AUTO_ACTIVATE_EXTERNAL = booleanPreferencesKey("auto_external")
        /** Set true by a DELIBERATE user stop (companion stop()), cleared by a
         *  deliberate start (companion start()). A START_STICKY relaunch checks
         *  this in onCreate: true → the user said stop, stay stopped (stopSelf);
         *  false → the SYSTEM killed us (e.g. One UI stopping the FGS on recents
         *  Close-All) and the relaunch should resume the session. */
        val SERVICE_USER_STOPPED = booleanPreferencesKey("service_user_stopped")
        /** Samsung only. When true, starting the PortalPad service first turns
         *  Samsung DeX OFF (if it's currently on) so DeX and our VirtualDisplay
         *  don't fight over the external display. Default true. */
        val STOP_DEX_ON_START = booleanPreferencesKey("stop_dex_on_start")
        /** Stock Android (Pixel). When true, PortalPad answers the system's
         *  "Connect to external display" sheet by choosing Desktop, so the
         *  display comes up as a separate screen instead of a mirror.
         *  Default true. Mirrored to PortalPadApp.autoDesktopOnConnectEnabled
         *  because the reader is the accessibility event thread, which cannot
         *  suspend to query DataStore. Never touches the sheet's "Don't ask me
         *  again" checkbox - that is a one-way door. */
        val AUTO_DESKTOP_ON_CONNECT = booleanPreferencesKey("auto_desktop_on_connect")
        val SYSTEM_DECORATIONS = booleanPreferencesKey("system_deco")
        val ALLOW_LOCKSCREEN = booleanPreferencesKey("allow_lock")
        val MULTI_WINDOW = booleanPreferencesKey("multi_window")
        val DOCK_ENABLED = booleanPreferencesKey("dock_enabled")
        val DOCK_DISPLAY_MODE = stringPreferencesKey("dock_display_mode")

        // Diagnostics
        val SHOW_CLICK_TOASTS = booleanPreferencesKey("debug_click_toasts")
        /**
         * Diagnostic: inject MOUSE-source ACTION_HOVER_MOVE events on cursor
         * motion. Default ON. Turn OFF to test whether hover events are the
         * cause of UI artifacts (Chrome dismissing address-bar suggestions
         * the moment the cursor enters them, IME hiding, etc.). With this
         * OFF, the cursor still moves visually via the overlay but the OS
         * sees no hover.
         */
        val VERBOSE_HOVER_LOG = booleanPreferencesKey("debug_verbose_hover_log")
        /**
         * Diagnostic: re-pin the IME display policy after every click + key
         * event. Default ON. Turn OFF to test whether the post-click re-pin
         * is bouncing focus and causing dropdowns / suggestion lists to
         * dismiss when the user interacts with text fields. With this OFF,
         * the IME is still pinned when the displayId changes, just not
         * after every interaction.
         */
        /** Set true after the user has acknowledged the logcat-viewer consent dialog. */
        val LOGCAT_CONSENT_SHOWN = booleanPreferencesKey("logcat_consent_shown")

        // Lifecycle prefs
        val START_ON_BOOT = booleanPreferencesKey("start_on_boot")
        /** Minutes with no display before service auto-shuts. 0 = never. */
        val AUTO_DISABLE_AFTER_MIN = androidx.datastore.preferences.core.intPreferencesKey("auto_disable_after_min")

        // ─── Backup & restore (these settings about backups are themselves
        // intentionally not included in the backup payload, so a restore doesn't
        // overwrite the user's current backup-folder choice) ────────────────
        val BACKUP_FOLDER_URI = stringPreferencesKey("backup_folder_uri")
        val BACKUP_FREQUENCY = stringPreferencesKey("backup_frequency")  // "off" | "scheduled"
        val BACKUP_LAST_SUCCESS_MS = androidx.datastore.preferences.core.longPreferencesKey("backup_last_success")
        /** ISO day-of-week 1-7 (Monday-Sunday), or 0 = every day. */
        val BACKUP_DAY = androidx.datastore.preferences.core.intPreferencesKey("backup_day")
        /** Hour-of-day 0-23 in device local time. Default 3 (early morning). */
        val BACKUP_HOUR = androidx.datastore.preferences.core.intPreferencesKey("backup_hour")
    }

    /** Exposed for [BackupManager]. Read directly via [androidx.datastore.preferences.core.Preferences] for bulk dumps. */
    val rawDataStore by lazy { context.dataStore }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val homeAction: Flow<AppEntry?> = context.dataStore.data.map { p ->
        p[Keys.HOME_ACTION]?.let { runCatching { json.decodeFromString<AppEntry>(it) }.getOrNull() }
    }

    /** The Quick Wheel ring: exactly [QUICK_WHEEL_SLOTS] slots, null = empty ("+"). */
    val wheelSlots: Flow<List<AppEntry?>> = context.dataStore.data.map { p ->
        val parsed = p[Keys.WHEEL_SLOTS]?.let {
            runCatching { json.decodeFromString<List<AppEntry?>>(it) }.getOrNull()
        } ?: emptyList()
        List(QUICK_WHEEL_SLOTS) { i -> parsed.getOrNull(i) }
    }
    // Optional per-color-key launch actions (null = key sends its event instead).
    val progActionRed: Flow<AppEntry?> = context.dataStore.data.map { p ->
        p[Keys.PROG_KEY_ACTION_RED]?.let { runCatching { json.decodeFromString<AppEntry>(it) }.getOrNull() }
    }
    val progActionGreen: Flow<AppEntry?> = context.dataStore.data.map { p ->
        p[Keys.PROG_KEY_ACTION_GREEN]?.let { runCatching { json.decodeFromString<AppEntry>(it) }.getOrNull() }
    }
    val progActionYellow: Flow<AppEntry?> = context.dataStore.data.map { p ->
        p[Keys.PROG_KEY_ACTION_YELLOW]?.let { runCatching { json.decodeFromString<AppEntry>(it) }.getOrNull() }
    }
    val progActionBlue: Flow<AppEntry?> = context.dataStore.data.map { p ->
        p[Keys.PROG_KEY_ACTION_BLUE]?.let { runCatching { json.decodeFromString<AppEntry>(it) }.getOrNull() }
    }
    // Optional per-color-key visual style (null = built-in look).
    val progKeyStyleRed: Flow<ProgKeyAppearance?> = context.dataStore.data.map { p ->
        p[Keys.PROG_KEY_STYLE_RED]?.let { runCatching { json.decodeFromString<ProgKeyAppearance>(it) }.getOrNull() }
    }
    val progKeyStyleGreen: Flow<ProgKeyAppearance?> = context.dataStore.data.map { p ->
        p[Keys.PROG_KEY_STYLE_GREEN]?.let { runCatching { json.decodeFromString<ProgKeyAppearance>(it) }.getOrNull() }
    }
    val progKeyStyleYellow: Flow<ProgKeyAppearance?> = context.dataStore.data.map { p ->
        p[Keys.PROG_KEY_STYLE_YELLOW]?.let { runCatching { json.decodeFromString<ProgKeyAppearance>(it) }.getOrNull() }
    }
    val progKeyStyleBlue: Flow<ProgKeyAppearance?> = context.dataStore.data.map { p ->
        p[Keys.PROG_KEY_STYLE_BLUE]?.let { runCatching { json.decodeFromString<ProgKeyAppearance>(it) }.getOrNull() }
    }
    val backAction: Flow<BackAction> = context.dataStore.data.map { p ->
        val raw = p[Keys.BACK_ACTION] ?: "system"
        if (raw == "system") BackAction.System
        else runCatching {
            BackAction.Launch(json.decodeFromString<AppEntry>(raw))
        }.getOrDefault(BackAction.System)
    }
    /** What launches on the glasses at session start. Default = Wallpaper. */
    val autoLaunchOnStart: Flow<AutoLaunch> = context.dataStore.data.map { p ->
        when (val raw = p[Keys.AUTO_LAUNCH_ON_START]) {
            null, "wallpaper" -> AutoLaunch.Wallpaper
            "last" -> AutoLaunch.LastApp
            else -> runCatching { AutoLaunch.Launch(json.decodeFromString<AppEntry>(raw)) }
                .getOrDefault(AutoLaunch.Wallpaper)
        }
    }
    /** The last app foregrounded on the glasses (for the Last-app restore). */
    val lastForegroundApp: Flow<AppEntry?> = context.dataStore.data.map { p ->
        p[Keys.LAST_FOREGROUND_APP]?.let { runCatching { json.decodeFromString<AppEntry>(it) }.getOrNull() }
    }
    /** Automatically reopen the last app in its original state/bounds on reconnect within 30 min. */
    val autoResumeLastApp30m: Flow<Boolean> = context.dataStore.data.map { p ->
        p[Keys.AUTO_RESUME_LAST_APP_30M] ?: true
    }.distinctUntilChanged()
    /** Forward an actively playing phone video app to the external display on connect. */
    val forwardVideoOnConnect: Flow<Boolean> = context.dataStore.data.map { p ->
        p[Keys.FORWARD_VIDEO_ON_CONNECT] ?: true
    }.distinctUntilChanged()
    suspend fun setForwardVideoOnConnect(enabled: Boolean) = context.dataStore.edit {
        it[Keys.FORWARD_VIDEO_ON_CONNECT] = enabled
    }
    /** Enforced media audio output policy while an external display is connected. */
    val audioOutputMode: Flow<AudioOutputMode> = context.dataStore.data.map { p ->
        AudioOutputMode.fromId(p[Keys.AUDIO_OUTPUT_MODE])
    }.distinctUntilChanged()
    suspend fun setAudioOutputMode(mode: AudioOutputMode) = context.dataStore.edit {
        it[Keys.AUDIO_OUTPUT_MODE] = mode.id
    }
    /** Snapshot of the last active external app + bounds + disconnect wall-clock timestamp. */
    val lastDisconnectedApp: Flow<LastDisconnectedApp?> = context.dataStore.data.map { p ->
        p[Keys.LAST_DISCONNECTED_APP]?.let {
            runCatching { json.decodeFromString<LastDisconnectedApp>(it) }.getOrNull()
        }
    }
    /** Which input interface to open on session start. Default = Trackpad. */
    val autoLaunchInterface: Flow<AutoLaunchInterface> = context.dataStore.data.map { p ->
        when (p[Keys.AUTO_LAUNCH_INTERFACE]) {
            "air_mouse" -> AutoLaunchInterface.AIR_MOUSE
            "remote" -> AutoLaunchInterface.REMOTE
            else -> AutoLaunchInterface.TRACKPAD
        }
    }

    // ─── Button appearance (cosmetic: icon + colors) ───────────────────────
    val backButtonStyle: Flow<ButtonAppearance?> = context.dataStore.data.map { p ->
        p[Keys.BACK_BUTTON_STYLE]?.let {
            runCatching { json.decodeFromString<ButtonAppearance>(it) }.getOrNull()
        }
    }
    val homeButtonStyle: Flow<ButtonAppearance?> = context.dataStore.data.map { p ->
        p[Keys.HOME_BUTTON_STYLE]?.let {
            runCatching { json.decodeFromString<ButtonAppearance>(it) }.getOrNull()
        }
    }
    val appDrawerButtonStyle: Flow<ButtonAppearance?> = context.dataStore.data.map { p ->
        p[Keys.APPDRAWER_BUTTON_STYLE]?.let {
            runCatching { json.decodeFromString<ButtonAppearance>(it) }.getOrNull()
        }
    }
    val keyboardButtonStyle: Flow<ButtonAppearance?> = context.dataStore.data.map { p ->
        p[Keys.KEYBOARD_BUTTON_STYLE]?.let {
            runCatching { json.decodeFromString<ButtonAppearance>(it) }.getOrNull()
        }
    }
    val savedColorPresets: Flow<List<Int>> = context.dataStore.data.map { p ->
        p[Keys.SAVED_COLOR_PRESETS]?.let {
            runCatching { json.decodeFromString<List<Int>>(it) }.getOrNull()
        } ?: emptyList()
    }

    /** Maps a nav-button target id to its DataStore key. Unknown targets fall
     *  back to the Back button key so callers never NPE. */
    private fun buttonStyleKey(target: String) = when (target) {
        "home" -> Keys.HOME_BUTTON_STYLE
        "appdrawer" -> Keys.APPDRAWER_BUTTON_STYLE
        "keyboard" -> Keys.KEYBOARD_BUTTON_STYLE
        else -> Keys.BACK_BUTTON_STYLE
    }

    suspend fun setButtonStyle(target: String, appearance: ButtonAppearance) {
        context.dataStore.edit { it[buttonStyleKey(target)] = json.encodeToString(appearance) }
    }

    /** Clear a button's custom appearance so it reverts to the built-in default
     *  (default icon + muted-purple background + near-white glyph). */
    suspend fun resetButtonStyle(target: String) {
        context.dataStore.edit { it.remove(buttonStyleKey(target)) }
    }

    private fun progKeyStyleKey(target: String) = when (target) {
        "prog_green" -> Keys.PROG_KEY_STYLE_GREEN
        "prog_yellow" -> Keys.PROG_KEY_STYLE_YELLOW
        "prog_blue" -> Keys.PROG_KEY_STYLE_BLUE
        else -> Keys.PROG_KEY_STYLE_RED
    }

    /** Persist a program key's custom appearance ("prog_red"/.../"prog_blue"). */
    suspend fun setProgKeyStyle(target: String, appearance: ProgKeyAppearance) {
        context.dataStore.edit { it[progKeyStyleKey(target)] = json.encodeToString(appearance) }
    }

    /** Clear a program key's custom appearance so it reverts to the built-in look. */
    suspend fun resetProgKeyStyle(target: String) {
        context.dataStore.edit { it.remove(progKeyStyleKey(target)) }
    }

    companion object {
        /** Canonical nav-button order; also the default when the pref is unset or
         *  corrupt. Adding a new nav button here makes it auto-appear (appended)
         *  for users who already have a saved order. */
        val DEFAULT_NAV_ORDER = listOf("back", "home", "appdrawer", "keyboard")

        /** Parse a stored CSV order into a clean list: known ids only, de-duped,
         *  with any missing canonical ids appended in canonical order so the result
         *  always contains exactly the four buttons. */
        fun sanitizeNavOrder(raw: String?): List<String> {
            if (raw.isNullOrBlank()) return DEFAULT_NAV_ORDER
            val kept = raw.split(",").map { it.trim() }
                .filter { it in DEFAULT_NAV_ORDER }
                .distinct()
            return kept + DEFAULT_NAV_ORDER.filter { it !in kept }
        }
    }

    /** Order of the four bottom-bar nav buttons (back/home/appdrawer/keyboard).
     *  Always emits all four ids; the keyboard slot is kept even when its pill is
     *  hidden so the position stays stable. */
    val navButtonOrder: Flow<List<String>> = context.dataStore.data.map { p ->
        sanitizeNavOrder(p[Keys.NAV_BUTTON_ORDER])
    }

    suspend fun setNavButtonOrder(order: List<String>) {
        val clean = sanitizeNavOrder(order.joinToString(","))
        context.dataStore.edit { it[Keys.NAV_BUTTON_ORDER] = clean.joinToString(",") }
    }

    suspend fun resetNavButtonOrder() {
        context.dataStore.edit { it.remove(Keys.NAV_BUTTON_ORDER) }
    }

    /** Save up to 10 custom color presets (most-recent first, de-duplicated). */
    suspend fun addColorPreset(color: Int) {
        context.dataStore.edit { p ->
            val current = p[Keys.SAVED_COLOR_PRESETS]?.let {
                runCatching { json.decodeFromString<List<Int>>(it) }.getOrNull()
            } ?: emptyList()
            val updated = (listOf(color) + current.filter { it != color }).take(10)
            p[Keys.SAVED_COLOR_PRESETS] = json.encodeToString(updated)
        }
    }

    suspend fun removeColorPreset(color: Int) {
        context.dataStore.edit { p ->
            val current = p[Keys.SAVED_COLOR_PRESETS]?.let {
                runCatching { json.decodeFromString<List<Int>>(it) }.getOrNull()
            } ?: emptyList()
            p[Keys.SAVED_COLOR_PRESETS] = json.encodeToString(current.filter { it != color })
        }
    }

    suspend fun clearColorPresets() {
        context.dataStore.edit { p -> p.remove(Keys.SAVED_COLOR_PRESETS) }
    }

    /** Selected home-backdrop wallpaper key for the external display. Defaults
     *  to [Wallpaper.DEFAULT] when unset. */
    /** Per-canvas raw pick (null when that slot was never set). The standard slot
     *  falls back to the legacy single key so existing users keep their pick. */
    fun homeWallpaperRaw(ultrawide: Boolean): Flow<Wallpaper?> = context.dataStore.data.map { p ->
        if (ultrawide) p[Keys.HOME_WALLPAPER_UW]?.let { Wallpaper.byKey(it) }
        else (p[Keys.HOME_WALLPAPER_STD] ?: p[Keys.HOME_WALLPAPER])?.let { Wallpaper.byKey(it) }
    }

    /** Resolved backdrop for a canvas: the slot's pick, or the canvas-aware default
     *  (ultrawide → Cosmic Genesis) when the user hasn't picked one. */
    fun homeWallpaperFor(ultrawide: Boolean): Flow<Wallpaper> =
        homeWallpaperRaw(ultrawide).map { it ?: Wallpaper.effectiveDefault(ultrawide) }

    suspend fun setHomeWallpaper(ultrawide: Boolean, wallpaper: Wallpaper) {
        context.dataStore.edit { p ->
            p[if (ultrawide) Keys.HOME_WALLPAPER_UW else Keys.HOME_WALLPAPER_STD] = wallpaper.key
        }
    }

    /** Clears both canvas slots (and the legacy key) → both revert to defaults. */
    suspend fun resetHomeWallpapers() {
        context.dataStore.edit { p ->
            p.remove(Keys.HOME_WALLPAPER_STD)
            p.remove(Keys.HOME_WALLPAPER_UW)
            p.remove(Keys.HOME_WALLPAPER)
        }
    }

    /** File path of the user-picked custom backdrop image, or null if none set. */
    val customWallpaperPath: Flow<String?> = context.dataStore.data.map { p ->
        p[Keys.CUSTOM_WALLPAPER_PATH]
    }

    suspend fun setCustomWallpaperPath(path: String?) {
        context.dataStore.edit { p ->
            if (path == null) p.remove(Keys.CUSTOM_WALLPAPER_PATH)
            else p[Keys.CUSTOM_WALLPAPER_PATH] = path
        }
    }
    val inputMode: Flow<InputMode> = context.dataStore.data.map { p ->
        when (p[Keys.INPUT_MODE]) {
            "air_mouse" -> InputMode.AIR_MOUSE
            else -> InputMode.TRACKPAD
        }
    }
    val accessMode: Flow<AccessMode> = context.dataStore.data.map { p ->
        when (p[Keys.ACCESS_MODE]) {
            "root" -> AccessMode.ROOT
            "shizuku" -> AccessMode.SHIZUKU
            "none" -> AccessMode.NONE
            // Legacy values from earlier versions where there was no NONE
            null -> AccessMode.NONE
            else -> AccessMode.NONE
        }
    }
    val dockDisplayMode: Flow<DockDisplayMode> = context.dataStore.data.map { p ->
        when (p[Keys.DOCK_DISPLAY_MODE]) {
            "presentation" -> DockDisplayMode.PRESENTATION
            else -> DockDisplayMode.OVERLAY
        }
    }
    /** Last saved session (open apps + bounds on the external display). */
    val lastSession: Flow<SavedSession> = context.dataStore.data.map { p ->
        p[Keys.LAST_SESSION]?.let {
            runCatching { json.decodeFromString<SavedSession>(it) }.getOrNull()
        } ?: SavedSession()
    }
    suspend fun setLastSession(session: SavedSession) = context.dataStore.edit {
        it[Keys.LAST_SESSION] = json.encodeToString(session)
    }

    /** Per-resolution layout memory: a [SavedSession] per canvas width. */
    val sessionsByWidth: Flow<SavedSessions> = context.dataStore.data.map { p ->
        p[Keys.SESSIONS_BY_WIDTH]?.let {
            runCatching { json.decodeFromString<SavedSessions>(it) }.getOrNull()
        } ?: SavedSessions()
    }

    suspend fun setSessionsByWidth(sessions: SavedSessions) = context.dataStore.edit {
        it[Keys.SESSIONS_BY_WIDTH] = json.encodeToString(sessions)
    }

    /** Store/overwrite the remembered layout for a single canvas [width]. */
    suspend fun putSessionForWidth(width: Int, session: SavedSession) {
        if (width <= 0) return
        val cur = sessionsByWidth.first().byWidth
        setSessionsByWidth(SavedSessions(cur + (width to session)))
    }

    /**
     * Pick the layout to restore for a canvas of [w]x[h], plus the scale to apply.
     * If this width has a remembered layout, return it for an EXACT restore (1f).
     * Otherwise seed from the most-recent layout (last session, else newest slot),
     * scaled from its canvas to this one. Empty session = nothing to restore.
     */
    suspend fun sessionForCanvas(w: Int, h: Int): Triple<SavedSession, Float, Float> {
        val map = sessionsByWidth.first().byWidth
        map[w]?.takeIf { it.windows.isNotEmpty() }?.let {
            android.util.Log.d(
                "PortalPadSleep",
                "sessionForCanvas: VISITED width=$w → restore saved (${it.windows.size} win, scale 1.0)",
            )
            return Triple(it, 1f, 1f)
        }
        val last = lastSession.first()
        val seed = last.takeIf { it.windows.isNotEmpty() }
            ?: map.values.filter { it.windows.isNotEmpty() }.maxByOrNull { it.savedAtMillis }
            ?: run {
                android.util.Log.d("PortalPadSleep", "sessionForCanvas: NEVER-VISITED width=$w → no seed available (empty)")
                return Triple(SavedSession(), 1f, 1f)
            }
        val sx = if (seed.canvasWidth > 0) w.toFloat() / seed.canvasWidth else 1f
        val sy = if (seed.canvasHeight > 0) h.toFloat() / seed.canvasHeight else 1f
        android.util.Log.d(
            "PortalPadSleep",
            "sessionForCanvas: NEVER-VISITED width=$w → carry-over seed from width=${seed.canvasWidth} " +
                "(${seed.windows.size} win) scale=${sx}x$sy",
        )
        return Triple(seed, sx, sy)
    }

    val dockConfig: Flow<DockConfig> = context.dataStore.data.map { p ->
        p[Keys.DOCK_CONFIG]?.let {
            runCatching { json.decodeFromString<DockConfig>(it) }.getOrNull()
        } ?: DockConfig()
    }.distinctUntilChanged()
    val topBarConfig: Flow<TopBarConfig> = context.dataStore.data.map { p ->
        p[Keys.TOP_BAR_CONFIG]?.let {
            runCatching { json.decodeFromString<TopBarConfig>(it) }.getOrNull()
        } ?: TopBarConfig()
    }
    val folderWindowConfig: Flow<FolderWindowConfig> = context.dataStore.data.map { p ->
        p[Keys.FOLDER_WINDOW_CONFIG]?.let {
            runCatching { json.decodeFromString<FolderWindowConfig>(it) }.getOrNull()
        } ?: FolderWindowConfig()
    }
    val widgetOverlayConfig: Flow<WidgetOverlayConfig> = context.dataStore.data.map { p ->
        p[Keys.WIDGET_OVERLAY_CONFIG]?.let {
            runCatching { json.decodeFromString<WidgetOverlayConfig>(it) }.getOrNull()
        } ?: WidgetOverlayConfig()
    }
    val displayDpi: Flow<Int> = context.dataStore.data.map { it[Keys.DISPLAY_DPI] ?: 0 }
    val shizukuAuthKey: Flow<String> = context.dataStore.data.map { it[Keys.SHIZUKU_AUTH_KEY] ?: "" }
    val aspectRatio: Flow<String> = context.dataStore.data.map { it[Keys.ASPECT_RATIO] ?: "AUTO" }
    /** Virtual-screen size, 50–100 (% of the aspect-fitted panel). 100 = full. */
    val screenSizePct: Flow<Int> = context.dataStore.data.map {
        (it[Keys.SCREEN_SIZE_PCT] ?: 100).coerceIn(50, 100)
    }

    /** Per-interface Side Mode (corner-dock) configuration. */
    val sideModeConfig: Flow<SideModeConfig> = context.dataStore.data.map { p ->
        p[Keys.SIDE_MODE_CONFIG]?.let {
            runCatching { json.decodeFromString<SideModeConfig>(it) }.getOrNull()
        } ?: SideModeConfig()
    }

    /** True while the last-selected interface was Remote (written by TrackpadActivity). */
    val lastModeWasRemote: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.LAST_MODE_WAS_REMOTE] ?: false }

    /** The live 3-way interface (Air Mouse / Trackpad / Remote), derived from the
     *  persisted input mode + the remote flag — both written on every switch — so
     *  the renderer can pick the right Side Mode config and reposition on switch. */
    val activeSideInterface: Flow<SideInterface> =
        kotlinx.coroutines.flow.combine(inputMode, lastModeWasRemote) { mode, remote ->
            when {
                remote -> SideInterface.REMOTE
                mode == InputMode.AIR_MOUSE -> SideInterface.AIR_MOUSE
                else -> SideInterface.TRACKPAD
            }
        }

    // 3-finger swipe actions. Defaults preserve the original behavior:
    // right→Back, left→Home, down→open notifications, up→close notifications.
    val gestureSwipeUp: Flow<GestureAction> =
        context.dataStore.data.map { GestureAction.fromId(it[Keys.GESTURE_SWIPE_UP] ?: GestureAction.NOTIF_CLOSE.id) }
    val gestureSwipeDown: Flow<GestureAction> =
        context.dataStore.data.map { GestureAction.fromId(it[Keys.GESTURE_SWIPE_DOWN] ?: GestureAction.NOTIF_OPEN.id) }
    val gestureSwipeLeft: Flow<GestureAction> =
        context.dataStore.data.map { GestureAction.fromId(it[Keys.GESTURE_SWIPE_LEFT] ?: GestureAction.HOME.id) }
    val gestureSwipeRight: Flow<GestureAction> =
        context.dataStore.data.map { GestureAction.fromId(it[Keys.GESTURE_SWIPE_RIGHT] ?: GestureAction.BACK.id) }

    val gestureAppUp: Flow<AppEntry?> = context.dataStore.data.map { p ->
        p[Keys.GESTURE_APP_UP]?.let { runCatching { json.decodeFromString<AppEntry>(it) }.getOrNull() }
    }
    val gestureAppDown: Flow<AppEntry?> = context.dataStore.data.map { p ->
        p[Keys.GESTURE_APP_DOWN]?.let { runCatching { json.decodeFromString<AppEntry>(it) }.getOrNull() }
    }
    val gestureAppLeft: Flow<AppEntry?> = context.dataStore.data.map { p ->
        p[Keys.GESTURE_APP_LEFT]?.let { runCatching { json.decodeFromString<AppEntry>(it) }.getOrNull() }
    }
    val gestureAppRight: Flow<AppEntry?> = context.dataStore.data.map { p ->
        p[Keys.GESTURE_APP_RIGHT]?.let { runCatching { json.decodeFromString<AppEntry>(it) }.getOrNull() }
    }
    val colorTuning: Flow<String> = context.dataStore.data.map { it[Keys.COLOR_TUNING] ?: "" }
    val gpuColorPipeline: Flow<Boolean> = context.dataStore.data.map { it[Keys.GPU_COLOR_PIPELINE] ?: true }
    val panelSystemMirror: Flow<Boolean> = context.dataStore.data.map { it[Keys.PANEL_SYSTEM_MIRROR] ?: false }
    val mirrorKeepAwakeMode: Flow<MirrorKeepAwakeMode> = context.dataStore.data.map {
        MirrorKeepAwakeMode.fromId(it[Keys.MIRROR_KEEP_AWAKE_MODE])
    }
    val forceSeparateDisplay: Flow<Boolean> = context.dataStore.data.map {
        it[Keys.FORCE_SEPARATE_DISPLAY] ?: false
    }
    val extMouseAutoCapture: Flow<Boolean> = context.dataStore.data.map {
        it[Keys.EXT_MOUSE_AUTO_CAPTURE] ?: true
    }
    val readerFlipMethod: Flow<Int> = context.dataStore.data.map { it[Keys.READER_FLIP_METHOD] ?: 1 }
    val remoteInputMode: Flow<Int> = context.dataStore.data.map { it[Keys.REMOTE_INPUT_MODE] ?: 0 }
    val remoteGesturePreset: Flow<Int> =
        context.dataStore.data.map { it[Keys.REMOTE_GESTURE_PRESET] ?: 0 }
    val remoteGestureRailLeft: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.REMOTE_GESTURE_RAIL_LEFT] ?: false }
    // Global haptic strength (ms). Default 25 = Medium. 0 = Off.
    val vibrationMs: Flow<Int> = context.dataStore.data.map { it[Keys.VIBRATION_MS] ?: 25 }
    fun bool(key: Preferences.Key<Boolean>, default: Boolean = false): Flow<Boolean> =
        context.dataStore.data.map { it[key] ?: default }

    fun int(key: Preferences.Key<Int>, default: Int = 0): Flow<Int> =
        context.dataStore.data.map { it[key] ?: default }

    suspend fun setInt(key: Preferences.Key<Int>, value: Int) =
        context.dataStore.edit { it[key] = value }

    fun float(key: Preferences.Key<Float>, default: Float = 0f): Flow<Float> =
        context.dataStore.data.map { it[key] ?: default }

    suspend fun setFloat(key: Preferences.Key<Float>, value: Float) =
        context.dataStore.edit { it[key] = value }

    fun string(key: Preferences.Key<String>, default: String = ""): Flow<String> =
        context.dataStore.data.map { it[key] ?: default }

    suspend fun setString(key: Preferences.Key<String>, value: String) =
        context.dataStore.edit { it[key] = value }

    suspend fun setHomeAction(entry: AppEntry?) = context.dataStore.edit { p ->
        if (entry == null) p.remove(Keys.HOME_ACTION)
        else p[Keys.HOME_ACTION] = json.encodeToString(entry)
    }

    /** Set/clear one Quick Wheel slot. Null clears it back to "+". */
    suspend fun setWheelSlot(index: Int, entry: AppEntry?) = context.dataStore.edit { p ->
        val cur = p[Keys.WHEEL_SLOTS]?.let {
            runCatching { json.decodeFromString<List<AppEntry?>>(it) }.getOrNull()
        } ?: emptyList()
        val list: MutableList<AppEntry?> = MutableList(QUICK_WHEEL_SLOTS) { i -> cur.getOrNull(i) }
        if (index in 0 until QUICK_WHEEL_SLOTS) list[index] = entry
        p[Keys.WHEEL_SLOTS] = json.encodeToString(list as List<AppEntry?>)
    }
    /** Set/clear a color key's launch action. Null clears it (→ key-send mode). */
    suspend fun setProgAction(colorKey: androidx.datastore.preferences.core.Preferences.Key<String>, entry: AppEntry?) =
        context.dataStore.edit { p ->
            if (entry == null) p.remove(colorKey)
            else p[colorKey] = json.encodeToString(entry)
        }
    suspend fun setBackAction(action: BackAction) = context.dataStore.edit { p ->
        p[Keys.BACK_ACTION] = when (action) {
            BackAction.System -> "system"
            is BackAction.Launch -> json.encodeToString(action.entry)
        }
    }
    suspend fun setAutoLaunchOnStart(value: AutoLaunch) = context.dataStore.edit { p ->
        p[Keys.AUTO_LAUNCH_ON_START] = when (value) {
            AutoLaunch.Wallpaper -> "wallpaper"
            AutoLaunch.LastApp -> "last"
            is AutoLaunch.Launch -> json.encodeToString(value.entry)
        }
    }
    suspend fun setLastForegroundApp(entry: AppEntry?) = context.dataStore.edit { p ->
        if (entry == null) p.remove(Keys.LAST_FOREGROUND_APP)
        else p[Keys.LAST_FOREGROUND_APP] = json.encodeToString(entry)
    }
    suspend fun setAutoResumeLastApp30m(enabled: Boolean) = context.dataStore.edit { p ->
        p[Keys.AUTO_RESUME_LAST_APP_30M] = enabled
    }
    suspend fun setLastDisconnectedApp(app: LastDisconnectedApp?) = context.dataStore.edit { p ->
        if (app == null) p.remove(Keys.LAST_DISCONNECTED_APP)
        else p[Keys.LAST_DISCONNECTED_APP] = json.encodeToString(app)
    }
    suspend fun setAutoLaunchInterface(value: AutoLaunchInterface) = context.dataStore.edit { p ->
        p[Keys.AUTO_LAUNCH_INTERFACE] = when (value) {
            AutoLaunchInterface.AIR_MOUSE -> "air_mouse"
            AutoLaunchInterface.TRACKPAD -> "trackpad"
            AutoLaunchInterface.REMOTE -> "remote"
        }
    }
    suspend fun setInputMode(mode: InputMode) = context.dataStore.edit {
        it[Keys.INPUT_MODE] = if (mode == InputMode.AIR_MOUSE) "air_mouse" else "trackpad"
    }
    suspend fun setAccessMode(mode: AccessMode) = context.dataStore.edit {
        it[Keys.ACCESS_MODE] = when (mode) {
            AccessMode.NONE -> "none"
            AccessMode.ROOT -> "root"
            AccessMode.SHIZUKU -> "shizuku"
        }
    }

    suspend fun setDockDisplayMode(mode: DockDisplayMode) = context.dataStore.edit {
        it[Keys.DOCK_DISPLAY_MODE] = if (mode == DockDisplayMode.PRESENTATION) "presentation" else "overlay"
    }
    suspend fun setDockConfig(config: DockConfig) = context.dataStore.edit {
        it[Keys.DOCK_CONFIG] = json.encodeToString(config)
    }

    /** First-run only: if no dock config has ever been saved, seed a sensible
     *  default so a fresh install isn't an empty dock. Everything is filtered to
     *  apps actually installed (queried via PackageManager) so there are no broken
     *  icons. Never re-seeds once a config exists — including an empty one the user
     *  created by clearing the dock — because the saved key is then present. */
    suspend fun seedDefaultDockIfNeeded() {
        // A present key (even one that decodes to an empty dock) means the user has
        // a saved config; never clobber it.
        val already = runCatching { context.dataStore.data.first()[Keys.DOCK_CONFIG] }.getOrNull()
        if (already != null) return

        val pm = context.packageManager
        fun installed(pkg: String): Boolean =
            runCatching { pm.getLaunchIntentForPackage(pkg) != null }.getOrDefault(false)
        fun shortcut(pkg: String, fallbackLabel: String): DockItem.Shortcut? {
            if (!installed(pkg)) return null
            // Prefer the app's real (localized) label; fall back to a short name.
            val label = runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: fallbackLabel
            return DockItem.Shortcut(
                id = "sc_seed_$pkg",
                label = label,
                app = AppEntry(packageName = pkg, componentName = null, label = label),
            )
        }

        val items = mutableListOf<DockItem>()

        // Stock Google apps → a "Google" folder (only the installed ones). Chrome
        // and YouTube are intentionally NOT here — they're pulled out as standalone
        // hero icons below.
        val googleApps = listOf(
            "com.google.android.gm" to "Gmail",
            "com.google.android.apps.maps" to "Maps",
            "com.google.android.apps.docs" to "Drive",
            "com.google.android.apps.photos" to "Photos",
            "com.google.android.calendar" to "Calendar",
            "com.google.android.apps.youtube.music" to "YT Music",
            "com.google.android.videos" to "Google TV",
            "com.google.android.apps.tachyon" to "Meet",
            "com.google.android.keep" to "Keep",
            "com.google.android.apps.translate" to "Translate",
            "com.google.android.contacts" to "Contacts",
            "com.google.android.apps.messaging" to "Messages",
            "com.google.android.apps.chromecast.app" to "Home",
            "com.google.android.apps.walletnfcrel" to "Wallet",
            "com.google.android.apps.magazines" to "News",
            "com.google.android.googlequicksearchbox" to "Google",
        ).mapNotNull { (pkg, label) -> shortcut(pkg, label) }
        if (googleApps.isNotEmpty()) {
            items += DockItem.Folder(
                id = "folder_google",
                label = "Google",
                contents = googleApps,
            )
        }

        // Files — prefer Files by Google, fall back to Samsung "My Files".
        (shortcut("com.google.android.apps.nbu.files", "Files")
            ?: shortcut("com.sec.android.app.myfiles", "My Files"))
            ?.let { items += it }

        // A few standalone hero apps so the dock feels populated right away.
        listOf(
            "com.android.chrome" to "Chrome",
            "com.google.android.youtube" to "YouTube",
            "com.android.vending" to "Play Store",
            "com.android.settings" to "Settings",
        ).forEach { (pkg, label) -> shortcut(pkg, label)?.let { items += it } }

        // Write the seed (or an explicit empty config if literally nothing matched,
        // so we don't re-query on every launch).
        setDockConfig(DockConfig(items = items))
    }
    suspend fun setTopBarConfig(config: TopBarConfig) = context.dataStore.edit {
        it[Keys.TOP_BAR_CONFIG] = json.encodeToString(config)
    }
    suspend fun setFolderWindowConfig(config: FolderWindowConfig) = context.dataStore.edit {
        it[Keys.FOLDER_WINDOW_CONFIG] = json.encodeToString(config)
    }
    suspend fun setWidgetOverlayConfig(config: WidgetOverlayConfig) = context.dataStore.edit {
        it[Keys.WIDGET_OVERLAY_CONFIG] = json.encodeToString(config)
    }
    suspend fun setDisplayDpi(value: Int) = context.dataStore.edit { it[Keys.DISPLAY_DPI] = value }
    suspend fun setShizukuAuthKey(value: String) = context.dataStore.edit { it[Keys.SHIZUKU_AUTH_KEY] = value }
    suspend fun setAspectRatio(value: String) = context.dataStore.edit { it[Keys.ASPECT_RATIO] = value }
    suspend fun setScreenSizePct(value: Int) = context.dataStore.edit {
        it[Keys.SCREEN_SIZE_PCT] = value.coerceIn(50, 100)
    }

    suspend fun setSideModeConfig(c: SideModeConfig) = context.dataStore.edit {
        it[Keys.SIDE_MODE_CONFIG] = json.encodeToString(c)
    }

    suspend fun setGestureSwipeUp(a: GestureAction) = context.dataStore.edit { it[Keys.GESTURE_SWIPE_UP] = a.id }
    suspend fun setGestureSwipeDown(a: GestureAction) = context.dataStore.edit { it[Keys.GESTURE_SWIPE_DOWN] = a.id }
    suspend fun setGestureSwipeLeft(a: GestureAction) = context.dataStore.edit { it[Keys.GESTURE_SWIPE_LEFT] = a.id }
    suspend fun setGestureSwipeRight(a: GestureAction) = context.dataStore.edit { it[Keys.GESTURE_SWIPE_RIGHT] = a.id }

    // Set a direction to LAUNCH_APP with the given app in one edit.
    suspend fun setGestureAppUp(entry: AppEntry) = context.dataStore.edit {
        it[Keys.GESTURE_SWIPE_UP] = GestureAction.LAUNCH_APP.id; it[Keys.GESTURE_APP_UP] = json.encodeToString(entry)
    }
    suspend fun setGestureAppDown(entry: AppEntry) = context.dataStore.edit {
        it[Keys.GESTURE_SWIPE_DOWN] = GestureAction.LAUNCH_APP.id; it[Keys.GESTURE_APP_DOWN] = json.encodeToString(entry)
    }
    suspend fun setGestureAppLeft(entry: AppEntry) = context.dataStore.edit {
        it[Keys.GESTURE_SWIPE_LEFT] = GestureAction.LAUNCH_APP.id; it[Keys.GESTURE_APP_LEFT] = json.encodeToString(entry)
    }
    suspend fun setGestureAppRight(entry: AppEntry) = context.dataStore.edit {
        it[Keys.GESTURE_SWIPE_RIGHT] = GestureAction.LAUNCH_APP.id; it[Keys.GESTURE_APP_RIGHT] = json.encodeToString(entry)
    }
    suspend fun setColorTuning(value: String) = context.dataStore.edit { it[Keys.COLOR_TUNING] = value }
    suspend fun setGpuColorPipeline(value: Boolean) = context.dataStore.edit { it[Keys.GPU_COLOR_PIPELINE] = value }
    suspend fun setPanelSystemMirror(value: Boolean) = context.dataStore.edit { it[Keys.PANEL_SYSTEM_MIRROR] = value }
    suspend fun setMirrorKeepAwakeMode(mode: MirrorKeepAwakeMode) = context.dataStore.edit {
        it[Keys.MIRROR_KEEP_AWAKE_MODE] = mode.id
    }
    suspend fun setForceSeparateDisplay(enabled: Boolean) = context.dataStore.edit {
        it[Keys.FORCE_SEPARATE_DISPLAY] = enabled
    }
    suspend fun setReaderFlipMethod(value: Int) = context.dataStore.edit { it[Keys.READER_FLIP_METHOD] = value }
    suspend fun setRemoteInputMode(value: Int) = context.dataStore.edit { it[Keys.REMOTE_INPUT_MODE] = value }
    suspend fun setRemoteGesturePreset(value: Int) =
        context.dataStore.edit { it[Keys.REMOTE_GESTURE_PRESET] = value }
    suspend fun setRemoteGestureRailLeft(value: Boolean) =
        context.dataStore.edit { it[Keys.REMOTE_GESTURE_RAIL_LEFT] = value }
    suspend fun setVibrationMs(value: Int) = context.dataStore.edit { it[Keys.VIBRATION_MS] = value }
    suspend fun setBool(key: Preferences.Key<Boolean>, value: Boolean) =
        context.dataStore.edit { it[key] = value }

    /**
     * Persist the three preferences that define one keyboard mode in a single
     * DataStore transaction. The keyboard picker used to write them one at a
     * time, briefly publishing impossible combinations while a mode changed
     * (for example, external routing paired with an old relay landing tab).
     */
    suspend fun setKeyboardMode(
        imeOnExternal: Boolean,
        autoOpenRelay: Boolean,
        relayOpensOnAndroidTab: Boolean,
        portalPadKbOnDisplay: Boolean,
        portalPadKbFloating: Boolean,
    ) = context.dataStore.edit { p ->
        p[Keys.IME_ON_EXTERNAL] = imeOnExternal
        p[Keys.AUTO_OPEN_RELAY_ON_FIELD] = autoOpenRelay
        p[Keys.RELAY_OPENS_ON_ANDROID_TAB] = relayOpensOnAndroidTab
        p[Keys.PORTALPAD_KB_ON_DISPLAY] = portalPadKbOnDisplay
        p[Keys.PORTALPAD_KB_FLOATING] = portalPadKbFloating
    }

    /**
     * Wipe every stored preference. Used by Settings → Diagnostics → Reset
     * to factory defaults. After this returns, the process should be killed
     * so the next launch reads pure defaults everywhere.
     */
    suspend fun clearAllForFactoryReset() {
        context.dataStore.edit { it.clear() }
    }
}

enum class InputMode { TRACKPAD, AIR_MOUSE }

/**
 * The privilege source PortalPad uses for elevated operations: launching apps
 * on external displays, AR-glasses virtual-display setup, IME policy.
 *
 * NONE: Accessibility-only mode. Clicks via [AccessibilityService.dispatchGesture]
 *       work on regular HDMI displays. AR glasses won't work without elevation.
 * SHIZUKU: Use Shizuku's user service for elevated operations.
 * ROOT: Use root shell for elevated operations.
 *
 * Note: Accessibility runs INDEPENDENTLY of this setting. Even with Shizuku
 * or Root selected, Accessibility can still be enabled — it's needed for
 * auto-keyboard popup, navigation buttons, and other observation features.
 */
enum class AccessMode { NONE, SHIZUKU, ROOT }

enum class DockDisplayMode { OVERLAY, PRESENTATION }

sealed class BackAction {
    data object System : BackAction()
    data class Launch(val entry: AppEntry) : BackAction()
}

/** What appears on the external display when a session starts (service start /
 *  display attach). Independent of the Home button. */
sealed class AutoLaunch {
    /** PortalPad's wallpaper backdrop (the default). */
    data object Wallpaper : AutoLaunch()
    /** Re-open the last app that was foregrounded on the glasses. */
    data object LastApp : AutoLaunch()
    /** Launch a specific app/activity. */
    data class Launch(val entry: AppEntry) : AutoLaunch()
}

/** Which input interface the trackpad opens to on a session start. Overrides
 *  the "restore last-used interface" behavior when set. */
enum class AutoLaunchInterface { AIR_MOUSE, TRACKPAD, REMOTE }

/** Keep-awake policy applied to the phone's primary display while connected to an external display,
 *  preventing Pixel screen timeouts from turning off mirrored external outputs. */
enum class MirrorKeepAwakeMode(
    val id: String,
    val title: String,
    val description: String,
) {
    OFF(
        id = "off",
        title = "Off (system timeout)",
        description = "Use normal Android screen timeout. In mirroring mode, the external display turns off with the phone screen.",
    ),
    KEEP_SCREEN_ON(
        id = "keep_screen_on",
        title = "Keep screen awake",
        description = "Prevents phone screen sleep while connected so mirrored external displays stay on.",
    ),
    DIM_SCREEN(
        id = "dim_screen",
        title = "Dim screen while awake",
        description = "Keeps the phone awake at minimum brightness during mirroring to save battery.",
    ),
    OLED_BLACK_OVERLAY(
        id = "oled_black_overlay",
        title = "OLED black overlay",
        description = "Keeps mirroring awake with a pure-black touchable cover over the controls (double-tap to reveal).",
    );

    companion object {
        fun fromId(id: String?): MirrorKeepAwakeMode =
            entries.firstOrNull { it.id == id } ?: OFF
    }
}

/** Enforced media audio routing priority once an external display session starts. */
enum class AudioOutputMode(
    val id: String,
    val title: String,
    val description: String,
) {
    AUTO(
        id = "auto",
        title = "Auto: Bluetooth → External display → Phone",
        description = "Uses Bluetooth audio if connected, then External display audio, then Phone audio. Automatically shifts to Bluetooth if it connects mid-session.",
    ),
    BLUETOOTH(
        id = "bluetooth",
        title = "Bluetooth audio",
        description = "Forces media audio to a connected Bluetooth audio device while an external display is active.",
    ),
    EXTERNAL_DISPLAY(
        id = "external_display",
        title = "External display audio",
        description = "Forces media audio to the external display, glasses, or HDMI/DisplayPort speakers.",
    ),
    PHONE(
        id = "phone",
        title = "Phone audio",
        description = "Forces media audio to stay on the phone speaker or wired headphones.",
    );

    companion object {
        fun fromId(id: String?): AudioOutputMode =
            entries.firstOrNull { it.id == id } ?: AUTO
    }
}
