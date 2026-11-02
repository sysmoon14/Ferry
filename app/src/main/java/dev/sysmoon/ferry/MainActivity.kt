package dev.sysmoon.ferry

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import dev.sysmoon.ferry.ui.ALL
import dev.sysmoon.ferry.ui.Section
import dev.sysmoon.ferry.ui.FerryTheme
import dev.sysmoon.ferry.ui.GuideScreen
import dev.sysmoon.ferry.ui.VideoLayer
import dev.sysmoon.ferry.ui.FootballScreen
import dev.sysmoon.ferry.ui.FootballState
import dev.sysmoon.ferry.ui.ReminderPopup
import dev.sysmoon.ferry.ui.SearchScreen
import dev.sysmoon.ferry.ui.SleepWarning
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import dev.sysmoon.ferry.ui.PlayerScreen
import dev.sysmoon.ferry.ui.SettingsScreen
import dev.sysmoon.ferry.player.PlayTarget
import kotlinx.coroutines.launch

enum class Screen { GUIDE, PLAYER, SETTINGS, SEARCH, FOOTBALL }

/**
 * What's on screen, bottom to top. The guide is always at the bottom (it stays composed under
 * everything); Search, Settings and Sports sit on it; the player sits on the guide or on Sports
 * (so Back from a match stream returns to the match's stream list). Back pops one level.
 */
private class NavStack {
    var stack by mutableStateOf(listOf(Screen.GUIDE))
        private set
    val top: Screen get() = stack.last()
    /** [screen] is somewhere in the stack (composed, maybe under the player). */
    operator fun contains(screen: Screen) = screen in stack
    /** Opens [screen] over the guide alone. */
    fun open(screen: Screen) { stack = listOf(Screen.GUIDE, screen) }
    /** Puts the player on top of whatever is showing. */
    fun pushPlayer() { if (top != Screen.PLAYER) stack = stack + Screen.PLAYER }
    /** Back: the screen underneath. Returns the new top. */
    fun pop(): Screen { if (stack.size > 1) stack = stack.dropLast(1); return top }
    fun reset() { stack = listOf(Screen.GUIDE) }
}

@OptIn(ExperimentalComposeUiApi::class)
class MainActivity : ComponentActivity() {
    private val nav = NavStack()
    private val screen: Screen get() = nav.top
    // Kept here so the guide's list survives trips to the player and back, and saved so the next
    // launch opens where this one left off.
    private var guideGroup by mutableStateOf(ALL)
    // Main menu section shown on the guide screen.
    private var section by mutableStateOf(Section.GUIDE)
    // Bumped whenever the guide comes back into view, so it re-takes focus (on the playing
    // channel after the player, on the group list after search/settings).
    private var returnSignal by mutableIntStateOf(0)
    private var returnToRail by mutableStateOf(false)

    private val football = FootballState()

    private fun openPlayer() { nav.pushPlayer() }

    // Launching straight into a stream (resume last channel, or a deep link with the app closed):
    // cover everything in black until the player is up, and put the video full screen at once
    // instead of growing it out of the mini player, so the guide and menus never flash up.
    private var covered by mutableStateOf(false)
    /** "Later"/"Hide" on the update prompt: don't show it again until the next launch. */
    private var updateHidden by mutableStateOf(false)
    private var snapVideo by mutableStateOf(false)
    /** When the remote was last touched; "Still watching?" after hours without. */
    private var lastKeyAt by mutableStateOf(System.currentTimeMillis())
    private var stillWatching by mutableStateOf(false)

    private fun openPlayerInstantly() {
        snapVideo = true
        nav.reset(); openPlayer()
        covered = false
        lifecycleScope.launch {
            kotlinx.coroutines.delay(300) // a few frames: the layer has snapped to full screen
            snapVideo = false
        }
    }

    /** Back from the player: whatever it was opened over. */
    private fun leavePlayer() {
        if (nav.pop() == Screen.GUIDE) guideReturned(rail = false)
    }

    private fun backToGuide(rail: Boolean) {
        nav.reset()
        guideReturned(rail)
    }

    private fun guideReturned(rail: Boolean) {
        returnToRail = rail || graph.player.state.value.channel == null
        returnSignal++
    }

