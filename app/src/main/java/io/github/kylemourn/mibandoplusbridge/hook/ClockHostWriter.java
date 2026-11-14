// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.kylemourn.mibandoplusbridge.hook;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Parcelable;

/**
 * Runs a phone-alarm command inside the clock process.
 *
 * <p>The alarm rows belong to the clock, and a third-party uid is filtered out of them, so the
 * commands the band sends have to run here. Each command goes through the entry the clock's own
 * button uses: the alert service for stop and snooze, and the alarm row for the next-time switch.
 * Nothing is deleted and the row layout is not changed; the switch the user would flip goes off.</p>
 *
 * <p>The result is a code plus the number of rows the provider actually changed. A switch that was
 * not changed reports {@link #MISS}, so a failed write never looks like a closed switch.</p>
 */
public final class ClockHostWriter {
    /** Extra that marks an intent this module started, so the stop echo is recognised by identity. */
    public static final String EXTRA_BRIDGE_EVENT = "io.github.kylemourn.mibandoplusbridge.CLOCK_EVENT";
    /** The action the alert service listens for when an alarm is closed or snoozed. */
    static final String ACTION_STOP = "STOP_ALARM";
    /**
     * The clock's own close button action. Posting it from another app reaches
     * {@code AlarmService.onStartCommand} but the service does not complete that path, so the stop
     * action above stays the way in; the constant is kept for the log comparison only.
     */
    static final String ACTION_DISMISS = "com.oplus.alarmclock.ALARM_DISMISS";

    /**
     * The service also starts when an alarm begins to ring, so only an explicit stop action is a
     * stop. Everything else that reaches the same entry is the ring itself.
     */
    public static boolean isStopAction(String action) {
        return action != null && (ACTION_STOP.equals(action) || action.endsWith("." + ACTION_STOP));
    }
    /** Extra the clock itself uses to tell a close from a snooze. */
    static final String EXTRA_DISMISSED = "IS_ALARM_DISMISSED";
    /** The clock's snooze path expects the schedule object it was ringing for. */
    static final String EXTRA_SCHEDULE = "intent.extra.alarm";

    public static final int OK = 0;
    public static final int UNAVAILABLE = 1;
    public static final int MISS = 2;

    private ClockHostWriter() {}

    /** What one command did. {@code rows} is the provider row count, or -1 when not applicable. */
    public record Result(int code, int rows, String detail) {
        public static Result of(int code) { return new Result(code, -1, ""); }
        public boolean ok() { return code == OK; }
    }

    /**
     * Stops the ring through the alert service. {@code dismiss} closes the alarm, {@code snooze}
     * repeats it later, which is how the clock's own two buttons differ.
     */
    public static Result stop(Context context, ClockHostProfile profile, String eventId, int alarmId,
            boolean dismiss, Object schedule) {
        if (context == null || profile == null || !profile.canStop()) return Result.of(UNAVAILABLE);
        try {
            Intent intent = new Intent();
            intent.setClassName(context.getPackageName(), profile.serviceClass.getName());
            intent.setAction(ACTION_STOP);
            intent.setData(profile.scheduleUri(alarmId));
            intent.putExtra(EXTRA_DISMISSED, dismiss);
            if (eventId != null) intent.putExtra(EXTRA_BRIDGE_EVENT, eventId);
            if (!dismiss && schedule instanceof Parcelable value) {
                try {
                    intent.putExtra(EXTRA_SCHEDULE, value);
                } catch (RuntimeException ignored) {
                    // A schedule that is not parcelable still lets the service stop the ring.
                }
            }
            context.startForegroundService(intent);
            return Result.of(OK);
        } catch (RuntimeException failure) {
            return new Result(UNAVAILABLE, -1, failure.getClass().getSimpleName());
        }
    }

    /**
     * Reads the switch value back so a write that reported rows can be told apart from a switch
     * that actually went grey. Returns -1 when the value cannot be read.
     */
    public static int readEnabled(Context context, ClockHostProfile profile, int alarmId) {
        if (context == null || profile == null || !profile.canDisable()) return -1;
        try (android.database.Cursor rows = context.getContentResolver().query(
                profile.alarmUri(alarmId), new String[]{"enabled"}, null, null, null)) {
            if (rows == null || !rows.moveToFirst()) return -1;
            return rows.getInt(0);
        } catch (RuntimeException unreadable) {
            return -1;
        }
    }

