package fr.proscenium.launcher;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RecordingCanvas;
import android.graphics.RectF;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;

/**
 * Verre réfractif calculé par la puce graphique (Android 13 et plus) : mêmes formules que GlassEngine
 * (Codex Glass), écrites en AGSL et exécutées pour chaque pixel du dock, à pleine résolution et à
 * chaque image, en lisant directement la photo du fond. Aucune copie d'écran, aucun calcul sur le
 * processeur, hors la position des gouttes de pluie (une fois par image).
 * Les longueurs de référence de Codex Glass sont converties avec {@code unit} = densité de l'écran.
 */
final class GpuGlass {
    private static final int MAX_DROPS = 28;

    private static final String SRC = String.join("\n",
            "uniform shader image;",
            "uniform float2 size;",
            "uniform float unit;",
            "uniform float blurRadius;",
            "uniform float4 frame;",       // rayon, bande, force, aberration
            "uniform float4 lensRect;",    // centre x, centre y, demi-largeur, demi-hauteur
            "uniform float4 lensParams;",  // rayon, bande, force, aberration
            "uniform float2 lensExtra;",   // sens, douceur
            "uniform float time;",
            "uniform float animOn;",
            "uniform float4 calm;",        // amplitude, vitesse, échelle, bruit
            "uniform float4 liquid;",      // amplitude, vitesse, échelle, bruit
            "uniform float liqBoost;",
            "uniform float4 rain;",        // amplitude, vitesse, anneau, extinction
            "uniform float rainNoise;",
            "uniform int dropCount;",
            "uniform float3 drops[" + MAX_DROPS + "];",

            // Distance signée à un rectangle arrondi centré
            "float sdf(float2 p, float2 hs, float r) {",
            "  float2 q = abs(p) - hs + r;",
            "  return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;",
            "}",

            // Bord réfractif d'une forme : garde la plus forte déviation (comme buildStatic)
            "void evalShape(float2 xy, float2 c, float2 hs, float r, float band, float strength, float chroma,",
            "               float insideOnly, float dir, float wave, float softness,",
            "               inout float best, inout float2 n, inout float ch, inout float dr) {",
            "  if (strength <= 0.0001 || band <= 0.0) return;",
            "  float2 p = xy - c;",
            "  float d = sdf(p, hs, r);",
            "  float edge = insideOnly > 0.5 ? max(0.0, -d) : abs(d);",
            "  if (edge > band) return;",
            "  float raw = 1.0 - smoothstep(0.0, band, edge);",
            "  float amount = pow(raw, 1.0 / max(0.25, softness)) * strength;",
            "  if (amount <= best) return;",
            "  if (wave > 0.5) {",
            "    float w = sin(xy.x * 0.035 / unit + xy.y * 0.022 / unit);",
            "    amount += w * 2.0 * unit * (amount / strength);",
            "  }",
            "  float gx = sdf(p + float2(1.0, 0.0), hs, r) - sdf(p - float2(1.0, 0.0), hs, r);",
            "  float gy = sdf(p + float2(0.0, 1.0), hs, r) - sdf(p - float2(0.0, 1.0), hs, r);",
            "  float len = sqrt(gx * gx + gy * gy);",
            "  if (len < 0.0001) return;",
            "  best = amount;",
            "  n = float2(gx, gy) / len;",
            "  ch = (amount / strength) * chroma;",
            "  dr = dir;",
            "}",

            // Eau calme, Liquide et Pluie cumulés, bornés à 18 px de référence (comme Anim.offset)
            "float2 animOffset(float2 p) {",
            "  float dx = 0.0; float dy = 0.0;",
            "  if (calm.x > 0.0) {",
            "    float t = time * calm.y; float sc = calm.z;",
            "    float pp = sin(p.x * sc + p.y * sc * 0.35 + t);",
            "    float s2 = sin(p.y * sc * 1.2 - t * 0.72);",
            "    float dg = sin((p.x + p.y) * sc * 0.62 + t * 0.48);",
            "    dx += (pp * 0.72 + dg * 0.28) * calm.x;",
            "    dy += (s2 * 0.62 - dg * 0.24) * calm.x;",
            "    float sh = sin(p.x * 0.113 / unit + p.y * 0.071 / unit + t * 1.37);",
            "    dx += sh * calm.w; dy -= sh * calm.w * 0.55;",
            "  }",
            "  if (liquid.x > 0.0) {",
            "    float t = time * liquid.y; float sc = liquid.z;",
            "    float pp = sin(p.x * sc + p.y * sc * 0.35 + t);",
            "    float s2 = sin(p.y * sc * 0.83 - t * 0.72);",
            "    float dg = sin((p.x + p.y) * sc * 0.62 + t * 0.31);",
            "    dx += (pp * 0.72 + dg * 0.28) * liquid.x * liqBoost;",
            "    dy += (s2 * 0.62 - dg * 0.24) * liquid.x * liqBoost;",
            "    float sh = sin(p.x * 0.113 / unit + p.y * 0.071 / unit + t * 1.37);",
            "    dx += sh * liquid.w; dy -= sh * liquid.w * 0.55;",
            "  }",
            "  if (dropCount > 0) {",
            "    for (int k = 0; k < " + MAX_DROPS + "; k++) {",
            "      if (k >= dropCount) break;",
            "      float3 d = drops[k];",
            "      float2 dd = p - d.xy;",
            "      float dist = length(dd);",
            "      if (dist > 0.001) {",
            "        float age = d.z;",
            "        float rad = 10.0 * unit + age * rain.z;",
            "        float band = 5.5 * unit + age * max(3.0 * unit, rain.z * 0.086);",
            "        float rd = abs(dist - rad);",
            "        if (rd <= band) {",
            "          float ring = 1.0 - smoothstep(0.0, band, rd);",
            "          float decay = (1.0 - age) * (1.0 - age);",
            "          float amount = ring * decay * sin((dist - rad) * 0.62 / unit) * rain.x * 2.1;",
            "          dx += dd.x / dist * amount; dy += dd.y / dist * amount;",
            "        }",
            "        float hl = 1.0 - smoothstep(0.0, 5.5 * unit, dist);",
            "        if (hl > 0.0) dy += hl * rain.x * 1.25 * (1.0 - smoothstep(0.0, 0.16 * rain.w, age));",
            "      }",
            "    }",
            "    float sh = sin(p.x * 0.097 / unit + p.y * 0.083 / unit + time * rain.y);",
            "    dx += sh * rainNoise; dy -= sh * rainNoise * 0.45;",
            "  }",
            "  float2 o = float2(dx, dy);",
            "  float len = length(o); float mx = 18.0 * unit;",
            "  if (len > mx) o *= mx / len;",
            "  return o;",
            "}",

            // Diffusion : petit flou à 7 points (centre + hexagone), à la place du flou de boîte du processeur
            "half4 blurAt(float2 p) {",
            "  if (blurRadius < 0.5) return image.eval(p);",
            "  float r = blurRadius;",
            "  half4 acc = image.eval(p) * 0.22;",
            "  acc += image.eval(p + float2( r, 0.0)) * 0.13;",
            "  acc += image.eval(p + float2(-r, 0.0)) * 0.13;",
            "  acc += image.eval(p + float2( 0.5 * r,  0.866 * r)) * 0.13;",
            "  acc += image.eval(p + float2(-0.5 * r,  0.866 * r)) * 0.13;",
            "  acc += image.eval(p + float2( 0.5 * r, -0.866 * r)) * 0.13;",
            "  acc += image.eval(p + float2(-0.5 * r, -0.866 * r)) * 0.13;",
            "  return acc;",
            "}",

            "half4 main(float2 xy) {",
            "  float2 c = size * 0.5;",
            "  float best = 0.0; float2 n = float2(0.0); float ch = 0.0; float dr = -1.0;",
            "  float fr = min(frame.x, min(c.x, c.y) * 0.42);",
            // Bord du panneau : réfraction vers l'intérieur, avec ondulation de surface
            "  evalShape(xy, c, c, fr, frame.y, frame.z, frame.w, 1.0, -1.0, 1.0, 1.0, best, n, ch, dr);",
            // Lentille fixe au centre, là où passe la tuile sélectionnée
            "  evalShape(xy, lensRect.xy, lensRect.zw, lensParams.x, lensParams.y, lensParams.z, lensParams.w,",
            "            0.0, lensExtra.x, 0.0, lensExtra.y, best, n, ch, dr);",
            "  float2 off = n * best * dr;",
            "  float2 chroma = n * ch;",
            "  if (animOn > 0.5) {",
            "    float e = min(min(xy.x, size.x - xy.x), min(xy.y, size.y - xy.y));",
            "    off += animOffset(xy) * smoothstep(0.0, 18.0 * unit, e);",
            "  }",
            "  float2 s = xy + off;",
            // Sans aberration (l'essentiel du verre), une seule lecture floutée suffit
            "  if (dot(chroma, chroma) < 0.0625) return half4(blurAt(s).rgb, 1.0);",
            "  return half4(blurAt(s - chroma).r, blurAt(s).g, blurAt(s + chroma).b, 1.0);",
            "}");

