package fr.proscenium.launcher;

import android.graphics.Color;

import java.util.Locale;
import java.util.Random;

/**
 * Météo choisie toute seule (modes Aléatoire et Selon le fond) : des couches cumulables (soleil,
 * pluie, éclairs, neige, brouillard), une force et un vent.
 *
 * <ul>
 * <li><b>Aléatoire</b> : chaque couche est tirée au sort, indépendamment des autres (de rien du tout
 *     à trois couches).</li>
 * <li><b>Selon le fond</b> : chaque couche reçoit une note d'après les genres du film, son titre, son
 *     accroche et son résumé (dans les six langues de l'appli) et l'image elle-même (lumière, couleur
 *     dominante) ; un peu de hasard s'y ajoute, et toutes les couches assez notées se cumulent.</li>
 * </ul>
 * Dans les deux cas, les éclairs viennent avec la pluie, et le soleil ne se mêle pas à l'orage.
 */
final class WeatherPicker {
    private WeatherPicker() {}

    /** Résultat : couches de WeatherFx, force (0 à 1), vent moyen et rafales (km/h). */
    static final class Pick {
        final int layers;
        final float amount;
        final int wind, gusts;

        Pick(int layers, float amount, int wind, int gusts) {
            this.layers = layers;
            this.amount = amount;
            this.wind = wind;
            this.gusts = gusts;
        }
    }

    private static final int SUN = 0, RAIN = 1, STORM = 2, SNOW = 3, FOG = 4;
    private static final int[] BITS = {WeatherFx.SUN, WeatherFx.RAIN, WeatherFx.STORM, WeatherFx.SNOW, WeatherFx.FOG};

    // ---------------------------------------------------------------- Aléatoire

    static Pick random(Random r) {
        float[] p = new float[5];
        // Probabilité de chaque couche ; l'orage n'est tiré que s'il pleut
        p[SUN] = r.nextFloat() < 0.38f ? 1f : 0f;
        p[RAIN] = r.nextFloat() < 0.36f ? 1f : 0f;
        p[STORM] = p[RAIN] > 0 && r.nextFloat() < 0.35f ? 1f : 0f;
        p[SNOW] = r.nextFloat() < 0.20f ? 1f : 0f;
        p[FOG] = r.nextFloat() < 0.28f ? 1f : 0f;
        int layers = combine(p, 0.5f, r);
        float amount = (layers & WeatherFx.STORM) != 0 ? 1f : 0.6f + 0.4f * r.nextFloat();
        return withWind(layers, amount, r);
    }

    // ---------------------------------------------------------------- Selon le fond

    /**
     * {@code genres}, {@code text} (titre, accroche, résumé) : vides s'ils manquent (photos Bing) ;
     * {@code lumTop}, {@code lumBottom} : luminosité du haut et du bas de l'image (0 à 1) ;
     * {@code accent} : couleur dominante de l'image.
     */
    static Pick smart(String genres, String text, float lumTop, float lumBottom, int accent, Random r) {
        float[] s = scores(genres, text, lumTop, lumBottom, accent);
        // Un peu de hasard : deux fonds semblables n'ont pas toujours la même météo
        for (int i = 0; i < s.length; i++) s[i] += r.nextFloat() * 0.25f - 0.08f;
        int layers = combine(s, 0.45f, r);
        if (layers == WeatherFx.NONE) {
            // Rien de net : la couche la mieux notée, si elle l'est un peu
            int best = 0;
            for (int i = 1; i < s.length; i++) if (s[i] > s[best]) best = i;
            if (s[best] >= 0.35f) layers = BITS[best] | (best == STORM ? WeatherFx.RAIN : 0);
        }
        float max = 0f;
        for (float x : s) max = Math.max(max, x);
        float amount = (layers & WeatherFx.STORM) != 0 ? 1f : Math.max(0.55f, Math.min(1f, 0.45f + 0.45f * max));
        return withWind(layers, amount, r);
    }

