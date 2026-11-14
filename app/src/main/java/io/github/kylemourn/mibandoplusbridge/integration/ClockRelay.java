// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.kylemourn.mibandoplusbridge.integration;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.SystemClock;

import java.util.ArrayDeque;

import io.github.kylemourn.mibandoplusbridge.BuildConfig;
import io.github.kylemourn.mibandoplusbridge.data.LocalPrefs;
import io.github.kylemourn.mibandoplusbridge.data.SessionLog;
import io.github.kylemourn.mibandoplusbridge.hook.ClockHostProfile;
import io.github.kylemourn.mibandoplusbridge.notify.PhoneAlarmNotice;
import io.github.kylemourn.mibandoplusbridge.protocol.BandAlarmCommand;
import io.github.kylemourn.mibandoplusbridge.service.BandLiveService;

/**
 * Module side of the phone-alarm link: the one place that decides what an occurrence means.
 *
 * <p>The clock reaches this process over an explicit broadcast, because a content provider would
 * need the clock to see the bridge package and a regular install does not grant that. The module
 * reaches the clock over a messenger the clock opens once per process; when that messenger is gone
 * the module says so and falls back to the notification the phone posted, instead of dropping the
 * command silently.</p>
 *
 * <p>Decisions come from {@link ClockArbiter}: an occurrence is identified by the alarm and the
 * second it started, so the dismiss the band echoes back after the module stopped the phone is the
 * same occurrence and runs nothing twice.</p>
 */
public final class ClockRelay {
    /** The notification path has no alarm id; the band packet still needs a number. */
    private static final int FALLBACK_ALARM_ID = 1;
    /** Round trip that asks the clock to hand over its command channel again. */
    private static final String ACTION_CHANNEL_REQUEST =
            BuildConfig.APPLICATION_ID + ".CLOCK_CHANNEL_REQUEST";
    private static final String CHANNEL_TOKEN = "miband-bridge-channel";
    private static final long REQUEST_SPACING_MS = 2_000L;
    private static final Object LOCK = new Object();
    private static final ClockArbiter arbiter = new ClockArbiter();
    /**
     * Commands decided before the clock's messenger came back. The clock holds the receipt, so the
     * module cannot recreate it on its own; keeping the command lets one request replay it.
     */
    private static final ArrayDeque<Pending> pending = new ArrayDeque<>();
    private static long lastRequestMs = Long.MIN_VALUE / 2;

    /** One decided command, kept while the clock's messenger is missing. */
    private static final class Pending {
        final String eventId;
        final int alarmId;
        final int repeat;
        final boolean snooze;

        Pending(String eventId, int alarmId, int repeat, boolean snooze) {
            this.eventId = eventId;
            this.alarmId = alarmId;
            this.repeat = repeat;
            this.snooze = snooze;
        }
    }

    private static volatile Messenger channel;
    private static volatile boolean hookSeen;

    private ClockRelay() {}

    /** True while the clock reports its rings itself, so the posted notification stays silent. */
    public static boolean hookActive() {
        return hookSeen;
    }

    /** The clock opened its command channel and said whether it reports rings. */
    public static void channel(Context context, Messenger messenger, boolean reportsRing) {
        channel = messenger;
        hookSeen = messenger != null && reportsRing;
        SessionLog.line(context, "CLOCK_CHANNEL_REGISTERED ring=" + (reportsRing ? 1 : 0)
                + " pending=" + pending.size());
        flushPending(context);
    }

    /** Legacy status report from an older clock process. */
    public static void status(Context context, String hook) {
        SessionLog.line(context, "CLOCK_HOOK " + (hook == null ? "" : hook));
        if (hook != null) hookSeen = !hook.contains("ring=0") || !hook.contains("stop=0");
    }

    private static final String STORE = "clock-ring";
    private static final String KEY_RING = "event";
    private static final String KEY_SEEN = "seen";

    /**
     * Keeps the ringing occurrence past a process restart: the app is neither persistent nor
     * exempt, so the band can close an alarm after this process was recreated.
     */
    private static void rememberRing(Context context, ClockEvents.Event event) {
        LocalPrefs.open(context, STORE).edit()
                .putString(KEY_RING, ClockEvents.encode(event))
                .putLong(KEY_SEEN, SystemClock.elapsedRealtime())
                .commit();
    }

    private static void forgetRing(Context context) {
        LocalPrefs.open(context, STORE).edit().remove(KEY_RING).remove(KEY_SEEN).commit();
    }

