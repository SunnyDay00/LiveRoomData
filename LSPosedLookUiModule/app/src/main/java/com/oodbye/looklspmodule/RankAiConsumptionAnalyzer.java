package com.oodbye.looklspmodule;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
final class RankAiConsumptionAnalyzer {
    private static final String TAG = "[RankAiAnalyzer]";
    private static final String LOGCAT_TAG = "RankAiAnalyzer";
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(3);
    private static final Object RESULT_FILE_LOCK = new Object();
    private static final Object RUNTIME_CONFIG_LOCK = new Object();
    private static final Pattern FILE_ROUND_PATTERN = Pattern.compile(".*_(\\d{8})_(\\d+)\\.csv$");
    private static final Pattern DATA_NUMERIC_PATTERN = Pattern.compile("^(\\d+(?:\\.\\d+)?)([万亿wW]?)$");
    private static final Pattern DATA_NUMERIC_FALLBACK_PATTERN =
            Pattern.compile("(\\d+(?:\\.\\d+)?)([万亿wW]?)");
    private static final SimpleDateFormat DAY_FORMAT = new SimpleDateFormat("yyyyMMdd", Locale.US);
    private static final SimpleDateFormat TS_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
    private static final String DIRECT_PREFS_NAME = "module_settings";
    private static final String DIRECT_KEY_AI_ANALYSIS_ENABLED = "ai_analysis_enabled";
    private static final String DIRECT_KEY_AI_API_URL = "ai_api_url";
    private static final String DIRECT_KEY_AI_API_KEY = "ai_api_key";
    private static final String DIRECT_KEY_AI_MODEL = "ai_model";
    private static final String DIRECT_KEY_FEISHU_PUSH_ENABLED = "feishu_push_enabled";
    private static final String DIRECT_KEY_FEISHU_WEBHOOK_URL = "feishu_webhook_url";
    private static final String DIRECT_KEY_FEISHU_SIGN_SECRET = "feishu_sign_secret";
    private static final boolean DIRECT_DEFAULT_AI_ANALYSIS_ENABLED = true;
    private static final boolean DIRECT_DEFAULT_FEISHU_PUSH_ENABLED = true;
    private static long sEngineConfigSeq = -1L;
    private static RuntimeConfigSnapshot sEngineConfigSnapshot;

    private static final String KEY_UESE_NAME = "用户昵称（uesename）";
    private static final String KEY_UESE_ID = "用户ID（ueseid）";
    private static final String KEY_LEVEL = "用户等级（level）";
    private static final String KEY_CONSUME = "消费数据（需计算）";
    private static final String KEY_ACCURACY = "消费计算准确度（百分百）";
    private static final String KEY_UP_NAME = "消费对象昵称（upname）";
    private static final String KEY_UP_ID = "消费对象ID（upid）";
    private static final String KEY_HOME_ID = "直播间ID（homeid）";
    private static final String KEY_TOTAL_DATA = "在直播间总消费数据（Data）";

    private RankAiConsumptionAnalyzer() {
    }

    static final class TestResult {
        final boolean success;
        final String detail;

        TestResult(boolean success, String detail) {
            this.success = success;
            this.detail = detail == null ? "" : detail.trim();
        }

        static TestResult ok(String detail) {
            return new TestResult(true, detail);
        }

        static TestResult fail(String detail) {
            return new TestResult(false, detail);
        }
    }

    static void updateRuntimeConfigFromEngineCommand(
            String command,
            long seq,
            boolean aiEnabled,
            String aiUrl,
            String aiApiKey,
            String aiModel,
            boolean feishuPushEnabled,
            String feishuWebhookUrl,
            String feishuSignSecret
    ) {
        String cmd = safeTrim(command);
        RuntimeConfigSnapshot snapshot = new RuntimeConfigSnapshot(
                aiEnabled,
                aiUrl,
                aiApiKey,
                aiModel,
                feishuPushEnabled,
                feishuWebhookUrl,
                feishuSignSecret,
                "engine_broadcast"
        );
        synchronized (RUNTIME_CONFIG_LOCK) {
            if (seq > 0L && sEngineConfigSeq > 0L && seq <= sEngineConfigSeq) {
                return;
            }
            sEngineConfigSeq = seq;
            sEngineConfigSnapshot = snapshot;
        }
        if (ModuleSettings.ENGINE_CMD_STOP.equalsIgnoreCase(cmd)) {
            // 保留最新配置，仅停止后续流程，不主动清空配置。
            return;
        }
        log(
                null,
                "runtime config updated from engine command: seq=" + seq
                        + " aiEnabled=" + aiEnabled
                        + " aiUrlSet=" + (!TextUtils.isEmpty(safeTrim(aiUrl)))
                        + " feishuEnabled=" + feishuPushEnabled
                        + " webhookSet=" + (!TextUtils.isEmpty(safeTrim(feishuWebhookUrl)))
        );
    }

    private static final class RuntimeConfigSnapshot {
        final boolean aiEnabled;
        final String aiUrl;
        final String aiApiKey;
        final String aiModel;
        final boolean feishuPushEnabled;
        final String feishuWebhookUrl;
        final String feishuSignSecret;
        final String source;

        RuntimeConfigSnapshot(
                boolean aiEnabled,
                String aiUrl,
                String aiApiKey,
                String aiModel,
                boolean feishuPushEnabled,
                String feishuWebhookUrl,
                String feishuSignSecret,
                String source
        ) {
            this.aiEnabled = aiEnabled;
            this.aiUrl = safeTrim(aiUrl);
            this.aiApiKey = safeTrim(aiApiKey);
            this.aiModel = safeTrim(aiModel);
            this.feishuPushEnabled = feishuPushEnabled;
            this.feishuWebhookUrl = safeTrim(feishuWebhookUrl);
            this.feishuSignSecret = safeTrim(feishuSignSecret);
            this.source = safeTrim(source);
        }
    }

