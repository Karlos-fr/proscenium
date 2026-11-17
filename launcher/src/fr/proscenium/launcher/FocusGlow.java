package fr.proscenium.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.ComposeShader;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.os.SystemClock;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import java.util.Random;

/**
 * Lueur colorée qui glisse d'une tuile à l'autre dans le dock.
 * Le flou est précalculé une fois dans une petite image (le flou matériel
 * n'existe pas sur Android 7), puis simplement étiré et teinté.
 *
 * Halo vivant : la lueur garde sa taille et sa place, mais son intensité varie tout autour
 * de l'appli. Des zones plus vives et plus sombres voyagent lentement le long du pourtour,
 * chacune à son rythme (trois harmoniques d'angle, vitesses et phases tirées au hasard,
 * renouvelées à chaque changement d'appli). L'éclat moyen reste constant : rien ne « pulse ».
 * Elle avance au rythme du Ken Burns ({@link #tick}).
 */
final class FocusGlow extends View {
    /** Rectangle « plein » de la lueur dans l'image : sert à caler la lueur autour de la tuile. */
    static final float INNER_X = 36f / 128f, INNER_Y = 36f / 58f;

    /** Points du dégradé circulaire qui module l'intensité autour du halo. */
    private static final int STOPS = 24;

    private static Bitmap glow;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF rect = new RectF();
    private final RectF from = new RectF();
    private ValueAnimator move;
    private int color = -1;

    private final Random random = new Random();
    private boolean animated;
    /** Ampleur des variations et vitesse de l'animation, 1 = réglage d'origine. */
    private float amount = 1f, speed = 1f;
    /** Temps propre de l'animation : il avance plus ou moins vite selon la vitesse. */
    private float clock;
    private long lastDraw, lastTick;
    /** Trois harmoniques d'angle : vitesse (rad/s, signe = sens de rotation), phase, poids. */
    private final float[] hSpeed = new float[3], hPhase = new float[3], hWeight = new float[3];
    private final int[] stopColors = new int[STOPS + 1];
    private final float[] stopPositions = new float[STOPS + 1];
    private final Matrix bitmapMatrix = new Matrix();
    private final BitmapShader bitmapShader;
    private boolean externalClock;

    FocusGlow(Context ctx) {
        super(ctx);
        if (glow == null) {
            glow = Bitmap.createBitmap(200, 130, Bitmap.Config.ARGB_8888);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(0xFFFFFFFF);
            p.setMaskFilter(new BlurMaskFilter(24, BlurMaskFilter.Blur.NORMAL));
            new Canvas(glow).drawRoundRect(new RectF(36, 36, 164, 94), 18, 18, p);
        }
        bitmapShader = new BitmapShader(glow, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        for (int i = 0; i <= STOPS; i++) stopPositions[i] = i / (float) STOPS;
        reseed();
    }

    void setColor(int c) {
        if (c == color) return;
        color = c;
        paint.setColorFilter(new PorterDuffColorFilter(c, PorterDuff.Mode.SRC_IN));
        invalidate();
    }

    /** Halo vivant ou fixe. */
    void setAnimated(boolean on) {
        if (on == animated) return;
        animated = on;
        invalidate();
    }

    /** Ampleur et vitesse du halo vivant (Barre d'apps → Lueur). */
    void setLiveness(float amount, float speed) {
        this.amount = amount;
        this.speed = speed;
        invalidate();
    }

    /** Sans Ken Burns, la lueur prend sa propre horloge (20 images/s). */
    void setExternalClock(boolean on) {
        if (on != externalClock) { externalClock = on; invalidate(); }
    }

    /** Nouveau tirage : chaque appli a sa propre façon de briller. */
    private void reseed() {
        for (int i = 0; i < 3; i++) {
            float sp = 0.25f + random.nextFloat() * 0.55f;
            hSpeed[i] = random.nextBoolean() ? sp : -sp;
            hPhase[i] = random.nextFloat() * 6.2832f;
            hWeight[i] = 0.6f + random.nextFloat() * 0.4f;
        }
    }

    /** Pas du Ken Burns : la lueur avance d'une image, 20 fois par seconde au plus. */
    void tick() {
        if (!animated || getAlpha() <= 0.01f || rect.isEmpty()) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastTick < 45) return;
        lastTick = now;
        invalidate();
    }

    /** Fait glisser la lueur vers {@code target} (coordonnées du parent). */
    void moveTo(RectF target) {
        if (move != null) move.cancel();
        if (rect.isEmpty()) {
            rect.set(target);
            invalidate();
            return;
        }
        if (!target.equals(rect)) reseed();
        from.set(rect);
        RectF to = new RectF(target);
        move = ValueAnimator.ofFloat(0f, 1f);
        move.setDuration(240);
        move.setInterpolator(new DecelerateInterpolator(1.6f));
        move.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            rect.set(from.left + (to.left - from.left) * t, from.top + (to.top - from.top) * t,
                    from.right + (to.right - from.right) * t, from.bottom + (to.bottom - from.bottom) * t);
            invalidate();
        });
        move.start();
    }

    /** Ne réclame aucune place : le dock garde la taille de ses tuiles. */
    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        setMeasuredDimension(
                MeasureSpec.getMode(wSpec) == MeasureSpec.EXACTLY ? MeasureSpec.getSize(wSpec) : 0,
                MeasureSpec.getMode(hSpec) == MeasureSpec.EXACTLY ? MeasureSpec.getSize(hSpec) : 0);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (rect.isEmpty()) return;
        if (!animated) {
            paint.setShader(null);
            c.drawBitmap(glow, null, rect, paint);
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (lastDraw > 0) clock += Math.min(200, now - lastDraw) / 1000f * speed;
        lastDraw = now;
        if (!externalClock && getAlpha() > 0.01f) postInvalidateDelayed(50);

        // Intensité selon l'angle autour du centre : 1 en moyenne, des zones plus vives et
        // plus sombres qui tournent lentement, chacune à sa vitesse
        float amp = 0.42f * amount;
        float sum = hWeight[0] + hWeight[1] + hWeight[2];
        for (int i = 0; i <= STOPS; i++) {
            double a = 2 * Math.PI * i / STOPS;
            double n = 0;
            for (int k = 0; k < 3; k++) n += hWeight[k] * Math.sin((k + 1) * a + hSpeed[k] * clock + hPhase[k]);
            float level = 0.88f + amp * (float) (n / sum);
            stopColors[i] = (Math.round(255 * Math.max(0.12f, Math.min(1f, level))) << 24) | 0xFFFFFF;
        }
        stopColors[STOPS] = stopColors[0];
        float cx = rect.centerX(), cy = rect.centerY();
        SweepGradient sweep = new SweepGradient(cx, cy, stopColors, stopPositions);
        // L'image floue (sa forme) multipliée par l'intensité selon l'angle, puis teintée
        bitmapMatrix.setRectToRect(new RectF(0, 0, glow.getWidth(), glow.getHeight()), rect, Matrix.ScaleToFit.FILL);
        bitmapShader.setLocalMatrix(bitmapMatrix);
        paint.setShader(new ComposeShader(bitmapShader, sweep, PorterDuff.Mode.DST_IN));
        c.drawRect(rect, paint);
        paint.setShader(null);
    }
}
