package fr.proscenium.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewParent;

/**
 * Petites icônes vectorielles dessinées à la main, sur une grille de 24 x 24.
 * Les icônes météo peuvent s'animer doucement (setAnimated) : rayons du soleil qui tournent,
 * nuages qui dérivent, pluie et neige qui tombent, éclair qui clignote, brume qui ondule,
 * étoile qui scintille près de la lune. Une image toutes les 50 ms, et presque rien quand
 * l'icône est cachée (mode cinéma sans bandeau).
 */
final class IconView extends View {
    enum Kind { CPU, RAM, GEAR, SUN, MOON, CLOUD, PARTLY_DAY, PARTLY_NIGHT, RAIN, SNOW, STORM, FOG,
        PICTURE, CINEMA, DOCK, GLASS, HOME, PLAYLIST, FILM }

    private static final long FRAME_MS = 50;

    private Kind kind;
    private int color = 0xFFFFFFFF;
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Remplissage aux angles adoucis (dents de l'engrenage). */
    private final Paint soft = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private boolean animated;
    /** Temps de l'animation, en secondes. */
    private float t;
    private final WeatherArt art = new WeatherArt();

    IconView(Context ctx, Kind kind) {
        super(ctx);
        this.kind = kind;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1.7f);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        fill.setStyle(Paint.Style.FILL);
        soft.setStyle(Paint.Style.FILL);
        soft.setPathEffect(new android.graphics.CornerPathEffect(0.6f));
        setLayerType(LAYER_TYPE_NONE, null);
    }

    void setKind(Kind k) {
        if (k != kind) { kind = k; invalidate(); }
    }

    /** Couleur des icônes « système » (CPU, RAM, engrenage). */
    void setColor(int c) {
        if (c != color) { color = c; invalidate(); }
    }

    /** Anime l'icône si c'est une icône météo (désactivé en mode économie). */
    void setAnimated(boolean on) {
        if (on != animated) { animated = on; invalidate(); }
    }

    /**
     * L'accueil redessine déjà l'écran à chaque pas du Ken Burns : l'icône s'y cale ({@link #tick})
     * au lieu d'ajouter ses propres images, décalées, qui coûteraient autant qu'un écran entier.
     */
    private boolean externalClock;
    private long lastTick;

    void setExternalClock(boolean on) {
        if (on != externalClock) { externalClock = on; invalidate(); }
    }

