package com.oodbye.looklspmodule;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

public class LookAccessibilityAdService extends AccessibilityService {
    private static final String TAG = "LOOKA11yAd";
    private static final String LOG_PREFIX = "[LOOKA11yAd]";

    private Handler handler;
    private AccessibilityCustomRulesAdEngine adEngine;
    private boolean loopStarted;
    private final Runnable realtimeLoopTask = new Runnable() {
        @Override
        public void run() {
            if (!loopStarted) {
                return;
            }
            tryScanAndHandle();
            handler.postDelayed(this, UiComponentConfig.AD_REALTIME_LOOP_INTERVAL_MS);
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        handler = new Handler(Looper.getMainLooper());
        adEngine = new AccessibilityCustomRulesAdEngine(this);
        configureServiceInfo();
        startRealtimeLoop();
        log("无障碍广告服务已连接");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }
        CharSequence packageName = event.getPackageName();
        if (!UiComponentConfig.TARGET_PACKAGE.equals(safeTrim(packageName))) {
            return;
        }
        tryScanAndHandle();
    }

    @Override
    public void onInterrupt() {
        log("无障碍广告服务被中断");
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        stopRealtimeLoop();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        stopRealtimeLoop();
        super.onDestroy();
        log("无障碍广告服务已销毁");
    }

    private void configureServiceInfo() {
        try {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info == null) {
                return;
            }
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                    | AccessibilityEvent.TYPE_VIEW_CLICKED
                    | AccessibilityEvent.TYPE_WINDOWS_CHANGED;
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
            info.notificationTimeout = 40L;
            info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            }
            info.packageNames = new String[] { UiComponentConfig.TARGET_PACKAGE };
            setServiceInfo(info);
        } catch (Throwable e) {
            log("配置无障碍服务失败: " + e);
        }
    }

    private void startRealtimeLoop() {
        if (loopStarted) {
            return;
        }
        loopStarted = true;
        if (handler != null) {
            handler.post(realtimeLoopTask);
        }
    }

    private void stopRealtimeLoop() {
        loopStarted = false;
        if (handler != null) {
            handler.removeCallbacks(realtimeLoopTask);
        }
    }

    private void tryScanAndHandle() {
        if (!isAdHandlingEnabled()) {
            return;
        }
        if (adEngine == null) {
            adEngine = new AccessibilityCustomRulesAdEngine(this);
        }
        adEngine.scanAndHandleIfNeeded(System.currentTimeMillis());
    }

    private boolean isAdHandlingEnabled() {
        ModuleSettings.ensureDefaults(this);
        android.content.SharedPreferences prefs = ModuleSettings.appPrefs(this);
        if (!ModuleSettings.getAdProcessEnabled(prefs)) {
            return false;
        }
        return ModuleSettings.getAccessibilityAdServiceEnabled(prefs);
    }

    private static String safeTrim(CharSequence value) {
        if (value == null) {
            return "";
        }
        return value.toString().trim();
    }

    private void log(String msg) {
        String line = LOG_PREFIX + " " + safeTrim(msg);
        try {
            Log.i(TAG, line);
        } catch (Throwable ignore) {
        }
        try {
            ModuleRunFileLogger.appendLine(this, line);
        } catch (Throwable ignore) {
        }
    }
}

