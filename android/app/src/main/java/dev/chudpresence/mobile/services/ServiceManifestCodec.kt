package dev.chudpresence.mobile.services

import dev.chudpresence.mobile.data.PresenceContent
import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

data class RemovedService(val id: String, val name: String, val packages: Set<String>)

internal data class RegistryCache(
    val manifest: List<ServiceDefinition>?,
    val discovered: Map<String, String>,
    val removed: List<RemovedService> = emptyList(),
    val categories: Map<String, String> = emptyMap(),
    val icons: Map<String, String> = emptyMap()
)

/** The registry can select only preimplemented fields and public HTTPS destinations. */
internal object ServiceManifestCodec {
    const val MAX_BYTES = 256 * 1024
    // Normalizing omitted defaults expands a valid download; saved application labels are separate.
    const val MAX_CACHE_BYTES = 1024 * 1024
    private val json = Json { isLenient = false }
    private val id = Regex("[a-z0-9][a-z0-9_.-]{0,127}")
    private val packageName = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")
    private val hostLabel = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
    private val fields = setOf("title", "display_title", "display_subtitle", "display_description", "artist", "album")

    fun decode(text: String): List<ServiceDefinition> {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Service registry is too large" }
        return decodeObject(json.parseToJsonElement(text) as? JsonObject ?: error("Expected a registry object"))
    }

    private fun decodeObject(root: JsonObject): List<ServiceDefinition> {
        require((root["schemaVersion"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull == 1) {
            "Unsupported service registry version"
        }
        val records = root["services"] as? JsonArray ?: error("Missing service definitions")
        require(records.size in 1..256) { "Invalid service count" }
        val definitions = records.map { raw ->
            val record = raw as? JsonObject ?: error("Invalid service definition")
            val serviceId = record.string("id")!!.also { require(id.matches(it)) { "Invalid service ID" } }
            val packages = record.strings("packages", 20).also {
                require(it.isNotEmpty() && it.all(::validPackage)) { "Invalid application package" }
            }
            val kind = record.string("mediaKind", "unknown")!!.also {
                require(it in setOf("music", "video", "unknown")) { "Unsupported media kind" }
            }
            val title = record.string("titleField", "title")!!.also { require(it in fields) { "Unsupported title field" } }
            val artist = record.string("artistField", "artist")!!.also { require(it in fields) { "Unsupported artist field" } }
            val episodes = record.boolean("supportsEpisodes", false)
            val default = PresenceContent.fromWireValue(record.string("defaultContent", "artist"))
                ?: error("Unsupported default appearance")
            require(default != PresenceContent.SEASON_EPISODE || episodes) { "Episode appearance requires episode support" }
            ServiceDefinition(serviceId, record.label("name", 80)!!, packages, kind, title, artist,
                record.label("titleLabel", 64, "Title")!!, record.label("artistLabel", 64, "Artist / creator")!!,
                default, episodes, record.string("fallbackArtworkUrl")?.also {
                    require(publicHttpsUrl(it)) { "Invalid fallback artwork URL" }
                }, record.strings("allowedHosts", 32, optional = true).also {
                    require(it.all(::publicHost)) { "Invalid allowed host" }
                }, record.label("contentButtonLabel", 32, optional = true),
                record.label("artistButtonLabel", 32, optional = true))
        }
        validateUnique(definitions)
        return definitions
    }

    fun encode(definitions: List<ServiceDefinition>): String = manifestObject(definitions).toString()

    fun encodeCache(manifest: List<ServiceDefinition>?, discovered: Map<String, String>,
        removed: List<RemovedService> = emptyList(), categories: Map<String, String> = emptyMap(),
        icons: Map<String, String> = emptyMap()): String = buildJsonObject {
        put("schemaVersion", 1)
        put("manifest", manifest?.let(::manifestObject) ?: JsonNull)
        put("discovered", buildJsonObject { discovered.forEach { (name, label) -> put(name, label) } })
        put("removed", buildJsonArray { removed.forEach { service -> add(buildJsonObject {
            put("id", service.id); put("name", service.name); put("packages", stringArray(service.packages))
        }) } })
        put("categories", buildJsonObject { categories.forEach { (pkg, category) -> put(pkg, category) } })
        put("icons", buildJsonObject { icons.forEach { (pkg, url) -> put(pkg, url) } })
    }.toString().also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_CACHE_BYTES) { "Service cache is too large" } }

