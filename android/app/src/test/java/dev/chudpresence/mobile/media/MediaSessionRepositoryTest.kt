package dev.chudpresence.mobile.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaSessionRepositoryTest {
    @Test fun `Hulu with null or blank metadata retains a publishable title`() {
        assertEquals("Hulu", mediaSessionTitle(HULU_PACKAGE, null))
        assertEquals("Hulu", mediaSessionTitle(HULU_PACKAGE, "  ", "  "))
    }

    @Test fun `Hulu uses supplied titles before its app fallback`() {
        assertEquals("Episode title", mediaSessionTitle(HULU_PACKAGE, "  Episode title  ", "Display title"))
        assertEquals("Display title", mediaSessionTitle(HULU_PACKAGE, null, "  Display title  "))
    }

    @Test fun `unknown sessions remain discoverable with display title or app label fallback`() {
        assertEquals("example.player", mediaSessionTitle("example.player", null))
        assertEquals("Display title", mediaSessionTitle("example.player", "  ", "Display title"))
        assertEquals("Example Player", mediaSessionTitle("example.player", null, null, "Example Player"))
        assertEquals("Song", mediaSessionTitle("example.player", " Song "))
    }

    @Test fun `patched Hulu reads special-season and fractional episode metadata`() {
        val hulu = snapshot("hulu", "playing").copy(packageName = HULU_PACKAGE, metadataEnriched = true)
        val enhanced = hulu.withHuluEpisodeMetadata(0, " 3.5 ")
        assertEquals(0, enhanced.seasonNumber)
        assertEquals("3.5", enhanced.episodeNumber)
    }

    @Test fun `missing invalid or removed Hulu episode metadata never becomes a default zero`() {
        val hulu = snapshot("hulu", "playing").copy(packageName = HULU_PACKAGE, metadataEnriched = true,
            seasonNumber = 2, episodeNumber = "3")
        for (season in listOf(null, -1L, Int.MAX_VALUE.toLong() + 1)) {
            assertNull(hulu.withHuluEpisodeMetadata(season, null).seasonNumber)
        }
        for (episode in listOf(null, "", " ", "0", "-1", "NaN", "Episode 3", "1e2")) {
            assertNull(hulu.withHuluEpisodeMetadata(null, episode).episodeNumber)
        }
        val movie = hulu.withHuluEpisodeMetadata(null, null)
        assertNull(movie.seasonNumber)
        assertNull(movie.episodeNumber)
    }

    @Test fun `episode extension keys work for any supported patched player and ignore unpatched players`() {
        val unpatched = snapshot("hulu", "playing").copy(packageName = HULU_PACKAGE)
        val other = unpatched.copy(packageName = "example.player", metadataEnriched = true)
        assertEquals(unpatched, unpatched.withHuluEpisodeMetadata(2, "3"))
        assertEquals(2, other.withEpisodeMetadata(2, "3").seasonNumber)
        assertEquals("3", other.withEpisodeMetadata(2, "3").episodeNumber)
    }

    private fun snapshot(
        id: String,
        state: String,
        position: Long? = null,
        updatedAt: Long? = null,
        speed: Float = 0f,
        duration: Long? = null,
        playingSince: Long = 0
    ) = MediaSnapshot(id, "package.$id", id, id, null, null, state, position, duration, null,
        updatedAt, speed, playingSince)

    @Test fun `playing position advances from playback state update time`() {
        val media = snapshot("one", "playing", position = 10_000, updatedAt = 20_000, speed = 1f, duration = 60_000)
        assertEquals(15_000L, media.positionAt(25_000))
    }

    @Test fun `freshly captured position is not over-advanced`() {
        val media = snapshot("one", "playing", position = 10_000, updatedAt = 25_000, speed = 1f, duration = 60_000)
        assertEquals(10_000L, media.positionAt(25_000))
    }

    @Test fun `advanced position is capped at duration`() {
        val media = snapshot("one", "playing", position = 58_000, updatedAt = 20_000, speed = 1f, duration = 60_000)
        assertEquals(60_000L, media.positionAt(25_000))
    }

    @Test fun `paused position does not advance`() {
        val media = snapshot("one", "paused", position = 10_000, updatedAt = 20_000, speed = 1f)
        assertEquals(10_000L, media.positionAt(25_000))
        assertEquals(5_000L, media.copy(durationMs = 5_000).positionAt(25_000))
    }

    @Test fun `malformed timing cannot overflow into negative playback progress`() {
        val extreme = snapshot("one", "playing", position = Long.MAX_VALUE - 100, updatedAt = 1,
            speed = Float.MAX_VALUE)
        assertEquals(Long.MAX_VALUE, extreme.positionAt(Long.MAX_VALUE))
        assertEquals(Long.MAX_VALUE, extreme.copy(positionMs = 100, positionUpdatedElapsedMs = Long.MIN_VALUE)
            .positionAt(Long.MAX_VALUE))
        assertEquals(100L, extreme.copy(positionMs = 100, playbackSpeed = Float.POSITIVE_INFINITY)
            .positionAt(Long.MAX_VALUE))
        assertNull(extreme.copy(positionMs = -1).positionAt(100))
    }

    @Test fun `new playing session replaces current paused session`() {
        val paused = snapshot("old", "paused")
        val playing = snapshot("new", "playing", playingSince = 2)
        assertEquals(playing, SessionSelector.select(listOf(paused, playing), paused.sessionId))
    }

    @Test fun `current playing session remains stable`() {
        val current = snapshot("current", "playing", playingSince = 1)
        val other = snapshot("other", "playing", playingSince = 2)
        assertEquals(current, SessionSelector.select(listOf(current, other), current.sessionId))
    }

    @Test fun `early next-track metadata is held while old position continues`() {
        val current = snapshot("one", "playing", position = 220_000, updatedAt = 1_000, speed = 1f)
        val held = shouldHoldPreviousTrack(current, "Next song", "Next artist", "playing", 231_000, 11_000)
        assertEquals(true, held)
    }

    @Test fun `next track is accepted after position resets`() {
        val current = snapshot("one", "playing", position = 220_000, updatedAt = 1_000, speed = 1f)
        val held = shouldHoldPreviousTrack(current, "Next song", "Next artist", "playing", 1_000, 11_000)
        assertEquals(false, held)
    }

    @Test fun `first observation does not require a transition`() {
        val current = snapshot("one", "paused", position = 220_000, updatedAt = 1_000, speed = 0f)
        val held = shouldHoldPreviousTrack(current, "Next song", "Next artist", "playing", 1_000, 11_000)
        assertEquals(false, held)
    }

    @Test fun `only legacy Music can retain early next-track text`() {
        val current = snapshot("one", "playing", position = 220_000, updatedAt = 1_000, speed = 1f)
            .copy(packageName = "com.google.android.apps.youtube.music")
        val next = current.copy(title = "Next song", artist = "Next artist", positionMs = 231_000)
        assertEquals(true, shouldRetainPreviousMetadata(current, next, 11_000))
        assertEquals(false, shouldRetainPreviousMetadata(current, next.copy(packageName = "example.video"), 11_000))
        assertEquals(false, shouldRetainPreviousMetadata(current, next.copy(metadataEnriched = true), 11_000))
        assertEquals(false, shouldRetainPreviousMetadata(current.copy(metadataEnriched = true), next, 11_000))
        assertEquals(false, shouldRetainPreviousMetadata(current, next.copy(mediaId = "new-item"), 11_000))
        assertEquals(false, shouldRetainPreviousMetadata(current, next.copy(isFallbackTitle = true), 11_000))
    }

    @Test fun `public artwork selects the first valid HTTPS URL`() {
        assertEquals(
            "https://lh3.googleusercontent.com/cover=w544-h544",
            publicArtworkUrl(
                "content://media/external/audio/1",
                "https://lh3.googleusercontent.com/cover=w544-h544",
                "https://example.com/fallback.webp"
            )
        )
    }

    @Test fun `local and insecure artwork locations are rejected`() {
        assertNull(publicArtworkUrl("content://media/cover", "file:///tmp/cover.webp", "http://example.com/cover.webp"))
    }
}
