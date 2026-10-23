package com.mistirain.SongRecorder;

public enum FilterType {
    PEAK("Peak"),
    LOW_SHELF("Low Shelf"),
    HIGH_SHELF("High Shelf");

    public final String label;

    FilterType(String label) {
        this.label = label;
    }
}
