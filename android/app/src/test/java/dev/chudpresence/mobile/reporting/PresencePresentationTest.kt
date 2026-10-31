package dev.chudpresence.mobile.reporting

import dev.chudpresence.mobile.media.MediaSnapshot
import org.junit.Assert.*
import org.junit.Test

class PresencePresentationTest {
    private fun song() = MediaSnapshot("session", "app.revanced.android.apps.youtube.music", "YT Music ReVanced",
        "A song", "An Artist", "An Album", "playing", 1000, 180000, null,
        mediaId = "abcdefghijk", artistUrl = "https://music.youtube.com/browse/UCartist",
        albumUrl = "https://music.youtube.com/browse/MPREalbum")

    @Test fun `song artist artwork and both buttons have independent destinations`() {
        val result = presencePresentation(song())
        assertEquals("https://music.youtube.com/watch?v=abcdefghijk", result.detailsUrl)
        assertEquals("An Artist", result.state)
        assertEquals(song().artistUrl, result.stateUrl)
        assertEquals(song().albumUrl, result.artworkClickUrl)
        assertEquals("Play on YouTube Music", result.button1Label)
        assertEquals(result.detailsUrl, result.button1Url)
        assertEquals("Search artist", result.button2Label)
        assertEquals("https://music.youtube.com/search?q=An+Artist", result.button2Url)
    }

    @Test fun `album metadata never replaces the artist second line`() {
        val result = presencePresentation(song())
        assertEquals("An Artist", result.state)
        assertEquals(song().artistUrl, result.stateUrl)
    }

    @Test fun `missing album falls back to artist text and artwork destination`() {
        val result = presencePresentation(song().copy(album = null, albumUrl = null))
        assertEquals("An Artist", result.state)
        assertEquals(song().artistUrl, result.stateUrl)
        assertEquals(song().artistUrl, result.artworkClickUrl)
    }

    @Test fun `unknown song id does not fabricate a play link`() {
        val result = presencePresentation(song().copy(mediaId = null))
        assertNull(result.detailsUrl)
        assertNull(result.button1Url)
        assertNotNull(result.button2Url)
    }

    @Test fun `youtube shorts preserve exact id and creator destination`() {
        val snapshot = song().copy(packageName = "app.revanced.android.youtube", appLabel = "YouTube ReVanced",
            artistUrl = "https://www.youtube.com/channel/UCcreator", contentType = "short")
        val result = presencePresentation(snapshot)
        assertEquals("https://www.youtube.com/shorts/abcdefghijk", result.detailsUrl)
        assertEquals("Watch Short", result.button1Label)
        assertEquals("View channel", result.button2Label)
        assertEquals(snapshot.artistUrl, result.stateUrl)
        assertEquals(snapshot.artistUrl, result.button2Url)
        assertNull(result.artworkClickUrl)
    }

    @Test fun `regular video and live content keep direct watch links`() {
        for (type in listOf("video", "live")) {
            val result = presencePresentation(song().copy(packageName = "com.google.android.youtube", appLabel = "YouTube", contentType = type))
            assertEquals("https://www.youtube.com/watch?v=abcdefghijk", result.detailsUrl)
            assertEquals("Watch on YouTube", result.button1Label)
        }
    }

    @Test fun `malformed ids and foreign destinations are not used`() {
        val result = presencePresentation(song().copy(mediaId = "not-an-id", artistUrl = "https://evil.example/browse/artist",
            albumUrl = "https://music.youtube.com@evil.example/album"))
        assertNull(result.detailsUrl)
        assertNull(result.stateUrl)
        assertNull(result.artworkClickUrl)
        assertNull(youtubeVideoId("https://evil.example/watch?v=abcdefghijk"))
        assertNull(youtubeVideoId("https://www.youtube.com:8443/watch?v=abcdefghijk"))
    }

    @Test fun `supported exact watch shorts and live URLs yield an id`() {
        for (url in listOf("https://youtu.be/abcdefghijk", "https://www.youtube.com/watch?v=abcdefghijk&x=1",
            "https://music.youtube.com/watch?v=abcdefghijk", "https://www.youtube.com/live/abcdefghijk",
            "https://www.youtube.com/shorts/abcdefghijk")) assertEquals("abcdefghijk", youtubeVideoId(url))
    }

    @Test fun `missing album link keeps artist text and link with artwork fallback`() {
        val result = presencePresentation(song().copy(albumUrl = null))
        assertEquals("An Artist", result.state)
        assertEquals(song().artistUrl, result.stateUrl)
        assertEquals(song().artistUrl, result.artworkClickUrl)
    }

    @Test fun `twitch button keeps its service name`() {
        val result = presencePresentation(song().copy(packageName = "tv.twitch.android.app", appLabel = "Twitch", contentUrl = "https://www.twitch.tv/channel"))
        assertEquals("Watch on Twitch", result.button1Label)
    }
}
