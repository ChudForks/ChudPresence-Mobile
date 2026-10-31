package dev.chudpresence.mobile.reporting

import dev.chudpresence.mobile.data.PresenceContent
import dev.chudpresence.mobile.data.PresenceDisplaySettings
import dev.chudpresence.mobile.data.PresenceService
import dev.chudpresence.mobile.media.MediaSnapshot
import org.junit.Assert.*
import org.junit.Test

class CompactPresenceTest {
    private fun snapshot(service: PresenceService) = MediaSnapshot("session", when (service) {
        PresenceService.YOUTUBE -> "com.google.android.youtube"
        PresenceService.YOUTUBE_MUSIC -> "com.google.android.apps.youtube.music"
        PresenceService.TWITCH -> "tv.twitch.android.app"
        PresenceService.CRUNCHYROLL -> "com.crunchyroll.crunchyroid"
        PresenceService.HULU -> "com.hulu.plus"
    }, service.label, "Media title", "Artist / creator / series", null, "playing", 1_000, 100_000, null)

    @Test fun `services default to artist creator or series with Hulu using app name`() {
        PresenceService.entries.forEach { service ->
            val result = compactPresence(snapshot(service), PresenceDisplaySettings())
            assertEquals(service.label, if (service == PresenceService.HULU) 0 else 2, result.statusDisplay)
            assertEquals("Artist / creator / series", result.presentation.state)
        }
    }

    @Test fun `switching services selects independent choices and keeps links and expanded title`() {
        val settings = PresenceDisplaySettings(mapOf(PresenceService.YOUTUBE to PresenceContent.APP_NAME,
            PresenceService.TWITCH to PresenceContent.TRACK))
        assertEquals(0, compactPresence(snapshot(PresenceService.YOUTUBE), settings).statusDisplay)
        assertEquals(2, compactPresence(snapshot(PresenceService.YOUTUBE_MUSIC), settings).statusDisplay)
        assertEquals(1, compactPresence(snapshot(PresenceService.TWITCH), settings).statusDisplay)
        assertEquals(2, compactPresence(snapshot(PresenceService.CRUNCHYROLL), settings).statusDisplay)
        PresenceService.entries.forEach {
            assertEquals(presencePresentation(snapshot(it)), compactPresence(snapshot(it), settings).presentation)
        }
    }

    @Test fun `ReVanced sessions use their service settings`() {
        val settings = PresenceDisplaySettings(mapOf(PresenceService.YOUTUBE to PresenceContent.APP_NAME,
            PresenceService.YOUTUBE_MUSIC to PresenceContent.TRACK))
        assertEquals(0, compactPresence(snapshot(PresenceService.YOUTUBE).copy(
            packageName = "app.revanced.android.youtube", appLabel = "YouTube ReVanced"), settings).statusDisplay)
        assertEquals(1, compactPresence(snapshot(PresenceService.YOUTUBE_MUSIC).copy(
            packageName = "app.revanced.android.apps.youtube.music", appLabel = "YT Music ReVanced"), settings).statusDisplay)
    }

    @Test fun `missing artist creator or series falls back to title`() {
        PresenceService.entries.forEach { service ->
            for (artist in listOf(null, "", "  ")) {
                val settings = PresenceDisplaySettings(mapOf(service to PresenceContent.ARTIST))
                assertEquals(1, compactPresence(snapshot(service).copy(artist = artist), settings).statusDisplay)
            }
        }
    }

    @Test fun `untitled Hulu publishes app name without fabricated episode links or artwork`() {
        val hulu = snapshot(PresenceService.HULU).copy(title = "Hulu", artist = null, durationMs = null)
        val result = compactPresence(hulu, PresenceDisplaySettings())
        assertEquals(0, result.statusDisplay)
        assertEquals(PresencePresentation(null), result.presentation)
        assertNull(youtubeVideoId(hulu))
        assertEquals(1, compactPresence(hulu, PresenceDisplaySettings(
            mapOf(PresenceService.HULU to PresenceContent.ARTIST))).statusDisplay)
    }

