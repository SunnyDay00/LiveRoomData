/**
 * AdbSwipe.js - 通用 ADB 滑动脚本 (基于 Shizuku)
 * 
 * 封装了 Shizuku 的连接检查、服务状态确认和权限申请逻辑。
 * 提供稳定的 swipe 功能。
 * 
 * 调用方式:
 * callScript("AdbSwipe", "swipe", startX, startY, endX, endY, duration);
 * 
 * 例如: 
 * callScript("AdbSwipe", "swipe", 540, 1500, 540, 600, 800);
 */

// ==============================
// 配置
// ==============================
var CONFIG = {
  SHIZUKU_PKG: "moe.shizuku.privileged.api", // Shizuku 包名
  RETRY_COUNT: 5,         // 连接重试次数
  RETRY_INTERVAL: 2000,   // 重试间隔(ms)
  PERMISSION_TIMEOUT: 10000 // 权限请求超时
};

function log(msg) {
  console.log("[AdbSwipe] " + msg);
  try { floatMessage("[AdbSwipe] " + msg); } catch(e) {}
}

function sleepMs(ms) {
    sleep(ms);
}

function toInt(val) {
    if (val == null) { return null; }
    if (typeof val === "number") { return val; }
    var s = "" + val;
    if (s == "" || s == "null" || s == "undefined") { return null; }
    var i = 0;
    var sign = 1;
    if (s.charAt(0) == "-") { sign = -1; i = 1; }
    var n = 0;
    var found = false;
    for (; i < s.length; i = i + 1) {
        var c = s.charAt(i);
        if (c < "0" || c > "9") { break; }
        n = n * 10 + (c.charCodeAt(0) - 48);
        found = true;
    }
    if (!found) { return null; }
    return n * sign;
}

function parseSwipeArgs(p1, p2, p3, p4, p5) {
    var sx = toInt(p1);
    var sy = toInt(p2);
    var ex = toInt(p3);
    var ey = toInt(p4);
    var dur = toInt(p5);

    if (sx != null && sy != null && ex != null && ey != null) {
        return {sx: sx, sy: sy, ex: ex, ey: ey, dur: dur};
    }

    if (p1 != null) {
        var s = "" + p1;
        var parts = s.split(",");
        if (parts.length >= 4) {
            sx = toInt(parts[0]);
            sy = toInt(parts[1]);
            ex = toInt(parts[2]);
            ey = toInt(parts[3]);
            dur = (parts.length >= 5) ? toInt(parts[4]) : dur;
            if (sx != null && sy != null && ex != null && ey != null) {
                return {sx: sx, sy: sy, ex: ex, ey: ey, dur: dur};
            }
        }
    }

    return null;
}

// ==============================
// Shizuku 检查逻辑
// ==============================
function checkShizukuInstalled() {
    // 简单检查: 尝试获取包名信息
    try {
        if (getAppVersionName(CONFIG.SHIZUKU_PKG)) {
            return true;
        }
    } catch(e) {}
    
    // 如果 getAppVersionName 不可用，尝试通过 shell pm list packages
    try {
         // 注意：这里用的是普通shell，不一定能列出所有
         // 暂且认为如果初始化能在后面成功就是安装了
    } catch(e) {}
    
    return true; // 默认假设安装了，依靠后续 connect 失败来报错
}

function ensureShizukuReady() {
    log("初始化 Shizuku...");
    
    try {
        shizuku.init();
    } catch(e) {
        log("Shizuku init 异常: " + e);
        return false;
    }

    var i = 0;
    var connected = false;
    
    // 循环检查服务启动状态
    for (i = 0; i < CONFIG.RETRY_COUNT; i++) {
        log("检查连接状态 (" + (i+1) + "/" + CONFIG.RETRY_COUNT + ")...");
        
        if (shizuku.connect()) {
            connected = true;
            break;
        }
        
        // 未连接，可能是服务没启动，提示用户或等待
        log("服务未连接/未启动，等待 " + CONFIG.RETRY_INTERVAL + "ms...");
        sleepMs(CONFIG.RETRY_INTERVAL);
    }
    
    if (!connected) {
        log("错误: Shizuku 服务未启动或连接失败。");
        // 尝试提示用户
        alert("Shizuku 服务未启动！\n\n请打开 'Shizuku' 应用并启动服务。\n(如果是已Root设备直接授权；未Root设备请配合电脑ADB启动)");
        return false;
    }
    
    // 检查权限
    if (!shizuku.checkPermission()) {
        log("权限未获取，正在请求...");
        shizuku.requestPermission(CONFIG.PERMISSION_TIMEOUT);
        
        // 再次检查
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
// 执行滑动
// ==============================
function doSwipe(sx, sy, ex, ey, duration) {
    if (!ensureShizukuReady()) {
        return false;
    }

    if (sx == null || sy == null || ex == null || ey == null) {
      log("参数无效: sx=" + sx + " sy=" + sy + " ex=" + ex + " ey=" + ey);
      return false;
    }
    
    // 默认时长
    if (!duration) {
      duration = 500;
    }
    
    var cmd = "input swipe " + sx + " " + sy + " " + ex + " " + ey + " " + duration;
    log("Exec: " + cmd);
    
    try {
        var ret = shizuku.execCmd(cmd);
        // ret 通常为空字符串表示成功，或者错误信息
        // 视接口实现而定，有的返回 output
        // log("Result: " + ret);
        return true;
    } catch(e) {
        log("Exec Exception: " + e);
        return false;
    }
}

// ==============================
// 主入口
// ==============================
function main(action, p1, p2, p3, p4, p5) {
    // action: method name
    // p1: startX, p2: startY, p3: endX, p4: endY, p5: duration
    
    if (action == "swipe") {
        var args = parseSwipeArgs(p1, p2, p3, p4, p5);
        if (args == null) {
            log("参数解析失败: p1=" + p1 + " p2=" + p2 + " p3=" + p3 + " p4=" + p4 + " p5=" + p5);
            return false;
        }
        return doSwipe(args.sx, args.sy, args.ex, args.ey, args.dur);
    }
    
    // 默认行为或测试
    if (action == "test") {
        return doSwipe(540, 1500, 540, 1000, 500);
    }
    
    return false;
}
