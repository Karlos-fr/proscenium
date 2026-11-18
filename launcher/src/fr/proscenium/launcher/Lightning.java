package fr.proscenium.launcher;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Éclairs d'orage, repris du studio de foudre du projet Platformer (ThunderWeather, réglages
 * « Orage Standard » de thunder-presets.json) :
 * <ul>
 * <li>tracé fractal : le segment de départ est coupé en deux à chaque niveau, le milieu déplacé au
 * hasard (chaos horizontal et vertical, réduit de 45 % à chaque niveau) ; le tronc a un chaos vertical
 * amplifié, d'où son zigzag ; des branches partent vers le bas, de côté, depuis le tronc ;</li>
 * <li>vie : l'éclair descend en {@code PROPAGATION} secondes (les branches naissent quand il les
 * atteint), puis s'efface ; pendant ce temps il scintille ;</li>
 * <li>rendu : un cœur blanc qui s'affine vers le bas, une lueur bleue en mélange additif (à la place du
 * bloom du studio), un flash qui éclaire la scène ;</li>
 * <li>déclenchement : un éclair toutes les {@code MIN_INTERVAL} à {@code MAX_INTERVAL} secondes, plus
 * souvent quand l'orage est fort.</li>
 * </ul>
 * Les longueurs du studio sont données pour une vue de 600 px de haut : elles sont mises à l'échelle.
 * Chaque réglage est multiplié par celui de la page Orage (WeatherStyles), en pourcentage.
 */
final class Lightning {
    // Réglages « Orage Standard » (thunder-presets.json)
    private static final float SPREAD_X = 95f, SPREAD_Y = 12f;
    private static final int DEPTH = 6;
    private static final float FORK_PROBABILITY = 0.35f, BRANCH_LENGTH = 80f;
    private static final float PROPAGATION = 0.15f, FADE_SPEED = 3.0f, FLICKER_FREQUENCY = 12f;
    private static final float MAIN_WIDTH = 6.0f, BRANCH_WIDTH = 2.5f;
    private static final int BOLT_COLOR = 0xFFFFFF, GLOW_COLOR = 0x0088FF;
    private static final float MIN_INTERVAL = 1.5f, MAX_INTERVAL = 6.0f;
    /** Hauteur de la vue du studio : les longueurs ci-dessus y sont mesurées. */
    private static final float STUDIO_HEIGHT = 600f;

    private static final class Branch {
        final float[] xs, ys;
        final int startIdx;

        Branch(float[][] pts, int startIdx) {
            xs = pts[0];
            ys = pts[1];
            this.startIdx = startIdx;
        }
    }

    private static final class Bolt {
        float[] xs, ys;
        final List<Branch> branches = new ArrayList<>();
        float progress, opacity = 1f;
    }

    private final Random random = new Random();
    private final List<Bolt> bolts = new ArrayList<>();
    private final Paint core = new Paint(Paint.ANTI_ALIAS_FLAG), glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private float timer = 2f;
    private long last;
    private float clock;
    private float flash;
    private final WeatherStyles st;
    /** Lueur de l'éclair en cours (couleur choisie dans la page Orage). */
    private int glowColor = GLOW_COLOR;

