package me.paranoid.wifikill;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.AttrRes;
import androidx.annotation.ColorInt;
import androidx.annotation.DimenRes;
import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.divider.MaterialDivider;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

/**
 * Theme aware building blocks for the whole application.
 *
 * <p>Every colour is referenced through a Material theme attribute, therefore
 * the same code renders correctly with the light and the dark scheme. All
 * spacing follows the dimensions declared in {@code dimens.xml}.</p>
 */
public final class UiKit {

    // ---- colour roles -----------------------------------------------------
    // The constants are theme attributes; use Theme.color() to resolve them.

    public static final int BG = Attrs.COLOR_SURFACE;
    public static final int PANEL = Attrs.COLOR_SURFACE_CONTAINER_LOWEST;
    public static final int PANEL_TONAL = Attrs.COLOR_SURFACE_CONTAINER_LOW;
    public static final int INK = Attrs.COLOR_ON_SURFACE;
    public static final int MUTED = Attrs.COLOR_ON_SURFACE_VARIANT;
    public static final int ACCENT = Attrs.COLOR_PRIMARY;
    public static final int ACCENT_SOFT = Attrs.COLOR_PRIMARY_CONTAINER;
    public static final int LINE = Attrs.COLOR_OUTLINE_VARIANT;
    public static final int SUCCESS = R.attr.colorSuccess;
    public static final int SUCCESS_BG = R.attr.colorSuccessContainer;
    public static final int SUCCESS_TEXT = R.attr.colorOnSuccessContainer;
    public static final int DANGER = Attrs.COLOR_ERROR;
    public static final int DANGER_BG = Attrs.COLOR_ERROR_CONTAINER;
    public static final int DANGER_TEXT = Attrs.COLOR_ON_ERROR_CONTAINER;
    public static final int WARNING_BG = R.attr.colorWarningContainer;
    public static final int WARNING_TEXT = R.attr.colorOnWarningContainer;
    public static final int SURFACE_SOFT = Attrs.COLOR_SURFACE_CONTAINER;

    // Legacy names, kept because the workbenches still reference them.
    public static final int BG_DARK = Attrs.COLOR_SURFACE;
    public static final int PANEL_DARK = Attrs.COLOR_SURFACE_CONTAINER_LOW;
    public static final int ACCENT_DARK = Attrs.COLOR_PRIMARY;
    public static final int MUTED_DARK = Attrs.COLOR_ON_SURFACE_VARIANT;
    public static final int WHITE = Attrs.COLOR_ON_SURFACE;
    public static final int CARD_STROKE = Attrs.COLOR_OUTLINE_VARIANT;
    public static final int LOG_BG = R.attr.colorLogBackground;
    public static final int LOG_TEXT = R.attr.colorLogText;
    public static final int INPUT_BG = Attrs.COLOR_SURFACE_CONTAINER_HIGH;
    public static final int INPUT_TEXT = Attrs.COLOR_ON_SURFACE;
    public static final int BUTTON_PRIMARY_BG = Attrs.COLOR_PRIMARY;
    public static final int BUTTON_SECONDARY_BG = Attrs.COLOR_SECONDARY_CONTAINER;
    public static final int BUTTON_ACCENT_BG = Attrs.COLOR_PRIMARY;

    private UiKit() {
    }

    // ---- colour helpers ---------------------------------------------------

    /** Resolves a theme attribute into a concrete colour. */
    @ColorInt
    public static int color(Context context, @AttrRes int attr) {
        return Theme.color(context, attr, Color.GRAY);
    }

    @ColorInt
    public static int color(Context context, @AttrRes int attr, @ColorInt int fallback) {
        return Theme.color(context, attr, fallback);
    }

    // ---- layout containers ------------------------------------------------

    /** A scrollable page with the standard margins. */
    public static ScrollView createPage(Activity activity) {
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(color(activity, BG, Color.WHITE));
        scroll.setClipToPadding(false);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        int horizontal = dim(activity, R.dimen.page_margin_horizontal);
        int top = dim(activity, R.dimen.page_margin_top);
        int bottom = dim(activity, R.dimen.page_margin_bottom);
        root.setPadding(horizontal, top, horizontal, bottom);
        scroll.addView(root);
        return scroll;
    }

