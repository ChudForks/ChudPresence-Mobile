package dev.chudpresence.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.chudpresence.mobile.link.*
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LinkSettings(screen: ScreenState, model: MainViewModel) {
    val state = screen.link
    if (!state.loaded) {
        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    var choosingSource by remember { mutableStateOf<LinkService?>(null) }
    var confirmingDisconnect by rememberSaveable { mutableStateOf(false) }
    val needsPairing = !state.paired || state.connection == LinkConnection.PAIR_AGAIN
    val canEdit = state.connection == LinkConnection.CONNECTED && !state.busy && !state.manualSyncing

    DashboardCard("ChudPresence Link", "PHONE + DESKTOP") {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer) {
                Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                    PresenceSymbol(PresenceSymbolKind.LINK, Modifier.size(30.dp), MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                val connected = state.connection == LinkConnection.CONNECTED
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Box(Modifier.size(7.dp).background(if (connected) Color(0xFF43B581) else MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
                    Text(when (state.connection) {
                        LinkConnection.UNPAIRED -> "Not paired"
                        LinkConnection.CONNECTING -> "Connecting…"
                        LinkConnection.CONNECTED -> "Connected"
                        LinkConnection.OFFLINE -> "Desktop unavailable"
                        LinkConnection.WAITING_FOR_NETWORK -> "Waiting for Wi-Fi"
                        LinkConnection.PAIR_AGAIN -> "Pair again"
                    }, style = MaterialTheme.typography.titleSmall)
                }
                Text(if (state.paired) state.phoneName else "Your apps, in sync", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text("Sync each app’s Discord display and activity settings between your phone and browser. Choose the apps and settings you want to link.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.paired) {
            Text(state.endpoint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.lastSyncedAt?.let { time ->
                Text("Last synced at ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(time))}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.pendingChanges > 0) Text("${state.pendingChanges} ${if (state.pendingChanges == 1) "change" else "changes"} waiting to sync",
                style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!needsPairing) OutlinedButton(onClick = { model.refreshLink() }, enabled = !state.busy && !state.manualSyncing,
                    modifier = Modifier.testTag("link-refresh")) {
                    Text(if (state.manualSyncing) "Syncing…" else "Sync now")
                }
                TextButton(onClick = { confirmingDisconnect = true }, enabled = !state.busy,
                    modifier = Modifier.testTag("link-unpair")) { Text("Unpair phone") }
            }
        }
    }

    state.message?.let { message ->
        Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
            Text(message, Modifier.fillMaxWidth().padding(16.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
    state.notice?.let { notice ->
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text(notice, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = model::dismissLinkNotice) { Text("Dismiss") }
            }
        }
    }

    if (needsPairing) {
        LinkPairingForm(state, model)
    } else {
        val selected = state.services.filter { it.syncRequested || it.syncEnabled }.sortedBy { it.name }
        val matches = state.services.filter { it.shared && !it.syncRequested && !it.syncEnabled }.sortedBy { it.name }
        val waiting = state.services.filter { !it.shared && !it.syncRequested && !it.syncEnabled }.sortedBy { it.name }
        DashboardCard("Linked apps", "${state.activeServices.size} SYNCING") {
            if (selected.isEmpty()) {
                Text("No apps linked yet. Enable a matching app below and choose which device’s settings to start with.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            selected.forEachIndexed { index, service ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                LinkServiceRow(service, if (service.id in state.activeServices) "Synced · ${service.syncFields.joinToString { it.label }}"
                    else "Paused · waiting for a compatible match") {
                    Column(horizontalAlignment = Alignment.End) {
                        TextButton(onClick = { choosingSource = service }, enabled = canEdit && service.shared && service.supportedFields.isNotEmpty(),
                            modifier = Modifier.testTag("link-settings-${service.id}")) { Text("Settings") }
                        TextButton(onClick = { model.setLinkServiceSync(service, false) }, enabled = canEdit) { Text("Turn off") }
                    }
                }
            }
            if (selected.isNotEmpty()) Text("Changes to selected settings in Activities update every linked device. Turning sync off keeps each device’s current settings.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DashboardCard("Matching apps", "CHOOSE WHAT TO SYNC") {
            if (matches.isEmpty()) Text("Matches appear when your browser and phone report the same app to this desktop. Connect your browser extension to Link to find matches.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            matches.forEachIndexed { index, service ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                LinkServiceRow(service, if (service.supportedFields.isEmpty()) "No common settings"
                    else "Available on ${service.participants.size} devices") {
                    OutlinedButton(onClick = { choosingSource = service }, enabled = canEdit && service.supportedFields.isNotEmpty(),
                        modifier = Modifier.testTag("link-enable-${service.id}")) { Text("Enable") }
                }
            }
            if (waiting.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text("Waiting for a browser match", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    waiting.forEach { service ->
                        Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                            Text(service.name, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (state.services.isEmpty() && !state.manualSyncing) Text("Installed, supported media apps are added here automatically.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.localOnlyServices.isNotEmpty()) {
            DashboardCard("Staying on this phone", "LOCAL APP CHOICES") {
                Text("${state.localOnlyServices.joinToString()} ${if (state.localOnlyServices.size == 1) "uses" else "use"} Season & episode. That display choice stays local; other supported settings can still sync. Choose App name, a creator, or a title in Activities to share the display too.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text("While ChudPresence is open or reporting is active, Link sends setting changes over your local network. Changes made away from your PC sync when you reconnect. Your phone publishes its media through Discord on this phone.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    choosingSource?.let { service ->
        LinkSourceDialog(service, state.deviceId, canEdit, { choosingSource = null }) { source, fields ->
            choosingSource = null
            model.setLinkServiceSync(service, true, source, fields)
        }
    }
    if (confirmingDisconnect) AlertDialog(onDismissRequest = { confirmingDisconnect = false },
        title = { Text("Unpair this phone?") },
        text = { Text("Your current app choices stay saved on this phone. Pair again whenever you want to sync with your desktop.") },
        confirmButton = { TextButton(onClick = { confirmingDisconnect = false; model.disconnectLink() }) { Text("Unpair") } },
        dismissButton = { TextButton(onClick = { confirmingDisconnect = false }) { Text("Cancel") } })
}

@Composable
private fun LinkPairingForm(state: LinkUiState, model: MainViewModel) {
    var address by rememberSaveable(state.deviceId) { mutableStateOf(state.endpoint) }
    var code by rememberSaveable(state.deviceId) { mutableStateOf("") }
    var phoneName by rememberSaveable(state.deviceId) { mutableStateOf(state.phoneName.ifEmpty { model.linkPhoneName }) }
    DashboardCard(if (state.paired) "Pair with your desktop again" else "Pair your desktop", "ONE-TIME SETUP") {
        Text("Open ChudPresence Link on your PC and choose Pair phone. Connect your phone to the same network, then enter the address and code shown there.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(value = address, onValueChange = { address = it }, label = { Text("Desktop address") },
            placeholder = { Text("192.168.1.10:17879") }, singleLine = true, enabled = !state.busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            supportingText = { Text("Use the local IP shown in the desktop app.") },
            modifier = Modifier.fillMaxWidth().testTag("link-address"))
        OutlinedTextField(value = code, onValueChange = { code = it.filter { c -> c in '0'..'9' }.take(8) },
            label = { Text("Phone pairing code") }, placeholder = { Text("00000000") },
            textStyle = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
            singleLine = true, enabled = !state.busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            supportingText = { Text("Eight digits · codes expire after five minutes") },
            modifier = Modifier.fillMaxWidth().testTag("link-pair-code"))
        OutlinedTextField(value = phoneName, onValueChange = { phoneName = it.take(80) }, label = { Text("Phone name") },
            singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("link-phone-name"))
        Button(onClick = { model.pairLink(address, code, phoneName); code = "" },
            enabled = !state.busy && address.isNotBlank() && code.length == 8 && phoneName.isNotBlank(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("link-pair")) {
            Text(if (state.busy) "Pairing…" else "Pair phone")
        }
        Text("Pairing lets you choose matching apps. Each app’s sync stays off until you enable it.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LinkServiceRow(service: LinkService, detail: String, action: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(service.name, style = MaterialTheme.typography.titleSmall)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            action()
        }
    }
}

@Composable
private fun LinkSourceDialog(service: LinkService, phoneDeviceId: String, enabled: Boolean,
    dismiss: () -> Unit, confirm: (String, Set<LinkField>) -> Unit) {
    var allSupported by remember(service.id, service.syncVersion) {
        mutableStateOf(!service.syncRequested || service.syncFields == service.supportedFields)
    }
    var chosenFields by remember(service.id, service.syncVersion) {
        mutableStateOf(service.syncFields.intersect(service.supportedFields))
    }
    val fields = if (allSupported) service.supportedFields else chosenFields
    val sources = service.participants.filter { it.supports(fields, service.displayChoices) }
        .sortedBy { if (it.deviceId == phoneDeviceId) 0 else 1 }
    var preferredSourceId by remember(service.id, service.syncVersion) { mutableStateOf(phoneDeviceId) }
    val sourceId = sources.firstOrNull { it.deviceId == preferredSourceId }?.deviceId ?: sources.firstOrNull()?.deviceId
    AlertDialog(onDismissRequest = dismiss, title = { Text("Sync ${service.name}") },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Choose what to sync, then the device whose last reported settings should be used to start.")
                Column(Modifier.selectableGroup()) {
                    listOf(true to "All supported settings", false to "Selected settings").forEach { (all, label) ->
                        Row(Modifier.fillMaxWidth().selectable(allSupported == all, onClick = { allSupported = all }, role = Role.RadioButton)
                            .padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = allSupported == all, onClick = null)
                            Text(label, Modifier.padding(start = 8.dp))
                        }
                    }
                }
                if (!allSupported) service.supportedFields.forEach { field ->
                    Row(Modifier.fillMaxWidth().toggleable(field in chosenFields, role = Role.Checkbox,
                        onValueChange = { checked -> chosenFields = if (checked) chosenFields + field else chosenFields - field })
                        .testTag("link-field-${field.wireName}").padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = field in chosenFields, onCheckedChange = null)
                        Text(field.label, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (allSupported) Text(service.supportedFields.joinToString { it.label }, style = MaterialTheme.typography.bodySmall)
                Text("Starting device", style = MaterialTheme.typography.titleSmall)
                if (sources.isEmpty()) Text("Select at least one setting with a compatible starting device.", style = MaterialTheme.typography.bodySmall)
                Column(Modifier.selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    sources.forEach { source ->
                        Row(Modifier.fillMaxWidth().selectable(source.deviceId == sourceId,
                            onClick = { preferredSourceId = source.deviceId }, role = Role.RadioButton)
                            .padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            RadioButton(selected = source.deviceId == sourceId, onClick = null)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(source.name, style = MaterialTheme.typography.titleSmall)
                                Text(if (source.deviceId == phoneDeviceId) "This phone" else if (source.kind == "browser") "Browser" else "Phone",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(fields.joinToString("\n") { field ->
                                    val value = source.values.getValue(field)
                                    "${field.label}: ${if (field == LinkField.DISPLAY) linkDisplayLabel(value.content) else if (value.content == "true") "On" else "Off"}"
                                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                Text("A device may be offline. All supported settings selects what is available now; later settings need a new selection.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { sourceId?.let { confirm(it, fields) } }, enabled = enabled && sourceId != null && fields.isNotEmpty()) {
            Text(if (service.syncRequested) "Save settings" else "Enable sync")
        } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}
