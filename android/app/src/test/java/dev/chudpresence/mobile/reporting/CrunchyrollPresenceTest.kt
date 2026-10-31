package dev.chudpresence.mobile.reporting

import dev.chudpresence.mobile.media.MediaSnapshot
import org.junit.Assert.*
import org.junit.Test

class CrunchyrollPresenceTest {
    private fun episode() = MediaSnapshot("session", "com.crunchyroll.crunchyroid", "Crunchyroll",
        "The Strongest Man", null, null, "playing", 1000, 1440000, null,
        mediaId = "GE00345561JAJP")

    @Test fun `enrichment links episode title and series name to different pages`() {
        val snapshot = enrichCrunchyrollSnapshot(episode(), ResolvedCrunchyrollEpisode(
            "ONE-PUNCH MAN", "Season 1", "https://img.example/episode.jpg", "G63K98PZ6", "G6K53V8V6"))
        val result = presencePresentation(snapshot)

        assertTrue(snapshot.crunchyrollEnriched)
        assertEquals(episode().title, snapshot.title)
        assertEquals(episode().mediaId, snapshot.mediaId)
        assertEquals(episode().positionMs, snapshot.positionMs)
        assertEquals(episode().durationMs, snapshot.durationMs)
        assertEquals("Season 1", snapshot.album)
        assertEquals("https://img.example/episode.jpg", snapshot.artworkUrl)
        assertEquals("https://www.crunchyroll.com/watch/G6K53V8V6", result.detailsUrl)
        assertEquals("ONE-PUNCH MAN", result.state)
        assertEquals("https://www.crunchyroll.com/series/G63K98PZ6", result.stateUrl)
        assertEquals(result.stateUrl, result.artworkClickUrl)
        assertEquals("Watch on Crunchyroll", result.button1Label)
        assertEquals(result.detailsUrl, result.button1Url)
        assertEquals("View series", result.button2Label)
        assertEquals(result.stateUrl, result.button2Url)
    }

    @Test fun `unenriched sessions keep direct episode link without a guessed series`() {
        val result = presencePresentation(episode())
        assertEquals("https://www.crunchyroll.com/watch/GE00345561JAJP", result.detailsUrl)
        assertEquals("Watch on Crunchyroll", result.button1Label)
        assertEquals(result.detailsUrl, result.button1Url)
        assertNull(result.state)
        assertNull(result.stateUrl)
        assertNull(result.artworkClickUrl)
        assertNull(result.button2Label)
        assertNull(result.button2Url)
    }

    @Test fun `partial enrichment retains text artwork and media session watch fallback`() {
        val raw = episode().copy(artist = "Series", album = "Season", artworkUrl = "https://img.example/raw.jpg")
        val snapshot = enrichCrunchyrollSnapshot(raw, ResolvedCrunchyrollEpisode(null, null, null))
        val result = presencePresentation(snapshot)
        assertEquals("Series", result.state)
        assertEquals(raw.album, snapshot.album)
        assertEquals(raw.artworkUrl, snapshot.artworkUrl)
        assertEquals("https://www.crunchyroll.com/watch/GE00345561JAJP", result.detailsUrl)
        assertNull(result.stateUrl)
        assertNull(result.button2Url)
    }

    @Test fun `missing episode id never fabricates a watch destination`() {
        val result = presencePresentation(episode().copy(mediaId = null))
        assertNull(result.detailsUrl)
        assertNull(result.button1Label)
        assertNull(result.button1Url)
    }

    @Test fun `public localized URLs are reduced to canonical content destinations`() {
        val result = presencePresentation(episode().copy(
            contentUrl = "https://www.crunchyroll.com/en-us/watch/G6K53V8V6/the-strongest-man?utm_source=test",
            artist = "Series", artistUrl = "https://crunchyroll.com/pt-br/series/G63K98PZ6/one-punch-man"))
        assertEquals("https://www.crunchyroll.com/watch/G6K53V8V6", result.detailsUrl)
        assertEquals("https://www.crunchyroll.com/series/G63K98PZ6", result.stateUrl)
    }

    @Test fun `foreign or wrong page URLs are omitted with a safe watch fallback`() {
        for (url in listOf("https://foreign.example/series/G63K98PZ6",
            "https://www.crunchyroll.com@foreign.example/series/G63K98PZ6",
            "http://www.crunchyroll.com/series/G63K98PZ6",
            "https://www.crunchyroll.com:8443/series/G63K98PZ6",
            "https://www.crunchyroll.com/watch/G6K53V8V6",
            "https://www.crunchyroll.com/series/G63K98PZ6%2Fanything")) {
            val result = presencePresentation(episode().copy(artist = "Series", artistUrl = url,
                contentUrl = "https://foreign.example/watch/G6K53V8V6"))
            assertEquals("https://www.crunchyroll.com/watch/GE00345561JAJP", result.detailsUrl)
            assertNull(result.stateUrl)
            assertNull(result.artworkClickUrl)
            assertNull(result.button2Label)
            assertNull(result.button2Url)
        }
    }

    @Test fun `series link without a series title never attaches to an empty state`() {
        val snapshot = enrichCrunchyrollSnapshot(episode(),
            ResolvedCrunchyrollEpisode(null, null, null, seriesId = "G63K98PZ6"))
        val result = presencePresentation(snapshot)
        assertNull(result.stateUrl)
        assertEquals("https://www.crunchyroll.com/series/G63K98PZ6", result.button2Url)
    }

    @Test fun `episode changes replace the previous series and watch destinations`() {
        val first = enrichCrunchyrollSnapshot(episode(),
            ResolvedCrunchyrollEpisode("Series 1", null, null, "G63K98PZ6", "G6K53V8V6"))
        val second = enrichCrunchyrollSnapshot(first.copy(title = "Another episode", mediaId = "G12345678"),
            ResolvedCrunchyrollEpisode("Series 2", null, null, "G87654321", "G12345678"))
        val result = presencePresentation(second)
        assertEquals("Series 2", result.state)
        assertEquals("https://www.crunchyroll.com/watch/G12345678", result.detailsUrl)
        assertEquals("https://www.crunchyroll.com/series/G87654321", result.stateUrl)
    }
}
