// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.kylemourn.mibandoplusbridge.notify;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.Test;
import static io.github.kylemourn.mibandoplusbridge.notify.PhoneCallGate.Phase.*;
import static io.github.kylemourn.mibandoplusbridge.notify.PhoneCallGate.State.*;
import static org.junit.Assert.*;

/**
 * The band only ever sees a ring or a dial. Anything that makes the phone stop ringing (answer,
 * reject, silence, hang up) clears the screen with the end packet.
 */
public final class PhoneCallGateTest {
    @Test public void dualSimStaysIncomingUntilTheLastRingEnds() {
        Sender sender = new Sender();
        PhoneCallGate gate = connected(sender);
        gate.onState(1, RINGING);
        gate.onState(2, RINGING);
        sender.ack(0);
        assertTrue(gate.isRingingDelivered());
        gate.onState(1, OFFHOOK);
        assertEquals(List.of("incoming"), sender.events);
        gate.onState(2, IDLE);
        assertEquals(List.of("incoming", "cancel", "clear"), sender.events);
        gate.onState(1, IDLE);
        assertEquals(List.of("incoming", "cancel", "clear"), sender.events);
        sender.ack(1);
        assertFalse(gate.isRingingDelivered());
    }

    @Test public void outgoingDialIsNotAnIncomingAlert() {
        Sender sender = new Sender();
        PhoneCallGate gate = connected(sender);
        gate.onState(1, OFFHOOK);
        gate.onState(2, OFFHOOK);
        assertEquals(List.of("outgoing"), sender.events);
        gate.onState(1, IDLE);
        gate.onState(2, IDLE);
        assertEquals(List.of("outgoing", "cancel", "clear"), sender.events);
    }

    @Test public void answeringAnOutgoingCallClearsTheDial() {
        Sender sender = new Sender();
        PhoneCallGate gate = connected(sender);
        gate.onState(1, OFFHOOK);
        assertEquals(List.of("outgoing"), sender.events);
        assertTrue(gate.markAnswered());
        assertEquals(List.of("outgoing", "cancel", "clear"), sender.events);
        assertFalse(gate.markAnswered());
        gate.onState(1, IDLE);
        assertEquals(List.of("outgoing", "cancel", "clear"), sender.events);
    }

    @Test public void answeringClearsTheRingInsteadOfShowingACall() {
        Sender sender = new Sender();
        PhoneCallGate gate = connected(sender);
        gate.onState(1, RINGING);
        assertFalse(gate.isRingingDelivered());
        gate.onState(1, OFFHOOK);
        assertEquals(List.of("incoming", "cancel", "clear"), sender.events);
        sender.ack(0);
        assertFalse(gate.isRingingDelivered());
        sender.ack(1);
        assertFalse(gate.isRingingDelivered());
    }

    @Test public void silentRingerClearsTheRingOnce() {
        Sender sender = new Sender();
        PhoneCallGate gate = connected(sender);
        gate.onState(1, RINGING);
        assertTrue(gate.isRinging());
        assertTrue(gate.markPhoneSilent());
        assertEquals(List.of("incoming", "cancel", "clear"), sender.events);
        assertFalse(gate.isRinging());
        assertFalse(gate.markPhoneSilent());
        assertEquals(List.of("incoming", "cancel", "clear"), sender.events);
    }

    @Test public void withdrawnCallNotificationClearsTheRing() {
        Sender sender = new Sender();
        PhoneCallGate gate = connected(sender);
        gate.onState(1, RINGING);
        assertTrue(gate.markPhoneSilent());
        gate.onState(1, IDLE);
        assertEquals(List.of("incoming", "cancel", "clear"), sender.events);
    }

