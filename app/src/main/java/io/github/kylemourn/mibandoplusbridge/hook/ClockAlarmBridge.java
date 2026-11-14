// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.kylemourn.mibandoplusbridge.hook;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.Messenger;
import android.os.SystemClock;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.kylemourn.mibandoplusbridge.BuildConfig;
import io.github.kylemourn.mibandoplusbridge.data.LocalPrefs;
import io.github.kylemourn.mibandoplusbridge.integration.ClockArbiter;
import io.github.kylemourn.mibandoplusbridge.integration.ClockEvents;
import io.github.kylemourn.mibandoplusbridge.integration.ClockRelayReceiver;
import io.github.kylemourn.mibandoplusbridge.hook.xposed.HookRuntime;
import io.github.kylemourn.mibandoplusbridge.hook.xposed.XC_MethodHook;
import io.github.kylemourn.mibandoplusbridge.hook.xposed.XposedBridge;
import io.github.kylemourn.mibandoplusbridge.hook.xposed.XposedHelpers;

/**
 * The clock side of the phone-alarm link, in two directions.
 *
 * <p>Outgoing: the klaxon entry and the alert service tell this class when an alarm starts, is
 * closed, or is snoozed. Each becomes one {@link ClockEvents} event that is broadcast to the
 * module. The identity is the alarm occurrence, so a stop the module caused itself is recognised by
 * the extra it put on the intent, not by how much time has passed.</p>
 *
 * <p>Incoming: this class opens a messenger to the module once per clock process, and runs the
 * commands the module sends through {@link ClockHostWriter} here, inside the clock. The result
 * travels back as one line, so a command that did nothing is visible in the log rather than assumed
 * to have worked.</p>
 */
public final class ClockAlarmBridge {
    private static final AtomicBoolean installed = new AtomicBoolean();
    /** Two reports of the same alarm inside this window are one occurrence. */
    private static final long SAME_RING_SEC = 5L;
    /** The alert service's own actions for the two buttons on the ring screen. */
    private static final String ACTION_DISMISS = "com.oplus.alarmclock.ALARM_DISMISS";
    private static final String ACTION_SNOOZE = "com.oplus.alarmclock.ALARM_SNOOZE";
    /** The module asks for the command channel again when its process lost the messenger. */
    private static final String ACTION_CHANNEL_REQUEST =
            BuildConfig.APPLICATION_ID + ".CLOCK_CHANNEL_REQUEST";
    /** Keeps an unrelated app from poking at alarm control; it is a guard, not a secret. */
    private static final String CHANNEL_TOKEN = "miband-bridge-channel";
    private static volatile Context app;
    private static volatile ClockHostProfile profile;
    private static volatile Messenger channel;
    private static volatile boolean reportsRing;
    private static volatile BroadcastReceiver channelRequests;
    /**
     * Alarms this side closed, so a ring that comes back for one of them is recognised as the
     * clock's own leftover snooze rather than a new alarm.
     */
    private static final Map<String, Long> CLOSED = new ConcurrentHashMap<>();
    private static final long CLOSED_WINDOW_MS = 30L * 60L * 1000L;
    private static final String CLOSED_STORE = "clock-closed";
    /** The service that posts the clock's five minute snooze card. */
    private static final String SNOOZE_SERVICE =
            "com.oplus.alarmclock.alarmclock.fluid.AlarmSnoozeService";
    private static final int SNOOZE_NOTIFICATION_ID = -1018;
    private static final long SNOOZE_CARD_WINDOW_MS = 8L * 60L * 1000L;
    /** Alarms the clock queued recently, so the snooze it schedules can be taken back. */
    private static final java.util.List<PendingAlarm> SCHEDULED =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    private static volatile String ringingEvent;
    private static volatile int ringingScheduleId = -1;
    private static volatile int ringingRowId = -1;
    private static volatile int ringingRepeat = ClockEvents.REPEAT_UNKNOWN;
    private static volatile Object ringingSchedule;
    /** The alert service the ring went through, so its own close entry can be called for the band. */
    private static volatile Object serviceRef;
    /** When the clock's own close entry last ran because the user tapped close on the phone. */
    private static volatile long userDismissAt = Long.MIN_VALUE / 2;
    /** Set while this bridge itself calls the close entry, so it is not taken for a user close. */
    private static volatile boolean inSoftDismiss;
    /** How long after a user close a stop still counts as that close. */
    private static final long USER_CLOSE_MS = 2_500L;

    private ClockAlarmBridge() {}

