package dev.chudpresence.mobile.media

import org.junit.Assert.*
import org.junit.Test

class MetadataContractTest {
    @Test fun `future breaking versions never enable custom field interpretation`() {
        assertFalse(supportsMetadataVersion(0))
        assertTrue(supportsMetadataVersion(1))
        assertFalse(supportsMetadataVersion(2))
        assertFalse(supportsMetadataVersion(Long.MAX_VALUE))
        assertEquals("Title", metadataText("  Title \n"))
    }

    @Test fun `custom enums have bounded known semantics`() {
        assertEquals("episode", supportedContentType(" EPISODE "))
        assertEquals("music", supportedMediaKind(" MUSIC "))
        assertNull(supportedContentType("execute"))
        assertNull(supportedMediaKind("javascript"))
    }

    @Test fun `public media links retain ordinary content parameters and omit unsafe alternatives`() {
        assertEquals("https://www.youtube.com/watch?v=abcdefghijk", publicMetadataUrl(
            "content://media/1", "https://www.youtube.com/watch?v=abcdefghijk"))
        assertEquals("https://images.example.com/art.webp", publicArtworkUrl(
            "https://images.example.com/art.webp?token=private", "https://images.example.com/art.webp"))
    }

    @Test fun `public URLs reject credentials signed queries local names and private network destinations`() {
        val rejected = listOf(
            "http://example.com/cover", "file:///tmp/art.png", "content://media/art",
            "https://user:pass@example.com/art", "https://localhost/art", "https://device.local/art",
            "https://printer/art", "https://127.0.0.1/art", "https://10.0.0.1/art",
            "https://172.16.0.1/art", "https://192.168.1.1/art", "https://169.254.169.254/art",
            "https://100.64.0.1/art", "https://[::1]/art", "https://[fe80::1]/art",
            "https://0177.0.0.1/art", "https://0x7f.0.0.1/art", "https://[::ffff:100.64.0.1]/art",
            "https://[fd00::1]/art", "https://example.com:8443/art",
            "https://example.com/art?access_token=secret", "https://example.com/art?Signature=secret",
            "https://example.com/art?X-Amz-Credential=secret", "https://example.com/art?%74oken=secret",
            "https://example.com/art?access_%2574oken=secret", "https://example.com/art?key=secret",
            "https://example.com/art?Expires=1234", "https://example.com/art?hdnea=secret"
        )
        rejected.forEach { assertNull(it, publicArtworkUrl(it)) }
    }

    @Test fun `removed optional episode fields clear prior values for any patched source`() {
        val episode = MediaSnapshot("id", "example.video", "Example", "Episode", "Series", null,
            "playing", 0, 60_000, null, metadataEnriched = true)
            .withEpisodeMetadata(2, "3.5")
        assertEquals(2, episode.seasonNumber)
        assertEquals("3.5", episode.episodeNumber)
        val movie = episode.withEpisodeMetadata(null, null)
        assertNull(movie.seasonNumber)
        assertNull(movie.episodeNumber)
        assertNull(episode.withEpisodeMetadata(Int.MAX_VALUE.toLong() + 1, "1e2").seasonNumber)
        assertNull(episode.withEpisodeMetadata(0, "1e2").episodeNumber)
    }
}
