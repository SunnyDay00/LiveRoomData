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

  SCROLL_AFTER_WAIT: 800,
  NO_NEW_SCROLL_LIMIT: 6,
  CHAT_TAB_CHECK_RETRY: 3,
  CHAT_TAB_CHECK_WAIT_MS: 800,
  CHAT_TAB_TEXT_MAX_STEP: 4,

  // 直播间卡片点击区域: left=48 top=192 width=984 height=422 (取中心点)
  LIVE_CARD_TAP_X: 540,
  LIVE_CARD_TAP_Y: 403,

  // 下滑进入下一个直播间（大幅度上滑）
  NEXT_SWIPE_START_X: 540,
  NEXT_SWIPE_START_Y: 1700,
  NEXT_SWIPE_END_X: 540,
  NEXT_SWIPE_END_Y: 300,
  NEXT_SWIPE_DURATION: 800,
  
  FLOAT_LOG_ENABLED: 1,
  DEBUG_PAUSE_ON_ERROR: 0,
  
  ID_TAB: "com.netease.play:id/tv_dragon_tab",
  ID_RNVIEW: "com.netease.play:id/rnView",
  ID_ROOMNO: "com.netease.play:id/roomNo",
  ID_LAYOUT_HEADER: "com.netease.play:id/layout_header",
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

function isClickable(v) {
  try {
    if (v != null) {
      if (v.clickable == true) {
        return true;
      }
    }
  } catch (e) {}
  return false;
}

function findActionBarTabParent(textView, allowFallback) {
  if (textView == null) { return null; }
  var p = getParent(textView);
  var depth = 0;
  while (p != null && depth < 10) {
    if (isClickable(p)) { return p; }
    p = getParent(p);
    depth = depth + 1;
  }
  return null;
}

function describeView(v) {
  if (v == null) { return "null"; }
  var cls = "";
  var text = "";
  var id = "";
  var clickable = "";
  var enabled = "";
  var visible = "";
  var left = "";
  var top = "";
  var right = "";
  var bottom = "";
  try { cls = "" + v.className; } catch (e1) {}
  try { text = "" + v.text; } catch (e2) {}
  try { id = "" + v.id; } catch (e3) {}
  try { clickable = "" + v.clickable; } catch (e4) {}
  try { enabled = "" + v.enabled; } catch (e5) {}
  try { visible = "" + v.visible; } catch (e6) {}
  try {
    if (v.left != null) { left = v.left; }
    if (v.top != null) { top = v.top; }
    if (v.right != null) { right = v.right; }
    if (v.bottom != null) { bottom = v.bottom; }
  } catch (e7) {}
  if (right === "" || bottom === "") {
    try {
      var b = v.bounds();
      if (b != null) {
        left = b.left;
        top = b.top;
        right = b.right;
        bottom = b.bottom;
      }
    } catch (e8) {}
  }
  return "class=" + cls + ", text=" + text + ", id=" + id +
    ", clickable=" + clickable + ", enabled=" + enabled + ", visible=" + visible +
    ", bounds=" + left + "," + top + "," + right + "," + bottom;
}

