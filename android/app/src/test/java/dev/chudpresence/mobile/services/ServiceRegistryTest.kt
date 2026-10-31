package dev.chudpresence.mobile.services

import dev.chudpresence.mobile.data.PresenceContent
import dev.chudpresence.mobile.media.MediaSnapshot
import dev.chudpresence.mobile.media.SessionSelector
import dev.chudpresence.mobile.reporting.presenceActivityType
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class ServiceRegistryTest {
    private val newService = ServiceDefinition("new_player", "New player", setOf("app.example.player"), "video")
    private val originalManifest get() = ServiceManifestCodec.encode(ServiceDefinitions.builtIns)
    private val extendedManifest get() = ServiceManifestCodec.encode(ServiceDefinitions.builtIns + newService)

    private class MemoryStorage(var contents: String? = null) : RegistryStorage {
        var writes = 0
        var failWrites = false
        var onWrite: ((String) -> Unit)? = null
        override fun read() = contents
        override fun write(contents: String) {
            if (failWrites) throw IOException("Disk unavailable")
            writes++
            this.contents = contents
            onWrite?.invoke(contents)
        }
    }

    @Test fun `opening registry and discovering known services makes no registry requests`() = runBlocking {
        var requests = 0
        val storage = MemoryStorage()
        val registry = ServiceRegistry(ServiceDefinitions.builtIns, { requests++; originalManifest }, storage)
        registry.beginReportingSession()
        for (definition in ServiceDefinitions.builtIns) {
            for (packageName in definition.packages) {
                assertEquals(definition, registry.resolve(packageName, "App label"))
                registry.discover(packageName, "App label")
            }
        }
        assertEquals(0, requests)
    }

    @Test fun `unknown stock application fetches definitions without requiring patch metadata`() = runBlocking {
        var requests = 0
        val registry = ServiceRegistry(ServiceDefinitions.builtIns, { requests++; extendedManifest }, MemoryStorage())
        registry.discover("app.example.player", "Stock Player")
        assertEquals(1, requests)
        assertEquals(newService, registry.resolve("app.example.player", "Stock Player"))
        assertEquals(newService, registry.definitionForId("new_player"))
        assertNull(registry.definitionForId("app.example.player"))
        registry.discover("app.example.player", "Stock Player")
        assertEquals(1, requests)
    }

    @Test fun `concurrent metadata events share one pending registry request`() = runBlocking {
        withTimeout(5_000) {
            val requests = AtomicInteger()
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val registry = ServiceRegistry(ServiceDefinitions.builtIns, {
                requests.incrementAndGet()
                started.complete(Unit)
                release.await()
                extendedManifest
            }, MemoryStorage())
            val discoveries = (1..16).map { async(Dispatchers.Default) { registry.discover("app.example.player", "Stock Player") } }
            started.await()
            assertTrue(registry.status.value.refreshing)
            release.complete(Unit)
            discoveries.awaitAll()
            assertEquals(1, requests.get())
            assertEquals(newService, registry.resolve("app.example.player", "Stock Player"))
            assertFalse(registry.status.value.refreshing)
        }
    }

    @Test fun `missing definition is remembered until another reporting session`() = runBlocking {
        var requests = 0
        val registry = ServiceRegistry(ServiceDefinitions.builtIns, { requests++; originalManifest }, MemoryStorage())
        repeat(5) { registry.discover("app.example.player", "Stock Player") }
        assertEquals(1, requests)
        val fallback = registry.resolve("app.example.player", "Stock Player")
        assertEquals("app.example.player", fallback.id)
        assertEquals("Stock Player", fallback.name)
        assertEquals("unknown", fallback.mediaKind)
        assertEquals(PresenceContent.TRACK, fallback.defaultContent)
        assertNull(registry.status.value.error)
        registry.beginReportingSession()
        registry.discover("app.example.player", "Stock Player")
        assertEquals(2, requests)
    }

    @Test fun `different unknown apps queued during one request share its missing result`() = runBlocking {
        coalesceMissingApps(failFetch = false)
    }

    @Test fun `different unknown apps queued during one request share its failure result`() = runBlocking {
        coalesceMissingApps(failFetch = true)
    }

    private suspend fun coalesceMissingApps(failFetch: Boolean) = kotlinx.coroutines.coroutineScope {
        withTimeout(5_000) {
            val requests = AtomicInteger()
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val secondSaved = CompletableDeferred<Unit>()
            val storage = MemoryStorage().apply {
                onWrite = { if (it.contains("app.example.other")) secondSaved.complete(Unit) }
            }
            val registry = ServiceRegistry(ServiceDefinitions.builtIns, {
                requests.incrementAndGet()
                started.complete(Unit)
                release.await()
                if (failFetch) throw IOException("Source unavailable")
                originalManifest
            }, storage)
            val first = async(Dispatchers.Default) { registry.discover("app.example.player", "First Player") }
            started.await()
            val second = async(Dispatchers.Default) { registry.discover("app.example.other", "Other Player") }
            secondSaved.await()
            release.complete(Unit)
            first.await()
            second.await()
            assertEquals(1, requests.get())
            registry.discover("app.example.player", "First Player")
            registry.discover("app.example.other", "Other Player")
            assertEquals(1, requests.get())
            // A genuinely new discovery after completion remains eligible for one lookup.
            registry.discover("app.example.later", "Later Player")
            assertEquals(2, requests.get())
        }
    }

    @Test fun `request failure keeps generic support and is not retried on every event`() = runBlocking {
        var requests = 0
        val registry = ServiceRegistry(ServiceDefinitions.builtIns, {
            requests++
            throw IOException("Source unavailable")
        }, MemoryStorage())
        repeat(5) { registry.discover("app.example.player", "Stock Player") }
        assertEquals(1, requests)
        assertEquals("Stock Player", registry.resolve("app.example.player", "Stock Player").name)
        assertNotNull(registry.status.value.error)
        assertFalse(registry.status.value.refreshing)
        registry.beginReportingSession()
        registry.discover("app.example.player", "Stock Player")
        assertEquals(2, requests)
    }

    @Test fun `manual refresh retries failed discovery and replaces provisional service`() = runBlocking {
        var requests = 0
        val registry = ServiceRegistry(ServiceDefinitions.builtIns, {
            if (++requests == 1) throw IOException("Source unavailable") else extendedManifest
        }, MemoryStorage())
        registry.discover("app.example.player", "Stock Player")
        registry.refresh()
        assertEquals(2, requests)
        assertEquals(newService, registry.resolve("app.example.player", "Stock Player"))
        assertEquals(1, registry.services.value.count { "app.example.player" in it.packages })
        assertNull(registry.status.value.error)
    }

    @Test fun `manual refresh of missing service does not immediately trigger another automatic request`() = runBlocking {
        var requests = 0
        val registry = ServiceRegistry(ServiceDefinitions.builtIns, { requests++; originalManifest }, MemoryStorage())
        registry.discover("app.example.player", "Stock Player")
        registry.refresh()
        registry.discover("app.example.player", "Stock Player")
        assertEquals(2, requests)
    }

    @Test fun `download supplies other services before they are encountered`() = runBlocking {
        val second = ServiceDefinition("other_player", "Other player", setOf("app.example.other"), "music")
        var requests = 0
        val registry = ServiceRegistry(ServiceDefinitions.builtIns, {
            requests++
            ServiceManifestCodec.encode(ServiceDefinitions.builtIns + newService + second)
        }, MemoryStorage())
        registry.discover("app.example.player", "Stock Player")
        registry.discover("app.example.other", "Other Player")
        assertEquals(1, requests)
        assertEquals(second, registry.resolve("app.example.other", "Other Player"))
    }

    @Test fun `cached definitions and discovered generic apps survive process restart without fetching`() = runBlocking {
        val storage = MemoryStorage()
        val initial = ServiceRegistry(ServiceDefinitions.builtIns, { extendedManifest }, storage)
        initial.discover("app.example.player", "Stock Player")
        initial.discover("app.example.missing", "Missing Player")
        var requests = 0
        val reopened = ServiceRegistry(ServiceDefinitions.builtIns, { requests++; error("Unexpected fetch") }, storage)
        assertEquals(newService, reopened.resolve("app.example.player", "Stock Player"))
        assertEquals("Missing Player", reopened.definitionForId("app.example.missing")?.name)
        reopened.discover("app.example.player", "Stock Player")
        assertEquals(0, requests)
    }

    @Test fun `invalid download preserves the previously committed registry`() = runBlocking {
        val storage = MemoryStorage()
        var body = extendedManifest
        val registry = ServiceRegistry(ServiceDefinitions.builtIns, { body }, storage)
        registry.discover("app.example.player", "Stock Player")
        val validCache = storage.contents
        body = "{\"schemaVersion\":99,\"services\":[]}"
        registry.refresh()
        assertEquals(validCache, storage.contents)
        assertEquals(newService, registry.resolve("app.example.player", "Stock Player"))
        assertNotNull(registry.status.value.error)
    }

    @Test fun `failed atomic cache commit does not replace valid in-memory definitions`() = runBlocking {
        val storage = MemoryStorage()
        val registry = ServiceRegistry(ServiceDefinitions.builtIns, { extendedManifest }, storage)
        storage.failWrites = true
        registry.discover("app.example.player", "Stock Player")
        assertEquals("app.example.player", registry.resolve("app.example.player", "Stock Player").id)
        assertEquals(ServiceDefinitions.builtIns.first(), registry.services.value.first())
        assertNotNull(registry.status.value.error)
    }

    @Test fun `unchanged media discovery retries a failed cache write`() = runBlocking {
        val storage = MemoryStorage().apply { failWrites = true }
        var requests = 0
        val registry = ServiceRegistry(ServiceDefinitions.builtIns, { requests++; originalManifest }, storage)
        registry.discover("app.example.player", "Player")
        assertNull(storage.contents)
        assertEquals("Player", registry.definitionForId("app.example.player")?.name)

        storage.failWrites = false
        registry.discover("app.example.player", "Player")
        assertEquals(1, requests) // Retrying storage does not repeat a completed network attempt.
        val reopened = ServiceRegistry(ServiceDefinitions.builtIns, { error("Unexpected fetch") }, storage)
        assertEquals("Player", reopened.definitionForId("app.example.player")?.name)
    }

    @Test fun `corrupt stored cache falls back to bundled definitions without requesting anything`() {
        val registry = ServiceRegistry(ServiceDefinitions.builtIns, { error("Unexpected fetch") }, MemoryStorage("broken json"))
        assertEquals(ServiceDefinitions.builtIns, registry.services.value)
    }

    @Test fun `invalid package cannot become a discovered service or trigger a request`() = runBlocking {
        var requests = 0
        val registry = ServiceRegistry(ServiceDefinitions.builtIns, { requests++; originalManifest }, MemoryStorage())
        registry.discover("../../private", "Bad package")
        assertEquals(0, requests)
        assertEquals(ServiceDefinitions.builtIns, registry.services.value)
    }

    @Test fun `generic SoundCloud uses Play category for Listening and caches it across restarts`() = runBlocking {
        val storage = MemoryStorage()
        var lookups = 0
        val registry = ServiceRegistry(ServiceDefinitions.builtIns, { originalManifest }, storage, { pkg ->
            assertEquals("com.soundcloud.android", pkg)
            lookups++
            GooglePlayAppInfo("MUSIC_AND_AUDIO", "https://play-lh.googleusercontent.com/soundcloud-icon")
        })
        repeat(5) { registry.discover("com.soundcloud.android", "SoundCloud") }
        val definition = registry.resolve("com.soundcloud.android", "SoundCloud")
        val snapshot = MediaSnapshot("soundcloud", "com.soundcloud.android", "SoundCloud", "Song", "Artist", null,
            "playing", 1_000, 60_000, null)
        assertEquals(0, presenceActivityType(snapshot, definition))
        // Canonical metadata from a compatible patch takes precedence over the app category.
        assertEquals(1, presenceActivityType(snapshot.copy(mediaKind = "video"), definition))
        assertEquals(1, lookups)
        val reopened = ServiceRegistry(ServiceDefinitions.builtIns, { originalManifest }, storage, { error("Cached app info was fetched") })
        reopened.discover(snapshot.packageName, snapshot.appLabel)
        assertEquals("music", reopened.resolve(snapshot.packageName, snapshot.appLabel).mediaKind)
    }

    @Test fun `Play failure retains basic support retries only on refresh or a new reporting run`() = runBlocking {
        var lookups = 0
        var available = false
        val registry = ServiceRegistry(ServiceDefinitions.builtIns, { originalManifest }, MemoryStorage(), {
            lookups++
            if (available) GooglePlayAppInfo("VIDEO_PLAYERS") else throw IOException("Play unavailable")
        })
        repeat(4) { registry.discover("app.example.player", "Player") }
        assertEquals(1, lookups)
        assertEquals("unknown", registry.resolve("app.example.player", "Player").mediaKind)
        registry.beginReportingSession()
        registry.discover("app.example.player", "Player")
        assertEquals(2, lookups)
        available = true
        registry.refresh()
        assertEquals(3, lookups)
        assertEquals("video", registry.resolve("app.example.player", "Player").mediaKind)
    }

    @Test fun `registry definitions retain their media kind while using a Play icon fallback`() = runBlocking {
        var lookups = 0
        val icon = "https://play-lh.googleusercontent.com/player-icon"
        val registry = ServiceRegistry(ServiceDefinitions.builtIns, { extendedManifest }, MemoryStorage(), {
            lookups++; GooglePlayAppInfo("MUSIC_AND_AUDIO", icon)
        })
        registry.discover("com.hulu.plus", "Hulu")
        registry.discover("app.example.player", "Player")
        assertEquals(newService.copy(fallbackArtworkUrl = icon), registry.resolve("app.example.player", "Player"))
        assertEquals(1, lookups)
    }

    @Test fun `ReVanced variants use stock listings and share persistent icons with the stock app`() = runBlocking {
        for ((alias, stock) in listOf(
            "app.revanced.android.youtube" to "com.google.android.youtube",
            "app.revanced.android.apps.youtube.music" to "com.google.android.apps.youtube.music"
        )) {
            val storage = MemoryStorage()
            val requests = mutableListOf<String>()
            val icon = "https://play-lh.googleusercontent.com/$stock-icon"
            val registry = ServiceRegistry(ServiceDefinitions.builtIns, { error("Known app fetched registry") }, storage, {
                requests += it
                GooglePlayAppInfo("MUSIC_AND_AUDIO", icon)
            })
            registry.discover(alias, "Patched app")
            assertEquals(listOf(stock), requests)
            assertEquals(icon, registry.resolve(alias, "Patched app").fallbackArtworkUrl)
            assertEquals(icon, registry.resolve(stock, "Stock app").fallbackArtworkUrl)
            registry.discover(stock, "Stock app")
            assertEquals(listOf(stock), requests)
            val cache = ServiceManifestCodec.decodeCache(storage.contents!!)
            assertEquals(icon, cache.icons[alias])
            val reopened = ServiceRegistry(ServiceDefinitions.builtIns, { error("Unexpected registry lookup") }, storage,
                { error("Cached icon was fetched again") })
            reopened.discover(alias, "Patched app")
            reopened.discover(stock, "Stock app")
            assertEquals(icon, reopened.resolve(alias, "Patched app").fallbackArtworkUrl)
        }
    }

    @Test fun `every built in has a supplied logo or can use its Play listing icon`() = runBlocking {
        val lookups = mutableListOf<String>()
        val registry = ServiceRegistry(ServiceDefinitions.builtIns, { error("Known app fetched registry") }, MemoryStorage(), {
            lookups += it
            GooglePlayAppInfo(iconUrl = "https://play-lh.googleusercontent.com/$it-icon")
        })
        for (service in ServiceDefinitions.builtIns) {
            val stock = service.packages.first { !it.startsWith("app.revanced.") }
            registry.discover(stock, service.name)
            assertNotNull(service.name, registry.resolve(stock, service.name).fallbackArtworkUrl)
            assertEquals(service.mediaKind, registry.resolve(stock, service.name).mediaKind)
        }
        assertEquals(5, lookups.size) // Hulu ships a logo and needs no request.
    }

    @Test fun `SoundCloud and Disney Plus return on their next media discovery without reinstalling`() = runBlocking {
        val storage = MemoryStorage()
        var requests = 0
        var lookups = 0
        val registry = ServiceRegistry(ServiceDefinitions.builtIns, { requests++; originalManifest }, storage,
            { lookups++; GooglePlayAppInfo("MUSIC_AND_AUDIO") })
        registry.discover("com.soundcloud.android", "SoundCloud")
        registry.remove("com.soundcloud.android")
        registry.remove("disney_plus")
        assertFalse(registry.isEnabled("com.soundcloud.android"))
        assertFalse(registry.isEnabled("com.disney.disneyplus"))
        assertNull(registry.definitionForId("disney_plus"))
        assertNull(registry.definitionForId("com.soundcloud.android"))
        assertEquals(1, lookups)
        registry.refresh()
        val reopened = ServiceRegistry(ServiceDefinitions.builtIns, { originalManifest }, storage)
        assertEquals(setOf("disney_plus", "com.soundcloud.android"), ServiceManifestCodec.decodeCache(storage.contents!!).removed.map { it.id }.toSet())
        assertFalse(reopened.isEnabled("com.soundcloud.android"))
        assertFalse(reopened.isEnabled("com.disney.disneyplus"))
        reopened.beginReportingSession()
        reopened.discover("com.soundcloud.android", "SoundCloud")
        reopened.discover("com.disney.disneyplus", "Disney+")
        assertTrue(reopened.isEnabled("com.soundcloud.android"))
        assertTrue(reopened.isEnabled("com.disney.disneyplus"))
        assertEquals("music", reopened.definitionForId("com.soundcloud.android")?.mediaKind)
        assertEquals("video", reopened.definitionForId("disney_plus")?.mediaKind)
        assertTrue(ServiceManifestCodec.decodeCache(storage.contents!!).removed.isEmpty())
    }

    @Test fun `removing current app lets reporting select another session or clear when none remain`() = runBlocking {
        val registry = ServiceRegistry(ServiceDefinitions.builtIns, { originalManifest }, MemoryStorage())
        val youtube = MediaSnapshot("youtube", "com.google.android.youtube", "YouTube", "Video", null, null, "playing", null, null, null)
        val music = youtube.copy(sessionId = "music", packageName = "com.google.android.apps.youtube.music")
        registry.remove("youtube")
        assertEquals(music, SessionSelector.select(listOf(youtube, music).filter { registry.isEnabled(it.packageName) }, youtube.sessionId))
        registry.remove("youtube_music")
        assertNull(SessionSelector.select(listOf(youtube, music).filter { registry.isEnabled(it.packageName) }, music.sessionId))
    }

    @Test fun `later registry identity stays hidden until its package is discovered again`() = runBlocking {
        var downloaded = originalManifest
        val registry = ServiceRegistry(ServiceDefinitions.builtIns, { downloaded }, MemoryStorage())
        registry.discover("app.example.player", "Player")
        registry.remove("app.example.player")
        downloaded = ServiceManifestCodec.encode(ServiceDefinitions.builtIns + newService.copy(packages = setOf("app.example.player", "app.example.alias")))
        registry.refresh()
        assertFalse(registry.isEnabled("app.example.player"))
        assertFalse(registry.isEnabled("app.example.alias"))
        assertNull(registry.definitionForId("new_player"))
        registry.discover("app.example.alias", "Player")
        assertNotNull(registry.definitionForId("new_player"))
    }

    @Test fun `failed cache write leaves removal and rediscovery uncommitted`() = runBlocking {
        val storage = MemoryStorage()
        val registry = ServiceRegistry(ServiceDefinitions.builtIns, { originalManifest }, storage)
        storage.failWrites = true
        assertTrue(runCatching { registry.remove("hulu") }.isFailure)
        assertTrue(registry.isEnabled("com.hulu.plus"))
        storage.failWrites = false
        registry.remove("hulu")
        storage.failWrites = true
        registry.discover("com.hulu.plus", "Hulu")
        assertFalse(registry.isEnabled("com.hulu.plus"))
        assertNotNull(registry.status.value.error)
    }

    @Test fun `old reinstall-only removal cache can rediscover an already installed app`() = runBlocking {
        val storage = MemoryStorage("""{"schemaVersion":1,"manifest":null,"discovered":{},
            "removed":[{"id":"hulu","name":"Hulu","packages":["com.hulu.plus"],"removedAt":100}]}""")
        val reopened = ServiceRegistry(ServiceDefinitions.builtIns, { originalManifest }, storage)
        assertFalse(reopened.isEnabled("com.hulu.plus"))
        reopened.discover("com.hulu.plus", "Hulu")
        assertTrue(reopened.isEnabled("com.hulu.plus"))
    }

    @Test fun `pending Play result cannot resurrect an app removed during lookup`() = runBlocking {
        withTimeout(5_000) {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val registry = ServiceRegistry(ServiceDefinitions.builtIns, { originalManifest }, MemoryStorage(), {
                started.complete(Unit)
                release.await()
                GooglePlayAppInfo("MUSIC_AND_AUDIO")
            })
            val discovery = async { registry.discover("com.soundcloud.android", "SoundCloud") }
            started.await()
            registry.remove("com.soundcloud.android")
            release.complete(Unit)
            discovery.await()
            assertFalse(registry.isEnabled("com.soundcloud.android"))
            assertNull(registry.definitionForId("com.soundcloud.android"))
        }
    }
}
