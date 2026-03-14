package com.oodbye.shuangyulspmodule;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 飞书 Webhook 消息发送（支持签名）。
 */
final class FeishuWebhookSender {
    private static final String TAG = "SYLspModule";

    private FeishuWebhookSender() {
    }

    // ─────────────────────── 发送结果 ───────────────────────
    static final class SendResult {
        final boolean success;
        final String detail;

        SendResult(boolean success, String detail) {
            this.success = success;
            this.detail = detail == null ? "" : detail;
        }
    }

    // ─────────────────────── 公开 API ───────────────────────

    /**
     * 探测 Webhook 是否可用（不发送实际消息）。
     * 只发送签名字段，不带 msg_type，飞书会拒绝但不投递消息。
     * 通过错误码判断 URL 可达性和签名正确性。
     */
    static SendResult probeWebhook(Context context, String webhookUrl, String signSecret) {
        if (TextUtils.isEmpty(webhookUrl)) {
            return new SendResult(false, "Webhook URL 为空");
        }
        try {
            // 只发签名字段，不带 msg_type/content，飞书不会投递任何消息
            JSONObject body = new JSONObject();
            addSignIfNeeded(body, signSecret);
            SendResult result = doPost(webhookUrl, body.toString());

            String detail = result.detail;

            // 签名错误 → 明确报错
            if (detail.contains("sign match fail") || detail.contains("19021")) {
                return new SendResult(false, "签名校验失败，请检查签名密钥");
            }
            // token 无效（URL 不对）
            if (detail.contains("token is invalid") || detail.contains("19024")) {
                return new SendResult(false, "Webhook URL 无效");
            }

            // 其他业务错误（如 msg_type 缺失/param invalid）说明 URL 可达 + 签名正确
            if (detail.contains("msg_type") || detail.contains("param invalid")
                    || detail.contains("19001") || detail.contains("19002")
                    || detail.contains("19005")) {
                return new SendResult(true, "连接正常（无消息发送）");
            }

            // HTTP 2xx 且业务码 = 0（不太可能但兜底）
            if (result.success) {
                return new SendResult(true, "连接正常（无消息发送）");
            }

            return result;
        } catch (Throwable e) {
            return new SendResult(false, "异常: " + e.getMessage());
        }
    }

    /** 发送纯文本消息 */
    static SendResult sendText(String text) {
        String webhookUrl = ModuleSettings.getFeishuWebhookUrl();
        String signSecret = ModuleSettings.getFeishuSignSecret();
        if (TextUtils.isEmpty(webhookUrl)) {
            return new SendResult(false, "Webhook URL 未配置");
        }
        if (!ModuleSettings.isFeishuPushEnabled()) {
            return new SendResult(false, "飞书推送未开启");
        }
        return doSendText(webhookUrl, signSecret, text);
    }

    /** 发送带标题的富文本消息 */
    static SendResult sendPost(String title, String content) {
        String webhookUrl = ModuleSettings.getFeishuWebhookUrl();
        String signSecret = ModuleSettings.getFeishuSignSecret();
        if (TextUtils.isEmpty(webhookUrl)) {
            return new SendResult(false, "Webhook URL 未配置");
        }
        if (!ModuleSettings.isFeishuPushEnabled()) {
            return new SendResult(false, "飞书推送未开启");
        }
        return doSendPost(webhookUrl, signSecret, title, content);
    }

    // ─────────────────────── 发送实现 ───────────────────────

    static SendResult doSendText(String webhookUrl, String signSecret, String text) {
        try {
            JSONObject body = new JSONObject();
            body.put("msg_type", "text");
            JSONObject contentObj = new JSONObject();
            contentObj.put("text", text);
            body.put("content", contentObj);
            addSignIfNeeded(body, signSecret);
            return doPost(webhookUrl, body.toString());
        } catch (Throwable e) {
            return new SendResult(false, "构造消息失败: " + e.getMessage());
        }
    }

    private static SendResult doSendPost(String webhookUrl, String signSecret,
                                          String title, String content) {
        try {
            JSONObject body = new JSONObject();
            body.put("msg_type", "post");
            JSONObject postObj = new JSONObject();
            JSONObject zhCn = new JSONObject();
            zhCn.put("title", title);
            org.json.JSONArray contentArray = new org.json.JSONArray();
            org.json.JSONArray lineArray = new org.json.JSONArray();
            JSONObject textNode = new JSONObject();
            textNode.put("tag", "text");
            textNode.put("text", content);
            lineArray.put(textNode);
            contentArray.put(lineArray);
            zhCn.put("content", contentArray);
            postObj.put("zh_cn", zhCn);
            body.put("content", new JSONObject().put("post", postObj));
            addSignIfNeeded(body, signSecret);
            return doPost(webhookUrl, body.toString());
        } catch (Throwable e) {
            return new SendResult(false, "构造消息失败: " + e.getMessage());
        }
    }

    private static void addSignIfNeeded(JSONObject body, String secret) throws Exception {
        if (TextUtils.isEmpty(secret)) return;
        long timestamp = System.currentTimeMillis() / 1000L;
        String sign = computeSign(timestamp, secret);
        body.put("timestamp", String.valueOf(timestamp));
        body.put("sign", sign);
    }

    private static String computeSign(long timestamp, String secret) throws Exception {
        String stringToSign = timestamp + "\n" + secret;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(stringToSign.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] signData = mac.doFinal(new byte[0]);
        return android.util.Base64.encodeToString(signData, android.util.Base64.NO_WRAP);
    }

    private static SendResult doPost(String urlStr, String jsonBody) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setConnectTimeout(UiComponentConfig.AI_HTTP_CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(UiComponentConfig.AI_HTTP_READ_TIMEOUT_MS);
            conn.setDoOutput(true);

            byte[] payload = jsonBody.getBytes(StandardCharsets.UTF_8);
            OutputStream os = conn.getOutputStream();
            os.write(payload);
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            StringBuilder sb = new StringBuilder();
            try {
                BufferedReader br = new BufferedReader(
                        new InputStreamReader(
                                code >= 200 && code < 300
                                        ? conn.getInputStream()
                                        : conn.getErrorStream(),
                                StandardCharsets.UTF_8
                        )
                );
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line);
                }
                br.close();
            } catch (Throwable ignore) {
            }

            boolean ok = code >= 200 && code < 300;
            if (ok) {
                try {
                    JSONObject resp = new JSONObject(sb.toString());
                    int statusCode = resp.optInt("StatusCode", resp.optInt("code", 0));
                    if (statusCode != 0) {
                        return new SendResult(false,
                                "飞书返回错误 code=" + statusCode + ", msg=" + resp.optString("msg", ""));
                    }
                } catch (Throwable ignored) {
                }
            }
            return new SendResult(ok,
                    ok ? "HTTP " + code : "HTTP " + code + ": " + sb.toString());
        } catch (Throwable e) {
            Log.e(TAG, "Feishu POST failed", e);
            return new SendResult(false, "异常: " + e.getMessage());
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Throwable ignore) { }
            }
        }
    }
}
