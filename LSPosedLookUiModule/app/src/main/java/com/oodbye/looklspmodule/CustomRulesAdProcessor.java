package com.oodbye.looklspmodule;

import android.app.Activity;
import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

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

import de.robv.android.xposed.XposedBridge;

final class CustomRulesAdProcessor {
    private static final String TAG = "[LOOKAdRules]";
    private static final String ALL_PATTERN_PREFIX = "ALL:";
    private static final Map<String, List<PopupRule>> RULE_CACHE = new ConcurrentHashMap<String, List<PopupRule>>();

    private final Activity activity;
    private final List<PopupRule> popupRules;
    private long lastScanAt;

    CustomRulesAdProcessor(Activity activity) {
        this.activity = activity;
        this.popupRules = loadRulesForTargetPackage(activity);
    }

    boolean scanAndHandleIfNeeded(long nowMs) {
        if (nowMs - lastScanAt < UiComponentConfig.AD_SCAN_INTERVAL_MS) {
            return false;
        }
        lastScanAt = nowMs;
        if (popupRules.isEmpty()) {
            return false;
        }
        View root = getRootView();
        if (root == null) {
            return false;
        }
        List<View> nodes = flattenViews(root);
        if (nodes.isEmpty()) {
            return false;
        }
        for (PopupRule rule : popupRules) {
            if (rule == null) {
                continue;
            }
            if (rule.delayPopupMs > 0 && nowMs - rule.lastHandleAt < rule.delayPopupMs) {
                continue;
            }
            View trigger = findTriggerViewByRulePattern(nodes, rule.idPattern);
            if (trigger == null) {
                continue;
            }
            if (executeAction(rule.actionPattern, nodes, trigger)) {
                rule.lastHandleAt = nowMs;
                log("命中规则: id=" + rule.idPattern + " action=" + rule.actionPattern);
                return true;
            }
        }
        return false;
    }

    private boolean executeAction(String actionPattern, List<View> nodes, View triggerView) {
        if (TextUtils.isEmpty(actionPattern)) {
            return clickWithParentFallback(triggerView);
        }
        if ("GLOBAL_ACTION_BACK".equalsIgnoreCase(actionPattern)) {
            try {
                activity.onBackPressed();
                return true;
            } catch (Throwable ignore) {
                return false;
            }
        }
        View actionView = findViewByPattern(nodes, actionPattern);
        if (actionView != null && clickWithParentFallback(actionView)) {
            return true;
        }
        if (clickWithParentFallback(triggerView)) {
            return true;
        }
        for (String fallbackAction : UiComponentConfig.COMMON_CLOSE_ACTIONS) {
            View fallbackView = findViewByPattern(nodes, fallbackAction);
            if (fallbackView != null && clickWithParentFallback(fallbackView)) {
                return true;
            }
        }
        return false;
    }

    private View getRootView() {
        try {
            if (activity.getWindow() == null) {
                return null;
            }
            View decor = activity.getWindow().getDecorView();
            if (decor == null) {
                return null;
            }
            return decor.getRootView();
        } catch (Throwable ignore) {
            return null;
        }
    }

