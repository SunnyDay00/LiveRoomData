package com.oodbye.shuangyulspmodule;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;

/**
 * 接收模块相关广播：引擎状态同步、运行报告、循环结束通知等。
 */
public class ModuleStartupReceiver extends BroadcastReceiver {
    private static final String TAG = "SYLspModule";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String action = intent.getAction();
        if (TextUtils.isEmpty(action)) return;
        Log.i(TAG, "ModuleStartupReceiver action=" + action);

        switch (action) {
            case Intent.ACTION_MY_PACKAGE_REPLACED:
            case Intent.ACTION_BOOT_COMPLETED:
            case Intent.ACTION_LOCKED_BOOT_COMPLETED:
                handleBoot(context);
                break;
            case ModuleSettings.ACTION_SYNC_FLOAT_STATE:
                handleSyncFloatState(context, intent);
                break;
            case ModuleSettings.ACTION_ENGINE_STATUS_REPORT:
                handleEngineStatusReport(context, intent);
                break;
            case ModuleSettings.ACTION_RUNTIME_STATS_REPORT:
                handleRuntimeStatsReport(context, intent);
                break;
            case ModuleSettings.ACTION_CYCLE_COMPLETE_NOTICE:
                handleCycleCompleteNotice(context, intent);
                break;
            case ModuleSettings.ACTION_CYCLE_LIMIT_FINISHED:
                handleCycleLimitFinished(context, intent);
                break;
            default:
                break;
        }
    }

    private void handleBoot(Context context) {
        ModuleSettings.ensureDefaults(context);
        SharedPreferences prefs = ModuleSettings.appPrefs(context);
        if (ModuleSettings.getGlobalFloatButtonEnabled(prefs)) {
            FloatServiceBootstrap.startFloatService(context);
        }
    }

    private void handleSyncFloatState(Context context, Intent intent) {
        SharedPreferences prefs = ModuleSettings.appPrefs(context);
        if (ModuleSettings.getGlobalFloatButtonEnabled(prefs)) {
            int displayId = intent.getIntExtra(ModuleSettings.EXTRA_TARGET_DISPLAY_ID, -1);
            boolean restart = intent.getBooleanExtra(
                    ModuleSettings.EXTRA_REQUEST_RESTART_TARGET_APP, false);
            FloatServiceBootstrap.startFloatService(context, displayId, restart);
        }
    }

    private void handleEngineStatusReport(Context context, Intent intent) {
        String status = intent.getStringExtra(ModuleSettings.EXTRA_ENGINE_STATUS);
        String command = intent.getStringExtra(ModuleSettings.EXTRA_ENGINE_COMMAND);
        long seq = intent.getLongExtra(ModuleSettings.EXTRA_ENGINE_COMMAND_SEQ, 0L);
        if (!TextUtils.isEmpty(status)) {
            ModuleSettings.syncEngineState(context,
                    command != null ? command : ModuleSettings.ENGINE_CMD_STOP,
                    ModuleSettings.parseStatusName(status), seq);
        }
    }

    private void handleRuntimeStatsReport(Context context, Intent intent) {
        long runStartAt = intent.getLongExtra(ModuleSettings.EXTRA_RUNTIME_RUN_START_AT, 0L);
        int completed = intent.getIntExtra(ModuleSettings.EXTRA_RUNTIME_CYCLE_COMPLETED, 0);
        int entered = intent.getIntExtra(ModuleSettings.EXTRA_RUNTIME_CYCLE_ENTERED, 0);
        long seq = intent.getLongExtra(ModuleSettings.EXTRA_RUNTIME_COMMAND_SEQ, 0L);
        ModuleSettings.syncRuntimeStats(context, runStartAt, completed, entered, seq);
    }

    private void handleCycleCompleteNotice(Context context, Intent intent) {
        String msg = intent.getStringExtra(ModuleSettings.EXTRA_CYCLE_COMPLETE_MESSAGE);
        CycleCompleteNotifier.show(context, msg != null ? msg : "采集完成");
    }

    private void handleCycleLimitFinished(Context context, Intent intent) {
        int cycles = intent.getIntExtra(ModuleSettings.EXTRA_FINISHED_CYCLES, 0);
        long duration = intent.getLongExtra(ModuleSettings.EXTRA_FINISHED_DURATION_MS, 0L);
        ModuleSettings.forceStopAndResetRuntime(context);
        CycleCompleteNotifier.show(context,
                "完成 " + cycles + " 轮采集, 耗时 " + (duration / 1000L) + "s");
    }
}
