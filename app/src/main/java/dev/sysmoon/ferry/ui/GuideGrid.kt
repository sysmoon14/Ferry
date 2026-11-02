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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.catch
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collectLatest
import dev.sysmoon.ferry.data.ChannelEntity
import dev.sysmoon.ferry.data.ProgrammeEntity
import dev.sysmoon.ferry.data.epgKey
import dev.sysmoon.ferry.data.displayName
import dev.sysmoon.ferry.data.shownNumber
import dev.sysmoon.ferry.data.archiveDays
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
private const val LOAD_MARGIN = 30                    // rows either side of the screen to load programmes for
private val CHANNEL_COL: Dp = 210.dp
private val ROW_HEIGHT: Dp = 34.dp

private fun floorSlot(t: Long) = t - Math.floorMod(t, SLOT_MS)

/**
 * TiviMate-style timeline guide. Rows are the channels of the selected list; the time window
 * moves in 30-minute steps when focus is pushed past either edge. OK on a programme that's on
 * now plays live; OK on a past programme plays it from the provider's archive when the channel
 * has catch-up. Long-press OK opens the channel menu. Channel +/− page a screenful of rows at a
 * time; Play/Pause jumps the window back to now.
 */
@Composable
fun GuideGrid(
    channels: List<ChannelEntity>,
    zap: ZapScope,
    /** The channel playing: kept in view behind the player and focused when the guide returns. */
    playingId: String?,
    active: Boolean,
    playerShowing: Boolean,
    /** Bumped when the guide comes back from the player (0 = never / not for this grid). */
    returnSignal: Int,
    onOpenPlayer: () -> Unit,
    /** Long press: open the channel menu (favourite, stream picker). */
    onMenu: (ChannelEntity) -> Unit,
    /** Back: return to now and hand focus to the group list. */
    onBackToGroups: () -> Unit,
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

    val bucket = Math.floorDiv(windowStart, LOAD_BUCKET_MS)
    // Reload when guide data changes (a refresh replaces it), not just on a timer.
    val epgVersion by remember { graph.db.programmes().observeCount().catch { emit(-1) } }.collectAsState(initial = 0)
    // Programmes are loaded for the rows on screen plus a margin either side, as the list scrolls
    // (a 1000-channel list no longer pulls every programme of the next 12 hours at once). The map
    // starts again when the list, the time bucket or the guide data changes.
    val listState = rememberLazyListState()
    var programmes by remember { mutableStateOf(emptyMap<String, List<ProgrammeEntity>>()) }
    LaunchedEffect(channels, bucket, epgVersion) {
        programmes = emptyMap()
        val loaded = HashSet<String>()
        val from = bucket * LOAD_BUCKET_MS - LOAD_BUCKET_MS
        val until = bucket * LOAD_BUCKET_MS + 3 * LOAD_BUCKET_MS
        snapshotFlow { listState.firstVisibleItemIndex to listState.layoutInfo.visibleItemsInfo.size }
            .collectLatest { (first, count) ->
                val lo = (first - LOAD_MARGIN).coerceAtLeast(0)
                val hi = (first + count.coerceAtLeast(25) + LOAD_MARGIN).coerceAtMost(channels.size)
                if (lo >= hi) return@collectLatest
                val keys = channels.subList(lo, hi).map { it.epgKey }.distinct().filter { it !in loaded }
                if (keys.isEmpty()) return@collectLatest
                val rows = runCatching { graph.db.programmes().window(keys, from, until) }.getOrDefault(emptyList()).groupBy { it.channelKey }
                loaded += keys
                programmes = programmes + keys.associateWith { rows[it].orEmpty() }
            }
    }

    var focused by remember { mutableStateOf<Pair<ChannelEntity, ProgrammeEntity?>?>(null) }
    val reminderList by graph.reminders.reminders.collectAsState()
    val reminded = remember(reminderList) { reminderList.map { it.key }.toSet() }

    // Time cursor (like TiviMate): left/right sets it, up/down keeps it, so moving between rows
    // lands on whatever is on at the same time instead of the geometrically nearest cell.
    var cursorTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var verticalNav by remember { mutableStateOf(false) }
    val requesters = remember { HashMap<Pair<String, Long>, FocusRequester>() }
    fun requester(channelId: String, start: Long) = requesters.getOrPut(channelId to start) { FocusRequester() }

    // Back: jump back to now and go to the group list in one press (no scrolling back through
    // catch-up to reach the groups).
    var gridFocused by remember { mutableStateOf(false) }
    BackHandler(enabled = active && gridFocused) {
        windowStart = nowSlot
        cursorTime = System.currentTimeMillis()
        onBackToGroups()
    }
    // Outside the grid (group list, player) the guide follows the clock, so Back in the group
    // list is free to leave the app and the grid is current when you come back to it.
    LaunchedEffect(gridFocused, nowSlot) {
        if (!gridFocused) { windowStart = nowSlot; cursorTime = System.currentTimeMillis() }
    }
    fun cellAt(ch: ChannelEntity, t: Long): Long {
        val visible = programmes[ch.epgKey].orEmpty().filter { it.stop > windowStart && it.start < windowEnd }
        if (visible.isEmpty()) return -1L
        return (visible.firstOrNull { it.start <= t && it.stop > t } ?: visible.minBy { kotlin.math.abs(it.start - t) }).start
    }

    // While the player covers the guide, keep the playing channel in view at "now", so the guide
    // is already in place when the player shrinks away; then focus what's on now.
    val returnFocus = remember { FocusRequester() }
    LaunchedEffect(playingId, channels, playerShowing) {
        if (!playerShowing) return@LaunchedEffect
        windowStart = floorSlot(System.currentTimeMillis())
        cursorTime = System.currentTimeMillis()
        val index = channels.indexOfFirst { it.id == playingId }
        if (index >= 0) listState.scrollToItem((index - 2).coerceAtLeast(0))
    }
    LaunchedEffect(returnSignal) {
        if (returnSignal == 0) return@LaunchedEffect
        val index = channels.indexOfFirst { it.id == playingId }
        if (index < 0) { onBackToGroups(); return@LaunchedEffect }
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
            listState.scrollToItem((index - 2).coerceAtLeast(0))
        }
        withFrameNanos { }
        if (runCatching { returnFocus.requestFocus() }.isFailure) onBackToGroups()
    }

    var focusedIndex by remember { mutableIntStateOf(-1) }
    /** Focus row [target] at the time cursor, scrolling it on screen first if needed. */
    fun moveTo(target: Int, scrollTo: Int? = null): Boolean {
        if (target !in channels.indices) return false
        val ch = channels[target]
        val req = requester(ch.id, cellAt(ch, cursorTime.coerceIn(windowStart, windowEnd - 1)))
        scope.launch {
            // Off-screen rows aren't composed yet: scroll first.
            if (listState.layoutInfo.visibleItemsInfo.none { it.index == target }) listState.scrollToItem(scrollTo ?: target)
            withFrameNanos { } // let the row (or a shifted window's cells) compose first
            verticalNav = true
            if (runCatching { req.requestFocus() }.isFailure) verticalNav = false
        }
        return true
    }
    /** Rows that fit on screen: a page for Channel +/−. */
    fun pageSize() = listState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)

    Column(
        modifier.onFocusChanged { gridFocused = it.hasFocus }.onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown || !gridFocused) return@onPreviewKeyEvent false
            when (e.key) {
                // A screenful at a time, like a set-top box's page keys.
                Key.ChannelUp, Key.PageUp -> moveTo((focusedIndex - pageSize()).coerceAtLeast(0), scrollTo = (focusedIndex - pageSize()).coerceAtLeast(0))
                Key.ChannelDown, Key.PageDown -> (focusedIndex + pageSize()).coerceAtMost(channels.lastIndex).let { moveTo(it, scrollTo = it) }
                // Jump back to what's on now, from wherever in time the grid has been scrolled to.
                Key.MediaPlayPause, Key.MediaPlay -> {
                    windowStart = nowSlot
                    cursorTime = System.currentTimeMillis()
                    moveTo(focusedIndex.coerceAtLeast(0))
                }
                else -> false
            }
        },
    ) {
        DetailPanel(focused, now, reminded)
        TimeRuler(windowStart, now)
        // Fill the rest of the screen; the last row may be partly visible.
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = listState,
        ) {
            itemsIndexed(channels, key = { _, ch -> ch.id }) { index, channel ->
                GridRow(
                    channel = channel,
                    programmes = programmes[channel.epgKey].orEmpty(),
                    reminded = reminded,
                    windowStart = windowStart,
                    windowEnd = windowEnd,
                    now = now,
                    focusRequester = if (channel.id == playingId) returnFocus else null,
                    canGoBack = { target ->
                        val limit = if (channel.archiveDays > 0) nowSlot - channel.archiveDays.coerceAtMost(7) * DAY_MS
                            else nowSlot - NO_ARCHIVE_BACK_MS
                        target >= limit
                    },
                    onShift = { delta, p ->
                        val target = windowStart + delta
                        if (target <= nowSlot + FUTURE_LIMIT_MS) {
                            windowStart = target
                            cursorTime = maxOf(p?.start ?: target, target)
                        }
                    },
                    onFocus = { p ->
                        focused = channel to p
                        focusedIndex = index
                        if (verticalNav) verticalNav = false
                        else cursorTime = maxOf(p?.start ?: windowStart, windowStart)
                    },
                    requesterFor = { start -> requester(channel.id, start) },
                    onVertical = { delta ->
                        moveTo(index + delta, scrollTo = if (delta > 0) listState.firstVisibleItemIndex + 1 else index + delta)
                    },
                    onSelect = { p ->
                        val t = System.currentTimeMillis()
                        when {
                            p == null || (p.start <= t && p.stop > t) -> {
                                graph.player.start(channel, zap); onOpenPlayer()
                            }
                            p.stop <= t && graph.player.canCatchUp(channel, p, t) -> {
                                graph.player.startCatchUp(channel, p, zap); onOpenPlayer()
                            }
                            // Coming up: OK sets (or clears) a reminder.
                            p.start > t -> scope.launch { graph.reminders.toggle(channel, p) }
                            // Past without an archive: nothing to play.
                        }
                    },
                    onLongPress = { onMenu(channel) },
                )
            }
        }
    }
}