    /** Puts the remembered ring back into the arbiter before a band command is judged. */
    private static void restoreRing(Context context) {
        LocalPrefs prefs = LocalPrefs.open(context, STORE);
        String encoded = prefs.getString(KEY_RING, "");
        if (encoded.isEmpty()) return;
        ClockEvents.Event event = ClockEvents.decode(encoded);
        if (event == null) {
            forgetRing(context);
            return;
        }
        arbiter.onRing(event, prefs.getLong(KEY_SEEN, SystemClock.elapsedRealtime()));
        SessionLog.line(context, "CLOCK_RING_RESTORED id=" + event.alarmId);
    }

    /** A clock event: the phone started, closed, or snoozed an alarm. */
    public static void onHostEvent(Context context, ClockEvents.Event event) {
        if (context == null || event == null) return;
        // Only an injected clock sends these, so an event also proves the hook is alive.
        hookSeen = true;
        if (!AlarmLinkage.enabled(context)) {
            SessionLog.line(context, "CLOCK_LINKAGE_OFF kind=" + ClockEvents.kindName(event.kind)
                    + " id=" + event.alarmId);
            return;
        }
        long now = SystemClock.elapsedRealtime();
        ClockArbiter.Decision decision;
        synchronized (LOCK) {
            if (event.kind == ClockEvents.KIND_RING) {
                decision = arbiter.onRing(event, now);
                rememberRing(context, event);
            } else {
                ClockEvents.Event tracked = arbiter.current();
                ClockEvents.Event match = tracked != null && tracked.alarmId == event.alarmId
                        ? tracked.withKind(event.kind) : event;
                decision = arbiter.onHostStopped(match, now);
                if (tracked != null) forgetRing(context);
            }
        }
        SessionLog.line(context, "CLOCK_HOST_EVENT kind=" + ClockEvents.kindName(event.kind)
                + " id=" + event.alarmId + " result=" + decision.resultName()
                + " actions=" + decision.actions);
        for (int action : decision.actions) {
            if (action == ClockArbiter.ACTION_BAND_ALERT) {
                sendBand(ClockEvents.KIND_RING, event.alarmId, event.label);
            } else if (action == ClockArbiter.ACTION_BAND_DISMISS) {
                sendBand(ClockEvents.KIND_DISMISS, event.alarmId, "");
            }
        }
    }

