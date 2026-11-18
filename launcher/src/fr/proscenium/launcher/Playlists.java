package fr.proscenium.launcher;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Playlists de films : chaque film garde ses fonds (fichiers 1920×1080) et son logo.
 * Stockage : playlists.json dans le dossier de l'appli (récupérable par adb), images dans tmdb/.
 * L'ancien dossier « fonds » (rempli depuis le PC jusqu'à la 0.29) est migré une fois en playlist
 * ordinaire. Les téléchargements tournent en arrière-plan, un film après l'autre.
 */
final class Playlists {
    private static final String TAG = "Proscenium";

    static final class Film {
        int tmdbId;
        String title = "", year = "", poster = "", customTitle = "";
        /** Chemins absolus des fonds (fichiers locaux). */
        final List<String> backdrops = new CopyOnWriteArrayList<>();
        /** Chemin absolu du logo, ou "" (titre affiché en texte). */
        String logo = "";
        /**
         * Fiche pour la carte titre du mode cinéma : résumé, accroche, genres (« Drame, Crime »), note
         * TMDB sur 10, nombre de votes, durée en minutes, et date de la fiche (ms, 0 : pas de fiche).
         */
        String overview = "", tagline = "", genres = "";
        double rating;
        int votes, runtime;
        long infoAt;

        String shownTitle() {
            return customTitle.isEmpty() ? title : customTitle;
        }
    }

    /** Playlist dynamique : les tendances TMDB de la semaine, renouvelées chaque jour. */
    static final String DYNAMIC_TRENDING = "trending";
    static final long DYNAMIC_EVERY_MS = 24 * 3600_000L;
    static final int[] DYNAMIC_SIZES = {10, 20, 30, 40};

    static final class Playlist {
        String id, name;
        final List<Film> films = new CopyOnWriteArrayList<>();
        /** "" pour une playlist composée à la main, DYNAMIC_TRENDING pour les tendances. */
        String dynamic = "";
        /** Dernière mise à jour (ms) et nombre de films d'une playlist dynamique. */
        long updated;
        int limit = 20;
        /** Pack de fonds d'où vient la playlist (voir Packs), ou "". */
        String pack = "";
        /** Version du pack installée (1 pour les packs installés avant les numéros de version). */
        int packVersion = 1;

        boolean isDynamic() {
            return !dynamic.isEmpty();
        }

        int imageCount() {
            int n = 0;
            for (Film f : films) n += f.backdrops.size();
            return n;
        }

        Film find(int tmdbId) {
            if (tmdbId == 0) return null;
            for (Film f : films) if (f.tmdbId == tmdbId) return f;
            return null;
        }
    }

    /** Suivi des téléchargements, affiché dans le panneau. */
    interface Progress { void update(String status); }

    private static List<Playlist> lists;
    private static final ExecutorService downloads = Executors.newSingleThreadExecutor();
    private static final Handler ui = new Handler(Looper.getMainLooper());
    private static volatile Progress progress;
    private static volatile String status = "";
    private static int pending;

    private Playlists() {}

    private static File file(Context ctx) {
        File base = ctx.getExternalFilesDir(null);
        return new File(base == null ? ctx.getFilesDir() : base, "playlists.json");
    }

    static synchronized List<Playlist> all(Context ctx) {
        if (lists == null) {
            lists = new CopyOnWriteArrayList<>();
            File f = file(ctx);
            if (f.exists()) read(f);
            else migrate(ctx);
            adoptOldFolder(ctx);
        }
        return lists;
    }

    static Playlist byId(Context ctx, String id) {
        for (Playlist p : all(ctx)) if (p.id.equals(id)) return p;
        return null;
    }

    static synchronized Playlist create(Context ctx, String name) {
        Playlist p = new Playlist();
        p.id = UUID.randomUUID().toString().substring(0, 8);
        p.name = name;
        all(ctx).add(p);
        save(ctx);
        return p;
    }

    static synchronized void delete(Context ctx, Playlist p) {
        all(ctx).remove(p);
        for (Film f : p.films) forget(ctx, f);
        save(ctx);
    }

