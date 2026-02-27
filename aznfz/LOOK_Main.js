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
  GKD_PKG: "hello.litiaotiao.app-1", // 李跳跳（主包名）
  GKD_PKG_LIST: ["hello.litiaotiao.app-1", "hello.litiaotiao.app"], // 李跳跳候选包名（如多开/分身）
  AZNFZ_PKG: "com.libra.aznfz", // 冰狐智能辅助包名（客户端）

  LOOP_LIVE_TOTAL: 0, // 0=无限；>0=点击直播间总次数
  STOP_AFTER_ROWS: 20000, // 写入多少条停止（安全停止）

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
  NO_NEW_SCROLL_LIMIT: 4, // 连续无新卡片次数上限

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

  // 一起聊列表扫描配置（UI 树遍历点击）
  LIST_DUMP_PATH: "/sdcard/uidump.xml",
  LIST_TARGET_CLASS: "android.view.ViewGroup",
  LIST_TARGET_PACKAGE: "com.netease.play",
  LIST_TARGET_CONTENT_DESC: "",
  LIST_TARGET_CHECKABLE: "false",
  LIST_TARGET_CHECKED: "false",
  LIST_TARGET_CLICKABLE: "true",
  DATA_ROOT_DIR: "/storage/emulated/0/LiveRoomData",
  DEDUP_SUB_DIR: "records",
  DEDUP_FILE_NAME: "live_room_dedup_state.txt",
  RECOLLECT_INTERVAL_HOURS: 12,
  PROCESSED_ROOMS_FILE_PATH: "/storage/emulated/0/LiveRoomData/runtime/processed_rooms.txt",
  LIST_SWIPE_START_X: 540,
  LIST_SWIPE_START_Y: 1700,
  LIST_SWIPE_END_X: 540,
  LIST_SWIPE_END_Y: 400,
  LIST_SWIPE_DURATION: 800,
  LIST_SWIPE_AFTER_WAIT_MS: 1200,
  LIST_DUMP_WAIT_MS: 300,
  LIST_DUMP_STABLE_WAIT_MS: 1200,
  LIST_PARSE_LOG_EVERY: 500,
  LIST_PARSE_MAX_MATCHES: 50000,
  LIST_PARSE_TIMEOUT_MS: 8000,

  FLOAT_LOG_ENABLED: 1, // 悬浮日志开关（1=开，0=关）
  DEBUG_PAUSE_ON_ERROR: 1, // 错误暂停开关（1=开，0=关）
  MONITOR_CHECK_INTERVAL: 10000, // 崩溃监控检查间隔(ms)

  SHIZUKU_PKG: "moe.shizuku.privileged.api", // Shizuku 包名
  RETRY_COUNT: 5, // Shizuku 连接重试次数
  RETRY_INTERVAL: 2000, // Shizuku 重试间隔(ms)
  PERMISSION_TIMEOUT: 10000, // Shizuku 权限请求超时(ms)
  USE_SHIZUKU_RESTART: 1, // 1=使用Shizuku强制关闭/启动（重启流程）
  USE_SHIZUKU_DUMP: 1, // 1=使用Shizuku进行UI树dump
  MAIN_POPUP_HANDLER_ENABLED: 0, // 1=强制主脚本检查广告（即使App已在前台）
  MAIN_LAUNCHED_APP: 0, // 运行时标记：是否由主脚本拉起App（自动覆盖）
  POPUP_FULLSCREEN_BACK_MAX_RETRY: 8, // 全屏广告 back 最大重试次数
  POPUP_FULLSCREEN_BACK_WAIT_MS: 800, // 全屏广告 back 重试间隔
  POPUP_SCAN_WAIT_MS: 600, // 多轮弹窗扫描间隔
  POPUP_SCAN_AFTER_ENTER_ROUNDS: 3, // 刚进入直播间后扫描轮数
  POPUP_SCAN_AFTER_HOST_ROUNDS: 2, // 采集主播信息返回直播间后扫描轮数
  POPUP_SCAN_DURING_RUN_ROUNDS: 1, // 运行过程中周期扫描轮数

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
var g_forceLaunchApp = 0;
var g_needWaitAfterStart = 0;
var COUNT_DIR = "/storage/emulated/0/LiveRoomData/runtime";
var COUNT_FILE_PATH = "/storage/emulated/0/LiveRoomData/runtime/datahandler_count.txt";
var PROCESSED_ROOMS_DIR = "/storage/emulated/0/LiveRoomData/runtime";
var PROCESSED_ROOMS_FILE_PATH = "/storage/emulated/0/LiveRoomData/runtime/processed_rooms.txt";

