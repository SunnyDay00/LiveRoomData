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

// ==============================
// 检查逻辑
// ==============================
function hasVflipperComponent() {
  return hasView("id:" + ID_VFLIPPER, {maxStep: 3});
}

function checkLiveRoomValid(retryCount, checkInterval) {
  logi("开始检查直播间有效性，重试次数=" + retryCount + "，间隔=" + checkInterval + "ms");
  
  // 如果检测到礼物弹幕，才等待
  if (hasGiftOverlay()) {
    logi("检测到礼物弹幕，等待 " + GIFT_OVERLAY_WAIT_MS + "ms...");
    waitForGiftOverlayToDisappear();
  } else {
    logi("未检测到礼物弹幕，直接检查...");
  }
  
  var i = 0;
  for (i = 0; i < retryCount; i = i + 1) {
    logi("第 " + (i + 1) + " 次检查...");
    
    // 每次检查前都检测礼物弹幕
    if (hasGiftOverlay()) {
      logi("检查中途检测到礼物弹幕，等待...");
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

// 注意：不要在文件末尾调用 main()
// 通过 callScript("LOOK_CheckLiveRoom", retryCount, checkInterval) 调用时，
// 引擎会自动执行 main() 函数并传入参数
