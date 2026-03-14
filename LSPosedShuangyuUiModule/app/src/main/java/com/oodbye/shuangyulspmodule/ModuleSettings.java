package com.oodbye.shuangyulspmodule;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;

import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XSharedPreferences;

/**
 * 模块设置集中管理（SharedPreferences + XSharedPreferences）。
 */
final class ModuleSettings {
    private static final String TAG = "SYLspModule";
    static final String MODULE_PACKAGE = "com.oodbye.shuangyulspmodule";
    static final String PREFS_NAME = "module_settings";

    // ─────────────────────── XSharedPreferences ───────────────────────
    private static XSharedPreferences sXsp;

    private ModuleSettings() {
    }

    static synchronized XSharedPreferences getXsp() {
        if (sXsp == null) {
            sXsp = new XSharedPreferences(MODULE_PACKAGE, PREFS_NAME);
            sXsp.makeWorldReadable();
        }
        sXsp.reload();
        return sXsp;
    }

    static SharedPreferences appPrefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // ─────────────────────── 全局悬浮按钮 ───────────────────────
    static final String KEY_GLOBAL_FLOAT_BUTTON_ENABLED = "global_float_button_enabled";
    static final boolean DEFAULT_GLOBAL_FLOAT_BUTTON_ENABLED = false;

    static boolean getGlobalFloatButtonEnabled(SharedPreferences prefs) {
        return prefs.getBoolean(KEY_GLOBAL_FLOAT_BUTTON_ENABLED, DEFAULT_GLOBAL_FLOAT_BUTTON_ENABLED);
    }

    static void setGlobalFloatButtonEnabled(Context context, boolean enabled) {
        appPrefs(context).edit()
                .putBoolean(KEY_GLOBAL_FLOAT_BUTTON_ENABLED, enabled)
                .apply();
    }

    // ─────────────────────── 悬浮信息窗口 ───────────────────────
    static final String KEY_FLOAT_INFO_WINDOW_ENABLED = "float_info_window_enabled";
    static final boolean DEFAULT_FLOAT_INFO_WINDOW_ENABLED = false;

    static boolean getFloatInfoWindowEnabled(SharedPreferences prefs) {
        return prefs.getBoolean(KEY_FLOAT_INFO_WINDOW_ENABLED, DEFAULT_FLOAT_INFO_WINDOW_ENABLED);
    }

    static void setFloatInfoWindowEnabled(Context context, boolean enabled) {
        appPrefs(context).edit()
                .putBoolean(KEY_FLOAT_INFO_WINDOW_ENABLED, enabled)
                .apply();
    }

    // ─────────────────────── 广告处理 ───────────────────────
    static final String KEY_AD_PROCESS_ENABLED = "ad_process_enabled";
    static final boolean DEFAULT_AD_PROCESS_ENABLED = true;

    static boolean isAdProcessEnabled() {
        XSharedPreferences xsp = getXsp();
        if (xsp == null) return DEFAULT_AD_PROCESS_ENABLED;
        return xsp.getBoolean(KEY_AD_PROCESS_ENABLED, DEFAULT_AD_PROCESS_ENABLED);
    }

    static boolean getAdProcessEnabled(SharedPreferences prefs) {
        return prefs.getBoolean(KEY_AD_PROCESS_ENABLED, DEFAULT_AD_PROCESS_ENABLED);
    }

    static void setAdProcessEnabled(Context context, boolean enabled) {
        appPrefs(context).edit()
                .putBoolean(KEY_AD_PROCESS_ENABLED, enabled)
                .apply();
    }

    // ─────────────────────── 无障碍广告服务 ───────────────────────
    static final String KEY_ACCESSIBILITY_AD_SERVICE_ENABLED = "accessibility_ad_service_enabled";
    static final boolean DEFAULT_ACCESSIBILITY_AD_SERVICE_ENABLED = true;

