package com.oodbye.shuangyulspmodule;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 双鱼直播数据采集任务脚本执行器 v3（基于无障碍服务 API）。
 *
 * 所有 UI 操作（点击、滑动、查找）均通过 ShuangyuAccessibilityAdService 的
 * AccessibilityNodeInfo API 执行，确保跨进程、跨 Display 可靠性。
 */
final class LiveRoomTaskScriptRunner {
    private static final String TAG = "SYLspModule";

    interface TaskFinishListener {
        void onTaskFinished(boolean success, String message);
    }

    interface TaskBridge {
        boolean shouldStop();
        void updateStatus(String status);
    }

    private final ShuangyuAccessibilityAdService a11y;
    private final Handler handler;
    private final TaskBridge bridge;
    private final Set<String> visitedRoomNames = new HashSet<>();
    private final Set<String> collectedUserIds = new HashSet<>();
    private int totalCollected = 0;
    private int totalRoomsEntered = 0;

    LiveRoomTaskScriptRunner(ShuangyuAccessibilityAdService a11yService,
                              Handler handler, TaskBridge bridge) {
        this.a11y = a11yService;
        this.handler = handler;
        this.bridge = bridge;
    }

    // ═══════════════════════ 通用验证循环 ═══════════════════════

    interface Condition { boolean check(); }

