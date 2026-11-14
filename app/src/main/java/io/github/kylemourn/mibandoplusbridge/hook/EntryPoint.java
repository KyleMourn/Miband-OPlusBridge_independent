// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.kylemourn.mibandoplusbridge.hook;

import android.app.Application;
import android.content.Context;
import androidx.annotation.NonNull;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.kylemourn.mibandoplusbridge.HostIdentity;
import io.github.kylemourn.mibandoplusbridge.hook.xposed.HookRuntime;
import io.github.kylemourn.mibandoplusbridge.hook.xposed.XC_MethodHook;
import io.github.kylemourn.mibandoplusbridge.hook.xposed.XposedHelpers;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * libxposed API 102 entry. The only Java entry, so the framework can hot reload it.
 *
 * <p>A hot reload skips the package callbacks. The old generation stops its threads and
 * listeners, then hands over each host package it saw: package name, class loader, and the
 * Application context once it exists. The new generation removes the old hooks and installs its
 * own. Pages the host already built keep what the old code drew on them until they refresh.</p>
 */
public final class EntryPoint extends XposedModule {
    private static final String CLOCK_PACKAGE = "com.coloros.alarmclock";
    /** OnePlus Clock on OxygenOS. Same alert classes as the ColorOS clock, different package. */
    private static final String CLOCK_PACKAGE_ALT = "com.oneplus.deskclock";
    private static final String WEATHER_SERVICE = "com.coloros.weather.service";
    private static final String WEATHER_APP = "net.oneplus.weather";
    private static final String STATE_PROCESS = "process";
    private static final String STATE_TARGETS = "targets";
    private static final String STATE_CARRIED = "carried";
    private static final AtomicBoolean installed = new AtomicBoolean();
    private static final AtomicBoolean weatherInstalled = new AtomicBoolean();

    private final List<Target> targets = new CopyOnWriteArrayList<>();
    private String processName = "";

    /** One host package in this process. Fields hold host objects only. */
    private static final class Target {
        final String packageName;
        final ClassLoader loader;
        volatile Context context;

        Target(String packageName, ClassLoader loader, Context context) {
            this.packageName = packageName;
            this.loader = loader;
            this.context = context;
        }
    }

