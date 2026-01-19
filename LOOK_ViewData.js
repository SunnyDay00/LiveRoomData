/**
 * LOOK_ViewData.js - 查看采集数据
 * 
 * 运行此脚本，将在日志中输出数据库中的所有记录。
 */

function main() {
  console.info("========== 开始读取数据库数据 ==========");
  try {
    var count = callScript("DataHandler", "dump");
    console.info("查询完成，共 " + count + " 条记录");
  } catch (e) {
    console.error("查看数据失败: " + e);
  }
  console.info("========== 读取结束 ==========");
}