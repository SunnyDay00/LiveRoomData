package com.oodbye.shuangyulspmodule;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 集中管理目标应用 com.sybl.voiceroom 的所有 UI 组件参数。
 */
final class UiComponentConfig {

    private UiComponentConfig() {
    }

    // ─────────────────────── 目标包名 ───────────────────────
    static final String TARGET_PACKAGE = "com.sybl.voiceroom";

    // ─────────────────────── 模块包名 ───────────────────────
    static final String MODULE_PACKAGE = "com.oodbye.shuangyulspmodule";

    // ─────────────────────── UI 节点规格 ───────────────────────
    static final class UiNodeSpec {
        final String text;
        final String fullResourceId;
        final String className;
        final String packageName;
        final Boolean selected;
        final String contentDesc;

        UiNodeSpec(String text, String fullResourceId, String className,
                   String packageName, Boolean selected) {
            this(text, fullResourceId, className, packageName, selected, "");
        }

        UiNodeSpec(String text, String fullResourceId, String className,
                   String packageName, Boolean selected, String contentDesc) {
            this.text = text;
            this.fullResourceId = fullResourceId;
            this.className = className;
            this.packageName = packageName;
            this.selected = selected;
            this.contentDesc = contentDesc == null ? "" : contentDesc;
        }
    }

    // ─────────────────────── 首页底部 Tab ───────────────────────
    /** 底部 Tab - 娱乐 (点击目标) */
    static final UiNodeSpec HOME_TAB_ENTERTAINMENT = new UiNodeSpec(
            "娱乐",
            "com.sybl.voiceroom:id/tv_tab_room",
            "android.widget.TextView",
            TARGET_PACKAGE,
            null
    );

    /** 底部 Tab - 娱乐 (已选中，用于验证) */
    static final UiNodeSpec HOME_TAB_ENTERTAINMENT_SELECTED = new UiNodeSpec(
            "娱乐",
            "com.sybl.voiceroom:id/tv_tab_room",
            "android.widget.TextView",
            TARGET_PACKAGE,
            true
    );

    /** 底部 Tab 容器 - 娱乐 (可点击区域) */
    static final UiNodeSpec HOME_TAB_ENTERTAINMENT_CONTAINER = new UiNodeSpec(
            "",
            "com.sybl.voiceroom:id/ll_tab_room",
            "android.widget.LinearLayout",
            TARGET_PACKAGE,
            null
    );

    // ─────────────────────── 榜单 Tab ───────────────────────
    /** TabLayout 容器 */
    static final String RANK_TAB_LAYOUT_ID = "com.sybl.voiceroom:id/tabLayout";

    /** 女神 Tab */
    static final UiNodeSpec RANK_TAB_GODDESS = new UiNodeSpec(
            "女神", "", "android.widget.TextView", TARGET_PACKAGE, null
    );
    /** 女神 Tab (已选中) */
    static final UiNodeSpec RANK_TAB_GODDESS_SELECTED = new UiNodeSpec(
            "女神", "", "android.widget.TextView", TARGET_PACKAGE, true
    );

    /** 男神 Tab */
    static final UiNodeSpec RANK_TAB_GOD = new UiNodeSpec(
            "男神", "", "android.widget.TextView", TARGET_PACKAGE, null
    );
    /** 男神 Tab (已选中) */
    static final UiNodeSpec RANK_TAB_GOD_SELECTED = new UiNodeSpec(
            "男神", "", "android.widget.TextView", TARGET_PACKAGE, true
    );

    /** 点唱 Tab */
    static final UiNodeSpec RANK_TAB_SING = new UiNodeSpec(
            "点唱", "", "android.widget.TextView", TARGET_PACKAGE, null
    );
    /** 点唱 Tab (已选中) */
    static final UiNodeSpec RANK_TAB_SING_SELECTED = new UiNodeSpec(
            "点唱", "", "android.widget.TextView", TARGET_PACKAGE, true
    );

    // ─────────────────────── 房间列表 ───────────────────────
    /** 房间列表 RecyclerView */
    static final String ROOM_LIST_RECYCLER_ID = "com.sybl.voiceroom:id/mRecyclerView";
    static final String ROOM_LIST_RECYCLER_CLASS = "androidx.recyclerview.widget.RecyclerView";

    /** 房间卡片 (子节点) */
    static final String ROOM_CARD_CLASS = "android.view.ViewGroup";

    /** 房间名 */
    static final String ROOM_CARD_NAME_ID = "com.sybl.voiceroom:id/tv_room_name";
    /** 主播名 */
    static final String ROOM_CARD_HOST_ID = "com.sybl.voiceroom:id/tv_host_name";
    /** 热度值 */
    static final String ROOM_CARD_HOT_ID = "com.sybl.voiceroom:id/tv_room_hot";

    // ─────────────────────── 房间内 ───────────────────────
    /** 房间名 (房间内) */
    static final String LIVE_ROOM_NAME_ID = "com.sybl.voiceroom:id/tv_room_name";
    /** 房间 ID (房间内) */
    static final String LIVE_ROOM_CODE_ID = "com.sybl.voiceroom:id/tv_room_code";

