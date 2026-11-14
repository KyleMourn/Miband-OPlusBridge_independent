// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.kylemourn.mibandoplusbridge.integration;

import android.content.Context;

import io.github.kylemourn.mibandoplusbridge.data.LocalPrefs;

/**
 * Whether the phone alarm is mirrored to the band at all.
 *
 * <p>Off means the notification mirror keeps working: the clock's own notification still reaches
 * the band, but the module stops sending its own alert and stops relaying band commands back into
 * the clock. That is the fallback position when a clock build cannot be read, and the switch a user
 * reaches for when they want the phone to stay silent on the wrist.</p>
 */
public final class AlarmLinkage {
    private static final String STORE = "alarm-linkage";
    private static final String KEY_ENABLED = "enabled";

    private AlarmLinkage() {}

    public static boolean enabled(Context context) {
        return context == null || LocalPrefs.open(context, STORE).getBoolean(KEY_ENABLED, true);
    }

    public static void setEnabled(Context context, boolean enabled) {
        if (context == null) return;
        LocalPrefs.open(context, STORE).edit().putBoolean(KEY_ENABLED, enabled).commit();
    }
}
