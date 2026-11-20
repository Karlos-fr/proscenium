package fr.proscenium.launcher;

import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

import fr.tvremote.core.Item;
import fr.tvremote.core.Result;
import fr.tvremote.core.Screen;

/**
 * Playlists sur la télécommande web, comme dans l'onglet Playlists de la TV : créer, renommer,
 * supprimer une playlist, chercher des films sur TMDB, choisir leurs fonds et leur logo, les
 * déplacer ou les retirer.
 *
 * Écrans : « playlists » (l'onglet), « pl:&lt;playlist&gt; », « res:&lt;playlist&gt;:&lt;search|popular|trending&gt; »
 * (films trouvés), « bd:&lt;playlist&gt;:&lt;film TMDB&gt; » (fonds d'un film), « film:&lt;playlist&gt;:&lt;film&gt; »
 * (fiche d'un film de la playlist). Les réponses de TMDB arrivent en arrière-plan : l'écran dit
 * « Chargement… », puis la page le relit.
 */
final class RemotePlaylists {
    private static final String TAG = "Proscenium";

    /** Ce que la télécommande demande à l'appli. */
    interface Host {
        MainActivity act();

        /** Les téléphones qui montrent cet écran (null : tous) le relisent. */
        void refresh(String screen);

        Item item(Settings.Item i);

        String films(int n);

        void downloads(Screen sc);
    }

    private final Host host;
    private final MainActivity act;
    /** Réponses de TMDB par clé (null : échec), et celles en cours de chargement. */
    private final Map<String, Object> cache = new HashMap<>();
    private final Set<String> loading = new HashSet<>();
    /** Films vus dans les résultats, pour l'écran de leurs fonds. */
    private final Map<Integer, Tmdb.Movie> movies = new HashMap<>();
    /** Dernière recherche par playlist, message par écran, logo choisi par film. */
    private final Map<String, String> searches = new HashMap<>(), status = new HashMap<>();
    private final Map<String, Integer> logoPicked = new HashMap<>();

    RemotePlaylists(Host host) {
        this.host = host;
        this.act = host.act();
    }

    MainActivity activity() {
        return act;
    }

    private String str(int res) {
        return act.getString(res);
    }

    private Settings s() {
        return act.settings;
    }

    private String logoLang() {
        return act.tmdb().hasKey() ? s().tmdbLanguage(Lang.tag(act)).substring(0, 2) : "fr";
    }

    private boolean original() {
        return s().tmdbQuality.index == Settings.QUALITY_ORIGINAL;
    }

    static boolean handles(String screen) {
        return screen.startsWith("pl:") || screen.startsWith("res:") || screen.startsWith("bd:")
                || screen.startsWith("film:");
    }

    /** Valeur en cache, ou lance son chargement (la page relira {@code screen}). */
    @SuppressWarnings("unchecked")
    private <T> T load(String key, String screen, Callable<T> loader) {
        if (cache.containsKey(key)) return (T) cache.get(key);
        if (loading.add(key)) {
            act.net.execute(() -> {
                Object v = null;
                try {
                    v = loader.call();
                } catch (Exception e) {
                    Log.w(TAG, "Télécommande : " + key, e);
                }
                Object got = v;
                act.ui.post(() -> {
                    loading.remove(key);
                    cache.put(key, got);
                    host.refresh(screen);
                });
            });
        }
        return null;
    }

    private boolean ready(String key) {
        return cache.containsKey(key);
    }

    private void status(Screen sc, String screen) {
        String st = status.get(screen);
        if (st != null && !st.isEmpty()) sc.add(Item.info("status", st, ""));
    }

    // ================================================================== Écrans

