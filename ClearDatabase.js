/**
 * ClearDatabase.js - 清理数据库脚本
 * 
 * 使用方式：
 *   在 aznfz 中运行此脚本，会清空 look_collect 数据库中的所有记录
 */

var DB_NAME = "look_collect";
var TABLE_NAME = "records_v4";

function logi(msg) {
  console.info("[ClearDB] " + msg);
  try { floatMessage("[ClearDB] " + msg); } catch (e) {}
}

function loge(msg) {
  console.error("[ClearDB] " + msg);
  try { floatMessage("[ClearDB][ERROR] " + msg); } catch (e) {}
}

function main() {
  logi("开始清理数据库...");
  
  var db = null;
  try {
    db = new Database(DB_NAME);
    logi("数据库已打开: " + DB_NAME);
  } catch (e) {
    loge("打开数据库失败: " + e);
    return;
  }
  
  // 获取清理前的记录数
  var countBefore = 0;
  try {
    var result = db.query("SELECT COUNT(*) FROM " + TABLE_NAME);
    if (result != null && result.length > 0) {
      countBefore = 0 + result[0]["COUNT(*)"];
    }
    logi("清理前记录数: " + countBefore);
  } catch (e) {
    logi("获取记录数失败: " + e);
  }
  
  // 清空数据表
  try {
    db.exeSql("DELETE FROM " + TABLE_NAME);
    logi("✅ 已清空数据表: " + TABLE_NAME);
  } catch (e) {
    loge("清空数据表失败: " + e);
    try { db.close(); } catch (e2) {}
    return;
  }
  
  // 重置自增ID (可选)
  try {
    db.exeSql("DELETE FROM sqlite_sequence WHERE name='" + TABLE_NAME + "'");
    logi("✅ 已重置自增ID");
  } catch (e) {
    logi("重置自增ID失败: " + e);
  }
  
  // 验证清理结果
  var countAfter = 0;
  try {
    var result2 = db.query("SELECT COUNT(*) FROM " + TABLE_NAME);
    if (result2 != null && result2.length > 0) {
      countAfter = 0 + result2[0]["COUNT(*)"];
    }
    logi("清理后记录数: " + countAfter);
  } catch (e) {
    logi("获取记录数失败: " + e);
  }
  
  // 关闭数据库
  try {
    db.close();
    logi("数据库已关闭");
  } catch (e) {
    loge("关闭数据库失败: " + e);
  }
  
  if (countAfter == 0) {
    logi("🎉 数据库清理完成！已删除 " + countBefore + " 条记录");
  } else {
    loge("⚠️ 清理可能不完整，仍有 " + countAfter + " 条记录");
  }
}

// 自动执行
main();
