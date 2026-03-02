package com.oodbye.looklspmodule;

import android.app.Activity;
import android.os.Handler;

/**
 * 实时广告检测循环：独立于主流程状态机，持续轮询并处理广告规则。
 * 说明：该模块仍运行在目标应用进程内（LSPosed 注入进程），以便访问当前 UI 树。
 */
final class RealtimeAdLoop {

    interface RuntimeBridge {
        boolean isEngineRunning();

        boolean isAdProcessEnabled();

        boolean isAccessibilityAdServiceEnabled();
    }

    private final Activity activity;
    private final Handler handler;
    private final RuntimeBridge bridge;
    private final Runnable loopTask;
    private boolean started;
    private CustomRulesAdProcessor adProcessor;

    RealtimeAdLoop(Activity activity, Handler handler, RuntimeBridge bridge) {
        this.activity = activity;
        this.handler = handler;
        this.bridge = bridge;
        this.started = false;
        this.adProcessor = null;
        this.loopTask = new Runnable() {
            @Override
            public void run() {
                if (!started) {
                    return;
                }
                tryScanAndHandle();
                handler.postDelayed(this, UiComponentConfig.AD_REALTIME_LOOP_INTERVAL_MS);
            }
        };
    }

    void start() {
        if (started) {
            return;
        }
        started = true;
        handler.post(loopTask);
    }

    void stop() {
        started = false;
        handler.removeCallbacks(loopTask);
    }

    private void tryScanAndHandle() {
        if (bridge == null) {
            return;
        }
        if (!bridge.isEngineRunning()) {
            return;
        }
        if (!bridge.isAdProcessEnabled()) {
            return;
        }
        if (bridge.isAccessibilityAdServiceEnabled()) {
            return;
        }
        if (adProcessor == null) {
            adProcessor = new CustomRulesAdProcessor(activity);
        }
        adProcessor.scanAndHandleIfNeeded(System.currentTimeMillis());
    }
}
