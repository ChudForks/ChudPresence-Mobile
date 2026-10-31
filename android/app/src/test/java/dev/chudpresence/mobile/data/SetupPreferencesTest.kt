package dev.chudpresence.mobile.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import dev.chudpresence.mobile.services.ServiceDefinition

class SetupPreferencesTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val jobs = mutableListOf<Job>()

    private fun open(file: File): SettingsStore {
        val job = SupervisorJob().also(jobs::add)
        return SettingsStore(PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + job), produceFile = { file }
        ))
    }

    @After fun closeStores() = runBlocking { jobs.forEach { it.cancelAndJoin() } }

    @Test fun `legacy global and album choices do not override per service defaults`() = runBlocking {
        withTimeout(5_000) {
            val job = SupervisorJob().also(jobs::add)
            val preferences = PreferenceDataStoreFactory.create(
                scope = CoroutineScope(Dispatchers.IO + job),
                produceFile = { File(temporaryFolder.root, "legacy.preferences_pb") }
            )
            preferences.edit {
                it[stringPreferencesKey("music_second_line")] = "album"
                it[stringPreferencesKey("presence_name_content")] = "app_name"
            }
            val store = SettingsStore(preferences)
            val settings = store.displaySettings.first()
            PresenceService.entries.forEach {
                assertEquals(if (it == PresenceService.HULU) PresenceContent.APP_NAME else PresenceContent.ARTIST, settings.contentFor(it))
            }
            assertFalse(store.setupCompleted.first())
        }
    }

    @Test fun `new installation requires setup and editing preferences does not complete it`() = runBlocking {
        withTimeout(5_000) {
            val store = open(File(temporaryFolder.root, "new.preferences_pb"))
            assertFalse(store.setupCompleted.first())
            PresenceService.entries.forEach {
                assertEquals(if (it == PresenceService.HULU) PresenceContent.APP_NAME else PresenceContent.ARTIST,
                    store.displaySettings.first().contentFor(it))
            }
            store.setServiceDisplayContent(PresenceService.YOUTUBE, PresenceContent.APP_NAME)
            assertFalse(store.setupCompleted.first())
            assertEquals(PresenceContent.APP_NAME, store.displaySettings.first().contentFor(PresenceService.YOUTUBE))
            assertEquals(PresenceContent.ARTIST, store.displaySettings.first().contentFor(PresenceService.YOUTUBE_MUSIC))
        }
    }

    @Test fun `completion and choices survive reopening storage without losing prior settings`() = runBlocking {
        withTimeout(5_000) {
            val file = File(temporaryFolder.root, "saved.preferences_pb")
            val store = open(file)
            val choices = mapOf(PresenceService.YOUTUBE to PresenceContent.APP_NAME,
                PresenceService.YOUTUBE_MUSIC to PresenceContent.ARTIST,
                PresenceService.TWITCH to PresenceContent.TRACK,
                PresenceService.CRUNCHYROLL to PresenceContent.SEASON_EPISODE,
                PresenceService.HULU to PresenceContent.SEASON_EPISODE)
            choices.forEach { (service, choice) -> store.setServiceDisplayContent(service, choice) }
            store.completeSetup()
            jobs.last().cancelAndJoin()

            val reopened = open(file)
            assertTrue(reopened.setupCompleted.first())
            assertEquals(PresenceDisplaySettings(choices), reopened.displaySettings.first())

            reopened.setServiceDisplayContent(PresenceService.TWITCH, PresenceContent.APP_NAME)
            reopened.completeSetup()
            assertTrue(reopened.setupCompleted.first())
            assertEquals(PresenceDisplaySettings(choices + (PresenceService.TWITCH to PresenceContent.APP_NAME)), reopened.displaySettings.first())
        }
    }

    @Test fun `invalid saved choices use artist defaults without affecting other services`() = runBlocking {
        withTimeout(5_000) {
            val job = SupervisorJob().also(jobs::add)
            val preferences = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job),
                produceFile = { File(temporaryFolder.root, "invalid.preferences_pb") })
            preferences.edit {
                it[stringPreferencesKey("presence_name_content_youtube")] = "season_episode"
                it[stringPreferencesKey("presence_name_content_youtube_music")] = "future_value"
                it[stringPreferencesKey("presence_name_content_twitch")] = "track"
            }
            val settings = SettingsStore(preferences).displaySettings.first()
            assertEquals(PresenceContent.ARTIST, settings.contentFor(PresenceService.YOUTUBE))
            assertEquals(PresenceContent.ARTIST, settings.contentFor(PresenceService.YOUTUBE_MUSIC))
            assertEquals(PresenceContent.TRACK, settings.contentFor(PresenceService.TWITCH))
            assertEquals(PresenceContent.ARTIST, settings.contentFor(PresenceService.CRUNCHYROLL))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `cannot save a Crunchyroll choice for YouTube`() = runBlocking {
        open(File(temporaryFolder.root, "unsupported.preferences_pb"))
            .setServiceDisplayContent(PresenceService.YOUTUBE, PresenceContent.SEASON_EPISODE)
        Unit
    }

    @Test fun `discovered service choices persist and survive a registry identity becoming available`() = runBlocking {
        withTimeout(5_000) {
            val file = File(temporaryFolder.root, "discovered.preferences_pb")
            val provisional = ServiceDefinition("example.video", "Example Video", setOf("example.video"),
                defaultContent = PresenceContent.TRACK, supportsEpisodes = true)
            val store = open(file)
            store.setServiceDisplayContent(PresenceService.YOUTUBE, PresenceContent.APP_NAME)
            store.setServiceDisplayContent(provisional, PresenceContent.SEASON_EPISODE)
            jobs.last().cancelAndJoin()
            val reopened = open(file)
            val configured = provisional.copy(id = "example_video", defaultContent = PresenceContent.ARTIST,
                supportsEpisodes = false)
            val settings = reopened.displaySettings.first()
            assertEquals(PresenceContent.APP_NAME, settings.contentFor(PresenceService.YOUTUBE))
            assertEquals(PresenceContent.SEASON_EPISODE, settings.contentFor(configured))
            assertTrue(settings.hasAdditionalEpisodeChoice(configured))
            reopened.setServiceDisplayContent(configured.copy(supportsEpisodes = true), PresenceContent.TRACK)
            assertEquals(PresenceContent.TRACK, reopened.displaySettings.first().contentFor(configured))
        }
    }

    @Test fun `definitions can extend legacy service options without losing saved episode choice`() = runBlocking {
        withTimeout(5_000) {
            val store = open(File(temporaryFolder.root, "extended.preferences_pb"))
            val extended = ServiceDefinition("youtube", "YouTube", setOf("com.google.android.youtube"), supportsEpisodes = true)
            store.setServiceDisplayContent(extended, PresenceContent.SEASON_EPISODE)
            val settings = store.displaySettings.first()
            assertEquals(PresenceContent.SEASON_EPISODE, settings.contentFor(extended))
            assertEquals(PresenceContent.SEASON_EPISODE, settings.contentFor(extended.copy(supportsEpisodes = false)))
            assertTrue(settings.hasAdditionalEpisodeChoice(extended))
            assertEquals(PresenceContent.ARTIST, settings.contentFor(PresenceService.YOUTUBE))
        }
    }

    @Test fun `activity controls default on and persist independently across services and restarts`() = runBlocking {
        withTimeout(5_000) {
            val file = File(temporaryFolder.root, "activity-controls.preferences_pb")
            val youtube = ServiceDefinition("youtube", "YouTube", setOf("com.google.android.youtube"))
            val generic = ServiceDefinition("example.player", "Example", setOf("example.player"))
            val store = open(file)
            assertEquals(ServiceActivityOptions(), store.displaySettings.first().optionsFor(youtube))
            assertEquals(ServiceActivityOptions(), store.displaySettings.first().optionsFor(generic))
            store.setServiceDisplayContent(PresenceService.YOUTUBE, PresenceContent.TRACK)
            ServiceActivityOption.entries.forEach { store.setServiceActivityOption(youtube, it, false) }
            store.setServiceActivityOption(generic, ServiceActivityOption.ARTWORK, false)
            jobs.last().cancelAndJoin()
            val reopened = open(file)
            val settings = reopened.displaySettings.first()
            assertEquals(ServiceActivityOptions(false, false, false, false, false), settings.optionsFor(youtube))
            assertEquals(ServiceActivityOptions(showArtwork = false), settings.optionsFor(generic))
            assertEquals(PresenceContent.TRACK, settings.contentFor(PresenceService.YOUTUBE))
            reopened.setServiceActivityOption(youtube, ServiceActivityOption.ENABLED, true)
            assertEquals(ServiceActivityOptions(true, false, false, false, false), reopened.displaySettings.first().optionsFor(youtube))
        }
    }

    @Test fun `activity controls follow a package into a definition and preserve inherited choices when edited`() = runBlocking {
        withTimeout(5_000) {
            val store = open(File(temporaryFolder.root, "activity-identity.preferences_pb"))
            val generic = ServiceDefinition("example.player", "Example", setOf("example.player"))
            store.setServiceActivityOption(generic, ServiceActivityOption.ENABLED, false)
            store.setServiceActivityOption(generic, ServiceActivityOption.ARTWORK, false)
            val defined = generic.copy(id = "example")
            assertEquals(ServiceActivityOptions(enabled = false, showArtwork = false), store.displaySettings.first().optionsFor(defined))
            store.setServiceActivityOption(defined, ServiceActivityOption.ENABLED, true)
            assertEquals(ServiceActivityOptions(showArtwork = false), store.displaySettings.first().optionsFor(defined))
        }
    }

    @Test fun `concurrent control changes retain every edit`() = runBlocking {
        withTimeout(5_000) {
            val store = open(File(temporaryFolder.root, "activity-concurrent.preferences_pb"))
            val service = ServiceDefinition("example.player", "Example", setOf("example.player"))
            coroutineScope {
                ServiceActivityOption.entries.forEach { option ->
                    launch { store.setServiceActivityOption(service, option, false) }
                }
            }
            assertEquals(ServiceActivityOptions(false, false, false, false, false), store.displaySettings.first().optionsFor(service))
        }
    }
}
