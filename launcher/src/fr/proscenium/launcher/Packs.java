package fr.proscenium.launcher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Packs de fonds thématiques, publiés dans le dossier packs/ du dépôt GitHub du projet.
 *
 * Un pack ne contient aucune image : seulement des références TMDB (numéro du film, chemins des
 * fonds et des logos choisis, titres dans chaque langue). Les images sont téléchargées directement
 * depuis le serveur d'images de TMDB, sans clé d'API, au moment de l'installation.
 *
 *  - packs/index.json : {"version":1,"packs":[{"id","version":2,"name":{"fr":…},"description":{…},"films":89,"cover":"/….jpg"}]}
 *    « version » d'un pack augmente quand sa liste de films change : la playlist installée propose
 *    alors une mise à jour, qui ajoute les nouveaux films.
 *  - packs/<id>.json  : {"id","name":{…},"films":[{"id":408,"year":"1937","title":{…},"backdrops":["/….jpg"],"logo":{"fr":"/….png"}}]}
 */
final class Packs {
    static final String BASE = "https://raw.githubusercontent.com/Karlos-fr/proscenium/main/packs/";

    static final class Pack {
        String id, name, description, cover;
        int films, version = 1;
    }

    private Packs() {}

    /** Catalogue des packs, textes dans la langue {@code lang} (« fr », « de »…). Appel réseau. */
    static List<Pack> catalog(String lang) throws Exception {
        JSONArray a = new JSONObject(Net.getText(BASE + "index.json")).getJSONArray("packs");
        List<Pack> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            Pack p = new Pack();
            p.id = o.getString("id");
            p.name = pick(o.opt("name"), lang);
            p.description = pick(o.opt("description"), lang);
            p.cover = o.optString("cover");
            p.films = o.optInt("films");
            p.version = o.optInt("version", 1);
            out.add(p);
        }
        return out;
    }

    /** Contenu complet d'un pack. Appel réseau. */
    static JSONObject load(String id) throws Exception {
        return new JSONObject(Net.getText(BASE + id + ".json"));
    }

    /** Texte dans la langue voulue, sinon en anglais, sinon le premier venu ; accepte aussi un simple texte. */
    static String pick(Object value, String lang) {
        if (value instanceof String) return (String) value;
        if (!(value instanceof JSONObject)) return "";
        JSONObject o = (JSONObject) value;
        String s = o.optString(lang, "");
        if (s.isEmpty()) s = o.optString("en", "");
        if (s.isEmpty()) {
            Iterator<String> keys = o.keys();
            while (keys.hasNext() && s.isEmpty()) s = o.optString(keys.next(), "");
        }
        return s;
    }
}
