/**
 * TMP_RestartAppTest.js - 临时测试：关闭/重启应用
 *
 * 运行方式：
 *   callScript("TMP_RestartAppTest");
 */

var CONFIG = {
  APP_PKG: "com.netease.play",
  APP_NAME: "LOOK直播",
  CLOSE_BACK_TIMES: 3, // 关闭前按返回次数
  CLOSE_WAIT_MS: 800,  // 关闭步骤间隔
  RESTART_WAIT_MS: 3000, // 重启后等待
  USE_SHIZUKU: 1, // 1=优先用Shizuku强制关闭/启动
  SHIZUKU_PKG: "moe.shizuku.privileged.api",
  RETRY_COUNT: 5,
  RETRY_INTERVAL: 2000,
  PERMISSION_TIMEOUT: 10000
};

function nowStr() { 
  var utcTime = new Date().getTime();
  var beijingOffset = 8 * 60 * 60 * 1000;
  return "" + (utcTime + beijingOffset);
}

function logi(msg) { 
  console.info("[" + nowStr() + "][TmpRestart][INFO] " + msg);
}

function logw(msg) { 
  console.warn("[" + nowStr() + "][TmpRestart][WARN] " + msg);
}

function loge(msg) { 
  console.error("[" + nowStr() + "][TmpRestart][ERROR] " + msg);
}

function sleepMs(ms) { 
  sleep(ms); 
}

function safeLaunchApp(appPkg) {
  var ret = -1;
  try {
    ret = launchApp(appPkg, "");
  } catch (e) {
    loge("launchApp exception=" + e);
    ret = -1;
  }
  return ret;
}

function safeKillBackgroundApp(appPkg) {
  try {
    killBackgroundApp(appPkg);
    return true;
  } catch (e) {
    return false;
  }
}

function checkShizukuInstalled() {
  try {
    if (getAppVersionName(CONFIG.SHIZUKU_PKG)) {
      return true;
    }
  } catch (e) {}
  return true;
}

function ensureShizukuReady() {
  if (CONFIG.USE_SHIZUKU != 1) {
    return false;
  }
  if (!checkShizukuInstalled()) {
    logw("Shizuku 未安装");
    return false;
  }
  try {
    shizuku.init();
  } catch (e) {
    logw("Shizuku init 异常: " + e);
    return false;
  }

  var i = 0;
  var connected = false;
  for (i = 0; i < CONFIG.RETRY_COUNT; i = i + 1) {
    if (shizuku.connect()) {
      connected = true;
      break;
    }
    sleepMs(CONFIG.RETRY_INTERVAL);
  }

  if (!connected) {
    logw("Shizuku 服务未启动或连接失败");
    return false;
  }

  if (!shizuku.checkPermission()) {
    shizuku.requestPermission(CONFIG.PERMISSION_TIMEOUT);
    if (!shizuku.checkPermission()) {
      logw("未获得 Shizuku 授权");
      return false;
    }
  }
  return true;
}

function execShizuku(cmd) {
  try {
    shizuku.execCmd(cmd);
    return true;
  } catch (e) {
    logw("execCmd 异常: " + e);
    return false;
  }
}

function forceStopApp(appPkg) {
  if (ensureShizukuReady()) {
    var cmd = "am force-stop " + appPkg;
    logi("[FORCE_STOP] cmd=" + cmd);
    return execShizuku(cmd);
  }
  logw("[FORCE_STOP] Shizuku 不可用，回退 killBackgroundApp");
  return safeKillBackgroundApp(appPkg);
}

function startApp(appPkg) {
  if (ensureShizukuReady()) {
    var cmd = "monkey -p " + appPkg + " -c android.intent.category.LAUNCHER 1";
    logi("[START_APP] cmd=" + cmd);
    return execShizuku(cmd);
  }
  logw("[START_APP] Shizuku 不可用，回退 launchApp");
  var ret = safeLaunchApp(appPkg);
  return (ret == 1);
}

function main() {
  setLogLevel(5);
  logi("=== 关闭/重启测试开始 ===");

  logi("[STEP_1] 启动应用");
  var okStart = startApp(CONFIG.APP_PKG);
  logi("[STEP_1] startApp ok=" + okStart);
  sleepMs(CONFIG.RESTART_WAIT_MS);

  logi("[STEP_2] 尝试返回到桌面/退出前台");
  var i = 0;
  for (i = 0; i < CONFIG.CLOSE_BACK_TIMES; i = i + 1) {
    try { back(); logi("[STEP_2] back() ok " + (i + 1) + "/" + CONFIG.CLOSE_BACK_TIMES); }
    catch (e1) { logw("[STEP_2] back() exception=" + e1); }
    sleepMs(CONFIG.CLOSE_WAIT_MS);
  }

  logi("[STEP_3] force-stop 应用");
  var killed = forceStopApp(CONFIG.APP_PKG);
  logi("[STEP_3] forceStop ok=" + killed);
  sleepMs(CONFIG.CLOSE_WAIT_MS);

  logi("[STEP_4] 重新启动应用");
  okStart = startApp(CONFIG.APP_PKG);
  logi("[STEP_4] startApp ok=" + okStart);
  sleepMs(CONFIG.RESTART_WAIT_MS);

  logi("=== 关闭/重启测试结束 ===");
}

main();
