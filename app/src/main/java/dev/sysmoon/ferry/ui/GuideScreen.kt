@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package dev.sysmoon.ferry.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.DrawerValue
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.ModalNavigationDrawer
import androidx.tv.material3.NavigationDrawerItem
import androidx.tv.material3.NavigationDrawerItemDefaults
import androidx.tv.material3.NavigationDrawerScope
import androidx.tv.material3.Text
import dev.sysmoon.ferry.data.ChannelEntity
import dev.sysmoon.ferry.data.displayName
import dev.sysmoon.ferry.data.epgKey
import dev.sysmoon.ferry.data.shownNumber
import dev.sysmoon.ferry.graph
import dev.sysmoon.ferry.player.ZapScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

const val FAVOURITES = "\u0000fav"
const val ALL = "\u0000all"
const val RECENT = "\u0000recent"
/** Rail keys for Sky layout sections ("\u0000sky:Sports"). */
const val SKY_PREFIX = "\u0000sky:"
private const val GUIDE_LIMIT = 1_000

/** Top-level menu sections that live on the guide screen (Sports, Search and Settings open their own screens). */
enum class Section(val label: String) {
    FAVOURITES("Favourites"),
    GUIDE("TV Guide"),
    LIST("Channels"),
}

/** Where focus is on the guide screen: the main menu, the group list, or the channels. */
private enum class Level { MENU, RAIL, CONTENT }

private enum class MenuItem(val label: String, val icon: ImageVector) {
    SEARCH("Search", FerryIcons.Search),
    FAVOURITES("Favourites", FerryIcons.Star),
    SPORTS("Sports", FerryIcons.Football),
    GUIDE("TV Guide", FerryIcons.Grid),
    LIST("Channels", FerryIcons.List),
    SETTINGS("Settings", FerryIcons.Settings),
}

private fun MenuItem.section() = when (this) {
    MenuItem.FAVOURITES -> Section.FAVOURITES
    MenuItem.GUIDE -> Section.GUIDE
    MenuItem.LIST -> Section.LIST
    else -> null
}

private val RAIL_WIDTH = 236.dp
/** Width of the collapsed nav rail: its 56dp icon items plus 12dp padding each side. */
private val NAV_COLLAPSED = 80.dp

