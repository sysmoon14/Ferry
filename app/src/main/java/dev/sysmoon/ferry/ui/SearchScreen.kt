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
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.tv.material3.Button

private sealed interface Hit {
    val channel: ChannelEntity
    data class Channel(override val channel: ChannelEntity) : Hit
    data class Programme(override val channel: ChannelEntity, val programme: ProgrammeEntity) : Hit
}

/**
 * Search channels and programmes (catch-up, on now, and coming up). OK on a channel or a
 * programme that's on plays it, on a past one plays catch-up, on a future one sets a reminder.
 * Results are grouped under headings; before anything is typed the screen offers recent searches.
 * The microphone button (or the remote's search key) takes the query by voice where the TV can.
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
    val recent by remember { graph.settings.recentSearches }.collectAsState(initial = emptyList())
    // A short confirmation in the hint line ("Reminder set for 21:00").
    var flash by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(flash) { if (flash != null) { delay(3_500); flash = null } }

    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        graph.awaitingResult = false
        val said = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!said.isNullOrBlank()) {
            query = said
            scope.launch { delay(700); runCatching { firstResult.requestFocus() } }
        }
    }
    fun speak() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Programme or channel name")
        graph.awaitingResult = true
        runCatching { voice.launch(intent) }.onFailure {
            graph.awaitingResult = false
            flash = "Voice search isn't available on this TV"
        }
    }

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
    val typed = query.trim().length >= 2

    Column(
        Modifier.fillMaxSize().background(FerryColors.Deep).padding(horizontal = 56.dp, vertical = 40.dp)
            .onPreviewKeyEvent { e ->
                // The remote's search / microphone key: say it instead of typing it.
                if (e.key == Key.Search || e.key == Key.VoiceAssist) {
                    if (e.type == KeyEventType.KeyUp) speak()
                    true
                } else false
            }
    ) {
        ScreenTitle("Search", FerryIcons.Search)
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
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
                    if (query.isEmpty()) Text("Programme or channel name", color = FerryColors.Muted, style = MaterialTheme.typography.bodyLarge)
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
            Spacer(Modifier.width(12.dp))
            Button(onClick = { speak() }, colors = ferryButtonColors()) {
                androidx.tv.material3.Icon(FerryIcons.Mic, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Speak")
            }
        }
        Spacer(Modifier.height(12.dp))
        Hint(
            flash ?: when {
                !typed -> "Type at least two letters, or press Speak"
                searching && hits.isEmpty() -> "Searching…"
                hits.isEmpty() -> "Nothing found"
                else -> "${hits.size} results   ·   OK  watch, catch up or set a reminder"
            },
            color = if (flash != null) FerryColors.Sea else FerryColors.Muted,
            icon = if (flash?.startsWith("Reminder set") == true) FerryIcons.Bell else null,
        )
        Spacer(Modifier.height(10.dp))

        val now = System.currentTimeMillis()
        when {
            // Nothing typed yet: recent searches, or what Search can do.
            !typed && recent.isNotEmpty() -> LazyColumn(
                Modifier.weight(1f).focusRestorer(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp),
            ) {
                item(key = "recent") { SectionLabel("Recent searches") }
                items(recent, key = { "r$it" }) { q ->
                    ListItem(
                        selected = false,
                        onClick = { query = q; keyboard?.hide() },
                        colors = ferryListItemColors(), border = ferryListItemBorder(), shape = ferryListItemShape(), scale = ferryListItemScale(),
                        leadingContent = { androidx.tv.material3.Icon(FerryIcons.History, null, modifier = Modifier.size(20.dp)) },
                        headlineContent = { Text(q, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall) },
                    )
                }
            }
            !typed -> EmptyMessage(
                FerryIcons.Search, "Find a programme or a channel",
                "Search covers what's on now, what's coming up, and the last week of catch-up.",
                Modifier.weight(1f).fillMaxWidth(),
            )
            searching && hits.isEmpty() -> SkeletonRows(5, Modifier.weight(1f))
            hits.isEmpty() -> EmptyMessage(
                FerryIcons.Search, "Nothing found",
                "No channel or programme matches “${query.trim()}”. Hidden channels aren't searched.",
                Modifier.weight(1f).fillMaxWidth(),
            )
            else -> {
                val programmes = hits.filterIsInstance<Hit.Programme>()
                val sections = listOf(
                    "Channels" to hits.filterIsInstance<Hit.Channel>(),
                    "On now" to programmes.filter { it.programme.start <= now && it.programme.stop > now },
                    "Coming up" to programmes.filter { it.programme.start > now },
                    "Catch-up" to programmes.filter { it.programme.stop <= now },
                ).filter { it.second.isNotEmpty() }
                val firstHit = sections.first().second.first()
                LazyColumn(
                    Modifier.weight(1f).focusRestorer(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp),
                ) {
                    sections.forEach { (title, list) ->
                        item(key = "h$title") { SectionLabel("$title  ·  ${list.size}") }
                        items(list, key = { h -> if (h is Hit.Programme) "p${h.channel.id}@${h.programme.start}" else "c${h.channel.id}" }) { h ->
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
                                    scope.launch { graph.settings.pushRecentSearch(query) }
                                    when {
                                        p == null || (p.start <= t && p.stop > t) -> { graph.player.start(ch); onOpenPlayer() }
                                        p.stop <= t -> { graph.player.startCatchUp(ch, p); onOpenPlayer() }
                                        else -> scope.launch {
                                            // Say what happened: only a small tag used to change.
                                            flash = if (graph.reminders.toggle(ch, p)) "Reminder set for ${time.format(Date(p.start))} on ${ch.displayName}"
                                                else "Reminder removed"
                                        }
                                    }
                                },
                                modifier = if (h == firstHit) Modifier.focusRequester(firstResult) else Modifier,
                                colors = ferryListItemColors(),
                                border = ferryListItemBorder(),
                                shape = ferryListItemShape(),
                                scale = ferryListItemScale(),
                                leadingContent = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("${ch.shownNumber}", color = FerryColors.Muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(44.dp), maxLines = 1)
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
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), color = FerryColors.Muted, style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp, start = 12.dp))
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