    /** The band closed or snoozed its reminder: stop the phone the way its own button does. */
    public static void onBandAction(Context context, int op, int alarmId) {
        if (context == null) return;
        if (!AlarmLinkage.enabled(context)) {
            SessionLog.line(context, "CLOCK_LINKAGE_OFF band op=" + op + " id=" + alarmId);
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (arbiter.current() == null && alarmId != FALLBACK_ALARM_ID) {
            synchronized (LOCK) {
                restoreRing(context);
            }
        }
        // The band reports a tap and its own alert timeout the same way; the age tells them apart.
        long age = arbiter.ageMs(now);
        if (op == ClockEvents.KIND_SNOOZE && age >= ClockArbiter.BAND_TAP_WINDOW_MS) {
            SessionLog.line(context, "CLOCK_BAND_TIMEOUT op=" + op + " id=" + alarmId
                    + " age=" + (age / 1000L) + "s");
            op = ClockEvents.KIND_DISMISS;
        }
        ClockArbiter.Decision decision;
        synchronized (LOCK) {
            decision = op == ClockEvents.KIND_SNOOZE
                    ? arbiter.onDeviceSnooze(alarmId, now) : arbiter.onDeviceDismiss(alarmId, now);
        }
        ClockEvents.Event tracked = arbiter.current();
        SessionLog.line(context, "CLOCK_BAND_ACTION op=" + op + " id=" + alarmId
                + " result=" + decision.resultName() + " actions=" + decision.actions);
        if (tracked == null) {
            // Without a tracked occurrence there is no alarm to close, and replaying a notification
            // action here would snooze or dismiss whichever alarm the clock happens to hold. The
            // fallback ring carries its own id, so an echo of it is only recorded.
            SessionLog.line(context, alarmId == FALLBACK_ALARM_ID
                    ? "CLOCK_BAND_FALLBACK_ECHO op=" + op
                    : "CLOCK_BAND_UNTRACKED id=" + alarmId);
            return;
        }
        if (!decision.has(ClockArbiter.ACTION_HOST_STOP) && !decision.has(ClockArbiter.ACTION_HOST_SNOOZE)) {
            return;
        }
        boolean snooze = op == ClockEvents.KIND_SNOOZE;
        if (!snooze && PhoneAlarmNotice.replay(context, ClockEvents.KIND_DISMISS)) {
            // The clock's own close button is reachable while the ring notification is posted; it
            // ends the countdown too, which a stop intent posted from here does not.
            SessionLog.line(context, "CLOCK_BAND_NATIVE op=" + op + " id=" + alarmId);
            return;
        }
        sendHost(context, tracked, snooze, alarmId);
    }

    /** One command result came back from the clock process. */
    public static void onResult(Context context, String event, int action, int code, int rows) {
        SessionLog.line(context, "CLOCK_RESULT action=" + actionName(action) + " code=" + code
                + " rows=" + rows + " event=" + (event == null ? "none" : event));
    }

    /**
     * The hook could not see the ring on this clock build, so the notification it posts is the only
     * source. The band gets the reminder with a placeholder id, and the band's own close command
     * goes back through the notification buttons.
     */
    public static void onFallbackRing(Context context, String label) {
        SessionLog.line(context, "CLOCK_FALLBACK_RING");
        sendBand(ClockEvents.KIND_RING, FALLBACK_ALARM_ID, label);
    }

    private static boolean sendHost(Context context, ClockEvents.Event tracked, boolean snooze,
            int alarmId) {
        Pending command = new Pending(tracked == null ? null : tracked.eventId(), alarmId,
                tracked == null ? ClockEvents.REPEAT_UNKNOWN : tracked.repeat, snooze);
        Messenger messenger = channel;
        if (messenger == null) {
            // The clock keeps the messenger; asking for it again is the only way back.
            enqueue(context, command);
            requestChannel(context);
            return true;
        }
        if (deliver(messenger, command)) return true;
        // The clock process is gone: keep the command and ask the new one for its messenger.
        channel = null;
        hookSeen = false;
        enqueue(context, command);
        requestChannel(context);
        return true;
    }

    private static boolean deliver(Messenger messenger, Pending command) {
        Bundle data = new Bundle();
        data.putString("event", command.eventId);
        data.putInt("alarm", command.alarmId);
        data.putInt("repeat", command.repeat);
        Message message = Message.obtain();
        message.what = command.snooze ? ClockArbiter.ACTION_HOST_SNOOZE : ClockArbiter.ACTION_HOST_STOP;
        message.setData(data);
        try {
            messenger.send(message);
            return true;
        } catch (RemoteException | RuntimeException dead) {
            return false;
        }
    }

    private static void enqueue(Context context, Pending command) {
        synchronized (pending) {
            pending.addLast(command);
            while (pending.size() > 8) {
                pending.removeFirst();
                SessionLog.line(context, "CLOCK_PENDING_DROPPED");
            }
        }
    }

    private static void flushPending(Context context) {
        synchronized (pending) {
            while (!pending.isEmpty()) {
                Messenger messenger = channel;
                if (messenger == null) return;
                Pending command = pending.peekFirst();
                if (!deliver(messenger, command)) {
                    channel = null;
                    hookSeen = false;
                    return;
                }
                pending.removeFirst();
                SessionLog.line(context, "CLOCK_PENDING_SENT id=" + command.alarmId
                        + " snooze=" + command.snooze);
            }
        }
    }

    /** Asks the clock to hand over its messenger again; spaced so one failure asks once. */
    private static void requestChannel(Context context) {
        if (context == null) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastRequestMs < REQUEST_SPACING_MS) return;
        lastRequestMs = now;
        String clock = ClockHostProfile.presence(context).packageName();
        if (clock == null) {
            SessionLog.line(context, "CLOCK_CHANNEL_REQUEST_SKIPPED clock=absent");
            return;
        }
        Intent intent = new Intent(ACTION_CHANNEL_REQUEST);
        intent.setPackage(clock);
        intent.putExtra("token", CHANNEL_TOKEN);
        try {
            context.sendBroadcast(intent);
            SessionLog.line(context, "CLOCK_CHANNEL_REQUESTED pkg=" + clock);
        } catch (RuntimeException failed) {
            SessionLog.line(context, "CLOCK_CHANNEL_REQUEST_FAILED "
                    + failed.getClass().getSimpleName());
        }
    }

    private static void sendBand(int kind, int alarmId, String label) {
        try {
            int alertTime = kind == ClockEvents.KIND_RING ? (int) (System.currentTimeMillis() / 1000L) : -1;
            BandLiveService.sendSessionCommand(
                    BandAlarmCommand.operation(kind, alarmId, alertTime, label == null ? "" : label));
        } catch (RuntimeException notRunning) {
            // The band service decides on its own whether a session exists.
        }
    }

    private static String actionName(int action) {
        switch (action) {
            case ClockArbiter.ACTION_HOST_STOP: return "STOP";
            case ClockArbiter.ACTION_HOST_DISABLE: return "DISABLE";
            case ClockArbiter.ACTION_HOST_SNOOZE: return "SNOOZE";
            default: return String.valueOf(action);
        }
    }
}
