package dev.sysmoon.ferry.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.ceil

/** Stops playback after a set time. [warning] is true for the last minute so the user can cancel. */
class SleepTimer(private val scope: CoroutineScope, private val onSleep: suspend () -> Unit) {
    private val _endsAt = MutableStateFlow<Long?>(null)
    val endsAt: StateFlow<Long?> = _endsAt.asStateFlow()
    private val _warning = MutableStateFlow(false)
    val warning: StateFlow<Boolean> = _warning.asStateFlow()
    private val _slept = MutableSharedFlow<Unit>(extraBufferCapacity = 2)
    val slept: SharedFlow<Unit> = _slept
    private var job: Job? = null

    fun set(minutes: Int) {
        job?.cancel()
        _warning.value = false
        if (minutes <= 0) { _endsAt.value = null; return }
        val end = System.currentTimeMillis() + minutes * 60_000L
        _endsAt.value = end
        job = scope.launch {
            delay((end - System.currentTimeMillis() - WARNING_MS).coerceAtLeast(0))
            _warning.value = true
            delay((end - System.currentTimeMillis()).coerceAtLeast(0))
            _warning.value = false
            _endsAt.value = null
            onSleep()
            _slept.tryEmit(Unit)
        }
    }

    fun cancel() = set(0)

    fun minutesLeft(): Int? = _endsAt.value?.let { ceil((it - System.currentTimeMillis()) / 60_000.0).toInt().coerceAtLeast(0) }

    /** Off → 15 → 30 → 60 → 90 → 120 → off, from whatever is left now. Returns the new setting. */
    fun cycle(): Int {
        val left = minutesLeft() ?: 0
        val next = if (_endsAt.value == null) STEPS.first() else STEPS.firstOrNull { it > left + 1 } ?: 0
        set(next)
        return next
    }

    companion object {
        val STEPS = listOf(15, 30, 60, 90, 120)
        private const val WARNING_MS = 60_000L
    }
}
