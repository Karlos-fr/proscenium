package fr.proscenium.launcher;

import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RecordingCanvas;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;

/**
 * Météo vivante calculée par la puce graphique (Android 13 et plus, AGSL). Deux programmes :
 * <ul>
 * <li>l'air : neige en trois couches, brume qui dérive. Calculé à
 * mi-résolution (tout y est flou), éclairé par l'image du film ;</li>
 * <li>les gouttes et flocons posés sur l'objectif, un dessin par goutte, à pleine résolution. Repris de la
 * recomp de Fun Tracks (render/ft_render_webgpu_lens.c) : une goutte collée à l'objectif est bien trop
 * près pour être nette, c'est une tache d'eau molle où la scène est renversée vers son centre (lentille
 * d'eau), floutée, avec une frange de couleur, un bord à peine assombri et la lumière concentrée en bas.</li>
 * </ul>
 * L'image du film arrive par {@code image} : copie réduite de la photo du fond, placée à l'écran.
 */
final class WeatherGpu {
    /** Facteur de réduction du calcul de l'air (2 = mi-résolution). */
    static final float SCALE = 2f;
    /** Brume seule (pluie, orage, brouillard) : douce, calculée au quart de la résolution. */
    static final float FOG_SCALE = 4f;

    private static final String AIR = String.join("\n",
            "uniform shader image;",
            "uniform float2 size;",
            "uniform float time;",
            "uniform float kind;",     // 1 pluie, 2 orage, 3 neige, 4 brouillard
            "uniform float amount;",
            "uniform float drift;",    // dérive de la neige, en px/s
            "uniform float unit;",     // densité de l'écran
            "uniform float px;",       // taille d'un pixel calculé, en px de l'écran
            "uniform float flash;",    // éclair (0 à 1)
            "uniform float fog;",      // force de la brume
            "uniform float snowSize;", // taille des flocons (1 : référence)
            "uniform float snowSpeed;",// vitesse de chute des flocons
            "uniform float fogSpeed;", // vitesse de dérive de la brume
            "uniform float fogLow;",   // brume plus dense en bas (0 : partout pareille)

            "float h11(float p) { p = fract(p * 0.1031); p *= p + 33.33; p *= p + p; return fract(p); }",
            "float h21(float2 p) { float3 q = fract(float3(p.xyx) * 0.1031); q += dot(q, q.yzx + 33.33); return fract((q.x + q.y) * q.z); }",

            "float vnoise(float2 p) {",
            "  float2 i = floor(p); float2 f = fract(p);",
            "  f = f * f * (3.0 - 2.0 * f);",
            "  float a = h21(i); float b = h21(i + float2(1.0, 0.0));",
            "  float c = h21(i + float2(0.0, 1.0)); float d = h21(i + float2(1.0, 1.0));",
            "  return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);",
            "}",
            "float fbm(float2 p) {",
            "  float v = 0.0; float a = 0.5;",
            "  for (int i = 0; i < 3; i++) { v += a * vnoise(p); p = p * 2.03 + float2(17.1, 9.3); a *= 0.5; }",
            "  return v;",
            "}",

            // Une couche de neige : un flocon au plus par case, qui tombe en se balançant
            "float flake(float2 xy, float cell, float speed, float radius, float soft, float density, float seed) {",
            "  float2 p = xy;",
            "  speed *= snowSpeed;",
            "  p.y -= time * speed;",
            "  p.x -= time * drift * speed / (70.0 * unit);",
            "  float2 id = floor(p / cell);",
            "  float r = h21(id + seed);",
            "  if (r > density) return 0.0;",
            "  float2 c = (id + 0.3 + 0.4 * float2(h11(r * 31.1 + seed), h11(r * 57.7 + seed))) * cell;",
            "  c.x += sin(time * (0.7 + r) + r * 40.0) * cell * 0.15;",
            "  float rad = max(min(radius * snowSize * (0.6 + 0.6 * h11(r * 7.3)), cell * 0.13), px * 0.9);",
            "  float d = length(p - c) / rad;",
            "  if (d >= 1.0) return 0.0;",
            "  return (1.0 - smoothstep(soft, 1.0, d)) * (0.55 + 0.45 * h11(r * 3.7)) * min(1.0, radius / rad * 1.5);",
            "}",

            "half4 main(float2 xy) {",
            "  float3 scene = float3(image.eval(xy).rgb);",
            "  float lum = dot(scene, float3(0.299, 0.587, 0.114));",
            "  float3 col = float3(0.0); float a = 0.0;",
            // Brume : deux nappes qui dérivent à des vitesses différentes, plus denses vers le bas
            "  if (fog > 0.0) {",
            "    float2 q = xy / (420.0 * unit);",
            "    float ft = time * fogSpeed;",
            "    float n = fbm(q + float2(ft * 0.018, ft * 0.004)) * 0.6 + fbm(q * 1.9 - float2(ft * 0.031, 0.0)) * 0.4;",
            "    float lowAmt = clamp(0.65 * fogLow, 0.0, 1.0);",
            "    float low = 1.0 - lowAmt + lowAmt * smoothstep(0.1, 1.0, xy.y / size.y);",
            "    float fa = clamp(fog * low * smoothstep(0.22, 0.8, n), 0.0, 0.85);",
            "    float3 fc = mix(float3(0.74, 0.77, 0.80), scene + 0.25, 0.35) * (0.55 + 0.6 * lum) + flash * 0.8;",
            "    col = fc * fa; a = fa;",
            "  }",
            // Neige : petits flocons nets au loin, gros flocons flous tout près (hors de la mise au point)
            "  if (kind > 2.5 && kind < 3.5) {",
            "    float s1 = flake(xy, 22.0 * unit, 34.0 * unit, 1.3 * unit, 0.15, 0.6 * amount, 3.0);",
            "    float s2 = flake(xy, 62.0 * unit, 70.0 * unit, 3.2 * unit, 0.3, 0.5 * amount, 11.0);",
            "    float s3 = flake(xy, 200.0 * unit, 150.0 * unit, 14.0 * unit, 0.45, 0.32 * amount, 23.0);",
            "    float3 white = mix(float3(0.92, 0.94, 0.98), scene + 0.3, 0.25) * (0.7 + 0.45 * lum) + flash * 0.5;",
            "    float sa = min(s1 * 0.6 + s2 * 0.8 + s3 * 0.32, 1.0);",
            "    col = col * (1.0 - sa) + white * sa;",
            "    a = a * (1.0 - sa) + sa * 0.9;",
            "  }",
            "  return half4(half3(col), half(a));",
            "}");

