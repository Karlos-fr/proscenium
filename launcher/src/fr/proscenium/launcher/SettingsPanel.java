package fr.proscenium.launcher;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


/**
 * Panneau de réglages complet : ajoute aux onglets simples les playlists de films et TMDB
 * (recherche, imports, choix des fonds triés par note, logos, playlist des tendances).
 */
final class SettingsPanel extends PanelTabs {
    SettingsPanel(Context ctx, Host host, Settings settings, GlassSettings glass) {
        super(ctx, host, settings, glass);
    }

    interface MovieLoader { List<Tmdb.Movie> load(Tmdb t) throws Exception; }

    /** Affiches (2:3) et fonds (16:9) dans les grilles, en dp. */
    static final int POSTER_W = 90, POSTER_H = 135, BACKDROP_W = 160, BACKDROP_H = 90;

    String logoLang() {
        return host.tmdb().hasKey() ? s.tmdbLanguage(Lang.tag(ctx)).substring(0, 2) : "fr";
    }

    boolean original() {
        return s.tmdbQuality.index == Settings.QUALITY_ORIGINAL;
    }


    // ---------------------------------------------------------------- Onglet Playlists

    void buildPlaylists() {
        section(R.string.sec_my_playlists);
        for (Playlists.Playlist p : Playlists.all(ctx)) {
            Row r = row(0, R.string.help_playlist_row, 0, () -> playlistSummary(p), null,
                    () -> openPage(() -> showPlaylist(p)), p.name);
            r.setTag(p.id);
            content.addView(r);
        }
        content.addView(action(R.string.act_new_playlist, R.string.help_new_playlist, null,
                () -> askText(R.string.prompt_playlist_name, "", name -> {
                    Playlists.Playlist p = Playlists.create(ctx, name);
                    openPage(() -> showPlaylist(p));
                })));
        // Une seule playlist des tendances : elle se tient à jour toute seule
        boolean hasTrending = false;
        for (Playlists.Playlist p : Playlists.all(ctx)) hasTrending |= p.isDynamic();
        if (!hasTrending) {
            content.addView(action(R.string.act_new_trending, R.string.help_new_trending, null, () -> {
                Playlists.Playlist p = Playlists.createTrending(ctx, ctx.getString(R.string.playlist_trending));
                s.setActive(p.id, true);
                Playlists.refreshDynamic(ctx, host.tmdb(), logoLang(), original(), true);
                openPage(() -> showPlaylist(p));
            }).badgeOf(R.string.badge_internet));
        }
        section(R.string.sec_packs);
        content.addView(action(R.string.act_browse_packs, R.string.help_browse_packs, () -> "›",
                () -> openPage(this::showPacks)).badgeOf(R.string.badge_internet));
        statusRow();
    }

    /** Catalogue des packs, gardé tant que l'appli tourne. */
    static List<Packs.Pack> packCatalog;
    static long packCatalogAt;

    /** Langue des titres et logos des packs : celle choisie pour TMDB. */
    String packLang() {
        return s.tmdbLanguage(Lang.tag(ctx)).substring(0, 2);
    }

