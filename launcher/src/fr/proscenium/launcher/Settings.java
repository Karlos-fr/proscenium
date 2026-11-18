package fr.proscenium.launcher;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * Tous les réglages de Proscenium, persistants, avec leurs valeurs par défaut et leurs bornes.
 * Chaque modification prévient les écouteurs : l'écran d'accueil l'applique aussitôt (aperçu en direct).
 * Le verre a son propre modèle (GlassSettings), repris de Codex Glass.
 */
final class Settings {
    interface Listener { void onSettingChanged(Item item); }

    /** Façon d'afficher la valeur d'un choix numérique. */
    enum Format { LABELS, SECONDS, PERCENT, DEGREES, MILLIS, PIXELS }

    abstract static class Item {
        final String key;
        /** Ressources du libellé et de la description (pied du panneau). */
        final int label, help;
        /** Pastille : 0, ou R.string.badge_cpu / badge_experimental / badge_internet. */
        int badge;

        Item(String key, int label, int help) {
            this.key = key;
            this.label = label;
            this.help = help;
        }

        Item badge(int b) {
            badge = b;
            return this;
        }

        abstract void reset();
        abstract void load(SharedPreferences p);
        abstract void save(SharedPreferences.Editor e);
    }

    static final class Flag extends Item {
        final boolean def;
        boolean value;

        Flag(String key, int label, int help, boolean def) {
            super(key, label, help);
            this.def = value = def;
        }

        void reset() { value = def; }
        void load(SharedPreferences p) { value = p.getBoolean(key, def); }
        void save(SharedPreferences.Editor e) { e.putBoolean(key, value); }
    }

    /** Curseur numérique. */
    static final class Num extends Item {
        final int def, min, max, step;
        final Format format;
        int value;

        Num(String key, int label, int help, int def, int min, int max, int step, Format format) {
            super(key, label, help);
            this.def = value = def;
            this.min = min;
            this.max = max;
            this.step = step;
            this.format = format;
        }

        void set(int v) { value = Math.max(min, Math.min(max, v)); }
        float f() { return value / 100f; }
        void reset() { value = def; }
        void load(SharedPreferences p) { set(p.getInt(key, def)); }
        void save(SharedPreferences.Editor e) { e.putInt(key, value); }
    }

    /** Choix dans une liste : valeurs (secondes, ms…) et libellés (ressources) pour Format.LABELS. */
    static final class Choice extends Item {
        final int[] values;
        final int[] labels;
        final Format format;
        final int def;
        int index;

        Choice(String key, int label, int help, int def, Format format, int[] values, int... labels) {
            super(key, label, help);
            this.values = values;
            this.labels = labels;
            this.format = format;
            this.def = index = def;
        }

        int value() { return values[index]; }
        void step(int dir) { index = Math.floorMod(index + dir, values.length); }
        void reset() { index = def; }
        void load(SharedPreferences p) { index = Math.max(0, Math.min(values.length - 1, p.getInt(key, def))); }
        void save(SharedPreferences.Editor e) { e.putInt(key, index); }
    }

    /** Texte libre (ville de la météo…) avec données associées. */
    static final class Text extends Item {
        final String def;
        String value;

        Text(String key, int label, int help, String def) {
            super(key, label, help);
            this.def = value = def;
        }

        void reset() { value = def; }
        void load(SharedPreferences p) { value = p.getString(key, def); }
        void save(SharedPreferences.Editor e) { e.putString(key, value); }
    }

    private static int[] seq(int n) {
        int[] v = new int[n];
        for (int i = 0; i < n; i++) v[i] = i;
        return v;
    }

