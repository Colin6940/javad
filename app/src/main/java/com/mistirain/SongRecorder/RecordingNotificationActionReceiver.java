package com.mistirain.SongRecorder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.content.ContextCompat;

/** Backward-compatible receiver retained for any previously posted notification intents. */
public class RecordingNotificationActionReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;
        switch (action) {
            case RecordingService.ACTION_TOGGLE_GATE:
            case RecordingService.ACTION_TOGGLE_EQ:
            case RecordingService.ACTION_TOGGLE_COMPRESSOR:
            case RecordingService.ACTION_TOGGLE_REVERB:
            case RecordingService.ACTION_TOGGLE_REVERB_EQ:
                RecordingService.toggleNotificationEffect(context, action);
                break;
            case RecordingService.ACTION_STOP_RECORDING:
            case RecordingService.ACTION_CANCEL_RECORDING:
                ContextCompat.startForegroundService(context,
                        new Intent(context, RecordingService.class).setAction(action));
                break;
            default:
                break;
        }
    }
}
