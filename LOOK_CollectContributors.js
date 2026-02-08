/**
 * LOOK_CollectContributors.js - 循环采集贡献榜用户信息脚本
 * 
 * 在贡献榜界面，循环点击用户行采集信息并保存。
 * 
 * 使用方式：
 *   var result = callScript("LOOK_CollectContributors.js", {
 *     hostInfo: { id, name, fans, ip },  // 主播信息
 *     clickCount: 5,     // 采集用户数量
 *     clickWaitMs: 1500, // 点击后等待时间
 *     stopAfterRows: 200 // 达到此数量停止
 *   });
 */

// ==============================
// 配置
// ==============================
var ID_USER_ID = "com.netease.play:id/id";
var ID_USER_NAME = "com.netease.play:id/artist_name";
var ID_AVATAR = "com.netease.play:id/avatar";
var ID_USER_MORE = "com.netease.play:id/tv_user_more";
var ID_PROFILE_TVID = "com.netease.play:id/tvID";
var ID_PROFILE_TVID_VALUE = "com.netease.play:id/tvIDValue";
var ID_NUM = "com.netease.play:id/num";

// 应用名称（用于数据库记录）
var APP_NAME = "LOOK直播";

var DEFAULT_CLICK_COUNT = 5;
var DEFAULT_CLICK_WAIT_MS = 1500;
var DEFAULT_STOP_AFTER_ROWS = 200;

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
  console.info("[" + nowStr() + "][CollectContributors][INFO] " + msg);
  try { floatMessage("[Collector] " + msg); } catch (e) {}
}

function logw(msg) { 
  console.warn("[" + nowStr() + "][CollectContributors][WARN] " + msg);
  try { floatMessage("[Collector][WARN] " + msg); } catch (e) {}
}

