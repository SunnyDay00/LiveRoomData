package com.oodbye.lsposedchathook;

import android.app.Activity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.SSLHandshakeException;

import de.robv.android.xposed.XposedBridge;

/**
 * 远程 API 客户端（Cloudflare Worker 中间层）。
 *
 * 接口约定：
 * - GET  /v1/health
 * - GET  /v1/config/version
 * - GET  /v1/config/snapshot
 * - POST /v1/chat/ingest
 */
class RemoteApiClient {
    private static final int CONNECT_TIMEOUT_MS = 6000;
    private static final int READ_TIMEOUT_MS = 8000;

    private final String tag;
    private final Activity activity;
    private volatile String lastRequestError = "";

    RemoteApiClient(Activity activity, String tag) {
        this.activity = activity;
        this.tag = tag;
    }

    boolean isWriteConfigured() {
        return !isEmpty(getBaseUrl()) && !isEmpty(getWriteKey());
    }

    boolean isReadConfigured() {
        return !isEmpty(getBaseUrl()) && !isEmpty(getReadKeyOrFallback());
    }

    String missingConfigReasonForWrite() {
        if (isEmpty(getBaseUrl())) {
            return "未配置远程API地址";
        }
        if (isEmpty(getWriteKey())) {
            return "未配置远程写入Key";
        }
        return "";
    }

    String getBaseUrl() {
        return normalizeBaseUrl(ModuleSettings.getRemoteApiBaseUrl());
    }

    boolean healthCheck() {
        Response rsp = request("GET", "/v1/health", null, getReadKeyOrFallback());
        return rsp != null && rsp.code >= 200 && rsp.code < 300;
    }

    String getLastRequestError() {
        return safeTrim(lastRequestError);
    }

    ConfigVersion fetchConfigVersion() {
        Response rsp = request("GET", "/v1/config/version", null, getReadKeyOrFallback());
        if (rsp == null || rsp.code < 200 || rsp.code >= 300 || isEmpty(rsp.body)) {
            return ConfigVersion.invalid();
        }
        try {
            JSONObject root = new JSONObject(rsp.body);
            JSONObject data = root.optJSONObject("data");
            String version = "";
            if (data != null) {
                version = safeTrim(data.optString("version", ""));
            }
            if (isEmpty(version)) {
                version = safeTrim(root.optString("version", ""));
            }
            if (isEmpty(version)) {
                return ConfigVersion.invalid();
            }
            return ConfigVersion.ok(version);
        } catch (Throwable e) {
            XposedBridge.log(tag + " parse config version error: " + e);
            return ConfigVersion.invalid();
        }
    }

    ConfigSnapshot fetchConfigSnapshot() {
        Response rsp = request("GET", "/v1/config/snapshot", null, getReadKeyOrFallback());
        if (rsp == null || rsp.code < 200 || rsp.code >= 300 || isEmpty(rsp.body)) {
            return null;
        }
        try {
            JSONObject root = new JSONObject(rsp.body);
            JSONObject data = root.optJSONObject("data");
            if (data == null) {
                return null;
            }

            ConfigSnapshot snapshot = new ConfigSnapshot();
            snapshot.version = safeTrim(data.optString("version", ""));
            snapshot.giftPrices = new LinkedHashMap<String, Long>();
            snapshot.blacklistRules = new ArrayList<String>();

            JSONArray giftArr = data.optJSONArray("giftPrices");
            if (giftArr != null) {
                for (int i = 0; i < giftArr.length(); i++) {
                    JSONObject item = giftArr.optJSONObject(i);
                    if (item == null) {
                        continue;
                    }
                    String giftName = safeTrim(item.optString("giftName", ""));
                    if (isEmpty(giftName)) {
                        continue;
                    }
                    long notePrice = item.optLong("notePrice", 0L);
                    if (notePrice < 0L) {
                        notePrice = 0L;
                    }
                    snapshot.giftPrices.put(giftName, notePrice);
                }
            }

            JSONArray blacklistArr = data.optJSONArray("blacklistRules");
            if (blacklistArr != null) {
                for (int i = 0; i < blacklistArr.length(); i++) {
                    String rule = safeTrim(blacklistArr.optString(i, ""));
                    if (!isEmpty(rule)) {
                        snapshot.blacklistRules.add(rule);
                    }
                }
            }
            return snapshot;
        } catch (Throwable e) {
            XposedBridge.log(tag + " parse config snapshot error: " + e);
            return null;
        }
    }

