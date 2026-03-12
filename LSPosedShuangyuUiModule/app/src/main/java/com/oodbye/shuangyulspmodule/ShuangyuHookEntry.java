package com.oodbye.shuangyulspmodule;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 双鱼直播模块 Xposed 入口。
 */
public class ShuangyuHookEntry implements IXposedHookLoadPackage {
    private static final String TAG = "SYLspModule";

    private static final Map<Activity, UiSession> sUiSessions = new HashMap<>();
    private static volatile BroadcastReceiver sCommandReceiver;
    private static final Object RECEIVER_LOCK = new Object();

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (!lpparam.packageName.equals(UiComponentConfig.TARGET_PACKAGE)) {
            return;
        }
        XposedBridge.log(TAG + ": handleLoadPackage - " + lpparam.packageName);
        ModuleRunFileLogger.init();
        ModuleRunFileLogger.i(TAG, "模块加载到进程: " + lpparam.packageName);

        hookActivityLifecycle(lpparam);
    }

    // ─────────────────────── Activity 生命周期 Hook ───────────────────────

    private void hookActivityLifecycle(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod("android.app.Activity", lpparam.classLoader,
                    "onCreate", Bundle.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Activity activity = (Activity) param.thisObject;
                            if (activity == null) return;
                            String actName = activity.getClass().getName();
                            ModuleRunFileLogger.i(TAG, "Activity.onCreate: " + actName);

                            ensureCommandReceiver(activity);
                            UiSession session = new UiSession(activity);
                            synchronized (sUiSessions) {
                                sUiSessions.put(activity, session);
                            }
                        }
                    });

            XposedHelpers.findAndHookMethod("android.app.Activity", lpparam.classLoader,
                    "onResume", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Activity activity = (Activity) param.thisObject;
                            if (activity == null) return;
                            UiSession session;
                            synchronized (sUiSessions) {
                                session = sUiSessions.get(activity);
                            }
                            if (session != null) {
                                session.onResume();
                            }
                        }
                    });

            XposedHelpers.findAndHookMethod("android.app.Activity", lpparam.classLoader,
                    "onDestroy", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Activity activity = (Activity) param.thisObject;
                            if (activity == null) return;
                            UiSession session;
                            synchronized (sUiSessions) {
                                session = sUiSessions.remove(activity);
                            }
                            if (session != null) {
                                session.destroy();
                            }
                        }
                    });
        } catch (Throwable e) {
            XposedBridge.log(TAG + ": hookActivityLifecycle failed: " + e.getMessage());
            ModuleRunFileLogger.e(TAG, "hookActivityLifecycle 失败", e);
        }
    }

    // ─────────────────────── 命令接收器 ───────────────────────

    private void ensureCommandReceiver(Context context) {
        synchronized (RECEIVER_LOCK) {
            if (sCommandReceiver != null) return;
            sCommandReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    if (intent == null) return;
                    String action = intent.getAction();
                    ModuleRunFileLogger.i(TAG, "命令接收: action=" + action);
                    handleCommand(intent);
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(ModuleSettings.ACTION_ENGINE_STATUS_REPORT);
            try {
                context.registerReceiver(sCommandReceiver, filter);
                ModuleRunFileLogger.i(TAG, "命令接收器已注册");
            } catch (Throwable e) {
                ModuleRunFileLogger.e(TAG, "注册命令接收器失败", e);
            }
        }
    }

    private void handleCommand(Intent intent) {
        String command = intent.getStringExtra(ModuleSettings.EXTRA_ENGINE_COMMAND);
        long seq = intent.getLongExtra(ModuleSettings.EXTRA_ENGINE_COMMAND_SEQ, 0L);
        if (TextUtils.isEmpty(command)) return;

        ModuleRunFileLogger.i(TAG, "引擎命令: " + command + " seq=" + seq);

        // 找到最新的 UiSession
        UiSession session = null;
        synchronized (sUiSessions) {
            for (UiSession s : sUiSessions.values()) {
                if (s != null && s.activity != null && !s.activity.isFinishing()) {
                    session = s;
                    break;
                }
            }
        }

        if (session == null) {
            ModuleRunFileLogger.w(TAG, "无可用 UiSession，忽略命令");
            return;
        }

        if (ModuleSettings.ENGINE_CMD_START.equals(command)) {
            session.startEngine(seq);
        } else if (ModuleSettings.ENGINE_CMD_STOP.equals(command)) {
            session.stopEngine(seq);
        }
    }

    // ─────────────────────── UiSession ───────────────────────

    static class UiSession implements LiveRoomRuntimeModule.RuntimeBridge {
        final Activity activity;
        final Handler handler;
        private LiveRoomRuntimeModule runtimeModule;
        private RealtimeAdLoop adLoop;
        private volatile boolean engineRunning;
        private volatile boolean shouldStop;
        private volatile long commandSeq;
        private int cycleCompleted;
        private int cycleEntered;
        private long runStartAt;

        UiSession(Activity activity) {
            this.activity = activity;
            this.handler = new Handler(Looper.getMainLooper());
            this.runtimeModule = new LiveRoomRuntimeModule(this);
        }

        void onResume() {
            // 检查是否有待执行命令
            checkPendingCommand();
        }

        void destroy() {
            stopEngine(0L);
            if (adLoop != null) {
                adLoop.stop();
            }
        }

        void startEngine(long seq) {
            if (engineRunning) return;
            commandSeq = seq;
            shouldStop = false;
            engineRunning = true;
            cycleCompleted = 0;
            cycleEntered = 0;
            runStartAt = System.currentTimeMillis();

            ModuleRunFileLogger.i(TAG, "引擎启动 seq=" + seq);
            reportEngineStatus(ModuleSettings.EngineStatus.RUNNING);

            // 启动 RealtimeAdLoop（如果启用）
            if (ModuleSettings.isAdProcessEnabled()) {
                if (adLoop == null) {
                    adLoop = new RealtimeAdLoop(handler, new RealtimeAdLoop.RuntimeBridge() {
                        @Override
                        public boolean isEngineRunning() {
                            return engineRunning;
                        }

                        @Override
                        public boolean isAdProcessEnabled() {
                            return ModuleSettings.isAdProcessEnabled();
                        }

                        @Override
                        public void reportAdDetected(String label) {
                            ModuleRunFileLogger.i(TAG, "广告检测: " + label);
                        }
                    });
                }
                adLoop.start();
            }

            // 开始采集循环
            runNextCycle();
        }

        void stopEngine(long seq) {
            if (!engineRunning && seq <= 0L) return;
            shouldStop = true;
            engineRunning = false;
            if (adLoop != null) {
                adLoop.stop();
            }
            if (runtimeModule != null) {
                runtimeModule.stop();
            }
            ModuleRunFileLogger.i(TAG, "引擎停止");
            reportEngineStatus(ModuleSettings.EngineStatus.STOPPED);
        }

        private void runNextCycle() {
            if (shouldStop || !engineRunning) return;

            SharedPreferences prefs = ModuleSettings.appPrefs(activity);
            int cycleLimit = ModuleSettings.getCycleLimit(prefs);
            if (cycleCompleted >= cycleLimit) {
                onAllCyclesFinished();
                return;
            }

            runtimeModule.start();
            runtimeModule.runCollectionCycle(new LiveRoomTaskScriptRunner.TaskFinishListener() {
                @Override
                public void onTaskFinished(boolean success, String message) {
                    if (shouldStop || !engineRunning) return;
                    cycleCompleted++;
                    ModuleRunFileLogger.i(TAG, "循环 " + cycleCompleted + " 完成: " + message);
                    reportRuntimeStats();

                    // 通知循环完成
                    Intent notice = new Intent(ModuleSettings.ACTION_CYCLE_COMPLETE_NOTICE);
                    notice.setPackage(ModuleSettings.MODULE_PACKAGE);
                    notice.putExtra(ModuleSettings.EXTRA_CYCLE_COMPLETE_MESSAGE,
                            "循环 " + cycleCompleted + " 完成");
                    try {
                        activity.sendBroadcast(notice);
                    } catch (Throwable ignore) {
                    }

                    // 下一轮
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            runNextCycle();
                        }
                    }, 3000L);
                }
            });
        }

        private void onAllCyclesFinished() {
            engineRunning = false;
            long duration = System.currentTimeMillis() - runStartAt;
            ModuleRunFileLogger.i(TAG, "所有循环完成: " + cycleCompleted + " 轮, 耗时 "
                    + (duration / 1000L) + "s");

            reportEngineStatus(ModuleSettings.EngineStatus.STOPPED);

            Intent finished = new Intent(ModuleSettings.ACTION_CYCLE_LIMIT_FINISHED);
            finished.setPackage(ModuleSettings.MODULE_PACKAGE);
            finished.putExtra(ModuleSettings.EXTRA_FINISHED_CYCLES, cycleCompleted);
            finished.putExtra(ModuleSettings.EXTRA_FINISHED_DURATION_MS, duration);
            try {
                activity.sendBroadcast(finished);
            } catch (Throwable ignore) {
            }
        }

        private void checkPendingCommand() {
            // 可从 SharedPrefs 读取待执行命令
        }

        // ─────────────────── RuntimeBridge 实现 ───────────────────

        @Override
        public Activity getActivity() {
            return activity;
        }

        @Override
        public Handler getHandler() {
            return handler;
        }

        @Override
        public boolean isEngineRunning() {
            return engineRunning;
        }

        @Override
        public boolean shouldStop() {
            return shouldStop;
        }

        @Override
        public void postDelayed(Runnable r, long delayMs) {
            handler.postDelayed(r, delayMs);
        }

        @Override
        public void onTaskFinished(boolean success, String message) {
            ModuleRunFileLogger.i(TAG, "任务完成: success=" + success + " msg=" + message);
        }

        @Override
        public void updateStatus(String status) {
            ModuleRunFileLogger.i(TAG, "状态更新: " + status);
        }

        // ─────────────────── 状态上报 ───────────────────

        private void reportEngineStatus(ModuleSettings.EngineStatus status) {
            Intent intent = new Intent(ModuleSettings.ACTION_ENGINE_STATUS_REPORT);
            intent.setPackage(ModuleSettings.MODULE_PACKAGE);
            intent.putExtra(ModuleSettings.EXTRA_ENGINE_STATUS, status.name());
            intent.putExtra(ModuleSettings.EXTRA_ENGINE_COMMAND_SEQ, commandSeq);
            try {
                activity.sendBroadcast(intent);
            } catch (Throwable ignore) {
            }
        }

        private void reportRuntimeStats() {
            Intent intent = new Intent(ModuleSettings.ACTION_RUNTIME_STATS_REPORT);
            intent.setPackage(ModuleSettings.MODULE_PACKAGE);
            intent.putExtra(ModuleSettings.EXTRA_RUNTIME_RUN_START_AT, runStartAt);
            intent.putExtra(ModuleSettings.EXTRA_RUNTIME_CYCLE_COMPLETED, cycleCompleted);
            intent.putExtra(ModuleSettings.EXTRA_RUNTIME_CYCLE_ENTERED, cycleEntered);
            intent.putExtra(ModuleSettings.EXTRA_RUNTIME_COMMAND_SEQ, commandSeq);
            try {
                activity.sendBroadcast(intent);
            } catch (Throwable ignore) {
            }
        }
    }

    // ─────────────────────── View 树调试 ───────────────────────

    static void dumpViewTree(View root, String prefix) {
        if (root == null || !ModuleSettings.isViewTreeDebugEnabled()) return;
        StringBuilder sb = new StringBuilder();
        dumpViewTreeInternal(root, prefix, sb, 0, 8);
        ModuleRunFileLogger.i(TAG, "ViewTree:\n" + sb.toString());
    }

    private static void dumpViewTreeInternal(View view, String prefix,
                                              StringBuilder sb, int depth, int maxDepth) {
        if (view == null || depth > maxDepth) return;
        String indent = "";
        for (int i = 0; i < depth; i++) indent += "  ";
        sb.append(indent)
                .append(view.getClass().getSimpleName())
                .append(" id=").append(view.getId())
                .append(" vis=").append(view.getVisibility())
                .append('\n');
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                dumpViewTreeInternal(vg.getChildAt(i), prefix, sb, depth + 1, maxDepth);
            }
        }
    }
}
