package dev.chudpresence.mobile.reporting

import dev.chudpresence.mobile.data.ServiceActivityOptions
import dev.chudpresence.mobile.media.MediaSnapshot
import org.junit.Assert.*
import org.junit.Test

class AppSessionTimerTest {
    private val music = MediaSnapshot("music", "app.revanced.android.apps.youtube.music", "YouTube Music", "First song",
        "Artist", "Album", "playing", 20_000, 60_000, null)
    private val elapsed = ServiceActivityOptions(showPlaybackProgress = false)

    @Test fun `track changes metadata edits pauses and repeat publications keep one app timestamp`() {
        val timer = AppSessionTimer()
        val updates = listOf(
            music,
            music.copy(artworkUrl = "https://example.com/cover.jpg"),
            music.copy(title = "Next song", mediaId = "next", positionMs = 0, durationMs = 120_000),
            music.copy(sessionId = "new-controller", title = "Third song", playbackState = "paused")
        )
        updates.forEachIndexed { index, snapshot ->
            val now = 100_000L + index * 10_000L
            val options = elapsed.copy(showArtwork = index % 2 == 0, showActionButtons = index % 2 != 0)
            assertEquals(PresenceTimestamps(100_000, -1), presenceTimestamps(snapshot, options,
                now, now, now, timer.startFor(snapshot.packageName, now)))
        }
    }

    @Test fun `changing progress mode keeps the same app baseline and restores each items progress`() {
        val timer = AppSessionTimer()
        val firstStart = timer.startFor(music.packageName, 100_000)
        assertEquals(PresenceTimestamps(80_000, 140_000),
            presenceTimestamps(music, ServiceActivityOptions(), 100_000, 100_000, 100_000, firstStart))
        assertEquals(PresenceTimestamps(100_000, -1), presenceTimestamps(music, elapsed, 130_000, 130_000,
            130_000, timer.startFor(music.packageName, 130_000)))
        val next = music.copy(title = "Next song", positionMs = 5_000, durationMs = 180_000)
        assertEquals(PresenceTimestamps(135_000, 315_000), presenceTimestamps(next, ServiceActivityOptions(),
            140_000, 140_000, 140_000, timer.startFor(next.packageName, 140_000)))
        assertEquals(PresenceTimestamps(100_000, -1), presenceTimestamps(next, elapsed, 150_000, 150_000,
            150_000, timer.startFor(next.packageName, 150_000)))
    }

    @Test fun `switching apps and returning starts a new counter rather than reusing an old total`() {
        val timer = AppSessionTimer()
        assertEquals(100_000L, timer.startFor(music.packageName, 100_000))
        assertEquals(120_000L, timer.startFor("app.revanced.android.youtube", 120_000))
        assertEquals(140_000L, timer.startFor(music.packageName, 140_000))
    }

    @Test fun `hidden pauses and item gaps retain the counter while the media app remains active`() {
        val timer = AppSessionTimer()
        timer.startFor(music.packageName, 100_000)
        timer.retainWhileActive(setOf(music.packageName, "other.player"))
        assertEquals(100_000L, timer.startFor(music.packageName, 150_000))
        timer.retainWhileActive(setOf("other.player"))
        assertNull(timer.startEpochMs)
        assertEquals(170_000L, timer.startFor(music.packageName, 170_000))
    }

    @Test fun `stopping reporting or losing all media starts a fresh counter next time`() {
        val timer = AppSessionTimer()
        timer.startFor(music.packageName, 100_000)
        timer.reset()
        assertNull(timer.startEpochMs)
        assertEquals(120_000L, timer.startFor(music.packageName, 120_000))
        timer.retainWhileActive(emptySet())
        assertNull(timer.startEpochMs)
        assertEquals(150_000L, timer.startFor(music.packageName, 150_000))
    }
}
