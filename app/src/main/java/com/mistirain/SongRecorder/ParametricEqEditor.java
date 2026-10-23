package com.mistirain.SongRecorder;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.chip.ChipGroup;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ParametricEqEditor extends EffectEditorView {

    private static final int[] NODE_COLORS = {
            Color.parseColor("#EF4444"), Color.parseColor("#F59E0B"), Color.parseColor("#10B981"),
            Color.parseColor("#3B82F6"), Color.parseColor("#A855F7")
    };

    private final MaterialSwitch enabledSwitch;
    private final TextView bandLabel;
    private final ChipGroup typeGroup;
    private final EqGraphView graph;
    private final TextView qLabel;
    private final Slider qSlider;
    private final BandButtonRow bandRow;
    private int selectedBand = 0;
    private boolean refreshing = false;

    public ParametricEqEditor(Context c, DspProcessor dsp) {
        super(c, dsp);
        enabledSwitch = Ui.toggle(c, dsp.eqEnabled, v -> {
            if (!refreshing) {
                dsp.eqEnabled = v;
                updateAll();
            }
        });
        Ui.addSpaced(content, Ui.switchHeader(c, enabledSwitch, "EQ ON/OFF", "EQ 도움말",
                "EQ는 주파수별 소리의 크기를 올리거나 줄이는 기능입니다. 그래프의 밴드 점을 움직여 중심 주파수와 Gain(dB)을 조절하고, Q 값으로 영향을 받는 주파수 범위를 조절할 수 있습니다. Peak는 기준 주파수 주변을 대칭적으로 조절하고, Low Shelf는 기준 주파수 아래의 저역을, High Shelf는 기준 주파수 위의 고역을 조절합니다. 밴드 버튼을 눌러 선택하고 다시 눌러 해당 밴드를 켜거나 끌 수 있습니다. 위의 EQ ON/OFF 스위치는 전체 EQ를 켜거나 끕니다."), 8);

        bandLabel = Ui.text(c, "", 12, true);
        Ui.addSpaced(content, bandLabel, 8);

        List<String> typeLabels = new ArrayList<>();
        for (FilterType t : FilterType.values()) typeLabels.add(t.label);
        typeGroup = Ui.chipGroup(c, typeLabels, dsp.eqBands[0].type.ordinal(), idx -> {
            if (refreshing) return;
            FilterType type = FilterType.values()[idx];
            EqBandConfig band = dsp.eqBands[selectedBand];
            if (band.type == type && band.enabled) return;
            dsp.updateBand(selectedBand, band.withType(type).withEnabled(true));
            updateAll();
        });
        Ui.addSpaced(content, typeGroup, 8);

        graph = new EqGraphView(c, EqGraphView.Mode.NODE_HIT, NODE_COLORS, 300, 4.5f);
        graph.setCallbacks(new EqGraphView.Callbacks() {
            @Override public EqBandConfig band(int index) { return dsp.eqBands[index]; }
            @Override public int bandCount() { return 5; }
            @Override public float responseDb(float freq) { return dsp.getMagnitudeResponseDb(freq); }
            @Override public int selectedBand() { return selectedBand; }
            @Override public void onSelectBand(int index) { selectedBand = index; updateAll(); }
            @Override public void onDragBand(int index, float freq, float gainDb) {
                dsp.updateBand(index, dsp.eqBands[index].withEnabled(true).withFreq(freq).withGainDb(gainDb));
                updateAll();
            }
            @Override public boolean curveActive() { return true; }
        });
        graph.setLayoutParams(Ui.fixedHeight(c, 160));
        Ui.addSpaced(content, graph, 8);

        LinearLayout qRow = Ui.horizontal(c);
        qLabel = Ui.text(c, "", 11);
        qRow.addView(qLabel, new LinearLayout.LayoutParams(Ui.dp(c, 96), LinearLayout.LayoutParams.WRAP_CONTENT));
        qSlider = Ui.slider(c, 0f, 8f, dsp.eqBands[0].q, v -> {
            dsp.updateBand(selectedBand, dsp.eqBands[selectedBand].withQ(v));
            updateAll();
        });
        LinearLayout.LayoutParams sliderLp = Ui.weight(1f);
        sliderLp.leftMargin = Ui.dp(c, 8);
        qRow.addView(qSlider, sliderLp);
        Ui.addSpaced(content, qRow, 8);

        bandRow = new BandButtonRow(c, 5, NODE_COLORS, 4, i -> {
            if (selectedBand == i) {
                EqBandConfig band = dsp.eqBands[i];
                dsp.updateBand(i, band.withEnabled(!band.enabled));
            } else {
                selectedBand = i;
            }
            updateAll();
        });
        Ui.addSpaced(content, bandRow, 8);

        Ui.addSpaced(content, Ui.resetButton(c, v -> {
            DspDefaults.resetEqToDefault(dsp, EffectPresetStore.loadDefaultPreset(c));
            selectedBand = 0;
            refresh();
        }), 8);
        updateAll();
    }

    private void updateAll() {
        refreshing = true;
        EqBandConfig band = dsp.eqBands[selectedBand];
        bandLabel.setText(String.format(Locale.US, "B%d(%s): %dHz / %+.1fdB / Q:%.2f",
                selectedBand + 1, band.enabled ? "ON" : "OFF", (int) band.freq, band.gainDb, band.q));
        bandLabel.setTextColor(band.enabled ? NODE_COLORS[selectedBand] : Color.GRAY);
        bandLabel.setTypeface(Typeface.DEFAULT_BOLD);
        Ui.selectChip(typeGroup, band.type.ordinal());
        qLabel.setText(String.format(Locale.US, "Q (0~8): %.2f", band.q));
        Ui.setSlider(qSlider, band.q);
        bandRow.update(dsp.eqBands, selectedBand);
        graph.invalidate();
        refreshing = false;
    }

    @Override
    protected void refresh() {
        refreshing = true;
        enabledSwitch.setChecked(dsp.eqEnabled);
        refreshing = false;
        updateAll();
    }
}
