package com.mistirain.SongRecorder;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Process-wide audio runtime shared by MainActivity and RecordingService. This lets a
 * notification start a microphone foreground service without launching the app UI while
 * still using the same effect configuration and audio engine as the visible recorder.
 */
public final class AppAudioRuntime {

    public static final class Runtime {
        public final DspProcessor dsp;
        public final AudioEngine audioEngine;

        Runtime(DspProcessor dsp, AudioEngine audioEngine) {
            this.dsp = dsp;
            this.audioEngine = audioEngine;
        }
    }

    /** Observers notified on the main thread when a revision counter changes. */
    public interface RevisionListener {
        void onRevisionChanged(int revision);
    }

    private static final String SESSION_PREFS = "SongRecorderRuntimePrefs";
    private static final String KEY_HAS_APP_SNAPSHOT = "has_app_recording_snapshot";
    private static final String KEY_HAS_NOTIFICATION_SNAPSHOT = "has_notification_effect_snapshot";

    private static volatile Runtime runtime;
    private static volatile boolean appRecordingStartedInThisProcess = false;
    private static volatile boolean notificationEffectsTouchedInThisProcess = false;

    // Editors observe these revisions and refresh their local controls when notification
    // actions change an effect while the activity is in the background.
    private static volatile int effectRevision = 0;
    private static volatile int sessionRevision = 0;
    private static final CopyOnWriteArrayList<RevisionListener> effectListeners = new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<RevisionListener> sessionListeners = new CopyOnWriteArrayList<>();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private AppAudioRuntime() { }

    public static boolean isAppRecordingStartedInThisProcess() { return appRecordingStartedInThisProcess; }
    public static boolean isNotificationEffectsTouchedInThisProcess() { return notificationEffectsTouchedInThisProcess; }
    public static int getEffectRevision() { return effectRevision; }
    public static int getSessionRevision() { return sessionRevision; }

    public static void addEffectRevisionListener(RevisionListener l) { effectListeners.addIfAbsent(l); }
    public static void removeEffectRevisionListener(RevisionListener l) { effectListeners.remove(l); }
    public static void addSessionRevisionListener(RevisionListener l) { sessionListeners.addIfAbsent(l); }
    public static void removeSessionRevisionListener(RevisionListener l) { sessionListeners.remove(l); }

