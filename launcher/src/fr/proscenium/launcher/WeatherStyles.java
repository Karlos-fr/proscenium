package fr.proscenium.launcher;

import android.content.SharedPreferences;

/**
 * Réglages fins de chaque météo du fond (WeatherFx), sur le modèle du verre (GlassSettings) et des
 * éditeurs de Platformer : pour chaque météo, des préréglages qui changent tous les réglages d'un
 * coup, puis chaque réglage à part. Les valeurs sont en pourcentage (100 = référence), sauf la
 * couleur de la lueur des éclairs (un choix dans une liste).
 */
final class WeatherStyles {
    /** Un réglage : pourcentage borné, ou choix dans une liste si {@code labels} n'est pas vide. */
    static final class Param {
        final String key;
        final int label, help, min, max, def, step;
        final int[] labels;
        int value;

        Param(String key, int label, int help, int min, int max, int def, int... labels) {
            this.key = key;
            this.label = label;
            this.help = help;
            this.min = min;
            this.max = max;
            this.def = def;
            this.labels = labels;
            this.step = labels.length > 0 ? 1 : 10;
            value = def;
        }

        float f() {
            return value / 100f;
        }

        void step(int dir) {
            if (labels.length > 0) value = Math.floorMod(value + dir, labels.length);
            else value = Math.max(min, Math.min(max, value + dir * step));
        }
    }

    /** Une météo : ses réglages et ses préréglages (valeurs dans l'ordre des réglages). */
    static final class Style {
        final int kind, name, help;
        final Param[] params;
        final int[] presetNames;
        final int[][] presets;
        /** Préréglage de référence (valeurs par défaut). */
        final int reference;

        Style(int kind, int name, int help, Param[] params, int reference, int[] presetNames, int[][] presets) {
            this.kind = kind;
            this.name = name;
            this.help = help;
            this.params = params;
            this.reference = reference;
            this.presetNames = presetNames;
            this.presets = presets;
        }

        /** Préréglage dont toutes les valeurs correspondent, ou -1 (personnalisé). */
        int preset() {
            for (int p = 0; p < presets.length; p++) {
                boolean same = true;
                for (int i = 0; i < params.length && same; i++) same = params[i].value == presets[p][i];
                if (same) return p;
            }
            return -1;
        }

        void applyPreset(int p) {
            for (int i = 0; i < params.length; i++) params[i].value = presets[p][i];
        }

        void nextPreset(int dir) {
            int cur = preset();
            applyPreset(Math.floorMod((cur < 0 ? reference : cur) + dir, presets.length));
        }

        void reset() {
            applyPreset(reference);
        }

        /**
         * Valeurs au hasard, dans une plage qui reste crédible (de 30 à 220 %, par pas de 10) ;
         * une couleur au hasard parmi celles proposées.
         */
        void randomize(java.util.Random r) {
            for (Param p : params) {
                if (p.labels.length > 0) {
                    p.value = r.nextInt(p.labels.length);
                } else {
                    int lo = Math.max(p.min, 30), hi = Math.min(p.max, 220);
                    p.value = lo + r.nextInt((hi - lo) / 10 + 1) * 10;
                }
            }
        }
    }

    // ---------------------------------------------------------------- Pluie : gouttes sur l'écran
    final Param rainDrops = new Param("wx_rain_drops", R.string.wx_drops, R.string.help_wx_drops, 0, 300, 100);
    final Param rainSize = new Param("wx_rain_size", R.string.wx_drop_size, R.string.help_wx_drop_size, 40, 200, 100);
    final Param rainSlide = new Param("wx_rain_slide", R.string.wx_slide, R.string.help_wx_slide, 20, 300, 100);
    final Param rainTrails = new Param("wx_rain_trails", R.string.wx_trails, R.string.help_wx_trails, 0, 250, 100);
    final Param rainWind = new Param("wx_rain_wind", R.string.wx_wind, R.string.help_wx_wind, 0, 300, 100);
    final Param rainDim = new Param("wx_rain_dim", R.string.wx_dim, R.string.help_wx_dim, 0, 300, 100);
    final Style rain = new Style(WeatherFx.RAIN, R.string.fx_rain, R.string.help_wx_page_rain,
            new Param[]{rainDrops, rainSize, rainSlide, rainTrails, rainWind, rainDim}, 1,
            new int[]{R.string.wxp_drizzle, R.string.wxp_shower, R.string.wxp_downpour},
            new int[][]{
                    {45, 70, 70, 60, 100, 50},       // Bruine
                    {100, 100, 100, 100, 100, 100},  // Averse
                    {190, 120, 130, 140, 120, 160},  // Pluie battante
            });

