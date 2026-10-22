package me.paranoid.wifikill;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;

import androidx.annotation.AttrRes;
import androidx.annotation.ColorInt;
import androidx.core.graphics.ColorUtils;

import com.google.android.material.color.MaterialColors;

/**
 * Small bridge between the Material theme and the programmatic UI.
 *
 * <p>Every colour used by the hand-built views is resolved from a theme
 * attribute instead of a literal, so the same code renders correctly in the
 * light and in the dark scheme.</p>
 */
final class Theme {

    private Theme() {
    }

    /**
     * Resolves a colour attribute of the current theme.
     *
     * @param context any context that carries the application theme
     * @param attr    the attribute to resolve, e.g. {@code R.attr.colorPrimary}
     * @return the resolved colour, or {@link Color#GRAY} when the attribute is
     *         not part of the theme
     */
    @ColorInt
    static int color(Context context, @AttrRes int attr) {
        return color(context, attr, Color.GRAY);
    }

    @ColorInt
    static int color(Context context, @AttrRes int attr, @ColorInt int fallback) {
        if (context == null) {
            return fallback;
        }
        return MaterialColors.getColor(context, attr, fallback);
    }

    /** True when the resolved configuration is the light variant. */
    static boolean isLight(Context context) {
        if (context == null) {
            return true;
        }
        int mode = context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return mode != Configuration.UI_MODE_NIGHT_YES;
    }

    /** Blends a colour towards the theme background, used for subtle fills. */
    @ColorInt
    static int blend(Context context, @ColorInt int color, float amount) {
        int background = color(context, R.attr.colorSurface, Color.WHITE);
        return ColorUtils.blendARGB(color, background, Math.max(0f, Math.min(1f, amount)));
    }
}
