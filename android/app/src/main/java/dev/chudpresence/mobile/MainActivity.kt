package dev.chudpresence.mobile

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.webkit.CookieManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.viewModels
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.chudpresence.mobile.data.PresenceContent
import dev.chudpresence.mobile.data.PresenceDisplaySettings
import dev.chudpresence.mobile.data.PresenceService
import dev.chudpresence.mobile.data.ServiceActivityOption
import dev.chudpresence.mobile.reporting.LocalDiscordPresence
import dev.chudpresence.mobile.reporting.DiscordRpcBinding
import dev.chudpresence.mobile.reporting.ReportingService
import dev.chudpresence.mobile.reporting.ReportingStatus
import dev.chudpresence.mobile.reporting.effectiveServiceDefinition
import dev.chudpresence.mobile.services.ServiceDefinition
import dev.chudpresence.mobile.services.ServiceDefinitions
import dev.chudpresence.mobile.link.LinkUiState
import dev.chudpresence.mobile.link.LinkService
import dev.chudpresence.mobile.link.LinkField
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val model: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LocalDiscordPresence.initialize(this)
        enableEdgeToEdge()
        setContent {
            PresenceTheme { PresenceScreen(model) }
        }
    }

    override fun onStart() {
        super.onStart()
        (application as ChudPresenceApplication).linkSync.setForegroundActive(true)
    }

    override fun onStop() {
        (application as ChudPresenceApplication).linkSync.setForegroundActive(false)
        super.onStop()
    }

    override fun bindService(service: Intent, connection: ServiceConnection, flags: Int): Boolean =
        if (service.action == DiscordRpcBinding.SERVICE_ACTION) DiscordRpcBinding.bind(this, service, connection, flags)
        else super.bindService(service, connection, flags)

    override fun unbindService(connection: ServiceConnection) {
        if (!DiscordRpcBinding.unbindIfOwned(this, connection)) super.unbindService(connection)
    }
}

