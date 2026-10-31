package dev.chudpresence.mobile.reporting

import dev.chudpresence.mobile.data.PresenceDisplaySettings
import dev.chudpresence.mobile.data.ServiceActivityOptions
import dev.chudpresence.mobile.media.MediaSnapshot
import dev.chudpresence.mobile.services.ServiceDefinition
import dev.chudpresence.mobile.services.ServiceDefinitions
import org.junit.Assert.*
import org.junit.Test

class PresenceOptionsTest {
    private val youtube = ServiceDefinitions.builtIns.single { it.id == "youtube" }
    private val music = ServiceDefinitions.builtIns.single { it.id == "youtube_music" }
    private fun video() = MediaSnapshot("video", "com.google.android.youtube", "YouTube", "Video", "Creator", null,
        "playing", 20_000, 60_000, null, artworkUrl = "https://example.com/cover.jpg", mediaId = "dQw4w9WgXcQ",
        artistUrl = "https://www.youtube.com/@creator")
    private fun settings(options: ServiceActivityOptions) = PresenceDisplaySettings(activityOptions = mapOf(youtube.id to options))

    @Test fun `defaults retain playing and paused sessions`() {
        val playing = video()
        val paused = playing.copy(playbackState = "paused")
        assertEquals(playing, selectPresenceSession(listOf(playing), null, PresenceDisplaySettings()))
        assertEquals(paused, selectPresenceSession(listOf(paused), null, PresenceDisplaySettings()))
    }

    @Test fun `disabled current service yields to another app and clears when none are eligible`() {
        val current = video()
        val other = current.copy(sessionId = "music", packageName = music.packages.first())
        val settings = settings(ServiceActivityOptions(enabled = false))
        assertEquals(other, selectPresenceSession(listOf(current, other), current.sessionId, settings))
        assertNull(selectPresenceSession(listOf(current), current.sessionId, settings))
        assertNull(selectPresenceSession(listOf(current.copy(playbackState = "paused")), current.sessionId, settings))
        assertEquals(current, selectPresenceSession(listOf(current), null, settings(ServiceActivityOptions())))
    }

    @Test fun `hiding paused activity allows another paused app and resumes automatically`() {
        val paused = video().copy(playbackState = "paused")
        val other = paused.copy(sessionId = "music", packageName = music.packages.first())
        val settings = settings(ServiceActivityOptions(showWhilePaused = false))
        assertNull(selectPresenceSession(listOf(paused), paused.sessionId, settings))
        assertEquals(other, selectPresenceSession(listOf(paused, other), paused.sessionId, settings))
        val resumed = paused.copy(playbackState = "playing")
        assertEquals(resumed, selectPresenceSession(listOf(resumed, other), null, settings))
    }

    @Test fun `artwork off ignores covers video ids and resolver results and uses the app icon`() {
        val definition = youtube.copy(fallbackArtworkUrl = "https://example.com/app-icon.png")
        val options = ServiceActivityOptions(showArtwork = false)
        assertEquals(definition.fallbackArtworkUrl, presenceArtworkUrl(video(), definition, options, "https://example.com/resolved.jpg"))
        assertNull(presenceArtworkUrl(video(), youtube, options))
        assertEquals(video().artworkUrl, presenceArtworkUrl(video(), definition, ServiceActivityOptions()))
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
            presenceArtworkUrl(video().copy(artworkUrl = null), definition, ServiceActivityOptions()))
        assertEquals("https://example.com/resolved.jpg", presenceArtworkUrl(video().copy(artworkUrl = null, mediaId = null),
            definition, ServiceActivityOptions(), "https://example.com/resolved.jpg"))
    }

    @Test fun `hiding buttons removes both actions while keeping clickable text`() {
        val normal = compactPresence(video(), PresenceDisplaySettings(), youtube).presentation
        assertNotNull(normal.button1Url)
        assertNotNull(normal.button2Url)
        val hidden = compactPresence(video(), settings(ServiceActivityOptions(showActionButtons = false)), youtube).presentation
        assertNull(hidden.button1Label)
        assertNull(hidden.button1Url)
        assertNull(hidden.button2Label)
        assertNull(hidden.button2Url)
        assertEquals(normal.detailsUrl, hidden.detailsUrl)
        assertEquals(normal.stateUrl, hidden.stateUrl)
        assertEquals(normal.state, hidden.state)
    }

    @Test fun `app icon does not retain the cover destination`() {
        val snapshot = video().copy(packageName = music.packages.first(), artistUrl = "https://music.youtube.com/channel/artist",
            albumUrl = "https://music.youtube.com/browse/album")
        val settings = PresenceDisplaySettings(activityOptions = mapOf(music.id to ServiceActivityOptions(showArtwork = false)))
        val presentation = compactPresence(snapshot, settings, music).presentation
        assertNull(presentation.artworkClickUrl)
        assertNotNull(presentation.button1Url)
    }

    @Test fun `progress off uses the app elapsed counter for playing paused and live media`() {
        val options = ServiceActivityOptions(showPlaybackProgress = false)
        listOf(video(), video().copy(playbackState = "paused"), video().copy(durationMs = null)).forEach {
            assertEquals(PresenceTimestamps(70_000, -1), presenceTimestamps(it, options, 100_000, 50_000, 90_000, 70_000))
        }
        assertEquals(PresenceTimestamps(80_000, 140_000),
            presenceTimestamps(video(), ServiceActivityOptions(), 100_000, 50_000, 90_000, 70_000))
        assertEquals(PresenceTimestamps(90_000, -1),
            presenceTimestamps(video().copy(playbackState = "paused"), ServiceActivityOptions(), 100_000, 50_000, 90_000, 70_000))
    }

    @Test fun `action controls are available only to services that can publish validated links`() {
        assertTrue(ServiceDefinitions.builtIns.all { it.supportsActionButtons })
        assertFalse(ServiceDefinition("example.app", "Example", setOf("example.app")).supportsActionButtons)
        assertTrue(ServiceDefinition("example", "Example", setOf("example.app"), allowedHosts = setOf("example.com")).supportsActionButtons)
    }
}