    static synchronized void removeFilm(Context ctx, Playlist p, Film f) {
        p.films.remove(f);
        forget(ctx, f);
        save(ctx);
    }

    static synchronized void move(Context ctx, Playlist p, Film f, int dir) {
        int at = p.films.indexOf(f), to = Math.max(0, Math.min(p.films.size() - 1, at + dir));
        if (at < 0 || at == to) return;
        p.films.remove(at);
        p.films.add(to, f);
        save(ctx);
    }

    /** Supprime les images téléchargées d'un film, si aucune autre playlist ne s'en sert. */
    private static void forget(Context ctx, Film f) {
        List<String> files = new ArrayList<>(f.backdrops);
        if (!f.logo.isEmpty()) files.add(f.logo);
        for (String path : files) deleteIfUnused(ctx, path);
    }

    static synchronized void deleteIfUnused(Context ctx, String path) {
        // Seules les images rangées par le launcher (tmdb/) sont supprimées
        if (!path.contains("/tmdb/")) return;
        for (Playlist p : all(ctx)) for (Film f : p.films) {
            if (f.backdrops.contains(path) || f.logo.equals(path)) return;
        }
        new File(path).delete();
    }

    static synchronized void save(Context ctx) {
        try {
            JSONArray root = new JSONArray();
            for (Playlist p : all(ctx)) {
                JSONArray films = new JSONArray();
                for (Film f : p.films) {
                    JSONArray b = new JSONArray();
                    for (String s : f.backdrops) b.put(s);
                    films.put(new JSONObject().put("tmdbId", f.tmdbId).put("title", f.title).put("year", f.year)
                            .put("poster", f.poster).put("customTitle", f.customTitle).put("logo", f.logo)
                            .put("overview", f.overview).put("tagline", f.tagline).put("genres", f.genres)
                            .put("rating", f.rating).put("votes", f.votes).put("runtime", f.runtime)
                            .put("infoAt", f.infoAt).put("backdrops", b));
                }
                JSONObject po = new JSONObject().put("id", p.id).put("name", p.name).put("films", films);
                if (p.isDynamic()) po.put("dynamic", p.dynamic).put("updated", p.updated).put("limit", p.limit);
                if (!p.pack.isEmpty()) po.put("pack", p.pack).put("packVersion", p.packVersion);
                root.put(po);
            }
            File f = file(ctx), tmp = new File(f.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(root.toString(1).getBytes(StandardCharsets.UTF_8));
            }
            tmp.renameTo(f);
        } catch (Exception e) {
            Log.w(TAG, "Playlists non enregistrées", e);
        }
    }

    private static void read(File f) {
        try {
            byte[] raw = new byte[(int) f.length()];
            try (FileInputStream in = new FileInputStream(f)) {
                for (int off = 0, n; off < raw.length && (n = in.read(raw, off, raw.length - off)) > 0; ) off += n;
            }
            JSONArray root = new JSONArray(new String(raw, StandardCharsets.UTF_8));
            for (int i = 0; i < root.length(); i++) {
                JSONObject o = root.getJSONObject(i);
                Playlist p = new Playlist();
                p.id = o.getString("id");
                p.name = o.optString("name");
                p.dynamic = o.optString("dynamic", "");
                p.updated = o.optLong("updated");
                p.limit = o.optInt("limit", 20);
                p.pack = o.optString("pack", "");
                p.packVersion = o.optInt("packVersion", 1);
                JSONArray films = o.optJSONArray("films");
                for (int j = 0; films != null && j < films.length(); j++) {
                    JSONObject fo = films.getJSONObject(j);
                    Film fi = new Film();
                    fi.tmdbId = fo.optInt("tmdbId");
                    fi.title = fo.optString("title");
                    fi.year = fo.optString("year");
                    fi.poster = fo.optString("poster");
                    fi.customTitle = fo.optString("customTitle");
                    fi.logo = fo.optString("logo");
                    fi.overview = fo.optString("overview");
                    fi.tagline = fo.optString("tagline");
                    fi.genres = fo.optString("genres");
                    fi.rating = fo.optDouble("rating", 0);
                    fi.votes = fo.optInt("votes");
                    fi.runtime = fo.optInt("runtime");
                    fi.infoAt = fo.optLong("infoAt");
                    JSONArray b = fo.optJSONArray("backdrops");
                    for (int k = 0; b != null && k < b.length(); k++) fi.backdrops.add(b.getString(k));
                    p.films.add(fi);
                }
                lists.add(p);
            }
        } catch (Exception e) {
            Log.w(TAG, "Playlists illisibles", e);
        }
    }

