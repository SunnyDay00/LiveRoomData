/**
 * LOOK_ContributionRank.js - 贡献榜界面脚本
 * 
 * 进入魅力榜 -> 贡献榜 -> 月榜，然后调用用户采集脚本。
 * 
 * 使用方式：
 *   var result = callScript("LOOK_ContributionRank.js", {
 *     hostInfo: { id, name, fans, ip },  // 主播信息
 *     clickCount: 5,      // 采集用户数量
 *     clickWaitMs: 1500,  // 点击后等待时间
 *     stopAfterRows: 200, // 达到此数量停止
 *     retryCount: 3       // 进入界面重试次数
 *   });
 */

// ==============================
// 配置
// ==============================
var ID_HEADER = "com.netease.play:id/headerUiContainer";
var ID_VFLIPPER = "com.netease.play:id/vflipper";

// 注意：rankText 是其他界面的组件，不应该点击
// var ID_RANKTEXT = "com.netease.play:id/rankText";

// 礼物弹幕相关ID
var GIFT_NOTICE_IDS = [
  "com.netease.play:id/liveNoticeRootContainer",
  "com.netease.play:id/imageBg",
  "com.netease.play:id/noticeContent",
  "com.netease.play:id/liveNoticeContainer",
  "com.netease.play:id/liveNotice",
  "com.netease.play:id/liveIcon"
];

// 礼物弹幕等待时间（毫秒）
var GIFT_OVERLAY_WAIT_MS = 3000;

var DEFAULT_CLICK_COUNT = 5;
var DEFAULT_CLICK_WAIT_MS = 1500;
var DEFAULT_STOP_AFTER_ROWS = 200;
var DEFAULT_RETRY_COUNT = 3;

// ==============================
// 工具函数
// ==============================
function nowStr() { 
  return "" + (new Date().getTime()); 
}

function logi(msg) { 
  console.info("[" + nowStr() + "][ContributionRank][INFO] " + msg);
  try { floatMessage("[ContribRank] " + msg); } catch (e) {}
}

function logw(msg) { 
  console.warn("[" + nowStr() + "][ContributionRank][WARN] " + msg);
  try { floatMessage("[ContribRank][WARN] " + msg); } catch (e) {}
}

