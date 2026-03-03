package com.oodbye.looklspmodule;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
    private final Object rankCollectLock = new Object();
    private long activeRankCollectRequestId = -1L;
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
                if (ModuleSettings.ACTION_A11Y_PANEL_CLICK_REQUEST.equals(action)) {
                    handlePanelClickRequest(intent);
                    return;
                }
                if (ModuleSettings.ACTION_A11Y_RANK_COLLECT_REQUEST.equals(action)) {
                    handleRankCollectRequest(intent);
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(ModuleSettings.ACTION_A11Y_PANEL_CLICK_REQUEST);
        filter.addAction(ModuleSettings.ACTION_A11Y_RANK_COLLECT_REQUEST);
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
        if (!waitSlidingContainerGone("panel_click_" + target)) {
            dispatchPanelClickResult(
                    requestId,
                    target,
                    false,
                    "sliding_container_block_timeout"
            );
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

    private void handleRankCollectRequest(Intent intent) {
        long requestId = intent.getLongExtra(
                ModuleSettings.EXTRA_A11Y_RANK_COLLECT_REQUEST_ID,
                -1L
        );
        String rankType = safeTrim(intent.getStringExtra(ModuleSettings.EXTRA_A11Y_RANK_COLLECT_TYPE));
        String homeId = safeTrim(intent.getStringExtra(ModuleSettings.EXTRA_A11Y_RANK_COLLECT_HOME_ID));
        long enterTimeMs = intent.getLongExtra(
                ModuleSettings.EXTRA_A11Y_RANK_COLLECT_ENTER_TIME,
                0L
        );
        int targetCount = Math.max(
                0,
                intent.getIntExtra(ModuleSettings.EXTRA_A11Y_RANK_COLLECT_TARGET_COUNT, 0)
        );
        if (requestId <= 0L) {
            log("忽略榜单采集请求：requestId无效 type=" + rankType);
            return;
        }
        if (!isSupportedRankCollectType(rankType)) {
            dispatchRankCollectResult(
                    requestId,
                    rankType,
                    false,
                    "unsupported_type",
                    0,
                    0
            );
            return;
        }
        synchronized (rankCollectLock) {
            if (activeRankCollectRequestId > 0L) {
                dispatchRankCollectResult(
                        requestId,
                        rankType,
                        false,
                        "collector_busy(requestId=" + activeRankCollectRequestId + ")",
                        0,
                        0
                );
                return;
            }
            activeRankCollectRequestId = requestId;
        }
        final long finalRequestId = requestId;
        final String finalRankType = rankType;
        final String finalHomeId = homeId;
        final long finalEnterTimeMs = Math.max(0L, enterTimeMs);
        final int finalTargetCount = targetCount;
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    RankCollectSummary summary = executeRankCollectRequest(
                            finalRequestId,
                            finalRankType,
                            finalHomeId,
                            finalEnterTimeMs,
                            finalTargetCount
                    );
                    dispatchRankCollectResult(
                            finalRequestId,
                            finalRankType,
                            summary.success,
                            summary.detail,
                            summary.count,
                            summary.maxRank
                    );
                } catch (Throwable e) {
                    dispatchRankCollectResult(
                            finalRequestId,
                            finalRankType,
                            false,
                            "collector_exception:" + safeTrim(e.toString()),
                            0,
                            0
                    );
                } finally {
                    synchronized (rankCollectLock) {
                        if (activeRankCollectRequestId == finalRequestId) {
                            activeRankCollectRequestId = -1L;
                        }
                    }
                }
            }
        }, "look_rank_collect_" + requestId);
        worker.start();
    }

    private RankCollectSummary executeRankCollectRequest(
            long requestId,
            String rankType,
            String homeId,
            long enterTimeMs,
            int targetCount
    ) {
        String type = safeTrim(rankType);
        int safeTargetCount = Math.max(0, targetCount);
        if (safeTargetCount <= 0) {
            return new RankCollectSummary(
                    true,
                    "target_count_zero",
                    0,
                    0
            );
        }
        if (!isEngineRunningForRankCollect()) {
            return new RankCollectSummary(
                    false,
                    "engine_not_running",
                    0,
                    0
            );
        }
        String csvPath = "";
        if (isContributionCollectType(type)) {
            csvPath = LiveRoomRankCsvStore.getContributionCsvPath(this);
        } else if (isCharmCollectType(type)) {
            csvPath = LiveRoomRankCsvStore.getCharmCsvPath(this);
        }
        log("开始榜单采集: requestId=" + requestId
                + " type=" + type
                + " targetCount=" + safeTargetCount
                + " homeId=" + homeId
                + " enterTime=" + enterTimeMs
                + " csv=" + safeTrim(csvPath));
        RankCollectSummary summary = collectRankRows(type, homeId, enterTimeMs, safeTargetCount);
        String detail = safeTrim(summary.detail);
        if (TextUtils.isEmpty(detail)) {
            detail = "done";
        }
        if (!TextUtils.isEmpty(csvPath)) {
            detail = detail + "|csv=" + csvPath;
        }
        return new RankCollectSummary(summary.success, detail, summary.count, summary.maxRank);
    }

    private RankCollectSummary collectRankRows(
            String rankType,
            String homeId,
            long enterTimeMs,
            int targetCount
    ) {
        int safeTargetCount = Math.max(0, targetCount);
        if (safeTargetCount <= 0) {
            return new RankCollectSummary(true, "target_count_zero", 0, 0);
        }
        int nextRank = 1;
        int collectedCount = 0;
        int maxCollectedRank = 0;
        int scrollCount = 0;
        int noProgressScrollCount = 0;
        int highestObservedRank = 0;
        int rootMissingRetry = 0;
        int rankPageNotReadyRetry = 0;
        int clickFailedRetry = 0;
        boolean rankPageOpenedLogged = false;
        boolean collectDetailEnabled = ModuleSettings.getCollectUserDetailEnabled(
                ModuleSettings.appPrefs(this)
        );
        boolean success = true;
        String failDetail = "";
        Set<Integer> doneRanks = new HashSet<Integer>();
        List<LiveRoomRankCsvStore.ContributionCsvRow> contributionRows =
                new ArrayList<LiveRoomRankCsvStore.ContributionCsvRow>();
        List<LiveRoomRankCsvStore.CharmCsvRow> charmRows =
                new ArrayList<LiveRoomRankCsvStore.CharmCsvRow>();
        while (nextRank <= safeTargetCount) {
            if (!isEngineRunningForRankCollect()) {
                success = false;
                failDetail = "engine_stopped";
                break;
            }
            if (!waitSlidingContainerGone("rank_collect_loop_" + nextRank)) {
                success = false;
                failDetail = "sliding_container_block_timeout";
                break;
            }
            AccessibilityNodeInfo root = obtainTargetRoot();
            if (root == null) {
                rootMissingRetry++;
                if (rootMissingRetry >= 10) {
                    success = false;
                    failDetail = "root_missing";
                    break;
                }
                SystemClock.sleep(UiComponentConfig.LIVE_ROOM_TASK_RANK_DETAIL_READY_RETRY_INTERVAL_MS);
                continue;
            }
            rootMissingRetry = 0;
            if (!isRankPageReady(root, rankType)) {
                rankPageNotReadyRetry++;
                int maxRetry = Math.max(1, UiComponentConfig.LIVE_ROOM_TASK_RANK_PAGE_READY_MAX_RETRY);
                String pageDetail = buildRankPageMatchDetail(root, rankType);
                if (rankPageNotReadyRetry <= 2 || rankPageNotReadyRetry % 4 == 0) {
                    log("榜单界面未就绪: type=" + safeTrim(rankType)
                            + " retry=" + rankPageNotReadyRetry + "/" + maxRetry
                            + " detail=" + pageDetail);
                }
                safeRecycle(root);
                if (rankPageNotReadyRetry >= maxRetry) {
                    success = false;
                    failDetail = "rank_page_not_ready(" + pageDetail + ")";
                    break;
                }
                SystemClock.sleep(UiComponentConfig.LIVE_ROOM_TASK_RANK_PAGE_READY_RETRY_INTERVAL_MS);
                continue;
            }
            rankPageNotReadyRetry = 0;
            if (!rankPageOpenedLogged) {
                String pageTypeLabel = isContributionCollectType(rankType) ? "贡献榜" : "魅力榜";
                String pageDetail = buildRankPageMatchDetail(root, rankType);
                log(pageTypeLabel + "界面检测已打开: detail=" + pageDetail);
                rankPageOpenedLogged = true;
            }
            RankScanResult scanResult;
            try {
                scanResult = scanRankRows(root, rankType, nextRank);
            } finally {
                safeRecycle(root);
            }
            highestObservedRank = Math.max(highestObservedRank, scanResult.maxRank);
            if (scanResult.match != null && scanResult.match.row != null && scanResult.match.node != null) {
                RankRowData row = scanResult.match.row;
                AccessibilityNodeInfo rowNode = scanResult.match.node;
                if (doneRanks.contains(row.rank)) {
                    nextRank = row.rank + 1;
                    safeRecycle(rowNode);
                    continue;
                }
                DetailCollectData detailData;
                if (collectDetailEnabled) {
                    ClickResult clickResult = clickAccessibilityNodeWithParentFallback(
                            rowNode,
                            "rank_row_" + row.rank
                    );
                    safeRecycle(rowNode);
                    if (!clickResult.success) {
                        clickFailedRetry++;
                        if (clickFailedRetry >= 3) {
                            success = false;
                            failDetail = "rank_row_click_failed(rank=" + row.rank + ")";
                            break;
                        }
                        SystemClock.sleep(UiComponentConfig.LIVE_ROOM_TASK_RANK_DETAIL_READY_RETRY_INTERVAL_MS);
                        continue;
                    }
                    clickFailedRetry = 0;
                    SystemClock.sleep(UiComponentConfig.LIVE_ROOM_TASK_RANK_DETAIL_WAIT_AFTER_CLICK_MS);
                    detailData = collectDetailData(rankType);
                    if (detailData.enteredDetail) {
                        performGlobalAction(GLOBAL_ACTION_BACK);
                        SystemClock.sleep(UiComponentConfig.LIVE_ROOM_TASK_RANK_BACK_TO_LIST_WAIT_MS);
                    }
                } else {
                    safeRecycle(rowNode);
                    detailData = new DetailCollectData(false, "", "", "", "");
                }
                boolean queued = queueRankRow(
                        rankType,
                        homeId,
                        row,
                        detailData,
                        enterTimeMs,
                        contributionRows,
                        charmRows
                );
                if (!queued) {
                    log("榜单采集入队失败: type=" + safeTrim(rankType) + " rank=" + row.rank);
                }
                doneRanks.add(row.rank);
                collectedCount++;
                maxCollectedRank = Math.max(maxCollectedRank, row.rank);
                nextRank = row.rank + 1;
                noProgressScrollCount = 0;
                log("榜单采集完成一条: type=" + safeTrim(rankType)
                        + " rank=" + row.rank
                        + " name=" + safeTrim(row.name)
                        + " data=" + safeTrim(row.dataValue)
                        + " id=" + safeTrim(detailData.userId)
                        + " detailMode=" + (collectDetailEnabled ? "detail" : "list"));
                continue;
            }
            if (!success) {
                break;
            }
            if (scrollCount >= UiComponentConfig.LIVE_ROOM_TASK_RANK_SCROLL_MAX_COUNT
                    || noProgressScrollCount >= UiComponentConfig.LIVE_ROOM_TASK_RANK_SCROLL_NO_PROGRESS_MAX_COUNT) {
                log("榜单采集停止上滑: type=" + safeTrim(rankType)
                        + " reason=scroll_limit"
                        + " nextRank=" + nextRank
                        + " scrollCount=" + scrollCount
                        + " noProgress=" + noProgressScrollCount
                        + " maxObserved=" + highestObservedRank);
                break;
            }
            int before = highestObservedRank;
            log("榜单采集准备上滑: type=" + safeTrim(rankType)
                    + " nextRank=" + nextRank
                    + " maxRankBefore=" + before
                    + " scrollCount=" + scrollCount
                    + " noProgress=" + noProgressScrollCount);
            AccessibilityNodeInfo scrollRoot = obtainTargetRoot();
            boolean scrolled = false;
            try {
                scrolled = performRankListScroll(scrollRoot, rankType, nextRank);
            } finally {
                safeRecycle(scrollRoot);
            }
            scrollCount++;
            SystemClock.sleep(UiComponentConfig.LIVE_ROOM_TASK_RANK_SCROLL_WAIT_MS);
            int after = before;
            AccessibilityNodeInfo rootAfter = obtainTargetRoot();
            if (rootAfter != null) {
                try {
                    after = Math.max(after, scanRankRows(rootAfter, rankType, -1).maxRank);
                } finally {
                    safeRecycle(rootAfter);
                }
            }
            highestObservedRank = Math.max(highestObservedRank, after);
            if (!scrolled || after <= before) {
                noProgressScrollCount++;
            } else {
                noProgressScrollCount = 0;
            }
            log("榜单采集上滑: type=" + safeTrim(rankType)
                    + " nextRank=" + nextRank
                    + " scrollCount=" + scrollCount
                    + " noProgress=" + noProgressScrollCount
                    + " maxRankBefore=" + before
                    + " maxRankAfter=" + after
                    + " scrolled=" + scrolled);
        }
        if (success && safeTargetCount > 0 && collectedCount <= 0) {
            success = false;
            failDetail = "rank_no_data_collected";
        }
        int flushedCount = flushQueuedRankRows(rankType, contributionRows, charmRows);
        boolean flushOk = flushedCount >= collectedCount;
        log("榜单采集写入汇总: type=" + safeTrim(rankType)
                + " queued=" + collectedCount
                + " flushed=" + flushedCount
                + " flushOk=" + flushOk
                + " detailMode=" + (collectDetailEnabled ? "detail" : "list"));
        String detail = (success ? "collect_done" : safeTrim(failDetail)) + "(target=" + safeTargetCount
                + ",count=" + collectedCount
                + ",flushed=" + flushedCount
                + ",maxRank=" + maxCollectedRank
                + ",highestObserved=" + highestObservedRank + ")";
        return new RankCollectSummary(success && flushOk, detail, collectedCount, maxCollectedRank);
    }

    private DetailCollectData collectDetailData(String rankType) {
        int maxRetry = Math.max(1, UiComponentConfig.LIVE_ROOM_TASK_RANK_DETAIL_READY_MAX_RETRY);
        int invalidDataRetryCount = 0;
        int maxInvalidDataRetry = Math.max(
                1,
                UiComponentConfig.LIVE_ROOM_TASK_RANK_DETAIL_DATA_RETRY_MAX_COUNT
        );
        DetailCollectData lastInvalidData = null;
        for (int i = 0; i < maxRetry; i++) {
            if (!waitSlidingContainerGone("detail_collect_" + (i + 1))) {
                break;
            }
            AccessibilityNodeInfo root = obtainTargetRoot();
            if (root == null) {
                SystemClock.sleep(UiComponentConfig.LIVE_ROOM_TASK_RANK_DETAIL_READY_RETRY_INTERVAL_MS);
                continue;
            }
            try {
                AccessibilityNodeInfo idNode = findFirstAccessibilityNode(
                        root,
                        UiComponentConfig.LIVE_ROOM_TASK_DETAIL_ID_NODE
                );
                AccessibilityNodeInfo artistNode = findFirstAccessibilityNode(
                        root,
                        UiComponentConfig.LIVE_ROOM_TASK_DETAIL_ARTIST_NAME_NODE
                );
                String userId = readNodeText(idNode);
                boolean ready = idNode != null && artistNode != null;
                safeRecycle(idNode);
                safeRecycle(artistNode);
                if (!ready) {
                    SystemClock.sleep(UiComponentConfig.LIVE_ROOM_TASK_RANK_DETAIL_READY_RETRY_INTERVAL_MS);
                    continue;
                }
                String ip = findNumByLabel(
                        root,
                        UiComponentConfig.LIVE_ROOM_TASK_DETAIL_LABEL_IP_REGION
                );
                String consumption = findNumByLabel(
                        root,
                        UiComponentConfig.LIVE_ROOM_TASK_DETAIL_LABEL_CONSUMPTION
                );
                String followers = "";
                if (isCharmCollectType(rankType)) {
                    followers = findNumByLabel(
                            root,
                            UiComponentConfig.LIVE_ROOM_TASK_DETAIL_LABEL_FOLLOWERS
                    );
                }
                DetailCollectData detailData = new DetailCollectData(
                        true,
                        userId,
                        ip,
                        consumption,
                        followers
                );
                if (isDetailDataValid(detailData, rankType)) {
                    return detailData;
                }
                lastInvalidData = detailData;
                invalidDataRetryCount++;
                if (invalidDataRetryCount >= maxInvalidDataRetry) {
                    return lastInvalidData;
                }
                log("用户详情数据无效，等待重试: attempt="
                        + invalidDataRetryCount + "/" + maxInvalidDataRetry
                        + " id=" + safeTrim(userId));
                SystemClock.sleep(UiComponentConfig.LIVE_ROOM_TASK_RANK_DETAIL_DATA_RETRY_WAIT_MS);
            } finally {
                safeRecycle(root);
            }
        }
        if (lastInvalidData != null) {
            return lastInvalidData;
        }
        return new DetailCollectData(false, "", "", "", "");
    }

    private boolean isDetailDataValid(DetailCollectData detailData, String rankType) {
        if (detailData == null || !detailData.enteredDetail) {
            return false;
        }
        if (!TextUtils.isEmpty(detailData.userId)) {
            return true;
        }
        if (!TextUtils.isEmpty(detailData.ip) || !TextUtils.isEmpty(detailData.consumption)) {
            return true;
        }
        return isCharmCollectType(rankType) && !TextUtils.isEmpty(detailData.followers);
    }

    private String findNumByLabel(AccessibilityNodeInfo root, String label) {
        if (root == null || TextUtils.isEmpty(safeTrim(label))) {
            return "";
        }
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<AccessibilityNodeInfo>();
        ArrayDeque<AccessibilityNodeInfo> allNodes = new ArrayDeque<AccessibilityNodeInfo>();
        AccessibilityNodeInfo rootCopy = AccessibilityNodeInfo.obtain(root);
        queue.offer(rootCopy);
        allNodes.offer(rootCopy);
        String targetLabel = normalizeLabelText(label);
        try {
            while (!queue.isEmpty()) {
                AccessibilityNodeInfo current = queue.poll();
                if (current == null) {
                    continue;
                }
                String text = safeTrim(current.getText());
                if (!TextUtils.isEmpty(text) && normalizeLabelText(text).contains(targetLabel)) {
                    AccessibilityNodeInfo parent = null;
                    try {
                        parent = current.getParent();
                        String value = findFirstNumValueInSubtree(parent);
                        if (!TextUtils.isEmpty(value)) {
                            safeRecycle(parent);
                            return value;
                        }
                    } catch (Throwable ignore) {
                    } finally {
                        safeRecycle(parent);
                    }
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
        return "";
    }

    private String findFirstNumValueInSubtree(AccessibilityNodeInfo root) {
        if (root == null) {
            return "";
        }
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<AccessibilityNodeInfo>();
        ArrayDeque<AccessibilityNodeInfo> allNodes = new ArrayDeque<AccessibilityNodeInfo>();
        AccessibilityNodeInfo rootCopy = AccessibilityNodeInfo.obtain(root);
        queue.offer(rootCopy);
        allNodes.offer(rootCopy);
        try {
            while (!queue.isEmpty()) {
                AccessibilityNodeInfo current = queue.poll();
                if (current == null) {
                    continue;
                }
                if (matchesNodeSpec(current, UiComponentConfig.LIVE_ROOM_TASK_DETAIL_NUM_NODE)) {
                    String value = readNodeText(current);
                    if (!TextUtils.isEmpty(value)) {
                        return value;
                    }
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
        return "";
    }

    private RankScanResult scanRankRows(
            AccessibilityNodeInfo root,
            String rankType,
            int expectedRank
    ) {
        if (root == null) {
            return new RankScanResult(null, 0);
        }
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<AccessibilityNodeInfo>();
        ArrayDeque<AccessibilityNodeInfo> allNodes = new ArrayDeque<AccessibilityNodeInfo>();
        AccessibilityNodeInfo rootCopy = AccessibilityNodeInfo.obtain(root);
        queue.offer(rootCopy);
        allNodes.offer(rootCopy);
        RankRowMatch matched = null;
        int maxRank = 0;
        try {
            while (!queue.isEmpty()) {
                AccessibilityNodeInfo current = queue.poll();
                if (current == null) {
                    continue;
                }
                if (isRankRowCandidateNode(current)) {
                    RankRowData rowData = parseRankRowData(current, rankType);
                    if (rowData != null && rowData.rank > 0) {
                        maxRank = Math.max(maxRank, rowData.rank);
                        if (expectedRank > 0 && rowData.rank == expectedRank && matched == null) {
                            matched = new RankRowMatch(
                                    AccessibilityNodeInfo.obtain(current),
                                    rowData
                            );
                        }
                    }
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
        return new RankScanResult(matched, maxRank);
    }

    private boolean isRankRowCandidateNode(AccessibilityNodeInfo node) {
        if (node == null) {
            return false;
        }
        if (!matchesNodeSpec(node, UiComponentConfig.LIVE_ROOM_TASK_RANK_ROW_NODE)) {
            return false;
        }
        boolean clickable = false;
        try {
            clickable = node.isClickable();
        } catch (Throwable ignore) {
            clickable = false;
        }
        if (!clickable) {
            return false;
        }
        return isNodeVisible(node);
    }

    private RankRowData parseRankRowData(AccessibilityNodeInfo rowNode, String rankType) {
        if (rowNode == null) {
            return null;
        }
        List<String> texts = collectNodeTexts(
                rowNode,
                UiComponentConfig.LIVE_ROOM_TASK_RANK_ROW_MAX_TEXT_SCAN
        );
        if (texts.isEmpty()) {
            return null;
        }
        int rank = parseRankValue(pickByIndex(texts, 0));
        if (rank <= 0) {
            rank = parseFirstRankFromTexts(texts);
        }
        if (rank <= 0) {
            return null;
        }
        if (isContributionCollectType(rankType)) {
            String level = pickByIndex(texts, UiComponentConfig.LIVE_ROOM_TASK_CONTRIBUTION_ROW_LEVEL_TEXT_INDEX);
            String name = pickByIndex(texts, UiComponentConfig.LIVE_ROOM_TASK_CONTRIBUTION_ROW_NAME_TEXT_INDEX);
            String dataValue = pickByIndex(texts, UiComponentConfig.LIVE_ROOM_TASK_CONTRIBUTION_ROW_DATA_TEXT_INDEX);
            if (TextUtils.isEmpty(name)) {
                name = findFirstNonNumericText(texts, 1);
            }
            if (TextUtils.isEmpty(dataValue)) {
                dataValue = findLastDataLikeText(texts);
            }
            return new RankRowData(rank, level, name, dataValue);
        }
        String name = pickByIndex(texts, UiComponentConfig.LIVE_ROOM_TASK_CHARM_ROW_NAME_TEXT_INDEX);
        String dataValue = pickByIndex(texts, UiComponentConfig.LIVE_ROOM_TASK_CHARM_ROW_DATA_TEXT_INDEX);
        if (TextUtils.isEmpty(name)) {
            name = findFirstNonNumericText(texts, 1);
        }
        if (TextUtils.isEmpty(dataValue)) {
            dataValue = findLastDataLikeText(texts);
        }
        return new RankRowData(rank, "", name, dataValue);
    }

    private List<String> collectNodeTexts(AccessibilityNodeInfo root, int maxCount) {
        if (root == null || maxCount <= 0) {
            return new ArrayList<String>(0);
        }
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<AccessibilityNodeInfo>();
        ArrayDeque<AccessibilityNodeInfo> allNodes = new ArrayDeque<AccessibilityNodeInfo>();
        AccessibilityNodeInfo rootCopy = AccessibilityNodeInfo.obtain(root);
        queue.offer(rootCopy);
        allNodes.offer(rootCopy);
        ArrayList<String> out = new ArrayList<String>(maxCount);
        try {
            while (!queue.isEmpty() && out.size() < maxCount) {
                AccessibilityNodeInfo current = queue.poll();
                if (current == null) {
                    continue;
                }
                String text = readNodeText(current);
                if (!TextUtils.isEmpty(text) && !out.contains(text)) {
                    out.add(text);
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
        return out;
    }

    private int parseFirstRankFromTexts(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return 0;
        }
        for (String text : texts) {
            int rank = parseRankValue(text);
            if (rank > 0) {
                return rank;
            }
        }
        return 0;
    }

    private int parseRankValue(String raw) {
        String value = safeTrim(raw);
        if (TextUtils.isEmpty(value)) {
            return 0;
        }
        if (!value.matches("^\\d{1,4}$")) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (Throwable ignore) {
            return 0;
        }
    }

    private String pickByIndex(List<String> values, int index) {
        if (values == null || values.isEmpty() || index < 0 || index >= values.size()) {
            return "";
        }
        return safeTrim(values.get(index));
    }

    private String findFirstNonNumericText(List<String> values, int startIndex) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        int start = Math.max(0, startIndex);
        for (int i = start; i < values.size(); i++) {
            String value = safeTrim(values.get(i));
            if (TextUtils.isEmpty(value)) {
                continue;
            }
            if (!value.matches("^[\\d.]+$")) {
                return value;
            }
        }
        return "";
    }

    private String findLastDataLikeText(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        for (int i = values.size() - 1; i >= 0; i--) {
            String value = safeTrim(values.get(i));
            if (TextUtils.isEmpty(value)) {
                continue;
            }
            if (value.matches(".*\\d.*")) {
                return value;
            }
        }
        return "";
    }

    private boolean queueRankRow(
            String rankType,
            String homeId,
            RankRowData row,
            DetailCollectData detailData,
            long enterTimeMs,
            List<LiveRoomRankCsvStore.ContributionCsvRow> contributionRows,
            List<LiveRoomRankCsvStore.CharmCsvRow> charmRows
    ) {
        if (row == null) {
            return false;
        }
        if (isContributionCollectType(rankType)) {
            if (contributionRows == null) {
                return false;
            }
            contributionRows.add(new LiveRoomRankCsvStore.ContributionCsvRow(
                    homeId,
                    detailData == null ? "" : detailData.userId,
                    row.name,
                    row.level,
                    row.dataValue,
                    detailData == null ? "" : detailData.consumption,
                    detailData == null ? "" : detailData.ip,
                    enterTimeMs
            ));
            return true;
        }
        if (charmRows == null) {
            return false;
        }
        charmRows.add(new LiveRoomRankCsvStore.CharmCsvRow(
                homeId,
                detailData == null ? "" : detailData.userId,
                row.name,
                row.dataValue,
                detailData == null ? "" : detailData.followers,
                detailData == null ? "" : detailData.consumption,
                detailData == null ? "" : detailData.ip,
                enterTimeMs
        ));
        return true;
    }

    private int flushQueuedRankRows(
            String rankType,
            List<LiveRoomRankCsvStore.ContributionCsvRow> contributionRows,
            List<LiveRoomRankCsvStore.CharmCsvRow> charmRows
    ) {
        if (isContributionCollectType(rankType)) {
            return LiveRoomRankCsvStore.appendContributionRows(this, contributionRows);
        }
        return LiveRoomRankCsvStore.appendCharmRows(this, charmRows);
    }

    private boolean performRankListScroll(
            AccessibilityNodeInfo root,
            String rankType,
            int nextRank
    ) {
        ClickResult actionResult = performRankListScrollByAction(root);
        if (actionResult.success) {
            log("榜单上滑执行: mode=scroll_action type=" + safeTrim(rankType)
                    + " nextRank=" + nextRank
                    + " detail=" + safeTrim(actionResult.detail));
            return true;
        }
        log("榜单上滑动作失败，改用手势: type=" + safeTrim(rankType)
                + " nextRank=" + nextRank
                + " detail=" + safeTrim(actionResult.detail));
        return performRankListScrollGesture(root, rankType, nextRank);
    }

    private ClickResult performRankListScrollByAction(AccessibilityNodeInfo root) {
        if (root == null) {
            return new ClickResult(false, "root_missing");
        }
        AccessibilityNodeInfo scrollNode = findRankScrollableNode(root);
        if (scrollNode == null) {
            return new ClickResult(false, "scroll_node_missing");
        }
        boolean actionDone = false;
        String usedAction = "";
        try {
            if (hasAccessibilityAction(scrollNode, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                actionDone = scrollNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
                usedAction = "ACTION_SCROLL_FORWARD";
            }
            if (!actionDone) {
                actionDone = scrollNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
                if (actionDone) {
                    usedAction = "ACTION_SCROLL_FORWARD(force)";
                }
            }
            if (actionDone) {
                return new ClickResult(
                        true,
                        "node=" + describeNodeForLog(scrollNode) + " action=" + safeTrim(usedAction)
                );
            }
            return new ClickResult(
                    false,
                    "perform_action_false node=" + describeNodeForLog(scrollNode)
            );
        } catch (Throwable e) {
            return new ClickResult(
                    false,
                    "perform_action_exception:" + safeTrim(e.toString())
            );
        } finally {
            safeRecycle(scrollNode);
        }
    }

    private AccessibilityNodeInfo findRankScrollableNode(AccessibilityNodeInfo root) {
        if (root == null) {
            return null;
        }
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<AccessibilityNodeInfo>();
        ArrayDeque<AccessibilityNodeInfo> allNodes = new ArrayDeque<AccessibilityNodeInfo>();
        AccessibilityNodeInfo rootCopy = AccessibilityNodeInfo.obtain(root);
        queue.offer(rootCopy);
        allNodes.offer(rootCopy);
        AccessibilityNodeInfo bestNode = null;
        long bestScore = Long.MIN_VALUE;
        try {
            while (!queue.isEmpty()) {
                AccessibilityNodeInfo current = queue.poll();
                if (current == null) {
                    continue;
                }
                if (isNodeVisible(current) && supportsScrollAction(current)) {
                    Rect rect = new Rect();
                    current.getBoundsInScreen(rect);
                    long area = Math.max(0, rect.width()) * Math.max(0, rect.height());
                    String className = safeTrim(current.getClassName());
                    long bonus = 0L;
                    if (className.contains("RecyclerView")) {
                        bonus = 3_000_000L;
                    } else if (className.contains("ListView") || className.contains("ScrollView")) {
                        bonus = 2_000_000L;
                    }
                    long score = area + bonus;
                    if (score > bestScore) {
                        safeRecycle(bestNode);
                        bestNode = AccessibilityNodeInfo.obtain(current);
                        bestScore = score;
                    }
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
        return bestNode;
    }

    private boolean supportsScrollAction(AccessibilityNodeInfo node) {
        if (node == null) {
            return false;
        }
        boolean scrollable = false;
        try {
            scrollable = node.isScrollable();
        } catch (Throwable ignore) {
            scrollable = false;
        }
        if (scrollable) {
            return true;
        }
        return hasAccessibilityAction(node, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
    }

    private boolean hasAccessibilityAction(AccessibilityNodeInfo node, int actionId) {
        if (node == null || actionId <= 0) {
            return false;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                List<AccessibilityNodeInfo.AccessibilityAction> actions = node.getActionList();
                if (actions != null) {
                    for (AccessibilityNodeInfo.AccessibilityAction action : actions) {
                        if (action != null && action.getId() == actionId) {
                            return true;
                        }
                    }
                }
                return false;
            }
            return (node.getActions() & actionId) != 0;
        } catch (Throwable ignore) {
            return false;
        }
    }

    private boolean performRankListScrollGesture(
            AccessibilityNodeInfo root,
            String rankType,
            int nextRank
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            log("榜单上滑跳过: sdk<24");
            return false;
        }
        int width = getResources() == null ? 0 : getResources().getDisplayMetrics().widthPixels;
        int height = getResources() == null ? 0 : getResources().getDisplayMetrics().heightPixels;
        if (width <= 0 || height <= 0) {
            log("榜单上滑跳过: display_invalid width=" + width + " height=" + height);
            return false;
        }
        float x = clampFloat(
                width * UiComponentConfig.LIVE_ROOM_TASK_RANK_SCROLL_SWIPE_X_RATIO,
                1f,
                Math.max(1f, width - 1f)
        );
        float startY = clampFloat(
                height * UiComponentConfig.LIVE_ROOM_TASK_RANK_SCROLL_SWIPE_START_Y_RATIO,
                1f,
                Math.max(1f, height - 1f)
        );
        float endY = clampFloat(
                height * UiComponentConfig.LIVE_ROOM_TASK_RANK_SCROLL_SWIPE_END_Y_RATIO,
                1f,
                Math.max(1f, height - 1f)
        );
        Rect anchor = resolveRankScrollAnchorBounds(root);
        if (anchor != null && anchor.width() > 0 && anchor.height() > 0) {
            float anchorCenterX = anchor.exactCenterX();
            float anchorCenterY = anchor.exactCenterY();
            float anchorSpan = Math.max(height * 0.22f, anchor.height() * 0.85f);
            float anchorStartY = anchorCenterY + anchorSpan * 0.5f;
            float anchorEndY = anchorCenterY - anchorSpan * 0.5f;
            x = clampFloat(anchorCenterX, 1f, Math.max(1f, width - 1f));
            startY = clampFloat(anchorStartY, 1f, Math.max(1f, height - 1f));
            endY = clampFloat(anchorEndY, 1f, Math.max(1f, height - 1f));
            if (startY <= endY + 16f) {
                startY = clampFloat(
                        height * UiComponentConfig.LIVE_ROOM_TASK_RANK_SCROLL_SWIPE_START_Y_RATIO,
                        1f,
                        Math.max(1f, height - 1f)
                );
                endY = clampFloat(
                        height * UiComponentConfig.LIVE_ROOM_TASK_RANK_SCROLL_SWIPE_END_Y_RATIO,
                        1f,
                        Math.max(1f, height - 1f)
                );
            }
        }
        Path path = new Path();
        path.moveTo(x, startY);
        path.lineTo(x, endY);
        log("榜单上滑执行: mode=gesture type=" + safeTrim(rankType)
                + " nextRank=" + nextRank
                + " width=" + width
                + " height=" + height
                + " x=" + x
                + " startY=" + startY
                + " endY=" + endY
                + " anchor=" + (anchor == null ? "none"
                : ("[" + anchor.left + "," + anchor.top + "][" + anchor.right + "," + anchor.bottom + "]")));
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(
                        path,
                        0L,
                        Math.max(120L, UiComponentConfig.LIVE_ROOM_TASK_RANK_SCROLL_GESTURE_DURATION_MS)
                );
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(stroke)
                .build();
        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] completed = new boolean[] { false };
        boolean dispatched = false;
        try {
            dispatched = dispatchGesture(
                    gesture,
                    new GestureResultCallback() {
                        @Override
                        public void onCompleted(GestureDescription gestureDescription) {
                            completed[0] = true;
                            log("榜单上滑回调: completed");
                            latch.countDown();
                        }

                        @Override
                        public void onCancelled(GestureDescription gestureDescription) {
                            completed[0] = false;
                            log("榜单上滑回调: cancelled");
                            latch.countDown();
                        }
                    },
                    null
            );
        } catch (Throwable ignore) {
            dispatched = false;
        }
        if (!dispatched) {
            log("榜单上滑失败: dispatchGesture=false");
            return false;
        }
        boolean awaitDone = false;
        try {
            awaitDone = latch.await(
                    Math.max(
                            300L,
                            UiComponentConfig.LIVE_ROOM_TASK_RANK_SCROLL_GESTURE_WAIT_TIMEOUT_MS
                    ),
                    TimeUnit.MILLISECONDS
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log("榜单上滑失败: await_interrupted");
            return false;
        }
        if (!awaitDone) {
            log("榜单上滑失败: await_timeout");
            return false;
        }
        log("榜单上滑结果: success=" + completed[0]);
        return completed[0];
    }

    private Rect resolveRankScrollAnchorBounds(AccessibilityNodeInfo root) {
        if (root == null) {
            return null;
        }
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<AccessibilityNodeInfo>();
        ArrayDeque<AccessibilityNodeInfo> allNodes = new ArrayDeque<AccessibilityNodeInfo>();
        AccessibilityNodeInfo rootCopy = AccessibilityNodeInfo.obtain(root);
        queue.offer(rootCopy);
        allNodes.offer(rootCopy);
        Rect bestRect = null;
        int bestArea = -1;
        try {
            while (!queue.isEmpty()) {
                AccessibilityNodeInfo current = queue.poll();
                if (current == null) {
                    continue;
                }
                if (isRankRowCandidateNode(current)) {
                    Rect rect = new Rect();
                    current.getBoundsInScreen(rect);
                    int area = Math.max(0, rect.width()) * Math.max(0, rect.height());
                    if (area > bestArea) {
                        bestRect = new Rect(rect);
                        bestArea = area;
                    }
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
        return bestRect;
    }

    private String describeNodeForLog(AccessibilityNodeInfo node) {
        if (node == null) {
            return "null";
        }
        String cls = safeTrim(node.getClassName());
        String id = "";
        try {
            id = safeTrim(node.getViewIdResourceName());
        } catch (Throwable ignore) {
            id = "";
        }
        Rect rect = new Rect();
        try {
            node.getBoundsInScreen(rect);
        } catch (Throwable ignore) {
            rect.setEmpty();
        }
        return "class=" + cls
                + ",id=" + id
                + ",bounds=[" + rect.left + "," + rect.top + "][" + rect.right + "," + rect.bottom + "]";
    }

    private boolean waitSlidingContainerGone(String stage) {
        long timeoutMs = Math.max(
                UiComponentConfig.LIVE_ROOM_TASK_PANEL_DETECT_BLOCK_WAIT_INTERVAL_MS,
                UiComponentConfig.LIVE_ROOM_TASK_PANEL_DETECT_BLOCK_WAIT_TIMEOUT_MS
        );
        long intervalMs = Math.max(
                120L,
                UiComponentConfig.LIVE_ROOM_TASK_PANEL_DETECT_BLOCK_WAIT_INTERVAL_MS
        );
        long startAt = SystemClock.uptimeMillis();
        int checkCount = 0;
        while (isEngineRunningForRankCollect()) {
            checkCount++;
            AccessibilityNodeInfo root = obtainTargetRoot();
            boolean blocked = false;
            try {
                blocked = isSlidingContainerVisible(root);
            } finally {
                safeRecycle(root);
            }
            if (!blocked) {
                if (checkCount > 1) {
                    log("遮挡层已消失: stage=" + safeTrim(stage)
                            + " checks=" + checkCount
                            + " waited=" + Math.max(0L, SystemClock.uptimeMillis() - startAt) + "ms");
                }
                return true;
            }
            long elapsed = Math.max(0L, SystemClock.uptimeMillis() - startAt);
            if (checkCount <= 2 || checkCount % 6 == 0) {
                log("等待遮挡层消失: stage=" + safeTrim(stage)
                        + " check=" + checkCount
                        + " elapsed=" + elapsed + "ms"
                        + " blockId="
                        + safeTrim(UiComponentConfig.LIVE_ROOM_TASK_PANEL_DETECT_BLOCK_NODE.fullResourceId));
            }
            if (elapsed >= timeoutMs) {
                log("等待遮挡层超时: stage=" + safeTrim(stage)
                        + " elapsed=" + elapsed + "ms");
                return false;
            }
            SystemClock.sleep(intervalMs);
        }
        return false;
    }

    private boolean isSlidingContainerVisible(AccessibilityNodeInfo root) {
        if (root == null) {
            return false;
        }
        AccessibilityNodeInfo blockNode = null;
        try {
            blockNode = findFirstAccessibilityNode(
                    root,
                    UiComponentConfig.LIVE_ROOM_TASK_PANEL_DETECT_BLOCK_NODE
            );
            return blockNode != null && isNodeVisible(blockNode);
        } finally {
            safeRecycle(blockNode);
        }
    }

    private AccessibilityNodeInfo obtainTargetRoot() {
        AccessibilityNodeInfo root = null;
        try {
            root = getRootInActiveWindow();
        } catch (Throwable ignore) {
            root = null;
        }
        if (root == null) {
            return null;
        }
        String rootPkg = safeTrim(root.getPackageName());
        if (!UiComponentConfig.TARGET_PACKAGE.equals(rootPkg)) {
            safeRecycle(root);
            return null;
        }
        return root;
    }

    private boolean isNodeVisible(AccessibilityNodeInfo node) {
        if (node == null) {
            return false;
        }
        boolean visible = false;
        try {
            visible = node.isVisibleToUser();
        } catch (Throwable ignore) {
            visible = false;
        }
        if (!visible) {
            return false;
        }
        Rect rect = new Rect();
        try {
            node.getBoundsInScreen(rect);
        } catch (Throwable ignore) {
            return false;
        }
        return rect.width() > 0 && rect.height() > 0;
    }

    private String readNodeText(AccessibilityNodeInfo node) {
        if (node == null) {
            return "";
        }
        try {
            return safeTrim(node.getText());
        } catch (Throwable ignore) {
            return "";
        }
    }

    private String normalizeLabelText(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace(" ", "")
                .replace("\t", "")
                .replace("\n", "")
                .replace("\r", "")
                .trim();
    }

    private float clampFloat(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private boolean isSupportedRankCollectType(String rankType) {
        return isContributionCollectType(rankType) || isCharmCollectType(rankType);
    }

    private boolean isContributionCollectType(String rankType) {
        return ModuleSettings.A11Y_RANK_COLLECT_TYPE_CONTRIBUTION.equals(safeTrim(rankType));
    }

    private boolean isCharmCollectType(String rankType) {
        return ModuleSettings.A11Y_RANK_COLLECT_TYPE_CHARM.equals(safeTrim(rankType));
    }

    private boolean isRankPageReady(AccessibilityNodeInfo root, String rankType) {
        if (root == null) {
            return false;
        }
        if (isContributionCollectType(rankType)) {
            boolean reward = hasAnyNodeSpecInTree(
                    root,
                    UiComponentConfig.LIVE_ROOM_TASK_CONTRIBUTION_PAGE_REWARD_CANDIDATE_NODES
            );
            boolean day = hasAnyNodeSpecInTree(
                    root,
                    UiComponentConfig.LIVE_ROOM_TASK_CONTRIBUTION_PAGE_DAY_CANDIDATE_NODES
            );
            int matched = (reward ? 1 : 0) + (day ? 1 : 0);
            return matched >= Math.max(
                    1,
                    UiComponentConfig.LIVE_ROOM_TASK_CONTRIBUTION_PAGE_MARKER_GROUP_MIN_MATCH_COUNT
            );
        }
        boolean rule = hasAnyNodeSpecInTree(
                root,
                UiComponentConfig.LIVE_ROOM_TASK_CHARM_PAGE_RULE_CANDIDATE_NODES
        );
        boolean day = hasAnyNodeSpecInTree(
                root,
                UiComponentConfig.LIVE_ROOM_TASK_CHARM_PAGE_DAY_CANDIDATE_NODES
        );
        int matched = (rule ? 1 : 0) + (day ? 1 : 0);
        return matched >= Math.max(
                1,
                UiComponentConfig.LIVE_ROOM_TASK_CHARM_PAGE_MARKER_GROUP_MIN_MATCH_COUNT
        );
    }

    private String buildRankPageMatchDetail(AccessibilityNodeInfo root, String rankType) {
        if (root == null) {
            return "root_missing";
        }
        if (isContributionCollectType(rankType)) {
            boolean reward = hasAnyNodeSpecInTree(
                    root,
                    UiComponentConfig.LIVE_ROOM_TASK_CONTRIBUTION_PAGE_REWARD_CANDIDATE_NODES
            );
            boolean day = hasAnyNodeSpecInTree(
                    root,
                    UiComponentConfig.LIVE_ROOM_TASK_CONTRIBUTION_PAGE_DAY_CANDIDATE_NODES
            );
            int matched = (reward ? 1 : 0) + (day ? 1 : 0);
            return "reward=" + reward + ",day=" + day + ",matched=" + matched + "/2";
        }
        boolean rule = hasAnyNodeSpecInTree(
                root,
                UiComponentConfig.LIVE_ROOM_TASK_CHARM_PAGE_RULE_CANDIDATE_NODES
        );
        boolean day = hasAnyNodeSpecInTree(
                root,
                UiComponentConfig.LIVE_ROOM_TASK_CHARM_PAGE_DAY_CANDIDATE_NODES
        );
        int matched = (rule ? 1 : 0) + (day ? 1 : 0);
        return "rule=" + rule + ",day=" + day + ",matched=" + matched + "/2";
    }

    private boolean hasAnyNodeSpecInTree(
            AccessibilityNodeInfo root,
            List<UiComponentConfig.UiNodeSpec> specs
    ) {
        if (root == null || specs == null || specs.isEmpty()) {
            return false;
        }
        for (int i = 0; i < specs.size(); i++) {
            UiComponentConfig.UiNodeSpec spec = specs.get(i);
            if (hasNodeSpecInTree(root, spec)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasNodeSpecInTree(
            AccessibilityNodeInfo root,
            UiComponentConfig.UiNodeSpec spec
    ) {
        if (root == null || spec == null) {
            return false;
        }
        AccessibilityNodeInfo node = null;
        try {
            node = findFirstAccessibilityNode(root, spec);
            return node != null;
        } finally {
            safeRecycle(node);
        }
    }

    private boolean isEngineRunningForRankCollect() {
        try {
            return ModuleSettings.getEngineStatus() == ModuleSettings.EngineStatus.RUNNING;
        } catch (Throwable ignore) {
            return true;
        }
    }

    private void dispatchRankCollectResult(
            long requestId,
            String rankType,
            boolean success,
            String detail,
            int count,
            int maxRank
    ) {
        try {
            Intent intent = new Intent(ModuleSettings.ACTION_A11Y_RANK_COLLECT_RESULT);
            intent.setPackage(UiComponentConfig.TARGET_PACKAGE);
            intent.putExtra(
                    ModuleSettings.EXTRA_A11Y_RANK_COLLECT_REQUEST_ID,
                    Math.max(0L, requestId)
            );
            intent.putExtra(
                    ModuleSettings.EXTRA_A11Y_RANK_COLLECT_TYPE,
                    safeTrim(rankType)
            );
            intent.putExtra(
                    ModuleSettings.EXTRA_A11Y_RANK_COLLECT_SUCCESS,
                    success
            );
            intent.putExtra(
                    ModuleSettings.EXTRA_A11Y_RANK_COLLECT_DETAIL,
                    safeTrim(detail)
            );
            intent.putExtra(
                    ModuleSettings.EXTRA_A11Y_RANK_COLLECT_COUNT,
                    Math.max(0, count)
            );
            intent.putExtra(
                    ModuleSettings.EXTRA_A11Y_RANK_COLLECT_MAX_RANK,
                    Math.max(0, maxRank)
            );
            sendBroadcast(intent);
            log("榜单采集结果: requestId=" + requestId
                    + " type=" + safeTrim(rankType)
                    + " success=" + success
                    + " count=" + Math.max(0, count)
                    + " maxRank=" + Math.max(0, maxRank)
                    + " detail=" + safeTrim(detail));
        } catch (Throwable e) {
            log("发送榜单采集结果失败: " + e);
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

    private static final class RankCollectSummary {
        final boolean success;
        final String detail;
        final int count;
        final int maxRank;

        RankCollectSummary(boolean success, String detail, int count, int maxRank) {
            this.success = success;
            this.detail = safeTrim(detail);
            this.count = Math.max(0, count);
            this.maxRank = Math.max(0, maxRank);
        }
    }

    private static final class RankRowData {
        final int rank;
        final String level;
        final String name;
        final String dataValue;

        RankRowData(int rank, String level, String name, String dataValue) {
            this.rank = Math.max(0, rank);
            this.level = safeTrim(level);
            this.name = safeTrim(name);
            this.dataValue = safeTrim(dataValue);
        }
    }

    private static final class RankRowMatch {
        final AccessibilityNodeInfo node;
        final RankRowData row;

        RankRowMatch(AccessibilityNodeInfo node, RankRowData row) {
            this.node = node;
            this.row = row;
        }
    }

    private static final class RankScanResult {
        final RankRowMatch match;
        final int maxRank;

        RankScanResult(RankRowMatch match, int maxRank) {
            this.match = match;
            this.maxRank = Math.max(0, maxRank);
        }
    }

    private static final class DetailCollectData {
        final boolean enteredDetail;
        final String userId;
        final String ip;
        final String consumption;
        final String followers;

        DetailCollectData(
                boolean enteredDetail,
                String userId,
                String ip,
                String consumption,
                String followers
        ) {
            this.enteredDetail = enteredDetail;
            this.userId = safeTrim(userId);
            this.ip = safeTrim(ip);
            this.consumption = safeTrim(consumption);
            this.followers = safeTrim(followers);
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
