package com.oodbye.shuangyulspmodule;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.util.Log;
import org.json.JSONObject;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 等级数据跨进程桥接（广播方式）。
 *
 * Hook 进程（目标 App）每次采集到等级数据后发送广播；
 * 模块进程（无障碍服务）注册接收器，缓存到内存 Map。
 *
 * 不依赖任何 Xposed API，可安全在两个进程中使用。
 */
final class LevelDataBridge {

    private static final String TAG = "SYLspModule";

    static final String ACTION_LEVEL_ENTRY = "com.oodbye.shuangyulspmodule.ACTION_LEVEL_ENTRY";
    static final String EXTRA_LEVEL_JSON = "level_json";

    // 模块进程内存缓存（由广播接收器填充）
    private static final Map<String, LevelInfo> sCache = new ConcurrentHashMap<>();

    private static BroadcastReceiver sReceiver;

    private LevelDataBridge() {}

    // ─────────────────────── 写入端（Hook 进程调用）───────────────────────

    /** Hook 端：采集到一条等级数据后，立刻发送广播 */
    static void broadcastEntry(Context context, String userId, String nickName,
                               int wealthLevel, int charmLevel,
                               String wealthImg, String charmImg,
                               String gender, int genderResId) {
        broadcastEntry(context, userId, nickName, "",
                wealthLevel, charmLevel, wealthImg, charmImg, gender, genderResId);
    }

    /** Hook 端：采集到一条等级数据后，立刻发送广播（含 userCode） */
    static void broadcastEntry(Context context, String userId, String nickName,
                               String userCode,
                               int wealthLevel, int charmLevel,
                               String wealthImg, String charmImg,
                               String gender, int genderResId) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("userId", userId);
            obj.put("nickName", nickName);
            obj.put("userCode", userCode != null ? userCode : "");
            obj.put("wealthLevel", wealthLevel);
            obj.put("charmLevel", charmLevel);
            obj.put("wealthImg", wealthImg != null ? wealthImg : "");
            obj.put("charmImg", charmImg != null ? charmImg : "");
            obj.put("gender", gender != null ? gender : "unknown");
            obj.put("genderResId", genderResId);

            Intent intent = new Intent(ACTION_LEVEL_ENTRY);
            intent.setPackage("com.oodbye.shuangyulspmodule");
            intent.putExtra(EXTRA_LEVEL_JSON, obj.toString());
            context.sendBroadcast(intent);
        } catch (Throwable e) {
            ModuleRunFileLogger.e(TAG, "LevelDataBridge.broadcastEntry 失败", e);
        }
    }

    // ─────────────────────── 读取端（模块进程调用）───────────────────────

    /** 模块端：注册广播接收器（在无障碍服务 onCreate 中调用） */
    static void registerReceiver(Context context) {
        if (sReceiver != null) return;
        sReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                String json = intent.getStringExtra(EXTRA_LEVEL_JSON);
                if (json == null) return;
                try {
                    JSONObject obj = new JSONObject(json);
                    String uid = obj.optString("userId", "");
                    if (uid.isEmpty()) return;

                    LevelInfo info = new LevelInfo();
                    info.userId = uid;
                    info.nickName = obj.optString("nickName", "");
                    info.userCode = obj.optString("userCode", "");
                    info.wealthLevel = obj.optInt("wealthLevel", -1);
                    info.charmLevel = obj.optInt("charmLevel", -1);
                    info.gender = obj.optString("gender", "unknown");
                    info.genderResId = obj.optInt("genderResId", 0);
                    // 按 userId 缓存
                    sCache.put(uid, info);
                    // 同时按 userCode 的纯数字部分缓存（如 "ID:22889314" → "22889314"）
                    if (!info.userCode.isEmpty()) {
                        String cleanCode = info.userCode;
                        if (cleanCode.startsWith("ID:")) cleanCode = cleanCode.substring(3);
                        if (!cleanCode.isEmpty() && !cleanCode.equals(uid)) {
                            sCache.put(cleanCode, info);
                        }
                    }
                } catch (Throwable e) {
                    ModuleRunFileLogger.e(TAG, "LevelDataBridge 接收失败", e);
                }
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_LEVEL_ENTRY);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(sReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(sReceiver, filter);
        }
        ModuleRunFileLogger.i(TAG, "📊 等级数据接收器已注册");
    }

    /** 模块端：查询指定用户的等级数据 */
    static LevelInfo readLevelEntry(Context context, String userId) {
        if (userId == null) return null;
        // 尝试直接匹配
        LevelInfo info = sCache.get(userId);
        if (info != null) return info;
        // userId 可能带 "ID:" 前缀
        String cleanId = userId;
        if (cleanId.startsWith("ID:")) cleanId = cleanId.substring(3);
        if (cleanId.startsWith("ID:")) cleanId = cleanId.substring(3);
        info = sCache.get(cleanId);
        return info;
    }

    /** 等级数据 POJO（不依赖 Xposed） */
    static class LevelInfo {
        String userId;
        String userCode;   // 展示 ID（如 "ID:22889314"），与 userId（内部ID）可能不同
        String nickName;
        int wealthLevel;
        int charmLevel;
        String gender;
        int genderResId;
    }
}
