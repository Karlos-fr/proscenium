package fr.proscenium.launcher;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.ImageView;

import java.util.ArrayList;
import java.util.List;

/**
 * Fait tourner le verre réfractif du dock.
 * Android 13 et plus : la puce graphique le calcule à chaque image, à pleine résolution (GpuGlass).
 * Avant (Freebox, Android 7), sur le processeur :
 *  1. sur le fil principal, capture la photo nette située derrière le dock, réduite au quart ;
 *  2. sur un fil dédié, calcule réfraction, aberration et animations (GlassEngine) ;
 *  3. renvoie l'image au dock, qui l'affiche agrandie avec lissage.
 * Les animations tournent à 20 images/s, seulement quand l'accueil est affiché.
 */
final class GlassController {
    /** Facteur de réduction : le verre est calculé au quart de la résolution de l'écran. */
    private static final int Q = 4;
    private static final long FRAME_MS = 50;
    private static final long CAPTURE_MIN_MS = 33;
    /**
     * Pendant la dérive lente du fond, on recalcule le verre aussi souvent que l'appareil le permet :
     * l'intervalle vaut 20 fois le coût mesuré d'un calcul, entre 33 ms (30 images/s, TV puissante)
     * et 1 s (petite box), pour que le verre ne prenne jamais plus de 5 % du processeur.
     */
    private static final long SLOW_MIN_MS = 33, SLOW_MAX_MS = 1000, COST_FACTOR = 20;
    /** Coût moyen d'une capture + d'un rendu, en ms (moyenne glissante). */
    private volatile float cost = 25f;
    private long captureStart;

    private final GlassView dock;
    private final ImageView wall;
    private final View inner, outer;
    private final GlassSettings settings;
    private final GlassEngine engine;
    private final float density;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final HandlerThread thread = new HandlerThread("ProsceniumGlass");
    private final Handler worker;
    private final long start = SystemClock.uptimeMillis();
    private final Paint capturePaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    private Bitmap capture;
    private final Bitmap[] outputs = new Bitmap[2];
    private int nextOutput;
    private volatile boolean running;
    private volatile List<RectF> lenses = new ArrayList<>();
    private volatile float cornerRadius;
    private long lastCapture;
    private boolean capturePending;

    GlassController(GlassView dock, ImageView wall, View inner, View outer, GlassSettings settings, float density) {
        this.dock = dock;
        this.wall = wall;
        this.inner = inner;
        this.outer = outer;
        this.settings = settings;
        this.density = density;
        engine = new GlassEngine(settings);
        // Android 13 et plus : le verre est calculé par la puce graphique, à chaque image
        GpuGlass dockGlass = android.os.Build.VERSION.SDK_INT >= 33 ? newGpuGlass(settings, density) : null;
        gpu = dockGlass != null;
        if (gpu) targets.add(new GpuTarget(dock, dockGlass, true, 0));
        thread.start();
        worker = new Handler(thread.getLooper());
    }

    // ---------------------------------------------------------------- Verre sur la puce graphique

    /** Vrai quand le verre est calculé par la puce graphique (sinon, GlassEngine sur le processeur). */
    private final boolean gpu;
    /** Panneaux en verre réfractif : le dock, puis ceux ajoutés par addGpuPanel (bandeau du haut). */
    private final List<GpuTarget> targets = new ArrayList<>();
    /** Tuile du centre et arrondi du dock, en pixels du dock (pleine résolution). */
    private volatile RectF lensFull;
    private volatile float radiusFull;

    private static final long GPU_FRAME_MS = 33;
    /** Dernière fois que le fond a demandé un redessin (Ken Burns, parallaxe, nouveau fond). */
    private volatile long lastExternalFrame;

    /** Le programme graphique est compilé par la TV au lancement : en cas d'échec, retour au processeur. */
    private static GpuGlass newGpuGlass(GlassSettings settings, float density) {
        try {
            return new GpuGlass(settings, density);
        } catch (RuntimeException e) {
            android.util.Log.w("Proscenium", "Verre sur la puce graphique indisponible", e);
            return null;
        }
    }

    /**
     * Donne aussi le verre réfractif à un autre panneau (bandeau du haut), avec son arrondi en pixels.
     * Seulement sur la puce graphique : sur le processeur (Freebox), il garde son verre dépoli simple.
     */
    void addGpuPanel(GlassView view, float radiusPx) {
        if (!gpu) return;
        GpuGlass glass = newGpuGlass(settings, density);
        if (glass == null) return;
        GpuTarget t = new GpuTarget(view, glass, false, radiusPx);
        targets.add(t);
        // Le grain du verre n'est dessiné que s'il est réglé (sinon un aplat noir)
        view.setGlassStyle(settings.tint.f(), settings.grain.f());
        if (running && enabled) view.setRenderer(t.renderer);
    }

