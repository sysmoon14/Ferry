package dev.sysmoon.ferry.ui

import android.graphics.Bitmap
import android.graphics.Color as AColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import dev.sysmoon.ferry.FerryService
import dev.sysmoon.ferry.graph
import dev.sysmoon.ferry.net.Network
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val graph = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    val pin by graph.settings.pin.collectAsState(initial = null)
    // Creates the PIN if this screen is reached before the service has (first launch race).
    LaunchedEffect(Unit) { graph.settings.pin() }
    val name by graph.settings.deviceName.collectAsState(initial = "")
    val sources by graph.db.sources().observeAll().collectAsState(initial = emptyList())
    val ip = remember { Network.localIpv4() }
    val base = ip?.let { "http://$it:${FerryService.PORT}" }
    // QR carries the PIN so scanning pairs in one step; the URL alone still asks for it.
    val qr = remember(base, pin) { if (base != null && pin != null) qrBitmap("$base/?pin=$pin") else null }

    Row(
        Modifier.fillMaxSize().background(FerryColors.Deep).padding(48.dp),
        horizontalArrangement = Arrangement.spacedBy(48.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("Set up from your phone", style = MaterialTheme.typography.headlineMedium, color = FerryColors.Gold)
            Spacer(Modifier.height(16.dp))
            Text("Scan the code, or open this address on any device on your network:", color = FerryColors.Muted)
            Spacer(Modifier.height(8.dp))
            Text(base ?: "No network connection", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))
            Text("Pairing PIN", color = FerryColors.Muted)
            Text(pin?.chunked(3)?.joinToString(" ") ?: "…", style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(24.dp))
            Text("Device name: $name", color = FerryColors.Muted)
            Text("${sources.size} source(s), ${sources.sumOf { it.channelCount }} channels", color = FerryColors.Muted)
            sources.filter { it.lastError != null }.forEach {
                Text("⚠ ${it.name}: ${it.lastError}", color = FerryColors.Gold)
            }
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onBack, colors = ferryButtonColors()) { Text("Back to guide") }
                OutlinedButton(colors = ferryOutlinedButtonColors(), onClick = { scope.launch { graph.repository.refreshDue(force = true) } }) {
                    Text("Refresh now")
                }
                OutlinedButton(colors = ferryOutlinedButtonColors(), onClick = { scope.launch { graph.settings.regeneratePin() } }) { Text("New PIN") }
                OutlinedButton(colors = ferryOutlinedButtonColors(), onClick = { scope.launch { graph.settings.revokeAllTokens() } }) {
                    Text("Unpair all")
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            qr?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = "Pairing QR code",
                    modifier = Modifier.size(320.dp),
                )
            }
        }
    }
}

private fun qrBitmap(text: String, size: Int = 512): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) AColor.BLACK else AColor.WHITE }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}
