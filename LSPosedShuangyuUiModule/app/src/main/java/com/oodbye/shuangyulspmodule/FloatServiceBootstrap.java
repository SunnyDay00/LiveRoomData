package com.oodbye.shuangyulspmodule;

import android.content.Context;
import android.content.Intent;

/**
 * 浮动服务启动引导。
 */
final class FloatServiceBootstrap {
    private static final String TAG = "SYLspModule";

    private FloatServiceBootstrap() {
    }

    static void startFloatService(Context context) {
        startFloatService(context, -1, false);
    }

    static void startFloatService(Context context, int displayId, boolean requestRestart) {
        try {
            Intent intent = new Intent(context, GlobalFloatService.class);
            if (displayId >= 0) {
                intent.putExtra(ModuleSettings.EXTRA_TARGET_DISPLAY_ID, displayId);
            }
            if (requestRestart) {
                intent.putExtra(ModuleSettings.EXTRA_REQUEST_RESTART_TARGET_APP, true);
            }
            context.startForegroundService(intent);
        } catch (Throwable ignore) {
            try {
                Intent intent = new Intent(context, GlobalFloatService.class);
                context.startService(intent);
            } catch (Throwable ignore2) {
            }
        }
    }

    static void stopFloatService(Context context) {
        try {
            Intent intent = new Intent(context, GlobalFloatService.class);
            context.stopService(intent);
        } catch (Throwable ignore) {
        }
    }
}
