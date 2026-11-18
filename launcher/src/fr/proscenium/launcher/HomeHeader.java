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

    /** Météo sur le fond : réelle (dernier relevé), coupée (réglage, mode économie) ou imposée. */
    void applyWeatherFx() {
        if (weatherFx == null) return;
        Settings s = settings;
        int kind = WeatherFx.NONE;
        float amount = 0.85f;
        if (weatherPreview >= 0) {
            // Page de réglage d'une météo ouverte : elle s'affiche, quels que soient les réglages
            kind = weatherPreview;
            amount = 1f;
        } else if (!s.economy.value) {
            switch (s.weatherFx.index) {
                case Settings.FX_REAL:
                    if (lastWeather != null) {
                        kind = WeatherFx.kindOf(lastWeather.code);
                        amount = WeatherFx.amountOf(lastWeather.code);
                    }
                    break;
                case Settings.FX_RAIN: kind = WeatherFx.RAIN; break;
                case Settings.FX_STORM: kind = WeatherFx.STORM; amount = 1f; break;
                case Settings.FX_SNOW: kind = WeatherFx.SNOW; break;
                case Settings.FX_FOG: kind = WeatherFx.FOG; break;
                default: break;
            }
        }
        // Vent : en mph avec les °F, ramené à des km/h
        // Vent affiché dans le bandeau : il pousse les gouttes qui glissent, rafales comprises
        float tilt = -0.1f, gust = 1f;
        if (lastWeather != null) {
            int wind = s.tempUnit.index == Settings.UNIT_F ? Math.round(lastWeather.wind * 1.609f) : lastWeather.wind;
            tilt = WeatherFx.tiltOf(wind, lastWeather.windFrom);
            gust = Math.max(1f, lastWeather.gusts / (float) Math.max(1, lastWeather.wind));
        }
        weatherFx.set(kind, amount * (weatherPreview >= 0 ? 1f : s.weatherFxAmount.f()), tilt, gust,
                weatherPreview >= 0 || s.weatherLens.value);
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
