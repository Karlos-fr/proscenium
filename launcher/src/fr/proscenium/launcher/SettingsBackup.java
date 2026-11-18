package fr.proscenium.launcher;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;

/**
 * Sauvegarde et restauration de tous les réglages (verre compris) dans un fichier JSON
 * du dossier de l'appli, récupérable et déposable par adb :
 *  - settings-backup.json : écrit par « Sauvegarder les réglages », relu par « Restaurer » ;
 *  - settings-import.json : déposé depuis le PC, appliqué au lancement suivant puis supprimé.
 * La clé TMDB et les états passagers (fond en cours, appli sélectionnée…) ne sont pas copiés.
 */
final class SettingsBackup {
    static final String BACKUP = "settings-backup.json", IMPORT = "settings-import.json";

    private SettingsBackup() {}

    static File file(Context ctx, String name) {
        File base = ctx.getExternalFilesDir(null);
        return new File(base == null ? ctx.getFilesDir() : base, name);
    }

    /** Réglages propres à cette box ou passagers, jamais sauvegardés ni écrasés. */
    private static boolean local(String key) {
        return key.equals("tmdb_key") || key.equals("dock_selected") || key.startsWith("wall_")
                || key.startsWith("reopen_") || key.startsWith("update_") || key.equals("imported_at") || key.equals("trending_created");
    }

    static void save(Context ctx, SharedPreferences prefs) throws Exception {
        JSONObject root = new JSONObject();
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            if (local(e.getKey())) continue;
            Object v = e.getValue();
            String type = v instanceof Boolean ? "b" : v instanceof Integer ? "i" : v instanceof Long ? "l"
                    : v instanceof Float ? "f" : v instanceof String ? "s" : null;
            if (type != null) root.put(e.getKey(), new JSONObject().put("t", type).put("v", v));
        }
        File f = file(ctx, BACKUP), tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(root.toString(1).getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(f)) throw new IllegalStateException("Renommage impossible");
    }

    /** Applique un fichier de réglages ; les clés absentes gardent leur valeur. Renvoie false s'il n'existe pas. */
    static boolean load(Context ctx, SharedPreferences prefs, String name) throws Exception {
        File f = file(ctx, name);
        if (!f.exists()) return false;
        byte[] raw = new byte[(int) f.length()];
        try (FileInputStream in = new FileInputStream(f)) {
            for (int off = 0, n; off < raw.length && (n = in.read(raw, off, raw.length - off)) > 0; ) off += n;
        }
        JSONObject root = new JSONObject(new String(raw, StandardCharsets.UTF_8));
        SharedPreferences.Editor e = prefs.edit();
        for (Iterator<String> it = root.keys(); it.hasNext(); ) {
            String key = it.next();
            if (local(key)) continue;
            JSONObject o = root.getJSONObject(key);
            switch (o.getString("t")) {
                case "b": e.putBoolean(key, o.getBoolean("v")); break;
                case "i": e.putInt(key, o.getInt("v")); break;
                case "l": e.putLong(key, o.getLong("v")); break;
                case "f": e.putFloat(key, (float) o.getDouble("v")); break;
                default: e.putString(key, o.getString("v")); break;
            }
        }
        e.commit();
        return true;
    }
}
