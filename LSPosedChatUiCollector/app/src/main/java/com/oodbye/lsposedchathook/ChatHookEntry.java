package com.oodbye.lsposedchathook;

import android.app.Activity;
import android.graphics.Color;
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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class ChatHookEntry implements IXposedHookLoadPackage {
    private static final String TARGET_PACKAGE = "com.netease.play";
    private static final String LIVE_ACTIVITY_KEYWORD = "LiveViewerActivity";
    private static final String TAG = "[LOOKChatHook]";
    private static final String FLOAT_TAG = "LOOK_CHAT_HOOK_FLOAT_BTN";
    private static final String MODULE_VERSION = "2026.02.25-r9";
    private static final String CSV_TIMEZONE_ID = "GMT+08:00";

    private static final long SCAN_INTERVAL_MS = 80L;
    private static final long TAB_CHECK_INTERVAL_MS = 250L;
    private static final long DEDUP_WINDOW_MS = 1200L;
    private static final int DEDUP_MAX_SIZE = 1200;

    private static final long ROOM_INFO_POLL_INTERVAL_MS = 250L;
    private static final int ROOM_INFO_POLL_MAX_RETRY = 10;
    private static final int ROOM_INFO_ENTER_MAX_RETRY = 3;
    private static final int CHAT_ROOT_MISS_CLEAR_THRESHOLD = 20;

    private static final Object FILE_LOCK = new Object();
    private static final Object STATE_LOCK = new Object();

    private static volatile boolean sHooksInstalled = false;

    private static final Map<Activity, UiSession> SESSIONS = new WeakHashMap<Activity, UiSession>();
    private static final LinkedHashMap<String, Long> RECENT_MESSAGES = new LinkedHashMap<String, Long>(256, 0.75f, true);

    @Override
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
                    if (!isLiveActivity(activity)) {
                        return;
                    }
                    ensureSession(activity);
                }
            });

            XposedBridge.hookAllMethods(Activity.class, "onPause", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Activity activity = (Activity) param.thisObject;
                    if (!isLiveActivity(activity)) {
                        return;
                    }
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

        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                UiSession session = getSession(activity);
                if (session == null) {
                    session = new UiSession(activity);
                    putSession(activity, session);
                }
                session.ensureFloatingButton();
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

    private static class UiSession {
        private final Activity activity;
        private final Handler handler;
        private final Runnable scanTask;
        private final Runnable roomInfoPollTask;

        private TextView floatButton;
        private boolean running;
        private boolean resolvingRoomInfo;
        private boolean openedDetailFromAvatar;
        private boolean captureEnabled;
        private boolean startupHintShown;

        private int chatVpId;
        private int contentId;
        private int avatarId;
        private int bgViewId;
        private int headerId;
        private int userIdResId;
        private int roomNoResId;

        private int roomInfoRetryCount;
        private int roomInfoEnterAttempt;
        private long lastMissingLogAt;
        private long lastNonRoomWarnAt;
        private long lastTabCheckAt;
        private int chatRootMissStreak;
        private boolean lastRoomTabActive = true;

        private String roomId = "";
        private File outputCsvFile;
        private File baseOutputDir;

        private List<String> lastFrameMessages = new ArrayList<String>();

        UiSession(Activity activity) {
            this.activity = activity;
            this.handler = new Handler(Looper.getMainLooper());

            this.scanTask = new Runnable() {
                @Override
                public void run() {
                    if (!running || activity.isFinishing() || activity.isDestroyed()) {
                        return;
                    }

                    if (captureEnabled && !resolvingRoomInfo) {
                        scanChatOnce();
                    }
                    refreshButtonStyle();
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
        }

        void start() {
            if (running) {
                return;
            }
            running = true;
            handler.post(scanTask);
        }

        void onActivityPause() {
            running = false;
            handler.removeCallbacks(scanTask);
            if (!resolvingRoomInfo) {
                handler.removeCallbacks(roomInfoPollTask);
                lastFrameMessages.clear();
            }
        }

        void pause() {
            running = false;
            handler.removeCallbacks(scanTask);
            handler.removeCallbacks(roomInfoPollTask);
            resolvingRoomInfo = false;
            lastFrameMessages.clear();
        }

        void destroy() {
            pause();
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
        }

        void ensureFloatingButton() {
            try {
                ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
                View existing = decor.findViewWithTag(FLOAT_TAG);
                if (existing instanceof TextView) {
                    floatButton = (TextView) existing;
                    refreshButtonStyle();
                    maybeShowStartupHint();
                    return;
                }

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
            Toast.makeText(activity, "聊天抓取默认关闭，点击悬浮按钮开启", Toast.LENGTH_SHORT).show();
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

        private void startEnableCaptureFlow() {
            resolveRoomResourceIds();
            outputCsvFile = null;
            baseOutputDir = null;
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
            captureEnabled = true;
            lastFrameMessages.clear();
            chatRootMissStreak = 0;
            if (!ensureCsvReady()) {
                failEnableCapture("CSV文件创建失败");
                return;
            }
            refreshButtonStyle();

            String info = "ID:" + safe(roomId, "unknown");
            Toast.makeText(activity, "聊天抓取已开启\n" + info + "\n" + buildPathHint(), Toast.LENGTH_SHORT).show();
            XposedBridge.log(TAG + " captureEnabled=true roomId=" + roomId
                    + " csv=" + (outputCsvFile == null ? "" : outputCsvFile.getAbsolutePath()));
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
            if (floatButton == null) {
                return;
            }

            if (resolvingRoomInfo) {
                floatButton.setText("读取房间中");
                floatButton.setTextColor(Color.WHITE);
                floatButton.setBackgroundColor(Color.parseColor("#E65100"));
                floatButton.setAlpha(0.9f);
                return;
            }

            if (captureEnabled) {
                floatButton.setText("聊天抓取:开");
                floatButton.setTextColor(Color.WHITE);
                floatButton.setBackgroundColor(Color.parseColor("#2E7D32"));
            } else {
                floatButton.setText("聊天抓取:关");
                floatButton.setTextColor(Color.WHITE);
                floatButton.setBackgroundColor(Color.parseColor("#B71C1C"));
            }
            floatButton.setAlpha(0.88f);
        }

        private void scanChatOnce() {
            if (!isRoomTabActiveFast()) {
                long now = System.currentTimeMillis();
                if (now - lastNonRoomWarnAt > 3000L) {
                    lastNonRoomWarnAt = now;
                    XposedBridge.log(TAG + " 当前不在“房间”tab，跳过采集");
                }
                lastFrameMessages.clear();
                return;
            }

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

            List<String> messages = new ArrayList<String>();
            collectText(chatRoot, messages, true, chatRootVisibleRect);
            if (messages.isEmpty()) {
                collectText(chatRoot, messages, false, chatRootVisibleRect);
            }

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

        private boolean isRoomTabActiveFast() {
            long now = System.currentTimeMillis();
            if (now - lastTabCheckAt < TAB_CHECK_INTERVAL_MS) {
                return lastRoomTabActive;
            }
            lastTabCheckAt = now;
            lastRoomTabActive = isRoomTabActive();
            return lastRoomTabActive;
        }

        private boolean isRoomTabActive() {
            TextView roomTab = findBottomTabTextView("房间");
            TextView squareTab = findBottomTabTextView("广场");

            if (roomTab == null && squareTab == null) {
                return false;
            }
            if (roomTab != null && squareTab == null) {
                return true;
            }
            if (roomTab == null) {
                return false;
            }

            Boolean byState = resolveRoomTabByState(roomTab, squareTab);
            if (byState != null) {
                return byState.booleanValue();
            }

            Boolean byColor = resolveRoomTabByColor(roomTab, squareTab);
            if (byColor != null) {
                return byColor.booleanValue();
            }

            return false;
        }

        private Boolean resolveRoomTabByState(TextView roomTab, TextView squareTab) {
            boolean roomActive = isViewActive(roomTab);
            boolean squareActive = isViewActive(squareTab);
            if (roomActive != squareActive) {
                return roomActive;
            }
            return null;
        }

        private Boolean resolveRoomTabByColor(TextView roomTab, TextView squareTab) {
            if (roomTab == null || squareTab == null) {
                return null;
            }
            try {
                int rc = roomTab.getCurrentTextColor();
                int sc = squareTab.getCurrentTextColor();
                double diff = colorLuma(rc) - colorLuma(sc);
                if (Math.abs(diff) < 8.0d) {
                    return null;
                }
                return diff > 0 ? Boolean.TRUE : Boolean.FALSE;
            } catch (Throwable ignored) {
                return null;
            }
        }

        private boolean isViewActive(View v) {
            if (v == null) {
                return false;
            }
            try {
                if (v.isSelected()) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
            try {
                if (v.isActivated()) {
                    return true;
                }
            } catch (Throwable ignored2) {
            }
            try {
                if (v.isPressed()) {
                    return true;
                }
            } catch (Throwable ignored3) {
            }
            try {
                if (v.isFocused()) {
                    return true;
                }
            } catch (Throwable ignored4) {
            }
            return false;
        }

        private double colorLuma(int color) {
            int r = Color.red(color);
            int g = Color.green(color);
            int b = Color.blue(color);
            return 0.2126d * r + 0.7152d * g + 0.0722d * b;
        }

        private TextView findBottomTabTextView(String label) {
            View root = activity.getWindow().getDecorView();
            if (root == null) {
                return null;
            }
            int screenHeight = 0;
            try {
                screenHeight = activity.getResources().getDisplayMetrics().heightPixels;
            } catch (Throwable ignored) {
                screenHeight = 0;
            }
            if (screenHeight <= 0) {
                screenHeight = 2400;
            }
            TabCandidate best = new TabCandidate();
            collectBottomTabTextView(root, label, screenHeight, best);
            return best.view;
        }

        private void collectBottomTabTextView(View node, String label, int screenHeight, TabCandidate best) {
            if (node == null) {
                return;
            }
            if (node instanceof TextView) {
                TextView tv = (TextView) node;
                String text = normalize(String.valueOf(tv.getText()));
                if (label.equals(text)) {
                    int[] loc = new int[2];
                    int h = 0;
                    int w = 0;
                    try {
                        node.getLocationOnScreen(loc);
                        h = node.getHeight();
                        w = node.getWidth();
                    } catch (Throwable ignored) {
                        h = 0;
                        w = 0;
                    }
                    if (h <= 0) {
                        try { h = node.getMeasuredHeight(); } catch (Throwable ignored2) { h = 0; }
                    }
                    if (w <= 0) {
                        try { w = node.getMeasuredWidth(); } catch (Throwable ignored3) { w = 0; }
                    }
                    int cy = loc[1] + Math.max(1, h) / 2;
                    int cx = loc[0] + Math.max(1, w) / 2;
                    if (cy >= (int) (screenHeight * 0.60f)) {
                        if (best.view == null || cy > best.centerY || (cy == best.centerY && cx < best.centerX)) {
                            best.view = tv;
                            best.centerY = cy;
                            best.centerX = cx;
                        }
                    }
                }
            }
            if (node instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) node;
                int count = group.getChildCount();
                for (int i = 0; i < count; i++) {
                    collectBottomTabTextView(group.getChildAt(i), label, screenHeight, best);
                }
            }
        }

        private static class TabCandidate {
            private TextView view;
            private int centerY = -1;
            private int centerX = Integer.MAX_VALUE;
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

        private void collectText(View node, List<String> out, boolean strictContentId, Rect chatRootVisibleRect) {
            if (node == null) {
                return;
            }

            if (node instanceof TextView) {
                TextView tv = (TextView) node;
                if (!isTextViewVisiblyInChatRoot(tv, chatRootVisibleRect)) {
                    // 过滤掉不可见/被隐藏/不在chatVp可见区域内的文本（如“广场”隐藏节点）
                    return;
                }
                CharSequence cs = tv.getText();
                String text = normalize(cs == null ? null : cs.toString());
                if (text != null) {
                    if (!strictContentId) {
                        out.add(text);
                    } else if (contentId != 0 && tv.getId() == contentId) {
                        out.add(text);
                    }
                }
            }

            if (node instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) node;
                int count = group.getChildCount();
                for (int i = 0; i < count; i++) {
                    collectText(group.getChildAt(i), out, strictContentId, chatRootVisibleRect);
                }
            }
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

        private void emitChatMessage(String msg) {
            long now = System.currentTimeMillis();
            if (!shouldEmit(msg, now)) {
                return;
            }

            String nowStr = nowTime(now);
            XposedBridge.log(TAG + " [ui] " + msg);
            appendCsv(nowStr, msg);
        }

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

        private void appendCsv(String timeStr, String msg) {
            if (!ensureCsvReady()) {
                return;
            }

            String line = csvEscape(timeStr) + "," + csvEscape(msg) + "\n";
            synchronized (FILE_LOCK) {
                try {
                    File dir = outputCsvFile.getParentFile();
                    if (dir == null) {
                        return;
                    }
                    if (!dir.exists() && !dir.mkdirs()) {
                        XposedBridge.log(TAG + " mkdir failed: " + dir.getAbsolutePath());
                        return;
                    }

                    boolean needHeader = !outputCsvFile.exists() || outputCsvFile.length() == 0;
                    FileOutputStream fos = new FileOutputStream(outputCsvFile, true);
                    try {
                        if (needHeader) {
                            fos.write(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
                            fos.write("时间,聊天记录\n".getBytes(StandardCharsets.UTF_8));
                        }
                        fos.write(line.getBytes(StandardCharsets.UTF_8));
                        XposedBridge.log(TAG + " csv append ok: " + outputCsvFile.getAbsolutePath() + " msg=" + msg);
                    } finally {
                        fos.close();
                    }
                } catch (IOException e) {
                    XposedBridge.log(TAG + " write csv error: " + e);
                }
            }
        }

        private boolean ensureCsvReady() {
            ensureOutputCsvFile();
            if (outputCsvFile == null) {
                return false;
            }
            return ensureCsvHeader(outputCsvFile);
        }

        private boolean ensureCsvHeader(File file) {
            if (file == null) {
                return false;
            }
            synchronized (FILE_LOCK) {
                try {
                    File dir = file.getParentFile();
                    if (dir == null) {
                        return false;
                    }
                    if (!dir.exists() && !dir.mkdirs()) {
                        XposedBridge.log(TAG + " mkdir failed: " + dir.getAbsolutePath());
                        return false;
                    }
                    boolean needHeader = !file.exists() || file.length() == 0;
                    FileOutputStream fos = new FileOutputStream(file, true);
                    try {
                        if (needHeader) {
                            fos.write(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
                            fos.write("时间,聊天记录\n".getBytes(StandardCharsets.UTF_8));
                        }
                    } finally {
                        fos.close();
                    }
                    return true;
                } catch (IOException e) {
                    XposedBridge.log(TAG + " ensure csv header error: " + e);
                    return false;
                }
            }
        }

        private void ensureOutputCsvFile() {
            if (outputCsvFile != null) {
                return;
            }

            String idPart = sanitizeFileNamePart(safe(roomId, "unknownRoomId"));
            String datePart = dayStamp();

            File baseDir = resolveBaseOutputDir();
            if (baseDir == null) {
                XposedBridge.log(TAG + " no writable output directory");
                return;
            }
            for (int idx = 1; idx <= 9999; idx++) {
                String fileName = idPart + "_" + datePart + "_" + pad3(idx) + ".csv";
                File f = new File(baseDir, fileName);
                if (!f.exists()) {
                    outputCsvFile = f;
                    XposedBridge.log(TAG + " csv target=" + outputCsvFile.getAbsolutePath());
                    return;
                }
            }

            outputCsvFile = new File(baseDir, idPart + "_" + datePart + "_" + nowStamp() + ".csv");
            XposedBridge.log(TAG + " csv target=" + outputCsvFile.getAbsolutePath());
        }

        private File resolveBaseOutputDir() {
            if (baseOutputDir != null) {
                return baseOutputDir;
            }

            List<File> candidates = new ArrayList<File>();
            candidates.add(new File("/storage/emulated/0/LiveRoomData"));
            candidates.add(new File("/sdcard/LiveRoomData"));

            try {
                File ext = activity.getExternalFilesDir(null);
                if (ext != null) {
                    candidates.add(new File(ext, "LiveRoomData"));
                }
            } catch (Throwable ignored) {
            }

            try {
                File internal = activity.getFilesDir();
                if (internal != null) {
                    candidates.add(new File(internal, "LiveRoomData"));
                }
            } catch (Throwable ignored2) {
            }

            for (int i = 0; i < candidates.size(); i++) {
                File dir = candidates.get(i);
                if (dir == null) {
                    continue;
                }
                try {
                    if (!dir.exists() && !dir.mkdirs()) {
                        continue;
                    }
                    if (dir.exists() && dir.isDirectory() && canWriteProbe(dir)) {
                        baseOutputDir = dir;
                        XposedBridge.log(TAG + " output dir=" + baseOutputDir.getAbsolutePath());
                        return baseOutputDir;
                    }
                } catch (Throwable ignored3) {
                }
            }
            return null;
        }

        private boolean canWriteProbe(File dir) {
            if (dir == null) {
                return false;
            }
            String probeName = ".probe_" + nowStamp() + "_" + System.currentTimeMillis();
            File probe = new File(dir, probeName);
            FileOutputStream fos = null;
            try {
                fos = new FileOutputStream(probe, false);
                fos.write("ok".getBytes(StandardCharsets.UTF_8));
                fos.flush();
                return true;
            } catch (Throwable ignored) {
                return false;
            } finally {
                try {
                    if (fos != null) {
                        fos.close();
                    }
                } catch (Throwable ignored2) {
                }
                try {
                    if (probe.exists()) {
                        probe.delete();
                    }
                } catch (Throwable ignored3) {
                }
            }
        }

        private String pad3(int value) {
            if (value < 10) {
                return "00" + value;
            }
            if (value < 100) {
                return "0" + value;
            }
            return String.valueOf(value);
        }

        private String sanitizeFileNamePart(String value) {
            String v = value;
            if (v == null) {
                v = "";
            }
            v = v.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
            while (v.contains("__")) {
                v = v.replace("__", "_");
            }
            if (v.startsWith("_")) {
                v = v.substring(1);
            }
            if (v.endsWith("_")) {
                v = v.substring(0, v.length() - 1);
            }
            if (v.length() == 0) {
                return "unknown";
            }
            if (v.length() > 60) {
                return v.substring(0, 60);
            }
            return v;
        }

        private String buildPathHint() {
            if (outputCsvFile != null) {
                return "CSV: " + outputCsvFile.getAbsolutePath();
            }
            File dir = resolveBaseOutputDir();
            if (dir != null) {
                return "目录: " + dir.getAbsolutePath();
            }
            return "CSV路径不可用";
        }

        private String nowTime(long ms) {
            return formatInCsvTimezone("yyyy-MM-dd HH:mm:ss.SSS", ms);
        }

        private String dayStamp() {
            return formatInCsvTimezone("yyyyMMdd", System.currentTimeMillis());
        }

        private String nowStamp() {
            return formatInCsvTimezone("HHmmss", System.currentTimeMillis());
        }

        private String formatInCsvTimezone(String pattern, long ms) {
            SimpleDateFormat sdf = new SimpleDateFormat(pattern, Locale.CHINA);
            sdf.setTimeZone(TimeZone.getTimeZone(CSV_TIMEZONE_ID));
            return sdf.format(new Date(ms));
        }

        private String csvEscape(String s) {
            if (s == null) {
                return "\"\"";
            }
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
    }
}
