/**
 * DataHandler.js - 通用数据处理脚本 (Atomic Persistence Version)
 *
 * 使用方式：
 *   callScript("DataHandler", "init", "look_collect");
 *   callScript("DataHandler", "insert", ...);
 *   callScript("DataHandler", "getCount");
 *   callScript("DataHandler", "dump");
 *   callScript("DataHandler", "close");
 *
 * 也支持对象参数：
 *   callScript("DataHandler", { action: "insert", dbName: "look_collect", row: { ... } });
 */

// ==============================
// 配置
// ==============================
var DB_NAME = "look_collect";
var TABLE_NAME = "records_v3";
var g_db = null;

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
  return s.split("'").join("''");
}

// ==============================
// 数据库操作 (Atomic: Open -> Action -> Close)
// ==============================
function dbOpen() {
  try {
    g_db = new Database(DB_NAME);
  } catch (e) {
    loge("open db error: " + e);
    return -1;
  }

  try {
    if (!g_db.isTableExist(TABLE_NAME)) {
      var createSql = ""
        + "CREATE TABLE IF NOT EXISTS " + TABLE_NAME + " ("
        + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
        + "app_name VARCHAR, "
        + "homeid VARCHAR, "
        + "homename VARCHAR, "
        + "fansnumber VARCHAR, "
        + "homeip VARCHAR, "
        + "uesenumber VARCHAR, "
        + "ueseid VARCHAR, "
        + "uesename VARCHAR, "
        + "consumption VARCHAR, "
        + "ueseip VARCHAR, "
        + "summary_consumption VARCHAR"
        + ");";
      g_db.exeSql(createSql);
      logi("create table " + TABLE_NAME);
    }
  } catch (e) {
    loge("dbOpen init error: " + e);
    return -1;
  }

  return 0;
}

function dbClose() {
  try {
    if (g_db != null) {
      g_db.close();
    }
  } catch (e) {
    loge("close db error: " + e);
  }
  g_db = null;
  return 0;
}

function dbQuery(sql) {
  try {
    return g_db.query(sql);
  } catch (e) {
    loge("query error: " + e);
    return null;
  }
}

function getInsertCount() {
  if (g_db == null) { return -1; }
  var count = 0;
  try {
    var result = dbQuery("SELECT COUNT(*) FROM " + TABLE_NAME);
    if (result != null) {
      if (result.length > 0) {
        var row = result[0];
        try {
          count = 0 + row["COUNT(*)"];
          if (count != count) { count = 0; }
        } catch (e) {
          count = 0;
        }
      }
    }
  } catch (e) {
    loge("getCount error: " + e);
  }
  return count;
}

function dbInsertRow(appName, homeid, homename, fansnumber, homeip,
  uesenumber, ueseid, uesename, consumption, ueseip, summaryConsumption) {
  if (g_db == null) { return -1; }
  var sql = ""
    + "INSERT INTO " + TABLE_NAME + " ("
    + "app_name, homeid, homename, fansnumber, homeip, "
    + "uesenumber, ueseid, uesename, consumption, ueseip, summary_consumption"
    + ") VALUES ("
    + "'" + sqlEsc(appName) + "', "
    + "'" + sqlEsc(homeid) + "', "
    + "'" + sqlEsc(homename) + "', "
    + "'" + sqlEsc(fansnumber) + "', "
    + "'" + sqlEsc(homeip) + "', "
    + "'" + sqlEsc(uesenumber) + "', "
    + "'" + sqlEsc(ueseid) + "', "
    + "'" + sqlEsc(uesename) + "', "
    + "'" + sqlEsc(consumption) + "', "
    + "'" + sqlEsc(ueseip) + "', "
    + "'" + sqlEsc(summaryConsumption) + "'"
    + ");";

  logi("Insert: homeid=" + homeid + ", ueseid=" + ueseid + ", consumption=" + consumption + ", summary=" + summaryConsumption);

  try {
    g_db.exeSql(sql);
  } catch (e) {
    loge("insert error: " + e);
    return -1;
  }

  return getInsertCount();
}

