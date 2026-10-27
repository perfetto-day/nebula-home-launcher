package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

/**
 * aurora_home's backdrop, drawn instead of a picture: a dark vertical gradient with soft coloured glows. Settings
 * picks one of {@link #SKIES}. The gradients are rendered once, with dithering, into a full-colour bitmap; every
 * frame after that is one texture draw, not several full-screen gradient passes, which a TV GPU feels on every
 * focus move. The glows stay dark enough under the clock and the bar for white text.
 */
final class AuroraSky extends View {

    /** One backdrop: its name, the base gradient's top and bottom, and glows of {cx, cy, rx, ry} (fractions of the
     *  screen) with their colours. */
    static final class Sky {
        final String key, name;
        final int top, bottom;
        final float[][] glows;
        final int[] colors;

        Sky(String key, String name, int top, int bottom, float[][] glows, int... colors) {
            this.key = key;
            this.name = name;
            this.top = top;
            this.bottom = bottom;
            this.glows = glows;
            this.colors = colors;
        }
    }

    static final Sky[] SKIES = {
            new Sky("aurora", "Aurora", 0xFF0A1322, 0xFF060912,
                    new float[][]{{0.28f, 0.06f, 0.57f, 0.33f}, {0.74f, 0.18f, 0.47f, 0.31f}},
                    0x6B2EC4AA, 0x577A54DC),
            new Sky("aurora_bright", "Aurora bright", 0xFF0A1424, 0xFF070B16,
                    new float[][]{{0.26f, 0.04f, 0.62f, 0.38f}, {0.76f, 0.16f, 0.52f, 0.36f},
                            {0.5f, 1.08f, 0.75f, 0.36f}},
                    0x9C30D5B5, 0x8A8A5CF0, 0x5C22C38E),
            new Sky("borealis", "Borealis", 0xFF07141A, 0xFF050B10,
                    new float[][]{{0.2f, 0.0f, 0.6f, 0.36f}, {0.82f, 1.02f, 0.62f, 0.42f},
                            {0.14f, 0.98f, 0.46f, 0.32f}},
                    0x7A2BD69A, 0x6620B4C8, 0x4D6A4FD8),
            new Sky("polar", "Polar night", 0xFF06101C, 0xFF040810,
                    new float[][]{{0.15f, 0.12f, 0.4f, 0.5f}, {0.5f, 0.02f, 0.45f, 0.4f},
                            {0.86f, 0.18f, 0.4f, 0.46f}, {0.6f, 1.06f, 0.72f, 0.32f}},
                    0x7030E0A0, 0x5C30C0E0, 0x66905AF0, 0x4D6A4FD8),
            new Sky("twilight", "Twilight", 0xFF120C24, 0xFF08060F,
                    new float[][]{{0.25f, 0.04f, 0.6f, 0.36f}, {0.8f, 0.14f, 0.46f, 0.3f},
                            {0.5f, 1.06f, 0.8f, 0.36f}},
                    0x807A4FE0, 0x66D6589E, 0x664A5BD6),
            new Sky("nebula", "Nebula", 0xFF0E0A1C, 0xFF06050E,
                    new float[][]{{0.72f, 0.08f, 0.5f, 0.36f}, {0.2f, 0.88f, 0.6f, 0.42f},
                            {0.9f, 0.98f, 0.36f, 0.3f}},
                    0x80B03CC8, 0x7A3A62E6, 0x4D2BC4D6),
            new Sky("ocean", "Ocean", 0xFF061423, 0xFF040A12,
                    new float[][]{{0.3f, 0.0f, 0.6f, 0.36f}, {0.72f, 1.02f, 0.7f, 0.42f},
                            {0.08f, 0.92f, 0.42f, 0.3f}},
                    0x6B1FA6D6, 0x7A2357D8, 0x4D1FC4B0),
            new Sky("forest", "Forest", 0xFF0A1410, 0xFF050A08,
                    new float[][]{{0.25f, 0.08f, 0.6f, 0.36f}, {0.8f, 0.96f, 0.56f, 0.36f},
                            {0.1f, 0.96f, 0.4f, 0.3f}},
                    0x6638A86A, 0x4D8CC84A, 0x4D2BA898),
            new Sky("sunset", "Sunset", 0xFF1A0D14, 0xFF0A0609,
                    new float[][]{{0.3f, 1.06f, 0.72f, 0.42f}, {0.8f, 0.04f, 0.5f, 0.32f},
                            {0.86f, 1.0f, 0.42f, 0.3f}},
                    0x80F0743A, 0x66C84A8C, 0x55F2B04A),
            new Sky("ember", "Ember", 0xFF160A08, 0xFF0A0505,
                    new float[][]{{0.2f, 0.96f, 0.6f, 0.42f}, {0.76f, 1.06f, 0.6f, 0.36f},
                            {0.8f, 0.04f, 0.46f, 0.3f}},
                    0x80D8402E, 0x66F08A2E, 0x4DA03050),
            new Sky("midnight", "Midnight", 0xFF0D1424, 0xFF070A12,
                    new float[][]{{0.5f, 0.0f, 0.8f, 0.36f}, {0.5f, 1.06f, 0.8f, 0.3f}},
                    0x40506A9C, 0x33405880),
            new Sky("graphite", "Graphite", 0xFF15171C, 0xFF0B0C0F,
                    new float[][]{{0.3f, 0.0f, 0.7f, 0.4f}, {0.8f, 1.0f, 0.6f, 0.36f}},
                    0x26FFFFFF, 0x1AFFFFFF),
    };

    private Sky sky;
    private Bitmap image;

    AuroraSky(Context context, String key) {
        super(context);
        sky = find(key);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    static Sky find(String key) {
        for (Sky s : SKIES) if (s.key.equals(key)) return s;
        return SKIES[0];
    }

    void show(String key) {
        Sky next = find(key);
        if (next == sky) return;
        sky = next;
        render(getWidth(), getHeight());
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        render(w, h);
    }

    private void render(int w, int h) {
        if (w <= 0 || h <= 0) return;
        if (image == null || image.getWidth() != w || image.getHeight() != h) {
            if (image != null) image.recycle();
            image = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        }
        Canvas c = new Canvas(image);
        Paint p = new Paint(Paint.DITHER_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, h * 0.68f, sky.top, sky.bottom, Shader.TileMode.CLAMP));
        c.drawPaint(p);
        for (int i = 0; i < sky.glows.length; i++) {
            float[] g = sky.glows[i];
            p.setShader(glow(w * g[0], h * g[1], w * g[2], h * g[3], sky.colors[i]));
            c.drawPaint(p);
        }
    }

    /** An elliptical glow fading to nothing: a circle gradient stretched by the ellipse's proportions. */
    private static Shader glow(float cx, float cy, float rx, float ry, int color) {
        RadialGradient g = new RadialGradient(0, 0, rx, new int[]{color, color & 0x00FFFFFF},
                new float[]{0f, 1f}, Shader.TileMode.CLAMP);
        Matrix m = new Matrix();
        m.setScale(1f, ry / rx);
        m.postTranslate(cx, cy);
        g.setLocalMatrix(m);
        return g;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (image != null) canvas.drawBitmap(image, 0, 0, null);
    }
}
