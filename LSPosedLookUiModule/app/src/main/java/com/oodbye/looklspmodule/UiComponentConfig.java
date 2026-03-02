package com.oodbye.looklspmodule;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 所有 UI 组件参数只允许在本文件中维护。
 */
final class UiComponentConfig {
    static final String TARGET_PACKAGE = "com.netease.play";
    static final String CUSTOM_RULES_ASSET_NAME = "CustomRules.ini";
    static final String CUSTOM_RULES_EXTERNAL_PATH = "/sdcard/Android/data/com.oodbye.looklspmodule/files/CustomRules.ini";
    static final String LIVE_ROOM_ACTIVITY_KEYWORD = "LiveViewerActivity";
    static final String HOME_ACTIVITY_KEYWORD = "HomeActivity";
    static final String LIVE_ROOM_ENTER_TASK_NAME = "live_room_enter_task";
    static final String RUN_LOG_EXTERNAL_DIR = "/sdcard/Android/data/com.oodbye.looklspmodule/files";
    static final String RUN_LOG_FALLBACK_DIR_NAME = "look_lsp_logs";
    static final String RUN_LOG_CURRENT_FILE_NAME = "look_lsp_run_current.log";
    static final String RUN_LOG_PREVIOUS_FILE_NAME = "look_lsp_run_previous.log";
    static final String MANUAL_VIEW_TREE_EXPORT_DIR = "/sdcard/Android/data/com.oodbye.looklspmodule/files/view_tree_dumps";
    static final String MANUAL_VIEW_TREE_EXPORT_FALLBACK_DIR_NAME = "look_view_tree_dumps";
    static final String MANUAL_VIEW_TREE_EXPORT_FILE_PREFIX = "look_activity_view_tree";
    static final int MANUAL_VIEW_TREE_EXPORT_MAX_NODES = 1200;
    static final int MANUAL_VIEW_TREE_EXPORT_MAX_DEPTH = 28;
    static final int MANUAL_VIEW_TREE_EXPORT_MAX_TEXT_LENGTH = 180;

