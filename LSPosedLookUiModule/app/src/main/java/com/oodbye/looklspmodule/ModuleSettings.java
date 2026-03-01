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
    static final String KEY_ENGINE_STATUS = "engine_status";
    static final String KEY_ENGINE_COMMAND = "engine_command";
    static final String KEY_ENGINE_COMMAND_SEQ = "engine_command_seq";
    static final String ACTION_ENGINE_COMMAND = MODULE_PACKAGE + ".ACTION_ENGINE_COMMAND";
    static final String EXTRA_ENGINE_COMMAND = "engine_command";
    static final String EXTRA_ENGINE_STATUS = "engine_status";
    static final String EXTRA_ENGINE_COMMAND_SEQ = "engine_command_seq";

    static final String ENGINE_CMD_RUN = "RUN";
    static final String ENGINE_CMD_PAUSE = "PAUSE";
    static final String ENGINE_CMD_STOP = "STOP";

    static final boolean DEFAULT_GLOBAL_FLOAT_BUTTON_ENABLED = false;
    static final boolean DEFAULT_AD_PROCESS_ENABLED = true;
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

    static synchronized EngineStatus getEngineStatus() {
        XSharedPreferences xsp = getXsp();
        if (xsp == null) {
            return EngineStatus.STOPPED;
        }
        return parseStatus(xsp.getString(KEY_ENGINE_STATUS, DEFAULT_ENGINE_STATUS));
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

    static synchronized EngineStatus getEngineStatus(SharedPreferences prefs) {
        if (prefs == null) {
            return EngineStatus.STOPPED;
        }
        return parseStatus(prefs.getString(KEY_ENGINE_STATUS, DEFAULT_ENGINE_STATUS));
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

    enum EngineStatus {
        RUNNING,
        PAUSED,
        STOPPED
    }
}
