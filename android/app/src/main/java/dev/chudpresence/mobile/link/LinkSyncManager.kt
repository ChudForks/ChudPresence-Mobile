package dev.chudpresence.mobile.link

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import android.net.Network
import android.util.Log
import androidx.core.content.ContextCompat
import dev.chudpresence.mobile.BuildConfig
import dev.chudpresence.mobile.data.SettingsStore
import dev.chudpresence.mobile.services.ServiceRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A single settings-only worker shared by the visible UI and the user-started reporting service. */
internal class LinkSyncManager(
    private val context: Context,
    private val store: SettingsStore,
    private val registry: ServiceRegistry
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val active = MutableStateFlow(false)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val probe = Channel<Unit>(Channel.CONFLATED)
    private val network = LinkNetworkMonitor(context).observe().stateIn(scope, SharingStarted.Eagerly, null)
    private var boundNetwork: Network? = null
    private var boundApi: LinkApi? = null
    private var foreground = false
    private var reporting = false
    private var reportedInventory: String? = null
    @Volatile private var retryNotBefore = 0L
    private val _state = MutableStateFlow(LinkUiState())
    val state: StateFlow<LinkUiState> = _state.asStateFlow()
    val defaultPhoneName: String = "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(80)

    init {
        scope.launch {
            var previousDevice: String? = null
            var previousChoices: Pair<dev.chudpresence.mobile.data.PresenceDisplaySettings, Map<String, LinkEdit>>? = null
            store.linkSnapshot.collect { snapshot ->
                val pairing = snapshot.pairing
                val device = pairing?.let { "${it.endpoint}/${it.serverId}/${it.deviceId}" }
                val changedDevice = device != previousDevice
                previousDevice = device
                _state.update { current ->
                    current.copy(loaded = true, paired = pairing != null, endpoint = pairing?.endpoint.orEmpty(),
                        phoneName = pairing?.phoneName.orEmpty(), deviceId = pairing?.deviceId.orEmpty(),
                        pendingChanges = snapshot.sync.pending.values.sumOf { it.fields.size },
                        connection = when {
                            pairing == null -> LinkConnection.UNPAIRED
                            snapshot.sync.requiresPairing -> LinkConnection.PAIR_AGAIN
                            changedDevice -> LinkConnection.CONNECTING
                            else -> current.connection
                        },
                        services = if (changedDevice) emptyList() else current.services,
                        activeServices = if (changedDevice) emptySet() else current.activeServices,
                        localOnlyServices = if (changedDevice) emptyList() else current.localOnlyServices,
                        lastSyncedAt = if (changedDevice) null else current.lastSyncedAt)
                }
                // Acknowledging/removing pending writes alone needs no new pass. Local controls
                // and remotely adopted values must both update the last-reported inventory.
                val sharedSettings = snapshot.displaySettings
                val choices = sharedSettings to snapshot.sync.pending
                val oldPending = previousChoices?.second
                if (previousChoices?.first != sharedSettings || snapshot.sync.pending.any { (id, edit) -> oldPending?.get(id) != edit }) requestSync()
                previousChoices = choices
            }
        }
        scope.launch { registry.services.drop(1).collect { requestSync() } }
        scope.launch {
            active.collectLatest { running ->
                if (!running) return@collectLatest
                callbackFlow {
                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(context: Context?, intent: Intent?) {
                            val packageName = intent?.data?.schemeSpecificPart ?: return
                            if (registry.services.value.any { packageName in it.packages }) trySend(Unit)
                        }
                    }
                    val filter = IntentFilter().apply {
                        addAction(Intent.ACTION_PACKAGE_ADDED)
                        addAction(Intent.ACTION_PACKAGE_REMOVED)
                        addAction(Intent.ACTION_PACKAGE_CHANGED)
                        addDataScheme("package")
                    }
                    ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
                    awaitClose { context.unregisterReceiver(receiver) }
                }.buffer(Channel.CONFLATED).collect { requestSync() }
            }
        }
        scope.launch {
            val pairingGate = store.linkSnapshot.map { snapshot ->
                snapshot.pairing?.takeUnless { snapshot.sync.requiresPairing }
                    ?.let { "${it.endpoint}/${it.serverId}/${it.deviceId}" }
            }.distinctUntilChanged()
            combine(active, pairingGate, network) { running, device, route -> Triple(running, device, route) }
                .distinctUntilChanged().collectLatest { (running, device, route) ->
                    if (!running || device == null) return@collectLatest
                    if (route == null) {
                        _state.update { it.copy(connection = LinkConnection.WAITING_FOR_NETWORK, message = null) }
                        return@collectLatest
                    }
                    val pairing = store.linkSnapshot.first().pairing ?: return@collectLatest
                    runSession(pairing, apiFor(route))
                }
        }
    }

    @Synchronized
    fun setForegroundActive(value: Boolean) {
        foreground = value
        active.value = foreground || reporting
        if (value) { requestSync(); probe.trySend(Unit) }
    }

    @Synchronized
    fun setReportingActive(value: Boolean) {
        reporting = value
        active.value = foreground || reporting
    }

    fun requestSync() { wake.trySend(Unit) }

    @Synchronized
    private fun apiFor(route: Network = network.value ?: throw LinkFailure(code = "no_local_network",
        message = "Link is waiting for Wi-Fi. Your local choices and pending changes are saved.")): LinkApi {
        if (boundNetwork != route || boundApi == null) {
            boundNetwork = route
            boundApi = LinkApi(route.socketFactory) { network.value == route }
        }
        return boundApi!!
    }

    private suspend fun runSession(pairing: LinkPairing, api: LinkApi) {
        val retry = LinkRetryPolicy()
        while (currentCoroutineContext().isActive) {
            while (probe.tryReceive().isSuccess) { /* Discard probes already covered by this attempt. */ }
            var verifiedAt: Long? = null
            try {
                awaitRateLimit()
                // A reconnected desktop may have restarted or gained settings capabilities.
                // Report the inventory again even if none of the phone's values changed.
                mutex.withLock { reportedInventory = null }
                while (wake.tryReceive().isSuccess) { /* Initial stream event covers existing changes. */ }
                coroutineScope {
                    launch { api.listen(pairing) {
                        if (verifiedAt == null) {
                            verifiedAt = SystemClock.elapsedRealtime()
                            if (BuildConfig.DEBUG) Log.d("LinkSync", "Settings notifications connected")
                        }
                        requestSync()
                    } }
                    // No periodic timer: the stream's initial revision performs catch-up, and
                    // later revisions/local edits are the only reasons to fetch settings.
                    while (isActive) {
                        wake.receive()
                        while (wake.tryReceive().isSuccess) { }
                        awaitRateLimit()
                        mutex.withLock { refreshLocked() }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (failure is LinkFailure && failure.code == "events_unsupported") {
                    var upgraded = false
                    try {
                        // Compatibility with an older desktop. New Link versions never use this timer.
                        while (currentCoroutineContext().isActive) {
                            awaitRateLimit()
                            mutex.withLock { refreshLocked() }
                            retry.reset()
                            if (api.supportsEvents) { upgraded = true; break }
                            withTimeoutOrNull(60_000L) { wake.receive() }
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (legacyFailure: Exception) { recordFailure(legacyFailure, pairing) }
                    if (upgraded) continue
                } else recordFailure(failure, pairing)
                if (store.linkSnapshot.first().sync.requiresPairing) return
                if (verifiedAt?.let { SystemClock.elapsedRealtime() - it >= 120_000L } == true) retry.reset()
                retry.failed(SystemClock.elapsedRealtime())
                if (BuildConfig.DEBUG) Log.d("LinkSync", "Desktop unavailable; reconnect delayed")
                // Local edits stay queued and cannot defeat offline backoff. Only a new
                // foreground/manual probe may shorten it; HTTP 429 remains a hard deadline.
                while (currentCoroutineContext().isActive) {
                    val remaining = maxOf(retry.retryAtMs, retryNotBefore) - SystemClock.elapsedRealtime()
                    if (remaining <= 0) break
                    val explicit = withTimeoutOrNull(remaining) { probe.receive(); true } == true
                    if (explicit) retry.allowImmediateRetry()
                }
            }
        }
    }

    private suspend fun awaitRateLimit() {
        while (true) {
            val remaining = retryNotBefore - SystemClock.elapsedRealtime()
            if (remaining <= 0) return
            delay(remaining)
        }
    }

    suspend fun pair(address: String, code: String, phoneName: String) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, message = null, notice = null) }
        try {
            mutex.withLock {
                checkRateLimit()
                val api = apiFor()
                val endpoint = normalizeLinkEndpoint(address)
                require(Regex("[0-9]{8}").matches(code)) { "Enter the eight-digit Pair phone code from the desktop app." }
                val name = phoneName.trim()
                require(name.isNotEmpty() && name.length <= 80 && name.none(Char::isISOControl)) { "Enter a phone name of 1–80 characters." }
                val pairing = api.pair(endpoint, code, name)
                store.saveLinkPairing(pairing)
                reportedInventory = null
                _state.update { it.copy(connection = LinkConnection.CONNECTING, notice = "Phone paired. Choose which matching apps to sync below.") }
                // The session opens its stream before the initial inventory/settings catch-up.
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            recordFailure(failure, store.linkSnapshot.first().pairing, pairingAttempt = true)
        } finally {
            _state.update { it.copy(busy = false) }
        }
        requestSync()
    }

    // Incoming changes keep controls steady; only tapping Sync now shows loading feedback.
    suspend fun refresh() {
        if (_state.value.busy || _state.value.manualSyncing) return
        _state.update { it.copy(manualSyncing = true) }
        try {
            mutex.withLock { refreshLocked() }
            probe.trySend(Unit)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: Exception) {
            recordFailure(failure, store.linkSnapshot.first().pairing)
        } finally {
            _state.update { it.copy(manualSyncing = false) }
        }
    }

    /** Uses the exact syncVersion the user reviewed; a stale selection must be reviewed again. */
    suspend fun selectSync(service: LinkService, enabled: Boolean, sourceDeviceId: String? = null,
        fields: Set<LinkField> = service.supportedFields) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, message = null, notice = null) }
        try {
            mutex.withLock {
                checkRateLimit()
                val api = apiFor()
                val local = store.linkSnapshot.first()
                val pairing = local.pairing ?: return@withLock
                if (local.sync.requiresPairing) return@withLock
                api.selectSync(pairing, service, enabled, sourceDeviceId, fields)
                refreshLocked()
                if (!enabled) _state.update { it.copy(notice = "${service.name} sync is off. Each device keeps its current settings.") }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            recordFailure(failure, store.linkSnapshot.first().pairing)
            if (failure is LinkFailure && failure.status == 409) {
                val notice = failure.message
                try { mutex.withLock { refreshLocked() } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (refreshFailure: Exception) { recordFailure(refreshFailure, store.linkSnapshot.first().pairing) }
                _state.update { it.copy(notice = notice) }
            }
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    suspend fun disconnect() {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, message = null, notice = null) }
        try {
            mutex.withLock {
                val local = store.linkSnapshot.first()
                val removedOnDesktop = local.pairing?.let { pairing ->
                    if (local.sync.requiresPairing || SystemClock.elapsedRealtime() < retryNotBefore) false
                    else try { apiFor().unpair(pairing); true }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { false }
                } ?: true
                store.clearLinkPairing()
                reportedInventory = null
                _state.update { it.copy(notice = if (removedOnDesktop) "Phone unpaired. Your app choices are saved on this phone."
                    else "Pairing removed from this phone. Remove its old entry in the desktop app when available.") }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            _state.update { it.copy(message = "Couldn’t remove the saved pairing. Please try again.") }
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    fun dismissNotice() { _state.update { it.copy(notice = null) } }

    private suspend fun refreshLocked() {
        val local = store.linkSnapshot.first()
        val pairing = local.pairing ?: return
        if (local.sync.requiresPairing) return
        checkRateLimit()
        val api = apiFor()
        val installed = registry.services.value.filter { definition ->
            definition.packages.any { packageName ->
                runCatching { context.packageManager.getApplicationInfo(packageName, 0).enabled }.getOrDefault(false)
            }
        }
        val identities = installed.groupBy { linkServiceId(it.id) }
        val candidates = installed.mapNotNull { definition ->
            val id = linkServiceId(definition.id) ?: return@mapNotNull null
            if (identities[id]?.size != 1) return@mapNotNull null
            LinkInventoryItem(definition, local.displaySettings.linkValues(definition))
        }.sortedBy { it.id }
        // Keep the complete advertised inventory within both protocol limits.
        var inventoryBytes = "{\"services\":[]}".length
        val inventory = candidates.take(128).takeWhile { item ->
            inventoryBytes += item.json().toString().toByteArray(Charsets.UTF_8).size + 1
            inventoryBytes <= 32 * 1024
        }
        val fingerprint = pairing.deviceId + inventory.joinToString { it.json().toString() }
        var services = api.services(pairing, inventory.takeIf { fingerprint != reportedInventory })
        reportedInventory = fingerprint
        val definitions = inventory.associate { it.id to it.definition }
        var choices = services.associate { it.id to it.displayChoices }
        fun selections(): Map<String, LinkSelection> = services.filter {
            it.shared && it.syncEnabled && it.syncFields.isNotEmpty() &&
                it.supportedFields.containsAll(it.syncFields) && it.id in definitions
        }.associate { it.id to LinkSelection(it.syncFields, it.syncVersion) }
        var eligible = selections().keys
        store.updateLinkDiscovery(pairing, selections())
        // Read availability before writing: an enabled service may be paused by an incompatible peer.
        // Keep the saved edit baseVersion, rather than rebasing it onto this newer read.
        // Compare taps against the beginning of the pass, including inventory/discovery I/O.
        // Submitted fields carry their own generations and are acknowledged independently.
        fun LinkLocalSnapshot.withPassEdits() = copy(sync = sync.copy(localEdits = local.sync.localEdits))
        var expected = store.linkSnapshot.first().withPassEdits()
        val remote = if (eligible.isEmpty()) LinkSettingsSnapshot(emptyMap()) else api.settings(pairing, emptyMap())
        var activeServices = eligible.intersect(remote.services.keys)
        var sent = expected.sync.pending.filter { (id, edit) -> id in activeServices && edit.baseVersion > 0 }
            .mapValues { (id, edit) -> edit.copy(fields = edit.fields.filter { (field, change) ->
                field in expected.sync.fieldsFor(id) && (field != LinkField.DISPLAY || change.value.content in choices[id].orEmpty())
            }) }.filterValues { it.fields.isNotEmpty() }
        var availabilityChanged = false
        val result = if (sent.isEmpty()) remote else try {
            api.settings(pairing, sent)
        } catch (failure: LinkFailure) {
            if (failure.code !in setOf("sync_not_enabled", "field_not_synced", "service_not_shared", "unsupported_display", "service_not_reported")) throw failure
            // Refresh availability once, without resubmitting a rejected write.
            availabilityChanged = true
            services = api.services(pairing)
            choices = services.associate { it.id to it.displayChoices }
            eligible = selections().keys
            store.updateLinkDiscovery(pairing, selections())
            expected = store.linkSnapshot.first().withPassEdits()
            sent = emptyMap()
            api.settings(pairing, emptyMap()).also { activeServices = eligible.intersect(it.services.keys) }
        }
        store.applyLinkSettings(pairing, expected, sent, result, definitions, activeServices, choices)
        val unsupported = expected.sync.pending.any { (id, edit) -> id in activeServices &&
            edit.fields[LinkField.DISPLAY]?.value?.content?.let { it !in choices[id].orEmpty() } == true }
        val conflicts = result.conflicts.mapNotNull { id -> services.firstOrNull { it.id == id }?.name }
        _state.update { current -> current.copy(connection = LinkConnection.CONNECTED, services = services,
            activeServices = activeServices, lastSyncedAt = System.currentTimeMillis(), message = null,
            localOnlyServices = installed.filter { local.displaySettings.contentFor(it).toLinkDisplay() == null }.map { it.name },
            notice = when {
                conflicts.isNotEmpty() -> "${conflicts.joinToString()}: a newer desktop setting was kept. You can change it again in Activities."
                unsupported -> "A choice unavailable on another linked device was reset to the shared choice. Turn off that app’s sync to choose independently."
                availabilityChanged -> "An app’s sync availability changed. The current selections have been refreshed."
                candidates.size > inventory.size -> "Link can report up to 128 apps within its inventory size limit. ${candidates.size - inventory.size} additional apps are staying local."
                else -> current.notice
            }) }
        if (BuildConfig.DEBUG) Log.d("LinkSync", "Settings sync completed")
    }

    private fun checkRateLimit() {
        if (SystemClock.elapsedRealtime() < retryNotBefore) {
            throw LinkFailure(status = 429, code = "local_rate_limit", message = "Link is receiving too many requests. Try again in a minute.")
        }
    }

    private suspend fun recordFailure(failure: Exception, pairing: LinkPairing?, pairingAttempt: Boolean = false) {
        val apiFailure = failure as? LinkFailure
        if (apiFailure?.status == 429 && apiFailure.code != "local_rate_limit") {
            synchronized(this) { retryNotBefore = maxOf(retryNotBefore, SystemClock.elapsedRealtime() + apiFailure.retryAfterMs) }
            requestSync()
        }
        // Another Wi-Fi can reuse the saved private IP. A different/unsupported server
        // is offline, not a reason to invalidate the home pairing or send it a token.
        val attemptedPairing = apiFailure?.verifiedPairing
        val needsPairing = !pairingAttempt && apiFailure?.status == 401 && attemptedPairing != null &&
            attemptedPairing.sameDevice(pairing) && attemptedPairing.token == pairing?.token && apiFailure.authenticationIsCurrent()
        if (needsPairing) store.requireLinkPairing(attemptedPairing!!)
        val latest = store.linkSnapshot.first()
        val target = attemptedPairing ?: pairing
        if (target != null && (!target.sameDevice(latest.pairing) || target.token != latest.pairing?.token)) return
        val message = when (failure) {
            is LinkFailure -> failure.message
            is IllegalArgumentException -> if (pairingAttempt) failure.message else "Link returned an invalid response. Check that the desktop app is up to date."
            is java.io.IOException -> "Can’t reach Link. Keep the desktop app open and connect both devices to the same network. Local app choices are saved."
            else -> "Couldn’t sync Link settings. Your local app choices are saved. Try refreshing."
        }
        _state.update { it.copy(message = message,
            connection = when {
                latest.sync.requiresPairing -> LinkConnection.PAIR_AGAIN
                network.value == null && it.paired -> LinkConnection.WAITING_FOR_NETWORK
                it.paired -> LinkConnection.OFFLINE
                else -> LinkConnection.UNPAIRED
            }) }
    }
}
