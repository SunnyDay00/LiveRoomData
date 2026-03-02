package com.oodbye.looklspmodule;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

public class LookAccessibilityAdService extends AccessibilityService {
    private static final String TAG = "LOOKA11yAd";
    private static final String LOG_PREFIX = "[LOOKA11yAd]";
    private static final long PANEL_SNAPSHOT_LOG_INTERVAL_MS = 3000L;

    private Handler handler;
    private AccessibilityCustomRulesAdEngine adEngine;
    private boolean loopStarted;
    private long lastPanelSnapshotUpdateAt;
    private long lastPanelSnapshotPersistAt;
    private long lastPanelSnapshotLogAt;
    private int lastPanelMarkerCount = -1;
    private int lastPanelPrimaryCount = -1;
    private String lastPanelDetail = "";
    private BroadcastReceiver panelClickRequestReceiver;
    private boolean panelClickRequestReceiverRegistered;
    private final Runnable realtimeLoopTask = new Runnable() {
        @Override
        public void run() {
            if (!loopStarted) {
                return;
            }
            tryScanAndHandle();
            handler.postDelayed(this, UiComponentConfig.AD_REALTIME_LOOP_INTERVAL_MS);
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        handler = new Handler(Looper.getMainLooper());
        ModuleSettings.ensureDefaults(this);
        resetPanelSnapshot("service_connected");
        ensurePanelClickRequestReceiver();
        adEngine = new AccessibilityCustomRulesAdEngine(this);
        configureServiceInfo();
        startRealtimeLoop();
        log("无障碍广告服务已连接");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }
        CharSequence packageName = event.getPackageName();
        if (!UiComponentConfig.TARGET_PACKAGE.equals(safeTrim(packageName))) {
            return;
        }
        tryScanAndHandle();
    }

    @Override
    public void onInterrupt() {
        log("无障碍广告服务被中断");
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        stopRealtimeLoop();
        unregisterPanelClickRequestReceiver();
        resetPanelSnapshot("service_unbind");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        stopRealtimeLoop();
        unregisterPanelClickRequestReceiver();
        resetPanelSnapshot("service_destroy");
        super.onDestroy();
        log("无障碍广告服务已销毁");
    }

