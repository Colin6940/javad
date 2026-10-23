package com.mistirain.SongRecorder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A named effect-chain snapshot.  The active "기본값" is a preset reference, not a list
 * of hard-coded defaults spread across the editors. Future preset UI can save additional
 * instances and change which preset is used as the application's default.
 */
public final class EffectChainPreset {
    public static final String BUILTIN_DEFAULT_ID = "builtin_default";

    public final String id;
    public final String name;
    public final boolean gateEnabled;
    public final float gateLowThreshDb;
    public final float gateMidThreshDb;
    public final float gateHighThreshDb;
    public final boolean eqEnabled;
    public final List<EqBandConfig> eqBands;
    public final boolean compEnabled;
    public final float compThresholdDb;
    public final float compRatio;
    public final float compMakeupDb;
    public final boolean reverbEnabled;
    public final boolean reverbPreComp;
    public final float reverbVolume;
    public final float reverbLowCutHz;
    public final float reverbTimeMs;
    public final float reverbPreDelayMs;
    public final boolean reverbEqEnabled;
    public final List<EqBandConfig> reverbEqBands;

    private EffectChainPreset(Builder b) {
        id = b.id;
        name = b.name;
        gateEnabled = b.gateEnabled;
        gateLowThreshDb = b.gateLowThreshDb;
        gateMidThreshDb = b.gateMidThreshDb;
        gateHighThreshDb = b.gateHighThreshDb;
        eqEnabled = b.eqEnabled;
        eqBands = Collections.unmodifiableList(new ArrayList<>(b.eqBands));
        compEnabled = b.compEnabled;
        compThresholdDb = b.compThresholdDb;
        compRatio = b.compRatio;
        compMakeupDb = b.compMakeupDb;
        reverbEnabled = b.reverbEnabled;
        reverbPreComp = b.reverbPreComp;
        reverbVolume = b.reverbVolume;
        reverbLowCutHz = b.reverbLowCutHz;
        reverbTimeMs = b.reverbTimeMs;
        reverbPreDelayMs = b.reverbPreDelayMs;
        reverbEqEnabled = b.reverbEqEnabled;
        reverbEqBands = Collections.unmodifiableList(new ArrayList<>(b.reverbEqBands));
    }

    public static Builder builder(String id, String name) {
        return new Builder(id, name);
    }

    public void applyTo(DspProcessor dsp) {
        dsp.gateEnabled = gateEnabled;
        dsp.gateLowThreshDb = gateLowThreshDb;
        dsp.gateMidThreshDb = gateMidThreshDb;
        dsp.gateHighThreshDb = gateHighThreshDb;

        dsp.eqEnabled = eqEnabled;
        EffectChainPreset defaultChain = builtInDefault();
        for (int i = 0; i < dsp.eqBands.length; i++) {
            dsp.updateBand(i, i < eqBands.size() ? eqBands.get(i) : defaultChain.eqBands.get(i));
        }

        dsp.compEnabled = compEnabled;
        dsp.compThresholdDb = compThresholdDb;
        dsp.compRatio = compRatio;
        dsp.compMakeupDb = compMakeupDb;

        dsp.reverbEnabled = reverbEnabled;
        dsp.reverbPreComp = reverbPreComp;
        dsp.reverbVolume = reverbVolume;
        dsp.reverbLowCutHz = reverbLowCutHz;
        dsp.reverbTimeMs = reverbTimeMs;
        dsp.reverbPreDelayMs = reverbPreDelayMs;
        dsp.reverbEqEnabled = reverbEqEnabled;
        for (int i = 0; i < dsp.reverbEqBands.length; i++) {
            dsp.updateReverbBand(i, i < reverbEqBands.size() ? reverbEqBands.get(i) : defaultChain.reverbEqBands.get(i));
        }
        dsp.resetState();
    }

    public static EffectChainPreset builtInDefault() {
        return builder(BUILTIN_DEFAULT_ID, "기본값")
                .eqEnabled(false)
                .eqBands(defaultEqBands())
                .reverbVolume(0.15f)
                .build();
    }

    public static EffectChainPreset capture(DspProcessor dsp) {
        return capture(dsp, "working", "현재 설정");
    }

    public static EffectChainPreset capture(DspProcessor dsp, String id, String name) {
        return builder(id, name)
                .gateEnabled(dsp.gateEnabled)
                .gateLowThreshDb(dsp.gateLowThreshDb)
                .gateMidThreshDb(dsp.gateMidThreshDb)
                .gateHighThreshDb(dsp.gateHighThreshDb)
                .eqEnabled(dsp.eqEnabled)
                .eqBands(Arrays.asList(dsp.eqBands.clone()))
                .compEnabled(dsp.compEnabled)
                .compThresholdDb(dsp.compThresholdDb)
                .compRatio(dsp.compRatio)
                .compMakeupDb(dsp.compMakeupDb)
                .reverbEnabled(dsp.reverbEnabled)
                .reverbPreComp(dsp.reverbPreComp)
                .reverbVolume(dsp.reverbVolume)
                .reverbLowCutHz(dsp.reverbLowCutHz)
                .reverbTimeMs(dsp.reverbTimeMs)
                .reverbPreDelayMs(dsp.reverbPreDelayMs)
                .reverbEqEnabled(dsp.reverbEqEnabled)
                .reverbEqBands(Arrays.asList(dsp.reverbEqBands.clone()))
                .build();
    }

