package fr.proscenium.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;

/**
 * Panneau « verre dépoli » : dessine la partie du fond flouté qui se trouve
 * derrière lui, en tenant compte des mouvements du fond (Ken Burns, parallaxe),
 * puis un voile teinté par la couleur d'accent, un reflet et un liseré lumineux.
 * Aucun flou n'est recalculé : tout reste fluide sur la Freebox.
 */
class GlassView extends FrameLayout {
    /** Fond flouté (192x108) et couleur d'accent de la photo affichée. */
    static volatile Bitmap blurred;
    static volatile int accent = 0xFF7FA7FF;
    /** Plans du fond : la parallaxe (outer) contient le Ken Burns (inner). */
    static View outer, inner;

    private float radius;
    private boolean rimHidden;
    /** Verre neutre (givré blanc, sans teinte d'accent), comme le bandeau du haut. */
    private final boolean neutral;
    private final Paint image = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint veil = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint topLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final RectF rimBox = new RectF();
    private final Matrix matrix = new Matrix();
    private final int[] loc = new int[2];

    /** Reflet qui traverse le verre (0 → 1), ou -1 quand il n'y en a pas. */
    private float sweep = -1f;
    private final Paint sweepPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix sweepMatrix = new Matrix();
    private LinearGradient sweepShader;
    private android.animation.ValueAnimator sweepAnim;

    /** Image calculée par le moteur de réfraction (dock), ou null pour le verre simple. */
    private Bitmap engineBitmap;
    private final RectF engineDst = new RectF();
    private float tintScale = 1f;
    private final Paint grainPaint = new Paint();
    private static Bitmap grainTile;

    /** Point lumineux qui suit la sélection (abscisse locale), ou NaN. */
    private float highlightX = Float.NaN;
    private final Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix highlightMatrix = new Matrix();
    private android.graphics.RadialGradient highlightShader;

    GlassView(Context ctx, float radiusPx) {
        this(ctx, radiusPx, false);
    }

