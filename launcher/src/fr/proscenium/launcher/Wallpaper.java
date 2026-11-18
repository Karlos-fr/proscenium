package fr.proscenium.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Fonds d'écran, deux sources au choix :
 *  - BING : les 8 dernières photos du jour, liste rafraîchie toutes les 6 h (files/bing/list.json) ;
 *  - PLAYLISTS : les fonds des playlists cochées, mélangés.
 * Pour chaque photo on calcule une fois : une version floutée minuscule (192x108)
 * pour le verre, et une couleur d'accent.
 */
final class Wallpaper {
    private static final String TAG = "Proscenium";
    private static final String BING_API = "https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=8&mkt=";
    /** Marché Bing, aligné sur la langue de l'interface (titres des photos traduits). */
    static volatile String market = "fr-FR";

    enum Source {
        BING("bing", 6 * 3600_000L),
        /** Fonds des playlists cochées pour l'accueil, relus à chaque fois. */
        PLAYLISTS("playlist", 0);

        /** Identifiants des playlists lues par la source PLAYLISTS. */
        static volatile List<String> playlistIds = new ArrayList<>();

        final String dir;
        final long maxAgeMs;

        Source(String dir, long maxAgeMs) {
            this.dir = dir;
            this.maxAgeMs = maxAgeMs;
        }
    }

    static final class Item {
        final File file;
        final String title, copyright, url;
        /** Logo du film (PNG transparent), ou null. */
        final File logo;
        /** Film de la playlist (fiche de la carte titre), ou null (photo Bing…). */
        Playlists.Film film;

        Item(File file, String title, String copyright, String url, File logo) {
            this.file = file;
            this.title = title;
            this.copyright = copyright;
            this.url = url;
            this.logo = logo;
        }
    }

    static final class Frame {
        final Bitmap sharp, blurred, logo;
        final String title, copyright;
        final int accent;
        /** Film de la playlist, ou null. */
        Playlists.Film film;
        /** Luminosité moyenne (0 à 1) du haut (heure, bandeau) et du bas (dock) de l'image. */
        final float lumTop, lumBottom;

        Frame(Bitmap sharp, Bitmap blurred, Bitmap logo, String title, String copyright, int accent) {
            this.sharp = sharp;
            this.blurred = blurred;
            this.logo = logo;
            this.title = title;
            this.copyright = copyright;
            this.accent = accent;
            this.lumTop = luminance(blurred, 0f, 0.3f);
            this.lumBottom = luminance(blurred, 0.65f, 1f);
        }

        private static float luminance(Bitmap b, float fromY, float toY) {
            int w = b.getWidth(), y0 = (int) (b.getHeight() * fromY), y1 = (int) (b.getHeight() * toY);
            int[] px = new int[w * (y1 - y0)];
            b.getPixels(px, 0, w, 0, y0, w, y1 - y0);
            double sum = 0;
            for (int c : px) sum += 0.2126 * ((c >> 16) & 0xff) + 0.7152 * ((c >> 8) & 0xff) + 0.0722 * (c & 0xff);
            return (float) (sum / px.length / 255.0);
        }
    }

    private Wallpaper() {}

    private static File dir(Context ctx, Source s) {
        File d = new File(ctx.getFilesDir(), s.dir);
        d.mkdirs();
        return d;
    }

    /** Met la liste à jour si elle est trop ancienne. Renvoie true si elle a changé. Hors fil principal. */
    static boolean refresh(Context ctx, Source s) {
        if (s != Source.BING) return false;
        // Fichiers des versions précédentes
        new File(ctx.getFilesDir(), "bing.jpg").delete();
        new File(ctx.getFilesDir(), "bing.json").delete();
        for (String old : new String[]{"tmdb", "films", "wallhaven"}) {
            File[] leftovers = new File(ctx.getFilesDir(), old).listFiles();
            if (leftovers != null) for (File f : leftovers) f.delete();
            new File(ctx.getFilesDir(), old).delete();
        }

        File d = dir(ctx, s), meta = new File(d, "list.json");
        if (meta.exists() && System.currentTimeMillis() - meta.lastModified() < s.maxAgeMs) return false;
        try {
            String before = meta.exists() ? read(meta) : "";
            JSONArray list;
            list = fetchBing(d);
            if (list.length() == 0) return false;
            String now = list.toString();
            write(meta, now.getBytes(StandardCharsets.UTF_8));

            // Ménage : on ne garde que les images encore listées
            Set<String> keep = new HashSet<>();
            keep.add(meta.getName());
            for (int i = 0; i < list.length(); i++) keep.add(list.getJSONObject(i).getString("file"));
            File[] all = d.listFiles();
            if (all != null) for (File f : all) if (!keep.contains(f.getName())) f.delete();
            return !now.equals(before);
        } catch (Exception e) {
            Log.w(TAG, "Liste " + s + " indisponible", e);
            return false;
        }
    }