    /**
     * L'onglet, découpé comme sur la TV : les playlists (une ligne ouvre la playlist), nouvelle
     * playlist et tendances, puis les packs. Les cases « affichées sur l'accueil » ont leur page.
     */
    Screen tab() {
        Screen sc = Screen.of("playlists", str(R.string.tab_playlists));
        List<Playlists.Playlist> all = Playlists.all(act);
        sc.add(Item.button("active", str(R.string.set_active_playlists)).opens("active")
                .help(str(R.string.help_web_active)).value(activeSummary()));
        Item edit = Item.list("edit", "", Item.Check.NONE).opens("pl");
        for (Playlists.Playlist p : all) {
            edit.row(Item.row(p.id, p.name).img("img/playlist:" + p.id).sub(summary(p)));
        }
        sc.add(Item.header(str(R.string.sec_my_playlists)));
        if (!all.isEmpty()) sc.add(edit.help(str(R.string.help_playlist_row)));
        sc.add(Item.text("new", str(R.string.act_new_playlist), "").placeholder(str(R.string.prompt_playlist_name))
                .help(str(R.string.help_new_playlist)));
        boolean hasTrending = false;
        for (Playlists.Playlist p : all) hasTrending |= p.isDynamic();
        if (!hasTrending) {
            sc.add(Item.button("trending", str(R.string.act_new_trending)).help(str(R.string.help_new_trending))
                    .badge(str(R.string.badge_internet), "internet"));
        }
        sc.add(Item.header(str(R.string.sec_packs)));
        sc.add(Item.button("packs", str(R.string.act_browse_packs)).opens("packs")
                .help(str(R.string.help_browse_packs)).badge(str(R.string.badge_internet), "internet"));
        host.downloads(sc);
        return sc;
    }

    /** Playlists affichées sur l'accueil : une case par playlist (leurs fonds se mélangent). */
    Screen active() {
        Settings s = s();
        Screen sc = Screen.of("active", str(R.string.set_active_playlists)).subtitle(str(R.string.help_web_active));
        Item grid = Item.grid("active", "", Item.Check.MULTI);
        for (Playlists.Playlist p : Playlists.all(act)) {
            grid.tile(Item.tile(p.id, p.name).img("img/playlist:" + p.id)
                    .checked(s.isActive(p.id)).sub(host.films(p.films.size())));
        }
        sc.add(grid);
        return sc;
    }

    private String activeSummary() {
        int n = s().activeIds().size();
        return n == 0 ? str(R.string.logo_none) : act.getResources().getQuantityString(R.plurals.playlists, n, n);
    }

    private String summary(Playlists.Playlist p) {
        return host.films(p.films.size()) + (s().isActive(p.id) ? "  ·  " + str(R.string.playlist_active) : "");
    }

    Screen screen(String id) {
        String[] a = id.split(":", 3);
        Playlists.Playlist p = a.length > 1 ? Playlists.byId(act, a[1]) : null;
        if (p == null) return null;
        switch (a[0]) {
            case "pl": return playlist(id, p);
            case "res": return a.length > 2 ? results(id, p, a[2]) : null;
            case "bd": {
                Tmdb.Movie m = a.length > 2 ? movie(p, a[2]) : null;
                return m == null ? null : backdrops(id, p, m);
            }
            case "film": {
                Playlists.Film f = a.length > 2 ? film(p, a[2]) : null;
                return f == null ? null : film(id, p, f);
            }
        }
        return null;
    }

