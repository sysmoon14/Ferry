package dev.sysmoon.ferry.ui

import android.graphics.Bitmap
import android.graphics.Color as AColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import dev.sysmoon.ferry.FerryService
import dev.sysmoon.ferry.graph
import dev.sysmoon.ferry.net.Network
import dev.sysmoon.ferry.update.UpdateState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import kotlinx.coroutines.flow.catch

/**
 * Settings: a short list of things you can do from the TV, and the pairing card for everything
 * else (sources, groups, Sky layout, MQTT… all live on the phone/web page).
 */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val graph = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    val pin by graph.settings.pin.collectAsState(initial = null)
    // Creates the PIN if this screen is reached before the service has (first launch race).
    LaunchedEffect(Unit) { graph.settings.pin() }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
    val name by graph.settings.deviceName.collectAsState(initial = "")
    val sources by graph.db.sources().observeAll().collectAsState(initial = emptyList())
    val update by graph.updater.state.collectAsState()
    val ip = remember { Network.localIpv4() }
    val base = ip?.let { "http://$it:${FerryService.PORT}" }
    // QR carries the PIN so scanning pairs in one step; the URL alone still asks for it.
    val qr = remember(base, pin) { if (base != null && pin != null) qrBitmap("$base/?pin=$pin") else null }
    // Short confirmation under the row that was just used.
    var flash by remember { mutableStateOf<Pair<String, String>?>(null) }
    LaunchedEffect(flash) { if (flash != null) { delay(3_000); flash = null } }
    fun note(row: String, text: String) { flash = row to text }
    val comfortable by remember { graph.settings.guideComfortable }.collectAsState(initial = false)
    val hidden by remember { graph.db.channels().observeHiddenChannels().catch { emit(emptyList()) } }.collectAsState(initial = emptyList())
    // "pin" / "unpair": asked before it happens (they used to fire on one press).
    var confirm by remember { mutableStateOf<String?>(null) }
    var showHidden by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxSize().background(FerryColors.Deep).padding(horizontal = 56.dp, vertical = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(56.dp),
    ) {
        Column(Modifier.weight(1f)) {
            ScreenTitle("Settings", FerryIcons.Settings)
            Spacer(Modifier.height(6.dp))
            Text(
                "$name  ·  ${sources.size} source${if (sources.size == 1) "" else "s"}  ·  ${"%,d".format(sources.sumOf { it.channelCount })} channels",
                color = FerryColors.Muted, style = MaterialTheme.typography.bodyMedium,
            )
            sources.filter { it.lastError != null }.forEach {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Icon(FerryIcons.Warning, null, tint = FerryColors.Gold, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("${it.name}: ${it.lastError}", color = FerryColors.Gold, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (sources.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "No playlists yet. Scan the code with your phone, then add one under Sources.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Spacer(Modifier.height(18.dp))

            val available = update as? UpdateState.Available
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SettingRow(
                    icon = FerryIcons.Download,
                    title = if (available != null) "Update to Ferry ${available.release.version}" else "Check for updates",
                    detail = flash?.takeIf { it.first == "update" }?.second ?: ("Ferry ${graph.updater.installedVersion}  ·  " + updateStatus(update)),
                    highlight = available != null,
                    modifier = Modifier.focusRequester(first),
                ) {
                    if (available != null) graph.updater.install(available.release)
                    else { graph.updater.check(); note("update", "Checking GitHub for a newer release…") }
                }
                SettingRow(
                    icon = FerryIcons.Refresh,
                    title = "Refresh playlists and guide",
                    detail = flash?.takeIf { it.first == "refresh" }?.second ?: "Reload every source and its listings now",
                ) {
                    scope.launch { graph.repository.refreshDue(force = true) }
                    note("refresh", "Refreshing — listings update as they arrive")
                }
                SettingRow(
                    icon = FerryIcons.Grid,
                    title = "Guide rows: ${if (comfortable) "Comfortable" else "Compact"}",
                    detail = if (comfortable) "Bigger rows and text, for watching from further away" else "About ten channels on screen",
                ) {
                    scope.launch { graph.settings.setGuideComfortable(!comfortable) }
                }
                SettingRow(
                    icon = FerryIcons.Hide,
                    title = "Hidden channels",
                    detail = if (hidden.isEmpty()) "None hidden one at a time" else "${hidden.size} hidden  ·  OK to bring one back",
                ) {
                    if (hidden.isNotEmpty()) showHidden = true
                }
                SettingRow(
                    icon = FerryIcons.Key,
                    title = "New pairing PIN",
                    detail = flash?.takeIf { it.first == "pin" }?.second ?: "Phones already paired stay paired",
                ) { confirm = "pin" }
                SettingRow(
                    icon = FerryIcons.LockOpen,
                    title = "Unpair everything",
                    detail = flash?.takeIf { it.first == "unpair" }?.second ?: "Phones and Home Assistant must pair again",
                ) { confirm = "unpair" }
            }
            Spacer(Modifier.height(8.dp))
            Hint("Back  guide")
        }

        // Pairing card
        Column(
            Modifier.width(380.dp).clip(RoundedCornerShape(20.dp)).background(FerryColors.Navy).padding(horizontal = 24.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(FerryIcons.Phone, null, tint = FerryColors.Gold, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text("Set up from your phone", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Sources, groups, favourites, Sky layout and Home Assistant all live on Ferry's settings page.",
                color = FerryColors.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(14.dp))
            Box(Modifier.size(212.dp).clip(RoundedCornerShape(14.dp)).background(Color.White), contentAlignment = Alignment.Center) {
                qr?.let { Image(bitmap = it.asImageBitmap(), contentDescription = "Pairing QR code", modifier = Modifier.size(200.dp)) }
                    ?: Text("No network", color = FerryColors.Deep)
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("OR OPEN", color = FerryColors.Muted, style = MaterialTheme.typography.labelSmall)
                    Text(base ?: "No network", style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = TABULAR), maxLines = 1)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("PIN", color = FerryColors.Muted, style = MaterialTheme.typography.labelSmall)
                    Text(pin?.chunked(3)?.joinToString(" ") ?: "…", style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = TABULAR), color = FerryColors.Gold)
                }
            }
        }
    }

    when (confirm) {
        "pin" -> ConfirmDialog(
            title = "Make a new pairing PIN?",
            body = "The PIN on this screen changes. Phones already paired stay paired.",
            confirm = "New PIN",
            onConfirm = {
                confirm = null
                scope.launch { graph.settings.regeneratePin() }
                note("pin", "New PIN made — it's on the card")
            },
            onDismiss = { confirm = null },
        )
        "unpair" -> ConfirmDialog(
            title = "Unpair everything?",
            body = "Every phone, Home Assistant and any Ferry that follows this one must pair again with the PIN.",
            confirm = "Unpair everything",
            onConfirm = {
                confirm = null
                scope.launch { graph.settings.revokeAllTokens() }
                note("unpair", "Done — nothing is paired with this TV now")
            },
            onDismiss = { confirm = null },
        )
    }
    if (showHidden && hidden.isNotEmpty()) {
        HiddenChannelsDialog(
            hidden,
            onShow = { ch -> scope.launch { graph.db.channels().setHidden(ch.id, false) } },
            onDismiss = { showHidden = false },
        )
    }
}

/** Channels hidden one at a time: OK on one brings it back. */
@Composable
private fun HiddenChannelsDialog(channels: List<dev.sysmoon.ferry.data.ChannelEntity>, onShow: (dev.sysmoon.ferry.data.ChannelEntity) -> Unit, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
    FerryDialog(onDismiss = onDismiss, eyebrow = "Hidden channels", eyebrowIcon = FerryIcons.Hide, subtitle = "OK brings a channel back to the guide.", width = 640.dp) {
        Spacer(Modifier.height(12.dp))
        androidx.compose.foundation.lazy.LazyColumn(
            Modifier.heightIn(max = 360.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp),
        ) {
            items(channels.size, key = { channels[it].id }) { i ->
                val ch = channels[i]
                ListItem(
                    selected = false,
                    onClick = { onShow(ch) },
                    modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                    colors = ferryListItemColors(), border = ferryListItemBorder(), shape = ferryListItemShape(), scale = ferryListItemScale(),
                    leadingContent = { LogoChip(ch.logo, ch.name, width = 58.dp, height = 36.dp) },
                    headlineContent = { Text(ch.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = ch.groupTitle?.let { g -> { Text(g, color = FerryColors.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Hint("OK  show again   ·   Back  close")
    }
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    detail: String,
    highlight: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    ListItem(
        selected = false,
        onClick = onClick,
        modifier = modifier,
        colors = ferryListItemColors(),
        border = ferryListItemBorder(),
        shape = ferryListItemShape(),
        scale = ferryListItemScale(),
        leadingContent = {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(if (highlight) FerryColors.Gold else FerryColors.Raised),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = if (highlight) FerryColors.Deep else FerryColors.Text, modifier = Modifier.size(22.dp)) }
        },
        headlineContent = { Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(detail, color = FerryColors.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = { Icon(FerryIcons.ChevronRight, null, tint = FerryColors.Faint, modifier = Modifier.size(22.dp)) },
    )
}

private fun qrBitmap(text: String, size: Int = 512): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) AColor.BLACK else AColor.WHITE }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

private fun updateStatus(s: UpdateState): String = when (s) {
    UpdateState.Idle -> "updates from GitHub releases"
    UpdateState.Checking -> "checking for updates…"
    UpdateState.UpToDate -> "up to date"
    is UpdateState.Available -> "version ${s.release.version} is available"
    is UpdateState.Downloading -> "downloading ${s.release.version}… ${s.percent}%"
    is UpdateState.Installing -> "installing ${s.release.version}…"
    is UpdateState.Failed -> "update: ${s.message}"
}
