package dev.sysmoon.ferry

import android.util.Log
import dev.sysmoon.ferry.player.PlayTarget

/** One entry point for commands arriving from MQTT and the web API. */
class RemoteCommands(private val graph: AppGraph) {

    fun play(target: PlayTarget) {
        runCatching { graph.context.startActivity(DeepLinks.playIntent(graph.context, target)) }
            .onFailure { Log.w(TAG, "Couldn't bring Ferry to the front", it) }
    }

    suspend fun key(key: String): Boolean {
        when (key.lowercase().trim()) {
            "up", "channel_up", "next" -> graph.player.step(1)
            "down", "channel_down", "prev", "previous" -> graph.player.step(-1)
            "stop" -> graph.player.stop()
            "pause", "play_pause", "toggle" -> graph.player.togglePause()
            "last", "previous_channel" ->
                if (graph.player.state.value.channel != null) graph.player.previousChannel() else play(PlayTarget.Last)
            "live" -> graph.player.goLive()
            else -> return false
        }
        return true
    }

    /**
     * Free-form play payload: "tvg:BBCOne.uk", "id:3fa2…", "number:101", "101", or a channel name.
     */
    fun playPayload(payload: String) {
        val p = payload.trim()
        val target = when {
            p.startsWith("tvg:") -> PlayTarget.Tvg(p.removePrefix("tvg:"))
            p.startsWith("id:") -> PlayTarget.Id(p.removePrefix("id:"))
            p.startsWith("number:") -> p.removePrefix("number:").toIntOrNull()?.let { PlayTarget.Number(it) }
            p.toIntOrNull() != null -> PlayTarget.Number(p.toInt())
            p.isNotEmpty() -> PlayTarget.Name(p)
            else -> null
        } ?: return
        play(target)
    }

    companion object { private const val TAG = "RemoteCommands" }
}
