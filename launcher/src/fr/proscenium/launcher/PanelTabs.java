package fr.proscenium.launcher;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


/**
 * Onglets de réglages simples : Arrière-plans, Mode cinéma, Barre d'apps (avec la page des applis),
 * Verre, Accueil et Système. Chaque changement est sauvegardé et appliqué tout de suite.
 */
abstract class PanelTabs extends PanelCore {
    PanelTabs(Context ctx, Host host, Settings settings, GlassSettings glass) {
        super(ctx, host, settings, glass);
    }

    /** Onglets Playlists et Films (TMDB), construits dans SettingsPanel. */
    abstract void buildPlaylists();

    abstract void buildTmdb(List<Settings.Item> r);


    // ---------------------------------------------------------------- Contenu des onglets

    void showTab(int tab) {
        currentTab = tab;
        for (int i = 0; i < tabViews.size(); i++) styleTab(i);
        pages.clear();
        returnFocus.clear();
        clearContent();
        List<Settings.Item> resettable = new ArrayList<>();
        switch (tab) {
            case TAB_BACKDROPS: buildBackdrops(resettable); break;
            case TAB_PLAYLISTS: buildPlaylists(); break;
            case TAB_TMDB: buildTmdb(resettable); break;
            case TAB_CINEMA: buildCinema(resettable); break;
            case TAB_DOCK: buildDock(resettable); break;
            case TAB_GLASS: buildGlass(resettable); break;
            case TAB_HOME: buildHome(resettable); break;
            default: buildSystem(); return;
        }
        if (!resettable.isEmpty() || tab == TAB_GLASS) {
            section(0);
            content.addView(confirmAction(R.string.act_reset_tab, R.string.help_reset_tab, () -> {
                for (Settings.Item i : resettable) {
                    i.reset();
                    s.changed(i);
                }
                if (currentTab == TAB_GLASS) {
                    g.resetAll();
                    host.glassChanged();
                }
                showTab(currentTab);
                focusFirstRow();
            }));
        }
    }

    void buildBackdrops(List<Settings.Item> r) {
        section(R.string.sec_source);
        add(r, choice(s.bgSource));
        content.addView(action(R.string.set_active_playlists, R.string.help_active_playlists, () -> {
            List<String> names = new ArrayList<>();
            for (String id : s.activeIds()) {
                Playlists.Playlist p = Playlists.byId(ctx, id);
                if (p != null) names.add(p.name);
            }
            String shown = names.isEmpty() ? ctx.getString(R.string.logo_none)
                    : names.size() == 1 ? names.get(0)
                    : ctx.getResources().getQuantityString(R.plurals.playlists, names.size(), names.size());
            return shown + "  ›";
        }, () -> tabViews.get(TAB_PLAYLISTS).requestFocus()));
        content.addView(action(R.string.act_next_image, R.string.help_next_image, null, host::nextBackdrop));
        section(R.string.sec_rotation);
        add(r, choice(s.bgDelay), choice(s.bgOrder));
        section(R.string.sec_transition);
        add(r, choice(s.bgTransition), num(s.bgTransitionMs));
        section(R.string.sec_ken_burns);
        add(r, flag(s.kenBurns), num(s.kbAmplitude), choice(s.kbCycle));
        section(R.string.sec_light);
        add(r, flag(s.vignette), num(s.vignetteIntensity), flag(s.themeAuto), choice(s.themeColor),
                flag(s.entrance), num(s.parallax));
    }

    void buildCinema(List<Settings.Item> r) {
        section(R.string.sec_activation);
        add(r, choice(s.cinemaDelay));
        section(R.string.sec_bars);
        add(r, choice(s.cinemaBars), num(s.barOpacity), flag(s.cinemaKeepZoom));
        section(R.string.sec_title_card);
        add(r, choice(s.titleMode), choice(s.titleSide), flag(s.showYear));
        section(R.string.sec_clock_header);
        add(r, flag(s.clockSeconds), flag(s.hideHeader));
        screensaverSection();
    }

    // ---------------------------------------------------------------- Premiers pas

