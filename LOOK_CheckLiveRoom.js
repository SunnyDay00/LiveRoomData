/**
 * LOOK_CheckLiveRoom.js - 检查直播间有效性脚本
 * 
 * 通过检测页面是否有 vflipper 组件来判断直播间是否有效。
 * 
 * 使用方式：
 *   var result = callScript("LOOK_CheckLiveRoom.js", {
 *     retryCount: 3,      // 重复检查次数
 *     checkInterval: 1000 // 每次检查间隔（毫秒）
 *   });
 *   // result.isValid: true/false
 */

// ==============================
// 配置
// ==============================
var ID_VFLIPPER = "com.netease.play:id/vflipper";

// 礼物弹幕相关ID
var GIFT_NOTICE_IDS = [
  "com.netease.play:id/liveNoticeRootContainer"
];

// PopupHandler 逻辑内联：无效直播间 / 全屏广告 rootContainer
var POPUP_INVALID_ROOM_BTN_ID = "com.netease.play:id/btn_join_chorus";
var POPUP_FULLSCREEN_ROOT_ID = "com.netease.play:id/rootContainer";
var POPUP_FULLSCREEN_BACK_MAX_RETRY = 5;
var POPUP_FULLSCREEN_BACK_WAIT_MS = 800;

// 礼物弹幕等待时间（毫秒）
var GIFT_OVERLAY_WAIT_MS = 1000;

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
  console.info("[" + nowStr() + "][CheckLiveRoom][INFO] " + msg);
  try { floatMessage("[CheckLiveRoom] " + msg); } catch (e) {}
}

