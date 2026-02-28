package com.oodbye.lsposedchathook;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import de.robv.android.xposed.XposedBridge;

/**
 * 礼物价格表加载器。
 * 数据来源：打包进 APK 的 com/oodbye/lsposedchathook/LOOK_price.json（每行: 礼物名,音符价格）。
 */
final class GiftPriceCatalog {
    private static final String RESOURCE_PATH = "com/oodbye/lsposedchathook/LOOK_price.json";

    private final String tag;
    private final Map<String, Long> priceMap = new LinkedHashMap<String, Long>();
    private boolean loaded;

    GiftPriceCatalog(String tag) {
        this.tag = tag;
    }

    synchronized long unitPrice(String giftName) {
        ensureLoaded();
        if (giftName == null) {
            return 0L;
        }
        Long v = priceMap.get(giftName.trim());
        return v == null ? 0L : v.longValue();
    }

    synchronized int size() {
        ensureLoaded();
        return priceMap.size();
    }

    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        loadFromResource();
    }

    private void loadFromResource() {
        InputStream in = null;
        BufferedReader reader = null;
        int loadedCount = 0;
        try {
            ClassLoader cl = GiftPriceCatalog.class.getClassLoader();
            if (cl == null) {
                XposedBridge.log(tag + " gift price load failed: classLoader null");
                return;
            }
            in = cl.getResourceAsStream(RESOURCE_PATH);
            if (in == null) {
                XposedBridge.log(tag + " gift price load failed: resource not found " + RESOURCE_PATH);
                return;
            }
            reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                String t = line.trim();
                if (t.length() == 0 || t.startsWith("#")) {
                    continue;
                }
                int comma = t.indexOf(',');
                if (comma <= 0 || comma >= t.length() - 1) {
                    continue;
                }
                String giftName = t.substring(0, comma).trim();
                String priceText = t.substring(comma + 1).trim();
                if (giftName.length() == 0 || priceText.length() == 0) {
                    continue;
                }
                long price;
                try {
                    price = Long.parseLong(priceText);
                } catch (Throwable ignored) {
                    continue;
                }
                priceMap.put(giftName, price);
                loadedCount++;
            }
            XposedBridge.log(tag + " gift price loaded entries=" + loadedCount);
        } catch (Throwable e) {
            XposedBridge.log(tag + " gift price load error: " + e);
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
}

