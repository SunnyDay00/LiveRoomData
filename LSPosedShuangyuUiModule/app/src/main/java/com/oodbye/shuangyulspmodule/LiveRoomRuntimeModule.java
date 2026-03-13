package com.oodbye.shuangyulspmodule;

import android.app.Activity;
import android.os.Handler;
import android.util.Log;

/**
 * 直播间运行时模块：在目标应用进程中管理直播间生命周期与任务执行。
 */
final class LiveRoomRuntimeModule {
    private static final String TAG = "SYLspModule";

    // ─────────────────────── RuntimeBridge ───────────────────────
    interface RuntimeBridge {
        Activity getActivity();
        Handler getHandler();
        boolean isEngineRunning();
        boolean shouldStop();
        void postDelayed(Runnable r, long delayMs);
        void onTaskFinished(boolean success, String message);
        void updateStatus(String status);
    }

    private final RuntimeBridge bridge;
    private volatile boolean running;

    LiveRoomRuntimeModule(RuntimeBridge bridge) {
        this.bridge = bridge;
    }

    void start() {
        if (running) return;
        running = true;
        Log.i(TAG, "LiveRoomRuntimeModule started");
    }

    void stop() {
        running = false;
        Log.i(TAG, "LiveRoomRuntimeModule stopped");
    }

    boolean isRunning() {
        return running;
    }

    /** 执行一次完整的采集循环 */
    void runCollectionCycle(final LiveRoomTaskScriptRunner.TaskFinishListener listener) {
        if (!running || bridge == null) {
            if (listener != null) listener.onTaskFinished(false, "模块未运行");
            return;
        }
        Handler handler = bridge.getHandler();
        if (handler == null) {
            if (listener != null) listener.onTaskFinished(false, "Handler 为空");
            return;
        }
        ShuangyuAccessibilityAdService a11y = ShuangyuAccessibilityAdService.getInstance();
        if (a11y == null) {
            Log.e(TAG, "⚠️ 无障碍服务未运行，无法执行采集");
            if (listener != null) listener.onTaskFinished(false, "无障碍服务未运行");
            return;
        }
        bridge.updateStatus("开始采集循环");
        LiveRoomTaskScriptRunner runner = new LiveRoomTaskScriptRunner(
                a11y, handler, new LiveRoomTaskScriptRunner.TaskBridge() {
            @Override
            public boolean shouldStop() {
                return bridge.shouldStop() || !running;
            }

            @Override
            public void updateStatus(String status) {
                bridge.updateStatus(status);
            }
        });
        runner.execute(listener);
    }

    /** 验证当前是否在直播间内（通过查找特征 View） */
    boolean isInLiveRoom(Activity activity) {
        if (activity == null) return false;
        try {
            android.view.View decor = activity.getWindow().getDecorView();
            android.view.View roomNameView = decor.findViewWithTag(
                    UiComponentConfig.LIVE_ROOM_NAME_ID);
            return roomNameView != null;
        } catch (Throwable e) {
            return false;
        }
    }
}
