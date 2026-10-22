package me.paranoid.wifikill;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;

/**
 * Building blocks for the three primary tabs.
 *
 * <p>The helper is a thin, tab oriented facade over {@link UiKit}: the colour
 * constants are theme attributes, so every screen follows the light or the
 * dark scheme automatically.</p>
 */
final class LightKit {

    // Colour roles - theme attributes resolved through Theme.color().
    static final int BG = UiKit.BG;
    static final int PANEL = UiKit.PANEL;
    static final int INK = UiKit.INK;
    static final int MUTED = UiKit.MUTED;
    static final int ACCENT = UiKit.ACCENT;
    static final int ACCENT_SOFT = UiKit.ACCENT_SOFT;
    static final int LINE = UiKit.LINE;
    static final int SUCCESS = UiKit.SUCCESS;
    static final int SUCCESS_BG = UiKit.SUCCESS_BG;
    static final int SUCCESS_TEXT = UiKit.SUCCESS_TEXT;
    static final int DANGER = UiKit.DANGER;
    static final int DANGER_BG = UiKit.DANGER_BG;
    static final int DANGER_TEXT = UiKit.DANGER_TEXT;
    static final int WARNING_BG = UiKit.WARNING_BG;
    static final int WARNING_TEXT = UiKit.WARNING_TEXT;
    static final int SURFACE_SOFT = UiKit.SURFACE_SOFT;

    private LightKit() {
    }

    /** Page shell: a scrollable, padded column. */
    static ScrollView page(Activity activity) {
        return UiKit.createPage(activity);
    }

    static LinearLayout root(Activity activity, ScrollView scroll) {
        return (LinearLayout) scroll.getChildAt(0);
    }

    /** Adds the standard eyebrow / title / subtitle header block. */
    static void header(LinearLayout root, Activity activity, String eyebrow, String title, String subtitle) {
        root.addView(text(activity, eyebrow.toUpperCase(java.util.Locale.ROOT), 11, ACCENT, true));
        root.addView(text(activity, title, 28, INK, true), top(activity, 5));
        if (subtitle != null && !subtitle.isEmpty()) {
            root.addView(text(activity, subtitle, 14, MUTED, false), top(activity, 5));
        }
    }

    static LinearLayout card(Activity activity) {
        return UiKit.card(activity);
    }

    static TextView text(Activity activity, String value, int size, int color, boolean bold) {
        return UiKit.text(activity, value, size, color, bold);
    }

    /** A filled primary button. */
    static MaterialButton primary(Activity activity, String label) {
        return UiKit.primary(activity, label);
    }

    /** A tonal button that carries the accent colour. */
    static MaterialButton secondary(Activity activity, String label) {
        return UiKit.accentContainer(activity, label);
    }

    /** A neutral button for low emphasis actions. */
    static MaterialButton neutral(Activity activity, String label) {
        MaterialButton button = UiKit.outlined(activity, label);
        button.setTextColor(UiKit.color(activity, INK, Color.BLACK));
        return button;
    }

    /** A destructive button. */
    static MaterialButton danger(Activity activity, String label) {
        return UiKit.danger(activity, label);
    }

    /** A tappable row with a title, a detail line and a trailing chevron. */
    static LinearLayout actionRow(Activity activity, String title, String detail, Runnable action) {
        return UiKit.actionRow(activity, title, detail, action);
    }

    /** A left-aligned title with an optional trailing widget. */
    static LinearLayout heading(Activity activity, String title) {
        return UiKit.heading(activity, title);
    }

    /** Hairline separator used between list rows. */
    static View divider(Activity activity) {
        return UiKit.divider(activity);
    }

    /** Small rounded badge, e.g. the security label of an access point. */
    static TextView badge(Activity activity, String label, int background, int foreground) {
        return UiKit.badge(activity, label, background, foreground);
    }

    /** Horizontal signal-strength meter built from filled/empty segments. */
    static LinearLayout signalMeter(Activity activity, int level, int maxLevel) {
        return UiKit.signalMeter(activity, level, maxLevel);
    }

    static GradientDrawable round(int color, int radius) {
        return UiKit.round(color, radius);
    }

    /** Rounded background built from a theme attribute. */
    static GradientDrawable round(android.content.Context context, int attr, int radius) {
        return UiKit.round(UiKit.color(context, attr, Color.GRAY), radius);
    }

    static LinearLayout.LayoutParams top(Activity activity, int value) {
        return UiKit.top(value, activity);
    }

    static LinearLayout.LayoutParams bottom(Activity activity, int value) {
        return UiKit.bottom(value, activity);
    }

    static int dp(Activity activity, float value) {
        return UiKit.dp(activity, value);
    }
}
