package com.oodbye.shuangyulspmodule;

import android.accessibilityservice.AccessibilityService;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 自定义广告规则引擎（从 JSON 文件加载规则）。
 */
final class AccessibilityCustomRulesAdEngine {
    private static final String TAG = "SYLspModule";

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

    AccessibilityCustomRulesAdEngine() {
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
                    if (executeAction(root, rule)) {
                        handled = true;
                        Log.i(TAG, "AdRule '" + rule.name + "' 命中并执行");
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
        File file = new File(UiComponentConfig.CUSTOM_AD_RULES_FILE_PATH);
        if (!file.exists() || !file.isFile()) return;
        try {
            byte[] bytes = new byte[(int) file.length()];
            java.io.FileInputStream fis = new java.io.FileInputStream(file);
            fis.read(bytes);
            fis.close();
            String json = new String(bytes, StandardCharsets.UTF_8);
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
            Log.i(TAG, "Loaded " + rules.size() + " custom ad rules");
        } catch (Throwable e) {
            Log.e(TAG, "Failed to load ad rules", e);
        }
    }

    private AccessibilityNodeInfo findMatch(AccessibilityNodeInfo root, AdRule rule) {
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
        }
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
        if (!TextUtils.isEmpty(rule.actionResourceId)) {
            List<AccessibilityNodeInfo> nodes =
                    root.findAccessibilityNodeInfosByViewId(rule.actionResourceId);
            if (nodes != null) {
                for (AccessibilityNodeInfo node : nodes) {
                    if (matchesText(node, rule.actionText)
                            && matchesClass(node, rule.actionClassName)) {
                        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    }
                }
            }
        }
        if (!TextUtils.isEmpty(rule.actionText)) {
            List<AccessibilityNodeInfo> nodes =
                    root.findAccessibilityNodeInfosByText(rule.actionText);
            if (nodes != null) {
                for (AccessibilityNodeInfo node : nodes) {
                    if (matchesClass(node, rule.actionClassName)) {
                        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    }
                }
            }
        }
        return false;
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
