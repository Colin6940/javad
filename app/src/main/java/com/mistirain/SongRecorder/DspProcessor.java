package com.mistirain.SongRecorder;

import java.util.Arrays;

public class DspProcessor {

    private final float sampleRate;

    // ==================== 1. 3밴드 멀티밴드 노이즈 게이트 ====================
    public volatile boolean gateEnabled = false;
    public volatile float gateLowThreshDb = -45f;  // < 250Hz
    public volatile float gateMidThreshDb = -45f;  // 250Hz ~ 2500Hz
    public volatile float gateHighThreshDb = -45f; // > 2500Hz

    private double lp1State = 0.0;
    private double lp2State = 0.0;
    private final double[] gateEnv = new double[3];
    private final double[] gateGain = {1.0, 1.0, 1.0};

    // These coefficients depend only on the sample rate, so calculate them once,
    // rather than performing exp() for every audio sample.
    private final double gateLowPassAlpha1;
    private final double gateLowPassAlpha2;
    private final double gateAttackCoef;
    private final double gateReleaseCoef;
    private static final double GATE_GAIN_SMOOTHING = 0.005;

    // ==================== 2. 5밴드 파라메트릭 EQ ====================
    public volatile boolean eqEnabled = false;
    public final EqBandConfig[] eqBands = new EqBandConfig[5];

    private volatile double[][] bandCoeffs = identityCoeffs(5);
    private final double[] z1 = new double[5];
    private final double[] z2 = new double[5];

    // ==================== 3. 컴프레서 ====================
    public volatile boolean compEnabled = false;
    public volatile float compThresholdDb = -20f; // -60 ~ 0 dB
    public volatile float compRatio = 2.0f;       // 2.0, 2.5, 3.0, 4.0
    public volatile float compMakeupDb = 0f;      // 0 ~ 24 dB
    public volatile float compInputLevelDb = -60f; // 실시간 레벨미터 표시용 (UI 참조)
    private double compEnv = 0.0;
    // A slightly slower attack and longer release make gain changes less abrupt,
    // while a soft knee below smooths the transition into compression.
    private final double compAttackCoef;  // 25 ms
    private final double compReleaseCoef; // 250 ms
    private static final double COMP_SOFT_KNEE_DB = 6.0;

    // ==================== 4. 플레이트 리버브 & 전용 3밴드 EQ ====================
    public volatile boolean reverbEnabled = false;
    public volatile boolean reverbPreComp = false; // true: Pre(EQ 직후), false: Post(컴프레서 직후)
    public volatile float reverbVolume = 0.15f;    // Wet 볼륨 기본값 15% (0.0 ~ 1.0)
    public volatile float reverbLowCutHz = 90f;    // 0f, 90f, 180f
    public volatile float reverbTimeMs = 1500f;    // 300 ~ 5000 ms
    public volatile float reverbPreDelayMs = 12f;  // 0 ~ 100 ms

    public volatile boolean reverbEqEnabled = false;
    public final EqBandConfig[] reverbEqBands = new EqBandConfig[3];

    private volatile double[][] revBandCoeffs = identityCoeffs(3);
    private final double[] revZ1 = new double[3];
    private final double[] revZ2 = new double[3];

    private double reverbHpState = 0.0;
    private final int maxPreDelaySamples; // 최대 200ms 버퍼
    private final double[] preDelayBuf;
    private int preDelayWriteIdx = 0;

    // Reverb controls change only from the UI. Cache their derived values so the
    // real-time audio loop does not repeatedly call exp()/pow() or recalculate delays.
    private float cachedLowCutHz = Float.NaN;
    private double cachedLowCutAlpha = 0.0;
    private float cachedPreDelayMs = Float.NaN;
    private int cachedPreDelaySamples = 1;
    private float cachedReverbTimeMs = Float.NaN;
    private double cachedReverbFeedback = 0.0;

    // 초기 고밀도 확산을 위한 4단 직렬 올패스 필터
    private final int[] apDelays = {142, 107, 379, 277};
    private final double[] apGains = {0.70, 0.70, 0.50, 0.50};
    private final double[][] apBufs = new double[4][];
    private final int[] apIdxs = new int[4];

