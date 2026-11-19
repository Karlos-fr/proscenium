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
 * Haut de l'écran : heure qui roule, date, et bandeau en verre (CPU, RAM, météo)
 * qui ouvre le panneau de réglages.
 */
abstract class HomeHeader extends HomeBackdrop {

    // ---------------------------------------------------------------- Haut de l'écran

    /** Vérifie l'heure chaque seconde ; seuls les chiffres qui changent glissent. */
    final Runnable clockTick = new Runnable() {
        @Override public void run() {
            clock.setTime(clockFormat.format(new Date()), true);
            ui.postDelayed(this, 1000 - System.currentTimeMillis() % 1000);
        }
    };

    View buildClock() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        clock = new RollingClock(this, hourStyle(), secondsStyle());
        clock.setTime(clockFormat.format(new Date()), false);
        box.addView(clock);
        dateText = text(18 * settings.dateSize.f(), 0xF0FFFFFF, false);
        dateText.setPadding(dp(2), dp(2), 0, 0);
        box.addView(dateText);
        return box;
    }

    /** Typographie très fine (Roboto Thin), à la taille réglée (Accueil → Taille de l'heure). */
    RollingClock.Styler hourStyle() {
        Typeface thin = Typeface.create("sans-serif-thin", Typeface.NORMAL);
        float k = settings.clockSize.f();
        return t -> {
            styleText(t, 76 * k, Color.WHITE, false);
            t.setTypeface(thin);
            t.setIncludeFontPadding(false);
            // Chiffres à chasse fixe : l'heure ne « danse » pas quand un 1 devient un 4
            t.setFontFeatureSettings("tnum");
        };
    }

    /** Les secondes, plus petites, apparaissent au repos ; elles suivent la taille de l'heure. */
    RollingClock.Styler secondsStyle() {
        Typeface thin = Typeface.create("sans-serif-thin", Typeface.NORMAL);
        float k = settings.clockSize.f();
        return t -> {
            styleText(t, 30 * k, 0xCCFFFFFF, false);
            t.setTypeface(thin);
            t.setIncludeFontPadding(false);
            t.setFontFeatureSettings("tnum");
            t.setPadding(0, 0, 0, dp(10 * k));
        };
    }

    /** Aperçu en direct des tailles de l'heure et de la date. */
    void applyClockSizes() {
        clock.restyle(hourStyle(), secondsStyle());
        dateText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18 * settings.dateSize.f());
    }

    /**
     * Bandeau en verre, du même verre que la barre d'applis (teinté par la couleur du fond) :
     * CPU, RAM | météo. Sélectionnable, il ouvre le panneau de réglages.
     */
    View buildHeader() {
        final float s = settings.headerScale.f();
        GlassView bar = new GlassView(this, dp(18 * s));
        glasses.add(bar);
        LinearLayout content = row(Math.round(22 * s));
        content.setPadding(dp(18 * s), dp(9 * s), dp(20 * s), dp(9 * s));

        cpuIcon = new IconView(this, IconView.Kind.CPU);
        cpuValue = text(15 * s, Color.WHITE, false);
        cpuValue.setText("—");
        cpuBlock = stat(cpuIcon, getString(R.string.label_cpu), cpuValue, s);
        content.addView(cpuBlock);

        ramIcon = new IconView(this, IconView.Kind.RAM);
        ramValue = text(15 * s, Color.WHITE, false);
        ramValue.setText("—");
        ramBlock = stat(ramIcon, getString(R.string.label_ram), ramValue, s);
        content.addView(ramBlock);

        headerDivider = new View(this);
        headerDivider.setBackgroundColor(0x4DFFFFFF);
        content.addView(headerDivider, new LinearLayout.LayoutParams(dp(1), dp(34 * s)));

        LinearLayout weather = row(Math.round(10 * s));
        weatherIcon = new IconView(this, IconView.Kind.PARTLY_DAY);
        weatherIcon.setAnimated(settings.weatherAnimOn());
        weather.addView(weatherIcon, new LinearLayout.LayoutParams(dp(32 * s), dp(32 * s)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.END);
        cityText = text(11 * s, 0xD9FFFFFF, false);
        cityText.setText(settings.weatherCity.value);
        tempText = text(16 * s, Color.WHITE, false);
        tempText.setText("—");
        col.addView(cityText);
        col.addView(tempText);
        weather.addView(col);
        // Vent : direction et rafales en petit au-dessus, vitesse moyenne en dessous
        LinearLayout windCol = new LinearLayout(this);
        windCol.setOrientation(LinearLayout.VERTICAL);
        windCol.setGravity(Gravity.START);
        gustText = text(11 * s, 0xD9FFFFFF, false);
        gustText.setText(getString(R.string.label_wind));
        windText = text(16 * s, Color.WHITE, false);
        windText.setText("\u2014");
        windCol.addView(gustText);
        windCol.addView(windText);
        LinearLayout.LayoutParams windLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        windLp.setMarginStart(dp(8 * s));
        weather.addView(windCol, windLp);
        windBlock = windCol;
        weatherBlock = weather;
        content.addView(weather);

        bar.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        makeFocusable(bar, Math.round(18 * s), true);
        headerRing = rings.get(rings.size() - 1);
        bar.setOnFocusChangeListener((v, has) -> {
            v.animate().scaleX(has ? 1.05f : 1f).scaleY(has ? 1.05f : 1f).setDuration(220).setInterpolator(ease).start();
            if (has) parallax(v);
        });
        bar.setOnClickListener(v -> openPanel(0));
        header = bar;
        return bar;
    }

    /** CPU, RAM et météo affichés ou non ; le séparateur n'apparaît qu'entre les deux groupes. */
    void applyHeaderBlocks() {
        // Le CPU disparaît de lui-même quand le système en interdit la lecture (TV sous Android 14…)
        boolean cpu = settings.showCpu.value && cpuReadable;
        cpuBlock.setVisibility(cpu ? View.VISIBLE : View.GONE);
        ramBlock.setVisibility(settings.showRam.value ? View.VISIBLE : View.GONE);
        weatherBlock.setVisibility(settings.showWeather.value ? View.VISIBLE : View.GONE);
        windBlock.setVisibility(settings.showWind.value ? View.VISIBLE : View.GONE);
        boolean stats = cpu || settings.showRam.value;
        headerDivider.setVisibility(stats && settings.showWeather.value ? View.VISIBLE : View.GONE);
        header.setVisibility(stats || settings.showWeather.value ? View.VISIBLE : View.INVISIBLE);
    }

    LinearLayout stat(IconView icon, String label, TextView value, float s) {
        LinearLayout block = row(Math.round(10 * s));
        block.addView(icon, new LinearLayout.LayoutParams(dp(22 * s), dp(22 * s)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView l = text(11 * s, 0xD9FFFFFF, false);
        l.setText(label);
        col.addView(l);
        col.addView(value);
        block.addView(col);
        return block;
    }

    final Runnable statsTick = new Runnable() {
        @Override public void run() {
            int c = stats.cpuPercent();
            cpuValue.setText(c < 0 ? "—" : c + "%");
            ramValue.setText(Stats.ram(HomeHeader.this));
            String d = dateFormat.format(new Date());
            dateText.setText(Character.toUpperCase(d.charAt(0)) + d.substring(1));
            ui.postDelayed(this, STATS_EVERY_MS);
        }
    };

    /**
     * Météo sur le fond : réelle (dernier relevé), coupée (réglage, mode économie) ou personnalisée
     * (couches cochées, cumulables). Pendant le réglage d'une page météo, sa couche seule s'affiche.
     */
    void applyWeatherFx() {
        if (weatherFx == null) return;
        Settings s = settings;
        int layers = WeatherFx.NONE;
        float amount = 0.85f;
        boolean custom = s.weatherFx.index == Settings.FX_CUSTOM;
        boolean auto = s.weatherFx.index == Settings.FX_RANDOM || s.weatherFx.index == Settings.FX_SMART;
        WeatherPicker.Pick pick = auto ? autoPick() : null;
        if (weatherPreview >= 0) {
            layers = weatherPreview;
            amount = 1f;
        } else if (!s.economy.value) {
            if (s.weatherFx.index == Settings.FX_REAL && lastWeather != null) {
                layers = WeatherFx.layersOf(lastWeather.code, lastWeather.day);
                amount = WeatherFx.amountOf(lastWeather.code);
                // Peu nuageux : un soleil plus discret
                if (lastWeather.code == 2) amount *= 0.6f;
            } else if (custom) {
                if (s.fxRain.value) layers |= WeatherFx.RAIN;
                if (s.fxLightning.value) layers |= WeatherFx.STORM;
                if (s.fxSnow.value) layers |= WeatherFx.SNOW;
                if (s.fxFog.value) layers |= WeatherFx.FOG;
                if (s.fxSun.value) layers |= WeatherFx.SUN;
                if (s.fxLightning.value) amount = 1f;
            } else if (pick != null) {
                layers = pick.layers;
                amount = pick.amount;
            }
        }
        // Vent affiché dans le bandeau (en mph avec les °F, ramené à des km/h) : il pousse les gouttes
        // qui glissent, rafales comprises. La météo personnalisée peut imposer le sien (vent d'ouest
        // si la ville n'a pas encore de relevé).
        int wind = 0, gusts = 0, from = 270;
        if (lastWeather != null) {
            boolean mph = s.tempUnit.index == Settings.UNIT_F;
            wind = mph ? Math.round(lastWeather.wind * 1.609f) : lastWeather.wind;
            gusts = mph ? Math.round(lastWeather.gusts * 1.609f) : lastWeather.gusts;
            from = lastWeather.windFrom;
        }
        if (pick != null) {
            // Vent tiré avec la météo ; il souffle de la direction réelle, ou de l'ouest
            wind = pick.wind;
            gusts = pick.gusts;
            if (lastWeather == null || Math.abs(Math.sin(Math.toRadians(from))) < 0.3) from = 270;
        } else if (custom) {
            switch (s.fxWind.index) {
                case Settings.WIND_NONE: wind = 0; gusts = 0; break;
                case Settings.WIND_LIGHT: wind = 15; gusts = 25; break;
                case Settings.WIND_STRONG: wind = 40; gusts = 65; break;
                case Settings.WIND_STORM: wind = 70; gusts = 110; break;
                default: break;
            }
            if (s.fxWind.index >= Settings.WIND_LIGHT && Math.abs(Math.sin(Math.toRadians(from))) < 0.3) from = 270;
        }
        float tilt = WeatherFx.tiltOf(wind, from);
        float gust = wind > 0 ? Math.max(1f, gusts / (float) wind) : 1f;
        weatherFx.set(layers, amount * (weatherPreview >= 0 ? 1f : s.weatherFxAmount.f()), tilt, gust,
                weatherPreview >= 0 || s.weatherLens.value);
    }

    // ---------------------------------------------------------------- Météo Aléatoire / Selon le fond

    /** Météo choisie toute seule, le fond pour lequel elle l'a été, le mode et l'heure du choix. */
    private WeatherPicker.Pick autoPick;
    private Wallpaper.Frame autoFrame;
    private int autoMode = -1;
    private long autoAt;
    private final java.util.Random autoRandom = new java.util.Random();
    /** Aléatoire : un nouveau tirage au changement de fond, au plus toutes les deux minutes. */
    private static final long RANDOM_EVERY_MS = 2 * 60_000L;

    /** La météo du moment : gardée, ou choisie de nouveau pour ce fond. */
    WeatherPicker.Pick autoPick() {
        int mode = settings.weatherFx.index;
        long now = System.currentTimeMillis();
        boolean stale = autoPick == null || autoMode != mode
                || (frame != autoFrame && (mode == Settings.FX_SMART || now - autoAt >= RANDOM_EVERY_MS));
        if (!stale) return autoPick;
        autoMode = mode;
        autoFrame = frame;
        autoAt = now;
        if (mode == Settings.FX_RANDOM || frame == null) {
            autoPick = WeatherPicker.random(autoRandom);
        } else {
            Playlists.Film film = frame.film;
            String genres = film == null ? "" : film.genres;
            String text = film == null ? frame.title
                    : film.title + " " + film.customTitle + " " + film.tagline + " " + film.overview;
            autoPick = WeatherPicker.smart(genres, text, frame.lumTop, frame.lumBottom, frame.accent, autoRandom);
        }
        Log.d(TAG, "Météo " + (mode == Settings.FX_RANDOM ? "aléatoire" : "selon le fond") + " : "
                + (frame == null ? "?" : frame.title) + " -> couches " + autoPick.layers + ", force " + autoPick.amount);
        return autoPick;
    }

    @Override
    void frameWeather() {
        int mode = settings.weatherFx.index;
        if (mode == Settings.FX_RANDOM || mode == Settings.FX_SMART) applyWeatherFx();
    }

    void refreshWeather() {
        weatherAt = System.currentTimeMillis();
        String coords = settings.weatherCoords.value;
        boolean fahrenheit = settings.tempUnit.index == Settings.UNIT_F;
        net.execute(() -> {
            try {
                Weather.Now w = Weather.fetch(coords, fahrenheit);
                ui.post(() -> {
                    lastWeather = w;
                    applyWeatherFx();
                    weatherIcon.setKind(w.icon());
                    tempText.setText(w.temp + (fahrenheit ? "°F" : "°C"));
                    windText.setText(w.wind + (fahrenheit ? " mph" : " km/h"));
                    gustText.setText(getString(R.string.label_wind_gusts, w.windArrow(), w.gusts));
                });
            } catch (Exception e) {
                Log.w(TAG, "Météo indisponible", e);
                weatherAt = 0;
            }
        });
    }
}