    public static void install(Context context, ClassLoader loader) {
        if (context == null || !installed.compareAndSet(false, true)) return;
        app = context;
        ClockHostProfile host = ClockHostProfile.probe(context, loader);
        profile = host;
        if (!host.canRing() && !host.canStop()) {
            HookRuntime.log("CLOCK_HOOK_SKIPPED " + host.describe());
            return;
        }
        boolean[] ringHooked = {false};
        if (host.canRing()) {
            HookRuntime.tryHook("CLOCK_RING_HOOK_FAILED", () -> {
                XposedBridge.hookMethod(host.ringMethod, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        onObservedRing(param.args);
                    }
                });
                ringHooked[0] = true;
            });
        }
        if (host.canStop()) {
            HookRuntime.tryHook("CLOCK_STOP_HOOK_FAILED", () -> XposedBridge.hookMethod(
                    host.stopMethod, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            // The service fills its schedule field while handling the intent.
                            onObservedStop(param.thisObject, param.args);
                        }
                    }));
        }
        // Only a hook that can see the ring may suppress the notification fallback.
        openChannel(context, ringHooked[0]);
        listenForChannelRequests(context);
        watchUserClose(host);
        watchSnoozeService(loader);
        watchAlarmScheduling(loader);
        HookRuntime.log("CLOCK_HOOK_INSTALLED pkg=" + host.packageName
                + " ring=" + (ringHooked[0] ? 1 : 0)
                + " stop=" + (host.canStop() ? 1 : 0));
    }

    // ---- clock to module ----

    private static void onObservedRing(Object[] args) {
        Context context = app;
        if (context == null || args == null) return;
        Object schedule = null;
        for (Object arg : args) {
            if (arg == null) continue;
            schedule = arg;
            break;
        }
        if (schedule == null) return;
        int scheduleId = scheduleId(schedule);
        int rowId = rowId(schedule);
        int repeat = repeatOf(schedule);
        long epoch = epochNow();
        String tracked = ringingEvent;
        if (tracked != null && ringingScheduleId == scheduleId && sameOccurrence(tracked, epoch)) {
            // The klaxon entry fires more than once while the screen wakes. One ring, one report.
            HookRuntime.log("CLOCK_RING_DUPLICATE id=" + scheduleId);
            return;
        }
        ClockEvents.Event ring = ClockEvents.ring(scheduleId, epoch, repeat, labelOf(schedule));
        String eventId = ring.eventId();
        ringingEvent = eventId;
        ringingScheduleId = scheduleId;
        ringingRowId = rowId;
        ringingRepeat = repeat;
        ringingSchedule = schedule;
        HookRuntime.log("CLOCK_RING id=" + scheduleId + " row=" + rowId + " repeat="
                + ClockEvents.repeatName(repeat));
        Intent intent = relay("event");
        intent.putExtra("event", ClockEvents.encode(ring));
        send(context, intent);
    }

    private static void onObservedStop(Object service, Object[] args) {
        Context context = app;
        if (service != null) serviceRef = service;
        if (context == null || args == null || args.length == 0) return;
        Intent intent = null;
        for (Object arg : args) {
            if (arg instanceof Intent value) {
                intent = value;
                break;
            }
        }
        if (intent == null) return;
        String mine = intent.getStringExtra(ClockHostWriter.EXTRA_BRIDGE_EVENT);
        note(context, "INTENT action=" + intent.getAction() + " id=" + idFromData(intent)
                + " dismissed=" + intent.getBooleanExtra(ClockHostWriter.EXTRA_DISMISSED, true)
                + " extras=" + keysOf(intent));
        if (mine != null) {
            // Our own stop or snooze came back through the service. Identity says so; time does not.
            HookRuntime.log("CLOCK_STOP_ECHO_SKIPPED event=" + mine);
            return;
        }
        int id = idFromData(intent);
        Object schedule = scheduleFromService(service);
        if (schedule == null) schedule = scheduleOf(intent);
        if (schedule != null) {
            int fromSchedule = scheduleId(schedule);
            if (fromSchedule > 0) id = fromSchedule;
        }
        boolean dismissed = intent.getBooleanExtra(ClockHostWriter.EXTRA_DISMISSED, true);
        String action = intent.getAction();
        boolean stop = ClockHostWriter.isStopAction(action);
        boolean dismissAction = ACTION_DISMISS.equals(action);
        boolean snoozeAction = ACTION_SNOOZE.equals(action);
        HookRuntime.log("CLOCK_SERVICE_INTENT action=" + action + " id=" + id
                + " stop=" + stop + " dismiss=" + dismissAction + " snooze=" + snoozeAction
                + " hasDismissed=" + intent.hasExtra(ClockHostWriter.EXTRA_DISMISSED)
                + " schedule=" + (schedule == null ? "-" : schedule.getClass().getName())
                + " extras=" + keysOf(intent));
        if (!stop && !dismissAction && !snoozeAction) {
            // Every alarm reaches the alert service through its start, and that start is the ring.
            onServiceRing(context, id, schedule, action);
            return;
        }
        boolean closes = dismissAction || (!snoozeAction && dismissed);
        int repeat = schedule == null ? ringingRepeat : repeatOf(schedule);
        long epoch = epochNow();
        String tracked = ringingEvent;
        if (tracked != null && ringingScheduleId == id) {
            int cut = tracked.lastIndexOf(':');
            if (cut > 0) {
                try {
                    epoch = Long.parseLong(tracked.substring(cut + 1));
                } catch (NumberFormatException ignored) {
                    // Keep the current second.
                }
            }
        }
        // A close after the user tapped close, a snooze the clock posted, or the clock ending its
        // own repeat window with the alarm still switched on.
        int kind;
        if (closes || afterUserClose()) {
            kind = ClockEvents.KIND_DISMISS;
        } else if (snoozeAction) {
            kind = ClockEvents.KIND_SNOOZE;
        } else {
            kind = ClockEvents.KIND_ENDED;
        }
        ClockEvents.Event event = ClockEvents.ring(id, epoch, repeat, "").withKind(kind);
        HookRuntime.log("CLOCK_STOP id=" + id + " kind=" + ClockEvents.kindName(kind)
                + " closes=" + closes + " action=" + action);
        Intent relay = relay("event");
        relay.putExtra("event", ClockEvents.encode(event));
        send(context, relay);
        if (closes) {
            ringingEvent = null;
            ringingSchedule = null;
        }
    }

    /** The ring seen at the alert service start, for builds whose klaxon entry never runs. */
    private static void onServiceRing(Context context, int scheduleId, Object schedule, String action) {
        if (scheduleId <= 0) return;
        Row row = readRow(context, scheduleId);
        if (row != null && !row.enabled() && recentlyClosed(context, scheduleId, row.alarmId())) {
            // The clock queues its own snooze when an alarm starts ringing. Closing the alarm from
            // the band clears the switch but leaves that queue, so the same row rings again five
            // minutes later and shows a countdown nobody asked for. A row whose switch is already
            // off can only be that leftover ring: stop it here and keep it away from the band.
            HookRuntime.log("CLOCK_SNOOZE_REFIRE id=" + scheduleId + " row=" + row.alarmId());
            note(context, "SNOOZE_REFIRE id=" + scheduleId + " row=" + row.alarmId());
            softDismiss();
            return;
        }
        long epoch = epochNow();
        String tracked = ringingEvent;
        if (tracked != null && ringingScheduleId == scheduleId && sameOccurrence(tracked, epoch)) {
            HookRuntime.log("CLOCK_RING_DUPLICATE id=" + scheduleId + " via=service");
            return;
        }
        int repeat = row != null ? row.repeat()
                : schedule == null ? ClockEvents.REPEAT_UNKNOWN : repeatOf(schedule);
        int rowValue = row != null && row.alarmId() > 0 ? row.alarmId()
                : schedule == null ? scheduleId : rowId(schedule);
        String label = row != null ? row.label() : schedule == null ? "" : labelOf(schedule);
        ClockEvents.Event ring = ClockEvents.ring(scheduleId, epoch, repeat, label);
        ringingEvent = ring.eventId();
        ringingScheduleId = scheduleId;
        ringingRowId = rowValue;
        ringingRepeat = repeat;
        ringingSchedule = schedule;
        HookRuntime.log("CLOCK_RING id=" + scheduleId + " row=" + rowValue + " repeat="
                + ClockEvents.repeatName(repeat) + " via=service action=" + action);
        note(context, "RING id=" + scheduleId + " row=" + rowValue + " repeat="
                + ClockEvents.repeatName(repeat) + " action=" + action);
        Intent relay = relay("event");
        relay.putExtra("event", ClockEvents.encode(ring));
        send(context, relay);
    }

    /** The schedule the alert service is handling, which it keeps on itself rather than in the intent. */
    private static Object scheduleFromService(Object service) {
        Object value = field(service, "mCurrentAlarmSchedule");
        if (value == null) value = field(service, "mAlarmSchedule");
        if (value == null) value = field(service, "mSchedule");
        return value;
    }

    /**
     * Reads the schedule row from the clock's own provider.
     *
     * <p>This code runs inside the clock process, so the private provider is reachable here even
     * though another package would be filtered out.</p>
     */
    private static Row readRow(Context context, int scheduleId) {
        ClockHostProfile host = profile;
        if (host == null || !host.canDisable()) return null;
        String[] columns = {"alarm_id", "daysofweek", "deleteAfterUse", "message", "enabled"};
        try (android.database.Cursor cursor = context.getContentResolver()
                .query(host.scheduleUri(scheduleId), columns, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) {
                HookRuntime.log("CLOCK_SCHEDULE_ROW id=" + scheduleId + " empty");
                return null;
            }
            int alarmId = cursor.getInt(0);
            int days = cursor.getInt(1);
            boolean deleteAfterUse = cursor.getInt(2) != 0;
            String label = cursor.getString(3);
            boolean enabled = cursor.getInt(4) != 0;
            // A schedule with no weekday repeats rings once and then clears its own switch.
            int repeat = ClockEvents.repeatOfDays(days, deleteAfterUse);
            HookRuntime.log("CLOCK_SCHEDULE_ROW id=" + scheduleId + " alarm=" + alarmId
                    + " days=" + days + " delete=" + deleteAfterUse + " enabled=" + enabled
                    + " repeat=" + ClockEvents.repeatName(repeat));
            return new Row(alarmId, repeat, label == null ? "" : label, enabled);
        } catch (RuntimeException failed) {
            HookRuntime.log("CLOCK_SCHEDULE_ROW_FAILED id=" + scheduleId + " "
                    + failed.getClass().getSimpleName());
            return null;
        }
    }

    /** The alarm row behind a ringing schedule, as the clock's own provider reports it. */
    private record Row(int alarmId, int repeat, String label, boolean enabled) {}

    /**
     * Notes that this side closed a schedule, so the ring the clock queues for it can be told apart
     * from a fresh alarm even after the clock process is rebuilt.
     */
    private static void rememberClosed(Context context, int scheduleId, int alarmId) {
        long now = System.currentTimeMillis();
        CLOSED.put("s" + scheduleId, now);
        if (alarmId > 0) CLOSED.put("a" + alarmId, now);
        try {
            LocalPrefs prefs = LocalPrefs.open(context, CLOSED_STORE);
            LocalPrefs.Editor edit = prefs.edit().putLong("s" + scheduleId, now);
            if (alarmId > 0) edit = edit.putLong("a" + alarmId, now);
            edit.commit();
        } catch (RuntimeException ignored) {
            // The memory copy still covers this clock process; the store only outlives a restart.
        }
    }

    /** Whether this schedule or its alarm row was closed here recently enough to explain a ring. */
    private static boolean recentlyClosed(Context context, int scheduleId, int alarmId) {
        long limit = System.currentTimeMillis() - CLOSED_WINDOW_MS;
        if (recent(context, "s" + scheduleId, limit)) return true;
        return alarmId > 0 && recent(context, "a" + alarmId, limit);
    }

    private static boolean recent(Context context, String key, long limit) {
        Long seen = CLOSED.get(key);
        if (seen != null && seen >= limit) return true;
        try {
            long stored = LocalPrefs.open(context, CLOSED_STORE).getLong(key, 0L);
            return stored >= limit;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /** Mirrors one observation into the module's session log, where it survives a logcat flush. */
    private static void note(Context context, String text) {
        Intent intent = relay("note");
        intent.putExtra("text", text);
        send(context, intent);
    }


    /** The schedule the clock hands its alert service, whatever key this build keeps it under. */
    private static Object scheduleOf(Intent intent) {
        try {
            Object direct = intent.getParcelableExtra(ClockHostWriter.EXTRA_SCHEDULE);
            if (direct != null) return direct;
        } catch (RuntimeException ignored) {
            // Another build may keep the schedule under a different key.
        }
        try {
            android.os.Bundle extras = intent.getExtras();
            if (extras == null) return null;
            for (String key : extras.keySet()) {
                Object value = extras.get(key);
                if (value == null) continue;
                if (value.getClass().getName().contains("AlarmSchedule")) return value;
            }
        } catch (RuntimeException ignored) {
            // An unreadable bundle still leaves the data uri to identify the alarm.
        }
        return null;
    }

    private static String keysOf(Intent intent) {
        try {
            android.os.Bundle extras = intent.getExtras();
            return extras == null ? "-" : extras.keySet().toString();
        } catch (RuntimeException unusable) {
            return "?";
        }
    }

    // ---- module to clock ----

    private static void openChannel(Context context, boolean seesRing) {
        reportsRing = seesRing;
        if (channel == null) {
            HandlerThread thread = HookRuntime.thread("clock-bridge");
            Handler handler = new Handler(thread.getLooper()) {
                @Override public void handleMessage(Message message) {
                    run(message);
                }
            };
            channel = new Messenger(handler);
        }
        announce(context);
        HookRuntime.log("CLOCK_CHANNEL_OPENED ring=" + (reportsRing ? 1 : 0));
    }

    /** Hands the messenger to the module again; the module can lose it when its process restarts. */
    private static void announce(Context context) {
        Messenger messenger = channel;
        Context host = context != null ? context : app;
        if (messenger == null || host == null) return;
        Intent intent = relay("channel");
        intent.putExtra("channel", messenger);
        intent.putExtra("ring", reportsRing);
        send(host, intent);
    }

    /**
     * The module can start after the clock and no longer remembers the messenger, so the clock
     * answers a request for it. The receiver only re-sends that messenger and nothing else.
     */
    private static void listenForChannelRequests(Context context) {
        if (channelRequests != null) return;
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent intent) {
                if (intent == null || !ACTION_CHANNEL_REQUEST.equals(intent.getAction())) return;
                if (!CHANNEL_TOKEN.equals(intent.getStringExtra("token"))) return;
                announce(ctx);
                HookRuntime.log("CLOCK_CHANNEL_REANNOUNCED ring=" + (reportsRing ? 1 : 0));
            }
        };
        channelRequests = receiver;
        IntentFilter filter = new IntentFilter(ACTION_CHANNEL_REQUEST);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(receiver, filter);
            }
        } catch (RuntimeException failed) {
            channelRequests = null;
            HookRuntime.log("CLOCK_CHANNEL_REQUEST_UNSUPPORTED " + failed.getClass().getSimpleName());
        }
    }

    private static void run(Message message) {
        Context context = app;
        if (context == null) return;
        Bundle data = message.getData();
        String eventId = data.getString("event");
        int action = message.what;
        int scheduleId = data.getInt("alarm", -1);
        int repeat = data.getInt("repeat", ClockEvents.REPEAT_UNKNOWN);
        // The switch does not always live on the schedule row the klaxon reports.
        int rowId = ringingRowId > 0 && ringingScheduleId == scheduleId
                ? ringingRowId : ClockHostWriter.readAlarmId(context, profile, scheduleId);
        boolean snooze = action == ClockArbiter.ACTION_HOST_SNOOZE;
        boolean oneShot = repeat == ClockEvents.REPEAT_NO;
        boolean written = false;
        if (!snooze && oneShot && rowId > 0) {
            // Write before the stop: the clock's own dismiss runs as soon as the stop lands and a
            // delete-after-use alarm leaves nothing to grey afterwards.
            ClockHostWriter.Result kept = ClockHostWriter.keep(context, profile, rowId);
            ClockHostWriter.Result off = ClockHostWriter.disable(context, profile, rowId);
            ClockHostWriter.Result list = ClockHostWriter.disableSchedule(context, profile, scheduleId);
            written = off.code() == ClockHostWriter.OK;
            rememberClosed(context, scheduleId, rowId);
            note(context, "CLOSE id=" + rowId + " sched=" + scheduleId + " keep=" + kept.code()
                    + " disable=" + off.code() + "/" + off.rows() + " list=" + list.code() + "/" + list.rows());
            report(context, eventId, ClockArbiter.ACTION_HOST_DISABLE, off.code(), off.rows());
        }
        if (!snooze) {
            // The clock queued its snooze when the alarm started; take that entry back first, so no
            // countdown is left to post a card or ring again five minutes from now.
            cancelQueuedSnooze(context, scheduleId);
            // The clock runs its own close while the service is still alive; asking it now is what
            // drops the snooze it queued when the alarm started. Done later the service is gone.
            boolean early = softDismiss();
            note(context, "CLOSE_DISMISS ok=" + early + " sched=" + scheduleId);
        }
        ClockHostWriter.Result stopped = ClockHostWriter.stop(context, profile, eventId, scheduleId,
                !snooze, ringingSchedule);
        note(context, "COMMAND action=" + action + " id=" + scheduleId + " code=" + stopped.code());
        report(context, eventId, action, stopped.code(), stopped.rows());
        if (!snooze) {
            // The clock's own dismiss also clears the countdown it would otherwise post, so it runs
            // for every close; the writes above already carried the switch.
            verifyOrDisable(context, rowId, scheduleId, eventId, oneShot && !written, written);
        }
        if (!snooze) {
            ringingEvent = null;
            ringingSchedule = null;
        }
    }

    /**
     * Runs the clock's own close so any pending countdown is dropped, then closes the switch when the
     * rows were not written up front (an alarm row the schedule did not name, for instance).
     */
    private static void verifyOrDisable(Context context, int alarmId, int scheduleId, String eventId,
                                        boolean disable, boolean written) {
        HookRuntime.main().postDelayed(() -> {
            boolean soft = softDismiss();
            if (!disable) {
                note(context, "SOFT_DISMISS ok=" + soft + " id=" + alarmId
                        + " written=" + written);
                report(context, eventId, ClockArbiter.ACTION_HOST_DISABLE, ClockHostWriter.OK, written ? 1 : -1);
                return;
            }
            int enabled = ClockHostWriter.readEnabled(context, profile, alarmId);
            String row = ClockHostWriter.describeAlarm(context, profile, alarmId);
            ClockHostWriter.Result list = ClockHostWriter.disableSchedule(context, profile, scheduleId);
            note(context, "SOFT_DISMISS ok=" + soft + " enabled=" + enabled
                    + " list=" + list.code() + "/" + list.rows() + " " + row);
            if (enabled == 0) {
                HookRuntime.log("CLOCK_DISABLE_NATIVE id=" + alarmId + " " + row);
                report(context, eventId, ClockArbiter.ACTION_HOST_DISABLE, ClockHostWriter.OK, 1);
                return;
            }
            ClockHostWriter.Result off = ClockHostWriter.disable(context, profile, alarmId);
            note(context, "DISABLE id=" + alarmId + " code=" + off.code() + " rows=" + off.rows());
            report(context, eventId, ClockArbiter.ACTION_HOST_DISABLE, off.code(), off.rows());
            recheck(context, alarmId);
        }, 1_500L);
    }

    /**
     * Calls the entry the clock runs for its own close button, which marks the alarm dismissed so no
     * countdown is posted. The method names are the shrunken ones this build ships; only the close
     * flag is passed, and a build without them reports {@code false} instead of guessing further.
     */
    private static boolean softDismiss() {
        Object service = serviceRef;
        if (service == null) return false;
        inSoftDismiss = true;
        try {
            for (Class<?> type : new Class<?>[]{boolean.class, Boolean.class}) {
                try {
                    java.lang.reflect.Method method = service.getClass().getDeclaredMethod("w", type);
                    method.setAccessible(true);
                    Object result = method.invoke(service, Boolean.TRUE);
                    HookRuntime.log("CLOCK_SOFT_DISMISS ok param=" + type.getSimpleName()
                            + " result=" + result);
                    return true;
                } catch (RuntimeException | ReflectiveOperationException failed) {
                    HookRuntime.log("CLOCK_SOFT_DISMISS_FAILED param=" + type.getSimpleName() + " "
                            + failed.getClass().getSimpleName());
                }
            }
            return false;
        } finally {
            inSoftDismiss = false;
        }
    }

    /**
     * Watches the entry the clock runs when the user taps close on the phone.
     * <p>Its own close and its own end of the repeat window both stop the ring, so the stop intent
     * alone cannot tell them apart. This entry only runs for a user close, so a stop that follows it
     * is a real close and everything else is the clock ending the ring by itself.
     */
    private static void watchUserClose(ClockHostProfile host) {
        Class<?> service = host == null ? null : host.serviceClass;
        if (service == null) return;
        for (Class<?> type : new Class<?>[]{boolean.class, Boolean.class}) {
            java.lang.reflect.Method method;
            try {
                method = service.getDeclaredMethod("w", type);
                method.setAccessible(true);
            } catch (ReflectiveOperationException | RuntimeException absent) {
                continue;
            }
            HookRuntime.tryHook("CLOCK_USER_CLOSE_HOOK_FAILED", () ->
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (inSoftDismiss) return;
                            userDismissAt = SystemClock.elapsedRealtime();
                        }
                    }));
            HookRuntime.log("CLOCK_USER_CLOSE_WATCHED param=" + type.getSimpleName());
            return;
        }
    }

    /**
     * Drops the countdown card the clock posts a few minutes after an alarm this side closed.
     *
     * <p>The clock queues that card when the alarm starts, and stopping the alarm does not take the
     * queue back. Clearing the card on arrival keeps a closed alarm from asking for a snooze nobody
     * chose; the ring that would follow is suppressed on its own.</p>
     */
    private static void watchSnoozeService(ClassLoader loader) {
        HookRuntime.tryHook("CLOCK_SNOOZE_WATCH_FAILED", () -> {
            Class<?> service = XposedHelpers.findClass(SNOOZE_SERVICE, loader);
            java.lang.reflect.Method start = service.getDeclaredMethod("onStartCommand",
                    Intent.class, int.class, int.class);
            XposedBridge.hookMethod(start, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    // A sticky restart would bring the card back after this side takes it down.
                    if (recentClosure()) param.setResult(android.app.Service.START_NOT_STICKY);
                }

                @Override protected void afterHookedMethod(MethodHookParam param) {
                    clearSnoozeCard(param.thisObject);
                }
            });
        });
    }

    private static void clearSnoozeCard(Object service) {
        Context context = app;
        if (context == null || !recentClosure()) return;
        dropCard(context);
        if (service instanceof android.app.Service running) {
            try {
                running.stopSelf();
            } catch (RuntimeException ignored) {
                // Already stopping counts as done.
            }
        }
        Handler main = HookRuntime.main();
        for (long delay : new long[]{600L, 1_800L}) {
            main.postDelayed(() -> dropCard(context), delay);
        }
        HookRuntime.log("CLOCK_SNOOZE_CARD_CLEARED");
        note(context, "SNOOZE_CARD_CLEARED");
    }

    /** Takes the card's notification away and asks the clock to let that service go. */
    private static void dropCard(Context context) {
        try {
            android.app.NotificationManager manager =
                    context.getSystemService(android.app.NotificationManager.class);
            if (manager != null) manager.cancel(SNOOZE_NOTIFICATION_ID);
        } catch (RuntimeException ignored) {
            // The card is best effort; a ring that still starts is suppressed separately.
        }
        try {
            String owner = profile != null ? profile.packageName : context.getPackageName();
            Intent stop = new Intent();
            stop.setClassName(owner, SNOOZE_SERVICE);
            context.stopService(stop);
        } catch (RuntimeException ignored) {
            // The service may already be gone.
        }
    }

    /**
     * Watches the clock's own alarm scheduling.
     *
     * <p>The snooze that produces the five minute countdown is an ordinary alarm entry, so the only
     * way to take it back is to hold on to the pending intent the clock handed the alarm manager and
     * cancel that same one.</p>
     */
    private static void watchAlarmScheduling(ClassLoader loader) {
        HookRuntime.tryHook("CLOCK_ALARM_WATCH_FAILED", () -> {
            Class<?> manager = android.app.AlarmManager.class;
            for (String name : new String[]{"set", "setExact", "setExactAndAllowWhileIdle", "setWindow"}) {
                XposedBridge.hookAllMethods(manager, name, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        rememberAlarm(param.args);
                    }
                });
            }
        });
    }

    private static void rememberAlarm(Object[] args) {
        if (args == null) return;
        long trigger = -1L;
        android.app.PendingIntent pending = null;
        for (Object arg : args) {
            if (arg instanceof android.app.PendingIntent value) pending = value;
            else if (arg instanceof Long value && trigger < 0L) trigger = value;
        }
        if (pending == null || trigger < 0L) return;
        SCHEDULED.add(new PendingAlarm(pending, trigger));
        while (SCHEDULED.size() > 32) SCHEDULED.remove(0);
    }

    /** Takes back the alarm the clock queued for this schedule, when it is the five minute one. */
    private static void cancelQueuedSnooze(Context context, int scheduleId) {
        android.app.AlarmManager manager = context.getSystemService(android.app.AlarmManager.class);
        if (manager == null || scheduleId <= 0) return;
        long now = System.currentTimeMillis();
        String needle = "/schedules/" + scheduleId;
        for (PendingAlarm alarm : SCHEDULED) {
            if (alarm.triggerAtMs() <= now || alarm.triggerAtMs() > now + 15L * 60L * 1000L) continue;
            String data = dataOf(alarm.intent());
            if (data == null || !data.contains(needle)) continue;
            try {
                manager.cancel(alarm.intent());
                alarm.intent().cancel();
                HookRuntime.log("CLOCK_SNOOZE_CANCELLED id=" + scheduleId);
                note(context, "SNOOZE_CANCELLED id=" + scheduleId);
            } catch (RuntimeException ignored) {
                // A cancelled or recycled intent simply means there is nothing left to cancel.
            }
            SCHEDULED.remove(alarm);
        }
    }

    private static String dataOf(android.app.PendingIntent pending) {
        try {
            java.lang.reflect.Method getIntent = android.app.PendingIntent.class.getDeclaredMethod("getIntent");
            getIntent.setAccessible(true);
            Intent intent = (Intent) getIntent.invoke(pending);
            return intent == null || intent.getData() == null ? null : intent.getData().toString();
        } catch (ReflectiveOperationException | RuntimeException failed) {
            return null;
        }
    }

    private record PendingAlarm(android.app.PendingIntent intent, long triggerAtMs) {}

    /** Whether an alarm was closed here recently enough to own the card the clock posts next. */
    private static boolean recentClosure() {        long limit = System.currentTimeMillis() - SNOOZE_CARD_WINDOW_MS;
        for (Long seen : CLOSED.values()) {
            if (seen != null && seen >= limit) return true;
        }
        return false;
    }

    /** True when this stop follows a close the user tapped on the phone, not the clock's own end. */
    private static boolean afterUserClose() {
        return SystemClock.elapsedRealtime() - userDismissAt < USER_CLOSE_MS;    }

    /** Looks at the switch again after the clock finished handling the stop, one and a half seconds on. */
    private static void recheck(Context context, int alarmId) {
        Handler main = HookRuntime.main();
        main.postDelayed(() -> {
            int enabled = ClockHostWriter.readEnabled(context, profile, alarmId);
            String row = ClockHostWriter.describeAlarm(context, profile, alarmId);
            HookRuntime.log("CLOCK_DISABLE_AFTER id=" + alarmId + " enabled=" + enabled + " " + row);
        }, 1_500L);
    }

    private static void report(Context context, String eventId, int action, int code, int rows) {
        Intent intent = relay("result");
        intent.putExtra("event", eventId);
        intent.putExtra("action", action);
        intent.putExtra("code", code);
        intent.putExtra("rows", rows);
        send(context, intent);
    }

    // ---- helpers ----

    private static Intent relay(String method) {
        Intent intent = new Intent(ClockRelayReceiver.ACTION);
        intent.setClassName(BuildConfig.APPLICATION_ID, ClockRelayReceiver.class.getName());
        intent.putExtra("method", method);
        return intent;
    }

    private static void send(Context context, Intent intent) {
        HookRuntime.log("CLOCK_RELAY_OUT method=" + intent.getStringExtra("method"));
        try {
            context.sendBroadcast(intent);
        } catch (RuntimeException failure) {
            HookRuntime.log("CLOCK_BROADCAST_FAILED " + failure.getClass().getSimpleName());
        }
    }

    private static long epochNow() {
        return System.currentTimeMillis() / 1000L;
    }

    private static boolean sameOccurrence(String tracked, long epoch) {
        int cut = tracked.lastIndexOf(':');
        if (cut <= 0) return false;
        try {
            return Math.abs(epoch - Long.parseLong(tracked.substring(cut + 1))) <= SAME_RING_SEC;
        } catch (NumberFormatException notASecond) {
            return false;
        }
    }

    private static int idFromData(Intent intent) {
        android.net.Uri data = intent.getData();
        if (data == null) return -1;
        String last = data.getLastPathSegment();
        if (last == null) return -1;
        try {
            return Integer.parseInt(last);
        } catch (NumberFormatException notANumber) {
            return -1;
        }
    }

    /** The schedule id the alert service keys its stop intent by. */
    private static int scheduleId(Object schedule) {
        Object value = call(schedule, "getAlarmId");
        if (value instanceof Integer id) return id;
        value = call(schedule, "getId");
        if (value instanceof Integer id) return id;
        value = field(schedule, "mId");
        if (value instanceof Integer id) return id;
        return -1;
    }

    /** The alarm row the next-time switch lives on, which is not always the schedule id. */
    private static int rowId(Object schedule) {
        Object value = call(schedule, "getAlarmId");
        if (value instanceof Integer id) return id;
        value = field(schedule, "mAlarmId");
        if (value instanceof Integer id) return id;
        return scheduleId(schedule);
    }

    private static int repeatOf(Object schedule) {
        Object value = call(schedule, "getRepeatDays");
        if (value instanceof Integer days) return days == 0 ? ClockEvents.REPEAT_NO : ClockEvents.REPEAT_YES;
        value = call(schedule, "isRepeating");
        if (value instanceof Boolean on) return on ? ClockEvents.REPEAT_YES : ClockEvents.REPEAT_NO;
        value = field(schedule, "mRepeatDays");
        if (value instanceof Integer days) return days == 0 ? ClockEvents.REPEAT_NO : ClockEvents.REPEAT_YES;
        value = field(schedule, "mRepeat");
        if (value instanceof Boolean on) return on ? ClockEvents.REPEAT_YES : ClockEvents.REPEAT_NO;
        if (value instanceof Integer days) return days == 0 ? ClockEvents.REPEAT_NO : ClockEvents.REPEAT_YES;
        Object alarm = field(schedule, "mAlarm");
        if (alarm != null) {
            Object set = field(alarm, "mRepeatSet");
            if (set instanceof Integer days) {
                return days == 0 ? ClockEvents.REPEAT_NO : ClockEvents.REPEAT_YES;
            }
            Object on = field(alarm, "mRepeat");
            if (on instanceof Boolean flag) {
                return flag ? ClockEvents.REPEAT_YES : ClockEvents.REPEAT_NO;
            }
        }
        return ClockEvents.REPEAT_UNKNOWN;
    }

    private static String labelOf(Object schedule) {
        Object value = call(schedule, "getLabel");
        if (value instanceof String text) return text;
        value = field(schedule, "mLabel");
        if (value instanceof String text) return text;
        return "";
    }

    private static Object call(Object target, String name) {
        try {
            return XposedHelpers.callMethod(target, name);
        } catch (Throwable missing) {
            return null;
        }
    }

    private static Object field(Object target, String name) {
        try {
            return XposedHelpers.getObjectField(target, name);
        } catch (Throwable missing) {
            return null;
        }
    }
}