    /**
     * Ancien dossier « fonds » (versions 0.3 à 0.29, rempli depuis le PC) : ses images
     * (« Titre (année).jpg » et logos/Titre (année).png) deviennent une playlist ordinaire.
     */
    private static void migrate(Context ctx) {
        File base = ctx.getExternalFilesDir(null);
        File[] files = base == null ? null : new File(base, "fonds")
                .listFiles((d, name) -> name.toLowerCase().matches(".*\\.(jpe?g|png|webp)$"));
        if (files == null || files.length == 0) return;
        java.util.Arrays.sort(files);
        Playlist p = new Playlist();
        p.id = UUID.randomUUID().toString().substring(0, 8);
        p.name = ctx.getString(R.string.playlist_migrated);
        for (File f : files) {
            String name = f.getName(), stem = name.substring(0, name.lastIndexOf('.'));
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(.*?)\\s*\\((\\d{4})\\)").matcher(stem);
            Film fi = new Film();
            boolean dated = m.find();
            fi.title = dated ? m.group(1) : stem;
            fi.year = dated ? m.group(2) : "";
            fi.backdrops.add(f.getPath());
            File logo = new File(f.getParentFile(), "logos/" + stem + ".png");
            if (logo.exists()) fi.logo = logo.getPath();
            p.films.add(fi);
        }
        lists.add(p);
    }

    /**
     * Range dans le stockage des playlists (tmdb/) les images encore dans l'ancien dossier
     * « fonds », puis supprime ce dossier. Sans effet une fois la migration faite.
     */
    private static void adoptOldFolder(Context ctx) {
        File base = ctx.getExternalFilesDir(null);
        if (base == null) return;
        File old = new File(base, "fonds");
        if (!old.exists()) return;
        File backdrops = Tmdb.storeDir(ctx, "backdrops"), logos = Tmdb.storeDir(ctx, "logos");
        boolean allMoved = true;
        for (Playlist p : lists) for (Film f : p.films) {
            for (int i = 0; i < f.backdrops.size(); i++) {
                String b = f.backdrops.get(i);
                if (!b.startsWith(old.getPath())) continue;
                String moved = move(b, backdrops);
                if (moved != null) f.backdrops.set(i, moved);
                else allMoved = false;
            }
            if (f.logo.startsWith(old.getPath())) {
                String moved = move(f.logo, logos);
                if (moved != null) f.logo = moved;
                else allMoved = false;
            }
        }
        save(ctx);
        // Ce qui reste dans le dossier n'appartient à aucune playlist
        if (allMoved) deleteTree(old);
    }

    /** Déplace un fichier vers {@code to} ; renvoie son nouveau chemin, ou null en cas d'échec. */
    private static String move(String path, File to) {
        File src = new File(path), dst = new File(to, "pc_" + src.getName().replaceAll("[^A-Za-z0-9._-]", "_"));
        if (dst.exists() || src.renameTo(dst)) return dst.getPath();
        return src.exists() ? null : path;
    }

    private static void deleteTree(File f) {
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteTree(c);
        f.delete();
    }

