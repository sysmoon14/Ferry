package dev.sysmoon.ferry.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import dev.sysmoon.ferry.data.ChannelEntity
import dev.sysmoon.ferry.data.ProgrammeEntity
import dev.sysmoon.ferry.data.epgKey
import dev.sysmoon.ferry.graph
import dev.sysmoon.ferry.player.ZapScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val SLOT_MS = 30 * 60_000L
private const val WINDOW_MS = 2 * 3_600_000L        // visible span: 2 hours
private const val LOAD_BUCKET_MS = 3 * 3_600_000L   // programmes are loaded in 3h buckets
private const val DAY_MS = 86_400_000L
private const val FUTURE_LIMIT_MS = DAY_MS           // how far ahead the grid scrolls
private const val NO_ARCHIVE_BACK_MS = 2 * 3_600_000L // how far back rows without catch-up go
private val CHANNEL_COL: Dp = 230.dp
private val ROW_HEIGHT: Dp = 62.dp

private fun floorSlot(t: Long) = t - Math.floorMod(t, SLOT_MS)

/**
 * TiviMate-style timeline guide. Rows are the channels of the selected list; the time window
 * moves in 30-minute steps when focus is pushed past either edge. OK on a programme that's on
 * now plays live; OK on a past programme plays it from the provider's archive when the channel
 * has catch-up. Long-press OK toggles the channel as a favourite.
 */
@Composable
fun GuideGrid(
    channels: List<ChannelEntity>,
    zap: ZapScope,
    returnTo: String?,
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val graph = LocalContext.current.graph
    val scope = rememberCoroutineScope()

    // Ticks every minute (now line, live/past styling) and nudges a programme reload every 10.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(60_000 - System.currentTimeMillis() % 60_000)
            value = System.currentTimeMillis()
        }
    }
    val nowSlot = floorSlot(now)
    var windowStart by remember { mutableLongStateOf(floorSlot(System.currentTimeMillis())) }
    val windowEnd = windowStart + WINDOW_MS
    // Back while browsing another time jumps back to now, like TiviMate.
    BackHandler(enabled = windowStart != nowSlot) { windowStart = nowSlot }

    val bucket = Math.floorDiv(windowStart, LOAD_BUCKET_MS)
    val reload = now / (10 * 60_000L)
    val programmes by produceState(emptyMap<String, List<ProgrammeEntity>>(), channels, bucket, reload) {
        val from = bucket * LOAD_BUCKET_MS - LOAD_BUCKET_MS
        val until = bucket * LOAD_BUCKET_MS + 3 * LOAD_BUCKET_MS
        val keys = channels.map { it.epgKey }.distinct()
        value = runCatching {
            keys.chunked(500).flatMap { graph.db.programmes().window(it, from, until) }.groupBy { it.channelKey }
        }.getOrDefault(emptyMap())
    }

    var focused by remember { mutableStateOf<Pair<ChannelEntity, ProgrammeEntity?>?>(null) }

    // Back from the player: scroll to the channel and focus what's on now.
    val listState = rememberLazyListState()
    val returnFocus = remember { FocusRequester() }
    var restored by remember { mutableStateOf(false) }
    LaunchedEffect(channels, returnTo, programmes) {
        if (restored || returnTo == null || channels.isEmpty() || programmes.isEmpty()) return@LaunchedEffect
        val index = channels.indexOfFirst { it.id == returnTo }
        restored = true
        if (index < 0) return@LaunchedEffect
        listState.scrollToItem((index - 2).coerceAtLeast(0))
        withFrameNanos { }
        runCatching { returnFocus.requestFocus() }
    }

    Column(modifier) {
        DetailPanel(focused, now)
        TimeRuler(windowStart, now)
        LazyColumn(
            Modifier.weight(1f),
            state = listState,
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            items(channels, key = { it.id }) { channel ->
                GridRow(
                    channel = channel,
                    programmes = programmes[channel.epgKey].orEmpty(),
                    windowStart = windowStart,
                    windowEnd = windowEnd,
                    now = now,
                    focusRequester = if (channel.id == returnTo) returnFocus else null,
                    canGoBack = { target ->
                        val limit = if (channel.catchupDays > 0) nowSlot - channel.catchupDays.coerceAtMost(7) * DAY_MS
                            else nowSlot - NO_ARCHIVE_BACK_MS
                        target >= limit
                    },
                    onShift = { delta ->
                        val target = windowStart + delta
                        if (target <= nowSlot + FUTURE_LIMIT_MS) windowStart = target
                    },
                    onFocus = { p -> focused = channel to p },
                    onSelect = { p ->
                        val t = System.currentTimeMillis()
                        when {
                            p == null || (p.start <= t && p.stop > t) -> {
                                graph.player.start(channel, zap); onOpenPlayer()
                            }
                            p.stop <= t && graph.player.canCatchUp(channel, p, t) -> {
                                graph.player.startCatchUp(channel, p, zap); onOpenPlayer()
                            }
                            // Future, or past without an archive: nothing to play.
                        }
                    },
                    onLongPress = { scope.launch { graph.db.channels().setFavourite(channel.id, !channel.favourite) } },
                )
            }
        }
    }
}

