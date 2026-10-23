package com.mistirain.SongRecorder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;

public class EffectChainPresetTest {
    @Test
    public void builtInDefaultPresetTurnsAllEffectsOffAndKeepsReverbMixAtFifteenPercent() {
        DspProcessor dsp = new DspProcessor();
        EffectChainPreset.builtInDefault().applyTo(dsp);

        assertFalse(dsp.gateEnabled);
        assertFalse(dsp.eqEnabled);
        assertFalse(dsp.compEnabled);
        assertFalse(dsp.reverbEnabled);
        assertFalse(dsp.reverbEqEnabled);
        assertEquals(0.15f, dsp.reverbVolume, 0.0001f);
        assertEquals(5, dsp.eqBands.length);
        assertEquals(3, dsp.reverbEqBands.length);
        for (EqBandConfig b : dsp.eqBands) assertFalse(b.enabled);
        for (EqBandConfig b : dsp.reverbEqBands) assertFalse(b.enabled);
    }

    @Test
    public void presetCanBeAppliedToRestoreValuesAsAChainSnapshot() {
        DspProcessor configured = new DspProcessor();
        configured.gateEnabled = true;
        configured.gateLowThreshDb = -38f;
        configured.eqEnabled = true;
        configured.updateBand(2, configured.eqBands[2].withEnabled(true).withFreq(1250f).withGainDb(3f));
        configured.compEnabled = true;
        configured.compRatio = 4f;
        configured.reverbEnabled = true;
        configured.reverbVolume = 0.22f;

        EffectChainPreset snapshot = EffectChainPreset.capture(configured);
        DspProcessor restored = new DspProcessor();
        snapshot.applyTo(restored);

        assertEquals(configured.gateEnabled, restored.gateEnabled);
        assertEquals(configured.gateLowThreshDb, restored.gateLowThreshDb, 0.0001f);
        assertEquals(configured.eqEnabled, restored.eqEnabled);
        assertEquals(configured.eqBands[2], restored.eqBands[2]);
        assertEquals(configured.compRatio, restored.compRatio, 0.0001f);
        assertEquals(configured.reverbVolume, restored.reverbVolume, 0.0001f);
    }

    @Test
    public void incompletePresetStillReplacesAllBandsInsteadOfKeepingStaleValues() {
        DspProcessor dsp = new DspProcessor();
        dsp.updateBand(4, dsp.eqBands[4].withEnabled(true).withGainDb(9f));
        dsp.updateReverbBand(2, dsp.reverbEqBands[2].withEnabled(true).withGainDb(6f));

        EffectChainPreset partial = EffectChainPreset.builder("partial_test", "Partial")
                .eqEnabled(false)
                .eqBands(Collections.singletonList(new EqBandConfig(true, 500f, 2f, 1.0f, FilterType.PEAK)))
                .reverbEqBands(Collections.emptyList())
                .build();

        partial.applyTo(dsp);

        assertTrue(dsp.eqBands[0].enabled);
        assertFalse(dsp.eqBands[4].enabled);
        assertEquals(0f, dsp.eqBands[4].gainDb, 0f);
        assertFalse(dsp.reverbEqBands[2].enabled);
        assertEquals(0f, dsp.reverbEqBands[2].gainDb, 0f);
    }
}
