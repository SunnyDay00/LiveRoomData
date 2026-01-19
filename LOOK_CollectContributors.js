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
  return "" + (new Date().getTime()); 
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
  var obj = { id: "", name: "", ip: "", fans: "", consumption: "" };

  obj.id = getTextOfFirst("id:" + ID_USER_ID, {maxStep: 2});
  logi("id=" + obj.id);
  obj.name = getTextOfFirst("id:" + ID_USER_NAME, {maxStep: 2});
  logi("name=" + obj.name);
  obj.ip = getNumNearLabel("IP属地");
  logi("ip=" + obj.ip);
  obj.fans = getNumNearLabel("粉丝");
  logi("fans=" + obj.fans);
  obj.consumption = getNumNearLabel("消费音符");
  logi("consumption=" + obj.consumption);
  logi("读取用户详情完成");

  return obj;
}

// ==============================
// 月榜用户行处理
// ==============================
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

function findContributionFrame() {
  var ret = findRet("className:android.widget.FrameLayout", {flag: "find_all", maxStep: 3});
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

function getClickableUserRows(frameObj) {
  var rows = [];
  
  var ret = findRet("className:android.view.ViewGroup", {root: frameObj, flag: "find_all", maxStep: 4});
  if (ret == null) { return rows; }
  if (ret.length <= 0) { return rows; }
  
  var count = ret.length;
  var i = 0;
  for (i = 0; i < count; i = i + 1) {
    try {
      var vg = ret.views[i];
      if (vg == null) { continue; }
      
      if (isClickable(vg)) {
        // 检查这个 ViewGroup 下的 TextView 是否包含数字（排名）
        var tvRet = findRet("className:android.widget.TextView", {root: vg, flag: "find_all", maxStep: 2});
        if (tvRet != null && tvRet.length > 0) {
          var tvCount = tvRet.length;
          var j = 0;
          var found = false;
          for (j = 0; j < tvCount; j = j + 1) {
            try {
              var tv = tvRet.views[j];
              if (tv == null) { continue; }
              var t = "" + tv.text;
              if (t != "") {
                if ((t >= "0") && (t <= "9999")) {
                  found = true;
                  break;
                }
              }
            } catch (e) {}
          }
          if (found) { rows.push(vg); }
        }
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
  
  var frame = findContributionFrame();
  if (frame == null) { 
    loge("frame not found"); 
    return { success: false, wrote: 0, error: "frame not found" }; 
  }

  var rows = getClickableUserRows(frame);
  if (rows.length <= 0) { 
    loge("rows empty"); 
    return { success: false, wrote: 0, error: "rows empty" }; 
  }

  logi("找到 " + rows.length + " 个用户行");

  var wrote = 0;
  var i = 0;
  var totalInserted = 0;

  // 获取当前已插入数量
  try {
    // callScript("DataHandler", "getCount")
    totalInserted = callScript("DataHandler", "getCount");
  } catch (e) {
    loge("getCount error: " + e);
  }

  for (i = 0; i < rows.length; i = i + 1) {
    if (wrote >= clickCount) { 
      logi("已采集满 " + clickCount + " 个用户");
      break; 
    }
    
    if (totalInserted >= stopAfterRows) { 
      logw("达到数据库写入上限 " + stopAfterRows); 
      return;
    }

    var uesenumber = getBottomRightText(rows[i]);
    logi("点击用户行 " + (i + 1) + ", uesenumber=" + uesenumber);

    clickObj(rows[i], "CLICK_USER_ROW");
    sleepMs(clickWaitMs);

    if (!isDetailPage()) {
      logw("未进入用户详情，返回");
      backAndWait("USER_DETAIL_BACK_FAILSAFE", clickWaitMs);
    } else {
      var userInfo = readUserDetail();

      // 调用数据处理脚本保存数据
      // callScript("DataHandler", "insert", app_name, homeid, homename, fansnumber, homeip, uesenumber, ueseid, uesename, consumption, ueseip)
      try {
        totalInserted = callScript("DataHandler", "insert", 
          APP_NAME,
          hostInfo.id, hostInfo.name, hostInfo.fans, hostInfo.ip,
          uesenumber, userInfo.id, userInfo.name, userInfo.consumption, userInfo.ip);
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
