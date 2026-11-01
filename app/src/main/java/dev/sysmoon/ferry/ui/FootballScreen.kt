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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import dev.sysmoon.ferry.data.Competition
import dev.sysmoon.ferry.data.Fixture
import dev.sysmoon.ferry.data.FootballData
import dev.sysmoon.ferry.data.MatchState
import dev.sysmoon.ferry.data.StreamCandidate
import dev.sysmoon.ferry.data.Team
import dev.sysmoon.ferry.data.displayName
import dev.sysmoon.ferry.graph
import dev.sysmoon.ferry.player.ZapScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class FootballTab(val label: String) { MY_TEAMS("My teams"), PL("Premier League"), UCL("Champions League"), CLUBS("Clubs") }

/** Kept by MainActivity so a trip to the player comes back to the same list. */
class FootballState {
    var tab by mutableStateOf(FootballTab.PL)
    var club by mutableStateOf<Team?>(null)
    var fixture by mutableStateOf<Fixture?>(null)
    var candidates by mutableStateOf<List<StreamCandidate>?>(null)
    /** Last stream played from the list, refocused on return. */
    var lastPlayed by mutableStateOf<String?>(null)
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
    val playing by graph.player.state.collectAsState()

    var fixtures by remember { mutableStateOf<List<Fixture>?>(null) }
    var teams by remember { mutableStateOf<List<Team>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loadToken by remember { mutableStateOf(0) }
    LaunchedEffect(loadToken) {
        error = null
        val from = FootballData.today().minusDays(1)
        val results = Competition.entries.map { c -> runCatching { graph.football.fixtures(c, from, 14) } }
        fixtures = results.flatMap { it.getOrDefault(emptyList()) }.sortedBy { it.kickoff }
        if (results.all { it.isFailure }) error = "Couldn't load fixtures (${results.first().exceptionOrNull()?.message})"
        teams = Competition.entries.flatMap { c -> runCatching { graph.football.teams(c) }.getOrDefault(emptyList()) }
            .distinctBy { it.id }.sortedBy { it.name }
    }
    // Default to My teams once the user has some.
    LaunchedEffect(myTeams.isNotEmpty()) { if (myTeams.isNotEmpty() && state.tab == FootballTab.PL && state.fixture == null) state.tab = FootballTab.MY_TEAMS }

    BackHandler(enabled = active) {
        when {
            state.fixture != null -> { state.fixture = null; state.candidates = null }
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

    val time = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
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
            fixture != null -> Candidates(state, fixture, active, onPlay = { c, all ->
                state.lastPlayed = c.channel.id
                graph.player.start(c.channel, ZapScope.Ids(fixture.title, all.map { it.channel.id }))
                onOpenPlayer()
            })
            state.tab == FootballTab.CLUBS && state.club == null -> {
                Tabs(state, myTeams.isNotEmpty(), entryFocus)
                Spacer(Modifier.height(10.dp))
                ClubList(teams, myTeams, onPick = { state.club = it })
            }
            else -> {
                if (state.club == null) Tabs(state, myTeams.isNotEmpty(), entryFocus)
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
                val all = fixtures
                val shown = when {
                    all == null -> null
                    state.club != null -> all.filter { it.home.id == state.club!!.id || it.away.id == state.club!!.id }
                    state.tab == FootballTab.MY_TEAMS -> all.filter { it.home.id in myTeams || it.away.id in myTeams }
                    state.tab == FootballTab.UCL -> all.filter { it.competition == Competition.CHAMPIONS_LEAGUE }
                    else -> all.filter { it.competition == Competition.PREMIER_LEAGUE }
                }
                when {
                    shown == null -> Text(error ?: "Loading fixtures…", color = FerryColors.Muted, modifier = Modifier.padding(16.dp))
                    shown.isEmpty() -> EmptyMessage(
                        FerryIcons.Football,
                        if (error != null) "Couldn't load fixtures" else "No matches",
                        error ?: if (state.tab == FootballTab.MY_TEAMS && state.club == null && myTeams.isEmpty())
                            "Pick your clubs under Clubs and their matches appear here."
                        else "Nothing in the next two weeks.",
                        Modifier.fillMaxSize(),
                    )
                    else -> FixtureList(shown, myTeams, time, day, showCompetition = state.tab == FootballTab.MY_TEAMS || state.club != null) { f ->
                        state.fixture = f
                        state.candidates = null
                        val sameSlot = (all ?: emptyList()).count { it.competition == f.competition && kotlin.math.abs(it.kickoff - f.kickoff) <= 30 * 60_000L }
                        scope.launch { state.candidates = runCatching { graph.matchFinder.find(f, sameSlot) }.getOrDefault(emptyList()) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Tabs(state: FootballState, haveMyTeams: Boolean, focus: FocusRequester) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FootballTab.entries.filter { it != FootballTab.MY_TEAMS || haveMyTeams }.forEach { t ->
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
                modifier = if (selected) Modifier.focusRequester(focus) else Modifier,
            ) {
                if (t == FootballTab.MY_TEAMS) {
                    androidx.tv.material3.Icon(FerryIcons.Star, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(t.label, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun FixtureList(
    fixtures: List<Fixture>,
    myTeams: Set<String>,
    time: SimpleDateFormat,
    day: SimpleDateFormat,
    showCompetition: Boolean,
    onPick: (Fixture) -> Unit,
) {
    // Live first, then upcoming by kick-off, then today's results.
    val now = System.currentTimeMillis()
    val ordered = fixtures.filter { it.state != MatchState.FINISHED || now - it.kickoff < 20 * 3_600_000L }
        .sortedWith(compareBy({ if (it.state == MatchState.LIVE) 0 else 1 }, { it.kickoff }))
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp)) {
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
                        Text(d.uppercase(), color = if (f.state == MatchState.LIVE) FerryColors.Live else FerryColors.Faint, style = MaterialTheme.typography.labelSmall)
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
                                color = if (f.homeScore != null) FerryColors.Text else FerryColors.Faint,
                                style = (if (f.homeScore != null) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall).copy(fontFeatureSettings = TABULAR),
                                modifier = Modifier.padding(horizontal = 14.dp),
                            )
                            TeamName(f.away, f.away.id in myTeams, Modifier.weight(1f), alignEnd = false)
                        }
                    },
                    trailingContent = { if (showCompetition) Text(f.competition.short, color = FerryColors.Faint, style = MaterialTheme.typography.labelMedium) },
                )
            }
        }
    }
}

@Composable
private fun TeamName(t: Team, mine: Boolean, modifier: Modifier, alignEnd: Boolean) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start) {
        if (!alignEnd) Crest(t)
        if (mine && alignEnd) androidx.tv.material3.Icon(FerryIcons.Star, null, tint = FerryColors.Gold, modifier = Modifier.size(13.dp))
        Text(t.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.titleSmall)
        if (mine && !alignEnd) androidx.tv.material3.Icon(FerryIcons.Star, null, tint = FerryColors.Gold, modifier = Modifier.size(13.dp))
        if (alignEnd) Crest(t)
    }
}

@Composable
private fun Crest(t: Team) {
    Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
        if (t.logo != null) AsyncImage(t.logo, null, Modifier.size(28.dp))
    }
}

@Composable
private fun ClubList(teams: List<Team>, myTeams: Set<String>, onPick: (Team) -> Unit) {
    if (teams.isEmpty()) { Text("Loading clubs…", color = FerryColors.Muted); return }
    val mine = teams.filter { it.id in myTeams }
    val ordered = mine + teams.filterNot { it.id in myTeams }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp)) {
        items(ordered, key = { it.id }) { t ->
            ListItem(
                selected = false,
                onClick = { onPick(t) },
                colors = ferryListItemColors(),
                border = ferryListItemBorder(),
                shape = ferryListItemShape(),
                scale = ferryListItemScale(),
                leadingContent = { Crest(t) },
                headlineContent = { Text(t.name, maxLines = 1, style = MaterialTheme.typography.titleSmall) },
                trailingContent = { if (t.id in myTeams) androidx.tv.material3.Icon(FerryIcons.Star, null, tint = FerryColors.Gold, modifier = Modifier.size(18.dp)) },
            )
        }
    }
}

