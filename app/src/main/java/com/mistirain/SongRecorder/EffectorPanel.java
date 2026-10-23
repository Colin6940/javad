package com.mistirain.SongRecorder;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import com.google.android.material.tabs.TabLayout;

/** Tabbed host for the four effect editors (게이트 / EQ / 컴프레서 / 리버브). */
public class EffectorPanel extends LinearLayout {

    private final DspProcessor dsp;
    private final FrameLayout container;
    private int selectedTab = 0;

    public EffectorPanel(Context c, DspProcessor dsp) {
        super(c);
        this.dsp = dsp;
        setOrientation(VERTICAL);

        TabLayout tabs = new TabLayout(c);
        tabs.setTabMode(TabLayout.MODE_FIXED);
        tabs.setTabGravity(TabLayout.GRAVITY_FILL);
        for (String title : new String[]{"게이트", "EQ", "컴프레서", "리버브"}) {
            tabs.addTab(tabs.newTab().setText(title));
        }
        addView(tabs, new LayoutParams(LayoutParams.MATCH_PARENT, Ui.dp(c, 38)));

        container = new FrameLayout(c);
        int h = Ui.dp(c, 10);
        int v = Ui.dp(c, 6);
        container.setPadding(h, v, h, v);
        addView(container, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));

        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) { show(tab.getPosition()); }
            @Override public void onTabUnselected(TabLayout.Tab tab) { }
            @Override public void onTabReselected(TabLayout.Tab tab) { }
        });
        show(0);
    }

    private void show(int index) {
        selectedTab = index;
        container.removeAllViews();
        Context c = getContext();
        View editor;
        switch (index) {
            case 1: editor = new ParametricEqEditor(c, dsp); break;
            case 2: editor = new CompressorEditor(c, dsp); break;
            case 3: editor = new PlateReverbEditor(c, dsp); break;
            case 0:
            default: editor = new MultibandGateEditor(c, dsp); break;
        }
        container.addView(editor, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    public int getSelectedTab() { return selectedTab; }

    /**
     * Sliders and EQ graphs live in this panel. Page swipes started here would fight with them,
     * so horizontal paging is paused for gestures that begin inside the effect panel.
     */
    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        androidx.viewpager2.widget.ViewPager2 pager = findPager();
        if (pager != null) {
            int action = ev.getActionMasked();
            if (action == android.view.MotionEvent.ACTION_DOWN) pager.setUserInputEnabled(false);
            else if (action == android.view.MotionEvent.ACTION_UP || action == android.view.MotionEvent.ACTION_CANCEL) {
                pager.setUserInputEnabled(true);
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    private androidx.viewpager2.widget.ViewPager2 findPager() {
        android.view.ViewParent p = getParent();
        while (p != null) {
            if (p instanceof androidx.viewpager2.widget.ViewPager2) return (androidx.viewpager2.widget.ViewPager2) p;
            p = p.getParent();
        }
        return null;
    }
}
