// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.kylemourn.mibandoplusbridge.notify;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** In-memory, per-subscription state only; submitted commands are not delivery evidence. */
public final class PhoneCallGate {
    public enum State { IDLE, RINGING, OFFHOOK }
    /** Band call screen. Incoming wins over an answered line; outgoing is a dial the phone started. */
    public enum Phase { NONE, INCOMING, ACTIVE, OUTGOING }

    public interface Sender {
        CompletionStage<Void> send(Phase phase);
        void cancelQueuedRing();
    }

    private static final class Line {
        Phase phase = Phase.NONE;
    }

    private final Sender sender;
    private final Map<Integer, Line> lines = new HashMap<>();
    private boolean enabled;
    private boolean connected;
    private Phase phase = Phase.NONE;
    private Phase delivered = Phase.NONE;
    private long revision;
    private Throwable lastFailure;

    public PhoneCallGate(Sender sender) {
        this.sender = Objects.requireNonNull(sender);
    }

    public synchronized void setEnabled(boolean value) {
        if (enabled == value) return;
        enabled = value;
        if (!value) {
            lines.clear();
            reconcile();
            delivered = Phase.NONE;
        }
    }

    /** Each session starts from a fresh snapshot. The previous session's call states stay behind. */
    public synchronized void connected(Map<Integer, State> currentStates) {
        revision++;
        connected = true;
        phase = Phase.NONE;
        delivered = Phase.NONE;
        lines.clear();
        if (enabled) apply(currentStates);
        reconcile();
    }

    public synchronized void disconnected() {
        revision++;
        connected = false;
        delivered = Phase.NONE;
        lines.clear();
        if (phase == Phase.INCOMING) {
            // The band still holds the ring screen: try to clear it before the session goes away.
            safeSend(Phase.NONE);
        }
        phase = Phase.NONE;
        sender.cancelQueuedRing();
    }

    /** Replacing the complete set makes subscription removal an atomic aggregate transition. */
    public synchronized void replaceStates(Map<Integer, State> currentStates) {
        if (!enabled) {
            lines.clear();
            reconcile();
            return;
        }
        apply(currentStates);
        reconcile();
    }

    public synchronized void onState(int subscriptionId, State state) {
        if (!enabled || !lines.containsKey(subscriptionId)) return;
        Line line = lines.get(subscriptionId);
        line.phase = transition(line.phase, Objects.requireNonNull(state), false);
        reconcile();
    }

    /**
     * The phone answered. Both a ringing line and a dialed line stop being an alert, so the band
     * screen is cleared for either instead of switching to an in-call screen.
     */
    public synchronized boolean markAnswered() {
        if (!enabled) return false;
        boolean changed = false;
        for (Line line : lines.values()) {
            if (line.phase == Phase.INCOMING || line.phase == Phase.OUTGOING) {
                line.phase = Phase.ACTIVE;
                changed = true;
            }
        }
        if (changed) reconcile();
        return changed;
    }

    /**
     * The phone stopped ringing without a telephony transition we can see: the call notification was
     * withdrawn (rejected, ended from the notification, or answered elsewhere) or the ringer went
     * silent. Every ringing line is cleared so the band screen goes away.
     */
    public synchronized boolean markPhoneSilent() {
        if (!enabled) return false;
        boolean changed = false;
        for (Line line : lines.values()) {
            if (line.phase == Phase.INCOMING) {
                line.phase = Phase.ACTIVE;
                changed = true;
            }
        }
        if (changed) reconcile();
        return changed;
    }

    /** True while the band is meant to be showing the ring screen. */
    public synchronized boolean isRinging() { return phase == Phase.INCOMING; }

    public synchronized boolean isRingingDelivered() { return delivered == Phase.INCOMING; }
    public synchronized Throwable lastFailure() { return lastFailure; }

    private void apply(Map<Integer, State> currentStates) {
        Map<Integer, Line> next = new HashMap<>();
        for (var entry : currentStates.entrySet()) {
            Line previous = lines.get(entry.getKey());
            Line line = new Line();
            line.phase = transition(previous == null ? Phase.NONE : previous.phase,
                    Objects.requireNonNull(entry.getValue()), previous == null);
            next.put(entry.getKey(), line);
        }
        lines.clear();
        lines.putAll(next);
    }

    private static Phase transition(Phase previous, State telephony, boolean snapshot) {
        return switch (telephony) {
            case RINGING -> Phase.INCOMING;
            case IDLE -> Phase.NONE;
            case OFFHOOK -> switch (previous) {
                case INCOMING, ACTIVE -> Phase.ACTIVE;
                case OUTGOING -> Phase.OUTGOING;
                case NONE -> snapshot ? Phase.ACTIVE : Phase.OUTGOING;
            };
        };
    }

    /**
     * Only a ring or a dial reaches the band. An answered line contributes nothing, so answering
     * turns into the end packet that clears the screen instead of an in-call screen.
     */
    private Phase aggregate() {
        boolean incoming = false;
        boolean outgoing = false;
        for (Line line : lines.values()) {
            switch (line.phase) {
                case INCOMING -> incoming = true;
                case OUTGOING -> outgoing = true;
                case ACTIVE, NONE -> { }
            }
        }
        if (incoming) return Phase.INCOMING;
        if (outgoing) return Phase.OUTGOING;
        return Phase.NONE;
    }

    /** Best-effort send outside a reconcile, used when the session is about to disappear. */
    private void safeSend(Phase value) {
        long token = ++revision;
        try {
            Objects.requireNonNull(sender.send(value)).whenComplete((unused, failure) -> {
                synchronized (PhoneCallGate.this) {
                    if (token != revision) return;
                    if (failure != null) lastFailure = failure;
                }
            });
        } catch (RuntimeException failure) {
            lastFailure = failure;
        }
    }

    private void reconcile() {
        Phase next = enabled && connected ? aggregate() : Phase.NONE;
        if (next == phase) return;
        if (phase != Phase.NONE) sender.cancelQueuedRing();
        phase = next;
        long token = ++revision;
        lastFailure = null;
        try {
            Objects.requireNonNull(sender.send(next)).whenComplete((unused, failure) -> {
                synchronized (PhoneCallGate.this) {
                    if (token != revision) return;
                    if (failure != null) lastFailure = failure;
                    else delivered = next;
                }
            });
        } catch (RuntimeException failure) {
            lastFailure = failure;
        }
    }
}
