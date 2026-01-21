/**
 * LOOK_StartLiveRoom.js - 启动直播间脚本
 * 
 * 在首页循环点击直播间卡片，检查有效性后采集数据。
 * 
 * 使用方式：
 *   callScript("LOOK_StartLiveRoom.js", {
 *     config: CONFIG  // 主脚本传入的配置
 *   });
 */

// ==============================
// 默认配置（从主脚本传入覆盖）
// ==============================
var CONFIG = {
  APP_PKG: "com.netease.play",
  APP_NAME: "LOOK直播",
  
  LOOP_LIVE_TOTAL: 0,
  STOP_AFTER_ROWS: 200,
  
  CLICK_LIVE_WAIT_MS: 2000,
  CLICK_WAIT_MS: 1500,
  APP_RESTART_WAIT_MS: 5000,
  
  ENTER_LIVE_RETRY: 3,
  HOME_ENSURE_RETRY: 4,
  LIVE_ROOM_CHECK_RETRY: 3,
  
  CONTRIB_CLICK_COUNT: 5,
  
  SCROLL_DISTANCE: 0.80,
  SCROLL_DURATION: 500,
  SCROLL_AFTER_WAIT: 800,
  NO_NEW_SCROLL_LIMIT: 6,
  
  FLOAT_LOG_ENABLED: 1,
  DEBUG_PAUSE_ON_ERROR: 0,
  
  ID_TAB: "com.netease.play:id/tv_dragon_tab",
  ID_IVCOVER: "com.netease.play:id/ivCover",
  ID_TVNAME: "com.netease.play:id/tvName",
  ID_HEADER: "com.netease.play:id/headerUiContainer",
  ID_CLOSEBTN: "com.netease.play:id/closeBtn"
};

// ==============================
// 全局变量
// ==============================
var g_seen = {};
var g_liveClickCount = 0;

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
  console.info("[" + nowStr() + "][StartLiveRoom][INFO] " + msg);
  if (CONFIG.FLOAT_LOG_ENABLED == 1) {
    try { floatMessage("[StartLiveRoom][INFO] " + msg); } catch (e) {}
  }
}

function logw(msg) { 
  console.warn("[" + nowStr() + "][StartLiveRoom][WARN] " + msg);
  if (CONFIG.FLOAT_LOG_ENABLED == 1) {
    try { floatMessage("[StartLiveRoom][WARN] " + msg); } catch (e) {}
  }
}

function loge(msg) { 
  console.error("[" + nowStr() + "][StartLiveRoom][ERROR] " + msg);
  if (CONFIG.FLOAT_LOG_ENABLED == 1) {
    try { floatMessage("[StartLiveRoom][ERROR] " + msg); } catch (e) {}
  }
}

function sleepMs(ms) { 
  sleep(ms); 
}

