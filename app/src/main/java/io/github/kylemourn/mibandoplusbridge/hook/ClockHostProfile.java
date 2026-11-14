// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.kylemourn.mibandoplusbridge.hook;

import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.net.Uri;

import java.lang.reflect.Method;

import io.github.kylemourn.mibandoplusbridge.hook.xposed.HookRuntime;

/**
 * What the running clock app actually offers, resolved from the app itself instead of from a log
 * line or from one hard-coded ColorOS package.
 *
 * <p>The previous implementation found its hooks by searching the apk for a string that the clock
 * happened to log, and reached the alarm rows through {@code com.coloros.alarmclock.alarmclock} and
 * the private {@code alarms.db}. Both break on a different clock build. Here the authority is the
 * first candidate that the package manager resolves <em>and</em> that this package owns, and the
 * classes are the alert service and the klaxon this process loaded. A capability that cannot be
 * proven stays false, so the caller stops instead of guessing.</p>
 *
 * <p>Nothing is written to disk: the probe runs once per clock process, costs a few lookups, and
 * keeps its answer in memory only. The clock's data directory stays untouched.</p>
 */
public final class ClockHostProfile {
    public static final String PACKAGE_ONEPLUS = "com.oneplus.deskclock";
    public static final String PACKAGE_COLOROS = "com.coloros.alarmclock";

    /** The alert service both clock packages ship. */
    static final String ALERT_SERVICE = "com.oplus.alarmclock.alert.AlarmService";
    /** The klaxon both clock packages ship. */
    static final String ALARM_KLAXON = "com.oplus.alarmclock.alert.AlarmKlaxon";

    /**
     * Authorities the clock has used. OxygenOS Clock is handled first because it ships its own
     * package; a ColorOS clock is tried next, and the AOSP name is the last fallback.
     */
    private static final String[] AUTHORITIES = {
            "com.oneplus.deskclock.alarmclock",
            "com.coloros.alarmclock.alarmclock",
            "com.android.deskclock",
    };

    private static volatile ClockHostProfile cached;

    public final String packageName;
    /** Authority owned by this package, or null when the provider is not reachable. */
    public final String authority;
    public final Class<?> serviceClass;
    /** {@code onStartCommand(Intent, int, int)} of the alert service, or null. */
    public final Method stopMethod;
    public final Class<?> klaxonClass;
    /** The klaxon method that receives the schedule object, or null. */
    public final Method ringMethod;

    private ClockHostProfile(String packageName, String authority, Class<?> serviceClass, Method stopMethod,
            Class<?> klaxonClass, Method ringMethod) {
        this.packageName = packageName;
        this.authority = authority;
        this.serviceClass = serviceClass;
        this.stopMethod = stopMethod;
        this.klaxonClass = klaxonClass;
        this.ringMethod = ringMethod;
    }

    public static boolean isClock(String pkg) {
        return PACKAGE_ONEPLUS.equals(pkg) || PACKAGE_COLOROS.equals(pkg);
    }

    /**
     * What the module process can see of the phone clock, using only the package manager: which
     * clock package is installed and whether one of its alarm authorities resolves. The clock's
     * classes are loaded only inside the clock process, so this is what the setup screen reads.
     */
    public record Presence(String packageName, String authority) {
        public boolean installed() {
            return packageName != null;
        }

        public boolean disableCapable() {
            return authority != null;
        }
    }

    public static Presence presence(Context context) {
        if (context == null) return new Presence(null, null);
        for (String candidate : new String[]{PACKAGE_ONEPLUS, PACKAGE_COLOROS}) {
            try {
                context.getPackageManager().getPackageInfo(candidate, 0);
            } catch (PackageManager.NameNotFoundException | RuntimeException absent) {
                continue;
            }
            return new Presence(candidate, authority(context, candidate));
        }
        return new Presence(null, null);
    }

    /** The ring is observable, so the band can be told an alarm started. */
    public boolean canRing() {
        return ringMethod != null && klaxonClass != null;
    }