    /** Packs de fonds thématiques : une tuile par pack, OK l'installe, ou le supprime s'il l'est déjà. */
    void showPacks() {
        clearContent();
        pageTitle(ctx.getString(R.string.packs_title), R.string.hint_packs);
        // Le launcher tourne des jours entiers : le catalogue est relu au plus toutes les heures
        if (packCatalog != null && System.currentTimeMillis() - packCatalogAt > 3600_000L) packCatalog = null;
        if (packCatalog == null) {
            TextView wait = note(ctx.getString(R.string.loading));
            String lang = packLang();
            async(() -> Packs.catalog(lang), (list, err) -> {
                if (list == null) {
                    wait.setText(R.string.packs_error);
                    return;
                }
                packCatalog = list;
                packCatalogAt = System.currentTimeMillis();
                rebuildPage(null);
                focusFirstRow();
            });
            return;
        }
        List<Packs.Pack> outdated = new ArrayList<>();
        for (Packs.Pack pack : packCatalog) if (hasUpdate(pack)) outdated.add(pack);
        if (!outdated.isEmpty()) {
            content.addView(action(R.string.act_update_packs, R.string.help_update_packs,
                    () -> String.valueOf(outdated.size()), () -> {
                        for (Packs.Pack pack : outdated) downloadPack(pack);
                    }).badgeOf(R.string.badge_internet));
        }
        List<View> tiles = new ArrayList<>();
        for (Packs.Pack pack : packCatalog) {
            Playlists.Playlist installed = Playlists.byPack(ctx, pack.id);
            String info = ctx.getResources().getQuantityString(R.plurals.films, pack.films, pack.films)
                    + (hasUpdate(pack) ? "  ·  " + ctx.getString(R.string.packs_update)
                    : installed != null ? "  ·  " + ctx.getString(R.string.packs_installed) : "")
                    + (pack.description.isEmpty() ? "" : "  ·  " + pack.description);
            View t = tile("w300", pack.cover, BACKDROP_W, BACKDROP_H, pack.name, info,
                    () -> Playlists.byPack(ctx, pack.id) != null, () -> togglePack(pack));
            t.setTag("pack:" + pack.id);
            tiles.add(t);
        }
        grid(tiles);
        note(ctx.getString(R.string.packs_notice));
        statusRow();
        focusFirstRow();
    }

    /** Pack installé dont une version plus récente est publiée (des films ont été ajoutés). */
    boolean hasUpdate(Packs.Pack pack) {
        Playlists.Playlist installed = Playlists.byPack(ctx, pack.id);
        return installed != null && pack.version > installed.packVersion;
    }

    /**
     * OK sur la tuile d'un pack, comme une case à cocher : un pack absent est installé, un pack
     * installé est supprimé (sa playlist et ses images ; un téléchargement en cours s'arrête).
     */
    void togglePack(Packs.Pack pack) {
        Playlists.Playlist installed = Playlists.byPack(ctx, pack.id);
        if (installed == null) {
            downloadPack(pack);
            return;
        }
        if (s.isActive(installed.id)) s.setActive(installed.id, false);
        Playlists.delete(ctx, installed);
        rebuildPage("pack:" + pack.id);
        help.setText(ctx.getString(R.string.packs_removed, pack.name));
    }

    /** Installe un pack, le complète ou le met à jour (seuls les films manquants sont téléchargés). */
    void downloadPack(Packs.Pack pack) {
        help.setText(R.string.loading);
        String lang = packLang();
        boolean original = original();
        async(() -> Playlists.installPack(ctx, Packs.load(pack.id), lang, original), (p, err) -> {
            if (p == null) {
                help.setText(R.string.packs_error);
                return;
            }
            s.setActive(p.id, true);
            if (s.bgSource.index != Settings.SOURCE_PLAYLISTS) {
                s.bgSource.index = Settings.SOURCE_PLAYLISTS;
                s.changed(s.bgSource);
            }
            rebuildPage("pack:" + pack.id);
            help.setText(R.string.import_started);
        });
    }

    String playlistSummary(Playlists.Playlist p) {
        String n = ctx.getResources().getQuantityString(R.plurals.films, p.films.size(), p.films.size());
        return n + (s.isActive(p.id) ? "  ·  " + ctx.getString(R.string.playlist_active) : "") + "  ›";
    }

    /** Ligne « Téléchargements » : avancement des imports en arrière-plan. */
    void statusRow() {
        section(R.string.sec_downloads);
        content.addView(row(R.string.set_downloads, R.string.help_downloads, R.string.badge_internet,
                Playlists::status, null, null, null));
    }

