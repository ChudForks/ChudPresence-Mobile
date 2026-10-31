package dev.chudpresence.mobile.reporting

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeVideoResolverTest {
    private val json = Json

    @Test fun `selects exact title and channel with closest duration`() {
        val response = json.parseToJsonElement("""
            {"contents":[
              {"videoRenderer":{"videoId":"AAAAAAAAAAA","title":{"runs":[{"text":"The Example"}]},
                "ownerText":{"runs":[{"text":"Example Channel"}]},"lengthText":{"simpleText":"10:00"}}},
              {"videoRenderer":{"videoId":"BBBBBBBBBBB","title":{"runs":[{"text":"The Example"}]},
                "ownerText":{"runs":[{"text":"Example Channel"}]},"lengthText":{"simpleText":"3:31"}}}
            ]}
        """.trimIndent())

        val result = selectYouTubeVideo(response, "The Example", "Example Channel", 212_000)

        assertEquals("BBBBBBBBBBB", result?.videoId)
        assertEquals("https://i.ytimg.com/vi/BBBBBBBBBBB/hqdefault.jpg", result?.artworkUrl)
    }

    @Test fun `normalizes punctuation and accents`() {
        val response = json.parseToJsonElement("""
            {"videoRenderer":{"videoId":"AbCdEfGhI_1","title":{"runs":[{"text":"Café — It's Fine!"}]},
              "ownerText":{"runs":[{"text":"Creator.Name"}]},"lengthText":{"simpleText":"1:05"}}}
        """.trimIndent())

        assertEquals(
            "AbCdEfGhI_1",
            selectYouTubeVideo(response, "Cafe, Its Fine", "Creator Name", 65_000)?.videoId
        )
    }

    @Test fun `rejects a different channel or implausible duration`() {
        val response = json.parseToJsonElement("""
            {"videoRenderer":{"videoId":"AAAAAAAAAAA","title":{"simpleText":"The Example"},
              "ownerText":{"runs":[{"text":"Wrong Channel"}]},"lengthText":{"simpleText":"25:00"}}}
        """.trimIndent())

        assertNull(selectYouTubeVideo(response, "The Example", "Example Channel", 212_000))
    }
}
