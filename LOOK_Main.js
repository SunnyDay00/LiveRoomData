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
  // 应用配置
  APP_PKG: "com.netease.play",
  APP_NAME: "LOOK直播",

  // 循环控制
  LOOP_LIVE_TOTAL: 0,          // 0 => 无限；>0 => 点击直播间总次数
  STOP_AFTER_ROWS: 200,        // 写入多少条停止（安全停止）

  // 等待时间（毫秒）
  CLICK_LIVE_WAIT_MS: 2000,    // 点击直播间后等待
  CLICK_WAIT_MS: 1500,         // 点击按钮后等待
  APP_RESTART_WAIT_MS: 5000,   // App重启后等待

  // 重试次数
  ENTER_LIVE_RETRY: 3,         // 进入直播间重试次数
  HOME_ENSURE_RETRY: 4,        // 确保回到首页重试次数
  LIVE_ROOM_CHECK_RETRY: 4,    // 检查直播间有效性重试次数

  // 贡献榜采集
  CONTRIB_CLICK_COUNT: 5,      // 每个直播间采集多少个贡献榜用户

  // 滚动参数
  SCROLL_DISTANCE: 0.80,
  SCROLL_DURATION: 500,
  SCROLL_AFTER_WAIT: 800,
  NO_NEW_SCROLL_LIMIT: 6,      // 连续N次没有新卡片 => 认为到底/异常

  // 悬浮日志开关（1=开启，0=关闭）
  FLOAT_LOG_ENABLED: 1,

  // 错误暂停开关（1=开启，0=关闭）
  DEBUG_PAUSE_ON_ERROR: 1,

  // 崩溃监控检查间隔（毫秒）
  MONITOR_CHECK_INTERVAL: 10000,

  // 关键控件ID
  ID_TAB: "com.netease.play:id/tv_dragon_tab",
  ID_IVCOVER: "com.netease.play:id/ivCover",
  ID_TVNAME: "com.netease.play:id/tvName",
  ID_HEADER: "com.netease.play:id/headerUiContainer",
  ID_CLOSEBTN: "com.netease.play:id/closeBtn",
  ID_BGVIEW: "com.netease.play:id/bgView",
  ID_AVATAR: "com.netease.play:id/avatar",
  ID_VFLIPPER: "com.netease.play:id/vflipper",
  ID_RANKTEXT: "com.netease.play:id/rankText",
  ID_USER_ID: "com.netease.play:id/id",
  ID_USER_NAME: "com.netease.play:id/artist_name",
  ID_NUM: "com.netease.play:id/num"
};

// ==============================
// 工具函数
// ==============================
function nowStr() { 
  return "" + (new Date().getTime()); 
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
// 软件状态检查
// ==============================
function checkAppRunning() {
  logi("检查软件运行状态...");
  
  try {
    var ret = launchApp(CONFIG.APP_PKG, "");
    if (ret == 0) {
      loge(CONFIG.APP_NAME + " 未安装");
      alert(CONFIG.APP_NAME + " 未安装，请先安装应用。");
      return false;
    }
    logi(CONFIG.APP_NAME + " 已启动");
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
  setLogLevel(4);
  enableVolumeRun(true);

  logi("========== LOOK直播数据采集脚本启动 ==========");

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

  // Step 3: 崩溃监控（已禁用）
  // 由于 currentPackage() 函数在此引擎中不可用，监控功能无法正常工作
  // 如需启用，需要找到引擎支持的前台检测方法
  // startMonitorThread();
  logi("崩溃监控已禁用（currentPackage不可用）");

  logi("调用 LOOK_StartLiveRoom...");
  try {
    var result = callScript("LOOK_StartLiveRoom", CONFIG);
    logi("LOOK_StartLiveRoom 执行完成: " + JSON.stringify(result));
  } catch (e) {
    loge("callScript LOOK_StartLiveRoom 异常: " + e);
  }

  logi("========== LOOK直播数据采集脚本结束 ==========");
}

main();