    private Screen playlist(String id, Playlists.Playlist p) {
        Settings s = s();
        Screen sc = Screen.of(id, p.name);
        sc.add(Item.toggle("use", str(R.string.act_use_playlist), s.isActive(p.id)).help(str(R.string.help_use_playlist)));
        if (p.isDynamic()) {
            sc.add(Item.header(str(R.string.sec_trending)));
            sc.add(Item.button("refresh", str(R.string.act_refresh_trending)).value(updated(p))
                    .help(str(R.string.help_refresh_trending)).badge(str(R.string.badge_internet), "internet"));
            Item size = Item.choice("size", str(R.string.set_trending_size), p.limit).help(str(R.string.help_trending_size));
            for (int n : Playlists.DYNAMIC_SIZES) size.option(n, host.films(n));
            sc.add(size);
            sc.add(Item.info("trending_note", str(R.string.trending_note), ""));
        } else {
            sc.add(Item.header(str(R.string.sec_add_films)));
            if (!act.tmdb().hasKey()) {
                sc.add(Item.button("no_key", str(R.string.tmdb_no_key)).opens("tmdb").help(str(R.string.help_tmdb_key)));
            } else {
                sc.add(Item.text("search", str(R.string.act_search_film), searches.containsKey(p.id) ? searches.get(p.id) : "")
                        .placeholder(str(R.string.prompt_search_film)).help(str(R.string.help_web_search_film))
                        .badge(str(R.string.badge_internet), "internet"));
                sc.add(Item.button("popular", str(R.string.act_import_popular)).opens("res:" + p.id + ":popular")
                        .help(str(R.string.help_import_list)));
                sc.add(Item.button("trending_list", str(R.string.act_import_trending)).opens("res:" + p.id + ":trending")
                        .help(str(R.string.help_import_list)));
            }
        }
        sc.add(Item.header(host.films(p.films.size()).toUpperCase(Lang.locale(act))));
        if (p.films.isEmpty()) {
            sc.add(Item.info("empty", str(p.isDynamic() ? R.string.trending_empty : R.string.playlist_empty), ""));
        } else {
            Item grid = Item.grid("films", "", Item.Check.NONE).opens("film");
            for (Playlists.Film f : p.films) {
                Item.Entry t = Item.tile(filmKey(p, f), f.shownTitle()).sub(filmInfo(f));
                if (!f.backdrops.isEmpty()) t.img("img/film:" + p.id + ":" + filmKey(p, f) + ":0");
                else if (f.poster != null && f.poster.startsWith("/")) t.img("img/tmdb:w185:" + f.poster);
                grid.tile(t);
            }
            sc.add(grid);
        }
        host.downloads(sc);
        sc.add(Item.header(str(R.string.sec_manage)));
        sc.add(Item.text("rename", str(R.string.act_rename), p.name).help(str(R.string.help_rename)));
        sc.add(Item.button("delete", str(R.string.act_delete_playlist)).help(str(R.string.help_delete_playlist))
                .confirm(str(R.string.act_delete_playlist) + " ?"));
        return sc;
    }

    private String updated(Playlists.Playlist p) {
        if (p.updated == 0) return str(R.string.updated_never);
        long hours = (System.currentTimeMillis() - p.updated) / 3600_000L;
        return hours < 1 ? str(R.string.updated_now)
                : act.getResources().getQuantityString(R.plurals.updated_hours, (int) hours, (int) hours);
    }

    private String filmInfo(Playlists.Film f) {
        String imgs = act.getResources().getQuantityString(R.plurals.images, f.backdrops.size(), f.backdrops.size());
        return (f.year.isEmpty() ? "" : f.year + "  ·  ") + imgs;
    }

    /** Clé de cache des films trouvés (la recherche dépend du texte tapé). */
    private String resultsKey(Playlists.Playlist p, String kind) {
        return "movies:" + kind + (kind.equals("search") ? ":" + searches.get(p.id) : "");
    }

