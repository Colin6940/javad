package com.mistirain.SongRecorder;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.File;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Java/View port of the former SplitRecorderEditorScreen composable. */
public class RecorderEditorView extends LinearLayout implements AppAudioRuntime.RevisionListener {

    /** Activity services the screen needs. */
    public interface Host {
        void requestRecordPermission(Ui.BoolListener onResult);
        void finishHost();
    }

    private static final List<String> FORMATS = Arrays.asList("m4a", "mp3", "wav");

    private final AudioEngine audioEngine;
    private final Host host;
    private final File rawWavFile;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    // ---- state
    private boolean isRecording;
    private boolean isPlaying;
    private boolean isPlaybackPaused;
    private boolean isSeeking = false;
    private boolean isProcessing = false;
    private float durationSec = 0f;
    private float selStart = 0f;
    private float selEnd = 1f;
    private float playheadRatio;
    private String selectedFormat;
    private boolean hasRecordedFile = false;
    private boolean initialStateReady = false;
    private boolean finishAfterStop = false;
    private boolean attached = false;
    private Uri storagePathUri;
    private String storagePathDisplay;
    private final ArrayDeque<RecordingNotificationCommandBus.Event> pendingCommands = new ArrayDeque<>();

    // ---- views
    private final ChipGroup formatGroup;
    private final MaterialButton saveButton;
    private final MaterialButton shareButton;
    private final WaveformEditorView waveform;
    private final LinearLayout reviewBlock;
    private final TextView selectionLabel;
    private final PlaybackTimelineView timeline;
    private final MaterialButton recordButton;
    private final MaterialButton playButton;

    private final Runnable pollTick = new Runnable() {
        @Override
        public void run() {
            if (!isSeeking) playheadRatio = audioEngine.getCurrentPreviewGlobalRatio();
            boolean playing = audioEngine.isPreviewActive() && !audioEngine.isPreviewPausedState();
            boolean paused = audioEngine.isPreviewPausedState();
            if (playing != isPlaying || paused != isPlaybackPaused) {
                isPlaying = playing;
                isPlaybackPaused = paused;
                updateUi();
            } else {
                updatePlayhead();
            }
            main.postDelayed(this, 40L);
        }
    };

    public RecorderEditorView(Context c, AudioEngine audioEngine, DspProcessor dsp, Host host,
                              String defaultFormat, Uri storagePathUri, String storagePathDisplay) {
        super(c);
        setOrientation(VERTICAL);
        this.audioEngine = audioEngine;
        this.host = host;
        this.storagePathUri = storagePathUri;
        this.storagePathDisplay = storagePathDisplay;
        rawWavFile = AppAudioRuntime.rawRecordingFile(c);
        isRecording = audioEngine.isRecordingActive();
        isPlaying = audioEngine.isPreviewActive() && !audioEngine.isPreviewPausedState();
        isPlaybackPaused = audioEngine.isPreviewPausedState();
        playheadRatio = audioEngine.getCurrentPreviewGlobalRatio();
        selectedFormat = defaultFormat;

        // ================= top half (weight 0.47)
        LinearLayout top = Ui.vertical(c);
        top.setPadding(Ui.dp(c, 10), Ui.dp(c, 6), Ui.dp(c, 10), Ui.dp(c, 6));
        addView(top, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 0.47f));

        LinearLayout headerRow = Ui.horizontal(c);
        formatGroup = Ui.chipGroup(c, Arrays.asList("M4A", "MP3", "WAV"), Math.max(0, FORMATS.indexOf(defaultFormat)),
                idx -> selectedFormat = FORMATS.get(idx));
        headerRow.addView(formatGroup, Ui.weight(1f));
        saveButton = Ui.button(c, "저장하기", v -> saveRecording());
        saveButton.setTextSize(11);
        styleSmall(saveButton);
        shareButton = Ui.button(c, "카톡 공유", v -> shareRecording());
        shareButton.setTextSize(11);
        styleSmall(shareButton);
        headerRow.addView(saveButton, new LayoutParams(LayoutParams.WRAP_CONTENT, Ui.dp(c, 30)));
        LayoutParams shareLp = new LayoutParams(LayoutParams.WRAP_CONTENT, Ui.dp(c, 30));
        shareLp.leftMargin = Ui.dp(c, 4);
        headerRow.addView(shareButton, shareLp);
        top.addView(headerRow, Ui.matchWrap());