    void showPlaylist(Playlists.Playlist p) {
        clearContent();
        pageTitle(p.name, 0);
        // Interrupteur : plusieurs playlists peuvent être affichées en même temps (fonds mélangés)
        content.addView(row(R.string.act_use_playlist, R.string.help_use_playlist, 0, () -> onOff(s.isActive(p.id)),
                dir -> {
                    boolean on = !s.isActive(p.id);
                    s.setActive(p.id, on);
                    if (on && s.bgSource.index != Settings.SOURCE_PLAYLISTS) {
                        s.bgSource.index = Settings.SOURCE_PLAYLISTS;
                        s.changed(s.bgSource);
                    }
                }, null, null));

        if (p.isDynamic()) {
            dynamicRows(p);
        } else {
            addFilmRows(p);
        }

        section(0);
        ((TextView) content.getChildAt(content.getChildCount() - 1)).setText(
                ctx.getResources().getQuantityString(R.plurals.films, p.films.size(), p.films.size()).toUpperCase(Lang.locale(ctx)));
        List<View> tiles = new ArrayList<>();
        for (Playlists.Film f : p.films) {
            // Le fond affiché à l'accueil plutôt que l'affiche ; l'affiche seulement si aucun fond n'est encore arrivé
            boolean backdrop = !f.backdrops.isEmpty();
            View t = tile(backdrop ? "file" : "w185", backdrop ? f.backdrops.get(0) : f.poster,
                    backdrop ? BACKDROP_W : POSTER_W, backdrop ? BACKDROP_H : POSTER_H,
                    f.shownTitle(), filmInfo(f), null, () -> openPage(() -> showFilm(p, f)));
            t.setTag(f);
            tiles.add(t);
        }
        grid(tiles);
        if (p.films.isEmpty()) note(ctx.getString(p.isDynamic() ? R.string.trending_empty : R.string.playlist_empty));

        statusRow();
        section(R.string.sec_manage);
        content.addView(action(R.string.act_rename, R.string.help_rename, null,
                () -> askText(R.string.prompt_playlist_name, p.name, name -> {
                    p.name = name;
                    Playlists.save(ctx);
                    rebuildPage(null);
                    focusFirstRow();
                })));
        content.addView(confirmAction(R.string.act_delete_playlist, R.string.help_delete_playlist, () -> {
            Playlists.delete(ctx, p);
            if (s.isActive(p.id)) s.setActive(p.id, false);
            back();
        }));
        focusFirstRow();
    }

    /** Tendances : date de mise à jour, mise à jour immédiate, nombre de films. */
    void dynamicRows(Playlists.Playlist p) {
        section(R.string.sec_trending);
        content.addView(action(R.string.act_refresh_trending, R.string.help_refresh_trending, () -> {
            if (p.updated == 0) return ctx.getString(R.string.updated_never);
            long hours = (System.currentTimeMillis() - p.updated) / 3600_000L;
            return hours < 1 ? ctx.getString(R.string.updated_now)
                    : ctx.getResources().getQuantityString(R.plurals.updated_hours, (int) hours, (int) hours);
        }, () -> {
            Playlists.refreshDynamic(ctx, host.tmdb(), logoLang(), original(), true);
            help.setText(R.string.import_started);
        }).badgeOf(R.string.badge_internet));
        content.addView(row(R.string.set_trending_size, R.string.help_trending_size, 0,
                () -> ctx.getResources().getQuantityString(R.plurals.films, p.limit, p.limit), dir -> {
                    int at = 0;
                    for (int i = 0; i < Playlists.DYNAMIC_SIZES.length; i++) if (Playlists.DYNAMIC_SIZES[i] == p.limit) at = i;
                    p.limit = Playlists.DYNAMIC_SIZES[Math.max(0, Math.min(Playlists.DYNAMIC_SIZES.length - 1, at + dir))];
                    Playlists.save(ctx);
                }, null, null));
        note(ctx.getString(R.string.trending_note));
    }

