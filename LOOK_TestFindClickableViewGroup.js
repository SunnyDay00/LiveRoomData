/**
 * LOOK_TestFindClickableViewGroup.js - ADB 获取 UI 树并查找可点击 ViewGroup
 *
 * 目标：
 * 1) 通过 ADB 获取当前界面 UI 树（uiautomator dump）
 * 2) 查找 resource-id = com.netease.play:id/rnView 且 class = android.widget.FrameLayout 的节点
 * 3) 在该节点子树中，匹配路径：
 *    android.widget.FrameLayout -> android.view.ViewGroup -> android.view.ViewGroup -> android.view.ViewGroup -> android.widget.FrameLayout -> android.view.ViewGroup
 * 4) 在路径终点 ViewGroup 下，搜索所有 class=android.view.ViewGroup 且 clickable=true 的节点
 *
 * 使用方式：
 *   callScript("LOOK_TestFindClickableViewGroup.js");
 */

// ==============================
// 配置
// ==============================
var CONFIG = {
  VERSION: "2026-01-31-01",
  DUMP_PATH: "/sdcard/uidump.xml",
  TARGET_CLASS: "android.view.ViewGroup",
  TARGET_PACKAGE: "com.netease.play",
  TARGET_CONTENT_DESC: "",
  TARGET_CHECKABLE: "false",
  TARGET_CHECKED: "false",
  TARGET_CLICKABLE: "true",
  ID_ROOM_NO: "com.netease.play:id/roomNo",
  CLICK_WAIT_MS: 2000,
  DUMP_WAIT_MS: 300,
  DUMP_STABLE_WAIT_MS: 1200,
  PARSE_LOG_EVERY: 500,
  PARSE_MAX_MATCHES: 50000,
  PARSE_TIMEOUT_MS: 8000
};

// ==============================
// 日志
// ==============================
function nowStr() {
  var utcTime = new Date().getTime();
  var beijingOffset = 8 * 60 * 60 * 1000;
  return "" + (utcTime + beijingOffset);
}

function logi(msg) {
  console.info("[" + nowStr() + "][TestUiTree][INFO] " + msg);
  try { floatMessage("[TestUiTree] " + msg); } catch (e) {}
}

function logw(msg) {
  console.warn("[" + nowStr() + "][TestUiTree][WARN] " + msg);
  try { floatMessage("[TestUiTree][WARN] " + msg); } catch (e) {}
}

function loge(msg) {
  console.error("[" + nowStr() + "][TestUiTree][ERROR] " + msg);
  try { floatMessage("[TestUiTree][ERROR] " + msg); } catch (e) {}
}

function sleepMs(ms) {
  sleep(ms);
}

// ==============================
// ADB 相关
// ==============================
function ensureShizukuReady() {
  try {
    shizuku.init();
  } catch (e1) {
    loge("shizuku.init 异常: " + e1);
    return false;
  }

  var connected = false;
  try {
    connected = shizuku.connect();
  } catch (e2) {
    loge("shizuku.connect 异常: " + e2);
    connected = false;
  }

  if (!connected) {
    loge("Shizuku 未连接，请检查服务状态");
    return false;
  }

  if (!shizuku.checkPermission()) {
    logw("Shizuku 权限未获取，正在请求...");
    shizuku.requestPermission(10000);
    if (!shizuku.checkPermission()) {
      loge("未获得 Shizuku 授权");
      return false;
    }
  }

  return true;
}

function shizukuExec(cmd) {
  try {
    var ret = shizuku.execCmd(cmd);
    return ret;
  } catch (e) {
    loge("shizuku.execCmd 异常: " + e + " cmd=" + cmd);
    return null;
  }
}

function shizukuTap(x, y) {
  if (x == null || y == null) {
    return false;
  }
  var cmd = "input tap " + x + " " + y;
  logi("[Tap] 执行: " + cmd);
  var ret = shizukuExec(cmd);
  if (ret == null) {
    logw("[Tap] 执行失败，返回 null");
    return false;
  }
  logi("[Tap] 执行完成，返回=" + ret);
  return true;
}

function shizukuBack() {
  shizukuExec("input keyevent 4");
}