    // ---------------------------------------------------------------- Arrière-plans
    static final int SOURCE_BING = 0, SOURCE_PLAYLISTS = 1;
    final Choice bgSource = new Choice("bg_source", R.string.set_bg_source, R.string.help_bg_source, SOURCE_PLAYLISTS,
            Format.LABELS, seq(2), R.string.src_bing, R.string.src_playlists);
    /** Playlists affichées sur l'accueil (identifiants séparés par des virgules), mélangées entre elles. */
    final Text activePlaylists = new Text("active_playlists", R.string.set_active_playlists,
            R.string.help_active_playlists, "");

    List<String> activeIds() {
        List<String> ids = new ArrayList<>();
        for (String id : activePlaylists.value.split(",")) if (!id.trim().isEmpty()) ids.add(id.trim());
        return ids;
    }

    boolean isActive(String playlistId) {
        return activeIds().contains(playlistId);
    }

    /** Coche ou décoche une playlist pour l'accueil. */
    void setActive(String playlistId, boolean on) {
        List<String> ids = activeIds();
        ids.remove(playlistId);
        if (on) ids.add(playlistId);
        StringBuilder b = new StringBuilder();
        for (String id : ids) b.append(b.length() == 0 ? "" : ",").append(id);
        activePlaylists.value = b.toString();
        changed(activePlaylists);
    }
    final Choice bgDelay = new Choice("bg_delay", R.string.set_bg_delay, R.string.help_bg_delay, 0,
            Format.SECONDS, new int[]{10, 20, 30, 60, 300, 900, 1800});
    static final int ORDER_RANDOM = 0, ORDER_SEQUENTIAL = 1;
    final Choice bgOrder = new Choice("bg_order", R.string.set_bg_order, R.string.help_bg_order, ORDER_RANDOM,
            Format.LABELS, seq(2), R.string.order_random, R.string.order_sequential);
    static final int TRANSITION_BLUR_ZOOM = 0, TRANSITION_FADE = 1, TRANSITION_SLIDE = 2, TRANSITION_NONE = 3;
    final Choice bgTransition = new Choice("bg_transition", R.string.set_bg_transition, R.string.help_bg_transition,
            TRANSITION_BLUR_ZOOM, Format.LABELS, seq(4),
            R.string.transition_blur_zoom, R.string.transition_fade, R.string.transition_slide, R.string.transition_none);
    final Num bgTransitionMs = new Num("bg_transition_ms", R.string.set_bg_transition_ms, R.string.help_bg_transition_ms,
            800, 300, 2000, 100, Format.MILLIS);
    final Flag kenBurns = (Flag) new Flag("ken_burns", R.string.set_ken_burns, R.string.help_ken_burns, true)
            .badge(R.string.badge_cpu);
    final Num kbAmplitude = new Num("kb_amplitude", R.string.set_kb_amplitude, R.string.help_kb_amplitude,
            100, 0, 200, 10, Format.PERCENT);
    final Choice kbCycle = new Choice("kb_cycle", R.string.set_kb_cycle, R.string.help_kb_cycle, 2,
            Format.SECONDS, new int[]{30, 60, 120, 300});
    final Flag vignette = new Flag("vignette", R.string.set_vignette, R.string.help_vignette, true);
    final Num vignetteIntensity = new Num("vignette_intensity", R.string.set_vignette_intensity,
            R.string.help_vignette_intensity, 100, 0, 200, 10, Format.PERCENT);
    final Flag themeAuto = new Flag("theme_auto", R.string.set_theme_auto, R.string.help_theme_auto, true);
    /** Couleurs fixes proposées quand le thème n'est pas automatique. */
    static final int[] THEME_COLORS = {0xFF7FA7FF, 0xFF4FD8E8, 0xFF66D98C, 0xFFFFC24D, 0xFFFF8A4D,
            0xFFFF5C6C, 0xFFFF7FC8, 0xFFB08CFF};
    final Choice themeColor = new Choice("theme_color", R.string.set_theme_color, R.string.help_theme_color, 0,
            Format.LABELS, seq(8), R.string.color_blue, R.string.color_cyan, R.string.color_green, R.string.color_amber,
            R.string.color_orange, R.string.color_red, R.string.color_pink, R.string.color_purple);
    final Flag entrance = new Flag("entrance", R.string.set_entrance, R.string.help_entrance, true);
    final Num parallax = (Num) new Num("parallax", R.string.set_parallax, R.string.help_parallax,
            100, 0, 200, 10, Format.PERCENT).badge(R.string.badge_cpu);

