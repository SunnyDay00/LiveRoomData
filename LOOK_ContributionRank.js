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

// 获取所有找到的views（注意：返回的是Java List对象，可能不支持length属性）
function getAllViews(tag, options) {
  var ret = findRet(tag, options);
  if (ret != null) {
    if (ret.length > 0) {
      return ret.views;
    }
  }
  return null;
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

// 获取集合大小
function getCollectionSize(col) {
  if (col == null) { return 0; }
  // 1. 尝试 .length
  try {
    if (col.length != null) { return col.length; }
  } catch (e) {}
  // 2. 尝试 .size() (Java List)
  try {
    return col.size();
  } catch (e) {}
  // 3. 尝试 .size 属性
  try {
    if (col.size != null) { return col.size; }
  } catch (e) {}
  
  return 0;
}

// 获取集合元素
function getCollectionItem(col, index) {
  if (col == null) { return null; }
  // 1. 尝试 [index]
  try {
    var item = col[index];
    if (item != null) { return item; }
  } catch (e) {}
  // 2. 尝试 .get(index) (Java List)
  try {
    return col.get(index);
  } catch (e) {}
  
  return null;
}

// ==============================
// 礼物弹幕检测
// ==============================
function hasGiftOverlay() {
  if (hasView("id:com.netease.play:id/liveNoticeRootContainer", {maxStep: 3})) { return true; }
  if (hasView("id:com.netease.play:id/imageBg", {maxStep: 3})) { return true; }
  if (hasView("id:com.netease.play:id/noticeContent", {maxStep: 3})) { return true; }
  if (hasView("id:com.netease.play:id/liveNoticeContainer", {maxStep: 3})) { return true; }
  if (hasView("id:com.netease.play:id/liveNotice", {maxStep: 3})) { return true; }
  if (hasView("id:com.netease.play:id/liveIcon", {maxStep: 3})) { return true; }
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
  
  // 直接查找 vflipper（不使用 root 选项）
  if (!hasView("id:" + ID_VFLIPPER, {maxStep: 3})) {
    loge("未找到 vflipper 组件");
    return false;
  }
  var vfView = getFirstView("id:" + ID_VFLIPPER, {maxStep: 3});
  logi("找到 vflipper 组件");
  
  if (vfView == null) {
    loge("vflipper 组件对象为空");
    return false;
  }
  
  // 根据官方文档，使用 view[i] 遍历子控件，view.size 或 view.length 获取子控件数量
  var childCount = 0;
  try {
    // 尝试获取子控件数量
    if (vfView.size != null) {
      childCount = vfView.size;
    } else if (vfView.length != null) {
      childCount = vfView.length;
    }
  } catch (e) {
    loge("获取子控件数量失败: " + e);
  }
  logi("vflipper 子控件数量=" + childCount);
  
  var clicked = false;
  var i = 0;
  
  // 遍历 vflipper 的所有子控件
  for (i = 0; i < childCount; i = i + 1) {
    try {
      var child = vfView[i];
      if (child != null) {
        // 检查是否是 TextView 且可点击
        var cn = "";
        try { cn = child.className; } catch (e) {}
        logi("子控件[" + i + "] className=" + cn + ", clickable=" + child.clickable);
        
        if (cn == "android.widget.TextView") {
          if (child.clickable == true) {
            logi("找到可点击的TextView子控件，尝试点击...");
            clicked = clickObj(child, "CHARM_ENTRY_TV");
            if (clicked) {
              sleepMs(clickWaitMs);
              break;
            }
          }
        }
      }
    } catch (e) {
      loge("遍历子控件[" + i + "]失败: " + e);
    }
  }
  
  // 如果没找到可点击的 TextView，尝试点击第一个子控件
  if (!clicked && childCount > 0) {
    try {
      var firstChild = vfView[0];
      if (firstChild != null) {
        logi("没有可点击的TextView，尝试点击第一个子控件...");
        clicked = clickObj(firstChild, "CHARM_ENTRY_FIRST_CHILD");
        if (clicked) {
          sleepMs(clickWaitMs);
        }
      }
    } catch (e) {
      loge("点击第一个子控件失败: " + e);
    }
  }

  if (!clicked) {
    loge("未能找到任何可点击的魅力榜入口");
    return false;
  }

  // 验证是否进入魅力榜
  var has1 = hasView("txt:魅力榜", {maxStep: 2});
  var has2 = hasView("txt:当前房间", {maxStep: 2});
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
  
  // 首先检查是否已经在贡献榜页面（日榜奖励和日榜都存在）
  var alreadyOnPage = hasView("txt:日榜奖励", {maxStep: 2}) && hasView("txt:日榜", {maxStep: 2});
  if (alreadyOnPage) {
    logi("已经在贡献榜页面，无需点击");
    return true;
  }
  
  var clicked = false;
  
  // 方法1: 搜索 android.view.View 并匹配 text="贡献榜" (这是可点击的容器)
  logi("方法1: 搜索 android.view.View...");
  var ret = findRet("className:android.view.View", {flag: "find_all", maxStep: 3});
  if (ret != null && ret.length > 0) {
    var count = ret.length;
    var i = 0;
    for (i = 0; i < count; i = i + 1) {
      try {
        var v = ret.views[i];
        if (v != null) {
          var vText = "" + v.text;
          if (vText == "贡献榜") {
            logi("找到 View 类型贡献榜按钮，clickable=" + v.clickable);
            clickObj(v, "CLICK_CONTRIB_VIEW");
            sleepMs(clickWaitMs);
            clicked = true;
            break;
          }
        }
      } catch (e) {}
    }
  }
  
  // 方法2: 如果方法1失败，使用 txt:贡献榜 搜索，然后获取父控件点击
  if (!clicked) {
    logi("方法2: 使用 txt:贡献榜 并获取父控件...");
    if (hasView("txt:贡献榜", {maxStep: 3})) {
      var tvView = getFirstView("txt:贡献榜", {maxStep: 3});
      if (tvView != null) {
        // 尝试点击父控件（可能是可点击的 View）
        var parentView = getParent(tvView);
        if (parentView != null) {
          logi("找到贡献榜文本，点击其父控件");
          clickObj(parentView, "CLICK_CONTRIB_PARENT");
          sleepMs(clickWaitMs);
          clicked = true;
        } else {
          // 如果没有父控件，直接点击文本
          logi("找到贡献榜文本，直接点击");
          clickObj(tvView, "CLICK_CONTRIB_TV");
          sleepMs(clickWaitMs);
          clicked = true;
        }
      }
    }
  }

  // 验证是否成功进入贡献榜
  var hasA = hasView("txt:日榜奖励", {maxStep: 2});
  var hasB = hasView("txt:日榜", {maxStep: 2});
  logi("贡献榜验证: 日榜奖励=" + hasA + ", 日榜=" + hasB);
  
  if (hasA && hasB) {
    logi("成功进入贡献榜界面");
    return true;
  }
  
  // 即使没找到按钮，如果点击成功也返回 true
  if (clicked) {
    logi("点击成功但验证未通过，仍继续执行");
    return true;
  }

  loge("进入贡献榜失败");
  return false;
}

// ==============================
// 切换到月榜
// ==============================
function switchToMonthRank(clickWaitMs) {
  logi("尝试切换到月榜...");
  
  if (!hasView("txt:日榜", {maxStep: 2})) { 
    loge("未找到日榜入口"); 
    return false; 
  }
  var dayView = getFirstView("txt:日榜", {maxStep: 2});
  if (dayView == null) {
    loge("日榜视图为空");
    return false;
  }

  var p = getParent(dayView);
  if (p != null) { 
    clickObj(p, "OPEN_RANK_OPTIONS"); 
  } else { 
    clickObj(dayView, "OPEN_RANK_OPTIONS_TEXT"); 
  }
  sleepMs(clickWaitMs);

  if (!hasView("txt:月榜", {maxStep: 2})) { 
    loge("未找到月榜选项"); 
    return false; 
  }
  var monthView = getFirstView("txt:月榜", {maxStep: 2});
  if (monthView == null) {
    loge("月榜视图为空");
    return false;
  }

  var pm = getParent(monthView);
  if (pm != null) { 
    clickObj(pm, "SELECT_MONTH"); 
  } else { 
    clickObj(monthView, "SELECT_MONTH_TEXT"); 
  }
  sleepMs(clickWaitMs);

  // 验证：只要点击成功，或者页面有"月榜"和"榜单说明"就认为成功
  var hasM = hasView("txt:月榜", {maxStep: 2});
  var hasR = hasView("txt:榜单说明", {maxStep: 2});
  logi("月榜验证: 月榜=" + hasM + ", 榜单说明=" + hasR);
  
  if (hasM && hasR) {
    logi("成功切换到月榜");
    return true;
  }
  
  // 即使验证没通过，如果点击都成功了也返回 true
  logi("月榜验证未通过，但点击成功，继续执行");
  return true;
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

// 注意：不要在文件末尾调用 main()
// 通过 callScript("LOOK_ContributionRank", ...) 调用时，引擎会自动执行 main()