function isValidUiXml(xml) {
  if (xml == null) {
    return false;
  }
  var s = "" + xml;
  if (s.indexOf("<hierarchy") < 0) {
    return false;
  }
  if (s.indexOf("<node") < 0) {
    return false;
  }
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
        if (isValidUiXml(xml)) {
          return "" + xml;
        }
      }
    }
    sleepMs(waitMs);
  }
  return null;
}

function logDumpFileInfo(path) {
  var ls = shizukuExec("ls -l " + path);
  if (ls != null) {
    logi("dump 文件信息: " + ls);
  }
  var head = shizukuExec("head -n 2 " + path);
  if (head != null) {
    logi("dump 文件头: " + head);
  }
}

function dumpUiTreeXml() {
  if (!ensureShizukuReady()) {
    return null;
  }

  logi("执行 uiautomator dump ...");
  var dumpRet = shizukuExec("uiautomator dump --compressed " + CONFIG.DUMP_PATH);
  if (dumpRet != null) {
    logi("dump 返回: " + dumpRet);
  }

  // 等待无障碍/系统状态稳定，避免读到不完整的XML
  sleepMs(CONFIG.DUMP_STABLE_WAIT_MS);

  var xml = readDumpXmlWithRetry(CONFIG.DUMP_PATH, 10, CONFIG.DUMP_WAIT_MS);

  if (!isValidUiXml(xml)) {
    logw("主路径未获取到 XML，尝试默认 dump 路径...");
    shizukuExec("uiautomator dump --compressed");
    xml = readDumpXmlWithRetry("/sdcard/window_dump.xml", 10, CONFIG.DUMP_WAIT_MS);
  }

  if (xml == null) {
    logw("dump 读取失败，输出文件信息用于排查");
    logDumpFileInfo(CONFIG.DUMP_PATH);
    loge("获取 UI 树失败（cat 返回空）");
    return null;
  }

  var s = "" + xml;
  logi("读取到 XML 长度: " + s.length);
  logXmlQuickInfo(s);
  return "" + xml;
}

// ==============================
// XML 解析
// ==============================
function parseAttrs(tag) {
  var attrs = {};
  if (tag == null) {
    return attrs;
  }
  var s = "" + tag;
  if (s.indexOf("</node") == 0) {
    return attrs;
  }
  var len = s.length;
  var i = 0;
  while (i < len) {
    var eq = s.indexOf("=", i);
    if (eq < 0) {
      break;
    }
    var j = eq - 1;
    while (j >= 0) {
      var cj = s.charAt(j);
      if (cj == " " || cj == "\n" || cj == "\t" || cj == "<") {
        break;
      }
      j = j - 1;
    }
    var key = s.substring(j + 1, eq);
    var q1 = s.indexOf("\"", eq + 1);
    if (q1 < 0) {
      break;
    }
    var q2 = s.indexOf("\"", q1 + 1);
    if (q2 < 0) {
      break;
    }
    if (key != null && key.length > 0) {
      attrs["" + key] = s.substring(q1 + 1, q2);
    }
    i = q2 + 1;
  }
  return attrs;
}

function isClosingNodeTag(tag) {
  if (tag == null) {
    return false;
  }
  var s = "" + tag;
  return s.indexOf("</node") == 0;
}

function isSelfClosingNodeTag(tag) {
  if (tag == null) {
    return false;
  }
  var s = "" + tag;
  var len = s.length;
  if (len < 2) {
    return false;
  }
  return s.charAt(len - 2) == "/" && s.charAt(len - 1) == ">";
}

function getFirstAttrValueInXml(s, attrName) {
  if (s == null) {
    return "";
  }
  var key = attrName + "=\"";
  var idx = s.indexOf(key);
  if (idx < 0) {
    return "";
  }
  var start = idx + key.length;
  var end = s.indexOf("\"", start);
  if (end < 0) {
    return "";
  }
  return s.substring(start, end);
}

function countOccurrences(s, needle) {
  if (s == null || needle == null || needle.length == 0) {
    return 0;
  }
  var count = 0;
  var idx = 0;
  while (true) {
    idx = s.indexOf(needle, idx);
    if (idx < 0) {
      break;
    }
    count = count + 1;
    idx = idx + needle.length;
  }
  return count;
}