    /** The whole row that drives the list switch, so a wrong target is visible instead of assumed. */
    public static String describeAlarm(Context context, ClockHostProfile profile, int alarmId) {
        if (context == null || profile == null || !profile.canDisable()) return "row=unavailable";
        String[] wanted = {"_id", "enabled", "alarm_state", "daysofweek", "deleteAfterUse",
                "alarm_mills", "alarm_hour", "alarm_minute", "alarmtime"};
        try (android.database.Cursor rows = context.getContentResolver().query(
                profile.alarmUri(alarmId), null, null, null, null)) {
            if (rows == null || !rows.moveToFirst()) return "row=absent";
            StringBuilder text = new StringBuilder("row=present");
            for (String name : wanted) {
                int column = rows.getColumnIndex(name);
                if (column < 0) continue;
                text.append(' ').append(name).append('=');
                try {
                    text.append(rows.getInt(column));
                } catch (RuntimeException unreadable) {
                    text.append('?');
                }
            }
            return text.toString();
        } catch (RuntimeException unreadable) {
            return "row=unreadable " + unreadable.getClass().getSimpleName();
        }
    }

    /** The alarm row a schedule belongs to, or -1 when the schedule cannot be read. */
    public static int readAlarmId(Context context, ClockHostProfile profile, int scheduleId) {
        if (context == null || profile == null || !profile.canDisable()) return -1;
        try (android.database.Cursor rows = context.getContentResolver().query(
                profile.scheduleUri(scheduleId), new String[]{"alarm_id"}, null, null, null)) {
            if (rows == null || !rows.moveToFirst()) return -1;
            return rows.getInt(0);
        } catch (RuntimeException unreadable) {
            return -1;
        }
    }

    /**
     * Closes the next-time switch of one alarm: the row keeps its time, the switch goes off, and the
     * alarm does not ring tomorrow. The update is verified by its row count. Only {@code enabled} is
     * written: the close-once columns belong to the clock's own countdown and zeroing them while an
     * alarm sounds leaves a pending "next ring" the clock shows as one minute away.
     */
    public static Result disable(Context context, ClockHostProfile profile, int alarmId) {
        if (context == null || profile == null || !profile.canDisable()) return Result.of(UNAVAILABLE);
        ContentValues values = new ContentValues();
        values.put("enabled", 0);
        try {
            int rows = context.getContentResolver().update(profile.alarmUri(alarmId), values, null, null);
            return new Result(rows > 0 ? OK : MISS, rows, "");
        } catch (RuntimeException failure) {
            return new Result(UNAVAILABLE, -1, failure.getClass().getSimpleName());
        }
    }

    /**
     * Turns the ringing schedule off. The list draws its switch from the schedule row, while the
     * alarm row is what the alarm itself reads, so both need the same answer.
     */
    public static Result disableSchedule(Context context, ClockHostProfile profile, int scheduleId) {
        if (context == null || profile == null || !profile.canDisable() || scheduleId <= 0) {
            return Result.of(UNAVAILABLE);
        }
        ContentValues values = new ContentValues();
        values.put("enabled", 0);
        try {
            int rows = context.getContentResolver()
                    .update(profile.scheduleUri(scheduleId), values, null, null);
            return new Result(rows > 0 ? OK : MISS, rows, "");
        } catch (RuntimeException failure) {
            return new Result(UNAVAILABLE, -1, failure.getClass().getSimpleName());
        }
    }

    /**
     * Makes the clock keep the row it is about to close. An alarm that says delete-after-use loses
     * its row when it is dismissed, which is right for its own button and wrong here: the band
     * closes the ring, the switch goes grey, and the alarm is still there for tomorrow.
     */
    public static Result keep(Context context, ClockHostProfile profile, int alarmId) {
        if (context == null || profile == null || !profile.canDisable() || alarmId <= 0) {
            return Result.of(UNAVAILABLE);
        }
        ContentValues values = new ContentValues();
        values.put("deleteAfterUse", 0);
        try {
            int rows = context.getContentResolver().update(profile.alarmUri(alarmId), values, null, null);
            return new Result(rows > 0 ? OK : MISS, rows, "");
        } catch (RuntimeException failure) {
            return new Result(UNAVAILABLE, -1, failure.getClass().getSimpleName());
        }
    }
}
