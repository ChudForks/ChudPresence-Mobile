package dev.chudpresence.mobile.services

import dev.chudpresence.mobile.data.PresenceContent
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ServiceManifestCodecTest {
    private fun record(extra: String = "") = """{"id":"player","name":"Player","packages":["app.example.player"]$extra}"""
    private fun manifest(records: String = record(), version: Int = 1) = """{"schemaVersion":$version,"services":[$records]}"""
    private fun invalid(body: String) {
        assertTrue("Expected invalid manifest: ${body.take(200)}", runCatching { ServiceManifestCodec.decode(body) }.isFailure)
    }

    @Test fun `bundled app and publishable source manifests match built-in definitions`() {
        val asset = listOf("src/main/assets/presence-services.json", "app/src/main/assets/presence-services.json",
            "android/app/src/main/assets/presence-services.json").map(::File).first { it.isFile }.canonicalFile
        val remote = File(asset.parentFile, "../../../../../revanced/presence-services.json").canonicalFile
        assertEquals(ServiceDefinitions.builtIns, ServiceManifestCodec.decode(asset.readText()))
        if (remote.isFile) assertEquals(asset.readText(), remote.readText())
        assertEquals(ServiceDefinitions.builtIns, ServiceManifestCodec.decode(ServiceManifestCodec.encode(ServiceDefinitions.builtIns)))
    }

    @Test fun `Disney uses standard subtitle for episode title and title for series`() {
        val disney = ServiceDefinitions.builtIns.first { it.id == "disney_plus" }
        assertEquals("video", disney.mediaKind)
        assertEquals("display_subtitle", disney.titleField)
        assertEquals("title", disney.artistField)
        assertEquals(PresenceContent.ARTIST, disney.defaultContent)
        assertNull(disney.fallbackArtworkUrl)
    }

    @Test fun `additive unknown JSON properties do not change supported behavior`() {
        val definition = ServiceManifestCodec.decode(manifest(record(",\"futureLabel\":\"Ignored\""))).single()
        assertEquals("title", definition.titleField)
        assertEquals("artist", definition.artistField)
    }

    @Test fun `schema field types mappings and media kinds must be understood`() {
        invalid(manifest(version = 2))
        invalid(manifest().replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\""))
        invalid(manifest(record(",\"titleField\":\"run_script\"")))
        invalid(manifest(record(",\"artistField\":\"private_token\"")))
        invalid(manifest(record(",\"mediaKind\":\"whatever\"")))
        invalid(manifest(record(",\"supportsEpisodes\":\"true\"")))
        invalid(manifest(record(",\"defaultContent\":\"future_option\"")))
        invalid(manifest(record(",\"defaultContent\":\"season_episode\"")))
    }

    @Test fun `duplicate service IDs and package ownership are rejected`() {
        invalid(manifest(record() + "," + record()))
        invalid(manifest(record() + "," + record().replace("\"id\":\"player\"", "\"id\":\"other\"")))
        invalid(manifest(record().replace("[\"app.example.player\"]", "[\"app.example.player\",\"app.example.player\"]")))
    }

    @Test fun `download cannot assign bundled package to unrelated service ID`() {
        val stolen = ServiceDefinition("other", "Other", setOf("com.hulu.plus"))
        assertTrue(runCatching { ServiceManifestCodec.merge(ServiceDefinitions.builtIns, listOf(stolen)) }.isFailure)
    }

    @Test fun `hosts must be exact public hostnames`() {
        for (host in listOf("*.example.com", "127.0.0.1", "localhost", "media.local", "192.168.0.1", "EXAMPLE.COM", "example.com/path", "user@example.com")) {
            invalid(manifest(record(",\"allowedHosts\":[\"$host\"]")))
        }
        assertEquals(setOf("example.com"), ServiceManifestCodec.decode(manifest(record(",\"allowedHosts\":[\"example.com\"]"))).single().allowedHosts)
    }

    @Test fun `fallback artwork cannot carry credentials signed tokens or nonpublic destinations`() {
        for (url in listOf("http://example.com/logo.png", "https://user:password@example.com/logo.png",
            "https://127.0.0.1/logo.png", "https://example.com:8443/logo.png", "https://example.com/logo.png?token=secret",
            "https://example.com/logo.png?X-Amz-Signature=secret", "https://example.com/logo.png?access_%74oken=secret",
            "https://example.com/logo.png?%2573ig=secret", "file:///private/logo.png")) {
            invalid(manifest(record(",\"fallbackArtworkUrl\":\"$url\"")))
        }
    }

    @Test fun `cache accommodates normalized manifests and many Unicode discovered labels`() {
        val records = (1..240).joinToString(",") { index ->
            val packages = (1..4).joinToString(",") { alias -> "\"app.${"a".repeat(210)}.p${index}x${alias}\"" }
            """{"id":"player_$index","name":"Player $index","packages":[$packages]}"""
        }
        val definitions = ServiceManifestCodec.decode(manifest(records))
        val discovered = (1..512).associate { "app.${"a".repeat(240)}.p$it" to "界".repeat(80) }
        val cache = ServiceManifestCodec.encodeCache(definitions, discovered)
        assertTrue("Fixture must cover a cache larger than the old bound", cache.toByteArray().size > ServiceManifestCodec.MAX_BYTES * 2)
        assertEquals(RegistryCache(definitions, discovered), ServiceManifestCodec.decodeCache(cache))
    }

    @Test fun `oversized documents and unusable required labels are rejected`() {
        invalid(" ".repeat(ServiceManifestCodec.MAX_BYTES + 1))
        invalid(manifest(record().replace("\"name\":\"Player\"", "\"name\":\" \"")))
        invalid(manifest(record().replace("app.example.player", "../../private")))
        invalid(manifest(record(",\"titleLabel\":\"${"x".repeat(65)}\"")))
    }

    @Test fun `old caches remain readable and removal and category fields round trip`() {
        val old = """{"schemaVersion":1,"manifest":null,"discovered":{"com.soundcloud.android":"SoundCloud"}}"""
        val decoded = ServiceManifestCodec.decodeCache(old)
        assertTrue(decoded.removed.isEmpty())
        assertTrue(decoded.categories.isEmpty())
        val removed = listOf(RemovedService("com.soundcloud.android", "SoundCloud", setOf("com.soundcloud.android")))
        val categories = mapOf("com.soundcloud.android" to "MUSIC_AND_AUDIO")
        assertEquals(RegistryCache(null, decoded.discovered, removed, categories), ServiceManifestCodec.decodeCache(
            ServiceManifestCodec.encodeCache(null, decoded.discovered, removed, categories)))
    }

    @Test fun `invalid removal and category cache records are rejected`() {
        val cache = ServiceManifestCodec.encodeCache(null, mapOf("com.soundcloud.android" to "SoundCloud"),
            listOf(RemovedService("youtube", "YouTube", setOf("com.google.android.youtube"))),
            mapOf("com.soundcloud.android" to "MUSIC_AND_AUDIO"))
        listOf(cache.replace("MUSIC_AND_AUDIO", "untrusted value"),
            cache.replace("com.google.android.youtube", "../../private"),
            cache.replace("\"YouTube\"", "\" \""), cache.replace("\"youtube\"", "\"bad service id\"")).forEach {
            assertTrue(runCatching { ServiceManifestCodec.decodeCache(it) }.isFailure)
        }
    }
}
