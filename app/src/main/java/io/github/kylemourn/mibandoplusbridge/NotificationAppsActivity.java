// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.kylemourn.mibandoplusbridge;

import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import io.github.kylemourn.mibandoplusbridge.notify.BandNotificationListener;
import io.github.kylemourn.mibandoplusbridge.ui.BridgeScreen;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Picks which apps may push notifications to the band. An empty selection means every app. */
public final class NotificationAppsActivity extends AppCompatActivity {
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<AppRow> rows = new ArrayList<>();
    private SharedPreferences settings;
    private LinearLayout listCard;
    private TextView summary;

    private static final class AppRow {
        final LinearLayout row;
        final String label;
        final String packageName;

        AppRow(LinearLayout row, String label, String packageName) {
            this.row = row;
            this.label = label;
            this.packageName = packageName;
        }
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        BridgeScreen screen = BridgeScreen.attach(this, "通知转发应用", true);
        settings = getSharedPreferences(BandNotificationListener.SETTINGS, MODE_PRIVATE);
        screen.bodyText("默认为全部应用转发通知；勾选后只转发勾选的应用，改动即时生效。");
        LinearLayout head = screen.card();
        summary = screen.bodyText(head, "");
        LinearLayout.LayoutParams summaryParams = (LinearLayout.LayoutParams) summary.getLayoutParams();
        summaryParams.bottomMargin = BridgeScreen.dp(this, 8);
        screen.outlined(head, "恢复为全部应用", this::clear);
        LinearLayout searchCard = screen.card();
        EditText search = new EditText(this);
        search.setHint("搜索应用名称或包名");
        search.setSingleLine(true);
        search.setPadding(0, 0, 0, 0);
        search.setBackground(null);
        searchCard.addView(search);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                filter(text == null ? "" : text.toString());
            }
            @Override public void afterTextChanged(Editable text) { }
        });
        listCard = screen.card();
        loader.execute(this::load);
    }

    private void load() {
        PackageManager packages = getPackageManager();
        List<String[]> apps = new ArrayList<>();
        for (ApplicationInfo info : packages.getInstalledApplications(0)) {
            if (getPackageName().equals(info.packageName)) continue;
            boolean launchable = packages.getLaunchIntentForPackage(info.packageName) != null;
            if (!launchable && (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
            String label = String.valueOf(info.loadLabel(packages)).trim();
            if (label.isEmpty()) label = info.packageName;
            apps.add(new String[]{label, info.packageName});
        }
        apps.sort((left, right) -> left[0].compareToIgnoreCase(right[0]));
        main.post(() -> render(apps));
    }

    private void render(List<String[]> apps) {
        if (isFinishing() || isDestroyed()) return;
        Set<String> selected = new HashSet<>(settings.getStringSet("packages", Set.of()));
        for (String[] app : apps) {
            String label = app[0];
            String packageName = app[1];
            CheckBox box = new CheckBox(this);
            box.setChecked(selected.contains(packageName));
            box.setPadding(0, 0, BridgeScreen.dp(this, 8), 0);
            LinearLayout text = new LinearLayout(this);
            text.setOrientation(LinearLayout.VERTICAL);
            TextView title = new TextView(this);
            title.setText(label);
            title.setTextSize(16);
            TextView code = new TextView(this);
            code.setText(packageName);
            code.setTextSize(12);
            code.setAlpha(0.7f);
            text.addView(title);
            text.addView(code);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, BridgeScreen.dp(this, 6), 0, BridgeScreen.dp(this, 6));
            row.addView(box);
            row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
            row.setOnClickListener(view -> box.toggle());
            box.setOnCheckedChangeListener((button, checked) -> save(packageName, checked));
            listCard.addView(row);
            rows.add(new AppRow(row, label, packageName));
        }
        updateSummary(selected.size());
    }

    private void filter(String query) {
        String needle = query.trim().toLowerCase(Locale.ROOT);
        for (AppRow app : rows) {
            boolean visible = needle.isEmpty()
                    || app.label.toLowerCase(Locale.ROOT).contains(needle)
                    || app.packageName.toLowerCase(Locale.ROOT).contains(needle);
            app.row.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    private void save(String packageName, boolean checked) {
        Set<String> selected = new HashSet<>(settings.getStringSet("packages", Set.of()));
        if (checked) selected.add(packageName);
        else selected.remove(packageName);
        settings.edit().putStringSet("packages", selected).apply();
        updateSummary(selected.size());
    }

    private void clear() {
        settings.edit().putStringSet("packages", new HashSet<>()).apply();
        for (int index = 0; index < listCard.getChildCount(); index++) {
            LinearLayout row = (LinearLayout) listCard.getChildAt(index);
            CheckBox box = (CheckBox) row.getChildAt(0);
            box.setOnCheckedChangeListener(null);
            box.setChecked(false);
            String packageName = packageOf(row);
            box.setOnCheckedChangeListener((button, checked) -> save(packageName, checked));
        }
        updateSummary(0);
    }

    private String packageOf(LinearLayout row) {
        LinearLayout text = (LinearLayout) row.getChildAt(1);
        TextView code = (TextView) text.getChildAt(1);
        return String.valueOf(code.getText());
    }

    private void updateSummary(int count) {
        if (summary == null) return;
        summary.setText(count == 0 ? "当前：转发全部应用" : "当前：只转发 " + count + " 个应用");
    }

    @Override protected void onDestroy() {
        loader.shutdownNow();
        super.onDestroy();
    }
}