    /** Un panneau dont le verre est calculé par la puce graphique, avec son propre programme. */
    private final class GpuTarget {
        final GlassView view;
        final GpuGlass glass;
        /** Le dock a une lentille sous la tuile du centre ; son arrondi suit le mode de la bande. */
        final boolean isDock;
        final float radius;
        final Matrix toView = new Matrix();
        final int[] loc = new int[2];
        final GlassView.Renderer renderer = this::draw;

        GpuTarget(GlassView view, GpuGlass glass, boolean isDock, float radius) {
            this.view = view;
            this.glass = glass;
            this.isDock = isDock;
            this.radius = radius;
        }

        /** La photo du fond est lue directement par la puce graphique, placée dans le repère du panneau. */
        boolean draw(Canvas c, RectF box, Paint paint) {
            Drawable d = wall.getDrawable();
            if (!(d instanceof BitmapDrawable) || view.getWidth() == 0) return false;
            // Photo → vue (centerCrop) → Ken Burns → parallaxe → écran → repère du panneau
            toView.set(wall.getImageMatrix());
            toView.postConcat(wall.getMatrix());
            toView.postConcat(inner.getMatrix());
            toView.postConcat(outer.getMatrix());
            view.getLocationOnScreen(loc);
            toView.postTranslate(-loc[0], -loc[1]);
            boolean animate = running && settings.animated() && animationsAllowed;
            glass.draw(c, box, paint, ((BitmapDrawable) d).getBitmap(), toView, isDock ? lensFull : null,
                    isDock ? radiusFull : radius, (SystemClock.uptimeMillis() - start) / 1000f, animate);
            // Eau calme, Liquide, Pluie : 30 images/s. Si le fond bouge déjà (Ken Burns), ses images suffisent :
            // en ajouter d'autres, non synchronisées, doublait le nombre de redessins de tout l'écran
            long now = SystemClock.uptimeMillis();
            if (animate && now - lastExternalFrame > GPU_FRAME_MS * 3 / 2) view.postInvalidateDelayed(GPU_FRAME_MS);
            return true;
        }
    }

    private volatile boolean enabled = true, animationsAllowed = true;

    /** Verre réfractif activé ou non ; désactivé, le dock revient au verre dépoli simple. */
    void setEnabled(boolean on) {
        if (on == enabled) return;
        enabled = on;
        if (on) {
            start();
        } else {
            stop();
            dock.setEngineBitmap(null);
            for (GpuTarget t : targets) t.view.setRenderer(null);
        }
    }

    /** Le mode économie coupe les animations Eau calme, Liquide et Pluie. */
    void setAnimationsAllowed(boolean allowed) {
        animationsAllowed = allowed;
        engine.setAnimationsAllowed(allowed);
        if (gpu) invalidateTargets();
        else renderOnce();
    }

    private void invalidateTargets() {
        for (GpuTarget t : targets) t.view.invalidate();
    }

    void start() {
        if (!enabled) return;
        running = true;
        if (gpu) {
            for (GpuTarget t : targets) t.view.setRenderer(t.renderer);
            return;
        }
        requestCapture();
        worker.removeCallbacks(tick);
        worker.post(tick);
    }

    void stop() {
        running = false;
        worker.removeCallbacks(tick);
        ui.removeCallbacks(doCapture);
        capturePending = false;
    }

    /** À appeler quand les réglages changent : la partie statique est recalculée. */
    void settingsChanged() {
        engine.markStaticDirty();
        dock.setGlassStyle(settings.tint.f(), settings.grain.f());
        if (gpu) {
            for (GpuTarget t : targets) t.view.setGlassStyle(settings.tint.f(), settings.grain.f());
            invalidateTargets();
        } else {
            renderOnce();
        }
    }

    /** Lentilles autour des tuiles (coordonnées du dock), à mettre à jour après la mise en page. */
    void setLenses(List<RectF> dockRects, float radiusPx) {
        List<RectF> scaled = new ArrayList<>();
        for (RectF r : dockRects) scaled.add(new RectF(r.left / Q, r.top / Q, r.right / Q, r.bottom / Q));
        lenses = scaled;
        cornerRadius = radiusPx / Q;
        lensFull = dockRects.isEmpty() ? null : new RectF(dockRects.get(0));
        radiusFull = radiusPx;
        engine.markStaticDirty();
        requestCapture();
    }

