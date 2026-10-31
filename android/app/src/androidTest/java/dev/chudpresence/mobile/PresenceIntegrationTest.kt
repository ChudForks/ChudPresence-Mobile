package dev.chudpresence.mobile

import android.content.ContextWrapper
import android.os.Bundle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.chudpresence.mobile.media.MediaSnapshot
import dev.chudpresence.mobile.media.SessionSelector
import dev.chudpresence.mobile.reporting.ReportingService
import dev.chudpresence.mobile.reporting.LocalDiscordPresence
import dev.chudpresence.mobile.data.PresenceDisplaySettings
import dev.chudpresence.mobile.reporting.prepareServiceSnapshot
import dev.chudpresence.mobile.services.ServiceRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit opt-in smoke checks; normal instrumentation runs do not access the network or publish. */
class PresenceIntegrationTest {
    @Test fun localRpcSurvivesActivityRecreationAndClosure() = runBlocking {
        assumeTrue("Pass publishPresence=true for the local Discord lifecycle check",
            InstrumentationRegistry.getArguments().getString("publishPresence") == "true")
        assumeTrue("Run while normal reporting is stopped", !ReportingService.status.value.running)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as ChudPresenceApplication
        var raw: MediaSnapshot? = null
        instrumentation.runOnMainSync {
            app.sessions.refresh()
            raw = SessionSelector.select(app.sessions.sessions.value.filter { app.serviceRegistry.isEnabled(it.packageName) }, null)
        }
        assumeTrue("Leave an enabled media session playing or paused", raw != null)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val presence = LocalDiscordPresence()
        try {
            val definition = app.serviceRegistry.resolve(raw!!.packageName, raw!!.appLabel)
            val snapshot = prepareServiceSnapshot(raw!!, definition)
            presence.publish(snapshot, PresenceDisplaySettings(), definition)
            scenario.recreate()
            presence.publish(snapshot, PresenceDisplaySettings(), definition)
            scenario.close()
            presence.publish(snapshot, PresenceDisplaySettings(), definition)
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "Real local RPC published before and after activity recreation and after closing the activity.\n")
            })
        } finally {
            presence.forceClear()
            presence.close()
            scenario.close()
        }
    }

    @Test fun publishedRegistryCanBeFetchedOnThePhone() = runBlocking {
        assumeTrue("Pass verifyRegistrySource=true for the live source check",
            InstrumentationRegistry.getArguments().getString("verifyRegistrySource") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.cacheDir, "registry-source-${UUID.randomUUID()}")
        check(directory.mkdirs())
        val isolatedContext = object : ContextWrapper(instrumentation.targetContext) {
            override fun getFilesDir(): File = directory
        }
        try {
            val registry = ServiceRegistry(isolatedContext)
            withTimeout(20_000) { registry.refresh() }
            assertFalse(registry.status.value.refreshing)
            assertNull(registry.status.value.error)
            assertEquals("disney_plus", registry.resolve("com.disney.disneyplus", "Disney+").id)
            assertTrue(File(directory, "presence-services-cache.json").isFile)
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "Published service registry fetched, validated and cached on the phone successfully.\n")
            })
        } finally {
            directory.listFiles()?.forEach { it.delete() }
            directory.delete()
        }
    }

    @Test fun selectedMediaPublishesThroughReportingServiceAndClears() = runBlocking {
        assumeTrue("Pass publishPresence=true for the local Discord smoke check",
            InstrumentationRegistry.getArguments().getString("publishPresence") == "true")
        assumeTrue("Run while normal reporting is stopped", !ReportingService.status.value.running)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as ChudPresenceApplication
        var raw: MediaSnapshot? = null
        instrumentation.runOnMainSync {
            app.sessions.refresh()
            raw = SessionSelector.select(app.sessions.sessions.value, null)
        }
        assumeTrue("Leave a media session playing or paused", raw != null)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            instrumentation.runOnMainSync {
                app.sessions.refresh()
                raw = SessionSelector.select(app.sessions.sessions.value, null) ?: raw
            }
            val actual = raw!!
            val definition = app.serviceRegistry.resolve(actual.packageName, actual.appLabel)
            scenario.onActivity { ReportingService.start(it) }
            withTimeout(30_000) {
                val published = ReportingService.status.first { it.running && it.publishingState == "Local • published through Discord" }
                assertEquals(definition.name, published.source)
                assertEquals(prepareServiceSnapshot(actual, definition).title, published.track)
                instrumentation.sendStatus(0, Bundle().apply {
                    putString("stream", "${definition.name} presence published successfully through ReportingService and the real local Discord RPC transport.\n")
                })
            }
        } finally {
            ReportingService.stop(app)
            withTimeout(15_000) { ReportingService.status.first { !it.running } }
            scenario.close()
        }
    }
}
