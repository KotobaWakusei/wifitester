package me.paranoid.wifikill;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

/**
 * Minimal dependency-free line chart for a rolling RSSI series.
 *
 * <p>The vertical scale is fixed to the practical RSSI window (-30…-95 dBm)
 * so the curve does not jump around between samples, with reference lines at
 * the common quality thresholds. Thread safety: {@link #submit} may be called
 * from any thread, drawing happens on the UI thread.</p>
 */
public final class SignalChartView extends View {

    private static final int MAX_SAMPLES = 120;
    private static final int MIN_DBM = -95;
    private static final int MAX_DBM = -30;

    private final Deque<Integer> samples = new ArrayDeque<>();
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint goodLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint threshold = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int lineColor = Color.parseColor("#4C8DFF");
    private int goodColor = Color.parseColor("#79E0B0");
    private int gridColor = Color.parseColor("#22888888");
    private int textColor = Color.WHITE;

    public SignalChartView(Context context) {
        this(context, null);
    }

    public SignalChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        grid.setStrokeWidth(dp(0.5f));
        threshold.setStrokeWidth(dp(0.75f));
        threshold.setColor(Color.parseColor("#33FF5555"));
        line.setStrokeWidth(dp(2f));
        goodLine.setStrokeWidth(dp(2f));
        line.setStyle(Paint.Style.STROKE);
        goodLine.setStyle(Paint.Style.STROKE);
        fill.setStyle(Paint.Style.FILL);
        label.setColor(textColor);
        label.setTextSize(dp(11f));
    }

    /** Applies the theme colours; call after the theme is resolved. */
    public void applyColors(int accent, int onSurface) {
        lineColor = accent;
        goodColor = Color.parseColor("#79E0B0");
        gridColor = Color.argb(40, Color.red(onSurface), Color.green(onSurface), Color.blue(onSurface));
        textColor = onSurface;
        label.setColor(textColor);
        grid.setColor(gridColor);
        invalidate();
    }

    /** Adds one RSSI sample and redraws. */
    public void submit(int rssi) {
        synchronized (samples) {
            samples.addLast(rssi);
            while (samples.size() > MAX_SAMPLES) {
                samples.pollFirst();
            }
        }
        postInvalidateOnAnimation();
    }

    public void clear() {
        synchronized (samples) {
            samples.clear();
        }
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float width = getWidth();
        float height = getHeight();
        canvas.drawColor(Color.TRANSPARENT);

        // Grid: one line per 10 dBm inside the window.
        for (int dbm = MAX_DBM; dbm >= MIN_DBM; dbm -= 10) {
            float y = yFor(dbm, height);
            canvas.drawLine(0, y, width, y, grid);
        }
        // Quality thresholds: -60 (good) and -75 (weak).
        canvas.drawLine(0, yFor(-60, height), width, yFor(-60, height), threshold);
        canvas.drawLine(0, yFor(-75, height), width, yFor(-75, height), threshold);

        Integer[] series;
        synchronized (samples) {
            series = samples.toArray(new Integer[0]);
        }
        if (series.length == 0) {
            canvas.drawText(getContext().getString(R.string.signal_chart_waiting),
                    dp(8), dp(16), label);
            return;
        }
        float step = series.length > 1 ? width / (MAX_SAMPLES - 1f) : width;
        float firstX = width - (series.length - 1) * step;

        Path path = new Path();
        Float previousY = null;
        for (int i = 0; i < series.length; i++) {
            float x = firstX + i * step;
            float y = yFor(series[i], height);
            if (i == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
            previousY = y;
        }

        // Fill under the curve for a bit of visual weight.
        fill.setColor(lineColor);
        fill.setAlpha(28);
        Path area = new Path(path);
        area.lineTo(width, height);
        area.lineTo(firstX, height);
        area.close();
        canvas.drawPath(area, fill);

        Paint paint = line;
        if (series.length > 0 && series[series.length - 1] >= -60) {
            paint = goodLine;
            paint.setColor(goodColor);
        } else {
            paint.setColor(lineColor);
        }
        canvas.drawPath(path, paint);

        // Latest value, top-right, away from the curve.
        int latest = series[series.length - 1];
        String text = latest + " dBm";
        float textWidth = label.measureText(text);
        canvas.drawText(text, width - textWidth - dp(8), dp(14), label);
    }

    private float yFor(int dbm, float height) {
        int clamped = Math.max(MIN_DBM, Math.min(MAX_DBM, dbm));
        float fraction = (MAX_DBM - clamped) / (float) (MAX_DBM - MIN_DBM);
        return dp(6) + fraction * (height - dp(12));
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
