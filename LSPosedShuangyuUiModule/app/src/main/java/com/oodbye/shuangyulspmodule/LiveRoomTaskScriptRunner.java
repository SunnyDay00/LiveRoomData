package com.oodbye.shuangyulspmodule;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 双鱼直播数据采集任务脚本执行器。
 *
 * 工作流：
 * 1. 点击「娱乐」Tab
 * 2. 切换到目标榜单 Tab（女神/男神/点唱）
 * 3. 遍历房间列表中的房间卡片
 * 4. 进入房间 → 获取房间名+ID → 点击在线榜 → 遍历用户卡片
 * 5. 过滤用户（管理员/房主、等级、忽略ID）
 * 6. 点击有效用户 → 读取地区+年龄 → 保存CSV + 推送飞书
 * 7. 返回房间列表 → 下一个房间
 * 8. 所有房间完成后切换榜单 → 重复
 */
final class LiveRoomTaskScriptRunner {
    private static final String TAG = "SYLspModule";

    // ─────────────────────── 回调接口 ───────────────────────
    interface TaskFinishListener {
        void onTaskFinished(boolean success, String message);
    }

    interface TaskBridge {
        boolean shouldStop();
        void updateStatus(String status);
        void postDelayed(Runnable r, long delayMs);
    }

    // ─────────────────────── 成员变量 ───────────────────────
    private final Activity activity;
    private final Handler handler;
    private final TaskBridge bridge;
    private final Set<String> visitedRoomNames = new HashSet<>();
    private final Set<String> collectedUserIds = new HashSet<>();
    private int totalCollected = 0;
    private int totalRoomsEntered = 0;

    LiveRoomTaskScriptRunner(Activity activity, Handler handler, TaskBridge bridge) {
        this.activity = activity;
        this.handler = handler;
        this.bridge = bridge;
    }

    // ─────────────────────── 主入口 ───────────────────────

    void execute(final TaskFinishListener listener) {
        ModuleRunFileLogger.i(TAG, "=== 采集任务开始 ===");

        // 获取要采集的榜单列表
        final List<RankTab> enabledTabs = getEnabledRankTabs();
        if (enabledTabs.isEmpty()) {
            ModuleRunFileLogger.w(TAG, "没有启用任何榜单");
            if (listener != null) listener.onTaskFinished(false, "没有启用任何榜单");
            return;
        }

        // Step 1: 点击娱乐 Tab
        bridge.updateStatus("正在点击娱乐Tab");
        clickEntertainmentTab(new Runnable() {
            @Override
            public void run() {
                // Step 2: 开始遍历榜单
                processRankTabs(enabledTabs, 0, listener);
            }
        });
    }

    // ─────────────────────── 榜单类型 ───────────────────────

    enum RankTab {
        GODDESS("女神"),
        GOD("男神"),
        SING("点唱");

        final String label;
        RankTab(String label) { this.label = label; }
    }

    private List<RankTab> getEnabledRankTabs() {
        List<RankTab> tabs = new ArrayList<>();
        if (ModuleSettings.isRankGoddessEnabled()) tabs.add(RankTab.GODDESS);
        if (ModuleSettings.isRankGodEnabled()) tabs.add(RankTab.GOD);
        if (ModuleSettings.isRankSingEnabled()) tabs.add(RankTab.SING);
        return tabs;
    }

    // ─────────────────────── Step 1: 点击娱乐 Tab ───────────────────────

    private void clickEntertainmentTab(final Runnable onDone) {
        if (shouldStop()) return;

        View decor = activity.getWindow().getDecorView();
        View tabView = findViewByResourceId(decor,
                UiComponentConfig.HOME_TAB_ENTERTAINMENT.fullResourceId);
        if (tabView == null) {
            // 尝试找容器
            tabView = findViewByResourceId(decor,
                    UiComponentConfig.HOME_TAB_ENTERTAINMENT_CONTAINER.fullResourceId);
        }

        if (tabView != null) {
            ModuleRunFileLogger.i(TAG, "点击娱乐Tab");
            tabView.performClick();
            handler.postDelayed(onDone, UiComponentConfig.CLICK_WAIT_MS);
        } else {
            ModuleRunFileLogger.w(TAG, "未找到娱乐Tab，尝试继续");
            handler.postDelayed(onDone, UiComponentConfig.CLICK_WAIT_MS);
        }
    }

