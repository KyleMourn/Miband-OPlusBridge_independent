// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.kylemourn.mibandoplusbridge.integration;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Messenger;

import io.github.kylemourn.mibandoplusbridge.BuildConfig;
import io.github.kylemourn.mibandoplusbridge.data.SessionLog;
import io.github.kylemourn.mibandoplusbridge.hook.ClockHostProfile;

/**
 * Receives the clock hook's events and command channel without a ContentProvider.
 *
 * <p>The clock is recognised by the packages it may run under, not by a signature, because an app
 * update rotates the certificate while the package name stays.</p>
 */
public final class ClockRelayReceiver extends BroadcastReceiver {
    public static final String ACTION = BuildConfig.APPLICATION_ID + ".CLOCK_RELAY";

    @Override public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null || !ACTION.equals(intent.getAction())) return;
        if (!fromClock(context)) return;
        String method = intent.getStringExtra("method");
        SessionLog.line(context, "CLOCK_RELAY_IN method=" + method + " from=" + sender(context));
        if ("channel".equals(method)) {
            Messenger channel = intent.getParcelableExtra("channel", Messenger.class);
            ClockRelay.channel(context, channel, intent.getBooleanExtra("ring", false));
            return;
        }
        if ("event".equals(method)) {
            ClockEvents.Event event = ClockEvents.decode(intent.getStringExtra("event"));
            if (event == null) {
                SessionLog.line(context, "CLOCK_EVENT_INVALID");
                return;
            }
            ClockRelay.onHostEvent(context, event);
            return;
        }
        if ("result".equals(method)) {
            ClockRelay.onResult(context, intent.getStringExtra("event"),
                    intent.getIntExtra("action", -1), intent.getIntExtra("code", -1),
                    intent.getIntExtra("rows", -1));
            return;
        }
        if ("note".equals(method)) {
            SessionLog.line(context, "CLOCK_NOTE " + intent.getStringExtra("text"));
            return;
        }
        SessionLog.line(context, "CLOCK_RELAY method=" + method);
        if ("status".equals(method)) {
            ClockRelay.status(context, intent.getStringExtra("hook"));
        }
    }

    /** The package a broadcast actually came from, for the log rather than for a decision. */
    private String sender(Context context) {
        if (Build.VERSION.SDK_INT < 34) return "?";
        try {
            int uid = getSentFromUid();
            String[] names = context.getPackageManager().getPackagesForUid(uid);
            return names == null || names.length == 0 ? String.valueOf(uid) : names[0];
        } catch (RuntimeException unknown) {
            return "?";
        }
    }

    private boolean fromClock(Context context) {
        if (Build.VERSION.SDK_INT < 34) return true;
        try {
            int uid = getSentFromUid();
            if (uid < 0) return true;
            String[] names = context.getPackageManager().getPackagesForUid(uid);
            if (names == null) return false;
            for (String name : names) {
                if (ClockHostProfile.isClock(name)) return true;
            }
            return false;
        } catch (RuntimeException unknown) {
            return true;
        }
    }
}