function debugPause(stepName, errorMsg) {
  loge("[DEBUG_PAUSE] " + stepName + ": " + errorMsg);
  if (CONFIG.DEBUG_PAUSE_ON_ERROR == 1) {
    logi("[DEBUG_PAUSE] 脚本已暂停");
    alert("步骤[" + stepName + "]失败\n\n错误: " + errorMsg + "\n\n点击确定继续执行。");
  }
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

// 检查是否能找到指定控件
function hasView(tag, options) {
  var ret = findRet(tag, options);
  if (ret != null) {
    if (ret.length > 0) {
      return true;
    }
  }
  return false;
}

// 获取第一个找到的view
function getFirstView(tag, options) {
  var ret = findRet(tag, options);
  if (ret != null) {
    if (ret.length > 0) {
      return ret.views[0];
    }
  }
  return null;
}

// 获取第一个控件的文本
function getTextOfFirst(tag, options) {
  var view = getFirstView(tag, options);
  if (view != null) {
    try {
      if (view.text != null) {
        return "" + view.text;
      }
    } catch (e) {}
  }
  return "";
}

function getParent(v) {
  var p = null;
  try { p = v.parent; } catch (e1) { p = null; }
  if (p == null) {
    try { p = v.getParent(); } catch (e2) { p = null; }
  }
  return p;
}

function clickObj(v, stepName) {
  try {
    click(v, {click: true});
    logi("[" + stepName + "] click ok");
    return true;
  } catch (e) {
    loge("[" + stepName + "] click exception=" + e);
    return false;
  }
}

function backAndWait(stepName) {
  try { back(); logi("[" + stepName + "] back()"); } catch (e) { loge("[" + stepName + "] back exception=" + e); }
  sleepMs(CONFIG.CLICK_WAIT_MS);
}

function doScrollDown() {
  var ok = false;
  try {
    ok = scroll(null, "down", {
      type: 1,
      distance: CONFIG.SCROLL_DISTANCE,
      duration: CONFIG.SCROLL_DURATION,
      afterWait: CONFIG.SCROLL_AFTER_WAIT
    });
  } catch (e) {
    ok = false;
  }
  return ok;
}

// ==============================
// App操作
// ==============================
function safeLaunchApp() {
  var ret = -1;
  try {
    ret = launchApp(CONFIG.APP_PKG, "");
  } catch (e) {
    loge("launchApp exception=" + e);
    ret = -1;
  }
  return ret;
}

function safeKillBackgroundApp() {
  try {
    killBackgroundApp(CONFIG.APP_PKG);
    return true;
  } catch (e) {
    return false;
  }
}

function restartApp(reason) {
  logw("[APP_RESTART] reason=" + reason);
  safeKillBackgroundApp();
  var ret = safeLaunchApp();
  if (ret == 0) {
    alert(CONFIG.APP_NAME + " 未安装");
    stop();
    return;
  }
  sleepMs(CONFIG.APP_RESTART_WAIT_MS);
}

// ==============================
// 页面判断
// ==============================
function isHomePage() {
  var ret = findRet("id:" + CONFIG.ID_TAB, {flag: "find_all", maxStep: 2});
  if (ret == null) { return false; }
  // 宽松检测：只要有2个以上的TAB就认为是首页，防止加载延迟导致误判
  if (ret.length < 2) { return false; }

  var validCount = 0;
  var seen = {};

  var count = ret.length;
  var i = 0;
  for (i = 0; i < count; i = i + 1) {
    try {
      var v = ret.views[i];
      if (v == null) { continue; }
      var t = "" + v.text;
      // 检查是否是已知的TAB名称
      if (t == "推荐" || t == "听听" || t == "一起聊" || t == "看看") {
        if (!seen[t]) {
          seen[t] = true;
          validCount = validCount + 1;
        }
      }
    } catch (e) {}
  }

  // 只要找到2个及以上有效TAB，就认为是首页
  if (validCount >= 2) {
    return true;
  }
  return false;
}

function isLiveRoomPage() {
  if (!hasView("id:" + CONFIG.ID_HEADER, {maxStep: 2})) { return false; }
  if (hasView("id:" + CONFIG.ID_CLOSEBTN, {maxStep: 2})) { return true; }
  return false;
}

function ensureHome() {
  var i = 0;

  for (i = 0; i < CONFIG.HOME_ENSURE_RETRY; i = i + 1) {
    if (isHomePage()) {
      logi("ensureHome ok");
      return true;
    }
    backAndWait("ENSURE_HOME_BACK");
  }

  alert(CONFIG.APP_NAME + "未在首页，需重启回首页。点击确认继续。");
  restartApp("ensureHome_fail");

  for (i = 0; i < CONFIG.HOME_ENSURE_RETRY; i = i + 1) {
    if (isHomePage()) {
      logi("ensureHome after restart ok");
      return true;
    }
    sleepMs(800);
  }

  loge("ensureHome failed");
  return false;
}

function goRecommendTab() {
  var ret = findRet("id:" + CONFIG.ID_TAB, {flag: "find_all", maxStep: 2});
  if (ret == null) { return; }
  if (ret.length <= 0) { return; }
  
  var count = ret.length;
  var i = 0;
  for (i = 0; i < count; i = i + 1) {
    try {
      var v = ret.views[i];
      if (v == null) { continue; }
      if (("" + v.text) == "推荐") {
        var p = getParent(v);
        if (p != null) {
          clickObj(p, "HOME_TAB_RECOMMEND");
        } else {
          clickObj(v, "HOME_TAB_RECOMMEND_TEXT");
        }
        sleepMs(CONFIG.CLICK_WAIT_MS);
        return;
      }
    } catch (e) {}
  }
}

// ==============================
// 直播间卡片遍历
// ==============================
function getRoomKeyFromIvCover(ivObj) {
  var p = getParent(ivObj);
  var name = "";
  if (p != null) {
    name = getTextOfFirst("id:" + CONFIG.ID_TVNAME, {root: p, maxStep: 2});
  }
  if (name == null) { name = ""; }

  if (name != "") { return "name_" + name; }

  try { return "pos_" + ivObj.left + "_" + ivObj.top; } catch (e) { return ""; }
}

function pickNextUnseenCardOnScreen() {
  var ret = findRet("id:" + CONFIG.ID_IVCOVER, {flag: "find_all", maxStep: 2});
  if (ret == null) { return null; }
  if (ret.length <= 0) { return null; }

  var count = ret.length;
  var i = 0;
  for (i = 0; i < count; i = i + 1) {
    try {
      var iv = ret.views[i];
      if (iv == null) { continue; }
      var key = getRoomKeyFromIvCover(iv);
      if (key != null && key != "") {
        if (g_seen[key] != true) {
          var card = getParent(iv);
          if (card != null) {
            return { card: card, key: key };
          }
        }
      }
    } catch (e) {}
  }
  return null;
}

// ==============================
// 进入直播间
// ==============================
function enterLiveByCard(cardObj) {
  var r = 0;
  for (r = 0; r < CONFIG.ENTER_LIVE_RETRY; r = r + 1) {
    clickObj(cardObj, "ENTER_LIVE_CARD");
    sleepMs(CONFIG.CLICK_LIVE_WAIT_MS);

    if (isLiveRoomPage()) {
      logi("进入直播间成功");
      return true;
    }
    logw("进入直播间重试=" + r);
  }
  loge("进入直播间失败");
  return false;
}

// ==============================
// 处理单个直播间
// ==============================
function processOneLive(cardObj, roomKey) {
  logi("========== 开始处理直播间 key=" + roomKey + " ==========");

  // Step 1: 进入直播间
  logi("[STEP_1] 尝试进入直播间...");
  if (!enterLiveByCard(cardObj)) {
    loge("[STEP_1] 进入直播间失败");
    debugPause("STEP_1", "进入直播间失败");
    restartApp("enter_live_failed");
    return false;
  }
  logi("[STEP_1] 成功进入直播间");

  // Step 2: 检查直播间有效性
  logi("[STEP_2] 检查直播间有效性...");
  var isValid = false;
  try {
    // callScript("LOOK_CheckLiveRoom", retryCount, checkInterval)
    isValid = callScript("LOOK_CheckLiveRoom", CONFIG.LIVE_ROOM_CHECK_RETRY, 1000);
  } catch (e) {
    loge("[STEP_2] callScript error: " + e);
  }
  
  if (!isValid) {
    logi("[STEP_2] 直播间无效，退出");
    backAndWait("BACK_INVALID_LIVE");
    return true;  // 返回true继续下一个，不是错误
  }
  logi("[STEP_2] 直播间有效");

  // Step 3: 采集主播信息
  logi("[STEP_3] 采集主播信息...");
  var hostResult = null;
  try {
    // callScript("LOOK_HostInfo", retryCount, clickWaitMs)
    // 返回 { success: true, hostInfo: {...} }
    hostResult = callScript("LOOK_HostInfo", CONFIG.ENTER_LIVE_RETRY, CONFIG.CLICK_WAIT_MS);
  } catch (e) {
    loge("[STEP_3] callScript error: " + e);
  }
  
  if (hostResult == null || !hostResult.success || hostResult.hostInfo == null) {
    loge("[STEP_3] 采集主播信息失败");
    debugPause("STEP_3", "采集主播信息失败");
    backAndWait("BACK_HOST_FAIL");
    return false;
  }
  
  var hostInfo = hostResult.hostInfo;
  
  logi("[STEP_3] 主播信息: id=" + hostInfo.id + " name=" + hostInfo.name);

  // Step 4: 采集贡献榜信息
  logi("[STEP_4] 采集贡献榜信息...");
  try {
    // callScript("LOOK_ContributionRank", hostId, hostName, hostFans, hostIp, clickCount, clickWaitMs, stopAfterRows, retryCount)
    callScript("LOOK_ContributionRank", 
      hostInfo.id, hostInfo.name, hostInfo.fans, hostInfo.ip,
      CONFIG.CONTRIB_CLICK_COUNT, CONFIG.CLICK_WAIT_MS, CONFIG.STOP_AFTER_ROWS, CONFIG.ENTER_LIVE_RETRY);
  } catch (e) {
    loge("[STEP_4] callScript error: " + e);
  }

  // Step 5: 返回首页
  logi("[STEP_5] 返回首页...");
  
  // 检查是否已经在首页（防止ContributionRank已退出）
  if (isHomePage()) {
    logi("已在首页，无需返回");
  } else {
    backAndWait("BACK_LIVE_TO_HOME");
  }
  ensureHome();
  goRecommendTab();

  logi("========== 完成处理直播间 key=" + roomKey + " ==========");
  return true;
}

// ==============================
// 主循环
// ==============================
function mainLoop() {
  var noNewScroll = 0;

  while (true) {
    // 检查数据库写入上限
    var insertCount = 0;
    try {
      // callScript("DataHandler", "getCount")
      insertCount = callScript("DataHandler", "getCount");
    } catch (e) {}
    
    if (insertCount >= CONFIG.STOP_AFTER_ROWS) {
      logw("达到 STOP_AFTER_ROWS=" + CONFIG.STOP_AFTER_ROWS);
      break;
    }

    // 检查直播间点击次数限制
    if (CONFIG.LOOP_LIVE_TOTAL != null) {
      if (CONFIG.LOOP_LIVE_TOTAL > 0) {
        if (g_liveClickCount >= CONFIG.LOOP_LIVE_TOTAL) {
          logw("达到 LOOP_LIVE_TOTAL=" + CONFIG.LOOP_LIVE_TOTAL);
          break;
        }
      }
    }

    // 确保在首页
    if (!isHomePage()) {
      logw("不在首页，尝试回到首页");
      if (!ensureHome()) {
        restartApp("not_home");
      }
      goRecommendTab();
    }

    // 寻找下一个未处理的直播间卡片
    var pick = pickNextUnseenCardOnScreen();
    if (pick != null) {
      g_seen[pick.key] = true;
      noNewScroll = 0;

      g_liveClickCount = g_liveClickCount + 1;
      logi("选择直播间 key=" + pick.key + " count=" + g_liveClickCount);

      var r = processOneLive(pick.card, pick.key);
      if (r == "STOP_DB_MAX") {
        logw("达到数据库上限，停止");
        break;
      }
      if (r != true) {
        logw("处理失败，回到首页继续");
        ensureHome();
        goRecommendTab();
      }
    } else {
      // 当前屏幕没有新卡片，滚动
      var sc = doScrollDown();
      logi("滚动 ok=" + sc);

      var pick2 = pickNextUnseenCardOnScreen();
      if (pick2 == null) {
        noNewScroll = noNewScroll + 1;
      } else {
        noNewScroll = 0;
      }

      logi("noNewScroll=" + noNewScroll + "/" + CONFIG.NO_NEW_SCROLL_LIMIT);

      if (noNewScroll >= CONFIG.NO_NEW_SCROLL_LIMIT) {
        logw("连续多次无新内容，重启");
        restartApp("no_new_items");
        ensureHome();
        goRecommendTab();
        noNewScroll = 0;
      }
    }
  }
}

// ==============================
// 主入口
// ==============================
function main(config) {
  // 如果传入了配置对象，则覆盖默认配置
  if (config != null && typeof config === "object") {
    var params = config;
    // 手动覆盖配置（不使用for-in循环）
    if (params.APP_PKG != null) { CONFIG.APP_PKG = params.APP_PKG; }
    if (params.APP_NAME != null) { CONFIG.APP_NAME = params.APP_NAME; }
    if (params.LOOP_LIVE_TOTAL != null) { CONFIG.LOOP_LIVE_TOTAL = params.LOOP_LIVE_TOTAL; }
    if (params.STOP_AFTER_ROWS != null) { CONFIG.STOP_AFTER_ROWS = params.STOP_AFTER_ROWS; }
    if (params.CLICK_LIVE_WAIT_MS != null) { CONFIG.CLICK_LIVE_WAIT_MS = params.CLICK_LIVE_WAIT_MS; }
    if (params.CLICK_WAIT_MS != null) { CONFIG.CLICK_WAIT_MS = params.CLICK_WAIT_MS; }
    if (params.APP_RESTART_WAIT_MS != null) { CONFIG.APP_RESTART_WAIT_MS = params.APP_RESTART_WAIT_MS; }
    if (params.ENTER_LIVE_RETRY != null) { CONFIG.ENTER_LIVE_RETRY = params.ENTER_LIVE_RETRY; }
    if (params.HOME_ENSURE_RETRY != null) { CONFIG.HOME_ENSURE_RETRY = params.HOME_ENSURE_RETRY; }
    if (params.LIVE_ROOM_CHECK_RETRY != null) { CONFIG.LIVE_ROOM_CHECK_RETRY = params.LIVE_ROOM_CHECK_RETRY; }
    if (params.CONTRIB_CLICK_COUNT != null) { CONFIG.CONTRIB_CLICK_COUNT = params.CONTRIB_CLICK_COUNT; }
    if (params.SCROLL_DISTANCE != null) { CONFIG.SCROLL_DISTANCE = params.SCROLL_DISTANCE; }
    if (params.SCROLL_DURATION != null) { CONFIG.SCROLL_DURATION = params.SCROLL_DURATION; }
    if (params.SCROLL_AFTER_WAIT != null) { CONFIG.SCROLL_AFTER_WAIT = params.SCROLL_AFTER_WAIT; }
    if (params.NO_NEW_SCROLL_LIMIT != null) { CONFIG.NO_NEW_SCROLL_LIMIT = params.NO_NEW_SCROLL_LIMIT; }
    if (params.FLOAT_LOG_ENABLED != null) { CONFIG.FLOAT_LOG_ENABLED = params.FLOAT_LOG_ENABLED; }
    if (params.DEBUG_PAUSE_ON_ERROR != null) { CONFIG.DEBUG_PAUSE_ON_ERROR = params.DEBUG_PAUSE_ON_ERROR; }
    if (params.ID_TAB != null) { CONFIG.ID_TAB = params.ID_TAB; }
    if (params.ID_IVCOVER != null) { CONFIG.ID_IVCOVER = params.ID_IVCOVER; }
    if (params.ID_TVNAME != null) { CONFIG.ID_TVNAME = params.ID_TVNAME; }
    if (params.ID_HEADER != null) { CONFIG.ID_HEADER = params.ID_HEADER; }
    if (params.ID_CLOSEBTN != null) { CONFIG.ID_CLOSEBTN = params.ID_CLOSEBTN; }
  }
  
  logi("脚本启动");
  
  // 初始化数据库
  try {
    // callScript("DataHandler", "init", dbName)
    callScript("DataHandler", "init", "look_collect");
  } catch (e) {
    loge("初始化数据库失败: " + e);
  }
  
  // 启动App
  var ret = safeLaunchApp();
  if (ret == 0) {
    alert(CONFIG.APP_NAME + " 未安装");
    stop();
    return;
  }
  
  sleepMs(CONFIG.APP_RESTART_WAIT_MS);
  
  // 确保在首页
  if (!ensureHome()) {
    loge("无法回到首页");
    stop();
    return;
  }
  
  goRecommendTab();
  
  // 开始主循环
  mainLoop();
  
  // 关闭数据库
  try {
    // callScript("DataHandler", "close")
    callScript("DataHandler", "close");
  } catch (e) {
    loge("关闭数据库失败: " + e);
  }
  
  logi("脚本结束");
}

// 注意：不要在文件末尾调用 main()
// 通过 callScript("LOOK_StartLiveRoom", CONFIG) 从 LOOK_Main.js 调用时，引擎会自动执行 main()
