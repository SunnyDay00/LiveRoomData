package com.oodbye.shuangyulspmodule;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import android.content.Intent;
import android.provider.Settings;
import android.net.Uri;
import android.os.Build;
import android.content.ComponentName;
import android.text.TextUtils;

/**
 * 模块设置界面（程序化构建 UI）。
 */
public class ModuleSettingsActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ModuleSettings.ensureDefaults(this);
        final SharedPreferences prefs = ModuleSettings.appPrefs(this);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(Color.parseColor("#F5F5F5"));

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(16), dp(16), dp(16), dp(32));

        // ════════════════════════ 标题 ════════════════════════
        TextView title = new TextView(this);
        title.setText("双鱼直播数据采集 模块设置");
        title.setTextSize(20);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(Color.parseColor("#212121"));
        title.setPadding(0, 0, 0, dp(16));
        container.addView(title);

        // ════════════════════════ 基础功能 ════════════════════════
        addSectionHeader(container, "基础功能");

        // 全局悬浮按钮
        final Switch floatSwitch = addSwitch(container, "全局悬浮按钮",
                ModuleSettings.getGlobalFloatButtonEnabled(prefs));
        floatSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                ModuleSettings.setGlobalFloatButtonEnabled(ModuleSettingsActivity.this, isChecked);
                if (isChecked) {
                    ensureOverlayPermissionIfNeeded();
                    FloatServiceBootstrap.startFloatService(ModuleSettingsActivity.this);
                    Toast.makeText(ModuleSettingsActivity.this, "已开启全局悬浮按钮", Toast.LENGTH_SHORT).show();
                } else {
                    FloatServiceBootstrap.stopFloatService(ModuleSettingsActivity.this);
                    Toast.makeText(ModuleSettingsActivity.this, "已关闭全局悬浮按钮", Toast.LENGTH_SHORT).show();
                }
            }
        });

        // 悬浮信息窗口
        final Switch infoSwitch = addSwitch(container, "悬浮信息窗口",
                ModuleSettings.getFloatInfoWindowEnabled(prefs));
        infoSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                ModuleSettings.setFloatInfoWindowEnabled(ModuleSettingsActivity.this, isChecked);
                if (ModuleSettings.getGlobalFloatButtonEnabled(ModuleSettings.appPrefs(ModuleSettingsActivity.this))
                        && canDrawOverlaysCompat()) {
                    FloatServiceBootstrap.startFloatService(ModuleSettingsActivity.this);
                }
                Toast.makeText(ModuleSettingsActivity.this,
                        isChecked ? "已开启悬浮信息窗口" : "已关闭悬浮信息窗口",
                        Toast.LENGTH_SHORT).show();
            }
        });

        // 广告处理
        final Switch adSwitch = addSwitch(container, "广告处理",
                ModuleSettings.getAdProcessEnabled(prefs));
        adSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                ModuleSettings.setAdProcessEnabled(ModuleSettingsActivity.this, isChecked);
            }
        });

        // 无障碍实时广告处理（替代LSP）
        final Switch accAdSwitch = addSwitch(container, "无障碍实时广告处理（替代LSP）",
                ModuleSettings.getAccessibilityAdServiceEnabled(prefs));
        accAdSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                ModuleSettings.setAccessibilityAdServiceEnabled(ModuleSettingsActivity.this, isChecked);
                if (!isChecked) {
                    Toast.makeText(ModuleSettingsActivity.this, "已切换为普通广告处理", Toast.LENGTH_SHORT).show();
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

        // View树调试输出
        final Switch debugSwitch = addSwitch(container, "View树调试输出（仅调试）",
                ModuleSettings.getViewTreeDebugEnabled(prefs));
        debugSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                ModuleSettings.setViewTreeDebugEnabled(ModuleSettingsActivity.this, isChecked);
            }
        });

        // ════════════════════════ 采集设置 ════════════════════════
        addSectionHeader(container, "采集设置");

        // 需采集的榜单
        addSubHeader(container, "需采集的榜单");

        final Switch goddessSwitch = addSwitch(container, "女神",
                ModuleSettings.getRankGoddessEnabled(prefs));
        goddessSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                ModuleSettings.setRankGoddessEnabled(ModuleSettingsActivity.this, isChecked);
            }
        });

        final Switch godSwitch = addSwitch(container, "男神",
                ModuleSettings.getRankGodEnabled(prefs));
        godSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                ModuleSettings.setRankGodEnabled(ModuleSettingsActivity.this, isChecked);
            }
        });

        final Switch singSwitch = addSwitch(container, "点唱",
                ModuleSettings.getRankSingEnabled(prefs));
        singSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                ModuleSettings.setRankSingEnabled(ModuleSettingsActivity.this, isChecked);
            }
        });

        // 财富等级要求
        addSubHeader(container, "财富等级要求（0=不限）");
        final EditText wealthInput = addNumberInput(container,
                String.valueOf(ModuleSettings.getMinWealthLevel(prefs)));

        // 魅力等级要求
        addSubHeader(container, "魅力等级要求（0=不限）");
        final EditText charmInput = addNumberInput(container,
                String.valueOf(ModuleSettings.getMinCharmLevel(prefs)));

        // 忽略用户ID
        addSubHeader(container, "忽略用户ID（逗号分隔）");
        final EditText ignoreInput = addTextInput(container,
                ModuleSettings.getIgnoreUserIds(prefs));

        // 循环次数
        addSubHeader(container, "循环次数");
        final EditText cycleLimitInput = addNumberInput(container,
                String.valueOf(ModuleSettings.getCycleLimit(prefs)));

        // 保存采集设置按钮
        Button saveCollectBtn = new Button(this);
        saveCollectBtn.setText("保存采集设置");
        saveCollectBtn.setBackgroundColor(Color.parseColor("#4CAF50"));
        saveCollectBtn.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams saveBtnParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        saveBtnParams.topMargin = dp(12);
        saveCollectBtn.setLayoutParams(saveBtnParams);
        saveCollectBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    int wealth = parseNonNegativeInt(wealthInput.getText().toString(), 0);
                    int charm = parseNonNegativeInt(charmInput.getText().toString(), 0);
                    int cycleLimit = parseNonNegativeInt(cycleLimitInput.getText().toString(), 1);
                    if (cycleLimit < 1) cycleLimit = 1;
                    ModuleSettings.setMinWealthLevel(ModuleSettingsActivity.this, wealth);
                    ModuleSettings.setMinCharmLevel(ModuleSettingsActivity.this, charm);
                    ModuleSettings.setIgnoreUserIds(ModuleSettingsActivity.this,
                            ignoreInput.getText().toString().trim());
                    ModuleSettings.setCycleLimit(ModuleSettingsActivity.this, cycleLimit);
                    Toast.makeText(ModuleSettingsActivity.this,
                            "采集设置已保存", Toast.LENGTH_SHORT).show();
                } catch (Throwable e) {
                    Toast.makeText(ModuleSettingsActivity.this,
                            "保存失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }
        });
        container.addView(saveCollectBtn);

        // ════════════════════════ 飞书 Webhook ════════════════════════
        addSectionHeader(container, "飞书 Webhook 机器人配置");

        final Switch feishuSwitch = addSwitch(container, "启用飞书推送",
                ModuleSettings.getFeishuPushEnabled(prefs));
        feishuSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                ModuleSettings.setFeishuPushEnabled(ModuleSettingsActivity.this, isChecked);
            }
        });

        addSubHeader(container, "Webhook URL");
        final EditText webhookInput = addTextInput(container,
                ModuleSettings.getFeishuWebhookUrl(prefs));

        addSubHeader(container, "签名密钥（选填）");
        final EditText signInput = addTextInput(container,
                ModuleSettings.getFeishuSignSecret(prefs));

        // 保存飞书配置
        Button saveFeishuBtn = new Button(this);
        saveFeishuBtn.setText("保存飞书配置");
        saveFeishuBtn.setBackgroundColor(Color.parseColor("#2196F3"));
        saveFeishuBtn.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams feishuBtnParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        feishuBtnParams.topMargin = dp(12);
        saveFeishuBtn.setLayoutParams(feishuBtnParams);
        saveFeishuBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ModuleSettings.setFeishuWebhookUrl(ModuleSettingsActivity.this,
                        webhookInput.getText().toString().trim());
                ModuleSettings.setFeishuSignSecret(ModuleSettingsActivity.this,
                        signInput.getText().toString().trim());
                Toast.makeText(ModuleSettingsActivity.this,
                        "飞书配置已保存", Toast.LENGTH_SHORT).show();
            }
        });
        container.addView(saveFeishuBtn);

        // 测试飞书连接
        Button testFeishuBtn = new Button(this);
        testFeishuBtn.setText("测试飞书连接");
        testFeishuBtn.setBackgroundColor(Color.parseColor("#FF9800"));
        testFeishuBtn.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams testBtnParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        testBtnParams.topMargin = dp(8);
        testFeishuBtn.setLayoutParams(testBtnParams);
        testFeishuBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final String url = webhookInput.getText().toString().trim();
                final String secret = signInput.getText().toString().trim();
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final FeishuWebhookSender.SendResult result =
                                FeishuWebhookSender.probeWebhook(
                                        ModuleSettingsActivity.this, url, secret);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                String msg = result.success
                                        ? "飞书连接成功"
                                        : "飞书连接失败: " + result.detail;
                                Toast.makeText(ModuleSettingsActivity.this,
                                        msg, Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                }).start();
            }
        });
        container.addView(testFeishuBtn);

        // ════════════════════════ 完成 ════════════════════════
        scrollView.addView(container);
        setContentView(scrollView);
    }

    // ─────────────────────── UI 工具方法 ───────────────────────

    private void addSectionHeader(LinearLayout container, String text) {
        TextView header = new TextView(this);
        header.setText(text);
        header.setTextSize(16);
        header.setTypeface(null, Typeface.BOLD);
        header.setTextColor(Color.parseColor("#1565C0"));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(24);
        params.bottomMargin = dp(8);
        header.setLayoutParams(params);
        container.addView(header);

        // 分隔线
        View divider = new View(this);
        divider.setBackgroundColor(Color.parseColor("#1565C0"));
        divider.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(2)));
        container.addView(divider);
    }

    private void addSubHeader(LinearLayout container, String text) {
        TextView header = new TextView(this);
        header.setText(text);
        header.setTextSize(14);
        header.setTextColor(Color.parseColor("#616161"));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(12);
        params.bottomMargin = dp(4);
        header.setLayoutParams(params);
        container.addView(header);
    }

    private Switch addSwitch(LinearLayout container, String label, boolean checked) {
        Switch s = new Switch(this);
        s.setText(label);
        s.setChecked(checked);
        s.setTextSize(15);
        s.setTextColor(Color.parseColor("#212121"));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        s.setLayoutParams(params);
        s.setPadding(dp(4), dp(8), dp(4), dp(8));
        container.addView(s);
        return s;
    }

    private EditText addTextInput(LinearLayout container, String value) {
        EditText et = new EditText(this);
        et.setText(value);
        et.setTextSize(14);
        et.setSingleLine(true);
        et.setBackgroundColor(Color.WHITE);
        et.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(4);
        et.setLayoutParams(params);
        container.addView(et);
        return et;
    }

    private EditText addNumberInput(LinearLayout container, String value) {
        EditText et = addTextInput(container, value);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        return et;
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return (int) (value * density + 0.5f);
    }

    private static int parseNonNegativeInt(String text, int defaultValue) {
        if (text == null || text.trim().isEmpty()) return defaultValue;
        try {
            return Math.max(0, Integer.parseInt(text.trim()));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    // ─────────────────────── 权限与设置跳转 ───────────────────────

    private void ensureOverlayPermissionIfNeeded() {
        if (!canDrawOverlaysCompat()) {
            Toast.makeText(this, "请授予悬浮窗权限", Toast.LENGTH_LONG).show();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private boolean canDrawOverlaysCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(this);
        }
        return true;
    }

    private boolean isAccessibilityServiceEnabledCompat() {
        int accessibilityEnabled = 0;
        final String service = getPackageName() + "/" + ShuangyuAccessibilityAdService.class.getCanonicalName();
        try {
            accessibilityEnabled = Settings.Secure.getInt(
                    getApplicationContext().getContentResolver(),
                    android.provider.Settings.Secure.ACCESSIBILITY_ENABLED);
        } catch (Settings.SettingNotFoundException e) {
            e.printStackTrace();
        }
        if (accessibilityEnabled == 1) {
            String settingValue = Settings.Secure.getString(
                    getApplicationContext().getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (settingValue != null) {
                TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
                splitter.setString(settingValue);
                while (splitter.hasNext()) {
                    String accessibilityService = splitter.next();
                    if (accessibilityService.equalsIgnoreCase(service)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void openAccessibilitySettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
        } catch (Throwable e) {
            e.printStackTrace();
            Toast.makeText(this, "无法打开无障碍设置", Toast.LENGTH_SHORT).show();
        }
    }
}
