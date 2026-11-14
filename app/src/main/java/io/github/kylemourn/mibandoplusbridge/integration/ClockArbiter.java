// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.kylemourn.mibandoplusbridge.integration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The only place that decides what a clock event means for the band and for the phone.
 *
 * <p>Each call takes the occurrence the band or the host is talking about and returns the actions to
 * perform. Identity comes from {@link ClockEvents.Event#eventId()}, never from how much time has
 * passed: the dismiss that the band echoes back after we stop the phone matches the occurrence we
 * are already holding, so it is reported as {@link #RESULT_DUPLICATE} and produces no action. That
 * replaces the two elapsed-time windows the previous implementation needed.
 *
 * <p>A dismiss closes the next-time switch only when the host proved the alarm is one-shot
 * ({@link ClockEvents#REPEAT_NO}). With {@link ClockEvents#REPEAT_UNKNOWN} the arbiter stops the ring
 * and leaves the switch alone, because a repeating alarm that silences itself tomorrow is worse than
 * a switch the user has to flip.
 */
public final class ClockArbiter {
    /** Tell the band to ring. */
    public static final int ACTION_BAND_ALERT = 0;
    /** Tell the band to stop its ring. */
    public static final int ACTION_BAND_DISMISS = 1;
    /** Tell the band the phone will ring again shortly. */
    public static final int ACTION_BAND_SNOOZE = 2;
    /** Ask the clock process to stop ringing through the same entry its button uses. */
    public static final int ACTION_HOST_STOP = 10;
    /** Ask the clock process to close this alarm's next-time switch. */
    public static final int ACTION_HOST_DISABLE = 11;
    /** Ask the clock process to snooze the way its button does. */
    public static final int ACTION_HOST_SNOOZE = 12;

    /** The event advanced the state and the actions describe what to do next. */
    public static final int RESULT_OK = 0;
    /** This exact occurrence was already handled, so nothing may run twice. */
    public static final int RESULT_DUPLICATE = 1;
    /** The occurrence is no longer the one being tracked; only a ring stop is safe. */
    public static final int RESULT_STALE = 2;
    /** Nothing was tracked at all, so only a ring stop is safe. */
    public static final int RESULT_UNTRACKED = 3;

    /** A ring older than this no longer describes what the user is hearing. */
    static final long MAX_RINGING_MS = 2L * 60L * 60L * 1000L;

    /**
     * How late a band tap may arrive and still count as a tap.
     *
     * <p>The band sends the same {@code op = 2} when the user taps snooze and when its own alert runs
     * out about a minute after the ring starts; only the timing tells them apart. A tap comes within
     * seconds, so anything later is the alert ending.
     */
    public static final long BAND_TAP_WINDOW_MS = 45_000L;

    static final int STATE_IDLE = 0;
    static final int STATE_RINGING = 1;
    static final int STATE_DISMISSED = 2;
    static final int STATE_SNOOZED = 3;

    /** What to do next, and why. */
    public static final class Decision {
        public final int result;
        public final List<Integer> actions;

        Decision(int result, List<Integer> actions) {
            this.result = result;
            this.actions = Collections.unmodifiableList(actions);
        }

        public boolean has(int action) {
            return actions.contains(action);
        }

        public String resultName() {
            switch (result) {
                case RESULT_OK: return "OK";
                case RESULT_DUPLICATE: return "DUPLICATE";
                case RESULT_STALE: return "STALE";
                default: return "UNTRACKED";
            }
        }

        @Override public String toString() {
            return resultName() + actions;
        }
    }

    private ClockEvents.Event current;
    private int state = STATE_IDLE;
    private long seenAtMs;

    /** The occurrence being tracked, or null when nothing rings. */
    public ClockEvents.Event current() {
        return current;
    }

    public int state() {
        return state;
    }

    /** How long the tracked occurrence has been going, or -1 when nothing is tracked. */
    public long ageMs(long nowMs) {
        return current == null ? -1L : nowMs - seenAtMs;
    }

    public void reset() {
        current = null;
        state = STATE_IDLE;
        seenAtMs = 0L;
    }

    /** The phone started ringing: alert the band once per occurrence. */
    public Decision onRing(ClockEvents.Event ring, long nowMs) {
        if (ring == null) throw new IllegalArgumentException("ring");
        if (current != null && state == STATE_RINGING && current.eventId().equals(ring.eventId())) {
            return new Decision(RESULT_DUPLICATE, new ArrayList<>(0));
        }
        current = ring;
        state = STATE_RINGING;
        seenAtMs = nowMs;
        List<Integer> actions = new ArrayList<>(1);
        actions.add(ACTION_BAND_ALERT);
        return new Decision(RESULT_OK, actions);
    }

    /** The band reported that the user closed its reminder. */
    public Decision onDeviceDismiss(int alarmId, long nowMs) {
        return onDeviceAction(alarmId, nowMs, false);
    }

    /** The band reported that the user asked for the reminder again. */
    public Decision onDeviceSnooze(int alarmId, long nowMs) {
        return onDeviceAction(alarmId, nowMs, true);
    }

    private Decision onDeviceAction(int alarmId, long nowMs, boolean snooze) {
        int hostAction = snooze ? ACTION_HOST_SNOOZE : ACTION_HOST_STOP;
        if (current == null || nowMs - seenAtMs > MAX_RINGING_MS) {
            state = STATE_IDLE;
            return new Decision(RESULT_UNTRACKED, single(hostAction));
        }
        if (current.alarmId != alarmId) {
            return new Decision(RESULT_STALE, single(hostAction));
        }
        if (state != STATE_RINGING && !(state == STATE_SNOOZED && !snooze)) {
            // The band echoing a command we already ran for this occurrence.
            return new Decision(RESULT_DUPLICATE, new ArrayList<>(0));
        }
        state = snooze ? STATE_SNOOZED : STATE_DISMISSED;
        seenAtMs = nowMs;
        List<Integer> actions = new ArrayList<>(2);
        actions.add(hostAction);
        if (!snooze && current.repeat == ClockEvents.REPEAT_NO) {
            actions.add(ACTION_HOST_DISABLE);
        }
        return new Decision(RESULT_OK, actions);
    }

    /** The phone itself stopped or closed the ring, so the band has to stop too. */
    public Decision onHostStopped(ClockEvents.Event stopped, long nowMs) {
        if (stopped == null) throw new IllegalArgumentException("stopped");
        if (current == null || !current.eventId().equals(stopped.eventId())) {
            return new Decision(RESULT_STALE, new ArrayList<>(0));
        }
        if (state != STATE_RINGING) {
            // Our own stop command came back through the host.
            return new Decision(RESULT_DUPLICATE, new ArrayList<>(0));
        }
        // A ring the clock ended by itself stays closeable: the clock keeps it in its snooze list,
        // and the user closes it from the band right after the ringing stops.
        if (stopped.kind == ClockEvents.KIND_ENDED) {
            state = STATE_SNOOZED;
        } else {
            state = stopped.kind == ClockEvents.KIND_SNOOZE ? STATE_SNOOZED : STATE_DISMISSED;
        }
        seenAtMs = nowMs;
        return new Decision(RESULT_OK, single(ACTION_BAND_DISMISS));
    }

    private static List<Integer> single(int action) {
        List<Integer> actions = new ArrayList<>(1);
        actions.add(action);
        return actions;
    }
}