    // ---------------------------------------------------------------- Orage : éclairs (studio Platformer)
    static final int[] GLOW_COLORS = {0x0088FF, 0x44AAFF, 0x5577AA, 0xFF44FF};
    final Param stormFrequency = new Param("wx_storm_frequency", R.string.wx_frequency, R.string.help_wx_frequency, 20, 400, 100);
    final Param stormForks = new Param("wx_storm_forks", R.string.wx_forks, R.string.help_wx_forks, 0, 250, 100);
    final Param stormChaos = new Param("wx_storm_chaos", R.string.wx_chaos, R.string.help_wx_chaos, 20, 200, 100);
    final Param stormWidth = new Param("wx_storm_width", R.string.wx_bolt_width, R.string.help_wx_bolt_width, 20, 250, 100);
    final Param stormDuration = new Param("wx_storm_duration", R.string.wx_bolt_duration, R.string.help_wx_bolt_duration, 20, 300, 100);
    final Param stormFlicker = new Param("wx_storm_flicker", R.string.wx_flicker, R.string.help_wx_flicker, 20, 250, 100);
    final Param stormGlow = new Param("wx_storm_glow", R.string.wx_glow_color, R.string.help_wx_glow_color, 0, 3, 0,
            R.string.color_blue, R.string.color_cyan, R.string.wx_steel, R.string.color_purple);
    final Param stormFlash = new Param("wx_storm_flash", R.string.wx_sky_flash, R.string.help_wx_sky_flash, 0, 250, 100);
    final Param stormDim = new Param("wx_storm_dim", R.string.wx_dim, R.string.help_wx_dim, 0, 250, 100);
    final Style storm = new Style(WeatherFx.STORM, R.string.fx_storm, R.string.help_wx_page_storm,
            new Param[]{stormFrequency, stormForks, stormChaos, stormWidth, stormDuration, stormFlicker, stormGlow,
                    stormFlash, stormDim}, 0,
            new int[]{R.string.wxp_storm_standard, R.string.wxp_storm_intense, R.string.wxp_storm_far,
                    R.string.wxp_storm_deluge},
            // Préréglages de thunder-presets.json, rapportés à « Orage Standard »
            new int[][]{
                    {100, 100, 100, 100, 100, 100, 0, 100, 100},  // Orage standard
                    {300, 170, 130, 150, 70, 170, 1, 160, 170},   // Foudre intense
                    {40, 60, 60, 50, 200, 50, 2, 40, 60},         // Tempête lointaine
                    {400, 200, 80, 80, 60, 200, 3, 180, 190},     // Déluge électrique
            });

    // ---------------------------------------------------------------- Neige
    final Param snowFlakes = new Param("wx_snow_flakes", R.string.wx_flakes, R.string.help_wx_flakes, 0, 250, 100);
    final Param snowSize = new Param("wx_snow_size", R.string.wx_flake_size, R.string.help_wx_flake_size, 40, 200, 100);
    final Param snowFall = new Param("wx_snow_fall", R.string.wx_fall_speed, R.string.help_wx_fall_speed, 20, 300, 100);
    final Param snowLens = new Param("wx_snow_lens", R.string.wx_lens_flakes, R.string.help_wx_lens_flakes, 0, 300, 100);
    final Param snowWind = new Param("wx_snow_wind", R.string.wx_wind, R.string.help_wx_wind, 0, 300, 100);
    final Param snowVeil = new Param("wx_snow_veil", R.string.wx_veil, R.string.help_wx_veil, 0, 300, 100);
    final Style snow = new Style(WeatherFx.SNOW, R.string.fx_snow, R.string.help_wx_page_snow,
            new Param[]{snowFlakes, snowSize, snowFall, snowLens, snowWind, snowVeil}, 1,
            new int[]{R.string.wxp_flurries, R.string.wxp_snow, R.string.wxp_blizzard},
            new int[][]{
                    {40, 80, 80, 40, 100, 50},       // Flocons légers
                    {100, 100, 100, 100, 100, 100},  // Neige
                    {200, 110, 160, 180, 220, 180},  // Tempête de neige
            });

