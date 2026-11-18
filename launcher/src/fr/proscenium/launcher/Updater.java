package fr.proscenium.launcher;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Mises à jour depuis les releases GitHub du projet : vérification (au plus une fois par jour),
 * téléchargement de l'APK puis installation par l'installeur d'Android, qui demande confirmation.
 * Le résultat de l'installation revient dans {@link Receiver}.
 */
final class Updater {
    static final String LATEST = "https://api.github.com/repos/Karlos-fr/proscenium/releases/latest";
    static final String RELEASES_PAGE = "https://github.com/Karlos-fr/proscenium/releases/latest";
    static final long EVERY_MS = 24L * 3600 * 1000;
    /** Préférences locales à la box (jamais sauvegardées) : préfixe « update_ ». */
    static final String P_CHECKED = "update_checked_at", P_VERSION = "update_version",
            P_URL = "update_url", P_NOTIFIED = "update_notified";

    private Updater() {}

    /** Dernière version publiée : numéro (sans « v ») et lien direct vers l'APK. */
    static final class Release {
        final String version, apkUrl;
        Release(String version, String apkUrl) { this.version = version; this.apkUrl = apkUrl; }
    }

    static Release fetchLatest() throws Exception {
        JSONObject r = new JSONObject(Net.getText(LATEST));
        String tag = r.optString("tag_name", "").replaceFirst("^[vV]", "");
        JSONArray assets = r.optJSONArray("assets");
        String apk = null;
        for (int i = 0; assets != null && i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            if (a.optString("name").toLowerCase().endsWith(".apk")) { apk = a.optString("browser_download_url"); break; }
        }
        if (tag.isEmpty() || apk == null) throw new IOException("Release sans APK");
        return new Release(tag, apk);
    }

    /** « 1.10 » est plus récent que « 1.9 » : comparaison nombre par nombre. */
    static boolean isNewer(String remote, String local) {
        String[] a = remote.split("[^0-9]+"), b = (local == null ? "" : local).split("[^0-9]+");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length && !a[i].isEmpty() ? Integer.parseInt(a[i]) : 0;
            int y = i < b.length && !b[i].isEmpty() ? Integer.parseInt(b[i]) : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    static String installedVersion(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    /** Vérifie maintenant et retient le résultat. Renvoie la version disponible, ou null si à jour. */
    static Release check(Context ctx, SharedPreferences prefs) throws Exception {
        Release r = fetchLatest();
        boolean newer = isNewer(r.version, installedVersion(ctx));
        prefs.edit().putLong(P_CHECKED, System.currentTimeMillis())
                .putString(P_VERSION, newer ? r.version : "")
                .putString(P_URL, newer ? r.apkUrl : "").apply();
        return newer ? r : null;
    }

    /** Version connue comme disponible (sans réseau), ou null. */
    static Release known(Context ctx, SharedPreferences prefs) {
        String v = prefs.getString(P_VERSION, ""), url = prefs.getString(P_URL, "");
        if (v.isEmpty() || url.isEmpty() || !isNewer(v, installedVersion(ctx))) return null;
        return new Release(v, url);
    }

    /**
     * Vérification quotidienne, à appeler hors du fil principal. Renvoie la version à annoncer
     * (une seule fois par version), ou null.
     */
    static String dailyCheck(Context ctx, SharedPreferences prefs) {
        if (System.currentTimeMillis() - prefs.getLong(P_CHECKED, 0) < EVERY_MS) return null;
        try {
            Release r = check(ctx, prefs);
            if (r == null || r.version.equals(prefs.getString(P_NOTIFIED, ""))) return null;
            prefs.edit().putString(P_NOTIFIED, r.version).apply();
            return r.version;
        } catch (Exception e) {
            Log.w(HomeBase.TAG, "Vérification des mises à jour impossible", e);
            return null;
        }
    }

    interface Progress { void percent(int p); }

    /** Télécharge l'APK dans le cache de l'appli (hors du fil principal). */
    static File download(Context ctx, String url, Progress progress) throws IOException {
        File dir = new File(ctx.getCacheDir(), "update");
        dir.mkdirs();
        File f = new File(dir, "Proscenium.apk");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(30_000);
        c.setRequestProperty("User-Agent", "Proscenium (Android TV)");
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            long total = c.getContentLengthLong(), done = 0;
            int last = -1;
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(f)) {
                byte[] buf = new byte[64 * 1024];
                for (int n; (n = in.read(buf)) > 0; ) {
                    out.write(buf, 0, n);
                    done += n;
                    int p = total > 0 ? (int) (done * 100 / total) : -1;
                    if (p != last) { last = p; progress.percent(p); }
                }
            }
        } finally {
            c.disconnect();
        }
        return f;
    }

    /**
     * Android 8+ : l'utilisateur doit autoriser Proscenium à installer des applis. Renvoie false
     * (et ouvre la page d'autorisation d'Android) tant que ce n'est pas fait.
     */
    static boolean canInstall(Activity a) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || a.getPackageManager().canRequestPackageInstalls()) return true;
        try {
            a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + a.getPackageName())));
        } catch (Exception e) {
            Log.w(HomeBase.TAG, "Page d'autorisation introuvable", e);
        }
        return false;
    }

    /** Confie l'APK à l'installeur d'Android (hors du fil principal) ; il demande confirmation. */
    static void install(Context ctx, File apk) throws IOException {
        PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(ctx.getPackageName());
        int id = pi.createSession(params);
        try (PackageInstaller.Session session = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = session.openWrite("Proscenium.apk", 0, apk.length())) {
                byte[] buf = new byte[64 * 1024];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                session.fsync(out);
            }
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent done = PendingIntent.getBroadcast(ctx, id, new Intent(ctx, Receiver.class), flags);
            session.commit(done.getIntentSender());
        }
    }

    /** Résultat de l'installation : confirmation à afficher, ou message d'échec. */
    public static final class Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(confirm);
                }
                return;
            }
            if (status == PackageInstaller.STATUS_SUCCESS) return;
            Log.w(HomeBase.TAG, "Installation refusée : " + status + " "
                    + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
            Context l = Lang.wrap(ctx);
            int msg = status == PackageInstaller.STATUS_FAILURE_CONFLICT
                    || status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ? R.string.update_signature
                    : status == PackageInstaller.STATUS_FAILURE_ABORTED ? R.string.update_cancelled
                    : R.string.update_failed;
            Toast.makeText(ctx, l.getString(msg), Toast.LENGTH_LONG).show();
        }
    }
}
