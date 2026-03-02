package com.oodbye.looklspmodule;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.hardware.display.DisplayManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.DataOutputStream;
import java.util.List;

public class GlobalFloatService extends Service {
    private static final String TAG = "LOOKLspModule";
    private static final String CHANNEL_ID = "look_lsp_float_service";
    private static final int NOTIFICATION_ID = 2026030101;
    private static final long DISPLAY_REFRESH_INTERVAL_MS = 2000L;
    private static final long PERMISSION_TOAST_MIN_INTERVAL_MS = 6000L;
    private static final long CYCLE_NOTICE_SHOW_MS = 3000L;

    private SharedPreferences prefs;
    private Handler handler;
    private long lastRealtimeSyncAt;
    private long lastRealtimeSyncedSeq;
    private WindowManager windowManager;
    private LinearLayout rootLayout;
    private LinearLayout actionPanel;
    private TextView mainButton;
    private TextView infoWindow;
    private TextView cycleNoticeWindow;
    private boolean overlayAdded;
    private WindowManager mirrorWindowManager;
    private LinearLayout mirrorRootLayout;
    private LinearLayout mirrorActionPanel;
    private TextView mirrorMainButton;
    private TextView mirrorInfoWindow;
    private TextView mirrorCycleNoticeWindow;
    private boolean mirrorOverlayAdded;
    private long lastPermissionToastAt;
    private int preferredDisplayId = -1;
    private int attachedDisplayId = -1;
    private int mirrorAttachedDisplayId = -1;
    private long runtimeStartFallbackAt = 0L;
    private final Runnable hideCycleNoticeTask = new Runnable() {
        @Override
        public void run() {
            hideCycleNoticeView(cycleNoticeWindow);
            hideCycleNoticeView(mirrorCycleNoticeWindow);
        }
    };
    private final Runnable refreshTask = new Runnable() {
        @Override
        public void run() {
            refreshOverlayState();
            handler.postDelayed(this, DISPLAY_REFRESH_INTERVAL_MS);
        }
    };

    public static void startServiceCompat(Context context) {
        startServiceCompatInternal(context, null, false, null);
    }

    public static void startServiceCompat(Context context, int targetDisplayId) {
        startServiceCompatInternal(context, Integer.valueOf(targetDisplayId), false, null);
    }

    public static void startServiceCompat(Context context, boolean requestRestartTargetApp) {
        startServiceCompatInternal(context, null, requestRestartTargetApp, null);
    }

    public static void startServiceCompatWithNotice(Context context, String noticeMessage) {
        startServiceCompatInternal(context, null, false, noticeMessage);
    }

    public static void startServiceCompat(
            Context context,
            int targetDisplayId,
            boolean requestRestartTargetApp
    ) {
        startServiceCompatInternal(
                context,
                Integer.valueOf(targetDisplayId),
                requestRestartTargetApp,
                null
        );
    }