    static boolean isAccessibilityAdServiceEnabled() {
        XSharedPreferences xsp = getXsp();
        if (xsp == null) return DEFAULT_ACCESSIBILITY_AD_SERVICE_ENABLED;
        return xsp.getBoolean(KEY_ACCESSIBILITY_AD_SERVICE_ENABLED, DEFAULT_ACCESSIBILITY_AD_SERVICE_ENABLED);
    }

    static boolean getAccessibilityAdServiceEnabled(SharedPreferences prefs) {
        return prefs.getBoolean(KEY_ACCESSIBILITY_AD_SERVICE_ENABLED, DEFAULT_ACCESSIBILITY_AD_SERVICE_ENABLED);
    }

    static void setAccessibilityAdServiceEnabled(Context context, boolean enabled) {
        appPrefs(context).edit()
                .putBoolean(KEY_ACCESSIBILITY_AD_SERVICE_ENABLED, enabled)
                .apply();
    }

    // ─────────────────────── View 树调试 ───────────────────────
    static final String KEY_VIEW_TREE_DEBUG_ENABLED = "view_tree_debug_enabled";
    static final boolean DEFAULT_VIEW_TREE_DEBUG_ENABLED = false;

    static boolean isViewTreeDebugEnabled() {
        XSharedPreferences xsp = getXsp();
        if (xsp == null) return DEFAULT_VIEW_TREE_DEBUG_ENABLED;
        return xsp.getBoolean(KEY_VIEW_TREE_DEBUG_ENABLED, DEFAULT_VIEW_TREE_DEBUG_ENABLED);
    }

    static boolean getViewTreeDebugEnabled(SharedPreferences prefs) {
        return prefs.getBoolean(KEY_VIEW_TREE_DEBUG_ENABLED, DEFAULT_VIEW_TREE_DEBUG_ENABLED);
    }

    static void setViewTreeDebugEnabled(Context context, boolean enabled) {
        appPrefs(context).edit()
                .putBoolean(KEY_VIEW_TREE_DEBUG_ENABLED, enabled)
                .apply();
    }

    // ─────────────────────── 飞书 Webhook ───────────────────────
    static final String KEY_FEISHU_PUSH_ENABLED = "feishu_push_enabled";
    static final boolean DEFAULT_FEISHU_PUSH_ENABLED = false;
    static final String KEY_FEISHU_WEBHOOK_URL = "feishu_webhook_url";
    static final String DEFAULT_FEISHU_WEBHOOK_URL = "";
    static final String KEY_FEISHU_SIGN_SECRET = "feishu_sign_secret";
    static final String DEFAULT_FEISHU_SIGN_SECRET = "";
    static final String KEY_FEISHU_PUSH_MIN_CONSUME = "feishu_push_min_consume";
    static final int DEFAULT_FEISHU_PUSH_MIN_CONSUME = 0;

    static boolean isFeishuPushEnabled() {
        XSharedPreferences xsp = getXsp();
        if (xsp == null) return DEFAULT_FEISHU_PUSH_ENABLED;
        return xsp.getBoolean(KEY_FEISHU_PUSH_ENABLED, DEFAULT_FEISHU_PUSH_ENABLED);
    }

    static boolean getFeishuPushEnabled(SharedPreferences prefs) {
        return prefs.getBoolean(KEY_FEISHU_PUSH_ENABLED, DEFAULT_FEISHU_PUSH_ENABLED);
    }

    static void setFeishuPushEnabled(Context context, boolean enabled) {
        appPrefs(context).edit()
                .putBoolean(KEY_FEISHU_PUSH_ENABLED, enabled)
                .apply();
    }

    static String getFeishuWebhookUrl() {
        XSharedPreferences xsp = getXsp();
        if (xsp == null) return DEFAULT_FEISHU_WEBHOOK_URL;
        return safeTrim(xsp.getString(KEY_FEISHU_WEBHOOK_URL, DEFAULT_FEISHU_WEBHOOK_URL));
    }

    static String getFeishuWebhookUrl(SharedPreferences prefs) {
        return safeTrim(prefs.getString(KEY_FEISHU_WEBHOOK_URL, DEFAULT_FEISHU_WEBHOOK_URL));
    }

