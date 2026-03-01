package com.oodbye.looklspmodule;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class ModuleSettingsActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("LOOK LSP 设置");

        ModuleSettings.ensureDefaults(this);
        final SharedPreferences prefs = ModuleSettings.appPrefs(this);

        ScrollView scrollView = new ScrollView(this);
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        container.setPadding(p, p, p, p);
        scrollView.addView(container, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView title = new TextView(this);
        title.setText("LOOK 直播 LSP 模块");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        container.addView(title);

        TextView desc = new TextView(this);
        desc.setText("模块通过 UI 组件采集数据；所有 UI 参数统一在 UiComponentConfig.java 维护。");
        desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        descLp.topMargin = dp(8);
        desc.setLayoutParams(descLp);
        container.addView(desc);

        final Switch floatSwitch = new Switch(this);
        floatSwitch.setText("全局悬浮按钮");
        floatSwitch.setChecked(ModuleSettings.getGlobalFloatButtonEnabled(prefs));
        LinearLayout.LayoutParams floatLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        floatLp.topMargin = dp(20);
        floatSwitch.setLayoutParams(floatLp);
        floatSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                ModuleSettings.setGlobalFloatButtonEnabled(ModuleSettingsActivity.this, isChecked);
                if (isChecked) {
                    ensureOverlayPermissionIfNeeded();
                    GlobalFloatService.startServiceCompat(ModuleSettingsActivity.this);
                    Toast.makeText(ModuleSettingsActivity.this, "已开启全局悬浮按钮", Toast.LENGTH_SHORT).show();
                } else {
                    GlobalFloatService.stopServiceCompat(ModuleSettingsActivity.this);
                    Toast.makeText(ModuleSettingsActivity.this, "已关闭全局悬浮按钮", Toast.LENGTH_SHORT).show();
                }
            }
        });
        container.addView(floatSwitch);

        TextView floatHint = new TextView(this);
        floatHint.setText("开启后会全局显示悬浮按钮，点击可选择“运行/暂停/停止”模块。");
        floatHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams floatHintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        floatHintLp.topMargin = dp(6);
        floatHint.setLayoutParams(floatHintLp);
        container.addView(floatHint);

        final Switch adSwitch = new Switch(this);
        adSwitch.setText("广告处理");
        adSwitch.setChecked(ModuleSettings.getAdProcessEnabled(prefs));
        LinearLayout.LayoutParams adLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        adLp.topMargin = dp(20);
        adSwitch.setLayoutParams(adLp);
        adSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                ModuleSettings.setAdProcessEnabled(ModuleSettingsActivity.this, isChecked);
                if (isChecked) {
                    Toast.makeText(ModuleSettingsActivity.this, "已开启广告处理", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(ModuleSettingsActivity.this, "已关闭广告处理", Toast.LENGTH_SHORT).show();
                }
            }
        });
        container.addView(adSwitch);

        TextView adHint = new TextView(this);
        adHint.setText("广告规则读取 CustomRules.ini（优先外部文件，其次模块 assets 内置文件）。");
        adHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams adHintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        adHintLp.topMargin = dp(6);
        adHint.setLayoutParams(adHintLp);
        container.addView(adHint);

        setContentView(scrollView);

        if (ModuleSettings.getGlobalFloatButtonEnabled(prefs)) {
            ensureOverlayPermissionIfNeeded();
            GlobalFloatService.startServiceCompat(this);
        } else {
            GlobalFloatService.stopServiceCompat(this);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        SharedPreferences prefs = ModuleSettings.appPrefs(this);
        if (ModuleSettings.getGlobalFloatButtonEnabled(prefs) && canDrawOverlaysCompat()) {
            GlobalFloatService.startServiceCompat(this);
        }
    }

    private void ensureOverlayPermissionIfNeeded() {
        if (canDrawOverlaysCompat()) {
            return;
        }
        Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_LONG).show();
        Intent intent = new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName())
        );
        startActivity(intent);
    }

    private boolean canDrawOverlaysCompat() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        return Settings.canDrawOverlays(this);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
