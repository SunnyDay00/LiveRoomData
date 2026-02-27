package com.oodbye.lsposedchathook;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;

import de.robv.android.xposed.XSharedPreferences;

final class ModuleSettings {
    static final String MODULE_PACKAGE = "com.oodbye.lsposedchathook";
    static final String PREFS_NAME = "module_settings";

    static final String KEY_DEFAULT_CAPTURE_ENABLED = "default_capture_enabled";
    static final String KEY_AUTO_HANDLE_FULLSCREEN_AD = "auto_handle_fullscreen_ad";

    static final boolean DEFAULT_CAPTURE_ENABLED = false;
    static final boolean DEFAULT_AUTO_HANDLE_FULLSCREEN_AD = true;

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

    static synchronized boolean isDefaultCaptureEnabled() {
        XSharedPreferences xsp = getXsp();
        if (xsp == null) {
            return DEFAULT_CAPTURE_ENABLED;
        }
        return xsp.getBoolean(KEY_DEFAULT_CAPTURE_ENABLED, DEFAULT_CAPTURE_ENABLED);
    }

    static synchronized boolean isAutoHandleFullscreenAdEnabled() {
        XSharedPreferences xsp = getXsp();
        if (xsp == null) {
            return DEFAULT_AUTO_HANDLE_FULLSCREEN_AD;
        }
        return xsp.getBoolean(KEY_AUTO_HANDLE_FULLSCREEN_AD, DEFAULT_AUTO_HANDLE_FULLSCREEN_AD);
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
}
