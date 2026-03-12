package com.oodbye.shuangyulspmodule;

import android.os.Handler;
import android.util.Log;

/**
 * 目标应用进程内实时广告检测循环。
 */
final class RealtimeAdLoop {
    private static final String TAG = "SYLspModule";

    interface RuntimeBridge {
        boolean isEngineRunning();
        boolean isAdProcessEnabled();
        void reportAdDetected(String label);
    }

    private final Handler handler;
    private final RuntimeBridge bridge;
    private boolean running;
    private final Runnable loopRunnable = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            try {
                onTick();
            } catch (Throwable e) {
                Log.e(TAG, "RealtimeAdLoop tick error", e);
            }
            if (running) {
                handler.postDelayed(this, UiComponentConfig.AD_REALTIME_LOOP_INTERVAL_MS);
            }
        }
    };

    RealtimeAdLoop(Handler handler, RuntimeBridge bridge) {
        this.handler = handler;
        this.bridge = bridge;
    }

    void start() {
        if (running) return;
        running = true;
        handler.post(loopRunnable);
    }

    void stop() {
        running = false;
        handler.removeCallbacks(loopRunnable);
    }

    boolean isRunning() {
        return running;
    }

    private void onTick() {
        if (bridge == null) return;
        if (!bridge.isEngineRunning()) return;
        if (!bridge.isAdProcessEnabled()) return;
        // 在目标应用 UI 线程内可执行实时广告检测
        // 具体检测逻辑由子类或回调实现
    }
}
