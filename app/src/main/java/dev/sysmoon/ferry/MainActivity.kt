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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import dev.sysmoon.ferry.ui.FAVOURITES
import dev.sysmoon.ferry.ui.FerryTheme
import dev.sysmoon.ferry.ui.GuideScreen
import dev.sysmoon.ferry.ui.MiniPlayer
import dev.sysmoon.ferry.ui.PlayerScreen
import dev.sysmoon.ferry.ui.SettingsScreen
import dev.sysmoon.ferry.player.PlayTarget
import kotlinx.coroutines.launch

enum class Screen { GUIDE, PLAYER, SETTINGS }

class MainActivity : ComponentActivity() {
    private var screen by mutableStateOf(Screen.GUIDE)
    // Kept here so the guide's list survives trips to the player and back.
    private var guideGroup by mutableStateOf(FAVOURITES)
    private var returnTo by mutableStateOf<String?>(null)
    private var gridView by mutableStateOf(true)
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
                Box(Modifier.fillMaxSize()) {
                    when (screen) {
                        Screen.GUIDE -> {
                            // Lowest priority: only reached when the guide itself has nothing to do
                            // with Back (focus already in the group list). Returns to the stream.
                            BackHandler(enabled = playing.channel != null) { screen = Screen.PLAYER }
                            GuideScreen(
                                selected = guideGroup,
                                onSelect = { guideGroup = it; returnTo = null },
                                returnTo = returnTo,
                                gridView = gridView,
                                onToggleView = { gridView = !gridView },
                                onOpenPlayer = { screen = Screen.PLAYER },
                                onOpenSettings = { screen = Screen.SETTINGS },
                            )
                        }
                        Screen.PLAYER -> {
                            // Leaving the player keeps the stream going in the corner mini player.
                            val leave = {
                                returnTo = graph.player.state.value.channel?.id
                                screen = Screen.GUIDE
                            }
                            BackHandler { leave() }
                            PlayerScreen(onBack = { leave() })
                        }
                        Screen.SETTINGS -> {
                            BackHandler { screen = Screen.GUIDE }
                            SettingsScreen(onBack = { screen = Screen.GUIDE })
                        }
                    }
                    if (screen != Screen.PLAYER && playing.channel != null) {
                        MiniPlayer(Modifier.align(Alignment.BottomEnd).padding(end = 28.dp, bottom = 24.dp))
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
        if (!pendingResume) return
        pendingResume = false
        lifecycleScope.launch {
            if (!graph.settings.resumeLastChannel() || graph.settings.lastChannelId() == null) return@launch
            screen = Screen.PLAYER
            if (!graph.player.play(PlayTarget.Last)) screen = Screen.GUIDE
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) pendingResume = true
        // Leaving the app (Home, another app launched) stops playback, like a normal TV app.
        // HA can still start it again via deep link / MQTT.
        if (!isChangingConfigurations) lifecycleScope.launch { graph.player.stop() }
    }

    private fun handle(intent: Intent?) {
        val action = DeepLinks.parse(intent)
        if (action != null) pendingResume = false // a deep link says what to show
        when (action) {
            is LaunchAction.Play -> {
                screen = Screen.PLAYER
                lifecycleScope.launch {
                    if (!graph.player.play(action.target)) screen = Screen.GUIDE
                }
            }
            LaunchAction.Guide -> screen = Screen.GUIDE
            null -> Unit
        }
    }
}
