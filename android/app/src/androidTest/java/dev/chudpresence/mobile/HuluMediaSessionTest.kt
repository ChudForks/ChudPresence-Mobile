package dev.chudpresence.mobile

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import dalvik.system.DexClassLoader
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import dev.chudpresence.mobile.media.HULU_PACKAGE
import dev.chudpresence.mobile.media.MediaNotificationListener
import dev.chudpresence.mobile.media.MediaSnapshot
import dev.chudpresence.mobile.media.withHuluEpisodeMetadata
import dev.chudpresence.mobile.data.PresenceContent
import dev.chudpresence.mobile.data.PresenceDisplaySettings
import dev.chudpresence.mobile.data.PresenceService
import dev.chudpresence.mobile.reporting.classifyMediaSource
import dev.chudpresence.mobile.reporting.compactPresence
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in device check: leave Hulu playing and grant ChudPresence notification access first.
 * Run directly with adb instrumentation to preserve the installed app's data and connections.
 * Does not launch an activity, control playback, or start presence reporting.
 */
class HuluMediaSessionTest {
    @Test fun activeHuluSessionIsDetectedWithAvailableMetadata() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager = context.getSystemService(MediaSessionManager::class.java)
        val controllers = manager.getActiveSessions(ComponentName(context, MediaNotificationListener::class.java))
        val hulu = controllers.firstOrNull { it.packageName == HULU_PACKAGE &&
            it.playbackState?.state in setOf(PlaybackState.STATE_PLAYING, PlaybackState.STATE_PAUSED, PlaybackState.STATE_BUFFERING) }
        assumeTrue("Play Hulu on a device with ChudPresence notification access", hulu != null)
        val controller = hulu!!
        instrumentation.sendStatus(0, Bundle().apply {
            // Report keys only for extras; their values may contain private application data.
            putString("stream", "Hulu session: metadataKeys=${controller.metadata?.keySet()}, " +
                "sessionExtrasKeys=${controller.extras?.keySet()}, " +
                "playbackExtrasKeys=${controller.playbackState?.extras?.keySet()}, " +
                "queueItems=${controller.queue?.size ?: 0}, state=${controller.playbackState?.state}\n")
        })
        var snapshot: MediaSnapshot? = null
        instrumentation.runOnMainSync {
            val repository = (context.applicationContext as ChudPresenceApplication).sessions
            assertTrue(repository.refresh())
            snapshot = repository.sessions.value.firstOrNull { it.packageName == HULU_PACKAGE }
        }
        assertNotNull("Hulu must survive null metadata instead of being discarded", snapshot)
        val actual = snapshot!!
        assertEquals("hulu", classifyMediaSource(actual.packageName, actual.appLabel))
        assertTrue(actual.title.isNotBlank())
        assertTrue(actual.playbackState in setOf("playing", "paused"))
        val metadata = controller.metadata
        if ((metadata?.getLong(PREFIX + "VERSION") ?: 0) >= 1) {
            assertTrue(actual.metadataEnriched)
            assertEquals(metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST), actual.artist)
            assertEquals(metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 }, actual.durationMs)
            assertEquals(metadata?.getString(PREFIX + "EPISODE_NUMBER"), actual.episodeNumber)
            assertEquals(metadata?.takeIf { it.containsKey(PREFIX + "SEASON_NUMBER") }
                ?.getLong(PREFIX + "SEASON_NUMBER")?.toInt(), actual.seasonNumber)
        }
        instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "Detected Hulu: state=${actual.playbackState}, title=${actual.title}, " +
                "positionMs=${actual.positionMs}, durationMs=${actual.durationMs}, " +
                "mediaId=${actual.mediaId}, hasArtwork=${actual.artwork != null || actual.artworkUrl != null}, " +
                "enhanced=${actual.metadataEnriched}, series=${actual.artist}, season=${actual.seasonNumber}, " +
                "episode=${actual.episodeNumber}\n")
        })
    }

    @Test fun patchedMetadataContractSupportsSeasonEpisodeAndMovieTransitions() {
        val episode = MediaMetadata.Builder()
            .putLong(PREFIX + "VERSION", 1)
            .putLong(PREFIX + "SEASON_NUMBER", 0)
            .putString(PREFIX + "EPISODE_NUMBER", "3")
            .build()
        val raw = MediaSnapshot("fixture", HULU_PACKAGE, "Hulu", "Episode title", "Series", null,
            "playing", 1_000, 60_000, null, metadataEnriched = true,
            contentUrl = "https://www.hulu.com/watch/episode-id")
        val enhanced = raw.withHuluEpisodeMetadata(episode)
        assertEquals(0, enhanced.seasonNumber)
        assertEquals("3", enhanced.episodeNumber)
        val compact = compactPresence(enhanced, PresenceDisplaySettings(
            mapOf(PresenceService.HULU to PresenceContent.SEASON_EPISODE)))
        assertEquals("Season 0 · Episode 3", compact.presentation.state)
        assertEquals("https://www.hulu.com/watch/episode-id", compact.presentation.stateUrl)
        val movie = enhanced.withHuluEpisodeMetadata(MediaMetadata.Builder().putLong(PREFIX + "VERSION", 1).build())
        assertNull(movie.seasonNumber)
        assertNull(movie.episodeNumber)
    }

    /** Loads the actual packaged extension, including D8's generated helper dependencies. */
    @Test fun packagedHuluExtensionPublishesRealAndroidMetadata() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File(context.getExternalFilesDir(null), "hulu-patch-test.dex")
        val expectedHash = InstrumentationRegistry.getArguments().getString("huluDexSha256")
        assumeTrue("Provide the packaged Hulu extension and its SHA-256 for this check",
            source.isFile && expectedHash?.matches(Regex("[A-Fa-f0-9]{64}")) == true)
        val bytes = source.readBytes()
        val actualHash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals(expectedHash!!.lowercase(), actualHash)
        val dex = File.createTempFile("hulu-extension-", ".dex", context.codeCacheDir)
        val session = MediaSession(context, "Hulu extension contract test")
        try {
            FileOutputStream(dex).use { output ->
                // Android 14+ requires read-only dynamic code before its contents are written.
                assertTrue(dex.setReadOnly())
                output.write(bytes)
            }
            val loader = DexClassLoader(dex.absolutePath, context.codeCacheDir.absolutePath, null, javaClass.classLoader)
            val publish = loader.loadClass("dev.chudpresence.revanced.HuluMetadataBridge")
                .getMethod("publish", MediaSession::class.java, Any::class.java)
            publish.invoke(null, session, PatchEpisode())
            val metadata = session.controller.metadata
            assertNotNull("The packaged bridge must actually publish, including all generated helpers", metadata)
            assertEquals("Fixture episode", metadata!!.getString(MediaMetadata.METADATA_KEY_TITLE))
            assertEquals("Fixture series", metadata.getString(MediaMetadata.METADATA_KEY_ARTIST))
            assertEquals(120_000L, metadata.getLong(MediaMetadata.METADATA_KEY_DURATION))
            assertEquals(1L, metadata.getLong(PREFIX + "VERSION"))
            assertEquals(2L, metadata.getLong(PREFIX + "SEASON_NUMBER"))
            assertEquals("3", metadata.getString(PREFIX + "EPISODE_NUMBER"))
            val observed = MediaSnapshot("runtime", HULU_PACKAGE, "Hulu",
                metadata.getString(MediaMetadata.METADATA_KEY_TITLE)!!,
                metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
                metadata.getString(MediaMetadata.METADATA_KEY_ALBUM), "playing", 0,
                metadata.getLong(MediaMetadata.METADATA_KEY_DURATION), null,
                metadataEnriched = metadata.getLong(PREFIX + "VERSION") >= 1)
                .withHuluEpisodeMetadata(metadata)
            assertEquals(2, observed.seasonNumber)
            assertEquals("3", observed.episodeNumber)
            publish.invoke(null, session, null)
            assertNull(session.controller.metadata)
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "Packaged Hulu extension published title, series, duration and season/episode through MediaController successfully.\n")
            })
        } finally {
            session.release()
            dex.delete()
        }
    }

    class PatchEpisode {
        fun getName() = "Fixture episode"
        fun getSeriesName() = "Fixture series"
        fun getDurationSeconds() = 120
        fun getSeasonNumber() = 2
        fun getEpisodeNumber() = 3
        fun getContentId() = "12345678-1234-5678-9abc-123456789abc"
        fun isLiveContent() = false
    }

    private companion object { const val PREFIX = "dev.chudpresence.metadata." }
}
