package fr.proscenium.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Écran de veille du système : le mode cinéma de l'accueil, par-dessus n'importe quelle appli
 * (Netflix en pause…). Mêmes fonds, bandes, carte titre et heure que l'accueil, réglés au même
 * endroit ; n'importe quelle touche le referme.
 */
public final class CinemaDream extends DreamService {
    private static final String TAG = "Proscenium";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Random random = new Random();
    private final Settings settings = new Settings();
    private final DecelerateInterpolator ease = new DecelerateInterpolator(1.8f);
    private SharedPreferences prefs;
    private SimpleDateFormat clockFormat, dateFormat;
    private float density;

    private FrameLayout wallInner;
    private ImageView wallImg, fadeImg, titleLogo;
    private View shadeTop;
    private LinearLayout titleCard;
    private TextView titleText, titleSub, dateText;
    private FilmCard filmCard;
    private RollingClock clock;
    private Wallpaper.Frame frame;
    private boolean running;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(Lang.wrap(base));
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(true);
        density = getResources().getDisplayMetrics().density;
        prefs = getSharedPreferences("proscenium", MODE_PRIVATE);
        settings.load(prefs);
        clockFormat = new SimpleDateFormat(settings.timeFormat.index == 1 ? "hhmmss" : "HHmmss", Lang.locale(this));
        dateFormat = new SimpleDateFormat(getString(R.string.date_pattern), Lang.locale(this));
        Wallpaper.market = Lang.tag(this);
        setContentView(build());
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        running = true;
        clockTick.run();
        kenBurns(true);
        loadWallpaper(false);
        ui.postDelayed(rotate, settings.bgDelay.value() * 1000L);
    }

    @Override
    public void onDreamingStopped() {
        running = false;
        ui.removeCallbacksAndMessages(null);
        wallInner.animate().cancel();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        io.shutdownNow();
        super.onDetachedFromWindow();
    }

    // ---------------------------------------------------------------- Construction

    private View build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        wallInner = new FrameLayout(this);
        wallImg = new ImageView(this);
        wallImg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        fadeImg = new ImageView(this);
        fadeImg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        fadeImg.setAlpha(0f);
        wallInner.addView(wallImg, match());
        wallInner.addView(fadeImg, match());
        wallInner.setAlpha(0f);
        root.addView(wallInner, match());

        // Dégradé sous l'heure, plus marqué quand le haut de l'image est clair
        shadeTop = new View(this);
        shadeTop.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xC0000000, 0x40000000, 0x00000000}));
        FrameLayout.LayoutParams shadeLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(230));
        shadeLp.gravity = Gravity.TOP;
        root.addView(shadeTop, shadeLp);

        // Bandes du mode cinéma
        int bar = barHeight();
        for (int gravity : new int[]{Gravity.TOP, Gravity.BOTTOM}) {
            View v = new View(this);
            v.setBackgroundColor(Color.BLACK);
            v.setAlpha(settings.barOpacity.f());
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, bar));
            lp.gravity = gravity;
            if (bar > 0) root.addView(v, lp);
        }

        // Heure et date, à la même place et à la même taille que sur l'accueil
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        Typeface thin = Typeface.create("sans-serif-thin", Typeface.NORMAL);
        float k = settings.clockSize.f();
        clock = new RollingClock(this, t -> {
            style(t, 76 * k, Color.WHITE);
            t.setTypeface(thin);
            t.setIncludeFontPadding(false);
            t.setFontFeatureSettings("tnum");
        }, t -> {
            style(t, 30 * k, 0xCCFFFFFF);
            t.setTypeface(thin);
            t.setIncludeFontPadding(false);
            t.setFontFeatureSettings("tnum");
            t.setPadding(0, 0, 0, dp(10 * k));
        });
        clock.setTime(clockFormat.format(new Date()), false);
        clock.showSeconds(settings.clockSeconds.value);
        box.addView(clock);
        dateText = new TextView(this);
        style(dateText, 18 * settings.dateSize.f(), 0xF0FFFFFF);
        dateText.setPadding(dp(2), dp(2), 0, 0);
        box.addView(dateText);
        root.addView(box, at(Gravity.TOP | Gravity.START, 48, 26, 0, 0));

        // Carte titre : logo du film ou titre, et année
        boolean left = settings.titleSide.index == Settings.SIDE_LEFT;
        int side = left ? Gravity.START : Gravity.END;
        titleCard = new LinearLayout(this);
        titleCard.setOrientation(LinearLayout.VERTICAL);
        titleCard.setGravity(side);
        titleCard.setAlpha(0f);
        titleLogo = new ImageView(this);
        titleLogo.setAdjustViewBounds(true);
        titleLogo.setMaxWidth(dp(380));
        titleLogo.setMaxHeight(dp(140));
        titleLogo.setScaleType(left ? ImageView.ScaleType.FIT_START : ImageView.ScaleType.FIT_END);
        titleText = new TextView(this);
        style(titleText, 34, Color.WHITE);
        titleText.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        titleText.setMaxWidth(dp(520));
        titleText.setGravity(side);
        titleSub = new TextView(this);
        style(titleSub, 14, 0xCCFFFFFF);
        titleSub.setLetterSpacing(0.15f);
        titleSub.setPadding(dp(2), dp(8), dp(2), 0);
        for (View child : new View[]{titleLogo, titleText, titleSub}) {
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.gravity = side;
            titleCard.addView(child, clp);
        }
        filmCard = new FilmCard(this);
        filmCard.attach(titleCard, side);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.gravity = Gravity.BOTTOM | side;
        tlp.setMargins(dp(56), 0, dp(56), Math.max(bar, dp(30)) + dp(22));
        root.addView(titleCard, tlp);
        return root;
    }

    private int barHeight() {
        switch (settings.cinemaBars.index) {
            case Settings.BARS_NONE: return 0;
            case Settings.BARS_SCOPE: return dp(69);
            default: return dp(30);
        }
    }

    // ---------------------------------------------------------------- Animations

    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            Date now = new Date();
            clock.setTime(clockFormat.format(now), true);
            String d = dateFormat.format(now);
            dateText.setText(Character.toUpperCase(d.charAt(0)) + d.substring(1));
            ui.postDelayed(this, 1000 - System.currentTimeMillis() % 1000);
        }
    };

    private final Runnable rotate = new Runnable() {
        @Override public void run() {
            loadWallpaper(true);
            ui.postDelayed(this, settings.bgDelay.value() * 1000L);
        }
    };

    /**
     * Lente dérive du Ken Burns, aller puis retour sur un cycle (Verre → Ken Burns), confiée au
     * moteur d'animation : rien à recalculer image par image. Comme en mode cinéma, l'image reste
     * à sa taille réelle si « garder le zoom » est décoché, mais elle dérive alors un peu, pour ne
     * pas figer l'écran pendant des heures.
     */
    private void kenBurns(boolean forward) {
        if (!running) return;
        float amp = settings.kenBurnsOn() ? settings.kbAmplitude.f() : 0.3f;
        float zoom = settings.cinemaKeepZoom.value ? 1f : 0.35f;
        float base = (0.04f + 0.06f * Math.min(1f, amp)) * zoom;
        float t = forward ? 1f : 0f;
        float s = 1f + base + 0.03f * t * amp * zoom;
        long half = Math.max(10_000L, settings.kbCycle.value() * 500L);
        wallInner.animate()
                .scaleX(s).scaleY(s)
                .translationX((t - 0.5f) * dp(24) * Math.max(amp, 0.3f) * zoom)
                .translationY((0.5f - t) * dp(12) * Math.max(amp, 0.3f) * zoom)
                .setStartDelay(0).setDuration(half)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .withEndAction(() -> kenBurns(!forward))
                .start();
    }

    // ---------------------------------------------------------------- Fonds

    /** Même liste et même position que l'accueil : l'écran de veille reprend là où il en était. */
    private void loadWallpaper(boolean next) {
        boolean sequential = settings.bgOrder.index == Settings.ORDER_SEQUENTIAL;
        io.execute(() -> {
            try {
                Wallpaper.Source.playlistIds = settings.activeIds();
                Wallpaper.Source src = settings.bgSource.index == Settings.SOURCE_PLAYLISTS
                        && Wallpaper.count(this, Wallpaper.Source.PLAYLISTS) > 0
                        ? Wallpaper.Source.PLAYLISTS : Wallpaper.Source.BING;
                String pref = "wall_" + src.dir;
                List<Wallpaper.Item> items = Wallpaper.items(this, src);
                int n = items.size();
                if (n == 0) return;
                int cur = Math.floorMod(prefs.getInt(pref, 0), n), idx = cur;
                if (next) {
                    idx = sequential || src == Wallpaper.Source.BING || n == 1
                            ? (cur + 1) % n : (cur + 1 + random.nextInt(n - 1)) % n;
                    prefs.edit().putInt(pref, idx).apply();
                }
                Wallpaper.Frame f = Wallpaper.decode(items.get(idx));
                ui.post(() -> { if (running) show(f); });
            } catch (Exception e) {
                Log.w(TAG, "Fond indisponible (écran de veille)", e);
            }
        });
    }

    /** Fondu enchaîné vers le nouveau fond ; la carte titre s'efface puis revient. */
    private void show(Wallpaper.Frame f) {
        Wallpaper.Frame old = frame;
        frame = f;
        float k = settings.vignette.value ? settings.vignetteIntensity.f() : 0f;
        shadeTop.animate().alpha(Math.min(1f, HomeBackdrop.vignette(f.lumTop) * k)).setDuration(800).start();
        if (old == null) {
            wallImg.setImageBitmap(f.sharp);
            wallInner.animate().alpha(1f).setDuration(1500).start();
            fillTitleCard(f);
            showTitle(1800);
            return;
        }
        long ms = Math.max(800, settings.bgTransitionMs.value);
        fadeImg.animate().cancel();
        fadeImg.setImageBitmap(old.sharp);
        fadeImg.setAlpha(1f);
        wallImg.setImageBitmap(f.sharp);
        fadeImg.animate().alpha(0f).setDuration(ms).setInterpolator(ease)
                .withEndAction(() -> fadeImg.setImageDrawable(null)).start();
        titleCard.animate().alpha(0f).setStartDelay(0).setDuration(250).withEndAction(() -> {
            fillTitleCard(f);
            showTitle(0);
        }).start();
    }

    private void showTitle(long delay) {
        if (settings.titleMode.index == Settings.TITLE_NONE) return;
        titleCard.animate().alpha(1f).setStartDelay(delay).setDuration(900).withEndAction(null).start();
    }

    /** Logo du film s'il existe, sinon le titre en grand ; année ou crédit dessous (comme l'accueil). */
    private void fillTitleCard(Wallpaper.Frame f) {
        String title = f.title.isEmpty() ? f.copyright : f.title;
        java.util.regex.Matcher year = java.util.regex.Pattern.compile("^(.*?)\\s*\\((\\d{4})\\)").matcher(title);
        boolean hasYear = year.find();
        int mode = settings.titleMode.index;
        boolean useLogo = mode == Settings.TITLE_LOGO && f.logo != null;
        titleLogo.setVisibility(useLogo ? View.VISIBLE : View.GONE);
        if (useLogo) titleLogo.setImageBitmap(f.logo);
        titleText.setVisibility(!useLogo && mode != Settings.TITLE_NONE ? View.VISIBLE : View.GONE);
        titleText.setText(hasYear ? year.group(1) : title);
        String sub = hasYear ? (settings.showYear.value ? year.group(2) : "") : (f.title.isEmpty() ? "" : f.copyright);
        if (mode == Settings.TITLE_NONE) sub = "";
        titleSub.setText(sub.toUpperCase(Lang.locale(this)));
        boolean infoLine = filmCard.fill(f.film, hasYear ? year.group(2) : "", settings);
        titleSub.setVisibility(sub.isEmpty() || infoLine ? View.GONE : View.VISIBLE);
    }

    // ---------------------------------------------------------------- Aides

    private void style(TextView t, float sp, int color) {
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setShadowLayer(dp(6), 0, dp(1), 0x66000000);
    }

    private FrameLayout.LayoutParams at(int gravity, int l, int t, int r, int b) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = gravity;
        lp.setMargins(dp(l), dp(t), dp(r), dp(b));
        return lp;
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private int dp(float v) {
        return Math.round(v * density);
    }
}
