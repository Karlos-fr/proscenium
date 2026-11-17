package fr.proscenium.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Client de l'API TMDB (v3) : recherche de films, populaires, tendances,
 * fonds et logos d'un film. Les réponses sont gardées en mémoire (TMDB limite le débit),
 * les miniatures sur le disque (cache de l'appli). Tout s'appelle hors du fil principal.
 */
final class Tmdb {
    private static final String API = "https://api.themoviedb.org/3";
    static final String IMG = "https://image.tmdb.org/t/p/";

    static final class Movie {
        int id;
        String title = "", year = "", poster = "";
        double vote;
    }

    static final class Image {
        String path, lang;
        int width, height, count;
        double vote;
    }

    /** Réponses récentes, toutes requêtes confondues. */
    private static final Map<String, JSONObject> memo = Collections.synchronizedMap(
            new LinkedHashMap<String, JSONObject>(32, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, JSONObject> e) {
                    return size() > 40;
                }
            });

    private final String key, language;

    /** {@code language} : « fr-FR » ou « en-US » (titres des films). */
    Tmdb(String key, String language) {
        this.key = key == null ? "" : key.trim();
        this.language = language;
    }

    boolean hasKey() {
        return !key.isEmpty();
    }

    /** Clé du réglage, sinon celle déposée depuis le PC (fichier tmdb.key dans le dossier de l'appli). */
    static String resolveKey(Context ctx, String fromSettings) {
        if (fromSettings != null && !fromSettings.trim().isEmpty()) return fromSettings.trim();
        File f = keyFile(ctx);
        if (f == null || !f.exists()) return "";
        try {
            return new String(readAll(f), "UTF-8").trim();
        } catch (Exception e) {
            return "";
        }
    }

    static File keyFile(Context ctx) {
        File base = ctx.getExternalFilesDir(null);
        return base == null ? null : new File(base, "tmdb.key");
    }

    private JSONObject call(String path, String query) throws Exception {
        return call(path, query, true);
    }

    /** {@code cached} : false pour une liste qui change (tendances du jour), toujours redemandée à TMDB. */
    private JSONObject call(String path, String query, boolean useCache) throws Exception {
        if (!useCache) memo.remove(API + path + "?" + (query == null ? "" : query));
        if (key.isEmpty()) throw new IllegalStateException("Clé TMDB absente");
        String url = API + path + "?" + (query == null ? "" : query);
        // Clé v3 (32 caractères) dans l'adresse, jeton v4 (long) dans l'en-tête
        boolean bearer = key.length() > 40;
        String full = bearer ? url : url + (query == null ? "" : "&") + "api_key=" + key;
        JSONObject cached = memo.get(url);
        if (cached != null) return cached;
        JSONObject o = new JSONObject(Net.getText(full, bearer ? key : null));
        memo.put(url, o);
        return o;
    }

    /** Vérifie la clé : renvoie true si TMDB répond. */
    boolean test() {
        try {
            memo.remove(API + "/configuration?");
            call("/configuration", null);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    List<Movie> search(String query) throws Exception {
        return movies(call("/search/movie", "query=" + enc(query) + "&include_adult=false&language=" + language));
    }

    List<Movie> popular() throws Exception {
        return movies(call("/movie/popular", "language=" + language));
    }

    List<Movie> trending() throws Exception {
        return movies(call("/trending/movie/week", "language=" + language));
    }

    /** Tendances de la semaine, 20 films par page, toujours à jour (pas de cache). */
    List<Movie> trending(int page) throws Exception {
        return movies(call("/trending/movie/week", "language=" + language + "&page=" + page, false));
    }

    private static List<Movie> movies(JSONObject page) throws Exception {
        List<Movie> out = new ArrayList<>();
        JSONArray results = page.optJSONArray("results");
        if (results == null) return out;
        for (int i = 0; i < results.length(); i++) {
            JSONObject o = results.getJSONObject(i);
            Movie m = new Movie();
            m.id = o.getInt("id");
            m.title = o.optString("title", o.optString("name"));
            String date = o.optString("release_date", "");
            m.year = date.length() >= 4 ? date.substring(0, 4) : "";
            m.poster = o.isNull("poster_path") ? "" : o.optString("poster_path", "");
            m.vote = o.optDouble("vote_average", 0);
            out.add(m);
        }
        return out;
    }

    private JSONObject images(int id) throws Exception {
        return call("/movie/" + id + "/images", "include_image_language=null,fr,en");
    }

    /**
     * Fonds du film, les mieux notés d'abord (note puis nombre de votes).
     * {@code textless} : seulement les images sans texte ; {@code fullHd} : 1920 px de large au moins.
     */
    List<Image> backdrops(int id, boolean textless, boolean fullHd) throws Exception {
        List<Image> out = new ArrayList<>();
        for (Image im : list(images(id).optJSONArray("backdrops"))) {
            if (textless && im.lang != null) continue;
            if (fullHd && im.width < 1920) continue;
            out.add(im);
        }
        Collections.sort(out, (a, b) -> a.vote != b.vote ? Double.compare(b.vote, a.vote) : b.count - a.count);
        return out;
    }

    /** Logos PNG du film : d'abord dans la langue voulue, puis en anglais ou en français, les mieux notés d'abord. */
    List<Image> logos(int id, String lang) throws Exception {
        List<Image> first = new ArrayList<>(), second = new ArrayList<>();
        for (Image im : list(images(id).optJSONArray("logos"))) {
            if (im.lang == null || !im.path.endsWith(".png")) continue;
            if (im.lang.equals(lang)) first.add(im);
            else if (im.lang.equals("fr") || im.lang.equals("en")) second.add(im);
        }
        Collections.sort(first, (a, b) -> Double.compare(b.vote, a.vote));
        Collections.sort(second, (a, b) -> Double.compare(b.vote, a.vote));
        first.addAll(second);
        return first;
    }

    /** Le fond le plus adapté à l'accueil : sans texte et en Full HD si possible. */
    Image bestBackdrop(int id) throws Exception {
        List<Image> l = backdrops(id, true, true);
        if (l.isEmpty()) l = backdrops(id, true, false);
        if (l.isEmpty()) l = backdrops(id, false, true);
        if (l.isEmpty()) l = backdrops(id, false, false);
        return l.isEmpty() ? null : l.get(0);
    }

    private static List<Image> list(JSONArray a) throws Exception {
        List<Image> out = new ArrayList<>();
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            Image im = new Image();
            im.path = o.getString("file_path");
            im.lang = o.isNull("iso_639_1") ? null : o.optString("iso_639_1");
            im.width = o.optInt("width");
            im.height = o.optInt("height");
            im.vote = o.optDouble("vote_average", 0);
            im.count = o.optInt("vote_count");
            out.add(im);
        }
        return out;
    }

    // ---------------------------------------------------------------- Images

    /** Miniature (affiche w185, fond w300…) gardée dans le cache de l'appli. */
    static Bitmap thumb(Context ctx, String size, String path) throws Exception {
        File dir = new File(ctx.getCacheDir(), "thumbs");
        dir.mkdirs();
        File f = new File(dir, size + "_" + path.replace("/", ""));
        if (!f.exists()) {
            File tmp = new File(f.getPath() + ".tmp");
            write(tmp, Net.get(IMG + size + path));
            tmp.renameTo(f);
        }
        return BitmapFactory.decodeFile(f.getPath());
    }

    static File storeDir(Context ctx, String sub) {
        File base = ctx.getExternalFilesDir(null);
        if (base == null) base = ctx.getFilesDir();
        File d = new File(base, "tmdb/" + sub);
        d.mkdirs();
        return d;
    }

    /**
     * Télécharge un fond pour l'accueil : en qualité originale ramenée à 1920×1080 (JPEG 88),
     * ou en 1280 px tel quel. Ne refait rien si le fichier existe.
     */
    static File saveBackdrop(Context ctx, int movieId, Image im, boolean original) throws Exception {
        File f = new File(storeDir(ctx, "backdrops"), movieId + "_" + im.path.replace("/", "").replace(".png", ".jpg"));
        if (f.exists()) return f;
        byte[] raw = Net.get(IMG + (original ? "original" : "w1280") + im.path);
        File tmp = new File(f.getPath() + ".tmp");
        if (im.width == 0) {
            // Fond d'un pack : seul son chemin est connu, la taille se lit dans l'image
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(raw, 0, raw.length, bounds);
            im.width = bounds.outWidth;
            im.height = bounds.outHeight;
        }
        if (original && im.width > 1920) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 1;
            while (im.width / (o.inSampleSize * 2) >= 1920) o.inSampleSize *= 2;
            Bitmap src = BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
            if (src == null) throw new IllegalStateException("Image illisible");
            Bitmap out = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888);
            // Recadrage au centre si l'image n'est pas exactement en 16:9
            float scale = Math.max(1920f / src.getWidth(), 1080f / src.getHeight());
            int cw = Math.round(1920 / scale), ch = Math.round(1080 / scale);
            int cx = (src.getWidth() - cw) / 2, cy = (src.getHeight() - ch) / 2;
            new Canvas(out).drawBitmap(src, new Rect(cx, cy, cx + cw, cy + ch), new Rect(0, 0, 1920, 1080),
                    new Paint(Paint.FILTER_BITMAP_FLAG));
            src.recycle();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            out.compress(Bitmap.CompressFormat.JPEG, 88, bytes);
            out.recycle();
            write(tmp, bytes.toByteArray());
        } else {
            write(tmp, raw);
        }
        if (!tmp.renameTo(f)) throw new IllegalStateException("Renommage impossible");
        return f;
    }

    /** Logo du film (PNG transparent, 500 px de large). */
    static File saveLogo(Context ctx, int movieId, Image im) throws Exception {
        File f = new File(storeDir(ctx, "logos"), movieId + "_" + im.path.replace("/", ""));
        if (f.exists()) return f;
        File tmp = new File(f.getPath() + ".tmp");
        write(tmp, Net.get(IMG + "w500" + im.path));
        if (!tmp.renameTo(f)) throw new IllegalStateException("Renommage impossible");
        return f;
    }

    /** Taille du cache des miniatures, en octets. */
    static long cacheSize(Context ctx) {
        long n = 0;
        File[] files = new File(ctx.getCacheDir(), "thumbs").listFiles();
        if (files != null) for (File f : files) n += f.length();
        return n;
    }

    static void clearCache(Context ctx) {
        File[] files = new File(ctx.getCacheDir(), "thumbs").listFiles();
        if (files != null) for (File f : files) f.delete();
        memo.clear();
    }

    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8");
    }

    private static void write(File f, byte[] data) throws Exception {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(data);
        }
    }

    private static byte[] readAll(File f) throws Exception {
        byte[] raw = new byte[(int) f.length()];
        try (FileInputStream in = new FileInputStream(f)) {
            for (int off = 0, n; off < raw.length && (n = in.read(raw, off, raw.length - off)) > 0; ) off += n;
        }
        return raw;
    }
}
