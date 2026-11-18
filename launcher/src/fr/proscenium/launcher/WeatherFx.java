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
 * <li>l'orage (Lightning, d'après le studio de foudre de Platformer) : des éclairs fractals qui
 * descendent, se ramifient, scintillent et éclairent la scène.</li>
 * </ul>
 * Android 13 et plus : la puce graphique calcule tout. Avant : neige simple et éclairs seulement.
 * Placé entre le fond et le dock : l'interface reste nette devant la météo.
 */
final class WeatherFx extends View {
    /** Couches cumulables (masque de bits) : pluie, éclairs d'orage, neige, brouillard. */
    static final int NONE = 0, RAIN = 1, STORM = 2, SNOW = 4, FOG = 8, SUN = 16;
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

    private int layers = NONE;
    private float amount = 1f, tilt;
    /**
     * Poussée du vent sur les gouttes qui glissent : de -1 (vers la gauche) à 1 (vers la droite), selon
     * la flèche du vent du bandeau (vent d'ouest : vers la droite) ; rapport rafales / vent moyen.
     */
    private float windX, gust = 1f;
    /** Horloge des rafales, en secondes. */
    private float windClock;
    /**
     * Bourrasque en cours : poussée de côté (signée), instant de début, durées de montée, de maintien
     * et de retombée (secondes), et délai avant la suivante.
     */
    private float gustAmp, gustStart = -100f, gustRise, gustHold, gustFall, nextGust = 2f;
    private boolean lens = true, running;

    /** Réglages fins de chaque météo (sous-menu Réglages de la météo), lus à chaque image. */
    private final WeatherStyles st;