    // ---------------------------------------------------------------- Mode cinéma
    final Choice cinemaDelay = new Choice("cinema_delay", R.string.set_cinema_delay, R.string.help_cinema_delay, 0,
            Format.SECONDS, new int[]{8, 15, 30, 60, 120, 300, 0});
    static final int BARS_NONE = 0, BARS_2_1 = 1, BARS_SCOPE = 2;
    final Choice cinemaBars = new Choice("cinema_bars", R.string.set_cinema_bars, R.string.help_cinema_bars, BARS_2_1,
            Format.LABELS, seq(3), R.string.bars_none, R.string.bars_2_1, R.string.bars_scope);
    final Num barOpacity = new Num("bar_opacity", R.string.set_bar_opacity, R.string.help_bar_opacity,
            100, 20, 100, 10, Format.PERCENT);
    /** Le fond garde sa taille en mode cinéma ; désactivé, il recule à sa taille réelle. */
    final Flag cinemaKeepZoom = new Flag("cinema_keep_zoom", R.string.set_cinema_keep_zoom,
            R.string.help_cinema_keep_zoom, true);
    static final int TITLE_LOGO = 0, TITLE_TEXT = 1, TITLE_NONE = 2;
    final Choice titleMode = new Choice("title_mode", R.string.set_title_mode, R.string.help_title_mode, TITLE_LOGO,
            Format.LABELS, seq(3), R.string.title_logo, R.string.title_text, R.string.title_none);
    static final int SIDE_RIGHT = 0, SIDE_LEFT = 1;
    final Choice titleSide = new Choice("title_side", R.string.set_title_side, R.string.help_title_side, SIDE_RIGHT,
            Format.LABELS, seq(2), R.string.side_right, R.string.side_left);
    final Flag showYear = new Flag("show_year", R.string.set_show_year, R.string.help_show_year, true);
    final Flag clockSeconds = new Flag("clock_seconds", R.string.set_clock_seconds, R.string.help_clock_seconds, true);
    final Flag hideHeader = new Flag("hide_header", R.string.set_hide_header, R.string.help_hide_header, true);

