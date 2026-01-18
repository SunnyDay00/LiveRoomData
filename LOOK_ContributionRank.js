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
var ID_RANKTEXT = "com.netease.play:id/rankText";

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

function getViews(tag, options) {
  var ret = findRet(tag, options);
  if (ret != null) {
    if (ret.views != null) {
      return ret.views;
    }
  }
  return [];
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
// 进入魅力榜
// ==============================
function enterCharmRankFromLive(clickWaitMs) {
  logi("开始查找魅力榜入口...");
  
  var header = getViews("id:" + ID_HEADER, {maxStep: 2});
  if (header.length <= 0) { 
    loge("未找到header容器"); 
    return false; 
  }
  logi("找到header容器");

  // 方式1：通过 vflipper 查找
  var vf = getViews("id:" + ID_VFLIPPER, {root: header[0], maxStep: 2});
  logi("vflipper查找结果: 数量=" + vf.length);

  // 方式2：通过 rankText 查找
  var rt = getViews("id:" + ID_RANKTEXT, {root: header[0], maxStep: 2});
  logi("rankText查找结果: 数量=" + rt.length);

  var clicked = false;
  var i = 0;
  
  // 优先尝试 rankText
  if (rt.length > 0) {
    logi("尝试通过rankText入口...");
    if (isClickable(rt[0])) {
      clicked = clickObj(rt[0], "CHARM_ENTRY_RANKTEXT");
    } else {
      var p = getParent(rt[0]);
      if (p != null) {
        if (isClickable(p)) {
          clicked = clickObj(p, "CHARM_ENTRY_RANKTEXT_PARENT");
        }
      }
    }
    if (clicked) {
      sleepMs(clickWaitMs);
    }
  }
  
  if (!clicked) {
    if (vf.length > 0) {
      logi("尝试通过vflipper入口...");
      var tvs = getViews("className:android.widget.TextView", {root: vf[0], flag: "find_all", maxStep: 3});
      for (i = 0; i < tvs.length; i = i + 1) {
        if (isClickable(tvs[i])) {
          clickObj(tvs[i], "CHARM_ENTRY_TEXTVIEW");
          sleepMs(clickWaitMs);
          clicked = true;
          break;
        }
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