    // 플레이트 잔향 탱크를 구성하는 6단 병렬 콤 필터
    private final int[] combDelays = {1557, 1617, 1491, 1422, 1277, 1356};
    private final double[][] combBufs = new double[6][];
    private final int[] combIdxs = new int[6];
    private final double[] combFilterStates = new double[6];
    private static final double PLATE_DAMPING = 0.30;

    public DspProcessor() {
        this(44100f);
    }

    public DspProcessor(float sampleRate) {
        this.sampleRate = sampleRate;
        gateLowPassAlpha1 = 1.0 - Math.exp(-2.0 * Math.PI * 250.0 / sampleRate);
        gateLowPassAlpha2 = 1.0 - Math.exp(-2.0 * Math.PI * 2500.0 / sampleRate);
        gateAttackCoef = Math.exp(-1.0 / (0.005 * sampleRate));
        gateReleaseCoef = Math.exp(-1.0 / (0.080 * sampleRate));
        compAttackCoef = Math.exp(-1.0 / (0.025 * sampleRate));
        compReleaseCoef = Math.exp(-1.0 / (0.250 * sampleRate));

        float[] eqFreqs = {100f, 350f, 1000f, 3500f, 10000f};
        FilterType[] eqTypes = {
                FilterType.LOW_SHELF, FilterType.PEAK, FilterType.PEAK, FilterType.PEAK, FilterType.HIGH_SHELF
        };
        for (int i = 0; i < 5; i++) {
            eqBands[i] = new EqBandConfig(false, eqFreqs[i], 0f, 1.0f, eqTypes[i]);
        }
        float[] revFreqs = {250f, 1200f, 4500f};
        for (int i = 0; i < 3; i++) {
            // Q = 1.4 고정 Bell(Peak) 필터
            reverbEqBands[i] = new EqBandConfig(false, revFreqs[i], 0f, 1.4f, FilterType.PEAK);
        }

        maxPreDelaySamples = (int) (sampleRate * 0.2f);
        preDelayBuf = new double[maxPreDelaySamples];
        for (int i = 0; i < 4; i++) apBufs[i] = new double[apDelays[i]];
        for (int i = 0; i < 6; i++) combBufs[i] = new double[combDelays[i]];

        for (int i = 0; i < 5; i++) updateBandCoeffs(i);
        for (int i = 0; i < 3; i++) updateReverbBandCoeffs(i);
    }

