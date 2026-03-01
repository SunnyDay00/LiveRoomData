package com.oodbye.looklspmodule;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.DataOutputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class GlobalFloatService extends Service {
    private static final String CHANNEL_ID = "look_lsp_float_service";
    private static final int NOTIFICATION_ID = 2026030101;
    private static final long DISPLAY_REFRESH_INTERVAL_MS = 2000L;

    private SharedPreferences prefs;
    private Handler handler;
    private final Map<Integer, OverlayHolder> overlays = new HashMap<Integer, OverlayHolder>();
    private final Runnable refreshTask = new Runnable() {
        @Override
        public void run() {
            refreshOverlayState();
            handler.postDelayed(this, DISPLAY_REFRESH_INTERVAL_MS);
        }
    };

    public static void startServiceCompat(Context context) {
        if (context == null) {
            return;
        }
        Intent intent = new Intent(context, GlobalFloatService.class);
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
        startForegroundInternal();
        refreshOverlayState();
        handler.postDelayed(refreshTask, DISPLAY_REFRESH_INTERVAL_MS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        refreshOverlayState();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (handler != null) {
            handler.removeCallbacks(refreshTask);
        }
        removeOverlay();
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
            stopSelf();
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_LONG).show();
            removeOverlay();
            return;
        }
        ensureOverlay();
        updateMainButtonStatus();
    }

    private void ensureOverlay() {
        DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        if (dm == null) {
            return;
        }
        Display[] displays = dm.getDisplays();
        if (displays == null || displays.length == 0) {
            return;
        }
        Set<Integer> activeDisplayIds = new HashSet<Integer>();
        for (Display display : displays) {
            if (display == null || display.getState() == Display.STATE_OFF) {
                continue;
            }
            int displayId = display.getDisplayId();
            activeDisplayIds.add(displayId);
            if (!overlays.containsKey(displayId)) {
                createOverlayForDisplay(display);
            }
        }
        Set<Integer> toRemove = new HashSet<Integer>();
        toRemove.addAll(overlays.keySet());
        toRemove.removeAll(activeDisplayIds);
        for (Integer displayId : toRemove) {
            removeOverlayForDisplay(displayId.intValue());
        }
    }

    private void createOverlayForDisplay(Display display) {
        Context displayContext = createDisplayContext(display);
        WindowManager wm = (WindowManager) displayContext.getSystemService(WINDOW_SERVICE);
        if (wm == null) {
            return;
        }
        final OverlayHolder holder = new OverlayHolder();
        holder.displayId = display.getDisplayId();
        holder.windowManager = wm;

        LinearLayout rootLayout = new LinearLayout(displayContext);
        rootLayout.setOrientation(LinearLayout.HORIZONTAL);
        rootLayout.setGravity(Gravity.CENTER_VERTICAL);
        rootLayout.setPadding(dp(6), dp(6), dp(6), dp(6));
        rootLayout.setBackgroundColor(Color.parseColor("#33222222"));
        holder.rootLayout = rootLayout;

        TextView mainButton = createActionButton(displayContext, "LSP", "#1E88E5");
        holder.mainButton = mainButton;
        mainButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (holder.actionPanel == null) {
                    return;
                }
                holder.actionPanel.setVisibility(
                        holder.actionPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE
                );
            }
        });
        rootLayout.addView(mainButton);

        LinearLayout actionPanel = new LinearLayout(displayContext);
        actionPanel.setOrientation(LinearLayout.HORIZONTAL);
        actionPanel.setVisibility(View.GONE);
        actionPanel.setPadding(dp(6), 0, 0, 0);
        holder.actionPanel = actionPanel;

        TextView runBtn = createActionButton(displayContext, "运行", "#2E7D32");
        runBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onRunClicked();
            }
        });
        actionPanel.addView(runBtn);

        TextView pauseBtn = createActionButton(displayContext, "暂停", "#EF6C00");
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
                updateMainButtonStatus();
                Toast.makeText(GlobalFloatService.this, "模块已暂停", Toast.LENGTH_SHORT).show();
            }
        });
        actionPanel.addView(pauseBtn);

        TextView stopBtn = createActionButton(displayContext, "停止", "#C62828");
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
                updateMainButtonStatus();
                Toast.makeText(GlobalFloatService.this, "模块已停止", Toast.LENGTH_SHORT).show();
            }
        });
        actionPanel.addView(stopBtn);

        rootLayout.addView(actionPanel);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        params.x = dp(12);
        params.y = 0;
        try {
            wm.addView(rootLayout, params);
            overlays.put(holder.displayId, holder);
        } catch (Throwable e) {
            Toast.makeText(this, "display " + holder.displayId + " 悬浮窗创建失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void removeOverlay() {
        Set<Integer> ids = new HashSet<Integer>(overlays.keySet());
        for (Integer id : ids) {
            removeOverlayForDisplay(id.intValue());
        }
    }

    private void removeOverlayForDisplay(int displayId) {
        OverlayHolder holder = overlays.remove(displayId);
        if (holder == null || holder.windowManager == null || holder.rootLayout == null) {
            return;
        }
        try {
            holder.windowManager.removeView(holder.rootLayout);
        } catch (Throwable ignore) {
        }
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
        boolean running = isPackageRunning(UiComponentConfig.TARGET_PACKAGE);
        if (running) {
            forceStopPackage(UiComponentConfig.TARGET_PACKAGE);
        }
        launchTargetApp(UiComponentConfig.TARGET_PACKAGE);
        updateMainButtonStatus();
        Toast.makeText(this, "模块运行中", Toast.LENGTH_SHORT).show();
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

    private void updateMainButtonStatus() {
        ModuleSettings.EngineStatus status = ModuleSettings.getEngineStatus(prefs);
        for (OverlayHolder holder : overlays.values()) {
            if (holder == null || holder.mainButton == null) {
                continue;
            }
            if (status == ModuleSettings.EngineStatus.RUNNING) {
                holder.mainButton.setText("LSP·运行");
                holder.mainButton.setBackgroundColor(Color.parseColor("#2E7D32"));
            } else if (status == ModuleSettings.EngineStatus.PAUSED) {
                holder.mainButton.setText("LSP·暂停");
                holder.mainButton.setBackgroundColor(Color.parseColor("#EF6C00"));
            } else {
                holder.mainButton.setText("LSP·停止");
                holder.mainButton.setBackgroundColor(Color.parseColor("#C62828"));
            }
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
        Intent openSettings = new Intent(this, ModuleSettingsActivity.class);
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? (PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, openSettings, flags);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        Notification notification = builder
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("LOOK LSP 模块")
                .setContentText("全局悬浮按钮服务运行中")
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
        try {
            startForeground(NOTIFICATION_ID, notification);
        } catch (Throwable e) {
            Toast.makeText(this, "悬浮服务启动失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private static final class OverlayHolder {
        int displayId;
        WindowManager windowManager;
        LinearLayout rootLayout;
        LinearLayout actionPanel;
        TextView mainButton;
    }
}
