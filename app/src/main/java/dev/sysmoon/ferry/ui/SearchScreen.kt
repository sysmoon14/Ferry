@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.sysmoon.ferry.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import dev.sysmoon.ferry.data.ChannelEntity
import dev.sysmoon.ferry.data.ProgrammeEntity
import dev.sysmoon.ferry.data.displayName
import dev.sysmoon.ferry.data.epgKey
import dev.sysmoon.ferry.data.shownNumber
import dev.sysmoon.ferry.graph
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private sealed interface Hit {
    val channel: ChannelEntity
    data class Channel(override val channel: ChannelEntity) : Hit
    data class Programme(override val channel: ChannelEntity, val programme: ProgrammeEntity) : Hit
}

/**
 * Search channels and programmes (catch-up, on now, and coming up). OK on a channel or a
 * programme that's on plays it, on a past one plays catch-up, on a future one sets a reminder.
 */
@Composable
fun SearchScreen(onOpenPlayer: () -> Unit) {
    val graph = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<Hit>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var fieldFocused by remember { mutableStateOf(false) }
    val field = remember { FocusRequester() }
    val firstResult = remember { FocusRequester() }
    val reminders by graph.reminders.reminders.collectAsState()
    val reminded = remember(reminders) { reminders.map { it.key }.toSet() }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { field.requestFocus() }
        keyboard?.show()
    }
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) { hits = emptyList(); searching = false; return@LaunchedEffect }
        searching = true
        delay(350) // wait for typing to pause
        hits = runCatching { search(graph, q) }.getOrDefault(emptyList())
        searching = false
    }

    val time = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    val day = remember { SimpleDateFormat("EEE d MMM", Locale.getDefault()) }

    Column(Modifier.fillMaxSize().background(FerryColors.Deep).padding(horizontal = 56.dp, vertical = 40.dp)) {
        ScreenTitle("Search", FerryIcons.Search)
        Spacer(Modifier.height(18.dp))
        Row(
            Modifier.width(620.dp).clip(RoundedCornerShape(14.dp))
                .background(if (fieldFocused) FerryColors.FocusFill else FerryColors.Navy)
                .border(2.dp, if (fieldFocused) FerryColors.Gold else FerryColors.Line, RoundedCornerShape(14.dp))
                .padding(horizontal = 18.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.tv.material3.Icon(FerryIcons.Search, null, tint = if (fieldFocused) FerryColors.Gold else FerryColors.Muted, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("Programme or channel name", color = FerryColors.Faint, style = MaterialTheme.typography.bodyLarge)
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = FerryColors.Text, fontSize = 19.sp),
                    cursorBrush = SolidColor(FerryColors.Gold),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        keyboard?.hide()
                        scope.launch { withFrameNanos { }; runCatching { firstResult.requestFocus() } }
                    }),
                    modifier = Modifier.fillMaxWidth().focusRequester(field).onFocusChanged { fieldFocused = it.isFocused },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Hint(
            when {
                query.trim().length < 2 -> "Type at least two letters   ·   ▼  results"
                searching -> "Searching…"
                hits.isEmpty() -> "Nothing found"
                else -> "${hits.size} results   ·   OK  watch (or catch-up)   ·   OK on a later programme  remind me"
            },
            color = FerryColors.Muted,
        )
        Spacer(Modifier.height(10.dp))
        LazyColumn(
            Modifier.weight(1f).focusRestorer(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp),
        ) {
            items(hits, key = { h -> if (h is Hit.Programme) "p${h.channel.id}@${h.programme.start}" else "c${h.channel.id}" }) { h ->
                val now = System.currentTimeMillis()
                val ch = h.channel
                val p = (h as? Hit.Programme)?.programme
                val status: (@Composable () -> Unit)? = when {
                    p == null -> null
                    p.start <= now && p.stop > now -> ({ Tag("Live", FerryColors.Live, dot = true) })
                    p.stop <= now -> ({ Tag("Catch-up", FerryColors.Sea, filled = false) })
                    "${ch.epgKey}@${p.start}" in reminded -> ({ Tag("Reminder set", FerryColors.Sea) })
                    else -> ({ Tag("Later", FerryColors.Muted, filled = false) })
                }
                ListItem(
                    selected = false,
                    onClick = {
                        val t = System.currentTimeMillis()
                        when {
                            p == null || (p.start <= t && p.stop > t) -> { graph.player.start(ch); onOpenPlayer() }
                            p.stop <= t -> { graph.player.startCatchUp(ch, p); onOpenPlayer() }
                            else -> scope.launch { graph.reminders.toggle(ch, p) }
                        }
                    },
                    modifier = if (h == hits.first()) Modifier.focusRequester(firstResult) else Modifier,
                    colors = ferryListItemColors(),
                    border = ferryListItemBorder(),
                    shape = ferryListItemShape(),
                    scale = ferryListItemScale(),
                    leadingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${ch.shownNumber}", color = FerryColors.Faint, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(44.dp), maxLines = 1)
                            LogoChip(ch.logo, ch.name, width = 58.dp, height = 36.dp)
                        }
                    },
                    headlineContent = {
                        Text(p?.title ?: ch.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    },
                    supportingContent = {
                        val dayText = p?.let { if (sameDay(it.start, now)) "Today" else day.format(Date(it.start)) }
                        Text(
                            if (p == null) "Channel" + (ch.groupTitle?.let { "  ·  $it" } ?: "")
                            else "$dayText ${time.format(Date(p.start))}–${time.format(Date(p.stop))}  ·  ${ch.displayName}",
                            color = FerryColors.Muted, style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = TABULAR), maxLines = 1,
                        )
                    },
                    trailingContent = status,
                )
            }
        }
    }
}

private suspend fun search(graph: dev.sysmoon.ferry.AppGraph, q: String): List<Hit> {
    val now = System.currentTimeMillis()
    val channels = graph.db.channels().searchVisible(q, 20).map { Hit.Channel(it) }
    // Programmes from the last week (catch-up) onwards; matched to visible channels by guide key.
    val programmes = graph.db.programmes().search(q, now - 7 * 86_400_000L, 400)
    val byKey = programmes.map { it.channelKey }.distinct().chunked(500)
        .flatMap { graph.db.channels().visibleByEpgKeys(it) }
        .groupBy { it.epgKey }
        .mapValues { (_, list) -> list.minWith(compareBy({ it.skyNumber == null }, { it.skyNumber ?: 0 }, { it.position })) }
    val progHits = programmes.mapNotNull { p ->
        val ch = byKey[p.channelKey] ?: return@mapNotNull null
        // Past programmes only when they can actually be watched again.
        if (p.stop <= now && !graph.player.canCatchUp(ch, p, now)) return@mapNotNull null
        Hit.Programme(ch, p)
    }.distinctBy { "${it.channel.id}@${it.programme.start}" }
    // On now first, then coming up soonest, then catch-up most recent first.
    val sorted = progHits.sortedWith(compareBy<Hit.Programme>(
        { when { it.programme.start <= now && it.programme.stop > now -> 0; it.programme.start > now -> 1; else -> 2 } },
        { if (it.programme.start > now) it.programme.start else -it.programme.start },
    ))
    return channels + sorted.take(80)
}

private fun sameDay(a: Long, b: Long): Boolean {
    val z = java.time.ZoneId.systemDefault()
    return java.time.Instant.ofEpochMilli(a).atZone(z).toLocalDate() == java.time.Instant.ofEpochMilli(b).atZone(z).toLocalDate()
}
