package com.oodbye.lsposedchathook;

import android.app.Activity;
import android.graphics.Color;
import android.provider.Settings;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LOOK 直播间聊天采集入口（LSPosed 模块）。
 * 文件职责：主流程编排与会话生命周期管理（页面识别、悬浮按钮、广告处理、消息扫描与调度）。
 *
 * 功能概览：
 * 1) 仅在 com.netease.play 进程生效，并在 LiveViewerActivity 生命周期内工作。
 * 2) 页面上注入悬浮按钮，默认“关闭”，点击后先读取 roomNo（房间 ID），成功后开启采集。
 * 3) 采集来源是 UI 组件树：chatVp 容器下全局可见的文本节点（优先 content id）。
 * 4) 对可见帧文本做增量对比 + 时间窗去重，避免重复刷屏。
 * 5) 聊天上传到远程数据库（经 Cloudflare Worker 中间层），本地 CSV 默认禁用。
 *
 * 说明：
 * - 当前版本是 UI 组件采集方案，不依赖网络层解包。
 * - 价格表与黑名单由云端下发：首次全量拉取，后续只做版本检测，变更才拉全量。
 */
public class ChatHookEntry implements IXposedHookLoadPackage {
    // 目标 App 与页面识别
    private static final String TARGET_PACKAGE = "com.netease.play";
    private static final String LIVE_ACTIVITY_KEYWORD = "LiveViewerActivity";
    private static final String TAG = "[LOOKChatHook]";
    private static final String FLOAT_TAG = "LOOK_CHAT_HOOK_FLOAT_BTN";
    private static final String FLOAT_AD_TAG = "LOOK_CHAT_HOOK_AD_BTN";
    private static final String MODULE_VERSION = "2026.02.28-r12";
    private static final String CSV_TIMEZONE_ID = "GMT+08:00";

    // 采集与去重参数（实时优先）
    private static final long SCAN_INTERVAL_MS = 80L;
    private static final long DEDUP_WINDOW_MS = 1200L;
    private static final int DEDUP_MAX_SIZE = 1200;
    private static final long CSV_FLUSH_INTERVAL_MS = 300L;
    private static final long SUMMARY_LOG_INTERVAL_MS = 5000L;
    private static final long REMOTE_CONFIG_VERSION_CHECK_INTERVAL_MS = 30000L;

    // 房间信息读取与容错参数
    private static final long ROOM_INFO_POLL_INTERVAL_MS = 250L;
    private static final int ROOM_INFO_POLL_MAX_RETRY = 10;
    private static final int ROOM_INFO_ENTER_MAX_RETRY = 3;
    private static final int CHAT_ROOT_MISS_CLEAR_THRESHOLD = 20;
    private static final long NEW_MSG_CLICK_COOLDOWN_MS = 350L;
    private static final long NEW_MSG_LOG_COOLDOWN_MS = 3000L;
    private static final long ACTION_TOAST_COOLDOWN_MS = 1500L;

    // 全屏广告处理（参考 PopupHandler.js: handleFullScreenAd）
    private static final long FULL_AD_CHECK_INTERVAL_MS = 300L;
    private static final long FULL_AD_BACK_INTERVAL_MS = 800L;
    private static final float FULL_AD_MIN_WIDTH_RATIO = 0.55f;
    private static final float FULL_AD_MIN_HEIGHT_RATIO = 0.45f;
    private static final String AD_SLIDING_FULL_RES_NAME = "com.netease.play:id/slidingContainer";

    // 全局共享锁：会话与远程配置状态
    private static final Object STATE_LOCK = new Object();
    private static final Object REMOTE_CONFIG_LOCK = new Object();

    private static volatile boolean sHooksInstalled = false;

    private static final Map<Activity, UiSession> SESSIONS = new WeakHashMap<Activity, UiSession>();
    private static final LinkedHashMap<String, Long> RECENT_MESSAGES = new LinkedHashMap<String, Long>(256, 0.75f, true);
    private static boolean sRemoteConfigLoaded = false;
    private static long sRemoteConfigLastCheckAt = 0L;
    private static String sRemoteConfigVersion = "";
    private static Map<String, Long> sRemoteGiftPrices = new LinkedHashMap<String, Long>();
    private static List<String> sRemoteBlacklistRules = new ArrayList<String>();

