package me.paranoid.wifikill;

import android.app.Application;

import androidx.appcompat.app.AppCompatDelegate;

/**
 * Application entry point.
 *
 * <p>The stored appearance preferences are applied before the first activity is
 * created so the very first frame already uses the right colour scheme and
 * language.</p>
 */
public final class WiFiTesterApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        // Night mode first: the locale switch recreates the activity, so the
        // theme has to be in place before that happens.
        AppConfig.applyTheme(this);
        AppConfig.applyLanguage(this);
        if (AppCompatDelegate.getDefaultNightMode() == AppCompatDelegate.MODE_NIGHT_UNSPECIFIED) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        }
    }
}
