package com.mistirain.SongRecorder;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicLong;

/** Queues notification actions until the recorder screen is ready to handle them. */
public final class RecordingNotificationCommandBus {

    public static final class Event {
        public final long id;
        public final RecordingNotificationCommand command;

        Event(long id, RecordingNotificationCommand command) {
            this.id = id;
            this.command = command;
        }
    }

    public interface Listener {
        void onCommand(Event event);
    }

    private static final AtomicLong ids = new AtomicLong(0L);
    private static final ArrayDeque<Event> pending = new ArrayDeque<>();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static Listener listener;

    private RecordingNotificationCommandBus() { }

    public static void dispatch(RecordingNotificationCommand command) {
        Event event = new Event(ids.incrementAndGet(), command);
        mainHandler.post(() -> {
            if (listener != null) listener.onCommand(event);
            else pending.add(event);
        });
    }

    /** Attaches the single consumer and drains anything queued while no screen was listening. */
    public static void setListener(Listener l) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(() -> setListener(l));
            return;
        }
        listener = l;
        while (listener != null && !pending.isEmpty()) {
            listener.onCommand(pending.poll());
        }
    }

    public static void clearListener(Listener l) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(() -> clearListener(l));
            return;
        }
        if (listener == l) listener = null;
    }
}
