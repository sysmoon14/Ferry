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
import androidx.compose.foundation.border

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
            secondsLeft > 90 -> "${if (reminder.fixtureId != null) "Kicks off" else "Starts"} in ${(secondsLeft + 30) / 60} min"
            secondsLeft > 5 -> "${if (reminder.fixtureId != null) "Kicks off" else "Starts"} in ${secondsLeft} s"
            reminder.fixtureId != null -> "Kicking off now"
            else -> "Starting now"
        },
        eyebrowIcon = FerryIcons.Bell,
        title = reminder.title,
        subtitle = if (reminder.fixtureId != null) "Kick-off ${time.format(Date(reminder.start))}  ·  ${reminder.channelName}"
            else "${time.format(Date(reminder.start))} – ${time.format(Date(reminder.stop))}  ·  ${reminder.channelName}",
        width = 560.dp,
    ) {
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onWatch, colors = ferryButtonColors(), modifier = Modifier.focusRequester(watch)) { Text(if (reminder.fixtureId != null) "Find a stream" else "Watch now") }
            Button(onClick = onDismiss, colors = ferryOutlinedButtonColorsAsButton(), border = ferrySecondaryButtonBorder()) { Text("Dismiss") }
        }
    }
}

/**
 * A reminder while something is on full screen: a banner in the corner instead of a dialog over
 * the picture. It takes no focus; MainActivity gives it OK (switch) and Back (dismiss). It goes
 * away by itself shortly after the programme starts.
 */
@Composable
fun ReminderBanner(reminder: Reminder, onExpired: () -> Unit, modifier: Modifier = Modifier) {
    var secondsLeft by remember(reminder.key) { mutableStateOf(((reminder.start - System.currentTimeMillis()) / 1000).toInt()) }
    LaunchedEffect(reminder.key) {
        while (secondsLeft > -45) {
            delay(1_000)
            secondsLeft = ((reminder.start - System.currentTimeMillis()) / 1000).toInt()
        }
        onExpired()
    }
    val match = reminder.fixtureId != null
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier.width(430.dp).clip(shape).background(FerryColors.Navy.copy(alpha = 0.96f))
            .border(androidx.compose.foundation.BorderStroke(1.dp, FerryColors.Line), shape)
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            androidx.tv.material3.Icon(FerryIcons.Bell, null, tint = FerryColors.Gold, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    secondsLeft > 90 -> "${if (match) "Kicks off" else "Starts"} in ${(secondsLeft + 30) / 60} min"
                    secondsLeft > 5 -> "${if (match) "Kicks off" else "Starts"} in $secondsLeft s"
                    match -> "Kicking off now"
                    else -> "Starting now"
                }.uppercase(),
                color = FerryColors.Gold, style = MaterialTheme.typography.labelSmall,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(reminder.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(reminder.channelName, color = FerryColors.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(10.dp))
        Hint(if (match) "OK  find a stream   ·   Back  dismiss" else "OK  switch   ·   Back  dismiss")
    }
}

/** Asks before something that can't be undone from the TV. Cancel has the focus. */
@Composable
fun ConfirmDialog(title: String, body: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val cancel = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { cancel.requestFocus() }
    }
    FerryDialog(onDismiss = onDismiss, eyebrow = "Are you sure?", eyebrowIcon = FerryIcons.Warning, title = title, subtitle = body, width = 560.dp) {
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onDismiss, colors = ferryButtonColors(), modifier = Modifier.focusRequester(cancel)) { Text("Cancel") }
            Button(onClick = onConfirm, colors = ferryOutlinedButtonColorsAsButton(), border = ferrySecondaryButtonBorder()) { Text(confirm) }
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
                    // The release's own summary: the first plain line of its notes. GitHub's generated
                    // list of commits (headings and bullets) isn't shown: it isn't written for viewers.
                    val notes = state.release.notes.lines().map { it.trim() }
                        .firstOrNull { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("* ") && !it.startsWith("- ") && "Full Changelog" !in it }
                        ?.take(220).orEmpty()
                    if (notes.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(notes, style = MaterialTheme.typography.bodyMedium, color = FerryColors.Text, maxLines = 6, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = onUpdate, colors = ferryButtonColors(), modifier = Modifier.focusRequester(primary)) { Text("Update now") }
                        Button(onClick = onLater, colors = ferryOutlinedButtonColorsAsButton(), border = ferrySecondaryButtonBorder()) { Text("Later") }
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
                        Button(onClick = onLater, colors = ferryOutlinedButtonColorsAsButton(), border = ferrySecondaryButtonBorder()) { Text("Later") }
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
            Button(onClick = onStop, colors = ferryOutlinedButtonColorsAsButton(), border = ferrySecondaryButtonBorder()) { Text("Stop") }
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