    private Screen results(String id, Playlists.Playlist p, String kind) {
        String title = kind.equals("search") ? "« " + searches.get(p.id) + " »"
                : str(kind.equals("popular") ? R.string.act_import_popular : R.string.act_import_trending);
        Screen sc = Screen.of(id, title).subtitle(str(R.string.hint_web_results));
        Tmdb tmdb = act.tmdb();
        if (!tmdb.hasKey()) {
            sc.add(Item.button("no_key", str(R.string.tmdb_no_key)).opens("tmdb").help(str(R.string.help_tmdb_key)));
            return sc;
        }
        if (kind.equals("search") && searches.get(p.id) == null) return sc;
        String key = resultsKey(p, kind);
        String query = searches.get(p.id);
        List<Tmdb.Movie> found = load(key, id, () -> kind.equals("search") ? tmdb.search(query)
                : kind.equals("popular") ? tmdb.popular() : tmdb.trending());
        if (!ready(key)) {
            sc.add(Item.info("loading", str(R.string.loading), ""));
            return sc;
        }
        if (found == null) {
            cache.remove(key);                             // nouvel essai à la prochaine ouverture
            sc.add(Item.info("error", str(R.string.tmdb_error), ""));
            return sc;
        }
        if (found.isEmpty()) {
            sc.add(Item.info("none", str(R.string.no_results), ""));
            return sc;
        }
        sc.add(Item.button("import_all", str(R.string.act_import_all)).value(String.valueOf(found.size()))
                .help(str(R.string.help_import_all)).badge(str(R.string.badge_internet), "internet"));
        status(sc, id);
        Item grid = Item.grid("movies", "", Item.Check.NONE).opens("bd");
        for (Tmdb.Movie m : found) {
            movies.put(m.id, m);
            String info = (m.year.isEmpty() ? "" : m.year + "  ·  ") + String.format(Lang.locale(act), "★ %.1f", m.vote);
            Item.Entry t = Item.tile(String.valueOf(m.id), m.title).sub(info).checked(p.find(m.id) != null);
            if (m.poster != null && m.poster.startsWith("/")) t.img("img/tmdb:w185:" + m.poster);
            grid.tile(t);
        }
        sc.add(grid);
        host.downloads(sc);
        return sc;
    }

    /** Film des résultats, ou de la playlist (page rouverte après un redémarrage de l'appli). */
    private Tmdb.Movie movie(Playlists.Playlist p, String key) {
        int id;
        try {
            id = Integer.parseInt(key);
        } catch (NumberFormatException e) {
            return null;
        }
        Tmdb.Movie m = movies.get(id);
        if (m != null) return m;
        Playlists.Film f = p.find(id);
        if (f == null) return null;
        m = new Tmdb.Movie();
        m.id = f.tmdbId;
        m.title = f.title;
        m.year = f.year;
        m.poster = f.poster;
        movies.put(id, m);
        return m;
    }

    private String backdropsKey(Tmdb.Movie m) {
        return "backdrops:" + m.id + ":" + s().textlessOnly.value + ":" + s().fullHdOnly.value;
    }

    private Screen backdrops(String id, Playlists.Playlist p, Tmdb.Movie m) {
        Settings s = s();
        Screen sc = Screen.of(id, m.title + (m.year.isEmpty() ? "" : " (" + m.year + ")"))
                .subtitle(str(R.string.hint_web_backdrops));
        sc.add(host.item(s.textlessOnly));
        sc.add(host.item(s.fullHdOnly));
        if (p.find(m.id) != null) sc.add(host.item(s.backdropPick));
        status(sc, id);
        Tmdb tmdb = act.tmdb();
        String key = backdropsKey(m);
        boolean textless = s.textlessOnly.value, fullHd = s.fullHdOnly.value;
        List<Tmdb.Image> list = load(key, id, () -> tmdb.backdrops(m.id, textless, fullHd));
        if (!ready(key)) {
            sc.add(Item.info("loading", str(R.string.loading), ""));
            return sc;
        }
        if (list == null) {
            cache.remove(key);
            sc.add(Item.info("error", str(R.string.tmdb_error), ""));
            return sc;
        }
        if (list.isEmpty()) {
            sc.add(Item.info("none", str(R.string.no_backdrops), ""));
            return sc;
        }
        Playlists.Film f = p.find(m.id);
        Item grid = Item.grid("bds", "", Item.Check.MULTI);
        for (int i = 0; i < list.size(); i++) {
            Tmdb.Image im = list.get(i);
            String info = String.format(Lang.locale(act), "★ %.1f  ·  %d × %d", im.vote, im.width, im.height);
            grid.tile(Item.tile(String.valueOf(i), im.lang == null ? str(R.string.backdrop_textless)
                    : im.lang.toUpperCase(Locale.ROOT)).img("img/tmdb:w300:" + im.path).sub(info)
                    .checked(f != null && f.backdrops.contains(backdropFile(m, im))));
        }
        sc.add(grid);
        host.downloads(sc);
        return sc;
    }

