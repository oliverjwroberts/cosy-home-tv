package com.cosyhome.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

/**
 * The cosy valley behind the clock: sky, sun or moon, three hills and a cottage.
 *
 * Drawn on a 1920x1080 design canvas and scaled to the view. Everything that never moves is
 * painted once per phase into {@link #still}; each frame only adds the stars, clouds and chimney
 * smoke on top, at about 25 fps, so the TV's GPU is mostly drawing one bitmap.
 */
public class SceneView extends View {

    enum Phase { DAWN, DAY, DUSK, NIGHT }

    interface Listener {
        /** A new still image (and blurred copy) is ready: the phase or the size changed. */
        void onSceneChanged();
    }

    private static final float W = 1920f, H = 1080f;
    private static final long FRAME_MS = 40;

    /** Sky stops, three hill colours, window colour, window glow strength, dark clock text. */
    private static final class Palette {
        final int s1, s2, s3, h1, h2, h3, win;
        final float glow;
        final boolean darkText;

        Palette(int s1, int s2, int s3, int h1, int h2, int h3, int win, float glow, boolean darkText) {
            this.s1 = s1;
            this.s2 = s2;
            this.s3 = s3;
            this.h1 = h1;
            this.h2 = h2;
            this.h3 = h3;
            this.win = win;
            this.glow = glow;
            this.darkText = darkText;
        }
    }

    private static final Palette DAWN = new Palette(0xFF2E3A6B, 0xFFC98A9A, 0xFFF6C99A,
            0xFF7C6A93, 0xFF54497A, 0xFF2E2A4E, 0xFFFFC27A, 0.5f, false);
    private static final Palette DAY = new Palette(0xFF4F9CCB, 0xFF93C6E4, 0xFFD8EEF6,
            0xFF8DB59A, 0xFF64957A, 0xFF3F6E55, 0xFF2B3A40, 0f, true);
    private static final Palette DUSK = new Palette(0xFF2F2350, 0xFFB4506A, 0xFFF2A65A,
            0xFF7A4560, 0xFF4E2C4A, 0xFF24162E, 0xFFFFB35C, 0.75f, false);
    private static final Palette NIGHT = new Palette(0xFF04060E, 0xFF0B1230, 0xFF1A2550,
            0xFF161D36, 0xFF0E1326, 0xFF070A14, 0xFFFFB35C, 1f, false);

    private static final float[][] STARS = {
            {180, 90, 2}, {420, 180, 1.6f}, {690, 70, 2.2f}, {930, 150, 1.4f}, {1150, 60, 2},
            {1320, 130, 1.5f}, {1720, 90, 2.2f}, {1830, 260, 1.4f}, {1080, 300, 1.4f}, {820, 380, 1.2f},
            {1620, 400, 1.3f}, {560, 330, 1.2f}, {300, 420, 1.1f}, {1240, 240, 1.2f}, {80, 250, 1.5f}};
    /** Cloud puffs as {cx, cy, rx, ry}. */
    private static final float[][] CLOUDS = {
            {420, 260, 130, 34}, {500, 240, 90, 40}, {1000, 380, 160, 30}, {1080, 362, 80, 34}, {1760, 430, 120, 26}};

    private Phase phase = Phase.NIGHT;
    private Palette pal = NIGHT;
    private Bitmap still;
    private Bitmap blurred;
    private Listener listener;
    private boolean running;
    private final long start = SystemClock.uptimeMillis();
    /** For the still layers. Shaders draw with the paint's alpha, so it must never carry a frame's fade. */
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** For the per-frame stars, clouds and smoke, whose colours carry their own fades. */
    private final Paint fx = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path far = new Path(), mid = new Path(), near = new Path();

