package fr.proscenium.launcher;

import android.graphics.RectF;

import java.util.List;

/**
 * Moteur de réfraction du verre, transposé de Codex Glass
 * (src/glass/WidgetGlassEffectDistortion.cpp, rendu CPU) :
 *  - forme en rectangle arrondi (SDF) dont les bords dévient l'image vers l'intérieur,
 *    avec aberration chromatique le long des normales ;
 *  - lentilles locales autour de chaque tuile ;
 *  - animations cumulables Eau calme, Liquide et Pluie (mêmes formules et coefficients).
 * Travaille sur une image réduite (ARGB) ; {@code unit} convertit les pixels de référence
 * de Codex Glass en pixels de cette image.
 */
final class GlassEngine {
    private int w, h;
    private float unit = 0.5f;
    private int[] source, blurred, out;
    // Partie statique (bords + lentilles), recalculée seulement quand la géométrie ou l'apparence changent
    private float[] staticDx, staticDy, chromaX, chromaY, edgeFade;
    private boolean staticDirty = true;
    private int blurRadius = -1;

    private final GlassSettings s;

    GlassEngine(GlassSettings settings) {
        s = settings;
    }

    private volatile boolean animationsAllowed = true;

    void setAnimationsAllowed(boolean allowed) {
        animationsAllowed = allowed;
    }

    void markStaticDirty() {
        staticDirty = true;
    }

    /** Nouvelle image source (déjà réduite), et géométrie du panneau. */
    synchronized void setSource(int[] pixels, int width, int height, float unitPx) {
        if (width != w || height != h || unitPx != unit) staticDirty = true;
        w = width;
        h = height;
        unit = unitPx;
        source = pixels;
        blurRadius = -1;
    }

    synchronized boolean hasSource() {
        return source != null;
    }