function logXmlQuickInfo(s) {
  var pkg = getFirstAttrValueInXml(s, "package");
  if (pkg != "") {
    logi("XML 示例 package: " + pkg);
  } else {
    logw("XML 未找到 package 属性");
  }
  var nodeCount = countOccurrences(s, "<node");
  logi("XML <node> 数量: " + nodeCount);
}

function buildTreeFromXml(xml) {
  var startMs = new Date().getTime();
  var treeRoot = { attrs: {}, children: [] };
  if (xml == null) {
    return treeRoot;
  }

  var s = "" + xml;
  var len = s.length;
  logi("buildTreeFromXml: XML长度=" + len);

  var stack = [treeRoot];
  var count = 0;
  var cursor = 0;

  logi("buildTreeFromXml: 进入解析循环");
  while (cursor < len) {
    var nowMs = new Date().getTime();
    if (nowMs - startMs > CONFIG.PARSE_TIMEOUT_MS) {
      logw("解析超时，强制中断。已处理标签数=" + count);
      break;
    }

    var lt = s.indexOf("<", cursor);
    if (lt < 0) {
      break;
    }

    // closing tag
    if (s.indexOf("</node", lt) == lt) {
      var gt1 = s.indexOf(">", lt + 2);
      if (gt1 < 0) {
        break;
      }
      if (stack.length > 1) {
        stack.pop();
      }
      count = count + 1;
      if (CONFIG.PARSE_LOG_EVERY > 0 && (count % CONFIG.PARSE_LOG_EVERY) == 0) {
        logi("解析进度: 已处理节点标签 " + count);
      }
      if (count > CONFIG.PARSE_MAX_MATCHES) {
        logw("解析节点数超过上限，提前停止: " + count);
        break;
      }
      cursor = gt1 + 1;
      continue;
    }

    // open node tag
    if (s.indexOf("<node", lt) == lt) {
      var gt2 = s.indexOf(">", lt + 5);
      if (gt2 < 0) {
        break;
      }
      var tag = s.substring(lt, gt2 + 1);
      var attrs = null;
      try {
        attrs = parseAttrs(tag);
      } catch (e1) {
        loge("parseAttrs 异常: " + e1);
        attrs = {};
      }
      var node = { attrs: attrs, children: [] };
      var parent = stack[stack.length - 1];
      if (parent != null && parent.children != null) {
        parent.children.push(node);
      }
      if (!isSelfClosingNodeTag(tag)) {
        stack.push(node);
      }
      count = count + 1;
      if (count <= 3) {
        logi("解析样本: index=" + lt + " tag=" + tag);
      }
      if (CONFIG.PARSE_LOG_EVERY > 0 && (count % CONFIG.PARSE_LOG_EVERY) == 0) {
        logi("解析进度: 已处理节点标签 " + count);
      }
      if (count > CONFIG.PARSE_MAX_MATCHES) {
        logw("解析节点数超过上限，提前停止: " + count);
        break;
      }
      cursor = gt2 + 1;
      continue;
    }

    // other tags like <hierarchy> etc.
    var gt3 = s.indexOf(">", lt + 1);
    if (gt3 < 0) {
      break;
    }
    cursor = gt3 + 1;
  }

  logi("构建树完成，匹配标签数=" + count + "，耗时=" + (new Date().getTime() - startMs) + "ms");
  return treeRoot;
}

function getAttr(node, key) {
  if (node == null) {
    return "";
  }
  if (node.attrs == null) {
    return "";
  }
  var v = node.attrs[key];
  if (v == null) {
    return "";
  }
  return "" + v;
}

function parseBoundsCenter(bounds) {
  if (bounds == null) {
    return null;
  }
  var s = "" + bounds;
  if (s.length == 0) {
    return null;
  }
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
  if (cur.length > 0) {
    nums.push(toInt(cur));
  }
  if (nums.length < 4) {
    return null;
  }
  var x1 = nums[0];
  var y1 = nums[1];
  var x2 = nums[2];
  var y2 = nums[3];
  var cx = Math.floor((x1 + x2) / 2);
  var cy = Math.floor((y1 + y2) / 2);
  return {x: cx, y: cy};
}

