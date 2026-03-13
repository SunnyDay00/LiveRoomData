package com.oodbye.shuangyulspmodule;

import android.accessibilityservice.AccessibilityService;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 自定义广告规则引擎（从 JSON 文件加载规则）。
 * 优先读取外部文件，不存在时从 assets 内置文件回退读取。
 */
final class AccessibilityCustomRulesAdEngine {
    private static final String TAG = "SYLspModule";
    private static final String ASSETS_AD_RULES_FILE = "ad_rules.json";

    static final class AdRule {
        final String name;
        final String matchText;
        final String matchResourceId;
        final String matchClassName;
        final String actionText;
        final String actionResourceId;
        final String actionClassName;

        AdRule(String name, String matchText, String matchResourceId,
               String matchClassName, String actionText,
               String actionResourceId, String actionClassName) {
            this.name = name;
            this.matchText = matchText;
            this.matchResourceId = matchResourceId;
            this.matchClassName = matchClassName;
            this.actionText = actionText;
            this.actionResourceId = actionResourceId;
            this.actionClassName = actionClassName;
        }
    }

    private final List<AdRule> rules = new ArrayList<>();
    private android.content.Context context;

    AccessibilityCustomRulesAdEngine(android.content.Context context) {
        this.context = context;
        loadRules();
    }

    void reloadRules() {
        rules.clear();
        loadRules();
    }

    int getRuleCount() {
        return rules.size();
    }

