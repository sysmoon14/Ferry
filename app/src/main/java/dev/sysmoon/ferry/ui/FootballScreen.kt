@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.sysmoon.ferry.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import dev.sysmoon.ferry.data.SportsGuides
import dev.sysmoon.ferry.data.StreamQuality
import dev.sysmoon.ferry.data.Competition
import dev.sysmoon.ferry.data.Fixture
import dev.sysmoon.ferry.data.FootballData
import dev.sysmoon.ferry.data.MatchState
import dev.sysmoon.ferry.data.StreamCandidate
import dev.sysmoon.ferry.data.Team
import dev.sysmoon.ferry.data.displayName
import dev.sysmoon.ferry.graph
import dev.sysmoon.ferry.player.ZapScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.ui.focus.onFocusChanged
import androidx.tv.material3.Surface
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Border

/** A tab on the Sports screen: My teams, Today, one per enabled competition, Clubs. */
sealed interface FootballTab {
    val label: String
    data object MyTeams : FootballTab { override val label = "My teams" }
    data object Today : FootballTab { override val label = "Today" }
    data class Comp(val c: Competition) : FootballTab { override val label get() = c.label }
    data object Clubs : FootballTab { override val label = "Clubs" }
}

/** Kept by MainActivity so a trip to the player comes back to the same list. */
class FootballState {
    var tab by mutableStateOf<FootballTab>(FootballTab.Today)
    var club by mutableStateOf<Team?>(null)
    var fixture by mutableStateOf<Fixture?>(null)
    var candidates by mutableStateOf<List<StreamCandidate>?>(null)
    /** True while the finder is still looking (the list grows as passes finish). */
    var searching by mutableStateOf(false)
    /** Last stream played from the list, refocused on return. */
    var lastPlayed by mutableStateOf<String?>(null)
    /** A fixture to open as soon as fixtures load (deep link, reminder). */
    var pendingFixtureId by mutableStateOf<String?>(null)
}

/**
 * Football: fixtures for the Premier League and Champions League (and the user's clubs), then
 * every stream in the playlist that might show a match. Playing one makes those streams the
 * channel up/down list, so a dead stream is one press away from the next.
 */