    /** Pas du Ken Burns : l'icône avance d'une image, au plus toutes les 50 ms. */
    void tick() {
        if (!animated || !isWeather()) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastTick < FRAME_MS - 5) return;
        lastTick = now;
        invalidate();
    }

    private boolean isWeather() {
        switch (kind) {
            case SUN: case MOON: case CLOUD: case PARTLY_DAY: case PARTLY_NIGHT:
            case RAIN: case SNOW: case STORM: case FOG: return true;
            default: return false;
        }
    }

    /** Opacité réelle, parents compris : une icône dans un bandeau effacé ne s'anime pas. */
    private float shownAlpha() {
        float a = getAlpha();
        for (ViewParent v = getParent(); v instanceof View; v = v.getParent()) a *= ((View) v).getAlpha();
        return a;
    }

    @Override
    protected void onDraw(Canvas c) {
        boolean anim = animated && isWeather();
        // Immobile : un instant où tout est visible (gouttes en l'air, étoiles allumées)
        t = anim ? (SystemClock.uptimeMillis() % 600_000L) / 1000f : 1.1f;
        // Image suivante, ou simple vérification espacée tant que l'icône est cachée
        if (anim && !externalClock) postInvalidateDelayed(isShown() && shownAlpha() > 0.02f ? FRAME_MS : 1000);
        c.save();
        c.scale(getWidth() / 24f, getHeight() / 24f);
        stroke.setColor(color);
        switch (kind) {
            case CPU: cpu(c); break;
            case RAM: ram(c); break;
            case GEAR: gear(c); break;
            case SUN: case MOON: case CLOUD: case PARTLY_DAY: case PARTLY_NIGHT:
            case RAIN: case SNOW: case STORM: case FOG:
                art.draw(c, kind, t);
                break;
            case PICTURE: picture(c); break;
            case CINEMA: cinema(c); break;
            case DOCK: dock(c); break;
            case GLASS: glass(c); break;
            case HOME: home(c); break;
            case PLAYLIST: playlist(c); break;
            case FILM: film(c); break;
        }
        c.restore();
    }

    private void cpu(Canvas c) {
        r.set(6, 6, 18, 18);
        c.drawRoundRect(r, 2.2f, 2.2f, stroke);
        r.set(9.5f, 9.5f, 14.5f, 14.5f);
        c.drawRoundRect(r, 1, 1, stroke);
        for (float p = 9; p <= 15; p += 3) {
            c.drawLine(p, 2.8f, p, 6, stroke);
            c.drawLine(p, 18, p, 21.2f, stroke);
            c.drawLine(2.8f, p, 6, p, stroke);
            c.drawLine(18, p, 21.2f, p, stroke);
        }
    }

    private void ram(Canvas c) {
        r.set(5, 3, 19, 8);
        c.drawOval(r, stroke);
        c.drawLine(5, 5.5f, 5, 18.5f, stroke);
        c.drawLine(19, 5.5f, 19, 18.5f, stroke);
        r.set(5, 9.5f, 19, 14.5f);
        c.drawArc(r, 0, 180, false, stroke);
        r.set(5, 16, 19, 21);
        c.drawArc(r, 0, 180, false, stroke);
    }

    /** Roue crantée : un disque, 8 dents rectangulaires arrondies, un trou au centre. */
    private void gear(Canvas c) {
        if (gearPath == null) {
            gearPath = new Path();
            gearPath.addCircle(12, 12, 7.6f, Path.Direction.CW);
            Path tooth = new Path();
            android.graphics.Matrix m = new android.graphics.Matrix();
            for (int i = 0; i < 8; i++) {
                tooth.reset();
                tooth.addRoundRect(new RectF(10.2f, 1.2f, 13.8f, 6f), 0.9f, 0.9f, Path.Direction.CW);
                m.setRotate(45f * i, 12, 12);
                tooth.transform(m);
                gearPath.op(tooth, Path.Op.UNION);
            }
            Path hole = new Path();
            hole.addCircle(12, 12, 3.4f, Path.Direction.CW);
            gearPath.op(hole, Path.Op.DIFFERENCE);
        }
        fill.setColor(color);
        c.drawPath(gearPath, fill);
    }

    private static Path gearPath;

    // ---------------------------------------------------------------- Icônes des onglets

    /** Trois lignes et un triangle de lecture : les playlists. */
    private void playlist(Canvas c) {
        c.drawLine(3, 6, 17, 6, stroke);
        c.drawLine(3, 11, 17, 11, stroke);
        c.drawLine(3, 16, 11, 16, stroke);
        Path play = new Path();
        play.moveTo(14, 13.5f);
        play.lineTo(20.5f, 17);
        play.lineTo(14, 20.5f);
        play.close();
        fill.setColor(color);
        c.drawPath(play, fill);
    }

    /** Clap de cinéma : les films TMDB. */
    private void film(Canvas c) {
        r.set(3, 10, 21, 20);
        c.drawRoundRect(r, 1.5f, 1.5f, stroke);
        Path top = new Path();
        top.moveTo(3.2f, 9.5f);
        top.lineTo(19.5f, 4.5f);
        top.lineTo(20.3f, 7.2f);
        top.lineTo(4, 12.2f);
        top.close();
        c.drawPath(top, stroke);
        c.drawLine(8.5f, 7.8f, 10.2f, 10.6f, stroke);
        c.drawLine(13.5f, 6.3f, 15.2f, 9.1f, stroke);
    }

    /** Cadre avec soleil et montagnes : les arrière-plans. */
    private void picture(Canvas c) {
        r.set(3, 5, 21, 19);
        c.drawRoundRect(r, 2, 2, stroke);
        c.drawCircle(8.5f, 9.5f, 1.6f, stroke);
        Path p = new Path();
        p.moveTo(4, 18);
        p.lineTo(10, 12);
        p.lineTo(14, 16);
        p.lineTo(16.5f, 13.5f);
        p.lineTo(20, 17);
        c.drawPath(p, stroke);
    }

    /** Écran large entre deux bandes : le mode cinéma. */
    private void cinema(Canvas c) {
        r.set(3, 6, 21, 18);
        c.drawRoundRect(r, 1.5f, 1.5f, stroke);
        c.drawLine(3, 9, 21, 9, stroke);
        c.drawLine(3, 15, 21, 15, stroke);
        Path play = new Path();
        play.moveTo(10.5f, 10.3f);
        play.lineTo(14, 12);
        play.lineTo(10.5f, 13.7f);
        play.close();
        fill.setColor(color);
        c.drawPath(play, fill);
    }

    /** Bande avec trois tuiles, la centrale plus grande : la barre d'apps. */
    private void dock(Canvas c) {
        c.drawLine(2, 17, 22, 17, stroke);
        r.set(3, 11, 7, 14);
        c.drawRoundRect(r, 0.8f, 0.8f, stroke);
        r.set(9, 9, 15, 14);
        c.drawRoundRect(r, 1, 1, stroke);
        r.set(17, 11, 21, 14);
        c.drawRoundRect(r, 0.8f, 0.8f, stroke);
    }

    /** Losange avec reflet : le verre. */
    private void glass(Canvas c) {
        Path p = new Path();
        p.moveTo(12, 3);
        p.lineTo(20, 12);
        p.lineTo(12, 21);
        p.lineTo(4, 12);
        p.close();
        c.drawPath(p, stroke);
        c.drawLine(8.5f, 9.5f, 12, 6, stroke);
    }

    /** Maison : l'accueil. */
    private void home(Canvas c) {
        Path p = new Path();
        p.moveTo(4, 11.5f);
        p.lineTo(12, 4.5f);
        p.lineTo(20, 11.5f);
        c.drawPath(p, stroke);
        p.reset();
        p.moveTo(6.5f, 10);
        p.lineTo(6.5f, 19.5f);
        p.lineTo(17.5f, 19.5f);
        p.lineTo(17.5f, 10);
        c.drawPath(p, stroke);
        r.set(10.5f, 14, 13.5f, 19.5f);
        c.drawRect(r, stroke);
    }
}
