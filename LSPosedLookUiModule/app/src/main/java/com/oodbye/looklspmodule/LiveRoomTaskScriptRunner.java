package com.oodbye.looklspmodule;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.List;

import de.robv.android.xposed.XposedBridge;

final class LiveRoomTaskScriptRunner {
    private static final String TAG = "[LOOKScriptRunner]";
    private static final int VIEW_TREE_DUMP_MAX_NODES = 320;
    private static final int VIEW_TREE_DUMP_MAX_DEPTH = 18;
    private static final int VIEW_TREE_DUMP_MAX_TEXT_LENGTH = 80;

    interface TaskFinishListener {
        void onTaskFinished(String reason);
    }

    private LiveRoomTaskScriptRunner() {
    }

    static long runLiveRoomEnterTask(
            final Activity activity,
            String homeId,
            long enterTimeMs,
            TaskFinishListener listener
    ) {
        final long startedAt = System.currentTimeMillis();
        final TaskContext taskContext = new TaskContext(
                startedAt,
                safeTrim(homeId),
                Math.max(0L, enterTimeMs),
                listener
        );
        long configuredWaitMs = Math.max(0L, UiComponentConfig.LIVE_ROOM_ENTER_TASK_WAIT_MS);
        long flowWaitMs = Math.max(configuredWaitMs, UiComponentConfig.LIVE_ROOM_TASK_MIN_RETURN_DELAY_MS);
        String taskName = UiComponentConfig.LIVE_ROOM_ENTER_TASK_NAME;
        log(activity, "task=" + taskName + " start, wait=" + flowWaitMs + "ms");
        if (activity == null) {
            log(activity, "task=" + taskName + " skip: activity_null");
            finishTask(activity, taskContext, "activity_null");
            log(activity, "task=" + taskName + " done, wait=" + flowWaitMs + "ms");
            return flowWaitMs;
        }
        if (!isTaskExecutionAllowed(activity)) {
            log(activity, "task=" + taskName + " skip: engine_not_running_or_activity_invalid");
            finishTask(activity, taskContext, "engine_not_running_or_activity_invalid");
            log(activity, "task=" + taskName + " done, wait=" + flowWaitMs + "ms");
            return flowWaitMs;
        }
        scheduleFindVFlipperAndRun(activity, taskContext, startedAt, 1);
        log(activity, "task=" + taskName + " sequence scheduled");
        log(activity, "task=" + taskName + " done, wait=" + flowWaitMs + "ms");
        return flowWaitMs;
    }

    private static void scheduleFindVFlipperAndRun(
            final Activity activity,
            final TaskContext taskContext,
            final long startedAt,
            final int findAttempt
    ) {
        if (!isTaskExecutionAllowed(activity)) {
            return;
        }
        View root = resolveRootView(activity);
        if (root == null) {
            scheduleFindRetry(activity, taskContext, startedAt, findAttempt, "root_view_missing");
            return;
        }
        PanelState panelState = resolvePanelState();
        if (isPanelReady(panelState)) {
            log(
                    activity,
                    "task=live_room_enter_task vflipper panel detect: opened=true "
                            + "detail=" + buildPanelMatchDetail(panelState)
            );
            startRankSequence(activity, taskContext, panelState);
            return;
        }
        View vflipperNode = findFirstNode(root, UiComponentConfig.LIVE_ROOM_TASK_VFLIPPER_NODE);
        if (vflipperNode == null) {
            scheduleFindRetry(activity, taskContext, startedAt, findAttempt, "vflipper_missing");
            return;
        }
        log(
                activity,
                "task=live_room_enter_task click vflipper start, attempt=" + findAttempt
        );
        ClickResult clickResult = clickWithParentFallbackDetailed(activity, vflipperNode, "vflipper");
        if (!clickResult.success) {
            scheduleFindRetry(activity, taskContext, startedAt, findAttempt, "vflipper_click_failed");
            return;
        }
        log(
                activity,
                "task=live_room_enter_task vflipper clicked, attempt=" + findAttempt
                        + " detail=" + clickResult.detail
        );
        boolean posted = postOnUi(
                activity,
                new Runnable() {
                    @Override
                    public void run() {
                        verifyPanelAndStartRankSequence(
                                activity,
                                taskContext,
                                startedAt,
                                findAttempt,
                                1
                        );
                    }
                },
                UiComponentConfig.LIVE_ROOM_TASK_WAIT_AFTER_VFLIPPER_CLICK_MS
        );
        if (!posted) {
            finishTask(activity, taskContext, "post_after_vflipper_click_failed");
        }
    }

    private static void verifyPanelAndStartRankSequence(
            final Activity activity,
            final TaskContext taskContext,
            final long startedAt,
            final int findAttempt,
            final int panelRetryIndex
    ) {
        if (!isTaskExecutionAllowed(activity)) {
            return;
        }
        View root = resolveRootView(activity);
        PanelState panelState = resolvePanelState();
        boolean blockedBySlidingContainer = isPanelDetectionBlockedBySlidingContainer(root);
        if (panelRetryIndex == 1) {
            maybeDumpViewTree(activity, root, "after_vflipper_click_wait");
        }
        int maxRetry = Math.max(0, UiComponentConfig.LIVE_ROOM_TASK_PANEL_READY_MAX_RETRY);
        if (blockedBySlidingContainer) {
            if (panelRetryIndex >= maxRetry) {
                maybeDumpViewTree(activity, root, "panel_detect_block_timeout");
                scheduleFindRetry(
                        activity,
                        taskContext,
                        startedAt,
                        findAttempt,
                        "panel_detect_block_visible:"
                                + UiComponentConfig.resourceEntryFromFullId(
                                UiComponentConfig.LIVE_ROOM_TASK_PANEL_DETECT_BLOCK_NODE.fullResourceId
                        )
                );
                return;
            }
            final int nextRetry = panelRetryIndex + 1;
            if (panelRetryIndex <= 2 || panelRetryIndex % 4 == 0) {
                String blockNodeId = safeTrim(
                        UiComponentConfig.LIVE_ROOM_TASK_PANEL_DETECT_BLOCK_NODE.fullResourceId
                );
                log(
                        activity,
                        "task=live_room_enter_task wait panel detect block, retry="
                                + panelRetryIndex + "/" + maxRetry
                                + " node=" + blockNodeId
                );
            }
            boolean posted = postOnUi(
                    activity,
                    new Runnable() {
                        @Override
                        public void run() {
                            verifyPanelAndStartRankSequence(
                                    activity,
                                    taskContext,
                                    startedAt,
                                    findAttempt,
                                    nextRetry
                            );
                        }
                    },
                    UiComponentConfig.LIVE_ROOM_TASK_PANEL_READY_RETRY_INTERVAL_MS
            );
            if (!posted) {
                finishTask(activity, taskContext, "post_wait_panel_detect_block_failed");
            }
            return;
        }
        if (panelRetryIndex == 1) {
            log(
                    activity,
                    "task=live_room_enter_task panel detect block inactive, continue panel check"
            );
        }
        if (panelRetryIndex == 1 || panelRetryIndex % 4 == 0) {
            log(
                    activity,
                    "task=live_room_enter_task vflipper panel detect: opened="
                            + isPanelReady(panelState)
                            + " retry=" + panelRetryIndex + "/" + maxRetry
                            + " detail=" + buildPanelMatchDetail(panelState)
            );
        }
        if (isPanelReady(panelState)) {
            log(
                    activity,
                    "task=live_room_enter_task vflipper panel detect: opened=true "
                            + "detail=" + buildPanelMatchDetail(panelState)
            );
            maybeDumpViewTree(activity, root, "panel_ready_before_rank_sequence");
            startRankSequence(activity, taskContext, panelState);
            return;
        }
        if (panelRetryIndex >= maxRetry) {
            maybeDumpViewTree(activity, root, "panel_not_ready_timeout");
            scheduleFindRetry(
                    activity,
                    taskContext,
                    startedAt,
                    findAttempt,
                    "panel_not_ready_after_click(marker=" + panelState.markerCount
                            + ", primary=" + panelState.primaryCount
                            + ", detail=" + panelState.detail + ")"
            );
            return;
        }
        final int nextRetry = panelRetryIndex + 1;
        if (panelRetryIndex <= 2 || panelRetryIndex % 4 == 0) {
            log(
                    activity,
                    "task=live_room_enter_task wait panel ready, retry="
                            + panelRetryIndex + "/" + maxRetry
                            + " markerCount=" + panelState.markerCount
                            + " primaryCount=" + panelState.primaryCount
                            + " detail=" + buildPanelMatchDetail(panelState)
            );
        }
        boolean posted = postOnUi(
                activity,
                new Runnable() {
                    @Override
                    public void run() {
                        verifyPanelAndStartRankSequence(
                                activity,
                                taskContext,
                                startedAt,
                                findAttempt,
                                nextRetry
                        );
                    }
                },
                UiComponentConfig.LIVE_ROOM_TASK_PANEL_READY_RETRY_INTERVAL_MS
        );
        if (!posted) {
            finishTask(activity, taskContext, "post_wait_panel_ready_failed");
        }
    }

