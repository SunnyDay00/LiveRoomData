package com.oodbye.looklspmodule;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;

import de.robv.android.xposed.XSharedPreferences;

final class ModuleSettings {
    static final String MODULE_PACKAGE = "com.oodbye.looklspmodule";
    static final String PREFS_NAME = "module_settings";

    static final String KEY_GLOBAL_FLOAT_BUTTON_ENABLED = "global_float_button_enabled";
    static final String KEY_AD_PROCESS_ENABLED = "ad_process_enabled";
    static final String KEY_ACCESSIBILITY_AD_SERVICE_ENABLED = "accessibility_ad_service_enabled";
    static final String KEY_AUTO_RUN_ON_APP_START_ENABLED = "auto_run_on_app_start_enabled";
    static final String KEY_TOGETHER_CYCLE_LIMIT = "together_cycle_limit";
    static final String KEY_TOGETHER_CYCLE_WAIT_SECONDS = "together_cycle_wait_seconds";
    static final String KEY_ENGINE_STATUS = "engine_status";
    static final String KEY_ENGINE_COMMAND = "engine_command";
    static final String KEY_ENGINE_COMMAND_SEQ = "engine_command_seq";
    static final String ACTION_ENGINE_COMMAND = MODULE_PACKAGE + ".ACTION_ENGINE_COMMAND";
    static final String ACTION_SYNC_FLOAT_SERVICE = MODULE_PACKAGE + ".ACTION_SYNC_FLOAT_SERVICE";
    static final String ACTION_ENGINE_STATUS_REPORT = MODULE_PACKAGE + ".ACTION_ENGINE_STATUS_REPORT";
    static final String ACTION_CYCLE_COMPLETE_NOTICE = MODULE_PACKAGE + ".ACTION_CYCLE_COMPLETE_NOTICE";
    static final String EXTRA_ENGINE_COMMAND = "engine_command";
    static final String EXTRA_ENGINE_STATUS = "engine_status";
    static final String EXTRA_ENGINE_COMMAND_SEQ = "engine_command_seq";
    static final String EXTRA_TARGET_DISPLAY_ID = "target_display_id";
    static final String EXTRA_REQUEST_RESTART_TARGET_APP = "request_restart_target_app";
    static final String EXTRA_CYCLE_COMPLETE_MESSAGE = "cycle_complete_message";

    static final String ENGINE_CMD_RUN = "RUN";
    static final String ENGINE_CMD_PAUSE = "PAUSE";
    static final String ENGINE_CMD_STOP = "STOP";

    static final boolean DEFAULT_GLOBAL_FLOAT_BUTTON_ENABLED = false;
    static final boolean DEFAULT_AD_PROCESS_ENABLED = true;
    static final boolean DEFAULT_ACCESSIBILITY_AD_SERVICE_ENABLED = true;
    static final boolean DEFAULT_AUTO_RUN_ON_APP_START_ENABLED = false;
    static final int DEFAULT_TOGETHER_CYCLE_LIMIT = 0;
    static final int DEFAULT_TOGETHER_CYCLE_WAIT_SECONDS = 0;
    static final String DEFAULT_ENGINE_STATUS = EngineStatus.STOPPED.name();

    private static final long XSP_RELOAD_INTERVAL_MS = 1000L;

    private static XSharedPreferences sXsp;
    private static long sLastReloadAt;

    private ModuleSettings() {
    }

    static SharedPreferences appPrefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    static void ensurePrefsReadable(Context context) {
        if (context == null) {
            return;
        }
        try {
            File spDir = new File(context.getApplicationInfo().dataDir, "shared_prefs");
            File spFile = new File(spDir, PREFS_NAME + ".xml");
            if (spDir.exists()) {
                spDir.setReadable(true, false);
                spDir.setExecutable(true, false);
            }
            if (spFile.exists()) {
                spFile.setReadable(true, false);
            }
        } catch (Throwable ignored) {
        }
    }

    static synchronized boolean isAdProcessEnabled() {
        XSharedPreferences xsp = getXsp();
        if (xsp == null) {
            return DEFAULT_AD_PROCESS_ENABLED;
        }
        return xsp.getBoolean(KEY_AD_PROCESS_ENABLED, DEFAULT_AD_PROCESS_ENABLED);
    }