    boolean ingestBatch(List<ChatRecord> records) {
        if (records == null || records.isEmpty()) {
            return true;
        }
        JSONArray arr = new JSONArray();
        try {
            for (int i = 0; i < records.size(); i++) {
                ChatRecord rec = records.get(i);
                if (rec == null) {
                    continue;
                }
                JSONObject obj = new JSONObject();
                obj.put("deviceId", safeTrim(rec.deviceId));
                obj.put("roomId", safeTrim(rec.roomId));
                obj.put("eventTime", safeTrim(rec.eventTime));
                obj.put("chatText", safeTrim(rec.chatText));
                obj.put("sender", safeTrim(rec.sender));
                obj.put("receiver", safeTrim(rec.receiver));
                obj.put("giftName", safeTrim(rec.giftName));
                if (rec.giftQty == null) {
                    obj.put("giftQty", JSONObject.NULL);
                } else {
                    obj.put("giftQty", rec.giftQty.intValue());
                }
                obj.put("notePrice", safeTrim(rec.notePrice));
                obj.put("msgHash", safeTrim(rec.msgHash));
                arr.put(obj);
            }
            JSONObject payload = new JSONObject();
            payload.put("records", arr);
            Response rsp = request("POST", "/v1/chat/ingest", payload.toString(), getWriteKey());
            return rsp != null && rsp.code >= 200 && rsp.code < 300;
        } catch (Throwable e) {
            XposedBridge.log(tag + " build ingest payload error: " + e);
            return false;
        }
    }

    private Response request(String method, String path, String body, String apiKey) {
        String base = normalizeBaseUrl(getBaseUrl());
        return requestWithBaseUrl(base, method, path, body, apiKey);
    }

