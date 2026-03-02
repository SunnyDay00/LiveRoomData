package com.oodbye.looklspmodule;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.widget.Toast;

/**
 * 完整循环完成提示（模块进程展示，不受目标 app 重启影响）。
 */
final class CycleCompleteNotifier {
    private static final Object LOCK = new Object();
    private static final long MIN_SHOW_INTERVAL_MS = 1200L;
    private static long sLastShownAt = 0L;

    private CycleCompleteNotifier() {
    }

    static void show(Context context, String message) {
        if (context == null) {
            return;
        }
        String text = safeTrim(message);
        if (TextUtils.isEmpty(text)) {
            return;
        }
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            if (now - sLastShownAt < MIN_SHOW_INTERVAL_MS) {
                return;
            }
            sLastShownAt = now;
        }
        final Context appContext = context.getApplicationContext() != null
                ? context.getApplicationContext()
                : context;
        final String toastText = text;
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Toast.makeText(appContext, toastText, Toast.LENGTH_LONG).show();
                } catch (Throwable ignore) {
                }
            }
        });
    }

    private static String safeTrim(String value) {
        if (value == null) {
            return "";
        }
        return value.trim();
    }
}