    // ─────────────────────── Step 2: 切换榜单 Tab ───────────────────────

    private void processRankTabs(final List<RankTab> tabs, final int index,
                                  final TaskFinishListener listener) {
        if (shouldStop() || index >= tabs.size()) {
            ModuleRunFileLogger.i(TAG, "=== 所有榜单处理完成 总采集=" + totalCollected
                    + " 总房间=" + totalRoomsEntered + " ===");
            if (listener != null) {
                listener.onTaskFinished(true,
                        "采集完成: " + totalCollected + " 条, " + totalRoomsEntered + " 个房间");
            }
            return;
        }

        final RankTab tab = tabs.get(index);
        bridge.updateStatus("切换到 " + tab.label + " 榜单");
        ModuleRunFileLogger.i(TAG, "切换到榜单: " + tab.label);

        clickRankTab(tab, new Runnable() {
            @Override
            public void run() {
                // Step 3: 遍历房间列表
                visitedRoomNames.clear();
                processRoomList(0, tab, new Runnable() {
                    @Override
                    public void run() {
                        // 下一个榜单
                        processRankTabs(tabs, index + 1, listener);
                    }
                });
            }
        });
    }

    private void clickRankTab(RankTab tab, final Runnable onDone) {
        View decor = activity.getWindow().getDecorView();
        View tabView = findViewByText(decor, tab.label, "android.widget.TextView");
        if (tabView != null) {
            ModuleRunFileLogger.i(TAG, "点击榜单Tab: " + tab.label);
            tabView.performClick();
        } else {
            ModuleRunFileLogger.w(TAG, "未找到榜单Tab: " + tab.label);
        }
        handler.postDelayed(onDone, UiComponentConfig.CLICK_WAIT_MS);
    }

    // ─────────────────────── Step 3: 遍历房间列表 ───────────────────────