    /**
     * Films sans fiche TMDB (venus de l'ancien dossier) : retrouve leur identifiant et leur affiche
     * par le titre et l'année, pour pouvoir ensuite changer leurs fonds et leur logo. Hors du fil principal.
     */
    static void identify(Context ctx, Tmdb tmdb) {
        if (!tmdb.hasKey()) return;
        boolean changed = false;
        for (Playlist p : all(ctx)) for (Film f : p.films) {
            if (f.tmdbId != 0) continue;
            try {
                Tmdb.Movie best = null;
                for (Tmdb.Movie m : tmdb.search(f.title)) {
                    if (best == null) best = m;
                    if (!f.year.isEmpty() && f.year.equals(m.year)) {
                        best = m;
                        break;
                    }
                }
                // -1 : introuvable, on ne cherche plus à chaque lancement
                f.tmdbId = best == null ? -1 : best.id;
                if (best != null) f.poster = best.poster;
                changed = true;
            } catch (Exception e) {
                Log.w(TAG, "Film non identifié : " + f.title, e);
                return;
            }
        }
        if (changed) save(ctx);
    }

    /** Une fiche de film est rafraîchie au plus une fois par mois (la note évolue). */
    static final long INFO_EVERY_MS = 30L * 24 * 3600_000L;

    /**
     * Résumé, accroche, genres, note et durée des films de tes playlists (pas des packs, qui les
     * apportent), dans la langue des titres TMDB. Hors du fil principal.
     */
    static void fillInfo(Context ctx, Tmdb tmdb) {
        if (!tmdb.hasKey()) return;
        long now = System.currentTimeMillis();
        int done = 0;
        for (Playlist p : all(ctx)) {
            if (!p.pack.isEmpty()) continue;
            for (Film f : p.films) {
                if (f.tmdbId <= 0 || now - f.infoAt < INFO_EVERY_MS) continue;
                try {
                    JSONObject d = tmdb.details(f.tmdbId);
                    List<String> names = new ArrayList<>();
                    JSONArray g = d.optJSONArray("genres");
                    for (int i = 0; g != null && i < g.length(); i++) {
                        String n = g.getJSONObject(i).optString("name");
                        if (!n.isEmpty()) names.add(n);
                    }
                    synchronized (Playlists.class) {
                        f.overview = d.optString("overview").trim();
                        f.tagline = d.optString("tagline").trim();
                        f.genres = android.text.TextUtils.join(", ", names);
                        f.rating = d.optDouble("vote_average", 0);
                        f.votes = d.optInt("vote_count");
                        f.runtime = d.optInt("runtime");
                        f.infoAt = now;
                    }
                    if (++done % 20 == 0) save(ctx);
                } catch (Exception e) {
                    Log.w(TAG, "Fiche du film non récupérée : " + f.title, e);
                    break;
                }
            }
        }
        if (done > 0) save(ctx);
    }

    /** Fiche d'un film d'un pack (résumé, accroche, genres… dans la langue {@code lang}, sinon l'anglais). */
    static void applyPackInfo(Film f, JSONObject fo, String lang) {
        if (!fo.has("overview")) return;
        f.overview = Packs.pick(fo.opt("overview"), lang).trim();
        f.tagline = Packs.pick(fo.opt("tagline"), lang).trim();
        f.genres = Packs.pickList(fo.opt("genres"), lang);
        f.rating = fo.optDouble("rating", 0);
        f.votes = fo.optInt("votes");
        f.runtime = fo.optInt("runtime");
        f.infoAt = System.currentTimeMillis();
    }

    // ---------------------------------------------------------------- Téléchargements

    static void setProgress(Progress p) {
        progress = p;
        if (p != null) p.update(status);
    }

    static String status() {
        return status;
    }

    private static void report(String s) {
        status = s;
        ui.post(() -> {
            Progress p = progress;
            if (p != null) p.update(s);
        });
    }

    /**
     * Ajoute des films d'un coup, avec pour chacun le fond le mieux noté (sans texte si possible)
     * et un logo. Les films déjà présents sont ignorés.
     */
    static void importMovies(Context ctx, Tmdb tmdb, Playlist p, List<Tmdb.Movie> movies, String logoLang, boolean original) {
        Context app = ctx.getApplicationContext();
        synchronized (Playlists.class) {
            pending += movies.size();
        }
        downloads.execute(() -> {
            int done = 0;
            for (Tmdb.Movie m : movies) {
                done++;
                report(app.getString(R.string.dl_progress, done, movies.size(), m.title));
                if (p.find(m.id) == null) addBest(app, tmdb, p, m, logoLang, original);
            }
            finish(app, movies.size());
        });
    }

