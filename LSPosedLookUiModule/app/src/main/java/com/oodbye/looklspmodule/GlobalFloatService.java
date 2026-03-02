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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class GlobalFloatService extends Service {
    private static final String TAG = "LOOKLspModule";
    private static final String CHANNEL_ID = "look_lsp_float_service";
    private static final int NOTIFICATION_ID = 2026030101;
    private static final long DISPLAY_REFRESH_INTERVAL_MS = 2000L;
    private static final long PERMISSION_TOAST_MIN_INTERVAL_MS = 6000L;
    private static final long CYCLE_NOTICE_SHOW_MS = 6000L;
    private static final int GLOBAL_DISPLAY_ID = 0;

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
    private LinearLayout cycleLimitDialogWindow;
    private boolean overlayAdded;
    private WindowManager mirrorWindowManager;
    private LinearLayout mirrorRootLayout;
    private LinearLayout mirrorActionPanel;
    private TextView mirrorMainButton;
    private TextView mirrorInfoWindow;
    private TextView mirrorCycleNoticeWindow;
    private LinearLayout mirrorCycleLimitDialogWindow;
    private boolean mirrorOverlayAdded;
    private final Map<Integer, ExtraOverlay> extraOverlays = new HashMap<Integer, ExtraOverlay>();
    private long lastPermissionToastAt;
    private int preferredDisplayId = -1;
    private int attachedDisplayId = -1;
    private int mirrorAttachedDisplayId = -1;
    private long runtimeStartFallbackAt = 0L;
    private String cycleLimitDialogMessage = "";
    private boolean cycleLimitDialogVisible = false;

    private static final class ExtraOverlay {
        int targetDisplayId = -1;
        WindowManager windowManager;
        LinearLayout rootLayout;
        LinearLayout actionPanel;
        TextView mainButton;
        TextView infoWindow;
        TextView cycleNoticeWindow;
        LinearLayout cycleLimitDialogWindow;
        boolean overlayAdded;
    }

    private final Runnable hideCycleNoticeTask = new Runnable() {
        @Override
        public void run() {
            hideCycleNoticeView(cycleNoticeWindow);
            hideCycleNoticeView(mirrorCycleNoticeWindow);
            for (ExtraOverlay overlay : extraOverlays.values()) {
                if (overlay == null) {
                    continue;
                }
                hideCycleNoticeView(overlay.cycleNoticeWindow);
            }
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

    public static void startServiceCompatWithCycleLimitDialog(
            Context context,
            int completedCycles,
            int cycleLimit,
            long durationMs
    ) {
        if (context == null) {
            return;
        }
        Intent intent = new Intent(context, GlobalFloatService.class);
        intent.putExtra(ModuleSettings.EXTRA_FINISHED_CYCLES, Math.max(0, completedCycles));
        intent.putExtra(ModuleSettings.EXTRA_FINISHED_CYCLE_LIMIT, Math.max(0, cycleLimit));
        intent.putExtra(ModuleSettings.EXTRA_FINISHED_DURATION_MS, Math.max(0L, durationMs));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
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
        boolean hasCycleLimitDialog = false;
        int finishedCycles = 0;
        int finishedCycleLimit = 0;
        long finishedDurationMs = 0L;
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
            hasCycleLimitDialog = intent.hasExtra(ModuleSettings.EXTRA_FINISHED_CYCLES)
                    || intent.hasExtra(ModuleSettings.EXTRA_FINISHED_CYCLE_LIMIT)
                    || intent.hasExtra(ModuleSettings.EXTRA_FINISHED_DURATION_MS);
            if (hasCycleLimitDialog) {
                finishedCycles = intent.getIntExtra(ModuleSettings.EXTRA_FINISHED_CYCLES, 0);
                finishedCycleLimit = intent.getIntExtra(ModuleSettings.EXTRA_FINISHED_CYCLE_LIMIT, 0);
                finishedDurationMs = intent.getLongExtra(ModuleSettings.EXTRA_FINISHED_DURATION_MS, 0L);
            }
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
        if (hasCycleLimitDialog) {
            showCycleLimitFinishedDialog(finishedCycles, finishedCycleLimit, finishedDurationMs);
        }
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
        removeExtraOverlays();
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
        cycleLimitDialogWindow = null;
        mirrorCycleLimitDialogWindow = null;
        cycleLimitDialogVisible = false;
        cycleLimitDialogMessage = "";
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
            removeExtraOverlays();
            log("float button disabled, stop self");
            stopSelf();
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            maybeShowOverlayPermissionToast();
            removeOverlay();
            removeMirrorOverlay();
            removeExtraOverlays();
            return;
        }
        ensureOverlay();
        ensureMirrorOverlay();
        ensureExtraOverlays();
        updateMainButtonStatus();
        updateInfoWindowStatus();
        updateCycleLimitDialogStatus();
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
        Context overlayContext = resolveOverlayContext(GLOBAL_DISPLAY_ID);
        WindowManager targetWindowManager =
                (WindowManager) overlayContext.getSystemService(WINDOW_SERVICE);
        if (targetWindowManager == null) {
            return;
        }
        if (windowManager == null) {
            windowManager = targetWindowManager;
        } else if (isOverlayAttached()) {
            int attachedDisplay = viewDisplayId(rootLayout);
            if (attachedDisplay >= 0 && attachedDisplay != GLOBAL_DISPLAY_ID) {
                removeOverlay();
                windowManager = targetWindowManager;
            }
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
            int realDisplayId = viewDisplayId(rootLayout);
            attachedDisplayId = realDisplayId >= 0 ? realDisplayId : GLOBAL_DISPLAY_ID;
            if (realDisplayId < 0) {
                log("global overlay display unresolved, contextDisplay="
                        + contextDisplayId(overlayContext)
                        + " targetDisplay=" + GLOBAL_DISPLAY_ID);
            }
            log("overlay added on displayId=" + attachedDisplayId + " (global)");
        } catch (Throwable e) {
            overlayAdded = false;
            attachedDisplayId = -1;
            log("overlay add failed on global targetDisplayId=" + GLOBAL_DISPLAY_ID + ": " + e);
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
        Context mirrorContext = resolveOverlayContext(preferredDisplayId);
        WindowManager targetMirrorWindowManager =
                (WindowManager) mirrorContext.getSystemService(WINDOW_SERVICE);
        if (targetMirrorWindowManager == null) {
            return;
        }
        if (mirrorWindowManager == null) {
            mirrorWindowManager = targetMirrorWindowManager;
        } else if (isMirrorOverlayAttached()) {
            int attachedDisplay = viewDisplayId(mirrorRootLayout);
            if (attachedDisplay >= 0 && attachedDisplay != preferredDisplayId) {
                removeMirrorOverlay();
                mirrorWindowManager = targetMirrorWindowManager;
            }
        }
        if (mirrorRootLayout == null) {
            buildMirrorOverlayView(mirrorContext);
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
            int realDisplayId = viewDisplayId(mirrorRootLayout);
            mirrorAttachedDisplayId = realDisplayId >= 0 ? realDisplayId : preferredDisplayId;
            if (realDisplayId < 0) {
                log("mirror overlay display unresolved, contextDisplay="
                        + contextDisplayId(mirrorContext)
                        + " targetDisplay=" + preferredDisplayId);
            }
            log("overlay added on displayId=" + mirrorAttachedDisplayId + " (mirror)");
        } catch (Throwable e) {
            mirrorOverlayAdded = false;
            mirrorAttachedDisplayId = -1;
            log("overlay add failed on displayId=" + preferredDisplayId + " (mirror): " + e);
        }
    }

    private void ensureExtraOverlays() {
        DisplayManager dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        if (dm == null) {
            removeExtraOverlays();
            return;
        }
        Display[] displays;
        try {
            displays = dm.getDisplays();
        } catch (Throwable e) {
            log("query displays failed for extra overlays: " + e);
            removeExtraOverlays();
            return;
        }
        Set<Integer> targetIds = new HashSet<Integer>();
        if (displays != null) {
            for (Display display : displays) {
                if (display == null) {
                    continue;
                }
                int displayId = display.getDisplayId();
                if (displayId < 0) {
                    continue;
                }
                if (displayId == GLOBAL_DISPLAY_ID || displayId == preferredDisplayId) {
                    continue;
                }
                if (display.getState() != Display.STATE_ON) {
                    continue;
                }
                targetIds.add(displayId);
                ensureExtraOverlay(displayId);
            }
        }
        List<Integer> removeIds = new ArrayList<Integer>();
        for (Integer existingId : extraOverlays.keySet()) {
            if (existingId == null) {
                continue;
            }
            if (!targetIds.contains(existingId)) {
                removeIds.add(existingId);
            }
        }
        for (Integer removeId : removeIds) {
            removeExtraOverlay(removeId.intValue());
        }
    }

    private void ensureExtraOverlay(int displayId) {
        if (displayId < 0 || displayId == GLOBAL_DISPLAY_ID || displayId == preferredDisplayId) {
            return;
        }
        ExtraOverlay overlay = extraOverlays.get(displayId);
        if (overlay == null) {
            overlay = new ExtraOverlay();
            overlay.targetDisplayId = displayId;
            extraOverlays.put(displayId, overlay);
        }
        Context overlayContext = resolveOverlayContext(displayId);
        WindowManager targetWindowManager =
                (WindowManager) overlayContext.getSystemService(WINDOW_SERVICE);
        if (targetWindowManager == null) {
            return;
        }
        if (overlay.windowManager == null) {
            overlay.windowManager = targetWindowManager;
        } else if (isExtraOverlayAttached(overlay)) {
            int attachedDisplay = viewDisplayId(overlay.rootLayout);
            if (attachedDisplay >= 0 && attachedDisplay != displayId) {
                removeExtraOverlayView(overlay);
                overlay.windowManager = targetWindowManager;
            }
        }
        if (overlay.rootLayout == null) {
            buildExtraOverlayView(overlay, overlayContext, displayId);
        }
        if (isExtraOverlayAttached(overlay)) {
            return;
        }
        removeExtraOverlayView(overlay);

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
            overlay.windowManager.addView(overlay.rootLayout, params);
            overlay.overlayAdded = true;
            int realDisplayId = viewDisplayId(overlay.rootLayout);
            overlay.targetDisplayId = realDisplayId >= 0 ? realDisplayId : displayId;
            if (realDisplayId < 0) {
                log("extra overlay display unresolved, contextDisplay="
                        + contextDisplayId(overlayContext)
                        + " targetDisplay=" + displayId);
            }
            log("overlay added on displayId=" + overlay.targetDisplayId + " (extra)");
        } catch (Throwable e) {
            overlay.overlayAdded = false;
            log("overlay add failed on displayId=" + displayId + " (extra): " + e);
        }
    }

    private Context resolveOverlayContext(int displayId) {
        if (displayId >= 0) {
            try {
                DisplayManager dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
                if (dm != null) {
                    Display display = dm.getDisplay(displayId);
                    if (display != null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            try {
                                return createWindowContext(
                                        display,
                                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                                        null
                                );
                            } catch (Throwable e) {
                                log("createWindowContext failed, displayId="
                                        + displayId + " err=" + e);
                            }
                        }
                        return createDisplayContext(display);
                    }
                }
            } catch (Throwable e) {
                log("resolve display context failed, displayId=" + displayId + " err=" + e);
            }
        }
        return this;
    }

    private int viewDisplayId(View view) {
        if (view == null || view.getDisplay() == null) {
            return -1;
        }
        return view.getDisplay().getDisplayId();
    }

    private int contextDisplayId(Context context) {
        if (context == null || context.getDisplay() == null) {
            return -1;
        }
        return context.getDisplay().getDisplayId();
    }

    private void buildOverlayView(Context overlayContext) {
        LinearLayout root = new LinearLayout(overlayContext);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.END);
        root.setPadding(dp(6), dp(6), dp(6), dp(6));
        root.setBackgroundColor(Color.parseColor("#33222222"));
        rootLayout = root;

        cycleLimitDialogWindow = createCycleLimitDialogView(overlayContext);
        root.addView(cycleLimitDialogWindow);

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

        mirrorCycleLimitDialogWindow = createCycleLimitDialogView(overlayContext);
        root.addView(mirrorCycleLimitDialogWindow);

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

    private void buildExtraOverlayView(
            final ExtraOverlay overlay,
            Context overlayContext,
            final int displayId
    ) {
        if (overlay == null) {
            return;
        }
        LinearLayout root = new LinearLayout(overlayContext);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.END);
        root.setPadding(dp(6), dp(6), dp(6), dp(6));
        root.setBackgroundColor(Color.parseColor("#33222222"));
        overlay.rootLayout = root;

        overlay.cycleLimitDialogWindow = createCycleLimitDialogView(overlayContext);
        root.addView(overlay.cycleLimitDialogWindow);

        TextView cycleNotice = createCycleNoticeTextView(overlayContext);
        overlay.cycleNoticeWindow = cycleNotice;
        cycleNotice.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleActionPanel(overlay.actionPanel);
            }
        });
        root.addView(cycleNotice);

        TextView info = createInfoTextView(overlayContext);
        overlay.infoWindow = info;
        info.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleActionPanel(overlay.actionPanel);
            }
        });
        root.addView(info);

        LinearLayout row = new LinearLayout(overlayContext);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleActionPanel(overlay.actionPanel);
            }
        });
        root.addView(row);

        TextView toggleButton = createActionButton(overlayContext, "LSP", "#1E88E5");
        overlay.mainButton = toggleButton;
        LinearLayout.LayoutParams mainLp = (LinearLayout.LayoutParams) toggleButton.getLayoutParams();
        mainLp.leftMargin = 0;
        toggleButton.setLayoutParams(mainLp);
        toggleButton.setMinWidth(dp(92));
        toggleButton.setGravity(Gravity.CENTER);
        toggleButton.setSingleLine(true);
        toggleButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleActionPanel(overlay.actionPanel);
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
        overlay.actionPanel = panel;

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
        overlay.targetDisplayId = displayId;
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

    private boolean isExtraOverlayAttached(ExtraOverlay overlay) {
        if (overlay == null || !overlay.overlayAdded || overlay.rootLayout == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            return overlay.rootLayout.isAttachedToWindow();
        }
        return overlay.rootLayout.getWindowToken() != null;
    }

    private void removeExtraOverlayView(ExtraOverlay overlay) {
        if (overlay == null) {
            return;
        }
        if (!overlay.overlayAdded
                && (overlay.rootLayout == null || overlay.rootLayout.getParent() == null)) {
            return;
        }
        if (overlay.windowManager == null || overlay.rootLayout == null) {
            overlay.overlayAdded = false;
            return;
        }
        try {
            overlay.windowManager.removeViewImmediate(overlay.rootLayout);
            log("overlay removed (extra displayId=" + overlay.targetDisplayId + ")");
        } catch (Throwable e) {
            log("overlay remove failed (extra displayId=" + overlay.targetDisplayId + "): " + e);
        }
        overlay.overlayAdded = false;
    }

    private void removeExtraOverlay(int displayId) {
        ExtraOverlay overlay = extraOverlays.remove(displayId);
        removeExtraOverlayView(overlay);
    }

    private void removeExtraOverlays() {
        List<Integer> ids = new ArrayList<Integer>(extraOverlays.keySet());
        for (Integer id : ids) {
            if (id == null) {
                continue;
            }
            removeExtraOverlay(id.intValue());
        }
        extraOverlays.clear();
    }

    private void onRunClicked() {
        hideCycleLimitFinishedDialog();
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

    private void onCycleLimitDialogEndClicked() {
        long seq = ModuleSettings.pushEngineCommand(
                this,
                ModuleSettings.ENGINE_CMD_STOP,
                ModuleSettings.EngineStatus.STOPPED
        );
        dispatchEngineCommandBroadcast(
                ModuleSettings.ENGINE_CMD_STOP,
                ModuleSettings.EngineStatus.STOPPED,
                seq
        );
        hideCycleLimitFinishedDialog();
        collapseActionPanel();
        updateMainButtonStatus();
        Toast.makeText(this, "模块已结束", Toast.LENGTH_SHORT).show();
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
        long seq = ModuleSettings.getEngineCommandSeq(prefs);
        if (seq <= 0L) {
            seq = ModuleSettings.pushEngineCommand(
                    this,
                    ModuleSettings.ENGINE_CMD_RUN,
                    ModuleSettings.EngineStatus.RUNNING
            );
        }
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
        log("cycle restart requested: relaunched target app with existing run seq=" + seq);
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
            intent.putExtra(
                    ModuleSettings.EXTRA_TOGETHER_CYCLE_LIMIT,
                    ModuleSettings.getTogetherCycleLimit(prefs)
            );
            intent.putExtra(
                    ModuleSettings.EXTRA_TOGETHER_CYCLE_WAIT_SECONDS,
                    ModuleSettings.getTogetherCycleWaitSeconds(prefs)
            );
            intent.putExtra(
                    ModuleSettings.EXTRA_RUNTIME_RUN_START_AT,
                    ModuleSettings.getRuntimeRunStartAt(prefs)
            );
            intent.putExtra(
                    ModuleSettings.EXTRA_RUNTIME_CYCLE_COMPLETED,
                    ModuleSettings.getRuntimeCycleCompleted(prefs)
            );
            intent.putExtra(
                    ModuleSettings.EXTRA_RUNTIME_CYCLE_ENTERED,
                    ModuleSettings.getRuntimeCycleEntered(prefs)
            );
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
        for (ExtraOverlay overlay : extraOverlays.values()) {
            if (overlay == null) {
                continue;
            }
            applyMainButtonStatus(overlay.mainButton, status);
        }
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
        if (cycleLimit > 0) {
            currentCycle = Math.min(cycleLimit, currentCycle);
        }
        String total = cycleLimit <= 0
                ? "无限"
                : String.valueOf(cycleLimit);
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
        String text = "循环: " + currentCycle + "/" + total
                + "\n已进房: " + Math.max(0, entered)
                + "\n运行: " + formatElapsed(elapsedMs);
        applyInfoWindowStatus(infoWindow, enabled, text);
        applyInfoWindowStatus(mirrorInfoWindow, enabled, text);
        for (ExtraOverlay overlay : extraOverlays.values()) {
            if (overlay == null) {
                continue;
            }
            applyInfoWindowStatus(overlay.infoWindow, enabled, text);
        }
    }

    private void updateCycleLimitDialogStatus() {
        applyCycleLimitDialogStatus(cycleLimitDialogWindow, cycleLimitDialogVisible, cycleLimitDialogMessage);
        applyCycleLimitDialogStatus(
                mirrorCycleLimitDialogWindow,
                cycleLimitDialogVisible,
                cycleLimitDialogMessage
        );
        for (ExtraOverlay overlay : extraOverlays.values()) {
            if (overlay == null) {
                continue;
            }
            applyCycleLimitDialogStatus(
                    overlay.cycleLimitDialogWindow,
                    cycleLimitDialogVisible,
                    cycleLimitDialogMessage
            );
        }
    }

    private void applyCycleLimitDialogStatus(LinearLayout dialog, boolean visible, String message) {
        if (dialog == null) {
            return;
        }
        if (!visible || TextUtils.isEmpty(message)) {
            dialog.setVisibility(View.GONE);
            return;
        }
        Object tagView = dialog.getTag();
        if (tagView instanceof TextView) {
            ((TextView) tagView).setText(message);
        }
        dialog.setVisibility(View.VISIBLE);
    }

    private void showCycleLimitFinishedDialog(int completedCycles, int cycleLimit, long durationMs) {
        int safeCompleted = Math.max(0, completedCycles);
        int safeLimit = Math.max(0, cycleLimit);
        long safeDuration = Math.max(0L, durationMs);
        String totalText = safeLimit <= 0 ? "无限" : String.valueOf(safeLimit);
        cycleLimitDialogMessage = "一起聊直播间循环点击次数已运行完成"
                + "\n已完成循环: " + safeCompleted + "/" + totalText
                + "\n运行时长: " + formatElapsed(safeDuration)
                + "\n请选择“重新运行”或“结束”";
        cycleLimitDialogVisible = true;
        ensureOverlay();
        ensureMirrorOverlay();
        ensureExtraOverlays();
        updateCycleLimitDialogStatus();
        log("show cycle limit dialog: completed=" + safeCompleted
                + " limit=" + safeLimit + " durationMs=" + safeDuration);
    }

    private void hideCycleLimitFinishedDialog() {
        if (!cycleLimitDialogVisible && TextUtils.isEmpty(cycleLimitDialogMessage)) {
            return;
        }
        cycleLimitDialogVisible = false;
        cycleLimitDialogMessage = "";
        updateCycleLimitDialogStatus();
    }

    private void showCycleCompleteNotice(String message) {
        String text = message == null ? "" : message.trim();
        if (TextUtils.isEmpty(text)) {
            return;
        }
        ensureOverlay();
        ensureMirrorOverlay();
        ensureExtraOverlays();
        showCycleNoticeView(cycleNoticeWindow, text);
        showCycleNoticeView(mirrorCycleNoticeWindow, text);
        for (ExtraOverlay overlay : extraOverlays.values()) {
            if (overlay == null) {
                continue;
            }
            showCycleNoticeView(overlay.cycleNoticeWindow, text);
        }
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
        for (ExtraOverlay overlay : extraOverlays.values()) {
            if (overlay == null || overlay.actionPanel == null) {
                continue;
            }
            overlay.actionPanel.setVisibility(View.GONE);
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
        for (ExtraOverlay overlay : extraOverlays.values()) {
            if (overlay == null || overlay.actionPanel == null || overlay.actionPanel == panel) {
                continue;
            }
            overlay.actionPanel.setVisibility(View.GONE);
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

    private LinearLayout createCycleLimitDialogView(Context context) {
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(10), dp(8), dp(10), dp(8));
        container.setBackgroundColor(Color.parseColor("#D91B1B1B"));
        container.setVisibility(View.GONE);
        LinearLayout.LayoutParams containerLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        containerLp.bottomMargin = dp(8);
        container.setLayoutParams(containerLp);

        TextView message = new TextView(context);
        message.setTextColor(Color.WHITE);
        message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        message.setSingleLine(false);
        message.setMaxWidth(dp(220));
        message.setMinWidth(dp(180));
        message.setText("一起聊直播间循环点击次数已运行完成");
        container.addView(message);
        container.setTag(message);

        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        actionsLp.topMargin = dp(8);
        actions.setLayoutParams(actionsLp);
        container.addView(actions);

        TextView rerunBtn = createDialogActionButton(context, "重新运行", "#2E7D32");
        rerunBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onRunClicked();
            }
        });
        actions.addView(rerunBtn);

        TextView endBtn = createDialogActionButton(context, "结束", "#C62828");
        LinearLayout.LayoutParams endLp = (LinearLayout.LayoutParams) endBtn.getLayoutParams();
        endLp.leftMargin = dp(8);
        endBtn.setLayoutParams(endLp);
        endBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onCycleLimitDialogEndClicked();
            }
        });
        actions.addView(endBtn);
        return container;
    }

    private TextView createDialogActionButton(Context context, String text, String colorHex) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setPadding(dp(12), dp(6), dp(12), dp(6));
        tv.setBackgroundColor(Color.parseColor(colorHex));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
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
