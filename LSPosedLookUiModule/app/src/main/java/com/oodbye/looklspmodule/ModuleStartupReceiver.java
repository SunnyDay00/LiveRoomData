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
        } else if (ModuleSettings.ACTION_RUNTIME_STATS_REPORT.equals(action)) {
            long runStartAt = intent.getLongExtra(ModuleSettings.EXTRA_RUNTIME_RUN_START_AT, 0L);
            int cycleCompleted = intent.getIntExtra(ModuleSettings.EXTRA_RUNTIME_CYCLE_COMPLETED, 0);
            int cycleEntered = intent.getIntExtra(ModuleSettings.EXTRA_RUNTIME_CYCLE_ENTERED, 0);
            ModuleSettings.syncRuntimeStats(context, runStartAt, cycleCompleted, cycleEntered);
            Log.i(TAG, "[ModuleReceiver] runtime stats synced: runStartAt=" + runStartAt
                    + " completed=" + cycleCompleted + " entered=" + cycleEntered);
            shouldSyncFloatService = false;
        } else if (ModuleSettings.ACTION_CYCLE_COMPLETE_NOTICE.equals(action)) {
            String message = intent.getStringExtra(ModuleSettings.EXTRA_CYCLE_COMPLETE_MESSAGE);
            CycleCompleteNotifier.show(context, message);
            GlobalFloatService.startServiceCompatWithNotice(context, message);
            Log.i(TAG, "[ModuleReceiver] cycle complete notice dispatched, message=" + message);
            shouldSyncFloatService = false;
        }
        if (shouldSyncFloatService) {
            FloatServiceBootstrap.syncFloatServiceState(context, intent);
        }
    }
}
