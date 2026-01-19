/**
 * LOOK_CollectContributors.js - 循环采集贡献榜用户信息脚本
 * 
 * 在贡献榜月榜界面，循环点击用户行采集信息并保存。
 * 
 * 使用方式：
 *   var result = callScript("LOOK_CollectContributors.js", {
 *     hostInfo: { id, name, fans, ip },  // 主播信息
 *     clickCount: 5,     // 采集用户数量
 *     clickWaitMs: 1500, // 点击后等待时间
 *     stopAfterRows: 200 // 达到此数量停止
 *   });
 */

// ==============================
// 配置
// ==============================
var ID_USER_ID = "com.netease.play:id/id";
var ID_USER_NAME = "com.netease.play:id/artist_name";
var ID_NUM = "com.netease.play:id/num";

// 应用名称（用于数据库记录）
var APP_NAME = "LOOK直播";

var DEFAULT_CLICK_COUNT = 5;
var DEFAULT_CLICK_WAIT_MS = 1500;
var DEFAULT_STOP_AFTER_ROWS = 200;

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
  console.info("[" + nowStr() + "][CollectContributors][INFO] " + msg);
  try { floatMessage("[Collector] " + msg); } catch (e) {}
}

function logw(msg) { 
  console.warn("[" + nowStr() + "][CollectContributors][WARN] " + msg);
  try { floatMessage("[Collector][WARN] " + msg); } catch (e) {}
}

