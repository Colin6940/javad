package com.mistirain.SongRecorder;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** File/DSP regressions; no microphone permission or external storage needed. */
@RunWith(AndroidJUnit4.class)
public class AudioRegressionTest {

    private Context context() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    private File fixture(int seconds) throws Exception {
        File file = File.createTempFile("audio_test_", ".wav", context().getCacheDir());
        int size = seconds * 44100 * 2;
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(size + 36).put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII))
                .putInt(16).putShort((short) 1).putShort((short) 1).putInt(44100).putInt(88200)
                .putShort((short) 2).putShort((short) 16).put("data".getBytes(StandardCharsets.US_ASCII)).putInt(size);
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            raf.write(header.array());
            raf.setLength(44L + size);
        }
        return file;
    }

    @Test
    public void longWaveformIncludesLastSample() throws Exception {
        File file = fixture(300);
        try {
            try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
                raf.seek(file.length() - 2);
                raf.write(new byte[]{(byte) 0xff, 0x7f});
            }
            List<Float> values = new AudioEngine(new DspProcessor()).extractAmplitudesFromWav(file, 160);
            assertEquals(160, values.size());
            assertTrue(values.get(values.size() - 1) > 0.99f);
            for (int i = 0; i < values.size() - 1; i++) assertTrue(values.get(i) <= 0.021f);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    @Test
    public void disabledEffectsPreserveAllPcmValuesExactly() throws Exception {
        File input = fixture(2);
        File output = File.createTempFile("pcm_output_", ".wav", context().getCacheDir());
        try {
            byte[] expected = new byte[65536 * 2];
            for (int i = 0; i < 65536; i++) {
                expected[i * 2] = (byte) i;
                expected[i * 2 + 1] = (byte) (i >> 8);
            }
            try (RandomAccessFile raf = new RandomAccessFile(input, "rw")) {
                raf.seek(44);
                raf.write(expected);
            }
            assertTrue(new AudioEngine(new DspProcessor()).renderAndEncodeToCache(
                    input, output, "wav", AudioEngine.EditMode.NONE, 0f, 1f));
            byte[] actual = new byte[expected.length];
            try (RandomAccessFile raf = new RandomAccessFile(output, "r")) {
                raf.seek(44);
                raf.readFully(actual);
            }
            assertArrayEquals(expected, actual);
            assertEquals(input.length(), output.length());
        } finally {
            //noinspection ResultOfMethodCallIgnored
            input.delete();
            //noinspection ResultOfMethodCallIgnored
            output.delete();
        }
    }

    @Test
    public void trimKeepsExactSelectedDuration() throws Exception {
        File input = fixture(8);
        File output = File.createTempFile("trim_output_", ".wav", context().getCacheDir());
        try {
            assertTrue(new AudioEngine(new DspProcessor()).renderAndEncodeToCache(
                    input, output, "wav", AudioEngine.EditMode.TRIM, 0.25f, 0.75f));
            assertEquals(44L + 4L * 44100 * 2, output.length());
        } finally {
            //noinspection ResultOfMethodCallIgnored
            input.delete();
            //noinspection ResultOfMethodCallIgnored
            output.delete();
        }
    }

    @Test
    public void seekBeforePlaybackRetainsCursor() {
        AudioEngine engine = new AudioEngine(new DspProcessor());
        engine.seekToGlobalRatio(0.37f);
        assertEquals(0.37f, engine.getCurrentPreviewGlobalRatio(), 0.000001f);
        engine.seekToGlobalRatio(1.5f);
        assertEquals(1f, engine.getCurrentPreviewGlobalRatio(), 0f);
        engine.seekToGlobalRatio(-1f);
        assertEquals(0f, engine.getCurrentPreviewGlobalRatio(), 0f);
    }
}
