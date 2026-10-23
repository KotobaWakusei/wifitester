package me.paranoid.wifikill;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.button.MaterialButton;

import java.util.List;

/**
 * 首次启动时的免责声明和工具选择界面。
 */
public class ToolSelectionActivity extends AppCompatActivity {
    private ToolPackageManager toolManager;
    private LinearLayout packageContainer;
    private CheckBox agreementCheckbox;
    private MaterialButton continueButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tool_selection);
        
        toolManager = new ToolPackageManager(this);
        
        agreementCheckbox = findViewById(R.id.agreement_checkbox);
        packageContainer = findViewById(R.id.package_container);
        continueButton = findViewById(R.id.continue_button);
        
        initPackageSelection();
        continueButton.setEnabled(false);
        
        agreementCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            continueButton.setEnabled(isChecked);
        });
        
        continueButton.setOnClickListener(v -> completeSetup());
    }

    private void initPackageSelection() {
        List<ToolPackageManager.ToolPackage> packages = toolManager.getAllPackages();
        
        for (ToolPackageManager.ToolPackage pkg : packages) {
            View packageItem = getLayoutInflater().inflate(R.layout.item_tool_package, null);
            
            CheckBox checkBox = packageItem.findViewById(R.id.package_checkbox);
            checkBox.setText(pkg.name);
            checkBox.setEnabled(!pkg.preInstalled); // 核心工具包不可取消
            checkBox.setChecked(pkg.preInstalled); // 核心工具包默认选中
            checkBox.setTag(pkg);
            
            TextView sizeText = packageItem.findViewById(R.id.package_size);
            sizeText.setText(pkg.getSizeFormatted(this));
            
            // 为可选工具包添加工具列表说明
            if (!pkg.preInstalled) {
                checkBox.setText(pkg.name + " (" + String.join(", ", pkg.tools) + ")");
            }
            
            packageContainer.addView(packageItem);
        }
    }

    private void completeSetup() {
        // 保存用户选择
        for (int i = 0; i < packageContainer.getChildCount(); i++) {
            View item = packageContainer.getChildAt(i);
            CheckBox checkBox = item.findViewById(R.id.package_checkbox);
            ToolPackageManager.ToolPackage pkg = (ToolPackageManager.ToolPackage) checkBox.getTag();
            
            if (checkBox.isChecked()) {
                toolManager.installPackage(pkg);
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
}