    void addFilmRows(Playlists.Playlist p) {
        section(R.string.sec_add_films);
        content.addView(action(R.string.act_search_film, R.string.help_search_film, null,
                () -> askText(R.string.prompt_search_film, "", q -> openSearch(p, q))).badgeOf(R.string.badge_internet));
        // Recherche vocale seulement si une reconnaissance vocale est installée (Katniss est désactivée sur la box)
        if (host.voiceAvailable()) {
            content.addView(action(R.string.act_voice_search, R.string.help_voice_search, null,
                    () -> host.startVoice(q -> openSearch(p, q))).badgeOf(R.string.badge_voice));
        }
        content.addView(action(R.string.act_import_popular, R.string.help_import_list, () -> "›",
                () -> openPage(() -> showResults(p, ctx.getString(R.string.act_import_popular), Tmdb::popular))));
        content.addView(action(R.string.act_import_trending, R.string.help_import_list, () -> "›",
                () -> openPage(() -> showResults(p, ctx.getString(R.string.act_import_trending), Tmdb::trending))));
    }

    String filmInfo(Playlists.Film f) {
        String imgs = ctx.getResources().getQuantityString(R.plurals.images, f.backdrops.size(), f.backdrops.size());
        return (f.year.isEmpty() ? "" : f.year + "  ·  ") + imgs;
    }

    void openSearch(Playlists.Playlist p, String query) {
        openPage(() -> showResults(p, "« " + query + " »", t -> t.search(query)));
    }

    /** Résultats (recherche, populaires, saga…) : « Tout importer » ou choix film par film. */
    void showResults(Playlists.Playlist p, String title, MovieLoader loader) {
        clearContent();
        pageTitle(title, R.string.hint_results);
        Tmdb tmdb = host.tmdb();
        if (!tmdb.hasKey()) {
            content.addView(action(R.string.tmdb_no_key, R.string.help_tmdb_key, null, () -> {
                pages.clear();
                returnFocus.clear();
                tabViews.get(TAB_TMDB).requestFocus();
            }));
            focusFirstRow();
            return;
        }
        final List<Tmdb.Movie> found = new ArrayList<>();
        Row importAll = action(R.string.act_import_all, R.string.help_import_all,
                () -> found.isEmpty() ? ctx.getString(R.string.loading) : String.valueOf(found.size()), () -> {
                    if (found.isEmpty()) return;
                    Playlists.importMovies(ctx, tmdb, p, new ArrayList<>(found), logoLang(), original());
                    help.setText(R.string.import_started);
                });
        importAll.badgeOf(R.string.badge_internet);
        content.addView(importAll);
        importAll.requestFocus();
        async(() -> loader.load(tmdb), (movies, error) -> {
            if (error != null || movies == null) {
                note(ctx.getString(R.string.tmdb_error));
                return;
            }
            found.addAll(movies);
            for (Runnable r : refreshers) r.run();
            if (movies.isEmpty()) {
                note(ctx.getString(R.string.no_results));
                return;
            }
            List<View> tiles = new ArrayList<>();
            for (Tmdb.Movie m : movies) {
                String info = (m.year.isEmpty() ? "" : m.year + "  ·  ") + String.format(Lang.locale(ctx), "★ %.1f", m.vote);
                View t = tile("w185", m.poster, POSTER_W, POSTER_H, m.title, info,
                        () -> p.find(m.id) != null, () -> openPage(() -> showBackdrops(p, m)));
                tiles.add(t);
            }
            grid(tiles);
        });
    }

