package dev.chudpresence.mobile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.res.Configuration
import android.os.Bundle
import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import dev.chudpresence.mobile.data.PresenceContent
import dev.chudpresence.mobile.data.PresenceDisplaySettings
import dev.chudpresence.mobile.data.PresenceService
import dev.chudpresence.mobile.media.MediaSnapshot
import dev.chudpresence.mobile.reporting.ReportingService
import dev.chudpresence.mobile.reporting.ReportingStatus
import dev.chudpresence.mobile.services.ServiceDefinitions
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import java.io.File

/** Exercises real Compose tree changes on a device without clearing connections or permissions. */
class SetupNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private var originalSettings: PresenceDisplaySettings? = null

    @Before fun openDashboard() {
        keepTestActivityAwake()
        val store = (compose.activity.application as ChudPresenceApplication).settingsStore
        runBlocking {
            originalSettings = store.displaySettings.first()
            store.completeSetup()
        }
        waitForText("CURRENT MEDIA")
    }

    @After fun restoreAppearance() {
        originalSettings?.let { original ->
            val store = (compose.activity.application as ChudPresenceApplication).settingsStore
            runBlocking { PresenceService.entries.forEach { store.setServiceDisplayContent(it, original.contentFor(it)) } }
        }
    }

    @Test fun replayAndFinishSetupRepeatedlyWithAllPreviewChoices() {
        repeat(3) {
            replaySetup()
            compose.onNodeWithText("Continue").performClick()
            waitForText("STEP 2 OF 3")
            continueEnrichment()
            waitForText("STEP 3 OF 3")
            compose.onNodeWithText("YouTube Music second line").assertDoesNotExist()

            listOf("app_name" to "YouTube Music", "track" to "The Pretender", "artist" to "Foo Fighters").forEach { (choice, example) ->
                compose.onNodeWithTag("preview-choice-$choice").performScrollTo().performClick()
                val description = "Discord preview. You, online. Listening to $example."
                compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithContentDescription(description).performScrollTo().assertIsDisplayed()
            }
            val store = (compose.activity.application as ChudPresenceApplication).settingsStore
            assertEquals(originalSettings, runBlocking { store.displaySettings.first() })
            compose.onNodeWithText("Finish setup").performClick()
            waitForText("CURRENT MEDIA")
        }
    }

    @Test fun serviceChoicesAreIndependentAndSurviveRecreation() {
        val store = (compose.activity.application as ChudPresenceApplication).settingsStore
        runBlocking { PresenceService.entries.forEach { store.setServiceDisplayContent(it, PresenceContent.ARTIST) } }
        compose.onNodeWithText("Settings").performClick()
        val choices = mapOf(PresenceService.YOUTUBE to PresenceContent.APP_NAME,
            PresenceService.YOUTUBE_MUSIC to PresenceContent.ARTIST,
            PresenceService.TWITCH to PresenceContent.TRACK,
            PresenceService.CRUNCHYROLL to PresenceContent.SEASON_EPISODE,
            PresenceService.HULU to PresenceContent.SEASON_EPISODE)
        choices.forEach { (service, choice) ->
            compose.onNodeWithTag("service-choice-${service.source}").performScrollTo().performClick()
            compose.onNodeWithTag("service-option-${service.source}-${choice.wireValue}").performClick()
            compose.waitUntil(10_000) {
                runBlocking { store.displaySettings.first().contentFor(service) == choice }
            }
        }
        recreateActivity()
        waitForText("App activities")
        choices.forEach { (service, choice) ->
            compose.onNodeWithTag("service-choice-${service.source}")
                .performScrollTo().assertTextContains(service.contentLabel(choice))
            assertEquals(choice, runBlocking { store.displaySettings.first().contentFor(service) })
        }
        compose.onNodeWithTag("preview-choice-track").performScrollTo().performClick()
        choices.forEach { (service, choice) ->
            assertEquals(choice, runBlocking { store.displaySettings.first().contentFor(service) })
        }
    }

    @Test fun setupSurvivesBackCloseAndActivityRecreation() {
        replaySetup()
        compose.onNodeWithText("Continue").performClick()
        waitForText("STEP 2 OF 3")
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        waitForText("STEP 1 OF 3")
        compose.onNodeWithText("Continue").performClick()
        waitForText("STEP 2 OF 3")
        continueEnrichment()
        waitForText("STEP 3 OF 3")
        recreateActivity()
        waitForText("STEP 3 OF 3")
        compose.onNodeWithText("Back").performClick()
        waitForText("STEP 2 OF 3")
        compose.onNodeWithText("Close").performClick()
        waitForText("Settings")
        compose.onNodeWithText("YouTube Music second line").assertDoesNotExist()
        compose.onNodeWithText("Done").performClick()
        waitForText("CURRENT MEDIA")
    }

    @Test fun settingsSectionsSurviveRecreationAndShortcutsOpenTheirSection() {
        captureScreen("dashboard")
        compose.onNodeWithText("Make your Discord activity feel like you.").assertDoesNotExist()
        compose.onNodeWithText("Connections").performScrollTo().performClick()
        waitForText("Twitch enrichment")
        compose.onNodeWithTag("settings-section-connections").assertIsSelected()
        val tabBounds = SettingsSection.entries.map { section ->
            compose.onNodeWithTag("settings-section-${section.name.lowercase()}").fetchSemanticsNode().boundsInRoot
        }
        compose.onNodeWithText("Preview example").assertDoesNotExist()
        captureScreen("connections")

        recreateActivity()
        waitForText("Twitch enrichment")
        compose.onNodeWithTag("settings-section-connections").assertIsSelected()
        compose.onNodeWithTag("settings-section-access").performClick()
        assertEquals(tabBounds, SettingsSection.entries.map { section ->
            compose.onNodeWithTag("settings-section-${section.name.lowercase()}").fetchSemanticsNode().boundsInRoot
        })
        assertLocalConnection()
        captureScreen("access")
        compose.onNodeWithText("Done").performClick()
        waitForText("CURRENT MEDIA")

        compose.onNodeWithText("Activities").performScrollTo().performClick()
        waitForText("Preview example")
        compose.onNodeWithTag("settings-section-activities").assertIsSelected()
        compose.onNodeWithText("Make your Discord activity feel like you.").assertDoesNotExist()
        captureScreen("activities")
        compose.onNodeWithTag("service-choice-youtube_music").performScrollTo().assertExists()
        captureScreen("services")
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        waitForText("CURRENT MEDIA")

        compose.onNodeWithText("Connection & access").performScrollTo().performClick()
        compose.onNodeWithTag("settings-section-access").assertIsSelected()
        assertLocalConnection()
    }

    @Test fun disneyDefinitionOffersIndependentAppearanceAndSurvivesRecreation() {
        val app = compose.activity.application as ChudPresenceApplication
        val definition = app.serviceRegistry.resolve("com.disney.disneyplus", "Disney+")
        val original = runBlocking { app.settingsStore.displaySettings.first().contentFor(definition) }
        compose.onNodeWithText("Settings").performClick()
        try {
            compose.onNodeWithTag("service-choice-disney_plus").performScrollTo().performClick()
            compose.onNodeWithTag("service-option-disney_plus-track").performClick()
            compose.waitUntil(10_000) {
                runBlocking { app.settingsStore.displaySettings.first().contentFor(definition) == PresenceContent.TRACK }
            }
            recreateActivity()
            waitForText("App activities")
            compose.onNodeWithTag("service-choice-disney_plus").performScrollTo().assertTextContains("Episode title")
            compose.onNodeWithTag("refresh-service-definitions").assertExists()
        } finally {
            runBlocking { app.settingsStore.setServiceDisplayContent(definition, original) }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("service-choice-disney_plus").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("service-choice-disney_plus").performScrollTo().assertTextContains(definition.contentLabel(original))
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val file = File(compose.activity.getExternalFilesDir(null), "generic-service-settings.png")
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("stream", "Generic service settings screenshot: ${file.absolutePath}\n")
        })
    }

    @Test fun removedServiceStaysHiddenAfterRecreationWithoutRestoreUi() {
        val app = compose.activity.application as ChudPresenceApplication
        assumeTrue("Hulu was already removed by the user", app.serviceRegistry.isEnabled("com.hulu.plus"))
        val original = runBlocking { app.settingsStore.displaySettings.first().contentFor(PresenceService.HULU) }
        compose.onNodeWithText("Settings").performClick()
        try {
            compose.onNodeWithTag("remove-service-hulu").performScrollTo().performClick()
            compose.waitUntil(10_000) { !app.serviceRegistry.isEnabled("com.hulu.plus") }
            compose.onNodeWithTag("service-choice-hulu").assertDoesNotExist()
            recreateActivity()
            waitForText("App activities")
            compose.onNodeWithTag("service-choice-hulu").assertDoesNotExist()
            compose.onNodeWithTag("show-removed-apps").assertDoesNotExist()
            compose.onNodeWithTag("restore-service-hulu").assertDoesNotExist()
            assertEquals(original, runBlocking { app.settingsStore.displaySettings.first().contentFor(PresenceService.HULU) })
        } finally {
            runBlocking { app.serviceRegistry.clearRemoval("hulu") }
        }
    }

    private fun replaySetup() {
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithTag("settings-section-access").performClick()
        assertLocalConnection()
        compose.onNodeWithText("Run setup again").performScrollTo().performClick()
        waitForText("STEP 1 OF 3")
        assertLocalConnection()
    }

    private fun assertLocalConnection() {
        waitForText("Discord app")
        listOf("Publishing method", "Server", "Local", "Private relay", "Scan pairing QR", "Pair manually").forEach { label ->
            compose.onNodeWithText(label).assertDoesNotExist()
        }
    }

    @Test fun activityCardShowsThePublishedFieldsProgressAndAvailableLinks() {
        val original = ReportingService.status.value
        assumeTrue("Keep normal reporting stopped for the isolated activity-card fixture", !original.running)
        val app = compose.activity.application as ChudPresenceApplication
        val music = ServiceDefinitions.builtIns.first { it.id == "youtube_music" }
        val media = MediaSnapshot("ui-fixture", "com.google.android.apps.youtube.music", "YouTube Music",
            "The Pretender", "Foo Fighters", "Echoes, Silence, Patience & Grace", "paused", 134_000, 269_000,
            BitmapFactory.decodeResource(compose.activity.resources, R.drawable.presence_brand), mediaId = "fixture0001")
        try {
            runBlocking { app.settingsStore.setServiceDisplayContent(PresenceService.YOUTUBE_MUSIC, PresenceContent.ARTIST) }
            ReportingService.status.value = ReportingStatus(running = true, source = music.name, track = media.title,
                artist = media.artist, album = media.album, publishingState = "Local • published through Discord",
                media = media, mediaDefinition = music)
            waitForText("The Pretender")
            compose.onNodeWithText("LISTENING TO YOUTUBE MUSIC").assertExists()
            compose.onNodeWithText("Foo Fighters • Paused").assertExists()
            compose.onNodeWithText("2:14").assertExists()
            compose.onNodeWithText("4:29").assertExists()
            compose.onNodeWithText("Play on YouTube Music").assertExists()
            compose.onNodeWithText("Search artist").assertExists()
            compose.onNodeWithTag("media-activity-card").performScrollTo()
            captureScreen("discord-activity", "media-activity-card")

            val crunchyroll = ServiceDefinitions.builtIns.first { it.id == "crunchyroll" }
            runBlocking { app.settingsStore.setServiceDisplayContent(PresenceService.CRUNCHYROLL, PresenceContent.SEASON_EPISODE) }
            ReportingService.status.value = ReportingService.status.value.copy(source = crunchyroll.name,
                media = media.copy(packageName = "com.crunchyroll.crunchyroid", appLabel = crunchyroll.name,
                    title = "Example episode", artist = "Example series", album = null, playbackState = "playing",
                    seasonNumber = 2, episodeNumber = "7", durationMs = null, positionMs = null, mediaId = null),
                track = "Example episode", artist = "Example series", album = null, mediaDefinition = crunchyroll)
            waitForText("Season 2 · Episode 7")
            compose.onNodeWithText("WATCHING CRUNCHYROLL").assertExists()
            compose.onNodeWithText("2:14").assertDoesNotExist()
            compose.onNodeWithText("Play on YouTube Music").assertDoesNotExist()
            compose.onNodeWithText("Search artist").assertDoesNotExist()
        } finally {
            ReportingService.status.value = original
        }
    }

    private fun continueEnrichment() {
        compose.onNode(hasClickAction() and (hasText("Continue") or hasText("Skip for now"))).performClick()
    }

    private fun waitForText(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text).assertExists()
    }

    private fun keepTestActivityAwake() = compose.runOnUiThread {
        compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun captureScreen(name: String, tag: String? = null) {
        val dark = compose.activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val file = File(compose.activity.getExternalFilesDir(null), "ui-polish/$name-${if (dark) "dark" else "light"}.png")
        file.parentFile?.mkdirs()
        val image = (if (tag == null) compose.onRoot() else compose.onNodeWithTag(tag)).captureToImage().asAndroidBitmap()
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun recreateActivity() {
        compose.activityRule.scenario.recreate()
        keepTestActivityAwake()
    }
}