    static final long MAIN_LOOP_INTERVAL_MS = 500L;
    static final long AD_SCAN_INTERVAL_MS = 120L;
    static final long AD_REALTIME_LOOP_INTERVAL_MS = 120L;
    static final long HOME_LOG_INTERVAL_MS = 3000L;
    static final long FLOW_LOG_INTERVAL_MS = 2500L;
    static final long ENTER_TOGETHER_TIMEOUT_MS = 5000L;
    static final long RETRY_ENTER_TOGETHER_MS = 1200L;
    static final long STARTUP_WAIT_BEFORE_CLICK_TOGETHER_MS = 3500L;
    static final long TOGETHER_REFRESH_WAIT_BEFORE_PULL_MS = 1200L;
    static final long TOGETHER_REFRESH_SETTLE_MS = 1800L;
    static final long TOGETHER_REFRESH_RETRY_INTERVAL_MS = 900L;
    static final long TOGETHER_SCROLL_RETRY_INTERVAL_MS = 1100L;
    static final long TOGETHER_SCROLL_SETTLE_MS = 900L;
    static final int TOGETHER_CYCLE_COMPLETE_EMPTY_STREAK = 4;
    static final int TOGETHER_CYCLE_COMPLETE_SAME_SIGNATURE_STREAK = 2;
    static final long TOGETHER_CYCLE_RESTART_INTERVAL_MS = 10000L;
    static final long LIVE_ROOM_ENTER_WAIT_MS = 700L;
    static final long LIVE_ROOM_VERIFY_TIMEOUT_MS = 9000L;
    static final int LIVE_ROOM_VERIFY_MAX_RETRY_COUNT = 18;
    static final long CARD_CLICK_COOLDOWN_MS = 1000L;
    static final long LIVE_ROOM_RETURN_TOGETHER_WAIT_MS = 3000L;
    static final long LIVE_ROOM_ENTER_TASK_WAIT_MS = 5000L;
    static final long LIVE_ROOM_TASK_VFLIPPER_FIND_RETRY_INTERVAL_MS = 350L;
    static final long LIVE_ROOM_TASK_VFLIPPER_FIND_TIMEOUT_MS = 0L;
    static final long LIVE_ROOM_TASK_WAIT_AFTER_VFLIPPER_CLICK_MS = 2000L;
    static final long LIVE_ROOM_TASK_PANEL_READY_RETRY_INTERVAL_MS = 350L;
    static final int LIVE_ROOM_TASK_PANEL_READY_MAX_RETRY = 24;
    static final long LIVE_ROOM_TASK_PANEL_A11Y_SNAPSHOT_STALE_MS = 3500L;
    static final long LIVE_ROOM_TASK_A11Y_CLICK_RESULT_WAIT_MS = 1200L;
    static final long LIVE_ROOM_TASK_CONTRIBUTION_WAIT_MS = 5000L;
    static final long LIVE_ROOM_TASK_CHARM_WAIT_MS = 5000L;
    static final long LIVE_ROOM_TASK_PANEL_CLOSE_CHECK_DELAY_MS = 500L;
    static final long LIVE_ROOM_TASK_PANEL_CLOSE_RETRY_INTERVAL_MS = 600L;
    static final int LIVE_ROOM_TASK_PANEL_CLOSE_MAX_BACK_RETRY = 2;
    static final int LIVE_ROOM_TASK_PANEL_MARKER_MIN_MATCH_COUNT = 1;
    static final int LIVE_ROOM_TASK_PANEL_PRIMARY_MIN_MATCH_COUNT = 1;
    static final long LIVE_ROOM_TASK_MIN_RETURN_DELAY_MS = 13000L;
    static final long LIVE_ROOM_SCRIPT_BACK_DELAY_MS = 500L;
    static final long A11Y_PANEL_SNAPSHOT_MIN_UPDATE_INTERVAL_MS = 400L;
    static final long A11Y_PANEL_SNAPSHOT_MAX_WRITE_INTERVAL_MS = 1200L;
    static final long TOUCH_SWIPE_DURATION_MS = 280L;
    static final int TOUCH_SWIPE_MOVE_STEPS = 8;
    static final float TOGETHER_REFRESH_SWIPE_X_RATIO = 0.5f;
    static final float TOGETHER_REFRESH_SWIPE_START_Y_RATIO = 0.28f;
    static final float TOGETHER_REFRESH_SWIPE_END_Y_RATIO = 0.78f;
    static final float TOGETHER_SCROLL_SWIPE_X_RATIO = 0.5f;
    static final float TOGETHER_SCROLL_SWIPE_START_Y_RATIO = 0.60f;
    static final float TOGETHER_SCROLL_SWIPE_END_Y_RATIO = 0.32f;
    static final int TOGETHER_CARD_TITLE_INDEX_START = 1;
    static final int TOGETHER_CARD_TITLE_INDEX_END = 4;
    static final int TOGETHER_CARD_CLICK_TITLE_INDEX_START = 3;
    static final int TOGETHER_CARD_CLICK_TITLE_INDEX_END = 4;
    static final String TOGETHER_CARD_COVER_RESOURCE_ENTRY = "DC_Image";
    static final float TOGETHER_CARD_TAP_X_RATIO = 0.5f;
    static final float TOGETHER_CARD_TAP_Y_RATIO = 0.42f;
    static final float TOGETHER_CARD_TAP_ALT_Y_RATIO = 0.68f;
    static final float TOGETHER_CARD_COVER_TAP_X_RATIO = 0.5f;
    static final float TOGETHER_CARD_COVER_TAP_Y_RATIO = 0.56f;
    static final float TOGETHER_CARD_SAFE_BOTTOM_Y_RATIO = 0.90f;