    Lightning(WeatherStyles styles) {
        st = styles;
        for (Paint p : new Paint[]{core, glow}) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
        }
    }

    void reset() {
        bolts.clear();
        timer = 1.5f + random.nextFloat() * 2f;
        last = 0;
        flash = 0;
    }

    boolean active() {
        return !bolts.isEmpty();
    }

    /** Éclat du ciel à cette image (0 à 1) : sert à éclairer la scène. */
    float flash() {
        return flash;
    }

    /** Délai avant le prochain éclair ; {@code intensity} de 0 à 1 (1 : intervalles les plus courts). */
    private float nextDelay(float intensity) {
        float scale = 2f - Math.max(0f, Math.min(1f, intensity));
        return (MIN_INTERVAL + random.nextFloat() * (MAX_INTERVAL - MIN_INTERVAL)) * scale / st.stormFrequency.f();
    }

    /** Avance d'une image ; renvoie vrai tant qu'un éclair est visible. */
    boolean update(long now, float intensity, int w, int h) {
        float dt = last == 0 ? 1f / 60f : Math.min(0.1f, (now - last) / 1000f);
        last = now;
        clock += dt;
        timer -= dt;
        if (timer <= 0) {
            createBolt(w, h);
            timer = nextDelay(intensity);
        }
        float max = 0;
        boolean visible = false;
        for (int i = bolts.size() - 1; i >= 0; i--) {
            Bolt b = bolts.get(i);
            if (b.progress < 1f) b.progress += dt / PROPAGATION;
            else b.opacity -= dt * FADE_SPEED / st.stormDuration.f();
            if (b.opacity <= 0) {
                bolts.remove(i);
                continue;
            }
            if (flickerOn()) {
                visible = true;
                max = Math.max(max, b.opacity);
            }
        }
        flash = visible ? Math.min(1.5f, max * (0.5f + 0.5f * Math.min(1f, intensity)) * st.stormFlash.f()) : 0f;
        return !bolts.isEmpty();
    }

    /** Scintillement : visible environ 73 % du temps. */
    private boolean flickerOn() {
        return Math.sin(clock * FLICKER_FREQUENCY * st.stormFlicker.f() * 10f) > -0.4;
    }

    /**
     * Subdivision fractale par déplacement du milieu : à chaque niveau, chaque segment est coupé en
     * deux et son milieu déplacé au hasard ; l'amplitude baisse de 45 % d'un niveau à l'autre.
     */
    private float[][] subdivide(float x0, float y0, float x1, float y1, float spreadX, float spreadY, int depth) {
        float[] xs = {x0, x1}, ys = {y0, y1};
        for (int d = 0; d < depth; d++) {
            int n = xs.length;
            float[] nx = new float[n * 2 - 1], ny = new float[n * 2 - 1];
            for (int i = 0; i < n - 1; i++) {
                nx[i * 2] = xs[i];
                ny[i * 2] = ys[i];
                nx[i * 2 + 1] = (xs[i] + xs[i + 1]) * 0.5f + (random.nextFloat() - 0.5f) * spreadX;
                ny[i * 2 + 1] = (ys[i] + ys[i + 1]) * 0.5f + (random.nextFloat() - 0.5f) * spreadY;
            }
            nx[n * 2 - 2] = xs[n - 1];
            ny[n * 2 - 2] = ys[n - 1];
            xs = nx;
            ys = ny;
            spreadX *= 0.55f;
            spreadY *= 0.55f;
        }
        return new float[][]{xs, ys};
    }

    /** Un nouvel éclair : il part au-dessus de l'écran et descend vers le milieu de l'image. */
    private void createBolt(int w, int h) {
        float k = h / STUDIO_HEIGHT;
        float chaos = st.stormChaos.f();
        glowColor = st.glowColor();
        float startX = w * (0.10f + random.nextFloat() * 0.80f);
        float startY = -10f * k;
        float endX = startX + (random.nextFloat() - 0.5f) * 200f * k;
        float endY = h * (0.35f + random.nextFloat() * 0.40f);
        Bolt b = new Bolt();
        // Tronc : chaos vertical amplifié (× 5) pour accentuer le zigzag
        float[][] main = subdivide(startX, startY, endX, endY, SPREAD_X * k * chaos, SPREAD_Y * 5f * k * chaos, DEPTH);
        b.xs = main[0];
        b.ys = main[1];
        int n = b.xs.length;
        // Branches : sur les points intermédiaires, vers le bas et de côté
        for (int i = 5; i < n - 8; i++) {
            if (random.nextFloat() >= FORK_PROBABILITY * st.stormForks.f() / 10f) continue;
            float side = random.nextFloat() < 0.5f ? -1f : 1f;
            double angle = Math.PI / 2 + side * (0.25 + random.nextFloat() * 0.65);
            float ex = b.xs[i] + (float) Math.cos(angle) * BRANCH_LENGTH * k;
            float ey = b.ys[i] + (float) Math.sin(angle) * BRANCH_LENGTH * k;
            b.branches.add(new Branch(subdivide(b.xs[i], b.ys[i], ex, ey, SPREAD_X * 0.4f * k * chaos, SPREAD_Y * k * chaos, 4), i));
        }
        bolts.add(b);
    }

    /** Dessine les éclairs visibles (mélange additif : ils éclairent ce qui est derrière). */
    void draw(Canvas c, int h) {
        if (bolts.isEmpty() || !flickerOn()) return;
        float k = h / STUDIO_HEIGHT * st.stormWidth.f();
        for (Bolt b : bolts) {
            float limit = Math.min(1f, b.progress);
            // Lueur large, lueur moyenne, puis le cœur blanc
            ribbon(c, b.xs, b.ys, MAIN_WIDTH * k * 3.2f, glowColor, b.opacity * 0.10f, limit);
            ribbon(c, b.xs, b.ys, MAIN_WIDTH * k * 1.4f, glowColor, b.opacity * 0.28f, limit);
            ribbon(c, b.xs, b.ys, MAIN_WIDTH * k * 0.45f, BOLT_COLOR, b.opacity, limit);
            for (Branch br : b.branches) {
                // La branche n'apparaît qu'une fois que la descente l'a atteinte
                float ratio = (b.progress * b.xs.length - br.startIdx) / 15f;
                if (ratio <= 0) continue;
                float lim = Math.min(1f, ratio);
                ribbon(c, br.xs, br.ys, BRANCH_WIDTH * k * 2.6f, glowColor, b.opacity * 0.08f, lim);
                ribbon(c, br.xs, br.ys, BRANCH_WIDTH * k * 0.5f, BOLT_COLOR, b.opacity * 0.7f, lim);
            }
        }
    }

    /**
     * Ruban le long du tracé, jusqu'à la fraction {@code limit} (descente), qui s'affine vers son bout
     * ({@code width} : pleine largeur au départ).
     */
    private void ribbon(Canvas c, float[] xs, float[] ys, float width, int rgb, float alpha, float limit) {
        int count = (int) (xs.length * limit);
        if (count < 2 || alpha <= 0.003f) return;
        Paint p = rgb == BOLT_COLOR ? core : glow;
        p.setColor(rgb);
        p.setAlpha(Math.round(Math.min(1f, alpha) * 255));
        if (p == glow) {
            // Lueur : un seul tracé (des segments superposés feraient des perles plus claires aux
            // jointures), d'une largeur moyenne
            path.rewind();
            path.moveTo(xs[0], ys[0]);
            for (int i = 1; i < count; i++) path.lineTo(xs[i], ys[i]);
            p.setStrokeWidth(width * 0.6f);
            c.drawPath(path, p);
            return;
        }
        for (int i = 0; i < count - 1; i++) {
            float wd = width * (1f - i / (float) count);
            if (wd < 0.4f) wd = 0.4f;
            p.setStrokeWidth(wd);
            c.drawLine(xs[i], ys[i], xs[i + 1], ys[i + 1], p);
        }
    }
}