function loge(msg) { 
  console.error("[" + nowStr() + "][CollectContributors][ERROR] " + msg);
  try { floatMessage("[Collector][ERROR] " + msg); } catch (e) {}
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

// 检查是否能找到指定控件
// 官方文档: 检查 ret.length > 0
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

function backAndWait(stepName, waitMs) {
  try { 
    back(); 
    logi("[" + stepName + "] back()"); 
  } catch (e) { 
    loge("[" + stepName + "] back exception=" + e); 
  }
  sleepMs(waitMs);
}

// 清理ID字符串，移除 "ID:" 前缀
function cleanId(str) {
  if (str == null) { return ""; }
  str = "" + str;
  return str.replace("ID：", "").replace("ID:", "").trim();
}

// ==============================
// 页面判断
// ==============================
function isDetailPage() {
  var hasId = hasView("id:" + ID_USER_ID, {maxStep: 2});
  var hasName = hasView("id:" + ID_USER_NAME, {maxStep: 2});
  if (hasId && hasName) {
    return true;
  }
  return false;
}

// ==============================
// 详情页字段提取
// ==============================
function getNumNearLabel(labelText) {
  // 直接搜索 txt:labelText，然后找父控件，再找 num 子控件
  if (!hasView("txt:" + labelText, {maxStep: 3})) {
    return "";
  }
  
  var labelView = getFirstView("txt:" + labelText, {maxStep: 3});
  if (labelView == null) { return ""; }
  
  var parent = getParent(labelView);
  if (parent == null) { return ""; }
  
  // 尝试在父控件中找 num
  var numText = getTextOfFirst("id:" + ID_NUM, {root: parent, maxStep: 2});
  if (numText != "" && numText != "null" && numText != "undefined") {
    return numText;
  }
  
  // 遍历父控件的子控件
  var childCount = 0;
  try {
    if (parent.size != null) { childCount = parent.size; }
    else if (parent.length != null) { childCount = parent.length; }
  } catch (e) {}
  
  var i = 0;
  for (i = 0; i < childCount; i = i + 1) {
    try {
      var child = parent[i];
      if (child != null) {
        var childId = "";
        try { childId = "" + child.id; } catch (e) {}
        var childText = "";
        try { childText = "" + child.text; } catch (e) {}
        
        if (childText == labelText) { continue; }
        
        if (childId.indexOf("num") >= 0) {
          if (childText != "" && childText != "null" && childText != "undefined") {
            return childText;
          }
        }
        
        if (childText != "" && childText != "null" && childText != "undefined" && childText != labelText) {
          return childText;
        }
      }
    } catch (e) {}
  }
  return "";
}

function readUserDetail() {
  logi("开始读取用户详情...");
  var obj = { ueseid: "", uesename: "", ueseip: "", SummaryConsumption: "" };

  var rawId = getTextOfFirst("id:" + ID_USER_ID, {maxStep: 2});
  obj.ueseid = cleanId(rawId);
  logi("ueseid=" + obj.ueseid);
  
  obj.uesename = getTextOfFirst("id:" + ID_USER_NAME, {maxStep: 2});
  logi("uesename=" + obj.uesename);
  
  obj.ueseip = getNumNearLabel("IP属地");
  logi("ueseip=" + obj.ueseip);
  
  obj.SummaryConsumption = getNumNearLabel("消费音符");
  logi("SummaryConsumption=" + obj.SummaryConsumption);
  
  logi("读取用户详情完成");

  return obj;
}

// ==============================
// 月榜用户行处理
// ==============================
function findRankTextView(rankStr) {
  var ret = findRet("txt:" + rankStr, {flag: "find_all", maxStep: 3});
  if (ret == null) { return null; }
  if (ret.length <= 0) { return null; }

  var count = ret.length;
  var i = 0;
  for (i = 0; i < count; i = i + 1) {
    try {
      var v = ret.views[i];
      if (v == null) { continue; }
      var txt = "" + v.text;
      if (txt != rankStr) { continue; }
      var cls = "";
      try { cls = "" + v.className; } catch (e) {}
      if (cls == "android.widget.TextView") {
        return v;
      }
    } catch (e) {}
  }
  return null;
}

function isViewGroupClassName(cls) {
  if (cls == "android.view.ViewGroup") { return true; }
  if (cls.indexOf("ViewGroup") >= 0) { return true; }
  if (cls.indexOf("Layout") >= 0) { return true; }
  return false;
}

function findClickableRowFromTextView(textView) {
  if (textView == null) { return null; }
  var p = getParent(textView);
  var depth = 0;
  while (p != null && depth < 10) {
    var cls = "";
    try { cls = "" + p.className; } catch (e) {}
    if (isClickable(p) && isViewGroupClassName(cls)) {
      return p;
    }
    p = getParent(p);
    depth = depth + 1;
  }
  return null;
}

function getBottomRightText(rootObj) {
  var bestText = "";
  var bestBottom = -1;
  var bestLeft = -1;

  // 使用 findRet 并正确访问
  var ret = findRet("className:android.widget.TextView", {root: rootObj, flag: "find_all", maxStep: 3});
  if (ret == null) { return ""; }
  if (ret.length <= 0) { return ""; }
  
  var count = ret.length;
  var i = 0;
  for (i = 0; i < count; i = i + 1) {
    try {
      var v = ret.views[i];
      if (v == null) { continue; }
      var txt = "" + v.text;

      var left = -1;
      var bottom = -1;

      if (v.left != null) { left = v.left; }
      if (v.bottom != null) {
        bottom = v.bottom;
      } else {
        if (v.top != null) {
          if (v.height != null) {
            bottom = v.top + v.height;
          }
        }
      }

      if (bottom > bestBottom) {
        bestBottom = bottom;
        bestLeft = left;
        bestText = txt;
      } else {
        if (bottom == bestBottom) {
          if (left > bestLeft) {
            bestLeft = left;
            bestText = txt;
          }
        }
      }
    } catch (e) {}
  }
  return bestText;
}

// 辅助函数：获取子控件数量
function getChildCount(v) {
  var cnt = 0;
  try {
    if (v.childCount != null) { cnt = v.childCount; }
    else if (v.getChildCount != null) { cnt = v.getChildCount(); }
    else if (v.size != null) { 
      if (typeof v.size === 'function') { cnt = v.size(); } else { cnt = v.size; }
    }
    else if (v.length != null) { cnt = v.length; }
  } catch (e) {}
  return cnt;
}

// 辅助函数：获取指定索引的子控件
function getChildAt(v, i) {
  var child = null;
  try { child = v[i]; } catch (e) {}
  if (child == null) {
    try { child = v.get(i); } catch (e) {}
  }
  if (child == null) {
    try { child = v.getChildAt(i); } catch (e) {}
  }
  return child;
}

// 辅助函数：获取组件的文本和左坐标，如果无效返回 null
function getViewTextInfo(v) {
  try {
    var txt = "";
    try { txt = "" + v.text; } catch (e) {}
    
    // 忽略空文本
    if (txt == null || txt == "" || txt == "null" || txt == "undefined") { return null; }
    
    var left = -1;
    if (v.left != null) { left = v.left; }
    else {
        try {
           var b = v.bounds();
           if (b != null) { left = b.left; }
        } catch(e) {}
    }
    
    if (left == -1) { return null; }
    
    return { text: txt, left: left };
  } catch (e) {
    return null;
  }
}

function getRightMostText(viewGroup) {
  if (viewGroup == null) { loge("getRightMostText: viewGroup is NULL"); return ""; }

  var bestText = "";
  var bestLeft = -1;

  // 1. 遍历一级子控件
  var level1Count = getChildCount(viewGroup);
  
  var i = 0;
  for (i = 0; i < level1Count; i = i + 1) {
    var child1 = getChildAt(viewGroup, i);
    if (child1 == null) { continue; }
    
    // 2. 遍历二级子控件 (假设结构是 Row -> Group -> TextViews)
    var level2Count = getChildCount(child1);
    
    // 如果一级子控件没有子控件，它自己可能是个TextView
    if (level2Count <= 0) {
      var info1 = getViewTextInfo(child1);
      if (info1 != null && info1.left > bestLeft) {
        bestLeft = info1.left;
        bestText = info1.text;
      }
      continue;
    }
    
    var j = 0;
    for (j = 0; j < level2Count; j = j + 1) {
      var child2 = getChildAt(child1, j);
      if (child2 == null) { continue; }
      
      var info2 = getViewTextInfo(child2);
      if (info2 != null && info2.left > bestLeft) {
        bestLeft = info2.left;
        bestText = info2.text;
      }
      
      // 3. 遍历三级子控件 (以防万一还有一层)
      var level3Count = getChildCount(child2);
      if (level3Count > 0) {
          var k = 0;
          for (k = 0; k < level3Count; k = k + 1) {
              var child3 = getChildAt(child2, k);
              if (child3 != null) {
                  var info3 = getViewTextInfo(child3);
                  if (info3 != null && info3.left > bestLeft) {
                      bestLeft = info3.left;
                      bestText = info3.text;
                  }
              }
          }
      }
    }
  }

  return bestText;
}

function pickContributionFrameFromRet(ret) {
  if (ret == null) { return null; }
  if (ret.length <= 0) { return null; }
  
  var count = ret.length;
  var i = 0;
  for (i = 0; i < count; i = i + 1) {
    try {
      var frame = ret.views[i];
      if (frame == null) { continue; }
      
      // 检查这个 frame 下是否有 txt:1（表示排行榜）
      if (hasView("txt:1", {root: frame, maxStep: 2})) {
        return frame;
      }
    } catch (e) {}
  }
  return null;
}

function findContributionFrame() {
  var tags = [
    "className:androidx.recyclerview.widget.RecyclerView",
    "className:android.support.v7.widget.RecyclerView",
    "className:android.widget.ListView",
    "className:android.widget.FrameLayout"
  ];
  var i = 0;
  for (i = 0; i < tags.length; i = i + 1) {
    var ret = findRet(tags[i], {flag: "find_all", maxStep: 3});
    var frame = pickContributionFrameFromRet(ret);
    if (frame != null) { return frame; }
  }
  return null;
}

function getClickableUserRows(frameObj) {
  var rows = [];
  
  if (frameObj == null) {
    var ret = findRet("className:android.view.ViewGroup", {flag: "find_all", maxStep: 2});
    if (ret == null) { return rows; }
    if (ret.length <= 0) { return rows; }

    var countFallback = ret.length;
    var iFallback = 0;
    for (iFallback = 0; iFallback < countFallback; iFallback = iFallback + 1) {
      try {
        var vFallback = ret.views[iFallback];
        if (vFallback == null) { continue; }
        var clsFallback = "";
        try { clsFallback = "" + vFallback.className; } catch (e) {}
        if (clsFallback == "android.view.ViewGroup" && isClickable(vFallback)) {
          rows.push(vFallback);
        }
      } catch (e) {}
    }
    return rows;
  }
  
  var listGroup = getFirstView("className:android.view.ViewGroup", {root: frameObj, maxStep: 1});
  if (listGroup == null) { return rows; }
  
  var count = 0;
  try {
    if (listGroup.size != null) { count = listGroup.size; }
    else if (listGroup.length != null) { count = listGroup.length; }
  } catch (e) {}
  if (count <= 0) {
    try { count = listGroup.size(); } catch (e) {}
  }
  
  var i = 0;
  for (i = 0; i < count; i = i + 1) {
    try {
      var row = null;
      try { row = listGroup[i]; } catch (e) { row = null; }
      if (row == null) {
        try { row = listGroup.get(i); } catch (e) { row = null; }
      }
      if (row == null) { continue; }
      
      var cls = "";
      try { cls = "" + row.className; } catch (e) {}
      if (cls == "android.view.ViewGroup" && isClickable(row)) {
        rows.push(row);
      }
    } catch (e) {}
  }
  return rows;
}

// ==============================
// 业务逻辑
// ==============================
function collectContributors(hostInfo, clickCount, clickWaitMs, stopAfterRows) {
  logi("开始采集贡献榜用户，采集数量=" + clickCount);

  var wrote = 0;
  var totalInserted = 0;

  // 获取当前已插入数量
  try {
    // callScript("DataHandler", "getCount")
    totalInserted = callScript("DataHandler", "getCount");
  } catch (e) {
    loge("getCount error: " + e);
  }

  var rank = 1;
  for (rank = 1; rank <= clickCount; rank = rank + 1) {
    if (totalInserted >= stopAfterRows) { 
      logw("达到数据库写入上限 " + stopAfterRows); 
      return;
    }

    var rankStr = "" + rank;
    var rankView = findRankTextView(rankStr);
    if (rankView == null) {
      logw("rank=" + rankStr + " TextView not found");
      continue;
    }

    var row = findClickableRowFromTextView(rankView);
    if (row == null) {
      logw("rank=" + rankStr + " row not found");
      continue;
    }

    var uesenumber = rankStr;
    
    // [新增] 每次处理新用户前，检查是否被弹窗阻挡
    callScript("LOOK_PopupHandler");
    
    var Consumption = getRightMostText(row);
    if (Consumption == "null" || Consumption == "undefined" || Consumption == "") {
      Consumption = "0";
    }
    logi("点击用户行 rank=" + rankStr + ", uesenumber=" + uesenumber + ", Consumption=" + Consumption);

    clickObj(row, "CLICK_USER_ROW_RANK_" + rankStr);
    sleepMs(clickWaitMs);

    if (!isDetailPage()) {
      logw("未进入用户详情，可能是被弹窗遮挡");
      callScript("LOOK_PopupHandler"); // 尝试消除弹窗
      backAndWait("USER_DETAIL_BACK_FAILSAFE", clickWaitMs);
    } else {
      var userInfo = readUserDetail();
      
      var cleanHostId = cleanId(hostInfo.id);

      // 调用数据处理脚本保存数据
      try {
        totalInserted = callScript("DataHandler", "insert", 
          APP_NAME,
          cleanHostId, hostInfo.name, hostInfo.fans, hostInfo.ip,
          uesenumber, userInfo.ueseid, userInfo.uesename, Consumption, userInfo.ueseip,
          userInfo.SummaryConsumption);
        wrote = wrote + 1;
        logi("数据保存成功，已采集 " + wrote + "/" + clickCount + ", 总计=" + totalInserted);
      } catch (e) {
        loge("callScript DataHandler error: " + e);
      }

      backAndWait("BACK_TO_MONTH", clickWaitMs);
    }
  }

  logi("采集完成，共采集 " + wrote + " 个用户");
}

// ==============================
// 主入口 - 通过函数参数接收数据
// callScript("LOOK_CollectContributors", hostId, hostName, hostFans, hostIp, clickCount, clickWaitMs, stopAfterRows)
// ==============================
function main(hostId, hostName, hostFans, hostIp, clickCount, clickWaitMs, stopAfterRows) {
  // 组装hostInfo对象
  var hostInfo = {
    id: hostId,
    name: hostName,
    fans: hostFans,
    ip: hostIp
  };
  
  // 设置默认值
  if (clickCount == null) {
    clickCount = DEFAULT_CLICK_COUNT;
  }
  if (clickWaitMs == null) {
    clickWaitMs = DEFAULT_CLICK_WAIT_MS;
  }
  if (stopAfterRows == null) {
    stopAfterRows = DEFAULT_STOP_AFTER_ROWS;
  }
  
  return collectContributors(hostInfo, clickCount, clickWaitMs, stopAfterRows);
}

// 注意：不要在文件末尾调用 main()
// 通过 callScript("LOOK_CollectContributors", ...) 调用时，引擎会自动执行 main()
