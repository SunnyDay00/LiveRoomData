package com.oodbye.shuangyulspmodule;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * AI 分析器（保留框架，新数据格式分析逻辑待实现）。
 */
final class RankAiConsumptionAnalyzer {
    private static final String TAG = "SYLspModule";

    private RankAiConsumptionAnalyzer() {
    }

    // ─────────────────────── 测试结果 ───────────────────────
    static final class TestResult {
        final boolean success;
        final String message;

        TestResult(boolean success, String message) {
            this.success = success;
            this.message = message == null ? "" : message;
        }
    }

    /** 测试 AI 连接是否正常 */
    static TestResult testAiConnection() {
        String apiUrl = ModuleSettings.getAiApiUrl();
        String apiKey = ModuleSettings.getAiApiKey();
        String model = ModuleSettings.getAiModel();
        if (TextUtils.isEmpty(apiUrl)) {
            return new TestResult(false, "AI API URL 未配置");
        }
        if (TextUtils.isEmpty(apiKey)) {
            return new TestResult(false, "AI API Key 未配置");
        }
        if (TextUtils.isEmpty(model)) {
            return new TestResult(false, "AI 模型 未配置");
        }
        return new TestResult(true, "AI 配置已就绪（模型: " + model + "）");
    }

    /**
     * 执行 AI 分析（新数据格式）。
     * 当前为框架代码，分析逻辑待实现。
     */
    static void analyzeIfEnabled(Context context, String csvPath) {
        if (context == null) return;
        if (!ModuleSettings.isAiAnalysisEnabled()) {
            Log.i(TAG, "AI 分析未启用，跳过");
            return;
        }
        if (TextUtils.isEmpty(csvPath)) {
            Log.w(TAG, "CSV 路径为空，跳过 AI 分析");
            return;
        }
        Log.i(TAG, "AI 分析：框架就绪，数据文件=" + csvPath + " (分析逻辑待实现)");
        // TODO: 根据新的用户数据 CSV 格式实现 AI 分析
        // 可读取 CSV → 构造 prompt → 调用 AI API → 解析结果 → 推送飞书
    }

    /** 读取自定义 AI Prompt */
    static String loadCustomPrompt() {
        File file = new File(UiComponentConfig.AI_PROMPT_FILE_PATH);
        if (!file.exists() || !file.isFile()) return "";
        StringBuilder sb = new StringBuilder();
        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            reader.close();
        } catch (Throwable e) {
            Log.e(TAG, "读取 AI prompt 失败", e);
        }
        return sb.toString().trim();
    }

    /** 保存 AI 分析结果到文件 */
    static void saveResult(String result) {
        if (TextUtils.isEmpty(result)) return;
        try {
            File dir = new File(UiComponentConfig.AI_RESULT_EXTERNAL_DIR);
            if (!dir.exists()) dir.mkdirs();
            SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
            String fileName = "ai_result_" + fmt.format(new Date()) + ".txt";
            File file = new File(dir, fileName);
            java.io.FileOutputStream fos = new java.io.FileOutputStream(file);
            fos.write(result.getBytes(StandardCharsets.UTF_8));
            fos.flush();
            fos.close();
            Log.i(TAG, "AI 结果已保存: " + file.getAbsolutePath());
        } catch (Throwable e) {
            Log.e(TAG, "保存 AI 结果失败", e);
        }
    }
}
