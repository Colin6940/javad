package com.mistirain.SongRecorder;

import android.content.Context;
import android.graphics.Color;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;

import java.util.Arrays;
import java.util.Locale;

public class PlateReverbEditor extends EffectEditorView {

    private static final float[] LOW_CUT_VALUES = {0f, 90f, 180f};
    private static final int[] REV_NODE_COLORS = {
            Color.parseColor("#EF4444"), Color.parseColor("#10B981"), Color.parseColor("#3B82F6")
    };

    private final MaterialSwitch enabledSwitch;
    private final ChipGroup prePostGroup;
    private final TextView mixLabel, lowCutLabel, timeLabel, preDelayLabel;
    private final Slider mixSlider, lowCutSlider, timeSlider, preDelaySlider;
    private final MaterialButton revEqButton;
    private final TextView revBandLabel;
    private final EqGraphView graph;
    private final TextView qLabel;
    private final Slider qSlider;
    private final BandButtonRow bandRow;
    private final MaterialButton bandOnOffButton;
    private int selectedRevBand = 0;
    private boolean refreshing = false;

    public PlateReverbEditor(Context c, DspProcessor dsp) {
        super(c, dsp);

        // ---- header: switch + help + Pre/Post
        LinearLayout header = Ui.horizontal(c);
        enabledSwitch = Ui.toggle(c, dsp.reverbEnabled, v -> { if (!refreshing) dsp.reverbEnabled = v; });
        LinearLayout left = Ui.switchHeader(c, enabledSwitch, "Reverb ON/OFF", "리버브 도움말",
                "리버브는 소리가 공간에서 반사된 뒤 남는 잔향을 더하는 효과입니다. Reverb Mix는 원래 소리에 더해지는 잔향 신호의 양(비율)입니다. Low Cut은 설정한 주파수 아래의 저음을 줄여 저역이 탁하거나 무겁게 쌓이는 것을 완화합니다. Reverb Time은 잔향이 사라지는 데 걸리는 시간이고, Pre-delay는 직접음 이후 잔향이 시작되기까지의 지연 시간입니다. Pre/Post는 잔향을 컴프레서 전 신호에서 가져올지, 컴프레서 후 신호에서 가져올지 선택합니다.");
        header.addView(left, Ui.weight(1f));
        prePostGroup = Ui.chipGroup(c, Arrays.asList("Pre", "Post"), dsp.reverbPreComp ? 0 : 1, idx -> {
            if (!refreshing) dsp.reverbPreComp = idx == 0;
        });
        header.addView(prePostGroup, Ui.wrap());
        Ui.addSpaced(content, header, 10);

        // ---- mix
        mixLabel = Ui.text(c, "", 12);
        mixSlider = Ui.slider(c, 0f, 1f, dsp.reverbVolume, v -> { dsp.reverbVolume = v; updateLabels(); });
        Ui.addSpaced(content, mixLabel, 10);
        Ui.addSpaced(content, mixSlider, 0);

        // ---- low cut (3 steps)
        lowCutLabel = Ui.text(c, "", 12);
        lowCutSlider = Ui.slider(c, 0f, 2f, lowCutStep(dsp.reverbLowCutHz, 1), v -> {
            int idx = Math.max(0, Math.min(2, Math.round(v)));
            dsp.reverbLowCutHz = LOW_CUT_VALUES[idx];
            updateLabels();
        });
        lowCutSlider.setStepSize(1f);
        Ui.addSpaced(content, lowCutLabel, 10);
        Ui.addSpaced(content, lowCutSlider, 0);
        Ui.addSpaced(content, scaleRow(c, "0Hz", "90Hz", "180Hz"), 0);

        // ---- reverb time (piecewise scale)
        timeLabel = Ui.text(c, "", 12);
        timeSlider = Ui.slider(c, 0f, 1f, timeMsToSliderFraction(dsp.reverbTimeMs), v -> {
            dsp.reverbTimeMs = sliderFractionToTimeMs(v);
            updateLabels();
        });
        Ui.addSpaced(content, timeLabel, 10);
        Ui.addSpaced(content, timeSlider, 0);
        Ui.addSpaced(content, scaleRow(c, "0ms", "1000ms", "3000ms"), 0);

        // ---- pre-delay
        preDelayLabel = Ui.text(c, "", 12);
        preDelaySlider = Ui.slider(c, 0f, 100f, dsp.reverbPreDelayMs, v -> { dsp.reverbPreDelayMs = v; updateLabels(); });
        Ui.addSpaced(content, preDelayLabel, 10);
        Ui.addSpaced(content, preDelaySlider, 0);

        Ui.addSpaced(content, Ui.resetButton(c, v -> {
            DspDefaults.resetReverbOnlyToDefault(dsp, EffectPresetStore.loadDefaultPreset(c));
            refresh();
        }), 10);

        Ui.addSpaced(content, Ui.divider(c, 1, Color.GRAY), 10);

        // ---- reverb EQ header
        LinearLayout eqHeader = Ui.horizontal(c);
        revEqButton = Ui.button(c, "EQ : OFF", v -> {
            dsp.reverbEqEnabled = !dsp.reverbEqEnabled;
            updateEq();
        });
        revEqButton.setTextSize(11);
        revEqButton.setMinHeight(Ui.dp(c, 30));
        revEqButton.setMinimumHeight(Ui.dp(c, 30));
        eqHeader.addView(revEqButton, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, Ui.dp(c, 30)));
        eqHeader.addView(Ui.helpButton(c, "리버브 EQ 도움말",
                "이 EQ는 원래 입력 소리 전체가 아니라 리버브 잔향의 음색을 조절합니다. 그래프의 밴드 점으로 주파수와 Gain(dB)을 바꿉니다. Peak는 중심 주파수 주변을 올리거나 줄이고, Low Shelf는 설정 주파수 아래의 저역, High Shelf는 설정 주파수 위의 고역을 조절합니다. 밴드별로 켜고 끌 수 있으며, 이 버튼은 리버브 전용 EQ 전체를 켜거나 끕니다."));
        View filler = new View(c);
        eqHeader.addView(filler, Ui.weight(1f));
        revBandLabel = Ui.text(c, "", 11);
        eqHeader.addView(revBandLabel, Ui.wrap());
        Ui.addSpaced(content, eqHeader, 10);