        waveform = new WaveformEditorView(c);
        waveform.setListener((s, e) -> {
            selStart = s;
            selEnd = e;
            updateSelectionLabel();
        });
        LayoutParams waveLp = new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f);
        waveLp.topMargin = Ui.dp(c, 4);
        waveLp.bottomMargin = Ui.dp(c, 4);
        top.addView(waveform, waveLp);

        reviewBlock = Ui.vertical(c);
        selectionLabel = Ui.text(c, "", 11);
        reviewBlock.addView(selectionLabel, Ui.matchWrap());
        timeline = new PlaybackTimelineView(c);
        timeline.setListener(new PlaybackTimelineView.Listener() {
            @Override
            public void onPosition(float ratio) {
                isSeeking = true;
                playheadRatio = ratio;
                updatePlayhead();
            }

            @Override
            public void onSeekFinished() {
                audioEngine.seekToGlobalRatio(playheadRatio);
                RecordingService.updatePlaybackUiState(isPlaying,
                        (long) (playheadRatio * durationSec * 1000f), (long) (durationSec * 1000f));
                isSeeking = false;
            }
        });
        reviewBlock.addView(timeline, Ui.matchWrap());
        top.addView(reviewBlock, Ui.matchWrap());

        LinearLayout controls = Ui.horizontal(c);
        controls.setGravity(android.view.Gravity.CENTER);
        recordButton = Ui.button(c, "새 녹음", v -> {
            if (isRecording || audioEngine.isRecordingActive()) stopRecordingAndPrepareReview();
            else startNewRecording();
        });
        recordButton.setTextSize(13);
        playButton = Ui.button(c, "재생", v -> togglePlayback());
        playButton.setTextSize(13);
        int pad = Ui.dp(c, 16);
        recordButton.setPadding(pad, 0, pad, 0);
        playButton.setPadding(pad, 0, pad, 0);
        controls.addView(recordButton, new LayoutParams(LayoutParams.WRAP_CONTENT, Ui.dp(c, 36)));
        LayoutParams playLp = new LayoutParams(LayoutParams.WRAP_CONTENT, Ui.dp(c, 36));
        playLp.leftMargin = Ui.dp(c, 10);
        controls.addView(playButton, playLp);
        top.addView(controls, Ui.matchWrap());

        // ================= divider + effects (weight 0.53)
        addView(Ui.divider(c, 2, Color.LTGRAY));
        EffectorPanel effects = new EffectorPanel(c, dsp);
        addView(effects, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 0.53f));

        updateUi();
    }

    private void styleSmall(MaterialButton b) {
        int p = Ui.dp(getContext(), 8);
        b.setPadding(p, 0, p, 0);
        b.setMinHeight(Ui.dp(getContext(), 30));
        b.setMinimumHeight(Ui.dp(getContext(), 30));
    }

    // =============================================================== public API for MainActivity

    public void setDefaultFormat(String format) {
        selectedFormat = format;
        int idx = FORMATS.indexOf(format);
        if (idx >= 0) Ui.selectChip(formatGroup, idx);
    }

    public void setStorage(Uri uri, String display) {
        storagePathUri = uri;
        storagePathDisplay = display;
    }

    public boolean isRecordingNow() {
        return isRecording;
    }

    /** Back press while recording: confirm stop. */
    public void showExitConfirmDialog() {
        new MaterialAlertDialogBuilder(getContext())
                .setTitle("녹음 중지")
                .setMessage("녹음을 중단합니까?")
                .setPositiveButton("확인", (d, w) -> {
                    finishAfterStop = true;
                    stopRecordingAndPrepareReview();
                })
                .setNegativeButton("취소", null)
                .show();
    }

    public void enqueueNotificationCommand(RecordingNotificationCommandBus.Event event) {
        pendingCommands.add(event);
        drainCommands();
    }

    public void release() {
        worker.shutdownNow();
    }

    // =============================================================== helpers

    private void runIo(Runnable r) {
        try {
            worker.execute(r);
        } catch (Exception ignored) {
            // executor shut down (Activity destroyed)
        }
    }

    private void onMain(Runnable r) {
        main.post(r);
    }

    private void toast(String message, int length) {
        Toast.makeText(getContext(), message, length).show();
    }

    private float currentTakeDurationSec() {
        long length = rawWavFile.length();
        return rawWavFile.exists() && length > 44L ? (length - 44L) / (44100f * 2f) : 0f;
    }

    private boolean hasUsableTakeNow() {
        return !audioEngine.isRecordingActive() && currentTakeDurationSec() > 0f;
    }

    private boolean hasRecorded() {
        return hasRecordedFile && !isRecording;
    }

    private boolean hasUsableTake() {
        return hasRecorded() || (!audioEngine.isRecordingActive() && rawWavFile.exists() && rawWavFile.length() > 44L);
    }

    private float effectiveDurationSec() {
        return durationSec > 0f ? durationSec : currentTakeDurationSec();
    }

    private void loadWaveformAsync(Runnable after) {
        runIo(() -> {
            List<Float> extracted;
            try {
                extracted = audioEngine.extractAmplitudesFromWav(rawWavFile, 160);
            } catch (Exception e) {
                extracted = java.util.Collections.emptyList();
            }
            final List<Float> values = extracted;
            onMain(() -> {
                waveform.setAmplitudes(values);
                if (after != null) after.run();
            });
        });
    }

    private void resetTakeState() {
        hasRecordedFile = false;
        durationSec = 0f;
        selStart = 0f;
        selEnd = 1f;
        playheadRatio = 0f;
        audioEngine.seekToGlobalRatio(0f);
        waveform.clearAmplitudes();
        waveform.setSelection(0f, 1f);
    }

    private void updateSelectionLabel() {
        selectionLabel.setText("선택 구간 " + PlaybackTimelineView.playbackTime(selStart * durationSec)
                + " – " + PlaybackTimelineView.playbackTime(selEnd * durationSec));
    }

    private void updatePlayhead() {
        waveform.setPosition(playheadRatio);
        if (reviewBlock.getVisibility() == VISIBLE) timeline.setState(playheadRatio, durationSec);
    }

    private void updateUi() {
        boolean recorded = hasRecorded();
        saveButton.setEnabled(recorded && !isProcessing);
        shareButton.setEnabled(recorded && !isProcessing);
        boolean shareEnabled = recorded && !isProcessing;
        Ui.tintButton(shareButton,
                shareEnabled ? Color.parseColor("#FEE500") : Color.parseColor("#33888888"),
                shareEnabled ? Color.parseColor("#191919") : Color.GRAY);

        waveform.setEditable(recorded && !isProcessing);
        waveform.setSelection(selStart, selEnd);
        reviewBlock.setVisibility(recorded ? VISIBLE : GONE);
        timeline.setEnabled(!isProcessing);
        updateSelectionLabel();

        recordButton.setEnabled(!isProcessing);
        recordButton.setText(isRecording ? "녹음 정지" : "새 녹음");
        if (isRecording) {
            Ui.tintButton(recordButton, Color.RED, Color.WHITE);
        } else {
            Ui.tintButton(recordButton, Ui.primary(getContext()),
                    com.google.android.material.color.MaterialColors.getColor(getContext(),
                            com.google.android.material.R.attr.colorOnPrimary, Color.WHITE));
        }
        if (isProcessing) recordButton.setAlpha(0.5f); else recordButton.setAlpha(1f);
        playButton.setEnabled(recorded && !isProcessing);
        playButton.setText(isPlaying ? "일시정지" : "재생");
        updatePlayhead();
    }

    // =============================================================== actions

    private void saveRecording() {
        if (!((hasUsableTake() || hasUsableTakeNow()) && !isProcessing)) return;
        Context c = getContext();
        TextInputLayout layout = new TextInputLayout(c, null,
                com.google.android.material.R.attr.textInputOutlinedStyle);
        layout.setHint("파일 이름 입력");
        TextInputEditText input = new TextInputEditText(layout.getContext());
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText("Song_" + System.currentTimeMillis());
        layout.addView(input);
        FrameLayout wrapper = new FrameLayout(c);
        int p = Ui.dp(c, 20);
        wrapper.setPadding(p, Ui.dp(c, 8), p, 0);
        wrapper.addView(layout);

        new MaterialAlertDialogBuilder(c)
                .setTitle("파일 저장")
                .setView(wrapper)
                .setPositiveButton("저장", (d, w) -> performSave(textOf(input)))
                .setNegativeButton("취소", null)
                .show();
    }

    private static String textOf(EditText e) {
        return e.getText() == null ? "" : e.getText().toString();
    }

    private void performSave(String customFileName) {
        audioEngine.stopPreview();
        isPlaying = false;
        isPlaybackPaused = false;
        isProcessing = true;
        updateUi();
        RecordingService.updatePlaybackUiState(false,
                (long) (playheadRatio * durationSec * 1000f), (long) (durationSec * 1000f));
        final float start = selStart;
        final float end = selEnd;
        final String format = selectedFormat;
        final Uri treeUri = storagePathUri;
        final String display = storagePathDisplay;
        final Context appContext = getContext().getApplicationContext();
        runIo(() -> {
            AudioEngine.EditMode mode = (start > 0.001f || end < 0.999f)
                    ? AudioEngine.EditMode.TRIM : AudioEngine.EditMode.NONE;
            String savedName = null;
            try {
                savedName = audioEngine.exportToMusicFolder(appContext, rawWavFile, format, customFileName,
                        mode, start, end, treeUri);
            } catch (Exception ignored) { }
            final String result = savedName;
            onMain(() -> {
                isProcessing = false;
                updateUi();
                Toast.makeText(appContext, result != null ? display + "/" + result + " 저장 완료" : "저장 실패",
                        Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void shareRecording() {
        float effective = effectiveDurationSec();
        if (!((hasUsableTake() || effective > 0f) && !isProcessing)) return;
        audioEngine.stopPreview();
        isPlaying = false;
        isPlaybackPaused = false;
        isProcessing = true;
        updateUi();
        RecordingService.updatePlaybackUiState(false,
                (long) (playheadRatio * effective * 1000f), (long) (effective * 1000f));
        final float start = selStart;
        final float end = selEnd;
        final String format = selectedFormat;
        final Context context = getContext();
        runIo(() -> {
            boolean ok;
            try {
                ok = audioEngine.shareAsKakaoVoiceMessage(context, rawWavFile, format, start, end);
            } catch (Exception e) {
                ok = false;
            }
            final boolean success = ok;
            onMain(() -> {
                isProcessing = false;
                updateUi();
                if (!success) toast("변환 또는 공유 준비에 실패했습니다.", Toast.LENGTH_SHORT);
            });
        });
    }

    private void startPlaybackAtCursor() {
        final float effective = effectiveDurationSec();
        if (!((hasUsableTake() || effective > 0f) && !isProcessing && effective > 0f)) return;
        float cursor = playheadRatio >= 0.99999f ? 0f : Ui.clamp(playheadRatio, 0f, 1f);
        playheadRatio = cursor;
        isPlaying = true;
        isPlaybackPaused = false;
        audioEngine.startPreview(rawWavFile, 0f, 1f, cursor,
                ratio -> onMain(() -> {
                    if (!isSeeking) {
                        playheadRatio = audioEngine.getCurrentPreviewGlobalRatio();
                        updatePlayhead();
                    }
                }),
                () -> onMain(() -> {
                    isPlaying = false;
                    isPlaybackPaused = false;
                    playheadRatio = audioEngine.getCurrentPreviewGlobalRatio();
                    updateUi();
                    RecordingService.updatePlaybackUiState(false,
                            (long) (playheadRatio * effective * 1000f), (long) (effective * 1000f));
                }),
                getContext().getApplicationContext(),
                (long) (effective * 1000f));
        RecordingService.updatePlaybackUiState(true,
                (long) (cursor * effective * 1000f), (long) (effective * 1000f));
        updateUi();
    }

    private void togglePlayback() {
        if (isPlaying) {
            audioEngine.pausePreview();
            isPlaying = false;
            isPlaybackPaused = audioEngine.isPreviewPausedState();
            RecordingService.updatePlaybackUiState(false,
                    (long) (audioEngine.getCurrentPreviewGlobalRatio() * durationSec * 1000f),
                    (long) (durationSec * 1000f));
            updateUi();
        } else if (isPlaybackPaused) {
            audioEngine.resumePreview();
            isPlaybackPaused = false;
            isPlaying = true;
            RecordingService.updatePlaybackUiState(true,
                    (long) (audioEngine.getCurrentPreviewGlobalRatio() * durationSec * 1000f),
                    (long) (durationSec * 1000f));
            updateUi();
        } else {
            startPlaybackAtCursor();
        }
    }

    private void stopRecordingAndPrepareReview() {
        if (isProcessing) return;
        isProcessing = true;
        updateUi();
        final Context appContext = getContext().getApplicationContext();
        runIo(() -> {
            audioEngine.stopRecording();
            onMain(() -> {
                isRecording = false;
                isPlaying = false;
                isPlaybackPaused = false;
                long wavSize = rawWavFile.length();
                hasRecordedFile = rawWavFile.exists() && wavSize > 44L;
                durationSec = hasRecordedFile ? (wavSize - 44L) / (44100f * 2f) : 0f;
                selStart = 0f;
                selEnd = 1f;
                playheadRatio = 0f;
                audioEngine.seekToGlobalRatio(0f);
                Runnable done = () -> {
                    isProcessing = false;
                    updateUi();
                    if (finishAfterStop) host.finishHost();
                };
                if (hasRecordedFile) {
                    RecordingService.showReviewNotification(appContext, rawWavFile, (long) (durationSec * 1000f));
                    updateUi();
                    loadWaveformAsync(done);
                } else {
                    RecordingService.updatePlaybackUiState(false, 0L, 0L);
                    done.run();
                }
            });
        });
    }

    private void startNewRecording() {
        Context c = getContext();
        boolean permission = ContextCompat.checkSelfPermission(c, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        if (!permission) {
            host.requestRecordPermission(granted -> {
                if (granted) startNewRecording();
            });
            toast("마이크 녹음 권한이 필요합니다.", Toast.LENGTH_SHORT);
            return;
        }
        if (isRecording) return;
        // Notification permission is requested only from the dedicated notification settings.
        audioEngine.stopPreview();
        isPlaying = false;
        isPlaybackPaused = false;
        resetTakeState();
        //noinspection ResultOfMethodCallIgnored
        rawWavFile.delete();
        //noinspection ResultOfMethodCallIgnored
        new File(c.getCacheDir(), "temp_pcm.pcm").delete();

        AppAudioRuntime.markAppRecordingStarted();
        // Snapshot the whole chain at capture start, so notification capture can reuse it.
        AppAudioRuntime.persistWorkingSnapshot(c.getApplicationContext());
        isRecording = true;
        updateUi();
        final Context appContext = c.getApplicationContext();
        audioEngine.startRecording(c, rawWavFile,
                amp -> onMain(() -> waveform.addAmplitude(amp, 320)),
                true,
                message -> onMain(() -> {
                    isRecording = false;
                    isPlaying = false;
                    isPlaybackPaused = false;
                    hasRecordedFile = false;
                    durationSec = 0f;
                    waveform.clearAmplitudes();
                    RecordingService.returnToIdleNotification(appContext);
                    updateUi();
                    Toast.makeText(appContext, message, Toast.LENGTH_LONG).show();
                }));
        AppAudioRuntime.bumpSessionRevision();
    }

    private void cancelCurrentRecording() {
        if (isProcessing) return;
        isProcessing = true;
        updateUi();
        final Context appContext = getContext().getApplicationContext();
        runIo(() -> {
            audioEngine.stopRecording();
            audioEngine.stopPreview();
            //noinspection ResultOfMethodCallIgnored
            rawWavFile.delete();
            //noinspection ResultOfMethodCallIgnored
            new File(appContext.getCacheDir(), "temp_pcm.pcm").delete();
            onMain(() -> {
                isRecording = false;
                isPlaying = false;
                isPlaybackPaused = false;
                resetTakeState();
                RecordingService.returnToIdleNotification(appContext);
                AppAudioRuntime.bumpSessionRevision();
                isProcessing = false;
                updateUi();
            });
        });
    }

    // =============================================================== initial state / file loading

    /** Equivalent of LaunchedEffect(initialFile). Pass null for normal (re)entry. */
    public void loadInitialState(File initialFile, Runnable onInitialFileConsumed) {
        initialStateReady = false;
        if (initialFile == null && hasRecordedFile && !isRecording) {
            markReady();
            return;
        }
        final Context appContext = getContext().getApplicationContext();
        if (initialFile != null && initialFile.exists()) {
            audioEngine.stopPreview();
            isPlaying = false;
            isPlaybackPaused = false;
            isProcessing = true;
            updateUi();
            runIo(() -> {
                boolean converted = audioEngine.convertAnyAudioToStandardWav(initialFile, rawWavFile);
                onMain(() -> {
                    if (converted && rawWavFile.exists() && rawWavFile.length() > 44L) {
                        durationSec = (rawWavFile.length() - 44L) / (44100f * 2f);
                        selStart = 0f;
                        selEnd = 1f;
                        playheadRatio = 0f;
                        audioEngine.seekToGlobalRatio(0f);
                        loadWaveformAsync(() -> {
                            hasRecordedFile = true;
                            RecordingService.showReviewNotification(appContext, rawWavFile, (long) (durationSec * 1000f));
                            isProcessing = false;
                            updateUi();
                            if (onInitialFileConsumed != null) onInitialFileConsumed.run();
                            markReady();
                        });
                    } else {
                        Toast.makeText(appContext, "오디오 파일을 불러오지 못했습니다.", Toast.LENGTH_SHORT).show();
                        isProcessing = false;
                        updateUi();
                        if (onInitialFileConsumed != null) onInitialFileConsumed.run();
                        markReady();
                    }
                });
            });
        } else if (audioEngine.isRecordingActive()) {
            // The recorder can remain active while the app is in the background.
            isRecording = true;
            hasRecordedFile = false;
            updateUi();
            markReady();
        } else if (rawWavFile.exists() && rawWavFile.length() > 44L) {
            // Restore an unsaved take when reopening the app.
            durationSec = (rawWavFile.length() - 44L) / (44100f * 2f);
            selStart = 0f;
            selEnd = 1f;
            playheadRatio = audioEngine.isPreviewActive() ? audioEngine.getCurrentPreviewGlobalRatio() : 0f;
            loadWaveformAsync(() -> {
                hasRecordedFile = true;
                RecordingService.showReviewNotification(appContext, rawWavFile, (long) (durationSec * 1000f));
                updateUi();
                markReady();
            });
        } else {
            audioEngine.stopPreview();
            isPlaying = false;
            isPlaybackPaused = false;
            resetTakeState();
            updateUi();
            markReady();
        }
    }

    private void markReady() {
        initialStateReady = true;
        drainCommands();
    }

    /** Equivalent of LaunchedEffect(sessionRevision). */
    @Override
    public void onRevisionChanged(int revision) {
        final Context appContext = getContext().getApplicationContext();
        if (audioEngine.isRecordingActive()) {
            isRecording = true;
            hasRecordedFile = false;
            isPlaying = false;
            isPlaybackPaused = false;
            updateUi();
        } else if (isRecording) {
            isRecording = false;
            isPlaying = false;
            isPlaybackPaused = false;
            long length = rawWavFile.length();
            hasRecordedFile = rawWavFile.exists() && length > 44L;
            durationSec = hasRecordedFile ? (length - 44L) / (44100f * 2f) : 0f;
            selStart = 0f;
            selEnd = 1f;
            playheadRatio = 0f;
            audioEngine.seekToGlobalRatio(0f);
            waveform.clearAmplitudes();
            updateUi();
            if (hasRecordedFile) {
                loadWaveformAsync(null);
                RecordingService.showReviewNotification(appContext, rawWavFile, (long) (durationSec * 1000f));
            }
        } else if (!rawWavFile.exists() || rawWavFile.length() <= 44L) {
            resetTakeState();
            updateUi();
        }
    }

    // =============================================================== notification commands

    private void drainCommands() {
        if (!initialStateReady) return;
        while (!pendingCommands.isEmpty()) {
            handleCommand(pendingCommands.poll());
        }
    }

    private void handleCommand(RecordingNotificationCommandBus.Event event) {
        // The notification may open the app after its screen state was recreated.
        if (!audioEngine.isRecordingActive() && !hasRecordedFile && rawWavFile.exists() && rawWavFile.length() > 44L) {
            durationSec = (rawWavFile.length() - 44L) / (44100f * 2f);
            selStart = 0f;
            selEnd = 1f;
            hasRecordedFile = true;
            loadWaveformAsync(null);
            updateUi();
        }

        RecordingNotificationCommand command = event.command;
        switch (command.type) {
            case START_RECORDING:
                if (!isRecording && !audioEngine.isRecordingActive()) startNewRecording();
                break;
            case STOP_RECORDING:
                if (audioEngine.isRecordingActive() || isRecording) stopRecordingAndPrepareReview();
                break;
            case CANCEL_RECORDING:
                if (audioEngine.isRecordingActive() || isRecording) cancelCurrentRecording();
                break;
            case RESET_PLAYBACK:
                cancelCurrentRecording();
                break;
            case PLAY_PLAYBACK:
                if (isPlaybackPaused) togglePlayback();
                else if (!isPlaying) startPlaybackAtCursor();
                break;
            case PAUSE_PLAYBACK:
                if (isPlaying) togglePlayback();
                break;
            case TOGGLE_PLAYBACK:
                togglePlayback();
                break;
            case SAVE_RECORDING:
                saveRecording();
                break;
            case SHARE_RECORDING:
                shareRecording();
                break;
            case SEEK_TO: {
                float effective = effectiveDurationSec();
                if ((hasUsableTake() || effective > 0f) && effective > 0f) {
                    float target = Ui.clamp(command.positionMs / (effective * 1000f), 0f, 1f);
                    playheadRatio = target;
                    audioEngine.seekToGlobalRatio(target);
                    RecordingService.updatePlaybackUiState(isPlaying,
                            (long) (target * effective * 1000f), (long) (effective * 1000f));
                    updatePlayhead();
                }
                break;
            }
        }
    }

    // =============================================================== lifecycle

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        AppAudioRuntime.addSessionRevisionListener(this);
        main.removeCallbacks(pollTick);
        main.post(pollTick);
    }

    @Override
    protected void onDetachedFromWindow() {
        attached = false;
        AppAudioRuntime.removeSessionRevisionListener(this);
        main.removeCallbacks(pollTick);
        super.onDetachedFromWindow();
    }

    public boolean isAttachedNow() {
        return attached;
    }
}