    static List<EqBandConfig> defaultEqBands() {
        return Arrays.asList(
                new EqBandConfig(false, 100f, 0f, 1f, FilterType.PEAK),
                new EqBandConfig(false, 300f, 0f, 1f, FilterType.PEAK),
                new EqBandConfig(false, 1000f, 0f, 1f, FilterType.PEAK),
                new EqBandConfig(false, 3500f, 0f, 1f, FilterType.PEAK),
                new EqBandConfig(false, 10000f, 0f, 1f, FilterType.PEAK)
        );
    }

    static List<EqBandConfig> defaultReverbEqBands() {
        return Arrays.asList(
                new EqBandConfig(false, 250f, 0f, 0.7f, FilterType.PEAK),
                new EqBandConfig(false, 1500f, 0f, 0.7f, FilterType.PEAK),
                new EqBandConfig(false, 6000f, 0f, 0.7f, FilterType.PEAK)
        );
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EffectChainPreset)) return false;
        EffectChainPreset p = (EffectChainPreset) o;
        return id.equals(p.id) && name.equals(p.name)
                && gateEnabled == p.gateEnabled
                && Float.compare(gateLowThreshDb, p.gateLowThreshDb) == 0
                && Float.compare(gateMidThreshDb, p.gateMidThreshDb) == 0
                && Float.compare(gateHighThreshDb, p.gateHighThreshDb) == 0
                && eqEnabled == p.eqEnabled && eqBands.equals(p.eqBands)
                && compEnabled == p.compEnabled
                && Float.compare(compThresholdDb, p.compThresholdDb) == 0
                && Float.compare(compRatio, p.compRatio) == 0
                && Float.compare(compMakeupDb, p.compMakeupDb) == 0
                && reverbEnabled == p.reverbEnabled && reverbPreComp == p.reverbPreComp
                && Float.compare(reverbVolume, p.reverbVolume) == 0
                && Float.compare(reverbLowCutHz, p.reverbLowCutHz) == 0
                && Float.compare(reverbTimeMs, p.reverbTimeMs) == 0
                && Float.compare(reverbPreDelayMs, p.reverbPreDelayMs) == 0
                && reverbEqEnabled == p.reverbEqEnabled && reverbEqBands.equals(p.reverbEqBands);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, gateEnabled, gateLowThreshDb, gateMidThreshDb, gateHighThreshDb,
                eqEnabled, eqBands, compEnabled, compThresholdDb, compRatio, compMakeupDb,
                reverbEnabled, reverbPreComp, reverbVolume, reverbLowCutHz, reverbTimeMs,
                reverbPreDelayMs, reverbEqEnabled, reverbEqBands);
    }

    /** Builder carrying the same defaults as the former Kotlin constructor. */
    public static final class Builder {
        private final String id;
        private final String name;
        private boolean gateEnabled = false;
        private float gateLowThreshDb = -45f;
        private float gateMidThreshDb = -45f;
        private float gateHighThreshDb = -45f;
        private boolean eqEnabled = false;
        private List<EqBandConfig> eqBands = defaultEqBands();
        private boolean compEnabled = false;
        private float compThresholdDb = -20f;
        private float compRatio = 2.5f;
        private float compMakeupDb = 0f;
        private boolean reverbEnabled = false;
        private boolean reverbPreComp = false;
        private float reverbVolume = 0.15f;
        private float reverbLowCutHz = 90f;
        private float reverbTimeMs = 1000f;
        private float reverbPreDelayMs = 20f;
        private boolean reverbEqEnabled = false;
        private List<EqBandConfig> reverbEqBands = defaultReverbEqBands();

        private Builder(String id, String name) {
            this.id = id;
            this.name = name;
        }

        public Builder gateEnabled(boolean v) { gateEnabled = v; return this; }
        public Builder gateLowThreshDb(float v) { gateLowThreshDb = v; return this; }
        public Builder gateMidThreshDb(float v) { gateMidThreshDb = v; return this; }
        public Builder gateHighThreshDb(float v) { gateHighThreshDb = v; return this; }
        public Builder eqEnabled(boolean v) { eqEnabled = v; return this; }
        public Builder eqBands(List<EqBandConfig> v) { eqBands = v; return this; }
        public Builder compEnabled(boolean v) { compEnabled = v; return this; }
        public Builder compThresholdDb(float v) { compThresholdDb = v; return this; }
        public Builder compRatio(float v) { compRatio = v; return this; }
        public Builder compMakeupDb(float v) { compMakeupDb = v; return this; }
        public Builder reverbEnabled(boolean v) { reverbEnabled = v; return this; }
        public Builder reverbPreComp(boolean v) { reverbPreComp = v; return this; }
        public Builder reverbVolume(float v) { reverbVolume = v; return this; }
        public Builder reverbLowCutHz(float v) { reverbLowCutHz = v; return this; }
        public Builder reverbTimeMs(float v) { reverbTimeMs = v; return this; }
        public Builder reverbPreDelayMs(float v) { reverbPreDelayMs = v; return this; }
        public Builder reverbEqEnabled(boolean v) { reverbEqEnabled = v; return this; }
        public Builder reverbEqBands(List<EqBandConfig> v) { reverbEqBands = v; return this; }

        public EffectChainPreset build() {
            return new EffectChainPreset(this);
        }
    }
}