    /**
     * Une goutte d'eau sur la vitre, nette : une demi-sphère d'eau est une lentille très courbe qui
     * montre, renversée et réduite, une large part de la scène derrière elle. Un fin bord sombre (la
     * lumière y est renvoyée de côté), la lumière concentrée en bas, un reflet vif en haut. Une goutte
     * qui glisse a la tête ronde en bas et s'affine vers le haut. Un flocon est une tache blanche givrée
     * qui devient eau en fondant. Une traînée (look.w = 2) est la mince pellicule d'eau qu'une goutte
     * laisse en glissant : à peine bombée, elle décale un peu l'image, l'éclaircit, et ses bords
     * accrochent la lumière ; ses morceaux se chevauchent en une trace continue qui sèche.
     */
    private static final String DROP = String.join("\n",
            "uniform shader image;",
            "uniform float2 center;",
            "uniform float2 size;",
            "uniform float4 look;",   // x : neige ; y : opacité ; z : forme ; w : glissement (0 à 1), ou 2 : traînée
            "uniform float blur;",    // mise au point à l'arrivée : 1 flou, 0 net
            "uniform float2 axis;",   // sens de la glisse (sinus, cosinus de l'angle depuis la verticale)
            // Repère de la goutte (axe y dans le sens de la glisse) → écran
            "float2 toScreen(float2 u) { return float2(u.x * axis.y + u.y * axis.x, -u.x * axis.x + u.y * axis.y); }",

            "half4 main(float2 q) {",
            "  float2 dv = q - center;",
            "  float2 local = float2(dv.x * axis.y - dv.y * axis.x, dv.x * axis.x + dv.y * axis.y) / size;",
            "  if (look.w > 1.5) {",
            "    float rf = length(local);",
            "    if (rf >= 1.0) return half4(0.0);",
            "    float cf = 1.0 - smoothstep(0.45, 1.0, rf);",
            "    float3 film = float3(image.eval(q - toScreen(local * size * float2(0.35, 0.12))).rgb) * 1.01 + 0.008;",
            // Bords de la pellicule : un liseré un peu sombre puis un filet de lumière
            "    float edge = smoothstep(0.35, 0.75, abs(local.x)) * (1.0 - smoothstep(0.75, 1.0, abs(local.x)));",
            "    film *= 1.0 - 0.22 * edge;",
            "    film += float3(0.05) * smoothstep(0.2, 0.6, -local.x) * (1.0 - smoothstep(0.6, 0.95, -local.x));",
            "    float af = look.y * cf * 0.42;",
            "    return half4(half3(film * af), half(af));",
            "  }",
            // Goutte qui glisse : plus étroite au-dessus de son centre
            "  float taper = 1.0 - 0.38 * look.w * smoothstep(0.0, -1.0, local.y);",
            "  float2 l = float2(local.x / taper, local.y);",
            "  float a = atan(l.y, l.x);",
            "  float s = look.z;",
            "  float wave = (0.045 * sin(2.0 * a + s) + 0.03 * sin(3.0 * a + 2.0 * s)) * (1.0 - 0.6 * look.w);",
            "  float r = length(l) / (1.0 + wave);",
            "  float aa = mix(1.4 / min(size.x, size.y), 0.7, blur);",
            "  float cover = 1.0 - smoothstep(1.0 - aa, 1.0, r);",
            "  if (cover <= 0.0) return half4(0.0);",
            "  float h = sqrt(max(0.0, 1.0 - r * r));",
            // La scène vue à travers : renversée, réduite, d'autant plus vers le bord
            "  float2 p = center - toScreen(l * size * (3.2 + 2.5 * (1.0 - h)));",
            "  float3 color = float3(image.eval(p).rgb);",
            "  if (blur > 0.01) {",
            "    float br = blur * min(size.x, size.y) * 1.2;",
            "    color += float3(image.eval(p + float2(br, 0.0)).rgb) + float3(image.eval(p - float2(br, 0.0)).rgb)",
            "           + float3(image.eval(p + float2(0.0, br)).rgb) + float3(image.eval(p - float2(0.0, br)).rgb);",
            "    color /= 5.0;",
            "  }",
            "  float sharp = 1.0 - 0.85 * blur;",
            "  color = color * 1.06 + 0.035;",
            // Bord sombre, lumière concentrée en bas, reflet du ciel en haut à gauche
            "  color *= 1.0 - 0.55 * sharp * smoothstep(0.72, 0.97, r);",
            "  color += float3(0.16) * smoothstep(0.55, 0.92, r) * smoothstep(0.1, 0.8, l.y);",
            "  float2 hp = l - float2(-0.3, -0.42);",
            "  float big = smoothstep(3.0, 16.0, min(size.x, size.y));",
            "  color += sharp * float3(0.12 + 0.5 * big) * exp(-dot(hp, hp) * 90.0) + float3(0.1 * big) * exp(-dot(hp, hp) * 9.0);",
            // Flocon : blanc givré, opaque au centre, qui redevient eau en fondant
            "  float3 frost = mix(color, float3(0.94, 0.96, 1.0), 0.75 * smoothstep(0.0, 0.8, h));",
            "  color = mix(color, frost, look.x);",
            "  float alpha = look.y * cover * mix(1.0, 0.9, look.x);",
            "  return half4(half3(color * alpha), half(alpha));",
            "}");