    static void setFeishuWebhookUrl(Context context, String url) {
        appPrefs(context).edit()
                .putString(KEY_FEISHU_WEBHOOK_URL, safeTrim(url))
                .apply();
    }

    static String getFeishuSignSecret() {
        XSharedPreferences xsp = getXsp();
        if (xsp == null) return DEFAULT_FEISHU_SIGN_SECRET;
        return safeTrim(xsp.getString(KEY_FEISHU_SIGN_SECRET, DEFAULT_FEISHU_SIGN_SECRET));
    }

    static String getFeishuSignSecret(SharedPreferences prefs) {
        return safeTrim(prefs.getString(KEY_FEISHU_SIGN_SECRET, DEFAULT_FEISHU_SIGN_SECRET));
    }

    static void setFeishuSignSecret(Context context, String secret) {
        appPrefs(context).edit()
                .putString(KEY_FEISHU_SIGN_SECRET, safeTrim(secret))
                .apply();
    }

    static int getFeishuPushMinConsume() {
        XSharedPreferences xsp = getXsp();
        if (xsp == null) return DEFAULT_FEISHU_PUSH_MIN_CONSUME;
        return Math.max(0, xsp.getInt(KEY_FEISHU_PUSH_MIN_CONSUME, DEFAULT_FEISHU_PUSH_MIN_CONSUME));
    }

    static int getFeishuPushMinConsume(SharedPreferences prefs) {
        return Math.max(0, prefs.getInt(KEY_FEISHU_PUSH_MIN_CONSUME, DEFAULT_FEISHU_PUSH_MIN_CONSUME));
    }

    // ─────────────────────── AI 分析 ───────────────────────
    static final String KEY_AI_ANALYSIS_ENABLED = "ai_analysis_enabled";
    static final boolean DEFAULT_AI_ANALYSIS_ENABLED = false;
    static final String KEY_AI_API_URL = "ai_api_url";
    static final String DEFAULT_AI_API_URL = "";
    static final String KEY_AI_API_KEY = "ai_api_key";
    static final String DEFAULT_AI_API_KEY = "";
    static final String KEY_AI_MODEL = "ai_model";
    static final String DEFAULT_AI_MODEL = "";

    static boolean isAiAnalysisEnabled() {
        XSharedPreferences xsp = getXsp();
        if (xsp == null) return DEFAULT_AI_ANALYSIS_ENABLED;
        return xsp.getBoolean(KEY_AI_ANALYSIS_ENABLED, DEFAULT_AI_ANALYSIS_ENABLED);
    }

    static String getAiApiUrl() {
        XSharedPreferences xsp = getXsp();
        if (xsp == null) return DEFAULT_AI_API_URL;
        return safeTrim(xsp.getString(KEY_AI_API_URL, DEFAULT_AI_API_URL));
    }

    static String getAiApiKey() {
        XSharedPreferences xsp = getXsp();
        if (xsp == null) return DEFAULT_AI_API_KEY;
        return safeTrim(xsp.getString(KEY_AI_API_KEY, DEFAULT_AI_API_KEY));
    }

    static String getAiModel() {
        XSharedPreferences xsp = getXsp();
        if (xsp == null) return DEFAULT_AI_MODEL;
        return safeTrim(xsp.getString(KEY_AI_MODEL, DEFAULT_AI_MODEL));
    }

    // ═══════════════════════ 新增：采集设置 ═══════════════════════

    // ─────────────────────── 需采集的榜单 ───────────────────────
    static final String KEY_RANK_GODDESS_ENABLED = "rank_goddess_enabled";
    static final boolean DEFAULT_RANK_GODDESS_ENABLED = true;
    static final String KEY_RANK_GOD_ENABLED = "rank_god_enabled";
    static final boolean DEFAULT_RANK_GOD_ENABLED = false;
    static final String KEY_RANK_SING_ENABLED = "rank_sing_enabled";
    static final boolean DEFAULT_RANK_SING_ENABLED = false;