        // ---- graph
        graph = new EqGraphView(c, EqGraphView.Mode.NEAREST_SELECTED, REV_NODE_COLORS, 250, 4f);
        graph.setCallbacks(new EqGraphView.Callbacks() {
            @Override public EqBandConfig band(int index) { return dsp.reverbEqBands[index]; }
            @Override public int bandCount() { return 3; }
            @Override public float responseDb(float freq) { return dsp.getReverbEqMagnitudeResponseDb(freq); }
            @Override public int selectedBand() { return selectedRevBand; }
            @Override public void onSelectBand(int index) { selectedRevBand = index; updateEq(); }
            @Override public void onDragBand(int index, float freq, float gainDb) {
                dsp.reverbEqEnabled = true;
                dsp.updateReverbBand(index, dsp.reverbEqBands[index].withEnabled(true)
                        .withFreq(freq).withGainDb(gainDb).withType(FilterType.PEAK));
                updateEq();
            }
            @Override public boolean curveActive() { return dsp.reverbEqEnabled; }
        });
        graph.setLayoutParams(Ui.fixedHeight(c, 160));
        Ui.addSpaced(content, graph, 10);

        // ---- Q
        LinearLayout qRow = Ui.horizontal(c);
        qLabel = Ui.text(c, "", 11);
        qRow.addView(qLabel, new LinearLayout.LayoutParams(Ui.dp(c, 96), LinearLayout.LayoutParams.WRAP_CONTENT));
        qSlider = Ui.slider(c, 0f, 1.5f, dsp.reverbEqBands[0].q, v -> {
            dsp.updateReverbBand(selectedRevBand, dsp.reverbEqBands[selectedRevBand].withQ(v));
            updateEq();
        });
        LinearLayout.LayoutParams sliderLp = Ui.weight(1f);
        sliderLp.leftMargin = Ui.dp(c, 8);
        qRow.addView(qSlider, sliderLp);
        Ui.addSpaced(content, qRow, 10);

