package com.mistirain.SongRecorder;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Persistent preset registry. A future preset screen can call savePreset + setDefaultPreset. */
public final class EffectPresetStore {
    private static final String PREFS_NAME = "SongRecorderEffectPresets";
    private static final String KEY_DEFAULT_PRESET_ID = "default_preset_id";
    private static final String KEY_PRESET_PREFIX = "preset_json_";

    private EffectPresetStore() { }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static EffectChainPreset loadDefaultPreset(Context context) {
        String defaultId = prefs(context).getString(KEY_DEFAULT_PRESET_ID, EffectChainPreset.BUILTIN_DEFAULT_ID);
        if (defaultId == null) defaultId = EffectChainPreset.BUILTIN_DEFAULT_ID;
        EffectChainPreset preset = loadPreset(context, defaultId);
        return preset != null ? preset : EffectChainPreset.builtInDefault();
    }

    public static EffectChainPreset loadPreset(Context context, String id) {
        String encoded = prefs(context).getString(KEY_PRESET_PREFIX + id, null);
        if (encoded == null) {
            return EffectChainPreset.BUILTIN_DEFAULT_ID.equals(id) ? EffectChainPreset.builtInDefault() : null;
        }
        try {
            return decode(new JSONObject(encoded));
        } catch (Exception e) {
            return null;
        }
    }

    public static void savePreset(Context context, EffectChainPreset preset) {
        try {
            prefs(context).edit().putString(KEY_PRESET_PREFIX + preset.id, encode(preset).toString()).apply();
        } catch (JSONException ignored) { }
    }

    public static boolean setDefaultPreset(Context context, String presetId) {
        if (loadPreset(context, presetId) == null) return false;
        prefs(context).edit().putString(KEY_DEFAULT_PRESET_ID, presetId).apply();
        return true;
    }

    /** Saves and selects a named preset as the new default (the future preset-management path). */
    public static void replaceDefaultPreset(Context context, EffectChainPreset preset) {
        savePreset(context, preset);
        prefs(context).edit().putString(KEY_DEFAULT_PRESET_ID, preset.id).apply();
    }

    public static void resetProcessorToDefault(Context context, DspProcessor dsp) {
        loadDefaultPreset(context).applyTo(dsp);
    }

    public static void saveWorkingSnapshot(Context context, DspProcessor dsp) {
        // The working snapshot is separate from the chosen default preset; editing effects must
        // not silently overwrite the user's "기본값" preset.
        savePreset(context, EffectChainPreset.capture(dsp, "working_snapshot", "현재 설정"));
    }

    private static JSONObject encode(EffectChainPreset preset) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", preset.id);
        o.put("name", preset.name);
        o.put("gateEnabled", preset.gateEnabled);
        o.put("gateLowThreshDb", (double) preset.gateLowThreshDb);
        o.put("gateMidThreshDb", (double) preset.gateMidThreshDb);
        o.put("gateHighThreshDb", (double) preset.gateHighThreshDb);
        o.put("eqEnabled", preset.eqEnabled);
        o.put("eqBands", bandsToJson(preset.eqBands));
        o.put("compEnabled", preset.compEnabled);
        o.put("compThresholdDb", (double) preset.compThresholdDb);
        o.put("compRatio", (double) preset.compRatio);
        o.put("compMakeupDb", (double) preset.compMakeupDb);
        o.put("reverbEnabled", preset.reverbEnabled);
        o.put("reverbPreComp", preset.reverbPreComp);
        o.put("reverbVolume", (double) preset.reverbVolume);
        o.put("reverbLowCutHz", (double) preset.reverbLowCutHz);
        o.put("reverbTimeMs", (double) preset.reverbTimeMs);
        o.put("reverbPreDelayMs", (double) preset.reverbPreDelayMs);
        o.put("reverbEqEnabled", preset.reverbEqEnabled);
        o.put("reverbEqBands", bandsToJson(preset.reverbEqBands));
        return o;
    }

    private static EffectChainPreset decode(JSONObject json) {
        JSONArray eq = json.optJSONArray("eqBands");
        JSONArray rev = json.optJSONArray("reverbEqBands");
        return EffectChainPreset.builder(
                        json.optString("id", EffectChainPreset.BUILTIN_DEFAULT_ID),
                        json.optString("name", "기본값"))
                .gateEnabled(json.optBoolean("gateEnabled", false))
                .gateLowThreshDb((float) json.optDouble("gateLowThreshDb", -45.0))
                .gateMidThreshDb((float) json.optDouble("gateMidThreshDb", -45.0))
                .gateHighThreshDb((float) json.optDouble("gateHighThreshDb", -45.0))
                .eqEnabled(json.optBoolean("eqEnabled", false))
                .eqBands(eq != null ? bandsFromJson(eq) : EffectChainPreset.builtInDefault().eqBands)
                .compEnabled(json.optBoolean("compEnabled", false))
                .compThresholdDb((float) json.optDouble("compThresholdDb", -20.0))
                .compRatio((float) json.optDouble("compRatio", 2.5))
                .compMakeupDb((float) json.optDouble("compMakeupDb", 0.0))
                .reverbEnabled(json.optBoolean("reverbEnabled", false))
                .reverbPreComp(json.optBoolean("reverbPreComp", false))
                .reverbVolume((float) json.optDouble("reverbVolume", 0.15))
                .reverbLowCutHz((float) json.optDouble("reverbLowCutHz", 90.0))
                .reverbTimeMs((float) json.optDouble("reverbTimeMs", 1000.0))
                .reverbPreDelayMs((float) json.optDouble("reverbPreDelayMs", 20.0))
                .reverbEqEnabled(json.optBoolean("reverbEqEnabled", false))
                .reverbEqBands(rev != null ? bandsFromJson(rev) : EffectChainPreset.builtInDefault().reverbEqBands)
                .build();
    }

    private static JSONArray bandsToJson(List<EqBandConfig> bands) throws JSONException {
        JSONArray array = new JSONArray();
        for (EqBandConfig band : bands) {
            JSONObject o = new JSONObject();
            o.put("enabled", band.enabled);
            o.put("freq", (double) band.freq);
            o.put("gainDb", (double) band.gainDb);
            o.put("q", (double) band.q);
            o.put("type", band.type.name());
            array.put(o);
        }
        return array;
    }

    private static List<EqBandConfig> bandsFromJson(JSONArray array) {
        List<EqBandConfig> list = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject band = array.optJSONObject(i);
            if (band == null) continue;
            FilterType type;
            try {
                type = FilterType.valueOf(band.optString("type", FilterType.PEAK.name()));
            } catch (Exception e) {
                type = FilterType.PEAK;
            }
            list.add(new EqBandConfig(
                    band.optBoolean("enabled", false),
                    (float) band.optDouble("freq", 1000.0),
                    (float) band.optDouble("gainDb", 0.0),
                    (float) band.optDouble("q", 1.0),
                    type));
        }
        return list;
    }
}