// ==============================
// 工具函数
// ==============================
function ensureDir(path) {
  try {
    var dir = new FileX(path);
    if (!dir.exists()) {
      dir.makeDirs();
    }
  } catch (e) {
    logw("创建目录失败: " + e);
  }
}

function initCountFile() {
  try {
    ensureDir(COUNT_DIR);
    var f = new FileX(COUNT_FILE_PATH);
    f.write("0");
    logi("初始化计数文件: " + COUNT_FILE_PATH);
    return true;
  } catch (e) {
    logw("初始化计数文件失败: " + e);
    return false;
  }
}

function normalizeRuntimeField(text) {
  if (text == null) { return ""; }
  var s = ("" + text).trim();
  if (s == "" || s == "null" || s == "undefined") {
    return "";
  }
  var out = "";
  var i = 0;
  for (i = 0; i < s.length; i = i + 1) {
    var c = s.charAt(i);
    if (c == "\n" || c == "\r" || c == "\t") {
      out = out + " ";
    } else {
      out = out + c;
    }
  }
  return out.trim();
}

function containsString(arr, key) {
  if (arr == null) { return false; }
  var i = 0;
  for (i = 0; i < arr.length; i = i + 1) {
    if (("" + arr[i]) == ("" + key)) {
      return true;
    }
  }
  return false;
}

function initProcessedRoomsFile() {
  try {
    ensureDir(PROCESSED_ROOMS_DIR);
    var path = PROCESSED_ROOMS_FILE_PATH;
    var oldFile = new FileX(path);
    if (oldFile != null && oldFile.exists()) {
      var rmOk = false;
      try {
        rmOk = oldFile.remove();
      } catch (e1) {
        rmOk = false;
      }
      if (rmOk) {
        logi("已删除旧直播间记录文件: " + path);
      } else {
        logw("删除旧直播间记录文件失败，继续新建: " + path);
      }
    }

    var f = new FileX(path);
    f.write("");
    logi("初始化本次运行直播间记录文件(已新建): " + path);
    return true;
  } catch (e) {
    logw("初始化本次运行直播间记录文件失败: " + e);
    return false;
  }
}

function readProcessedRoomsFile() {
  var out = {count: 0, rooms: []};
  try {
    ensureDir(PROCESSED_ROOMS_DIR);
    var f = new FileX(PROCESSED_ROOMS_FILE_PATH);
    if (f == null || !f.exists()) {
      return out;
    }
    var text = f.read();
    if (text == null) {
      return out;
    }
    var s = "" + text;
    var lines = s.split("\n");
    var seen = [];
    var i = 0;
    for (i = 0; i < lines.length; i = i + 1) {
      var line = ("" + lines[i]).trim();
      if (line == "") { continue; }
      var tabIdx = line.indexOf("\t");
      var hostId = "";
      var hostName = "";
      if (tabIdx >= 0) {
        hostId = normalizeRuntimeField(line.substring(0, tabIdx));
        hostName = normalizeRuntimeField(line.substring(tabIdx + 1));
      } else {
        hostId = normalizeRuntimeField(line);
      }
      var key = hostId + "|" + hostName;
      if (containsString(seen, key)) { continue; }
      seen.push(key);
      out.rooms.push({id: hostId, name: hostName});
    }
    out.count = out.rooms.length;
    return out;
  } catch (e) {
    logw("读取本次运行直播间记录文件失败: " + e);
    return {count: -1, rooms: []};
  }
}