internal data class ScreenState(
    val link: LinkUiState = LinkUiState(),
    val reporting: ReportingStatus = ReportingStatus(),
    val displaySettings: PresenceDisplaySettings = PresenceDisplaySettings(),
    val services: List<ServiceDefinition> = ServiceDefinitions.builtIns,
    val refreshingServices: Boolean = false,
    val serviceRefreshError: String? = null,
    val crunchyrollConnected: Boolean = false,
    val twitchConnected: Boolean = false,
    val twitchConfigured: Boolean = false,
    val twitchConnecting: Boolean = false,
    val twitchUserCode: String? = null,
    val twitchVerificationUri: String? = null,
    val error: String? = null,
    val setupCompleted: Boolean? = null,
    val savingSetup: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as ChudPresenceApplication
    internal val mediaSessions = app.sessions.sessions
    private val error = MutableStateFlow<String?>(null)
    private val savingSetup = MutableStateFlow(false)
    private val twitchConnection = MutableStateFlow(Triple(false, null as String?, null as String?))
    private var twitchConnectionJob: Job? = null
    private val coreState = combine(ReportingService.status, app.settingsStore.displaySettings,
        app.settingsStore.crunchyrollSession) { reporting, settings, crunchyroll ->
        ScreenState(reporting = reporting, displaySettings = settings,
            crunchyrollConnected = crunchyroll != null, twitchConfigured = app.twitchApi.configured)
    }
    private val baseState = combine(coreState, app.settingsStore.twitchSession, app.settingsStore.setupCompleted) { current, twitch, completed ->
        current.copy(twitchConnected = twitch != null, setupCompleted = completed)
    }
    private val availableServices = combine(app.serviceRegistry.services, app.sessions.sessions,
        app.settingsStore.displaySettings) { definitions, sessions, settings ->
        definitions.map { definition ->
            if (settings.hasAdditionalEpisodeChoice(definition)) definition.copy(supportsEpisodes = true)
            else sessions.firstOrNull { it.packageName in definition.packages && it.seasonNumber != null && it.episodeNumber != null }
                ?.let { effectiveServiceDefinition(it, definition) } ?: definition
        }
    }
    private val serviceState = combine(baseState, availableServices, app.serviceRegistry.status) { current, services, registry ->
        current.copy(services = services, refreshingServices = registry.refreshing, serviceRefreshError = registry.error)
    }
    internal val state = combine(serviceState, error, twitchConnection, savingSetup, app.linkSync.state) { current, issue, connection, saving, link ->
        current.copy(error = issue, link = link, twitchConnecting = connection.first,
            twitchUserCode = connection.second, twitchVerificationUri = connection.third, savingSetup = saving)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScreenState())

    fun completeSetup(onComplete: () -> Unit) {
        if (savingSetup.value) return
        savingSetup.value = true
        viewModelScope.launch {
            try {
                app.settingsStore.completeSetup()
                error.value = null
                onComplete()
            } catch (failure: java.io.IOException) {
                error.value = "Couldn’t save setup. Please try again."
            } finally {
                savingSetup.value = false
            }
        }
    }
    fun setServiceDisplayContent(service: PresenceService, content: PresenceContent) = viewModelScope.launch {
        try {
            app.settingsStore.setServiceDisplayContent(service, content)
            app.linkSync.requestSync()
            error.value = null
        } catch (failure: java.io.IOException) {
            error.value = "Couldn’t save activity settings. Please try again."
        }
    }
    fun setServiceDisplayContent(service: ServiceDefinition, content: PresenceContent) = viewModelScope.launch {
        try {
            app.settingsStore.setServiceDisplayContent(service, content)
            app.linkSync.requestSync()
            error.value = null
        } catch (failure: java.io.IOException) {
            error.value = "Couldn’t save activity settings. Please try again."
        }
    }
    fun setServiceActivityOption(service: ServiceDefinition, option: ServiceActivityOption, value: Boolean) = viewModelScope.launch {
        try {
            app.settingsStore.setServiceActivityOption(service, option, value)
            error.value = null
        } catch (failure: java.io.IOException) {
            error.value = "Couldn’t save activity settings. Please try again."
        }
    }
    fun refreshServiceDefinitions() = viewModelScope.launch { app.serviceRegistry.refresh() }
    internal val linkPhoneName get() = app.linkSync.defaultPhoneName
    internal fun pairLink(address: String, code: String, phoneName: String) = viewModelScope.launch {
        app.linkSync.pair(address, code, phoneName)
    }
    internal fun refreshLink() = viewModelScope.launch { app.linkSync.refresh() }
    internal fun setLinkServiceSync(service: LinkService, enabled: Boolean, sourceDeviceId: String? = null,
        fields: Set<LinkField> = service.supportedFields) = viewModelScope.launch {
        app.linkSync.selectSync(service, enabled, sourceDeviceId, fields)
    }
    internal fun disconnectLink() = viewModelScope.launch { app.linkSync.disconnect() }
    internal fun dismissLinkNotice() = app.linkSync.dismissNotice()
    fun removeService(serviceId: String) = viewModelScope.launch {
        try {
            app.serviceRegistry.remove(serviceId)
            error.value = null
        } catch (failure: java.io.IOException) {
            error.value = "Couldn’t remove app. Please try again."
        }
    }
    fun disconnectCrunchyroll() = viewModelScope.launch {
        try {
            app.settingsStore.clearCrunchyrollSession()
        } catch (failure: java.io.IOException) {
            error.value = "Couldn’t disconnect Crunchyroll. Please try again."
            return@launch
        }
        app.crunchyrollApi.clear()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }

    fun connectTwitch() {
        if (twitchConnectionJob?.isActive == true) return
        twitchConnectionJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            twitchConnection.value = Triple(true, null, null)
            error.value = null
            try {
                val authorization = app.twitchApi.startDeviceAuthorization()
                twitchConnection.value = Triple(true, authorization.userCode, authorization.verificationUri)
                val session = app.twitchApi.awaitDeviceAuthorization(authorization)
                app.settingsStore.saveTwitchSession(session)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error.value = failure.message ?: "Twitch connection failed."
            } finally {
                // A cancelled attempt can finish after the user starts a replacement.
                if (twitchConnectionJob === coroutineContext[Job]) {
                    twitchConnection.value = Triple(false, null, null)
                }
            }
        }
        twitchConnectionJob?.start()
    }

    fun cancelTwitchConnection() {
        twitchConnectionJob?.cancel()
        twitchConnectionJob = null
        twitchConnection.value = Triple(false, null, null)
    }

    fun disconnectTwitch() = viewModelScope.launch {
        cancelTwitchConnection()
        val previous = try {
            app.settingsStore.twitchSession.firstOrNull().also {
                app.settingsStore.clearTwitchSession()
            }
        } catch (failure: java.io.IOException) {
            error.value = "Couldn’t disconnect Twitch. Please try again."
            return@launch
        }
        app.twitchApi.clear()
        // Finish local disconnect before the network request; a new sign-in must survive
        // a slow or failed revocation of the old session.
        if (previous != null) try {
            app.twitchApi.revoke(previous)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Revocation is best effort after the user has removed the local credentials.
        }
    }
}