    GlassView(Context ctx, float radiusPx, boolean neutral) {
        super(ctx);
        radius = radiusPx;
        this.neutral = neutral;
        float d = ctx.getResources().getDisplayMetrics().density;
        setWillNotDraw(false);
        setClipChildren(false);
        setClipToPadding(false);

        ColorMatrix vivid = new ColorMatrix();
        vivid.setSaturation(neutral ? 0.85f : 1.6f);
        image.setColorFilter(new ColorMatrixColorFilter(vivid));
        veil.setColor(neutral ? 0x1AFFFFFF : 0x1FFFFFFF);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(1.5f * d);
        topLine.setStyle(Paint.Style.STROKE);
        topLine.setStrokeWidth(1.5f * d);
        topLine.setColor(0xA6FFFFFF);

        setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        // Découpe des coins arrondis par le contour de la vue : lissée par le moteur graphique,
        // contrairement à un masque (clipPath), qui laissait des coins crénelés
        setClipToOutline(true);
        setElevation(10 * d);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        box.set(0, 0, w, h);
        float inset = rim.getStrokeWidth() / 2;
        rimBox.set(inset, inset, w - inset, h - inset);
        updateTopLine();
        shine.setShader(new LinearGradient(0, 0, w * 0.55f, h, 0x38FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        // Bande de lumière diagonale, large d'environ une hauteur de panneau
        sweepShader = new LinearGradient(0, 0, h * 1.2f, h * 0.5f,
                new int[]{0x00FFFFFF, 0x2EFFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP);
        sweepPaint.setShader(sweepShader);
        highlightShader = new android.graphics.RadialGradient(0, 0, h * 1.1f,
                new int[]{0x40FFFFFF, 0x14FFFFFF, 0x00FFFFFF}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
        highlightPaint.setShader(highlightShader);
    }

    /** Arrondi des coins (0 = bande droite). */
    void setCornerRadius(float px) {
        radius = px;
        invalidateOutline();
        // Avant la mise en page, la taille est nulle : les dégradés seront créés par onSizeChanged
        if (getWidth() > 0 && getHeight() > 0) onSizeChanged(getWidth(), getHeight(), getWidth(), getHeight());
        invalidate();
    }

    /** Épaisseur du liseré et du reflet de l'arête haute, en pixels (0 = sans contour). */
    void setRimWidth(float px) {
        rimHidden = px <= 0;
        px = Math.max(1f, px);
        rim.setStrokeWidth(px);
        topLine.setStrokeWidth(px);
        float inset = px / 2;
        rimBox.set(inset, inset, getWidth() - inset, getHeight() - inset);
        updateTopLine();
        invalidate();
    }

    /** Voile sombre posé sous le contenu (0 = aucun) : garde le texte lisible sur un fond clair. */
    private final Paint shade = new Paint();

    {
        // Un Paint neuf est noir opaque : sans voile demandé, il doit rester transparent
        shade.setColor(Color.TRANSPARENT);
    }

    void setShade(int argb) {
        shade.setColor(argb);
        invalidate();
    }

    /** Dessine lui-même le fond du verre (verre calculé par la puce graphique) ; false s'il ne peut pas. */
    interface Renderer {
        boolean draw(Canvas c, RectF box, Paint paint);
    }

    private Renderer renderer;

    void setRenderer(Renderer r) {
        renderer = r;
        invalidate();
    }

    /** Affiche l'image calculée par le moteur de réfraction à la place du fond flouté simple. */
    void setEngineBitmap(Bitmap b) {
        engineBitmap = b;
        invalidate();
    }

    /**
     * Teinte et grain de Codex Glass (1 = référence) : la teinte module le voile coloré,
     * le grain superpose un bruit fin (opacité de référence 4,5 %).
     */
    void setGlassStyle(float tint, float grain) {
        tintScale = tint;
        if (grainTile == null) {
            grainTile = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
            java.util.Random r = new java.util.Random(7);
            int[] px = new int[96 * 96];
            for (int i = 0; i < px.length; i++) {
                int v = r.nextInt(256);
                px[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
            }
            grainTile.setPixels(px, 0, 96, 0, 0, 96, 96);
        }
        grainPaint.setShader(new android.graphics.BitmapShader(grainTile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        grainPaint.setAlpha(Math.round(255 * Math.min(1f, 0.045f * grain)));
        invalidate();
    }

    /** Fait traverser lentement un reflet de gauche à droite, comme la lumière sur une vitre. */
    void sweep(long delayMs, long durationMs) {
        if (sweepAnim != null) sweepAnim.cancel();
        sweepAnim = android.animation.ValueAnimator.ofFloat(0f, 1f);
        sweepAnim.setStartDelay(delayMs);
        sweepAnim.setDuration(durationMs);
        sweepAnim.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        sweepAnim.addUpdateListener(a -> {
            sweep = (float) a.getAnimatedValue();
            invalidate();
        });
        sweepAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                sweep = -1f;
                invalidate();
            }
        });
        sweepAnim.start();
    }

    /** Place le point lumineux à l'abscisse donnée (NaN pour l'éteindre). */
    void setHighlightX(float x) {
        highlightX = x;
        invalidate();
    }

    float getHighlightX() {
        return highlightX;
    }

    @Override
    protected void onDraw(Canvas c) {
        int a = accent;
        if (neutral) {
            // Givré neutre, mais la bordure prend la couleur de l'image
            // Voile légèrement sombre : le texte blanc reste lisible sur les images claires
            tint.setColor(0x38000000);
            rim.setColor((mix(a, Color.WHITE, 0.5f) & 0x00FFFFFF) | 0x80000000);
        } else {
            int tintAlpha = Math.min(255, Math.round(0x24 * tintScale));
            tint.setColor((a & 0x00FFFFFF) | (tintAlpha << 24));
            rim.setColor((mix(a, Color.WHITE, 0.55f) & 0x00FFFFFF) | 0x8C000000);
        }

        Bitmap b = blurred;
        c.save();
        // Verre réfractif calculé par la puce graphique (GpuGlass, pleine résolution) si possible,
        // sinon par GlassEngine sur le processeur (image réduite, agrandie avec lissage)
        boolean refracted = renderer != null && renderer.draw(c, box, image);
        if (!refracted && engineBitmap != null) {
            refracted = true;
            engineDst.set(0, 0, getWidth(), getHeight());
            c.drawBitmap(engineBitmap, null, engineDst, image);
        }
        if (!refracted && b != null) {
            View root = getRootView();
            getLocationOnScreen(loc);
            matrix.setScale(root.getWidth() / (float) b.getWidth(), root.getHeight() / (float) b.getHeight());
            if (inner != null) matrix.postConcat(inner.getMatrix());
            if (outer != null) matrix.postConcat(outer.getMatrix());
            matrix.postTranslate(-loc[0], -loc[1]);
            c.drawBitmap(b, matrix, image);
        } else if (!refracted) {
            c.drawColor(0x55203040);
        }
        c.drawRect(box, tint);
        c.drawRect(box, veil);
        if (shade.getAlpha() > 0) c.drawRect(box, shade);
        if (refracted && grainPaint.getAlpha() > 0) c.drawRect(box, grainPaint);
        c.drawRect(box, shine);
        if (!Float.isNaN(highlightX)) {
            // Lumière qui suit la sélection, posée sur l'arête haute du verre
            highlightMatrix.setTranslate(highlightX, 0);
            highlightShader.setLocalMatrix(highlightMatrix);
            c.drawRect(box, highlightPaint);
        }
        if (sweep >= 0) {
            float band = getHeight() * 1.2f;
            sweepMatrix.setTranslate(-band + sweep * (getWidth() + band), 0);
            sweepShader.setLocalMatrix(sweepMatrix);
            c.drawRect(box, sweepPaint);
        }
        c.restore();
        if (rimHidden) return;
        c.drawRoundRect(rimBox, radius, radius, rim);
        // Reflet sur l'arête du haut, comme sur une plaque de verre : il s'éteint en dégradé le long
        // des coins arrondis, sans coupure nette entre le haut éclairé et le bas des côtés
        c.drawRoundRect(rimBox, radius, radius, topLine);
    }

    /** Dégradé du reflet de l'arête haute : plein en haut, éteint vers le bas des coins arrondis. */
    private void updateTopLine() {
        float fade = Math.max(radius * 1.5f, topLine.getStrokeWidth() * 3);
        topLine.setShader(new LinearGradient(0, 0, 0, fade, 0xA6FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
    }

    static int mix(int a, int b, float t) {
        return Color.rgb(
                (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }
}