function buildRoomsTextForDing(rooms) {
  if (rooms == null || rooms.length <= 0) {
    return "本次采集直播间列表:\n无";
  }
  var lines = [];
  lines.push("本次采集直播间列表:");
  var i = 0;
  for (i = 0; i < rooms.length; i = i + 1) {
    var item = rooms[i];
    var hostId = "";
    var hostName = "";
    if (item != null) {
      hostId = normalizeRuntimeField(item.id);
      hostName = normalizeRuntimeField(item.name);
    }
    lines.push((i + 1) + ". ID=" + hostId + " 昵称=" + hostName);
  }
  return lines.join("\n");
}

function buildSummaryTextForBot(statusTitle, roomCount, writeCount, runStartAt, runEndAt, runDurationText, stopReasonText) {
  var lines = [];
  if (statusTitle != null && ("" + statusTitle).trim() != "") {
    lines.push(statusTitle);
  }
  if (stopReasonText != null && ("" + stopReasonText).trim() != "") {
    lines.push("停止原因: " + stopReasonText);
  }
  if (roomCount >= 0) { lines.push("采集直播间数量: " + roomCount); }
  if (writeCount >= 0) { lines.push("写入数据行数: " + writeCount); }
  lines.push("开始运行时间: " + runStartAt);
  lines.push("结束运行时间: " + runEndAt);
  lines.push("运行时长: " + runDurationText);
  return lines.join("\n");
}

function parseRoomCountFromScriptResult(result) {
  var roomCount = -1;
  if (result == null) {
    return roomCount;
  }
  var rs = ("" + result).trim();
  var isNum = (rs != "" && rs != "null" && rs != "undefined");
  var i = 0;
  var n = 0;
  for (i = 0; i < rs.length; i = i + 1) {
    var c = rs.charAt(i);
    if (c < "0" || c > "9") {
      isNum = false;
      break;
    }
    n = n * 10 + (c.charCodeAt(0) - 48);
  }
  if (isNum) {
    roomCount = n;
  }
  return roomCount;
}

function syncProcessedRoomsPathFromConfig() {
  var p = normalizeRuntimeField(CONFIG.PROCESSED_ROOMS_FILE_PATH);
  if (p == "") {
    p = "/storage/emulated/0/LiveRoomData/runtime/processed_rooms.txt";
  }
  PROCESSED_ROOMS_FILE_PATH = p;
  var idx = p.lastIndexOf("/");
  if (idx > 0) {
    PROCESSED_ROOMS_DIR = p.substring(0, idx);
  } else {
    PROCESSED_ROOMS_DIR = "/storage/emulated/0/LiveRoomData/runtime";
  }
}

function backToAznfz() {
  var target = CONFIG.AZNFZ_PKG;
  if (ensureShizukuReady()) {
    execShizuku("input keyevent 3");
    sleepMs(800);
    execShizuku("am start -a android.intent.action.MAIN -c android.intent.category.HOME");
    sleepMs(800);
    execShizuku("monkey -p " + target + " -c android.intent.category.LAUNCHER 1");
    sleepMs(800);
  } else {
    try { refresh({packageName: target}); } catch (e1) {}
    try { launchApp(target, ""); } catch (e2) {}
    sleepMs(800);
  }
  try {
    var cur = getCurPackageName();
    if (cur == target) { return true; }
    logw("返回 AZNFZ 失败，当前包: " + cur + ", target=" + target);
  } catch (e3) {}
  return false;
}

function nowStr() { 
  // 获取UTC时间戳,然后加上北京时间偏移(UTC+8小时)
  var utcTime = new Date().getTime();
  var beijingOffset = 8 * 60 * 60 * 1000; // 8小时转换为毫秒
  return "" + (utcTime + beijingOffset);
}

function nowMs() {
  var t = 0;
  try {
    t = new Date().getTime();
  } catch (e) {
    t = 0;
  }
  return t;
}

function pad2(n) {
  if (n < 10) {
    return "0" + n;
  }
  return "" + n;
}

function pad3(n) {
  if (n < 10) {
    return "00" + n;
  }
  if (n < 100) {
    return "0" + n;
  }
  return "" + n;
}

