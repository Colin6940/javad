package com.mistirain.SongRecorder;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/**
 * Frequency-response graph with draggable band nodes. Used by the main EQ and the reverb EQ.
 *
 * <p>Two interaction styles are supported to match the original screens:</p>
 * <ul>
 *   <li>{@link Mode#NODE_HIT}: a drag only starts when the touch begins on a node; blank
 *   space is left to the parent ScrollView (main EQ).</li>
 *   <li>{@link Mode#NEAREST_SELECTED}: tap selects the closest node within 50px; any drag
 *   moves the currently selected node (reverb EQ).</li>
 * </ul>
 */
public class EqGraphView extends View {

    public enum Mode { NODE_HIT, NEAREST_SELECTED }

    public interface Callbacks {
        /** Number of nodes and their current values. */
        EqBandConfig band(int index);
        int bandCount();
        float responseDb(float freq);
        int selectedBand();
        void onSelectBand(int index);
        void onDragBand(int index, float freq, float gainDb);
        boolean curveActive();
    }

    private final Mode mode;
    private final int[] nodeColors;
    private final int steps;
    private final float curveWidth;
    private Callbacks callbacks;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path curvePath = new Path();
    private final RectF clipRect = new RectF();
    private final Path clipPath = new Path();
    private int draggingBand = -1;
    private boolean dragging = false;

    public EqGraphView(Context context, Mode mode, int[] nodeColors, int steps, float curveWidth) {
        super(context);
        this.mode = mode;
        this.nodeColors = nodeColors;
        this.steps = steps;
        this.curveWidth = curveWidth;
    }

    public void setCallbacks(Callbacks callbacks) {
        this.callbacks = callbacks;
        invalidate();
    }

    static float freqToNormX(float freq) {
        return Ui.clamp((float) (Math.log(freq / 20f) / Math.log(1000f)), 0f, 1f);
    }

    static float normXToFreq(float normX) {
        return Ui.clamp((float) (20f * Math.pow(1000f, Ui.clamp(normX, 0f, 1f))), 20f, 20000f);
    }

    static float gainToNormY(float gain) {
        return Ui.clamp((15f - gain) / 30f, 0f, 1f);
    }

    static float normYToGain(float normY) {
        return Ui.clamp(15f - Ui.clamp(normY, 0f, 1f) * 30f, -15f, 15f);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        boolean dark = Ui.isDark(getContext());

        clipRect.set(0, 0, w, h);
        float r = Ui.dp(getContext(), 8);
        clipPath.reset();
        clipPath.addRoundRect(clipRect, r, r, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clipPath);
        canvas.drawColor(dark ? Color.parseColor("#121212") : Color.WHITE);

        paint.setStyle(Paint.Style.STROKE);
        int gridColor = dark ? Color.parseColor("#262626") : Color.parseColor("#E5E7EB");
        int midGridColor = dark ? Color.parseColor("#444444") : Color.parseColor("#D1D5DB");
        if (mode == Mode.NODE_HIT) {
            paint.setColor(gridColor);
            paint.setStrokeWidth(1f);
            canvas.drawLine(0, h * 0.25f, w, h * 0.25f, paint);
            canvas.drawLine(0, h * 0.75f, w, h * 0.75f, paint);
        }
        paint.setColor(midGridColor);
        paint.setStrokeWidth(2f);
        canvas.drawLine(0, h * 0.5f, w, h * 0.5f, paint);
        paint.setColor(dark ? Color.parseColor("#2A2A2A") : Color.parseColor("#F3F4F6"));
        paint.setStrokeWidth(1.5f);
        for (float f : new float[]{100f, 1000f, 10000f}) {
            float gx = freqToNormX(f) * w;
            canvas.drawLine(gx, 0, gx, h, paint);
        }

        if (callbacks == null) {
            canvas.restore();
            return;
        }

        curvePath.reset();
        for (int s = 0; s <= steps; s++) {
            float normX = (float) s / steps;
            float freq = normXToFreq(normX);
            float db = Ui.clamp(callbacks.responseDb(freq), -15f, 15f);
            float x = normX * w;
            float y = gainToNormY(db) * h;
            if (s == 0) curvePath.moveTo(x, y);
            else curvePath.lineTo(x, y);
        }
        paint.setColor(callbacks.curveActive() ? Color.parseColor("#38BDF8") : Color.GRAY);
        paint.setStrokeWidth(curveWidth);
        canvas.drawPath(curvePath, paint);

        int selected = callbacks.selectedBand();
        for (int idx = 0; idx < callbacks.bandCount(); idx++) {
            EqBandConfig band = callbacks.band(idx);
            float cx = freqToNormX(band.freq) * w;
            float cy = gainToNormY(band.gainDb) * h;
            float radius = idx == selected ? 22f : 15f;
            int activeColor = nodeColors[idx];
            paint.setStyle(Paint.Style.FILL);
            if (band.enabled) {
                paint.setColor((activeColor & 0x00FFFFFF) | (0x59 << 24)); // alpha 0.35
                canvas.drawCircle(cx, cy, radius + 12f, paint);
                paint.setColor(activeColor);
                canvas.drawCircle(cx, cy, radius, paint);
            } else {
                paint.setColor(Color.parseColor("#4B5563"));
                canvas.drawCircle(cx, cy, radius, paint);
            }
            if (idx == selected) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(3f);
                paint.setColor(Color.WHITE);
                canvas.drawCircle(cx, cy, radius + 4f, paint);
            }
        }
        canvas.restore();
    }

    private int nearestBand(float x, float y, float maxDistance, boolean useNodeRadius) {
        float w = Math.max(getWidth(), 1);
        float h = Math.max(getHeight(), 1);
        int closest = -1;
        float best = Float.MAX_VALUE;
        int selected = callbacks.selectedBand();
        for (int i = 0; i < callbacks.bandCount(); i++) {
            EqBandConfig b = callbacks.band(i);
            float dx = x - freqToNormX(b.freq) * w;
            float dy = y - gainToNormY(b.gainDb) * h;
            float d = (float) Math.hypot(dx, dy);
            float limit = useNodeRadius ? (i == selected ? 22f : 15f) + 7f : maxDistance;
            if (d <= limit && d < best) {
                best = d;
                closest = i;
            }
        }
        return closest;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (callbacks == null || !isEnabled()) return false;
        float w = Math.max(getWidth(), 1);
        float h = Math.max(getHeight(), 1);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                dragging = false;
                if (mode == Mode.NODE_HIT) {
                    int hit = nearestBand(e.getX(), e.getY(), 0f, true);
                    if (hit < 0) return false; // let the parent scroll
                    draggingBand = hit;
                    callbacks.onSelectBand(hit);
                    getParent().requestDisallowInterceptTouchEvent(true);
                    invalidate();
                    return true;
                } else {
                    int hit = nearestBand(e.getX(), e.getY(), 50f, false);
                    if (hit >= 0) callbacks.onSelectBand(hit);
                    draggingBand = callbacks.selectedBand();
                    getParent().requestDisallowInterceptTouchEvent(true);
                    invalidate();
                    return true;
                }
            }
            case MotionEvent.ACTION_MOVE: {
                if (draggingBand < 0) return false;
                dragging = true;
                float freq = normXToFreq(e.getX() / w);
                float gain = normYToGain(e.getY() / h);
                callbacks.onDragBand(draggingBand, freq, gain);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
                if (!dragging) performClick();
                // fall through
            case MotionEvent.ACTION_CANCEL:
                draggingBand = -1;
                dragging = false;
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
