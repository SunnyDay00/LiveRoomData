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
  // 获取UTC时间戳,然后加上北京时间偏移(UTC+8小时)
  var utcTime = new Date().getTime();
  var beijingOffset = 8 * 60 * 60 * 1000; // 8小时转换为毫秒
  return "" + (utcTime + beijingOffset);
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
// UI 结构：
// ViewGroup
//   ├─ com.netease.play:id/desc (text: "粉丝"/"IP属地")
//   └─ com.netease.play:id/num (text: 实际数值)
//
function getNumNearLabel(labelText) {
  logi("[getNumNearLabel] 开始查找标签: " + labelText);
  
  // 1. 搜索标签 TextView
  if (!hasView("txt:" + labelText, {maxStep: 3})) {
    logw("[getNumNearLabel] 未找到标签: " + labelText);
    return "";
  }
  
  var labelView = getFirstView("txt:" + labelText, {maxStep: 3});
  if (labelView == null) { 
    logw("[getNumNearLabel] labelView 为 null");
    return ""; 
  }
  logi("[getNumNearLabel] 找到标签 TextView");
  
  // 2. 获取父 ViewGroup
  var parent = getParent(labelView);
  if (parent == null) { 
    logw("[getNumNearLabel] 父控件为 null");
    return ""; 
  }
  logi("[getNumNearLabel] 成功获取父 ViewGroup");
  
  // 3. 遍历父 ViewGroup 中的所有子控件，查找另一个 TextView
  // 根据UI树：父ViewGroup中只有两个TextView，一个是标签，另一个是数值
  var maxTry = 100;
  var i = 0;
  var foundTextViews = [];
  
  for (i = 0; i < maxTry; i = i + 1) {
    var child = null;
    try { child = parent[i]; } catch (e) {}
    if (child == null || child === undefined) { 
      break; 
    }
    
    // 检查是否是 TextView
    var childClass = "";
    try { childClass = "" + child.className; } catch (e) {}
    
    var childText = "";
    try { childText = "" + child.text; } catch (e) {}
    
    var childId = "";
    try { childId = "" + child.id; } catch (e) {}
    
    logi("[getNumNearLabel] 子控件[" + i + "] class=" + childClass + ", text=" + childText + ", id=" + childId);
    
    // 如果是 TextView 且不是标签本身
    if (childClass == "android.widget.TextView") {
      if (child !== labelView && childText != labelText) {
        // 忽略空值
        if (childText != "" && childText != "null" && childText != "undefined") {
          foundTextViews.push({index: i, text: childText, id: childId});
          logi("[getNumNearLabel] 找到候选 TextView: " + childText);
        }
      }
    }
  }
  
  logi("[getNumNearLabel] 遍历完成，共找到 " + foundTextViews.length + " 个候选 TextView");
  
  // 4. 返回找到的数值 TextView
  if (foundTextViews.length == 0) {
    logw("[getNumNearLabel] 未找到任何有效的数值 TextView");
    return "";
  }
  
  // 如果只有一个，直接返回
  if (foundTextViews.length == 1) {
    var result = foundTextViews[0].text;
    logi("[getNumNearLabel] 返回唯一的候选值: " + result);
    return result;
  }
  
  // 如果有多个，优先选择 id 包含 "num" 的
  var j = 0;
  for (j = 0; j < foundTextViews.length; j = j + 1) {
    var tv = foundTextViews[j];
    if (tv.id.indexOf("num") >= 0) {
      logi("[getNumNearLabel] 返回 id 包含 'num' 的值: " + tv.text);
      return tv.text;
    }
  }
  
  // 否则返回第一个
  var firstResult = foundTextViews[0].text;
  logi("[getNumNearLabel] 返回第一个候选值: " + firstResult);
  return firstResult;
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
  logi("fans=" + obj.fans);
  // obj.consumption = getNumNearLabel("消费音符");
  // logi("consumption=" + obj.consumption);
  logi("读取主播详情完成");

  return obj;
}

// ==============================
function enterHostDetailFromLive(clickWaitMs) {
  logi("开始查找主播详情入口...");
  
  // 检查 header
  if (!hasView("id:" + ID_HEADER, {maxStep: 2})) { 
    loge("header未找到"); 
    return false; 
  }
  logi("找到header容器");

  var clicked = false;
  var avatarView = null;
  var bgView = null;
  
  // 查找 avatar 和 bgView
  if (hasView("id:" + ID_AVATAR, {maxStep: 2})) {
    avatarView = getFirstView("id:" + ID_AVATAR, {maxStep: 2});
  }
  if (hasView("id:" + ID_BGVIEW, {maxStep: 2})) {
    bgView = getFirstView("id:" + ID_BGVIEW, {maxStep: 2});
  }

  // 方式1: 优先点击 avatar（需可点击）
  if (avatarView != null && avatarView.clickable) {
    logi("尝试通过avatar进入...");
    clickObj(avatarView, "CLICK_AVATAR");
    sleepMs(clickWaitMs);
    clicked = true;
  } else if (bgView != null && bgView.clickable) {
    // 方式2: 其次点击 bgView（需可点击）
    logi("尝试通过bgView进入...");
    clickObj(bgView, "CLICK_BGVIEW");
    sleepMs(clickWaitMs);
    clicked = true;
  } else if (avatarView != null) {
    // 方式3: 兜底点击 avatar（忽略 clickable）
    logi("尝试通过avatar兜底进入...");
    clickObj(avatarView, "CLICK_AVATAR_FALLBACK");
    sleepMs(clickWaitMs);
    clicked = true;
  }
  
  if (!clicked) {
    loge("bgView和avatar都未找到或不可点击");
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
