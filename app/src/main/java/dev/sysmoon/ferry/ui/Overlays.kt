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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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

/** "Starting now" pop-up for a reminder. Goes away by itself after two minutes. */
@Composable
fun ReminderPopup(reminder: Reminder, onWatch: () -> Unit, onDismiss: () -> Unit) {
    val watch = remember { FocusRequester() }
    LaunchedEffect(reminder.key) {
        withFrameNanos { }
        runCatching { watch.requestFocus() }
        delay(120_000)
        onDismiss()
    }
    val time = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    FerryDialog(
        onDismiss = onDismiss,
        eyebrow = "Starting now",
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
