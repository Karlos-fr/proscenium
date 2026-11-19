package fr.proscenium.launcher;

import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import fr.tvremote.core.AppInfo;
import fr.tvremote.core.Image;
import fr.tvremote.core.Item;
import fr.tvremote.core.Result;
import fr.tvremote.core.Screen;
import fr.tvremote.core.TvRemote;

/**
 * Télécommande web : les réglages du panneau (sauf le verre) sur la page servie au téléphone
 * (socle commun fr.tvremote.core, voir D:\VibeCoding\tvremote). Le serveur vit tant que
 * l'appli tourne et que le réglage « Télécommande web » est actif ; il survit à la
 * reconstruction de l'activité (changement de langue…), qui ne fait que s'y rattacher.
 */
final class ProsceniumRemote implements TvRemote.App, Settings.Listener {
    private static final String TAG = "Proscenium";
    private static ProsceniumRemote instance;

    private MainActivity act;
    private TvRemote server;
    /** Réglage en cours d'application depuis le téléphone : le téléphone a déjà le nouvel écran. */
    private boolean fromPhone;
    /** Mode d'accès et nom du serveur en marche : les changer demande un redémarrage. */
    private String serverKey = "", lastError;
    private final Random random = new Random();
    /** Messages d'état affichés sur la page (recherche de ville, test de la clé…). */
    private String cityStatus = "", keyStatus = "", backupStatus = "";
    /** Image du fond affiché, en JPEG réduit, et son numéro (ETag). */
    private volatile byte[] backdropJpeg;
    private volatile int backdropVersion;

    // ================================================================== Cycle de vie

    /** À chaque création de l'activité : rattache la télécommande, la démarre si le réglage le veut. */
    static void attach(MainActivity a) {
        if (instance == null) instance = new ProsceniumRemote();
        instance.act = a;
        a.settings.addListener(instance);
        instance.apply();
    }

    static void detach(MainActivity a) {
        if (instance == null || instance.act != a) return;
        a.settings.removeListener(instance);
    }

    static ProsceniumRemote get() {
        return instance;
    }

    /** Démarre ou arrête le serveur selon le réglage. */
    void apply() {
        Settings s = act.settings;
        boolean want = s.webRemote.value;
        TvRemote.Access access = s.webRemoteFixed.value ? TvRemote.Access.OPEN : TvRemote.Access.TOKEN;
        String name = TvRemote.cleanHostname(s.webRemoteName.value, "proscenium");
        String key = access + "|" + name;
        if (want && server != null && server.running() && key.equals(serverKey)) {
            server.setPassword(s.webRemotePassword.value);     // changé à chaud, sans redémarrer
            return;
        }
        if (server != null) {
            server.stop();
            server = null;
        }
        lastError = null;
        if (!want) return;
        server = TvRemote.start(act, this, new TvRemote.Options().port(8770).access(access).hostname(name)
                .password(s.webRemotePassword.value).realm(str(R.string.app_name))
                .onAddressChanged(this::addressChanged));
        serverKey = key;
        if (server == null) {
            lastError = TvRemote.lastError();
            Log.w(TAG, "Télécommande web : " + lastError);
        } else {
            Log.i(TAG, "Télécommande web : " + server.url() + "  " + server.localName());
            frameChanged();
        }
    }

    /** Nouvelle adresse IP, ou nom .local pris par un autre appareil : la page du QR code se refait. */
    private void addressChanged() {
        if (act.panel != null) act.panel.webRemoteChanged();
    }

    /** Pourquoi le serveur n'a pas démarré (« port 8770 busy »…), ou null. */
    String lastError() {
        return lastError;
    }

    /** Adresse avec le nom .local, ou null. */
    String localName() {
        return server != null && server.running() ? server.localName() : null;
    }

    /** Adresse à ouvrir sur le téléphone, ou null (arrêtée, pas de réseau). */
    String url() {
        return server != null && server.running() ? server.url() : null;
    }

    TvRemote server() {
        return server;
    }

    @Override
    public void onSettingChanged(Settings.Item item) {
        Settings s = act.settings;
        if (item == s.webRemote || item == s.webRemoteFixed || item == s.webRemoteName || item == s.webRemotePassword) {
            apply();
            return;
        }
        // Changé à la télécommande de la TV : les téléphones relisent leur écran
        if (!fromPhone && server != null) server.changed(null);
    }

