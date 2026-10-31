package dev.chudpresence.mobile

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.chudpresence.mobile.data.PresenceService
import dev.chudpresence.mobile.link.LinkConnection
import dev.chudpresence.mobile.link.LinkSyncManager
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.Instant

/** Opt-in check against the user's existing paired Link. Change YouTube Music on the
 * desktop, then restore it when the status says the changed value arrived. No credentials
 * are printed and this does not enable reporting or change pairing/service selections.
 */
@OptIn(FlowPreview::class)
class LinkSyncNotificationTest {
    @Test fun reconnectReportsUnchangedInventoryAgain() = runBlocking {
        assumeTrue("Enable only for a live Link reconnect check",
            InstrumentationRegistry.getArguments().getString("verifyLinkReconnect") == "true")
        assumeTrue("Keep normal reporting stopped for this lifecycle check", !dev.chudpresence.mobile.reporting.ReportingService.status.value.running)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as ChudPresenceApplication
        val snapshot = app.settingsStore.linkSnapshot.first()
        val pairing = snapshot.pairing
        assumeTrue("Pair this phone with Link first", pairing != null && !snapshot.sync.requiresPairing)
        var wasForeground = false
        instrumentation.runOnMainSync {
            wasForeground = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .any { it is MainActivity }
        }
        val sync = app.linkSync
        fun inventoryReportedAt() = sync.state.value.services.flatMap { it.participants }
            .filter { it.deviceId == pairing!!.deviceId }.maxOfOrNull { Instant.parse(it.reportedAt).toEpochMilli() }
        try {
            val started = System.currentTimeMillis()
            sync.setForegroundActive(true)
            withTimeout(25_000) {
                sync.state.first { it.connection == LinkConnection.CONNECTED && (it.lastSyncedAt ?: 0) >= started }
            }
            awaitQuiet(sync) // Remote adoption must settle before checking an unchanged inventory.
            val firstReport = inventoryReportedAt()
            assumeTrue("Install an app that this phone can report to Link", firstReport != null)

            sync.setForegroundActive(false)
            delay(1_000) // Allow cancellation to close the existing notification connection.
            val reconnected = System.currentTimeMillis()
            sync.setForegroundActive(true)
            withTimeout(25_000) {
                sync.state.first { it.connection == LinkConnection.CONNECTED && (it.lastSyncedAt ?: 0) >= reconnected }
            }
            assertTrue("The desktop must receive a fresh inventory even when settings did not change",
                (inventoryReportedAt() ?: 0) > firstReport!!)
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "LINK_RECONNECT_INVENTORY_OK: desktop report timestamp advanced after reconnect\n")
            })
        } finally { sync.setForegroundActive(wasForeground) }
    }

    @Test fun idleNotificationConnectionDoesNotPoll() = runBlocking {
        assumeTrue("Enable only for an interactive Link check",
            InstrumentationRegistry.getArguments().getString("verifyLinkIdle") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as ChudPresenceApplication
        val snapshot = app.settingsStore.linkSnapshot.first()
        assumeTrue("Pair this phone with Link first", snapshot.pairing != null && !snapshot.sync.requiresPairing)
        var wasForeground = false
        instrumentation.runOnMainSync {
            wasForeground = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .any { it is MainActivity }
        }
        val sync = app.linkSync
        val started = System.currentTimeMillis()
        sync.setForegroundActive(true)
        try {
            withTimeout(25_000) {
                sync.state.first { it.connection == LinkConnection.CONNECTED && (it.lastSyncedAt ?: 0) >= started }
            }
            awaitQuiet(sync)
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "LINK_PUSH_IDLE_OK: 25 continuous seconds without settings sync\n") })
        } finally { sync.setForegroundActive(wasForeground) }
    }

    @Test fun desktopChangesArriveAndIdleDoesNotPoll() = runBlocking {
        assumeTrue("Enable only for an interactive Link check",
            InstrumentationRegistry.getArguments().getString("verifyLinkPush") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as ChudPresenceApplication
        val snapshot = app.settingsStore.linkSnapshot.first()
        assumeTrue("Pair this phone with Link first", snapshot.pairing != null && !snapshot.sync.requiresPairing)
        var wasForeground = false
        instrumentation.runOnMainSync {
            wasForeground = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .any { it is MainActivity }
        }
        fun status(message: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$message\n") })
        val sync = app.linkSync
        val started = System.currentTimeMillis()
        sync.setForegroundActive(true)
        try {
            withTimeout(25_000) {
                sync.state.first { it.connection == LinkConnection.CONNECTED && (it.lastSyncedAt ?: 0) >= started }
            }
            assumeTrue("Enable YouTube Music sync first", "youtube-music" in sync.state.value.activeServices)
            val initial = app.settingsStore.displaySettings.first().contentFor(PresenceService.YOUTUBE_MUSIC)
            status("LINK_PUSH_READY initial=$initial; change YouTube Music on the desktop")
            val changed = withTimeout(120_000) {
                app.settingsStore.displaySettings.first { it.contentFor(PresenceService.YOUTUBE_MUSIC) != initial }
            }.contentFor(PresenceService.YOUTUBE_MUSIC)
            status("LINK_PUSH_RECEIVED value=$changed; restore the desktop choice to $initial")
            withTimeout(60_000) {
                app.settingsStore.displaySettings.first { it.contentFor(PresenceService.YOUTUBE_MUSIC) == initial }
            }
            status("LINK_PUSH_RESTORED; checking 25 seconds without settings polling")
            awaitQuiet(sync)
            status("LINK_PUSH_IDLE_OK")
        } finally {
            sync.setForegroundActive(wasForeground)
        }
    }

    private suspend fun awaitQuiet(sync: LinkSyncManager) {
        // Another participant can report its newly adopted inventory on its next pass.
        // Allow that real revision to settle, then require a continuous quiet period.
        // A periodic ten-second phone poll would never satisfy this condition.
        val quiet = withTimeout(90_000) {
            sync.state.map { it.connection to it.lastSyncedAt }.distinctUntilChanged().debounce(25_000).first()
        }
        assertEquals("Link must remain connected throughout its idle check", LinkConnection.CONNECTED, quiet.first)
    }
}
