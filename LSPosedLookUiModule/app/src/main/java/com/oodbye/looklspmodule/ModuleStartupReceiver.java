package com.oodbye.looklspmodule;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class ModuleStartupReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : intent.getAction();
        if (ModuleSettings.ACTION_ENGINE_STATUS_REPORT.equals(action)) {
            String statusName = intent.getStringExtra(ModuleSettings.EXTRA_ENGINE_STATUS);
            String command = intent.getStringExtra(ModuleSettings.EXTRA_ENGINE_COMMAND);
            long seq = intent.getLongExtra(ModuleSettings.EXTRA_ENGINE_COMMAND_SEQ, -1L);
            ModuleSettings.EngineStatus status = ModuleSettings.parseStatusName(statusName);
            ModuleSettings.syncEngineState(context, command, status, seq);
        } else if (ModuleSettings.ACTION_CYCLE_COMPLETE_NOTICE.equals(action)) {
            String message = intent.getStringExtra(ModuleSettings.EXTRA_CYCLE_COMPLETE_MESSAGE);
            CycleCompleteNotifier.show(context, message);
        }
        FloatServiceBootstrap.syncFloatServiceState(context, intent);
    }
}
