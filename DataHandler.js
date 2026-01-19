/**
 * DataHandler.js - 通用数据处理脚本
 * 
 * 用于写入本地数据库，可被多个软件的脚本调用。
 * 
 * 使用方式：
 *   callScript("DataHandler.js", {
 *     action: "init",      // init / insert / close / getCount
 *     dbName: "look_collect",
 *     row: { ... }         // insert时需要
 *   });
 */

// ==============================
// 全局变量
// ==============================
var g_db = null;
var g_insertCount = 0;

// ==============================
// 工具函数
// ==============================
function nowStr() { 
  return "" + (new Date().getTime()); 
}

function logi(msg) { 
  console.info("[" + nowStr() + "][DataHandler][INFO] " + msg);
  try { floatMessage("[DataHandler] " + msg); } catch (e) {}
}

function loge(msg) { 
  console.error("[" + nowStr() + "][DataHandler][ERROR] " + msg);
  try { floatMessage("[DataHandler][ERROR] " + msg); } catch (e) {}
}

function sqlEsc(s) {
  if (s == null) { return ""; }
  s = "" + s;
  // 单引号转义
  return s.split("'").join("''");
}

// ==============================
// 数据库操作
// ==============================
function dbInit(dbName) {
  try {
    g_db = new Database(dbName);
    logi("open db " + dbName);

    if (!g_db.isTableExist("records")) {
      var sql =
        "create table records(" +
        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT," +
        "ts VARCHAR(32) NULL," +
        "app_name VARCHAR(64) NULL," +
        "homeid VARCHAR(64) NULL," +
        "homename VARCHAR(128) NULL," +
        "fansnumber VARCHAR(64) NULL," +
        "homeip VARCHAR(64) NULL," +
        "uesenumber VARCHAR(64) NULL," +
        "ueseid VARCHAR(64) NULL," +
        "uesename VARCHAR(128) NULL," +
        "consumption VARCHAR(64) NULL," +
        "ueseip VARCHAR(64) NULL" +
        ")";
      g_db.exeSql(sql);
      logi("table created");
    } else {
      logi("table exists");
    }
    return true;
  } catch (e) {
    loge("init exception=" + e);
    return false;
  }
}

function dbInsertRow(row) {
  if (g_db == null) {
    loge("db null");
    return false;
  }

  try {
    var sql =
      "insert into records(ts,app_name,homeid,homename,fansnumber,homeip,uesenumber,ueseid,uesename,consumption,ueseip) values(" +
      "'" + sqlEsc(nowStr()) + "'," +
      "'" + sqlEsc(row.app_name) + "'," +
      "'" + sqlEsc(row.homeid) + "'," +
      "'" + sqlEsc(row.homename) + "'," +
      "'" + sqlEsc(row.fansnumber) + "'," +
      "'" + sqlEsc(row.homeip) + "'," +
      "'" + sqlEsc(row.uesenumber) + "'," +
      "'" + sqlEsc(row.ueseid) + "'," +
      "'" + sqlEsc(row.uesename) + "'," +
      "'" + sqlEsc(row.consumption) + "'," +
      "'" + sqlEsc(row.ueseip) + "'" +
      ")";
    var b = g_db.exeSql(sql);
    g_insertCount = g_insertCount + 1;
    logi("insert ok=" + b + " count=" + g_insertCount);
    return true;
  } catch (e) {
    loge("insert exception=" + e);
    return false;
  }
}

function dbClose() {
  try {
    if (g_db != null) {
      g_db.close();
      g_db = null;
    }
    logi("close ok");
  } catch (e) {
    loge("close exception=" + e);
  }
}

function getInsertCount() {
  return g_insertCount;
}

// ==============================
// 主入口 - 通过函数参数接收数据
// callScript("DataHandler", action, param1, param2, ...)
// action: "init" -> param1=dbName
// action: "insert" -> param1=app_name, param2-param10 = 数据字段
// action: "close" -> 无参数
// action: "getCount" -> 无参数
// ==============================
function main(action, param1, param2, param3, param4, param5, param6, param7, param8, param9, param10) {
  logi("action=" + action);
  
  if (action == "init") {
    var dbName = (param1 != null) ? param1 : "data_collect";
    var ok = dbInit(dbName);
    return ok;
  } 
  else if (action == "insert") {
    // param1=app_name, param2-param10: homeid, homename, fansnumber, homeip, uesenumber, ueseid, uesename, consumption, ueseip
    var row = {
      app_name: param1,
      homeid: param2,
      homename: param3,
      fansnumber: param4,
      homeip: param5,
      uesenumber: param6,
      ueseid: param7,
      uesename: param8,
      consumption: param9,
      ueseip: param10
    };
    var ok = dbInsertRow(row);
    return g_insertCount;
  } 
  else if (action == "close") {
    dbClose();
    return true;
  } 
  else if (action == "getCount") {
    return g_insertCount;
  } 
  else {
    loge("unknown action: " + action);
    return -1;
  }
}

// 注意：不要在文件末尾调用 main()
// 通过 callScript("DataHandler", ...) 调用时，引擎会自动执行 main()