    private String backdropFile(Tmdb.Movie m, Tmdb.Image im) {
        return new File(Tmdb.storeDir(act, "backdrops"),
                m.id + "_" + im.path.replace("/", "").replace(".png", ".jpg")).getPath();
    }

    private Screen film(String id, Playlists.Playlist p, Playlists.Film f) {
        Screen sc = Screen.of(id, f.shownTitle() + (f.year.isEmpty() ? "" : " (" + f.year + ")"));
        boolean fromTmdb = f.tmdbId > 0;
        String fk = filmKey(p, f);
        sc.add(Item.header(str(R.string.sec_film_images)));
        if (fromTmdb) {
            sc.add(Item.button("change", str(R.string.act_change_backdrops)).help(str(R.string.help_change_backdrops))
                    .badge(str(R.string.badge_internet), "internet"));
        }
        Item imgs = Item.grid("imgs", "", Item.Check.NONE);
        for (int i = 0; i < f.backdrops.size(); i++) {
            imgs.tile(Item.tile(String.valueOf(i), "").img("img/film:" + p.id + ":" + fk + ":" + i));
        }
        sc.add(imgs);

        sc.add(Item.header(str(R.string.sec_film_title)));
        status(sc, id);
        if (fromTmdb) {
            String key = "logos:" + f.tmdbId + ":" + logoLang();
            String lang = logoLang();
            Tmdb tmdb = act.tmdb();
            List<Tmdb.Image> logos = load(key, id, () -> tmdb.logos(f.tmdbId, lang));
            Integer picked = logoPicked.get(p.id + ":" + fk);
            int value = f.logo.isEmpty() ? -1 : picked != null && picked >= 0 ? picked : -2;
            Item logo = Item.choice("logo", str(R.string.set_film_logo), value).help(str(R.string.help_web_logo));
            if (value == -2) logo.option(-2, str(R.string.logo_yes));
            logo.option(-1, str(R.string.logo_none));
            if (logos != null) {
                for (int i = 0; i < logos.size(); i++) logo.option(i, act.getString(R.string.logo_n, i + 1, logos.size()));
            } else if (!ready(key)) {
                logo.enabled(false);
                logo.help(str(R.string.loading));
            }
            sc.add(logo);
        } else {
            sc.add(Item.info("logo", str(R.string.set_film_logo), str(f.logo.isEmpty() ? R.string.logo_none : R.string.logo_yes))
                    .help(str(R.string.help_film_logo_local)));
        }
        sc.add(Item.text("title", str(R.string.set_film_title), f.shownTitle()).help(str(R.string.help_film_title)));
        if (!p.isDynamic()) {
            sc.add(Item.header(str(R.string.sec_manage)));
            Item pos = Item.choice("position", str(R.string.set_film_position), p.films.indexOf(f))
                    .help(str(R.string.help_web_position));
            for (int i = 0; i < p.films.size(); i++) pos.option(i, (i + 1) + " / " + p.films.size());
            sc.add(pos);
            sc.add(Item.button("remove", str(R.string.act_remove_film)).help(str(R.string.help_remove_film))
                    .confirm(str(R.string.act_remove_film) + " ?"));
        }
        return sc;
    }

    /** Identifiant d'un film dans les écrans : son numéro TMDB, ou sa place pour un film local. */
    private static String filmKey(Playlists.Playlist p, Playlists.Film f) {
        return f.tmdbId > 0 ? String.valueOf(f.tmdbId) : "i" + p.films.indexOf(f);
    }