    // ─────────────────────── 在线榜 ───────────────────────
    /** 在线榜入口 (可点击容器) */
    static final String ONLINE_USER_LAYOUT_ID = "com.sybl.voiceroom:id/layout_online_user";
    /** 在线人数文本 (不可点击，仅读取) */
    static final String ONLINE_COUNT_ID = "com.sybl.voiceroom:id/tv_online_count";

    // ─────────────────────── 用户卡片列表 (在线榜内) ───────────────────────
    /** 用户卡片列表 RecyclerView (与房间列表共用同一 resource-id) */
    static final String USER_LIST_RECYCLER_ID = "com.sybl.voiceroom:id/mRecyclerView";

    /** 用户 ID */
    static final String USER_CODE_ID = "com.sybl.voiceroom:id/tv_user_code";
    /** 用户昵称 */
    static final String USER_NICKNAME_ID = "com.sybl.voiceroom:id/tv_nickname";
    /** 用户昵称容器 */
    static final String USER_INFO_LAYOUT_ID = "com.sybl.voiceroom:id/ll_info";
    /** 管理员/房主图标 (存在表示是管理员) */
    static final String USER_ROOM_ROLE_ICON_ID = "com.sybl.voiceroom:id/iv_room_role";

    // ─────────────────────── 用户卡片详情 (点击用户后) ───────────────────────
    /** 用户地区 */
    static final String USER_DETAIL_LOCATION_ID = "com.sybl.voiceroom:id/tv_location";
    /** 用户年龄 */
    static final String USER_DETAIL_AGE_ID = "com.sybl.voiceroom:id/tv_age";

    // ─────────────────────── 超时/间隔 ───────────────────────
    /** 点击后等待 UI 变化 */
    static final long CLICK_WAIT_MS = 1200L;
    /** 滚动后等待加载 */
    static final long SCROLL_WAIT_MS = 1500L;
    /** 进入房间后等待 */
    static final long ENTER_ROOM_WAIT_MS = 2500L;
    /** 返回后等待 UI 恢复 */
    static final long BACK_WAIT_MS = 1500L;
    /** 在线榜点击后等待 */
    static final long ONLINE_LIST_WAIT_MS = 2000L;
    /** 用户卡片弹出等待 */
    static final long USER_CARD_WAIT_MS = 1800L;
    /** 检查新内容最大重试次数 */
    static final int NO_NEW_CONTENT_MAX_RETRIES = 3;
    /** 广告实时检测循环间隔 */
    static final long AD_REALTIME_LOOP_INTERVAL_MS = 2000L;
    /** HTTP 连接超时 */
    static final int AI_HTTP_CONNECT_TIMEOUT_MS = 15000;
    /** HTTP 读取超时 */
    static final int AI_HTTP_READ_TIMEOUT_MS = 60000;

    // ─────────────────────── 文件路径 ───────────────────────
    /** CSV 外部存储目录 */
    static final String LIVE_RANK_CSV_EXTERNAL_DIR = "/sdcard/ShuangyuLspModule/csv";
    /** CSV 回退目录名 */
    static final String LIVE_RANK_CSV_FALLBACK_DIR_NAME = "shuangyu_csv";
    /** CSV 文件名前缀 */
    static final String LIVE_RANK_CSV_PREFIX = "shuangyu_data";
    /** CSV 文件后缀 */
    static final String LIVE_RANK_CSV_SUFFIX = ".csv";
    /** 旧版兼容：贡献/魅力文件前缀 (不再使用但保留常量) */
    static final String LIVE_RANK_CSV_CONTRIBUTION_PREFIX = "contribution";
    static final String LIVE_RANK_CSV_CHARM_PREFIX = "charm";

    /** 运行日志外部目录 */
    static final String RUN_LOG_EXTERNAL_DIR = "/sdcard/ShuangyuLspModule/logs";
    /** 运行日志回退目录名 */
    static final String RUN_LOG_FALLBACK_DIR_NAME = "shuangyu_logs";
    /** 当前运行日志文件名 */
    static final String RUN_LOG_CURRENT_FILE_NAME = "run_current.log";
    /** 上次运行日志文件名 */
    static final String RUN_LOG_PREVIOUS_FILE_NAME = "run_previous.log";

    /** 自定义广告规则文件路径 */
    static final String CUSTOM_AD_RULES_FILE_PATH = "/sdcard/ShuangyuLspModule/ad_rules.json";

    /** AI 分析结果目录 */
    static final String AI_RESULT_EXTERNAL_DIR = "/sdcard/ShuangyuLspModule/ai_result";
    /** AI 分析结果回退目录名 */
    static final String AI_RESULT_FALLBACK_DIR_NAME = "shuangyu_ai_result";
    /** AI Prompt 文件路径 */
    static final String AI_PROMPT_FILE_PATH = "/sdcard/ShuangyuLspModule/ai_prompt.txt";

    // ─────────────────────── 辅助方法（从原 LOOK 项目保留） ───────────────────────
    /** 提取 resource-id 的短名 */
    static String shortResourceId(String fullId) {
        if (fullId == null || fullId.isEmpty()) {
            return "";
        }
        int slashIndex = fullId.indexOf('/');
        if (slashIndex >= 0 && slashIndex < fullId.length() - 1) {
            return fullId.substring(slashIndex + 1);
        }
        return fullId;
    }
}