    private static boolean isPanelDetectionBlockedBySlidingContainer(View root) {
        if (root == null) {
            return false;
        }
        View blockNode = findFirstNode(
                root,
                UiComponentConfig.LIVE_ROOM_TASK_PANEL_DETECT_BLOCK_NODE
        );
        if (blockNode == null || !blockNode.isShown()) {
            return false;
        }
        return hasUsableVisibleBounds(blockNode);
    }

    private static void startRankSequence(
            final Activity activity,
            final TaskContext taskContext,
            PanelState panelState
    ) {
        if (!isTaskExecutionAllowed(activity)) {
            return;
        }
        int markerCount = panelState == null ? 0 : panelState.markerCount;
        int primaryCount = panelState == null ? 0 : panelState.primaryCount;
        log(
                activity,
                "task=live_room_enter_task panel entered, markerCount="
                        + markerCount + " primaryCount=" + primaryCount
        );
        if (isCharmCollected(taskContext)) {
            requestClosePanel(activity, taskContext, 0);
            return;
        }
        if (isContributionCollected(taskContext)) {
            clickCharmAndCollect(activity, taskContext);
            return;
        }
        clickRankThenCollectWithRetry(
                activity,
                taskContext,
                ModuleSettings.A11Y_PANEL_CLICK_TARGET_CONTRIBUTION,
                ModuleSettings.A11Y_RANK_COLLECT_TYPE_CONTRIBUTION,
                "贡献榜",
                ModuleSettings.getContributionRankLoopCount(),
                1,
                new Runnable() {
                    @Override
                    public void run() {
                        markContributionCollected(taskContext);
                        clickCharmAndCollect(activity, taskContext);
                    }
                }
        );
    }

    private static void clickCharmAndCollect(final Activity activity, final TaskContext taskContext) {
        if (!isTaskExecutionAllowed(activity)) {
            return;
        }
        clickRankThenCollectWithRetry(
                activity,
                taskContext,
                ModuleSettings.A11Y_PANEL_CLICK_TARGET_CHARM,
                ModuleSettings.A11Y_RANK_COLLECT_TYPE_CHARM,
                "魅力榜",
                ModuleSettings.getCharmRankLoopCount(),
                1,
                new Runnable() {
                    @Override
                    public void run() {
                        markCharmCollected(taskContext);
                        triggerAiConsumptionAnalysis(activity, taskContext);
                        requestClosePanel(activity, taskContext, 0);
                    }
                }
        );
    }

    private static void clickRankThenCollectWithRetry(
            final Activity activity,
            final TaskContext taskContext,
            final String rankClickTarget,
            final String rankType,
            final String rankName,
            final int targetCount,
            final int clickRetryIndex,
            final Runnable onDone
    ) {
        if (!isTaskExecutionAllowed(activity)) {
            return;
        }
        requestRankTabClickAsync(
                activity,
                rankClickTarget,
                rankName,
                new RankTabClickCallback() {
                    @Override
                    public void onResult(boolean clicked) {
                        if (!isTaskExecutionAllowed(activity)) {
                            return;
                        }
                        if (!clicked) {
                            int maxRetry = Math.max(
                                    1,
                                    UiComponentConfig.LIVE_ROOM_TASK_RANK_TAB_CLICK_MAX_RETRY
                            );
                            if (clickRetryIndex <= 2 || clickRetryIndex % 3 == 0) {
                                log(
                                        activity,
                                        "task=live_room_enter_task rank click retry scheduled: "
                                                + safeTrim(rankName)
                                                + " retry=" + clickRetryIndex + "/" + maxRetry
                                );
                            }
                            if (clickRetryIndex >= maxRetry) {
                                final PanelState panelState = resolvePanelState();
                                final boolean panelReady = isPanelReady(panelState);
                                log(
                                        activity,
                                        "task=live_room_enter_task rank click retry threshold reached: "
                                                + safeTrim(rankName)
                                                + " retry=" + clickRetryIndex + "/" + maxRetry
                                                + " panelReady=" + panelReady
                                                + " snapshot=" + buildPanelMatchDetail(panelState)
                                );
                                if (panelReady) {
                                    postStartRankCollect(
                                            activity,
                                            taskContext,
                                            rankType,
                                            rankName,
                                            targetCount,
                                            onDone
                                    );
                                } else {
                                    scheduleFindVFlipperAndRun(
                                            activity,
                                            taskContext,
                                            taskContext.startedAt,
                                            1
                                    );
                                }
                                return;
                            }
                            final int nextRetry = clickRetryIndex + 1;
                            boolean retryPosted = postOnUi(
                                    activity,
                                    new Runnable() {
                                        @Override
                                        public void run() {
                                            clickRankThenCollectWithRetry(
                                                    activity,
                                                    taskContext,
                                                    rankClickTarget,
                                                    rankType,
                                                    rankName,
                                                    targetCount,
                                                    nextRetry,
                                                    onDone
                                            );
                                        }
                                    },
                                    UiComponentConfig.LIVE_ROOM_TASK_RANK_TAB_CLICK_RETRY_INTERVAL_MS
                            );
                            if (!retryPosted) {
                                finishTask(activity, taskContext, "post_rank_tab_click_retry_failed");
                            }
                            return;
                        }
                        postStartRankCollect(
                                activity,
                                taskContext,
                                rankType,
                                rankName,
                                targetCount,
                                onDone
                        );
                    }
                }
        );
    }

    private interface RankTabClickCallback {
        void onResult(boolean clicked);
    }

