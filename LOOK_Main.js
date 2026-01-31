/**
 * LOOK_Main.js - LOOK直播数据采集主脚本
 * 
 * 冰狐智能辅助（aznfz）脚本
 * 
 * 所有可自定义变量集中在此文件，方便修改。
 * 运行此脚本开始采集数据。
 */

// ==============================
// 配置区（所有可自定义变量）
// ==============================
var CONFIG = {
  APP_PKG: "com.netease.play", // 应用包名
  APP_NAME: "LOOK直播", // 应用名称
  GKD_PKG: "li.songe.gkd-1", // GKD 包名
  AZNFZ_PKG: "com.libra.aznfz-1", // 冰狐智能辅助包名（客户端）

  LOOP_LIVE_TOTAL: 0, // 0=无限；>0=点击直播间总次数
  STOP_AFTER_ROWS: 200, // 写入多少条停止（安全停止）

  CLICK_LIVE_WAIT_MS: 2000, // 点击直播间后等待
  CLICK_WAIT_MS: 1500, // 点击按钮后等待
  APP_RESTART_WAIT_MS: 5000, // App重启后等待

  ENTER_LIVE_RETRY: 3, // 进入直播间重试次数
  RESTART_TO_CHAT_RETRY: 2, // 退出/重启→切到一起聊 重试次数
  HOME_ENSURE_RETRY: 4, // 确保回到首页重试次数
  LIVE_ROOM_CHECK_RETRY: 4, // 检查直播间有效性重试次数

  CONTRIB_CLICK_COUNT: 5, // 每个直播间采集贡献榜用户数量

  SCROLL_DISTANCE: 0.80, // 旧版滑动距离比例（保留字段）
  SCROLL_DURATION: 500, // 旧版滑动时长(ms)（保留字段）
  SCROLL_AFTER_WAIT: 800, // 滑动后等待(ms)
  NO_NEW_SCROLL_LIMIT: 6, // 连续无新卡片次数上限

  CHAT_TAB_CHECK_RETRY: 3, // 切换一起聊重试次数
  CHAT_TAB_CHECK_WAIT_MS: 800, // 切换一起聊失败等待(ms)
  CHAT_TAB_TEXT_MAX_STEP: 4, // 查找一起聊文本 maxStep

  LIVE_CARD_TAP_X: 540, // 直播间卡片点击 X
  LIVE_CARD_TAP_Y: 403, // 直播间卡片点击 Y

  NEXT_SWIPE_START_X: 540, // 下滑起点 X
  NEXT_SWIPE_START_Y: 1700, // 下滑起点 Y
  NEXT_SWIPE_END_X: 540, // 下滑终点 X
  NEXT_SWIPE_END_Y: 300, // 下滑终点 Y
  NEXT_SWIPE_DURATION: 800, // 下滑持续时长(ms)

  FLOAT_LOG_ENABLED: 1, // 悬浮日志开关（1=开，0=关）
  DEBUG_PAUSE_ON_ERROR: 1, // 错误暂停开关（1=开，0=关）
  MONITOR_CHECK_INTERVAL: 10000, // 崩溃监控检查间隔(ms)

  SHIZUKU_PKG: "moe.shizuku.privileged.api", // Shizuku 包名
  RETRY_COUNT: 5, // Shizuku 连接重试次数
  RETRY_INTERVAL: 2000, // Shizuku 重试间隔(ms)
  PERMISSION_TIMEOUT: 10000, // Shizuku 权限请求超时(ms)
  USE_SHIZUKU_RESTART: 1, // 1=使用Shizuku强制关闭/启动（重启流程）
  MAIN_POPUP_HANDLER_ENABLED: 0, // 1=强制主脚本检查广告（即使App已在前台）
  MAIN_LAUNCHED_APP: 0, // 运行时标记：是否由主脚本拉起App（自动覆盖）

  ID_TAB: "com.netease.play:id/tv_dragon_tab", // 首页TAB文本
  ID_RNVIEW: "com.netease.play:id/rnView", // 一起聊 rnView
  ID_ROOMNO: "com.netease.play:id/roomNo", // 直播间 roomNo
  ID_LAYOUT_HEADER: "com.netease.play:id/layout_header", // 首页 header 容器
  ID_IVCOVER: "com.netease.play:id/ivCover", // 直播间封面
  ID_TVNAME: "com.netease.play:id/tvName", // 直播间主播名
  ID_HEADER: "com.netease.play:id/headerUiContainer", // 直播间头部容器
  ID_CLOSEBTN: "com.netease.play:id/closeBtn", // 直播间关闭按钮
  ID_BGVIEW: "com.netease.play:id/bgView", // 背景视图
  ID_AVATAR: "com.netease.play:id/avatar", // 头像
  ID_VFLIPPER: "com.netease.play:id/vflipper", // vflipper 组件
  ID_RANKTEXT: "com.netease.play:id/rankText", // 排行文字
  ID_USER_ID: "com.netease.play:id/id", // 用户ID
  ID_USER_NAME: "com.netease.play:id/artist_name", // 用户名
  ID_NUM: "com.netease.play:id/num" // 数值字段
};

