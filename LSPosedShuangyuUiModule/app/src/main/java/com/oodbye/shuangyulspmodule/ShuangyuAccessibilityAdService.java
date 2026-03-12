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
        customAdProcessor = new CustomRulesAdProcessor();
        ModuleRunFileLogger.init();
        ModuleRunFileLogger.i(TAG, "ShuangyuAccessibilityAdService created");
    }

    @Override
    public void onDestroy() {
        sInstance = null;
        collectingEnabled = false;
        ModuleRunFileLogger.i(TAG, "ShuangyuAccessibilityAdService destroyed");
        super.onDestroy();
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        ModuleRunFileLogger.i(TAG, "无障碍服务已连接");
    }

    static ShuangyuAccessibilityAdService getInstance() {
        return sInstance;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        int eventType = event.getEventType();

        // 广告检测
        if (ModuleSettings.isAccessibilityAdServiceEnabled()) {
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

            // 2. 通用广告弹窗检测
            if (checkAndDismissGenericAd(root)) {
                AdHandledNotifier.show(this, "通用广告");
            }

            root.recycle();
        } catch (Throwable e) {
            Log.e(TAG, "checkForAds error", e);
        }
    }

    private boolean checkAndDismissGenericAd(AccessibilityNodeInfo root) {
        // 查找常见的关闭按钮文字
        String[] dismissTexts = {"关闭", "跳过", "不感兴趣", "取消", "我知道了", "知道了", "以后再说"};
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
                AccessibilityNodeInfo target = nodes.get(0);
                boolean clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
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
     * 执行全局返回操作。
     */
    void performGlobalBack() {
        performGlobalAction(GLOBAL_ACTION_BACK);
    }

    /**
     * 向下滑动当前焦点区域。
     */
    void performScrollDown() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return;
            // 查找可滚动节点
            AccessibilityNodeInfo scrollable = findScrollableNode(root);
            if (scrollable != null) {
                scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            }
            root.recycle();
        } catch (Throwable e) {
            Log.e(TAG, "performScrollDown error", e);
        }
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

    /**
     * 重新加载自定义广告规则。
     */
    void reloadCustomRules() {
        if (customAdProcessor != null) {
            customAdProcessor.reloadRules();
            ModuleRunFileLogger.i(TAG, "自定义广告规则已重新加载: "
                    + customAdProcessor.getRuleCount() + " 条");
        }
    }
}
