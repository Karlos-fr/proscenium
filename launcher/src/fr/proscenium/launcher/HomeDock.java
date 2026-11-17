package fr.proscenium.launcher;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.Collator;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


/**
 * Barre d'applis en carrousel façon Aeon (sélection fixe au centre, boucle), et son édition
 * à la télécommande par appui long : déplacer, retirer, ajouter une appli.
 */
abstract class HomeDock extends HomeHeader {
    // ---------------------------------------------------------------- Carrousel du dock (façon Aeon)



    /** Réduction des tuiles et des écarts pour en montrer plus : 1 pour 5 applis visibles, 2/3 pour 7, 1/2 pour 9. */
    float fit() {
        return 4f / (settings.visibleApps.value() - 1);
    }

    float tileW() {
        return dp(TILE_W_DP * settings.tileSize.f() * fit());
    }

    float tileH() {
        return dp(TILE_H_DP * settings.tileSize.f() * fit());
    }

    /** Hauteur de la bande de verre et de la piste des tuiles, en hauteurs de tuile. */
    static final float DOCK_SLIM_H = 1.05f, DOCK_WIDE_H = 1.45f, TRACK_H = 1.8f;

    /** Bande fine (l'appli sélectionnée en dépasse) ou large (elle y tient entière). */
    float dockH() {
        return settings.slimBand.value ? DOCK_SLIM_H : DOCK_WIDE_H;
    }

    /** Opacité du halo : sa puissance jusqu'à 100 %. */
    float glowAlpha() {
        return Math.min(1f, settings.glowPower.f());
    }
    /** Couloir posé sur la bande : la lueur, puis la piste des tuiles. */
    FrameLayout lane;

    float centerZoom() {
        return settings.centerZoom.f();
    }

