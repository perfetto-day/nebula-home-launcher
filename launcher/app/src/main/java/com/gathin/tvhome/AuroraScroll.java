package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.widget.ScrollView;

/**
 * aurora_home's scrolling page: it fades only at the top, under the clock and the bar. The fading edge length still
 * sets how far a focused row stays from either edge when the page scrolls to it, so the bottom row's lifted tile
 * and its focus ring are never cut by the screen's edge.
 */
public final class AuroraScroll extends ScrollView {

    public AuroraScroll(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected float getBottomFadingEdgeStrength() {
        return 0f;
    }

    /**
     * Scrolling back up to an item that fits on the first screen goes all the way to the top, so the page's heading
     * comes back too instead of staying cut under the fading edge.
     */
    @Override
    protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
        int delta = super.computeScrollDeltaToGetChildRectOnScreen(rect);
        int room = getHeight() - getPaddingTop() - getPaddingBottom() - getVerticalFadingEdgeLength();
        if (delta < 0 && rect.bottom <= room) return -getScrollY();
        return delta;
    }
}
