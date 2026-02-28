package com.oodbye.lsposedchathook;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
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
        setTitle("LOOK Chat 设置");

        final SharedPreferences prefs = ModuleSettings.appPrefs(this);
        ModuleSettings.ensurePrefsReadable(this);

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
        title.setText("LSPosedChatUiCollector");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        container.addView(title);

        TextView desc = new TextView(this);
        desc.setText("修改设置后，直播页会在约1秒内读取到新配置。");
        desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        descLp.topMargin = dp(8);
        desc.setLayoutParams(descLp);
        container.addView(desc);

        final Switch defaultCaptureSwitch = new Switch(this);
        defaultCaptureSwitch.setText("聊天抓取默认状态：开启");
        defaultCaptureSwitch.setChecked(
                prefs.getBoolean(
                        ModuleSettings.KEY_DEFAULT_CAPTURE_ENABLED,
                        ModuleSettings.DEFAULT_CAPTURE_ENABLED
                )
        );
        LinearLayout.LayoutParams s1Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        s1Lp.topMargin = dp(20);
        defaultCaptureSwitch.setLayoutParams(s1Lp);
        defaultCaptureSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                prefs.edit().putBoolean(ModuleSettings.KEY_DEFAULT_CAPTURE_ENABLED, isChecked).commit();
                ModuleSettings.ensurePrefsReadable(ModuleSettingsActivity.this);
            }
        });
        container.addView(defaultCaptureSwitch);

        TextView s1Hint = new TextView(this);
        s1Hint.setText("关闭：进入直播间后默认“聊天抓取:关”\n开启：进入直播间后会自动尝试开启聊天抓取");
        s1Hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams s1HintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        s1HintLp.topMargin = dp(6);
        s1Hint.setLayoutParams(s1HintLp);
        container.addView(s1Hint);

        final Switch adSwitch = new Switch(this);
        adSwitch.setText("进入直播间自动处理全屏广告");
        adSwitch.setChecked(
                prefs.getBoolean(
                        ModuleSettings.KEY_AUTO_HANDLE_FULLSCREEN_AD,
                        ModuleSettings.DEFAULT_AUTO_HANDLE_FULLSCREEN_AD
                )
        );
        LinearLayout.LayoutParams s2Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        s2Lp.topMargin = dp(20);
        adSwitch.setLayoutParams(s2Lp);
        adSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                prefs.edit().putBoolean(ModuleSettings.KEY_AUTO_HANDLE_FULLSCREEN_AD, isChecked).commit();
                ModuleSettings.ensurePrefsReadable(ModuleSettingsActivity.this);
                if (!isChecked) {
                    Toast.makeText(ModuleSettingsActivity.this, "已关闭自动处理全屏广告", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(ModuleSettingsActivity.this, "已开启自动处理全屏广告", Toast.LENGTH_SHORT).show();
                }
            }
        });
        container.addView(adSwitch);

        TextView s2Hint = new TextView(this);
        s2Hint.setText("开启：检测到广告会自动执行返回\n关闭：完全不处理全屏广告");
        s2Hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams s2HintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        s2HintLp.topMargin = dp(6);
        s2Hint.setLayoutParams(s2HintLp);
        container.addView(s2Hint);

        final Switch parseSwitch = new Switch(this);
        parseSwitch.setText("聊天记录解析");
        parseSwitch.setChecked(
                prefs.getBoolean(
                        ModuleSettings.KEY_CHAT_PARSE_ENABLED,
                        ModuleSettings.DEFAULT_CHAT_PARSE_ENABLED
                )
        );
        LinearLayout.LayoutParams s3Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        s3Lp.topMargin = dp(20);
        parseSwitch.setLayoutParams(s3Lp);
        parseSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                prefs.edit().putBoolean(ModuleSettings.KEY_CHAT_PARSE_ENABLED, isChecked).commit();
                ModuleSettings.ensurePrefsReadable(ModuleSettingsActivity.this);
                if (isChecked) {
                    Toast.makeText(ModuleSettingsActivity.this, "已开启聊天记录解析", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(ModuleSettingsActivity.this, "已关闭聊天记录解析", Toast.LENGTH_SHORT).show();
                }
            }
        });
        container.addView(parseSwitch);

        TextView s3Hint = new TextView(this);
        s3Hint.setText("开启：按送礼规则解析并填充送礼字段\n关闭：仅保存时间与聊天记录，送礼字段留空");
        s3Hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams s3HintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        s3HintLp.topMargin = dp(6);
        s3Hint.setLayoutParams(s3HintLp);
        container.addView(s3Hint);

        final Switch blacklistSwitch = new Switch(this);
        blacklistSwitch.setText("屏蔽黑名单聊天记录");
        blacklistSwitch.setChecked(
                prefs.getBoolean(
                        ModuleSettings.KEY_BLOCK_BLACKLIST_ENABLED,
                        ModuleSettings.DEFAULT_BLOCK_BLACKLIST_ENABLED
                )
        );
        LinearLayout.LayoutParams s4Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        s4Lp.topMargin = dp(20);
        blacklistSwitch.setLayoutParams(s4Lp);
        blacklistSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                prefs.edit().putBoolean(ModuleSettings.KEY_BLOCK_BLACKLIST_ENABLED, isChecked).commit();
                ModuleSettings.ensurePrefsReadable(ModuleSettingsActivity.this);
                if (isChecked) {
                    Toast.makeText(ModuleSettingsActivity.this, "已开启黑名单屏蔽", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(ModuleSettingsActivity.this, "已关闭黑名单屏蔽", Toast.LENGTH_SHORT).show();
                }
            }
        });
        container.addView(blacklistSwitch);

        TextView s4Hint = new TextView(this);
        s4Hint.setText("开启：命中黑名单的记录不上传远程数据库\n关闭：不过滤黑名单");
        s4Hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams s4HintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        s4HintLp.topMargin = dp(6);
        s4Hint.setLayoutParams(s4HintLp);
        container.addView(s4Hint);

        TextView apiHint = new TextView(this);
        apiHint.setText("远程API/密钥已内置到模块中。\n将用于：数据库连通性检测、拉取礼物价格/黑名单、批量上传聊天记录。");
        apiHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams apiHintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        apiHintLp.topMargin = dp(20);
        apiHint.setLayoutParams(apiHintLp);
        container.addView(apiHint);

        setContentView(scrollView);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
