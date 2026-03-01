package com.oodbye.looklspmodule;

import android.app.Activity;
import android.content.Context;

import de.robv.android.xposed.XposedBridge;

final class LiveRoomTaskScriptRunner {
    private static final String TAG = "[LOOKScriptRunner]";

    private LiveRoomTaskScriptRunner() {
    }

    static long runLiveRoomEnterTask(Activity activity) {
        long waitMs = Math.max(0L, UiComponentConfig.LIVE_ROOM_ENTER_TASK_WAIT_MS);
        log(
                activity,
                "task=" + UiComponentConfig.LIVE_ROOM_ENTER_TASK_NAME
                        + " start, wait=" + waitMs + "ms"
        );
        log(
                activity,
                "task=" + UiComponentConfig.LIVE_ROOM_ENTER_TASK_NAME
                        + " done, wait=" + waitMs + "ms"
        );
        return waitMs;
    }

    private static String safeTrim(String s) {
        if (s == null) {
            return "";
        }
        return s.trim();
    }

    private static void log(Context context, String msg) {
        String line = TAG + " " + safeTrim(msg);
        try {
            XposedBridge.log(line);
        } catch (Throwable ignore) {
        }
        ModuleRunFileLogger.appendLine(context, line);
    }
}
