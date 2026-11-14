// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.kylemourn.mibandoplusbridge.integration;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;

import io.github.kylemourn.mibandoplusbridge.hook.ClockHostProfile;

/**
 * Legacy clock to bridge channel. Current builds report through {@link ClockRelayReceiver}; this
 * provider stays so an already running clock process can finish reporting until it is restarted.
 */
public final class PhoneAlarmProvider extends ContentProvider {
    /** Band dismissed (1) or snoozed (2). Runs the same arbitrated path as the live channel. */
    public static void offer(Context context, int op, int id) {
        if (context == null) return;
        ClockRelay.onBandAction(context, op, id);
    }

    @Override public boolean onCreate() { return true; }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (!clockCaller()) return null;
        Context context = getContext();
        if (context == null) return null;
        if ("status".equals(method)) {
            ClockRelay.status(context, extras == null ? "" : extras.getString("hook", ""));
        } else {
            if (extras == null) return null;
            int op = extras.getInt("op", -1);
            int id = extras.getInt("id", -1);
            String label = extras.getString("label");
            long identity = Binder.clearCallingIdentity();
            try {
                switch (op) {
                    case ClockEvents.KIND_DISMISS, ClockEvents.KIND_SNOOZE ->
                            ClockRelay.onBandAction(context, op, id);
                    case ClockEvents.KIND_RING -> ClockRelay.onFallbackRing(context, label);
                    default -> { return null; }
                }
            } catch (IllegalArgumentException rejected) {
                return null;
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
        Bundle result = new Bundle();
        result.putString("status", "QUEUED");
        return result;
    }

    private boolean clockCaller() {
        Context context = getContext();
        if (context == null) return false;
        String[] packages = context.getPackageManager().getPackagesForUid(Binder.getCallingUid());
        if (packages == null) return false;
        for (String name : packages) {
            if (ClockHostProfile.isClock(name)) return true;
        }
        return false;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        throw new SecurityException("PHONE_ALARM_IPC_ONLY");
    }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new SecurityException("PHONE_ALARM_IPC_ONLY"); }
    @Override public int delete(Uri uri, String selection, String[] args) {
        throw new SecurityException("PHONE_ALARM_IPC_ONLY");
    }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new SecurityException("PHONE_ALARM_IPC_ONLY");
    }
}
