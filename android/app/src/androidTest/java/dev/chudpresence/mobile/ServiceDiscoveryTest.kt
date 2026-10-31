package dev.chudpresence.mobile

import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.platform.app.InstrumentationRegistry
import dev.chudpresence.mobile.data.SettingsStore
import dev.chudpresence.mobile.data.PresenceContent
import dev.chudpresence.mobile.media.MediaSessionRepository
import dev.chudpresence.mobile.media.MediaSnapshot
import dev.chudpresence.mobile.reporting.compactPresence
import dev.chudpresence.mobile.reporting.prepareServiceSnapshot
import dev.chudpresence.mobile.reporting.presenceActivityType
import dev.chudpresence.mobile.services.RegistryStorage
import dev.chudpresence.mobile.services.ServiceDefinition
import dev.chudpresence.mobile.services.ServiceDefinitions
import dev.chudpresence.mobile.services.ServiceManifestCodec
import dev.chudpresence.mobile.services.ServiceRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Isolated registry, inactive media session, and disposable settings. Never starts reporting or uses the network. */
class ServiceDiscoveryTest {
    private class MemoryStorage : RegistryStorage {
        @Volatile var contents: String? = null
        override fun read(): String? = contents
        override fun write(contents: String) { this.contents = contents }
    }

    private fun stockSnapshot(): MediaSnapshot {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        var snapshot: MediaSnapshot? = null
        instrumentation.runOnMainSync {
            val session = MediaSession(context, "Unknown stock service discovery fixture")
            try {
                session.setMetadata(MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, "Fixture series")
                    .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, "Fixture episode")
                    .putString(MediaMetadata.METADATA_KEY_MEDIA_URI, "https://example.com/watch/episode")
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, 60_000).build())
                session.setPlaybackState(PlaybackState.Builder()
                    .setState(PlaybackState.STATE_PLAYING, 12_000, 1f).build())
                snapshot = MediaSessionRepository(context).toSnapshot(session.controller)
            } finally {
                session.release()
            }
        }
        return snapshot!!
    }

    private fun definition(snapshot: MediaSnapshot) = ServiceDefinition(
        id = "fixture_video", name = "Fixture Video", packages = setOf(snapshot.packageName),
        mediaKind = "video", titleField = "display_subtitle", artistField = "title",
        allowedHosts = setOf("example.com"), defaultContent = PresenceContent.ARTIST
    )

    @Test fun unknownUnpatchedSessionUsesGenericPresenceWhileOneDefinitionFetchIsPending() = runBlocking {
        withTimeout(10_000) {
            val snapshot = stockSnapshot()
            assertFalse(snapshot.metadataEnriched)
            val configured = definition(snapshot)
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val requests = AtomicInteger()
            val registry = ServiceRegistry(ServiceDefinitions.builtIns, {
                requests.incrementAndGet()
                started.complete(Unit)
                release.await()
                ServiceManifestCodec.encode(ServiceDefinitions.builtIns + configured)
            }, MemoryStorage())
            assertEquals(0, requests.get())
            registry.beginReportingSession()
            val events = (1..8).map { async(Dispatchers.Default) { registry.discover(snapshot.packageName, snapshot.appLabel) } }
            try {
                started.await()
                val generic = registry.resolve(snapshot.packageName, snapshot.appLabel)
                assertEquals(snapshot.packageName, generic.id)
                assertEquals("Fixture series", prepareServiceSnapshot(snapshot, generic).title)
                assertEquals(2, presenceActivityType(snapshot, generic))
                assertEquals(1, requests.get())
            } finally {
                release.complete(Unit)
            }
            events.awaitAll()
            val resolved = registry.resolve(snapshot.packageName, snapshot.appLabel)
            val prepared = prepareServiceSnapshot(snapshot, resolved)
            assertEquals(configured, resolved)
            assertEquals("Fixture episode", prepared.title)
            assertEquals("Fixture series", prepared.artist)
            assertEquals(1, presenceActivityType(prepared, resolved))
            assertEquals(1, requests.get())
            repeat(12) { registry.discover(snapshot.packageName, snapshot.appLabel) }
            delay(150)
            assertEquals("Known playback and registry idle time must not trigger requests", 1, requests.get())
        }
    }

    @Test fun missingDefinitionWaitsForExplicitRefreshAndCachedIdentityRetainsSavedChoice() = runBlocking {
        withTimeout(10_000) {
            val snapshot = stockSnapshot()
            val configured = definition(snapshot)
            val storage = MemoryStorage()
            val requests = AtomicInteger()
            var publishDefinition = false
            val registry = ServiceRegistry(ServiceDefinitions.builtIns, {
                requests.incrementAndGet()
                ServiceManifestCodec.encode(ServiceDefinitions.builtIns + if (publishDefinition) listOf(configured) else emptyList())
            }, storage)
            val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
                "service-discovery-${UUID.randomUUID()}.preferences_pb")
            val jobs = mutableListOf<Job>()
            fun openPreferences(): SettingsStore {
                val job = SupervisorJob().also(jobs::add)
                return SettingsStore(PreferenceDataStoreFactory.create(
                    scope = CoroutineScope(Dispatchers.IO + job), produceFile = { file }))
            }
            try {
                registry.beginReportingSession()
                registry.discover(snapshot.packageName, snapshot.appLabel)
                val provisional = registry.resolve(snapshot.packageName, snapshot.appLabel)
                assertEquals(snapshot.packageName, provisional.id)
                val preferences = openPreferences()
                preferences.setServiceDisplayContent(provisional, PresenceContent.APP_NAME)
                repeat(12) { registry.discover(snapshot.packageName, snapshot.appLabel) }
                assertEquals("A missing definition is checked once per reporting run", 1, requests.get())
                delay(150)
                assertEquals(1, requests.get())

                publishDefinition = true
                registry.refresh()
                val resolved = registry.resolve(snapshot.packageName, snapshot.appLabel)
                assertEquals(configured.id, resolved.id)
                val prepared = prepareServiceSnapshot(snapshot, resolved)
                assertEquals(PresenceContent.APP_NAME,
                    compactPresence(prepared, preferences.displaySettings.first(), resolved).content)
                assertEquals(2, requests.get())
                jobs.last().cancelAndJoin()

                val reopenedPreferences = openPreferences()
                val reopeningRequests = AtomicInteger()
                val reopened = ServiceRegistry(ServiceDefinitions.builtIns, {
                    reopeningRequests.incrementAndGet()
                    error("A cached known service must not fetch")
                }, storage)
                reopened.beginReportingSession()
                reopened.discover(snapshot.packageName, snapshot.appLabel)
                assertEquals(configured, reopened.resolve(snapshot.packageName, snapshot.appLabel))
                assertEquals(PresenceContent.APP_NAME, reopenedPreferences.displaySettings.first().contentFor(configured))
                assertEquals(0, reopeningRequests.get())
            } finally {
                jobs.forEach { it.cancelAndJoin() }
                file.delete()
            }
        }
    }
}
