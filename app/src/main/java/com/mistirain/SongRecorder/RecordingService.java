package com.mistirain.SongRecorder;

import android.Manifest;
import android.app.ActivityOptions;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.io.File;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class RecordingService extends Service {

    private enum ControlState { IDLE, RECORDING, REVIEW }

    /** Callback for {@link #beginRecording}. */
    public interface StartCallback {
        void onReady(boolean success, String reason);
    }

    private static final String TAG = "RecordingService";

    public static final String CHANNEL_ID = "RecordingServiceChannel_v2";
    public static final int NOTIFICATION_ID = 1;
    public static final String PREFS_NAME = "SongRecorderPrefs";
    /** Runtime enable state for the current app process/session. */
    public static final String PREF_NOTIFICATION_ENABLED = "notification_enabled";

    /** Used by the visible app to keep the notification synchronized with its recorder. */
    public static final String ACTION_START_RECORDING = "com.mistirain.SongRecorder.START_RECORDING";
    public static final String ACTION_REVIEW_READY = "com.mistirain.SongRecorder.REVIEW_READY";
    public static final String ACTION_NOTIFICATION_START_RECORDING = "com.mistirain.SongRecorder.NOTIFICATION_START_RECORDING";

    /** Service-only action: begins capture without opening MainActivity. */
    public static final String ACTION_BACKGROUND_START_RECORDING = "com.mistirain.SongRecorder.BACKGROUND_START_RECORDING";
    public static final String ACTION_STOP_RECORDING = "com.mistirain.SongRecorder.NOTIFICATION_STOP_RECORDING";
    public static final String ACTION_CANCEL_RECORDING = "com.mistirain.SongRecorder.NOTIFICATION_CANCEL_RECORDING";
    public static final String ACTION_RESET_RECORDING = "com.mistirain.SongRecorder.RESET_RECORDING";
    public static final String ACTION_PLAYBACK_RESET = "com.mistirain.SongRecorder.NOTIFICATION_PLAYBACK_RESET";
    public static final String ACTION_PLAYBACK_PLAY = "com.mistirain.SongRecorder.NOTIFICATION_PLAYBACK_PLAY";
    public static final String ACTION_PLAYBACK_PAUSE = "com.mistirain.SongRecorder.NOTIFICATION_PLAYBACK_PAUSE";
    public static final String ACTION_PLAYBACK_TOGGLE = "com.mistirain.SongRecorder.NOTIFICATION_PLAYBACK_TOGGLE";
    public static final String ACTION_SAVE = "com.mistirain.SongRecorder.NOTIFICATION_SAVE";
    public static final String ACTION_SHARE = "com.mistirain.SongRecorder.NOTIFICATION_SHARE";

    public static final String ACTION_TOGGLE_GATE = "com.mistirain.SongRecorder.TOGGLE_GATE";
    public static final String ACTION_TOGGLE_EQ = "com.mistirain.SongRecorder.TOGGLE_EQ";
    public static final String ACTION_TOGGLE_COMPRESSOR = "com.mistirain.SongRecorder.TOGGLE_COMPRESSOR";
    public static final String ACTION_TOGGLE_REVERB = "com.mistirain.SongRecorder.TOGGLE_REVERB";
    public static final String ACTION_TOGGLE_REVERB_EQ = "com.mistirain.SongRecorder.TOGGLE_REVERB_EQ";

    private static final String EXTRA_FILE_PATH = "extra_file_path";
    private static final String EXTRA_DURATION_MS = "extra_duration_ms";

    private static volatile RecordingService runningInstance = null;
    private static final Object pendingStartLock = new Object();
    private static StartCallback pendingStartCallback = null;
    private static Runnable pendingStartTimeout = null;
    private static final Handler pendingStartHandler = new Handler(Looper.getMainLooper());

    // ====================================================================== static helpers

    static String formatClockTime(long milliseconds) {
        long totalSeconds = Math.max(milliseconds, 0L) / 1000L;
        String seconds = String.format(Locale.US, "%02d", totalSeconds % 60L);
        long totalMinutes = totalSeconds / 60L;
        if (totalMinutes >= 60L) {
            long hours = totalMinutes / 60L;
            String minutes = String.format(Locale.US, "%02d", totalMinutes % 60L);
            return hours + ":" + minutes + ":" + seconds;
        }
        return totalMinutes + ":" + seconds;
    }

    private static long clamp(long v, long min, long max) { return Math.max(min, Math.min(max, v)); }

    private static boolean isEffectToggle(String action) {
        return ACTION_TOGGLE_GATE.equals(action) || ACTION_TOGGLE_EQ.equals(action)
                || ACTION_TOGGLE_COMPRESSOR.equals(action) || ACTION_TOGGLE_REVERB.equals(action)
                || ACTION_TOGGLE_REVERB_EQ.equals(action);
    }

    private static void applyToggle(DspProcessor dsp, String action) {
        if (action == null) return;
        switch (action) {
            case ACTION_TOGGLE_GATE: dsp.gateEnabled = !dsp.gateEnabled; break;
            case ACTION_TOGGLE_EQ: dsp.eqEnabled = !dsp.eqEnabled; break;
            case ACTION_TOGGLE_COMPRESSOR: dsp.compEnabled = !dsp.compEnabled; break;
            case ACTION_TOGGLE_REVERB: dsp.reverbEnabled = !dsp.reverbEnabled; break;
            case ACTION_TOGGLE_REVERB_EQ: dsp.reverbEqEnabled = !dsp.reverbEqEnabled; break;
            default: break;
        }
    }

    private static long durationFromWavLength(long length) {
        return Math.max(((length - 44L) * 1000L) / (44100L * 2L), 1L);
    }

    private static String preferredFormat(SharedPreferences prefs) {
        String raw = prefs.getString("default_format", "m4a");
        String lower = raw == null ? "m4a" : raw.toLowerCase(Locale.US);
        return (lower.equals("m4a") || lower.equals("mp3") || lower.equals("wav")) ? lower : "m4a";
    }

    public static boolean isNotificationFeatureEnabled(Context context) {
        return context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(PREF_NOTIFICATION_ENABLED, false);
    }

    private static void setEnabledPref(Context appContext, boolean enabled) {
        appContext.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(PREF_NOTIFICATION_ENABLED, enabled)
                .apply();
    }

    /** Every clean app launch begins with notifications OFF; there is no persistent ON mode. */
    public static void prepareNotificationStateForAppLaunch(Context context) {
        Context appContext = context.getApplicationContext();
        setEnabledPref(appContext, false);
        applyNotificationSetting(appContext, false);
    }

    public static void enableNotificationForCurrentSession(Context context) {
        Context appContext = context.getApplicationContext();
        setEnabledPref(appContext, true);
        applyNotificationSetting(appContext, true);
    }

    public static void disableNotificationForCurrentSession(Context context) {
        Context appContext = context.getApplicationContext();
        setEnabledPref(appContext, false);
        applyNotificationSetting(appContext, false);
    }

    /** Persist OFF when the UI task exits; preserve a live capture's required FGS card. */
    public static void resetNotificationPreferenceForAppExit(Context context, boolean preserveActiveCapture) {
        Context appContext = context.getApplicationContext();
        setEnabledPref(appContext, false);
        if (!preserveActiveCapture) applyNotificationSetting(appContext, false);
    }

    /**
     * Start the service and report only after onStartCommand has either promoted it to a
     * microphone foreground service or rejected the start. startForegroundService() alone
     * is asynchronous and must not be treated as proof that microphone capture can begin.
     */
    public static void beginRecording(Context context, StartCallback onReady) {
        Context appContext = context.getApplicationContext();
        Runnable timeout = () -> completePendingRecordingStart(false,
                "서비스 준비 시간이 초과되었습니다. 다시 시도해주세요.");
        boolean accepted;
        synchronized (pendingStartLock) {
            if (pendingStartCallback != null) {
                accepted = false;
            } else {
                pendingStartCallback = onReady;
                pendingStartTimeout = timeout;
                accepted = true;
            }
        }
        if (!accepted) {
            onReady.onReady(false, "이미 녹음 서비스를 준비하고 있습니다.");
            return;
        }
        pendingStartHandler.postDelayed(timeout, 4500L);
        try {
            ContextCompat.startForegroundService(appContext,
                    new Intent(appContext, RecordingService.class).setAction(ACTION_START_RECORDING));
        } catch (Exception e) {
            Log.e(TAG, "Unable to request microphone foreground service", e);
            completePendingRecordingStart(false,
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    private static boolean hasPendingRecordingStart() {
        synchronized (pendingStartLock) {
            return pendingStartCallback != null;
        }
    }

    private static boolean completePendingRecordingStart(boolean success, String reason) {
        StartCallback callback;
        Runnable timeout;
        synchronized (pendingStartLock) {
            callback = pendingStartCallback;
            timeout = pendingStartTimeout;
            pendingStartCallback = null;
            pendingStartTimeout = null;
        }
        if (timeout != null) pendingStartHandler.removeCallbacks(timeout);
        if (callback == null) return false;
        try {
            callback.onReady(success, reason);
        } catch (Exception e) {
            Log.e(TAG, "Foreground-start callback failed", e);
        }
        return true;
    }

    /** Describes OS-level notification blocks that the app's own switch cannot override. */
    public static String notificationBlockReason(Context context) {
        Context appContext = context.getApplicationContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return "Android 알림 권한이 허용되지 않았습니다.";
        }
        if (!NotificationManagerCompat.from(appContext).areNotificationsEnabled()) {
            return "앱 전체 알림이 꺼져 있습니다.";
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = appContext.getSystemService(NotificationManager.class)
                    .getNotificationChannel(CHANNEL_ID);
            if (channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_NONE) {
                return "녹음 및 재생 컨트롤 알림 채널이 차단되어 있습니다.";
            }
        }
        return null;
    }

    /** Re-posts the appropriate notification when the app returns to the foreground. */
    public static void restoreNotificationIfEnabled(Context context) {
        Context appContext = context.getApplicationContext();
        RecordingService service = runningInstance;
        if (!isNotificationFeatureEnabled(appContext)) {
            if (service == null || (!service.isRecording && service.recordingFilePath == null)) {
                cancelNotification(appContext);
            }
            return;
        }
        if (service == null) {
            showIdleNotification(appContext);
        } else {
            service.mainHandler.post(() -> {
                if (service.isRecording || service.recordingFilePath != null) {
                    service.updateNotification();
                } else {
                    showIdleNotification(appContext);
                }
            });
        }
    }

    /** True only while microphone capture is actually running. */
    public static boolean isActivelyRecording() {
        RecordingService s = runningInstance;
        return s != null && s.isRecording;
    }

    /** True while a recording or its review take is owned by the service. */
    public static boolean hasActiveRecordingOrReviewSession() {
        RecordingService service = runningInstance;
        if (service == null) return false;
        return service.isRecording || service.recordingFilePath != null;
    }

    /**
     * A fresh app launch discards an abandoned unsaved review take, but never interrupts
     * an actively running microphone capture or a take accessed through a notification action.
     */
    public static void resetTransientTakeForFreshAppStart(Context context) {
        Context appContext = context.getApplicationContext();
        RecordingService service = runningInstance;
        if (service == null) {
            if (isNotificationFeatureEnabled(appContext)) showIdleNotification(appContext);
            else cancelNotification(appContext);
            return;
        }
        Runnable reset = () -> {
            // A real capture always wins over a request to clean abandoned review state.
            if (service.isRecording) return;
            AppAudioRuntime.get(appContext).audioEngine.stopPreview();
            service.isPlaying = false;
            service.recordingFilePath = null;
            service.currentPositionMs = 0L;
            service.durationMs = 0L;
            service.stopForeground(STOP_FOREGROUND_REMOVE);
            service.notificationManager.cancel(NOTIFICATION_ID);
            service.stopSelf();
            if (isNotificationFeatureEnabled(appContext)) showIdleNotification(appContext);
            AppAudioRuntime.bumpSessionRevision();
        };
        // MainActivity calls this from onCreate. Run inline on the UI thread so the recorder
        // screen cannot race and restore the take being discarded.
        if (Looper.myLooper() == service.mainHandler.getLooper()) reset.run();
        else service.mainHandler.post(reset);
    }

    /** Apply an expanded-notification effect toggle without starting a background service.
     * Android may reject ordinary background-service starts when only an idle notification
     * exists, so these lightweight UI actions are handled directly by the receiver.
     */
    public static void toggleNotificationEffect(Context context, String action) {
        Context appContext = context.getApplicationContext();
        if (!isEffectToggle(action)) return;

        AppAudioRuntime.Runtime runtime = AppAudioRuntime.get(appContext);
        if (!AppAudioRuntime.shouldUseCurrentSettingsForNotificationRecording(appContext)
                && !isActivelyRecording()) {
            EffectPresetStore.resetProcessorToDefault(appContext, runtime.dsp);
            AppAudioRuntime.bumpEffectRevision();
        }
        applyToggle(runtime.dsp, action);
        AppAudioRuntime.markNotificationEffectTouched(appContext);
        refreshNotificationState(appContext);
    }

    public static void applyNotificationSetting(Context context, boolean enabled) {
        Context appContext = context.getApplicationContext();
        RecordingService service = runningInstance;
        if (service == null) {
            if (enabled) showIdleNotification(appContext);
            else cancelNotification(appContext);
            return;
        }

        service.mainHandler.post(() -> {
            if (service.isRecording) {
                service.updateNotification();
            } else if (enabled && service.recordingFilePath != null) {
                service.updateNotification();
            } else {
                service.stopForeground(STOP_FOREGROUND_REMOVE);
                service.notificationManager.cancel(NOTIFICATION_ID);
                service.stopSelf();
                if (enabled) showIdleNotification(appContext);
                else cancelNotification(appContext);
            }
        });
    }

    /** Refreshes labels/toggle states after the visible editor changes DSP controls. */
    public static void refreshNotificationState(Context context) {
        Context appContext = context.getApplicationContext();
        if (!isNotificationFeatureEnabled(appContext)) return;
        // Until the app has performed an in-app recording, notification mode is explicitly
        // based on the selected default preset (plus changes made directly in notification).
        if (!AppAudioRuntime.shouldUseCurrentSettingsForNotificationRecording(appContext)
                && !isActivelyRecording()) {
            EffectPresetStore.resetProcessorToDefault(appContext, AppAudioRuntime.get(appContext).dsp);
            AppAudioRuntime.bumpEffectRevision();
        }
        RecordingService service = runningInstance;
        if (service == null) {
            showIdleNotification(appContext);
        } else {
            service.mainHandler.post(() -> {
                if (!service.isRecording && service.recordingFilePath != null) {
                    service.prepareReviewShareFile();
                } else {
                    service.updateNotification();
                }
            });
        }
    }

    public static void showReviewNotification(Context context, File wavFile, long durationMs) {
        Context appContext = context.getApplicationContext();
        if (!isNotificationFeatureEnabled(appContext)) {
            RecordingService service = runningInstance;
            if (service != null) {
                service.mainHandler.post(() -> {
                    service.stopForeground(STOP_FOREGROUND_REMOVE);
                    service.notificationManager.cancel(NOTIFICATION_ID);
                    service.stopSelf();
                });
            } else {
                cancelNotification(appContext);
            }
            return;
        }

        RecordingService service = runningInstance;
        if (service != null) {
            service.mainHandler.post(() -> {
                String path = wavFile.getAbsolutePath();
                if (service.isRecording || !path.equals(service.recordingFilePath)) {
                    service.enterReviewState(path, durationMs);
                } else {
                    service.refreshReviewState(path, durationMs);
                }
            });
        } else {
            ContextCompat.startForegroundService(appContext,
                    new Intent(appContext, RecordingService.class)
                            .setAction(ACTION_REVIEW_READY)
                            .putExtra(EXTRA_FILE_PATH, wavFile.getAbsolutePath())
                            .putExtra(EXTRA_DURATION_MS, durationMs));
        }
    }

    public static void returnToIdleNotification(Context context) {
        Context appContext = context.getApplicationContext();
        RecordingService service = runningInstance;
        if (service == null) {
            if (isNotificationFeatureEnabled(appContext)) showIdleNotification(appContext);
            else cancelNotification(appContext);
            AppAudioRuntime.bumpEffectRevision();
            AppAudioRuntime.bumpSessionRevision();
            return;
        }
        service.mainHandler.post(() -> {
            service.stopRecordingTimer();
            service.isRecording = false;
            service.isStopping = false;
            service.isPlaying = false;
            service.recordingFilePath = null;
            service.currentPositionMs = 0L;
            service.durationMs = 0L;
            service.stopForeground(STOP_FOREGROUND_REMOVE);
            service.notificationManager.cancel(NOTIFICATION_ID);
            service.stopSelf();
            if (isNotificationFeatureEnabled(appContext)) showIdleNotification(appContext);
            AppAudioRuntime.bumpSessionRevision();
        });
    }

    public static void showIdleNotification(Context context) {
        showIdleNotification(context, false);
    }

    /** Posts a standard, system-rendered notification for the current session. */
    public static void showIdleNotification(Context context, boolean fromBoot) {
        Context appContext = context.getApplicationContext();
        if (!isNotificationFeatureEnabled(appContext)) {
            cancelNotification(appContext);
            return;
        }
        String status = fromBoot ? "준비됨 · 눌러서 녹음 시작" : "녹음 대기 · 버튼으로 바로 시작";
        Notification notification;
        try {
            notification = buildControlNotification(appContext, ControlState.IDLE, status, false, 0L, 0L, null);
        } catch (Exception e) {
            Log.e(TAG, "Idle notification construction failed", e);
            try {
                notification = buildNativeFallbackNotification(appContext, ControlState.IDLE, status, false);
            } catch (Exception fallbackError) {
                Log.e(TAG, "Native idle notification construction also failed", fallbackError);
                return;
            }
        }
        String blockReason = notificationBlockReason(appContext);
        if (blockReason != null) {
            Log.w(TAG, "Idle notification not posted: " + blockReason);
            return;
        }
        try {
            NotificationManagerCompat.from(appContext).notify(NOTIFICATION_ID, notification);
            Log.i(TAG, "Idle notification posted; channel=" + CHANNEL_ID);
        } catch (Exception e) {
            Log.e(TAG, "Failed to post idle notification", e);
        }
    }

    private static void cancelNotification(Context context) {
        try {
            NotificationManagerCompat.from(context.getApplicationContext()).cancel(NOTIFICATION_ID);
        } catch (SecurityException ignored) { }
    }

    private static void ensureNotificationChannel(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "녹음 및 재생 컨트롤",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("녹음, 이펙트 토글, 미리 듣기, 저장 및 공유를 제공합니다.");
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    public static void updatePlaybackPosition(boolean isPlaying, long positionMs, long durationMs) {
        RecordingService service = runningInstance;
        if (service == null) return;

        service.mainHandler.post(() -> {
            if (service.resettingPlayback || service.recordingFilePath == null) return;
            service.durationMs = Math.max(durationMs, 0L);
            service.currentPositionMs = clamp(positionMs, 0L, Math.max(service.durationMs, 0L));
            service.isPlaying = isPlaying;

            // AudioEngine reports the playhead roughly every 250 ms. Refresh the existing
            // notification at a lower rate.
            long now = SystemClock.elapsedRealtime();
            if (!service.isRecording && service.recordingFilePath != null
                    && now - service.lastPlaybackNotificationUpdateElapsedMs >= 500L) {
                service.lastPlaybackNotificationUpdateElapsedMs = now;
                service.updateNotification();
            }
        });
    }

    public static void updatePlaybackUiState(boolean isPlaying, long positionMs, long durationMs) {
        RecordingService service = runningInstance;
        if (service == null) return;

        service.mainHandler.post(() -> {
            if (service.resettingPlayback || service.recordingFilePath == null) return;
            service.durationMs = Math.max(durationMs, 0L);
            service.currentPositionMs = clamp(positionMs, 0L, Math.max(service.durationMs, 0L));
            service.isPlaying = isPlaying;
            service.updateNotification();
        });
    }

    private static NotificationCompat.Builder baseBuilder(Context context, String status) {
        return new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_record_circle)
                .setContentTitle("송레코더")
                .setContentText(status)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(openAppPendingIntent(context))
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setAutoCancel(false)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE);
    }

    private static Notification buildControlNotification(
            Context context,
            ControlState state,
            String status,
            boolean isPlaying,
            long positionMs,
            long durationMs,
            PendingIntent reviewSharePendingIntent
    ) {
        ensureNotificationChannel(context);

        RecordingService instance = runningInstance;
        if (state == ControlState.REVIEW && instance != null) {
            return instance.buildReviewMediaNotification(status);
        }
        if (instance != null && instance.mediaSession != null) instance.mediaSession.setActive(false);

        // Keep every state in the same app-owned notification (same ID and layout family).
        NotificationCompat.Builder builder = baseBuilder(context, status);

        // The custom layouts own all buttons, preventing duplicate standard action rows.
        try {
            builder.setCustomContentView(makeControlRemoteViews(context, state, status, isPlaying, false,
                            positionMs, durationMs, reviewSharePendingIntent))
                    .setCustomBigContentView(makeControlRemoteViews(context, state, status, isPlaying, true,
                            positionMs, durationMs, reviewSharePendingIntent))
                    .setStyle(new NotificationCompat.DecoratedCustomViewStyle());
        } catch (RuntimeException e) {
            Log.e(TAG, "Custom notification views unavailable; using native actions for " + state, e);
            throw e;
        }
        return builder.build();
    }

    private static Notification buildNativeFallbackNotification(
            Context context, ControlState state, String status, boolean isPlaying) {
        ensureNotificationChannel(context);
        NotificationCompat.Builder builder = baseBuilder(context, status);

        switch (state) {
            case IDLE:
                builder.addAction(new NotificationCompat.Action.Builder(R.drawable.ic_record_circle, "녹음 시작",
                        servicePendingIntent(context, ACTION_BACKGROUND_START_RECORDING, 110, true)).build());
                break;
            case RECORDING:
                builder.addAction(new NotificationCompat.Action.Builder(R.drawable.ic_record_pause, "녹음 정지",
                        servicePendingIntent(context, ACTION_STOP_RECORDING, 101, false)).build());
                builder.addAction(new NotificationCompat.Action.Builder(R.drawable.ic_record_cancel, "녹음 취소",
                        servicePendingIntent(context, ACTION_CANCEL_RECORDING, 105, false)).build());
                break;
            case REVIEW: {
                String playAction = isPlaying ? ACTION_PLAYBACK_PAUSE : ACTION_PLAYBACK_PLAY;
                String playLabel = isPlaying ? "일시정지" : "재생";
                builder.addAction(new NotificationCompat.Action.Builder(
                        isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        playLabel, servicePendingIntent(context, playAction, 102, false)).build());
                builder.addAction(new NotificationCompat.Action.Builder(R.drawable.ic_record_stop, "녹음 초기화",
                        servicePendingIntent(context, ACTION_RESET_RECORDING, 106, false)).build());
                builder.addAction(new NotificationCompat.Action.Builder(android.R.drawable.ic_menu_save, "저장",
                        servicePendingIntent(context, ACTION_SAVE, 103, false)).build());
                builder.addAction(new NotificationCompat.Action.Builder(android.R.drawable.ic_menu_share, "공유",
                        servicePendingIntent(context, ACTION_SHARE, 104, false)).build());
                break;
            }
        }
        return builder.build();
    }

    private static RemoteViews makeControlRemoteViews(
            Context context,
            ControlState state,
            String status,
            boolean isPlaying,
            boolean expanded,
            long positionMs,
            long durationMs,
            PendingIntent reviewSharePendingIntent
    ) {
        int layoutId = expanded ? R.layout.notification_controls_expanded : R.layout.notification_controls_compact;
        RemoteViews views = new RemoteViews(context.getPackageName(), layoutId);
        views.setTextViewText(R.id.notification_status, status);

        int primaryIcon;
        String primaryLabel;
        PendingIntent primaryIntent;
        int secondaryIcon;
        String secondaryLabel;
        PendingIntent secondaryIntent;
        boolean secondaryEnabled;

        switch (state) {
            case RECORDING:
                primaryIcon = R.drawable.ic_record_pause;
                primaryLabel = "녹음 정지";
                primaryIntent = servicePendingIntent(context, ACTION_STOP_RECORDING, 101, false);
                secondaryIcon = R.drawable.ic_record_cancel;
                secondaryLabel = "녹음 취소";
                secondaryIntent = servicePendingIntent(context, ACTION_CANCEL_RECORDING, 105, false);
                secondaryEnabled = true;
                break;
            case REVIEW:
                primaryIcon = isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play;
                primaryLabel = isPlaying ? "일시정지" : "미리 듣기";
                primaryIntent = servicePendingIntent(context,
                        isPlaying ? ACTION_PLAYBACK_PAUSE : ACTION_PLAYBACK_PLAY, 102, false);
                secondaryIcon = R.drawable.ic_record_stop;
                secondaryLabel = "녹음 초기화";
                secondaryIntent = servicePendingIntent(context, ACTION_RESET_RECORDING, 106, false);
                secondaryEnabled = true;
                break;
            case IDLE:
            default:
                primaryIcon = R.drawable.ic_record_circle;
                primaryLabel = "녹음 시작";
                primaryIntent = servicePendingIntent(context, ACTION_BACKGROUND_START_RECORDING, 110, true);
                secondaryIcon = R.drawable.ic_record_stop;
                secondaryLabel = "중단";
                secondaryIntent = null;
                secondaryEnabled = false;
                break;
        }

        views.setImageViewResource(R.id.action_primary_icon, primaryIcon);
        views.setTextViewText(R.id.action_primary_label, primaryLabel);
        views.setOnClickPendingIntent(R.id.action_primary, primaryIntent);
        views.setImageViewResource(R.id.action_secondary_icon, secondaryIcon);
        views.setTextViewText(R.id.action_secondary_label, secondaryLabel);
        // Avoid RemoteViews reflective calls such as setClickable/setEnabled on
        // notification-optimized layouts.
        boolean showSecondary = secondaryEnabled && secondaryIntent != null;
        views.setViewVisibility(R.id.action_secondary, showSecondary ? View.VISIBLE : View.GONE);
        if (showSecondary) views.setOnClickPendingIntent(R.id.action_secondary, secondaryIntent);

        if (expanded) {
            RecordingService instance = runningInstance;
            boolean showReviewProgress = state == ControlState.REVIEW && (instance == null || instance.mediaSession == null);
            views.setViewVisibility(R.id.review_progress_row, showReviewProgress ? View.VISIBLE : View.GONE);
            if (showReviewProgress) {
                long safeDuration = Math.max(durationMs, 0L);
                long safePosition = clamp(positionMs, 0L, safeDuration);
                int progress = safeDuration > 0L
                        ? (int) clamp((safePosition * 1000L) / safeDuration, 0L, 1000L) : 0;
                views.setProgressBar(R.id.review_progress, 1000, progress, false);
                views.setTextViewText(R.id.review_position_time, formatClockTime(safePosition));
                views.setTextViewText(R.id.review_duration_time, formatClockTime(safeDuration));
            }

            DspProcessor dsp = AppAudioRuntime.get(context).dsp;
            views.setTextViewText(R.id.effect_gate, "게이트 " + (dsp.gateEnabled ? "ON" : "OFF"));
            views.setTextViewText(R.id.effect_eq, "EQ " + (dsp.eqEnabled ? "ON" : "OFF"));
            views.setTextViewText(R.id.effect_compressor, "컴프 " + (dsp.compEnabled ? "ON" : "OFF"));
            views.setTextViewText(R.id.effect_reverb, "리버브 " + (dsp.reverbEnabled ? "ON" : "OFF"));
            views.setTextViewText(R.id.effect_reverb_eq, "리버브 EQ " + (dsp.reverbEqEnabled ? "ON" : "OFF"));
            views.setOnClickPendingIntent(R.id.effect_gate, effectTogglePendingIntent(context, ACTION_TOGGLE_GATE, 201));
            views.setOnClickPendingIntent(R.id.effect_eq, effectTogglePendingIntent(context, ACTION_TOGGLE_EQ, 202));
            views.setOnClickPendingIntent(R.id.effect_compressor, effectTogglePendingIntent(context, ACTION_TOGGLE_COMPRESSOR, 203));
            views.setOnClickPendingIntent(R.id.effect_reverb, effectTogglePendingIntent(context, ACTION_TOGGLE_REVERB, 204));
            views.setOnClickPendingIntent(R.id.effect_reverb_eq, effectTogglePendingIntent(context, ACTION_TOGGLE_REVERB_EQ, 205));
            views.setViewVisibility(R.id.review_actions_row, state == ControlState.REVIEW ? View.VISIBLE : View.GONE);
        }
        if (expanded && state == ControlState.REVIEW) {
            // Save is a service command and never opens the editor or a dialog.
            views.setOnClickPendingIntent(R.id.review_save, servicePendingIntent(context, ACTION_SAVE, 103, false));
            // Use a direct external Activity PendingIntent only after the share file has been rendered.
            if (reviewSharePendingIntent != null) {
                views.setTextViewText(R.id.review_share, "카톡 공유");
                views.setViewVisibility(R.id.review_share, View.VISIBLE);
                views.setOnClickPendingIntent(R.id.review_share, reviewSharePendingIntent);
            } else {
                views.setViewVisibility(R.id.review_share, View.GONE);
            }
        }
        return views;
    }

    private static PendingIntent servicePendingIntent(Context context, String action, int requestCode, boolean foreground) {
        Intent intent = new Intent(context, RecordingService.class).setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        return foreground
                ? PendingIntent.getForegroundService(context, requestCode, intent, flags)
                : PendingIntent.getService(context, requestCode, intent, flags);
    }

    private static PendingIntent effectTogglePendingIntent(Context context, String action, int requestCode) {
        Intent intent = new Intent(context, RecordingNotificationActionReceiver.class).setAction(action);
        return PendingIntent.getBroadcast(context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /**
     * Opens the external share target directly from SystemUI. This Intent deliberately
     * does not target MainActivity, so tapping Share never foregrounds SongRecorder.
     */
    private static PendingIntent preparedShareActivityPendingIntent(Context context, File shareFile) {
        if (!shareFile.exists() || shareFile.length() <= 0L) return null;
        try {
            Uri contentUri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", shareFile);
            Intent baseIntent = new Intent(Intent.ACTION_SEND_MULTIPLE);
            baseIntent.setType("*/*");
            ArrayList<Uri> uris = new ArrayList<>();
            uris.add(contentUri);
            baseIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            baseIntent.setClipData(ClipData.newRawUri("", contentUri));
            baseIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent kakaoIntent = new Intent(baseIntent).setPackage("com.kakao.talk");
            Intent targetIntent = kakaoIntent.resolveActivity(context.getPackageManager()) != null
                    ? kakaoIntent
                    : Intent.createChooser(baseIntent, "음성 메시지 공유");
            ActivityOptions creatorOptions = ActivityOptions.makeBasic();
            if (Build.VERSION.SDK_INT >= 35) {
                // targetSdk 35+ must explicitly delegate creator BAL privileges.
                creatorOptions.setPendingIntentCreatorBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
            }
            int requestCode = 3000 + (shareFile.getName().hashCode() & 0x3FFF);
            return PendingIntent.getActivity(context, requestCode, targetIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE,
                    creatorOptions.toBundle());
        } catch (Exception e) {
            Log.e(TAG, "Unable to prepare direct Kakao share action", e);
            return null;
        }
    }

    private static PendingIntent openAppPendingIntent(Context context) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(context, 100, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    // ====================================================================== instance state

    final Handler mainHandler = new Handler(Looper.getMainLooper());
    private NotificationManager notificationManager;
    private boolean isRecording = false;
    private boolean isStopping = false;
    private boolean isPlaying = false;
    private String recordingFilePath = null;
    private long durationMs = 0L;
    private long currentPositionMs = 0L;
    private long recordingStartedAtElapsedMs = 0L;
    private long lastPlaybackNotificationUpdateElapsedMs = 0L;
    private MediaSession mediaSession = null;
    /** Serial executor: replaces the share-render Mutex (one render at a time). */
    private final ExecutorService shareExecutor = Executors.newSingleThreadExecutor();
    /** General background work (save/reset I/O). */
    private final ExecutorService ioExecutor = Executors.newCachedThreadPool();
    private String actionFeedbackMessage = null;
    private Runnable actionFeedbackReset = null;
    private boolean isSavingTake = false;
    private long sharePreparationGeneration = 0L;
    private Future<?> sharePreparationJob = null;
    private File preparedShareFile = null;
    private PendingIntent reviewSharePendingIntent = null;
    private boolean launchShareWhenPrepared = false;
    private volatile boolean resettingPlayback = false;
    private boolean destroyed = false;

    private final Runnable recordingTimerTick = new Runnable() {
        @Override
        public void run() {
            if (isRecording && !isStopping) {
                updateNotification();
                mainHandler.postDelayed(this, 1000L);
            }
        }
    };

    private void stopRecordingTimer() {
        mainHandler.removeCallbacks(recordingTimerTick);
        recordingStartedAtElapsedMs = 0L;
    }

    /** Posts to the main thread unless the service has already been destroyed. */
    private void postMain(Runnable r) {
        mainHandler.post(() -> {
            if (!destroyed) r.run();
        });
    }

    @Override
    public void onCreate() {
        super.onCreate();
        runningInstance = this;
        notificationManager = getSystemService(NotificationManager.class);
        ensureNotificationChannel(this);
        MediaSession session = new MediaSession(this, "SongRecorderReview");
        session.setSessionActivity(openAppPendingIntent(this));
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { handlePlaybackAction(ACTION_PLAYBACK_PLAY); }
            @Override public void onPause() { handlePlaybackAction(ACTION_PLAYBACK_PAUSE); }

            @Override
            public void onSeekTo(long pos) {
                if (durationMs <= 0L || isStopping) return;
                AudioEngine engine = AppAudioRuntime.get(getApplicationContext()).audioEngine;
                currentPositionMs = clamp(pos, 0L, durationMs);
                engine.seekToGlobalRatio((float) currentPositionMs / durationMs);
                updateNotification();
            }

            @Override
            public void onCustomAction(String action, Bundle extras) {
                if (ACTION_RESET_RECORDING.equals(action)) resetReviewRecording();
                else if (ACTION_SAVE.equals(action)) saveReviewTake();
                else if (ACTION_SHARE.equals(action)) handleShareAction();
                else if (isEffectToggle(action)) toggleNotificationEffect(getApplicationContext(), action);
            }
        }, mainHandler);
        mediaSession = session;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (action == null) return START_NOT_STICKY;
        switch (action) {
            case ACTION_START_RECORDING: {
                boolean hadRequest = hasPendingRecordingStart();
                boolean ready = hadRequest && enterRecordingState();
                boolean delivered = completePendingRecordingStart(ready, ready ? null
                        : "Android가 마이크 포그라운드 서비스를 허용하지 않았습니다. 앱 화면에서 다시 시도해주세요.");
                if (!ready || !delivered) {
                    try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Exception ignored) { }
                    try { notificationManager.cancel(NOTIFICATION_ID); } catch (Exception ignored) { }
                    stopSelfResult(startId);
                }
                break;
            }
            case ACTION_BACKGROUND_START_RECORDING:
                startNotificationRecording(startId);
                break;
            case ACTION_STOP_RECORDING:
                if (isRecording) stopNotificationRecording(); else stopSelfResult(startId);
                break;
            case ACTION_CANCEL_RECORDING:
                if (isRecording) cancelNotificationRecording(); else stopSelfResult(startId);
                break;
            case ACTION_REVIEW_READY:
                enterReviewState(intent.getStringExtra(EXTRA_FILE_PATH), intent.getLongExtra(EXTRA_DURATION_MS, 0L));
                break;
            case ACTION_RESET_RECORDING:
                resetReviewRecording();
                break;
            case ACTION_PLAYBACK_PLAY:
            case ACTION_PLAYBACK_PAUSE:
            case ACTION_PLAYBACK_TOGGLE:
            case ACTION_PLAYBACK_RESET:
                ensureReviewSessionFromTempTake();
                handlePlaybackAction(action);
                break;
            case ACTION_SAVE:
                ensureReviewSessionFromTempTake();
                saveReviewTake();
                break;
            case ACTION_SHARE:
                ensureReviewSessionFromTempTake();
                handleShareAction();
                break;
            case ACTION_TOGGLE_GATE:
            case ACTION_TOGGLE_EQ:
            case ACTION_TOGGLE_COMPRESSOR:
            case ACTION_TOGGLE_REVERB:
            case ACTION_TOGGLE_REVERB_EQ:
                handleEffectToggle(action, startId);
                break;
            default:
                break;
        }
        return START_NOT_STICKY;
    }

    private void startNotificationRecording(int startId) {
        if (isStopping) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            showPermissionRequiredNotification();
            stopSelf(startId);
            return;
        }
        if (!AppAudioRuntime.shouldUseCurrentSettingsForNotificationRecording(this)) {
            EffectPresetStore.resetProcessorToDefault(this, AppAudioRuntime.get(this).dsp);
            AppAudioRuntime.bumpEffectRevision();
        }
        AppAudioRuntime.Runtime runtime = AppAudioRuntime.get(this);
        File rawFile = AppAudioRuntime.rawRecordingFile(this);
        if (runtime.audioEngine.isRecordingActive()) {
            if (!enterRecordingState()) {
                stopSelf(startId);
                return;
            }
            AppAudioRuntime.bumpSessionRevision();
            return;
        }
        if (isSavingTake) {
            showActionFeedback("저장 완료 후 다시 녹음해주세요.");
            return;
        }
        sharePreparationGeneration += 1L;
        if (sharePreparationJob != null) sharePreparationJob.cancel(true);
        reviewSharePendingIntent = null;
        preparedShareFile = null;
        runtime.audioEngine.stopPreview();
        runtime.audioEngine.seekToGlobalRatio(0f);
        if (mediaSession != null) mediaSession.setActive(false);
        //noinspection ResultOfMethodCallIgnored
        rawFile.delete();
        //noinspection ResultOfMethodCallIgnored
        new File(getCacheDir(), "temp_pcm.pcm").delete();
        // Must promote to a microphone foreground service before AudioRecord begins.
        if (!enterRecordingState()) {
            stopSelf(startId);
            return;
        }
        runtime.audioEngine.startRecording(getApplicationContext(), rawFile, amp -> { }, false,
                message -> postMain(this::returnToIdleAfterFailure));
        if (!runtime.audioEngine.isRecordingActive()) {
            isRecording = false;
            returnToIdleAfterFailure();
            return;
        }
        AppAudioRuntime.bumpSessionRevision();
    }

    private boolean enterRecordingState() {
        isStopping = false;
        isPlaying = false;
        recordingFilePath = null;
        currentPositionMs = 0L;
        durationMs = 0L;
        stopRecordingTimer();
        recordingStartedAtElapsedMs = SystemClock.elapsedRealtime();
        try {
            String recordingStatus = "녹음 중 " + formatClockTime(0L);
            Notification notification;
            try {
                notification = buildControlNotification(this, ControlState.RECORDING, recordingStatus, false, 0L, 0L, null);
            } catch (Exception e) {
                Log.e(TAG, "Custom recording notification failed; using native actions", e);
                notification = buildNativeFallbackNotification(this, ControlState.RECORDING, recordingStatus, false);
            }
            // Promote to a microphone foreground service before AudioRecord begins.
            startMicrophoneForeground(notification);
            isRecording = true;
            mainHandler.postDelayed(recordingTimerTick, 1000L);
            try { updateNotification(); } catch (Exception ignored) { }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to promote microphone foreground service", e);
            isRecording = false;
            stopRecordingTimer();
            try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Exception ignored) { }
            try { notificationManager.cancel(NOTIFICATION_ID); } catch (Exception ignored) { }
            return false;
        }
    }

    private void stopNotificationRecording() {
        if (!isRecording || isStopping) return;
        isStopping = true;
        stopRecordingTimer();
        updateNotification();
        new Thread(() -> {
            AppAudioRuntime.Runtime runtime = AppAudioRuntime.get(getApplicationContext());
            runtime.audioEngine.stopRecording();
            File file = AppAudioRuntime.rawRecordingFile(getApplicationContext());
            long duration = (file.exists() && file.length() > 44L) ? durationFromWavLength(file.length()) : 0L;
            postMain(() -> {
                isStopping = false;
                isRecording = false;
                if (file.exists() && file.length() > 44L && duration > 0L) {
                    if (isNotificationFeatureEnabled(this)) {
                        enterReviewState(file.getAbsolutePath(), duration);
                    } else {
                        // The microphone FGS notification is required during capture, but the
                        // review/playback control card is not retained after the user turned it OFF.
                        recordingFilePath = null;
                        isPlaying = false;
                        stopForeground(STOP_FOREGROUND_REMOVE);
                        notificationManager.cancel(NOTIFICATION_ID);
                        stopSelf();
                    }
                } else {
                    returnToIdleAfterFailure();
                }
                AppAudioRuntime.bumpSessionRevision();
            });
        }, "notification-recording-stop").start();
    }

    private void cancelNotificationRecording() {
        if (isStopping) return;
        isStopping = true;
        stopRecordingTimer();
        updateNotification();
        new Thread(() -> {
            AppAudioRuntime.Runtime runtime = AppAudioRuntime.get(getApplicationContext());
            if (runtime.audioEngine.isRecordingActive()) runtime.audioEngine.stopRecording();
            runtime.audioEngine.stopPreview();
            //noinspection ResultOfMethodCallIgnored
            AppAudioRuntime.rawRecordingFile(getApplicationContext()).delete();
            //noinspection ResultOfMethodCallIgnored
            new File(getCacheDir(), "temp_pcm.pcm").delete();
            postMain(() -> {
                stopRecordingTimer();
                isRecording = false;
                isStopping = false;
                isPlaying = false;
                recordingFilePath = null;
                durationMs = 0L;
                currentPositionMs = 0L;
                stopForeground(STOP_FOREGROUND_REMOVE);
                notificationManager.cancel(NOTIFICATION_ID);
                AppAudioRuntime.bumpSessionRevision();
                if (isNotificationFeatureEnabled(this)) showIdleNotification(this);
                stopSelf();
            });
        }, "notification-recording-cancel").start();
    }

    private void returnToIdleAfterFailure() {
        stopRecordingTimer();
        isRecording = false;
        isStopping = false;
        recordingFilePath = null;
        isPlaying = false;
        stopForeground(STOP_FOREGROUND_REMOVE);
        notificationManager.cancel(NOTIFICATION_ID);
        if (isNotificationFeatureEnabled(this)) showIdleNotification(this);
        stopSelf();
    }

    private void showPermissionRequiredNotification() {
        ensureNotificationChannel(this);
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_record_circle)
                .setContentTitle("송레코더")
                .setContentText("마이크 권한이 필요합니다. 앱을 열어 녹음 권한을 허용하세요.")
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(openAppPendingIntent(this))
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build();
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification);
        } catch (Exception e) {
            Log.e(TAG, "Unable to show microphone-permission notice", e);
        }
    }

    private void ensureReviewSessionFromTempTake() {
        if (recordingFilePath != null) return;
        File file = AppAudioRuntime.rawRecordingFile(getApplicationContext());
        if (!file.exists() || file.length() <= 44L) return;
        enterReviewState(file.getAbsolutePath(), durationFromWavLength(file.length()));
    }

    private File getReviewFile() {
        File file = recordingFilePath != null ? new File(recordingFilePath)
                : AppAudioRuntime.rawRecordingFile(getApplicationContext());
        if (!file.exists() || file.length() <= 44L) return null;
        if (recordingFilePath == null) recordingFilePath = file.getAbsolutePath();
        if (durationMs <= 0L) durationMs = durationFromWavLength(file.length());
        return file;
    }

    private void setPersistentActionFeedback(String message) {
        if (actionFeedbackReset != null) mainHandler.removeCallbacks(actionFeedbackReset);
        actionFeedbackReset = null;
        actionFeedbackMessage = message;
        updateNotification();
    }

    private void showActionFeedback(String message) {
        showActionFeedback(message, 2600L);
    }

    private void showActionFeedback(String message, long feedbackDurationMs) {
        if (actionFeedbackReset != null) mainHandler.removeCallbacks(actionFeedbackReset);
        actionFeedbackMessage = message;
        Runnable reset = new Runnable() {
            @Override
            public void run() {
                actionFeedbackMessage = null;
                actionFeedbackReset = null;
                updateNotification();
            }
        };
        actionFeedbackReset = reset;
        updateNotification();
        mainHandler.postDelayed(reset, feedbackDurationMs);
    }

    /** Pre-render the current effect chain so the notification Share action can launch the
     * external KakaoTalk activity directly via a notification-owned Activity PendingIntent. */
    private void prepareReviewShareFile() {
        if (isRecording || isStopping) return;
        File inputFile = getReviewFile();
        if (inputFile == null) {
            preparedShareFile = null;
            reviewSharePendingIntent = null;
            updateNotification();
            return;
        }

        sharePreparationGeneration += 1L;
        final long generation = sharePreparationGeneration;
        Future<?> previousShareJob = sharePreparationJob;
        if (previousShareJob != null) previousShareJob.cancel(true);
        preparedShareFile = null;
        reviewSharePendingIntent = null;
        launchShareWhenPrepared = false;
        setPersistentActionFeedback("카톡 공유 준비 중…");

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        final String format = preferredFormat(prefs);
        final EffectChainPreset dspSnapshot = EffectChainPreset.capture(
                AppAudioRuntime.get(getApplicationContext()).dsp, "notification_share_snapshot", "알림 카톡 공유");
        final Context appContext = getApplicationContext();

        // shareExecutor is single-threaded, so a previous (cancelled) render always finishes
        // before this one starts — the equivalent of join() + the old render Mutex.
        sharePreparationJob = shareExecutor.submit(() -> {
            File outputFile = null;
            Exception failure = null;
            boolean cancelled = false;
            try {
                Thread.sleep(250L);
                DspProcessor exportDsp = new DspProcessor();
                dspSnapshot.applyTo(exportDsp);
                AudioEngine exportEngine = new AudioEngine(exportDsp);
                outputFile = exportEngine.prepareKakaoVoiceMessageFile(appContext, inputFile, format, 0f, 1f);
                if (Thread.currentThread().isInterrupted()) cancelled = true;
            } catch (InterruptedException | CancellationException e) {
                cancelled = true;
            } catch (Exception e) {
                failure = e;
            }
            if (cancelled) return; // A newer request owns the UI.
            final File result = outputFile;
            final Exception error = failure;
            postMain(() -> {
                if (generation != sharePreparationGeneration) return;
                try {
                    if (error != null) {
                        sharePreparationJob = null;
                        preparedShareFile = null;
                        reviewSharePendingIntent = null;
                        Log.e(TAG, "Unable to prepare background Kakao share file", error);
                        showActionFeedback("카톡 공유 준비 실패", 3200L);
                        return;
                    }
                    sharePreparationJob = null;
                    preparedShareFile = result;
                    reviewSharePendingIntent = result != null
                            ? preparedShareActivityPendingIntent(appContext, result) : null;
                    if (result != null && reviewSharePendingIntent != null) {
                        showActionFeedback("카톡 공유 준비 완료", 1800L);
                        if (launchShareWhenPrepared) {
                            launchShareWhenPrepared = false;
                            launchPreparedShareForFallback();
                        }
                    } else {
                        showActionFeedback("카톡 공유 파일을 준비하지 못했습니다.", 3200L);
                    }
                } finally {
                    if (generation == sharePreparationGeneration) updateNotification();
                }
            });
        });
    }

    private void handleShareAction() {
        if (reviewSharePendingIntent != null) {
            launchPreparedShareForFallback();
            return;
        }
        // This path is used only if a native fallback notification is needed.
        launchShareWhenPrepared = true;
        prepareReviewShareFile();
        launchShareWhenPrepared = true;
        setPersistentActionFeedback("카톡 공유 파일 준비 중…");
    }

    private void launchPreparedShareForFallback() {
        PendingIntent pendingIntent = reviewSharePendingIntent;
        if (pendingIntent == null) return;
        try {
            ActivityOptions options = ActivityOptions.makeBasic();
            if (Build.VERSION.SDK_INT >= 34) {
                // This is a direct result of tapping the foreground-service notification action.
                options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
            }
            pendingIntent.send(options.toBundle());
        } catch (PendingIntent.CanceledException e) {
            Log.e(TAG, "Kakao share PendingIntent was canceled", e);
            showActionFeedback("카톡 공유를 다시 눌러주세요.");
        } catch (Exception e) {
            Log.e(TAG, "Unable to launch Kakao share target", e);
            showActionFeedback("공유 앱을 열지 못했습니다.");
        }
    }

    private void saveReviewTake() {
        if (isSavingTake || isStopping || isRecording) return;
        File inputFile = getReviewFile();
        if (inputFile == null) {
            showActionFeedback("저장할 녹음 파일이 없습니다.");
            return;
        }
        isSavingTake = true;
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        final String format = preferredFormat(prefs);
        final String fileName = "Song_" + System.currentTimeMillis();
        Uri parsedTree = null;
        String stored = prefs.getString("storage_uri", null);
        if (stored != null) {
            try { parsedTree = Uri.parse(stored); } catch (Exception ignored) { }
        }
        final Uri treeUri = parsedTree;
        final EffectChainPreset dspSnapshot = EffectChainPreset.capture(
                AppAudioRuntime.get(getApplicationContext()).dsp, "notification_save_snapshot", "알림 저장");
        final Context appContext = getApplicationContext();
        setPersistentActionFeedback("저장 중…");

        ioExecutor.execute(() -> {
            String result = null;
            Exception failure = null;
            try {
                DspProcessor exportDsp = new DspProcessor();
                dspSnapshot.applyTo(exportDsp);
                AudioEngine exportEngine = new AudioEngine(exportDsp);
                result = exportEngine.exportToMusicFolder(appContext, inputFile, format, fileName,
                        AudioEngine.EditMode.NONE, 0f, 1f, treeUri);
            } catch (Exception e) {
                failure = e;
            }
            final String saved = result;
            final Exception error = failure;
            postMain(() -> {
                if (error != null) {
                    Log.e(TAG, "Notification save action failed", error);
                    showActionFeedback("파일 저장에 실패했습니다.", 3400L);
                } else if (saved != null) {
                    showActionFeedback("저장 완료 · " + fileName + "." + format, 3400L);
                } else {
                    showActionFeedback("파일 저장에 실패했습니다.", 3400L);
                }
                isSavingTake = false;
            });
        });
    }

    private void handlePlaybackAction(String action) {
        if (isStopping || isRecording) return;
        File file = getReviewFile();
        if (file == null) {
            showActionFeedback("미리 들을 녹음 파일이 없습니다.");
            return;
        }
        AudioEngine engine = AppAudioRuntime.get(getApplicationContext()).audioEngine;
        if (durationMs <= 0L) durationMs = durationFromWavLength(file.length());

        if (ACTION_PLAYBACK_PLAY.equals(action)) {
            if (engine.isPreviewActive()) {
                if (engine.isPreviewPausedState()) engine.resumePreview();
                isPlaying = !engine.isPreviewPausedState();
            } else {
                if (currentPositionMs >= durationMs) currentPositionMs = 0L;
                float startRatio = Math.max(0f, Math.min(1f, (float) currentPositionMs / Math.max(durationMs, 1L)));
                isPlaying = true;
                engine.startPreview(file, 0f, 1f, startRatio, ratio -> { },
                        () -> mainHandler.post(() -> {
                            if (!resettingPlayback) {
                                isPlaying = false;
                                currentPositionMs = clamp((long) (engine.getCurrentPreviewGlobalRatio() * durationMs), 0L, durationMs);
                                updateNotification();
                            }
                        }),
                        getApplicationContext(), durationMs);
                if (!engine.isPreviewActive()) {
                    isPlaying = false;
                    showActionFeedback("미리 듣기를 시작하지 못했습니다.");
                    return;
                }
            }
            updateNotification();
        } else if (ACTION_PLAYBACK_PAUSE.equals(action)) {
            engine.pausePreview();
            isPlaying = false;
            currentPositionMs = clamp((long) (engine.getCurrentPreviewGlobalRatio() * durationMs), 0L, durationMs);
            updateNotification();
        } else if (ACTION_PLAYBACK_TOGGLE.equals(action)) {
            handlePlaybackAction(engine.isPreviewActive() && !engine.isPreviewPausedState()
                    ? ACTION_PLAYBACK_PAUSE : ACTION_PLAYBACK_PLAY);
        } else if (ACTION_PLAYBACK_RESET.equals(action)) {
            resetReviewRecording();
        }
    }

    private void handleEffectToggle(String action, int startId) {
        AppAudioRuntime.Runtime runtime = AppAudioRuntime.get(this);
        // If the app has not made an in-app recording in this session, notification-only mode
        // starts from the selected default preset before applying the first toggle.
        if (!AppAudioRuntime.shouldUseCurrentSettingsForNotificationRecording(this) && !isRecording) {
            EffectPresetStore.resetProcessorToDefault(this, runtime.dsp);
            AppAudioRuntime.bumpEffectRevision();
        }
        applyToggle(runtime.dsp, action);
        AppAudioRuntime.markNotificationEffectTouched(this);
        if (!isRecording && recordingFilePath != null) {
            prepareReviewShareFile();
        } else {
            updateNotification();
        }
        if (!isRecording && recordingFilePath == null) {
            showIdleNotification(this);
            stopSelf(startId);
        }
    }

    private void resetReviewRecording() {
        if (isRecording || isStopping) return;
        if (isSavingTake) {
            showActionFeedback("저장 완료 후 초기화해주세요.");
            return;
        }
        isStopping = true;
        resettingPlayback = true;
        sharePreparationGeneration += 1L;
        final Future<?> oldShare = sharePreparationJob;
        if (oldShare != null) oldShare.cancel(true);
        sharePreparationJob = null;
        reviewSharePendingIntent = null;
        preparedShareFile = null;
        if (mediaSession != null) mediaSession.setActive(false);
        final Context appContext = getApplicationContext();
        ioExecutor.execute(() -> {
            // Wait for the cancelled render to wind down (equivalent of join()).
            try {
                shareExecutor.submit(() -> { }).get();
            } catch (Exception ignored) { }
            AudioEngine engine = AppAudioRuntime.get(appContext).audioEngine;
            engine.stopPreview();
            //noinspection ResultOfMethodCallIgnored
            AppAudioRuntime.rawRecordingFile(appContext).delete();
            //noinspection ResultOfMethodCallIgnored
            new File(getCacheDir(), "temp_pcm.pcm").delete();
            postMain(() -> {
                engine.seekToGlobalRatio(0f);
                recordingFilePath = null;
                isPlaying = false;
                durationMs = 0L;
                currentPositionMs = 0L;
                isStopping = false;
                resettingPlayback = false;
                if (actionFeedbackReset != null) mainHandler.removeCallbacks(actionFeedbackReset);
                actionFeedbackMessage = null;
                stopForeground(STOP_FOREGROUND_REMOVE);
                notificationManager.cancel(NOTIFICATION_ID);
                AppAudioRuntime.bumpSessionRevision();
                if (isNotificationFeatureEnabled(RecordingService.this)) showIdleNotification(RecordingService.this);
                stopSelf();
            });
        });
    }

    private Notification buildReviewMediaNotification(String status) {
        MediaSession session = mediaSession;
        if (session == null) throw new IllegalStateException("MediaSession unavailable");
        AudioEngine engine = AppAudioRuntime.get(getApplicationContext()).audioEngine;
        isPlaying = engine.isPreviewActive() && !engine.isPreviewPausedState();
        currentPositionMs = clamp((long) (engine.getCurrentPreviewGlobalRatio() * durationMs), 0L, durationMs);
        session.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "녹음 미리 듣기")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, status)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs).build());
        PlaybackState.Builder state = new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SEEK_TO)
                .setState(isPlaying ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        currentPositionMs, isPlaying ? 1f : 0f, SystemClock.elapsedRealtime())
                .addCustomAction(ACTION_RESET_RECORDING, "녹음 초기화", R.drawable.ic_record_cancel)
                .addCustomAction(ACTION_SAVE, "저장", android.R.drawable.ic_menu_save);
        if (reviewSharePendingIntent != null) {
            state.addCustomAction(ACTION_SHARE, "카톡 공유", android.R.drawable.ic_menu_share);
        }
        session.setPlaybackState(state.build());
        session.setActive(true);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_record_circle)
                .setContentTitle("녹음 미리 듣기")
                .setContentText(status)
                .setContentIntent(openAppPendingIntent(this))
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        isPlaying ? "일시정지" : "재생",
                        servicePendingIntent(this, ACTION_PLAYBACK_TOGGLE, 102, false)).build())
                .addAction(new Notification.Action.Builder(R.drawable.ic_record_cancel, "녹음 초기화",
                        servicePendingIntent(this, ACTION_RESET_RECORDING, 106, false)).build())
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_save, "저장",
                        servicePendingIntent(this, ACTION_SAVE, 103, false)).build());
        if (reviewSharePendingIntent != null) {
            builder.addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_share, "카톡 공유",
                    reviewSharePendingIntent).build());
        }
        // SystemUI owns the draggable seek bar. Preserve effect toggles in the decorated expanded view.
        builder.setCustomBigContentView(makeControlRemoteViews(this, ControlState.REVIEW, status,
                isPlaying, true, currentPositionMs, durationMs, reviewSharePendingIntent));
        builder.setStyle(new Notification.DecoratedMediaCustomViewStyle()
                .setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0, 1, 2));
        return builder.build();
    }

    private void startMicrophoneForeground(Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void startMediaPlaybackForeground(Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void enterReviewState(String filePath, long duration) {
        isRecording = false;
        isStopping = false;
        isPlaying = false;
        recordingFilePath = filePath;
        durationMs = Math.max(duration, 0L);
        currentPositionMs = 0L;
        stopRecordingTimer();
        String reviewStatus = "녹음 완료 · 미리 듣기 / 저장 / 공유";
        Notification reviewNotification;
        try {
            reviewNotification = buildControlNotification(this, ControlState.REVIEW, reviewStatus, false,
                    currentPositionMs, durationMs, null);
        } catch (Exception e) {
            Log.e(TAG, "Custom review notification failed; using native actions", e);
            reviewNotification = buildNativeFallbackNotification(this, ControlState.REVIEW, reviewStatus, false);
        }
        try {
            startMediaPlaybackForeground(reviewNotification);
            updateNotification();
            prepareReviewShareFile();
        } catch (Exception e) {
            Log.e(TAG, "Failed to enter review foreground state", e);
            try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Exception ignored) { }
            try { notificationManager.cancel(NOTIFICATION_ID); } catch (Exception ignored) { }
            stopSelf();
        }
    }

    private void refreshReviewState(String filePath, long duration) {
        isRecording = false;
        recordingFilePath = filePath;
        durationMs = Math.max(duration, 0L);
        currentPositionMs = clamp(currentPositionMs, 0L, durationMs);
        stopRecordingTimer();
        updateNotification();
        if (preparedShareFile == null && sharePreparationJob == null) prepareReviewShareFile();
    }

    private void updateNotification() {
        if (!isRecording && recordingFilePath == null && !isNotificationFeatureEnabled(this)) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            notificationManager.cancel(NOTIFICATION_ID);
            stopSelf();
            return;
        }
        ControlState state = isRecording ? ControlState.RECORDING
                : (recordingFilePath != null ? ControlState.REVIEW : ControlState.IDLE);
        String status = actionFeedbackMessage;
        if (status == null) {
            switch (state) {
                case RECORDING:
                    if (isStopping) {
                        status = "녹음 정리 중…";
                    } else {
                        long elapsed = recordingStartedAtElapsedMs > 0L
                                ? SystemClock.elapsedRealtime() - recordingStartedAtElapsedMs : 0L;
                        status = "녹음 중 " + formatClockTime(elapsed);
                    }
                    break;
                case REVIEW:
                    status = isPlaying ? "녹음 미리 듣기 중" : "녹음 완료 · 미리 듣기 / 저장 / 공유";
                    break;
                case IDLE:
                default:
                    status = "녹음 대기 · 버튼으로 바로 시작";
                    break;
            }
        }
        Notification notification;
        try {
            notification = buildControlNotification(this, state, status, isPlaying, currentPositionMs, durationMs,
                    state == ControlState.REVIEW ? reviewSharePendingIntent : null);
        } catch (Exception e) {
            Log.e(TAG, "Custom notification update failed; using native actions for " + state, e);
            notification = buildNativeFallbackNotification(this, state, status, isPlaying);
        }
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification);
        } catch (Exception e) {
            Log.e(TAG, "Failed to update notification state=" + state, e);
        }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        setEnabledPref(this, false);
        if (!isRecording && !isPlaying) {
            try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Exception ignored) { }
            try { notificationManager.cancel(NOTIFICATION_ID); } catch (Exception ignored) { }
            stopSelf();
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
        stopRecordingTimer();
        if (sharePreparationJob != null) sharePreparationJob.cancel(true);
        if (actionFeedbackReset != null) mainHandler.removeCallbacks(actionFeedbackReset);
        destroyed = true;
        shareExecutor.shutdownNow();
        ioExecutor.shutdown();
        if (runningInstance == this) runningInstance = null;
        super.onDestroy();
    }
}
