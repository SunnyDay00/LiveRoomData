var CLOUD_API_URL = "https://liveroomdata.sssr.edu.kg/upload"; 

function uploadToCloud(data) {
  if (CLOUD_API_URL == null) {
     alert("URL Config Error");
     return;
  }

  if (typeof rsContext === "undefined") {
      var msg = "No rsContext";
      console.error(msg);
      alert(msg);
      return;
  }
  
  console.log("Starting cloud upload...");

  try {
    var loader = rsContext.getClass().getClassLoader();
    var OkHttpClientClass = loader.loadClass("okhttp3.OkHttpClient");
    var RequestBuilderClass = loader.loadClass("okhttp3.Request$Builder");
    var FormBodyBuilderClass = loader.loadClass("okhttp3.FormBody$Builder");
    
    var formBuilder = FormBodyBuilderClass.newInstance();
    var val = "";
    
    val = data.app_name; if (val == null) { val = ""; } formBuilder.add("app_name", val + "");
    val = data.homeid; if (val == null) { val = ""; } formBuilder.add("homeid", val + "");
    val = data.homename; if (val == null) { val = ""; } formBuilder.add("homename", val + "");
    val = data.fansnumber; if (val == null) { val = ""; } formBuilder.add("fansnumber", val + "");
    val = data.homeip; if (val == null) { val = ""; } formBuilder.add("homeip", val + "");
    val = data.dayuesenumber; if (val == null) { val = ""; } formBuilder.add("dayuesenumber", val + "");
    val = data.monthuesenumber; if (val == null) { val = ""; } formBuilder.add("monthuesenumber", val + "");
    val = data.ueseid; if (val == null) { val = ""; } formBuilder.add("ueseid", val + "");
    val = data.uesename; if (val == null) { val = ""; } formBuilder.add("uesename", val + "");
    val = data.consumption; if (val == null) { val = ""; } formBuilder.add("consumption", val + "");
    val = data.ueseip; if (val == null) { val = ""; } formBuilder.add("ueseip", val + "");
    val = data.summaryconsumption; if (val == null) { val = ""; } formBuilder.add("summaryconsumption", val + "");
    val = data.record_time; if (val == null) { val = ""; } formBuilder.add("record_time", val + "");
    
    var requestBody = formBuilder.build();
    var reqBuilder = RequestBuilderClass.newInstance();
    reqBuilder.url(CLOUD_API_URL);
    reqBuilder.post(requestBody);
    var request = reqBuilder.build();
    
    // 4. Execute with Retry
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
            try { code = response.code(); } catch(e) { code = -1; }
            
            if (code == 200) {
                console.log("Upload Success");
                success = true;
                break; // 成功退出循环
            } else {
                var errorBody = "";
                try { errorBody = response.body().string(); } catch(e) {}
                lastError = "HTTP " + code + ": " + errorBody;
                console.error("Upload Fail: " + lastError);
            }
            
        } catch (ex) {
            var cause = ex;
            try { if (ex.getCause() != null) { cause = ex.getCause(); } } catch(e2) {}
            lastError = "Network Error: " + cause;
            console.error("Retry " + (r+1) + " Exception: " + lastError);
        }
        
        // 等待后重试 (2秒)
        if (r < 2) {
             try { sleep(2000); } catch(e) {}
        }
    }
    
    if (!success) {
        alert("上传失败(重试3次):\n" + lastError);
        return;
    }
    
  } catch (e) {
    var exMsg = "Upload Error: " + e;
    try { if (e.getCause() != null) { exMsg = exMsg + " CAUSE: " + e.getCause(); } } catch(ex2) {}
    console.error(exMsg);
    alert("脚本运行错误: " + exMsg);
    return;
  }
}

function dbInsertRow(appName, homeid, homename, fansnumber, homeip, dayuesenumber, monthuesenumber, ueseid, uesename, consumption, ueseip, summaryConsumption) {
  
  // Inline getNowDateStr logic
  var utcTime = new Date().getTime();
  var beijingOffset = 28800000;
  var recordTime = "" + (utcTime + beijingOffset);

  console.log("Preparing upload...");
  
  var rowData = {
      app_name: appName,
      homeid: homeid,
      homename: homename,
      fansnumber: fansnumber,
      homeip: homeip,
      dayuesenumber: dayuesenumber,
      monthuesenumber: monthuesenumber,
      ueseid: ueseid,
      uesename: uesename,
      consumption: consumption,
      ueseip: ueseip,
      summaryconsumption: summaryConsumption,
      record_time: recordTime
  };
  
  uploadToCloud(rowData);
  return 1;
}

function main(action, param1, param2, param3, param4, param5, param6, param7, param8, param9, param10, param11, param12) {
  var row = null;
  if (action != null && typeof action === "object") {
    var opts = action;
    action = opts.action;
    if (opts.row != null) { row = opts.row; }
  }

  if (row != null) {
    if (row.app_name != null) { param1 = row.app_name; }
    if (row.homeid != null) { param2 = row.homeid; }
    if (row.homename != null) { param3 = row.homename; }
    if (row.fansnumber != null) { param4 = row.fansnumber; }
    if (row.homeip != null) { param5 = row.homeip; }
    if (row.dayuesenumber != null) { param6 = row.dayuesenumber; }
    if (row.monthuesenumber != null) { param7 = row.monthuesenumber; }
    if (row.ueseid != null) { param8 = row.ueseid; }
    if (row.uesename != null) { param9 = row.uesename; }
    if (row.consumption != null) { param10 = row.consumption; }
    if (row.ueseip != null) { param11 = row.ueseip; }
    if (row.summaryconsumption != null) { param12 = row.summaryconsumption; }
  }

  if (action == "insert") {
    return dbInsertRow(param1, param2, param3, param4, param5, param6, param7, param8, param9, param10, param11, param12);
  }
  return 0;
}
