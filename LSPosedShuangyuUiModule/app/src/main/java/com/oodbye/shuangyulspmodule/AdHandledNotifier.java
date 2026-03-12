package com.oodbye.shuangyulspmodule;

import android.content.Context;
import android.widget.Toast;

/**
 * 广告处理完成通知（Toast）。
 */
final class AdHandledNotifier {
    private AdHandledNotifier() {
    }

    static void show(final Context context, final String label) {
        if (context == null) return;
        try {
            Toast.makeText(context, "广告已处理: " + label, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignore) {
        }
    }
}
