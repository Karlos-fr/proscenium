package fr.proscenium.launcher;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.Collator;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


/**
 * Socle de l'écran d'accueil : état partagé par tous les plans (réglages, vues, verre, carrousel),
 * outils de mise en forme, et liens vers les plans qui le complètent.
 * L'écran est construit en couches : HomeBase → HomeBackdrop (fond, cinéma) → HomeHeader (heure,
 * bandeau) → HomeDock (barre d'applis) → MainActivity (cycle de vie, réglages, panneau).
 */
abstract class HomeBase extends Activity {
    static final String TAG = "Proscenium";

    static final long STATS_EVERY_MS = 2_000;
    static final long WEATHER_EVERY_MS = 30 * 60_000L;
    /**
     * Cadence du Ken Burns : 30 images/s sur un appareil confortable (2 Go de mémoire ou plus),
     * 5 images/s sur une petite box, où le fond bouge de moins d'un pixel par seconde.
     */
    static final long KEN_BURNS_TICK_FAST_MS = 33, KEN_BURNS_TICK_SLOW_MS = 200;
    long kenBurnsTick = KEN_BURNS_TICK_SLOW_MS;
    /** Intensité du flou du fond quand le panneau est ouvert (0 = net, 1 = très flou). */
    static final float DOF_ALPHA = 0.75f;
    static final String SHINE_TAG = "shine";
    /** Écart entre deux tuiles et taille d'une tuile, en dp, à 100 %. */
    static final float SLOT_DP = 152;
    static final int TILE_W_DP = 128, TILE_H_DP = 72;
    static final String PREF_REOPEN_TAB = "reopen_settings_tab";
    static final String PREF_REOPEN_WELCOME = "reopen_welcome";

    final Handler ui = new Handler(Looper.getMainLooper());
    /** Fonds d'écran : lecture et décodage des images, rien d'autre. */
    final ExecutorService io = Executors.newSingleThreadExecutor();
    /** Réseau (fiches TMDB, tendances, météo, villes) : à part, pour ne jamais retarder le changement de fond. */
    final ExecutorService net = Executors.newSingleThreadExecutor();
    final Stats stats = new Stats();
    final boolean cpuReadable = Stats.cpuReadable();
    final Settings settings = new Settings();
    final GlassSettings glassSettings = new GlassSettings();
    /** Réglages fins de chaque météo du fond (sous-menu Réglages de la météo). */
    final WeatherStyles weatherStyles = new WeatherStyles();
    /** Météo montrée pendant le réglage de sa page (WeatherFx.RAIN…), ou -1. */
    int weatherPreview = -1;
    final List<GlassView> glasses = new ArrayList<>();
    /** Liserés de sélection, teintés par la couleur d'accent. */
    final List<GradientDrawable> rings = new ArrayList<>();
    final DecelerateInterpolator ease = new DecelerateInterpolator(1.8f);
    final Random random = new Random();
    SimpleDateFormat dateFormat, clockFormat;

    float density;
    SharedPreferences prefs;
    GlassController glassCtl;
    SettingsPanel panel;

    // Plan du fond
    FrameLayout wallOuter, wallInner;
    ImageView wallImg, blurImg;
    /** Fond flouté : entrée cinématique et profondeur de champ du panneau. */
    ImageView dofImg;
    View shadeTop, shadeBottom;
    // Mode cinéma au repos : bandes et carte titre (logo du film)
    View barTop, barBottom;
    LinearLayout titleCard;
    ImageView titleLogo;
    TextView titleText, titleSub;
    /** Fiche du film sous le titre : infos, note, résumé. */
    FilmCard filmCard;
    // Plan du milieu
    FrameLayout mid;
    GlassView dock;
    FocusGlow glow;
    // Premier plan
    FrameLayout front;
    GlassView header;
    GradientDrawable headerRing;
    View cpuBlock, ramBlock, weatherBlock, headerDivider;
    RollingClock clock;
    TextView dateText, cpuValue, ramValue, tempText, cityText, windText, gustText;
    View windBlock;
    IconView cpuIcon, ramIcon, weatherIcon;

    Wallpaper.Frame frame;
    int accent = GlassView.accent;
    ValueAnimator accentAnim;
    long weatherAt;
    /** Météo vivante devant le fond, et la dernière météo reçue (null tant qu'il n'y en a pas). */
    WeatherFx weatherFx;
    Weather.Now lastWeather;
    boolean focusedOnce, idle, swallowKeyUp, longPressed, cleanedUp;

