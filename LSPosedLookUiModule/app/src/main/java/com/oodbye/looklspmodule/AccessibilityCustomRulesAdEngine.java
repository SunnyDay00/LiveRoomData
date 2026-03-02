package com.oodbye.looklspmodule;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.view.accessibility.AccessibilityWindowInfo;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class AccessibilityCustomRulesAdEngine {
    private static final String TAG = "LOOKA11yAd";
    private static final String LOG_PREFIX = "[LOOKA11yAd]";
    private static final String ALL_PATTERN_PREFIX = "ALL:";
    private static final Map<String, List<PopupRule>> RULE_CACHE =
            new ConcurrentHashMap<String, List<PopupRule>>();

    private final AccessibilityService service;
    private final List<PopupRule> popupRules;
    private long lastScanAt;
    private long lastNoTargetWindowLogAt;

    AccessibilityCustomRulesAdEngine(AccessibilityService service) {
        this.service = service;
        this.popupRules = loadRulesForTargetPackage(service);
        this.lastScanAt = 0L;
        this.lastNoTargetWindowLogAt = 0L;
    }

    boolean scanAndHandleIfNeeded(long nowMs) {
        if (service == null) {
            return false;
        }
        if (nowMs - lastScanAt < UiComponentConfig.AD_SCAN_INTERVAL_MS) {
            return false;
        }
        lastScanAt = nowMs;
        if (popupRules.isEmpty()) {
            return false;
        }
        AccessibilityNodeInfo root = null;
        List<NodeRef> nodes = Collections.emptyList();
        try {
            root = service.getRootInActiveWindow();
            if (root != null) {
                CharSequence rootPackage = root.getPackageName();
                if (UiComponentConfig.TARGET_PACKAGE.equals(safeTrim(rootPackage))) {
                    nodes = flattenNodes(root);
                }
            }
            if (nodes.isEmpty()) {
                nodes = flattenNodesFromFocusedTargetWindow(UiComponentConfig.TARGET_PACKAGE);
            }
            if (nodes.isEmpty()) {
                if (nowMs - lastNoTargetWindowLogAt > 3000L) {
                    lastNoTargetWindowLogAt = nowMs;
                    log("广告扫描：当前显示屏未命中 LOOK 窗口，跳过本轮");
                }
                return false;
            }
            for (PopupRule rule : popupRules) {
                if (rule == null) {
                    continue;
                }
                if (rule.delayPopupMs > 0 && nowMs - rule.lastHandleAt < rule.delayPopupMs) {
                    continue;
                }
                NodeRef trigger = findTriggerNodeByRulePattern(nodes, rule.idPattern);
                if (trigger == null) {
                    logAllRuleMissingIfNeeded(rule, nodes, nowMs);
                    continue;
                }
                if (executeAction(rule.actionPattern, nodes, trigger)) {
                    rule.lastHandleAt = nowMs;
                    AdHandledNotifier.show(service, buildAdHint(rule));
                    log("命中规则: id=" + rule.idPattern + " action=" + rule.actionPattern);
                    return true;
                }
                if (nowMs - rule.lastFailLogAt > 2000L) {
                    rule.lastFailLogAt = nowMs;
                    log("命中规则但动作失败: id=" + rule.idPattern + " action=" + rule.actionPattern);
                }
            }
            return false;
        } catch (Throwable e) {
            log("广告扫描异常: " + e);
            return false;
        } finally {
            recycleNodeRefs(nodes);
            safeRecycle(root);
        }
    }

    private boolean executeAction(String actionPattern, List<NodeRef> nodes, NodeRef triggerNode) {
        if (TextUtils.isEmpty(actionPattern)) {
            return clickWithParentFallback(triggerNode == null ? null : triggerNode.node);
        }
        if ("GLOBAL_ACTION_BACK".equalsIgnoreCase(actionPattern)) {
            try {
                return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
            } catch (Throwable ignore) {
                return false;
            }
        }
        NodeRef actionNode = findNodeByPattern(nodes, actionPattern);
        if (actionNode != null && clickWithParentFallback(actionNode.node)) {
            return true;
        }
        if (triggerNode != null && clickWithParentFallback(triggerNode.node)) {
            return true;
        }
        for (String fallbackAction : UiComponentConfig.COMMON_CLOSE_ACTIONS) {
            NodeRef fallbackNode = findNodeByPattern(nodes, fallbackAction);
            if (fallbackNode != null && clickWithParentFallback(fallbackNode.node)) {
                return true;
            }
        }
        return false;
    }

    private String buildAdHint(PopupRule rule) {
        if (rule == null) {
            return "";
        }
        String action = safeTrim(rule.actionPattern);
        if ("GLOBAL_ACTION_BACK".equalsIgnoreCase(action)) {
            return "已返回关闭";
        }
        if (TextUtils.isEmpty(action)) {
            return "已点击关闭";
        }
        return "已执行规则动作";
    }

    private boolean clickWithParentFallback(AccessibilityNodeInfo start) {
        if (start == null) {
            return false;
        }
        AccessibilityNodeInfo current = start;
        List<AccessibilityNodeInfo> parentChain = new ArrayList<AccessibilityNodeInfo>(6);
        try {
            for (int depth = 0; current != null && depth < 6; depth++) {
                if (isNodeVisible(current)) {
                    try {
                        if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            return true;
                        }
                    } catch (Throwable ignore) {
                    }
                    try {
                        if (tapNodeCenter(current)) {
                            return true;
                        }
                    } catch (Throwable ignore) {
                    }
                }
                AccessibilityNodeInfo parent = current.getParent();
                if (parent == null) {
                    break;
                }
                parentChain.add(parent);
                current = parent;
            }
            return false;
        } finally {
            for (AccessibilityNodeInfo parent : parentChain) {
                safeRecycle(parent);
            }
        }
    }

    private boolean tapNodeCenter(AccessibilityNodeInfo node) {
        if (node == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return false;
        }
        Rect rect = new Rect();
        node.getBoundsInScreen(rect);
        if (rect.width() <= 0 || rect.height() <= 0) {
            return false;
        }
        float x = rect.exactCenterX();
        float y = rect.exactCenterY();
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0L, 55L);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        return service.dispatchGesture(gesture, null, null);
    }

    private static boolean isNodeVisible(AccessibilityNodeInfo node) {
        if (node == null) {
            return false;
        }
        try {
            return node.isVisibleToUser();
        } catch (Throwable ignore) {
            return false;
        }
    }

    private static List<NodeRef> flattenNodes(AccessibilityNodeInfo root) {
        if (root == null) {
            return Collections.emptyList();
        }
        List<NodeRef> out = new ArrayList<NodeRef>(256);
        ArrayDeque<AccessibilityNodeInfo> stack = new ArrayDeque<AccessibilityNodeInfo>();
        stack.push(root);
        while (!stack.isEmpty()) {
            AccessibilityNodeInfo node = stack.pop();
            if (node == null) {
                continue;
            }
            out.add(new NodeRef(node, collectCandidates(node)));
            int count = Math.max(0, node.getChildCount());
            for (int i = count - 1; i >= 0; i--) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    stack.push(child);
                }
            }
        }
        return out;
    }

    private static void recycleNodeRefs(List<NodeRef> refs) {
        if (refs == null || refs.isEmpty()) {
            return;
        }
        for (NodeRef ref : refs) {
            if (ref == null) {
                continue;
            }
            safeRecycle(ref.node);
        }
    }

    private static void safeRecycle(AccessibilityNodeInfo node) {
        if (node == null) {
            return;
        }
        try {
            node.recycle();
        } catch (Throwable ignore) {
        }
    }

    private List<NodeRef> flattenNodesFromFocusedTargetWindow(String targetPackage) {
        if (service == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return Collections.emptyList();
        }
        List<AccessibilityWindowInfo> windows;
        try {
            windows = service.getWindows();
        } catch (Throwable ignore) {
            return Collections.emptyList();
        }
        if (windows == null || windows.isEmpty()) {
            return Collections.emptyList();
        }
        for (AccessibilityWindowInfo window : windows) {
            if (window == null) {
                continue;
            }
            boolean activeOrFocused = false;
            try {
                activeOrFocused = window.isActive() || window.isFocused();
            } catch (Throwable ignore) {
                activeOrFocused = false;
            }
            if (!activeOrFocused) {
                continue;
            }
            AccessibilityNodeInfo root = null;
            try {
                root = window.getRoot();
                if (root == null) {
                    continue;
                }
                if (!targetPackage.equals(safeTrim(root.getPackageName()))) {
                    safeRecycle(root);
                    continue;
                }
                return flattenNodes(root);
            } catch (Throwable ignore) {
                safeRecycle(root);
            }
        }
        return Collections.emptyList();
    }

    private void logAllRuleMissingIfNeeded(PopupRule rule, List<NodeRef> nodes, long nowMs) {
        if (rule == null) {
            return;
        }
        String idPattern = safeTrim(rule.idPattern);
        if (!idPattern.startsWith(ALL_PATTERN_PREFIX)) {
            return;
        }
        if (nowMs - rule.lastMissLogAt < 3000L) {
            return;
        }
        String allPattern = safeTrim(idPattern.substring(ALL_PATTERN_PREFIX.length()));
        if (TextUtils.isEmpty(allPattern)) {
            return;
        }
        String[] conditions = allPattern.split("&");
        List<String> missing = new ArrayList<String>(conditions.length);
        for (String condition : conditions) {
            String token = safeTrim(condition);
            if (token.isEmpty()) {
                continue;
            }
            if (findNodeByPattern(nodes, token) == null) {
                missing.add(token);
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        rule.lastMissLogAt = nowMs;
        log("ALL规则未满足: id=" + idPattern + " missing=" + missing);
    }

    private static NodeRef findNodeByPattern(List<NodeRef> nodes, String pattern) {
        if (nodes == null || nodes.isEmpty()) {
            return null;
        }
        String normalized = safeTrim(pattern);
        if (normalized.isEmpty()) {
            return null;
        }
        for (NodeRef ref : nodes) {
            if (ref == null) {
                continue;
            }
            if (matchesPattern(ref, normalized)) {
                return ref;
            }
        }
        return null;
    }

    private static NodeRef findTriggerNodeByRulePattern(List<NodeRef> nodes, String rulePattern) {
        String normalized = safeTrim(rulePattern);
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.startsWith(ALL_PATTERN_PREFIX)) {
            return findTriggerByAllConditions(
                    nodes,
                    safeTrim(normalized.substring(ALL_PATTERN_PREFIX.length()))
            );
        }
        return findNodeByPattern(nodes, normalized);
    }

    private static NodeRef findTriggerByAllConditions(List<NodeRef> nodes, String allPattern) {
        if (nodes == null || nodes.isEmpty()) {
            return null;
        }
        if (TextUtils.isEmpty(allPattern)) {
            return null;
        }
        String[] conditions = allPattern.split("&");
        NodeRef trigger = null;
        for (String condition : conditions) {
            String token = safeTrim(condition);
            if (token.isEmpty()) {
                continue;
            }
            NodeRef matched = findNodeByPattern(nodes, token);
            if (matched == null) {
                return null;
            }
            if (trigger == null) {
                trigger = matched;
            }
        }
        return trigger;
    }

    private static boolean matchesPattern(NodeRef ref, String rawPattern) {
        if (ref == null) {
            return false;
        }
        String pattern = safeTrim(rawPattern);
        if (pattern.isEmpty()) {
            return false;
        }
        boolean exact = false;
        if (pattern.startsWith("=") || pattern.startsWith("|") || pattern.startsWith("+")) {
            if (pattern.startsWith("=")) {
                exact = true;
            }
            pattern = safeTrim(pattern.substring(1));
        }
        if (pattern.isEmpty()) {
            return false;
        }
        String[] segments = pattern.split("&");
        for (String segment : segments) {
            String token = safeTrim(segment);
            if (token.isEmpty()) {
                continue;
            }
            if (!matchTokenOnNode(ref.candidates, token, exact)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchTokenOnNode(List<String> candidates, String token, boolean exact) {
        if (candidates == null || candidates.isEmpty()) {
            return false;
        }
        for (String candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            if (exact) {
                if (candidate.equals(token)) {
                    return true;
                }
            } else if (candidate.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> collectCandidates(AccessibilityNodeInfo node) {
        List<String> out = new ArrayList<String>(6);
        if (node == null) {
            return out;
        }
        addCandidate(out, node.getContentDescription());
        addCandidate(out, node.getText());
        String viewId = safeTrim(node.getViewIdResourceName());
        addCandidate(out, viewId);
        addCandidate(out, UiComponentConfig.resourceEntryFromFullId(viewId));
        addCandidate(out, node.getClassName());
        addCandidate(out, node.getPackageName());
        return out;
    }

    private static void addCandidate(List<String> out, CharSequence value) {
        if (out == null) {
            return;
        }
        String normalized = safeTrim(value);
        if (TextUtils.isEmpty(normalized)) {
            return;
        }
        if (!out.contains(normalized)) {
            out.add(normalized);
        }
    }

    private static List<PopupRule> loadRulesForTargetPackage(AccessibilityService service) {
        if (service == null) {
            return Collections.emptyList();
        }
        String packageName = UiComponentConfig.TARGET_PACKAGE;
        List<PopupRule> cache = RULE_CACHE.get(packageName);
        if (cache != null) {
            return cache;
        }
        List<PopupRule> loaded = parsePopupRules(service, packageName);
        RULE_CACHE.put(packageName, loaded);
        return loaded;
    }

    private static List<PopupRule> parsePopupRules(AccessibilityService service, String packageName) {
        String rawJson = readCustomRulesText(service);
        if (TextUtils.isEmpty(rawJson)) {
            return Collections.emptyList();
        }
        String targetHash = String.valueOf(packageName.hashCode());
        try {
            JSONArray rootArray = new JSONArray(rawJson);
            for (int i = 0; i < rootArray.length(); i++) {
                JSONObject wrapper = rootArray.optJSONObject(i);
                if (wrapper == null) {
                    continue;
                }
                String packageRuleJson = safeTrim(wrapper.optString(targetHash));
                if (packageRuleJson.isEmpty()) {
                    continue;
                }
                JSONObject packageRuleObj = new JSONObject(sanitizeJson(packageRuleJson));
                JSONArray popupArray = packageRuleObj.optJSONArray("popup_rules");
                if (popupArray == null || popupArray.length() == 0) {
                    return Collections.emptyList();
                }
                List<PopupRule> out = new ArrayList<PopupRule>(popupArray.length());
                for (int r = 0; r < popupArray.length(); r++) {
                    JSONObject item = popupArray.optJSONObject(r);
                    if (item == null) {
                        continue;
                    }
                    String id = safeTrim(item.optString("id"));
                    String action = safeTrim(item.optString("action"));
                    if (id.isEmpty()) {
                        continue;
                    }
                    int delay = item.optInt("delay_popup", 0);
                    out.add(new PopupRule(id, action, delay));
                }
                log(service, "加载规则: package=" + packageName + " count=" + out.size());
                return out;
            }
        } catch (Throwable e) {
            log(service, "解析 CustomRules.ini 失败: " + e);
        }
        log(service, "未命中包名规则: " + packageName + " hash=" + targetHash);
        return Collections.emptyList();
    }

    private static String sanitizeJson(String json) {
        String s = safeTrim(json);
        if (s.isEmpty()) {
            return s;
        }
        s = s.replaceAll(",\\s*\\]", "]");
        s = s.replaceAll(",\\s*\\}", "}");
        return s;
    }

    private static String readCustomRulesText(AccessibilityService service) {
        String external = readFileSafely(new File(UiComponentConfig.CUSTOM_RULES_EXTERNAL_PATH));
        if (!TextUtils.isEmpty(external)) {
            return external;
        }
        InputStream in = null;
        try {
            in = service.getAssets().open(UiComponentConfig.CUSTOM_RULES_ASSET_NAME);
            return readStream(in);
        } catch (Throwable e) {
            log(service, "读取 assets/CustomRules.ini 失败: " + e);
            return "";
        } finally {
            closeQuietly(in);
        }
    }

    private static String readFileSafely(File file) {
        if (file == null || !file.exists() || !file.isFile()) {
            return "";
        }
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            return readStream(in);
        } catch (Throwable e) {
            log(null, "读取外部 CustomRules.ini 失败: " + e);
            return "";
        } finally {
            closeQuietly(in);
        }
    }

    private static String readStream(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int len;
        while ((len = in.read(buffer)) != -1) {
            out.write(buffer, 0, len);
        }
        return out.toString(StandardCharsets.UTF_8.name());
    }

    private static void closeQuietly(InputStream in) {
        if (in == null) {
            return;
        }
        try {
            in.close();
        } catch (Throwable ignore) {
        }
    }

    private static String safeTrim(CharSequence s) {
        if (s == null) {
            return "";
        }
        return s.toString().trim();
    }

    private void log(String msg) {
        log(service, msg);
    }

    private static void log(AccessibilityService service, String msg) {
        String line = LOG_PREFIX + " " + safeTrim(msg);
        try {
            Log.i(TAG, line);
        } catch (Throwable ignore) {
        }
        try {
            ModuleRunFileLogger.appendLine(service, line);
        } catch (Throwable ignore) {
        }
    }

    private static final class NodeRef {
        final AccessibilityNodeInfo node;
        final List<String> candidates;

        NodeRef(AccessibilityNodeInfo node, List<String> candidates) {
            this.node = node;
            this.candidates = candidates == null ? Collections.<String>emptyList() : candidates;
        }
    }

    private static final class PopupRule {
        final String idPattern;
        final String actionPattern;
        final int delayPopupMs;
        long lastHandleAt;
        long lastFailLogAt;
        long lastMissLogAt;

        PopupRule(String idPattern, String actionPattern, int delayPopupMs) {
            this.idPattern = idPattern;
            this.actionPattern = actionPattern;
            this.delayPopupMs = Math.max(delayPopupMs, 0);
            this.lastHandleAt = 0L;
            this.lastFailLogAt = 0L;
            this.lastMissLogAt = 0L;
        }
    }
}
