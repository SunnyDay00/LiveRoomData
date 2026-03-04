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

        TextView contributionRankTitle = new TextView(this);
        contributionRankTitle.setText("贡献榜列表组循环点击次数");
        contributionRankTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        LinearLayout.LayoutParams contributionRankTitleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        contributionRankTitleLp.topMargin = dp(20);
        contributionRankTitle.setLayoutParams(contributionRankTitleLp);
        container.addView(contributionRankTitle);

        LinearLayout contributionRankRow = new LinearLayout(this);
        contributionRankRow.setOrientation(LinearLayout.HORIZONTAL);
        contributionRankRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams contributionRankRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        contributionRankRowLp.topMargin = dp(8);
        contributionRankRow.setLayoutParams(contributionRankRowLp);
        container.addView(contributionRankRow);

        final EditText contributionRankInput = new EditText(this);
        contributionRankInput.setSingleLine(true);
        contributionRankInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        contributionRankInput.setHint(String.valueOf(ModuleSettings.DEFAULT_CONTRIBUTION_RANK_LOOP_COUNT));
        contributionRankInput.setText(
                String.valueOf(ModuleSettings.getContributionRankLoopCount(prefs))
        );
        LinearLayout.LayoutParams contributionRankInputLp = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        contributionRankInputLp.weight = 1f;
        contributionRankInput.setLayoutParams(contributionRankInputLp);
        contributionRankRow.addView(contributionRankInput);

        Button contributionRankSaveBtn = new Button(this);
        contributionRankSaveBtn.setText("保存");
        LinearLayout.LayoutParams contributionRankSaveLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        contributionRankSaveLp.leftMargin = dp(10);
        contributionRankSaveBtn.setLayoutParams(contributionRankSaveLp);
        contributionRankSaveBtn.setOnClickListener(v -> {
            String raw = String.valueOf(contributionRankInput.getText()).trim();
            int count;
            if (TextUtils.isEmpty(raw)) {
                count = ModuleSettings.DEFAULT_CONTRIBUTION_RANK_LOOP_COUNT;
            } else {
                try {
                    count = Integer.parseInt(raw);
                } catch (Throwable e) {
                    Toast.makeText(
                            ModuleSettingsActivity.this,
                            "请输入非负整数",
                            Toast.LENGTH_SHORT
                    ).show();
                    return;
                }
            }
            if (count < 0) {
                Toast.makeText(
                        ModuleSettingsActivity.this,
                        "请输入非负整数",
                        Toast.LENGTH_SHORT
                ).show();
                return;
            }
            ModuleSettings.setContributionRankLoopCount(ModuleSettingsActivity.this, count);
            contributionRankInput.setText(String.valueOf(count));
            Toast.makeText(
                    ModuleSettingsActivity.this,
                    "已设置贡献榜循环点击次数为 " + count,
                    Toast.LENGTH_SHORT
            ).show();
        });
        contributionRankRow.addView(contributionRankSaveBtn);

        TextView contributionRankHint = new TextView(this);
        contributionRankHint.setText("按用户排名从 1 开始采集，默认 5。目标排名不存在时会尝试上滑继续采集。");
        contributionRankHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams contributionRankHintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        contributionRankHintLp.topMargin = dp(6);
        contributionRankHint.setLayoutParams(contributionRankHintLp);
        container.addView(contributionRankHint);

        TextView charmRankTitle = new TextView(this);
        charmRankTitle.setText("魅力榜列表组循环点击次数");
        charmRankTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        LinearLayout.LayoutParams charmRankTitleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        charmRankTitleLp.topMargin = dp(20);
        charmRankTitle.setLayoutParams(charmRankTitleLp);
        container.addView(charmRankTitle);

        LinearLayout charmRankRow = new LinearLayout(this);
        charmRankRow.setOrientation(LinearLayout.HORIZONTAL);
        charmRankRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams charmRankRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        charmRankRowLp.topMargin = dp(8);
        charmRankRow.setLayoutParams(charmRankRowLp);
        container.addView(charmRankRow);

        final EditText charmRankInput = new EditText(this);
        charmRankInput.setSingleLine(true);
        charmRankInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        charmRankInput.setHint(String.valueOf(ModuleSettings.DEFAULT_CHARM_RANK_LOOP_COUNT));
        charmRankInput.setText(String.valueOf(ModuleSettings.getCharmRankLoopCount(prefs)));
        LinearLayout.LayoutParams charmRankInputLp = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        charmRankInputLp.weight = 1f;
        charmRankInput.setLayoutParams(charmRankInputLp);
        charmRankRow.addView(charmRankInput);

        Button charmRankSaveBtn = new Button(this);
        charmRankSaveBtn.setText("保存");
        LinearLayout.LayoutParams charmRankSaveLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        charmRankSaveLp.leftMargin = dp(10);
        charmRankSaveBtn.setLayoutParams(charmRankSaveLp);
        charmRankSaveBtn.setOnClickListener(v -> {
            String raw = String.valueOf(charmRankInput.getText()).trim();
            int count;
            if (TextUtils.isEmpty(raw)) {
                count = ModuleSettings.DEFAULT_CHARM_RANK_LOOP_COUNT;
            } else {
                try {
                    count = Integer.parseInt(raw);
                } catch (Throwable e) {
                    Toast.makeText(
                            ModuleSettingsActivity.this,
                            "请输入非负整数",
                            Toast.LENGTH_SHORT
                    ).show();
                    return;
                }
            }
            if (count < 0) {
                Toast.makeText(
                        ModuleSettingsActivity.this,
                        "请输入非负整数",
                        Toast.LENGTH_SHORT
                ).show();
                return;
            }
            ModuleSettings.setCharmRankLoopCount(ModuleSettingsActivity.this, count);
            charmRankInput.setText(String.valueOf(count));
            Toast.makeText(
                    ModuleSettingsActivity.this,
                    "已设置魅力榜循环点击次数为 " + count,
                    Toast.LENGTH_SHORT
            ).show();
        });
        charmRankRow.addView(charmRankSaveBtn);

        TextView charmRankHint = new TextView(this);
        charmRankHint.setText("按用户排名从 1 开始采集，默认 20。目标排名不存在时会尝试上滑继续采集。");
        charmRankHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams charmRankHintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        charmRankHintLp.topMargin = dp(6);
        charmRankHint.setLayoutParams(charmRankHintLp);
        container.addView(charmRankHint);

        LinearLayout singleRankRetryRow = new LinearLayout(this);
        singleRankRetryRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams singleRankRetryRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        singleRankRetryRowLp.topMargin = dp(14);
        singleRankRetryRow.setLayoutParams(singleRankRetryRowLp);
        container.addView(singleRankRetryRow);

        TextView singleRankRetryLabel = new TextView(this);
        singleRankRetryLabel.setText("单榜重试上限/超时设置");
        singleRankRetryLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        LinearLayout.LayoutParams singleRankRetryLabelLp = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        singleRankRetryLabelLp.weight = 1f;
        singleRankRetryLabel.setLayoutParams(singleRankRetryLabelLp);
        singleRankRetryRow.addView(singleRankRetryLabel);

        final EditText singleRankRetryInput = new EditText(this);
        singleRankRetryInput.setSingleLine(true);
        singleRankRetryInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        singleRankRetryInput.setHint(String.valueOf(ModuleSettings.DEFAULT_SINGLE_RANK_RETRY_LIMIT));
        singleRankRetryInput.setText(String.valueOf(ModuleSettings.getSingleRankRetryLimit(prefs)));
        LinearLayout.LayoutParams singleRankRetryInputLp = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        singleRankRetryInputLp.weight = 1f;
        singleRankRetryInput.setLayoutParams(singleRankRetryInputLp);
        singleRankRetryRow.addView(singleRankRetryInput);

        Button singleRankRetrySaveBtn = new Button(this);
        singleRankRetrySaveBtn.setText("保存");
        LinearLayout.LayoutParams singleRankRetrySaveLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        singleRankRetrySaveLp.leftMargin = dp(10);
        singleRankRetrySaveBtn.setLayoutParams(singleRankRetrySaveLp);
        singleRankRetrySaveBtn.setOnClickListener(v -> {
            String raw = String.valueOf(singleRankRetryInput.getText()).trim();
            int count;
            if (TextUtils.isEmpty(raw)) {
                count = ModuleSettings.DEFAULT_SINGLE_RANK_RETRY_LIMIT;
            } else {
                try {
                    count = Integer.parseInt(raw);
                } catch (Throwable e) {
                    Toast.makeText(
                            ModuleSettingsActivity.this,
                            "请输入非负整数",
                            Toast.LENGTH_SHORT
                    ).show();
                    return;
                }
            }
            if (count < 0) {
                Toast.makeText(
                        ModuleSettingsActivity.this,
                        "请输入非负整数",
                        Toast.LENGTH_SHORT
                ).show();
                return;
            }
            ModuleSettings.setSingleRankRetryLimit(ModuleSettingsActivity.this, count);
            singleRankRetryInput.setText(String.valueOf(count));
            Toast.makeText(
                    ModuleSettingsActivity.this,
                    "已设置单榜重试上限为 " + count,
                    Toast.LENGTH_SHORT
            ).show();
        });
        singleRankRetryRow.addView(singleRankRetrySaveBtn);

        TextView singleRankRetryHint = new TextView(this);
        singleRankRetryHint.setText("默认 3。连续上滑无新榜单数据达到该次数时，判定该榜单数量不足并结束采集；同时用于限制单榜失败重试次数。");
        singleRankRetryHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams singleRankRetryHintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        singleRankRetryHintLp.topMargin = dp(6);
        singleRankRetryHint.setLayoutParams(singleRankRetryHintLp);
        container.addView(singleRankRetryHint);

        final Switch collectAllRankUsersSwitch = new Switch(this);
        collectAllRankUsersSwitch.setText("全量采集榜单用户数据");
        collectAllRankUsersSwitch.setChecked(ModuleSettings.getCollectAllRankUsersEnabled(prefs));
        LinearLayout.LayoutParams collectAllRankUsersLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        collectAllRankUsersLp.topMargin = dp(20);
        collectAllRankUsersSwitch.setLayoutParams(collectAllRankUsersLp);
        collectAllRankUsersSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                ModuleSettings.setCollectAllRankUsersEnabled(ModuleSettingsActivity.this, isChecked);
                Toast.makeText(
                        ModuleSettingsActivity.this,
                        isChecked ? "已开启全量采集榜单用户数据" : "已关闭全量采集榜单用户数据",
                        Toast.LENGTH_SHORT
                ).show();
            }
        });
        container.addView(collectAllRankUsersSwitch);

        TextView collectAllRankUsersHint = new TextView(this);
        collectAllRankUsersHint.setText("默认开启。开启后忽略贡献榜/魅力榜循环点击次数，持续上滑采集，直到连续无新榜单数据达到“单榜重试上限/超时设置”后判定榜单到底。");
        collectAllRankUsersHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams collectAllRankUsersHintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        collectAllRankUsersHintLp.topMargin = dp(6);
        collectAllRankUsersHint.setLayoutParams(collectAllRankUsersHintLp);
        container.addView(collectAllRankUsersHint);

        LinearLayout collectAllDataLimitRow = new LinearLayout(this);
        collectAllDataLimitRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams collectAllDataLimitRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        collectAllDataLimitRowLp.topMargin = dp(10);
        collectAllDataLimitRow.setLayoutParams(collectAllDataLimitRowLp);
        container.addView(collectAllDataLimitRow);

        TextView collectAllDataLimitLabel = new TextView(this);
        collectAllDataLimitLabel.setText("全量采集Data数值限制");
        collectAllDataLimitLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        LinearLayout.LayoutParams collectAllDataLimitLabelLp = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        collectAllDataLimitLabelLp.weight = 1f;
        collectAllDataLimitLabel.setLayoutParams(collectAllDataLimitLabelLp);
        collectAllDataLimitRow.addView(collectAllDataLimitLabel);

        final EditText collectAllDataLimitInput = new EditText(this);
        collectAllDataLimitInput.setSingleLine(true);
        collectAllDataLimitInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        collectAllDataLimitInput.setHint(String.valueOf(ModuleSettings.DEFAULT_COLLECT_ALL_RANK_DATA_LIMIT));
        collectAllDataLimitInput.setText(
                String.valueOf(ModuleSettings.getCollectAllRankDataLimit(prefs))
        );
        LinearLayout.LayoutParams collectAllDataLimitInputLp = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        collectAllDataLimitInputLp.weight = 1f;
        collectAllDataLimitInput.setLayoutParams(collectAllDataLimitInputLp);
        collectAllDataLimitRow.addView(collectAllDataLimitInput);

        Button collectAllDataLimitSaveBtn = new Button(this);
        collectAllDataLimitSaveBtn.setText("保存");
        LinearLayout.LayoutParams collectAllDataLimitSaveLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        collectAllDataLimitSaveLp.leftMargin = dp(10);
        collectAllDataLimitSaveBtn.setLayoutParams(collectAllDataLimitSaveLp);
        collectAllDataLimitSaveBtn.setOnClickListener(v -> {
            String raw = String.valueOf(collectAllDataLimitInput.getText()).trim();
            int limit;
            if (TextUtils.isEmpty(raw)) {
                limit = ModuleSettings.DEFAULT_COLLECT_ALL_RANK_DATA_LIMIT;
            } else {
                try {
                    limit = Integer.parseInt(raw);
                } catch (Throwable e) {
                    Toast.makeText(
                            ModuleSettingsActivity.this,
                            "请输入非负整数，0表示不限制",
                            Toast.LENGTH_SHORT
                    ).show();
                    return;
                }
            }
            if (limit < 0) {
                Toast.makeText(
                        ModuleSettingsActivity.this,
                        "请输入非负整数，0表示不限制",
                        Toast.LENGTH_SHORT
                ).show();
                return;
            }
            ModuleSettings.setCollectAllRankDataLimit(ModuleSettingsActivity.this, limit);
            collectAllDataLimitInput.setText(String.valueOf(limit));
            Toast.makeText(
                    ModuleSettingsActivity.this,
                    "已设置全量采集Data数值限制为 " + limit,
                    Toast.LENGTH_SHORT
            ).show();
        });
        collectAllDataLimitRow.addView(collectAllDataLimitSaveBtn);

        TextView collectAllDataLimitHint = new TextView(this);
        collectAllDataLimitHint.setText("仅在“全量采集榜单用户数据”开启时生效。默认 5000，0 表示不限制。会先将 Data 的“万”单位换算后再比较，小于限制值则结束当前榜单采集。");
        collectAllDataLimitHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams collectAllDataLimitHintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        collectAllDataLimitHintLp.topMargin = dp(6);
        collectAllDataLimitHint.setLayoutParams(collectAllDataLimitHintLp);
        container.addView(collectAllDataLimitHint);

        final Switch collectUserDetailSwitch = new Switch(this);
        collectUserDetailSwitch.setText("采集用户详细界面");
        collectUserDetailSwitch.setChecked(ModuleSettings.getCollectUserDetailEnabled(prefs));
        LinearLayout.LayoutParams collectUserDetailLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        collectUserDetailLp.topMargin = dp(16);
        collectUserDetailSwitch.setLayoutParams(collectUserDetailLp);
        collectUserDetailSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                ModuleSettings.setCollectUserDetailEnabled(ModuleSettingsActivity.this, isChecked);
                Toast.makeText(
                        ModuleSettingsActivity.this,
                        isChecked ? "已开启采集用户详细界面" : "已关闭采集用户详细界面",
                        Toast.LENGTH_SHORT
                ).show();
            }
        });
        container.addView(collectUserDetailSwitch);

        TextView collectUserDetailHint = new TextView(this);
        collectUserDetailHint.setText("开启：点击用户进入详情采集ID/IP/消费等；关闭：仅采集榜单列表可见数据，不进入详情。");
        collectUserDetailHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams collectUserDetailHintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        collectUserDetailHintLp.topMargin = dp(6);
        collectUserDetailHint.setLayoutParams(collectUserDetailHintLp);
        container.addView(collectUserDetailHint);

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