    // ---------------------------------------------------------------- Barre d'apps
    /** Applis du dock, dans l'ordre (noms de paquets séparés par des virgules). */
    final Text dockApps = new Text("dock_apps", R.string.set_dock_apps, R.string.help_dock_apps,
            "net.oqee.androidtv.mini4k,com.netflix.ninja,com.amazon.amazonvideo.livingroom,"
                    + "com.plexapp.android,com.google.android.youtube.tv");
    /** Raccourcis vers les entrées HDMI (consoles…) : JSON, voir InputShortcuts. */
    final Text inputShortcuts = new Text("input_shortcuts", R.string.set_input_shortcuts,
            R.string.help_input_shortcuts, "[]");
    static final int BAND_FULL = 0, BAND_FLOATING = 1;
    final Choice bandMode = new Choice("band_mode", R.string.set_band_mode, R.string.help_band_mode, BAND_FULL,
            Format.LABELS, seq(2), R.string.band_full, R.string.band_floating);
    /** Bande fine : l'appli sélectionnée, agrandie, dépasse du verre ; sinon elle y tient entière. */
    final Flag slimBand = new Flag("slim_band", R.string.set_slim_band, R.string.help_slim_band, true);
    /** Applis visibles d'un coup dans la barre : plus il y en a, plus les tuiles sont petites et serrées. */
    final Choice visibleApps = new Choice("visible_apps", R.string.set_visible_apps, R.string.help_visible_apps, 0,
            Format.LABELS, new int[]{5, 7, 9}, R.string.visible_5, R.string.visible_7, R.string.visible_9);
    final Num tileSize = new Num("tile_size", R.string.set_tile_size, R.string.help_tile_size,
            100, 70, 140, 5, Format.PERCENT);
    final Num tileSpacing = new Num("tile_spacing", R.string.set_tile_spacing, R.string.help_tile_spacing,
            100, 70, 160, 5, Format.PERCENT);
    final Num centerZoom = new Num("center_zoom", R.string.set_center_zoom, R.string.help_center_zoom,
            110, 100, 130, 2, Format.PERCENT);
    final Num tilt = new Num("tilt", R.string.set_tilt, R.string.help_tilt, 12, 0, 25, 1, Format.DEGREES);
    final Flag shine = new Flag("shine", R.string.set_shine, R.string.help_shine, true);
    final Num neighborDim = new Num("neighbor_dim", R.string.set_neighbor_dim, R.string.help_neighbor_dim,
            100, 0, 200, 10, Format.PERCENT);
    static final int GLOW_THEME = 0, GLOW_APP = 1, GLOW_NONE = 2;
    final Choice glowMode = new Choice("glow_mode", R.string.set_glow_mode, R.string.help_glow_mode, GLOW_APP,
            Format.LABELS, seq(3), R.string.glow_theme, R.string.glow_app, R.string.glow_none);
    /** Halo vivant : il ondule et s'avive au hasard ; coupé en mode économie. */
    final Flag glowAnim = new Flag("glow_anim", R.string.set_glow_anim, R.string.help_glow_anim, true);
    /** Ampleur et vitesse du halo vivant. */
    final Num glowAnimAmount = new Num("glow_anim_amount", R.string.set_glow_anim_amount,
            R.string.help_glow_anim_amount, 100, 20, 250, 10, Format.PERCENT);
    final Num glowAnimSpeed = new Num("glow_anim_speed", R.string.set_glow_anim_speed,
            R.string.help_glow_anim_speed, 100, 25, 300, 25, Format.PERCENT);
    /** Puissance du halo : jusqu'à 100 % il s'intensifie, au-delà il s'étend. */
    final Num glowPower = new Num("glow_power", R.string.set_glow_power, R.string.help_glow_power,
            100, 10, 200, 10, Format.PERCENT);
    final Choice scrollSpeed = new Choice("scroll_speed", R.string.set_scroll_speed, R.string.help_scroll_speed, 1,
            Format.LABELS, new int[]{250, 420, 700}, R.string.speed_fast, R.string.speed_normal, R.string.speed_slow);
    final Flag scrollParallax = new Flag("scroll_parallax", R.string.set_scroll_parallax,
            R.string.help_scroll_parallax, true);
    final Flag loop = new Flag("loop", R.string.set_loop, R.string.help_loop, true);

    // ---------------------------------------------------------------- Verre (en plus de GlassSettings)
    final Flag glassEngine = (Flag) new Flag("glass_engine", R.string.set_glass_engine, R.string.help_glass_engine, true)
            .badge(R.string.badge_cpu);
    final Num rimWidth = new Num("rim_width", R.string.set_rim_width, R.string.help_rim_width, 1, 0, 4, 1, Format.PIXELS);