    /** Fonds d'un film, les mieux notés d'abord : OK ajoute le fond à la playlist (ou le retire). */
    void showBackdrops(Playlists.Playlist p, Tmdb.Movie m) {
        clearContent();
        pageTitle(m.title + (m.year.isEmpty() ? "" : " (" + m.year + ")"), R.string.hint_backdrops);
        Tmdb tmdb = host.tmdb();
        Row textless = row(s.textlessOnly.label, s.textlessOnly.help, 0, () -> onOff(s.textlessOnly.value), dir -> {
            s.textlessOnly.value = !s.textlessOnly.value;
            s.changed(s.textlessOnly);
            rebuildPage("textless");
        }, null, null);
        textless.setTag("textless");
        content.addView(textless);
        Row fullHd = row(s.fullHdOnly.label, s.fullHdOnly.help, 0, () -> onOff(s.fullHdOnly.value), dir -> {
            s.fullHdOnly.value = !s.fullHdOnly.value;
            s.changed(s.fullHdOnly);
            rebuildPage("fullhd");
        }, null, null);
        fullHd.setTag("fullhd");
        content.addView(fullHd);
        // Film déjà dans la playlist : le fond choisi remplace l'actuel (ou s'y ajoute)
        if (p.find(m.id) != null) content.addView(choice(s.backdropPick));
        focusFirstRow();
        TextView loading = note(ctx.getString(R.string.loading));
        async(() -> tmdb.backdrops(m.id, s.textlessOnly.value, s.fullHdOnly.value), (list, error) -> {
            content.removeView(loading);
            if (error != null || list == null) {
                note(ctx.getString(R.string.tmdb_error));
                return;
            }
            if (list.isEmpty()) {
                note(ctx.getString(R.string.no_backdrops));
                return;
            }
            List<View> tiles = new ArrayList<>();
            for (Tmdb.Image im : list) {
                String file = new File(Tmdb.storeDir(ctx, "backdrops"),
                        m.id + "_" + im.path.replace("/", "").replace(".png", ".jpg")).getPath();
                String info = String.format(Lang.locale(ctx), "★ %.1f  ·  %d × %d", im.vote, im.width, im.height);
                View[] holder = new View[1];
                holder[0] = tile("w300", im.path, BACKDROP_W, BACKDROP_H, im.lang == null
                                ? ctx.getString(R.string.backdrop_textless) : im.lang.toUpperCase(Locale.ROOT), info,
                        () -> { Playlists.Film f = p.find(m.id); return f != null && f.backdrops.contains(file); },
                        () -> {
                            Playlists.Film before = p.find(m.id);
                            boolean adding = before == null || !before.backdrops.contains(file);
                            if (adding) help.setText(R.string.backdrop_downloading);
                            boolean replace = s.backdropPick.index == Settings.PICK_REPLACE && before != null;
                            Playlists.toggleBackdrop(ctx, tmdb, p, m, im, logoLang(), original(), replace, () -> {
                                if (!open) return;
                                for (Runnable r : refreshers) r.run();
                                // Message final, à la place de « Téléchargement du fond… »
                                Playlists.Film f = p.find(m.id);
                                boolean present = f != null && f.backdrops.contains(file);
                                help.setText(!adding ? R.string.backdrop_removed
                                        : present ? R.string.backdrop_added : R.string.backdrop_failed);
                            });
                        });
                tiles.add(holder[0]);
            }
            grid(tiles);
        });
    }

