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
            tryScrollForMoreRooms(rankTab, onAllDone);
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

    private void tryScrollForMoreRooms(final RankTab rankTab, final Runnable onAllDone) {
        if (shouldStop()) { onAllDone.run(); return; }
        bridge.updateStatus("滚动加载更多房间");
        ModuleRunFileLogger.i(TAG, "📜 滚动加载更多房间");
        a11y.performScrollDownByResourceId(UiComponentConfig.ROOM_LIST_RECYCLER_ID);

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                List<String> names = a11y.getTextListByResourceId(
                        UiComponentConfig.ROOM_CARD_NAME_ID);
                List<String> unvisited = new ArrayList<>();
                if (names != null) {
                    for (String name : names) {
                        if (!TextUtils.isEmpty(name) && !visitedRoomNames.contains(name)) {
                            unvisited.add(name);
                        }
                    }
                }
                if (unvisited.isEmpty()) {
                    ModuleRunFileLogger.i(TAG, "📜 没有新的未访问房间，完成当前榜单");
                    onAllDone.run();
                } else {
                    ModuleRunFileLogger.i(TAG, "📜 找到 " + unvisited.size() + " 个新房间");
                    processRoomByIndex(names, 0, rankTab, onAllDone);
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

        // 每次都重新读取最新的用户列表
        List<String> nicknames = a11y.getTextListByResourceId(UiComponentConfig.USER_NICKNAME_ID);
        List<String> userIds = a11y.getTextListByResourceId(UiComponentConfig.USER_CODE_ID);

        if (nicknames == null || nicknames.isEmpty()) {
            ModuleRunFileLogger.i(TAG, "👥 在线榜无用户");
            exitOnlineList(onDone);
            return;
        }

        int count = nicknames.size();
        ModuleRunFileLogger.i(TAG, "👥 当前可见 " + count + " 个用户");

        // 加载忽略列表
        SharedPreferences prefs = ModuleSettings.appPrefs(a11y);
        Set<String> ignoreIds = ModuleSettings.parseIgnoreUserIdSet(
                ModuleSettings.getIgnoreUserIds(prefs));

        // 查找第一个未采集的用户
        int targetIndex = -1;
        String targetNickname = null;
        String targetUserId = null;

        for (int i = 0; i < count; i++) {
            String nick = nicknames.get(i);
            String uid = (userIds != null && i < userIds.size()) ? userIds.get(i) : "";

            // 跳过已采集
            if (!TextUtils.isEmpty(uid) && collectedUserIds.contains(uid)) continue;
            // 跳过忽略用户
            if (!TextUtils.isEmpty(uid) && ignoreIds.contains(uid)) {
                ModuleRunFileLogger.i(TAG, "⏭️ 跳过忽略用户: " + nick + " (" + uid + ")");
                continue;
            }
            // 跳过管理员/房主
            if (a11y.isAdminUserAtIndex(i)) {
                ModuleRunFileLogger.i(TAG, "⏭️ 跳过管理员/房主: " + nick);
                if (!TextUtils.isEmpty(uid)) collectedUserIds.add(uid);
                continue;
            }
            // 跳过空昵称
            if (TextUtils.isEmpty(nick)) continue;

            targetIndex = i;
            targetNickname = nick;
            targetUserId = uid;
            break;
        }

        if (targetIndex < 0) {
            // 当前可见用户全部已采集，尝试滚动加载更多
            ModuleRunFileLogger.i(TAG, "📜 当前用户全部已处理，尝试滚动加载更多 (重试 "
                    + scrollRetry + "/" + UiComponentConfig.NO_NEW_CONTENT_MAX_RETRIES + ")");
            tryScrollForMoreUsers(roomName, roomId, scrollRetry, onDone);
            return;
        }

        // 找到目标用户，开始采集
        final int idx = targetIndex;
        final String nick = targetNickname;
        final String uid = targetUserId;

        bridge.updateStatus("采集用户: " + nick);
        ModuleRunFileLogger.i(TAG, "👤 点击用户: " + nick + " (ID:" + uid + ")");

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

                saveUserData(uid, nick, "", age, "", "", "",
                        location, roomName, roomId);

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
                if (!TextUtils.isEmpty(uid)) collectedUserIds.add(uid);
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

        if (prevRetry >= UiComponentConfig.NO_NEW_CONTENT_MAX_RETRIES) {
            ModuleRunFileLogger.i(TAG, "📜 用户列表无新内容（已重试 " + prevRetry + " 次），完成当前房间");
            exitOnlineList(onDone);
            return;
        }

        ModuleRunFileLogger.i(TAG, "📜 滑动加载更多用户 (第 " + (prevRetry + 1) + " 次)");
        a11y.performSmallScrollDown();

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                // 检查是否有新的未采集用户
                List<String> userIds = a11y.getTextListByResourceId(
                        UiComponentConfig.USER_CODE_ID);
                boolean hasNew = false;
                if (userIds != null) {
                    for (String uid : userIds) {
                        if (!TextUtils.isEmpty(uid) && !collectedUserIds.contains(uid)) {
                            hasNew = true;
                            break;
                        }
                    }
                }

                if (hasNew) {
                    ModuleRunFileLogger.i(TAG, "📜 发现新用户，继续采集");
                    collectNextUser(roomName, roomId, 0, onDone);
                } else {
                    // 没有新用户，递增重试
                    collectNextUser(roomName, roomId, prevRetry + 1, onDone);
                }
            }
        }, UiComponentConfig.SCROLL_WAIT_MS);
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
            sendToFeishu(userId, userName, age, location, roomName, roomId);
        } else {
            ModuleRunFileLogger.e(TAG, "❌ CSV 保存失败: " + userName);
        }
    }

    private void sendToFeishu(String userId, String userName, String age,
                               String location, String roomName, String roomId) {
        SharedPreferences prefs = ModuleSettings.appPrefs(a11y);
        if (!ModuleSettings.getFeishuPushEnabled(prefs)) return;
        final String webhookUrl = ModuleSettings.getFeishuWebhookUrl(prefs);
        final String signSecret = ModuleSettings.getFeishuSignSecret(prefs);
        if (TextUtils.isEmpty(webhookUrl)) return;

        final String text = "昵称: " + safeTrim(userName) + "\n"
                + "ID: " + safeTrim(userId) + "\n"
                + "年龄: " + safeTrim(age) + "\n"
                + "地区: " + safeTrim(location) + "\n"
                + "房间: " + safeTrim(roomName) + " (" + safeTrim(roomId) + ")";
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    FeishuWebhookSender.doSendText(webhookUrl, signSecret, text);
                } catch (Throwable e) {
                    ModuleRunFileLogger.e(TAG, "飞书发送失败: " + e.getMessage());
                }
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
}
