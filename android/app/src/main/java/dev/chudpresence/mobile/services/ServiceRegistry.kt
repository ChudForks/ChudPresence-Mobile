package dev.chudpresence.mobile.services

import android.content.Context
import android.util.AtomicFile
import dev.chudpresence.mobile.data.PresenceContent
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

data class RegistryStatus(val refreshing: Boolean = false, val error: String? = null)

internal interface RegistryStorage {
    fun read(): String?
    fun write(contents: String)
}

/**
 * Registry definitions fetch only for an unfamiliar package or explicit refresh. Apps without
 * fallback artwork can also look up their Play icon. Reading settings never makes requests.
 */
class ServiceRegistry internal constructor(
    private val bundled: List<ServiceDefinition>,
    private val fetcher: suspend () -> String,
    private val storage: RegistryStorage,
    private val appInfoFetcher: suspend (String) -> GooglePlayAppInfo? = { null },
    private val labelResolver: (String) -> String? = { null }
) {
    constructor(context: Context) : this(
        ServiceManifestCodec.decode(context.assets.open("presence-services.json").bufferedReader().use { it.readText() }),
        RegistryHttpFetcher()::fetch,
        AtomicRegistryStorage(File(context.filesDir, "presence-services-cache.json")),
        GooglePlayAppLookup()::fetch,
        { packageName -> installedAppLabel(context, packageName) }
    )

    private val stateLock = Any()
    private val fetchLock = Mutex()
    private val storageLock = Mutex()
    private val appInfoLock = Mutex()
    private var manifest: List<ServiceDefinition>? = null
    private var configured = bundled.toList()
    private val discovered = linkedMapOf<String, String>()
    private val attempted = mutableSetOf<String>()
    private val appInfoAttempted = mutableSetOf<String>()
    private val categories = linkedMapOf<String, String>()
    private val icons = linkedMapOf<String, String>()
    private var removed = emptyList<RemovedService>()
    private var discoveryRevision = 0L
    private var persistedDiscoveryRevision = 0L
    private var completedFetchRevision = 0L
    private val _services = MutableStateFlow<List<ServiceDefinition>>(emptyList())
    val services: StateFlow<List<ServiceDefinition>> = _services
    private val _status = MutableStateFlow(RegistryStatus())
    val status: StateFlow<RegistryStatus> = _status

    init {
        // A corrupt cache cannot remove shipped support or prevent ordinary reporting.
        val cache = runCatching { storage.read()?.let(ServiceManifestCodec::decodeCache) }.getOrNull()
        if (cache != null) {
            runCatching { ServiceManifestCodec.merge(bundled, cache.manifest) }.onSuccess {
                configured = it
                manifest = cache.manifest
                discovered.putAll(cache.discovered.mapValues { (pkg, label) ->
                    ServiceManifestCodec.appLabel(labelResolver(pkg).orEmpty(), pkg, label)
                })
                removed = cache.removed
                categories.putAll(cache.categories)
                icons.putAll(cache.icons)
            }
        }
        synchronized(stateLock) { publishServices() }
    }

    fun resolve(packageName: String, appLabel: String): ServiceDefinition = synchronized(stateLock) {
        configured.firstOrNull { packageName in it.packages }?.let(::withAppIcon) ?: generic(packageName,
            ServiceManifestCodec.appLabel(appLabel, packageName, discovered[packageName]))
    }

    fun definitionForId(id: String): ServiceDefinition? = _services.value.firstOrNull { it.id == id }

    /** A stopped-and-started reporting run may retry definitions unavailable in the prior run. */
    fun beginReportingSession() = synchronized(stateLock) { attempted.clear(); appInfoAttempted.clear() }

    fun isEnabled(packageName: String): Boolean = synchronized(stateLock) { !isRemoved(packageName) }

    /** Forget the service until its next media-session discovery, including without a reinstall. */
    suspend fun remove(serviceId: String) = storageLock.withLock {
        val next = synchronized(stateLock) {
            val service = _services.value.firstOrNull { it.id == serviceId } ?: return@withLock
            removed + RemovedService(service.id, service.name, service.packages)
        }
        saveRemoval(next)
    }

    internal suspend fun clearRemoval(serviceId: String) = storageLock.withLock {
        val next = synchronized(stateLock) { removed.filterNot { it.id == serviceId } }
        saveRemoval(next)
    }

    private suspend fun saveRemoval(next: List<RemovedService>) {
        val cache = synchronized(stateLock) { encodeCache(removedServices = next) }
        withContext(Dispatchers.IO) { storage.write(cache) }
        synchronized(stateLock) { removed = next; publishServices() }
    }

    private fun isRemoved(packageName: String): Boolean {
        val definition = configured.firstOrNull { packageName in it.packages }
        return removed.any { service ->
            packageName in service.packages || definition?.let {
                it.id == service.id || it.packages.any(service.packages::contains)
            } == true
        }
    }

    suspend fun discover(packageName: String, appLabel: String) {
        if (!ServiceManifestCodec.validPackage(packageName)) return
        try {
            rediscoverPackage(packageName)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            _status.value = _status.value.copy(error = "Couldn’t save rediscovered app. Please try again.")
            return
        }
        val discovery = synchronized(stateLock) {
            if (isRemoved(packageName)) return
            val definition = configured.firstOrNull { packageName in it.packages }
            // A supplied logo already covers the fallback; keep the specialized definition intact.
            if (definition?.fallbackArtworkUrl != null) return
            val label = definition?.name ?: ServiceManifestCodec.appLabel(appLabel, packageName, discovered[packageName])
            if (discovered[packageName] != label) {
                // Bound persistent discovery independently of ordinary session playback.
                if (discovered.size < 512 || packageName in discovered) {
                    discovered[packageName] = label
                    discoveryRevision++
                    publishServices()
                }
            }
            Triple(discoveryRevision != persistedDiscoveryRevision, completedFetchRevision, definition != null)
        }
        if (discovery.first) try {
            persistCurrent()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            _status.value = _status.value.copy(error = "Couldn’t save discovered services.")
        }
        if (!discovery.third) fetchLock.withLock {
            val needsFetch = synchronized(stateLock) {
                // An unknown app queued behind another app's request can consume that full-manifest
                // lookup even when both apps were absent, rather than immediately downloading again.
                !isRemoved(packageName) && configured.none { packageName in it.packages } && attempted.add(packageName) &&
                    completedFetchRevision == discovery.second
            }
            if (needsFetch) refreshLocked()
        }
        lookupAppInfo(packageName)
    }

    private suspend fun rediscoverPackage(packageName: String) {
        if (isEnabled(packageName)) return
        storageLock.withLock {
            val next = synchronized(stateLock) {
                val definition = configured.firstOrNull { packageName in it.packages }
                removed.filterNot { service ->
                    (packageName in service.packages || definition?.let {
                        it.id == service.id || it.packages.any(service.packages::contains)
                    } == true)
                }
            }
            if (next != removed) saveRemoval(next)
        }
    }

    /** The only refresh path that ignores the per-reporting-run negative cache. */
    suspend fun refresh() {
        fetchLock.withLock {
            synchronized(stateLock) { attempted.clear(); appInfoAttempted.clear() }
            try {
                refreshLocked()
            } finally {
                // Avoid another automatic fetch immediately following a manual missing/failed lookup.
                synchronized(stateLock) {
                    attempted.addAll(discovered.keys.filter { pkg -> configured.none { pkg in it.packages } })
                }
            }
        }
        synchronized(stateLock) { discovered.keys.toList() }.forEach { lookupAppInfo(it) }
    }

    private suspend fun refreshLocked() {
        _status.value = RegistryStatus(refreshing = true)
        try {
            val downloaded = ServiceManifestCodec.decode(fetcher())
            val merged = ServiceManifestCodec.merge(bundled, downloaded)
            // Commit the complete validated registry before making it visible to publishers.
            storageLock.withLock {
                val cache = synchronized(stateLock) { encodeCache(downloaded) }
                withContext(Dispatchers.IO) { storage.write(cache) }
                synchronized(stateLock) {
                    manifest = downloaded
                    configured = merged
                    publishServices()
                }
            }
            _status.value = RegistryStatus()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            _status.value = RegistryStatus(error = "Couldn’t refresh service definitions. Basic media support remains available.")
        } finally {
            synchronized(stateLock) { completedFetchRevision++ }
            if (_status.value.refreshing) _status.value = _status.value.copy(refreshing = false)
        }
    }

    private suspend fun persistCurrent() = storageLock.withLock {
        val (cache, revision) = synchronized(stateLock) {
            if (discoveryRevision == persistedDiscoveryRevision) null else encodeCache() to discoveryRevision
        } ?: return@withLock
        withContext(Dispatchers.IO) { storage.write(cache) }
        // Discovery can change while IO is suspended. Only acknowledge the snapshot written;
        // a failed write or a newer discovery remains eligible for persistence on the next event.
        synchronized(stateLock) { persistedDiscoveryRevision = revision }
    }

    private suspend fun lookupAppInfo(packageName: String) = appInfoLock.withLock {
        val needed = synchronized(stateLock) {
            val definition = configured.firstOrNull { packageName in it.packages }
            val missingInfo = if (definition == null) packageName !in categories || packageName !in icons
                else definition.fallbackArtworkUrl == null && definition.packages.none { it in icons }
            !isRemoved(packageName) && packageName in discovered && missingInfo && appInfoAttempted.add(packageName)
        }
        if (!needed) return@withLock
        try {
            // Query the stock listing for known patched aliases; cache under the installed
            // package so removal, rediscovery and cache validation keep their original identity.
            val info = appInfoFetcher(GooglePlayAppLookup.listingPackage(packageName)) ?: return@withLock
            val category = info.category?.takeIf(GooglePlayAppLookup::validCategory)
            val icon = info.iconUrl?.takeIf(GooglePlayAppLookup::validIconUrl)
            if (category == null && icon == null) return@withLock
            storageLock.withLock {
                val next = synchronized(stateLock) {
                    categories.toMap() + (category?.let { mapOf(packageName to it) } ?: emptyMap())
                }
                val nextIcons = synchronized(stateLock) {
                    icons.toMap() + (icon?.let { mapOf(packageName to it) } ?: emptyMap())
                }
                val cache = synchronized(stateLock) { encodeCache(appCategories = next, appIcons = nextIcons) }
                withContext(Dispatchers.IO) { storage.write(cache) }
                synchronized(stateLock) {
                    category?.let { categories[packageName] = it }
                    icon?.let { icons[packageName] = it }
                    publishServices()
                }
            }
        } catch (cancelled: CancellationException) {
            synchronized(stateLock) { appInfoAttempted.remove(packageName) }
            throw cancelled
        } catch (_: Exception) {
            // Offline, delisted, or changed Play pages keep basic presence and retry on the next run/refresh.
        }
    }

    private fun encodeCache(downloaded: List<ServiceDefinition>? = manifest,
        removedServices: List<RemovedService> = removed, appCategories: Map<String, String> = categories.toMap(),
        appIcons: Map<String, String> = icons.toMap()) =
        ServiceManifestCodec.encodeCache(downloaded, discovered.toMap(), removedServices, appCategories, appIcons)

    /** Must be called while holding stateLock. Every emitted collection is an immutable snapshot. */
    private fun publishServices() {
        _services.value = (configured.map(::withAppIcon) + discovered.filterKeys { pkg -> configured.none { pkg in it.packages } }
            .map { (pkg, label) -> generic(pkg, label) }
            ).filterNot { service -> service.packages.any(::isRemoved) }
    }

    private fun generic(packageName: String, label: String) = ServiceDefinition(
        id = packageName, name = label, packages = setOf(packageName),
        mediaKind = GooglePlayAppLookup.mediaKind(categories[packageName]), defaultContent = PresenceContent.TRACK,
        fallbackArtworkUrl = icons[packageName]
    )

    private fun withAppIcon(service: ServiceDefinition): ServiceDefinition =
        if (service.fallbackArtworkUrl != null) service else service.copy(
            fallbackArtworkUrl = service.packages.sorted().firstNotNullOfOrNull { icons[it] })

    companion object {
        const val SOURCE_URL = "https://raw.githubusercontent.com/ChudForks/ChudPresence-Patches/main/presence-services.json"
    }
}