@Composable
private fun DetailPanel(focused: Pair<ChannelEntity, ProgrammeEntity?>?, now: Long) {
    val graph = LocalContext.current.graph
    val time = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    val day = remember { SimpleDateFormat("EEE d MMM", Locale.getDefault()) }
    Column(Modifier.fillMaxWidth().height(132.dp).padding(start = 4.dp, bottom = 8.dp)) {
        val (channel, p) = focused ?: return@Column
        Text(
            p?.title ?: channel.name,
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
            val bits = buildList {
                if (p != null) {
                    val dayText = if (floorDay(p.start) == floorDay(now)) "Today" else day.format(Date(p.start))
                    add("$dayText ${time.format(Date(p.start))}–${time.format(Date(p.stop))}")
                }
                add(channel.name)
            }
            Text(bits.joinToString("  ·  "), color = FerryColors.Muted)
            when {
                p != null && p.start <= now && p.stop > now -> Tag("LIVE", FerryColors.Gold)
                p != null && p.stop <= now && graph.player.canCatchUp(channel, p, now) -> Tag("CATCH-UP", FerryColors.Sea)
            }
            if (channel.favourite) Tag("★", FerryColors.Gold)
        }
        p?.description?.let {
            Text(it, color = FerryColors.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun floorDay(t: Long): Long {
    val z = java.time.ZoneId.systemDefault()
    return java.time.Instant.ofEpochMilli(t).atZone(z).toLocalDate().toEpochDay()
}

@Composable
private fun Tag(text: String, color: Color) {
    Text(
        text,
        color = FerryColors.Deep,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(start = 12.dp).clip(RoundedCornerShape(4.dp)).background(color)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

@Composable
private fun TimeRuler(windowStart: Long, now: Long) {
    val time = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    Row(Modifier.fillMaxWidth().height(28.dp)) {
        Box(Modifier.width(CHANNEL_COL), contentAlignment = Alignment.CenterStart) {
            val label = if (floorDay(windowStart) == floorDay(now)) "Today"
                else SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(windowStart))
            Text(label, color = FerryColors.Gold, style = MaterialTheme.typography.labelLarge)
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
            val perMs = maxWidth / WINDOW_MS.toFloat()
            var t = windowStart
            while (t < windowStart + WINDOW_MS) {
                Text(
                    time.format(Date(t)),
                    color = FerryColors.Muted,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.offset(x = perMs * (t - windowStart).toFloat() + 6.dp),
                )
                t += SLOT_MS
            }
            NowLine(windowStart, now, perMs)
        }
    }
}

@Composable
private fun NowLine(windowStart: Long, now: Long, perMs: Dp) {
    if (now < windowStart || now >= windowStart + WINDOW_MS) return
    Box(
        Modifier.offset(x = perMs * (now - windowStart).toFloat()).width(2.dp).fillMaxHeight()
            .background(FerryColors.Gold)
    )
}

@Composable
private fun GridRow(
    channel: ChannelEntity,
    programmes: List<ProgrammeEntity>,
    windowStart: Long,
    windowEnd: Long,
    now: Long,
    focusRequester: FocusRequester?,
    canGoBack: (Long) -> Boolean,
    onShift: (Long) -> Unit,
    onFocus: (ProgrammeEntity?) -> Unit,
    onSelect: (ProgrammeEntity?) -> Unit,
    onLongPress: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(ROW_HEIGHT).padding(vertical = 3.dp)) {
        // Channel column
        Row(Modifier.width(CHANNEL_COL).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${channel.number ?: channel.position + 1}",
                color = FerryColors.Muted,
                modifier = Modifier.width(44.dp),
                maxLines = 1,
            )
            Box(
                Modifier.size(width = 52.dp, height = 34.dp).clip(RoundedCornerShape(4.dp)).background(FerryColors.Navy),
                contentAlignment = Alignment.Center,
            ) {
                if (channel.logo != null) AsyncImage(channel.logo, null, Modifier.padding(3.dp))
                else Text(channel.name.take(3).uppercase(), color = FerryColors.Muted, style = MaterialTheme.typography.labelSmall)
            }
            Text(
                (if (channel.favourite) "★ " else "") + channel.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 10.dp, end = 8.dp),
            )
        }

        // Programmes
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
            val perMs = maxWidth / WINDOW_MS.toFloat()
            val visible = programmes.filter { it.stop > windowStart && it.start < windowEnd }
            val cells: List<ProgrammeEntity?> = visible.ifEmpty { listOf(null) }
            // The cell to focus when returning from the player: what's on now, else the first.
            val returnIndex = cells.indexOfFirst { it != null && it.start <= now && it.stop > now }.coerceAtLeast(0)

            cells.forEachIndexed { i, p ->
                key(p?.start ?: -1L) {
                    val left = p?.let { maxOf(it.start, windowStart) } ?: windowStart
                    val right = p?.let { minOf(it.stop, windowEnd) } ?: windowEnd
                    val x = perMs * (left - windowStart).toFloat()
                    val w = (perMs * (right - left).toFloat() - 3.dp).coerceAtLeast(6.dp)
                    val past = p != null && p.stop <= now
                    val live = p == null || (p.start <= now && p.stop > now)
                    var mod = Modifier.offset(x = x).width(w).fillMaxHeight()
                    if (focusRequester != null && i == returnIndex) mod = mod.focusRequester(focusRequester)
                    Surface(
                        onClick = { onSelect(p) },
                        onLongClick = onLongPress,
                        modifier = mod
                            .onFocusChanged { if (it.isFocused) onFocus(p) }
                            .onPreviewKeyEvent { e ->
                                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when (e.key) {
                                    // At the right edge: move the window on rather than leaving the row.
                                    Key.DirectionRight -> if (i == cells.lastIndex && right >= windowEnd) {
                                        onShift(SLOT_MS); true
                                    } else false
                                    // At the left edge: go back in time while there's history to show;
                                    // otherwise let focus move out to the group list.
                                    Key.DirectionLeft -> if (i == 0 && left <= windowStart && canGoBack(windowStart - SLOT_MS)) {
                                        onShift(-SLOT_MS); true
                                    } else false
                                    else -> false
                                }
                            },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = if (live) Color(0xFF14243A) else FerryColors.Navy,
                            contentColor = if (past) FerryColors.Muted else FerryColors.Text,
                            focusedContainerColor = Color(0xFF1B2D47),
                            focusedContentColor = FerryColors.Text,
                        ),
                        border = ClickableSurfaceDefaults.border(
                            focusedBorder = Border(BorderStroke(2.dp, FerryColors.Gold), shape = RoundedCornerShape(6.dp)),
                        ),
                    ) {
                        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                when {
                                    p == null -> "No guide data"
                                    p.stop <= now && channel.catchupDays > 0 &&
                                        p.start > now - channel.catchupDays * 86_400_000L -> "↺ ${p.title}"
                                    else -> p.title
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
            NowLine(windowStart, now, perMs)
        }
    }
}
