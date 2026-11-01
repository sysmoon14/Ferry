package dev.sysmoon.ferry

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
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

@OptIn(ExperimentalComposeUiApi::class)
class MainActivity : ComponentActivity() {
    private var screen by mutableStateOf(Screen.GUIDE)
    // Kept here so the guide's list survives trips to the player and back.
    private var guideGroup by mutableStateOf(ALL)
    // Main menu section shown on the guide screen.
    private var section by mutableStateOf(Section.GUIDE)
    // Bumped whenever the guide comes back into view, so it re-takes focus (on the playing
    // channel after the player, on the group list after search/settings).
    private var returnSignal by mutableIntStateOf(0)
    private var returnToRail by mutableStateOf(false)

    private val football = FootballState()
    /** Where Back from the player goes: the guide, or the football stream list it was started from. */
    private var playerReturn by mutableStateOf(Screen.GUIDE)

    private fun openPlayer(from: Screen) { playerReturn = from; screen = Screen.PLAYER }

    private fun leavePlayer() {
        if (playerReturn == Screen.FOOTBALL) screen = Screen.FOOTBALL else backToGuide(rail = false)
    }

    private fun backToGuide(rail: Boolean) {
        returnToRail = rail || graph.player.state.value.channel == null
        returnSignal++
        screen = Screen.GUIDE
    }
    // Launched (or brought back from Home) without a deep link: offer the last channel.
    private var pendingResume = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FerryService.start(this)
        pendingResume = savedInstanceState == null
        handle(intent)
        setContent {
            FerryTheme {
                val playing by graph.player.state.collectAsState()
                val sleepWarning by graph.sleep.warning.collectAsState()
                val due by graph.reminders.due.collectAsState()
                LaunchedEffect(Unit) { graph.sleep.slept.collect { backToGuide(rail = true) } }
                Box(
                    Modifier.fillMaxSize().background(Color.Black).onPreviewKeyEvent { e ->
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
                            onSelect = { guideGroup = it },
                            section = section,
                            onSection = { section = it },
                            playingId = playing.channel?.id,
                            active = guideActive,
                            playerShowing = screen == Screen.PLAYER,
                            returnSignal = returnSignal,
                            returnToRail = returnToRail,
                            onOpenPlayer = { openPlayer(Screen.GUIDE) },
                            onOpenSettings = { screen = Screen.SETTINGS },
                            onOpenSearch = { screen = Screen.SEARCH },
                            onOpenFootball = { screen = Screen.FOOTBALL },
                        )
                    }
                    when (screen) {
                        Screen.SEARCH -> {
                            BackHandler { backToGuide(rail = true) }
                            SearchScreen(onOpenPlayer = { openPlayer(Screen.GUIDE) })
                        }
                        Screen.SETTINGS -> {
                            BackHandler { backToGuide(rail = true) }
                            SettingsScreen(onBack = { backToGuide(rail = true) })
                        }
                        else -> Unit
                    }
                    // Kept composed under the player, so Back from a stream returns to the same list.
                    if (screen == Screen.FOOTBALL || (screen == Screen.PLAYER && playerReturn == Screen.FOOTBALL)) {
                        FootballScreen(
                            football,
                            active = screen == Screen.FOOTBALL,
                            onOpenPlayer = { openPlayer(Screen.FOOTBALL) },
                            onBack = { backToGuide(rail = true) },
                        )
                    }
                    // Full screen in the player; shrinks to the corner over the guide and search.
                    VideoLayer(
                        full = screen == Screen.PLAYER,
                        show = screen == Screen.PLAYER ||
                            ((screen == Screen.GUIDE || screen == Screen.SEARCH || screen == Screen.FOOTBALL) && playing.channel != null),
                    )
                    if (screen == Screen.PLAYER) {
                        // Leaving the player keeps the stream going in the corner mini player.
                        BackHandler { leavePlayer() }
                        PlayerScreen(onBack = { leavePlayer() })
                    }
                    if (sleepWarning) SleepWarning(Modifier.align(Alignment.TopCenter).padding(top = 32.dp))
                    due?.let { r ->
                        ReminderPopup(
                            r,
                            onWatch = {
                                graph.reminders.dismiss()
                                openPlayer(Screen.GUIDE)
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
        if (!pendingResume) return
        pendingResume = false
        lifecycleScope.launch {
            if (!graph.settings.resumeLastChannel() || graph.settings.lastChannelId() == null) return@launch
            openPlayer(Screen.GUIDE)
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
                openPlayer(Screen.GUIDE)
                lifecycleScope.launch {
                    if (!graph.player.play(action.target)) backToGuide(rail = true)
                }
            }
            LaunchAction.Guide -> backToGuide(rail = true)
            null -> Unit
        }
    }
}
