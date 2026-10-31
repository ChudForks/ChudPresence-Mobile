package dev.chudpresence.mobile.reporting

import dev.chudpresence.mobile.data.PresenceContent
import dev.chudpresence.mobile.data.PresenceDisplaySettings
import dev.chudpresence.mobile.media.MediaSnapshot
import dev.chudpresence.mobile.services.ServiceDefinition
import dev.chudpresence.mobile.services.ServiceDefinitions
import org.junit.Assert.*
import org.junit.Test

class GenericPresenceTest {
    private val definition = ServiceDefinition("example_video", "Example Video", setOf("example.video"),
        mediaKind = "video", defaultContent = PresenceContent.TRACK,
        allowedHosts = setOf("video.example.com"), contentButtonLabel = "Watch on Example Video")
    private fun raw() = MediaSnapshot("session", "example.video", "Example Video", "Episode", "Series", null,
        "playing", 20_000, 60_000, null)

    @Test fun `unpatched arbitrary app uses its definition and independent saved choice`() {
        val snapshot = prepareServiceSnapshot(raw(), definition)
        val settings = PresenceDisplaySettings(additionalServices = mapOf(definition.id to PresenceContent.APP_NAME))
        assertEquals(0, compactPresence(snapshot, settings, definition).statusDisplay)
        assertEquals(1, presenceActivityType(snapshot, definition))
        assertEquals("Series", presencePresentation(snapshot, definition).state)
    }

    @Test fun `Disney uses the standard subtitle for the episode and title for the series`() {
        val disney = ServiceDefinitions.builtIns.single { it.id == "disney_plus" }
        val raw = raw().copy(packageName = "com.disney.disneyplus", appLabel = "Disney+",
            title = "Hercules: The Animated Series", artist = null, rawTitle = "Hercules: The Animated Series",
            displaySubtitle = "S1:E1 Hercules and the Apollo Mission")
        val mapped = prepareServiceSnapshot(raw, disney)
        assertEquals(raw.displaySubtitle, mapped.title)
        assertEquals(raw.title, mapped.artist)
        assertEquals(1, presenceActivityType(mapped, disney))
        assertEquals(2, compactPresence(mapped, PresenceDisplaySettings(), disney).statusDisplay)
        assertNull(presencePresentation(mapped, disney).button1Url)
    }

    @Test fun `a compatible patch chooses canonical fields ahead of stock service mappings`() {
        val corrected = raw().copy(metadataEnriched = true, metadataVersion = 1, title = "Corrected episode",
            artist = "Corrected series", displaySubtitle = "Stale stock subtitle", rawTitle = "Corrected episode",
            mediaKind = "music")
        val mapping = definition.copy(titleField = "display_subtitle", artistField = "title")
        assertEquals(corrected, prepareServiceSnapshot(corrected, mapping))
        assertEquals(0, presenceActivityType(corrected, mapping))
    }

    @Test fun `generic stock session retains a useful subtitle without pretending it is missing`() {
        val snapshot = prepareServiceSnapshot(raw().copy(artist = null, displaySubtitle = "Available subtitle"), definition)
        assertEquals("Available subtitle", snapshot.artist)
        assertEquals(2, presenceActivityType(snapshot, definition.copy(mediaKind = "unknown")))
    }

    @Test fun `untitled partial sessions show the app name and do not inherit item metadata`() {
        val partial = raw().copy(title = "Example Video", artist = null, durationMs = null, isFallbackTitle = true)
        assertEquals(PresenceContent.APP_NAME, compactPresence(partial, PresenceDisplaySettings(), definition).content)
        assertNull(presencePresentation(partial, definition).button1Url)
    }

    @Test fun `patch episode keys work for a new service without a service enum entry`() {
        val snapshot = raw().copy(metadataEnriched = true, seasonNumber = 2, episodeNumber = "3.5")
        val settings = PresenceDisplaySettings(additionalServices = mapOf(definition.id to PresenceContent.SEASON_EPISODE))
        val result = compactPresence(snapshot, settings, definition)
        assertEquals("Season 2 · Episode 3.5", result.presentation.state)
        assertEquals(2, result.statusDisplay)
        val movie = compactPresence(snapshot.copy(seasonNumber = null, episodeNumber = null),
            settings, definition.copy(defaultContent = PresenceContent.ARTIST))
        assertEquals(PresenceContent.TRACK, movie.content)
        assertEquals(1, movie.statusDisplay)
    }

    @Test fun `preferences made for an unknown package survive acquiring a registry identity`() {
        val saved = PresenceDisplaySettings(additionalServices = mapOf("example.video" to PresenceContent.APP_NAME))
        assertEquals(PresenceContent.APP_NAME, saved.contentFor(definition))
    }

    @Test fun `generic links require allowed public destinations and never invent links from IDs`() {
        val good = raw().copy(contentUrl = "https://video.example.com/watch/episode", artistUrl = "https://video.example.com/series/one")
        val result = presencePresentation(good, definition)
        assertEquals(good.contentUrl, result.detailsUrl)
        assertEquals("Watch on Example Video", result.button1Label)
        assertEquals(good.artistUrl, result.stateUrl)
        for (url in listOf("https://evil.example/watch/episode", "https://video.example.com.evil.example/watch/id",
            "http://video.example.com/watch/id", "https://user:password@video.example.com/watch/id",
            "https://video.example.com/watch/id?access_token=private", "https://127.0.0.1/watch/id")) {
            assertNull(url, presencePresentation(good.copy(contentUrl = url), definition).button1Url)
        }
        assertNull(presencePresentation(raw().copy(mediaId = "content-id"), definition).detailsUrl)
        assertNull(presencePresentation(good, definition.copy(allowedHosts = emptySet())).detailsUrl)
    }
}