    private final RuntimeShader shader = new RuntimeShader(SRC);
    private final GlassSettings s;
    private final float unit;
    private final Matrix local = new Matrix();
    private final float[] drops = new float[MAX_DROPS * 3];
    /** Copie de la photo réduite de moitié (960 × 540) : assez de détail pour les bords réfractifs. */
    private static final int PHOTO_REDUCTION = 2;
    private Bitmap lastBitmap, small;
    private BitmapShader bitmapShader;

    GpuGlass(GlassSettings settings, float density) {
        s = settings;
        unit = density;
    }

    /**
     * Dessine le verre dans {@code box} (coordonnées du dock). {@code toDock} place la photo du fond
     * dans ces coordonnées ; {@code lens} est la tuile du centre ; {@code paint} porte les filtres du verre.
     */
    void draw(Canvas c, RectF box, Paint paint, Bitmap photo, Matrix toDock, RectF lens, float cornerRadius,
              float timeSec, boolean animate) {
        if (photo != lastBitmap) {
            // Copie réduite, faite une fois par fond : le verre est flou, une image plus petite suffit
            // et la puce graphique la lit plus vite
            lastBitmap = photo;
            small = Bitmap.createScaledBitmap(photo, Math.max(1, photo.getWidth() / PHOTO_REDUCTION),
                    Math.max(1, photo.getHeight() / PHOTO_REDUCTION), true);
            bitmapShader = new BitmapShader(small, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            // Lecture lissée (bilinéaire) : sinon la puce graphique prend le pixel le plus proche
            // et la copie réduite apparaît en petits carrés
            bitmapShader.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
        }
        // Copie réduite → photo d'origine → dock
        local.setScale(photo.getWidth() / (float) small.getWidth(), photo.getHeight() / (float) small.getHeight());
        local.postConcat(toDock);
        bitmapShader.setLocalMatrix(local);
        shader.setInputShader("image", bitmapShader);

        float w = box.width(), h = box.height();
        shader.setFloatUniform("size", w, h);
        shader.setFloatUniform("unit", unit);
        // Même flou que le processeur : rayon 1,6 px au quart de la résolution, soit 6,4 px ici
        shader.setFloatUniform("blurRadius", 6.4f * s.diffusion.f());
        shader.setFloatUniform("frame", cornerRadius, 58f * unit * s.edgeWidth.f(),
                18f * unit * s.edgeRefraction.f(), 1.35f * unit * s.chromatic.f());

        float strength = 8f * unit * s.lensRefraction.f();
        if (lens != null && lens.width() > 2 && lens.height() > 2) {
            float hw = lens.width() / 2, hh = lens.height() / 2;
            shader.setFloatUniform("lensRect", lens.centerX(), lens.centerY(), hw, hh);
            shader.setFloatUniform("lensParams", Math.min(14f * unit, Math.min(hw, hh)),
                    12f * unit * s.lensWidth.f(), Math.abs(strength), 0.85f * unit * s.chromatic.f());
        } else {
            shader.setFloatUniform("lensRect", 0, 0, 0, 0);
            shader.setFloatUniform("lensParams", 0, 0, 0, 0);
        }
        shader.setFloatUniform("lensExtra", strength > 0 ? -1f : 1f, s.lensSoftness.f());

        shader.setFloatUniform("time", timeSec);
        shader.setFloatUniform("animOn", animate ? 1f : 0f);
        float calmAmp = animate && s.calmOn ? 1.8f * unit * s.calmIntensity.f() : 0;
        shader.setFloatUniform("calm", calmAmp, 0.72f * s.calmSpeed.f(),
                6.2831853f / Math.max(1, 118f * unit * s.calmWavelength.f()), 0.18f * unit * s.calmNoise.f());
        float liqAmp = animate && s.liquidOn ? 3.4f * unit * s.liquidIntensity.f() : 0;
        shader.setFloatUniform("liquid", liqAmp, 0.46f * s.liquidSpeed.f(),
                6.2831853f / Math.max(1, 86f * unit * s.liquidWavelength.f()), 0.36f * unit * s.liquidNoise.f());
        shader.setFloatUniform("liqBoost", 1.15f + s.liquidFluidity.f() * 0.20f);
        float rainAmp = animate && s.rainOn ? 2.6f * unit * s.rainIntensity.f() : 0;
        float rainSpeed = s.rainSpeed.f();
        shader.setFloatUniform("rain", rainAmp, rainSpeed, 58f * unit * s.rainRing.f(), s.rainFade.f());
        shader.setFloatUniform("rainNoise", 0.12f * unit * s.rainIntensity.f());

        // Gouttes : mêmes positions pseudo-aléatoires que GlassEngine.Anim
        int count = rainAmp > 0 ? Math.max(3, Math.min(MAX_DROPS, Math.round(11f * s.rainDensity.f()))) : 0;
        float time = timeSec * rainSpeed;
        for (int k = 0; k < count; k++) {
            float seed = k + 1;
            float cycle = time * (0.28f + hash(seed * 3.19f) * 0.18f) + hash(seed * 2.31f);
            float index = (float) Math.floor(cycle);
            drops[k * 3] = (0.08f + hash(seed * 5.47f + index * 11.17f) * 0.84f) * w;
            drops[k * 3 + 1] = (0.10f + hash(seed * 8.91f + index * 13.31f) * 0.80f) * h;
            drops[k * 3 + 2] = cycle - index;
        }
        shader.setIntUniform("dropCount", count);
        shader.setFloatUniform("drops", drops);

        // Le verre est flou par nature : calculé à mi-résolution dans un calque, puis agrandi avec lissage.
        // Quatre fois moins de pixels pour la puce graphique, sans différence visible.
        int lw = (int) Math.ceil(w / SCALE), lh = (int) Math.ceil(h / SCALE);
        layer.setPosition(0, 0, lw, lh);
        RecordingCanvas rc = layer.beginRecording(lw, lh);
        rc.scale(1f / SCALE, 1f / SCALE);
        Shader previous = paint.getShader();
        paint.setShader(shader);
        rc.drawRect(box, paint);
        paint.setShader(previous);
        layer.endRecording();
        c.save();
        c.scale(SCALE, SCALE);
        c.drawRenderNode(layer);
        c.restore();
    }

    /** Facteur de réduction du calcul (2 = mi-résolution). */
    private static final float SCALE = 2f;
    private final RenderNode layer = new RenderNode("verre");

    {
        // Calque hors écran à sa propre taille : c'est lui qui fixe la résolution du calcul
        layer.setUseCompositingLayer(true, null);
    }

    private static float hash(float seed) {
        float v = (float) (Math.sin(seed * 12.9898f) * 43758.5453f);
        return v - (float) Math.floor(v);
    }
}
