package com.oodbye.shuangyulspmodule;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 双鱼直播辅助无障碍服务：广告检测 + 数据采集辅助。
 *
 * 通过 Accessibility API 实现：
 * - 实时广告弹窗检测与自动关闭
 * - 自定义广告规则匹配
 * - 辅助数据采集工作流（作为 Xposed Hook 的补充）
 */
public class ShuangyuAccessibilityAdService extends AccessibilityService {
    private static final String TAG = "SYLspModule";

    private static volatile ShuangyuAccessibilityAdService sInstance;
    private Handler handler;
    private CustomRulesAdProcessor customAdProcessor;

    // 广告检测状态
    private long lastAdCheckTime = 0L;
    private static final long AD_CHECK_INTERVAL_MS = 1500L;

    // 采集状态
    private volatile boolean collectingEnabled = false;
    private final Set<String> collectedUserIds = new HashSet<>();
    private String currentRoomName = "";
    private String currentRoomId = "";

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        handler = new Handler(Looper.getMainLooper());
        customAdProcessor = new CustomRulesAdProcessor(this);
        ModuleRunFileLogger.init();
        ModuleRunFileLogger.i(TAG, "ShuangyuAccessibilityAdService created");
    }

    @Override
    public void onDestroy() {
        sInstance = null;
        collectingEnabled = false;
        stopProactiveAdCheck();
        ModuleRunFileLogger.i(TAG, "ShuangyuAccessibilityAdService destroyed");
        removeA11yFloatButton();
        super.onDestroy();
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        ModuleRunFileLogger.i(TAG, "无障碍服务已连接");
        LevelDataBridge.registerReceiver(this);
        startProactiveAdCheck();
        addA11yFloatButton();

        // 检查是否需要恢复采集（服务重启后自动恢复）
        if (isCollectionPersisted()) {
            ModuleRunFileLogger.i(TAG, "🔄 检测到采集被中断（服务重启），3 秒后自动恢复...");
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!collectionRunning && isCollectionPersisted()) {
                        ModuleRunFileLogger.i(TAG, "🔄 自动恢复采集...");
                        startCollection(null);
                    }
                }
            }, 3000L);
        }
    }

    // ─────────────────────── 无障碍悬浮按钮（TYPE_ACCESSIBILITY_OVERLAY）───────────────────────
    private final java.util.Map<Integer, View> a11yFloatButtons = new java.util.LinkedHashMap<>();
    private final java.util.Map<Integer, WindowManager> a11yWindowManagers = new java.util.LinkedHashMap<>();
    private int floatDownX, floatDownY, floatLastX, floatLastY;
    private boolean floatIsDragging;
    private long floatLastClickTime = 0;
    private long floatDownTime = 0;
    private static final long CLICK_DEBOUNCE_MS = 1000L;
    private static final long DISPLAY_CHECK_INTERVAL_MS = 10_000L;
    private Runnable displayCheckRunnable;

    /** 在所有活跃屏幕上创建悬浮按钮 */
    private void addA11yFloatButton() {
        try {
            android.hardware.display.DisplayManager dm =
                    (android.hardware.display.DisplayManager) getSystemService(
                            android.content.Context.DISPLAY_SERVICE);
            android.view.Display[] displays = dm.getDisplays();

            for (android.view.Display display : displays) {
                int displayId = display.getDisplayId();
                if (a11yFloatButtons.containsKey(displayId)) continue; // 已创建
                try {
                    android.content.Context displayCtx = createDisplayContext(display);
                    WindowManager wm = (WindowManager) displayCtx.getSystemService(
                            android.content.Context.WINDOW_SERVICE);

                    View btn = createA11yButton(displayCtx);

                    float density = displayCtx.getResources().getDisplayMetrics().density;
                    int size = (int) (56 * density);
                    WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                            size, size,
                            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                            android.graphics.PixelFormat.TRANSLUCENT
                    );
                    params.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
                    params.x = (int) (16 * density);
                    params.y = (int) (200 * density);

                    wm.addView(btn, params);
                    a11yFloatButtons.put(displayId, btn);
                    a11yWindowManagers.put(displayId, wm);
                    ModuleRunFileLogger.i(TAG, "✅ 悬浮按钮已创建 displayId=" + displayId);
                } catch (Throwable e) {
                    ModuleRunFileLogger.w(TAG, "屏幕 " + displayId
                            + " 创建按钮失败: " + e.getMessage());
                }
            }
        } catch (Throwable e) {
            ModuleRunFileLogger.e(TAG, "创建悬浮按钮失败", e);
        }


        // 启动定期检查（检测新屏幕）
        startDisplayCheck();
    }

    /** 定期检查新屏幕并创建按钮 */
    private void startDisplayCheck() {
        if (displayCheckRunnable != null) return;
        displayCheckRunnable = new Runnable() {
            @Override
            public void run() {
                addA11yFloatButton(); // 已有的会跳过
                if (handler != null) {
                    handler.postDelayed(this, DISPLAY_CHECK_INTERVAL_MS);
                }
            }
        };
        handler.postDelayed(displayCheckRunnable, DISPLAY_CHECK_INTERVAL_MS);
    }

    private void stopDisplayCheck() {
        if (displayCheckRunnable != null && handler != null) {
            handler.removeCallbacks(displayCheckRunnable);
            displayCheckRunnable = null;
        }
    }

    private View createA11yButton(android.content.Context ctx) {
        float density = ctx.getResources().getDisplayMetrics().density;
        android.widget.TextView tv = new android.widget.TextView(ctx);
        tv.setText("鱼");
        tv.setTextColor(android.graphics.Color.WHITE);
        tv.setTextSize(18);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setGravity(android.view.Gravity.CENTER);
        tv.setBackgroundColor(android.graphics.Color.parseColor("#CC2196F3"));
        int pad = (int) (4 * density);
        tv.setPadding(pad, pad, pad, pad);

        tv.setOnTouchListener(new android.view.View.OnTouchListener() {
            @Override
            public boolean onTouch(android.view.View v, android.view.MotionEvent event) {
                WindowManager.LayoutParams lp =
                        (WindowManager.LayoutParams) v.getLayoutParams();
                switch (event.getAction()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        floatDownX = (int) event.getRawX();
                        floatDownY = (int) event.getRawY();
                        floatLastX = lp.x;
                        floatLastY = lp.y;
                        floatIsDragging = false;
                        floatDownTime = System.currentTimeMillis();
                        return true;
                    case android.view.MotionEvent.ACTION_MOVE:
                        int dx = (int) event.getRawX() - floatDownX;
                        int dy = (int) event.getRawY() - floatDownY;
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            floatIsDragging = true;
                        }
                        if (floatIsDragging) {
                            lp.x = floatLastX + dx;
                            lp.y = floatLastY + dy;
                            for (java.util.Map.Entry<Integer, View> entry
                                    : a11yFloatButtons.entrySet()) {
                                if (entry.getValue() == v) {
                                    WindowManager wm = a11yWindowManagers.get(entry.getKey());
                                    if (wm != null) {
                                        try { wm.updateViewLayout(v, lp); }
                                        catch (Throwable ignore) {}
                                    }
                                    break;
                                }
                            }
                        }
                        return true;
                    case android.view.MotionEvent.ACTION_UP:
                        if (!floatIsDragging) {
                            long now = System.currentTimeMillis();
                            long pressDuration = now - floatDownTime;
                            if (pressDuration >= 50
                                    && (now - floatLastClickTime) >= CLICK_DEBOUNCE_MS) {
                                floatLastClickTime = now;
                                onA11yFloatButtonClicked(v);
                            }
                        }
                        return true;
                }
                return false;
            }
        });
        return tv;
    }

    private void removeA11yFloatButton() {
        stopDisplayCheck();
        for (java.util.Map.Entry<Integer, View> entry : a11yFloatButtons.entrySet()) {
            WindowManager wm = a11yWindowManagers.get(entry.getKey());
            if (wm != null) {
                try { wm.removeView(entry.getValue()); }
                catch (Throwable ignore) {}
            }
        }
        a11yFloatButtons.clear();
        a11yWindowManagers.clear();
    }

    private void onA11yFloatButtonClicked(View clickedBtn) {
        ModuleRunFileLogger.i(TAG, "🔘 悬浮按钮被点击，弹出菜单");
        showA11yPopupMenu(clickedBtn);
    }

    // ─────── 弹出菜单 ───────
    private View a11yPopupMenu;
    private WindowManager a11yPopupMenuWm;

    private void showA11yPopupMenu(View clickedBtn) {
        if (a11yPopupMenu != null) {
            dismissA11yPopupMenu();
            return;
        }
        // 找到被点击按钮所在的 displayId 和 WindowManager
        WindowManager menuWm = null;
        for (java.util.Map.Entry<Integer, View> entry : a11yFloatButtons.entrySet()) {
            if (entry.getValue() == clickedBtn) {
                menuWm = a11yWindowManagers.get(entry.getKey());
                break;
            }
        }
        if (menuWm == null || clickedBtn == null) return;
        try {
            float density = getResources().getDisplayMetrics().density;
            int menuWidth = (int) (180 * density);

            android.widget.LinearLayout menu = new android.widget.LinearLayout(this);
            menu.setOrientation(android.widget.LinearLayout.VERTICAL);
            menu.setBackgroundColor(android.graphics.Color.parseColor("#F0333333"));
            int pad = (int) (12 * density);
            menu.setPadding(pad, pad, pad, pad);

            // 菜单项公共样式
            android.widget.LinearLayout.LayoutParams itemLp =
                    new android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            (int) (44 * density));
            itemLp.bottomMargin = (int) (4 * density);

            if (!collectionRunning) {
                android.widget.TextView btnStart = makeMenuButton("▶ 开始采集",
                        "#CC4CAF50", density);
                btnStart.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        dismissA11yPopupMenu();
                        startCollection(null);
                        updateA11yFloatButtonState(true);
                    }
                });
                menu.addView(btnStart, itemLp);
            } else {
                android.widget.TextView btnStop = makeMenuButton("⏹ 停止采集",
                        "#CCF44336", density);
                btnStop.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        dismissA11yPopupMenu();
                        stopCollection();
                        updateA11yFloatButtonState(false);
                    }
                });
                menu.addView(btnStop, itemLp);
            }

            android.widget.TextView btnClose = makeMenuButton("✕ 关闭菜单",
                    "#CC757575", density);
            btnClose.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    dismissA11yPopupMenu();
                }
            });
            menu.addView(btnClose, itemLp);

            // 获取按钮位置，菜单显示在按钮旁边
            WindowManager.LayoutParams btnLp =
                    (WindowManager.LayoutParams) clickedBtn.getLayoutParams();
            int btnSize = (int) (56 * density);

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    menuWidth,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    android.graphics.PixelFormat.TRANSLUCENT
            );
            params.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
            params.x = btnLp.x + btnSize + (int) (8 * density);
            params.y = btnLp.y;

            menuWm.addView(menu, params);
            a11yPopupMenu = menu;
            a11yPopupMenuWm = menuWm;
        } catch (Throwable e) {
            ModuleRunFileLogger.e(TAG, "弹出菜单失败", e);
        }
    }

    private android.widget.TextView makeMenuButton(String text, String bgColor, float density) {
        android.widget.TextView btn = new android.widget.TextView(this);
        btn.setText(text);
        btn.setTextColor(android.graphics.Color.WHITE);
        btn.setTextSize(14);
        btn.setGravity(android.view.Gravity.CENTER);
        btn.setBackgroundColor(android.graphics.Color.parseColor(bgColor));
        int p = (int) (8 * density);
        btn.setPadding(p, p, p, p);
        return btn;
    }

    private void dismissA11yPopupMenu() {
        if (a11yPopupMenu != null && a11yPopupMenuWm != null) {
            try {
                a11yPopupMenuWm.removeView(a11yPopupMenu);
            } catch (Throwable ignore) {}
            a11yPopupMenu = null;
            a11yPopupMenuWm = null;
        }
    }

    /** 更新所有屏幕上按钮颜色来反映运行状态 */
    void updateA11yFloatButtonState(boolean running) {
        for (View btn : a11yFloatButtons.values()) {
            if (btn instanceof android.widget.TextView) {
                android.widget.TextView tv = (android.widget.TextView) btn;
                if (running) {
                    tv.setBackgroundColor(android.graphics.Color.parseColor("#CC4CAF50"));
                    tv.setText("运");
                } else {
                    tv.setBackgroundColor(android.graphics.Color.parseColor("#CC2196F3"));
                    tv.setText("鱼");
                }
            }
        }
    }

    static ShuangyuAccessibilityAdService getInstance() {
        return sInstance;
    }

    // ─────────────────────── 主动广告检测定时器 ───────────────────────
    private static final long PROACTIVE_AD_CHECK_INTERVAL = 2000L;
    private final Runnable proactiveAdCheckRunnable = new Runnable() {
        @Override
        public void run() {
            checkForAds();
            if (handler != null) {
                handler.postDelayed(this, PROACTIVE_AD_CHECK_INTERVAL);
            }
        }
    };

    private void startProactiveAdCheck() {
        if (handler != null) {
            handler.removeCallbacks(proactiveAdCheckRunnable);
            handler.postDelayed(proactiveAdCheckRunnable, PROACTIVE_AD_CHECK_INTERVAL);
            ModuleRunFileLogger.i(TAG, "主动广告检测已启动 (每" + PROACTIVE_AD_CHECK_INTERVAL + "ms)");
        }
    }

    private void stopProactiveAdCheck() {
        if (handler != null) {
            handler.removeCallbacks(proactiveAdCheckRunnable);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        int eventType = event.getEventType();

        // 广告检测 — 始终启用（青少年模式等弹窗需要自动处理）
        {
            long now = System.currentTimeMillis();
            if (now - lastAdCheckTime > AD_CHECK_INTERVAL_MS) {
                lastAdCheckTime = now;
                checkForAds();
            }
        }

        // 窗口内容变化时的状态跟踪
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            onWindowChanged(event);
        }
    }

    @Override
    public void onInterrupt() {
        ModuleRunFileLogger.w(TAG, "无障碍服务被中断");
    }

    // ─────────────────────── 广告检测 ───────────────────────

    private void checkForAds() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return;

            // 1. 自定义规则检测
            if (customAdProcessor.getRuleCount() > 0) {
                if (customAdProcessor.process(this)) {
                    AdHandledNotifier.show(this, "自定义规则");
                    root.recycle();
                    return;
                }
            }

            // 2. 通用广告弹窗检测（采集运行期间跳过，避免误关房间 UI）
            if (!collectionRunning && checkAndDismissGenericAd(root)) {
                AdHandledNotifier.show(this, "通用广告");
            }

            root.recycle();
        } catch (Throwable e) {
            Log.e(TAG, "checkForAds error", e);
        }
    }

    private boolean checkAndDismissGenericAd(AccessibilityNodeInfo root) {
        // 查找常见的关闭按钮文字
        // 只保留明确是广告的关键词，「关闭」「取消」太通用，会误点房间 UI
        String[] dismissTexts = {"跳过", "不感兴趣", "以后再说"};
        for (String text : dismissTexts) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
            if (nodes != null && !nodes.isEmpty()) {
                for (AccessibilityNodeInfo node : nodes) {
                    // 验证是否是可点击的小按钮（比较短文本，排除长文本）
                    CharSequence nodeText = node.getText();
                    if (nodeText != null && nodeText.length() <= 8 && node.isClickable()) {
                        // 检查是否在弹窗中（父节点是 Dialog style）
                        if (isLikelyAdDismissButton(node)) {
                            node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                            ModuleRunFileLogger.i(TAG, "关闭广告弹窗: " + text);
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private boolean isLikelyAdDismissButton(AccessibilityNodeInfo node) {
        // 简单启发式：如果节点可点击且不是导航元素，可能是广告关闭按钮
        if (!node.isClickable()) return false;
        // 排除底部导航（Tab 按钮）
        try {
            CharSequence nodeText = node.getText();
            if (nodeText != null) {
                String text = nodeText.toString();
                // 排除底部 Tab
                if ("娱乐".equals(text) || "首页".equals(text)
                        || "消息".equals(text) || "我的".equals(text)) {
                    return false;
                }
            }
        } catch (Throwable ignore) {
        }
        return true;
    }

    // ─────────────────────── 窗口状态跟踪 ───────────────────────

    private void onWindowChanged(AccessibilityEvent event) {
        // 可以在此跟踪当前页面状态
        try {
            CharSequence className = event.getClassName();
            if (className != null) {
                String cls = className.toString();
                // 检测是否被踢出房间的对话框
                checkKickedOutDialog();
            }
        } catch (Throwable ignore) {
        }
    }

    private void checkKickedOutDialog() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return;

            // 检测踢出对话框文本
            List<AccessibilityNodeInfo> nodes =
                    root.findAccessibilityNodeInfosByText("您已被踢出");
            if (nodes != null && !nodes.isEmpty()) {
                // 尝试点击"知道了"或"确定"
                String[] confirmTexts = {"知道了", "确定", "我知道了", "知道了并关闭"};
                for (String text : confirmTexts) {
                    List<AccessibilityNodeInfo> btns =
                            root.findAccessibilityNodeInfosByText(text);
                    if (btns != null) {
                        for (AccessibilityNodeInfo btn : btns) {
                            if (btn.isClickable()) {
                                btn.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                                ModuleRunFileLogger.i(TAG, "检测到被踢出，自动点击: " + text);
                                root.recycle();
                                return;
                            }
                        }
                    }
                }
            }
            root.recycle();
        } catch (Throwable ignore) {
        }
    }

    // ─────────────────────── 采集控制 ───────────────────────

    private static final String KEY_COLLECTION_RUNNING = "collection_running";
    private volatile boolean collectionRunning = false;

    private void persistCollectionState(boolean running) {
        ModuleSettings.appPrefs(this).edit()
                .putBoolean(KEY_COLLECTION_RUNNING, running)
                .apply();
    }

    private boolean isCollectionPersisted() {
        return ModuleSettings.appPrefs(this)
                .getBoolean(KEY_COLLECTION_RUNNING, false);
    }

    /**
     * 启动采集任务（在模块进程中运行）。
     */
    void startCollection(final GlobalFloatService floatService) {
        if (collectionRunning) {
            ModuleRunFileLogger.i(TAG, "⚠️ 采集已在运行中");
            return;
        }
        collectionRunning = true;
        persistCollectionState(true);

        // 启动目标 App（如果尚未运行）
        try {
            Intent launchIntent = getPackageManager().getLaunchIntentForPackage(
                    UiComponentConfig.TARGET_PACKAGE);
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(launchIntent);
                ModuleRunFileLogger.i(TAG, "🚀 已启动目标 App: "
                        + UiComponentConfig.TARGET_PACKAGE);
            } else {
                ModuleRunFileLogger.w(TAG, "⚠️ 未找到目标 App: "
                        + UiComponentConfig.TARGET_PACKAGE);
            }
        } catch (Throwable e) {
            ModuleRunFileLogger.e(TAG, "启动目标 App 失败", e);
        }

        final int totalCycles = ModuleSettings.getCycleLimit(
                ModuleSettings.appPrefs(this));
        ModuleRunFileLogger.i(TAG, "🟢🟢🟢 ════ 采集启动（无障碍模式）════ 🟢🟢🟢");
        ModuleRunFileLogger.i(TAG, "📋 计划循环次数: " + totalCycles);

        if (floatService != null) {
            floatService.appendLog("🟢 采集已启动");
        }

        runCollectionCycle(1, totalCycles, floatService);
    }

    private void runCollectionCycle(final int currentCycle, final int totalCycles,
                                     final GlobalFloatService floatService) {
        if (!collectionRunning || currentCycle > totalCycles) {
            ModuleRunFileLogger.i(TAG, "🏁🏁🏁 ════ 所有循环完成 ════ 🏁🏁🏁");
            markCollectionFinished();
            if (floatService != null) {
                floatService.appendLog("🏁 所有循环完成");
            }
            return;
        }

        ModuleRunFileLogger.i(TAG, "🔄 ════ 开始第 " + currentCycle + "/" + totalCycles
                + " 轮采集循环 ════");

        if (floatService != null) {
            floatService.appendLog("🔄 循环 " + currentCycle + "/" + totalCycles);
        }

        LiveRoomTaskScriptRunner runner = new LiveRoomTaskScriptRunner(
                this, handler, new LiveRoomTaskScriptRunner.TaskBridge() {
            @Override
            public boolean shouldStop() {
                return !collectionRunning;
            }

            @Override
            public void updateStatus(String status) {
                ModuleRunFileLogger.i(TAG, "状态更新: " + status);
                if (floatService != null) {
                    floatService.appendLog(status);
                }
            }
        });

        runner.execute(new LiveRoomTaskScriptRunner.TaskFinishListener() {
            @Override
            public void onTaskFinished(boolean success, String message) {
                ModuleRunFileLogger.i(TAG, "🔄 循环 " + currentCycle + "/" + totalCycles
                        + " 完成: " + message);
                if (floatService != null) {
                    floatService.appendLog("循环 " + currentCycle + " 完成: " + message);
                }

                if (currentCycle < totalCycles && collectionRunning) {
                    // 等待 3 秒后开始下一轮
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            runCollectionCycle(currentCycle + 1, totalCycles, floatService);
                        }
                    }, 3000L);
                } else {
                    markCollectionFinished();
                    ModuleRunFileLogger.i(TAG, "🏁🏁🏁 ════ 所有循环完成 ════ 🏁🏁🏁");
                    if (floatService != null) {
                        floatService.appendLog("🏁 全部完成");
                    }
                }
            }
        });
    }

    void stopCollection() {
        if (collectionRunning) {
            collectionRunning = false;
            persistCollectionState(false);
            ModuleRunFileLogger.i(TAG, "🛑 采集已停止");
        }
    }

    /** 标记采集正常完成 */
    private void markCollectionFinished() {
        collectionRunning = false;
        persistCollectionState(false);
    }

    boolean isCollectionRunning() {
        return collectionRunning;
    }

    // ─────────────────────── 辅助采集方法（供外部调用） ───────────────────────

    /**
     * 通过无障碍 API 查找并点击指定资源 ID 的节点。
     */
    boolean clickNodeByResourceId(String fullResourceId) {
        if (TextUtils.isEmpty(fullResourceId)) return false;
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return false;
            List<AccessibilityNodeInfo> nodes =
                    root.findAccessibilityNodeInfosByViewId(fullResourceId);
            if (nodes != null && !nodes.isEmpty()) {
                boolean clicked = clickClickableNode(nodes.get(0));
                root.recycle();
                return clicked;
            }
            root.recycle();
        } catch (Throwable e) {
            Log.e(TAG, "clickNodeByResourceId error", e);
        }
        return false;
    }

    /**
     * 通过文本查找并点击节点（精确匹配）。
     */
    boolean clickNodeByText(String text) {
        if (TextUtils.isEmpty(text)) return false;
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return false;
            List<AccessibilityNodeInfo> nodes =
                    root.findAccessibilityNodeInfosByText(text);
            if (nodes != null) {
                for (AccessibilityNodeInfo node : nodes) {
                    CharSequence ct = node.getText();
                    if (ct != null && ct.toString().equals(text)) {
                        boolean clicked = clickClickableNode(node);
                        root.recycle();
                        return clicked;
                    }
                }
            }
            root.recycle();
        } catch (Throwable e) {
            Log.e(TAG, "clickNodeByText error", e);
        }
        return false;
    }

    /**
     * 点击节点；如果不可点击，沿父节点链向上查找。
     */
    boolean clickClickableNode(AccessibilityNodeInfo node) {
        if (node == null) return false;
        if (node.isClickable()) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
        AccessibilityNodeInfo current = node.getParent();
        for (int i = 0; i < 8 && current != null; i++) {
            if (current.isClickable()) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
            current = current.getParent();
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    /**
     * 获取指定资源 ID 的节点文本。
     */
    String getTextByResourceId(String fullResourceId) {
        if (TextUtils.isEmpty(fullResourceId)) return "";
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return "";
            List<AccessibilityNodeInfo> nodes =
                    root.findAccessibilityNodeInfosByViewId(fullResourceId);
            if (nodes != null && !nodes.isEmpty()) {
                CharSequence text = nodes.get(0).getText();
                root.recycle();
                return text != null ? text.toString().trim() : "";
            }
            root.recycle();
        } catch (Throwable e) {
            Log.e(TAG, "getTextByResourceId error", e);
        }
        return "";
    }

    /**
     * 检查资源 ID 节点是否存在。
     */
    boolean isNodePresent(String fullResourceId) {
        if (TextUtils.isEmpty(fullResourceId)) return false;
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return false;
            List<AccessibilityNodeInfo> nodes =
                    root.findAccessibilityNodeInfosByViewId(fullResourceId);
            boolean present = nodes != null && !nodes.isEmpty();
            root.recycle();
            return present;
        } catch (Throwable e) { return false; }
    }

    /**
     * 检查文本是否存在。
     */
    boolean isTextPresent(String text) {
        if (TextUtils.isEmpty(text)) return false;
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return false;
            List<AccessibilityNodeInfo> nodes =
                    root.findAccessibilityNodeInfosByText(text);
            boolean present = nodes != null && !nodes.isEmpty();
            root.recycle();
            return present;
        } catch (Throwable e) { return false; }
    }

    /**
     * 获取所有匹配资源 ID 的节点文本列表。
     */
    List<String> getTextListByResourceId(String fullResourceId) {
        List<String> list = new ArrayList<>();
        if (TextUtils.isEmpty(fullResourceId)) return list;
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return list;
            List<AccessibilityNodeInfo> nodes =
                    root.findAccessibilityNodeInfosByViewId(fullResourceId);
            if (nodes != null) {
                for (AccessibilityNodeInfo node : nodes) {
                    CharSequence text = node.getText();
                    list.add(text != null ? text.toString().trim() : "");
                }
            }
            root.recycle();
        } catch (Throwable e) {
            Log.e(TAG, "getTextListByResourceId error", e);
        }
        return list;
    }

    /**
     * 点击第 N 个指定资源 ID 的节点。
     */
    boolean clickNthNodeByResourceId(String fullResourceId, int index) {
        if (TextUtils.isEmpty(fullResourceId)) return false;
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return false;
            List<AccessibilityNodeInfo> nodes =
                    root.findAccessibilityNodeInfosByViewId(fullResourceId);
            if (nodes != null && index < nodes.size()) {
                boolean clicked = clickClickableNode(nodes.get(index));
                root.recycle();
                return clicked;
            }
            root.recycle();
        } catch (Throwable e) {
            Log.e(TAG, "clickNthNodeByResourceId error", e);
        }
        return false;
    }

    // ─────────────────────── 在线榜用户卡片结构化读取 ───────────────────────

    /** 在线榜的一张用户卡片信息 */
    static final class OnlineUserCard {
        final int index;         // 在 RecyclerView 中的索引
        final String nickname;   // 昵称文本
        final String userCode;   // ID 文本（如 "ID:22222877"），可能为空
        final boolean isAdmin;   // 是否管理员/房主

        OnlineUserCard(int index, String nickname, String userCode, boolean isAdmin) {
            this.index = index;
            this.nickname = nickname != null ? nickname.trim() : "";
            this.userCode = userCode != null ? userCode.trim() : "";
            this.isAdmin = isAdmin;
        }
    }

    /**
     * 结构化读取在线榜中所有可见的用户卡片。
     * 遍历 RecyclerView 的子 ViewGroup，从同一个卡片中同时读取
     * tv_nickname、tv_user_code 和 iv_room_role（管理员标识），
     * 保证数据一一对应。
     */
    List<OnlineUserCard> getOnlineUserCards() {
        List<OnlineUserCard> cards = new ArrayList<>();
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return cards;

            // 找到 RecyclerView
            List<AccessibilityNodeInfo> recyclers =
                    root.findAccessibilityNodeInfosByViewId(UiComponentConfig.ROOM_LIST_RECYCLER_ID);
            if (recyclers == null || recyclers.isEmpty()) {
                root.recycle();
                return cards;
            }
            AccessibilityNodeInfo recyclerView = recyclers.get(0);

            // 遍历 RecyclerView 的直接子节点（每个子节点是一个用户卡片 ViewGroup）
            for (int i = 0; i < recyclerView.getChildCount(); i++) {
                AccessibilityNodeInfo cardNode = recyclerView.getChild(i);
                if (cardNode == null) continue;

                // 使用 findAccessibilityNodeInfosByViewId 搜索（比手动递归更可靠）
                String nickname = "";
                String userCode = "";
                boolean isAdmin = false;

                List<AccessibilityNodeInfo> nickNodes =
                        cardNode.findAccessibilityNodeInfosByViewId(UiComponentConfig.USER_NICKNAME_ID);
                if (nickNodes != null && !nickNodes.isEmpty()) {
                    CharSequence text = nickNodes.get(0).getText();
                    nickname = text != null ? text.toString().trim() : "";
                }

                List<AccessibilityNodeInfo> codeNodes =
                        cardNode.findAccessibilityNodeInfosByViewId(UiComponentConfig.USER_CODE_ID);
                if (codeNodes != null && !codeNodes.isEmpty()) {
                    CharSequence text = codeNodes.get(0).getText();
                    userCode = text != null ? text.toString().trim() : "";
                }

                List<AccessibilityNodeInfo> roleNodes =
                        cardNode.findAccessibilityNodeInfosByViewId(UiComponentConfig.USER_ROOM_ROLE_ICON_ID);
                if (roleNodes != null && !roleNodes.isEmpty()) {
                    isAdmin = true;
                }

                // 只添加有昵称的卡片
                if (!TextUtils.isEmpty(nickname)) {
                    cards.add(new OnlineUserCard(i, nickname, userCode, isAdmin));
                    if (isAdmin) {
                        ModuleRunFileLogger.i(TAG, "🔍 检测到管理员/房主: " + nickname
                                + " (" + userCode + ")");
                    }
                }
            }
            root.recycle();
        } catch (Throwable e) {
            Log.e(TAG, "getOnlineUserCards error", e);
        }
        return cards;
    }

    /**
     * 检测在线榜第 index 个用户是否是管理员/房主。
     * 管理员在 ll_info 容器下有 iv_room_role 图标子节点。
     */
    boolean isAdminUserAtIndex(int index) {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return false;
            List<AccessibilityNodeInfo> infoLayouts =
                    root.findAccessibilityNodeInfosByViewId(UiComponentConfig.USER_INFO_LAYOUT_ID);
            if (infoLayouts != null && index < infoLayouts.size()) {
                AccessibilityNodeInfo infoLayout = infoLayouts.get(index);
                for (int i = 0; i < infoLayout.getChildCount(); i++) {
                    AccessibilityNodeInfo child = infoLayout.getChild(i);
                    if (child != null) {
                        CharSequence viewId = child.getViewIdResourceName();
                        if (UiComponentConfig.USER_ROOM_ROLE_ICON_ID.equals(
                                viewId != null ? viewId.toString() : "")) {
                            root.recycle();
                            return true;
                        }
                    }
                }
            }
            root.recycle();
        } catch (Throwable e) {
            Log.e(TAG, "isAdminUserAtIndex error", e);
        }
        return false;
    }

    /**
     * 小幅度向下滚动（在线榜用）。
     * 优先对 RecyclerView 执行 ACTION_SCROLL_FORWARD，
     * 如果找不到可滚动节点则回退到 dispatchGesture。
     */
    boolean performSmallScrollDown() {
        // 策略：用 RecyclerView 的实际 bounds 计算坐标，
        // 通过 "input swipe" shell 命令执行精确滑动（跨屏幕兼容，幅度可控）
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                List<AccessibilityNodeInfo> recyclers =
                        root.findAccessibilityNodeInfosByViewId(
                                UiComponentConfig.ROOM_LIST_RECYCLER_ID);
                if (recyclers != null && !recyclers.isEmpty()) {
                    AccessibilityNodeInfo rv = recyclers.get(0);
                    android.graphics.Rect bounds = new android.graphics.Rect();
                    rv.getBoundsInScreen(bounds);

                    if (bounds.height() > 100) {
                        int x = bounds.centerX();
                        // 滑动幅度：节点高度的 50%
                        int scrollAmount = bounds.height() * 50 / 100;
                        int startY = bounds.centerY() + scrollAmount / 2;
                        int endY = bounds.centerY() - scrollAmount / 2;

                        boolean inputResult = execInputSwipe(x, startY, x, endY, 300);
                        if (inputResult) {
                            ModuleRunFileLogger.i(TAG, "📜 performSmallScrollDown (input swipe"
                                    + " y:" + startY + "→" + endY
                                    + " amount:" + scrollAmount + ") result=true");
                            root.recycle();
                            return true;
                        }
                    }

                    // 回退：ACTION_SCROLL_FORWARD
                    boolean result = rv.performAction(
                            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
                    ModuleRunFileLogger.i(TAG,
                            "📜 performSmallScrollDown (SCROLL_FORWARD fallback) result="
                                    + result);
                    root.recycle();
                    return result;
                }
                root.recycle();
            }
        } catch (Throwable e) {
            Log.e(TAG, "performSmallScrollDown error", e);
        }
        return false;
    }

    /**
     * 通过 shell 命令执行 input swipe。
     * 这种方式不受 Display 限制，坐标精确可控。
     */
    private boolean execInputSwipe(int x1, int y1, int x2, int y2, int durationMs) {
        try {
            String cmd = "input swipe " + x1 + " " + y1 + " " + x2 + " " + y2
                    + " " + durationMs;
            // 使用 su 执行（LSPosed 设备已 root），普通 sh 没有 INJECT_EVENTS 权限
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            // 超时 3 秒防止 su 卡住
            boolean finished = p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                Log.w(TAG, "input swipe 超时");
                return false;
            }
            return p.exitValue() == 0;
        } catch (Throwable e) {
            Log.w(TAG, "input swipe 失败: " + e.getMessage());
        }
        return false;
    }

    /**
     * 手势向下滑动（榜单列表刷新/加载更多用）。
     * 幅度为屏幕的 40%。
     */
    boolean performGestureSwipeDown() {
        return performInputSwipe(0.75f, 0.25f, 400);
    }

    /**
     * 手势小幅度向下滑动。
     * 幅度为屏幕的 20%。
     */
    boolean performGestureSmallSwipeDown() {
        return performInputSwipe(0.60f, 0.40f, 300);
    }

    /**
     * 手势向上滑动（下拉刷新用）。
     * 幅度为屏幕的 40%。
     */
    boolean performGestureSwipeUp() {
        return performInputSwipe(0.30f, 0.70f, 400);
    }

    /**
     * 通过 su input swipe 执行滑动（跨屏幕兼容）。
     * 从 root window 获取实际屏幕尺寸。
     */
    private boolean performInputSwipe(float startYRatio, float endYRatio, int durationMs) {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                android.graphics.Rect bounds = new android.graphics.Rect();
                root.getBoundsInScreen(bounds);
                root.recycle();

                if (bounds.height() > 100) {
                    int x = bounds.centerX();
                    int startY = bounds.top + (int)(bounds.height() * startYRatio);
                    int endY = bounds.top + (int)(bounds.height() * endYRatio);
                    boolean result = execInputSwipe(x, startY, x, endY, durationMs);
                    ModuleRunFileLogger.i(TAG, "📜 performInputSwipe ("
                            + String.format("%.0f%%→%.0f%%", startYRatio * 100, endYRatio * 100)
                            + " y:" + startY + "→" + endY + ") result=" + result);
                    return result;
                }
            }
        } catch (Throwable e) {
            Log.e(TAG, "performInputSwipe error", e);
        }
        return false;
    }

    /** 执行全局返回。 */
    void performGlobalBack() {
        performGlobalAction(GLOBAL_ACTION_BACK);
    }

    /** 向下滚动指定资源 ID 的可滚动容器。 */
    boolean performScrollDownByResourceId(String fullResourceId) {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return false;
            if (!TextUtils.isEmpty(fullResourceId)) {
                List<AccessibilityNodeInfo> nodes =
                        root.findAccessibilityNodeInfosByViewId(fullResourceId);
                if (nodes != null && !nodes.isEmpty()) {
                    boolean r = nodes.get(0).performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
                    root.recycle();
                    return r;
                }
            }
            AccessibilityNodeInfo scrollable = findScrollableNode(root);
            if (scrollable != null) {
                boolean r = scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
                root.recycle();
                return r;
            }
            root.recycle();
        } catch (Throwable e) {
            Log.e(TAG, "performScrollDownByResourceId error", e);
        }
        return false;
    }

    /** 向上滚动指定资源 ID 的可滚动容器。 */
    boolean performScrollUpByResourceId(String fullResourceId) {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return false;
            if (!TextUtils.isEmpty(fullResourceId)) {
                List<AccessibilityNodeInfo> nodes =
                        root.findAccessibilityNodeInfosByViewId(fullResourceId);
                if (nodes != null && !nodes.isEmpty()) {
                    boolean r = nodes.get(0).performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
                    root.recycle();
                    return r;
                }
            }
            AccessibilityNodeInfo scrollable = findScrollableNode(root);
            if (scrollable != null) {
                boolean r = scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
                root.recycle();
                return r;
            }
            root.recycle();
        } catch (Throwable e) {
            Log.e(TAG, "performScrollUpByResourceId error", e);
        }
        return false;
    }

    void performScrollDown() {
        performScrollDownByResourceId(null);
    }

    private AccessibilityNodeInfo findScrollableNode(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isScrollable()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo found = findScrollableNode(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    void reloadCustomRules() {
        if (customAdProcessor != null) {
            customAdProcessor.reloadRules();
            ModuleRunFileLogger.i(TAG, "自定义广告规则已重新加载: "
                    + customAdProcessor.getRuleCount() + " 条");
        }
    }
}
