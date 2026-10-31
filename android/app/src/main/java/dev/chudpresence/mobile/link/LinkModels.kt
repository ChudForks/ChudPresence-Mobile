package dev.chudpresence.mobile.link

import dev.chudpresence.mobile.data.PresenceContent
import dev.chudpresence.mobile.data.PresenceDisplaySettings
import dev.chudpresence.mobile.data.ServiceActivityOption
import dev.chudpresence.mobile.services.ServiceDefinition
import java.util.Locale
import kotlinx.serialization.json.*

internal class LinkPairing(
    val endpoint: String,
    val serverId: String,
    val deviceId: String,
    val phoneName: String,
    val token: String
) {
    fun sameDevice(other: LinkPairing?): Boolean = other != null &&
        endpoint == other.endpoint && serverId == other.serverId && deviceId == other.deviceId
}

internal enum class LinkField(val wireName: String, val label: String, val option: ServiceActivityOption? = null) {
    DISPLAY("display", "Discord bar display"),
    ENABLED("enabled", "Enable service", ServiceActivityOption.ENABLED),
    SHOW_WHILE_PAUSED("showWhilePaused", "Show while paused", ServiceActivityOption.SHOW_WHILE_PAUSED),
    ARTWORK("artwork", "Artwork", ServiceActivityOption.ARTWORK),
    PLAYBACK_PROGRESS("playbackProgress", "Playback progress", ServiceActivityOption.PLAYBACK_PROGRESS),
    ACTION_BUTTONS("actionButtons", "Action buttons", ServiceActivityOption.ACTION_BUTTONS);

    companion object {
        fun fromWire(value: String): LinkField? = entries.firstOrNull { it.wireName == value }
        fun forOption(option: ServiceActivityOption): LinkField = entries.single { it.option == option }
    }
}

internal val linkDisplayFields: Set<LinkField> = setOf(LinkField.DISPLAY)

/** Null and absent fields stay local; explicit false is a supported value. */
internal fun readLinkValues(root: JsonObject): Map<LinkField, JsonPrimitive> = buildMap {
    LinkField.entries.forEach { field ->
        val raw = root[field.wireName]?.takeUnless { it == JsonNull } ?: return@forEach
        val value = raw as? JsonPrimitive ?: throw IllegalArgumentException("Invalid Link setting")
        require(if (field == LinkField.DISPLAY) value.isString && linkContent(value.content) != null
            else !value.isString && value.booleanOrNull != null) { "Invalid Link setting" }
        put(field, value)
    }
}

internal data class LinkPreference(val values: Map<LinkField, JsonPrimitive>, val version: Long)
internal data class LinkFieldEdit(val value: JsonPrimitive, val editId: String)
internal data class LinkEdit(val fields: Map<LinkField, LinkFieldEdit>, val baseVersion: Long)
internal data class LinkSelection(val fields: Set<LinkField>, val version: Long)

internal data class LinkStoredState(
    val requiresPairing: Boolean = false,
    val enabled: Set<String> = emptySet(),
    val baselines: Map<String, LinkPreference> = emptyMap(),
    val pending: Map<String, LinkEdit> = emptyMap(),
    val localEdits: Map<String, Map<LinkField, String>> = emptyMap(),
    val syncFields: Map<String, Set<LinkField>> = emptyMap(),
    val syncVersions: Map<String, Long> = emptyMap()
) {
    fun fieldsFor(id: String): Set<LinkField> = if (id in enabled) syncFields[id] ?: linkDisplayFields else emptySet()
}

internal data class LinkLocalSnapshot(
    val pairing: LinkPairing?,
    val sync: LinkStoredState,
    val displaySettings: dev.chudpresence.mobile.data.PresenceDisplaySettings
)

internal data class LinkParticipant(
    val deviceId: String, val name: String, val kind: String,
    val values: Map<LinkField, JsonPrimitive>, val reportedAt: String
) {
    fun supports(fields: Set<LinkField>, displayChoices: List<String>): Boolean = fields.isNotEmpty() && fields.all {
        it in values && (it != LinkField.DISPLAY || values[it]?.content in displayChoices)
    }
}

