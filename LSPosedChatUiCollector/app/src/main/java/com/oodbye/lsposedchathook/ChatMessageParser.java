package com.oodbye.lsposedchathook;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 聊天文本处理器：
 * 1) 去除开头 icon 前缀
 * 2) 解析“送了 ... icn”礼物消息
 */
final class ChatMessageParser {
    private static final Pattern P_QUANTITY_EACH = Pattern.compile("^各(\\d+)个(.+)$");
    private static final Pattern P_QUANTITY = Pattern.compile("^(\\d+)个(.+)$");

    private ChatMessageParser() {
    }

    static String stripIconPrefix(String raw) {
        if (raw == null) {
            return "";
        }
        String t = raw;
        // 兼容前导空白、BOM、零宽字符等场景，强制去除开头 icon 前缀。
        t = t.replaceFirst("^[\\s\\uFEFF\\u200B\\u200C\\u200D]*icon\\s*", "");
        return t.trim();
    }

    static GiftParseResult parseGift(String cleanedChat, GiftPriceCatalog giftPriceCatalog) {
        if (cleanedChat == null) {
            return GiftParseResult.empty();
        }
        String text = cleanedChat.trim();
        if (text.length() == 0) {
            return GiftParseResult.empty();
        }
        if (text.indexOf("送了") < 0 || !text.endsWith("icn")) {
            return GiftParseResult.empty();
        }

        int idx = text.indexOf("送了");
        if (idx <= 0 || idx >= text.length() - 2) {
            return GiftParseResult.empty();
        }

        String sender = text.substring(0, idx).trim();
        String rest = text.substring(idx + 2).trim();
        if (sender.length() == 0 || rest.length() == 0) {
            return GiftParseResult.empty();
        }

        int lastSpace = rest.lastIndexOf(' ');
        if (lastSpace <= 0 || lastSpace >= rest.length() - 1) {
            return GiftParseResult.empty();
        }
        String receiversPart = rest.substring(0, lastSpace).trim();
        String giftPartWithIcn = rest.substring(lastSpace + 1).trim();
        if (!giftPartWithIcn.endsWith("icn")) {
            return GiftParseResult.empty();
        }

        String giftSpec = giftPartWithIcn.substring(0, giftPartWithIcn.length() - 3).trim();
        if (giftSpec.length() == 0) {
            return GiftParseResult.empty();
        }

        int quantity = 1;
        String giftName = giftSpec;
        Matcher eachMatcher = P_QUANTITY_EACH.matcher(giftSpec);
        if (eachMatcher.matches()) {
            quantity = parsePositiveInt(eachMatcher.group(1), 1);
            giftName = safeTrim(eachMatcher.group(2));
        } else {
            Matcher quantityMatcher = P_QUANTITY.matcher(giftSpec);
            if (quantityMatcher.matches()) {
                quantity = parsePositiveInt(quantityMatcher.group(1), 1);
                giftName = safeTrim(quantityMatcher.group(2));
            }
        }
        if (giftName.length() == 0) {
            return GiftParseResult.empty();
        }

        List<String> receivers = splitReceivers(receiversPart);
        if (receivers.isEmpty()) {
            return GiftParseResult.empty();
        }

        long unitPrice = giftPriceCatalog == null ? 0L : giftPriceCatalog.unitPrice(giftName);
        long totalPrice = unitPrice * (long) quantity;
        return GiftParseResult.of(sender, receivers, giftName, quantity, totalPrice);
    }

    private static List<String> splitReceivers(String receiversPart) {
        List<String> out = new ArrayList<String>();
        if (receiversPart == null) {
            return out;
        }
        String[] arr = receiversPart.split("、");
        for (int i = 0; i < arr.length; i++) {
            String item = safeTrim(arr[i]);
            if (item.length() > 0) {
                out.add(item);
            }
        }
        return out;
    }

    private static String safeTrim(String s) {
        return s == null ? "" : s.trim();
    }

    private static int parsePositiveInt(String text, int fallback) {
        try {
            int v = Integer.parseInt(text);
            return v > 0 ? v : fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    static final class GiftParseResult {
        final boolean isGift;
        final String sender;
        final List<String> receivers;
        final String giftName;
        final int quantityEach;
        final long totalPriceEachReceiver;

        private GiftParseResult(boolean isGift,
                                String sender,
                                List<String> receivers,
                                String giftName,
                                int quantityEach,
                                long totalPriceEachReceiver) {
            this.isGift = isGift;
            this.sender = sender;
            this.receivers = receivers;
            this.giftName = giftName;
            this.quantityEach = quantityEach;
            this.totalPriceEachReceiver = totalPriceEachReceiver;
        }

        static GiftParseResult empty() {
            return new GiftParseResult(false, "", new ArrayList<String>(), "", 0, 0L);
        }

        static GiftParseResult of(String sender,
                                  List<String> receivers,
                                  String giftName,
                                  int quantityEach,
                                  long totalPriceEachReceiver) {
            return new GiftParseResult(true, sender, receivers, giftName, quantityEach, totalPriceEachReceiver);
        }
    }
}
