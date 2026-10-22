package me.paranoid.wifikill;

import android.content.Context;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

/**
 * Appearance preferences: the day/night variant and the interface language.
 *
 * <p>Both settings follow the system by default. The user can force a light or
 * dark theme and can pin the interface to a single language; the choice is
 * stored in {@link SettingsStore} and applied globally through the AppCompat
 * delegate so every activity reacts at once.</p>
 */
final class AppConfig {

    /** Follow the system day/night setting. */
    static final String THEME_SYSTEM = "system";
    /** Always use the light scheme. */
    static final String THEME_LIGHT = "light";
    /** Always use the dark scheme. */
    static final String THEME_DARK = "dark";

    /** Supported theme values, in the order they are shown. */
    static final String[] THEME_MODES = {THEME_SYSTEM, THEME_LIGHT, THEME_DARK};

    /** Follow the language of the system. */
    static final String LANGUAGE_SYSTEM = "system";
    /** Simplified Chinese. */
    static final String LANGUAGE_ZH = "zh";
    /** English. */
    static final String LANGUAGE_EN = "en";

    /** Supported languages, in the order they are shown. */
    static final String[] LANGUAGES = {LANGUAGE_SYSTEM, LANGUAGE_ZH, LANGUAGE_EN};

    private AppConfig() {
    }

    // ---- theme ------------------------------------------------------------

    static String themeMode(Context context) {
        String stored = SettingsStore.prefs(context).getString(SettingsStore.KEY_THEME_MODE, THEME_SYSTEM);
        for (String mode : THEME_MODES) {
            if (mode.equals(stored)) {
                return stored;
            }
        }
        return THEME_SYSTEM;
    }

    static void setThemeMode(Context context, String mode) {
        String value = THEME_SYSTEM;
        for (String allowed : THEME_MODES) {
            if (allowed.equals(mode)) {
                value = allowed;
            }
        }
        SettingsStore.prefs(context).edit().putString(SettingsStore.KEY_THEME_MODE, value).apply();
    }

    /** Maps the stored theme onto the AppCompat night mode constants. */
    static int nightMode(Context context) {
        switch (themeMode(context)) {
            case THEME_LIGHT:
                return AppCompatDelegate.MODE_NIGHT_NO;
            case THEME_DARK:
                return AppCompatDelegate.MODE_NIGHT_YES;
            default:
                return AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        }
    }

    /** Applies the stored theme. Safe to call repeatedly. */
    static void applyTheme(Context context) {
        int mode = nightMode(context);
        if (AppCompatDelegate.getDefaultNightMode() != mode) {
            AppCompatDelegate.setDefaultNightMode(mode);
        }
    }

    // ---- language ---------------------------------------------------------

    static String language(Context context) {
        String stored = SettingsStore.prefs(context).getString(SettingsStore.KEY_LANGUAGE, LANGUAGE_SYSTEM);
        for (String language : LANGUAGES) {
            if (language.equals(stored)) {
                return stored;
            }
        }
        return LANGUAGE_SYSTEM;
    }

    static void setLanguage(Context context, String language) {
        String value = LANGUAGE_SYSTEM;
        for (String allowed : LANGUAGES) {
            if (allowed.equals(language)) {
                value = allowed;
            }
        }
        SettingsStore.prefs(context).edit().putString(SettingsStore.KEY_LANGUAGE, value).apply();
    }

    /** The locale list that matches the stored language preference. */
    static LocaleListCompat locales(Context context) {
        switch (language(context)) {
            case LANGUAGE_ZH:
                return LocaleListCompat.forLanguageTags("zh-CN");
            case LANGUAGE_EN:
                return LocaleListCompat.forLanguageTags("en");
            default:
                // An empty list means "follow the system language".
                return LocaleListCompat.getEmptyLocaleList();
        }
    }

    /**
     * Applies the stored language. An empty locale list restores the system
     * language, which is what the "follow system" option asks for.
     */
    static void applyLanguage(Context context) {
        LocaleListCompat target = locales(context);
        LocaleListCompat current = AppCompatDelegate.getApplicationLocales();
        if (!current.equals(target)) {
            AppCompatDelegate.setApplicationLocales(target);
        }
    }

    /** Human readable name of a language option, resolved from the resources. */
    static int languageLabel(Context context, String language) {
        switch (language) {
            case LANGUAGE_ZH:
                return R.string.language_chinese;
            case LANGUAGE_EN:
                return R.string.language_english;
            default:
                return R.string.language_system;
        }
    }

    /** Human readable name of a theme option, resolved from the resources. */
    static int themeLabel(Context context, String mode) {
        switch (mode) {
            case THEME_LIGHT:
                return R.string.theme_light;
            case THEME_DARK:
                return R.string.theme_dark;
            default:
                return R.string.theme_system;
        }
    }
}
