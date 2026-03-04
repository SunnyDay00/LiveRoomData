package com.oodbye.looklspmodule;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class ModuleStartupReceiver extends BroadcastReceiver {
    private static final String TAG = "LOOKLspModule";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : intent.getAction();
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_USER_UNLOCKED.equals(action)) {
            long seq = ModuleSettings.forceStopAndResetRuntime(context);
            Log.i(TAG, "[ModuleReceiver] system action reset engine/runtime to STOPPED, action="
                    + action + " seq=" + seq);
        }
        boolean shouldSyncFloatService = true;
        if (ModuleSettings.ACTION_ENGINE_STATUS_REPORT.equals(action)) {
            String statusName = intent.getStringExtra(ModuleSettings.EXTRA_ENGINE_STATUS);
            String command = intent.getStringExtra(ModuleSettings.EXTRA_ENGINE_COMMAND);
            long seq = intent.getLongExtra(ModuleSettings.EXTRA_ENGINE_COMMAND_SEQ, -1L);
            ModuleSettings.EngineStatus status = ModuleSettings.parseStatusName(statusName);
            ModuleSettings.syncEngineState(context, command, status, seq);
            Log.i(TAG, "[ModuleReceiver] engine status report: status=" + status
                    + " command=" + command + " seq=" + seq);
            // 引擎状态报告会高频上报，悬浮服务由自身定时刷新状态，不在此处重复拉起。
            shouldSyncFloatService = false;
        } else if (ModuleSettings.ACTION_RUNTIME_STATS_REPORT.equals(action)) {
            long runStartAt = intent.getLongExtra(ModuleSettings.EXTRA_RUNTIME_RUN_START_AT, 0L);
            int cycleCompleted = intent.getIntExtra(ModuleSettings.EXTRA_RUNTIME_CYCLE_COMPLETED, 0);
            int cycleEntered = intent.getIntExtra(ModuleSettings.EXTRA_RUNTIME_CYCLE_ENTERED, 0);
            long runtimeSeq = intent.getLongExtra(ModuleSettings.EXTRA_RUNTIME_COMMAND_SEQ, -1L);
            ModuleSettings.syncRuntimeStats(
                    context,
                    runStartAt,
                    cycleCompleted,
                    cycleEntered,
                    runtimeSeq
            );
            Log.i(TAG, "[ModuleReceiver] runtime stats synced: runStartAt=" + runStartAt
                    + " completed=" + cycleCompleted
                    + " entered=" + cycleEntered
                    + " runtimeSeq=" + runtimeSeq);
            shouldSyncFloatService = false;
        } else if (ModuleSettings.ACTION_CYCLE_COMPLETE_NOTICE.equals(action)) {
            String message = intent.getStringExtra(ModuleSettings.EXTRA_CYCLE_COMPLETE_MESSAGE);
            CycleCompleteNotifier.show(context, message);
            GlobalFloatService.startServiceCompatWithNotice(context, message);
            Log.i(TAG, "[ModuleReceiver] cycle complete notice dispatched, message=" + message);
            shouldSyncFloatService = false;
        } else if (ModuleSettings.ACTION_CYCLE_LIMIT_FINISHED.equals(action)) {
            int completedCycles = intent.getIntExtra(ModuleSettings.EXTRA_FINISHED_CYCLES, 0);
            int cycleLimit = intent.getIntExtra(ModuleSettings.EXTRA_FINISHED_CYCLE_LIMIT, 0);
            long durationMs = intent.getLongExtra(ModuleSettings.EXTRA_FINISHED_DURATION_MS, 0L);
            GlobalFloatService.startServiceCompatWithCycleLimitDialog(
                    context,
                    completedCycles,
                    cycleLimit,
                    durationMs
            );
            Log.i(TAG, "[ModuleReceiver] cycle limit finished dispatched: completed="
                    + completedCycles + " limit=" + cycleLimit + " durationMs=" + durationMs);
            shouldSyncFloatService = false;
        } else if (ModuleSettings.ACTION_AI_ANALYSIS_REQUEST.equals(action)) {
            String homeId = intent.getStringExtra(ModuleSettings.EXTRA_AI_ANALYZE_HOME_ID);
            long enterTimeMs = intent.getLongExtra(ModuleSettings.EXTRA_AI_ANALYZE_ENTER_TIME, 0L);
            String contributionCsv = intent.getStringExtra(ModuleSettings.EXTRA_AI_ANALYZE_CONTRIBUTION_CSV);
            String charmCsv = intent.getStringExtra(ModuleSettings.EXTRA_AI_ANALYZE_CHARM_CSV);
            RankAiConsumptionAnalyzer.scheduleAnalysis(
                    context,
                    homeId,
                    enterTimeMs,
                    contributionCsv,
                    charmCsv
            );
            Log.i(TAG, "[ModuleReceiver] ai analysis request dispatched: homeId="
                    + String.valueOf(homeId)
                    + " contributionCsv=" + String.valueOf(contributionCsv)
                    + " charmCsv=" + String.valueOf(charmCsv));
            shouldSyncFloatService = false;
        }
        if (shouldSyncFloatService) {
            boolean hasDisplayExtra = intent != null
                    && intent.hasExtra(ModuleSettings.EXTRA_TARGET_DISPLAY_ID);
            int targetDisplayId = hasDisplayExtra
                    ? intent.getIntExtra(ModuleSettings.EXTRA_TARGET_DISPLAY_ID, -1)
                    : -1;
            boolean requestRestart = intent != null
                    && intent.getBooleanExtra(ModuleSettings.EXTRA_REQUEST_RESTART_TARGET_APP, false);
            Log.i(TAG, "[ModuleReceiver] sync float service: action=" + String.valueOf(action)
                    + " hasDisplayExtra=" + hasDisplayExtra
                    + " targetDisplayId=" + targetDisplayId
                    + " requestRestart=" + requestRestart);
            FloatServiceBootstrap.syncFloatServiceState(context, intent);
        }
    }
}