@Composable
private fun PresenceScreen(model: MainViewModel) {
    val state by model.state.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var permissionRevision by remember { mutableIntStateOf(0) }
    var showingSettings by rememberSaveable { mutableStateOf(false) }
    var settingsSection by rememberSaveable { mutableStateOf(SettingsSection.ACTIVITIES) }
    var replaySetup by rememberSaveable { mutableStateOf(false) }
    var setupStep by rememberSaveable { mutableIntStateOf(0) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permissionRevision++ }
    val crunchyrollLogin = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    val openTwitch: () -> Unit = {
        state.twitchVerificationUri?.let { uri ->
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri))) }
        }
    }
    LaunchedEffect(state.twitchVerificationUri) {
        if (state.twitchVerificationUri != null) openTwitch()
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permissionRevision++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val listenerEnabled = remember(permissionRevision) {
        androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }
    val notificationsGranted = remember(permissionRevision) {
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    val batteryExempt = remember(permissionRevision) {
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    }
    val discordInstalled = remember(permissionRevision) { LocalDiscordPresence.isDiscordInstalled() }
    val ready = discordInstalled && listenerEnabled && notificationsGranted
    val running = state.reporting.running
    val inSetup = state.setupCompleted == false || replaySetup
    val openSettings: (SettingsSection) -> Unit = { section ->
        settingsSection = section
        showingSettings = true
    }
    BackHandler(enabled = !state.savingSetup && (showingSettings || replaySetup || (inSetup && setupStep > 0))) {
        when {
            inSetup && setupStep > 0 -> setupStep--
            replaySetup -> { replaySetup = false; showingSettings = true }
            else -> showingSettings = false
        }
    }
    val connectionAccess: @Composable () -> Unit = {
        DashboardCard("Connection & access", if (ready) "READY TO REPORT" else "FINISH YOUR SETUP") {
            SetupProgress(listOf(discordInstalled, listenerEnabled, notificationsGranted).count { it })
            SetupStep("01", "Discord app", if (discordInstalled) "Discord is installed; make sure you are signed in."
                else "Install Discord and sign in on this phone.", discordInstalled)
            HorizontalDivider()
            SetupStep("02", "Media access", "Read active media through notification access.", listenerEnabled)
            if (!listenerEnabled) OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }, modifier = Modifier.fillMaxWidth()) { Text("Grant media access") }
            HorizontalDivider()
            SetupStep("03", "Reporting notification", "Keep a visible control while reporting runs.", notificationsGranted)
            if (Build.VERSION.SDK_INT >= 33 && !notificationsGranted) OutlinedButton(onClick = { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }, modifier = Modifier.fillMaxWidth()) { Text("Allow notification") }
            HorizontalDivider()
            SetupStep("04", "Background reliability", "Recommended for reporting with your screen off.", batteryExempt)
            if (!batteryExempt) OutlinedButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
            }, modifier = Modifier.fillMaxWidth()) { Text("Allow background reporting") }
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (state.setupCompleted == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
            // Replace the complete page subtree when its identity changes. Keying only the
            // remembered scroll state can corrupt Compose's layout-node updates on navigation.
            key(inSetup, showingSettings, setupStep, if (showingSettings && !inSetup) settingsSection else null) {
                Column(Modifier.widthIn(max = 600.dp).fillMaxSize()) {
                    Box(Modifier.padding(horizontal = 24.dp)) {
                        PresenceHeader {
                            when {
                                inSetup && replaySetup -> TextButton(onClick = { replaySetup = false; showingSettings = true },
                                    enabled = !state.savingSetup) { Text("Close") }
                                !inSetup && showingSettings -> TextButton(onClick = { showingSettings = false }) { Text("Done") }
                                !inSetup -> TextButton(onClick = { openSettings(SettingsSection.ACTIVITIES) }) { Text("Settings") }
                            }
                        }
                    }
                    if (showingSettings && !inSetup) {
                        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 20.dp),
                            verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            Text("Settings", style = MaterialTheme.typography.headlineLarge)
                            SettingsSections(settingsSection) { settingsSection = it }
                        }
                    }
                    val scrollState = rememberScrollState()
                    Column(Modifier.weight(1f).verticalScroll(scrollState).padding(horizontal = 24.dp).padding(bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        state.error?.let { issue ->
                            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(8.dp)) {
                                Text(issue, Modifier.fillMaxWidth().padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                            }
                        }
                        when {
                            inSetup -> {
                                FirstTimeSetupHeading(setupStep)
                                when (setupStep) {
                                    0 -> connectionAccess()
                                    1 -> EnrichmentSettings(state, model, openTwitch) {
                                        crunchyrollLogin.launch(Intent(context, CrunchyrollLoginActivity::class.java))
                                    }
                                    else -> ActivitySettings(state.displaySettings, model, state.services,
                                        state.refreshingServices, state.serviceRefreshError, state.link)
                                }
                            }
                            showingSettings -> {
                                when (settingsSection) {
                                    SettingsSection.ACTIVITIES -> ActivitySettings(state.displaySettings, model, state.services,
                                        state.refreshingServices, state.serviceRefreshError, state.link)
                                    SettingsSection.CONNECTIONS -> EnrichmentSettings(state, model, openTwitch) {
                                        crunchyrollLogin.launch(Intent(context, CrunchyrollLoginActivity::class.java))
                                    }
                                    SettingsSection.LINK -> LinkSettings(state, model)
                                    SettingsSection.ACCESS -> {
                                        connectionAccess()
                                        TextButton(onClick = { setupStep = 0; replaySetup = true }, modifier = Modifier.fillMaxWidth()) {
                                            Text("Run setup again")
                                        }
                                    }
                                }
                            }
                            else -> {
                                PresenceHero(running, discordInstalled, state.reporting.error != null) {
                                    if (discordInstalled || running) {
                                        Button(onClick = { if (running) ReportingService.stop(context) else ReportingService.start(context) },
                                            enabled = running || ready, modifier = Modifier.testTag("reporting-toggle").fillMaxWidth()
                                                .heightIn(min = 54.dp), shape = RoundedCornerShape(12.dp)) {
                                            PresenceSymbol(if (running) PresenceSymbolKind.STOP else PresenceSymbolKind.PLAY,
                                                Modifier.size(18.dp), LocalContentColor.current)
                                            Spacer(Modifier.width(10.dp))
                                            Text(if (running) "Stop reporting" else "Start reporting")
                                        }
                                        if (!ready && !running) TextButton(onClick = { openSettings(SettingsSection.ACCESS) }) { Text("Finish connection & access") }
                                    } else TextButton(onClick = { openSettings(SettingsSection.ACCESS) }) { Text("Check connection & access") }
                                }
                                MediaActivityCard(state.reporting, state.displaySettings)
                                DashboardShortcuts(state.services.size, listOf(state.twitchConnected, state.crunchyrollConnected).count { it },
                                    ready, openSettings, state.link)
                                Text("Reporting stays off after a reboot.", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (inSetup) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (setupStep > 0) OutlinedButton(onClick = { setupStep-- }, enabled = !state.savingSetup,
                                modifier = Modifier.heightIn(min = 52.dp)) { Text("Back") }
                            Button(onClick = {
                                if (setupStep < 2) setupStep++
                                else model.completeSetup { replaySetup = false; showingSettings = false }
                            }, enabled = !state.savingSetup, modifier = Modifier.weight(1f).heightIn(min = 52.dp), shape = RoundedCornerShape(8.dp)) {
                                Text(when {
                                    state.savingSetup -> "Saving…"
                                    setupStep == 2 -> "Finish setup"
                                    setupStep == 1 && !state.twitchConnected && !state.crunchyrollConnected -> "Skip for now"
                                    else -> "Continue"
                                })
                            }
                        }
                    }
                }
            }
        }
    }
}