    static final List<UiNodeSpec> LOOK_HOME_NODES = Collections.unmodifiableList(Arrays.asList(
            new UiNodeSpec("推荐", "com.netease.play:id/tv_dragon_tab", "android.widget.TextView", "com.netease.play", null),
            new UiNodeSpec("听听", "com.netease.play:id/tv_dragon_tab", "android.widget.TextView", "com.netease.play", null),
            new UiNodeSpec("一起聊", "com.netease.play:id/tv_dragon_tab", "android.widget.TextView", "com.netease.play", null),
            new UiNodeSpec("看看", "com.netease.play:id/tv_dragon_tab", "android.widget.TextView", "com.netease.play", null)
    ));

    static final UiNodeSpec TOGETHER_TAB_CLICK_NODE = new UiNodeSpec(
            "一起聊",
            "com.netease.play:id/tv_dragon_tab",
            "android.widget.TextView",
            "com.netease.play",
            null
    );

    // 一起聊界面识别参数（来自四份 UI 树对比结果）
    static final List<UiNodeSpec> TOGETHER_PAGE_NODES = Collections.unmodifiableList(Arrays.asList(
            new UiNodeSpec(
                    "一起聊",
                    "com.netease.play:id/tv_dragon_tab",
                    "android.widget.TextView",
                    "com.netease.play",
                    Boolean.TRUE
            ),
            new UiNodeSpec(
                    "",
                    "com.netease.play:id/rnView",
                    "android.widget.FrameLayout",
                    "com.netease.play",
                    null
            )
    ));

    static final UiNodeSpec TOGETHER_ROOM_LIST_CONTAINER_NODE = new UiNodeSpec(
            "",
            "com.netease.play:id/rnView",
            "android.widget.FrameLayout",
            "com.netease.play",
            null
    );

    static final List<UiNodeSpec> LOGIN_BLOCK_DIALOG_NODES = Collections.unmodifiableList(Arrays.asList(
            new UiNodeSpec(
                    "设置",
                    "com.netease.play:id/menu_login_with_setting",
                    "",
                    "com.netease.play",
                    null
            ),
            new UiNodeSpec(
                    "",
                    "com.netease.play:id/qq",
                    "",
                    "com.netease.play",
                    null
            ),
            new UiNodeSpec(
                    "",
                    "com.netease.play:id/weibo",
                    "",
                    "com.netease.play",
                    null
            ),
            new UiNodeSpec(
                    "",
                    "com.netease.play:id/phone",
                    "",
                    "com.netease.play",
                    null
            ),
            new UiNodeSpec(
                    "",
                    "com.netease.play:id/cloudmusic",
                    "",
                    "com.netease.play",
                    null
            )
    ));

    static final UiNodeSpec LIVE_ROOM_ROOM_NO_NODE = new UiNodeSpec(
            "",
            "com.netease.play:id/roomNo",
            "android.widget.TextView",
            "com.netease.play",
            null
    );

    static final UiNodeSpec LIVE_ROOM_TITLE_NODE = new UiNodeSpec(
            "",
            "com.netease.play:id/title",
            "android.widget.TextView",
            "com.netease.play",
            null
    );

    static final UiNodeSpec LIVE_ROOM_CLOSE_BUTTON_NODE = new UiNodeSpec(
            "",
            "com.netease.play:id/closeBtn",
            "android.widget.ImageView",
            "com.netease.play",
            null
    );

    static final List<UiNodeSpec> LIVE_ROOM_VERIFY_NODES = Collections.unmodifiableList(Arrays.asList(
            LIVE_ROOM_ROOM_NO_NODE,
            LIVE_ROOM_TITLE_NODE,
            LIVE_ROOM_CLOSE_BUTTON_NODE
    ));

    static final List<UiNodeSpec> LIVE_ROOM_VERIFY_RELAXED_NODES = Collections.unmodifiableList(Arrays.asList(
            LIVE_ROOM_TITLE_NODE,
            LIVE_ROOM_CLOSE_BUTTON_NODE
    ));

    static final UiNodeSpec LIVE_ROOM_TASK_VFLIPPER_NODE = new UiNodeSpec(
            "",
            "com.netease.play:id/vflipper",
            "android.widget.ViewFlipper",
            "com.netease.play",
            null
    );

