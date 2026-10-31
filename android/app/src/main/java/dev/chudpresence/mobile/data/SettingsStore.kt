package dev.chudpresence.mobile.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import dev.chudpresence.mobile.services.ServiceDefinition
import dev.chudpresence.mobile.link.*
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.settings by preferencesDataStore("chudpresence")
private val SERVICE_CONTENT = PresenceService.entries.associateWith {
    androidx.datastore.preferences.core.stringPreferencesKey("presence_name_content_${it.source}")
}
private const val SERVICE_CONTENT_PREFIX = "presence_name_content_"
private const val EPISODE_SUPPORT_PREFIX = "presence_episode_support_"
private const val ACTIVITY_OPTION_PREFIX = "presence_activity_"
private val SETUP_COMPLETED = booleanPreferencesKey("setup_completed")
private val CRUNCHYROLL_REFRESH_TOKEN = androidx.datastore.preferences.core.stringPreferencesKey("crunchyroll_refresh_token")
private val CRUNCHYROLL_DEVICE_ID = androidx.datastore.preferences.core.stringPreferencesKey("crunchyroll_device_id")
private val TWITCH_ACCESS_TOKEN = androidx.datastore.preferences.core.stringPreferencesKey("twitch_access_token")
private val TWITCH_REFRESH_TOKEN = androidx.datastore.preferences.core.stringPreferencesKey("twitch_refresh_token")
private val TWITCH_EXPIRES_AT = androidx.datastore.preferences.core.longPreferencesKey("twitch_expires_at")
private val LINK_PAIRING = androidx.datastore.preferences.core.stringPreferencesKey("link_pairing_encrypted")
private val LINK_SYNC = androidx.datastore.preferences.core.stringPreferencesKey("link_sync_state")

data class CrunchyrollSession(val refreshToken: String, val deviceId: String)
data class TwitchSession(val accessToken: String, val refreshToken: String, val expiresAtEpochMs: Long)

class SettingsStore internal constructor(private val settings: DataStore<Preferences>) {
    constructor(context: Context) : this(context.settings)

    val setupCompleted: Flow<Boolean> = settings.data.map { preferences ->
        preferences[SETUP_COMPLETED] ?: false
    }

    suspend fun completeSetup() = settings.edit {
        it[SETUP_COMPLETED] = true
    }

    val displaySettings: Flow<PresenceDisplaySettings> = settings.data.map(::readDisplaySettings).distinctUntilChanged()

    private fun readDisplaySettings(preferences: Preferences): PresenceDisplaySettings {
        val activityOptions = mutableMapOf<String, ServiceActivityOptions>()
        ServiceActivityOption.entries.forEach { option ->
            val prefix = ACTIVITY_OPTION_PREFIX + option.storageKey + "_"
            preferences.asMap().forEach { (key, value) ->
                if (key.name.startsWith(prefix) && value is Boolean) {
                    val id = key.name.removePrefix(prefix)
                    activityOptions[id] = (activityOptions[id] ?: ServiceActivityOptions()).with(option, value)
                }
            }
        }
        // Ignore the legacy global choice and use each service's default.
        return PresenceDisplaySettings(
            services = PresenceService.entries.mapNotNull { service ->
                PresenceContent.fromWireValue(preferences[SERVICE_CONTENT.getValue(service)])
                    ?.let { service to it }
            }.toMap(),
            additionalServices = preferences.asMap().mapNotNull { (key, value) ->
                val id = key.name.takeIf { it.startsWith(SERVICE_CONTENT_PREFIX) }
                    ?.removePrefix(SERVICE_CONTENT_PREFIX) ?: return@mapNotNull null
                if (PresenceService.entries.any { it.source == id }) return@mapNotNull null
                PresenceContent.fromWireValue(value as? String)?.let { id to it }
            }.toMap(),
            extendedEpisodeServices = preferences.asMap().mapNotNull { (key, value) ->
                key.name.takeIf { it.startsWith(EPISODE_SUPPORT_PREFIX) && value == true }
                    ?.removePrefix(EPISODE_SUPPORT_PREFIX)
            }.toSet(),
            activityOptions = activityOptions
        )
    }

