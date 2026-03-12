package com.oodbye.shuangyulspmodule;

import android.content.Context;
import android.widget.Toast;

/**
 * 采集完成通知（Toast）。
 */
final class CycleCompleteNotifier {
    private CycleCompleteNotifier() {
    }

    static void show(final Context context, final String message) {
        if (context == null) return;
        try {
            Toast.makeText(context, "采集完成: " + message, Toast.LENGTH_LONG).show();
        } catch (Throwable ignore) {
        }
    }
}