function clickObj(v, stepName) {
  try {
    var ok = click(v, {click: true});
    if (ok) {
      logi("[" + stepName + "] click ok");
      return true;
    }
  } catch (e1) {
    logw("[" + stepName + "] 原生点击异常，尝试手势点击: " + e1);
  }
  try {
    click(v, {click: false});
    logi("[" + stepName + "] 手势点击 ok");
    return true;
  } catch (e2) {
    loge("[" + stepName + "] click exception=" + e2);
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
    // 使用 AdbSwipe 进行滑动 (Shizuku)
    // 大幅度上滑进入下一个直播间
    logi("调用 AdbSwipe 进行滑动...");
    var swipeArgs = CONFIG.NEXT_SWIPE_START_X + "," +
      CONFIG.NEXT_SWIPE_START_Y + "," +
      CONFIG.NEXT_SWIPE_END_X + "," +
      CONFIG.NEXT_SWIPE_END_Y + "," +
      CONFIG.NEXT_SWIPE_DURATION;
    logi("[SWIPE] args=" + swipeArgs);
    var ret = callScript("AdbSwipe", "swipe", swipeArgs);
    
    // 只要没有抛出异常且返回了(即使是undefined), 通常认为尝试过了
    // 如果 AdbSwipe 返回明确的 true/false 更好
    if (ret != false) { 
        ok = true; 
    }
    
    // 滑动后等待页面加载
    sleepMs(CONFIG.SCROLL_AFTER_WAIT);
    
  } catch (e) {
    loge("AdbSwipe 调用异常: " + e);
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

function clickChatTabByText(clickWaitMs) {
  var maxStep = CONFIG.CHAT_TAB_TEXT_MAX_STEP;
  if (maxStep == null || maxStep <= 0) { maxStep = 4; }

  var headerRet = findRet("id:" + CONFIG.ID_LAYOUT_HEADER, {flag: "find_all|traverse_invisible", maxStep: maxStep});
  if (headerRet == null || headerRet.length <= 0) {
    logw("[HOME_TAB_CHAT] 未找到 layout_header");
    return false;
  }
  logi("[HOME_TAB_CHAT] 找到 layout_header 数量=" + headerRet.length);

  var h = 0;
  for (h = 0; h < headerRet.length; h = h + 1) {
    var header = null;
    try { header = headerRet.views[h]; } catch (e0) { header = null; }
    if (header == null) { continue; }

    var ret = findRet("txt:一起聊", {
      root: header,
      flag: "find_all|traverse_invisible",
      maxStep: maxStep
    });
    if (ret == null || ret.length <= 0) { continue; }
    logi("[HOME_TAB_CHAT] header[" + h + "] 一起聊数量=" + ret.length);

    var i = 0;
    for (i = 0; i < ret.length; i = i + 1) {
      var tv = null;
      try { tv = ret.views[i]; } catch (e1) { tv = null; }
      if (tv == null) { continue; }
      var tvText = "" + tv.text;
      if (tvText.indexOf("一起聊") < 0) { continue; }
      logi("[HOME_TAB_CHAT] 候选一起聊[" + h + ":" + i + "] " + describeView(tv));

      var tab = findActionBarTabParent(tv, false);
      if (tab != null) {
        logi("[HOME_TAB_CHAT] 可点击父控件 " + describeView(tab));
        var ok1 = clickObj(tab, "HOME_TAB_CHAT");
        logi("[HOME_TAB_CHAT] 点击父控件结果 ok=" + ok1);
      } else {
        logw("[HOME_TAB_CHAT] 未找到可点击父控件，尝试点击文本");
        var ok2 = clickObj(tv, "HOME_TAB_CHAT_TEXT");
        logi("[HOME_TAB_CHAT] 点击文本结果 ok=" + ok2);
      }
      sleepMs(clickWaitMs);
      var rnOk = isChatTabPage();
      logi("[HOME_TAB_CHAT] 点击后 rnView=" + rnOk);
      if (rnOk) { return true; }
    }
  }

  return false;
}

function isChatTabPage() {
  return hasView("id:" + CONFIG.ID_RNVIEW, {maxStep: 2});
}

function ensureChatTab() {
  if (isChatTabPage()) { return true; }
  var retry = CONFIG.CHAT_TAB_CHECK_RETRY;
  if (retry == null || retry <= 0) { retry = 3; }
  var waitMs = CONFIG.CHAT_TAB_CHECK_WAIT_MS;
  if (waitMs == null || waitMs <= 0) { waitMs = 800; }
  var r = 0;
  for (r = 0; r < retry; r = r + 1) {
    if (clickChatTabByText(CONFIG.CLICK_WAIT_MS)) { return true; }
    if (isChatTabPage()) { return true; }
    logw("切换一起聊失败，重试 " + (r + 1) + "/" + retry);
    sleepMs(waitMs);
  }
  logw("切换一起聊失败，跳过点击直播间");
  return false;
}

// ==============================
// 直播间Key
// ==============================

function getRoomKeyFromLiveRoom() {
  var text = "";
  var i = 0;
  for (i = 0; i < 2; i = i + 1) {
    var ret = findRet("id:" + CONFIG.ID_ROOMNO, {
      flag: "find_all|traverse_invisible",
      maxStep: 6
    });
    if (ret != null && ret.length > 0) {
      var v = null;
      try { v = ret.views[0]; } catch (e1) { v = null; }
      if (v != null) {
        try { if (v.text != null) { text = "" + v.text; } } catch (e2) {}
      }
    }
    text = ("" + text).trim();
    if (text == "null" || text == "undefined") { text = ""; }
    if (text != "") { break; }
    if (i == 0) { sleepMs(200); }
  }
  if (text == "") {
    logw("[ROOMNO] 未读取到 roomNo");
  } else {
    logi("GetRoomKey(roomNo): " + text);
  }
  return text;
}

// ==============================
// 进入直播间
// ==============================
function enterLiveByCard() {
  var r = 0;
  for (r = 0; r < CONFIG.ENTER_LIVE_RETRY; r = r + 1) {
    var clicked = false;
    try {
      // 直接写死坐标，避免参数传递异常
      var x = 540;
      var y = 403;
      logi("[LIVE_CARD_ADB] 使用固定坐标 x=" + x + " y=" + y);
      clicked = callScript("AdbClick", "click", x, y);
    } catch (e) {
      clicked = false;
      loge("[ADB_CLICK] 调用异常: " + e);
    }
    if (!clicked) { logw("点击直播间失败，retry=" + r); }
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
function processOneLive(alreadyInLive) {
  logi("========== 开始处理直播间 ==========");

  // Step 1: 进入直播间
  if (alreadyInLive == true) {
    logi("[STEP_1] 已在直播间，跳过点击进入");
  } else {
    logi("[STEP_1] 尝试进入直播间...");
    if (!enterLiveByCard()) {
      loge("[STEP_1] 进入直播间失败");
      debugPause("STEP_1", "进入直播间失败");
      restartApp("enter_live_failed");
      return "FAIL";
    }
    logi("[STEP_1] 成功进入直播间");
  }

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
    logi("[STEP_2] 直播间无效，跳过");
    return "SKIP";
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
    return "SKIP";
  }
  
  var hostInfo = hostResult.hostInfo;

  if (hostInfo.id == null || ("" + hostInfo.id).trim() == "") {
    logi("[STEP_3] hostInfo.id为空，等待返回直播间后读取roomNo");
    var liveReady = false;
    var retry = 0;
    for (retry = 0; retry < CONFIG.LIVE_ROOM_CHECK_RETRY; retry = retry + 1) {
      if (isLiveRoomPage()) {
        liveReady = true;
        break;
      }
      sleepMs(CONFIG.CLICK_WAIT_MS);
    }

    if (liveReady) {
      var roomNoText = getTextOfFirst("id:com.netease.play:id/roomNo", {maxStep: 5});
      if (roomNoText != null && ("" + roomNoText).trim() != "") {
        hostInfo.id = "" + roomNoText;
        logi("[STEP_3] hostInfo.id为空，使用roomNo=" + hostInfo.id);
      } else {
        logw("[STEP_3] hostInfo.id为空且未找到roomNo");
      }
    } else {
      logw("[STEP_3] 未确认回到直播间，跳过roomNo读取");
    }
  }

  logi("[STEP_3] 主播信息: id=" + hostInfo.id + " name=" + hostInfo.name);

  // Step 3.1: 获取直播间Key并去重
  var roomKey = getRoomKeyFromLiveRoom();
  if (roomKey == null || roomKey == "") {
    if (hostInfo.id != null && ("" + hostInfo.id).trim() != "") {
      roomKey = ("" + hostInfo.id).trim();
      logw("[STEP_3] roomNo为空，使用 hostInfo.id 作为Key=" + roomKey);
    } else if (hostInfo.name != null && ("" + hostInfo.name).trim() != "") {
      roomKey = ("" + hostInfo.name).trim();
      logw("[STEP_3] roomNo为空，使用 hostInfo.name 作为Key=" + roomKey);
    } else {
      roomKey = "UNKNOWN_" + nowStr();
      logw("[STEP_3] roomNo为空，使用兜底Key=" + roomKey);
    }
  }

  logi("[STEP_3] 直播间Key=" + roomKey);

  if (g_seen[roomKey] == true) {
    logw("[STEP_3] 直播间已处理 key=" + roomKey);
    return "SKIP";
  }
  g_seen[roomKey] = true;
  g_liveClickCount = g_liveClickCount + 1;
  logi("选择直播间 key=" + roomKey + " count=" + g_liveClickCount);

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

  logi("========== 完成处理直播间 key=" + roomKey + " ==========");
  return "OK";
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

    // 已在直播间则直接处理，否则回首页+切换一起聊
    var inLive = isLiveRoomPage();
    if (!inLive) {
      if (!isHomePage()) {
        logw("不在首页，尝试回到首页");
        if (!ensureHome()) {
          restartApp("not_home");
        }
      }
      ensureChatTab();
      if (!isChatTabPage()) {
        logw("未在一起聊界面，跳过点击直播间");
        sleepMs(CONFIG.CHAT_TAB_CHECK_WAIT_MS);
        continue;
      }
    }

    // 通过固定坐标点击进入直播间
    var r = processOneLive(inLive);
    if (r == "STOP_DB_MAX") {
      logw("达到数据库上限，停止");
      break;
    }

    if (r == "OK") {
      noNewScroll = 0;
    } else if (r == "SKIP") {
      noNewScroll = noNewScroll + 1;
    } else {
      noNewScroll = noNewScroll + 1;
      logw("处理失败，继续尝试下一个直播间");
    }

    // 滚动进入下一个直播间
    var sc = doScrollDown();
    logi("滚动 ok=" + sc);

    logi("noNewScroll=" + noNewScroll + "/" + CONFIG.NO_NEW_SCROLL_LIMIT);

    if (noNewScroll >= CONFIG.NO_NEW_SCROLL_LIMIT) {
      logw("连续多次无新内容，重启");
      restartApp("no_new_items");
      noNewScroll = 0;
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
    if (params.SCROLL_AFTER_WAIT != null) { CONFIG.SCROLL_AFTER_WAIT = params.SCROLL_AFTER_WAIT; }
    if (params.NO_NEW_SCROLL_LIMIT != null) { CONFIG.NO_NEW_SCROLL_LIMIT = params.NO_NEW_SCROLL_LIMIT; }
    if (params.CHAT_TAB_CHECK_RETRY != null) { CONFIG.CHAT_TAB_CHECK_RETRY = params.CHAT_TAB_CHECK_RETRY; }
    if (params.CHAT_TAB_CHECK_WAIT_MS != null) { CONFIG.CHAT_TAB_CHECK_WAIT_MS = params.CHAT_TAB_CHECK_WAIT_MS; }
    if (params.CHAT_TAB_TEXT_MAX_STEP != null) { CONFIG.CHAT_TAB_TEXT_MAX_STEP = params.CHAT_TAB_TEXT_MAX_STEP; }
    if (params.LIVE_CARD_TAP_X != null) { CONFIG.LIVE_CARD_TAP_X = params.LIVE_CARD_TAP_X; }
    if (params.LIVE_CARD_TAP_Y != null) { CONFIG.LIVE_CARD_TAP_Y = params.LIVE_CARD_TAP_Y; }
    if (params.NEXT_SWIPE_START_X != null) { CONFIG.NEXT_SWIPE_START_X = params.NEXT_SWIPE_START_X; }
    if (params.NEXT_SWIPE_START_Y != null) { CONFIG.NEXT_SWIPE_START_Y = params.NEXT_SWIPE_START_Y; }
    if (params.NEXT_SWIPE_END_X != null) { CONFIG.NEXT_SWIPE_END_X = params.NEXT_SWIPE_END_X; }
    if (params.NEXT_SWIPE_END_Y != null) { CONFIG.NEXT_SWIPE_END_Y = params.NEXT_SWIPE_END_Y; }
    if (params.NEXT_SWIPE_DURATION != null) { CONFIG.NEXT_SWIPE_DURATION = params.NEXT_SWIPE_DURATION; }
    if (params.FLOAT_LOG_ENABLED != null) { CONFIG.FLOAT_LOG_ENABLED = params.FLOAT_LOG_ENABLED; }
    if (params.DEBUG_PAUSE_ON_ERROR != null) { CONFIG.DEBUG_PAUSE_ON_ERROR = params.DEBUG_PAUSE_ON_ERROR; }
    if (params.ID_TAB != null) { CONFIG.ID_TAB = params.ID_TAB; }
    if (params.ID_RNVIEW != null) { CONFIG.ID_RNVIEW = params.ID_RNVIEW; }
    if (params.ID_ROOMNO != null) { CONFIG.ID_ROOMNO = params.ID_ROOMNO; }
    if (params.ID_LAYOUT_HEADER != null) { CONFIG.ID_LAYOUT_HEADER = params.ID_LAYOUT_HEADER; }
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