    private static Playlists.Film film(Playlists.Playlist p, String key) {
        try {
            if (key.startsWith("i")) {
                int i = Integer.parseInt(key.substring(1));
                return i >= 0 && i < p.films.size() ? p.films.get(i) : null;
            }
            return p.find(Integer.parseInt(key));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ================================================================== Changements

    /** Réglage changé sur un de ces écrans ; null : pas pour nous (réglage ordinaire). */
    Result set(String screen, String id, Object value) {
        Settings s = s();
        if (screen.equals("playlists")) {
            if (!id.equals("new")) return null;
            String name = String.valueOf(value).trim();
            if (name.isEmpty()) return Result.ok();
            Playlists.Playlist p = Playlists.create(act, name);
            return Result.ok().open("pl:" + p.id);
        }
        String[] a = screen.split(":", 3);
        Playlists.Playlist p = a.length > 1 ? Playlists.byId(act, a[1]) : null;
        if (p == null) return null;
        switch (a[0]) {
            case "pl":
                switch (id) {
                    case "use": {
                        boolean on = Boolean.TRUE.equals(value);
                        s.setActive(p.id, on);
                        if (on && s.bgSource.index != Settings.SOURCE_PLAYLISTS) {
                            s.bgSource.index = Settings.SOURCE_PLAYLISTS;
                            s.changed(s.bgSource);
                        }
                        return Result.ok();
                    }
                    case "size":
                        if (value instanceof Number) {
                            p.limit = ((Number) value).intValue();
                            Playlists.save(act);
                        }
                        return Result.ok();
                    case "search": {
                        String q = String.valueOf(value).trim();
                        if (q.isEmpty()) return Result.ok();
                        searches.put(p.id, q);
                        return Result.ok().open("res:" + p.id + ":search");
                    }
                    case "rename": {
                        String name = String.valueOf(value).trim();
                        if (!name.isEmpty()) {
                            p.name = name;
                            Playlists.save(act);
                        }
                        return Result.ok();
                    }
                }
                return null;
            case "bd":
                if (id.startsWith("bds/")) {
                    Tmdb.Movie m = a.length > 2 ? movie(p, a[2]) : null;
                    if (m == null) return Result.error("?", "unknown");
                    return toggleBackdrop(screen, p, m, id.substring(4));
                }
                return null;
            case "film": {
                Playlists.Film f = a.length > 2 ? film(p, a[2]) : null;
                if (f == null) return Result.error("?", "unknown");
                switch (id) {
                    case "logo": return setLogo(screen, p, f, value);
                    case "title": {
                        String t = String.valueOf(value).trim();
                        if (t.isEmpty()) return Result.ok();
                        f.customTitle = t.equals(f.title) ? "" : t;
                        Playlists.save(act);
                        return Result.ok();
                    }
                    case "position": {
                        if (!(value instanceof Number)) return Result.ok();
                        int to = Math.max(0, Math.min(p.films.size() - 1, ((Number) value).intValue()));
                        int from = p.films.indexOf(f);
                        for (int i = from; i != to; i += to > from ? 1 : -1) Playlists.move(act, p, f, to > from ? 1 : -1);
                        // Un film local change de clé en changeant de place : la page suit
                        return f.tmdbId > 0 ? Result.ok() : Result.ok().open("film:" + p.id + ":" + filmKey(p, f));
                    }
                }
                return null;
            }
        }
        return null;
    }

    private Result toggleBackdrop(String screen, Playlists.Playlist p, Tmdb.Movie m, String index) {
        @SuppressWarnings("unchecked")
        List<Tmdb.Image> list = (List<Tmdb.Image>) cache.get(backdropsKey(m));
        int i;
        try {
            i = Integer.parseInt(index);
        } catch (NumberFormatException e) {
            return Result.error("?", "unknown");
        }
        if (list == null || i < 0 || i >= list.size()) return Result.error("?", "unknown");
        Tmdb.Image im = list.get(i);
        String file = backdropFile(m, im);
        Playlists.Film before = p.find(m.id);
        boolean adding = before == null || !before.backdrops.contains(file);
        if (adding) status.put(screen, str(R.string.backdrop_downloading));
        boolean replace = s().backdropPick.index == Settings.PICK_REPLACE && before != null;
        Playlists.toggleBackdrop(act, act.tmdb(), p, m, im, logoLang(), original(), replace, () -> {
            Playlists.Film f = p.find(m.id);
            boolean present = f != null && f.backdrops.contains(file);
            status.put(screen, str(!adding ? R.string.backdrop_removed
                    : present ? R.string.backdrop_added : R.string.backdrop_failed));
            host.refresh(null);
        });
        return Result.ok();
    }

    private Result setLogo(String screen, Playlists.Playlist p, Playlists.Film f, Object value) {
        if (!(value instanceof Number)) return Result.ok();
        int v = ((Number) value).intValue();
        if (v == -2) return Result.ok();
        @SuppressWarnings("unchecked")
        List<Tmdb.Image> logos = (List<Tmdb.Image>) cache.get("logos:" + f.tmdbId + ":" + logoLang());
        if (v >= 0 && (logos == null || v >= logos.size())) return Result.error("?", "unknown");
        logoPicked.put(p.id + ":" + filmKey(p, f), v);
        Playlists.setLogo(act, f, v < 0 ? null : logos.get(v), () -> host.refresh(screen));
        return Result.ok();
    }

    /** Bouton ou case sans valeur ; null : pas pour nous. */
    Result action(String screen, String id) {
        Settings s = s();
        if (screen.equals("playlists")) {
            if (id.startsWith("edit/")) return Result.ok().open("pl:" + id.substring(5));
            if (id.equals("trending")) {
                Playlists.Playlist p = Playlists.createTrending(act, str(R.string.playlist_trending));
                s.setActive(p.id, true);
                Playlists.refreshDynamic(act, act.tmdb(), logoLang(), original(), true);
                return Result.ok().open("pl:" + p.id);
            }
            return null;
        }
        String[] a = screen.split(":", 3);
        Playlists.Playlist p = a.length > 1 ? Playlists.byId(act, a[1]) : null;
        if (p == null) return null;
        switch (a[0]) {
            case "pl":
                if (id.startsWith("films/")) return Result.ok().open("film:" + p.id + ":" + id.substring(6));
                if (id.equals("refresh")) {
                    Playlists.refreshDynamic(act, act.tmdb(), logoLang(), original(), true);
                    return Result.ok();
                }
                if (id.equals("delete")) {
                    if (s.isActive(p.id)) s.setActive(p.id, false);
                    Playlists.delete(act, p);
                    return Result.ok().open("playlists");
                }
                return null;
            case "res":
                if (id.startsWith("movies/")) return Result.ok().open("bd:" + p.id + ":" + id.substring(7));
                if (id.equals("import_all") && a.length > 2) {
                    @SuppressWarnings("unchecked")
                    List<Tmdb.Movie> found = (List<Tmdb.Movie>) cache.get(resultsKey(p, a[2]));
                    if (found == null || found.isEmpty()) return Result.ok();
                    Playlists.importMovies(act, act.tmdb(), p, new ArrayList<>(found), logoLang(), original());
                    status.put(screen, str(R.string.import_started));
                    return Result.ok();
                }
                return null;
            case "film": {
                Playlists.Film f = a.length > 2 ? film(p, a[2]) : null;
                if (f == null) return Result.error("?", "unknown");
                if (id.equals("change") && f.tmdbId > 0) return Result.ok().open("bd:" + p.id + ":" + f.tmdbId);
                if (id.equals("remove")) {
                    Playlists.removeFilm(act, p, f);
                    return Result.ok().open("pl:" + p.id);
                }
                return null;
            }
        }
        return null;
    }

    // ================================================================== Images

    /** « film:&lt;playlist&gt;:&lt;film&gt;:&lt;n&gt; » : fichier du n-ième fond d'un film de la playlist, ou null. */
    String imagePath(String key) {
        String[] a = key.split(":");
        if (a.length != 4) return null;
        Playlists.Playlist p = Playlists.byId(act, a[1]);
        Playlists.Film f = p == null ? null : film(p, a[2]);
        if (f == null) return null;
        int n;
        try {
            n = Integer.parseInt(a[3]);
        } catch (NumberFormatException e) {
            return null;
        }
        if (n < 0 || n >= f.backdrops.size()) return null;
        String path = f.backdrops.get(n);
        return new File(path).exists() ? path : null;
    }
}
