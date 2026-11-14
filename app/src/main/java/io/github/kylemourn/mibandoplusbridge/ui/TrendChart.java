// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.kylemourn.mibandoplusbridge.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;
import com.google.android.material.color.MaterialColors;

/** Minimal seven-slot bar trend for local health history. No data is invented when nothing is stored. */
public final class TrendChart extends View {
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valueText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private float[] values = new float[0];
    private String[] labels = new String[0];
    private boolean hours;

    public TrendChart(Context context) {
        this(context, null);
    }

    public TrendChart(Context context, AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        bar.setStyle(Paint.Style.FILL);
        bar.setColor(MaterialColors.getColor(context, com.google.android.material.R.attr.colorPrimary, 0xFF5B8DEF));
        valueText.setTextSize(10 * density);
        valueText.setTextAlign(Paint.Align.CENTER);
        valueText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        valueText.setColor(MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface, 0xFF222222));
        labelText.setTextSize(11 * density);
        labelText.setTextAlign(Paint.Align.CENTER);
        labelText.setColor(MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF777777));
    }

    /** Values and labels must have the same length, oldest first. Zeros mean "no record". */
    public void show(float[] values, String[] labels, boolean hours) {
        this.values = values == null ? new float[0] : values;
        this.labels = labels == null ? new String[0] : labels;
        this.hours = hours;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        setMeasuredDimension(width, Math.round(150 * getResources().getDisplayMetrics().density));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float density = getResources().getDisplayMetrics().density;
        int width = getWidth();
        float top = 20 * density;
        float bottom = getHeight() - 22 * density;
        float max = 0;
        for (float value : values) max = Math.max(max, value);
        if (values.length == 0 || max <= 0) {
            canvas.drawText("同步一次手环后显示趋势", width / 2f, getHeight() / 2f, labelText);
            return;
        }
        float slot = (float) width / values.length;
        float barWidth = Math.min(slot * 0.52f, 30 * density);
        for (int i = 0; i < values.length; i++) {
            float center = slot * i + slot / 2f;
            if (values[i] > 0) {
                float height = Math.max(2 * density, (bottom - top) * (values[i] / max));
                rect.set(center - barWidth / 2f, bottom - height, center + barWidth / 2f, bottom);
                canvas.drawRoundRect(rect, 6 * density, 6 * density, bar);
                canvas.drawText(shortValue(values[i]), center, bottom - height - 4 * density, valueText);
            }
            if (i < labels.length) canvas.drawText(labels[i], center, bottom + 15 * density, labelText);
        }
    }

    private String shortValue(float value) {
        if (hours) return value >= 10 ? String.valueOf(Math.round(value)) : String.format(java.util.Locale.ROOT, "%.1f", value);
        if (value >= 10_000) return String.format(java.util.Locale.ROOT, "%.1f万", value / 10_000f);
        return Long.toString(Math.round(value));
    }
}