    static boolean isRankGoddessEnabled() {
        XSharedPreferences xsp = getXsp();
        if (xsp == null) return DEFAULT_RANK_GODDESS_ENABLED;
        return xsp.getBoolean(KEY_RANK_GODDESS_ENABLED, DEFAULT_RANK_GODDESS_ENABLED);
    }

    static boolean getRankGoddessEnabled(SharedPreferences prefs) {
        return prefs.getBoolean(KEY_RANK_GODDESS_ENABLED, DEFAULT_RANK_GODDESS_ENABLED);
    }

    static void setRankGoddessEnabled(Context context, boolean enabled) {
        appPrefs(context).edit()
                .putBoolean(KEY_RANK_GODDESS_ENABLED, enabled)
                .apply();
    }

    static boolean isRankGodEnabled() {
        XSharedPreferences xsp = getXsp();
        if (xsp == null) return DEFAULT_RANK_GOD_ENABLED;
        return xsp.getBoolean(KEY_RANK_GOD_ENABLED, DEFAULT_RANK_GOD_ENABLED);
    }

    static boolean getRankGodEnabled(SharedPreferences prefs) {
        return prefs.getBoolean(KEY_RANK_GOD_ENABLED, DEFAULT_RANK_GOD_ENABLED);
    }

    static void setRankGodEnabled(Context context, boolean enabled) {
        appPrefs(context).edit()
                .putBoolean(KEY_RANK_GOD_ENABLED, enabled)
                .apply();
    }

    static boolean isRankSingEnabled() {
        XSharedPreferences xsp = getXsp();
        if (xsp == null) return DEFAULT_RANK_SING_ENABLED;
        return xsp.getBoolean(KEY_RANK_SING_ENABLED, DEFAULT_RANK_SING_ENABLED);
    }

    static boolean getRankSingEnabled(SharedPreferences prefs) {
        return prefs.getBoolean(KEY_RANK_SING_ENABLED, DEFAULT_RANK_SING_ENABLED);
    }

    static void setRankSingEnabled(Context context, boolean enabled) {
        appPrefs(context).edit()
                .putBoolean(KEY_RANK_SING_ENABLED, enabled)
                .apply();
    }

    // ─────────────────────── 财富等级要求 ───────────────────────
    static final String KEY_MIN_WEALTH_LEVEL = "min_wealth_level";
    static final int DEFAULT_MIN_WEALTH_LEVEL = 0;

    static int getMinWealthLevel() {
        XSharedPreferences xsp = getXsp();
        if (xsp == null) return DEFAULT_MIN_WEALTH_LEVEL;
        return Math.max(0, xsp.getInt(KEY_MIN_WEALTH_LEVEL, DEFAULT_MIN_WEALTH_LEVEL));
    }

    static int getMinWealthLevel(SharedPreferences prefs) {
        return Math.max(0, prefs.getInt(KEY_MIN_WEALTH_LEVEL, DEFAULT_MIN_WEALTH_LEVEL));
    }

    static void setMinWealthLevel(Context context, int level) {
        appPrefs(context).edit()
                .putInt(KEY_MIN_WEALTH_LEVEL, Math.max(0, level))
                .apply();
    }

    // ─────────────────────── 魅力等级要求 ───────────────────────
    static final String KEY_MIN_CHARM_LEVEL = "min_charm_level";
    static final int DEFAULT_MIN_CHARM_LEVEL = 0;

    static int getMinCharmLevel() {
        XSharedPreferences xsp = getXsp();
        if (xsp == null) return DEFAULT_MIN_CHARM_LEVEL;
        return Math.max(0, xsp.getInt(KEY_MIN_CHARM_LEVEL, DEFAULT_MIN_CHARM_LEVEL));
    }

    static int getMinCharmLevel(SharedPreferences prefs) {
        return Math.max(0, prefs.getInt(KEY_MIN_CHARM_LEVEL, DEFAULT_MIN_CHARM_LEVEL));
    }

