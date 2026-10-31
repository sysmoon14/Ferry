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
