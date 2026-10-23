package com.mistirain.SongRecorder;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** Waveform with draggable trim handles and a playhead cursor. */
public class WaveformEditorView extends View {

    public interface SelectionListener { void onSelection(float start, float end); }

    private final List<Float> amplitudes = new ArrayList<>();
    private float selStart = 0f;
    private float selEnd = 1f;
    private float position = 0f;
    private boolean editable = false;
    private SelectionListener listener;
    private int handle = -1;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    public WaveformEditorView(Context c) {
        super(c);
        setBackground(Ui.rounded(Color.parseColor("#161616"), Ui.dp(c, 6)));
        setClipToOutline(true);
    }

    public void setListener(SelectionListener l) { listener = l; }

    public void setAmplitudes(List<Float> values) {
        amplitudes.clear();
        amplitudes.addAll(values);
        invalidate();
    }

    public void addAmplitude(float value, int maxCount) {
        if (amplitudes.size() >= maxCount) amplitudes.remove(0);
        amplitudes.add(value);
        invalidate();
    }

    public void clearAmplitudes() {
        amplitudes.clear();
        invalidate();
    }

    public void setSelection(float start, float end) {
        selStart = start;
        selEnd = end;
        invalidate();
    }

    public void setPosition(float p) {
        if (position != p) {
            position = p;
            invalidate();
        }
    }

    public void setEditable(boolean e) {
        if (editable != e) {
            editable = e;
            invalidate();
        }
    }

    private float dp(float v) { return Ui.dp(getContext(), v); }

    @Override
    protected void onDraw(Canvas canvas) {
        float inset = dp(12);
        float width = Math.max(getWidth() - inset * 2, 1f);
        float left = inset + width * selStart;
        float right = inset + width * selEnd;
        float height = getHeight();

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x333B82F6);
        canvas.drawRect(left, 0, left + Math.max(right - left, 0f), height, paint);

        paint.setStrokeWidth(dp(2));
        int n = Math.max(amplitudes.size(), 1);
        for (int i = 0; i < amplitudes.size(); i++) {
            float x = inset + width * (i + 0.5f) / n;
            float half = Math.max(1.5f, amplitudes.get(i) * height * 0.45f);
            paint.setColor(x >= left && x <= right ? Color.parseColor("#60A5FA") : Color.DKGRAY);
            canvas.drawLine(x, height / 2 - half, x, height / 2 + half, paint);
        }
        if (editable) {
            float cursor = inset + width * Ui.clamp(position, 0f, 1f);
            paint.setColor(Color.YELLOW);
            canvas.drawLine(cursor, 0, cursor, height, paint);
            paint.setColor(Color.WHITE);
            for (float x : new float[]{left, right}) {
                canvas.drawLine(x, 0, x, height, paint);
                rect.set(x - dp(4), height / 2 - dp(12), x + dp(4), height / 2 + dp(12));
                canvas.drawRoundRect(rect, dp(3), dp(3), paint);
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!editable) return false;
        float inset = dp(12);
        float width = Math.max(getWidth() - inset * 2, 1f);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                float left = inset + width * selStart;
                float right = inset + width * selEnd;
                float ld = Math.abs(e.getX() - left);
                float rd = Math.abs(e.getX() - right);
                if (Math.min(ld, rd) > dp(28)) {
                    handle = -1;
                    return false;
                }
                handle = ld <= rd ? 0 : 1;
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (handle < 0) return false;
                float value = Ui.clamp((e.getX() - inset) / width, 0f, 1f);
                float minimum = Math.min(dp(2) / width, 0.02f);
                if (handle == 0) {
                    selStart = Math.max(Math.min(value, selEnd - minimum), 0f);
                } else {
                    selEnd = Math.min(Math.max(value, selStart + minimum), 1f);
                }
                invalidate();
                if (listener != null) listener.onSelection(selStart, selEnd);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                handle = -1;
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            default:
                return true;
        }
    }
}