    private static SharedPreferences sessionPrefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE);
    }

    public static synchronized Runtime get(Context context) {
        Runtime existing = runtime;
        if (existing != null) return existing;
        DspProcessor dsp = new DspProcessor();
        SharedPreferences prefs = sessionPrefs(context);
        boolean hasPreviousAppSnapshot = prefs.getBoolean(KEY_HAS_APP_SNAPSHOT, false);
        boolean hasNotificationSnapshot = prefs.getBoolean(KEY_HAS_NOTIFICATION_SNAPSHOT, false);
        EffectChainPreset initialPreset = null;
        if (hasPreviousAppSnapshot) {
            initialPreset = EffectPresetStore.loadPreset(context, "working_snapshot");
        } else if (hasNotificationSnapshot) {
            initialPreset = EffectPresetStore.loadPreset(context, "notification_snapshot");
        }
        if (initialPreset == null) initialPreset = EffectPresetStore.loadDefaultPreset(context);
        initialPreset.applyTo(dsp);
        Runtime created = new Runtime(dsp, new AudioEngine(dsp));
        runtime = created;
        return created;
    }

    public static void markAppRecordingStarted() {
        appRecordingStartedInThisProcess = true;
    }

    public static void markNotificationEffectTouched(Context context) {
        notificationEffectsTouchedInThisProcess = true;
        SharedPreferences prefs = sessionPrefs(context);
        boolean hasStoredAppSnapshot = prefs.getBoolean(KEY_HAS_APP_SNAPSHOT, false);
        DspProcessor dsp = get(context).dsp;
        // Persist effect toggles even when the notification is the first entry point. This lets
        // several expanded-notification toggles survive short-lived service/process recreation.
        EffectPresetStore.savePreset(context,
                EffectChainPreset.capture(dsp, "notification_snapshot", "알림 사용 설정"));
        prefs.edit().putBoolean(KEY_HAS_NOTIFICATION_SNAPSHOT, true).apply();
        if (appRecordingStartedInThisProcess || hasStoredAppSnapshot) {
            EffectPresetStore.saveWorkingSnapshot(context, dsp);
            prefs.edit().putBoolean(KEY_HAS_APP_SNAPSHOT, true).apply();
        }
        bumpEffectRevision();
    }

    public static boolean shouldUseCurrentSettingsForNotificationRecording(Context context) {
        if (appRecordingStartedInThisProcess || notificationEffectsTouchedInThisProcess) return true;
        SharedPreferences prefs = sessionPrefs(context);
        return prefs.getBoolean(KEY_HAS_APP_SNAPSHOT, false)
                || prefs.getBoolean(KEY_HAS_NOTIFICATION_SNAPSHOT, false);
    }

    public static void persistWorkingSnapshot(Context context) {
        SharedPreferences prefs = sessionPrefs(context);
        boolean hasStoredAppSnapshot = prefs.getBoolean(KEY_HAS_APP_SNAPSHOT, false);
        if (!appRecordingStartedInThisProcess && !hasStoredAppSnapshot) return;
        EffectPresetStore.saveWorkingSnapshot(context, get(context).dsp);
        prefs.edit().putBoolean(KEY_HAS_APP_SNAPSHOT, true).apply();
    }

    /** Called only at the first Activity creation of a fresh process when no service take exists. */
    public static synchronized void beginFreshAppSession(Context context) {
        appRecordingStartedInThisProcess = false;
        notificationEffectsTouchedInThisProcess = false;
        SharedPreferences prefs = sessionPrefs(context);
        // The in-app recording snapshot is scoped to the new app session. Notification-only
        // effect toggles, however, are user choices and remain selected across Activity/process
        // recreation until the user changes them or chooses another default preset.
        boolean hasNotificationSnapshot = prefs.getBoolean(KEY_HAS_NOTIFICATION_SNAPSHOT, false);
        prefs.edit().putBoolean(KEY_HAS_APP_SNAPSHOT, false).apply();
        Runtime sharedRuntime = runtime;
        if (sharedRuntime != null) {
            EffectChainPreset notificationPreset = hasNotificationSnapshot
                    ? EffectPresetStore.loadPreset(context, "notification_snapshot") : null;
            (notificationPreset != null ? notificationPreset : EffectPresetStore.loadDefaultPreset(context))
                    .applyTo(sharedRuntime.dsp);
        }
        bumpEffectRevision();
    }

    public static void bumpEffectRevision() {
        final int value;
        synchronized (AppAudioRuntime.class) {
            value = ++effectRevision;
        }
        dispatch(effectListeners, value);
    }

    public static void bumpSessionRevision() {
        final int value;
        synchronized (AppAudioRuntime.class) {
            value = ++sessionRevision;
        }
        dispatch(sessionListeners, value);
    }

    private static void dispatch(CopyOnWriteArrayList<RevisionListener> listeners, int value) {
        if (listeners.isEmpty()) return;
        Runnable r = () -> {
            for (RevisionListener l : listeners) l.onRevisionChanged(value);
        };
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else mainHandler.post(r);
    }

    public static File rawRecordingFile(Context context) {
        return new File(context.getApplicationContext().getCacheDir(), "temp_recording.wav");
    }

    /**
     * Clear only the temporary unsaved take on a true cold activity start. Files saved through
     * the file browser live elsewhere and are never touched here.
     */
    public static void clearAbandonedTemporaryTake(Context context) {
        //noinspection ResultOfMethodCallIgnored
        rawRecordingFile(context).delete();
        //noinspection ResultOfMethodCallIgnored
        new File(context.getApplicationContext().getCacheDir(), "temp_pcm.pcm").delete();
    }
}
