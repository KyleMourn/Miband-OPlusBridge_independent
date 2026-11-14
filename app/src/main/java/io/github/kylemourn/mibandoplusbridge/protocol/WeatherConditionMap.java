/* Copyright (C) 2017-2024 Andreas Shimokawa, Yoran Vulker.
 * Adapted Xiaomi condition codes from Gadgetbridge commit
 * 75f923904f8504b03fabdee0987fd1c269a92278; see compat/upstream.json.
 */
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.kylemourn.mibandoplusbridge.protocol;

/** Current OHealth WeatherCodeNewEnum.weatherCode -> Xiaomi condition; -1 means unsupported. */
public final class WeatherConditionMap {
    private WeatherConditionMap() {}

    public static int fromOHealth(int code) {
        // OHealth 6.9.37 WeatherCodeNewEnum; Xiaomi values from the pinned
        // Gadgetbridge XiaomiWeatherConditions table and observed Band 11 frames.
        // An unsupported category stays unmapped. Clear skies is only the clear category.
        return switch (code) {
            case 1, 6, 7 -> 7;
            case 2, 3, 4, 5 -> 3;
            case 8 -> 8;
            case 9, 10, 11 -> 9;
            case 12, 13 -> 10;
            case 14, 15, 16 -> 12;
            case 17 -> 19;
            case 18, 19, 20 -> 5;
            case 22, 23, 24, 25 -> 4;
            case 26 -> 33;
            case 27, 28 -> 13;
            case 29 -> 14;
            case 30 -> 26;
            case 31 -> 15;
            case 32 -> 27;
            case 33 -> 16;
            case 34 -> 28;
            case 35, 36 -> 17;
            case 37 -> 6;
            case 38, 39, 40, 50, 51, 52, 53 -> 18;
            case 41, 42, 43, 44 -> 32;
            case 45, 46, 47 -> 29;
            case 48 -> 20;
            case 49 -> 31;
            case 54, 55, 68 -> 0;
            case 56, 57, 58, 69 -> 1;
            case 59 -> 2;
            case 60, 61, 62, 63, 64, 65, 66, 67 -> 30;
            default -> -1;
        };
    }

    /** A system-weather snapshot already carries Xiaomi condition codes. */
    public static int normalizeSystemCode(int code) {
        return code >= 0 && code <= 33 ? code : 0;
    }

    /**
     * The OnePlus weather database stores a readable Chinese description next to each code
     * (1..39 use the national names, 55..59 are cloudiness variants). The description is the
     * only value both the current row and the forecasts always carry, so map from it.
     */
    public static int fromDescription(String description) {
        if (description == null) return -1;
        String text = description.trim();
        if (text.isEmpty()) return -1;
        if (text.contains("雷") && text.contains("雨")) return text.contains("冰雹") ? 5 : 4;
        if (text.contains("雨夹雪") || text.contains("雨雪")) return 6;
        if (text.contains("冻雨")) return 19;
        if (text.contains("大雨到暴雨")) return 23;
        if (text.contains("暴雨到大暴雨")) return 24;
        if (text.contains("大暴雨到特大暴雨")) return 25;
        if (text.contains("中到大雨")) return 22;
        if (text.contains("小到中雨")) return 21;
        if (text.contains("特大暴雨")) return 12;
        if (text.contains("大暴雨")) return 11;
        if (text.contains("暴雨")) return 10;
        if (text.contains("大雨")) return 9;
        if (text.contains("中雨")) return 8;
        if (text.contains("阵雨")) return 3;
        if (text.contains("毛毛雨") || text.contains("细雨") || text.contains("小雨")) return 7;
        if (text.contains("大到暴雪")) return 28;
        if (text.contains("中到大雪")) return 27;
        if (text.contains("小到中雪")) return 26;
        if (text.contains("阵雪")) return 13;
        if (text.contains("暴雪")) return 17;
        if (text.contains("大雪")) return 16;
        if (text.contains("中雪")) return 15;
        if (text.contains("小雪")) return 14;
        if (text.contains("雪")) return 14;
        if (text.contains("沙尘暴")) return text.contains("强") ? 31 : 20;
        if (text.contains("浮尘") || text.contains("扬沙") || text.contains("沙尘")) return 29;
        if (text.contains("霾") || text.contains("雾")) return 18;
        if (text.contains("大风") || text.contains("强风") || text.contains("飓风") || text.contains("龙卷")) return 30;
        if (text.contains("阴")) return 2;
        if (text.contains("多云") || text.contains("少云") || text.contains("云")) return 1;
        if (text.contains("晴")) return 0;
        return -1;
    }
}
