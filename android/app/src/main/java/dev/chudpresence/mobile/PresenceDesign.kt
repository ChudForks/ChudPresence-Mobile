package dev.chudpresence.mobile

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chudpresence.mobile.data.PresenceContent
import dev.chudpresence.mobile.link.LinkUiState
import dev.chudpresence.mobile.link.LinkConnection

@Composable
internal fun PresenceTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) darkColorScheme(
        primary = Color(0xFFF2F2F2), onPrimary = Color(0xFF000000),
        primaryContainer = Color(0xFF1C1C1F), onPrimaryContainer = Color(0xFFF2F2F2),
        secondary = Color(0xFFC2C2C8), onSecondary = Color.Black,
        secondaryContainer = Color(0xFF202024), onSecondaryContainer = Color(0xFFE7E7EC),
        tertiary = Color(0xFFC2C2C8), onTertiary = Color.Black,
        tertiaryContainer = Color(0xFF202024), onTertiaryContainer = Color(0xFFE7E7EC),
        background = Color(0xFF000000), surface = Color(0xFF000000),
        onBackground = Color(0xFFEEEEEE), onSurface = Color(0xFFEEEEEE),
        surfaceVariant = Color(0xFF1C1C1F), onSurfaceVariant = Color(0xFFA3A3AC),
        surfaceContainer = Color(0xFF141416), surfaceContainerHigh = Color(0xFF1C1C1F),
        surfaceContainerHighest = Color(0xFF242428), surfaceContainerLow = Color.Black,
        surfaceContainerLowest = Color.Black, surfaceTint = Color.Transparent,
        inverseSurface = Color(0xFFE7E7EC), inverseOnSurface = Color(0xFF1A1A1E),
        inversePrimary = Color(0xFF202124),
        outline = Color(0xFF75757F), outlineVariant = Color(0xFF252529),
        error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
        errorContainer = Color(0xFF3A1617), onErrorContainer = Color(0xFFFFDAD6)
    ) else lightColorScheme(
        primary = Color(0xFF202124), onPrimary = Color.White,
        primaryContainer = Color(0xFFE9EAEC), onPrimaryContainer = Color(0xFF202124),
        secondary = Color(0xFF55585E), onSecondary = Color.White,
        secondaryContainer = Color(0xFFEDEEF0), onSecondaryContainer = Color(0xFF292B30),
        tertiary = Color(0xFF55585E), onTertiary = Color.White,
        tertiaryContainer = Color(0xFFEDEEF0), onTertiaryContainer = Color(0xFF292B30),
        background = Color(0xFFF6F6F8), surface = Color(0xFFF6F6F8),
        onBackground = Color(0xFF202124), onSurface = Color(0xFF202124),
        surfaceVariant = Color(0xFFEDEEF0), onSurfaceVariant = Color(0xFF60636A),
        surfaceContainerLowest = Color.White, surfaceContainerLow = Color.White,
        surfaceContainer = Color(0xFFF4F4F5), surfaceContainerHigh = Color(0xFFEDEEF0),
        surfaceContainerHighest = Color(0xFFE6E7E9), surfaceTint = Color.Transparent,
        inverseSurface = Color(0xFF202124), inverseOnSurface = Color(0xFFF5F5F5),
        inversePrimary = Color(0xFFE6E7E9),
        outline = Color(0xFF858890), outlineVariant = Color(0xFFDCDDDF),
        error = Color(0xFFB3261E), onError = Color.White,
        errorContainer = Color(0xFFFCE9E7), onErrorContainer = Color(0xFF721C17)
    )
    MaterialTheme(colorScheme = colors, typography = Typography(
        headlineLarge = TextStyle(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp),
        titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
        titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        labelSmall = TextStyle(fontSize = 10.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp),
        bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
        bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 19.sp)
    ), shapes = Shapes(extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(24.dp)),
        content = content)
}

@Composable
internal fun PresenceHeader(actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Image(painterResource(R.drawable.presence_brand), contentDescription = null, modifier = Modifier.size(38.dp))
        Text("ChudPresence", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        actions()
    }
}