    /** Le fond a bougé (parallaxe, nouvelle photo) : nouvelle capture, au plus toutes les 60 ms. */
    void requestCapture() {
        requestCapture(false);
    }

    /**
     * {@code slow} : dérive lente du Ken Burns (moins d'un pixel par seconde), une capture
     * par seconde suffit. Rien n'est recalculé quand le dock est invisible (mode cinéma).
     */
    void requestCapture(boolean slow) {
        // Sur la puce graphique, pas de copie d'écran : il suffit de redessiner les panneaux
        if (gpu) {
            lastExternalFrame = SystemClock.uptimeMillis();
            if (running) invalidateTargets();
            return;
        }
        if (!running || capturePending || dock.getAlpha() * ((View) dock.getParent()).getAlpha() < 0.05f) return;
        if (slow && SystemClock.uptimeMillis() - lastCapture < slowInterval()) return;
        capturePending = true;
        long wait = Math.max(0, CAPTURE_MIN_MS - (SystemClock.uptimeMillis() - lastCapture));
        ui.postDelayed(doCapture, wait);
    }

    /** Intervalle entre deux recalculs pendant la dérive du fond, selon la puissance mesurée. */
    long slowInterval() {
        return Math.max(SLOW_MIN_MS, Math.min(SLOW_MAX_MS, (long) (cost * COST_FACTOR)));
    }

    /** Vrai si l'appareil calcule le verre assez vite pour suivre un fond animé à 30 images/s. */
    boolean fast() {
        return slowInterval() <= 100;
    }

    private final Runnable doCapture = new Runnable() {
        @Override public void run() {
            capturePending = false;
            lastCapture = SystemClock.uptimeMillis();
            captureStart = SystemClock.elapsedRealtimeNanos();
            Drawable d = wall.getDrawable();
            if (!(d instanceof BitmapDrawable) || dock.getWidth() == 0) return;
            Bitmap sharp = ((BitmapDrawable) d).getBitmap();
            int w = Math.max(4, dock.getWidth() / Q), h = Math.max(4, dock.getHeight() / Q);
            if (capture == null || capture.getWidth() != w || capture.getHeight() != h) {
                capture = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            }
            // Photo -> vue (centerCrop) -> Ken Burns -> parallaxe -> écran -> repère du dock, réduit au quart
            Matrix m = new Matrix(wall.getImageMatrix());
            m.postConcat(wall.getMatrix());
            m.postConcat(inner.getMatrix());
            m.postConcat(outer.getMatrix());
            int[] loc = new int[2];
            dock.getLocationOnScreen(loc);
            m.postTranslate(-loc[0], -loc[1]);
            m.postScale(1f / Q, 1f / Q);
            Canvas c = new Canvas(capture);
            c.drawColor(0xFF000000);
            c.drawBitmap(sharp, m, capturePaint);
            int[] px = new int[w * h];
            capture.getPixels(px, 0, w, 0, 0, w, h);
            float unit = density / Q;
            long captureNs = SystemClock.elapsedRealtimeNanos() - captureStart;
            worker.post(() -> {
                long t0 = SystemClock.elapsedRealtimeNanos();
                engine.setSource(px, w, h, unit);
                renderNow(w, h);
                float ms = (captureNs + SystemClock.elapsedRealtimeNanos() - t0) / 1e6f;
                cost = cost * 0.8f + ms * 0.2f;
            });
        }
    };

    private void renderOnce() {
        worker.post(() -> {
            Bitmap c = capture;
            if (c != null) renderNow(c.getWidth(), c.getHeight());
        });
    }

    /** Animation : une image toutes les 50 ms tant qu'une animation est active. */
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            boolean visible = dock.getAlpha() * ((View) dock.getParent()).getAlpha() >= 0.05f;
            boolean animated = settings.animated() && animationsAllowed;
            if (animated && visible && capture != null) renderNow(capture.getWidth(), capture.getHeight());
            worker.postDelayed(this, animated ? FRAME_MS : 500);
        }
    };

    /** Fil dédié : calcule l'image puis la confie au dock (deux images en alternance, sans déchirure). */
    private void renderNow(int w, int h) {
        if (!engine.hasSource()) return;
        float t = (SystemClock.uptimeMillis() - start) / 1000f;
        int[] px = engine.render(t, cornerRadius, lenses);
        if (px == null) return;
        Bitmap target = outputs[nextOutput];
        if (target == null || target.getWidth() != w || target.getHeight() != h) {
            target = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            outputs[nextOutput] = target;
        }
        target.setPixels(px, 0, w, 0, 0, w, h);
        nextOutput ^= 1;
        Bitmap ready = target;
        ui.post(() -> dock.setEngineBitmap(ready));
    }
}
