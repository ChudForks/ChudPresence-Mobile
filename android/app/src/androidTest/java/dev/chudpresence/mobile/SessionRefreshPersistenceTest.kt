package dev.chudpresence.mobile

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.platform.app.InstrumentationRegistry
import dev.chudpresence.mobile.data.CrunchyrollSession
import dev.chudpresence.mobile.data.SettingsStore
import dev.chudpresence.mobile.data.TwitchSession
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Uses real Android Keystore encryption with disposable preferences, never saved accounts. */
class SessionRefreshPersistenceTest {
    private val crunchyroll = CrunchyrollSession("fixture-crunchyroll-original", "fixture-device")
    private val rotatedCrunchyroll = crunchyroll.copy(refreshToken = "fixture-crunchyroll-rotated")
    private val twitch = TwitchSession("fixture-access-original", "fixture-refresh-original", 100)
    private val rotatedTwitch = TwitchSession("fixture-access-rotated", "fixture-refresh-rotated", 200)

    private suspend fun withStore(check: suspend (SettingsStore, File) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "refresh-fixture-${UUID.randomUUID()}.preferences_pb")
        val job = SupervisorJob()
        val store = SettingsStore(PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + job), produceFile = { file }))
        try {
            withTimeout(15_000) { check(store, file) }
        } finally {
            job.cancelAndJoin()
            file.delete()
        }
    }

    @Test fun refreshUpdatesTheMatchingSessionAndKeepsTokensEncrypted() = runBlocking {
        withStore { store, file ->
            store.saveCrunchyrollSession(crunchyroll)
            store.saveTwitchSession(twitch)
            store.saveRefreshedCrunchyrollSession(crunchyroll, rotatedCrunchyroll)
            store.saveRefreshedTwitchSession(twitch, rotatedTwitch)
            assertEquals(rotatedCrunchyroll, store.crunchyrollSession.first())
            assertEquals(rotatedTwitch, store.twitchSession.first())
            val contents = file.readBytes().toString(Charsets.ISO_8859_1)
            assertFalse(contents.contains(rotatedCrunchyroll.refreshToken))
            assertFalse(contents.contains(rotatedTwitch.accessToken))
            assertFalse(contents.contains(rotatedTwitch.refreshToken))
        }
    }

    @Test fun aLateRefreshCannotRestoreDisconnectedAccounts() = runBlocking {
        withStore { store, _ ->
            store.saveCrunchyrollSession(crunchyroll)
            store.saveTwitchSession(twitch)
            store.clearCrunchyrollSession()
            store.clearTwitchSession()
            store.saveRefreshedCrunchyrollSession(crunchyroll, rotatedCrunchyroll)
            store.saveRefreshedTwitchSession(twitch, rotatedTwitch)
            assertNull(store.crunchyrollSession.first())
            assertNull(store.twitchSession.first())
        }
    }

    @Test fun aLateRefreshCannotOverwriteANewerLoginOrRotation() = runBlocking {
        withStore { store, _ ->
            val newCrunchyroll = CrunchyrollSession("fixture-new-login", "fixture-new-device")
            val newTwitch = TwitchSession("fixture-new-access", "fixture-new-refresh", 300)
            store.saveCrunchyrollSession(newCrunchyroll)
            store.saveTwitchSession(newTwitch)
            store.saveRefreshedCrunchyrollSession(crunchyroll, rotatedCrunchyroll)
            store.saveRefreshedTwitchSession(twitch, rotatedTwitch)
            assertEquals(newCrunchyroll, store.crunchyrollSession.first())
            assertEquals(newTwitch, store.twitchSession.first())

            store.saveCrunchyrollSession(crunchyroll)
            store.saveTwitchSession(twitch)
            store.saveRefreshedCrunchyrollSession(crunchyroll, rotatedCrunchyroll)
            store.saveRefreshedTwitchSession(twitch, rotatedTwitch)
            store.saveRefreshedCrunchyrollSession(crunchyroll, newCrunchyroll)
            store.saveRefreshedTwitchSession(twitch, newTwitch)
            assertEquals(rotatedCrunchyroll, store.crunchyrollSession.first())
            assertEquals(rotatedTwitch, store.twitchSession.first())
        }
    }

    @Test fun disconnectWinsAgainstConcurrentRefresh() = runBlocking {
        withStore { store, _ ->
            repeat(6) {
                store.saveCrunchyrollSession(crunchyroll)
                store.saveTwitchSession(twitch)
                coroutineScope {
                    launch(Dispatchers.IO) {
                        store.saveRefreshedCrunchyrollSession(crunchyroll, rotatedCrunchyroll)
                        store.saveRefreshedTwitchSession(twitch, rotatedTwitch)
                    }
                    launch(Dispatchers.IO) {
                        store.clearCrunchyrollSession()
                        store.clearTwitchSession()
                    }
                }
                assertNull(store.crunchyrollSession.first())
                assertNull(store.twitchSession.first())
            }
        }
    }
}
