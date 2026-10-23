package com.mistirain.SongRecorder;

import java.util.Objects;

/** Immutable EQ band description (Java port of the former Kotlin data class). */
public final class EqBandConfig {
    public final boolean enabled; // 기본값 OFF
    public final float freq;
    public final float gainDb;
    public final float q;
    public final FilterType type;

    public EqBandConfig(boolean enabled, float freq, float gainDb, float q, FilterType type) {
        this.enabled = enabled;
        this.freq = freq;
        this.gainDb = gainDb;
        this.q = q;
        this.type = type == null ? FilterType.PEAK : type;
    }

    public EqBandConfig withEnabled(boolean value) { return new EqBandConfig(value, freq, gainDb, q, type); }
    public EqBandConfig withFreq(float value) { return new EqBandConfig(enabled, value, gainDb, q, type); }
    public EqBandConfig withGainDb(float value) { return new EqBandConfig(enabled, freq, value, q, type); }
    public EqBandConfig withQ(float value) { return new EqBandConfig(enabled, freq, gainDb, value, type); }
    public EqBandConfig withType(FilterType value) { return new EqBandConfig(enabled, freq, gainDb, q, value); }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EqBandConfig)) return false;
        EqBandConfig that = (EqBandConfig) o;
        return enabled == that.enabled
                && Float.compare(freq, that.freq) == 0
                && Float.compare(gainDb, that.gainDb) == 0
                && Float.compare(q, that.q) == 0
                && type == that.type;
    }

    @Override
    public int hashCode() {
        return Objects.hash(enabled, freq, gainDb, q, type);
    }

    @Override
    public String toString() {
        return "EqBandConfig(enabled=" + enabled + ", freq=" + freq + ", gainDb=" + gainDb
                + ", q=" + q + ", type=" + type + ")";
    }
}