    float kenBurnsT = 0.5f, kenBurnsAmount = 1f;
    ValueAnimator zoomAnim;

    /** Piste du carrousel : c'est elle qui a le focus, les tuiles défilent dessous. */
    FrameLayout track;
    final List<View> tiles = new ArrayList<>();
    final Map<View, Integer> tileColors = new HashMap<>();
    int selected;
    /** Position animée (index fractionnaire, non bornée : la boucle est gérée au placement). */
    float position, target;
    ValueAnimator scrollAnim;
    /** 0 = dock non sélectionné, 1 = dock sélectionné (zoom, inclinaison et lueur de la tuile centrale). */
    float focusAmount;
    ValueAnimator focusAnim;

    /** Menu en verre de l'appli du centre, ouvert par un appui long. */
    GlassView appMenu;
    /** Panneau de verre du raccourci HDMI (création, modification), et l'éditeur qui l'occupe. */
    GlassView shortcutGlass;
    ShortcutEditor shortcutEditor;
    boolean moveMode, editingDock, menuArmed;
    TextView dockHint;

    void invalidateGlasses() {
        invalidateGlasses(false);
    }

    /** {@code slow} : simple dérive du Ken Burns, le verre réfractif se met à jour au plus une fois par seconde. */
    void invalidateGlasses(boolean slow) {
        for (GlassView g : glasses) g.invalidate();
        // Le fond a bougé : le verre réfractif du dock doit relire ce qu'il y a derrière lui
        if (glassCtl != null) glassCtl.requestCapture(slow);
    }

    // ---------------------------------------------------------------- Aides

    /**
     * Épaisseur du liseré de sélection : celle du contour du verre (Verre → Contour), pour que
     * sélectionner le bandeau l'éclaire sans épaissir sa bordure.
     */
    int ringWidth() {
        return Math.max(1, settings.rimWidth.value);
    }

    /** Coins arrondis et, si demandé, fin liseré (couleur d'accent) quand l'élément est sélectionné. */
    void makeFocusable(View v, int radiusDp, boolean withRing) {
        float r = dp(radiusDp);
        v.setFocusable(true);
        v.setClickable(true);
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
            }
        });
        v.setClipToOutline(true);
        if (!withRing) return;
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(r);
        ring.setStroke(ringWidth(), 0xE6FFFFFF);
        rings.add(ring);
        StateListDrawable fg = new StateListDrawable();
        fg.addState(new int[]{android.R.attr.state_focused}, ring);
        fg.addState(new int[0], new ColorDrawable(Color.TRANSPARENT));
        v.setForeground(fg);
    }

    void launch(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Log.w(TAG, "Lancement impossible : " + i, e);
        }
    }

    LinearLayout row(int gapDp) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setClipChildren(false);
        l.setClipToPadding(false);
        GradientDrawable gap = new GradientDrawable();
        gap.setSize(dp(gapDp), 1);
        l.setDividerDrawable(gap);
        l.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
        return l;
    }

    TextView text(float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        styleText(t, sp, color, bold);
        return t;
    }

    void styleText(TextView t, float sp, int color, boolean bold) {
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setShadowLayer(dp(6), 0, dp(1), 0x66000000);
    }

    FrameLayout.LayoutParams at(int gravity, int l, int t, int r, int b) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = gravity;
        lp.setMargins(dp(l), dp(t), dp(r), dp(b));
        return lp;
    }

    static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    int dp(float v) {
        return Math.round(v * density);
    }

    // ---------------------------------------------------------------- Liens entre les plans

    /** Ouvre le panneau de réglages sur un onglet (MainActivity). */
    abstract void openPanel(int tab);

    /** Couleur de la lueur sous l'appli du centre (HomeDock). */
    abstract void updateGlowColor();

    /** Nouveau fond à l'écran : les météos Aléatoire et Selon le fond peuvent changer avec lui. */
    void frameWeather() { }

    /** Panneau de verre suivi par l'animation du fond (MainActivity). */
    public abstract GlassView newGlass(float radiusPx);

    /** Applis installées, paquet et nom, dans l'ordre alphabétique (MainActivity). */
    public abstract List<String[]> installedApps();
}
