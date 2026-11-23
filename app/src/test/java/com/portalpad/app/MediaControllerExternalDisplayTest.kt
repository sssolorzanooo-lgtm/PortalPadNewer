package com.portalpad.app

import android.app.Application
import android.media.session.PlaybackState
import android.view.KeyEvent
import com.portalpad.app.service.MediaController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MediaControllerExternalDisplayTest {

    private class FakeSession(
        packageName: String,
        initialState: Int?,
        actions: Long,
        hasPosition: Boolean = false,
        private val onPlayAction: (FakeSession) -> Unit = {},
        private val onPauseAction: (FakeSession) -> Unit = {},
        private val onKeyAction: (FakeSession, Int) -> Unit = { _, _ -> },
    ) : MediaController.SessionHandle {
        var currentState: Int? = initialState
        var currentActions: Long = actions
        var currentHasPosition: Boolean = hasPosition
        private val pkg = packageName
        val calls = mutableListOf<String>()

        override val snapshot: MediaController.ExternalSessionSnapshot
            get() = MediaController.ExternalSessionSnapshot(
                packageName = pkg,
                state = currentState,
                actions = currentActions,
                hasPosition = currentHasPosition,
            )

        override fun play() {
            calls += "play"
            onPlayAction(this)
        }

        override fun pause() {
            calls += "pause"
            onPauseAction(this)
        }

        override fun dispatchMediaKey(keyCode: Int) {
            calls += "key:$keyCode"
            onKeyAction(this, keyCode)
        }
    }

    @Test
    fun selectSessionForExternalPackages_ignoresBackgroundPhoneApps() {
        val backgroundPlaying = FakeSession(
            packageName = "com.spotify.music",
            initialState = PlaybackState.STATE_PLAYING,
            actions = PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE,
        )
        val externalPaused = FakeSession(
            packageName = "com.netflix.mediaclient",
            initialState = PlaybackState.STATE_PAUSED,
            actions = PlaybackState.ACTION_PLAY,
        )
        val ownDecoy = FakeSession(
            packageName = "com.aistudio.portalpad.kxmpzq",
            initialState = PlaybackState.STATE_PLAYING,
            actions = 0L,
        )

        // When Netflix is displayed on external display (even though Spotify is actively playing
        // in the background on the phone), Netflix MUST be chosen.
        val picked = MediaController.selectSessionForExternalPackages(
            sessions = listOf(ownDecoy, backgroundPlaying, externalPaused),
            visibleExternalPackages = listOf("com.netflix.mediaclient"),
            ownPackageName = "com.aistudio.portalpad.kxmpzq",
        )
        assertEquals("com.netflix.mediaclient", picked?.snapshot?.packageName)

        // When no app is displayed on the external display, nothing is picked (never touch Spotify).
        val none = MediaController.selectSessionForExternalPackages(
            sessions = listOf(ownDecoy, backgroundPlaying, externalPaused),
            visibleExternalPackages = emptyList(),
            ownPackageName = "com.aistudio.portalpad.kxmpzq",
        )
        assertNull(none)

        // When external display shows Chrome (no MediaSession), background Spotify is still ignored.
        val chromeOnly = MediaController.selectSessionForExternalPackages(
            sessions = listOf(ownDecoy, backgroundPlaying),
            visibleExternalPackages = listOf("com.android.chrome"),
            ownPackageName = "com.aistudio.portalpad.kxmpzq",
        )
        assertNull(chromeOnly)
    }

    @Test
    fun selectSessionForExternalPackages_picksBestSessionAmongSamePackageAndWindowOrder() {
        val staleChrome = FakeSession(
            packageName = "com.android.chrome",
            initialState = PlaybackState.STATE_NONE,
            actions = 0L,
        )
        val pausedChromeVideo = FakeSession(
            packageName = "com.android.chrome",
            initialState = PlaybackState.STATE_PAUSED,
            actions = PlaybackState.ACTION_PLAY,
            hasPosition = true,
        )
        val sideVlc = FakeSession(
            packageName = "org.videolan.vlc",
            initialState = PlaybackState.STATE_PLAYING,
            actions = PlaybackState.ACTION_PAUSE,
        )

        // Topmost external window (Chrome) has an actionable paused session -> wins over side window.
        val topPicked = MediaController.selectSessionForExternalPackages(
            sessions = listOf(staleChrome, sideVlc, pausedChromeVideo),
            visibleExternalPackages = listOf("com.android.chrome", "org.videolan.vlc"),
            ownPackageName = "com.aistudio.portalpad.kxmpzq",
        )
        assertEquals(pausedChromeVideo, topPicked)
    }

    @Test
    fun dispatchPlayPauseSmart_resumesPausedAppViaTransportControlsWithoutToggleBounce() {
        val pausedApp = FakeSession(
            packageName = "com.android.chrome",
            initialState = PlaybackState.STATE_PAUSED,
            actions = PlaybackState.ACTION_PLAY,
            onPlayAction = { it.currentState = PlaybackState.STATE_PLAYING },
        )
        val broadcasts = mutableListOf<Pair<String, Int>>()

        MediaController.dispatchPlayPauseSmart(
            initial = pausedApp,
            querySessionForPkg = { pausedApp },
            sendPackageBroadcast = { pkg, key -> broadcasts += pkg to key },
            sleepMs = {},
        )

        assertEquals(listOf("play"), pausedApp.calls)
        assertEquals(PlaybackState.STATE_PLAYING, pausedApp.currentState)
        assertTrue(broadcasts.isEmpty())
    }

    @Test
    fun dispatchPlayPauseSmart_fallsBackToMediaPlayKeyAndScopedBroadcastIfPlayIgnored() {
        // App advertises ACTION_PLAY & ACTION_PLAY_PAUSE, ignores transportControls.play() and
        // KEYCODE_MEDIA_PLAY, and only wakes on package-scoped broadcast or PLAY_PAUSE fallback.
        val stubbornApp = FakeSession(
            packageName = "org.videolan.vlc",
            initialState = PlaybackState.STATE_PAUSED,
            actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PLAY_PAUSE,
        )
        val broadcasts = mutableListOf<Pair<String, Int>>()

        MediaController.dispatchPlayPauseSmart(
            initial = stubbornApp,
            querySessionForPkg = { stubbornApp },
            sendPackageBroadcast = { pkg, key ->
                broadcasts += pkg to key
                stubbornApp.currentState = PlaybackState.STATE_PLAYING
            },
            sleepMs = {},
        )

        assertEquals(
            listOf(
                "play",
                "key:${KeyEvent.KEYCODE_MEDIA_PLAY}",
                "key:${KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE}",
            ),
            stubbornApp.calls,
        )
        assertEquals(listOf("org.videolan.vlc" to KeyEvent.KEYCODE_MEDIA_PLAY), broadcasts)
    }

    @Test
    fun dispatchPlayPauseSmart_pausesPlayingAppCleanly() {
        val playingApp = FakeSession(
            packageName = "com.google.android.youtube",
            initialState = PlaybackState.STATE_PLAYING,
            actions = PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE,
            onPauseAction = { it.currentState = PlaybackState.STATE_PAUSED },
        )
        val broadcasts = mutableListOf<Pair<String, Int>>()

        MediaController.dispatchPlayPauseSmart(
            initial = playingApp,
            querySessionForPkg = { playingApp },
            sendPackageBroadcast = { pkg, key -> broadcasts += pkg to key },
            sleepMs = {},
        )

        assertEquals(listOf("pause"), playingApp.calls)
        assertEquals(PlaybackState.STATE_PAUSED, playingApp.currentState)
        assertTrue(broadcasts.isEmpty())
    }
}
