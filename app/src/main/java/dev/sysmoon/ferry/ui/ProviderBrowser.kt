package dev.sysmoon.ferry.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.sysmoon.ferry.data.ProviderList
import dev.sysmoon.ferry.data.StreamQuality
import dev.sysmoon.ferry.graph

/** Rail key for the provider list. */
const val PROVIDER = "\u0000provider"

/**
 * The provider list: every category as the source sends it, in its order, and inside one every
 * stream on its own row — duplicates Ferry folded into another channel, channels the Sky layout
 * leaves out, hidden ones too. OK plays exactly that stream (no fallback to another one). Back
 * goes from a category to the categories, and from there to the group list.
 */
@Composable
fun ProviderBrowser(
    active: Boolean,
    /** Focus requests for the guide's channels; only those for [PROVIDER] are ours. */
    focusRequest: FocusRequest?,
    /** Changes when the playlists do, to reload the categories. */
    refreshKey: Any,
    onOpenPlayer: () -> Unit,
    /** Back from the categories: to the group list. */
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val graph = LocalContext.current.graph
    val list = graph.repository.providerList
    var open by remember { mutableStateOf<ProviderList.Category?>(null) }
    val categories by produceState<List<ProviderList.Category>?>(null, refreshKey) {
        value = runCatching { list.categories() }.getOrDefault(emptyList())
    }
    val streams by produceState<List<ProviderList.Stream>?>(null, open, refreshKey) {
        value = null
        val c = open ?: return@produceState
        value = runCatching { list.streams(c.sourceId, c.name) }.getOrDefault(emptyList())
    }
    val nowPlaying by graph.player.state.collectAsState()
    val playingId = nowPlaying.stream?.id
    val manySources = categories.orEmpty().map { it.sourceId }.distinct().size > 1

    val categoryState = remember { LazyListState() }
    val streamState = remember(open) { LazyListState() }
    var focused by remember { mutableStateOf(false) }

    // Where focus should land next: a row key, found by index in the list on screen.
    var target by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var targetN by remember { mutableIntStateOf(0) }
    val targetFocus = remember { FocusRequester() }
    fun focus(key: String, index: Int) { target = key to index; targetN++ }

    fun categoryKey(c: ProviderList.Category) = "c${c.sourceId}:${c.name ?: "\u0000"}"

    // A move into the channels from the group list (or back from the player).
    var doneRequest by remember { mutableIntStateOf(0) }
    LaunchedEffect(focusRequest, categories, streams) {
        val r = focusRequest ?: return@LaunchedEffect
        if (r.n == doneRequest || r.group != PROVIDER) return@LaunchedEffect
        val c = open
        if (c != null) {
            val rows = streams ?: return@LaunchedEffect // still loading: runs again when they arrive
            doneRequest = r.n
            val id = (r.target as? GuideFocus.Channel)?.id
            val index = rows.indexOfFirst { it.channel.id == id }.coerceAtLeast(0)
            rows.getOrNull(index)?.let { focus("s${it.channel.id}", index) }
        } else {
            val cats = categories ?: return@LaunchedEffect
            doneRequest = r.n
            cats.firstOrNull()?.let { focus(categoryKey(it), 0) }
        }
    }
    LaunchedEffect(targetN) {
        val (_, index) = target ?: return@LaunchedEffect
        val state = if (open != null) streamState else categoryState
        if (state.layoutInfo.visibleItemsInfo.none { it.index == index }) state.scrollToItem((index - 2).coerceAtLeast(0))
        // The row composes over the next frame or two: keep trying before giving up.
        for (attempt in 0 until 20) {
            withFrameNanos { }
            if (runCatching { targetFocus.requestFocus() }.isSuccess) break
        }
    }
    // Opening a category starts at its first stream; closing it goes back to that category.
    var opened by remember { mutableStateOf<ProviderList.Category?>(null) }
    LaunchedEffect(streams) {
        val rows = streams ?: return@LaunchedEffect
        if (open != null && open != opened) { opened = open; rows.firstOrNull()?.let { focus("s${it.channel.id}", 0) } }
    }
    BackHandler(enabled = active && focused) {
        val c = open
        if (c == null) { onBack(); return@BackHandler }
        open = null
        opened = null
        val index = categories.orEmpty().indexOf(c)
        if (index >= 0) focus(categoryKey(c), index)
    }

    Column(modifier.onFocusChanged { focused = it.hasFocus }) {
        val c = open
        Text(
            when {
                c != null -> "${c.name ?: "No category"}  ·  ${"%,d".format(c.count)} streams  ·  OK plays that stream only"
                categories != null -> "${"%,d".format(categories!!.size)} categories, every stream as your provider lists them"
                else -> ""
            },
            color = FerryColors.Muted,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        val cats = categories
        when {
            c == null && cats == null -> SkeletonRows(6, Modifier.weight(1f))
            c == null && cats!!.isEmpty() -> EmptyMessage(
                FerryIcons.Layers, "Nothing from your provider yet",
                "Add a playlist in Settings; its categories show here after the first refresh.",
                Modifier.weight(1f).fillMaxWidth(),
            )
            c == null -> LazyColumn(
                Modifier.weight(1f),
                state = categoryState,
                verticalArrangement = Arrangement.spacedBy(2.dp),
                contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp),
            ) {
                items(cats!!, key = { categoryKey(it) }) { cat ->
                    val key = categoryKey(cat)
                    ListItem(
                        selected = false,
                        onClick = { open = cat },
                        modifier = if (target?.first == key) Modifier.focusRequester(targetFocus) else Modifier,
                        colors = ferryListItemColors(),
                        border = ferryListItemBorder(),
                        shape = ferryListItemShape(),
                        scale = ferryListItemScale(),
                        headlineContent = {
                            Text(cat.name ?: "No category", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                        },
                        supportingContent = if (manySources) ({
                            Text(cat.source, color = FerryColors.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        }) else null,
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("%,d".format(cat.count), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                                Icon(FerryIcons.ChevronRight, null, tint = FerryColors.Muted, modifier = Modifier.padding(start = 6.dp).size(20.dp))
                            }
                        },
                    )
                }
            }
            streams == null -> SkeletonRows(6, Modifier.weight(1f))
            streams!!.isEmpty() -> EmptyMessage(
                FerryIcons.Layers, "This category is empty now",
                "The playlist changed since it was listed. Press Back for the categories.",
                Modifier.weight(1f).fillMaxWidth(),
            )
            else -> LazyColumn(
                Modifier.weight(1f),
                state = streamState,
                verticalArrangement = Arrangement.spacedBy(2.dp),
                contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp),
            ) {
                items(streams!!, key = { "s${it.channel.id}" }) { s ->
                    val ch = s.channel
                    val playing = ch.id == playingId
                    ListItem(
                        selected = playing,
                        onClick = { graph.player.startExact(ch); onOpenPlayer() },
                        modifier = if (target?.first == "s${ch.id}") Modifier.focusRequester(targetFocus) else Modifier,
                        colors = ferryListItemColors(),
                        border = ferryListItemBorder(),
                        shape = ferryListItemShape(),
                        scale = ferryListItemScale(),
                        leadingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    ch.number?.toString().orEmpty(),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = FerryColors.Muted,
                                    modifier = Modifier.width(44.dp),
                                    maxLines = 1,
                                )
                                LogoChip(ch.logo, ch.name, width = 64.dp, height = 40.dp)
                            }
                        },
                        // The provider's own name, quality tags and all.
                        headlineContent = {
                            Text(ch.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                        },
                        supportingContent = {
                            Text(
                                listOfNotNull(
                                    StreamQuality.parse(ch.name).label,
                                    ch.tvgId?.let { "guide $it" } ?: "no guide id",
                                ).joinToString("  ·  "),
                                color = FerryColors.Muted,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (ch.primaryId != null) Tag("Backup", FerryColors.Muted, filled = false)
                                if (ch.hidden) { Spacer(Modifier.width(6.dp)); Tag("Hidden", FerryColors.Muted, filled = false) }
                                if (playing) { Spacer(Modifier.width(6.dp)); Tag("Watching", FerryColors.Text) }
                            }
                        },
                    )
                }
            }
        }
    }
}
