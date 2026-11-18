package fr.proscenium.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;

import java.util.Random;

/**
 * Météo vivante devant le film : pluie, orage, neige ou brouillard, selon la météo réelle de la ville
 * (ou choisis dans les réglages). Repris de la recomp de Fun Tracks :
 * <ul>
 * <li>l'air (WeatherGpu) : neige qui tombe en se balançant, brume qui dérive. Pas de pluie dans l'air :
 * la pluie se voit seulement aux gouttes sur l'objectif ;</li>
 * <li>l'objectif (render/ft_render_webgpu_lens.c) : des gouttes s'y posent, s'étalent, les plus lourdes
 * glissent en zigzaguant, avalent celles qu'elles touchent et laissent une traînée humide, puis sèchent ;
 * les flocons s'y posent et fondent en gouttes ;</li>
 * <li>l'orage (render/ft_render_webgpu_particles.c) : un éclair toutes les 5 à 17 secondes, en deux
 * sursauts, qui illumine la scène ; un sur trois, lointain, plus faible.</li>
 * </ul>
 * Android 13 et plus : la puce graphique calcule tout. Avant : neige simple et éclairs seulement.
 * Placé entre le fond et le dock : l'interface reste nette devant la météo.
 */
final class WeatherFx extends View {
    static final int NONE = 0, RAIN = 1, STORM = 2, SNOW = 3, FOG = 4;
    private static final String TAG = "Proscenium";

    /** Plus grand nombre de gouttes, gouttelettes de traînée et flocons sur la vitre. */
    private static final int MAX_DROPS = 600;
    /** Rayon (part de la hauteur de l'écran) à partir duquel une goutte est assez lourde pour glisser. */
    private static final float SLIDE_RADIUS = 0.0105f;
    /** Plus grosse goutte possible : au-delà, l'eau en trop part en traînée. */
    private static final float MAX_RADIUS = 0.03f;
    /** Pas de la simulation : 60 par seconde, quelle que soit la cadence d'affichage. */
    private static final float STEP_MS = 1000f / 60f;
    private static final int MELT_STEPS = 150;
    /** Largeur de la copie réduite de la photo lue par la puce graphique. */
    private static final int SMALL_WIDTH = 960;

    private final ImageView wall;
    private final View inner, outer;
    private final WeatherGpu gpu;
    private final float density;
    private final Random random = new Random();
    private final long start = SystemClock.uptimeMillis();

    private int kind = NONE;
    private float amount = 1f, tilt;
    private boolean lens = true, running;