function civilFromDays(z) {
  var z2 = z + 719468;
  var era = Math.floor(z2 / 146097);
  var doe = z2 - era * 146097;
  var yoe = Math.floor((doe - Math.floor(doe / 1460) + Math.floor(doe / 36524) - Math.floor(doe / 146096)) / 365);
  var y = yoe + era * 400;
  var doy = doe - (365 * yoe + Math.floor(yoe / 4) - Math.floor(yoe / 100));
  var mp = Math.floor((5 * doy + 2) / 153);
  var d = doy - Math.floor((153 * mp + 2) / 5) + 1;
  var m = mp + (mp < 10 ? 3 : -9);
  y = y + (m <= 2 ? 1 : 0);
  return {y: y, m: m, d: d};
}

function formatBeijingDateTimeFromUtcMs(utcMs) {
  var ms = utcMs;
  if (ms == null) {
    ms = 0;
  }
  var beijingOffset = 8 * 60 * 60 * 1000;
  var localMs = ms + beijingOffset;

  var dayMs = 86400000;
  var days = Math.floor(localMs / dayMs);
  var remain = localMs - days * dayMs;
  if (remain < 0) {
    remain = remain + dayMs;
    days = days - 1;
  }

  var ymd = civilFromDays(days);
  var hh = Math.floor(remain / 3600000);
  var mm = Math.floor((remain % 3600000) / 60000);

  return ymd.y + "-" + pad2(ymd.m) + "-" + pad2(ymd.d) + " " +
    pad2(hh) + ":" + pad2(mm);
}

function nowReadableStr() {
  return formatBeijingDateTimeFromUtcMs(nowMs());
}

