package com.mistirain.SongRecorder;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.chip.ChipGroup;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;

import java.util.Arrays;
import java.util.Locale;

public class CompressorEditor extends EffectEditorView {

    private static final float[] RATIO_VALUES = {2.0f, 2.5f, 3.0f, 4.0f};

    private final MaterialSwitch enabledSwitch;
    private final ChipGroup ratioGroup;
    private final TextView thresholdLabel, levelLabel, makeupLabel;
    private final Slider thresholdSlider, makeupSlider;
    private LevelMeter meter;
    private boolean refreshing = false;
    private float currentLevelDb = -60f;

    private final Runnable meterTick = new Runnable() {
        @Override
        public void run() {
            currentLevelDb = dsp.compInputLevelDb;
            levelLabel.setText(String.format(Locale.US, "입력 레벨: %.1f dB", currentLevelDb));
            meter.invalidate();
            postDelayed(this, 30L);
        }
    };

    public CompressorEditor(Context c, DspProcessor dsp) {
        super(c, dsp);
        enabledSwitch = Ui.toggle(c, dsp.compEnabled, v -> {
            if (!refreshing) dsp.compEnabled = v;
            if (meter != null) meter.invalidate();
        });
        Ui.addSpaced(content, Ui.switchHeader(c, enabledSwitch, "Comp ON/OFF", "컴프레서 도움말",
                "컴프레서는 큰 소리의 증가량을 줄여 전체 음량 차이를 완만하게 만드는 효과입니다. Threshold는 압축이 시작되는 기준 레벨이고, Ratio는 기준을 넘은 소리를 얼마나 강하게 압축할지 정합니다(예: 2:1보다 4:1이 더 강한 압축). Makeup Gain은 압축 후 출력 음량을 올리는 양입니다. 컴프레서가 작은 소리만 따로 키우는 것은 아니지만, 압축 후 Makeup Gain을 더하면 작은 소리도 상대적으로 잘 들릴 수 있습니다. 이 앱은 어택 25ms, 릴리즈 250ms, Soft Knee 6dB로 비교적 부드러운 반응을 목표로 합니다."), 10);

        Ui.addSpaced(content, Ui.muted(c, "부드러운 반응: 어택 25 ms · 릴리즈 250 ms · Soft Knee 6 dB", 11), 10);

        Ui.addSpaced(content, Ui.text(c, "Ratio", 12, true), 10);
        ratioGroup = Ui.chipGroup(c, Arrays.asList("2:1", "2.5:1", "3:1", "4:1"), ratioIndex(dsp.compRatio), idx -> {
            if (!refreshing) dsp.compRatio = RATIO_VALUES[idx];
        });
        Ui.addSpaced(content, ratioGroup, 4);

        LinearLayout thresholdRow = Ui.horizontal(c);
        thresholdLabel = Ui.text(c, "", 13, true);
        levelLabel = Ui.muted(c, "입력 레벨: -60.0 dB", 12);
        thresholdRow.addView(thresholdLabel, Ui.weight(1f));
        thresholdRow.addView(levelLabel, Ui.wrap());
        Ui.addSpaced(content, thresholdRow, 10);

        meter = new LevelMeter(c);
        meter.setLayoutParams(Ui.fixedHeight(c, 20));
        Ui.addSpaced(content, meter, 4);

        thresholdSlider = Ui.slider(c, -60f, 0f, dsp.compThresholdDb, v -> {
            dsp.compThresholdDb = v;
            updateLabels();
            meter.invalidate();
        });
        Ui.addSpaced(content, thresholdSlider, 4);

        makeupLabel = Ui.text(c, "", 13, true);
        Ui.addSpaced(content, makeupLabel, 10);
        makeupSlider = Ui.slider(c, 0f, 24f, dsp.compMakeupDb, v -> {
            dsp.compMakeupDb = v;
            updateLabels();
        });
        Ui.addSpaced(content, makeupSlider, 2);

        Ui.addSpaced(content, Ui.resetButton(c, v -> {
            DspDefaults.resetCompressorToDefault(dsp, EffectPresetStore.loadDefaultPreset(c));
            refresh();
        }), 10);
        updateLabels();
    }

    private static int ratioIndex(float ratio) {
        for (int i = 0; i < RATIO_VALUES.length; i++) {
            if (Math.abs(ratio - RATIO_VALUES[i]) < 0.05f) return i;
        }
        return -1;
    }

    private void updateLabels() {
        thresholdLabel.setText(String.format(Locale.US, "Threshold: %.1f dB", dsp.compThresholdDb));
        makeupLabel.setText(String.format(Locale.US, "Makeup Gain (음량 보상): +%.1f dB", dsp.compMakeupDb));
    }

    @Override
    protected void refresh() {
        refreshing = true;
        enabledSwitch.setChecked(dsp.compEnabled);
        int idx = ratioIndex(dsp.compRatio);
        if (idx >= 0) Ui.selectChip(ratioGroup, idx);
        Ui.setSlider(thresholdSlider, dsp.compThresholdDb);
        Ui.setSlider(makeupSlider, dsp.compMakeupDb);
        updateLabels();
        meter.invalidate();
        refreshing = false;
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        post(meterTick);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(meterTick);
        super.onDetachedFromWindow();
    }

    private final class LevelMeter extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        LevelMeter(Context c) {
            super(c);
            setBackground(Ui.rounded(Color.parseColor("#1E1E1E"), Ui.dp(c, 4)));
            setClipToOutline(true);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            float levelNorm = Ui.clamp((currentLevelDb + 60f) / 60f, 0f, 1f);
            float threshNorm = Ui.clamp((dsp.compThresholdDb + 60f) / 60f, 0f, 1f);
            boolean compressing = dsp.compEnabled && currentLevelDb > dsp.compThresholdDb;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(compressing ? Color.parseColor("#F59E0B") : Color.parseColor("#10B981"));
            canvas.drawRect(0, 0, w * levelNorm, h, paint);
            paint.setColor(Color.RED);
            paint.setStrokeWidth(5f);
            float tx = w * threshNorm;
            canvas.drawLine(tx, 0, tx, h, paint);
        }
    }
}