    /** Nouveau fond à l'écran : état en direct pour la barre « en cours » de la page. */
    void frameChanged() {
        if (server == null || act == null) return;
        Wallpaper.Frame f = act.frame;
        if (f == null) return;
        int version = ++backdropVersion;
        Bitmap sharp = f.sharp;
        act.io.execute(() -> {
            try {
                int w = 640, h = Math.max(1, Math.round(w * sharp.getHeight() / (float) sharp.getWidth()));
                Bitmap small = Bitmap.createScaledBitmap(sharp, w, h, true);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                small.compress(Bitmap.CompressFormat.JPEG, 80, out);
                if (small != sharp) small.recycle();
                if (version == backdropVersion) backdropJpeg = out.toByteArray();
            } catch (Exception e) {
                Log.w(TAG, "Miniature du fond", e);
            }
            act.ui.post(() -> pushState(f, version));
        });
    }

    private void pushState(Wallpaper.Frame f, int version) {
        if (server == null) return;
        JSONObject st = new JSONObject();
        try {
            st.put("title", f.title == null ? "" : f.title);
            st.put("img", "img/backdrop?v=" + version);
            st.put("city", act.settings.weatherCity.value);
        } catch (Exception ignored) { }
        server.state(st);
    }

    // ================================================================== Description de l'appli

    private String str(int res) {
        return act.getString(res);
    }

    @Override
    public AppInfo info() {
        AppInfo info = new AppInfo(str(R.string.app_name), act.versionName(), "backdrops")
                .lang(Lang.tag(act).substring(0, 2)).accent(act.accent).theme("background", "#101826");
        String[][] tabs = {{"backdrops", "picture"}, {"playlists", "playlist"}, {"cinema", "cinema"},
                {"dock", "dock"}, {"home", "home"}, {"tmdb", "film"}, {"system", "gear"}};
        int[] names = {R.string.tab_backdrops, R.string.tab_playlists, R.string.tab_cinema, R.string.tab_dock,
                R.string.tab_home, R.string.tab_tmdb, R.string.tab_system};
        for (int i = 0; i < tabs.length; i++) info.tab(tabs[i][0], str(names[i]), "static/icons/" + tabs[i][0] + ".svg");
        return info;
    }

