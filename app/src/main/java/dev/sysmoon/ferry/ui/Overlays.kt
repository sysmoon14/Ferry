package dev.sysmoon.ferry.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(560.dp).clip(RoundedCornerShape(14.dp)).background(FerryColors.Deep)
                .padding(horizontal = 28.dp, vertical = 22.dp)
        ) {
            Text("🔔  STARTING NOW", color = FerryColors.Gold, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            Text(reminder.title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                "${time.format(Date(reminder.start))} – ${time.format(Date(reminder.stop))}  ·  ${reminder.channelName}",
                color = FerryColors.Muted,
            )
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onWatch, colors = ferryButtonColors(), modifier = Modifier.focusRequester(watch)) { Text("Watch now") }
                Button(onClick = onDismiss, colors = ferryButtonColors()) { Text("Dismiss") }
            }
        }
    }
}

/** Shown for the sleep timer's last minute; MainActivity cancels the timer on any key. */
@Composable
fun SleepWarning(modifier: Modifier = Modifier) {
    Text(
        "☾  Sleep timer: Ferry stops in under a minute. Press any button to keep watching.",
        color = FerryColors.Text,
        modifier = modifier.clip(RoundedCornerShape(12.dp)).background(FerryColors.Navy.copy(alpha = 0.95f))
            .padding(horizontal = 24.dp, vertical = 14.dp),
    )
}


/** Offered when a newer GitHub release is out (never over full-screen playback). */
@Composable
fun UpdatePrompt(state: dev.sysmoon.ferry.update.UpdateState, onUpdate: () -> Unit, onLater: () -> Unit) {
    val primary = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { primary.requestFocus() }
    }
    Dialog(onDismissRequest = onLater) {
        Column(
            Modifier.width(600.dp).clip(RoundedCornerShape(14.dp)).background(FerryColors.Deep)
                .padding(horizontal = 28.dp, vertical = 22.dp)
        ) {
            Text("⬆  UPDATE AVAILABLE", color = FerryColors.Gold, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            when (state) {
                is dev.sysmoon.ferry.update.UpdateState.Available -> {
                    Text("Ferry ${state.release.version}", style = MaterialTheme.typography.headlineSmall)
                    Text("You have ${dev.sysmoon.ferry.BuildConfig.VERSION_NAME}. Your settings and channels are kept.", color = FerryColors.Muted)
                    val notes = state.release.notes.lines().map { it.trim() }
                        .filter { (it.startsWith("* ") || it.startsWith("- ")) && "Full Changelog" !in it }.take(5)
                        .joinToString("\n") { "•  " + it.trimStart('*', '-', ' ').substringBefore(" by @") }
                    if (notes.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(notes, style = MaterialTheme.typography.bodySmall, maxLines = 6, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = onUpdate, colors = ferryButtonColors(), modifier = Modifier.focusRequester(primary)) { Text("Update now") }
                        Button(onClick = onLater, colors = ferryButtonColors()) { Text("Later") }
                    }
                }
                is dev.sysmoon.ferry.update.UpdateState.Downloading -> {
                    Text("Downloading Ferry ${state.release.version}… ${state.percent}%", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(10.dp))
                    androidx.compose.foundation.layout.Box(
                        Modifier.width(400.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(FerryColors.Navy)
                    ) {
                        androidx.compose.foundation.layout.Box(
                            Modifier.width((400 * state.percent / 100).dp).height(4.dp).background(FerryColors.Gold)
                        )
                    }
                    Spacer(Modifier.height(18.dp))
                    Button(onClick = onLater, colors = ferryButtonColors(), modifier = Modifier.focusRequester(primary)) { Text("Hide") }
                }
                is dev.sysmoon.ferry.update.UpdateState.Installing -> {
                    Text("Installing Ferry ${state.release.version}…", style = MaterialTheme.typography.titleMedium)
                    Text("Confirm with Install if Android asks. Ferry restarts on the new version.", color = FerryColors.Muted)
                    Spacer(Modifier.height(18.dp))
                    Button(onClick = onLater, colors = ferryButtonColors(), modifier = Modifier.focusRequester(primary)) { Text("Hide") }
                }
                is dev.sysmoon.ferry.update.UpdateState.Failed -> {
                    Text("The update didn't install", style = MaterialTheme.typography.titleMedium)
                    Text(state.message, color = FerryColors.Muted)
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = onUpdate, colors = ferryButtonColors(), modifier = Modifier.focusRequester(primary)) { Text("Try again") }
                        Button(onClick = onLater, colors = ferryButtonColors()) { Text("Later") }
                    }
                }
                else -> Unit
            }
        }
    }
}
