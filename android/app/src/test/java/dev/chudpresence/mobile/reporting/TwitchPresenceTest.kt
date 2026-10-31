package dev.chudpresence.mobile.reporting

import dev.chudpresence.mobile.media.MediaSnapshot
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class TwitchPresenceTest {
    private fun session() = MediaSnapshot("session", "tv.twitch.android.app", "Twitch",
        "creator_login", "Streaming Just Chatting", null, "playing", 1000, null, null)

    private fun stream() = parseTwitchStream(Json.parseToJsonElement("""
        {"data":[{"id":"12345678901","user_login":"creator_login","user_name":"Creator Display Name",
          "title":"A live stream title","game_name":"Just Chatting","started_at":"2026-10-22T20:00:00Z",
          "thumbnail_url":"https://img.example/{width}x{height}.jpg"}]}
    """))!!

    @Test fun `enriched stream title creator and artwork open the actual channel login`() {
        val snapshot = enrichTwitchSnapshot(session(), stream())
        val result = presencePresentation(snapshot)
        val channel = "https://www.twitch.tv/creator_login"
        assertEquals("A live stream title", snapshot.title)
        assertEquals("Creator Display Name • Live", result.state)
        assertEquals("Just Chatting", snapshot.album)
        assertEquals("https://img.example/640x360.jpg", snapshot.artworkUrl)
        assertTrue(snapshot.twitchEnriched)
        assertEquals(session().positionMs, snapshot.positionMs)
        assertEquals(session().durationMs, snapshot.durationMs)
        assertEquals(channel, result.detailsUrl)
        assertEquals(channel, result.stateUrl)
        assertEquals(channel, result.artworkClickUrl)
        assertEquals("Watch on Twitch", result.button1Label)
        assertEquals(channel, result.button1Url)
        assertNull(result.button2Url)
    }

    @Test fun `unenriched session preserves text without guessing links from a display name or stream id`() {
        val snapshot = session().copy(mediaId = "12345678901")
        val result = presencePresentation(snapshot)
        assertEquals(snapshot.artist, result.state)
        assertNull(result.detailsUrl)
        assertNull(result.stateUrl)
        assertNull(result.artworkClickUrl)
        assertNull(result.button1Label)
        assertNull(result.button1Url)
    }

    @Test fun `Twitch stream ids cannot become YouTube artwork ids`() {
        val snapshot = enrichTwitchSnapshot(session(), stream().copy(artworkUrl = null))
        assertEquals("12345678901", snapshot.mediaId)
        assertNull(youtubeVideoId(snapshot))
        assertNull(snapshot.artworkUrl)
    }

    @Test fun `public channel URLs normalize host login and tracking data`() {
        for (host in listOf("twitch.tv", "www.twitch.tv", "m.twitch.tv")) {
            val result = presencePresentation(session().copy(
                contentUrl = "https://$host/Creator_Login/?utm_source=test#player"))
            assertEquals("https://www.twitch.tv/creator_login", result.detailsUrl)
            assertEquals(result.detailsUrl, result.stateUrl)
        }
    }

    @Test fun `foreign and malformed URLs cannot be published as live channel links`() {
        for (url in listOf("https://foreign.example/creator_login",
            "https://www.twitch.tv@foreign.example/creator_login", "http://www.twitch.tv/creator_login",
            "https://www.twitch.tv:8443/creator_login",
            "https://www.twitch.tv/creator_login%2Fanything", "https://www.twitch.tv/creator_login/extra")) {
            val result = presencePresentation(session().copy(contentUrl = url, artistUrl = url))
            assertNull(result.detailsUrl)
            assertNull(result.stateUrl)
            assertNull(result.artworkClickUrl)
            assertNull(result.button1Url)
        }
    }

    @Test fun `switching channels replaces the title creator and destination together`() {
        val first = enrichTwitchSnapshot(session(), stream())
        val second = enrichTwitchSnapshot(session().copy(title = "another_creator"), stream().copy(
            id = "23456789012", userLogin = "another_creator", userName = "Another Creator", title = "Another stream"))
        val firstResult = presencePresentation(first)
        val result = presencePresentation(second)
        assertEquals("Another stream", second.title)
        assertEquals("Another Creator • Live", result.state)
        assertEquals("https://www.twitch.tv/another_creator", result.detailsUrl)
        assertEquals(result.detailsUrl, result.stateUrl)
        assertNotEquals(firstResult.detailsUrl, result.detailsUrl)
    }

    @Test fun `missing category and artwork retain media session fallbacks`() {
        val raw = session().copy(artworkUrl = "https://img.example/raw.jpg")
        val snapshot = enrichTwitchSnapshot(raw, stream().copy(gameName = null, artworkUrl = null))
        assertEquals("Just Chatting", snapshot.album)
        assertEquals(raw.artworkUrl, snapshot.artworkUrl)
    }

    @Test fun `invalid official login retains metadata without fabricating a URL`() {
        val snapshot = enrichTwitchSnapshot(session(), stream().copy(userLogin = "bad/login"))
        val result = presencePresentation(snapshot)
        assertEquals(stream().title, snapshot.title)
        assertEquals(stream().userName + " • Live", result.state)
        assertNull(result.detailsUrl)
        assertNull(result.stateUrl)
        assertNull(result.button1Url)
    }

    @Test fun `known creator destination can provide the channel when content URL is absent`() {
        val result = presencePresentation(session().copy(artistUrl = "https://www.twitch.tv/creator_login"))
        assertEquals("https://www.twitch.tv/creator_login", result.detailsUrl)
        assertEquals(result.detailsUrl, result.stateUrl)
    }

    @Test fun `missing creator text does not attach a URL to an empty state`() {
        val result = presencePresentation(session().copy(artist = null,
            contentUrl = "https://www.twitch.tv/creator_login"))
        assertNull(result.state)
        assertNull(result.stateUrl)
        assertEquals("https://www.twitch.tv/creator_login", result.detailsUrl)
    }

    @Test fun `Samsung VOD duration distinguishes recorded playback from a live stream`() {
        assertTrue(isTwitchVod(session().copy(durationMs = 30_140_000)))
        assertFalse(isTwitchVod(session()))
        assertFalse(isTwitchVod(session().copy(durationMs = 0)))
        assertTrue(isTwitchVod(session().copy(contentUrl = "https://www.twitch.tv/videos/123456789")))
    }

    @Test fun `VOD enrichment keeps the video title progress and direct video destination`() {
        val raw = session().copy(durationMs = 30_140_000, positionMs = 5_456_244)
        val video = ResolvedTwitchVideo("123456789", "creator_login", "Creator", "An older broadcast", 30_140_000, "https://img.example/vod.jpg")
        val snapshot = enrichTwitchVideoSnapshot(raw, video)
        val result = presencePresentation(snapshot)
        assertEquals("An older broadcast", snapshot.title)
        assertEquals("Creator", result.state)
        assertEquals(raw.positionMs, snapshot.positionMs)
        assertEquals(raw.durationMs, snapshot.durationMs)
        assertEquals("https://www.twitch.tv/videos/123456789", result.detailsUrl)
        assertEquals(result.detailsUrl, result.button1Url)
        assertEquals(result.detailsUrl, result.artworkClickUrl)
        assertEquals("https://www.twitch.tv/creator_login", result.stateUrl)
        assertEquals("Watch on Twitch", result.button1Label)
        assertNull(result.button2Label)
        assertNull(youtubeVideoId(snapshot))
    }

    @Test fun `unidentified VOD retains progress and opens channel videos without a guessed broadcast`() {
        val raw = session().copy(durationMs = 30_140_000, positionMs = 5_456_244)
        val fallback = twitchVodFallback(raw)
        val result = presencePresentation(fallback)
        assertEquals("Twitch VOD", fallback.title)
        assertEquals("creator_login", result.state)
        assertEquals(raw.durationMs, fallback.durationMs)
        assertEquals(raw.positionMs, fallback.positionMs)
        assertNull(result.detailsUrl)
        assertEquals("https://www.twitch.tv/creator_login/videos", result.button1Url)
        assertEquals("Watch on Twitch", result.button1Label)
    }

    @Test fun `a live stream id cannot be mistaken for a VOD id`() {
        val live = enrichTwitchSnapshot(session(), stream())
        assertFalse(isTwitchVod(live))
        assertNull(twitchVodId(live))
        assertEquals("12345678901", twitchVodId(session().copy(durationMs = 30_140_000, mediaId = "12345678901")))
    }
}
