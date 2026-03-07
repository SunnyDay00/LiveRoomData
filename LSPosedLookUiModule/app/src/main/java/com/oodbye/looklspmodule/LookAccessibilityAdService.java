package com.oodbye.looklspmodule;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuService;
import rikka.shizuku.Shizuku;

public class LookAccessibilityAdService extends AccessibilityService {
    private static final String TAG = "LOOKA11yAd";
    private static final String LOG_PREFIX = "[LOOKA11yAd]";
    private static final long PANEL_SNAPSHOT_LOG_INTERVAL_MS = 3000L;
    private static final long SHIZUKU_PERMISSION_REQUEST_INTERVAL_MS = 30_000L;
    private static final int SHIZUKU_PERMISSION_REQUEST_CODE = 1201;
    private static final Pattern RANK_VALUE_LEADING_DIGITS_PATTERN =
            Pattern.compile("^(\\d{1,4})\\D*$");
    private static final Pattern RANK_DATA_NUMERIC_PATTERN =
            Pattern.compile("^(\\d+(?:\\.\\d+)?)([万亿wW]?)$");
    private static final Pattern RANK_DATA_NUMERIC_FALLBACK_PATTERN =
            Pattern.compile("(\\d+(?:\\.\\d+)?)([万亿wW]?)");
    private static final int RANK_COLLECT_SESSION_CACHE_MAX = 128;

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
    private final Object rankCollectSessionLock = new Object();
    private final LinkedHashMap<String, HashSet<Integer>> rankCollectedRanksBySession =
            new LinkedHashMap<String, HashSet<Integer>>();
    private long activeRankCollectRequestId = -1L;
    private Thread activeRankCollectWorker;
    private long lastShizukuPermissionRequestAt = 0L;
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
        cancelActiveRankCollect("service_unbind");
        resetPanelSnapshot("service_unbind");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        stopRealtimeLoop();
        unregisterPanelClickRequestReceiver();
        cancelActiveRankCollect("service_destroy");
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