    // ---------------------------------------------------------------- Brouillard
    final Param fogDensity = new Param("wx_fog_density", R.string.wx_density, R.string.help_wx_density, 0, 250, 100);
    final Param fogDrift = new Param("wx_fog_drift", R.string.wx_drift, R.string.help_wx_drift, 0, 300, 100);
    final Param fogLow = new Param("wx_fog_low", R.string.wx_low, R.string.help_wx_low, 0, 150, 100);
    final Param fogVeil = new Param("wx_fog_veil", R.string.wx_veil, R.string.help_wx_veil, 0, 300, 100);
    final Style fog = new Style(WeatherFx.FOG, R.string.fx_fog, R.string.help_wx_page_fog,
            new Param[]{fogDensity, fogDrift, fogLow, fogVeil}, 1,
            new int[]{R.string.wxp_mist, R.string.wxp_fog, R.string.wxp_peasouper},
            new int[][]{
                    {50, 100, 120, 60},    // Brume
                    {100, 100, 100, 100},  // Brouillard
                    {170, 70, 80, 170},    // Purée de pois
            });

    // ---------------------------------------------------------------- Soleil : rayons de lumière
    final Param sunRays = new Param("wx_sun_rays", R.string.wx_sun_rays, R.string.help_wx_sun_rays, 0, 250, 100);
    final Param sunLength = new Param("wx_sun_length", R.string.wx_sun_length, R.string.help_wx_sun_length, 20, 200, 100);
    final Param sunThreshold = new Param("wx_sun_threshold", R.string.wx_sun_threshold, R.string.help_wx_sun_threshold, 20, 200, 100);
    final Param sunPosition = new Param("wx_sun_position", R.string.wx_sun_position, R.string.help_wx_sun_position, 0, 100, 70);
    final Param sunWarmth = new Param("wx_sun_warmth", R.string.wx_sun_warmth, R.string.help_wx_sun_warmth, 0, 200, 100);
    final Param sunGlow = new Param("wx_sun_glow", R.string.wx_sun_glow, R.string.help_wx_sun_glow, 0, 250, 100);
    final Param sunShimmer = new Param("wx_sun_shimmer", R.string.wx_sun_shimmer, R.string.help_wx_sun_shimmer, 0, 300, 100);
    final Param sunDust = new Param("wx_sun_dust", R.string.wx_sun_dust, R.string.help_wx_sun_dust, 0, 300, 60);
    final Param sunClouds = new Param("wx_sun_clouds", R.string.wx_sun_clouds, R.string.help_wx_sun_clouds, 0, 140, 100);
    final Style sun = new Style(WeatherFx.SUN, R.string.fx_sun, R.string.help_wx_page_sun,
            new Param[]{sunRays, sunLength, sunThreshold, sunPosition, sunWarmth, sunGlow, sunShimmer, sunDust, sunClouds}, 0,
            new int[]{R.string.wxp_sun_full, R.string.wxp_sun_morning, R.string.wxp_sun_sunset, R.string.wxp_sun_forest,
                    R.string.wxp_sun_soft},
            new int[][]{
                    {100, 100, 100, 70, 100, 100, 100, 60, 100},   // Plein soleil
                    {120, 140, 80, 20, 150, 120, 80, 120, 60},     // Matin doré
                    {140, 160, 70, 90, 200, 150, 60, 40, 120},     // Coucher de soleil
                    {170, 180, 130, 50, 90, 60, 120, 200, 80},     // Sous-bois
                    {50, 70, 120, 70, 60, 50, 60, 0, 40},          // Discret
            });

    final Style[] all = {sun, rain, storm, snow, fog};

    /** Couleur de la lueur des éclairs (RGB). */
    int glowColor() {
        return GLOW_COLORS[Math.max(0, Math.min(GLOW_COLORS.length - 1, stormGlow.value))];
    }

    void load(SharedPreferences prefs) {
        for (Style st : all) for (Param p : st.params) {
            int v = prefs.getInt(p.key, p.def);
            p.value = p.labels.length > 0 ? Math.floorMod(v, p.labels.length) : Math.max(p.min, Math.min(p.max, v));
        }
    }

    void save(SharedPreferences prefs) {
        SharedPreferences.Editor e = prefs.edit();
        for (Style st : all) for (Param p : st.params) e.putInt(p.key, p.value);
        e.apply();
    }
}
