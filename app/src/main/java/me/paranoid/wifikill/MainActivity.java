package me.paranoid.wifikill;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.bottomnavigation.BottomNavigationView;

/**
 * Application shell: hosts the three primary workspaces behind a bottom
 * navigation bar.
 *
 * <ul>
 *   <li>{@link WifiListFragment} — SSID list, link status and per-network details</li>
 *   <li>{@link ToolsFragment} — the diagnostic and security tool collection</li>
 *   <li>{@link SettingsFragment} — capture defaults, Root and safety settings</li>
 * </ul>
 *
 * <p>Tabs are kept alive with show/hide instead of replace so scan results and
 * tool state survive navigation.</p>
 */
public final class MainActivity extends BaseActivity {

    /** Tab identifiers, in bottom-navigation order. */
    private static final String[] TAB_TAGS = {"tab_wifi", "tab_tools", "tab_settings"};
    private static final String[] TAB_CLASSES = {
            WifiListFragment.class.getName(),
            ToolsFragment.class.getName(),
            SettingsFragment.class.getName()
    };

    private FrameLayout container;
    private BottomNavigationView navigation;
    private int selectedTab = 0;
    /** Guards the tab transaction against the re-entrant selection listener. */
    private boolean rendering;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildShell();
        int restored = getIntent() != null ? getIntent().getIntExtra(EXTRA_TAB, 0) : 0;
        selectTab(clampTab(restored), false);
    }

    private void buildShell() {
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackgroundColor(UiKit.color(this, UiKit.BG));

        container = new FrameLayout(this);
        container.setId(R.id.tab_container);
        shell.addView(container, new LinearLayout.LayoutParams(-1, 0, 1));

        navigation = new BottomNavigationView(this);
        navigation.setId(R.id.bottom_navigation);
        navigation.inflateMenu(R.menu.bottom_nav_menu);
        navigation.setOnItemSelectedListener(item -> {
            int index = indexOfTab(item.getItemId());
            if (index < 0) {
                return false;
            }
            // The navigation bar is the single source of truth: the listener
            // only updates the tab, it must not call setSelectedItemId()
            // again because that would call the listener a second time.
            selectedTab = index;
            renderTabs(true);
            animateSelectedNavigation();
            return true;
        });
        navigation.setLabelVisibilityMode(BottomNavigationView.LABEL_VISIBILITY_LABELED);
        shell.addView(navigation, new LinearLayout.LayoutParams(-1, dp(80)));

        setContentView(shell);
    }

    private static int indexOfTab(int itemId) {
        if (itemId == R.id.tab_wifi) {
            return 0;
        }
        if (itemId == R.id.tab_tools) {
            return 1;
        }
        if (itemId == R.id.tab_settings) {
            return 2;
        }
        return -1;
    }

    private static int itemIdOfTab(int tab) {
        if (tab == 1) {
            return R.id.tab_tools;
        }
        if (tab == 2) {
            return R.id.tab_settings;
        }
        return R.id.tab_wifi;
    }

    /**
     * Switches the visible tab, keeping the other tabs alive but hidden.
     *
     * <p>The navigation bar reports the selection through its listener, so
     * this method only moves the selection when the caller is not the bar
     * itself. Otherwise the listener and {@code setSelectedItemId()} would
     * call each other without end.</p>
     */
    public void selectTab(int tab, boolean animate) {
        int target = clampTab(tab);
        boolean changed = target != selectedTab;
        selectedTab = target;
        if (navigation != null && navigation.getSelectedItemId() != itemIdOfTab(target)) {
            // The listener renders the tabs as soon as the bar changed its
            // selection, so nothing has to be rendered a second time here.
            navigation.setSelectedItemId(itemIdOfTab(target));
            return;
        }
        renderTabs(animate && changed);
        if (changed && animate) {
            animateSelectedNavigation();
        }
    }

    private void renderTabs(boolean animate) {
        FragmentManager manager = getSupportFragmentManager();
        if (manager == null || rendering) {
            // setSelectedItemId() calls the selection listener synchronously,
            // which re-enters this method. The outer call renders the tabs
            // with the current selection, so the nested one has to stop.
            return;
        }
        rendering = true;
        FragmentTransaction transaction = manager.beginTransaction();
        for (int i = 0; i < TAB_TAGS.length; i++) {
            Fragment fragment = manager.findFragmentByTag(TAB_TAGS[i]);
            if (fragment == null) {
                fragment = newFragment(TAB_CLASSES[i]);
                if (fragment == null) {
                    continue;
                }
                transaction.add(R.id.tab_container, fragment, TAB_TAGS[i]);
                if (i != selectedTab) {
                    transaction.hide(fragment);
                }
                continue;
            }
            if (i == selectedTab && fragment.isHidden()) {
                transaction.show(fragment);
            } else if (i != selectedTab && !fragment.isHidden()) {
                transaction.hide(fragment);
            }
        }
        transaction.commitAllowingStateLoss();
        rendering = false;
        if (animate) {
            animatePageIn();
        }
    }

    /**
     * Creates a fragment by its class name.
     *
     * <p>The tabs are addressed by name so the release build can shrink the
     * unused screens; the ProGuard rules keep these classes alive.</p>
     */
    private static Fragment newFragment(String className) {
        try {
            Class<?> type = Class.forName(className);
            Object instance = type.getDeclaredConstructor().newInstance();
            return instance instanceof Fragment ? (Fragment) instance : null;
        } catch (Exception failure) {
            return null;
        }
    }

    /** Lightweight native-property transitions; no extra animation dependency. */
    private void animatePageIn() {
        if (container == null || container.getChildCount() == 0) {
            return;
        }
        View page = container.getChildAt(0);
        page.setAlpha(0f);
        page.setTranslationY(dp(8));
        page.animate().alpha(1f).translationY(0f).setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
    }

    private void animateSelectedNavigation() {
        if (navigation == null) {
            return;
        }
        View item = navigation.findViewById(selectedItemId());
        if (item == null) {
            return;
        }
        item.setScaleX(0.94f);
        item.setScaleY(0.94f);
        item.animate().scaleX(1f).scaleY(1f).setDuration(180)
                .setInterpolator(new android.view.animation.OvershootInterpolator(1.2f)).start();
    }

    private int selectedItemId() {
        return selectedTab == 0 ? R.id.tab_wifi : (selectedTab == 1 ? R.id.tab_tools : R.id.tab_settings);
    }

    private static int clampTab(int tab) {
        if (tab < 0 || tab >= TAB_TAGS.length) {
            return 0;
        }
        return tab;
    }

    @Override
    public void onBackPressed() {
        // Back always returns to the Wi-Fi list before leaving the app.
        if (selectedTab != 0) {
            selectTab(0, true);
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && intent.hasExtra(EXTRA_TAB)) {
            selectTab(clampTab(intent.getIntExtra(EXTRA_TAB, 0)), true);
        }
    }

    /** Sends a short toast from any tab. */
    static void notify(android.app.Activity activity, String message) {
        if (activity == null || message == null || message.trim().isEmpty()) {
            return;
        }
        Toast.makeText(activity, message.trim(), Toast.LENGTH_LONG).show();
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** Extra key used to open the shell on a specific tab. */
    public static final String EXTRA_TAB = "me.paranoid.wifikill.extra.TAB";
}