function dbDump() {
  if (g_db == null) { return -1; }
  var result = dbQuery("SELECT * FROM " + TABLE_NAME);
  if (result == null) { return -1; }

  var count = 0;
  try {
    if (result.length == 0) {
      return 0;
    }
    
    var i = 0;
    for (i = 0; i < result.length; i = i + 1) {
      var row = result[i];
      var output = "记录" + (i + 1) + ": ";
      var first = true;
      
      // 遍历行的所有字段
      if (row.id != null) { output = output + "id=" + row.id; first = false; }
      if (row.app_name != null) { if (!first) { output = output + ", "; } output = output + "app_name=" + row.app_name; first = false; }
      if (row.homeid != null) { if (!first) { output = output + ", "; } output = output + "homeid=" + row.homeid; first = false; }
      if (row.homename != null) { if (!first) { output = output + ", "; } output = output + "homename=" + row.homename; first = false; }
      if (row.fansnumber != null) { if (!first) { output = output + ", "; } output = output + "fansnumber=" + row.fansnumber; first = false; }
      if (row.homeip != null) { if (!first) { output = output + ", "; } output = output + "homeip=" + row.homeip; first = false; }
      if (row.uesenumber != null) { if (!first) { output = output + ", "; } output = output + "uesenumber=" + row.uesenumber; first = false; }
      if (row.ueseid != null) { if (!first) { output = output + ", "; } output = output + "ueseid=" + row.ueseid; first = false; }
      if (row.uesename != null) { if (!first) { output = output + ", "; } output = output + "uesename=" + row.uesename; first = false; }
      if (row.consumption != null) { if (!first) { output = output + ", "; } output = output + "consumption=" + row.consumption; first = false; }
      if (row.ueseip != null) { if (!first) { output = output + ", "; } output = output + "ueseip=" + row.ueseip; first = false; }
      if (row.summary_consumption != null) { if (!first) { output = output + ", "; } output = output + "summary_consumption=" + row.summary_consumption; first = false; }
      
      console.info(output);
      count = count + 1;
    }
  } catch (e) {
    loge("dump error: " + e);
  }

  return count;
}


// ==============================
// 主入口
// ==============================
function main(action, param1, param2, param3, param4, param5,
  param6, param7, param8, param9, param10, param11) {
  var row = null;
  var dbName = null;

  if (action != null && typeof action === "object") {
    var opts = action;
    action = opts.action;
    if (opts.dbName != null) { dbName = "" + opts.dbName; }
    if (opts.row != null) { row = opts.row; }
  }

  if (dbName != null && dbName !== "") {
    DB_NAME = dbName.replace(/\.db$/i, "");
  }
  if (action === "init") {
    if (dbName == null) {
      if (param1 != null) {
        DB_NAME = ("" + param1).replace(/\.db$/i, "");
      }
    } else if (dbName === "") {
      if (param1 != null) {
        DB_NAME = ("" + param1).replace(/\.db$/i, "");
      }
    }
  }

  if (action == null) {
    loge("missing action");
    return -1;
  }
  action = "" + action;

  if (row != null) {
    if (row.app_name != null) { param1 = row.app_name; }
    if (row.homeid != null) { param2 = row.homeid; }
    if (row.homename != null) { param3 = row.homename; }
    if (row.fansnumber != null) { param4 = row.fansnumber; }
    if (row.homeip != null) { param5 = row.homeip; }
    if (row.uesenumber != null) { param6 = row.uesenumber; }
    if (row.ueseid != null) { param7 = row.ueseid; }
    if (row.uesename != null) { param8 = row.uesename; }
    if (row.consumption != null) { param9 = row.consumption; }
    if (row.ueseip != null) { param10 = row.ueseip; }
    if (row.summary_consumption != null) { param11 = row.summary_consumption; }
  }

  if (action == "init") {
    if (dbOpen() != 0) { return -1; }
    dbClose();
    return 0;
  } else if (action == "insert") {
    if (dbOpen() != 0) { return -1; }
    var result = dbInsertRow(param1, param2, param3, param4, param5,
      param6, param7, param8, param9, param10, param11);
    dbClose();
    return result;
  } else if (action == "getCount") {
    if (dbOpen() != 0) { return -1; }
    var count = getInsertCount();
    dbClose();
    return count;
  } else if (action == "dump") {
    if (dbOpen() != 0) { return -1; }
    var rows = dbDump();
    dbClose();
    return rows;
  } else if (action == "close") {
    return dbClose();
  } else {
    loge("unknown action: " + action);
    return -1;
  }
}

// 注意：不要在文件末尾调用 main()
// 通过 callScript("DataHandler", ...) 调用时，引擎会自动执行 main()
