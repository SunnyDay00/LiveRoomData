package com.oodbye.looklspmodule;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.widget.Toast;

/**
 * 广告处理成功后的轻提示（系统 Toast，约 2 秒自动淡出）。
 */
final class AdHandledNotifier {
    private static final Object LOCK = new Object();
    private static final long MIN_SHOW_INTERVAL_MS = 700L;
    private static long sLastShownAt = 0L;

    private AdHandledNotifier() {
    }

    static void show(Context context, String detail) {
        if (context == null) {
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
        final String suffix = safeTrim(detail);
        final String message = TextUtils.isEmpty(suffix)
                ? "广告已处理"
                : "广告已处理：" + suffix;
        Handler mainHandler = new Handler(Looper.getMainLooper());
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show();
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
