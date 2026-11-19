package fr.proscenium.launcher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Contenu des onglets de réglages, décrit une seule fois : le panneau de la TV (PanelTabs) et la
 * télécommande web (ProsceniumRemote) le lisent tous les deux. Un réglage ajouté ici apparaît donc
 * des deux côtés, à la même place.
 *
 * Une entrée est un titre de section ({@link Section}), un réglage ({@link Settings.Item}, dessiné
 * selon son type), ou une ligne particulière ({@link Special}) que chaque côté dessine à sa façon (ou
 * pas : « ouvrir les paramètres d'Android » n'a pas de sens sur le téléphone).
 *
 * Les onglets Playlists et Verre restent faits à la main : grilles de vignettes, sous-pages.
 */
final class PanelLayout {
    private PanelLayout() {}

    static final class Section {
        final int title;

        Section(int title) { this.title = title; }
    }

    static final class Special {
        final String id;

        Special(String id) { this.id = id; }
    }

    private static Section sec(int title) { return new Section(title); }

    private static Special sp(String id) { return new Special(id); }

    /** Lignes particulières (identifiants partagés par les deux côtés). */
    static final String ACTIVE_PLAYLISTS = "active_playlists", NEXT_IMAGE = "next", SCREENSAVER = "screensaver",
            DREAM_SETTINGS = "dream_settings", DOCK_APPS = "dock_apps", CITY = "city", WEATHER_STYLES = "weather_styles",
            TMDB_KEY = "tmdb_key", TMDB_KEY_PHONE = "tmdb_key_phone", TMDB_TEST = "tmdb_test", CACHE = "cache",
            CLEAR_CACHE = "clear_cache", DOWNLOADS = "downloads", TMDB_ABOUT = "tmdb_about", WEB_REMOTE = "web_remote",
            SERVICE = "service", RESTRICTED = "restricted", ANDROID_SETTINGS = "android_settings", BACKUP = "backup", RESTORE = "restore",
            RESET_ALL = "reset_all", UPDATE = "update", WELCOME = "welcome", VERSION = "version", CREDITS = "credits";

    /** Entrées de l'onglet {@code tab} (PanelCore.TAB_…) ; vide pour Playlists et Verre. */
    static List<Object> of(int tab, Settings s) {
        switch (tab) {
            case PanelCore.TAB_BACKDROPS:
                return list(sec(R.string.sec_source), s.bgSource, sp(ACTIVE_PLAYLISTS), sp(NEXT_IMAGE),
                        sec(R.string.sec_rotation), s.bgDelay, s.bgOrder,
                        sec(R.string.sec_transition), s.bgTransition, s.bgTransitionMs,
                        sec(R.string.sec_ken_burns), s.kenBurns, s.kbAmplitude, s.kbCycle,
                        sec(R.string.sec_light), s.vignette, s.vignetteIntensity, s.themeAuto, s.themeColor, s.entrance,
                        s.parallax);
            case PanelCore.TAB_CINEMA:
                return list(sec(R.string.sec_activation), s.cinemaDelay, s.keepScreenOn,
                        sec(R.string.sec_bars), s.cinemaBars, s.barOpacity, s.cinemaKeepZoom,
                        sec(R.string.sec_title_card), s.titleMode, s.titleSide, s.showYear, s.titleInfo, s.titleRating,
                        s.titleSummary, s.titleTextSize,
                        sec(R.string.sec_clock_header), s.clockSeconds, s.hideHeader,
                        sec(R.string.sec_screensaver), sp(SCREENSAVER), sp(DREAM_SETTINGS));
            case PanelCore.TAB_DOCK:
                return list(sec(R.string.sec_apps), sp(DOCK_APPS),
                        sec(R.string.sec_band), s.bandMode, s.slimBand,
                        sec(R.string.sec_tiles), s.visibleApps, s.tileSize, s.tileSpacing, s.centerZoom, s.tilt, s.shine,
                        s.neighborDim,
                        sec(R.string.sec_glow), s.glowMode, s.glowPower, s.glowAnim, s.glowAnimAmount, s.glowAnimSpeed,
                        sec(R.string.sec_scroll), s.scrollSpeed, s.scrollParallax, s.loop);
            case PanelCore.TAB_HOME:
                return list(sec(R.string.sec_weather), sp(CITY), s.tempUnit, s.weatherFx, s.fxSun, s.fxRain, s.fxLightning,
                        s.fxSnow, s.fxFog, s.fxWind, s.weatherFxAmount, s.weatherLens, sp(WEATHER_STYLES),
                        sec(R.string.sec_time), s.timeFormat, s.clockSize, s.dateSize,
                        sec(R.string.sec_header), s.showCpu, s.showRam, s.showWeather, s.showWind, s.weatherAnim,
                        s.headerScale,
                        sec(R.string.sec_sounds), s.uiSounds, s.uiVolume);
            case PanelCore.TAB_TMDB:
                return list(sec(R.string.sec_tmdb_access), sp(TMDB_KEY), sp(TMDB_KEY_PHONE), sp(TMDB_TEST),
                        sec(R.string.sec_tmdb_images), s.tmdbLang, s.tmdbQuality, s.textlessOnly, s.fullHdOnly,
                        sec(R.string.sec_cache), sp(CACHE), sp(CLEAR_CACHE),
                        sp(DOWNLOADS), sec(R.string.sec_about), sp(TMDB_ABOUT));
            case PanelCore.TAB_SYSTEM:
                return list(sec(R.string.sec_language), s.language,
                        sec(R.string.sec_performance), s.economy,
                        sec(R.string.sec_web_remote), sp(WEB_REMOTE),
                        sec(R.string.sec_service), sp(SERVICE), sp(RESTRICTED), sp(ANDROID_SETTINGS),
                        sec(R.string.sec_maintenance), sp(BACKUP), sp(RESTORE), sp(RESET_ALL),
                        sec(R.string.sec_updates), sp(UPDATE), s.updateCheck,
                        sec(R.string.sec_about), sp(WELCOME), sp(VERSION), sp(CREDITS), sp(TMDB_ABOUT));
            default:
                return new ArrayList<>();
        }
    }

    /**
     * Réglages de l'onglet que « Réinitialiser cet onglet » remet à leur valeur d'origine (Système
     * n'en a pas : langue et économie se changent à part). Les lignes particulières y ajoutent les
     * leurs (ville, applis de la barre).
     */
    static boolean resettable(int tab) {
        return tab != PanelCore.TAB_SYSTEM;
    }

    private static List<Object> list(Object... entries) {
        return new ArrayList<>(Arrays.asList(entries));
    }
}
