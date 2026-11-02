package dev.sysmoon.ferry.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.sysmoon.ferry.data.Reminder
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/**
 * Reminder pop-up, two minutes before the programme: "Starts in 2 min" counting down to
 * "Starting now". Goes away by itself a couple of minutes after the start.
 */
@Composable
fun ReminderPopup(reminder: Reminder, onWatch: () -> Unit, onDismiss: () -> Unit) {
    val watch = remember { FocusRequester() }
    var secondsLeft by remember(reminder.key) { mutableStateOf(((reminder.start - System.currentTimeMillis()) / 1000).toInt()) }
    LaunchedEffect(reminder.key) {
        withFrameNanos { }
        runCatching { watch.requestFocus() }
        while (secondsLeft > -120) {
            delay(1_000)
            secondsLeft = ((reminder.start - System.currentTimeMillis()) / 1000).toInt()
        }
        onDismiss()
    }
    val time = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    FerryDialog(
        onDismiss = onDismiss,
        eyebrow = when {
            secondsLeft > 90 -> "Starts in ${(secondsLeft + 30) / 60} min"
            secondsLeft > 5 -> "Starts in ${secondsLeft} s"
            else -> "Starting now"
        },
        eyebrowIcon = FerryIcons.Bell,
        title = reminder.title,
        subtitle = "${time.format(Date(reminder.start))} – ${time.format(Date(reminder.stop))}  ·  ${reminder.channelName}",
        width = 560.dp,
    ) {
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onWatch, colors = ferryButtonColors(), modifier = Modifier.focusRequester(watch)) { Text("Watch now") }
            Button(onClick = onDismiss, colors = ferryOutlinedButtonColorsAsButton()) { Text("Dismiss") }
        }
    }
}

/** Shown for the sleep timer's last minute; MainActivity cancels the timer on any key. */
@Composable
fun SleepWarning(modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(999.dp)).background(FerryColors.Navy.copy(alpha = 0.96f))
            .padding(horizontal = 22.dp, vertical = 12.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        androidx.tv.material3.Icon(FerryIcons.Moon, null, tint = FerryColors.Gold, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text("Sleep timer: Ferry stops in under a minute.", color = FerryColors.Text, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.width(10.dp))
        Text("Press any button to keep watching", color = FerryColors.Muted, style = MaterialTheme.typography.bodyMedium)
    }
}


/** Offered when a newer GitHub release is out (never over full-screen playback). */
@Composable
fun UpdatePrompt(state: dev.sysmoon.ferry.update.UpdateState, onUpdate: () -> Unit, onLater: () -> Unit) {
    val primary = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { primary.requestFocus() }
    }
    FerryDialog(onDismiss = onLater, eyebrow = "Update available", eyebrowIcon = FerryIcons.Download, width = 600.dp) {
            when (state) {
                is dev.sysmoon.ferry.update.UpdateState.Available -> {
                    Text("Ferry ${state.release.version}", style = MaterialTheme.typography.headlineSmall)
                    Text("You have ${dev.sysmoon.ferry.BuildConfig.VERSION_NAME}. Your settings and channels are kept.", color = FerryColors.Muted, style = MaterialTheme.typography.bodyMedium)
                    val notes = state.release.notes.lines().map { it.trim() }
                        .filter { (it.startsWith("* ") || it.startsWith("- ")) && "Full Changelog" !in it }.take(5)
                        .joinToString("\n") { "•  " + it.trimStart('*', '-', ' ').substringBefore(" by @") }
                    if (notes.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(notes, style = MaterialTheme.typography.bodyMedium, color = FerryColors.Text, maxLines = 6, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = onUpdate, colors = ferryButtonColors(), modifier = Modifier.focusRequester(primary)) { Text("Update now") }
                        Button(onClick = onLater, colors = ferryOutlinedButtonColorsAsButton()) { Text("Later") }
                    }
                }
                is dev.sysmoon.ferry.update.UpdateState.Downloading -> {
                    Text("Downloading Ferry ${state.release.version}… ${state.percent}%", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(10.dp))
                    ProgressLine(state.percent / 100f, Modifier.width(400.dp))
                    Spacer(Modifier.height(18.dp))
                    Button(onClick = onLater, colors = ferryButtonColors(), modifier = Modifier.focusRequester(primary)) { Text("Hide") }
                }
                is dev.sysmoon.ferry.update.UpdateState.Installing -> {
                    Text("Installing Ferry ${state.release.version}…", style = MaterialTheme.typography.titleMedium)
                    Text("Confirm with Install if Android asks. Ferry restarts on the new version.", color = FerryColors.Muted, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(18.dp))
                    Button(onClick = onLater, colors = ferryButtonColors(), modifier = Modifier.focusRequester(primary)) { Text("Hide") }
                }
                is dev.sysmoon.ferry.update.UpdateState.Failed -> {
                    Text("The update didn't install", style = MaterialTheme.typography.titleMedium)
                    Text(state.message, color = FerryColors.Muted, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = onUpdate, colors = ferryButtonColors(), modifier = Modifier.focusRequester(primary)) { Text("Try again") }
                        Button(onClick = onLater, colors = ferryOutlinedButtonColorsAsButton()) { Text("Later") }
                    }
                }
                else -> Unit
            }
    }
}