    // ---------------------------------------------------------------- Accueil
    final Text weatherCity = new Text("weather_city", R.string.set_weather_city, R.string.help_weather_city,
            "Paris");
    final Text weatherCoords = new Text("weather_coords", R.string.set_weather_city, R.string.help_weather_city,
            "48.8566,2.3522");
    static final int UNIT_C = 0, UNIT_F = 1;
    final Choice tempUnit = new Choice("temp_unit", R.string.set_temp_unit, R.string.help_temp_unit, UNIT_C,
            Format.LABELS, seq(2), R.string.unit_c, R.string.unit_f);
    final Choice timeFormat = new Choice("time_format", R.string.set_time_format, R.string.help_time_format, 0,
            Format.LABELS, seq(2), R.string.time_24h, R.string.time_12h);
    /** Taille de l'heure (et de ses secondes) et de la date, en pourcentage de la taille d'origine. */
    final Num clockSize = new Num("clock_size", R.string.set_clock_size, R.string.help_clock_size,
            100, 50, 150, 5, Format.PERCENT);
    final Num dateSize = new Num("date_size", R.string.set_date_size, R.string.help_date_size,
            100, 50, 150, 5, Format.PERCENT);
    final Flag showCpu = new Flag("show_cpu", R.string.set_show_cpu, R.string.help_show_cpu, true);
    final Flag showRam = new Flag("show_ram", R.string.set_show_ram, R.string.help_show_ram, true);
    final Flag showWeather = new Flag("show_weather", R.string.set_show_weather, R.string.help_show_weather, true);
    /** Icône météo animée (soleil qui tourne, pluie qui tombe…), coupée en mode économie. */
    final Flag showWind = new Flag("show_wind", R.string.set_show_wind, R.string.help_show_wind, true);
    final Flag weatherAnim = new Flag("weather_anim", R.string.set_weather_anim, R.string.help_weather_anim, true);
    final Num headerScale = new Num("header_scale", R.string.set_header_scale, R.string.help_header_scale,
            75, 50, 120, 5, Format.PERCENT);

    // ---------------------------------------------------------------- Système
    static final int LANG_AUTO = 0;
    final Choice language = new Choice("language", R.string.set_language, R.string.help_language, LANG_AUTO,
            Format.LABELS, seq(7), R.string.lang_auto, R.string.lang_fr, R.string.lang_en, R.string.lang_de,
            R.string.lang_it, R.string.lang_es, R.string.lang_sv);
    final Flag economy = new Flag("economy", R.string.set_economy, R.string.help_economy, false);
    final Flag updateCheck = new Flag("update_check", R.string.set_update_check, R.string.help_update_check, true);

    // ---------------------------------------------------------------- Sons d'interface
    final Flag uiSounds = new Flag("ui_sounds", R.string.set_ui_sounds, R.string.help_ui_sounds, true);
    final Num uiVolume = new Num("ui_volume", R.string.set_ui_volume, R.string.help_ui_volume,
            40, 10, 100, 10, Format.PERCENT);

    // ---------------------------------------------------------------- Films (TMDB)
    /** Clé d'API TMDB ; vide = celle du fichier tmdb.key déposé depuis le PC. Jamais réinitialisée. */
    final Text tmdbKey = new Text("tmdb_key", R.string.set_tmdb_key, R.string.help_tmdb_key, "");
    static final int TMDB_LANG_APP = 0;
    final Choice tmdbLang = (Choice) new Choice("tmdb_lang", R.string.set_tmdb_lang, R.string.help_tmdb_lang,
            TMDB_LANG_APP, Format.LABELS, seq(7), R.string.tmdb_lang_app, R.string.lang_fr, R.string.lang_en,
            R.string.lang_de, R.string.lang_it, R.string.lang_es, R.string.lang_sv)
            .badge(R.string.badge_internet);
    static final int QUALITY_ORIGINAL = 0, QUALITY_1280 = 1;
    final Choice tmdbQuality = new Choice("tmdb_quality", R.string.set_tmdb_quality, R.string.help_tmdb_quality,
            QUALITY_ORIGINAL, Format.LABELS, seq(2), R.string.quality_original, R.string.quality_1280);
    final Flag textlessOnly = new Flag("textless_only", R.string.set_textless_only, R.string.help_textless_only, true);
    final Flag fullHdOnly = new Flag("full_hd_only", R.string.set_full_hd_only, R.string.help_full_hd_only, true);
    /** Fond choisi dans TMDB pour un film déjà présent : il remplace l'actuel, ou s'y ajoute. */
    static final int PICK_REPLACE = 0, PICK_ADD = 1;
    final Choice backdropPick = new Choice("backdrop_pick", R.string.set_backdrop_pick, R.string.help_backdrop_pick,
            PICK_REPLACE, Format.LABELS, seq(2), R.string.pick_replace, R.string.pick_add);

