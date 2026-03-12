package com.oodbye.shuangyulspmodule;

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
import android.graphics.Typeface;
import android.os.Build;
import android.os.IBinder;
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

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        addFloatButton();
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
        removeFloatButton();
        removeInfoWindow();
        super.onDestroy();
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
        // 处理引擎状态变更、显示 ID 切换等
        int displayId = intent.getIntExtra(ModuleSettings.EXTRA_TARGET_DISPLAY_ID, -1);
        boolean restart = intent.getBooleanExtra(
                ModuleSettings.EXTRA_REQUEST_RESTART_TARGET_APP, false);
        Log.i(TAG, "FloatService intent: displayId=" + displayId + " restart=" + restart);
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
        } catch (Throwable e) {
            Log.e(TAG, "Failed to add float button", e);
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
                sendEngineCommand(ModuleSettings.ENGINE_CMD_START);
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
        Intent intent = new Intent(ModuleSettings.ACTION_ENGINE_STATUS_REPORT);
        intent.setPackage(UiComponentConfig.TARGET_PACKAGE);
        intent.putExtra(ModuleSettings.EXTRA_ENGINE_COMMAND, command);
        intent.putExtra(ModuleSettings.EXTRA_ENGINE_COMMAND_SEQ, seq);
        try {
            sendBroadcast(intent);
        } catch (Throwable e) {
            Log.e(TAG, "Failed to send engine command", e);
        }

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
}
