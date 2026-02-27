/**
 * AdbClick.js - 通用 ADB 点击脚本（基于 Shizuku）
 *
 * 使用方式:
 *   callScript("AdbClick", "click", x, y);
 */

// ==============================
// 配置
// ==============================
var CONFIG = {
  SHIZUKU_PKG: "moe.shizuku.privileged.api",
  RETRY_COUNT: 5,
  RETRY_INTERVAL: 2000,
  PERMISSION_TIMEOUT: 10000
};

function log(msg) {
  console.log("[AdbClick] " + msg);
  try { floatMessage("[AdbClick] " + msg); } catch (e) {}
}

function sleepMs(ms) { sleep(ms); }

// ==============================
// Shizuku 初始化
// ==============================
function checkShizukuInstalled() {
  try {
    if (getAppVersionName(CONFIG.SHIZUKU_PKG)) {
      return true;
    }
  } catch (e) {}
  return true;
}

function ensureShizukuReady() {
  log("初始化 Shizuku...");
  try {
    shizuku.init();
  } catch (e) {
    log("Shizuku init 异常: " + e);
    return false;
  }

  var i = 0;
  var connected = false;
  for (i = 0; i < CONFIG.RETRY_COUNT; i = i + 1) {
    log("检查连接状态 (" + (i + 1) + "/" + CONFIG.RETRY_COUNT + ")...");
    if (shizuku.connect()) {
      connected = true;
      break;
    }
    log("服务未连接/未启动，等待 " + CONFIG.RETRY_INTERVAL + "ms...");
    sleepMs(CONFIG.RETRY_INTERVAL);
  }

  if (!connected) {
    log("错误: Shizuku 服务未启动或连接失败。");
    alert("Shizuku 服务未启动！\n\n请打开 Shizuku 应用并启动服务。");
    return false;
  }

  if (!shizuku.checkPermission()) {
    log("权限未获取，正在请求...");
    shizuku.requestPermission(CONFIG.PERMISSION_TIMEOUT);
    if (!shizuku.checkPermission()) {
      log("错误: 未获得 Shizuku 授权。");
      alert("请在弹窗中允许 Shizuku 授权！");
      return false;
    }
  }

  log("Shizuku 就绪。");
  return true;
}

// ==============================
// 执行点击
// ==============================
function doClick(x, y) {
  if (!ensureShizukuReady()) {
    return false;
  }
  if (x == null || y == null) {
    log("坐标无效");
    return false;
  }

  var cmd = "input tap " + x + " " + y;
  log("执行: " + cmd);
  try {
    shizuku.execCmd(cmd);
    return true;
  } catch (e) {
    log("执行异常: " + e);
    return false;
  }
}

// ==============================
// 主入口
// ==============================
function main(action, p1, p2) {
  if (action == "click") {
    return doClick(p1, p2);
  }
  if (action == "test") {
    return doClick(540, 960);
  }
  return false;
}