    static synchronized boolean isAccessibilityAdServiceEnabled() {
        XSharedPreferences xsp = getXsp();
        if (xsp == null) {
            return DEFAULT_ACCESSIBILITY_AD_SERVICE_ENABLED;
        }
        return xsp.getBoolean(
                KEY_ACCESSIBILITY_AD_SERVICE_ENABLED,
                DEFAULT_ACCESSIBILITY_AD_SERVICE_ENABLED
        );
    }

    static synchronized boolean isAutoRunOnAppStartEnabled() {
        XSharedPreferences xsp = getXsp();
        if (xsp == null) {
            return DEFAULT_AUTO_RUN_ON_APP_START_ENABLED;
        }
        return xsp.getBoolean(
                KEY_AUTO_RUN_ON_APP_START_ENABLED,
                DEFAULT_AUTO_RUN_ON_APP_START_ENABLED
        );
    }

    static synchronized EngineStatus getEngineStatus() {
        XSharedPreferences xsp = getXsp();
        if (xsp == null) {
            return EngineStatus.STOPPED;
        }
        return parseStatus(xsp.getString(KEY_ENGINE_STATUS, DEFAULT_ENGINE_STATUS));
    }

    static synchronized int getTogetherCycleLimit() {
        XSharedPreferences xsp = getXsp();
        if (xsp == null) {
            return DEFAULT_TOGETHER_CYCLE_LIMIT;
        }
        return sanitizeNonNegativeInt(xsp.getInt(KEY_TOGETHER_CYCLE_LIMIT, DEFAULT_TOGETHER_CYCLE_LIMIT));
    }

    static synchronized int getTogetherCycleWaitSeconds() {
        XSharedPreferences xsp = getXsp();
        if (xsp == null) {
            return DEFAULT_TOGETHER_CYCLE_WAIT_SECONDS;
        }
        return sanitizeNonNegativeInt(
                xsp.getInt(KEY_TOGETHER_CYCLE_WAIT_SECONDS, DEFAULT_TOGETHER_CYCLE_WAIT_SECONDS)
        );
    }

    static synchronized String getEngineCommand() {
        XSharedPreferences xsp = getXsp();
        if (xsp == null) {
            return "";
        }
        return safeTrim(xsp.getString(KEY_ENGINE_COMMAND, ""));
    }

    static synchronized long getEngineCommandSeq() {
        XSharedPreferences xsp = getXsp();
        if (xsp == null) {
            return 0L;
        }
        return xsp.getLong(KEY_ENGINE_COMMAND_SEQ, 0L);
    }

    static synchronized boolean getGlobalFloatButtonEnabled(SharedPreferences prefs) {
        if (prefs == null) {
            return DEFAULT_GLOBAL_FLOAT_BUTTON_ENABLED;
        }
        return prefs.getBoolean(KEY_GLOBAL_FLOAT_BUTTON_ENABLED, DEFAULT_GLOBAL_FLOAT_BUTTON_ENABLED);
    }

    static synchronized boolean getAdProcessEnabled(SharedPreferences prefs) {
        if (prefs == null) {
            return DEFAULT_AD_PROCESS_ENABLED;
        }
        return prefs.getBoolean(KEY_AD_PROCESS_ENABLED, DEFAULT_AD_PROCESS_ENABLED);
    }

    static synchronized boolean getAccessibilityAdServiceEnabled(SharedPreferences prefs) {
        if (prefs == null) {
            return DEFAULT_ACCESSIBILITY_AD_SERVICE_ENABLED;
        }
        return prefs.getBoolean(
                KEY_ACCESSIBILITY_AD_SERVICE_ENABLED,
                DEFAULT_ACCESSIBILITY_AD_SERVICE_ENABLED
        );
    }

    static synchronized boolean getAutoRunOnAppStartEnabled(SharedPreferences prefs) {
        if (prefs == null) {
            return DEFAULT_AUTO_RUN_ON_APP_START_ENABLED;
        }
        return prefs.getBoolean(
                KEY_AUTO_RUN_ON_APP_START_ENABLED,
                DEFAULT_AUTO_RUN_ON_APP_START_ENABLED
        );
    }

    static synchronized EngineStatus getEngineStatus(SharedPreferences prefs) {
        if (prefs == null) {
            return EngineStatus.STOPPED;
        }
        return parseStatus(prefs.getString(KEY_ENGINE_STATUS, DEFAULT_ENGINE_STATUS));
    }