internal data class LinkService(
    val id: String,
    val name: String,
    val shared: Boolean,
    val syncEnabled: Boolean,
    val syncVersion: Long,
    val displayChoices: List<String>,
    val participants: List<LinkParticipant>,
    val supportedFields: Set<LinkField> = linkDisplayFields,
    val syncFields: Set<LinkField> = linkDisplayFields,
    val syncRequested: Boolean = syncEnabled
)

internal data class LinkSettingsSnapshot(
    val services: Map<String, LinkPreference>, val conflicts: Set<String> = emptySet()
)

internal enum class LinkConnection { UNPAIRED, CONNECTING, CONNECTED, OFFLINE, WAITING_FOR_NETWORK, PAIR_AGAIN }

internal data class LinkUiState(
    val loaded: Boolean = false,
    val paired: Boolean = false,
    val endpoint: String = "",
    val phoneName: String = "",
    val deviceId: String = "",
    val connection: LinkConnection = LinkConnection.UNPAIRED,
    val busy: Boolean = false,
    val manualSyncing: Boolean = false,
    val services: List<LinkService> = emptyList(),
    val activeServices: Set<String> = emptySet(),
    val localOnlyServices: List<String> = emptyList(),
    val pendingChanges: Int = 0,
    val lastSyncedAt: Long? = null,
    val message: String? = null,
    val notice: String? = null
)

internal fun PresenceContent.toLinkDisplay(): String? = when (this) {
    PresenceContent.APP_NAME -> "app"
    PresenceContent.ARTIST -> "creator"
    PresenceContent.TRACK -> "title"
    PresenceContent.SEASON_EPISODE -> null
}

internal fun linkContent(value: String): PresenceContent? = when (value) {
    "app" -> PresenceContent.APP_NAME
    "creator" -> PresenceContent.ARTIST
    "title" -> PresenceContent.TRACK
    else -> null
}

internal fun linkDisplayLabel(value: String): String = when (value) {
    "app" -> "App name"
    "creator" -> "Creator / artist / series"
    "title" -> "Media title"
    else -> value
}

/** Platform storage keys stay intact; Link uses lowercase, hyphenated identities. */
internal fun linkServiceId(localId: String): String? = localId.lowercase(Locale.ROOT)
    .replace(Regex("[_.]+"), "-")
    .takeIf { it.length <= 64 && Regex("[a-z][a-z0-9]*(?:-[a-z0-9]+)*").matches(it) }

internal fun PresenceDisplaySettings.linkValues(definition: ServiceDefinition): Map<LinkField, JsonPrimitive> = buildMap {
    contentFor(definition).toLinkDisplay()?.let { put(LinkField.DISPLAY, JsonPrimitive(it)) }
    val options = optionsFor(definition)
    LinkField.entries.forEach { field ->
        val option = field.option ?: return@forEach
        if (field != LinkField.ACTION_BUTTONS || definition.supportsActionButtons) put(field, JsonPrimitive(options[option]))
    }
}

internal data class LinkInventoryItem(val definition: ServiceDefinition, val values: Map<LinkField, JsonPrimitive>) {
    val id: String get() = requireNotNull(linkServiceId(definition.id))
    fun json(settingsSupported: Boolean = true): JsonObject = buildJsonObject {
        put("id", id)
        put("name", definition.name.take(80))
        val supported = values.filterKeys { settingsSupported || it == LinkField.DISPLAY }
        supported.forEach { (field, value) -> put(field.wireName, value) }
        if (LinkField.DISPLAY in supported) {
            put("displayChoices", JsonArray(definition.options.mapNotNull { it.toLinkDisplay() }.map(::JsonPrimitive)))
        }
    }
}

