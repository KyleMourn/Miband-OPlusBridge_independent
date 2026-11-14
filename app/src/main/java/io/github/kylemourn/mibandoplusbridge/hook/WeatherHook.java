// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.kylemourn.mibandoplusbridge.hook;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import io.github.kylemourn.mibandoplusbridge.BuildConfig;
import io.github.kylemourn.mibandoplusbridge.data.SessionLog;
import io.github.kylemourn.mibandoplusbridge.protocol.WeatherConditionMap;
import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Reads the OnePlus weather database inside the weather process and hands a snapshot to the
 * bridge. The bridge cannot open that file itself: it belongs to the weather uid and provider
 * lookups are filtered by package visibility. The snapshot travels as an explicit broadcast.
 */
public final class WeatherHook {
    private static final String BRIDGE = BuildConfig.APPLICATION_ID;
    private static final String RELAY_RECEIVER =
            "io.github.kylemourn.mibandoplusbridge.integration.WeatherRelayReceiver";
    private static final String RELAY_ACTION = BuildConfig.APPLICATION_ID + ".WEATHER_RELAY";
    public static final String REFRESH_ACTION = BuildConfig.APPLICATION_ID + ".WEATHER_REFRESH";
    private static final String SERVICE_PACKAGE = "com.coloros.weather.service";
    private static final long CYCLE_MS = 10 * 60_000L;
    private static final AtomicBoolean installed = new AtomicBoolean();
    private static volatile Context appContext;
    private static volatile Handler handler;
    private static volatile String lastPayload = "";

    private WeatherHook() {}

    public static void install(Context context) {
        if (context == null || !installed.compareAndSet(false, true)) return;
        SessionLog.line(context, "WEATHER_HOOK_ENTER pkg=" + context.getPackageName()
                + " process=" + Application.getProcessName());
        Context app = context.getApplicationContext() == null ? context : context.getApplicationContext();
        appContext = app;
        HandlerThread thread = new HandlerThread("oplus-weather-hook");
        thread.start();
        handler = new Handler(thread.getLooper());
        BroadcastReceiver refresh = new BroadcastReceiver() {
            @Override public void onReceive(Context receiver, Intent intent) {
                Handler current = handler;
                if (current != null) current.post(() -> publish(appContext, true));
            }
        };
        IntentFilter filter = new IntentFilter(REFRESH_ACTION);
        app.registerReceiver(refresh, filter, Context.RECEIVER_EXPORTED);
        handler.postDelayed(cycle, 3_000L);
        SessionLog.line(app, "WEATHER_HOOK_INSTALLED process=" + Application.getProcessName()
                + " pkg=" + app.getPackageName());
    }

    private static final Runnable cycle = new Runnable() {
        @Override public void run() {
            try {
                publish(appContext, false);
            } finally {
                Handler current = handler;
                if (current != null) current.postDelayed(this, CYCLE_MS);
            }
        }
    };

    private static void publish(Context context, boolean force) {
        if (context == null) return;
        try {
            JSONObject snapshot = read(context);
            if (snapshot == null) {
                SessionLog.line(context, "WEATHER_HOOK_NO_DB");
                return;
            }
            String payload = snapshot.toString();
            String stable = payload.replaceAll("\"publishedAtMs\":\\d+", "");
            if (!force && stable.equals(lastPayload)) return;
            Intent intent = new Intent(RELAY_ACTION);
            intent.setClassName(BRIDGE, RELAY_RECEIVER);
            intent.putExtra("snapshot", payload);
            context.sendBroadcast(intent);
            lastPayload = stable;
            SessionLog.line(context, "WEATHER_HOOK_SENT code=" + snapshot.optInt("conditionCode")
                    + " city=" + snapshot.optString("cityName"));
        } catch (Throwable failure) {
            SessionLog.line(context, "WEATHER_HOOK_FAILED " + failure.getClass().getSimpleName()
                    + (failure.getMessage() == null ? "" : ": " + failure.getMessage()));
        }
    }

    private static JSONObject read(Context context) throws Exception {
        SQLiteDatabase sql = open(context);
        if (sql == null) return null;
        try {
            if (hasTable(sql, "observe_weather")) return readApp(sql);
            if (hasTable(sql, "oppo_weather_info")) return readService(sql);
            return null;
        } finally {
            try { sql.close(); } catch (RuntimeException ignored) {}
        }
    }

