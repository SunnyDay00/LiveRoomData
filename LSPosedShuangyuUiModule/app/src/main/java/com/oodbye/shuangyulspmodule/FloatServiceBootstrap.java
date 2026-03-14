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
        // 悬浮按钮已由无障碍服务 TYPE_ACCESSIBILITY_OVERLAY 管理，
        // 不再需要启动 GlobalFloatService
    }

    static void startFloatService(Context context, int displayId, boolean requestRestart) {
        // 已禁用
    }

    static void stopFloatService(Context context) {
        // 停止旧服务（如果还在运行）
        try {
            Intent intent = new Intent(context, GlobalFloatService.class);
            context.stopService(intent);
        } catch (Throwable ignore) {
        }
    }
}
