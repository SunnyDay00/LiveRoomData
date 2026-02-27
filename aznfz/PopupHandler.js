/**
 * PopupHandler.js - 全局弹窗处理脚本
 * 
 * 专门负责识别和处理各类阻断流程的弹窗，如：
 * 1. 无效直播间（加入合唱按钮）
 * 2. 全屏广告（rootContainer + closeBtn）
 * 
 * 备注：
 *   该脚本保留为独立弹窗处理实现，主流程脚本已改为内联逻辑，不再直接调用本脚本。
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

function logw(msg) { 
  console.warn("[" + nowStr() + "][PopupHandler][WARN] " + msg);
  try { floatMessage("[Popup][WARN] " + msg); } catch (e) {}
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

function restartLookAppFromPopupHandler(reason) {
  var pkg = "com.netease.play";
  logw("[RESTART] reason=" + reason + "，执行重启应用");
  try {
    killBackgroundApp(pkg);
  } catch (e1) {
    logw("[RESTART] killBackgroundApp 异常: " + e1);
  }
  sleepMs(2000);
  try {
    launchApp(pkg, "");
  } catch (e2) {
    logw("[RESTART] launchApp 异常: " + e2);
  }
  sleepMs(5000);
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
  var rootTag = "id:com.netease.play:id/rootContainer";
  if (!hasView(rootTag, {maxStep: 3})) { return false; }

  var maxRetry = 5;
  var i = 0;
  logi("检测到 [全屏广告]（rootContainer），不点关闭，改为 back() 退回");
  for (i = 0; i < maxRetry; i = i + 1) {
    if (!hasView(rootTag, {maxStep: 3})) {
      return true;
    }
    logi("[全屏广告] back 重试 " + (i + 1) + "/" + maxRetry);
    try {
      back();
    } catch (e) {
      logw("[全屏广告] back 异常: " + e);
    }
    sleepMs(800);
    if (!hasView(rootTag, {maxStep: 3})) {
      logi("[全屏广告] rootContainer 已消失");
      return true;
    }
  }

  if (hasView(rootTag, {maxStep: 3})) {
    logw("[全屏广告] back 重试 " + maxRetry + " 次后仍存在，触发重启");
    restartLookAppFromPopupHandler("rootContainer_stuck");
    if (hasView(rootTag, {maxStep: 3})) {
      logw("[全屏广告] 重启后 rootContainer 仍存在");
    } else {
      logi("[全屏广告] 重启后 rootContainer 已消失");
    }
    return true;
  }
  return true;
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
