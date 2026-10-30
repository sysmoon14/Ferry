package dev.sysmoon.ferry

import android.content.Context
import android.content.Intent
import dev.sysmoon.ferry.player.PlayTarget

sealed interface LaunchAction {
    data class Play(val target: PlayTarget) : LaunchAction
    data object Guide : LaunchAction
}

/**
 * ferry://channel/<channelId>
 * ferry://tvg/<tvg-id>            e.g. ferry://tvg/BBCOne.uk  (best for HA / NFC: survives re-imports)
 * ferry://number/<n>
 * ferry://name/<channel name>
 * ferry://last
 * ferry://guide
 *
 * am start -a dev.sysmoon.ferry.action.PLAY --es tvg_id BBCOne.uk
 */
object DeepLinks {
    const val ACTION_PLAY = "dev.sysmoon.ferry.action.PLAY"

    /**
     * Used by MQTT and the web API to start playback. Going through the activity (rather than
     * the player directly) brings Ferry to the front if another app is showing. From the
     * background this needs "Display over other apps" — see README.
     */
    fun playIntent(context: Context, target: PlayTarget): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = ACTION_PLAY
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            when (target) {
                is PlayTarget.Id -> putExtra("channel_id", target.id)
                is PlayTarget.Tvg -> putExtra("tvg_id", target.tvgId)
                is PlayTarget.Number -> putExtra("number", target.number)
                is PlayTarget.Name -> putExtra("name", target.name)
                PlayTarget.Last -> Unit
            }
        }

    fun parse(intent: Intent?): LaunchAction? {
        intent ?: return null
        return when (intent.action) {
            ACTION_PLAY -> {
                val target = intent.getStringExtra("channel_id")?.let { PlayTarget.Id(it) }
                    ?: intent.getStringExtra("tvg_id")?.let { PlayTarget.Tvg(it) }
                    ?: intent.getStringExtra("name")?.let { PlayTarget.Name(it) }
                    ?: intent.getIntExtra("number", -1).takeIf { it > 0 }?.let { PlayTarget.Number(it) }
                    ?: PlayTarget.Last
                LaunchAction.Play(target)
            }
            Intent.ACTION_VIEW -> {
                val uri = intent.data ?: return null
                if (uri.scheme != "ferry") return null
                val arg = uri.pathSegments.firstOrNull()
                when (uri.host) {
                    "channel" -> arg?.let { LaunchAction.Play(PlayTarget.Id(it)) }
                    "tvg" -> arg?.let { LaunchAction.Play(PlayTarget.Tvg(it)) }
                    "number" -> arg?.toIntOrNull()?.let { LaunchAction.Play(PlayTarget.Number(it)) }
                    "name" -> arg?.let { LaunchAction.Play(PlayTarget.Name(it)) }
                    "last" -> LaunchAction.Play(PlayTarget.Last)
                    "guide" -> LaunchAction.Guide
                    else -> null
                }
            }
            else -> null
        }
    }
}