    /** Fiche d'un film de la playlist : fonds, logo, titre affiché, position, retrait. */
    void showFilm(Playlists.Playlist p, Playlists.Film f) {
        clearContent();
        pageTitle(f.shownTitle() + (f.year.isEmpty() ? "" : " (" + f.year + ")"), 0);
        // -1 : film venu de l'ancien dossier, introuvable sur TMDB
        boolean fromTmdb = f.tmdbId > 0;

        section(R.string.sec_film_images);
        List<View> tiles = new ArrayList<>();
        for (String b : f.backdrops) tiles.add(tile("file", b, BACKDROP_W, BACKDROP_H, "", "", null, null));
        if (fromTmdb) {
            content.addView(action(R.string.act_change_backdrops, R.string.help_change_backdrops, () -> "›", () -> {
                Tmdb.Movie m = new Tmdb.Movie();
                m.id = f.tmdbId;
                m.title = f.title;
                m.year = f.year;
                m.poster = f.poster;
                openPage(() -> showBackdrops(p, m));
            }).badgeOf(R.string.badge_internet));
        }
        grid(tiles);

        section(R.string.sec_film_title);
        // Logos du film : chargés au premier appui sur ← →, -1 = aucun (titre en texte)
        final List<Tmdb.Image> logos = new ArrayList<>();
        final int[] logoIndex = {-2};
        Row logo = row(R.string.set_film_logo, fromTmdb ? R.string.help_film_logo : R.string.help_film_logo_local, 0,
                () -> f.logo.isEmpty() ? ctx.getString(R.string.logo_none)
                        : logoIndex[0] >= 0 ? ctx.getString(R.string.logo_n, logoIndex[0] + 1, logos.size())
                        : ctx.getString(R.string.logo_yes),
                fromTmdb ? dir -> {
                    if (logoIndex[0] == -2) {
                        logoIndex[0] = -1;
                        help.setText(R.string.loading);
                        async(() -> host.tmdb().logos(f.tmdbId, logoLang()), (list, err) -> {
                            if (list != null) logos.addAll(list);
                            help.setText(logos.isEmpty() ? R.string.no_logos : R.string.help_film_logo);
                        });
                        return;
                    }
                    if (logos.isEmpty()) return;
                    logoIndex[0] = Math.floorMod(logoIndex[0] + 1 + dir, logos.size() + 1) - 1;
                    Playlists.setLogo(ctx, f, logoIndex[0] < 0 ? null : logos.get(logoIndex[0]), () -> {
                        if (open) for (Runnable r : refreshers) r.run();
                    });
                } : null, null, null);
        content.addView(logo);
        content.addView(action(R.string.set_film_title, R.string.help_film_title, f::shownTitle,
                () -> askText(R.string.prompt_film_title, f.shownTitle(), t -> {
                    f.customTitle = t.equals(f.title) ? "" : t;
                    Playlists.save(ctx);
                    for (Runnable r : refreshers) r.run();
                })));

        // Tendances : l'ordre et la présence des films suivent le classement, pas la main
        if (!p.isDynamic()) {
            section(R.string.sec_manage);
            content.addView(row(R.string.set_film_position, R.string.help_film_position, 0,
                    () -> (p.films.indexOf(f) + 1) + " / " + p.films.size(),
                    dir -> Playlists.move(ctx, p, f, dir), null, null));
            content.addView(confirmAction(R.string.act_remove_film, R.string.help_remove_film, () -> {
                Playlists.removeFilm(ctx, p, f);
                back();
            }));
        }
        focusFirstRow();
    }

    // ---------------------------------------------------------------- Clé TMDB depuis le téléphone