    private void configureServiceInfo() {
        try {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info == null) {
                return;
            }
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                    | AccessibilityEvent.TYPE_VIEW_CLICKED
                    | AccessibilityEvent.TYPE_WINDOWS_CHANGED;
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
            info.notificationTimeout = 40L;
            info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            }
            info.packageNames = new String[] { UiComponentConfig.TARGET_PACKAGE };
            setServiceInfo(info);
        } catch (Throwable e) {
            log("配置无障碍服务失败: " + e);
        }
    }

    private void startRealtimeLoop() {
        if (loopStarted) {
            return;
        }
        loopStarted = true;
        if (handler != null) {
            handler.post(realtimeLoopTask);
        }
    }

    private void stopRealtimeLoop() {
        loopStarted = false;
        if (handler != null) {
            handler.removeCallbacks(realtimeLoopTask);
        }
    }

    private void tryScanAndHandle() {
        long nowMs = System.currentTimeMillis();
        updatePanelSnapshotIfNeeded(nowMs);
        if (!isAdHandlingEnabled()) {
            return;
        }
        if (adEngine == null) {
            adEngine = new AccessibilityCustomRulesAdEngine(this);
        }
        adEngine.scanAndHandleIfNeeded(nowMs);
    }

    private boolean isAdHandlingEnabled() {
        ModuleSettings.ensureDefaults(this);
        android.content.SharedPreferences prefs = ModuleSettings.appPrefs(this);
        if (!ModuleSettings.getAdProcessEnabled(prefs)) {
            return false;
        }
        return ModuleSettings.getAccessibilityAdServiceEnabled(prefs);
    }

    private void ensurePanelClickRequestReceiver() {
        if (panelClickRequestReceiverRegistered) {
            return;
        }
        panelClickRequestReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context context, Intent intent) {
                if (intent == null) {
                    return;
                }
                String action = safeTrim(intent.getAction());
                if (!ModuleSettings.ACTION_A11Y_PANEL_CLICK_REQUEST.equals(action)) {
                    return;
                }
                handlePanelClickRequest(intent);
            }
        };
        IntentFilter filter = new IntentFilter(ModuleSettings.ACTION_A11Y_PANEL_CLICK_REQUEST);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(panelClickRequestReceiver, filter, RECEIVER_EXPORTED);
            } else {
                registerReceiver(panelClickRequestReceiver, filter);
            }
            panelClickRequestReceiverRegistered = true;
        } catch (Throwable e) {
            panelClickRequestReceiverRegistered = false;
            log("注册面板点击请求接收器失败: " + e);
        }
    }

    private void unregisterPanelClickRequestReceiver() {
        if (!panelClickRequestReceiverRegistered || panelClickRequestReceiver == null) {
            panelClickRequestReceiver = null;
            panelClickRequestReceiverRegistered = false;
            return;
        }
        try {
            unregisterReceiver(panelClickRequestReceiver);
        } catch (Throwable ignore) {
        }
        panelClickRequestReceiver = null;
        panelClickRequestReceiverRegistered = false;
    }

    private void handlePanelClickRequest(Intent intent) {
        long requestId = intent.getLongExtra(ModuleSettings.EXTRA_A11Y_PANEL_CLICK_REQUEST_ID, -1L);
        String target = safeTrim(intent.getStringExtra(ModuleSettings.EXTRA_A11Y_PANEL_CLICK_TARGET));
        if (requestId <= 0L) {
            log("忽略面板点击请求：requestId无效 target=" + target);
            return;
        }
        List<UiComponentConfig.UiNodeSpec> specs = resolvePanelClickSpecs(target);
        if (specs.isEmpty()) {
            dispatchPanelClickResult(requestId, target, false, "unsupported_target");
            return;
        }
        AccessibilityNodeInfo rootRaw = null;
        AccessibilityNodeInfo root = null;
        AccessibilityNodeInfo targetNode = null;
        try {
            rootRaw = getRootInActiveWindow();
            if (rootRaw == null) {
                dispatchPanelClickResult(requestId, target, false, "root_missing");
                return;
            }
            String rootPkg = safeTrim(rootRaw.getPackageName());
            if (!UiComponentConfig.TARGET_PACKAGE.equals(rootPkg)) {
                dispatchPanelClickResult(
                        requestId,
                        target,
                        false,
                        "root_pkg_mismatch:" + rootPkg
                );
                return;
            }
            root = AccessibilityNodeInfo.obtain(rootRaw);
            targetNode = findFirstAccessibilityNodeFromCandidates(root, specs);
            if (targetNode == null) {
                dispatchPanelClickResult(requestId, target, false, "target_missing");
                return;
            }
            ClickResult clickResult = clickAccessibilityNodeWithParentFallback(
                    targetNode,
                    "panel_" + target
            );
            dispatchPanelClickResult(requestId, target, clickResult.success, clickResult.detail);
        } catch (Throwable e) {
            dispatchPanelClickResult(requestId, target, false, "exception:" + safeTrim(e.toString()));
        } finally {
            safeRecycle(targetNode);
            safeRecycle(root);
            safeRecycle(rootRaw);
        }
    }

    private List<UiComponentConfig.UiNodeSpec> resolvePanelClickSpecs(String target) {
        if (ModuleSettings.A11Y_PANEL_CLICK_TARGET_CONTRIBUTION.equals(target)) {
            return UiComponentConfig.LIVE_ROOM_TASK_CONTRIBUTION_TAB_CANDIDATE_NODES;
        }
        if (ModuleSettings.A11Y_PANEL_CLICK_TARGET_CHARM.equals(target)) {
            return UiComponentConfig.LIVE_ROOM_TASK_CHARM_TAB_CANDIDATE_NODES;
        }
        return new ArrayList<UiComponentConfig.UiNodeSpec>(0);
    }

    private void resetPanelSnapshot(String reason) {
        long nowMs = System.currentTimeMillis();
        String detail = "reset=" + safeTrim(reason);
        lastPanelSnapshotUpdateAt = nowMs;
        lastPanelSnapshotPersistAt = nowMs;
        lastPanelSnapshotLogAt = nowMs;
        lastPanelMarkerCount = 0;
        lastPanelPrimaryCount = 0;
        lastPanelDetail = detail;
        try {
            ModuleSettings.updateA11yPanelSnapshot(this, 0, 0, nowMs, detail);
            dispatchPanelSnapshotBroadcast(0, 0, nowMs, detail);
        } catch (Throwable e) {
            log("重置面板快照失败: " + e);
        }
    }

    private void updatePanelSnapshotIfNeeded(long nowMs) {
        long minIntervalMs = Math.max(
                80L,
                UiComponentConfig.A11Y_PANEL_SNAPSHOT_MIN_UPDATE_INTERVAL_MS
        );
        if (nowMs - lastPanelSnapshotUpdateAt < minIntervalMs) {
            return;
        }
        lastPanelSnapshotUpdateAt = nowMs;
        AccessibilityNodeInfo rawRoot = null;
        try {
            rawRoot = getRootInActiveWindow();
            if (rawRoot == null) {
                return;
            }
            String rootPkg = safeTrim(rawRoot.getPackageName());
            if (!UiComponentConfig.TARGET_PACKAGE.equals(rootPkg)) {
                return;
            }
            AccessibilityNodeInfo root = AccessibilityNodeInfo.obtain(rawRoot);
            List<A11yNodeSnapshot> snapshots = flattenNodeSnapshots(root);
            int markerCount = countMatchedSpecs(
                    snapshots,
                    UiComponentConfig.LIVE_ROOM_TASK_PANEL_OPEN_A11Y_NODES
            );
            int primaryCount = countMatchedSpecs(
                    snapshots,
                    UiComponentConfig.LIVE_ROOM_TASK_PANEL_OPEN_PRIMARY_NODES
            );
            String detail = buildPanelSnapshotDetail(snapshots);
            persistPanelSnapshotIfNeeded(nowMs, markerCount, primaryCount, detail);
        } catch (Throwable e) {
            if (nowMs - lastPanelSnapshotLogAt >= PANEL_SNAPSHOT_LOG_INTERVAL_MS) {
                lastPanelSnapshotLogAt = nowMs;
                log("更新面板快照异常: " + e);
            }
        } finally {
            safeRecycle(rawRoot);
        }
    }

    private void persistPanelSnapshotIfNeeded(
            long nowMs,
            int markerCount,
            int primaryCount,
            String detail
    ) {
        int safeMarker = Math.max(0, markerCount);
        int safePrimary = Math.max(0, primaryCount);
        String safeDetail = safeTrim(detail);
        boolean changed = safeMarker != lastPanelMarkerCount
                || safePrimary != lastPanelPrimaryCount
                || !TextUtils.equals(safeDetail, lastPanelDetail);
        long maxWriteInterval = Math.max(
                UiComponentConfig.A11Y_PANEL_SNAPSHOT_MIN_UPDATE_INTERVAL_MS,
                UiComponentConfig.A11Y_PANEL_SNAPSHOT_MAX_WRITE_INTERVAL_MS
        );
        boolean intervalElapsed = nowMs - lastPanelSnapshotPersistAt >= maxWriteInterval;
        if (!changed && !intervalElapsed) {
            return;
        }
        ModuleSettings.updateA11yPanelSnapshot(this, safeMarker, safePrimary, nowMs, safeDetail);
        dispatchPanelSnapshotBroadcast(safeMarker, safePrimary, nowMs, safeDetail);
        lastPanelSnapshotPersistAt = nowMs;
        lastPanelMarkerCount = safeMarker;
        lastPanelPrimaryCount = safePrimary;
        lastPanelDetail = safeDetail;
        if (changed || nowMs - lastPanelSnapshotLogAt >= PANEL_SNAPSHOT_LOG_INTERVAL_MS) {
            lastPanelSnapshotLogAt = nowMs;
            log("面板快照更新: marker=" + safeMarker
                    + " primary=" + safePrimary
                    + " detail=" + safeDetail);
        }
    }

    private static List<A11yNodeSnapshot> flattenNodeSnapshots(AccessibilityNodeInfo root) {
        if (root == null) {
            return new ArrayList<A11yNodeSnapshot>(0);
        }
        ArrayDeque<AccessibilityNodeInfo> stack = new ArrayDeque<AccessibilityNodeInfo>();
        ArrayList<A11yNodeSnapshot> out = new ArrayList<A11yNodeSnapshot>(256);
        stack.push(root);
        while (!stack.isEmpty()) {
            AccessibilityNodeInfo current = stack.pop();
            if (current == null) {
                continue;
            }
            out.add(A11yNodeSnapshot.fromNode(current));
            int childCount = Math.max(0, current.getChildCount());
            for (int i = childCount - 1; i >= 0; i--) {
                AccessibilityNodeInfo child = null;
                try {
                    child = current.getChild(i);
                } catch (Throwable ignore) {
                    child = null;
                }
                if (child != null) {
                    stack.push(child);
                }
            }
            safeRecycle(current);
        }
        return out;
    }

    private static int countMatchedSpecs(
            List<A11yNodeSnapshot> snapshots,
            List<UiComponentConfig.UiNodeSpec> specs
    ) {
        if (snapshots == null || snapshots.isEmpty() || specs == null || specs.isEmpty()) {
            return 0;
        }
        int matched = 0;
        for (UiComponentConfig.UiNodeSpec spec : specs) {
            if (spec == null) {
                continue;
            }
            if (containsMatchedSnapshot(snapshots, spec)) {
                matched++;
            }
        }
        return matched;
    }

    private static boolean containsMatchedSnapshot(
            List<A11yNodeSnapshot> snapshots,
            UiComponentConfig.UiNodeSpec spec
    ) {
        if (snapshots == null || snapshots.isEmpty() || spec == null) {
            return false;
        }
        for (A11yNodeSnapshot snapshot : snapshots) {
            if (matchesNodeSpec(snapshot, spec)) {
                return true;
            }
        }
        return false;
    }

    private static String buildPanelSnapshotDetail(List<A11yNodeSnapshot> snapshots) {
        boolean currentMatched = containsMatchedSnapshot(
                snapshots,
                UiComponentConfig.LIVE_ROOM_TASK_PANEL_CURRENT_ROOM_DESC_NODE
        ) || containsMatchedSnapshot(
                snapshots,
                UiComponentConfig.LIVE_ROOM_TASK_PANEL_CURRENT_ROOM_NODE
        );
        boolean contributionMatched = containsMatchedSnapshot(
                snapshots,
                UiComponentConfig.LIVE_ROOM_TASK_PANEL_CONTRIBUTION_DESC_NODE
        ) || containsMatchedSnapshot(
                snapshots,
                UiComponentConfig.LIVE_ROOM_TASK_PANEL_CONTRIBUTION_NODE
        );
        boolean charmMatched = containsMatchedSnapshot(
                snapshots,
                UiComponentConfig.LIVE_ROOM_TASK_PANEL_CHARM_DESC_NODE
        ) || containsMatchedSnapshot(
                snapshots,
                UiComponentConfig.LIVE_ROOM_TASK_PANEL_CHARM_NODE
        );
        return "current=" + currentMatched
                + ", contribution=" + contributionMatched
                + ", charm=" + charmMatched;
    }

    private static boolean matchesNodeSpec(A11yNodeSnapshot node, UiComponentConfig.UiNodeSpec spec) {
        if (node == null || spec == null) {
            return false;
        }
        String expectClass = safeTrim(spec.className);
        if (!TextUtils.isEmpty(expectClass) && !isClassNameMatched(node.className, expectClass)) {
            return false;
        }
        String expectPackage = safeTrim(spec.packageName);
        if (!TextUtils.isEmpty(expectPackage) && !expectPackage.equals(node.packageName)) {
            return false;
        }
        if (spec.selected != null && node.selected != spec.selected.booleanValue()) {
            return false;
        }
        String expectFullId = safeTrim(spec.fullResourceId);
        if (!TextUtils.isEmpty(expectFullId)) {
            if (TextUtils.isEmpty(node.viewIdResourceName)) {
                return false;
            }
            if (!expectFullId.equals(node.viewIdResourceName)) {
                return false;
            }
        }
        String expectText = safeTrim(spec.text);
        if (!TextUtils.isEmpty(expectText) && !expectText.equals(node.text)) {
            return false;
        }
        String expectDesc = safeTrim(spec.contentDesc);
        if (!TextUtils.isEmpty(expectDesc) && !expectDesc.equals(node.contentDesc)) {
            return false;
        }
        return true;
    }

    private static boolean isClassNameMatched(String actualClassName, String expectedClassName) {
        String actual = safeTrim(actualClassName);
        String expected = safeTrim(expectedClassName);
        if (TextUtils.isEmpty(actual) || TextUtils.isEmpty(expected)) {
            return false;
        }
        if (actual.equals(expected)) {
            return true;
        }
        int dot = expected.lastIndexOf('.');
        if (dot >= 0 && dot < expected.length() - 1) {
            String expectedSimple = expected.substring(dot + 1);
            if (actual.endsWith("." + expectedSimple) || actual.equals(expectedSimple)) {
                return true;
            }
        }
        return false;
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

    private AccessibilityNodeInfo findFirstAccessibilityNodeFromCandidates(
            AccessibilityNodeInfo root,
            List<UiComponentConfig.UiNodeSpec> specs
    ) {
        if (root == null || specs == null || specs.isEmpty()) {
            return null;
        }
        for (UiComponentConfig.UiNodeSpec spec : specs) {
            if (spec == null) {
                continue;
            }
            AccessibilityNodeInfo node = findFirstAccessibilityNode(root, spec);
            if (node != null) {
                return node;
            }
        }
        return null;
    }

    private AccessibilityNodeInfo findFirstAccessibilityNode(
            AccessibilityNodeInfo root,
            UiComponentConfig.UiNodeSpec spec
    ) {
        if (root == null || spec == null) {
            return null;
        }
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<AccessibilityNodeInfo>();
        ArrayDeque<AccessibilityNodeInfo> allNodes = new ArrayDeque<AccessibilityNodeInfo>();
        queue.offer(AccessibilityNodeInfo.obtain(root));
        allNodes.offer(queue.peek());
        AccessibilityNodeInfo result = null;
        try {
            while (!queue.isEmpty()) {
                AccessibilityNodeInfo current = queue.poll();
                if (current == null) {
                    continue;
                }
                if (matchesNodeSpec(current, spec)) {
                    result = AccessibilityNodeInfo.obtain(current);
                    break;
                }
                int childCount = Math.max(0, current.getChildCount());
                for (int i = 0; i < childCount; i++) {
                    AccessibilityNodeInfo child = null;
                    try {
                        child = current.getChild(i);
                    } catch (Throwable ignore) {
                        child = null;
                    }
                    if (child != null) {
                        queue.offer(child);
                        allNodes.offer(child);
                    }
                }
            }
        } finally {
            while (!allNodes.isEmpty()) {
                safeRecycle(allNodes.poll());
            }
        }
        return result;
    }

    private boolean matchesNodeSpec(AccessibilityNodeInfo node, UiComponentConfig.UiNodeSpec spec) {
        if (node == null || spec == null) {
            return false;
        }
        String expectClass = safeTrim(spec.className);
        if (!TextUtils.isEmpty(expectClass)) {
            String actualClass = safeTrim(node.getClassName());
            if (!isClassNameMatched(actualClass, expectClass)) {
                return false;
            }
        }
        String expectPackage = safeTrim(spec.packageName);
        if (!TextUtils.isEmpty(expectPackage)) {
            String actualPkg = safeTrim(node.getPackageName());
            if (!expectPackage.equals(actualPkg)) {
                return false;
            }
        }
        if (spec.selected != null) {
            boolean selected = false;
            try {
                selected = node.isSelected();
            } catch (Throwable ignore) {
                selected = false;
            }
            if (selected != spec.selected.booleanValue()) {
                return false;
            }
        }
        String expectFullId = safeTrim(spec.fullResourceId);
        if (!TextUtils.isEmpty(expectFullId)) {
            String actualFullId = "";
            try {
                actualFullId = safeTrim(node.getViewIdResourceName());
            } catch (Throwable ignore) {
                actualFullId = "";
            }
            if (!expectFullId.equals(actualFullId)) {
                return false;
            }
        }
        String expectText = safeTrim(spec.text);
        if (!TextUtils.isEmpty(expectText)) {
            String actualText = "";
            try {
                actualText = safeTrim(node.getText());
            } catch (Throwable ignore) {
                actualText = "";
            }
            if (!expectText.equals(actualText)) {
                return false;
            }
        }
        String expectDesc = safeTrim(spec.contentDesc);
        if (!TextUtils.isEmpty(expectDesc)) {
            String actualDesc = "";
            try {
                actualDesc = safeTrim(node.getContentDescription());
            } catch (Throwable ignore) {
                actualDesc = "";
            }
            if (!expectDesc.equals(actualDesc)) {
                return false;
            }
        }
        return true;
    }

    private ClickResult clickAccessibilityNodeWithParentFallback(
            AccessibilityNodeInfo start,
            String clickName
    ) {
        if (start == null) {
            return new ClickResult(false, clickName + " a11y_start_null");
        }
        AccessibilityNodeInfo current = AccessibilityNodeInfo.obtain(start);
        int depth = 0;
        try {
            while (current != null && depth < 6) {
                boolean clicked = false;
                try {
                    clicked = current.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                } catch (Throwable ignore) {
                }
                if (clicked) {
                    return new ClickResult(true, clickName + " via=a11y_click depth=" + depth);
                }
                AccessibilityNodeInfo parent = null;
                try {
                    parent = current.getParent();
                } catch (Throwable ignore) {
                    parent = null;
                }
                current.recycle();
                current = parent;
                depth++;
            }
        } finally {
            safeRecycle(current);
        }
        return new ClickResult(false, clickName + " a11y_click_failed");
    }

    private void dispatchPanelClickResult(
            long requestId,
            String target,
            boolean success,
            String detail
    ) {
        try {
            Intent intent = new Intent(ModuleSettings.ACTION_A11Y_PANEL_CLICK_RESULT);
            intent.setPackage(UiComponentConfig.TARGET_PACKAGE);
            intent.putExtra(
                    ModuleSettings.EXTRA_A11Y_PANEL_CLICK_REQUEST_ID,
                    Math.max(0L, requestId)
            );
            intent.putExtra(
                    ModuleSettings.EXTRA_A11Y_PANEL_CLICK_TARGET,
                    safeTrim(target)
            );
            intent.putExtra(
                    ModuleSettings.EXTRA_A11Y_PANEL_CLICK_SUCCESS,
                    success
            );
            intent.putExtra(
                    ModuleSettings.EXTRA_A11Y_PANEL_CLICK_DETAIL,
                    safeTrim(detail)
            );
            sendBroadcast(intent);
            log("面板点击结果: requestId=" + requestId
                    + " target=" + safeTrim(target)
                    + " success=" + success
                    + " detail=" + safeTrim(detail));
        } catch (Throwable e) {
            log("发送面板点击结果失败: " + e);
        }
    }

    private void dispatchPanelSnapshotBroadcast(
            int markerCount,
            int primaryCount,
            long updatedAt,
            String detail
    ) {
        try {
            Intent intent = new Intent(ModuleSettings.ACTION_A11Y_PANEL_SNAPSHOT);
            intent.setPackage(UiComponentConfig.TARGET_PACKAGE);
            intent.putExtra(
                    ModuleSettings.EXTRA_A11Y_PANEL_MARKER_COUNT,
                    Math.max(0, markerCount)
            );
            intent.putExtra(
                    ModuleSettings.EXTRA_A11Y_PANEL_PRIMARY_COUNT,
                    Math.max(0, primaryCount)
            );
            intent.putExtra(
                    ModuleSettings.EXTRA_A11Y_PANEL_UPDATED_AT,
                    Math.max(0L, updatedAt)
            );
            intent.putExtra(
                    ModuleSettings.EXTRA_A11Y_PANEL_DETAIL,
                    safeTrim(detail)
            );
            sendBroadcast(intent);
        } catch (Throwable e) {
            if (System.currentTimeMillis() - lastPanelSnapshotLogAt >= PANEL_SNAPSHOT_LOG_INTERVAL_MS) {
                lastPanelSnapshotLogAt = System.currentTimeMillis();
                log("发送面板快照广播失败: " + e);
            }
        }
    }

    private static String safeTrim(CharSequence value) {
        if (value == null) {
            return "";
        }
        return value.toString().trim();
    }

    private static final class ClickResult {
        final boolean success;
        final String detail;

        ClickResult(boolean success, String detail) {
            this.success = success;
            this.detail = safeTrim(detail);
        }
    }

    private static final class A11yNodeSnapshot {
        final String text;
        final String contentDesc;
        final String viewIdResourceName;
        final String className;
        final String packageName;
        final boolean selected;

        A11yNodeSnapshot(
                String text,
                String contentDesc,
                String viewIdResourceName,
                String className,
                String packageName,
                boolean selected
        ) {
            this.text = safeTrim(text);
            this.contentDesc = safeTrim(contentDesc);
            this.viewIdResourceName = safeTrim(viewIdResourceName);
            this.className = safeTrim(className);
            this.packageName = safeTrim(packageName);
            this.selected = selected;
        }

        static A11yNodeSnapshot fromNode(AccessibilityNodeInfo node) {
            if (node == null) {
                return new A11yNodeSnapshot("", "", "", "", "", false);
            }
            String text = "";
            String contentDesc = "";
            String viewId = "";
            String className = "";
            String packageName = "";
            boolean selected = false;
            try {
                text = safeTrim(node.getText());
            } catch (Throwable ignore) {
                text = "";
            }
            try {
                contentDesc = safeTrim(node.getContentDescription());
            } catch (Throwable ignore) {
                contentDesc = "";
            }
            try {
                viewId = safeTrim(node.getViewIdResourceName());
            } catch (Throwable ignore) {
                viewId = "";
            }
            try {
                className = safeTrim(node.getClassName());
            } catch (Throwable ignore) {
                className = "";
            }
            try {
                packageName = safeTrim(node.getPackageName());
            } catch (Throwable ignore) {
                packageName = "";
            }
            try {
                selected = node.isSelected();
            } catch (Throwable ignore) {
                selected = false;
            }
            return new A11yNodeSnapshot(
                    text,
                    contentDesc,
                    viewId,
                    className,
                    packageName,
                    selected
            );
        }
    }

    private void log(String msg) {
        String line = LOG_PREFIX + " " + safeTrim(msg);
        try {
            Log.i(TAG, line);
        } catch (Throwable ignore) {
        }
        try {
            ModuleRunFileLogger.appendLine(this, line);
        } catch (Throwable ignore) {
        }
    }
}