    static synchronized int getTogetherCycleLimit(SharedPreferences prefs) {
        if (prefs == null) {
            return DEFAULT_TOGETHER_CYCLE_LIMIT;
        }
        return sanitizeNonNegativeInt(
                prefs.getInt(KEY_TOGETHER_CYCLE_LIMIT, DEFAULT_TOGETHER_CYCLE_LIMIT)
        );
    }

    static synchronized int getTogetherCycleWaitSeconds(SharedPreferences prefs) {
        if (prefs == null) {
            return DEFAULT_TOGETHER_CYCLE_WAIT_SECONDS;
        }
        return sanitizeNonNegativeInt(
                prefs.getInt(KEY_TOGETHER_CYCLE_WAIT_SECONDS, DEFAULT_TOGETHER_CYCLE_WAIT_SECONDS)
        );
    }

    static synchronized String getEngineCommand(SharedPreferences prefs) {
        if (prefs == null) {
            return "";
        }
        return safeTrim(prefs.getString(KEY_ENGINE_COMMAND, ""));
    }

    static synchronized long getEngineCommandSeq(SharedPreferences prefs) {
        if (prefs == null) {
            return 0L;
        }
        return prefs.getLong(KEY_ENGINE_COMMAND_SEQ, 0L);
    }

    static synchronized void setGlobalFloatButtonEnabled(Context context, boolean enabled) {
        if (context == null) {
            return;
        }
        SharedPreferences prefs = appPrefs(context);
        prefs.edit().putBoolean(KEY_GLOBAL_FLOAT_BUTTON_ENABLED, enabled).commit();
        ensurePrefsReadable(context);
    }

    static synchronized void setAdProcessEnabled(Context context, boolean enabled) {
        if (context == null) {
            return;
        }
        SharedPreferences prefs = appPrefs(context);
        prefs.edit().putBoolean(KEY_AD_PROCESS_ENABLED, enabled).commit();
        ensurePrefsReadable(context);
    }

    static synchronized void setAccessibilityAdServiceEnabled(Context context, boolean enabled) {
        if (context == null) {
            return;
        }
        SharedPreferences prefs = appPrefs(context);
        prefs.edit().putBoolean(KEY_ACCESSIBILITY_AD_SERVICE_ENABLED, enabled).commit();
        ensurePrefsReadable(context);
    }

    static synchronized void setAutoRunOnAppStartEnabled(Context context, boolean enabled) {
        if (context == null) {
            return;
        }
        SharedPreferences prefs = appPrefs(context);
        prefs.edit().putBoolean(KEY_AUTO_RUN_ON_APP_START_ENABLED, enabled).commit();
        ensurePrefsReadable(context);
    }

    static synchronized void setTogetherCycleLimit(Context context, int limit) {
        if (context == null) {
            return;
        }
        int safeValue = sanitizeNonNegativeInt(limit);
        SharedPreferences prefs = appPrefs(context);
        prefs.edit().putInt(KEY_TOGETHER_CYCLE_LIMIT, safeValue).commit();
        ensurePrefsReadable(context);
    }

    static synchronized void setTogetherCycleWaitSeconds(Context context, int seconds) {
        if (context == null) {
            return;
        }
        int safeValue = sanitizeNonNegativeInt(seconds);
        SharedPreferences prefs = appPrefs(context);
        prefs.edit().putInt(KEY_TOGETHER_CYCLE_WAIT_SECONDS, safeValue).commit();
        ensurePrefsReadable(context);
    }

    static synchronized long pushEngineCommand(Context context, String command, EngineStatus status) {
        if (context == null) {
            return 0L;
        }
        SharedPreferences prefs = appPrefs(context);
        long nextSeq = prefs.getLong(KEY_ENGINE_COMMAND_SEQ, 0L) + 1L;
        prefs.edit()
                .putString(KEY_ENGINE_COMMAND, safeTrim(command))
                .putString(KEY_ENGINE_STATUS, status.name())
                .putLong(KEY_ENGINE_COMMAND_SEQ, nextSeq)
                .commit();
        ensurePrefsReadable(context);
        return nextSeq;
    }

    static synchronized void syncEngineState(
            Context context,
            String command,
            EngineStatus status,
            long seq
    ) {
        if (context == null) {
            return;
        }
        SharedPreferences prefs = appPrefs(context);
        long storedSeq = prefs.getLong(KEY_ENGINE_COMMAND_SEQ, 0L);
        long finalSeq = seq > 0L ? Math.max(storedSeq, seq) : storedSeq;
        String cmd = safeTrim(command);
        if (cmd.length() == 0) {
            cmd = commandForStatus(status);
        }
        prefs.edit()
                .putString(KEY_ENGINE_COMMAND, cmd)
                .putString(KEY_ENGINE_STATUS, status.name())
                .putLong(KEY_ENGINE_COMMAND_SEQ, finalSeq)
                .commit();
        ensurePrefsReadable(context);
    }