    /** net.oneplus.weather database: attend_city / observe_weather / daily_forecast_weather. */
    private static JSONObject readApp(SQLiteDatabase sql) throws Exception {
        Cursor city = null;
        Cursor observe = null;
        Cursor air = null;
        Cursor daily = null;
        Cursor hourly = null;
        try {
            city = sql.rawQuery("SELECT _id, city_name, location_key, timezone_id FROM attend_city"
                    + " ORDER BY is_resident_city DESC, is_location_city DESC, sort ASC LIMIT 1", null);
            if (!city.moveToFirst()) return null;
            long cityRow = city.getLong(0);
            String cityName = value(city, 1);
            String locationKey = value(city, 2);
            String timezone = value(city, 3);
            if (cityName == null || cityName.isBlank()) return null;
            if (locationKey == null || !locationKey.matches("[A-Za-z0-9:_-]{1,64}")) {
                locationKey = "oplus:" + cityRow;
            }
            if (timezone == null || timezone.isBlank()) timezone = "Asia/Shanghai";

            observe = sql.rawQuery("SELECT temp, humidity, weather_code, weather_desc, pressure,"
                    + " uvIndex, wind_degree, wind_power FROM observe_weather WHERE city_id=?"
                    + " ORDER BY _id DESC LIMIT 1", new String[] {Long.toString(cityRow)});
            if (!observe.moveToFirst()) return null;
            Double temperature = number(observe, 0);
            if (temperature == null) return null;

            air = sql.rawQuery("SELECT \"index\" FROM air_quality WHERE city_id=? ORDER BY _id DESC LIMIT 1",
                    new String[] {Long.toString(cityRow)});
            Double aqi = air.moveToFirst() ? number(air, 0) : null;

            JSONObject root = new JSONObject();
            root.put("source", "system");
            root.put("locationKey", locationKey);
            root.put("cityName", cityName);
            root.put("locationName", cityName);
            root.put("timezone", timezone);
            root.put("unit", "c");
            root.put("publishedAtMs", System.currentTimeMillis());
            root.put("conditionCode", condition((int) number(observe, 2), value(observe, 3)));
            root.put("temperature", temperature);
            putInt(root, "humidity", number(observe, 1));
            putInt(root, "uvIndex", number(observe, 5));
            putInt(root, "windDegree", number(observe, 6));
            putInt(root, "windPower", number(observe, 7));
            Double pressure = number(observe, 4);
            if (pressure != null) root.put("pressure", pressure);
            putInt(root, "aqi", aqi);

            daily = sql.rawQuery("SELECT time, day_code, night_code, day_weather_desc,"
                    + " night_weather_desc, temp_max, temp_min, sunrise_time, sunset_time"
                    + " FROM daily_forecast_weather WHERE city_id=? ORDER BY time ASC LIMIT 7",
                    new String[] {Long.toString(cityRow)});
            JSONArray days = new JSONArray();
            while (daily.moveToNext()) {
                long time = daily.getLong(0);
                Double max = number(daily, 5);
                Double min = number(daily, 6);
                if (time <= 0 || max == null || min == null) continue;
                JSONObject day = new JSONObject();
                day.put("timeMs", time);
                day.put("dayCode", condition((int) number(daily, 1), value(daily, 3)));
                day.put("nightCode", condition((int) number(daily, 2), value(daily, 4)));
                day.put("maxTemp", max);
                day.put("minTemp", min);
                Long sunrise = millis(number(daily, 7));
                Long sunset = millis(number(daily, 8));
                if (sunrise != null && sunset != null && sunrise < sunset) {
                    day.put("sunriseMs", sunrise);
                    day.put("sunsetMs", sunset);
                }
                days.put(day);
            }
            if (days.length() == 0) return null;
            root.put("daily", days);

            long now = System.currentTimeMillis();
            hourly = sql.rawQuery("SELECT time, weather_code, temp, weather_desc"
                    + " FROM hourly_forecast_weather WHERE city_id=? AND time>=?"
                    + " ORDER BY time ASC LIMIT 23",
                    new String[] {Long.toString(cityRow), Long.toString(now - 3_600_000L)});
            JSONArray hours = new JSONArray();
            while (hourly.moveToNext()) {
                long time = hourly.getLong(0);
                Double temp = number(hourly, 2);
                if (time <= 0 || temp == null) continue;
                JSONObject hour = new JSONObject();
                hour.put("timeMs", time);
                hour.put("conditionCode", condition((int) number(hourly, 1), value(hourly, 3)));
                hour.put("temperature", temp);
                hours.put(hour);
            }
            if (hours.length() == 0) return null;
            root.put("hourly", hours);
            return root;
        } finally {
            close(hourly);
            close(daily);
            close(air);
            close(observe);
            close(city);
        }
    }