/** Pairing JSON is encrypted by SettingsStore; ordinary sync metadata never includes a token. */
internal object LinkStateCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encodePairing(pairing: LinkPairing): String = buildJsonObject {
        put("endpoint", pairing.endpoint); put("serverId", pairing.serverId)
        put("deviceId", pairing.deviceId); put("phoneName", pairing.phoneName); put("token", pairing.token)
    }.toString()

    fun decodePairing(value: String): LinkPairing {
        val root = json.parseToJsonElement(value).jsonObject
        return LinkPairing(root.getValue("endpoint").jsonPrimitive.content,
            root.getValue("serverId").jsonPrimitive.content, root.getValue("deviceId").jsonPrimitive.content,
            root.getValue("phoneName").jsonPrimitive.content, root.getValue("token").jsonPrimitive.content)
    }

    fun encode(state: LinkStoredState): String = buildJsonObject {
        put("requiresPairing", state.requiresPairing)
        put("enabled", JsonArray(state.enabled.sorted().map(::JsonPrimitive)))
        put("baselines", buildJsonObject {
            state.baselines.forEach { (id, value) -> put(id, buildJsonObject {
                value.values.forEach { (field, setting) -> put(field.wireName, setting) }; put("version", value.version)
            }) }
        })
        put("pending", buildJsonObject {
            state.pending.forEach { (id, value) -> put(id, buildJsonObject {
                put("baseVersion", value.baseVersion)
                put("fields", buildJsonObject { value.fields.forEach { (field, edit) ->
                    put(field.wireName, buildJsonObject { put("value", edit.value); put("editId", edit.editId) })
                } })
            }) }
        })
        put("localEdits", buildJsonObject { state.localEdits.forEach { (id, fields) ->
            put(id, buildJsonObject { fields.forEach { (field, editId) -> put(field.wireName, editId) } })
        } })
        put("syncFields", buildJsonObject { state.syncFields.forEach { (id, fields) ->
            put(id, JsonArray(fields.map { JsonPrimitive(it.wireName) }))
        } })
        put("syncVersions", buildJsonObject { state.syncVersions.forEach { (id, version) -> put(id, version) } })
    }.toString()

    fun decode(value: String?): LinkStoredState = runCatching {
        if (value == null) return LinkStoredState()
        val root = json.parseToJsonElement(value).jsonObject
        LinkStoredState(
            requiresPairing = root["requiresPairing"]?.jsonPrimitive?.booleanOrNull ?: false,
            enabled = root["enabled"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty(),
            baselines = root["baselines"]?.jsonObject?.mapValues { (_, raw) ->
                LinkPreference(readLinkValues(raw.jsonObject), raw.jsonObject.getValue("version").jsonPrimitive.long)
            }.orEmpty(),
            pending = root["pending"]?.jsonObject?.mapValues { (_, raw) ->
                val item = raw.jsonObject
                val fields = item["fields"]?.jsonObject?.mapNotNull { (name, edit) ->
                    LinkField.fromWire(name)?.let { field -> field to LinkFieldEdit(
                        edit.jsonObject.getValue("value").jsonPrimitive, edit.jsonObject.getValue("editId").jsonPrimitive.content) }
                }?.toMap() ?: mapOf(LinkField.DISPLAY to LinkFieldEdit(item.getValue("display").jsonPrimitive,
                    item.getValue("editId").jsonPrimitive.content))
                LinkEdit(fields, item.getValue("baseVersion").jsonPrimitive.long)
            }.orEmpty(),
            localEdits = root["localEdits"]?.jsonObject?.mapValues { (_, value) ->
                if (value is JsonPrimitive) mapOf(LinkField.DISPLAY to value.content)
                else value.jsonObject.mapNotNull { (name, editId) ->
                    LinkField.fromWire(name)?.let { it to editId.jsonPrimitive.content }
                }.toMap()
            }.orEmpty(),
            syncFields = root["syncFields"]?.jsonObject?.mapValues { (_, value) ->
                value.jsonArray.mapNotNull { LinkField.fromWire(it.jsonPrimitive.content) }.toSet()
            }.orEmpty(),
            syncVersions = root["syncVersions"]?.jsonObject?.mapValues { it.value.jsonPrimitive.long }.orEmpty()
        )
    }.getOrDefault(LinkStoredState())
}