    void buildDock() {
        dock = newGlass(0);
        glow = new FocusGlow(this);
        glow.setAlpha(0f);
        glow.setAnimated(settings.glowAnimOn());
        glow.setLiveness(settings.glowAnimAmount.f(), settings.glowAnimSpeed.f());

        track = new FrameLayout(this);
        track.setId(View.generateViewId());
        track.setClipChildren(false);
        track.setFocusable(true);
        track.setOnFocusChangeListener((v, has) -> onDockFocus(has));
        track.setOnKeyListener((v, code, e) -> onCarouselKey(code, e));

        FrameLayout.LayoutParams dockLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                // Bande fine, à peine plus haute que les tuiles (elle s'affine quand elles rapetissent,
                // 7 ou 9 applis visibles) : l'appli sélectionnée, agrandie et soulevée, en sort
                Math.round(tileH() * dockH()));
        dockLp.gravity = Gravity.BOTTOM;
        mid.addView(dock, dockLp);
        // La lueur et les tuiles ne sont pas dans le verre, qui découpe ce qui dépasse, mais juste
        // devant lui, sur un couloir plus haut centré sur la bande (placé par applyBand)
        lane = new FrameLayout(this);
        lane.setClipChildren(false);
        lane.addView(glow, match());
        lane.addView(track, match());
        FrameLayout.LayoutParams laneLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.round(tileH() * TRACK_H));
        laneLp.gravity = Gravity.BOTTOM;
        // Au-dessus du verre, qui a une élévation (ombre) de 10 dp
        lane.setElevation(dock.getElevation() + dp(1));
        mid.addView(lane, laneLp);
        lane.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) placeGlow();
        });
        dock.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) {
                placeGlow();
                updateGlassLenses();
            }
        });
    }

    /** Bande pleine largeur et droite (Aeon), ou flottante et arrondie ; épaisseur du contour. */
    void applyBand() {
        boolean floating = settings.bandMode.index == Settings.BAND_FLOATING;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) dock.getLayoutParams();
        // Pleine largeur : la bande dépasse de chaque côté de l'écran, plus que le décalage maximal de la
        // parallaxe (14 points à 200 %) ; ses extrémités et ses traits verticaux ne se voient jamais
        int side = floating ? dp(48) : -dp(20);
        lp.setMargins(side, 0, side, dp(26));
        lp.height = Math.round(tileH() * dockH());
        dock.setLayoutParams(lp);
        // Couloir de la lueur et des tuiles : même largeur, même centre vertical que la bande
        FrameLayout.LayoutParams tlp = (FrameLayout.LayoutParams) lane.getLayoutParams();
        tlp.setMargins(side, 0, side, dp(26) - Math.round(tileH() * (TRACK_H - dockH()) / 2f));
        lane.setLayoutParams(tlp);
        dock.setCornerRadius(floating ? dp(30) : 0);
        dock.setRimWidth(settings.rimWidth.value);
        glassCtl.setLenses(new ArrayList<>(), floating ? dp(30) : 0);
        dock.post(this::updateGlassLenses);
    }

    /** Une seule lentille, fixe au centre : c'est là que passe la tuile sélectionnée. */
    void updateGlassLenses() {
        if (dock.getWidth() == 0) return;
        float w = tileW(), h = tileH();
        float cx = dock.getWidth() / 2f, cy = dock.getHeight() / 2f;
        List<RectF> rects = new ArrayList<>();
        rects.add(new RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2));
        glassCtl.setLenses(rects, settings.bandMode.index == Settings.BAND_FLOATING ? dp(30) : 0);
    }

    void populateApps() {
        PackageManager pm = getPackageManager();
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        Map<String, ResolveInfo> apps = new HashMap<>();
        for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
            if (!ri.activityInfo.packageName.equals(getPackageName())) apps.put(ri.activityInfo.packageName, ri);
        }

        // Les tuiles sont recréées : on ne garde que le liseré du bandeau
        rings.clear();
        if (headerRing != null) rings.add(headerRing);
        track.removeAllViews();
        tiles.clear();
        tileColors.clear();
        for (String pkg : settings.dockApps.value.split(",")) {
            View t;
            if (InputShortcuts.isShortcut(pkg.trim())) {
                // Raccourci vers une entrée HDMI (console…)
                InputShortcuts.Shortcut sc = InputShortcuts.find(settings, pkg.trim());
                if (sc == null) continue;
                t = inputTile(pkg.trim(), sc);
            } else {
                ResolveInfo ri = apps.get(pkg.trim());
                if (ri == null) continue;
                t = appTile(ri, pm);
            }
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.round(tileW()), Math.round(tileH()));
            lp.gravity = Gravity.CENTER;
            track.addView(t, lp);
            tiles.add(t);
        }

        // On revient sur l'appli sélectionnée la dernière fois
        String last = prefs.getString("dock_selected", "");
        selected = Math.min(1, Math.max(0, tiles.size() - 1));
        for (int i = 0; i < tiles.size(); i++) if (last.equals(tiles.get(i).getTag())) selected = i;
        if (scrollAnim != null) scrollAnim.cancel();
        position = target = selected;
        layoutCarousel();
        applyAccent();

        if (!panel.isOpen() && (!focusedOnce || getCurrentFocus() == null)) ui.post(track::requestFocus);
        focusedOnce = true;
    }

    View appTile(ResolveInfo ri, PackageManager pm) {
        String pkg = ri.activityInfo.packageName;
        String label = String.valueOf(ri.loadLabel(pm));
        Drawable banner = ri.activityInfo.loadBanner(pm);
        if (banner == null) banner = ri.activityInfo.applicationInfo.loadBanner(pm);

        FrameLayout tile = new FrameLayout(this);
        tile.setTag(pkg);
        tile.setContentDescription(label);
        Drawable art;
        if (banner != null) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageDrawable(banner);
            tile.addView(iv, match());
            art = banner;
        } else {
            // Pas de bannière TV : icône + nom sur fond translucide
            art = ri.loadIcon(pm);
            tile.setBackgroundColor(0x33FFFFFF);
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER);
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(art);
            box.addView(iv, new LinearLayout.LayoutParams(Math.round(tileH() * 0.45f), Math.round(tileH() * 0.45f)));
            TextView name = text(11, Color.WHITE, true);
            name.setText(label);
            name.setSingleLine();
            box.addView(name);
            tile.addView(box, match());
        }
        return finishTile(tile, art);
    }

    /** Tuile d'un raccourci HDMI : l'image de la console et son nom, ou le nom seul. */
    View inputTile(String entry, InputShortcuts.Shortcut sc) {
        FrameLayout tile = new FrameLayout(this);
        tile.setTag(entry);
        tile.setContentDescription(sc.name);
        int res = InputShortcuts.iconRes(sc.icon);
        Drawable art;
        if (res != 0) {
            art = getDrawable(res);
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageDrawable(art);
            tile.addView(iv, match());
            // Le nom, en bas, sur un léger dégradé sombre
            View fade = new View(this);
            fade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                    new int[]{0xA0000000, 0x00000000}));
            FrameLayout.LayoutParams fadeLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    Math.round(tileH() * 0.45f));
            fadeLp.gravity = Gravity.BOTTOM;
            tile.addView(fade, fadeLp);
            TextView name = text(10 * settings.tileSize.f() * fit() * 1.25f, Color.WHITE, true);
            name.setText(sc.name);
            name.setSingleLine();
            FrameLayout.LayoutParams nameLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            nameLp.gravity = Gravity.BOTTOM | Gravity.START;
            nameLp.setMargins(Math.round(tileH() * 0.1f), 0, 0, Math.round(tileH() * 0.06f));
            tile.addView(name, nameLp);
        } else {
            // Sans icône : le nom en grand sur un dégradé sombre
            GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{0xFF2A3346, 0xFF121722});
            tile.setBackground(bg);
            art = bg;
            TextView name = text(16 * settings.tileSize.f() * fit(), Color.WHITE, true);
            name.setText(sc.name);
            name.setGravity(Gravity.CENTER);
            name.setSingleLine();
            tile.addView(name, match());
        }
        return finishTile(tile, art);
    }

    /** Couleur dominante, reflet, perspective et coins arrondis, communs à toutes les tuiles. */
    View finishTile(FrameLayout tile, Drawable art) {
        tileColors.put(tile, Palette.dominant(art, 0));
        // Reflet lumineux qui balaie la tuile quand elle arrive au centre (découpé par les coins arrondis)
        View shine = new View(this);
        shine.setTag(SHINE_TAG);
        shine.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0x00FFFFFF, 0x33FFFFFF, 0xB3FFFFFF, 0x33FFFFFF, 0x00FFFFFF}));
        shine.setRotation(20f);
        shine.setAlpha(0f);
        FrameLayout.LayoutParams shineLp = new FrameLayout.LayoutParams(Math.round(tileW() * 0.5f), Math.round(tileH() * 2.4f));
        shineLp.gravity = Gravity.CENTER_VERTICAL | Gravity.START;
        tile.addView(shine, shineLp);
        // Perspective douce pour l'inclinaison 3D
        tile.setCameraDistance(2200 * density);
        makeFocusable(tile, 14, false);
        tile.setFocusable(false);
        return tile;
    }

    /**
     * Gauche / droite fait tourner le carrousel, OK lance l'appli du centre,
     * appui long : menu de l'appli (déplacer, retirer, ajouter). En mode déplacement,
     * gauche / droite déplace l'appli et OK termine.
     */
    boolean onCarouselKey(int code, KeyEvent e) {
        if (tiles.isEmpty()) {
            // Barre vide : l'appui long permet quand même d'ajouter une appli
            if ((code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER)
                    && e.getAction() == KeyEvent.ACTION_UP) pickAppToAdd();
            return code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
        }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            int dir = code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (moveMode) moveSelected(dir);
                else scrollBy(dir);
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
            if (moveMode) {
                if (e.getAction() == KeyEvent.ACTION_UP) endMove();
                return true;
            }
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) longPressed = false;
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() >= 8 && !longPressed) {
                longPressed = true;
                showAppMenu();
            }
            if (e.getAction() == KeyEvent.ACTION_UP && !longPressed) launchSelected();
            return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- Édition de la barre à la télécommande



    void showAppMenu() {
        if (appMenu != null) return;
        menuArmed = false;
        ui.removeCallbacks(goIdle);
        String label = String.valueOf(tiles.get(selected).getContentDescription());
        appMenu = newGlass(dp(18));
        appMenu.setShade(0xA60E131C);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(10), dp(10), dp(10), dp(10));
        TextView title = text(13, 0xB3FFFFFF, false);
        title.setText(label);
        title.setPadding(dp(14), dp(2), dp(14), dp(6));
        col.addView(title);
        List<View> rows = new ArrayList<>();
        rows.add(menuRow(R.string.app_menu_move, this::startMove));
        String entry = (String) tiles.get(selected).getTag();
        if (InputShortcuts.isShortcut(entry)) {
            rows.add(menuRow(R.string.app_menu_edit, () -> editShortcut(entry)));
        }
        rows.add(menuRow(R.string.app_menu_remove, this::removeSelected));
        rows.add(menuRow(R.string.app_menu_add, this::pickAppToAdd));
        rows.add(menuRow(R.string.app_menu_settings, () -> openPanel(3)));
        for (View r : rows) col.addView(r);
        appMenu.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(300), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.bottomMargin = dock.getHeight() + dp(26) + dp(14);
        ((ViewGroup) mid.getParent()).addView(appMenu, lp);
        track.setFocusable(false);
        header.setFocusable(false);
        GlassView menu = appMenu;
        menu.setAlpha(0f);
        menu.setTranslationY(dp(12));
        menu.animate().alpha(1f).translationY(0).setDuration(220).setInterpolator(ease)
                .setUpdateListener(a -> menu.invalidate()).start();
        rows.get(0).requestFocus();
    }

    TextView menuRow(int labelRes, Runnable action) {
        TextView t = text(16, Color.WHITE, false);
        t.setText(labelRes);
        t.setPadding(dp(14), dp(10), dp(14), dp(10));
        t.setFocusable(true);
        GradientDrawable on = new GradientDrawable();
        on.setCornerRadius(dp(10));
        on.setColor(0x33FFFFFF);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, on);
        bg.addState(new int[0], new ColorDrawable(Color.TRANSPARENT));
        t.setBackground(bg);
        t.setOnKeyListener((v, code, e) -> {
            if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) return true;
            if ((code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER)) {
                // Le relâchement de l'appui long qui a ouvert le menu ne compte pas : il faut un nouvel appui
                if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) menuArmed = true;
                if (e.getAction() == KeyEvent.ACTION_UP && menuArmed) {
                    Sounds.play(Sounds.SELECT);
                    closeAppMenu(false);
                    action.run();
                }
                return true;
            }
            return false;
        });
        return t;
    }

    void closeAppMenu(boolean refocus) {
        if (appMenu == null) return;
        View m = appMenu;
        appMenu = null;
        glasses.remove(m);
        m.animate().cancel();
        m.animate().alpha(0f).setDuration(150).setUpdateListener(null).withEndAction(() -> {
            ViewGroup parent = (ViewGroup) m.getParent();
            if (parent != null) parent.removeView(m);
        }).start();
        track.setFocusable(true);
        header.setFocusable(true);
        if (refocus) track.requestFocus();
        scheduleIdle();
    }

    /** Mode déplacement : l'appli du centre suit les flèches, OK ou Retour termine. */
    void startMove() {
        track.requestFocus();
        moveMode = true;
        ui.removeCallbacks(goIdle);
        if (dockHint == null) {
            dockHint = text(14, Color.WHITE, false);
            dockHint.setText(R.string.move_hint);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            lp.bottomMargin = dock.getHeight() + dp(26) + dp(10);
            mid.addView(dockHint, lp);
        }
        dockHint.setVisibility(View.VISIBLE);
        View t = tiles.get(selected);
        t.animate().translationYBy(-dp(8)).setDuration(180).start();
    }

    void endMove() {
        moveMode = false;
        if (dockHint != null) dockHint.setVisibility(View.GONE);
        layoutCarousel();
        saveDockOrder();
        scheduleIdle();
    }

    /** Échange l'appli du centre avec sa voisine : elle reste au centre, la voisine passe de l'autre côté. */
    void moveSelected(int dir) {
        int n = tiles.size();
        if (n < 2 || (!settings.loop.value && (selected + dir < 0 || selected + dir >= n))) return;
        Sounds.play(Sounds.STEP);
        int to = Math.floorMod(selected + dir, n);
        java.util.Collections.swap(tiles, selected, to);
        selected = to;
        position = target = settings.loop.value ? target + dir : selected;
        layoutCarousel();
        View t = tiles.get(selected);
        t.setTranslationY(t.getTranslationY() - dp(8));
    }

    void removeSelected() {
        String pkg = (String) tiles.get(selected).getTag();
        List<String> order = dockOrder();
        order.remove(pkg);
        if (InputShortcuts.isShortcut(pkg)) InputShortcuts.remove(settings, pkg);
        // La sélection passe à la voisine de droite
        if (tiles.size() > 1) prefs.edit().putString("dock_selected",
                (String) tiles.get(Math.floorMod(selected + 1, tiles.size())).getTag()).apply();
        writeDockOrder(order, true);
    }

    /** Liste des applis installées absentes de la barre ; celle choisie s'insère après l'appli du centre. */
    void pickAppToAdd() {
        List<String> order = dockOrder();
        List<String[]> candidates = new ArrayList<>();
        // En tête, un raccourci vers une entrée HDMI de la TV (console…), s'il y en a
        if (!InputShortcuts.hdmiInputs(this).isEmpty()) {
            candidates.add(new String[]{null, getString(R.string.add_input_shortcut)});
        }
        for (String[] a : installedApps()) if (!order.contains(a[0])) candidates.add(a);
        if (candidates.isEmpty()) {
            android.widget.Toast.makeText(this, R.string.add_app_none, android.widget.Toast.LENGTH_SHORT).show();
            track.requestFocus();
            return;
        }
        // Chaque ligne montre le logo de l'appli : sa bannière TV, sinon son icône centrée
        // dans un cadre de même taille, pour que les noms restent alignés
        PackageManager pm = getPackageManager();
        int logoW = dp(64), logoH = dp(36);
        android.widget.ArrayAdapter<String[]> adapter = new android.widget.ArrayAdapter<String[]>(
                this, android.R.layout.select_dialog_item, android.R.id.text1, candidates) {
            @Override
            public View getView(int pos, View convert, ViewGroup parent) {
                TextView tv = (TextView) super.getView(pos, convert, parent);
                String[] c = getItem(pos);
                tv.setText(c[1]);
                Drawable logo = null;
                boolean wide = false;
                try {
                    if (c[0] == null) {
                        logo = getDrawable(InputShortcuts.ICON_RES[0]);
                    } else {
                        logo = pm.getApplicationBanner(c[0]);
                        wide = logo != null;
                        if (logo == null) logo = pm.getApplicationIcon(c[0]);
                    }
                } catch (PackageManager.NameNotFoundException ignored) { }
                if (logo != null) {
                    if (!wide) {
                        int side = logoH, pad = (logoW - side) / 2;
                        logo = new android.graphics.drawable.InsetDrawable(logo, pad, 0, pad, 0);
                    }
                    logo.setBounds(0, 0, logoW, logoH);
                }
                tv.setCompoundDrawablesRelative(logo, null, null, null);
                tv.setCompoundDrawablePadding(dp(16));
                return tv;
            }
        };
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.add_app_title)
                .setAdapter(adapter, (d, which) -> {
                    String[] c = candidates.get(which);
                    if (c[0] == null) {
                        editShortcut(null);
                    } else {
                        insertInDock(c[0]);
                    }
                })
                .setOnDismissListener(d -> track.requestFocus())
                .show();
    }

    /** Ajoute une entrée à la barre juste après l'appli du centre, et la sélectionne. */
    void insertInDock(String entry) {
        List<String> o = dockOrder();
        String current = tiles.isEmpty() ? null : (String) tiles.get(selected).getTag();
        int at = current == null ? -1 : o.indexOf(current);
        o.add(at < 0 ? o.size() : at + 1, entry);
        prefs.edit().putString("dock_selected", entry).apply();
        writeDockOrder(o, true);
    }

    /** Panneau unique de création ou de modification d'un raccourci HDMI. */
    void editShortcut(String entry) {
        InputShortcuts.Shortcut sc = entry == null ? null : InputShortcuts.find(settings, entry);
        if (entry != null && sc == null) return;
        ui.removeCallbacks(goIdle);
        track.setFocusable(false);
        header.setFocusable(false);
        menuBlur(true);
        shortcutEditor = ShortcutEditor.show(this, shortcutGlass, accent, sc, new ShortcutEditor.Listener() {
            @Override public void saved(InputShortcuts.Shortcut edited) {
                if (entry == null) {
                    InputShortcuts.Shortcut created = InputShortcuts.create(settings, edited.input, edited.name, edited.icon);
                    insertInDock(InputShortcuts.PREFIX + created.id);
                } else {
                    InputShortcuts.update(settings, edited);
                    populateApps();
                }
            }

            @Override public void removed() {
                removeSelected();
            }

            @Override public void closed() {
                menuBlur(false);
                track.setFocusable(true);
                header.setFocusable(true);
                track.requestFocus();
                scheduleIdle();
            }
        });
    }

    List<String> dockOrder() {
        List<String> order = new ArrayList<>();
        for (String p : settings.dockApps.value.split(",")) if (!p.trim().isEmpty()) order.add(p.trim());
        return order;
    }

    /** Ordre des tuiles après un déplacement ; les applis désinstallées gardent leur place en fin de liste. */
    void saveDockOrder() {
        List<String> order = new ArrayList<>();
        for (View t : tiles) order.add((String) t.getTag());
        for (String p : dockOrder()) if (!order.contains(p)) order.add(p);
        prefs.edit().putString("dock_selected", (String) tiles.get(selected).getTag()).apply();
        writeDockOrder(order, false);
    }

    /** Enregistre la barre ; {@code rebuild} : recrée les tuiles (ajout, retrait), sinon elles sont déjà en place. */
    void writeDockOrder(List<String> order, boolean rebuild) {
        StringBuilder b = new StringBuilder();
        for (String p : order) b.append(b.length() == 0 ? "" : ",").append(p);
        settings.dockApps.value = b.toString();
        editingDock = !rebuild;
        settings.changed(settings.dockApps);
        editingDock = false;
        track.requestFocus();
    }

    /** C'est le menu qui se déplace : la sélection reste au centre, en boucle (ou bornée). */
    void scrollBy(int dir) {
        int n = tiles.size();
        if (!settings.loop.value && (selected + dir < 0 || selected + dir >= n)) return;
        Sounds.play(Sounds.MOVE);
        selected = Math.floorMod(selected + dir, n);
        prefs.edit().putString("dock_selected", (String) tiles.get(selected).getTag()).apply();
        target = settings.loop.value ? target + dir : selected;
        if (scrollAnim != null) scrollAnim.cancel();
        scrollAnim = ValueAnimator.ofFloat(position, target);
        scrollAnim.setDuration(settings.scrollSpeed.value());
        scrollAnim.setInterpolator(ease);
        scrollAnim.addUpdateListener(a -> {
            position = (float) a.getAnimatedValue();
            layoutCarousel();
        });
        scrollAnim.start();
        sweepShine(tiles.get(selected), 200);
        updateGlowColor();
        if (settings.scrollParallaxOn()) nudgeParallax(dir);
    }

    void launchSelected() {
        if (tiles.isEmpty()) return;
        Sounds.play(Sounds.SELECT);
        String pkg = (String) tiles.get(selected).getTag();
        if (InputShortcuts.isShortcut(pkg)) {
            InputShortcuts.Shortcut sc = InputShortcuts.find(settings, pkg);
            if (sc != null) launch(InputShortcuts.launchIntent(sc.input));
            return;
        }
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i != null) launch(i);
    }

    /**
     * Place chaque tuile selon sa distance au centre : la centrale est grande, inclinée et en avant,
     * les voisines rapetissent et s'estompent, et celles qui passent d'un bord à l'autre de la boucle
     * le font en étant invisibles.
     */
    void layoutCarousel() {
        int n = tiles.size();
        if (n == 0) return;
        float slot = dp(SLOT_DP * settings.tileSize.f() * settings.tileSpacing.f() * fit());
        float half = n / 2f;
        // Les tuiles au-delà des applis visibles s'effacent (2 de chaque côté pour 5 visibles, 3 pour 7…)
        float side = (settings.visibleApps.value() - 1) / 2f;
        // Au-delà, la tuile s'efface sur un peu moins d'une case : celle qui entre apparaît en glissant
        float fadeEnd = side + 0.8f;
        float dim = settings.neighborDim.f();
        float zoom = 1f + (centerZoom() - 1f) * focusAmount;
        float tilt = settings.tilt.value;
        for (int i = 0; i < n; i++) {
            View t = tiles.get(i);
            float d;
            if (settings.loop.value) {
                // Place de la tuile à l'arrivée, ramenée dans [-n/2, n/2[ (c'est ce qui fait boucler),
                // puis le glissement restant jusqu'à l'arrivée. Compter depuis l'arrivée et non depuis
                // la position du moment rend le défilement symétrique : la tuile qui revient d'un bord
                // entre en glissant, à gauche comme à droite, au lieu de surgir à la fin de l'animation.
                float base = (i - target) % n;
                if (base < 0) base += n;
                if (base >= half) base -= n;
                d = base + (target - position);
            } else {
                d = i - position;
            }
            float ad = Math.abs(d);
            float near = Math.max(0f, 1f - ad); // 1 au centre, 0 dès qu'on est à une case
            float small = 1f - 0.1f * dim, smaller = 1f - 0.2f * dim;
            float scale = ad < 1 ? zoom + (small - zoom) * ad : small + (smaller - small) * Math.min(ad - 1, 1);
            float alpha = ad < 1 ? 1f - 0.3f * dim * ad : (1f - 0.3f * dim) - 0.15f * dim * Math.min(ad - 1, 1);
            if (settings.loop.value && ad > side) alpha *= Math.max(0f, 1f - (ad - side) / Math.max(0.01f, fadeEnd - side));
            if (focusAmount < 1f) alpha = alpha + (Math.min(0.92f, alpha + 0.2f) - alpha) * (1f - focusAmount);
            t.setTranslationX(d * slot);
            t.setScaleX(scale);
            t.setScaleY(scale);
            t.setAlpha(Math.max(0f, alpha));
            t.setTranslationY(-dp(6) * near * focusAmount);
            t.setTranslationZ(dp(10) * near * focusAmount);
            t.setRotationX(tilt * near * focusAmount);
        }
    }

    /** Reflet qui balaie la tuile arrivée au centre. */
    void sweepShine(View tile, long delay) {
        if (!settings.shine.value) return;
        View shine = tile.findViewWithTag(SHINE_TAG);
        if (shine == null || tile.getWidth() == 0) return;
        shine.animate().cancel();
        shine.setTranslationX(-shine.getWidth() * 1.5f);
        shine.setAlpha(1f);
        shine.animate().translationX(tile.getWidth() + shine.getWidth()).setStartDelay(delay).setDuration(750)
                .setInterpolator(ease).withEndAction(() -> shine.setAlpha(0f)).start();
    }

    /** Parallaxe au défilement : le fond glisse un peu à l'opposé, puis revient en douceur. */
    void nudgeParallax(int dir) {
        float k = settings.parallaxAmount();
        wallOuter.animate().translationX(-dir * dp(16) * k).translationY(0).setStartDelay(0).setDuration(260)
                .setInterpolator(ease).setUpdateListener(a -> invalidateGlasses())
                .withEndAction(() -> wallOuter.animate().translationX(0).setDuration(1100).setInterpolator(ease)
                        .setUpdateListener(a -> invalidateGlasses()).withEndAction(null).start())
                .start();
        mid.animate().translationX(-dir * dp(5) * k).setStartDelay(0).setDuration(260).setInterpolator(ease)
                .withEndAction(() -> mid.animate().translationX(0).setDuration(1100).withEndAction(null).start()).start();
    }

    /** Le dock prend ou perd le focus : la tuile centrale s'élève (ou se repose), la lueur s'allume. */
    void onDockFocus(boolean has) {
        if (focusAnim != null) focusAnim.cancel();
        focusAnim = ValueAnimator.ofFloat(focusAmount, has ? 1f : 0f);
        focusAnim.setDuration(has ? 320 : 220);
        focusAnim.setInterpolator(ease);
        focusAnim.addUpdateListener(a -> {
            focusAmount = (float) a.getAnimatedValue();
            layoutCarousel();
        });
        focusAnim.start();
        updateGlowColor();
        boolean visible = has && settings.glowMode.index != Settings.GLOW_NONE;
        glow.animate().alpha(visible ? glowAlpha() : 0f).setDuration(220).start();
        if (has) {
            placeGlow();
            dock.setHighlightX(dock.getWidth() / 2f);
            if (!tiles.isEmpty()) sweepShine(tiles.get(selected), 120);
            parallax(track);
        } else {
            dock.setHighlightX(Float.NaN);
        }
    }

    /** Lueur : couleur du thème, couleur de l'appli au centre, ou éteinte. */
    void updateGlowColor() {
        if (glow == null) return;
        int mode = settings.glowMode.index;
        int c = accent;
        if (mode == Settings.GLOW_APP && !tiles.isEmpty()) {
            Integer app = tileColors.get(tiles.get(selected));
            if (app != null && app != 0) c = app;
        }
        glow.setColor(c);
        if (mode == Settings.GLOW_NONE) glow.setAlpha(0f);
        else if (track != null && track.hasFocus()) glow.setAlpha(glowAlpha());
    }

    /** La lueur et le point lumineux restent fixes au centre : c'est le menu qui bouge. */
    void placeGlow() {
        if (lane.getWidth() == 0) return;
        float w = tileW() * centerZoom(), h = tileH() * centerZoom();
        float cx = lane.getWidth() / 2f, cy = lane.getHeight() / 2f - dp(6);
        // Au-delà de 100 %, le halo ne peut plus s'intensifier : il s'étend
        float spread = Math.max(1f, settings.glowPower.f());
        float ex = w * FocusGlow.INNER_X * spread, ey = h * FocusGlow.INNER_Y * spread;
        glow.moveTo(new RectF(cx - w / 2 - ex, cy - h / 2 - ey, cx + w / 2 + ex, cy + h / 2 + ey));
        if (track.hasFocus()) dock.setHighlightX(cx);
    }
}