    private void processRoomList(final int retryCount, final RankTab rankTab,
                                  final Runnable onAllDone) {
        if (shouldStop()) return;

        View decor = activity.getWindow().getDecorView();
        ViewGroup recycler = (ViewGroup) findViewByResourceId(decor,
                UiComponentConfig.ROOM_LIST_RECYCLER_ID);

        if (recycler == null) {
            ModuleRunFileLogger.w(TAG, "未找到房间列表 RecyclerView");
            if (retryCount < UiComponentConfig.NO_NEW_CONTENT_MAX_RETRIES) {
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        processRoomList(retryCount + 1, rankTab, onAllDone);
                    }
                }, UiComponentConfig.SCROLL_WAIT_MS);
            } else {
                onAllDone.run();
            }
            return;
        }

        // 收集当前可见的房间卡片
        final List<View> roomCards = findRoomCards(recycler);
        if (roomCards.isEmpty()) {
            ModuleRunFileLogger.w(TAG, "没有找到房间卡片");
            onAllDone.run();
            return;
        }

        processRoomCards(roomCards, 0, recycler, rankTab, onAllDone);
    }

    private List<View> findRoomCards(ViewGroup recycler) {
        List<View> cards = new ArrayList<>();
        for (int i = 0; i < recycler.getChildCount(); i++) {
            View child = recycler.getChildAt(i);
            if (child instanceof ViewGroup) {
                // 检查卡片内是否有房间名节点
                View roomNameView = findViewByResourceId(child,
                        UiComponentConfig.ROOM_CARD_NAME_ID);
                if (roomNameView != null) {
                    cards.add(child);
                }
            }
        }
        return cards;
    }

    private void processRoomCards(final List<View> cards, final int index,
                                   final ViewGroup recycler, final RankTab rankTab,
                                   final Runnable onAllDone) {
        if (shouldStop() || index >= cards.size()) {
            // 滚动加载更多
            tryScrollForMoreRooms(recycler, rankTab, onAllDone);
            return;
        }

        View card = cards.get(index);
        String roomName = getTextFromChild(card, UiComponentConfig.ROOM_CARD_NAME_ID);
        String hostName = getTextFromChild(card, UiComponentConfig.ROOM_CARD_HOST_ID);

        if (TextUtils.isEmpty(roomName)) {
            processRoomCards(cards, index + 1, recycler, rankTab, onAllDone);
            return;
        }

        if (visitedRoomNames.contains(roomName)) {
            ModuleRunFileLogger.i(TAG, "跳过已访问房间: " + roomName);
            processRoomCards(cards, index + 1, recycler, rankTab, onAllDone);
            return;
        }

        visitedRoomNames.add(roomName);
        bridge.updateStatus("进入房间: " + roomName);
        ModuleRunFileLogger.i(TAG, "点击进入房间: " + roomName + " 主播: " + hostName);

        card.performClick();
        totalRoomsEntered++;

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                // 在房间内执行采集
                collectInRoom(new Runnable() {
                    @Override
                    public void run() {
                        // 返回房间列表
                        bridge.updateStatus("返回房间列表");
                        pressBack();
                        handler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                processRoomCards(cards, index + 1, recycler, rankTab, onAllDone);
                            }
                        }, UiComponentConfig.BACK_WAIT_MS);
                    }
                });
            }
        }, UiComponentConfig.ENTER_ROOM_WAIT_MS);
    }

    private void tryScrollForMoreRooms(final ViewGroup recycler, final RankTab rankTab,
                                        final Runnable onAllDone) {
        if (shouldStop()) {
            onAllDone.run();
            return;
        }
        bridge.updateStatus("滚动加载更多房间");
        int prevCount = recycler.getChildCount();
        scrollDown(recycler);

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                int newCount = recycler.getChildCount();
                List<View> newCards = findRoomCards(recycler);
                // 过滤已访问
                List<View> unvisited = new ArrayList<>();
                for (View card : newCards) {
                    String name = getTextFromChild(card, UiComponentConfig.ROOM_CARD_NAME_ID);
                    if (!TextUtils.isEmpty(name) && !visitedRoomNames.contains(name)) {
                        unvisited.add(card);
                    }
                }
                if (unvisited.isEmpty()) {
                    ModuleRunFileLogger.i(TAG, "没有新的未访问房间，完成当前榜单");
                    onAllDone.run();
                } else {
                    processRoomCards(unvisited, 0, recycler, rankTab, onAllDone);
                }
            }
        }, UiComponentConfig.SCROLL_WAIT_MS);
    }

    // ─────────────────────── Step 4-7: 房间内采集 ───────────────────────

    private void collectInRoom(final Runnable onDone) {
        if (shouldStop()) { onDone.run(); return; }

        View decor = activity.getWindow().getDecorView();

        // 获取房间名和 ID
        final String roomName = getTextFromView(decor, UiComponentConfig.LIVE_ROOM_NAME_ID);
        final String roomId = getTextFromView(decor, UiComponentConfig.LIVE_ROOM_CODE_ID);
        ModuleRunFileLogger.i(TAG, "房间内: name=" + roomName + " id=" + roomId);

        // 点击在线榜入口
        View onlineLayout = findViewByResourceId(decor, UiComponentConfig.ONLINE_USER_LAYOUT_ID);
        if (onlineLayout == null) {
            ModuleRunFileLogger.w(TAG, "未找到在线榜入口");
            onDone.run();
            return;
        }

        bridge.updateStatus("打开在线列表");
        onlineLayout.performClick();

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                collectUsersInOnlineList(roomName, roomId, 0, onDone);
            }
        }, UiComponentConfig.ONLINE_LIST_WAIT_MS);
    }

    private void collectUsersInOnlineList(final String roomName, final String roomId,
                                           final int scrollRetry, final Runnable onDone) {
        if (shouldStop()) { onDone.run(); return; }

        View decor = activity.getWindow().getDecorView();
        ViewGroup userRecycler = findUserRecycler(decor);
        if (userRecycler == null) {
            ModuleRunFileLogger.w(TAG, "未找到用户列表 RecyclerView");
            // 关闭在线榜
            pressBack();
            handler.postDelayed(onDone, UiComponentConfig.BACK_WAIT_MS);
            return;
        }

        final List<View> userCards = findUserCards(userRecycler);
        if (userCards.isEmpty() && scrollRetry >= UiComponentConfig.NO_NEW_CONTENT_MAX_RETRIES) {
            ModuleRunFileLogger.i(TAG, "用户列表为空或已遍历完成");
            pressBack();
            handler.postDelayed(onDone, UiComponentConfig.BACK_WAIT_MS);
            return;
        }

        processUserCards(userCards, 0, roomName, roomId, userRecycler, new Runnable() {
            @Override
            public void run() {
                // 滚动加载更多用户
                tryScrollForMoreUsers(userRecycler, roomName, roomId, scrollRetry, onDone);
            }
        });
    }

    private void processUserCards(final List<View> cards, final int index,
                                   final String roomName, final String roomId,
                                   final ViewGroup userRecycler, final Runnable onBatchDone) {
        if (shouldStop() || index >= cards.size()) {
            onBatchDone.run();
            return;
        }

        View card = cards.get(index);
        String userId = getTextFromChild(card, UiComponentConfig.USER_CODE_ID);
        String nickname = getTextFromChild(card, UiComponentConfig.USER_NICKNAME_ID);

        // 过滤管理员/房主（有 iv_room_role 图标的）
        View roleIcon = findViewByResourceId(card, UiComponentConfig.USER_ROOM_ROLE_ICON_ID);
        boolean isAdminOrOwner = (roleIcon != null && roleIcon.getVisibility() == View.VISIBLE);

        if (isAdminOrOwner) {
            ModuleRunFileLogger.i(TAG, "跳过管理员/房主: " + nickname);
            processUserCards(cards, index + 1, roomName, roomId, userRecycler, onBatchDone);
            return;
        }

        // 过滤忽略的用户 ID
        Set<String> ignoreIds = ModuleSettings.parseIgnoreUserIdSet(
                ModuleSettings.getIgnoreUserIds());
        if (!TextUtils.isEmpty(userId) && ignoreIds.contains(userId)) {
            ModuleRunFileLogger.i(TAG, "跳过忽略用户: " + userId);
            processUserCards(cards, index + 1, roomName, roomId, userRecycler, onBatchDone);
            return;
        }

        // 跳过已采集用户
        if (!TextUtils.isEmpty(userId) && collectedUserIds.contains(userId)) {
            processUserCards(cards, index + 1, roomName, roomId, userRecycler, onBatchDone);
            return;
        }

        // 点击用户卡片获取详细信息
        bridge.updateStatus("采集用户: " + nickname);
        card.performClick();

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                // 读取用户详情
                View detailDecor = activity.getWindow().getDecorView();
                String location = getTextFromView(detailDecor,
                        UiComponentConfig.USER_DETAIL_LOCATION_ID);
                String age = getTextFromView(detailDecor,
                        UiComponentConfig.USER_DETAIL_AGE_ID);

                String userId2 = getTextFromChild(cards.get(index),
                        UiComponentConfig.USER_CODE_ID);
                String nickname2 = getTextFromChild(cards.get(index),
                        UiComponentConfig.USER_NICKNAME_ID);

                // 保存数据
                saveUserData(userId2, nickname2, "", age, "", "", "", location,
                        roomName, roomId);

                // 关闭用户详情（按返回）
                pressBack();

                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        processUserCards(cards, index + 1, roomName, roomId,
                                userRecycler, onBatchDone);
                    }
                }, UiComponentConfig.BACK_WAIT_MS);
            }
        }, UiComponentConfig.USER_CARD_WAIT_MS);
    }

    private void tryScrollForMoreUsers(final ViewGroup userRecycler,
                                        final String roomName, final String roomId,
                                        final int prevRetry, final Runnable onDone) {
        if (shouldStop()) {
            pressBack();
            handler.postDelayed(onDone, UiComponentConfig.BACK_WAIT_MS);
            return;
        }

        int prevChildCount = userRecycler.getChildCount();
        scrollDown(userRecycler);

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                List<View> newCards = findUserCards(userRecycler);
                List<View> uncollected = new ArrayList<>();
                for (View card : newCards) {
                    String uid = getTextFromChild(card, UiComponentConfig.USER_CODE_ID);
                    if (!TextUtils.isEmpty(uid) && !collectedUserIds.contains(uid)) {
                        uncollected.add(card);
                    }
                }

                if (uncollected.isEmpty()) {
                    int nextRetry = prevRetry + 1;
                    if (nextRetry >= UiComponentConfig.NO_NEW_CONTENT_MAX_RETRIES) {
                        ModuleRunFileLogger.i(TAG, "用户列表无新内容，完成当前房间");
                        pressBack();
                        handler.postDelayed(onDone, UiComponentConfig.BACK_WAIT_MS);
                    } else {
                        collectUsersInOnlineList(roomName, roomId, nextRetry, onDone);
                    }
                } else {
                    processUserCards(uncollected, 0, roomName, roomId, userRecycler,
                            new Runnable() {
                                @Override
                                public void run() {
                                    tryScrollForMoreUsers(userRecycler, roomName, roomId,
                                            0, onDone);
                                }
                            });
                }
            }
        }, UiComponentConfig.SCROLL_WAIT_MS);
    }

    // ─────────────────────── 保存数据 ───────────────────────

    private void saveUserData(String userId, String userName, String gender,
                               String age, String wealthLevel, String charmLevel,
                               String followers, String location,
                               String roomName, String roomId) {
        long now = System.currentTimeMillis();
        LiveRoomRankCsvStore.UserDataRow row = new LiveRoomRankCsvStore.UserDataRow(
                userId, userName, gender, age, wealthLevel, charmLevel,
                followers, location, roomName, roomId, now
        );
        boolean success = LiveRoomRankCsvStore.appendRow(activity, row);
        if (success) {
            totalCollected++;
            if (!TextUtils.isEmpty(userId)) {
                collectedUserIds.add(userId);
            }
            ModuleRunFileLogger.i(TAG, "已采集 #" + totalCollected + ": "
                    + userName + " (ID:" + userId + ") 房间:" + roomName);

            // 推送到飞书
            sendToFeishu(userId, userName, age, location, roomName, roomId);
        } else {
            ModuleRunFileLogger.e(TAG, "CSV 保存失败: " + userName);
        }
    }

    private void sendToFeishu(String userId, String userName, String age,
                               String location, String roomName, String roomId) {
        if (!ModuleSettings.isFeishuPushEnabled()) return;
        final String text = "新用户采集\n"
                + "昵称: " + safeTrim(userName) + "\n"
                + "ID: " + safeTrim(userId) + "\n"
                + "年龄: " + safeTrim(age) + "\n"
                + "地区: " + safeTrim(location) + "\n"
                + "房间: " + safeTrim(roomName) + " (" + safeTrim(roomId) + ")";
        new Thread(new Runnable() {
            @Override
            public void run() {
                FeishuWebhookSender.sendText(text);
            }
        }).start();
    }

    // ─────────────────────── View 查找工具 ───────────────────────

    private View findViewByResourceId(View root, String fullResourceId) {
        if (root == null || TextUtils.isEmpty(fullResourceId)) return null;
        String shortId = UiComponentConfig.shortResourceId(fullResourceId);
        if (TextUtils.isEmpty(shortId)) return null;
        int resId = activity.getResources().getIdentifier(
                shortId, "id", UiComponentConfig.TARGET_PACKAGE);
        if (resId != 0) {
            View found = root.findViewById(resId);
            if (found != null) return found;
        }
        // 备用：递归搜索
        return findViewByResourceIdRecursive(root, fullResourceId);
    }

    private View findViewByResourceIdRecursive(View root, String fullResourceId) {
        if (root == null) return null;
        try {
            int id = root.getId();
            if (id != View.NO_ID) {
                try {
                    String entryName = root.getResources().getResourceEntryName(id);
                    String pkg = root.getResources().getResourcePackageName(id);
                    String full = pkg + ":id/" + entryName;
                    if (full.equals(fullResourceId)) return root;
                } catch (Throwable ignore) {
                }
            }
        } catch (Throwable ignore) {
        }
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View found = findViewByResourceIdRecursive(vg.getChildAt(i), fullResourceId);
                if (found != null) return found;
            }
        }
        return null;
    }

    private View findViewByText(View root, String text, String className) {
        if (root == null || TextUtils.isEmpty(text)) return null;
        if (root instanceof TextView) {
            CharSequence ct = ((TextView) root).getText();
            if (ct != null && ct.toString().equals(text)) {
                if (TextUtils.isEmpty(className)
                        || root.getClass().getName().contains(className.replace("android.widget.", ""))) {
                    return root;
                }
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View found = findViewByText(vg.getChildAt(i), text, className);
                if (found != null) return found;
            }
        }
        return null;
    }

    private String getTextFromView(View root, String fullResourceId) {
        View v = findViewByResourceId(root, fullResourceId);
        if (v instanceof TextView) {
            CharSequence ct = ((TextView) v).getText();
            return ct != null ? ct.toString().trim() : "";
        }
        return "";
    }

    private String getTextFromChild(View parent, String fullResourceId) {
        return getTextFromView(parent, fullResourceId);
    }

    private ViewGroup findUserRecycler(View root) {
        // 在线榜的用户列表与房间列表共用同一 resource-id
        // 但此时应该是在线榜弹出层中
        View v = findViewByResourceId(root, UiComponentConfig.USER_LIST_RECYCLER_ID);
        if (v instanceof ViewGroup) return (ViewGroup) v;
        return null;
    }

    private List<View> findUserCards(ViewGroup recycler) {
        List<View> cards = new ArrayList<>();
        if (recycler == null) return cards;
        for (int i = 0; i < recycler.getChildCount(); i++) {
            View child = recycler.getChildAt(i);
            if (child instanceof ViewGroup) {
                View nicknameView = findViewByResourceId(child,
                        UiComponentConfig.USER_NICKNAME_ID);
                if (nicknameView != null) {
                    cards.add(child);
                }
            }
        }
        return cards;
    }

    // ─────────────────────── 控制操作 ───────────────────────

    private void scrollDown(View view) {
        if (view == null) return;
        try {
            int height = view.getHeight();
            view.scrollBy(0, (int) (height * 0.7));
        } catch (Throwable e) {
            ModuleRunFileLogger.e(TAG, "scrollDown 失败", e);
        }
    }

    private void pressBack() {
        try {
            activity.onBackPressed();
        } catch (Throwable e) {
            ModuleRunFileLogger.e(TAG, "pressBack 失败", e);
        }
    }

    private boolean shouldStop() {
        return bridge != null && bridge.shouldStop();
    }

    private static String safeTrim(String value) {
        if (value == null) return "";
        return value.trim();
    }
}