var g_mainLaunchedApp = 0;

// ==============================
// 工具函数
// ==============================
function nowStr() { 
  // 获取UTC时间戳,然后加上北京时间偏移(UTC+8小时)
  var utcTime = new Date().getTime();
  var beijingOffset = 8 * 60 * 60 * 1000; // 8小时转换为毫秒
  return "" + (utcTime + beijingOffset);
}

function logi(msg) { 
  console.info("[" + nowStr() + "][LOOK_Main][INFO] " + msg);
  if (CONFIG.FLOAT_LOG_ENABLED == 1) {
    try { floatMessage("[Main][INFO] " + msg); } catch (e) {}
  }
}

function logw(msg) { 
  console.warn("[" + nowStr() + "][LOOK_Main][WARN] " + msg);
  if (CONFIG.FLOAT_LOG_ENABLED == 1) {
    try { floatMessage("[Main][WARN] " + msg); } catch (e) {}
  }
}

function loge(msg) { 
  console.error("[" + nowStr() + "][LOOK_Main][ERROR] " + msg);
  if (CONFIG.FLOAT_LOG_ENABLED == 1) {
    try { floatMessage("[Main][ERROR] " + msg); } catch (e) {}
  }
}

function sleepMs(ms) { 
  sleep(ms); 
}

function findRet(tag, options) {
  var ret = null;
  try {
    if (options != null) {
      ret = findView(tag, options);
    } else {
      ret = findView(tag);
    }
  } catch (e) {
    ret = null;
  }
  return ret;
}

function hasView(tag, options) {
  var ret = findRet(tag, options);
  if (ret != null) {
    if (ret.length > 0) {
      return true;
    }
  }
  return false;
}

function isHomePageFast() {
  var ret = findRet("id:" + CONFIG.ID_TAB, {flag: "find_all", maxStep: 2});
  if (ret == null) { return false; }
  if (ret.length < 2) { return false; }
  return true;
}

function isLiveRoomPageFast() {
  if (!hasView("id:" + CONFIG.ID_HEADER, {maxStep: 2})) { return false; }
  if (hasView("id:" + CONFIG.ID_CLOSEBTN, {maxStep: 2})) { return true; }
  return false;
}

function isAppForeground() {
  if (isHomePageFast()) { return true; }
  if (isLiveRoomPageFast()) { return true; }
  return false;
}

// ==============================
// 无障碍检查
// ==============================
function checkAccessibility() {
  logi("检查无障碍功能...");
  
  try {
    var enabled = isAccessibilityEnabled();
    if (enabled) {
      logi("无障碍功能已开启");
      return true;
    }
  } catch (e) {
    loge("检查无障碍功能异常: " + e);
  }
  
  // 无障碍未开启，弹窗警告
  logw("无障碍功能未开启");
  alert("⚠️ 无障碍功能未开启\n\n请开启无障碍功能后重新运行脚本。\n\n点击确定后将打开无障碍设置。");
  
  try {
    openAccessibilitySetting();
  } catch (e) {
    loge("打开无障碍设置失败: " + e);
  }
  
  return false;
}

// ==============================
// GKD 运行状态检查
// ==============================
function isPackageRunningByShizuku(packageName) {
  try {
    shizuku.init();
    if (!shizuku.connect()) {
      return null;
    }
    var ret = shizuku.execCmd("pidof " + packageName);
    shizuku.close();
    if (ret == null) { return false; }
    var s = ("" + ret);
    if (s.indexOf("not found") >= 0) { return null; }
    var i = 0;
    for (i = 0; i < s.length; i = i + 1) {
      var c = s.charAt(i);
      if (c >= "0" && c <= "9") { return true; }
    }
    return false;
  } catch (e) {
    return null;
  }
}

