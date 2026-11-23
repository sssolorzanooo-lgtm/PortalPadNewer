package com.portalpad.app.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent

/**
 * Media + audio control that **does not** require Shizuku or root.
 *
 * Volume buttons → [AudioManager.adjustStreamVolume]: zero permissions, always
 * works, shows the system volume UI.
 *
 * Media keys (play/pause/next/prev/ff/rew) → tries three paths in order:
 *   1. [MediaSessionManager.dispatchMediaKeyEvent] on the active session
 *      (requires Notification Access — the user grants this once in system
 *      settings, no Shizuku needed)
 *   2. [Intent.ACTION_MEDIA_BUTTON] ordered broadcast — works without any
 *      permission but less reliable on Android 13+
 *   3. Fallback through [InputInjector.pressKey] (only useful if Shizuku/Root
 *      is set up anyway)
 *
 * D-pad and Enter still go through InputInjector because they need to target
 * the external display, which media-key paths can't do.
 */
class MediaController(private val context: Context) {

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mediaSessionManager =
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val listenerComponent =
        ComponentName(context, PortalPadNotificationListenerService::class.java)

    // Serializes volume writes off the main thread. setStreamVolume can be slow
    // on some devices and the slider calls it rapidly during a drag; doing that
    // on the UI thread caused ANRs. Single-thread = ordered, last-write-wins.
    private val volumeExecutor =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "portalpad-volume").apply { isDaemon = true }
        }

    // Off-UI executor for media session resolution & play/pause verification fallbacks.
    private val mediaExecutor =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "portalpad-media").apply { isDaemon = true }
        }
    private val externalPlayPauseGen = java.util.concurrent.atomic.AtomicLong(0L)

    /** True when the user has granted Notification Access to PortalPad. */
    val hasNotificationAccess: Boolean
        get() {
            val enabled = Settings.Secure.getString(
                context.contentResolver, "enabled_notification_listeners",
            ) ?: return false
            return enabled.split(":").any {
                it.equals(listenerComponent.flattenToString(), ignoreCase = true)
            }
        }

    // ─────────────────────────── volume (no permission) ───────────────────────────

    fun volumeUp() {
        val stream = AudioManager.STREAM_MUSIC
        val before = runCatching { audioManager.getStreamVolume(stream) }.getOrDefault(-1)
        val ok = runCatching {
            audioManager.adjustStreamVolume(
                stream, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI,
            )
        }.isSuccess
        val after = runCatching { audioManager.getStreamVolume(stream) }.getOrDefault(-1)
        Log.i(TAG, "DIAG-VOLUME action=UP ok=$ok before=$before after=$after")
    }

    fun volumeDown() {
        val stream = AudioManager.STREAM_MUSIC
        val before = runCatching { audioManager.getStreamVolume(stream) }.getOrDefault(-1)
        val ok = runCatching {
            audioManager.adjustStreamVolume(
                stream, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI,
            )
        }.isSuccess
        val after = runCatching { audioManager.getStreamVolume(stream) }.getOrDefault(-1)
        Log.i(TAG, "DIAG-VOLUME action=DOWN ok=$ok before=$before after=$after")
    }

    /**
     * Set STREAM_MUSIC volume to an absolute percentage (0..100) WITHOUT
     * showing Android's system volume UI — used by the in-app volume slider so
     * dragging it doesn't pop the OS volume panel over the glasses display.
     * flags = 0 is the key: it changes the level silently.
     *
     * IMPORTANT: the AudioManager calls run on a dedicated background thread,
     * not the caller's (main/UI) thread. On some devices setStreamVolume is
     * slow (it walks the audio policy + fires ContentObservers), and the slider
     * calls this rapidly during a drag — doing it on the main thread jammed the
     * UI thread and triggered an ANR ("input dispatching timed out"). The
     * single-thread executor keeps writes ordered (last-write-wins) while never
     * blocking the UI.
     */
    fun setVolumePct(pct: Int) {
        volumeExecutor.execute {
            try {
                val stream = AudioManager.STREAM_MUSIC
                val before = audioManager.getStreamVolume(stream)
                val max = audioManager.getStreamMaxVolume(stream).coerceAtLeast(1)
                val level = (pct.coerceIn(0, 100) * max + 50) / 100   // round to nearest step
                // Unmute first if needed, so dragging up from a muted state
                // actually produces sound rather than raising a muted stream.
                if (level > 0 && audioManager.isStreamMute(stream)) {
                    runCatching { audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0) }
                }
                audioManager.setStreamVolume(stream, level.coerceIn(0, max), 0)
                val after = audioManager.getStreamVolume(stream)
                Log.i(
                    TAG,
                    "DIAG-VOLUME action=SET_PCT requested=${pct.coerceIn(0, 100)} " +
                        "before=$before target=${level.coerceIn(0, max)} after=$after max=$max",
                )
            } catch (t: Throwable) {
                Log.w(TAG, "setVolumePct failed", t)
            }
        }
    }

    /**
     * Saved volume from before muting, used to restore on unmute. -1 = no
     * saved value (we're not currently in a mute-via-our-button state).
     * AudioManager's own ADJUST_TOGGLE_MUTE internally remembers, but
     * behavior varies across OEMs (some restore to 1, not previous level).
     * Tracking it ourselves guarantees the restore is correct on Samsung.
     */
    private var preMuteVolume: Int = -1

    /**
     * Toggle mute. If currently NOT muted: save current volume, then mute.
     * If currently muted: restore saved volume (or 50% as a safe fallback
     * if we don't have a saved value — e.g. the user muted via another app).
     */
    fun mute() {
        val stream = AudioManager.STREAM_MUSIC
        val before = audioManager.getStreamVolume(stream)
        val isMuted = audioManager.isStreamMute(stream) || audioManager.getStreamVolume(stream) == 0
        if (isMuted) {
            // Unmute. Prefer restoring our saved level. If we don't have one,
            // pick a safe default (50% of max). Use setStreamVolume directly
            // so we explicitly set the target level — ADJUST_UNMUTE on its
            // own restores AudioManager's internal saved level, which on
            // Samsung sometimes ends up at volume 1.
            val max = audioManager.getStreamMaxVolume(stream)
            val target = if (preMuteVolume > 0) preMuteVolume else (max / 2).coerceAtLeast(1)
            audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
            audioManager.setStreamVolume(stream, target.coerceIn(0, max), AudioManager.FLAG_SHOW_UI)
            preMuteVolume = -1
        } else {
            // Save current level then mute. ADJUST_MUTE on STREAM_MUSIC sets
            // the stream's volume to 0 internally while preserving the
            // "saved" volume in AudioManager, but we keep our own copy for
            // reliable cross-OEM restore.
            preMuteVolume = audioManager.getStreamVolume(stream)
            audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
        }
        val after = runCatching { audioManager.getStreamVolume(stream) }.getOrDefault(-1)
        val mutedAfter = runCatching { audioManager.isStreamMute(stream) }.getOrDefault(after == 0)
        Log.i(
            TAG,
            "DIAG-VOLUME action=${if (isMuted) "UNMUTE" else "MUTE"} " +
                "before=$before after=$after mutedAfter=$mutedAfter",
        )
    }

    /** Current media volume as 0-100, for UI (quick-settings volume pill). */
    fun volumePct(): Int = runCatching {
        val stream = AudioManager.STREAM_MUSIC
        val max = audioManager.getStreamMaxVolume(stream).coerceAtLeast(1)
        (audioManager.getStreamVolume(stream) * 100) / max
    }.getOrDefault(0)

    /**
     * Map a displayed percentage to Android's real discrete music-volume step.
     * Gesture haptics use this to tick only when the OS level would actually
     * change, rather than buzzing for every intermediate 5% visual increment.
     */
    fun volumeStepForPercent(pct: Int): Int = runCatching {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            .coerceAtLeast(1)
        (pct.coerceIn(0, 100) * max + 50) / 100
    }.getOrDefault(pct.coerceIn(0, 100))

    /** Current muted state — for UI to reflect the toggle accurately. */
    fun isMuted(): Boolean {
        val stream = AudioManager.STREAM_MUSIC
        return audioManager.isStreamMute(stream) || audioManager.getStreamVolume(stream) == 0
    }

    // ─────────────────────────── media keys (no Shizuku needed) ───────────────────

    fun playPause() {
        val gen = externalPlayPauseGen.incrementAndGet()
        mediaExecutor.execute {
            if (externalPlayPauseGen.get() != gen) return@execute
            logSessions("key PLAY_PAUSE")
            val active = if (hasNotificationAccess) {
                pickAppSession()?.let(::RealSessionHandle)
            } else null
            if (active != null) {
                dispatchPlayPauseSmart(
                    initial = active,
                    querySessionForPkg = { pkg ->
                        findBestSessionForPackage(pkg)?.let(::RealSessionHandle)
                    },
                    sendPackageBroadcast = { pkg, keyCode ->
                        sendViaPackageBroadcast(pkg, keyCode)
                    },
                    sleepMs = { ms -> runCatching { Thread.sleep(ms) } },
                    shouldAbort = { externalPlayPauseGen.get() != gen },
                )
            } else {
                sendViaBroadcast(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            }
        }
    }

    /**
     * Play or pause media **strictly** for the app currently displayed on the
     * external display. Never targets background phone apps or sends an
     * unscoped global `ACTION_MEDIA_BUTTON` broadcast.
     */
    fun playPauseExternalDisplay(resolveVisiblePackages: () -> List<String>) {
        val gen = externalPlayPauseGen.incrementAndGet()
        mediaExecutor.execute {
            if (externalPlayPauseGen.get() != gen) return@execute
            val visiblePackages = runCatching { resolveVisiblePackages() }.getOrDefault(emptyList())
            if (visiblePackages.isEmpty()) {
                Log.i(
                    TAG,
                    "DIAG-MEDIA playPauseExternalDisplay -> no app visible on external display; ignoring",
                )
                return@execute
            }
            val sessions = if (hasNotificationAccess) {
                runCatching {
                    mediaSessionManager.getActiveSessions(listenerComponent)
                }.getOrDefault(emptyList()).map(::RealSessionHandle)
            } else {
                emptyList()
            }
            val target = selectSessionForExternalPackages(
                sessions = sessions,
                visibleExternalPackages = visiblePackages,
                ownPackageName = context.packageName,
            )
            if (target != null) {
                Log.i(
                    TAG,
                    "DIAG-MEDIA playPauseExternalDisplay -> ${target.snapshot.packageName} " +
                        "state=${stateName(target.snapshot.state)} visible=$visiblePackages",
                )
                dispatchPlayPauseSmart(
                    initial = target,
                    querySessionForPkg = { pkg ->
                        findBestSessionForPackage(pkg)?.let(::RealSessionHandle)
                    },
                    sendPackageBroadcast = { pkg, keyCode ->
                        sendViaPackageBroadcast(pkg, keyCode)
                    },
                    sleepMs = { ms -> runCatching { Thread.sleep(ms) } },
                    shouldAbort = { externalPlayPauseGen.get() != gen },
                )
                return@execute
            }

            // No active MediaSession for the visible external app (e.g. Notification Access
            // off, or player deactivated its session while paused). Scope ACTION_MEDIA_BUTTON
            // strictly to the top visible external package so background apps never trigger.
            val topPkg = visiblePackages.first()
            Log.i(
                TAG,
                "DIAG-MEDIA playPauseExternalDisplay -> no MediaSession for visible=$visiblePackages; " +
                    "package-scoped broadcast to $topPkg",
            )
            sendViaPackageBroadcast(topPkg, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        }
    }

    fun next() = sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
    fun prev() = sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    fun fastForward() = seekRelativeOrKey(SKIP_MS, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
    fun rewind() = seekRelativeOrKey(-SKIP_MS, KeyEvent.KEYCODE_MEDIA_REWIND)
    fun stop() = sendMediaKey(KeyEvent.KEYCODE_MEDIA_STOP)

    /**
     * Seek forward (+) or backward (-) by [totalSeconds] in one shot when the
     * active media session supports ACTION_SEEK_TO, or by dispatching matching
     * 10-second FF/REW media key pulses otherwise.
     */
    fun seekBySeconds(totalSeconds: Int) {
        if (totalSeconds == 0) return
        val deltaMs = totalSeconds * 1000L
        val fallbackKey = if (totalSeconds > 0) {
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
        } else {
            KeyEvent.KEYCODE_MEDIA_REWIND
        }
        val session = pickAppSession()
        val state = session?.playbackState
        val canSeek = state != null && state.position >= 0 &&
            (state.actions and android.media.session.PlaybackState.ACTION_SEEK_TO) != 0L
        if (session != null && canSeek) {
            val basePosition = if (
                state!!.state == android.media.session.PlaybackState.STATE_PLAYING &&
                state.lastPositionUpdateTime > 0L
            ) {
                val elapsed = (SystemClock.elapsedRealtime() - state.lastPositionUpdateTime).coerceAtLeast(0L)
                state.position + (elapsed * state.playbackSpeed).toLong()
            } else {
                state.position
            }
            val dur = session.metadata?.getLong(
                android.media.MediaMetadata.METADATA_KEY_DURATION,
            ) ?: -1L
            var target = basePosition + deltaMs
            if (target < 0L) target = 0L
            if (dur > 0L && target > dur) target = dur
            Log.i(TAG, "DIAG-MEDIA seekBySeconds ${totalSeconds}s -> ${session.packageName} seekTo=$target")
            runCatching { session.transportControls.seekTo(target) }
            return
        }
        val steps = (kotlin.math.abs(totalSeconds) / 10).coerceIn(1, 30)
        Log.i(TAG, "DIAG-MEDIA seekBySeconds ${totalSeconds}s -> fallback ${keyName(fallbackKey)} x$steps")
        repeat(steps) {
            sendMediaKey(fallbackKey)
        }
    }

    /**
     * (currentMs, durationMs) for the active media session. Returns null when
     * no session is playing or notification access isn't granted.
     */
    fun getCurrentPlaybackPosition(): Pair<Long, Long>? {
        if (!hasNotificationAccess) return null
        return try {
            val session = pickAppSession() ?: return null
            val state = session.playbackState ?: return null
            val duration = session.metadata?.getLong(
                android.media.MediaMetadata.METADATA_KEY_DURATION
            ) ?: -1L
            if (duration <= 0) return null
            state.position to duration
        } catch (t: Throwable) {
            Log.w(TAG, "getCurrentPlaybackPosition failed", t)
            null
        }
    }

    /**
     * True when a media session is actively PLAYING (not paused/stopped/buffering).
     * Used by the media cursor auto-hide. Returns false when notification access
     * isn't granted (we can't enumerate sessions) or no session is playing.
     */
    fun isPlaying(): Boolean {
        if (!hasNotificationAccess) return false
        return try {
            pickAppSession()?.playbackState?.state ==
                android.media.session.PlaybackState.STATE_PLAYING
        } catch (t: Throwable) {
            Log.w(TAG, "isPlaying check failed", t)
            false
        }
    }

    /** Packages currently in STATE_PLAYING or STATE_BUFFERING, excluding PortalPad itself. */
    fun getPlayingPackageNames(): List<String> {
        if (!hasNotificationAccess) return emptyList()
        return runCatching {
            val own = context.packageName
            mediaSessionManager.getActiveSessions(listenerComponent)
                .filter { s ->
                    s.packageName != own && (
                        s.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING ||
                            s.playbackState?.state == android.media.session.PlaybackState.STATE_BUFFERING
                        )
                }
                .map { it.packageName }
                .distinct()
        }.getOrDefault(emptyList())
    }

    /** True if [pkg] has an active session in PLAYING or BUFFERING state. */
    fun isPackagePlaying(pkg: String): Boolean {
        if (!hasNotificationAccess) return false
        return runCatching {
            mediaSessionManager.getActiveSessions(listenerComponent).any { s ->
                s.packageName == pkg && (
                    s.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING ||
                        s.playbackState?.state == android.media.session.PlaybackState.STATE_BUFFERING
                    )
            }
        }.getOrDefault(false)
    }

    /** Nudge [pkg] (or current session fallback) to keep playing after task forwarding. */
    fun resumePackagePlayback(pkg: String): Boolean {
        if (hasNotificationAccess) {
            val session = runCatching {
                mediaSessionManager.getActiveSessions(listenerComponent)
                    .firstOrNull { it.packageName == pkg }
            }.getOrNull()
            if (session != null) {
                val st = session.playbackState?.state
                if (st == android.media.session.PlaybackState.STATE_PLAYING ||
                    st == android.media.session.PlaybackState.STATE_BUFFERING
                ) {
                    return true
                }
                runCatching { session.transportControls.play() }
                val now = SystemClock.uptimeMillis()
                runCatching {
                    session.dispatchMediaButtonEvent(
                        KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY, 0),
                    )
                    session.dispatchMediaButtonEvent(
                        KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY, 0),
                    )
                }
                return true
            }
        }
        return sendViaBroadcast(KeyEvent.KEYCODE_MEDIA_PLAY)
    }

    /** Seek the active media session to the given absolute position in ms. */
    fun seekTo(positionMs: Long) {
        if (!hasNotificationAccess) return
        try {
            val session = pickAppSession() ?: return
            session.transportControls.seekTo(positionMs)
        } catch (t: Throwable) {
            Log.w(TAG, "seekTo failed", t)
        }
    }

    private fun sendMediaKey(keyCode: Int) {
        logSessions("key ${keyName(keyCode)}")
        if (sendViaMediaSession(keyCode)) {
            Log.i(TAG, "DIAG-MEDIA key ${keyName(keyCode)} -> session path returned OK")
            return
        }
        if (sendViaBroadcast(keyCode)) {
            Log.i(TAG, "DIAG-MEDIA key ${keyName(keyCode)} -> broadcast path returned OK")
            return
        }
        Log.w(TAG, "DIAG-MEDIA key ${keyName(keyCode)} -> NO path worked")
    }

    // ─────────────────────────── DIAG-MEDIA instrumentation ───────────────────────
    // Logcat-only. Dumps the active-session world on each user-initiated media key
    // / seek so we can see whether the target app (e.g. Paramount+) publishes a
    // MediaSession at all, its playback state, which transport actions it
    // advertises, and which session we picked. "session path returned OK" means
    // the dispatch call did not throw — NOT that the app acted on it: an app that
    // ignores media-button events (as Netflix does for FF/REW) still returns OK.
    private fun logSessions(reason: String) {
        if (!hasNotificationAccess) {
            Log.i(TAG, "DIAG-MEDIA [$reason] no notification access")
            return
        }
        val sessions = runCatching {
            mediaSessionManager.getActiveSessions(listenerComponent)
        }.getOrNull()
        if (sessions == null) {
            Log.i(TAG, "DIAG-MEDIA [$reason] getActiveSessions returned null")
            return
        }
        val own = context.packageName
        val picked = pickAppSession()?.packageName
        Log.i(TAG, "DIAG-MEDIA [$reason] ${sessions.size} session(s), picked=$picked")
        sessions.forEach { s ->
            val st = s.playbackState
            val ownTag = if (s.packageName == own) " (OWN-decoy)" else ""
            Log.i(
                TAG,
                "DIAG-MEDIA   pkg=${s.packageName}$ownTag state=${stateName(st?.state)}" +
                    " pos=${st?.position} actions=[${actionNames(st?.actions ?: 0L)}]",
            )
        }
    }

    private fun keyName(keyCode: Int): String = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "PLAY_PAUSE"
        KeyEvent.KEYCODE_MEDIA_NEXT -> "NEXT"
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "PREV"
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> "FF"
        KeyEvent.KEYCODE_MEDIA_REWIND -> "REW"
        KeyEvent.KEYCODE_MEDIA_STOP -> "STOP"
        else -> "key$keyCode"
    }

    private fun stateName(state: Int?): String = when (state) {
        null -> "null"
        android.media.session.PlaybackState.STATE_NONE -> "NONE"
        android.media.session.PlaybackState.STATE_STOPPED -> "STOPPED"
        android.media.session.PlaybackState.STATE_PAUSED -> "PAUSED"
        android.media.session.PlaybackState.STATE_PLAYING -> "PLAYING"
        android.media.session.PlaybackState.STATE_BUFFERING -> "BUFFERING"
        android.media.session.PlaybackState.STATE_ERROR -> "ERROR"
        else -> "state$state"
    }

    private fun actionNames(actions: Long): String {
        val names = mutableListOf<String>()
        fun has(flag: Long) = actions and flag != 0L
        if (has(android.media.session.PlaybackState.ACTION_PLAY_PAUSE)) names += "PLAY_PAUSE"
        if (has(android.media.session.PlaybackState.ACTION_PLAY)) names += "PLAY"
        if (has(android.media.session.PlaybackState.ACTION_PAUSE)) names += "PAUSE"
        if (has(android.media.session.PlaybackState.ACTION_SKIP_TO_NEXT)) names += "NEXT"
        if (has(android.media.session.PlaybackState.ACTION_SKIP_TO_PREVIOUS)) names += "PREV"
        if (has(android.media.session.PlaybackState.ACTION_FAST_FORWARD)) names += "FF"
        if (has(android.media.session.PlaybackState.ACTION_REWIND)) names += "REW"
        if (has(android.media.session.PlaybackState.ACTION_SEEK_TO)) names += "SEEK_TO"
        if (has(android.media.session.PlaybackState.ACTION_STOP)) names += "STOP"
        return names.joinToString(",")
    }

    /**
     * The REAL app's media session to act on — never PortalPad's own
     * `PortalPadVolume` decoy. That decoy is created active + PLAYING (so
     * hardware volume keys route to it), and Android frequently pushes it to the
     * TOP of the session stack — so a naive getActiveSessions().firstOrNull()
     * would dispatch transport keys / seeks / position reads to IT, where they
     * are silently swallowed. That was the "media controls dead / slider blank
     * until I tap OK on the app" bug: tapping the app just happened to push its
     * real session back above ours. Skip our own package, then prefer the
     * session that's actually PLAYING/BUFFERING (what the user is watching), then
     * a PAUSED one, then any remaining external session — preserving the system's
     * priority order within each tier.
     */
    private fun pickAppSession(): android.media.session.MediaController? {
        if (!hasNotificationAccess) return null
        val sessions = runCatching {
            mediaSessionManager.getActiveSessions(listenerComponent)
        }.getOrNull() ?: return null
        val own = context.packageName
        val external = sessions.filter { it.packageName != own }
        return external.firstOrNull {
            val s = it.playbackState?.state
            s == android.media.session.PlaybackState.STATE_PLAYING ||
                s == android.media.session.PlaybackState.STATE_BUFFERING
        } ?: external.firstOrNull {
            it.playbackState?.state == android.media.session.PlaybackState.STATE_PAUSED
        } ?: external.firstOrNull()
    }

    /**
     * Skip by [deltaMs] via an absolute seek when the app's session supports
     * seeking (advertises ACTION_SEEK_TO and reports a position) — this is what
     * actually works on apps like Netflix, which ignore the FAST_FORWARD/REWIND
     * media keys. Falls back to the media key otherwise, since some players only
     * respond to those.
     */
    private fun seekRelativeOrKey(deltaMs: Long, fallbackKey: Int) {
        logSessions("seek ${deltaMs}ms")
        val session = pickAppSession()
        val state = session?.playbackState
        val canSeek = state != null && state.position >= 0 &&
            (state.actions and android.media.session.PlaybackState.ACTION_SEEK_TO) != 0L
        if (session != null && canSeek) {
            val dur = session.metadata?.getLong(
                android.media.MediaMetadata.METADATA_KEY_DURATION,
            ) ?: -1L
            var target = state!!.position + deltaMs
            if (target < 0L) target = 0L
            if (dur > 0L && target > dur) target = dur
            Log.i(TAG, "DIAG-MEDIA seek ${deltaMs}ms -> ${session.packageName} seekTo=$target")
            runCatching { session.transportControls.seekTo(target) }
            return
        }
        Log.i(TAG, "DIAG-MEDIA seek ${deltaMs}ms -> canSeek=$canSeek, falling back to ${keyName(fallbackKey)} key")
        sendMediaKey(fallbackKey)
    }

    private fun findBestSessionForPackage(pkg: String): android.media.session.MediaController? {
        if (!hasNotificationAccess) return null
        val sessions = runCatching {
            mediaSessionManager.getActiveSessions(listenerComponent)
        }.getOrNull() ?: return null
        val matches = sessions.filter { it.packageName == pkg }
        return matches.firstOrNull {
            val s = it.playbackState?.state
            s == android.media.session.PlaybackState.STATE_PLAYING ||
                s == android.media.session.PlaybackState.STATE_BUFFERING
        } ?: matches.firstOrNull {
            it.playbackState?.state == android.media.session.PlaybackState.STATE_PAUSED &&
                ((it.playbackState?.actions ?: 0L) and (
                    android.media.session.PlaybackState.ACTION_PLAY or
                        android.media.session.PlaybackState.ACTION_PLAY_PAUSE
                    )) != 0L
        } ?: matches.firstOrNull {
            it.playbackState?.state == android.media.session.PlaybackState.STATE_PAUSED
        } ?: matches.firstOrNull()
    }

    private fun sendViaMediaSession(keyCode: Int): Boolean {
        if (!hasNotificationAccess) return false
        return try {
            val active = pickAppSession() ?: return false
            Log.i(TAG, "DIAG-MEDIA dispatch ${keyName(keyCode)} -> ${active.packageName}")
            val downTime = SystemClock.uptimeMillis()
            active.dispatchMediaButtonEvent(
                KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, keyCode, 0),
            )
            active.dispatchMediaButtonEvent(
                KeyEvent(downTime, downTime + 20L, KeyEvent.ACTION_UP, keyCode, 0),
            )
            true
        } catch (t: Throwable) {
            Log.w(TAG, "MediaSession dispatch failed", t)
            false
        }
    }

    private fun sendViaPackageBroadcast(pkg: String, keyCode: Int): Boolean {
        return try {
            val downTime = SystemClock.uptimeMillis()
            val down = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                setPackage(pkg)
                putExtra(
                    Intent.EXTRA_KEY_EVENT,
                    KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, keyCode, 0),
                )
            }
            val up = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                setPackage(pkg)
                putExtra(
                    Intent.EXTRA_KEY_EVENT,
                    KeyEvent(downTime, downTime + 20L, KeyEvent.ACTION_UP, keyCode, 0),
                )
            }
            context.sendOrderedBroadcast(down, null)
            context.sendOrderedBroadcast(up, null)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Package-scoped media broadcast failed pkg=$pkg", t)
            false
        }
    }

    private fun sendViaBroadcast(keyCode: Int): Boolean {
        return try {
            val downTime = SystemClock.uptimeMillis()
            val down = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, keyCode, 0))
            }
            val up = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(downTime, downTime + 20L, KeyEvent.ACTION_UP, keyCode, 0))
            }
            context.sendOrderedBroadcast(down, null)
            context.sendOrderedBroadcast(up, null)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Media broadcast failed", t)
            false
        }
    }

    private inner class RealSessionHandle(
        private val controller: android.media.session.MediaController,
    ) : SessionHandle {
        override val snapshot: ExternalSessionSnapshot
            get() {
                val st = controller.playbackState
                return ExternalSessionSnapshot(
                    packageName = controller.packageName,
                    state = st?.state,
                    actions = st?.actions ?: 0L,
                    hasPosition = (st?.position ?: -1L) >= 0L,
                )
            }

        override fun play() {
            runCatching { controller.transportControls.play() }
                .onFailure { Log.w(TAG, "transportControls.play failed for ${controller.packageName}", it) }
        }

        override fun pause() {
            runCatching { controller.transportControls.pause() }
                .onFailure { Log.w(TAG, "transportControls.pause failed for ${controller.packageName}", it) }
        }

        override fun dispatchMediaKey(keyCode: Int) {
            runCatching {
                val downTime = SystemClock.uptimeMillis()
                controller.dispatchMediaButtonEvent(
                    KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, keyCode, 0),
                )
                controller.dispatchMediaButtonEvent(
                    KeyEvent(downTime, downTime + 20L, KeyEvent.ACTION_UP, keyCode, 0),
                )
            }.onFailure {
                Log.w(TAG, "dispatchMediaButtonEvent($keyCode) failed for ${controller.packageName}", it)
            }
        }
    }

    internal data class ExternalSessionSnapshot(
        val packageName: String,
        val state: Int?,
        val actions: Long,
        val hasPosition: Boolean = false,
    )

    internal interface SessionHandle {
        val snapshot: ExternalSessionSnapshot
        fun play()
        fun pause()
        fun dispatchMediaKey(keyCode: Int)
    }

    companion object {
        private const val TAG = "MediaController"
        /** Relative skip distance for the Fast-forward / Rewind buttons. */
        private const val SKIP_MS = 10_000L
        private const val VERIFY_DELAY_MS = 200L

        private fun isActivePlaybackState(state: Int?): Boolean =
            state == android.media.session.PlaybackState.STATE_PLAYING ||
                state == android.media.session.PlaybackState.STATE_BUFFERING

        /**
         * Selects the best [SessionHandle] strictly from [visibleExternalPackages]
         * (ordered top-most/focused external display window first). Returns null if
         * no app is displayed on the external display or none of the displayed apps
         * own an active MediaSession — background phone apps are never selected.
         */
        internal fun selectSessionForExternalPackages(
            sessions: List<SessionHandle>,
            visibleExternalPackages: List<String>,
            ownPackageName: String,
        ): SessionHandle? {
            if (visibleExternalPackages.isEmpty() || sessions.isEmpty()) return null
            val visibleSet = visibleExternalPackages.toSet()
            val candidateSessions = sessions.filter { s ->
                val pkg = s.snapshot.packageName
                pkg != ownPackageName && !pkg.startsWith("com.portalpad") && pkg in visibleSet
            }
            if (candidateSessions.isEmpty()) return null

            for (pkg in visibleExternalPackages) {
                val pkgSessions = candidateSessions.filter { it.snapshot.packageName == pkg }
                if (pkgSessions.isEmpty()) continue
                val picked = pkgSessions.firstOrNull { isActivePlaybackState(it.snapshot.state) }
                    ?: pkgSessions.firstOrNull {
                        it.snapshot.state == android.media.session.PlaybackState.STATE_PAUSED &&
                            (it.snapshot.actions and (
                                android.media.session.PlaybackState.ACTION_PLAY or
                                    android.media.session.PlaybackState.ACTION_PLAY_PAUSE
                                )) != 0L
                    }
                    ?: pkgSessions.firstOrNull {
                        it.snapshot.state == android.media.session.PlaybackState.STATE_PAUSED
                    }
                    ?: pkgSessions.firstOrNull { it.snapshot.actions != 0L }
                    ?: pkgSessions.first()
                return picked
            }
            return null
        }

        /**
         * Dispatches Pause or Resume Play reliably against [initial], verifying whether
         * the player transitioned state and falling back cleanly (using non-toggle
         * transport / PLAY / PAUSE calls before any package-scoped broadcast or toggle)
         * so apps that ignore `KEYCODE_MEDIA_PLAY_PAUSE` when paused still resume.
         */
        internal fun dispatchPlayPauseSmart(
            initial: SessionHandle,
            querySessionForPkg: (String) -> SessionHandle?,
            sendPackageBroadcast: (String, Int) -> Unit,
            sleepMs: (Long) -> Unit,
            shouldAbort: () -> Boolean = { false },
        ) {
            val snap = initial.snapshot
            val pkg = snap.packageName
            val actions = snap.actions
            val wasPlaying = isActivePlaybackState(snap.state)

            if (wasPlaying) {
                val usedTogglePrimary =
                    (actions and android.media.session.PlaybackState.ACTION_PAUSE) == 0L &&
                        (actions and android.media.session.PlaybackState.ACTION_PLAY_PAUSE) != 0L
                if (usedTogglePrimary) {
                    initial.dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                } else {
                    initial.pause()
                }
                sleepMs(VERIFY_DELAY_MS)
                if (shouldAbort()) return

                val after1 = querySessionForPkg(pkg)
                if (after1 == null || !isActivePlaybackState(after1.snapshot.state)) return

                // Still playing — send explicit non-toggle PAUSE key event.
                after1.dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
                sleepMs(VERIFY_DELAY_MS)
                if (shouldAbort()) return

                val after2 = querySessionForPkg(pkg)
                if (after2 == null || !isActivePlaybackState(after2.snapshot.state)) return

                sendPackageBroadcast(pkg, KeyEvent.KEYCODE_MEDIA_PAUSE)
                return
            }

            // Resuming from PAUSED / STOPPED / NONE:
            // Many Android players handle Callback.onPlay() (transportControls.play()) or
            // explicit KEYCODE_MEDIA_PLAY cleanly when paused even when they drop
            // raw KEYCODE_MEDIA_PLAY_PAUSE button events.
            val supportsPlay = (actions and android.media.session.PlaybackState.ACTION_PLAY) != 0L
            val supportsPause = (actions and android.media.session.PlaybackState.ACTION_PAUSE) != 0L
            val supportsToggle = (actions and android.media.session.PlaybackState.ACTION_PLAY_PAUSE) != 0L
            val sentTogglePrimary = !supportsPlay && !supportsPause && actions != 0L && supportsToggle

            if (sentTogglePrimary) {
                initial.dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            } else {
                initial.play()
            }
            sleepMs(VERIFY_DELAY_MS)
            if (shouldAbort()) return

            val after1 = querySessionForPkg(pkg)
            if (after1 != null && isActivePlaybackState(after1.snapshot.state)) return

            if (sentTogglePrimary) {
                (after1 ?: initial).play()
            } else {
                (after1 ?: initial).dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
            }
            sleepMs(VERIFY_DELAY_MS)
            if (shouldAbort()) return

            val after2 = querySessionForPkg(pkg)
            if (after2 != null && isActivePlaybackState(after2.snapshot.state)) return

            sendPackageBroadcast(pkg, KeyEvent.KEYCODE_MEDIA_PLAY)
            if (!sentTogglePrimary && supportsToggle) {
                (after2 ?: initial).dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            }
        }
    }
}