    /** 在窗口中匹配规则并执行动作 */
    boolean processRules(AccessibilityService service) {
        if (service == null || rules.isEmpty()) return false;
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) return false;
        boolean handled = false;
        try {
            for (AdRule rule : rules) {
                AccessibilityNodeInfo matchNode = findMatch(root, rule);
                if (matchNode != null) {
                    Log.i(TAG, "🎯 广告规则匹配: '" + rule.name + "'，尝试执行动作...");
                    if (executeAction(root, rule)) {
                        handled = true;
                        Log.i(TAG, "✅ 广告规则 '" + rule.name + "' 命中并已执行动作");
                    } else {
                        Log.w(TAG, "⚠️ 广告规则 '" + rule.name + "' 匹配但动作执行失败");
                    }
                }
            }
        } catch (Throwable e) {
            Log.e(TAG, "processRules error", e);
        } finally {
            root.recycle();
        }
        return handled;
    }

    private void loadRules() {
        // 优先从外部文件读取
        File file = new File(UiComponentConfig.CUSTOM_AD_RULES_FILE_PATH);
        if (file.exists() && file.isFile()) {
            try {
                byte[] bytes = new byte[(int) file.length()];
                java.io.FileInputStream fis = new java.io.FileInputStream(file);
                fis.read(bytes);
                fis.close();
                String json = new String(bytes, StandardCharsets.UTF_8);
                parseRulesJson(json);
                Log.i(TAG, "从外部文件加载了 " + rules.size() + " 条广告规则");
                return;
            } catch (Throwable e) {
                Log.e(TAG, "读取外部广告规则失败，回退到内置规则", e);
            }
        }

        // 回退：从 APK assets 读取内置规则
        if (context != null) {
            try {
                InputStream is = context.getAssets().open(ASSETS_AD_RULES_FILE);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[1024];
                int len;
                while ((len = is.read(buf)) != -1) {
                    bos.write(buf, 0, len);
                }
                is.close();
                String json = bos.toString("UTF-8");
                parseRulesJson(json);
                Log.i(TAG, "从内置 assets 加载了 " + rules.size() + " 条广告规则");
            } catch (Throwable e) {
                Log.e(TAG, "读取内置广告规则失败", e);
            }
        }
    }

    private void parseRulesJson(String json) {
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                rules.add(new AdRule(
                        obj.optString("name", "rule_" + i),
                        obj.optString("matchText", ""),
                        obj.optString("matchResourceId", ""),
                        obj.optString("matchClassName", ""),
                        obj.optString("actionText", ""),
                        obj.optString("actionResourceId", ""),
                        obj.optString("actionClassName", "")
                ));
            }
        } catch (Throwable e) {
            Log.e(TAG, "解析广告规则 JSON 失败", e);
        }
    }

    private AccessibilityNodeInfo findMatch(AccessibilityNodeInfo root, AdRule rule) {
        // 方法1: 通过 resource-id 查找
        if (!TextUtils.isEmpty(rule.matchResourceId)) {
            List<AccessibilityNodeInfo> nodes =
                    root.findAccessibilityNodeInfosByViewId(rule.matchResourceId);
            if (nodes != null && !nodes.isEmpty()) {
                for (AccessibilityNodeInfo node : nodes) {
                    if (matchesText(node, rule.matchText)
                            && matchesClass(node, rule.matchClassName)) {
                        return node;
                    }
                }
            }
            // 注意：即使 resourceId 找不到，仍继续用 text 方式尝试
        }
        // 方法2: 通过文本查找（作为回退）
        if (!TextUtils.isEmpty(rule.matchText)) {
            List<AccessibilityNodeInfo> nodes =
                    root.findAccessibilityNodeInfosByText(rule.matchText);
            if (nodes != null) {
                for (AccessibilityNodeInfo node : nodes) {
                    if (matchesClass(node, rule.matchClassName)) {
                        return node;
                    }
                }
            }
        }
        return null;
    }

    private boolean executeAction(AccessibilityNodeInfo root, AdRule rule) {
        // 方法1: 通过 resource-id 查找动作按钮
        if (!TextUtils.isEmpty(rule.actionResourceId)) {
            List<AccessibilityNodeInfo> nodes =
                    root.findAccessibilityNodeInfosByViewId(rule.actionResourceId);
            if (nodes != null) {
                for (AccessibilityNodeInfo node : nodes) {
                    if (matchesText(node, rule.actionText)
                            && matchesClass(node, rule.actionClassName)) {
                        Log.i(TAG, "📌 找到动作节点(resourceId): " + rule.actionResourceId);
                        boolean clicked = performClickOnNodeOrParent(node);
                        if (clicked) return true;
                    }
                }
            }
            // 注意：即使 resourceId 找不到，仍继续用 text 方式尝试
        }
        // 方法2: 通过文本查找动作按钮
        if (!TextUtils.isEmpty(rule.actionText)) {
            List<AccessibilityNodeInfo> nodes =
                    root.findAccessibilityNodeInfosByText(rule.actionText);
            if (nodes != null) {
                for (AccessibilityNodeInfo node : nodes) {
                    if (matchesClass(node, rule.actionClassName)) {
                        Log.i(TAG, "📌 找到动作节点(text): " + rule.actionText);
                        boolean clicked = performClickOnNodeOrParent(node);
                        if (clicked) return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 尝试点击节点；如果节点本身不可点击，则向上查找可点击的父节点。
     */
    private boolean performClickOnNodeOrParent(AccessibilityNodeInfo node) {
        if (node == null) return false;
        // 先尝试点击自身
        if (node.isClickable()) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
        // 向上查找可点击的父节点（最多 5 层）
        AccessibilityNodeInfo current = node.getParent();
        for (int i = 0; i < 5 && current != null; i++) {
            if (current.isClickable()) {
                Log.i(TAG, "📌 节点不可点击，使用父节点(层" + (i + 1) + ")执行点击");
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
            current = current.getParent();
        }
        // 最后强制尝试点击原始节点
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    private static boolean matchesText(AccessibilityNodeInfo node, String text) {
        if (TextUtils.isEmpty(text)) return true;
        CharSequence nodeText = node.getText();
        return nodeText != null && nodeText.toString().contains(text);
    }

    private static boolean matchesClass(AccessibilityNodeInfo node, String className) {
        if (TextUtils.isEmpty(className)) return true;
        CharSequence nodeClass = node.getClassName();
        return nodeClass != null && nodeClass.toString().contains(className);
    }
}