    @Override
    public Screen screen(String id, TvRemote.Query q) {
        Settings s = act.settings;
        switch (id) {
            case "backdrops": {
                Screen sc = tab(id, R.string.tab_backdrops, 0);
                section(sc, R.string.sec_source);
                sc.add(item(s.bgSource));
                sc.add(Item.button("playlists", str(R.string.set_active_playlists)).opens("playlists")
                        .help(str(R.string.help_active_playlists)).value(activeSummary()));
                sc.add(Item.button("next", str(R.string.act_next_image)).help(str(R.string.help_next_image)));
                section(sc, R.string.sec_rotation);
                add(sc, s.bgDelay, s.bgOrder);
                section(sc, R.string.sec_transition);
                add(sc, s.bgTransition, s.bgTransitionMs);
                section(sc, R.string.sec_ken_burns);
                add(sc, s.kenBurns, s.kbAmplitude, s.kbCycle);
                section(sc, R.string.sec_light);
                add(sc, s.vignette, s.vignetteIntensity, s.themeAuto, s.themeColor, s.entrance, s.parallax);
                return sc;
            }
            case "playlists": {
                Screen sc = tab(id, R.string.tab_playlists, 1);
                Item grid = Item.grid("active", str(R.string.sec_my_playlists), Item.Check.MULTI)
                        .help(str(R.string.help_active_playlists));
                for (Playlists.Playlist p : Playlists.all(act)) {
                    grid.tile(Item.tile(p.id, p.name).img("img/playlist:" + p.id)
                            .checked(s.isActive(p.id)).sub(films(p.films.size())));
                }
                sc.add(grid);
                section(sc, R.string.sec_packs);
                sc.add(Item.button("packs", str(R.string.act_browse_packs)).opens("packs")
                        .help(str(R.string.help_browse_packs)).badge(str(R.string.badge_internet), "internet"));
                downloads(sc);
                return sc;
            }
            case "packs": return packs();
            case "cinema": {
                Screen sc = tab(id, R.string.tab_cinema, 2);
                section(sc, R.string.sec_activation);
                add(sc, s.cinemaDelay);
                section(sc, R.string.sec_bars);
                add(sc, s.cinemaBars, s.barOpacity, s.cinemaKeepZoom);
                section(sc, R.string.sec_title_card);
                add(sc, s.titleMode, s.titleSide, s.showYear, s.titleInfo, s.titleRating, s.titleSummary, s.titleTextSize);
                section(sc, R.string.sec_clock_header);
                add(sc, s.clockSeconds, s.hideHeader);
                section(sc, R.string.sec_screensaver);
                sc.add(Item.toggle("screensaver", str(R.string.set_screensaver), dreamEnabled())
                        .help(str(R.string.help_screensaver)));
                return sc;
            }
            case "dock": {
                Screen sc = tab(id, R.string.tab_dock, 3);
                section(sc, R.string.sec_apps);
                sc.add(Item.button("apps", str(R.string.set_dock_apps)).opens("dock_apps")
                        .help(str(R.string.help_dock_apps)).value(String.valueOf(dockOrder().size())));
                section(sc, R.string.sec_band);
                add(sc, s.bandMode, s.slimBand);
                section(sc, R.string.sec_tiles);
                add(sc, s.visibleApps, s.tileSize, s.tileSpacing, s.centerZoom, s.tilt, s.shine, s.neighborDim);
                section(sc, R.string.sec_glow);
                add(sc, s.glowMode, s.glowPower, s.glowAnim, s.glowAnimAmount, s.glowAnimSpeed);
                section(sc, R.string.sec_scroll);
                add(sc, s.scrollSpeed, s.scrollParallax, s.loop);
                return sc;
            }
            case "dock_apps": return dockApps();
            case "home": {
                Screen sc = tab(id, R.string.tab_home, 4);
                section(sc, R.string.sec_weather);
                sc.add(Item.text("city", str(R.string.set_weather_city), s.weatherCity.value)
                        .help(cityStatus.isEmpty() ? str(R.string.help_weather_city) : cityStatus)
                        .badge(str(R.string.badge_internet), "internet"));
                add(sc, s.tempUnit, s.weatherFx, s.fxSun, s.fxRain, s.fxLightning, s.fxSnow, s.fxFog, s.fxWind,
                        s.weatherFxAmount, s.weatherLens);
                sc.add(Item.button("styles", str(R.string.act_weather_styles)).opens("weather_styles")
                        .help(str(R.string.help_weather_styles)).badge(str(R.string.badge_cpu), "cpu"));
                section(sc, R.string.sec_time);
                add(sc, s.timeFormat, s.clockSize, s.dateSize);
                section(sc, R.string.sec_header);
                add(sc, s.showCpu, s.showRam, s.showWeather, s.showWind, s.weatherAnim, s.headerScale);
                section(sc, R.string.sec_sounds);
                add(sc, s.uiSounds, s.uiVolume);
                return sc;
            }
            case "weather_styles": {
                Screen sc = Screen.of(id, str(R.string.act_weather_styles)).subtitle(str(R.string.hint_weather_styles));
                for (WeatherStyles.Style w : act.weatherStyles.all) {
                    sc.add(Item.button("wx" + w.kind, str(w.name)).opens("wx:" + w.kind).help(str(w.help))
                            .value(presetName(w)).badge(str(R.string.badge_cpu), "cpu"));
                }
                return sc;
            }
            case "tmdb": {
                Screen sc = tab(id, R.string.tab_tmdb, 5);
                section(sc, R.string.sec_tmdb_access);
                Tmdb tmdb = act.tmdb();
                sc.add(Item.secret("key", str(R.string.set_tmdb_key), tmdb.hasKey())
                        .placeholder(str(R.string.prompt_tmdb_key))
                        .help(keyStatus.isEmpty() ? str(R.string.help_tmdb_key) : keyStatus));
                sc.add(Item.button("test", str(R.string.act_test_tmdb)).help(str(R.string.help_test_tmdb))
                        .badge(str(R.string.badge_internet), "internet"));
                section(sc, R.string.sec_tmdb_images);
                add(sc, s.tmdbLang, s.tmdbQuality, s.textlessOnly, s.fullHdOnly);
                section(sc, R.string.sec_cache);
                sc.add(Item.info("cache", str(R.string.set_cache),
                        String.format(Lang.locale(act), "%.1f Mo", Tmdb.cacheSize(act) / 1048576f)));
                sc.add(Item.button("clear_cache", str(R.string.act_clear_cache)).help(str(R.string.help_clear_cache))
                        .confirm(str(R.string.act_clear_cache) + " ?"));
                downloads(sc);
                return sc;
            }
            case "system": {
                Screen sc = tab(id, R.string.tab_system, 6);
                section(sc, R.string.sec_language);
                add(sc, s.language);
                section(sc, R.string.sec_performance);
                add(sc, s.economy);
                section(sc, R.string.sec_maintenance);
                sc.add(Item.button("backup", str(R.string.act_backup)).help(str(R.string.help_backup)).value(backupStatus));
                sc.add(Item.button("restore", str(R.string.act_restore)).help(str(R.string.help_restore))
                        .confirm(str(R.string.act_restore) + " ?"));
                sc.add(Item.button("reset_all", str(R.string.act_reset_all)).help(str(R.string.help_reset_all))
                        .confirm(str(R.string.act_reset_all) + " ?"));
                section(sc, R.string.sec_updates);
                add(sc, s.updateCheck);
                section(sc, R.string.sec_about);
                sc.add(Item.info("version", str(R.string.set_version), act.versionName()));
                sc.add(Item.info("credits", str(R.string.set_credits), str(R.string.credits_value)));
                sc.add(Item.info("tmdb_notice", str(R.string.tmdb_notice), ""));
                return sc;
            }
            case "live": {
                Screen sc = Screen.of(id, str(R.string.app_name));
                sc.add(Item.button("next", str(R.string.act_next_image)));
                return sc;
            }
        }
        if (id.startsWith("wx:")) {
            WeatherStyles.Style w = style(id.substring(3));
            return w == null ? null : weatherStyle(w);
        }
        return null;
    }

