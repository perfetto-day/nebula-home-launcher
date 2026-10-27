package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

/**
 * aurora_home's focusable item: one child drawn with rounded corners, and a glass ring around it while focused.
 * The ring sits {@link #GAP_DP} outside the child with a radius {@link #GAP_DP} larger, so its corners run parallel
 * to the child's. Focus lifts the item (Lift: scale, and for tiles a soft shadow); motion only decelerates.
 */
final class RingFrame extends FrameLayout {

    static final float GAP_DP = 3f, STROKE_DP = 1.5f;
    private static final long LIFT_MS = 240, RING_MS = 180;

    private final float density, gap, stroke;
    private final GlassRing ring;
    private final RectF box = new RectF();
    private float radius, ringAlpha, liftScale = 1.05f, liftZ;
    private boolean pill;
    private float aspect;
    private ValueAnimator ringAnim;

    RingFrame(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        gap = GAP_DP * density;
        stroke = STROKE_DP * density;
        int inset = Math.round(gap + stroke);
        setPadding(inset, inset, inset, inset);
        setFocusable(true);
        setClipChildren(false);
        setClipToPadding(false);
        setWillNotDraw(false);
        ring = new GlassRing(context);
    }

    /** Corner radius of the child in dp; a pill rounds to half its height whatever the size. */
    RingFrame corners(float radiusDp, boolean pill) {
        this.radius = radiusDp * density;
        this.pill = pill;
        return this;
    }

    /** Height as a fraction of width (9/16 for a banner); 0 leaves the height to the layout. */
    RingFrame aspect(float heightOverWidth) {
        this.aspect = heightOverWidth;
        return this;
    }

    /** How far focus lifts the item: its scale, and the child's shadow height in dp (0: no shadow). */
    RingFrame lift(float scale, float shadowDp) {
        this.liftScale = scale;
        this.liftZ = shadowDp * density;
        return this;
    }

    /** Adds the single child and clips it to the rounded shape. */
    RingFrame content(View child) {
        child.setDuplicateParentStateEnabled(true);
        child.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                float r = pill ? view.getHeight() / 2f : radius;
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
            }
        });
        child.setClipToOutline(true);
        addView(child, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        return this;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        if (aspect > 0 && MeasureSpec.getMode(widthSpec) == MeasureSpec.EXACTLY) {
            int pad = getPaddingLeft() + getPaddingRight();
            int h = Math.round((MeasureSpec.getSize(widthSpec) - pad) * aspect) + pad;
            heightSpec = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY);
        }
        super.onMeasure(widthSpec, heightSpec);
    }

    float liftScale() {
        return liftScale;
    }

    /** The ring's box in this frame's own (unscaled) coordinates. */
    void ringBox(RectF out) {
        View child = getChildAt(0);
        float half = stroke / 2f;
        out.set(child.getLeft() - gap - half, child.getTop() - gap - half,
                child.getRight() + gap + half, child.getBottom() + gap + half);
    }

    float ringRadius() {
        View child = getChildAt(0);
        return (pill ? child.getHeight() / 2f : radius) + gap + stroke / 2f;
    }

    /** The focus style was switched while this frame has focus: hand the ring to the block's glide, or take it back. */
    void restyle() {
        if (!isFocused()) return;
        FocusGlide glide = FocusGlide.of(this);
        boolean gliding = glide != null && FocusGlide.enabled(getContext());
        if (glide != null) {
            if (gliding) glide.focusGained(this);
            else glide.clear();
        }
        if (ringAnim != null) ringAnim.cancel();
        ringAlpha = gliding ? 0f : 1f;
        invalidate();
    }

    @Override
    protected void onFocusChanged(boolean focused, int direction, android.graphics.Rect previous) {
        super.onFocusChanged(focused, direction, previous);
        boolean motion = AuroraHome.motion(getContext());
        // Glide: the block's sliding ring stands in for this frame's own.
        FocusGlide glide = getChildCount() > 0 && FocusGlide.enabled(getContext()) ? FocusGlide.of(this) : null;
        if (glide != null) {
            if (focused) glide.focusGained(this);
            else glide.focusLost(this);
        }
        float scale = focused ? liftScale : 1f;
        View child = getChildCount() > 0 ? getChildAt(0) : null;
        if (motion) {
            animate().scaleX(scale).scaleY(scale).setDuration(LIFT_MS)
                    .setInterpolator(new DecelerateInterpolator(2f)).start();
            if (child != null && liftZ > 0)
                child.animate().translationZ(focused ? liftZ : 0).setDuration(LIFT_MS).start();
        } else {
            setScaleX(scale);
            setScaleY(scale);
            if (child != null && liftZ > 0) child.setTranslationZ(focused ? liftZ : 0);
        }
        if (ringAnim != null) ringAnim.cancel();
        float target = focused && glide == null ? 1f : 0f;
        if (!motion) {
            ringAlpha = target;
            invalidate();
            return;
        }
        ringAnim = ValueAnimator.ofFloat(ringAlpha, target);
        ringAnim.setDuration(RING_MS);
        ringAnim.setInterpolator(new DecelerateInterpolator());
        ringAnim.addUpdateListener(a -> {
            ringAlpha = (float) a.getAnimatedValue();
            invalidate();
        });
        ringAnim.start();
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (ringAlpha <= 0f || getChildCount() == 0) return;
        ringBox(box);
        ring.draw(canvas, box, ringRadius(), ringAlpha);
    }
}
