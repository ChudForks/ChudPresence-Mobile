package dev.chudpresence.mobile.reporting

import dev.chudpresence.mobile.media.HULU_PACKAGE
import dev.chudpresence.mobile.media.MediaSnapshot
import org.junit.Assert.*
import org.junit.Test

class HuluPresenceTest {
    private fun episode() = MediaSnapshot("hulu", HULU_PACKAGE, "Hulu", "Episode title", "Series name",
        "Season 2 · Episode 3", "playing", 20_000, 1_500_000, null, metadataEnriched = true,
        contentUrl = "https://www.hulu.com/watch/episode-id?utm_source=share", seasonNumber = 2, episodeNumber = "3")

    @Test fun `episode title artwork and watch button open the clean public Hulu page`() {
        val presentation = presencePresentation(episode())
        assertEquals("Series name", presentation.state)
        assertEquals("https://www.hulu.com/watch/episode-id", presentation.detailsUrl)
        assertEquals(presentation.detailsUrl, presentation.artworkClickUrl)
        assertEquals("Watch on Hulu", presentation.button1Label)
        assertEquals(presentation.detailsUrl, presentation.button1Url)
        assertNull(presentation.stateUrl)
        assertNull(presentation.button2Url)
    }

    @Test fun `content or asset IDs alone do not fabricate a Hulu watch destination`() {
        for (id in listOf(null, "12345", "12345678-1234-5678-9abc-123456789abc", "not-an-id")) {
            val presentation = presencePresentation(episode().copy(contentUrl = null, mediaId = id))
            assertNull(presentation.detailsUrl)
            assertNull(presentation.button1Url)
        }
    }

    @Test fun `private invalid or unrelated destinations are not published`() {
        for (invalid in listOf("http://www.hulu.com/watch/id", "https://www.hulu.com.evil.example/watch/id",
            "https://user:pass@www.hulu.com/watch/id", "https://www.hulu.com:8443/watch/id",
            "https://play.hulu.com/license", "https://www.hulu.com/api/items", "file:///watch/id",
            "https://www.hulu.com/watch/%2e%2e", "https://www.hulu.com/watch/id/extra")) {
            val presentation = presencePresentation(episode().copy(contentUrl = invalid))
            assertNull(invalid, presentation.detailsUrl)
            assertNull(invalid, presentation.button1Url)
        }
    }

    @Test fun `unpatched Hulu keeps its fallback without fabricated watch destinations`() {
        val presentation = presencePresentation(episode().copy(title = "Hulu", artist = null, album = null,
            durationMs = null, contentUrl = null, metadataEnriched = false, seasonNumber = null, episodeNumber = null))
        assertEquals(PresencePresentation(null), presentation)
    }
}