    // ---------------------------------------------------------------- Écrans particuliers

    /** Catalogue des packs : chargé en arrière-plan à la première demande, puis gardé une heure. */
    private Screen packs() {
        Screen sc = Screen.of("packs", str(R.string.packs_title)).subtitle(str(R.string.hint_packs));
        List<Packs.Pack> catalog = SettingsPanel.packCatalog;
        if (catalog != null && System.currentTimeMillis() - SettingsPanel.packCatalogAt > 3600_000L) catalog = null;
        if (catalog == null) {
            sc.add(Item.info("loading", str(R.string.loading), ""));
            String lang = packLang();
            act.net.execute(() -> {
                List<Packs.Pack> list = null;
                try {
                    list = Packs.catalog(lang);
                } catch (Exception e) {
                    Log.w(TAG, "Catalogue des packs", e);
                }
                List<Packs.Pack> got = list;
                act.ui.post(() -> {
                    if (got != null) {
                        SettingsPanel.packCatalog = got;
                        SettingsPanel.packCatalogAt = System.currentTimeMillis();
                    }
                    if (server != null) server.changed("packs");
                });
            });
            return sc;
        }
        Item grid = Item.grid("packs", "", Item.Check.MULTI);
        for (Packs.Pack pack : catalog) {
            Playlists.Playlist installed = Playlists.byPack(act, pack.id);
            boolean update = installed != null && pack.version > installed.packVersion;
            String sub = films(pack.films) + (update ? "  ·  " + str(R.string.packs_update)
                    : installed != null ? "  ·  " + str(R.string.packs_installed) : "");
            grid.tile(Item.tile(pack.id, pack.name).img("img/tmdb:w300:" + pack.cover).checked(installed != null)
                    .sub(sub));
        }
        sc.add(grid);
        sc.add(Item.info("notice", str(R.string.packs_notice), ""));
        downloads(sc);
        return sc;
    }