    /** A plain vertical column. */
    public static LinearLayout column(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    /** A horizontal row aligned on the vertical centre. */
    public static LinearLayout row(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    // ---- surfaces ---------------------------------------------------------

    /** A filled card that follows the Material 3 elevation and corner rules. */
    public static MaterialCardView materialCard(Context context) {
        MaterialCardView card = new MaterialCardView(context);
        card.setCardBackgroundColor(color(context, PANEL, Color.WHITE));
        card.setRadius(dim(context, R.dimen.card_radius));
        card.setCardElevation(0f);
        card.setStrokeWidth(dp(context, 1));
        card.setStrokeColor(color(context, LINE, Color.LTGRAY));
        card.setUseCompatPadding(false);
        return card;
    }

    /** A card that uses a tinted container colour. */
    public static MaterialCardView tonalCard(Context context) {
        MaterialCardView card = materialCard(context);
        card.setCardBackgroundColor(color(context, PANEL_TONAL, Color.WHITE));
        card.setStrokeWidth(0);
        return card;
    }

    /** The legacy linear card used by the existing screens. */
    public static LinearLayout card(Activity activity) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        int padding = dim(activity, R.dimen.card_padding);
        card.setPadding(padding, padding, padding, padding);
        GradientDrawable background = round(color(activity, PANEL, Color.WHITE),
                dim(activity, R.dimen.card_radius));
        background.setStroke(dp(activity, 1), color(activity, LINE, Color.LTGRAY));
        card.setBackground(background);
        card.setElevation(0f);
        return card;
    }

    // ---- text -------------------------------------------------------------

    /**
     * Creates a text view.
     *
     * @param color a theme attribute such as {@link #INK} or a literal colour
     */
    public static TextView text(Activity activity, String value, int size, int color, boolean bold) {
        TextView view = new TextView(activity);
        view.setText(value == null ? "" : value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        view.setTextColor(resolve(activity, color));
        if (bold) {
            view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
        view.setLineSpacing(dp(activity, 2), 1f);
        return view;
    }

    /**
     * Resolves either a theme attribute or keeps a literal colour.
     *
     * <p>Attribute identifiers are positive resource identifiers, literal
     * colours are opaque values and therefore negative.</p>
     */
    @ColorInt
    public static int resolve(Context context, int value) {
        if (context == null || value <= 0) {
            return value;
        }
        return Theme.color(context, value, value);
    }

    // ---- buttons ----------------------------------------------------------

    /** A filled primary action. */
    public static MaterialButton primary(Activity activity, String label) {
        MaterialButton button = baseButton(activity, label);
        button.setBackgroundTintList(tint(activity, ACCENT));
        button.setTextColor(color(activity, Attrs.COLOR_ON_PRIMARY, Color.WHITE));
        return button;
    }

    /** A tonal button, used for secondary but important actions. */
    public static MaterialButton tonal(Activity activity, String label) {
        MaterialButton button = baseButton(activity, label);
        button.setBackgroundTintList(tint(activity, Attrs.COLOR_SECONDARY_CONTAINER));
        button.setTextColor(color(activity, Attrs.COLOR_ON_SECONDARY_CONTAINER, Color.BLACK));
        return button;
    }

    /** A tonal button that uses the primary container colour. */
    public static MaterialButton accentContainer(Activity activity, String label) {
        MaterialButton button = baseButton(activity, label);
        button.setBackgroundTintList(tint(activity, ACCENT_SOFT));
        button.setTextColor(color(activity, Attrs.COLOR_ON_PRIMARY_CONTAINER, Color.BLACK));
        return button;
    }

    /** An outlined button. */
    public static MaterialButton outlined(Activity activity, String label) {
        MaterialButton button = baseButton(activity, label);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.TRANSPARENT));
        button.setTextColor(color(activity, ACCENT, Color.BLUE));
        button.setStrokeColor(android.content.res.ColorStateList.valueOf(color(activity, LINE, Color.GRAY)));
        button.setStrokeWidth(dp(activity, 1));
        return button;
    }

    /** A borderless text button. */
    public static MaterialButton textButton(Activity activity, String label) {
        MaterialButton button = baseButton(activity, label);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.TRANSPARENT));
        button.setTextColor(color(activity, ACCENT, Color.BLUE));
        return button;
    }

    /** A destructive action. */
    public static MaterialButton danger(Activity activity, String label) {
        MaterialButton button = baseButton(activity, label);
        button.setBackgroundTintList(tint(activity, Attrs.COLOR_ERROR_CONTAINER));
        button.setTextColor(color(activity, Attrs.COLOR_ON_ERROR_CONTAINER, Color.RED));
        return button;
    }

    private static MaterialButton baseButton(Activity activity, String label) {
        MaterialButton button = new MaterialButton(activity);
        button.setText(label == null ? "" : label);
        button.setAllCaps(false);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        button.setMinHeight(dim(activity, R.dimen.button_height));
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setCornerRadius(dim(activity, R.dimen.button_radius));
        button.setLetterSpacing(0f);
        button.setPadding(dp(activity, 20), 0, dp(activity, 20), 0);
        return button;
    }

    private static android.content.res.ColorStateList tint(Context context, int attr) {
        return android.content.res.ColorStateList.valueOf(color(context, attr, Color.GRAY));
    }

    // ---- inputs -----------------------------------------------------------

    /** A Material switch. */
    public static MaterialSwitch switchView(Context context, boolean checked,
                                            android.widget.CompoundButton.OnCheckedChangeListener listener) {
        MaterialSwitch view = new MaterialSwitch(context);
        view.setChecked(checked);
        if (listener != null) {
            view.setOnCheckedChangeListener(listener);
        }
        return view;
    }

    /** A chip that carries a short status label. */
    public static Chip chip(Context context, String label) {
        Chip chip = new Chip(context);
        chip.setText(label == null ? "" : label);
        chip.setClickable(false);
        chip.setCheckable(false);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        chip.setEnsureMinTouchTargetSize(false);
        chip.setChipMinHeight(dp(context, 28));
        return chip;
    }

    /**
     * Background of an exclusive option: filled and outlined when it is
     * active, a quiet surface otherwise.
     */
    public static GradientDrawable segment(Context context, boolean active) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setCornerRadius(dp(context, 12));
        drawable.setColor(color(context,
                active ? Attrs.COLOR_PRIMARY_CONTAINER : Attrs.COLOR_SURFACE_CONTAINER_LOW));
        drawable.setStroke(dp(context, 1),
                color(context, active ? Attrs.COLOR_PRIMARY : Attrs.COLOR_OUTLINE_VARIANT));
        return drawable;
    }

    /** A filled text field with a floating label. */
    public static TextInputLayout inputLayout(Context context, String hint) {
        TextInputLayout layout = new TextInputLayout(context);
        layout.setHint(hint);
        layout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        layout.setBoxCornerRadii(
                dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12));
        return layout;
    }

    /** The editable part of a {@link #inputLayout(Context, String)}. */
    public static TextInputEditText input(Context context, String hint, boolean singleLine) {
        TextInputEditText field = new TextInputEditText(context);
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        field.setSingleLine(singleLine);
        field.setInputType(singleLine
                ? InputType.TYPE_CLASS_TEXT
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        field.setHint(hint);
        return field;
    }

    /** A plain edit field that matches the theme. */
    public static EditText plainInput(Context context, String hint) {
        EditText field = new EditText(context);
        field.setHint(hint);
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        field.setTextColor(color(context, INPUT_TEXT, Color.BLACK));
        field.setHintTextColor(color(context, MUTED, Color.GRAY));
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT);
        field.setBackground(round(color(context, INPUT_BG, Color.WHITE), dp(context, 12)));
        field.setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12));
        return field;
    }

    // ---- rows and dividers ------------------------------------------------

    /** A tappable row with a title, a detail line and a trailing chevron. */
    public static LinearLayout actionRow(Activity activity, String title, String detail, Runnable action) {
        LinearLayout row = row(activity);
        LinearLayout labels = column(activity);
        labels.addView(text(activity, title, 16, INK, true));
        if (detail != null && !detail.isEmpty()) {
            labels.addView(text(activity, detail, 13, MUTED, false), top(3, activity));
        }
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        TextView arrow = text(activity, "›", 26, ACCENT, true);
        arrow.setGravity(Gravity.CENTER);
        row.addView(arrow);
        row.setPadding(0, dp(activity, 12), 0, dp(activity, 12));
        row.setBackground(androidx.core.content.ContextCompat.getDrawable(activity, R.drawable.list_item_selector));
        if (action != null) {
            row.setOnClickListener(v -> action.run());
        }
        return row;
    }

    /** A left aligned title with an optional trailing widget. */
    public static LinearLayout heading(Activity activity, String title) {
        LinearLayout row = row(activity);
        row.addView(text(activity, title, 16, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        return row;
    }

    /** A hairline separator that follows the theme. */
    public static View divider(Activity activity) {
        MaterialDivider line = new MaterialDivider(activity);
        line.setDividerColor(color(activity, LINE, Color.LTGRAY));
        line.setDividerInsetStart(0);
        line.setDividerInsetEnd(0);
        line.setDividerThickness(dp(activity, 1));
        return line;
    }

    /** A small rounded badge, e.g. the security label of an access point. */
    public static TextView badge(Activity activity, String label, int background, int foreground) {
        TextView view = text(activity, label, 11, foreground, true);
        view.setPadding(dp(activity, 8), dp(activity, 4), dp(activity, 8), dp(activity, 4));
        view.setBackground(round(resolve(activity, background), dp(activity, 8)));
        return view;
    }

    /** Horizontal signal-strength meter built from filled/empty segments. */
    public static LinearLayout signalMeter(Activity activity, int level, int maxLevel) {
        LinearLayout meter = new LinearLayout(activity);
        meter.setOrientation(LinearLayout.HORIZONTAL);
        int segments = 4;
        for (int i = 0; i < segments; i++) {
            int threshold = (int) Math.ceil((double) maxLevel * (i + 1) / segments);
            boolean active = level >= threshold;
            View bar = new View(activity);
            bar.setBackground(round(color(activity, active ? ACCENT : LINE, Color.GRAY), dp(activity, 2)));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    dp(activity, 14), dp(activity, 5));
            params.rightMargin = dp(activity, 4);
            bar.setLayoutParams(params);
            meter.addView(bar);
        }
        return meter;
    }

    // ---- logs -------------------------------------------------------------

    /** A monospaced log view styled with the terminal surface. */
    public static TextView logView(Activity activity) {
        TextView view = new TextView(activity);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        view.setTextColor(color(activity, LOG_TEXT, Color.WHITE));
        view.setTypeface(Typeface.MONOSPACE);
        view.setPadding(dp(activity, 14), dp(activity, 14), dp(activity, 14), dp(activity, 14));
        view.setBackground(round(color(activity, LOG_BG, Color.BLACK), dp(activity, 16)));
        view.setTextIsSelectable(true);
        view.setLineSpacing(dp(activity, 4), 1f);
        return view;
    }

    /** A monospaced view for capture analysis results. */
    public static TextView resultView(Activity activity) {
        TextView view = new TextView(activity);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        view.setTextColor(color(activity, R.attr.colorCodeText, Color.BLACK));
        view.setTypeface(Typeface.MONOSPACE);
        view.setPadding(dp(activity, 14), dp(activity, 14), dp(activity, 14), dp(activity, 14));
        view.setBackground(round(color(activity, R.attr.colorCodeBackground, Color.LTGRAY), dp(activity, 16)));
        view.setTextIsSelectable(true);
        view.setLineSpacing(dp(activity, 3), 1f);
        return view;
    }

    /**
     * Wraps a log view into a card with a title row and a line counter.
     *
     * <p>The counter view is tagged so that the screen can update it while the
     * log grows.</p>
     */
    public static LinearLayout logCard(Activity activity, TextView logView, String title) {
        LinearLayout card = card(activity);
        LinearLayout header = row(activity);
        header.addView(text(activity, title, 15, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        TextView counter = text(activity, "", 12, MUTED, false);
        counter.setTag(LOG_COUNTER_TAG);
        header.addView(counter);
        card.addView(header);
        card.addView(logView, top(10, activity));
        return card;
    }

    /** Tag used to find the line counter of a log card. */
    public static final String LOG_COUNTER_TAG = "log_counter";

    /** Updates the line counter of a log card, if the card has one. */
    public static void updateLogCounter(View root, int lines) {
        if (root == null) {
            return;
        }
        View counter = root.findViewWithTag(LOG_COUNTER_TAG);
        if (counter instanceof TextView) {
            ((TextView) counter).setText(root.getContext().getString(R.string.attack_log_lines, lines));
        }
    }

    /** Counts the lines of a log buffer. */
    public static int countLines(CharSequence text) {
        if (text == null) {
            return 0;
        }
        int lines = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }

    // ---- section header ---------------------------------------------------

    /** Eyebrow + title + optional subtitle header block. */
    public static void addSectionHeader(LinearLayout root, Activity activity,
                                        String eyebrow, String title, String subtitle) {
        root.addView(text(activity, eyebrow.toUpperCase(java.util.Locale.ROOT), 11, ACCENT, true));
        root.addView(text(activity, title, 28, INK, true), top(5, activity));
        if (subtitle != null && !subtitle.isEmpty()) {
            root.addView(text(activity, subtitle, 14, MUTED, false), top(5, activity));
        }
    }

    // ---- legacy helpers ---------------------------------------------------

    /** Legacy alias for {@link #primary(Activity, String)}. */
    public static MaterialButton buttonPrimary(Activity activity, String label) {
        return primary(activity, label);
    }

    /** Legacy alias for {@link #tonal(Activity, String)}. */
    public static MaterialButton buttonSecondary(Activity activity, String label) {
        return tonal(activity, label);
    }

    /** Legacy alias for {@link #outlined(Activity, String)}. */
    public static MaterialButton buttonTertiary(Activity activity, String label) {
        return outlined(activity, label);
    }

    /** Legacy alias for {@link #accentContainer(Activity, String)}. */
    public static MaterialButton buttonCard(Activity activity, String label) {
        return accentContainer(activity, label);
    }

    /** A button that carries the warning colour role. */
    public static MaterialButton buttonWarning(Activity activity, String label) {
        MaterialButton button = baseButton(activity, label);
        button.setBackgroundTintList(tint(activity, R.attr.colorWarningContainer));
        button.setTextColor(color(activity, R.attr.colorOnWarningContainer, Color.BLACK));
        return button;
    }

    public static GradientDrawable round(@ColorInt int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    public static GradientDrawable round(Context context, int attr, int radius) {
        return round(color(context, attr, Color.GRAY), radius);
    }

    public static LinearLayout.LayoutParams top(int dp, Activity activity) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(activity, dp);
        return params;
    }

    public static LinearLayout.LayoutParams bottom(int dp, Activity activity) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(activity, dp);
        return params;
    }

    public static int dp(Activity activity, float value) {
        return dp((Context) activity, value);
    }

    public static int dp(Context context, float value) {
        if (context == null) {
            return (int) (value + 0.5f);
        }
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    /**
     * Reads a dimension resource.
     *
     * <p>The value is already expressed in pixels, so it must not be passed
     * through {@link #dp(Context, float)} a second time.</p>
     */
    public static int dim(Context context, @DimenRes int dimen) {
        if (context == null) {
            return 0;
        }
        return context.getResources().getDimensionPixelSize(dimen);
    }

    /** Adds a view to a parent using the standard top margin helper. */
    public static void addWithTop(ViewGroup parent, View child, int margin, Activity activity) {
        parent.addView(child, top(margin, activity));
    }

    @Nullable
    public static ViewGroup.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(-1, -2);
    }
}