    static void setMinCharmLevel(Context context, int level) {
        appPrefs(context).edit()
                .putInt(KEY_MIN_CHARM_LEVEL, Math.max(0, level))
                .apply();
    }

    // ─────────────────────── 忽略用户 ID ───────────────────────
    static final String KEY_IGNORE_USER_IDS = "ignore_user_ids";
    static final String DEFAULT_IGNORE_USER_IDS = "23084913";

    static String getIgnoreUserIds() {
        XSharedPreferences xsp = getXsp();
        if (xsp == null) return DEFAULT_IGNORE_USER_IDS;
        return safeTrim(xsp.getString(KEY_IGNORE_USER_IDS, DEFAULT_IGNORE_USER_IDS));
    }

    static String getIgnoreUserIds(SharedPreferences prefs) {
        return safeTrim(prefs.getString(KEY_IGNORE_USER_IDS, DEFAULT_IGNORE_USER_IDS));
    }

    static void setIgnoreUserIds(Context context, String ids) {
        appPrefs(context).edit()
                .putString(KEY_IGNORE_USER_IDS, safeTrim(ids))
                .apply();
    }

    /** 解析忽略用户 ID 列表 */
    static Set<String> parseIgnoreUserIdSet(String raw) {
        Set<String> set = new HashSet<>();
        if (TextUtils.isEmpty(raw)) return set;
        String[] parts = raw.split("[,，\\s]+");
        for (String part : parts) {
            String id = safeTrim(part);
            if (!TextUtils.isEmpty(id)) {
                set.add(id);
            }
        }
        return set;
    }

    // ─────────────────────── 引擎控制 ───────────────────────
    static final String KEY_ENGINE_STATUS = "engine_status";
    static final String KEY_ENGINE_COMMAND = "engine_command";
    static final String KEY_ENGINE_COMMAND_SEQ = "engine_command_seq";
    static final String KEY_RUNTIME_RUN_START_AT = "runtime_run_start_at";
    static final String KEY_RUNTIME_CYCLE_COMPLETED = "runtime_cycle_completed";
    static final String KEY_RUNTIME_CYCLE_ENTERED = "runtime_cycle_entered";
    static final String KEY_CYCLE_LIMIT = "cycle_limit";
    static final int DEFAULT_CYCLE_LIMIT = 1;

    static final String ENGINE_CMD_START = "start";
    static final String ENGINE_CMD_STOP = "stop";

    // Intent Actions
    static final String ACTION_SYNC_FLOAT_STATE = MODULE_PACKAGE + ".ACTION_SYNC_FLOAT_STATE";
    static final String ACTION_ENGINE_COMMAND = MODULE_PACKAGE + ".ACTION_ENGINE_COMMAND";
    static final String ACTION_ENGINE_STATUS_REPORT = MODULE_PACKAGE + ".ACTION_ENGINE_STATUS_REPORT";
    static final String ACTION_RUNTIME_STATS_REPORT = MODULE_PACKAGE + ".ACTION_RUNTIME_STATS_REPORT";
    static final String ACTION_CYCLE_COMPLETE_NOTICE = MODULE_PACKAGE + ".ACTION_CYCLE_COMPLETE_NOTICE";
    static final String ACTION_CYCLE_LIMIT_FINISHED = MODULE_PACKAGE + ".ACTION_CYCLE_LIMIT_FINISHED";
    static final String ACTION_AI_ANALYSIS_REQUEST = MODULE_PACKAGE + ".ACTION_AI_ANALYSIS_REQUEST";
    static final String ACTION_LEVEL_DATA_REPORT = MODULE_PACKAGE + ".ACTION_LEVEL_DATA_REPORT";

    // Intent Extras (Level Data)
    static final String EXTRA_LEVEL_DATA_JSON = "level_data_json";

