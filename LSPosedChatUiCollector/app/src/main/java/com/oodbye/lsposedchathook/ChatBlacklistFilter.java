package com.oodbye.lsposedchathook;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XposedBridge;

/**
 * 聊天黑名单过滤组件（由云端配置下发后替换规则）。
 */
class ChatBlacklistFilter {
    private final String tag;

    private final Set<String> exactRules = new HashSet<String>();
    private final Set<String> canonicalRules = new HashSet<String>();

    ChatBlacklistFilter(String tag) {
        this.tag = tag;
    }

    synchronized void replaceRules(List<String> rules) {
        Set<String> newExact = new HashSet<String>();
        Set<String> newCanonical = new HashSet<String>();

        if (rules != null) {
            for (int i = 0; i < rules.size(); i++) {
                String raw = rules.get(i);
                String v = normalize(raw);
                if (v == null || v.length() == 0) {
                    continue;
                }
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

        XposedBridge.log(tag + " blacklist cache replaced exact=" + exactRules.size()
                + " canonical=" + canonicalRules.size());
    }

    synchronized int size() {
        return exactRules.size();
    }

    synchronized boolean isBlocked(String text) {
        String t = normalize(text);
        if (t == null) {
            return true;
        }

        if (exactRules.contains(t)) {
            return true;
        }

        String canonical = canonicalize(t);
        if (canonical != null && canonicalRules.contains(canonical)) {
            return true;
        }

        if (canonical != null) {
            for (String rule : canonicalRules) {
                if (rule != null && rule.length() > 0 && canonical.contains(rule)) {
                    return true;
                }
            }
        }
        return false;
    }

    private String normalize(String text) {
        if (text == null) {
            return null;
        }
        String t = text.replace('\n', ' ').replace('\r', ' ').trim();
        if (t.length() == 0) {
            return null;
        }
        if (t.length() > 1200) {
            t = t.substring(0, 1200);
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
        t = t.replaceAll("[，。！？；：、“”‘’（）()【】《》<>·…—\\-_,.:;!?'\"`~]", "");
        t = t.trim();
        if (t.length() == 0) {
            return null;
        }
        if (t.length() > 1200) {
            t = t.substring(0, 1200);
        }
        return t;
    }
}
