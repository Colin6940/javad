package com.mistirain.SongRecorder;

/** Commands forwarded from notification intents to the visible recorder screen. */
public final class RecordingNotificationCommand {
    public enum Type {
        START_RECORDING,
        STOP_RECORDING,
        CANCEL_RECORDING,
        RESET_PLAYBACK,
        PLAY_PLAYBACK,
        PAUSE_PLAYBACK,
        TOGGLE_PLAYBACK,
        SAVE_RECORDING,
        SHARE_RECORDING,
        SEEK_TO
    }

    public final Type type;
    /** Only meaningful for {@link Type#SEEK_TO}. */
    public final long positionMs;

    private RecordingNotificationCommand(Type type, long positionMs) {
        this.type = type;
        this.positionMs = positionMs;
    }

    public static RecordingNotificationCommand of(Type type) {
        return new RecordingNotificationCommand(type, 0L);
    }

    public static RecordingNotificationCommand seekTo(long positionMs) {
        return new RecordingNotificationCommand(Type.SEEK_TO, positionMs);
    }
}
