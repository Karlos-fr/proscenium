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
 * Socle du panneau de réglages (inspiré de Projectivy et d'Eden / Confluence de XBMC) :
 * colonne d'onglets à gauche, réglages en sections à droite, description et pastille en bas.
 * Contient la navigation (onglets, sous-pages, retour à l'élément d'origine), les lignes de réglage,
 * les grilles d'images et la saisie de texte. Les onglets sont dans PanelTabs et SettingsPanel.
 *
 * Navigation : ↑ ↓ entre les lignes, ← → pour régler, OK pour basculer ou ouvrir,
 * Retour pour revenir aux onglets puis fermer.
 */
abstract class PanelCore {
    /** Ce que le panneau demande à l'écran d'accueil. */
    interface Host {
        GlassView newGlass(float radiusPx);
        void nextBackdrop();
        void glassChanged();
        /** Réglages fins de chaque météo, et leur sauvegarde après un changement. */
        WeatherStyles weatherStyles();
        void weatherStylesChanged();
        /** Montre une météo sur le fond pendant le réglage de sa page ; -1 : la météo habituelle. */
        void previewWeather(int kind);
        void openAccessibility();
        void openAndroidSettings();
        boolean homeServiceActive();
        /** Applis installées pour la barre : paquet → nom, dans l'ordre alphabétique. */
        List<String[]> installedApps();
        void dockAppsChanged();
        /** Recherche d'une ville ; rappel sur le fil principal avec {nom, "lat,lon"} ou null. */
        void searchCity(String query, CityCallback cb);
        void panelClosed();
        String versionName();
        /** Client TMDB avec la clé et la langue des réglages. */
        Tmdb tmdb();
        boolean voiceAvailable();
        /** Recherche vocale Google ; rappel sur le fil principal avec le texte entendu. */
        void startVoice(TextCallback cb);
        /** Écrit settings-backup.json ; false en cas d'échec. */
        boolean backupSettings();
        /** Relit settings-backup.json puis reconstruit l'écran ; false s'il n'y a pas de sauvegarde. */
        boolean restoreSettings();
    }

    /** Villes trouvées : {nom, "lat,lon", « Nom, région, pays »} ; vide si aucune, null si erreur réseau. */
    interface CityCallback { void found(java.util.List<String[]> cities); }
    interface TextCallback { void text(String s); }


    static final int TAB_BACKDROPS = 0, TAB_PLAYLISTS = 1, TAB_CINEMA = 2, TAB_DOCK = 3, TAB_GLASS = 4,
            TAB_HOME = 5, TAB_TMDB = 6, TAB_SYSTEM = 7;
    static final int[] TAB_NAMES = {R.string.tab_backdrops, R.string.tab_playlists, R.string.tab_cinema,
            R.string.tab_dock, R.string.tab_glass, R.string.tab_home, R.string.tab_tmdb, R.string.tab_system};
    static final IconView.Kind[] TAB_ICONS = {IconView.Kind.PICTURE, IconView.Kind.PLAYLIST, IconView.Kind.CINEMA,
            IconView.Kind.DOCK, IconView.Kind.GLASS, IconView.Kind.HOME, IconView.Kind.FILM, IconView.Kind.GEAR};


    final Context ctx;
    final Host host;
    final Settings s;
    final GlassSettings g;
    final float density;
    final Handler ui = new Handler(Looper.getMainLooper());
    final DecelerateInterpolator ease = new DecelerateInterpolator(1.8f);
    final Random random = new Random();

    final GlassView view;
    final LinearLayout tabColumn, content;
    final ScrollView scroll;
    final TextView help, badge;
    final View focusSink;
    final List<View> tabViews = new ArrayList<>();
    final List<Runnable> refreshers = new ArrayList<>();
    int currentTab;
    boolean open;
    /** Sous-pages ouvertes (la plus récente en tête) : chacune sait se reconstruire. */
    final ArrayDeque<Runnable> pages = new ArrayDeque<>();
    /** Change à chaque page affichée : les chargements d'une page quittée sont ignorés. */
    volatile int generation;
    final ExecutorService net = Executors.newFixedThreadPool(2);
    final LruCache<String, Bitmap> thumbs = new LruCache<>(40);
    int accent = 0xFF7FA7FF;

    PanelCore(Context ctx, Host host, Settings settings, GlassSettings glass) {
        this.ctx = ctx;
        this.host = host;
        this.s = settings;
        this.g = glass;
        density = ctx.getResources().getDisplayMetrics().density;

        view = host.newGlass(dp(28));
        view.setVisibility(View.GONE);
        // Verre fumé : le texte reste lisible quel que soit le fond (ciel, neige, image claire)
        view.setShade(0xA60E131C);
        LinearLayout column = new LinearLayout(ctx);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(26), dp(22), dp(26), dp(16));

        TextView title = text(24, Color.WHITE, false);
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        title.setText(R.string.settings_title);
        title.setPadding(dp(6), 0, 0, dp(14));
        column.addView(title);

        LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.HORIZONTAL);
        tabColumn = new LinearLayout(ctx);
        tabColumn.setOrientation(LinearLayout.VERTICAL);
        body.addView(tabColumn, new LinearLayout.LayoutParams(dp(210), ViewGroup.LayoutParams.MATCH_PARENT));
        View sep = new View(ctx);
        sep.setBackgroundColor(0x2EFFFFFF);
        LinearLayout.LayoutParams sepLp = new LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT);
        sepLp.setMargins(dp(14), 0, dp(14), 0);
        body.addView(sep, sepLp);
        scroll = new ScrollView(ctx);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFocusable(false);
        content = new LinearLayout(ctx);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, dp(6), dp(8));
        scroll.addView(content);
        body.addView(scroll, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        column.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        View line = new View(ctx);
        line.setBackgroundColor(0x2EFFFFFF);
        LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lineLp.setMargins(0, dp(10), 0, dp(10));
        column.addView(line, lineLp);
        LinearLayout foot = new LinearLayout(ctx);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        help = text(13, 0xD9FFFFFF, false);
        help.setMaxLines(2);
        foot.addView(help, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        badge = text(12, 0xFFFFD25A, true);
        badge.setPadding(dp(16), 0, dp(4), 0);
        foot.addView(badge);
        column.addView(foot, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));
        view.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // Point d'appui invisible : garde le focus pendant qu'une page se reconstruit,
        // sinon il retomberait sur le premier onglet (qui réafficherait son contenu)
        focusSink = new View(ctx);
        focusSink.setFocusable(true);
        view.addView(focusSink, new FrameLayout.LayoutParams(1, 1));

        for (int i = 0; i < TAB_NAMES.length; i++) tabColumn.addView(tabRow(i));
        // Avancement des téléchargements : les lignes « Téléchargements » se mettent à jour
        Playlists.setProgress(status -> {
            if (open) for (Runnable r : new ArrayList<>(refreshers)) r.run();
        });
    }

    // ---------------------------------------------------------------- Ouverture et navigation

    boolean isOpen() {
        return open;
    }

    void open(int tab) {
        open = true;
        pages.clear();
        returnFocus.clear();
        view.setVisibility(View.VISIBLE);
        view.setAlpha(0f);
        view.setScaleX(0.96f);
        view.setScaleY(0.96f);
        view.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(ease)
                .setUpdateListener(a -> view.invalidate()).withEndAction(null).start();
        showTab(Math.max(0, Math.min(TAB_NAMES.length - 1, tab)));
        tabViews.get(currentTab).requestFocus();
    }

    void close() {
        if (!open) return;
        open = false;
        view.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f).setDuration(220)
                .withEndAction(() -> view.setVisibility(View.GONE)).start();
        host.panelClosed();
    }

    int currentTab() {
        return currentTab;
    }

    /** Retour : sous-page → onglet → contenu → fermeture. */
    void back() {
        Sounds.play(Sounds.BACK);
        if (!pages.isEmpty()) {
            pages.pop();
            Object from = returnFocus.isEmpty() ? NO_FOCUS : returnFocus.pop();
            if (pages.isEmpty()) {
                // showTab vide aussi les piles : on garde la sélection à retrouver
                showTab(currentTab);
                focusFirstRow();
            } else {
                pages.peek().run();
            }
            // Retour sur l'élément d'où l'on était parti (le film qu'on vient d'éditer, par exemple)
            View v = from == NO_FOCUS ? null : content.findViewWithTag(from);
            if (v != null) {
                v.requestFocus();
                scrollIntoView(v);
            }
        } else if (content.findFocus() != null) {
            tabViews.get(currentTab).requestFocus();
        } else {
            close();
        }
    }

    /** Couleur d'accent de l'image : onglet actif et curseurs. */
    void setAccent(int color) {
        accent = color;
        for (int i = 0; i < tabViews.size(); i++) styleTab(i);
        if (open) for (Runnable r : refreshers) r.run();
    }

    View tabRow(int index) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(8), dp(12), dp(8));
        row.setFocusable(true);
        IconView icon = new IconView(ctx, TAB_ICONS[index]);
        row.addView(icon, new LinearLayout.LayoutParams(dp(22), dp(22)));
        TextView name = text(16, Color.WHITE, false);
        name.setText(TAB_NAMES[index]);
        name.setPadding(dp(14), 0, 0, 0);
        row.addView(name);
        row.setBackground(rowBackground());
        row.setOnFocusChangeListener((v, has) -> {
            if (has && currentTab != index) showTab(index);
            if (has) {
                help.setText(TAB_NAMES[index]);
                badge.setText(R.string.hint_nav);
                badge.setTextColor(0x99FFFFFF);
            }
        });
        row.setOnKeyListener((v, code, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
                focusFirstRow();
                return true;
            }
            return false;
        });
        tabViews.add(row);
        return row;
    }

    void styleTab(int i) {
        View row = tabViews.get(i);
        IconView icon = (IconView) ((ViewGroup) row).getChildAt(0);
        TextView name = (TextView) ((ViewGroup) row).getChildAt(1);
        boolean active = i == currentTab;
        icon.setColor(active ? GlassView.mix(accent, Color.WHITE, 0.35f) : 0xB3FFFFFF);
        name.setTextColor(active ? Color.WHITE : 0xB3FFFFFF);
        name.setTypeface(Typeface.create(active ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
    }

    void focusFirstRow() {
        for (int i = 0; i < content.getChildCount(); i++) {
            View v = content.getChildAt(i);
            if (v.isFocusable()) {
                v.requestFocus();
                scroll.smoothScrollTo(0, 0);
                return;
            }
        }
    }

    /** Affiche un onglet (défini dans PanelTabs). */
    abstract void showTab(int tab);

    // ---------------------------------------------------------------- Pages

    /** Ouvre une sous-page ; Retour reviendra à la précédente. */
    void openPage(Runnable build) {
        // Ce qui était sélectionné au départ (un film, une playlist…) le redeviendra au retour
        Object from = focusedTag();
        returnFocus.push(from == null ? NO_FOCUS : from);
        pages.push(build);
        build.run();
    }

    /** Sélection à retrouver en revenant de chaque sous-page ouverte (même ordre que {@code pages}). */
    final ArrayDeque<Object> returnFocus = new ArrayDeque<>();
    static final Object NO_FOCUS = new Object();

    /**
     * Fait défiler la page jusqu'à l'élément, une fois la page reconstruite et mise en page.
     * Remplace le retour en haut lancé par la reconstruction (sinon l'élément reste hors de l'écran).
     */
    void scrollIntoView(View v) {
        int gen = generation;
        scroll.postDelayed(() -> {
            if (gen != generation || !v.isAttachedToWindow()) return;
            int y = 0;
            for (View p = v; p != null && p != content; p = p.getParent() instanceof View ? (View) p.getParent() : null) {
                y += p.getTop();
            }
            // L'élément arrive au tiers de la hauteur visible, avec ce qui le précède au-dessus
            scroll.smoothScrollTo(0, Math.max(0, y - scroll.getHeight() / 3));
        }, 60);
    }

    /** Étiquette de l'élément sélectionné dans le contenu (tuile de film, ligne de playlist…), ou null. */
    Object focusedTag() {
        View v = content.findFocus();
        while (v != null && v != content) {
            if (v.getTag() != null) return v.getTag();
            v = v.getParent() instanceof View ? (View) v.getParent() : null;
        }
        return null;
    }

    /** Reconstruit la page affichée (après un changement), en gardant la sélection si possible. */
    void rebuildPage(Object focusTag) {
        int y = scroll.getScrollY();
        if (pages.isEmpty()) showTab(currentTab);
        else pages.peek().run();
        if (focusTag == null) return;
        View v = content.findViewWithTag(focusTag);
        if (v == null) return;
        v.requestFocus();
        // La page reste où elle était (la reconstruction l'avait ramenée en haut) ; une fois mise en
        // page, ce défilement remplace celui vers le haut, encore en cours
        int gen = generation;
        scroll.post(() -> {
            if (gen == generation) scroll.smoothScrollTo(0, y);
        });
    }

    /** Une page de réglage de la météo montre sa météo sur le fond ; elle s'arrête en la quittant. */
    boolean previewingWeather;

    void clearContent() {
        if (previewingWeather) {
            previewingWeather = false;
            host.previewWeather(-1);
        }
        if (content.findFocus() != null) focusSink.requestFocus();
        generation++;
        content.removeAllViews();
        refreshers.clear();
        scroll.scrollTo(0, 0);
    }

    /** Titre d'une sous-page, avec une ligne d'explication facultative. */
    void pageTitle(String title, int hintRes) {
        TextView t = text(19, Color.WHITE, false);
        t.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        t.setText(title);
        t.setSingleLine();
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setPadding(dp(14), dp(2), dp(14), hintRes == 0 ? dp(6) : 0);
        content.addView(t);
        if (hintRes == 0) return;
        TextView h = text(12, 0x99FFFFFF, false);
        h.setText(hintRes);
        h.setPadding(dp(14), dp(2), dp(14), dp(6));
        content.addView(h);
    }

    /** Logo TMDB, exigé par les conditions de l'API à côté de la mention (onglet Films, crédits). */
    void tmdbLogo() {
        android.widget.ImageView iv = new android.widget.ImageView(ctx);
        iv.setImageResource(R.drawable.tmdb_logo);
        iv.setAdjustViewBounds(true);
        iv.setContentDescription("TMDB");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(123), dp(16));
        lp.setMargins(dp(14), dp(10), dp(14), 0);
        content.addView(iv, lp);
    }

    /** Ligne de texte simple (message, mention), non sélectionnable. */
    TextView note(String message) {
        TextView t = text(13, 0xB3FFFFFF, false);
        t.setText(message);
        t.setPadding(dp(14), dp(8), dp(14), dp(8));
        content.addView(t);
        return t;
    }

    /** Lance un travail réseau ; le résultat n'est affiché que si la page n'a pas changé entre-temps. */
    <T> void async(Loader<T> work, Result<T> done) {
        int gen = generation;
        net.execute(() -> {
            T value = null;
            Exception error = null;
            try {
                value = work.load();
            } catch (Exception e) {
                error = e;
            }
            T v = value;
            Exception err = error;
            ui.post(() -> {
                if (gen == generation && open) done.got(v, err);
            });
        });
    }

    interface Loader<T> { T load() throws Exception; }
    interface Result<T> { void got(T value, Exception error); }


    // ---------------------------------------------------------------- Grilles d'affiches

    /**
     * Tuile d'image : affiche TMDB (w185), fond TMDB (w300) ou fichier local ("file"),
     * légende et ligne d'infos dessous, coche si {@code checked} répond oui.
     */
    View tile(String size, String path, int wDp, int hDp, String caption, String info,
                      BoolGet checked, Runnable ok) {
        LinearLayout t = new LinearLayout(ctx);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(dp(6), dp(6), dp(6), dp(6));
        t.setFocusable(true);
        t.setBackground(rowBackground());
        FrameLayout frame = new FrameLayout(ctx);
        frame.setBackgroundColor(0x1FFFFFFF);
        frame.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(6));
            }
        });
        frame.setClipToOutline(true);
        ImageView iv = new ImageView(ctx);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        frame.addView(iv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        TextView check = text(13, Color.WHITE, true);
        check.setText("✓");
        check.setGravity(Gravity.CENTER);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(accent);
        check.setBackground(dot);
        FrameLayout.LayoutParams checkLp = new FrameLayout.LayoutParams(dp(22), dp(22));
        checkLp.gravity = Gravity.TOP | Gravity.END;
        checkLp.setMargins(0, dp(4), dp(4), 0);
        frame.addView(check, checkLp);
        t.addView(frame, new LinearLayout.LayoutParams(dp(wDp), dp(hDp)));
        if (!caption.isEmpty()) {
            TextView c = text(12, 0xE6FFFFFF, false);
            c.setText(caption);
            c.setSingleLine();
            c.setEllipsize(TextUtils.TruncateAt.END);
            c.setPadding(0, dp(5), 0, 0);
            t.addView(c, new LinearLayout.LayoutParams(dp(wDp), ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        Runnable refresh = () -> {
            boolean on = checked != null && checked.get();
            check.setVisibility(on ? View.VISIBLE : View.GONE);
            ((GradientDrawable) check.getBackground()).setColor(accent);
        };
        refreshers.add(refresh);
        refresh.run();
        if (path != null && !path.isEmpty()) loadThumb(iv, size, path);
        String helpText = caption.isEmpty() ? info : caption + (info.isEmpty() ? "" : "  —  " + info);
        t.setOnFocusChangeListener((v, has) -> {
            v.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).setDuration(160).start();
            if (!has) return;
            help.setText(helpText);
            badge.setText(R.string.hint_nav);
            badge.setTextColor(0x99FFFFFF);
        });
        t.setOnKeyListener((v, code, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
                if (e.getRepeatCount() == 0 && ok != null) {
                    Sounds.play(Sounds.SELECT);
                    ok.run();
                }
                return true;
            }
            // Dans une sous-page, ← sur la première colonne ne sort pas vers les onglets
            if (code == KeyEvent.KEYCODE_DPAD_LEFT && !pages.isEmpty()) {
                ViewGroup line = (ViewGroup) v.getParent();
                return line.indexOfChild(v) == 0;
            }
            return false;
        });
        return t;
    }

    /** Range les tuiles en lignes, autant par ligne que la largeur le permet (tailles mélangées possibles). */
    void grid(List<View> tiles) {
        int available = content.getWidth() > 0 ? content.getWidth() - dp(6) : dp(560);
        LinearLayout line = null;
        int used = 0;
        for (int i = 0; i < tiles.size(); i++) {
            tiles.get(i).measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
            int w = tiles.get(i).getMeasuredWidth() + dp(4);
            if (line == null || used + w > available) {
                used = 0;
                line = new LinearLayout(ctx);
                line.setOrientation(LinearLayout.HORIZONTAL);
                line.setClipChildren(false);
                line.setPadding(dp(4), dp(2), 0, dp(2));
                content.addView(line);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(4);
            line.addView(tiles.get(i), lp);
            used += w;
        }
    }

    /** Miniature en arrière-plan ; ignorée si la page a changé entre-temps. */
    void loadThumb(ImageView iv, String size, String path) {
        String k = size + path;
        Bitmap cached = thumbs.get(k);
        if (cached != null) {
            iv.setImageBitmap(cached);
            return;
        }
        int gen = generation;
        net.execute(() -> {
            if (gen != generation) return;
            Bitmap b = null;
            try {
                if ("file".equals(size)) {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inSampleSize = 4;
                    o.inPreferredConfig = Bitmap.Config.RGB_565;
                    b = BitmapFactory.decodeFile(path, o);
                } else {
                    b = Tmdb.thumb(ctx, size, path);
                }
            } catch (Exception ignored) {
                // Pas de miniature : la tuile reste vide
            }
            Bitmap bm = b;
            if (bm != null) ui.post(() -> {
                thumbs.put(k, bm);
                if (gen == generation) iv.setImageBitmap(bm);
            });
        });
    }

    // ---------------------------------------------------------------- Saisie de texte

    /** Clavier de la box dans une petite fenêtre ; rappel avec le texte saisi (non vide). */
    void askText(int titleRes, String initial, TextCallback cb) {
        EditText input = new EditText(ctx);
        input.setSingleLine();
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setText(initial);
        input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(ctx)
                .setTitle(titleRes)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String q = input.getText().toString().trim();
                    if (!q.isEmpty()) cb.text(q);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }


    // ---------------------------------------------------------------- Lignes

    interface Value { String get(); }
    interface Step { void apply(int dir); }
    interface BoolGet { boolean get(); }
    interface BoolSet { void set(boolean v); }

    /** Ligne générique : libellé à gauche, valeur à droite, barre de progression facultative. */
    final class Row extends LinearLayout {
        final TextView label, value;
        final View bar, fill;
        int helpRes, badgeRes;
        float progress = -1f;
        /** Recalcule la position du curseur (lignes à curseur), à chaque rafraîchissement de la ligne. */
        Runnable progressSource;
        /** Réglage piloté par la ligne, ou null. */
        Settings.Item item;

        Row(int labelRes, String labelText) {
            super(ctx);
            setOrientation(VERTICAL);
            setPadding(dp(14), dp(9), dp(14), dp(9));
            setFocusable(true);
            setBackground(rowBackground());
            LinearLayout line = new LinearLayout(ctx);
            line.setOrientation(HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);
            label = text(16, Color.WHITE, false);
            if (labelRes != 0) label.setText(labelRes);
            else label.setText(labelText);
            line.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            value = text(15, 0xE6FFFFFF, true);
            value.setGravity(Gravity.END);
            line.addView(value);
            addView(line);
            FrameLayout track = new FrameLayout(ctx);
            bar = track;
            track.setBackgroundColor(0x26FFFFFF);
            fill = new View(ctx);
            track.addView(fill, new FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT));
            LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2));
            barLp.topMargin = dp(6);
            addView(track, barLp);
            track.setVisibility(GONE);
        }

        Row badgeOf(int res) {
            badgeRes = res;
            return this;
        }

        void setProgress(float p) {
            progress = p;
            bar.setVisibility(VISIBLE);
            bar.post(() -> {
                ViewGroup.LayoutParams lp = fill.getLayoutParams();
                lp.width = Math.round(bar.getWidth() * Math.max(0, Math.min(1, p)));
                fill.setLayoutParams(lp);
                fill.setBackgroundColor(GlassView.mix(accent, Color.WHITE, 0.3f));
            });
        }
    }

    /** Fabrique une ligne : {@code step} pour ← →, {@code ok} pour OK ; null = sans effet. */
    Row row(int labelRes, int helpRes, int badgeRes, Value value, Step step, Runnable ok, String labelText) {
        Row r = new Row(labelRes, labelText);
        r.helpRes = helpRes;
        r.badgeRes = badgeRes;
        Runnable refresh = () -> {
            if (value != null) r.value.setText(value.get());
            // Le curseur suit la nouvelle valeur dès l'appui sur ← →, pas seulement au prochain rafraîchissement général
            if (r.progressSource != null) r.progressSource.run();
            else if (r.progress >= 0) r.setProgress(r.progress);
        };
        refreshers.add(refresh);
        refresh.run();
        r.setOnFocusChangeListener((v, has) -> {
            if (!has) return;
            if (r.helpRes != 0) help.setText(r.helpRes);
            else help.setText("");
            if (r.badgeRes != 0) {
                badge.setText(r.badgeRes);
                badge.setTextColor(0xFFFFD25A);
            } else {
                badge.setText(R.string.hint_nav);
                badge.setTextColor(0x99FFFFFF);
            }
        });
        r.setOnKeyListener((v, code, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                int dir = code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
                if (step != null) {
                    step.apply(dir);
                    refresh.run();
                    Sounds.play(Sounds.STEP);
                } else if (dir < 0 && pages.isEmpty()) {
                    tabViews.get(currentTab).requestFocus();
                }
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
                if (e.getRepeatCount() > 0) return true;
                if (ok != null) {
                    Sounds.play(Sounds.SELECT);
                    ok.run();
                    refresh.run();
                } else if (step != null) {
                    step.apply(1);
                    refresh.run();
                    Sounds.play(Sounds.STEP);
                }
                return true;
            }
            return false;
        });
        return r;
    }

    Row flag(Settings.Flag f) {
        Row r = row(f.label, f.help, f.badge, () -> onOff(f.value), dir -> {
            f.value = !f.value;
            s.changed(f);
        }, null, null);
        r.item = f;
        return r;
    }

    Row num(Settings.Num n) {
        Row r = row(n.label, n.help, n.badge, () -> format(n.format, n.value), dir -> {
            n.set(n.value + dir * n.step);
            s.changed(n);
        }, null, null);
        r.item = n;
        r.progressSource = () -> r.setProgress((n.value - n.min) / (float) (n.max - n.min));
        r.progressSource.run();
        return r;
    }

    Row choice(Settings.Choice c) {
        Row r = row(c.label, c.help, c.badge, () -> c.format == Settings.Format.LABELS
                ? ctx.getString(c.labels[c.index]) : format(c.format, c.value()), dir -> {
            c.step(dir);
            s.changed(c);
        }, null, null);
        r.item = c;
        return r;
    }

    Row action(int labelRes, int helpRes, Value value, Runnable ok) {
        return row(labelRes, helpRes, 0, value, null, ok, null);
    }

    /** Action destructive : un premier OK demande confirmation, un second dans les 4 s l'exécute. */
    Row confirmAction(int labelRes, int helpRes, Runnable ok) {
        final boolean[] armed = {false};
        final Row[] holder = new Row[1];
        holder[0] = row(labelRes, helpRes, 0, () -> armed[0] ? ctx.getString(R.string.act_confirm) : "", null, () -> {
            if (armed[0]) {
                armed[0] = false;
                ok.run();
            } else {
                armed[0] = true;
                ui.postDelayed(() -> {
                    armed[0] = false;
                    holder[0].value.setText("");
                }, 4000);
            }
        }, null);
        return holder[0];
    }



    /** Ajoute des lignes ; leurs réglages sont réinitialisables par « Réinitialiser cet onglet ». */
    void add(List<Settings.Item> resettable, Row... rows) {
        for (Row r : rows) {
            content.addView(r);
            if (r.item != null && !resettable.contains(r.item)) resettable.add(r.item);
        }
    }

    void section(int titleRes) {
        TextView t = text(12, 0xA6FFFFFF, false);
        if (titleRes != 0) t.setText(titleRes);
        t.setLetterSpacing(0.14f);
        t.setPadding(dp(14), dp(content.getChildCount() == 0 ? 2 : 16), dp(14), dp(4));
        content.addView(t);
    }

    // ---------------------------------------------------------------- Mise en forme

    String onOff(boolean v) {
        return ctx.getString(v ? R.string.val_on : R.string.val_off);
    }

    String format(Settings.Format f, int v) {
        switch (f) {
            case SECONDS:
                if (v == 0) return ctx.getString(R.string.fmt_never);
                return v >= 60 ? ctx.getString(R.string.fmt_minutes, v / 60) : ctx.getString(R.string.fmt_seconds, v);
            case PERCENT: return ctx.getString(R.string.fmt_percent, v);
            case DEGREES: return ctx.getString(R.string.fmt_degrees, v);
            case MILLIS: return ctx.getString(R.string.fmt_millis, v / 1000f);
            case PIXELS: return ctx.getString(R.string.fmt_pixels, v);
            default: return String.valueOf(v);
        }
    }

    StateListDrawable rowBackground() {
        GradientDrawable on = new GradientDrawable();
        on.setCornerRadius(dp(12));
        on.setColor(0x33FFFFFF);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, on);
        bg.addState(new int[0], new ColorDrawable(Color.TRANSPARENT));
        return bg;
    }

    TextView text(float sp, int color, boolean medium) {
        TextView t = new TextView(ctx);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (medium) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    int dp(float v) {
        return Math.round(v * density);
    }
}
