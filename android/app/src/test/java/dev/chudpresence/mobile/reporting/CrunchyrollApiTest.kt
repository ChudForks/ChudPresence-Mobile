package dev.chudpresence.mobile.reporting

import kotlinx.serialization.json.Json
import dev.chudpresence.mobile.data.CrunchyrollSession
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class CrunchyrollApiTest {
    @Test fun `failed episode lookup retains rotation for the next request`() = runBlocking {
        MockWebServer().use { server ->
            val api = localApi(server)
            val original = CrunchyrollSession("old-refresh", "device")
            server.enqueue(MockResponse()) // Homepage warm-up
            server.enqueue(MockResponse().setBody("""
                {"access_token":"new-access","refresh_token":"new-refresh","expires_in":14400}
            """))
            server.enqueue(MockResponse().setResponseCode(503))
            try {
                withTimeout(3_000) { api.resolveEpisode(original, "G6K53V8V6") }
                fail("The metadata lookup must fail")
            } catch (_: IOException) { }
            assertEquals(CrunchyrollSession("new-refresh", "device"), api.refreshedSession(original))

            repeat(3) { server.takeRequest() }
            server.enqueue(MockResponse().setBody("""{"data":[]}"""))
            assertNull(withTimeout(3_000) { api.resolveEpisode(original, "G6K53V8V6") })
            assertEquals("Bearer new-access", server.takeRequest().getHeader("Authorization"))
            assertEquals(4, server.requestCount)
            api.clear()
            assertNull(api.refreshedSession(original))
        }
    }

    @Test fun `a second rotation updates the original session after an episode 401`() = runBlocking {
        MockWebServer().use { server ->
            val api = localApi(server)
            val original = CrunchyrollSession("old-refresh", "device")
            server.enqueue(MockResponse())
            server.enqueue(MockResponse().setBody("""
                {"access_token":"new-access","refresh_token":"new-refresh","expires_in":14400}
            """))
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse())
            server.enqueue(MockResponse().setBody("""
                {"access_token":"latest-access","refresh_token":"latest-refresh","expires_in":14400}
            """))
            server.enqueue(MockResponse().setResponseCode(503))
            try {
                withTimeout(3_000) { api.resolveEpisode(original, "G6K53V8V6") }
                fail("The metadata lookup must fail")
            } catch (_: IOException) { }
            assertEquals(CrunchyrollSession("latest-refresh", "device"), api.refreshedSession(original))
            assertEquals(6, server.requestCount)
        }
    }

    private fun localApi(server: MockWebServer) = CrunchyrollApi(OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            val url = server.url(request.url.encodedPath).newBuilder()
                .encodedQuery(request.url.encodedQuery).build()
            chain.proceed(request.newBuilder().url(url).build())
        }.build())

    @Test fun `authorization URL uses Crunchyroll SSO code flow`() {
        val url = CrunchyrollApi().authorizationUrl("verifier", "anonymous").toHttpUrl()
        assertEquals("sso.crunchyroll.com", url.host)
        assertEquals("code", url.queryParameter("response_type"))
        assertEquals("verifier", url.queryParameter("code_challenge"))
        assertEquals("sso.crunchyroll://auth", url.queryParameter("redirect_uri"))
    }

    @Test fun `episode response exposes series season and largest artwork`() {
        val response = Json.parseToJsonElement("""
            {"data":[{
              "id":"G6K53V8V6",
              "series_id":"G63K98PZ6",
              "series_title":"ONE-PUNCH MAN",
              "season_title":"One-Punch Man Season 1",
              "images":{"thumbnail":[[
                {"source":"https://img.example/small.jpg","width":320,"height":180},
                {"source":"https://img.example/large.jpg","width":1280,"height":720}
              ]]}
            }]}
        """.trimIndent())

        assertEquals(
            ResolvedCrunchyrollEpisode(
                "ONE-PUNCH MAN",
                "One-Punch Man Season 1",
                "https://img.example/large.jpg",
                seriesId = "G63K98PZ6",
                episodeId = "G6K53V8V6"
            ),
            parseCrunchyrollEpisode(response)
        )
    }

    @Test fun `legacy object response reads nested episode metadata`() {
        val response = Json.parseToJsonElement("""
            {"data":[{"id":"G6K53V8V6","episode_metadata":{
              "series_title":"Series","season_title":"Season","series_id":"G63K98PZ6"}}]}
        """.trimIndent())
        assertEquals("Series", parseCrunchyrollEpisode(response)?.seriesTitle)
        assertEquals("Season", parseCrunchyrollEpisode(response)?.seasonTitle)
        assertEquals("G63K98PZ6", parseCrunchyrollEpisode(response)?.seriesId)
        assertEquals("G6K53V8V6", parseCrunchyrollEpisode(response)?.episodeId)
    }

    @Test fun `missing or malformed content ids do not fabricate destinations`() {
        for (id in listOf("", "../../watch/G6K53V8V6", "https://foreign.example/series/G63K98PZ6", "g63k98pz6")) {
            val response = Json.parseToJsonElement("""
                {"data":[{"id":"$id","series_id":"$id","series_title":"Series"}]}
            """.trimIndent())
            val episode = parseCrunchyrollEpisode(response)
            assertEquals("Series", episode?.seriesTitle)
            assertNull(episode?.seriesId)
            assertNull(episode?.episodeId)
        }
    }

    @Test fun `series id alone can still provide a series destination`() {
        val response = Json.parseToJsonElement("""{"data":[{"series_id":"G63K98PZ6"}]}""")
        assertEquals("G63K98PZ6", parseCrunchyrollEpisode(response)?.seriesId)
    }

    @Test fun `empty response is not treated as enrichment`() {
        assertNull(parseCrunchyrollEpisode(Json.parseToJsonElement("{\"data\":[]}")))
    }

    @Test fun `season and episode metadata preserves decimal episode labels`() {
        val episode = parseCrunchyrollEpisode(Json.parseToJsonElement("""
            {"data":[{"episode_metadata":{"season_number":2,"episode":"12.5","episode_number":12}}]}
        """.trimIndent()))
        assertEquals(2, episode?.seasonNumber)
        assertEquals("12.5", episode?.episodeNumber)
    }

    @Test fun `numeric episode number is used when episode label is blank`() {
        val episode = parseCrunchyrollEpisode(Json.parseToJsonElement("""
            {"data":[{"season_number":1,"episode":" ","episode_number":3}]}
        """.trimIndent()))
        assertEquals(1, episode?.seasonNumber)
        assertEquals("3", episode?.episodeNumber)
    }

    @Test fun `missing or malformed season numbers remain unavailable`() {
        for (season in listOf("null", "-1", "\"bad\"")) {
            val episode = parseCrunchyrollEpisode(Json.parseToJsonElement("""
                {"data":[{"series_title":"Series","season_number":$season,"episode":"1"}]}
            """.trimIndent()))
            assertNull(episode?.seasonNumber)
            assertEquals("1", episode?.episodeNumber)
        }
    }
}
