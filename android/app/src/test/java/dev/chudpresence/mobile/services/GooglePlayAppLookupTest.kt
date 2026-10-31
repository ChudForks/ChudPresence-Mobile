package dev.chudpresence.mobile.services

import org.junit.Assert.*
import org.junit.Test

class GooglePlayAppLookupTest {
    private val pkg = "com.soundcloud.android"
    private val iconUrl = "https://play-lh.googleusercontent.com/soundcloud-icon"
    private fun listing(category: String = "MUSIC_AND_AUDIO", packageName: String = pkg) = """
        <a href="/store/apps/category/FAMILY">Kids</a>
        <script type="application/ld+json">{"@type":"SoftwareApplication",
        "name":"SoundCloud: Discover Music",
        "url":"https://play.google.com/store/apps/details/SoundCloud_Discover_Music?id\u003d$packageName\u0026hl\u003den_US",
        "applicationCategory":"$category","image":"$iconUrl"}</script>
    """.trimIndent()

    @Test fun `SoundCloud structured listing identifies music without reading navigation categories`() {
        val info = GooglePlayAppLookup.parse(listing(), pkg)
        assertEquals("MUSIC_AND_AUDIO", info?.category)
        assertEquals(iconUrl, info?.iconUrl)
        assertEquals("music", GooglePlayAppLookup.mediaKind(info?.category))
    }

    @Test fun `category mapping covers listening watching and neutral fallback`() {
        assertEquals("music", GooglePlayAppLookup.mediaKind("MUSIC_AND_AUDIO"))
        assertEquals("video", GooglePlayAppLookup.mediaKind("VIDEO_PLAYERS"))
        assertEquals("video", GooglePlayAppLookup.mediaKind("ENTERTAINMENT"))
        listOf(null, "SOCIAL", "BOOKS_AND_REFERENCE", "GAME_MUSIC", "FUTURE_CATEGORY").forEach {
            assertEquals("unknown", GooglePlayAppLookup.mediaKind(it))
        }
    }

    @Test fun `wrong package recommendations and login pages cannot classify the app`() {
        assertNull(GooglePlayAppLookup.parse(listing(packageName = "app.example.recommendation"), pkg))
        assertNull(GooglePlayAppLookup.parse("<a href='/store/apps/category/MUSIC_AND_AUDIO'>Music & Audio</a>", pkg))
        assertNull(GooglePlayAppLookup.parse("<html>Please sign in</html>", pkg))
        assertNull(GooglePlayAppLookup.parse(listing().replace("SoftwareApplication", "WebSite"), pkg))
        assertNull(GooglePlayAppLookup.parse(listing().replace("play.google.com", "example.com"), pkg))
        assertNull(GooglePlayAppLookup.parse(listing("unknown category"), pkg)?.category)
        assertEquals("MUSIC_AND_AUDIO", GooglePlayAppLookup.parse(listing(packageName = "other.app") + listing(), pkg)?.category)
    }

    @Test fun `malformed JSON is ignored and single quoted script attributes are supported`() {
        val malformed = "<script type='application/ld+json'>{broken}</script>"
        assertNull(GooglePlayAppLookup.parse(malformed, pkg))
        assertEquals("MUSIC_AND_AUDIO", GooglePlayAppLookup.parse(malformed +
            listing().replace("type=\"application/ld+json\"", "TYPE = 'application/ld+json'"), pkg)?.category)
    }
}