@Composable
fun FootballScreen(state: FootballState, active: Boolean, onOpenPlayer: () -> Unit, onBack: () -> Unit) {
    val graph = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    val myTeams by remember { graph.settings.myTeams }.collectAsState(initial = emptySet())
    val enabledNames by remember { graph.settings.competitions }.collectAsState(initial = Competition.DEFAULT)
    val competitions = remember(enabledNames) { Competition.enabled(enabledNames) }
    val playing by graph.player.state.collectAsState()
    val reminderList by graph.reminders.reminders.collectAsState()

    var fixtures by remember { mutableStateOf<List<Fixture>?>(null) }
    var teams by remember { mutableStateOf<List<Team>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { withContext(Dispatchers.Default) { graph.matchFinder.warm() } }
    LaunchedEffect(competitions) {
        error = null
        val from = FootballData.today().minusDays(1)
        val results = competitions.map { c -> runCatching { graph.football.fixtures(c, from, 14) } }
        fixtures = results.flatMap { it.getOrDefault(emptyList()) }.sortedBy { it.kickoff }
        if (results.all { it.isFailure }) error = "Couldn't load fixtures (${results.first().exceptionOrNull()?.message})"
        teams = competitions.flatMap { c -> runCatching { graph.football.teams(c) }.getOrDefault(emptyList()) }
            .distinctBy { it.id }.sortedBy { it.name }
    }
    // Default to My teams once the user has some.
    LaunchedEffect(myTeams.isNotEmpty()) { if (myTeams.isNotEmpty() && state.tab == FootballTab.Today && state.fixture == null) state.tab = FootballTab.MyTeams }

    fun open(f: Fixture, all: List<Fixture>) {
        state.fixture = f
        state.candidates = graph.matchFinder.cached(f)
        state.searching = state.candidates == null
        if (!state.searching) return
        val sameSlot = all.count { it.competition == f.competition && kotlin.math.abs(it.kickoff - f.kickoff) <= 30 * 60_000L }
        scope.launch {
            runCatching { graph.matchFinder.find(f, sameSlot).collect { if (state.fixture?.id == f.id) state.candidates = it } }
            if (state.fixture?.id == f.id) { state.searching = false; if (state.candidates == null) state.candidates = emptyList() }
        }
    }
    // A deep link or reminder named a match: open it once fixtures are in.
    LaunchedEffect(fixtures, state.pendingFixtureId) {
        val id = state.pendingFixtureId ?: return@LaunchedEffect
        val all = fixtures ?: return@LaunchedEffect
        state.pendingFixtureId = null
        all.firstOrNull { it.id == id }?.let { open(it, all) }
    }

    BackHandler(enabled = active) {
        when {
            state.fixture != null -> { state.fixture = null; state.candidates = null; state.searching = false }
            state.club != null -> state.club = null
            else -> onBack()
        }
    }

    // Focus: the selected tab (or the club's My teams button) when the list shows.
    val entryFocus = remember { FocusRequester() }
    LaunchedEffect(active, state.fixture == null, state.club, state.tab) {
        if (!active || state.fixture != null) return@LaunchedEffect
        withFrameNanos { }
        runCatching { entryFocus.requestFocus() }
    }

    // 12- or 24-hour, as the TV is set (it used to be fixed to 24-hour here only).
    val time = remember { java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT) }
    val day = remember { SimpleDateFormat("EEEE d MMMM", Locale.getDefault()) }

    Column(Modifier.fillMaxSize().background(FerryColors.Deep).padding(horizontal = 56.dp, vertical = 40.dp)) {
        Box(Modifier.padding(end = if (playing.channel != null) MINI_CLEARANCE else 0.dp)) {
            val f = state.fixture
            ScreenTitle(
                when {
                    f != null -> "${f.home.name} v ${f.away.name}"
                    state.club != null -> state.club!!.name
                    else -> "Football"
                },
                FerryIcons.Football,
            )
        }
        Spacer(Modifier.height(18.dp))

        val fixture = state.fixture
        when {
            fixture != null -> Candidates(
                state, fixture, active,
                hasReminder = reminderList.any { it.fixtureId == fixture.id },
                onRemind = { scope.launch { graph.reminders.toggleFixture(fixture) } },
                onPlay = { c, all ->
                    state.lastPlayed = c.channel.id
                    graph.footballPicks.started(fixture, c.channel.id)
                    graph.player.start(c.channel, ZapScope.Ids(fixture.title, all.map { it.channel.id }))
                    onOpenPlayer()
                },
            )
            else -> {
                if (state.club == null) Tabs(state, competitions, myTeams.isNotEmpty(), entryFocus)
                else {
                    val club = state.club!!
                    Button(onClick = { scope.launch { graph.settings.toggleMyTeam(club.id) } }, colors = ferryButtonColors(),
                        modifier = Modifier.focusRequester(entryFocus)) {
                        androidx.tv.material3.Icon(if (club.id in myTeams) FerryIcons.Star else FerryIcons.StarOutline, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (club.id in myTeams) "Remove from My teams" else "Add to My teams")
                    }
                }
                Spacer(Modifier.height(10.dp))
                if (state.tab == FootballTab.Clubs && state.club == null) {
                    ClubList(teams, myTeams, onPick = { state.club = it })
                    return@Column
                }
                val all = fixtures
                val tab = state.tab
                val now = System.currentTimeMillis()
                val shown = when {
                    all == null -> null
                    state.club != null -> all.filter { it.home.id == state.club!!.id || it.away.id == state.club!!.id }
                    tab == FootballTab.MyTeams -> all.filter { it.home.id in myTeams || it.away.id in myTeams }
                    tab == FootballTab.Today -> all.filter { it.state == MatchState.LIVE || sameDay(it.kickoff, now) }
                    tab is FootballTab.Comp -> all.filter { it.competition == tab.c }
                    else -> all
                }
                when {
                    shown == null -> if (error != null) Text(error ?: "", color = FerryColors.Muted, modifier = Modifier.padding(16.dp))
                        else SkeletonRows(6, Modifier.padding(top = 6.dp))
                    shown.isEmpty() -> EmptyMessage(
                        FerryIcons.Football,
                        if (error != null) "Couldn't load fixtures" else "No matches",
                        error ?: when {
                            tab == FootballTab.MyTeams && state.club == null && myTeams.isEmpty() -> "Pick your clubs under Clubs and their matches appear here."
                            tab == FootballTab.Today -> "Nothing on today in your competitions."
                            else -> "Nothing in the next two weeks."
                        },
                        Modifier.fillMaxSize(),
                    )
                    else -> FixtureList(
                        shown, myTeams, reminderList.mapNotNull { it.fixtureId }.toSet(), time, day,
                        showCompetition = tab == FootballTab.MyTeams || tab == FootballTab.Today || state.club != null,
                    ) { f -> open(f, all ?: emptyList()) }
                }
            }
        }
    }
}

