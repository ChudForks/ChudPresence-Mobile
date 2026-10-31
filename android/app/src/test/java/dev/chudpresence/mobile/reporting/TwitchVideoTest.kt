package dev.chudpresence.mobile.reporting

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class TwitchVideoTest {
    @Test fun `video endpoint metadata preserves a VOD identity and thumbnail placeholders`() {
        val videos = parseTwitchVideos(Json.parseToJsonElement("""
            {"data":[{"id":"123456789","stream_id":"98765432101","user_login":"creator_login",
              "user_name":"Creator", "title":"An older broadcast","duration":"8h22m20s",
              "thumbnail_url":"https://img.example/%{width}x%{height}.jpg"}]}
        """))
        assertEquals(1, videos.size)
        assertEquals("123456789", videos.single().id)
        assertEquals("An older broadcast", videos.single().title)
        assertEquals(30_140_000L, videos.single().durationMs)
        assertEquals("https://img.example/320x180.jpg", videos.single().artworkUrl)
    }

    @Test fun `duration parser supports Twitch hours minutes and seconds`() {
        assertEquals(30_140_000L, parseTwitchVideoDuration("8h22m20s"))
        assertEquals(3_600_000L, parseTwitchVideoDuration("1h"))
        assertEquals(201_000L, parseTwitchVideoDuration("3m21s"))
        assertEquals(60_000L, parseTwitchVideoDuration("60s"))
        for (value in listOf(null, "", "0s", "-1s", "1:00", "999999999999999999999h")) assertNull(parseTwitchVideoDuration(value))
    }

    @Test fun `VOD selection requires a unique exact creator and duration match`() {
        val wanted = ResolvedTwitchVideo("123", "creator_login", "Creator", "Wanted", 30_140_000, null)
        val other = wanted.copy(id = "124", title = "Other broadcast", durationMs = 30_100_000)
        assertEquals(wanted, selectTwitchVideo(listOf(other, wanted), "creator_login", 30_140_000))
        assertNull(selectTwitchVideo(listOf(other), "creator_login", 30_140_000))
        assertNull(selectTwitchVideo(listOf(wanted), "another_creator", 30_140_000))
        assertNull(selectTwitchVideo(listOf(wanted, wanted.copy(id = "125")), "creator_login", 30_140_000))
    }

    @Test fun `missing deleted or malformed video metadata is not used`() {
        assertTrue(parseTwitchVideos(Json.parseToJsonElement("""{"data":[]}""")).isEmpty())
        assertTrue(parseTwitchVideos(Json.parseToJsonElement("""
            {"data":[{"id":"not-a-video-id","user_login":"creator_login","title":"Bad", "duration":"1h"}]}
        """)).isEmpty())
    }
}
