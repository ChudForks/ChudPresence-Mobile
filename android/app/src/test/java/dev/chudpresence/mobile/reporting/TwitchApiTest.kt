package dev.chudpresence.mobile.reporting

import kotlinx.serialization.json.Json
import dev.chudpresence.mobile.data.TwitchSession
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class TwitchApiTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun `device authorization response preserves code and timing`() {
        val authorization = parseTwitchDeviceAuthorization(json.parseToJsonElement("""
            {"device_code":"device","user_code":"ABC123","verification_uri":"https://www.twitch.tv/activate","expires_in":1800,"interval":5}
        """), 1_000)!!

        assertEquals("ABC123", authorization.userCode)
        assertEquals(1_801_000, authorization.expiresAtEpochMs)
        assertEquals(5, authorization.pollingIntervalSeconds)
    }

    @Test fun `token response creates an expiring encrypted-store session`() {
        val session = parseTwitchSession(json.parseToJsonElement("""
            {"access_token":"access","refresh_token":"refresh","expires_in":14400}
        """), 2_000)!!

        assertEquals("access", session.accessToken)
        assertEquals("refresh", session.refreshToken)
        assertEquals(14_402_000, session.expiresAtEpochMs)
    }

    @Test fun `stream response exposes official title category and artwork`() {
        val stream = parseTwitchStream(json.parseToJsonElement("""
            {"data":[{"id":"42","user_login":"zackrawrr","user_name":"zackrawrr","title":"A real stream title","game_name":"Just Chatting","started_at":"2026-09-16T20:00:00Z","thumbnail_url":"https://example.com/{width}x{height}.jpg"}]}
        """))!!

        assertEquals("A real stream title", stream.title)
        assertEquals("Just Chatting", stream.gameName)
        assertEquals("https://example.com/640x360.jpg", stream.artworkUrl)
    }

    @Test fun `offline stream response returns no enrichment`() {
        assertNull(parseTwitchStream(json.parseToJsonElement("""{"data":[]}""")))
    }

    @Test fun `expired token rotation survives a failed stream lookup and can be retried`() = runBlocking {
        MockWebServer().use { server ->
            val api = localApi(server)
            val original = TwitchSession("old-access", "old-refresh", 0)
            server.enqueue(rotatedTokens())
            server.enqueue(MockResponse().setResponseCode(503))

            try {
                withTimeout(3_000) { api.resolveStream(original, "example") }
                fail("The metadata lookup must fail")
            } catch (_: IOException) { }

            val refreshed = api.refreshedSession(original)!!
            assertEquals("new-access", refreshed.accessToken)
            assertEquals("new-refresh", refreshed.refreshToken)
            assertEquals("/oauth2/token", server.takeRequest().path)
            assertEquals("/helix/streams?user_login=example", server.takeRequest().path)

            // The reporting collector may be cancelled before it saves the rotation. A
            // replacement lookup must still use the new token rather than refresh the old one.
            server.enqueue(MockResponse().setBody("""{"data":[]}"""))
            val result = withTimeout(3_000) { api.resolveStream(original, "example") }
            assertEquals(refreshed, result.session)
            assertEquals("Bearer new-access", server.takeRequest().getHeader("Authorization"))
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun `a VOD 401 rotation survives failure of the authenticated retry`() = runBlocking {
        MockWebServer().use { server ->
            val api = localApi(server)
            val original = TwitchSession("old-access", "old-refresh", Long.MAX_VALUE)
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(rotatedTokens())
            server.enqueue(MockResponse().setResponseCode(503))

            try {
                withTimeout(3_000) { api.resolveVideo(original, "example", 60_000, "123") }
                fail("The metadata retry must fail")
            } catch (_: IOException) { }

            assertEquals("new-refresh", api.refreshedSession(original)?.refreshToken)
            api.clear()
            assertNull(api.refreshedSession(original))
        }
    }

    @Test fun `the original session tracks a second rotation after a 401`() = runBlocking {
        MockWebServer().use { server ->
            val api = localApi(server)
            val original = TwitchSession("old-access", "old-refresh", 0)
            server.enqueue(rotatedTokens())
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setBody("""
                {"access_token":"latest-access","refresh_token":"latest-refresh","expires_in":14400}
            """))
            server.enqueue(MockResponse().setResponseCode(503))
            try {
                withTimeout(3_000) { api.resolveStream(original, "example") }
                fail("The metadata lookup must fail")
            } catch (_: IOException) { }
            assertEquals("latest-refresh", api.refreshedSession(original)?.refreshToken)
            assertEquals(4, server.requestCount)
        }
    }

    @Test fun `cancelling metadata does not discard an already rotated token`() = runBlocking {
        MockWebServer().use { server ->
            val api = localApi(server)
            val original = TwitchSession("old-access", "old-refresh", 0)
            server.enqueue(rotatedTokens())
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            withTimeout(5_000) {
                val lookup = async { api.resolveStream(original, "example") }
                withContext(Dispatchers.IO) {
                    repeat(2) { assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
                }
                lookup.cancelAndJoin()
            }
            assertEquals("new-refresh", api.refreshedSession(original)?.refreshToken)
        }
    }

    private fun localApi(server: MockWebServer): TwitchApi = TwitchApi("client", OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            val url = server.url(request.url.encodedPath).newBuilder()
                .encodedQuery(request.url.encodedQuery).build()
            chain.proceed(request.newBuilder().url(url).build())
        }.build())

    private fun rotatedTokens() = MockResponse().setBody("""
        {"access_token":"new-access","refresh_token":"new-refresh","expires_in":14400}
    """)
}
