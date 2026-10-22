package me.paranoid.wifikill;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.widget.LinearLayout;

import com.google.android.material.bottomnavigation.BottomNavigationView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/**
 * Smoke test for the user interface.
 *
 * <p>It builds the real screens on a simulated device, which catches layout
 * and inflation problems that a pure JVM test cannot see.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class UserInterfaceSmokeTest {

    private static final int DENSITY_FACTOR = 3;

    /** The shell must start and show the three workspaces. */
    @Test
    public void mainActivityStartsWithThreeTabs() {
        try (ActivityController<MainActivity> controller =
                     Robolectric.buildActivity(MainActivity.class)) {
            MainActivity activity = controller.setup().get();
            assertNotNull(activity.findViewById(R.id.tab_container));

            BottomNavigationView bar = activity.findViewById(R.id.bottom_navigation);
            assertNotNull(bar);
            assertEquals(3, bar.getMenu().size());
        }
    }

    /** Every tab must inflate without an exception. */
    @Test
    public void everyTabInflates() {
        try (ActivityController<MainActivity> controller =
                     Robolectric.buildActivity(MainActivity.class)) {
            MainActivity activity = controller.setup().get();
            activity.selectTab(0, false);
            activity.selectTab(1, false);
            activity.selectTab(2, false);
            controller.pause().resume();
        }
    }

    /**
     * Tapping a tab has to work.
     *
     * <p>The selection listener and {@code setSelectedItemId()} used to call
     * each other, which ended in a StackOverflowError.</p>
     */
    @Test
    public void tappingATabSwitchesTheWorkspace() {
        try (ActivityController<MainActivity> controller =
                     Robolectric.buildActivity(MainActivity.class)) {
            MainActivity activity = controller.setup().get();
            BottomNavigationView bar = activity.findViewById(R.id.bottom_navigation);
            assertNotNull(bar);

            bar.setSelectedItemId(R.id.tab_tools);
            assertEquals(R.id.tab_tools, bar.getSelectedItemId());
            bar.setSelectedItemId(R.id.tab_settings);
            assertEquals(R.id.tab_settings, bar.getSelectedItemId());
            bar.setSelectedItemId(R.id.tab_wifi);
            assertEquals(R.id.tab_wifi, bar.getSelectedItemId());
        }
    }

    /** The detail screen and the workbenches must build their views. */
    @Test
    public void workbenchesInflate() {
        Robolectric.buildActivity(NetworkDetailActivity.class).setup();
        Robolectric.buildActivity(AttackActivity.class).setup();
        Robolectric.buildActivity(MonitorModeActivity.class).setup();
        Robolectric.buildActivity(AircrackToolkitActivity.class).setup();
        Robolectric.buildActivity(FrameAnalysisActivity.class).setup();
        Robolectric.buildActivity(WiFiAuditActivity.class).setup();
        Robolectric.buildActivity(AircrackNgActivity.class).setup();
    }

    /**
     * The card padding must be the declared dimension and nothing else.
     *
     * <p>A value that was converted from pixels to density a second time
     * would be three times too large on a xxxhdpi screen.</p>
     */
    @Test
    public void cardPaddingUsesTheDeclaredDimension() {
        try (ActivityController<MainActivity> controller =
                     Robolectric.buildActivity(MainActivity.class)) {
            MainActivity activity = controller.setup().get();
            LinearLayout card = UiKit.card(activity);
            int expected = activity.getResources().getDimensionPixelSize(R.dimen.card_padding);
            assertEquals(expected, card.getPaddingLeft());
            assertEquals(expected, card.getPaddingTop());
        }
    }

    /** The page margins must be the declared dimensions as well. */
    @Test
    public void pageMarginsUseTheDeclaredDimensions() {
        try (ActivityController<MainActivity> controller =
                     Robolectric.buildActivity(MainActivity.class)) {
            MainActivity activity = controller.setup().get();
            android.widget.ScrollView page = UiKit.createPage(activity);
            LinearLayout root = (LinearLayout) page.getChildAt(0);
            assertEquals(activity.getResources().getDimensionPixelSize(R.dimen.page_margin_horizontal),
                    root.getPaddingLeft());
            assertEquals(activity.getResources().getDimensionPixelSize(R.dimen.page_margin_top),
                    root.getPaddingTop());
        }
    }

    /** The button height must come from the dimension resource. */
    @Test
    public void buttonHeightUsesTheDeclaredDimension() {
        try (ActivityController<MainActivity> controller =
                     Robolectric.buildActivity(MainActivity.class)) {
            MainActivity activity = controller.setup().get();
            com.google.android.material.button.MaterialButton button =
                    UiKit.primary(activity, "test");
            assertEquals(activity.getResources().getDimensionPixelSize(R.dimen.button_height),
                    button.getMinimumHeight());
        }
    }

    /** The dark scheme has to change the resolved colours. */
    @Test
    @Config(qualifiers = "night")
    public void nightSchemeResolvesDifferentColours() {
        int night = UiKit.color(RuntimeEnvironment.getApplication(),
                Attrs.COLOR_SURFACE);
        assertTrue("the dark surface has to differ from the light one",
                night != 0xFFF8FAFF);
    }
}
