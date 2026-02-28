package com.oodbye.lsposedchathook;

import java.util.LinkedHashMap;
import java.util.Map;

import de.robv.android.xposed.XposedBridge;

/**
 * 礼物价格表缓存（由云端配置下发后替换）。
 */
final class GiftPriceCatalog {
    private final String tag;
    private final Map<String, Long> priceMap = new LinkedHashMap<String, Long>();

    GiftPriceCatalog(String tag) {
        this.tag = tag;
    }

    synchronized long unitPrice(String giftName) {
        if (giftName == null) {
            return 0L;
        }
        Long v = priceMap.get(giftName.trim());
        return v == null ? 0L : v.longValue();
    }

    synchronized int size() {
        return priceMap.size();
    }

    synchronized void replaceAll(Map<String, Long> latest) {
        priceMap.clear();
        if (latest != null && !latest.isEmpty()) {
            for (Map.Entry<String, Long> it : latest.entrySet()) {
                String name = it.getKey() == null ? "" : it.getKey().trim();
                if (name.length() == 0) {
                    continue;
                }
                long price = it.getValue() == null ? 0L : it.getValue().longValue();
                if (price < 0L) {
                    price = 0L;
                }
                priceMap.put(name, price);
            }
        }
        XposedBridge.log(tag + " gift price cache replaced count=" + priceMap.size());
    }
}