    static synchronized EngineStatus parseStatusName(String value) {
        return parseStatus(value);
    }

    static synchronized String commandForStatus(EngineStatus status) {
        if (status == EngineStatus.RUNNING) {
            return ENGINE_CMD_RUN;
        }
        if (status == EngineStatus.PAUSED) {
            return ENGINE_CMD_PAUSE;
        }
        return ENGINE_CMD_STOP;
    }

    static synchronized void ensureDefaults(Context context) {
        if (context == null) {
            return;
        }
        SharedPreferences prefs = appPrefs(context);
        SharedPreferences.Editor editor = prefs.edit();
        if (!prefs.contains(KEY_GLOBAL_FLOAT_BUTTON_ENABLED)) {
            editor.putBoolean(KEY_GLOBAL_FLOAT_BUTTON_ENABLED, DEFAULT_GLOBAL_FLOAT_BUTTON_ENABLED);
        }
        if (!prefs.contains(KEY_AD_PROCESS_ENABLED)) {
            editor.putBoolean(KEY_AD_PROCESS_ENABLED, DEFAULT_AD_PROCESS_ENABLED);
        }
        if (!prefs.contains(KEY_ACCESSIBILITY_AD_SERVICE_ENABLED)) {
            editor.putBoolean(
                    KEY_ACCESSIBILITY_AD_SERVICE_ENABLED,
                    DEFAULT_ACCESSIBILITY_AD_SERVICE_ENABLED
            );
        }
        if (!prefs.contains(KEY_AUTO_RUN_ON_APP_START_ENABLED)) {
            editor.putBoolean(
                    KEY_AUTO_RUN_ON_APP_START_ENABLED,
                    DEFAULT_AUTO_RUN_ON_APP_START_ENABLED
            );
        }
        if (!prefs.contains(KEY_TOGETHER_CYCLE_LIMIT)) {
            editor.putInt(KEY_TOGETHER_CYCLE_LIMIT, DEFAULT_TOGETHER_CYCLE_LIMIT);
        }
        if (!prefs.contains(KEY_TOGETHER_CYCLE_WAIT_SECONDS)) {
            editor.putInt(KEY_TOGETHER_CYCLE_WAIT_SECONDS, DEFAULT_TOGETHER_CYCLE_WAIT_SECONDS);
        }
        if (!prefs.contains(KEY_ENGINE_STATUS)) {
            editor.putString(KEY_ENGINE_STATUS, DEFAULT_ENGINE_STATUS);
        }
        if (!prefs.contains(KEY_ENGINE_COMMAND)) {
            editor.putString(KEY_ENGINE_COMMAND, "");
        }
        if (!prefs.contains(KEY_ENGINE_COMMAND_SEQ)) {
            editor.putLong(KEY_ENGINE_COMMAND_SEQ, 0L);
        }
        editor.commit();
        ensurePrefsReadable(context);
    }

    private static XSharedPreferences getXsp() {
        long now = System.currentTimeMillis();
        if (sXsp == null) {
            sXsp = new XSharedPreferences(MODULE_PACKAGE, PREFS_NAME);
            sXsp.makeWorldReadable();
            sLastReloadAt = 0L;
        }
        if (now - sLastReloadAt >= XSP_RELOAD_INTERVAL_MS) {
            sXsp.reload();
            sLastReloadAt = now;
        }
        return sXsp;
    }

    private static EngineStatus parseStatus(String value) {
        String normalized = safeTrim(value).toUpperCase();
        if (EngineStatus.RUNNING.name().equals(normalized)) {
            return EngineStatus.RUNNING;
        }
        if (EngineStatus.PAUSED.name().equals(normalized)) {
            return EngineStatus.PAUSED;
        }
        return EngineStatus.STOPPED;
    }

    private static String safeTrim(String s) {
        if (s == null) {
            return "";
        }
        return s.trim();
    }

    private static int sanitizeNonNegativeInt(int value) {
        return Math.max(0, value);
    }

    enum EngineStatus {
        RUNNING,
        PAUSED,
        STOPPED
    }
}
