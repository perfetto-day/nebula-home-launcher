package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** A small on/off switch for the Aurora settings rows: the knob slides and the track fills, decelerating. */
final class AuroraSwitch extends View {

    /** Monochrome like the rest of the glass: a light track with a dark knob when on, a faint track when off. */
    private static final int ON = 0xFFE8EDF5, OFF = 0x33FFFFFF, KNOB_ON = 0xFF0B1020, KNOB_OFF = 0xFFC3CBD9;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF track = new RectF();
    private boolean checked;
    private float position;
    private ValueAnimator anim;

    AuroraSwitch(Context context) {
        super(context);
        setDuplicateParentStateEnabled(true);
    }

    boolean isChecked() {
        return checked;
    }

    void setChecked(boolean on, boolean animate) {
        checked = on;
        if (anim != null) anim.cancel();
        float target = on ? 1f : 0f;
        if (!animate || !AuroraHome.motion(getContext())) {
            position = target;
            invalidate();
            return;
        }
        anim = ValueAnimator.ofFloat(position, target);
        anim.setDuration(220);
        anim.setInterpolator(new DecelerateInterpolator(2f));
        anim.addUpdateListener(a -> {
            position = (float) a.getAnimatedValue();
            invalidate();
        });
        anim.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float h = getHeight(), w = getWidth(), r = h / 2f;
        track.set(0, 0, w, h);
        paint.setColor(blend(OFF, ON, position));
        canvas.drawRoundRect(track, r, r, paint);
        float knob = r - h * 0.14f;
        float cx = r + (w - 2 * r) * position;
        paint.setColor(blend(KNOB_OFF, KNOB_ON, position));
        canvas.drawCircle(cx, r, knob, paint);
    }

    private static int blend(int a, int b, float t) {
        int aa = a >>> 24, ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int ba = b >>> 24, br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return ((int) (aa + (ba - aa) * t) << 24) | ((int) (ar + (br - ar) * t) << 16)
                | ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
    }
}
