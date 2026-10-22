package me.paranoid.wifikill;

import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.material.snackbar.Snackbar;

/**
 * Common base for every screen of the app.
 *
 * <p>It keeps the system bars in sync with the active colour scheme and
 * offers the small helpers that all workbenches need.</p>
 */
public abstract class BaseActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applySystemBars();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applySystemBars();
    }

    /**
     * Paints the status and navigation bars with the page background and
     * switches the icon colour so they stay readable in both schemes.
     */
    protected void applySystemBars() {
        int background = Theme.color(this, R.attr.colorSurface, Color.WHITE);
        getWindow().setStatusBarColor(background);
        getWindow().setNavigationBarColor(background);
        View decor = getWindow().getDecorView();
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(getWindow(), decor);
        boolean light = Theme.isLight(this);
        controller.setAppearanceLightStatusBars(light);
        controller.setAppearanceLightNavigationBars(light);
    }

    /** A Material toolbar that matches the current theme. */
    protected Toolbar toolbar(CharSequence title, CharSequence subtitle) {
        Toolbar toolbar = new Toolbar(this);
        toolbar.setTitle(title);
        if (subtitle != null && subtitle.length() > 0) {
            toolbar.setSubtitle(subtitle);
        }
        toolbar.setTitleTextColor(Theme.color(this, R.attr.colorOnSurface));
        toolbar.setSubtitleTextColor(Theme.color(this, R.attr.colorOnSurfaceVariant));
        return toolbar;
    }

    /** Shows a Material snackbar anchored to the content of the activity. */
    protected void snack(String message) {
        if (message == null || message.trim().isEmpty()) {
            return;
        }
        View root = findViewById(android.R.id.content);
        if (root == null) {
            return;
        }
        Snackbar bar = Snackbar.make(root, message.trim(), Snackbar.LENGTH_LONG);
        ViewGroup.LayoutParams params = bar.getView().getLayoutParams();
        if (params instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
            int margin = Math.max(margins.leftMargin, margins.rightMargin);
            margins.setMargins(margin, margin, margin, margin);
            bar.getView().setLayoutParams(margins);
        }
        bar.show();
    }
}
