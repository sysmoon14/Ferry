package dev.sysmoon.ferry

import dev.sysmoon.ferry.player.Recovery
import dev.sysmoon.ferry.player.Recovery.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecoveryTest {
    private var now = 1_000_000L
    private val r = Recovery { now }

    @Test
    fun restartsTheSameStreamFirstWhenItHadBeenPlaying() {
        r.tuned(3, 0)
        assertEquals(Action.RestartSame, r.onFailure(hadStarted = true))
        assertEquals(0, r.index)
    }

    @Test
    fun movesOnWhenAStreamNeverStarted() {
        r.tuned(3, 0)
        assertEquals(Action.Switch(1), r.onFailure(hadStarted = false))
        assertEquals(Action.Switch(2), r.onFailure(hadStarted = false))
        // Every stream tried: a round from the best one, then give up after three rounds a minute.
        assertEquals(Action.RetryRound, r.onFailure(hadStarted = false))
        assertEquals(0, r.index)
        assertEquals(Action.Switch(1), r.onFailure(hadStarted = false))
        assertEquals(Action.Switch(2), r.onFailure(hadStarted = false))
        assertEquals(Action.RetryRound, r.onFailure(hadStarted = false))
        r.onFailure(false); r.onFailure(false)
        assertEquals(Action.RetryRound, r.onFailure(hadStarted = false))
        r.onFailure(false); r.onFailure(false)
        assertEquals(Action.GiveUp, r.onFailure(hadStarted = false))
    }

    @Test
    fun threeRestartsAMinuteThenTheNextStream() {
        r.tuned(2, 0)
        repeat(3) { assertEquals(Action.RestartSame, r.onFailure(hadStarted = true)) }
        assertEquals(Action.Switch(1), r.onFailure(hadStarted = true))
        // A minute later restarts are allowed again.
        now += 61_000
        assertEquals(Action.RestartSame, r.onFailure(hadStarted = true))
    }

    @Test
    fun wrapsRoundFromTheStreamItStartedOn() {
        r.tuned(3, 2)
        assertEquals(Action.Switch(0), r.onFailure(hadStarted = false))
        assertEquals(Action.Switch(1), r.onFailure(hadStarted = false))
    }

    @Test
    fun playingSteadilyResetsTheRound() {
        r.tuned(2, 0)
        assertEquals(Action.Switch(1), r.onFailure(hadStarted = false))
        r.stable()
        assertEquals(Action.Switch(0), r.onFailure(hadStarted = false))
    }

    @Test
    fun singleStreamRetriesThenGivesUp() {
        r.tuned(1, 0)
        assertEquals(Action.RetryRound, r.onFailure(hadStarted = false))
        assertEquals(Action.RetryRound, r.onFailure(hadStarted = false))
        assertEquals(Action.RetryRound, r.onFailure(hadStarted = false))
        assertEquals(Action.GiveUp, r.onFailure(hadStarted = false))
        now += 61_000
        assertEquals(Action.RetryRound, r.onFailure(hadStarted = false))
    }

    @Test
    fun backstopRetunesSameThenNextAtMostFourTimes() {
        r.tuned(2, 0)
        assertEquals(Action.RestartSame, r.onBackstop())
        assertEquals(Action.Switch(1), r.onBackstop())
        assertEquals(Action.Switch(0), r.onBackstop())
        assertEquals(Action.Switch(1), r.onBackstop())
        assertNull(r.onBackstop())
        now += 10 * 60_000 + 1
        assertEquals(Action.RestartSame, r.onBackstop())
    }

    @Test
    fun backstopOnASingleStreamAlwaysRetunesIt() {
        r.tuned(1, 0)
        assertEquals(Action.RestartSame, r.onBackstop())
        assertEquals(Action.RestartSame, r.onBackstop())
    }
}