function formatDurationMs(durationMs) {
  var d = durationMs;
  if (d == null || d < 0) {
    d = 0;
  }
  var totalMin = Math.floor(d / 60000);
  var h = Math.floor(totalMin / 60);
  var m = totalMin % 60;
  if (h > 0) {
    return h + "小时" + m + "分钟";
  }
  return m + "分钟";
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
// 李跳跳 运行状态检查
// ==============================
function isPackageRunningByShizuku(packageName) {
  if (!ensureShizukuReady()) {
    return null;
  }

  try {
    var ret = shizuku.execCmd("pidof " + packageName);
    try { shizuku.close(); } catch (e1) {}
    if (ret == null) { return null; }
    var s = ("" + ret);
    if (s.indexOf("not found") >= 0 || s.indexOf("No such") >= 0) { return false; }
    var i = 0;
    for (i = 0; i < s.length; i = i + 1) {
      var c = s.charAt(i);
      if (c >= "0" && c <= "9") { return true; }
    }
    return false;
  } catch (e) {
    logw("[SHIZUKU] pidof 异常: " + e);
    return null;
  }
}

function ensureShizukuReady() {
  if (CONFIG.USE_SHIZUKU_RESTART != 1 && CONFIG.USE_SHIZUKU_DUMP != 1) {
    return false;
  }

  try {
    shizuku.init();
  } catch (e) {
    logw("[SHIZUKU] init 异常: " + e);
    return false;
  }

  var i = 0;
  var connected = false;
  for (i = 0; i < CONFIG.RETRY_COUNT; i = i + 1) {
    if (shizuku.connect()) {
      connected = true;
      break;
    }
    sleepMs(CONFIG.RETRY_INTERVAL);
  }

  if (!connected) {
    logw("[SHIZUKU] 服务未启动或连接失败");
    return false;
  }

  if (!shizuku.checkPermission()) {
    shizuku.requestPermission(CONFIG.PERMISSION_TIMEOUT);
    if (!shizuku.checkPermission()) {
      logw("[SHIZUKU] 未获得授权");
      return false;
    }
  }

  return true;
}

function execShizuku(cmd) {
  try {
    shizuku.execCmd(cmd);
    return true;
  } catch (e) {
    logw("[SHIZUKU] execCmd 异常: " + e);
    return false;
  }
}

function forceStopAppByAdb() {
  if (ensureShizukuReady()) {
    var cmd = "am force-stop " + CONFIG.APP_PKG;
    logi("[ADB] force-stop: " + cmd);
    return execShizuku(cmd);
  }
  logw("[ADB] Shizuku 不可用，回退 killBackgroundApp");
  try {
    killBackgroundApp(CONFIG.APP_PKG);
    return true;
  } catch (e) {
    return false;
  }
}

function stopAppIfRunningAtStart() {
  var running = false;
  try {
    if (getCurPackageName() == CONFIG.APP_PKG) {
      running = true;
    }
  } catch (e) {}

  if (!running) {
    var r = isPackageRunningByShizuku(CONFIG.APP_PKG);
    if (r === true) { running = true; }
  }

  if (!running) {
    logi("未检测到 " + CONFIG.APP_NAME + " 正在运行，无需强制关闭");
    return true;
  }

  logw("检测到 " + CONFIG.APP_NAME + " 正在运行，执行 ADB 强制关闭");
  var killed = forceStopAppByAdb();
  logi("强制关闭结果 ok=" + killed);
  if (!killed) {
    logw("强制关闭失败，将继续尝试拉起应用");
  }
  g_forceLaunchApp = 1;
  return true;
}

function checkGkdRunning() {
  logi("检查 李跳跳 运行状态...");

  var pkgs = CONFIG.GKD_PKG_LIST;
  if (pkgs == null || pkgs.length == 0) {
    pkgs = [CONFIG.GKD_PKG];
  }

  // 前台快速判定
  try {
    var cur = getCurPackageName();
    var i0 = 0;
    for (i0 = 0; i0 < pkgs.length; i0 = i0 + 1) {
      if (cur == pkgs[i0]) {
        CONFIG.GKD_PKG = pkgs[i0];
        logi("检测到 李跳跳 在前台运行: " + CONFIG.GKD_PKG);
        return true;
      }
    }
  } catch (e) {}

  // Shizuku 进程检查
  var i1 = 0;
  for (i1 = 0; i1 < pkgs.length; i1 = i1 + 1) {
    var running = isPackageRunningByShizuku(pkgs[i1]);
    if (running === true) {
      CONFIG.GKD_PKG = pkgs[i1];
      logi("检测到 李跳跳 进程在运行: " + CONFIG.GKD_PKG);
      return true;
    }
  }

  if (isPackageRunningByShizuku(pkgs[0]) === null) {
    logw("无法通过 Shizuku 判断 李跳跳 是否运行，尝试启动");
  } else {
    logw("未检测到 李跳跳 进程运行，尝试启动");
  }

  // 尝试启动李跳跳（无论是否已在后台，启动不会影响后续逻辑）
  var started = false;
  var i2 = 0;
  for (i2 = 0; i2 < pkgs.length; i2 = i2 + 1) {
    try {
      var ret = launchApp(pkgs[i2], "");
      if (ret == 0) {
        continue;
      }
      if (ret == 1) {
        CONFIG.GKD_PKG = pkgs[i2];
        started = true;
        break;
      }
    } catch (e3) {
      loge("启动李跳跳异常: " + e3);
    }
  }

  if (started) {
    logi("已尝试启动李跳跳，继续执行: " + CONFIG.GKD_PKG);
    return true;
  }

  try { refresh({packageName: CONFIG.AZNFZ_PKG}); } catch (e2) {}
  loge("李跳跳未安装或包名不匹配: " + pkgs);
  alert("李跳跳未安装或包名不匹配，请确认包名。");
  return false;
}

// ==============================
// 软件状态检查
// ==============================
function checkAppRunning() {
  logi("检查软件运行状态...");

  if (g_forceLaunchApp != 1 && isAppForeground()) {
    logi("检测到App已在前台运行");
    g_mainLaunchedApp = 0;
    g_needWaitAfterStart = 0;
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
    g_forceLaunchApp = 0;
    g_needWaitAfterStart = 1;
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

  var runStartAt = nowReadableStr();
  var runStartMs = nowMs();

  logi("========== LOOK直播数据采集脚本启动 ==========");
  logi("脚本开始运行时间: " + runStartAt);

  // Step -1: 如果 App 已在运行，先通过 ADB 强制关闭
  stopAppIfRunningAtStart();

  // Step 0: 检查 李跳跳 是否运行
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

  if (g_needWaitAfterStart == 1) {
    sleepMs(CONFIG.APP_RESTART_WAIT_MS);
  }
  
  // Step 2.1: 处理开屏广告/弹窗（已移除 PopupHandler 调用）
  CONFIG.MAIN_LAUNCHED_APP = g_mainLaunchedApp;
  syncProcessedRoomsPathFromConfig();

  // 初始化写入计数文件（每次运行清零）
  initCountFile();
  // 初始化本次运行直播间记录文件（每次运行清空）
  initProcessedRoomsFile();

  // Step 3: 崩溃监控（已禁用）
  // 由于 currentPackage() 函数在此引擎中不可用，监控功能无法正常工作
  // 如需启用，需要找到引擎支持的前台检测方法
  // startMonitorThread();
  logi("崩溃监控已禁用（currentPackage不可用）");

  logi("调用 LOOK_StartLiveRoom...");
  var result = null;
  try {
    result = callScript("LOOK_StartLiveRoom", CONFIG);
    logi("LOOK_StartLiveRoom 执行完成: " + result);
  } catch (e) {
    loge("callScript LOOK_StartLiveRoom 异常: " + e);
  }

  var uploadFailedStopped = false;
  var stopReasonCode = "";
  var stopReasonText = "";
  if (result != null && typeof result === "object") {
    if (result.stopReason != null) {
      stopReasonCode = "" + result.stopReason;
    }
    if (stopReasonCode == "UPLOAD_WRITE_FAILED") {
      uploadFailedStopped = true;
      stopReasonText = "上传写入失败";
    } else {
      stopReasonText = stopReasonCode;
    }
  }

  // 统计采集直播间数量（优先读取本次运行记录文件）
  var roomsInfo = readProcessedRoomsFile();
  var roomCount = roomsInfo.count;
  if (roomCount < 0) {
    roomCount = parseRoomCountFromScriptResult(result);
  }

  // 统计写入数据行数（来自 DataHandler 计数）
  var writeCount = -1;
  try {
    writeCount = callScript("DataHandler", "getCount");
  } catch (e2) {
    logw("获取写入数量失败: " + e2);
    writeCount = -1;
  }

  // 切回 AZNFZ_PKG 并提示完成
  var runEndAt = nowReadableStr();
  var runEndMs = nowMs();
  var runDurationMs = runEndMs - runStartMs;
  if (runDurationMs < 0) {
    runDurationMs = 0;
  }
  var runDurationText = formatDurationMs(runDurationMs);

  backToAznfz();
  var doneMsg = "采集已完成";
  var statusTitle = "采集任务已完成";
  if (uploadFailedStopped) {
    doneMsg = "数据上传写入失败，脚本已停止运行";
    statusTitle = "【告警】数据上传写入失败，脚本已停止运行";
  }
  if (roomCount >= 0) { doneMsg = doneMsg + "\n\n采集直播间数量: " + roomCount; }
  if (writeCount >= 0) { doneMsg = doneMsg + "\n写入数据行数: " + writeCount; }
  doneMsg = doneMsg + "\n开始运行时间: " + runStartAt;
  doneMsg = doneMsg + "\n结束运行时间: " + runEndAt;
  doneMsg = doneMsg + "\n运行时长: " + runDurationText;
  var dingMsg = buildSummaryTextForBot(statusTitle, roomCount, writeCount, runStartAt, runEndAt, runDurationText, stopReasonText);
  dingMsg = dingMsg + "\n\n" + buildRoomsTextForDing(roomsInfo.rooms);
  try {
    callScript("DingTalkBot", dingMsg);
  } catch (e5) {
    logw("DingTalkBot 发送失败: " + e5);
  }
  alert(doneMsg);

  logi("========== LOOK直播数据采集脚本结束 ==========");
  try { stop(); } catch (e6) {}
}