        // ---- band tiles + ON/OFF
        LinearLayout bandsLine = Ui.horizontal(c);
        bandRow = new BandButtonRow(c, 3, REV_NODE_COLORS, 6, i -> {
            if (selectedRevBand == i) {
                toggleBand(i);
            } else {
                selectedRevBand = i;
                updateEq();
            }
        });
        bandsLine.addView(bandRow, Ui.weight(1f));
        bandOnOffButton = Ui.button(c, "OFF", v -> toggleBand(selectedRevBand));
        bandOnOffButton.setTextSize(11);
        LinearLayout.LayoutParams onOffLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, Ui.dp(c, 34));
        onOffLp.leftMargin = Ui.dp(c, 6);
        bandsLine.addView(bandOnOffButton, onOffLp);
        Ui.addSpaced(content, bandsLine, 10);

        Ui.addSpaced(content, Ui.resetButton(c, v -> {
            DspDefaults.resetReverbEqOnlyToDefault(dsp, EffectPresetStore.loadDefaultPreset(c));
            selectedRevBand = 0;
            updateEq();
        }), 10);

        updateLabels();
        updateEq();
    }

    private void toggleBand(int i) {
        EqBandConfig band = dsp.reverbEqBands[i];
        boolean next = !band.enabled;
        if (next) dsp.reverbEqEnabled = true;
        dsp.updateReverbBand(i, band.withEnabled(next));
        updateEq();
    }

    private static LinearLayout scaleRow(Context c, String a, String b, String d) {
        LinearLayout row = Ui.horizontal(c);
        TextView ta = Ui.muted(c, a, 10);
        TextView tb = Ui.muted(c, b, 10);
        tb.setGravity(android.view.Gravity.CENTER);
        TextView td = Ui.muted(c, d, 10);
        td.setGravity(android.view.Gravity.END);
        row.addView(ta, Ui.weight(1f));
        row.addView(tb, Ui.weight(1f));
        row.addView(td, Ui.weight(1f));
        return row;
    }

    private static float lowCutStep(float hz, int minimum) {
        for (int i = 0; i < LOW_CUT_VALUES.length; i++) {
            if (Math.abs(LOW_CUT_VALUES[i] - hz) < 5f) return Math.max(i, minimum);
        }
        return minimum;
    }

    private static float timeMsToSliderFraction(float ms) {
        float c = Ui.clamp(ms, 0f, 3000f);
        return c <= 1000f ? (c / 1000f) * 0.5f : 0.5f + ((c - 1000f) / 2000f) * 0.5f;
    }

    private static float sliderFractionToTimeMs(float fraction) {
        float f = Ui.clamp(fraction, 0f, 1f);
        return f <= 0.5f ? (f / 0.5f) * 1000f : 1000f + ((f - 0.5f) / 0.5f) * 2000f;
    }

    private void updateLabels() {
        mixLabel.setText(String.format(Locale.US, "Reverb Mix (볼륨): %d%%", (int) (dsp.reverbVolume * 100)));
        int step = Math.max(0, Math.min(2, Math.round(lowCutSlider.getValue())));
        lowCutLabel.setText("Low Cut (먹먹함 제거): " + (int) LOW_CUT_VALUES[step] + "Hz");
        timeLabel.setText(String.format(Locale.US, "Reverb Time: %d ms", Math.round(Ui.clamp(dsp.reverbTimeMs, 0f, 3000f))));
        preDelayLabel.setText(String.format(Locale.US, "Pre-delay: %d ms", (int) dsp.reverbPreDelayMs));
    }

    private void updateEq() {
        boolean on = dsp.reverbEqEnabled;
        revEqButton.setText(on ? "EQ : ON" : "EQ : OFF");
        Ui.tintButton(revEqButton, on ? Color.parseColor("#2563EB") : Color.DKGRAY, Color.WHITE);
        EqBandConfig band = dsp.reverbEqBands[selectedRevBand];
        revBandLabel.setText(String.format(Locale.US, "B%d(%s): %dHz / %+.1fdB / Q:%.2f",
                selectedRevBand + 1, band.enabled ? "ON" : "OFF", (int) band.freq, band.gainDb, band.q));
        revBandLabel.setTextColor(band.enabled ? REV_NODE_COLORS[selectedRevBand] : Color.GRAY);
        qLabel.setText(String.format(Locale.US, "Q (0~1.5): %.2f", band.q));
        Ui.setSlider(qSlider, band.q);
        bandRow.update(dsp.reverbEqBands, selectedRevBand);
        bandOnOffButton.setText(band.enabled ? "ON" : "OFF");
        graph.invalidate();
    }

    @Override
    protected void refresh() {
        refreshing = true;
        enabledSwitch.setChecked(dsp.reverbEnabled);
        Ui.selectChip(prePostGroup, dsp.reverbPreComp ? 0 : 1);
        Ui.setSlider(mixSlider, dsp.reverbVolume);
        Ui.setSlider(lowCutSlider, lowCutStep(dsp.reverbLowCutHz, 0));
        Ui.setSlider(timeSlider, timeMsToSliderFraction(dsp.reverbTimeMs));
        Ui.setSlider(preDelaySlider, dsp.reverbPreDelayMs);
        updateLabels();
        updateEq();
        refreshing = false;
    }
}
