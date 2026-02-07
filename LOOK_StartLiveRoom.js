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
  RESTART_TO_CHAT_RETRY: 2,
  HOME_ENSURE_RETRY: 4,
  LIVE_ROOM_CHECK_RETRY: 3,
  
  CONTRIB_CLICK_COUNT: 5,

  SCROLL_AFTER_WAIT: 800,
  NO_NEW_SCROLL_LIMIT: 4,
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

  // 一起聊列表扫描配置（UI 树遍历点击）
  LIST_DUMP_PATH: "/sdcard/uidump.xml",
  LIST_TARGET_CLASS: "android.view.ViewGroup",
  LIST_TARGET_PACKAGE: "com.netease.play",
  LIST_TARGET_CONTENT_DESC: "",
  LIST_TARGET_CHECKABLE: "false",
  LIST_TARGET_CHECKED: "false",
  LIST_TARGET_CLICKABLE: "true",
  LIST_CLICKED_KEYS_DIR: "/sdcard/homekey",
  LIST_CLICKED_KEYS_FILE_PREFIX: "LOOK_clicked_rooms_",
  LIST_CLICKED_KEYS_FILE_SUFFIX: ".txt",
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
  USE_SHIZUKU_DUMP: 1,
  
  FLOAT_LOG_ENABLED: 1,
  DEBUG_PAUSE_ON_ERROR: 0,

  USE_SHIZUKU_RESTART: 1, // 1=使用Shizuku强制关闭/启动
  SHIZUKU_PKG: "moe.shizuku.privileged.api",
  RETRY_COUNT: 5,
  RETRY_INTERVAL: 2000,
  PERMISSION_TIMEOUT: 10000,
  MAIN_LAUNCHED_APP: 0, // 运行时标记：是否由主脚本拉起App
  
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
var g_seen = [];
var g_liveClickCount = 0;
var g_homeKeyDate = "";
var g_homeKeyFile = null;
var g_homeKeyData = null;
var g_lastRoomKey = "";
var g_skipRestartOnce = false;
var g_listClickedPath = "";
var g_listClickedCount = 0;
var g_listClickedKeyList = [];
var g_listClickedAllList = [];

// ==============================
// 工具函数
// ==============================
function nowStr() { 
  // 获取UTC时间戳,然后加上北京时间偏移(UTC+8小时)
  var utcTime = new Date().getTime();
  var beijingOffset = 8 * 60 * 60 * 1000; // 8小时转换为毫秒
  return "" + (utcTime + beijingOffset);
}

function pad2(n) {
  return (n < 10 ? "0" : "") + n;
}

