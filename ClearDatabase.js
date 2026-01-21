/**
 * ClearDatabase.js - 清理数据库脚本
 * 
 * 使用方式：
 *   在 aznfz 中运行此脚本，会清空 look_collect 数据库中的所有记录
 */


var DB_NAME = "look_collect";

function logi(msg) {
  console.info("[ClearDB] " + msg);
  try { floatMessage("[ClearDB] " + msg); } catch (e) {}
}

function loge(msg) {
  console.error("[ClearDB] " + msg);
  try { floatMessage("[ClearDB][ERROR] " + msg); } catch (e) {}
}

function logw(msg) {
  console.warn("[ClearDB] " + msg);
  try { floatMessage("[ClearDB][WARN] " + msg); } catch (e) {}
}

function main() {
  logi("开始彻底清理数据库...");
  
  var db = null;
  try {
    db = new Database(DB_NAME);
    logi("数据库已打开: " + DB_NAME);
  } catch (e) {
    loge("打开数据库失败: " + e);
    return;
  }
  
  var deletedCount = 0;
  
  // 查询数据库中的所有表
  try {
    var result = db.query("SELECT name FROM sqlite_master WHERE type='table'");
    
    if (result != null && result.length > 0) {
      logi("发现 " + result.length + " 个表");
      
      var i = 0;
      for (i = 0; i < result.length; i = i + 1) {
        var tableName = "";
        try {
          tableName = "" + result[i].name;
        } catch (e) {
          continue;
        }
        
        // 跳过系统表
        if (tableName == "android_metadata" || tableName == "sqlite_sequence") {
          logi("⏭️  跳过系统表: " + tableName);
          continue;
        }
        
        // 删除表
        try {
          db.exeSql("DROP TABLE IF EXISTS " + tableName);
          logi("✅ 已删除表: " + tableName);
          deletedCount = deletedCount + 1;
        } catch (e) {
          logw("删除表 " + tableName + " 失败: " + e);
        }
      }
    } else {
      logi("数据库中没有表");
    }
  } catch (e) {
    loge("查询表列表失败: " + e);
  }
  
  // 关闭数据库
  try {
    db.close();
    logi("数据库已关闭");
  } catch (e) {
    loge("关闭数据库失败: " + e);
  }
  
  if (deletedCount > 0) {
    logi("🎉 数据库彻底清理完成！已删除 " + deletedCount + " 个表");
  } else {
    logi("ℹ️  没有需要删除的表");
  }
}

// 自动执行
main();
