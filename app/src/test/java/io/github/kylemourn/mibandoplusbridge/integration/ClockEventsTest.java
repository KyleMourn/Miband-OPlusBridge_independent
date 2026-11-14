// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.kylemourn.mibandoplusbridge.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ClockEventsTest {
    private static final long EPOCH = 1_750_000_000L;

    @Test public void ringRoundTripsThroughJson() {
        ClockEvents.Event event = ClockEvents.ring(42, EPOCH, ClockEvents.REPEAT_YES, "晨跑");
        ClockEvents.Event back = ClockEvents.decode(ClockEvents.encode(event));
        assertNotNull(back);
        assertEquals(ClockEvents.KIND_RING, back.kind);
        assertEquals(42, back.alarmId);
        assertEquals(EPOCH, back.ringEpochSec);
        assertEquals(ClockEvents.REPEAT_YES, back.repeat);
        assertEquals("晨跑", back.label);
        assertEquals(event.eventId(), back.eventId());
    }

    @Test public void labelSurvivesQuotesBackslashesAndNewlines() {
        String label = "他说 \"晚安\"\n然后\\离开";
        ClockEvents.Event back = ClockEvents.decode(
                ClockEvents.encode(ClockEvents.ring(7, EPOCH, ClockEvents.REPEAT_NO, label)));
        assertNotNull(back);
        assertEquals(label, back.label);
    }

    @Test public void eventIdIsAlarmAndEpoch() {
        assertEquals("42:1750000000", ClockEvents.ring(42, EPOCH, ClockEvents.REPEAT_NO, "").eventId());
    }

    @Test public void dismissKeepsTheIdentityOfItsRing() {
        ClockEvents.Event ring = ClockEvents.ring(9, EPOCH, ClockEvents.REPEAT_YES, "起床");
        for (int kind : new int[] {ClockEvents.KIND_DISMISS, ClockEvents.KIND_SNOOZE}) {
            ClockEvents.Event moved = ring.withKind(kind);
            assertEquals(kind, moved.kind);
            assertEquals(ring.eventId(), moved.eventId());
            assertEquals(ring.alarmId, moved.alarmId);
            assertEquals(ring.ringEpochSec, moved.ringEpochSec);
            assertEquals(ring.repeat, moved.repeat);
            assertEquals("起床", moved.label);
        }
    }

    @Test public void bandOpcodeSharesTheProtocolNumbering() {
        assertEquals(0, ClockEvents.KIND_RING);
        assertEquals(1, ClockEvents.KIND_DISMISS);
        assertEquals(2, ClockEvents.KIND_SNOOZE);
        ClockEvents.Event ring = ClockEvents.ring(3, EPOCH, ClockEvents.REPEAT_NO, "");
        assertEquals(0, ring.bandOp());
        assertEquals(1, ring.withKind(ClockEvents.KIND_DISMISS).bandOp());
        assertEquals(2, ring.withKind(ClockEvents.KIND_SNOOZE).bandOp());
    }

    @Test public void unknownRepeatIsTheSafeDefault() {
        ClockEvents.Event back = ClockEvents.decode(
                "{\"k\":0,\"i\":5,\"t\":1750000000,\"l\":\"x\"}");
        assertNotNull(back);
        assertEquals(ClockEvents.REPEAT_UNKNOWN, back.repeat);
        assertTrue(back.repeatsOrUnknown());
        assertFalse(ClockEvents.ring(1, EPOCH, ClockEvents.REPEAT_NO, "").repeatsOrUnknown());
    }

    @Test public void outOfRangeRepeatFallsBackToUnknown() {
        ClockEvents.Event back = ClockEvents.decode(
                "{\"k\":0,\"i\":5,\"t\":1750000000,\"r\":9,\"l\":\"x\"}");
        assertNotNull(back);
        assertEquals(ClockEvents.REPEAT_UNKNOWN, back.repeat);
    }

    @Test public void incompleteEnvelopeDecodesToNull() {
        assertNull(ClockEvents.decode(null));
        assertNull(ClockEvents.decode(""));
        assertNull(ClockEvents.decode("not json"));
        assertNull(ClockEvents.decode("{}"));
        assertNull(ClockEvents.decode("{\"k\":7,\"i\":5,\"t\":1750000000}"));
        assertNull(ClockEvents.decode("{\"k\":0,\"i\":-1,\"t\":1750000000}"));
        assertNull(ClockEvents.decode("{\"k\":0,\"i\":5,\"t\":0}"));
        assertNull(ClockEvents.decode("{\"k\":0,\"i\":5}"));
    }

    @Test public void invalidOccurrenceIsRejected() {
        boolean threw = false;
        try {
            ClockEvents.ring(1, 0L, ClockEvents.REPEAT_NO, "");
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        assertTrue(threw);
        threw = false;
        try {
            ClockEvents.ring(-1, EPOCH, ClockEvents.REPEAT_NO, "");
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        assertTrue(threw);
    }

    @Test public void kindAndRepeatNamesAreStable() {
        assertEquals("RING", ClockEvents.kindName(ClockEvents.KIND_RING));
        assertEquals("DISMISS", ClockEvents.kindName(ClockEvents.KIND_DISMISS));
        assertEquals("SNOOZE", ClockEvents.kindName(ClockEvents.KIND_SNOOZE));
        assertEquals("no", ClockEvents.repeatName(ClockEvents.REPEAT_NO));
        assertEquals("yes", ClockEvents.repeatName(ClockEvents.REPEAT_YES));
        assertEquals("unknown", ClockEvents.repeatName(ClockEvents.REPEAT_UNKNOWN));
        assertTrue(ClockEvents.ring(1, EPOCH, ClockEvents.REPEAT_NO, "").toString().contains("id=1"));
    }

    @Test public void plainAlarmRowIsOneShotAndWeekdayRowRepeats() {
        assertEquals(ClockEvents.REPEAT_NO, ClockEvents.repeatOfDays(0, true));
        // Keeping the row after it fires says nothing about coming back tomorrow.
        assertEquals(ClockEvents.REPEAT_NO, ClockEvents.repeatOfDays(0, false));
        assertEquals(ClockEvents.REPEAT_YES, ClockEvents.repeatOfDays(3, true));
        assertEquals(ClockEvents.REPEAT_YES, ClockEvents.repeatOfDays(127, false));
    }

    @Test public void clockEndedRingLooksLikeADismissToTheBand() {
        ClockEvents.Event ended = ClockEvents.ring(7, EPOCH, ClockEvents.REPEAT_NO, "")
                .withKind(ClockEvents.KIND_ENDED);
        assertEquals("ENDED", ClockEvents.kindName(ClockEvents.KIND_ENDED));
        // The band protocol has no opcode for "the phone stopped ringing by itself".
        assertEquals(ClockEvents.KIND_DISMISS, ended.bandOp());
        assertEquals("7:" + EPOCH, ended.eventId());
        ClockEvents.Event decoded = ClockEvents.decode(ClockEvents.encode(ended));
        assertNotNull(decoded);
        assertEquals(ClockEvents.KIND_ENDED, decoded.kind);
    }
}