    /** Applis de la barre : cochées dans leur ordre (déplaçables), puis les autres. */
    private Screen dockApps() {
        Screen sc = Screen.of("dock_apps", str(R.string.set_dock_apps)).subtitle(str(R.string.help_dock_apps));
        List<String> order = dockOrder();
        List<String[]> apps = new ArrayList<>(act.installedApps());
        for (InputShortcuts.Shortcut shortcut : InputShortcuts.load(act.settings)) {
            apps.add(0, new String[]{InputShortcuts.PREFIX + shortcut.id, shortcut.name + "  ·  HDMI"});
        }
        Item in = Item.list("order", str(R.string.dock_apps_title), Item.Check.MULTI).order(true);
        for (String pkg : order) {
            for (String[] a : apps) if (a[0].equals(pkg)) in.row(Item.row(id(pkg), a[1]).checked(true));
        }
        sc.add(in);
        Item others = Item.list("others", "", Item.Check.MULTI);
        for (String[] a : apps) if (!order.contains(a[0])) others.row(Item.row(id(a[0]), a[1]).checked(false));
        sc.add(others);
        return sc;
    }

    /** Les identifiants de ligne n'ont pas de « / » (paquets et raccourcis HDMI n'en ont pas, par sûreté). */
    private static String id(String pkg) {
        return pkg.replace("/", "%2F");
    }

    private static String unId(String id) {
        return id.replace("%2F", "/");
    }

    private Screen weatherStyle(WeatherStyles.Style w) {
        Screen sc = Screen.of("wx:" + w.kind, str(w.name)).subtitle(str(R.string.hint_weather_style));
        Item preset = Item.choice("preset", str(R.string.wx_preset), w.preset()).help(str(R.string.help_wx_preset));
        if (w.preset() < 0) preset.option(-1, str(R.string.preset_custom));
        for (int i = 0; i < w.presetNames.length; i++) preset.option(i, str(w.presetNames[i]));
        sc.add(preset);
        for (WeatherStyles.Param p : w.params) {
            if (p.labels.length > 0) {
                Item c = Item.choice(p.key, str(p.label), p.value).help(str(p.help));
                for (int i = 0; i < p.labels.length; i++) c.option(i, str(p.labels[i]));
                sc.add(c.badge(str(R.string.badge_cpu), "cpu"));
            } else {
                sc.add(Item.slider(p.key, str(p.label), p.value, p.min, p.max, p.step).format("%d %%")
                        .help(str(p.help)).badge(str(R.string.badge_cpu), "cpu"));
            }
        }
        sc.add(Item.header(""));
        sc.add(Item.button("random", str(R.string.act_random_values)).help(str(R.string.help_random_weather)));
        sc.add(Item.button("reset", str(R.string.act_reset_weather)).help(str(R.string.help_reset_weather)));
        return sc;
    }

    // ================================================================== Changements

    @Override
    public Result set(String screen, String id, Object value) {
        Settings s = act.settings;
        fromPhone = true;
        try {
            if (screen.startsWith("wx:")) return setWeather(style(screen.substring(3)), id, value);
            switch (screen) {
                case "playlists":
                    if (id.startsWith("active/")) {
                        s.setActive(id.substring(7), Boolean.TRUE.equals(value));
                        return Result.ok();
                    }
                    break;
                case "packs":
                    if (id.startsWith("packs/")) return togglePack(id.substring(6), Boolean.TRUE.equals(value));
                    break;
                case "dock_apps":
                    return setDock(id, value);
                case "cinema":
                    if (id.equals("screensaver")) {
                        act.getPackageManager().setComponentEnabledSetting(dream(), Boolean.TRUE.equals(value)
                                        ? PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                                        : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                                PackageManager.DONT_KILL_APP);
                        return Result.ok();
                    }
                    break;
                case "home":
                    if (id.equals("city")) return searchCity(String.valueOf(value).trim());
                    break;
                case "tmdb":
                    if (id.equals("key")) return testKey(String.valueOf(value).replaceAll("\\s", ""));
                    break;
            }
            Settings.Item item = byKey(id);
            if (item == null) return Result.error("?", "unknown");
            if (item instanceof Settings.Flag) {
                ((Settings.Flag) item).value = Boolean.TRUE.equals(value);
            } else if (item instanceof Settings.Num) {
                ((Settings.Num) item).set(((Number) value).intValue());
            } else if (item instanceof Settings.Choice) {
                Settings.Choice c = (Settings.Choice) item;
                c.index = Math.max(0, Math.min(c.values.length - 1, ((Number) value).intValue()));
            } else {
                return Result.error("?", "unknown");
            }
            s.changed(item);
            return Result.ok();
        } finally {
            fromPhone = false;
        }
    }

