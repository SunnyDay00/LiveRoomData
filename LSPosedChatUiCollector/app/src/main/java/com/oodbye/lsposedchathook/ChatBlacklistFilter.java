package com.oodbye.lsposedchathook;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XposedBridge;

/**
 * 聊天黑名单过滤组件。
 * 文件职责：从 APK 资源中的 chat_blacklist.txt 加载规则，并执行精确匹配过滤。
 */
class ChatBlacklistFilter {
    private static final String RESOURCE_PATH = "com/oodbye/lsposedchathook/chat_blacklist.txt";

    private final String tag;
    private final long reloadIntervalMs;

    private long lastLoadAt;
    private final Set<String> exactRules = new HashSet<String>();
    private final Set<String> canonicalRules = new HashSet<String>();

    ChatBlacklistFilter(String tag, long reloadIntervalMs) {
        this.tag = tag;
        this.reloadIntervalMs = reloadIntervalMs;
    }

    void reloadNow() {
        lastLoadAt = 0L;
        reloadIfNeeded();
    }

    boolean isBlocked(String text) {
        String t = normalize(text);
        if (t == null) {
            return true;
        }

        reloadIfNeeded();
        if (exactRules.contains(t)) {
            return true;
        }
        String canonical = canonicalize(t);
        if (canonical != null && canonicalRules.contains(canonical)) {
            return true;
        }
        // 兼容消息中存在额外前后缀、标点差异的场景：采用标准化后包含匹配。
        if (canonical != null) {
            for (String rule : canonicalRules) {
                if (rule != null && rule.length() > 0 && canonical.contains(rule)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void reloadIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastLoadAt < reloadIntervalMs) {
            return;
        }
        lastLoadAt = now;

        InputStream in = null;
        BufferedReader reader = null;
        try {
            ClassLoader cl = ChatBlacklistFilter.class.getClassLoader();
            if (cl == null) {
                return;
            }
            in = cl.getResourceAsStream(RESOURCE_PATH);
            if (in == null) {
                XposedBridge.log(tag + " blacklist resource not found: " + RESOURCE_PATH);
                return;
            }

            Set<String> newExact = new HashSet<String>();
            Set<String> newCanonical = new HashSet<String>();
            reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                String t = line.trim();
                if (t.startsWith("\uFEFF")) {
                    t = t.substring(1).trim();
                }
                if (t.length() == 0 || t.startsWith("#")) {
                    continue;
                }
                String v = normalize(t);
                if (v != null && v.length() > 0) {
                    newExact.add(v);
                    String canonical = canonicalize(v);
                    if (canonical != null && canonical.length() > 0) {
                        newCanonical.add(canonical);
                    }
                }
            }
            exactRules.clear();
            exactRules.addAll(newExact);
            canonicalRules.clear();
            canonicalRules.addAll(newCanonical);
            XposedBridge.log(tag + " blacklist loaded exact=" + exactRules.size()
                    + " canonical=" + canonicalRules.size());
        } catch (Throwable e) {
            XposedBridge.log(tag + " load blacklist error: " + e);
        } finally {
            try {
                if (reader != null) {
                    reader.close();
                }
            } catch (Throwable ignored) {
            }
            try {
                if (in != null) {
                    in.close();
                }
            } catch (Throwable ignored2) {
            }
        }
    }

    private String normalize(String text) {
        if (text == null) {
            return null;
        }
        String t = text.replace('\n', ' ').replace('\r', ' ').trim();
        if (t.length() == 0) {
            return null;
        }
        if (t.length() > 400) {
            t = t.substring(0, 400);
        }
        return t;
    }

    private String canonicalize(String text) {
        if (text == null) {
            return null;
        }
        String t = text;
        t = t.replace('\uFEFF', ' ');
        t = t.replace('\u200B', ' ');
        t = t.replace('\u200C', ' ');
        t = t.replace('\u200D', ' ');
        t = t.replace('\u3000', ' ');
        t = t.replaceAll("\\s+", "");
        // 去标点后再匹配，避免“，。”“.”等差异导致漏拦截。
        t = t.replaceAll("[，。！？；：、“”‘’（）()【】《》<>·…—\\-_,.:;!?'\"`~]", "");
        t = t.trim();
        if (t.length() == 0) {
            return null;
        }
        if (t.length() > 400) {
            t = t.substring(0, 400);
        }
        return t;
    }
}
