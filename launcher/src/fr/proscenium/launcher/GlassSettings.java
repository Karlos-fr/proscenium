package fr.proscenium.launcher;

import android.content.SharedPreferences;

import java.util.Random;

/**
 * Réglages de l'effet verre du dock, repris de Codex Glass (src/glass/WidgetGlassSettings) :
 * une apparence permanente avec préréglages, et trois animations cumulables
 * (Eau calme, Liquide, Pluie). Toutes les valeurs sont en pourcentage, 100 = référence.
 */
final class GlassSettings {
    enum Preset { CLEAR, SUBTLE, FROSTED, STRONG, CUSTOM }

    /** Un réglage en pourcentage, avec ses bornes et sa clé de sauvegarde. */
    static final class Param {
        final String key;
        final int label;
        final int min, max, def;
        int value;

        Param(String key, int label, int min, int max, int def) {
            this.key = key;
            this.label = label;
            this.min = min;
            this.max = max;
            this.def = def;
            this.value = def;
        }

        float f() {
            return value / 100f;
        }

        void set(int v) {
            value = Math.max(min, Math.min(max, v));
        }
    }

    // Apparence permanente
    final Param diffusion = new Param("diffusion", R.string.glass_diffusion, 0, 200, 100);
    final Param tint = new Param("tint", R.string.glass_tint, 0, 200, 100);
    final Param grain = new Param("grain", R.string.glass_grain, 0, 200, 100);
    final Param edgeRefraction = new Param("edge_refraction", R.string.glass_edge_refraction, 0, 200, 100);
    final Param edgeWidth = new Param("edge_width", R.string.glass_edge_width, 25, 200, 100);
    final Param chromatic = new Param("chromatic", R.string.glass_chromatic, 0, 200, 100);
    final Param lensRefraction = new Param("lens_refraction", R.string.glass_lens_refraction, -200, 200, 100);
    final Param lensWidth = new Param("lens_width", R.string.glass_lens_width, 25, 250, 100);
    final Param lensSoftness = new Param("lens_softness", R.string.glass_lens_softness, 25, 200, 100);
    final Param[] appearance = {diffusion, tint, grain, edgeRefraction, edgeWidth, chromatic,
            lensRefraction, lensWidth, lensSoftness};

    // Eau calme
    boolean calmOn;
    final Param calmIntensity = new Param("calm_intensity", R.string.glass_intensity, 25, 200, 100);
    final Param calmSpeed = new Param("calm_speed", R.string.glass_speed, 25, 200, 100);
    final Param calmWavelength = new Param("calm_wavelength", R.string.glass_wavelength, 25, 200, 100);
    final Param calmNoise = new Param("calm_noise", R.string.glass_noise, 0, 200, 100);
    final Param[] calm = {calmIntensity, calmSpeed, calmWavelength, calmNoise};

    // Liquide
    boolean liquidOn;
    final Param liquidIntensity = new Param("liquid_intensity", R.string.glass_intensity, 25, 200, 100);
    final Param liquidSpeed = new Param("liquid_speed", R.string.glass_speed, 25, 200, 100);
    final Param liquidWavelength = new Param("liquid_wavelength", R.string.glass_wavelength, 25, 200, 100);
    final Param liquidFluidity = new Param("liquid_fluidity", R.string.glass_fluidity, 0, 200, 100);
    final Param liquidNoise = new Param("liquid_noise", R.string.glass_noise, 0, 200, 100);
    final Param[] liquid = {liquidIntensity, liquidSpeed, liquidWavelength, liquidFluidity, liquidNoise};

    // Pluie
    boolean rainOn;
    final Param rainIntensity = new Param("rain_intensity", R.string.glass_intensity, 25, 200, 100);
    final Param rainSpeed = new Param("rain_speed", R.string.glass_speed, 25, 200, 100);
    final Param rainDensity = new Param("rain_density", R.string.glass_density, 25, 250, 100);
    final Param rainRing = new Param("rain_ring", R.string.glass_ring, 50, 200, 100);
    final Param rainFade = new Param("rain_fade", R.string.glass_fade, 25, 200, 100);
    final Param[] rain = {rainIntensity, rainSpeed, rainDensity, rainRing, rainFade};

    /** Préréglages d'apparence de Codex Glass, dans l'ordre des 8 premiers réglages. */
    private static final int[][] PRESETS = {
            {46, 54, 40, 61, 72, 52, 63, 67},     // Clair
            {71, 79, 67, 78, 83, 70, 75, 83},     // Subtil
            {100, 100, 100, 100, 100, 100, 100, 100}, // Givré
            {121, 115, 133, 133, 121, 122, 138, 125}, // Fort
    };

    boolean animated() {
        return calmOn || liquidOn || rainOn;
    }

    Preset preset() {
        for (int p = 0; p < PRESETS.length; p++) {
            boolean same = lensSoftness.value == 100;
            for (int i = 0; i < 8 && same; i++) same = appearance[i].value == PRESETS[p][i];
            if (same) return Preset.values()[p];
        }
        return Preset.CUSTOM;
    }

    /** Libellé traduit du préréglage. */
    static int presetLabel(Preset p) {
        switch (p) {
            case CLEAR: return R.string.preset_clear;
            case SUBTLE: return R.string.preset_subtle;
            case STRONG: return R.string.preset_strong;
            case CUSTOM: return R.string.preset_custom;
            default: return R.string.preset_frosted;
        }
    }

    void applyPreset(Preset p) {
        if (p == Preset.CUSTOM) return;
        int[] v = PRESETS[p.ordinal()];
        for (int i = 0; i < 8; i++) appearance[i].set(v[i]);
        lensSoftness.set(100);
    }

    /** Préréglage suivant (Clair → Subtil → Givré → Fort → Clair). */
    void nextPreset(int dir) {
        Preset cur = preset();
        int i = cur == Preset.CUSTOM ? Preset.FROSTED.ordinal() : cur.ordinal();
        applyPreset(Preset.values()[Math.floorMod(i + dir, 4)]);
    }

    void randomizeAppearance(Random r) {
        randomize(appearance, r);
    }

    /** Valeurs au hasard pour un groupe de réglages (apparence ou une animation). */
    static void randomize(Param[] group, Random r) {
        for (Param p : group) p.set(p.min + r.nextInt(p.max - p.min + 1));
    }

    void resetAll() {
        for (Param[] group : new Param[][]{appearance, calm, liquid, rain}) for (Param p : group) p.value = p.def;
        calmOn = liquidOn = rainOn = false;
    }

    void load(SharedPreferences prefs) {
        for (Param[] group : new Param[][]{appearance, calm, liquid, rain}) {
            for (Param p : group) p.set(prefs.getInt("glass_" + p.key, p.def));
        }
        calmOn = prefs.getBoolean("glass_calm_on", false);
        liquidOn = prefs.getBoolean("glass_liquid_on", false);
        rainOn = prefs.getBoolean("glass_rain_on", false);
    }

    void save(SharedPreferences prefs) {
        SharedPreferences.Editor e = prefs.edit();
        for (Param[] group : new Param[][]{appearance, calm, liquid, rain}) {
            for (Param p : group) e.putInt("glass_" + p.key, p.value);
        }
        e.putBoolean("glass_calm_on", calmOn).putBoolean("glass_liquid_on", liquidOn).putBoolean("glass_rain_on", rainOn);
        e.apply();
    }
}
