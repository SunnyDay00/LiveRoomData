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

    static final long MAIN_LOOP_INTERVAL_MS = 500L;
    static final long AD_SCAN_INTERVAL_MS = 350L;
    static final long HOME_LOG_INTERVAL_MS = 3000L;
    static final long FLOW_LOG_INTERVAL_MS = 2500L;
    static final long ENTER_TOGETHER_TIMEOUT_MS = 5000L;
    static final long RETRY_ENTER_TOGETHER_MS = 1200L;
    static final long STARTUP_WAIT_BEFORE_CLICK_TOGETHER_MS = 3500L;
    static final long TOGETHER_REFRESH_WAIT_BEFORE_PULL_MS = 1200L;
    static final long TOGETHER_REFRESH_SETTLE_MS = 1800L;
    static final long TOGETHER_REFRESH_RETRY_INTERVAL_MS = 900L;
    static final long LIVE_ROOM_ENTER_WAIT_MS = 700L;
    static final long CARD_CLICK_COOLDOWN_MS = 1000L;
    static final long LIVE_ROOM_RETURN_TOGETHER_WAIT_MS = 1000L;
    static final long LIVE_ROOM_ENTER_TASK_WAIT_MS = 5000L;
    static final long LIVE_ROOM_SCRIPT_BACK_DELAY_MS = 500L;
    static final long TOUCH_SWIPE_DURATION_MS = 280L;
    static final int TOUCH_SWIPE_MOVE_STEPS = 8;
    static final float TOGETHER_REFRESH_SWIPE_X_RATIO = 0.5f;
    static final float TOGETHER_REFRESH_SWIPE_START_Y_RATIO = 0.28f;
    static final float TOGETHER_REFRESH_SWIPE_END_Y_RATIO = 0.78f;
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

        UiNodeSpec(String text, String fullResourceId, String className, String packageName, Boolean selected) {
            this.text = text;
            this.fullResourceId = fullResourceId;
            this.className = className;
            this.packageName = packageName;
            this.selected = selected;
        }
    }
}