    private void cancelActiveRankCollect(String reason) {
        long requestId;
        Thread worker;
        synchronized (rankCollectLock) {
            requestId = activeRankCollectRequestId;
            worker = activeRankCollectWorker;
            activeRankCollectRequestId = -1L;
            activeRankCollectWorker = null;
        }
        if (requestId > 0L) {
            log("取消榜单采集活动请求: reason=" + safeTrim(reason)
                    + " requestId=" + requestId);
        }
        if (worker != null && worker.isAlive()) {
            try {
                worker.interrupt();
            } catch (Throwable ignore) {
            }
        }
        synchronized (rankCollectSessionLock) {
            rankCollectedRanksBySession.clear();
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
        final long finalRequestId = requestId;
        final String finalRankType = rankType;
        final String finalHomeId = homeId;
        final long finalEnterTimeMs = Math.max(0L, enterTimeMs);
        final int finalTargetCount = targetCount;
        final long[] preemptedRequestHolder = new long[] { -1L };
        final Thread[] preemptedWorkerHolder = new Thread[] { null };
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!isRankCollectRequestActive(finalRequestId)) {
                        log("榜单采集请求启动前已失效: requestId=" + finalRequestId);
                        return;
                    }
                    RankCollectSummary summary = executeRankCollectRequest(
                            finalRequestId,
                            finalRankType,
                            finalHomeId,
                            finalEnterTimeMs,
                            finalTargetCount
                    );
                    if (!isRankCollectRequestActive(finalRequestId)) {
                        log("榜单采集请求已被接管，忽略旧结果: requestId=" + finalRequestId
                                + " detail=" + safeTrim(summary.detail));
                        return;
                    }
                    dispatchRankCollectResult(
                            finalRequestId,
                            finalRankType,
                            summary.success,
                            summary.detail,
                            summary.count,
                            summary.maxRank
                    );
                } catch (Throwable e) {
                    if (!isRankCollectRequestActive(finalRequestId)) {
                        log("榜单采集旧请求异常已忽略: requestId=" + finalRequestId
                                + " error=" + safeTrim(e.toString()));
                        return;
                    }
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
                        if (activeRankCollectWorker == Thread.currentThread()) {
                            activeRankCollectWorker = null;
                        }
                    }
                }
            }
        }, "look_rank_collect_" + requestId);
        synchronized (rankCollectLock) {
            if (activeRankCollectRequestId > 0L && activeRankCollectRequestId != requestId) {
                preemptedRequestHolder[0] = activeRankCollectRequestId;
                preemptedWorkerHolder[0] = activeRankCollectWorker;
            }
            activeRankCollectRequestId = requestId;
            activeRankCollectWorker = worker;
        }
        if (preemptedRequestHolder[0] > 0L) {
            log("榜单采集请求接管: previousRequestId=" + preemptedRequestHolder[0]
                    + " newRequestId=" + requestId
                    + " type=" + safeTrim(rankType));
            Thread preemptedWorker = preemptedWorkerHolder[0];
            if (preemptedWorker != null && preemptedWorker.isAlive()) {
                try {
                    preemptedWorker.interrupt();
                } catch (Throwable ignore) {
                }
            }
        }
        try {
            worker.start();
        } catch (Throwable e) {
            synchronized (rankCollectLock) {
                if (activeRankCollectRequestId == requestId) {
                    activeRankCollectRequestId = -1L;
                }
                if (activeRankCollectWorker == worker) {
                    activeRankCollectWorker = null;
                }
            }
            dispatchRankCollectResult(
                    requestId,
                    rankType,
                    false,
                    "collector_worker_start_failed:" + safeTrim(e.toString()),
                    0,
                    0
            );
        }
    }

    private RankCollectSummary executeRankCollectRequest(
            long requestId,
            String rankType,
            String homeId,
            long enterTimeMs,
            int targetCount
    ) {
        String type = safeTrim(rankType);
        boolean collectAllRankUsers = ModuleSettings.getCollectAllRankUsersEnabled(
                ModuleSettings.appPrefs(this)
        );
        int collectAllRankDataLimit = ModuleSettings.getCollectAllRankDataLimit(
                ModuleSettings.appPrefs(this)
        );
        int safeTargetCount = Math.max(0, targetCount);
        if (!collectAllRankUsers && safeTargetCount <= 0) {
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
        if (!isRankCollectRequestActive(requestId)) {
            return new RankCollectSummary(
                    false,
                    "request_preempted_before_start",
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
                + " targetCount=" + (collectAllRankUsers ? "ALL" : safeTargetCount)
                + " collectAll=" + collectAllRankUsers
                + " dataLimit=" + (collectAllRankUsers ? collectAllRankDataLimit : 0)
                + " homeId=" + homeId
                + " enterTime=" + enterTimeMs
                + " csv=" + safeTrim(csvPath));
        RankCollectSummary summary = collectRankRows(
                requestId,
                type,
                homeId,
                enterTimeMs,
                safeTargetCount,
                collectAllRankUsers,
                collectAllRankDataLimit
        );
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
            long requestId,
            String rankType,
            String homeId,
            long enterTimeMs,
            int targetCount,
            boolean collectAllRankUsers,
            int collectAllRankDataLimit
    ) {
        int safeTargetCount = Math.max(0, targetCount);
        int safeCollectAllDataLimit = collectAllRankUsers
                ? Math.max(0, collectAllRankDataLimit)
                : 0;
        if (!collectAllRankUsers && safeTargetCount <= 0) {
            return new RankCollectSummary(true, "target_count_zero", 0, 0);
        }
        int nextRank = 1;
        int collectedCount = 0;
        int appendCandidateCount = 0;
        int maxCollectedRank = 0;
        int scrollCount = 0;
        int noProgressScrollCount = 0;
        int highestObservedRank = 0;
        int rootMissingRetry = 0;
        int rankPageNotReadyRetry = 0;
        int clickFailedRetry = 0;
        boolean rankPageOpenedLogged = false;
        boolean pendingNoProgressConfirm = false;
        boolean stoppedByNoProgressLimit = false;
        boolean stoppedByDataLimit = false;
        boolean collectDetailEnabled = ModuleSettings.getCollectUserDetailEnabled(
                ModuleSettings.appPrefs(this)
        );
        int singleRankRetryLimit = Math.max(
                1,
                ModuleSettings.getSingleRankRetryLimit(ModuleSettings.appPrefs(this))
        );
        boolean success = true;
        String failDetail = "";
        Set<Integer> doneRanks = new HashSet<Integer>();
        Set<Integer> requestCollectedRanks = new HashSet<Integer>();
        List<LiveRoomRankCsvStore.ContributionCsvRow> contributionRows =
                new ArrayList<LiveRoomRankCsvStore.ContributionCsvRow>();
        List<LiveRoomRankCsvStore.CharmCsvRow> charmRows =
                new ArrayList<LiveRoomRankCsvStore.CharmCsvRow>();
        String collectSessionKey = "";
        if (!collectAllRankUsers && safeTargetCount > 0) {
            collectSessionKey = buildRankCollectSessionKey(rankType, homeId, enterTimeMs);
            if (!TextUtils.isEmpty(collectSessionKey)) {
                doneRanks.addAll(snapshotCollectedRanksForSession(collectSessionKey));
                collectedCount = countRanksWithinLimit(doneRanks, safeTargetCount);
                nextRank = resolveNextMissingRank(doneRanks, nextRank);
            }
        }
        while (collectAllRankUsers || nextRank <= safeTargetCount) {
            if (!isRankCollectRequestActive(requestId)) {
                success = false;
                failDetail = "request_preempted";
                break;
            }
            if (!isEngineRunningForRankCollect()) {
                success = false;
                failDetail = "engine_stopped";
                break;
            }
            if (!waitSlidingContainerGone(requestId, "rank_collect_loop_" + nextRank)) {
                if (!isRankCollectRequestActive(requestId)) {
                    success = false;
                    failDetail = "request_preempted";
                } else {
                    success = false;
                    failDetail = "sliding_container_block_timeout";
                }
                break;
            }
            if (!isRankCollectRequestActive(requestId)) {
                success = false;
                failDetail = "request_preempted";
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
            if (collectAllRankUsers && safeCollectAllDataLimit > 0) {
                RankRowData limitCheckRow = findVisibleRowByRank(scanResult.visibleRows, nextRank);
                if (limitCheckRow == null
                        && scanResult.match != null
                        && scanResult.match.row != null
                        && scanResult.match.row.rank == nextRank) {
                    limitCheckRow = scanResult.match.row;
                }
                if (limitCheckRow != null) {
                    long normalizedDataValue = parseRankDataToComparableValue(limitCheckRow.dataValue);
                    if (normalizedDataValue >= 0L && normalizedDataValue < safeCollectAllDataLimit) {
                        stoppedByDataLimit = true;
                        log("榜单采集达到数值限制，停止采集: type=" + safeTrim(rankType)
                                + " rank=" + limitCheckRow.rank
                                + " dataRaw=" + safeTrim(limitCheckRow.dataValue)
                                + " dataValue=" + normalizedDataValue
                                + " limit=" + safeCollectAllDataLimit);
                        break;
                    }
                }
            }
            if (!collectDetailEnabled && collectAllRankUsers) {
                int pageCollected = 0;
                boolean stopByDataLimitThisPage = false;
                int expectedVisibleRank = Math.max(1, nextRank);
                DetailCollectData listModeDetail = new DetailCollectData(false, "", "", "", "");
                for (RankRowData visibleRow : scanResult.visibleRows) {
                    if (visibleRow == null || visibleRow.rank <= 0) {
                        continue;
                    }
                    if (doneRanks.contains(visibleRow.rank)) {
                        continue;
                    }
                    if (safeCollectAllDataLimit > 0) {
                        if (visibleRow.rank < expectedVisibleRank) {
                            continue;
                        }
                        if (visibleRow.rank > expectedVisibleRank) {
                            break;
                        }
                    }
                    if (safeCollectAllDataLimit > 0) {
                        long normalizedDataValue = parseRankDataToComparableValue(visibleRow.dataValue);
                        if (normalizedDataValue >= 0L && normalizedDataValue < safeCollectAllDataLimit) {
                            stoppedByDataLimit = true;
                            stopByDataLimitThisPage = true;
                            log("榜单采集达到数值限制，停止采集: type=" + safeTrim(rankType)
                                    + " rank=" + visibleRow.rank
                                    + " dataRaw=" + safeTrim(visibleRow.dataValue)
                                    + " dataValue=" + normalizedDataValue
                                    + " limit=" + safeCollectAllDataLimit);
                            break;
                        }
                    }
                    boolean queued = queueRankRow(
                            rankType,
                            homeId,
                            visibleRow,
                            listModeDetail,
                            enterTimeMs,
                            contributionRows,
                            charmRows
                    );
                    if (!queued) {
                        log("榜单采集入队失败: type=" + safeTrim(rankType) + " rank=" + visibleRow.rank);
                        continue;
                    }
                    if (doneRanks.add(visibleRow.rank)) {
                        collectedCount++;
                        appendCandidateCount++;
                        requestCollectedRanks.add(visibleRow.rank);
                    }
                    pageCollected++;
                    maxCollectedRank = Math.max(maxCollectedRank, visibleRow.rank);
                    log("榜单采集完成一条: type=" + safeTrim(rankType)
                            + " rank=" + visibleRow.rank
                            + " name=" + safeTrim(visibleRow.name)
                            + " data=" + safeTrim(visibleRow.dataValue)
                            + " id="
                            + " detailMode=list");
                    if (safeCollectAllDataLimit > 0) {
                        expectedVisibleRank = visibleRow.rank + 1;
                    }
                }
                if (stopByDataLimitThisPage) {
                    break;
                }
                if (pageCollected > 0) {
                    nextRank = resolveNextMissingRank(doneRanks, nextRank);
                    pendingNoProgressConfirm = false;
                    noProgressScrollCount = 0;
                    log("榜单可见区批量采集: type=" + safeTrim(rankType)
                            + " pageCollected=" + pageCollected
                            + " totalCollected=" + collectedCount
                            + " nextRank=" + nextRank
                            + " scannedMaxRank=" + scanResult.maxRank);
                    continue;
                }
            }
            if (collectAllRankUsers
                    && collectDetailEnabled
                    && (scanResult.match == null
                    || scanResult.match.row == null
                    || scanResult.match.node == null)) {
                RankRowData fallbackRow = null;
                for (RankRowData visibleRow : scanResult.visibleRows) {
                    if (visibleRow == null || visibleRow.rank <= 0) {
                        continue;
                    }
                    if (doneRanks.contains(visibleRow.rank)) {
                        continue;
                    }
                    fallbackRow = visibleRow;
                    break;
                }
                if (fallbackRow != null) {
                    int expectedBefore = nextRank;
                    if (safeCollectAllDataLimit > 0 && fallbackRow.rank > expectedBefore + 1) {
                        fallbackRow = null;
                    } else {
                    nextRank = fallbackRow.rank;
                    if (expectedBefore != nextRank) {
                        log("榜单当前页目标排名缺失，改用可见排名重试: type=" + safeTrim(rankType)
                                + " expectedRank=" + expectedBefore
                                + " fallbackRank=" + nextRank
                                + " scannedMaxRank=" + scanResult.maxRank);
                        continue;
                    }
                    }
                }
            }
            int visibleFallbackRank = resolveFirstVisibleUncollectedRank(
                    scanResult.visibleRows,
                    doneRanks,
                    collectAllRankUsers ? Integer.MAX_VALUE : safeTargetCount
            );
            if (collectAllRankUsers
                    && safeCollectAllDataLimit > 0
                    && visibleFallbackRank > nextRank + 1) {
                visibleFallbackRank = 0;
            }
            if (visibleFallbackRank > 0 && visibleFallbackRank != nextRank) {
                int expectedBefore = nextRank;
                nextRank = visibleFallbackRank;
                log("榜单当前页存在未采集用户，优先继续当前页: type=" + safeTrim(rankType)
                        + " expectedRank=" + expectedBefore
                        + " continueWithRank=" + nextRank
                        + " scannedMaxRank=" + scanResult.maxRank
                        + " visibleRanks=" + buildVisibleRanksSummary(scanResult.visibleRows));
                continue;
            }
            if (scanResult.match != null && scanResult.match.row != null && scanResult.match.node != null) {
                RankRowData row = scanResult.match.row;
                AccessibilityNodeInfo rowNode = scanResult.match.node;
                if (doneRanks.contains(row.rank)) {
                    nextRank = resolveNextMissingRank(doneRanks, nextRank);
                    safeRecycle(rowNode);
                    continue;
                }
                if (collectAllRankUsers && safeCollectAllDataLimit > 0) {
                    long normalizedDataValue = parseRankDataToComparableValue(row.dataValue);
                    if (normalizedDataValue >= 0L && normalizedDataValue < safeCollectAllDataLimit) {
                        stoppedByDataLimit = true;
                        safeRecycle(rowNode);
                        log("榜单采集达到数值限制，停止采集: type=" + safeTrim(rankType)
                                + " rank=" + row.rank
                                + " dataRaw=" + safeTrim(row.dataValue)
                                + " dataValue=" + normalizedDataValue
                                + " limit=" + safeCollectAllDataLimit);
                        break;
                    }
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
                    SystemClock.sleep(UiComponentConfig.LIVE_ROOM_TASK_RANK_DETAIL_WAIT_AFTER_CLICK_MS);
                    detailData = collectDetailData(requestId, rankType);
                    if (!isRankCollectRequestActive(requestId)) {
                        success = false;
                        failDetail = "request_preempted";
                        break;
                    }
                    if (detailData.enteredDetail) {
                        performGlobalAction(GLOBAL_ACTION_BACK);
                        SystemClock.sleep(UiComponentConfig.LIVE_ROOM_TASK_RANK_BACK_TO_LIST_WAIT_MS);
                    }
                    if (!detailData.enteredDetail) {
                        clickFailedRetry++;
                        if (clickFailedRetry < 3) {
                            log("用户详情未就绪，等待重试: type=" + safeTrim(rankType)
                                    + " rank=" + row.rank
                                    + " retry=" + clickFailedRetry + "/3");
                            SystemClock.sleep(
                                    UiComponentConfig.LIVE_ROOM_TASK_RANK_DETAIL_READY_RETRY_INTERVAL_MS
                            );
                            continue;
                        }
                        log("用户详情未就绪，重试耗尽后按空值写入: type=" + safeTrim(rankType)
                                + " rank=" + row.rank);
                    } else {
                        clickFailedRetry = 0;
                    }
                    boolean detailValid = isDetailDataValid(detailData, rankType);
                    log("用户详情采集结果: type=" + safeTrim(rankType)
                            + " rank=" + row.rank
                            + " enteredDetail=" + detailData.enteredDetail
                            + " valid=" + detailValid
                            + " id=" + safeTrim(detailData.userId)
                            + " ip=" + safeTrim(detailData.ip)
                            + " consumption=" + safeTrim(detailData.consumption)
                            + " followers=" + safeTrim(detailData.followers));
                    if (!detailValid) {
                        log("用户详情字段全空，按空值写入: type=" + safeTrim(rankType)
                                + " rank=" + row.rank);
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
                if (doneRanks.add(row.rank)) {
                    collectedCount++;
                    appendCandidateCount++;
                    requestCollectedRanks.add(row.rank);
                }
                maxCollectedRank = Math.max(maxCollectedRank, row.rank);
                nextRank = resolveNextMissingRank(doneRanks, nextRank);
                pendingNoProgressConfirm = false;
                noProgressScrollCount = 0;
                log("榜单采集完成一条: type=" + safeTrim(rankType)
                        + " rank=" + row.rank
                        + " name=" + safeTrim(row.name)
                        + " data=" + safeTrim(row.dataValue)
                        + " id=" + safeTrim(detailData.userId)
                        + " detailMode=" + (collectDetailEnabled ? "detail" : "list"));
                continue;
            }
            int visibleMaxRank = 0;
            for (RankRowData visibleRow : scanResult.visibleRows) {
                if (visibleRow == null || visibleRow.rank <= 0) {
                    continue;
                }
                visibleMaxRank = Math.max(visibleMaxRank, visibleRow.rank);
            }
            boolean pageExhaustedForExpected = visibleMaxRank > 0 && nextRank > visibleMaxRank;
            if (noProgressScrollCount > 0 || scrollCount % 3 == 0) {
                if (pageExhaustedForExpected) {
                    log("榜单当前页已采尽，准备上滑: type=" + safeTrim(rankType)
                            + " expectedRank=" + nextRank
                            + " visibleMaxRank=" + visibleMaxRank
                            + " visibleCount=" + scanResult.visibleRows.size()
                            + " visibleRanks=" + buildVisibleRanksSummary(scanResult.visibleRows));
                } else {
                    log("榜单目标排名当前不可见，继续上滑: type=" + safeTrim(rankType)
                            + " expectedRank=" + nextRank
                            + " scannedMaxRank=" + scanResult.maxRank
                            + " visibleCount=" + scanResult.visibleRows.size()
                            + " visibleRanks=" + buildVisibleRanksSummary(scanResult.visibleRows)
                            + " signature=" + safeTrim(scanResult.pageSignature));
                }
            }
            if (!success) {
                break;
            }
            int scrollHardLimit = collectAllRankUsers
                    ? Math.max(
                    UiComponentConfig.LIVE_ROOM_TASK_RANK_SCROLL_MAX_COUNT + 40,
                    UiComponentConfig.LIVE_ROOM_TASK_RANK_SCROLL_MAX_COUNT * 8
            )
                    : UiComponentConfig.LIVE_ROOM_TASK_RANK_SCROLL_MAX_COUNT;
            if (scrollCount >= scrollHardLimit) {
                log("榜单采集停止上滑: type=" + safeTrim(rankType)
                        + " reason=scroll_limit"
                        + " nextRank=" + nextRank
                        + " scrollCount=" + scrollCount
                        + " scrollLimit=" + scrollHardLimit
                        + " noProgress=" + noProgressScrollCount
                        + " noProgressLimit=" + Math.max(1, singleRankRetryLimit)
                        + " maxObserved=" + highestObservedRank);
                break;
            }
            int before = scanResult.maxRank;
            log("榜单采集准备上滑: type=" + safeTrim(rankType)
                    + " nextRank=" + nextRank
                    + " maxRankBefore=" + before
                    + " scrollCount=" + scrollCount
                    + " noProgress=" + noProgressScrollCount
                    + " confirmPending=" + pendingNoProgressConfirm);
            AccessibilityNodeInfo scrollRoot = obtainTargetRoot();
            boolean scrolled = false;
            try {
                scrolled = performRankListScroll(scrollRoot, rankType, nextRank, scrollCount);
            } finally {
                safeRecycle(scrollRoot);
            }
            scrollCount++;
            SystemClock.sleep(UiComponentConfig.LIVE_ROOM_TASK_RANK_SCROLL_WAIT_MS);
            int after = before;
            RankScanResult afterScan = null;
            AccessibilityNodeInfo rootAfter = obtainTargetRoot();
            if (rootAfter != null) {
                try {
                    afterScan = scanRankRows(rootAfter, rankType, -1);
                    after = Math.max(after, afterScan.maxRank);
                } finally {
                    safeRecycle(rootAfter);
                }
            }
            if (after <= before) {
                int compensateMaxTry = Math.max(
                        0,
                        UiComponentConfig.LIVE_ROOM_TASK_RANK_SCROLL_ACTION_COMPENSATE_MAX_TRY
                );
                for (int compensateTry = 1; compensateTry <= compensateMaxTry; compensateTry++) {
                    AccessibilityNodeInfo compensateRoot = obtainTargetRoot();
                    boolean compensateSuccess;
                    String compensateDetail;
                    try {
                        compensateSuccess = performRankListScroll(
                                compensateRoot,
                                rankType,
                                nextRank,
                                scrollCount + compensateTry
                        );
                        compensateDetail = compensateSuccess ? "ok" : "failed";
                    } finally {
                        safeRecycle(compensateRoot);
                    }
                    log("榜单采集上滑补偿: type=" + safeTrim(rankType)
                            + " nextRank=" + nextRank
                            + " try=" + compensateTry + "/" + compensateMaxTry
                            + " success=" + compensateSuccess
                            + " detail=" + safeTrim(compensateDetail));
                    if (!compensateSuccess) {
                        continue;
                    }
                    scrolled = true;
                    SystemClock.sleep(
                            Math.max(
                                    120L,
                                    UiComponentConfig.LIVE_ROOM_TASK_RANK_SCROLL_ACTION_COMPENSATE_WAIT_MS
                            )
                    );
                    AccessibilityNodeInfo compensateAfterRoot = obtainTargetRoot();
                    int compensateAfter = after;
                    if (compensateAfterRoot != null) {
                        try {
                            RankScanResult compensateAfterScan = scanRankRows(
                                    compensateAfterRoot,
                                    rankType,
                                    -1
                            );
                            afterScan = compensateAfterScan;
                            compensateAfter = Math.max(compensateAfter, compensateAfterScan.maxRank);
                        } finally {
                            safeRecycle(compensateAfterRoot);
                        }
                    }
                    after = Math.max(after, compensateAfter);
                    if (after > before) {
                        break;
                    }
                }
            }
            highestObservedRank = Math.max(highestObservedRank, after);
            boolean signatureChanged = isRankPageRefreshed(scanResult, afterScan);
            boolean rankProgressed = scrolled
                    && (
                    after > before
                            || signatureChanged
            );
            if (rankProgressed) {
                pendingNoProgressConfirm = false;
                noProgressScrollCount = 0;
            } else {
                int noProgressLimit = Math.max(1, singleRankRetryLimit);
                if (!pendingNoProgressConfirm) {
                    pendingNoProgressConfirm = true;
                    noProgressScrollCount = 1;
                    log("榜单采集疑似到底，进入复核上滑: type=" + safeTrim(rankType)
                            + " nextRank=" + nextRank
                            + " retry=" + noProgressScrollCount + "/" + noProgressLimit
                            + " maxRankBefore=" + before
                            + " maxRankAfter=" + after
                            + " limit=" + noProgressLimit);
                } else {
                    noProgressScrollCount++;
                    log("榜单采集到底复核: type=" + safeTrim(rankType)
                            + " nextRank=" + nextRank
                            + " retry=" + noProgressScrollCount + "/" + noProgressLimit
                            + " maxRankBefore=" + before
                            + " maxRankAfter=" + after);
                }
                if (noProgressScrollCount >= noProgressLimit) {
                    stoppedByNoProgressLimit = true;
                    log("榜单采集停止上滑: type=" + safeTrim(rankType)
                            + " reason=no_progress_limit"
                            + " nextRank=" + nextRank
                            + " scrollCount=" + scrollCount
                            + " noProgress=" + noProgressScrollCount
                            + " noProgressLimit=" + noProgressLimit
                            + " maxObserved=" + highestObservedRank);
                    break;
                }
            }
            log("榜单采集上滑: type=" + safeTrim(rankType)
                    + " nextRank=" + nextRank
                    + " scrollCount=" + scrollCount
                    + " noProgress=" + noProgressScrollCount
                    + " maxRankBefore=" + before
                    + " maxRankAfter=" + after
                    + " scrolled=" + scrolled
                    + " progressed=" + rankProgressed
                    + " signatureChanged=" + signatureChanged
                    + " confirmPending=" + pendingNoProgressConfirm);
        }
        if (success && collectedCount <= 0 && !stoppedByDataLimit) {
            success = false;
            failDetail = "rank_no_data_collected";
        }
        boolean countMatched = collectAllRankUsers
                ? (stoppedByNoProgressLimit || stoppedByDataLimit)
                : (safeTargetCount <= 0 || collectedCount >= safeTargetCount);
        if (success && !countMatched) {
            if (!collectAllRankUsers && stoppedByNoProgressLimit && collectedCount > 0) {
                countMatched = true;
                log("榜单采集提前完成: type=" + safeTrim(rankType)
                        + " reason=no_progress_limit"
                        + " target=" + safeTargetCount
                        + " actual=" + collectedCount
                        + " highestObserved=" + highestObservedRank
                        + " limit=" + singleRankRetryLimit);
            } else if (collectAllRankUsers && stoppedByDataLimit) {
                countMatched = true;
            } else if (collectAllRankUsers) {
                success = false;
                failDetail = "collect_all_not_confirmed_bottom(actual=" + collectedCount
                        + ",highestObserved=" + highestObservedRank
                        + ",scrollCount=" + scrollCount + ")";
            } else {
                success = false;
                failDetail = "rank_target_not_reached(target=" + safeTargetCount
                        + ",actual=" + collectedCount
                        + ",highestObserved=" + highestObservedRank + ")";
            }
        }
        RankFlushResult flushResult = flushQueuedRankRows(rankType, contributionRows, charmRows);
        boolean flushOk = flushResult.ioSuccess && flushResult.acceptedCount >= appendCandidateCount;
        if (!collectAllRankUsers
                && flushOk
                && !TextUtils.isEmpty(collectSessionKey)
                && !requestCollectedRanks.isEmpty()) {
            mergeCollectedRanksForSession(collectSessionKey, requestCollectedRanks);
        }
        if (!collectAllRankUsers
                && flushOk
                && success
                && countMatched
                && !TextUtils.isEmpty(collectSessionKey)) {
            clearCollectedRanksForSession(collectSessionKey);
        }
        String targetLabel = collectAllRankUsers ? "ALL" : String.valueOf(safeTargetCount);
        log("榜单采集数量核对: type=" + safeTrim(rankType)
                + " target=" + targetLabel
                + " actual=" + collectedCount
                + " matched=" + countMatched
                + " maxRank=" + maxCollectedRank
                + " highestObserved=" + highestObservedRank
                + " scrollCount=" + scrollCount
                + " noProgress=" + noProgressScrollCount);
        log("榜单采集写入汇总: type=" + safeTrim(rankType)
                + " queued=" + appendCandidateCount
                + " accepted=" + flushResult.acceptedCount
                + " written=" + flushResult.writtenCount
                + " dedupSkipped=" + flushResult.duplicateSkippedCount
                + " ioSuccess=" + flushResult.ioSuccess
                + " flushOk=" + flushOk
                + " detailMode=" + (collectDetailEnabled ? "detail" : "list"));
        String detail = (success ? "collect_done" : safeTrim(failDetail)) + "(target=" + targetLabel
                + ",count=" + collectedCount
                + ",newCount=" + appendCandidateCount
                + ",matched=" + countMatched
                + ",accepted=" + flushResult.acceptedCount
                + ",written=" + flushResult.writtenCount
                + ",dedupSkipped=" + flushResult.duplicateSkippedCount
                + ",maxRank=" + maxCollectedRank
                + ",highestObserved=" + highestObservedRank + ")";
        return new RankCollectSummary(success && flushOk, detail, collectedCount, maxCollectedRank);
    }

    private DetailCollectData collectDetailData(long requestId, String rankType) {
        int maxRetry = Math.max(1, UiComponentConfig.LIVE_ROOM_TASK_RANK_DETAIL_READY_MAX_RETRY);
        int invalidDataRetryCount = 0;
        int maxInvalidDataRetry = Math.max(
                1,
                UiComponentConfig.LIVE_ROOM_TASK_RANK_DETAIL_DATA_RETRY_MAX_COUNT
        );
        DetailCollectData lastInvalidData = null;
        for (int i = 0; i < maxRetry; i++) {
            if (!isRankCollectRequestActive(requestId)) {
                break;
            }
            if (!waitSlidingContainerGone(requestId, "detail_collect_" + (i + 1))) {
                break;
            }
            if (!isRankCollectRequestActive(requestId)) {
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
                boolean hasIdentityMarker = idNode != null || artistNode != null;
                safeRecycle(idNode);
                safeRecycle(artistNode);
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
                boolean hasAnyDetailField = !TextUtils.isEmpty(userId)
                        || !TextUtils.isEmpty(ip)
                        || !TextUtils.isEmpty(consumption)
                        || !TextUtils.isEmpty(followers);
                boolean ready = hasIdentityMarker || hasAnyDetailField;
                if (!ready) {
                    SystemClock.sleep(UiComponentConfig.LIVE_ROOM_TASK_RANK_DETAIL_READY_RETRY_INTERVAL_MS);
                    continue;
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
        return !TextUtils.isEmpty(detailData.userId)
                || !TextUtils.isEmpty(detailData.ip)
                || !TextUtils.isEmpty(detailData.consumption)
                || !TextUtils.isEmpty(detailData.followers);
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
            return new RankScanResult(
                    null,
                    0,
                    "",
                    Collections.<RankRowData>emptyList()
            );
        }
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<AccessibilityNodeInfo>();
        ArrayDeque<AccessibilityNodeInfo> allNodes = new ArrayDeque<AccessibilityNodeInfo>();
        AccessibilityNodeInfo rootCopy = AccessibilityNodeInfo.obtain(root);
        queue.offer(rootCopy);
        allNodes.offer(rootCopy);
        RankRowMatch matched = null;
        int maxRank = 0;
        ArrayList<RankVisibleRow> signatureRows = new ArrayList<RankVisibleRow>();
        ArrayList<RankRowData> visibleRows = new ArrayList<RankRowData>();
        HashSet<Integer> visibleRankSet = new HashSet<Integer>();
        try {
            while (!queue.isEmpty()) {
                AccessibilityNodeInfo current = queue.poll();
                if (current == null) {
                    continue;
                }
                if (isRankRowCandidateNode(current)) {
                    RankRowData rowData = parseRankRowData(current, rankType);
                    if (rowData != null && rowData.rank > 0) {
                        Rect bounds = new Rect();
                        boolean hasBounds = false;
                        try {
                            current.getBoundsInScreen(bounds);
                            hasBounds = true;
                        } catch (Throwable ignore) {
                            hasBounds = false;
                        }
                        if (!hasBounds || !isRectInCurrentScreen(bounds)) {
                            continue;
                        }
                        maxRank = Math.max(maxRank, rowData.rank);
                        if (signatureRows.size() < 50) {
                            signatureRows.add(
                                    new RankVisibleRow(
                                            bounds.top,
                                            rowData.rank,
                                            rowData.name
                                    )
                            );
                        }
                        if (!visibleRankSet.contains(rowData.rank)) {
                            visibleRankSet.add(rowData.rank);
                            visibleRows.add(new RankRowData(
                                    rowData.rank,
                                    rowData.level,
                                    rowData.name,
                                    rowData.dataValue
                            ));
                        }
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
        Collections.sort(visibleRows, new Comparator<RankRowData>() {
            @Override
            public int compare(RankRowData o1, RankRowData o2) {
                if (o1 == o2) {
                    return 0;
                }
                if (o1 == null) {
                    return -1;
                }
                if (o2 == null) {
                    return 1;
                }
                return o1.rank - o2.rank;
            }
        });
        return new RankScanResult(
                matched,
                maxRank,
                buildRankPageSignature(signatureRows),
                visibleRows
        );
    }

    private boolean isRankPageRefreshed(RankScanResult before, RankScanResult after) {
        if (before == null || after == null) {
            return false;
        }
        if (after.maxRank > before.maxRank) {
            return true;
        }
        return !TextUtils.equals(safeTrim(before.pageSignature), safeTrim(after.pageSignature));
    }

    private String buildRankPageSignature(List<RankVisibleRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return "";
        }
        Collections.sort(rows, new Comparator<RankVisibleRow>() {
            @Override
            public int compare(RankVisibleRow o1, RankVisibleRow o2) {
                if (o1 == o2) {
                    return 0;
                }
                if (o1 == null) {
                    return -1;
                }
                if (o2 == null) {
                    return 1;
                }
                if (o1.top != o2.top) {
                    return o1.top - o2.top;
                }
                if (o1.rank != o2.rank) {
                    return o1.rank - o2.rank;
                }
                return safeTrim(o1.name).compareTo(safeTrim(o2.name));
            }
        });
        StringBuilder signatureBuilder = new StringBuilder();
        int maxSignatureCount = Math.min(20, rows.size());
        for (int i = 0; i < maxSignatureCount; i++) {
            RankVisibleRow row = rows.get(i);
            if (row == null) {
                continue;
            }
            if (signatureBuilder.length() > 0) {
                signatureBuilder.append(';');
            }
            signatureBuilder.append(row.rank).append('|').append(safeTrim(row.name));
        }
        return signatureBuilder.toString();
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
        int rank = extractRankFromRowNode(rowNode, texts);
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

    private int extractRankFromRowNode(AccessibilityNodeInfo rowNode, List<String> texts) {
        if (rowNode == null) {
            return parseFirstRankFromTexts(texts);
        }
        Rect rowRect = new Rect();
        boolean rowRectOk = false;
        try {
            rowNode.getBoundsInScreen(rowRect);
            rowRectOk = rowRect.width() > 0 && rowRect.height() > 0;
        } catch (Throwable ignore) {
            rowRectOk = false;
        }
        if (!rowRectOk) {
            int rankFromFirst = parseRankValue(pickByIndex(texts, 0));
            if (rankFromFirst > 0) {
                return rankFromFirst;
            }
            return parseFirstRankFromTexts(texts);
        }
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<AccessibilityNodeInfo>();
        ArrayDeque<AccessibilityNodeInfo> allNodes = new ArrayDeque<AccessibilityNodeInfo>();
        AccessibilityNodeInfo rootCopy = AccessibilityNodeInfo.obtain(rowNode);
        queue.offer(rootCopy);
        allNodes.offer(rootCopy);
        int bestRank = 0;
        int bestScore = Integer.MAX_VALUE;
        try {
            while (!queue.isEmpty()) {
                AccessibilityNodeInfo current = queue.poll();
                if (current == null) {
                    continue;
                }
                String text = readNodeText(current);
                int parsedRank = parseRankValue(text);
                if (parsedRank > 0) {
                    Rect nodeRect = new Rect();
                    boolean nodeRectOk = false;
                    try {
                        current.getBoundsInScreen(nodeRect);
                        nodeRectOk = true;
                    } catch (Throwable ignore) {
                        nodeRectOk = false;
                    }
                    if (nodeRectOk && isRectInCurrentScreen(nodeRect)) {
                        int rowWidth = Math.max(1, rowRect.width());
                        int leftOffset = Math.max(0, nodeRect.left - rowRect.left);
                        float leftRatio = leftOffset / (float) rowWidth;
                        if (leftRatio <= 0.38f) {
                            int topOffset = Math.max(0, nodeRect.top - rowRect.top);
                            int textLen = safeTrim(text).length();
                            int score = leftOffset * 5 + topOffset * 2 + textLen * 8;
                            if (score < bestScore) {
                                bestScore = score;
                                bestRank = parsedRank;
                            }
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
        if (bestRank > 0) {
            return bestRank;
        }
        int rankFromFirst = parseRankValue(pickByIndex(texts, 0));
        if (rankFromFirst > 0) {
            return rankFromFirst;
        }
        return parseFirstRankFromTexts(texts);
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
        String normalized = value
                .replace("第", "")
                .replace("名", "")
                .replace("NO.", "")
                .replace("No.", "")
                .replace("no.", "")
                .replace("NO", "")
                .replace("No", "")
                .replace("no", "")
                .replace("#", "")
                .replace("、", "")
                .replace("。", "")
                .replace(".", "");
        normalized = safeTrim(normalized);
        if (normalized.matches("^\\d{1,4}$")) {
            try {
                return Integer.parseInt(normalized);
            } catch (Throwable ignore) {
                return 0;
            }
        }
        Matcher matcher = RANK_VALUE_LEADING_DIGITS_PATTERN.matcher(value);
        if (matcher.find()) {
            String leading = safeTrim(matcher.group(1));
            if (leading.matches("^\\d{1,4}$")) {
                try {
                    return Integer.parseInt(leading);
                } catch (Throwable ignore) {
                    return 0;
                }
            }
        }
        return 0;
    }

    private String pickByIndex(List<String> values, int index) {
        if (values == null || values.isEmpty() || index < 0 || index >= values.size()) {
            return "";
        }
        return safeTrim(values.get(index));
    }

    private int resolveNextMissingRank(Set<Integer> doneRanks, int currentRank) {
        int nextRank = Math.max(1, currentRank);
        if (doneRanks == null || doneRanks.isEmpty()) {
            return nextRank;
        }
        while (doneRanks.contains(nextRank) && nextRank < Integer.MAX_VALUE) {
            nextRank++;
        }
        return Math.max(1, nextRank);
    }

    private int countRanksWithinLimit(Set<Integer> ranks, int maxRankLimit) {
        if (ranks == null || ranks.isEmpty()) {
            return 0;
        }
        int safeMaxLimit = maxRankLimit <= 0 ? Integer.MAX_VALUE : maxRankLimit;
        int count = 0;
        for (Integer rank : ranks) {
            if (rank == null) {
                continue;
            }
            int safeRank = Math.max(0, rank.intValue());
            if (safeRank <= 0 || safeRank > safeMaxLimit) {
                continue;
            }
            count++;
        }
        return count;
    }

    private String buildRankCollectSessionKey(String rankType, String homeId, long enterTimeMs) {
        String safeType = safeTrim(rankType);
        String safeHomeId = safeTrim(homeId);
        long safeEnterTime = Math.max(0L, enterTimeMs);
        if (TextUtils.isEmpty(safeType) || TextUtils.isEmpty(safeHomeId) || safeEnterTime <= 0L) {
            return "";
        }
        return safeType + "|" + safeHomeId + "|" + safeEnterTime;
    }

    private Set<Integer> snapshotCollectedRanksForSession(String sessionKey) {
        String key = safeTrim(sessionKey);
        if (TextUtils.isEmpty(key)) {
            return Collections.emptySet();
        }
        synchronized (rankCollectSessionLock) {
            HashSet<Integer> cached = rankCollectedRanksBySession.get(key);
            if (cached == null || cached.isEmpty()) {
                return Collections.emptySet();
            }
            return new HashSet<Integer>(cached);
        }
    }

    private void mergeCollectedRanksForSession(String sessionKey, Set<Integer> ranks) {
        String key = safeTrim(sessionKey);
        if (TextUtils.isEmpty(key) || ranks == null || ranks.isEmpty()) {
            return;
        }
        synchronized (rankCollectSessionLock) {
            HashSet<Integer> cached = rankCollectedRanksBySession.get(key);
            if (cached == null) {
                cached = new HashSet<Integer>();
                rankCollectedRanksBySession.put(key, cached);
            }
            for (Integer rank : ranks) {
                if (rank == null) {
                    continue;
                }
                int safeRank = Math.max(0, rank.intValue());
                if (safeRank <= 0) {
                    continue;
                }
                cached.add(safeRank);
            }
            while (rankCollectedRanksBySession.size() > RANK_COLLECT_SESSION_CACHE_MAX) {
                String eldestKey = rankCollectedRanksBySession.keySet().iterator().next();
                rankCollectedRanksBySession.remove(eldestKey);
            }
        }
    }

    private void clearCollectedRanksForSession(String sessionKey) {
        String key = safeTrim(sessionKey);
        if (TextUtils.isEmpty(key)) {
            return;
        }
        synchronized (rankCollectSessionLock) {
            rankCollectedRanksBySession.remove(key);
        }
    }

    private int resolveFirstVisibleUncollectedRank(
            List<RankRowData> visibleRows,
            Set<Integer> doneRanks,
            int maxRankLimit
    ) {
        if (visibleRows == null || visibleRows.isEmpty()) {
            return 0;
        }
        int safeMaxLimit = maxRankLimit <= 0 ? Integer.MAX_VALUE : maxRankLimit;
        for (RankRowData row : visibleRows) {
            if (row == null || row.rank <= 0) {
                continue;
            }
            if (row.rank > safeMaxLimit) {
                continue;
            }
            if (doneRanks != null && doneRanks.contains(row.rank)) {
                continue;
            }
            return row.rank;
        }
        return 0;
    }

    private RankRowData findVisibleRowByRank(List<RankRowData> visibleRows, int targetRank) {
        int safeTargetRank = Math.max(1, targetRank);
        if (visibleRows == null || visibleRows.isEmpty() || safeTargetRank <= 0) {
            return null;
        }
        for (RankRowData row : visibleRows) {
            if (row == null || row.rank <= 0) {
                continue;
            }
            if (row.rank == safeTargetRank) {
                return row;
            }
        }
        return null;
    }

    private long parseRankDataToComparableValue(String rawValue) {
        String value = safeTrim(rawValue)
                .replace(",", "")
                .replace("，", "")
                .replace(" ", "");
        if (TextUtils.isEmpty(value)) {
            return -1L;
        }
        Matcher matcher = RANK_DATA_NUMERIC_PATTERN.matcher(value);
        if (!matcher.matches()) {
            matcher = RANK_DATA_NUMERIC_FALLBACK_PATTERN.matcher(value);
            if (!matcher.find()) {
                return -1L;
            }
        }
        double number;
        try {
            number = Double.parseDouble(safeTrim(matcher.group(1)));
        } catch (Throwable ignore) {
            return -1L;
        }
        if (Double.isNaN(number) || Double.isInfinite(number) || number < 0d) {
            return -1L;
        }
        String unit = safeTrim(matcher.group(2));
        double multiplier = 1d;
        if ("万".equals(unit) || "w".equals(unit) || "W".equals(unit)) {
            multiplier = 10000d;
        } else if ("亿".equals(unit)) {
            multiplier = 100000000d;
        }
        double normalized = number * multiplier;
        if (Double.isNaN(normalized) || Double.isInfinite(normalized)
                || normalized < 0d || normalized > Long.MAX_VALUE) {
            return -1L;
        }
        return Math.round(normalized);
    }

    private String buildVisibleRanksSummary(List<RankRowData> visibleRows) {
        if (visibleRows == null || visibleRows.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        int count = 0;
        for (RankRowData row : visibleRows) {
            if (row == null || row.rank <= 0) {
                continue;
            }
            if (count > 0) {
                sb.append(',');
            }
            sb.append(row.rank);
            count++;
            if (count >= 8) {
                break;
            }
        }
        if (count < visibleRows.size()) {
            sb.append(",...");
        }
        sb.append(']');
        return sb.toString();
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

    private RankFlushResult flushQueuedRankRows(
            String rankType,
            List<LiveRoomRankCsvStore.ContributionCsvRow> contributionRows,
            List<LiveRoomRankCsvStore.CharmCsvRow> charmRows
    ) {
        if (isContributionCollectType(rankType)) {
            LiveRoomRankCsvStore.AppendResult result =
                    LiveRoomRankCsvStore.appendContributionRowsWithResult(this, contributionRows);
            return new RankFlushResult(
                    result.acceptedCount,
                    result.writtenCount,
                    result.duplicateSkippedCount,
                    result.ioSuccess
            );
        }
        LiveRoomRankCsvStore.AppendResult result =
                LiveRoomRankCsvStore.appendCharmRowsWithResult(this, charmRows);
        return new RankFlushResult(
                result.acceptedCount,
                result.writtenCount,
                result.duplicateSkippedCount,
                result.ioSuccess
        );
    }

    private boolean performRankListScroll(
            AccessibilityNodeInfo root,
            String rankType,
            int nextRank,
            int attemptIndex
    ) {
        ClickResult shellResult = performRankListScrollByShellInput(rankType, nextRank, attemptIndex);
        if (shellResult.success) {
            log("榜单上滑执行: mode=shizuku_shell type=" + safeTrim(rankType)
                    + " nextRank=" + nextRank
                    + " detail=" + safeTrim(shellResult.detail));
            return true;
        }
        if (!TextUtils.isEmpty(shellResult.detail) && !"disabled".equals(shellResult.detail)) {
            log("榜单上滑shell未命中，回退手势: type=" + safeTrim(rankType)
                    + " nextRank=" + nextRank
                    + " detail=" + safeTrim(shellResult.detail));
        }
        ClickResult gestureResult = performRankListScrollGesture(
                root,
                rankType,
                nextRank,
                attemptIndex
        );
        if (gestureResult.success) {
            log("榜单上滑执行: mode=gesture_direct type=" + safeTrim(rankType)
                    + " nextRank=" + nextRank
                    + " detail=" + safeTrim(gestureResult.detail));
            return true;
        }
        log("榜单上滑失败: mode=gesture_direct type=" + safeTrim(rankType)
                + " nextRank=" + nextRank
                + " detail=" + safeTrim(gestureResult.detail));
        return false;
    }

    private ClickResult performRankListScrollByShellInput(
            String rankType,
            int nextRank,
            int attemptIndex
    ) {
        if (!UiComponentConfig.LIVE_ROOM_TASK_RANK_SCROLL_PREFER_SHIZUKU_SHELL) {
            return new ClickResult(false, "disabled");
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return new ClickResult(false, "sdk<23");
        }
        if (!Shizuku.pingBinder()) {
            return new ClickResult(false, "shizuku_binder_unavailable");
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            maybeRequestShizukuPermission();
            return new ClickResult(false, "shizuku_permission_denied");
        }
        int width = getResources() == null ? 0 : getResources().getDisplayMetrics().widthPixels;
        int height = getResources() == null ? 0 : getResources().getDisplayMetrics().heightPixels;
        if (width <= 0 || height <= 0) {
            return new ClickResult(false, "display_invalid");
        }
        int profile = 0;
        float xRatio = UiComponentConfig.LIVE_ROOM_TASK_RANK_SCROLL_SWIPE_X_RATIO;
        float extraDistanceRatio = 0f;
        int x = Math.round(clampFloat(
                width * xRatio,
                1f,
                Math.max(1f, width - 1f)
        ));
        float startYRaw = clampFloat(
                height * UiComponentConfig.LIVE_ROOM_TASK_RANK_SCROLL_SWIPE_START_Y_RATIO,
                1f,
                Math.max(1f, height - 1f)
        );
        float endYRaw = clampFloat(
                height * Math.max(
                        0.05f,
                        UiComponentConfig.LIVE_ROOM_TASK_RANK_SCROLL_SWIPE_END_Y_RATIO - extraDistanceRatio
                ),
                1f,
                Math.max(1f, height - 1f)
        );
        float minDistance = clampFloat(
                height * UiComponentConfig.LIVE_ROOM_TASK_RANK_SCROLL_SWIPE_MIN_DISTANCE_RATIO,
                100f,
                Math.max(120f, height - 1f)
        );
        if (startYRaw <= endYRaw || startYRaw - endYRaw < minDistance) {
            endYRaw = clampFloat(
                    startYRaw - minDistance,
                    1f,
                    Math.max(1f, height - 1f)
            );
        }
        int startY = Math.round(startYRaw);
        int endY = Math.round(endYRaw);
        long duration = Math.max(
                120L,
                UiComponentConfig.LIVE_ROOM_TASK_RANK_SCROLL_SHELL_INPUT_DURATION_MS
        );
        String command = "input swipe "
                + x + " "
                + startY + " "
                + x + " "
                + endY + " "
                + duration;
        try {
            IBinder binder = Shizuku.getBinder();
            if (binder == null || !binder.pingBinder()) {
                return new ClickResult(false, "shizuku_binder_dead");
            }
            IShizukuService service = IShizukuService.Stub.asInterface(binder);
            if (service == null) {
                return new ClickResult(false, "shizuku_service_null");
            }
            IRemoteProcess remoteProcess = service.newProcess(
                    new String[]{"sh", "-c", command},
                    null,
                    null
            );
            if (remoteProcess == null) {
                return new ClickResult(false, "shizuku_remote_process_null");
            }
            InputStream outStream = null;
            InputStream errStream = null;
            try {
                ParcelFileDescriptor outFd = remoteProcess.getInputStream();
                if (outFd != null) {
                    outStream = new ParcelFileDescriptor.AutoCloseInputStream(outFd);
                }
            } catch (Throwable ignore) {
                outStream = null;
            }
            try {
                ParcelFileDescriptor errFd = remoteProcess.getErrorStream();
                if (errFd != null) {
                    errStream = new ParcelFileDescriptor.AutoCloseInputStream(errFd);
                }
            } catch (Throwable ignore) {
                errStream = null;
            }
            int code = remoteProcess.waitFor();
            String err = readProcessStream(errStream);
            String out = readProcessStream(outStream);
            try {
                remoteProcess.destroy();
            } catch (Throwable ignore) {
            }
            if (code == 0) {
                return new ClickResult(true, "cmd_ok profile=" + profile
                        + " x=" + x
                        + " startY=" + startY
                        + " endY=" + endY
                        + " out=" + safeTrim(out));
            }
            return new ClickResult(false, "cmd_exit_" + code
                    + " err=" + safeTrim(err)
                    + " out=" + safeTrim(out));
        } catch (Throwable e) {
            return new ClickResult(false, "shizuku_exception:" + safeTrim(e.toString()));
        }
    }

    private void maybeRequestShizukuPermission() {
        long now = SystemClock.uptimeMillis();
        if (now - lastShizukuPermissionRequestAt < SHIZUKU_PERMISSION_REQUEST_INTERVAL_MS) {
            return;
        }
        lastShizukuPermissionRequestAt = now;
        try {
            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE);
            log("Shizuku授权请求已发起: requestCode=" + SHIZUKU_PERMISSION_REQUEST_CODE);
        } catch (Throwable e) {
            log("Shizuku授权请求失败: " + e);
        }
    }

    private String readProcessStream(InputStream inputStream) {
        if (inputStream == null) {
            return "";
        }
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(inputStream));
            StringBuilder sb = new StringBuilder();
            String line;
            int lineCount = 0;
            while ((line = reader.readLine()) != null && lineCount < 6) {
                if (sb.length() > 0) {
                    sb.append(" | ");
                }
                sb.append(line);
                lineCount++;
            }
            return sb.toString();
        } catch (Throwable ignore) {
            return "";
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    private ClickResult performRankListScrollByAction(AccessibilityNodeInfo root) {
        if (root == null) {
            return new ClickResult(false, "root_missing");
        }
        AccessibilityNodeInfo scrollNode = findRankScrollableNode(root);
        if (scrollNode == null) {
            ClickResult rootResult = performRootScrollFallback(root);
            if (rootResult.success) {
                return new ClickResult(true, "scroll_node_missing_root_fallback " + safeTrim(rootResult.detail));
            }
            return new ClickResult(false, "scroll_node_missing|" + safeTrim(rootResult.detail));
        }
        boolean actionDone = false;
        String usedAction = "";
        String actionState = buildNodeScrollActionState(scrollNode);
        log("榜单上滑动作节点: " + describeNodeForLog(scrollNode)
                + " actionState=" + actionState);
        try {
            if (!actionDone && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                int actionDown = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.getId();
                if (hasAccessibilityAction(scrollNode, actionDown)) {
                    actionDone = scrollNode.performAction(actionDown);
                    if (actionDone) {
                        usedAction = "ACTION_SCROLL_DOWN";
                    }
                }
                if (!actionDone) {
                    actionDone = scrollNode.performAction(actionDown);
                    if (actionDone) {
                        usedAction = "ACTION_SCROLL_DOWN(force)";
                    }
                }
            }
            if (!actionDone && canUseForwardAsVertical(scrollNode)) {
                actionDone = scrollNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
                if (actionDone) {
                    usedAction = "ACTION_SCROLL_FORWARD(vertical)";
                }
                if (!actionDone) {
                    actionDone = scrollNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
                    if (actionDone) {
                        usedAction = "ACTION_SCROLL_FORWARD(vertical_force)";
                    }
                }
            }
            if (actionDone) {
                return new ClickResult(
                        true,
                        "node=" + describeNodeForLog(scrollNode)
                                + " action=" + safeTrim(usedAction)
                                + " actionState=" + actionState
                );
            }
            return new ClickResult(
                    false,
                    "perform_action_false node=" + describeNodeForLog(scrollNode)
                            + " actionState=" + actionState
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

    private ClickResult performRootScrollFallback(AccessibilityNodeInfo root) {
        if (root == null) {
            return new ClickResult(false, "root_missing");
        }
        try {
            String actionState = buildNodeScrollActionState(root);
            boolean ok = false;
            String action = "";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                int actionDown = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.getId();
                if (hasAccessibilityAction(root, actionDown)) {
                    ok = root.performAction(actionDown);
                    action = "ACTION_SCROLL_DOWN";
                }
            }
            if (!ok && hasAccessibilityAction(root, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                ok = root.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
                action = "ACTION_SCROLL_FORWARD";
            }
            if (ok) {
                log("榜单上滑根节点兜底成功: action=" + safeTrim(action)
                        + " actionState=" + actionState);
                return new ClickResult(true, "root_fallback_" + safeTrim(action));
            }
            log("榜单上滑根节点兜底失败: actionState=" + actionState);
            return new ClickResult(false, "root_fallback_false actionState=" + actionState);
        } catch (Throwable e) {
            log("榜单上滑根节点兜底异常: " + e);
            return new ClickResult(false, "root_fallback_exception:" + safeTrim(e.toString()));
        }
    }

    private AccessibilityNodeInfo findRankScrollableNode(AccessibilityNodeInfo root) {
        if (root == null) {
            return null;
        }
        AccessibilityNodeInfo rankRelatedNode = findRankRowRelatedScrollableNode(root);
        if (rankRelatedNode != null) {
            return rankRelatedNode;
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
                if (isNodeVisible(current) && supportsVerticalScrollAction(current)) {
                    Rect rect = new Rect();
                    current.getBoundsInScreen(rect);
                    long area = Math.max(0, rect.width()) * Math.max(0, rect.height());
                    String className = safeTrim(current.getClassName());
                    long bonus = 0L;
                    if (className.contains("RecyclerView")) {
                        bonus = 5_000_000L;
                    } else if (className.contains("ListView") || className.contains("ScrollView")) {
                        bonus = 3_000_000L;
                    } else if (className.contains("ViewPager")) {
                        bonus = -4_000_000L;
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
        if (bestNode == null) {
            bestNode = findRankRowRelatedScrollableNodeRelaxed(root);
            if (bestNode != null) {
                log("榜单上滑节点回退命中: " + describeNodeForLog(bestNode)
                        + " actionState=" + buildNodeScrollActionState(bestNode));
            }
        }
        return bestNode;
    }

    private AccessibilityNodeInfo findRankRowRelatedScrollableNode(AccessibilityNodeInfo root) {
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
                if (isRankRowCandidateNode(current)) {
                    AccessibilityNodeInfo parent = null;
                    try {
                        parent = current.getParent();
                    } catch (Throwable ignore) {
                        parent = null;
                    }
                    int depth = 0;
                    while (parent != null && depth < 8) {
                        AccessibilityNodeInfo nextParent = null;
                        try {
                            nextParent = parent.getParent();
                        } catch (Throwable ignore) {
                            nextParent = null;
                        }
                        if (isNodeVisible(parent) && supportsVerticalScrollAction(parent)) {
                            Rect rect = new Rect();
                            parent.getBoundsInScreen(rect);
                            long area = Math.max(0, rect.width()) * Math.max(0, rect.height());
                            String className = safeTrim(parent.getClassName());
                            long bonus = 6_000_000L;
                            if (className.contains("RecyclerView")) {
                                bonus += 3_000_000L;
                            } else if (className.contains("ListView") || className.contains("ScrollView")) {
                                bonus += 2_000_000L;
                            } else if (className.contains("ViewPager")) {
                                bonus -= 5_000_000L;
                            }
                            long score = area + bonus - depth * 100_000L;
                            if (score > bestScore) {
                                safeRecycle(bestNode);
                                bestNode = AccessibilityNodeInfo.obtain(parent);
                                bestScore = score;
                            }
                        }
                        safeRecycle(parent);
                        parent = nextParent;
                        depth++;
                    }
                    safeRecycle(parent);
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

    private AccessibilityNodeInfo findRankRowRelatedScrollableNodeRelaxed(AccessibilityNodeInfo root) {
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
                if (isRankRowCandidateNode(current)) {
                    AccessibilityNodeInfo parent = null;
                    try {
                        parent = current.getParent();
                    } catch (Throwable ignore) {
                        parent = null;
                    }
                    int depth = 0;
                    while (parent != null && depth < 10) {
                        AccessibilityNodeInfo nextParent = null;
                        try {
                            nextParent = parent.getParent();
                        } catch (Throwable ignore) {
                            nextParent = null;
                        }
                        if (isNodeVisible(parent) && supportsAnyScrollAction(parent)) {
                            Rect rect = new Rect();
                            parent.getBoundsInScreen(rect);
                            long area = Math.max(0, rect.width()) * Math.max(0, rect.height());
                            String className = safeTrim(parent.getClassName());
                            boolean downUp = hasVerticalDirectionalAction(parent);
                            boolean leftRight = hasHorizontalDirectionalAction(parent);
                            long bonus = 1_000_000L;
                            if (className.contains("RecyclerView")) {
                                bonus += 3_000_000L;
                            }
                            if (downUp) {
                                bonus += 2_000_000L;
                            }
                            if (leftRight) {
                                bonus -= 2_000_000L;
                            }
                            long score = area + bonus - depth * 120_000L;
                            if (score > bestScore) {
                                safeRecycle(bestNode);
                                bestNode = AccessibilityNodeInfo.obtain(parent);
                                bestScore = score;
                            }
                        }
                        safeRecycle(parent);
                        parent = nextParent;
                        depth++;
                    }
                    safeRecycle(parent);
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

    private boolean supportsVerticalScrollAction(AccessibilityNodeInfo node) {
        if (node == null) {
            return false;
        }
        if (hasAnyVerticalScrollAction(node)) {
            return true;
        }
        boolean scrollable = false;
        try {
            scrollable = node.isScrollable();
        } catch (Throwable ignore) {
            scrollable = false;
        }
        if (!scrollable) {
            return false;
        }
        return isLikelyVerticalScrollClass(safeTrim(node.getClassName()));
    }

    private boolean hasAnyVerticalScrollAction(AccessibilityNodeInfo node) {
        if (node == null) {
            return false;
        }
        String className = safeTrim(node.getClassName());
        boolean verticalClass = isLikelyVerticalScrollClass(className);
        if (hasVerticalDirectionalAction(node)) {
            return true;
        }
        boolean hasForwardBackward = hasAccessibilityAction(node, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                || hasAccessibilityAction(node, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
        if (!hasForwardBackward) {
            return false;
        }
        if (!hasHorizontalDirectionalAction(node)) {
            return true;
        }
        if (verticalClass && (hasAccessibilityAction(node, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                || hasAccessibilityAction(node, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD))) {
            return true;
        }
        return false;
    }

    private boolean isLikelyVerticalScrollClass(String className) {
        String cls = safeTrim(className);
        if (TextUtils.isEmpty(cls)) {
            return false;
        }
        return cls.contains("RecyclerView")
                || cls.contains("ListView")
                || cls.contains("ScrollView")
                || cls.contains("NestedScrollView")
                || cls.contains("AbsListView");
    }

    private boolean canUseForwardAsVertical(AccessibilityNodeInfo node) {
        if (node == null) {
            return false;
        }
        if (!hasAccessibilityAction(node, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
            return false;
        }
        if (isLikelyVerticalScrollClass(safeTrim(node.getClassName()))) {
            return true;
        }
        return !hasHorizontalDirectionalAction(node);
    }

    private boolean supportsAnyScrollAction(AccessibilityNodeInfo node) {
        if (node == null) {
            return false;
        }
        if (hasAnyVerticalScrollAction(node)) {
            return true;
        }
        boolean scrollable = false;
        try {
            scrollable = node.isScrollable();
        } catch (Throwable ignore) {
            scrollable = false;
        }
        return scrollable
                || hasAccessibilityAction(node, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                || hasAccessibilityAction(node, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
    }

    private boolean hasVerticalDirectionalAction(AccessibilityNodeInfo node) {
        if (node == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return false;
        }
        return hasAccessibilityAction(
                node,
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.getId()
        ) || hasAccessibilityAction(
                node,
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.getId()
        );
    }

    private boolean hasHorizontalDirectionalAction(AccessibilityNodeInfo node) {
        if (node == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return false;
        }
        return hasAccessibilityAction(
                node,
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.getId()
        ) || hasAccessibilityAction(
                node,
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.getId()
        );
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

    private ClickResult performRankListScrollGesture(
            AccessibilityNodeInfo root,
            String rankType,
            int nextRank,
            int attemptIndex
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            log("榜单上滑跳过: sdk<24");
            return new ClickResult(false, "sdk<24");
        }
        int width = getResources() == null ? 0 : getResources().getDisplayMetrics().widthPixels;
        int height = getResources() == null ? 0 : getResources().getDisplayMetrics().heightPixels;
        if (width <= 0 || height <= 0) {
            log("榜单上滑跳过: display_invalid width=" + width + " height=" + height);
            return new ClickResult(false, "display_invalid");
        }
        int profile = 0;
        float xRatio = UiComponentConfig.LIVE_ROOM_TASK_RANK_SCROLL_SWIPE_X_RATIO;
        float extraDistanceRatio = 0f;
        float x = clampFloat(
                width * xRatio,
                1f,
                Math.max(1f, width - 1f)
        );
        float startY = clampFloat(
                height * UiComponentConfig.LIVE_ROOM_TASK_RANK_SCROLL_SWIPE_START_Y_RATIO,
                1f,
                Math.max(1f, height - 1f)
        );
        float endY = clampFloat(
                height * Math.max(
                        0.05f,
                        UiComponentConfig.LIVE_ROOM_TASK_RANK_SCROLL_SWIPE_END_Y_RATIO - extraDistanceRatio
                ),
                1f,
                Math.max(1f, height - 1f)
        );
        float minDistance = clampFloat(
                height * UiComponentConfig.LIVE_ROOM_TASK_RANK_SCROLL_SWIPE_MIN_DISTANCE_RATIO,
                80f,
                Math.max(120f, height - 1f)
        );
        if (startY <= endY) {
            endY = clampFloat(
                    startY - minDistance,
                    1f,
                    Math.max(1f, height - 1f)
            );
        } else if (startY - endY < minDistance) {
            endY = clampFloat(
                    startY - minDistance,
                    1f,
                    Math.max(1f, height - 1f)
            );
        }
        Path path = new Path();
        path.moveTo(x, startY);
        path.lineTo(x, endY);
        log("榜单上滑执行: mode=gesture type=" + safeTrim(rankType)
                + " nextRank=" + nextRank
                + " attemptIndex=" + Math.max(0, attemptIndex)
                + " profile=" + profile
                + " width=" + width
                + " height=" + height
                + " xRatio=" + xRatio
                + " x=" + x
                + " startY=" + startY
                + " endY=" + endY
                + " directNoNode=true");
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(
                        path,
                        0L,
                        Math.max(120L, UiComponentConfig.LIVE_ROOM_TASK_RANK_SCROLL_GESTURE_DURATION_MS)
                );
        int targetDisplayId = resolveNodeDisplayId(root);
        int serviceDisplayId = -1;
        try {
            serviceDisplayId = getDisplay() == null ? -1 : getDisplay().getDisplayId();
        } catch (Throwable ignore) {
            serviceDisplayId = -1;
        }
        GestureDescription.Builder gestureBuilder = new GestureDescription.Builder()
                .addStroke(stroke);
        boolean gestureDisplaySet = false;
        if (UiComponentConfig.LIVE_ROOM_TASK_RANK_SCROLL_GESTURE_USE_ROOT_DISPLAY_ID
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && targetDisplayId >= 0) {
            try {
                gestureBuilder.setDisplayId(targetDisplayId);
                gestureDisplaySet = true;
            } catch (Throwable e) {
                gestureDisplaySet = false;
                log("榜单上滑手势绑定显示屏失败: targetDisplayId=" + targetDisplayId
                        + " error=" + safeTrim(e.toString()));
            }
        }
        GestureDescription gesture = gestureBuilder.build();
        log("榜单上滑手势显示屏: targetDisplayId=" + targetDisplayId
                + " serviceDisplayId=" + serviceDisplayId
                + " displaySet=" + gestureDisplaySet);
        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] completed = new boolean[] { false };
        final boolean[] cancelled = new boolean[] { false };
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
                            cancelled[0] = true;
                            log("榜单上滑回调: cancelled");
                            latch.countDown();
                        }
                    },
                    handler
            );
        } catch (Throwable ignore) {
            dispatched = false;
        }
        if (!dispatched) {
            log("榜单上滑失败: dispatchGesture=false");
            return new ClickResult(false, "dispatchGesture=false");
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
            return new ClickResult(false, "await_interrupted");
        }
        if (!awaitDone) {
            log("榜单上滑回调超时，按已派发手势继续验证: await_timeout");
            return new ClickResult(true, "dispatch_ok_callback_timeout");
        }
        if (cancelled[0]) {
            return new ClickResult(false, "gesture_cancelled");
        }
        log("榜单上滑结果: success=" + completed[0]);
        return new ClickResult(completed[0], completed[0] ? "gesture_completed" : "gesture_not_completed");
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

    private int resolveNodeDisplayId(AccessibilityNodeInfo node) {
        if (node == null) {
            return -1;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return -1;
        }
        AccessibilityWindowInfo window = null;
        try {
            window = node.getWindow();
            if (window == null) {
                return -1;
            }
            return window.getDisplayId();
        } catch (Throwable ignore) {
            return -1;
        } finally {
            if (window != null) {
                try {
                    window.recycle();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    private String buildNodeScrollActionState(AccessibilityNodeInfo node) {
        if (node == null) {
            return "node_null";
        }
        boolean scrollable = false;
        try {
            scrollable = node.isScrollable();
        } catch (Throwable ignore) {
            scrollable = false;
        }
        String cls = safeTrim(node.getClassName());
        boolean verticalClass = isLikelyVerticalScrollClass(cls);
        boolean forward = hasAccessibilityAction(node, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
        boolean backward = hasAccessibilityAction(node, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
        boolean down = false;
        boolean up = false;
        boolean left = false;
        boolean right = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            down = hasAccessibilityAction(
                    node,
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.getId()
            );
            up = hasAccessibilityAction(
                    node,
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.getId()
            );
            left = hasAccessibilityAction(
                    node,
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.getId()
            );
            right = hasAccessibilityAction(
                    node,
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.getId()
            );
        }
        return "scrollable=" + scrollable
                + ",class=" + cls
                + ",verticalClass=" + verticalClass
                + ",forward=" + forward
                + ",backward=" + backward
                + ",down=" + down
                + ",up=" + up
                + ",left=" + left
                + ",right=" + right;
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
        return waitSlidingContainerGone(-1L, stage);
    }

    private boolean waitSlidingContainerGone(long requestId, String stage) {
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
        while (shouldContinueRankCollect(requestId)) {
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
        if (rect.width() <= 0 || rect.height() <= 0) {
            return false;
        }
        return isRectInCurrentScreen(rect);
    }

    private boolean isRectInCurrentScreen(Rect rect) {
        if (rect == null) {
            return false;
        }
        int screenWidth = 0;
        int screenHeight = 0;
        try {
            screenWidth = getResources().getDisplayMetrics().widthPixels;
            screenHeight = getResources().getDisplayMetrics().heightPixels;
        } catch (Throwable ignore) {
            screenWidth = 0;
            screenHeight = 0;
        }
        if (screenWidth <= 0 || screenHeight <= 0) {
            return rect.width() > 0 && rect.height() > 0;
        }
        return rect.right > 0
                && rect.bottom > 0
                && rect.left < screenWidth
                && rect.top < screenHeight;
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

    private boolean isRankCollectRequestActive(long requestId) {
        if (requestId <= 0L) {
            return true;
        }
        synchronized (rankCollectLock) {
            return activeRankCollectRequestId == requestId;
        }
    }

    private boolean shouldContinueRankCollect(long requestId) {
        if (requestId > 0L && !isRankCollectRequestActive(requestId)) {
            return false;
        }
        return isEngineRunningForRankCollect();
    }

    private boolean isEngineRunningForRankCollect() {
        ModuleSettings.EngineStatus appStatus = ModuleSettings.EngineStatus.STOPPED;
        ModuleSettings.EngineStatus xspStatus = ModuleSettings.EngineStatus.STOPPED;
        try {
            appStatus = ModuleSettings.getEngineStatus(ModuleSettings.appPrefs(this));
        } catch (Throwable ignore) {
        }
        try {
            xspStatus = ModuleSettings.getEngineStatus();
        } catch (Throwable ignore) {
        }
        boolean running = appStatus == ModuleSettings.EngineStatus.RUNNING
                || xspStatus == ModuleSettings.EngineStatus.RUNNING;
        if (!running) {
            log("榜单采集引擎状态未运行: appStatus=" + appStatus + " xspStatus=" + xspStatus);
        }
        return running;
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

    private static final class RankFlushResult {
        final int acceptedCount;
        final int writtenCount;
        final int duplicateSkippedCount;
        final boolean ioSuccess;

        RankFlushResult(
                int acceptedCount,
                int writtenCount,
                int duplicateSkippedCount,
                boolean ioSuccess
        ) {
            this.acceptedCount = Math.max(0, acceptedCount);
            this.writtenCount = Math.max(0, writtenCount);
            this.duplicateSkippedCount = Math.max(0, duplicateSkippedCount);
            this.ioSuccess = ioSuccess;
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

    private static final class RankVisibleRow {
        final int top;
        final int rank;
        final String name;

        RankVisibleRow(int top, int rank, String name) {
            this.top = top;
            this.rank = Math.max(0, rank);
            this.name = safeTrim(name);
        }
    }

    private static final class RankScanResult {
        final RankRowMatch match;
        final int maxRank;
        final String pageSignature;
        final List<RankRowData> visibleRows;

        RankScanResult(
                RankRowMatch match,
                int maxRank,
                String pageSignature,
                List<RankRowData> visibleRows
        ) {
            this.match = match;
            this.maxRank = Math.max(0, maxRank);
            this.pageSignature = safeTrim(pageSignature);
            if (visibleRows == null || visibleRows.isEmpty()) {
                this.visibleRows = Collections.emptyList();
            } else {
                this.visibleRows = new ArrayList<RankRowData>(visibleRows);
            }
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
