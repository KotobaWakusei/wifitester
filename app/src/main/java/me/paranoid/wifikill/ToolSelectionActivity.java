package me.paranoid.wifikill;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;

/**
 * 首次启动时的免责声明和工具选择界面。
 */
public class ToolSelectionActivity extends AppCompatActivity {
    private ToolPackageManager toolManager;
    private LinearLayout packageContainer;
    private CheckBox agreementCheckbox;
    private MaterialButton continueButton;
    private TextView statusHint;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tool_selection);
        
        toolManager = new ToolPackageManager(this);
        
        agreementCheckbox = findViewById(R.id.agreement_checkbox);
        packageContainer = findViewById(R.id.package_container);
        continueButton = findViewById(R.id.continue_button);
        
        statusHint = findViewById(R.id.package_status_hint);

        // 内置工具包随 APK 一起分发，启动时即解包，界面才能显示真实状态。
        // 解包加 SHA-256 校验是数 MB 的磁盘 I/O，放在后台线程执行，避免
        // 首帧渲染被阻塞（低端机首启可触发 ANR）。
        new Thread(() -> {
            new BundledToolchain(this).install();
            runOnUiThread(this::initPackageSelection);
        }, "wifitester-bundled-install").start();

        initPackageSelection();
        continueButton.setEnabled(false);
        
        final TextView agreementHint = findViewById(R.id.agreement_hint);
        agreementCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            continueButton.setEnabled(isChecked);
            // A greyed out button with no explanation is a dead end; hide the
            // hint as soon as the declaration is acknowledged.
            if (agreementHint != null) {
                agreementHint.setVisibility(isChecked ? View.GONE : View.VISIBLE);
            }
        });
        
        continueButton.setOnClickListener(v -> completeSetup());
    }

    private void initPackageSelection() {
        // Preserve what the user ticked: every progress broadcast used to
        // rebuild the list from scratch, silently resetting the checkboxes of
        // packages that were still downloading.
        java.util.Set<String> selections = new java.util.HashSet<>();
        for (int i = 0; i < packageContainer.getChildCount(); i++) {
            View item = packageContainer.getChildAt(i);
            CheckBox checkBox = item.findViewById(R.id.package_checkbox);
            ToolPackageManager.ToolPackage tag =
                    (ToolPackageManager.ToolPackage) checkBox.getTag();
            if (tag != null && checkBox.isEnabled() && checkBox.isChecked()) {
                selections.add(tag.id);
            }
        }
        List<ToolPackageManager.ToolPackage> packages = toolManager.getAllPackages();
        packageContainer.removeAllViews();

        for (ToolPackageManager.ToolPackage pkg : packages) {
            View packageItem = getLayoutInflater().inflate(R.layout.item_tool_package, null);

            CheckBox checkBox = packageItem.findViewById(R.id.package_checkbox);
            boolean installed = toolManager.isPackageInstalled(pkg);
            // 已安装的工具包不可取消；未安装的只是一个下载请求，勾选不等于已安装
            checkBox.setText(pkg.name);
            checkBox.setEnabled(!installed);
            checkBox.setChecked(installed || selections.contains(pkg.id));
            checkBox.setTag(pkg);

            TextView sizeText = packageItem.findViewById(R.id.package_size);
            sizeText.setText(updateSizeLabel(pkg));

            // 为可选工具包添加工具列表说明
            if (!toolManager.isPreInstalled(pkg)) {
                checkBox.setText(pkg.name + " (" + String.join(", ", pkg.tools) + ")");
            }

            packageContainer.addView(packageItem);
        }
        updateStatusHint();
    }

    private String updateSizeLabel(ToolPackageManager.ToolPackage pkg) {
        if (toolManager.isPackageInstalled(pkg)) {
            return getString(R.string.tools_size_label,
                    ToolPackageManager.formatSize(toolManager.getSizeBytes(pkg)));
        }
        if (toolManager.hasPendingInstall(pkg)) {
            return getString(R.string.tools_downloading, pkg.name);
        }
        return getString(R.string.tools_status_missing);
    }

    private void updateStatusHint() {
        if (statusHint == null) return;
        ArrayList<String> pending = new ArrayList<>();
        for (ToolPackageManager.ToolPackage pkg : toolManager.getAllPackages()) {
            if (toolManager.hasPendingInstall(pkg)) pending.add(pkg.name);
        }
        statusHint.setVisibility(pending.isEmpty() ? View.GONE : View.VISIBLE);
        if (!pending.isEmpty()) {
            statusHint.setText(getString(R.string.tools_pending_first_launch,
                    TextUtils.join("、", pending)));
        }
    }

    private void completeSetup() {
        // 只记录下载请求，真正的安装状态由 ToolInstallerService 在文件就位后写入
        for (int i = 0; i < packageContainer.getChildCount(); i++) {
            View item = packageContainer.getChildAt(i);
            CheckBox checkBox = item.findViewById(R.id.package_checkbox);
            ToolPackageManager.ToolPackage pkg = (ToolPackageManager.ToolPackage) checkBox.getTag();
            
            if (checkBox.isChecked() && !toolManager.isPackageInstalled(pkg)) {
                toolManager.requestInstall(pkg);
                ToolInstallerService.start(this, pkg);
            }
        }
        
        // 标记首次启动完成
        toolManager.markFirstLaunchCompleted();
        
        // 启动主界面
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finish();
    }

    private final BroadcastReceiver progressReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getBooleanExtra("running", false)) {
                initPackageSelection();
                return;
            }
            String message = intent.getStringExtra("message");
            if (message != null && !intent.getBooleanExtra("success", false)) {
                Toast.makeText(context, message, Toast.LENGTH_LONG).show();
            }
            initPackageSelection();
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(ToolInstallerService.ACTION_PROGRESS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(progressReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(progressReceiver, filter);
        }
    }

    @Override
    protected void onPause() {
        unregisterReceiver(progressReceiver);
        super.onPause();
    }
}