    /** Ajoute un film avec son fond le mieux noté (sans texte si possible) et son logo. Fil des téléchargements. */
    private static boolean addBest(Context app, Tmdb tmdb, Playlist p, Tmdb.Movie m, String logoLang, boolean original) {
        try {
            Tmdb.Image best = tmdb.bestBackdrop(m.id);
            if (best == null) return false;
            File img = Tmdb.saveBackdrop(app, m.id, best, original);
            Film f = newFilm(m);
            f.backdrops.add(img.getPath());
            f.logo = bestLogo(app, tmdb, m.id, logoLang);
            synchronized (Playlists.class) {
                p.films.add(f);
                save(app);
            }
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Import impossible : " + m.title, e);
            return false;
        }
    }

    // ---------------------------------------------------------------- Packs de fonds

    /** Playlist déjà créée à partir de ce pack, ou null. */
    static Playlist byPack(Context ctx, String packId) {
        for (Playlist p : all(ctx)) if (packId.equals(p.pack)) return p;
        return null;
    }

    /**
     * Installe un pack (voir Packs) : crée sa playlist, ou complète celle qui existe déjà,
     * puis télécharge en arrière-plan les fonds et logos choisis, directement chez TMDB.
     * Titres et logos dans la langue {@code lang} si le pack les propose.
     */
    static Playlist installPack(Context ctx, JSONObject pack, String lang, boolean original) throws Exception {
        Context app = ctx.getApplicationContext();
        String id = pack.getString("id");
        Playlist p;
        synchronized (Playlists.class) {
            p = byPack(app, id);
            if (p == null) {
                p = create(app, Packs.pick(pack.opt("name"), lang));
                p.pack = id;
            }
            // Version retenue tout de suite : un téléchargement interrompu se complète ensuite
            // en rouvrant le pack (il lui manque des films)
            p.packVersion = Math.max(p.packVersion, pack.optInt("version", 1));
            save(app);
        }
        JSONArray films = pack.getJSONArray("films");
        Playlist target = p;
        synchronized (Playlists.class) {
            pending += films.length();
        }
        downloads.execute(() -> {
            for (int i = 0; i < films.length(); i++) {
                JSONObject fo = films.optJSONObject(i);
                if (fo == null) continue;
                String title = Packs.pick(fo.opt("title"), lang);
                report(app.getString(R.string.dl_progress, i + 1, films.length(), title));
                int movie = fo.optInt("id");
                synchronized (Playlists.class) {
                    if (!all(app).contains(target)) break;   // pack supprimé entre-temps
                }
                Film known = target.find(movie);
                if (known != null) {
                    // Film déjà là : seule sa fiche est mise à jour (nouvelle version du pack)
                    synchronized (Playlists.class) {
                        applyPackInfo(known, fo, lang);
                        if (i == films.length() - 1 || i % 10 == 9) save(app);
                    }
                    continue;
                }
                try {
                    Film f = new Film();
                    f.tmdbId = movie;
                    f.title = title;
                    applyPackInfo(f, fo, lang);
                    f.year = fo.optString("year");
                    JSONArray backdrops = fo.optJSONArray("backdrops");
                    for (int k = 0; backdrops != null && k < backdrops.length(); k++) {
                        Tmdb.Image im = new Tmdb.Image();
                        im.path = backdrops.getString(k);
                        f.backdrops.add(Tmdb.saveBackdrop(app, movie, im, original).getPath());
                    }
                    if (f.backdrops.isEmpty()) continue;
                    String logo = Packs.pick(fo.opt("logo"), lang);
                    if (!logo.isEmpty()) {
                        Tmdb.Image im = new Tmdb.Image();
                        im.path = logo;
                        try {
                            f.logo = Tmdb.saveLogo(app, movie, im).getPath();
                        } catch (Exception e) {
                            Log.w(TAG, "Logo du pack non téléchargé : " + title, e);
                        }
                    }
                    synchronized (Playlists.class) {
                        if (!all(app).contains(target)) {
                            forget(app, f);
                            break;
                        }
                        target.films.add(f);
                        save(app);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Film du pack non téléchargé : " + title, e);
                }
            }
            finish(app, films.length());
        });
        return p;
    }

    // ---------------------------------------------------------------- Playlists dynamiques

    /** Crée la playlist des tendances (vide : elle se remplit à sa première mise à jour). */
    static synchronized Playlist createTrending(Context ctx, String name) {
        Playlist p = create(ctx, name);
        p.dynamic = DYNAMIC_TRENDING;
        save(ctx);
        return p;
    }

    /** Met à jour les playlists dynamiques trop anciennes (ou toutes si {@code force}), puis fait le ménage. */
    static void refreshDynamic(Context ctx, Tmdb tmdb, String logoLang, boolean original, boolean force) {
        if (!tmdb.hasKey()) return;
        Context app = ctx.getApplicationContext();
        for (Playlist p : all(app)) {
            if (!p.isDynamic() || (!force && System.currentTimeMillis() - p.updated < DYNAMIC_EVERY_MS)) continue;
            synchronized (Playlists.class) {
                pending++;
            }
            downloads.execute(() -> {
                refreshTrending(app, tmdb, p, logoLang, original);
                cleanUp(app);
                finish(app, 1);
            });
        }
    }

    /**
     * Tendances : les films sortis du classement s'en vont (avec leurs images), les nouveaux arrivent
     * avec leur meilleur fond, et la playlist suit l'ordre du classement. Les fonds et logos choisis
     * à la main pour un film toujours en tendance sont gardés.
     */
    private static void refreshTrending(Context app, Tmdb tmdb, Playlist p, String logoLang, boolean original) {
        List<Tmdb.Movie> ranked = new ArrayList<>();
        try {
            for (int page = 1; ranked.size() < p.limit && page <= 3; page++) {
                for (Tmdb.Movie m : tmdb.trending(page)) {
                    // Films sans date de sortie : souvent pas encore d'images utilisables
                    if (!m.year.isEmpty() && ranked.size() < p.limit) ranked.add(m);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Tendances indisponibles", e);
            return;
        }
        if (ranked.isEmpty()) return;
        List<Integer> ids = new ArrayList<>();
        for (Tmdb.Movie m : ranked) ids.add(m.id);
        for (Film f : p.films) {
            if (!ids.contains(f.tmdbId)) {
                synchronized (Playlists.class) {
                    p.films.remove(f);
                }
                forget(app, f);
            }
        }
        int done = 0;
        for (Tmdb.Movie m : ranked) {
            done++;
            if (p.find(m.id) != null) continue;
            report(app.getString(R.string.dl_progress, done, ranked.size(), m.title));
            addBest(app, tmdb, p, m, logoLang, original);
        }
        synchronized (Playlists.class) {
            List<Film> ordered = new ArrayList<>();
            for (int id : ids) {
                Film f = p.find(id);
                if (f != null) ordered.add(f);
            }
            p.films.clear();
            p.films.addAll(ordered);
            p.updated = System.currentTimeMillis();
            save(app);
        }
    }

    /**
     * Ménage du disque : supprime les images téléchargées que plus aucune playlist n'utilise,
     * ainsi que les téléchargements interrompus (.tmp). Fil des téléchargements uniquement,
     * pour ne jamais effacer un fichier en cours d'écriture.
     */
    static void cleanUp(Context app) {
        java.util.Set<String> used = new java.util.HashSet<>();
        for (Playlist p : all(app)) for (Film f : p.films) {
            used.addAll(f.backdrops);
            if (!f.logo.isEmpty()) used.add(f.logo);
        }
        long freed = 0;
        for (String sub : new String[]{"backdrops", "logos"}) {
            File[] files = Tmdb.storeDir(app, sub).listFiles();
            if (files == null) continue;
            for (File f : files) {
                if (used.contains(f.getPath())) continue;
                long size = f.length();
                if (f.delete()) freed += size;
            }
        }
        if (freed > 0) Log.i(TAG, "Ménage : " + freed / 1024 + " Ko libérés");
    }

    /** Le ménage seul, lancé à l'ouverture (après d'éventuels imports interrompus). */
    static void cleanUpLater(Context ctx) {
        Context app = ctx.getApplicationContext();
        downloads.execute(() -> cleanUp(app));
    }

    /**
     * Ajoute (ou retire) un fond précis d'un film, choisi dans la grille des fonds.
     * {@code replace} : le nouveau fond remplace ceux que le film avait déjà.
     */
    static void toggleBackdrop(Context ctx, Tmdb tmdb, Playlist p, Tmdb.Movie m, Tmdb.Image im,
                               String logoLang, boolean original, boolean replace, Runnable after) {
        Context app = ctx.getApplicationContext();
        Film existing = p.find(m.id);
        String target = new File(Tmdb.storeDir(app, "backdrops"),
                m.id + "_" + im.path.replace("/", "").replace(".png", ".jpg")).getPath();
        if (existing != null && existing.backdrops.contains(target)) {
            synchronized (Playlists.class) {
                existing.backdrops.remove(target);
                if (existing.backdrops.isEmpty()) p.films.remove(existing);
                save(app);
            }
            deleteIfUnused(app, target);
            if (existing.backdrops.isEmpty()) forget(app, existing);
            ui.post(after);
            return;
        }
        synchronized (Playlists.class) {
            pending++;
        }
        downloads.execute(() -> {
            report(app.getString(R.string.dl_progress, 1, 1, m.title));
            try {
                File img = Tmdb.saveBackdrop(app, m.id, im, original);
                List<String> replaced = new ArrayList<>();
                synchronized (Playlists.class) {
                    Film f = p.find(m.id);
                    if (f == null) {
                        f = newFilm(m);
                        p.films.add(f);
                    }
                    if (replace) {
                        for (String old : f.backdrops) if (!old.equals(img.getPath())) replaced.add(old);
                        f.backdrops.removeAll(replaced);
                    }
                    if (!f.backdrops.contains(img.getPath())) f.backdrops.add(img.getPath());
                    save(app);
                }
                for (String old : replaced) deleteIfUnused(app, old);
                Film f = p.find(m.id);
                if (f != null && f.logo.isEmpty()) {
                    f.logo = bestLogo(app, tmdb, m.id, logoLang);
                    save(app);
                }
            } catch (Exception e) {
                Log.w(TAG, "Fond non téléchargé : " + m.title, e);
            }
            finish(app, 1);
            ui.post(after);
        });
    }

    /** Change de logo : télécharge celui choisi, ou n'en met aucun (null). */
    static void setLogo(Context ctx, Film f, Tmdb.Image im, Runnable after) {
        Context app = ctx.getApplicationContext();
        downloads.execute(() -> {
            String old = f.logo;
            try {
                f.logo = im == null ? "" : Tmdb.saveLogo(app, f.tmdbId, im).getPath();
                save(app);
                if (!old.isEmpty() && !old.equals(f.logo)) deleteIfUnused(app, old);
            } catch (Exception e) {
                Log.w(TAG, "Logo non téléchargé", e);
            }
            ui.post(after);
        });
    }

    private static Film newFilm(Tmdb.Movie m) {
        Film f = new Film();
        f.tmdbId = m.id;
        f.title = m.title;
        f.year = m.year;
        f.poster = m.poster;
        return f;
    }

    private static String bestLogo(Context app, Tmdb tmdb, int id, String lang) {
        try {
            List<Tmdb.Image> logos = tmdb.logos(id, lang);
            return logos.isEmpty() ? "" : Tmdb.saveLogo(app, id, logos.get(0)).getPath();
        } catch (Exception e) {
            return "";
        }
    }

    private static void finish(Context app, int count) {
        boolean idle;
        synchronized (Playlists.class) {
            pending -= count;
            idle = pending <= 0;
        }
        if (idle) report(app.getString(R.string.dl_done));
    }
}
