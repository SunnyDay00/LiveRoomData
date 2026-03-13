package com.oodbye.shuangyulspmodule;

import android.accessibilityservice.AccessibilityService;
import android.util.Log;

/**
 * 通过无障碍服务执行自定义广告规则的处理器。
 */
final class CustomRulesAdProcessor {
    private static final String TAG = "SYLspModule";

    private final AccessibilityCustomRulesAdEngine engine;

    CustomRulesAdProcessor(android.content.Context context) {
        this.engine = new AccessibilityCustomRulesAdEngine(context);
    }

    void reloadRules() {
        engine.reloadRules();
    }

    int getRuleCount() {
        return engine.getRuleCount();
    }

    boolean process(AccessibilityService service) {
        try {
            return engine.processRules(service);
        } catch (Throwable e) {
            Log.e(TAG, "CustomRulesAdProcessor error", e);
            return false;
        }
    }
}
