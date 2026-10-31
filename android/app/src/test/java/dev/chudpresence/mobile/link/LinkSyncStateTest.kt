package dev.chudpresence.mobile.link

import dev.chudpresence.mobile.data.PresenceContent
import dev.chudpresence.mobile.data.PresenceDisplaySettings
import dev.chudpresence.mobile.data.PresenceService
import dev.chudpresence.mobile.data.ServiceActivityOptions
import dev.chudpresence.mobile.services.ServiceDefinition
import dev.chudpresence.mobile.services.ServiceDefinitions
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LinkSyncStateTest {
    private val fields = LinkField.entries.toSet()
    private val initialValues = readLinkValues(Json.parseToJsonElement(
        """{"display":"creator","enabled":true,"showWhilePaused":true,"artwork":true,"playbackProgress":true,"actionButtons":true}"""
    ).jsonObject)
    private fun state(ids: Set<String> = setOf("youtube"), selected: Set<LinkField> = fields, version: Long = 7) =
        LinkStoredState(enabled = ids, baselines = ids.associateWith { LinkPreference(initialValues, version) },
            syncFields = ids.associateWith { selected }, syncVersions = ids.associateWith { 3L })

    private fun merge(current: LinkStoredState, expected: LinkStoredState = current,
        sent: Map<String, LinkEdit> = emptyMap(), values: Map<LinkField, JsonPrimitive> = initialValues,
        version: Long = 8, conflicts: Set<String> = emptySet(),
        supported: Set<LinkField> = fields): LinkSettingsMerge = mergeLinkSettings(current, expected, sent,
        LinkSettingsSnapshot(mapOf("youtube" to LinkPreference(values, version)), conflicts), setOf("youtube"),
        mapOf("youtube" to (initialValues + current.pending["youtube"]?.fields.orEmpty().mapValues { it.value.value }).filterKeys { it in supported }),
        mapOf("youtube" to listOf("app", "creator", "title")))

    @Test fun `disabled services report all supported false values and normalized identity`() {
        val service = ServiceDefinitions.builtIns.single { it.id == "youtube_music" }
        val settings = PresenceDisplaySettings(activityOptions = mapOf(service.id to ServiceActivityOptions(false, false, false, false, false)))
        val report = LinkInventoryItem(service, settings.linkValues(service)).json()
        assertEquals("youtube-music", report.getValue("id").jsonPrimitive.content)
        LinkField.entries.filter { it != LinkField.DISPLAY }.forEach { assertFalse(report.getValue(it.wireName).jsonPrimitive.boolean) }
        assertEquals("creator", report.getValue("display").jsonPrimitive.content)
    }

    @Test fun `episode display stays local without removing boolean capabilities`() {
        val service = ServiceDefinitions.builtIns.single { it.id == "hulu" }
        val settings = PresenceDisplaySettings(services = mapOf(PresenceService.HULU to PresenceContent.SEASON_EPISODE))
        val report = LinkInventoryItem(service, settings.linkValues(service)).json()
        assertFalse(report.containsKey("display"))
        assertFalse(report.containsKey("displayChoices"))
        assertTrue(report.getValue("artwork").jsonPrimitive.boolean)
        assertTrue(report.getValue("enabled").jsonPrimitive.boolean)
    }

    @Test fun `generic apps do not advertise unavailable action buttons`() {
        val service = ServiceDefinition("example.player", "Example", setOf("example.player"))
        assertFalse(LinkField.ACTION_BUTTONS in PresenceDisplaySettings().linkValues(service))
        assertTrue(LinkField.PLAYBACK_PROGRESS in PresenceDisplaySettings().linkValues(service))
    }

    @Test fun `legacy state migrates without broadening sync or rebasing offline edits`() {
        val legacy = """{"enabled":["youtube"],"baselines":{"youtube":{"display":"creator","version":6}},
            "pending":{"youtube":{"display":"title","baseVersion":6,"editId":"tap"}},"localEdits":{"youtube":"tap"}}"""
        val decoded = LinkStateCodec.decode(legacy)
        val discovered = decoded.withDiscovery(mapOf("youtube" to LinkSelection(linkDisplayFields, 4)))
        assertEquals(linkDisplayFields, discovered.fieldsFor("youtube"))
        assertEquals(6L, discovered.pending.getValue("youtube").baseVersion)
        assertEquals("title", discovered.pending.getValue("youtube").fields.getValue(LinkField.DISPLAY).value.content)
        assertEquals("tap", discovered.localEdits.getValue("youtube")[LinkField.DISPLAY])
        assertEquals(discovered, LinkStateCodec.decode(LinkStateCodec.encode(discovered)))
    }

    @Test fun `multifield edits selections and field generations persist across restart`() {
        val edited = state().recordEdit("youtube", LinkField.ARTWORK, JsonPrimitive(false))
            .recordEdit("youtube", LinkField.ENABLED, JsonPrimitive(false))
        assertEquals(edited, LinkStateCodec.decode(LinkStateCodec.encode(edited)))
        assertEquals(setOf(LinkField.ARTWORK, LinkField.ENABLED), edited.pending.getValue("youtube").fields.keys)
    }

    @Test fun `only selected local fields queue and multiple offline edits keep original service version`() {
        val edited = state(selected = setOf(LinkField.ARTWORK)).recordEdit("youtube", LinkField.ARTWORK, JsonPrimitive(false))
        val later = edited.copy(baselines = mapOf("youtube" to LinkPreference(initialValues, 12)))
            .recordEdit("youtube", LinkField.ENABLED, JsonPrimitive(false))
            .recordEdit("youtube", LinkField.ARTWORK, JsonPrimitive(true))
        assertEquals(setOf(LinkField.ARTWORK), later.pending.getValue("youtube").fields.keys)
        assertEquals(7L, later.pending.getValue("youtube").baseVersion)
        assertTrue(LinkField.ENABLED in later.localEdits.getValue("youtube"))
    }

    @Test fun `boolean only partial snapshots apply false without resetting unselected or absent values`() {
        val original = state(selected = setOf(LinkField.ARTWORK, LinkField.ACTION_BUTTONS))
        val remote = mapOf(LinkField.ARTWORK to JsonPrimitive(false), LinkField.ENABLED to JsonPrimitive(false))
        val merged = merge(original, values = remote)
        assertEquals(mapOf(LinkField.ARTWORK to JsonPrimitive(false)), merged.apply.getValue("youtube"))
        assertTrue(merged.state.pending.isEmpty())
        assertEquals(original.localEdits, merged.state.localEdits)
    }

    @Test fun `an unsent offline edit is never rebased onto a fresh read`() {
        val original = state().recordEdit("youtube", LinkField.ARTWORK, JsonPrimitive(false))
        val merged = merge(original, version = 20)
        assertEquals(7L, merged.state.pending.getValue("youtube").baseVersion)
        assertFalse(merged.apply.getValue("youtube").containsKey(LinkField.ARTWORK))
        assertEquals(initialValues.getValue(LinkField.ENABLED), merged.apply.getValue("youtube")[LinkField.ENABLED])
    }

    @Test fun `an edit made during initial adoption obtains its first baseline without losing the tap`() {
        val original = state().copy(baselines = emptyMap())
        val current = original.recordEdit("youtube", LinkField.ARTWORK, JsonPrimitive(false))
        val merged = merge(current, expected = original, version = 9)
        assertEquals(9L, merged.state.pending.getValue("youtube").baseVersion)
        assertEquals(JsonPrimitive(false), merged.state.pending.getValue("youtube").fields.getValue(LinkField.ARTWORK).value)
        assertFalse(LinkField.ARTWORK in merged.apply.getValue("youtube"))
    }

    @Test fun `a newer tap on the submitted field survives acknowledgement and uses the acknowledged version`() {
        val original = state().recordEdit("youtube", LinkField.ARTWORK, JsonPrimitive(false))
        val current = original.recordEdit("youtube", LinkField.ARTWORK, JsonPrimitive(true))
        val merged = merge(current, expected = original, sent = original.pending,
            values = initialValues + (LinkField.ARTWORK to JsonPrimitive(false)))
        assertFalse(LinkField.ARTWORK in merged.apply.getValue("youtube"))
        assertEquals(JsonPrimitive(true), merged.state.pending.getValue("youtube").fields.getValue(LinkField.ARTWORK).value)
        assertEquals(8L, merged.state.pending.getValue("youtube").baseVersion)
    }

    @Test fun `an edit on another field in flight preserves only that field and adopts the rest`() {
        val original = state().recordEdit("youtube", LinkField.ARTWORK, JsonPrimitive(false))
        val current = original.recordEdit("youtube", LinkField.ENABLED, JsonPrimitive(false))
        val merged = merge(current, expected = original, sent = original.pending,
            values = initialValues + (LinkField.ARTWORK to JsonPrimitive(false)))
        assertEquals(setOf(LinkField.ENABLED), merged.state.pending.getValue("youtube").fields.keys)
        assertFalse(LinkField.ENABLED in merged.apply.getValue("youtube"))
        assertEquals(JsonPrimitive(false), merged.apply.getValue("youtube")[LinkField.ARTWORK])
        assertEquals(8L, merged.state.pending.getValue("youtube").baseVersion)
    }

    @Test fun `a stale service conflict adopts authority while another service succeeds`() {
        val original = state(ids = setOf("youtube", "twitch"))
            .recordEdit("youtube", LinkField.ARTWORK, JsonPrimitive(false))
            .recordEdit("twitch", LinkField.ARTWORK, JsonPrimitive(false))
        val response = LinkSettingsSnapshot(mapOf("youtube" to LinkPreference(initialValues, 9),
            "twitch" to LinkPreference(initialValues + (LinkField.ARTWORK to JsonPrimitive(false)), 10)), setOf("youtube"))
        val merged = mergeLinkSettings(original, original, original.pending, response, original.enabled,
            original.enabled.associateWith { initialValues }, original.enabled.associateWith { listOf("app", "creator", "title") })
        assertTrue(merged.state.pending.isEmpty())
        assertEquals(JsonPrimitive(true), merged.apply.getValue("youtube")[LinkField.ARTWORK])
        assertEquals(JsonPrimitive(false), merged.apply.getValue("twitch")[LinkField.ARTWORK])
    }

    @Test fun `a newer tap during a conflict is retained without rebasing the rejected write`() {
        val original = state().recordEdit("youtube", LinkField.ARTWORK, JsonPrimitive(false))
        val current = original.recordEdit("youtube", LinkField.ENABLED, JsonPrimitive(false))
        val merged = merge(current, expected = original, sent = original.pending, conflicts = setOf("youtube"), version = 20)
        assertEquals(setOf(LinkField.ENABLED), merged.state.pending.getValue("youtube").fields.keys)
        assertEquals(7L, merged.state.pending.getValue("youtube").baseVersion)
        assertEquals(JsonPrimitive(true), merged.apply.getValue("youtube")[LinkField.ARTWORK])
    }

    @Test fun `selection changes and source resets require a fresh baseline and discard old shared writes`() {
        val edited = state().recordEdit("youtube", LinkField.ARTWORK, JsonPrimitive(false))
        listOf(setOf(LinkField.ENABLED), fields).forEach { selection ->
            val changed = edited.withDiscovery(mapOf("youtube" to LinkSelection(selection, 4)))
            assertTrue(changed.pending.isEmpty())
            assertTrue(changed.baselines.isEmpty())
            assertEquals(selection, changed.fieldsFor("youtube"))
        }
        val paused = edited.withDiscovery(emptyMap())
        assertTrue(paused.pending.isEmpty())
        assertTrue(paused.baselines.isEmpty())
        assertEquals(edited.localEdits, paused.localEdits)
        assertTrue(paused.withDiscovery(mapOf("youtube" to LinkSelection(fields, 3))).baselines.isEmpty())
    }

    @Test fun `an unmappable display discards only its queued field while booleans keep syncing`() {
        val current = state().recordEdit("youtube", LinkField.ARTWORK, JsonPrimitive(false))
            .recordEdit("youtube", LinkField.DISPLAY, JsonPrimitive("title"))
            .recordEdit("youtube", LinkField.DISPLAY, null)
        assertEquals(setOf(LinkField.ARTWORK), current.pending.getValue("youtube").fields.keys)
        val merged = merge(current, supported = fields - LinkField.DISPLAY)
        assertFalse(LinkField.DISPLAY in merged.apply.getValue("youtube"))
        assertTrue(LinkField.ENABLED in merged.apply.getValue("youtube"))
    }

    @Test fun `a false starting value advertises support without a display choice`() {
        val participant = LinkParticipant("phone", "Phone", "mobile", mapOf(LinkField.ARTWORK to JsonPrimitive(false)), "now")
        assertTrue(participant.supports(setOf(LinkField.ARTWORK), emptyList()))
        assertFalse(participant.supports(linkDisplayFields, listOf("app")))
        assertFalse(participant.supports(emptySet(), emptyList()))
    }

    @Test fun `a tap during discovery before learning a selection is queued with the fresh baseline`() {
        val beforeDiscovery = LinkStoredState()
        val tapped = beforeDiscovery.recordEdit("youtube", LinkField.ARTWORK, JsonPrimitive(false))
        assertTrue(tapped.pending.isEmpty())
        val discovered = tapped.withDiscovery(mapOf("youtube" to LinkSelection(setOf(LinkField.ARTWORK), 1)))
        val merged = mergeLinkSettings(discovered, beforeDiscovery, emptyMap(),
            LinkSettingsSnapshot(mapOf("youtube" to LinkPreference(mapOf(LinkField.ARTWORK to JsonPrimitive(true)), 5))),
            setOf("youtube"), mapOf("youtube" to mapOf(LinkField.ARTWORK to JsonPrimitive(false))), emptyMap())
        assertFalse(LinkField.ARTWORK in merged.apply.getValue("youtube"))
        assertEquals(JsonPrimitive(false), merged.state.pending.getValue("youtube").fields.getValue(LinkField.ARTWORK).value)
        assertEquals(5L, merged.state.pending.getValue("youtube").baseVersion)
    }

    @Test fun `a tap included in the outgoing request is acknowledged even if discovery began earlier`() {
        val beforeDiscovery = state()
        val tapped = beforeDiscovery.recordEdit("youtube", LinkField.ARTWORK, JsonPrimitive(false))
        val merged = merge(tapped, expected = beforeDiscovery, sent = tapped.pending,
            values = initialValues + (LinkField.ARTWORK to JsonPrimitive(false)))
        assertTrue(merged.state.pending.isEmpty())
        assertEquals(JsonPrimitive(false), merged.apply.getValue("youtube")[LinkField.ARTWORK])
    }

    @Test fun `a remembered selection cannot queue writes while its settings are inactive`() {
        val inactive = state().copy(enabled = emptySet())
            .recordEdit("youtube", LinkField.ARTWORK, JsonPrimitive(false))
        assertTrue(inactive.pending.isEmpty())
        assertTrue(inactive.fieldsFor("youtube").isEmpty())
    }
}
