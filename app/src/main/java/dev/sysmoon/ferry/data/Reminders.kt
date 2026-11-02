package dev.sysmoon.ferry.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** A programme the user asked to be told about when it starts. */
@Serializable
data class Reminder(
    val channelId: String,
    val epgKey: String,
    val start: Long,
    val stop: Long,
    val title: String,
    val channelName: String,
    /** Set for a football fixture reminder: the ESPN event id. Pops up [FIXTURE_LEAD_MS] before kick-off. */
    val fixtureId: String? = null,
) {
    val key: String get() = "$epgKey@$start"
    val lead: Long get() = if (fixtureId != null) FIXTURE_LEAD_MS else LEAD_MS

    companion object {
        const val LEAD_MS = 120_000L
        const val FIXTURE_LEAD_MS = 5 * 60_000L
    }
}

/**
 * Reminders for upcoming programmes. When one starts, [due] holds it until the user answers the
 * pop-up (or the programme ends), and [fired] tells Home Assistant.
 */
class ReminderManager(private val settings: Settings, scope: CoroutineScope) {
    val reminders: StateFlow<List<Reminder>> = settings.reminders.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _due = MutableStateFlow<Reminder?>(null)
    val due: StateFlow<Reminder?> = _due.asStateFlow()

    private val _fired = MutableSharedFlow<Reminder>(extraBufferCapacity = 8)
    val fired: SharedFlow<Reminder> = _fired

    fun has(channel: ChannelEntity, programme: ProgrammeEntity): Boolean =
        reminders.value.any { it.epgKey == channel.epgKey && it.start == programme.start }

    /** Adds or removes a reminder; returns true if one is now set. */
    suspend fun toggle(channel: ChannelEntity, programme: ProgrammeEntity): Boolean {
        val key = "${channel.epgKey}@${programme.start}"
        var set = false
        settings.updateReminders { list ->
            if (list.any { it.key == key }) list.filterNot { it.key == key }
            else {
                set = true
                (list + Reminder(channel.primaryId ?: channel.id, channel.epgKey, programme.start, programme.stop,
                    programme.title, channel.displayName)).sortedBy { it.start }
            }
        }
        return set
    }

    fun hasFixture(f: Fixture): Boolean = reminders.value.any { it.fixtureId == f.id }

    /** Adds or removes a reminder for a match (five minutes before kick-off); true if one is now set. */
    suspend fun toggleFixture(f: Fixture): Boolean {
        var set = false
        settings.updateReminders { list ->
            if (list.any { it.fixtureId == f.id }) list.filterNot { it.fixtureId == f.id }
            else {
                set = true
                (list + Reminder("", "fixture:${f.id}", f.kickoff, f.kickoff + 2 * 3_600_000L, f.title, f.competition.label, f.id)).sortedBy { it.start }
            }
        }
        return set
    }

    fun dismiss() { _due.value = null }

    init {
        scope.launch {
            while (isActive) {
                runCatching { check() }
                delay(15_000)
            }
        }
    }

    private suspend fun check() {
        val now = System.currentTimeMillis()
        _due.value?.let { if (it.stop <= now) _due.value = null }
        val startingNow = reminders.value.filter { it.start <= now + it.lead }
        if (startingNow.isEmpty()) return
        settings.updateReminders { list -> list.filterNot { r -> startingNow.any { it.key == r.key } } }
        // Only remind about programmes still on (Ferry may have been off when one started).
        startingNow.filter { it.stop > now }.maxByOrNull { it.start }?.let { r ->
            _due.value = r
            _fired.tryEmit(r)
        }
    }
}