function toInt(s) {
  if (s == null) {
    return 0;
  }
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

function getRoomNoTextByA11y() {
  try {
    var ret = findView("id:" + CONFIG.ID_ROOM_NO, {maxStep: 3});
    if (ret == null) {
      logw("[RoomNo] findView 返回 null");
      return "";
    }
    logi("[RoomNo] findView length=" + ret.length);
    if (ret != null && ret.length > 0) {
      var view = ret.views[0];
      if (view != null) {
        var t = view.text;
        if (t == null) {
          logw("[RoomNo] text 为空");
          return "";
        }
        logi("[RoomNo] text=" + t);
        return "" + t;
      }
    }
  } catch (e) {
    logw("findView roomNo 异常: " + e);
  }
  return "";
}

// ==============================
// 查找逻辑
// ==============================
function isTargetUserGroup(node) {
  if (node == null) {
    return false;
  }
  var cls = getAttr(node, "class");
  if (cls != CONFIG.TARGET_CLASS) {
    return false;
  }
  var pkg = getAttr(node, "package");
  if (pkg != CONFIG.TARGET_PACKAGE) {
    return false;
  }
  var desc = getAttr(node, "content-desc");
  if (desc != CONFIG.TARGET_CONTENT_DESC) {
    return false;
  }
  var checkable = getAttr(node, "checkable");
  if (checkable != CONFIG.TARGET_CHECKABLE) {
    return false;
  }
  var checked = getAttr(node, "checked");
  if (checked != CONFIG.TARGET_CHECKED) {
    return false;
  }
  var clickable = getAttr(node, "clickable");
  if (clickable != CONFIG.TARGET_CLICKABLE) {
    return false;
  }
  return true;
}

function collectUserGroups(node, out) {
  if (node == null) {
    return;
  }

  if (isTargetUserGroup(node)) {
    out.push(node);
  }

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
    if (map[k] == true) {
      continue;
    }
    map[k] = true;
    out.push(nodes[i]);
  }
  return out;
}

function formatNode(node) {
  var cls = getAttr(node, "class");
  var rid = getAttr(node, "resource-id");
  var clickable = getAttr(node, "clickable");
  var bounds = getAttr(node, "bounds");
  var text = getAttr(node, "text");
  var desc = getAttr(node, "content-desc");
  return "class=" + cls + ", clickable=" + clickable + ", id=" + rid + ", bounds=" + bounds + ", text=" + text + ", desc=" + desc;
}

function strTrim(s) {
  if (s == null) {
    return "";
  }
  var t = "" + s;
  var start = 0;
  var end = t.length - 1;
  while (start <= end) {
    var c1 = t.charAt(start);
    if (c1 != " " && c1 != "\n" && c1 != "\t" && c1 != "\r") {
      break;
    }
    start = start + 1;
  }
  while (end >= start) {
    var c2 = t.charAt(end);
    if (c2 != " " && c2 != "\n" && c2 != "\t" && c2 != "\r") {
      break;
    }
    end = end - 1;
  }
  if (end < start) {
    return "";
  }
  return t.substring(start, end + 1);
}

function isNonEmptyText(s) {
  return strTrim(s).length > 0;
}

function initIndexTextMap() {
  return {"0": "", "1": "", "2": ""};
}