function civilFromDays(z) {
  // 算法: Howard Hinnant civil_from_days
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

function dateStrYmd() {
  // 避免使用 getUTC* 等方法（在部分环境不可用）
  var utcTime = new Date().getTime();
  var beijingOffset = 8 * 60 * 60 * 1000;
  var ms = utcTime + beijingOffset;
  var days = Math.floor(ms / 86400000);
  var ymd = civilFromDays(days);
  return "" + ymd.y + pad2(ymd.m) + pad2(ymd.d);
}

function escapeJsonString(s) {
  var out = "";
  var i = 0;
  for (i = 0; i < s.length; i = i + 1) {
    var c = s.charAt(i);
    if (c == "\\") { out = out + "\\\\"; }
    else if (c == "\"") { out = out + "\\\""; }
    else if (c == "\n") { out = out + "\\n"; }
    else if (c == "\r") { out = out + "\\r"; }
    else if (c == "\t") { out = out + "\\t"; }
    else { out = out + c; }
  }
  return out;
}

function containsKey(arr, key) {
  if (arr == null) { return false; }
  var i = 0;
  for (i = 0; i < arr.length; i = i + 1) {
    if (("" + arr[i]) == ("" + key)) {
      return true;
    }
  }
  return false;
}

function parseKeysFromJson(text) {
  var keys = [];
  if (text == null) { return keys; }
  var t = ("" + text);
  var idx = t.indexOf("\"keys\"");
  if (idx < 0) { return keys; }
  var start = t.indexOf("[", idx);
  if (start < 0) { return keys; }
  var end = t.indexOf("]", start);
  if (end < 0) { return keys; }
  var arrText = t.substring(start + 1, end);
  var i = 0;
  var inStr = false;
  var esc = false;
  var cur = "";
  for (i = 0; i < arrText.length; i = i + 1) {
    var c = arrText.charAt(i);
    if (!inStr) {
      if (c == "\"") {
        inStr = true;
        cur = "";
      }
      continue;
    }
    if (esc) {
      cur = cur + c;
      esc = false;
      continue;
    }
    if (c == "\\") {
      esc = true;
      continue;
    }
    if (c == "\"") {
      if (!containsKey(keys, cur)) {
        keys.push(cur);
      }
      inStr = false;
      continue;
    }
    cur = cur + c;
  }
  return keys;
}

function buildHomeKeyJson(data) {
  var parts = [];
  var i = 0;
  for (i = 0; i < data.keys.length; i = i + 1) {
    parts.push("\"" + escapeJsonString("" + data.keys[i]) + "\"");
  }
  var keysPart = parts.join(",");
  return "{\"date\":\"" + data.date + "\",\"updatedAt\":\"" + data.updatedAt + "\",\"keys\":[" + keysPart + "]}";
}

function buildHomeKeyFileName(dateStr) {
  var d = dateStr;
  if (d == null || d == "") { d = dateStrYmd(); }
  var dir = "/storage/emulated/0/homekey";
  ensureDir(dir);
  return dir + "/homekey_" + d + ".json";
}

function ensureDir(path) {
  try {
    var dir = new FileX(path);
    if (!dir.exists()) {
      dir.makeDirs();
    }
  } catch (e) {
    logw("[HOMEKEY] 创建目录失败: " + e);
  }
}

function loadHomeKeyData(file) {
  if (!file.exists()) {
    return {
      date: dateStrYmd(),
      updatedAt: nowStr(),
      keys: []
    };
  }
  var txt = "";
  try {
    txt = file.read();
  } catch (e) {
    logw("[HOMEKEY] 读取文件失败: " + e);
    return {
      date: dateStrYmd(),
      updatedAt: nowStr(),
      keys: []
    };
  }
  return {
    date: dateStrYmd(),
    updatedAt: nowStr(),
    keys: parseKeysFromJson(txt)
  };
}

function writeHomeKeyData(file, data) {
  var content = "";
  try {
    content = buildHomeKeyJson(data);
  } catch (e) {
    logw("[HOMEKEY] JSON 序列化失败: " + e);
    return false;
  }
  try {
    // 依据 文档 文件.md: FileX.write 写入字符串
    file.write(content);
    return true;
  } catch (e2) {
    logw("[HOMEKEY] 写入文件失败: " + e2);
    return false;
  }
}

function initHomeKeyStore() {
  var dateStr = dateStrYmd();
  g_homeKeyDate = dateStr;
  g_homeKeyFile = new FileX(buildHomeKeyFileName(dateStr));
  g_homeKeyData = loadHomeKeyData(g_homeKeyFile);
  if (g_homeKeyData == null || g_homeKeyData.keys == null) {
    g_homeKeyData = {date: dateStr, updatedAt: nowStr(), keys: []};
  }
  g_seen = g_homeKeyData.keys;
  logi("[HOMEKEY] file=" + g_homeKeyFile.getPath() + " keys=" + g_seen.length);
}

function ensureHomeKeyStore() {
  var curDate = dateStrYmd();
  if (g_homeKeyDate == null || g_homeKeyDate == "" || g_homeKeyFile == null || g_homeKeyData == null) {
    initHomeKeyStore();
    return;
  }
  if (curDate != g_homeKeyDate) {
    logi("[HOMEKEY] 日期切换 " + g_homeKeyDate + " -> " + curDate);
    initHomeKeyStore();
  }
}

function addHomeKey(key) {
  if (key == null || ("" + key).trim() == "") { return false; }
  ensureHomeKeyStore();
  if (containsKey(g_seen, key)) { return false; }
  g_seen.push("" + key);
  g_homeKeyData.updatedAt = nowStr();
  var ok = writeHomeKeyData(g_homeKeyFile, g_homeKeyData);
  if (!ok) {
    logw("[HOMEKEY] 写入失败 key=" + key);
  }
  return ok;
}

// ==============================
// 一起聊列表卡片去重
// ==============================
function buildListClickedKeysFilePath() {
  var d = dateStrYmd();
  return CONFIG.LIST_CLICKED_KEYS_DIR + "/" +
    CONFIG.LIST_CLICKED_KEYS_FILE_PREFIX + d + CONFIG.LIST_CLICKED_KEYS_FILE_SUFFIX;
}

function loadListClickedKeys(path) {
  var allList = [];
  var keyList = [];
  try {
    var f = new FileX(path);
    if (f != null && f.exists()) {
      var text = f.read();
      if (text != null) {
        var s = "" + text;
        var lines = s.split("\n");
        var i = 0;
        for (i = 0; i < lines.length; i = i + 1) {
          var line = ("" + lines[i]).trim();
          if (line != "") {
            if (!containsKey(allList, line)) {
              allList.push(line);
              if (line.indexOf("KEY:") == 0) { keyList.push(line); }
            }
          }
        }
      }
    }
  } catch (e) {
    logw("[LIST_CLICKED] 读取记录失败: " + e);
  }
  return {allList: allList, keyList: keyList, count: allList.length};
}

function appendListClickedKey(path, key) {
  if (key == null) { return; }
  var k = ("" + key).trim();
  if (k == "") { return; }
  try {
    var f = new FileX(path);
    f.append(k + "\n");
  } catch (e) {
    logw("[LIST_CLICKED] 写入失败: " + e);
  }
}

function initListClickedStore() {
  ensureDir(CONFIG.LIST_CLICKED_KEYS_DIR);
  g_listClickedPath = buildListClickedKeysFilePath();
  var loaded = loadListClickedKeys(g_listClickedPath);
  g_listClickedAllList = (loaded.allList != null ? loaded.allList : []);
  g_listClickedKeyList = (loaded.keyList != null ? loaded.keyList : []);
  g_listClickedCount = loaded.count;
  logi("[LIST_CLICKED] file=" + g_listClickedPath + " keys=" + g_listClickedCount);
}

function markListClicked(key) {
  if (key == null) { return false; }
  var k = ("" + key).trim();
  if (k == "") { return false; }
  if (containsKey(g_listClickedAllList, k)) { return false; }
  g_listClickedAllList.push(k);
  appendListClickedKey(g_listClickedPath, k);
  g_listClickedCount = g_listClickedCount + 1;
  if (k.indexOf("KEY:") == 0) { g_listClickedKeyList.push(k); }
  return true;
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
// 一起聊列表：UI 树 dump + XML 解析
// ==============================
function isValidUiXml(xml) {
  if (xml == null) { return false; }
  var s = "" + xml;
  if (s.indexOf("<hierarchy") < 0) { return false; }
  if (s.indexOf("<node") < 0) { return false; }
  return true;
}

function readDumpXmlWithRetry(path, retryCount, waitMs) {
  var i = 0;
  for (i = 0; i < retryCount; i = i + 1) {
    var f = new FileX(path);
    if (f != null && f.exists()) {
      var size = f.size();
      if (size > 0) {
        var xml = f.read();
        if (isValidUiXml(xml)) { return "" + xml; }
      }
    }
    sleepMs(waitMs);
  }
  return null;
}

function dumpChatListUiXml() {
  if (CONFIG.USE_SHIZUKU_DUMP != 1) {
    logw("[LIST_DUMP] 禁用 Shizuku dump");
    return null;
  }
  if (!ensureShizukuReady()) {
    logw("[LIST_DUMP] Shizuku 未就绪");
    return null;
  }
  try {
    var cmd = "uiautomator dump --compressed " + CONFIG.LIST_DUMP_PATH;
    logi("[LIST_DUMP] " + cmd);
    execShizuku(cmd);
  } catch (e) {
    logw("[LIST_DUMP] dump 异常: " + e);
  }
  sleepMs(CONFIG.LIST_DUMP_STABLE_WAIT_MS);
  var xml = readDumpXmlWithRetry(CONFIG.LIST_DUMP_PATH, 10, CONFIG.LIST_DUMP_WAIT_MS);
  if (!isValidUiXml(xml)) {
    logw("[LIST_DUMP] 主路径失败，尝试默认路径");
    execShizuku("uiautomator dump --compressed");
    xml = readDumpXmlWithRetry("/sdcard/window_dump.xml", 10, CONFIG.LIST_DUMP_WAIT_MS);
  }
  if (!isValidUiXml(xml)) {
    logw("[LIST_DUMP] 获取 XML 失败");
    return null;
  }
  return "" + xml;
}

function parseAttrs(tag) {
  var attrs = {};
  if (tag == null) { return attrs; }
  var s = "" + tag;
  if (s.indexOf("</node") == 0) { return attrs; }
  var len = s.length;
  var i = 0;
  while (i < len) {
    var eq = s.indexOf("=", i);
    if (eq < 0) { break; }
    var j = eq - 1;
    while (j >= 0) {
      var cj = s.charAt(j);
      if (cj == " " || cj == "\n" || cj == "\t" || cj == "<") { break; }
      j = j - 1;
    }
    var key = s.substring(j + 1, eq);
    var q1 = s.indexOf("\"", eq + 1);
    if (q1 < 0) { break; }
    var q2 = s.indexOf("\"", q1 + 1);
    if (q2 < 0) { break; }
    if (key != null && key.length > 0) {
      attrs["" + key] = s.substring(q1 + 1, q2);
    }
    i = q2 + 1;
  }
  return attrs;
}

function isSelfClosingNodeTag(tag) {
  if (tag == null) { return false; }
  var s = "" + tag;
  var len = s.length;
  if (len < 2) { return false; }
  return s.charAt(len - 2) == "/" && s.charAt(len - 1) == ">";
}

function buildTreeFromXml(xml) {
  var startMs = new Date().getTime();
  var treeRoot = { attrs: {}, children: [] };
  if (xml == null) { return treeRoot; }
  var s = "" + xml;
  var len = s.length;
  var stack = [treeRoot];
  var count = 0;
  var cursor = 0;
  while (cursor < len) {
    var nowMs = new Date().getTime();
    if (nowMs - startMs > CONFIG.LIST_PARSE_TIMEOUT_MS) {
      logw("[LIST_PARSE] 解析超时，已处理=" + count);
      break;
    }
    var lt = s.indexOf("<", cursor);
    if (lt < 0) { break; }
    if (s.indexOf("</node", lt) == lt) {
      var gt1 = s.indexOf(">", lt + 2);
      if (gt1 < 0) { break; }
      if (stack.length > 1) { stack.pop(); }
      count = count + 1;
      if (CONFIG.LIST_PARSE_LOG_EVERY > 0 && (count % CONFIG.LIST_PARSE_LOG_EVERY) == 0) {
        logi("[LIST_PARSE] 进度=" + count);
      }
      if (count > CONFIG.LIST_PARSE_MAX_MATCHES) {
        logw("[LIST_PARSE] 节点数超上限: " + count);
        break;
      }
      cursor = gt1 + 1;
      continue;
    }
    if (s.indexOf("<node", lt) == lt) {
      var gt2 = s.indexOf(">", lt + 5);
      if (gt2 < 0) { break; }
      var tag = s.substring(lt, gt2 + 1);
      var attrs = null;
      try { attrs = parseAttrs(tag); } catch (e1) { attrs = {}; }
      var node = { attrs: attrs, children: [] };
      var parent = stack[stack.length - 1];
      if (parent != null && parent.children != null) {
        parent.children.push(node);
      }
      if (!isSelfClosingNodeTag(tag)) { stack.push(node); }
      count = count + 1;
      if (CONFIG.LIST_PARSE_LOG_EVERY > 0 && (count % CONFIG.LIST_PARSE_LOG_EVERY) == 0) {
        logi("[LIST_PARSE] 进度=" + count);
      }
      if (count > CONFIG.LIST_PARSE_MAX_MATCHES) {
        logw("[LIST_PARSE] 节点数超上限: " + count);
        break;
      }
      cursor = gt2 + 1;
      continue;
    }
    var gt3 = s.indexOf(">", lt + 1);
    if (gt3 < 0) { break; }
    cursor = gt3 + 1;
  }
  return treeRoot;
}

function getAttr(node, key) {
  if (node == null) { return ""; }
  if (node.attrs == null) { return ""; }
  var v = node.attrs[key];
  if (v == null) { return ""; }
  return "" + v;
}

function parseBoundsCenter(bounds) {
  if (bounds == null) { return null; }
  var s = "" + bounds;
  if (s.length == 0) { return null; }
  var nums = [];
  var cur = "";
  var i = 0;
  while (i < s.length) {
    var c = s.charAt(i);
    if (c >= "0" && c <= "9") {
      cur = cur + c;
    } else {
      if (cur.length > 0) {
        nums.push(toInt(cur));
        cur = "";
      }
    }
    i = i + 1;
  }
  if (cur.length > 0) { nums.push(toInt(cur)); }
  if (nums.length < 4) { return null; }
  var x1 = nums[0];
  var y1 = nums[1];
  var x2 = nums[2];
  var y2 = nums[3];
  var cx = Math.floor((x1 + x2) / 2);
  var cy = Math.floor((y1 + y2) / 2);
  return {x: cx, y: cy};
}

function toInt(s) {
  if (s == null) { return 0; }
  var t = "" + s;
  var n = 0;
  var i = 0;
  while (i < t.length) {
    var c = t.charAt(i);
    if (c >= "0" && c <= "9") {
      n = n * 10 + (c.charCodeAt(0) - 48);
    }
    i = i + 1;
  }
  return n;
}

function isTargetUserGroup(node) {
  if (node == null) { return false; }
  if (getAttr(node, "class") != CONFIG.LIST_TARGET_CLASS) { return false; }
  if (getAttr(node, "package") != CONFIG.LIST_TARGET_PACKAGE) { return false; }
  if (getAttr(node, "content-desc") != CONFIG.LIST_TARGET_CONTENT_DESC) { return false; }
  if (getAttr(node, "checkable") != CONFIG.LIST_TARGET_CHECKABLE) { return false; }
  if (getAttr(node, "checked") != CONFIG.LIST_TARGET_CHECKED) { return false; }
  if (getAttr(node, "clickable") != CONFIG.LIST_TARGET_CLICKABLE) { return false; }
  return true;
}

function collectUserGroups(node, out) {
  if (node == null) { return; }
  if (isTargetUserGroup(node)) { out.push(node); }
  var children = node.children;
  if (children != null) {
    var i = 0;
    for (i = 0; i < children.length; i = i + 1) {
      collectUserGroups(children[i], out);
    }
  }
}

function nodeKey(node) {
  var cls = getAttr(node, "class");
  var rid = getAttr(node, "resource-id");
  var bounds = getAttr(node, "bounds");
  var text = getAttr(node, "text");
  var desc = getAttr(node, "content-desc");
  return cls + "|" + rid + "|" + bounds + "|" + text + "|" + desc;
}

function dedupNodes(nodes) {
  var out = [];
  var map = {};
  var i = 0;
  for (i = 0; i < nodes.length; i = i + 1) {
    var k = nodeKey(nodes[i]);
    if (map[k] == true) { continue; }
    map[k] = true;
    out.push(nodes[i]);
  }
  return out;
}

function strTrim(s) {
  if (s == null) { return ""; }
  var t = "" + s;
  var start = 0;
  var end = t.length - 1;
  while (start <= end) {
    var c1 = t.charAt(start);
    if (c1 != " " && c1 != "\n" && c1 != "\t" && c1 != "\r") { break; }
    start = start + 1;
  }
  while (end >= start) {
    var c2 = t.charAt(end);
    if (c2 != " " && c2 != "\n" && c2 != "\t" && c2 != "\r") { break; }
    end = end - 1;
  }
  if (end < start) { return ""; }
  return t.substring(start, end + 1);
}

function isNonEmptyText(s) {
  return strTrim(s).length > 0;
}

function initIndexTextMap() {
  return {"0": "", "1": "", "2": ""};
}

function hasChildIndexInSubtree(node, idxStr) {
  if (node == null) { return false; }
  var stack = [node];
  while (stack.length > 0) {
    var cur = stack.pop();
    if (cur == null) { continue; }
    var idx = getAttr(cur, "index");
    if (idx == idxStr) { return true; }
    var children = cur.children;
    if (children != null) {
      var i = 0;
      for (i = 0; i < children.length; i = i + 1) {
        stack.push(children[i]);
      }
    }
  }
  return false;
}

function allIndexFound(map) {
  if (map == null) { return false; }
  return map["0"] != "" && map["1"] != "" && map["2"] != "";
}

function collectIndexTextsInSubtree(node, map) {
  if (node == null || map == null) { return; }
  var stack = [node];
  while (stack.length > 0) {
    var cur = stack.pop();
    if (cur == null) { continue; }
    var idx = getAttr(cur, "index");
    if (idx == "0" || idx == "1" || idx == "2") {
      if (map[idx] == "") {
        var txt = getAttr(cur, "text");
        if (isNonEmptyText(txt)) { map[idx] = strTrim(txt); }
      }
    }
    if (allIndexFound(map)) { return; }
    var children = cur.children;
    if (children != null) {
      var i = 0;
      for (i = 0; i < children.length; i = i + 1) {
        stack.push(children[i]);
      }
    }
  }
}

function collectFirstTextsInSubtree(node, limit) {
  var out = [];
  if (node == null) { return out; }
  var stack = [node];
  while (stack.length > 0) {
    var cur = stack.pop();
    if (cur == null) { continue; }
    var txt = getAttr(cur, "text");
    if (isNonEmptyText(txt)) {
      out.push(strTrim(txt));
      if (out.length >= limit) { return out; }
    }
    var children = cur.children;
    if (children != null) {
      var i = 0;
      for (i = 0; i < children.length; i = i + 1) {
        stack.push(children[i]);
      }
    }
  }
  return out;
}

function collectAllTextsInSubtree(node, limit) {
  var out = [];
  if (node == null) { return out; }
  var stack = [node];
  while (stack.length > 0) {
    var cur = stack.pop();
    if (cur == null) { continue; }
    var txt = getAttr(cur, "text");
    if (isNonEmptyText(txt)) {
      out.push(strTrim(txt));
      if (limit != null && limit > 0 && out.length >= limit) { return out; }
    }
    var children = cur.children;
    if (children != null) {
      var i = 0;
      for (i = 0; i < children.length; i = i + 1) {
        stack.push(children[i]);
      }
    }
  }
  return out;
}

function listContainsText(texts, token) {
  if (texts == null || token == null) { return false; }
  var t = ("" + token).trim();
  if (t == "") { return false; }
  var i = 0;
  for (i = 0; i < texts.length; i = i + 1) {
    if (("" + texts[i]).indexOf(t) >= 0) { return true; }
  }
  return false;
}

function isKeyMatchedInStore(groupTexts) {
  if (g_listClickedKeyList == null || g_listClickedKeyList.length <= 0) { return false; }
  if (groupTexts == null || groupTexts.length == 0) { return false; }
  var i = 0;
  for (i = 0; i < g_listClickedKeyList.length; i = i + 1) {
    var keyId = g_listClickedKeyList[i];
    if (keyId == null) { continue; }
    var raw = "" + keyId;
    if (raw.indexOf("KEY:") == 0) { raw = raw.substring(4); }
    if (raw == "") { continue; }
    var parts = raw.split("|");
    var ok = true;
    var j = 0;
    for (j = 0; j < parts.length; j = j + 1) {
      if (!listContainsText(groupTexts, parts[j])) {
        ok = false;
        break;
      }
    }
    if (ok) { return true; }
  }
  return false;
}

function getUserGroupKey(node) {
  var map = initIndexTextMap();
  collectIndexTextsInSubtree(node, map);
  var parts = [];
  if (map["0"] != "") { parts.push(map["0"]); }
  if (map["1"] != "") { parts.push(map["1"]); }
  if (map["2"] != "") { parts.push(map["2"]); }
  if (parts.length > 0) { return parts.join("|"); }
  var fallback = collectFirstTextsInSubtree(node, 3);
  return fallback.join("|");
}

function buildUserGroupKeyForClick(node) {
  var map = initIndexTextMap();
  collectIndexTextsInSubtree(node, map);
  var idx0 = map["0"];
  var idx1 = map["1"];
  var idx2 = map["2"];
  var allowByIdx0 = (idx0 != "" && idx0 != "一起聊");
  var hasIdx3 = hasChildIndexInSubtree(node, "3");
  if (!hasIdx3 && !allowByIdx0) { return ""; }
  var key = "";
  if (allowByIdx0) {
    var parts = [];
    if (idx0 != "") { parts.push(idx0); }
    if (idx1 != "") { parts.push(idx1); }
    key = parts.join("|");
  } else if (idx0 == "一起聊") {
    var parts2 = [];
    if (idx1 != "") { parts2.push(idx1); }
    if (idx2 != "") { parts2.push(idx2); }
    key = parts2.join("|");
  } else {
    key = getUserGroupKey(node);
  }
  return key;
}

function scanChatListCards() {
  var xml = dumpChatListUiXml();
  if (xml == null || xml.indexOf("<hierarchy") < 0) {
    logw("[LIST_SCAN] XML 获取失败");
    return {items: [], allKeysArr: []};
  }
  var treeRoot = buildTreeFromXml(xml);
  var groups = [];
  collectUserGroups(treeRoot, groups);
  groups = dedupNodes(groups);
  var items = [];
  var allKeysArr = [];
  var seenAll = [];
  var seenItems = [];
  var i = 0;
  for (i = 0; i < groups.length; i = i + 1) {
    var key = buildUserGroupKeyForClick(groups[i]);
    if (key == "") { continue; }
    var keyId = "KEY:" + key;
    logi("[LIST_KEY] keyId=" + keyId);
    if (!containsKey(seenAll, keyId)) { seenAll.push(keyId); allKeysArr.push(keyId); }
    var groupTexts = collectAllTextsInSubtree(groups[i], 30);
    if (containsKey(g_listClickedAllList, keyId) || containsKey(seenItems, keyId) || isKeyMatchedInStore(groupTexts)) { continue; }
    seenItems.push(keyId);
    var bounds = getAttr(groups[i], "bounds");
    var pt = parseBoundsCenter(bounds);
    if (pt == null) { continue; }
    items.push({keyId: keyId, key: key, x: pt.x, y: pt.y});
  }
  return {items: items, allKeysArr: allKeysArr};
}

function buildKeysSignatureArr(list) {
  if (list == null || list.length <= 0) { return ""; }
  var arr = list.slice(0);
  arr.sort();
  return arr.join("|");
}

function listSwipeUp() {
  try {
    var sx = CONFIG.LIST_SWIPE_START_X;
    var sy = CONFIG.LIST_SWIPE_START_Y;
    var ex = CONFIG.LIST_SWIPE_END_X;
    var ey = CONFIG.LIST_SWIPE_END_Y;
    if (sy < ey) {
      var t = sy; sy = ey; ey = t;
      t = sx; sx = ex; ex = t;
    }
    callScript("AdbSwipe", "swipe", sx, sy, ex, ey, CONFIG.LIST_SWIPE_DURATION);
  } catch (e) {
    logw("[LIST_SWIPE] 上滑异常: " + e);
  }
  sleepMs(CONFIG.LIST_SWIPE_AFTER_WAIT_MS);
}

function listSwipeDown() {
  try {
    var sx = CONFIG.LIST_SWIPE_START_X;
    var sy = CONFIG.LIST_SWIPE_START_Y;
    var ex = CONFIG.LIST_SWIPE_END_X;
    var ey = CONFIG.LIST_SWIPE_END_Y;
    if (sy > ey) {
      var t = sy; sy = ey; ey = t;
      t = sx; sx = ex; ex = t;
    }
    callScript("AdbSwipe", "swipe", sx, sy, ex, ey, CONFIG.LIST_SWIPE_DURATION);
  } catch (e) {
    logw("[LIST_SWIPE] 下滑异常: " + e);
  }
  sleepMs(CONFIG.LIST_SWIPE_AFTER_WAIT_MS);
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

function checkShizukuInstalled() {
  // 避免在部分引擎中 getAppVersionName 不存在导致编译失败
  return true;
}

function ensureShizukuReady() {
  if (CONFIG.USE_SHIZUKU_RESTART != 1 && CONFIG.USE_SHIZUKU_DUMP != 1) {
    return false;
  }
  if (!checkShizukuInstalled()) {
    logw("[SHIZUKU] 未安装");
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

function forceStopApp() {
  if (ensureShizukuReady()) {
    var cmd = "am force-stop " + CONFIG.APP_PKG;
    logi("[FORCE_STOP] cmd=" + cmd);
    return execShizuku(cmd);
  }
  logw("[FORCE_STOP] Shizuku 不可用，回退 killBackgroundApp");
  return safeKillBackgroundApp();
}

function startApp() {
  if (ensureShizukuReady()) {
    var cmd = "monkey -p " + CONFIG.APP_PKG + " -c android.intent.category.LAUNCHER 1";
    logi("[START_APP] cmd=" + cmd);
    return execShizuku(cmd);
  }
  logw("[START_APP] Shizuku 不可用，回退 launchApp");
  var ret = safeLaunchApp();
  return (ret == 1);
}

function restartApp(reason) {
  logw("[APP_RESTART] reason=" + reason);
  logi("[APP_RESTART] 关闭应用...");
  var killed = forceStopApp();
  logi("[APP_RESTART] 关闭结果 ok=" + killed);
  logi("[APP_RESTART] 启动应用...");
  var started = startApp();
  logi("[APP_RESTART] 启动结果 ok=" + started);
  if (!started) {
    alert(CONFIG.APP_NAME + " 未安装");
    stop();
    return;
  }
  logi("[APP_RESTART] 等待应用重启 " + CONFIG.APP_RESTART_WAIT_MS + "ms");
  sleepMs(CONFIG.APP_RESTART_WAIT_MS);
  // 已移除 PopupHandler 调用
}

function restartToChatTab(reason) {
  var retry = CONFIG.RESTART_TO_CHAT_RETRY;
  if (retry == null || retry <= 0) {
    retry = 1;
  }

  var i = 0;
  for (i = 0; i < retry; i = i + 1) {
    logw("[RESTART_FLOW] reason=" + reason + " retry=" + (i + 1) + "/" + retry + " 执行: 退出/重启→首页→一起聊");
    restartApp(reason);
    if (!ensureHome()) {
      loge("[RESTART_FLOW] ensureHome 失败，继续重试");
      continue;
    }
    ensureChatTab();
    if (!isChatTabPage()) {
      logw("[RESTART_FLOW] 未进入一起聊界面，继续重试");
      continue;
    }
    logi("[RESTART_FLOW] 已进入一起聊界面");
    return true;
  }
  return false;
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

function isInLookApp() {
  try {
    var cur = getCurPackageName();
    if (cur == CONFIG.APP_PKG) {
      return true;
    }
    if (cur != null && cur != "" && cur != "null" && cur != "undefined") {
      logw("[PKG] 当前前台包名=" + cur + "，目标包名=" + CONFIG.APP_PKG);
      return false;
    }
  } catch (e) {
    logw("[PKG] getCurPackageName 异常: " + e);
  }
  // 当前台包名不可用时，避免误判导致频繁拉起
  return true;
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

function normalizeKeyText(text) {
  var t = "" + text;
  t = t.trim();
  if (t == "null" || t == "undefined") { t = ""; }
  return t;
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
// 下滑进入下一个直播间
// ==============================
function enterNextLiveBySwipe(prevKey) {
  var retry = CONFIG.ENTER_LIVE_RETRY;
  if (retry == null || retry <= 0) { retry = 1; }
  var r = 0;
  for (r = 0; r < retry; r = r + 1) {
    var sc = doScrollDown();
    logi("[NEXT_LIVE] swipe ok=" + sc + " retry=" + (r + 1) + "/" + retry);
    sleepMs(CONFIG.CLICK_LIVE_WAIT_MS);

    if (!isLiveRoomPage()) {
      logw("[NEXT_LIVE] 滑动后未在直播间，继续重试");
      continue;
    }

    var curKey = normalizeKeyText(getRoomKeyFromLiveRoom());
    var prev = normalizeKeyText(prevKey);
    if (curKey != "" && prev != "" && curKey != prev) {
      try {
        var ph = callScript("PopupHandler");
        if (ph != null && ph.handled == true) { sleepMs(800); }
      } catch (eph) {
        logw("[POPUP] enterNextLiveBySwipe 异常: " + eph);
      }
      logi("[NEXT_LIVE] 已进入下一个直播间 key=" + curKey);
      return true;
    }
    logw("[NEXT_LIVE] 未进入下一个直播间 prevKey=" + prev + " curKey=" + curKey);
  }
  return false;
}

function tapChatListItem(item) {
  if (item == null) { return false; }
  var x = item.x;
  var y = item.y;
  if (x == null || y == null) { return false; }
  var ok = false;
  try {
    ok = callScript("AdbClick", "click", x, y);
  } catch (e) {
    logw("[LIST_TAP] AdbClick 异常: " + e);
    ok = false;
  }
  return ok;
}

function backToChatTab() {
  var i = 0;
  for (i = 0; i < 6; i = i + 1) {
    logi("[BACK_TO_CHAT] attempt=" + (i + 1) + "/6");
    if (isChatTabPage()) { return true; }

    if (isLiveRoomPage()) {
      logi("[BACK_TO_CHAT] in live room, do back()");
      backAndWait("BACK_TO_CHAT");
    } else {
      if (!isInLookApp()) {
        logw("[BACK_TO_CHAT] 不在 LOOK 前台，先拉起应用");
        var started = startApp();
        logi("[BACK_TO_CHAT] startApp ok=" + started);
        sleepMs(CONFIG.APP_RESTART_WAIT_MS);
        if (isChatTabPage()) { return true; }
        continue;
      }
      logi("[BACK_TO_CHAT] not in live room, try popup+back()");
      try {
        var ph = callScript("PopupHandler");
        if (ph != null && ph.handled == true) { sleepMs(500); }
      } catch (eph) {}
      try { back(); } catch (e) {}
    }

    // 给页面更长的稳定时间
    sleepMs(CONFIG.CLICK_WAIT_MS + 800);
    if (isChatTabPage()) { return true; }
    logw("[BACK_TO_CHAT] still not in chat tab after attempt=" + (i + 1));
  }

  // 多次返回仍失败时才兜底回首页
  logw("[BACK_TO_CHAT] failed after 6 attempts, fallback ensureHome+ensureChatTab");
  if (ensureHome()) {
    ensureChatTab();
    if (isChatTabPage()) { return true; }
  }
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
      return "FAIL_ENTER";
    }
    logi("[STEP_1] 成功进入直播间");
  }

  // Step 1.0: 进入直播间后处理弹窗
  try {
    var ph = callScript("PopupHandler");
    if (ph != null && ph.handled == true) { sleepMs(800); }
  } catch (eph) {
    logw("[POPUP] processOneLive 异常: " + eph);
  }

  // Step 1.1: 进入直播间后优先去重（基于 roomNo）
  if (CONFIG.CLICK_LIVE_WAIT_MS != null && CONFIG.CLICK_LIVE_WAIT_MS > 0) {
    logi("[STEP_1.1] 等待 CLICK_LIVE_WAIT_MS=" + CONFIG.CLICK_LIVE_WAIT_MS + "ms 后读取 roomNo");
    sleepMs(CONFIG.CLICK_LIVE_WAIT_MS);
  }
  var earlyKey = normalizeKeyText(getRoomKeyFromLiveRoom());
  if (earlyKey != "") {
    ensureHomeKeyStore();
    if (containsKey(g_seen, earlyKey)) {
      logw("[STEP_1.1] 去重命中：重复直播间 key=" + earlyKey + " seenCount=" + g_seen.length);
      return "SKIP";
    }
    logi("[STEP_1.1] 去重通过：新直播间 key=" + earlyKey + " seenCount=" + g_seen.length);
    markListClicked("ROOM:" + earlyKey);
  } else {
    logw("[STEP_1.1] roomNo 为空，无法提前去重");
  }

  // Step 2: 检查直播间有效性
  logi("[STEP_2] 检查直播间有效性...");
  var isValid = false;
  var invalidRoom = false;
  try {
    // callScript("LOOK_CheckLiveRoom", retryCount, checkInterval)
    var checkRet = callScript("LOOK_CheckLiveRoom", CONFIG.LIVE_ROOM_CHECK_RETRY, 1000);
    if (checkRet != null && typeof checkRet === "object") {
      if (checkRet.invalidRoom === true) { invalidRoom = true; }
      if (checkRet.valid === true) { isValid = true; }
    } else {
      if (checkRet === "INVALID_ROOM") { invalidRoom = true; }
      if (checkRet === true) { isValid = true; }
    }
  } catch (e) {
    loge("[STEP_2] callScript error: " + e);
  }

  if (invalidRoom) {
    logi("[STEP_2] 检测到无效直播间（加入合唱），跳过");
    return "SKIP";
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
  var roomKey = earlyKey;
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
  g_lastRoomKey = "" + roomKey;

  ensureHomeKeyStore();
  if (earlyKey == "" || earlyKey == null) {
    var isDup = containsKey(g_seen, roomKey);
    if (isDup) {
      logw("[STEP_3] 去重命中：重复直播间 key=" + roomKey + " seenCount=" + g_seen.length);
      return "SKIP";
    }
    logi("[STEP_3] 去重通过：新直播间 key=" + roomKey + " seenCount=" + g_seen.length);
  }
  addHomeKey(roomKey);
  g_liveClickCount = g_liveClickCount + 1;
  if (CONFIG.LOOP_LIVE_TOTAL != null && CONFIG.LOOP_LIVE_TOTAL > 0) {
    var remain = CONFIG.LOOP_LIVE_TOTAL - g_liveClickCount;
    if (remain < 0) { remain = 0; }
    logi("选择直播间 key=" + roomKey + " count=" + g_liveClickCount + "/" + CONFIG.LOOP_LIVE_TOTAL + " remain=" + remain);
  } else {
    logi("选择直播间 key=" + roomKey + " count=" + g_liveClickCount + "/无限");
  }

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
  var lastKeysSig = "";

  while (true) {
    // 检查数据库写入上限
    var insertCount = 0;
    try { insertCount = callScript("DataHandler", "getCount"); } catch (e) {}
    if (insertCount >= CONFIG.STOP_AFTER_ROWS) {
      logw("达到 STOP_AFTER_ROWS=" + CONFIG.STOP_AFTER_ROWS);
      break;
    }

    // 检查直播间点击次数限制
    if (CONFIG.LOOP_LIVE_TOTAL != null && CONFIG.LOOP_LIVE_TOTAL > 0) {
      if (g_liveClickCount >= CONFIG.LOOP_LIVE_TOTAL) {
        logw("达到 LOOP_LIVE_TOTAL=" + CONFIG.LOOP_LIVE_TOTAL);
        break;
      }
    }

    // 确保在一起聊页面
    if (isLiveRoomPage()) {
      backToChatTab();
    }
    if (!isChatTabPage()) {
      if (g_skipRestartOnce == true) {
        g_skipRestartOnce = false;
        logw("主脚本刚拉起App，优先回首页并切到一起聊（跳过重启流程）");
        if (!ensureHome()) {
          logw("回首页失败，改为执行重启流程(RESTART_TO_CHAT_RETRY=" + CONFIG.RESTART_TO_CHAT_RETRY + ")");
          if (!restartToChatTab("not_in_chat")) {
            logw("重启流程失败（未进入一起聊），等待 " + CONFIG.CHAT_TAB_CHECK_WAIT_MS + "ms 后重试");
            sleepMs(CONFIG.CHAT_TAB_CHECK_WAIT_MS);
            continue;
          }
        }
        ensureChatTab();
        if (!isChatTabPage()) {
          logw("未进入一起聊界面，等待 " + CONFIG.CHAT_TAB_CHECK_WAIT_MS + "ms 后重试");
          sleepMs(CONFIG.CHAT_TAB_CHECK_WAIT_MS);
          continue;
        }
      } else {
        logw("不在一起聊界面，执行重启流程(RESTART_TO_CHAT_RETRY=" + CONFIG.RESTART_TO_CHAT_RETRY + ")");
        if (!restartToChatTab("not_in_chat")) {
          logw("重启流程失败（未进入一起聊），等待 " + CONFIG.CHAT_TAB_CHECK_WAIT_MS + "ms 后重试");
          sleepMs(CONFIG.CHAT_TAB_CHECK_WAIT_MS);
          continue;
        }
      }
    }

    // 从直播间返回后，给列表界面稳定时间再 dump UI
    sleepMs(1500);

    // 扫描列表
    var scan = scanChatListCards();
    var items = scan.items;
    var keysSig = buildKeysSignatureArr(scan.allKeysArr);
    if (keysSig != "") { lastKeysSig = keysSig; }

    if (items.length == 0) {
      noNewScroll = noNewScroll + 1;
    } else {
      noNewScroll = 0;
    }

    // 逐个点击进入直播间
    var i = 0;
    for (i = 0; i < items.length; i = i + 1) {
      if (CONFIG.LOOP_LIVE_TOTAL != null && CONFIG.LOOP_LIVE_TOTAL > 0) {
        if (g_liveClickCount >= CONFIG.LOOP_LIVE_TOTAL) { break; }
      }
      markListClicked(items[i].keyId);
      logi("[LIST_CLICK] key=" + items[i].key + " x=" + items[i].x + " y=" + items[i].y);
      var tapOk = tapChatListItem(items[i]);
      logi("[LIST_CLICK] tapOk=" + tapOk);
      sleepMs(CONFIG.CLICK_LIVE_WAIT_MS);
      if (!isLiveRoomPage()) {
        logw("[LIST_CLICK] 未进入直播间，跳过");
        continue;
      }
      var r = processOneLive(true);
      if (r == "STOP_DB_MAX") {
        logw("达到数据库上限，停止");
        return;
      }
      // 先等待页面状态稳定，再执行返回一起聊判定，避免切窗瞬态误判
      var stableWaitMs = 1000;
      logi("[AFTER_LIVE] wait stable " + stableWaitMs + "ms before backToChatTab");
      sleepMs(stableWaitMs);
      logi("[AFTER_LIVE] processOneLive result=" + r + " , back to chat...");
      var backOk = backToChatTab();
      logi("[AFTER_LIVE] backToChatTab ok=" + backOk);
      if (!backOk) {
        logw("返回一起聊失败，执行重启流程");
        restartToChatTab("back_to_chat_failed");
      }
      // 每处理一个直播间都检查数据上限
      try { insertCount = callScript("DataHandler", "getCount"); } catch (e2) {}
      if (insertCount >= CONFIG.STOP_AFTER_ROWS) {
        logw("达到 STOP_AFTER_ROWS=" + CONFIG.STOP_AFTER_ROWS);
        return;
      }
    }

    logi("noNewScroll=" + noNewScroll + "/" + CONFIG.NO_NEW_SCROLL_LIMIT);
    if (noNewScroll >= CONFIG.NO_NEW_SCROLL_LIMIT) {
      // 判定是否为页面已完成或卡顿
      var sig0 = lastKeysSig;
      listSwipeUp();
      var scanUp = scanChatListCards();
      var sigUp = buildKeysSignatureArr(scanUp.allKeysArr);
      listSwipeDown();
      var scanDown = scanChatListCards();
      var sigDown = buildKeysSignatureArr(scanDown.allKeysArr);
      var hasChange = false;
      if (sigDown != "" && sigDown != sigUp) { hasChange = true; }
      if (!hasChange && sig0 != "" && sigDown != "" && sigDown != sig0) { hasChange = true; }
      if (hasChange) {
        logw("列表可滚动但无新卡片，认为采集完毕");
        break;
      } else {
        logw("列表无变化，疑似卡顿，重启后继续");
        restartToChatTab("list_stuck");
        noNewScroll = 0;
        continue;
      }
    }

    // 继续上滑加载下一屏
    listSwipeUp();
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
    if (params.RESTART_TO_CHAT_RETRY != null) { CONFIG.RESTART_TO_CHAT_RETRY = params.RESTART_TO_CHAT_RETRY; }
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
    if (params.LIST_DUMP_PATH != null) { CONFIG.LIST_DUMP_PATH = params.LIST_DUMP_PATH; }
    if (params.LIST_TARGET_CLASS != null) { CONFIG.LIST_TARGET_CLASS = params.LIST_TARGET_CLASS; }
    if (params.LIST_TARGET_PACKAGE != null) { CONFIG.LIST_TARGET_PACKAGE = params.LIST_TARGET_PACKAGE; }
    if (params.LIST_TARGET_CONTENT_DESC != null) { CONFIG.LIST_TARGET_CONTENT_DESC = params.LIST_TARGET_CONTENT_DESC; }
    if (params.LIST_TARGET_CHECKABLE != null) { CONFIG.LIST_TARGET_CHECKABLE = params.LIST_TARGET_CHECKABLE; }
    if (params.LIST_TARGET_CHECKED != null) { CONFIG.LIST_TARGET_CHECKED = params.LIST_TARGET_CHECKED; }
    if (params.LIST_TARGET_CLICKABLE != null) { CONFIG.LIST_TARGET_CLICKABLE = params.LIST_TARGET_CLICKABLE; }
    if (params.LIST_CLICKED_KEYS_DIR != null) { CONFIG.LIST_CLICKED_KEYS_DIR = params.LIST_CLICKED_KEYS_DIR; }
    if (params.LIST_CLICKED_KEYS_FILE_PREFIX != null) { CONFIG.LIST_CLICKED_KEYS_FILE_PREFIX = params.LIST_CLICKED_KEYS_FILE_PREFIX; }
    if (params.LIST_CLICKED_KEYS_FILE_SUFFIX != null) { CONFIG.LIST_CLICKED_KEYS_FILE_SUFFIX = params.LIST_CLICKED_KEYS_FILE_SUFFIX; }
    if (params.LIST_SWIPE_START_X != null) { CONFIG.LIST_SWIPE_START_X = params.LIST_SWIPE_START_X; }
    if (params.LIST_SWIPE_START_Y != null) { CONFIG.LIST_SWIPE_START_Y = params.LIST_SWIPE_START_Y; }
    if (params.LIST_SWIPE_END_X != null) { CONFIG.LIST_SWIPE_END_X = params.LIST_SWIPE_END_X; }
    if (params.LIST_SWIPE_END_Y != null) { CONFIG.LIST_SWIPE_END_Y = params.LIST_SWIPE_END_Y; }
    if (params.LIST_SWIPE_DURATION != null) { CONFIG.LIST_SWIPE_DURATION = params.LIST_SWIPE_DURATION; }
    if (params.LIST_SWIPE_AFTER_WAIT_MS != null) { CONFIG.LIST_SWIPE_AFTER_WAIT_MS = params.LIST_SWIPE_AFTER_WAIT_MS; }
    if (params.LIST_DUMP_WAIT_MS != null) { CONFIG.LIST_DUMP_WAIT_MS = params.LIST_DUMP_WAIT_MS; }
    if (params.LIST_DUMP_STABLE_WAIT_MS != null) { CONFIG.LIST_DUMP_STABLE_WAIT_MS = params.LIST_DUMP_STABLE_WAIT_MS; }
    if (params.LIST_PARSE_LOG_EVERY != null) { CONFIG.LIST_PARSE_LOG_EVERY = params.LIST_PARSE_LOG_EVERY; }
    if (params.LIST_PARSE_MAX_MATCHES != null) { CONFIG.LIST_PARSE_MAX_MATCHES = params.LIST_PARSE_MAX_MATCHES; }
    if (params.LIST_PARSE_TIMEOUT_MS != null) { CONFIG.LIST_PARSE_TIMEOUT_MS = params.LIST_PARSE_TIMEOUT_MS; }
    if (params.USE_SHIZUKU_DUMP != null) { CONFIG.USE_SHIZUKU_DUMP = params.USE_SHIZUKU_DUMP; }
    if (params.FLOAT_LOG_ENABLED != null) { CONFIG.FLOAT_LOG_ENABLED = params.FLOAT_LOG_ENABLED; }
    if (params.DEBUG_PAUSE_ON_ERROR != null) { CONFIG.DEBUG_PAUSE_ON_ERROR = params.DEBUG_PAUSE_ON_ERROR; }
    if (params.USE_SHIZUKU_RESTART != null) { CONFIG.USE_SHIZUKU_RESTART = params.USE_SHIZUKU_RESTART; }
    if (params.SHIZUKU_PKG != null) { CONFIG.SHIZUKU_PKG = params.SHIZUKU_PKG; }
    if (params.RETRY_COUNT != null) { CONFIG.RETRY_COUNT = params.RETRY_COUNT; }
    if (params.RETRY_INTERVAL != null) { CONFIG.RETRY_INTERVAL = params.RETRY_INTERVAL; }
    if (params.PERMISSION_TIMEOUT != null) { CONFIG.PERMISSION_TIMEOUT = params.PERMISSION_TIMEOUT; }
    if (params.MAIN_LAUNCHED_APP != null) { CONFIG.MAIN_LAUNCHED_APP = params.MAIN_LAUNCHED_APP; }
    if (params.ID_TAB != null) { CONFIG.ID_TAB = params.ID_TAB; }
    if (params.ID_RNVIEW != null) { CONFIG.ID_RNVIEW = params.ID_RNVIEW; }
    if (params.ID_ROOMNO != null) { CONFIG.ID_ROOMNO = params.ID_ROOMNO; }
    if (params.ID_LAYOUT_HEADER != null) { CONFIG.ID_LAYOUT_HEADER = params.ID_LAYOUT_HEADER; }
    if (params.ID_HEADER != null) { CONFIG.ID_HEADER = params.ID_HEADER; }
    if (params.ID_CLOSEBTN != null) { CONFIG.ID_CLOSEBTN = params.ID_CLOSEBTN; }
  }
  
  if (CONFIG.MAIN_LAUNCHED_APP == 1) {
    g_skipRestartOnce = true;
    logi("检测到主脚本拉起App，首次进入主循环将跳过重启流程");
  }

  logi("脚本启动");

  // 初始化本地homekey文件
  initHomeKeyStore();
  // 初始化列表卡片去重
  initListClickedStore();
  
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
  return g_liveClickCount;
}

// 注意：不要在文件末尾调用 main()
// 通过 callScript("LOOK_StartLiveRoom", CONFIG) 从 LOOK_Main.js 调用时，引擎会自动执行 main()