private class AtomicRegistryStorage(file: File) : RegistryStorage {
    private val file = AtomicFile(file)

    override fun read(): String? = try {
        file.openRead().bufferedReader().use { reader ->
            val buffer = CharArray(4096)
            val result = StringBuilder()
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                result.append(buffer, 0, count)
                require(result.length <= ServiceManifestCodec.MAX_CACHE_BYTES) { "Service cache is too large" }
            }
            result.toString()
        }
    } catch (_: java.io.FileNotFoundException) {
        null
    }

    override fun write(contents: String) {
        val stream = file.startWrite()
        try {
            stream.write(contents.toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (failure: Exception) {
            file.failWrite(stream)
            throw failure
        }
    }
}

/** No metadata, app identity, credentials, or playback details are sent to the registry source. */
private class RegistryHttpFetcher {
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    suspend fun fetch(): String = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(Request.Builder().url(ServiceRegistry.SOURCE_URL)
            .header("Accept", "application/json").header("Cache-Control", "no-cache").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(error))
            }

            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        require(it.isSuccessful) { "Registry source returned ${it.code}" }
                        val body = it.body ?: error("Empty service registry")
                        require(body.contentLength() <= ServiceManifestCodec.MAX_BYTES) { "Service registry is too large" }
                        val bytes = body.byteStream().use { input ->
                            val output = ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                require(output.size() + count <= ServiceManifestCodec.MAX_BYTES) { "Service registry is too large" }
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        }
                        bytes.decodeToString(throwOnInvalidSequence = true)
                    }
                }
                if (continuation.isActive) continuation.resumeWith(result)
            }
        })
    }
}