@Composable
internal fun PresenceHero(running: Boolean, configured: Boolean, hasError: Boolean,
                          actions: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        color = colors.surfaceContainerLow, border = BorderStroke(1.dp, colors.outlineVariant)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(7.dp).background(when {
                    hasError -> colors.error
                    running -> if (isSystemInDarkTheme()) Color(0xFF67DCA2) else Color(0xFF18794E)
                    else -> colors.onSurfaceVariant
                }, CircleShape))
                Text(when { hasError -> "CHECK CONNECTION"; running -> "REPORTING ON"; else -> "REPORTING OFF" },
                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(when { hasError -> "Connection issue"; running -> "Reporting active"; configured -> "Ready to report";
                    else -> "Discord required" },
                    style = MaterialTheme.typography.headlineLarge)
                Text(when {
                    hasError -> "Check the publisher status below."
                    running -> "Media activity updates automatically."
                    configured -> "Share your current media on Discord."
                    else -> "Install Discord and sign in on this phone to share your media."
                }, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), content = actions)
        }
    }
}

@Composable
internal fun DashboardCard(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
}

internal enum class SettingsSection(val label: String, val symbol: PresenceSymbolKind, val navigationLabel: String = label) {
    ACTIVITIES("Activities", PresenceSymbolKind.ACTIVITIES),
    CONNECTIONS("Connections", PresenceSymbolKind.CONNECTIONS, "Services"),
    LINK("Link", PresenceSymbolKind.LINK),
    ACCESS("Access", PresenceSymbolKind.ACCESS)
}