    private fun savePlace() {
        lifecycleScope.launch { graph.settings.setLastPlace(section.name, guideGroup) }
    }
    // Launched (or brought back from Home) without a deep link: offer the last channel.
    private var pendingResume = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FerryService.start(this)
        pendingResume = savedInstanceState == null
        covered = pendingResume
        lifecycleScope.launch {
            val (sec, grp) = graph.settings.lastPlace()
            sec?.let { name -> Section.entries.firstOrNull { it.name == name }?.let { section = it } }
            grp?.let { guideGroup = it }
        }
        handle(intent)
        setContent {
            FerryTheme {
                val playing by graph.player.state.collectAsState()
                val sleepWarning by graph.sleep.warning.collectAsState()
                val due by graph.reminders.due.collectAsState()
                val update by graph.updater.state.collectAsState()
                val idleHours by graph.settings.idleStopHours.collectAsState(initial = 0)
                LaunchedEffect(Unit) { graph.sleep.slept.collect { backToGuide(rail = true) } }
                // "Still watching?" after [idleHours] of playback with the remote untouched.
                LaunchedEffect(idleHours, playing.channel?.id) {
                    if (idleHours <= 0 || playing.channel == null) return@LaunchedEffect
                    while (true) {
                        kotlinx.coroutines.delay(60_000)
                        if (!stillWatching && graph.player.state.value.state == dev.sysmoon.ferry.player.PlaybackState.PLAYING &&
                            System.currentTimeMillis() - lastKeyAt >= idleHours * 3_600_000L) stillWatching = true
                    }
                }
                Box(
                    Modifier.fillMaxSize().background(Color.Black).onPreviewKeyEvent { e ->
                        lastKeyAt = System.currentTimeMillis()
                        // Sleep timer's last minute: any key keeps watching.
                        if (!sleepWarning) return@onPreviewKeyEvent false
                        if (e.type == KeyEventType.KeyUp) graph.sleep.cancel()
                        true
                    }
                ) {
                    val guideActive = screen == Screen.GUIDE
                    // The guide stays composed underneath everything, so coming back to it (Back from
                    // the player, search or settings) reveals it as it was instead of rebuilding it.
                    // While another screen is up, focus can't wander into it.
                    Box(
                        Modifier.fillMaxSize()
                            .focusProperties { enter = { if (guideActive) FocusRequester.Default else FocusRequester.Cancel } }
                            .focusGroup()
                    ) {
                        // Back in the main menu isn't handled here, so it leaves the app (onStop
                        // stops any stream that's still playing in the mini player).
                        GuideScreen(
                            selected = guideGroup,
                            onSelect = { guideGroup = it; savePlace() },
                            section = section,
                            onSection = { section = it; savePlace() },
                            playingId = playing.channel?.id,
                            active = guideActive,
                            playerShowing = screen == Screen.PLAYER,
                            returnSignal = returnSignal,
                            returnToRail = returnToRail,
                            onOpenPlayer = { openPlayer() },
                            onOpenSettings = { nav.open(Screen.SETTINGS) },
                            onOpenSearch = { nav.open(Screen.SEARCH) },
                            onOpenFootball = { nav.open(Screen.FOOTBALL) },
                        )
                    }
                    // Secondary screens slide up over the guide and fade away again.
                    val enter = fadeIn(tween(220)) + slideInVertically(tween(260)) { it / 24 }
                    val exit = fadeOut(tween(150))
                    AnimatedVisibility(visible = screen == Screen.SEARCH, enter = enter, exit = exit) {
                        BackHandler { backToGuide(rail = true) }
                        SearchScreen(onOpenPlayer = { nav.reset(); openPlayer() })
                    }
                    AnimatedVisibility(visible = screen == Screen.SETTINGS, enter = enter, exit = exit) {
                        BackHandler { backToGuide(rail = true) }
                        SettingsScreen(onBack = { backToGuide(rail = true) })
                    }
                    // Kept composed under the player, so Back from a stream returns to the same list.
                    AnimatedVisibility(visible = Screen.FOOTBALL in nav, enter = enter, exit = exit) {
                        FootballScreen(
                            football,
                            active = screen == Screen.FOOTBALL,
                            onOpenPlayer = { openPlayer() },
                            onBack = { backToGuide(rail = true) },
                        )
                    }
                    // Full screen in the player; shrinks to the corner over the guide and search.
                    VideoLayer(
                        full = screen == Screen.PLAYER,
                        animate = !snapVideo,
                        show = screen == Screen.PLAYER ||
                            ((screen == Screen.GUIDE || screen == Screen.SEARCH || screen == Screen.FOOTBALL) && playing.channel != null),
                    )
                    if (screen == Screen.PLAYER) {
                        // Leaving the player keeps the stream going in the corner mini player.
                        BackHandler { leavePlayer() }
                        PlayerScreen(onBack = { leavePlayer() })
                    }
                    // Offered at launch when a newer release is out, but never over full-screen viewing.
                    val showUpdate = update is dev.sysmoon.ferry.update.UpdateState.Available ||
                        update is dev.sysmoon.ferry.update.UpdateState.Downloading ||
                        update is dev.sysmoon.ferry.update.UpdateState.Installing ||
                        (update as? dev.sysmoon.ferry.update.UpdateState.Failed)?.release != null
                    if (showUpdate && !updateHidden && screen != Screen.PLAYER && !covered && due == null) {
                        dev.sysmoon.ferry.ui.UpdatePrompt(
                            update,
                            onUpdate = { graph.updater.install() },
                            onLater = {
                                updateHidden = true
                                if (update is dev.sysmoon.ferry.update.UpdateState.Available) graph.updater.dismiss()
                            },
                        )
                    }
                    if (stillWatching) {
                        dev.sysmoon.ferry.ui.StillWatching(
                            hours = idleHours,
                            onKeep = { stillWatching = false; lastKeyAt = System.currentTimeMillis() },
                            onStop = {
                                stillWatching = false
                                Logs.i("Ferry", "Still watching? — no answer after $idleHours h, stopping playback")
                                graph.scope.launch { graph.player.stop() }
                                backToGuide(rail = true)
                            },
                        )
                    }
                    if (covered) Box(Modifier.fillMaxSize().background(Color.Black))
                    if (sleepWarning) SleepWarning(Modifier.align(Alignment.TopCenter).padding(top = 32.dp))
                    due?.let { r ->
                        ReminderPopup(
                            r,
                            onWatch = {
                                graph.reminders.dismiss()
                                nav.reset(); openPlayer()
                                lifecycleScope.launch {
                                    if (!graph.player.play(PlayTarget.Id(r.channelId))) backToGuide(rail = true)
                                }
                            },
                            onDismiss = { graph.reminders.dismiss() },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onStart() {
        super.onStart()
        graph.uiVisible.value = true
        updateHidden = false
        graph.updater.resumePendingInstall()
        graph.updater.checkIfDue()
        if (!pendingResume) { covered = false; return }
        pendingResume = false
        covered = true
        lifecycleScope.launch {
            if (!graph.settings.resumeLastChannel() || graph.settings.lastChannelId() == null) {
                covered = false
                return@launch
            }
            openPlayerInstantly()
            if (!graph.player.play(PlayTarget.Last)) backToGuide(rail = true)
        }
    }

    override fun onStop() {
        super.onStop()
        graph.uiVisible.value = false
        if (!isChangingConfigurations) pendingResume = true
        // Leaving the app (Home, another app launched) stops playback, like a normal TV app.
        // HA can still start it again via deep link / MQTT.
        // App scope, not lifecycleScope: Back out of the app destroys the activity straight after
        // onStop, which would cancel the stop half-way and leave the audio playing.
        if (!isChangingConfigurations) graph.scope.launch { graph.player.stop() }
    }

    private fun handle(intent: Intent?) {
        val action = DeepLinks.parse(intent)
        if (action != null) pendingResume = false // a deep link says what to show
        when (action) {
            is LaunchAction.Play -> {
                // App not on screen yet (launched by HA / a deep link): straight into the stream.
                if (screen != Screen.PLAYER && !graph.uiVisible.value) openPlayerInstantly() else { nav.reset(); openPlayer() }
                lifecycleScope.launch {
                    if (!graph.player.play(action.target)) backToGuide(rail = true)
                }
            }
            LaunchAction.Guide -> { covered = false; backToGuide(rail = true) }
            null -> Unit
        }
    }
}
