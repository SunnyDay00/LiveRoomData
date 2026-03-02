package com.oodbye.looklspmodule;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.InputType;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.view.Gravity;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
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
        floatHint.setText("开启后会全局显示悬浮按钮，点击可选择“运行/停止”模块。");
        floatHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams floatHintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        floatHintLp.topMargin = dp(6);
        floatHint.setLayoutParams(floatHintLp);
        container.addView(floatHint);

        final Switch floatInfoSwitch = new Switch(this);
        floatInfoSwitch.setText("悬浮信息窗口");
        floatInfoSwitch.setChecked(ModuleSettings.getFloatInfoWindowEnabled(prefs));
        LinearLayout.LayoutParams floatInfoLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        floatInfoLp.topMargin = dp(12);
        floatInfoSwitch.setLayoutParams(floatInfoLp);
        floatInfoSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                ModuleSettings.setFloatInfoWindowEnabled(ModuleSettingsActivity.this, isChecked);
                if (ModuleSettings.getGlobalFloatButtonEnabled(ModuleSettings.appPrefs(ModuleSettingsActivity.this))
                        && canDrawOverlaysCompat()) {
                    GlobalFloatService.startServiceCompat(ModuleSettingsActivity.this);
                }
                Toast.makeText(
                        ModuleSettingsActivity.this,
                        isChecked ? "已开启悬浮信息窗口" : "已关闭悬浮信息窗口",
                        Toast.LENGTH_SHORT
                ).show();
            }
        });
        container.addView(floatInfoSwitch);

        TextView floatInfoHint = new TextView(this);
        floatInfoHint.setText("开启后会在悬浮按钮上方显示：循环(当前/剩余)、本轮直播间进入数、运行时长。");
        floatInfoHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams floatInfoHintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        floatInfoHintLp.topMargin = dp(6);
        floatInfoHint.setLayoutParams(floatInfoHintLp);
        container.addView(floatInfoHint);

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

        final Switch accessibilityAdSwitch = new Switch(this);
        accessibilityAdSwitch.setText("无障碍实时广告服务（替代LSP）");
        accessibilityAdSwitch.setChecked(ModuleSettings.getAccessibilityAdServiceEnabled(prefs));
        LinearLayout.LayoutParams accessibilityLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        accessibilityLp.topMargin = dp(20);
        accessibilityAdSwitch.setLayoutParams(accessibilityLp);
        accessibilityAdSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                ModuleSettings.setAccessibilityAdServiceEnabled(ModuleSettingsActivity.this, isChecked);
                if (!isChecked) {
                    Toast.makeText(ModuleSettingsActivity.this, "已切换为 LSP 广告处理", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (isAccessibilityServiceEnabledCompat()) {
                    Toast.makeText(ModuleSettingsActivity.this, "无障碍广告服务已启用", Toast.LENGTH_SHORT).show();
                    return;
                }
                Toast.makeText(ModuleSettingsActivity.this, "请开启无障碍服务权限", Toast.LENGTH_LONG).show();
                openAccessibilitySettings();
            }
        });
        container.addView(accessibilityAdSwitch);

        TextView accessibilityHint = new TextView(this);
        accessibilityHint.setText("开启后广告检测改为无障碍实时扫描（不依赖 LSP 注入）。");
        accessibilityHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams accessibilityHintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        accessibilityHintLp.topMargin = dp(6);
        accessibilityHint.setLayoutParams(accessibilityHintLp);
        container.addView(accessibilityHint);

        final Switch autoRunSwitch = new Switch(this);
        autoRunSwitch.setText("启动软件自动运行模块");
        autoRunSwitch.setChecked(ModuleSettings.getAutoRunOnAppStartEnabled(prefs));
        LinearLayout.LayoutParams autoRunLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        autoRunLp.topMargin = dp(20);
        autoRunSwitch.setLayoutParams(autoRunLp);
        autoRunSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                ModuleSettings.setAutoRunOnAppStartEnabled(ModuleSettingsActivity.this, isChecked);
                Toast.makeText(
                        ModuleSettingsActivity.this,
                        isChecked ? "已开启启动软件自动运行模块" : "已关闭启动软件自动运行模块",
                        Toast.LENGTH_SHORT
                ).show();
            }
        });
        container.addView(autoRunSwitch);

        TextView autoRunHint = new TextView(this);
        autoRunHint.setText("开启后，打开 LOOK 软件会自动开始模块流程；关闭后需手动点击悬浮按钮“运行”。");
        autoRunHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams autoRunHintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        autoRunHintLp.topMargin = dp(6);
        autoRunHint.setLayoutParams(autoRunHintLp);
        container.addView(autoRunHint);

        final Switch viewTreeDumpSwitch = new Switch(this);
        viewTreeDumpSwitch.setText("View树调试输出（仅调试）");
        viewTreeDumpSwitch.setChecked(ModuleSettings.getViewTreeDumpDebugEnabled(prefs));
        LinearLayout.LayoutParams viewTreeDumpLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        viewTreeDumpLp.topMargin = dp(20);
        viewTreeDumpSwitch.setLayoutParams(viewTreeDumpLp);
        viewTreeDumpSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                ModuleSettings.setViewTreeDumpDebugEnabled(ModuleSettingsActivity.this, isChecked);
                Toast.makeText(
                        ModuleSettingsActivity.this,
                        isChecked ? "已开启View树调试输出" : "已关闭View树调试输出",
                        Toast.LENGTH_SHORT
                ).show();
            }
        });
        container.addView(viewTreeDumpSwitch);

        TextView viewTreeDumpHint = new TextView(this);
        viewTreeDumpHint.setText("仅用于排查直播间界面识别问题；开启后会在运行日志输出Activity View树结构。");
        viewTreeDumpHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams viewTreeDumpHintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        viewTreeDumpHintLp.topMargin = dp(6);
        viewTreeDumpHint.setLayoutParams(viewTreeDumpHintLp);
        container.addView(viewTreeDumpHint);

        TextView cycleLimitTitle = new TextView(this);
        cycleLimitTitle.setText("一起聊直播间循环点击次数");
        cycleLimitTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        LinearLayout.LayoutParams cycleLimitTitleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        cycleLimitTitleLp.topMargin = dp(20);
        cycleLimitTitle.setLayoutParams(cycleLimitTitleLp);
        container.addView(cycleLimitTitle);

        LinearLayout cycleLimitRow = new LinearLayout(this);
        cycleLimitRow.setOrientation(LinearLayout.HORIZONTAL);
        cycleLimitRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams cycleLimitRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        cycleLimitRowLp.topMargin = dp(8);
        cycleLimitRow.setLayoutParams(cycleLimitRowLp);
        container.addView(cycleLimitRow);

        final EditText cycleLimitInput = new EditText(this);
        cycleLimitInput.setSingleLine(true);
        cycleLimitInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        cycleLimitInput.setHint("0");
        cycleLimitInput.setText(String.valueOf(ModuleSettings.getTogetherCycleLimit(prefs)));
        LinearLayout.LayoutParams cycleInputLp = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        cycleInputLp.weight = 1f;
        cycleLimitInput.setLayoutParams(cycleInputLp);
        cycleLimitRow.addView(cycleLimitInput);

        Button cycleLimitSaveBtn = new Button(this);
        cycleLimitSaveBtn.setText("保存");
        LinearLayout.LayoutParams cycleSaveLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        cycleSaveLp.leftMargin = dp(10);
        cycleLimitSaveBtn.setLayoutParams(cycleSaveLp);
        cycleLimitSaveBtn.setOnClickListener(v -> {
            String raw = String.valueOf(cycleLimitInput.getText()).trim();
            int limit;
            if (TextUtils.isEmpty(raw)) {
                limit = 0;
            } else {
                try {
                    limit = Integer.parseInt(raw);
                } catch (Throwable e) {
                    Toast.makeText(
                            ModuleSettingsActivity.this,
                            "请输入非负整数，0表示无限",
                            Toast.LENGTH_SHORT
                    ).show();
                    return;
                }
            }
            if (limit < 0) {
                Toast.makeText(
                        ModuleSettingsActivity.this,
                        "请输入非负整数，0表示无限",
                        Toast.LENGTH_SHORT
                ).show();
                return;
            }
            ModuleSettings.setTogetherCycleLimit(ModuleSettingsActivity.this, limit);
            cycleLimitInput.setText(String.valueOf(limit));
            Toast.makeText(
                    ModuleSettingsActivity.this,
                    limit == 0 ? "已设置为无限循环" : "已设置循环次数为 " + limit,
                    Toast.LENGTH_SHORT
            ).show();
        });
        cycleLimitRow.addView(cycleLimitSaveBtn);

        TextView cycleLimitHint = new TextView(this);
        cycleLimitHint.setText("0 表示无限循环；设置为 N 表示完成 N 次“完整一轮卡片处理”后自动停止。");
        cycleLimitHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams cycleLimitHintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        cycleLimitHintLp.topMargin = dp(6);
        cycleLimitHint.setLayoutParams(cycleLimitHintLp);
        container.addView(cycleLimitHint);

        TextView cycleWaitTitle = new TextView(this);
        cycleWaitTitle.setText("每次循环后等待时间（秒）");
        cycleWaitTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        LinearLayout.LayoutParams cycleWaitTitleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        cycleWaitTitleLp.topMargin = dp(20);
        cycleWaitTitle.setLayoutParams(cycleWaitTitleLp);
        container.addView(cycleWaitTitle);

        LinearLayout cycleWaitRow = new LinearLayout(this);
        cycleWaitRow.setOrientation(LinearLayout.HORIZONTAL);
        cycleWaitRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams cycleWaitRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        cycleWaitRowLp.topMargin = dp(8);
        cycleWaitRow.setLayoutParams(cycleWaitRowLp);
        container.addView(cycleWaitRow);

        final EditText cycleWaitInput = new EditText(this);
        cycleWaitInput.setSingleLine(true);
        cycleWaitInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        cycleWaitInput.setHint("10");
        cycleWaitInput.setText(String.valueOf(ModuleSettings.getTogetherCycleWaitSeconds(prefs)));
        LinearLayout.LayoutParams cycleWaitInputLp = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        cycleWaitInputLp.weight = 1f;
        cycleWaitInput.setLayoutParams(cycleWaitInputLp);
        cycleWaitRow.addView(cycleWaitInput);

        Button cycleWaitSaveBtn = new Button(this);
        cycleWaitSaveBtn.setText("保存");
        LinearLayout.LayoutParams cycleWaitSaveLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        cycleWaitSaveLp.leftMargin = dp(10);
        cycleWaitSaveBtn.setLayoutParams(cycleWaitSaveLp);
        cycleWaitSaveBtn.setOnClickListener(v -> {
            String raw = String.valueOf(cycleWaitInput.getText()).trim();
            int waitSeconds;
            if (TextUtils.isEmpty(raw)) {
                waitSeconds = 0;
            } else {
                try {
                    waitSeconds = Integer.parseInt(raw);
                } catch (Throwable e) {
                    Toast.makeText(
                            ModuleSettingsActivity.this,
                            "请输入非负整数秒数",
                            Toast.LENGTH_SHORT
                    ).show();
                    return;
                }
            }
            if (waitSeconds < 0) {
                Toast.makeText(
                        ModuleSettingsActivity.this,
                        "请输入非负整数秒数",
                        Toast.LENGTH_SHORT
                ).show();
                return;
            }
            ModuleSettings.setTogetherCycleWaitSeconds(ModuleSettingsActivity.this, waitSeconds);
            cycleWaitInput.setText(String.valueOf(waitSeconds));
            Toast.makeText(
                    ModuleSettingsActivity.this,
                    "已设置循环后等待 " + waitSeconds + " 秒",
                    Toast.LENGTH_SHORT
            ).show();
        });
        cycleWaitRow.addView(cycleWaitSaveBtn);

        TextView cycleWaitHint = new TextView(this);
        cycleWaitHint.setText("每完成一次完整循环后，等待该秒数再启动下一轮；默认 10 秒。");
        cycleWaitHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams cycleWaitHintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        cycleWaitHintLp.topMargin = dp(6);
        cycleWaitHint.setLayoutParams(cycleWaitHintLp);
        container.addView(cycleWaitHint);

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

    private void openAccessibilitySettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Throwable e) {
            Toast.makeText(this, "打开无障碍设置失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private boolean isAccessibilityServiceEnabledCompat() {
        String enabled = Settings.Secure.getString(
                getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        );
        if (TextUtils.isEmpty(enabled)) {
            return false;
        }
        ComponentName target = new ComponentName(this, LookAccessibilityAdService.class);
        String expectFull = target.flattenToString();
        String expectShort = target.flattenToShortString();
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabled);
        for (String item : splitter) {
            String current = item == null ? "" : item.trim();
            if (expectFull.equalsIgnoreCase(current) || expectShort.equalsIgnoreCase(current)) {
                return true;
            }
        }
        return false;
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
