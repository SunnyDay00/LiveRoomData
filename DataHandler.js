// Worker URL (deployed successfully)
var CLOUD_API_URL = "https://neon.sssr.edu.kg/upload"; 
var API_KEY = "lrm_7Kx9mP2vN5qR8wT4yU3zB6aC1dE"; // API key for authentication
var g_dbName = "";
var COUNT_DIR = "/storage/emulated/0/LiveRoomData/runtime";
var COUNT_FILE_PATH = "/storage/emulated/0/LiveRoomData/runtime/datahandler_count.txt";

function ensureDir(path) {
  try {
    var dir = new FileX(path);
    if (!dir.exists()) {
      dir.makeDirs();
    }
  } catch (e) {
  }
}

function parsePositiveInt(text) {
  if (text == null) { return 0; }
  var t = ("" + text).trim();
  if (t == "") { return 0; }
  var i = 0;
  var n = 0;
  for (i = 0; i < t.length; i = i + 1) {
    var c = t.charAt(i);
    if (c < "0" || c > "9") { return 0; }
    n = n * 10 + (c.charCodeAt(0) - 48);
  }
  return n;
}

function readCountFromFile() {
  try {
    ensureDir(COUNT_DIR);
    var f = new FileX(COUNT_FILE_PATH);
    if (f == null || !f.exists()) {
      return 0;
    }
    var s = f.read();
    return parsePositiveInt(s);
  } catch (e) {
  }
  return 0;
}

function writeCountToFile(n) {
  try {
    ensureDir(COUNT_DIR);
    var f2 = new FileX(COUNT_FILE_PATH);
    f2.write("" + n);
    return true;
  } catch (e) {
  }
  return false;
}

function uploadToCloud(data) {
  if (CLOUD_API_URL == null) {
     alert("URL Config Error");
     return false;
  }

  if (typeof rsContext === "undefined") {
      var msg = "No rsContext";
      console.error(msg);
      alert(msg);
      return false;
  }
  
  console.log("Starting cloud upload...");

  try {
    var loader = rsContext.getClass().getClassLoader();
    var OkHttpClientClass = loader.loadClass("okhttp3.OkHttpClient");
    var RequestBuilderClass = loader.loadClass("okhttp3.Request$Builder");
    var FormBodyBuilderClass = loader.loadClass("okhttp3.FormBody$Builder");
    
    // Build Form Body (Classic approach, fully supported)
    var formBuilder = FormBodyBuilderClass.newInstance();
    
    // Add fields safely
    var val = "";
    
    val = data.app_name; formBuilder.add("app_name", val + "");
    val = data.homeid; formBuilder.add("homeid", val + "");
    val = data.homename; formBuilder.add("homename", val + "");
    val = data.fansnumber; formBuilder.add("fansnumber", val + "");
    val = data.homeip; formBuilder.add("homeip", val + "");
    val = data.dayuesenumber; formBuilder.add("dayuesenumber", val + "");
    val = data.weekuesenumber; formBuilder.add("weekuesenumber", val + "");
    val = data.monthuesenumber; formBuilder.add("monthuesenumber", val + "");
    val = data.ueseid; formBuilder.add("ueseid", val + "");
    val = data.uesename; formBuilder.add("uesename", val + "");
    val = data.consumption; formBuilder.add("consumption", val + "");
    val = data.summaryconsumption; formBuilder.add("summaryconsumption", val + "");
    val = data.ueseip; formBuilder.add("ueseip", val + "");

    
    var requestBody = formBuilder.build();
    var reqBuilder = RequestBuilderClass.newInstance();
    reqBuilder.url(CLOUD_API_URL);
    reqBuilder.post(requestBody);
    
    // Add API Key Header
    reqBuilder.addHeader("X-API-Key", API_KEY);
    
    var request = reqBuilder.build();
    
    // Execute with Retry
    var client = OkHttpClientClass.newInstance();
    var success = false;
    var lastError = "";
    
    // 重试循环 3 次
    for (var r = 0; r < 3; r = r + 1) {
        try {
            console.log("Upload attempt " + (r + 1) + "...");
            var call = client.newCall(request);
            var response = call.execute();
            
            if (response == null) {
                lastError = "Response is null";
                continue;
            }
            
            var code = 0;
            try {
                code = response.code();
            } catch(e) {
                code = -1;
            }
            
            if (code == 200) {
                console.log("Upload Success");
                success = true;
                break; // 成功退出循环
            } else {
                var errorBody = "";
                try {
                    errorBody = response.body().string();
                } catch(e) {
                }
                lastError = "HTTP " + code + ": " + errorBody;
                console.error("Upload Fail: " + lastError);
            }
            
        } catch (ex) {
            var cause = ex;
            try {
                if (ex.getCause() != null) {
                    cause = ex.getCause();
                }
            } catch(e2) {
            }
            lastError = "Network Error: " + cause;
            console.error("Retry " + (r+1) + " Exception: " + lastError);
        }
        
        // 等待后重试 (2秒)
        if (r < 2) {
             try {
                 sleep(2000);
             } catch(e) {
             }
        }
    }
    
    if (!success) {
        alert("上传失败(重试3次):\\n" + lastError);
        return false;
    }
    
  } catch (e) {
    var exMsg = "Upload Error: " + e;
    try {
        if (e.getCause() != null) {
            exMsg = exMsg + " CAUSE: " + e.getCause();
        }
    } catch(ex2) {
    }
    console.error(exMsg);
    alert("脚本运行错误: " + exMsg);
    return false;
  }
  
  return true;
}