    /**
     * 先延迟 initialDelayMs，再开始验证循环。
     */
    private void delayThenVerify(final String label, final long initialDelayMs,
                                  final Condition condition,
                                  final int maxRetries, final int tryBackEvery,
                                  final Runnable onSuccess, final Runnable onFail) {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                verifyLoop(label, condition, 0, maxRetries, tryBackEvery, onSuccess, onFail);
            }
        }, initialDelayMs);
    }

    private void verifyLoop(final String label, final Condition condition,
                             final int attempt, final int maxRetries,
                             final int tryBackEvery,
                             final Runnable onSuccess, final Runnable onFail) {
        if (shouldStop()) return;

        if (condition.check()) {
            ModuleRunFileLogger.i(TAG, "✅ 验证通过: " + label + " (第" + attempt + "次)");
            onSuccess.run();
            return;
        }

        if (attempt >= maxRetries) {
            ModuleRunFileLogger.w(TAG, "❌ 验证超时: " + label + " (尝试 " + maxRetries + " 次)");
            if (onFail != null) onFail.run();
            return;
        }

        if (tryBackEvery > 0 && attempt > 0 && attempt % tryBackEvery == 0) {
            ModuleRunFileLogger.i(TAG, "⏳ " + label + " 未通过 (" + attempt + "), 按返回关闭弹窗");
            a11y.performGlobalBack();
        } else {
            ModuleRunFileLogger.i(TAG, "⏳ " + label + " 等待中 (" + attempt + ")");
        }

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                verifyLoop(label, condition, attempt + 1, maxRetries,
                        tryBackEvery, onSuccess, onFail);
            }
        }, UiComponentConfig.VERIFY_INTERVAL_MS);
    }

    // ═══════════════════════ 主入口 ═══════════════════════

    void execute(final TaskFinishListener listener) {
        ModuleRunFileLogger.i(TAG, "📋 === 采集任务开始（无障碍 API 模式）===");

        final List<RankTab> enabledTabs = getEnabledRankTabs();
        if (enabledTabs.isEmpty()) {
            ModuleRunFileLogger.w(TAG, "⚠️ 没有启用任何榜单");
            if (listener != null) listener.onTaskFinished(false, "没有启用任何榜单");
            return;
        }
        ModuleRunFileLogger.i(TAG, "📋 需采集榜单: " + enabledTabs.size() + " 个");

        // Step 0: 等待娱乐Tab可见
        bridge.updateStatus("等待主界面就绪...");
        verifyLoop("等待主界面就绪", new Condition() {
            @Override
            public boolean check() {
                return a11y.isNodePresent(
                        UiComponentConfig.HOME_TAB_ENTERTAINMENT.fullResourceId);
            }
        }, 0, UiComponentConfig.VERIFY_MAX_RETRIES, 3, new Runnable() {
            @Override
            public void run() {
                stepClickEntertainmentTab(enabledTabs, listener);
            }
        }, new Runnable() {
            @Override
            public void run() {
                ModuleRunFileLogger.w(TAG, "⚠️ 等待主界面超时，尝试继续");
                stepClickEntertainmentTab(enabledTabs, listener);
            }
        });
    }

    // ═══════════════════════ Step 1: 点击娱乐 Tab ═══════════════════════

    private void stepClickEntertainmentTab(final List<RankTab> enabledTabs,
                                            final TaskFinishListener listener) {
        if (shouldStop()) return;

        bridge.updateStatus("点击娱乐Tab...");
        ModuleRunFileLogger.i(TAG, "🏠 点击娱乐Tab");

        // 先尝试容器 ID，再尝试文本 ID
        boolean clicked = a11y.clickNodeByResourceId(
                UiComponentConfig.HOME_TAB_ENTERTAINMENT_CONTAINER.fullResourceId);
        if (!clicked) {
            clicked = a11y.clickNodeByResourceId(
                    UiComponentConfig.HOME_TAB_ENTERTAINMENT.fullResourceId);
        }
        if (!clicked) {
            clicked = a11y.clickNodeByText("娱乐");
        }
        ModuleRunFileLogger.i(TAG, "🏠 娱乐Tab点击结果: " + clicked);

        // 延迟 2 秒后验证：榜单 TabLayout 可见
        delayThenVerify("验证已进入娱乐界面", 2000L, new Condition() {
            @Override
            public boolean check() {
                return a11y.isNodePresent(UiComponentConfig.RANK_TAB_LAYOUT_ID);
            }
        }, UiComponentConfig.VERIFY_MAX_RETRIES, 3, new Runnable() {
            @Override
            public void run() {
                processRankTabs(enabledTabs, 0, listener);
            }
        }, new Runnable() {
            @Override
            public void run() {
                ModuleRunFileLogger.w(TAG, "⚠️ 未能确认进入娱乐界面，尝试继续");
                processRankTabs(enabledTabs, 0, listener);
            }
        });
    }

    // ═══════════════════════ 榜单 ═══════════════════════

    enum RankTab {
        GODDESS("女神"), GOD("男神"), SING("点唱");
        final String label;
        RankTab(String label) { this.label = label; }
    }

    private List<RankTab> getEnabledRankTabs() {
        SharedPreferences prefs = ModuleSettings.appPrefs(a11y);
        List<RankTab> tabs = new ArrayList<>();
        if (ModuleSettings.getRankGoddessEnabled(prefs)) tabs.add(RankTab.GODDESS);
        if (ModuleSettings.getRankGodEnabled(prefs)) tabs.add(RankTab.GOD);
        if (ModuleSettings.getRankSingEnabled(prefs)) tabs.add(RankTab.SING);
        return tabs;
    }

    // ═══════════════════════ Step 2: 切换榜单 Tab ═══════════════════════

    private void processRankTabs(final List<RankTab> tabs, final int index,
                                  final TaskFinishListener listener) {
        if (shouldStop() || index >= tabs.size()) {
            ModuleRunFileLogger.i(TAG, "📊📊📊 === 所有榜单处理完成 总采集=" + totalCollected
                    + " 总房间=" + totalRoomsEntered + " ===");
            if (listener != null) {
                listener.onTaskFinished(true,
                        "采集完成: " + totalCollected + " 条, " + totalRoomsEntered + " 个房间");
            }
            return;
        }

        final RankTab tab = tabs.get(index);
        bridge.updateStatus("切换到 " + tab.label + " 榜单");
        ModuleRunFileLogger.i(TAG, "📌 切换到榜单: " + tab.label);

        boolean clicked = a11y.clickNodeByText(tab.label);
        ModuleRunFileLogger.i(TAG, "📌 点击榜单Tab[" + tab.label + "] 结果: " + clicked);

        // 延迟 2 秒后验证：有房间卡片
        delayThenVerify("验证已进入" + tab.label + "榜单", 2000L, new Condition() {
            @Override
            public boolean check() {
                List<String> rooms = a11y.getTextListByResourceId(
                        UiComponentConfig.ROOM_CARD_NAME_ID);
                return rooms != null && rooms.size() > 0;
            }
        }, UiComponentConfig.VERIFY_MAX_RETRIES, 0, new Runnable() {
            @Override
            public void run() {
                visitedRoomNames.clear();
                refreshAndProcessRooms(tab, new Runnable() {
                    @Override
                    public void run() {
                        processRankTabs(tabs, index + 1, listener);
                    }
                });
            }
        }, new Runnable() {
            @Override
            public void run() {
                ModuleRunFileLogger.w(TAG, "⚠️ " + tab.label + " 榜单无房间，跳过");
                processRankTabs(tabs, index + 1, listener);
            }
        });
    }

    // ═══════════════════════ Step 3: 遍历房间列表 ═══════════════════════

    private void refreshAndProcessRooms(final RankTab rankTab, final Runnable onAllDone) {
        if (shouldStop()) return;

        bridge.updateStatus("读取 " + rankTab.label + " 房间列表...");
        ModuleRunFileLogger.i(TAG, "🔃 读取房间列表");

        // 等待列表加载完成后直接读取
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                processRoomList(rankTab, onAllDone);
            }
        }, 1500L);
    }

    private void processRoomList(final RankTab rankTab, final Runnable onAllDone) {
        if (shouldStop()) return;

        List<String> roomNames = a11y.getTextListByResourceId(
                UiComponentConfig.ROOM_CARD_NAME_ID);

        if (roomNames == null || roomNames.isEmpty()) {
            ModuleRunFileLogger.w(TAG, "⚠️ 没有找到房间卡片");
            onAllDone.run();
            return;
        }

        ModuleRunFileLogger.i(TAG, "🏠 找到 " + roomNames.size() + " 个房间卡片");
        processRoomByIndex(roomNames, 0, rankTab, onAllDone);
    }

    private void processRoomByIndex(final List<String> roomNames, final int index,
                                     final RankTab rankTab, final Runnable onAllDone) {
        if (shouldStop() || index >= roomNames.size()) {
            tryScrollForMoreRooms(rankTab, 0, onAllDone);
            return;
        }

        final String roomName = roomNames.get(index);
        if (TextUtils.isEmpty(roomName) || visitedRoomNames.contains(roomName)) {
            processRoomByIndex(roomNames, index + 1, rankTab, onAllDone);
            return;
        }

        visitedRoomNames.add(roomName);
        bridge.updateStatus("进入房间: " + roomName);
        ModuleRunFileLogger.i(TAG, "🚪 点击进入房间: " + roomName);

        // 通过房间名 resource-id 的第 index 个节点点击
        boolean clicked = a11y.clickNthNodeByResourceId(
                UiComponentConfig.ROOM_CARD_NAME_ID, index);
        ModuleRunFileLogger.i(TAG, "🚪 点击房间卡片结果: " + clicked);
        totalRoomsEntered++;

        // 延迟 3 秒后验证：房间代号文本非空
        delayThenVerify("验证已进入房间[" + roomName + "]", 3000L, new Condition() {
            @Override
            public boolean check() {
                String code = a11y.getTextByResourceId(UiComponentConfig.LIVE_ROOM_CODE_ID);
                return !TextUtils.isEmpty(code);
            }
        }, UiComponentConfig.VERIFY_MAX_RETRIES, 3, new Runnable() {
            @Override
            public void run() {
                collectInRoom(new Runnable() {
                    @Override
                    public void run() {
                        backToRankList(rankTab, onAllDone);
                    }
                });
            }
        }, new Runnable() {
            @Override
            public void run() {
                ModuleRunFileLogger.w(TAG, "⚠️ 未能进入房间: " + roomName + "，跳过");
                a11y.performGlobalBack();
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        // 重新获取房间列表
                        List<String> freshRooms = a11y.getTextListByResourceId(
                                UiComponentConfig.ROOM_CARD_NAME_ID);
                        processRoomByIndex(freshRooms, 0, rankTab, onAllDone);
                    }
                }, UiComponentConfig.BACK_WAIT_MS);
            }
        });
    }

    /** 从房间返回榜单列表 */
    private void backToRankList(final RankTab rankTab, final Runnable onAllDone) {
        bridge.updateStatus("返回房间列表");
        ModuleRunFileLogger.i(TAG, "⬅️ 返回房间列表");
        a11y.performGlobalBack();

        delayThenVerify("验证已返回榜单列表", 2000L, new Condition() {
            @Override
            public boolean check() {
                // 榜单 layout 可见 且 房间代号不可见
                boolean hasLayout = a11y.isNodePresent(UiComponentConfig.RANK_TAB_LAYOUT_ID);
                String code = a11y.getTextByResourceId(UiComponentConfig.LIVE_ROOM_CODE_ID);
                return hasLayout && TextUtils.isEmpty(code);
            }
        }, UiComponentConfig.VERIFY_MAX_RETRIES, 3, new Runnable() {
            @Override
            public void run() {
                List<String> freshRooms = a11y.getTextListByResourceId(
                        UiComponentConfig.ROOM_CARD_NAME_ID);
                processRoomByIndex(freshRooms, 0, rankTab, onAllDone);
            }
        }, new Runnable() {
            @Override
            public void run() {
                ModuleRunFileLogger.w(TAG, "⚠️ 未能确认返回榜单，尝试继续");
                List<String> freshRooms = a11y.getTextListByResourceId(
                        UiComponentConfig.ROOM_CARD_NAME_ID);
                if (freshRooms != null && !freshRooms.isEmpty()) {
                    processRoomByIndex(freshRooms, 0, rankTab, onAllDone);
                } else {
                    onAllDone.run();
                }
            }
        });
    }

    private void tryScrollForMoreRooms(final RankTab rankTab, final int prevRetry,
                                        final Runnable onAllDone) {
        if (shouldStop()) { onAllDone.run(); return; }

        final int CONFIRM_RETRIES = 2;
        if (prevRetry >= CONFIRM_RETRIES) {
            ModuleRunFileLogger.i(TAG, "📜 房间列表已到底（连续 " + prevRetry
                    + " 次滑动无变化），完成当前榜单");
            onAllDone.run();
            return;
        }

        // 记录滑动前的房间名列表
        final List<String> beforeNames = a11y.getTextListByResourceId(
                UiComponentConfig.ROOM_CARD_NAME_ID);

        bridge.updateStatus("滚动加载更多房间");
        ModuleRunFileLogger.i(TAG, "📜 滚动加载更多房间" + (prevRetry > 0
                ? " (确认到底 " + prevRetry + "/" + CONFIRM_RETRIES + ")" : ""));
        a11y.performGestureSwipeDown();

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                List<String> names = a11y.getTextListByResourceId(
                        UiComponentConfig.ROOM_CARD_NAME_ID);
                // 检查是否有新的未访问房间
                boolean hasNew = false;
                if (names != null) {
                    for (String name : names) {
                        if (!TextUtils.isEmpty(name) && !visitedRoomNames.contains(name)) {
                            hasNew = true;
                            break;
                        }
                    }
                }
                if (hasNew) {
                    ModuleRunFileLogger.i(TAG, "📜 找到新房间，继续采集");
                    processRoomByIndex(names, 0, rankTab, onAllDone);
                } else {
                    // 没有新房间，比较滑动前后列表是否变化
                    boolean listChanged = !nicksEqual(beforeNames, names);
                    if (listChanged) {
                        ModuleRunFileLogger.i(TAG, "📜 房间列表有变化但无新房间，继续滑动");
                        tryScrollForMoreRooms(rankTab, 0, onAllDone);
                    } else {
                        ModuleRunFileLogger.i(TAG, "📜 房间列表无变化，确认到底 ("
                                + (prevRetry + 1) + "/" + CONFIRM_RETRIES + ")");
                        tryScrollForMoreRooms(rankTab, prevRetry + 1, onAllDone);
                    }
                }
            }
        }, UiComponentConfig.SCROLL_WAIT_MS);
    }

    // ═══════════════════════ Step 4-7: 房间内采集 ═══════════════════════

    /**
     * 房间内采集流程：
     * 1. 获取房间名/ID
     * 2. 点击在线榜 → 验证打开
     * 3. 读取用户列表，逐个采集
     * 4. 每次采集完返回房间后，重新打开在线榜继续
     * 5. 在线榜滚动加载更多用户，直到无新用户
     */
    private void collectInRoom(final Runnable onDone) {
        if (shouldStop()) { onDone.run(); return; }

        final String roomName = a11y.getTextByResourceId(UiComponentConfig.LIVE_ROOM_NAME_ID);
        final String roomId = a11y.getTextByResourceId(UiComponentConfig.LIVE_ROOM_CODE_ID);
        ModuleRunFileLogger.i(TAG, "🏠 房间内: name=" + roomName + " id=" + roomId);

        // 开始在线榜采集循环
        openOnlineListAndCollect(roomName, roomId, 0, onDone);
    }

    /**
     * 打开在线榜，然后开始采集循环。
     * scrollRetry: 当前滚动重试次数（无新用户时递增）
     */
    private void openOnlineListAndCollect(final String roomName, final String roomId,
                                           final int scrollRetry, final Runnable onDone) {
        if (shouldStop()) { onDone.run(); return; }

        // 检查在线榜入口
        boolean hasOnline = a11y.isNodePresent(UiComponentConfig.ONLINE_USER_LAYOUT_ID);
        if (!hasOnline) {
            ModuleRunFileLogger.w(TAG, "⚠️ 未找到在线榜入口，跳过此房间");
            onDone.run();
            return;
        }

        bridge.updateStatus("打开在线列表");
        ModuleRunFileLogger.i(TAG, "👥 点击在线榜");
        boolean clicked = a11y.clickNodeByResourceId(UiComponentConfig.ONLINE_USER_LAYOUT_ID);
        ModuleRunFileLogger.i(TAG, "👥 在线榜点击结果: " + clicked);

        delayThenVerify("验证在线榜已打开", 2000L, new Condition() {
            @Override
            public boolean check() {
                return a11y.isNodePresent(UiComponentConfig.USER_NICKNAME_ID);
            }
        }, UiComponentConfig.VERIFY_MAX_RETRIES, 0, new Runnable() {
            @Override
            public void run() {
                // 在线榜已打开，开始逐个采集用户
                collectNextUser(roomName, roomId, scrollRetry, onDone);
            }
        }, new Runnable() {
            @Override
            public void run() {
                ModuleRunFileLogger.w(TAG, "⚠️ 在线榜未能打开，跳过此房间");
                onDone.run();
            }
        });
    }

    /**
     * 在线榜中寻找下一个未采集用户，点击采集。
     * 采集完返回房间后会重新调用 openOnlineListAndCollect 继续。
     */
    private void collectNextUser(final String roomName, final String roomId,
                                  final int scrollRetry, final Runnable onDone) {
        if (shouldStop()) {
            exitOnlineList(onDone);
            return;
        }

        // 结构化读取在线榜的用户卡片（从同一个 ViewGroup 中同时提取 nickname/userCode/isAdmin）
        List<ShuangyuAccessibilityAdService.OnlineUserCard> userCards = a11y.getOnlineUserCards();

        if (userCards == null || userCards.isEmpty()) {
            ModuleRunFileLogger.i(TAG, "👥 在线榜无用户卡片");
            exitOnlineList(onDone);
            return;
        }

        int count = userCards.size();
        ModuleRunFileLogger.i(TAG, "👥 当前可见 " + count + " 个用户卡片");

        // 加载过滤设置
        SharedPreferences prefs = ModuleSettings.appPrefs(a11y);
        Set<String> ignoreIds = ModuleSettings.parseIgnoreUserIdSet(
                ModuleSettings.getIgnoreUserIds(prefs));
        int minWealth = ModuleSettings.getMinWealthLevel(prefs);
        int minCharm = ModuleSettings.getMinCharmLevel(prefs);

        // 查找第一个未采集、未过滤的用户
        ShuangyuAccessibilityAdService.OnlineUserCard targetCard = null;

        for (int i = 0; i < count; i++) {
            ShuangyuAccessibilityAdService.OnlineUserCard card = userCards.get(i);
            String nick = card.nickname;
            String uid = card.userCode;  // 原始格式如 "ID:22222877"
            String cleanUid = cleanUserId(uid);

            // 生成唯一标识（优先用 cleanUid，没有的话用昵称）
            String trackingKey = !TextUtils.isEmpty(cleanUid) ? cleanUid : nick;

            // 跳过已采集
            if (collectedUserIds.contains(trackingKey)) continue;

            // 跳过管理员/房主（卡片内检测到 iv_room_role）
            if (card.isAdmin) {
                ModuleRunFileLogger.i(TAG, "⏭️ 跳过管理员/房主: " + nick + " (" + uid + ")");
                collectedUserIds.add(trackingKey);
                continue;
            }

            // 跳过忽略用户（使用清理后的 ID 比较）
            if (!TextUtils.isEmpty(cleanUid) && ignoreIds.contains(cleanUid)) {
                ModuleRunFileLogger.i(TAG, "⏭️ 跳过忽略用户: " + nick + " (" + uid + ")");
                collectedUserIds.add(trackingKey);
                continue;
            }

            // 跳过空昵称或特殊用户
            if (TextUtils.isEmpty(nick) || "神秘人".equals(nick)) continue;

            // 跳过低于等级要求的用户
            if (minWealth > 0 || minCharm > 0) {
                LevelDataBridge.LevelInfo lvl =
                        LevelDataBridge.readLevelEntry(a11y, uid);
                if (lvl != null) {
                    if (minWealth > 0 && lvl.wealthLevel >= 0
                            && lvl.wealthLevel < minWealth) {
                        ModuleRunFileLogger.i(TAG, "⏭️ 跳过低财富等级: " + nick
                                + " (财富=" + lvl.wealthLevel + " < " + minWealth + ")");
                        collectedUserIds.add(trackingKey);
                        continue;
                    }
                    if (minCharm > 0 && lvl.charmLevel >= 0
                            && lvl.charmLevel < minCharm) {
                        ModuleRunFileLogger.i(TAG, "⏭️ 跳过低魅力等级: " + nick
                                + " (魅力=" + lvl.charmLevel + " < " + minCharm + ")");
                        collectedUserIds.add(trackingKey);
                        continue;
                    }
                }
                // lvl == null 时不过滤（等级数据可能还没被 Hook 采集到）
            }

            targetCard = card;
            break;
        }

        if (targetCard == null) {
            // 当前可见用户全部已采集/过滤，尝试滚动加载更多
            ModuleRunFileLogger.i(TAG, "📜 当前用户全部已处理，尝试滚动加载更多 (重试 "
                    + scrollRetry + "/" + UiComponentConfig.NO_NEW_CONTENT_MAX_RETRIES + ")");
            tryScrollForMoreUsers(roomName, roomId, scrollRetry, onDone);
            return;
        }

        // 找到目标用户，开始采集
        final String nick = targetCard.nickname;
        final String uid = targetCard.userCode;
        final String finalTrackingKey = !TextUtils.isEmpty(cleanUserId(uid))
                ? cleanUserId(uid) : nick;

        // 通过 tv_nickname 定位用户在当前列表中的位置并点击
        // （使用 tv_nickname 列表做匹配，因为它是稳定可见的）
        List<String> currentNicks = a11y.getTextListByResourceId(UiComponentConfig.USER_NICKNAME_ID);
        int clickIndex = -1;
        if (currentNicks != null) {
            for (int i = 0; i < currentNicks.size(); i++) {
                if (nick.equals(currentNicks.get(i))) {
                    clickIndex = i;
                    break;
                }
            }
        }
        if (clickIndex < 0) {
            ModuleRunFileLogger.w(TAG, "⚠️ 无法定位目标用户: " + nick + "，跳过");
            collectedUserIds.add(finalTrackingKey);
            collectNextUser(roomName, roomId, scrollRetry, onDone);
            return;
        }
        final int idx = clickIndex;

        bridge.updateStatus("采集用户: " + nick);
        ModuleRunFileLogger.i(TAG, "👤 点击用户: " + nick + " (" + uid + ")");

        boolean userClicked = a11y.clickNthNodeByResourceId(
                UiComponentConfig.USER_NICKNAME_ID, idx);
        ModuleRunFileLogger.i(TAG, "👤 用户点击结果: " + userClicked);

        delayThenVerify("验证用户详情[" + nick + "]", 2000L, new Condition() {
            @Override
            public boolean check() {
                return a11y.isNodePresent(UiComponentConfig.USER_DETAIL_LOCATION_ID)
                        || a11y.isNodePresent(UiComponentConfig.USER_DETAIL_AGE_ID);
            }
        }, UiComponentConfig.VERIFY_MAX_RETRIES, 0, new Runnable() {
            @Override
            public void run() {
                // 读取用户详情
                String location = a11y.getTextByResourceId(
                        UiComponentConfig.USER_DETAIL_LOCATION_ID);
                String age = a11y.getTextByResourceId(
                        UiComponentConfig.USER_DETAIL_AGE_ID);

                ModuleRunFileLogger.i(TAG, "📝 采集: " + nick
                        + " 地区=" + location + " 年龄=" + age);

                // 从跨进程共享文件读取等级数据
                String gender = "";
                String wealthLevelStr = "";
                String charmLevelStr = "";
                String detailCleanUid = cleanUserId(uid);
                LevelDataBridge.LevelInfo levelEntry =
                        LevelDataBridge.readLevelEntry(a11y, detailCleanUid);
                if (levelEntry != null) {
                    String rawGender = levelEntry.gender != null ? levelEntry.gender : "";
                    if (rawGender.equals("male") || rawGender.contains("boy")
                            || rawGender.equals("resId:1")) {
                        gender = "男";
                    } else if (rawGender.equals("female") || rawGender.contains("girl")
                            || rawGender.equals("resId:2")) {
                        gender = "女";
                    }
                    wealthLevelStr = levelEntry.wealthLevel >= 0
                            ? String.valueOf(levelEntry.wealthLevel) : "";
                    charmLevelStr = levelEntry.charmLevel >= 0
                            ? String.valueOf(levelEntry.charmLevel) : "";
                    ModuleRunFileLogger.i(TAG, "📊 等级数据: gender=" + gender
                            + " wealth=" + wealthLevelStr
                            + " charm=" + charmLevelStr);
                } else {
                    ModuleRunFileLogger.w(TAG, "⚠️ 未找到用户等级数据: " + detailCleanUid);
                }

                saveUserData(detailCleanUid, nick, gender, age,
                        wealthLevelStr, charmLevelStr, "",
                        location, roomName, roomId);
                collectedUserIds.add(finalTrackingKey);

                // 返回房间（关闭用户详情）
                a11y.performGlobalBack();
                ModuleRunFileLogger.i(TAG, "⬅️ 关闭用户详情，返回房间");

                // 验证已返回房间（可见在线榜入口 = 在房间内）
                delayThenVerify("验证已返回房间", 1500L, new Condition() {
                    @Override
                    public boolean check() {
                        return a11y.isNodePresent(UiComponentConfig.ONLINE_USER_LAYOUT_ID);
                    }
                }, UiComponentConfig.VERIFY_MAX_RETRIES, 3, new Runnable() {
                    @Override
                    public void run() {
                        // 已回到房间，重新打开在线榜继续采集下一个
                        openOnlineListAndCollect(roomName, roomId, 0, onDone);
                    }
                }, new Runnable() {
                    @Override
                    public void run() {
                        // 返回失败，可能在线榜还开着，直接继续
                        ModuleRunFileLogger.w(TAG, "⚠️ 返回房间验证失败，尝试继续");
                        // 检查是否还在线榜界面
                        if (a11y.isNodePresent(UiComponentConfig.USER_NICKNAME_ID)) {
                            collectNextUser(roomName, roomId, 0, onDone);
                        } else {
                            openOnlineListAndCollect(roomName, roomId, 0, onDone);
                        }
                    }
                });
            }
        }, new Runnable() {
            @Override
            public void run() {
                ModuleRunFileLogger.w(TAG, "⚠️ 用户详情未打开，跳过: " + nick);
                // 标记为已访问避免死循环
                collectedUserIds.add(finalTrackingKey);
                a11y.performGlobalBack();
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        // 检查当前在哪里，决定下一步
                        if (a11y.isNodePresent(UiComponentConfig.USER_NICKNAME_ID)) {
                            // 还在在线榜
                            collectNextUser(roomName, roomId, 0, onDone);
                        } else {
                            // 回到了房间
                            openOnlineListAndCollect(roomName, roomId, 0, onDone);
                        }
                    }
                }, UiComponentConfig.BACK_WAIT_MS);
            }
        });
    }

    /** 退出在线榜回到房间 */
    private void exitOnlineList(final Runnable onDone) {
        ModuleRunFileLogger.i(TAG, "👥 退出在线榜");
        a11y.performGlobalBack();
        delayThenVerify("验证已退出在线榜", 2000L, new Condition() {
            @Override
            public boolean check() {
                return a11y.isNodePresent(UiComponentConfig.ONLINE_USER_LAYOUT_ID);
            }
        }, UiComponentConfig.VERIFY_MAX_RETRIES, 3, onDone, onDone);
    }

    /**
     * 在线榜中滚动加载更多用户。
     */
    private void tryScrollForMoreUsers(final String roomName, final String roomId,
                                        final int prevRetry, final Runnable onDone) {
        if (shouldStop()) {
            exitOnlineList(onDone);
            return;
        }

        // prevRetry 表示连续"滑动后可见用户完全没变化"的次数
        // 达到 3 次确认后才认为真正到底
        final int CONFIRM_RETRIES = 2;
        if (prevRetry >= CONFIRM_RETRIES) {
            ModuleRunFileLogger.i(TAG, "📜 用户列表已到底（连续 " + prevRetry
                    + " 次滑动无变化），完成当前房间");
            exitOnlineList(onDone);
            return;
        }

        // 记录滑动前的可见用户昵称列表
        final List<String> beforeNicks = a11y.getTextListByResourceId(
                UiComponentConfig.USER_NICKNAME_ID);

        ModuleRunFileLogger.i(TAG, "📜 滑动加载更多用户" + (prevRetry > 0
                ? " (确认到底 " + prevRetry + "/" + CONFIRM_RETRIES + ")" : ""));
        a11y.performSmallScrollDown();

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                // 检查滑动后的可见用户
                List<ShuangyuAccessibilityAdService.OnlineUserCard> cards = a11y.getOnlineUserCards();

                // 检查是否有新的可采集用户（非管理员且未采集）
                boolean hasNew = false;
                if (cards != null) {
                    for (ShuangyuAccessibilityAdService.OnlineUserCard card : cards) {
                        if (card.isAdmin) continue;
                        if ("神秘人".equals(card.nickname)) continue;
                        String trackingKey = !TextUtils.isEmpty(cleanUserId(card.userCode))
                                ? cleanUserId(card.userCode) : card.nickname;
                        if (!collectedUserIds.contains(trackingKey)) {
                            hasNew = true;
                            break;
                        }
                    }
                }

                if (hasNew) {
                    // 发现新用户，继续采集
                    ModuleRunFileLogger.i(TAG, "📜 发现新用户，继续采集");
                    collectNextUser(roomName, roomId, 0, onDone);
                } else {
                    // 没有新用户，检查可见用户列表是否变化
                    List<String> afterNicks = a11y.getTextListByResourceId(
                            UiComponentConfig.USER_NICKNAME_ID);
                    boolean listChanged = !nicksEqual(beforeNicks, afterNicks);

                    if (listChanged) {
                        // 列表有变化（可能滑过了一批管理员），继续滑动
                        ModuleRunFileLogger.i(TAG, "📜 列表有变化但无新可采集用户，继续滑动");
                        collectNextUser(roomName, roomId, 0, onDone);
                    } else {
                        // 列表完全没变化，进入确认到底阶段
                        ModuleRunFileLogger.i(TAG, "📜 列表无变化，确认到底 ("
                                + (prevRetry + 1) + "/" + CONFIRM_RETRIES + ")");
                        collectNextUser(roomName, roomId, prevRetry + 1, onDone);
                    }
                }
            }
        }, UiComponentConfig.SCROLL_WAIT_MS);
    }

    /**
     * 比较两个昵称列表是否相同（用于判断列表是否滑动了）。
     */
    private static boolean nicksEqual(List<String> a, List<String> b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!TextUtils.equals(a.get(i), b.get(i))) return false;
        }
        return true;
    }

    // ═══════════════════════ 保存数据 ═══════════════════════

    private void saveUserData(String userId, String userName, String gender,
                               String age, String wealthLevel, String charmLevel,
                               String followers, String location,
                               String roomName, String roomId) {
        long now = System.currentTimeMillis();
        LiveRoomRankCsvStore.UserDataRow row = new LiveRoomRankCsvStore.UserDataRow(
                userId, userName, gender, age, wealthLevel, charmLevel,
                followers, location, roomName, roomId, now
        );
        boolean success = LiveRoomRankCsvStore.appendRow(a11y, row);
        if (success) {
            totalCollected++;
            if (!TextUtils.isEmpty(userId)) {
                collectedUserIds.add(userId);
            }
            ModuleRunFileLogger.i(TAG, "💾 已采集 #" + totalCollected + ": "
                    + userName + " (ID:" + userId + ") 房间:" + roomName);
            sendToFeishu(userId, userName, gender, age,
                    wealthLevel, charmLevel, followers,
                    location, roomName, roomId);
        } else {
            ModuleRunFileLogger.e(TAG, "❌ CSV 保存失败: " + userName);
        }
    }

    private void sendToFeishu(String userId, String userName, String gender,
                               String age, String wealthLevel, String charmLevel,
                               String followers, String location,
                               String roomName, String roomId) {
        // 关键字段校验：缺少用户 ID、财富等级、魅力等级时不发送
        if (TextUtils.isEmpty(userId) || TextUtils.isEmpty(wealthLevel)
                || TextUtils.isEmpty(charmLevel)) {
            ModuleRunFileLogger.w(TAG, "⚠️ 飞书跳过: 关键数据缺失 (ID="
                    + safeTrim(userId) + " 财富=" + safeTrim(wealthLevel)
                    + " 魅力=" + safeTrim(charmLevel) + ") 用户: " + userName);
            return;
        }
        SharedPreferences prefs = ModuleSettings.appPrefs(a11y);
        if (!ModuleSettings.getFeishuPushEnabled(prefs)) return;
        final String webhookUrl = ModuleSettings.getFeishuWebhookUrl(prefs);
        final String signSecret = ModuleSettings.getFeishuSignSecret(prefs);
        if (TextUtils.isEmpty(webhookUrl)) return;

        java.text.SimpleDateFormat sdf =
                new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US);
        String timeStr = sdf.format(new java.util.Date());

        final String text = "时间: " + timeStr + "\n"
                + "用户ID: " + safeTrim(userId) + "\n"
                + "用户昵称: " + safeTrim(userName) + "\n"
                + "性别: " + safeTrim(gender) + "\n"
                + "年龄: " + safeTrim(age) + "\n"
                + "财富等级: " + safeTrim(wealthLevel) + "\n"
                + "魅力等级: " + safeTrim(charmLevel) + "\n"
                + "粉丝数: " + safeTrim(followers) + "\n"
                + "地区: " + safeTrim(location) + "\n"
                + "所在房间: " + safeTrim(roomName) + "\n"
                + "所在房间ID: " + safeTrim(roomId);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final int MAX_RETRIES = 3;
                for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
                    try {
                        FeishuWebhookSender.SendResult result =
                                FeishuWebhookSender.doSendText(webhookUrl, signSecret, text);
                        if (result.success) {
                            return; // 发送成功
                        }
                        ModuleRunFileLogger.w(TAG, "⚠️ 飞书发送失败 (第" + attempt + "次): "
                                + result.detail);
                    } catch (Throwable e) {
                        ModuleRunFileLogger.w(TAG, "⚠️ 飞书发送异常 (第" + attempt + "次): "
                                + e.getMessage());
                    }
                    if (attempt < MAX_RETRIES) {
                        try {
                            // 指数退避：3s, 6s, 12s
                            Thread.sleep(3000L * (1L << (attempt - 1)));
                        } catch (InterruptedException ignored) {}
                    }
                }
                ModuleRunFileLogger.e(TAG, "❌ 飞书发送最终失败（已重试" + MAX_RETRIES + "次）: "
                        + userName);
            }
        }).start();
    }

    // ═══════════════════════ 工具方法 ═══════════════════════

    private boolean shouldStop() {
        return bridge != null && bridge.shouldStop();
    }

    private static String safeTrim(String value) {
        if (value == null) return "";
        return value.trim();
    }

    /** 去掉 "ID:" 前缀，返回纯用户 ID。例如 "ID:23084913" → "23084913" */
    private static String cleanUserId(String uid) {
        if (uid == null) return "";
        String cleaned = uid.trim();
        if (cleaned.startsWith("ID:")) cleaned = cleaned.substring(3).trim();
        return cleaned;
    }
}