    WeatherFx(Context ctx, ImageView wall, View inner, View outer, WeatherStyles styles) {
        super(ctx);
        st = styles;
        lightning = new Lightning(styles);
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

    /**
     * Couches d'un code météo WMO (Open-Meteo) : la bruine vient avec de la brume, la pluie ou la bruine
     * verglaçante avec du grésil, l'orage avec de la pluie, et de la grêle pour les orages violents.
     */
    static int layersOf(int code, boolean day) {
        // Ciel dégagé ou peu nuageux, de jour : des rayons de soleil
        if (code <= 2) return day ? SUN : NONE;
        if (code == 45 || code == 48) return FOG;
        if (code == 51 || code == 53 || code == 55) return RAIN | FOG;
        if (code == 56 || code == 57 || code == 66 || code == 67) return RAIN | SNOW;
        if (code >= 61 && code <= 65) return RAIN;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return SNOW;
        if (code >= 80 && code <= 82) return RAIN;
        if (code == 95) return RAIN | STORM;
        if (code >= 96) return RAIN | STORM | SNOW;
        return NONE;
    }

    private boolean has(int layer) {
        return (layers & layer) != 0;
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

    /** Couches à afficher (masque RAIN | STORM | SNOW | FOG), force, pente et rafales du vent, gouttes. */
    void set(int layers, float amount, float tilt, float gust, boolean lens) {
        this.windX = Math.max(-1f, Math.min(1f, -tilt / 0.32f));
        this.gust = Math.max(1f, Math.min(3f, gust));
        // Une couche retirée emporte ce qu'elle avait mis sur l'écran
        if ((this.layers & ~layers & (RAIN | SNOW)) != 0) {
            dropCount = 0;
            pending = pendingSnow = 0;
        }
        if ((layers & STORM) != 0 && (this.layers & STORM) == 0) lightning.reset();
        this.layers = layers;
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
        if (layers == NONE) return;
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        long now = SystemClock.uptimeMillis();
        // Temps ramené à dix minutes : les positions restent précises sur la puce graphique
        float time = ((now - start) % 600_000L) / 1000f;
        // Orage : éclairs du studio de foudre de Platformer (voir Lightning)
        float flash = 0f;
        if (has(STORM)) {
            lightning.update(now, Math.min(1f, amount), w, h);
            flash = lightning.flash();
        }
        Bitmap photo = photo();

        // Ciel couvert : la scène s'assombrit et bleuit un peu sous la pluie, plus sous l'orage, et
        // s'éclaircit sous la neige et le brouillard ; les voiles des couches se superposent
        float k = Math.min(amount, 1.4f);
        if (has(RAIN)) veil(c, w, h, 0.10f * k * st.rainDim.f(), 0x0A1420);
        if (has(STORM)) veil(c, w, h, 0.24f * k * st.stormDim.f(), 0x050818);   // voile d'orage du studio
        if (has(SNOW)) veil(c, w, h, 0.07f * k * st.snowVeil.f(), 0xDCE6F2);
        if (has(FOG)) veil(c, w, h, 0.10f * k * st.fogVeil.f(), 0xC8CDD2);
        if (has(SUN)) veil(c, w, h, 0.05f * k * st.sunWarmth.f(), 0xFFB05A);   // lumière chaude

        // Éclair : la scène elle-même s'illumine (fondu « écran »), surtout vers le ciel, puis l'éclair
        // est tracé par-dessus, derrière les gouttes (il est dehors, de l'autre côté de la vitre)
        if (flash > 0.01f) {
            if (photo != null) {
                flashPaint.setAlpha((int) (Math.min(1f, flash * 0.55f) * 255));
                c.drawBitmap(photo, toScreen, flashPaint);
            }
            sky.setShader(new LinearGradient(0, 0, 0, h, argb(0.35f * flash, 0xC8D8FF), 0, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, sky);
        }
        if (has(STORM)) lightning.draw(c, h);

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
            // Les gouttes sont sur la vitre, le brouillard et les voiles dehors : elles les montrent aussi
            float hr = 0, hg = 0, hb = 0, ha = 0;
            float[][] layersVeil = {
                    {has(RAIN) ? 0.10f * k * st.rainDim.f() : 0, 0x0A / 255f, 0x14 / 255f, 0x20 / 255f},
                    {has(STORM) ? 0.24f * k * st.stormDim.f() : 0, 0x05 / 255f, 0x08 / 255f, 0x18 / 255f},
                    {has(SNOW) ? 0.07f * k * st.snowVeil.f() + 0.06f * amount * st.snowVeil.f() : 0, 0.90f, 0.93f, 0.96f},
                    {has(FOG) ? 0.10f * k * st.fogVeil.f() + Math.min(0.85f, 0.5f * amount * st.fogDensity.f()) : 0,
                            0.84f, 0.86f, 0.89f}};
            for (float[] v : layersVeil) {
                float a = Math.max(0f, Math.min(0.9f, v[0]));
                hr = hr * (1 - a) + v[1] * a;
                hg = hg * (1 - a) + v[2] * a;
                hb = hb * (1 - a) + v[3] * a;
                ha = ha + a * (1 - ha);
            }
            if (ha > 0.001f) gpu.setHaze(hr / ha, hg / ha, hb / ha, ha);
            else gpu.setHaze(0, 0, 0, 0);
            // Pas de pluie dans l'air : seulement les gouttes sur l'objectif. Brume pour le brouillard,
            // un léger voile sous la neige
            if (has(FOG) || has(SNOW)) {
                float fog = (has(FOG) ? 0.75f * amount * st.fogDensity.f() : 0f)
                        + (has(SNOW) ? 0.12f * amount * st.snowVeil.f() : 0f);
                float drift = -tilt / 0.32f * 45f * density * st.snowWind.f();
                gpu.drawAir(c, w, h, time, has(SNOW), amount * st.snowFlakes.f(), drift,
                        flash, fog, st.snowSize.f(), st.snowFall.f(), st.fogDrift.f(), st.fogLow.f());
            }
            // Soleil : rayons dehors, devant le film et derrière les gouttes de la vitre
            if (has(SUN)) {
                gpu.drawSun(c, w, h, time, st.sunPosition.f(), 0.9f * amount * st.sunRays.f(),
                        Math.max(0.88f, Math.min(0.995f, 0.88f + 0.055f * st.sunLength.f())),
                        Math.max(0.05f, Math.min(0.9f, 0.45f * st.sunThreshold.f())),
                        Math.min(1f, 0.5f * st.sunWarmth.f()), st.sunGlow.f(), st.sunShimmer.f(), st.sunDust.f(),
                        st.sunClouds.f() * 0.7f);
            }
            if (lens && (has(RAIN) || has(SNOW))) {
                stepLens(now, w / (float) h);
                drawLens(c, w, h);
            }
        } else {
            drawFallback(c, w, h, time);
        }

        if (running) postInvalidateOnAnimation();
    }

    private void veil(Canvas c, int w, int h, float alpha, int rgb) {
        tint.setColor(argb(alpha, rgb));
        c.drawRect(0, 0, w, h, tint);
    }

    private static int argb(float alpha, int rgb) {
        return ((int) (Math.max(0f, Math.min(1f, alpha)) * 255) << 24) | rgb;
    }

    /** Sans puce graphique programmable : flocons simples (les gouttes sur l'objectif demandent AGSL). */
    private void drawFallback(Canvas c, int w, int h, float time) {
        if (has(SNOW)) {
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

    private final Lightning lightning;

    // ---------------------------------------------------------------- Vitre

    /**
     * Goutte sur la vitre, posée ou qui glisse, gouttelette laissée en traînée, ou flocon. Positions en
     * part de la largeur (x) et de la hauteur (y) de l'écran ; rayon en part de la hauteur.
     */
    private static final class Drop {
        float x, y, radius, vx, vy, snow, seed, trailFrom, filmFrom;
        /** Sens de la glisse, en radians depuis la verticale (positif : vers la droite). */
        float angle;
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
    private float pending, pendingSnow;
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
        d.angle = 0;
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
        windClock += 1f / 60f;
        updateGust();
        // Pluie et neige se cumulent : chacune pose ses gouttes ou ses flocons à son rythme
        if (has(RAIN)) pending += 0.09f * st.rainDrops.f() * amount;
        if (has(SNOW)) pendingSnow += 0.035f * st.snowLens.f() * amount;
        while (pending >= 1f) {
            pending -= 1f;
            float size = random.nextFloat();
            float r = (random.nextFloat() < 0.12f ? SLIDE_RADIUS + 0.006f * size   // grosse goutte : glisse aussitôt
                    : 0.004f + 0.0085f * size * size) * st.rainSize.f();
            float x = random.nextFloat(), y = random.nextFloat() * 0.95f;
            // Un impact sur une goutte posée s'y fond : elle grossit
            Drop hit = null;
            for (int i = 0; i < dropCount; i++) {
                Drop o = drops[i];
                if (o.trail || o.snow > 0) continue;
                float dx = (o.x - x) * aspect, dy = o.y - y;
                if (dx * dx + dy * dy < o.radius * o.radius) { hit = o; break; }
            }
            if (hit != null) hit.radius = Math.min((float) Math.sqrt(hit.radius * hit.radius + r * r), MAX_RADIUS);
            else addDrop(x, y, r, false, false);
        }
        while (pendingSnow >= 1f) {
            pendingSnow -= 1f;
            addDrop(random.nextFloat(), random.nextFloat() * 0.95f, 0.006f + 0.01f * random.nextFloat(), true, false);
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

    /**
     * Bourrasques : de brusques coups de vent, d'autant plus forts et fréquents que les rafales
     * annoncées dépassent le vent moyen. Montée en un dixième de seconde, maintien, retombée, avec un
     * léger battement ; le plus souvent dans le sens du vent, parfois à contresens (tourbillon).
     */
    private void updateGust() {
        if (windClock < nextGust) return;
        float strength = Math.max(0.25f, Math.min(2f, gust - 1f));
        float dir = Math.abs(windX) > 0.05f ? Math.signum(windX) : (random.nextBoolean() ? 1f : -1f);
        if (random.nextFloat() < 0.25f) dir = -dir;
        gustAmp = dir * strength * (0.6f + 0.8f * random.nextFloat());
        gustStart = windClock;
        gustRise = 0.08f + 0.08f * random.nextFloat();
        gustHold = 0.2f + 0.6f * random.nextFloat();
        gustFall = 0.3f + 0.4f * random.nextFloat();
        // Plus les rafales sont fortes, plus elles se suivent de près
        nextGust = windClock + gustRise + gustHold + gustFall + (2f + 7f * random.nextFloat()) / (0.6f + strength);
    }

    /** Poussée de la bourrasque à cet instant (0 hors bourrasque). */
    private float gustNow() {
        float t = windClock - gustStart;
        float env;
        if (t < 0) return 0f;
        if (t < gustRise) env = t / gustRise;
        else if (t < gustRise + gustHold) env = 1f;
        else if (t < gustRise + gustHold + gustFall) env = 1f - (t - gustRise - gustHold) / gustFall;
        else return 0f;
        return gustAmp * env * (1f + 0.25f * (float) Math.sin(t * 57f));
    }

    private void slide(Drop d, float aspect) {
        // L'humidité de l'air et la pluie fine font grossir lentement chaque goutte
        d.radius = Math.min(d.radius + 0.000008f * amount, MAX_RADIUS);
        float windEffect = has(RAIN) ? st.rainWind.f() : st.snowWind.f();
        if (d.radius < SLIDE_RADIUS) {
            d.vy = 0;
            d.vx = 0;
            // Une bourrasque fait frémir et décale un peu les gouttes moyennes posées
            if (d.radius > SLIDE_RADIUS * 0.6f) d.x += gustNow() * windEffect * 0.0012f / aspect;
            return;
        }
        if (d.stuck > 0) {
            d.stuck--;
            d.vy *= 0.6f;
        } else {
            float weight = d.radius / SLIDE_RADIUS;
            d.vy = d.vy * 0.965f + 0.00011f * weight * st.rainSlide.f();
            // Le verre la retient parfois un instant (d'autant plus qu'elle est légère)
            if (random.nextFloat() < 0.01f / weight) d.stuck = 4 + random.nextInt(30);
            // Elle dévie un peu de temps en temps
            if (random.nextFloat() < 0.035f) d.vx = (random.nextFloat() - 0.5f) * d.vy * 0.9f;
        }
        d.vx *= 0.92f;
        // Vent moyen, plus la bourrasque : elle change net la direction de la goutte
        float push = (windX * 0.9f + gustNow() * 1.3f) * windEffect;
        float dx = d.vx + push * d.vy;
        d.y += d.vy;
        d.x += dx / aspect;
        // Sens de la glisse, lissé : la goutte et sa traînée s'orientent dans ce sens
        if (d.vy > 0.0004f) d.angle += ((float) Math.atan2(dx, d.vy) - d.angle) * 0.3f;
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
        if (st.rainTrails.value > 0 && d.y - d.filmFrom > d.radius * 0.7f) {
            d.filmFrom = d.y;
            float back = d.radius * 0.9f;
            Drop f = addDrop(d.x - (float) Math.sin(d.angle) * back / aspect, d.y - (float) Math.cos(d.angle) * back,
                    d.radius * 0.55f, false, true);
            if (f != null) {
                f.film = true;
                f.angle = d.angle;
                f.life = Math.max(10, Math.round((70 + random.nextInt(70)) * st.rainTrails.f()));
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
            float spread = d.trail ? Math.min(0.55f + 0.09f * d.age, 1f) : 1f;
            float opacity = d.trail ? Math.max(0f, Math.min(1f, (d.life - d.age) / (d.life * (d.film ? 0.75f : 0.4f)))) : 1f;
            if (opacity <= 0) continue;
            float radius = d.radius * h * spread * (d.trail ? 0.6f + 0.4f * opacity : 1f);
            if (d.film) {
                // Pellicule : allongée, plus étroite en séchant
                gpu.drawDrop(c, d.x * w, d.y * h, d.radius * h * (0.55f + 0.45f * opacity),
                        d.radius * h * 1.7f, 0f, opacity, d.seed, 2f, 0f, d.angle);
                continue;
            }
            float run = Math.min(d.vy * 160f, 1f);
            // Une goutte qui glisse s'allonge : tête ronde en bas, plus fine en haut
            float sx = radius * (1f - 0.12f * run);
            float sy = radius * (1f + 0.35f * run);
            float blur = 0f;
            if (!d.trail && d.age < 30) {
                // Arrivée : floue et un peu plus grande, la mise au point se fait en 0,2 s ; en même temps
                // l'impact l'écrase, puis elle reprend sa forme en oscillant comme de l'eau
                float t = d.age / 60f;
                float focus = Math.min(1f, t / 0.2f);
                blur = (1f - focus) * (1f - focus);
                float wob = (float) (Math.exp(-t * 14f) * Math.cos(t * 40f));
                float grow = 1f + 0.25f * blur;
                sx *= grow * (1f + 0.3f * wob);
                sy *= grow * (1f - 0.22f * wob);
                opacity *= 0.55f + 0.45f * focus;
            }
            gpu.drawDrop(c, d.x * w, d.y * h, sx, sy, d.snow, opacity, d.seed, run, blur, run > 0.05f ? d.angle : 0f);
        }
    }
}
