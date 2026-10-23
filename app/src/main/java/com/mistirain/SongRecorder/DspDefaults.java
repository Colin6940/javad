package com.mistirain.SongRecorder;

/** Individual reset buttons restore values from the currently selected default preset. */
public final class DspDefaults {
    private DspDefaults() { }

    public static void resetGateToDefault(DspProcessor dsp) { resetGateToDefault(dsp, EffectChainPreset.builtInDefault()); }

    public static void resetGateToDefault(DspProcessor dsp, EffectChainPreset preset) {
        dsp.gateEnabled = preset.gateEnabled;
        dsp.gateLowThreshDb = preset.gateLowThreshDb;
        dsp.gateMidThreshDb = preset.gateMidThreshDb;
        dsp.gateHighThreshDb = preset.gateHighThreshDb;
    }

    public static void resetEqToDefault(DspProcessor dsp) { resetEqToDefault(dsp, EffectChainPreset.builtInDefault()); }

    public static void resetEqToDefault(DspProcessor dsp, EffectChainPreset preset) {
        dsp.eqEnabled = preset.eqEnabled;
        for (int i = 0; i < Math.min(5, preset.eqBands.size()); i++) dsp.updateBand(i, preset.eqBands.get(i));
    }

    public static void resetCompressorToDefault(DspProcessor dsp) { resetCompressorToDefault(dsp, EffectChainPreset.builtInDefault()); }

    public static void resetCompressorToDefault(DspProcessor dsp, EffectChainPreset preset) {
        dsp.compEnabled = preset.compEnabled;
        dsp.compThresholdDb = preset.compThresholdDb;
        dsp.compRatio = preset.compRatio;
        dsp.compMakeupDb = preset.compMakeupDb;
    }

    public static void resetReverbOnlyToDefault(DspProcessor dsp) { resetReverbOnlyToDefault(dsp, EffectChainPreset.builtInDefault()); }

    public static void resetReverbOnlyToDefault(DspProcessor dsp, EffectChainPreset preset) {
        dsp.reverbEnabled = preset.reverbEnabled;
        dsp.reverbPreComp = preset.reverbPreComp;
        dsp.reverbVolume = preset.reverbVolume;
        dsp.reverbLowCutHz = preset.reverbLowCutHz;
        dsp.reverbTimeMs = preset.reverbTimeMs;
        dsp.reverbPreDelayMs = preset.reverbPreDelayMs;
    }

    public static void resetReverbEqOnlyToDefault(DspProcessor dsp) { resetReverbEqOnlyToDefault(dsp, EffectChainPreset.builtInDefault()); }

    public static void resetReverbEqOnlyToDefault(DspProcessor dsp, EffectChainPreset preset) {
        dsp.reverbEqEnabled = preset.reverbEqEnabled;
        for (int i = 0; i < Math.min(3, preset.reverbEqBands.size()); i++) dsp.updateReverbBand(i, preset.reverbEqBands.get(i));
    }

    public static void resetAllEffectsToDefault(DspProcessor dsp) { resetAllEffectsToDefault(dsp, EffectChainPreset.builtInDefault()); }

    public static void resetAllEffectsToDefault(DspProcessor dsp, EffectChainPreset preset) {
        preset.applyTo(dsp);
    }
}
