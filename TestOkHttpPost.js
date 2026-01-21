
function logi(msg) {
    console.log("[OkPost] " + msg);
}

function loge(msg) {
    console.error("[OkPost] " + msg);
}

function main() {
    logi("Starting OkHttp POST Test...");
    
    if (typeof rsContext === "undefined") {
        loge("rsContext missing");
        return;
    }

    try {
        var loader = rsContext.getClass().getClassLoader();
        
        // 1. Load Classes
        var OkHttpClientClass = loader.loadClass("okhttp3.OkHttpClient");
        var RequestBuilderClass = loader.loadClass("okhttp3.Request$Builder");
        var FormBodyBuilderClass = loader.loadClass("okhttp3.FormBody$Builder");
        
        logi("Classes loaded.");
        
        // 2. Instantiate Builder
        var formBuilder = FormBodyBuilderClass.newInstance();
        
        // 3. Add Data (Method Chaining might be hard if return type is specific, but usually it returns Builder)
        // We will call add(key, value)
        // Check if we can find the 'add' method.
        // reflection: Method add = FormBodyBuilderClass.getMethod("add", String.class, String.class);
        // But with this engine, we might be able to direct call if it's public.
        
        // Let's try direct call first
        try {
            formBuilder.add("homeid", "okhttp_post_test");
            formBuilder.add("homename", "OkPostUser");
            formBuilder.add("record_time", "123456789");
            logi("Form data added via direct call.");
        } catch(e) {
            logi("Direct add failed ("+e+"), trying reflection...");
            // Use getMethods to find 'add'
             var methods = FormBodyBuilderClass.getMethods();
             var addMethod = null;
             for (var i=0; i<methods.length; i++) {
                 var m = methods[i];
                 if (m.getName() == "add") {
                      // Check args count
                      if (m.getParameterTypes().length == 2) {
                          addMethod = m;
                          break;
                      }
                 }
             }
             if (addMethod) {
                 addMethod.invoke(formBuilder, "homeid", "okhttp_post_test_reflect");
                 addMethod.invoke(formBuilder, "homename", "OkPostUserReflect");
                 addMethod.invoke(formBuilder, "record_time", "9999999");
                 logi("Form data added via reflection invoke.");
             } else {
                 loge("Could not find 'add' method on FormBody$Builder");
                 return;
             }
        }
        
        // 4. Build RequestBody
        var requestBody = formBuilder.build();
        logi("RequestBody built: " + requestBody);
        
        // 5. Build Request
        var reqBuilder = RequestBuilderClass.newInstance();
        reqBuilder.url("https://liveroomdata.sssr.edu.kg/upload");
        reqBuilder.post(requestBody);
        var request = reqBuilder.build();
        
        // 6. Execute
        var client = OkHttpClientClass.newInstance();
        var call = client.newCall(request);
        var response = call.execute();
        
        logi("Response Code: " + response.code());
        logi("Body: " + response.body().string());
        
    } catch(e) {
        loge("Exception: " + e);
        try { loge("Ex Detail: " + e.toString()); } catch(ex){}
    }
    
    logi("Test Ended");
}

main();
