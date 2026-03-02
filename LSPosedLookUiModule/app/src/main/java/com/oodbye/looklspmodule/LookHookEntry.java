package com.oodbye.looklspmodule;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LOOK 直播 UI 组件采集入口（LSPosed 模块）。
 */
public class LookHookEntry implements IXposedHookLoadPackage {
    private static final String TAG = "[LOOKLspModule]";
    private static final String LOGCAT_TAG = "LOOKLspModule";
    private static final Object LOCK = new Object();
    private static final Object FLOW_STATE_LOCK = new Object();
    private static final Map<Activity, UiSession> SESSIONS = new WeakHashMap<Activity, UiSession>();
    private static final Set<String> PROCESSED_CARD_KEYS = new LinkedHashSet<String>();
    private static final List<String> LAST_CLICKED_CARD_TITLE_CANDIDATES = new ArrayList<String>();
    private static long sHandledCommandSeq = -1L;
    private static long sInitializedRunSeq = Long.MIN_VALUE;
    private static BroadcastReceiver sCommandReceiver;
    private static boolean sCommandReceiverRegistered = false;
    private static String sRealtimeCommand = "";
    private static ModuleSettings.EngineStatus sRealtimeStatus = null;
    private static long sRealtimeCommandSeq = -1L;
    private static int sRealtimeTogetherCycleLimit = -1;
    private static int sRealtimeTogetherCycleWaitSeconds = -1;
    private static boolean sHookInstalled = false;
    private static boolean sAwaitingLiveRoomScript = false;
    private static long sLastCardClickAt = 0L;
    private static long sLastLiveRoomReturnAt = 0L;
    private static long sLastFloatSyncRequestAt = 0L;
    private static boolean sAutoRunConsumedForProcess = false;
    private static long sRuntimeRunStartedAt = 0L;
    private static int sRuntimeCycleCompletedCount = 0;
    private static int sRuntimeCycleEnteredCount = 0;

