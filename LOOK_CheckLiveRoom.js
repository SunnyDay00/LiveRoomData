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

function getViews(tag, options) {
  var ret = findRet(tag, options);
  if (ret != null) {
    if (ret.views != null) {
      return ret.views;
    }
  }
  return [];
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
  
  var i = 0;
  for (i = 0; i < retryCount; i = i + 1) {
    logi("第 " + (i + 1) + " 次检查...");
    
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
