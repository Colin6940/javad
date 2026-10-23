package com.mistirain.SongRecorder;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Row of "Band N" toggle tiles used by both EQ editors. */
public class BandButtonRow extends LinearLayout {

    public interface Listener { void onBandClicked(int index); }

    private final TextView[] tiles;
    private final int[] colors;

    public BandButtonRow(Context c, int count, int[] colors, float spacingDp, Listener listener) {
        super(c);
        setOrientation(HORIZONTAL);
        this.colors = colors;
        tiles = new TextView[count];
        for (int i = 0; i < count; i++) {
            TextView t = new TextView(c);
            t.setText("Band " + (i + 1));
            t.setTextSize(11);
            t.setGravity(Gravity.CENTER);
            final int index = i;
            t.setOnClickListener(v -> listener.onBandClicked(index));
            LayoutParams lp = new LayoutParams(0, Ui.dp(c, 34), 1f);
            if (i > 0) lp.leftMargin = Ui.dp(c, spacingDp);
            addView(t, lp);
            tiles[i] = t;
        }
    }

    public void update(EqBandConfig[] bands, int selected) {
        Context c = getContext();
        for (int i = 0; i < tiles.length; i++) {
            boolean enabled = bands[i].enabled;
            boolean isSelected = i == selected;
            TextView t = tiles[i];
            t.setBackground(Ui.rounded(enabled ? colors[i] : Color.parseColor("#1F2937"), Ui.dp(c, 8),
                    Ui.dp(c, isSelected ? 2 : 1), isSelected ? Color.WHITE : Color.DKGRAY));
            t.setElevation(enabled ? Ui.dp(c, 6) : 0f);
            t.setTextColor(enabled ? Color.WHITE : Color.LTGRAY);
            t.setTypeface(enabled || isSelected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
    }
}