    final List<Item> all = new ArrayList<>();
    private final List<Listener> listeners = new ArrayList<>();
    private SharedPreferences prefs;

    Settings() {
        Item[] items = {bgSource, bgDelay, bgOrder, bgTransition, bgTransitionMs, kenBurns, kbAmplitude, kbCycle,
                vignette, vignetteIntensity, themeAuto, themeColor, entrance, parallax,
                cinemaDelay, cinemaBars, barOpacity, cinemaKeepZoom, titleMode, titleSide, showYear, clockSeconds, hideHeader,
                dockApps, inputShortcuts, bandMode, slimBand, visibleApps, tileSize, tileSpacing, centerZoom, tilt, shine, neighborDim, glowMode,
                glowPower, glowAnim, glowAnimAmount, glowAnimSpeed, scrollSpeed,
                scrollParallax, loop, glassEngine, rimWidth,
                weatherCity, weatherCoords, tempUnit, timeFormat, clockSize, dateSize, showCpu, showRam, showWeather, showWind, weatherAnim, headerScale,
                language, economy, updateCheck, uiSounds, uiVolume, tmdbLang, tmdbQuality, textlessOnly, fullHdOnly, backdropPick};
        for (Item i : items) all.add(i);
    }

    /** Langue des titres TMDB (« fr-FR », « de-DE »…) ; ses deux premières lettres choisissent les logos. */
    String tmdbLanguage(String appTag) {
        int i = tmdbLang.index - 1;
        if (i >= 0 && i < Lang.SUPPORTED.length) return Lang.SUPPORTED[i][0] + "-" + Lang.SUPPORTED[i][1];
        return appTag;
    }

    void load(SharedPreferences p) {
        prefs = p;
        // Anciennes préférences de source : « source » (0.3 à 0.23), puis Bing / dossier / playlist (0.24 à 0.29)
        if (!p.contains(bgSource.key) && p.contains("source")) {
            bgSource.index = "bing".equals(p.getString("source", "")) ? SOURCE_BING : SOURCE_PLAYLISTS;
        } else {
            bgSource.load(p);
        }
        for (Item i : all) if (i != bgSource) i.load(p);
        // Hors de « Réinitialiser tous les réglages » : la clé et le choix de playlist restent
        tmdbKey.load(p);
        activePlaylists.load(p);
    }

    void addListener(Listener l) {
        listeners.add(l);
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    /** À appeler après chaque modification : sauvegarde puis aperçu en direct. */
    void changed(Item item) {
        SharedPreferences.Editor e = prefs.edit();
        item.save(e);
        e.apply();
        for (Listener l : new ArrayList<>(listeners)) l.onSettingChanged(item);
    }

    void resetAll() {
        SharedPreferences.Editor e = prefs.edit();
        for (Item i : all) {
            i.reset();
            i.save(e);
        }
        e.apply();
        for (Listener l : new ArrayList<>(listeners)) l.onSettingChanged(null);
    }

    /** Les effets gourmands, sauf si le mode économie les coupe. */
    boolean kenBurnsOn() { return kenBurns.value && !economy.value; }
    boolean glowAnimOn() { return glowAnim.value && !economy.value; }
    boolean weatherAnimOn() { return weatherAnim.value && !economy.value; }
    float parallaxAmount() { return economy.value ? 0f : parallax.f(); }
    boolean scrollParallaxOn() { return scrollParallax.value && !economy.value; }
}
