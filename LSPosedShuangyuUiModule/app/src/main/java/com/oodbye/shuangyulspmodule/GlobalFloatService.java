package com.oodbye.shuangyulspmodule;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import android.content.pm.PackageManager;
import android.content.ComponentName;
import android.net.Uri;
import android.text.TextUtils;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 全局悬浮按钮与信息窗口服务。
 */
public class GlobalFloatService extends Service {
    private static final String TAG = "SYLspModule";
    private static final String CHANNEL_ID = "shuangyu_float_service";
    private static final int NOTIFICATION_ID = 20001;

    private WindowManager windowManager;
    private View floatButton;
    private View infoWindow;
    private boolean infoWindowVisible = false;

    // 信息窗口内容
    private TextView tvEngineStatus;
    private TextView tvCycleInfo;
    private TextView tvRuntime;
    private TextView tvLogOutput;

    // 拖拽状态
    private int lastX, lastY;
    private int downX, downY;
    private boolean isDragging;
    private int currentDisplayId = -1;
    private Handler keepAliveHandler;
    private static final long KEEP_ALIVE_INTERVAL_MS = 3000L;

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        // 悬浮按钮已由无障碍服务使用 TYPE_ACCESSIBILITY_OVERLAY 创建，
        // GlobalFloatService 不再创建自己的悬浮按钮
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            handleIntent(intent);
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopKeepAlive();
        removeFloatButton();
        removeInfoWindow();
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.w(TAG, "onTaskRemoved: 服务被清理，尝试自动重启");
        // 被用户滑动清除后台时自动重启服务
        try {
            Intent restartIntent = new Intent(this, GlobalFloatService.class);
            startForegroundService(restartIntent);
        } catch (Throwable e) {
            Log.e(TAG, "自动重启失败", e);
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Log.i(TAG, "onConfigurationChanged: 屏幕配置变更，重建悬浮按钮");
        // 屏幕旋转、多屏连接/断开、分屏模式切换时重建悬浮按钮
        recreateFloatButton();
    }

