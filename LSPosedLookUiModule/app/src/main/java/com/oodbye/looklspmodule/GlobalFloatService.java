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

    private SharedPreferences prefs;
    private Handler handler;
    private long lastRealtimeSyncAt;
    private long lastRealtimeSyncedSeq;
    private WindowManager windowManager;
    private LinearLayout rootLayout;
    private LinearLayout actionPanel;
    private TextView mainButton;
    private boolean overlayAdded;
    private long lastPermissionToastAt;
    private int preferredDisplayId = -1;
    private int attachedDisplayId = -1;
    private final Runnable refreshTask = new Runnable() {
        @Override
        public void run() {
            refreshOverlayState();
            handler.postDelayed(this, DISPLAY_REFRESH_INTERVAL_MS);
        }
    };

    public static void startServiceCompat(Context context) {
        startServiceCompat(context, -1, false);
    }

    public static void startServiceCompat(Context context, int targetDisplayId) {
        startServiceCompat(context, targetDisplayId, false);
    }

    public static void startServiceCompat(
            Context context,
            int targetDisplayId,
            boolean requestRestartTargetApp
    ) {
        if (context == null) {
            return;
        }
        Intent intent = new Intent(context, GlobalFloatService.class);
        intent.putExtra(ModuleSettings.EXTRA_TARGET_DISPLAY_ID, targetDisplayId);
        intent.putExtra(
                ModuleSettings.EXTRA_REQUEST_RESTART_TARGET_APP,
                requestRestartTargetApp
        );
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
        }
        if (hasDisplayExtra && targetDisplayId != preferredDisplayId) {
            preferredDisplayId = targetDisplayId;
            removeOverlay();
            windowManager = null;
            attachedDisplayId = -1;
            log("switch target displayId=" + preferredDisplayId);
        }
        refreshOverlayState();
        if (requestRestartTargetApp) {
            restartTargetAppForNextCycle();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (handler != null) {
            handler.removeCallbacks(refreshTask);
        }
        removeOverlay();
        windowManager = null;
        rootLayout = null;
        actionPanel = null;
        mainButton = null;
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
            log("float button disabled, stop self");
            stopSelf();
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            maybeShowOverlayPermissionToast();
            removeOverlay();
            return;
        }
        ensureOverlay();
        updateMainButtonStatus();
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
        if (windowManager == null) {
            windowManager = resolveWindowManagerForDisplay(preferredDisplayId);
        }
        if (windowManager == null) {
            return;
        }
        if (rootLayout == null) {
            buildOverlayView();
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
            attachedDisplayId = preferredDisplayId;
            log("overlay added on displayId=" + attachedDisplayId);
        } catch (Throwable e) {
            overlayAdded = false;
            attachedDisplayId = -1;
            log("overlay add failed on displayId=" + preferredDisplayId + ": " + e);
        }
    }

    private WindowManager resolveWindowManagerForDisplay(int displayId) {
        if (displayId >= 0) {
            try {
                DisplayManager dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
                if (dm != null) {
                    Display display = dm.getDisplay(displayId);
                    if (display != null) {
                        Context displayContext = createDisplayContext(display);
                        WindowManager wm = (WindowManager) displayContext.getSystemService(WINDOW_SERVICE);
                        if (wm != null) {
                            return wm;
                        }
                    }
                }
            } catch (Throwable e) {
                log("resolve display window manager failed, displayId=" + displayId + " err=" + e);
            }
        }
        return (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    private void buildOverlayView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding(dp(6), dp(6), dp(6), dp(6));
        root.setBackgroundColor(Color.parseColor("#33222222"));
        rootLayout = root;

        TextView toggleButton = createActionButton(this, "LSP", "#1E88E5");
        mainButton = toggleButton;
        mainButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (actionPanel == null) {
                    return;
                }
                actionPanel.setVisibility(
                        actionPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE
                );
            }
        });
        root.addView(toggleButton);

        LinearLayout actionPanel = new LinearLayout(this);
        actionPanel.setOrientation(LinearLayout.HORIZONTAL);
        actionPanel.setVisibility(View.GONE);
        actionPanel.setPadding(dp(6), 0, 0, 0);
        this.actionPanel = actionPanel;

        TextView runBtn = createActionButton(this, "运行", "#2E7D32");
        runBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onRunClicked();
            }
        });
        actionPanel.addView(runBtn);

        TextView pauseBtn = createActionButton(this, "暂停", "#EF6C00");
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

        TextView stopBtn = createActionButton(this, "停止", "#C62828");
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

    private boolean isOverlayAttached() {
        if (!overlayAdded || rootLayout == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            return rootLayout.isAttachedToWindow();
        }
        return rootLayout.getWindowToken() != null;
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
        if (mainButton == null) {
            return;
        }
        ModuleSettings.EngineStatus status = ModuleSettings.getEngineStatus(prefs);
        if (status == ModuleSettings.EngineStatus.RUNNING) {
            mainButton.setText("LSP-运行中");
            mainButton.setBackgroundColor(Color.parseColor("#2E7D32"));
        } else if (status == ModuleSettings.EngineStatus.PAUSED) {
            mainButton.setText("LSP-已暂停");
            mainButton.setBackgroundColor(Color.parseColor("#EF6C00"));
        } else {
            mainButton.setText("LSP-未运行");
            mainButton.setBackgroundColor(Color.parseColor("#C62828"));
        }
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
        lp.leftMargin = dp(4);
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
