package com.oodbye.looklspmodule;

import android.app.Activity;
import android.text.TextUtils;
import android.view.View;

import java.util.List;

/**
 * 直播间运行模块：负责“进入直播间后的校验、任务执行、返回”流程。
 */
final class LiveRoomRuntimeModule {

    interface RuntimeBridge {
        boolean isAwaitingLiveRoomTask();

        long getLastCardClickAt();

        View getRootView();

        boolean hasAllNodes(View root, List<UiComponentConfig.UiNodeSpec> specs);

        String findMissingNodeReason(View root, List<UiComponentConfig.UiNodeSpec> specs);

        boolean isLiveRoomActivity();

        List<String> getLastClickedCardTitleCandidates();

        String resolveLiveRoomTitle(View root);

        boolean isTitleMatched(List<String> values, String target);

        void resetAwaitingLiveRoomFlag();

        void markLiveRoomTaskFinished();

        void log(String msg);

        void logFlow(String msg);

        void postDelayed(Runnable task, long delayMs);

        boolean isEngineRunning();

        void onBackPressed();

        void markLiveRoomReturned(long now);

        void markLiveRoomEntered(String liveRoomTitle);
    }

    private LiveRoomRuntimeModule() {
    }

    static boolean run(
            final long now,
            final Activity activity,
            final boolean handledInSession,
            final RuntimeBridge bridge
    ) {
        if (bridge == null) {
            return handledInSession;
        }
        long elapsedMs = Math.max(0L, now - bridge.getLastCardClickAt());
        long retryBaseInterval = Math.max(100L, UiComponentConfig.MAIN_LOOP_INTERVAL_MS);
        int retryIndex = (int) (elapsedMs / retryBaseInterval) + 1;
        int maxRetry = Math.max(1, UiComponentConfig.LIVE_ROOM_VERIFY_MAX_RETRY_COUNT);
        boolean retryExhausted = retryIndex >= maxRetry;
        boolean verifyTimedOut = elapsedMs > UiComponentConfig.LIVE_ROOM_VERIFY_TIMEOUT_MS;
        if (!bridge.isAwaitingLiveRoomTask()) {
            bridge.logFlow("直播间页：当前无待执行任务，isLiveActivity=" + bridge.isLiveRoomActivity());
            return handledInSession;
        }
        if (handledInSession) {
            return true;
        }
        if (now - bridge.getLastCardClickAt() < UiComponentConfig.LIVE_ROOM_ENTER_WAIT_MS) {
            bridge.logFlow("直播间页：等待页面稳定后执行任务");
            return false;
        }
        if (retryIndex <= 1) {
            bridge.log("直播间校验开始：elapsed=" + elapsedMs + "ms retry=" + retryIndex + "/" + maxRetry
                    + " isLiveActivity=" + bridge.isLiveRoomActivity());
        }
        View root = bridge.getRootView();
        if (root == null) {
            bridge.logFlow("直播间页：未获取到根节点，等待校验");
            return false;
        }
        if (bridge.hasAllNodes(root, UiComponentConfig.LOGIN_BLOCK_DIALOG_NODES)) {
            bridge.resetAwaitingLiveRoomFlag();
            bridge.log("直播间校验识别到登录拦截界面，立即返回并跳过当前卡片");
            bridge.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!bridge.isEngineRunning()) {
                        return;
                    }
                    try {
                        bridge.onBackPressed();
                        bridge.markLiveRoomReturned(System.currentTimeMillis());
                    } catch (Throwable e) {
                        bridge.log("登录拦截界面返回失败: " + e);
                    }
                }
            }, UiComponentConfig.LIVE_ROOM_SCRIPT_BACK_DELAY_MS);
            return true;
        }
        boolean strictVerified = bridge.hasAllNodes(root, UiComponentConfig.LIVE_ROOM_VERIFY_NODES);
        boolean relaxedVerified = bridge.isLiveRoomActivity()
                && bridge.hasAllNodes(root, UiComponentConfig.LIVE_ROOM_VERIFY_RELAXED_NODES);
        if (!strictVerified && !relaxedVerified) {
            String missing = bridge.findMissingNodeReason(
                    root,
                    UiComponentConfig.LIVE_ROOM_VERIFY_NODES
            );
            if (retryExhausted || verifyTimedOut) {
                bridge.resetAwaitingLiveRoomFlag();
                bridge.log("直播间校验失败并结束重试，retry=" + retryIndex + "/" + maxRetry
                        + " missing=" + missing + "，恢复列表点击流程");
                tryBackToTogetherIfNeeded(bridge, "直播间校验节点缺失");
            } else {
                bridge.logFlow("直播间校验重试 " + retryIndex + "/" + maxRetry + "，缺失节点=" + missing);
            }
            return false;
        }
        if (!strictVerified && relaxedVerified) {
            bridge.log("直播间校验使用宽松模式（title+closeBtn），继续执行任务");
        }

        String liveRoomTitle = bridge.resolveLiveRoomTitle(root);
        List<String> titleCandidates = bridge.getLastClickedCardTitleCandidates();
        if (titleCandidates == null || titleCandidates.isEmpty()) {
            if (retryExhausted || verifyTimedOut) {
                bridge.resetAwaitingLiveRoomFlag();
                bridge.log("直播间校验失败并结束重试：未采集到卡片标题候选，retry="
                        + retryIndex + "/" + maxRetry + "，恢复列表点击流程");
                tryBackToTogetherIfNeeded(bridge, "直播间标题候选缺失");
            } else {
                bridge.logFlow("直播间校验重试 " + retryIndex + "/" + maxRetry + "：等待卡片标题候选");
            }
            return false;
        }
        if (TextUtils.isEmpty(liveRoomTitle)) {
            if (retryExhausted || verifyTimedOut) {
                bridge.resetAwaitingLiveRoomFlag();
                bridge.log("直播间校验失败并结束重试：直播间title为空，retry="
                        + retryIndex + "/" + maxRetry + "，恢复列表点击流程");
                tryBackToTogetherIfNeeded(bridge, "直播间title缺失");
            } else {
                bridge.logFlow("直播间校验重试 " + retryIndex + "/" + maxRetry + "：等待直播间title加载");
            }
            return false;
        }

        if (!bridge.isTitleMatched(titleCandidates, liveRoomTitle)) {
            bridge.markLiveRoomTaskFinished();
            bridge.log("直播间校验失败：title不匹配。liveTitle=" + liveRoomTitle
                    + " candidates=" + titleCandidates + "，执行返回");
            bridge.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!bridge.isEngineRunning()) {
                        return;
                    }
                    try {
                        bridge.onBackPressed();
                        bridge.markLiveRoomReturned(System.currentTimeMillis());
                    } catch (Throwable e) {
                        bridge.log("直播间返回失败: " + e);
                    }
                }
            }, UiComponentConfig.LIVE_ROOM_SCRIPT_BACK_DELAY_MS);
            return true;
        }

        bridge.markLiveRoomEntered(liveRoomTitle);
        bridge.log("直播间校验通过，开始执行直播间任务。liveTitle=" + liveRoomTitle
                + " candidates=" + titleCandidates);
        long taskWaitMs = LiveRoomTaskScriptRunner.runLiveRoomEnterTask(activity);
        bridge.markLiveRoomTaskFinished();
        long backDelayMs = Math.max(UiComponentConfig.LIVE_ROOM_SCRIPT_BACK_DELAY_MS, taskWaitMs);
        bridge.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!bridge.isEngineRunning()) {
                    return;
                }
                try {
                    bridge.onBackPressed();
                    bridge.markLiveRoomReturned(System.currentTimeMillis());
                    bridge.log("直播间任务完成，执行返回退出直播间");
                } catch (Throwable e) {
                    bridge.log("直播间返回失败: " + e);
                }
            }
        }, backDelayMs);
        return true;
    }

    private static void tryBackToTogetherIfNeeded(final RuntimeBridge bridge, final String reason) {
        if (bridge == null) {
            return;
        }
        if (!bridge.isLiveRoomActivity()) {
            return;
        }
        bridge.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!bridge.isEngineRunning()) {
                    return;
                }
                try {
                    bridge.onBackPressed();
                    bridge.markLiveRoomReturned(System.currentTimeMillis());
                    bridge.log("直播间校验失败后执行返回，reason=" + reason);
                } catch (Throwable e) {
                    bridge.log("直播间校验失败后返回异常: " + e);
                }
            }
        }, UiComponentConfig.LIVE_ROOM_SCRIPT_BACK_DELAY_MS);
    }
}