    private static JSONArray fetchBing(File d) throws Exception {
        JSONArray images = new JSONObject(Net.getText(BING_API + market)).getJSONArray("images");
        JSONArray list = new JSONArray();
        for (int i = 0; i < images.length(); i++) {
            JSONObject o = images.getJSONObject(i);
            String base = o.getString("urlbase");
            String name = base.substring(base.indexOf("id=") + 3).replaceAll("[^A-Za-z0-9_.-]", "_") + ".jpg";
            list.put(new JSONObject()
                    .put("file", name)
                    .put("title", o.optString("title"))
                    .put("copyright", o.optString("copyright"))
                    .put("url", "https://www.bing.com" + base + "_1920x1080.jpg"));
            download(new File(d, name), "https://www.bing.com" + base + "_1920x1080.jpg");
        }
        return list;
    }

    /** Photos de la source, la plus récente (ou la mieux classée) en premier. */
    static List<Item> items(Context ctx, Source s) {
        List<Item> out = new ArrayList<>();
        if (s == Source.PLAYLISTS) {
            // Une image présente dans deux playlists cochées n'est comptée qu'une fois
            Set<String> seen = new HashSet<>();
            for (String id : Source.playlistIds) {
                Playlists.Playlist p = Playlists.byId(ctx, id);
                if (p == null) continue;
                for (Playlists.Film f : p.films) {
                    // « Titre (année) » : la carte titre du mode cinéma en tire l'année
                    String title = f.year.isEmpty() ? f.shownTitle() : f.shownTitle() + " (" + f.year + ")";
                    File logo = f.logo.isEmpty() ? null : new File(f.logo);
                    for (String b : f.backdrops) {
                        File img = new File(b);
                        if (img.exists() && seen.add(b)) {
                            Item item = new Item(img, title, "TMDB", null, logo != null && logo.exists() ? logo : null);
                            item.film = f;
                            out.add(item);
                        }
                    }
                }
            }
            return out;
        }
        File d = dir(ctx, s), meta = new File(d, "list.json");
        if (!meta.exists()) return out;
        try {
            JSONArray list = new JSONArray(read(meta));
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.getJSONObject(i);
                File f = new File(d, o.getString("file"));
                String url = o.optString("url", "");
                if (f.exists() || !url.isEmpty()) {
                    out.add(new Item(f, o.optString("title"), o.optString("copyright"), url.isEmpty() ? null : url, null));
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Liste " + s + " illisible", e);
        }
        return out;
    }

    static int count(Context ctx, Source s) {
        return items(ctx, s).size();
    }

    /** Télécharge l'image si besoin, puis la décode (1920 px de large au plus). Hors fil principal. */
    static Frame decode(Item it) throws Exception {
        if (!it.file.exists() && it.url != null) download(it.file, it.url);

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(it.file.getPath(), bounds);
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.RGB_565; // 4 Mo au lieu de 8 pour du 1080p
        o.inSampleSize = 1;
        while (bounds.outWidth / (o.inSampleSize * 2) >= 1920) o.inSampleSize *= 2;
        Bitmap sharp = BitmapFactory.decodeFile(it.file.getPath(), o);
        if (sharp == null) {
            it.file.delete();
            throw new IllegalStateException("Image illisible : " + it.file);
        }

        Bitmap small = Bitmap.createScaledBitmap(sharp, 192, 108, true).copy(Bitmap.Config.ARGB_8888, true);
        Blur.box(small, 3, 3);
        Bitmap logo = it.logo != null ? BitmapFactory.decodeFile(it.logo.getPath()) : null;
        Frame frame = new Frame(sharp, small, logo, it.title, it.copyright, Palette.accentOf(small));
        frame.film = it.film;
        return frame;
    }

    private static void download(File f, String url) throws Exception {
        if (f.exists()) return;
        File tmp = new File(f.getPath() + ".tmp");
        write(tmp, Net.get(url));
        if (!tmp.renameTo(f)) throw new IllegalStateException("Renommage impossible");
    }

    private static String read(File f) throws Exception {
        byte[] raw = new byte[(int) f.length()];
        try (FileInputStream in = new FileInputStream(f)) {
            for (int off = 0, n; off < raw.length && (n = in.read(raw, off, raw.length - off)) > 0; ) off += n;
        }
        return new String(raw, StandardCharsets.UTF_8);
    }

    private static void write(File f, byte[] data) throws Exception {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(data);
        }
    }
}