    static final UiNodeSpec LIVE_ROOM_TASK_PANEL_DETECT_BLOCK_NODE = new UiNodeSpec(
            "",
            "com.netease.play:id/slidingContainer",
            "",
            "com.netease.play",
            null
    );

    static final UiNodeSpec LIVE_ROOM_TASK_PANEL_CURRENT_ROOM_NODE = new UiNodeSpec(
            "当前房间",
            "",
            "android.widget.TextView",
            "",
            null
    );

    static final UiNodeSpec LIVE_ROOM_TASK_PANEL_CURRENT_ROOM_DESC_NODE = new UiNodeSpec(
            "",
            "",
            "",
            "",
            null,
            "当前房间"
    );

    static final UiNodeSpec LIVE_ROOM_TASK_PANEL_CONTRIBUTION_NODE = new UiNodeSpec(
            "贡献榜",
            "",
            "android.widget.TextView",
            "",
            null
    );

    static final UiNodeSpec LIVE_ROOM_TASK_PANEL_CONTRIBUTION_DESC_NODE = new UiNodeSpec(
            "",
            "",
            "",
            "",
            null,
            "贡献榜"
    );

    static final UiNodeSpec LIVE_ROOM_TASK_PANEL_CHARM_NODE = new UiNodeSpec(
            "魅力榜",
            "",
            "android.widget.TextView",
            "",
            null
    );

    static final UiNodeSpec LIVE_ROOM_TASK_PANEL_CHARM_DESC_NODE = new UiNodeSpec(
            "",
            "",
            "",
            "",
            null,
            "魅力榜"
    );

    static final UiNodeSpec LIVE_ROOM_TASK_PANEL_NOBILITY_NODE = new UiNodeSpec(
            "贵族",
            "",
            "android.widget.TextView",
            "",
            null
    );

    static final UiNodeSpec LIVE_ROOM_TASK_PANEL_NOBILITY_DESC_NODE = new UiNodeSpec(
            "",
            "",
            "",
            "",
            null,
            "贵族"
    );

    static final UiNodeSpec LIVE_ROOM_TASK_PANEL_FANS_NODE = new UiNodeSpec(
            "粉团榜",
            "",
            "android.widget.TextView",
            "",
            null
    );

    static final UiNodeSpec LIVE_ROOM_TASK_PANEL_FANS_DESC_NODE = new UiNodeSpec(
            "",
            "",
            "",
            "",
            null,
            "粉团榜"
    );

    static final UiNodeSpec LIVE_ROOM_TASK_PANEL_ONLINE_NODE = new UiNodeSpec(
            "在线",
            "",
            "android.widget.TextView",
            "",
            null
    );

    static final UiNodeSpec LIVE_ROOM_TASK_PANEL_ONLINE_DESC_NODE = new UiNodeSpec(
            "",
            "",
            "",
            "",
            null,
            "在线"
    );

    static final List<UiNodeSpec> LIVE_ROOM_TASK_PANEL_MARKER_NODES = Collections.unmodifiableList(Arrays.asList(
            LIVE_ROOM_TASK_PANEL_CURRENT_ROOM_NODE,
            LIVE_ROOM_TASK_PANEL_CONTRIBUTION_NODE,
            LIVE_ROOM_TASK_PANEL_CHARM_NODE,
            LIVE_ROOM_TASK_PANEL_NOBILITY_NODE,
            LIVE_ROOM_TASK_PANEL_FANS_NODE,
            LIVE_ROOM_TASK_PANEL_ONLINE_NODE
    ));

    static final List<UiNodeSpec> LIVE_ROOM_TASK_PANEL_MARKER_DESC_NODES = Collections.unmodifiableList(Arrays.asList(
            LIVE_ROOM_TASK_PANEL_CURRENT_ROOM_DESC_NODE,
            LIVE_ROOM_TASK_PANEL_CONTRIBUTION_DESC_NODE,
            LIVE_ROOM_TASK_PANEL_CHARM_DESC_NODE,
            LIVE_ROOM_TASK_PANEL_NOBILITY_DESC_NODE,
            LIVE_ROOM_TASK_PANEL_FANS_DESC_NODE,
            LIVE_ROOM_TASK_PANEL_ONLINE_DESC_NODE
    ));

