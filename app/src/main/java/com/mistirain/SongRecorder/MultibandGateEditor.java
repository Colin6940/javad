package com.mistirain.SongRecorder;

import android.content.Context;
import android.widget.TextView;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;

import java.util.Locale;

public class MultibandGateEditor extends EffectEditorView {

    private final MaterialSwitch enabledSwitch;
    private final TextView lowLabel, midLabel, highLabel;
    private final Slider lowSlider, midSlider, highSlider;
    private boolean refreshing = false;

    public MultibandGateEditor(Context c, DspProcessor dsp) {
        super(c, dsp);
        enabledSwitch = Ui.toggle(c, dsp.gateEnabled, v -> { if (!refreshing) dsp.gateEnabled = v; });
        Ui.addSpaced(content, Ui.switchHeader(c, enabledSwitch, "Noise Gate ON/OFF", "노이즈 게이트 도움말",
                "노이즈 게이트는 입력 소리를 저음(250Hz 미만), 중음(250Hz~2.5kHz), 고음(2.5kHz 초과)의 세 대역으로 나누어 각각 처리합니다. 각 대역의 소리 레벨이 설정한 Threshold(dB)보다 낮으면 해당 대역의 음량을 크게 줄여 배경 잡음이나 험을 줄입니다. 다만 Threshold를 너무 높게 설정하면 작은 목소리나 단어 끝부분도 함께 줄어들 수 있습니다."), 6);

        lowLabel = Ui.text(c, "", 12);
        lowSlider = Ui.slider(c, -80f, 0f, dsp.gateLowThreshDb, v -> { dsp.gateLowThreshDb = v; updateLabels(); });
        midLabel = Ui.text(c, "", 12);
        midSlider = Ui.slider(c, -80f, 0f, dsp.gateMidThreshDb, v -> { dsp.gateMidThreshDb = v; updateLabels(); });
        highLabel = Ui.text(c, "", 12);
        highSlider = Ui.slider(c, -80f, 0f, dsp.gateHighThreshDb, v -> { dsp.gateHighThreshDb = v; updateLabels(); });
        Ui.addSpaced(content, lowLabel, 6);
        Ui.addSpaced(content, lowSlider, 0);
        Ui.addSpaced(content, midLabel, 6);
        Ui.addSpaced(content, midSlider, 0);
        Ui.addSpaced(content, highLabel, 6);
        Ui.addSpaced(content, highSlider, 0);

        Ui.addSpaced(content, Ui.resetButton(c, v -> {
            DspDefaults.resetGateToDefault(dsp, EffectPresetStore.loadDefaultPreset(c));
            refresh();
        }), 10);
        updateLabels();
    }

    private void updateLabels() {
        lowLabel.setText(String.format(Locale.US, "Low Band (< 250Hz 저음 험/진동 차단): %.1f dB", dsp.gateLowThreshDb));
        midLabel.setText(String.format(Locale.US, "Mid Band (250Hz ~ 2.5kHz 중음 잡음 차단): %.1f dB", dsp.gateMidThreshDb));
        highLabel.setText(String.format(Locale.US, "High Band (> 2.5kHz 고음 히스 차단): %.1f dB", dsp.gateHighThreshDb));
    }

    @Override
    protected void refresh() {
        refreshing = true;
        enabledSwitch.setChecked(dsp.gateEnabled);
        Ui.setSlider(lowSlider, dsp.gateLowThreshDb);
        Ui.setSlider(midSlider, dsp.gateMidThreshDb);
        Ui.setSlider(highSlider, dsp.gateHighThreshDb);
        updateLabels();
        refreshing = false;
    }
}
