package com.oodbye.shuangyulspmodule;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
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
        super.onDestroy();
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        ModuleRunFileLogger.i(TAG, "无障碍服务已连接");
        LevelDataBridge.registerReceiver(this);
        startProactiveAdCheck();

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
     * 小幅度向下滚动，适合在线列表等需要精细控制的场景。
     * 使用无障碍 ACTION_SCROLL_FORWARD 在可滚动控件上执行滚动。
     */
    boolean performSmallScrollDown() {
        boolean result = performScrollDownByResourceId(null);
        ModuleRunFileLogger.i(TAG, "📜 performSmallScrollDown (accessibility) result=" + result);
        return result;
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