function loge(msg) { 
  console.error("[" + nowStr() + "][ContributionRank][ERROR] " + msg);
  try { floatMessage("[ContribRank][ERROR] " + msg); } catch (e) {}
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

// 安全获取数组长度（兼容引擎特殊数组）
function safeLength(arr) {
  if (arr == null) { return 0; }
  try {
    var len = arr.getLength();
    if (len != null) { return len; }
  } catch (e1) {}
  try {
    var len2 = arr.length;
    if (len2 != null) { return len2; }
  } catch (e2) {}
  return 0;
}

function getViews(tag, options) {
  var ret = findRet(tag, options);
  if (ret != null) {
    if (ret.views != null) {
      return ret.views;
    }
  }
  return null;
}

// 检查views是否有元素
function hasViews(views) {
  if (views == null) { return false; }
  try {
    if (views[0] != null) { return true; }
  } catch (e) {}
  return false;
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
// 礼物弹幕检测
// ==============================
function hasGiftOverlay() {
  // 直接检查每个ID，使用 hasViews 辅助函数
  if (hasViews(getViews("id:com.netease.play:id/liveNoticeRootContainer", {maxStep: 3}))) { return true; }
  if (hasViews(getViews("id:com.netease.play:id/imageBg", {maxStep: 3}))) { return true; }
  if (hasViews(getViews("id:com.netease.play:id/noticeContent", {maxStep: 3}))) { return true; }
  if (hasViews(getViews("id:com.netease.play:id/liveNoticeContainer", {maxStep: 3}))) { return true; }
  if (hasViews(getViews("id:com.netease.play:id/liveNotice", {maxStep: 3}))) { return true; }
  if (hasViews(getViews("id:com.netease.play:id/liveIcon", {maxStep: 3}))) { return true; }
  return false;
}

function waitForGiftOverlayToDisappear() {
  if (hasGiftOverlay()) {
    logi("检测到礼物弹幕，等待 " + GIFT_OVERLAY_WAIT_MS + "ms...");
    sleepMs(GIFT_OVERLAY_WAIT_MS);
    
    // 再次检查
    if (hasGiftOverlay()) {
      logi("礼物弹幕仍存在，继续等待...");
      sleepMs(GIFT_OVERLAY_WAIT_MS);
    }
  }
}

// ==============================
// 进入魅力榜（通过 vflipper 下的 TextView）
// ==============================
function enterCharmRankFromLive(clickWaitMs) {
  logi("开始查找魅力榜入口...");
  
  // 首先等待礼物弹幕消失
  waitForGiftOverlayToDisappear();
  
  var header = getViews("id:" + ID_HEADER, {maxStep: 2});
  if (header.length <= 0) { 
    loge("未找到header容器"); 
    return false; 
  }
  logi("找到header容器");

  // 只通过 vflipper 下的 TextView 进入（不使用 rankText）
  var vf = getViews("id:" + ID_VFLIPPER, {root: header[0], maxStep: 2});
  logi("vflipper查找结果: 数量=" + vf.length);

  if (vf.length <= 0) {
    loge("未找到 vflipper 组件");
    return false;
  }

  // 查找 vflipper 下的所有 TextView
  var tvs = getViews("className:android.widget.TextView", {root: vf[0], flag: "find_all", maxStep: 3});
  logi("vflipper下找到 " + tvs.length + " 个TextView");

  var clicked = false;
  var i = 0;
  
  // 遍历所有 TextView，找到可点击的并点击
  for (i = 0; i < tvs.length; i = i + 1) {
    if (isClickable(tvs[i])) {
      logi("找到可点击的TextView，尝试点击...");
      clicked = clickObj(tvs[i], "CHARM_ENTRY_VFLIPPER_TV");
      if (clicked) {
        sleepMs(clickWaitMs);
        break;
      }
    }
  }

  // 如果没有可点击的TextView，尝试点击第一个TextView
  if (!clicked) {
    if (tvs.length > 0) {
      logi("没有可点击的TextView，尝试点击第一个...");
      clicked = clickObj(tvs[0], "CHARM_ENTRY_FIRST_TV");
      if (clicked) {
        sleepMs(clickWaitMs);
      }
    }
  }

  if (!clicked) {
    loge("未能找到任何可点击的魅力榜入口");
    return false;
  }

  // 验证是否进入魅力榜
  var has1 = (getViews("txt:魅力榜", {maxStep: 2}).length > 0);
  var has2 = (getViews("txt:当前房间", {maxStep: 2}).length > 0);
  logi("页面验证: 魅力榜=" + has1 + ", 当前房间=" + has2);
  if (has1) {
    if (has2) {
      logi("成功进入魅力榜界面");
      return true;
    }
  }

  loge("进入魅力榜失败");
  return false;
}

// ==============================
// 进入贡献榜
// ==============================
function enterContributionRank(clickWaitMs) {
  logi("尝试进入贡献榜...");
  
  var vs = getViews("className:android.view.View", {flag: "find_all", maxStep: 3});
  var i = 0;
  var clicked = false;
  
  for (i = 0; i < vs.length; i = i + 1) {
    try {
      if (("" + vs[i].text) == "贡献榜") {
        clickObj(vs[i], "CLICK_CONTRIB_TAB");
        sleepMs(clickWaitMs);
        clicked = true;
        break;
      }
    } catch (e) {}
  }

  if (!clicked) {
    loge("未找到贡献榜按钮");
    return false;
  }

  var hasA = (getViews("txt:日榜奖励", {maxStep: 2}).length > 0);
  var hasB = (getViews("txt:日榜", {maxStep: 2}).length > 0);
  if (hasA) {
    if (hasB) {
      logi("成功进入贡献榜界面");
      return true;
    }
  }

  loge("进入贡献榜失败");
  return false;
}

// ==============================
// 切换到月榜
// ==============================
function switchToMonthRank(clickWaitMs) {
  logi("尝试切换到月榜...");
  
  var day = getViews("txt:日榜", {maxStep: 2});
  if (day.length <= 0) { 
    loge("未找到日榜入口"); 
    return false; 
  }

  var p = getParent(day[0]);
  if (p != null) { 
    clickObj(p, "OPEN_RANK_OPTIONS"); 
  } else { 
    clickObj(day[0], "OPEN_RANK_OPTIONS_TEXT"); 
  }
  sleepMs(clickWaitMs);

  var month = getViews("txt:月榜", {maxStep: 2});
  if (month.length <= 0) { 
    loge("未找到月榜选项"); 
    return false; 
  }

  var pm = getParent(month[0]);
  if (pm != null) { 
    clickObj(pm, "SELECT_MONTH"); 
  } else { 
    clickObj(month[0], "SELECT_MONTH_TEXT"); 
  }
  sleepMs(clickWaitMs);

  var hasM = (getViews("txt:月榜", {maxStep: 2}).length > 0);
  var hasR = (getViews("txt:日榜奖励", {maxStep: 2}).length > 0);
  if (hasM) {
    if (hasR) {
      logi("成功切换到月榜");
      return true;
    }
  }

  loge("切换月榜失败");
  return false;
}

// ==============================
// 业务逻辑
// ==============================
function processContributionRank(hostInfo, clickCount, clickWaitMs, stopAfterRows, retryCount) {
  logi("开始处理贡献榜，重试次数=" + retryCount);
  
  var r = 0;
  
  // 尝试进入魅力榜
  var charmEntered = false;
  for (r = 0; r < retryCount; r = r + 1) {
    if (enterCharmRankFromLive(clickWaitMs)) {
      charmEntered = true;
      break;
    }
    logw("进入魅力榜失败，第 " + (r + 1) + " 次重试...");
    sleepMs(500);
  }
  
  if (!charmEntered) {
    loge("多次尝试进入魅力榜均失败");
    return { success: false, error: "enter charm failed" };
  }
  
  // 进入贡献榜
  if (!enterContributionRank(clickWaitMs)) {
    loge("进入贡献榜失败");
    backAndWait("BACK_CHARM_FAIL", clickWaitMs);
    return { success: false, error: "enter contribution failed" };
  }
  
  // 切换到月榜
  if (!switchToMonthRank(clickWaitMs)) {
    loge("切换月榜失败");
    backAndWait("BACK_CONTRIB_FAIL", clickWaitMs);
    backAndWait("BACK_CHARM_FAIL_2", clickWaitMs);
    return { success: false, error: "switch month failed" };
  }
  
  // 调用用户采集脚本
  logi("开始采集贡献榜用户...");
  try {
    // callScript("LOOK_CollectContributors", hostId, hostName, hostFans, hostIp, clickCount, clickWaitMs, stopAfterRows)
    callScript("LOOK_CollectContributors", 
      hostInfo.id, hostInfo.name, hostInfo.fans, hostInfo.ip,
      clickCount, clickWaitMs, stopAfterRows);
  } catch (e) {
    loge("callScript CollectContributors error: " + e);
  }
  
  // 返回：月榜 -> 贡献榜 -> 魅力榜/直播 -> 直播
  logi("返回直播间...");
  backAndWait("BACK_MONTH_TO_CONTRIB", clickWaitMs);
  backAndWait("BACK_CONTRIB_TO_CHARM", clickWaitMs);
  backAndWait("BACK_CHARM_TO_LIVE", clickWaitMs);
}

// ==============================
// 主入口 - 通过函数参数接收数据
// callScript("LOOK_ContributionRank", hostId, hostName, hostFans, hostIp, clickCount, clickWaitMs, stopAfterRows, retryCount)
// ==============================
function main(hostId, hostName, hostFans, hostIp, clickCount, clickWaitMs, stopAfterRows, retryCount) {
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
  if (retryCount == null) {
    retryCount = DEFAULT_RETRY_COUNT;
  }
  
  return processContributionRank(hostInfo, clickCount, clickWaitMs, stopAfterRows, retryCount);
}

// 执行
main();
