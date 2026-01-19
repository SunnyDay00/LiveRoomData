/**
 * LOOK_HostInfo.js - 主播信息采集脚本
 * 
 * 进入主播详情页，采集主播信息后返回直播间。
 * 
 * 使用方式：
 *   var result = callScript("LOOK_HostInfo.js", {
 *     retryCount: 3,      // 进入详情页重试次数
 *     clickWaitMs: 1500   // 点击后等待时间
 *   });
 *   // result.success: true/false
 *   // result.hostInfo: { id, name, ip, fans, consumption }
 */

// ==============================
// 配置
// ==============================
var ID_HEADER = "com.netease.play:id/headerUiContainer";
var ID_AVATAR = "com.netease.play:id/avatar";
var ID_BGVIEW = "com.netease.play:id/bgView";
var ID_USER_ID = "com.netease.play:id/id";
var ID_USER_NAME = "com.netease.play:id/artist_name";
var ID_NUM = "com.netease.play:id/num";

var DEFAULT_CLICK_WAIT_MS = 1500;
var DEFAULT_RETRY_COUNT = 3;

// ==============================
// 工具函数
// ==============================
function nowStr() { 
  return "" + (new Date().getTime()); 
}

function logi(msg) { 
  console.info("[" + nowStr() + "][HostInfo][INFO] " + msg);
  try { floatMessage("[HostInfo] " + msg); } catch (e) {}
}

function logw(msg) { 
  console.warn("[" + nowStr() + "][HostInfo][WARN] " + msg);
  try { floatMessage("[HostInfo][WARN] " + msg); } catch (e) {}
}

function loge(msg) { 
  console.error("[" + nowStr() + "][HostInfo][ERROR] " + msg);
  try { floatMessage("[HostInfo][ERROR] " + msg); } catch (e) {}
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
  try {
    var hasId = hasView("id:" + ID_USER_ID, {maxStep: 2});
    var hasName = hasView("id:" + ID_USER_NAME, {maxStep: 2});
    if (hasId && hasName) {
      return true;
    }
  } catch (e) {
    loge("isDetailPage exception=" + e);
  }
  return false;
}

// ==============================
// 详情页字段提取
// ==============================
function getNumNearLabel(labelText) {
  var labels = getViews("className:android.widget.TextView", {flag: "find_all", maxStep: 2});
  var i = 0;
  for (i = 0; i < labels.length; i = i + 1) {
    try {
      if (("" + labels[i].text) == labelText) {
        var p = getParent(labels[i]);
        if (p != null) {
          var v = getTextOfFirst("id:" + ID_NUM, {root: p, maxStep: 2});
          if (v != null) {
            if (v != "") {
              return v;
            }
          }
        }
      }
    } catch (e) {}
  }
  return "";
}

function readDetailBasic() {
  logi("开始读取主播详情...");
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
  logi("读取主播详情完成");

  return obj;
}

// ==============================
// 业务逻辑
// ==============================
function enterHostDetailFromLive(clickWaitMs) {
  logi("开始查找主播详情入口...");
  
  var header = getViews("id:" + ID_HEADER, {maxStep: 2});
  if (header.length <= 0) { 
    loge("header未找到"); 
    return false; 
  }
  logi("找到header容器");

  var clicked = false;
  
  // 方式1: 尝试点击 avatar
  var avatar = getViews("id:" + ID_AVATAR, {root: header[0], maxStep: 2});
  logi("avatar查找结果: 数量=" + avatar.length);
  if (avatar.length > 0) {
    logi("尝试通过avatar进入...");
    clickObj(avatar[0], "CLICK_AVATAR");
    sleepMs(clickWaitMs);
    clicked = true;
  }
  
  // 方式2: 如果 avatar 没找到，尝试 bgView
  if (!clicked) {
    var bg = getViews("id:" + ID_BGVIEW, {root: header[0], maxStep: 2});
    logi("bgView查找结果: 数量=" + bg.length);
    if (bg.length > 0) {
      logi("尝试通过bgView进入...");
      clickObj(bg[0], "CLICK_BGVIEW");
      sleepMs(clickWaitMs);
      clicked = true;
    }
  }
  
  if (!clicked) {
    loge("avatar和bgView都未找到");
    return false;
  }

  logi("检测是否进入详情页...");
  if (isDetailPage()) { 
    logi("成功进入主播详情页"); 
    return true; 
  }
  loge("未能进入详情页，页面验证失败");
  return false;
}

function collectHostInfo(retryCount, clickWaitMs) {
  logi("开始采集主播信息，重试次数=" + retryCount);
  
  var r = 0;
  for (r = 0; r < retryCount; r = r + 1) {
    logi("第 " + (r + 1) + " 次尝试进入主播详情页...");
    
    if (enterHostDetailFromLive(clickWaitMs)) {
      // 成功进入，读取信息
      var hostInfo = readDetailBasic();
      
      // 返回直播间
      logi("返回直播间...");
      backAndWait("BACK_TO_LIVE", clickWaitMs);
      
      // 检查是否已退出详情页
      if (isDetailPage()) {
        logi("仍在详情页，再次返回");
        backAndWait("BACK_TO_LIVE_2", clickWaitMs);
      }
      
      return { success: true, hostInfo: hostInfo };
    }
    
    logw("进入主播详情页失败，重试...");
    sleepMs(500);
  }
  
  loge("多次尝试进入主播详情页均失败");
  return { success: false, hostInfo: null };
}

// ==============================
// 主入口 - 通过函数参数接收数据
// callScript("LOOK_HostInfo", retryCount, clickWaitMs)
// ==============================
function main(retryCount, clickWaitMs) {
  // 设置默认值
  if (retryCount == null) {
    retryCount = DEFAULT_RETRY_COUNT;
  }
  if (clickWaitMs == null) {
    clickWaitMs = DEFAULT_CLICK_WAIT_MS;
  }
  
  return collectHostInfo(retryCount, clickWaitMs);
}

// 注意：不要在文件末尾调用 main()
// 通过 callScript("LOOK_HostInfo", retryCount, clickWaitMs) 调用时，引擎会自动执行 main()