    /** The alert service can be reached, so the band can stop a ring. */
    public boolean canStop() {
        return stopMethod != null && serviceClass != null;
    }

    /** Snoozing goes through the same alert service as stopping. */
    public boolean canSnooze() {
        return canStop();
    }

    /** The alarm rows are reachable, so the next-time switch can be closed. */
    public boolean canDisable() {
        return authority != null;
    }

    /** The schedule object can be read, so a one-shot alarm is told apart from a repeating one. */
    public boolean canReadRepeat() {
        return canRing();
    }

    public Uri scheduleUri(int scheduleId) {
        return ContentUris.withAppendedId(Uri.parse("content://" + authority + "/schedules"), scheduleId);
    }

    public Uri alarmUri(int alarmId) {
        return ContentUris.withAppendedId(Uri.parse("content://" + authority + "/alarm"), alarmId);
    }

    /** Resolves the profile once per clock process and reports the answer in one log line. */
    public static ClockHostProfile probe(Context context, ClassLoader loader) {
        String pkg = context == null ? "" : context.getPackageName();
        ClockHostProfile known = cached;
        if (known != null && known.packageName.equals(pkg)) return known;
        ClockHostProfile made = resolve(context, pkg, loader);
        cached = made;
        HookRuntime.log(made.describe());
        return made;
    }

    private static ClockHostProfile resolve(Context context, String pkg, ClassLoader loader) {
        String authority = authority(context, pkg);
        Class<?> service = load(loader, ALERT_SERVICE);
        Class<?> klaxon = load(loader, ALARM_KLAXON);
        return new ClockHostProfile(pkg, authority, service, stopMethod(service),
                klaxon, ringMethod(klaxon));
    }

    private static String authority(Context context, String pkg) {
        if (context == null || pkg == null || pkg.isEmpty()) return null;
        for (String candidate : AUTHORITIES) {
            try {
                ProviderInfo info = context.getPackageManager().resolveContentProvider(candidate, 0);
                if (info != null && pkg.equals(info.packageName)) return candidate;
            } catch (RuntimeException ignored) {
                // A provider the app cannot see is treated as absent.
            }
        }
        return null;
    }

    private static Class<?> load(ClassLoader loader, String name) {
        try {
            return Class.forName(name, false, loader);
        } catch (ClassNotFoundException | LinkageError missing) {
            return null;
        }
    }

    private static Method stopMethod(Class<?> service) {
        if (service == null) return null;
        try {
            return service.getDeclaredMethod("onStartCommand", Intent.class, int.class, int.class);
        } catch (NoSuchMethodException missing) {
            return null;
        }
    }

    /**
     * The klaxon method that receives the schedule. A one-argument method whose type is a schedule
     * is the alert entry; otherwise the widest {@code start*} method is used, which is what the
     * previous fallback also picked.
     */
    private static Method ringMethod(Class<?> klaxon) {
        if (klaxon == null) return null;
        Method widest = null;
        int widestCount = -1;
        for (Class<?> current = klaxon; current != null && current != Object.class;
                current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                String name = method.getName();
                if (!name.startsWith("start")) continue;
                int count = method.getParameterCount();
                if (count == 1 && method.getParameterTypes()[0].getName().contains("Schedule")) {
                    return method;
                }
                if (count > widestCount) {
                    widest = method;
                    widestCount = count;
                }
            }
        }
        return widest;
    }

    /** One line for the session log: what was found, and what therefore cannot be done. */
    public String describe() {
        if (!canRing() && !canStop()) {
            return "CLOCK_HOST_UNRESOLVED pkg=" + packageName + " authority=" + authority;
        }
        return "CLOCK_HOST_RESOLVED pkg=" + packageName
                + " authority=" + (authority == null ? "none" : authority)
                + " ring=" + (canRing() ? 1 : 0)
                + " stop=" + (canStop() ? 1 : 0)
                + " disable=" + (canDisable() ? 1 : 0)
                + " service=" + (serviceClass == null ? "none" : serviceClass.getSimpleName());
    }
}
