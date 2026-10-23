package com.mistirain.SongRecorder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DspProcessorTest {
    @Test
    public void disabledEffectsPreserveInput() {
        DspProcessor processor = new DspProcessor();
        float input = 0.6f;
        float actual = processor.processSample(input);
        assertEquals(input, actual, 1e-6f);
    }

    @Test
    public void resetStateClearsTheInputLevelMeter() {
        DspProcessor processor = new DspProcessor();
        // The level meter uses a 25 ms attack, so a single sample cannot lift it above -60 dB.
        // Feed ~45 ms of signal so the meter clearly rises before testing the reset.
        for (int i = 0; i < 2000; i++) processor.processSample(0.5f);
        assertTrue(processor.compInputLevelDb > -60f);

        processor.resetState();

        assertEquals(-60f, processor.compInputLevelDb, 0f);
    }

    @Test
    public void reverbVolumeDefaultsAndResetsToFifteenPercent() {
        DspProcessor processor = new DspProcessor();
        assertEquals(0.15f, processor.reverbVolume, 0f);

        processor.reverbVolume = 0.8f;
        DspDefaults.resetReverbOnlyToDefault(processor);

        assertEquals(0.15f, processor.reverbVolume, 0f);
    }

    @Test
    public void enabledEffectsProduceFiniteBoundedSamples() {
        DspProcessor processor = new DspProcessor();
        processor.gateEnabled = true;
        processor.eqEnabled = true;
        processor.compEnabled = true;
        processor.reverbEnabled = true;
        processor.reverbEqEnabled = true;

        for (int index = 0; index < 10_000; index++) {
            float input = index % 2 == 0 ? 0.8f : -0.8f;
            float output = processor.processSample(input);
            assertTrue(Float.isFinite(output));
            assertTrue(output >= -1f && output <= 1f);
        }
    }
}