    @Test fun `Crunchyroll season and episode use episode destination and preserve series links`() {
        val raw = snapshot(PresenceService.CRUNCHYROLL).copy(mediaId = "GE00345561JAJP")
        val enriched = enrichCrunchyrollSnapshot(raw, ResolvedCrunchyrollEpisode("Series", "Season 2", null,
            "G63K98PZ6", "G6K53V8V6", seasonNumber = 2, episodeNumber = "3.5"))
        val settings = PresenceDisplaySettings(mapOf(PresenceService.CRUNCHYROLL to PresenceContent.SEASON_EPISODE))
        val result = compactPresence(enriched, settings)
        assertEquals(2, result.statusDisplay)
        assertEquals("Season 2 · Episode 3.5", result.presentation.state)
        assertEquals(result.presentation.detailsUrl, result.presentation.stateUrl)
        assertEquals("https://www.crunchyroll.com/series/G63K98PZ6", result.presentation.button2Url)
        assertEquals(raw.title, enriched.title)
        assertEquals("Series", compactPresence(enriched, PresenceDisplaySettings()).presentation.state)
    }

    @Test fun `missing episode numbers fall back to episode title without guessing`() {
        val settings = PresenceDisplaySettings(mapOf(PresenceService.CRUNCHYROLL to PresenceContent.SEASON_EPISODE))
        val raw = snapshot(PresenceService.CRUNCHYROLL)
        listOf(raw, raw.copy(seasonNumber = 1), raw.copy(episodeNumber = "2"),
            raw.copy(seasonNumber = -1, episodeNumber = "2"), raw.copy(seasonNumber = 1, episodeNumber = " ")).forEach {
            assertEquals(1, compactPresence(it, settings).statusDisplay)
        }
    }

    @Test fun `patched Hulu can show season and episode while keeping its watch destination`() {
        val episode = snapshot(PresenceService.HULU).copy(metadataEnriched = true, seasonNumber = 2,
            episodeNumber = "3", contentUrl = "https://www.hulu.com/watch/episode-id")
        val settings = PresenceDisplaySettings(mapOf(PresenceService.HULU to PresenceContent.SEASON_EPISODE))
        val compact = compactPresence(episode, settings)
        assertEquals(2, compact.statusDisplay)
        assertEquals("Season 2 · Episode 3", compact.presentation.state)
        assertEquals("https://www.hulu.com/watch/episode-id", compact.presentation.stateUrl)
        assertEquals("Watch on Hulu", compact.presentation.button1Label)
        assertEquals(0, compactPresence(episode, PresenceDisplaySettings()).statusDisplay)
        assertEquals("Artist / creator / series", compactPresence(episode, PresenceDisplaySettings(
            mapOf(PresenceService.HULU to PresenceContent.ARTIST))).presentation.state)
    }

    @Test fun `Hulu season and episode preference falls back for movies and unpatched sessions`() {
        val settings = PresenceDisplaySettings(mapOf(PresenceService.HULU to PresenceContent.SEASON_EPISODE))
        listOf(snapshot(PresenceService.HULU), snapshot(PresenceService.HULU).copy(seasonNumber = 1),
            snapshot(PresenceService.HULU).copy(episodeNumber = "2")).forEach {
            assertEquals(1, compactPresence(it, settings).statusDisplay)
        }
        assertTrue(PresenceContent.SEASON_EPISODE in PresenceService.HULU.options)
        assertFalse(PresenceContent.SEASON_EPISODE in PresenceService.YOUTUBE.options)
    }

    @Test fun `unknown player ignores supported service choices and uses artist`() {
        val unknown = snapshot(PresenceService.YOUTUBE).copy(packageName = "example.player", appLabel = "Player")
        val settings = PresenceDisplaySettings(PresenceService.entries.associateWith { PresenceContent.APP_NAME })
        assertEquals(2, compactPresence(unknown, settings).statusDisplay)
    }
}
