package com.oodbye.shuangyulspmodule;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
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
            filter.addAction(ModuleSettings.ACTION_ENGINE_COMMAND);
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    // Android 13+ 需要 RECEIVER_EXPORTED 标志才能接收外部广播
                    context.registerReceiver(sCommandReceiver, filter, Context.RECEIVER_EXPORTED);
                } else {
                    context.registerReceiver(sCommandReceiver, filter);
                }
                ModuleRunFileLogger.i(TAG, "命令接收器已注册 (action=" + ModuleSettings.ACTION_ENGINE_COMMAND + ")");
            } catch (Throwable e) {
                ModuleRunFileLogger.e(TAG, "注册命令接收器失败", e);
            }
        }
    }

    private void handleCommand(Intent intent) {
        String command = intent.getStringExtra(ModuleSettings.EXTRA_ENGINE_COMMAND);
        long seq = intent.getLongExtra(ModuleSettings.EXTRA_ENGINE_COMMAND_SEQ, 0L);
        if (TextUtils.isEmpty(command)) return;

        ModuleRunFileLogger.i(TAG, "📩 收到引擎命令: " + command + " seq=" + seq);

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
            ModuleRunFileLogger.w(TAG, "⚠️ 无可用 UiSession（目标Activity未运行），忽略命令");
            return;
        }

        if (ModuleSettings.ENGINE_CMD_START.equals(command)) {
            session.startEngine(seq);
        } else if (ModuleSettings.ENGINE_CMD_STOP.equals(command)) {
            session.stopEngine(seq);
        } else if ("dump_levels".equals(command)) {
            // 遍历所有活跃 Activity 的 View 树
            synchronized (sUiSessions) {
                for (java.util.Map.Entry<Activity, UiSession> entry : sUiSessions.entrySet()) {
                    Activity act = entry.getKey();
                    if (act != null && !act.isFinishing()) {
                        ModuleRunFileLogger.i(TAG, "🔍 dump Activity: " + act.getClass().getName());
                        dumpLevelViews(act);
                    }
                }
            }
        }
    }

    /**
     * 在目标进程中遍历 View 树，dump iv_wealth / iv_charm 的详细属性。
     * 这些 ImageView 的 text 在无障碍 API 中为空，但在 Java 层可以读取
     * tag、drawable 资源名、contentDescription 等。
     */
    private void dumpLevelViews(Activity activity) {
        try {
            android.view.View decorView = activity.getWindow().getDecorView();
            ModuleRunFileLogger.i(TAG, "🔍 ════ 开始 dump 等级 View ════");
            dumpViewRecursive(decorView, 0);
            ModuleRunFileLogger.i(TAG, "🔍 ════ dump 完成 ════");
        } catch (Throwable e) {
            ModuleRunFileLogger.e(TAG, "dumpLevelViews 失败", e);
        }
    }

    private void dumpViewRecursive(android.view.View view, int depth) {
        if (view == null || depth > 20) return;

        // 只关注 ll_level / iv_wealth / iv_charm 相关节点
        int viewId = view.getId();
        String idName = "";
        try {
            if (viewId != android.view.View.NO_ID) {
                idName = view.getResources().getResourceEntryName(viewId);
            }
        } catch (Throwable ignore) {}

        boolean isLevelRelated = "ll_level".equals(idName)
                || "iv_wealth".equals(idName)
                || "iv_charm".equals(idName);

        if (isLevelRelated) {
            StringBuilder sb = new StringBuilder();
            String indent = new String(new char[depth]).replace('\0', ' ');
            sb.append(indent).append("🔍 ").append(view.getClass().getSimpleName())
              .append(" id=").append(idName);

            // tag
            Object tag = view.getTag();
            if (tag != null) sb.append(" tag=").append(tag).append("(").append(tag.getClass().getSimpleName()).append(")");

            // content description
            CharSequence cd = view.getContentDescription();
            if (cd != null && cd.length() > 0) sb.append(" desc=").append(cd);

            // ImageView specific
            if (view instanceof android.widget.ImageView) {
                android.widget.ImageView iv = (android.widget.ImageView) view;
                android.graphics.drawable.Drawable d = iv.getDrawable();
                if (d != null) {
                    sb.append(" drawable=").append(d.getClass().getName());
                    // Bitmap info
                    if (d instanceof android.graphics.drawable.BitmapDrawable) {
                        android.graphics.Bitmap bmp = ((android.graphics.drawable.BitmapDrawable) d).getBitmap();
                        if (bmp != null) {
                            sb.append(" bmp=").append(bmp.getWidth()).append("x").append(bmp.getHeight());
                        }
                    }
                    // resource id
                    try {
                        java.lang.reflect.Field f = android.widget.ImageView.class.getDeclaredField("mResource");
                        f.setAccessible(true);
                        int resId = f.getInt(iv);
                        if (resId != 0) {
                            sb.append(" res=").append(view.getResources().getResourceName(resId));
                        }
                    } catch (Throwable ignore) {}
                }

                // 尝试通过反射读取 Glide 的 Request 信息
                try {
                    Object glideTag = null;
                    // Glide 4.x 使用 View.setTag(tagId, viewTarget)
                    // 先扫描所有可能的 tag id
                    for (int k = 0x7f000000; k < 0x7f001000; k++) {
                        Object t = view.getTag(k);
                        if (t != null && t.getClass().getName().contains("glide")) {
                            glideTag = t;
                            sb.append(" GLIDE_TAG[0x").append(Integer.toHexString(k)).append("]=")
                              .append(t.getClass().getName());
                            // 尝试获取 request -> model (URL)
                            try {
                                java.lang.reflect.Method getReq = t.getClass().getMethod("getRequest");
                                Object req = getReq.invoke(t);
                                if (req != null) {
                                    sb.append(" req=").append(req.getClass().getName());
                                    // SingleRequest.model 是加载的 URL/model
                                    try {
                                        java.lang.reflect.Field modelField = req.getClass().getDeclaredField("model");
                                        modelField.setAccessible(true);
                                        Object model = modelField.get(req);
                                        sb.append(" MODEL=").append(model);
                                    } catch (Throwable ignore2) {
                                        // 打印 request 的所有字段名
                                        sb.append(" reqFields=[");
                                        for (java.lang.reflect.Field rf : req.getClass().getDeclaredFields()) {
                                            sb.append(rf.getName()).append(",");
                                        }
                                        sb.append("]");
                                    }
                                }
                            } catch (Throwable ignore2) {}
                            break;
                        }
                    }
                    if (glideTag == null) {
                        // 也检查普通 tag
                        Object plainTag = view.getTag();
                        if (plainTag != null && plainTag.getClass().getName().contains("glide")) {
                            sb.append(" PLAIN_GLIDE_TAG=").append(plainTag.getClass().getName());
                        }
                    }
                } catch (Throwable ignore) {}
            }

            // 尝试读取所有非空 tag (扩大范围)
            try {
                for (int key = 0x7f000000; key < 0x7f001000; key++) {
                    Object keyTag = view.getTag(key);
                    if (keyTag != null) {
                        String cn = keyTag.getClass().getName();
                        sb.append(" tag[0x").append(Integer.toHexString(key)).append("]=")
                          .append(cn);
                        if (!(keyTag instanceof android.view.View)) {
                            sb.append(":").append(keyTag.toString());
                        }
                    }
                }
            } catch (Throwable ignore) {}

            ModuleRunFileLogger.i(TAG, sb.toString());
        }

        // 递归子 View
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup vg = (android.view.ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                dumpViewRecursive(vg.getChildAt(i), depth + 1);
            }
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
            if (engineRunning) {
                ModuleRunFileLogger.w(TAG, "⚠️ 引擎已在运行中，忽略重复启动命令");
                return;
            }
            commandSeq = seq;
            shouldStop = false;
            engineRunning = true;
            cycleCompleted = 0;
            cycleEntered = 0;
            runStartAt = System.currentTimeMillis();

            ModuleRunFileLogger.i(TAG, "🟢🟢🟢 ════ 引擎启动 seq=" + seq + " ════ 🟢🟢🟢");
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
            long elapsed = (System.currentTimeMillis() - runStartAt) / 1000L;
            ModuleRunFileLogger.i(TAG, "🔴🔴🔴 ════ 引擎停止 (运行" + elapsed + "秒, 完成" + cycleCompleted + "轮) ════ 🔴🔴🔴");
            reportEngineStatus(ModuleSettings.EngineStatus.STOPPED);
        }

        private void runNextCycle() {
            if (shouldStop || !engineRunning) {
                ModuleRunFileLogger.w(TAG, "⚠️ runNextCycle 被跳过 (shouldStop=" + shouldStop + " engineRunning=" + engineRunning + ")");
                return;
            }

            SharedPreferences prefs = ModuleSettings.appPrefs(activity);
            int cycleLimit = ModuleSettings.getCycleLimit(prefs);
            if (cycleCompleted >= cycleLimit) {
                onAllCyclesFinished();
                return;
            }

            ModuleRunFileLogger.i(TAG, "🔄 ════ 开始第 " + (cycleCompleted + 1) + "/" + cycleLimit + " 轮采集循环 ════");
            runtimeModule.start();
            runtimeModule.runCollectionCycle(new LiveRoomTaskScriptRunner.TaskFinishListener() {
                @Override
                public void onTaskFinished(boolean success, String message) {
                    if (shouldStop || !engineRunning) {
                        ModuleRunFileLogger.w(TAG, "⚠️ 循环结果被丢弃 (引擎已停止): " + message);
                        return;
                    }
                    cycleCompleted++;
                    ModuleRunFileLogger.i(TAG, "🔄 循环 " + cycleCompleted + "/" + cycleLimit + " 完成: " + message);
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
            ModuleRunFileLogger.i(TAG, "🏁🏁🏁 ════ 所有循环完成: " + cycleCompleted + " 轮, 耗时 "
                    + (duration / 1000L) + "秒 ════ 🏁🏁🏁");

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