    static final String PREF_WELCOME_DONE = "welcome_done";
    /** Page « Premiers pas » (même instance : sert à savoir si elle est affichée). */
    final Runnable welcomePage = this::showWelcome;

    /** Catalogue des packs (SettingsPanel). */
    abstract void showPacks();

    boolean onWelcome() {
        return open && !pages.isEmpty() && pages.peek() == welcomePage;
    }

    /** Ouvre les Premiers pas par-dessus l'onglet Système (premier lancement, ou à la demande). */
    void openWelcome() {
        ctx.getSharedPreferences("proscenium", Context.MODE_PRIVATE).edit().putBoolean(PREF_WELCOME_DONE, true).apply();
        openPage(welcomePage);
        focusFirstRow();
    }

    /** Premier lancement : langue, ville de la météo, un pack de fonds, service du bouton Accueil. */
    void showWelcome() {
        clearContent();
        pageTitle(ctx.getString(R.string.welcome_title), R.string.hint_welcome);
        content.addView(choice(s.language));
        content.addView(action(R.string.set_weather_city, R.string.help_weather_city, () -> s.weatherCity.value,
                this::askCity).badgeOf(R.string.badge_internet));
        content.addView(action(R.string.act_welcome_packs, R.string.help_welcome_packs, () -> {
            int n = 0;
            for (Playlists.Playlist p : Playlists.all(ctx)) if (!p.pack.isEmpty()) n++;
            return n > 0 ? ctx.getResources().getQuantityString(R.plurals.playlists, n, n) + "  ›" : "›";
        }, () -> openPage(this::showPacks)).badgeOf(R.string.badge_internet));
        content.addView(action(R.string.set_service, R.string.help_service,
                () -> ctx.getString(host.homeServiceActive() ? R.string.service_on : R.string.service_off),
                host::openAccessibility));
        content.addView(action(R.string.act_welcome_done, R.string.help_welcome_done, null, this::close));
    }

    // ---------------------------------------------------------------- Économiseur d'écran

    android.content.ComponentName dreamComponent() {
        return new android.content.ComponentName(ctx, CinemaDream.class);
    }

    boolean dreamEnabled() {
        int st = ctx.getPackageManager().getComponentEnabledSetting(dreamComponent());
        return st != android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
    }

    /** Économiseur d'Android actuellement choisi : Proscenium, un autre, ou inconnu (réglage illisible). */
    String currentDream() {
        try {
            String c = android.provider.Settings.Secure.getString(ctx.getContentResolver(), "screensaver_components");
            if (c == null || c.isEmpty()) return ctx.getString(R.string.dream_none);
            String first = c.split(",")[0];
            if (first.startsWith(ctx.getPackageName() + "/")) return ctx.getString(R.string.app_name);
            android.content.ComponentName cn = android.content.ComponentName.unflattenFromString(first);
            if (cn == null) return "";
            android.content.pm.PackageManager pm = ctx.getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(cn.getPackageName(), 0)));
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Proscenium peut servir d'économiseur d'écran Android. Le désactiver retire Proscenium de la
     * liste des économiseurs d'Android (qui reprend alors le sien) ; le choix de l'économiseur
     * actif reste dans les paramètres d'Android, qu'on ouvre directement.
     */
    void screensaverSection() {
        section(R.string.sec_screensaver);
        content.addView(action(R.string.set_screensaver, R.string.help_screensaver,
                () -> ctx.getString(dreamEnabled() ? R.string.val_on : R.string.val_off), () -> {
                    ctx.getPackageManager().setComponentEnabledSetting(dreamComponent(), dreamEnabled()
                                    ? android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                                    : android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                            android.content.pm.PackageManager.DONT_KILL_APP);
                    for (Runnable x : new ArrayList<>(refreshers)) x.run();
                }));
        content.addView(action(R.string.act_dream_settings, R.string.help_dream_settings,
                () -> currentDream() + "  ›", () -> {
                    // Android TV : page des économiseurs. Google TV ne l'a plus et n'offre que son
                    // mode ambiant (le choix d'un économiseur tiers s'y fait par adb, voir le README)
                    for (String action : new String[]{android.provider.Settings.ACTION_DREAM_SETTINGS,
                            "com.google.android.tv.settings.ambient"}) {
                        android.content.Intent i = new android.content.Intent(action)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                        if (i.resolveActivity(ctx.getPackageManager()) == null) continue;
                        try {
                            ctx.startActivity(i);
                            return;
                        } catch (Exception ignored) {
                        }
                    }
                    host.openAndroidSettings();
                }));
    }