@Composable
private fun DetailPanel(focused: Pair<ChannelEntity, ProgrammeEntity?>?, now: Long, reminded: Set<String>) {
    val graph = LocalContext.current.graph
    val time = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    val day = remember { SimpleDateFormat("EEE d MMM", Locale.getDefault()) }
    // Keep clear of the corner mini player while a stream is playing; tall enough that the
    // time ruler below starts under it.
    val miniShowing = graph.player.state.collectAsState().value.channel != null
    Column(Modifier.fillMaxWidth().height(92.dp).padding(start = 4.dp, bottom = 6.dp, end = if (miniShowing) MINI_CLEARANCE else 0.dp)) {
        val (channel, p) = focused ?: return@Column
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                p?.title ?: channel.displayName,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(12.dp))
            when {
                p != null && p.start <= now && p.stop > now -> Tag("Live", FerryColors.Live, dot = true)
                p != null && p.stop <= now && graph.player.canCatchUp(channel, p, now) -> Tag("Catch-up", FerryColors.Sea)
                p != null && p.start > now && "${channel.epgKey}@${p.start}" in reminded -> Tag("Reminder set", FerryColors.Sea)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp)) {
            val bits = buildList {
                if (p != null) {
                    val dayText = if (floorDay(p.start) == floorDay(now)) "Today" else day.format(Date(p.start))
                    add("$dayText ${time.format(Date(p.start))}–${time.format(Date(p.stop))}")
                }
                add(channel.displayName)
                if (channel.streamCount > 1) add("${channel.streamCount} streams")
                if (p != null && p.start > now && "${channel.epgKey}@${p.start}" !in reminded) add("OK to set a reminder")
            }
            if (channel.favourite) {
                androidx.tv.material3.Icon(FerryIcons.Star, null, tint = FerryColors.Gold, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(bits.joinToString("  ·  "), color = FerryColors.Muted, style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = TABULAR),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            p?.description ?: if (p == null) "No guide data for this channel" else "",
            color = FerryColors.Faint, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

private fun floorDay(t: Long): Long {
    val z = java.time.ZoneId.systemDefault()
    return java.time.Instant.ofEpochMilli(t).atZone(z).toLocalDate().toEpochDay()
}

@Composable
private fun TimeRuler(windowStart: Long, now: Long) {
    val time = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    Row(Modifier.fillMaxWidth().height(22.dp).padding(bottom = 2.dp)) {
        Box(Modifier.width(CHANNEL_COL).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
            val label = if (floorDay(windowStart) == floorDay(now)) "Today"
                else SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(windowStart))
            Text(label.uppercase(), color = FerryColors.Gold, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 2.dp))
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
            val perMs = maxWidth / WINDOW_MS.toFloat()
            var t = windowStart
            while (t < windowStart + WINDOW_MS) {
                Text(
                    time.format(Date(t)),
                    color = FerryColors.Faint,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.align(Alignment.CenterStart).offset(x = perMs * (t - windowStart).toFloat() + 6.dp),
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
    reminded: Set<String>,
    windowStart: Long,
    windowEnd: Long,
    now: Long,
    focusRequester: FocusRequester?,
    canGoBack: (Long) -> Boolean,
    onShift: (Long, ProgrammeEntity?) -> Unit,
    onFocus: (ProgrammeEntity?) -> Unit,
    requesterFor: (Long) -> FocusRequester,
    onVertical: (Int) -> Boolean,
    onSelect: (ProgrammeEntity?) -> Unit,
    onLongPress: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(ROW_HEIGHT).padding(vertical = 2.dp)) {
        // Channel column
        Row(Modifier.width(CHANNEL_COL).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${channel.shownNumber}",
                color = FerryColors.Faint,
                modifier = Modifier.width(38.dp),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
            LogoChip(channel.logo, channel.name, width = 40.dp, height = 24.dp)
            if (channel.favourite) {
                androidx.tv.material3.Icon(FerryIcons.Star, null, tint = FerryColors.Gold, modifier = Modifier.padding(start = 7.dp).size(12.dp))
            }
            Text(
                channel.displayName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
                modifier = Modifier.padding(start = if (channel.favourite) 5.dp else 8.dp, end = 8.dp),
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
                    var mod = Modifier.offset(x = x).width(w).fillMaxHeight().focusRequester(requesterFor(p?.start ?: -1L))
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
                                        onShift(SLOT_MS, p); true
                                    } else false
                                    Key.DirectionUp -> onVertical(-1)
                                    Key.DirectionDown -> onVertical(1)
                                    // At the left edge: go back in time while there's history to show;
                                    // otherwise let focus move out to the group list.
                                    Key.DirectionLeft -> if (i == 0 && left <= windowStart && canGoBack(windowStart - SLOT_MS)) {
                                        onShift(-SLOT_MS, p); true
                                    } else false
                                    else -> false
                                }
                            },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = when { p == null -> Color(0xFF0F1828); live -> Color(0xFF1A2A44); else -> FerryColors.Navy },
                            contentColor = when { p == null -> FerryColors.Faint; past -> FerryColors.Muted; else -> FerryColors.Text },
                            focusedContainerColor = FerryColors.FocusFill,
                            focusedContentColor = FerryColors.Text,
                        ),
                        border = ClickableSurfaceDefaults.border(
                            focusedBorder = Border(BorderStroke(2.dp, FerryColors.Gold), shape = RoundedCornerShape(6.dp)),
                        ),
                    ) {
                        // Very short programmes are too narrow for any text; the detail panel names them.
                        if (w > 40.dp) Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            val reminderSet = p != null && "${channel.epgKey}@${p.start}" in reminded
                            val catchUp = p != null && p.stop <= now && channel.archiveDays > 0 &&
                                p.start > now - channel.archiveDays * 86_400_000L
                            if (reminderSet || catchUp) {
                                androidx.tv.material3.Icon(
                                    if (reminderSet) FerryIcons.Bell else FerryIcons.Replay, null,
                                    tint = if (reminderSet) FerryColors.Sea else FerryColors.Muted,
                                    modifier = Modifier.padding(end = 5.dp).size(13.dp),
                                )
                            }
                            Text(
                                p?.title ?: "No guide data",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontWeight = if (live && p != null) androidx.compose.ui.text.font.FontWeight.Medium else androidx.compose.ui.text.font.FontWeight.Normal,
                                ),
                            )
                        }
                    }
                }
            }
            NowLine(windowStart, now, perMs)
        }
    }
}