function logw(msg) { 
  console.warn("[" + nowStr() + "][CheckLiveRoom][WARN] " + msg);
  try { floatMessage("[CheckLiveRoom][WARN] " + msg); } catch (e) {}
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
// 官方文档: 检查 ret.length > 0，然后用 ret.views[0] 访问元素
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

function hasRet(ret) {
  if (ret == null) {
    return false;
  }
  if (ret.length > 0) {
    return true;
  }
  return false;
}

// ==============================
// 礼物弹幕检测
// ==============================
function hasGiftOverlay() {
  var ids = GIFT_NOTICE_IDS;
  if (ids == null) {
    return false;
  }
  var len = ids.length;
  var i = 0;
  for (i = 0; i < len; i = i + 1) {
    var id = ids[i];
    if (hasView("id:" + id, {maxStep: 3})) {
      return true;
    }
  }
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

function normalizePopupResult(ret) {
  var r = {handled: false, invalidRoom: false, needRestart: false};
  if (ret == null) { return r; }
  if (ret === "INVALID_ROOM") {
    r.handled = true;
    r.invalidRoom = true;
    return r;
  }
  if (ret === "NEED_RESTART") {
    r.handled = true;
    r.needRestart = true;
    return r;
  }
  if (typeof ret === "object") {
    if (ret.handled === true) { r.handled = true; }
    if (ret.invalidRoom === true) { r.invalidRoom = true; }
    if (ret.needRestart === true) { r.needRestart = true; }
    return r;
  }
  if (ret === true) { r.handled = true; }
  return r;
}

function handleInvalidLiveRoomPopupInCheck() {
  var btnRet = findRet("id:" + POPUP_INVALID_ROOM_BTN_ID, {maxStep: 3});
  if (!hasRet(btnRet)) {
    return false;
  }
  logi("检测到 [无效直播间]（加入合唱），交由主流程跳过");
  return true;
}

function handleFullScreenAdPopupInCheck() {
  var result = {handled: false, needRestart: false};
  var rootTag = "id:" + POPUP_FULLSCREEN_ROOT_ID;
  if (!hasView(rootTag, {maxStep: 3})) {
    return result;
  }

  result.handled = true;
  logi("检测到 [全屏广告]（rootContainer），不点关闭，改为 back() 退回");

  var i = 0;
  for (i = 0; i < POPUP_FULLSCREEN_BACK_MAX_RETRY; i = i + 1) {
    if (!hasView(rootTag, {maxStep: 3})) {
      return result;
    }
    logi("[全屏广告] back 重试 " + (i + 1) + "/" + POPUP_FULLSCREEN_BACK_MAX_RETRY);
    try {
      back();
    } catch (e) {
      logw("[全屏广告] back 异常: " + e);
    }
    sleepMs(POPUP_FULLSCREEN_BACK_WAIT_MS);
    if (!hasView(rootTag, {maxStep: 3})) {
      logi("[全屏广告] rootContainer 已消失");
      return result;
    }
  }

  if (hasView(rootTag, {maxStep: 3})) {
    result.needRestart = true;
    logw("[全屏广告] back 重试 " + POPUP_FULLSCREEN_BACK_MAX_RETRY + " 次后仍存在，通知主流程重启应用");
  }
  return result;
}

function handlePopupModulesInCheck() {
  var result = {handled: false, invalidRoom: false, needRestart: false, reason: ""};

  var adRet = handleFullScreenAdPopupInCheck();
  if (adRet != null) {
    if (adRet.handled === true) {
      result.handled = true;
      result.reason = "fullscreen_ad";
    }
    if (adRet.needRestart === true) {
      result.handled = true;
      result.needRestart = true;
      result.reason = "fullscreen_ad_stuck";
      return result;
    }
  }

  if (handleInvalidLiveRoomPopupInCheck()) {
    result.handled = true;
    result.invalidRoom = true;
    result.reason = "invalid_room";
  }

  return result;
}

function handlePopupAndGiftOverlay() {
  // 处理可能的弹窗（全屏广告/无效直播间）
  var popup = normalizePopupResult(handlePopupModulesInCheck());
  if (popup != null && popup.invalidRoom == true) {
    return "INVALID_ROOM";
  }
  if (popup != null && popup.needRestart == true) {
    return "NEED_RESTART";
  }
  if (popup != null && popup.handled == true) {
    logi("检测并处理了弹窗(广告)，额外等待 2000ms 让直播间恢复...");
    sleepMs(2000);

    // 处理完弹窗后，再次尝试处理一次（防止多重弹窗）
    var popupAgain = normalizePopupResult(handlePopupModulesInCheck());
    if (popupAgain != null && popupAgain.invalidRoom == true) {
      return "INVALID_ROOM";
    }
    if (popupAgain != null && popupAgain.needRestart == true) {
      return "NEED_RESTART";
    }
    if (popupAgain != null && popupAgain.handled == true) {
      logi("再次检测并处理了弹窗，等待 1000ms...");
      sleepMs(1000);
    }
  }
  
  // 如果检测到礼物弹幕，才等待
  if (hasGiftOverlay()) {
    logi("检测到礼物弹幕，等待 " + GIFT_OVERLAY_WAIT_MS + "ms...");
    waitForGiftOverlayToDisappear();
  } else {
    logi("未检测到礼物弹幕，继续重试...");
  }

  return null;
}

// ==============================
// 检查逻辑
// ==============================
function hasVflipperComponent() {
  return hasView("id:" + ID_VFLIPPER, {maxStep: 3});
}

function checkLiveRoomValid(retryCount, checkInterval) {
  logi("开始检查直播间有效性，重试次数=" + retryCount + "，间隔=" + checkInterval + "ms");

  var i = 0;
  for (i = 0; i < retryCount; i = i + 1) {
    logi("第 " + (i + 1) + " 次检查...");

    // 先处理弹窗/礼物弹幕，再做有效性判断，避免开屏广告干扰后续点击
    var overlayRet = handlePopupAndGiftOverlay();
    if (overlayRet == "INVALID_ROOM") {
      logi("检测到无效直播间（加入合唱），返回给主流程处理");
      return "INVALID_ROOM";
    }
    if (overlayRet == "NEED_RESTART") {
      logw("全屏广告 rootContainer 多次返回仍存在，返回给主流程执行重启");
      return "NEED_RESTART";
    }

    if (hasVflipperComponent()) {
      logi("检测到 vflipper 组件，直播间有效");
      return true;
    }
    
    logw("未检测到 vflipper 组件");
    
    // 若检查无效，等待后再试
    if (i < retryCount - 1) {
      sleepMs(checkInterval);
    }
  }
  
  logi("检查完成，直播间无效");
  return false;
}

// ==============================
// 主入口 - 通过函数参数接收数据
// callScript("LOOK_CheckLiveRoom", retryCount, checkInterval)
// ==============================
function main(retryCount, checkInterval) {
  // 设置默认值
  if (retryCount == null) {
    retryCount = 3;
  }
  if (checkInterval == null) {
    checkInterval = 1000;
  }
  
  var ret = checkLiveRoomValid(retryCount, checkInterval);
  if (ret == "INVALID_ROOM") {
    return {valid: false, invalidRoom: true, needRestart: false};
  }
  if (ret == "NEED_RESTART") {
    return {valid: false, invalidRoom: false, needRestart: true};
  }
  return {valid: (ret === true), invalidRoom: false, needRestart: false};
}

// 注意：不要在文件末尾调用 main()
// 通过 callScript("LOOK_CheckLiveRoom", retryCount, checkInterval) 调用时，
// 引擎会自动执行 main() 函数并传入参数
