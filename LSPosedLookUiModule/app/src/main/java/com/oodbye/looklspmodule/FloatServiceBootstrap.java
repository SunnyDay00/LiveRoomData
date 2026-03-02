package com.oodbye.looklspmodule;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;

final class FloatServiceBootstrap {
    private FloatServiceBootstrap() {
    }

    static void syncFloatServiceState(Context context, Intent intent) {
        if (context == null) {
            return;
        }
        ModuleSettings.ensureDefaults(context);
        SharedPreferences prefs = ModuleSettings.appPrefs(context);
        boolean enabled = ModuleSettings.getGlobalFloatButtonEnabled(prefs);
        if (!enabled) {
            GlobalFloatService.stopServiceCompat(context);
            return;
        }
        if (!canDrawOverlaysCompat(context)) {
            return;
        }
        int displayId = -1;
        boolean hasDisplayExtra = false;
        boolean requestRestart = false;
        if (intent != null) {
            hasDisplayExtra = intent.hasExtra(ModuleSettings.EXTRA_TARGET_DISPLAY_ID);
            if (hasDisplayExtra) {
                displayId = intent.getIntExtra(ModuleSettings.EXTRA_TARGET_DISPLAY_ID, -1);
            }
            requestRestart = intent.getBooleanExtra(
                    ModuleSettings.EXTRA_REQUEST_RESTART_TARGET_APP,
                    false
            );
        }
        if (hasDisplayExtra || requestRestart) {
            GlobalFloatService.startServiceCompat(context, displayId, requestRestart);
        } else {
            GlobalFloatService.startServiceCompat(context);
        }
    }

    private static boolean canDrawOverlaysCompat(Context context) {
        if (context == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        return Settings.canDrawOverlays(context);
    }
}