    void buildDock(List<Settings.Item> r) {
        section(R.string.sec_apps);
        content.addView(action(R.string.set_dock_apps, R.string.help_dock_apps,
                () -> String.valueOf(s.dockApps.value.isEmpty() ? 0 : s.dockApps.value.split(",").length) + "  ›",
                () -> openPage(this::showDockApps)));
        r.add(s.dockApps);
        section(R.string.sec_band);
        add(r, choice(s.bandMode), flag(s.slimBand));
        section(R.string.sec_tiles);
        add(r, choice(s.visibleApps), num(s.tileSize), num(s.tileSpacing), num(s.centerZoom), num(s.tilt), flag(s.shine), num(s.neighborDim));
        section(R.string.sec_glow);
        add(r, choice(s.glowMode), num(s.glowPower), flag(s.glowAnim), num(s.glowAnimAmount), num(s.glowAnimSpeed));
        section(R.string.sec_scroll);
        add(r, choice(s.scrollSpeed), flag(s.scrollParallax), flag(s.loop));
    }

    void buildGlass(List<Settings.Item> r) {
        section(R.string.sec_glass_engine);
        add(r, flag(s.glassEngine), num(s.rimWidth));
        section(R.string.sec_glass_appearance);
        content.addView(row(R.string.glass_preset, R.string.help_glass_preset, 0,
                () -> ctx.getString(GlassSettings.presetLabel(g.preset())),
                dir -> { g.nextPreset(dir); glassChanged(); }, () -> { g.nextPreset(1); glassChanged(); }, null));
        for (GlassSettings.Param p : g.appearance) content.addView(glassParam(p));
        content.addView(action(R.string.act_random_glass, R.string.help_random_glass, null, () -> {
            g.randomizeAppearance(random);
            glassChanged();
        }));
        section(R.string.sec_calm);
        content.addView(glassToggle(() -> g.calmOn, v -> g.calmOn = v));
        for (GlassSettings.Param p : g.calm) content.addView(glassParam(p));
        section(R.string.sec_liquid);
        content.addView(glassToggle(() -> g.liquidOn, v -> g.liquidOn = v));
        for (GlassSettings.Param p : g.liquid) content.addView(glassParam(p));
        section(R.string.sec_rain);
        content.addView(glassToggle(() -> g.rainOn, v -> g.rainOn = v));
        for (GlassSettings.Param p : g.rain) content.addView(glassParam(p));
    }

    void buildHome(List<Settings.Item> r) {
        section(R.string.sec_weather);
        content.addView(action(R.string.set_weather_city, R.string.help_weather_city, () -> s.weatherCity.value,
                this::askCity).badgeOf(R.string.badge_internet));
        r.add(s.weatherCity);
        r.add(s.weatherCoords);
        add(r, choice(s.tempUnit));
        section(R.string.sec_time);
        add(r, choice(s.timeFormat), num(s.clockSize), num(s.dateSize));
        section(R.string.sec_header);
        add(r, flag(s.showCpu), flag(s.showRam), flag(s.showWeather), flag(s.showWind), flag(s.weatherAnim), num(s.headerScale));
        section(R.string.sec_sounds);
        add(r, flag(s.uiSounds), num(s.uiVolume));
    }