    @Override public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
        HookRuntime.attach(this);
        processName = param.getProcessName();
    }

    @Override public void onPackageReady(@NonNull PackageReadyParam param) {
        String pkg = param.getPackageName();
        if (!handled(pkg)) return;
        Target target = new Target(pkg, param.getClassLoader(), null);
        targets.add(target);
        dispatch(target);
    }

    @Override public boolean onHotReloading(@NonNull HotReloadingParam param) {
        HookRuntime.close();
        ArrayList<Object[]> saved = new ArrayList<>();
        for (Target target : targets) {
            saved.add(new Object[] {target.packageName, target.loader, target.context});
        }
        HashMap<String, Object> state = new HashMap<>();
        state.put(STATE_PROCESS, processName);
        state.put(STATE_TARGETS, saved);
        state.put(STATE_CARRIED, new HashMap<>(HookRuntime.carriedState()));
        param.setSavedInstanceState(state);
        probe("HOT_RELOAD_LEAVING targets=" + saved.size());
        return true;
    }

    @Override public void onHotReloaded(@NonNull HotReloadedParam param) {
        HookRuntime.attach(this);
        processName = param.getProcessName();
        int old = param.getOldHookHandles().size();
        param.getOldHookHandles().forEach(XposedInterface.HookHandle::unhook);
        if (!(param.getSavedInstanceState() instanceof Map<?, ?> state)) {
            probe("HOT_RELOAD_WITHOUT_STATE unhooked=" + old);
            return;
        }
        if (state.get(STATE_CARRIED) instanceof Map<?, ?> carried) HookRuntime.restoreCarried(carried);
        if (state.get(STATE_TARGETS) instanceof List<?> saved) {
            for (Object entry : saved) {
                if (!(entry instanceof Object[] values) || values.length != 3
                        || !(values[0] instanceof String pkg) || !(values[1] instanceof ClassLoader loader)) continue;
                Target target = new Target(pkg, loader, values[2] instanceof Context context ? context : null);
                targets.add(target);
                try {
                    dispatch(target);
                } catch (Throwable failure) {
                    probe("HOT_RELOAD_DISPATCH_FAILED pkg=" + pkg + " " + failure.getClass().getSimpleName());
                }
            }
        }
        probe("HOT_RELOADED process=" + processName + " unhooked=" + old + " targets=" + targets.size());
    }

    private static boolean clock(String pkg) {
        return CLOCK_PACKAGE.equals(pkg) || CLOCK_PACKAGE_ALT.equals(pkg);
    }

    private static boolean handled(String pkg) {
        return HostIdentity.MI_PACKAGE.equals(pkg) || HostIdentity.DEVICES_PACKAGE.equals(pkg)
                || clock(pkg)
                || WEATHER_SERVICE.equals(pkg) || WEATHER_APP.equals(pkg);
    }

    /**
     * Installs hooks for one host package. On first load the Application is still missing, so most
     * hooks wait for {@code Application.attach}. After a hot reload the saved context is used directly.
     */
    private void dispatch(Target load) {
        String process = processName;
        if (HostIdentity.MI_PACKAGE.equals(load.packageName)
                || HostIdentity.DEVICES_PACKAGE.equals(load.packageName)
                || clock(load.packageName)) {
            probe("PACKAGE_LOADED pkg=" + load.packageName + " process=" + process);
        }
        if (clock(load.packageName)) {
            whenAttached(load, app -> {
                try {
                    ClockAlarmBridge.install(app, load.loader);
                } catch (Throwable failure) {
                    probe("CLOCK_HOOK_SKIPPED "
                            + failure.getClass().getSimpleName());
                }
            });
            return;
        }
        if (WEATHER_SERVICE.equals(load.packageName) || WEATHER_APP.equals(load.packageName)) {
            probe("WEATHER_PACKAGE_LOADED pkg=" + load.packageName + " process=" + process);
            whenAttached(load, app -> {
                if (!weatherInstalled.compareAndSet(false, true)) return;
                try {
                    WeatherHook.install(app);
                    probe("WEATHER_HOOK_READY process=" + process);
                } catch (Throwable failure) {
                    probe("WEATHER_HOOK_SKIPPED " + failure.getClass().getSimpleName()
                            + (failure.getMessage() == null ? "" : " " + failure.getMessage()));
                    io.github.kylemourn.mibandoplusbridge.data.SessionLog.line(app,
                            "WEATHER_HOOK_SKIPPED " + failure.getClass().getSimpleName()
                                    + (failure.getMessage() == null ? "" : " " + failure.getMessage()));
                }
            });
            return;
        }
        whenAttached(load, context -> {
            if (!installed.compareAndSet(false, true)) return;
            probe("ATTACH process=" + process);
            if (HostIdentity.DEVICES_PACKAGE.equals(load.packageName)) {
                try {
                    MyDevicesHook.install(context, load.loader);
                    probe("DEVICE_CARD_HOOK_INSTALLED");
                } catch (Throwable skipped) {
                    probe("DEVICE_CARD_HOOK_SKIPPED "
                            + skipped.getClass().getSimpleName()
                            + (skipped.getMessage() == null ? "" : " " + skipped.getMessage()));
                }
                return;
            }
            if (!HostIdentity.installed(context, HostIdentity.MI_PACKAGE)) {
                probe("MI_PACKAGE_ABSENT");
                return;
            }
            try {
                MiFitnessImportHook.install(context, load.loader);
                probe("OplusBandBridge: IMPORT_HOOK_INSTALLED");
            } catch (Throwable incompatible) {
                probe("OplusBandBridge: HOST_VERSION_UNSUPPORTED");
            }
            try {
                TransportProbeHook.install(context, load.loader);
            } catch (Throwable incompatible) {
                probe("TRANSPORT_PROBE_UNAVAILABLE");
            }
            try {
                ProtocolCaptureHook.install(context, load.loader);
            } catch (Throwable incompatible) {
                probe("PROTOCOL_CAPTURE_UNAVAILABLE");
            }
        });
    }

    /** Runs now if the host Application is known, else after {@code Application.attach}. */
    private static void whenAttached(Target load, java.util.function.Consumer<Context> action) {
        Context known = load.context;
        if (known != null) {
            action.accept(known);
            return;
        }
        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                Context context = (Context) param.args[0];
                if (context == null) return;
                Context app = context.getApplicationContext() == null ? context : context.getApplicationContext();
                if (load.context == null) load.context = app;
                action.accept(app);
            }
        });
    }

    /**
     * LSPosed only relays the framework log into its own log on this device. A plain
     * android.util.Log call from this module stayed off that page. Write to both so a
     * missing line means the hook was not injected, rather than a log on the invisible channel.
     */
    private static void probe(String line) {
        HookRuntime.log(line);
    }
}