@Composable
fun GuideScreen(
    /** The group picked in TV Guide / Channel List (Favourites has its own list). */
    selected: String,
    onSelect: (String) -> Unit,
    section: Section,
    onSection: (Section) -> Unit,
    /** The channel playing (kept in view behind the player, focused when coming back to the guide). */
    playingId: String?,
    /** False while another screen (player, search, settings) is on top. */
    active: Boolean,
    /** The full-screen player is covering the guide. */
    playerShowing: Boolean,
    /** Bumped each time the guide comes back into view. */
    returnSignal: Int,
    /** Coming back from search/settings/sports: focus the main menu rather than a channel. */
    returnToRail: Boolean,
    onOpenPlayer: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenFootball: () -> Unit,
) {
    val graph = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    val dao = graph.db.channels()
    // Only the selected group is ever loaded (capped), so 20k+ channel lineups stay light.
    // .catch: a failed read (e.g. mid-refresh) shows an empty list instead of crashing.
    val groups by remember { dao.observeGroups().catch { emit(emptyList()) } }
        .collectAsState(initial = emptyList())
    val total by remember { dao.observeVisibleCount().catch { emit(0) } }.collectAsState(initial = 0)
    // Sky layout: Sky's sections replace provider groups; picked provider groups follow as extras.
    val skyLayout by remember { graph.settings.skyLayout }.collectAsState(initial = false)
    val skySections by remember { dao.observeSkySections().catch { emit(emptyList()) } }.collectAsState(initial = emptyList())
    val skyExtras by remember { graph.settings.skyExtraGroups }.collectAsState(initial = emptySet())
    val sky = skyLayout && skySections.isNotEmpty()
    val railGroups = if (sky) groups.filter { it in skyExtras } else groups
    val nowPlaying by graph.player.state.collectAsState()
    val recents by remember { graph.settings.recents }.collectAsState(initial = emptyList())

    val gridView = section != Section.LIST
    val hasRail = section != Section.FAVOURITES
    val validKeys = buildSet {
        add(ALL); if (recents.isNotEmpty()) add(RECENT)
        if (sky) skySections.forEach { add(SKY_PREFIX + it) }
        addAll(railGroups)
    }
    // Fall back to All when the selection disappears (layout switched, recents cleared…).
    val effective = when {
        section == Section.FAVOURITES -> FAVOURITES
        selected == RECENT && recents.isEmpty() -> ALL
        groups.isEmpty() && selected != FAVOURITES -> selected // lists still loading: keep the selection
        selected in validKeys -> selected
        else -> ALL
    }
    val shownFlow = remember(effective, sky) {
        when {
            effective == FAVOURITES -> dao.observeFavourites()
            effective == RECENT -> graph.settings.recents.flatMapLatest { ids ->
                dao.observeByIds(ids).map { rows -> ids.mapNotNull { id -> rows.firstOrNull { it.id == id && !it.hidden } } }
            }
            effective == ALL && sky -> dao.observeSkyAll()
            effective == ALL -> dao.observeFirst(GUIDE_LIMIT)
            effective.startsWith(SKY_PREFIX) -> dao.observeSkySection(effective.removePrefix(SKY_PREFIX))
            else -> dao.observeGroup(effective, GUIDE_LIMIT)
        }.catch { emit(emptyList()) }
    }
    val shown by shownFlow.collectAsState(initial = emptyList())
    val zapScope = when {
        effective == FAVOURITES -> ZapScope.Favourites
        effective == RECENT -> ZapScope.Recent
        effective == ALL && sky -> ZapScope.Sky(null)
        effective == ALL -> ZapScope.All
        effective.startsWith(SKY_PREFIX) -> ZapScope.Sky(effective.removePrefix(SKY_PREFIX))
        else -> ZapScope.Group(effective)
    }
    // Long-press menu (favourite + stream picker) for this channel, if open.
    var menuFor by remember { mutableStateOf<ChannelEntity?>(null) }
    menuFor?.let { ChannelMenu(it, zapScope, onDismiss = { menuFor = null }, onPlayed = onOpenPlayer) }

    // Each group gets its own scroll position, so opening a group always starts at the top.
    val listState = remember(effective) { androidx.compose.foundation.lazy.LazyListState() }
    val returnFocus = remember { FocusRequester() }

    data class RailEntry(val key: String, val label: String, val icon: ImageVector? = null)
    val rail = buildList {
        if (recents.isNotEmpty()) add(RailEntry(RECENT, "Recently watched"))
        add(RailEntry(ALL, "All channels"))
        if (sky) skySections.forEach { add(RailEntry(SKY_PREFIX + it, it)) }
        railGroups.forEach { add(RailEntry(it, it)) }
    }
    val currentMenuItem = when (section) {
        Section.FAVOURITES -> MenuItem.FAVOURITES
        Section.GUIDE -> MenuItem.GUIDE
        Section.LIST -> MenuItem.LIST
    }

    // Three levels: the main menu (a rail that opens out while it has focus), then (TV Guide and
    // Channels) the group list, then the channels. Back walks back up; Back in the main menu
    // leaves the app.
    val railState = rememberLazyListState()
    val railFocus = remember { FocusRequester() }
    val menuFocus = remember { FocusRequester() }
    val contentFocus = remember { FocusRequester() }
    var level by remember { mutableStateOf(if (playerShowing) Level.CONTENT else Level.MENU) }
    var channelsFocused by remember { mutableStateOf(false) }
    var railFocused by remember { mutableStateOf(false) }
    LaunchedEffect(channelsFocused) { if (channelsFocused) level = Level.CONTENT }
    // Already in its final state when the player shrinks away.
    LaunchedEffect(playerShowing) { if (playerShowing) level = Level.CONTENT }

    val focusMenu: () -> Unit = {
        scope.launch {
            if (level != Level.MENU) { level = Level.MENU; withFrameNanos { } }
            runCatching { menuFocus.requestFocus() }
        }
    }
    val focusRail: () -> Unit = {
        scope.launch {
            if (level != Level.RAIL) { level = Level.RAIL; withFrameNanos { } }
            val index = rail.indexOfFirst { it.key == effective }.coerceAtLeast(0)
            if (railState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
                railState.scrollToItem(index)
                withFrameNanos { }
            }
            runCatching { railFocus.requestFocus() }
        }
    }
    /** [toMenu]: where to fall back to if nothing in the channels can take focus. */
    val focusContent: (toMenu: Boolean) -> Unit = { toMenu ->
        scope.launch {
            level = Level.CONTENT
            withFrameNanos { }
            runCatching { contentFocus.requestFocus() }
            withFrameNanos { }
            // Nothing to focus (no favourites yet, list still loading): stay where we were
            // rather than leaving focus nowhere.
            if (!channelsFocused) { if (toMenu) focusMenu() else focusRail() }
        }
    }
    /** Back (or Left at the edge) from the channels. */
    val backFromContent: () -> Unit = { if (hasRail) focusRail() else focusMenu() }
    fun enter(item: MenuItem) {
        when (item) {
            MenuItem.SEARCH -> onOpenSearch()
            MenuItem.SPORTS -> onOpenFootball()
            MenuItem.SETTINGS -> onOpenSettings()
            else -> {
                val s = item.section()!!
                onSection(s)
                if (s == Section.FAVOURITES) focusContent(true) else focusRail()
            }
        }
    }
    BackHandler(enabled = active && channelsFocused && !gridView) { backFromContent() }
    BackHandler(enabled = active && railFocused) { focusMenu() }

    LaunchedEffect(Unit) { if (!playerShowing) { withFrameNanos { }; runCatching { menuFocus.requestFocus() } } }
    // List view: while the player covers the guide, keep the playing channel in view; when the
    // guide comes back, focus it (or the main menu, after search/settings/sports).
    LaunchedEffect(playingId, shown, playerShowing) {
        if (gridView || !playerShowing) return@LaunchedEffect
        val index = shown.indexOfFirst { it.id == playingId }
        if (index >= 0) listState.scrollToItem((index - 2).coerceAtLeast(0))
    }
    LaunchedEffect(returnSignal) {
        if (returnSignal == 0) return@LaunchedEffect
        if (returnToRail) { focusMenu(); return@LaunchedEffect }
        if (gridView) return@LaunchedEffect // the grid handles its own
        val index = shown.indexOfFirst { it.id == playingId }
        if (index < 0) { backFromContent(); return@LaunchedEffect }
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
            listState.scrollToItem((index - 2).coerceAtLeast(0))
        }
        withFrameNanos { } // let the row compose before focusing it
        runCatching { returnFocus.requestFocus() }
    }

    ModalNavigationDrawer(
        modifier = Modifier.fillMaxSize().background(FerryColors.Deep),
        drawerContent = { drawerValue ->
            NavRail(
                open = drawerValue == DrawerValue.Open,
                current = currentMenuItem,
                menuFocus = menuFocus,
                onEnter = ::enter,
            )
        },
    ) {
        // The drawer overlays the content (it doesn't push it), so leave its collapsed width clear.
        Row(Modifier.fillMaxSize().padding(start = NAV_COLLAPSED + 20.dp, end = 32.dp, top = 14.dp, bottom = 10.dp)) {
            // Group list (TV Guide and Channels)
            AnimatedVisibility(
                visible = level == Level.RAIL && hasRail,
                enter = expandHorizontally(tween(220)) + fadeIn(tween(220)),
                exit = shrinkHorizontally(tween(180)) + fadeOut(tween(120)),
            ) {
                Row {
                    Column(Modifier.width(RAIL_WIDTH).fillMaxHeight().onFocusChanged { railFocused = it.hasFocus }) {
                        Text(
                            currentMenuItem.label,
                            style = MaterialTheme.typography.headlineSmall,
                            maxLines = 1,
                            modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 14.dp),
                        )
                        LazyColumn(
                            Modifier.weight(1f),
                            state = railState,
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp),
                        ) {
                            items(rail, key = { it.key }) { entry ->
                                val isSelected = entry.key == effective
                                ListItem(
                                    modifier = if (isSelected) Modifier.focusRequester(railFocus) else Modifier,
                                    selected = isSelected,
                                    onClick = { onSelect(entry.key); focusContent(false) },
                                    leadingContent = if (entry.icon != null) ({ Icon(entry.icon, null, modifier = Modifier.size(20.dp)) }) else null,
                                    headlineContent = {
                                        Text(entry.label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                                    },
                                    trailingContent = if (isSelected) ({ Icon(FerryIcons.ChevronRight, null, tint = FerryColors.Gold, modifier = Modifier.size(20.dp)) }) else null,
                                    colors = ferryListItemColors(),
                                    border = ferryListItemBorder(),
                                    shape = ferryListItemShape(),
                                    scale = ferryListItemScale(),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(24.dp))
                }
            }

            // Channels
            Column(
                Modifier.weight(1f).fillMaxHeight()
                    .onFocusChanged { channelsFocused = it.hasFocus }
                    .focusRequester(contentFocus)
                    .focusGroup()
            ) {
                // Leave room on the right for the corner mini player when a stream is playing.
                Row(
                    Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 10.dp, end = if (nowPlaying.channel != null) MINI_CLEARANCE else 0.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        when {
                            effective == FAVOURITES -> "Favourites"
                            effective == RECENT -> "Recently watched"
                            effective == ALL -> "All channels"
                            else -> effective.removePrefix(SKY_PREFIX)
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (effective == ALL && !sky && total > GUIDE_LIMIT) {
                        Spacer(Modifier.width(14.dp))
                        Tag("first $GUIDE_LIMIT of $total", FerryColors.Muted, filled = false)
                    }
                    Spacer(Modifier.weight(1f))
                    Clock()
                }
                if (shown.isEmpty()) {
                    if (effective == FAVOURITES && total > 0) {
                        EmptyMessage(
                            FerryIcons.StarOutline, "No favourites yet",
                            "Hold OK on any channel in the TV Guide or Channels to add it here.",
                            Modifier.weight(1f).fillMaxWidth(),
                        )
                    } else {
                        EmptyMessage(
                            FerryIcons.Tv, "No channels yet",
                            "Open Settings, scan the code with your phone and add a playlist from there.",
                            Modifier.weight(1f).fillMaxWidth(),
                        )
                    }
                } else if (gridView) key(effective) {
                    GuideGrid(
                        channels = shown,
                        zap = zapScope,
                        playingId = playingId,
                        active = active,
                        playerShowing = playerShowing,
                        returnSignal = if (returnToRail) 0 else returnSignal,
                        onOpenPlayer = onOpenPlayer,
                        onMenu = { menuFor = it },
                        onBackToGroups = backFromContent,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    LazyColumn(
                        Modifier.weight(1f),
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp),
                    ) {
                        items(shown, key = { it.id }) { channel ->
                            ChannelRow(
                                channel = channel,
                                playing = nowPlaying.channel?.id == channel.id,
                                modifier = if (channel.id == playingId) Modifier.focusRequester(returnFocus) else Modifier,
                                onLongClick = { menuFor = channel },
                                onClick = {
                                    // Zapping follows the list you started from, not the provider's group.
                                    graph.player.start(channel, zapScope)
                                    onOpenPlayer()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The main menu: a rail of icons that opens out to show labels while it has focus (and dims
 * what's behind it), like the side menu of any streaming app.
 */
@Composable
private fun NavigationDrawerScope.NavRail(open: Boolean, current: MenuItem, menuFocus: FocusRequester, onEnter: (MenuItem) -> Unit) {
    Column(
        Modifier
            .fillMaxHeight()
            .background(FerryColors.Deep)
            .padding(horizontal = 12.dp, vertical = 16.dp),
    ) {
        // Brand
        Row(Modifier.height(56.dp).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(BrandMark, "Ferry", tint = Color.Unspecified, modifier = Modifier.size(40.dp))
            AnimatedVisibility(visible = open, enter = fadeIn(tween(200)) + expandHorizontally(tween(200)), exit = fadeOut(tween(120)) + shrinkHorizontally(tween(160))) {
                Text("Ferry", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 14.dp), maxLines = 1, softWrap = false)
            }
        }
        Spacer(Modifier.height(20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MenuItem.entries.forEach { item ->
                val isCurrent = item == current
                NavigationDrawerItem(
                    selected = isCurrent,
                    onClick = { onEnter(item) },
                    modifier = (if (isCurrent) Modifier.focusRequester(menuFocus) else Modifier)
                        .onPreviewKeyEvent { e ->
                            // Right goes into the item, like OK.
                            if (e.key == Key.DirectionRight) {
                                if (e.type == KeyEventType.KeyDown) onEnter(item)
                                true
                            } else false
                        },
                    leadingContent = { Icon(item.icon, null, modifier = Modifier.size(24.dp)) },
                    shape = NavigationDrawerItemDefaults.shape(shape = RoundedCornerShape(14.dp)),
                    colors = NavigationDrawerItemDefaults.colors(
                        containerColor = Color.Transparent,
                        contentColor = FerryColors.Muted,
                        inactiveContentColor = FerryColors.Muted,
                        focusedContainerColor = FerryColors.Gold,
                        focusedContentColor = FerryColors.Deep,
                        pressedContainerColor = FerryColors.Gold,
                        pressedContentColor = FerryColors.Deep,
                        selectedContainerColor = Color.Transparent,
                        selectedContentColor = FerryColors.Gold,
                        focusedSelectedContainerColor = FerryColors.Gold,
                        focusedSelectedContentColor = FerryColors.Deep,
                        pressedSelectedContainerColor = FerryColors.Gold,
                        pressedSelectedContentColor = FerryColors.Deep,
                    ),
                ) {
                    Text(item.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

@Composable
private fun Clock() {
    val format = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(60_000 - value % 60_000) // tick on the minute
        }
    }
    Text(format.format(Date(now)), style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = TABULAR), color = FerryColors.Muted)
}

@Composable
private fun ChannelRow(
    channel: ChannelEntity,
    playing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    val graph = LocalContext.current.graph
    // Per-row lookup is indexed and cheap; swap for a windowed query when the grid view lands.
    val programmes by produceState(initialValue = emptyList(), channel.epgKey) {
        value = graph.db.programmes().upcoming(channel.epgKey, System.currentTimeMillis(), 2)
    }
    val nowMs = System.currentTimeMillis()
    val now = programmes.firstOrNull()?.takeIf { it.start <= nowMs }
    val next = if (now != null) programmes.getOrNull(1) else programmes.firstOrNull()
    val time = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }

    ListItem(
        selected = playing,
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        colors = ferryListItemColors(),
        border = ferryListItemBorder(),
        shape = ferryListItemShape(),
        scale = ferryListItemScale(),
        leadingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${channel.shownNumber}",
                    style = MaterialTheme.typography.labelMedium,
                    color = FerryColors.Faint,
                    modifier = Modifier.width(44.dp),
                )
                LogoChip(channel.logo, channel.name, width = 64.dp, height = 40.dp)
            }
        },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (channel.favourite) {
                    Icon(FerryIcons.Star, null, tint = FerryColors.Gold, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(channel.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f, fill = false))
                if (channel.streamCount > 1) {
                    Spacer(Modifier.width(10.dp))
                    Text("${channel.streamCount} streams", color = FerryColors.Faint, style = MaterialTheme.typography.labelMedium)
                }
                if (playing) {
                    Spacer(Modifier.width(10.dp))
                    Tag("Watching", FerryColors.Gold)
                }
            }
        },
        supportingContent = {
            Column {
                Text(
                    now?.title ?: "No guide data",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (now != null) FerryColors.Muted else FerryColors.Faint,
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 5.dp)) {
                    if (now != null) {
                        ProgressLine(((nowMs - now.start).toFloat() / (now.stop - now.start).coerceAtLeast(1)), Modifier.width(160.dp))
                        Spacer(Modifier.width(14.dp))
                    }
                    next?.let {
                        Text(
                            "Next ${time.format(Date(it.start))}  ${it.title}",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = FerryColors.Faint,
                            style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = TABULAR),
                        )
                    }
                }
            }
        },
    )
}

@Composable
fun ProgressBar(start: Long, stop: Long, modifier: Modifier = Modifier) {
    val fraction = ((System.currentTimeMillis() - start).toFloat() / (stop - start).coerceAtLeast(1)).coerceIn(0f, 1f)
    ProgressLine(fraction, modifier)
}
