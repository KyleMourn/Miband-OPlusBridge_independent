// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.kylemourn.mibandoplusbridge.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ClockArbiterTest {
    private static final long T0 = 1_750_000_000_000L;
    private static final long SEC = 1000L;
    private static final long SNOOZE_GAP = 5L * 60L * SEC;

    private static ClockEvents.Event ring(int alarmId, long ringEpochSec, int repeat) {
        return ClockEvents.ring(alarmId, ringEpochSec, repeat, "alarm");
    }

    @Test public void ringAlertsTheBand() {
        ClockArbiter arbiter = new ClockArbiter();
        ClockArbiter.Decision decision = arbiter.onRing(ring(7, 1_000L, ClockEvents.REPEAT_NO), T0);
        assertEquals(ClockArbiter.RESULT_OK, decision.result);
        assertEquals(1, decision.actions.size());
        assertTrue(decision.has(ClockArbiter.ACTION_BAND_ALERT));
        assertEquals(ClockArbiter.STATE_RINGING, arbiter.state());
        assertEquals("7:1000", arbiter.current().eventId());
    }

    @Test public void secondReportOfTheSameOccurrenceIsDropped() {
        ClockArbiter arbiter = new ClockArbiter();
        arbiter.onRing(ring(7, 1_000L, ClockEvents.REPEAT_NO), T0);
        ClockArbiter.Decision again = arbiter.onRing(ring(7, 1_000L, ClockEvents.REPEAT_NO), T0 + SEC);
        assertEquals(ClockArbiter.RESULT_DUPLICATE, again.result);
        assertTrue(again.actions.isEmpty());
        assertEquals(ClockArbiter.STATE_RINGING, arbiter.state());
    }

    @Test public void oneShotDismissStopsAndClosesTheSwitch() {
        ClockArbiter arbiter = new ClockArbiter();
        arbiter.onRing(ring(7, 1_000L, ClockEvents.REPEAT_NO), T0);
        ClockArbiter.Decision decision = arbiter.onDeviceDismiss(7, T0 + 20 * SEC);
        assertEquals(ClockArbiter.RESULT_OK, decision.result);
        assertTrue(decision.has(ClockArbiter.ACTION_HOST_STOP));
        assertTrue(decision.has(ClockArbiter.ACTION_HOST_DISABLE));
        assertFalse(decision.has(ClockArbiter.ACTION_BAND_DISMISS));
        assertEquals(ClockArbiter.STATE_DISMISSED, arbiter.state());
    }

    @Test public void repeatingDismissStopsOnly() {
        ClockArbiter arbiter = new ClockArbiter();
        arbiter.onRing(ring(7, 1_000L, ClockEvents.REPEAT_YES), T0);
        ClockArbiter.Decision decision = arbiter.onDeviceDismiss(7, T0 + 20 * SEC);
        assertEquals(ClockArbiter.RESULT_OK, decision.result);
        assertTrue(decision.has(ClockArbiter.ACTION_HOST_STOP));
        assertFalse(decision.has(ClockArbiter.ACTION_HOST_DISABLE));
    }

    @Test public void unknownRepeatDismissLeavesTheSwitchAlone() {
        ClockArbiter arbiter = new ClockArbiter();
        arbiter.onRing(ring(7, 1_000L, ClockEvents.REPEAT_UNKNOWN), T0);
        ClockArbiter.Decision decision = arbiter.onDeviceDismiss(7, T0 + 20 * SEC);
        assertTrue(decision.has(ClockArbiter.ACTION_HOST_STOP));
        assertFalse(decision.has(ClockArbiter.ACTION_HOST_DISABLE));
    }

    @Test public void echoedDismissAfterDismissIsDropped() {
        ClockArbiter arbiter = new ClockArbiter();
        arbiter.onRing(ring(7, 1_000L, ClockEvents.REPEAT_NO), T0);
        arbiter.onDeviceDismiss(7, T0 + 20 * SEC);
        ClockArbiter.Decision echo = arbiter.onDeviceDismiss(7, T0 + 21 * SEC);
        assertEquals(ClockArbiter.RESULT_DUPLICATE, echo.result);
        assertTrue(echo.actions.isEmpty());
        assertEquals(ClockArbiter.STATE_DISMISSED, arbiter.state());
    }

    @Test public void snoozeAsksTheHostToSnooze() {
        ClockArbiter arbiter = new ClockArbiter();
        arbiter.onRing(ring(7, 1_000L, ClockEvents.REPEAT_NO), T0);
        ClockArbiter.Decision decision = arbiter.onDeviceSnooze(7, T0 + 20 * SEC);
        assertEquals(ClockArbiter.RESULT_OK, decision.result);
        assertTrue(decision.has(ClockArbiter.ACTION_HOST_SNOOZE));
        assertFalse(decision.has(ClockArbiter.ACTION_HOST_DISABLE));
        assertEquals(ClockArbiter.STATE_SNOOZED, arbiter.state());
    }

    @Test public void ringAfterSnoozeIsANewOccurrence() {
        ClockArbiter arbiter = new ClockArbiter();
        long first = 1_000L;
        arbiter.onRing(ring(7, first, ClockEvents.REPEAT_NO), T0);
        arbiter.onDeviceSnooze(7, T0 + 20 * SEC);
        long second = first + SNOOZE_GAP / SEC;
        ClockArbiter.Decision decision = arbiter.onRing(ring(7, second, ClockEvents.REPEAT_NO), T0 + SNOOZE_GAP);
        assertEquals(ClockArbiter.RESULT_OK, decision.result);
        assertTrue(decision.has(ClockArbiter.ACTION_BAND_ALERT));
        assertEquals("7:" + second, arbiter.current().eventId());
    }

    @Test public void dismissOfAnotherAlarmIsStaleAndStopsOnly() {
        ClockArbiter arbiter = new ClockArbiter();
        arbiter.onRing(ring(7, 1_000L, ClockEvents.REPEAT_NO), T0);
        ClockArbiter.Decision decision = arbiter.onDeviceDismiss(9, T0 + 20 * SEC);
        assertEquals(ClockArbiter.RESULT_STALE, decision.result);
        assertTrue(decision.has(ClockArbiter.ACTION_HOST_STOP));
        assertFalse(decision.has(ClockArbiter.ACTION_HOST_DISABLE));
        assertEquals(ClockArbiter.STATE_RINGING, arbiter.state());
        assertEquals("7:1000", arbiter.current().eventId());
    }

    @Test public void dismissWithoutTrackedRingStopsOnly() {
        ClockArbiter arbiter = new ClockArbiter();
        ClockArbiter.Decision decision = arbiter.onDeviceDismiss(7, T0);
        assertEquals(ClockArbiter.RESULT_UNTRACKED, decision.result);
        assertTrue(decision.has(ClockArbiter.ACTION_HOST_STOP));
        assertFalse(decision.has(ClockArbiter.ACTION_HOST_DISABLE));
    }

    @Test public void snoozeWithoutTrackedRingKeepsTheSnoozeIntent() {
        ClockArbiter arbiter = new ClockArbiter();
        ClockArbiter.Decision decision = arbiter.onDeviceSnooze(7, T0);
        assertEquals(ClockArbiter.RESULT_UNTRACKED, decision.result);
        assertTrue(decision.has(ClockArbiter.ACTION_HOST_SNOOZE));
    }

    @Test public void agedRingNoLongerCounts() {
        ClockArbiter arbiter = new ClockArbiter();
        arbiter.onRing(ring(7, 1_000L, ClockEvents.REPEAT_NO), T0);
        ClockArbiter.Decision decision = arbiter.onDeviceDismiss(
                7, T0 + ClockArbiter.MAX_RINGING_MS + SEC);
        assertEquals(ClockArbiter.RESULT_UNTRACKED, decision.result);
        assertTrue(decision.has(ClockArbiter.ACTION_HOST_STOP));
        assertFalse(decision.has(ClockArbiter.ACTION_HOST_DISABLE));
    }

    @Test public void hostStopTellsTheBandOnce() {
        ClockArbiter arbiter = new ClockArbiter();
        ClockEvents.Event event = ring(7, 1_000L, ClockEvents.REPEAT_NO);
        arbiter.onRing(event, T0);
        ClockArbiter.Decision decision = arbiter.onHostStopped(event, T0 + 20 * SEC);
        assertEquals(ClockArbiter.RESULT_OK, decision.result);
        assertEquals(1, decision.actions.size());
        assertTrue(decision.has(ClockArbiter.ACTION_BAND_DISMISS));
        assertEquals(ClockArbiter.STATE_DISMISSED, arbiter.state());
        ClockArbiter.Decision again = arbiter.onHostStopped(event, T0 + 21 * SEC);
        assertEquals(ClockArbiter.RESULT_DUPLICATE, again.result);
        assertTrue(again.actions.isEmpty());
    }

    @Test public void hostStopForUnknownOccurrenceIsStale() {
        ClockArbiter arbiter = new ClockArbiter();
        ClockArbiter.Decision decision = arbiter.onHostStopped(ring(7, 1_000L, ClockEvents.REPEAT_NO), T0);
        assertEquals(ClockArbiter.RESULT_STALE, decision.result);
        assertTrue(decision.actions.isEmpty());
    }

    @Test public void closeAfterTheClockEndedItsOwnRingStillWorks() {
        ClockArbiter arbiter = new ClockArbiter();
        ClockEvents.Event event = ring(7, 1_000L, ClockEvents.REPEAT_NO);
        arbiter.onRing(event, T0);
        // The clock ends its repeat window on its own, not the user.
        ClockEvents.Event ended = event.withKind(ClockEvents.KIND_ENDED);
        ClockArbiter.Decision stop = arbiter.onHostStopped(ended, T0 + 25 * SEC);
        assertEquals(ClockArbiter.RESULT_OK, stop.result);
        assertTrue(stop.has(ClockArbiter.ACTION_BAND_DISMISS));
        assertEquals(ClockArbiter.STATE_SNOOZED, arbiter.state());
        // The user then closes it from the band: still a real close, and one-shot still clears.
        ClockArbiter.Decision close = arbiter.onDeviceDismiss(7, T0 + 40 * SEC);
        assertEquals(ClockArbiter.RESULT_OK, close.result);
        assertTrue(close.has(ClockArbiter.ACTION_HOST_STOP));
        assertTrue(close.has(ClockArbiter.ACTION_HOST_DISABLE));
    }

    @Test public void bandSnoozeAfterTheClockEndedStaysDropped() {
        ClockArbiter arbiter = new ClockArbiter();
        ClockEvents.Event event = ring(7, 1_000L, ClockEvents.REPEAT_NO);
        arbiter.onRing(event, T0);
        arbiter.onHostStopped(event.withKind(ClockEvents.KIND_ENDED), T0 + 25 * SEC);
        // The clock already queued its own snooze, so a band snooze must not queue a second one.
        ClockArbiter.Decision snooze = arbiter.onDeviceSnooze(7, T0 + 40 * SEC);
        assertEquals(ClockArbiter.RESULT_DUPLICATE, snooze.result);
        assertTrue(snooze.actions.isEmpty());
    }

    @Test public void agesTellABandTapFromItsOwnAlertTimeout() {
        ClockArbiter arbiter = new ClockArbiter();
        assertEquals(-1L, arbiter.ageMs(T0));
        ClockEvents.Event event = ring(7, 1_000L, ClockEvents.REPEAT_NO);
        arbiter.onRing(event, T0);
        // A tap comes within seconds; the alert timeout arrives about a minute later.
        assertTrue(arbiter.ageMs(T0 + 5 * SEC) < ClockArbiter.BAND_TAP_WINDOW_MS);
        assertTrue(arbiter.ageMs(T0 + 61 * SEC) >= ClockArbiter.BAND_TAP_WINDOW_MS);
    }

    @Test public void resetForgetsTheOccurrence() {
        ClockArbiter arbiter = new ClockArbiter();
        arbiter.onRing(ring(7, 1_000L, ClockEvents.REPEAT_NO), T0);
        arbiter.reset();
        assertEquals(ClockArbiter.STATE_IDLE, arbiter.state());
        assertEquals(null, arbiter.current());
        assertEquals(ClockArbiter.RESULT_UNTRACKED, arbiter.onDeviceDismiss(7, T0 + SEC).result);
    }
}