@Composable
private fun Candidates(state: FootballState, f: Fixture, active: Boolean, onPlay: (StreamCandidate, List<StreamCandidate>) -> Unit) {
    val time = remember { SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()) }
    val list = state.candidates
    val returnFocus = remember { FocusRequester() }
    LaunchedEffect(list, active) {
        if (list.isNullOrEmpty() || !active) return@LaunchedEffect
        withFrameNanos { }
        runCatching { returnFocus.requestFocus() }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (f.state == MatchState.LIVE) { Tag("Live ${f.detail}", FerryColors.Live, dot = true); Spacer(Modifier.width(12.dp)) }
        Text(
            listOf(
                f.competition.label,
                when (f.state) {
                    MatchState.LIVE -> "${f.homeScore} – ${f.awayScore}"
                    MatchState.FINISHED -> "Full time ${f.homeScore} – ${f.awayScore}"
                    MatchState.UPCOMING -> "Kick-off ${time.format(Date(f.kickoff))}"
                },
            ).joinToString("  ·  "),
            color = FerryColors.Muted, style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = TABULAR),
        )
    }
    Spacer(Modifier.height(10.dp))
    when {
        list == null -> Text("Looking through your playlist and guides…", color = FerryColors.Muted, modifier = Modifier.padding(16.dp))
        list.isEmpty() -> EmptyMessage(
            FerryIcons.Search, "No streams found yet",
            "Providers often rename their event channels shortly before kick-off, so check again nearer the time.",
            Modifier.fillMaxSize(),
        )
        else -> {
            Hint("${list.size} possible streams, best first   ·   while watching, ▲ ▼ moves through this list   ·   Back returns here", color = FerryColors.Muted)
            Spacer(Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp)) {
                items(list, key = { it.channel.id }) { c ->
                    val focusHere = c.channel.id == (state.lastPlayed?.takeIf { id -> list.any { it.channel.id == id } } ?: list.first().channel.id)
                    ListItem(
                        selected = false,
                        onClick = { onPlay(c, list) },
                        modifier = if (focusHere) Modifier.focusRequester(returnFocus) else Modifier,
                        colors = ferryListItemColors(),
                        border = ferryListItemBorder(),
                        shape = ferryListItemShape(),
                        scale = ferryListItemScale(),
                        leadingContent = { LogoChip(c.channel.logo, c.channel.name, width = 58.dp, height = 36.dp) },
                        headlineContent = { Text(c.channel.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall) },
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