    void buildSystem() {
        section(R.string.sec_language);
        content.addView(choice(s.language));
        section(R.string.sec_performance);
        content.addView(flag(s.economy));
        section(R.string.sec_service);
        content.addView(action(R.string.set_service, R.string.help_service,
                () -> ctx.getString(host.homeServiceActive() ? R.string.service_on : R.string.service_off),
                host::openAccessibility));
        content.addView(action(R.string.act_android_settings, R.string.help_android_settings, null, host::openAndroidSettings));
        section(R.string.sec_maintenance);
        final String[] saved = {""};
        content.addView(action(R.string.act_backup, R.string.help_backup, () -> saved[0], () -> {
            saved[0] = ctx.getString(host.backupSettings() ? R.string.backup_done : R.string.backup_failed);
        }));
        content.addView(confirmAction(R.string.act_restore, R.string.help_restore, () -> {
            if (!host.restoreSettings()) help.setText(R.string.restore_none);
        }));
        content.addView(confirmAction(R.string.act_reset_all, R.string.help_reset_all, () -> {
            g.resetAll();
            host.glassChanged();
            s.resetAll();
        }));
        updateSection();
        section(R.string.sec_about);
        content.addView(action(R.string.act_welcome, R.string.help_welcome, () -> "›", this::openWelcome));
        content.addView(action(R.string.set_version, R.string.help_version, host::versionName, null));
        content.addView(action(R.string.set_credits, R.string.help_credits, () -> ctx.getString(R.string.credits_value), null));
    }

    // ---------------------------------------------------------------- Mises à jour

    /** État affiché sur la ligne « Mise à jour » ; survit aux reconstructions de l'onglet. */
    static volatile String updateStatus = "";
    static volatile boolean updateBusy;

    /**
     * OK sur la ligne : cherche une nouvelle version ; si elle est connue, la télécharge puis la
     * confie à l'installeur d'Android (qui demande confirmation).
     */
    void updateSection() {
        android.content.SharedPreferences prefs = ctx.getSharedPreferences("proscenium", Context.MODE_PRIVATE);
        Runnable refreshAll = () -> { for (Runnable x : new java.util.ArrayList<>(refreshers)) x.run(); };
        section(R.string.sec_updates);
        content.addView(action(R.string.act_update, R.string.help_update, () -> {
            if (!updateStatus.isEmpty()) return updateStatus;
            Updater.Release known = Updater.known(ctx, prefs);
            return known != null ? ctx.getString(R.string.update_available, known.version) : "›";
        }, () -> {
            if (updateBusy) return;
            Updater.Release known = Updater.known(ctx, prefs);
            if (known == null) {
                updateBusy = true;
                updateStatus = ctx.getString(R.string.update_checking);
                refreshAll.run();
                async(() -> Updater.check(ctx, prefs), (r, err) -> {
                    updateBusy = false;
                    updateStatus = err != null ? ctx.getString(R.string.update_offline)
                            : r == null ? ctx.getString(R.string.update_none, Updater.installedVersion(ctx)) : "";
                    refreshAll.run();
                });
                return;
            }
            if (ctx instanceof android.app.Activity && !Updater.canInstall((android.app.Activity) ctx)) {
                updateStatus = "";
                help.setText(R.string.update_permission);
                return;
            }
            updateBusy = true;
            updateStatus = ctx.getString(R.string.update_downloading, 0);
            refreshAll.run();
            Context app = ctx.getApplicationContext();
            String failed = ctx.getString(R.string.update_failed), installing = ctx.getString(R.string.update_installing);
            new Thread(() -> {
                try {
                    java.io.File apk = Updater.download(app, known.apkUrl, p -> {
                        updateStatus = ctx.getString(R.string.update_downloading, Math.max(0, p));
                        ui.post(refreshAll);
                    });
                    updateStatus = installing;
                    ui.post(refreshAll);
                    Updater.install(app, apk);
                } catch (Exception e) {
                    android.util.Log.w(HomeBase.TAG, "Mise à jour impossible", e);
                    updateStatus = failed;
                    ui.post(refreshAll);
                } finally {
                    updateBusy = false;
                }
            }, "proscenium-update").start();
        }).badgeOf(R.string.badge_internet));
        content.addView(flag(s.updateCheck));
    }

    // ---------------------------------------------------------------- Sous-page : applis de la barre