    static void scheduleAnalysis(
            Context context,
            String homeId,
            long enterTimeMs,
            String contributionCsvPath,
            String charmCsvPath
    ) {
        final Context app = context == null ? null : context.getApplicationContext();
        if (app == null) {
            return;
        }
        final String safeHomeId = safeTrim(homeId);
        final String safeContributionPath = safeTrim(contributionCsvPath);
        final String safeCharmPath = safeTrim(charmCsvPath);
        final String traceId = buildTraceId(
                safeHomeId,
                enterTimeMs,
                safeContributionPath,
                safeCharmPath
        );
        if (TextUtils.isEmpty(safeHomeId)
                || TextUtils.isEmpty(safeContributionPath)
                || TextUtils.isEmpty(safeCharmPath)) {
            log(app, "skip analyze: missing required params traceId=" + traceId
                    + " homeId=" + safeHomeId
                    + " contribution=" + safeContributionPath
                    + " charm=" + safeCharmPath);
            return;
        }
        final RuntimeConfigSnapshot runtimeConfig = readRuntimeConfig(app);
        if (runtimeConfig == null || !runtimeConfig.aiEnabled) {
            log(app, "skip analyze: ai_analysis_disabled traceId=" + traceId + " source="
                    + (runtimeConfig == null ? "unknown" : runtimeConfig.source));
            return;
        }
        log(app, "schedule analyze: traceId=" + traceId
                + " configSource=" + runtimeConfig.source
                + " aiUrlSet=" + (!TextUtils.isEmpty(runtimeConfig.aiUrl))
                + " feishuEnabled=" + runtimeConfig.feishuPushEnabled
                + " homeId=" + safeHomeId
                + " executor=" + getExecutorStats());
        final long enqueueAtMs = SystemClock.elapsedRealtime();
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                long waitMs = Math.max(0L, SystemClock.elapsedRealtime() - enqueueAtMs);
                log(app, "analyze worker start: traceId=" + traceId
                        + " waitMs=" + waitMs
                        + " executor=" + getExecutorStats());
                runAnalysis(
                        app,
                        safeHomeId,
                        Math.max(0L, enterTimeMs),
                        safeContributionPath,
                        safeCharmPath,
                        runtimeConfig
                );
            }
        });
    }

    static TestResult testConnection(Context context) {
        final Context app = context == null ? null : context.getApplicationContext();
        if (app == null) {
            return TestResult.fail("context_null");
        }
        AiConfig config = readAiConfig(app);
        if (!config.valid) {
            return TestResult.fail(config.invalidReason);
        }
        String resolvedUrl = resolveChatCompletionsUrl(config.url);
        log(app, "test connection start: configuredEndpoint=" + summarizeEndpoint(config.url)
                + " requestEndpoint=" + summarizeEndpoint(resolvedUrl)
                + " model=" + safeTrim(config.model));
        String prompt = ensurePromptAndRead(app);
        if (TextUtils.isEmpty(prompt)) {
            prompt = buildDefaultPromptTemplate();
        }
        String userContent = "请仅返回一行：AI连接成功";
        HttpCallResult result = callAiChatCompletion(config, prompt, userContent);
        if (!result.success) {
            log(app, "test connection failed: status=" + result.statusCode
                    + " detail=" + safeTrim(result.detail));
            return TestResult.fail(result.detail);
        }
        String content = safeTrim(result.messageContent);
        if (TextUtils.isEmpty(content)) {
            content = safeTrim(result.rawBody);
        }
        if (TextUtils.isEmpty(content)) {
            content = "AI连接成功";
        }
        log(app, "test connection success: status=" + result.statusCode
                + " reply=" + truncate(content, 120));
        return TestResult.ok("测试连接成功: " + truncate(content, 180));
    }

    private static void runAnalysis(
            Context context,
            String homeId,
            long enterTimeMs,
            String contributionCsvPath,
            String charmCsvPath,
            RuntimeConfigSnapshot runtimeConfig
    ) {
        String traceId = buildTraceId(homeId, enterTimeMs, contributionCsvPath, charmCsvPath);
        long startedAt = SystemClock.elapsedRealtime();
        try {
            RuntimeConfigSnapshot activeConfig = runtimeConfig == null
                    ? readRuntimeConfig(context)
                    : runtimeConfig;
            log(context, "analyze start: traceId=" + traceId
                    + " configSource=" + (activeConfig == null ? "unknown" : activeConfig.source)
                    + " homeId=" + safeTrim(homeId)
                    + " enterTimeMs=" + Math.max(0L, enterTimeMs));
            AiConfig config = buildAiConfig(activeConfig);
            if (!config.valid) {
                log(context, "skip analyze: invalid config, traceId=" + traceId + ", source="
                        + (activeConfig == null ? "unknown" : activeConfig.source)
                        + ", detail=" + config.invalidReason);
                return;
            }
            File contributionCurrentFile = new File(contributionCsvPath);
            File charmCurrentFile = new File(charmCsvPath);
            log(context, "analyze current csv check: traceId=" + traceId
                    + " contributionExists=" + contributionCurrentFile.exists()
                    + " contributionLen=" + contributionCurrentFile.length()
                    + " charmExists=" + charmCurrentFile.exists()
                    + " charmLen=" + charmCurrentFile.length());
            if (!waitForCurrentCsvFiles(contributionCurrentFile, charmCurrentFile)) {
                log(context, "skip analyze: current csv missing, traceId=" + traceId
                        + " contributionExists=" + contributionCurrentFile.exists()
                        + " charmExists=" + charmCurrentFile.exists()
                        + " contribution=" + contributionCsvPath
                        + " charm=" + charmCsvPath);
                return;
            }
            CsvRoundRef currentRef = parseRoundRef(contributionCurrentFile.getName());
            if (currentRef == null) {
                log(context, "skip analyze: invalid current contribution file name, traceId="
                        + traceId + " file="
                        + contributionCurrentFile.getName());
                return;
            }
            RoomRoundData currentData = loadRoomRoundData(
                    contributionCurrentFile,
                    charmCurrentFile,
                    homeId,
                    currentRef.roundIndex
            );
            log(context, "analyze current data loaded: traceId=" + traceId
                    + " round=" + currentData.roundIndex
                    + " contributionRows=" + currentData.contributionRows.size()
                    + " charmRows=" + currentData.charmRows.size());
            if (!currentData.isUsable()) {
                log(context, "skip analyze: current round room data empty, traceId=" + traceId
                        + " homeId=" + homeId);
                return;
            }
            PreviousRoundSearchResult previous = findPreviousRoundData(
                    contributionCurrentFile,
                    charmCurrentFile,
                    homeId,
                    currentRef
            );
            if (previous == null || !previous.found || previous.data == null || !previous.data.isUsable()) {
                int scanned = previous == null ? 0 : previous.scannedRounds;
                int existingPairs = previous == null ? 0 : previous.existingPairRounds;
                log(context, "skip analyze: previous round room data not found, traceId=" + traceId
                        + " homeId=" + homeId
                        + " scannedRounds=" + scanned
                        + " existingPairRounds=" + existingPairs);
                return;
            }
            log(context, "analyze previous data loaded: traceId=" + traceId
                    + " previousRound=" + previous.data.roundIndex
                    + " contributionRows=" + previous.data.contributionRows.size()
                    + " charmRows=" + previous.data.charmRows.size()
                    + " scannedRounds=" + previous.scannedRounds
                    + " existingPairRounds=" + previous.existingPairRounds);
            String prompt = ensurePromptAndRead(context);
            if (TextUtils.isEmpty(prompt)) {
                prompt = buildDefaultPromptTemplate();
            }
            JSONObject payload = buildPayloadJson(homeId, enterTimeMs, currentData, previous.data);
            String userContent = "请根据下方 JSON 数据进行“用户消费数据与消费对象”分析。"
                    + "\n注意：数据采集可能不完整，请给出准确度百分比。"
                    + "\n\n" + payload.toString();
            log(context, "analyze ai request start: traceId=" + traceId
                    + " endpoint=" + summarizeEndpoint(config.url)
                    + " model=" + safeTrim(config.model)
                    + " promptChars=" + prompt.length()
                    + " payloadChars=" + payload.toString().length());
            long aiReqAt = SystemClock.elapsedRealtime();
            HttpCallResult aiResult = callAiChatCompletion(config, prompt, userContent);
            long aiElapsed = Math.max(0L, SystemClock.elapsedRealtime() - aiReqAt);
            log(context, "analyze ai request end: traceId=" + traceId
                    + " success=" + aiResult.success
                    + " status=" + aiResult.statusCode
                    + " elapsedMs=" + aiElapsed
                    + " detail=" + truncate(aiResult.detail, 180)
                    + " bodyChars=" + safeTrim(aiResult.rawBody).length()
                    + " contentChars=" + safeTrim(aiResult.messageContent).length());
            String rawBodyText = safeTrim(aiResult.rawBody);
            if (!TextUtils.isEmpty(rawBodyText)) {
                log(context, "analyze ai raw body begin: traceId=" + traceId
                        + " chars=" + rawBodyText.length());
                logLargeContent(context, "analyze ai raw body", traceId, rawBodyText);
                log(context, "analyze ai raw body end: traceId=" + traceId);
            }
            if (!aiResult.success) {
                log(context, "analyze failed: traceId=" + traceId + " " + aiResult.detail);
                return;
            }
            String aiText = safeTrim(aiResult.messageContent);
            if (TextUtils.isEmpty(aiText)) {
                aiText = safeTrim(aiResult.rawBody);
            }
            if (TextUtils.isEmpty(aiText)) {
                log(context, "analyze failed: ai empty response, traceId=" + traceId);
                return;
            }
            log(context, "analyze ai reply begin: traceId=" + traceId
                    + " chars=" + aiText.length());
            logLargeContent(context, "analyze ai reply", traceId, aiText);
            log(context, "analyze ai reply end: traceId=" + traceId);
            List<AnalysisRecord> records = parseAnalysisRecords(aiText, homeId);
            enrichAnalysisRecordsFromCollectedData(records, currentData, previous.data);
            records = sanitizeAnalysisRecords(records);
            log(context, "analyze parse records: traceId=" + traceId
                    + " parsedCount=" + records.size()
                    + " aiTextChars=" + aiText.length());
            if (records.isEmpty()) {
                log(context, "analyze parse empty valid records: traceId=" + traceId
                        + " aiPreview=" + truncate(aiText.replace('\n', ' '), 260));
            }
            appendAnalysisResult(
                    context,
                    homeId,
                    currentData.roundIndex,
                    previous.data.roundIndex,
                    records,
                    aiText
            );
            dispatchFeishuWebhook(
                    context,
                    homeId,
                    currentData.roundIndex,
                    previous.data.roundIndex,
                    records,
                    aiText,
                    activeConfig
            );
            log(context, "analyze success: traceId=" + traceId
                    + " homeId=" + homeId
                    + " currentRound=" + currentData.roundIndex
                    + " previousRound=" + previous.data.roundIndex
                    + " records=" + records.size()
                    + " totalMs=" + Math.max(0L, SystemClock.elapsedRealtime() - startedAt));
        } catch (Throwable e) {
            log(context, "analyze exception: traceId=" + traceId + " " + e);
        }
    }

    private static boolean waitForCurrentCsvFiles(File contribution, File charm) {
        if (contribution == null || charm == null) {
            return false;
        }
        final int maxTry = 10;
        final long waitMs = 120L;
        for (int i = 0; i < maxTry; i++) {
            if (contribution.exists() && charm.exists()) {
                return true;
            }
            SystemClock.sleep(waitMs);
        }
        return contribution.exists() && charm.exists();
    }

    private static AiConfig readAiConfig(Context context) {
        RuntimeConfigSnapshot snapshot = readRuntimeConfig(context);
        return buildAiConfig(snapshot);
    }

    private static AiConfig buildAiConfig(RuntimeConfigSnapshot snapshot) {
        String url = snapshot == null ? "" : safeTrim(snapshot.aiUrl);
        String key = snapshot == null ? "" : safeTrim(snapshot.aiApiKey);
        String model = snapshot == null ? "" : safeTrim(snapshot.aiModel);
        if (TextUtils.isEmpty(url)) {
            return AiConfig.invalid("AI配置不完整：请填写 AI大模型URL");
        }
        if (TextUtils.isEmpty(key)) {
            return AiConfig.invalid("AI配置不完整：请填写 AI大模型AKY");
        }
        if (TextUtils.isEmpty(model)) {
            return AiConfig.invalid("AI配置不完整：请填写 AI大模型model");
        }
        return new AiConfig(url, key, model, true, "");
    }

    private static RuntimeConfigSnapshot readRuntimeConfig(Context context) {
        RuntimeConfigSnapshot engineConfig = readRuntimeConfigFromEngineCommand();
        RuntimeConfigSnapshot direct = readRuntimeConfigFromDirect(context);
        if (isModuleProcessContext(context)) {
            return direct;
        }
        if (isAiConfigComplete(engineConfig)) {
            return engineConfig;
        }
        RuntimeConfigSnapshot xsp = readRuntimeConfigFromXsp(context);
        RuntimeConfigSnapshot moduleContext = readRuntimeConfigFromModuleContext(context);

        if (isAiConfigComplete(xsp)) {
            return xsp;
        }
        if (isAiConfigComplete(moduleContext)) {
            log(context, "config source fallback: xsp_ai_incomplete -> module_context");
            return moduleContext;
        }
        if (isConfigFilled(engineConfig)) {
            log(context, "config source fallback: xsp/module_context_not_filled -> engine_broadcast");
            return engineConfig;
        }
        if (isConfigFilled(moduleContext) && !isConfigFilled(xsp)) {
            log(context, "config source fallback: xsp_empty -> module_context");
            return moduleContext;
        }
        if (isConfigFilled(xsp)) {
            return xsp;
        }
        if (xsp != null) {
            return xsp;
        }
        if (moduleContext != null) {
            return moduleContext;
        }
        return new RuntimeConfigSnapshot(
                direct.aiEnabled,
                direct.aiUrl,
                direct.aiApiKey,
                direct.aiModel,
                direct.feishuPushEnabled,
                direct.feishuWebhookUrl,
                direct.feishuSignSecret,
                "direct_fallback"
        );
    }

    private static RuntimeConfigSnapshot readRuntimeConfigFromEngineCommand() {
        synchronized (RUNTIME_CONFIG_LOCK) {
            return sEngineConfigSnapshot;
        }
    }

    private static RuntimeConfigSnapshot readRuntimeConfigFromModuleContext(Context context) {
        if (context == null) {
            return null;
        }
        try {
            Context moduleContext = context.createPackageContext(
                    ModuleSettings.MODULE_PACKAGE,
                    Context.CONTEXT_IGNORE_SECURITY
            );
            SharedPreferences prefs = moduleContext.getSharedPreferences(
                    DIRECT_PREFS_NAME,
                    Context.MODE_PRIVATE
            );
            return new RuntimeConfigSnapshot(
                    prefs.getBoolean(DIRECT_KEY_AI_ANALYSIS_ENABLED, DIRECT_DEFAULT_AI_ANALYSIS_ENABLED),
                    safeTrim(prefs.getString(DIRECT_KEY_AI_API_URL, "")),
                    safeTrim(prefs.getString(DIRECT_KEY_AI_API_KEY, "")),
                    safeTrim(prefs.getString(DIRECT_KEY_AI_MODEL, "")),
                    prefs.getBoolean(DIRECT_KEY_FEISHU_PUSH_ENABLED, DIRECT_DEFAULT_FEISHU_PUSH_ENABLED),
                    safeTrim(prefs.getString(DIRECT_KEY_FEISHU_WEBHOOK_URL, "")),
                    safeTrim(prefs.getString(DIRECT_KEY_FEISHU_SIGN_SECRET, "")),
                    "module_context"
            );
        } catch (Throwable e) {
            log(context, "config read module_context failed: " + safeTrim(String.valueOf(e)));
            return null;
        }
    }

    private static boolean isConfigFilled(RuntimeConfigSnapshot snapshot) {
        if (snapshot == null) {
            return false;
        }
        return !TextUtils.isEmpty(safeTrim(snapshot.aiUrl))
                || !TextUtils.isEmpty(safeTrim(snapshot.aiApiKey))
                || !TextUtils.isEmpty(safeTrim(snapshot.aiModel))
                || !TextUtils.isEmpty(safeTrim(snapshot.feishuWebhookUrl));
    }

    private static boolean isAiConfigComplete(RuntimeConfigSnapshot snapshot) {
        if (snapshot == null) {
            return false;
        }
        return !TextUtils.isEmpty(safeTrim(snapshot.aiUrl))
                && !TextUtils.isEmpty(safeTrim(snapshot.aiApiKey))
                && !TextUtils.isEmpty(safeTrim(snapshot.aiModel));
    }

    private static RuntimeConfigSnapshot readRuntimeConfigFromDirect(Context context) {
        boolean aiEnabled = DIRECT_DEFAULT_AI_ANALYSIS_ENABLED;
        String aiUrl = "";
        String aiApiKey = "";
        String aiModel = "";
        boolean feishuPushEnabled = DIRECT_DEFAULT_FEISHU_PUSH_ENABLED;
        String feishuWebhookUrl = "";
        String feishuSignSecret = "";
        if (context != null) {
            try {
                SharedPreferences prefs = context.getSharedPreferences(
                        DIRECT_PREFS_NAME,
                        Context.MODE_PRIVATE
                );
                aiEnabled = prefs.getBoolean(
                        DIRECT_KEY_AI_ANALYSIS_ENABLED,
                        DIRECT_DEFAULT_AI_ANALYSIS_ENABLED
                );
                aiUrl = safeTrim(prefs.getString(DIRECT_KEY_AI_API_URL, ""));
                aiApiKey = safeTrim(prefs.getString(DIRECT_KEY_AI_API_KEY, ""));
                aiModel = safeTrim(prefs.getString(DIRECT_KEY_AI_MODEL, ""));
                feishuPushEnabled = prefs.getBoolean(
                        DIRECT_KEY_FEISHU_PUSH_ENABLED,
                        DIRECT_DEFAULT_FEISHU_PUSH_ENABLED
                );
                feishuWebhookUrl = safeTrim(prefs.getString(DIRECT_KEY_FEISHU_WEBHOOK_URL, ""));
                feishuSignSecret = safeTrim(prefs.getString(DIRECT_KEY_FEISHU_SIGN_SECRET, ""));
            } catch (Throwable ignore) {
            }
        }
        return new RuntimeConfigSnapshot(
                aiEnabled,
                aiUrl,
                aiApiKey,
                aiModel,
                feishuPushEnabled,
                feishuWebhookUrl,
                feishuSignSecret,
                "direct"
        );
    }

    private static RuntimeConfigSnapshot readRuntimeConfigFromXsp(Context context) {
        try {
            return new RuntimeConfigSnapshot(
                    ModuleSettings.isAiAnalysisEnabled(),
                    ModuleSettings.getAiApiUrl(),
                    ModuleSettings.getAiApiKey(),
                    ModuleSettings.getAiModel(),
                    ModuleSettings.isFeishuPushEnabled(),
                    ModuleSettings.getFeishuWebhookUrl(),
                    ModuleSettings.getFeishuSignSecret(),
                    "xsp"
            );
        } catch (Throwable e) {
            log(context, "config read xsp failed: " + safeTrim(String.valueOf(e)));
            return null;
        }
    }

    private static boolean isModuleProcessContext(Context context) {
        if (context == null) {
            return false;
        }
        String packageName = safeTrim(context.getPackageName());
        return ModuleSettings.MODULE_PACKAGE.equals(packageName);
    }

    private static JSONObject buildPayloadJson(
            String homeId,
            long enterTimeMs,
            RoomRoundData current,
            RoomRoundData previous
    ) {
        JSONObject root = new JSONObject();
        try {
            root.put("homeid", safeTrim(homeId));
            root.put("enterTimeMs", Math.max(0L, enterTimeMs));
            root.put("note", "数据可能不完整，结果仅作推断");
            root.put("currentRound", current.roundIndex);
            root.put("previousRound", previous.roundIndex);
            root.put("contributionCurrent", buildContributionJsonArray(current.contributionRows, previous.contributionRows));
            root.put("contributionPrevious", buildContributionJsonArray(previous.contributionRows, Collections.<ContributionData>emptyList()));
            root.put("charmCurrent", buildCharmJsonArray(current.charmRows, previous.charmRows));
            root.put("charmPrevious", buildCharmJsonArray(previous.charmRows, Collections.<CharmData>emptyList()));
        } catch (Throwable ignore) {
        }
        return root;
    }

    private static JSONArray buildContributionJsonArray(
            List<ContributionData> currentRows,
            List<ContributionData> previousRows
    ) {
        JSONArray arr = new JSONArray();
        Map<String, ContributionData> previousMap = new HashMap<String, ContributionData>();
        if (previousRows != null) {
            for (ContributionData row : previousRows) {
                if (row == null) {
                    continue;
                }
                previousMap.put(row.identityKey(), row);
            }
        }
        if (currentRows == null) {
            return arr;
        }
        ArrayList<ContributionData> sorted = new ArrayList<ContributionData>(currentRows);
        Collections.sort(sorted, new Comparator<ContributionData>() {
            @Override
            public int compare(ContributionData o1, ContributionData o2) {
                if (o1 == o2) {
                    return 0;
                }
                if (o1 == null) {
                    return -1;
                }
                if (o2 == null) {
                    return 1;
                }
                return safeTrim(o1.userName).compareTo(safeTrim(o2.userName));
            }
        });
        for (ContributionData row : sorted) {
            if (row == null) {
                continue;
            }
            ContributionData prev = previousMap.get(row.identityKey());
            JSONObject obj = new JSONObject();
            try {
                obj.put("ueseid", row.userId);
                obj.put("uesename", row.userName);
                obj.put("level", row.level);
                obj.put("dataRaw", row.dataRaw);
                if (row.dataValue >= 0L) {
                    obj.put("dataValue", row.dataValue);
                } else {
                    obj.put("dataValue", JSONObject.NULL);
                }
                if (prev != null && prev.dataValue >= 0L && row.dataValue >= 0L) {
                    obj.put("previousDataValue", prev.dataValue);
                    obj.put("deltaDataValue", Math.max(0L, row.dataValue - prev.dataValue));
                } else {
                    obj.put("previousDataValue", JSONObject.NULL);
                    obj.put("deltaDataValue", JSONObject.NULL);
                }
            } catch (Throwable ignore) {
            }
            arr.put(obj);
        }
        return arr;
    }

    private static JSONArray buildCharmJsonArray(
            List<CharmData> currentRows,
            List<CharmData> previousRows
    ) {
        JSONArray arr = new JSONArray();
        Map<String, CharmData> previousMap = new HashMap<String, CharmData>();
        if (previousRows != null) {
            for (CharmData row : previousRows) {
                if (row == null) {
                    continue;
                }
                previousMap.put(row.identityKey(), row);
            }
        }
        if (currentRows == null) {
            return arr;
        }
        ArrayList<CharmData> sorted = new ArrayList<CharmData>(currentRows);
        Collections.sort(sorted, new Comparator<CharmData>() {
            @Override
            public int compare(CharmData o1, CharmData o2) {
                if (o1 == o2) {
                    return 0;
                }
                if (o1 == null) {
                    return -1;
                }
                if (o2 == null) {
                    return 1;
                }
                return safeTrim(o1.upName).compareTo(safeTrim(o2.upName));
            }
        });
        for (CharmData row : sorted) {
            if (row == null) {
                continue;
            }
            CharmData prev = previousMap.get(row.identityKey());
            JSONObject obj = new JSONObject();
            try {
                obj.put("upid", row.upId);
                obj.put("upname", row.upName);
                obj.put("dataRaw", row.dataRaw);
                if (row.dataValue >= 0L) {
                    obj.put("dataValue", row.dataValue);
                } else {
                    obj.put("dataValue", JSONObject.NULL);
                }
                if (prev != null && prev.dataValue >= 0L && row.dataValue >= 0L) {
                    obj.put("previousDataValue", prev.dataValue);
                    obj.put("deltaDataValue", Math.max(0L, row.dataValue - prev.dataValue));
                } else {
                    obj.put("previousDataValue", JSONObject.NULL);
                    obj.put("deltaDataValue", JSONObject.NULL);
                }
            } catch (Throwable ignore) {
            }
            arr.put(obj);
        }
        return arr;
    }

    private static PreviousRoundSearchResult findPreviousRoundData(
            File currentContributionFile,
            File currentCharmFile,
            String homeId,
            CsvRoundRef currentRef
    ) {
        if (currentContributionFile == null || currentCharmFile == null || currentRef == null) {
            return PreviousRoundSearchResult.notFound(0, 0);
        }
        File dir = currentContributionFile.getParentFile();
        if (dir == null) {
            return PreviousRoundSearchResult.notFound(0, 0);
        }
        int currentRound = Math.max(1, currentRef.roundIndex);
        int scannedRounds = 0;
        int existingPairRounds = 0;
        for (int round = currentRound - 1; round >= 1; round--) {
            scannedRounds++;
            File contribution = new File(
                    dir,
                    UiComponentConfig.LIVE_RANK_CSV_CONTRIBUTION_PREFIX
                            + "_" + currentRef.dayToken + "_" + round + UiComponentConfig.LIVE_RANK_CSV_SUFFIX
            );
            File charm = new File(
                    dir,
                    UiComponentConfig.LIVE_RANK_CSV_CHARM_PREFIX
                            + "_" + currentRef.dayToken + "_" + round + UiComponentConfig.LIVE_RANK_CSV_SUFFIX
            );
            if (!contribution.exists() || !charm.exists()) {
                continue;
            }
            existingPairRounds++;
            RoomRoundData data = loadRoomRoundData(contribution, charm, homeId, round);
            if (data.isUsable()) {
                return PreviousRoundSearchResult.found(data, scannedRounds, existingPairRounds);
            }
        }
        return PreviousRoundSearchResult.notFound(scannedRounds, existingPairRounds);
    }

    private static RoomRoundData loadRoomRoundData(
            File contributionFile,
            File charmFile,
            String homeId,
            int roundIndex
    ) {
        List<ContributionData> contributionRows = readContributionRows(contributionFile, homeId);
        List<CharmData> charmRows = readCharmRows(charmFile, homeId);
        return new RoomRoundData(roundIndex, contributionRows, charmRows);
    }

    private static List<ContributionData> readContributionRows(File file, String homeId) {
        if (file == null || !file.exists() || TextUtils.isEmpty(homeId)) {
            return Collections.emptyList();
        }
        ArrayList<ContributionData> rows = new ArrayList<ContributionData>();
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(
                    new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)
            );
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (first) {
                    first = false;
                    continue;
                }
                List<String> cells = parseCsvLine(line);
                if (cells.size() < 5) {
                    continue;
                }
                String rowHome = safeTrim(cells.get(0));
                if (!safeTrim(homeId).equals(rowHome)) {
                    continue;
                }
                String userId = getCell(cells, 1);
                String userName = getCell(cells, 2);
                String level = getCell(cells, 3);
                String dataRaw = getCell(cells, 4);
                rows.add(new ContributionData(rowHome, userId, userName, level, dataRaw));
            }
        } catch (Throwable ignore) {
            return Collections.emptyList();
        } finally {
            closeQuietly(reader);
        }
        return rows;
    }

    private static List<CharmData> readCharmRows(File file, String homeId) {
        if (file == null || !file.exists() || TextUtils.isEmpty(homeId)) {
            return Collections.emptyList();
        }
        ArrayList<CharmData> rows = new ArrayList<CharmData>();
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(
                    new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)
            );
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (first) {
                    first = false;
                    continue;
                }
                List<String> cells = parseCsvLine(line);
                if (cells.size() < 4) {
                    continue;
                }
                String rowHome = safeTrim(cells.get(0));
                if (!safeTrim(homeId).equals(rowHome)) {
                    continue;
                }
                String upId = getCell(cells, 1);
                String upName = getCell(cells, 2);
                String dataRaw = getCell(cells, 3);
                rows.add(new CharmData(rowHome, upId, upName, dataRaw));
            }
        } catch (Throwable ignore) {
            return Collections.emptyList();
        } finally {
            closeQuietly(reader);
        }
        return rows;
    }

    private static String getCell(List<String> cells, int index) {
        if (cells == null || index < 0 || index >= cells.size()) {
            return "";
        }
        return safeTrim(cells.get(index));
    }

    private static List<String> parseCsvLine(String line) {
        if (line == null) {
            return Collections.emptyList();
        }
        ArrayList<String> result = new ArrayList<String>();
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    sb.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
                continue;
            }
            if (c == ',' && !inQuotes) {
                result.add(sb.toString());
                sb.setLength(0);
                continue;
            }
            sb.append(c);
        }
        result.add(sb.toString());
        return result;
    }

    private static CsvRoundRef parseRoundRef(String fileName) {
        String safeName = safeTrim(fileName);
        if (TextUtils.isEmpty(safeName)) {
            return null;
        }
        Matcher matcher = FILE_ROUND_PATTERN.matcher(safeName);
        if (!matcher.matches()) {
            return null;
        }
        String dayToken = safeTrim(matcher.group(1));
        int round = parsePositiveIntSafe(matcher.group(2));
        if (TextUtils.isEmpty(dayToken) || round <= 0) {
            return null;
        }
        return new CsvRoundRef(dayToken, round);
    }

    private static int parsePositiveIntSafe(String raw) {
        try {
            int value = Integer.parseInt(safeTrim(raw));
            return Math.max(0, value);
        } catch (Throwable ignore) {
            return 0;
        }
    }

    private static long parseDataValue(String raw) {
        String value = safeTrim(raw)
                .replace(",", "")
                .replace("，", "")
                .replace(" ", "");
        if (TextUtils.isEmpty(value)) {
            return -1L;
        }
        Matcher matcher = DATA_NUMERIC_PATTERN.matcher(value);
        if (!matcher.matches()) {
            matcher = DATA_NUMERIC_FALLBACK_PATTERN.matcher(value);
            if (!matcher.find()) {
                return -1L;
            }
        }
        double number;
        try {
            number = Double.parseDouble(safeTrim(matcher.group(1)));
        } catch (Throwable ignore) {
            return -1L;
        }
        if (Double.isNaN(number) || Double.isInfinite(number) || number < 0d) {
            return -1L;
        }
        String unit = safeTrim(matcher.group(2));
        double multiplier = 1d;
        if ("万".equals(unit) || "w".equals(unit) || "W".equals(unit)) {
            multiplier = 10000d;
        } else if ("亿".equals(unit)) {
            multiplier = 100000000d;
        }
        double normalized = number * multiplier;
        if (Double.isNaN(normalized) || Double.isInfinite(normalized)
                || normalized < 0d || normalized > Long.MAX_VALUE) {
            return -1L;
        }
        return Math.round(normalized);
    }

    private static HttpCallResult callAiChatCompletion(
            AiConfig config,
            String systemPrompt,
            String userContent
    ) {
        if (config == null || !config.valid) {
            return HttpCallResult.fail("invalid_ai_config", "");
        }
        HttpURLConnection connection = null;
        OutputStream os = null;
        InputStream is = null;
        String rawBody = "";
        int statusCode = -1;
        String requestUrl = resolveChatCompletionsUrl(config.url);
        String endpointSummary = summarizeEndpoint(requestUrl);
        String configuredEndpointSummary = summarizeEndpoint(config.url);
        try {
            URL url = new URL(requestUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(UiComponentConfig.AI_HTTP_CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(UiComponentConfig.AI_HTTP_READ_TIMEOUT_MS);
            connection.setRequestMethod("POST");
            connection.setDoInput(true);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "LOOK-LSP-AI/1.0");
            String auth = safeTrim(config.apiKey);
            if (!TextUtils.isEmpty(auth)) {
                if (!auth.toLowerCase(Locale.US).startsWith("bearer ")) {
                    auth = "Bearer " + auth;
                }
                connection.setRequestProperty("Authorization", auth);
                connection.setRequestProperty("api-key", safeTrim(config.apiKey));
            }
            JSONObject body = new JSONObject();
            body.put("model", safeTrim(config.model));
            JSONArray messages = new JSONArray();
            messages.put(new JSONObject()
                    .put("role", "system")
                    .put("content", safeTrim(systemPrompt)));
            messages.put(new JSONObject()
                    .put("role", "user")
                    .put("content", safeTrim(userContent)));
            body.put("messages", messages);
            body.put("temperature", 0.1);
            byte[] requestBytes = body.toString().getBytes(StandardCharsets.UTF_8);
            os = connection.getOutputStream();
            os.write(requestBytes);
            os.flush();
            statusCode = connection.getResponseCode();
            if (statusCode >= 200 && statusCode < 300) {
                is = connection.getInputStream();
            } else {
                is = connection.getErrorStream();
            }
            rawBody = readAllText(is);
            String messageContent = extractMessageContent(rawBody);
            if (statusCode >= 200 && statusCode < 300) {
                return HttpCallResult.ok(statusCode, rawBody, messageContent);
            }
            String responseSummary = truncate(rawBody, 240);
            if (TextUtils.isEmpty(responseSummary)) {
                responseSummary = "empty_body";
            }
            String detail;
            if (statusCode == 404) {
                detail = "http_404: endpoint_not_found(configured="
                        + configuredEndpointSummary + ",request=" + endpointSummary + ")"
                        + " response=" + responseSummary
                        + "，请确认填写的是完整 Chat Completions 接口URL";
            } else {
                detail = "http_" + statusCode + ": configuredEndpoint="
                        + configuredEndpointSummary
                        + " requestEndpoint=" + endpointSummary
                        + " response=" + responseSummary;
            }
            return HttpCallResult.fail(statusCode, detail, rawBody);
        } catch (Throwable e) {
            return HttpCallResult.fail(
                    statusCode,
                    "request_exception(configuredEndpoint=" + configuredEndpointSummary
                            + ",requestEndpoint=" + endpointSummary + "):"
                            + safeTrim(String.valueOf(e)),
                    rawBody
            );
        } finally {
            closeQuietly(os);
            closeQuietly(is);
            if (connection != null) {
                try {
                    connection.disconnect();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    private static String extractMessageContent(String rawBody) {
        String body = safeTrim(rawBody);
        if (TextUtils.isEmpty(body)) {
            return "";
        }
        try {
            JSONObject json = new JSONObject(body);
            JSONArray choices = json.optJSONArray("choices");
            if (choices != null && choices.length() > 0) {
                JSONObject firstChoice = choices.optJSONObject(0);
                if (firstChoice != null) {
                    JSONObject message = firstChoice.optJSONObject("message");
                    if (message != null) {
                        Object contentObj = message.opt("content");
                        String content = normalizeContentObject(contentObj);
                        if (!TextUtils.isEmpty(content)) {
                            return content;
                        }
                    }
                    String text = safeTrim(firstChoice.optString("text"));
                    if (!TextUtils.isEmpty(text)) {
                        return text;
                    }
                }
            }
            String outputText = safeTrim(json.optString("output_text"));
            if (!TextUtils.isEmpty(outputText)) {
                return outputText;
            }
            JSONArray outputArray = json.optJSONArray("output");
            if (outputArray != null) {
                for (int i = 0; i < outputArray.length(); i++) {
                    JSONObject item = outputArray.optJSONObject(i);
                    if (item == null) {
                        continue;
                    }
                    JSONArray contentArray = item.optJSONArray("content");
                    if (contentArray == null) {
                        continue;
                    }
                    StringBuilder sb = new StringBuilder();
                    for (int j = 0; j < contentArray.length(); j++) {
                        JSONObject c = contentArray.optJSONObject(j);
                        if (c == null) {
                            continue;
                        }
                        String txt = safeTrim(c.optString("text"));
                        if (!TextUtils.isEmpty(txt)) {
                            if (sb.length() > 0) {
                                sb.append('\n');
                            }
                            sb.append(txt);
                        }
                    }
                    if (sb.length() > 0) {
                        return sb.toString();
                    }
                }
            }
        } catch (Throwable ignore) {
            // 不是 JSON 时回退原文。
        }
        return body;
    }

    private static String normalizeContentObject(Object contentObj) {
        if (contentObj == null) {
            return "";
        }
        if (contentObj instanceof String) {
            return safeTrim((String) contentObj);
        }
        if (contentObj instanceof JSONArray) {
            JSONArray arr = (JSONArray) contentObj;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.length(); i++) {
                Object item = arr.opt(i);
                if (item instanceof String) {
                    String text = safeTrim((String) item);
                    if (!TextUtils.isEmpty(text)) {
                        if (sb.length() > 0) {
                            sb.append('\n');
                        }
                        sb.append(text);
                    }
                    continue;
                }
                if (item instanceof JSONObject) {
                    JSONObject obj = (JSONObject) item;
                    String text = safeTrim(obj.optString("text"));
                    if (!TextUtils.isEmpty(text)) {
                        if (sb.length() > 0) {
                            sb.append('\n');
                        }
                        sb.append(text);
                    }
                }
            }
            return sb.toString();
        }
        return safeTrim(String.valueOf(contentObj));
    }

    private static String summarizeEndpoint(String rawUrl) {
        String urlText = safeTrim(rawUrl);
        if (TextUtils.isEmpty(urlText)) {
            return "";
        }
        try {
            URL parsed = new URL(urlText);
            String protocol = safeTrim(parsed.getProtocol());
            String host = safeTrim(parsed.getHost());
            int port = parsed.getPort();
            String path = safeTrim(parsed.getPath());
            StringBuilder sb = new StringBuilder();
            if (!TextUtils.isEmpty(protocol)) {
                sb.append(protocol).append("://");
            }
            sb.append(host);
            if (port > 0) {
                sb.append(':').append(port);
            }
            if (!TextUtils.isEmpty(path)) {
                sb.append(path);
            }
            return sb.toString();
        } catch (Throwable ignore) {
            return truncate(urlText, 120);
        }
    }

    private static String resolveChatCompletionsUrl(String rawUrl) {
        String urlText = safeTrim(rawUrl);
        if (TextUtils.isEmpty(urlText)) {
            return "";
        }
        try {
            URI uri = new URI(urlText);
            String path = safeTrim(uri.getPath());
            String lowerPath = path.toLowerCase(Locale.US);
            if (lowerPath.endsWith("/chat/completions")) {
                return uri.toString();
            }
            boolean shouldAppend = false;
            if (TextUtils.isEmpty(path) || "/".equals(path)) {
                shouldAppend = true;
            } else if ("/v1".equals(lowerPath)
                    || "/v1/".equals(lowerPath)
                    || "/v1beta".equals(lowerPath)
                    || "/v1beta/".equals(lowerPath)) {
                shouldAppend = true;
            }
            if (!shouldAppend) {
                return uri.toString();
            }
            String normalizedPath = path;
            if (TextUtils.isEmpty(normalizedPath)) {
                normalizedPath = "/chat/completions";
            } else if (normalizedPath.endsWith("/")) {
                normalizedPath = normalizedPath + "chat/completions";
            } else {
                normalizedPath = normalizedPath + "/chat/completions";
            }
            URI resolved = new URI(
                    uri.getScheme(),
                    uri.getAuthority(),
                    normalizedPath,
                    uri.getQuery(),
                    uri.getFragment()
            );
            return resolved.toString();
        } catch (Throwable ignore) {
            String lower = urlText.toLowerCase(Locale.US);
            if (lower.endsWith("/chat/completions")) {
                return urlText;
            }
            if (lower.endsWith("/v1")
                    || lower.endsWith("/v1/")
                    || lower.endsWith("/v1beta")
                    || lower.endsWith("/v1beta/")
                    || "/".equals(urlText)) {
                if (urlText.endsWith("/")) {
                    return urlText + "chat/completions";
                }
                return urlText + "/chat/completions";
            }
            return urlText;
        }
    }

    private static List<AnalysisRecord> parseAnalysisRecords(String aiText, String fallbackHomeId) {
        if (TextUtils.isEmpty(aiText)) {
            return Collections.emptyList();
        }
        String normalized = aiText.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\\n");
        ArrayList<AnalysisRecord> records = new ArrayList<AnalysisRecord>();
        AnalysisRecord current = null;
        for (String lineRaw : lines) {
            String line = safeTrim(lineRaw);
            line = normalizeAnalysisLine(line);
            if (TextUtils.isEmpty(line)) {
                continue;
            }
            if (line.startsWith("----")) {
                if (current != null && current.hasAnyField()) {
                    current.fillDefaultHomeId(fallbackHomeId);
                    records.add(current);
                }
                current = null;
                continue;
            }
            if (matchesFieldKey(line, KEY_UESE_NAME)) {
                if (current != null && current.hasAnyField()) {
                    current.fillDefaultHomeId(fallbackHomeId);
                    records.add(current);
                }
                current = new AnalysisRecord();
                current.userName = parseLineValue(line);
                continue;
            }
            if (current == null) {
                continue;
            }
            if (matchesFieldKey(line, KEY_UESE_ID)) {
                current.userId = parseLineValue(line);
            } else if (matchesFieldKey(line, KEY_LEVEL)) {
                current.level = parseLineValue(line);
            } else if (matchesFieldKey(line, KEY_CONSUME)) {
                current.consumeData = parseLineValue(line);
            } else if (matchesFieldKey(line, KEY_ACCURACY)) {
                current.accuracy = parseLineValue(line);
            } else if (matchesFieldKey(line, KEY_UP_NAME)) {
                current.targetName = parseLineValue(line);
            } else if (matchesFieldKey(line, KEY_UP_ID)) {
                current.targetId = parseLineValue(line);
            } else if (matchesFieldKey(line, KEY_HOME_ID)) {
                current.homeId = parseLineValue(line);
            } else if (matchesFieldKey(line, KEY_TOTAL_DATA)) {
                current.totalData = parseLineValue(line);
            }
        }
        if (current != null && current.hasAnyField()) {
            current.fillDefaultHomeId(fallbackHomeId);
            records.add(current);
        }
        return records;
    }

    private static String normalizeAnalysisLine(String rawLine) {
        String line = safeTrim(rawLine);
        if (TextUtils.isEmpty(line)) {
            return "";
        }
        if (line.startsWith("```")) {
            return "";
        }
        if (line.startsWith("-")) {
            line = safeTrim(line.substring(1));
        } else if (line.startsWith("*")) {
            line = safeTrim(line.substring(1));
        }
        if (line.matches("^\\d+[\\.)].*")) {
            line = safeTrim(line.replaceFirst("^\\d+[\\.)]\\s*", ""));
        }
        return line;
    }

    private static boolean matchesFieldKey(String line, String keyWithMeta) {
        String safeLine = safeTrim(line);
        String safeKey = safeTrim(keyWithMeta);
        if (TextUtils.isEmpty(safeLine) || TextUtils.isEmpty(safeKey)) {
            return false;
        }
        if (safeLine.startsWith(safeKey)) {
            return true;
        }
        String plainKey = extractPlainFieldKey(safeKey);
        return !TextUtils.isEmpty(plainKey) && safeLine.startsWith(plainKey);
    }

    private static String extractPlainFieldKey(String keyWithMeta) {
        String key = safeTrim(keyWithMeta);
        if (TextUtils.isEmpty(key)) {
            return "";
        }
        int idxCn = key.indexOf('（');
        int idxEn = key.indexOf('(');
        int idx;
        if (idxCn >= 0 && idxEn >= 0) {
            idx = Math.min(idxCn, idxEn);
        } else if (idxCn >= 0) {
            idx = idxCn;
        } else {
            idx = idxEn;
        }
        if (idx <= 0) {
            return key;
        }
        return safeTrim(key.substring(0, idx));
    }

    private static List<AnalysisRecord> sanitizeAnalysisRecords(List<AnalysisRecord> records) {
        if (records == null || records.isEmpty()) {
            return Collections.emptyList();
        }
        ArrayList<AnalysisRecord> out = new ArrayList<AnalysisRecord>();
        for (AnalysisRecord record : records) {
            if (record == null) {
                continue;
            }
            if (isEffectivelyEmptyRecord(record)) {
                continue;
            }
            out.add(record);
        }
        return out;
    }

    private static boolean isEffectivelyEmptyRecord(AnalysisRecord record) {
        if (record == null) {
            return true;
        }
        String userName = safeTrim(record.userName);
        String userId = safeTrim(record.userId);
        String level = safeTrim(record.level);
        String consume = safeTrim(record.consumeData);
        String accuracy = safeTrim(record.accuracy);
        String upName = safeTrim(record.targetName);
        String upId = safeTrim(record.targetId);
        String totalData = safeTrim(record.totalData);

        boolean allCoreEmpty = TextUtils.isEmpty(userName)
                && TextUtils.isEmpty(userId)
                && TextUtils.isEmpty(level)
                && TextUtils.isEmpty(consume)
                && TextUtils.isEmpty(accuracy)
                && TextUtils.isEmpty(upName)
                && TextUtils.isEmpty(upId)
                && TextUtils.isEmpty(totalData);
        if (allCoreEmpty) {
            return true;
        }
        boolean hasIdentity = !TextUtils.isEmpty(userName)
                || !TextUtils.isEmpty(userId)
                || !TextUtils.isEmpty(upName)
                || !TextUtils.isEmpty(upId);
        if (!hasIdentity) {
            return true;
        }
        return "RAW_REPLY".equalsIgnoreCase(userName) && TextUtils.isEmpty(consume);
    }

    private static void enrichAnalysisRecordsFromCollectedData(
            List<AnalysisRecord> records,
            RoomRoundData current,
            RoomRoundData previous
    ) {
        if (records == null || records.isEmpty() || current == null) {
            return;
        }
        Map<String, ContributionData> currentById = new HashMap<String, ContributionData>();
        Map<String, ContributionData> currentByName = new HashMap<String, ContributionData>();
        Map<String, ContributionData> previousById = new HashMap<String, ContributionData>();
        Map<String, ContributionData> previousByName = new HashMap<String, ContributionData>();
        indexContributionRows(current == null ? null : current.contributionRows, currentById, currentByName);
        indexContributionRows(previous == null ? null : previous.contributionRows, previousById, previousByName);
        for (AnalysisRecord record : records) {
            if (record == null) {
                continue;
            }
            String userId = safeTrim(record.userId);
            String userName = safeTrim(record.userName);
            ContributionData cur = findContribution(userId, userName, currentById, currentByName);
            ContributionData prev = findContribution(userId, userName, previousById, previousByName);
            long currentValue = cur == null ? -1L : cur.dataValue;
            long previousValue = prev == null ? -1L : prev.dataValue;
            long delta = (currentValue >= 0L && previousValue >= 0L)
                    ? Math.max(0L, currentValue - previousValue)
                    : -1L;

            if (TextUtils.isEmpty(safeTrim(record.totalData))) {
                if (currentValue >= 0L) {
                    record.totalData = String.valueOf(currentValue);
                } else if (cur != null && !TextUtils.isEmpty(safeTrim(cur.dataRaw))) {
                    record.totalData = safeTrim(cur.dataRaw);
                }
            }

            String consume = safeTrim(record.consumeData);
            if (TextUtils.isEmpty(consume)) {
                if (delta > 0L) {
                    record.consumeData = String.valueOf(delta);
                } else if (currentValue >= 0L) {
                    record.consumeData = String.valueOf(currentValue);
                } else if (!TextUtils.isEmpty(safeTrim(record.totalData))) {
                    record.consumeData = safeTrim(record.totalData);
                }
                continue;
            }
            if (isEstimateOnlyText(consume)) {
                if (delta > 0L) {
                    record.consumeData = consume + ":" + delta;
                } else if (currentValue >= 0L) {
                    record.consumeData = consume + ":" + currentValue;
                } else if (!TextUtils.isEmpty(safeTrim(record.totalData))) {
                    record.consumeData = consume + ":" + safeTrim(record.totalData);
                }
            }
        }
    }

    private static void indexContributionRows(
            List<ContributionData> rows,
            Map<String, ContributionData> byId,
            Map<String, ContributionData> byName
    ) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        for (ContributionData row : rows) {
            if (row == null) {
                continue;
            }
            String id = safeTrim(row.userId);
            String name = safeTrim(row.userName);
            if (!TextUtils.isEmpty(id) && !byId.containsKey(id)) {
                byId.put(id, row);
            }
            if (!TextUtils.isEmpty(name) && !byName.containsKey(name)) {
                byName.put(name, row);
            }
        }
    }

    private static ContributionData findContribution(
            String userId,
            String userName,
            Map<String, ContributionData> byId,
            Map<String, ContributionData> byName
    ) {
        String id = safeTrim(userId);
        if (!TextUtils.isEmpty(id)) {
            ContributionData hit = byId.get(id);
            if (hit != null) {
                return hit;
            }
        }
        String name = safeTrim(userName);
        if (!TextUtils.isEmpty(name)) {
            ContributionData hit = byName.get(name);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private static boolean isEstimateOnlyText(String text) {
        String value = safeTrim(text).toLowerCase(Locale.US);
        if (TextUtils.isEmpty(value)) {
            return false;
        }
        if ("估算".equals(value) || "estimated".equals(value) || "estimate".equals(value)) {
            return true;
        }
        return value.startsWith("估算(") && value.endsWith(")");
    }

    private static String parseLineValue(String line) {
        if (TextUtils.isEmpty(line)) {
            return "";
        }
        int idx = line.indexOf('：');
        if (idx < 0) {
            idx = line.indexOf(':');
        }
        if (idx < 0 || idx + 1 >= line.length()) {
            return "";
        }
        return safeTrim(line.substring(idx + 1));
    }

    private static void appendAnalysisResult(
            Context context,
            String homeId,
            int currentRound,
            int previousRound,
            List<AnalysisRecord> records,
            String rawAiReply
    ) {
        File file = resolveAnalysisResultFile(context);
        if (file == null) {
            log(context, "append analyze result failed: file null");
            return;
        }
        synchronized (RESULT_FILE_LOCK) {
            ensureFile(file);
            StringBuilder sb = new StringBuilder();
            sb.append("# analyze_at=")
                    .append(formatTs(System.currentTimeMillis()))
                    .append(" homeid=")
                    .append(safeTrim(homeId))
                    .append(" current_round=")
                    .append(Math.max(0, currentRound))
                    .append(" previous_round=")
                    .append(Math.max(0, previousRound))
                    .append('\n');
            if (records != null && !records.isEmpty()) {
                for (AnalysisRecord record : records) {
                    if (record == null) {
                        continue;
                    }
                    sb.append("----\n");
                    sb.append(KEY_UESE_NAME).append("：").append(safeTrim(record.userName)).append('\n');
                    sb.append(KEY_UESE_ID).append("：").append(safeTrim(record.userId)).append('\n');
                    sb.append(KEY_LEVEL).append("：").append(safeTrim(record.level)).append('\n');
                    sb.append(KEY_CONSUME).append("：").append(safeTrim(record.consumeData)).append('\n');
                    sb.append(KEY_ACCURACY).append("：").append(safeTrim(record.accuracy)).append('\n');
                    sb.append(KEY_UP_NAME).append("：").append(safeTrim(record.targetName)).append('\n');
                    sb.append(KEY_UP_ID).append("：").append(safeTrim(record.targetId)).append('\n');
                    sb.append(KEY_HOME_ID).append("：").append(safeTrim(record.homeId)).append('\n');
                    sb.append(KEY_TOTAL_DATA).append("：").append(safeTrim(record.totalData)).append('\n');
                    sb.append("----\n");
                }
            } else {
                sb.append("----\n");
                sb.append("raw_reply：").append(safeTrim(rawAiReply)).append('\n');
                sb.append("----\n");
            }
            sb.append('\n');
            writeText(file, sb.toString(), true);
            log(context, "analyze result appended: file=" + file.getAbsolutePath()
                    + " homeId=" + safeTrim(homeId)
                    + " currentRound=" + Math.max(0, currentRound)
                    + " previousRound=" + Math.max(0, previousRound)
                    + " records=" + (records == null ? 0 : records.size())
                    + " bytes=" + sb.toString().getBytes(StandardCharsets.UTF_8).length);
        }
    }

    private static void dispatchFeishuWebhook(
            Context context,
            String homeId,
            int currentRound,
            int previousRound,
            List<AnalysisRecord> records,
            String rawAiReply,
            RuntimeConfigSnapshot runtimeConfig
    ) {
        RuntimeConfigSnapshot activeConfig = runtimeConfig == null
                ? readRuntimeConfig(context)
                : runtimeConfig;
        boolean feishuEnabled = activeConfig != null && activeConfig.feishuPushEnabled;
        if (!feishuEnabled) {
            log(context, "feishu push skipped: switch_disabled source="
                    + (activeConfig == null ? "unknown" : activeConfig.source));
            return;
        }
        String webhookUrl = activeConfig == null ? "" : safeTrim(activeConfig.feishuWebhookUrl);
        String signSecret = activeConfig == null ? "" : safeTrim(activeConfig.feishuSignSecret);
        if (TextUtils.isEmpty(webhookUrl)) {
            log(context, "feishu push skipped: webhook_empty source="
                    + (activeConfig == null ? "unknown" : activeConfig.source));
            return;
        }
        ArrayList<AnalysisRecord> safeRecords = new ArrayList<AnalysisRecord>();
        if (records != null) {
            for (AnalysisRecord record : records) {
                if (record == null || !record.hasAnyField()) {
                    continue;
                }
                safeRecords.add(record);
            }
        }
        if (safeRecords.isEmpty()) {
            log(context, "feishu push skipped: no_valid_records homeId=" + safeTrim(homeId)
                    + " rawPreview=" + truncate(safeTrim(rawAiReply).replace('\n', ' '), 200));
            return;
        }
        int total = safeRecords.size();
        int successCount = 0;
        log(context, "feishu push start: homeId=" + safeTrim(homeId)
                + " currentRound=" + currentRound
                + " previousRound=" + previousRound
                + " recordCount=" + total
                + " source=" + (activeConfig == null ? "unknown" : activeConfig.source)
                + " signEnabled=" + (!TextUtils.isEmpty(signSecret)));
        for (int i = 0; i < safeRecords.size(); i++) {
            AnalysisRecord record = safeRecords.get(i);
            String text = buildFeishuMessage(
                    homeId,
                    currentRound,
                    previousRound,
                    i + 1,
                    total,
                    record
            );
            FeishuWebhookSender.SendResult result = FeishuWebhookSender.sendText(
                    context,
                    webhookUrl,
                    signSecret,
                    text
            );
            if (result != null && result.success) {
                successCount++;
                continue;
            }
            String detail = result == null ? "result_null" : safeTrim(result.detail);
            int status = result == null ? -1 : result.statusCode;
            log(context, "feishu push item failed: homeId=" + safeTrim(homeId)
                    + " index=" + (i + 1) + "/" + total
                    + " status=" + status
                    + " detail=" + detail);
        }
        log(context, "feishu push finished: homeId=" + safeTrim(homeId)
                + " successCount=" + successCount
                + " total=" + total
                + " allSuccess=" + (successCount == total));
    }

    private static String buildFeishuMessage(
            String homeId,
            int currentRound,
            int previousRound,
            int index,
            int total,
            AnalysisRecord record
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append(KEY_UESE_NAME).append("：").append(safeTrim(record.userName)).append('\n');
        sb.append(KEY_UESE_ID).append("：").append(safeTrim(record.userId)).append('\n');
        sb.append(KEY_LEVEL).append("：").append(safeTrim(record.level)).append('\n');
        sb.append(KEY_CONSUME).append("：").append(safeTrim(record.consumeData)).append('\n');
        sb.append(KEY_ACCURACY).append("：").append(safeTrim(record.accuracy)).append('\n');
        sb.append(KEY_UP_NAME).append("：").append(safeTrim(record.targetName)).append('\n');
        sb.append(KEY_UP_ID).append("：").append(safeTrim(record.targetId)).append('\n');
        sb.append(KEY_HOME_ID).append("：").append(safeTrim(record.homeId)).append('\n');
        sb.append(KEY_TOTAL_DATA).append("：").append(safeTrim(record.totalData));
        return sb.toString();
    }

    private static File resolveAnalysisResultFile(Context context) {
        File dir = resolveAnalysisDir(context);
        if (dir == null) {
            return null;
        }
        String dayToken;
        synchronized (DAY_FORMAT) {
            dayToken = DAY_FORMAT.format(new Date());
        }
        return new File(
                dir,
                UiComponentConfig.AI_ANALYSIS_RESULT_PREFIX
                        + dayToken
                        + UiComponentConfig.AI_ANALYSIS_RESULT_SUFFIX
        );
    }

    private static String ensurePromptAndRead(Context context) {
        String text = readModulePromptAsset(context);
        if (TextUtils.isEmpty(text)) {
            return safeTrim(buildDefaultPromptTemplate());
        }
        return safeTrim(text);
    }

    private static String readModulePromptAsset(Context context) {
        if (context == null) {
            return "";
        }
        InputStream in = null;
        try {
            Context moduleContext = context.createPackageContext(
                    ModuleSettings.MODULE_PACKAGE,
                    Context.CONTEXT_IGNORE_SECURITY
            );
            in = moduleContext.getAssets().open(UiComponentConfig.AI_PROMPT_ASSET_NAME);
            return readAllText(in);
        } catch (Throwable ignore) {
            closeQuietly(in);
            in = null;
        }
        try {
            in = context.getAssets().open(UiComponentConfig.AI_PROMPT_ASSET_NAME);
            return readAllText(in);
        } catch (Throwable ignore) {
            return "";
        } finally {
            closeQuietly(in);
        }
    }

    private static String buildDefaultPromptTemplate() {
        return "你是直播间消费对象推断引擎。\n"
                + "你会收到同一直播间的贡献榜与魅力榜当前轮/上一轮数据。\n"
                + "核心关系：贡献榜记录消费者累计消费，魅力榜记录获利者累计获利；二者同轮增量应近似守恒。\n"
                + "任务：对每个贡献榜正向差值用户，推断其消费对象并分配消费金额。\n"
                + "计算规则：\n"
                + "1. 差值=当前轮Data-上一轮Data，只关注差值>0。\n"
                + "2. 支持一对多/多对一分配；同一消费者多条记录金额之和应尽量等于其贡献榜差值。\n"
                + "3. 全体分配金额总和应尽量逼近魅力榜正向总增量。\n"
                + "4. 消费数据必须为整数数值，禁止输出估算/约/大概等文字。\n"
                + "5. 消费计算准确度必须是百分比格式（如100%、86%），禁止估值/不确定等文字。\n"
                + "6. 输出字段名不要包含括号中的英文标识；不要输出解释段落/JSON/Markdown。\n"
                + "7. 仅当本轮无任何可推断的正向差值时，输出 NO_VALID_RECORDS。\n"
                + "输出格式：\n"
                + "----\n"
                + "用户昵称：\n"
                + "用户ID：\n"
                + "用户等级：\n"
                + "消费数据：\n"
                + "消费计算准确度：\n"
                + "消费对象昵称：\n"
                + "消费对象ID：\n"
                + "直播间ID：\n"
                + "在直播间总消费数据：\n"
                + "----\n";
    }

    private static File resolveAnalysisDir(Context context) {
        File[] candidates = new File[] {
                new File(UiComponentConfig.AI_ANALYSIS_EXTERNAL_DIR),
                buildExternalContextDir(context),
                buildInternalContextDir(context)
        };
        for (File candidate : candidates) {
            if (isWritableDir(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static File buildExternalContextDir(Context context) {
        if (context == null) {
            return null;
        }
        try {
            File base = context.getExternalFilesDir(null);
            if (base == null) {
                return null;
            }
            return new File(base, UiComponentConfig.AI_ANALYSIS_FALLBACK_DIR_NAME);
        } catch (Throwable ignore) {
            return null;
        }
    }

    private static File buildInternalContextDir(Context context) {
        if (context == null) {
            return null;
        }
        try {
            File base = context.getFilesDir();
            if (base == null) {
                return null;
            }
            return new File(base, UiComponentConfig.AI_ANALYSIS_FALLBACK_DIR_NAME);
        } catch (Throwable ignore) {
            return null;
        }
    }

    private static boolean isWritableDir(File dir) {
        if (dir == null) {
            return false;
        }
        try {
            if (dir.exists()) {
                if (!dir.isDirectory()) {
                    return false;
                }
            } else if (!dir.mkdirs()) {
                return false;
            }
            File probe = new File(dir, ".probe");
            FileOutputStream fos = new FileOutputStream(probe, false);
            fos.write('1');
            fos.flush();
            closeQuietly(fos);
            probe.delete();
            return true;
        } catch (Throwable ignore) {
            return false;
        }
    }

    private static String readText(File file) {
        if (file == null || !file.exists()) {
            return "";
        }
        FileInputStream fis = null;
        ByteArrayOutputStream bos = null;
        try {
            fis = new FileInputStream(file);
            bos = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int len;
            while ((len = fis.read(buffer)) != -1) {
                bos.write(buffer, 0, len);
            }
            return bos.toString(StandardCharsets.UTF_8.name());
        } catch (Throwable ignore) {
            return "";
        } finally {
            closeQuietly(fis);
            closeQuietly(bos);
        }
    }

    private static String readAllText(InputStream inputStream) {
        if (inputStream == null) {
            return "";
        }
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (Throwable ignore) {
            return "";
        } finally {
            closeQuietly(reader);
        }
    }

    private static void ensureFile(File file) {
        if (file == null) {
            return;
        }
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            if (!file.exists()) {
                file.createNewFile();
            }
        } catch (Throwable ignore) {
        }
    }

    private static void writeText(File file, String text, boolean append) {
        if (file == null) {
            return;
        }
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(file, append);
            fos.write((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            fos.flush();
        } catch (Throwable ignore) {
        } finally {
            closeQuietly(fos);
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Throwable ignore) {
        }
    }

    private static String safeTrim(String value) {
        if (value == null) {
            return "";
        }
        return value.trim();
    }

    private static String formatTs(long ts) {
        synchronized (TS_FORMAT) {
            return TS_FORMAT.format(new Date(Math.max(0L, ts)));
        }
    }

    private static String truncate(String value, int maxLen) {
        String safe = safeTrim(value);
        if (safe.length() <= maxLen) {
            return safe;
        }
        return safe.substring(0, Math.max(0, maxLen)) + "...";
    }

    private static void logLargeContent(
            Context context,
            String stage,
            String traceId,
            String content
    ) {
        String safeStage = safeTrim(stage);
        String safeTraceId = safeTrim(traceId);
        String text = content == null ? "" : content;
        if (TextUtils.isEmpty(text)) {
            log(context, safeStage + " empty: traceId=" + safeTraceId);
            return;
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\\n");
        int lineNo = 0;
        for (String line : lines) {
            lineNo++;
            String safeLine = line == null ? "" : line;
            if (safeLine.length() <= 700) {
                log(context, safeStage + " line=" + lineNo
                        + " traceId=" + safeTraceId
                        + " content=" + safeLine);
                continue;
            }
            int part = 0;
            int start = 0;
            while (start < safeLine.length()) {
                int end = Math.min(safeLine.length(), start + 700);
                part++;
                log(context, safeStage + " line=" + lineNo
                        + " part=" + part
                        + " traceId=" + safeTraceId
                        + " content=" + safeLine.substring(start, end));
                start = end;
            }
        }
    }

    private static String buildTraceId(
            String homeId,
            long enterTimeMs,
            String contributionCsvPath,
            String charmCsvPath
    ) {
        String safeHome = safeTrim(homeId);
        String contribName = "";
        String charmName = "";
        try {
            contribName = new File(safeTrim(contributionCsvPath)).getName();
        } catch (Throwable ignore) {
        }
        try {
            charmName = new File(safeTrim(charmCsvPath)).getName();
        } catch (Throwable ignore) {
        }
        return safeHome + "|" + Math.max(0L, enterTimeMs) + "|" + contribName + "|" + charmName;
    }

    private static String getExecutorStats() {
        try {
            if (EXECUTOR instanceof ThreadPoolExecutor) {
                ThreadPoolExecutor pool = (ThreadPoolExecutor) EXECUTOR;
                return "active=" + pool.getActiveCount()
                        + ",queued=" + pool.getQueue().size()
                        + ",poolSize=" + pool.getPoolSize();
            }
        } catch (Throwable ignore) {
        }
        return "active=unknown,queued=unknown,poolSize=unknown";
    }

    private static void log(Context context, String msg) {
        String line = TAG + " " + safeTrim(msg);
        Log.i(LOGCAT_TAG, line);
        logToXposedIfAvailable(line);
        ModuleRunFileLogger.appendLine(context, line);
    }

    private static void logToXposedIfAvailable(String line) {
        try {
            Class<?> clazz = Class.forName("de.robv.android.xposed.XposedBridge");
            Method method = clazz.getMethod("log", String.class);
            method.invoke(null, line);
        } catch (Throwable ignore) {
        }
    }

    private static final class CsvRoundRef {
        final String dayToken;
        final int roundIndex;

        CsvRoundRef(String dayToken, int roundIndex) {
            this.dayToken = safeTrim(dayToken);
            this.roundIndex = Math.max(1, roundIndex);
        }
    }

    private static final class AiConfig {
        final String url;
        final String apiKey;
        final String model;
        final boolean valid;
        final String invalidReason;

        AiConfig(String url, String apiKey, String model, boolean valid, String invalidReason) {
            this.url = safeTrim(url);
            this.apiKey = safeTrim(apiKey);
            this.model = safeTrim(model);
            this.valid = valid;
            this.invalidReason = safeTrim(invalidReason);
        }

        static AiConfig invalid(String reason) {
            return new AiConfig("", "", "", false, reason);
        }
    }

    private static final class HttpCallResult {
        final boolean success;
        final String detail;
        final int statusCode;
        final String rawBody;
        final String messageContent;

        HttpCallResult(boolean success, String detail, int statusCode, String rawBody, String messageContent) {
            this.success = success;
            this.detail = safeTrim(detail);
            this.statusCode = statusCode;
            this.rawBody = safeTrim(rawBody);
            this.messageContent = safeTrim(messageContent);
        }

        static HttpCallResult ok(int statusCode, String rawBody, String messageContent) {
            return new HttpCallResult(true, "ok", statusCode, rawBody, messageContent);
        }

        static HttpCallResult fail(String detail, String rawBody) {
            return new HttpCallResult(false, detail, -1, rawBody, "");
        }

        static HttpCallResult fail(int statusCode, String detail, String rawBody) {
            return new HttpCallResult(false, detail, statusCode, rawBody, "");
        }
    }

    private static final class PreviousRoundSearchResult {
        final boolean found;
        final RoomRoundData data;
        final int scannedRounds;
        final int existingPairRounds;

        PreviousRoundSearchResult(
                boolean found,
                RoomRoundData data,
                int scannedRounds,
                int existingPairRounds
        ) {
            this.found = found;
            this.data = data;
            this.scannedRounds = Math.max(0, scannedRounds);
            this.existingPairRounds = Math.max(0, existingPairRounds);
        }

        static PreviousRoundSearchResult found(
                RoomRoundData data,
                int scannedRounds,
                int existingPairRounds
        ) {
            return new PreviousRoundSearchResult(true, data, scannedRounds, existingPairRounds);
        }

        static PreviousRoundSearchResult notFound(int scannedRounds, int existingPairRounds) {
            return new PreviousRoundSearchResult(false, null, scannedRounds, existingPairRounds);
        }
    }

    private static final class RoomRoundData {
        final int roundIndex;
        final List<ContributionData> contributionRows;
        final List<CharmData> charmRows;

        RoomRoundData(int roundIndex, List<ContributionData> contributionRows, List<CharmData> charmRows) {
            this.roundIndex = Math.max(0, roundIndex);
            this.contributionRows = contributionRows == null
                    ? Collections.<ContributionData>emptyList()
                    : contributionRows;
            this.charmRows = charmRows == null
                    ? Collections.<CharmData>emptyList()
                    : charmRows;
        }

        boolean isUsable() {
            return !contributionRows.isEmpty() && !charmRows.isEmpty();
        }
    }

    private static final class ContributionData {
        final String homeId;
        final String userId;
        final String userName;
        final String level;
        final String dataRaw;
        final long dataValue;

        ContributionData(String homeId, String userId, String userName, String level, String dataRaw) {
            this.homeId = safeTrim(homeId);
            this.userId = safeTrim(userId);
            this.userName = safeTrim(userName);
            this.level = safeTrim(level);
            this.dataRaw = safeTrim(dataRaw);
            this.dataValue = parseDataValue(dataRaw);
        }

        String identityKey() {
            if (!TextUtils.isEmpty(userId)) {
                return "id:" + userId;
            }
            return "name:" + userName;
        }
    }

    private static final class CharmData {
        final String homeId;
        final String upId;
        final String upName;
        final String dataRaw;
        final long dataValue;

        CharmData(String homeId, String upId, String upName, String dataRaw) {
            this.homeId = safeTrim(homeId);
            this.upId = safeTrim(upId);
            this.upName = safeTrim(upName);
            this.dataRaw = safeTrim(dataRaw);
            this.dataValue = parseDataValue(dataRaw);
        }

        String identityKey() {
            if (!TextUtils.isEmpty(upId)) {
                return "id:" + upId;
            }
            return "name:" + upName;
        }
    }

    private static final class AnalysisRecord {
        String userName = "";
        String userId = "";
        String level = "";
        String consumeData = "";
        String accuracy = "";
        String targetName = "";
        String targetId = "";
        String homeId = "";
        String totalData = "";

        boolean hasAnyField() {
            return !TextUtils.isEmpty(userName)
                    || !TextUtils.isEmpty(userId)
                    || !TextUtils.isEmpty(level)
                    || !TextUtils.isEmpty(consumeData)
                    || !TextUtils.isEmpty(accuracy)
                    || !TextUtils.isEmpty(targetName)
                    || !TextUtils.isEmpty(targetId)
                    || !TextUtils.isEmpty(homeId)
                    || !TextUtils.isEmpty(totalData);
        }

        void fillDefaultHomeId(String fallbackHomeId) {
            if (TextUtils.isEmpty(homeId)) {
                homeId = safeTrim(fallbackHomeId);
            }
        }

        static AnalysisRecord fromRaw(String raw, String fallbackHomeId) {
            AnalysisRecord record = new AnalysisRecord();
            record.userName = "RAW_REPLY";
            record.homeId = safeTrim(fallbackHomeId);
            record.consumeData = truncate(safeTrim(raw), 500);
            record.accuracy = "0%";
            return record;
        }
    }
}
