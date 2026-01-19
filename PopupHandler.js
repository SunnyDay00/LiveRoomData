/**
 * PopupHandler.js - 全局弹窗处理脚本
 * 
 * 专门负责识别和处理各类阻断流程的弹窗，如：
 * 1. 青少年模式提醒 ("青少年模式" + "我知道了")
 * 2. 这里的 "跳过" 按钮
 * 
 * 使用方式：
 *   var handled = callScript("LOOK_PopupHandler");
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
  // 必须同时存在标题和按钮，防止误点
  var hasTitle = hasView("txt:青少年模式", {maxStep: 5});
  var hasBtn = hasView("txt:我知道了", {maxStep: 5});

  if (hasTitle && hasBtn) {
    logi("检测到 [青少年模式] 弹窗，尝试点击 '我知道了'...");
    var btn = getFirstView("txt:我知道了", {maxStep: 5});
    if (clickObj(btn, "CLICK_TEEN_CLOSE")) {
      return true;
    }
  }
  return false;
}

// 2. 处理跳过按钮
function handleSkipButton() {
  // 简单粗暴地查找 "跳过" 文本
  // 注意：有些跳过可能是 "跳过 5s" 这种动态文本，这里先只匹配精确的 "跳过"
  // 如果需要匹配包含 "跳过" 的，可以使用 Fuzzy 匹配或者遍历包含跳过的文本
  
  if (hasView("txt:跳过", {maxStep: 3})) {
    logi("检测到 [跳过] 按钮，尝试点击...");
    var btn = getFirstView("txt:跳过", {maxStep: 3});
    if (clickObj(btn, "CLICK_SKIP")) {
      return true;
    }
  }
  return false;
}

// ==============================
// 主流程
// ==============================
function main() {
  var handled = false;
  
  if (handleTeenagerMode()) {
    handled = true;
  }
  
  if (!handled) {
    if (handleSkipButton()) {
      handled = true;
    }
  }
  
  return handled;
}
