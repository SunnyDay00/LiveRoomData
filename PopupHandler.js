/**
 * PopupHandler.js - 全局弹窗处理脚本
 * 
 * 专门负责识别和处理各类阻断流程的弹窗，如：
 * 1. 青少年模式提醒 ("青少年模式" + "我知道了")
 * 2. 这里的 "跳过" 按钮
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

// 1. 处理青少年模式
function handleTeenagerMode() {
  // 先找按钮以避免无弹窗时做两次等待型查找
  var btnRet = findRet("txt:我知道了", {maxStep: 5});
  if (!hasRet(btnRet)) { return false; }

  // 必须同时存在标题和按钮，防止误点
  var titleRet = findRet("txt:青少年模式", {maxStep: 5});
  if (!hasRet(titleRet)) { return false; }

  logi("检测到 [青少年模式] 弹窗，尝试点击 '我知道了'...");
  return clickObj(btnRet.views[0], "CLICK_TEEN_CLOSE");
}

// 2. 处理跳过按钮
function handleSkipButton() {
  // 简单粗暴地查找 "跳过" 文本
  // 注意：有些跳过可能是 "跳过 5s" 这种动态文本，这里先只匹配精确的 "跳过"
  // 如果需要匹配包含 "跳过" 的，可以使用 Fuzzy 匹配或者遍历包含跳过的文本
  
  var btnRet = findRet("txt:跳过", {maxStep: 5});
  if (!hasRet(btnRet)) { return false; }

  logi("检测到 [跳过] 按钮，尝试点击...");
  return clickObj(btnRet.views[0], "CLICK_SKIP");
}

// 3. 处理全屏广告
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
  var handled = false;
  
  // 1. 处理青少年模式
  if (handleTeenagerMode()) {
    handled = true;
  }
  
  // 2. 处理跳过按钮
  if (!handled) {
    if (handleSkipButton()) {
      handled = true;
    }
  }
  
  // 3. 处理全屏广告
  if (!handled) {
    if (handleFullScreenAd()) {
      handled = true;
    }
  }
  
  return handled;
}
