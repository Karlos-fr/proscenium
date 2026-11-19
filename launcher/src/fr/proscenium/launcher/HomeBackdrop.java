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
 * Plan du fond : animations d'entrée, mode cinéma (bandes, carte titre, dézoom), Ken Burns,
 * parallaxe, rotation et transitions des fonds, vignettage et couleur d'accent.
 */
abstract class HomeBackdrop extends HomeBase {
    // ---------------------------------------------------------------- Animations d'ambiance

    /** Entrée : le fond dézoome doucement, le dock monte en fondu. */
    void appear() {
        wallOuter.setScaleX(1.06f);
        wallOuter.setScaleY(1.06f);
        wallOuter.animate().scaleX(1f).scaleY(1f).setDuration(1100).setInterpolator(ease)
                .setUpdateListener(a -> invalidateGlasses()).start();
        mid.setAlpha(0f);
        mid.setTranslationY(dp(26));
        mid.animate().alpha(1f).translationY(0).setStartDelay(150).setDuration(500).setInterpolator(ease)
                .setUpdateListener(null).start();
        front.setAlpha(0f);
        front.animate().alpha(1f).setDuration(400).start();
        header.setAlpha(1f);
        cinema(false, 0);
        if (settings.entrance.value && !panel.isOpen()) {
            // Entrée cinématique : la photo arrive floue puis devient nette pendant que l'heure et le dock apparaissent
            dofImg.animate().cancel();
            dofImg.setAlpha(1f);
            dofImg.animate().alpha(0f).setStartDelay(100).setDuration(750).setInterpolator(ease).start();
        }
        // Un reflet traverse lentement le verre du dock puis celui du bandeau
        dock.sweep(450, 1800);
        header.sweep(800, 1600);
        idle = false;
        scheduleIdle();
    }

    void scheduleIdle() {
        ui.removeCallbacks(goIdle);
        int delay = settings.cinemaDelay.value();
        if (delay > 0 && !panel.isOpen()) ui.postDelayed(goIdle, delay * 1000L);
    }

    // ---------------------------------------------------------------- Mode cinéma

