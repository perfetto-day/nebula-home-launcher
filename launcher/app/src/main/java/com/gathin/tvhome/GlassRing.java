package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * The focus ring, drawn as glass rather than solid white: a translucent line lit from above (brighter at the top,
 * fainter at the bottom) over a soft halo. The gradient is built once in unit space and stretched to the ring, so
 * drawing it while it animates allocates nothing.
 */
final class GlassRing {

    private static final int TOP = 0xE6FFFFFF, BOTTOM = 0x8CFFFFFF, HALO = 0x1FFFFFFF;
    private static final float HALO_DP = 4f;

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final LinearGradient light = new LinearGradient(0, 0, 0, 1, TOP, BOTTOM, Shader.TileMode.CLAMP);
    private final Matrix stretch = new Matrix();

    GlassRing(Context context) {
        float density = context.getResources().getDisplayMetrics().density;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(RingFrame.STROKE_DP * density);
        line.setShader(light);
        halo.setStyle(Paint.Style.STROKE);
        halo.setStrokeWidth(HALO_DP * density);
        halo.setColor(HALO);
    }

    /** @param alpha 0..1, the ring's own fade */
    void draw(Canvas canvas, RectF box, float radius, float alpha) {
        int a = Math.round(255 * alpha);
        halo.setAlpha(Math.round(Color.alpha(HALO) * alpha));
        canvas.drawRoundRect(box, radius, radius, halo);
        stretch.setScale(1f, Math.max(1f, box.height()));
        stretch.postTranslate(0, box.top);
        light.setLocalMatrix(stretch);
        line.setAlpha(a);
        canvas.drawRoundRect(box, radius, radius, line);
    }
}