    // vflipper 面板开启判定：仅使用无障碍树匹配此按钮组（文本 + content-desc 双通道）
    static final List<UiNodeSpec> LIVE_ROOM_TASK_PANEL_OPEN_A11Y_NODES = Collections.unmodifiableList(Arrays.asList(
            LIVE_ROOM_TASK_PANEL_CURRENT_ROOM_DESC_NODE,
            LIVE_ROOM_TASK_PANEL_CONTRIBUTION_DESC_NODE,
            LIVE_ROOM_TASK_PANEL_CHARM_DESC_NODE,
            LIVE_ROOM_TASK_PANEL_NOBILITY_DESC_NODE,
            LIVE_ROOM_TASK_PANEL_FANS_DESC_NODE,
            LIVE_ROOM_TASK_PANEL_ONLINE_DESC_NODE,
            LIVE_ROOM_TASK_PANEL_CURRENT_ROOM_NODE,
            LIVE_ROOM_TASK_PANEL_CONTRIBUTION_NODE,
            LIVE_ROOM_TASK_PANEL_CHARM_NODE,
            LIVE_ROOM_TASK_PANEL_NOBILITY_NODE,
            LIVE_ROOM_TASK_PANEL_FANS_NODE,
            LIVE_ROOM_TASK_PANEL_ONLINE_NODE
    ));

    static final List<UiNodeSpec> LIVE_ROOM_TASK_PANEL_OPEN_PRIMARY_NODES = Collections.unmodifiableList(Arrays.asList(
            LIVE_ROOM_TASK_PANEL_CONTRIBUTION_DESC_NODE,
            LIVE_ROOM_TASK_PANEL_CHARM_DESC_NODE,
            LIVE_ROOM_TASK_PANEL_CONTRIBUTION_NODE,
            LIVE_ROOM_TASK_PANEL_CHARM_NODE
    ));

    static final List<UiNodeSpec> LIVE_ROOM_TASK_CONTRIBUTION_TAB_CANDIDATE_NODES = Collections.unmodifiableList(Arrays.asList(
            LIVE_ROOM_TASK_PANEL_CONTRIBUTION_DESC_NODE,
            LIVE_ROOM_TASK_PANEL_CONTRIBUTION_NODE
    ));

    static final List<UiNodeSpec> LIVE_ROOM_TASK_CHARM_TAB_CANDIDATE_NODES = Collections.unmodifiableList(Arrays.asList(
            LIVE_ROOM_TASK_PANEL_CHARM_DESC_NODE,
            LIVE_ROOM_TASK_PANEL_CHARM_NODE
    ));

    static final List<String> COMMON_CLOSE_ACTIONS = Collections.unmodifiableList(Arrays.asList(
            "关闭",
            "跳过",
            "取消"
    ));

    private UiComponentConfig() {
    }

    static String resourceEntryFromFullId(String fullResourceId) {
        if (fullResourceId == null) {
            return "";
        }
        int slash = fullResourceId.lastIndexOf('/');
        if (slash < 0 || slash >= fullResourceId.length() - 1) {
            return "";
        }
        return fullResourceId.substring(slash + 1);
    }

    static final class UiNodeSpec {
        final String text;
        final String fullResourceId;
        final String className;
        final String packageName;
        final Boolean selected;
        final String contentDesc;

        UiNodeSpec(String text, String fullResourceId, String className, String packageName, Boolean selected) {
            this(text, fullResourceId, className, packageName, selected, "");
        }

        UiNodeSpec(
                String text,
                String fullResourceId,
                String className,
                String packageName,
                Boolean selected,
                String contentDesc
        ) {
            this.text = text;
            this.fullResourceId = fullResourceId;
            this.className = className;
            this.packageName = packageName;
            this.selected = selected;
            this.contentDesc = contentDesc;
        }
    }
}
