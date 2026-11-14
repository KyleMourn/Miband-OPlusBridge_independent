// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.kylemourn.mibandoplusbridge.notify;

import android.content.Context;
import android.content.Intent;
import android.service.notification.StatusBarNotification;
import io.github.kylemourn.mibandoplusbridge.data.SessionLog;
import io.github.kylemourn.mibandoplusbridge.hook.ClockHostProfile;
import io.github.kylemourn.mibandoplusbridge.integration.AlarmLinkage;
import io.github.kylemourn.mibandoplusbridge.integration.ClockRelay;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The clock rings as a foreground notification. When the hook cannot anchor the klaxon this
 * listener still reports the ring, and replays the clock's own buttons when the band answers.
 */
public final class PhoneAlarmNotice {
    public static final String CLOCK = ClockHostProfile.PACKAGE_COLOROS;
    /** OnePlus Clock on OxygenOS keeps the coloros process data but ships as its own package. */
    public static final String CLOCK_ALT = ClockHostProfile.PACKAGE_ONEPLUS;
    public static final Set<String> CLOCKS = Set.of(CLOCK, CLOCK_ALT);
    private static final Set<String> ringing = ConcurrentHashMap.newKeySet();
    /** AlarmSnoozeService foreground id. A countdown, not a ringing alarm. */
    static final int SNOOZE_NOTIFICATION_ID = -1018;
    /** TimerService, the timer floating window, and the stopwatch service share the klaxon channel. */
    private static final int TIMER_SERVICE_ID = -1014;
    private static final int TIMER_FLOATING_ID = -1015;
    private static final int STOPWATCH_SERVICE_ID = -1016;
    private static volatile StatusBarNotification lastRing;

    private PhoneAlarmNotice() {}

    public static boolean isClock(String pkg) {
        return ClockHostProfile.isClock(pkg);
    }

    public static boolean ringing(String pkg, int flags, String channel, int id, boolean fullScreen) {
        if (!isClock(pkg) || channel == null || channel.contains("next.alarm")) return false;
        if (id == SNOOZE_NOTIFICATION_ID || runningClockTool(id, channel)) return false;
        boolean service = (flags & android.app.Notification.FLAG_FOREGROUND_SERVICE) != 0;
        // ColorOS 17 posts the klaxon on this channel. Timer and stopwatch services use it too.
        if ("clock_foreground_service_channel_id".equals(channel)) return service;
        if (id == Integer.MIN_VALUE || id == -1017) return true;
        if (!channel.contains("alarmclock")) return false;
        return fullScreen || service;
    }

    /** A running timer or stopwatch. The band has no packet for either, so the clock alarm stays on the phone. */
    private static boolean runningClockTool(int id, String channel) {
        if (id == TIMER_SERVICE_ID || id == TIMER_FLOATING_ID || id == STOPWATCH_SERVICE_ID) return true;
        return channel.contains("timer") || channel.contains("stopwatch");
    }

    public static boolean ringing(StatusBarNotification item) {
        if (item == null || item.getNotification() == null) return false;
        android.app.Notification notification = item.getNotification();
        return ringing(item.getPackageName(), notification.flags, notification.getChannelId(),
                item.getId(), notification.fullScreenIntent != null);
    }

    public static void posted(Context context, StatusBarNotification item) {
        if (!ringing(item)) return;
        ringing.add(item.getKey());
        lastRing = item;
        if (ClockRelay.hookActive() && AlarmLinkage.enabled(context)) return;
        String title = item.getNotification().extras == null ? ""
                : item.getNotification().extras.getString(android.app.Notification.EXTRA_TITLE, "");
        ClockRelay.onFallbackRing(context, title);
    }

    public static void removed(Context context, StatusBarNotification item) {
        if (item == null || !ringing.remove(item.getKey())) return;
        StatusBarNotification ring = lastRing;
        if (ring != null && item.getKey().equals(ring.getKey())) lastRing = null;
    }

    public static String lastRingKey() {
        StatusBarNotification item = lastRing;
        return item == null ? null : item.getKey();
    }

    /** The band dismissed or snoozed without a usable channel. Replays the clock's own action. */
    public static boolean bandAction(Context context, int op) {
        if (op != 1 && op != 2) return false;
        return replay(context, op) || platformAction(context, op);
    }

    /**
     * Presses the clock's own button on its ringing notification. False when the notification or its
     * button is not at hand, so the caller can pick another path instead of inventing an intent.
     */
    public static boolean replay(Context context, int op) {
        if (op != 1 && op != 2) return false;
        StatusBarNotification item = lastRing;
        SessionLog.line(context, "ALARM_BAND_ACTION_ENTER op=" + op + " ring=" + (item != null));
        if (item == null || item.getNotification() == null) return false;
        android.app.Notification.Action[] actions = item.getNotification().actions;
        if (actions == null || actions.length == 0) {
            SessionLog.line(context, "ALARM_BAND_ACTION_NO_ACTIONS");
            return false;
        }
        String wanted = op == 2 ? "稍后|snooze|延迟|暂停" : "关闭|停止|dismiss|stop";
        int picked = -1;
        for (int index = 0; index < actions.length; index++) {
            android.app.Notification.Action action = actions[index];
            if (action == null || action.title == null || action.actionIntent == null) continue;
            if (action.title.toString().matches("(?i).*(" + wanted + ").*")) {
                picked = index;
                break;
            }
        }
        if (picked < 0) picked = op == 2 && actions.length > 1 ? actions.length - 1 : 0;
        android.app.Notification.Action action = actions[picked];
        if (action == null || action.actionIntent == null) return false;
        try {
            action.actionIntent.send();
            SessionLog.line(context, "ALARM_BAND_ACTION op=" + op + " index=" + picked);
            return true;
        } catch (android.app.PendingIntent.CanceledException gone) {
            SessionLog.line(context, "ALARM_BAND_ACTION_FAILED " + gone.getClass().getSimpleName());
            return false;
        }
    }

    /** On OxygenOS the klaxon notification has no action buttons; drive the platform alarm API instead.
     *  A background activity start is only allowed when the user granted "display over other apps". */
    private static boolean platformAction(Context context, int op) {
        String action = op == 2
                ? "android.intent.action.SNOOZE_ALARM"
                : "android.intent.action.DISMISS_ALARM";
        if (!android.provider.Settings.canDrawOverlays(context)) {
            SessionLog.line(context, "ALARM_NO_OVERLAY");
        }
        try {
            Intent intent = new Intent(action);
            intent.putExtra("android.intent.extra.alarm.SEARCH_MODE", "android.next");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            SessionLog.line(context, "ALARM_API_START op=" + op);
            return true;
        } catch (RuntimeException failure) {
            SessionLog.line(context, "ALARM_API_FAILED " + failure.getClass().getSimpleName());
            return false;
        }
    }
}
