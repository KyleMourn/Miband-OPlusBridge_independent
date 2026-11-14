// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.kylemourn.mibandoplusbridge.integration;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import io.github.kylemourn.mibandoplusbridge.BuildConfig;
import io.github.kylemourn.mibandoplusbridge.data.SessionLog;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Accepts a weather snapshot read from the OnePlus weather database by the module hook.
 *
 * <p>Only the weather app and its service may write it. The receiver is exported so the hook can
 * reach the bridge package without a ContentProvider lookup, which the weather process cannot
 * resolve under package visibility rules.</p>
 */
public final class WeatherRelayReceiver extends BroadcastReceiver {
    public static final String ACTION = BuildConfig.APPLICATION_ID + ".WEATHER_RELAY";
    private static final String[] SOURCES = {"com.coloros.weather.service", "net.oneplus.weather"};

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;
        if (!fromWeather(context)) {
            SessionLog.line(context, "WEATHER_RELAY_REJECTED");
            return;
        }
        String raw = intent.getStringExtra("snapshot");
        if (raw == null || raw.length() > 131_072) {
            SessionLog.line(context, "WEATHER_RELAY_INVALID size");
            return;
        }
        try {
            JSONObject snapshot = new JSONObject(raw);
            WeatherSnapshotProvider.validate(snapshot);
            SharedPreferences prefs = context.getSharedPreferences("weather-bridge", 0);
            if (!prefs.edit().putString("snapshot", snapshot.toString()).putString("request", "")
                    .putString("error", "").putLong("updatedAtMs", System.currentTimeMillis()).commit()) {
                SessionLog.line(context, "WEATHER_RELAY_STORAGE_FAILED");
                return;
            }
            context.getContentResolver().notifyChange(WeatherSnapshotProvider.UPDATED, null);
            JSONArray daily = snapshot.optJSONArray("daily");
            JSONArray hourly = snapshot.optJSONArray("hourly");
            SessionLog.line(context, "WEATHER_RELAY_STORED code=" + snapshot.optInt("conditionCode", -1)
                    + " city=" + snapshot.optString("cityName")
                    + " days=" + (daily == null ? 0 : daily.length())
                    + " hours=" + (hourly == null ? 0 : hourly.length()));
        } catch (Exception failure) {
            SessionLog.line(context, "WEATHER_RELAY_FAILED " + failure.getMessage());
        }
    }

    private boolean fromWeather(Context context) {
        int uid = Build.VERSION.SDK_INT >= 34 ? getSentFromUid() : -1;
        // Some OxygenOS builds strip the sender from explicit broadcasts; the payload is still
        // re-validated before it can replace a snapshot.
        if (uid < 0) return true;
        String[] packages = context.getPackageManager().getPackagesForUid(uid);
        if (packages == null) return false;
        for (String name : packages) {
            for (String source : SOURCES) if (source.equals(name)) return true;
        }
        return false;
    }
}