    private static void startServiceCompatInternal(
            Context context,
            Integer targetDisplayId,
            boolean requestRestartTargetApp,
            String noticeMessage
    ) {
        if (context == null) {
            return;
        }
        Intent intent = new Intent(context, GlobalFloatService.class);
        if (targetDisplayId != null) {
            intent.putExtra(ModuleSettings.EXTRA_TARGET_DISPLAY_ID, targetDisplayId.intValue());
        }
        if (requestRestartTargetApp) {
            intent.putExtra(ModuleSettings.EXTRA_REQUEST_RESTART_TARGET_APP, true);
        }
        if (!TextUtils.isEmpty(noticeMessage)) {
            intent.putExtra(ModuleSettings.EXTRA_CYCLE_COMPLETE_MESSAGE, noticeMessage);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stopServiceCompat(Context context) {
        if (context == null) {
            return;
        }
        Intent intent = new Intent(context, GlobalFloatService.class);
        context.stopService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = ModuleSettings.appPrefs(this);
        ModuleSettings.ensureDefaults(this);
        handler = new Handler(Looper.getMainLooper());
        lastRealtimeSyncAt = 0L;
        lastRealtimeSyncedSeq = -1L;
        overlayAdded = false;
        mirrorOverlayAdded = false;
        lastPermissionToastAt = 0L;
        startForegroundInternal();
        refreshOverlayState();
        handler.postDelayed(refreshTask, DISPLAY_REFRESH_INTERVAL_MS);
        log("service created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        int targetDisplayId = preferredDisplayId;
        boolean hasDisplayExtra = false;
        boolean requestRestartTargetApp = false;
        String cycleNoticeMessage = "";
        if (intent != null) {
            hasDisplayExtra = intent.hasExtra(ModuleSettings.EXTRA_TARGET_DISPLAY_ID);
            if (hasDisplayExtra) {
                targetDisplayId = intent.getIntExtra(
                        ModuleSettings.EXTRA_TARGET_DISPLAY_ID,
                        preferredDisplayId
                );
            }
            requestRestartTargetApp = intent.getBooleanExtra(
                    ModuleSettings.EXTRA_REQUEST_RESTART_TARGET_APP,
                    false
            );
            cycleNoticeMessage = intent.getStringExtra(ModuleSettings.EXTRA_CYCLE_COMPLETE_MESSAGE);
        }
        if (hasDisplayExtra && targetDisplayId >= 0 && targetDisplayId != preferredDisplayId) {
            preferredDisplayId = targetDisplayId;
            log("switch target displayId=" + preferredDisplayId);
            removeMirrorOverlay();
            mirrorWindowManager = null;
            mirrorRootLayout = null;
            mirrorActionPanel = null;
            mirrorMainButton = null;
            mirrorInfoWindow = null;
            mirrorCycleNoticeWindow = null;
            mirrorAttachedDisplayId = -1;
        }
        refreshOverlayState();
        showCycleCompleteNotice(cycleNoticeMessage);
        if (requestRestartTargetApp) {
            restartTargetAppForNextCycle();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (handler != null) {
            handler.removeCallbacks(refreshTask);
            handler.removeCallbacks(hideCycleNoticeTask);
        }
        removeOverlay();
        removeMirrorOverlay();
        windowManager = null;
        mirrorWindowManager = null;
        rootLayout = null;
        mirrorRootLayout = null;
        actionPanel = null;
        mirrorActionPanel = null;
        mainButton = null;
        mirrorMainButton = null;
        infoWindow = null;
        mirrorInfoWindow = null;
        cycleNoticeWindow = null;
        mirrorCycleNoticeWindow = null;
        log("service destroyed");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void refreshOverlayState() {
        boolean enabled = ModuleSettings.getGlobalFloatButtonEnabled(prefs);
        if (!enabled) {
            removeOverlay();
            removeMirrorOverlay();
            log("float button disabled, stop self");
            stopSelf();
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            maybeShowOverlayPermissionToast();
            removeOverlay();
            removeMirrorOverlay();
            return;
        }
        ensureOverlay();
        ensureMirrorOverlay();
        updateMainButtonStatus();
        updateInfoWindowStatus();
        syncEngineStateBroadcastIfNeeded();
    }

    private void maybeShowOverlayPermissionToast() {
        long now = System.currentTimeMillis();
        if (now - lastPermissionToastAt < PERMISSION_TOAST_MIN_INTERVAL_MS) {
            return;
        }
        lastPermissionToastAt = now;
        Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_LONG).show();
    }

    private void ensureOverlay() {
        Context overlayContext = resolveOverlayContext(0);
        if (windowManager == null) {
            windowManager = (WindowManager) overlayContext.getSystemService(WINDOW_SERVICE);
        }
        if (windowManager == null) {
            return;
        }
        if (rootLayout == null) {
            buildOverlayView(overlayContext);
        }
        if (isOverlayAttached()) {
            return;
        }
        removeOverlay();

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                flags,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        params.x = dp(12);
        params.y = 0;
        try {
            windowManager.addView(rootLayout, params);
            overlayAdded = true;
            attachedDisplayId = 0;
            log("overlay added on displayId=0 (global)");
        } catch (Throwable e) {
            overlayAdded = false;
            attachedDisplayId = -1;
            log("overlay add failed on displayId=0 (global): " + e);
        }
    }

    private void ensureMirrorOverlay() {
        if (preferredDisplayId < 0) {
            removeMirrorOverlay();
            mirrorWindowManager = null;
            mirrorAttachedDisplayId = -1;
            return;
        }
        if (preferredDisplayId == 0) {
            removeMirrorOverlay();
            mirrorWindowManager = null;
            mirrorAttachedDisplayId = -1;
            return;
        }
        if (mirrorAttachedDisplayId != preferredDisplayId) {
            removeMirrorOverlay();
            mirrorWindowManager = null;
        }
        if (mirrorWindowManager == null) {
            Context mirrorContext = resolveOverlayContext(preferredDisplayId);
            mirrorWindowManager = (WindowManager) mirrorContext.getSystemService(WINDOW_SERVICE);
        }
        if (mirrorWindowManager == null) {
            return;
        }
        if (mirrorRootLayout == null) {
            buildMirrorOverlayView(resolveOverlayContext(preferredDisplayId));
        }
        if (isMirrorOverlayAttached()) {
            return;
        }
        removeMirrorOverlay();

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                flags,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        params.x = dp(12);
        params.y = 0;
        try {
            mirrorWindowManager.addView(mirrorRootLayout, params);
            mirrorOverlayAdded = true;
            mirrorAttachedDisplayId = preferredDisplayId;
            log("overlay added on displayId=" + mirrorAttachedDisplayId + " (mirror)");
        } catch (Throwable e) {
            mirrorOverlayAdded = false;
            mirrorAttachedDisplayId = -1;
            log("overlay add failed on displayId=" + preferredDisplayId + " (mirror): " + e);
        }
    }

    private Context resolveOverlayContext(int displayId) {
        if (displayId >= 0) {
            try {
                DisplayManager dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
                if (dm != null) {
                    Display display = dm.getDisplay(displayId);
                    if (display != null) {
                        return createDisplayContext(display);
                    }
                }
            } catch (Throwable e) {
                log("resolve display context failed, displayId=" + displayId + " err=" + e);
            }
        }
        return this;
    }

    private void buildOverlayView(Context overlayContext) {
        LinearLayout root = new LinearLayout(overlayContext);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.END);
        root.setPadding(dp(6), dp(6), dp(6), dp(6));
        root.setBackgroundColor(Color.parseColor("#33222222"));
        rootLayout = root;

        TextView cycleNotice = createCycleNoticeTextView(overlayContext);
        cycleNoticeWindow = cycleNotice;
        cycleNotice.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleActionPanel();
            }
        });
        root.addView(cycleNotice);

        TextView info = createInfoTextView(overlayContext);
        infoWindow = info;
        infoWindow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleActionPanel();
            }
        });
        root.addView(info);

        LinearLayout row = new LinearLayout(overlayContext);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleActionPanel();
            }
        });
        root.addView(row);

        TextView toggleButton = createActionButton(overlayContext, "LSP", "#1E88E5");
        mainButton = toggleButton;
        LinearLayout.LayoutParams mainLp = (LinearLayout.LayoutParams) mainButton.getLayoutParams();
        mainLp.leftMargin = 0;
        mainButton.setLayoutParams(mainLp);
        mainButton.setMinWidth(dp(92));
        mainButton.setGravity(Gravity.CENTER);
        mainButton.setSingleLine(true);
        mainButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleActionPanel();
            }
        });
        mainButton.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event == null) {
                    return false;
                }
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_UP) {
                    v.performClick();
                    return true;
                }
                return action == MotionEvent.ACTION_DOWN;
            }
        });
        row.addView(toggleButton);

        LinearLayout actionPanel = new LinearLayout(overlayContext);
        actionPanel.setOrientation(LinearLayout.VERTICAL);
        actionPanel.setVisibility(View.GONE);
        actionPanel.setPadding(0, dp(6), 0, 0);
        actionPanel.setGravity(Gravity.END);
        LinearLayout.LayoutParams panelLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        panelLp.gravity = Gravity.END;
        actionPanel.setLayoutParams(panelLp);
        this.actionPanel = actionPanel;

        TextView runBtn = createActionButton(overlayContext, "运行", "#2E7D32");
        runBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onRunClicked();
            }
        });
        actionPanel.addView(runBtn);

        TextView pauseBtn = createActionButton(overlayContext, "暂停", "#EF6C00");
        pauseBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                long seq = ModuleSettings.pushEngineCommand(
                        GlobalFloatService.this,
                        ModuleSettings.ENGINE_CMD_PAUSE,
                        ModuleSettings.EngineStatus.PAUSED
                );
                dispatchEngineCommandBroadcast(
                        ModuleSettings.ENGINE_CMD_PAUSE,
                        ModuleSettings.EngineStatus.PAUSED,
                        seq
                );
                collapseActionPanel();
                updateMainButtonStatus();
                Toast.makeText(GlobalFloatService.this, "模块已暂停", Toast.LENGTH_SHORT).show();
            }
        });
        actionPanel.addView(pauseBtn);

        TextView stopBtn = createActionButton(overlayContext, "停止", "#C62828");
        stopBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                long seq = ModuleSettings.pushEngineCommand(
                        GlobalFloatService.this,
                        ModuleSettings.ENGINE_CMD_STOP,
                        ModuleSettings.EngineStatus.STOPPED
                );
                dispatchEngineCommandBroadcast(
                        ModuleSettings.ENGINE_CMD_STOP,
                        ModuleSettings.EngineStatus.STOPPED,
                        seq
                );
                collapseActionPanel();
                updateMainButtonStatus();
                Toast.makeText(GlobalFloatService.this, "模块已停止", Toast.LENGTH_SHORT).show();
            }
        });
        actionPanel.addView(stopBtn);

        root.addView(actionPanel);
    }

    private void buildMirrorOverlayView(Context overlayContext) {
        LinearLayout root = new LinearLayout(overlayContext);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.END);
        root.setPadding(dp(6), dp(6), dp(6), dp(6));
        root.setBackgroundColor(Color.parseColor("#33222222"));
        mirrorRootLayout = root;

        TextView cycleNotice = createCycleNoticeTextView(overlayContext);
        mirrorCycleNoticeWindow = cycleNotice;
        cycleNotice.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleActionPanel(mirrorActionPanel);
            }
        });
        root.addView(cycleNotice);

        TextView info = createInfoTextView(overlayContext);
        mirrorInfoWindow = info;
        info.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleActionPanel(mirrorActionPanel);
            }
        });
        root.addView(info);

        LinearLayout row = new LinearLayout(overlayContext);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleActionPanel(mirrorActionPanel);
            }
        });
        root.addView(row);

        TextView toggleButton = createActionButton(overlayContext, "LSP", "#1E88E5");
        mirrorMainButton = toggleButton;
        LinearLayout.LayoutParams mainLp = (LinearLayout.LayoutParams) toggleButton.getLayoutParams();
        mainLp.leftMargin = 0;
        toggleButton.setLayoutParams(mainLp);
        toggleButton.setMinWidth(dp(92));
        toggleButton.setGravity(Gravity.CENTER);
        toggleButton.setSingleLine(true);
        toggleButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleActionPanel(mirrorActionPanel);
            }
        });
        toggleButton.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event == null) {
                    return false;
                }
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_UP) {
                    v.performClick();
                    return true;
                }
                return action == MotionEvent.ACTION_DOWN;
            }
        });
        row.addView(toggleButton);

        LinearLayout panel = new LinearLayout(overlayContext);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setVisibility(View.GONE);
        panel.setPadding(0, dp(6), 0, 0);
        panel.setGravity(Gravity.END);
        LinearLayout.LayoutParams panelLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        panelLp.gravity = Gravity.END;
        panel.setLayoutParams(panelLp);
        mirrorActionPanel = panel;

        TextView runBtn = createActionButton(overlayContext, "运行", "#2E7D32");
        runBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onRunClicked();
            }
        });
        panel.addView(runBtn);

        TextView pauseBtn = createActionButton(overlayContext, "暂停", "#EF6C00");
        pauseBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                long seq = ModuleSettings.pushEngineCommand(
                        GlobalFloatService.this,
                        ModuleSettings.ENGINE_CMD_PAUSE,
                        ModuleSettings.EngineStatus.PAUSED
                );
                dispatchEngineCommandBroadcast(
                        ModuleSettings.ENGINE_CMD_PAUSE,
                        ModuleSettings.EngineStatus.PAUSED,
                        seq
                );
                collapseActionPanel();
                updateMainButtonStatus();
                Toast.makeText(GlobalFloatService.this, "模块已暂停", Toast.LENGTH_SHORT).show();
            }
        });
        panel.addView(pauseBtn);

        TextView stopBtn = createActionButton(overlayContext, "停止", "#C62828");
        stopBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                long seq = ModuleSettings.pushEngineCommand(
                        GlobalFloatService.this,
                        ModuleSettings.ENGINE_CMD_STOP,
                        ModuleSettings.EngineStatus.STOPPED
                );
                dispatchEngineCommandBroadcast(
                        ModuleSettings.ENGINE_CMD_STOP,
                        ModuleSettings.EngineStatus.STOPPED,
                        seq
                );
                collapseActionPanel();
                updateMainButtonStatus();
                Toast.makeText(GlobalFloatService.this, "模块已停止", Toast.LENGTH_SHORT).show();
            }
        });
        panel.addView(stopBtn);

        root.addView(panel);
    }

    private boolean isOverlayAttached() {
        if (!overlayAdded || rootLayout == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            return rootLayout.isAttachedToWindow();
        }
        return rootLayout.getWindowToken() != null;
    }

    private boolean isMirrorOverlayAttached() {
        if (!mirrorOverlayAdded || mirrorRootLayout == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            return mirrorRootLayout.isAttachedToWindow();
        }
        return mirrorRootLayout.getWindowToken() != null;
    }

    private void removeOverlay() {
        if (!overlayAdded && (rootLayout == null || rootLayout.getParent() == null)) {
            return;
        }
        if (windowManager == null || rootLayout == null) {
            overlayAdded = false;
            return;
        }
        try {
            windowManager.removeViewImmediate(rootLayout);
            log("overlay removed");
        } catch (Throwable e) {
            log("overlay remove failed: " + e);
        }
        overlayAdded = false;
        attachedDisplayId = -1;
    }

    private void removeMirrorOverlay() {
        if (!mirrorOverlayAdded && (mirrorRootLayout == null || mirrorRootLayout.getParent() == null)) {
            return;
        }
        if (mirrorWindowManager == null || mirrorRootLayout == null) {
            mirrorOverlayAdded = false;
            return;
        }
        try {
            mirrorWindowManager.removeViewImmediate(mirrorRootLayout);
            log("overlay removed (mirror)");
        } catch (Throwable e) {
            log("overlay remove failed (mirror): " + e);
        }
        mirrorOverlayAdded = false;
        mirrorAttachedDisplayId = -1;
    }

    private void onRunClicked() {
        long seq = ModuleSettings.pushEngineCommand(
                this,
                ModuleSettings.ENGINE_CMD_RUN,
                ModuleSettings.EngineStatus.RUNNING
        );
        dispatchEngineCommandBroadcast(
                ModuleSettings.ENGINE_CMD_RUN,
                ModuleSettings.EngineStatus.RUNNING,
                seq
        );
        collapseActionPanel();
        boolean running = isPackageRunning(UiComponentConfig.TARGET_PACKAGE);
        if (running) {
            forceStopPackage(UiComponentConfig.TARGET_PACKAGE);
        }
        launchTargetApp(UiComponentConfig.TARGET_PACKAGE);
        dispatchRunCommandAfterLaunch(seq);
        updateMainButtonStatus();
        Toast.makeText(this, "模块运行中", Toast.LENGTH_SHORT).show();
    }

    private void restartTargetAppForNextCycle() {
        if (prefs == null) {
            return;
        }
        ModuleSettings.EngineStatus status = ModuleSettings.getEngineStatus(prefs);
        if (status != ModuleSettings.EngineStatus.RUNNING) {
            log("ignore cycle restart request: engine status=" + status);
            return;
        }
        long seq = ModuleSettings.pushEngineCommand(
                this,
                ModuleSettings.ENGINE_CMD_RUN,
                ModuleSettings.EngineStatus.RUNNING
        );
        dispatchEngineCommandBroadcast(
                ModuleSettings.ENGINE_CMD_RUN,
                ModuleSettings.EngineStatus.RUNNING,
                seq
        );
        boolean running = isPackageRunning(UiComponentConfig.TARGET_PACKAGE);
        if (running) {
            forceStopPackage(UiComponentConfig.TARGET_PACKAGE);
        }
        launchTargetApp(UiComponentConfig.TARGET_PACKAGE);
        dispatchRunCommandAfterLaunch(seq);
        updateMainButtonStatus();
        log("cycle restart requested: relaunched target app, seq=" + seq);
    }

    private void dispatchRunCommandAfterLaunch(final long seq) {
        if (handler == null) {
            return;
        }
        long[] delays = new long[] { 400L, 1200L, 2200L };
        for (final long delay : delays) {
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    dispatchEngineCommandBroadcast(
                            ModuleSettings.ENGINE_CMD_RUN,
                            ModuleSettings.EngineStatus.RUNNING,
                            seq
                    );
                }
            }, delay);
        }
    }

    private void dispatchEngineCommandBroadcast(
            String command,
            ModuleSettings.EngineStatus status,
            long seq
    ) {
        try {
            Intent intent = new Intent(ModuleSettings.ACTION_ENGINE_COMMAND);
            intent.setPackage(UiComponentConfig.TARGET_PACKAGE);
            intent.putExtra(ModuleSettings.EXTRA_ENGINE_COMMAND, command);
            intent.putExtra(ModuleSettings.EXTRA_ENGINE_STATUS, status == null ? "" : status.name());
            intent.putExtra(ModuleSettings.EXTRA_ENGINE_COMMAND_SEQ, seq);
            sendBroadcast(intent);
        } catch (Throwable ignore) {
        }
    }

    private void syncEngineStateBroadcastIfNeeded() {
        if (prefs == null) {
            return;
        }
        long now = System.currentTimeMillis();
        ModuleSettings.EngineStatus status = ModuleSettings.getEngineStatus(prefs);
        long seq = ModuleSettings.getEngineCommandSeq(prefs);
        String command = ModuleSettings.getEngineCommand(prefs);
        if (TextUtils.isEmpty(command)) {
            if (status == ModuleSettings.EngineStatus.RUNNING) {
                command = ModuleSettings.ENGINE_CMD_RUN;
            } else if (status == ModuleSettings.EngineStatus.PAUSED) {
                command = ModuleSettings.ENGINE_CMD_PAUSE;
            } else {
                command = ModuleSettings.ENGINE_CMD_STOP;
            }
        }
        boolean seqChanged = (seq != lastRealtimeSyncedSeq);
        boolean runningHeartbeat = (status == ModuleSettings.EngineStatus.RUNNING
                && now - lastRealtimeSyncAt >= 3000L);
        if (!seqChanged && !runningHeartbeat) {
            return;
        }
        dispatchEngineCommandBroadcast(command, status, seq);
        lastRealtimeSyncedSeq = seq;
        lastRealtimeSyncAt = now;
    }

    private void updateMainButtonStatus() {
        ModuleSettings.EngineStatus status = ModuleSettings.getEngineStatus(prefs);
        applyMainButtonStatus(mainButton, status);
        applyMainButtonStatus(mirrorMainButton, status);
    }

    private void updateInfoWindowStatus() {
        boolean enabled = ModuleSettings.getFloatInfoWindowEnabled(prefs);
        ModuleSettings.EngineStatus status = ModuleSettings.getEngineStatus(prefs);
        int cycleLimit = ModuleSettings.getTogetherCycleLimit(prefs);
        int completed = ModuleSettings.getRuntimeCycleCompleted(prefs);
        int entered = ModuleSettings.getRuntimeCycleEntered(prefs);
        long runStartAt = ModuleSettings.getRuntimeRunStartAt(prefs);
        int currentCycle = status == ModuleSettings.EngineStatus.RUNNING
                ? Math.max(1, completed + 1)
                : Math.max(0, completed);
        String remaining = cycleLimit <= 0
                ? "无限"
                : String.valueOf(Math.max(0, cycleLimit - completed));
        long elapsedMs = 0L;
        if (status == ModuleSettings.EngineStatus.RUNNING) {
            boolean targetRunning = isPackageRunning(UiComponentConfig.TARGET_PACKAGE);
            if (!targetRunning) {
                runtimeStartFallbackAt = 0L;
                elapsedMs = 0L;
            } else {
            long effectiveStartAt = runStartAt;
            if (effectiveStartAt <= 0L) {
                if (runtimeStartFallbackAt <= 0L) {
                    runtimeStartFallbackAt = System.currentTimeMillis();
                }
                effectiveStartAt = runtimeStartFallbackAt;
            } else {
                runtimeStartFallbackAt = effectiveStartAt;
            }
            elapsedMs = Math.max(0L, System.currentTimeMillis() - effectiveStartAt);
            }
        } else {
            runtimeStartFallbackAt = 0L;
        }
        String text = "循环: " + currentCycle + "/" + remaining
                + "\n已进房: " + Math.max(0, entered)
                + "\n运行: " + formatElapsed(elapsedMs);
        applyInfoWindowStatus(infoWindow, enabled, text);
        applyInfoWindowStatus(mirrorInfoWindow, enabled, text);
    }

    private void showCycleCompleteNotice(String message) {
        String text = message == null ? "" : message.trim();
        if (TextUtils.isEmpty(text)) {
            return;
        }
        ensureOverlay();
        ensureMirrorOverlay();
        showCycleNoticeView(cycleNoticeWindow, text);
        showCycleNoticeView(mirrorCycleNoticeWindow, text);
        if (handler != null) {
            handler.removeCallbacks(hideCycleNoticeTask);
            handler.postDelayed(hideCycleNoticeTask, CYCLE_NOTICE_SHOW_MS);
        }
        try {
            Toast.makeText(this, text, Toast.LENGTH_LONG).show();
        } catch (Throwable ignore) {
        }
        log("show cycle complete notice: " + text);
    }

    private void applyMainButtonStatus(TextView button, ModuleSettings.EngineStatus status) {
        if (button == null) {
            return;
        }
        if (status == ModuleSettings.EngineStatus.RUNNING) {
            button.setText("LSP-运行中");
            button.setBackgroundColor(Color.parseColor("#2E7D32"));
        } else if (status == ModuleSettings.EngineStatus.PAUSED) {
            button.setText("LSP-已暂停");
            button.setBackgroundColor(Color.parseColor("#EF6C00"));
        } else {
            button.setText("LSP-未运行");
            button.setBackgroundColor(Color.parseColor("#C62828"));
        }
    }

    private void applyInfoWindowStatus(TextView view, boolean enabled, String text) {
        if (view == null) {
            return;
        }
        if (!enabled) {
            view.setVisibility(View.GONE);
            return;
        }
        view.setVisibility(View.VISIBLE);
        view.setText(text);
    }

    private void showCycleNoticeView(TextView view, String text) {
        if (view == null) {
            return;
        }
        view.setText(text);
        view.setAlpha(1f);
        view.setVisibility(View.VISIBLE);
    }

    private void hideCycleNoticeView(TextView view) {
        if (view == null) {
            return;
        }
        try {
            view.animate().alpha(0f).setDuration(280L).withEndAction(new Runnable() {
                @Override
                public void run() {
                    view.setVisibility(View.GONE);
                    view.setAlpha(1f);
                }
            }).start();
        } catch (Throwable ignore) {
            view.setVisibility(View.GONE);
            view.setAlpha(1f);
        }
    }

    private String formatElapsed(long elapsedMs) {
        long totalSeconds = Math.max(0L, elapsedMs / 1000L);
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        return twoDigits(hours) + ":" + twoDigits(minutes) + ":" + twoDigits(seconds);
    }

    private String twoDigits(long value) {
        long safe = Math.max(0L, value);
        if (safe < 10L) {
            return "0" + safe;
        }
        return String.valueOf(safe);
    }

    private void launchTargetApp(String packageName) {
        try {
            Intent launchIntent = getPackageManager().getLaunchIntentForPackage(packageName);
            if (launchIntent == null) {
                Toast.makeText(this, "未找到 LOOK 启动入口", Toast.LENGTH_SHORT).show();
                return;
            }
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(launchIntent);
        } catch (Throwable e) {
            Toast.makeText(this, "启动 LOOK 失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private boolean isPackageRunning(String packageName) {
        if (TextUtils.isEmpty(packageName)) {
            return false;
        }
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) {
                return false;
            }
            List<ActivityManager.RunningAppProcessInfo> infos = am.getRunningAppProcesses();
            if (infos == null) {
                return false;
            }
            for (ActivityManager.RunningAppProcessInfo info : infos) {
                if (info == null) {
                    continue;
                }
                if (packageName.equals(info.processName)) {
                    return true;
                }
                if (info.pkgList != null) {
                    for (String pkg : info.pkgList) {
                        if (packageName.equals(pkg)) {
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable ignore) {
        }
        return false;
    }

    private void forceStopPackage(String packageName) {
        if (TextUtils.isEmpty(packageName)) {
            return;
        }
        boolean done = runCommandAsRoot("am force-stop " + packageName);
        if (!done) {
            runCommandNormally("am force-stop " + packageName);
        }
        try {
            Thread.sleep(450L);
        } catch (InterruptedException ignore) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean runCommandAsRoot(String command) {
        Process process = null;
        DataOutputStream os = null;
        try {
            process = Runtime.getRuntime().exec("su");
            os = new DataOutputStream(process.getOutputStream());
            os.writeBytes(command + "\n");
            os.writeBytes("exit\n");
            os.flush();
            int code = process.waitFor();
            return code == 0;
        } catch (Throwable ignore) {
            return false;
        } finally {
            closeQuietly(os);
            if (process != null) {
                process.destroy();
            }
        }
    }

    private void runCommandNormally(String command) {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"sh", "-c", command});
            process.waitFor();
        } catch (Throwable ignore) {
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private static void closeQuietly(DataOutputStream os) {
        if (os == null) {
            return;
        }
        try {
            os.close();
        } catch (Throwable ignore) {
        }
    }

    private void collapseActionPanel() {
        if (actionPanel != null) {
            actionPanel.setVisibility(View.GONE);
        }
        if (mirrorActionPanel != null) {
            mirrorActionPanel.setVisibility(View.GONE);
        }
    }

    private void toggleActionPanel() {
        toggleActionPanel(actionPanel);
    }

    private void toggleActionPanel(LinearLayout panel) {
        if (panel == null) {
            return;
        }
        if (panel == actionPanel && mirrorActionPanel != null) {
            mirrorActionPanel.setVisibility(View.GONE);
        } else if (panel == mirrorActionPanel && actionPanel != null) {
            actionPanel.setVisibility(View.GONE);
        }
        boolean show = panel.getVisibility() != View.VISIBLE;
        panel.setVisibility(show ? View.VISIBLE : View.GONE);
        log("toggle actionPanel -> " + (show ? "VISIBLE" : "GONE"));
    }

    private TextView createActionButton(Context context, String text, String colorHex) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setPadding(dp(10), dp(6), dp(10), dp(6));
        tv.setBackgroundColor(Color.parseColor(colorHex));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lp.topMargin = dp(4);
        lp.gravity = Gravity.END;
        tv.setLayoutParams(lp);
        return tv;
    }

    private TextView createInfoTextView(Context context) {
        TextView tv = new TextView(context);
        tv.setTextColor(Color.parseColor("#EDEDED"));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        tv.setPadding(dp(8), dp(4), dp(8), dp(4));
        tv.setBackgroundColor(Color.parseColor("#66333333"));
        tv.setSingleLine(false);
        tv.setMaxWidth(dp(168));
        tv.setMinWidth(dp(132));
        tv.setGravity(Gravity.START);
        tv.setText("循环: 0/无限\n已进房: 0\n运行: 00:00:00");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lp.bottomMargin = dp(6);
        tv.setLayoutParams(lp);
        return tv;
    }

    private TextView createCycleNoticeTextView(Context context) {
        TextView tv = new TextView(context);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setPadding(dp(8), dp(5), dp(8), dp(5));
        tv.setBackgroundColor(Color.parseColor("#CC1B5E20"));
        tv.setVisibility(View.GONE);
        tv.setAlpha(1f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lp.bottomMargin = dp(6);
        tv.setLayoutParams(lp);
        return tv;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void startForegroundInternal() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "LOOK LSP 悬浮服务",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("提供全局悬浮按钮控制模块运行状态");
            nm.createNotificationChannel(channel);
        }
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        Notification notification = builder
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("LOOK LSP 模块")
                .setContentText("全局悬浮按钮服务运行中")
                .setOngoing(true)
                .build();
        try {
            startForeground(NOTIFICATION_ID, notification);
        } catch (Throwable e) {
            Toast.makeText(this, "悬浮服务启动失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void log(String msg) {
        try {
            Log.i(TAG, "[FloatService] " + msg);
        } catch (Throwable ignore) {
        }
    }
}