    WeatherFx(Context ctx, ImageView wall, View inner, View outer) {
        super(ctx);
        this.wall = wall;
        this.inner = inner;
        this.outer = outer;
        density = ctx.getResources().getDisplayMetrics().density;
        WeatherGpu g = null;
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                g = new WeatherGpu(density);
            } catch (Throwable t) {
                Log.w(TAG, "Météo sur le fond : programme graphique refusé", t);
            }
        }
        gpu = g;
        for (int i = 0; i < MAX_DROPS; i++) drops[i] = new Drop();
        setFocusable(false);
        setClickable(false);
    }

    // ---------------------------------------------------------------- Météo

    /** Effet d'un code météo WMO (Open-Meteo). */
    static int kindOf(int code) {
        if (code == 45 || code == 48) return FOG;
        if (code >= 51 && code <= 67) return RAIN;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return SNOW;
        if (code >= 80 && code <= 82) return RAIN;
        if (code >= 95) return STORM;
        return NONE;
    }

    /** Force d'un code météo : bruine, pluie faible, modérée, forte… */
    static float amountOf(int code) {
        switch (code) {
            case 51: case 56: return 0.3f;
            case 53: return 0.4f;
            case 55: case 57: return 0.5f;
            case 61: case 80: return 0.6f;
            case 63: case 66: case 81: return 0.8f;
            case 65: case 67: case 82: return 1f;
            case 71: case 77: return 0.5f;
            case 73: case 85: return 0.75f;
            case 75: case 86: return 1f;
            case 45: return 0.8f;
            case 48: return 1f;
            default: return code >= 95 ? 1f : 0.8f;
        }
    }

    /**
     * Pente de la pluie d'après le vent (vitesse en km/h, direction d'où il vient, en degrés) : un vent
     * d'ouest pousse la pluie vers la droite, comme si l'on regardait vers le nord.
     */
    static float tiltOf(int wind, int windFrom) {
        float strength = Math.min(wind / 45f, 1f);
        return (float) Math.sin(Math.toRadians(windFrom)) * 0.32f * strength;
    }

    void set(int kind, float amount, float tilt, boolean lens) {
        if (kind != this.kind) {
            dropCount = 0;
            pending = 0;
            nextFlash = 0;
            flashStart = -1;
        }
        this.kind = kind;
        this.amount = Math.max(0.1f, Math.min(2f, amount));
        this.tilt = tilt;
        this.lens = lens;
        if (!lens) dropCount = 0;
        invalidate();
    }

    void start() {
        running = true;
        lastStep = 0;
        invalidate();
    }

    void stop() {
        running = false;
    }

    // ---------------------------------------------------------------- Dessin

    private Bitmap lastPhoto, small;
    private BitmapShader smallShader;
    private final Matrix toScreen = new Matrix(), smallToScreen = new Matrix();
    private final int[] loc = new int[2];
    private final Paint tint = new Paint(), flashPaint = new Paint(Paint.FILTER_BITMAP_FLAG), sky = new Paint();
    private final Paint fallback = new Paint(Paint.ANTI_ALIAS_FLAG);

    {
        flashPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SCREEN));
    }

    /** Photo du fond → vue (centerCrop) → Ken Burns → parallaxe → écran → ce calque. */
    private Bitmap photo() {
        Drawable d = wall.getDrawable();
        if (!(d instanceof BitmapDrawable)) return null;
        Bitmap b = ((BitmapDrawable) d).getBitmap();
        if (b == null || b.isRecycled()) return null;
        toScreen.set(wall.getImageMatrix());
        toScreen.postConcat(wall.getMatrix());
        toScreen.postConcat(inner.getMatrix());
        toScreen.postConcat(outer.getMatrix());
        getLocationOnScreen(loc);
        toScreen.postTranslate(-loc[0], -loc[1]);
        return b;
    }

    @Override
    protected void onDraw(Canvas c) {
        if (kind == NONE) return;
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        long now = SystemClock.uptimeMillis();
        // Temps ramené à dix minutes : les positions restent précises sur la puce graphique
        float time = ((now - start) % 600_000L) / 1000f;
        float flash = flash(now);
        Bitmap photo = photo();

        // Ciel couvert : la scène s'assombrit et bleuit un peu sous la pluie, s'éclaircit sous la neige
        float k = Math.min(amount, 1.4f);
        switch (kind) {
            case RAIN: tint.setColor(argb(0.10f * k, 0x0A1420)); break;
            case STORM: tint.setColor(argb(0.24f * k, 0x070D18)); break;
            case SNOW: tint.setColor(argb(0.07f * k, 0xDCE6F2)); break;
            default: tint.setColor(argb(0.10f * k, 0xC8CDD2)); break;
        }
        c.drawRect(0, 0, w, h, tint);

        // Éclair : la scène elle-même s'illumine (fondu « écran »), surtout vers le ciel
        if (flash > 0.01f) {
            if (photo != null) {
                flashPaint.setAlpha((int) (Math.min(1f, flash * 0.75f) * 255));
                c.drawBitmap(photo, toScreen, flashPaint);
            }
            sky.setShader(new LinearGradient(0, 0, 0, h, argb(0.45f * flash, 0xDDE8FF), 0, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, sky);
        }

        boolean onGpu = gpu != null && photo != null;
        if (onGpu) {
            if (photo != lastPhoto) {
                // Copie réduite, faite une fois par fond : assez de détail pour une scène vue à travers l'eau
                lastPhoto = photo;
                int sw = Math.min(SMALL_WIDTH, photo.getWidth());
                int sh = Math.max(1, Math.round(photo.getHeight() * sw / (float) photo.getWidth()));
                small = Bitmap.createScaledBitmap(photo, sw, sh, true);
                smallShader = new BitmapShader(small, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                smallShader.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
            }
            smallToScreen.setScale(photo.getWidth() / (float) small.getWidth(), photo.getHeight() / (float) small.getHeight());
            smallToScreen.postConcat(toScreen);
            smallShader.setLocalMatrix(smallToScreen);
            gpu.setImage(smallShader);
            // Pas de pluie dans l'air : seulement les gouttes sur l'objectif. Brume pour le brouillard,
            // un léger voile sous la neige
            if (kind == FOG || kind == SNOW) {
                float fog = kind == FOG ? 0.75f * amount : 0.12f * amount;
                float drift = -tilt / 0.32f * 45f * density;
                gpu.drawAir(c, w, h, time, kind, amount, tilt, drift, flash, fog);
            }
            if (lens && kind != FOG) {
                stepLens(now, w / (float) h);
                drawLens(c, w, h);
            }
        } else {
            drawFallback(c, w, h, time);
        }

        if (running) postInvalidateOnAnimation();
    }

    private static int argb(float alpha, int rgb) {
        return ((int) (Math.max(0f, Math.min(1f, alpha)) * 255) << 24) | rgb;
    }

    /** Sans puce graphique programmable : flocons simples (les gouttes sur l'objectif demandent AGSL). */
    private void drawFallback(Canvas c, int w, int h, float time) {
        if (kind == SNOW) {
            int n = Math.round(160 * amount);
            fallback.setColor(0xB0F0F4FA);
            for (int i = 0; i < n; i++) {
                float r = (1 + 2.5f * hash(i * 7.7f)) * density;
                float speed = (30 + 60 * hash(i * 2.9f)) * density;
                float y = (hash(i * 3.1f) * (h + 20) + time * speed) % (h + 20) - 10;
                float x = (hash(i * 1.7f) * w + (float) Math.sin(time * 0.8f + i) * 12 * density) % w;
                c.drawCircle(x, y, r, fallback);
            }
        }
    }

    private static float hash(float seed) {
        float v = (float) (Math.sin(seed * 12.9898f) * 43758.5453f);
        return v - (float) Math.floor(v);
    }

    // ---------------------------------------------------------------- Éclairs

    private long nextFlash, flashStart = -1;
    private boolean flashFar;

    /** Intensité de l'éclair (0 hors éclair) : deux sursauts, puis la lueur s'éteint. */
    private float flash(long now) {
        if (kind != STORM) return 0;
        if (nextFlash == 0) nextFlash = now + 2500 + random.nextInt(4000);
        if (now >= nextFlash) {
            flashStart = now;
            flashFar = random.nextFloat() < 0.35f;
            nextFlash = now + 5000 + random.nextInt(12000);
        }
        if (flashStart < 0) return 0;
        long t = now - flashStart;
        float f = t < 50 ? 1f : t < 110 ? 0.25f : t < 170 ? 0.85f : t < 230 ? 0.35f
                : t < 600 ? 0.35f * (1f - (t - 230) / 370f) : 0f;
        return f * (flashFar ? 0.35f : 1f);
    }

    // ---------------------------------------------------------------- Vitre

    /**
     * Goutte sur la vitre, posée ou qui glisse, gouttelette laissée en traînée, ou flocon. Positions en
     * part de la largeur (x) et de la hauteur (y) de l'écran ; rayon en part de la hauteur.
     */
    private static final class Drop {
        float x, y, radius, vx, vy, snow, seed, trailFrom, filmFrom;
        /** Gouttelette de traînée : elle ne glisse pas et s'évapore doucement. */
        boolean trail;
        /** Morceau de la pellicule d'eau laissée par une goutte qui glisse (avec trail). */
        boolean film;
        /** Pas restants où la goutte reste accrochée au verre. */
        int stuck;
        int age, life;
    }

    private final Drop[] drops = new Drop[MAX_DROPS];
    private int dropCount;
    private float pending;
    private long lastStep;

    private Drop addDrop(float x, float y, float radius, boolean snow, boolean trail) {
        if (dropCount >= MAX_DROPS) return null;
        Drop d = drops[dropCount++];
        d.x = x;
        d.y = y;
        d.radius = radius;
        d.vx = d.vy = 0;
        d.snow = snow ? 1f : 0f;
        d.seed = random.nextFloat() * 6.2831853f;
        d.trail = trail;
        d.trailFrom = y;
        d.filmFrom = y;
        d.film = false;
        d.stuck = 0;
        d.age = 0;
        d.life = trail ? 180 + random.nextInt(240) : snow ? 90 + random.nextInt(200) : 0;
        return d;
    }

    /** Avance la vitre au rythme de 60 pas par seconde, quelle que soit la cadence d'affichage. */
    private void stepLens(long now, float aspect) {
        if (lastStep == 0 || now - lastStep > 500) lastStep = now - (long) STEP_MS;
        int steps = 0;
        while (now - lastStep >= STEP_MS && steps < 4) {
            step(aspect);
            lastStep += (long) STEP_MS;
            steps++;
        }
        if (steps == 4) lastStep = now;
    }

    /** Les deux gouttes se touchent-elles ? */
    private static boolean touch(Drop a, Drop b, float aspect) {
        float dx = (a.x - b.x) * aspect, dy = a.y - b.y, reach = (a.radius + b.radius) * 0.85f;
        return dx * dx + dy * dy < reach * reach;
    }

    /**
     * Un pas, comme l'eau sur une vitre : les impacts posent des gouttes ou grossissent celles qu'ils
     * touchent ; une goutte reste accrochée tant qu'elle est légère, puis glisse, par à-coups (elle
     * accroche le verre, repart, dévie un peu), ramasse les gouttes sur son chemin et laisse derrière
     * elle une traînée humide ; vidée de son eau, elle s'arrête. Les flocons se posent et fondent en gouttes.
     */
    private void step(float aspect) {
        boolean snow = kind == SNOW;
        float rate = (snow ? 0.035f : 0.09f) * amount;
        if (kind == STORM) rate *= 1.6f;
        pending += rate;
        while (pending >= 1f) {
            pending -= 1f;
            float size = random.nextFloat();
            float r = snow ? 0.006f + 0.01f * size
                    : random.nextFloat() < 0.12f ? SLIDE_RADIUS + 0.006f * size   // grosse goutte : glisse aussitôt
                    : 0.004f + 0.0085f * size * size;
            float x = random.nextFloat(), y = random.nextFloat() * 0.95f;
            // Un impact sur une goutte posée s'y fond : elle grossit
            Drop hit = null;
            for (int i = 0; i < dropCount && !snow; i++) {
                Drop o = drops[i];
                if (o.trail || o.snow > 0) continue;
                float dx = (o.x - x) * aspect, dy = o.y - y;
                if (dx * dx + dy * dy < o.radius * o.radius) { hit = o; break; }
            }
            if (hit != null) hit.radius = Math.min((float) Math.sqrt(hit.radius * hit.radius + r * r), MAX_RADIUS);
            else addDrop(x, y, r, snow, false);
        }
        for (int i = 0; i < dropCount; i++) {
            Drop d = drops[i];
            d.age++;
            if (d.snow > 0) {
                // Le flocon fond en goutte d'eau, un peu plus petite
                if (d.age > d.life) {
                    d.snow = Math.max(0f, d.snow - 1f / MELT_STEPS);
                    d.radius *= 0.9993f;
                }
            } else if (!d.trail) {
                slide(d, aspect);
            }
            boolean gone = d.trail ? d.age >= d.life : d.y - d.radius > 1.02f;
            if (gone || d.x < -0.1f || d.x > 1.1f) {
                drops[i] = drops[--dropCount];
                drops[dropCount] = d;
                i--;
            }
        }
    }

    private void slide(Drop d, float aspect) {
        // L'humidité de l'air et la pluie fine font grossir lentement chaque goutte
        d.radius = Math.min(d.radius + 0.000008f * amount, MAX_RADIUS);
        if (d.radius < SLIDE_RADIUS) {
            d.vy = 0;
            d.vx = 0;
            return;
        }
        if (d.stuck > 0) {
            d.stuck--;
            d.vy *= 0.6f;
        } else {
            float weight = d.radius / SLIDE_RADIUS;
            d.vy = d.vy * 0.965f + 0.00011f * weight;
            // Le verre la retient parfois un instant (d'autant plus qu'elle est légère)
            if (random.nextFloat() < 0.01f / weight) d.stuck = 4 + random.nextInt(30);
            // Elle dévie un peu de temps en temps
            if (random.nextFloat() < 0.035f) d.vx = (random.nextFloat() - 0.5f) * d.vy * 0.9f;
        }
        d.vx *= 0.92f;
        d.y += d.vy;
        d.x += d.vx / aspect;
        if (d.vy < 0.0004f) return;
        // Elle avale les gouttes et gouttelettes qu'elle touche
        for (int j = 0; j < dropCount; j++) {
            Drop o = drops[j];
            if (o == d || o.life < 0 || o.film || !touch(d, o, aspect) || o.y > d.y + d.radius * 0.3f && !o.trail && o.vy > d.vy) continue;
            d.radius = Math.min((float) Math.sqrt(d.radius * d.radius + o.radius * o.radius), MAX_RADIUS);
            o.y = 9f;   // retirée au prochain passage (sortie par le bas)
            o.trail = false;
            o.snow = 0;
            o.life = -1;
        }
        // Elle laisse derrière elle une pellicule d'eau qui sèche peu à peu
        if (d.y - d.filmFrom > d.radius * 0.7f) {
            d.filmFrom = d.y;
            Drop f = addDrop(d.x, d.y - d.radius * 0.9f, d.radius * 0.55f, false, true);
            if (f != null) {
                f.film = true;
                f.life = 70 + random.nextInt(70);
            }
        }
        // et, de loin en loin, de fines gouttelettes ; elle s'allège
        if (d.y - d.trailFrom > d.radius * 4f) {
            d.trailFrom = d.y;
            float tr = d.radius * (0.14f + 0.16f * random.nextFloat());
            if (addDrop(d.x + (random.nextFloat() - 0.5f) * d.radius * 0.5f / aspect, d.y - d.radius * 1.4f, tr,
                    false, true) != null) {
                d.radius = (float) Math.sqrt(Math.max(d.radius * d.radius - tr * tr * 1.5f, 0));
            }
        }
    }

    private void drawLens(Canvas c, int w, int h) {
        for (int i = 0; i < dropCount; i++) {
            Drop d = drops[i];
            if (d.y > 2f) continue;
            // Impact : la goutte s'étale en quelques pas ; une gouttelette s'évapore en fin de vie
            float spread = Math.min(0.55f + 0.09f * d.age, 1f);
            float opacity = d.trail ? Math.max(0f, Math.min(1f, (d.life - d.age) / (d.life * (d.film ? 0.75f : 0.4f)))) : 1f;
            if (opacity <= 0) continue;
            float radius = d.radius * h * spread * (d.trail ? 0.6f + 0.4f * opacity : 1f);
            if (d.film) {
                // Pellicule : allongée, plus étroite en séchant
                gpu.drawDrop(c, d.x * w, d.y * h, d.radius * h * (0.55f + 0.45f * opacity),
                        d.radius * h * 1.7f, 0f, opacity, d.seed, 2f);
                continue;
            }
            float run = Math.min(d.vy * 160f, 1f);
            // Une goutte qui glisse s'allonge : tête ronde en bas, plus fine en haut
            float sx = radius * (1f - 0.12f * run);
            float sy = radius * (1f + 0.35f * run);
            gpu.drawDrop(c, d.x * w, d.y * h, sx, sy, d.snow, opacity, d.seed, run);
        }
    }
}