    /** QR code vers la petite page de saisie servie par la TV (KeyServer). */
    void showKeyQr() {
        clearContent();
        pageTitle(ctx.getString(R.string.key_qr_title), 0);
        TextView status = text(14, Color.WHITE, false);
        KeyServer server = KeyServer.start(ctx, key -> {
            s.tmdbKey.value = key;
            s.changed(s.tmdbKey);
            status.setText(R.string.key_qr_done);
            for (Runnable x : refreshers) x.run();
        });
        if (server == null) {
            note(ctx.getString(R.string.key_qr_no_network));
            focusFirstRow();
            return;
        }
        qrImage(server.url);
        status.setText(R.string.key_qr_hint);
        status.setGravity(Gravity.CENTER_HORIZONTAL);
        status.setPadding(dp(14), dp(10), dp(14), dp(4));
        content.addView(status, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        note(ctx.getString(R.string.key_qr_note));
    }

    /** QR code (modules nets, faciles à lire pour l'appareil photo) suivi de l'adresse en clair. */
    void qrImage(String url) {
        QrCode qr = QrCode.encode(url);
        int quiet = 3, n = qr.size + quiet * 2;
        Bitmap small = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < n; y++)
            for (int x = 0; x < n; x++)
                small.setPixel(x, y, qr.get(x - quiet, y - quiet) ? Color.BLACK : Color.WHITE);
        Bitmap big = Bitmap.createScaledBitmap(small, n * 8, n * 8, false);
        small.recycle();
        ImageView iv = new ImageView(ctx);
        iv.setImageBitmap(big);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(220), dp(220));
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.setMargins(0, dp(6), 0, dp(6));
        content.addView(iv, lp);
        TextView link = text(13, 0xB3FFFFFF, false);
        link.setText(url);
        link.setGravity(Gravity.CENTER_HORIZONTAL);
        content.addView(link, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    // ---------------------------------------------------------------- Télécommande web

    /** Marche / arrêt de la télécommande web ; en marche, le QR code de sa page. */
    @Override
    void showWebRemote() {
        clearContent();
        pageTitle(ctx.getString(R.string.set_web_remote), 0);
        Row toggle = row(s.webRemote.label, s.webRemote.help, 0, () -> onOff(s.webRemote.value), dir -> {
            s.webRemote.value = !s.webRemote.value;
            s.changed(s.webRemote);
            rebuildPage("web_remote");
        }, null, null);
        toggle.setTag("web_remote");
        content.addView(toggle);
        if (s.webRemote.value) {
            ProsceniumRemote remote = ProsceniumRemote.get();
            String url = remote == null ? null : remote.url();
            if (url == null) {
                note(ctx.getString(R.string.key_qr_no_network));
            } else {
                qrImage(url);
                note(ctx.getString(R.string.web_remote_hint));
            }
        }
        focusFirstRow();
    }

    // ---------------------------------------------------------------- Onglet Films (TMDB)

    void buildTmdb(List<Settings.Item> r) {
        section(R.string.sec_tmdb_access);
        content.addView(action(R.string.set_tmdb_key, R.string.help_tmdb_key, () -> ctx.getString(
                !s.tmdbKey.value.isEmpty() ? R.string.key_entered
                        : host.tmdb().hasKey() ? R.string.key_from_file : R.string.key_missing),
                () -> askText(R.string.prompt_tmdb_key, "", k -> {
                    s.tmdbKey.value = k.replaceAll("\\s", "");
                    s.changed(s.tmdbKey);
                    for (Runnable x : refreshers) x.run();
                })));
        content.addView(action(R.string.act_key_phone, R.string.help_key_phone, () -> "›",
                () -> openPage(this::showKeyQr)));
        final String[] test = {""};
        content.addView(action(R.string.act_test_tmdb, R.string.help_test_tmdb, () -> test[0], () -> {
            test[0] = ctx.getString(R.string.loading);
            Tmdb tmdb = host.tmdb();
            async(tmdb::test, (ok, err) -> {
                test[0] = ctx.getString(Boolean.TRUE.equals(ok) ? R.string.test_ok : R.string.test_failed);
                for (Runnable x : refreshers) x.run();
            });
        }).badgeOf(R.string.badge_internet));

        section(R.string.sec_tmdb_images);
        add(r, choice(s.tmdbLang), choice(s.tmdbQuality), flag(s.textlessOnly), flag(s.fullHdOnly));

        section(R.string.sec_cache);
        content.addView(action(R.string.set_cache, R.string.help_cache,
                () -> String.format(Lang.locale(ctx), "%.1f Mo", Tmdb.cacheSize(ctx) / 1048576f), null));
        content.addView(confirmAction(R.string.act_clear_cache, R.string.help_clear_cache, () -> {
            Tmdb.clearCache(ctx);
            thumbs.evictAll();
            for (Runnable x : refreshers) x.run();
        }));
        statusRow();
        section(R.string.sec_about);
        tmdbLogo();
        note(ctx.getString(R.string.tmdb_notice));
    }
}