    void buildCinema(FrameLayout root) {
        barTop = new View(this);
        barTop.setBackgroundColor(Color.BLACK);
        FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30));
        topLp.gravity = Gravity.TOP;
        root.addView(barTop, topLp);
        barBottom = new View(this);
        barBottom.setBackgroundColor(Color.BLACK);
        FrameLayout.LayoutParams botLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30));
        botLp.gravity = Gravity.BOTTOM;
        root.addView(barBottom, botLp);

        titleCard = new LinearLayout(this);
        titleCard.setOrientation(LinearLayout.VERTICAL);
        titleCard.setAlpha(0f);
        titleLogo = new ImageView(this);
        titleLogo.setAdjustViewBounds(true);
        titleLogo.setMaxWidth(dp(380));
        titleLogo.setMaxHeight(dp(140));
        titleCard.addView(titleLogo, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        titleText = text(34, Color.WHITE, false);
        titleText.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        titleText.setMaxWidth(dp(520));
        titleCard.addView(titleText);
        titleSub = text(14, 0xCCFFFFFF, false);
        titleSub.setLetterSpacing(0.15f);
        titleSub.setPadding(dp(2), dp(8), dp(2), 0);
        titleCard.addView(titleSub);
        filmCard = new FilmCard(this);
        filmCard.attach(titleCard, settings.titleSide.index == Settings.SIDE_LEFT ? Gravity.START : Gravity.END);
        root.addView(titleCard, at(Gravity.BOTTOM | Gravity.END, 0, 0, 56, 52));
    }

    /** Hauteur des bandes (aucune, 2:1, 2,39:1), leur opacité, et côté de la carte titre. */
    void applyCinemaLayout() {
        int h = barHeight();
        for (View bar : new View[]{barTop, barBottom}) {
            ViewGroup.LayoutParams lp = bar.getLayoutParams();
            lp.height = Math.max(1, h);
            bar.setLayoutParams(lp);
            bar.setAlpha(settings.barOpacity.f());
            if (!idle) bar.setTranslationY(bar == barTop ? -h - 1 : h + 1);
        }
        boolean left = settings.titleSide.index == Settings.SIDE_LEFT;
        int side = left ? Gravity.START : Gravity.END;
        titleCard.setGravity(side);
        titleLogo.setScaleType(left ? ImageView.ScaleType.FIT_START : ImageView.ScaleType.FIT_END);
        titleText.setGravity(side);
        // Logo, titre et année calés du même côté
        for (View child : new View[]{titleLogo, titleText, titleSub}) {
            LinearLayout.LayoutParams clp = (LinearLayout.LayoutParams) child.getLayoutParams();
            clp.gravity = side;
            child.setLayoutParams(clp);
        }
        filmCard.setSide(side);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) titleCard.getLayoutParams();
        lp.gravity = Gravity.BOTTOM | side;
        lp.setMargins(dp(56), 0, dp(56), Math.max(h, dp(30)) + dp(22));
        titleCard.setLayoutParams(lp);
    }

    int barHeight() {
        switch (settings.cinemaBars.index) {
            case Settings.BARS_NONE: return 0;
            case Settings.BARS_SCOPE: return dp(69);
            default: return dp(30);
        }
    }

    /** Remplit la carte titre : logo du film s'il existe, sinon le titre en grand ; année ou crédit dessous. */
    void fillTitleCard(Wallpaper.Frame f) {
        String title = f.title.isEmpty() ? f.copyright : f.title;
        java.util.regex.Matcher year = java.util.regex.Pattern.compile("^(.*?)\\s*\\((\\d{4})\\)").matcher(title);
        boolean hasYear = year.find();
        String name = hasYear ? year.group(1) : title;
        int mode = settings.titleMode.index;
        boolean useLogo = mode == Settings.TITLE_LOGO && f.logo != null;
        titleLogo.setVisibility(useLogo ? View.VISIBLE : View.GONE);
        if (useLogo) titleLogo.setImageBitmap(f.logo);
        titleText.setVisibility(!useLogo && mode != Settings.TITLE_NONE ? View.VISIBLE : View.GONE);
        titleText.setText(name);
        String sub = hasYear ? (settings.showYear.value ? year.group(2) : "") : (f.title.isEmpty() ? "" : f.copyright);
        if (mode == Settings.TITLE_NONE) sub = "";
        titleSub.setText(sub.toUpperCase(Lang.locale(this)));
        // Fiche du film : sa ligne d'infos remplace celle de l'année
        boolean infoLine = filmCard.fill(f.film, hasYear ? year.group(2) : "", settings);
        titleSub.setVisibility(sub.isEmpty() || infoLine ? View.GONE : View.VISIBLE);
    }

    /** Entrée (au repos) ou sortie du mode cinéma. */
    void cinema(boolean on, long delay) {
        int bar = barHeight();
        clock.showSeconds(on && settings.clockSeconds.value);
        // L'image recule à sa taille réelle et se recentre : les bandes ne rognent presque plus rien
        boolean dezoom = !settings.cinemaKeepZoom.value;
        if (dezoom || !on) animateZoom(on ? 0f : 1f, on ? delay : 0, on ? 1800 : 500);
        if (on && dezoom) {
            wallOuter.animate().translationX(0).translationY(0).setStartDelay(delay).setDuration(1800)
                    .setInterpolator(ease).setUpdateListener(a -> invalidateGlasses()).start();
        }
        barTop.animate().translationY(on ? 0 : -bar - 1).setStartDelay(delay).setDuration(on ? 1400 : 450).setInterpolator(ease).start();
        barBottom.animate().translationY(on ? 0 : bar + 1).setStartDelay(delay).setDuration(on ? 1400 : 450).setInterpolator(ease).start();
        boolean left = settings.titleSide.index == Settings.SIDE_LEFT;
        if (on && settings.titleMode.index != Settings.TITLE_NONE) {
            titleCard.setTranslationX(dp(left ? -24 : 24));
            titleCard.animate().alpha(1f).translationX(0).setStartDelay(delay + 900).setDuration(1200).setInterpolator(ease).start();
        } else {
            titleCard.animate().alpha(0f).translationX(0).setStartDelay(0).setDuration(300).start();
        }
    }

    /** Profondeur de champ : le fond passe hors focus quand le panneau s'ouvre, puis redevient net. */
    void menuBlur(boolean on) {
        dofImg.animate().alpha(on ? DOF_ALPHA : 0f).setStartDelay(0).setDuration(on ? 450 : 600).setInterpolator(ease).start();
    }

    /** Mode repos : il ne reste que la photo et l'heure (et le bandeau si on le garde). */
    final Runnable goIdle = () -> {
        if (panel.isOpen() || this.appMenu != null || this.moveMode
                || (this.shortcutEditor != null && this.shortcutEditor.isOpen())) return;
        idle = true;
        mid.animate().alpha(0f).setStartDelay(0).setDuration(1400).setUpdateListener(null).start();
        if (settings.hideHeader.value) header.animate().alpha(0f).setDuration(1400).start();
        cinema(true, 600);
    };

    void wake() {
        ui.removeCallbacks(goIdle);
        if (idle) {
            idle = false;
            mid.animate().alpha(1f).setStartDelay(0).setDuration(350).setUpdateListener(null).start();
            header.animate().alpha(1f).setDuration(350).start();
            cinema(false, 0);
            // Le verre n'était plus calculé pendant le mode cinéma : on le remet à jour dès que le dock réapparaît
            ui.postDelayed(() -> glassCtl.requestCapture(), 90);
        }
        scheduleIdle();
    }

    // ---------------------------------------------------------------- Ken Burns et parallaxe

    final Runnable kenBurns = new Runnable() {
        @Override public void run() {
            if (settings.kenBurnsOn()) {
                long cycle = settings.kbCycle.value() * 1000L;
                float phase = (SystemClock.uptimeMillis() % cycle) / (float) cycle;
                float tri = phase < 0.5f ? phase * 2 : 2 - phase * 2;
                setKenBurns((1 - (float) Math.cos(Math.PI * tri)) / 2); // adouci aux extrémités
                // L'icône météo avance au même rythme : pas d'image en plus
                weatherIcon.setExternalClock(true);
                weatherIcon.tick();
                glow.setExternalClock(true);
                glow.tick();
                ui.postDelayed(this, kenBurnsTick);
            } else {
                // Sans Ken Burns, l'icône météo reprend sa propre horloge
                weatherIcon.setExternalClock(false);
                glow.setExternalClock(false);
            }
        }
    };



    void setKenBurns(float t) {
        kenBurnsT = t;
        // L'amplitude règle le zoom de base et la dérive ; kenBurnsAmount = 0 en mode cinéma (image à 1,0x)
        float amp = settings.kenBurnsOn() ? settings.kbAmplitude.f() : 0.3f;
        float base = 0.04f + 0.06f * Math.min(1f, amp);
        float s = 1f + (base + 0.03f * t * amp) * kenBurnsAmount;
        wallInner.setScaleX(s);
        wallInner.setScaleY(s);
        wallInner.setTranslationX((t - 0.5f) * dp(24) * amp * kenBurnsAmount);
        wallInner.setTranslationY((0.5f - t) * dp(12) * amp * kenBurnsAmount);
        invalidateGlasses(zoomAnim == null || !zoomAnim.isRunning());
    }

    /** Fait reculer (0) ou revenir (1) le zoom du fond, comme une caméra qui élargit le cadre. */
    void animateZoom(float to, long delay, long duration) {
        if (zoomAnim != null) zoomAnim.cancel();
        zoomAnim = ValueAnimator.ofFloat(kenBurnsAmount, to);
        zoomAnim.setStartDelay(delay);
        zoomAnim.setDuration(duration);
        zoomAnim.setInterpolator(ease);
        zoomAnim.addUpdateListener(a -> {
            kenBurnsAmount = (float) a.getAnimatedValue();
            setKenBurns(kenBurnsT);
        });
        zoomAnim.start();
    }

    /** Parallaxe : le fond se décale à l'opposé de la sélection, le dock un peu, le haut à peine. */
    void parallax(View focused) {
        int[] p = new int[2];
        focused.getLocationOnScreen(p);
        float half = getWindow().getDecorView().getWidth() / 2f;
        // Avant la première mise en page, la largeur vaut 0 : une division par zéro donnerait NaN
        if (half <= 0 || focused.getWidth() == 0) return;
        float nx = (p[0] + focused.getWidth() / 2f - half) / half;
        if (Float.isNaN(nx)) return;
        nx = Math.max(-1f, Math.min(1f, nx));
        float amount = settings.parallaxAmount();
        float row = focused == track ? 0f : -0.6f;
        wallOuter.animate().translationX(-nx * dp(22) * amount).translationY(-row * dp(10) * amount)
                .setDuration(750).setInterpolator(ease).setUpdateListener(a -> invalidateGlasses()).start();
        mid.animate().translationX(-nx * dp(7) * amount).setStartDelay(0).setDuration(750).setInterpolator(ease).start();
        front.animate().translationX(-nx * dp(3) * amount).setDuration(750).setInterpolator(ease).start();
    }

    // ---------------------------------------------------------------- Fond d'écran

    final Runnable rotate = new Runnable() {
        @Override public void run() {
            loadWallpaper(NEXT);
            ui.postDelayed(this, rotateDelay());
        }
    };

    static final int CURRENT = 0, NEXT = 1, SWITCH = 2;

    void scheduleRotate() {
        ui.removeCallbacks(rotate);
        ui.postDelayed(rotate, rotateDelay());
    }

    long rotateDelay() {
        return settings.bgDelay.value() * 1000L;
    }

    /** Playlists cochées, ou Bing si aucune n'a d'image. Hors du fil principal. */
    Wallpaper.Source source() {
        Wallpaper.Source.playlistIds = settings.activeIds();
        if (settings.bgSource.index == Settings.SOURCE_PLAYLISTS && Wallpaper.count(this, Wallpaper.Source.PLAYLISTS) > 0) {
            return Wallpaper.Source.PLAYLISTS;
        }
        return Wallpaper.Source.BING;
    }

    /**
     * CURRENT : remet la photo en cours (au lancement) ; NEXT : photo suivante ;
     * SWITCH : changement de source. Tout se passe en arrière-plan.
     */
    void loadWallpaper(int mode) {
        boolean hasFrame = frame != null;
        boolean sequential = settings.bgOrder.index == Settings.ORDER_SEQUENTIAL;
        io.execute(() -> {
            try {
                Wallpaper.Source src = source();
                String pref = "wall_" + src.dir;
                List<Wallpaper.Item> items = Wallpaper.items(this, src);
                // La photo en cache s'affiche tout de suite, avant tout téléchargement
                boolean shown = hasFrame && mode == CURRENT;
                if (!shown && mode != NEXT && !items.isEmpty()) {
                    Wallpaper.Frame cached = Wallpaper.decode(items.get(Math.floorMod(prefs.getInt(pref, 0), items.size())));
                    ui.post(() -> showFrame(cached));
                    shown = true;
                }
                boolean changed = Wallpaper.refresh(this, src);
                if (shown && !changed) return;
                items = Wallpaper.items(this, src);
                int n = items.size();
                if (n == 0) return;
                int cur = Math.floorMod(prefs.getInt(pref, 0), n);
                int idx;
                if (changed && src == Wallpaper.Source.BING) idx = 0;
                else if (sequential || src == Wallpaper.Source.BING) idx = (cur + 1) % n;
                else idx = n == 1 ? 0 : (cur + 1 + random.nextInt(n - 1)) % n;
                prefs.edit().putInt(pref, idx).apply();
                Wallpaper.Frame f = Wallpaper.decode(items.get(idx));
                ui.post(() -> showFrame(f));
            } catch (Exception e) {
                Log.w(TAG, "Fond indisponible", e);
            }
        });
    }

    /** Transition réglable : flou et zoom, fondu, glissement ou aucune. */
    void showFrame(Wallpaper.Frame f) {
        Wallpaper.Frame old = frame;
        frame = f;
        ProsceniumRemote remote = ProsceniumRemote.get();
        if (remote != null) remote.frameChanged();
        frameWeather();
        long ms = settings.bgTransitionMs.value;
        if (old == null) {
            wallImg.setImageBitmap(f.sharp);
            applyFrame(f);
            animateAccent(themeColor(f));
            return;
        }
        // La couleur du nouveau fond envahit le verre avant même que l'image apparaisse
        animateAccent(themeColor(f));
        blurImg.animate().cancel();
        blurImg.setTranslationX(0);
        switch (settings.bgTransition.index) {
            case Settings.TRANSITION_NONE:
                wallImg.setImageBitmap(f.sharp);
                applyFrame(f);
                break;
            case Settings.TRANSITION_FADE:
            case Settings.TRANSITION_SLIDE:
                // L'ancienne image passe devant, puis s'efface (ou glisse hors de l'écran)
                boolean slide = settings.bgTransition.index == Settings.TRANSITION_SLIDE;
                blurImg.setImageBitmap(old.sharp);
                blurImg.setAlpha(1f);
                wallImg.setImageBitmap(f.sharp);
                applyFrame(f);
                blurImg.animate().alpha(slide ? 1f : 0f).translationX(slide ? -wallInner.getWidth() : 0)
                        .setStartDelay(0).setDuration(ms).setInterpolator(ease).withEndAction(() -> {
                            blurImg.setAlpha(0f);
                            blurImg.setTranslationX(0);
                        }).start();
                break;
            default:
                // Flou et zoom : l'image actuelle se floute, la nouvelle se révèle avec un léger zoom
                blurImg.setImageBitmap(old.blurred);
                blurImg.setScaleType(ImageView.ScaleType.FIT_XY);
                blurImg.animate().alpha(1f).setStartDelay(0).setDuration(ms * 3 / 8).setInterpolator(ease).withEndAction(() -> {
                    wallImg.setImageBitmap(f.sharp);
                    blurImg.setImageBitmap(f.blurred);
                    applyFrame(f);
                    wallImg.setScaleX(1.04f);
                    wallImg.setScaleY(1.04f);
                    wallImg.animate().scaleX(1f).scaleY(1f).setDuration(ms + ms / 8).setInterpolator(ease).start();
                    blurImg.animate().alpha(0f).setStartDelay(80).setDuration(ms * 5 / 8).setInterpolator(ease)
                            .withEndAction(() -> blurImg.setScaleType(ImageView.ScaleType.CENTER_CROP)).start();
                }).start();
                break;
        }
    }

    /** Couleur du thème : celle de l'image, ou la couleur fixe choisie. */
    int themeColor(Wallpaper.Frame f) {
        return settings.themeAuto.value ? f.accent : Settings.THEME_COLORS[settings.themeColor.index];
    }

    /**
     * Assombrit seulement ce qui en a besoin : une image claire derrière l'heure ou le dock
     * reçoit un dégradé plus marqué, une image sombre presque rien.
     */
    void applyVignette(Wallpaper.Frame f) {
        float k = settings.vignette.value ? settings.vignetteIntensity.f() : 0f;
        shadeTop.animate().alpha(Math.min(1f, vignette(f.lumTop) * k)).setDuration(800).start();
        shadeBottom.animate().alpha(Math.min(1f, vignette(f.lumBottom) * k)).setDuration(800).start();
    }

    static float vignette(float lum) {
        return Math.max(0.15f, Math.min(1f, 0.15f + (lum - 0.2f) * 1.6f));
    }

    void applyFrame(Wallpaper.Frame f) {
        GlassView.blurred = f.blurred;
        dofImg.setImageBitmap(f.blurred);
        applyVignette(f);
        if (idle) {
            // En mode cinéma, la carte titre s'efface puis revient avec le nouveau film
            titleCard.animate().alpha(0f).setStartDelay(0).setDuration(250).withEndAction(() -> {
                fillTitleCard(f);
                if (settings.titleMode.index != Settings.TITLE_NONE) {
                    titleCard.animate().alpha(1f).setDuration(700).withEndAction(null).start();
                }
            }).start();
        } else {
            fillTitleCard(f);
        }
        glassCtl.requestCapture();
    }

    /** La couleur d'accent teinte le verre, les icônes, la lueur et le panneau. */
    void animateAccent(int to) {
        if (accentAnim != null) accentAnim.cancel();
        accentAnim = ValueAnimator.ofObject(new ArgbEvaluator(), accent, to);
        accentAnim.setDuration(700);
        accentAnim.addUpdateListener(a -> {
            accent = (int) a.getAnimatedValue();
            GlassView.accent = accent;
            applyAccent();
            invalidateGlasses();
        });
        accentAnim.start();
    }

    void applyAccent() {
        int soft = GlassView.mix(accent, Color.WHITE, 0.3f);
        int light = GlassView.mix(accent, Color.WHITE, 0.55f);
        cpuIcon.setColor(soft);
        ramIcon.setColor(soft);
        updateGlowColor();
        for (GradientDrawable r : rings) r.setStroke(ringWidth(), (light & 0x00FFFFFF) | 0xF0000000);
        if (panel != null && panel.isOpen()) panel.setAccent(accent);
    }
}