function dbInsertRow(appName, homeid, homename, fansnumber, homeip, dayuesenumber, weekuesenumber, monthuesenumber, ueseid, uesename, consumption, ueseip, summaryConsumption) {
  
  console.log("Preparing upload...");
  
  var rowData = {
      app_name: appName,
      homeid: homeid,
      homename: homename,
      fansnumber: fansnumber,
      homeip: homeip,
      dayuesenumber: dayuesenumber,
      weekuesenumber: weekuesenumber,
      monthuesenumber: monthuesenumber,
      ueseid: ueseid,
      uesename: uesename,
      consumption: consumption,
      ueseip: ueseip,
      summaryconsumption: summaryConsumption
  };
  
  var ok = uploadToCloud(rowData);
  if (ok) {
    var cnt = readCountFromFile();
    cnt = cnt + 1;
    writeCountToFile(cnt);
    return 1;
  }
  return 0;
}

function main(action, param1, param2, param3, param4, param5, param6, param7, param8, param9, param10, param11, param12, param13) {
  
  // 1. Try to get row object from action
  var row = null;
  if (action != null && typeof action === "object") {
    var opts = action;
    action = opts.action;
    if (opts.row != null) {
        row = opts.row;
    }
    if (opts.dbName != null) {
        param1 = opts.dbName;
    }
  }

  if (action == "init") {
    g_dbName = (param1 == null ? "" : (param1 + ""));
    console.log("DataHandler init: " + g_dbName);
    return 1;
  }

  if (action == "getCount") {
    return readCountFromFile();
  }

  if (action == "close") {
    console.log("DataHandler close: " + g_dbName);
    return readCountFromFile();
  }

  // 2. Build rowData based on available input
  var rowData = {};

  if (row != null) {
      // MODE A: Object-based Call (Preferred)
      // Map correctly using property names
      rowData.app_name = row.app_name;
      rowData.homeid = row.homeid;
      rowData.homename = row.homename;
      rowData.fansnumber = row.fansnumber;
      rowData.homeip = row.homeip;
      rowData.dayuesenumber = row.dayuesenumber;
      rowData.weekuesenumber = row.weekuesenumber;
      rowData.monthuesenumber = row.monthuesenumber;
      rowData.ueseid = row.ueseid;
      rowData.uesename = row.uesename;
      rowData.consumption = row.consumption;
      rowData.ueseip = row.ueseip;
      rowData.summaryconsumption = row.summaryconsumption;
      
  } else {
      // MODE B: Legacy Positional Call (row is null)
      // The caller passes arguments positionally, but the order is SCRAMBLED compared to our new schema.
      // Based on user feedback and debugging:
      // p1..p6 = Standard
      // p7 = Month (Week skipped)
      // p8 = Name (Swapped with ID)
      // p9 = ID   (Swapped with Name)
      // p10 = IP/Location (Swapped with Consumption)
      // p11 = Consumption (Swapped with IP)
      // p12 = Summary
      
      rowData.app_name = param1;
      rowData.homeid = param2;
      rowData.homename = param3;
      rowData.fansnumber = param4;
      rowData.homeip = param5;
      rowData.dayuesenumber = param6;
      rowData.weekuesenumber = ""; // Legacy caller does not send week
      rowData.monthuesenumber = param7; // p7 is Month
      rowData.ueseid = param9;          // p9 is ID (Correcting swap)
      rowData.uesename = param8;        // p8 is Name (Correcting swap)
      rowData.consumption = param11;    // p11 is Consumption (Correcting swap)
      rowData.ueseip = param10;         // p10 is IP (Correcting swap)
      rowData.summaryconsumption = param12;
  }

  // 3. Prevent nulls (Common cleanup)
  if(rowData.app_name == null) { rowData.app_name = ""; }
  if(rowData.homeid == null) { rowData.homeid = ""; }
  if(rowData.homename == null) { rowData.homename = ""; }
  if(rowData.fansnumber == null) { rowData.fansnumber = ""; }
  if(rowData.homeip == null) { rowData.homeip = ""; }
  if(rowData.dayuesenumber == null) { rowData.dayuesenumber = ""; }
  if(rowData.weekuesenumber == null) { rowData.weekuesenumber = ""; }
  if(rowData.monthuesenumber == null) { rowData.monthuesenumber = ""; }
  if(rowData.ueseid == null) { rowData.ueseid = ""; }
  if(rowData.uesename == null) { rowData.uesename = ""; }
  if(rowData.consumption == null) { rowData.consumption = ""; }
  if(rowData.ueseip == null) { rowData.ueseip = ""; }
  if(rowData.summaryconsumption == null) { rowData.summaryconsumption = ""; }

  if (action == "insert") {
    var ok2 = uploadToCloud(rowData);
    if (ok2) {
      var cnt2 = readCountFromFile();
      cnt2 = cnt2 + 1;
      writeCountToFile(cnt2);
      return 1;
    }
    return 0;
  }
  return 0;
}
