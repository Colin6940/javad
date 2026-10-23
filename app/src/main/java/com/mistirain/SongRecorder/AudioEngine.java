package com.mistirain.SongRecorder;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.Log;

import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.ReturnCode;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;

/**
 * Recording, preview playback and export engine.
 *
 * <p>The former Kotlin {@code suspend} functions are now ordinary blocking methods. They must be
 * called from a background thread; cancellation is cooperative via {@link Thread#interrupt()},
 * which makes the work throw {@link CancellationException}.</p>
 */
public class AudioEngine {

    public enum EditMode { NONE, TRIM, CUT }

    public interface AmplitudeListener { void onAmplitude(float amplitude); }
    public interface ErrorListener { void onError(String message); }
    public interface ProgressListener { void onProgress(float ratio); }

    private static final String TAG = "AudioEngine";

    private final DspProcessor dspProcessor;

    private volatile boolean isRecording = false;
    private volatile boolean isStartingRecording = false;
    private volatile boolean isFinalizingRecording = false;

    private final Object recordingLock = new Object();
    private long recordingStartGeneration = 0L;

    private volatile boolean isPreviewing = false;
    private volatile boolean isPreviewPaused = false;
    /** NaN means "no seek requested". */
    private volatile float requestedSeekRatio = Float.NaN;
    private volatile AudioTrack activeAudioTrack = null;
    private volatile float previewStartRatio = 0f;
    private volatile float previewEndRatio = 1f;
    private volatile float currentPreviewGlobalRatio = 0f;

    private volatile Thread recordingThread = null;
    private Thread previewThread = null;

    private final int sampleRate = 44100;
    private final int channelConfig = AudioFormat.CHANNEL_IN_MONO;
    private final int audioFormat = AudioFormat.ENCODING_PCM_16BIT;

    public AudioEngine(DspProcessor dspProcessor) {
        this.dspProcessor = dspProcessor;
    }

    public float getCurrentPreviewGlobalRatio() { return currentPreviewGlobalRatio; }

    /** True while capture is active, its foreground start is pending, or its file is finalizing. */
    public boolean isRecordingActive() {
        return isRecording || isStartingRecording || isFinalizingRecording;
    }

    public boolean isPreviewActive() { return isPreviewing; }

    public boolean isPreviewPausedState() { return isPreviewing && isPreviewPaused; }