    public SceneView(Context context, AttributeSet attrs) {
        super(context, attrs);
        far.moveTo(0, 720);
        far.cubicTo(240, 640, 420, 660, 640, 700);
        far.cubicTo(860, 740, 1040, 640, 1280, 650);
        far.cubicTo(1500, 660, 1700, 720, 1920, 690);
        closeToBottom(far);
        mid.moveTo(0, 820);
        mid.cubicTo(260, 760, 520, 790, 760, 820);
        mid.cubicTo(1000, 850, 1240, 760, 1500, 770);
        mid.cubicTo(1700, 778, 1820, 800, 1920, 790);
        closeToBottom(mid);
        near.moveTo(0, 940);
        near.cubicTo(300, 880, 640, 900, 900, 930);
        near.cubicTo(1160, 960, 1440, 900, 1700, 905);
        near.cubicTo(1800, 907, 1880, 915, 1920, 920);
        closeToBottom(near);
    }

    private static void closeToBottom(Path p) {
        p.lineTo(W, H);
        p.lineTo(0, H);
        p.close();
    }

    void setListener(Listener l) {
        listener = l;
    }

    Phase phase() {
        return phase;
    }

    /** Daytime has a light sky, so the clock switches to dark text. */
    boolean wantsDarkText() {
        return pal.darkText;
    }

    /** A small, heavily blurred copy of the still scene, for the glass panels to sample. */
    Bitmap blurred() {
        return blurred;
    }

    void setPhase(Phase p) {
        if (p == phase && still != null) return;
        phase = p;
        pal = p == Phase.DAWN ? DAWN : p == Phase.DAY ? DAY : p == Phase.DUSK ? DUSK : NIGHT;
        rebuild();
    }

    /** Frees the full-screen still while an app is in front; the next {@link #setPhase} redraws it. */
    void release() {
        still = null;
    }

    /** Animate only while Home is on screen. */
    void setRunning(boolean run) {
        running = run;
        if (run) invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        rebuild();
    }

    private void rebuild() {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        // Full colour (about 8 MB at 1080p): RGB_565 bands visibly in the dark sky and the moon's glow.
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.scale(w / W, h / H);
        drawStill(c);
        still = bmp;
        blurred = Blur.of(bmp, 8, 5);
        invalidate();
        if (listener != null) listener.onSceneChanged();
    }

    // ---- the still layers -------------------------------------------------------------------------