    /** com.coloros.weather.service database: attent_city / oppo_weather_info. */
    private static JSONObject readService(SQLiteDatabase sql) throws Exception {
        Cursor city = null;
        Cursor weather = null;
        Cursor hours = null;
        try {
            city = sql.rawQuery("SELECT _id, city_name, city_code, timezone_name FROM attent_city"
                    + " ORDER BY current DESC, sort ASC, _id ASC LIMIT 1", null);
            if (!city.moveToFirst()) return null;
            long cityRow = city.getLong(0);
            String cityName = value(city, 1);
            String cityCode = value(city, 2);
            String timezone = value(city, 3);
            if (cityName == null || cityName.isBlank()) return null;
            if (cityCode == null || !cityCode.matches("[A-Za-z0-9:_-]{1,64}")) {
                cityCode = "oplus:" + cityRow;
            }
            if (timezone == null || timezone.isBlank()) timezone = "Asia/Shanghai";

            weather = sql.rawQuery("SELECT weather_index, date, current_weather, day_weather,"
                    + " night_weather, current_temp, day_temp, night_temp, current_humidity,"
                    + " current_uv_index, current_wind_power, current_wind_degrees, pressure,"
                    + " avg_aqi, sunrise_time, sunset_time FROM oppo_weather_info"
                    + " WHERE city_id=? ORDER BY weather_index ASC", new String[] {Long.toString(cityRow)});
            if (!weather.moveToFirst()) return null;

            String currentText = value(weather, 2);
            if (currentText == null || currentText.isBlank()) currentText = value(weather, 3);
            Double currentTemp = number(weather, 5);
            if (currentTemp == null) currentTemp = number(weather, 6);
            if (currentTemp == null) currentTemp = number(weather, 7);
            if (currentTemp == null) return null;

            JSONObject root = new JSONObject();
            root.put("source", "system");
            root.put("locationKey", cityCode);
            root.put("cityName", cityName);
            root.put("locationName", cityName);
            root.put("timezone", timezone);
            root.put("unit", "c");
            root.put("publishedAtMs", System.currentTimeMillis());
            root.put("conditionCode", condition(-1, currentText));
            root.put("temperature", currentTemp);
            putInt(root, "humidity", number(weather, 8));
            putInt(root, "uvIndex", number(weather, 9));
            putInt(root, "windPower", number(weather, 10));
            putInt(root, "windDegree", number(weather, 11));
            Double pressure = number(weather, 12);
            if (pressure != null) root.put("pressure", pressure);
            putInt(root, "aqi", number(weather, 13));

            JSONArray daily = new JSONArray();
            do {
                long date = weather.getLong(1);
                if (date <= 0 || daily.length() >= 7) break;
                Double max = number(weather, 6);
                Double min = number(weather, 7);
                if (max == null || min == null || max < min) continue;
                JSONObject day = new JSONObject();
                day.put("timeMs", date);
                day.put("dayCode", condition(-1, value(weather, 3)));
                day.put("nightCode", condition(-1, value(weather, 4)));
                day.put("maxTemp", max);
                day.put("minTemp", min);
                Long sunrise = millisText(value(weather, 14));
                Long sunset = millisText(value(weather, 15));
                if (sunrise != null && sunset != null && sunrise < sunset) {
                    day.put("sunriseMs", sunrise);
                    day.put("sunsetMs", sunset);
                }
                daily.put(day);
            } while (weather.moveToNext());
            if (daily.length() == 0) return null;
            root.put("daily", daily);

            long now = System.currentTimeMillis();
            hours = sql.rawQuery("SELECT time, weather_desc, temp FROM hourly_forecast_weather"
                    + " WHERE city_id=? AND time>=? ORDER BY time ASC LIMIT 23",
                    new String[] {Long.toString(cityRow), Long.toString(now - 3_600_000L)});
            JSONArray hourly = new JSONArray();
            while (hours.moveToNext()) {
                long time = hours.getLong(0);
                Double temp = number(hours, 2);
                if (time <= 0 || temp == null) continue;
                JSONObject hour = new JSONObject();
                hour.put("timeMs", time);
                hour.put("conditionCode", condition(-1, value(hours, 1)));
                hour.put("temperature", temp);
                hourly.put(hour);
            }
            if (hourly.length() == 0) return null;
            root.put("hourly", hourly);
            return root;
        } finally {
            close(hours);
            close(weather);
            close(city);
        }
    }

    private static SQLiteDatabase open(Context context) {
        File own = context.getDatabasePath("weather.db");
        SQLiteDatabase database = openReadOnly(own);
        if (database != null) return database;
        int userId = Process.myUid() / 100_000;
        File service = new File("/data/user_de/" + userId + "/" + SERVICE_PACKAGE + "/databases/weather.db");
        return openReadOnly(service);
    }

    private static SQLiteDatabase openReadOnly(File file) {
        if (file == null || !file.canRead()) return null;
        try {
            return SQLiteDatabase.openDatabase(file.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private static boolean hasTable(SQLiteDatabase sql, String table) {
        Cursor cursor = null;
        try {
            cursor = sql.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
                    new String[] {table});
            return cursor.moveToFirst();
        } catch (RuntimeException unavailable) {
            return false;
        } finally {
            close(cursor);
        }
    }

    private static int condition(int code, String text) {
        int mapped = WeatherConditionMap.fromDescription(text);
        if (mapped >= 0) return mapped;
        return WeatherConditionMap.normalizeSystemCode(code);
    }

    private static String value(Cursor cursor, int column) {
        return cursor.isNull(column) ? null : cursor.getString(column);
    }

    private static double number(Cursor cursor, int column) {
        if (cursor.isNull(column)) return -1d;
        return cursor.getDouble(column);
    }

    private static Long millis(double value) {
        if (value <= 0) return null;
        long parsed = (long) value;
        return parsed > 1_000_000_000_000L ? parsed : null;
    }

    private static Long millisText(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed > 1_000_000_000_000L ? parsed : null;
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static void putInt(JSONObject root, String key, double value) throws Exception {
        if (value < 0) return;
        root.put(key, (int) Math.round(value));
    }

    private static void close(Cursor cursor) {
        if (cursor != null) try { cursor.close(); } catch (RuntimeException ignored) {}
    }
}