    suspend fun setServiceActivityOption(service: ServiceDefinition, option: ServiceActivityOption, value: Boolean) = settings.edit {
        // Adopt package-keyed choices when discovery later assigns a stable registry ID. Read
        // inside the transaction so rapid changes to different controls cannot lose an edit.
        val previous = readDisplaySettings(it).optionsFor(service)
        val options = previous.with(option, value)
        ServiceActivityOption.entries.forEach { field ->
            it[booleanPreferencesKey(ACTIVITY_OPTION_PREFIX + field.storageKey + "_" + service.id)] = options[field]
        }
        if (previous[option] != value) it.recordLinkEdit(service.id, LinkField.forOption(option), JsonPrimitive(value))
    }
    val crunchyrollSession: Flow<CrunchyrollSession?> = settings.data.map { preferences ->
        preferences[CRUNCHYROLL_REFRESH_TOKEN] to preferences[CRUNCHYROLL_DEVICE_ID]
    }.distinctUntilChanged().map { (encrypted, deviceId) ->
        if (encrypted == null || deviceId == null) null
        else runCatching { CrunchyrollSession(decrypt(encrypted), deviceId) }.getOrNull()
    }.distinctUntilChanged()
    val twitchSession: Flow<TwitchSession?> = settings.data.map { preferences ->
        Triple(preferences[TWITCH_ACCESS_TOKEN], preferences[TWITCH_REFRESH_TOKEN], preferences[TWITCH_EXPIRES_AT])
    }.distinctUntilChanged().map { (access, refresh, expiresAt) ->
        if (access == null || refresh == null || expiresAt == null) null
        else runCatching { TwitchSession(decrypt(access), decrypt(refresh), expiresAt) }.getOrNull()
    }.distinctUntilChanged()

    suspend fun setServiceDisplayContent(service: PresenceService, content: PresenceContent) = settings.edit {
        require(content in service.options) { "Unsupported profile choice for ${service.label}" }
        val previous = readDisplaySettings(it).contentFor(service)
        it[SERVICE_CONTENT.getValue(service)] = content.wireValue
        if (previous != content) it.recordLinkEdit(service.source, LinkField.DISPLAY, content.toLinkDisplay()?.let(::JsonPrimitive))
    }

    suspend fun setServiceDisplayContent(service: ServiceDefinition, content: PresenceContent) = settings.edit {
        require(content in service.options) { "Unsupported profile choice for ${service.name}" }
        val previous = readDisplaySettings(it).contentFor(service)
        it[androidx.datastore.preferences.core.stringPreferencesKey(SERVICE_CONTENT_PREFIX + service.id)] = content.wireValue
        if (previous != content) it.recordLinkEdit(service.id, LinkField.DISPLAY, content.toLinkDisplay()?.let(::JsonPrimitive))
        val legacy = PresenceService.entries.firstOrNull { it.source == service.id }
        if (content == PresenceContent.SEASON_EPISODE && legacy != null && content !in legacy.options) {
            it[booleanPreferencesKey(EPISODE_SUPPORT_PREFIX + service.id)] = true
        }
    }

    suspend fun saveCrunchyrollSession(session: CrunchyrollSession) = settings.edit {
        it[CRUNCHYROLL_REFRESH_TOKEN] = encrypt(session.refreshToken)
        it[CRUNCHYROLL_DEVICE_ID] = session.deviceId
    }

    /** A delayed refresh must not undo Disconnect or replace a newer sign-in. */
    internal suspend fun saveRefreshedCrunchyrollSession(original: CrunchyrollSession, refreshed: CrunchyrollSession) = settings.edit {
        val encrypted = it[CRUNCHYROLL_REFRESH_TOKEN] ?: return@edit
        val device = it[CRUNCHYROLL_DEVICE_ID] ?: return@edit
        val current = runCatching { CrunchyrollSession(decrypt(encrypted), device) }.getOrNull()
        if (current != original) return@edit
        it[CRUNCHYROLL_REFRESH_TOKEN] = encrypt(refreshed.refreshToken)
        it[CRUNCHYROLL_DEVICE_ID] = refreshed.deviceId
    }

    suspend fun clearCrunchyrollSession() = settings.edit {
        it.remove(CRUNCHYROLL_REFRESH_TOKEN)
        it.remove(CRUNCHYROLL_DEVICE_ID)
    }

    suspend fun saveTwitchSession(session: TwitchSession) = settings.edit {
        it[TWITCH_ACCESS_TOKEN] = encrypt(session.accessToken)
        it[TWITCH_REFRESH_TOKEN] = encrypt(session.refreshToken)
        it[TWITCH_EXPIRES_AT] = session.expiresAtEpochMs
    }

    internal suspend fun saveRefreshedTwitchSession(original: TwitchSession, refreshed: TwitchSession) = settings.edit {
        val access = it[TWITCH_ACCESS_TOKEN] ?: return@edit
        val refresh = it[TWITCH_REFRESH_TOKEN] ?: return@edit
        val expiresAt = it[TWITCH_EXPIRES_AT] ?: return@edit
        val current = runCatching {
            TwitchSession(decrypt(access), decrypt(refresh), expiresAt)
        }.getOrNull()
        if (current != original) return@edit
        it[TWITCH_ACCESS_TOKEN] = encrypt(refreshed.accessToken)
        it[TWITCH_REFRESH_TOKEN] = encrypt(refreshed.refreshToken)
        it[TWITCH_EXPIRES_AT] = refreshed.expiresAtEpochMs
    }