/**
 * After hours of unattended playback: "Still watching?" with a one-minute countdown. Any key
 * keeps going (MainActivity dismisses it); when it runs out, playback stops and the guide shows.
 */
@Composable
fun StillWatching(hours: Int, onKeep: () -> Unit, onStop: () -> Unit) {
    val keep = remember { FocusRequester() }
    var secondsLeft by remember { mutableStateOf(60) }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { keep.requestFocus() }
        while (secondsLeft > 0) { delay(1_000); secondsLeft-- }
        onStop()
    }
    FerryDialog(
        onDismiss = onKeep,
        eyebrow = "Still watching?",
        eyebrowIcon = FerryIcons.Moon,
        title = "Ferry has been on for $hours hour${if (hours == 1) "" else "s"}",
        subtitle = "Playback stops in $secondsLeft s unless you press a button.",
        width = 560.dp,
    ) {
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onKeep, colors = ferryButtonColors(), modifier = Modifier.focusRequester(keep)) { Text("Keep watching") }
            Button(onClick = onStop, colors = ferryOutlinedButtonColorsAsButton()) { Text("Stop") }
        }
    }
}

/** Playback stats (long-press Info): what the stream is and how the TV is coping with it. */
@Composable
fun StatsOverlay(player: dev.sysmoon.ferry.player.PlayerController, modifier: Modifier = Modifier) {
    var stats by remember { mutableStateOf(dev.sysmoon.ferry.player.PlayerStats()) }
    var droppedPerMin by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        var lastDropped = -1
        var lastAt = 0L
        while (true) {
            val s = runCatching { player.stats() }.getOrNull()
            if (s != null) {
                stats = s
                val now = System.currentTimeMillis()
                if (lastDropped >= 0 && now > lastAt) {
                    droppedPerMin = ((s.droppedFrames - lastDropped) * 60_000L / (now - lastAt)).toInt().coerceAtLeast(0)
                }
                lastDropped = s.droppedFrames; lastAt = now
            }
            delay(1_000)
        }
    }
    val now = player.state.collectAsState().value
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(FerryColors.Deep.copy(alpha = 0.9f)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("PLAYBACK", color = FerryColors.Gold, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(4.dp))
        @Composable fun line(label: String, value: String?) {
            if (value.isNullOrBlank()) return
            Row {
                Text(label, color = FerryColors.Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(110.dp))
                Text(value, color = FerryColors.Text, style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = TABULAR))
            }
        }
        line("Channel", now.channel?.name)
        if (now.streamCount > 1) line("Stream", now.stream?.name)
        line("Video", listOfNotNull(stats.resolution, stats.frameRate.takeIf { it > 0 }?.let { "%.0f fps".format(it) },
            stats.videoCodec, if (stats.hdr) "HDR" else null).joinToString("  ·  ").ifBlank { null })
        line("Audio", listOfNotNull(stats.audioCodec, stats.audioChannels.takeIf { it > 0 }?.let { "${it}ch" }).joinToString("  ·  ").ifBlank { null })
        line("Bitrate", stats.bitrate.takeIf { it > 0 }?.let { "%.1f Mbit/s".format(it / 1_000_000f) })
        line("Buffer", "%.1f s".format(stats.bufferedMs / 1000f))
        line("Dropped", "${stats.droppedFrames} frames" + (if (droppedPerMin > 0) "  ·  $droppedPerMin/min" else ""))
        line("Decoder", listOfNotNull(stats.decoder, if (stats.tunnelled) "tunnelled" else null).joinToString("  ·  ").ifBlank { null })
        line("Version", dev.sysmoon.ferry.BuildConfig.VERSION_NAME)
    }
}