    /**
     * Calcule l'image du verre au temps donné. Renvoie le tableau de sortie (réutilisé).
     * {@code cornerRadius} et {@code lenses} sont en pixels de l'image réduite.
     */
    synchronized int[] render(float timeSec, float cornerRadius, List<RectF> lenses) {
        if (source == null) return null;
        int n = w * h;
        if (out == null || out.length != n) out = new int[n];

        // Diffusion : flou du fond, proportionnel au réglage (la référence floute légèrement)
        int radius = Math.round(1.6f * s.diffusion.f());
        if (radius != blurRadius || blurred == null || blurred.length != n) {
            blurRadius = radius;
            blurred = source.clone();
            if (radius > 0) Blur.box(blurred, w, h, radius, 2);
        }
        if (staticDirty || staticDx == null || staticDx.length != n) {
            buildStatic(cornerRadius, lenses);
            staticDirty = false;
        }

        boolean anim = s.animated() && animationsAllowed;
        Anim a = anim ? new Anim(timeSec) : null;
        for (int y = 0, i = 0; y < h; y++) {
            for (int x = 0; x < w; x++, i++) {
                float ox = staticDx[i], oy = staticDy[i];
                if (anim) {
                    a.offset(x, y, edgeFade[i]);
                    ox += a.ox;
                    oy += a.oy;
                }
                float sx = x + ox, sy = y + oy;
                float cx = chromaX[i], cy = chromaY[i];
                int r = channel(sx - cx, sy - cy, 16);
                int g = channel(sx, sy, 8);
                int b = channel(sx + cx, sy + cy, 0);
                out[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- Partie statique

    private void buildStatic(float cornerRadius, List<RectF> lenses) {
        int n = w * h;
        staticDx = new float[n];
        staticDy = new float[n];
        chromaX = new float[n];
        chromaY = new float[n];
        edgeFade = new float[n];

        float cxF = (w - 1) * 0.5f, cyF = (h - 1) * 0.5f;
        float radius = Math.min(cornerRadius, Math.min(cxF, cyF) * 0.42f);
        // Bord du panneau : réfraction vers l'intérieur, avec ondulation de surface
        Shape frame = new Shape(cxF, cyF, cxF, cyF, radius,
                58f * unit * s.edgeWidth.f(), 18f * unit * s.edgeRefraction.f(),
                1.35f * unit * s.chromatic.f(), true, -1f, true, 1f);
        Shape[] shapes = new Shape[1 + (lenses == null ? 0 : lenses.size())];
        shapes[0] = frame;
        int count = 1;
        if (lenses != null) {
            float strength = 8f * unit * s.lensRefraction.f();
            for (RectF l : lenses) {
                if (l.width() <= 2 || l.height() <= 2 || Math.abs(strength) < 0.0001f) continue;
                float hw = l.width() / 2, hh = l.height() / 2;
                shapes[count++] = new Shape(l.centerX(), l.centerY(), hw, hh, Math.min(14f * unit, Math.min(hw, hh)),
                        12f * unit * s.lensWidth.f(), Math.abs(strength), 0.85f * unit * s.chromatic.f(),
                        false, strength > 0 ? -1f : 1f, false, s.lensSoftness.f());
            }
        }

        float fadeWidth = 18f * unit;
        for (int y = 0, i = 0; y < h; y++) {
            for (int x = 0; x < w; x++, i++) {
                float d = Math.min(Math.min(x, w - 1 - x), Math.min(y, h - 1 - y));
                edgeFade[i] = smooth(0, fadeWidth, d);

                float best = 0, nx = 0, ny = 0, chroma = 0, dir = -1;
                for (int k = 0; k < count; k++) {
                    Shape sh = shapes[k];
                    float px = x - sh.cx, py = y - sh.cy;
                    float sdf = sdf(px, py, sh.hw, sh.hh, sh.r);
                    float edge = sh.insideOnly ? Math.max(0, -sdf) : Math.abs(sdf);
                    if (edge > sh.band) continue;
                    float raw = 1f - smooth(0, sh.band, edge);
                    float amount = (float) Math.pow(raw, 1f / Math.max(0.25f, sh.softness)) * sh.strength;
                    if (amount <= best) continue;
                    if (sh.surfaceWave) {
                        float wave = (float) Math.sin(x * 0.035f / unit + y * 0.022f / unit);
                        amount += wave * 2f * unit * (amount / sh.strength);
                    }
                    // Normale : gradient numérique de la SDF
                    float e = 1f;
                    float gx = sdf(px + e, py, sh.hw, sh.hh, sh.r) - sdf(px - e, py, sh.hw, sh.hh, sh.r);
                    float gy = sdf(px, py + e, sh.hw, sh.hh, sh.r) - sdf(px, py - e, sh.hw, sh.hh, sh.r);
                    float len = (float) Math.sqrt(gx * gx + gy * gy);
                    if (len < 0.0001f) continue;
                    best = amount;
                    nx = gx / len;
                    ny = gy / len;
                    chroma = (amount / sh.strength) * sh.chroma;
                    dir = sh.dir;
                }
                staticDx[i] = nx * best * dir;
                staticDy[i] = ny * best * dir;
                chromaX[i] = nx * chroma;
                chromaY[i] = ny * chroma;
            }
        }
    }

    private static final class Shape {
        final float cx, cy, hw, hh, r, band, strength, chroma, dir, softness;
        final boolean insideOnly, surfaceWave;

        Shape(float cx, float cy, float hw, float hh, float r, float band, float strength, float chroma,
              boolean insideOnly, float dir, boolean surfaceWave, float softness) {
            this.cx = cx;
            this.cy = cy;
            this.hw = hw;
            this.hh = hh;
            this.r = r;
            this.band = band;
            this.strength = strength;
            this.chroma = chroma;
            this.insideOnly = insideOnly;
            this.dir = dir;
            this.surfaceWave = surfaceWave;
            this.softness = softness;
        }
    }

    /** Distance signée à un rectangle arrondi centré. */
    private static float sdf(float px, float py, float hw, float hh, float r) {
        float qx = Math.abs(px) - hw + r, qy = Math.abs(py) - hh + r;
        float ox = Math.max(qx, 0), oy = Math.max(qy, 0);
        return (float) Math.sqrt(ox * ox + oy * oy) + Math.min(Math.max(qx, qy), 0) - r;
    }

    private static float smooth(float e0, float e1, float v) {
        float t = Math.max(0, Math.min(1, (v - e0) / (e1 - e0)));
        return t * t * (3 - 2 * t);
    }

    // ---------------------------------------------------------------- Animations

    /** Décalages animés d'une image : Eau calme, Liquide et Pluie cumulés, bornés à 18 px de référence. */
    private final class Anim {
        final float t;
        float ox, oy;
        // Paramètres de référence de Codex Glass, convertis en pixels de l'image réduite
        final float calmAmp, calmSpeed, calmScale, calmNoise;
        final float liqAmp, liqSpeed, liqScale, liqNoise, liqBoost;
        final float rainAmp, rainSpeed, rainRing, rainFade, rainNoise;
        final int drops;
        final float[] dropX, dropY, dropAge;

        Anim(float t) {
            this.t = t;
            calmAmp = s.calmOn ? 1.8f * unit * s.calmIntensity.f() : 0;
            calmSpeed = 0.72f * s.calmSpeed.f();
            calmScale = 6.2831853f / Math.max(1, 118f * unit * s.calmWavelength.f());
            calmNoise = 0.18f * unit * s.calmNoise.f();
            liqAmp = s.liquidOn ? 3.4f * unit * s.liquidIntensity.f() : 0;
            liqSpeed = 0.46f * s.liquidSpeed.f();
            liqScale = 6.2831853f / Math.max(1, 86f * unit * s.liquidWavelength.f());
            liqNoise = 0.36f * unit * s.liquidNoise.f();
            liqBoost = 1.15f + s.liquidFluidity.f() * 0.20f;
            rainAmp = s.rainOn ? 2.6f * unit * s.rainIntensity.f() : 0;
            rainSpeed = s.rainSpeed.f();
            rainRing = 58f * unit * s.rainRing.f();
            rainFade = s.rainFade.f();
            rainNoise = 0.12f * unit * s.rainIntensity.f();

            // Positions des gouttes calculées une fois par image (comme AnimatedRainOffset)
            drops = rainAmp > 0 ? Math.max(3, Math.min(28, Math.round(11f * s.rainDensity.f()))) : 0;
            dropX = new float[drops];
            dropY = new float[drops];
            dropAge = new float[drops];
            float time = t * rainSpeed;
            for (int k = 0; k < drops; k++) {
                float seed = k + 1;
                float cycle = time * (0.28f + hash(seed * 3.19f) * 0.18f) + hash(seed * 2.31f);
                float index = (float) Math.floor(cycle);
                dropAge[k] = cycle - index;
                dropX[k] = (0.08f + hash(seed * 5.47f + index * 11.17f) * 0.84f) * (w - 1);
                dropY[k] = (0.10f + hash(seed * 8.91f + index * 13.31f) * 0.80f) * (h - 1);
            }
        }

        void offset(float x, float y, float fade) {
            float dx = 0, dy = 0;
            if (calmAmp > 0) {
                float time = t * calmSpeed;
                float p = (float) Math.sin(x * calmScale + y * calmScale * 0.35f + time);
                float sc = (float) Math.sin(y * calmScale * 1.2f - time * 0.72f);
                float dg = (float) Math.sin((x + y) * calmScale * 0.62f + time * 0.48f);
                dx += (p * 0.72f + dg * 0.28f) * calmAmp;
                dy += (sc * 0.62f - dg * 0.24f) * calmAmp;
                float sh = (float) Math.sin(x * 0.113f / unit + y * 0.071f / unit + time * 1.37f);
                dx += sh * calmNoise;
                dy -= sh * calmNoise * 0.55f;
            }
            if (liqAmp > 0) {
                float time = t * liqSpeed;
                float p = (float) Math.sin(x * liqScale + y * liqScale * 0.35f + time);
                float sc = (float) Math.sin(y * liqScale * 0.83f - time * 0.72f);
                float dg = (float) Math.sin((x + y) * liqScale * 0.62f + time * 0.31f);
                dx += (p * 0.72f + dg * 0.28f) * liqAmp * liqBoost;
                dy += (sc * 0.62f - dg * 0.24f) * liqAmp * liqBoost;
                float sh = (float) Math.sin(x * 0.113f / unit + y * 0.071f / unit + time * 1.37f);
                dx += sh * liqNoise;
                dy -= sh * liqNoise * 0.55f;
            }
            if (drops > 0) {
                for (int k = 0; k < drops; k++) {
                    float ddx = x - dropX[k], ddy = y - dropY[k];
                    float dist = (float) Math.sqrt(ddx * ddx + ddy * ddy);
                    if (dist <= 0.001f) continue;
                    float age = dropAge[k];
                    float rad = 10f * unit + age * rainRing;
                    float band = 5.5f * unit + age * Math.max(3f * unit, rainRing * 0.086f);
                    float ringDist = Math.abs(dist - rad);
                    if (ringDist <= band) {
                        float ring = 1f - smooth(0, band, ringDist);
                        float decay = (1 - age) * (1 - age);
                        float amount = ring * decay * (float) Math.sin((dist - rad) * 0.62f / unit) * rainAmp * 2.1f;
                        dx += ddx / dist * amount;
                        dy += ddy / dist * amount;
                    }
                    float highlight = 1f - smooth(0, 5.5f * unit, dist);
                    if (highlight > 0) dy += highlight * rainAmp * 1.25f * (1f - smooth(0, 0.16f * rainFade, age));
                }
                float sh = (float) Math.sin(x * 0.097f / unit + y * 0.083f / unit + t * rainSpeed);
                dx += sh * rainNoise;
                dy -= sh * rainNoise * 0.45f;
            }
            float len = (float) Math.sqrt(dx * dx + dy * dy), max = 18f * unit;
            if (len > max) {
                dx *= max / len;
                dy *= max / len;
            }
            ox = dx * fade;
            oy = dy * fade;
        }
    }

    private static float hash(float seed) {
        float v = (float) (Math.sin(seed * 12.9898f) * 43758.5453f);
        return v - (float) Math.floor(v);
    }

    /** Lecture bilinéaire d'un canal (décalage 16 = rouge, 8 = vert, 0 = bleu). */
    private int channel(float x, float y, int shift) {
        x = Math.max(0, Math.min(w - 1, x));
        y = Math.max(0, Math.min(h - 1, y));
        int x0 = (int) x, y0 = (int) y;
        int x1 = Math.min(x0 + 1, w - 1), y1 = Math.min(y0 + 1, h - 1);
        float tx = x - x0, ty = y - y0;
        int[] p = blurred;
        float a = (p[y0 * w + x0] >> shift) & 0xff, b = (p[y0 * w + x1] >> shift) & 0xff;
        float c = (p[y1 * w + x0] >> shift) & 0xff, d = (p[y1 * w + x1] >> shift) & 0xff;
        float top = a + (b - a) * tx, bottom = c + (d - c) * tx;
        return Math.max(0, Math.min(255, Math.round(top + (bottom - top) * ty)));
    }
}