    suspend fun clearTwitchSession() = settings.edit {
        it.remove(TWITCH_ACCESS_TOKEN)
        it.remove(TWITCH_REFRESH_TOKEN)
        it.remove(TWITCH_EXPIRES_AT)
    }

    internal val linkSnapshot: Flow<LinkLocalSnapshot> = settings.data.map { preferences ->
        LinkLocalSnapshot(readLinkPairing(preferences), LinkStateCodec.decode(preferences[LINK_SYNC]), readDisplaySettings(preferences))
    }

    private fun readLinkPairing(preferences: Preferences): LinkPairing? = preferences[LINK_PAIRING]?.let {
        runCatching { LinkStateCodec.decodePairing(decrypt(it)) }.getOrNull()
    }

    internal suspend fun saveLinkPairing(pairing: LinkPairing) = settings.edit {
        it[LINK_PAIRING] = encrypt(LinkStateCodec.encodePairing(pairing))
        it[LINK_SYNC] = LinkStateCodec.encode(LinkStoredState())
    }

    internal suspend fun clearLinkPairing() = settings.edit {
        it.remove(LINK_PAIRING)
        it.remove(LINK_SYNC)
    }

    internal suspend fun requireLinkPairing(pairing: LinkPairing) = settings.edit {
        val current = readLinkPairing(it)
        if (pairing.sameDevice(current) && pairing.token == current?.token) {
            it[LINK_SYNC] = LinkStateCodec.encode(LinkStateCodec.decode(it[LINK_SYNC]).copy(requiresPairing = true))
        }
    }

    /** The local choice and its queued shared edit are one durable transaction. */
    private fun MutablePreferences.recordLinkEdit(localId: String, field: LinkField, value: JsonPrimitive?) {
        if (this[LINK_PAIRING] == null) return
        val id = linkServiceId(localId) ?: return
        val state = LinkStateCodec.decode(this[LINK_SYNC])
        this[LINK_SYNC] = LinkStateCodec.encode(state.recordEdit(id, field, value))
    }

    internal suspend fun updateLinkDiscovery(pairing: LinkPairing, selections: Map<String, LinkSelection>) = settings.edit {
        if (!pairing.sameDevice(readLinkPairing(it))) return@edit
        val state = LinkStateCodec.decode(it[LINK_SYNC])
        it[LINK_SYNC] = LinkStateCodec.encode(state.withDiscovery(selections))
    }

    /** Remote application never calls the local edit path; in-flight responses preserve newer taps. */
    internal suspend fun applyLinkSettings(pairing: LinkPairing, expected: LinkLocalSnapshot,
        sent: Map<String, LinkEdit>, response: LinkSettingsSnapshot, definitions: Map<String, ServiceDefinition>,
        active: Set<String>, choices: Map<String, List<String>>) = settings.edit { preferences ->
        if (!pairing.sameDevice(readLinkPairing(preferences))) return@edit
        val current = LinkStateCodec.decode(preferences[LINK_SYNC])
        if (current.requiresPairing) return@edit
        val displaySettings = readDisplaySettings(preferences)
        val merged = mergeLinkSettings(current, expected.sync, sent, response, active,
            definitions.mapValues { displaySettings.linkValues(it.value) }, choices)
        merged.apply.forEach { (id, fields) ->
            val definition = definitions.getValue(id)
            fields[LinkField.DISPLAY]?.content?.let(::linkContent)?.takeIf { it in definition.options }?.let { content ->
                preferences[androidx.datastore.preferences.core.stringPreferencesKey(SERVICE_CONTENT_PREFIX + definition.id)] = content.wireValue
            }
            var options = displaySettings.optionsFor(definition)
            val activityFields = fields.filterKeys { it.option != null }
            activityFields.forEach { (field, value) -> options = options.with(field.option!!, value.boolean) }
            if (activityFields.isNotEmpty()) ServiceActivityOption.entries.forEach { option ->
                preferences[booleanPreferencesKey(ACTIVITY_OPTION_PREFIX + option.storageKey + "_" + definition.id)] = options[option]
            }
        }
        preferences[LINK_SYNC] = LinkStateCodec.encode(merged.state)
    }

    @Synchronized
    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey("chudpresence-token", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("chudpresence-token", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(value.encodeToByteArray()))
    }

    private fun decrypt(value: String): String {
        val bytes = Base64.getDecoder().decode(value)
        require(bytes.size > 12)
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        }.doFinal(bytes.copyOfRange(12, bytes.size)).decodeToString()
    }
}
