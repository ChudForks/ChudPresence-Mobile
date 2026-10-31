package dev.chudpresence.mobile

import android.content.pm.ApplicationInfo
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.Role
import androidx.core.graphics.drawable.toBitmap
import dev.chudpresence.mobile.data.PresenceContent
import dev.chudpresence.mobile.data.PresenceDisplaySettings
import dev.chudpresence.mobile.data.ServiceActivityOption
import dev.chudpresence.mobile.data.ServiceActivityOptions
import dev.chudpresence.mobile.media.MediaSnapshot
import dev.chudpresence.mobile.services.ServiceDefinition
import dev.chudpresence.mobile.services.ServiceDefinitions
import dev.chudpresence.mobile.link.LinkUiState
import dev.chudpresence.mobile.link.LinkField
import dev.chudpresence.mobile.link.linkServiceId
import dev.chudpresence.mobile.link.toLinkDisplay

@Composable
internal fun ActivitySettings(settings: PresenceDisplaySettings, model: MainViewModel,
    services: List<ServiceDefinition> = ServiceDefinitions.builtIns,
    refreshingServices: Boolean = false, refreshError: String? = null, link: LinkUiState = LinkUiState()) {
    var previewContent by rememberSaveable { mutableStateOf(PresenceContent.ARTIST) }
    var showingServiceHelp by rememberSaveable { mutableStateOf(false) }
    val sessions by model.mediaSessions.collectAsState()
    DashboardCard("Preview example", "COMPACT DISCORD ACTIVITY") {
        DiscordProfilePreview(previewContent)
        DisplayContentSelector(previewContent) { previewContent = it }
        Text(when (previewContent) {
            PresenceContent.APP_NAME -> "App name shows the media app, like YouTube Music."
            PresenceContent.TRACK -> "Track shows the song, video, episode, or stream title."
            PresenceContent.ARTIST -> "Artist shows the artist, creator, or series name."
            PresenceContent.SEASON_EPISODE -> "Season & episode shows the episode’s season and number."
        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Preview only. Set your app choices below.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    DashboardCard("App activities", "YOUR APPS") {
        Text("Manage what each app shares on Discord. Changes apply immediately.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        services.forEachIndexed { index, service ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            key(service.id) {
                val preferredPackage = sessions.filter { it.packageName in service.packages }
                    .maxWithOrNull(compareBy<MediaSnapshot> {
                        when (it.playbackState) { "playing" -> 2; "paused" -> 1; else -> 0 }
                    }.thenBy { it.playingSince })?.packageName
                val linkId = linkServiceId(service.id)
                val displayLinked = linkId in link.activeServices && link.services.any {
                    it.id == linkId && LinkField.DISPLAY in it.syncFields
                }
                ServiceActivitySelector(service, settings.contentFor(service),
                    preferredPackage = preferredPackage,
                    enabled = settings.optionsFor(service).enabled,
                    linked = displayLinked && settings.contentFor(service).toLinkDisplay() != null,
                    remove = { model.removeService(service.id) },
                    select = { model.setServiceDisplayContent(service, it) })
                ServiceActivityControls(service, settings.optionsFor(service)) { option, value ->
                    model.setServiceActivityOption(service, option, value)
                }
            }
        }
        if (services.isEmpty()) Text("No apps added. Play media while reporting to detect an app.",
            style = MaterialTheme.typography.bodySmall)
        Text("Disable a service to stop sharing until you enable it again. Remove hides it from this list until its media is detected again while reporting.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = model::refreshServiceDefinitions, enabled = !refreshingServices,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("refresh-service-definitions")) {
            Text(if (refreshingServices) "Refreshing service definitions…" else "Refresh service definitions")
        }
        refreshError?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        TextButton(onClick = { showingServiceHelp = !showingServiceHelp },
            modifier = Modifier.testTag("service-support-toggle").semantics {
                contentDescription = if (showingServiceHelp) "Hide service support details" else "Show service support details"
            }) {
            Text(if (showingServiceHelp) "Hide service support details" else "About service support")
        }
        if (showingServiceHelp) {
            Text("Other media apps appear when reporting detects them. Titles and playback information work automatically.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Crunchyroll’s season and episode numbers require enrichment. Hulu’s metadata patch adds titles, series, artwork, and episode numbers. Missing details fall back to the title or app name.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Generic apps use their Google Play category: Music & Audio sets Listening; Video Players & Editors and Entertainment set Watching. Basic support continues if lookup is unavailable.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ServiceActivitySelector(service: ServiceDefinition, selected: PresenceContent,
    preferredPackage: String?, enabled: Boolean, linked: Boolean, remove: () -> Unit, select: (PresenceContent) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)
                .semantics { contentDescription = "${service.name} activity: ${service.contentLabel(selected)}" }
                .testTag("service-choice-${service.id}").clickable(role = Role.Button) { expanded = true }
                .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ServiceAppIcon(service, preferredPackage)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(service.name, style = MaterialTheme.typography.titleSmall)
                    Text(if (!enabled) "Disabled" else service.contentLabel(selected) + if (linked) " · Linked" else "", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                PresenceSymbol(PresenceSymbolKind.CHEVRON, Modifier.size(16.dp), MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                service.options.forEach { option ->
                    val active = selected == option
                    DropdownMenuItem(text = { Text(service.contentLabel(option)) },
                        leadingIcon = { Text(if (active) "✓" else "") },
                        modifier = Modifier.semantics { this.selected = active }
                            .testTag("service-option-${service.id}-${option.wireValue}"),
                        onClick = { expanded = false; select(option) })
                }
            }
        }
        TextButton(onClick = remove, modifier = Modifier.testTag("remove-service-${service.id}")
            .semantics { contentDescription = "Remove ${service.name}" }) {
            Text("Remove", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ServiceActivityControls(service: ServiceDefinition, options: ServiceActivityOptions,
    change: (ServiceActivityOption, Boolean) -> Unit) {
    var expanded by rememberSaveable(service.id) { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded },
        modifier = Modifier.testTag("service-controls-${service.id}").semantics {
            contentDescription = if (expanded) "Hide ${service.name} controls" else "Show ${service.name} controls"
        }) {
        Text(if (expanded) "Hide controls" else "Activity controls")
    }
    if (expanded) {
        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            ServiceActivityToggle(service, ServiceActivityOption.ENABLED, "Enable service",
                "Share this app’s activity on Discord.", options.enabled, change)
            ServiceActivityToggle(service, ServiceActivityOption.SHOW_WHILE_PAUSED, "Show while paused",
                "Keep the activity visible when playback is paused.", options.showWhilePaused, change)
            ServiceActivityToggle(service, ServiceActivityOption.ARTWORK, "Artwork",
                "Show cover art or a video thumbnail. Off uses the app icon.", options.showArtwork, change)
            ServiceActivityToggle(service, ServiceActivityOption.PLAYBACK_PROGRESS, "Playback progress",
                "Show this item’s progress. Off counts your time in the app.", options.showPlaybackProgress, change)
            if (service.supportsActionButtons) {
                ServiceActivityToggle(service, ServiceActivityOption.ACTION_BUTTONS, "Action buttons",
                    "Show watch, play, or creator buttons when available.", options.showActionButtons, change)
            }
        }
    }
}

@Composable
private fun ServiceActivityToggle(service: ServiceDefinition, option: ServiceActivityOption, title: String,
    description: String, checked: Boolean, change: (ServiceActivityOption, Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)
        .testTag("service-control-${service.id}-${option.storageKey}")
        .toggleable(value = checked, role = Role.Switch, onValueChange = { change(option, it) })
        .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun ServiceAppIcon(service: ServiceDefinition, preferredPackage: String?) {
    val context = LocalContext.current
    val icon = remember(context, service.packages, preferredPackage) {
        val manager = context.packageManager
        service.packages.mapNotNull { name ->
            runCatching { manager.getApplicationInfo(name, 0) }.getOrNull()?.takeIf { it.enabled }
        }.sortedWith(compareByDescending<ApplicationInfo> { it.packageName == preferredPackage }
            .thenBy { it.flags and ApplicationInfo.FLAG_SYSTEM != 0 })
            .firstNotNullOfOrNull { info ->
                runCatching { manager.getApplicationIcon(info).toBitmap(128, 128).asImageBitmap() }.getOrNull()
            }
    }
    Surface(Modifier.size(36.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape = RoundedCornerShape(10.dp)) {
        if (icon != null) {
            Image(icon, contentDescription = null, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)))
        } else {
            Box(contentAlignment = Alignment.Center) {
                Text(service.name.take(1).uppercase(), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A sample compact profile row, deliberately independent of the user's live media or account. */
@Composable
internal fun DiscordProfilePreview(selected: PresenceContent) {
    val activity = when (selected) {
        PresenceContent.APP_NAME -> "YouTube Music"
        PresenceContent.TRACK -> "The Pretender"
        PresenceContent.ARTIST -> "Foo Fighters"
        PresenceContent.SEASON_EPISODE -> "Season 1 · Episode 1"
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(Modifier.fillMaxWidth().clearAndSetSemantics {
            contentDescription = "Discord preview. You, online. Listening to $activity."
            liveRegion = LiveRegionMode.Polite
        }, shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(40.dp)) {
                    Box(Modifier.fillMaxSize().background(Color(0xFF484B60), CircleShape), contentAlignment = Alignment.Center) {
                        Text("Y", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE7E8F0))
                    }
                    Surface(Modifier.size(13.dp).align(Alignment.BottomEnd), shape = CircleShape,
                        color = Color(0xFF43B581), border = BorderStroke(3.dp, MaterialTheme.colorScheme.surfaceContainerLow)) {}
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("You", fontSize = 15.sp, lineHeight = 19.sp,
                        fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("♫", color = Color(0xFF43B581), fontSize = 14.sp, lineHeight = 17.sp)
                        Text(activity, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
internal fun EnrichmentSettings(state: ScreenState, model: MainViewModel, openTwitch: () -> Unit, connectCrunchyroll: () -> Unit) {
    DashboardCard("Twitch enrichment", "OFFICIAL TWITCH CONNECTION") {
        ConnectionLabel(when {
            state.twitchConnected -> "Connected"
            state.twitchConnecting -> "Connecting…"
            else -> "Optional"
        }, state.twitchConnected)
        Text("Add stream titles, creators, categories, and artwork. VODs use matching video details when available.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!state.twitchConfigured) Text("Twitch connection is unavailable in this build.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.twitchUserCode?.let { code ->
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("TWITCH DEVICE CODE", style = MaterialTheme.typography.labelSmall)
                    Text(code, style = MaterialTheme.typography.titleLarge)
                    Text("Enter this code on Twitch to finish connecting.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        when {
            state.twitchConnecting -> FlowActions {
                OutlinedButton(onClick = openTwitch, enabled = state.twitchVerificationUri != null) { Text("Open Twitch") }
                TextButton(onClick = model::cancelTwitchConnection) { Text("Cancel") }
            }
            state.twitchConnected -> FlowActions {
                OutlinedButton(onClick = model::connectTwitch, enabled = state.twitchConfigured) { Text("Reconnect") }
                TextButton(onClick = model::disconnectTwitch) { Text("Disconnect") }
            }
            else -> OutlinedButton(onClick = model::connectTwitch, enabled = state.twitchConfigured,
                modifier = Modifier.fillMaxWidth()) { Text("Connect Twitch") }
        }
    }
    DashboardCard("Crunchyroll enrichment", "EXPERIMENTAL · UNOFFICIAL") {
        ConnectionLabel(if (state.crunchyrollConnected) "Connected" else "Optional", state.crunchyrollConnected)
        Text("Add series and season names, artwork, and episode and series links.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Experimental, unofficial integration. Sign in on Crunchyroll’s page. If enrichment is unavailable, the episode title and timer still work.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.crunchyrollConnected) FlowActions {
            OutlinedButton(onClick = connectCrunchyroll) { Text("Reconnect") }
            TextButton(onClick = model::disconnectCrunchyroll) { Text("Disconnect") }
        } else OutlinedButton(onClick = connectCrunchyroll, modifier = Modifier.fillMaxWidth()) { Text("Connect Crunchyroll") }
    }
}

@Composable
private fun ConnectionLabel(label: String, connected: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(6.dp).background(if (connected) Color(0xFF43B581) else MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowActions(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@Composable
internal fun FirstTimeSetupHeading(step: Int) {
    val labels = listOf("Connection", "Enrichment", "Activities")
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            labels.forEachIndexed { index, label ->
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.fillMaxWidth().height(3.dp).background(
                        if (index <= step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(2.dp)))
                    Text("${index + 1}. $label", style = MaterialTheme.typography.bodySmall,
                        color = if (index == step) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text("STEP ${step + 1} OF 3", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(when (step) {
            0 -> "Connect Discord"
            1 -> "Connect services"
            else -> "Set up activities"
        }, style = MaterialTheme.typography.headlineLarge)
        Text(when (step) {
            0 -> "Share your media through the Discord app on this phone. You can finish connection and access later in Settings."
            1 -> "Connect Twitch, Crunchyroll, or both for richer activity. These are optional; you can add them later in Settings."
            else -> "Try the sample preview, then pick the detail friends see for each service."
        }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Preview(name = "App name", widthDp = 320, showBackground = true)
@Composable
private fun AppNamePreview() = PresenceTheme {
    Box(Modifier.padding(16.dp)) { DiscordProfilePreview(PresenceContent.APP_NAME) }
}

@Preview(name = "Track", widthDp = 320, showBackground = true)
@Composable
private fun TrackPreview() = PresenceTheme {
    Box(Modifier.padding(16.dp)) { DiscordProfilePreview(PresenceContent.TRACK) }
}

@Preview(name = "Artist", widthDp = 320, showBackground = true)
@Composable
private fun ArtistPreview() = PresenceTheme {
    Box(Modifier.padding(16.dp)) { DiscordProfilePreview(PresenceContent.ARTIST) }
}
