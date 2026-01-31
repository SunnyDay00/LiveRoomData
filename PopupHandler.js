/**
 * PopupHandler.js - 全局弹窗处理脚本
 * 
 * 专门负责识别和处理各类阻断流程的弹窗，如：
 * 1. 无效直播间（加入合唱按钮）
 * 2. 全屏广告（rootContainer + closeBtn）
 * 
 * 使用方式：
 *   var handled = callScript("PopupHandler");
 *   if (handled) { sleep(1000); }
 */

// ==============================
// 工具函数 (复制自其他脚本以保持独立性)
// ==============================
function nowStr() { 
  var utcTime = new Date().getTime();
  var beijingOffset = 8 * 60 * 60 * 1000;
  return "" + (utcTime + beijingOffset);
}

function logi(msg) { 
  console.info("[" + nowStr() + "][PopupHandler][INFO] " + msg);
  try { floatMessage("[Popup] " + msg); } catch (e) {}
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
  } catch (e) { ret = null; }
  return ret;
}

function hasView(tag, options) {
  var ret = findRet(tag, options);
  if (ret != null && ret.length > 0) { return true; }
  return false;
}

function getFirstView(tag, options) {
  var ret = findRet(tag, options);
  if (ret != null && ret.length > 0) { return ret.views[0]; }
  return null;
}

function hasRet(ret) {
  return ret != null && ret.length > 0;
}

function clickObj(v, stepName) {
  try {
    click(v, {click: true});
    logi("[" + stepName + "] click ok");
    return true;
  } catch (e) {
    return false;
  }
}

// ==============================
// 具体的弹窗处理逻辑
// ==============================

// 1. 处理无效直播间（加入合唱）
function handleInvalidLiveRoom() {
  var btnRet = findRet("id:com.netease.play:id/btn_join_chorus", {maxStep: 3});
  if (!hasRet(btnRet)) { return false; }

  logi("检测到 [无效直播间]（加入合唱），仅标记交由主流程跳过");
  return true;
}

// 2. 处理全屏广告
function handleFullScreenAd() {
  // 更精确的检测：必须同时存在广告容器和关闭按钮
  // 先找关闭按钮，避免无广告时做两次等待型查找
  var closeRet = findRet("id:com.netease.play:id/closeBtn", {maxStep: 5});
  if (!hasRet(closeRet)) { return false; }

  var containerRet = findRet("id:com.netease.play:id/rootContainer", {maxStep: 5});
  if (!hasRet(containerRet)) { return false; }

  logi("检测到 [全屏广告]（rootContainer + closeBtn），尝试点击关闭...");

  if (clickObj(closeRet.views[0], "CLICK_AD_CLOSE")) {
    sleepMs(500);  // 等待关闭动画
    return true;
  }
  return false;
}

// ==============================
// 主流程
// ==============================
function main() {
  var result = {
    handled: false,
    invalidRoom: false,
    reason: ""
  };

  if (handleFullScreenAd()) {
    result.handled = true;
    if (result.reason == "") { result.reason = "fullscreen_ad"; }
  }

  if (handleInvalidLiveRoom()) {
    result.handled = true;
    result.invalidRoom = true;
    result.reason = "invalid_room";
  }

  return result;
}