    // ─────────────────────── 通知栏 ───────────────────────

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "双鱼采集悬浮服务",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent intent = new Intent(this, ModuleSettingsActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        return builder
                .setContentTitle("双鱼直播数据采集")
                .setContentText("悬浮服务运行中")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    // ─────────────────────── 意图处理 ───────────────────────

    private void handleIntent(Intent intent) {
        int displayId = intent.getIntExtra(ModuleSettings.EXTRA_TARGET_DISPLAY_ID, -1);
        boolean restart = intent.getBooleanExtra(
                ModuleSettings.EXTRA_REQUEST_RESTART_TARGET_APP, false);
        Log.i(TAG, "FloatService intent: displayId=" + displayId + " restart=" + restart);

        // 如果 displayId 变化，重建悬浮按钮以确保可见
        if (displayId >= 0 && displayId != currentDisplayId) {
            Log.i(TAG, "Display 变更: " + currentDisplayId + " -> " + displayId + "，重建悬浮按钮");
            currentDisplayId = displayId;
            recreateFloatButton();
        }
    }

    // ─────────────────────── 保活机制 ───────────────────────

    private void startKeepAlive() {
        keepAliveHandler = new Handler(Looper.getMainLooper());
        keepAliveHandler.postDelayed(keepAliveRunnable, KEEP_ALIVE_INTERVAL_MS);
    }

    private void stopKeepAlive() {
        if (keepAliveHandler != null) {
            keepAliveHandler.removeCallbacks(keepAliveRunnable);
        }
    }

    private final Runnable keepAliveRunnable = new Runnable() {
        @Override
        public void run() {
            // 检查悬浮按钮是否仍然存在并可见
            boolean needRecreate = false;
            if (floatButton == null) {
                Log.w(TAG, "悬浮按钮为 null，重新创建");
                needRecreate = true;
            } else if (!floatButton.isAttachedToWindow()) {
                Log.w(TAG, "悬浮按钮未附加到窗口，重新创建");
                needRecreate = true;
            } else if (!floatButton.isShown()) {
                Log.w(TAG, "悬浮按钮不可见（可能被多屏切换隐藏），重新创建");
                needRecreate = true;
            } else {
                // 额外验证：尝试更新一下 layout，如果抛异常说明视图已失效
                try {
                    WindowManager.LayoutParams lp =
                            (WindowManager.LayoutParams) floatButton.getLayoutParams();
                    if (lp != null) {
                        windowManager.updateViewLayout(floatButton, lp);
                    }
                } catch (Throwable e) {
                    Log.w(TAG, "悬浮按钮 layout 刷新失败，重新创建: " + e.getMessage());
                    needRecreate = true;
                }
            }

            if (needRecreate) {
                recreateFloatButton();
            }

            if (keepAliveHandler != null) {
                keepAliveHandler.postDelayed(this, KEEP_ALIVE_INTERVAL_MS);
            }
        }
    };

    private void recreateFloatButton() {
        removeFloatButton();
        removeInfoWindow();
        // 延迟 100ms 重建，给系统时间清理旧视图（多屏场景下重要）
        if (keepAliveHandler != null) {
            keepAliveHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    addFloatButton();
                }
            }, 100);
        } else {
            addFloatButton();
        }
    }

    // ─────────────────────── 悬浮按钮 ───────────────────────

    private void addFloatButton() {
        if (!canDrawOverlays()) {
            Log.w(TAG, "No overlay permission");
            return;
        }

        floatButton = createFloatButtonView();

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                dp(56), dp(56),
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = dp(16);
        params.y = dp(200);

        floatButton.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                return handleFloatTouch(v, event, (WindowManager.LayoutParams) v.getLayoutParams());
            }
        });

        try {
            windowManager.addView(floatButton, params);
            Log.i(TAG, "悬浮按钮已添加");
        } catch (Throwable e) {
            Log.e(TAG, "添加悬浮按钮失败，将在 1 秒后重试: " + e.getMessage());
            floatButton = null;
            // 延迟 1 秒后重试一次
            if (keepAliveHandler != null) {
                keepAliveHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (floatButton == null) {
                            addFloatButton();
                        }
                    }
                }, 1000);
            }
        }
    }

    private View createFloatButtonView() {
        TextView tv = new TextView(this);
        tv.setText("鱼");
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(18);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setBackgroundColor(Color.parseColor("#CC2196F3"));
        tv.setPadding(dp(4), dp(4), dp(4), dp(4));
        return tv;
    }

    private boolean handleFloatTouch(View v, MotionEvent event,
                                      WindowManager.LayoutParams params) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                downX = (int) event.getRawX();
                downY = (int) event.getRawY();
                lastX = params.x;
                lastY = params.y;
                isDragging = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                int dx = (int) event.getRawX() - downX;
                int dy = (int) event.getRawY() - downY;
                if (Math.abs(dx) > dp(5) || Math.abs(dy) > dp(5)) {
                    isDragging = true;
                }
                if (isDragging) {
                    params.x = lastX + dx;
                    params.y = lastY + dy;
                    try {
                        windowManager.updateViewLayout(v, params);
                    } catch (Throwable ignore) {
                    }
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (!isDragging) {
                    onFloatButtonClick();
                }
                return true;
        }
        return false;
    }

    private void onFloatButtonClick() {
        if (infoWindowVisible) {
            removeInfoWindow();
        } else {
            showInfoWindow();
        }
    }

    private void removeFloatButton() {
        if (floatButton != null) {
            try {
                windowManager.removeView(floatButton);
            } catch (Throwable ignore) {
            }
            floatButton = null;
        }
    }

    // ─────────────────────── 信息窗口 ───────────────────────

    private void showInfoWindow() {
        if (!canDrawOverlays() || infoWindowVisible) return;

        infoWindow = createInfoWindowView();

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                dp(300), dp(400),
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.CENTER;

        try {
            windowManager.addView(infoWindow, params);
            infoWindowVisible = true;
            refreshInfoContent();
        } catch (Throwable e) {
            Log.e(TAG, "Failed to show info window", e);
        }
    }

    private View createInfoWindowView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#F0FFFFFF"));
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        // 标题
        TextView titleTv = new TextView(this);
        titleTv.setText("双鱼直播数据采集");
        titleTv.setTextSize(16);
        titleTv.setTypeface(null, Typeface.BOLD);
        titleTv.setTextColor(Color.parseColor("#1565C0"));
        root.addView(titleTv);

        // 分隔线
        View div = new View(this);
        div.setBackgroundColor(Color.parseColor("#E0E0E0"));
        LinearLayout.LayoutParams divParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        divParams.topMargin = dp(8);
        divParams.bottomMargin = dp(8);
        div.setLayoutParams(divParams);
        root.addView(div);

        // 引擎状态
        tvEngineStatus = new TextView(this);
        tvEngineStatus.setTextSize(13);
        tvEngineStatus.setTextColor(Color.parseColor("#424242"));
        root.addView(tvEngineStatus);

        // 循环信息
        tvCycleInfo = new TextView(this);
        tvCycleInfo.setTextSize(13);
        tvCycleInfo.setTextColor(Color.parseColor("#424242"));
        LinearLayout.LayoutParams cycleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        cycleParams.topMargin = dp(4);
        tvCycleInfo.setLayoutParams(cycleParams);
        root.addView(tvCycleInfo);

        // 运行时间
        tvRuntime = new TextView(this);
        tvRuntime.setTextSize(13);
        tvRuntime.setTextColor(Color.parseColor("#424242"));
        LinearLayout.LayoutParams rtParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rtParams.topMargin = dp(4);
        tvRuntime.setLayoutParams(rtParams);
        root.addView(tvRuntime);

        // 日志输出
        ScrollView logScroll = new ScrollView(this);
        LinearLayout.LayoutParams logScrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(160));
        logScrollParams.topMargin = dp(8);
        logScroll.setLayoutParams(logScrollParams);
        logScroll.setBackgroundColor(Color.parseColor("#FAFAFA"));

        tvLogOutput = new TextView(this);
        tvLogOutput.setTextSize(11);
        tvLogOutput.setTextColor(Color.parseColor("#616161"));
        tvLogOutput.setPadding(dp(8), dp(8), dp(8), dp(8));
        logScroll.addView(tvLogOutput);
        root.addView(logScroll);

        // 操作按钮行
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams btnRowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        btnRowParams.topMargin = dp(12);
        btnRow.setLayoutParams(btnRowParams);

        // Start/Stop 按钮
        TextView startBtn = createActionButton("启动", Color.parseColor("#4CAF50"));
        startBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onStartButtonClick();
            }
        });
        btnRow.addView(startBtn);

        TextView stopBtn = createActionButton("停止", Color.parseColor("#F44336"));
        LinearLayout.LayoutParams stopParams = (LinearLayout.LayoutParams) stopBtn.getLayoutParams();
        if (stopParams == null) {
            stopParams = new LinearLayout.LayoutParams(0, dp(40), 1);
        }
        stopParams.leftMargin = dp(8);
        stopBtn.setLayoutParams(stopParams);
        stopBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ShuangyuAccessibilityAdService a11y =
                        ShuangyuAccessibilityAdService.getInstance();
                if (a11y != null) {
                    a11y.stopCollection();
                    appendLog("🛑 采集已停止");
                }
                sendEngineCommand(ModuleSettings.ENGINE_CMD_STOP);
            }
        });
        btnRow.addView(stopBtn);

        // 关闭按钮
        TextView closeBtn = createActionButton("关闭", Color.parseColor("#757575"));
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(0, dp(40), 1);
        closeParams.leftMargin = dp(8);
        closeBtn.setLayoutParams(closeParams);
        closeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                removeInfoWindow();
            }
        });
        btnRow.addView(closeBtn);

        root.addView(btnRow);
        return root;
    }

    private TextView createActionButton(String text, int bgColor) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setTextColor(Color.WHITE);
        tv.setBackgroundColor(bgColor);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(8), dp(8), dp(8), dp(8));
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, dp(40), 1));
        return tv;
    }

    private void refreshInfoContent() {
        SharedPreferences prefs = ModuleSettings.appPrefs(this);
        ModuleSettings.EngineStatus status = ModuleSettings.getEngineStatus(prefs);
        int completed = ModuleSettings.getRuntimeCycleCompleted(prefs);
        int entered = ModuleSettings.getRuntimeCycleEntered(prefs);
        int limit = ModuleSettings.getCycleLimit(prefs);

        if (tvEngineStatus != null) {
            tvEngineStatus.setText("引擎状态: " + status.name());
        }
        if (tvCycleInfo != null) {
            tvCycleInfo.setText("循环: " + completed + "/" + limit + " (已进入 " + entered + " 个房间)");
        }
        if (tvRuntime != null) {
            SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss", Locale.US);
            tvRuntime.setText("刷新时间: " + fmt.format(new Date()));
        }
    }

    private void removeInfoWindow() {
        if (infoWindow != null) {
            try {
                windowManager.removeView(infoWindow);
            } catch (Throwable ignore) {
            }
            infoWindow = null;
            infoWindowVisible = false;
        }
    }

    // ─────────────────────── 引擎命令 ───────────────────────

    private void sendEngineCommand(String command) {
        long seq = System.currentTimeMillis();
        SharedPreferences.Editor editor = ModuleSettings.appPrefs(this).edit();
        editor.putString(ModuleSettings.KEY_ENGINE_COMMAND, command);
        editor.putLong(ModuleSettings.KEY_ENGINE_COMMAND_SEQ, seq);
        editor.apply();

        // 广播命令到目标应用进程
        Intent intent = new Intent(ModuleSettings.ACTION_ENGINE_COMMAND);
        intent.setPackage(UiComponentConfig.TARGET_PACKAGE);
        intent.putExtra(ModuleSettings.EXTRA_ENGINE_COMMAND, command);
        intent.putExtra(ModuleSettings.EXTRA_ENGINE_COMMAND_SEQ, seq);
        try {
            sendBroadcast(intent);
            Log.i(TAG, "已发送引擎命令: " + command + " seq=" + seq + " -> " + UiComponentConfig.TARGET_PACKAGE);
        } catch (Throwable e) {
            Log.e(TAG, "Failed to send engine command", e);
        }

        // 用户反馈
        appendLog(ModuleSettings.ENGINE_CMD_START.equals(command) ? "已发送启动命令" : "已发送停止命令");
        refreshInfoContent();
    }

    // ─────────────────────── 工具方法 ───────────────────────

    private boolean canDrawOverlays() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(this);
        }
        return true;
    }

    /** 追加日志到信息窗口 */
    void appendLog(String text) {
        if (tvLogOutput == null) return;
        try {
            SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss", Locale.US);
            String line = fmt.format(new Date()) + " " + text + "\n";
            tvLogOutput.append(line);
        } catch (Throwable ignore) {
        }
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return (int) (value * density + 0.5f);
    }

    // ════════════════════ 启动前置检查 ════════════════════

    /**
     * 启动按钮点击处理：执行一系列前置检查，全部通过后才启动目标应用并发送命令。
     */
    private void onStartButtonClick() {
        Log.i(TAG, "启动按钮被点击，开始前置检查...");
        appendLog("开始前置检查...");

        // 1. 检查目标应用是否已安装
        if (!isTargetAppInstalled()) {
            String msg = "目标应用未安装: " + UiComponentConfig.TARGET_PACKAGE;
            Log.e(TAG, msg);
            appendLog("❌ " + msg);
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            return;
        }
        appendLog("✅ 目标应用已安装");

        // 2. 检查悬浮窗权限
        if (!canDrawOverlays()) {
            appendLog("❌ 未授予悬浮窗权限");
            Toast.makeText(this, "请授予悬浮窗权限", Toast.LENGTH_LONG).show();
            openOverlayPermissionSettings();
            return;
        }
        appendLog("✅ 悬浮窗权限已授予");

        // 3. 检查无障碍服务权限
        if (!isAccessibilityServiceEnabled()) {
            appendLog("❌ 无障碍服务未开启");
            Toast.makeText(this, "请开启无障碍服务权限", Toast.LENGTH_LONG).show();
            openAccessibilitySettings();
            return;
        }
        appendLog("✅ 无障碍服务已开启");

        // 4. 检查飞书 Webhook（如果启用了飞书推送）
        SharedPreferences prefs = ModuleSettings.appPrefs(this);
        if (ModuleSettings.getFeishuPushEnabled(prefs)) {
            appendLog("⛳ 正在测试飞书连接...");
            final String webhookUrl = ModuleSettings.getFeishuWebhookUrl(prefs);
            final String signSecret = ModuleSettings.getFeishuSignSecret(prefs);
            if (TextUtils.isEmpty(webhookUrl)) {
                appendLog("❌ 飞书 Webhook URL 未配置");
                Toast.makeText(this, "飞书 Webhook URL 未配置，请先设置", Toast.LENGTH_LONG).show();
                return;
            }
            // 在后台线程测试飞书连接
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final FeishuWebhookSender.SendResult result =
                            FeishuWebhookSender.probeWebhook(
                                    GlobalFloatService.this, webhookUrl, signSecret);
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            if (result.success) {
                                appendLog("✅ 飞书连接成功");
                                // 飞书检查通过，继续启动流程
                                proceedToLaunchAndStart();
                            } else {
                                String msg = "飞书连接失败: " + result.detail;
                                appendLog("❌ " + msg);
                                Toast.makeText(GlobalFloatService.this, msg, Toast.LENGTH_LONG).show();
                            }
                        }
                    });
                }
            }).start();
            return; // 等待飞书检查回调
        }

        // 无需检查飞书，直接启动
        proceedToLaunchAndStart();
    }

    /**
     * 所有前置检查通过后，强制关闭目标应用 → 重新启动 → 通过无障碍服务运行采集。
     */
    private void proceedToLaunchAndStart() {
        appendLog("✅ 所有检查通过");

        // Step 1: 强制关闭目标应用（确保干净启动）
        appendLog("⏳ 正在强制关闭目标应用...");
        forceStopTargetApp();

        // Step 2: 等待 2 秒后重新启动
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                appendLog("⏳ 正在启动目标应用...");
                boolean launched = launchTargetApp();
                if (!launched) {
                    appendLog("❌ 无法启动目标应用");
                    Toast.makeText(GlobalFloatService.this, "无法启动目标应用", Toast.LENGTH_LONG).show();
                    return;
                }
                appendLog("✅ 目标应用已启动");

                // Step 3: 等待 8 秒让界面加载完成
                appendLog("⏳ 等待 8 秒让界面加载...");
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        // 等待无障碍服务实例就绪（APK 安装后可能需要重连）
                        waitForA11yAndStart(0);
                    }
                }, 8000L);
            }
        }, 2000L);
    }

    /**
     * 等待无障碍服务实例就绪，最多重试 10 次（每次 500ms）。
     * APK 重装后服务可能需要几秒才能重新连接。
     */
    private void waitForA11yAndStart(final int attempt) {
        ShuangyuAccessibilityAdService a11y =
                ShuangyuAccessibilityAdService.getInstance();
        if (a11y != null) {
            appendLog("📤 通过无障碍服务启动采集");
            a11y.startCollection(GlobalFloatService.this);
            return;
        }

        if (attempt >= 10) {
            appendLog("❌ 无障碍服务未就绪（等待 5 秒后超时）");
            Toast.makeText(this, "无障碍服务未运行，请重新开启", Toast.LENGTH_LONG).show();
            openAccessibilitySettings();
            return;
        }

        if (attempt == 0) {
            appendLog("⏳ 等待无障碍服务就绪...");
        }
        Log.i(TAG, "等待无障碍服务实例... 第 " + (attempt + 1) + " 次");

        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                waitForA11yAndStart(attempt + 1);
            }
        }, 500L);
    }

    private void forceStopTargetApp() {
        try {
            Runtime.getRuntime().exec(new String[]{
                    "am", "force-stop", UiComponentConfig.TARGET_PACKAGE
            });
            Log.i(TAG, "已强制关闭: " + UiComponentConfig.TARGET_PACKAGE);
        } catch (Throwable e) {
            Log.e(TAG, "force-stop 失败", e);
        }
    }

    // ──────────────────── 检查方法 ────────────────────

    private boolean isTargetAppInstalled() {
        try {
            getPackageManager().getPackageInfo(UiComponentConfig.TARGET_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private boolean launchTargetApp() {
        try {
            Intent launchIntent = getPackageManager()
                    .getLaunchIntentForPackage(UiComponentConfig.TARGET_PACKAGE);
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(launchIntent);
                Log.i(TAG, "已启动目标应用: " + UiComponentConfig.TARGET_PACKAGE);
                return true;
            } else {
                Log.w(TAG, "无法获取目标应用启动 Intent");
                return false;
            }
        } catch (Throwable e) {
            Log.e(TAG, "启动目标应用失败", e);
            return false;
        }
    }

    private boolean isAccessibilityServiceEnabled() {
        int accessibilityEnabled = 0;
        final String service = getPackageName() + "/"
                + ShuangyuAccessibilityAdService.class.getCanonicalName();
        try {
            accessibilityEnabled = Settings.Secure.getInt(
                    getApplicationContext().getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED);
        } catch (Settings.SettingNotFoundException e) {
            // ignore
        }
        if (accessibilityEnabled == 1) {
            String settingValue = Settings.Secure.getString(
                    getApplicationContext().getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (settingValue != null) {
                TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
                splitter.setString(settingValue);
                while (splitter.hasNext()) {
                    String accessibilityService = splitter.next();
                    if (accessibilityService.equalsIgnoreCase(service)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void openOverlayPermissionSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } catch (Throwable e) {
                Log.e(TAG, "无法打开悬浮窗权限设置", e);
            }
        }
    }

    private void openAccessibilitySettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Throwable e) {
            Log.e(TAG, "无法打开无障碍设置", e);
            Toast.makeText(this, "无法打开无障碍设置", Toast.LENGTH_SHORT).show();
        }
    }

    // ─────────────────────── 供外部启动服务 ───────────────────────

    static void startServiceCompat(Context context) {
        FloatServiceBootstrap.startFloatService(context);
    }

    static void stopServiceCompat(Context context) {
        FloatServiceBootstrap.stopFloatService(context);
    }
}