    private static List<View> flattenViews(View root) {
        if (root == null) {
            return Collections.emptyList();
        }
        List<View> out = new ArrayList<View>(256);
        ArrayDeque<View> stack = new ArrayDeque<View>();
        stack.push(root);
        while (!stack.isEmpty()) {
            View node = stack.pop();
            out.add(node);
            if (!(node instanceof ViewGroup)) {
                continue;
            }
            ViewGroup group = (ViewGroup) node;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                View child = group.getChildAt(i);
                if (child != null) {
                    stack.push(child);
                }
            }
        }
        return out;
    }

    private static View findViewByPattern(List<View> nodes, String pattern) {
        if (nodes == null || nodes.isEmpty()) {
            return null;
        }
        String normalized = safeTrim(pattern);
        if (normalized.isEmpty()) {
            return null;
        }
        for (View node : nodes) {
            if (node == null) {
                continue;
            }
            if (matchesPattern(node, normalized)) {
                return node;
            }
        }
        return null;
    }

    private static View findTriggerViewByRulePattern(List<View> nodes, String rulePattern) {
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
        return findViewByPattern(nodes, normalized);
    }

    private static View findTriggerByAllConditions(List<View> nodes, String allPattern) {
        if (nodes == null || nodes.isEmpty()) {
            return null;
        }
        if (TextUtils.isEmpty(allPattern)) {
            return null;
        }
        String[] conditions = allPattern.split("&");
        View trigger = null;
        for (String condition : conditions) {
            String token = safeTrim(condition);
            if (token.isEmpty()) {
                continue;
            }
            View matched = findViewByPattern(nodes, token);
            if (matched == null) {
                return null;
            }
            if (trigger == null) {
                trigger = matched;
            }
        }
        return trigger;
    }

    private static boolean matchesPattern(View view, String rawPattern) {
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
            if (!matchTokenOnView(view, token, exact)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchTokenOnView(View view, String token, boolean exact) {
        List<String> candidates = collectCandidates(view);
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

    private static List<String> collectCandidates(View view) {
        List<String> out = new ArrayList<String>(4);
        CharSequence desc = view.getContentDescription();
        if (!TextUtils.isEmpty(desc)) {
            out.add(desc.toString());
        }
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (!TextUtils.isEmpty(text)) {
                out.add(text.toString());
            }
        }
        int id = view.getId();
        if (id != View.NO_ID) {
            try {
                String entry = view.getResources().getResourceEntryName(id);
                if (!TextUtils.isEmpty(entry)) {
                    out.add(entry);
                }
            } catch (Throwable ignore) {
            }
            try {
                String fullName = view.getResources().getResourceName(id);
                if (!TextUtils.isEmpty(fullName)) {
                    out.add(fullName);
                }
            } catch (Throwable ignore) {
            }
        }
        out.add(view.getClass().getSimpleName());
        return out;
    }

    private static boolean clickWithParentFallback(View start) {
        View current = start;
        int depth = 0;
        while (current != null && depth < 4) {
            if (current.isShown() && current.isClickable()) {
                try {
                    return current.performClick();
                } catch (Throwable ignore) {
                    return false;
                }
            }
            if (!(current.getParent() instanceof View)) {
                break;
            }
            current = (View) current.getParent();
            depth++;
        }
        return false;
    }

    private static List<PopupRule> loadRulesForTargetPackage(Context context) {
        if (context == null) {
            return Collections.emptyList();
        }
        String packageName = UiComponentConfig.TARGET_PACKAGE;
        List<PopupRule> cache = RULE_CACHE.get(packageName);
        if (cache != null) {
            return cache;
        }
        List<PopupRule> loaded = parsePopupRules(context, packageName);
        RULE_CACHE.put(packageName, loaded);
        return loaded;
    }

    private static List<PopupRule> parsePopupRules(Context context, String packageName) {
        String rawJson = readCustomRulesText(context);
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
                log(context, "加载规则: package=" + packageName + " count=" + out.size());
                return out;
            }
        } catch (Throwable e) {
            log(context, "解析 CustomRules.ini 失败: " + e);
        }
        log(context, "未命中包名规则: " + packageName + " hash=" + targetHash);
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

    private static String readCustomRulesText(Context context) {
        String external = readFileSafely(new File(UiComponentConfig.CUSTOM_RULES_EXTERNAL_PATH));
        if (!TextUtils.isEmpty(external)) {
            return external;
        }

        String moduleAsset = readModuleAssetSafely(context);
        if (!TextUtils.isEmpty(moduleAsset)) {
            return moduleAsset;
        }

        InputStream in = null;
        try {
            in = context.getAssets().open(UiComponentConfig.CUSTOM_RULES_ASSET_NAME);
            return readStream(in);
        } catch (Throwable e) {
            log(context, "读取目标进程 assets/CustomRules.ini 失败: " + e);
            return "";
        } finally {
            closeQuietly(in);
        }
    }

    private static String readModuleAssetSafely(Context context) {
        if (context == null) {
            return "";
        }
        InputStream in = null;
        try {
            Context moduleContext = context.createPackageContext(
                    ModuleSettings.MODULE_PACKAGE,
                    Context.CONTEXT_IGNORE_SECURITY
            );
            in = moduleContext.getAssets().open(UiComponentConfig.CUSTOM_RULES_ASSET_NAME);
            return readStream(in);
        } catch (Throwable e) {
            log(context, "读取模块 assets/CustomRules.ini 失败: " + e);
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

    private static String safeTrim(String s) {
        if (s == null) {
            return "";
        }
        return s.trim();
    }

    private void log(String msg) {
        log(activity, msg);
    }

    private static void log(Context context, String msg) {
        String line = TAG + " " + safeTrim(msg);
        try {
            XposedBridge.log(line);
        } catch (Throwable ignore) {
        }
        ModuleRunFileLogger.appendLine(context, line);
    }

    private static final class PopupRule {
        final String idPattern;
        final String actionPattern;
        final int delayPopupMs;
        long lastHandleAt;

        PopupRule(String idPattern, String actionPattern, int delayPopupMs) {
            this.idPattern = idPattern;
            this.actionPattern = actionPattern;
            this.delayPopupMs = Math.max(delayPopupMs, 0);
            this.lastHandleAt = 0L;
        }
    }
}