function checkGkdRunning() {
  logi("检查 GKD 运行状态...");

  // 前台快速判定
  try {
    if (getCurPackageName() == CONFIG.GKD_PKG) {
      logi("检测到 GKD 在前台运行");
      return true;
    }
  } catch (e) {}

  // Shizuku 进程检查
  var running = isPackageRunningByShizuku(CONFIG.GKD_PKG);
  if (running === true) {
    logi("检测到 GKD 进程在运行");
    return true;
  }

  if (running === null) {
    logw("无法通过 Shizuku 判断 GKD 是否运行，按未运行处理");
  } else {
    logw("未检测到 GKD 进程运行");
  }

  try { refresh({packageName: CONFIG.AZNFZ_PKG}); } catch (e2) {}
  alert("请先启动GKD软件");
  return false;
}

// ==============================
// 软件状态检查
// ==============================
function checkAppRunning() {
  logi("检查软件运行状态...");

  if (isAppForeground()) {
    logi("检测到App已在前台运行");
    g_mainLaunchedApp = 0;
    return true;
  }
  
  try {
    var ret = launchApp(CONFIG.APP_PKG, "");
    if (ret == 0) {
      loge(CONFIG.APP_NAME + " 未安装");
      alert(CONFIG.APP_NAME + " 未安装，请先安装应用。");
      return false;
    }
    g_mainLaunchedApp = 1;
    logi(CONFIG.APP_NAME + " 已启动（由主脚本拉起）");
    return true;
  } catch (e) {
    loge("启动应用异常: " + e);
    return false;
  }
}

// ==============================
// 崩溃监控线程函数
// ==============================
function monitorThreadFunc() {
  try {
    // 注意：线程函数无法访问全局变量，需要硬编码参数
    callScript("AppMonitor", 
      "com.netease.play",  // appPkg
      "LOOK直播",           // appName
      "LOOK_Main",         // mainScript
      10000                 // checkInterval
    );
  } catch (e) {
    console.error("[AppMonitor Thread] error: " + e);
  }
}

// ==============================
// 启动崩溃监控线程
// ==============================
function startMonitorThread() {
  logi("启动崩溃监控线程...");
  
  try {
    var t = new Thread();
    t.start(monitorThreadFunc);
    logi("崩溃监控线程已启动");
    return true;
  } catch (e) {
    loge("启动监控线程失败: " + e);
    return false;
  }
}

// ==============================
// 主入口
// ==============================
function main() {
  setLogLevel(5);
  enableVolumeRun(true);

  logi("========== LOOK直播数据采集脚本启动 ==========");

  // Step 0: 检查 GKD 是否运行
  if (!checkGkdRunning()) {
    stop();
    return;
  }

  // Step 1: 检查无障碍功能
  if (!checkAccessibility()) {
    logi("脚本退出：无障碍功能未开启");
    stop();
    return;
  }

  // Step 2: 检查软件运行状态
  if (!checkAppRunning()) {
    logi("脚本退出：软件未安装或启动失败");
    stop();
    return;
  }

  sleepMs(CONFIG.APP_RESTART_WAIT_MS);
  
  // Step 2.1: 处理开屏广告/弹窗（已移除 PopupHandler 调用）
  CONFIG.MAIN_LAUNCHED_APP = g_mainLaunchedApp;

  // Step 3: 崩溃监控（已禁用）
  // 由于 currentPackage() 函数在此引擎中不可用，监控功能无法正常工作
  // 如需启用，需要找到引擎支持的前台检测方法
  // startMonitorThread();
  logi("崩溃监控已禁用（currentPackage不可用）");

  logi("调用 LOOK_StartLiveRoom...");
  try {
    var result = callScript("LOOK_StartLiveRoom", CONFIG);
    logi("LOOK_StartLiveRoom 执行完成: " + result);
  } catch (e) {
    loge("callScript LOOK_StartLiveRoom 异常: " + e);
  }

  logi("========== LOOK直播数据采集脚本结束 ==========");
}

main();
