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
import android.widget.Toast;

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
 * Écran d'accueil, en trois plans : le fond (Ken Burns + parallaxe), la barre d'apps
 * (carrousel façon Aeon), puis l'heure et le bandeau. Tous les réglages viennent de Settings
 * et s'appliquent en direct depuis le panneau de réglages.
 */
public class MainActivity extends HomeDock implements Settings.Listener, SettingsPanel.Host {
    // ---------------------------------------------------------------- Langue

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(Lang.wrap(base));
    }

    // ---------------------------------------------------------------- Cycle de vie

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        density = getResources().getDisplayMetrics().density;
        android.app.ActivityManager.MemoryInfo mem = new android.app.ActivityManager.MemoryInfo();
        ((android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(mem);
        if (mem.totalMem >= 2L * 1024 * 1024 * 1024) kenBurnsTick = KEN_BURNS_TICK_FAST_MS;
        prefs = getSharedPreferences("proscenium", MODE_PRIVATE);
        importDroppedSettings();
        settings.load(prefs);
        // 0.74 : le halo prend désormais la couleur du logo de l'appli (une seule fois, ensuite au choix)
        if (!prefs.getBoolean("glow_app_color", false)) {
            settings.glowMode.index = Settings.GLOW_APP;
            settings.changed(settings.glowMode);
            prefs.edit().putBoolean("glow_app_color", true).apply();
        }
        settings.addListener(this);
        // Premier lancement avec les playlists : toutes celles qui existent (dont l'ancien dossier migré) sont affichées
        if (!prefs.contains(settings.activePlaylists.key)) {
            StringBuilder ids = new StringBuilder();
            for (Playlists.Playlist p : Playlists.all(this)) ids.append(ids.length() == 0 ? "" : ",").append(p.id);
            settings.activePlaylists.value = ids.toString();
            settings.changed(settings.activePlaylists);
        }
        glassSettings.load(prefs);
        weatherStyles.load(prefs);
        Locale locale = Lang.locale(this);
        dateFormat = new SimpleDateFormat(getString(R.string.date_pattern), locale);
        clockFormat = new SimpleDateFormat(settings.timeFormat.index == 1 ? "hhmmss" : "HHmmss", locale);
        Wallpaper.market = Lang.tag(this);

        FrameLayout root = new FrameLayout(this);
        root.setClipChildren(false);
        root.setBackgroundColor(0xFF101826);

        // Fond : parallaxe (outer) > Ken Burns (inner) > photo nette + photo floutée pour les transitions
        wallOuter = new FrameLayout(this);
        wallInner = new FrameLayout(this);
        wallImg = new ImageView(this);
        wallImg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        blurImg = new ImageView(this);
        blurImg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        blurImg.setAlpha(0f);
        dofImg = new ImageView(this);
        dofImg.setScaleType(ImageView.ScaleType.FIT_XY);
        dofImg.setAlpha(0f);
        wallInner.addView(wallImg, match());
        wallInner.addView(dofImg, match());
        wallInner.addView(blurImg, match());
        wallOuter.addView(wallInner, match());
        root.addView(wallOuter, match());
        GlassView.outer = wallOuter;
        GlassView.inner = wallInner;
        setKenBurns(0.5f);

        // Vignettage intelligent : deux dégradés (haut et bas) dont l'opacité suit la luminosité de l'image
        shadeTop = new View(this);
        shadeTop.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xC0000000, 0x40000000, 0x00000000}));
        FrameLayout.LayoutParams topShadeLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(230));
        topShadeLp.gravity = Gravity.TOP;
        root.addView(shadeTop, topShadeLp);
        shadeBottom = new View(this);
        shadeBottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xC0000000, 0x40000000, 0x00000000}));
        FrameLayout.LayoutParams bottomShadeLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(260));
        bottomShadeLp.gravity = Gravity.BOTTOM;
        root.addView(shadeBottom, bottomShadeLp);
        shadeTop.setAlpha(0.5f);
        shadeBottom.setAlpha(0.6f);
        // Météo vivante : devant le film, derrière l'interface
        weatherFx = new WeatherFx(this, wallImg, wallInner, wallOuter, weatherStyles);
        root.addView(weatherFx, match());
        applyWeatherFx();

        mid = new FrameLayout(this);
        mid.setClipChildren(false);
        buildDock();
        root.addView(mid, match());
        // Verre réfractif du dock (moteur Codex Glass)
        glassCtl = new GlassController(dock, wallImg, wallInner, wallOuter, glassSettings, density);
        glassCtl.settingsChanged();
        glassCtl.setEnabled(settings.glassEngine.value);
        glassCtl.setAnimationsAllowed(!settings.economy.value);
        Sounds.configure(settings.uiSounds.value, settings.uiVolume.f());
        io.execute(() -> Sounds.init(this));

        buildCinema(root);

        front = new FrameLayout(this);
        front.setClipChildren(false);
        front.addView(buildClock(), at(Gravity.TOP | Gravity.START, 48, 26, 0, 0));
        front.addView(buildHeader(), at(Gravity.TOP | Gravity.END, 0, 30, 44, 0));
        root.addView(front, match());
        // Le bandeau CPU/RAM/météo prend lui aussi le verre réfractif, là où la puce graphique le calcule
        glassCtl.addGpuPanel(header, dp(18 * settings.headerScale.f()));
        // Même épaisseur de contour que la barre d'applis (réglage Verre → Contour)
        header.setRimWidth(settings.rimWidth.value);

        panel = new SettingsPanel(this, this, settings, glassSettings);
        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(dp(860), dp(500));
        panelLp.gravity = Gravity.CENTER;
        root.addView(panel.view, panelLp);
        // Le panneau de réglages a le même verre que la barre et le bandeau (réfraction, contour)
        glassCtl.addGpuPanel(panel.view, dp(28));
        panel.view.setRimWidth(settings.rimWidth.value);
        // Panneau du raccourci HDMI : même verre, même taille que celui des réglages
        shortcutGlass = newGlass(dp(28));
        shortcutGlass.setShade(0xA60E131C);
        shortcutGlass.setVisibility(View.GONE);
        FrameLayout.LayoutParams shortcutLp = new FrameLayout.LayoutParams(dp(860), dp(500));
        shortcutLp.gravity = Gravity.CENTER;
        root.addView(shortcutGlass, shortcutLp);
        glassCtl.addGpuPanel(shortcutGlass, dp(28));
        shortcutGlass.setRimWidth(settings.rimWidth.value);

        // Haut / bas : du carrousel au bandeau et inversement
        header.setId(View.generateViewId());
        header.setNextFocusDownId(track.getId());
        track.setNextFocusUpId(header.getId());

        setContentView(root);
        applyBand();
        applyHeaderBlocks();
        applyCinemaLayout();
    }

    @Override
    protected void onResume() {
        super.onResume();
        populateApps();
        ui.post(statsTick);
        ui.post(clockTick);
        ui.post(kenBurns);
        scheduleRotate();
        weatherFx.start();
        if (System.currentTimeMillis() - weatherAt > WEATHER_EVERY_MS) refreshWeather();
        // Films migrés sans fiche TMDB : on la retrouve en arrière-plan (une seule fois par film)
        Tmdb tmdb = tmdb();
        net.execute(() -> {
            Playlists.identify(this, tmdb);
            // Résumé, accroche, genres, note et durée des films de tes playlists (une fois par mois)
            Playlists.fillInfo(this, tmdb);
        });
        // Tendances du jour (au plus une mise à jour par 24 h), puis ménage des images inutilisées
        String logoLang = settings.tmdbLanguage(Lang.tag(this)).substring(0, 2);
        boolean original = settings.tmdbQuality.index == Settings.QUALITY_ORIGINAL;
        // Les tendances sont créées et affichées d'office, une seule fois : supprimées, elles ne reviennent pas
        if (tmdb.hasKey() && !prefs.getBoolean("trending_created", false)) {
            boolean exists = false;
            for (Playlists.Playlist p : Playlists.all(this)) exists |= p.isDynamic();
            if (!exists) {
                Playlists.Playlist p = Playlists.createTrending(this, getString(R.string.playlist_trending));
                settings.setActive(p.id, true);
            }
            prefs.edit().putBoolean("trending_created", true).apply();
        }
        net.execute(() -> Playlists.refreshDynamic(this, tmdb, logoLang, original, false));
        // Nouvelle version sur GitHub : vérifiée au plus une fois par jour, annoncée une seule fois
        if (settings.updateCheck.value) net.execute(() -> {
            String v = Updater.dailyCheck(this, prefs);
            if (v != null) ui.post(() -> Toast.makeText(this, getString(R.string.update_toast, v), Toast.LENGTH_LONG).show());
        });
        importDroppedPlaylist(tmdb, logoLang, original);
        if (!cleanedUp) {
            cleanedUp = true;
            Playlists.cleanUpLater(this);
        }
        loadWallpaper(CURRENT);
        appear();
        glassCtl.start();
        // Lentille du verre, une fois le dock mis en page
        dock.post(this::updateGlassLenses);
        // Après un changement de langue ou de structure, on revient dans le panneau, sur le même onglet
        int reopen = prefs.getInt(PREF_REOPEN_TAB, -1);
        boolean welcome = prefs.getBoolean(PREF_REOPEN_WELCOME, false);
        if (reopen >= 0) {
            prefs.edit().remove(PREF_REOPEN_TAB).remove(PREF_REOPEN_WELCOME).apply();
            ui.postDelayed(() -> {
                openPanel(reopen);
                if (welcome) panel.openWelcome();
            }, 300);
        } else if (!prefs.getBoolean(PanelTabs.PREF_WELCOME_DONE, false)) {
            // Premier lancement : les Premiers pas. Une installation déjà garnie (mise à jour,
            // réglages importés) n'en a pas besoin.
            boolean configured = prefs.contains("imported_at");
            for (Playlists.Playlist p : Playlists.all(this)) configured |= !p.films.isEmpty();
            if (configured) prefs.edit().putBoolean(PanelTabs.PREF_WELCOME_DONE, true).apply();
            else ui.postDelayed(() -> {
                if (panel.isOpen()) return;
                openPanel(PanelCore.TAB_SYSTEM);
                panel.openWelcome();
            }, 1500);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Rien ne tourne pendant qu'une appli est ouverte
        glassCtl.stop();
        weatherFx.stop();
        ui.removeCallbacks(statsTick);
        ui.removeCallbacks(clockTick);
        ui.removeCallbacks(kenBurns);
        ui.removeCallbacks(rotate);
        ui.removeCallbacks(goIdle);
    }

    @Override
    protected void onDestroy() {
        settings.removeListener(this);
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        wake();
    }

    @Override
    public void onBackPressed() {
        // L'écran d'accueil ne se ferme pas avec Retour ; Retour remonte dans le panneau,
        // ferme le menu d'une appli ou termine son déplacement
        if (shortcutEditor != null && shortcutEditor.isOpen()) {
            Sounds.play(Sounds.BACK);
            shortcutEditor.close();
        } else if (appMenu != null) {
            Sounds.play(Sounds.BACK);
            closeAppMenu(true);
        } else if (moveMode) {
            Sounds.play(Sounds.BACK);
            endMove();
        } else if (panel.isOpen()) {
            panel.back();
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            boolean wasIdle = idle;
            wake();
            // Au repos, le premier appui ne fait que réveiller l'écran
            if (wasIdle) {
                swallowKeyUp = true;
                return true;
            }
        } else if (e.getAction() == KeyEvent.ACTION_UP && swallowKeyUp) {
            swallowKeyUp = false;
            return true;
        }
        // Tic discret quand une flèche déplace vraiment la sélection (le carrousel joue le sien)
        View before = getCurrentFocus();
        boolean handled = super.dispatchKeyEvent(e);
        if (e.getAction() == KeyEvent.ACTION_DOWN && isArrow(e.getKeyCode()) && getCurrentFocus() != before) {
            Sounds.play(Sounds.MOVE);
        }
        return handled;
    }

    private static boolean isArrow(int code) {
        return code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT;
    }

    // ---------------------------------------------------------------- Réglages : aperçu en direct

    @Override
    public void onSettingChanged(Settings.Item item) {
        Settings s = settings;
        if (item == null) {
            // Tout a été réinitialisé : on reconstruit tout, langue comprise
            reopenAfterRecreate();
            return;
        }
        if (item == s.language || item == s.headerScale || item == s.tileSize || item == s.visibleApps
                || item == s.timeFormat) {
            // Changements de structure : l'écran est reconstruit, le panneau rouvert sur le même onglet
            reopenAfterRecreate();
        } else if (item == s.bgSource || item == s.activePlaylists) {
            loadWallpaper(SWITCH);
            scheduleRotate();
        } else if (item == s.bgDelay) {
            scheduleRotate();
        } else if (item == s.vignette || item == s.vignetteIntensity) {
            if (frame != null) applyVignette(frame);
        } else if (item == s.themeAuto || item == s.themeColor) {
            if (frame != null) animateAccent(themeColor(frame));
        } else if (item == s.kenBurns || item == s.kbAmplitude || item == s.economy) {
            if (item == s.economy) {
                applyWeatherFx();
                glassCtl.setAnimationsAllowed(!s.economy.value);
                weatherIcon.setAnimated(s.weatherAnimOn());
                glow.setAnimated(s.glowAnimOn());
            }
            ui.removeCallbacks(kenBurns);
            ui.post(kenBurns);
            if (!s.kenBurnsOn()) setKenBurns(0.5f);
        } else if (item == s.cinemaBars || item == s.barOpacity || item == s.titleSide) {
            applyCinemaLayout();
        } else if (item == s.titleMode || item == s.showYear || item == s.titleInfo || item == s.titleRating
                || item == s.titleSummary || item == s.titleTextSize) {
            if (frame != null) fillTitleCard(frame);
        } else if (item == s.dockApps) {
            // Après un déplacement à la télécommande, les tuiles sont déjà dans le bon ordre
            if (!editingDock) populateApps();
        } else if (item == s.bandMode || item == s.slimBand) {
            applyBand();
        } else if (item == s.tileSpacing || item == s.centerZoom || item == s.tilt || item == s.neighborDim
                || item == s.loop) {
            if (!s.loop.value) {
                position = target = selected;
            }
            layoutCarousel();
            placeGlow();
        } else if (item == s.glowMode || item == s.glowPower) {
            updateGlowColor();
            placeGlow();
        } else if (item == s.glassEngine) {
            glassCtl.setEnabled(s.glassEngine.value);
        } else if (item == s.rimWidth) {
            // Même contour pour la barre, le bandeau du haut et le panneau de réglages
            dock.setRimWidth(s.rimWidth.value);
            header.setRimWidth(s.rimWidth.value);
            panel.view.setRimWidth(s.rimWidth.value);
            shortcutGlass.setRimWidth(s.rimWidth.value);
            applyAccent();
        } else if (item == s.clockSize || item == s.dateSize) {
            applyClockSizes();
        } else if (item == s.uiSounds || item == s.uiVolume) {
            Sounds.configure(s.uiSounds.value, s.uiVolume.f());
        } else if (item == s.weatherCity || item == s.weatherCoords || item == s.tempUnit) {
            cityText.setText(s.weatherCity.value);
            refreshWeather();
        } else if (item == s.showCpu || item == s.showRam || item == s.showWeather
                || item == s.showWind) {
            applyHeaderBlocks();
        } else if (item == s.glowAnim) {
            glow.setAnimated(s.glowAnimOn());
        } else if (item == s.glowAnimAmount || item == s.glowAnimSpeed) {
            glow.setLiveness(s.glowAnimAmount.f(), s.glowAnimSpeed.f());
        } else if (item == s.weatherFx || item == s.weatherFxAmount || item == s.weatherLens || item == s.fxRain
                || item == s.fxLightning || item == s.fxSnow || item == s.fxFog || item == s.fxWind || item == s.fxSun) {
            applyWeatherFx();
        } else if (item == s.weatherAnim) {
            weatherIcon.setAnimated(s.weatherAnimOn());
        }
    }

    /** Réglages déposés depuis le PC (settings-import.json) : appliqués une fois, au lancement. */
    /**
     * Playlist déposée depuis le PC (adb push … playlist-import.json, dans le dossier de l'appli) :
     * {"name": "…", "movies": [{"id": 862, "title": "Toy Story", "year": "1995"}, …]}.
     * La playlist est créée (ou complétée si elle existe), chaque film reçoit son meilleur fond
     * et son logo, selon les réglages de qualité, puis le fichier est supprimé.
     */
    void importDroppedPlaylist(Tmdb tmdb, String logoLang, boolean original) {
        java.io.File f = new java.io.File(getExternalFilesDir(null), "playlist-import.json");
        if (!f.exists() || !tmdb.hasKey()) return;
        try {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            try (java.io.InputStream in = new java.io.FileInputStream(f)) {
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) > 0; ) bytes.write(buf, 0, n);
            }
            org.json.JSONObject o = new org.json.JSONObject(bytes.toString("UTF-8"));
            String name = o.getString("name");
            Playlists.Playlist target = null;
            for (Playlists.Playlist p : Playlists.all(this)) if (!p.isDynamic() && p.name.equals(name)) target = p;
            if (target == null) target = Playlists.create(this, name);
            org.json.JSONArray a = o.getJSONArray("movies");
            List<Tmdb.Movie> movies = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                org.json.JSONObject mo = a.getJSONObject(i);
                Tmdb.Movie m = new Tmdb.Movie();
                m.id = mo.getInt("id");
                m.title = mo.optString("title");
                m.year = mo.optString("year");
                movies.add(m);
            }
            if (!f.delete()) Log.w(TAG, "Playlist déposée : fichier non supprimé");
            Log.i(TAG, "Playlist déposée « " + name + " » : " + movies.size() + " films à importer");
            Playlists.importMovies(this, tmdb, target, movies, logoLang, original);
        } catch (Exception e) {
            Log.w(TAG, "Playlist déposée illisible", e);
            f.renameTo(new java.io.File(f.getPath() + ".erreur"));
        }
    }

    void importDroppedSettings() {
        java.io.File f = SettingsBackup.file(this, SettingsBackup.IMPORT);
        if (!f.exists() || f.lastModified() == prefs.getLong("imported_at", 0)) return;
        try {
            SettingsBackup.load(this, prefs, SettingsBackup.IMPORT);
            // Si le fichier ne peut pas être supprimé, sa date évite de l'appliquer à chaque lancement
            prefs.edit().putLong("imported_at", f.lastModified()).commit();
            f.delete();
        } catch (Exception e) {
            Log.w(TAG, "Réglages importés illisibles", e);
        }
    }

    @Override
    public boolean backupSettings() {
        try {
            SettingsBackup.save(this, prefs);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Sauvegarde impossible", e);
            return false;
        }
    }

    @Override
    public boolean restoreSettings() {
        try {
            if (!SettingsBackup.load(this, prefs, SettingsBackup.BACKUP)) return false;
        } catch (Exception e) {
            Log.w(TAG, "Restauration impossible", e);
            return false;
        }
        reopenAfterRecreate();
        return true;
    }

    void reopenAfterRecreate() {
        prefs.edit().putInt(PREF_REOPEN_TAB, panel.isOpen() ? panel.currentTab() : -1)
                .putBoolean(PREF_REOPEN_WELCOME, panel.onWelcome()).apply();
        if (!panel.isOpen()) prefs.edit().remove(PREF_REOPEN_TAB).apply();
        ui.post(this::recreate);
    }

    // ---------------------------------------------------------------- Panneau de réglages

    void openPanel(int tab) {
        ui.removeCallbacks(goIdle);
        track.setFocusable(false);
        header.setFocusable(false);
        menuBlur(true);
        panel.setAccent(accent);
        panel.open(tab);
    }


    @Override
    public void panelClosed() {
        previewWeather(-1);
        menuBlur(false);
        track.setFocusable(true);
        header.setFocusable(true);
        track.requestFocus();
        scheduleIdle();
    }

    @Override
    public GlassView newGlass(float radiusPx) {
        GlassView g = new GlassView(this, radiusPx);
        glasses.add(g);
        return g;
    }

    @Override
    public void nextBackdrop() {
        loadWallpaper(NEXT);
        scheduleRotate();
    }

    @Override
    public WeatherStyles weatherStyles() {
        return weatherStyles;
    }

    @Override
    public void weatherStylesChanged() {
        weatherStyles.save(prefs);
    }

    @Override
    public void previewWeather(int kind) {
        if (kind == weatherPreview) return;
        weatherPreview = kind;
        applyWeatherFx();
    }

    @Override
    public void glassChanged() {
        glassSettings.save(prefs);
        glassCtl.settingsChanged();
    }

    @Override
    public void openAccessibility() {
        launch(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
    }

    @Override
    public void openAndroidSettings() {
        launch(new Intent(android.provider.Settings.ACTION_SETTINGS));
    }

    @Override
    public boolean homeServiceActive() {
        String enabled = android.provider.Settings.Secure.getString(getContentResolver(),
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return enabled != null && enabled.contains(getPackageName());
    }

    @Override
    public List<String[]> installedApps() {
        PackageManager pm = getPackageManager();
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<String[]> out = new ArrayList<>();
        for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
            if (ri.activityInfo.packageName.equals(getPackageName())) continue;
            out.add(new String[]{ri.activityInfo.packageName, String.valueOf(ri.loadLabel(pm))});
        }
        Collator col = Collator.getInstance(Lang.locale(this));
        out.sort((a, b) -> col.compare(a[1], b[1]));
        return out;
    }

    @Override
    public void dockAppsChanged() {
        populateApps();
    }

    @Override
    public void searchCity(String query, SettingsPanel.CityCallback cb) {
        String language = Lang.locale(this).getLanguage();
        net.execute(() -> {
            String[] found = null;
            try {
                found = Weather.searchCity(query, language);
            } catch (Exception e) {
                Log.w(TAG, "Géocodage impossible", e);
            }
            String[] result = found;
            ui.post(() -> cb.found(result));
        });
    }

    @Override
    public Tmdb tmdb() {
        return new Tmdb(Tmdb.resolveKey(this, settings.tmdbKey.value), settings.tmdbLanguage(Lang.tag(this)));
    }

    @Override
    public boolean voiceAvailable() {
        return !getPackageManager().queryIntentActivities(
                new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0).isEmpty();
    }

    static final int REQ_VOICE = 7;
    SettingsPanel.TextCallback voiceCallback;

    @Override
    public void startVoice(SettingsPanel.TextCallback cb) {
        voiceCallback = cb;
        Intent i = new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, Lang.tag(this));
        try {
            startActivityForResult(i, REQ_VOICE);
        } catch (Exception e) {
            Log.w(TAG, "Reconnaissance vocale indisponible", e);
            voiceCallback = null;
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != REQ_VOICE || voiceCallback == null) return;
        SettingsPanel.TextCallback cb = voiceCallback;
        voiceCallback = null;
        List<String> heard = data == null ? null
                : data.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS);
        if (result == RESULT_OK && heard != null && !heard.isEmpty()) cb.text(heard.get(0));
    }

    @Override
    public String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }
}
