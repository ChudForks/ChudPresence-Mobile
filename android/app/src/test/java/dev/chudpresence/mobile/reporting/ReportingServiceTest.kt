package dev.chudpresence.mobile.reporting

import org.junit.Assert.assertEquals
import org.junit.Test

class ReportingServiceTest {
    @Test fun `stock YouTube Music package is recognized`() {
        assertEquals("youtube_music", classifyMediaSource("com.google.android.apps.youtube.music", "Music"))
    }

    @Test fun `custom YouTube Music client label is recognized`() {
        assertEquals("youtube_music", classifyMediaSource("app.custom.music", "YouTube Music"))
    }

    @Test fun `ReVanced YouTube Music package is recognized`() {
        assertEquals("youtube_music", classifyMediaSource("app.revanced.android.apps.youtube.music", "YT Music ReVanced"))
    }

    @Test fun `unrelated player remains unknown`() {
        assertEquals("unknown", classifyMediaSource("app.example.player", "Example Player"))
    }

    @Test fun `Crunchyroll package is recognized`() {
        assertEquals("crunchyroll", classifyMediaSource("com.crunchyroll.crunchyroid", "Crunchyroll"))
    }

    @Test fun `Twitch package is recognized`() {
        assertEquals("twitch", classifyMediaSource("tv.twitch.android.app", "Twitch"))
    }

    @Test fun `Hulu package is recognized even without a visible application label`() {
        assertEquals("hulu", classifyMediaSource("com.hulu.plus", "com.hulu.plus"))
        assertEquals("unknown", classifyMediaSource("example.player", "Hulu"))
    }


    @Test fun `Crunchyroll media id becomes a watch URL`() {
        assertEquals(
            "https://www.crunchyroll.com/watch/GE00345561JAJP",
            crunchyrollWatchUrl("com.crunchyroll.crunchyroid", "GE00345561JAJP")
        )
    }

    @Test fun `invalid or unrelated media ids do not become Crunchyroll URLs`() {
        assertEquals(null, crunchyrollWatchUrl("com.crunchyroll.crunchyroid", "not/a/content-id"))
        assertEquals(null, crunchyrollWatchUrl("app.example.player", "GE00345561JAJP"))
    }

    @Test fun `Discord text truncation respects UTF-8 byte limit without splitting emoji`() {
        val value = "🧠".repeat(40) + " ending"
        val result = discordText(value)
        assertEquals(128, result.encodeToByteArray().size)
        assertEquals("🧠".repeat(32), result)
    }

    @Test fun `Discord text truncation preserves ordinary short text`() {
        assertEquals("Short stream", discordText("  Short stream  "))
    }
}