    @Override
    public Result action(String screen, String id) {
        Settings s = act.settings;
        switch (id) {
            case "next":
                act.nextBackdrop();
                return Result.ok();
            case "test": {
                keyStatus = str(R.string.loading);
                Tmdb tmdb = act.tmdb();
                act.net.execute(() -> {
                    boolean ok;
                    try {
                        ok = tmdb.test();
                    } catch (Exception e) {
                        ok = false;
                    }
                    boolean good = ok;
                    act.ui.post(() -> {
                        keyStatus = str(good ? R.string.test_ok : R.string.test_failed);
                        if (server != null) server.changed("tmdb");
                    });
                });
                return Result.ok();
            }
            case "clear_cache":
                Tmdb.clearCache(act);
                return Result.ok();
            case "backup":
                backupStatus = str(act.backupSettings() ? R.string.backup_done : R.string.backup_failed);
                return Result.ok();
            case "restore":
                return act.restoreSettings() ? Result.ok() : Result.error(str(R.string.restore_none));
            case "reset_all":
                act.glassSettings.resetAll();
                act.glassChanged();
                s.resetAll();
                return Result.ok();
            case "random":
            case "reset": {
                WeatherStyles.Style w = screen.startsWith("wx:") ? style(screen.substring(3)) : null;
                if (w == null) break;
                if (id.equals("random")) w.randomize(random); else w.reset();
                weatherChanged();
                return Result.ok();
            }
        }
        return Result.error("?", "unknown");
    }

    @Override
    public void opened(String screen) {
        // Page d'une météo : elle s'affiche sur le fond tant qu'un téléphone la montre
        WeatherStyles.Style w = screen.startsWith("wx:") ? style(screen.substring(3)) : null;
        if (w != null) act.previewWeather(w.kind);
    }

    @Override
    public void closed(String screen) {
        if (screen.startsWith("wx:")) act.previewWeather(-1);
    }