    private static void requestRankTabClickAsync(
            final Activity activity,
            final String rankClickTarget,
            final String rankName,
            final RankTabClickCallback callback
    ) {
        if (callback == null) {
            return;
        }
        final Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean clicked = clickRankTab(activity, rankClickTarget, rankName);
                boolean posted = postOnUi(
                        activity,
                        new Runnable() {
                            @Override
                            public void run() {
                                callback.onResult(clicked);
                            }
                        },
                        0L
                );
                if (!posted) {
                    callback.onResult(clicked);
                }
            }
        }, "look_rank_tab_click_worker");
        try {
            worker.start();
        } catch (Throwable e) {
            log(activity, "task=live_room_enter_task rank click worker start failed: " + e);
            callback.onResult(false);
        }
    }

    private static void postStartRankCollect(
            final Activity activity,
            final TaskContext taskContext,
            final String rankType,
            final String rankName,
            final int targetCount,
            final Runnable onDone
    ) {
        boolean posted = postOnUi(
                activity,
                new Runnable() {
                    @Override
                    public void run() {
                        startRankCollect(
                                activity,
                                taskContext,
                                rankType,
                                rankName,
                                targetCount,
                                onDone
                        );
                    }
                },
                UiComponentConfig.LIVE_ROOM_TASK_RANK_COLLECT_START_DELAY_MS
        );
        if (!posted) {
            log(
                    activity,
                    "task=live_room_enter_task start rank collect post failed: "
                            + safeTrim(rankName)
            );
            finishTask(activity, taskContext, "post_start_rank_collect_failed");
        }
    }

    private static void startRankCollect(
            final Activity activity,
            final TaskContext taskContext,
            final String rankType,
            final String rankName,
            final int targetCount,
            final Runnable onDone
    ) {
        startRankCollect(
                activity,
                taskContext,
                rankType,
                rankName,
                targetCount,
                onDone,
                1
        );
    }

    private static void startRankCollect(
            final Activity activity,
            final TaskContext taskContext,
            final String rankType,
            final String rankName,
            final int targetCount,
            final Runnable onDone,
            final int dispatchRound
    ) {
        if (!isTaskExecutionAllowed(activity)) {
            return;
        }
        final int safeTargetCount = Math.max(0, targetCount);
        final boolean collectAllRankUsers = ModuleSettings.isCollectAllRankUsersEnabled();
        if (safeTargetCount <= 0 && !collectAllRankUsers) {
            log(
                    activity,
                    "task=live_room_enter_task rank collect skip: " + safeTrim(rankName)
                            + " targetCount=0"
            );
            runNextStep(activity, onDone);
            return;
        }
        long requestId = LookHookEntry.dispatchA11yRankCollectRequestForTaskRunner(
                activity,
                rankType,
                taskContext.homeId,
                taskContext.enterTimeMs,
                safeTargetCount
        );
        if (requestId <= 0L) {
            log(
                    activity,
                    "task=live_room_enter_task rank collect dispatch failed: "
                            + safeTrim(rankName)
                            + " dispatchRound=" + dispatchRound
                            + " targetCount=" + safeTargetCount
            );
            scheduleRankCollectRedispatch(
                    activity,
                    taskContext,
                    rankType,
                    rankName,
                    safeTargetCount,
                    dispatchRound + 1,
                    onDone,
                    "dispatch_failed"
            );
            return;
        }
        log(
                activity,
                "task=live_room_enter_task rank collect dispatched: "
                        + safeTrim(rankName)
                        + " dispatchRound=" + dispatchRound
                        + " requestId=" + requestId
                        + " targetCount=" + safeTargetCount
                        + " collectAll=" + collectAllRankUsers
                        + " homeId=" + safeTrim(taskContext.homeId)
                        + " enterAt=" + taskContext.enterTimeMs
        );
        waitRankCollectResult(
                activity,
                taskContext,
                requestId,
                rankType,
                rankName,
                safeTargetCount,
                SystemClock.uptimeMillis(),
                1,
                dispatchRound,
                onDone
        );
    }

    private static void waitRankCollectResult(
            final Activity activity,
            final TaskContext taskContext,
            final long requestId,
            final String rankType,
            final String rankName,
            final int targetCount,
            final long waitStartedAt,
            final int pollIndex,
            final int dispatchRound,
            final Runnable onDone
    ) {
        if (!isTaskExecutionAllowed(activity)) {
            return;
        }
        LookHookEntry.A11yRankCollectResult result =
                LookHookEntry.consumeA11yRankCollectResultForTaskRunner(requestId);
        if (result != null) {
            captureRankCsvPath(taskContext, rankType, result.detail, activity);
            log(
                    activity,
                    "task=live_room_enter_task rank collect result: "
                            + safeTrim(rankName)
                            + " dispatchRound=" + dispatchRound
                            + " success=" + result.success
                            + " count=" + result.count
                            + " maxRank=" + result.maxRank
                            + " targetCount=" + targetCount
                            + " detail=" + safeTrim(result.detail)
            );
            if (result.success) {
                runNextStep(activity, onDone);
                return;
            }
            String resultDetail = safeTrim(result.detail);
            if (resultDetail.contains("rank_page_not_ready")) {
                String rankClickTarget = resolveRankClickTarget(rankType);
                if (!TextUtils.isEmpty(rankClickTarget)) {
                    log(
                            activity,
                            "task=live_room_enter_task rank collect page_not_ready, reclick tab: "
                                    + safeTrim(rankName)
                                    + " dispatchRound=" + dispatchRound
                                    + " detail=" + resultDetail
                    );
                    clickRankThenCollectWithRetry(
                            activity,
                            taskContext,
                            rankClickTarget,
                            rankType,
                            rankName,
                            targetCount,
                            1,
                            onDone
                    );
                    return;
                }
            }
            scheduleRankCollectRedispatch(
                    activity,
                    taskContext,
                    rankType,
                    rankName,
                    targetCount,
                    dispatchRound + 1,
                    onDone,
                    "result_failed"
            );
            return;
        }
        long elapsed = Math.max(0L, SystemClock.uptimeMillis() - waitStartedAt);
        long timeoutMs = Math.max(
                UiComponentConfig.LIVE_ROOM_TASK_RANK_COLLECT_RESULT_POLL_INTERVAL_MS,
                UiComponentConfig.LIVE_ROOM_TASK_RANK_COLLECT_RESULT_TIMEOUT_MS
        );
        if (elapsed >= timeoutMs) {
            log(
                    activity,
                    "task=live_room_enter_task rank collect timeout: "
                            + safeTrim(rankName)
                            + " dispatchRound=" + dispatchRound
                            + " requestId=" + requestId
                            + " elapsed=" + elapsed + "ms"
                            + " targetCount=" + targetCount
            );
            scheduleRankCollectRedispatch(
                    activity,
                    taskContext,
                    rankType,
                    rankName,
                    targetCount,
                    dispatchRound + 1,
                    onDone,
                    "result_timeout"
            );
            return;
        }
        if (pollIndex <= 2 || pollIndex % 10 == 0) {
            log(
                    activity,
                    "task=live_room_enter_task rank collect waiting: "
                            + safeTrim(rankName)
                            + " dispatchRound=" + dispatchRound
                            + " requestId=" + requestId
                            + " poll=" + pollIndex
                            + " elapsed=" + elapsed + "ms"
            );
        }
        boolean posted = postOnUi(
                activity,
                new Runnable() {
                    @Override
                    public void run() {
                        waitRankCollectResult(
                                activity,
                                taskContext,
                                requestId,
                                rankType,
                                rankName,
                                targetCount,
                                waitStartedAt,
                                pollIndex + 1,
                                dispatchRound,
                                onDone
                        );
                    }
                },
                UiComponentConfig.LIVE_ROOM_TASK_RANK_COLLECT_RESULT_POLL_INTERVAL_MS
        );
        if (!posted) {
            finishTask(activity, taskContext, "post_wait_rank_collect_result_failed");
        }
    }

    private static void scheduleRankCollectRedispatch(
            final Activity activity,
            final TaskContext taskContext,
            final String rankType,
            final String rankName,
            final int targetCount,
            final int nextDispatchRound,
            final Runnable onDone,
            final String reason
    ) {
        if (!isTaskExecutionAllowed(activity)) {
            return;
        }
        int maxRedispatchRound = Math.max(1, ModuleSettings.getSingleRankRetryLimit());
        if (nextDispatchRound > maxRedispatchRound) {
            log(
                    activity,
                    "task=live_room_enter_task rank collect redispatch stop: "
                            + safeTrim(rankName)
                            + " reason=retry_limit_reached"
                            + " nextRound=" + nextDispatchRound
                            + " maxRound=" + maxRedispatchRound
                            + " lastReason=" + safeTrim(reason)
                            + " targetCount=" + targetCount
            );
            finishTask(
                    activity,
                    taskContext,
                    "rank_collect_retry_limit_reached:"
                            + safeTrim(rankName)
                            + "(maxRound=" + maxRedispatchRound + ")"
            );
            return;
        }
        log(
                activity,
                "task=live_room_enter_task rank collect redispatch: "
                        + safeTrim(rankName)
                        + " nextRound=" + nextDispatchRound
                        + " reason=" + safeTrim(reason)
                        + " maxRound=" + maxRedispatchRound
                        + " targetCount=" + targetCount
        );
        boolean posted = postOnUi(
                activity,
                new Runnable() {
                    @Override
                    public void run() {
                        startRankCollect(
                                activity,
                                taskContext,
                                rankType,
                                rankName,
                                targetCount,
                                onDone,
                                nextDispatchRound
                        );
                    }
                },
                UiComponentConfig.LIVE_ROOM_TASK_RANK_COLLECT_REDISPATCH_DELAY_MS
        );
        if (!posted) {
            finishTask(activity, taskContext, "post_rank_collect_redispatch_failed");
        }
    }

    private static String resolveRankClickTarget(String rankType) {
        String type = safeTrim(rankType);
        if (ModuleSettings.A11Y_RANK_COLLECT_TYPE_CONTRIBUTION.equals(type)) {
            return ModuleSettings.A11Y_PANEL_CLICK_TARGET_CONTRIBUTION;
        }
        if (ModuleSettings.A11Y_RANK_COLLECT_TYPE_CHARM.equals(type)) {
            return ModuleSettings.A11Y_PANEL_CLICK_TARGET_CHARM;
        }
        return "";
    }

    private static void runNextStep(Activity activity, Runnable onDone) {
        if (onDone == null) {
            return;
        }
        try {
            onDone.run();
        } catch (Throwable e) {
            log(activity, "task=live_room_enter_task next step exception: " + safeTrim(String.valueOf(e)));
        }
    }

    private static boolean isContributionCollected(TaskContext taskContext) {
        if (taskContext == null) {
            return false;
        }
        synchronized (taskContext.lock) {
            return taskContext.contributionCollected;
        }
    }

    private static boolean isCharmCollected(TaskContext taskContext) {
        if (taskContext == null) {
            return false;
        }
        synchronized (taskContext.lock) {
            return taskContext.charmCollected;
        }
    }

    private static void markContributionCollected(TaskContext taskContext) {
        if (taskContext == null) {
            return;
        }
        synchronized (taskContext.lock) {
            taskContext.contributionCollected = true;
        }
    }

    private static void markCharmCollected(TaskContext taskContext) {
        if (taskContext == null) {
            return;
        }
        synchronized (taskContext.lock) {
            taskContext.charmCollected = true;
        }
    }

    private static void captureRankCsvPath(
            TaskContext taskContext,
            String rankType,
            String detail,
            Activity activity
    ) {
        if (taskContext == null) {
            return;
        }
        String csvPath = extractCsvPathFromResultDetail(detail);
        if (TextUtils.isEmpty(csvPath)) {
            return;
        }
        synchronized (taskContext.lock) {
            String type = safeTrim(rankType);
            if (ModuleSettings.A11Y_RANK_COLLECT_TYPE_CONTRIBUTION.equals(type)) {
                taskContext.contributionCsvPath = csvPath;
            } else if (ModuleSettings.A11Y_RANK_COLLECT_TYPE_CHARM.equals(type)) {
                taskContext.charmCsvPath = csvPath;
            }
        }
        if (activity != null) {
            log(activity,
                    "task=live_room_enter_task rank collect csv captured: type="
                            + safeTrim(rankType)
                            + " csv=" + csvPath);
        }
    }

    private static String extractCsvPathFromResultDetail(String detail) {
        String value = safeTrim(detail);
        if (TextUtils.isEmpty(value)) {
            return "";
        }
        String marker = "|csv=";
        int idx = value.lastIndexOf(marker);
        if (idx < 0) {
            return "";
        }
        return safeTrim(value.substring(idx + marker.length()));
    }

    private static void triggerAiConsumptionAnalysis(Activity activity, TaskContext taskContext) {
        if (taskContext == null || activity == null) {
            log(activity, "task=live_room_enter_task ai analyze skip: context_invalid");
            return;
        }
        String homeId;
        long enterTimeMs;
        String contributionCsv;
        String charmCsv;
        synchronized (taskContext.lock) {
            if (taskContext.aiAnalysisDispatched) {
                log(activity, "task=live_room_enter_task ai analyze skip: already_dispatched");
                return;
            }
            if (!taskContext.contributionCollected || !taskContext.charmCollected) {
                log(activity,
                        "task=live_room_enter_task ai analyze skip: rank_not_ready"
                                + " contributionCollected=" + taskContext.contributionCollected
                                + " charmCollected=" + taskContext.charmCollected);
                return;
            }
            contributionCsv = safeTrim(taskContext.contributionCsvPath);
            charmCsv = safeTrim(taskContext.charmCsvPath);
            if (TextUtils.isEmpty(contributionCsv) || TextUtils.isEmpty(charmCsv)) {
                log(activity,
                        "task=live_room_enter_task ai analyze skip: csv_missing"
                                + " contributionCsv=" + contributionCsv
                                + " charmCsv=" + charmCsv);
                return;
            }
            taskContext.aiAnalysisDispatched = true;
            homeId = safeTrim(taskContext.homeId);
            enterTimeMs = taskContext.enterTimeMs;
        }
        try {
            Context appContext = activity.getApplicationContext();
            if (appContext == null) {
                log(activity, "task=live_room_enter_task ai analyze skip: app_context_null");
                return;
            }
            Intent request = new Intent(ModuleSettings.ACTION_AI_ANALYSIS_REQUEST);
            request.setPackage(ModuleSettings.MODULE_PACKAGE);
            request.putExtra(ModuleSettings.EXTRA_AI_ANALYZE_HOME_ID, homeId);
            request.putExtra(ModuleSettings.EXTRA_AI_ANALYZE_ENTER_TIME, Math.max(0L, enterTimeMs));
            request.putExtra(ModuleSettings.EXTRA_AI_ANALYZE_CONTRIBUTION_CSV, contributionCsv);
            request.putExtra(ModuleSettings.EXTRA_AI_ANALYZE_CHARM_CSV, charmCsv);
            appContext.sendBroadcast(request);
            log(activity,
                    "task=live_room_enter_task ai analyze dispatched: homeId="
                            + homeId
                            + " contributionCsv=" + contributionCsv
                            + " charmCsv=" + charmCsv
                            + " via=module_broadcast");
        } catch (Throwable e) {
            synchronized (taskContext.lock) {
                taskContext.aiAnalysisDispatched = false;
            }
            log(activity,
                    "task=live_room_enter_task ai analyze dispatch failed: "
                            + safeTrim(String.valueOf(e)));
        }
    }

    private static void requestClosePanel(
            final Activity activity,
            final TaskContext taskContext,
            final int backRetryIndex
    ) {
        if (!isTaskExecutionAllowed(activity)) {
            return;
        }
        try {
            activity.onBackPressed();
            log(
                    activity,
                    "task=live_room_enter_task panel close request, retry=" + backRetryIndex
            );
        } catch (Throwable e) {
            log(activity, "task=live_room_enter_task panel close back failed: " + e);
            finishTask(activity, taskContext, "panel_close_back_failed");
            return;
        }
        boolean posted = postOnUi(
                activity,
                new Runnable() {
                    @Override
                    public void run() {
                        verifyPanelClosed(activity, taskContext, backRetryIndex);
                    }
                },
                UiComponentConfig.LIVE_ROOM_TASK_PANEL_CLOSE_CHECK_DELAY_MS
        );
        if (!posted) {
            finishTask(activity, taskContext, "post_verify_panel_close_failed");
        }
    }

    private static void verifyPanelClosed(
            final Activity activity,
            final TaskContext taskContext,
            final int backRetryIndex
    ) {
        if (!isTaskExecutionAllowed(activity)) {
            return;
        }
        PanelState panelState = resolvePanelState();
        if (!isPanelReady(panelState)) {
            int markerCount = panelState == null ? 0 : panelState.markerCount;
            int primaryCount = panelState == null ? 0 : panelState.primaryCount;
            log(
                    activity,
                    "task=live_room_enter_task panel closed confirmed, markerCount="
                            + markerCount + " primaryCount=" + primaryCount
            );
            finishTask(activity, taskContext, "panel_closed_confirmed");
            return;
        }
        int maxRetry = Math.max(0, UiComponentConfig.LIVE_ROOM_TASK_PANEL_CLOSE_MAX_BACK_RETRY);
        int markerCount = panelState == null ? 0 : panelState.markerCount;
        int primaryCount = panelState == null ? 0 : panelState.primaryCount;
        if (backRetryIndex >= maxRetry) {
            log(
                    activity,
                    "task=live_room_enter_task panel still open after close retries, markerCount="
                            + markerCount + " primaryCount=" + primaryCount
                            + " retry=" + backRetryIndex + "/" + maxRetry
            );
            finishTask(activity, taskContext, "panel_close_retry_exhausted");
            return;
        }
        final int nextRetry = backRetryIndex + 1;
        log(
                activity,
                "task=live_room_enter_task panel not closed, retry back "
                        + nextRetry + "/" + maxRetry
                        + " markerCount=" + markerCount
                        + " primaryCount=" + primaryCount
        );
        boolean posted = postOnUi(
                activity,
                new Runnable() {
                    @Override
                    public void run() {
                        requestClosePanel(activity, taskContext, nextRetry);
                    }
                },
                UiComponentConfig.LIVE_ROOM_TASK_PANEL_CLOSE_RETRY_INTERVAL_MS
        );
        if (!posted) {
            finishTask(activity, taskContext, "post_retry_panel_close_failed");
        }
    }

    private static void scheduleFindRetry(
            final Activity activity,
            final TaskContext taskContext,
            final long startedAt,
            final int currentAttempt,
            final String reason
    ) {
        if (!isTaskExecutionAllowed(activity)) {
            return;
        }
        long timeoutMs = Math.max(0L, UiComponentConfig.LIVE_ROOM_TASK_VFLIPPER_FIND_TIMEOUT_MS);
        long elapsed = Math.max(0L, System.currentTimeMillis() - startedAt);
        if (timeoutMs > 0L && elapsed >= timeoutMs) {
            log(
                    activity,
                    "task=live_room_enter_task find vflipper timeout, elapsed="
                            + elapsed + "ms reason=" + safeTrim(reason)
            );
            finishTask(
                    activity,
                    taskContext,
                    "find_vflipper_timeout:" + safeTrim(reason)
            );
            return;
        }
        final int nextAttempt = currentAttempt + 1;
        if (currentAttempt <= 2 || currentAttempt % 5 == 0) {
            log(
                    activity,
                    "task=live_room_enter_task wait vflipper, attempt=" + currentAttempt
                            + " elapsed=" + elapsed + "ms reason=" + safeTrim(reason)
            );
        }
        boolean posted = postOnUi(
                activity,
                new Runnable() {
                    @Override
                    public void run() {
                        scheduleFindVFlipperAndRun(
                                activity,
                                taskContext,
                                startedAt,
                                nextAttempt
                        );
                    }
                },
                UiComponentConfig.LIVE_ROOM_TASK_VFLIPPER_FIND_RETRY_INTERVAL_MS
        );
        if (!posted) {
            finishTask(activity, taskContext, "post_find_vflipper_retry_failed");
        }
    }

    private static boolean clickRankTab(
            Activity activity,
            String rankTarget,
            String rankName
    ) {
        if (!isTaskExecutionAllowed(activity) || TextUtils.isEmpty(rankTarget)) {
            return false;
        }
        LookHookEntry.A11yPanelClickResult clickResult =
                LookHookEntry.requestA11yPanelClickForTaskRunner(
                        activity,
                        rankTarget,
                        UiComponentConfig.LIVE_ROOM_TASK_A11Y_CLICK_RESULT_WAIT_MS
                );
        if (clickResult != null && clickResult.success) {
            log(
                    activity,
                    "task=live_room_enter_task rank clicked: " + safeTrim(rankName)
                            + " detail=" + safeTrim(clickResult.detail)
            );
            return true;
        }
        String failedDetail = clickResult == null ? "null_result" : safeTrim(clickResult.detail);
        if (failedDetail.contains("a11y_click_timeout")) {
            PanelState panelState = resolvePanelState();
            if (isPanelReady(panelState) && isRankTargetVisibleInSnapshot(rankTarget, panelState)) {
                log(
                        activity,
                        "task=live_room_enter_task rank click timeout fallback accepted: "
                                + safeTrim(rankName)
                                + " snapshot=" + buildPanelMatchDetail(panelState)
                );
                return true;
            }
        }
        log(
                activity,
                "task=live_room_enter_task rank click failed: " + safeTrim(rankName)
                        + " detail=" + failedDetail
        );
        return false;
    }

    private static boolean isRankTargetVisibleInSnapshot(String rankTarget, PanelState panelState) {
        if (panelState == null || TextUtils.isEmpty(rankTarget)) {
            return false;
        }
        String detail = safeTrim(panelState.detail);
        if (TextUtils.isEmpty(detail)) {
            return false;
        }
        if (ModuleSettings.A11Y_PANEL_CLICK_TARGET_CONTRIBUTION.equals(rankTarget)) {
            return detail.contains("contribution=true");
        }
        if (ModuleSettings.A11Y_PANEL_CLICK_TARGET_CHARM.equals(rankTarget)) {
            return detail.contains("charm=true");
        }
        return false;
    }

    private static PanelState resolvePanelState() {
        int markerCount = 0;
        int primaryCount = 0;
        long updatedAt = 0L;
        String detail = "";
        boolean usedRealtimeSnapshot = false;
        try {
            long realtimeUpdatedAt = LookHookEntry.getRealtimeA11yPanelUpdatedAtForTaskRunner();
            if (realtimeUpdatedAt > 0L) {
                markerCount = LookHookEntry.getRealtimeA11yPanelMarkerCountForTaskRunner();
                primaryCount = LookHookEntry.getRealtimeA11yPanelPrimaryCountForTaskRunner();
                updatedAt = realtimeUpdatedAt;
                detail = LookHookEntry.getRealtimeA11yPanelDetailForTaskRunner();
                usedRealtimeSnapshot = true;
            }
        } catch (Throwable ignore) {
            usedRealtimeSnapshot = false;
        }
        if (!usedRealtimeSnapshot) {
            try {
                markerCount = ModuleSettings.getA11yPanelMarkerCount();
                primaryCount = ModuleSettings.getA11yPanelPrimaryCount();
                updatedAt = ModuleSettings.getA11yPanelUpdatedAt();
                detail = ModuleSettings.getA11yPanelDetail();
            } catch (Throwable ignore) {
                markerCount = 0;
                primaryCount = 0;
                updatedAt = 0L;
                detail = "";
            }
        }
        long nowMs = System.currentTimeMillis();
        long staleMs = Math.max(1000L, UiComponentConfig.LIVE_ROOM_TASK_PANEL_A11Y_SNAPSHOT_STALE_MS);
        long ageMs = updatedAt <= 0L ? Long.MAX_VALUE : Math.max(0L, nowMs - updatedAt);
        boolean stale = updatedAt <= 0L || ageMs > staleMs;
        if (stale) {
            markerCount = 0;
            primaryCount = 0;
        }
        if (usedRealtimeSnapshot && !TextUtils.isEmpty(detail)) {
            detail = "realtime:" + safeTrim(detail);
        } else if (!TextUtils.isEmpty(detail)) {
            detail = "xsp:" + safeTrim(detail);
        }
        return new PanelState(markerCount, primaryCount, updatedAt, ageMs, stale, safeTrim(detail));
    }

    private static String buildPanelMatchDetail(PanelState panelState) {
        if (panelState == null) {
            return "snapshot=null";
        }
        long updatedAt = panelState.updatedAt;
        long ageMs = panelState.ageMs == Long.MAX_VALUE
                ? -1L
                : Math.max(0L, panelState.ageMs);
        String stalePart = panelState.stale ? "stale=true" : "stale=false";
        String updatePart = updatedAt <= 0L ? "updatedAt=0" : ("updatedAt=" + updatedAt);
        String agePart = ageMs < 0L ? "age=unknown" : ("age=" + ageMs + "ms");
        String detailPart = TextUtils.isEmpty(panelState.detail)
                ? "detail=empty"
                : ("detail=" + panelState.detail);
        return stalePart + ", " + updatePart + ", " + agePart + ", " + detailPart;
    }

    private static boolean isPanelReady(PanelState panelState) {
        if (panelState == null || panelState.stale) {
            return false;
        }
        int primaryCount = panelState == null ? 0 : panelState.primaryCount;
        int primaryThreshold = Math.max(1, UiComponentConfig.LIVE_ROOM_TASK_PANEL_PRIMARY_MIN_MATCH_COUNT);
        return primaryCount >= primaryThreshold;
    }

    private static boolean postOnUi(Activity activity, Runnable task, long delayMs) {
        if (activity == null || task == null) {
            return false;
        }
        long safeDelay = Math.max(0L, delayMs);
        try {
            View root = resolveRootView(activity);
            if (root != null) {
                root.postDelayed(task, safeDelay);
                return true;
            }
        } catch (Throwable ignore) {
        }
        try {
            Handler handler = new Handler(Looper.getMainLooper());
            handler.postDelayed(task, safeDelay);
            return true;
        } catch (Throwable ignore) {
            return false;
        }
    }

    private static boolean isLiveRoomActivity(Activity activity) {
        if (activity == null) {
            return false;
        }
        try {
            String name = safeTrim(activity.getClass().getName());
            return !TextUtils.isEmpty(name)
                    && name.contains(UiComponentConfig.LIVE_ROOM_ACTIVITY_KEYWORD);
        } catch (Throwable ignore) {
            return false;
        }
    }

    private static boolean isTaskExecutionAllowed(Activity activity) {
        if (!isActivityUsable(activity)) {
            return false;
        }
        if (!isLiveRoomActivity(activity)) {
            return false;
        }
        return isEngineRunning();
    }

    private static boolean isEngineRunning() {
        try {
            return LookHookEntry.isEngineRunningForTaskRunner();
        } catch (Throwable ignore) {
            return ModuleSettings.getEngineStatus() == ModuleSettings.EngineStatus.RUNNING;
        }
    }

    private static boolean isActivityUsable(Activity activity) {
        if (activity == null) {
            return false;
        }
        if (activity.isFinishing()) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= 17 && activity.isDestroyed()) {
            return false;
        }
        return true;
    }

    private static View resolveRootView(Activity activity) {
        if (activity == null) {
            return null;
        }
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

    private static void maybeDumpViewTree(Context context, View root, String stage) {
        if (!isViewTreeDumpEnabled()) {
            return;
        }
        String safeStage = safeTrim(stage);
        if (root == null) {
            log(
                    context,
                    "task=live_room_enter_task view_tree_dump stage=" + safeStage + " root=null"
            );
            return;
        }
        ArrayDeque<ViewDepth> queue = new ArrayDeque<ViewDepth>();
        queue.offer(new ViewDepth(root, 0));
        int dumped = 0;
        log(
                context,
                "task=live_room_enter_task view_tree_dump begin stage=" + safeStage
                        + " maxNodes=" + VIEW_TREE_DUMP_MAX_NODES
                        + " maxDepth=" + VIEW_TREE_DUMP_MAX_DEPTH
        );
        while (!queue.isEmpty() && dumped < VIEW_TREE_DUMP_MAX_NODES) {
            ViewDepth current = queue.poll();
            if (current == null || current.view == null) {
                continue;
            }
            dumped++;
            log(
                    context,
                    "task=live_room_enter_task view_tree_dump node#"
                            + dumped
                            + " d=" + current.depth
                            + " " + formatViewNodeForDump(current.view)
            );
            if (current.depth >= VIEW_TREE_DUMP_MAX_DEPTH) {
                continue;
            }
            if (!(current.view instanceof ViewGroup)) {
                continue;
            }
            ViewGroup group = (ViewGroup) current.view;
            int childCount = group.getChildCount();
            for (int i = 0; i < childCount; i++) {
                View child = group.getChildAt(i);
                if (child != null) {
                    queue.offer(new ViewDepth(child, current.depth + 1));
                }
            }
        }
        if (!queue.isEmpty()) {
            log(
                    context,
                    "task=live_room_enter_task view_tree_dump truncated stage=" + safeStage
                            + " dumped=" + dumped
                            + " remainingQueue=" + queue.size()
            );
        } else {
            log(
                    context,
                    "task=live_room_enter_task view_tree_dump end stage=" + safeStage
                            + " dumped=" + dumped
            );
        }
    }

    private static boolean isViewTreeDumpEnabled() {
        try {
            return ModuleSettings.isViewTreeDumpDebugEnabled();
        } catch (Throwable ignore) {
            return false;
        }
    }

    private static String formatViewNodeForDump(View view) {
        if (view == null) {
            return "view=null";
        }
        String className = safeTrim(view.getClass().getName());
        String idName = resolveResourceFullName(view);
        String text = "";
        if (view instanceof TextView) {
            text = safeLogValue(String.valueOf(((TextView) view).getText()));
        }
        String desc = safeLogValue(String.valueOf(view.getContentDescription()));
        Rect rect = new Rect();
        boolean hasVisibleRect = false;
        try {
            hasVisibleRect = view.getGlobalVisibleRect(rect);
        } catch (Throwable ignore) {
            hasVisibleRect = false;
        }
        String bounds = hasVisibleRect
                ? ("[" + rect.left + "," + rect.top + "][" + rect.right + "," + rect.bottom + "]")
                : "none";
        return "class=" + className
                + " id=" + safeLogValue(idName)
                + " text=" + text
                + " desc=" + desc
                + " clickable=" + view.isClickable()
                + " enabled=" + view.isEnabled()
                + " selected=" + view.isSelected()
                + " shown=" + view.isShown()
                + " alpha=" + view.getAlpha()
                + " bounds=" + bounds;
    }

    private static String safeLogValue(String value) {
        String normalized = safeTrim(value)
                .replace('\n', ' ')
                .replace('\r', ' ')
                .replace('\t', ' ');
        if (normalized.length() == 0) {
            return "\"\"";
        }
        if (normalized.length() > VIEW_TREE_DUMP_MAX_TEXT_LENGTH) {
            normalized = normalized.substring(0, VIEW_TREE_DUMP_MAX_TEXT_LENGTH) + "...";
        }
        return "\"" + normalized + "\"";
    }

    private static View findFirstNode(View root, UiComponentConfig.UiNodeSpec spec) {
        if (root == null || spec == null) {
            return null;
        }
        ArrayDeque<View> stack = new ArrayDeque<View>();
        stack.push(root);
        while (!stack.isEmpty()) {
            View current = stack.pop();
            if (matchesNodeSpec(current, spec)) {
                return current;
            }
            if (current instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) current;
                for (int i = group.getChildCount() - 1; i >= 0; i--) {
                    View child = group.getChildAt(i);
                    if (child != null) {
                        stack.push(child);
                    }
                }
            }
        }
        return null;
    }

    private static View findFirstNodeFromCandidates(
            View root,
            List<UiComponentConfig.UiNodeSpec> specs
    ) {
        if (root == null || specs == null || specs.isEmpty()) {
            return null;
        }
        for (UiComponentConfig.UiNodeSpec spec : specs) {
            if (spec == null) {
                continue;
            }
            View target = findFirstNode(root, spec);
            if (target != null) {
                return target;
            }
        }
        return null;
    }

    private static AccessibilityNodeInfo findFirstAccessibilityNodeFromCandidates(
            View root,
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

    private static int countMatchedAccessibilityNodes(View root, List<UiComponentConfig.UiNodeSpec> specs) {
        if (root == null || specs == null || specs.isEmpty()) {
            return 0;
        }
        int matched = 0;
        for (UiComponentConfig.UiNodeSpec spec : specs) {
            if (spec == null) {
                continue;
            }
            AccessibilityNodeInfo node = findFirstAccessibilityNode(root, spec);
            if (node != null) {
                matched++;
                node.recycle();
            }
        }
        return matched;
    }

    private static AccessibilityNodeInfo findFirstAccessibilityNode(
            View root,
            UiComponentConfig.UiNodeSpec spec
    ) {
        if (root == null || spec == null) {
            return null;
        }
        AccessibilityNodeInfo rootNode;
        try {
            rootNode = root.createAccessibilityNodeInfo();
        } catch (Throwable ignore) {
            return null;
        }
        if (rootNode == null) {
            return null;
        }
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<AccessibilityNodeInfo>();
        ArrayDeque<AccessibilityNodeInfo> allNodes = new ArrayDeque<AccessibilityNodeInfo>();
        queue.offer(rootNode);
        allNodes.offer(rootNode);
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
                int childCount = current.getChildCount();
                for (int i = 0; i < childCount; i++) {
                    AccessibilityNodeInfo child = current.getChild(i);
                    if (child != null) {
                        queue.offer(child);
                        allNodes.offer(child);
                    }
                }
            }
        } catch (Throwable ignore) {
            result = null;
        } finally {
            while (!allNodes.isEmpty()) {
                AccessibilityNodeInfo node = allNodes.poll();
                if (node != null) {
                    try {
                        node.recycle();
                    } catch (Throwable ignore) {
                    }
                }
            }
        }
        return result;
    }

    private static boolean matchesNodeSpec(View view, UiComponentConfig.UiNodeSpec spec) {
        if (view == null || spec == null) {
            return false;
        }
        if (!TextUtils.isEmpty(spec.className) && !isClassMatched(view, spec.className)) {
            return false;
        }
        if (!TextUtils.isEmpty(spec.packageName)) {
            String viewPkg = "";
            if (view.getContext() != null) {
                viewPkg = safeTrim(view.getContext().getPackageName());
            }
            boolean pkgMatched = spec.packageName.equals(viewPkg);
            String fullResName = resolveResourceFullName(view);
            if (!pkgMatched && !TextUtils.isEmpty(fullResName)) {
                pkgMatched = fullResName.startsWith(spec.packageName + ":");
            }
            if (!pkgMatched) {
                return false;
            }
        }
        if (spec.selected != null) {
            boolean selected = view.isSelected();
            if (!selected && view.getParent() instanceof View) {
                selected = ((View) view.getParent()).isSelected();
            }
            if (selected != spec.selected.booleanValue()) {
                return false;
            }
        }
        String expectedFullId = safeTrim(spec.fullResourceId);
        if (!TextUtils.isEmpty(expectedFullId)) {
            String actualFullId = resolveResourceFullName(view);
            if (!TextUtils.isEmpty(actualFullId)) {
                if (!expectedFullId.equals(actualFullId)) {
                    return false;
                }
            } else {
                String expectedEntry = UiComponentConfig.resourceEntryFromFullId(expectedFullId);
                String actualEntry = resolveResourceEntryName(view);
                if (!expectedEntry.equals(actualEntry)) {
                    return false;
                }
            }
        }
        if (!TextUtils.isEmpty(spec.text)) {
            if (!(view instanceof TextView)) {
                return false;
            }
            String expectedText = safeTrim(spec.text);
            String actualText = safeTrim(String.valueOf(((TextView) view).getText()));
            if (!expectedText.equals(actualText)) {
                return false;
            }
        }
        if (!TextUtils.isEmpty(spec.contentDesc)) {
            String expectedDesc = safeTrim(spec.contentDesc);
            String actualDesc = safeTrim(String.valueOf(view.getContentDescription()));
            if (!expectedDesc.equals(actualDesc)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesNodeSpec(AccessibilityNodeInfo node, UiComponentConfig.UiNodeSpec spec) {
        if (node == null || spec == null) {
            return false;
        }
        if (!TextUtils.isEmpty(spec.className)) {
            String actualClassName = safeTrim(String.valueOf(node.getClassName()));
            if (!isClassNameMatched(actualClassName, spec.className)) {
                return false;
            }
        }
        if (!TextUtils.isEmpty(spec.packageName)) {
            String actualPkg = safeTrim(String.valueOf(node.getPackageName()));
            if (!spec.packageName.equals(actualPkg)) {
                return false;
            }
        }
        if (spec.selected != null && node.isSelected() != spec.selected.booleanValue()) {
            return false;
        }
        String expectedFullId = safeTrim(spec.fullResourceId);
        if (!TextUtils.isEmpty(expectedFullId)) {
            String actualFullId = safeTrim(node.getViewIdResourceName());
            if (!TextUtils.isEmpty(actualFullId)) {
                if (!expectedFullId.equals(actualFullId)) {
                    return false;
                }
            } else {
                return false;
            }
        }
        if (!TextUtils.isEmpty(spec.text)) {
            String actualText = safeTrim(String.valueOf(node.getText()));
            if (!safeTrim(spec.text).equals(actualText)) {
                return false;
            }
        }
        if (!TextUtils.isEmpty(spec.contentDesc)) {
            String actualDesc = safeTrim(String.valueOf(node.getContentDescription()));
            if (!safeTrim(spec.contentDesc).equals(actualDesc)) {
                return false;
            }
        }
        return true;
    }

    private static ClickResult clickWithParentFallbackDetailed(
            Activity activity,
            View start,
            String clickName
    ) {
        if (activity == null) {
            return new ClickResult(false, clickName + " activity_null");
        }
        if (start == null) {
            return new ClickResult(false, clickName + " start_view_null");
        }
        View current = start;
        int depth = 0;
        while (current != null && depth < 5) {
            if (current.isShown()) {
                boolean hasValidBounds = hasUsableVisibleBounds(current);
                try {
                    if (hasValidBounds && current.isClickable() && current.performClick()) {
                        return new ClickResult(true, clickName + " via=performClick depth=" + depth);
                    }
                } catch (Throwable ignore) {
                }
                try {
                    if (hasValidBounds && current.isClickable() && current.callOnClick()) {
                        return new ClickResult(true, clickName + " via=callOnClick depth=" + depth);
                    }
                } catch (Throwable ignore) {
                }
                try {
                    boolean allowTapCenter = current.isClickable() || depth == 0;
                    if (hasValidBounds && allowTapCenter && tapViewCenter(activity, current)) {
                        return new ClickResult(true, clickName + " via=tap_center depth=" + depth);
                    }
                } catch (Throwable ignore) {
                }
            }
            if (!(current.getParent() instanceof View)) {
                break;
            }
            current = (View) current.getParent();
            depth++;
        }
        return new ClickResult(false, clickName + " fallback_failed");
    }

    private static ClickResult clickAccessibilityNodeWithParentFallback(
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
            if (current != null) {
                try {
                    current.recycle();
                } catch (Throwable ignore) {
                }
            }
        }
        return new ClickResult(false, clickName + " a11y_click_failed");
    }

    private static boolean hasUsableVisibleBounds(View target) {
        if (target == null || !target.isShown()) {
            return false;
        }
        Rect rect = new Rect();
        if (!target.getGlobalVisibleRect(rect)) {
            return false;
        }
        return rect.width() > 2 && rect.height() > 2;
    }

    private static boolean tapViewCenter(Activity activity, View target) {
        return tapViewAtRatio(activity, target, 0.5f, 0.5f);
    }

    private static boolean tapViewAtRatio(Activity activity, View target, float xRatio, float yRatio) {
        if (activity == null || target == null || !target.isShown()) {
            return false;
        }
        Rect rect = new Rect();
        if (!target.getGlobalVisibleRect(rect)) {
            return false;
        }
        if (rect.width() <= 0 || rect.height() <= 0) {
            return false;
        }
        float safeXRatio = clamp(xRatio, 0.05f, 0.95f);
        float safeYRatio = clamp(yRatio, 0.05f, 0.95f);
        float x = rect.left + rect.width() * safeXRatio;
        float y = rect.top + rect.height() * safeYRatio;
        return dispatchTapGlobal(activity, x, y);
    }

    private static boolean dispatchTapGlobal(Activity activity, float x, float y) {
        if (activity == null) {
            return false;
        }
        long downTime = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(downTime, downTime + 45L, MotionEvent.ACTION_UP, x, y, 0);
        boolean handledDown = false;
        boolean handledUp = false;
        try {
            handledDown = activity.dispatchTouchEvent(down);
            handledUp = activity.dispatchTouchEvent(up);
        } finally {
            down.recycle();
            up.recycle();
        }
        if (!(handledDown || handledUp) && activity.getWindow() != null) {
            MotionEvent down2 = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0);
            MotionEvent up2 = MotionEvent.obtain(downTime, downTime + 45L, MotionEvent.ACTION_UP, x, y, 0);
            try {
                View decor = activity.getWindow().getDecorView();
                if (decor != null) {
                    handledDown = decor.dispatchTouchEvent(down2);
                    handledUp = decor.dispatchTouchEvent(up2);
                }
            } finally {
                down2.recycle();
                up2.recycle();
            }
        }
        return handledDown || handledUp;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String resolveResourceFullName(View view) {
        if (view == null || view.getId() == View.NO_ID) {
            return "";
        }
        try {
            return safeTrim(view.getResources().getResourceName(view.getId()));
        } catch (Throwable ignore) {
            return "";
        }
    }

    private static String resolveResourceEntryName(View view) {
        if (view == null || view.getId() == View.NO_ID) {
            return "";
        }
        try {
            return safeTrim(view.getResources().getResourceEntryName(view.getId()));
        } catch (Throwable ignore) {
            return "";
        }
    }

    private static boolean isClassMatched(View view, String expectedClassName) {
        if (view == null || TextUtils.isEmpty(expectedClassName)) {
            return false;
        }
        String actualName = view.getClass().getName();
        if (expectedClassName.equals(actualName)) {
            return true;
        }
        String expectedSimple = expectedClassName;
        int dot = expectedSimple.lastIndexOf('.');
        if (dot >= 0 && dot < expectedSimple.length() - 1) {
            expectedSimple = expectedSimple.substring(dot + 1);
        }
        if (expectedSimple.equals(view.getClass().getSimpleName())) {
            return true;
        }
        try {
            ClassLoader cl = view.getClass().getClassLoader();
            Class<?> expected = Class.forName(expectedClassName, false, cl);
            return expected.isAssignableFrom(view.getClass());
        } catch (Throwable ignore) {
            return false;
        }
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

    private static void finishTask(Activity activity, TaskContext taskContext, String reason) {
        if (taskContext == null) {
            return;
        }
        String safeReason = safeTrim(reason);
        if (TextUtils.isEmpty(safeReason)) {
            safeReason = "unspecified";
        }
        boolean shouldNotify = false;
        synchronized (taskContext.lock) {
            if (!taskContext.finished) {
                taskContext.finished = true;
                shouldNotify = true;
            }
        }
        if (!shouldNotify) {
            return;
        }
        long elapsed = Math.max(0L, System.currentTimeMillis() - taskContext.startedAt);
        log(
                activity,
                "task=live_room_enter_task sequence complete, reason="
                        + safeReason + " elapsed=" + elapsed + "ms"
        );
        if (taskContext.listener == null) {
            return;
        }
        try {
            taskContext.listener.onTaskFinished(safeReason);
        } catch (Throwable e) {
            log(activity, "task=live_room_enter_task finish callback failed: " + e);
        }
    }

    private static String safeTrim(String s) {
        if (s == null) {
            return "";
        }
        return s.trim();
    }

    private static final class ViewDepth {
        final View view;
        final int depth;

        ViewDepth(View view, int depth) {
            this.view = view;
            this.depth = Math.max(0, depth);
        }
    }

    private static final class TaskContext {
        final long startedAt;
        final String homeId;
        final long enterTimeMs;
        final TaskFinishListener listener;
        final Object lock;
        boolean finished;
        boolean contributionCollected;
        boolean charmCollected;
        String contributionCsvPath;
        String charmCsvPath;
        boolean aiAnalysisDispatched;

        TaskContext(long startedAt, String homeId, long enterTimeMs, TaskFinishListener listener) {
            this.startedAt = Math.max(0L, startedAt);
            this.homeId = safeTrim(homeId);
            this.enterTimeMs = Math.max(0L, enterTimeMs);
            this.listener = listener;
            this.lock = new Object();
            this.finished = false;
            this.contributionCollected = false;
            this.charmCollected = false;
            this.contributionCsvPath = "";
            this.charmCsvPath = "";
            this.aiAnalysisDispatched = false;
        }
    }

    private static final class ClickResult {
        final boolean success;
        final String detail;

        ClickResult(boolean success, String detail) {
            this.success = success;
            this.detail = detail == null ? "" : detail;
        }
    }

    private static final class PanelState {
        final int markerCount;
        final int primaryCount;
        final long updatedAt;
        final long ageMs;
        final boolean stale;
        final String detail;

        PanelState(
                int markerCount,
                int primaryCount,
                long updatedAt,
                long ageMs,
                boolean stale,
                String detail
        ) {
            this.markerCount = Math.max(0, markerCount);
            this.primaryCount = Math.max(0, primaryCount);
            this.updatedAt = Math.max(0L, updatedAt);
            this.ageMs = ageMs < 0L ? 0L : ageMs;
            this.stale = stale;
            this.detail = detail == null ? "" : detail;
        }
    }

    private static void log(Context context, String msg) {
        String line = TAG + " " + safeTrim(msg);
        try {
            XposedBridge.log(line);
        } catch (Throwable ignore) {
        }
        ModuleRunFileLogger.appendLine(context, line);
    }
}