    private final RuntimeShader air = new RuntimeShader(AIR);
    private final RuntimeShader drop = new RuntimeShader(DROP);
    private final Paint airPaint = new Paint();
    private final Paint dropPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RenderNode layer = new RenderNode("meteo");
    private final float unit;

    WeatherGpu(float density) {
        unit = density;
        layer.setUseCompositingLayer(true, null);
    }

    /** Prend la photo réduite du fond, placée à l'écran (une fois par image). */
    void setImage(BitmapShader image) {
        air.setInputShader("image", image);
        drop.setInputShader("image", image);
    }

    /** L'air : pluie, neige, brume, dans un calque à mi-résolution agrandi avec lissage. */
    void drawAir(Canvas c, int w, int h, float time, int kind, float amount, float tilt, float drift, float flash,
                 float fog, float snowSize, float snowSpeed, float fogSpeed, float fogLow) {
        air.setFloatUniform("snowSize", snowSize);
        air.setFloatUniform("snowSpeed", snowSpeed);
        air.setFloatUniform("fogSpeed", fogSpeed);
        air.setFloatUniform("fogLow", fogLow);
        air.setFloatUniform("size", w, h);
        air.setFloatUniform("time", time);
        air.setFloatUniform("kind", kind);
        air.setFloatUniform("amount", amount);
        air.setFloatUniform("drift", drift);
        air.setFloatUniform("unit", unit);
        float scale = kind == WeatherFx.SNOW ? SCALE : FOG_SCALE;
        air.setFloatUniform("px", scale);
        air.setFloatUniform("flash", flash);
        air.setFloatUniform("fog", fog);
        int lw = (int) Math.ceil(w / scale), lh = (int) Math.ceil(h / scale);
        layer.setPosition(0, 0, lw, lh);
        RecordingCanvas rc = layer.beginRecording(lw, lh);
        rc.scale(1f / scale, 1f / scale);
        airPaint.setShader(air);
        rc.drawRect(0, 0, w, h, airPaint);
        layer.endRecording();
        c.save();
        c.scale(scale, scale);
        c.drawRenderNode(layer);
        c.restore();
    }

    /**
     * Une goutte ou un flocon sur la vitre ; (sx, sy) : demi-largeur et demi-hauteur ; {@code run} : de 0
     * (posée) à 1 (glisse vite).
     */
    void drawDrop(Canvas c, float cx, float cy, float sx, float sy, float snow, float opacity, float seed,
                  float run, float blur, float angle) {
        drop.setFloatUniform("center", cx, cy);
        drop.setFloatUniform("size", sx, sy);
        drop.setFloatUniform("look", snow, opacity, seed, run);
        drop.setFloatUniform("blur", blur);
        drop.setFloatUniform("axis", (float) Math.sin(angle), (float) Math.cos(angle));
        dropPaint.setShader(drop);
        // Penchée, la goutte tient dans un carré
        float e = (angle == 0f ? 1f : 0f), hx = (e * sx + (1f - e) * Math.max(sx, sy)) * 1.15f,
                hy = (e * sy + (1f - e) * Math.max(sx, sy)) * 1.15f;
        c.drawRect(cx - hx, cy - hy, cx + hx, cy + hy, dropPaint);
    }
}
