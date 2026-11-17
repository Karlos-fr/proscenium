package fr.proscenium.launcher;

import android.content.Context;
import android.content.res.Configuration;

import java.util.Locale;

/**
 * Langue de l'interface : celle du système par défaut (anglais si elle n'est pas prise en charge),
 * ou celle choisie dans l'onglet Système. Les textes vivent dans res/values (anglais),
 * res/values-fr, -de, -it, -es et -sv.
 */
final class Lang {
    private Lang() {}

    /** Langues prises en charge, dans l'ordre du réglage (après « Automatique ») : code et pays. */
    static final String[][] SUPPORTED = {
            {"fr", "FR"}, {"en", "US"}, {"de", "DE"}, {"it", "IT"}, {"es", "ES"}, {"sv", "SE"}};

    /** Langue choisie : Settings.LANG_AUTO, ou 1 + rang dans SUPPORTED (lue directement dans les préférences). */
    static int choice(Context ctx) {
        return ctx.getSharedPreferences("proscenium", Context.MODE_PRIVATE).getInt("language", Settings.LANG_AUTO);
    }

    /** Locale de la langue n° {@code index} de SUPPORTED. */
    static Locale of(int index) {
        return new Locale(SUPPORTED[index][0], SUPPORTED[index][1]);
    }

    /** Locale effective de l'interface. */
    static Locale locale(Context ctx) {
        int c = choice(ctx);
        if (c > 0 && c <= SUPPORTED.length) return of(c - 1);
        Locale sys = Locale.getDefault();
        for (String[] l : SUPPORTED) if (l[0].equals(sys.getLanguage())) return sys;
        return Locale.US;
    }

    /** Contexte dont les ressources suivent la langue choisie (à utiliser dans attachBaseContext). */
    static Context wrap(Context base) {
        if (choice(base) == Settings.LANG_AUTO) return base;
        Configuration c = new Configuration(base.getResources().getConfiguration());
        c.setLocale(locale(base));
        return base.createConfigurationContext(c);
    }

    /** Code de langue pour les services en ligne : « fr-FR », « en-US », « de-DE »… */
    static String tag(Context ctx) {
        String language = locale(ctx).getLanguage();
        for (String[] l : SUPPORTED) if (l[0].equals(language)) return l[0] + "-" + l[1];
        return "en-US";
    }
}
