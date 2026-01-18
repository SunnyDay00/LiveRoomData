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
  "com.netease.play:id/liveNoticeRootContainer",
  "com.netease.play:id/imageBg",
  "com.netease.play:id/noticeContent",
  "com.netease.play:id/liveNoticeContainer",
  "com.netease.play:id/liveNotice",
  "com.netease.play:id/liveIcon"
];

// 礼物弹幕等待时间（毫秒）
var GIFT_OVERLAY_WAIT_MS = 3000;

// ==============================
// 工具函数
// ==============================
function nowStr() { 
  return "" + (new Date().getTime()); 
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

// 安全获取数组长度（兼容引擎特殊数组）
function safeLength(arr) {
  if (arr == null) { return 0; }
  try {
    // 尝试使用 getLength() 方法
    var len = arr.getLength();
    if (len != null) { return len; }
  } catch (e1) {}
  try {
    // 尝试使用 .length 属性
    var len2 = arr.length;
    if (len2 != null) { return len2; }
  } catch (e2) {}
  // 都失败则返回0
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
  // 尝试访问第一个元素
  try {
    if (views[0] != null) { return true; }
  } catch (e) {}
  return false;
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
// 检查逻辑
// ==============================
function hasVflipperComponent() {
  var views = getViews("id:" + ID_VFLIPPER, {maxStep: 3});
  return (views.length > 0);
}

function checkLiveRoomValid(retryCount, checkInterval) {
  logi("开始检查直播间有效性，重试次数=" + retryCount + "，间隔=" + checkInterval + "ms");
  
  // 首先等待礼物弹幕消失
  waitForGiftOverlayToDisappear();
  
  var i = 0;
  for (i = 0; i < retryCount; i = i + 1) {
    logi("第 " + (i + 1) + " 次检查...");
    
    // 每次检查前都检测礼物弹幕
    if (hasGiftOverlay()) {
      logi("检测到礼物弹幕，等待...");
      sleepMs(GIFT_OVERLAY_WAIT_MS);
    }
    
    if (hasVflipperComponent()) {
      logi("检测到 vflipper 组件，直播间有效");
      return true;
    }
    
    logw("未检测到 vflipper 组件");
    
    // 如果不是最后一次检查，等待后再试
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
  
  var isValid = checkLiveRoomValid(retryCount, checkInterval);
  
  // 直接返回结果
  return isValid;
}

// 执行
main();