    /** Liste des applis installées : OK ajoute ou retire, ← → déplace une appli de la barre. */
    void showDockApps() {
        clearContent();
        TextView title = text(12, 0xC0FFFFFF, false);
        title.setText(R.string.dock_apps_title);
        title.setLetterSpacing(0.12f);
        title.setPadding(dp(14), dp(4), dp(14), dp(2));
        content.addView(title);
        TextView hint = text(12, 0x99FFFFFF, false);
        hint.setText(R.string.dock_apps_hint);
        hint.setPadding(dp(14), 0, dp(14), dp(8));
        content.addView(hint);

        List<String> order = dockOrder();
        List<String[]> apps = new ArrayList<>(host.installedApps());
        // Les raccourcis HDMI (consoles) se rangent comme des applis ; on les crée par l'appui long sur la barre
        for (InputShortcuts.Shortcut sc : InputShortcuts.load(s)) {
            apps.add(0, new String[]{InputShortcuts.PREFIX + sc.id, sc.name + "  ·  HDMI"});
        }
        // Applis de la barre d'abord, dans leur ordre, puis les autres
        List<String[]> sorted = new ArrayList<>();
        for (String pkg : order) for (String[] a : apps) if (a[0].equals(pkg)) sorted.add(a);
        for (String[] a : apps) if (!order.contains(a[0])) sorted.add(a);
        for (String[] a : sorted) content.addView(dockAppRow(a[0], a[1]));
        focusFirstRow();
    }

    List<String> dockOrder() {
        List<String> order = new ArrayList<>();
        for (String p : s.dockApps.value.split(",")) if (!p.trim().isEmpty()) order.add(p.trim());
        return order;
    }

    View dockAppRow(String pkg, String label) {
        Row r = row(0, R.string.help_dock_apps, 0, () -> {
            List<String> order = dockOrder();
            int at = order.indexOf(pkg);
            return at < 0 ? "" : (at + 1) + "  ✓";
        }, dir -> {
            List<String> order = dockOrder();
            int at = order.indexOf(pkg);
            if (at < 0) return;
            int to = Math.max(0, Math.min(order.size() - 1, at + dir));
            order.remove(at);
            order.add(to, pkg);
            saveDockOrder(order);
            showDockAppsKeepFocus(pkg);
        }, () -> {
            List<String> order = dockOrder();
            if (order.contains(pkg)) order.remove(pkg);
            else order.add(pkg);
            saveDockOrder(order);
            showDockAppsKeepFocus(pkg);
        }, label);
        r.setTag(pkg);
        return r;
    }

    void saveDockOrder(List<String> order) {
        StringBuilder b = new StringBuilder();
        for (String p : order) b.append(b.length() == 0 ? "" : ",").append(p);
        s.dockApps.value = b.toString();
        s.changed(s.dockApps);
        host.dockAppsChanged();
    }

    void showDockAppsKeepFocus(String pkg) {
        showDockApps();
        for (int i = 0; i < content.getChildCount(); i++) {
            View v = content.getChildAt(i);
            if (pkg.equals(v.getTag())) {
                v.requestFocus();
                return;
            }
        }
    }


    void askCity() {
        askText(R.string.weather_prompt, s.weatherCity.value, q -> {
            help.setText(R.string.weather_searching);
            host.searchCity(q, result -> {
                if (result == null) {
                    help.setText(R.string.weather_not_found);
                    return;
                }
                s.weatherCity.value = result[0];
                s.weatherCoords.value = result[1];
                s.changed(s.weatherCoords);
                s.changed(s.weatherCity);
                for (Runnable r : refreshers) r.run();
            });
        });
    }


    Row glassParam(GlassSettings.Param p) {
        Row r = row(p.label, R.string.help_glass_param, R.string.badge_cpu, () -> p.value + " %", dir -> {
            p.set(p.value + dir * 10);
            glassChanged();
        }, null, null);
        r.progressSource = () -> r.setProgress((p.value - p.min) / (float) (p.max - p.min));
        r.progressSource.run();
        return r;
    }

    Row glassToggle(BoolGet get, BoolSet set) {
        return row(R.string.glass_enabled, R.string.help_glass_anim, R.string.badge_cpu, () -> onOff(get.get()),
                dir -> {
                    set.set(!get.get());
                    glassChanged();
                }, null, null);
    }

    void glassChanged() {
        host.glassChanged();
        for (Runnable r : refreshers) r.run();
    }
}