    @Override
    /**
     * LSPosed 入口：仅命中目标包后安装 Activity 生命周期 hook。
     */
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        TimeZone tz = TimeZone.getTimeZone(CSV_TIMEZONE_ID);
        int offsetHours = tz.getRawOffset() / (1000 * 60 * 60);
        XposedBridge.log(TAG + " loaded in process=" + lpparam.processName
                + " version=" + MODULE_VERSION
                + " csvTz=" + tz.getID()
                + " offsetH=" + offsetHours);
        installActivityHooks();
    }

    /**
     * 安装 Activity 生命周期 hook：
     * - onResume: 创建/恢复会话并确保悬浮按钮存在
     * - onPause: 暂停扫描
     * - onDestroy: 清理会话
     */
    private void installActivityHooks() {
        if (sHooksInstalled) {
            return;
        }
        synchronized (STATE_LOCK) {
            if (sHooksInstalled) {
                return;
            }

            XposedBridge.hookAllMethods(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Activity activity = (Activity) param.thisObject;
                    boolean isLive = isLiveActivity(activity);
                    XposedBridge.log(TAG + " onResume activity=" + activity.getClass().getName() + " live=" + isLive);
                    ensureSession(activity);
                }
            });

            XposedBridge.hookAllMethods(Activity.class, "onPause", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Activity activity = (Activity) param.thisObject;
                    UiSession session = getSession(activity);
                    if (session != null) {
                        session.onActivityPause();
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

            sHooksInstalled = true;
            XposedBridge.log(TAG + " hook installed: Activity lifecycle + chat component scanner");
        }
    }

    /**
     * 页面识别：只在直播间 Activity 运行采集逻辑。
     */
    private boolean isLiveActivity(Activity activity) {
        if (activity == null) {
            return false;
        }
        String name = activity.getClass().getName();
        return name != null && name.contains(LIVE_ACTIVITY_KEYWORD);
    }

    private void ensureSession(final Activity activity) {
        if (activity == null || activity.isFinishing()) {
            return;
        }

        final boolean isLive = isLiveActivity(activity);
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                UiSession session = getSession(activity);
                if (session == null) {
                    session = new UiSession(activity, isLive);
                    putSession(activity, session);
                } else {
                    session.setLiveActivity(isLive);
                }
                if (isLive) {
                    session.ensureFloatingButton();
                }
                session.start();
            }
        });
    }

    private UiSession getSession(Activity activity) {
        synchronized (STATE_LOCK) {
            return SESSIONS.get(activity);
        }
    }

    private void putSession(Activity activity, UiSession session) {
        synchronized (STATE_LOCK) {
            SESSIONS.put(activity, session);
        }
    }

    private UiSession removeSession(Activity activity) {
        synchronized (STATE_LOCK) {
            return SESSIONS.remove(activity);
        }
    }

    /**
     * 单个直播页会话：
     * - 管理悬浮按钮与“开/关”状态
     * - 周期扫描 chatVp
     * - 将增量聊天批量上传到远程数据库
     */
    private static class UiSession {
        private final Activity activity;
        private final Handler handler;
        private final Runnable scanTask;
        private final Runnable roomInfoPollTask;
        private final Runnable csvFlushTask;
        private final ExecutorService remoteExecutor;
        private boolean liveActivity;

        private TextView floatButton;
        private TextView adToggleButton;
        private boolean running;
        private boolean resolvingRoomInfo;
        private boolean openedDetailFromAvatar;
        private boolean captureEnabled;
        private boolean adAutoHandleEnabled;
        private boolean startupHintShown;
        private boolean defaultModeApplied;

        private int chatVpId;
        private int contentId;
        private int avatarId;
        private int bgViewId;
        private int headerId;
        private int userIdResId;
        private int roomNoResId;
        private int newMessageHintResId;
        private int rootContainerResId;
        private int slidingContainerResId;
        private int closeBtnResId;

        private int roomInfoRetryCount;
        private int roomInfoEnterAttempt;
        private long lastMissingLogAt;
        private int chatRootMissStreak;
        private boolean handlingFullScreenAd;
        private long lastFullAdCheckAt;
        private long lastFullAdBackAt;
        private long lastFullAdLogAt;
        private long lastFullAdDetectedToastAt;
        private long lastFullAdToastAt;
        private long lastNewMsgClickAt;
        private long lastNewMsgLogAt;
        private long lastNewMsgToastAt;
        private long lastSummaryLogAt;

        private long totalCaptured;
        private long totalFilteredBlacklist;
        private long totalDroppedByDedup;

        private String roomId = "";
        private String deviceId = "";

        private List<String> lastFrameMessages = new ArrayList<String>();
        private boolean csvFlushScheduled;
        private boolean remoteConfigSyncing;
        private final ChatBlacklistFilter blacklistFilter;
        private final GiftPriceCatalog giftPriceCatalog;
        private final RemoteApiClient remoteApiClient;
        private final RemoteBatchUploader remoteBatchUploader;

        UiSession(Activity activity, boolean liveActivity) {
            this.activity = activity;
            this.liveActivity = liveActivity;
            this.handler = new Handler(Looper.getMainLooper());
            this.remoteExecutor = Executors.newSingleThreadExecutor();
            this.blacklistFilter = new ChatBlacklistFilter(TAG);
            this.giftPriceCatalog = new GiftPriceCatalog(TAG);
            this.remoteApiClient = new RemoteApiClient(activity, TAG);
            this.remoteBatchUploader = new RemoteBatchUploader(TAG, remoteApiClient);
            this.deviceId = resolveDeviceId();
            applyCachedRemoteConfigFromMemory();
            this.adAutoHandleEnabled = ModuleSettings.isAutoHandleFullscreenAdEnabled();

            this.scanTask = new Runnable() {
                @Override
                public void run() {
                    if (!running || activity.isFinishing() || activity.isDestroyed()) {
                        return;
                    }

                    if (adAutoHandleEnabled) {
                        maybeHandleFullScreenAd();
                        if (handlingFullScreenAd) {
                            refreshButtonStyle();
                            handler.postDelayed(this, SCAN_INTERVAL_MS);
                            return;
                        }
                    } else {
                        handlingFullScreenAd = false;
                        maybeNotifyAdDetectedWithoutHandling();
                    }

                    if (liveActivity) {
                        maybeClickNewMessageHint();
                    }

                    if (liveActivity && captureEnabled && !resolvingRoomInfo) {
                        maybeRefreshRemoteConfigAsync();
                        scanChatOnce();
                    }
                    if (liveActivity) {
                        refreshButtonStyle();
                    }
                    handler.postDelayed(this, SCAN_INTERVAL_MS);
                }
            };

            this.roomInfoPollTask = new Runnable() {
                @Override
                public void run() {
                    // r5开始仅支持直播页roomNo直读，不再走详情页点击流程。
                    resolvingRoomInfo = false;
                }
            };

            this.csvFlushTask = new Runnable() {
                @Override
                public void run() {
                    flushPendingCsvAsync();
                }
            };
        }

        // 启动扫描循环（按钮是否开启由 captureEnabled 控制）
        void start() {
            if (running) {
                return;
            }
            running = true;
            if (liveActivity) {
                applyDefaultModeIfNeeded();
            }
            handler.post(scanTask);
        }

        void setLiveActivity(boolean liveActivity) {
            this.liveActivity = liveActivity;
        }

        private void applyDefaultModeIfNeeded() {
            if (defaultModeApplied) {
                return;
            }
            defaultModeApplied = true;
            if (ModuleSettings.isDefaultCaptureEnabled()) {
                XposedBridge.log(TAG + " default capture setting=ON, auto enable");
                startEnableCaptureFlow();
            } else {
                XposedBridge.log(TAG + " default capture setting=OFF");
            }
        }

        // 页面切后台时暂停，避免无意义扫描
        void onActivityPause() {
            running = false;
            handler.removeCallbacks(scanTask);
            handler.removeCallbacks(csvFlushTask);
            handlingFullScreenAd = false;
            flushPendingCsvAsync();
            if (!resolvingRoomInfo) {
                handler.removeCallbacks(roomInfoPollTask);
                lastFrameMessages.clear();
            }
        }

        void pause() {
            running = false;
            handler.removeCallbacks(scanTask);
            handler.removeCallbacks(roomInfoPollTask);
            handler.removeCallbacks(csvFlushTask);
            resolvingRoomInfo = false;
            handlingFullScreenAd = false;
            lastFrameMessages.clear();
            flushPendingCsvAsync();
        }

        void destroy() {
            pause();
            try {
                remoteExecutor.shutdownNow();
            } catch (Throwable ignored0) {
            }
            if (floatButton != null) {
                try {
                    ViewGroup parent = (ViewGroup) floatButton.getParent();
                    if (parent != null) {
                        parent.removeView(floatButton);
                    }
                } catch (Throwable ignored) {
                }
                floatButton = null;
            }
            if (adToggleButton != null) {
                try {
                    ViewGroup parent = (ViewGroup) adToggleButton.getParent();
                    if (parent != null) {
                        parent.removeView(adToggleButton);
                    }
                } catch (Throwable ignored) {
                }
                adToggleButton = null;
            }
        }

        /**
         * 悬浮按钮说明：
         * - 点击：切换抓取开关（开启前先读取 roomNo）
         * - 长按：手动触发一次扫描（用于现场排查）
         */
        void ensureFloatingButton() {
            try {
                ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
                View existing = decor.findViewWithTag(FLOAT_TAG);
                if (existing instanceof TextView) {
                    floatButton = (TextView) existing;
                }
                if (floatButton == null) {
                    TextView btn = new TextView(activity);
                    btn.setTag(FLOAT_TAG);
                    btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                    btn.setPadding(dp(10), dp(6), dp(10), dp(6));
                    btn.setGravity(Gravity.CENTER);
                    btn.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            onFloatingButtonClick();
                        }
                    });
                    btn.setOnLongClickListener(new View.OnLongClickListener() {
                        @Override
                        public boolean onLongClick(View v) {
                            if (captureEnabled) {
                                scanChatOnce();
                                Toast.makeText(activity, "已手动扫描一次\n" + buildPathHint(), Toast.LENGTH_SHORT).show();
                                return true;
                            }
                            Toast.makeText(activity, "当前抓取已关闭，点击按钮可开启\n" + buildPathHint(), Toast.LENGTH_SHORT).show();
                            return true;
                        }
                    });

                    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                    );
                    lp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
                    lp.rightMargin = dp(12);
                    lp.topMargin = dp(80);

                    decor.addView(btn, lp);
                    floatButton = btn;
                }

                View existingAd = decor.findViewWithTag(FLOAT_AD_TAG);
                if (existingAd instanceof TextView) {
                    adToggleButton = (TextView) existingAd;
                }
                if (adToggleButton == null) {
                    TextView adBtn = new TextView(activity);
                    adBtn.setTag(FLOAT_AD_TAG);
                    adBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                    adBtn.setPadding(dp(10), dp(6), dp(10), dp(6));
                    adBtn.setGravity(Gravity.CENTER);
                    adBtn.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            onAdToggleButtonClick();
                        }
                    });

                    FrameLayout.LayoutParams adLp = new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                    );
                    adLp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
                    adLp.rightMargin = dp(12);
                    adLp.topMargin = dp(132);

                    decor.addView(adBtn, adLp);
                    adToggleButton = adBtn;
                }
                refreshButtonStyle();
                maybeShowStartupHint();
            } catch (Throwable e) {
                XposedBridge.log(TAG + " add floating button failed: " + e);
            }
        }

        private void maybeShowStartupHint() {
            if (startupHintShown) {
                return;
            }
            startupHintShown = true;
            if (ModuleSettings.isDefaultCaptureEnabled()) {
                Toast.makeText(activity, "聊天抓取默认开启，进入房间会自动尝试开启", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(activity, "聊天抓取默认关闭，点击悬浮按钮开启", Toast.LENGTH_SHORT).show();
            }
        }

        private void onFloatingButtonClick() {
            if (resolvingRoomInfo) {
                Toast.makeText(activity, "正在读取房间信息...", Toast.LENGTH_SHORT).show();
                return;
            }

            if (captureEnabled) {
                captureEnabled = false;
                refreshButtonStyle();
                Toast.makeText(activity, "聊天抓取已暂停", Toast.LENGTH_SHORT).show();
                XposedBridge.log(TAG + " captureEnabled=false");
                return;
            }

            startEnableCaptureFlow();
        }

        private void onAdToggleButtonClick() {
            adAutoHandleEnabled = !adAutoHandleEnabled;
            if (!adAutoHandleEnabled) {
                handlingFullScreenAd = false;
                Toast.makeText(activity, "广告自动关闭:关", Toast.LENGTH_SHORT).show();
                XposedBridge.log(TAG + " ad auto handle=OFF (floating toggle)");
            } else {
                Toast.makeText(activity, "广告自动关闭:开", Toast.LENGTH_SHORT).show();
                XposedBridge.log(TAG + " ad auto handle=ON (floating toggle)");
            }
            refreshButtonStyle();
        }

        /**
         * 开启采集流程：
         * 1) 重置上一轮房间信息状态
         * 2) 从直播页 roomNo 直接读取房间 ID
         * 3) 先检测远程数据库连通并拉取配置，再切换为开启状态
         */
        private void startEnableCaptureFlow() {
            resolveRoomResourceIds();
            roomId = "";
            roomInfoRetryCount = 0;
            roomInfoEnterAttempt = 0;
            openedDetailFromAvatar = false;
            resolvingRoomInfo = false;
            refreshButtonStyle();
            Toast.makeText(activity, "正在读取房间ID...", Toast.LENGTH_SHORT).show();

            String cleanId = readRoomIdFromLiveRoomNo();
            if (isEmpty(cleanId)) {
                failEnableCapture("未读取到房间ID(roomNo)");
                return;
            }

            roomId = cleanId;
            enableCaptureAfterRoomInfo();
        }

        private void enableCaptureAfterRoomInfo() {
            resolvingRoomInfo = false;
            openedDetailFromAvatar = false;
            roomInfoRetryCount = 0;
            roomInfoEnterAttempt = 0;
            if (isEmpty(roomId)) {
                failEnableCapture("未读取到房间ID(roomNo)");
                return;
            }
            if (!remoteApiClient.isWriteConfigured()) {
                failEnableCapture(remoteApiClient.missingConfigReasonForWrite());
                return;
            }

            resolvingRoomInfo = true;
            refreshButtonStyle();
            Toast.makeText(activity, "检测远程数据库...", Toast.LENGTH_SHORT).show();

            remoteExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    boolean healthOk = remoteApiClient.healthCheck();
                    if (!healthOk) {
                        final String detail = remoteApiClient.getLastRequestError();
                        activity.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                String reason = "远程数据库不可用，请检查API地址/密钥";
                                if (!isEmpty(detail)) {
                                    reason = reason + "（" + detail + "）";
                                }
                                failEnableCapture(reason);
                            }
                        });
                        return;
                    }

                    boolean configOk = syncRemoteConfigBlocking(false, true);
                    if (!configOk) {
                        activity.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                failEnableCapture("拉取云端配置失败");
                            }
                        });
                        return;
                    }

                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            resolvingRoomInfo = false;
                            captureEnabled = true;
                            lastFrameMessages.clear();
                            chatRootMissStreak = 0;
                            refreshButtonStyle();

                            String info = "ID:" + safe(roomId, "unknown");
                            Toast.makeText(activity, "聊天抓取已开启\n" + info + "\n" + buildPathHint(), Toast.LENGTH_SHORT).show();
                            XposedBridge.log(TAG + " captureEnabled=true roomId=" + roomId
                                    + " remote=" + remoteApiClient.getBaseUrl());
                        }
                    });
                }
            });
        }

        private void failEnableCapture(String reason) {
            resolvingRoomInfo = false;
            openedDetailFromAvatar = false;
            roomInfoRetryCount = 0;
            roomInfoEnterAttempt = 0;
            captureEnabled = false;
            refreshButtonStyle();
            Toast.makeText(activity, "开启失败: " + reason, Toast.LENGTH_SHORT).show();
            XposedBridge.log(TAG + " enable capture failed: " + reason);
        }

        private void maybeRefreshRemoteConfigAsync() {
            long now = System.currentTimeMillis();
            if (remoteConfigSyncing) {
                return;
            }
            synchronized (REMOTE_CONFIG_LOCK) {
                if (now - sRemoteConfigLastCheckAt < REMOTE_CONFIG_VERSION_CHECK_INTERVAL_MS) {
                    return;
                }
                sRemoteConfigLastCheckAt = now;
            }
            remoteConfigSyncing = true;
            remoteExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        syncRemoteConfigBlocking(false, false);
                    } finally {
                        remoteConfigSyncing = false;
                    }
                }
            });
        }

        private boolean syncRemoteConfigBlocking(boolean forceFull, boolean strictFail) {
            if (!remoteApiClient.isReadConfigured()) {
                XposedBridge.log(TAG + " remote config read not configured");
                return !strictFail;
            }

            if (!forceFull && applyCachedRemoteConfigFromMemory()) {
                RemoteApiClient.ConfigVersion version = remoteApiClient.fetchConfigVersion();
                if (!version.ok || isEmpty(version.version)) {
                    return !strictFail;
                }
                String currentVersion;
                synchronized (REMOTE_CONFIG_LOCK) {
                    currentVersion = sRemoteConfigVersion;
                }
                if (version.version.equals(currentVersion)) {
                    return true;
                }
            }

            RemoteApiClient.ConfigSnapshot snapshot = remoteApiClient.fetchConfigSnapshot();
            if (snapshot == null) {
                return !strictFail;
            }

            Map<String, Long> latestGift = copyGiftMap(snapshot.giftPrices);
            List<String> latestBlacklist = copyBlacklist(snapshot.blacklistRules);
            String latestVersion = safe(snapshot.version, "");

            giftPriceCatalog.replaceAll(latestGift);
            blacklistFilter.replaceRules(latestBlacklist);
            long now = System.currentTimeMillis();
            synchronized (REMOTE_CONFIG_LOCK) {
                sRemoteGiftPrices = latestGift;
                sRemoteBlacklistRules = latestBlacklist;
                sRemoteConfigVersion = latestVersion;
                sRemoteConfigLoaded = true;
                sRemoteConfigLastCheckAt = now;
            }
            XposedBridge.log(TAG + " remote config synced version=" + latestVersion
                    + " gift=" + giftPriceCatalog.size()
                    + " blacklist=" + blacklistFilter.size());
            return true;
        }

        private boolean applyCachedRemoteConfigFromMemory() {
            Map<String, Long> giftCopy = null;
            List<String> blacklistCopy = null;
            String versionCopy = "";
            synchronized (REMOTE_CONFIG_LOCK) {
                if (!sRemoteConfigLoaded) {
                    return false;
                }
                giftCopy = copyGiftMap(sRemoteGiftPrices);
                blacklistCopy = copyBlacklist(sRemoteBlacklistRules);
                versionCopy = sRemoteConfigVersion;
            }
            giftPriceCatalog.replaceAll(giftCopy);
            blacklistFilter.replaceRules(blacklistCopy);
            if (!isEmpty(versionCopy)) {
                XposedBridge.log(TAG + " remote config cache hit version=" + versionCopy
                        + " gift=" + giftPriceCatalog.size()
                        + " blacklist=" + blacklistFilter.size());
            }
            return true;
        }

        private Map<String, Long> copyGiftMap(Map<String, Long> src) {
            Map<String, Long> out = new LinkedHashMap<String, Long>();
            if (src == null || src.isEmpty()) {
                return out;
            }
            for (Map.Entry<String, Long> it : src.entrySet()) {
                String key = it.getKey() == null ? "" : it.getKey().trim();
                if (key.length() == 0) {
                    continue;
                }
                long value = it.getValue() == null ? 0L : it.getValue().longValue();
                if (value < 0L) {
                    value = 0L;
                }
                out.put(key, value);
            }
            return out;
        }

        private List<String> copyBlacklist(List<String> src) {
            List<String> out = new ArrayList<String>();
            if (src == null || src.isEmpty()) {
                return out;
            }
            for (int i = 0; i < src.size(); i++) {
                String item = src.get(i);
                if (item == null) {
                    continue;
                }
                String v = item.trim();
                if (v.length() > 0) {
                    out.add(v);
                }
            }
            return out;
        }

        private boolean enterDetailFromLiveByHostEntry() {
            XposedBridge.log(TAG + " detail-click path disabled in r8");
            return false;
        }

        private boolean clickWithParentFallback(View startView, String scene) {
            XposedBridge.log(TAG + " click path disabled in r8, scene=" + scene);
            return false;
        }

        private void resolveRoomResourceIds() {
            if (userIdResId == 0) {
                userIdResId = activity.getResources().getIdentifier("id", "id", TARGET_PACKAGE);
                if (userIdResId == 0) {
                    userIdResId = activity.getResources().getIdentifier("id", "id", activity.getPackageName());
                }
            }
            if (roomNoResId == 0) {
                roomNoResId = activity.getResources().getIdentifier("roomNo", "id", TARGET_PACKAGE);
                if (roomNoResId == 0) {
                    roomNoResId = activity.getResources().getIdentifier("roomNo", "id", activity.getPackageName());
                }
            }
        }

        private boolean tryReadRoomInfoNow() {
            resolveRoomResourceIds();
            String roomNoText = readTextByResId(roomNoResId);
            String cleanId = cleanRoomId(roomNoText);
            if (isEmpty(cleanId)) {
                return false;
            }

            roomId = cleanId;
            XposedBridge.log(TAG + " room id read from roomNo: " + roomId);
            return true;
        }

        private String readRoomIdFromLiveRoomNo() {
            resolveRoomResourceIds();
            String roomNoText = readTextByResId(roomNoResId);
            return cleanRoomId(roomNoText);
        }

        private void fillRoomIdFromRoomNo() {
            resolveRoomResourceIds();
            String roomNoText = readTextByResId(roomNoResId);
            String cleaned = cleanRoomId(roomNoText);
            if (!isEmpty(cleaned)) {
                roomId = cleaned;
                XposedBridge.log(TAG + " room id fallback from roomNo=" + roomId);
                return;
            }
            XposedBridge.log(TAG + " roomNo fallback failed");
        }

        private void backToLiveIfNeeded() {
            if (!openedDetailFromAvatar) {
                return;
            }
            if (!isDetailVisible()) {
                openedDetailFromAvatar = false;
                return;
            }

            try {
                activity.onBackPressed();
                XposedBridge.log(TAG + " back to live from detail");
            } catch (Throwable e) {
                XposedBridge.log(TAG + " back failed: " + e);
            }
            if (isDetailVisible()) {
                try {
                    activity.onBackPressed();
                    XposedBridge.log(TAG + " back to live from detail (2nd)");
                } catch (Throwable e2) {
                    XposedBridge.log(TAG + " second back failed: " + e2);
                }
            }
            openedDetailFromAvatar = false;
        }

        private boolean isDetailVisible() {
            resolveRoomResourceIds();
            String idText = readTextByResId(userIdResId);
            return !isEmpty(idText);
        }

        private String readTextByResId(int resId) {
            if (resId == 0) {
                return "";
            }
            try {
                View v = activity.findViewById(resId);
                if (!(v instanceof TextView)) {
                    return "";
                }
                CharSequence cs = ((TextView) v).getText();
                return cs == null ? "" : cs.toString();
            } catch (Throwable ignored) {
                return "";
            }
        }

        private int dp(int value) {
            float density = activity.getResources().getDisplayMetrics().density;
            return (int) (value * density + 0.5f);
        }

        private void refreshButtonStyle() {
            if (floatButton != null) {
                if (resolvingRoomInfo) {
                    floatButton.setText("读取房间中");
                    floatButton.setTextColor(Color.WHITE);
                    floatButton.setBackgroundColor(Color.parseColor("#E65100"));
                    floatButton.setAlpha(0.9f);
                } else if (handlingFullScreenAd) {
                    floatButton.setText("广告处理中");
                    floatButton.setTextColor(Color.WHITE);
                    floatButton.setBackgroundColor(Color.parseColor("#6A1B9A"));
                    floatButton.setAlpha(0.9f);
                } else if (captureEnabled) {
                    floatButton.setText("聊天抓取:开");
                    floatButton.setTextColor(Color.WHITE);
                    floatButton.setBackgroundColor(Color.parseColor("#2E7D32"));
                    floatButton.setAlpha(0.88f);
                } else {
                    floatButton.setText("聊天抓取:关");
                    floatButton.setTextColor(Color.WHITE);
                    floatButton.setBackgroundColor(Color.parseColor("#B71C1C"));
                    floatButton.setAlpha(0.88f);
                }
            }

            if (adToggleButton != null) {
                if (handlingFullScreenAd && adAutoHandleEnabled) {
                    adToggleButton.setText("广告处理:中");
                    adToggleButton.setTextColor(Color.WHITE);
                    adToggleButton.setBackgroundColor(Color.parseColor("#6A1B9A"));
                    adToggleButton.setAlpha(0.9f);
                } else if (adAutoHandleEnabled) {
                    adToggleButton.setText("广告处理:开");
                    adToggleButton.setTextColor(Color.WHITE);
                    adToggleButton.setBackgroundColor(Color.parseColor("#1565C0"));
                    adToggleButton.setAlpha(0.88f);
                } else {
                    adToggleButton.setText("广告处理:关");
                    adToggleButton.setTextColor(Color.WHITE);
                    adToggleButton.setBackgroundColor(Color.parseColor("#757575"));
                    adToggleButton.setAlpha(0.88f);
                }
            }
        }

        /**
         * 检测并处理全屏广告（rootContainer/slidingContainer + closeBtn）：
         * - 命中后 back() 一次
         * - 间隔后再次检测，若仍存在继续 back()，直到广告消失
         */
        private void maybeHandleFullScreenAd() {
            long now = System.currentTimeMillis();
            if (now - lastFullAdCheckAt < FULL_AD_CHECK_INTERVAL_MS) {
                return;
            }
            lastFullAdCheckAt = now;

            boolean showing = isFullScreenAdShowing();
            if (!showing) {
                if (handlingFullScreenAd) {
                    handlingFullScreenAd = false;
                    if (now - lastFullAdLogAt > 800L) {
                        lastFullAdLogAt = now;
                        XposedBridge.log(TAG + " [全屏广告] 已消失");
                    }
                }
                return;
            }

            if (handlingFullScreenAd) {
                if (now - lastFullAdBackAt < FULL_AD_BACK_INTERVAL_MS) {
                    return;
                }
            }

            maybeToastAdDetected(now, "检测到全屏广告");
            handlingFullScreenAd = true;
            if (now - lastFullAdLogAt > 800L) {
                lastFullAdLogAt = now;
                XposedBridge.log(TAG + " 检测到 [全屏广告]（rootContainer/slidingContainer），执行 back() 退回");
            }
            try {
                activity.onBackPressed();
                lastFullAdBackAt = now;
                if (now - lastFullAdToastAt > ACTION_TOAST_COOLDOWN_MS) {
                    lastFullAdToastAt = now;
                    Toast.makeText(activity, "已执行返回关闭全屏广告", Toast.LENGTH_SHORT).show();
                }
            } catch (Throwable e) {
                XposedBridge.log(TAG + " [全屏广告] back 异常: " + e);
            }
        }

        private void maybeNotifyAdDetectedWithoutHandling() {
            long now = System.currentTimeMillis();
            if (now - lastFullAdCheckAt < FULL_AD_CHECK_INTERVAL_MS) {
                return;
            }
            lastFullAdCheckAt = now;
            if (!isFullScreenAdShowing()) {
                return;
            }
            maybeToastAdDetected(now, "检测到全屏广告（自动处理已关闭）");
        }

        private void maybeToastAdDetected(long now, String message) {
            if (now - lastFullAdDetectedToastAt < ACTION_TOAST_COOLDOWN_MS) {
                return;
            }
            lastFullAdDetectedToastAt = now;
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
        }

        /**
         * 持续检测“底部有新消息”提示并自动点击，尽量把聊天滚动到最新位置。
         */
        private void maybeClickNewMessageHint() {
            long now = System.currentTimeMillis();
            if (now - lastNewMsgClickAt < NEW_MSG_CLICK_COOLDOWN_MS) {
                return;
            }

            resolveRealtimeHintResId();
            if (newMessageHintResId == 0) {
                return;
            }

            View hint = null;
            try {
                hint = activity.findViewById(newMessageHintResId);
            } catch (Throwable ignored) {
                hint = null;
            }
            if (!(hint instanceof TextView)) {
                return;
            }
            TextView tv = (TextView) hint;
            if (!isViewVisible(tv)) {
                return;
            }

            String text = normalize(String.valueOf(tv.getText()));
            if (text == null || text.indexOf("底部有新消息") < 0) {
                return;
            }

            if (performClickWithParentFallback(tv)) {
                lastNewMsgClickAt = now;
                if (now - lastNewMsgLogAt > NEW_MSG_LOG_COOLDOWN_MS) {
                    lastNewMsgLogAt = now;
                    XposedBridge.log(TAG + " 点击 [底部有新消息] 提示");
                }
                if (now - lastNewMsgToastAt > ACTION_TOAST_COOLDOWN_MS) {
                    lastNewMsgToastAt = now;
                    Toast.makeText(activity, "已点击“底部有新消息”", Toast.LENGTH_SHORT).show();
                }
            }
        }

        private void resolveRealtimeHintResId() {
            if (newMessageHintResId == 0) {
                newMessageHintResId = activity.getResources().getIdentifier("newMessageHint", "id", TARGET_PACKAGE);
                if (newMessageHintResId == 0) {
                    newMessageHintResId = activity.getResources().getIdentifier("newMessageHint", "id", activity.getPackageName());
                }
            }
        }

        private boolean performClickWithParentFallback(View view) {
            if (view == null) {
                return false;
            }
            try {
                if (view.performClick()) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
            try {
                if (view.callOnClick()) {
                    return true;
                }
            } catch (Throwable ignored2) {
            }
            View current = view;
            for (int i = 0; i < 5; i++) {
                if (!(current.getParent() instanceof View)) {
                    break;
                }
                current = (View) current.getParent();
                try {
                    if (current.performClick()) {
                        return true;
                    }
                } catch (Throwable ignored3) {
                }
                try {
                    if (current.callOnClick()) {
                        return true;
                    }
                } catch (Throwable ignored4) {
                }
            }
            return false;
        }

        private void resolveAdResourceIds() {
            if (rootContainerResId == 0) {
                rootContainerResId = activity.getResources().getIdentifier("rootContainer", "id", TARGET_PACKAGE);
                if (rootContainerResId == 0) {
                    rootContainerResId = activity.getResources().getIdentifier("rootContainer", "id", activity.getPackageName());
                }
            }
            if (slidingContainerResId == 0) {
                slidingContainerResId = activity.getResources().getIdentifier("slidingContainer", "id", TARGET_PACKAGE);
                if (slidingContainerResId == 0) {
                    slidingContainerResId = activity.getResources().getIdentifier("slidingContainer", "id", activity.getPackageName());
                }
            }
            if (closeBtnResId == 0) {
                closeBtnResId = activity.getResources().getIdentifier("closeBtn", "id", TARGET_PACKAGE);
                if (closeBtnResId == 0) {
                    closeBtnResId = activity.getResources().getIdentifier("closeBtn", "id", activity.getPackageName());
                }
            }
        }

        private boolean isFullScreenAdShowing() {
            resolveAdResourceIds();

            // 优先按完整 resource-id 强匹配，覆盖用户反馈场景：
            // text="" resource-id="com.netease.play:id/slidingContainer" class="android.widget.FrameLayout"
            View slidingByFullRes = findViewByFullResNameAcrossWindows(AD_SLIDING_FULL_RES_NAME);
            if (slidingByFullRes != null && (isViewVisible(slidingByFullRes) || isViewLikelyVisibleRelaxed(slidingByFullRes))) {
                return true;
            }

            // 先按 id 名称做兜底查找，避免 getIdentifier/findViewById 在某些窗口场景失效
            View slidingByName = findViewByIdNameAcrossWindows("slidingContainer");
            if (slidingByName != null && (isViewVisible(slidingByName) || isViewLikelyVisibleRelaxed(slidingByName))) {
                return true;
            }
            View rootByName = findViewByIdNameAcrossWindows("rootContainer");
            View closeByName = findViewByIdNameAcrossWindows("closeBtn");
            if (rootByName != null && isViewVisible(rootByName)) {
                if (closeByName != null && isViewVisible(closeByName)) {
                    return true;
                }
                if (isLargeVisibleOverlay(rootByName)) {
                    return true;
                }
            }

            View adContainer = null;
            View slidingContainer = null;
            if (rootContainerResId != 0) {
                View rootContainer = findViewByIdAcrossWindows(rootContainerResId);
                if (isViewVisible(rootContainer)) {
                    adContainer = rootContainer;
                }
            }
            if (slidingContainerResId != 0) {
                slidingContainer = findViewByIdAcrossWindows(slidingContainerResId);
                if (slidingContainer != null && (isViewVisible(slidingContainer) || isViewLikelyVisibleRelaxed(slidingContainer))) {
                    // slidingContainer 命中时直接判定为广告，避免被严格可见性规则漏判
                    return true;
                }
            }
            if (adContainer == null) {
                return false;
            }

            // 优先判断 closeBtn 是否可见，避免误判普通容器
            if (closeBtnResId != 0) {
                View closeBtn = findViewByIdAcrossWindows(closeBtnResId);
                if (isViewVisible(closeBtn)) {
                    return true;
                }
            }

            // 未找到 closeBtn 时，按广告容器可见占比做兜底判定
            return isLargeVisibleOverlay(adContainer);
        }

        private View findViewByFullResNameAcrossWindows(String fullResName) {
            if (isEmpty(fullResName)) {
                return null;
            }
            try {
                View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
                View found = findViewByFullResNameInTree(decor, fullResName);
                if (found != null) {
                    return found;
                }
            } catch (Throwable ignored) {
            }
            try {
                Class<?> wmgCls = Class.forName("android.view.WindowManagerGlobal");
                Object wmg = wmgCls.getMethod("getInstance").invoke(null);
                Field viewsField = wmgCls.getDeclaredField("mViews");
                viewsField.setAccessible(true);
                Object viewsObj = viewsField.get(wmg);
                if (viewsObj instanceof List) {
                    List<?> views = (List<?>) viewsObj;
                    for (int i = views.size() - 1; i >= 0; i--) {
                        Object item = views.get(i);
                        if (item instanceof View) {
                            View found = findViewByFullResNameInTree((View) item, fullResName);
                            if (found != null) {
                                return found;
                            }
                        }
                    }
                }
            } catch (Throwable ignored2) {
            }
            return null;
        }

        private View findViewByFullResNameInTree(View root, String fullResName) {
            if (root == null || isEmpty(fullResName)) {
                return null;
            }
            List<View> stack = new ArrayList<View>();
            stack.add(root);
            while (!stack.isEmpty()) {
                int last = stack.size() - 1;
                View node = stack.remove(last);
                if (node == null) {
                    continue;
                }
                int id = View.NO_ID;
                try {
                    id = node.getId();
                } catch (Throwable ignored) {
                    id = View.NO_ID;
                }
                if (id != View.NO_ID) {
                    try {
                        String name = node.getResources().getResourceName(id);
                        if (fullResName.equals(name)) {
                            return node;
                        }
                    } catch (Throwable ignored2) {
                    }
                }
                if (node instanceof ViewGroup) {
                    ViewGroup group = (ViewGroup) node;
                    int count = group.getChildCount();
                    for (int i = count - 1; i >= 0; i--) {
                        stack.add(group.getChildAt(i));
                    }
                }
            }
            return null;
        }

        private View findViewByIdNameAcrossWindows(String entryName) {
            if (isEmpty(entryName)) {
                return null;
            }
            try {
                View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
                View found = findViewByIdNameInTree(decor, entryName);
                if (found != null) {
                    return found;
                }
            } catch (Throwable ignored) {
            }
            try {
                Class<?> wmgCls = Class.forName("android.view.WindowManagerGlobal");
                Object wmg = wmgCls.getMethod("getInstance").invoke(null);
                Field viewsField = wmgCls.getDeclaredField("mViews");
                viewsField.setAccessible(true);
                Object viewsObj = viewsField.get(wmg);
                if (viewsObj instanceof List) {
                    List<?> views = (List<?>) viewsObj;
                    for (int i = views.size() - 1; i >= 0; i--) {
                        Object item = views.get(i);
                        if (item instanceof View) {
                            View found = findViewByIdNameInTree((View) item, entryName);
                            if (found != null) {
                                return found;
                            }
                        }
                    }
                }
            } catch (Throwable ignored2) {
            }
            return null;
        }

        private View findViewByIdNameInTree(View root, String entryName) {
            if (root == null || isEmpty(entryName)) {
                return null;
            }
            List<View> stack = new ArrayList<View>();
            stack.add(root);
            while (!stack.isEmpty()) {
                int last = stack.size() - 1;
                View node = stack.remove(last);
                if (node == null) {
                    continue;
                }
                int id = View.NO_ID;
                try {
                    id = node.getId();
                } catch (Throwable ignored) {
                    id = View.NO_ID;
                }
                if (id != View.NO_ID) {
                    try {
                        String name = node.getResources().getResourceEntryName(id);
                        if (entryName.equals(name)) {
                            return node;
                        }
                    } catch (Throwable ignored2) {
                    }
                }
                if (node instanceof ViewGroup) {
                    ViewGroup group = (ViewGroup) node;
                    int count = group.getChildCount();
                    for (int i = count - 1; i >= 0; i--) {
                        stack.add(group.getChildAt(i));
                    }
                }
            }
            return null;
        }

        private View findViewByIdAcrossWindows(int resId) {
            if (resId == 0) {
                return null;
            }
            try {
                View v = activity.findViewById(resId);
                if (v != null) {
                    return v;
                }
            } catch (Throwable ignored) {
            }
            try {
                View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
                if (decor != null) {
                    View v2 = decor.findViewById(resId);
                    if (v2 != null) {
                        return v2;
                    }
                }
            } catch (Throwable ignored2) {
            }

            // 兜底：扫描当前进程所有窗口（解决广告在独立 Dialog/浮层窗口中导致 findViewById 找不到）
            try {
                Class<?> wmgCls = Class.forName("android.view.WindowManagerGlobal");
                Object wmg = wmgCls.getMethod("getInstance").invoke(null);
                Field viewsField = wmgCls.getDeclaredField("mViews");
                viewsField.setAccessible(true);
                Object viewsObj = viewsField.get(wmg);
                if (viewsObj instanceof List) {
                    List<?> views = (List<?>) viewsObj;
                    for (int i = views.size() - 1; i >= 0; i--) {
                        Object item = views.get(i);
                        if (item instanceof View) {
                            View found = ((View) item).findViewById(resId);
                            if (found != null) {
                                return found;
                            }
                        }
                    }
                }
            } catch (Throwable ignored3) {
            }
            return null;
        }

        private boolean isViewVisible(View view) {
            if (view == null) {
                return false;
            }
            try {
                if (view.getVisibility() != View.VISIBLE) {
                    return false;
                }
                if (!view.isShown()) {
                    return false;
                }
                if (view.getAlpha() <= 0.02f) {
                    return false;
                }
            } catch (Throwable ignored) {
                return false;
            }
            Rect rect = new Rect();
            try {
                if (!view.getGlobalVisibleRect(rect)) {
                    return false;
                }
            } catch (Throwable ignored2) {
                return false;
            }
            return rect.width() > 0 && rect.height() > 0;
        }

        private boolean isViewLikelyVisibleRelaxed(View view) {
            if (view == null) {
                return false;
            }
            try {
                int vis = view.getVisibility();
                if (vis != View.VISIBLE) {
                    return false;
                }
            } catch (Throwable ignored) {
                return false;
            }
            try {
                if (view.getAlpha() <= 0.01f) {
                    return false;
                }
            } catch (Throwable ignored2) {
            }
            // 放宽规则：不强依赖 isShown/getGlobalVisibleRect，避免动画层或特殊容器漏判
            return true;
        }

        private boolean isLargeVisibleOverlay(View view) {
            if (view == null) {
                return false;
            }
            Rect rect = new Rect();
            try {
                if (!view.getGlobalVisibleRect(rect)) {
                    return false;
                }
            } catch (Throwable ignored) {
                return false;
            }
            if (rect.width() <= 0 || rect.height() <= 0) {
                return false;
            }

            int screenW = activity.getResources().getDisplayMetrics().widthPixels;
            int screenH = activity.getResources().getDisplayMetrics().heightPixels;
            if (screenW <= 0 || screenH <= 0) {
                return false;
            }

            float widthRatio = ((float) rect.width()) / ((float) screenW);
            float heightRatio = ((float) rect.height()) / ((float) screenH);
            return widthRatio >= FULL_AD_MIN_WIDTH_RATIO && heightRatio >= FULL_AD_MIN_HEIGHT_RATIO;
        }

        /**
         * 单次扫描主流程：
         * - 在 chatVp 可见区域收集文本
         * - 与上一帧做增量对比，再执行全局去重后输出
         */
        private void scanChatOnce() {
            View chatRoot = findChatRootView();
            if (chatRoot == null) {
                chatRootMissStreak = chatRootMissStreak + 1;
                long now = System.currentTimeMillis();
                if (now - lastMissingLogAt > 3000L) {
                    lastMissingLogAt = now;
                    XposedBridge.log(TAG + " chatVp not found in " + activity.getClass().getName());
                }
                if (chatRootMissStreak >= CHAT_ROOT_MISS_CLEAR_THRESHOLD) {
                    lastFrameMessages.clear();
                }
                return;
            }
            chatRootMissStreak = 0;
            Rect chatRootVisibleRect = new Rect();
            boolean chatRootVisible = false;
            try {
                chatRootVisible = chatRoot.getGlobalVisibleRect(chatRootVisibleRect);
            } catch (Throwable ignored) {
                chatRootVisible = false;
            }
            if (!chatRootVisible) {
                return;
            }

            List<String> messages = collectVisibleChatTextsSinglePass(chatRoot, chatRootVisibleRect);

            if (messages.isEmpty()) {
                return;
            }

            List<String> currentFrame = uniqueOrdered(messages);
            List<String> delta = computeFrameDelta(lastFrameMessages, currentFrame);
            lastFrameMessages = currentFrame;

            for (int i = 0; i < delta.size(); i++) {
                String msg = delta.get(i);
                emitChatMessage(msg);
            }
        }

        /**
         * 单次遍历 chatVp 子树，仅收集“全局可见”的聊天文本：
         * - 优先采集 id=content 的文本
         * - 若本轮未命中 content，再降级到其它可见文本（仍只遍历一次）
         */
        private List<String> collectVisibleChatTextsSinglePass(View chatRoot, Rect chatRootVisibleRect) {
            List<String> primary = new ArrayList<String>();
            List<String> fallback = new ArrayList<String>();
            if (chatRoot == null || chatRootVisibleRect == null) {
                return primary;
            }

            List<View> stack = new ArrayList<View>();
            stack.add(chatRoot);

            while (!stack.isEmpty()) {
                int last = stack.size() - 1;
                View node = stack.remove(last);
                if (node == null) {
                    continue;
                }

                if (node instanceof TextView) {
                    TextView tv = (TextView) node;
                    if (isTextViewVisiblyInChatRoot(tv, chatRootVisibleRect)) {
                        String text = normalize(String.valueOf(tv.getText()));
                        if (text != null) {
                            if (contentId != 0 && tv.getId() == contentId) {
                                primary.add(text);
                            } else {
                                fallback.add(text);
                            }
                        }
                    }
                }

                if (node instanceof ViewGroup) {
                    ViewGroup group = (ViewGroup) node;
                    int count = group.getChildCount();
                    for (int i = count - 1; i >= 0; i--) {
                        stack.add(group.getChildAt(i));
                    }
                }
            }
            if (!primary.isEmpty()) {
                return primary;
            }
            return fallback;
        }

        private List<String> uniqueOrdered(List<String> input) {
            LinkedHashMap<String, Boolean> keep = new LinkedHashMap<String, Boolean>();
            for (int i = 0; i < input.size(); i++) {
                String item = input.get(i);
                if (item != null && !keep.containsKey(item)) {
                    keep.put(item, true);
                }
            }
            return new ArrayList<String>(keep.keySet());
        }

        private List<String> computeFrameDelta(List<String> previous, List<String> current) {
            List<String> out = new ArrayList<String>();
            if (current == null || current.isEmpty()) {
                return out;
            }
            if (previous == null || previous.isEmpty()) {
                out.addAll(current);
                return out;
            }

            int overlap = longestSuffixPrefix(previous, current);
            if (overlap > 0) {
                for (int i = overlap; i < current.size(); i++) {
                    out.add(current.get(i));
                }
                return out;
            }

            LinkedHashMap<String, Boolean> prevSet = new LinkedHashMap<String, Boolean>();
            for (int i = 0; i < previous.size(); i++) {
                prevSet.put(previous.get(i), true);
            }
            for (int i = 0; i < current.size(); i++) {
                String msg = current.get(i);
                if (!prevSet.containsKey(msg)) {
                    out.add(msg);
                }
            }
            return out;
        }

        private int longestSuffixPrefix(List<String> previous, List<String> current) {
            int max = Math.min(previous.size(), current.size());
            for (int len = max; len >= 1; len--) {
                boolean ok = true;
                int start = previous.size() - len;
                for (int i = 0; i < len; i++) {
                    if (!previous.get(start + i).equals(current.get(i))) {
                        ok = false;
                        break;
                    }
                }
                if (ok) {
                    return len;
                }
            }
            return 0;
        }

        private View findChatRootView() {
            if (chatVpId == 0) {
                chatVpId = activity.getResources().getIdentifier("chatVp", "id", TARGET_PACKAGE);
                if (chatVpId == 0) {
                    chatVpId = activity.getResources().getIdentifier("chatVp", "id", activity.getPackageName());
                }
            }
            if (contentId == 0) {
                contentId = activity.getResources().getIdentifier("content", "id", TARGET_PACKAGE);
                if (contentId == 0) {
                    contentId = activity.getResources().getIdentifier("content", "id", activity.getPackageName());
                }
            }
            if (chatVpId == 0) {
                return null;
            }
            return activity.findViewById(chatVpId);
        }

        private boolean isTextViewVisiblyInChatRoot(TextView tv, Rect chatRootVisibleRect) {
            if (tv == null || chatRootVisibleRect == null) {
                return false;
            }
            try {
                if (tv.getVisibility() != View.VISIBLE) {
                    return false;
                }
            } catch (Throwable ignored) {
                return false;
            }
            try {
                if (!tv.isShown()) {
                    return false;
                }
            } catch (Throwable ignored2) {
                return false;
            }
            try {
                if (tv.getAlpha() <= 0.02f) {
                    return false;
                }
            } catch (Throwable ignored3) {
            }

            Rect tvVisibleRect = new Rect();
            try {
                if (!tv.getGlobalVisibleRect(tvVisibleRect)) {
                    return false;
                }
            } catch (Throwable ignored4) {
                return false;
            }
            if (tvVisibleRect.width() <= 0 || tvVisibleRect.height() <= 0) {
                return false;
            }
            if (!Rect.intersects(chatRootVisibleRect, tvVisibleRect)) {
                return false;
            }
            int cx = tvVisibleRect.centerX();
            int cy = tvVisibleRect.centerY();
            return chatRootVisibleRect.contains(cx, cy);
        }

        private String normalize(String text) {
            if (text == null) {
                return null;
            }
            String t = text.replace('\n', ' ').replace('\r', ' ').trim();
            if (t.length() == 0) {
                return null;
            }
            if (t.length() > 400) {
                t = t.substring(0, 400);
            }
            return t;
        }

        // roomNo 可能包含前缀文本，统一提取数字作为房间 ID
        private String cleanRoomId(String raw) {
            String t = normalize(raw);
            if (t == null) {
                return "";
            }
            String digits = t.replaceAll("[^0-9]", "");
            if (digits.length() > 0) {
                return digits;
            }
            return t;
        }

        private boolean isEmpty(String s) {
            return s == null || s.trim().length() == 0;
        }

        private String safe(String value, String fallback) {
            if (isEmpty(value)) {
                return fallback;
            }
            return value;
        }

        private boolean isBlacklistedMessage(String msg) {
            if (!ModuleSettings.isBlockBlacklistEnabled()) {
                return false;
            }
            if (blacklistFilter.isBlocked(msg)) {
                return true;
            }
            String stripped = ChatMessageParser.stripIconPrefix(msg);
            return blacklistFilter.isBlocked(stripped);
        }

        /**
         * 统一输出入口：黑名单过滤 -> 去重 -> 入队异步远程上传。
         */
        private void emitChatMessage(String msg) {
            long now = System.currentTimeMillis();
            if (isBlacklistedMessage(msg)) {
                totalFilteredBlacklist = totalFilteredBlacklist + 1;
                maybeLogSummary(now);
                return;
            }
            if (!shouldEmit(msg, now)) {
                totalDroppedByDedup = totalDroppedByDedup + 1;
                maybeLogSummary(now);
                return;
            }

            String nowStr = nowTime(now);
            appendCsv(nowStr, msg);
            totalCaptured = totalCaptured + 1;
            maybeLogSummary(now);
        }

        private void maybeLogSummary(long nowMs) {
            if (nowMs - lastSummaryLogAt < SUMMARY_LOG_INTERVAL_MS) {
                return;
            }
            lastSummaryLogAt = nowMs;
            int pending = remoteBatchUploader.pendingSize();
            XposedBridge.log(TAG + " stats captured=" + totalCaptured
                    + " filtered=" + totalFilteredBlacklist
                    + " dedup=" + totalDroppedByDedup
                    + " pendingRemote=" + pending);
        }

        // 近窗去重：同一文本在 DEDUP_WINDOW_MS 内仅输出一次
        private boolean shouldEmit(String msg, long nowMs) {
            synchronized (STATE_LOCK) {
                Long lastMs = RECENT_MESSAGES.get(msg);
                if (lastMs != null && nowMs - lastMs < DEDUP_WINDOW_MS) {
                    return false;
                }

                RECENT_MESSAGES.put(msg, nowMs);
                while (RECENT_MESSAGES.size() > DEDUP_MAX_SIZE) {
                    String oldestKey = RECENT_MESSAGES.keySet().iterator().next();
                    RECENT_MESSAGES.remove(oldestKey);
                }
                return true;
            }
        }

        // 历史方法名保留：当前实现改为“远程入队上传”
        private void appendCsv(String timeStr, String msg) {
            String cleanedChat = ChatMessageParser.stripIconPrefix(msg);
            boolean parseEnabled = ModuleSettings.isChatParseEnabled();
            if (!parseEnabled) {
                enqueueRemoteRecord(timeStr, cleanedChat, "", "", "", null, "");
                scheduleRemoteFlush();
                return;
            }

            ChatMessageParser.GiftParseResult gift = ChatMessageParser.parseGift(cleanedChat, giftPriceCatalog);
            if (!gift.isGift) {
                enqueueRemoteRecord(timeStr, cleanedChat, "", "", "", null, "");
            } else {
                Integer quantity = Integer.valueOf(gift.quantityEach);
                String priceText = gift.totalPriceEachReceiver > 0
                        ? String.valueOf(gift.totalPriceEachReceiver)
                        : "未知";
                for (int i = 0; i < gift.receivers.size(); i++) {
                    String receiver = gift.receivers.get(i);
                    enqueueRemoteRecord(timeStr, cleanedChat, gift.sender, receiver, gift.giftName, quantity, priceText);
                }
            }
            scheduleRemoteFlush();
        }

        private void enqueueRemoteRecord(String eventTime,
                                         String chatText,
                                         String sender,
                                         String receiver,
                                         String giftName,
                                         Integer giftQty,
                                         String notePrice) {
            RemoteApiClient.ChatRecord rec = new RemoteApiClient.ChatRecord();
            rec.deviceId = safe(deviceId, "unknown_device");
            rec.roomId = safe(roomId, "");
            rec.eventTime = safe(eventTime, nowTime(System.currentTimeMillis()));
            rec.chatText = safe(chatText, "");
            rec.sender = safe(sender, "");
            rec.receiver = safe(receiver, "");
            rec.giftName = safe(giftName, "");
            rec.giftQty = giftQty;
            rec.notePrice = safe(notePrice, "");
            rec.msgHash = buildMsgHash(
                    rec.deviceId + "|" + rec.roomId + "|" + rec.eventTime + "|"
                            + rec.chatText + "|" + rec.sender + "|" + rec.receiver + "|"
                            + rec.giftName + "|" + (rec.giftQty == null ? "" : String.valueOf(rec.giftQty))
            );
            remoteBatchUploader.enqueue(rec);
        }

        private void scheduleRemoteFlush() {
            if (csvFlushScheduled) {
                return;
            }
            csvFlushScheduled = true;
            handler.postDelayed(csvFlushTask, CSV_FLUSH_INTERVAL_MS);
        }

        private void flushPendingCsvAsync() {
            csvFlushScheduled = false;
            remoteBatchUploader.flushAsync();
        }

        private String buildPathHint() {
            String baseUrl = remoteApiClient.getBaseUrl();
            if (isEmpty(baseUrl)) {
                return "远程API未配置";
            }
            return "远程: " + baseUrl;
        }

        private String nowTime(long ms) {
            return formatInCsvTimezone("yyyy-MM-dd HH:mm:ss.SSS", ms);
        }

        private String formatInCsvTimezone(String pattern, long ms) {
            SimpleDateFormat sdf = new SimpleDateFormat(pattern, Locale.CHINA);
            sdf.setTimeZone(TimeZone.getTimeZone(CSV_TIMEZONE_ID));
            return sdf.format(new Date(ms));
        }

        private String resolveDeviceId() {
            try {
                String id = Settings.Secure.getString(activity.getContentResolver(), Settings.Secure.ANDROID_ID);
                if (!isEmpty(id)) {
                    return id;
                }
            } catch (Throwable ignored) {
            }
            String fallback = activity.getPackageName() + "|" + android.os.Process.myPid();
            return buildMsgHash(fallback);
        }

        private String buildMsgHash(String raw) {
            String input = raw == null ? "" : raw;
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] bytes = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder(bytes.length * 2);
                for (int i = 0; i < bytes.length; i++) {
                    int v = bytes[i] & 0xff;
                    if (v < 0x10) {
                        sb.append('0');
                    }
                    sb.append(Integer.toHexString(v));
                }
                return sb.toString();
            } catch (Throwable ignored) {
                return String.valueOf(input.hashCode());
            }
        }
    }
}
