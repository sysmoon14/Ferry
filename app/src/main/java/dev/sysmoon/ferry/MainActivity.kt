package dev.sysmoon.ferry

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import dev.sysmoon.ferry.ui.FAVOURITES
import dev.sysmoon.ferry.ui.FerryTheme
import dev.sysmoon.ferry.ui.GuideScreen
import dev.sysmoon.ferry.ui.PlayerScreen
import dev.sysmoon.ferry.ui.SettingsScreen
import kotlinx.coroutines.launch

enum class Screen { GUIDE, PLAYER, SETTINGS }

class MainActivity : ComponentActivity() {
    private var screen by mutableStateOf(Screen.GUIDE)
    // Kept here so the guide's list survives trips to the player and back.
    private var guideGroup by mutableStateOf(FAVOURITES)
    private var returnTo by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FerryService.start(this)
        handle(intent)
        setContent {
            FerryTheme {
                when (screen) {
                    Screen.GUIDE -> GuideScreen(
                        selected = guideGroup,
                        onSelect = { guideGroup = it },
                        returnTo = returnTo,
                        onOpenPlayer = { screen = Screen.PLAYER },
                        onOpenSettings = { screen = Screen.SETTINGS },
                    )
                    Screen.PLAYER -> {
                        // Leaving the player stops the stream; otherwise audio keeps playing behind
                        // the guide with no picture. (A live preview tile is a later feature.)
                        val leave = {
                            returnTo = graph.player.state.value.channel?.id
                            screen = Screen.GUIDE
                            lifecycleScope.launch { graph.player.stop() }
                        }
                        BackHandler { leave() }
                        PlayerScreen(onBack = { leave() })
                    }
                    Screen.SETTINGS -> {
                        BackHandler { screen = Screen.GUIDE }
                        SettingsScreen(onBack = { screen = Screen.GUIDE })
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onStop() {
        super.onStop()
        // Leaving the app (Home, another app launched) stops playback, like a normal TV app.
        // HA can still start it again via deep link / MQTT.
        if (!isChangingConfigurations) lifecycleScope.launch { graph.player.stop() }
    }

    private fun handle(intent: Intent?) {
        when (val action = DeepLinks.parse(intent)) {
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