    private static float clamp(float v, float min, float max) { return Math.max(min, Math.min(max, v)); }
    private static long clamp(long v, long min, long max) { return Math.max(min, Math.min(max, v)); }
    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("cancelled");
    }

    // ============================================================================ Recording

    public void startRecording(Context context, File outputFile, AmplitudeListener onAmplitudeUpdate) {
        startRecording(context, outputFile, onAmplitudeUpdate, true, null);
    }

    public void startRecording(
            Context context,
            File outputFile,
            AmplitudeListener onAmplitudeUpdate,
            boolean notifyService,
            ErrorListener onRecordingError
    ) {
        final Context appContext = context.getApplicationContext();
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            safeError(onRecordingError, "마이크 권한이 없어 녹음을 시작하지 못했습니다.");
            return;
        }

        final long generation;
        synchronized (recordingLock) {
            Thread existing = recordingThread;
            if (isRecording || isStartingRecording || isFinalizingRecording
                    || (existing != null && existing.isAlive())) {
                return;
            }
            recordingStartGeneration += 1L;
            isStartingRecording = true;
            generation = recordingStartGeneration;
        }

        final StartFailureReporter reportStartFailure = (message, error) -> {
            boolean shouldNotify;
            synchronized (recordingLock) {
                if (generation != recordingStartGeneration) {
                    shouldNotify = false;
                } else {
                    isStartingRecording = false;
                    isRecording = false;
                    isFinalizingRecording = false;
                    recordingThread = null;
                    shouldNotify = true;
                }
            }
            if (shouldNotify) {
                if (error != null) Log.e(TAG, message, error);
                safeError(onRecordingError, message);
            }
        };

        // Do not open AudioRecord until RecordingService has successfully entered the
        // microphone foreground state. startForegroundService() itself is asynchronous.
        final Runnable launchCapture = () -> {
            boolean canPrepare;
            synchronized (recordingLock) {
                canPrepare = generation == recordingStartGeneration && isStartingRecording;
            }
            if (!canPrepare) {
                RecordingService.returnToIdleNotification(appContext);
                return;
            }

            Thread captureThread = new Thread(
                    () -> runCapture(appContext, outputFile, onAmplitudeUpdate, onRecordingError, generation),
                    "SongRecorder-AudioCapture");

            Exception threadStartFailure = null;
            boolean installedAndStarted;
            synchronized (recordingLock) {
                if (generation != recordingStartGeneration || !isStartingRecording) {
                    installedAndStarted = false;
                } else {
                    recordingThread = captureThread;
                    isStartingRecording = false;
                    isRecording = true;
                    try {
                        // Start while holding the same lock used by stopRecording().
                        captureThread.start();
                        installedAndStarted = true;
                    } catch (Exception e) {
                        threadStartFailure = e;
                        recordingThread = null;
                        isRecording = false;
                        installedAndStarted = false;
                    }
                }
            }
            if (!installedAndStarted) {
                if (threadStartFailure != null) {
                    String msg = threadStartFailure.getMessage();
                    reportStartFailure.report(msg != null ? msg : "녹음 작업 스레드를 시작하지 못했습니다.", threadStartFailure);
                }
                RecordingService.returnToIdleNotification(appContext);
            }
        };

        if (notifyService) {
            RecordingService.beginRecording(appContext, (ready, reason) -> {
                if (ready) {
                    launchCapture.run();
                } else {
                    String detail = (reason != null && !reason.trim().isEmpty()) ? " (" + reason + ")" : "";
                    reportStartFailure.report("녹음용 포그라운드 서비스를 준비하지 못했습니다." + detail, null);
                }
            });
        } else {
            // Headless recording is only called by RecordingService after it has already
            // promoted itself with a microphone foreground notification.
            launchCapture.run();
        }
    }

    private interface StartFailureReporter {
        void report(String message, Throwable error);
    }

    private static void safeError(ErrorListener listener, String message) {
        if (listener == null) return;
        try {
            listener.onError(message);
        } catch (Exception callbackError) {
            Log.e(TAG, "Recording error callback failed", callbackError);
        }
    }

    @SuppressLint("MissingPermission")
    private void runCapture(Context appContext, File outputFile, AmplitudeListener onAmplitudeUpdate,
                            ErrorListener onRecordingError, long generation) {
        AudioRecord audioRecord = null;
        FileOutputStream fos = null;
        File parent = outputFile.getParentFile();
        File pcmFile = new File(parent != null ? parent : appContext.getCacheDir(), "temp_pcm.pcm");
        String errorMessage = null;
        Throwable failure = null;
        try {
            int minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat);
            if (minBufferSize <= 0) {
                throw new IllegalStateException("오디오 입력 장치를 초기화하지 못했습니다. 마이크 권한과 다른 녹음 앱 실행 여부를 확인해주세요.");
            }

            audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig,
                    audioFormat, minBufferSize * 2);
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException("마이크를 열 수 없습니다. 다른 앱이 마이크를 사용 중인지 확인해주세요.");
            }

            fos = new FileOutputStream(pcmFile, false);
            byte[] buffer = new byte[minBufferSize];
            audioRecord.startRecording();
            if (audioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IllegalStateException("마이크 녹음을 시작하지 못했습니다.");
            }
            dspProcessor.resetState();
            long lastAmplitudeDispatchNs = 0L;

            while (isRecording) {
                int read = audioRecord.read(buffer, 0, buffer.length);
                if (read > 0) {
                    fos.write(buffer, 0, read);

                    float maxAmp = 0f;
                    for (int i = 0; i + 1 < read; i += 2) {
                        int sample = (buffer[i] & 0xFF) | (buffer[i + 1] << 8);
                        int s16 = sample > 32767 ? sample - 65536 : sample;
                        float fVal = clamp(s16 / 32768f, -1f, 1f);
                        float processed = dspProcessor.processSample(fVal);
                        float absVal = Math.abs(processed);
                        if (absVal > maxAmp) maxAmp = absVal;
                    }
                    // Avoid flooding the UI thread with one callback per tiny audio buffer.
                    long nowNs = System.nanoTime();
                    if (nowNs - lastAmplitudeDispatchNs >= 50_000_000L) {
                        if (onAmplitudeUpdate != null) onAmplitudeUpdate.onAmplitude(maxAmp);
                        lastAmplitudeDispatchNs = nowNs;
                    }
                } else if (isRecording && (read == AudioRecord.ERROR_BAD_VALUE
                        || read == AudioRecord.ERROR_INVALID_OPERATION
                        || read == AudioRecord.ERROR_DEAD_OBJECT)) {
                    throw new IllegalStateException("마이크 입력이 중단되었습니다. 다른 녹음 앱을 종료한 뒤 다시 시도해주세요.");
                }
            }
            fos.flush();
        } catch (Exception e) {
            failure = e;
            if (isRecording) {
                if (e instanceof SecurityException) {
                    errorMessage = "마이크 권한이 없어 녹음을 시작하지 못했습니다.";
                } else {
                    errorMessage = e.getMessage() != null ? e.getMessage() : "녹음을 시작하거나 유지하는 중 오류가 발생했습니다.";
                }
            }
            Log.e(TAG, "Audio capture failed", e);
        } finally {
            try {
                if (audioRecord != null && audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                }
            } catch (Exception e) {
                Log.w(TAG, "AudioRecord.stop failed", e);
            }
            try {
                if (audioRecord != null) audioRecord.release();
            } catch (Exception e) {
                Log.w(TAG, "AudioRecord.release failed", e);
            }
            try {
                if (fos != null) fos.close();
            } catch (Exception e) {
                Log.w(TAG, "PCM stream close failed", e);
            }
        }

        // A stop pressed before the first audio sample arrives is a clean cancellation,
        // not a failed empty recording.
        if (errorMessage == null && !isRecording && (!pcmFile.exists() || pcmFile.length() == 0L)) {
            //noinspection ResultOfMethodCallIgnored
            pcmFile.delete();
            synchronized (recordingLock) {
                if (generation == recordingStartGeneration) {
                    isRecording = false;
                    isStartingRecording = false;
                    isFinalizingRecording = false;
                    recordingThread = null;
                }
            }
            return;
        }

        // Hold the busy state until conversion ends; otherwise a quick second tap can
        // overwrite temp_pcm.pcm while the previous take is being converted to WAV.
        synchronized (recordingLock) {
            if (generation == recordingStartGeneration) isFinalizingRecording = true;
        }
        if (errorMessage == null) {
            try {
                if (pcmFile.exists() && pcmFile.length() > 0L) {
                    convertPcmToWav(pcmFile, outputFile);
                    if (!outputFile.exists() || outputFile.length() <= 44L) {
                        errorMessage = "녹음 파일을 WAV로 변환하지 못했습니다. 다시 녹음해주세요.";
                    }
                } else {
                    errorMessage = "녹음된 오디오 데이터가 없습니다. 마이크 권한과 입력 장치를 확인해주세요.";
                }
            } catch (Exception e) {
                failure = e;
                errorMessage = e.getMessage() != null ? e.getMessage() : "녹음 파일을 저장하는 중 오류가 발생했습니다.";
                Log.e(TAG, "WAV finalization failed", e);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        pcmFile.delete();

        synchronized (recordingLock) {
            if (generation == recordingStartGeneration) {
                isRecording = false;
                isStartingRecording = false;
                isFinalizingRecording = false;
                recordingThread = null;
            }
        }
        if (errorMessage != null) safeError(onRecordingError, errorMessage);
        if (failure != null && errorMessage == null) {
            Log.w(TAG, "Recording ended after a recoverable error", failure);
        }
    }

    public void stopRecording() {
        Thread thread;
        synchronized (recordingLock) {
            if (isStartingRecording) {
                // Invalidate a pending asynchronous service-start callback.
                recordingStartGeneration += 1L;
                isStartingRecording = false;
            }
            isRecording = false;
            thread = recordingThread;
        }
        try {
            if (thread != null && thread != Thread.currentThread()) thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ============================================================================ Conversion

    /** 외부 오디오 파일(m4a, mp3, wav)을 편집용 표준 WAV(44.1kHz, Mono, 16-bit PCM)로 변환합니다. (blocking) */
    public boolean convertAnyAudioToStandardWav(File inputFile, File outputWav) {
        if (!inputFile.exists()) return false;
        if (outputWav.exists()) //noinspection ResultOfMethodCallIgnored
            outputWav.delete();

        String cmd = "-y -i \"" + inputFile.getAbsolutePath() + "\" -vn -acodec pcm_s16le -ar "
                + sampleRate + " -ac 1 \"" + outputWav.getAbsolutePath() + "\"";
        FFmpegSession session = FFmpegKit.execute(cmd);
        if (ReturnCode.isSuccess(session.getReturnCode()) && outputWav.exists() && outputWav.length() > 44L) {
            // FFmpeg가 생성한 확장 헤더를 제거하고 표준 44바이트 WAV 헤더로 정규화합니다.
            try {
                normalizeWavFile(outputWav);
                return true;
            } catch (Exception e) {
                Log.e(TAG, "WAV normalization failed", e);
                //noinspection ResultOfMethodCallIgnored
                outputWav.delete();
                return false;
            }
        }
        return false;
    }

    public List<Float> extractAmplitudesFromWav(File wavFile) {
        return extractAmplitudesFromWav(wavFile, 150);
    }

    /** WAV 파일로부터 화면에 표시할 파형(진폭 리스트)을 추출합니다. (blocking, interruptible) */
    public List<Float> extractAmplitudesFromWav(File wavFile, int targetPoints) {
        List<Float> result = new ArrayList<>();
        if (!wavFile.exists() || wavFile.length() <= 44L) return result;
        try (RandomAccessFile source = new RandomAccessFile(wavFile, "r")) {
            long totalSamples = (source.length() - 44L) / 2L;
            int pointCount = (int) Math.min(Math.max(targetPoints, 1), totalSamples);
            if (pointCount == 0) return result;
            byte[] buffer = new byte[65536];
            for (int point = 0; point < pointCount; point++) {
                checkCancelled();
                long start = totalSamples * point / pointCount;
                long end = totalSamples * (point + 1) / pointCount;
                source.seek(44L + start * 2L);
                long remaining = (end - start) * 2L;
                float peak = 0f;
                while (remaining > 0L) {
                    checkCancelled();
                    int count = (int) Math.min(buffer.length, remaining);
                    source.readFully(buffer, 0, count);
                    for (int i = 0; i + 1 < count; i += 2) {
                        int sample = (short) ((buffer[i] & 255) | (buffer[i + 1] << 8));
                        peak = Math.max(peak, Math.abs(sample / 32768f));
                    }
                    remaining -= count;
                }
                result.add(clamp(peak, 0.02f, 1f));
            }
        } catch (CancellationException e) {
            throw e;
        } catch (Exception e) {
            Log.e(TAG, "Waveform extraction failed", e);
        }
        return result;
    }

    private void normalizeWavFile(File wavFile) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(wavFile, "r");
        long offset = 12L;
        long dataOffset = 44L;
        int dataSize = (int) (wavFile.length() - 44L);
        File normalized;
        try {
            while (offset + 8L <= wavFile.length()) {
                raf.seek(offset);
                byte[] idBuf = new byte[4];
                raf.readFully(idBuf);
                String chunkId = new String(idBuf, StandardCharsets.US_ASCII);
                int chunkSize = readInt(raf);

                if (chunkId.equals("data")) {
                    dataOffset = offset + 8L;
                    dataSize = Math.min(chunkSize, (int) (wavFile.length() - dataOffset));
                    break;
                }
                offset += 8L + chunkSize + (chunkSize & 1);
            }

            normalized = new File(wavFile.getParentFile(), wavFile.getName() + ".normalizing");
            try (FileOutputStream output = new FileOutputStream(normalized)) {
                writeWavHeader(output, dataSize);
                raf.seek(dataOffset);
                byte[] buffer = new byte[65536];
                int remaining = dataSize;
                while (remaining > 0) {
                    int count = Math.min(buffer.length, remaining);
                    raf.readFully(buffer, 0, count);
                    output.write(buffer, 0, count);
                    remaining -= count;
                }
            }
        } finally {
            raf.close();
        }
        if (!normalized.renameTo(wavFile)) throw new IllegalStateException("WAV 헤더 정규화 실패");
    }

    // ============================================================================ Preview

    public void startPreview(File rawWavFile, float startRatio, float endRatio, float initialRatio,
                             ProgressListener onProgressRatio, Runnable onPlaybackEnded) {
        startPreview(rawWavFile, startRatio, endRatio, initialRatio, onProgressRatio, onPlaybackEnded, null, 0L);
    }

    public void startPreview(
            File rawWavFile,
            float startRatio,
            float endRatio,
            float initialRatio,
            ProgressListener onProgressRatio,
            Runnable onPlaybackEnded,
            Context notificationContext,
            long durationMs
    ) {
        if (isPreviewing || !rawWavFile.exists()) return;

        previewStartRatio = clamp(startRatio, 0f, 1f);
        previewEndRatio = clamp(endRatio, previewStartRatio, 1f);
        isPreviewing = true;
        isPreviewPaused = false;
        requestedSeekRatio = Float.NaN;
        currentPreviewGlobalRatio = previewStartRatio
                + clamp(initialRatio, 0f, 1f) * (previewEndRatio - previewStartRatio);

        PreviewWorker worker = new PreviewWorker(rawWavFile, initialRatio, onProgressRatio,
                onPlaybackEnded, notificationContext, durationMs);
        previewThread = new Thread(worker, "SongRecorderPreview");
        previewThread.start();
    }

    /** Holds the per-playback mutable state that the Kotlin version kept in local closures. */
    private final class PreviewWorker implements Runnable {
        private final File rawWavFile;
        private final float initialRatio;
        private final ProgressListener onProgressRatio;
        private final Runnable onPlaybackEnded;
        private final Context notificationContext;
        private final long durationMs;

        private AudioTrack track;
        private long segmentBytes = 0L;
        private long segmentOriginByte = 0L;
        private long inputOffset = 0L;
        private long writtenFrames = 0L;
        private long lastUiUpdateNs = 0L;
        private long lastNotificationUpdateNs = 0L;

        PreviewWorker(File rawWavFile, float initialRatio, ProgressListener onProgressRatio,
                      Runnable onPlaybackEnded, Context notificationContext, long durationMs) {
            this.rawWavFile = rawWavFile;
            this.initialRatio = initialRatio;
            this.onProgressRatio = onProgressRatio;
            this.onPlaybackEnded = onPlaybackEnded;
            this.notificationContext = notificationContext;
            this.durationMs = durationMs;
        }

        private void progress(float ratio) {
            if (onProgressRatio != null) onProgressRatio.onProgress(ratio);
        }

        private void reportPlaybackPosition(boolean forceUi) {
            if (!Float.isNaN(requestedSeekRatio) || !isPreviewing) return;
            AudioTrack activeTrack = track;
            if (activeTrack == null) return;
            long playedFrames = activeTrack.getPlaybackHeadPosition() & 0xffffffffL;
            long bytesPosition = clamp(segmentOriginByte + playedFrames * 2L, 0L, segmentBytes);
            double bytesTotal = Math.max(segmentBytes, 1L);
            currentPreviewGlobalRatio = clamp(
                    previewStartRatio + (float) (bytesPosition / bytesTotal) * (previewEndRatio - previewStartRatio),
                    previewStartRatio, previewEndRatio);

            long now = System.nanoTime();
            if (forceUi || now - lastUiUpdateNs >= 40_000_000L) {
                progress(currentPreviewGlobalRatio);
                lastUiUpdateNs = now;
            }
            if (notificationContext != null && now - lastNotificationUpdateNs >= 250_000_000L) {
                RecordingService.updatePlaybackPosition(
                        isPreviewing && !isPreviewPaused,
                        (long) (currentPreviewGlobalRatio * durationMs),
                        durationMs);
                lastNotificationUpdateNs = now;
            }
        }

        private void releaseTrack() {
            AudioTrack old = track;
            track = null;
            if (activeAudioTrack == old) activeAudioTrack = null;
            if (old != null) {
                try { old.stop(); } catch (Exception ignored) { }
                try { old.release(); } catch (Exception ignored) { }
            }
        }

        private void createTrackAt(long byteOffset, int minBufferSize) {
            releaseTrack();
            AudioTrack newTrack = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(minBufferSize * 2)
                    .build();
            track = newTrack;
            activeAudioTrack = newTrack;
            segmentOriginByte = clamp(byteOffset, 0L, segmentBytes) & -2L;
            inputOffset = segmentOriginByte;
            writtenFrames = 0L;
            dspProcessor.resetState();
            if (!isPreviewPaused) newTrack.play();
        }

        @Override
        public void run() {
            RandomAccessFile pcmFile = null;
            long fileStartByte;
            boolean naturalCompletion = false;
            try {
                RandomAccessFile pcm = new RandomAccessFile(rawWavFile, "r");
                pcmFile = pcm;
                long totalFrames = Math.max(pcm.length() - 44L, 0L) / 2L;
                long startFrame = (long) (totalFrames * (double) previewStartRatio);
                long endFrame = (long) (totalFrames * (double) previewEndRatio);
                fileStartByte = 44L + startFrame * 2L;
                segmentBytes = (endFrame - startFrame) * 2L;
                if (segmentBytes <= 0L) return;

                int minBufferRaw = AudioTrack.getMinBufferSize(sampleRate,
                        AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
                int minBufferSize = minBufferRaw > 0 ? minBufferRaw : 4096;
                long startingByte = ((long) (segmentBytes * (double) clamp(initialRatio, 0f, 1f))) & -2L;
                createTrackAt(startingByte, minBufferSize);
                progress(currentPreviewGlobalRatio);
                byte[] outputBuffer = new byte[1024];

                while (isPreviewing) {
                    float seekRatio = requestedSeekRatio;
                    if (!Float.isNaN(seekRatio)) {
                        requestedSeekRatio = Float.NaN;
                        long requestedByte = ((long) (segmentBytes * (double) clamp(seekRatio, 0f, 1f))) & -2L;
                        createTrackAt(requestedByte, minBufferSize);
                        currentPreviewGlobalRatio = previewStartRatio
                                + ((float) segmentOriginByte / Math.max(segmentBytes, 1L))
                                * (previewEndRatio - previewStartRatio);
                        reportPlaybackPosition(true);
                    }

                    if (!isPreviewing) break;
                    AudioTrack currentTrack = track;
                    if (currentTrack == null) break;

                    if (isPreviewPaused) {
                        try { currentTrack.pause(); } catch (Exception ignored) { }
                        reportPlaybackPosition(false);
                        Thread.sleep(12L);
                        continue;
                    } else if (currentTrack.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                        try { currentTrack.play(); } catch (Exception ignored) { }
                    }

                    if (inputOffset >= segmentBytes) {
                        reportPlaybackPosition(false);
                        long playedFrames = currentTrack.getPlaybackHeadPosition() & 0xffffffffL;
                        // A seek to the very end can leave zero frames to write; it is
                        // still a completed playback, not a state that should wait forever.
                        if (playedFrames >= writtenFrames) {
                            naturalCompletion = true;
                            currentPreviewGlobalRatio = previewEndRatio;
                            progress(currentPreviewGlobalRatio);
                            break;
                        }
                        Thread.sleep(10L);
                        continue;
                    }

                    int length = ((int) Math.min(outputBuffer.length, segmentBytes - inputOffset)) & -2;
                    pcm.seek(fileStartByte + inputOffset);
                    pcm.readFully(outputBuffer, 0, length);
                    for (int i = 0; i < length; i += 2) {
                        int low = outputBuffer[i] & 0xFF;
                        int high = outputBuffer[i + 1] & 0xFF;
                        int sample = low | (high << 8);
                        int s16 = sample > 32767 ? sample - 65536 : sample;
                        float fVal = clamp(s16 / 32768f, -1f, 1f);
                        float processed = dspProcessor.processSample(fVal);
                        int pInt = clamp((int) (clamp(processed, -1f, 1f) * 32768f), -32768, 32767);
                        outputBuffer[i] = (byte) (pInt & 0xFF);
                        outputBuffer[i + 1] = (byte) ((pInt >> 8) & 0xFF);
                    }

                    int bufferOffset = 0;
                    while (bufferOffset < length && isPreviewing && Float.isNaN(requestedSeekRatio)) {
                        if (isPreviewPaused) {
                            reportPlaybackPosition(false);
                            Thread.sleep(12L);
                            continue;
                        }
                        if (currentTrack.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) currentTrack.play();
                        int bytesWritten = currentTrack.write(outputBuffer, bufferOffset,
                                length - bufferOffset, AudioTrack.WRITE_NON_BLOCKING);
                        if (bytesWritten > 0) {
                            bufferOffset += bytesWritten;
                            writtenFrames += bytesWritten / 2L;
                        } else if (bytesWritten < 0) {
                            // A negative AudioTrack result is a write failure.
                            if (Float.isNaN(requestedSeekRatio) && !isPreviewPaused && isPreviewing) {
                                isPreviewing = false;
                            }
                            break;
                        } else {
                            reportPlaybackPosition(false);
                            Thread.sleep(2L);
                        }
                    }
                    inputOffset += bufferOffset;
                    reportPlaybackPosition(false);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                Log.e(TAG, "Preview playback failed", e);
            } finally {
                try { if (pcmFile != null) pcmFile.close(); } catch (Exception ignored) { }
                releaseTrack();
                isPreviewing = false;
                isPreviewPaused = false;
                requestedSeekRatio = Float.NaN;
                if (notificationContext != null) {
                    RecordingService.updatePlaybackUiState(false,
                            (long) (currentPreviewGlobalRatio * durationMs), durationMs);
                }
                // Keep the final cursor position so another Play action can restart from it.
                if (naturalCompletion) currentPreviewGlobalRatio = previewEndRatio;
                if (onPlaybackEnded != null) onPlaybackEnded.run();
            }
        }
    }

    public void pausePreview() {
        if (!isPreviewing || isPreviewPaused) return;
        isPreviewPaused = true;
        AudioTrack t = activeAudioTrack;
        try { if (t != null) t.pause(); } catch (Exception ignored) { }
    }

    public void resumePreview() {
        if (!isPreviewing || !isPreviewPaused) return;
        isPreviewPaused = false;
    }

    /** Seek to a position relative to the selected preview range (0..1). */
    public void seekPreviewToRatio(float relativeRatio) {
        if (!isPreviewing) return;
        float ratio = clamp(relativeRatio, 0f, 1f);
        currentPreviewGlobalRatio = previewStartRatio + ratio * (previewEndRatio - previewStartRatio);
        requestedSeekRatio = ratio;
        AudioTrack t = activeAudioTrack;
        try { if (t != null) t.pause(); } catch (Exception ignored) { }
    }

    public void stopPreview() {
        isPreviewing = false;
        requestedSeekRatio = Float.NaN;
        AudioTrack t = activeAudioTrack;
        try { if (t != null) t.stop(); } catch (Exception ignored) { }
        Thread thread = previewThread;
        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public void seekToGlobalRatio(float ratio) {
        float target = clamp(ratio, 0f, 1f);
        currentPreviewGlobalRatio = target;
        if (isPreviewing) {
            float span = Math.max(previewEndRatio - previewStartRatio, 0.000001f);
            seekPreviewToRatio((target - previewStartRatio) / span);
        }
    }

    // ============================================================================ Rendering

    public boolean renderAndEncodeToCache(File inputWav, File outputFile, String format, EditMode mode,
                                          float startRatio, float endRatio) throws IOException {
        return renderAndEncodeToCache(inputWav, outputFile, format, mode, startRatio, endRatio, 44100, 192);
    }

    /** Blocking render + encode. Throws {@link CancellationException} if the thread is interrupted. */
    public boolean renderAndEncodeToCache(
            File inputWav,
            File outputFile,
            String format,
            EditMode mode,
            float startRatio,
            float endRatio,
            int outputSampleRate,
            int bitrateKbps
    ) throws IOException {
        String ext = format.toLowerCase(Locale.US);
        if (outputFile.exists()) //noinspection ResultOfMethodCallIgnored
            outputFile.delete();

        if (ext.equals("wav") && outputSampleRate == sampleRate) {
            return processWavWithDsp(inputWav, outputFile, mode, startRatio, endRatio);
        }

        File processedWav = File.createTempFile("processed_", ".wav", inputWav.getParentFile());
        try {
            boolean success = processWavWithDsp(inputWav, processedWav, mode, startRatio, endRatio);
            if (!success || !processedWav.exists()) return false;
            checkCancelled();
            List<String> args = new ArrayList<>(Arrays.asList("-y", "-i", processedWav.getAbsolutePath(), "-vn",
                    "-ar", String.valueOf(outputSampleRate), "-ac", "1"));
            switch (ext) {
                case "wav":
                    args.addAll(Arrays.asList("-c:a", "pcm_s16le"));
                    break;
                case "mp3":
                    args.addAll(Arrays.asList("-b:a", bitrateKbps + "k"));
                    break;
                default:
                    args.addAll(Arrays.asList("-c:a", "aac", "-b:a", bitrateKbps + "k", "-movflags", "+faststart"));
                    break;
            }
            args.add(outputFile.getAbsolutePath());
            FFmpegSession session = FFmpegKit.executeWithArguments(args.toArray(new String[0]));
            checkCancelled();
            return ReturnCode.isSuccess(session.getReturnCode()) && outputFile.exists() && outputFile.length() > 0L;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            processedWav.delete();
        }
    }

    private boolean processWavWithDsp(File input, File output, EditMode mode, float startRatio, float endRatio) {
        try {
            EffectChainPreset snapshot = EffectChainPreset.capture(dspProcessor, "export", "Export");
            DspProcessor processor = new DspProcessor();
            snapshot.applyTo(processor);
            try (RandomAccessFile source = new RandomAccessFile(input, "r")) {
                long samples = Math.max(source.length() - 44L, 0L) / 2L;
                if (samples == 0L) return false;
                long start = (long) (samples * (double) clamp(startRatio, 0f, 1f));
                long end = (long) (samples * (double) clamp(endRatio, startRatio, 1f));
                long[][] ranges = mode == EditMode.CUT
                        ? new long[][]{{0L, start}, {end, samples}}
                        : new long[][]{{start, end}};
                long byteCount = 0L;
                for (long[] r : ranges) byteCount += (r[1] - r[0]) * 2L;
                if (byteCount > Integer.MAX_VALUE) throw new IllegalArgumentException("지원 파일 크기 초과");
                try (FileOutputStream target = new FileOutputStream(output)) {
                    writeWavHeader(target, (int) byteCount);
                    byte[] buffer = new byte[65536];
                    for (long[] r : ranges) {
                        long from = r[0];
                        long to = r[1];
                        source.seek(44L + from * 2L);
                        long remaining = (to - from) * 2L;
                        while (remaining > 0) {
                            checkCancelled();
                            int count = (int) Math.min(buffer.length, remaining);
                            source.readFully(buffer, 0, count);
                            for (int i = 0; i + 1 < count; i += 2) {
                                int sample = (short) ((buffer[i] & 255) | (buffer[i + 1] << 8));
                                int value = clamp((int) (processor.processSample(sample / 32768f) * 32768f), -32768, 32767);
                                buffer[i] = (byte) value;
                                buffer[i + 1] = (byte) (value >> 8);
                            }
                            target.write(buffer, 0, count);
                            remaining -= count;
                        }
                    }
                }
            }
            return true;
        } catch (CancellationException e) {
            //noinspection ResultOfMethodCallIgnored
            output.delete();
            throw e;
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            output.delete();
            Log.e(TAG, "Rendering failed", e);
            return false;
        }
    }

    /** Returns {sampleRate, bitrateKbps}. */
    private int[] outputSettings(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("SongRecorderPrefs", Context.MODE_PRIVATE);
        int rate = "48000 Hz".equals(prefs.getString("samplerate", "44100 Hz")) ? 48000 : 44100;
        String bitrateLabel = prefs.getString("bitrate", "192 kbps");
        int bitrate;
        if ("128 kbps".equals(bitrateLabel)) bitrate = 128;
        else if ("320 kbps".equals(bitrateLabel)) bitrate = 320;
        else bitrate = 192;
        return new int[]{rate, bitrate};
    }

    private static void copyStream(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
    }

    /** Blocking export. Returns the saved URI/path string, or null on failure. */
    @SuppressLint("NewApi")
    public String exportToMusicFolder(
            Context context,
            File rawWavFile,
            String format,
            String customFileName,
            EditMode mode,
            float startRatio,
            float endRatio,
            Uri customTreeUri
    ) throws IOException {
        String ext = format.toLowerCase(Locale.US);
        String cleanName = customFileName.trim().isEmpty() ? "Song_" + System.currentTimeMillis() : customFileName;

        // SharedPreferences에서 파일 이름 규칙 읽기 및 날짜 조합
        SharedPreferences prefs = context.getSharedPreferences("SongRecorderPrefs", Context.MODE_PRIVATE);
        String rule = prefs.getString("filename_rule", "(없음)");
        if (rule == null) rule = "(없음)";

        String dateStr = "";
        if (!rule.equals("(없음)")) {
            String pattern;
            switch (rule) {
                case "YY.MM.DD": pattern = "yy.MM.dd"; break;
                case "YYYY.MM.DD": pattern = "yyyy.MM.dd"; break;
                case "YYMMDD": pattern = "yyMMdd"; break;
                case "YYYYMMDD": pattern = "yyyyMMdd"; break;
                case "YY-MM-DD": pattern = "yy-MM-dd"; break;
                case "YYYY-MM-DD": pattern = "yyyy-MM-dd"; break;
                default: pattern = ""; break;
            }
            if (!pattern.isEmpty()) {
                dateStr = new SimpleDateFormat(pattern, Locale.getDefault()).format(new Date());
            }
        }

        String finalBaseName = dateStr.isEmpty() ? cleanName : cleanName + " " + dateStr;
        String fileName = finalBaseName.toLowerCase(Locale.ROOT).endsWith("." + ext)
                ? finalBaseName : finalBaseName + "." + ext;
        File cacheFile = new File(context.getCacheDir(), fileName);

        int[] settings = outputSettings(context);
        boolean success = renderAndEncodeToCache(rawWavFile, cacheFile, ext, mode, startRatio, endRatio,
                settings[0], settings[1]);
        if (!success || !cacheFile.exists()) return null;

        String mimeType;
        switch (ext) {
            case "mp3": mimeType = "audio/mpeg"; break;
            case "wav": mimeType = "audio/x-wav"; break;
            default: mimeType = "audio/mp4"; break;
        }

        if (customTreeUri != null) {
            try {
                String docId = DocumentsContract.getTreeDocumentId(customTreeUri);
                Uri parentUri = DocumentsContract.buildDocumentUriUsingTree(customTreeUri, docId);
                Uri targetUri = DocumentsContract.createDocument(context.getContentResolver(), parentUri, mimeType, fileName);
                if (targetUri != null) {
                    try (OutputStream output = context.getContentResolver().openOutputStream(targetUri);
                         InputStream input = new FileInputStream(cacheFile)) {
                        if (output == null) throw new IOException("openOutputStream returned null");
                        copyStream(input, output);
                    }
                    //noinspection ResultOfMethodCallIgnored
                    cacheFile.delete();
                    return targetUri.toString();
                }
            } catch (Exception ignored) {
            }
        }

        if (Build.VERSION.SDK_INT < 29) {
            File base = context.getExternalFilesDir(null);
            if (base == null) return null;
            File directory = new File(base, "SongRecorder");
            //noinspection ResultOfMethodCallIgnored
            directory.mkdirs();
            File destination = new File(directory, fileName);
            try (InputStream input = new FileInputStream(cacheFile);
                 OutputStream output = new FileOutputStream(destination, false)) {
                copyStream(input, output);
            }
            //noinspection ResultOfMethodCallIgnored
            cacheFile.delete();
            return destination.getAbsolutePath();
        }

        ContentValues contentValues = new ContentValues();
        contentValues.put(MediaStore.Audio.Media.DISPLAY_NAME, fileName);
        contentValues.put(MediaStore.Audio.Media.MIME_TYPE, mimeType);
        contentValues.put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/SongRecorder");
        contentValues.put(MediaStore.Audio.Media.IS_PENDING, 1);

        Uri collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri itemUri = context.getContentResolver().insert(collection, contentValues);

        if (itemUri != null) {
            try (OutputStream output = context.getContentResolver().openOutputStream(itemUri);
                 InputStream input = new FileInputStream(cacheFile)) {
                if (output == null) throw new IOException("openOutputStream returned null");
                copyStream(input, output);
            }
            contentValues.clear();
            contentValues.put(MediaStore.Audio.Media.IS_PENDING, 0);
            context.getContentResolver().update(itemUri, contentValues, null, null);
            //noinspection ResultOfMethodCallIgnored
            cacheFile.delete();
            return itemUri.toString();
        }

        return null;
    }

    /**
     * Renders a share-ready copy without opening any Activity. RecordingService uses this
     * before attaching a direct KakaoTalk PendingIntent to the notification. (blocking)
     */
    public File prepareKakaoVoiceMessageFile(Context context, File inputWav, String format,
                                             float startRatio, float endRatio) throws IOException {
        if (!inputWav.exists() || inputWav.length() <= 44L) return null;

        File shareDir = new File(context.getCacheDir(), "shared_audio");
        //noinspection ResultOfMethodCallIgnored
        shareDir.mkdirs();
        // Use a unique file for each render.
        String lower = format.toLowerCase(Locale.US);
        String ext = (lower.equals("m4a") || lower.equals("mp3") || lower.equals("wav")) ? lower : "m4a";
        File shareFile = new File(shareDir, "VoiceMsg_" + System.currentTimeMillis() + "_"
                + (System.nanoTime() % 1_000_000L) + "." + ext);
        EditMode mode = (startRatio > 0.001f || endRatio < 0.999f) ? EditMode.TRIM : EditMode.NONE;

        // Prune only stale exports; do not remove the file referenced by the live notification.
        long expiry = System.currentTimeMillis() - 24L * 60L * 60L * 1000L;
        File[] existing = shareDir.listFiles();
        if (existing != null) {
            for (File f : existing) {
                if (f.isFile() && f.lastModified() < expiry) //noinspection ResultOfMethodCallIgnored
                    f.delete();
            }
        }

        int[] settings = outputSettings(context);
        boolean success = renderAndEncodeToCache(inputWav, shareFile, ext, mode, startRatio, endRatio,
                settings[0], settings[1]);
        if (success && shareFile.exists() && shareFile.length() > 0L) return shareFile;
        //noinspection ResultOfMethodCallIgnored
        shareFile.delete();
        return null;
    }

    /** Blocking: renders on the calling (background) thread, then launches the share UI on main. */
    public boolean shareAsKakaoVoiceMessage(Context context, File inputWav, String format,
                                            float startRatio, float endRatio) throws IOException {
        File shareFile = prepareKakaoVoiceMessageFile(context, inputWav, format, startRatio, endRatio);
        if (shareFile == null) return false;

        final boolean[] result = {false};
        final Object lock = new Object();
        final boolean[] done = {false};
        Runnable launch = () -> {
            try {
                Uri contentUri = FileProvider.getUriForFile(context,
                        context.getPackageName() + ".fileprovider", shareFile);

                Intent shareIntent = new Intent(Intent.ACTION_SEND_MULTIPLE);
                shareIntent.setType("*/*");
                ArrayList<Uri> uris = new ArrayList<>();
                uris.add(contentUri);
                shareIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
                shareIntent.setClipData(ClipData.newRawUri("", contentUri));
                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                shareIntent.setPackage("com.kakao.talk");
                Intent target;
                if (shareIntent.resolveActivity(context.getPackageManager()) != null) {
                    target = shareIntent;
                } else {
                    shareIntent.setPackage(null);
                    target = Intent.createChooser(shareIntent, "음성 메시지 공유");
                }
                if (!(context instanceof Activity)) target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(target);
                result[0] = true;
            } catch (Exception e) {
                Log.e(TAG, "Unable to launch audio sharing target", e);
                result[0] = false;
            } finally {
                synchronized (lock) {
                    done[0] = true;
                    lock.notifyAll();
                }
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            launch.run();
        } else {
            new Handler(Looper.getMainLooper()).post(launch);
            synchronized (lock) {
                while (!done[0]) {
                    try {
                        lock.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new CancellationException("cancelled");
                    }
                }
            }
        }
        return result[0];
    }

    // ============================================================================ WAV helpers

    private void convertPcmToWav(File pcmFile, File wavFile) throws IOException {
        int pcmLength = (int) pcmFile.length();
        try (FileOutputStream fos = new FileOutputStream(wavFile);
             FileInputStream fis = new FileInputStream(pcmFile)) {
            writeWavHeader(fos, pcmLength);
            byte[] buf = new byte[1024];
            int read;
            while ((read = fis.read(buf)) != -1) {
                fos.write(buf, 0, read);
            }
        }
    }

    private void writeWavHeader(FileOutputStream fos, int pcmLength) throws IOException {
        int totalDataLen = pcmLength + 36;
        int byteRate = sampleRate * 2;
        byte[] header = new byte[44];
        header[0] = 'R'; header[1] = 'I'; header[2] = 'F'; header[3] = 'F';
        header[4] = (byte) (totalDataLen & 0xff);
        header[5] = (byte) ((totalDataLen >> 8) & 0xff);
        header[6] = (byte) ((totalDataLen >> 16) & 0xff);
        header[7] = (byte) ((totalDataLen >> 24) & 0xff);
        header[8] = 'W'; header[9] = 'A'; header[10] = 'V'; header[11] = 'E';
        header[12] = 'f'; header[13] = 'm'; header[14] = 't'; header[15] = ' ';
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0;
        header[20] = 1; header[21] = 0;
        header[22] = 1; header[23] = 0;
        header[24] = (byte) (sampleRate & 0xff);
        header[25] = (byte) ((sampleRate >> 8) & 0xff);
        header[26] = (byte) ((sampleRate >> 16) & 0xff);
        header[27] = (byte) ((sampleRate >> 24) & 0xff);
        header[28] = (byte) (byteRate & 0xff);
        header[29] = (byte) ((byteRate >> 8) & 0xff);
        header[30] = (byte) ((byteRate >> 16) & 0xff);
        header[31] = (byte) ((byteRate >> 24) & 0xff);
        header[32] = 2; header[33] = 0;
        header[34] = 16; header[35] = 0;
        header[36] = 'd'; header[37] = 'a'; header[38] = 't'; header[39] = 'a';
        header[40] = (byte) (pcmLength & 0xff);
        header[41] = (byte) ((pcmLength >> 8) & 0xff);
        header[42] = (byte) ((pcmLength >> 16) & 0xff);
        header[43] = (byte) ((pcmLength >> 24) & 0xff);
        fos.write(header, 0, 44);
    }

    private int readInt(RandomAccessFile raf) throws IOException {
        byte[] b = new byte[4];
        raf.readFully(b);
        return (b[0] & 0xFF) | ((b[1] & 0xFF) << 8) | ((b[2] & 0xFF) << 16) | ((b[3] & 0xFF) << 24);
    }
}