    static {
        log("LookHookEntry class loaded");
    }

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (!UiComponentConfig.TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }
        log("loaded in process=" + lpparam.processName + " package=" + lpparam.packageName);
        try {
            installActivityHooks();
        } catch (Throwable e) {
            log("installActivityHooks failed: " + e);
        }
    }

    private void installActivityHooks() {
        if (sHookInstalled) {
            return;
        }
        synchronized (LOCK) {
            if (sHookInstalled) {
                return;
            }
            XposedBridge.hookAllMethods(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Activity activity = (Activity) param.thisObject;
                    ensureSession(activity);
                }
            });
            XposedBridge.hookAllMethods(Activity.class, "onPause", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Activity activity = (Activity) param.thisObject;
                    UiSession session = getSession(activity);
                    if (session != null) {
                        session.onPause();
                    }
                }
            });
            XposedBridge.hookAllMethods(Activity.class, "onDestroy", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Activity activity = (Activity) param.thisObject;
                    UiSession session = removeSession(activity);
                    if (session != null) {
                        session.destroy();
                    }
                }
            });
            sHookInstalled = true;
            log("activity hooks installed");
        }
    }

    private void ensureCommandReceiver(final Context appContext) {
        if (appContext == null) {
            return;
        }
        synchronized (LOCK) {
            if (sCommandReceiverRegistered) {
                return;
            }
            IntentFilter filter = new IntentFilter(ModuleSettings.ACTION_ENGINE_COMMAND);
            sCommandReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null) {
                        return;
                    }
                    if (!ModuleSettings.ACTION_ENGINE_COMMAND.equals(intent.getAction())) {
                        return;
                    }
                    String command = safeTrimStatic(intent.getStringExtra(ModuleSettings.EXTRA_ENGINE_COMMAND));
                    String statusName = safeTrimStatic(intent.getStringExtra(ModuleSettings.EXTRA_ENGINE_STATUS));
                    long seq = intent.getLongExtra(ModuleSettings.EXTRA_ENGINE_COMMAND_SEQ, -1L);
                    int cycleLimit = intent.getIntExtra(
                            ModuleSettings.EXTRA_TOGETHER_CYCLE_LIMIT,
                            -1
                    );
                    int cycleWaitSeconds = intent.getIntExtra(
                            ModuleSettings.EXTRA_TOGETHER_CYCLE_WAIT_SECONDS,
                            -1
                    );
                    long runtimeRunStartAt = intent.getLongExtra(
                            ModuleSettings.EXTRA_RUNTIME_RUN_START_AT,
                            -1L
                    );
                    int runtimeCompleted = intent.getIntExtra(
                            ModuleSettings.EXTRA_RUNTIME_CYCLE_COMPLETED,
                            -1
                    );
                    int runtimeEntered = intent.getIntExtra(
                            ModuleSettings.EXTRA_RUNTIME_CYCLE_ENTERED,
                            -1
                    );
                    ModuleSettings.EngineStatus status = parseEngineStatusStatic(statusName);
                    updateRealtimeEngineState(
                            command,
                            status,
                            seq,
                            cycleLimit,
                            cycleWaitSeconds,
                            runtimeRunStartAt,
                            runtimeCompleted,
                            runtimeEntered
                    );
                    log("receive realtime command: cmd=" + command
                            + " status=" + status.name()
                            + " seq=" + seq
                            + " cycleLimit=" + cycleLimit
                            + " waitSeconds=" + cycleWaitSeconds
                            + " runtimeCompleted=" + runtimeCompleted);
                }
            };
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    appContext.registerReceiver(sCommandReceiver, filter, Context.RECEIVER_EXPORTED);
                } else {
                    appContext.registerReceiver(sCommandReceiver, filter);
                }
                sCommandReceiverRegistered = true;
            } catch (Throwable e) {
                log("register command receiver failed: " + e);
            }
        }
    }

    private void ensureSession(final Activity activity) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        ensureCommandReceiver(activity.getApplicationContext());
        requestModuleFloatServiceSync(activity.getApplicationContext(), activity);
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                UiSession session = getSession(activity);
                if (session == null) {
                    session = new UiSession(activity);
                    putSession(activity, session);
                }
                session.start();
            }
        });
    }

    private void requestModuleFloatServiceSync(Context appContext, Activity activity) {
        if (appContext == null) {
            return;
        }
        long now = System.currentTimeMillis();
        synchronized (FLOW_STATE_LOCK) {
            if (now - sLastFloatSyncRequestAt < 2500L) {
                return;
            }
            sLastFloatSyncRequestAt = now;
        }
        try {
            Intent intent = new Intent(ModuleSettings.ACTION_SYNC_FLOAT_SERVICE);
            intent.setPackage(ModuleSettings.MODULE_PACKAGE);
            int displayId = -1;
            if (activity != null && activity.getDisplay() != null) {
                displayId = activity.getDisplay().getDisplayId();
            }
            if (displayId >= 0) {
                intent.putExtra(ModuleSettings.EXTRA_TARGET_DISPLAY_ID, displayId);
            }
            appContext.sendBroadcast(intent);
        } catch (Throwable e) {
            log("sync float service broadcast failed: " + e);
        }
    }

    private UiSession getSession(Activity activity) {
        synchronized (LOCK) {
            return SESSIONS.get(activity);
        }
    }

    private void putSession(Activity activity, UiSession session) {
        synchronized (LOCK) {
            SESSIONS.put(activity, session);
        }
    }

    private UiSession removeSession(Activity activity) {
        synchronized (LOCK) {
            return SESSIONS.remove(activity);
        }
    }

    private static final class UiSession {
        private final Activity activity;
        private final Handler handler;
        private final LiveRoomRuntimeModule.RuntimeBridge liveRoomRuntimeBridge;
        private final RealtimeAdLoop realtimeAdLoop;
        private final Runnable loopTask;

        private boolean started;
        private long lastFlowLogAt;
        private String lastFlowLogMessage;
        private long lastCommandSeq;
        private long lastEnterTogetherAttemptAt;
        private long togetherEnterStartAt;
        private long runStartedAt;
        private long togetherPageEnteredAt;
        private long togetherRefreshAt;
        private long lastTogetherRefreshAttemptAt;
        private long togetherScrollAt;
        private long lastTogetherScrollAttemptAt;
        private int togetherNoNewCardStreak;
        private int togetherNoNewSameSignatureStreak;
        private int togetherCycleCompletedCount;
        private int togetherCycleLiveRoomEnteredCount;
        private String togetherLastNoNewSignature;
        private long togetherLastCycleCompleteAt;
        private boolean togetherCycleRestartRequested;
        private long lastLoginBlockHandleAt;
        private ModuleSettings.EngineStatus lastStatus;
        private RunFlowState flowState;
        private boolean liveRoomScriptHandledInSession;
        private boolean togetherRefreshedInRun;
        private boolean adModeHintLogged;

        UiSession(Activity activity) {
            this.activity = activity;
            this.handler = new Handler(Looper.getMainLooper());
            this.started = false;
            this.lastFlowLogAt = 0L;
            this.lastFlowLogMessage = "";
            this.lastCommandSeq = -1L;
            this.lastEnterTogetherAttemptAt = 0L;
            this.togetherEnterStartAt = 0L;
            this.runStartedAt = 0L;
            this.togetherPageEnteredAt = 0L;
            this.togetherRefreshAt = 0L;
            this.lastTogetherRefreshAttemptAt = 0L;
            this.togetherScrollAt = 0L;
            this.lastTogetherScrollAttemptAt = 0L;
            this.togetherNoNewCardStreak = 0;
            this.togetherNoNewSameSignatureStreak = 0;
            this.togetherCycleCompletedCount = 0;
            this.togetherCycleLiveRoomEnteredCount = 0;
            this.togetherLastNoNewSignature = "";
            this.togetherLastCycleCompleteAt = 0L;
            this.togetherCycleRestartRequested = false;
            this.lastLoginBlockHandleAt = 0L;
            this.lastStatus = ModuleSettings.EngineStatus.STOPPED;
            this.flowState = RunFlowState.IDLE;
            this.liveRoomScriptHandledInSession = false;
            this.togetherRefreshedInRun = false;
            this.adModeHintLogged = false;
            this.liveRoomRuntimeBridge = new LiveRoomRuntimeModule.RuntimeBridge() {
                @Override
                public boolean isAwaitingLiveRoomTask() {
                    return isAwaitingLiveRoomScript();
                }

                @Override
                public long getLastCardClickAt() {
                    return LookHookEntry.getLastCardClickAt();
                }

                @Override
                public View getRootView() {
                    return UiSession.this.getRootView();
                }

                @Override
                public boolean hasAllNodes(View root, List<UiComponentConfig.UiNodeSpec> specs) {
                    return UiSession.this.hasAllNodes(root, specs);
                }

                @Override
                public String findMissingNodeReason(View root, List<UiComponentConfig.UiNodeSpec> specs) {
                    return UiSession.this.findMissingNodeReason(root, specs);
                }

                @Override
                public boolean isLiveRoomActivity() {
                    return UiSession.this.isLiveRoomActivity();
                }

                @Override
                public List<String> getLastClickedCardTitleCandidates() {
                    return LookHookEntry.getLastClickedCardTitleCandidates();
                }

                @Override
                public String resolveLiveRoomTitle(View root) {
                    return UiSession.this.resolveLiveRoomTitle(root);
                }

                @Override
                public boolean isTitleMatched(List<String> values, String target) {
                    return UiSession.this.isStringInList(values, target);
                }

                @Override
                public void resetAwaitingLiveRoomFlag() {
                    LookHookEntry.resetAwaitingLiveRoomFlag();
                }

                @Override
                public void markLiveRoomTaskFinished() {
                    LookHookEntry.markLiveRoomScriptFinished();
                }

                @Override
                public void log(String msg) {
                    LookHookEntry.log(msg);
                }

                @Override
                public void logFlow(String msg) {
                    UiSession.this.logFlow(msg);
                }

                @Override
                public void postDelayed(Runnable task, long delayMs) {
                    handler.postDelayed(task, delayMs);
                }

                @Override
                public boolean isEngineRunning() {
                    return resolveEffectiveEngineStatus() == ModuleSettings.EngineStatus.RUNNING;
                }

                @Override
                public void onBackPressed() {
                    activity.onBackPressed();
                }

                @Override
                public void markLiveRoomReturned(long now) {
                    LookHookEntry.markLiveRoomReturned(now);
                }

                @Override
                public void markLiveRoomEntered(String liveRoomTitle) {
                    UiSession.this.onLiveRoomEntered(liveRoomTitle);
                }
            };
            this.realtimeAdLoop = new RealtimeAdLoop(
                    activity,
                    handler,
                    new RealtimeAdLoop.RuntimeBridge() {
                        @Override
                        public boolean isEngineRunning() {
                            return resolveEffectiveEngineStatus() == ModuleSettings.EngineStatus.RUNNING;
                        }

                        @Override
                        public boolean isAdProcessEnabled() {
                            return ModuleSettings.isAdProcessEnabled();
                        }

                        @Override
                        public boolean isAccessibilityAdServiceEnabled() {
                            return ModuleSettings.isAccessibilityAdServiceEnabled();
                        }
                    }
            );
            this.loopTask = new Runnable() {
                @Override
                public void run() {
                    if (!started) {
                        return;
                    }
                    tick();
                    handler.postDelayed(this, UiComponentConfig.MAIN_LOOP_INTERVAL_MS);
                }
            };
        }

        void start() {
            if (started) {
                return;
            }
            started = true;
            if (!adModeHintLogged && ModuleSettings.isAccessibilityAdServiceEnabled()) {
                adModeHintLogged = true;
                log("广告处理当前由无障碍服务执行，日志请查看 tag=LOOKA11yAd");
            }
            realtimeAdLoop.start();
            handler.post(loopTask);
        }

        void onPause() {
            stopLoop();
        }

        void destroy() {
            stopLoop();
        }

        private void stopLoop() {
            started = false;
            handler.removeCallbacks(loopTask);
            realtimeAdLoop.stop();
        }

        private void tick() {
            if (activity.isFinishing() || activity.isDestroyed()) {
                stopLoop();
                return;
            }
            syncCommandLog();
            ModuleSettings.EngineStatus status = resolveEffectiveEngineStatus();
            if (status != lastStatus) {
                log("status changed to " + status.name());
                onStatusChanged(lastStatus, status);
                lastStatus = status;
            }
            if (status != ModuleSettings.EngineStatus.RUNNING) {
                return;
            }
            long now = System.currentTimeMillis();
            View root = getRootView();
            boolean awaitingLiveRoom = isAwaitingLiveRoomScript();
            if (awaitingLiveRoom) {
                if (root != null && isTogetherPage(root)) {
                    logFlow("点击卡片后仍在一起聊，等待界面切换");
                    runMainFlow(now);
                    return;
                }
                logFlow("点击卡片后已离开一起聊，开始进行广告与直播间校验");
                runLiveRoomFlow(now);
                return;
            }
            if (isLiveRoomActivity()) {
                runLiveRoomFlow(now);
                return;
            }
            runMainFlow(now);
        }

        private ModuleSettings.EngineStatus resolveEffectiveEngineStatus() {
            ModuleSettings.EngineStatus realtime = getRealtimeStatus();
            if (realtime != null) {
                return realtime;
            }
            ModuleSettings.EngineStatus configured = ModuleSettings.getEngineStatus();
            if (configured != ModuleSettings.EngineStatus.STOPPED) {
                markAutoRunConsumedForProcess();
                return configured;
            }
            if (ModuleSettings.isAutoRunOnAppStartEnabled() && tryConsumeAutoRunForProcess()) {
                log("启动软件自动运行模块：本次进程自动进入运行态");
                return ModuleSettings.EngineStatus.RUNNING;
            }
            return ModuleSettings.EngineStatus.STOPPED;
        }

        private void onStatusChanged(ModuleSettings.EngineStatus from, ModuleSettings.EngineStatus to) {
            reportEngineStatusToModule(to);
            if (to == ModuleSettings.EngineStatus.RUNNING) {
                long seq = resolveEffectiveCommandSeq();
                boolean isNewRunSeq = markRunSeqInitialized(seq);
                long persistedRunStartedAt = resolveRuntimeRunStartedAtFromShared();
                int persistedCompleted = resolveRuntimeCycleCompletedFromShared();
                int persistedEntered = resolveRuntimeCycleEnteredFromShared();
                boolean shouldRecoverRuntime = (!isNewRunSeq)
                        || persistedRunStartedAt > 0L
                        || persistedCompleted > 0
                        || persistedEntered > 0;
                if (shouldRecoverRuntime) {
                    runStartedAt = persistedRunStartedAt;
                    togetherCycleCompletedCount = Math.max(0, persistedCompleted);
                    togetherCycleLiveRoomEnteredCount = Math.max(0, persistedEntered);
                    setRuntimeRunStartedAtShared(runStartedAt);
                    setRuntimeCycleCompletedCountShared(togetherCycleCompletedCount);
                    setRuntimeCycleEnteredCountShared(togetherCycleLiveRoomEnteredCount);
                    flowState = RunFlowState.WAIT_HOME;
                    if (isNewRunSeq) {
                        log("RUN 状态续跑恢复：seq=" + seq
                                + " runStartedAt=" + runStartedAt
                                + " completedCycles=" + togetherCycleCompletedCount
                                + " enteredInCycle=" + togetherCycleLiveRoomEnteredCount);
                    }
                    return;
                }
                ModuleRunFileLogger.prepareForNewRun(activity.getApplicationContext(), seq);
                runStartedAt = System.currentTimeMillis();
                setRuntimeRunStartedAtShared(runStartedAt);
                flowState = RunFlowState.WAIT_HOME;
                togetherEnterStartAt = 0L;
                lastEnterTogetherAttemptAt = 0L;
                lastFlowLogAt = 0L;
                lastFlowLogMessage = "";
                liveRoomScriptHandledInSession = false;
                togetherPageEnteredAt = 0L;
                togetherRefreshAt = 0L;
                lastTogetherRefreshAttemptAt = 0L;
                togetherScrollAt = 0L;
                lastTogetherScrollAttemptAt = 0L;
                togetherNoNewCardStreak = 0;
                togetherNoNewSameSignatureStreak = 0;
                togetherCycleCompletedCount = 0;
                togetherCycleLiveRoomEnteredCount = 0;
                setRuntimeCycleCompletedCountShared(0);
                setRuntimeCycleEnteredCountShared(0);
                togetherLastNoNewSignature = "";
                togetherLastCycleCompleteAt = 0L;
                togetherCycleRestartRequested = false;
                lastLoginBlockHandleAt = 0L;
                togetherRefreshedInRun = false;
                resetGlobalCardFlowState();
                log("运行流程开始：启动软件 -> 判断首页 -> 点击一起聊 -> 校验一起聊界面");
                String logPath = ModuleRunFileLogger.getCurrentLogPath(activity.getApplicationContext());
                if (!TextUtils.isEmpty(logPath)) {
                    log("本次运行日志文件=" + logPath);
                }
                reportRuntimeStatsToModule(
                        resolveRuntimeRunStartedAtFromShared(),
                        getRuntimeCycleCompletedCountShared(),
                        getRuntimeCycleEnteredCountShared()
                );
                return;
            }
            if (from == ModuleSettings.EngineStatus.RUNNING && to != ModuleSettings.EngineStatus.RUNNING) {
                flowState = RunFlowState.IDLE;
                togetherEnterStartAt = 0L;
                lastEnterTogetherAttemptAt = 0L;
                togetherPageEnteredAt = 0L;
                togetherRefreshAt = 0L;
                lastTogetherRefreshAttemptAt = 0L;
                togetherScrollAt = 0L;
                lastTogetherScrollAttemptAt = 0L;
                togetherNoNewCardStreak = 0;
                togetherNoNewSameSignatureStreak = 0;
                togetherCycleCompletedCount = 0;
                togetherCycleLiveRoomEnteredCount = 0;
                setRuntimeRunStartedAtShared(0L);
                setRuntimeCycleCompletedCountShared(0);
                setRuntimeCycleEnteredCountShared(0);
                togetherLastNoNewSignature = "";
                togetherLastCycleCompleteAt = 0L;
                togetherCycleRestartRequested = false;
                lastLoginBlockHandleAt = 0L;
                togetherRefreshedInRun = false;
                liveRoomScriptHandledInSession = false;
                lastFlowLogMessage = "";
                resetAwaitingLiveRoomFlag();
                reportRuntimeStatsToModule(0L, 0, 0);
            }
        }

        private void reportEngineStatusToModule(ModuleSettings.EngineStatus status) {
            Context appContext = activity.getApplicationContext();
            if (appContext == null || status == null) {
                return;
            }
            try {
                Intent intent = new Intent(ModuleSettings.ACTION_ENGINE_STATUS_REPORT);
                intent.setPackage(ModuleSettings.MODULE_PACKAGE);
                String command = ModuleSettings.commandForStatus(status);
                intent.putExtra(ModuleSettings.EXTRA_ENGINE_STATUS, status.name());
                intent.putExtra(ModuleSettings.EXTRA_ENGINE_COMMAND, command);
                intent.putExtra(ModuleSettings.EXTRA_ENGINE_COMMAND_SEQ, resolveEffectiveCommandSeq());
                appContext.sendBroadcast(intent);
            } catch (Throwable e) {
                log("report engine status failed: " + e);
            }
        }

        private void runMainFlow(long now) {
            View root = getRootView();
            if (root == null) {
                logFlow("未获取到根节点，等待下一轮");
                return;
            }
            if (handleLoginBlockDialog(root, now)) {
                return;
            }
            if (!isHomeActivity()) {
                logFlow("当前非 HomeActivity，等待返回首页容器页。activity=" + activity.getClass().getName());
                return;
            }

            if (isTogetherPage(root)) {
                if (flowState != RunFlowState.IN_TOGETHER) {
                    flowState = RunFlowState.IN_TOGETHER;
                    togetherPageEnteredAt = now;
                    log("已进入一起聊界面，识别成功");
                }
                if (handlePendingLiveRoomEntryInTogether(now)) {
                    return;
                }
                if (!togetherRefreshedInRun) {
                    handleTogetherInitialRefresh(now);
                    return;
                }
                if (togetherRefreshAt > 0L
                        && now - togetherRefreshAt < UiComponentConfig.TOGETHER_REFRESH_SETTLE_MS) {
                    logFlow("一起聊页：已刷新，等待页面稳定");
                    return;
                }
                handleTogetherCards(root, now);
                return;
            }

            if (isAwaitingLiveRoomScript()) {
                if (now - getLastCardClickAt() > UiComponentConfig.ENTER_TOGETHER_TIMEOUT_MS) {
                    resetAwaitingLiveRoomFlag();
                    log("点击直播卡片后未进入直播间，已超时，恢复列表点击流程");
                } else {
                    logFlow("已点击直播卡片，等待进入直播间");
                }
            }

            boolean isHome = isLookHomePage(root);
            if (!isHome) {
                String missing = findMissingNodeReason(root, UiComponentConfig.LOOK_HOME_NODES);
                if (flowState == RunFlowState.WAIT_TOGETHER
                        && now - togetherEnterStartAt > UiComponentConfig.ENTER_TOGETHER_TIMEOUT_MS) {
                    flowState = RunFlowState.WAIT_HOME;
                    log("等待进入一起聊超时，回退到首页识别阶段");
                    return;
                }
                logFlow("当前不在首页，等待首页出现；缺失节点=" + missing
                        + " activity=" + activity.getClass().getName());
                return;
            }

            if (flowState != RunFlowState.WAIT_TOGETHER) {
                flowState = RunFlowState.WAIT_HOME;
            }

            if (runStartedAt > 0L
                    && now - runStartedAt < UiComponentConfig.STARTUP_WAIT_BEFORE_CLICK_TOGETHER_MS) {
                logFlow("已在首页，等待启动稳定后再点击一起聊");
                return;
            }

            if (now - lastEnterTogetherAttemptAt < UiComponentConfig.RETRY_ENTER_TOGETHER_MS) {
                logFlow("已在首页，等待点击一起聊重试间隔");
                return;
            }

            ClickResult togetherClick = clickTogetherTab(root);
            lastEnterTogetherAttemptAt = now;
            if (togetherClick.success) {
                togetherEnterStartAt = now;
                flowState = RunFlowState.WAIT_TOGETHER;
                log("首页识别成功，已点击“一起聊”按钮，等待进入一起聊界面。detail="
                        + togetherClick.target);
            } else {
                logFlow("首页识别成功，但未完成一起聊点击。reason=" + togetherClick.target);
            }
        }

        private boolean handleLoginBlockDialog(View root, long now) {
            if (root == null) {
                return false;
            }
            if (!hasAllNodes(root, UiComponentConfig.LOGIN_BLOCK_DIALOG_NODES)) {
                return false;
            }
            if (now - lastLoginBlockHandleAt < 1000L) {
                return true;
            }
            lastLoginBlockHandleAt = now;
            try {
                activity.onBackPressed();
                log("检测到登录拦截界面（menu_login_with_setting+qq+weibo+phone+cloudmusic），已执行返回");
            } catch (Throwable e) {
                log("检测到登录拦截界面，但返回失败: " + e);
            }
            return true;
        }

        private boolean handlePendingLiveRoomEntryInTogether(long now) {
            if (!isAwaitingLiveRoomScript()) {
                return false;
            }
            long waited = now - getLastCardClickAt();
            if (waited > UiComponentConfig.ENTER_TOGETHER_TIMEOUT_MS) {
                resetAwaitingLiveRoomFlag();
                flowState = RunFlowState.IN_TOGETHER;
                log("点击直播卡片后未进入直播间（仍在一起聊界面），进入检测超时，恢复列表点击流程");
                return false;
            }
            logFlow("一起聊页：已点击卡片，等待进入直播间并执行校验");
            return true;
        }

        private void handleTogetherInitialRefresh(long now) {
            if (togetherPageEnteredAt <= 0L) {
                togetherPageEnteredAt = now;
            }
            if (now - togetherPageEnteredAt < UiComponentConfig.TOGETHER_REFRESH_WAIT_BEFORE_PULL_MS) {
                logFlow("一起聊页：等待后执行下滑刷新");
                return;
            }
            if (now - lastTogetherRefreshAttemptAt < UiComponentConfig.TOGETHER_REFRESH_RETRY_INTERVAL_MS) {
                logFlow("一起聊页：下滑刷新重试间隔中");
                return;
            }
            lastTogetherRefreshAttemptAt = now;
            if (!performTogetherPullRefresh()) {
                logFlow("一起聊页：下滑刷新执行失败，等待重试");
                return;
            }
            togetherRefreshedInRun = true;
            togetherRefreshAt = now;
            log("一起聊页：已执行下滑刷新，等待页面稳定后继续");
        }

        private void handleTogetherCards(View root, long now) {
            if (isAwaitingLiveRoomScript()) {
                logFlow("一起聊页：等待直播间任务执行");
                return;
            }
            if (now - getLastLiveRoomReturnAt() < UiComponentConfig.LIVE_ROOM_RETURN_TOGETHER_WAIT_MS) {
                logFlow("一起聊页：直播间返回后等待1秒再点击下一卡片");
                return;
            }
            if (now - getLastCardClickAt() < UiComponentConfig.CARD_CLICK_COOLDOWN_MS) {
                logFlow("一起聊页：直播卡片点击冷却中");
                return;
            }
            if (togetherScrollAt > 0L
                    && now - togetherScrollAt < UiComponentConfig.TOGETHER_SCROLL_SETTLE_MS) {
                logFlow("一起聊页：上滑后等待页面稳定");
                return;
            }
            CardPickResult pick = pickNextTogetherCard(root);
            if (pick == null || pick.next == null) {
                if (now - lastTogetherScrollAttemptAt < UiComponentConfig.TOGETHER_SCROLL_RETRY_INTERVAL_MS) {
                    logFlow("一起聊页：无新卡片，等待上滑重试间隔");
                    return;
                }
                lastTogetherScrollAttemptAt = now;
                boolean swiped = performTogetherScrollUp();
                if (swiped) {
                    togetherScrollAt = now;
                    togetherNoNewCardStreak++;
                    int visible = pick == null ? 0 : pick.visibleTotal;
                    int processed = pick == null ? 0 : pick.processedCount;
                    int unprocessed = pick == null ? 0 : pick.unprocessedCount;
                    String signature = pick == null ? "" : pick.visibleKeySignature;
                    updateNoNewSignatureStreak(signature);
                    log("一起聊页：当前可见区域无新卡片，执行上滑加载更多。visible="
                            + visible + " processed=" + processed + " unprocessed=" + unprocessed
                            + " emptyStreak=" + togetherNoNewCardStreak);
                    if (shouldFinishTogetherCycle(now, pick)) {
                        requestRestartForNextTogetherCycle(now);
                    }
                } else {
                    logFlow("一起聊页：上滑加载更多失败，等待重试");
                }
                return;
            }
            togetherNoNewCardStreak = 0;
            togetherNoNewSameSignatureStreak = 0;
            togetherLastNoNewSignature = "";
            ClickResult clickResult = clickTogetherCard(pick.next);
            if (clickResult.success) {
                markCardClicked(pick.next.key, now, pick.next.titleCandidates);
                flowState = RunFlowState.WAIT_LIVE_ROOM;
                log("已点击直播卡片，key=" + pick.next.key
                        + " clickTarget=" + clickResult.target
                        + " titleCandidates=" + pick.next.titleCandidates
                        + " visible=" + pick.visibleTotal
                        + " processed=" + pick.processedCount
                        + " unprocessed=" + pick.unprocessedCount);
            } else {
                logFlow("直播卡片点击失败，等待重试 key=" + pick.next.key + " reason=" + clickResult.target);
            }
        }

        private void updateNoNewSignatureStreak(String signature) {
            String current = safeTrim(signature);
            if (TextUtils.isEmpty(current)) {
                togetherNoNewSameSignatureStreak = 0;
                togetherLastNoNewSignature = "";
                return;
            }
            if (current.equals(togetherLastNoNewSignature)) {
                togetherNoNewSameSignatureStreak++;
                return;
            }
            togetherLastNoNewSignature = current;
            togetherNoNewSameSignatureStreak = 1;
        }

        private boolean shouldFinishTogetherCycle(long now, CardPickResult pick) {
            if (pick == null) {
                return false;
            }
            if (pick.visibleTotal <= 0 || pick.unprocessedCount > 0) {
                return false;
            }
            if (getProcessedCardCount() <= 0) {
                return false;
            }
            if (togetherCycleRestartRequested) {
                return false;
            }
            long waitMs = resolveTogetherCycleRestartDelayMs();
            if (now - togetherLastCycleCompleteAt < waitMs) {
                return false;
            }
            boolean emptyStreakReached = togetherNoNewCardStreak
                    >= UiComponentConfig.TOGETHER_CYCLE_COMPLETE_EMPTY_STREAK;
            boolean signatureStreakReached = togetherNoNewSameSignatureStreak
                    >= UiComponentConfig.TOGETHER_CYCLE_COMPLETE_SAME_SIGNATURE_STREAK;
            return emptyStreakReached && signatureStreakReached;
        }

        private void requestRestartForNextTogetherCycle(final long now) {
            int baseCompleted = Math.max(
                    togetherCycleCompletedCount,
                    resolveRuntimeCycleCompletedFromShared()
            );
            int completed = baseCompleted + 1;
            togetherCycleCompletedCount = completed;
            setRuntimeCycleCompletedCountShared(completed);
            int cycleLimit = resolveTogetherCycleLimit();
            long restartDelayMs = resolveTogetherCycleRestartDelayMs();
            int enteredCount = Math.max(0, getRuntimeCycleEnteredCountShared());
            String cycleCompleteMessage = buildCycleCompleteMessage(enteredCount, completed, cycleLimit);
            notifyCycleCompleteToModule(cycleCompleteMessage);
            togetherCycleLiveRoomEnteredCount = 0;
            setRuntimeCycleEnteredCountShared(0);
            reportRuntimeStatsToModule(
                    resolveRuntimeRunStartedAtFromShared(),
                    getRuntimeCycleCompletedCountShared(),
                    getRuntimeCycleEnteredCountShared()
            );
            togetherLastCycleCompleteAt = now;
            if (cycleLimit > 0 && completed >= cycleLimit) {
                stopEngineAfterCycleLimitReached(completed, cycleLimit);
                return;
            }
            togetherCycleRestartRequested = true;
            final int processedCount = getProcessedCardCount();
            log("一起聊页：判定本轮卡片处理完成，准备重启 LOOK 进入下一轮。processedKeys="
                    + processedCount
                    + " enteredLiveRooms=" + enteredCount
                    + " completedCycles=" + completed
                    + " cycleLimit=" + cycleLimit
                    + " cycleCompleteCond=emptyStreak>="
                    + UiComponentConfig.TOGETHER_CYCLE_COMPLETE_EMPTY_STREAK
                    + " && signatureStreak>="
                    + UiComponentConfig.TOGETHER_CYCLE_COMPLETE_SAME_SIGNATURE_STREAK
                    + " emptyStreak=" + togetherNoNewCardStreak
                    + " signatureStreak=" + togetherNoNewSameSignatureStreak
                    + " restartDelayMs=" + restartDelayMs);
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!started) {
                        togetherCycleRestartRequested = false;
                        return;
                    }
                    if (resolveEffectiveEngineStatus() != ModuleSettings.EngineStatus.RUNNING) {
                        togetherCycleRestartRequested = false;
                        return;
                    }
                    try {
                        requestModuleRestartTargetApp(activity.getApplicationContext(), activity);
                    } finally {
                        togetherCycleRestartRequested = false;
                    }
                }
            }, restartDelayMs);
        }

        private int resolveTogetherCycleLimit() {
            int realtime = getRealtimeTogetherCycleLimit();
            if (realtime >= 0) {
                return realtime;
            }
            return ModuleSettings.getTogetherCycleLimit();
        }

        private long resolveTogetherCycleRestartDelayMs() {
            int realtime = getRealtimeTogetherCycleWaitSeconds();
            long seconds = realtime >= 0 ? realtime : ModuleSettings.getTogetherCycleWaitSeconds();
            if (seconds < 0L) {
                seconds = 0L;
            }
            if (seconds > 86400L) {
                seconds = 86400L;
            }
            return seconds * 1000L;
        }

        private void onLiveRoomEntered(String liveRoomTitle) {
            if (runStartedAt <= 0L) {
                runStartedAt = resolveRuntimeRunStartedAtFromShared();
                if (runStartedAt <= 0L) {
                    runStartedAt = System.currentTimeMillis();
                    setRuntimeRunStartedAtShared(runStartedAt);
                    log("直播间进入时发现 runStartedAt=0，已自动补齐当前时间戳");
                }
            }
            int recoveredEntered = resolveRuntimeCycleEnteredFromShared();
            if (recoveredEntered > getRuntimeCycleEnteredCountShared()) {
                setRuntimeCycleEnteredCountShared(recoveredEntered);
            }
            int entered = incrementRuntimeCycleEnteredShared();
            togetherCycleLiveRoomEnteredCount = entered;
            togetherCycleCompletedCount = resolveRuntimeCycleCompletedFromShared();
            setRuntimeCycleCompletedCountShared(togetherCycleCompletedCount);
            String title = safeTrim(liveRoomTitle);
            if (!TextUtils.isEmpty(title)) {
                log("直播间进入计数+1，本轮累计=" + togetherCycleLiveRoomEnteredCount + " title=" + title);
            } else {
                log("直播间进入计数+1，本轮累计=" + togetherCycleLiveRoomEnteredCount);
            }
            reportRuntimeStatsToModule(
                    resolveRuntimeRunStartedAtFromShared(),
                    getRuntimeCycleCompletedCountShared(),
                    getRuntimeCycleEnteredCountShared()
            );
        }

        private String buildCycleCompleteMessage(int enteredCount, int completedCycles, int cycleLimit) {
            int remaining = cycleLimit <= 0 ? -1 : Math.max(0, cycleLimit - completedCycles);
            String remainingText = remaining < 0 ? "无限" : String.valueOf(remaining);
            String totalText = cycleLimit <= 0 ? "无限" : String.valueOf(cycleLimit);
            return "第 " + Math.max(0, completedCycles) + "/" + totalText
                    + " 轮完成，本轮进入直播间 " + Math.max(0, enteredCount)
                    + " 个，剩余 " + remainingText + " 轮";
        }

        private void notifyCycleCompleteToModule(String message) {
            String msg = safeTrim(message);
            if (TextUtils.isEmpty(msg)) {
                return;
            }
            Context appContext = activity.getApplicationContext();
            if (appContext == null) {
                return;
            }
            try {
                Intent intent = new Intent(ModuleSettings.ACTION_CYCLE_COMPLETE_NOTICE);
                intent.setPackage(ModuleSettings.MODULE_PACKAGE);
                intent.putExtra(ModuleSettings.EXTRA_CYCLE_COMPLETE_MESSAGE, msg);
                appContext.sendBroadcast(intent);
            } catch (Throwable e) {
                log("循环完成提示广播发送失败: " + e);
            }
        }

        private void reportRuntimeStatsToModule(long runStartAt, int cycleCompleted, int cycleEntered) {
            Context appContext = activity.getApplicationContext();
            if (appContext == null) {
                return;
            }
            try {
                Intent intent = new Intent(ModuleSettings.ACTION_RUNTIME_STATS_REPORT);
                intent.setPackage(ModuleSettings.MODULE_PACKAGE);
                intent.putExtra(ModuleSettings.EXTRA_RUNTIME_RUN_START_AT, Math.max(0L, runStartAt));
                intent.putExtra(
                        ModuleSettings.EXTRA_RUNTIME_CYCLE_COMPLETED,
                        Math.max(0, cycleCompleted)
                );
                intent.putExtra(
                        ModuleSettings.EXTRA_RUNTIME_CYCLE_ENTERED,
                        Math.max(0, cycleEntered)
                );
                intent.putExtra(
                        ModuleSettings.EXTRA_RUNTIME_COMMAND_SEQ,
                        Math.max(0L, resolveEffectiveCommandSeq())
                );
                appContext.sendBroadcast(intent);
            } catch (Throwable e) {
                log("report runtime stats failed: " + e);
            }
        }

        private long resolveRuntimeRunStartedAtFromShared() {
            long shared = getRuntimeRunStartedAtShared();
            if (shared > 0L) {
                return shared;
            }
            long persisted = ModuleSettings.getRuntimeRunStartAt();
            if (persisted > 0L) {
                setRuntimeRunStartedAtShared(persisted);
                return persisted;
            }
            return 0L;
        }

        private int resolveRuntimeCycleCompletedFromShared() {
            int shared = getRuntimeCycleCompletedCountShared();
            if (shared > 0) {
                return shared;
            }
            return ModuleSettings.getRuntimeCycleCompleted();
        }

        private int resolveRuntimeCycleEnteredFromShared() {
            int shared = getRuntimeCycleEnteredCountShared();
            if (shared > 0) {
                return shared;
            }
            return ModuleSettings.getRuntimeCycleEntered();
        }

        private void stopEngineAfterCycleLimitReached(int completed, int cycleLimit) {
            long nextSeq = resolveEffectiveCommandSeq();
            if (nextSeq <= 0L) {
                nextSeq = 1L;
            } else {
                nextSeq += 1L;
            }
            long runStartAt = resolveRuntimeRunStartedAtFromShared();
            long durationMs = runStartAt > 0L
                    ? Math.max(0L, System.currentTimeMillis() - runStartAt)
                    : 0L;
            updateRealtimeEngineState(
                    ModuleSettings.ENGINE_CMD_STOP,
                    ModuleSettings.EngineStatus.STOPPED,
                    nextSeq,
                    -1,
                    -1,
                    -1L,
                    -1,
                    -1
            );
            reportEngineStatusToModule(ModuleSettings.EngineStatus.STOPPED);
            notifyCycleLimitFinishedToModule(completed, cycleLimit, durationMs);
            log("一起聊页：已完成循环次数上限，自动停止模块。completedCycles="
                    + completed + " cycleLimit=" + cycleLimit + " durationMs=" + durationMs);
        }

        private void notifyCycleLimitFinishedToModule(int completed, int cycleLimit, long durationMs) {
            Context appContext = activity.getApplicationContext();
            if (appContext == null) {
                return;
            }
            try {
                Intent intent = new Intent(ModuleSettings.ACTION_CYCLE_LIMIT_FINISHED);
                intent.setPackage(ModuleSettings.MODULE_PACKAGE);
                intent.putExtra(ModuleSettings.EXTRA_FINISHED_CYCLES, Math.max(0, completed));
                intent.putExtra(ModuleSettings.EXTRA_FINISHED_CYCLE_LIMIT, Math.max(0, cycleLimit));
                intent.putExtra(ModuleSettings.EXTRA_FINISHED_DURATION_MS, Math.max(0L, durationMs));
                appContext.sendBroadcast(intent);
            } catch (Throwable e) {
                log("循环上限完成广播发送失败: " + e);
            }
        }

        private void requestModuleRestartTargetApp(Context appContext, Activity activity) {
            if (appContext == null) {
                return;
            }
            Intent intent = new Intent(ModuleSettings.ACTION_SYNC_FLOAT_SERVICE);
            intent.setPackage(ModuleSettings.MODULE_PACKAGE);
            int displayId = -1;
            if (activity != null && activity.getDisplay() != null) {
                displayId = activity.getDisplay().getDisplayId();
            }
            if (displayId >= 0) {
                intent.putExtra(ModuleSettings.EXTRA_TARGET_DISPLAY_ID, displayId);
            }
            intent.putExtra(ModuleSettings.EXTRA_REQUEST_RESTART_TARGET_APP, true);
            appContext.sendBroadcast(intent);
            log("一起聊页：已请求模块服务重启 LOOK，displayId=" + displayId);
        }

        private boolean performTogetherPullRefresh() {
            View root = getRootView();
            if (root == null) {
                return false;
            }
            int width = root.getWidth();
            int height = root.getHeight();
            if (width <= 0 || height <= 0) {
                return false;
            }
            float x = clamp(
                    width * UiComponentConfig.TOGETHER_REFRESH_SWIPE_X_RATIO,
                    1f,
                    Math.max(1f, width - 1f)
            );
            float startY = clamp(
                    height * UiComponentConfig.TOGETHER_REFRESH_SWIPE_START_Y_RATIO,
                    1f,
                    Math.max(1f, height - 1f)
            );
            float endY = clamp(
                    height * UiComponentConfig.TOGETHER_REFRESH_SWIPE_END_Y_RATIO,
                    1f,
                    Math.max(1f, height - 1f)
            );
            return dispatchSwipeTouch(
                    root,
                    x,
                    startY,
                    x,
                    endY,
                    UiComponentConfig.TOUCH_SWIPE_DURATION_MS,
                    UiComponentConfig.TOUCH_SWIPE_MOVE_STEPS
            );
        }

        private boolean performTogetherScrollUp() {
            View root = getRootView();
            if (root == null) {
                return false;
            }
            int width = root.getWidth();
            int height = root.getHeight();
            if (width <= 0 || height <= 0) {
                return false;
            }
            float x = clamp(
                    width * UiComponentConfig.TOGETHER_SCROLL_SWIPE_X_RATIO,
                    1f,
                    Math.max(1f, width - 1f)
            );
            float startY = clamp(
                    height * UiComponentConfig.TOGETHER_SCROLL_SWIPE_START_Y_RATIO,
                    1f,
                    Math.max(1f, height - 1f)
            );
            float endY = clamp(
                    height * UiComponentConfig.TOGETHER_SCROLL_SWIPE_END_Y_RATIO,
                    1f,
                    Math.max(1f, height - 1f)
            );
            return dispatchSwipeTouch(
                    root,
                    x,
                    startY,
                    x,
                    endY,
                    UiComponentConfig.TOUCH_SWIPE_DURATION_MS,
                    UiComponentConfig.TOUCH_SWIPE_MOVE_STEPS
            );
        }

        private boolean dispatchSwipeTouch(
                View target,
                float startX,
                float startY,
                float endX,
                float endY,
                long durationMs,
                int steps
        ) {
            if (target == null || durationMs <= 0L) {
                return false;
            }
            int moveSteps = Math.max(1, steps);
            long downTime = SystemClock.uptimeMillis();
            boolean handled = false;
            MotionEvent down = MotionEvent.obtain(
                    downTime,
                    downTime,
                    MotionEvent.ACTION_DOWN,
                    startX,
                    startY,
                    0
            );
            try {
                handled |= target.dispatchTouchEvent(down);
            } finally {
                down.recycle();
            }
            for (int i = 1; i <= moveSteps; i++) {
                float fraction = i / (float) moveSteps;
                float x = startX + (endX - startX) * fraction;
                float y = startY + (endY - startY) * fraction;
                long eventTime = downTime + (long) (durationMs * fraction);
                MotionEvent move = MotionEvent.obtain(
                        downTime,
                        eventTime,
                        MotionEvent.ACTION_MOVE,
                        x,
                        y,
                        0
                );
                try {
                    handled |= target.dispatchTouchEvent(move);
                } finally {
                    move.recycle();
                }
            }
            MotionEvent up = MotionEvent.obtain(
                    downTime,
                    downTime + durationMs + 16L,
                    MotionEvent.ACTION_UP,
                    endX,
                    endY,
                    0
            );
            try {
                handled |= target.dispatchTouchEvent(up);
            } finally {
                up.recycle();
            }
            return handled;
        }

        private float clamp(float value, float min, float max) {
            return Math.max(min, Math.min(max, value));
        }

        private void runLiveRoomFlow(long now) {
            liveRoomScriptHandledInSession = LiveRoomRuntimeModule.run(
                    now,
                    activity,
                    liveRoomScriptHandledInSession,
                    liveRoomRuntimeBridge
            );
        }

        private void logFlow(String msg) {
            String normalized = safeTrim(msg);
            if (TextUtils.isEmpty(normalized)) {
                return;
            }
            if (normalized.equals(lastFlowLogMessage)) {
                return;
            }
            long now = System.currentTimeMillis();
            if (now - lastFlowLogAt < UiComponentConfig.FLOW_LOG_INTERVAL_MS) {
                return;
            }
            lastFlowLogAt = now;
            lastFlowLogMessage = normalized;
            log(normalized);
        }

        private void syncCommandLog() {
            long seq = resolveEffectiveCommandSeq();
            if (seq == lastCommandSeq) {
                return;
            }
            lastCommandSeq = seq;
            if (!markCommandSeqHandled(seq)) {
                return;
            }
            String cmd = resolveEffectiveCommand();
            if (!TextUtils.isEmpty(cmd)) {
                log("receive command: " + cmd);
            }
            if (ModuleSettings.ENGINE_CMD_RUN.equalsIgnoreCase(cmd)) {
                ModuleRunFileLogger.prepareForNewRun(activity.getApplicationContext(), seq);
                String logPath = ModuleRunFileLogger.getCurrentLogPath(activity.getApplicationContext());
                if (!TextUtils.isEmpty(logPath)) {
                    log("运行命令触发日志轮转，当前日志文件=" + logPath);
                }
            }
        }

        private long resolveEffectiveCommandSeq() {
            long realtimeSeq = getRealtimeCommandSeq();
            if (realtimeSeq > 0L) {
                return realtimeSeq;
            }
            return ModuleSettings.getEngineCommandSeq();
        }

        private String resolveEffectiveCommand() {
            String realtimeCmd = getRealtimeCommand();
            if (!TextUtils.isEmpty(realtimeCmd)) {
                return realtimeCmd;
            }
            return ModuleSettings.getEngineCommand();
        }

        private boolean isLookHomePage(View root) {
            return hasAllNodes(root, UiComponentConfig.LOOK_HOME_NODES);
        }

        private boolean isTogetherPage(View root) {
            return hasAllNodes(root, UiComponentConfig.TOGETHER_PAGE_NODES);
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

        private boolean hasAllNodes(View root, java.util.List<UiComponentConfig.UiNodeSpec> specs) {
            if (root == null || specs == null || specs.isEmpty()) {
                return false;
            }
            for (UiComponentConfig.UiNodeSpec spec : specs) {
                if (findFirstNode(root, spec) == null) {
                    return false;
                }
            }
            return true;
        }

        private String findMissingNodeReason(View root, java.util.List<UiComponentConfig.UiNodeSpec> specs) {
            if (root == null || specs == null || specs.isEmpty()) {
                return "root/specs empty";
            }
            for (UiComponentConfig.UiNodeSpec spec : specs) {
                if (findFirstNode(root, spec) == null) {
                    return describeSpec(spec);
                }
            }
            return "none";
        }

        private String describeSpec(UiComponentConfig.UiNodeSpec spec) {
            if (spec == null) {
                return "null";
            }
            return "text=" + spec.text
                    + ", id=" + spec.fullResourceId
                    + ", class=" + spec.className
                    + ", selected=" + String.valueOf(spec.selected);
        }

        private ClickResult clickTogetherTab(View root) {
            View togetherNode = findFirstNode(root, UiComponentConfig.TOGETHER_TAB_CLICK_NODE);
            if (togetherNode == null) {
                return new ClickResult(false, "together_tab_not_found");
            }
            return clickWithParentFallbackDetailed(togetherNode, "together_tab");
        }

        private ClickResult clickTogetherCard(TogetherCard card) {
            if (card == null || card.view == null) {
                return new ClickResult(false, "card_view_null");
            }
            List<ClickTarget> targets = new ArrayList<ClickTarget>(8);
            View cover = findFirstDescendantByResourceEntry(
                    card.view,
                    UiComponentConfig.TOGETHER_CARD_COVER_RESOURCE_ENTRY
            );
            if (cover != null) {
                targets.add(new ClickTarget(
                        "cover",
                        cover,
                        UiComponentConfig.TOGETHER_CARD_COVER_TAP_X_RATIO,
                        UiComponentConfig.TOGETHER_CARD_COVER_TAP_Y_RATIO
                ));
            }
            if (card.view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) card.view;
                int start = Math.max(0, UiComponentConfig.TOGETHER_CARD_CLICK_TITLE_INDEX_START);
                int end = Math.min(group.getChildCount() - 1, UiComponentConfig.TOGETHER_CARD_CLICK_TITLE_INDEX_END);
                for (int i = start; i <= end; i++) {
                    View child = group.getChildAt(i);
                    if (child == null) {
                        continue;
                    }
                    targets.add(new ClickTarget(
                            "title_index_" + i,
                            child,
                            UiComponentConfig.TOGETHER_CARD_TAP_X_RATIO,
                            UiComponentConfig.TOGETHER_CARD_TAP_Y_RATIO
                    ));
                }
            }
            targets.add(new ClickTarget(
                    "card_root_primary",
                    card.view,
                    UiComponentConfig.TOGETHER_CARD_TAP_X_RATIO,
                    UiComponentConfig.TOGETHER_CARD_TAP_Y_RATIO
            ));
            targets.add(new ClickTarget(
                    "card_root_alt",
                    card.view,
                    UiComponentConfig.TOGETHER_CARD_TAP_X_RATIO,
                    UiComponentConfig.TOGETHER_CARD_TAP_ALT_Y_RATIO
            ));

            for (ClickTarget target : targets) {
                if (target == null || target.view == null || !target.view.isShown()) {
                    continue;
                }
                boolean tapped = tapViewAtRatio(target.view, target.xRatio, target.yRatio);
                if (tapped) {
                    return new ClickResult(
                            true,
                            target.name + " via=tap_ratio(" + target.xRatio + "," + target.yRatio + ")"
                                    + " target=" + describeViewForLog(target.view)
                    );
                }
                ClickResult fallback = clickWithParentFallbackDetailed(target.view, target.name);
                if (fallback.success) {
                    return fallback;
                }
            }
            return new ClickResult(false, "all_click_targets_failed");
        }

        private View findFirstDescendantByResourceEntry(View root, String expectedEntry) {
            String entry = safeTrim(expectedEntry);
            if (root == null || TextUtils.isEmpty(entry)) {
                return null;
            }
            ArrayDeque<View> stack = new ArrayDeque<View>();
            stack.push(root);
            while (!stack.isEmpty()) {
                View current = stack.pop();
                if (entry.equals(resolveResourceEntryName(current))) {
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

        private String describeViewForLog(View view) {
            if (view == null) {
                return "null";
            }
            String cls = view.getClass().getName();
            String fullId = resolveResourceFullName(view);
            if (TextUtils.isEmpty(fullId)) {
                fullId = resolveResourceEntryName(view);
            }
            String text = "";
            if (view instanceof TextView) {
                text = safeTrim(String.valueOf(((TextView) view).getText()));
                if (text.length() > 40) {
                    text = text.substring(0, 40) + "...";
                }
            }
            Rect rect = new Rect();
            if (!view.getGlobalVisibleRect(rect)) {
                rect.setEmpty();
            }
            return "class=" + cls
                    + ", id=" + fullId
                    + ", text=" + text
                    + ", clickable=" + view.isClickable()
                    + ", shown=" + view.isShown()
                    + ", bounds=" + rect.toShortString();
        }

        private CardPickResult pickNextTogetherCard(View root) {
            View container = findFirstNode(root, UiComponentConfig.TOGETHER_ROOM_LIST_CONTAINER_NODE);
            if (container == null) {
                return new CardPickResult(null, 0, 0, 0, "");
            }
            List<TogetherCard> cards = collectTogetherCards(container);
            if (cards.isEmpty()) {
                return new CardPickResult(null, 0, 0, 0, "");
            }
            TogetherCard firstUnprocessed = null;
            int processed = 0;
            int unprocessed = 0;
            for (TogetherCard card : cards) {
                if (!isCardProcessed(card.key)) {
                    unprocessed++;
                    if (firstUnprocessed == null) {
                        firstUnprocessed = card;
                    }
                    continue;
                }
                processed++;
            }
            return new CardPickResult(
                    firstUnprocessed,
                    cards.size(),
                    processed,
                    unprocessed,
                    buildVisibleCardSignature(cards)
            );
        }

        private String buildVisibleCardSignature(List<TogetherCard> cards) {
            if (cards == null || cards.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            int count = 0;
            for (TogetherCard card : cards) {
                if (card == null || TextUtils.isEmpty(card.key)) {
                    continue;
                }
                if (count > 0) {
                    sb.append("||");
                }
                sb.append(card.key);
                count++;
                if (count >= 8) {
                    break;
                }
            }
            return safeTrim(sb.toString());
        }

        private List<TogetherCard> collectTogetherCards(View container) {
            LinkedHashMap<String, TogetherCard> deduped = new LinkedHashMap<String, TogetherCard>(16);
            int rootHeight = resolveRootHeight(container);
            ArrayDeque<View> stack = new ArrayDeque<View>();
            stack.push(container);
            while (!stack.isEmpty()) {
                View current = stack.pop();
                if (isTogetherCardView(current) && isCardInSafeClickArea(current, rootHeight)) {
                    List<String> texts = collectCardTexts(current);
                    List<String> titleCandidates = collectCardTitleCandidates(current, texts);
                    String key = buildCardKey(current, texts, titleCandidates);
                    if (!TextUtils.isEmpty(key) && !deduped.containsKey(key)) {
                        deduped.put(key, new TogetherCard(current, key, titleCandidates));
                    }
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
            return new ArrayList<TogetherCard>(deduped.values());
        }

        private int resolveRootHeight(View container) {
            if (container != null) {
                View root = container.getRootView();
                if (root != null && root.getHeight() > 0) {
                    return root.getHeight();
                }
            }
            try {
                View root = getRootView();
                if (root != null && root.getHeight() > 0) {
                    return root.getHeight();
                }
            } catch (Throwable ignore) {
            }
            return 0;
        }

        private boolean isCardInSafeClickArea(View card, int rootHeight) {
            if (card == null) {
                return false;
            }
            if (rootHeight <= 0) {
                return true;
            }
            Rect rect = new Rect();
            if (!card.getGlobalVisibleRect(rect)) {
                return false;
            }
            if (rect.width() <= 0 || rect.height() <= 0) {
                return false;
            }
            float safeBottom = rootHeight * UiComponentConfig.TOGETHER_CARD_SAFE_BOTTOM_Y_RATIO;
            return rect.bottom <= safeBottom;
        }

        private boolean isTogetherCardView(View view) {
            if (view == null || !view.isShown() || !view.isClickable()) {
                return false;
            }
            return view instanceof ViewGroup;
        }

        private String buildCardKey(View card, List<String> texts, List<String> titleCandidates) {
            if (titleCandidates != null && !titleCandidates.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < titleCandidates.size() && i < 2; i++) {
                    if (i > 0) {
                        sb.append('|');
                    }
                    sb.append(titleCandidates.get(i));
                }
                String key = safeTrim(sb.toString());
                if (!TextUtils.isEmpty(key)) {
                    return key;
                }
            }
            if (!texts.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < texts.size() && i < 2; i++) {
                    if (i > 0) {
                        sb.append('|');
                    }
                    sb.append(texts.get(i));
                }
                return sb.toString();
            }
            String entry = resolveResourceEntryName(card);
            if (!TextUtils.isEmpty(entry)) {
                int childCount = 0;
                if (card instanceof ViewGroup) {
                    childCount = ((ViewGroup) card).getChildCount();
                }
                return "id:" + entry + "|children:" + childCount;
            }
            return "";
        }

        private List<String> collectCardTitleCandidates(View card, List<String> fallbackTexts) {
            List<String> out = new ArrayList<String>(4);
            if (card instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) card;
                int start = Math.max(0, UiComponentConfig.TOGETHER_CARD_TITLE_INDEX_START);
                int end = Math.min(group.getChildCount() - 1, UiComponentConfig.TOGETHER_CARD_TITLE_INDEX_END);
                for (int i = start; i <= end; i++) {
                    View child = group.getChildAt(i);
                    collectTextsFromNode(child, out, true);
                }
            }
            if (out.isEmpty() && fallbackTexts != null) {
                for (String text : fallbackTexts) {
                    addUniqueText(out, text, true);
                }
            }
            return out;
        }

        private List<String> collectCardTexts(View card) {
            List<String> out = new ArrayList<String>(8);
            collectTextsFromNode(card, out, false);
            return out;
        }

        private void collectTextsFromNode(View node, List<String> out, boolean keepPureNumber) {
            if (node == null || out == null) {
                return;
            }
            ArrayDeque<View> stack = new ArrayDeque<View>();
            stack.push(node);
            while (!stack.isEmpty()) {
                View current = stack.pop();
                if (current instanceof TextView) {
                    String text = safeTrim(String.valueOf(((TextView) current).getText()));
                    addUniqueText(out, text, keepPureNumber);
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
        }

        private void addUniqueText(List<String> out, String text, boolean keepPureNumber) {
            if (out == null) {
                return;
            }
            String value = safeTrim(text);
            if (TextUtils.isEmpty(value)) {
                return;
            }
            if ("一起聊".equals(value)) {
                return;
            }
            if (!keepPureNumber && value.matches("^\\d+$")) {
                return;
            }
            if (!out.contains(value)) {
                out.add(value);
            }
        }

        private String resolveLiveRoomTitle(View root) {
            View titleNode = findFirstNode(root, UiComponentConfig.LIVE_ROOM_TITLE_NODE);
            if (!(titleNode instanceof TextView)) {
                return "";
            }
            return safeTrim(String.valueOf(((TextView) titleNode).getText()));
        }

        private boolean isStringInList(List<String> values, String target) {
            if (values == null || values.isEmpty()) {
                return false;
            }
            String normalized = safeTrim(target);
            if (TextUtils.isEmpty(normalized)) {
                return false;
            }
            for (String value : values) {
                if (normalized.equals(safeTrim(value))) {
                    return true;
                }
            }
            return false;
        }

        private View findFirstNode(View root, UiComponentConfig.UiNodeSpec spec) {
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

        private boolean matchesNodeSpec(View view, UiComponentConfig.UiNodeSpec spec) {
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
                String expectedText = safeTrim(spec.text);
                if (!(view instanceof TextView)) {
                    return false;
                }
                CharSequence text = ((TextView) view).getText();
                if (!expectedText.equals(safeTrim(String.valueOf(text)))) {
                    return false;
                }
            }
            return true;
        }

        private ClickResult clickWithParentFallbackDetailed(View start, String clickName) {
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
                            return new ClickResult(
                                    true,
                                    clickName + " via=performClick depth=" + depth
                                            + " target=" + describeViewForLog(current)
                            );
                        }
                    } catch (Throwable ignore) {
                    }
                    try {
                        if (hasValidBounds && current.isClickable() && current.callOnClick()) {
                            return new ClickResult(
                                    true,
                                    clickName + " via=callOnClick depth=" + depth
                                            + " target=" + describeViewForLog(current)
                            );
                        }
                    } catch (Throwable ignore) {
                    }
                    try {
                        boolean allowTapCenter = current.isClickable() || depth == 0;
                        if (hasValidBounds && allowTapCenter && tapViewCenter(current)) {
                            return new ClickResult(
                                    true,
                                    clickName + " via=tap_center depth=" + depth
                                            + " target=" + describeViewForLog(current)
                            );
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
            return new ClickResult(
                    false,
                    clickName + " fallback_failed start=" + describeViewForLog(start)
            );
        }

        private boolean hasUsableVisibleBounds(View target) {
            if (target == null || !target.isShown()) {
                return false;
            }
            Rect rect = new Rect();
            if (!target.getGlobalVisibleRect(rect)) {
                return false;
            }
            return rect.width() > 2 && rect.height() > 2;
        }

        private boolean tapViewCenter(View target) {
            return tapViewAtRatio(target, 0.5f, 0.5f);
        }

        private boolean tapViewAtRatio(View target, float xRatio, float yRatio) {
            if (target == null || !target.isShown()) {
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
            return dispatchTapGlobal(x, y);
        }

        private boolean dispatchTapGlobal(float x, float y) {
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

        private String resolveResourceEntryName(View view) {
            if (view == null || view.getId() == View.NO_ID) {
                return "";
            }
            try {
                return safeTrim(view.getResources().getResourceEntryName(view.getId()));
            } catch (Throwable ignore) {
                return "";
            }
        }

        private String resolveResourceFullName(View view) {
            if (view == null || view.getId() == View.NO_ID) {
                return "";
            }
            try {
                return safeTrim(view.getResources().getResourceName(view.getId()));
            } catch (Throwable ignore) {
                return "";
            }
        }

        private boolean isClassMatched(View view, String expectedClassName) {
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

        private String safeTrim(String s) {
            if (s == null) {
                return "";
            }
            return s.trim();
        }

        private enum RunFlowState {
            IDLE,
            WAIT_HOME,
            WAIT_TOGETHER,
            IN_TOGETHER,
            WAIT_LIVE_ROOM
        }

        private boolean isHomeActivity() {
            String name = activity.getClass().getName();
            return !TextUtils.isEmpty(name) && name.contains(UiComponentConfig.HOME_ACTIVITY_KEYWORD);
        }

        private boolean isLiveRoomActivity() {
            String name = activity.getClass().getName();
            return !TextUtils.isEmpty(name) && name.contains(UiComponentConfig.LIVE_ROOM_ACTIVITY_KEYWORD);
        }

        private final class ClickTarget {
            final String name;
            final View view;
            final float xRatio;
            final float yRatio;

            ClickTarget(String name, View view, float xRatio, float yRatio) {
                this.name = name == null ? "" : name;
                this.view = view;
                this.xRatio = xRatio;
                this.yRatio = yRatio;
            }
        }

        private final class ClickResult {
            final boolean success;
            final String target;

            ClickResult(boolean success, String target) {
                this.success = success;
                this.target = target == null ? "" : target;
            }
        }

        private final class TogetherCard {
            final View view;
            final String key;
            final List<String> titleCandidates;

            TogetherCard(View view, String key, List<String> titleCandidates) {
                this.view = view;
                this.key = key;
                this.titleCandidates = titleCandidates == null
                        ? new ArrayList<String>()
                        : new ArrayList<String>(titleCandidates);
            }
        }

        private final class CardPickResult {
            final TogetherCard next;
            final int visibleTotal;
            final int processedCount;
            final int unprocessedCount;
            final String visibleKeySignature;

            CardPickResult(
                    TogetherCard next,
                    int visibleTotal,
                    int processedCount,
                    int unprocessedCount,
                    String visibleKeySignature
            ) {
                this.next = next;
                this.visibleTotal = Math.max(0, visibleTotal);
                this.processedCount = Math.max(0, processedCount);
                this.unprocessedCount = Math.max(0, unprocessedCount);
                this.visibleKeySignature = visibleKeySignature == null ? "" : visibleKeySignature;
            }
        }
    }

    private static void resetGlobalCardFlowState() {
        synchronized (FLOW_STATE_LOCK) {
            PROCESSED_CARD_KEYS.clear();
            LAST_CLICKED_CARD_TITLE_CANDIDATES.clear();
            sAwaitingLiveRoomScript = false;
            sLastCardClickAt = 0L;
            sLastLiveRoomReturnAt = 0L;
        }
    }

    private static boolean markCommandSeqHandled(long seq) {
        synchronized (FLOW_STATE_LOCK) {
            if (seq == sHandledCommandSeq) {
                return false;
            }
            sHandledCommandSeq = seq;
            return true;
        }
    }

    private static boolean markRunSeqInitialized(long seq) {
        synchronized (FLOW_STATE_LOCK) {
            if (seq == sInitializedRunSeq) {
                return false;
            }
            sInitializedRunSeq = seq;
            return true;
        }
    }

    private static void updateRealtimeEngineState(
            String command,
            ModuleSettings.EngineStatus status,
            long seq,
            int cycleLimit,
            int cycleWaitSeconds,
            long runtimeRunStartAt,
            int runtimeCompleted,
            int runtimeEntered
    ) {
        synchronized (FLOW_STATE_LOCK) {
            sRealtimeCommand = safeTrimStatic(command);
            sRealtimeStatus = status;
            sRealtimeCommandSeq = seq;
            if (cycleLimit >= 0) {
                sRealtimeTogetherCycleLimit = cycleLimit;
            }
            if (cycleWaitSeconds >= 0) {
                sRealtimeTogetherCycleWaitSeconds = cycleWaitSeconds;
            }
            if (runtimeRunStartAt > 0L) {
                if (sRuntimeRunStartedAt <= 0L) {
                    sRuntimeRunStartedAt = runtimeRunStartAt;
                } else {
                    sRuntimeRunStartedAt = Math.min(sRuntimeRunStartedAt, runtimeRunStartAt);
                }
            }
            if (runtimeCompleted >= 0) {
                sRuntimeCycleCompletedCount = Math.max(sRuntimeCycleCompletedCount, runtimeCompleted);
            }
            if (runtimeEntered >= 0) {
                sRuntimeCycleEnteredCount = Math.max(0, runtimeEntered);
            }
            if (status == ModuleSettings.EngineStatus.RUNNING) {
                sAutoRunConsumedForProcess = true;
            }
            if (status != ModuleSettings.EngineStatus.RUNNING) {
                sAwaitingLiveRoomScript = false;
                sRuntimeRunStartedAt = 0L;
                sRuntimeCycleCompletedCount = 0;
                sRuntimeCycleEnteredCount = 0;
            }
        }
    }

    private static long getRuntimeRunStartedAtShared() {
        synchronized (FLOW_STATE_LOCK) {
            return Math.max(0L, sRuntimeRunStartedAt);
        }
    }

    private static void setRuntimeRunStartedAtShared(long value) {
        synchronized (FLOW_STATE_LOCK) {
            sRuntimeRunStartedAt = Math.max(0L, value);
        }
    }

    private static int getRuntimeCycleCompletedCountShared() {
        synchronized (FLOW_STATE_LOCK) {
            return Math.max(0, sRuntimeCycleCompletedCount);
        }
    }

    private static void setRuntimeCycleCompletedCountShared(int value) {
        synchronized (FLOW_STATE_LOCK) {
            sRuntimeCycleCompletedCount = Math.max(0, value);
        }
    }

    private static int getRuntimeCycleEnteredCountShared() {
        synchronized (FLOW_STATE_LOCK) {
            return Math.max(0, sRuntimeCycleEnteredCount);
        }
    }

    private static void setRuntimeCycleEnteredCountShared(int value) {
        synchronized (FLOW_STATE_LOCK) {
            sRuntimeCycleEnteredCount = Math.max(0, value);
        }
    }

    private static int incrementRuntimeCycleEnteredShared() {
        synchronized (FLOW_STATE_LOCK) {
            sRuntimeCycleEnteredCount = Math.max(0, sRuntimeCycleEnteredCount) + 1;
            return sRuntimeCycleEnteredCount;
        }
    }

    private static boolean tryConsumeAutoRunForProcess() {
        synchronized (FLOW_STATE_LOCK) {
            if (sAutoRunConsumedForProcess) {
                return false;
            }
            sAutoRunConsumedForProcess = true;
            return true;
        }
    }

    private static void markAutoRunConsumedForProcess() {
        synchronized (FLOW_STATE_LOCK) {
            sAutoRunConsumedForProcess = true;
        }
    }

    private static ModuleSettings.EngineStatus getRealtimeStatus() {
        synchronized (FLOW_STATE_LOCK) {
            return sRealtimeStatus;
        }
    }

    private static long getRealtimeCommandSeq() {
        synchronized (FLOW_STATE_LOCK) {
            return sRealtimeCommandSeq;
        }
    }

    private static int getRealtimeTogetherCycleLimit() {
        synchronized (FLOW_STATE_LOCK) {
            return sRealtimeTogetherCycleLimit;
        }
    }

    private static int getRealtimeTogetherCycleWaitSeconds() {
        synchronized (FLOW_STATE_LOCK) {
            return sRealtimeTogetherCycleWaitSeconds;
        }
    }

    private static String getRealtimeCommand() {
        synchronized (FLOW_STATE_LOCK) {
            return sRealtimeCommand;
        }
    }

    private static ModuleSettings.EngineStatus parseEngineStatusStatic(String value) {
        String normalized = safeTrimStatic(value).toUpperCase();
        if (ModuleSettings.EngineStatus.RUNNING.name().equals(normalized)) {
            return ModuleSettings.EngineStatus.RUNNING;
        }
        if (ModuleSettings.EngineStatus.PAUSED.name().equals(normalized)) {
            return ModuleSettings.EngineStatus.PAUSED;
        }
        return ModuleSettings.EngineStatus.STOPPED;
    }

    private static void markCardClicked(String key, long now, List<String> titleCandidates) {
        synchronized (FLOW_STATE_LOCK) {
            if (!TextUtils.isEmpty(key)) {
                PROCESSED_CARD_KEYS.add(key);
                if (PROCESSED_CARD_KEYS.size() > 1000) {
                    List<String> keys = new ArrayList<String>(PROCESSED_CARD_KEYS);
                    PROCESSED_CARD_KEYS.clear();
                    for (int i = Math.max(0, keys.size() - 600); i < keys.size(); i++) {
                        PROCESSED_CARD_KEYS.add(keys.get(i));
                    }
                }
            }
            LAST_CLICKED_CARD_TITLE_CANDIDATES.clear();
            if (titleCandidates != null) {
                for (String title : titleCandidates) {
                    String value = title == null ? "" : title.trim();
                    if (TextUtils.isEmpty(value)) {
                        continue;
                    }
                    if (!LAST_CLICKED_CARD_TITLE_CANDIDATES.contains(value)) {
                        LAST_CLICKED_CARD_TITLE_CANDIDATES.add(value);
                    }
                }
            }
            sAwaitingLiveRoomScript = true;
            sLastCardClickAt = now;
        }
    }

    private static boolean isCardProcessed(String key) {
        synchronized (FLOW_STATE_LOCK) {
            return !TextUtils.isEmpty(key) && PROCESSED_CARD_KEYS.contains(key);
        }
    }

    private static int getProcessedCardCount() {
        synchronized (FLOW_STATE_LOCK) {
            return PROCESSED_CARD_KEYS.size();
        }
    }

    private static boolean isAwaitingLiveRoomScript() {
        synchronized (FLOW_STATE_LOCK) {
            return sAwaitingLiveRoomScript;
        }
    }

    private static long getLastCardClickAt() {
        synchronized (FLOW_STATE_LOCK) {
            return sLastCardClickAt;
        }
    }

    private static void markLiveRoomReturned(long now) {
        synchronized (FLOW_STATE_LOCK) {
            sLastLiveRoomReturnAt = now;
        }
    }

    private static long getLastLiveRoomReturnAt() {
        synchronized (FLOW_STATE_LOCK) {
            return sLastLiveRoomReturnAt;
        }
    }

    private static void markLiveRoomScriptFinished() {
        synchronized (FLOW_STATE_LOCK) {
            sAwaitingLiveRoomScript = false;
        }
    }

    private static List<String> getLastClickedCardTitleCandidates() {
        synchronized (FLOW_STATE_LOCK) {
            return new ArrayList<String>(LAST_CLICKED_CARD_TITLE_CANDIDATES);
        }
    }

    private static void resetAwaitingLiveRoomFlag() {
        synchronized (FLOW_STATE_LOCK) {
            sAwaitingLiveRoomScript = false;
        }
    }

    private static String safeTrimStatic(String s) {
        if (s == null) {
            return "";
        }
        return s.trim();
    }

    private static void log(String msg) {
        String line = TAG + " " + msg;
        try {
            XposedBridge.log(line);
        } catch (Throwable ignore) {
        }
        try {
            Log.i(LOGCAT_TAG, line);
        } catch (Throwable ignore) {
        }
        ModuleRunFileLogger.appendLine(null, line);
    }
}
