// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.kylemourn.mibandoplusbridge.integration;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * One clock occurrence as the bridge carries it from the clock process to the module process.
 *
 * <p>An occurrence is identified by {@code alarmId + ":" + ringEpochSec}. The ringing event and the
 * dismiss or snooze that follows it share that identity; the same alarm ringing again after a snooze
 * carries a later epoch and is therefore a different occurrence. Every decision in the alarm path
 * keys on that identity, so no step has to infer from elapsed time which command belongs to which
 * ring.
 *
 * <p>Kinds reuse the numbering the band protocol already uses
 * ({@code BandAlarmCommand.operation}: 0 alert, 1 dismiss, 2 snooze), so {@link Event#bandOp()} is
 * the value to hand to the band without translation.
 */
public final class ClockEvents {
    /** The phone started ringing {@code alarmId}. */
    public static final int KIND_RING = 0;
    /** The user stopped the ring; a one-shot alarm also loses its next-time switch. */
    public static final int KIND_DISMISS = 1;
    /** The user asked for the alarm again a few minutes later. */
    public static final int KIND_SNOOZE = 2;
    /**
     * The clock stopped the ring on its own after its repeat window, without the user closing it.
     *
     * <p>The clock keeps such a ring in its snooze list, so it rings again a few minutes later. The
     * band still has to stop now, and a close that arrives afterwards is still a real close.
     */
    public static final int KIND_ENDED = 3;

    /** The host reported that this alarm never repeats. */
    public static final int REPEAT_NO = 0;
    /** The host reported that this alarm repeats on some days. */
    public static final int REPEAT_YES = 1;
    /** The host could not be read, so a dismiss must leave the switch alone. */
    public static final int REPEAT_UNKNOWN = 2;

    private static final String KEY_KIND = "k";
    private static final String KEY_ID = "i";
    private static final String KEY_EPOCH = "t";
    private static final String KEY_REPEAT = "r";
    private static final String KEY_LABEL = "l";

    private ClockEvents() {
    }

    /** An immutable occurrence, shared by its ring, dismiss and snooze. */
    public static final class Event {
        public final int kind;
        public final int alarmId;
        public final long ringEpochSec;
        public final int repeat;
        public final String label;

        Event(int kind, int alarmId, long ringEpochSec, int repeat, String label) {
            if (!isKind(kind)) throw new IllegalArgumentException("kind");
            if (alarmId < 0) throw new IllegalArgumentException("alarmId");
            if (ringEpochSec <= 0L) throw new IllegalArgumentException("ringEpochSec");
            this.kind = kind;
            this.alarmId = alarmId;
            this.ringEpochSec = ringEpochSec;
            this.repeat = isRepeat(repeat) ? repeat : REPEAT_UNKNOWN;
            this.label = label == null ? "" : label;
        }

        /** Identity of this occurrence; the same for its ring, dismiss and snooze. */
        public String eventId() {
            return alarmId + ":" + ringEpochSec;
        }

        /** Opcode expected by {@code BandAlarmCommand.operation}. */
        public int bandOp() {
            // The band protocol knows alert, dismiss and snooze; a clock-ended ring is a dismiss.
            return kind == KIND_ENDED ? KIND_DISMISS : kind;
        }

        /** Same occurrence reported as another kind, keeping identity and label. */
        public Event withKind(int newKind) {
            return new Event(newKind, alarmId, ringEpochSec, repeat, label);
        }

        /** True when this event cannot change the next-time switch safely. */
        public boolean repeatsOrUnknown() {
            return repeat != REPEAT_NO;
        }

        @Override public String toString() {
            return kindName(kind) + " id=" + alarmId + " epoch=" + ringEpochSec + " repeat=" + repeat;
        }
    }

    public static Event ring(int alarmId, long ringEpochSec, int repeat, String label) {
        return new Event(KIND_RING, alarmId, ringEpochSec, repeat, label);
    }

    public static boolean isKind(int kind) {
        return kind >= KIND_RING && kind <= KIND_ENDED;
    }

    public static boolean isRepeat(int repeat) {
        return repeat >= REPEAT_NO && repeat <= REPEAT_UNKNOWN;
    }

    public static String kindName(int kind) {
        switch (kind) {
            case KIND_RING: return "RING";
            case KIND_DISMISS: return "DISMISS";
            case KIND_SNOOZE: return "SNOOZE";
            case KIND_ENDED: return "ENDED";
            default: return "KIND_" + kind;
        }
    }

    public static String repeatName(int repeat) {
        switch (repeat) {
            case REPEAT_NO: return "no";
            case REPEAT_YES: return "yes";
            default: return "unknown";
        }
    }

    public static String encode(Event event) {
        if (event == null) return "";
        JSONObject root = new JSONObject();
        try {
            root.put(KEY_KIND, event.kind);
            root.put(KEY_ID, event.alarmId);
            root.put(KEY_EPOCH, event.ringEpochSec);
            root.put(KEY_REPEAT, event.repeat);
            root.put(KEY_LABEL, event.label);
        } catch (JSONException e) {
            return "";
        }
        return root.toString();
    }

    /**
     * The repeat state behind one alarm row.
     *
     * <p>Only the weekday set decides it: days mean the alarm comes back, no days mean it rings
     * once. {@code deleteAfterUse} is a separate setting about clearing the row after it fires, so
     * a one-time alarm that keeps its row is still one time and still clears its own switch.</p>
     */
    public static int repeatOfDays(int days, boolean deleteAfterUse) {
        // The second argument stays in the signature so the row reader keeps its own shape.
        return days != 0 ? REPEAT_YES : REPEAT_NO;
    }

    /** Decodes an envelope, or null when the text is not a complete occurrence. */
    public static Event decode(String text) {
        if (text == null || text.isEmpty()) return null;
        try {
            JSONObject root = new JSONObject(text);
            int kind = root.optInt(KEY_KIND, -1);
            int alarmId = root.optInt(KEY_ID, -1);
            long epoch = root.optLong(KEY_EPOCH, 0L);
            if (!isKind(kind) || alarmId < 0 || epoch <= 0L) return null;
            int repeat = root.optInt(KEY_REPEAT, REPEAT_UNKNOWN);
            String label = root.optString(KEY_LABEL, "");
            return new Event(kind, alarmId, epoch, repeat, label);
        } catch (JSONException e) {
            return null;
        }
    }
}