    private void drawStill(Canvas c) {
        paint.setShader(new LinearGradient(0, 0, 0, H, new int[]{pal.s1, pal.s2, pal.s3},
                new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, paint);
        paint.setShader(null);

        switch (phase) {
            case DAWN:
                glowDisc(c, 1180, 660, 68, 0xFFFFE2B4, 0xFFFFD9A0, 0.35f);
                break;
            case DAY:
                glowDisc(c, 1500, 200, 66, 0xFFFFF8E2, 0xFFFFF6D6, 0.55f);
                break;
            case DUSK:
                glowDisc(c, 820, 706, 80, 0xFFFFC27E, 0xFFFFB46B, 0.45f);
                break;
            case NIGHT:
                glow(c, 1480, 230, 54, 0xFFF1EAD8, 0.16f);
                // The crescent: the moon's disc less an offset shadow disc, so the unlit part
                // never reaches past the moon's edge.
                Path moon = new Path();
                moon.addCircle(1480, 230, 54, Path.Direction.CW);
                Path shadow = new Path();
                shadow.addCircle(1502, 216, 48, Path.Direction.CW);
                moon.op(shadow, Path.Op.DIFFERENCE);
                fill(c, moon, 0xFFF1EAD8);
                break;
        }

        fill(c, far, pal.h1);
        fill(c, mid, pal.h2);

        if (pal.glow > 0) {
            int a = Math.round(pal.glow * 0x99);
            int core = (a << 24) | (pal.win & 0xFFFFFF);
            paint.setShader(new RadialGradient(1564, 746, 110, core, pal.win & 0xFFFFFF, Shader.TileMode.CLAMP));
            c.drawCircle(1564, 746, 110, paint);
            paint.setShader(null);
        }

        // Cottage, chimney and a few trees on the middle ridge.
        paint.setColor(pal.h3);
        c.drawRect(1520, 722, 1610, 778, paint);
        Path roof = new Path();
        roof.moveTo(1508, 726);
        roof.lineTo(1565, 684);
        roof.lineTo(1622, 726);
        roof.close();
        c.drawPath(roof, paint);
        c.drawRect(1588, 690, 1601, 716, paint);
        tree(c, 1650, 782, 34, 54);
        tree(c, 1690, 784, 38, 68);
        tree(c, 1468, 780, 28, 42);
        paint.setColor(pal.win);
        c.drawRect(1540, 738, 1558, 754, paint);
        c.drawRect(1570, 738, 1588, 754, paint);

        fill(c, near, pal.h3);
    }

    private void glowDisc(Canvas c, float x, float y, float r, int disc, int glow, float glowAlpha) {
        glow(c, x, y, r, glow, glowAlpha);
        paint.setColor(disc);
        c.drawCircle(x, y, r, paint);
    }

    /** The soft halo around a disc of radius r, without the disc itself. */
    private void glow(Canvas c, float x, float y, float r, int color, float alpha) {
        int a = Math.round(alpha * 255) << 24;
        paint.setShader(new RadialGradient(x, y, r * 2.6f, a | (color & 0xFFFFFF), color & 0xFFFFFF, Shader.TileMode.CLAMP));
        c.drawCircle(x, y, r * 2.6f, paint);
        paint.setShader(null);
    }

    private void tree(Canvas c, float x, float baseY, float w, float h) {
        Path p = new Path();
        p.moveTo(x, baseY);
        p.lineTo(x + w / 2, baseY - h);
        p.lineTo(x + w, baseY);
        p.close();
        c.drawPath(p, paint);
    }

    private void fill(Canvas c, Path p, int color) {
        paint.setColor(color);
        c.drawPath(p, paint);
    }

    // ---- each frame -------------------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        if (still == null) return;
        canvas.drawBitmap(still, 0, 0, null);
        float seconds = (SystemClock.uptimeMillis() - start) / 1000f;
        canvas.save();
        canvas.scale(getWidth() / W, getHeight() / H);
        if (phase == Phase.NIGHT || phase == Phase.DUSK) drawStars(canvas, seconds, phase == Phase.NIGHT ? 1f : 0.35f);
        if (phase == Phase.DAWN || phase == Phase.DAY) drawClouds(canvas, seconds);
        if (phase != Phase.DAY) drawSmoke(canvas, seconds);
        canvas.restore();
        if (running) postInvalidateDelayed(FRAME_MS);
    }

    private void drawStars(Canvas c, float s, float strength) {
        for (int i = 0; i < STARS.length; i++) {
            float[] st = STARS[i];
            // Every other star twinkles, each on its own slow period.
            float twinkle = i % 2 == 0 ? 1f : 0.65f + 0.35f * (float) Math.sin(s * (0.9f + i * 0.13f) + i);
            fx.setColor(Color.argb(Math.round(240 * strength * twinkle), 255, 255, 255));
            c.drawCircle(st[0], st[1], st[2], fx);
        }
    }

    private void drawClouds(Canvas c, float s) {
        // Drift 230 px one way over 70 s, then back: the mockup's ease-in-out alternate.
        float t = (float) (0.5 - 0.5 * Math.cos(Math.PI * s / 70.0));
        float dx = -90 + 230 * t;
        fx.setColor(Color.argb(phase == Phase.DAY ? 150 : 120, 255, 255, 255));
        for (float[] cl : CLOUDS) {
            c.drawOval(cl[0] - cl[2] + dx, cl[1] - cl[3], cl[0] + cl[2] + dx, cl[1] + cl[3], fx);
        }
    }

    private void drawSmoke(Canvas c, float s) {
        for (int i = 0; i < 3; i++) {
            float t = ((s + i * 2.33f) % 7f) / 7f;              // 0..1 over a 7 s rise
            float alpha = t < 0.2f ? t / 0.2f * 0.45f : 0.45f * (1 - (t - 0.2f) / 0.8f);
            float r = 10 * (0.6f + 1.1f * t);
            fx.setColor(Color.argb(Math.round(alpha * 255), 0xD8, 0xD2, 0xE2));
            c.drawCircle(1595 + 34 * t, 680 - 130 * t, r, fx);
        }
    }
}