function loge(msg) { 
  console.error("[" + nowStr() + "][CollectContributors][ERROR] " + msg);
  try { floatMessage("[Collector][ERROR] " + msg); } catch (e) {}
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
// 官方文档: 检查 ret.length > 0
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

// 获取第一个控件的文本
function getTextOfFirst(tag, options) {
  var view = getFirstView(tag, options);
  if (view != null) {
    try {
      if (view.text != null) {
        return "" + view.text;
      }
    } catch (e) {}
  }
  return "";
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

// 清理ID字符串，移除 "ID:" 前缀
function cleanId(str) {
  if (str == null) { return ""; }
  str = "" + str;
  return str.replace("ID：", "").replace("ID:", "").trim();
}

function isEmptyText(str) {
  if (str == null) { return true; }
  if (str == "" || str == "null" || str == "undefined") { return true; }
  return false;
}

// ==============================
// 页面判断
// ==============================
function isDetailPage() {
  var hasId = hasView("id:" + ID_USER_ID, {maxStep: 2});
  var hasName = hasView("id:" + ID_USER_NAME, {maxStep: 2});
  if (hasId && hasName) {
    return true;
  }
  return false;
}

function isUserProfilePage() {
  if (hasView("id:" + ID_USER_MORE, {maxStep: 2})) {
    return true;
  }
  if (hasView("id:" + ID_PROFILE_TVID, {maxStep: 2})) {
    return true;
  }
  return false;
}

function isContributionRankPage() {
  if (hasView("txt:日榜奖励？", {maxStep: 4})) { return true; }
  if (hasView("txt:周榜奖励？", {maxStep: 4})) { return true; }
  if (hasView("txt:榜单说明", {maxStep: 4})) { return true; }
  if (hasView("txt:日榜", {maxStep: 4})) { return true; }
  if (hasView("txt:周榜", {maxStep: 4})) { return true; }
  if (hasView("txt:月榜", {maxStep: 4})) { return true; }
  return false;
}

// 自动检测当前是日榜、周榜还是月榜
function detectRankType() {
  logi("[detectRankType] 开始检测榜单类型...");
  
  // 检测榜单名称文本
  var hasDayText = hasView("txt:日榜", {maxStep: 5});
  var hasWeekText = hasView("txt:周榜", {maxStep: 5});
  var hasMonthText = hasView("txt:月榜", {maxStep: 5});
  
  // 检测特征文本
  var hasDayReward = hasView("txt:日榜奖励？", {maxStep: 5});
  var hasWeekReward = hasView("txt:周榜奖励？", {maxStep: 5});
  var hasRankDesc = hasView("txt:榜单说明", {maxStep: 5});
  
  // 日榜判断: "日榜" + "日榜奖励？"
  if (hasDayText && hasDayReward) {
    logi("[detectRankType] 检测到'日榜'+'日榜奖励？' → 判断为日榜");
    return "day";
  }
  
  // 周榜判断: "周榜" + "周榜奖励？"
  if (hasWeekText && hasWeekReward) {
    logi("[detectRankType] 检测到'周榜'+'周榜奖励？' → 判断为周榜");
    return "week";
  }
  
  // 月榜判断: "月榜" + "榜单说明"
  if (hasMonthText && hasRankDesc) {
    logi("[detectRankType] 检测到'月榜'+'榜单说明' → 判断为月榜");
    return "month";
  }
  
  // 降级方案:仅根据榜单名称判断
  if (hasDayText && !hasWeekText && !hasMonthText) {
    logi("[detectRankType] 只检测到'日榜'(无配对特征) → 判断为日榜");
    return "day";
  }
  if (hasWeekText && !hasDayText && !hasMonthText) {
    logi("[detectRankType] 只检测到'周榜'(无配对特征) → 判断为周榜");
    return "week";
  }
  if (hasMonthText && !hasDayText && !hasWeekText) {
    logi("[detectRankType] 只检测到'月榜'(无配对特征) → 判断为月榜");
    return "month";
  }
  
  // 默认返回日榜(第一次进入默认显示日榜)
  logw("[detectRankType] 无法明确判断,默认为日榜");
  return "day";
}

// ==============================
// 详情页字段提取
// ==============================
function getNumNearLabel(labelText) {
  logi("[getNumNearLabel] 开始查找标签: " + labelText);
  
  // 1. 搜索标签 TextView
  if (!hasView("txt:" + labelText, {maxStep: 3})) {
    logw("[getNumNearLabel] 未找到标签: " + labelText);
    return "";
  }
  
  var labelView = getFirstView("txt:" + labelText, {maxStep: 3});
  if (labelView == null) { 
    logw("[getNumNearLabel] labelView 为 null");
    return ""; 
  }
  logi("[getNumNearLabel] 找到标签 TextView");
  
  // 2. 获取父 ViewGroup
  var parent = getParent(labelView);
  if (parent == null) { 
    logw("[getNumNearLabel] 父控件为 null");
    return ""; 
  }
  logi("[getNumNearLabel] 成功获取父 ViewGroup");
  
  // 3. 遍历父 ViewGroup 中的所有子控件，查找另一个 TextView
  // 根据UI树：父ViewGroup中只有两个TextView，一个是标签，另一个是数值
  var maxTry = 100;
  var i = 0;
  var foundTextViews = [];
  
  for (i = 0; i < maxTry; i = i + 1) {
    var child = null;
    try { child = parent[i]; } catch (e) {}
    if (child == null || child === undefined) { 
      break; 
    }
    
    // 检查是否是 TextView
    var childClass = "";
    try { childClass = "" + child.className; } catch (e) {}
    
    var childText = "";
    try { childText = "" + child.text; } catch (e) {}
    
    var childId = "";
    try { childId = "" + child.id; } catch (e) {}
    
    logi("[getNumNearLabel] 子控件[" + i + "] class=" + childClass + ", text=" + childText + ", id=" + childId);
    
    // 如果是 TextView 且不是标签本身
    if (childClass == "android.widget.TextView") {
      if (child !== labelView && childText != labelText) {
        // 忽略空值
        if (childText != "" && childText != "null" && childText != "undefined") {
          foundTextViews.push({index: i, text: childText, id: childId});
          logi("[getNumNearLabel] 找到候选 TextView: " + childText);
        }
      }
    }
  }
  
  logi("[getNumNearLabel] 遍历完成，共找到 " + foundTextViews.length + " 个候选 TextView");
  
  // 4. 返回找到的数值 TextView
  if (foundTextViews.length == 0) {
    logw("[getNumNearLabel] 未找到任何有效的数值 TextView");
    return "";
  }
  
  // 如果只有一个，直接返回
  if (foundTextViews.length == 1) {
    var result = foundTextViews[0].text;
    logi("[getNumNearLabel] 返回唯一的候选值: " + result);
    return result;
  }
  
  // 如果有多个，优先选择 id 包含 "num" 的
  var j = 0;
  for (j = 0; j < foundTextViews.length; j = j + 1) {
    var tv = foundTextViews[j];
    if (tv.id.indexOf("num") >= 0) {
      logi("[getNumNearLabel] 返回 id 包含 'num' 的值: " + tv.text);
      return tv.text;
    }
  }
  
  // 否则返回第一个
  var firstResult = foundTextViews[0].text;
  logi("[getNumNearLabel] 返回第一个候选值: " + firstResult);
  return firstResult;
}

function doProfileSwipeUp() {
  var ok = false;
  try {
    logi("[readUserIdFromProfile] 调用AdbSwipe上滑...");
    var ret = callScript("AdbSwipe", "swipe", 540, 1700, 540, 400, 800);
    if (ret != false) {
      ok = true;
    }
    sleepMs(800);
  } catch (e) {
    loge("[readUserIdFromProfile] AdbSwipe exception=" + e);
    ok = false;
  }
  return ok;
}

function readUserIdFromProfile(clickWaitMs) {
  logi("[readUserIdFromProfile] 尝试进入用户主页获取ueseid...");
  if (clickWaitMs == null) {
    clickWaitMs = DEFAULT_CLICK_WAIT_MS;
  }

  var avatarView = getFirstView("id:" + ID_AVATAR, {maxStep: 2});
  if (avatarView == null) {
    logw("[readUserIdFromProfile] avatar未找到");
    return "";
  }

  clickObj(avatarView, "CLICK_AVATAR_TO_PROFILE");
  sleepMs(clickWaitMs);

  if (!isUserProfilePage()) {
    if (!isDetailPage()) {
      logw("[readUserIdFromProfile] 未检测到用户主页，尝试返回");
      backAndWait("BACK_AFTER_PROFILE_FAIL", clickWaitMs);
    } else {
      logw("[readUserIdFromProfile] 未检测到用户主页，仍在详情页");
    }
    return "";
  }

  var profileId = cleanId(getTextOfFirst("id:" + ID_PROFILE_TVID, {maxStep: 2}));
  if (isEmptyText(profileId)) {
    logw("[readUserIdFromProfile] tvID未找到，尝试读取tvIDValue");
    profileId = cleanId(getTextOfFirst("id:" + ID_PROFILE_TVID_VALUE, {maxStep: 2}));
  }
  if (isEmptyText(profileId)) {
    logw("[readUserIdFromProfile] tvID仍未找到，尝试上滑后再次查找");
    doProfileSwipeUp();
    profileId = cleanId(getTextOfFirst("id:" + ID_PROFILE_TVID, {maxStep: 2}));
    if (isEmptyText(profileId)) {
      logw("[readUserIdFromProfile] 上滑后tvID仍为空，尝试读取tvIDValue");
      profileId = cleanId(getTextOfFirst("id:" + ID_PROFILE_TVID_VALUE, {maxStep: 2}));
    }
  }

  if (isEmptyText(profileId)) {
    logw("[readUserIdFromProfile] 仍未获取到ueseid");
  } else {
    logi("[readUserIdFromProfile] ueseid=" + profileId);
  }

  backAndWait("BACK_TO_USER_DETAIL_FROM_PROFILE", clickWaitMs);
  return profileId;
}

function readUserDetail(clickWaitMs) {
  logi("开始读取用户详情...");
  var obj = { ueseid: "", uesename: "", ueseip: "", SummaryConsumption: "" };

  var rawId = getTextOfFirst("id:" + ID_USER_ID, {maxStep: 2});
  obj.ueseid = cleanId(rawId);
  if (isEmptyText(obj.ueseid)) {
    logw("ueseid为空，尝试从用户主页获取");
    var profileId = readUserIdFromProfile(clickWaitMs);
    if (!isEmptyText(profileId)) {
      obj.ueseid = profileId;
    }
  }
  logi("ueseid=" + obj.ueseid);
  
  obj.uesename = getTextOfFirst("id:" + ID_USER_NAME, {maxStep: 2});
  logi("uesename=" + obj.uesename);
  
  obj.ueseip = getNumNearLabel("IP属地");
  logi("ueseip=" + obj.ueseip);
  
  obj.SummaryConsumption = getNumNearLabel("消费音符");
  logi("SummaryConsumption=" + obj.SummaryConsumption);
  
  logi("读取用户详情完成");

  return obj;
}

// ==============================
// 月榜用户行处理
// ==============================
function findRankTextView(rankStr) {
  var ret = findRet("txt:" + rankStr, {flag: "find_all", maxStep: 3});
  if (ret == null) { return null; }
  if (ret.length <= 0) { return null; }

  var count = ret.length;
  var i = 0;
  for (i = 0; i < count; i = i + 1) {
    try {
      var v = ret.views[i];
      if (v == null) { continue; }
      var txt = "" + v.text;
      if (txt != rankStr) { continue; }
      var cls = "";
      try { cls = "" + v.className; } catch (e) {}
      if (cls == "android.widget.TextView") {
        return v;
      }
    } catch (e) {}
  }
  return null;
}

function isViewGroupClassName(cls) {
  if (cls == "android.view.ViewGroup") { return true; }
  if (cls.indexOf("ViewGroup") >= 0) { return true; }
  if (cls.indexOf("Layout") >= 0) { return true; }
  return false;
}

function findClickableRowFromTextView(textView) {
  if (textView == null) { return null; }
  var p = getParent(textView);
  var depth = 0;
  while (p != null && depth < 10) {
    var cls = "";
    try { cls = "" + p.className; } catch (e) {}
    if (isClickable(p) && isViewGroupClassName(cls)) {
      return p;
    }
    p = getParent(p);
    depth = depth + 1;
  }
  return null;
}

function getBottomRightText(rootObj) {
  var bestText = "";
  var bestBottom = -1;
  var bestLeft = -1;

  // 使用 findRet 并正确访问
  var ret = findRet("className:android.widget.TextView", {root: rootObj, flag: "find_all", maxStep: 3});
  if (ret == null) { return ""; }
  if (ret.length <= 0) { return ""; }
  
  var count = ret.length;
  var i = 0;
  for (i = 0; i < count; i = i + 1) {
    try {
      var v = ret.views[i];
      if (v == null) { continue; }
      var txt = "" + v.text;

      var left = -1;
      var bottom = -1;

      if (v.left != null) { left = v.left; }
      if (v.bottom != null) {
        bottom = v.bottom;
      } else {
        if (v.top != null) {
          if (v.height != null) {
            bottom = v.top + v.height;
          }
        }
      }

      if (bottom > bestBottom) {
        bestBottom = bottom;
        bestLeft = left;
        bestText = txt;
      } else {
        if (bottom == bestBottom) {
          if (left > bestLeft) {
            bestLeft = left;
            bestText = txt;
          }
        }
      }
    } catch (e) {}
  }
  return bestText;
}

// 辅助函数：获取子控件数量
// 辅助函数：获取子控件数量
// 辅助函数：获取子控件数量
function getChildCount(v) {
  if (v == null) { return 0; }
  var maxTry = 200;
  var i = 0;
  for (i = 0; i < maxTry; i = i + 1) {
    var child = null;
    try { child = v[i]; } catch (e) {}
    if (child == null || child === undefined) { return i; }
  }
  return maxTry;
}

// 辅助函数：获取指定索引的子控件
function getChildAt(v, i) {
  var child = null;
  try { child = v[i]; } catch (e) {}
  return child;
}

// 辅助函数：获取组件的文本和左坐标，如果无效返回 null
// 辅助函数：获取组件的文本和左坐标，如果无效返回 null
function getViewTextInfo(v) {
  try {
    var txt = "";
    try { txt = "" + v.text; } catch (e) {}
    
    // 忽略空文本
    if (txt == null || txt == "" || txt == "null" || txt == "undefined") { return null; }
    
    var left = -1;
    if (v.left != null) { left = v.left; }
    else {
        try {
           var b = v.bounds();
           if (b != null) { left = b.left; }
        } catch(e) {}
    }
    
    if (left == -1) { return null; }
    
    return { text: txt, left: left };
  } catch (e) {
    return null;
  }
}

function getRightMostText(viewGroup) {
  if (viewGroup == null) { loge("getRightMostText: viewGroup is NULL"); return ""; }

  var bestText = "";
  var bestLeft = -1;

  // 1. 遍历一级子控件
  var level1Count = getChildCount(viewGroup);
  
  var i = 0;
  for (i = 0; i < level1Count; i = i + 1) {
    var child1 = getChildAt(viewGroup, i);
    if (child1 == null) { continue; }
    
    // 2. 遍历二级子控件 (假设结构是 Row -> Group -> TextViews)
    var level2Count = getChildCount(child1);
    
    // 如果一级子控件没有子控件，它自己可能是个TextView
    if (level2Count <= 0) {
      var info1 = getViewTextInfo(child1);
      if (info1 != null && info1.left > bestLeft) {
        bestLeft = info1.left;
        bestText = info1.text;
      }
      continue;
    }
    
    var j = 0;
    for (j = 0; j < level2Count; j = j + 1) {
      var child2 = getChildAt(child1, j);
      if (child2 == null) { continue; }
      
      var info2 = getViewTextInfo(child2);
      if (info2 != null && info2.left > bestLeft) {
        bestLeft = info2.left;
        bestText = info2.text;
      }
      
      // 3. 遍历三级子控件 (以防万一还有一层)
      var level3Count = getChildCount(child2);
      if (level3Count > 0) {
          var k = 0;
          for (k = 0; k < level3Count; k = k + 1) {
              var child3 = getChildAt(child2, k);
              if (child3 != null) {
                  var info3 = getViewTextInfo(child3);
                  if (info3 != null && info3.left > bestLeft) {
                      bestLeft = info3.left;
                      bestText = info3.text;
                  }
              }
          }
      }
    }
  }

  return bestText;
}

function pickContributionFrameFromRet(ret) {
  if (ret == null) { return null; }
  if (ret.length <= 0) { return null; }
  
  var count = ret.length;
  var i = 0;
  for (i = 0; i < count; i = i + 1) {
    try {
      var frame = ret.views[i];
      if (frame == null) { continue; }
      
      // 检查这个 frame 下是否有 txt:1（表示排行榜）
      if (hasView("txt:1", {root: frame, maxStep: 2})) {
        return frame;
      }
    } catch (e) {}
  }
  return null;
}

function findContributionFrame() {
  var tags = [
    "className:androidx.recyclerview.widget.RecyclerView",
    "className:android.support.v7.widget.RecyclerView",
    "className:android.widget.ListView",
    "className:android.widget.FrameLayout"
  ];
  var i = 0;
  for (i = 0; i < tags.length; i = i + 1) {
    var ret = findRet(tags[i], {flag: "find_all", maxStep: 3});
    var frame = pickContributionFrameFromRet(ret);
    if (frame != null) { return frame; }
  }
  return null;
}

function getClickableUserRows(frameObj) {
  var rows = [];
  
  if (frameObj == null) {
    var ret = findRet("className:android.view.ViewGroup", {flag: "find_all", maxStep: 2});
    if (ret == null) { return rows; }
    if (ret.length <= 0) { return rows; }

    var countFallback = ret.length;
    var iFallback = 0;
    for (iFallback = 0; iFallback < countFallback; iFallback = iFallback + 1) {
      try {
        var vFallback = ret.views[iFallback];
        if (vFallback == null) { continue; }
        var clsFallback = "";
        try { clsFallback = "" + vFallback.className; } catch (e) {}
        if (clsFallback == "android.view.ViewGroup" && isClickable(vFallback)) {
          rows.push(vFallback);
        }
      } catch (e) {}
    }
    return rows;
  }
  
  var listGroup = getFirstView("className:android.view.ViewGroup", {root: frameObj, maxStep: 1});
  if (listGroup == null) { return rows; }
  
  var count = 0;
  try {
    if (listGroup.size != null) { count = listGroup.size; }
    else if (listGroup.length != null) { count = listGroup.length; }
  } catch (e) {}
  if (count <= 0) {
    try { count = listGroup.size(); } catch (e) {}
  }
  
  var i = 0;
  for (i = 0; i < count; i = i + 1) {
    try {
      var row = null;
      try { row = listGroup[i]; } catch (e) { row = null; }
      if (row == null) { continue; }
      
      var cls = "";
      try { cls = "" + row.className; } catch (e) {}
      if (cls == "android.view.ViewGroup" && isClickable(row)) {
        rows.push(row);
      }
    } catch (e) {}
  }
  return rows;
}

// ==============================
// 业务逻辑
// ==============================
function collectContributors(hostInfo, rankType, clickCount, clickWaitMs, stopAfterRows) {
  logi("========== 开始采集贡献榜用户（传入 rankType=" + rankType + "）==========");
  
  // 自动检测当前榜单类型（覆盖传入的 rankType）
  var detectedRankType = detectRankType();
  if (detectedRankType != rankType) {
    logw("检测到榜单类型不匹配！传入=" + rankType + ", 实际=" + detectedRankType);
    logw("将使用检测到的榜单类型: " + detectedRankType);
    rankType = detectedRankType;
  } else {
    logi("榜单类型匹配: " + rankType);
  }
  logi("最终使用榜单类型: " + rankType);

  var wrote = 0;
  var totalInserted = 0;

  // 获取当前已插入数量
  try {
    // callScript("DataHandler", "getCount")
    totalInserted = callScript("DataHandler", "getCount");
  } catch (e) {
    loge("getCount error: " + e);
  }

  var rank = 1;
  for (rank = 1; rank <= clickCount; rank = rank + 1) {
    if (totalInserted >= stopAfterRows) { 
      logw("达到数据库写入上限 " + stopAfterRows); 
      return {
        success: true,
        rankType: rankType,
        wrote: wrote,
        totalInserted: totalInserted,
        stoppedByLimit: true
      };
    }

    var rankStr = "" + rank;
    var rankView = findRankTextView(rankStr);
    if (rankView == null) {
      logw("rank=" + rankStr + " TextView not found");
      continue;
    }

    var row = findClickableRowFromTextView(rankView);
    if (row == null) {
      logw("rank=" + rankStr + " row not found");
      continue;
    }

    var dayuesenumber = "";
    var weekuesenumber = "";
    var monthuesenumber = "";
    
    // 根据榜单类型设置排名
    if (rankType == "day") {
      dayuesenumber = rankStr;
    } else if (rankType == "week") {
      weekuesenumber = rankStr;
    } else {
      monthuesenumber = rankStr;
    }
    
    // [新增] 每次处理新用户前，检查是否被弹窗阻挡（已移除 PopupHandler 调用）
    
    var Consumption = getRightMostText(row);
    if (Consumption == "null" || Consumption == "undefined" || Consumption == "") {
      Consumption = "0";
    }
    logi("点击用户行 rank=" + rankStr + ", rankType=" + rankType + ", Consumption=" + Consumption);

    clickObj(row, "CLICK_USER_ROW_RANK_" + rankStr);
    sleepMs(clickWaitMs);

    if (!isDetailPage()) {
      logw("未进入用户详情，可能是被弹窗遮挡");
      if (isContributionRankPage()) {
        logi("当前仍在贡献榜界面，跳过 back 防止误退");
      } else if (isUserProfilePage()) {
        logw("当前在用户主页，执行返回到贡献榜");
        backAndWait("BACK_PROFILE_TO_RANK", clickWaitMs);
      } else {
        logw("当前页面未知，执行一次保护性返回");
        backAndWait("USER_DETAIL_BACK_FAILSAFE", clickWaitMs);
      }
    } else {
      var userInfo = readUserDetail(clickWaitMs);
      
      var cleanHostId = cleanId(hostInfo.id);

        // 调用数据处理脚本保存数据 (使用 named object 方式，避免位置错乱)
        var rowData = {
            app_name: APP_NAME,
            homeid: cleanHostId,
            homename: hostInfo.name,
            fansnumber: hostInfo.fans,
            homeip: hostInfo.ip,
            dayuesenumber: dayuesenumber,
            monthuesenumber: monthuesenumber,
            weekuesenumber: weekuesenumber,
            ueseid: userInfo.ueseid,
            uesename: userInfo.uesename,
            consumption: Consumption,
            ueseip: userInfo.ueseip,
            summaryconsumption: userInfo.SummaryConsumption
        };

        try {
            // 传递对象：action="insert", row=rowData
            var insertResult = callScript("DataHandler", { action: "insert", row: rowData });
            if (typeof insertResult === "number" && insertResult > 0) {
              totalInserted = totalInserted + insertResult;
            } else {
              totalInserted = totalInserted + 1;
            }

            wrote = wrote + 1;
            logi("数据保存成功，已采集 " + wrote + "/" + clickCount + ", 总计=" + totalInserted);
        } catch (e) {
            loge("callScript DataHandler error: " + e);
        }

      backAndWait("BACK_TO_MONTH", clickWaitMs);
    }
  }

  logi("采集完成，共采集 " + wrote + " 个用户");
  return {
    success: true,
    rankType: rankType,
    wrote: wrote,
    totalInserted: totalInserted,
    stoppedByLimit: false
  };
}

// ==============================
// 主入口 - 通过函数参数接收数据
// callScript("LOOK_CollectContributors", hostId, hostName, hostFans, hostIp, rankType, clickCount, clickWaitMs, stopAfterRows)
// rankType: 'day' = 日榜, 'month' = 月榜
// ==============================
function main(hostId, hostName, hostFans, hostIp, rankType, clickCount, clickWaitMs, stopAfterRows) {
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
  
  // 调用采集逻辑
  return collectContributors(hostInfo, rankType, clickCount, clickWaitMs, stopAfterRows);
}

// 注意：不要在文件末尾调用 main()
// 通过 callScript("LOOK_CollectContributors", ...) 调用时，引擎会自动执行 main()
