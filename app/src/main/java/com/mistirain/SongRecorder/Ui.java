package com.mistirain.SongRecorder;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;

import java.util.List;

/** Small programmatic-view helpers that replace the Compose building blocks. */
public final class Ui {
    private Ui() { }

    public interface FloatListener { void onValue(float value); }
    public interface BoolListener { void onValue(boolean value); }
    public interface IntListener { void onValue(int value); }

    public static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    public static boolean isDark(Context c) {
        int mode = c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    public static int primary(Context c) {
        return MaterialColors.getColor(c, androidx.appcompat.R.attr.colorPrimary, Color.parseColor("#2563EB"));
    }

    public static int onSurface(Context c) {
        return MaterialColors.getColor(c, com.google.android.material.R.attr.colorOnSurface, Color.BLACK);
    }

    public static int onSurfaceVariant(Context c) {
        return MaterialColors.getColor(c, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY);
    }

    public static int background(Context c) {
        return isDark(c) ? Color.parseColor("#121212") : Color.parseColor("#F9FAFB");
    }

    public static TextView text(Context c, CharSequence s, float sp) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(onSurface(c));
        return t;
    }

    public static TextView text(Context c, CharSequence s, float sp, boolean bold) {
        TextView t = text(c, s, sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    public static TextView muted(Context c, CharSequence s, float sp) {
        TextView t = text(c, s, sp);
        t.setTextColor(Color.GRAY);
        return t;
    }

    public static LinearLayout vertical(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout horizontal(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    public static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    public static LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w);
    }

    public static LinearLayout.LayoutParams fixedHeight(Context c, float heightDp) {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, heightDp));
    }