    /** Notes des couches (sans le hasard), de 0 à 1 environ. Visible pour les tests. */
    static float[] scores(String genres, String text, float lumTop, float lumBottom, int accent) {
        float[] s = new float[5];
        String g = genres == null ? "" : genres.toLowerCase(Locale.ROOT);
        String t = text == null ? "" : " " + text.toLowerCase(Locale.ROOT) + " ";

        // Genres (noms TMDB dans les six langues)
        if (any(g, "horror", "horreur", "orrore", "terror", "skräck")) { s[FOG] += 0.45f; s[RAIN] += 0.25f; s[STORM] += 0.25f; }
        if (any(g, "thriller")) { s[RAIN] += 0.35f; s[FOG] += 0.15f; }
        if (any(g, "western")) s[SUN] += 0.5f;
        if (any(g, "war", "guerre", "krieg", "guerra", "krig")) { s[FOG] += 0.35f; s[RAIN] += 0.2f; }
        if (any(g, "science", "fantascienza", "ciencia", "sci-fi")) s[FOG] += 0.25f;
        if (any(g, "fantas")) { s[FOG] += 0.2f; s[SNOW] += 0.1f; s[SUN] += 0.1f; }
        if (any(g, "myster", "mistero", "misterio")) { s[FOG] += 0.4f; s[RAIN] += 0.15f; }
        if (any(g, "animation", "animazione", "animación", "familie", "family", "famille", "famiglia", "familia", "familj")) {
            s[SUN] += 0.2f; s[SNOW] += 0.1f;
        }
        if (any(g, "adventure", "aventure", "abenteuer", "avventura", "aventura", "äventyr")) s[SUN] += 0.2f;
        if (any(g, "romance", "romantik", "romantic", "romántic", "romantique")) { s[RAIN] += 0.2f; s[SUN] += 0.15f; }
        if (any(g, "drama", "drame", "dramma")) s[RAIN] += 0.2f;
        if (any(g, "crime", "krimi", "crimine", "crimen", "kriminal")) s[RAIN] += 0.3f;

        // Mots du titre, de l'accroche et du résumé
        if (any(t, "snow", "neige", "schnee", " neve", "nieve", " snö", "winter", "hiver", "inverno", "invierno", "vinter",
                "christmas", "noël", "weihnacht", " natale", "navidad", " jul ", " ice ", "glace", " eis ", "ghiaccio", "hielo",
                "frozen", "arctic", "arctique", "arktis", "artico", "ártico", "polar", "antarct", "blizzard")) s[SNOW] += 0.6f;
        if (any(t, " rain", "pluie", "regen", "pioggia", "lluvia", " regn", "flood", "inondation", "überschwemm",
                "alluvione", "inundación", "översvämning", "monsoon", "mousson")) s[RAIN] += 0.45f;
        if (any(t, "storm", "tempête", "orage", "sturm", "gewitter", "tempesta", "temporale", "tormenta", "tempestad",
                "åska", "thunder", "tonnerre", "hurricane", "ouragan", "hurrikan", "uragano", "huracán", "orkan", "tornado")) {
            s[STORM] += 0.45f; s[RAIN] += 0.25f;
        }
        if (any(t, " fog", "brouillard", "brume", "nebel", "nebbia", "niebla", "dimma", " mist", "ghost", "fantôme",
                "gespenst", " geist", "fantasma", " spök", "haunt", "hanté", "spuk", "infestat", "embrujad", "vampir", "zombie")) {
            s[FOG] += 0.45f;
        }
        if (any(t, "desert", "désert", "wüste", "deserto", "desierto", "öken", "summer", " été ", "sommer", " estate ",
                "verano", "sommar", "beach", "plage", "strand", "spiaggia", "playa", " sun ", "soleil", "sonne", " sole ",
                " sol ", "tropic", "tropique", "safari", "sahara", "island", " île ", "insel", " isola ", " isla ")) {
            s[SUN] += 0.4f;
        }

        // L'image : un ciel clair et une lumière chaude appellent le soleil ; une image sombre et
        // bleutée la pluie ou la brume ; une image blanche et peu colorée la neige
        float[] hsv = new float[3];
        Color.colorToHSV(accent, hsv);
        float hue = hsv[0], sat = hsv[1], val = hsv[2];
        float lum = (lumTop + lumBottom) / 2f;
        boolean warm = sat > 0.25f && (hue < 60f || hue > 330f);
        boolean blue = sat > 0.2f && hue > 180f && hue < 260f;
        if (warm && val > 0.5f && lum > 0.4f) s[SUN] += 0.35f;
        if (lumTop > 0.55f && !blue) s[SUN] += 0.15f;
        if (lum > 0.55f && sat < 0.22f) { s[SNOW] += 0.2f; s[FOG] += 0.15f; }
        if (lum < 0.3f) { s[RAIN] += 0.15f; s[FOG] += 0.15f; }
        if (blue && lum < 0.45f) s[RAIN] += 0.2f;
        return s;
    }

    // ---------------------------------------------------------------- Commun

    /** Couches retenues : celles qui atteignent {@code threshold} (trois au plus), rendues cohérentes. */
    private static int combine(float[] s, float threshold, Random r) {
        int layers = WeatherFx.NONE, count = 0;
        Integer[] order = {SUN, RAIN, STORM, SNOW, FOG};
        java.util.Arrays.sort(order, (a, b) -> Float.compare(s[b], s[a]));
        for (int i : order) {
            if (s[i] < threshold || count >= 3) break;
            layers |= BITS[i];
            count++;
        }
        // Des éclairs : il pleut aussi, et le soleil se cache
        if ((layers & WeatherFx.STORM) != 0) {
            layers |= WeatherFx.RAIN;
            layers &= ~WeatherFx.SUN;
        }
        // Pluie et neige mêlées : un ciel trop chargé pour le soleil
        if ((layers & WeatherFx.RAIN) != 0 && (layers & WeatherFx.SNOW) != 0) layers &= ~WeatherFx.SUN;
        return layers;
    }

    /** Vent qui va avec : fort sous l'orage, modéré sous la pluie, léger sinon (parfois aucun). */
    private static Pick withWind(int layers, float amount, Random r) {
        int wind, gusts;
        if ((layers & WeatherFx.STORM) != 0) {
            wind = 35 + r.nextInt(30);
            gusts = wind + 25 + r.nextInt(30);
        } else if ((layers & WeatherFx.RAIN) != 0) {
            wind = 8 + r.nextInt(22);
            gusts = wind + 5 + r.nextInt(20);
        } else {
            wind = r.nextFloat() < 0.4f ? 0 : 5 + r.nextInt(12);
            gusts = wind == 0 ? 0 : wind + r.nextInt(10);
        }
        return new Pick(layers, amount, wind, gusts);
    }

    private static boolean any(String s, String... words) {
        for (String w : words) if (s.contains(w)) return true;
        return false;
    }
}