    // Intent Extras
    static final String EXTRA_ENGINE_STATUS = "engine_status";
    static final String EXTRA_ENGINE_COMMAND = "engine_command";
    static final String EXTRA_ENGINE_COMMAND_SEQ = "engine_command_seq";
    static final String EXTRA_RUNTIME_RUN_START_AT = "runtime_run_start_at";
    static final String EXTRA_RUNTIME_CYCLE_COMPLETED = "runtime_cycle_completed";
    static final String EXTRA_RUNTIME_CYCLE_ENTERED = "runtime_cycle_entered";
    static final String EXTRA_RUNTIME_COMMAND_SEQ = "runtime_command_seq";
    static final String EXTRA_CYCLE_COMPLETE_MESSAGE = "cycle_complete_message";
    static final String EXTRA_FINISHED_CYCLES = "finished_cycles";
    static final String EXTRA_FINISHED_CYCLE_LIMIT = "finished_cycle_limit";
    static final String EXTRA_FINISHED_DURATION_MS = "finished_duration_ms";
    static final String EXTRA_TARGET_DISPLAY_ID = "target_display_id";
    static final String EXTRA_REQUEST_RESTART_TARGET_APP = "request_restart_target_app";
    static final String EXTRA_AI_ANALYZE_HOME_ID = "ai_analyze_home_id";
    static final String EXTRA_AI_ANALYZE_ENTER_TIME = "ai_analyze_enter_time";
    static final String EXTRA_AI_ANALYZE_CONTRIBUTION_CSV = "ai_analyze_contribution_csv";
    static final String EXTRA_AI_ANALYZE_CHARM_CSV = "ai_analyze_charm_csv";

    // ─────────────────────── 引擎状态枚举 ───────────────────────
    enum EngineStatus {
        STOPPED, STARTING, RUNNING, STOPPING, UNKNOWN
    }

    static EngineStatus parseStatusName(String name) {
        if (TextUtils.isEmpty(name)) return EngineStatus.UNKNOWN;
        try {
            return EngineStatus.valueOf(name.toUpperCase());
        } catch (Throwable e) {
            return EngineStatus.UNKNOWN;
        }
    }

    // ─────────────────────── 引擎状态读写 ───────────────────────
    static EngineStatus getEngineStatus(SharedPreferences prefs) {
        return parseStatusName(prefs.getString(KEY_ENGINE_STATUS, EngineStatus.STOPPED.name()));
    }

    static String getEngineCommand(SharedPreferences prefs) {
        return safeTrim(prefs.getString(KEY_ENGINE_COMMAND, ENGINE_CMD_STOP));
    }

    static long getEngineCommandSeq(SharedPreferences prefs) {
        return prefs.getLong(KEY_ENGINE_COMMAND_SEQ, 0L);
    }

    static int getRuntimeCycleCompleted(SharedPreferences prefs) {
        return Math.max(0, prefs.getInt(KEY_RUNTIME_CYCLE_COMPLETED, 0));
    }

    static int getRuntimeCycleEntered(SharedPreferences prefs) {
        return Math.max(0, prefs.getInt(KEY_RUNTIME_CYCLE_ENTERED, 0));
    }

    static int getCycleLimit(SharedPreferences prefs) {
        return Math.max(1, prefs.getInt(KEY_CYCLE_LIMIT, DEFAULT_CYCLE_LIMIT));
    }

    static void setCycleLimit(Context context, int limit) {
        appPrefs(context).edit()
                .putInt(KEY_CYCLE_LIMIT, Math.max(1, limit))
                .apply();
    }

    // ─────────────────────── 引擎操作 ───────────────────────
    static void syncEngineState(Context context, String command, EngineStatus status, long seq) {
        SharedPreferences.Editor editor = appPrefs(context).edit();
        editor.putString(KEY_ENGINE_STATUS, status.name());
        editor.putString(KEY_ENGINE_COMMAND, safeTrim(command));
        if (seq > 0L) {
            editor.putLong(KEY_ENGINE_COMMAND_SEQ, seq);
        }
        editor.apply();
    }

