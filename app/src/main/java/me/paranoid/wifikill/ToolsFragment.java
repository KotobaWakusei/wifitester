package me.paranoid.wifikill;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;

/**
 * Second tab: the collection of diagnostic and security tools.
 *
 * <p>Every entry opens a dedicated workbench screen. The active tools are
 * grouped separately from the read-only ones so the user can tell at a glance
 * which screens can change the state of the radio.</p>
 */
public final class ToolsFragment extends Fragment {

    /**
     * The attached host context. The framework {@code Fragment} API has no
     * {@code requireContext()}, so the context is resolved defensively.
     */
    private android.content.Context host() {
        android.content.Context context = getActivity();
        return context != null ? context : getContext();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        ScrollView scroll = LightKit.page(getActivity());
        LinearLayout root = LightKit.root(getActivity(), scroll);
        LightKit.header(root, getActivity(), getString(R.string.tools_eyebrow), getString(R.string.tools_title),
                getString(R.string.tools_subtitle));

        root.addView(section(getString(R.string.tools_readonly), getString(R.string.tools_readonly_detail)), LightKit.bottom(getActivity(), 10));

        addTool(root, "01", getString(R.string.tools_monitor),
                getString(R.string.tools_monitor_detail),
                getString(R.string.action_open_monitor), () -> open(MonitorModeActivity.class), false);
        addTool(root, "02", getString(R.string.tools_capture),
                getString(R.string.tools_capture_detail),
                getString(R.string.tools_capture), () -> open(AircrackToolkitActivity.class), false);
        addTool(root, "03", getString(R.string.tools_frames),
                getString(R.string.tools_frames_detail),
                getString(R.string.tools_frames), () -> open(FrameAnalysisActivity.class), false);
        addTool(root, "04", getString(R.string.tools_audit),
                getString(R.string.tools_audit_detail),
                getString(R.string.tools_audit), () -> open(WiFiAuditActivity.class), false);
        addTool(root, "05", getString(R.string.tools_inventory),
                getString(R.string.tools_inventory_detail),
                getString(R.string.tools_inventory), () -> open(AircrackNgActivity.class), false);

        root.addView(section(getString(R.string.tools_active), getString(R.string.tools_active_detail)),
                LightKit.bottom(getActivity(), 10));

        addTool(root, "A1", getString(R.string.tools_attack),
                getString(R.string.tools_attack_detail),
                getString(R.string.tools_attack), () -> open(AttackActivity.class), true);

        LinearLayout notice = LightKit.card(getActivity());
        notice.addView(LightKit.text(getActivity(), getString(R.string.tools_boundary_title), 15, LightKit.INK, true));
        notice.addView(LightKit.text(getActivity(),
                getString(R.string.tools_boundary_text),
                12, LightKit.MUTED, false), LightKit.top(getActivity(), 6));
        root.addView(notice, LightKit.bottom(getActivity(), 12));

        LinearLayout bundled = LightKit.card(getActivity());
        bundled.addView(LightKit.text(getActivity(), getString(R.string.tools_bundled_title), 16, LightKit.INK, true));
        bundled.addView(LightKit.text(getActivity(),
                getString(R.string.tools_bundled_text),
                13, LightKit.MUTED, false), LightKit.top(getActivity(), 7));
        root.addView(bundled);

        return scroll;
    }

    private LinearLayout section(String title, String detail) {
        LinearLayout row = new LinearLayout(getActivity());
        row.setOrientation(LinearLayout.VERTICAL);
        row.addView(LightKit.text(getActivity(), title, 15, LightKit.INK, true));
        row.addView(LightKit.text(getActivity(), detail, 12, LightKit.MUTED, false),
                LightKit.top(getActivity(), 2));
        return row;
    }

    private void addTool(LinearLayout root, String index, String title, String description,
                         String action, Runnable click, boolean active) {
        LinearLayout card = LightKit.card(getActivity());
        LinearLayout heading = new LinearLayout(getActivity());
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(android.view.Gravity.CENTER_VERTICAL);

        TextView badge = LightKit.text(getActivity(), index, 12,
                active ? LightKit.DANGER : LightKit.ACCENT, true);
        badge.setPadding(dp(9), dp(7), dp(9), dp(7));
        badge.setBackground(LightKit.round(host(), active ? LightKit.DANGER_BG : LightKit.ACCENT_SOFT, dp(9)));
        heading.addView(badge);

        TextView name = LightKit.text(getActivity(), title, 17, LightKit.INK, true);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(0, -2, 1);
        nameParams.leftMargin = dp(10);
        heading.addView(name, nameParams);
        card.addView(heading);
        card.addView(LightKit.text(getActivity(), description, 13, LightKit.MUTED, false),
                LightKit.top(getActivity(), 8));

        MaterialButton open = active
                ? LightKit.danger(getActivity(), action)
                : LightKit.primary(getActivity(), action);
        open.setOnClickListener(v -> click.run());
        card.addView(open, LightKit.top(getActivity(), 12));
        root.addView(card, LightKit.bottom(getActivity(), 11));
    }

    private void open(Class<?> target) {
        try {
            startActivity(new android.content.Intent(getActivity(), target));
        } catch (Exception failure) {
            MainActivity.notify(getActivity(), getString(R.string.tools_open_failed));
        }
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