    private Response requestWithBaseUrl(String baseUrl, String method, String path, String body, String apiKey) {
        HttpURLConnection conn = null;
        InputStream in = null;
        String fullUrl = "";
        String host = "";
        lastRequestError = "";
        try {
            if (isEmpty(baseUrl)) {
                lastRequestError = "base_url_empty";
                return null;
            }
            fullUrl = buildUrl(baseUrl, path);
            URL url = new URL(fullUrl);
            host = safeTrim(url.getHost());
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setUseCaches(false);
            conn.setDoInput(true);
            conn.setRequestProperty("Accept", "application/json");
            if (!isEmpty(apiKey)) {
                conn.setRequestProperty("X-API-Key", apiKey);
            }
            if (body != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                conn.setFixedLengthStreamingMode(bytes.length);
                OutputStream os = conn.getOutputStream();
                try {
                    os.write(bytes);
                    os.flush();
                } finally {
                    os.close();
                }
            }

            int code = conn.getResponseCode();
            in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String responseBody = readFully(in);
            if (code >= 400) {
                String bodyPreview = safeTrim(responseBody);
                if (bodyPreview.length() > 120) {
                    bodyPreview = bodyPreview.substring(0, 120);
                }
                lastRequestError = "HTTP " + code + (isEmpty(bodyPreview) ? "" : (": " + bodyPreview));
                XposedBridge.log(tag + " remote http error method=" + method
                        + " path=" + path
                        + " host=" + host
                        + " code=" + code
                        + " detail=" + lastRequestError);
            } else {
                lastRequestError = "";
            }
            return new Response(code, responseBody);
        } catch (Throwable e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (e instanceof UnknownHostException) {
                lastRequestError = "NETWORK: UnknownHostException: " + msg;
                logDnsDiagnostics(host);
            } else if (e instanceof SocketTimeoutException) {
                lastRequestError = "NETWORK: SocketTimeoutException: " + msg;
            } else if (e instanceof SSLHandshakeException) {
                lastRequestError = "NETWORK: SSLHandshakeException: " + msg;
            } else if (e instanceof ConnectException) {
                lastRequestError = "NETWORK: ConnectException: " + msg;
            } else {
                lastRequestError = "NETWORK: " + e.getClass().getSimpleName()
                        + (msg.length() == 0 ? "" : (": " + msg));
            }
            XposedBridge.log(tag + " remote request error method=" + method
                    + " path=" + path
                    + " host=" + host
                    + " url=" + fullUrl
                    + " err=" + e.getClass().getSimpleName()
                    + (msg.length() == 0 ? "" : (": " + msg)));
            return null;
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (Throwable ignored) {
            }
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private void logDnsDiagnostics(String host) {
        if (isEmpty(host)) {
            XposedBridge.log(tag + " dns diag skipped: host empty");
            return;
        }
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses == null || addresses.length == 0) {
                XposedBridge.log(tag + " dns diag host=" + host + " resolved=none");
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < addresses.length; i++) {
                InetAddress addr = addresses[i];
                if (addr == null) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append(",");
                }
                sb.append(addr.getHostAddress());
            }
            XposedBridge.log(tag + " dns diag host=" + host + " resolved=" + sb);
        } catch (Throwable e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            XposedBridge.log(tag + " dns diag host=" + host + " failed="
                    + e.getClass().getSimpleName()
                    + (msg.length() == 0 ? "" : (": " + msg)));
        }
    }

    private String buildUrl(String baseUrl, String path) {
        String b = baseUrl;
        String p = path;
        if (isEmpty(p)) {
            p = "/";
        }
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        return b + p;
    }

    private String readFully(InputStream in) throws Exception {
        if (in == null) {
            return "";
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        return sb.toString();
    }

    private String getWriteKey() {
        return safeTrim(ModuleSettings.getRemoteWriteApiKey());
    }

    private String getReadKeyOrFallback() {
        String read = safeTrim(ModuleSettings.getRemoteReadApiKey());
        if (!isEmpty(read)) {
            return read;
        }
        return getWriteKey();
    }

    private boolean isEmpty(String s) {
        return s == null || s.trim().length() == 0;
    }

    private String safeTrim(String s) {
        if (s == null) {
            return "";
        }
        return s.trim();
    }

    private String normalizeBaseUrl(String raw) {
        String value = safeTrim(raw);
        if (value.length() == 0) {
            return "";
        }
        value = value.replaceAll("\\s+", "");
        if (value.startsWith("HTTP://")) {
            value = "http://" + value.substring(7);
        } else if (value.startsWith("HTTPS://")) {
            value = "https://" + value.substring(8);
        }
        // 域名统一小写，避免复制时出现 I/l 混淆导致解析异常
        int schemeIdx = value.indexOf("://");
        if (schemeIdx > 0 && schemeIdx + 3 < value.length()) {
            String scheme = value.substring(0, schemeIdx + 3);
            String hostAndPath = value.substring(schemeIdx + 3).toLowerCase(Locale.ROOT);
            value = scheme + hostAndPath;
        }
        return value;
    }

    static class ConfigVersion {
        final boolean ok;
        final String version;

        private ConfigVersion(boolean ok, String version) {
            this.ok = ok;
            this.version = version == null ? "" : version;
        }

        static ConfigVersion ok(String version) {
            return new ConfigVersion(true, version);
        }

        static ConfigVersion invalid() {
            return new ConfigVersion(false, "");
        }
    }

    static class ConfigSnapshot {
        String version = "";
        Map<String, Long> giftPrices = new LinkedHashMap<String, Long>();
        List<String> blacklistRules = new ArrayList<String>();
    }

    static class ChatRecord {
        String deviceId;
        String roomId;
        String eventTime;
        String chatText;
        String sender;
        String receiver;
        String giftName;
        Integer giftQty;
        String notePrice;
        String msgHash;
    }

    private static class Response {
        final int code;
        final String body;

        Response(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }
}