    @Test public void disableClearsCurrentAlertAndDoesNotRetainOldCallState() {
        Sender sender = new Sender();
        PhoneCallGate gate = connected(sender);
        gate.onState(1, RINGING);
        sender.ack(0);
        gate.setEnabled(false);
        gate.onState(2, RINGING);
        gate.setEnabled(false);
        gate.setEnabled(true);
        assertEquals(List.of("incoming", "cancel", "clear"), sender.events);
        assertFalse(gate.isRingingDelivered());
        gate.replaceStates(Map.of(1, IDLE, 2, IDLE));
        assertEquals(List.of("incoming", "cancel", "clear"), sender.events);
    }

    @Test public void disconnectClearsTheRingAndReconnectShowsOnlyALiveRing() {
        Sender sender = new Sender();
        PhoneCallGate gate = connected(sender);
        gate.onState(1, RINGING);
        gate.disconnected();
        assertEquals(List.of("incoming", "clear", "cancel"), sender.events);
        // An answered call on the far side is not an alert, so the new session starts silent.
        gate.connected(Map.of(1, IDLE, 2, OFFHOOK));
        assertEquals(List.of("incoming", "clear", "cancel"), sender.events);
        gate.disconnected();
        gate.connected(Map.of(1, IDLE, 2, RINGING));
        assertEquals(List.of("incoming", "clear", "cancel", "cancel", "incoming"), sender.events);
        sender.ack(2);
        assertTrue(gate.isRingingDelivered());
    }

    @Test public void failedSubmissionIsObservableAndCannotBecomeDelivery() {
        Sender sender = new Sender();
        PhoneCallGate gate = connected(sender);
        gate.onState(1, RINGING);
        RuntimeException failure = new IllegalStateException("OFFLINE");
        sender.pending.get(0).completeExceptionally(failure);
        assertSame(failure, gate.lastFailure());
        assertFalse(gate.isRingingDelivered());
        gate.onState(1, RINGING);
        assertEquals(List.of("incoming"), sender.events);
        gate.onState(1, IDLE);
        assertEquals(List.of("incoming", "cancel", "clear"), sender.events);
        sender.ack(1);
        assertNull(gate.lastFailure());
    }

    @Test public void immediateRejectionIsObservableWithoutEscapingCallback() {
        RuntimeException rejected = new IllegalStateException("NOT_READY");
        PhoneCallGate gate = new PhoneCallGate(new PhoneCallGate.Sender() {
            public CompletionStage<Void> send(PhoneCallGate.Phase phase) { throw rejected; }
            public void cancelQueuedRing() { }
        });
        gate.setEnabled(true);
        gate.connected(Map.of(1, RINGING));
        assertSame(rejected, gate.lastFailure());
        assertFalse(gate.isRingingDelivered());
    }

    @Test public void subscriptionRemovalAndLatestTransitionPreserveClearOrdering() {
        Sender sender = new Sender();
        PhoneCallGate gate = connected(sender);
        gate.onState(1, RINGING);
        gate.replaceStates(Map.of(2, IDLE));
        gate.onState(1, RINGING);
        gate.onState(2, RINGING);
        sender.ack(1);
        assertFalse(gate.isRingingDelivered());
        sender.ack(2);
        assertTrue(gate.isRingingDelivered());
        assertEquals(List.of("incoming", "cancel", "clear", "incoming"), sender.events);
    }

    private static PhoneCallGate connected(Sender sender) {
        PhoneCallGate gate = new PhoneCallGate(sender);
        gate.setEnabled(true);
        gate.connected(Map.of(1, IDLE, 2, IDLE));
        return gate;
    }

    private static final class Sender implements PhoneCallGate.Sender {
        final List<String> events = new ArrayList<>();
        final List<CompletableFuture<Void>> pending = new ArrayList<>();
        public CompletionStage<Void> send(PhoneCallGate.Phase phase) {
            events.add(switch (phase) {
                case INCOMING -> "incoming";
                case ACTIVE -> "active";
                case OUTGOING -> "outgoing";
                case NONE -> "clear";
            });
            CompletableFuture<Void> result = new CompletableFuture<>();
            pending.add(result);
            return result;
        }
        public void cancelQueuedRing() { events.add("cancel"); }
        void ack(int index) { pending.get(index).complete(null); }
    }
}
