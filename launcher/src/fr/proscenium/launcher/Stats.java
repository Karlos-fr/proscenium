package fr.proscenium.launcher;

import android.app.ActivityManager;
import android.content.Context;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.Locale;

/** Charge du processeur (/proc/stat) et mémoire utilisée. */
final class Stats {
    private long lastTotal = -1, lastIdle;

    /** Faux quand le système interdit de lire la charge du processeur (Android 8 et plus récents). */
    static boolean cpuReadable() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/stat"))) {
            return r.readLine() != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** Pourcentage d'utilisation du CPU depuis l'appel précédent, ou -1 si illisible. */
    int cpuPercent() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/stat"))) {
            String[] f = r.readLine().trim().split("\\s+");
            long total = 0;
            for (int i = 1; i < f.length; i++) total += Long.parseLong(f[i]);
            long idle = Long.parseLong(f[4]) + (f.length > 5 ? Long.parseLong(f[5]) : 0);
            int pct = -1;
            if (lastTotal >= 0 && total > lastTotal) {
                pct = (int) Math.round(100.0 * (1 - (idle - lastIdle) / (double) (total - lastTotal)));
            }
            lastTotal = total;
            lastIdle = idle;
            return pct;
        } catch (Exception e) {
            return -1;
        }
    }

    /** Mémoire utilisée / totale, dans la langue de l'appli : « 0,7 / 1,4 Go » ou « 0.7 / 1.4 GB ». */
    static String ram(Context ctx) {
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        double gb = 1024.0 * 1024 * 1024;
        return ctx.getString(R.string.ram_format, (mi.totalMem - mi.availMem) / gb, mi.totalMem / gb);
    }
}
