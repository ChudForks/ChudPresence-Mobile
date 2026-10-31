package dev.chudpresence.mobile

import androidx.test.platform.app.InstrumentationRegistry
import dev.chudpresence.mobile.services.GooglePlayAppLookup
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

class GooglePlayCategoryTest {
    /** Explicit opt-in smoke check of the actual Android HTTP client and Google's current listing. */
    @Test fun soundCloudPublicListingIsMusicAndAudio() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyPlayCategory") == "true")
        val info = GooglePlayAppLookup().fetch("com.soundcloud.android")
        val category = info?.category
        assertEquals("MUSIC_AND_AUDIO", category)
        assertEquals("music", GooglePlayAppLookup.mediaKind(category))
    }
}
