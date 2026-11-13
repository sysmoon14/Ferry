package dev.sysmoon.ferry.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay

/** The current time, updated on each minute boundary. */
@Composable
fun rememberMinuteState(): State<Long> = produceState(System.currentTimeMillis()) {
    while (true) {
        delay(60_000 - System.currentTimeMillis() % 60_000)
        value = System.currentTimeMillis()
    }
}

/** The current time, updated on each minute boundary (reading it recomposes the caller each minute). */
@Composable
fun rememberMinute(): Long {
    val now by rememberMinuteState()
    return now
}
