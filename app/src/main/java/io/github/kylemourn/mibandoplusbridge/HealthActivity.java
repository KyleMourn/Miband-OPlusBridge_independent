// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.kylemourn.mibandoplusbridge;

import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import io.github.kylemourn.mibandoplusbridge.data.BandStateRepository;
import io.github.kylemourn.mibandoplusbridge.data.BindingStore;
import io.github.kylemourn.mibandoplusbridge.data.HealthRecord;
import io.github.kylemourn.mibandoplusbridge.data.HealthRecordStore;
import io.github.kylemourn.mibandoplusbridge.integration.HealthQueueProvider;
import io.github.kylemourn.mibandoplusbridge.service.BandLiveService;
import io.github.kylemourn.mibandoplusbridge.ui.BridgeScreen;
import io.github.kylemourn.mibandoplusbridge.ui.TrendChart;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** Local band history that does not require OPPO Health to display. */
public final class HealthActivity extends AppCompatActivity {
    private static final int DAYS = 7;
    private static final ExecutorService READER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "health-screen");
        thread.setDaemon(true);
        return thread;
    });

    private final Handler main = new Handler(Looper.getMainLooper());
    private BridgeScreen screen;
    private TrendChart stepsChart;
    private TrendChart sleepChart;
    private TrendChart heartChart;
    private TextView summary;
    private TextView recent;
    private TextView status;
    private int generation;
    /** Replays record their rows asynchronously; repaint as soon as the provider announces them. */
    private final ContentObserver recordsObserver = new ContentObserver(main) {
        @Override
        public void onChange(boolean selfChange) {
            reload();
        }
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        screen = BridgeScreen.attach(this, "健康数据", true);
        build();
        getContentResolver().registerContentObserver(HealthQueueProvider.RECORDS_URI, true, recordsObserver);
        reload();
    }

    @Override
    protected void onDestroy() {
        generation++;
        getContentResolver().unregisterContentObserver(recordsObserver);
        super.onDestroy();
    }

    private void build() {
        screen.caption("手环同步的历史保存在本机，数据全部留在本机。");
        LinearLayout overview = screen.section("今日概览");
        summary = screen.bodyText(overview, "正在读取…");
        LinearLayout stepsCard = screen.section("步数 · 最近 7 天");
        stepsChart = new TrendChart(this);
        stepsCard.addView(stepsChart, new LinearLayout.LayoutParams(-1, BridgeScreen.dp(this, 150)));
        LinearLayout sleepCard = screen.section("睡眠 · 最近 7 天");
        sleepChart = new TrendChart(this);
        sleepCard.addView(sleepChart, new LinearLayout.LayoutParams(-1, BridgeScreen.dp(this, 150)));
        LinearLayout heartCard = screen.section("心率 · 最近 7 天日均");
        heartChart = new TrendChart(this);
        heartCard.addView(heartChart, new LinearLayout.LayoutParams(-1, BridgeScreen.dp(this, 150)));
        LinearLayout recentCard = screen.section("最近记录");
        recent = screen.bodyText(recentCard, "正在读取…");
        recent.setTextSize(14);
        LinearLayout exportCard = screen.section("导出");
        screen.bodyText(exportCard, "把手环最近的步数、心率、睡眠、血氧和压力导出为 CSV，可发送到任意应用或保存到文件。");
        screen.outlined(exportCard, "导出 CSV · 最近 30 天", this::exportCsv);
        status = screen.caption("");
        screen.filled("立即同步手环", this::requestSync);
        screen.outlined("刷新本页", this::reload);
        screen.caption("同步会从手环读取今天和最近几天的历史，通常需要一两分钟；完成后点「刷新本页」即可看到新记录。");
    }

    private void requestSync() {
        BandLiveService.start(this);
        BandLiveService.requestSync(this);
        Toast.makeText(this, "已请求同步手环历史", Toast.LENGTH_SHORT).show();
        status.setText("同步已开始，一两分钟后点「刷新本页」。");
    }

    private void exportCsv() {
        status.setText("正在生成 CSV…");
        final Context context = getApplicationContext();
        READER.execute(() -> {
            File file = null;
            String message;
            try {
                file = writeCsv(context);
                message = "已生成 " + file.getName();
            } catch (Exception failure) {
                message = "导出中断：" + describe(failure);
            }
            final File exported = file;
            final String result = message;
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                status.setText(result);
                if (exported != null) shareCsv(exported);
            });
        });
    }

    private static File writeCsv(Context context) throws Exception {
        JSONObject binding = new BindingStore(context).read();
        if (binding == null) throw new IllegalStateException("BINDING_REQUIRED");
        String device = BandStateRepository.deviceId(binding);
        ZoneId zone = ZoneId.systemDefault();
        long end = System.currentTimeMillis() + 1;
        long start = LocalDate.now(zone).minusDays(29).atStartOfDay(zone).toInstant().toEpochMilli();
        List<String> kinds = List.of("steps_day", "steps_interval", "heart_rate",
                "sleep_interval", "sleep_stage", "spo2", "stress");
        List<HealthRecord> records;
        try (HealthRecordStore store = new HealthRecordStore(context)) {
            records = store.localHistory(device, kinds, start, end, 4000);
        }
        File directory = new File(context.getCacheDir(), "debug");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IllegalStateException("CACHE_UNAVAILABLE");
        }
        String name = "health-"
                + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now(zone))
                + ".csv";
        File file = new File(directory, name);
        DateTimeFormatter format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(zone);
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(file), StandardCharsets.UTF_8)) {
            writer.write('\ufeff');
            writer.write("开始时间,结束时间,类型,数值,单位,阶段,完整\n");
            for (HealthRecord record : records) {
                long value = valueOf(record);
                if ("sleep_interval".equals(record.kind) || "sleep_stage".equals(record.kind)) {
                    value = sleepMillis(record);
                }
                // Idle minutes carry no information and would drown the actual activities.
                if ("steps_interval".equals(record.kind) && value == 0) continue;
                writer.write(csv(format.format(Instant.ofEpochMilli(record.startMs))));
                writer.write(',');
                writer.write(csv(format.format(Instant.ofEpochMilli(record.endMs))));
                writer.write(',');
                writer.write(csv(record.kind));
                writer.write(',');
                writer.write(Long.toString(value));
                writer.write(',');
                writer.write(csv(unitOf(record.kind)));
                writer.write(',');
                writer.write(csv(record.stage == null ? "" : String.valueOf(record.stage)));
                writer.write(',');
                writer.write(record.complete ? "1" : "0");
                writer.write('\n');
            }
        }
        return file;
    }

    private void shareCsv(File file) {
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".debuglog", file);
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("text/csv")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send, "导出手环数据"));
    }

    private static String csv(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static String unitOf(String kind) {
        if (kind.startsWith("steps_")) return "步";
        if ("heart_rate".equals(kind)) return "次/分";
        if ("spo2".equals(kind)) return "%";
        if ("stress".equals(kind)) return "分";
        if (kind.startsWith("sleep_")) return "毫秒";
        return "";
    }

    private void reload() {
        final int token = ++generation;
        final Context context = getApplicationContext();
        status.setText("正在读取本机记录…");
        READER.execute(() -> {
            Report report = null;
            String error = null;
            try {
                report = read(context);
            } catch (Exception failure) {
                error = describe(failure);
            }
            final Report loaded = report;
            final String failure = error;
            main.post(() -> {
                if (token != generation || isFinishing() || isDestroyed()) return;
                if (loaded == null) {
                    status.setText(failure == null ? "读取中断，请重试" : failure);
                    return;
                }
                render(loaded);
            });
        });
    }

    private static Report read(Context context) throws Exception {
        JSONObject binding = new BindingStore(context).read();
        if (binding == null) throw new IllegalStateException("BINDING_REQUIRED");
        String device = BandStateRepository.deviceId(binding);
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        long end = System.currentTimeMillis() + 1;
        long start = today.minusDays(DAYS - 1L).atStartOfDay(zone).toInstant().toEpochMilli();
        List<HealthRecord> steps;
        List<HealthRecord> heart;
        List<HealthRecord> sleep;
        List<HealthRecord> spo2;
        List<HealthRecord> stress;
        String account;
        try (HealthRecordStore store = new HealthRecordStore(context)) {
            steps = store.localHistory(device, List.of("steps_day", "steps_interval"), start, end, 4000);
            heart = store.localHistory(device, "heart_rate", start, end, 4000);
            sleep = store.localHistory(device, List.of("sleep_interval"), start - 12L * 3_600_000L, end, 2000);
            spo2 = store.localHistory(device, "spo2", start, end, 1000);
            stress = store.localHistory(device, "stress", start, end, 1000);
            account = store.confirmedAccountHash();
        }
        Report report = new Report();
        report.note = account == null
                ? "记录保存在本机。"
                : "记录保存在本机。";

        long[] stepDay = new long[DAYS];
        long[] stepInterval = new long[DAYS];
        boolean[] hasStepDay = new boolean[DAYS];
        for (HealthRecord record : steps) {
            int index = dayIndex(record.startMs, today, zone);
            long value = valueOf(record);
            if (index < 0 || value < 0) continue;
            if ("steps_day".equals(record.kind)) {
                stepDay[index] = Math.max(stepDay[index], value);
                hasStepDay[index] = true;
            } else {
                stepInterval[index] += value;
            }
        }
        report.steps = new float[DAYS];
        for (int i = 0; i < DAYS; i++) report.steps[i] = hasStepDay[i] ? stepDay[i] : stepInterval[i];

        long[] heartSum = new long[DAYS];
        int[] heartCount = new int[DAYS];
        long latestHeart = 0;
        long latestHeartAt = 0;
        for (HealthRecord record : heart) {
            int index = dayIndex(record.startMs, today, zone);
            long value = valueOf(record);
            if (index < 0 || value < 40 || value > 220) continue;
            heartSum[index] += value;
            heartCount[index]++;
            if (record.startMs > latestHeartAt) {
                latestHeartAt = record.startMs;
                latestHeart = value;
            }
        }
        long todayHeartSum = heartSum[DAYS - 1];
        int todayHeartCount = heartCount[DAYS - 1];
        report.heartAvg = new float[DAYS];
        for (int i = 0; i < DAYS; i++) {
            report.heartAvg[i] = heartCount[i] == 0 ? 0f : (float) heartSum[i] / heartCount[i];
        }

        float[] sleepHours = new float[DAYS];
        float lastSleepHours = 0;
        long lastSleepEnd = 0;
        for (HealthRecord record : sleep) {
            if (!record.complete || record.endMs <= record.startMs) continue;
            long millis = sleepMillis(record);
            float hours = millis / 3_600_000f;
            if (hours <= 0 || hours > 24) continue;
            int index = dayIndex(record.endMs, today, zone);
            if (index >= 0) sleepHours[index] += hours;
            if (record.endMs > lastSleepEnd) {
                lastSleepEnd = record.endMs;
                lastSleepHours = hours;
            }
        }
        report.sleep = sleepHours;
        report.todaySteps = Math.round(report.steps[DAYS - 1]);
        report.todayHeartAverage = todayHeartCount == 0 ? 0 : todayHeartSum / todayHeartCount;
        report.latestHeart = latestHeart;
        report.latestHeartAt = latestHeartAt;
        report.lastSleepHours = lastSleepHours;
        report.spo2 = latestValue(spo2);
        report.stress = latestValue(stress);

        ArrayList<HealthRecord> all = new ArrayList<>();
        all.addAll(steps);
        all.addAll(heart);
        all.addAll(sleep);
        all.addAll(spo2);
        all.addAll(stress);
        all.sort((left, right) -> Long.compare(right.startMs, left.startMs));
        StringBuilder text = new StringBuilder();
        int shown = 0;
        for (HealthRecord record : all) {
            if (shown >= 25) break;
            if (!"steps_day".equals(record.kind) && valueOf(record) <= 0 && record.kind.startsWith("steps_")) continue;
            if (shown > 0) text.append('\n');
            text.append(formatRecord(record, zone));
            shown++;
        }
        report.records = shown == 0
                ? "记录从下一次同步开始出现：同步一次手环后，历史会显示在这里。"
                : text.toString();
        report.labels = new String[DAYS];
        for (int i = 0; i < DAYS; i++) report.labels[i] = weekday(today.minusDays(DAYS - 1L - i));
        return report;
    }

    private void render(Report report) {
        StringBuilder text = new StringBuilder();
        if (report.todaySteps > 0) text.append("今日 ").append(report.todaySteps).append(" 步");
        if (report.latestHeart > 0) {
            if (text.length() > 0) text.append('\n');
            text.append("最近心率 ").append(report.latestHeart).append(" 次/分");
            if (report.latestHeartAt > 0) {
                text.append("（").append(DateTimeFormatter.ofPattern("MM-dd HH:mm")
                        .withZone(ZoneId.systemDefault())
                        .format(Instant.ofEpochMilli(report.latestHeartAt))).append("）");
            }
            if (report.todayHeartAverage > 0) text.append("，今日平均 ").append(report.todayHeartAverage);
        }
        if (report.lastSleepHours > 0) {
            if (text.length() > 0) text.append('\n');
            text.append("上次睡眠 ").append(formatHours((long) (report.lastSleepHours * 3_600_000f)));
        }
        if (report.spo2 > 0 || report.stress > 0) {
            if (text.length() > 0) text.append('\n');
            if (report.spo2 > 0) text.append("血氧 ").append(report.spo2).append("%");
            if (report.spo2 > 0 && report.stress > 0) text.append(" · ");
            if (report.stress > 0) text.append("压力 ").append(report.stress);
        }
        summary.setText(text.length() == 0 ? "今天的记录从同步开始：先同步一次手环。" : text.toString());
        stepsChart.show(report.steps, report.labels, false);
        sleepChart.show(report.sleep, report.labels, true);
        heartChart.show(report.heartAvg, report.labels, false);
        recent.setText(report.records);
        status.setText(report.note);
    }

    private static long valueOf(HealthRecord record) {
        return record.value == null ? 0L : record.value.longValue();
    }

    private static long sleepMillis(HealthRecord record) {
        long value = record.value == null ? 0L : record.value.longValue();
        return value > 0 ? value : record.endMs - record.startMs;
    }

    private static long latestValue(List<HealthRecord> records) {
        long latest = 0;
        long at = 0;
        for (HealthRecord record : records) {
            if (record.startMs < at) continue;
            at = record.startMs;
            latest = valueOf(record);
        }
        return latest;
    }

    private static int dayIndex(long timeMs, LocalDate today, ZoneId zone) {
        LocalDate day = Instant.ofEpochMilli(timeMs).atZone(zone).toLocalDate();
        long offset = ChronoUnit.DAYS.between(today.minusDays(DAYS - 1L), day);
        return offset < 0 || offset >= DAYS ? -1 : (int) offset;
    }

    private static String weekday(LocalDate day) {
        return switch (day.getDayOfWeek()) {
            case MONDAY -> "周一";
            case TUESDAY -> "周二";
            case WEDNESDAY -> "周三";
            case THURSDAY -> "周四";
            case FRIDAY -> "周五";
            case SATURDAY -> "周六";
            case SUNDAY -> "周日";
        };
    }

    private static String formatHours(long millis) {
        return String.format(Locale.ROOT, "%.1f 小时", millis / 3_600_000f);
    }

    private static String formatRecord(HealthRecord record, ZoneId zone) {
        DateTimeFormatter format = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(zone);
        String time = format.format(Instant.ofEpochMilli(record.startMs));
        long value = valueOf(record);
        if (record.kind.startsWith("sleep_")) {
            return time + "  " + formatHours(sleepMillis(record));
        }
        String line = switch (record.kind) {
            case "steps_day" -> "步数日汇总 " + Math.max(0, value) + " 步";
            case "steps_interval" -> "步数 " + Math.max(0, value) + " 步";
            case "heart_rate" -> "心率 " + value + " 次/分";
            case "spo2" -> "血氧 " + value + "%";
            case "stress" -> "压力 " + value;
            default -> record.kind + " " + value;
        };
        return time + "  " + line;
    }

    private static String describe(Exception failure) {
        String message = failure.getMessage();
        if ("BINDING_REQUIRED".equals(message)) return "先在首页完成「导入绑定」，记录随即显示在这里。";
        return "读取中断：" + (message == null ? failure.getClass().getSimpleName() : message);
    }

    private static final class Report {
        float[] steps = new float[DAYS];
        float[] sleep = new float[DAYS];
        float[] heartAvg = new float[DAYS];
        String[] labels = new String[DAYS];
        long todaySteps;
        long todayHeartAverage;
        long latestHeart;
        long latestHeartAt;
        float lastSleepHours;
        long spo2;
        long stress;
        String records = "";
        String note = "";
    }
}
