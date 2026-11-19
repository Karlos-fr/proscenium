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



    // ---------------------------------------------------------------- Contenu des onglets

    void showTab(int tab) {
        currentTab = tab;
        for (int i = 0; i < tabViews.size(); i++) styleTab(i);
        pages.clear();
        returnFocus.clear();
        clearContent();
        List<Settings.Item> resettable = new ArrayList<>();
        switch (tab) {
            case TAB_PLAYLISTS: buildPlaylists(); break;
            case TAB_GLASS: buildGlass(resettable); break;
            default:
                buildLayout(tab, resettable);
                if (!PanelLayout.resettable(tab)) return;
                break;
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

    // ---------------------------------------------------------------- Onglets décrits par PanelLayout

    /** Construit un onglet depuis sa description (la même que celle de la télécommande web). */
    void buildLayout(int tab, List<Settings.Item> r) {
        for (Object e : PanelLayout.of(tab, s)) {
            if (e instanceof PanelLayout.Section) {
                section(((PanelLayout.Section) e).title);
            } else if (e instanceof Settings.Flag) {
                add(r, flag((Settings.Flag) e));
            } else if (e instanceof Settings.Num) {
                add(r, num((Settings.Num) e));
            } else if (e instanceof Settings.Choice) {
                add(r, choice((Settings.Choice) e));
            } else if (e instanceof PanelLayout.Special) {
                special(((PanelLayout.Special) e).id, r);
            }
        }
    }

    /** État de la ligne « Sauvegarder les réglages » ; survit aux reconstructions de l'onglet. */
    String backupStatus = "";

    /**
     * Ligne particulière d'un onglet (voir PanelLayout). Celles de l'onglet Films sont dans
     * SettingsPanel, qui complète cette méthode.
     */
    void special(String id, List<Settings.Item> r) {
        switch (id) {
            case PanelLayout.ACTIVE_PLAYLISTS:
                content.addView(action(R.string.set_active_playlists, R.string.help_active_playlists, () -> {
                    List<String> names = new ArrayList<>();
                    for (String pid : s.activeIds()) {
                        Playlists.Playlist p = Playlists.byId(ctx, pid);
                        if (p != null) names.add(p.name);
                    }
                    String shown = names.isEmpty() ? ctx.getString(R.string.logo_none)
                            : names.size() == 1 ? names.get(0)
                            : ctx.getResources().getQuantityString(R.plurals.playlists, names.size(), names.size());
                    return shown + "  ›";
                }, () -> tabViews.get(TAB_PLAYLISTS).requestFocus()));
                break;
            case PanelLayout.NEXT_IMAGE:
                content.addView(action(R.string.act_next_image, R.string.help_next_image, null, host::nextBackdrop));
                break;
            // Proscenium peut servir d'économiseur d'écran Android. Le désactiver retire Proscenium de la
            // liste des économiseurs d'Android (qui reprend alors le sien) ; le choix de l'économiseur
            // actif reste dans les paramètres d'Android, qu'on ouvre directement
            case PanelLayout.SCREENSAVER:
                content.addView(action(R.string.set_screensaver, R.string.help_screensaver,
                        () -> ctx.getString(dreamEnabled() ? R.string.val_on : R.string.val_off), () -> {
                            ctx.getPackageManager().setComponentEnabledSetting(dreamComponent(), dreamEnabled()
                                            ? android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                                            : android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                                    android.content.pm.PackageManager.DONT_KILL_APP);
                            ProsceniumRemote.contentChanged();
                            for (Runnable x : new ArrayList<>(refreshers)) x.run();
                        }));
                break;
            case PanelLayout.DREAM_SETTINGS:
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
                break;
            case PanelLayout.DOCK_APPS:
                content.addView(action(R.string.set_dock_apps, R.string.help_dock_apps,
                        () -> String.valueOf(s.dockApps.value.isEmpty() ? 0 : s.dockApps.value.split(",").length) + "  ›",
                        () -> openPage(this::showDockApps)));
                r.add(s.dockApps);
                break;
            case PanelLayout.CITY:
                content.addView(action(R.string.set_weather_city, R.string.help_weather_city, () -> s.weatherCity.value,
                        this::askCity).badgeOf(R.string.badge_internet));
                r.add(s.weatherCity);
                r.add(s.weatherCoords);
                break;
            case PanelLayout.WEATHER_STYLES: {
                Row styles = action(R.string.act_weather_styles, R.string.help_weather_styles, () -> "›",
                        () -> openPage(this::showWeatherStyles));
                styles.badgeOf(R.string.badge_cpu).setTag("weather_styles");
                content.addView(styles);
                break;
            }
            case PanelLayout.WEB_REMOTE:
                content.addView(action(R.string.set_web_remote, R.string.help_web_remote,
                        () -> onOff(s.webRemote.value) + "  ›", () -> openPage(webRemotePage)));
                break;
            case PanelLayout.SERVICE:
                content.addView(action(R.string.set_service, R.string.help_service,
                        () -> ctx.getString(host.homeServiceActive() ? R.string.service_on : R.string.service_off),
                        host::openAccessibility));
                break;
            // Android 13 et plus bloque l'accessibilité des applis installées hors du Play Store
            // (« paramètre restreint ») : l'interrupteur du service refuse alors de s'activer. Cette
            // ligne ouvre la page de l'appli, où le menu ⋮ permet de lever le blocage
            case PanelLayout.RESTRICTED:
                if (android.os.Build.VERSION.SDK_INT < 33 || host.homeServiceActive()) break;
                content.addView(action(R.string.act_restricted, R.string.help_restricted, () -> "›", () -> {
                    try {
                        ctx.startActivity(new android.content.Intent(
                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.parse("package:" + ctx.getPackageName()))
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (Exception e) {
                        host.openAndroidSettings();
                    }
                }));
                break;
            case PanelLayout.ANDROID_SETTINGS:
                content.addView(action(R.string.act_android_settings, R.string.help_android_settings, null,
                        host::openAndroidSettings));
                break;
            case PanelLayout.BACKUP:
                content.addView(action(R.string.act_backup, R.string.help_backup, () -> backupStatus, () -> {
                    backupStatus = ctx.getString(host.backupSettings() ? R.string.backup_done : R.string.backup_failed);
                }));
                break;
            case PanelLayout.RESTORE:
                content.addView(confirmAction(R.string.act_restore, R.string.help_restore, () -> {
                    if (!host.restoreSettings()) help.setText(R.string.restore_none);
                }));
                break;
            case PanelLayout.RESET_ALL:
                content.addView(confirmAction(R.string.act_reset_all, R.string.help_reset_all, () -> {
                    g.resetAll();
                    host.glassChanged();
                    s.resetAll();
                }));
                break;
            case PanelLayout.UPDATE:
                updateRow();
                break;
            case PanelLayout.WELCOME:
                content.addView(action(R.string.act_welcome, R.string.help_welcome, () -> "›", this::openWelcome));
                break;
            case PanelLayout.VERSION:
                content.addView(action(R.string.set_version, R.string.help_version, host::versionName, null));
                break;
            case PanelLayout.CREDITS:
                content.addView(action(R.string.set_credits, R.string.help_credits,
                        () -> ctx.getString(R.string.credits_value), null));
                break;
            case PanelLayout.TMDB_ABOUT:
                tmdbLogo();
                note(ctx.getString(R.string.tmdb_notice));
                break;
            default:
                break;
        }
    }

    // ---------------------------------------------------------------- Premiers pas

    static final String PREF_WELCOME_DONE = "welcome_done";
    /** Page « Premiers pas » (même instance : sert à savoir si elle est affichée). */
    final Runnable welcomePage = this::showWelcome;

    /** Catalogue des packs (SettingsPanel). */
    abstract void showPacks();

    /** Télécommande web : marche / arrêt et QR code vers la page (SettingsPanel). */
    abstract void showWebRemote();

    /** Page de la télécommande web (même instance : sert à savoir si elle est affichée). */
    final Runnable webRemotePage = this::showWebRemote;

    /** Adresse de la télécommande changée (nouvelle IP, autre nom .local) : la page se refait si elle est ouverte. */
    void webRemoteChanged() {
        if (open && !pages.isEmpty() && pages.peek() == webRemotePage) rebuildPage("web_remote");
    }

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
        // Les trois animations, chacune dans son sous-menu
        section(R.string.sec_glass_anims);
        glassEffectRow("glass:calm", R.string.glass_calm, R.string.help_glass_calm, () -> g.calmOn, v -> g.calmOn = v, g.calm);
        glassEffectRow("glass:liquid", R.string.glass_liquid, R.string.help_glass_liquid, () -> g.liquidOn,
                v -> g.liquidOn = v, g.liquid);
        glassEffectRow("glass:rain", R.string.fx_rain, R.string.help_glass_rain, () -> g.rainOn, v -> g.rainOn = v, g.rain);
    }

    /** Ligne d'une animation du verre : son état, et OK ouvre son sous-menu. */
    void glassEffectRow(String tag, int name, int help, BoolGet get, BoolSet set, GlassSettings.Param[] params) {
        Row r = row(name, help, R.string.badge_cpu, () -> onOff(get.get()) + "  ›", null,
                () -> openPage(() -> showGlassEffect(name, get, set, params)), null);
        r.setTag(tag);
        content.addView(r);
    }

    /** Sous-menu d'une animation du verre : marche / arrêt, ses réglages, des valeurs au hasard. */
    void showGlassEffect(int name, BoolGet get, BoolSet set, GlassSettings.Param[] params) {
        clearContent();
        pageTitle(ctx.getString(name), 0);
        content.addView(glassToggle(get, set));
        for (GlassSettings.Param p : params) content.addView(glassParam(p));
        section(0);
        content.addView(action(R.string.act_random_values, R.string.help_random_glass_effect, null, () -> {
            GlassSettings.randomize(params, random);
            set.set(true);
            glassChanged();
        }));
        focusFirstRow();
    }

    // ---------------------------------------------------------------- Mises à jour

    /** État affiché sur la ligne « Mise à jour » ; survit aux reconstructions de l'onglet. */
    static volatile String updateStatus = "";
    static volatile boolean updateBusy;

    /**
     * OK sur la ligne : cherche une nouvelle version ; si elle est connue, la télécharge puis la
     * confie à l'installeur d'Android (qui demande confirmation).
     */
    void updateRow() {
        android.content.SharedPreferences prefs = ctx.getSharedPreferences("proscenium", Context.MODE_PRIVATE);
        Runnable refreshAll = () -> { for (Runnable x : new java.util.ArrayList<>(refreshers)) x.run(); };
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
            host.searchCity(q, cities -> {
                if (cities == null || cities.isEmpty()) {
                    help.setText(R.string.weather_not_found);
                    return;
                }
                if (cities.size() == 1) {
                    setCity(cities.get(0));
                    return;
                }
                // Plusieurs villes de ce nom : la liste, avec région et pays, pour choisir la sienne
                String[] labels = new String[cities.size()];
                for (int i = 0; i < labels.length; i++) labels[i] = cities.get(i)[2];
                help.setText("");
                new android.app.AlertDialog.Builder(ctx)
                        .setTitle(R.string.weather_choose)
                        .setItems(labels, (d, which) -> setCity(cities.get(which)))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            });
        });
    }

    void setCity(String[] city) {
        s.weatherCity.value = city[0];
        s.weatherCoords.value = city[1];
        s.changed(s.weatherCoords);
        s.changed(s.weatherCity);
        for (Runnable r : refreshers) r.run();
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

    // ---------------------------------------------------------------- Réglages de la météo

    /** Sous-menu : une ligne par météo, avec son préréglage. */
    void showWeatherStyles() {
        clearContent();
        pageTitle(ctx.getString(R.string.act_weather_styles), R.string.hint_weather_styles);
        for (WeatherStyles.Style w : host.weatherStyles().all) {
            Row r = row(w.name, w.help, R.string.badge_cpu, () -> presetName(w) + "  ›", null,
                    () -> openPage(() -> showWeatherStyle(w)), null);
            r.setTag("wx:" + w.kind);
            content.addView(r);
        }
        focusFirstRow();
    }

    /** Page d'une météo : préréglage, réglages, retour aux valeurs d'origine ; la météo s'affiche en direct. */
    void showWeatherStyle(WeatherStyles.Style w) {
        clearContent();
        pageTitle(ctx.getString(w.name), R.string.hint_weather_style);
        previewingWeather = true;
        host.previewWeather(w.kind);
        content.addView(row(R.string.wx_preset, R.string.help_wx_preset, 0, () -> presetName(w),
                dir -> { w.nextPreset(dir); weatherStylesChanged(); },
                () -> { w.nextPreset(1); weatherStylesChanged(); }, null));
        for (WeatherStyles.Param p : w.params) {
            Row r = row(p.label, p.help, R.string.badge_cpu,
                    () -> p.labels.length > 0 ? ctx.getString(p.labels[p.value]) : p.value + " %",
                    dir -> { p.step(dir); weatherStylesChanged(); }, null, null);
            if (p.labels.length == 0) {
                r.progressSource = () -> r.setProgress((p.value - p.min) / (float) (p.max - p.min));
                r.progressSource.run();
            }
            content.addView(r);
        }
        section(0);
        content.addView(action(R.string.act_random_values, R.string.help_random_weather, null,
                () -> { w.randomize(random); weatherStylesChanged(); }));
        content.addView(action(R.string.act_reset_weather, R.string.help_reset_weather, null,
                () -> { w.reset(); weatherStylesChanged(); }));
        focusFirstRow();
    }

    String presetName(WeatherStyles.Style w) {
        int p = w.preset();
        return ctx.getString(p < 0 ? R.string.preset_custom : w.presetNames[p]);
    }

    void weatherStylesChanged() {
        host.weatherStylesChanged();
        for (Runnable r : refreshers) r.run();
    }

    void glassChanged() {
        host.glassChanged();
        for (Runnable r : refreshers) r.run();
    }
}
