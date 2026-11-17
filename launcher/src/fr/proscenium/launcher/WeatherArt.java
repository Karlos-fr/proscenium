package fr.proscenium.launcher;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * Icônes météo dessinées sur une grille de 24 × 24 (le canevas est déjà mis à l'échelle),
 * à l'instant {@code t} (secondes) : soleil à halo qui respire et rayons effilés qui tournent,
 * nuages en dégradé avec reflet, ombre et second nuage en arrière-plan, gouttes en larme qui
 * éclaboussent, cristaux de neige qui tournent, éclair qui illumine le nuage, brume vaporeuse,
 * lune à cratères et halo bleuté, étoiles qui scintillent.
 */
final class WeatherArt {
    private static final float TAU = (float) (Math.PI * 2);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Path p = new Path();
    private static Path cloudShape;

    WeatherArt() {
        fill.setStyle(Paint.Style.FILL);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
    }

    void draw(Canvas c, IconView.Kind kind, float t) {
        switch (kind) {
            case SUN: sun(c, t, 12, 12, 4.6f); break;
            case PARTLY_DAY: sun(c, t, 8.8f, 8.4f, 3.5f); front(c, t, 3.2f, false); break;
            case CLOUD: back(c, t, -3.6f, -2.6f, false); front(c, t, 1, false); break;
            case RAIN: drops(c, t); back(c, t, -3.4f, -5, true); front(c, t, -3, false); break;
            case SNOW: snow(c, t); back(c, t, -3.4f, -5, false); front(c, t, -3, false); break;
            case STORM: {
                float f = bolt(c, t);
                back(c, t, -3.4f, -5, true);
                front(c, t, -3, true);
                // Le flash éclaire le dessous du nuage
                if (f > 0) radial(c, 12.5f, 12.5f, 7, 0, argb(0.45f * f, 0xFFF0C8), 0x00FFF0C8);
                break;
            }
            case FOG: fog(c, t); break;
            case MOON:
                moon(c, t, 11, 12.5f, 6.6f);
                sparkle(c, t, 19, 5, 1.4f, 0);
                sparkle(c, t, 20.5f, 15, 0.9f, 0.4f);
                sparkle(c, t, 4.5f, 4.5f, 1, 0.7f);
                break;
            case PARTLY_NIGHT:
                moon(c, t, 8.6f, 8, 4.8f);
                sparkle(c, t, 17.5f, 3.8f, 1.2f, 0.3f);
                sparkle(c, t, 3.2f, 15, 0.8f, 0.8f);
                front(c, t, 3.2f, false);
                break;
            default: break;
        }
    }

    // ---------------------------------------------------------------- Soleil

    private void sun(Canvas c, float t, float x, float y, float rad) {
        float pulse = 0.5f + 0.5f * sin(t / 3f);
        // Le halo s'arrête avant le bord de l'icône : coupé net, il dessinerait un carré
        float halo = toEdge(x, y, rad * 2.6f + pulse * 0.8f);
        radial(c, x, y, halo, rad * 0.6f / halo, 0x8CFFC850, 0x00FFAA28);
        // Douze rayons effilés, longs et courts en alternance, qui tournent (un tour en 30 s)
        c.save();
        c.translate(x, y);
        c.rotate(t / 30f * 360f);
        for (int i = 0; i < 12; i++) {
            c.rotate(30f);
            float len = rad + (i % 2 == 0 ? 3.6f + pulse * 0.6f : 2.4f);
            fill.setColor(0xFF000000); // opaque : le dégradé garde ses propres transparences
            fill.setShader(new LinearGradient(0, -rad - 0.8f, 0, -len, 0xF2FFD666, 0x00FFAA28, Shader.TileMode.CLAMP));
            p.reset();
            p.moveTo(-0.55f, -rad - 0.6f);
            p.lineTo(0, -len);
            p.lineTo(0.55f, -rad - 0.6f);
            p.close();
            c.drawPath(p, fill);
        }
        fill.setShader(null);
        c.restore();
        // Cœur : blanc chaud au centre, orange au bord
        fill.setColor(0xFF000000); // opaque : le dégradé garde ses propres transparences
        fill.setShader(new RadialGradient(x - rad * 0.35f, y - rad * 0.35f, rad * 1.15f,
                new int[]{0xFFFFF8D6, 0xFFFFD24A, 0xFFFF9A1F}, new float[]{0, 0.45f, 1}, Shader.TileMode.CLAMP));
        c.drawCircle(x, y, rad, fill);
        fill.setShader(null);
    }

    // ---------------------------------------------------------------- Nuages

    private static Path cloudShape() {
        if (cloudShape == null) {
            Path a = new Path();
            a.addCircle(8.5f, 14, 3.8f, Path.Direction.CW);
            Path b = new Path();
            b.addCircle(12.5f, 11, 4.8f, Path.Direction.CW);
            a.op(b, Path.Op.UNION);
            b.reset();
            b.addCircle(16.8f, 14.3f, 3.5f, Path.Direction.CW);
            a.op(b, Path.Op.UNION);
            b.reset();
            b.addRect(8.5f, 13, 16.8f, 17.8f, Path.Direction.CW);
            a.op(b, Path.Op.UNION);
            cloudShape = a;
        }
        return cloudShape;
    }

    private void cloud(Canvas c, float dx, float dy, float s, int top, int bottom, boolean shadow) {
        c.save();
        c.translate(dx, dy);
        c.scale(s, s, 12.6f, 14);
        if (shadow) {
            fill.setColor(0x47000000);
            r.set(12.8f - 7.5f, 18.6f - 1.3f, 12.8f + 7.5f, 18.6f + 1.3f);
            c.drawOval(r, fill);
        }
        fill.setColor(0xFF000000); // opaque : le dégradé garde ses propres transparences
        fill.setShader(new LinearGradient(0, 6, 0, 18, top, bottom, Shader.TileMode.CLAMP));
        c.drawPath(cloudShape(), fill);
        fill.setShader(null);
        // Reflet sur le dessus
        fill.setColor(0x8CFFFFFF);
        c.save();
        c.rotate(-20, 11.2f, 8.9f);
        r.set(11.2f - 2.4f, 8.9f - 0.9f, 11.2f + 2.4f, 8.9f + 0.9f);
        c.drawOval(r, fill);
        c.restore();
        c.restore();
    }

    /** Nuage de devant : il dérive lentement (cycle de 7 s). */
    private void front(Canvas c, float t, float dy, boolean dark) {
        cloud(c, sin(t / 7f) * 0.9f, dy, 1, dark ? 0xFF8090A8 : 0xFFFFFFFF, dark ? 0xFF3E4A5E : 0xFFC4D2E8, true);
    }

    /** Nuage du fond, plus petit et plus sombre, qui dérive en sens inverse : de la profondeur. */
    private void back(Canvas c, float t, float dx, float dy, boolean dark) {
        cloud(c, dx - sin(t / 9f) * 1.2f, dy, 0.72f, dark ? 0xFF5C6A80 : 0xFFAFC0DA, dark ? 0xFF2E3747 : 0xFF7E92B2, false);
    }

    // ---------------------------------------------------------------- Pluie, neige, orage, brume

    /** Gouttes en larme qui tombent en biais et éclaboussent en touchant le sol. */
    private void drops(Canvas c, float t) {
        for (int i = 0; i < 4; i++) {
            float per = 0.85f + i * 0.07f;
            float q = frac(t / per + i * 0.29f);
            float x = 7.4f + i * 3.2f - q * 1.2f, y = 15 + q * 7.5f;
            float a = Math.min(1, Math.min(q * 5, (1 - q) * 3));
            fill.setColor(0xFF000000); // opaque : le dégradé garde ses propres transparences
            fill.setShader(new LinearGradient(x, y - 2, x, y + 1.2f, 0x00BEE6FF, 0xFF5BB6FF, Shader.TileMode.CLAMP));
            fill.setAlpha(Math.round(255 * a));
            p.reset();
            p.moveTo(x + 0.35f, y - 2.4f);
            p.quadTo(x + 1, y + 0.4f, x, y + 0.9f);
            p.quadTo(x - 1, y + 0.4f, x + 0.35f, y - 2.4f);
            c.drawPath(p, fill);
            fill.setShader(null);
            fill.setAlpha(255);
            if (q > 0.82f) {
                float s = (q - 0.82f) / 0.18f;
                line.setColor(0xFF9FD3FF);
                line.setAlpha(Math.round(255 * (1 - s) * 0.8f));
                line.setStrokeWidth(0.35f);
                r.set(x - 0.6f - s * 1.6f, 22.6f - 0.25f - s * 0.4f, x + 0.6f + s * 1.6f, 22.6f + 0.25f + s * 0.4f);
                c.drawOval(r, line);
                line.setAlpha(255);
            }
        }
    }

    /** Cristaux à six branches qui tournent en tombant et oscillent. */
    private void snow(Canvas c, float t) {
        for (int i = 0; i < 4; i++) {
            float per = 2.6f + i * 0.4f;
            float q = frac(t / per + i * 0.31f);
            float x = 7.8f + i * 3 + sin(t / 1.8f + i) * 0.8f, y = 14.5f + q * 8;
            float a = Math.min(1, Math.min(q * 4, (1 - q) * 3));
            flake(c, x, y, i % 2 == 1 ? 1.15f : 1.5f, t * (i % 2 == 1 ? 1.2f : -0.9f), a);
        }
    }

    private void flake(Canvas c, float x, float y, float rad, float rot, float a) {
        c.save();
        c.translate(x, y);
        c.rotate((float) Math.toDegrees(rot));
        line.setColor(0xFFFFFFFF);
        line.setAlpha(Math.round(255 * a));
        line.setStrokeWidth(0.38f);
        for (int i = 0; i < 6; i++) {
            c.rotate(60);
            c.drawLine(0, 0, 0, -rad, line);
            c.drawLine(0, -rad * 0.55f, -rad * 0.28f, -rad * 0.8f, line);
            c.drawLine(0, -rad * 0.55f, rad * 0.28f, -rad * 0.8f, line);
        }
        fill.setColor(0xFFFFFFFF);
        fill.setAlpha(Math.round(255 * a));
        c.drawCircle(0, 0, rad * 0.18f, fill);
        fill.setAlpha(255);
        line.setAlpha(255);
        c.restore();
    }

    /** Éclair : deux flashs toutes les 3,5 s, avec une lueur ; renvoie l'intensité du flash. */
    private float bolt(Canvas c, float t) {
        float q = t % 3.5f;
        float f = q < 0.1f ? 1f : (q > 0.22f && q < 0.36f ? 0.8f : 0f);
        if (f > 0) {
            radial(c, 12, 17, 9, 0, argb(0.55f * f, 0xFFF0B4), 0x00FFF0B4);
            radial(c, 12.6f, 19, 4.5f, 0, argb(0.9f * f, 0xFFE178), 0x00FFE178);
        }
        fill.setColor(0xFF000000); // opaque : le dégradé garde ses propres transparences
        fill.setShader(new LinearGradient(12, 15, 12, 23, 0xFFFFFBE0, 0xFFFFB520, Shader.TileMode.CLAMP));
        fill.setAlpha(f > 0 ? 255 : 140);
        p.reset();
        p.moveTo(13.4f, 15.2f);
        p.lineTo(10.2f, 19.6f);
        p.lineTo(12.6f, 19.6f);
        p.lineTo(11, 23.4f);
        p.lineTo(15.4f, 18.1f);
        p.lineTo(12.9f, 18.1f);
        p.close();
        c.drawPath(p, fill);
        fill.setShader(null);
        fill.setAlpha(255);
        return f;
    }

    /** Trois bandes vaporeuses aux bords fondus, qui glissent en sens opposés. */
    private void fog(Canvas c, float t) {
        float[][] bands = {{8.5f, 1, 4, 20}, {12.5f, -1, 6, 18}, {16.5f, 0.6f, 4, 20}};
        for (float[] b : bands) {
            float d = sin(t / 5f) * 1.6f * b[1];
            fill.setColor(0xFF000000); // opaque : le dégradé garde ses propres transparences
            fill.setShader(new LinearGradient(b[2] + d, 0, b[3] + d, 0,
                    new int[]{0x00DCE6F5, 0xE6DCE6F5, 0xE6DCE6F5, 0x00DCE6F5}, new float[]{0, 0.3f, 0.7f, 1},
                    Shader.TileMode.CLAMP));
            r.set(b[2] + d - 1, b[0] - 0.9f, b[3] + d + 1, b[0] + 0.9f);
            c.drawRoundRect(r, 0.9f, 0.9f, fill);
            fill.setShader(null);
        }
    }

    // ---------------------------------------------------------------- Nuit

    /** Croissant de lune en dégradé, avec cratères et halo bleuté qui respire. */
    private void moon(Canvas c, float t, float x, float y, float rad) {
        float pulse = 0.5f + 0.5f * sin(t / 4f);
        radial(c, x, y, toEdge(x, y, rad * 2.2f), 0.36f, argb(0.28f + 0.12f * pulse, 0xBED2FF), 0x00BED2FF);
        // Disque moins la morsure (Path.op) : le croissant ne déborde jamais du disque
        p.reset();
        p.addCircle(x, y, rad, Path.Direction.CW);
        Path bite = new Path();
        bite.addCircle(x + rad * 0.55f, y - rad * 0.4f, rad * 0.85f, Path.Direction.CW);
        p.op(bite, Path.Op.DIFFERENCE);
        c.save();
        c.clipPath(p);
        fill.setColor(0xFF000000); // opaque : le dégradé garde ses propres transparences
        fill.setShader(new RadialGradient(x - rad * 0.4f, y + rad * 0.3f, rad * 1.3f,
                0xFFFFFFFF, 0xFFBFCBEA, Shader.TileMode.CLAMP));
        c.drawCircle(x, y, rad, fill);
        fill.setShader(null);
        fill.setColor(0x7396A5CD);
        c.drawCircle(x - 0.45f * rad, y + 0.25f * rad, 0.18f * rad, fill);
        c.drawCircle(x - 0.1f * rad, y + 0.6f * rad, 0.12f * rad, fill);
        c.drawCircle(x - 0.62f * rad, y - 0.2f * rad, 0.1f * rad, fill);
        c.restore();
    }

    /** Étoile à quatre branches qui apparaît, scintille en tournant, puis s'éteint. */
    private void sparkle(Canvas c, float t, float x, float y, float s, float phase) {
        float a = Math.max(0, sin(t / 2.4f + phase));
        if (a < 0.02f) return;
        float k = s * (0.5f + 0.7f * a);
        fill.setColor(0xFFFFFFFF);
        fill.setAlpha(Math.round(255 * a));
        c.save();
        c.translate(x, y);
        c.rotate((float) Math.toDegrees(t * 0.6f + phase));
        for (int i = 0; i < 4; i++) {
            c.rotate(90);
            p.reset();
            p.moveTo(0, -k);
            p.quadTo(0.12f * k, -0.12f * k, 0, 0);
            p.quadTo(-0.12f * k, -0.12f * k, 0, -k);
            c.drawPath(p, fill);
        }
        c.drawCircle(0, 0, k * 0.18f, fill);
        fill.setAlpha(255);
        c.restore();
    }

    // ---------------------------------------------------------------- Aides

    /** Disque en dégradé radial, du centre (couleur {@code from}, opaque jusqu'à {@code hold}) au bord. */
    private void radial(Canvas c, float x, float y, float rad, float hold, int from, int to) {
        rad = toEdge(x, y, rad);
        fill.setColor(0xFF000000); // opaque : le dégradé garde ses propres transparences
        fill.setShader(new RadialGradient(x, y, rad, new int[]{from, from, to},
                new float[]{0, Math.max(0, Math.min(0.99f, hold)), 1}, Shader.TileMode.CLAMP));
        c.drawCircle(x, y, rad, fill);
        fill.setShader(null);
    }

    /** Rayon ramené à la distance du bord de l'icône le plus proche. */
    private static float toEdge(float x, float y, float rad) {
        return Math.min(rad, Math.min(Math.min(x, y), Math.min(24 - x, 24 - y)));
    }

    private static int argb(float alpha, int rgb) {
        return (Math.round(Math.max(0, Math.min(1, alpha)) * 255) << 24) | (rgb & 0xFFFFFF);
    }

    private static float sin(float turns) {
        return (float) Math.sin(turns * TAU);
    }

    private static float frac(float v) {
        return v - (float) Math.floor(v);
    }
}