private fun sameDay(a: Long, b: Long): Boolean {
    val z = java.time.ZoneId.systemDefault()
    return java.time.Instant.ofEpochMilli(a).atZone(z).toLocalDate() == java.time.Instant.ofEpochMilli(b).atZone(z).toLocalDate()
}

@Composable
private fun Tabs(state: FootballState, competitions: List<Competition>, haveMyTeams: Boolean, focus: FocusRequester) {
    val tabs = buildList {
        if (haveMyTeams) add(FootballTab.MyTeams)
        add(FootballTab.Today)
        competitions.forEach { add(FootballTab.Comp(it)) }
        add(FootballTab.Clubs)
    }
    // Moving along the tabs switches straight away (no OK needed). Coming back up from the list,
    // focusRestorer returns to the tab that was left, so the list doesn't change under you.
    LazyRow(Modifier.focusRestorer(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(tabs, key = { it.label }) { t ->
            val selected = state.tab == t
            Button(
                onClick = { state.tab = t; state.club = null },
                colors = androidx.tv.material3.ButtonDefaults.colors(
                    containerColor = if (selected) FerryColors.Gold.copy(alpha = 0.16f) else androidx.compose.ui.graphics.Color.Transparent,
                    contentColor = if (selected) FerryColors.Gold else FerryColors.Muted,
                    focusedContainerColor = FerryColors.Gold,
                    focusedContentColor = FerryColors.Deep,
                    pressedContainerColor = FerryColors.Gold,
                    pressedContentColor = FerryColors.Deep,
                ),
                modifier = (if (selected) Modifier.focusRequester(focus) else Modifier)
                    .onFocusChanged { if (it.isFocused && state.tab != t) { state.tab = t; state.club = null } },
            ) {
                if (t == FootballTab.MyTeams) {
                    androidx.tv.material3.Icon(FerryIcons.Star, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                }
                // Long competition names get their short form so the row fits.
                Text(if (t is FootballTab.Comp && competitions.size > 4) t.c.short else t.label, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun FixtureList(
    fixtures: List<Fixture>,
    myTeams: Set<String>,
    reminded: Set<String>,
    time: java.text.DateFormat,
    day: SimpleDateFormat,
    showCompetition: Boolean,
    onPick: (Fixture) -> Unit,
) {
    // Live first, then upcoming by kick-off, then today's results.
    val now = System.currentTimeMillis()
    val ordered = fixtures.filter { it.state != MatchState.FINISHED || now - it.kickoff < 20 * 3_600_000L }
        .sortedWith(compareBy({ if (it.state == MatchState.LIVE) 0 else 1 }, { it.kickoff }))
    LazyColumn(Modifier.focusRestorer(), verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp)) {
        var lastDay = ""
        ordered.forEach { f ->
            val d = if (f.state == MatchState.LIVE) "Live now" else day.format(Date(f.kickoff))
            if (d != lastDay) {
                lastDay = d
                item(key = "h$d") {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp, start = 12.dp)) {
                        if (f.state == MatchState.LIVE) {
                            Box(Modifier.size(7.dp).clip(androidx.compose.foundation.shape.CircleShape).background(FerryColors.Live))
                            Spacer(Modifier.width(7.dp))
                        }
                        Text(d.uppercase(), color = if (f.state == MatchState.LIVE) FerryColors.Live else FerryColors.Muted, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            item(key = f.id) {
                ListItem(
                    selected = false,
                    onClick = { onPick(f) },
                    colors = ferryListItemColors(),
                    border = ferryListItemBorder(),
                    shape = ferryListItemShape(),
                    scale = ferryListItemScale(),
                    leadingContent = {
                        Box(Modifier.width(84.dp), contentAlignment = Alignment.CenterStart) {
                            when (f.state) {
                                MatchState.LIVE -> Tag(f.detail, FerryColors.Live, dot = true)
                                MatchState.FINISHED -> Tag("FT", FerryColors.Muted, filled = false)
                                MatchState.UPCOMING -> Text(time.format(Date(f.kickoff)), color = FerryColors.Muted,
                                    style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = TABULAR), maxLines = 1)
                            }
                        }
                    },
                    headlineContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TeamName(f.home, f.home.id in myTeams, Modifier.weight(1f), alignEnd = true)
                            Text(
                                if (f.homeScore != null) "${f.homeScore} – ${f.awayScore}" else "v",
                                color = if (f.homeScore != null) FerryColors.Text else FerryColors.Muted,
                                style = (if (f.homeScore != null) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall).copy(fontFeatureSettings = TABULAR),
                                modifier = Modifier.padding(horizontal = 14.dp),
                            )
                            TeamName(f.away, f.away.id in myTeams, Modifier.weight(1f), alignEnd = false)
                        }
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (f.id in reminded) {
                                androidx.tv.material3.Icon(FerryIcons.Bell, null, tint = FerryColors.Sea, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(8.dp))
                            }
                            if (showCompetition) Text(f.competition.short, color = FerryColors.Muted, style = MaterialTheme.typography.labelMedium)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun TeamName(t: Team, mine: Boolean, modifier: Modifier, alignEnd: Boolean) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start) {
        if (!alignEnd) Crest(t)
        if (mine && alignEnd) androidx.tv.material3.Icon(FerryIcons.Star, null, tint = FerryColors.Text, modifier = Modifier.size(13.dp))
        Text(t.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.titleSmall)
        if (mine && !alignEnd) androidx.tv.material3.Icon(FerryIcons.Star, null, tint = FerryColors.Text, modifier = Modifier.size(13.dp))
        if (alignEnd) Crest(t)
    }
}

@Composable
private fun Crest(t: Team, size: androidx.compose.ui.unit.Dp = 28.dp) {
    var failed by remember(t.logo) { mutableStateOf(false) }
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        if (t.logo != null && !failed) AsyncImage(t.logo, null, Modifier.size(size), onError = { failed = true })
        else {
            // No crest (or it didn't load): the club's initials on a disc, so the row isn't lopsided.
            val initials = t.name.split(' ', '-').filter { it.isNotBlank() && it.first().isLetter() }.take(2)
                .joinToString("") { it.first().uppercase() }.ifEmpty { "?" }
            Box(Modifier.size(size).clip(androidx.compose.foundation.shape.CircleShape).background(FerryColors.Raised), contentAlignment = Alignment.Center) {
                Text(initials, color = FerryColors.Muted, style = if (size >= 40.dp) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ClubList(teams: List<Team>, myTeams: Set<String>, onPick: (Team) -> Unit) {
    if (teams.isEmpty()) { SkeletonRows(5, Modifier.padding(top = 6.dp)); return }
    val mine = teams.filter { it.id in myTeams }
    val ordered = mine + teams.filterNot { it.id in myTeams }
    // A grid of crests: a whole league fits on one screen instead of a long single column.
    LazyVerticalGrid(
        columns = GridCells.Fixed(6),
        modifier = Modifier.focusRestorer(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp),
    ) {
        items(ordered, key = { it.id }) { t ->
            val tile = RoundedCornerShape(14.dp)
            Surface(
                onClick = { onPick(t) },
                shape = ClickableSurfaceDefaults.shape(tile),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = FerryColors.Navy, contentColor = FerryColors.Text,
                    focusedContainerColor = FerryColors.FocusFill, focusedContentColor = FerryColors.Text,
                ),
                border = ClickableSurfaceDefaults.border(focusedBorder = Border(androidx.compose.foundation.BorderStroke(2.dp, FerryColors.Gold), shape = tile)),
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Crest(t, 48.dp)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (t.id in myTeams) {
                            androidx.tv.material3.Icon(FerryIcons.Star, "In My teams", tint = FerryColors.Text, modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(t.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun Candidates(
    state: FootballState,
    f: Fixture,
    active: Boolean,
    hasReminder: Boolean,
    onRemind: () -> Unit,
    onPlay: (StreamCandidate, List<StreamCandidate>) -> Unit,
) {
    val dayFormat = remember { SimpleDateFormat("EEE d MMM", Locale.getDefault()) }
    val clock = remember { java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT) }
    val list = state.candidates
    val returnFocus = remember { FocusRequester() }
    val watchFocus = remember { FocusRequester() }
    // Focus the stream played last time (back from the player), else the Watch button.
    LaunchedEffect(list.isNullOrEmpty(), active) {
        if (!active) return@LaunchedEffect
        withFrameNanos { }
        if (list.isNullOrEmpty() || state.lastPlayed == null) runCatching { watchFocus.requestFocus() }
        else runCatching { returnFocus.requestFocus() }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (f.state == MatchState.LIVE) { Tag("Live ${f.detail}", FerryColors.Live, dot = true); Spacer(Modifier.width(12.dp)) }
        Text(
            listOf(
                f.competition.label,
                when (f.state) {
                    MatchState.LIVE -> "${f.homeScore} – ${f.awayScore}"
                    MatchState.FINISHED -> "Full time ${f.homeScore} – ${f.awayScore}"
                    MatchState.UPCOMING -> "Kick-off ${dayFormat.format(Date(f.kickoff))}, ${clock.format(Date(f.kickoff))}"
                },
            ).joinToString("  ·  "),
            color = FerryColors.Muted, style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = TABULAR),
        )
    }
    Spacer(Modifier.height(12.dp))
    // Watch the best stream in one press; remind me before kick-off.
    val best = list?.firstOrNull()
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(
            onClick = { best?.let { onPlay(it, list) } },
            enabled = best != null,
            colors = ferryButtonColors(),
            modifier = Modifier.focusRequester(watchFocus),
        ) {
            androidx.tv.material3.Icon(FerryIcons.Play, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(best?.let { "Watch  ·  ${it.channel.displayName}" } ?: if (state.searching) "Looking for streams…" else "No stream found")
        }
        if (f.state == MatchState.UPCOMING) {
            Button(onClick = onRemind, colors = ferryOutlinedButtonColorsAsButton(), border = ferrySecondaryButtonBorder()) {
                androidx.tv.material3.Icon(FerryIcons.Bell, null, tint = if (hasReminder) FerryColors.Sea else FerryColors.Text, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (hasReminder) "Reminder set  ·  5 min before" else "Remind me")
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    when {
        list == null -> Column {
            Hint("Looking through your playlist and guides…")
            Spacer(Modifier.height(10.dp))
            SkeletonRows(5)
        }
        list.isEmpty() -> EmptyMessage(
            FerryIcons.Search, "No streams found yet",
            "Providers often rename their event channels shortly before kick-off, so check again nearer the time.",
            Modifier.fillMaxSize(),
        )
        else -> {
            Hint(
                (if (state.searching) "${list.size} so far, still looking…" else "${list.size} possible streams, best first") +
                    "   ·   while watching, ▲ ▼ moves through this list   ·   Back returns here",
                color = FerryColors.Muted,
            )
            Spacer(Modifier.height(8.dp))
            val focusId = state.lastPlayed?.takeIf { id -> list.any { it.channel.id == id } }
            // Rows arriving above the first visible one would otherwise push the list down.
            val listState = rememberLazyListState()
            LaunchedEffect(list.size) { if (focusId == null) listState.scrollToItem(0) }
            LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp)) {
                for (group in StreamCandidate.Group.entries) {
                    val members = list.filter { it.group == group }
                    if (members.isEmpty()) continue
                    item(key = "h${group.name}") {
                        Text(group.label.uppercase(), color = FerryColors.Muted, style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp, start = 12.dp))
                    }
                    items(members, key = { it.channel.id }) { c ->
                        val quality = StreamQuality.parse(c.channel.name).label
                        val country = SportsGuides.country(c.channel.name, c.channel.groupTitle, c.channel.tvgId)
                        ListItem(
                            selected = false,
                            onClick = { onPlay(c, list) },
                            modifier = if (c.channel.id == focusId) Modifier.focusRequester(returnFocus) else Modifier,
                            colors = ferryListItemColors(),
                            border = ferryListItemBorder(),
                            shape = ferryListItemShape(),
                            scale = ferryListItemScale(),
                            leadingContent = { LogoChip(c.channel.logo, c.channel.name, width = 58.dp, height = 36.dp) },
                            headlineContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(c.channel.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f, fill = false))
                                    quality?.let { Spacer(Modifier.width(8.dp)); Tag(it, FerryColors.Muted, filled = false, upper = false) }
                                    if (country != null && group != StreamCandidate.Group.UK) { Spacer(Modifier.width(6.dp)); Tag(country, FerryColors.Muted, filled = false) }
                                }
                            },
                            supportingContent = {
                                Text(listOfNotNull(c.reason, c.channel.groupTitle).joinToString("  ·  "),
                                    color = FerryColors.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            trailingContent = {
                                Tag(c.strength, when { c.score >= 85 -> FerryColors.Gold; c.score >= 60 -> FerryColors.Sea; else -> FerryColors.Muted }, filled = c.score >= 85)
                            },
                        )
                    }
                }
            }
        }
    }
}
