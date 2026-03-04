package com.oodbye.looklspmodule;

import android.content.Context;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class FeishuWebhookSender {
    private static final String TAG = "[LOOKFeishuPush]";
    private static final String LOGCAT_TAG = "LOOKFeishuPush";
    private static final int FEISHU_SIGN_ERROR_CODE = 19021;

    private FeishuWebhookSender() {
    }

    private enum SignProfile {
        FEISHU_DOC_SECONDS_EMPTY("feishu_doc_sec_empty"),
        ALT_SECRET_KEY_SECONDS_DATA("alt_secret_key_sec_data"),
        FEISHU_DOC_MILLIS_EMPTY("feishu_doc_ms_empty");

        final String tag;

        SignProfile(String tag) {
            this.tag = tag;
        }
    }

    static final class SendResult {
        final boolean success;
        final String detail;
        final int statusCode;
        final String responseBody;

        SendResult(boolean success, String detail, int statusCode, String responseBody) {
            this.success = success;
            this.detail = detail == null ? "" : detail.trim();
            this.statusCode = statusCode;
            this.responseBody = responseBody == null ? "" : responseBody.trim();
        }

        static SendResult ok(int statusCode, String detail, String responseBody) {
            return new SendResult(true, detail, statusCode, responseBody);
        }

        static SendResult fail(int statusCode, String detail, String responseBody) {
            return new SendResult(false, detail, statusCode, responseBody);
        }
    }

    static SendResult sendText(
            Context context,
            String webhookUrl,
            String signSecret,
            String text
    ) {
        String url = safeTrim(webhookUrl);
        String message = safeTrim(text);
        if (TextUtils.isEmpty(url)) {
            return SendResult.fail(-1, "webhook_url_empty", "");
        }
        if (TextUtils.isEmpty(message)) {
            return SendResult.fail(-1, "message_empty", "");
        }
        String secret = safeTrim(signSecret);
        if (TextUtils.isEmpty(secret)) {
            return sendTextOnce(context, url, "", message, null);
        }
        SendResult lastResult = null;
        SignProfile[] profiles = new SignProfile[] {
                SignProfile.FEISHU_DOC_SECONDS_EMPTY,
                SignProfile.ALT_SECRET_KEY_SECONDS_DATA,
                SignProfile.FEISHU_DOC_MILLIS_EMPTY
        };
        for (int i = 0; i < profiles.length; i++) {
            SignProfile profile = profiles[i];
            SendResult result = sendTextOnce(context, url, secret, message, profile);
            lastResult = result;
            if (result != null && result.success) {
                return result;
            }
            boolean signFail = isFeishuSignError(result);
            if (!signFail) {
                return result;
            }
            if (i < profiles.length - 1) {
                log(context, "sign profile retry: from=" + profile.tag
                        + " next=" + profiles[i + 1].tag
                        + " status=" + (result == null ? -1 : result.statusCode));
            }
        }
        return lastResult == null ? SendResult.fail(-1, "send_failed_unknown", "") : lastResult;
    }

    private static SendResult sendTextOnce(
            Context context,
            String webhookUrl,
            String signSecret,
            String text,
            SignProfile signProfile
    ) {
        HttpURLConnection connection = null;
        OutputStream os = null;
        InputStream is = null;
        int statusCode = -1;
        String body = "";
        try {
            JSONObject requestJson = buildRequestBody(text, safeTrim(signSecret), signProfile);
            String tsForLog = safeTrim(requestJson.optString("timestamp"));
            String signForLog = safeTrim(requestJson.optString("sign"));
            log(context, "send request: profile=" + (signProfile == null ? "none" : signProfile.tag)
                    + " webhook=" + maskWebhook(webhookUrl)
                    + " timestamp=" + (TextUtils.isEmpty(tsForLog) ? "none" : tsForLog)
                    + " signLen=" + (TextUtils.isEmpty(signForLog) ? 0 : signForLog.length())
                    + " signPrefix=" + (TextUtils.isEmpty(signForLog) ? "" : truncate(signForLog, 12)));
            byte[] payload = requestJson.toString().getBytes(StandardCharsets.UTF_8);
            connection = (HttpURLConnection) new URL(webhookUrl).openConnection();
            connection.setConnectTimeout(UiComponentConfig.AI_HTTP_CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(UiComponentConfig.AI_HTTP_READ_TIMEOUT_MS);
            connection.setRequestMethod("POST");
            connection.setDoInput(true);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "LOOK-LSP-Feishu/1.0");
            os = connection.getOutputStream();
            os.write(payload);
            os.flush();
            statusCode = connection.getResponseCode();
            String serverDateHeader = safeTrim(connection.getHeaderField("Date"));
            long serverDateMs = parseHttpDateMillis(serverDateHeader);
            if (serverDateMs > 0L) {
                long skewSec = (System.currentTimeMillis() - serverDateMs) / 1000L;
                log(context, "response date: profile=" + (signProfile == null ? "none" : signProfile.tag)
                        + " serverDate=" + serverDateHeader
                        + " skewSec=" + skewSec);
            }
            if (statusCode >= 200 && statusCode < 300) {
                is = connection.getInputStream();
            } else {
                is = connection.getErrorStream();
            }
            body = readAllText(is);
            if (statusCode < 200 || statusCode >= 300) {
                log(context, "send failed: http=" + statusCode
                        + " profile=" + (signProfile == null ? "none" : signProfile.tag)
                        + " body=" + truncate(body, 220));
                return SendResult.fail(statusCode, "http_" + statusCode, body);
            }
            String parseDetail = parseFeishuResponseDetail(body);
            boolean ok = isFeishuResponseSuccess(body);
            if (!ok) {
                log(context, "send failed: feishu_not_ok"
                        + " profile=" + (signProfile == null ? "none" : signProfile.tag)
                        + " body=" + truncate(body, 220));
                return SendResult.fail(statusCode, parseDetail, body);
            }
            log(context, "send ok: http=" + statusCode
                    + " profile=" + (signProfile == null ? "none" : signProfile.tag)
                    + " detail=" + parseDetail);
            return SendResult.ok(statusCode, parseDetail, body);
        } catch (Throwable e) {
            String detail = "request_exception:" + safeTrim(String.valueOf(e));
            log(context, "send exception: " + detail
                    + " profile=" + (signProfile == null ? "none" : signProfile.tag));
            return SendResult.fail(statusCode, detail, body);
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

    private static JSONObject buildRequestBody(String text, String signSecret, SignProfile profile) throws Exception {
        JSONObject body = new JSONObject();
        body.put("msg_type", "text");
        JSONObject content = new JSONObject();
        content.put("text", text);
        body.put("content", content);
        if (!TextUtils.isEmpty(signSecret)) {
            SignProfile actualProfile = profile == null
                    ? SignProfile.FEISHU_DOC_SECONDS_EMPTY : profile;
            boolean millisTimestamp = actualProfile == SignProfile.FEISHU_DOC_MILLIS_EMPTY;
            String timestamp = millisTimestamp
                    ? String.valueOf(System.currentTimeMillis())
                    : String.valueOf(System.currentTimeMillis() / 1000L);
            body.put("timestamp", timestamp);
            body.put("sign", buildSign(timestamp, signSecret, actualProfile));
        }
        return body;
    }

    private static String buildSign(String timestamp, String secret, SignProfile profile) throws Exception {
        String stringToSign = timestamp + "\n" + secret;
        Mac mac = Mac.getInstance("HmacSHA256");
        byte[] signData;
        if (profile == SignProfile.ALT_SECRET_KEY_SECONDS_DATA) {
            SecretKeySpec keySpec = new SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"
            );
            mac.init(keySpec);
            signData = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
        } else {
            SecretKeySpec keySpec = new SecretKeySpec(
                    stringToSign.getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"
            );
            mac.init(keySpec);
            signData = mac.doFinal(new byte[0]);
        }
        return Base64.encodeToString(signData, Base64.NO_WRAP);
    }

    private static boolean isFeishuResponseSuccess(String rawBody) {
        String body = safeTrim(rawBody);
        if (TextUtils.isEmpty(body)) {
            return true;
        }
        try {
            JSONObject json = new JSONObject(body);
            if (json.has("code")) {
                return json.optInt("code", -1) == 0;
            }
            if (json.has("StatusCode")) {
                return json.optInt("StatusCode", -1) == 0;
            }
        } catch (Throwable ignore) {
            return false;
        }
        return true;
    }

    private static String parseFeishuResponseDetail(String rawBody) {
        String body = safeTrim(rawBody);
        if (TextUtils.isEmpty(body)) {
            return "ok";
        }
        try {
            JSONObject json = new JSONObject(body);
            int code = json.has("code") ? json.optInt("code", -1) : -1;
            int statusCode = json.has("StatusCode") ? json.optInt("StatusCode", -1) : -1;
            String msg = safeTrim(json.optString("msg"));
            if (!TextUtils.isEmpty(msg)) {
                if (code >= 0) {
                    return "code=" + code + " " + msg;
                }
                return msg;
            }
            String statusMsg = safeTrim(json.optString("StatusMessage"));
            if (!TextUtils.isEmpty(statusMsg)) {
                if (statusCode >= 0) {
                    return "StatusCode=" + statusCode + " " + statusMsg;
                }
                return statusMsg;
            }
            return truncate(body, 120);
        } catch (Throwable ignore) {
            return truncate(body, 120);
        }
    }

    private static boolean isFeishuSignError(SendResult result) {
        if (result == null) {
            return false;
        }
        int code = parseFeishuErrorCode(result.responseBody);
        if (code == FEISHU_SIGN_ERROR_CODE) {
            return true;
        }
        String detail = safeTrim(result.detail).toLowerCase();
        return detail.contains("sign match fail")
                || detail.contains("timestamp is not within one hour");
    }

    private static int parseFeishuErrorCode(String rawBody) {
        String body = safeTrim(rawBody);
        if (TextUtils.isEmpty(body)) {
            return -1;
        }
        try {
            JSONObject json = new JSONObject(body);
            if (json.has("code")) {
                return json.optInt("code", -1);
            }
            if (json.has("StatusCode")) {
                return json.optInt("StatusCode", -1);
            }
        } catch (Throwable ignore) {
        }
        return -1;
    }

    private static String readAllText(InputStream is) {
        if (is == null) {
            return "";
        }
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
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

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Throwable ignore) {
        }
    }

    private static String truncate(String value, int maxLen) {
        String text = safeTrim(value);
        if (text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, Math.max(0, maxLen)) + "...";
    }

    private static String safeTrim(String value) {
        if (value == null) {
            return "";
        }
        return value.trim();
    }

    private static long parseHttpDateMillis(String headerValue) {
        String text = safeTrim(headerValue);
        if (TextUtils.isEmpty(text)) {
            return -1L;
        }
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US);
            sdf.setLenient(false);
            sdf.setTimeZone(TimeZone.getTimeZone("GMT"));
            return sdf.parse(text).getTime();
        } catch (Throwable ignore) {
            return -1L;
        }
    }

    private static void log(Context context, String message) {
        String line = TAG + " " + safeTrim(message);
        Log.i(LOGCAT_TAG, line);
        logToXposedIfAvailable(line);
        ModuleRunFileLogger.appendLine(context, line);
    }

    private static String maskWebhook(String url) {
        String text = safeTrim(url);
        if (TextUtils.isEmpty(text)) {
            return "";
        }
        if (text.length() <= 20) {
            return text;
        }
        return text.substring(0, 16) + "..." + text.substring(text.length() - 8);
    }

    private static void logToXposedIfAvailable(String line) {
        try {
            Class<?> clazz = Class.forName("de.robv.android.xposed.XposedBridge");
            Method method = clazz.getMethod("log", String.class);
            method.invoke(null, line);
        } catch (Throwable ignore) {
        }
    }
}
