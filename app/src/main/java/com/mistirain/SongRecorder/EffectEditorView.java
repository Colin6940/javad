package com.mistirain.SongRecorder;

import android.content.Context;
import android.widget.LinearLayout;

import androidx.core.widget.NestedScrollView;

/** Base class for the four effect editors: a vertical scroll container that refreshes its
 * controls whenever a notification action changes the shared DSP (effect revision). */
public abstract class EffectEditorView extends NestedScrollView implements AppAudioRuntime.RevisionListener {

    protected final DspProcessor dsp;
    protected final LinearLayout content;

    protected EffectEditorView(Context context, DspProcessor dsp) {
        super(context);
        this.dsp = dsp;
        content = Ui.vertical(context);
        int pad = Ui.dp(context, 2);
        content.setPadding(0, pad, 0, Ui.dp(context, 12));
        addView(content, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    /** Re-reads every control from {@link #dsp}. */
    protected abstract void refresh();

    @Override
    public void onRevisionChanged(int revision) {
        refresh();
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        AppAudioRuntime.addEffectRevisionListener(this);
        refresh();
    }

    @Override
    protected void onDetachedFromWindow() {
        AppAudioRuntime.removeEffectRevisionListener(this);
        super.onDetachedFromWindow();
    }
}