@Composable
internal fun SettingsSections(selected: SettingsSection, select: (SettingsSection) -> Unit) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelMedium
    val labelWidth = SettingsSection.entries.maxOf { textMeasurer.measure(it.navigationLabel, style = labelStyle).size.width }
    BoxWithConstraints(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerLow)
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))) {
        val neededWidth = with(density) { labelWidth.toDp() } + 16.dp
        val columns = when {
            neededWidth <= (maxWidth - 20.dp) / 4 -> 4
            neededWidth <= (maxWidth - 12.dp) / 2 -> 2
            else -> 1
        }
        val sectionItem: @Composable (SettingsSection, Modifier) -> Unit = { section, itemModifier ->
                val active = section == selected
                Surface(itemModifier.testTag("settings-section-${section.name.lowercase()}")
                    .selectable(selected = active, onClick = { select(section) }, role = Role.Tab),
                    color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, if (active) MaterialTheme.colorScheme.outline else Color.Transparent)) {
                    Column(Modifier.heightIn(min = 72.dp).padding(horizontal = 6.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterVertically)) {
                        PresenceSymbol(section.symbol, Modifier.size(21.dp),
                            if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(section.navigationLabel, style = labelStyle, maxLines = 1, softWrap = false,
                            color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
        }
        if (columns < 4) {
            Column(Modifier.padding(4.dp).selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SettingsSection.entries.chunked(columns).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEach { section -> sectionItem(section, Modifier.weight(1f)) }
                    }
                }
            }
        } else {
            Row(Modifier.padding(4.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                SettingsSection.entries.forEach { section -> sectionItem(section, Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
internal fun DashboardShortcuts(serviceCount: Int, connectionCount: Int, ready: Boolean,
    open: (SettingsSection) -> Unit, link: LinkUiState = LinkUiState()) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column {
            DashboardShortcut("Activities", "$serviceCount ${if (serviceCount == 1) "app" else "apps"} configured",
                PresenceSymbolKind.ACTIVITIES) { open(SettingsSection.ACTIVITIES) }
            HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
            DashboardShortcut("Connections", "$connectionCount of 2 services connected", PresenceSymbolKind.CONNECTIONS) {
                open(SettingsSection.CONNECTIONS)
            }
            HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
            DashboardShortcut("ChudPresence Link", when {
                !link.paired -> "Sync app choices with your desktop"
                link.connection == LinkConnection.PAIR_AGAIN -> "Pair this phone again"
                link.connection == LinkConnection.OFFLINE -> "Desktop offline · choices saved"
                link.connection == LinkConnection.WAITING_FOR_NETWORK -> "Waiting for Wi-Fi · choices saved"
                link.activeServices.isEmpty() -> "Paired · choose apps to sync"
                else -> "${link.activeServices.size} ${if (link.activeServices.size == 1) "app" else "apps"} syncing"
            }, PresenceSymbolKind.LINK) { open(SettingsSection.LINK) }
            HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
            DashboardShortcut("Connection & access", if (ready) "Ready to report" else "Finish setup",
                PresenceSymbolKind.ACCESS) { open(SettingsSection.ACCESS) }
        }
    }
}

@Composable
private fun DashboardShortcut(title: String, detail: String, symbol: PresenceSymbolKind, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        PresenceSymbol(symbol, Modifier.size(21.dp), MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        PresenceSymbol(PresenceSymbolKind.CHEVRON, Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal enum class PresenceSymbolKind { ACTIVITIES, CONNECTIONS, LINK, ACCESS, CHEVRON, PLAY, STOP }

/** Small decorative symbols; each control also has a text label. */
@Composable
internal fun PresenceSymbol(kind: PresenceSymbolKind, modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val unit = size.minDimension / 24f
        val stroke = Stroke(width = 1.7f * unit, cap = StrokeCap.Round)
        fun point(x: Float, y: Float) = Offset(x * unit, y * unit)
        fun line(x: Float, y: Float, endX: Float, endY: Float) =
            drawLine(color, point(x, y), point(endX, endY), strokeWidth = stroke.width, cap = StrokeCap.Round)
        when (kind) {
            PresenceSymbolKind.ACTIVITIES -> {
                listOf(6f to 9f, 12f to 16f, 18f to 7f).forEach { (x, y) ->
                    line(x, 3f, x, y - 3f)
                    line(x, y + 3f, x, 21f)
                    drawCircle(color, 2.5f * unit, point(x, y), style = stroke)
                }
            }
            PresenceSymbolKind.CONNECTIONS -> {
                drawRoundRect(color, point(2f, 7f), androidx.compose.ui.geometry.Size(12f * unit, 10f * unit),
                    androidx.compose.ui.geometry.CornerRadius(5f * unit), style = stroke)
                drawRoundRect(color, point(10f, 7f), androidx.compose.ui.geometry.Size(12f * unit, 10f * unit),
                    androidx.compose.ui.geometry.CornerRadius(5f * unit), style = stroke)
            }
            PresenceSymbolKind.LINK -> {
                drawRoundRect(color, point(2f, 4f), androidx.compose.ui.geometry.Size(14f * unit, 11f * unit),
                    androidx.compose.ui.geometry.CornerRadius(2f * unit), style = stroke)
                line(7f, 15f, 7f, 19f)
                line(4f, 19f, 10f, 19f)
                drawRoundRect(color, point(14f, 9f), androidx.compose.ui.geometry.Size(8f * unit, 13f * unit),
                    androidx.compose.ui.geometry.CornerRadius(1.5f * unit), style = stroke)
                line(17f, 19f, 19f, 19f)
            }
            PresenceSymbolKind.ACCESS -> {
                drawCircle(color, 9f * unit, point(12f, 12f), style = stroke)
                line(8f, 12f, 11f, 15f)
                line(11f, 15f, 16f, 9f)
            }
            PresenceSymbolKind.CHEVRON -> { line(9f, 6f, 15f, 12f); line(15f, 12f, 9f, 18f) }
            PresenceSymbolKind.PLAY -> {
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(8f * unit, 5f * unit); lineTo(19f * unit, 12f * unit)
                    lineTo(8f * unit, 19f * unit); close()
                }
                drawPath(path, color)
            }
            PresenceSymbolKind.STOP -> drawRoundRect(color, point(6f, 6f),
                androidx.compose.ui.geometry.Size(12f * unit, 12f * unit), androidx.compose.ui.geometry.CornerRadius(2f * unit))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DisplayContentSelector(selected: PresenceContent, select: (PresenceContent) -> Unit) {
    FlowRow(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PresenceContent.previewOptions.forEach { option ->
            val active = selected == option
            val fill by animateColorAsState(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerLow, label = "Profile choice")
            Box(Modifier.testTag("preview-choice-${option.wireValue}").clip(RoundedCornerShape(10.dp)).background(fill)
                .border(1.dp, if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                .selectable(selected = active, onClick = { select(option) }, role = Role.RadioButton)
                .heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                Text(option.label, style = MaterialTheme.typography.labelLarge,
                    color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun SetupProgress(completed: Int) {
    Text(if (completed == 3) "All required permissions granted" else "$completed / 3 requirements complete",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun SetupStep(number: String, title: String, description: String, complete: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                Text(if (complete) "✓" else number, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (complete) "Ready" else if (number == "04") "Recommended" else "Required",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}