    public static View spacer(Context c, float widthDp, float heightDp) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(c, widthDp), dp(c, heightDp)));
        return v;
    }

    /** Adds a child with a top margin, mimicking Arrangement.spacedBy. */
    public static void addSpaced(LinearLayout parent, View child, float topDp) {
        ViewGroup.LayoutParams lp = child.getLayoutParams();
        LinearLayout.LayoutParams llp = lp instanceof LinearLayout.LayoutParams
                ? (LinearLayout.LayoutParams) lp : matchWrap();
        if (parent.getChildCount() > 0) llp.topMargin = dp(parent.getContext(), topDp);
        parent.addView(child, llp);
    }

    public static View divider(Context c, float thicknessDp, int color) {
        View v = new View(c);
        v.setBackgroundColor(color);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, thicknessDp))));
        return v;
    }

    public static MaterialSwitch toggle(Context c, boolean checked, BoolListener listener) {
        MaterialSwitch s = new MaterialSwitch(c);
        s.setChecked(checked);
        s.setOnCheckedChangeListener((b, isChecked) -> listener.onValue(isChecked));
        return s;
    }

    public static Slider slider(Context c, float from, float to, float value, FloatListener listener) {
        Slider s = new Slider(c);
        s.setValueFrom(from);
        s.setValueTo(to);
        s.setValue(clamp(value, from, to));
        s.setLabelBehavior(com.google.android.material.slider.LabelFormatter.LABEL_GONE);
        s.addOnChangeListener((slider, v, fromUser) -> {
            if (fromUser) listener.onValue(v);
        });
        return s;
    }

    /** Sets a slider value without firing the user listener, clamping to its range. */
    public static void setSlider(Slider s, float value) {
        float v = clamp(value, s.getValueFrom(), s.getValueTo());
        float step = s.getStepSize();
        if (step > 0f) {
            v = s.getValueFrom() + Math.round((v - s.getValueFrom()) / step) * step;
            v = clamp(v, s.getValueFrom(), s.getValueTo());
        }
        if (s.getValue() != v) s.setValue(v);
    }

    public static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    public static MaterialButton button(Context c, String label, View.OnClickListener onClick) {
        MaterialButton b = new MaterialButton(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setInsetTop(0);
        b.setInsetBottom(0);
        b.setMinHeight(dp(c, 34));
        b.setMinimumHeight(dp(c, 34));
        b.setPadding(dp(c, 12), 0, dp(c, 12), 0);
        b.setOnClickListener(onClick);
        return b;
    }

    public static MaterialButton outlinedButton(Context c, String label, View.OnClickListener onClick) {
        MaterialButton b = new MaterialButton(c, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setInsetTop(0);
        b.setInsetBottom(0);
        b.setMinHeight(dp(c, 34));
        b.setMinimumHeight(dp(c, 34));
        b.setOnClickListener(onClick);
        return b;
    }

    public static void tintButton(MaterialButton b, int background, int foreground) {
        b.setBackgroundTintList(ColorStateList.valueOf(background));
        b.setTextColor(foreground);
    }

    /** A "reset" button used at the bottom of every effect editor. */
    public static MaterialButton resetButton(Context c, View.OnClickListener onClick) {
        MaterialButton b = outlinedButton(c, "초기화", onClick);
        b.setLayoutParams(fixedHeight(c, 34));
        return b;
    }

    public static Chip chip(Context c, String label) {
        Chip chip = new Chip(c, null, com.google.android.material.R.attr.chipStyle);
        chip.setText(label);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        chip.setCheckable(true);
        chip.setCheckedIconVisible(true);
        chip.setEnsureMinTouchTargetSize(false);
        chip.setChipMinHeight(dp(c, 30));
        return chip;
    }

    /** Single-selection chip row (Compose FilterChip equivalent). */
    public static ChipGroup chipGroup(Context c, List<String> labels, int selected, IntListener listener) {
        ChipGroup group = new ChipGroup(c);
        group.setSingleSelection(true);
        group.setSelectionRequired(true);
        group.setChipSpacingHorizontal(dp(c, 4));
        for (int i = 0; i < labels.size(); i++) {
            Chip chip = chip(c, labels.get(i));
            chip.setId(View.generateViewId());
            chip.setTag(i);
            group.addView(chip);
            if (i == selected) chip.setChecked(true);
        }
        group.setOnCheckedStateChangeListener((g, ids) -> {
            if (ids.isEmpty()) return;
            View v = g.findViewById(ids.get(0));
            if (v != null) listener.onValue((Integer) v.getTag());
        });
        return group;
    }

    public static void selectChip(ChipGroup group, int index) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View v = group.getChildAt(i);
            if (v instanceof Chip && Integer.valueOf(index).equals(v.getTag())) {
                if (!((Chip) v).isChecked()) ((Chip) v).setChecked(true);
                return;
            }
        }
    }

    /** Compact help button that shows a dialog (EffectHelpButton). */
    public static ImageButton helpButton(Context c, String title, String description) {
        ImageButton b = new ImageButton(c);
        b.setImageResource(R.drawable.ic_help_outline);
        b.setColorFilter(onSurfaceVariant(c));
        TypedValue tv = new TypedValue();
        c.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true);
        b.setBackgroundResource(tv.resourceId);
        b.setContentDescription("도움말");
        int size = dp(c, 32);
        b.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        b.setPadding(dp(c, 6), dp(c, 6), dp(c, 6), dp(c, 6));
        b.setOnClickListener(v -> new MaterialAlertDialogBuilder(c)
                .setTitle(title)
                .setMessage(description)
                .setPositiveButton("확인", null)
                .show());
        return b;
    }

    /** Header row: switch + bold title + help icon. */
    public static LinearLayout switchHeader(Context c, MaterialSwitch sw, String title, String helpTitle, String helpBody) {
        LinearLayout row = horizontal(c);
        row.addView(sw, wrap());
        row.addView(spacer(c, 6, 1));
        row.addView(text(c, title, 13, true), wrap());
        row.addView(helpButton(c, helpTitle, helpBody));
        return row;
    }

    public static GradientDrawable rounded(int color, float radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radiusPx);
        return d;
    }

    public static GradientDrawable rounded(int color, float radiusPx, int strokePx, int strokeColor) {
        GradientDrawable d = rounded(color, radiusPx);
        d.setStroke(strokePx, strokeColor);
        return d;
    }
}