    fun decodeCache(text: String): RegistryCache {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_CACHE_BYTES) { "Service cache is too large" }
        val root = json.parseToJsonElement(text) as? JsonObject ?: error("Invalid service cache")
        require((root["schemaVersion"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull == 1)
        val manifest = root["manifest"].takeUnless { it == null || it == JsonNull }?.let {
            decodeObject(it as? JsonObject ?: error("Invalid cached registry"))
        }
        val discoveredObject = root["discovered"] as? JsonObject ?: error("Invalid discovered applications")
        require(discoveredObject.size <= 512) { "Too many discovered applications" }
        val discovered = discoveredObject.mapValues { (packageName, value) ->
            require(validPackage(packageName)) { "Invalid discovered package" }
            val label = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Invalid app label")
            require(validLabel(label, 80)) { "Invalid app label" }
            label
        }
        val removed = (root["removed"]?.let { it as? JsonArray ?: error("Invalid removed services") } ?: JsonArray(emptyList())).map {
            val record = it as? JsonObject ?: error("Invalid removed service")
            val serviceId = record.string("id")!!
            require(id.matches(serviceId) || validPackage(serviceId)) { "Invalid removed service ID" }
            RemovedService(serviceId, record.label("name", 80)!!, record.strings("packages", 20).also { packages ->
                require(packages.isNotEmpty() && packages.all(::validPackage)) { "Invalid removed packages" }
            })
        }
        require(removed.size <= 768 && removed.map { it.id }.distinct().size == removed.size) { "Invalid removed services" }
        val categoriesObject = root["categories"]?.let { it as? JsonObject ?: error("Invalid app categories") } ?: JsonObject(emptyMap())
        require(categoriesObject.size <= 512) { "Too many app categories" }
        val categories = categoriesObject.mapValues { (pkg, value) ->
            require(validPackage(pkg) && pkg in discovered) { "Invalid categorized package" }
            (value as? JsonPrimitive)?.takeIf { it.isString && GooglePlayAppLookup.validCategory(it.content) }?.content
                ?: error("Invalid app category")
        }
        val iconsObject = root["icons"]?.let { it as? JsonObject ?: error("Invalid app icons") } ?: JsonObject(emptyMap())
        require(iconsObject.size <= 512) { "Too many app icons" }
        val icons = iconsObject.mapValues { (pkg, value) ->
            require(validPackage(pkg) && pkg in discovered) { "Invalid app icon package" }
            (value as? JsonPrimitive)?.takeIf { it.isString && GooglePlayAppLookup.validIconUrl(it.content) }?.content
                ?: error("Invalid app icon URL")
        }
        return RegistryCache(manifest, discovered, removed, categories, icons)
    }

    fun merge(bundled: List<ServiceDefinition>, downloaded: List<ServiceDefinition>?): List<ServiceDefinition> {
        val byId = bundled.associateByTo(linkedMapOf()) { it.id }
        downloaded?.forEach { byId[it.id] = it }
        return byId.values.toList().also(::validateUnique)
    }

    fun validPackage(value: String): Boolean = value.length <= 255 && packageName.matches(value)

    fun appLabel(value: String, fallback: String, previous: String? = null): String {
        fun readable(label: String?) = label?.filterNot(Char::isISOControl)?.trim()?.take(80)
            ?.takeIf { it.isNotEmpty() && it != fallback.take(80) }
        return readable(value) ?: readable(previous) ?: fallback.take(80)
    }

    fun publicHost(value: String): Boolean {
        if (value.length !in 4..253 || value != value.lowercase()) return false
        val labels = value.split('.')
        return labels.size >= 2 && labels.all(hostLabel::matches) && labels.last().any(Char::isLetter) &&
            labels.last().length >= 2 && value !in setOf("localhost", "localhost.localdomain") &&
            labels.last() !in setOf("local", "localhost", "internal", "test", "invalid", "example", "onion")
    }

    fun publicHttpsUrl(value: String): Boolean = value.length <= 300 && runCatching {
        val uri = URI(value)
        uri.scheme == "https" && uri.host?.let(::publicHost) == true && uri.userInfo == null &&
            uri.port == -1 && uri.fragment == null && publicQuery(uri.rawQuery)
    }.getOrDefault(false)

    private fun publicQuery(rawQuery: String?): Boolean {
        var query = rawQuery ?: return true
        // Inspect decoded names too: a URL such as ?access_%74oken=... is still credential-bearing.
        repeat(3) {
            val names = query.split('&').map { it.substringBefore('=').lowercase() }
            if (names.any { name -> name in setOf("auth", "authorization", "key", "sig", "signature", "policy", "expires", "session", "sessionid", "key-pair-id") ||
                    listOf("token", "credential", "secret", "password", "x-amz-", "x-goog-").any(name::contains) }) return false
            val decoded = java.net.URLDecoder.decode(query, "UTF-8")
            if (decoded == query) return true
            query = decoded
        }
        return '%' !in query
    }

    private fun validateUnique(definitions: List<ServiceDefinition>) {
        require(definitions.map { it.id }.distinct().size == definitions.size) { "Duplicate service ID" }
        val packages = definitions.flatMap { it.packages }
        require(packages.distinct().size == packages.size) { "An application belongs to multiple services" }
    }

    private fun validLabel(value: String, limit: Int): Boolean = value.isNotBlank() && value == value.trim() &&
        value.length <= limit && value.none(Char::isISOControl)

    private fun JsonObject.string(key: String, default: String? = null): String? {
        val value = get(key) ?: return default
        if (value == JsonNull) return null
        return (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Invalid $key")
    }

    private fun JsonObject.label(key: String, limit: Int, default: String? = null, optional: Boolean = false): String? =
        string(key, default).also {
            require((optional && it == null) || (it != null && validLabel(it, limit))) { "Invalid $key" }
        }

    private fun JsonObject.boolean(key: String, default: Boolean): Boolean {
        val value = get(key) ?: return default
        return (value as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: error("Invalid $key")
    }

    private fun JsonObject.strings(key: String, limit: Int, optional: Boolean = false): Set<String> {
        val value = get(key) ?: return if (optional) emptySet() else error("Missing $key")
        val array = value as? JsonArray ?: error("Invalid $key")
        require(array.size <= limit) { "Too many $key" }
        val values = array.map { (it as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Invalid $key") }
        require(values.distinct().size == values.size) { "Duplicate $key" }
        return values.toSet()
    }

    private fun manifestObject(definitions: List<ServiceDefinition>): JsonObject = buildJsonObject {
        put("schemaVersion", 1)
        put("services", buildJsonArray {
            definitions.forEach { service -> add(buildJsonObject {
                put("id", service.id); put("name", service.name)
                put("packages", stringArray(service.packages))
                put("mediaKind", service.mediaKind); put("titleField", service.titleField); put("artistField", service.artistField)
                put("titleLabel", service.titleLabel); put("artistLabel", service.artistLabel)
                put("defaultContent", service.defaultContent.wireValue); put("supportsEpisodes", service.supportsEpisodes)
                service.fallbackArtworkUrl?.let { put("fallbackArtworkUrl", it) }
                put("allowedHosts", stringArray(service.allowedHosts))
                service.contentButtonLabel?.let { put("contentButtonLabel", it) }
                service.artistButtonLabel?.let { put("artistButtonLabel", it) }
            }) }
        })
    }

    private fun stringArray(values: Collection<String>): JsonArray = JsonArray(values.map(::JsonPrimitive))
}