function hasChildIndexInSubtree(node, idxStr) {
  if (node == null) {
    return false;
  }
  var stack = [node];
  while (stack.length > 0) {
    var cur = stack.pop();
    if (cur == null) {
      continue;
    }
    var idx = getAttr(cur, "index");
    if (idx == idxStr) {
      return true;
    }
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
  if (map == null) {
    return false;
  }
  return map["0"] != "" && map["1"] != "" && map["2"] != "";
}

function collectIndexTextsInSubtree(node, map) {
  if (node == null || map == null) {
    return;
  }
  var stack = [node];
  while (stack.length > 0) {
    var cur = stack.pop();
    if (cur == null) {
      continue;
    }
    var idx = getAttr(cur, "index");
    if (idx == "0" || idx == "1" || idx == "2") {
      if (map[idx] == "") {
        var txt = getAttr(cur, "text");
        if (isNonEmptyText(txt)) {
          map[idx] = strTrim(txt);
        }
      }
    }
    if (allIndexFound(map)) {
      return;
    }
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
  if (node == null) {
    return out;
  }
  var stack = [node];
  while (stack.length > 0) {
    var cur = stack.pop();
    if (cur == null) {
      continue;
    }
    var txt = getAttr(cur, "text");
    if (isNonEmptyText(txt)) {
      out.push(strTrim(txt));
      if (out.length >= limit) {
        return out;
      }
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

function getUserGroupKey(node) {
  var map = initIndexTextMap();
  collectIndexTextsInSubtree(node, map);
  var parts = [];
  if (map["0"] != "") { parts.push(map["0"]); }
  if (map["1"] != "") { parts.push(map["1"]); }
  if (map["2"] != "") { parts.push(map["2"]); }

  if (parts.length > 0) {
    return parts.join("|");
  }

  // fallback: 取子树中前3个非空text
  var fallback = collectFirstTextsInSubtree(node, 3);
  return fallback.join("|");
}

// ==============================
// 主入口
// ==============================
function main() {
  logi("脚本版本: " + CONFIG.VERSION);
  logi("开始获取 UI 树并分析...");
  var xml = dumpUiTreeXml();
  if (xml == null || xml.indexOf("<hierarchy") < 0) {
    loge("未获取到有效的 UI 树 XML");
    return false;
  }

  logi("开始解析 XML 并构建树...");
  var treeRoot = buildTreeFromXml(xml);
  logi("完成构建树，开始匹配用户可点击组...");
  var userGroups = [];
  collectUserGroups(treeRoot, userGroups);
  userGroups = dedupNodes(userGroups);

  if (userGroups.length == 0) {
    logw("未找到符合条件的用户可点击组");
    return true;
  }

  logi("找到用户可点击组数量: " + userGroups.length);
  var k = 0;
  for (k = 0; k < userGroups.length; k = k + 1) {
    logi("开始处理用户组 #" + (k + 1));
    var map = initIndexTextMap();
    collectIndexTextsInSubtree(userGroups[k], map);
    var idx0 = map["0"];
    var idx1 = map["1"];
    var idx2 = map["2"];
    var allowByIdx0 = (idx0 != "" && idx0 != "一起聊");
    var hasIdx3 = hasChildIndexInSubtree(userGroups[k], "3");
    if (!hasIdx3 && !allowByIdx0) {
      continue;
    }
    logi("#" + (k + 1) + " " + formatNode(userGroups[k]));
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
      key = getUserGroupKey(userGroups[k]);
    }
    logi("#" + (k + 1) + " 用户组KEY: " + key);

    try {
      logi("#" + (k + 1) + " 进入点击流程");
      var bounds = getAttr(userGroups[k], "bounds");
      logi("#" + (k + 1) + " bounds=" + bounds);
      var pt = parseBoundsCenter(bounds);
      if (pt == null) {
        logw("#" + (k + 1) + " bounds 无效，跳过点击");
        continue;
      }
      logi("#" + (k + 1) + " 点击进入用户组: x=" + pt.x + " y=" + pt.y);
      var tapOk = shizukuTap(pt.x, pt.y);
      logi("#" + (k + 1) + " 点击结果: " + tapOk);
      logi("#" + (k + 1) + " 等待进入直播间 " + CONFIG.CLICK_WAIT_MS + "ms");
      sleepMs(CONFIG.CLICK_WAIT_MS);

      logi("#" + (k + 1) + " 检测 roomNo...");
      var roomNo = getRoomNoTextByA11y();
      if (roomNo != "") {
        logi("#" + (k + 1) + " 进入直播间成功，roomNo=" + roomNo);
      } else {
        logw("#" + (k + 1) + " 未检测到 roomNo，可能未进入直播间");
      }

      // 返回列表，继续下一个
      shizukuBack();
      sleepMs(800);
      logi("#" + (k + 1) + " 点击流程结束");
    } catch (eClick) {
      loge("#" + (k + 1) + " 点击流程异常: " + eClick);
    }
  }

  return true;
}
