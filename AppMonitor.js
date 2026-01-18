/**
 * AppMonitor.js - 通用软件崩溃监控脚本
 * 
 * 定期检查软件状态，崩溃时重启并重新运行主脚本。
 * 
 * 使用方式（在新线程中启动）：
 *   var t = new Thread();
 *   t.start(function() {
 *     callScript("AppMonitor.js", {
 *       appPkg: "com.netease.play",
 *       appName: "LOOK直播",
 *       mainScript: "LOOK_Main.js",
 *       checkInterval: 10000,  // 检查间隔（毫秒），可选，默认10秒
 *       homeCheckFunc: "isHomePage"  // 首页检测函数名，可选
 *     });
 *   });
 */

// ==============================
// 工具函数
// ==============================
function nowStr() { 
  return "" + (new Date().getTime()); 
}

function logi(msg) { 
  console.info("[" + nowStr() + "][AppMonitor][INFO] " + msg);
  try { floatMessage("[Monitor] " + msg); } catch (e) {}
}

function logw(msg) { 
  console.warn("[" + nowStr() + "][AppMonitor][WARN] " + msg);
  try { floatMessage("[Monitor][WARN] " + msg); } catch (e) {}
}

function loge(msg) { 
  console.error("[" + nowStr() + "][AppMonitor][ERROR] " + msg);
  try { floatMessage("[Monitor][ERROR] " + msg); } catch (e) {}
}

function sleepMs(ms) { 
  sleep(ms); 
}

// ==============================
// App操作
// ==============================
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

function isAppInForeground(appPkg) {
  // currentPackage() 函数在此引擎中不可用
  // 简化逻辑：始终假设App可能不在前台，让监控逻辑处理
  // 这样监控会定期尝试切换到前台，如果已经在前台不会有副作用
  return false;
}

function bringAppToForeground(appPkg) {
  try {
    var ret = launchApp(appPkg, "");
    return (ret == 1);
  } catch (e) {
    loge("bringAppToForeground exception=" + e);
    return false;
  }
}

// ==============================
// 主监控逻辑
// ==============================
function monitorApp(appPkg, appName, mainScript, checkInterval) {
  logi("开始监控: " + appName + " (" + appPkg + ")");
  logi("检查间隔: " + checkInterval + "ms");
  logi("重启后运行: " + mainScript);

  while (true) {
    sleepMs(checkInterval);
    
    logi("检查App状态...");
    
    // 检查App是否在前台
    if (!isAppInForeground(appPkg)) {
      logw("App不在前台，尝试切换到前台...");
      
      var brought = bringAppToForeground(appPkg);
      if (!brought) {
        // 软件可能已崩溃，尝试重启
        logw("无法切换到前台，软件可能已崩溃，正在重启...");
        
        safeKillBackgroundApp(appPkg);
        sleepMs(1000);
        
        var ret = safeLaunchApp(appPkg);
        if (ret == 0) {
          loge(appName + " 未安装，停止监控");
          break;
        }
        
        sleepMs(5000);  // 等待App启动
        
        // 重新运行主脚本
        logi("重新运行主脚本: " + mainScript);
        try {
          callScript(mainScript);
        } catch (e) {
          loge("callScript exception=" + e);
        }
      } else {
        // 成功切换到前台，等待一下再检查首页状态
        sleepMs(2000);
      }
    } else {
      logi("App在前台运行正常");
    }
  }
}

// ==============================
// 主入口 - 接收位置参数
// callScript("AppMonitor", appPkg, appName, mainScript, checkInterval)
// ==============================
function main(appPkg, appName, mainScript, checkInterval) {
  // 设置默认值
  if (checkInterval == null) {
    checkInterval = 10000;
  }

  if (!appPkg) {
    loge("missing appPkg");
    return;
  }
  if (!appName) {
    loge("missing appName");
    return;
  }
  if (!mainScript) {
    loge("missing mainScript");
    return;
  }

  monitorApp(appPkg, appName, mainScript, checkInterval);
}

main();