    static void syncRuntimeStats(Context context, long runStartAt,
                                  int cycleCompleted, int cycleEntered, long runtimeSeq) {
        SharedPreferences.Editor editor = appPrefs(context).edit();
        editor.putLong(KEY_RUNTIME_RUN_START_AT, Math.max(0L, runStartAt));
        editor.putInt(KEY_RUNTIME_CYCLE_COMPLETED, Math.max(0, cycleCompleted));
        editor.putInt(KEY_RUNTIME_CYCLE_ENTERED, Math.max(0, cycleEntered));
        if (runtimeSeq > 0L) {
            editor.putLong(KEY_ENGINE_COMMAND_SEQ, runtimeSeq);
        }
        editor.apply();
    }

    static long forceStopAndResetRuntime(Context context) {
        long seq = System.currentTimeMillis();
        SharedPreferences.Editor editor = appPrefs(context).edit();
        editor.putString(KEY_ENGINE_STATUS, EngineStatus.STOPPED.name());
        editor.putString(KEY_ENGINE_COMMAND, ENGINE_CMD_STOP);
        editor.putLong(KEY_ENGINE_COMMAND_SEQ, seq);
        editor.putLong(KEY_RUNTIME_RUN_START_AT, 0L);
        editor.putInt(KEY_RUNTIME_CYCLE_COMPLETED, 0);
        editor.putInt(KEY_RUNTIME_CYCLE_ENTERED, 0);
        editor.apply();
        return seq;
    }

    // ─────────────────────── 默认值初始化 ───────────────────────
    static void ensureDefaults(Context context) {
        SharedPreferences prefs = appPrefs(context);
        if (prefs.contains(KEY_GLOBAL_FLOAT_BUTTON_ENABLED)) {
            return;
        }
        SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean(KEY_GLOBAL_FLOAT_BUTTON_ENABLED, DEFAULT_GLOBAL_FLOAT_BUTTON_ENABLED);
        editor.putBoolean(KEY_FLOAT_INFO_WINDOW_ENABLED, DEFAULT_FLOAT_INFO_WINDOW_ENABLED);
        editor.putBoolean(KEY_AD_PROCESS_ENABLED, DEFAULT_AD_PROCESS_ENABLED);
        editor.putBoolean(KEY_ACCESSIBILITY_AD_SERVICE_ENABLED, DEFAULT_ACCESSIBILITY_AD_SERVICE_ENABLED);
        editor.putBoolean(KEY_VIEW_TREE_DEBUG_ENABLED, DEFAULT_VIEW_TREE_DEBUG_ENABLED);
        editor.putBoolean(KEY_FEISHU_PUSH_ENABLED, DEFAULT_FEISHU_PUSH_ENABLED);
        editor.putString(KEY_FEISHU_WEBHOOK_URL, DEFAULT_FEISHU_WEBHOOK_URL);
        editor.putString(KEY_FEISHU_SIGN_SECRET, DEFAULT_FEISHU_SIGN_SECRET);
        editor.putBoolean(KEY_RANK_GODDESS_ENABLED, DEFAULT_RANK_GODDESS_ENABLED);
        editor.putBoolean(KEY_RANK_GOD_ENABLED, DEFAULT_RANK_GOD_ENABLED);
        editor.putBoolean(KEY_RANK_SING_ENABLED, DEFAULT_RANK_SING_ENABLED);
        editor.putInt(KEY_MIN_WEALTH_LEVEL, DEFAULT_MIN_WEALTH_LEVEL);
        editor.putInt(KEY_MIN_CHARM_LEVEL, DEFAULT_MIN_CHARM_LEVEL);
        editor.putString(KEY_IGNORE_USER_IDS, DEFAULT_IGNORE_USER_IDS);
        editor.putInt(KEY_CYCLE_LIMIT, DEFAULT_CYCLE_LIMIT);
        editor.apply();
    }

    // ─────────────────────── 工具方法 ───────────────────────
    private static String safeTrim(String value) {
        if (value == null) return "";
        return value.trim();
    }
}
