package dev.chudpresence.mobile

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import androidx.core.app.NotificationManagerCompat
import androidx.test.platform.app.InstrumentationRegistry
import dev.chudpresence.mobile.media.MediaNotificationListener
import dev.chudpresence.mobile.media.MediaSessionRepository
import dev.chudpresence.mobile.media.MediaSnapshot
import dev.chudpresence.mobile.data.PresenceContent
import dev.chudpresence.mobile.data.PresenceDisplaySettings
import dev.chudpresence.mobile.reporting.compactPresence
import dev.chudpresence.mobile.reporting.prepareServiceSnapshot
import dev.chudpresence.mobile.reporting.presenceActivityType
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Read-only inspection of real sessions plus inactive synthetic sessions. Never controls playback or publishes. */
class GenericMediaSessionTest {
    @Test fun activeDisneySessionExposesStockSubtitleAndTiming() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue("Grant ChudPresence media access", NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName))
        val manager = context.getSystemService(MediaSessionManager::class.java)
        val controller = manager.getActiveSessions(ComponentName(context, MediaNotificationListener::class.java))
            .firstOrNull { it.packageName == DISNEY_PACKAGE && it.playbackState?.state in
                setOf(PlaybackState.STATE_PLAYING, PlaybackState.STATE_PAUSED, PlaybackState.STATE_BUFFERING) }
        assumeTrue("Leave Disney+ playing for its live metadata check", controller != null)
        val actualController = controller!!
        var snapshot: MediaSnapshot? = null
        instrumentation.runOnMainSync {
            val repository = (context.applicationContext as ChudPresenceApplication).sessions
            assertTrue(repository.refresh())
            snapshot = repository.sessions.value.firstOrNull { it.packageName == DISNEY_PACKAGE }
        }
        val actual = snapshot
        assertNotNull("An unfamiliar stock video service must remain discoverable", actual)
        assertFalse(actual!!.isFallbackTitle)
        assertEquals(actualController.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE), actual.rawTitle)
        assertEquals(actualController.metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE), actual.displaySubtitle)
        assertEquals(actualController.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION), actual.durationMs)
        assertTrue(actual.durationMs != null && actual.durationMs!! > 0)
        assertNotNull("The tested Disney+ session publishes its episode in DISPLAY_SUBTITLE", actual.displaySubtitle)
        val app = context.applicationContext as ChudPresenceApplication
        val definition = app.serviceRegistry.resolve(actual.packageName, actual.appLabel)
        val prepared = prepareServiceSnapshot(actual, definition)
        assertEquals("disney_plus", definition.id)
        assertEquals(actual.displaySubtitle, prepared.title)
        assertEquals(actual.rawTitle, prepared.artist)
        assertEquals("Disney+ is a video service", 1, presenceActivityType(prepared, definition))
        val compact = compactPresence(prepared, PresenceDisplaySettings(), definition)
        assertEquals(PresenceContent.ARTIST, compact.content)
        assertEquals(actual.rawTitle, compact.presentation.state)
        instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "Disney+ standard metadata: title=${actual.rawTitle}, subtitle=${actual.displaySubtitle}, " +
                "positionMs=${actual.positionMs}, durationMs=${actual.durationMs}, state=${actual.playbackState}, " +
                "metadataKeys=${actualController.metadata?.keySet()}, sessionExtrasKeys=${actualController.extras?.keySet()}, " +
                "preparedTitle=${prepared.title}, preparedSeries=${prepared.artist}, activityType=${presenceActivityType(prepared, definition)}\n")
        })
    }

    @Test fun standardMetadataAndPatchFieldsUseOneGenericReader() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.runOnMainSync {
            val repository = MediaSessionRepository(context)
            val session = MediaSession(context, "Generic metadata contract fixture")
            try {
                session.setPlaybackState(PlaybackState.Builder()
                    .setState(PlaybackState.STATE_PLAYING, 10_000, 1f).build())
                session.setMetadata(MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, "Series")
                    .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, "S1:E1 Episode")
                    .putString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION, "Description")
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, 60_000)
                    .putString(MediaMetadata.METADATA_KEY_MEDIA_URI, "https://example.com/watch/item")
                    .build())
                val stock = repository.toSnapshot(session.controller)
                assertEquals("Series", stock.title)
                assertEquals("S1:E1 Episode", stock.displaySubtitle)
                assertEquals("Description", stock.displayDescription)
                assertEquals(60_000L, stock.durationMs)
                assertEquals("https://example.com/watch/item", stock.contentUrl)
                assertFalse(stock.metadataEnriched)

                // This package is ChudPresence itself, proving custom fields have no Hulu/package gate.
                session.setMetadata(MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, "Corrected episode title")
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, "Corrected series")
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, 120_000)
                    .putLong(PREFIX + "VERSION", 1)
                    .putLong(PREFIX + "SEASON_NUMBER", 2)
                    .putString(PREFIX + "EPISODE_NUMBER", "3")
                    .putString(PREFIX + "CONTENT_TYPE", "episode")
                    .putString(PREFIX + "MEDIA_KIND", "video")
                    .putString(PREFIX + "CONTENT_URL", "https://example.com/watch/episode")
                    .build())
                val patched = repository.toSnapshot(session.controller)
                assertTrue(patched.metadataEnriched)
                assertEquals("Corrected episode title", patched.title)
                assertEquals("Corrected series", patched.artist)
                assertEquals(120_000L, patched.durationMs)
                assertEquals(2, patched.seasonNumber)
                assertEquals("3", patched.episodeNumber)
                assertEquals("episode", patched.contentType)
                assertEquals("video", patched.mediaKind)
                assertEquals("https://example.com/watch/episode", patched.contentUrl)

                session.setMetadata(MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, "Movie")
                    .putLong(PREFIX + "VERSION", 1).build())
                val movie = repository.toSnapshot(session.controller)
                assertEquals("Movie", movie.title)
                assertNull(movie.artist)
                assertNull(movie.durationMs)
                assertNull(movie.seasonNumber)
                assertNull(movie.episodeNumber)
                assertNull(movie.contentUrl)

                session.setMetadata(MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, "Future standard title")
                    .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, "standard-id")
                    .putLong(PREFIX + "VERSION", 2)
                    .putString(PREFIX + "VIDEO_ID", "future-custom-id")
                    .putString(PREFIX + "CONTENT_URL", "https://example.com/future")
                    .putLong(PREFIX + "SEASON_NUMBER", 9).build())
                val future = repository.toSnapshot(session.controller)
                assertEquals("Future standard title", future.title)
                assertEquals("standard-id", future.mediaId)
                assertEquals(2L, future.metadataVersion)
                assertFalse(future.metadataEnriched)
                assertNull(future.contentUrl)
                assertNull(future.seasonNumber)

                session.setMetadata(null)
                val untitled = repository.toSnapshot(session.controller)
                assertTrue(untitled.isFallbackTitle)
                assertTrue(untitled.title.isNotBlank())
                assertEquals(context.packageName, untitled.packageName)
                assertNull(untitled.rawTitle)
                assertNull(untitled.displaySubtitle)
            } finally {
                session.release()
            }
        }
    }

    private companion object {
        const val DISNEY_PACKAGE = "com.disney.disneyplus"
        const val PREFIX = "dev.chudpresence.metadata."
    }
}
