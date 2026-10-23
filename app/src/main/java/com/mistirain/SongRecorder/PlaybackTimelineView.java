package com.mistirain.SongRecorder;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/** Seekable playback timeline with current/total time labels. */
public class PlaybackTimelineView extends LinearLayout {

    public interface Listener {
        void onPosition(float ratio);
        void onSeekFinished();
    }

    private final Bar bar;
    private final TextView currentLabel;
    private final TextView totalLabel;
    private float position = 0f;
    private float durationSec = 0f;
    private Listener listener;

    public static String playbackTime(float seconds) {
        long total = (long) Math.max(seconds, 0f);
        return (total / 60) + ":" + String.format(Locale.US, "%02d", total % 60);
    }

    public PlaybackTimelineView(Context c) {
        super(c);
        setOrientation(VERTICAL);
        bar = new Bar(c);
        addView(bar, new LayoutParams(LayoutParams.MATCH_PARENT, Ui.dp(c, 40)));
        LinearLayout labels = Ui.horizontal(c);
        currentLabel = Ui.text(c, "0:00", 11);
        totalLabel = Ui.text(c, "0:00", 11);
        labels.addView(currentLabel, Ui.weight(1f));
        labels.addView(totalLabel, Ui.wrap());
        addView(labels, Ui.matchWrap());
    }

    public void setListener(Listener l) { listener = l; }

    public void setState(float position, float durationSec) {
        this.position = position;
        this.durationSec = durationSec;
        currentLabel.setText(playbackTime(position * durationSec));
        totalLabel.setText(playbackTime(durationSec));
        bar.invalidate();
    }

    private final class Bar extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Bar(Context c) {
            super(c);
            paint.setStrokeCap(Paint.Cap.ROUND);
            setContentDescription("재생 위치");
            setFocusable(true);
        }

        private float ratioAt(float x) {
            float inset = Ui.dp(getContext(), 8);
            return Ui.clamp((x - inset) / Math.max(getWidth() - 2 * inset, 1f), 0f, 1f);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            Context c = getContext();
            float inset = Ui.dp(c, 8);
            float end = getWidth() - inset;
            float x = inset + (end - inset) * Ui.clamp(position, 0f, 1f);
            float y = getHeight() / 2f;
            int active = Ui.primary(c);
            int onSurface = Ui.onSurface(c);
            int inactive = (onSurface & 0x00FFFFFF) | (0x38 << 24); // alpha 0.22
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Ui.dp(c, 3));
            paint.setColor(isEnabled() ? inactive : (inactive & 0x00FFFFFF) | (0x1A << 24));
            canvas.drawLine(inset, y, end, y, paint);
            paint.setColor(active);
            canvas.drawLine(inset, y, x, y, paint);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(x, y, Ui.dp(c, 5), paint);
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (!PlaybackTimelineView.this.isEnabled() || listener == null) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    getParent().requestDisallowInterceptTouchEvent(true);
                    // fall through
                case MotionEvent.ACTION_MOVE:
                    listener.onPosition(ratioAt(e.getX()));
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    getParent().requestDisallowInterceptTouchEvent(false);
                    listener.onSeekFinished();
                    return true;
                default:
                    return true;
            }
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(
                    AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT, 0f, 1f, Ui.clamp(position, 0f, 1f)));
            if (PlaybackTimelineView.this.isEnabled()) {
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);
            }
        }

        @Override
        public boolean performAccessibilityAction(int action, Bundle arguments) {
            if (action == android.R.id.accessibilityActionSetProgress && arguments != null
                    && PlaybackTimelineView.this.isEnabled() && listener != null) {
                float value = arguments.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE);
                listener.onPosition(Ui.clamp(value, 0f, 1f));
                listener.onSeekFinished();
                return true;
            }
            return super.performAccessibilityAction(action, arguments);
        }
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        bar.setEnabled(enabled);
        bar.invalidate();
    }
}