    private static double[][] identityCoeffs(int count) {
        double[][] result = new double[count][];
        for (int i = 0; i < count; i++) result[i] = new double[]{1.0, 0.0, 0.0, 0.0, 0.0};
        return result;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    public void updateBand(int index, EqBandConfig config) {
        eqBands[index] = config;
        updateBandCoeffs(index);
    }

    public void updateReverbBand(int index, EqBandConfig config) {
        // 기존 q=1.4 강제 고정을 제거하여 슬라이더 값(0~1.5)이 정상 적용되도록 함
        reverbEqBands[index] = config.withType(FilterType.PEAK);
        updateReverbBandCoeffs(index);
    }

    private double[] computeBiquadCoeffs(EqBandConfig b) {
        if (!b.enabled) return new double[]{1.0, 0.0, 0.0, 0.0, 0.0};

        double f0 = clamp(b.freq, 20f, sampleRate * 0.45f);
        double dbGain = clamp(b.gainDb, -15f, 15f);
        double q = clamp(b.q, 0.1f, 10.0f);

        double a = Math.pow(10.0, dbGain / 40.0);
        double w0 = 2.0 * Math.PI * f0 / sampleRate;
        double cosW0 = Math.cos(w0);
        double sinW0 = Math.sin(w0);
        double alpha = sinW0 / (2.0 * q);

        double b0, b1, b2, a0, a1, a2;

        switch (b.type) {
            case LOW_SHELF: {
                double twoSqrtAAlpha = 2.0 * Math.sqrt(a) * alpha;
                b0 = a * ((a + 1.0) - (a - 1.0) * cosW0 + twoSqrtAAlpha);
                b1 = 2.0 * a * ((a - 1.0) - (a + 1.0) * cosW0);
                b2 = a * ((a + 1.0) - (a - 1.0) * cosW0 - twoSqrtAAlpha);
                a0 = (a + 1.0) + (a - 1.0) * cosW0 + twoSqrtAAlpha;
                a1 = -2.0 * ((a - 1.0) + (a + 1.0) * cosW0);
                a2 = (a + 1.0) + (a - 1.0) * cosW0 - twoSqrtAAlpha;
                break;
            }
            case HIGH_SHELF: {
                double twoSqrtAAlpha = 2.0 * Math.sqrt(a) * alpha;
                b0 = a * ((a + 1.0) + (a - 1.0) * cosW0 + twoSqrtAAlpha);
                b1 = -2.0 * a * ((a - 1.0) + (a + 1.0) * cosW0);
                b2 = a * ((a + 1.0) + (a - 1.0) * cosW0 - twoSqrtAAlpha);
                a0 = (a + 1.0) - (a - 1.0) * cosW0 + twoSqrtAAlpha;
                a1 = 2.0 * ((a - 1.0) - (a + 1.0) * cosW0);
                a2 = (a + 1.0) - (a - 1.0) * cosW0 - twoSqrtAAlpha;
                break;
            }
            case PEAK:
            default: {
                b0 = 1.0 + alpha * a;
                b1 = -2.0 * cosW0;
                b2 = 1.0 - alpha * a;
                a0 = 1.0 + alpha / a;
                a1 = -2.0 * cosW0;
                a2 = 1.0 - alpha / a;
                break;
            }
        }

        return new double[]{b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0};
    }

    private void updateBandCoeffs(int index) {
        double[][] copy = bandCoeffs.clone();
        copy[index] = computeBiquadCoeffs(eqBands[index]);
        bandCoeffs = copy;
    }

    private void updateReverbBandCoeffs(int index) {
        double[][] copy = revBandCoeffs.clone();
        copy[index] = computeBiquadCoeffs(reverbEqBands[index]);
        revBandCoeffs = copy;
    }

    public float getMagnitudeResponseDb(float freq) {
        if (!eqEnabled) return 0f;
        return computeResponseFromCoeffs(freq, bandCoeffs, eqBands);
    }

    public float getReverbEqMagnitudeResponseDb(float freq) {
        if (!reverbEqEnabled) return 0f;
        return computeResponseFromCoeffs(freq, revBandCoeffs, reverbEqBands);
    }

    private float computeResponseFromCoeffs(float freq, double[][] coeffsArray, EqBandConfig[] configs) {
        double w = 2.0 * Math.PI * freq / sampleRate;
        double cosW = Math.cos(w);
        double cos2W = Math.cos(2.0 * w);
        double sinW = Math.sin(w);
        double sin2W = Math.sin(2.0 * w);

        double totalDb = 0.0;
        for (int i = 0; i < coeffsArray.length; i++) {
            if (!configs[i].enabled) continue;
            double[] c = coeffsArray[i];
            double numRe = c[0] + c[1] * cosW + c[2] * cos2W;
            double numIm = -c[1] * sinW - c[2] * sin2W;
            double denRe = 1.0 + c[3] * cosW + c[4] * cos2W;
            double denIm = -c[3] * sinW - c[4] * sin2W;

            double magSq = (numRe * numRe + numIm * numIm) / Math.max(denRe * denRe + denIm * denIm, 1e-12);
            totalDb += 10.0 * Math.log10(Math.max(magSq, 1e-12));
        }
        return (float) totalDb;
    }

    public void resetState() {
        for (int i = 0; i < 5; i++) { z1[i] = 0.0; z2[i] = 0.0; }
        for (int i = 0; i < 3; i++) { revZ1[i] = 0.0; revZ2[i] = 0.0; }
        compEnv = 0.0;
        compInputLevelDb = -60f;
        lp1State = 0.0;
        lp2State = 0.0;
        for (int i = 0; i < 3; i++) { gateEnv[i] = 0.0; gateGain[i] = 1.0; }

        reverbHpState = 0.0;
        Arrays.fill(preDelayBuf, 0.0);
        preDelayWriteIdx = 0;
        for (int i = 0; i < 4; i++) {
            Arrays.fill(apBufs[i], 0.0);
            apIdxs[i] = 0;
        }
        for (int i = 0; i < 6; i++) {
            Arrays.fill(combBufs[i], 0.0);
            combIdxs[i] = 0;
            combFilterStates[i] = 0.0;
        }
    }

    private double processReverbTank(double reverbIn) {
        double rSignal = reverbIn;

        // 1. 로우컷 필터 (0Hz, 90Hz, 180Hz)
        float lowCut = reverbLowCutHz;
        if (lowCut > 1f) {
            if (lowCut != cachedLowCutHz) {
                cachedLowCutHz = lowCut;
                cachedLowCutAlpha = 1.0 - Math.exp(-2.0 * Math.PI * lowCut / sampleRate);
            }
            reverbHpState += cachedLowCutAlpha * (rSignal - reverbHpState);
            rSignal -= reverbHpState;
        }

        // 2. 프리딜레이 (0 ~ 150ms)
        float preDelayMs = reverbPreDelayMs;
        if (preDelayMs != cachedPreDelayMs) {
            cachedPreDelayMs = preDelayMs;
            cachedPreDelaySamples = clamp(
                    (int) ((clamp(preDelayMs, 0f, 150f) / 1000f) * sampleRate),
                    1, maxPreDelaySamples - 1);
        }
        int delaySamples = cachedPreDelaySamples;
        int readIdx = (preDelayWriteIdx - delaySamples + maxPreDelaySamples) % maxPreDelaySamples;
        double delayedInput = preDelayBuf[readIdx];
        preDelayBuf[preDelayWriteIdx] = rSignal * 0.5;
        preDelayWriteIdx = (preDelayWriteIdx + 1) % maxPreDelaySamples;

        // 3. 4단 직렬 올패스 확산기
        double diffused = delayedInput;
        for (int i = 0; i < 4; i++) {
            double[] buf = apBufs[i];
            int idx = apIdxs[i];
            double g = apGains[i];

            double bufOut = buf[idx];
            double vn = diffused - g * bufOut;
            buf[idx] = vn;
            diffused = bufOut + g * vn;

            apIdxs[i] = (idx + 1) % buf.length;
        }

        // 4. Reverb Time (ms) 기반 6단 병렬 콤 필터
        float reverbTime = reverbTimeMs;
        if (reverbTime != cachedReverbTimeMs) {
            cachedReverbTimeMs = reverbTime;
            double rtSec = clamp(reverbTime, 200f, 5000f) / 1000.0;
            double avgDelaySec = 1453.0 / sampleRate;
            cachedReverbFeedback = clamp(Math.pow(10.0, -3.0 * avgDelaySec / rtSec), 0.40, 0.95);
        }
        double feedback = cachedReverbFeedback;

        double combSum = 0.0;
        for (int i = 0; i < 6; i++) {
            double[] buf = combBufs[i];
            int idx = combIdxs[i];
            double delOut = buf[idx];

            combFilterStates[i] = delOut * (1.0 - PLATE_DAMPING) + combFilterStates[i] * PLATE_DAMPING;
            buf[idx] = diffused + combFilterStates[i] * feedback;
            combSum += delOut;

            combIdxs[i] = (idx + 1) % buf.length;
        }

        double wet = combSum * 0.18;

        // 5. 리버브 전용 3밴드 Bell EQ (Q = 1.4 고정)
        if (reverbEqEnabled) {
            double[][] currentRevCoeffs = revBandCoeffs;
            for (int i = 0; i < 3; i++) {
                if (!reverbEqBands[i].enabled) continue;
                double[] c = currentRevCoeffs[i];
                double y = c[0] * wet + revZ1[i];
                revZ1[i] = c[1] * wet - c[3] * y + revZ2[i];
                revZ2[i] = c[2] * wet - c[4] * y;
                wet = y;
            }
        }

        return wet * reverbVolume;
    }

    private double gateBand(int band, double signal, float threshold) {
        double abs = Math.abs(signal);
        if (abs > gateEnv[band]) {
            gateEnv[band] = gateAttackCoef * gateEnv[band] + (1.0 - gateAttackCoef) * abs;
        } else {
            gateEnv[band] = gateReleaseCoef * gateEnv[band] + (1.0 - gateReleaseCoef) * abs;
        }
        double envDb = 20.0 * Math.log10(Math.max(gateEnv[band], 1e-7));
        double targetGain = envDb < threshold ? 0.02 : 1.0;
        gateGain[band] += GATE_GAIN_SMOOTHING * (targetGain - gateGain[band]);
        return gateGain[band];
    }

    public float processSample(float input) {
        double x = input;

        // [단계 1] 3밴드 노이즈 게이트
        if (gateEnabled) {
            lp1State += gateLowPassAlpha1 * (x - lp1State);
            lp2State += gateLowPassAlpha2 * (x - lp2State);

            // Keep the bands as scalars to avoid per-sample allocation.
            double lowBand = lp1State;
            double midBand = lp2State - lp1State;
            double highBand = x - lp2State;
            // Snapshot thresholds once per sample.
            float lowThreshold = gateLowThreshDb;
            float midThreshold = gateMidThreshDb;
            float highThreshold = gateHighThreshDb;

            double lowGain = gateBand(0, lowBand, lowThreshold);
            double midGain = gateBand(1, midBand, midThreshold);
            double highGain = gateBand(2, highBand, highThreshold);

            x = lowBand * lowGain + midBand * midGain + highBand * highGain;
        }

        // [단계 2] 5밴드 파라메트릭 EQ
        if (eqEnabled) {
            double[][] currentCoeffs = bandCoeffs;
            for (int i = 0; i < 5; i++) {
                if (!eqBands[i].enabled) continue;
                double[] c = currentCoeffs[i];
                double y = c[0] * x + z1[i];
                z1[i] = c[1] * x - c[3] * y + z2[i];
                z2[i] = c[2] * x - c[4] * y;
                x = y;
            }
        }

        // Pre 모드 리버브 탭 신호 확보 (EQ까지 통과한 신호)
        double preCompSignal = x;

        // [단계 3] 컴프레서 (레벨미터는 활성화 여부와 무관하게 상시 측정)
        double absX = Math.abs(x);
        if (absX > compEnv) {
            compEnv = compAttackCoef * compEnv + (1.0 - compAttackCoef) * absX;
        } else {
            compEnv = compReleaseCoef * compEnv + (1.0 - compReleaseCoef) * absX;
        }
        double envDb = 20.0 * Math.log10(Math.max(compEnv, 1e-7));
        compInputLevelDb = clamp((float) envDb, -60f, 0f);

        if (compEnabled) {
            double thresholdDb = compThresholdDb;
            double overThresholdDb = envDb - thresholdDb;
            double compressionSlope = 1.0 - 1.0 / (double) compRatio;
            double kneeHalfDb = COMP_SOFT_KNEE_DB * 0.5;

            // 6 dB soft knee
            double gainReductionDb;
            if (overThresholdDb <= -kneeHalfDb) {
                gainReductionDb = 0.0;
            } else if (overThresholdDb >= kneeHalfDb) {
                gainReductionDb = -overThresholdDb * compressionSlope;
            } else {
                double kneePosition = overThresholdDb + kneeHalfDb;
                gainReductionDb = -compressionSlope * kneePosition * kneePosition / (2.0 * COMP_SOFT_KNEE_DB);
            }
            double totalGain = Math.pow(10.0, (gainReductionDb + compMakeupDb) / 20.0);
            x *= totalGain;
        }

        // Post 모드 리버브 탭 신호 확보 (컴프레서까지 통과한 신호)
        double postCompSignal = x;

        // [단계 4] 리버브 연산 및 믹스 (Pre / Post 선택 반영)
        if (reverbEnabled && reverbVolume > 0.001f) {
            double reverbSource = reverbPreComp ? preCompSignal : postCompSignal;
            x += processReverbTank(reverbSource);
        }

        return (float) clamp(x, -1.0, 1.0);
    }
}