    @Override
    public Image image(String key) {
        try {
            if (key.startsWith("backdrop")) {
                byte[] b = backdropJpeg;
                return b == null ? null : new Image(b, "image/jpeg", "bg" + backdropVersion);
            }
            if (key.startsWith("tmdb:")) {                      // tmdb:w300:/chemin.jpg
                int colon = key.indexOf(':', 5);
                String size = key.substring(5, colon), path = key.substring(colon + 1);
                if (!path.startsWith("/") || path.contains("..")) return null;
                return jpeg(Tmdb.thumb(act, size, path), size + path);
            }
            if (key.startsWith("playlist:")) {                  // premier fond de la playlist
                Playlists.Playlist p = Playlists.byId(act, key.substring(9));
                if (p == null) return null;
                for (Playlists.Film f : p.films) {
                    for (String path : f.backdrops) {
                        File file = new File(path);
                        if (!file.exists()) continue;
                        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                        o.inSampleSize = 4;
                        return jpeg(android.graphics.BitmapFactory.decodeFile(path, o), path + file.lastModified());
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Image de la télécommande " + key, e);
        }
        return null;
    }

    private static Image jpeg(Bitmap b, String version) {
        if (b == null) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, 82, out);
        return new Image(out.toByteArray(), "image/jpeg", Integer.toHexString(version.hashCode()));
    }

    // ---------------------------------------------------------------- Détails des changements

    private Result setWeather(WeatherStyles.Style w, String id, Object value) {
        if (w == null || !(value instanceof Number)) return Result.error("?", "unknown");
        int v = ((Number) value).intValue();
        if (id.equals("preset")) {
            if (v >= 0 && v < w.presetNames.length) w.applyPreset(v);
        } else {
            WeatherStyles.Param p = null;
            for (WeatherStyles.Param x : w.params) if (x.key.equals(id)) p = x;
            if (p == null) return Result.error("?", "unknown");
            p.value = p.labels.length > 0 ? Math.floorMod(v, p.labels.length) : Math.max(p.min, Math.min(p.max, v));
        }
        weatherChanged();
        return Result.ok();
    }

    private void weatherChanged() {
        act.weatherStylesChanged();
        if (act.panel != null && act.panel.isOpen()) act.panel.weatherStylesChanged();
    }

    private Result setDock(String id, Object value) {
        List<String> order = dockOrder();
        if (id.equals("order") && value instanceof org.json.JSONArray) {
            org.json.JSONArray a = (org.json.JSONArray) value;
            List<String> next = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                String pkg = unId(a.optString(i));
                if (order.contains(pkg) && !next.contains(pkg)) next.add(pkg);
            }
            for (String pkg : order) if (!next.contains(pkg)) next.add(pkg);
            order = next;
        } else if (id.startsWith("order/") || id.startsWith("others/")) {
            String pkg = unId(id.substring(id.indexOf('/') + 1));
            order.remove(pkg);
            if (Boolean.TRUE.equals(value)) order.add(pkg);
        } else {
            return Result.error("?", "unknown");
        }
        StringBuilder b = new StringBuilder();
        for (String p : order) b.append(b.length() == 0 ? "" : ",").append(p);
        act.settings.dockApps.value = b.toString();
        act.settings.changed(act.settings.dockApps);
        act.dockAppsChanged();
        return Result.ok();
    }

    /** Coche : installe le pack (en arrière-plan) ; décoche : le supprime avec ses images. */
    private Result togglePack(String packId, boolean on) {
        Settings s = act.settings;
        Playlists.Playlist installed = Playlists.byPack(act, packId);
        if (!on) {
            if (installed != null) {
                if (s.isActive(installed.id)) s.setActive(installed.id, false);
                Playlists.delete(act, installed);
            }
            return Result.ok();
        }
        if (installed != null) return Result.ok();
        String lang = packLang();
        boolean original = s.tmdbQuality.index == Settings.QUALITY_ORIGINAL;
        String loading = str(R.string.packs_error);
        act.net.execute(() -> {
            Playlists.Playlist p = null;
            try {
                p = Playlists.installPack(act, Packs.load(packId), lang, original);
            } catch (Exception e) {
                Log.w(TAG, "Pack " + packId, e);
            }
            Playlists.Playlist got = p;
            act.ui.post(() -> {
                if (got != null) {
                    s.setActive(got.id, true);
                    if (s.bgSource.index != Settings.SOURCE_PLAYLISTS) {
                        s.bgSource.index = Settings.SOURCE_PLAYLISTS;
                        s.changed(s.bgSource);
                    }
                } else {
                    Log.w(TAG, loading);
                }
                if (server != null) server.changed(null);
            });
        });
        return Result.ok();
    }

    private Result searchCity(String query) {
        if (query.isEmpty()) return Result.ok();
        cityStatus = str(R.string.weather_searching);
        Settings s = act.settings;
        act.searchCity(query, result -> {
            if (result == null) {
                cityStatus = str(R.string.weather_not_found);
            } else {
                cityStatus = "";
                s.weatherCity.value = result[0];
                s.weatherCoords.value = result[1];
                fromPhone = true;
                s.changed(s.weatherCoords);
                s.changed(s.weatherCity);
                fromPhone = false;
            }
            if (server != null) server.changed("home");
        });
        return Result.ok();
    }

    /** Clé TMDB envoyée du téléphone : vérifiée auprès de TMDB avant d'être enregistrée. */
    private Result testKey(String key) {
        if (key.isEmpty()) return Result.ok();
        keyStatus = str(R.string.loading);
        act.net.execute(() -> {
            boolean ok;
            try {
                ok = new Tmdb(key, "en-US").test();
            } catch (Exception e) {
                ok = false;
            }
            boolean good = ok;
            act.ui.post(() -> {
                if (good) {
                    act.settings.tmdbKey.value = key;
                    fromPhone = true;
                    act.settings.changed(act.settings.tmdbKey);
                    fromPhone = false;
                }
                keyStatus = str(good ? R.string.test_ok : R.string.key_page_bad);
                if (server != null) server.changed("tmdb");
            });
        });
        return Result.ok();
    }

    // ================================================================== Aides

    private Screen tab(String id, int title, int order) {
        return Screen.of(id, str(title));          // les onglets viennent de AppInfo.tab()
    }

    private void section(Screen sc, int title) {
        sc.add(Item.header(str(title)));
    }

    private void add(Screen sc, Settings.Item... items) {
        for (Settings.Item i : items) sc.add(item(i));
    }

    /** Un réglage du panneau, avec le même libellé, la même aide et la même pastille. */
    private Item item(Settings.Item i) {
        Item it;
        if (i instanceof Settings.Flag) {
            it = Item.toggle(i.key, str(i.label), ((Settings.Flag) i).value);
        } else if (i instanceof Settings.Num) {
            Settings.Num n = (Settings.Num) i;
            it = Item.slider(n.key, str(n.label), n.value, n.min, n.max, n.step).format(format(n.format));
        } else if (i instanceof Settings.Choice) {
            Settings.Choice c = (Settings.Choice) i;
            it = Item.choice(c.key, str(c.label), c.index);
            for (int k = 0; k < c.values.length; k++) {
                it.option(k, c.format == Settings.Format.LABELS ? str(c.labels[k]) : value(c.format, c.values[k]));
            }
        } else {
            it = Item.info(i.key, str(i.label), ((Settings.Text) i).value);
        }
        it.help(i.help == 0 ? "" : str(i.help));
        if (i.badge != 0) it.badge(str(i.badge), i.badge == R.string.badge_cpu ? "cpu"
                : i.badge == R.string.badge_internet ? "internet" : "experimental");
        return it;
    }

    /** Format d'un curseur pour la page (%d : la valeur entière). */
    private String format(Settings.Format f) {
        switch (f) {
            case SECONDS: return str(R.string.fmt_seconds);
            case PERCENT: return str(R.string.fmt_percent);
            case DEGREES: return str(R.string.fmt_degrees);
            case MILLIS: return "%d ms";
            case PIXELS: return str(R.string.fmt_pixels);
            default: return "%d";
        }
    }

    /** Valeur d'un choix non libellé, écrite comme dans le panneau. */
    private String value(Settings.Format f, int v) {
        switch (f) {
            case SECONDS:
                if (v == 0) return str(R.string.fmt_never);
                return v >= 60 ? act.getString(R.string.fmt_minutes, v / 60) : act.getString(R.string.fmt_seconds, v);
            case PERCENT: return act.getString(R.string.fmt_percent, v);
            case DEGREES: return act.getString(R.string.fmt_degrees, v);
            case MILLIS: return act.getString(R.string.fmt_millis, v / 1000f);
            case PIXELS: return act.getString(R.string.fmt_pixels, v);
            default: return String.valueOf(v);
        }
    }

    private Settings.Item byKey(String key) {
        for (Settings.Item i : act.settings.all) if (i.key.equals(key)) return i;
        return null;
    }

    private WeatherStyles.Style style(String kind) {
        for (WeatherStyles.Style w : act.weatherStyles.all) if (String.valueOf(w.kind).equals(kind)) return w;
        return null;
    }

    private String presetName(WeatherStyles.Style w) {
        int p = w.preset();
        return str(p < 0 ? R.string.preset_custom : w.presetNames[p]);
    }

    private String activeSummary() {
        int n = act.settings.activeIds().size();
        return n == 0 ? str(R.string.logo_none) : act.getResources().getQuantityString(R.plurals.playlists, n, n);
    }

    private String films(int n) {
        return act.getResources().getQuantityString(R.plurals.films, n, n);
    }

    private void downloads(Screen sc) {
        String st = Playlists.status();
        if (st != null && !st.isEmpty()) {
            sc.add(Item.header(str(R.string.sec_downloads)));
            sc.add(Item.info("downloads", str(R.string.set_downloads), st));
        }
    }

    private String packLang() {
        return act.settings.tmdbLanguage(Lang.tag(act)).substring(0, 2);
    }

    private List<String> dockOrder() {
        List<String> order = new ArrayList<>();
        for (String p : act.settings.dockApps.value.split(",")) if (!p.trim().isEmpty()) order.add(p.trim());
        return order;
    }

    private ComponentName dream() {
        return new ComponentName(act, CinemaDream.class);
    }

    private boolean dreamEnabled() {
        return act.getPackageManager().getComponentEnabledSetting(dream())
                != PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
    }
}
