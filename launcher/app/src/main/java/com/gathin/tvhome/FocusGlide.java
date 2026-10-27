package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;

import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

/**
 * The Glide focus style: one ring per block of tiles (a row or a grid) that slides from tile to tile instead of each
 * tile fading its own ring. The ring's edges ride springs (Material 3 Expressive's spatial spring: a little
 * overshoot, then it settles), at full strength all the way. Entering or leaving the block fades it,
 * as the Lift style does, so moving between blocks, or to the tab bar, looks the same in both styles.
 * It is drawn in the block's overlay, above the tiles, in the block's coordinates, so it scrolls with them.
 */
final class FocusGlide extends Drawable {

    static final String LIFT = "lift", GLIDE = "glide";
    private static final float STIFFNESS = 600f, DAMPING = 0.8f;
    private static final long FADE_MS = 180;

    private final ViewGroup block;
    private final GlassRing ring;
    private final FloatValueHolder[] edges = new FloatValueHolder[4];
    private final SpringAnimation[] springs = new SpringAnimation[4];
    private final RectF rect = new RectF(), target = new RectF();
    private float radius, presence;
    private ValueAnimator fade;
    private RingFrame current;
    private final Runnable leave = () -> {
        current = null;
        fadeTo(0f);
    };

    private FocusGlide(ViewGroup block) {
        this.block = block;
        ring = new GlassRing(block.getContext());
        for (int i = 0; i < 4; i++) {
            edges[i] = new FloatValueHolder();
            springs[i] = new SpringAnimation(edges[i]);
            springs[i].setSpring(new SpringForce().setStiffness(STIFFNESS).setDampingRatio(DAMPING));
            springs[i].setMinimumVisibleChange(0.5f);
            springs[i].addUpdateListener((a, value, velocity) -> invalidateSelf());
        }
        // A block that is laid out again (rebuilt, or moved on the page) keeps the ring on its tile.
        block.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            setBounds(0, 0, r - l, b - t);
            if (current != null) v.post(this::follow);
        });
    }

    /** Makes the group one block: focus moving between its tiles glides there. */
    static void block(ViewGroup group) {
        if (group.getTag(R.id.aurora_glide) != null) return;
        FocusGlide glide = new FocusGlide(group);
        group.setTag(R.id.aurora_glide, glide);
        glide.setBounds(0, 0, group.getWidth(), group.getHeight());
        group.getOverlay().add(glide);
    }

    /** The block the view is in, or null outside every block. */
    static FocusGlide of(View view) {
        for (View v = view; v != null; v = v.getParent() instanceof View ? (View) v.getParent() : null) {
            Object glide = v.getTag(R.id.aurora_glide);
            if (glide instanceof FocusGlide) return (FocusGlide) glide;
        }
        return null;
    }

    static boolean enabled(Context context) {
        return GLIDE.equals(AuroraPrefs.text(context, "focus_style", LIFT));
    }

    void focusGained(RingFrame frame) {
        block.removeCallbacks(leave);
        boolean moving = current != null && presence > 0f && AuroraHome.motion(block.getContext());
        current = frame;
        if (frame.getWidth() == 0) {
            // Not laid out yet: the layout listener places it.
            fadeTo(1f);
            return;
        }
        if (!moving) {
            snap(frame);
        } else {
            measure(frame);
            springs[0].animateToFinalPosition(target.left);
            springs[1].animateToFinalPosition(target.top);
            springs[2].animateToFinalPosition(target.right);
            springs[3].animateToFinalPosition(target.bottom);
        }
        fadeTo(1f);
    }

    /** Losing focus waits a moment: when the next tile is in this block too, the ring slides there instead. */
    void focusLost(RingFrame frame) {
        if (frame != current) return;
        block.removeCallbacks(leave);
        block.post(leave);
    }

    /** Hides the ring at once, as when the style is switched back to Lift. */
    void clear() {
        block.removeCallbacks(leave);
        current = null;
        if (fade != null) fade.cancel();
        presence = 0f;
        invalidateSelf();
    }

    /** After a layout: a sliding ring is sent to the tile's new place, a resting one is moved there. */
    private void follow() {
        if (current == null || current.getWidth() == 0) return;
        boolean sliding = false;
        for (SpringAnimation s : springs) sliding |= s.isRunning();
        if (!sliding) {
            snap(current);
            return;
        }
        measure(current);
        springs[0].animateToFinalPosition(target.left);
        springs[1].animateToFinalPosition(target.top);
        springs[2].animateToFinalPosition(target.right);
        springs[3].animateToFinalPosition(target.bottom);
    }

    private void snap(RingFrame frame) {
        measure(frame);
        float[] values = {target.left, target.top, target.right, target.bottom};
        for (int i = 0; i < 4; i++) {
            springs[i].cancel();
            edges[i].setValue(values[i]);
        }
        invalidateSelf();
    }

    /** The ring's box around the frame's child as it will be once lifted, in the block's coordinates. */
    private void measure(RingFrame frame) {
        frame.ringBox(target);
        float scale = frame.liftScale();
        float px = frame.getWidth() / 2f, py = frame.getHeight() / 2f;
        target.set(px + (target.left - px) * scale, py + (target.top - py) * scale,
                px + (target.right - px) * scale, py + (target.bottom - py) * scale);
        float x = 0, y = 0;
        for (View v = frame; v != block && v.getParent() instanceof View; v = (View) v.getParent()) {
            View parent = (View) v.getParent();
            x += v.getLeft() - parent.getScrollX();
            y += v.getTop() - parent.getScrollY();
        }
        target.offset(x, y);
        radius = frame.ringRadius() * scale;
    }

    private void fadeTo(float to) {
        if (fade != null) fade.cancel();
        if (!AuroraHome.motion(block.getContext())) {
            presence = to;
            invalidateSelf();
            return;
        }
        fade = ValueAnimator.ofFloat(presence, to);
        fade.setDuration(FADE_MS);
        fade.setInterpolator(new DecelerateInterpolator());
        fade.addUpdateListener(a -> {
            presence = (float) a.getAnimatedValue();
            invalidateSelf();
        });
        fade.start();
    }

    @Override
    public void draw(Canvas canvas) {
        if (presence <= 0f) return;
        rect.set(edges[0].getValue(), edges[1].getValue(), edges[2].getValue(), edges[3].getValue());
        ring.draw(canvas, rect, radius, presence);
    }

    @Override
    public void setAlpha(int alpha) {
    }

    @Override
    public void setColorFilter(ColorFilter filter) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
