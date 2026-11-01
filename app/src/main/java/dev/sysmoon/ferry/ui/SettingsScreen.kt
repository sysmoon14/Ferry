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
            Spacer(Modifier.height(22.dp))

            val available = update as? UpdateState.Available
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                    detail = flash?.takeIf { it.first == "refresh" }?.second ?: "Reload every source now instead of waiting for the next scheduled refresh",
                ) {
                    scope.launch { graph.repository.refreshDue(force = true) }
                    note("refresh", "Refreshing in the background — channels and listings update as they arrive")
                }
                SettingRow(
                    icon = FerryIcons.Key,
                    title = "New pairing PIN",
                    detail = flash?.takeIf { it.first == "pin" }?.second ?: "Phones already paired stay paired",
                ) {
                    scope.launch { graph.settings.regeneratePin() }
                    note("pin", "New PIN made — it's on the card")
                }
                SettingRow(
                    icon = FerryIcons.LockOpen,
                    title = "Unpair everything",
                    detail = flash?.takeIf { it.first == "unpair" }?.second ?: "Every phone and Home Assistant will need to pair with this TV again",
                ) {
                    scope.launch { graph.settings.revokeAllTokens() }
                    note("unpair", "Done — nothing is paired with this TV now")
                }
            }
            Spacer(Modifier.weight(1f))
            Hint("Back  guide")
        }

        // Pairing card
        Column(
            Modifier.width(360.dp).clip(RoundedCornerShape(20.dp)).background(FerryColors.Navy).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(FerryIcons.Phone, null, tint = FerryColors.Gold, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text("Set up from your phone", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Scan to open Ferry's settings page — sources, groups, favourites, Sky layout and Home Assistant all live there.",
                color = FerryColors.Muted, style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(18.dp))
            Box(Modifier.size(248.dp).clip(RoundedCornerShape(14.dp)).background(Color.White), contentAlignment = Alignment.Center) {
                qr?.let { Image(bitmap = it.asImageBitmap(), contentDescription = "Pairing QR code", modifier = Modifier.size(232.dp)) }
                    ?: Text("No network", color = FerryColors.Deep)
            }
            Spacer(Modifier.height(18.dp))
            Text("OR OPEN", color = FerryColors.Faint, style = MaterialTheme.typography.labelSmall)
            Text(base ?: "No network connection", style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = TABULAR), maxLines = 1)
            Spacer(Modifier.height(12.dp))
            Text("PAIRING PIN", color = FerryColors.Faint, style = MaterialTheme.typography.labelSmall)
            Text(pin?.chunked(3)?.joinToString("  ") ?: "…", style = MaterialTheme.typography.displaySmall, color = FerryColors.Gold)
        }
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
